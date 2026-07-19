from __future__ import annotations

import ast
import json
import logging
import os
import re
import selectors
import subprocess
import threading
import time
from pathlib import Path
from typing import Any


LOGGER = logging.getLogger("agentremote.host.metadata")
MODEL_CACHE_SECONDS = 300

FALLBACK_GROK_COMMANDS = [
    {
        "name": "compact",
        "description": "Compress conversation history to save context window",
        "argumentHint": "optional context about what to preserve",
    },
    {
        "name": "always-approve",
        "description": "Toggle always-approve mode (skip all permission prompts)",
        "argumentHint": "on|off",
    },
    {
        "name": "context",
        "description": "Show context window usage and session stats",
        "argumentHint": None,
    },
    {
        "name": "session-info",
        "description": "Show session details (model, turns, context usage)",
        "argumentHint": None,
    },
    {
        "name": "feedback",
        "description": "Send feedback about the current session",
        "argumentHint": "feedback text",
    },
    {
        "name": "goal",
        "description": "Set, manage, or check an autonomous goal",
        "argumentHint": "<objective> [--budget <tokens>] | status | pause | resume | clear",
    },
    {
        "name": "loop",
        "description": "Run a prompt on a recurring interval",
        "argumentHint": "[interval] <prompt>",
    },
]


def _non_blank(value: Any) -> str | None:
    text = str(value).strip() if value is not None else ""
    return text or None


def _positive_int(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    try:
        parsed = int(str(value).replace(",", ""))
    except (TypeError, ValueError):
        return None
    return parsed if parsed >= 0 else None


def _unquote(value: str) -> str:
    text = value.strip()
    if len(text) >= 2 and text[0] in {'"', "'"} and text[-1] == text[0]:
        try:
            parsed = ast.literal_eval(text)
        except (SyntaxError, ValueError):
            return text[1:-1]
        if isinstance(parsed, str):
            return parsed
    return text


def _frontmatter(path: Path) -> dict[str, str]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return {}
    if not lines or lines[0].strip() != "---":
        return {}
    result: dict[str, str] = {}
    index = 1
    while index < len(lines) and lines[index].strip() != "---":
        line = lines[index]
        match = re.match(r"^([A-Za-z0-9_-]+):\s*(.*)$", line)
        if not match:
            index += 1
            continue
        key, raw = match.groups()
        if raw in {">", ">-", "|", "|-"}:
            index += 1
            parts: list[str] = []
            while index < len(lines) and (not lines[index].strip() or lines[index][:1].isspace()):
                parts.append(lines[index].strip())
                index += 1
            if raw.startswith(">"):
                result[key] = " ".join(part for part in parts if part)
            else:
                result[key] = "\n".join(parts).strip()
            continue
        result[key] = _unquote(raw)
        index += 1

    # Grok prefers metadata.short-description when present.
    for line_index, line in enumerate(lines[1:index], start=1):
        if line.strip() != "metadata:":
            continue
        for nested in lines[line_index + 1 : index]:
            if nested and not nested[:1].isspace():
                break
            short = re.match(r"^\s+short-description:\s*(.*)$", nested)
            if short:
                result["short-description"] = _unquote(short.group(1))
                break
    return result


class MetadataProvider:
    """Reads backend-owned metadata without taking ownership of durable turns."""

    def __init__(
        self,
        grok_bin: str = "grok",
        codex_bin: str = "codex",
        home: str | Path | None = None,
        probe_grok: bool = True,
    ):
        self.home = Path(home).expanduser() if home is not None else Path.home()
        if home is None:
            self.grok_home = Path(os.environ.get("GROK_HOME") or self.home / ".grok").expanduser()
            self.codex_home = Path(os.environ.get("CODEX_HOME") or self.home / ".codex").expanduser()
        else:
            self.grok_home = self.home / ".grok"
            self.codex_home = self.home / ".codex"
        self.grok_bin = grok_bin
        self.codex_bin = codex_bin
        self.probe_grok = probe_grok
        self._probe_lock = threading.Lock()
        self._grok_models_at = 0.0
        self._grok_model_state: dict[str, Any] = {}
        self._grok_initial_commands: list[dict[str, Any]] = []
        self._codex_models_at = 0.0
        self._codex_model_catalog: list[dict[str, Any]] = []

    def metadata_for(self, session: dict[str, Any]) -> dict[str, Any]:
        model_override = _non_blank(session.get("modelOverride"))
        if model_override is not None:
            selected = next(
                (model for model in self.models_for(str(session.get("backend")))
                 if model["id"] == model_override),
                None,
            )
            if selected is not None:
                return {
                    "modelId": selected["id"],
                    "modelName": selected["name"],
                    "reasoningEffort": _non_blank(session.get("reasoningEffort"))
                    or selected.get("defaultReasoningEffort"),
                    "contextWindowTokens": selected.get("contextWindowTokens"),
                }
        if session.get("backend") == "codex":
            return self._codex_defaults()
        model_state, _ = self._grok_discovery()
        summary = self._grok_summary(session)
        model_id = _non_blank(summary.get("current_model_id")) or _non_blank(
            model_state.get("currentModelId")
        )
        model = self._grok_model(model_state, model_id)
        model_meta = model.get("_meta") if isinstance(model.get("_meta"), dict) else {}
        return {
            "modelId": model_id or _non_blank(model.get("modelId")),
            "modelName": _non_blank(model.get("name")) or model_id,
            "reasoningEffort": _non_blank(summary.get("reasoning_effort"))
            or _non_blank(model_meta.get("reasoningEffort")),
            "contextWindowTokens": _positive_int(model_meta.get("totalContextTokens")),
        }

    def models_for(self, backend: str) -> list[dict[str, Any]]:
        if backend == "grok":
            state, _ = self._grok_discovery()
            current = _non_blank(state.get("currentModelId"))
            models = state.get("availableModels")
            if not isinstance(models, list):
                return []
            result: list[dict[str, Any]] = []
            for raw in models:
                if not isinstance(raw, dict):
                    continue
                model_id = _non_blank(raw.get("modelId"))
                if model_id is None:
                    continue
                meta = raw.get("_meta") if isinstance(raw.get("_meta"), dict) else {}
                efforts = []
                for effort in meta.get("reasoningEfforts") or []:
                    if not isinstance(effort, dict):
                        continue
                    value = _non_blank(effort.get("value")) or _non_blank(effort.get("id"))
                    if value and value not in efforts:
                        efforts.append(value)
                result.append(
                    {
                        "id": model_id,
                        "name": _non_blank(raw.get("name")) or model_id,
                        "description": _non_blank(raw.get("description")),
                        "contextWindowTokens": _positive_int(meta.get("totalContextTokens")),
                        "reasoningEfforts": efforts,
                        "defaultReasoningEffort": _non_blank(meta.get("reasoningEffort")),
                        "isDefault": model_id == current,
                    }
                )
            return result
        if backend == "codex":
            return self._codex_models()
        return []

    def commands_for(self, backend: str) -> list[dict[str, Any]]:
        if backend != "grok":
            return []
        _, discovered = self._grok_discovery()
        commands: list[dict[str, Any]] = []
        seen: set[str] = set()

        def add(command: dict[str, Any]) -> None:
            name = _non_blank(command.get("name"))
            if name is None or name.lower() in seen:
                return
            seen.add(name.lower())
            input_value = command.get("input")
            hint = command.get("argumentHint")
            if hint is None and isinstance(input_value, dict):
                hint = input_value.get("hint")
            commands.append(
                {
                    "name": name,
                    "description": _non_blank(command.get("description")) or "Agent command",
                    "argumentHint": _non_blank(hint),
                }
            )

        for command in discovered or FALLBACK_GROK_COMMANDS:
            add(command)
        # These session commands are not present in Grok's initialize bootstrap list.
        for command in FALLBACK_GROK_COMMANDS:
            add(command)
        for root in (self.grok_home / "skills", self.grok_home / "bundled" / "skills"):
            if not root.is_dir():
                continue
            for manifest in sorted(root.glob("*/SKILL.md")):
                data = _frontmatter(manifest)
                name = _non_blank(data.get("name")) or manifest.parent.name
                description = (
                    _non_blank(data.get("short-description"))
                    or _non_blank(data.get("description"))
                    or "Grok skill"
                )
                add(
                    {
                        "name": name,
                        "description": description,
                        "argumentHint": data.get("argument-hint"),
                    }
                )
        return commands

    def parse_session_info(self, text: str) -> dict[str, Any]:
        metadata: dict[str, Any] = {}
        model = re.search(r"(?im)^\*\*Model:\*\*\s*([^\n]+)", text)
        if model:
            metadata["modelId"] = model.group(1).strip()
            metadata["modelName"] = model.group(1).strip()
        context = re.search(
            r"(?im)^\*\*Context:\*\*\s*([\d,]+)\s*/\s*([\d,]+)\s+tokens",
            text,
        )
        if context:
            metadata["usedTokens"] = _positive_int(context.group(1))
            metadata["contextWindowTokens"] = _positive_int(context.group(2))
        return metadata

    @staticmethod
    def context_report(session: dict[str, Any]) -> str:
        model = _non_blank(session.get("modelName")) or _non_blank(session.get("modelId"))
        effort = _non_blank(session.get("reasoningEffort"))
        used = _positive_int(session.get("usedTokens"))
        size = _positive_int(session.get("contextWindowTokens"))
        lines = [
            "**Model**",
            model or "Not reported",
            "",
            "**Reasoning effort**",
            effort.capitalize() if effort else "Not reported",
            "",
            "**Context window**",
        ]
        if used is not None and size:
            percent = used / size * 100
            lines.append(f"{used:,} / {size:,} tokens ({percent:.1f}%)")
            lines.append(f"{max(0, size - used):,} tokens remaining")
        elif used is not None:
            lines.append(f"{used:,} tokens used")
        elif size is not None:
            lines.append(f"{size:,} token capacity; current use not reported")
        else:
            lines.append("Token usage has not been reported yet.")
        return "\n".join(lines)

    @staticmethod
    def compact_report(session: dict[str, Any]) -> str:
        used = _positive_int(session.get("usedTokens"))
        size = _positive_int(session.get("contextWindowTokens"))
        lines = ["Conversation history compacted successfully."]
        if used is not None and size:
            lines.extend(["", f"**Context after compaction:** {used:,} / {size:,} tokens"])
        return "\n".join(lines)

    def _codex_defaults(self) -> dict[str, Any]:
        values: dict[str, str] = {}
        try:
            lines = (self.codex_home / "config.toml").read_text(encoding="utf-8").splitlines()
        except OSError:
            lines = []
        for line in lines:
            match = re.match(r"^\s*(model|model_reasoning_effort)\s*=\s*(.+?)\s*$", line)
            if match:
                values[match.group(1)] = _unquote(match.group(2))
        model = _non_blank(values.get("model"))
        return {
            "modelId": model,
            "modelName": model,
            "reasoningEffort": _non_blank(values.get("model_reasoning_effort")),
        }

    def _codex_models(self) -> list[dict[str, Any]]:
        now = time.monotonic()
        with self._probe_lock:
            if self._codex_model_catalog and now - self._codex_models_at < MODEL_CACHE_SECONDS:
                return list(self._codex_model_catalog)
            try:
                environment = os.environ.copy()
                environment["CODEX_HOME"] = str(self.codex_home)
                completed = subprocess.run(
                    [self.codex_bin, "debug", "models"],
                    check=True,
                    capture_output=True,
                    text=True,
                    timeout=15,
                    env=environment,
                )
                payload = json.loads(completed.stdout)
                raw_models = payload.get("models") if isinstance(payload, dict) else payload
                catalog: list[dict[str, Any]] = []
                for raw in raw_models or []:
                    if not isinstance(raw, dict) or raw.get("visibility") == "hide":
                        continue
                    model_id = _non_blank(raw.get("slug")) or _non_blank(raw.get("id"))
                    if model_id is None:
                        continue
                    efforts = []
                    for effort in raw.get("supported_reasoning_levels") or []:
                        if not isinstance(effort, dict):
                            continue
                        value = _non_blank(effort.get("effort"))
                        if value and value not in efforts:
                            efforts.append(value)
                    catalog.append(
                        {
                            "id": model_id,
                            "name": _non_blank(raw.get("display_name")) or model_id,
                            "description": _non_blank(raw.get("description")),
                            "contextWindowTokens": _positive_int(raw.get("context_window")),
                            "reasoningEfforts": efforts,
                            "defaultReasoningEffort": _non_blank(raw.get("default_reasoning_level")),
                            "isDefault": False,
                        }
                    )
                defaults = self._codex_defaults()
                default_id = defaults.get("modelId")
                for model in catalog:
                    model["isDefault"] = model["id"] == default_id
                self._codex_model_catalog = catalog
                self._codex_models_at = now
            except Exception as error:
                LOGGER.warning("Could not read Codex model catalog: %s", error)
                if not self._codex_model_catalog:
                    return []
            return list(self._codex_model_catalog)

    def _grok_summary(self, session: dict[str, Any]) -> dict[str, Any]:
        backend_id = _non_blank(session.get("backendSessionId"))
        if backend_id is None:
            return {}
        for candidate in (self.grok_home / "sessions").glob(f"*/{backend_id}/summary.json"):
            try:
                value = json.loads(candidate.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            info = value.get("info") if isinstance(value.get("info"), dict) else {}
            if _non_blank(info.get("cwd")) == _non_blank(session.get("cwd")):
                return value
        return {}

    @staticmethod
    def _grok_model(model_state: dict[str, Any], model_id: str | None) -> dict[str, Any]:
        models = model_state.get("availableModels")
        if not isinstance(models, list):
            return {}
        for model in models:
            if not isinstance(model, dict):
                continue
            if model_id is None or _non_blank(model.get("modelId")) == model_id:
                return model
        return {}

    def _grok_discovery(self) -> tuple[dict[str, Any], list[dict[str, Any]]]:
        with self._probe_lock:
            now = time.monotonic()
            if self._grok_models_at and now - self._grok_models_at < MODEL_CACHE_SECONDS:
                return self._grok_model_state, self._grok_initial_commands
            if not self.probe_grok:
                return self._grok_model_state, self._grok_initial_commands
            try:
                result = self._probe_grok_initialize()
                meta = result.get("_meta") if isinstance(result.get("_meta"), dict) else {}
                state = meta.get("modelState")
                commands = meta.get("availableCommands")
                if not isinstance(state, dict) or not isinstance(
                    state.get("availableModels"), list
                ):
                    raise RuntimeError("Grok ACP initialize did not return a model catalog")
                self._grok_model_state = state
                self._grok_initial_commands = [
                    command for command in commands or [] if isinstance(command, dict)
                ]
                self._grok_models_at = now
            except Exception as error:
                LOGGER.warning("Could not read Grok model/command metadata: %s", error)
            return self._grok_model_state, self._grok_initial_commands

    def _probe_grok_initialize(self) -> dict[str, Any]:
        selector: selectors.BaseSelector | None = None
        process = subprocess.Popen(
            [self.grok_bin, "agent", "--no-leader", "stdio"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            bufsize=1,
        )
        try:
            assert process.stdin is not None
            assert process.stdout is not None
            request = {
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": 1,
                    "clientCapabilities": {},
                    "clientInfo": {
                        "name": "agentremote_host",
                        "title": "AgentRemote durable host",
                        "version": "1",
                    },
                },
            }
            process.stdin.write(json.dumps(request, separators=(",", ":")) + "\n")
            process.stdin.flush()
            selector = selectors.DefaultSelector()
            selector.register(process.stdout, selectors.EVENT_READ)
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                ready = selector.select(timeout=max(0.0, deadline - time.monotonic()))
                if not ready:
                    break
                line = process.stdout.readline()
                if not line:
                    break
                message = json.loads(line)
                if message.get("id") != 1:
                    continue
                if message.get("error"):
                    raise RuntimeError(str(message["error"]))
                result = message.get("result")
                return result if isinstance(result, dict) else {}
            raise TimeoutError("Grok ACP initialize timed out")
        finally:
            if selector is not None:
                selector.close()
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=3)
            if process.stdin is not None:
                process.stdin.close()
            if process.stdout is not None:
                process.stdout.close()

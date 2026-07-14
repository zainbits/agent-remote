from __future__ import annotations

import json
import logging
import os
import queue
import subprocess
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .store import JobStore


LOGGER = logging.getLogger("agentremote.host.catalog")
DEFAULT_SYNC_TTL_SECONDS = 30.0
FAILED_SYNC_TTL_SECONDS = 5.0


def _iso_epoch_seconds(value: Any) -> str | None:
    try:
        return datetime.fromtimestamp(int(value), timezone.utc).isoformat().replace("+00:00", "Z")
    except (TypeError, ValueError, OSError):
        return None


def _content_text(content: Any) -> str:
    if isinstance(content, str):
        return content
    if not isinstance(content, list):
        return ""
    parts: list[str] = []
    for item in content:
        if isinstance(item, str):
            parts.append(item)
        elif isinstance(item, dict):
            value = item.get("text") or item.get("content")
            if isinstance(value, str):
                parts.append(value)
    return "\n".join(parts)


class LegacyCatalog:
    """Lazily adopts existing CLI sessions into the durable catalog."""

    def __init__(
        self,
        store: JobStore,
        codex_bin: str = "codex",
        sync_ttl_seconds: float | None = None,
    ):
        self.store = store
        self.codex_bin = codex_bin
        if sync_ttl_seconds is None:
            try:
                sync_ttl_seconds = float(
                    os.environ.get(
                        "AGENTREMOTE_LEGACY_SYNC_TTL_SECONDS",
                        DEFAULT_SYNC_TTL_SECONDS,
                    )
                )
            except ValueError:
                sync_ttl_seconds = DEFAULT_SYNC_TTL_SECONDS
        self.sync_ttl_seconds = max(0.0, sync_ttl_seconds)
        self._sync_lock = threading.Lock()
        self._sync_deadlines: dict[tuple[str, str, int], float] = {}

    def sync(self, backend: str, cwd: str, limit: int) -> None:
        normalized_backend = backend.strip().lower()
        normalized_limit = max(1, min(limit, 200))
        key = (normalized_backend, str(Path(cwd).expanduser().resolve()), normalized_limit)

        # Session cards can refresh every two seconds while a turn is active.
        # Discovery is much heavier than the SQLite-backed status list (Codex
        # launches app-server), so make each key single-flight and reuse a
        # recent result. Holding the lock also prevents duplicate launches when
        # two Android refreshes arrive together.
        with self._sync_lock:
            now = time.monotonic()
            if now < self._sync_deadlines.get(key, 0.0):
                return
            try:
                if normalized_backend == "codex":
                    self._sync_codex(cwd, normalized_limit)
                elif normalized_backend == "grok":
                    self._sync_grok(cwd, normalized_limit)
                else:
                    return
            except Exception as error:
                # Legacy discovery must never make durable sessions unavailable.
                LOGGER.warning("Could not refresh legacy %s sessions: %s", normalized_backend, error)
                self._sync_deadlines[key] = time.monotonic() + min(
                    self.sync_ttl_seconds,
                    FAILED_SYNC_TTL_SECONDS,
                )
            else:
                self._sync_deadlines[key] = time.monotonic() + self.sync_ttl_seconds

    def cleanup_sessions(self) -> list[dict[str, Any]]:
        """List every top-level CLI session eligible for age-based maintenance."""
        with self._sync_lock:
            sessions: dict[tuple[str, str], dict[str, Any]] = {}
            for summary in self._all_grok_summaries():
                backend_id = str(summary.get("sessionId") or "").strip()
                if backend_id:
                    sessions[("grok", backend_id)] = {
                        "backend": "grok",
                        "backendSessionId": backend_id,
                        "updatedAt": summary.get("updatedAt"),
                    }
            for thread in self._all_codex_threads():
                backend_id = str(thread.get("id") or "").strip()
                if backend_id:
                    sessions[("codex", backend_id)] = {
                        "backend": "codex",
                        "backendSessionId": backend_id,
                        "updatedAt": _iso_epoch_seconds(thread.get("updatedAt")),
                    }
            return list(sessions.values())

    def invalidate(self) -> None:
        with self._sync_lock:
            self._sync_deadlines.clear()

    @staticmethod
    def _all_grok_summaries() -> list[dict[str, Any]]:
        try:
            from host.session_index import load_cleanup_summaries
        except ImportError:
            from session_index import load_cleanup_summaries

        return load_cleanup_summaries()

    def _all_codex_threads(self) -> list[dict[str, Any]]:
        threads: list[dict[str, Any]] = []
        for archived in (False, True):
            cursor: str | None = None
            seen_cursors: set[str] = set()
            while True:
                params: dict[str, Any] = {
                    "archived": archived,
                    "limit": 200,
                    "sourceKinds": ["cli", "vscode", "exec", "appServer"],
                    "sortKey": "updated_at",
                    "sortDirection": "desc",
                }
                if cursor:
                    params["cursor"] = cursor
                result = self._codex_rpc("thread/list", params)
                threads.extend(
                    thread
                    for thread in result.get("data") or []
                    if isinstance(thread, dict)
                )
                next_cursor = str(result.get("nextCursor") or "").strip()
                if not next_cursor or next_cursor in seen_cursors:
                    break
                seen_cursors.add(next_cursor)
                cursor = next_cursor
        return threads

    def import_history_if_needed(self, session_id: str) -> None:
        session = self.store.get_session(session_id)
        backend_id = session.get("backendSessionId")
        if not backend_id or self.store.has_messages(session_id):
            return
        try:
            if session["backend"] == "codex":
                history = self._codex_history(backend_id)
            else:
                history = self._grok_history(backend_id, session["cwd"])
            self.store.import_history(session_id, history)
        except Exception as error:
            LOGGER.warning("Could not import history for %s session %s: %s", session["backend"], session_id, error)

    def codex_thread_status(self, session: dict[str, Any]) -> dict[str, Any]:
        backend_id = str(session.get("backendSessionId") or "").strip()
        status = self._codex_status_base(session)
        if not backend_id:
            return status
        result = self._codex_rpc(
            "thread/read",
            {"threadId": backend_id, "includeTurns": False},
        )
        return self._merge_codex_thread_status(status, result.get("thread") or {})

    def codex_status(self, session: dict[str, Any]) -> dict[str, Any]:
        backend_id = str(session.get("backendSessionId") or "").strip()
        requests = [
            ("account/read", {"refreshToken": False}),
            ("account/rateLimits/read", {}),
        ]
        if backend_id:
            requests.append(
                ("thread/read", {"threadId": backend_id, "includeTurns": False}),
            )
        results = self._codex_rpc_many(requests)
        status = self._codex_status_base(session)
        thread_result = results.get("thread/read") or {}
        status = self._merge_codex_thread_status(status, thread_result.get("thread") or {})

        account = (results.get("account/read") or {}).get("account") or {}
        if isinstance(account, dict):
            status["account"] = {
                "type": account.get("type"),
                "email": account.get("email"),
                "planType": account.get("planType"),
            }

        limits_result = results.get("account/rateLimits/read") or {}
        limits_by_id = limits_result.get("rateLimitsByLimitId")
        rate_limits = None
        if isinstance(limits_by_id, dict):
            rate_limits = limits_by_id.get("codex")
        if not isinstance(rate_limits, dict):
            candidate = limits_result.get("rateLimits")
            rate_limits = candidate if isinstance(candidate, dict) else None
        if rate_limits is not None:
            status["rateLimits"] = rate_limits
        reset_credits = limits_result.get("rateLimitResetCredits")
        if isinstance(reset_credits, dict):
            status["rateLimitResetCreditsAvailable"] = reset_credits.get("availableCount")
        return status

    def _sync_codex(self, cwd: str, limit: int) -> None:
        result = self._codex_rpc(
            "thread/list",
            {
                "cwd": cwd,
                "limit": max(1, min(limit, 200)),
                "sourceKinds": ["cli", "vscode", "exec", "appServer"],
                "sortKey": "updated_at",
                "sortDirection": "desc",
            },
        )
        for thread in result.get("data") or []:
            if not isinstance(thread, dict):
                continue
            backend_id = str(thread.get("id") or "")
            thread_cwd = str(thread.get("cwd") or "")
            if not backend_id or thread_cwd != cwd:
                continue
            name = thread.get("name")
            preview = str(thread.get("preview") or "").strip()
            title = str(name).strip() if name is not None else ""
            if not title:
                title = (preview.splitlines() or ["New Codex thread"])[0][:120]
            self.store.import_session(
                backend="codex",
                backend_session_id=backend_id,
                cwd=thread_cwd,
                title=title or "New Codex thread",
                created_at=_iso_epoch_seconds(thread.get("createdAt")),
                updated_at=_iso_epoch_seconds(thread.get("updatedAt")),
                model_id=thread.get("model"),
            )

    def _sync_grok(self, cwd: str, limit: int) -> None:
        try:
            from host.session_index import load_summaries
        except ImportError:
            from session_index import load_summaries

        for summary in load_summaries(cwd, limit):
            backend_id = str(summary.get("sessionId") or "")
            if not backend_id:
                continue
            self.store.import_session(
                backend="grok",
                backend_session_id=backend_id,
                cwd=str(summary.get("cwd") or cwd),
                title=str(summary.get("title") or "New Grok session"),
                created_at=summary.get("createdAt"),
                updated_at=summary.get("updatedAt"),
                model_id=summary.get("modelId"),
            )

    def _codex_history(self, backend_session_id: str) -> list[dict[str, Any]]:
        result = self._codex_rpc(
            "thread/read",
            {"threadId": backend_session_id, "includeTurns": True},
        )
        thread = result.get("thread") or {}
        history: list[dict[str, Any]] = []
        for turn_index, turn in enumerate(thread.get("turns") or []):
            if not isinstance(turn, dict):
                continue
            turn_key = str(turn.get("id") or turn_index)
            for item_index, item in enumerate(turn.get("items") or []):
                if not isinstance(item, dict):
                    continue
                item_type = item.get("type")
                source_id = str(item.get("id") or f"{turn_key}:{item_index}")
                if item_type == "userMessage":
                    text = _content_text(item.get("content"))
                    role = "user"
                elif item_type == "agentMessage":
                    text = str(item.get("text") or "")
                    role = "assistant"
                elif item_type in {"reasoning", "plan"}:
                    text = _content_text(item.get("summary")) or str(item.get("text") or "")
                    role = "thought"
                elif item_type in {
                    "commandExecution",
                    "fileChange",
                    "mcpToolCall",
                    "dynamicToolCall",
                    "collabAgentToolCall",
                    "webSearch",
                }:
                    text, detail = self._codex_tool(item_type, item)
                    if text:
                        history.append(
                            {
                                "turnKey": turn_key,
                                "sourceId": source_id,
                                "role": "tool",
                                "text": text,
                                "kind": item_type,
                                "detail": detail,
                            }
                        )
                    continue
                else:
                    continue
                if text:
                    history.append(
                        {
                            "turnKey": turn_key,
                            "sourceId": source_id,
                            "role": role,
                            "text": text,
                        }
                    )
        return history

    @staticmethod
    def _codex_status_base(session: dict[str, Any]) -> dict[str, Any]:
        backend_id = str(session.get("backendSessionId") or "").strip()
        return {
            "modelId": session.get("modelId"),
            "modelName": session.get("modelName") or session.get("modelId"),
            "reasoningEffort": session.get("reasoningEffort"),
            "cwd": session.get("cwd"),
            "sessionId": backend_id or session.get("id"),
            "durableSessionId": session.get("id"),
            "contextUsedTokens": session.get("usedTokens"),
            "contextWindowTokens": session.get("contextWindowTokens"),
        }

    def _merge_codex_thread_status(
        self,
        status: dict[str, Any],
        thread: dict[str, Any],
    ) -> dict[str, Any]:
        merged = dict(status)
        if not isinstance(thread, dict):
            return merged
        for source, target in (
            ("id", "sessionId"),
            ("name", "threadName"),
            ("forkedFromId", "forkedFrom"),
            ("cwd", "cwd"),
            ("cliVersion", "cliVersion"),
            ("modelProvider", "modelProvider"),
        ):
            value = thread.get(source)
            if value is not None and str(value).strip():
                merged[target] = value
        rollout_path = thread.get("path")
        if rollout_path:
            merged.update(self._codex_rollout_status(Path(str(rollout_path))))
        return merged

    @classmethod
    def _codex_rollout_status(cls, path: Path) -> dict[str, Any]:
        if not path.is_file():
            return {}
        turn_context: dict[str, Any] | None = None
        token_info: dict[str, Any] | None = None
        agents_files: list[str] | None = None
        for event in cls._reverse_jsonl(path):
            event_type = event.get("type")
            payload = event.get("payload")
            if not isinstance(payload, dict):
                continue
            if turn_context is None and event_type == "turn_context":
                turn_context = payload
            elif (
                token_info is None
                and event_type == "event_msg"
                and payload.get("type") == "token_count"
                and isinstance(payload.get("info"), dict)
            ):
                token_info = payload["info"]
            elif agents_files is None and event_type == "world_state":
                state = payload.get("state")
                if isinstance(state, dict):
                    agents_files = cls._agents_md_paths(state.get("agents_md"))
            if turn_context is not None and token_info is not None and agents_files is not None:
                break

        status: dict[str, Any] = {}
        if turn_context is not None:
            collaboration = turn_context.get("collaboration_mode")
            collaboration_mode = (
                collaboration.get("mode") if isinstance(collaboration, dict) else collaboration
            )
            sandbox = turn_context.get("sandbox_policy")
            status.update(
                {
                    "modelId": turn_context.get("model"),
                    "modelName": turn_context.get("model"),
                    "reasoningEffort": turn_context.get("effort"),
                    "reasoningSummary": turn_context.get("summary"),
                    "approvalPolicy": turn_context.get("approval_policy"),
                    "approvalsReviewer": turn_context.get("approvals_reviewer"),
                    "collaborationMode": collaboration_mode,
                }
            )
            if isinstance(sandbox, dict):
                status["sandboxMode"] = sandbox.get("type")
                status["networkAccess"] = sandbox.get("network_access")
        if token_info is not None:
            last = token_info.get("last_token_usage")
            total = token_info.get("total_token_usage")
            if isinstance(last, dict):
                status["lastTokenUsage"] = cls._camel_token_usage(last)
                status["contextUsedTokens"] = last.get("total_tokens")
            if isinstance(total, dict):
                status["totalTokenUsage"] = cls._camel_token_usage(total)
            status["contextWindowTokens"] = token_info.get("model_context_window")
        if agents_files is not None:
            status["agentsFiles"] = agents_files
        return {key: value for key, value in status.items() if value is not None}

    @staticmethod
    def _camel_token_usage(usage: dict[str, Any]) -> dict[str, Any]:
        return {
            "inputTokens": usage.get("input_tokens"),
            "cachedInputTokens": usage.get("cached_input_tokens"),
            "outputTokens": usage.get("output_tokens"),
            "reasoningOutputTokens": usage.get("reasoning_output_tokens"),
            "totalTokens": usage.get("total_tokens"),
        }

    @classmethod
    def _agents_md_paths(cls, value: Any) -> list[str]:
        paths: list[str] = []

        def visit(item: Any) -> None:
            if isinstance(item, dict):
                path_value = item.get("path")
                directory = item.get("directory")
                if isinstance(path_value, str) and path_value.strip():
                    paths.append(path_value)
                elif isinstance(directory, str) and directory.strip():
                    paths.append(str(Path(directory) / "AGENTS.md"))
                for nested in item.values():
                    if isinstance(nested, (dict, list)):
                        visit(nested)
            elif isinstance(item, list):
                for nested in item:
                    visit(nested)

        visit(value)
        return list(dict.fromkeys(paths))

    @staticmethod
    def _reverse_jsonl(path: Path):
        with path.open("rb") as source:
            source.seek(0, 2)
            position = source.tell()
            buffer = b""
            while position > 0:
                size = min(65_536, position)
                position -= size
                source.seek(position)
                buffer = source.read(size) + buffer
                lines = buffer.split(b"\n")
                buffer = lines[0]
                for raw in reversed(lines[1:]):
                    if not raw.strip():
                        continue
                    try:
                        value = json.loads(raw)
                    except (UnicodeDecodeError, json.JSONDecodeError):
                        continue
                    if isinstance(value, dict):
                        yield value
            if buffer.strip():
                try:
                    value = json.loads(buffer)
                except (UnicodeDecodeError, json.JSONDecodeError):
                    return
                if isinstance(value, dict):
                    yield value

    @staticmethod
    def _codex_tool(item_type: str, item: dict[str, Any]) -> tuple[str, str | None]:
        if item_type == "commandExecution":
            title = _content_text(item.get("command")) or str(item.get("command") or "Command")
            detail = str(item.get("aggregatedOutput") or "")
        elif item_type == "fileChange":
            title = "File changes"
            detail = "\n".join(
                f"{change.get('kind', 'update')} · {change.get('path', '')}"
                for change in item.get("changes") or []
                if isinstance(change, dict)
            )
        elif item_type == "webSearch":
            title = f"Search · {item.get('query', '')}".rstrip(" ·")
            detail = None
        else:
            title = str(item.get("tool") or item.get("name") or item_type)
            detail = json.dumps(item, ensure_ascii=False, indent=2)
        return title[:300], detail[-16_000:] if detail else None

    def _grok_history(self, backend_session_id: str, cwd: str) -> list[dict[str, Any]]:
        try:
            from host.session_index import (
                fallback_bootstrap_count,
                first_turn_baseline,
                iter_jsonl,
                message_text,
                sessions_root,
            )
        except ImportError:
            from session_index import (
                fallback_bootstrap_count,
                first_turn_baseline,
                iter_jsonl,
                message_text,
                sessions_root,
            )

        session_dir: Path | None = None
        for candidate in sessions_root().glob(f"*/{backend_session_id}"):
            summary_path = candidate / "summary.json"
            try:
                summary = json.loads(summary_path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            if str((summary.get("info") or {}).get("cwd") or "") == cwd:
                session_dir = candidate
                break
        if session_dir is None:
            return []

        records = [item for item in iter_jsonl(session_dir / "chat_history.jsonl") if item.get("type") != "system"]
        baseline = first_turn_baseline(session_dir)
        if baseline is None:
            baseline = fallback_bootstrap_count(records)
        records = records[min(baseline, len(records)) :]

        history: list[dict[str, Any]] = []
        turn_index = -1
        for item_index, item in enumerate(records):
            item_type = item.get("type")
            if item_type == "user" and item.get("synthetic_reason") is None:
                turn_index += 1
                role = "user"
                text = message_text(item)
            elif item_type == "assistant":
                role = "assistant"
                text = message_text(item)
            elif item_type == "reasoning":
                role = "thought"
                text = _content_text(item.get("summary"))
            else:
                continue
            if text:
                history.append(
                    {
                        "turnKey": str(max(turn_index, 0)),
                        "sourceId": str(item.get("id") or item_index),
                        "role": role,
                        "text": text,
                    }
                )
        return history

    def _codex_rpc(self, method: str, params: dict[str, Any]) -> dict[str, Any]:
        return self._codex_rpc_many([(method, params)]).get(method, {})

    def _codex_rpc_many(
        self,
        requests_to_make: list[tuple[str, dict[str, Any]]],
    ) -> dict[str, dict[str, Any]]:
        process = subprocess.Popen(
            [self.codex_bin, "app-server"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            bufsize=1,
        )
        try:
            assert process.stdin is not None
            assert process.stdout is not None
            requests = [
                {
                    "method": "initialize",
                    "id": 1,
                    "params": {
                        "clientInfo": {
                            "name": "agentremote_host",
                            "title": "AgentRemote durable host",
                            "version": "1",
                        }
                    },
                },
                {"method": "initialized", "params": {}},
            ]
            request_ids: dict[int, str] = {}
            for request_id, (method, params) in enumerate(requests_to_make, start=2):
                requests.append({"method": method, "id": request_id, "params": params})
                request_ids[request_id] = method
            for request in requests:
                process.stdin.write(json.dumps(request, separators=(",", ":")) + "\n")
            process.stdin.flush()

            messages: queue.Queue[dict[str, Any] | None] = queue.Queue()

            def read_messages() -> None:
                assert process.stdout is not None
                try:
                    for line in process.stdout:
                        try:
                            value = json.loads(line)
                        except json.JSONDecodeError:
                            continue
                        if isinstance(value, dict):
                            messages.put(value)
                finally:
                    messages.put(None)

            reader = threading.Thread(target=read_messages, name="codex-rpc-reader", daemon=True)
            reader.start()
            deadline = time.monotonic() + 15
            results: dict[str, dict[str, Any]] = {}
            while time.monotonic() < deadline:
                try:
                    message = messages.get(timeout=max(0.0, deadline - time.monotonic()))
                except queue.Empty:
                    break
                if message is None:
                    break
                method = request_ids.get(message.get("id"))
                if method is None:
                    continue
                if message.get("error"):
                    LOGGER.warning(
                        "Codex app-server %s failed: %s",
                        method,
                        message["error"].get("message") or message["error"],
                    )
                    results[method] = {}
                else:
                    result = message.get("result")
                    results[method] = result if isinstance(result, dict) else {}
                if len(results) == len(request_ids):
                    return results
            missing = ", ".join(method for method in request_ids.values() if method not in results)
            raise TimeoutError(f"Codex app-server timed out during {missing}")
        finally:
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

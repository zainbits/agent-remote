from __future__ import annotations

import json
import logging
import selectors
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .store import JobStore


LOGGER = logging.getLogger("agentremote.host.catalog")


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

    def __init__(self, store: JobStore, codex_bin: str = "codex"):
        self.store = store
        self.codex_bin = codex_bin

    def sync(self, backend: str, cwd: str, limit: int) -> None:
        try:
            if backend == "codex":
                self._sync_codex(cwd, limit)
            elif backend == "grok":
                self._sync_grok(cwd, limit)
        except Exception as error:
            # Legacy discovery must never make durable sessions unavailable.
            LOGGER.warning("Could not refresh legacy %s sessions: %s", backend, error)

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
        selector: selectors.BaseSelector | None = None
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
                {"method": method, "id": 2, "params": params},
            ]
            for request in requests:
                process.stdin.write(json.dumps(request, separators=(",", ":")) + "\n")
            process.stdin.flush()

            selector = selectors.DefaultSelector()
            selector.register(process.stdout, selectors.EVENT_READ)
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                ready = selector.select(timeout=max(0.0, deadline - time.monotonic()))
                if not ready:
                    break
                line = process.stdout.readline()
                if not line:
                    break
                message = json.loads(line)
                if message.get("id") != 2:
                    continue
                if message.get("error"):
                    raise RuntimeError(str(message["error"].get("message") or message["error"]))
                return message.get("result") or {}
            raise TimeoutError(f"Codex app-server timed out during {method}")
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

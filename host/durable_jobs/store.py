from __future__ import annotations

import json
import os
import sqlite3
import threading
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ACTIVE_STATUSES = {"queued", "running", "cancelling"}


class StoreError(RuntimeError):
    pass


class NotFoundError(StoreError):
    pass


class ConflictError(StoreError):
    pass


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


class JobStore:
    """Small SQLite repository with one short-lived connection per transaction."""

    def __init__(self, database_path: str | Path):
        self.database_path = Path(database_path).expanduser().resolve()
        self.database_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        self._schema_lock = threading.Lock()
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.database_path, timeout=30.0)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA busy_timeout = 30000")
        return connection

    def _initialize(self) -> None:
        schema = Path(__file__).with_name("schema.sql").read_text(encoding="utf-8")
        with self._schema_lock, self._connect() as connection:
            connection.execute("PRAGMA journal_mode = WAL")
            connection.execute("PRAGMA synchronous = FULL")
            connection.executescript(schema)
            columns = {
                row["name"]
                for row in connection.execute("PRAGMA table_info(sessions)").fetchall()
            }
            if "model_name" not in columns:
                connection.execute("ALTER TABLE sessions ADD COLUMN model_name TEXT")
            if "model_override" not in columns:
                connection.execute("ALTER TABLE sessions ADD COLUMN model_override TEXT")
            if "codex_full_access" not in columns:
                connection.execute(
                    "ALTER TABLE sessions ADD COLUMN codex_full_access INTEGER NOT NULL DEFAULT 1"
                )
            connection.execute("PRAGMA user_version = 4")
        os.chmod(self.database_path, 0o600)

    @staticmethod
    def _event(
        connection: sqlite3.Connection,
        session_id: str,
        turn_id: str | None,
        event_type: str,
        payload: dict[str, Any],
        now: str | None = None,
    ) -> int:
        cursor = connection.execute(
            """
            INSERT INTO events(session_id, turn_id, type, payload_json, created_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            (session_id, turn_id, event_type, json.dumps(payload, separators=(",", ":")), now or utc_now()),
        )
        return int(cursor.lastrowid)

    @staticmethod
    def _session_dict(row: sqlite3.Row) -> dict[str, Any]:
        return {
            "id": row["id"],
            "backend": row["backend"],
            "backendSessionId": row["backend_session_id"],
            "cwd": row["cwd"],
            "title": row["title"],
            "status": row["status"],
            "activeTurnId": row["active_turn_id"],
            "modelId": row["model_id"],
            "modelName": row["model_name"],
            "modelOverride": row["model_override"],
            "reasoningEffort": row["reasoning_effort"],
            "usedTokens": row["used_tokens"],
            "contextWindowTokens": row["context_window_tokens"],
            "codexFullAccess": bool(row["codex_full_access"]),
            "lastError": row["last_error"],
            "createdAt": row["created_at"],
            "updatedAt": row["updated_at"],
            "messageCount": row["message_count"] if "message_count" in row.keys() else None,
        }

    @staticmethod
    def _turn_dict(row: sqlite3.Row) -> dict[str, Any]:
        return {
            "id": row["id"],
            "sessionId": row["session_id"],
            "prompt": row["prompt"],
            "status": row["status"],
            "stopReason": row["stop_reason"],
            "error": row["error"],
            "cancellationRequested": bool(row["cancellation_requested"]),
            "workerPid": row["worker_pid"],
            "createdAt": row["created_at"],
            "startedAt": row["started_at"],
            "completedAt": row["completed_at"],
        }

    @staticmethod
    def _message_dict(row: sqlite3.Row) -> dict[str, Any]:
        return {
            "id": row["id"],
            "turnId": row["turn_id"],
            "role": row["role"],
            "text": row["text"],
            "status": row["status"],
            "kind": row["kind"],
            "detail": row["detail"],
            "createdAt": row["created_at"],
            "updatedAt": row["updated_at"],
        }

    def create_session(
        self,
        backend: str,
        cwd: str,
        codex_full_access: bool = True,
    ) -> dict[str, Any]:
        normalized_backend = backend.strip().lower()
        if normalized_backend not in {"grok", "codex"}:
            raise StoreError("backend must be grok or codex")
        path = Path(cwd).expanduser().resolve()
        if not path.is_dir():
            raise StoreError(f"Working directory does not exist: {path}")
        session_id = str(uuid.uuid4())
        now = utc_now()
        title = f"New {normalized_backend.title()} session"
        effective_full_access = normalized_backend == "codex" and codex_full_access
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO sessions(
                    id, backend, cwd, title, status, codex_full_access, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'idle', ?, ?, ?)
                """,
                (
                    session_id,
                    normalized_backend,
                    str(path),
                    title,
                    int(effective_full_access),
                    now,
                    now,
                ),
            )
            self._event(
                connection,
                session_id,
                None,
                "session.created",
                {"status": "idle", "codexFullAccess": effective_full_access},
                now,
            )
        return self.get_session(session_id)

    def set_model(
        self,
        session_id: str,
        model_id: str,
        model_name: str,
        reasoning_effort: str | None,
        context_window_tokens: int | None,
    ) -> dict[str, Any]:
        now = utc_now()
        with self._connect() as connection:
            current = connection.execute(
                "SELECT * FROM sessions WHERE id = ?",
                (session_id,),
            ).fetchone()
            if current is None:
                raise NotFoundError("Session not found")
            if current["status"] in ACTIVE_STATUSES:
                raise ConflictError("Cannot change model while a turn is active")
            connection.execute(
                """
                UPDATE sessions SET
                    model_override = ?,
                    model_id = ?,
                    model_name = ?,
                    reasoning_effort = ?,
                    context_window_tokens = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                (
                    model_id,
                    model_id,
                    model_name,
                    reasoning_effort,
                    context_window_tokens,
                    now,
                    session_id,
                ),
            )
            row = connection.execute(
                "SELECT * FROM sessions WHERE id = ?",
                (session_id,),
            ).fetchone()
            if row is None:
                raise NotFoundError("Session not found")
            session = self._session_dict(row)
            self._event(
                connection,
                session_id,
                None,
                "usage.updated",
                {
                    "modelId": session["modelId"],
                    "modelName": session["modelName"],
                    "reasoningEffort": session["reasoningEffort"],
                    "usedTokens": session["usedTokens"],
                    "contextWindowTokens": session["contextWindowTokens"],
                },
                now,
            )
        return session

    def import_session(
        self,
        backend: str,
        backend_session_id: str,
        cwd: str,
        title: str,
        created_at: str | None = None,
        updated_at: str | None = None,
        model_id: str | None = None,
    ) -> dict[str, Any]:
        """Register a pre-durable CLI session without modifying its backend history."""
        if not backend_session_id:
            raise StoreError("Legacy session has no backend session id")
        local_id: str
        with self._connect() as connection:
            existing = connection.execute(
                "SELECT id FROM sessions WHERE backend = ? AND backend_session_id = ?",
                (backend, backend_session_id),
            ).fetchone()
            if existing is not None:
                local_id = existing["id"]
            else:
                local_id = str(uuid.uuid4())
                now = utc_now()
                created = created_at or now
                updated = updated_at or created
                inserted = connection.execute(
                    """
                    INSERT OR IGNORE INTO sessions(
                        id, backend, backend_session_id, cwd, title, status, model_id,
                        created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, 'idle', ?, ?, ?)
                    """,
                    (
                        local_id,
                        backend,
                        backend_session_id,
                        str(Path(cwd).expanduser().resolve()),
                        title[:200] or f"Imported {backend.title()} session",
                        model_id,
                        created,
                        updated,
                    ),
                )
                if inserted.rowcount:
                    self._event(
                        connection,
                        local_id,
                        None,
                        "session.imported",
                        {"backendSessionId": backend_session_id},
                        now,
                    )
                else:
                    winner = connection.execute(
                        "SELECT id FROM sessions WHERE backend = ? AND backend_session_id = ?",
                        (backend, backend_session_id),
                    ).fetchone()
                    if winner is None:
                        raise StoreError("Could not adopt legacy session")
                    local_id = winner["id"]
        return self.get_session(local_id)

    def import_history(self, session_id: str, history: list[dict[str, Any]]) -> bool:
        """Import normalized legacy messages once; future turns remain ordinary durable jobs."""
        if not history:
            return False
        now = utc_now()
        with self._connect() as connection:
            session = connection.execute("SELECT * FROM sessions WHERE id = ?", (session_id,)).fetchone()
            if session is None:
                raise NotFoundError("Session not found")
            existing = connection.execute(
                "SELECT 1 FROM messages WHERE session_id = ? LIMIT 1",
                (session_id,),
            ).fetchone()
            if existing is not None:
                return False
            turns: dict[str, str] = {}
            prompts: dict[str, str] = {}
            for item in history:
                key = str(item.get("turnKey") or "0")
                if item.get("role") == "user" and key not in prompts:
                    prompts[key] = str(item.get("text") or "")
            for item_index, item in enumerate(history):
                role = str(item.get("role") or "")
                text = str(item.get("text") or "")
                if role not in {"user", "assistant", "thought", "tool"} or not text:
                    continue
                key = str(item.get("turnKey") or "0")
                turn_id = turns.get(key)
                if turn_id is None:
                    turn_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"agentremote:{session_id}:{key}"))
                    turns[key] = turn_id
                    connection.execute(
                        """
                        INSERT OR IGNORE INTO turns(
                            id, session_id, prompt, status, stop_reason,
                            created_at, started_at, completed_at
                        ) VALUES (?, ?, ?, 'completed', 'imported', ?, ?, ?)
                        """,
                        (turn_id, session_id, prompts.get(key, "Imported turn"), now, now, now),
                    )
                message_id = str(
                    uuid.uuid5(
                        uuid.NAMESPACE_URL,
                        f"agentremote:{session_id}:{key}:{item_index}:{item.get('sourceId') or text}",
                    )
                )
                connection.execute(
                    """
                    INSERT OR IGNORE INTO messages(
                        id, session_id, turn_id, role, text, status, kind, detail,
                        created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, 'completed', ?, ?, ?, ?)
                    """,
                    (
                        message_id,
                        session_id,
                        turn_id,
                        role,
                        text,
                        item.get("kind"),
                        item.get("detail"),
                        now,
                        now,
                    ),
                )
            connection.execute(
                "UPDATE sessions SET updated_at = MAX(updated_at, ?) WHERE id = ?",
                (now, session_id),
            )
        return True

    def has_messages(self, session_id: str) -> bool:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT 1 FROM messages WHERE session_id = ? LIMIT 1",
                (session_id,),
            ).fetchone()
        return row is not None

    def get_session(self, session_id: str) -> dict[str, Any]:
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT s.*,
                       (SELECT COUNT(*) FROM messages m
                        WHERE m.session_id = s.id AND m.role IN ('user', 'assistant')) AS message_count
                FROM sessions s WHERE s.id = ?
                """,
                (session_id,),
            ).fetchone()
        if row is None:
            raise NotFoundError("Session not found")
        return self._session_dict(row)

    def list_sessions(self, backend: str, cwd: str, limit: int = 50) -> list[dict[str, Any]]:
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT s.*,
                       (SELECT COUNT(*) FROM messages m
                        WHERE m.session_id = s.id AND m.role IN ('user', 'assistant')) AS message_count
                FROM sessions s
                WHERE s.backend = ? AND s.cwd = ?
                ORDER BY s.updated_at DESC
                LIMIT ?
                """,
                (backend.lower(), str(Path(cwd).expanduser().resolve()), max(1, min(limit, 200))),
            ).fetchall()
        return [self._session_dict(row) for row in rows]

    def session_bundle(self, session_id: str) -> dict[str, Any]:
        with self._connect() as connection:
            # One read snapshot keeps messages and the returned event cursor aligned.
            connection.execute("BEGIN")
            session_row = connection.execute(
                """
                SELECT s.*,
                       (SELECT COUNT(*) FROM messages m
                        WHERE m.session_id = s.id AND m.role IN ('user', 'assistant')) AS message_count
                FROM sessions s WHERE s.id = ?
                """,
                (session_id,),
            ).fetchone()
            if session_row is None:
                raise NotFoundError("Session not found")
            messages = connection.execute(
                "SELECT * FROM messages WHERE session_id = ? ORDER BY rowid",
                (session_id,),
            ).fetchall()
            latest_event = connection.execute(
                "SELECT COALESCE(MAX(id), 0) FROM events WHERE session_id = ?",
                (session_id,),
            ).fetchone()[0]
        return {
            "session": self._session_dict(session_row),
            "messages": [self._message_dict(row) for row in messages],
            "latestEventId": int(latest_event),
        }

    def create_turn(self, session_id: str, prompt: str) -> dict[str, Any]:
        text = prompt.strip()
        if not text:
            raise StoreError("Prompt cannot be empty")
        turn_id = str(uuid.uuid4())
        message_id = str(uuid.uuid4())
        now = utc_now()
        with self._connect() as connection:
            session = connection.execute("SELECT * FROM sessions WHERE id = ?", (session_id,)).fetchone()
            if session is None:
                raise NotFoundError("Session not found")
            if session["status"] in ACTIVE_STATUSES:
                raise ConflictError("This session already has an active turn")
            connection.execute(
                """
                INSERT INTO turns(id, session_id, prompt, status, created_at)
                VALUES (?, ?, ?, 'queued', ?)
                """,
                (turn_id, session_id, text, now),
            )
            connection.execute(
                """
                INSERT INTO messages(id, session_id, turn_id, role, text, created_at, updated_at)
                VALUES (?, ?, ?, 'user', ?, ?, ?)
                """,
                (message_id, session_id, turn_id, text, now, now),
            )
            title = session["title"]
            if title.startswith("New "):
                title = text.splitlines()[0][:120] or title
            connection.execute(
                """
                UPDATE sessions
                SET title = ?, status = 'queued', active_turn_id = ?, last_error = NULL, updated_at = ?
                WHERE id = ?
                """,
                (title, turn_id, now, session_id),
            )
            self._event(
                connection,
                session_id,
                turn_id,
                "message.created",
                {"messageId": message_id, "role": "user", "text": text},
                now,
            )
            self._event(connection, session_id, turn_id, "turn.queued", {"turnId": turn_id}, now)
            row = connection.execute("SELECT * FROM turns WHERE id = ?", (turn_id,)).fetchone()
        return self._turn_dict(row)

    def get_turn(self, turn_id: str) -> dict[str, Any]:
        with self._connect() as connection:
            row = connection.execute("SELECT * FROM turns WHERE id = ?", (turn_id,)).fetchone()
        if row is None:
            raise NotFoundError("Turn not found")
        return self._turn_dict(row)

    def mark_turn_running(self, turn_id: str, worker_pid: int) -> bool:
        now = utc_now()
        with self._connect() as connection:
            row = connection.execute("SELECT * FROM turns WHERE id = ?", (turn_id,)).fetchone()
            if row is None:
                raise NotFoundError("Turn not found")
            if row["status"] != "queued" or row["cancellation_requested"]:
                return False
            connection.execute(
                "UPDATE turns SET status = 'running', worker_pid = ?, started_at = ? WHERE id = ?",
                (worker_pid, now, turn_id),
            )
            connection.execute(
                "UPDATE sessions SET status = 'running', updated_at = ? WHERE id = ?",
                (now, row["session_id"]),
            )
            self._event(
                connection,
                row["session_id"],
                turn_id,
                "turn.started",
                {"turnId": turn_id},
                now,
            )
        return True

    def set_backend_session_id(self, session_id: str, backend_session_id: str) -> None:
        if not backend_session_id:
            return
        now = utc_now()
        with self._connect() as connection:
            current = connection.execute(
                "SELECT backend FROM sessions WHERE id = ?",
                (session_id,),
            ).fetchone()
            if current is None:
                raise NotFoundError("Session not found")
            duplicate = connection.execute(
                """
                SELECT s.id, s.status,
                       (SELECT COUNT(*) FROM messages m WHERE m.session_id = s.id) AS message_count
                FROM sessions s
                WHERE s.backend = ? AND s.backend_session_id = ? AND s.id != ?
                """,
                (current["backend"], backend_session_id, session_id),
            ).fetchone()
            if duplicate is not None:
                if duplicate["status"] in ACTIVE_STATUSES or duplicate["message_count"]:
                    raise ConflictError("Backend session is already owned by another durable session")
                # A catalog refresh can discover a just-started CLI thread before its
                # worker consumes thread.started. Remove that empty catalog placeholder.
                connection.execute("DELETE FROM sessions WHERE id = ?", (duplicate["id"],))
            connection.execute(
                "UPDATE sessions SET backend_session_id = ?, updated_at = ? WHERE id = ?",
                (backend_session_id, now, session_id),
            )
            self._event(
                connection,
                session_id,
                None,
                "session.backend_linked",
                {"backendSessionId": backend_session_id},
                now,
            )

    def append_message_delta(
        self,
        session_id: str,
        turn_id: str,
        message_id: str,
        role: str,
        delta: str,
    ) -> None:
        if not delta:
            return
        now = utc_now()
        with self._connect() as connection:
            connection.execute(
                """
                INSERT OR IGNORE INTO messages(
                    id, session_id, turn_id, role, text, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, '', 'in_progress', ?, ?)
                """,
                (message_id, session_id, turn_id, role, now, now),
            )
            connection.execute(
                "UPDATE messages SET text = text || ?, status = 'in_progress', updated_at = ? WHERE id = ?",
                (delta, now, message_id),
            )
            self._event(
                connection,
                session_id,
                turn_id,
                "message.delta",
                {"messageId": message_id, "role": role, "delta": delta},
                now,
            )

    def complete_message(self, message_id: str) -> None:
        now = utc_now()
        with self._connect() as connection:
            row = connection.execute("SELECT * FROM messages WHERE id = ?", (message_id,)).fetchone()
            if row is None:
                return
            connection.execute(
                "UPDATE messages SET status = 'completed', updated_at = ? WHERE id = ?",
                (now, message_id),
            )
            self._event(
                connection,
                row["session_id"],
                row["turn_id"],
                "message.completed",
                {"messageId": message_id, "role": row["role"]},
                now,
            )

    def turn_role_text(self, turn_id: str, role: str) -> str:
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT text FROM messages WHERE turn_id = ? AND role = ? ORDER BY rowid",
                (turn_id, role),
            ).fetchall()
        return "\n".join(str(row["text"] or "") for row in rows).strip()

    def append_complete_message(
        self,
        session_id: str,
        turn_id: str,
        message_id: str,
        role: str,
        text: str,
    ) -> None:
        if not text:
            return
        self.append_message_delta(session_id, turn_id, message_id, role, text)
        self.complete_message(message_id)

    def upsert_tool(
        self,
        session_id: str,
        turn_id: str,
        message_id: str,
        title: str,
        status: str | None,
        kind: str | None,
        detail: str | None,
    ) -> None:
        now = utc_now()
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO messages(
                    id, session_id, turn_id, role, text, status, kind, detail, created_at, updated_at
                ) VALUES (?, ?, ?, 'tool', ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    text = excluded.text,
                    status = excluded.status,
                    kind = excluded.kind,
                    detail = excluded.detail,
                    updated_at = excluded.updated_at
                """,
                (message_id, session_id, turn_id, title, status, kind, detail, now, now),
            )
            self._event(
                connection,
                session_id,
                turn_id,
                "tool.updated",
                {
                    "messageId": message_id,
                    "title": title,
                    "status": status,
                    "kind": kind,
                    "detail": detail,
                },
                now,
            )

    def update_usage(self, session_id: str, usage: dict[str, Any]) -> dict[str, Any]:
        def optional_int(*keys: str) -> int | None:
            for key in keys:
                value = usage.get(key)
                if value is None or isinstance(value, bool):
                    continue
                try:
                    return max(0, int(value))
                except (TypeError, ValueError):
                    continue
            return None

        input_tokens = optional_int("input_tokens", "inputTokens")
        output_tokens = optional_int("output_tokens", "outputTokens")
        used_tokens = optional_int("used_tokens", "usedTokens", "totalTokens")
        if used_tokens is None and (input_tokens is not None or output_tokens is not None):
            used_tokens = (input_tokens or 0) + (output_tokens or 0)
        context_window_tokens = optional_int(
            "context_window_tokens",
            "contextWindowTokens",
            "contextSize",
        )
        model_id = str(usage.get("model_id") or usage.get("modelId") or "").strip() or None
        model_name = str(usage.get("model_name") or usage.get("modelName") or "").strip() or None
        reasoning_effort = str(
            usage.get("reasoning_effort") or usage.get("reasoningEffort") or ""
        ).strip() or None
        now = utc_now()
        with self._connect() as connection:
            current = connection.execute("SELECT * FROM sessions WHERE id = ?", (session_id,)).fetchone()
            if current is None:
                raise NotFoundError("Session not found")
            updates = {
                "model_id": model_id,
                "model_name": model_name,
                "reasoning_effort": reasoning_effort,
                "used_tokens": used_tokens,
                "context_window_tokens": context_window_tokens,
            }
            changed = any(
                value is not None and value != current[column]
                for column, value in updates.items()
            )
            if not changed:
                return self._session_dict(current)
            connection.execute(
                """
                UPDATE sessions SET
                    model_id = COALESCE(?, model_id),
                    model_name = COALESCE(?, model_name),
                    reasoning_effort = COALESCE(?, reasoning_effort),
                    used_tokens = COALESCE(?, used_tokens),
                    context_window_tokens = COALESCE(?, context_window_tokens),
                    updated_at = ?
                WHERE id = ?
                """,
                (
                    model_id,
                    model_name,
                    reasoning_effort,
                    used_tokens,
                    context_window_tokens,
                    now,
                    session_id,
                ),
            )
            row = connection.execute("SELECT * FROM sessions WHERE id = ?", (session_id,)).fetchone()
            if row is None:
                raise NotFoundError("Session not found")
            merged = self._session_dict(row)
            self._event(
                connection,
                session_id,
                None,
                "usage.updated",
                {
                    "modelId": merged["modelId"],
                    "modelName": merged["modelName"],
                    "reasoningEffort": merged["reasoningEffort"],
                    "usedTokens": merged["usedTokens"],
                    "contextWindowTokens": merged["contextWindowTokens"],
                    "raw": usage,
                },
                now,
            )
        return merged

    def request_cancellation(self, session_id: str) -> dict[str, Any] | None:
        now = utc_now()
        with self._connect() as connection:
            session = connection.execute("SELECT * FROM sessions WHERE id = ?", (session_id,)).fetchone()
            if session is None:
                raise NotFoundError("Session not found")
            turn_id = session["active_turn_id"]
            if turn_id is None or session["status"] not in ACTIVE_STATUSES:
                return None
            turn = connection.execute("SELECT * FROM turns WHERE id = ?", (turn_id,)).fetchone()
            connection.execute(
                "UPDATE turns SET cancellation_requested = 1 WHERE id = ?",
                (turn_id,),
            )
            if turn["status"] == "queued":
                connection.execute(
                    """
                    UPDATE turns
                    SET status = 'cancelled', stop_reason = 'cancelled', completed_at = ?
                    WHERE id = ?
                    """,
                    (now, turn_id),
                )
                connection.execute(
                    """
                    UPDATE sessions
                    SET status = 'cancelled', active_turn_id = NULL, updated_at = ?
                    WHERE id = ?
                    """,
                    (now, session_id),
                )
                self._event(
                    connection,
                    session_id,
                    turn_id,
                    "turn.cancelled",
                    {"turnId": turn_id, "stopReason": "cancelled"},
                    now,
                )
            else:
                connection.execute(
                    "UPDATE sessions SET status = 'cancelling', updated_at = ? WHERE id = ?",
                    (now, session_id),
                )
                self._event(
                    connection,
                    session_id,
                    turn_id,
                    "turn.cancelling",
                    {"turnId": turn_id},
                    now,
                )
        return self.get_turn(turn_id)

    def cancellation_requested(self, turn_id: str) -> bool:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT cancellation_requested FROM turns WHERE id = ?",
                (turn_id,),
            ).fetchone()
        return bool(row and row[0])

    def finish_turn(
        self,
        turn_id: str,
        status: str,
        stop_reason: str | None = None,
        error: str | None = None,
    ) -> None:
        if status not in {"completed", "failed", "cancelled"}:
            raise StoreError(f"Invalid terminal turn status: {status}")
        now = utc_now()
        session_status = "idle" if status == "completed" else status
        event_type = f"turn.{status}"
        with self._connect() as connection:
            row = connection.execute("SELECT * FROM turns WHERE id = ?", (turn_id,)).fetchone()
            if row is None:
                raise NotFoundError("Turn not found")
            if row["status"] in {"completed", "failed", "cancelled"}:
                return
            connection.execute(
                """
                UPDATE turns
                SET status = ?, stop_reason = ?, error = ?, worker_pid = NULL, completed_at = ?
                WHERE id = ?
                """,
                (status, stop_reason, error, now, turn_id),
            )
            connection.execute(
                """
                UPDATE sessions
                SET status = ?, active_turn_id = NULL, last_error = ?, updated_at = ?
                WHERE id = ?
                """,
                (session_status, error, now, row["session_id"]),
            )
            self._event(
                connection,
                row["session_id"],
                turn_id,
                event_type,
                {"turnId": turn_id, "stopReason": stop_reason, "error": error},
                now,
            )

    def events_after(self, session_id: str, after: int, limit: int = 500) -> list[dict[str, Any]]:
        with self._connect() as connection:
            exists = connection.execute("SELECT 1 FROM sessions WHERE id = ?", (session_id,)).fetchone()
            if exists is None:
                raise NotFoundError("Session not found")
            rows = connection.execute(
                """
                SELECT * FROM events
                WHERE session_id = ? AND id > ?
                ORDER BY id
                LIMIT ?
                """,
                (session_id, max(0, after), max(1, min(limit, 2000))),
            ).fetchall()
        return [
            {
                "id": int(row["id"]),
                "turnId": row["turn_id"],
                "type": row["type"],
                "data": json.loads(row["payload_json"]),
                "createdAt": row["created_at"],
            }
            for row in rows
        ]

    def fail_orphaned_turns(self) -> int:
        """Close jobs whose owning daemon disappeared before recording a terminal state."""
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT id FROM turns WHERE status IN ('queued', 'running', 'cancelling')"
            ).fetchall()
        for row in rows:
            self.finish_turn(
                row["id"],
                "failed",
                stop_reason="host_restarted",
                error="The host job service restarted before this turn completed.",
            )
        return len(rows)

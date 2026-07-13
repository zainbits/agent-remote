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


class _ConnectionLease:
    """Serialize one short transaction on the store's persistent connection."""

    def __init__(self, store: "JobStore"):
        self.store = store

    def __enter__(self) -> sqlite3.Connection:
        self.store._connection_lock.acquire()
        if self.store._closed:
            self.store._connection_lock.release()
            raise StoreError("Database is closed")
        try:
            return self.store._connection.__enter__()
        except Exception:
            self.store._connection_lock.release()
            raise

    def __exit__(self, exception_type: object, exception: object, traceback: object) -> bool:
        try:
            return bool(
                self.store._connection.__exit__(exception_type, exception, traceback)
            )
        finally:
            self.store._connection_lock.release()


class JobStore:
    """Small SQLite repository serialized through one durable WAL connection."""

    def __init__(self, database_path: str | Path):
        self.database_path = Path(database_path).expanduser().resolve()
        self.database_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.attachment_directory = self.database_path.parent / "attachments"
        self.attachment_directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        self._schema_lock = threading.Lock()
        self._connection_lock = threading.RLock()
        self._closed = False
        self._connection = sqlite3.connect(
            self.database_path,
            timeout=30.0,
            check_same_thread=False,
        )
        self._connection.row_factory = sqlite3.Row
        self._connection.execute("PRAGMA foreign_keys = ON")
        self._connection.execute("PRAGMA busy_timeout = 30000")
        self._initialize()

    def _connect(self) -> _ConnectionLease:
        return _ConnectionLease(self)

    def _initialize(self) -> None:
        schema = Path(__file__).with_name("schema.sql").read_text(encoding="utf-8")
        with self._schema_lock, self._connect() as connection:
            connection.execute("PRAGMA journal_mode = WAL")
            connection.execute("PRAGMA synchronous = FULL")
            connection.execute("PRAGMA wal_autocheckpoint = 1000")
            connection.execute("PRAGMA journal_size_limit = 8388608")
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
            message_columns = {
                row["name"]
                for row in connection.execute("PRAGMA table_info(messages)").fetchall()
            }
            if "ordinal" not in message_columns:
                connection.execute(
                    "ALTER TABLE messages ADD COLUMN ordinal INTEGER NOT NULL DEFAULT 0"
                )
                self._initialize_message_ordinals(connection)
            connection.execute(
                "CREATE INDEX IF NOT EXISTS messages_session_ordinal "
                "ON messages(session_id, ordinal)"
            )
            connection.execute("PRAGMA user_version = 6")
        os.chmod(self.database_path, 0o600)
        os.chmod(self.attachment_directory, 0o700)

    def close(self) -> None:
        with self._connection_lock:
            if self._closed:
                return
            self._connection.close()
            self._closed = True

    @staticmethod
    def _initialize_message_ordinals(connection: sqlite3.Connection) -> None:
        """Backfill explicit timeline order and repair v4 role-coalesced Codex turns."""
        connection.execute(
            "CREATE TEMP TABLE message_ordinal_migration(id TEXT PRIMARY KEY, ordinal INTEGER)"
        )
        connection.execute(
            """
            INSERT INTO message_ordinal_migration(id, ordinal)
            SELECT id, ROW_NUMBER() OVER (PARTITION BY session_id ORDER BY rowid) - 1
            FROM messages
            """
        )
        connection.execute(
            """
            UPDATE messages
            SET ordinal = (
                SELECT migrated.ordinal
                FROM message_ordinal_migration migrated
                WHERE migrated.id = messages.id
            )
            """
        )

        # Durable v4 used one early-created assistant row for every Codex item in
        # a turn. If tools were inserted after that row, its final answer was
        # appended in place and replay appeared to end on the last tool. Move
        # only that recognizable legacy aggregate to the end of its own turn.
        connection.execute(
            """
            UPDATE messages AS aggregate
            SET ordinal = (
                SELECT COALESCE(MAX(peer.ordinal), aggregate.ordinal) + 1
                FROM messages peer
                WHERE peer.turn_id = aggregate.turn_id
            )
            WHERE aggregate.role = 'assistant'
              AND aggregate.turn_id IS NOT NULL
              AND aggregate.id = aggregate.turn_id || ':assistant'
              AND EXISTS (
                  SELECT 1
                  FROM sessions session
                  WHERE session.id = aggregate.session_id
                    AND session.backend = 'codex'
              )
              AND EXISTS (
                  SELECT 1
                  FROM messages tool
                  WHERE tool.turn_id = aggregate.turn_id
                    AND tool.role = 'tool'
                    AND tool.ordinal > aggregate.ordinal
              )
            """
        )

        # Normalize positions after the repair. Ties with the following turn's
        # user row are resolved by original row order, which keeps the repaired
        # assistant inside its own turn.
        connection.execute("DELETE FROM message_ordinal_migration")
        connection.execute(
            """
            INSERT INTO message_ordinal_migration(id, ordinal)
            SELECT id,
                   ROW_NUMBER() OVER (
                       PARTITION BY session_id
                       ORDER BY ordinal, rowid
                   ) - 1
            FROM messages
            """
        )
        connection.execute(
            """
            UPDATE messages
            SET ordinal = (
                SELECT migrated.ordinal
                FROM message_ordinal_migration migrated
                WHERE migrated.id = messages.id
            )
            """
        )
        connection.execute("DROP TABLE message_ordinal_migration")

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
    def _turn_dict(
        row: sqlite3.Row,
        attachments: list[dict[str, Any]] | None = None,
    ) -> dict[str, Any]:
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
            "attachments": attachments or [],
        }

    @staticmethod
    def _message_dict(
        row: sqlite3.Row,
        attachments: list[dict[str, Any]] | None = None,
    ) -> dict[str, Any]:
        return {
            "id": row["id"],
            "turnId": row["turn_id"],
            "role": row["role"],
            "text": row["text"],
            "status": row["status"],
            "kind": row["kind"],
            "detail": row["detail"],
            "ordinal": row["ordinal"],
            "createdAt": row["created_at"],
            "updatedAt": row["updated_at"],
            "attachments": attachments or [],
        }

    def _attachment_dict(
        self,
        row: sqlite3.Row,
        include_path: bool = False,
    ) -> dict[str, Any]:
        attachment = {
            "id": row["id"],
            "fileName": row["file_name"],
            "mimeType": row["mime_type"],
            "sizeBytes": row["size_bytes"],
        }
        if include_path:
            attachment["path"] = str(self.attachment_directory / row["stored_name"])
        return attachment

    def register_attachment(
        self,
        session_id: str,
        attachment_id: str,
        stored_name: str,
        file_name: str,
        mime_type: str,
        size_bytes: int,
    ) -> dict[str, Any]:
        now = utc_now()
        with self._connect() as connection:
            session = connection.execute(
                "SELECT status FROM sessions WHERE id = ?",
                (session_id,),
            ).fetchone()
            if session is None:
                raise NotFoundError("Session not found")
            if session["status"] in ACTIVE_STATUSES:
                raise ConflictError("Cannot upload an image while a turn is active")
            connection.execute(
                """
                INSERT INTO attachments(
                    id, session_id, stored_name, file_name, mime_type, size_bytes, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    attachment_id,
                    session_id,
                    stored_name,
                    file_name,
                    mime_type,
                    size_bytes,
                    now,
                ),
            )
            row = connection.execute(
                "SELECT * FROM attachments WHERE id = ?",
                (attachment_id,),
            ).fetchone()
        return self._attachment_dict(row)

    def pending_attachment_count(self, session_id: str) -> int:
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT COUNT(*) FROM attachments
                WHERE session_id = ? AND turn_id IS NULL
                """,
                (session_id,),
            ).fetchone()
        return int(row[0])

    def pop_stale_pending_attachments(self, before: str) -> list[str]:
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT stored_name FROM attachments
                WHERE turn_id IS NULL AND created_at < ?
                """,
                (before,),
            ).fetchall()
            connection.execute(
                "DELETE FROM attachments WHERE turn_id IS NULL AND created_at < ?",
                (before,),
            )
        return [str(row["stored_name"]) for row in rows]

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
            next_ordinal = int(
                connection.execute(
                    "SELECT COALESCE(MAX(ordinal), -1) + 1 FROM messages WHERE session_id = ?",
                    (session_id,),
                ).fetchone()[0]
            )
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
                        ordinal, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, 'completed', ?, ?, ?, ?, ?)
                    """,
                    (
                        message_id,
                        session_id,
                        turn_id,
                        role,
                        text,
                        item.get("kind"),
                        item.get("detail"),
                        next_ordinal,
                        now,
                        now,
                    ),
                )
                next_ordinal += 1
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
                "SELECT * FROM messages WHERE session_id = ? ORDER BY ordinal, rowid",
                (session_id,),
            ).fetchall()
            attachment_rows = connection.execute(
                """
                SELECT * FROM attachments
                WHERE session_id = ? AND turn_id IS NOT NULL
                ORDER BY turn_id, turn_ordinal, rowid
                """,
                (session_id,),
            ).fetchall()
            latest_event = connection.execute(
                "SELECT COALESCE(MAX(id), 0) FROM events WHERE session_id = ?",
                (session_id,),
            ).fetchone()[0]
        attachments_by_turn: dict[str, list[dict[str, Any]]] = {}
        for row in attachment_rows:
            attachments_by_turn.setdefault(row["turn_id"], []).append(
                self._attachment_dict(row)
            )
        return {
            "session": self._session_dict(session_row),
            "messages": [
                self._message_dict(
                    row,
                    attachments_by_turn.get(row["turn_id"], [])
                    if row["role"] == "user"
                    else [],
                )
                for row in messages
            ],
            "latestEventId": int(latest_event),
        }

    def create_turn(
        self,
        session_id: str,
        prompt: str,
        attachment_ids: list[str] | None = None,
    ) -> dict[str, Any]:
        text = prompt.strip()
        requested_attachments = list(dict.fromkeys(attachment_ids or []))
        if len(requested_attachments) > 4:
            raise StoreError("A turn can include at most 4 images")
        if not text and not requested_attachments:
            raise StoreError("Prompt or image attachment is required")
        turn_id = str(uuid.uuid4())
        message_id = str(uuid.uuid4())
        now = utc_now()
        with self._connect() as connection:
            session = connection.execute("SELECT * FROM sessions WHERE id = ?", (session_id,)).fetchone()
            if session is None:
                raise NotFoundError("Session not found")
            if session["status"] in ACTIVE_STATUSES:
                raise ConflictError("This session already has an active turn")
            attachment_rows: list[sqlite3.Row] = []
            if requested_attachments:
                placeholders = ",".join("?" for _ in requested_attachments)
                rows = connection.execute(
                    f"SELECT * FROM attachments WHERE id IN ({placeholders})",
                    requested_attachments,
                ).fetchall()
                by_id = {row["id"]: row for row in rows}
                if len(by_id) != len(requested_attachments):
                    raise StoreError("One or more image attachments were not found")
                attachment_rows = [by_id[attachment_id] for attachment_id in requested_attachments]
                if any(row["session_id"] != session_id for row in attachment_rows):
                    raise StoreError("Image attachment belongs to a different session")
                if any(row["turn_id"] is not None for row in attachment_rows):
                    raise ConflictError("Image attachment has already been sent")
            connection.execute(
                """
                INSERT INTO turns(id, session_id, prompt, status, created_at)
                VALUES (?, ?, ?, 'queued', ?)
                """,
                (turn_id, session_id, text, now),
            )
            for position, attachment_id in enumerate(requested_attachments):
                connection.execute(
                    """
                    UPDATE attachments SET turn_id = ?, turn_ordinal = ?
                    WHERE id = ?
                    """,
                    (turn_id, position, attachment_id),
                )
            connection.execute(
                """
                INSERT INTO messages(
                    id, session_id, turn_id, role, text, ordinal, created_at, updated_at
                )
                VALUES (
                    ?, ?, ?, 'user', ?,
                    (SELECT COALESCE(MAX(ordinal), -1) + 1 FROM messages WHERE session_id = ?),
                    ?, ?
                )
                """,
                (message_id, session_id, turn_id, text, session_id, now, now),
            )
            title = session["title"]
            if title.startswith("New "):
                title = text.splitlines()[0][:120] if text else (
                    "Image attachment" if len(requested_attachments) == 1 else
                    f"{len(requested_attachments)} image attachments"
                )
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
                {
                    "messageId": message_id,
                    "role": "user",
                    "text": text,
                    "attachments": [self._attachment_dict(row) for row in attachment_rows],
                },
                now,
            )
            self._event(connection, session_id, turn_id, "turn.queued", {"turnId": turn_id}, now)
        return self.get_turn(turn_id)

    def get_turn(self, turn_id: str) -> dict[str, Any]:
        with self._connect() as connection:
            row = connection.execute("SELECT * FROM turns WHERE id = ?", (turn_id,)).fetchone()
            attachment_rows = connection.execute(
                """
                SELECT * FROM attachments WHERE turn_id = ?
                ORDER BY turn_ordinal, rowid
                """,
                (turn_id,),
            ).fetchall()
        if row is None:
            raise NotFoundError("Turn not found")
        return self._turn_dict(
            row,
            [self._attachment_dict(item, include_path=True) for item in attachment_rows],
        )

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
                    id, session_id, turn_id, role, text, status, ordinal, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, ?, '', 'in_progress',
                    (SELECT COALESCE(MAX(ordinal), -1) + 1 FROM messages WHERE session_id = ?),
                    ?, ?
                )
                """,
                (message_id, session_id, turn_id, role, session_id, now, now),
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

    def complete_message(self, message_id: str) -> bool:
        now = utc_now()
        with self._connect() as connection:
            row = connection.execute("SELECT * FROM messages WHERE id = ?", (message_id,)).fetchone()
            if row is None or row["status"] == "completed":
                return False
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
        return True

    def turn_role_text(self, turn_id: str, role: str) -> str:
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT text FROM messages WHERE turn_id = ? AND role = ? ORDER BY ordinal, rowid",
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
        self.upsert_message_snapshot(
            session_id,
            turn_id,
            message_id,
            role,
            text,
            completed=True,
        )

    def upsert_message_snapshot(
        self,
        session_id: str,
        turn_id: str,
        message_id: str,
        role: str,
        text: str,
        completed: bool,
    ) -> bool:
        """Persist an authoritative backend item without duplicating repeated snapshots."""
        if role not in {"assistant", "thought"}:
            raise StoreError("Snapshot role must be assistant or thought")
        value = str(text or "")
        now = utc_now()
        changed = False
        with self._connect() as connection:
            current = connection.execute(
                "SELECT * FROM messages WHERE id = ?",
                (message_id,),
            ).fetchone()
            if current is None:
                if not value:
                    return False
                status = "completed" if completed else "in_progress"
                connection.execute(
                    """
                    INSERT INTO messages(
                        id, session_id, turn_id, role, text, status, ordinal,
                        created_at, updated_at
                    ) VALUES (
                        ?, ?, ?, ?, ?, ?,
                        (SELECT COALESCE(MAX(ordinal), -1) + 1 FROM messages WHERE session_id = ?),
                        ?, ?
                    )
                    """,
                    (
                        message_id,
                        session_id,
                        turn_id,
                        role,
                        value,
                        status,
                        session_id,
                        now,
                        now,
                    ),
                )
                self._event(
                    connection,
                    session_id,
                    turn_id,
                    "message.delta",
                    {"messageId": message_id, "role": role, "delta": value},
                    now,
                )
                if completed:
                    self._event(
                        connection,
                        session_id,
                        turn_id,
                        "message.completed",
                        {"messageId": message_id, "role": role},
                        now,
                    )
                return True

            if (
                current["session_id"] != session_id
                or current["turn_id"] != turn_id
                or current["role"] != role
            ):
                raise StoreError("Backend message id was reused for a different timeline item")

            old_text = str(current["text"] or "")
            new_status = (
                "completed"
                if completed or current["status"] == "completed"
                else "in_progress"
            )
            text_changed = value != old_text
            status_changed = current["status"] != new_status
            if not text_changed and not status_changed:
                return False
            connection.execute(
                "UPDATE messages SET text = ?, status = ?, updated_at = ? WHERE id = ?",
                (value, new_status, now, message_id),
            )
            if text_changed:
                if value.startswith(old_text):
                    event_type = "message.delta"
                    payload = {
                        "messageId": message_id,
                        "role": role,
                        "delta": value[len(old_text) :],
                    }
                else:
                    event_type = "message.replaced"
                    payload = {
                        "messageId": message_id,
                        "role": role,
                        "text": value,
                    }
                self._event(connection, session_id, turn_id, event_type, payload, now)
            if new_status == "completed" and (
                current["status"] != "completed" or text_changed
            ):
                self._event(
                    connection,
                    session_id,
                    turn_id,
                    "message.completed",
                    {"messageId": message_id, "role": role},
                    now,
                )
            changed = True
        return changed

    def upsert_tool(
        self,
        session_id: str,
        turn_id: str,
        message_id: str,
        title: str,
        status: str | None,
        kind: str | None,
        detail: str | None,
    ) -> bool:
        now = utc_now()
        with self._connect() as connection:
            current = connection.execute(
                "SELECT * FROM messages WHERE id = ?",
                (message_id,),
            ).fetchone()
            if current is not None:
                if (
                    current["session_id"] != session_id
                    or current["turn_id"] != turn_id
                    or current["role"] != "tool"
                ):
                    raise StoreError("Backend tool id was reused for a different timeline item")
                if (
                    current["text"] == title
                    and current["status"] == status
                    and current["kind"] == kind
                    and current["detail"] == detail
                ):
                    return False
            connection.execute(
                """
                INSERT INTO messages(
                    id, session_id, turn_id, role, text, status, kind, detail,
                    ordinal, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, 'tool', ?, ?, ?, ?,
                    (SELECT COALESCE(MAX(ordinal), -1) + 1 FROM messages WHERE session_id = ?),
                    ?, ?
                )
                ON CONFLICT(id) DO UPDATE SET
                    text = excluded.text,
                    status = excluded.status,
                    kind = excluded.kind,
                    detail = excluded.detail,
                    updated_at = excluded.updated_at
                """,
                (
                    message_id,
                    session_id,
                    turn_id,
                    title,
                    status,
                    kind,
                    detail,
                    session_id,
                    now,
                    now,
                ),
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
        return True

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

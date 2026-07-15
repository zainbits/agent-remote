from __future__ import annotations

import json
import logging
import os
import re
import signal
import subprocess
import tempfile
import threading
import time
import uuid
from concurrent.futures import Future, ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, BinaryIO

from .catalog import LegacyCatalog
from .metadata import MetadataProvider
from .store import ConflictError, JobStore, NotFoundError, StoreError


MAX_DETAIL_CHARS = 16_000
LOGGER = logging.getLogger("agentremote.host.manager")
CODEX_SANDBOX_MODE = "workspace-write"
CODEX_FULL_ACCESS_MODE = "danger-full-access"
CODEX_APPROVAL_POLICY = "never"
MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024
MAX_PENDING_ATTACHMENTS = 16
DEFAULT_CLEANUP_DAYS = 30
MAX_CLEANUP_DAYS = 3650
IMAGE_MIME_EXTENSIONS = {
    "image/png": ".png",
    "image/jpeg": ".jpg",
    "image/gif": ".gif",
    "image/webp": ".webp",
}


def _text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return "\n".join(_text(item) for item in value if _text(item))
    if isinstance(value, dict):
        for key in ("text", "content", "summary"):
            if key in value:
                return _text(value[key])
    return str(value)


def _image_mime(header: bytes) -> str | None:
    if header.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if header.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if header.startswith((b"GIF87a", b"GIF89a")):
        return "image/gif"
    if len(header) >= 12 and header.startswith(b"RIFF") and header[8:12] == b"WEBP":
        return "image/webp"
    return None


def _timestamp_seconds(value: Any) -> float | None:
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    text = str(value).strip()
    if not text:
        return None
    if text.isdigit():
        return float(text)
    normalized = text[:-1] + "+00:00" if text.endswith("Z") else text
    normalized = re.sub(r"(\.\d{6})\d+(?=[+-]\d\d:\d\d$)", r"\1", normalized)
    try:
        return datetime.fromisoformat(normalized).timestamp()
    except ValueError:
        return None


class _StreamMessages:
    """Coalesce token-sized CLI deltas before committing them to SQLite."""

    def __init__(self, manager: "JobManager", session_id: str, turn_id: str):
        self.manager = manager
        self.session_id = session_id
        self.turn_id = turn_id
        self.ids = {
            "assistant": f"{turn_id}:assistant",
            "thought": f"{turn_id}:thought",
        }
        self.pending = {"assistant": "", "thought": ""}
        self.last_flush = time.monotonic()
        self.last_role: str | None = None

    def append(self, role: str, delta: str) -> None:
        if not delta:
            return
        if self.last_role is not None and self.last_role != role:
            self.flush(self.last_role)
        self.last_role = role
        self.pending[role] += delta
        now = time.monotonic()
        if len(self.pending[role]) >= 96 or "\n" in delta or now - self.last_flush >= 0.15:
            self.flush(role)

    def flush(self, role: str | None = None) -> None:
        roles = (role,) if role else tuple(self.pending)
        wrote = False
        for current in roles:
            delta = self.pending[current]
            if not delta:
                continue
            self.pending[current] = ""
            self.manager.store.append_message_delta(
                self.session_id,
                self.turn_id,
                self.ids[current],
                current,
                delta,
            )
            wrote = True
        if wrote:
            self.last_flush = time.monotonic()
            self.manager.notify(self.session_id)

    def complete(self) -> None:
        self.flush()
        for message_id in self.ids.values():
            self.manager.store.complete_message(message_id)
        self.manager.notify(self.session_id)


class _CodexTurnControl:
    """Thread-safe writes and deferred interruption for one app-server turn."""

    INTERRUPT_REQUEST_ID = 900_000_001

    def __init__(self, process: subprocess.Popen[str]):
        self.process = process
        self._lock = threading.Lock()
        self._thread_id: str | None = None
        self._turn_id: str | None = None
        self._interrupt_requested = False
        self._interrupt_sent = False

    def send(self, message: dict[str, Any]) -> None:
        with self._lock:
            self._send_locked(message)

    def set_active_turn(self, thread_id: str, turn_id: str) -> None:
        if not thread_id or not turn_id:
            return
        with self._lock:
            self._thread_id = thread_id
            self._turn_id = turn_id
            if self._interrupt_requested and not self._interrupt_sent:
                self._send_interrupt_locked()

    def request_interrupt(self) -> bool:
        with self._lock:
            self._interrupt_requested = True
            if self._interrupt_sent:
                return True
            if not self._thread_id or not self._turn_id:
                return True
            try:
                self._send_interrupt_locked()
            except (BrokenPipeError, OSError, ValueError):
                return False
            return True

    def _send_interrupt_locked(self) -> None:
        assert self._thread_id is not None
        assert self._turn_id is not None
        self._send_locked(
            {
                "id": self.INTERRUPT_REQUEST_ID,
                "method": "turn/interrupt",
                "params": {
                    "threadId": self._thread_id,
                    "turnId": self._turn_id,
                },
            }
        )
        self._interrupt_sent = True

    def _send_locked(self, message: dict[str, Any]) -> None:
        source = self.process.stdin
        if source is None or source.closed:
            raise BrokenPipeError("Codex app-server input is closed")
        source.write(json.dumps(message, ensure_ascii=False, separators=(",", ":")) + "\n")
        source.flush()


class JobManager:
    def __init__(
        self,
        store: JobStore,
        max_workers: int = 4,
        codex_bin: str | None = None,
        grok_bin: str | None = None,
        metadata: MetadataProvider | None = None,
    ):
        self.store = store
        self.codex_bin = codex_bin or os.environ.get("AGENTREMOTE_CODEX_BIN", "codex")
        self.grok_bin = grok_bin or os.environ.get("AGENTREMOTE_GROK_BIN", "grok")
        self.metadata = metadata or MetadataProvider(
            grok_bin=self.grok_bin,
            codex_bin=self.codex_bin,
        )
        self.executor = ThreadPoolExecutor(max_workers=max(1, max_workers), thread_name_prefix="agent-job")
        self._lock = threading.RLock()
        self._processes: dict[str, subprocess.Popen[str]] = {}
        self._codex_controls: dict[str, _CodexTurnControl] = {}
        self._futures: dict[str, Future[None]] = {}
        self._conditions: dict[str, threading.Condition] = {}
        self._versions: dict[str, int] = {}
        self._session_operation_locks: dict[str, threading.Lock] = {}
        self._cleanup_lock = threading.Lock()
        self.catalog = LegacyCatalog(store, self.codex_bin)
        self.import_legacy = os.environ.get("AGENTREMOTE_IMPORT_LEGACY", "1") != "0"
        self._cleanup_stale_attachments()
        self.store.fail_orphaned_turns()

    def _condition(self, session_id: str) -> threading.Condition:
        with self._lock:
            return self._conditions.setdefault(session_id, threading.Condition())

    def _session_operation_lock(self, session_id: str) -> threading.Lock:
        with self._lock:
            return self._session_operation_locks.setdefault(session_id, threading.Lock())

    def notify(self, session_id: str) -> None:
        condition = self._condition(session_id)
        with condition:
            self._versions[session_id] = self._versions.get(session_id, 0) + 1
            condition.notify_all()

    def create_session(
        self,
        backend: str,
        cwd: str,
        codex_full_access: bool = True,
    ) -> dict[str, Any]:
        session = self.store.create_session(backend, cwd, codex_full_access)
        self._refresh_metadata(session["id"])
        self.notify(session["id"])
        return self.store.get_session(session["id"])

    def list_sessions(self, backend: str, cwd: str, limit: int = 50) -> list[dict[str, Any]]:
        if self.import_legacy:
            self.catalog.sync(backend, cwd, limit)
        return self.store.list_sessions(backend, cwd, limit)

    def update_session_metadata(
        self,
        session_id: str,
        *,
        title: str | None = None,
        pinned: bool | None = None,
        unread: bool | None = None,
    ) -> dict[str, Any]:
        session = self.store.update_session_metadata(
            session_id,
            title=title,
            pinned=pinned,
            unread=unread,
        )
        self.notify(session_id)
        return session

    def delete_session(self, session_id: str) -> dict[str, bool]:
        operation_lock = self._session_operation_lock(session_id)
        with operation_lock:
            session = self.store.get_session(session_id)
            if session["status"] in {"queued", "running", "cancelling"}:
                raise ConflictError("Stop the active turn before deleting this session")
            backend_id = str(session.get("backendSessionId") or "").strip()
            if backend_id:
                self._delete_backend_session(session["backend"], backend_id)
            stored_names = self.store.delete_session(session_id)
        for stored_name in stored_names:
            try:
                (self.store.attachment_directory / stored_name).unlink(missing_ok=True)
            except OSError as error:
                LOGGER.warning("Could not remove a deleted session attachment: %s", error)
        self.notify(session_id)
        return {"deleted": True}

    def preview_session_cleanup(self, older_than_days: int = DEFAULT_CLEANUP_DAYS) -> dict[str, Any]:
        return self._cleanup_summary(self._session_cleanup_inventory(older_than_days))

    def delete_old_sessions(self, older_than_days: int = DEFAULT_CLEANUP_DAYS) -> dict[str, Any]:
        if not self._cleanup_lock.acquire(blocking=False):
            raise ConflictError("Session cleanup is already running")
        try:
            inventory = self._session_cleanup_inventory(older_than_days)
            summary = self._cleanup_summary(inventory)
            deleted = {"grok": 0, "codex": 0, "total": 0}
            failed = {"grok": 0, "codex": 0, "total": 0}
            skipped_active = int(summary["skippedActive"])
            for candidate in inventory["candidates"]:
                backend = candidate["backend"]
                try:
                    local_session = candidate.get("localSession")
                    if local_session is not None:
                        self.delete_session(local_session["id"])
                    else:
                        self._delete_backend_session(backend, candidate["backendSessionId"])
                except ConflictError:
                    skipped_active += 1
                except StoreError as error:
                    LOGGER.warning("Could not delete one old %s session: %s", backend, error)
                    failed[backend] += 1
                    failed["total"] += 1
                else:
                    deleted[backend] += 1
                    deleted["total"] += 1
            self.catalog.invalidate()
            return {
                **summary,
                "deleted": deleted,
                "failed": failed,
                "skippedActive": skipped_active,
            }
        finally:
            self._cleanup_lock.release()

    def _session_cleanup_inventory(self, older_than_days: int) -> dict[str, Any]:
        if not 1 <= older_than_days <= MAX_CLEANUP_DAYS:
            raise StoreError(f"olderThanDays must be between 1 and {MAX_CLEANUP_DAYS}")
        cutoff = datetime.now(timezone.utc) - timedelta(days=older_than_days)
        records: dict[tuple[str, str], dict[str, Any]] = {}

        for session in self.store.cleanup_sessions():
            backend = session["backend"]
            backend_id = str(session.get("backendSessionId") or "").strip()
            key_id = backend_id or f"local:{session['id']}"
            records[(backend, key_id)] = {
                "backend": backend,
                "backendSessionId": backend_id or None,
                "localSession": session,
                "timestamps": [_timestamp_seconds(session.get("updatedAt"))],
            }

        for backend_session in self.catalog.cleanup_sessions():
            backend = str(backend_session.get("backend") or "").strip().lower()
            backend_id = str(backend_session.get("backendSessionId") or "").strip()
            if backend not in {"grok", "codex"} or not backend_id:
                continue
            record = records.setdefault(
                (backend, backend_id),
                {
                    "backend": backend,
                    "backendSessionId": backend_id,
                    "localSession": None,
                    "timestamps": [],
                },
            )
            record["timestamps"].append(_timestamp_seconds(backend_session.get("updatedAt")))

        candidates: list[dict[str, Any]] = []
        skipped_pinned = 0
        skipped_active = 0
        cutoff_seconds = cutoff.timestamp()
        for record in records.values():
            timestamps = [value for value in record["timestamps"] if value is not None]
            if not timestamps or max(timestamps) >= cutoff_seconds:
                continue
            local_session = record.get("localSession")
            if local_session is not None and local_session["status"] in {
                "queued",
                "running",
                "cancelling",
            }:
                skipped_active += 1
            elif local_session is not None and local_session["pinned"]:
                skipped_pinned += 1
            else:
                candidates.append(record)

        return {
            "olderThanDays": older_than_days,
            "cutoff": cutoff.isoformat(timespec="milliseconds").replace("+00:00", "Z"),
            "candidates": candidates,
            "skippedPinned": skipped_pinned,
            "skippedActive": skipped_active,
        }

    @staticmethod
    def _cleanup_summary(inventory: dict[str, Any]) -> dict[str, Any]:
        eligible = {"grok": 0, "codex": 0, "total": 0}
        for candidate in inventory["candidates"]:
            eligible[candidate["backend"]] += 1
            eligible["total"] += 1
        return {
            "olderThanDays": inventory["olderThanDays"],
            "cutoff": inventory["cutoff"],
            "eligible": eligible,
            "skippedPinned": inventory["skippedPinned"],
            "skippedActive": inventory["skippedActive"],
        }

    def _delete_backend_session(self, backend: str, backend_id: str) -> None:
        command = (
            [self.grok_bin, "sessions", "delete", backend_id]
            if backend == "grok"
            else [self.codex_bin, "delete", "--force", backend_id]
        )
        try:
            result = subprocess.run(
                command,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=30,
                check=False,
            )
        except FileNotFoundError as error:
            raise StoreError(f"{backend.title()} CLI is not available on the host") from error
        except subprocess.TimeoutExpired as error:
            raise StoreError(f"Timed out deleting the linked {backend.title()} session") from error
        if result.returncode != 0:
            raise StoreError(
                f"Couldn't delete the linked {backend.title()} session; "
                "the AgentRemote copy was kept"
            )

    def session_bundle(self, session_id: str) -> dict[str, Any]:
        if self.import_legacy:
            self.catalog.import_history_if_needed(session_id)
        session = self._refresh_metadata(session_id)
        if session["backend"] == "codex" and session.get("backendSessionId"):
            self._refresh_codex_thread_metadata(session_id)
        bundle = self.store.session_bundle(session_id)
        bundle["commands"] = self.metadata.commands_for(bundle["session"]["backend"])
        return bundle

    def codex_status(self, session_id: str) -> dict[str, Any]:
        session = self.store.get_session(session_id)
        if session["backend"] != "codex":
            raise StoreError("Status is only available for Codex sessions")
        status = self.catalog.codex_status(session)
        if session.get("modelOverride"):
            status["modelId"] = session["modelId"]
            status["modelName"] = session["modelName"]
        if session.get("reasoningEffort"):
            status["reasoningEffort"] = session["reasoningEffort"]
        # These values are host-owned worker policy, so report what the next turn
        # will actually use even when an older rollout predates this configuration.
        status["sandboxMode"] = (
            CODEX_FULL_ACCESS_MODE if session["codexFullAccess"] else CODEX_SANDBOX_MODE
        )
        status["networkAccess"] = True
        status["approvalPolicy"] = CODEX_APPROVAL_POLICY
        usage = {
            "modelId": status.get("modelId"),
            "modelName": status.get("modelName"),
            "reasoningEffort": status.get("reasoningEffort"),
            "usedTokens": status.get("contextUsedTokens"),
            "contextWindowTokens": status.get("contextWindowTokens"),
        }
        if any(value is not None for value in usage.values()):
            self.store.update_usage(session_id, usage)
            self.notify(session_id)
        return status

    def command_catalog(self, session_id: str) -> list[dict[str, Any]]:
        session = self.store.get_session(session_id)
        return self.metadata.commands_for(session["backend"])

    def model_catalog(self, backend: str) -> list[dict[str, Any]]:
        normalized = backend.strip().lower()
        if normalized not in {"grok", "codex"}:
            raise StoreError("backend must be grok or codex")
        return self.metadata.models_for(normalized)

    def select_model(self, session_id: str, model_id: str) -> dict[str, Any]:
        session = self.store.get_session(session_id)
        requested = model_id.strip()
        model = next(
            (item for item in self.metadata.models_for(session["backend"])
             if item["id"] == requested),
            None,
        )
        if model is None:
            raise StoreError("Model is not available for this backend")
        efforts = model.get("reasoningEfforts") or []
        current_effort = session.get("reasoningEffort")
        if current_effort in efforts:
            reasoning_effort = current_effort
        else:
            reasoning_effort = model.get("defaultReasoningEffort")
            if reasoning_effort not in efforts:
                reasoning_effort = efforts[0] if efforts else None
        updated = self.store.set_model(
            session_id,
            model["id"],
            model["name"],
            reasoning_effort,
            model.get("contextWindowTokens"),
        )
        self.notify(session_id)
        return updated

    def select_reasoning_effort(
        self,
        session_id: str,
        reasoning_effort: str,
    ) -> dict[str, Any]:
        session = self.store.get_session(session_id)
        models = self.metadata.models_for(session["backend"])
        current_model_id = str(
            session.get("modelOverride") or session.get("modelId") or ""
        ).strip()
        model = next(
            (item for item in models if item["id"] == current_model_id),
            None,
        )
        if model is None:
            raise StoreError("Current model is not available for this backend")
        requested = reasoning_effort.strip()
        if requested not in (model.get("reasoningEfforts") or []):
            raise StoreError("Reasoning effort is not available for the current model")
        updated = self.store.set_model(
            session_id,
            model["id"],
            model["name"],
            requested,
            model.get("contextWindowTokens"),
        )
        self.notify(session_id)
        return updated

    def upload_attachment(
        self,
        session_id: str,
        file_name: str,
        declared_mime_type: str,
        size_bytes: int,
        source: BinaryIO,
    ) -> dict[str, Any]:
        session = self.store.get_session(session_id)
        if session["status"] in {"queued", "running", "cancelling"}:
            raise ConflictError("Cannot upload an image while a turn is active")
        if size_bytes <= 0 or size_bytes > MAX_ATTACHMENT_BYTES:
            raise StoreError("Image must be between 1 byte and 20 MiB")
        if declared_mime_type and not declared_mime_type.lower().startswith("image/"):
            raise StoreError("Attachment must be an image")
        if self.store.pending_attachment_count(session_id) >= MAX_PENDING_ATTACHMENTS:
            raise ConflictError("Too many unsent images; wait for older uploads to expire")

        attachment_id = str(uuid.uuid4())
        descriptor, temporary_path = tempfile.mkstemp(
            prefix=".upload-",
            dir=self.store.attachment_directory,
        )
        temporary = Path(temporary_path)
        final_path: Path | None = None
        header = bytearray()
        try:
            remaining = size_bytes
            with os.fdopen(descriptor, "wb") as target:
                os.fchmod(target.fileno(), 0o600)
                while remaining:
                    chunk = source.read(min(64 * 1024, remaining))
                    if not chunk:
                        raise StoreError("Image upload ended before Content-Length")
                    if len(header) < 16:
                        header.extend(chunk[:16 - len(header)])
                    target.write(chunk)
                    remaining -= len(chunk)
            mime_type = _image_mime(bytes(header))
            if mime_type is None:
                raise StoreError("Only PNG, JPEG, GIF, and WebP images are supported")
            stored_name = f"{attachment_id}{IMAGE_MIME_EXTENSIONS[mime_type]}"
            final_path = self.store.attachment_directory / stored_name
            os.replace(temporary, final_path)
            raw_display_name = file_name.replace("\\", "/").split("/")[-1].strip()
            display_name = "".join(
                character for character in raw_display_name
                if ord(character) >= 32 and character != "\x7f"
            )[:200]
            if not display_name:
                display_name = f"image{IMAGE_MIME_EXTENSIONS[mime_type]}"
            try:
                return self.store.register_attachment(
                    session_id,
                    attachment_id,
                    stored_name,
                    display_name,
                    mime_type,
                    size_bytes,
                )
            except Exception:
                final_path.unlink(missing_ok=True)
                raise
        finally:
            temporary.unlink(missing_ok=True)

    def _cleanup_stale_attachments(self) -> None:
        for temporary in self.store.attachment_directory.glob(".upload-*"):
            temporary.unlink(missing_ok=True)
        cutoff = (datetime.now(timezone.utc) - timedelta(days=1)).isoformat(
            timespec="milliseconds"
        ).replace("+00:00", "Z")
        for stored_name in self.store.pop_stale_pending_attachments(cutoff):
            (self.store.attachment_directory / stored_name).unlink(missing_ok=True)

    def start_turn(
        self,
        session_id: str,
        prompt: str,
        attachment_ids: list[str] | None = None,
    ) -> dict[str, Any]:
        operation_lock = self._session_operation_lock(session_id)
        with operation_lock:
            turn = self.store.create_turn(session_id, prompt, attachment_ids)
            self.notify(session_id)
            future = self.executor.submit(self._run_turn, turn["id"])
            with self._lock:
                self._futures[turn["id"]] = future
        future.add_done_callback(lambda _: self._forget_future(turn["id"]))
        return {
            **turn,
            "attachments": [
                {key: value for key, value in attachment.items() if key != "path"}
                for attachment in turn["attachments"]
            ],
        }

    def _forget_future(self, turn_id: str) -> None:
        with self._lock:
            self._futures.pop(turn_id, None)

    def cancel_session(self, session_id: str) -> bool:
        turn = self.store.request_cancellation(session_id)
        self.notify(session_id)
        if turn is None:
            return False
        with self._lock:
            process = self._processes.get(turn["id"])
            codex_control = self._codex_controls.get(turn["id"])
        if process is not None and process.poll() is None:
            if codex_control is None or not codex_control.request_interrupt():
                self._signal_process(process, signal.SIGINT)
            threading.Thread(
                target=self._kill_later,
                args=(process, 5.0),
                name=f"cancel-{turn['id'][:8]}",
                daemon=True,
            ).start()
        return True

    @staticmethod
    def _signal_process(process: subprocess.Popen[str], sig: signal.Signals) -> None:
        try:
            os.killpg(process.pid, sig)
        except (ProcessLookupError, PermissionError):
            try:
                process.send_signal(sig)
            except ProcessLookupError:
                pass

    def _kill_later(self, process: subprocess.Popen[str], delay: float) -> None:
        try:
            process.wait(timeout=delay)
        except subprocess.TimeoutExpired:
            self._signal_process(process, signal.SIGKILL)

    def wait_for_events(
        self,
        session_id: str,
        after: int,
        timeout: float,
    ) -> list[dict[str, Any]]:
        deadline = time.monotonic() + max(0.0, min(timeout, 25.0))
        condition = self._condition(session_id)
        while True:
            version = self._versions.get(session_id, 0)
            events = self.store.events_after(session_id, after)
            if events:
                return events
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return []
            with condition:
                if self._versions.get(session_id, 0) == version:
                    condition.wait(timeout=remaining)

    def _command(
        self,
        session: dict[str, Any],
        prompt: str,
        prompt_path: str | None = None,
        attachments: list[dict[str, Any]] | None = None,
    ) -> list[str]:
        cwd = session["cwd"]
        backend_id = session.get("backendSessionId")
        attached_images = attachments or []
        if session["backend"] == "codex":
            return [self.codex_bin, "app-server"]
        prompt_args = (
            ["--prompt-json", self._grok_prompt_json(prompt, attached_images)]
            if attached_images
            else ["--prompt-file", prompt_path or prompt]
        )
        command = [
            self.grok_bin,
            *prompt_args,
            "--output-format",
            "streaming-json",
            "--always-approve",
            "--cwd",
            cwd,
        ]
        if session.get("modelOverride"):
            model_args = ["--model", session["modelOverride"]]
            if session.get("reasoningEffort"):
                model_args.extend(["--reasoning-effort", session["reasoningEffort"]])
            command[1:1] = model_args
        if backend_id:
            command[1:1] = ["--resume", backend_id]
        return command

    @classmethod
    def _grok_prompt_json(
        cls,
        prompt: str,
        attachments: list[dict[str, Any]],
    ) -> str:
        content: list[dict[str, Any]] = []
        effective_prompt = cls._effective_grok_prompt(prompt)
        if effective_prompt:
            content.append({"type": "text", "text": effective_prompt})
        for attachment in attachments:
            content.append(
                {
                    "type": "resource_link",
                    "uri": Path(attachment["path"]).resolve().as_uri(),
                    "name": attachment["fileName"],
                    "mimeType": attachment["mimeType"],
                    "size": attachment["sizeBytes"],
                }
            )
        return json.dumps(
            {"type": "acp", "content": content},
            ensure_ascii=False,
            separators=(",", ":"),
        )

    @staticmethod
    def _effective_grok_prompt(prompt: str) -> str:
        # Grok's headless `/context` completes without a text event. `/session-info`
        # is the equivalent non-interactive report and includes live token counts.
        if prompt.strip().lower() in {"/context", "/usage"}:
            return "/session-info"
        return prompt

    def _run_turn(self, turn_id: str) -> None:
        turn = self.store.get_turn(turn_id)
        if turn["status"] != "queued" or turn["cancellationRequested"]:
            return
        session = self.store.get_session(turn["sessionId"])
        if session["backend"] == "codex":
            self._run_codex_app_server_turn(turn, session)
            return
        messages = _StreamMessages(self, session["id"], turn_id)
        process: subprocess.Popen[str] | None = None
        stderr_thread: threading.Thread | None = None
        prompt_path: str | None = None
        stderr_parts: list[str] = []
        terminal_error: str | None = None
        stop_reason: str | None = None
        compact_completed = False
        try:
            if session["backend"] == "grok" and not turn["attachments"]:
                descriptor, prompt_path = tempfile.mkstemp(
                    prefix=f"prompt-{turn_id[:8]}-",
                    suffix=".txt",
                    dir=self.store.database_path.parent,
                    text=True,
                )
                with os.fdopen(descriptor, "w", encoding="utf-8") as prompt_file:
                    prompt_file.write(self._effective_grok_prompt(turn["prompt"]))
            command = self._command(
                session,
                turn["prompt"],
                prompt_path,
                turn["attachments"],
            )
            process = subprocess.Popen(
                command,
                cwd=session["cwd"],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                bufsize=1,
                start_new_session=True,
            )
            with self._lock:
                self._processes[turn_id] = process
            if not self.store.mark_turn_running(turn_id, process.pid):
                self._signal_process(process, signal.SIGTERM)
                try:
                    process.wait(timeout=2.0)
                except subprocess.TimeoutExpired:
                    self._signal_process(process, signal.SIGKILL)
                    process.wait(timeout=2.0)
                return
            self.notify(session["id"])

            stderr_thread = threading.Thread(
                target=self._drain_stderr,
                args=(process, stderr_parts),
                name=f"stderr-{turn_id[:8]}",
                daemon=True,
            )
            stderr_thread.start()

            assert process.stdout is not None
            for raw_line in process.stdout:
                line = raw_line.strip()
                if not line:
                    continue
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    stderr_parts.append(line)
                    continue
                result = self._handle_grok_event(session, turn_id, messages, event)
                if result.get("stopReason") is not None:
                    stop_reason = result["stopReason"]
                if result.get("error"):
                    terminal_error = result["error"]
                if result.get("compactCompleted"):
                    compact_completed = True

            return_code = process.wait()
            stderr_thread.join(timeout=1.0)
            messages.complete()
            cancelled = self.store.cancellation_requested(turn_id)
            if cancelled:
                self.store.finish_turn(turn_id, "cancelled", stop_reason="cancelled")
            elif terminal_error:
                self.store.finish_turn(turn_id, "failed", stop_reason="failed", error=terminal_error)
            elif return_code != 0:
                error = self._stderr_message(stderr_parts, return_code)
                self.store.finish_turn(turn_id, "failed", stop_reason="failed", error=error)
            else:
                self._finalize_grok_command(
                    session["id"],
                    turn_id,
                    turn["prompt"],
                    compact_completed,
                )
                self.store.finish_turn(turn_id, "completed", stop_reason=stop_reason or "completed")
            self.notify(session["id"])
        except FileNotFoundError as error:
            self.store.finish_turn(
                turn_id,
                "failed",
                stop_reason="runner_missing",
                error=f"Agent executable not found: {error.filename}",
            )
            self.notify(session["id"])
        except Exception as error:  # Keep the daemon alive and persist every worker failure.
            messages.flush()
            if self.store.cancellation_requested(turn_id):
                self.store.finish_turn(turn_id, "cancelled", stop_reason="cancelled")
            else:
                self.store.finish_turn(
                    turn_id,
                    "failed",
                    stop_reason="host_error",
                    error=f"Host worker error: {error}",
                )
            self.notify(session["id"])
        finally:
            with self._lock:
                self._processes.pop(turn_id, None)
            if process is not None:
                if process.poll() is None:
                    self._signal_process(process, signal.SIGTERM)
                    try:
                        process.wait(timeout=2.0)
                    except subprocess.TimeoutExpired:
                        self._signal_process(process, signal.SIGKILL)
                        process.wait(timeout=2.0)
                if stderr_thread is not None:
                    stderr_thread.join(timeout=1.0)
                if process.stdin is not None and not process.stdin.closed:
                    process.stdin.close()
                if process.stdout is not None:
                    process.stdout.close()
                if process.stderr is not None:
                    process.stderr.close()
            if prompt_path is not None:
                try:
                    os.unlink(prompt_path)
                except FileNotFoundError:
                    pass

    def _run_codex_app_server_turn(
        self,
        turn: dict[str, Any],
        session: dict[str, Any],
    ) -> None:
        turn_id = turn["id"]
        process: subprocess.Popen[str] | None = None
        control: _CodexTurnControl | None = None
        stderr_thread: threading.Thread | None = None
        stderr_parts: list[str] = []
        state: dict[str, Any] = {
            "threadId": str(session.get("backendSessionId") or ""),
            "turnId": "",
            "terminal": False,
            "turnStatus": None,
            "stopReason": None,
            "error": None,
            "tools": {},
            "reasoningIndexes": {},
        }
        try:
            process = subprocess.Popen(
                [self.codex_bin, "app-server"],
                cwd=session["cwd"],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                bufsize=1,
                start_new_session=True,
            )
            control = _CodexTurnControl(process)
            with self._lock:
                self._processes[turn_id] = process
                self._codex_controls[turn_id] = control
            if not self.store.mark_turn_running(turn_id, process.pid):
                return
            self.notify(session["id"])

            stderr_thread = threading.Thread(
                target=self._drain_stderr,
                args=(process, stderr_parts),
                name=f"stderr-{turn_id[:8]}",
                daemon=True,
            )
            stderr_thread.start()

            control.send(
                {
                    "id": 1,
                    "method": "initialize",
                    "params": {
                        "clientInfo": {
                            "name": "agentremote_host",
                            "title": "AgentRemote durable host",
                            "version": "1",
                        }
                    },
                }
            )
            self._wait_for_codex_response(
                process, control, session, turn_id, state, 1, "initialize", stderr_parts
            )
            control.send({"method": "initialized", "params": {}})

            thread_method = "thread/resume" if state["threadId"] else "thread/start"
            thread_params = self._codex_thread_params(session)
            if state["threadId"]:
                thread_params["threadId"] = state["threadId"]
            control.send(
                {
                    "id": 2,
                    "method": thread_method,
                    "params": thread_params,
                }
            )
            thread_result = self._wait_for_codex_response(
                process,
                control,
                session,
                turn_id,
                state,
                2,
                thread_method,
                stderr_parts,
            )
            thread = thread_result.get("thread")
            backend_thread_id = str(thread.get("id") if isinstance(thread, dict) else "").strip()
            if not backend_thread_id:
                raise StoreError(f"Codex app-server {thread_method} returned no thread id")
            state["threadId"] = backend_thread_id
            if backend_thread_id != session.get("backendSessionId"):
                self.store.set_backend_session_id(session["id"], backend_thread_id)
                self.notify(session["id"])
            self._merge_codex_thread_result(session, thread_result)

            control.send(
                {
                    "id": 3,
                    "method": "turn/start",
                    "params": self._codex_turn_params(session, turn, backend_thread_id),
                }
            )
            turn_result = self._wait_for_codex_response(
                process,
                control,
                session,
                turn_id,
                state,
                3,
                "turn/start",
                stderr_parts,
            )
            backend_turn = turn_result.get("turn")
            backend_turn_id = str(
                backend_turn.get("id") if isinstance(backend_turn, dict) else ""
            ).strip()
            if not backend_turn_id:
                raise StoreError("Codex app-server turn/start returned no turn id")
            state["turnId"] = backend_turn_id
            control.set_active_turn(backend_thread_id, backend_turn_id)

            while not state["terminal"]:
                message = self._read_codex_message(process, stderr_parts)
                self._handle_codex_app_server_message(
                    control, session, turn_id, state, message
                )

            cancelled = self.store.cancellation_requested(turn_id) or state["turnStatus"] in {
                "interrupted",
                "cancelled",
            }
            if cancelled:
                self.store.finish_turn(turn_id, "cancelled", stop_reason="cancelled")
            elif state["error"] or state["turnStatus"] == "failed":
                error = str(state["error"] or "Codex turn failed.")
                self.store.finish_turn(turn_id, "failed", stop_reason="failed", error=error)
            else:
                self._refresh_codex_thread_metadata(session["id"])
                self.store.finish_turn(
                    turn_id,
                    "completed",
                    stop_reason=str(state["stopReason"] or "completed"),
                )
            self.notify(session["id"])
        except FileNotFoundError as error:
            self.store.finish_turn(
                turn_id,
                "failed",
                stop_reason="runner_missing",
                error=f"Agent executable not found: {error.filename}",
            )
            self.notify(session["id"])
        except Exception as error:  # Keep the daemon alive and preserve cancellation intent.
            if self.store.cancellation_requested(turn_id):
                self.store.finish_turn(turn_id, "cancelled", stop_reason="cancelled")
            else:
                self.store.finish_turn(
                    turn_id,
                    "failed",
                    stop_reason="host_error",
                    error=f"Host worker error: {error}",
                )
            self.notify(session["id"])
        finally:
            with self._lock:
                self._codex_controls.pop(turn_id, None)
                self._processes.pop(turn_id, None)
            if process is not None:
                if process.poll() is None:
                    self._signal_process(process, signal.SIGTERM)
                    try:
                        process.wait(timeout=2.0)
                    except subprocess.TimeoutExpired:
                        self._signal_process(process, signal.SIGKILL)
                        process.wait(timeout=2.0)
                if stderr_thread is not None:
                    stderr_thread.join(timeout=1.0)
                if process.stdin is not None and not process.stdin.closed:
                    process.stdin.close()
                if process.stdout is not None:
                    process.stdout.close()
                if process.stderr is not None:
                    process.stderr.close()

    @staticmethod
    def _codex_thread_params(session: dict[str, Any]) -> dict[str, Any]:
        params: dict[str, Any] = {
            "cwd": session["cwd"],
            "approvalPolicy": CODEX_APPROVAL_POLICY,
            "sandbox": (
                CODEX_FULL_ACCESS_MODE
                if session.get("codexFullAccess", True)
                else CODEX_SANDBOX_MODE
            ),
        }
        if session.get("modelOverride"):
            params["model"] = session["modelOverride"]
        return params

    @staticmethod
    def _codex_turn_params(
        session: dict[str, Any],
        turn: dict[str, Any],
        backend_thread_id: str,
    ) -> dict[str, Any]:
        inputs: list[dict[str, Any]] = []
        if turn["prompt"]:
            inputs.append({"type": "text", "text": turn["prompt"]})
        inputs.extend(
            {"type": "localImage", "path": attachment["path"]}
            for attachment in turn["attachments"]
        )
        full_access = bool(session.get("codexFullAccess", True))
        sandbox_policy: dict[str, Any]
        if full_access:
            sandbox_policy = {"type": "dangerFullAccess"}
        else:
            sandbox_policy = {
                "type": "workspaceWrite",
                "writableRoots": [session["cwd"]],
                "networkAccess": True,
            }
        params: dict[str, Any] = {
            "threadId": backend_thread_id,
            "input": inputs,
            "cwd": session["cwd"],
            "approvalPolicy": CODEX_APPROVAL_POLICY,
            "sandboxPolicy": sandbox_policy,
        }
        if session.get("modelOverride"):
            params["model"] = session["modelOverride"]
            if session.get("reasoningEffort"):
                params["effort"] = session["reasoningEffort"]
        return params

    def _wait_for_codex_response(
        self,
        process: subprocess.Popen[str],
        control: _CodexTurnControl,
        session: dict[str, Any],
        turn_id: str,
        state: dict[str, Any],
        request_id: int,
        method: str,
        stderr_parts: list[str],
    ) -> dict[str, Any]:
        while True:
            message = self._read_codex_message(process, stderr_parts)
            if message.get("id") == request_id and "method" not in message:
                error = message.get("error")
                if error:
                    detail = _text(error.get("message") if isinstance(error, dict) else error)
                    raise StoreError(
                        f"Codex app-server {method} failed: {detail or 'unknown error'}"
                    )
                result = message.get("result")
                return result if isinstance(result, dict) else {}
            self._handle_codex_app_server_message(control, session, turn_id, state, message)

    @staticmethod
    def _read_codex_message(
        process: subprocess.Popen[str],
        stderr_parts: list[str],
    ) -> dict[str, Any]:
        assert process.stdout is not None
        while True:
            raw_line = process.stdout.readline()
            if raw_line == "":
                return_code = process.poll()
                detail = "\n".join(stderr_parts)[-MAX_DETAIL_CHARS:].strip()
                suffix = f": {detail}" if detail else ""
                raise StoreError(
                    f"Codex app-server closed before the turn completed "
                    f"(status {return_code if return_code is not None else 'unknown'}){suffix}"
                )
            line = raw_line.strip()
            if not line:
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError:
                stderr_parts.append(line)
                continue
            if isinstance(value, dict):
                return value

    def _handle_codex_app_server_message(
        self,
        control: _CodexTurnControl,
        session: dict[str, Any],
        turn_id: str,
        state: dict[str, Any],
        message: dict[str, Any],
    ) -> None:
        method = str(message.get("method") or "")
        if not method:
            return
        params = message.get("params")
        if not isinstance(params, dict):
            params = {}
        if "id" in message:
            self._respond_to_codex_server_request(
                control, session, turn_id, method, message["id"], params
            )
            return

        event_thread_id = str(params.get("threadId") or "")
        if state["threadId"] and event_thread_id and event_thread_id != state["threadId"]:
            return
        if method == "thread/started":
            thread = params.get("thread")
            backend_id = str(thread.get("id") if isinstance(thread, dict) else "").strip()
            if backend_id:
                state["threadId"] = backend_id
        elif method == "turn/started":
            turn = params.get("turn")
            backend_turn_id = str(turn.get("id") if isinstance(turn, dict) else "").strip()
            if backend_turn_id:
                state["turnId"] = backend_turn_id
                control.set_active_turn(str(state["threadId"]), backend_turn_id)
        elif method in {"item/started", "item/completed"}:
            item = params.get("item")
            if isinstance(item, dict):
                self._handle_codex_app_item(
                    session,
                    turn_id,
                    item,
                    completed=method == "item/completed",
                    state=state,
                )
        elif method == "item/agentMessage/delta":
            self._append_codex_message_delta(session, turn_id, params, "assistant")
        elif method == "item/reasoning/summaryTextDelta":
            source_id = str(params.get("itemId") or "").strip()
            delta = str(params.get("delta") or "")
            summary_index = params.get("summaryIndex")
            previous_index = state["reasoningIndexes"].get(source_id)
            if source_id and previous_index is not None and previous_index != summary_index:
                delta = "\n" + delta
            if source_id:
                state["reasoningIndexes"][source_id] = summary_index
            self._append_codex_message_delta(
                session, turn_id, {**params, "delta": delta}, "thought"
            )
        elif method == "item/plan/delta":
            self._append_codex_message_delta(session, turn_id, params, "thought")
        elif method in {
            "item/commandExecution/outputDelta",
            "item/fileChange/outputDelta",
        }:
            self._append_codex_tool_delta(session, turn_id, method, params, state)
        elif method == "thread/tokenUsage/updated":
            token_usage = params.get("tokenUsage")
            if isinstance(token_usage, dict):
                latest = token_usage.get("last")
                usage = {
                    "usedTokens": (
                        latest.get("totalTokens") if isinstance(latest, dict) else None
                    ),
                    "contextWindowTokens": token_usage.get("modelContextWindow"),
                }
                if any(value is not None for value in usage.values()):
                    self.store.update_usage(session["id"], usage)
                    self.notify(session["id"])
        elif method == "error":
            error = params.get("error")
            if not params.get("willRetry"):
                state["error"] = (
                    _text(error.get("message") if isinstance(error, dict) else error)
                    or "Codex turn failed."
                )
        elif method == "turn/completed":
            turn = params.get("turn")
            if isinstance(turn, dict):
                for item in turn.get("items") or []:
                    if isinstance(item, dict):
                        self._handle_codex_app_item(
                            session, turn_id, item, completed=True, state=state
                        )
                status = str(turn.get("status") or "completed")
                error = turn.get("error")
                if status == "failed" and not state["error"]:
                    state["error"] = (
                        _text(error.get("message") if isinstance(error, dict) else error)
                        or "Codex turn failed."
                    )
                state["turnStatus"] = status
                state["stopReason"] = "cancelled" if status == "interrupted" else status
            else:
                state["turnStatus"] = "completed"
                state["stopReason"] = "completed"
            state["terminal"] = True

    def _respond_to_codex_server_request(
        self,
        control: _CodexTurnControl,
        session: dict[str, Any],
        turn_id: str,
        method: str,
        request_id: Any,
        params: dict[str, Any],
    ) -> None:
        if method in {
            "item/commandExecution/requestApproval",
            "item/fileChange/requestApproval",
        }:
            result: dict[str, Any] = {"decision": "decline"}
        elif method in {"applyPatchApproval", "execCommandApproval"}:
            result = {"decision": "denied"}
        elif method == "item/permissions/requestApproval":
            result = {"permissions": {}, "scope": "turn"}
        elif method == "item/tool/requestUserInput":
            source_id = str(params.get("itemId") or "").strip()
            if source_id:
                changed = self.store.upsert_tool(
                    session["id"],
                    turn_id,
                    f"{turn_id}:{source_id}",
                    "Interactive input requested",
                    "declined",
                    "userInput",
                    "AgentRemote does not support interactive question forms yet.",
                )
                if changed:
                    self.notify(session["id"])
            result = {"answers": {}}
        elif method == "mcpServer/elicitation/request":
            result = {"action": "decline"}
        elif method == "currentTime/read":
            result = {"currentTimeAt": int(time.time())}
        else:
            control.send(
                {
                    "id": request_id,
                    "error": {"code": -32601, "message": "Unsupported client request"},
                }
            )
            return
        control.send({"id": request_id, "result": result})

    def _append_codex_message_delta(
        self,
        session: dict[str, Any],
        turn_id: str,
        params: dict[str, Any],
        role: str,
    ) -> None:
        source_id = str(params.get("itemId") or "").strip()
        delta = str(params.get("delta") or "")
        if not source_id or not delta:
            return
        self.store.append_message_delta(
            session["id"], turn_id, f"{turn_id}:{source_id}", role, delta
        )
        self.notify(session["id"])

    def _append_codex_tool_delta(
        self,
        session: dict[str, Any],
        turn_id: str,
        method: str,
        params: dict[str, Any],
        state: dict[str, Any],
    ) -> None:
        source_id = str(params.get("itemId") or "").strip()
        delta = str(params.get("delta") or "")
        if not source_id or not delta:
            return
        default_kind = "commandExecution" if "commandExecution" in method else "fileChange"
        default_title = "Command" if default_kind == "commandExecution" else "Applying file changes"
        tool = state["tools"].setdefault(
            source_id,
            {"title": default_title, "kind": default_kind, "detail": ""},
        )
        tool["detail"] = (str(tool.get("detail") or "") + delta)[-MAX_DETAIL_CHARS:]
        changed = self.store.upsert_tool(
            session["id"],
            turn_id,
            f"{turn_id}:{source_id}",
            str(tool["title"]),
            "in_progress",
            str(tool["kind"]),
            str(tool["detail"]),
        )
        if changed:
            self.notify(session["id"])

    def _handle_codex_app_item(
        self,
        session: dict[str, Any],
        turn_id: str,
        item: dict[str, Any],
        completed: bool,
        state: dict[str, Any],
    ) -> None:
        item_type = str(item.get("type") or "")
        source_id = str(item.get("id") or "").strip()
        if not item_type or not source_id or item_type in {"userMessage", "hookPrompt"}:
            return
        message_id = f"{turn_id}:{source_id}"
        if item_type in {"agentMessage", "reasoning", "plan"}:
            role = "assistant" if item_type == "agentMessage" else "thought"
            if item_type == "agentMessage":
                text = _text(item.get("text"))
            elif item_type == "reasoning":
                text = _text(item.get("summary"))
            else:
                text = _text(item.get("text"))
            if text:
                changed = self.store.upsert_message_snapshot(
                    session["id"], turn_id, message_id, role, text, completed
                )
            elif completed:
                changed = self.store.complete_message(message_id)
            else:
                changed = False
            if changed:
                self.notify(session["id"])
            return

        title, detail = self._codex_tool(item_type, item)
        cached = state["tools"].get(source_id) or {}
        if not detail:
            detail = str(cached.get("detail") or "") or None
        status = self._normalize_codex_status(
            str(item.get("status") or ("completed" if completed else "inProgress"))
        )
        state["tools"][source_id] = {
            "title": title,
            "kind": item_type,
            "detail": detail or "",
        }
        changed = self.store.upsert_tool(
            session["id"],
            turn_id,
            message_id,
            title,
            status,
            item_type,
            detail,
        )
        if changed:
            self.notify(session["id"])

    def _merge_codex_thread_result(
        self,
        session: dict[str, Any],
        result: dict[str, Any],
    ) -> None:
        model_id = str(result.get("model") or "").strip() or None
        effort = str(result.get("reasoningEffort") or "").strip() or None
        usage: dict[str, Any] = {}
        if model_id:
            usage["modelId"] = model_id
            usage["modelName"] = (
                session.get("modelName")
                if session.get("modelOverride") == model_id and session.get("modelName")
                else model_id
            )
        if effort and not session.get("modelOverride"):
            usage["reasoningEffort"] = effort
        if usage:
            self.store.update_usage(session["id"], usage)
            self.notify(session["id"])

    @staticmethod
    def _drain_stderr(process: subprocess.Popen[str], target: list[str]) -> None:
        if process.stderr is None:
            return
        try:
            for line in process.stderr:
                value = line.strip()
                if value:
                    target.append(value)
                    if len(target) > 100:
                        del target[:20]
        except ValueError:
            # The worker finalizer can close the pipe after process termination.
            return

    @staticmethod
    def _stderr_message(parts: list[str], return_code: int) -> str:
        detail = "\n".join(parts)[-MAX_DETAIL_CHARS:].strip()
        return detail or f"Agent process exited with status {return_code}."

    @staticmethod
    def _codex_tool(item_type: str, item: dict[str, Any]) -> tuple[str, str | None]:
        if item_type == "commandExecution":
            command = _text(item.get("command")) or "Command"
            output = _text(item.get("aggregatedOutput") or item.get("output"))
            exit_code = item.get("exitCode")
            detail = output
            if exit_code is not None:
                detail = (detail + f"\nexit code: {exit_code}").strip()
            return command[:300], detail[-MAX_DETAIL_CHARS:] or None
        if item_type == "fileChange":
            changes = item.get("changes") or []
            detail = "\n".join(
                f"{change.get('kind', 'update')} · {change.get('path', '')}"
                for change in changes
                if isinstance(change, dict)
            )
            return "File changes", detail[-MAX_DETAIL_CHARS:] or None
        if item_type == "mcpToolCall":
            title = " · ".join(
                part for part in (_text(item.get("server")), _text(item.get("tool"))) if part
            ) or "MCP tool"
        elif item_type == "dynamicToolCall":
            title = _text(item.get("tool")) or "Tool call"
        elif item_type == "collabAgentToolCall":
            title = _text(item.get("tool")) or "Agent task"
        elif item_type == "webSearch":
            title = f"Search · {_text(item.get('query'))}".rstrip(" ·")
        elif item_type == "imageView":
            title = "View image"
        elif item_type == "imageGeneration":
            title = "Image generation"
        else:
            title = re.sub(r"(?<!^)(?=[A-Z])", " ", item_type).title()
        detail = json.dumps(item, ensure_ascii=False, indent=2)[-MAX_DETAIL_CHARS:]
        return title[:300], detail

    @staticmethod
    def _normalize_codex_status(status: str) -> str:
        return {
            "inProgress": "in_progress",
            "pending": "pending",
            "completed": "completed",
            "failed": "failed",
            "declined": "declined",
            "interrupted": "cancelled",
            "cancelled": "cancelled",
        }.get(status, status or "in_progress")

    def _handle_grok_event(
        self,
        session: dict[str, Any],
        turn_id: str,
        messages: _StreamMessages,
        event: dict[str, Any],
    ) -> dict[str, Any]:
        event_type = str(event.get("type") or "")
        if event_type == "thought":
            messages.append("thought", _text(event.get("data")))
        elif event_type == "text":
            messages.append("assistant", _text(event.get("data")))
        elif event_type == "end":
            backend_id = str(event.get("sessionId") or "")
            self.store.set_backend_session_id(session["id"], backend_id)
            metadata = {
                "modelId": event.get("modelId"),
                "usedTokens": event.get("totalTokens"),
            }
            if any(value is not None for value in metadata.values()):
                self.store.update_usage(session["id"], metadata)
            self.notify(session["id"])
            return {"stopReason": str(event.get("stopReason") or "completed"), "error": None}
        elif event_type == "auto_compact_completed":
            return {"stopReason": None, "error": None, "compactCompleted": True}
        elif event_type == "error":
            return {
                "stopReason": "failed",
                "error": _text(event.get("data") or event.get("message")) or "Grok turn failed.",
            }
        return {"stopReason": None, "error": None}

    def _refresh_metadata(self, session_id: str) -> dict[str, Any]:
        session = self.store.get_session(session_id)
        try:
            metadata = self.metadata.metadata_for(session)
            if any(value is not None for value in metadata.values()):
                session = self.store.update_usage(session_id, metadata)
                self.notify(session_id)
        except Exception as error:
            # Metadata enriches the UI; it must never make a durable turn unavailable.
            LOGGER.warning("Could not refresh %s metadata: %s", session["backend"], error)
        return session

    def _refresh_codex_thread_metadata(self, session_id: str) -> dict[str, Any]:
        session = self.store.get_session(session_id)
        if session["backend"] != "codex" or not session.get("backendSessionId"):
            return session
        try:
            status = self.catalog.codex_thread_status(session)
            selected = bool(session.get("modelOverride"))
            usage = {
                "modelId": session.get("modelId") if selected else status.get("modelId"),
                "modelName": session.get("modelName") if selected else status.get("modelName"),
                "reasoningEffort": (
                    session.get("reasoningEffort") if selected else status.get("reasoningEffort")
                ),
                "usedTokens": status.get("contextUsedTokens"),
                "contextWindowTokens": (
                    session.get("contextWindowTokens")
                    if selected
                    else status.get("contextWindowTokens")
                ),
            }
            if any(value is not None for value in usage.values()):
                session = self.store.update_usage(session_id, usage)
                self.notify(session_id)
        except Exception as error:
            LOGGER.warning("Could not refresh Codex thread status: %s", error)
        return session

    def _finalize_grok_command(
        self,
        session_id: str,
        turn_id: str,
        prompt: str,
        compact_completed: bool,
    ) -> None:
        command = prompt.strip().split(maxsplit=1)[0].lower() if prompt.strip().startswith("/") else ""
        assistant_text = self.store.turn_role_text(turn_id, "assistant")
        if command in {"/context", "/usage", "/session-info"} and assistant_text:
            parsed = self.metadata.parse_session_info(assistant_text)
            if parsed:
                self.store.update_usage(session_id, parsed)
                self.notify(session_id)
        session = self._refresh_metadata(session_id)
        if assistant_text or not command:
            return
        if command in {"/context", "/usage", "/session-info"}:
            report = self.metadata.context_report(session)
        elif command == "/compact" and compact_completed:
            report = self.metadata.compact_report(session)
        elif compact_completed:
            report = "Conversation history compacted successfully."
        else:
            report = f"`{command}` completed successfully without a text report."
        self.store.append_complete_message(
            session_id,
            turn_id,
            f"{turn_id}:assistant",
            "assistant",
            report,
        )
        self.notify(session_id)

    def shutdown(self) -> None:
        with self._lock:
            processes = list(self._processes.values())
        for process in processes:
            if process.poll() is None:
                self._signal_process(process, signal.SIGTERM)
        self.executor.shutdown(wait=True, cancel_futures=False)
        self.store.close()

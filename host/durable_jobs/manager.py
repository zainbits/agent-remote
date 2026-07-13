from __future__ import annotations

import json
import logging
import os
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
CODEX_FULL_ACCESS_FLAG = "--dangerously-bypass-approvals-and-sandbox"
CODEX_APPROVAL_POLICY = "never"
CODEX_NETWORK_ACCESS_CONFIG = "sandbox_workspace_write.network_access=true"
MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024
MAX_PENDING_ATTACHMENTS = 16
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
        self._futures: dict[str, Future[None]] = {}
        self._conditions: dict[str, threading.Condition] = {}
        self._versions: dict[str, int] = {}
        self.catalog = LegacyCatalog(store, self.codex_bin)
        self.import_legacy = os.environ.get("AGENTREMOTE_IMPORT_LEGACY", "1") != "0"
        self._cleanup_stale_attachments()
        self.store.fail_orphaned_turns()

    def _condition(self, session_id: str) -> threading.Condition:
        with self._lock:
            return self._conditions.setdefault(session_id, threading.Condition())

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
        if process is not None and process.poll() is None:
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
            full_access = bool(session.get("codexFullAccess", True))
            model_args: list[str] = []
            if session.get("modelOverride"):
                model_args.extend(["--model", session["modelOverride"]])
                if session.get("reasoningEffort"):
                    model_args.extend(
                        ["-c", f'model_reasoning_effort="{session["reasoningEffort"]}"']
                    )
            permission_args = (
                [CODEX_FULL_ACCESS_FLAG]
                if full_access
                else [
                    "-c",
                    f'sandbox_mode="{CODEX_SANDBOX_MODE}"',
                    "-c",
                    f'approval_policy="{CODEX_APPROVAL_POLICY}"',
                    "-c",
                    CODEX_NETWORK_ACCESS_CONFIG,
                ]
            )
            image_args = [
                argument
                for attachment in attached_images
                for argument in ("--image", attachment["path"])
            ]
            if backend_id:
                return [
                    self.codex_bin,
                    "exec",
                    "resume",
                    "--json",
                    "--skip-git-repo-check",
                    *model_args,
                    *permission_args,
                    *image_args,
                    backend_id,
                    "-",
                ]
            return [
                self.codex_bin,
                "exec",
                "--json",
                "--color",
                "never",
                "--skip-git-repo-check",
                "--cd",
                cwd,
                *model_args,
                *permission_args,
                *image_args,
                "-",
            ]
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
                stdin=subprocess.PIPE if session["backend"] == "codex" else subprocess.DEVNULL,
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

            if session["backend"] == "codex" and process.stdin is not None:
                process.stdin.write(turn["prompt"])
                process.stdin.close()

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
                if session["backend"] == "codex":
                    result = self._handle_codex_event(session, turn_id, event)
                else:
                    result = self._handle_grok_event(session, turn_id, messages, event)
                if result.get("stopReason") is not None:
                    stop_reason = result["stopReason"]
                if result.get("error"):
                    terminal_error = result["error"]
                if result.get("compactCompleted"):
                    compact_completed = True

            return_code = process.wait()
            stderr_thread.join(timeout=1.0)
            if session["backend"] == "grok":
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
                if session["backend"] == "grok":
                    self._finalize_grok_command(
                        session["id"],
                        turn_id,
                        turn["prompt"],
                        compact_completed,
                    )
                else:
                    self._refresh_codex_thread_metadata(session["id"])
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
            if session["backend"] == "grok":
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

    def _handle_codex_event(
        self,
        session: dict[str, Any],
        turn_id: str,
        event: dict[str, Any],
    ) -> dict[str, Any]:
        event_type = event.get("type", "")
        if event_type == "thread.started":
            backend_id = str(event.get("thread_id") or "")
            self.store.set_backend_session_id(session["id"], backend_id)
            self.notify(session["id"])
        elif event_type in {"item.started", "item.updated", "item.completed"}:
            item = event.get("item") or {}
            item_type = str(item.get("type") or "")
            source_item_id = str(item.get("id") or "").strip()
            completed = event_type == "item.completed"
            # Codex defines each item as a distinct ordered unit. Keep its
            # backend id all the way into durable storage so progress messages,
            # tools, and the final answer replay in exactly the emitted order.
            if item_type in {"agent_message", "reasoning", "plan"}:
                if not source_item_id and not completed:
                    return {"stopReason": None, "error": None}
                item_id = f"{turn_id}:{source_item_id or uuid.uuid4()}"
                role = "assistant" if item_type == "agent_message" else "thought"
                text = _text(
                    item.get("text")
                    if item_type == "agent_message"
                    else item.get("text") or item.get("summary")
                )
                if text:
                    changed = self.store.upsert_message_snapshot(
                        session["id"],
                        turn_id,
                        item_id,
                        role,
                        text,
                        completed=completed,
                    )
                elif completed:
                    changed = self.store.complete_message(item_id)
                else:
                    changed = False
                if changed:
                    self.notify(session["id"])
            elif item_type:
                item_id = f"{turn_id}:{source_item_id or uuid.uuid4()}"
                title, detail = self._codex_tool(item_type, item)
                changed = self.store.upsert_tool(
                    session["id"],
                    turn_id,
                    item_id,
                    title,
                    str(item.get("status") or ("completed" if completed else "in_progress")),
                    item_type,
                    detail,
                )
                if changed:
                    self.notify(session["id"])
        elif event_type == "turn.completed":
            usage = event.get("usage") or {}
            if usage:
                self.store.update_usage(session["id"], usage)
                self.notify(session["id"])
            return {"stopReason": "completed", "error": None}
        elif event_type in {"turn.failed", "error"}:
            message = _text(event.get("message") or event.get("error")) or "Codex turn failed."
            return {"stopReason": "failed", "error": message}
        return {"stopReason": None, "error": None}

    @staticmethod
    def _codex_tool(item_type: str, item: dict[str, Any]) -> tuple[str, str | None]:
        if item_type == "command_execution":
            command = _text(item.get("command")) or "Command"
            output = _text(item.get("aggregated_output") or item.get("output"))
            exit_code = item.get("exit_code")
            detail = output
            if exit_code is not None:
                detail = (detail + f"\nexit code: {exit_code}").strip()
            return command[:300], detail[-MAX_DETAIL_CHARS:] or None
        if item_type == "file_change":
            changes = item.get("changes") or []
            detail = "\n".join(
                f"{change.get('kind', 'update')} · {change.get('path', '')}"
                for change in changes
                if isinstance(change, dict)
            )
            return "File changes", detail[-MAX_DETAIL_CHARS:] or None
        if item_type in {"mcp_tool_call", "dynamic_tool_call"}:
            title = _text(item.get("tool") or item.get("name")) or "Tool call"
        elif item_type == "web_search":
            title = f"Search · {_text(item.get('query'))}".rstrip(" ·")
        else:
            title = item_type.replace("_", " ").title()
        detail = json.dumps(item, ensure_ascii=False, indent=2)[-MAX_DETAIL_CHARS:]
        return title[:300], detail

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

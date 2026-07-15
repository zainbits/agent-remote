from __future__ import annotations

import hmac
import json
import logging
import urllib.parse
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from .manager import JobManager
from .store import ConflictError, NotFoundError, StoreError


LOGGER = logging.getLogger("agentremote.host")
MAX_BODY_BYTES = 1_048_576


class DurableHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], manager: JobManager, token: str):
        super().__init__(address, DurableRequestHandler)
        self.manager = manager
        self.token = token


class DurableRequestHandler(BaseHTTPRequestHandler):
    server: DurableHTTPServer
    protocol_version = "HTTP/1.1"

    def log_message(self, format_string: str, *args: object) -> None:
        LOGGER.info("%s - %s", self.client_address[0], format_string % args)

    def _send(self, status: HTTPStatus, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status.value)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self) -> bool:
        value = self.headers.get("Authorization", "")
        candidate = value[7:] if value.lower().startswith("bearer ") else ""
        return bool(candidate) and hmac.compare_digest(candidate, self.server.token)

    def _require_auth(self) -> bool:
        if self._authorized():
            return True
        self.close_connection = True
        self._send(HTTPStatus.UNAUTHORIZED, {"error": "Unauthorized"})
        return False

    def _json_body(self) -> dict[str, Any]:
        length = self._content_length(MAX_BODY_BYTES)
        try:
            value = json.loads(self.rfile.read(length))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise StoreError("Request body must be valid JSON") from error
        if not isinstance(value, dict):
            raise StoreError("Request body must be a JSON object")
        return value

    def _content_length(self, maximum: int) -> int:
        raw_length = self.headers.get("Content-Length", "0")
        try:
            length = int(raw_length)
        except ValueError as error:
            raise StoreError("Invalid Content-Length") from error
        if length <= 0 or length > maximum:
            if maximum == MAX_BODY_BYTES:
                raise StoreError("Request body must be between 1 byte and 1 MiB")
            raise StoreError("Image must be between 1 byte and 20 MiB")
        return length

    @staticmethod
    def _route(path: str) -> list[str]:
        return [urllib.parse.unquote(part) for part in path.split("/") if part]

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        parsed = urllib.parse.urlsplit(self.path)
        if parsed.path in {"/health", "/api/v1/health"}:
            self._send(HTTPStatus.OK, {"status": "ok", "service": "agentremote-durable"})
            return
        if not self._require_auth():
            return
        try:
            route = self._route(parsed.path)
            query = urllib.parse.parse_qs(parsed.query)
            if route == ["api", "v1", "session-cleanup"]:
                days = int(query.get("olderThanDays", ["30"])[0])
                self._send(
                    HTTPStatus.OK,
                    {"cleanup": self.server.manager.preview_session_cleanup(days)},
                )
                return
            if route == ["api", "v1", "models"]:
                backend = str(query.get("backend", [""])[0])
                self._send(
                    HTTPStatus.OK,
                    {"models": self.server.manager.model_catalog(backend)},
                )
                return
            if route == ["api", "v1", "sessions"]:
                backend = query.get("backend", [""])[0].lower()
                cwd = query.get("cwd", [""])[0]
                if backend not in {"grok", "codex"} or not cwd:
                    raise StoreError("backend and cwd query parameters are required")
                limit = int(query.get("limit", ["50"])[0])
                self._send(
                    HTTPStatus.OK,
                    {"sessions": self.server.manager.list_sessions(backend, cwd, limit)},
                )
                return
            if len(route) == 4 and route[:3] == ["api", "v1", "sessions"]:
                self._send(
                    HTTPStatus.OK,
                    self.server.manager.session_bundle(route[3]),
                )
                return
            if len(route) == 5 and route[:3] == ["api", "v1", "sessions"] and route[4] == "commands":
                self._send(
                    HTTPStatus.OK,
                    {"commands": self.server.manager.command_catalog(route[3])},
                )
                return
            if len(route) == 5 and route[:3] == ["api", "v1", "sessions"] and route[4] == "status":
                self._send(
                    HTTPStatus.OK,
                    {"status": self.server.manager.codex_status(route[3])},
                )
                return
            if len(route) == 5 and route[:3] == ["api", "v1", "sessions"] and route[4] == "events":
                after = int(query.get("after", ["0"])[0])
                wait = float(query.get("wait", ["0"])[0])
                events = self.server.manager.wait_for_events(route[3], after, wait)
                session = self.server.manager.store.get_session(route[3])
                self._send(HTTPStatus.OK, {"events": events, "session": session})
                return
            self.close_connection = True
            self.close_connection = True
            self._send(HTTPStatus.NOT_FOUND, {"error": "Endpoint not found"})
        except Exception as error:
            self._handle_error(error)

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        parsed = urllib.parse.urlsplit(self.path)
        if not self._require_auth():
            return
        try:
            route = self._route(parsed.path)
            if route == ["api", "v1", "sessions"]:
                body = self._json_body()
                codex_full_access = body.get("codexFullAccess", True)
                if not isinstance(codex_full_access, bool):
                    raise StoreError("codexFullAccess must be a boolean")
                session = self.server.manager.create_session(
                    str(body.get("backend") or ""),
                    str(body.get("cwd") or ""),
                    codex_full_access=codex_full_access,
                )
                self._send(HTTPStatus.CREATED, {"session": session})
                return
            if len(route) == 5 and route[:3] == ["api", "v1", "sessions"] and route[4] == "turns":
                body = self._json_body()
                attachment_ids = body.get("attachmentIds") or []
                if not isinstance(attachment_ids, list) or not all(
                    isinstance(item, str) for item in attachment_ids
                ):
                    raise StoreError("attachmentIds must be an array of strings")
                turn = self.server.manager.start_turn(
                    route[3],
                    str(body.get("prompt") or ""),
                    attachment_ids,
                )
                self._send(HTTPStatus.ACCEPTED, {"turn": turn})
                return
            if len(route) == 5 and route[:3] == ["api", "v1", "sessions"] and route[4] == "attachments":
                length = self._content_length(20 * 1024 * 1024)
                raw_name = self.headers.get("X-File-Name", "")
                if len(raw_name) > 1024:
                    raise StoreError("Image filename is too long")
                attachment = self.server.manager.upload_attachment(
                    route[3],
                    urllib.parse.unquote_plus(raw_name),
                    self.headers.get("Content-Type", "").split(";", 1)[0].strip(),
                    length,
                    self.rfile,
                )
                self._send(HTTPStatus.CREATED, {"attachment": attachment})
                return
            if len(route) == 5 and route[:3] == ["api", "v1", "sessions"] and route[4] == "model":
                body = self._json_body()
                session = self.server.manager.select_model(
                    route[3],
                    str(body.get("modelId") or ""),
                )
                self._send(HTTPStatus.OK, {"session": session})
                return
            if (
                len(route) == 5
                and route[:3] == ["api", "v1", "sessions"]
                and route[4] == "reasoning-effort"
            ):
                body = self._json_body()
                session = self.server.manager.select_reasoning_effort(
                    route[3],
                    str(body.get("reasoningEffort") or ""),
                )
                self._send(HTTPStatus.OK, {"session": session})
                return
            if len(route) == 5 and route[:3] == ["api", "v1", "sessions"] and route[4] == "cancel":
                self._json_body()
                cancelled = self.server.manager.cancel_session(route[3])
                self._send(HTTPStatus.ACCEPTED, {"cancelled": cancelled})
                return
            self.close_connection = True
            self._send(HTTPStatus.NOT_FOUND, {"error": "Endpoint not found"})
        except Exception as error:
            self._handle_error(error)

    def do_PATCH(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        parsed = urllib.parse.urlsplit(self.path)
        if not self._require_auth():
            return
        try:
            route = self._route(parsed.path)
            if len(route) == 4 and route[:3] == ["api", "v1", "sessions"]:
                body = self._json_body()
                allowed = {"title", "pinned", "unread"}
                unknown = set(body) - allowed
                if unknown:
                    raise StoreError(f"Unknown session fields: {', '.join(sorted(unknown))}")
                title = body.get("title")
                pinned = body.get("pinned")
                unread = body.get("unread")
                if title is not None and not isinstance(title, str):
                    raise StoreError("title must be a string")
                if pinned is not None and not isinstance(pinned, bool):
                    raise StoreError("pinned must be a boolean")
                if unread is not None and not isinstance(unread, bool):
                    raise StoreError("unread must be a boolean")
                session = self.server.manager.update_session_metadata(
                    route[3],
                    title=title,
                    pinned=pinned,
                    unread=unread,
                )
                self._send(HTTPStatus.OK, {"session": session})
                return
            self.close_connection = True
            self._send(HTTPStatus.NOT_FOUND, {"error": "Endpoint not found"})
        except Exception as error:
            self._handle_error(error)

    def do_DELETE(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        parsed = urllib.parse.urlsplit(self.path)
        if not self._require_auth():
            return
        try:
            route = self._route(parsed.path)
            query = urllib.parse.parse_qs(parsed.query)
            if route == ["api", "v1", "session-cleanup"]:
                days = int(query.get("olderThanDays", ["30"])[0])
                self._send(
                    HTTPStatus.OK,
                    {"cleanup": self.server.manager.delete_old_sessions(days)},
                )
                return
            if len(route) == 4 and route[:3] == ["api", "v1", "sessions"]:
                result = self.server.manager.delete_session(route[3])
                self._send(HTTPStatus.OK, result)
                return
            self.close_connection = True
            self._send(HTTPStatus.NOT_FOUND, {"error": "Endpoint not found"})
        except Exception as error:
            self._handle_error(error)

    def _handle_error(self, error: Exception) -> None:
        self.close_connection = True
        if isinstance(error, NotFoundError):
            status = HTTPStatus.NOT_FOUND
        elif isinstance(error, ConflictError):
            status = HTTPStatus.CONFLICT
        elif isinstance(error, (StoreError, ValueError)):
            status = HTTPStatus.BAD_REQUEST
        else:
            LOGGER.exception("Unhandled API error")
            status = HTTPStatus.INTERNAL_SERVER_ERROR
        message = str(error) if status != HTTPStatus.INTERNAL_SERVER_ERROR else "Internal server error"
        self._send(status, {"error": message})

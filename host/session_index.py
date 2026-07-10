#!/usr/bin/env python3
"""Lightweight session index HTTP API for AgentRemote.

Grok ACP does not implement session/list. This process reads
~/.grok/sessions/**/summary.json and serves a JSON list.

Auth: same secret as grok agent serve (Authorization: Bearer <secret>
or ?server-key= / X-Server-Key header).

Usage:
  python3 session_index.py --bind 0.0.0.0 --port 2420 --secret <token>
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.parse
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any


def grok_home() -> Path:
    return Path(os.environ.get("GROK_HOME") or Path.home() / ".grok")


def sessions_root() -> Path:
    return grok_home() / "sessions"


def parse_ts(value: Any) -> float:
    if value is None:
        return 0.0
    if isinstance(value, (int, float)):
        return float(value)
    s = str(value).strip()
    if not s:
        return 0.0
    try:
        # ISO with optional nanoseconds / Z
        if s.endswith("Z"):
            s = s[:-1] + "+00:00"
        # trim fractional seconds beyond microseconds
        if "." in s:
            head, rest = s.split(".", 1)
            frac = ""
            tz = ""
            for i, ch in enumerate(rest):
                if ch.isdigit():
                    frac += ch
                else:
                    tz = rest[i:]
                    break
            frac = (frac + "000000")[:6]
            s = f"{head}.{frac}{tz}"
        return datetime.fromisoformat(s).timestamp()
    except Exception:
        return 0.0


def int_or_none(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return None
    return parsed if parsed >= 0 else None


def iter_jsonl(path: Path):
    try:
        lines = path.open("r", encoding="utf-8")
    except OSError:
        return
    with lines:
        for line in lines:
            try:
                value = json.loads(line)
            except (TypeError, ValueError):
                continue
            if isinstance(value, dict):
                yield value


def first_turn_baseline(session_dir: Path) -> int | None:
    """Number of non-system history records inserted before the first real turn."""
    for event in iter_jsonl(session_dir / "events.jsonl"):
        if event.get("type") != "turn_started":
            continue
        return int_or_none(event.get("conversation_message_count"))
    return None


def message_text(message: dict[str, Any]) -> str:
    content = message.get("content")
    if isinstance(content, str):
        return content
    if not isinstance(content, list):
        return ""
    parts: list[str] = []
    for item in content:
        if not isinstance(item, dict):
            continue
        text = item.get("text") or item.get("content")
        if isinstance(text, str):
            parts.append(text)
    return "\n".join(parts)


def fallback_bootstrap_count(history: list[dict[str, Any]]) -> int:
    """Handle older sessions without turn events using Grok's synthetic prefix."""
    last_synthetic = -1
    for index, message in enumerate(history):
        if message.get("synthetic_reason") is not None:
            last_synthetic = index
        elif last_synthetic >= 0:
            break
    return last_synthetic + 1


def visible_message_count(summary_path: Path, data: dict[str, Any]) -> int:
    """Count chat bubbles AgentRemote renders, not Grok's internal records."""
    history = [
        message
        for message in iter_jsonl(summary_path.parent / "chat_history.jsonl")
        if message.get("type") != "system"
    ]
    if not history:
        turns = int_or_none(data.get("next_trace_turn"))
        if turns is not None:
            return turns * 2
        return int_or_none(data.get("num_chat_messages")) or 0

    baseline = first_turn_baseline(summary_path.parent)
    if baseline is None:
        baseline = fallback_bootstrap_count(history)
    conversation = history[min(baseline, len(history)) :]

    count = 0
    suppress_turn = False
    assistant_counted = False
    for message in conversation:
        message_type = message.get("type")
        if message_type == "user" and message.get("synthetic_reason") is None:
            suppress_turn = message_text(message).lstrip().startswith("/")
            assistant_counted = False
            if not suppress_turn:
                count += 1
        elif message_type == "assistant" and not suppress_turn and not assistant_counted:
            count += 1
            assistant_counted = True
    return count


def load_summaries(cwd_filter: str | None, limit: int) -> list[dict[str, Any]]:
    root = sessions_root()
    if not root.is_dir():
        return []

    items: list[dict[str, Any]] = []
    for summary_path in root.glob("*/*/summary.json"):
        try:
            data = json.loads(summary_path.read_text(encoding="utf-8"))
        except Exception:
            continue
        info = data.get("info") or {}
        sid = info.get("id") or summary_path.parent.name
        cwd = info.get("cwd") or ""
        if cwd_filter and cwd != cwd_filter:
            continue
        title = (
            data.get("generated_title")
            or data.get("session_summary")
            or "(no title)"
        )
        created = data.get("created_at")
        updated = data.get("last_active_at") or data.get("updated_at") or created
        items.append(
            {
                "sessionId": sid,
                "title": title if str(title).strip() else "(no title)",
                "cwd": cwd,
                "createdAt": created,
                "updatedAt": updated,
                "messageCount": visible_message_count(summary_path, data),
                "modelId": data.get("current_model_id"),
            }
        )

    items.sort(key=lambda x: parse_ts(x.get("updatedAt")), reverse=True)
    return items[: max(1, limit)]


class Handler(BaseHTTPRequestHandler):
    secret: str = ""

    def log_message(self, fmt: str, *args: Any) -> None:
        sys.stderr.write(
            "%s - %s\n" % (self.address_string(), fmt % args)
        )

    def _unauthorized(self) -> None:
        body = b'{"error":"unauthorized"}'
        self.send_response(401)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code: int, obj: Any) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self) -> bool:
        if not self.secret:
            return True
        auth = self.headers.get("Authorization", "")
        if auth.lower().startswith("bearer ") and auth[7:].strip() == self.secret:
            return True
        key = self.headers.get("X-Server-Key") or self.headers.get("X-Agent-Secret")
        if key and key.strip() == self.secret:
            return True
        parsed = urllib.parse.urlparse(self.path)
        qs = urllib.parse.parse_qs(parsed.query)
        sk = (qs.get("server-key") or qs.get("secret") or [None])[0]
        return bool(sk and sk == self.secret)

    def do_OPTIONS(self) -> None:  # noqa: N802
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
        self.send_header(
            "Access-Control-Allow-Headers",
            "Authorization, Content-Type, X-Server-Key, X-Agent-Secret",
        )
        self.end_headers()

    def do_GET(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path.rstrip("/") or "/"
        if path in ("/health", "/api/health"):
            self._json(200, {"ok": True})
            return
        if not self._authorized():
            self._unauthorized()
            return
        if path in ("/sessions", "/api/sessions"):
            qs = urllib.parse.parse_qs(parsed.query)
            cwd = (qs.get("cwd") or [None])[0]
            try:
                limit = int((qs.get("limit") or ["50"])[0])
            except ValueError:
                limit = 50
            limit = max(1, min(limit, 200))
            sessions = load_summaries(cwd, limit)
            self._json(
                200,
                {
                    "cwd": cwd,
                    "sessions": sessions,
                    "count": len(sessions),
                    "sessionsRoot": str(sessions_root()),
                },
            )
            return
        self._json(
            404,
            {
                "error": "not found",
                "endpoints": ["/health", "/api/sessions?cwd=<path>&limit=50"],
            },
        )


def main() -> int:
    ap = argparse.ArgumentParser(description="AgentRemote session index")
    ap.add_argument("--bind", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=2420)
    ap.add_argument(
        "--secret",
        default=os.environ.get("GROK_AGENT_SECRET", ""),
        help="Same secret as grok agent serve",
    )
    args = ap.parse_args()
    Handler.secret = args.secret or ""
    server = ThreadingHTTPServer((args.bind, args.port), Handler)
    print(
        f"session_index listening on http://{args.bind}:{args.port} "
        f"(sessions under {sessions_root()})",
        flush=True,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

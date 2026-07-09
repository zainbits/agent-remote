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
                "messageCount": data.get("num_chat_messages")
                or data.get("num_messages")
                or 0,
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

from __future__ import annotations

import argparse
import logging
import os
import secrets
import signal
import sys
from pathlib import Path

from .api import DurableHTTPServer
from .manager import JobManager
from .store import JobStore


def data_dir() -> Path:
    configured = os.environ.get("AGENTREMOTE_DATA_DIR")
    if configured:
        return Path(configured).expanduser()
    xdg = os.environ.get("XDG_DATA_HOME")
    return Path(xdg).expanduser() / "agentremote" if xdg else Path.home() / ".local/share/agentremote"


def token_path() -> Path:
    configured = os.environ.get("AGENTREMOTE_TOKEN_FILE")
    return Path(configured).expanduser() if configured else data_dir() / "server.token"


def database_path() -> Path:
    configured = os.environ.get("AGENTREMOTE_DATABASE")
    return Path(configured).expanduser() if configured else data_dir() / "jobs.sqlite3"


def ensure_token(rotate: bool = False) -> str:
    path = token_path()
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if rotate or not path.exists():
        token = secrets.token_urlsafe(48)
        temporary = path.with_suffix(".tmp")
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            handle.write(token + "\n")
        os.replace(temporary, path)
        os.chmod(path, 0o600)
        return token
    token = path.read_text(encoding="utf-8").strip()
    if not token:
        raise RuntimeError(f"Token file is empty: {path}")
    os.chmod(path, 0o600)
    return token


def parse_bind(value: str) -> tuple[str, int]:
    host, separator, raw_port = value.rpartition(":")
    if not separator or not host:
        raise ValueError("Bind must use HOST:PORT")
    port = int(raw_port)
    if not (1 <= port <= 65535):
        raise ValueError("Port must be between 1 and 65535")
    return host, port


def serve() -> int:
    bind = parse_bind(os.environ.get("AGENTREMOTE_BIND", "0.0.0.0:2440"))
    max_workers = int(os.environ.get("AGENTREMOTE_MAX_WORKERS", "4"))
    token = ensure_token()
    store = JobStore(database_path())
    manager = JobManager(store, max_workers=max_workers)
    server = DurableHTTPServer(bind, manager, token)

    logging.basicConfig(
        level=os.environ.get("AGENTREMOTE_LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    logging.getLogger("agentremote.host").info(
        "durable service listening on http://%s:%d (database %s)",
        bind[0],
        bind[1],
        database_path(),
    )

    def stop(_signum: int, _frame: object) -> None:
        # shutdown() must run outside the serve_forever thread.
        import threading

        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        server.serve_forever(poll_interval=0.5)
    finally:
        server.server_close()
        manager.shutdown()
    return 0


def main(argv: list[str] | None = None) -> int:
    os.umask(0o077)
    parser = argparse.ArgumentParser(description="AgentRemote durable host service")
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("serve", help="Run the HTTP job service")
    subparsers.add_parser("init", help="Create runtime directories, token, and database")
    subparsers.add_parser("show-token", help="Print the existing host token")
    subparsers.add_parser("rotate-token", help="Replace the host token and print the new value")
    args = parser.parse_args(argv)

    if args.command == "serve":
        return serve()
    if args.command == "init":
        ensure_token()
        JobStore(database_path())
        return 0
    if args.command == "show-token":
        path = token_path()
        if not path.exists():
            print(f"Token does not exist yet: {path}", file=sys.stderr)
            return 1
        print(ensure_token())
        return 0
    if args.command == "rotate-token":
        print(ensure_token(rotate=True))
        return 0
    return 2


if __name__ == "__main__":
    raise SystemExit(main())

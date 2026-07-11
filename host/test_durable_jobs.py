import json
import os
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

from host.durable_jobs.api import DurableHTTPServer
from host.durable_jobs.manager import JobManager
from host.durable_jobs.store import ConflictError, JobStore


FAKE_CODEX = r'''#!/usr/bin/env python3
import json
import os
import sys
import time

prompt = sys.stdin.read()
print(json.dumps({"type": "thread.started", "thread_id": "codex-thread-test"}), flush=True)
print(json.dumps({"type": "turn.started"}), flush=True)
if "WAIT_FOR_CANCEL" in prompt:
    time.sleep(30)
if "SLOW" in prompt:
    time.sleep(0.4)
print(json.dumps({"type": "item.completed", "item": {
    "id": "agent", "type": "agent_message", "text": "codex-result"
}}), flush=True)
print(json.dumps({"type": "turn.completed", "usage": {
    "input_tokens": 10, "output_tokens": 4
}}), flush=True)
'''


FAKE_GROK = r'''#!/usr/bin/env python3
import json
import sys

print(json.dumps({"type": "thought", "data": "checking"}), flush=True)
print(json.dumps({"type": "text", "data": "grok-result"}), flush=True)
print(json.dumps({
    "type": "end", "stopReason": "EndTurn", "sessionId": "grok-session-test"
}), flush=True)
'''


class DurableJobsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        root = Path(self.temporary.name)
        self.workspace = root / "workspace"
        self.workspace.mkdir()
        self.codex = self._executable(root / "fake-codex", FAKE_CODEX)
        self.grok = self._executable(root / "fake-grok", FAKE_GROK)
        self.store = JobStore(root / "jobs.sqlite3")
        self.manager = JobManager(
            self.store,
            max_workers=3,
            codex_bin=str(self.codex),
            grok_bin=str(self.grok),
        )

    def tearDown(self):
        self.manager.shutdown()
        self.temporary.cleanup()

    @staticmethod
    def _executable(path: Path, source: str) -> Path:
        path.write_text(source, encoding="utf-8")
        path.chmod(0o755)
        return path

    def _wait_terminal(self, session_id: str, timeout: float = 5.0):
        deadline = time.monotonic() + timeout
        cursor = 0
        observed = []
        while time.monotonic() < deadline:
            events = self.manager.wait_for_events(session_id, cursor, 0.5)
            observed.extend(events)
            if events:
                cursor = events[-1]["id"]
            session = self.store.get_session(session_id)
            if session["status"] not in {"queued", "running", "cancelling"}:
                return session, observed
        self.fail("durable turn did not reach a terminal state")

    def test_codex_turn_completes_without_an_observer(self):
        session = self.manager.create_session("codex", str(self.workspace))
        self.manager.start_turn(session["id"], "do the durable task")

        # No client subscribes while the worker runs. State is recovered afterwards.
        terminal, events = self._wait_terminal(session["id"])
        bundle = self.store.session_bundle(session["id"])

        self.assertEqual("idle", terminal["status"])
        self.assertEqual("codex-thread-test", terminal["backendSessionId"])
        self.assertEqual(["user", "assistant"], [item["role"] for item in bundle["messages"]])
        self.assertEqual("codex-result", bundle["messages"][-1]["text"])
        self.assertIn("turn.completed", [event["type"] for event in events])

    def test_grok_turn_is_persisted(self):
        session = self.manager.create_session("grok", str(self.workspace))
        self.manager.start_turn(session["id"], "run grok")
        terminal, _ = self._wait_terminal(session["id"])
        bundle = self.store.session_bundle(session["id"])

        self.assertEqual("grok-session-test", terminal["backendSessionId"])
        self.assertEqual("grok-result", bundle["messages"][-1]["text"])
        self.assertEqual(["user", "thought", "assistant"], [m["role"] for m in bundle["messages"]])

    def test_same_session_rejects_overlapping_turns(self):
        session = self.manager.create_session("codex", str(self.workspace))
        self.manager.start_turn(session["id"], "WAIT_FOR_CANCEL")
        with self.assertRaises(ConflictError):
            self.manager.start_turn(session["id"], "second prompt")
        self.assertTrue(self.manager.cancel_session(session["id"]))
        terminal, _ = self._wait_terminal(session["id"])
        self.assertEqual("cancelled", terminal["status"])

    def test_different_sessions_run_concurrently(self):
        first = self.manager.create_session("codex", str(self.workspace))
        second = self.manager.create_session("codex", str(self.workspace))
        self.manager.start_turn(first["id"], "SLOW first")
        self.manager.start_turn(second["id"], "SLOW second")

        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            statuses = {
                self.store.get_session(first["id"])["status"],
                self.store.get_session(second["id"])["status"],
            }
            if statuses == {"running"}:
                break
            self.manager.wait_for_events(first["id"], 0, 0.05)
        self.assertEqual("running", self.store.get_session(first["id"])["status"])
        self.assertEqual("running", self.store.get_session(second["id"])["status"])
        self._wait_terminal(first["id"])
        self._wait_terminal(second["id"])

    def test_authenticated_http_api(self):
        token = "test-token"
        server = DurableHTTPServer(("127.0.0.1", 0), self.manager, token)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f"http://127.0.0.1:{server.server_address[1]}"
        try:
            request = urllib.request.Request(
                base + "/api/v1/sessions",
                data=json.dumps({"backend": "codex", "cwd": str(self.workspace)}).encode(),
                headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(request, timeout=2) as response:
                created = json.load(response)
            self.assertEqual("codex", created["session"]["backend"])

            with self.assertRaises(urllib.error.HTTPError) as unauthorized:
                urllib.request.urlopen(base + "/api/v1/sessions?backend=codex&cwd=/", timeout=2)
            self.assertEqual(401, unauthorized.exception.code)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_database_permissions_are_private(self):
        mode = os.stat(self.store.database_path).st_mode & 0o777
        self.assertEqual(0o600, mode)

    def test_legacy_session_is_adopted_once_with_history(self):
        first = self.store.import_session(
            "codex",
            "legacy-thread",
            str(self.workspace),
            "Legacy task",
        )
        second = self.store.import_session(
            "codex",
            "legacy-thread",
            str(self.workspace),
            "Duplicate discovery",
        )
        self.assertEqual(first["id"], second["id"])
        self.assertTrue(
            self.store.import_history(
                first["id"],
                [
                    {"turnKey": "one", "role": "user", "text": "old prompt"},
                    {"turnKey": "one", "role": "assistant", "text": "old answer"},
                ],
            )
        )
        self.assertFalse(self.store.import_history(first["id"], []))
        bundle = self.store.session_bundle(first["id"])
        self.assertEqual(["old prompt", "old answer"], [m["text"] for m in bundle["messages"]])


if __name__ == "__main__":
    unittest.main()

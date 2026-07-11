import json
import os
import sqlite3
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

from host.durable_jobs.api import DurableHTTPServer
from host.durable_jobs.manager import JobManager
from host.durable_jobs.metadata import MetadataProvider
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

prompt = ""
if "--prompt-file" in sys.argv:
    with open(sys.argv[sys.argv.index("--prompt-file") + 1], encoding="utf-8") as source:
        prompt = source.read().strip()
print(json.dumps({"type": "thought", "data": "checking"}), flush=True)
if prompt == "/session-info":
    print(json.dumps({"type": "text", "data": (
        "**Session ID:** grok-session-test\n\n"
        "**Working directory:** /tmp\n\n"
        "**Model:** grok-test\n\n"
        "**Turn:** 1\n\n"
        "**Context:** 2,651 / 500,000 tokens (1%)"
    )}), flush=True)
elif prompt.startswith("/compact"):
    print(json.dumps({"type": "auto_compact_completed"}), flush=True)
else:
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
        self.metadata = MetadataProvider(
            grok_bin=str(self.grok),
            home=root,
            probe_grok=False,
        )
        self.manager = JobManager(
            self.store,
            max_workers=3,
            codex_bin=str(self.codex),
            grok_bin=str(self.grok),
            metadata=self.metadata,
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

    def test_grok_context_alias_returns_report_and_updates_usage(self):
        session = self.manager.create_session("grok", str(self.workspace))
        self.manager.start_turn(session["id"], "/context")
        terminal, _ = self._wait_terminal(session["id"])
        bundle = self.store.session_bundle(session["id"])

        self.assertEqual("idle", terminal["status"])
        self.assertEqual("/context", bundle["messages"][0]["text"])
        self.assertIn("**Context:** 2,651 / 500,000 tokens", bundle["messages"][-1]["text"])
        self.assertEqual(2651, terminal["usedTokens"])
        self.assertEqual(500000, terminal["contextWindowTokens"])
        self.assertEqual("grok-test", terminal["modelId"])

    def test_grok_compact_synthesizes_a_persisted_report(self):
        session = self.manager.create_session("grok", str(self.workspace))
        self.manager.start_turn(session["id"], "/compact")
        _, events = self._wait_terminal(session["id"])
        bundle = self.store.session_bundle(session["id"])

        assistant = [message for message in bundle["messages"] if message["role"] == "assistant"]
        self.assertEqual(1, len(assistant))
        self.assertIn("compacted successfully", assistant[0]["text"])
        self.assertIn("message.delta", [event["type"] for event in events])

    def test_full_usage_metadata_is_merged_and_emitted(self):
        session = self.manager.create_session("codex", str(self.workspace))
        merged = self.store.update_usage(
            session["id"],
            {
                "modelId": "codex-test",
                "modelName": "Codex Test",
                "reasoningEffort": "high",
                "usedTokens": 123,
                "contextWindowTokens": 4096,
            },
        )
        event = self.store.events_after(session["id"], 0)[-1]

        self.assertEqual("Codex Test", merged["modelName"])
        self.assertEqual("high", merged["reasoningEffort"])
        self.assertEqual(4096, merged["contextWindowTokens"])
        self.assertEqual("usage.updated", event["type"])
        self.assertEqual("codex-test", event["data"]["modelId"])
        self.assertEqual(123, event["data"]["usedTokens"])

    def test_new_codex_session_seeds_configured_model_and_effort(self):
        config = self.temporary.name + "/.codex"
        os.makedirs(config, exist_ok=True)
        Path(config, "config.toml").write_text(
            'model = "gpt-test"\nmodel_reasoning_effort = "xhigh"\n',
            encoding="utf-8",
        )

        session = self.manager.create_session("codex", str(self.workspace))

        self.assertEqual("gpt-test", session["modelId"])
        self.assertEqual("gpt-test", session["modelName"])
        self.assertEqual("xhigh", session["reasoningEffort"])

    def test_grok_command_catalog_includes_builtins_and_installed_skills(self):
        skill = Path(self.temporary.name, ".grok", "skills", "verify", "SKILL.md")
        skill.parent.mkdir(parents=True)
        skill.write_text(
            "---\nname: verify\ndescription: Verify the current work\n"
            'argument-hint: "optional focus"\n---\n',
            encoding="utf-8",
        )

        commands = self.metadata.commands_for("grok")
        by_name = {command["name"]: command for command in commands}

        self.assertIn("compact", by_name)
        self.assertIn("session-info", by_name)
        self.assertEqual("optional focus", by_name["verify"]["argumentHint"])
        self.assertEqual([], self.metadata.commands_for("codex"))

        session = self.manager.create_session("grok", str(self.workspace))
        bundle = self.manager.session_bundle(session["id"])
        self.assertIn("verify", {command["name"] for command in bundle["commands"]})

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

            commands_request = urllib.request.Request(
                base + f"/api/v1/sessions/{created['session']['id']}/commands",
                headers={"Authorization": f"Bearer {token}"},
            )
            with urllib.request.urlopen(commands_request, timeout=2) as response:
                commands = json.load(response)
            self.assertEqual([], commands["commands"])

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

    def test_existing_v1_database_adds_model_name_column(self):
        path = Path(self.temporary.name, "v1.sqlite3")
        with sqlite3.connect(path) as connection:
            connection.execute(
                """
                CREATE TABLE sessions (
                    id TEXT PRIMARY KEY,
                    backend TEXT NOT NULL,
                    backend_session_id TEXT,
                    cwd TEXT NOT NULL,
                    title TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'idle',
                    active_turn_id TEXT,
                    model_id TEXT,
                    reasoning_effort TEXT,
                    used_tokens INTEGER,
                    context_window_tokens INTEGER,
                    last_error TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """
            )
            connection.execute("PRAGMA user_version = 1")

        migrated = JobStore(path)
        with migrated._connect() as connection:  # Verify the on-open migration contract.
            columns = {row["name"] for row in connection.execute("PRAGMA table_info(sessions)")}
            version = connection.execute("PRAGMA user_version").fetchone()[0]

        self.assertIn("model_name", columns)
        self.assertEqual(2, version)

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

import io
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
from unittest import mock

from host.durable_jobs.api import DurableHTTPServer
from host.durable_jobs.catalog import LegacyCatalog
from host.durable_jobs.manager import JobManager
from host.durable_jobs.metadata import MetadataProvider
from host.durable_jobs.store import ConflictError, JobStore, NotFoundError, StoreError


FAKE_CODEX = r'''#!/usr/bin/env python3
import json
import os
import sys
import time

if sys.argv[1:3] == ["debug", "models"]:
    print(json.dumps({"models": [
        {
            "slug": "gpt-test",
            "display_name": "GPT Test",
            "description": "Default test model",
            "default_reasoning_level": "high",
            "supported_reasoning_levels": [{"effort": "low"}, {"effort": "high"}],
            "context_window": 4096,
            "visibility": "list"
        },
        {
            "slug": "gpt-fast",
            "display_name": "GPT Fast",
            "description": "Fast test model",
            "default_reasoning_level": "low",
            "supported_reasoning_levels": [{"effort": "low"}],
            "context_window": 2048,
            "visibility": "list"
        }
    ]}))
    raise SystemExit(0)

if "app-server" in sys.argv:
    for line in sys.stdin:
        request = json.loads(line)
        request_id = request.get("id")
        if request_id is None:
            continue
        method = request.get("method")
        if method == "thread/read":
            result = {"thread": {
                "id": request.get("params", {}).get("threadId"),
                "cliVersion": "0.test",
                "cwd": os.getcwd(),
                "modelProvider": "openai",
                "model": "gpt-old",
                "reasoningEffort": "high",
            }}
        elif method == "account/read":
            result = {"requiresOpenaiAuth": True, "account": {
                "type": "chatgpt", "email": "test@example.invalid", "planType": "plus"
            }}
        elif method == "account/rateLimits/read":
            result = {"rateLimits": {
                "limitId": "codex",
                "primary": {"usedPercent": 40, "windowDurationMins": 300, "resetsAt": 2000000000},
                "secondary": {"usedPercent": 25, "windowDurationMins": 10080, "resetsAt": 2000600000},
            }, "rateLimitResetCredits": {"availableCount": 2, "credits": []}}
        else:
            result = {}
        print(json.dumps({"id": request_id, "result": result}), flush=True)
    raise SystemExit(0)

prompt = sys.stdin.read()
print(json.dumps({"type": "thread.started", "thread_id": "codex-thread-test"}), flush=True)
print(json.dumps({"type": "turn.started"}), flush=True)
if "WAIT_FOR_CANCEL" in prompt:
    time.sleep(30)
if "SLOW" in prompt:
    time.sleep(0.4)
if "INTERLEAVED" in prompt:
    print(json.dumps({"type": "item.completed", "item": {
        "id": "commentary", "type": "agent_message", "text": "checking first"
    }}), flush=True)
    print(json.dumps({"type": "item.started", "item": {
        "id": "tool", "type": "command_execution", "command": "test command",
        "status": "in_progress"
    }}), flush=True)
    print(json.dumps({"type": "item.completed", "item": {
        "id": "tool", "type": "command_execution", "command": "test command",
        "aggregated_output": "done", "exit_code": 0, "status": "completed"
    }}), flush=True)
    print(json.dumps({"type": "item.started", "item": {
        "id": "final", "type": "agent_message", "text": "final "
    }}), flush=True)
    print(json.dumps({"type": "item.updated", "item": {
        "id": "final", "type": "agent_message", "text": "final answer"
    }}), flush=True)
    print(json.dumps({"type": "item.completed", "item": {
        "id": "final", "type": "agent_message", "text": "final answer"
    }}), flush=True)
else:
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
elif "--prompt-json" in sys.argv:
    value = json.loads(sys.argv[sys.argv.index("--prompt-json") + 1])
    prompt = "\n".join(
        block.get("text", "") for block in value.get("content", [])
        if block.get("type") == "text"
    ).strip()
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
            codex_bin=str(self.codex),
            home=root,
            probe_grok=False,
        )
        self.metadata._grok_probe_attempted = True
        self.metadata._grok_model_state = {
            "currentModelId": "grok-test",
            "availableModels": [
                {
                    "modelId": "grok-test",
                    "name": "Grok Test",
                    "description": "Default test model",
                    "_meta": {
                        "totalContextTokens": 500000,
                        "reasoningEffort": "high",
                        "reasoningEfforts": [
                            {"value": "low"},
                            {"value": "high"},
                        ],
                    },
                },
                {
                    "modelId": "grok-fast",
                    "name": "Grok Fast",
                    "description": "Fast test model",
                    "_meta": {"totalContextTokens": 200000},
                },
            ],
        }
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

    def test_legacy_catalog_sync_is_cached_and_single_flight_per_key(self):
        catalog = LegacyCatalog(
            self.store,
            codex_bin=str(self.codex),
            sync_ttl_seconds=30,
        )
        calls: list[tuple[str, int]] = []
        catalog._sync_codex = lambda cwd, limit: calls.append((cwd, limit))

        catalog.sync("codex", str(self.workspace), 50)
        catalog.sync("codex", str(self.workspace), 50)
        catalog.sync("codex", str(self.workspace), 100)

        self.assertEqual(
            [(str(self.workspace), 50), (str(self.workspace), 100)],
            calls,
        )

    def test_codex_interleaved_items_keep_live_and_replay_order(self):
        session = self.manager.create_session("codex", str(self.workspace))
        self.manager.start_turn(session["id"], "INTERLEAVED")

        _, events = self._wait_terminal(session["id"])
        bundle = self.store.session_bundle(session["id"])
        messages = bundle["messages"]

        self.assertEqual(
            ["user", "assistant", "tool", "assistant"],
            [message["role"] for message in messages],
        )
        self.assertEqual("checking first", messages[1]["text"])
        self.assertEqual("final answer", messages[-1]["text"])
        self.assertEqual("completed", messages[-1]["status"])
        self.assertNotEqual(messages[1]["id"], messages[-1]["id"])
        self.assertEqual(list(range(4)), [message["ordinal"] for message in messages])

        timeline = []
        for event in events:
            if event["type"] == "message.delta":
                timeline.append((event["type"], event["data"].get("delta")))
            elif event["type"] == "tool.updated":
                timeline.append((event["type"], event["data"].get("status")))
        self.assertEqual(
            [
                ("message.delta", "checking first"),
                ("tool.updated", "in_progress"),
                ("tool.updated", "completed"),
                ("message.delta", "final "),
                ("message.delta", "answer"),
            ],
            timeline,
        )

    def test_message_snapshots_are_idempotent_and_can_replace_text(self):
        session = self.manager.create_session("codex", str(self.workspace))
        turn = self.store.create_turn(session["id"], "snapshot test")
        message_id = f'{turn["id"]}:snapshot'

        self.assertTrue(
            self.store.upsert_message_snapshot(
                session["id"], turn["id"], message_id, "assistant", "draft", False
            )
        )
        self.assertFalse(
            self.store.upsert_message_snapshot(
                session["id"], turn["id"], message_id, "assistant", "draft", False
            )
        )
        self.assertTrue(
            self.store.upsert_message_snapshot(
                session["id"], turn["id"], message_id, "assistant", "rewritten", True
            )
        )
        self.assertFalse(
            self.store.upsert_message_snapshot(
                session["id"], turn["id"], message_id, "assistant", "rewritten", True
            )
        )

        bundle = self.store.session_bundle(session["id"])
        self.assertEqual("rewritten", bundle["messages"][-1]["text"])
        self.assertEqual("completed", bundle["messages"][-1]["status"])
        event_types = [event["type"] for event in self.store.events_after(session["id"], 0)]
        self.assertEqual(1, event_types.count("message.replaced"))
        self.assertEqual(1, event_types.count("message.completed"))

    def test_unchanged_tool_snapshots_do_not_duplicate_replay_events(self):
        session = self.manager.create_session("codex", str(self.workspace))
        turn = self.store.create_turn(session["id"], "tool snapshot test")
        message_id = f'{turn["id"]}:tool'

        self.assertTrue(
            self.store.upsert_tool(
                session["id"], turn["id"], message_id,
                "Command", "in_progress", "command_execution", "output",
            )
        )
        self.assertFalse(
            self.store.upsert_tool(
                session["id"], turn["id"], message_id,
                "Command", "in_progress", "command_execution", "output",
            )
        )
        self.assertTrue(
            self.store.upsert_tool(
                session["id"], turn["id"], message_id,
                "Command", "completed", "command_execution", "output",
            )
        )

        events = self.store.events_after(session["id"], 0)
        self.assertEqual(2, [event["type"] for event in events].count("tool.updated"))

    def test_grok_turn_is_persisted(self):
        session = self.manager.create_session("grok", str(self.workspace))
        self.manager.start_turn(session["id"], "run grok")
        terminal, _ = self._wait_terminal(session["id"])
        bundle = self.store.session_bundle(session["id"])

        self.assertEqual("grok-session-test", terminal["backendSessionId"])
        self.assertEqual("grok-result", bundle["messages"][-1]["text"])
        self.assertEqual(["user", "thought", "assistant"], [m["role"] for m in bundle["messages"]])

    def test_images_are_durable_and_mapped_to_backend_commands(self):
        session = self.manager.create_session("codex", str(self.workspace))
        image_bytes = b"\x89PNG\r\n\x1a\n" + b"test-image"
        attachment = self.manager.upload_attachment(
            session["id"],
            "screen.png",
            "image/png",
            len(image_bytes),
            io.BytesIO(image_bytes),
        )

        turn = self.store.create_turn(session["id"], "", [attachment["id"]])
        command = self.manager._command(
            session,
            turn["prompt"],
            attachments=turn["attachments"],
        )
        bundle = self.store.session_bundle(session["id"])
        event = next(
            item for item in self.store.events_after(session["id"], 0)
            if item["type"] == "message.created"
        )

        self.assertEqual("", bundle["messages"][0]["text"])
        self.assertEqual("screen.png", bundle["messages"][0]["attachments"][0]["fileName"])
        self.assertNotIn("path", bundle["messages"][0]["attachments"][0])
        self.assertEqual("screen.png", event["data"]["attachments"][0]["fileName"])
        self.assertEqual(
            turn["attachments"][0]["path"],
            command[command.index("--image") + 1],
        )
        self.assertTrue(Path(turn["attachments"][0]["path"]).is_file())

        self.store.finish_turn(turn["id"], "completed")
        with self.assertRaises(ConflictError):
            self.store.create_turn(session["id"], "reuse", [attachment["id"]])

    def test_grok_images_use_compact_acp_resource_links(self):
        session = self.manager.create_session("grok", str(self.workspace))
        attachment = {
            "path": str(self.workspace / "image.png"),
            "fileName": "image.png",
            "mimeType": "image/png",
            "sizeBytes": 123,
        }

        command = self.manager._command(session, "inspect this", attachments=[attachment])
        payload = json.loads(command[command.index("--prompt-json") + 1])

        self.assertEqual("acp", payload["type"])
        self.assertEqual(["text", "resource_link"], [item["type"] for item in payload["content"]])
        self.assertEqual("inspect this", payload["content"][0]["text"])
        self.assertEqual("image/png", payload["content"][1]["mimeType"])

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

    def test_codex_workers_default_to_full_access_for_new_and_resumed_turns(self):
        session = self.manager.create_session("codex", str(self.workspace))
        new_command = self.manager._command(session, "first")
        self.assertTrue(session["codexFullAccess"])
        self.assertIn("--dangerously-bypass-approvals-and-sandbox", new_command)
        self.assertNotIn("sandbox_workspace_write.network_access=true", new_command)

        self.store.set_backend_session_id(session["id"], "existing-thread")
        resumed = self.manager._command(self.store.get_session(session["id"]), "next")
        self.assertIn("resume", resumed)
        self.assertIn("--dangerously-bypass-approvals-and-sandbox", resumed)

    def test_codex_workers_can_opt_out_to_networked_workspace_sandbox(self):
        session = self.manager.create_session(
            "codex",
            str(self.workspace),
            codex_full_access=False,
        )
        new_command = self.manager._command(session, "first")
        self.assertFalse(session["codexFullAccess"])
        self.assertIn("sandbox_workspace_write.network_access=true", new_command)
        self.assertIn('sandbox_mode="workspace-write"', new_command)
        self.assertNotIn("--dangerously-bypass-approvals-and-sandbox", new_command)

        self.store.set_backend_session_id(session["id"], "existing-thread")
        resumed = self.manager._command(self.store.get_session(session["id"]), "next")
        self.assertIn("resume", resumed)
        self.assertIn("sandbox_workspace_write.network_access=true", resumed)
        self.assertIn('sandbox_mode="workspace-write"', resumed)

    def test_selected_model_is_persisted_and_applied_to_new_and_resumed_turns(self):
        session = self.manager.create_session("codex", str(self.workspace))

        selected = self.manager.select_model(session["id"], "gpt-fast")

        self.assertEqual("gpt-fast", selected["modelOverride"])
        self.assertEqual("GPT Fast", selected["modelName"])
        self.assertEqual("low", selected["reasoningEffort"])
        self.assertEqual(2048, selected["contextWindowTokens"])
        new_command = self.manager._command(selected, "first")
        self.assertEqual("gpt-fast", new_command[new_command.index("--model") + 1])
        self.assertIn('model_reasoning_effort="low"', new_command)

        self.store.set_backend_session_id(session["id"], "existing-thread")
        resumed = self.manager._command(self.store.get_session(session["id"]), "next")
        self.assertIn("resume", resumed)
        self.assertEqual("gpt-fast", resumed[resumed.index("--model") + 1])

        bundle = self.manager.session_bundle(session["id"])
        self.assertEqual("gpt-fast", bundle["session"]["modelId"])
        self.assertEqual("GPT Fast", bundle["session"]["modelName"])

    def test_grok_selected_model_is_applied_without_unsupported_effort(self):
        session = self.manager.create_session("grok", str(self.workspace))

        selected = self.manager.select_model(session["id"], "grok-fast")
        command = self.manager._command(selected, "first", "/tmp/prompt")

        self.assertEqual("grok-fast", selected["modelOverride"])
        self.assertIsNone(selected["reasoningEffort"])
        self.assertEqual("grok-fast", command[command.index("--model") + 1])
        self.assertNotIn("--reasoning-effort", command)

    def test_model_change_is_rejected_while_turn_is_active(self):
        session = self.manager.create_session("codex", str(self.workspace))
        self.manager.start_turn(session["id"], "WAIT_FOR_CANCEL")

        with self.assertRaises(ConflictError):
            self.manager.select_model(session["id"], "gpt-fast")

        self.assertTrue(self.manager.cancel_session(session["id"]))
        self._wait_terminal(session["id"])

    def test_codex_rollout_status_matches_native_context_fields(self):
        rollout = self.workspace / "rollout.jsonl"
        records = [
            {
                "type": "world_state",
                "payload": {
                    "state": {
                        "agents_md": {"directory": str(self.workspace), "text": "rules"},
                    },
                },
            },
            {
                "type": "turn_context",
                "payload": {
                    "model": "gpt-test",
                    "effort": "xhigh",
                    "summary": "auto",
                    "approval_policy": "never",
                    "approvals_reviewer": "user",
                    "collaboration_mode": {"mode": "default", "settings": {}},
                    "sandbox_policy": {"type": "workspace-write", "network_access": True},
                },
            },
            {
                "type": "event_msg",
                "payload": {
                    "type": "token_count",
                    "info": {
                        "last_token_usage": {
                            "input_tokens": 12000,
                            "cached_input_tokens": 9000,
                            "output_tokens": 345,
                            "reasoning_output_tokens": 100,
                            "total_tokens": 12345,
                        },
                        "total_token_usage": {
                            "input_tokens": 20000,
                            "cached_input_tokens": 10000,
                            "output_tokens": 2000,
                            "reasoning_output_tokens": 1000,
                            "total_tokens": 22000,
                        },
                        "model_context_window": 353400,
                    },
                },
            },
        ]
        rollout.write_text("\n".join(json.dumps(record) for record in records) + "\n", encoding="utf-8")

        status = self.manager.catalog._codex_rollout_status(rollout)

        self.assertEqual(12345, status["contextUsedTokens"])
        self.assertEqual(353400, status["contextWindowTokens"])
        self.assertEqual("auto", status["reasoningSummary"])
        self.assertEqual("default", status["collaborationMode"])
        self.assertTrue(status["networkAccess"])
        self.assertEqual([str(self.workspace / "AGENTS.md")], status["agentsFiles"])

    def test_codex_status_reads_account_limits_and_reports_worker_policy(self):
        session = self.manager.create_session("codex", str(self.workspace))

        status = self.manager.codex_status(session["id"])

        self.assertEqual("plus", status["account"]["planType"])
        self.assertEqual(40, status["rateLimits"]["primary"]["usedPercent"])
        self.assertEqual(25, status["rateLimits"]["secondary"]["usedPercent"])
        self.assertEqual(2, status["rateLimitResetCreditsAvailable"])
        self.assertEqual("danger-full-access", status["sandboxMode"])
        self.assertTrue(status["networkAccess"])
        self.assertEqual("never", status["approvalPolicy"])

        sandboxed = self.manager.create_session(
            "codex",
            str(self.workspace),
            codex_full_access=False,
        )
        sandboxed_status = self.manager.codex_status(sandboxed["id"])
        self.assertEqual("workspace-write", sandboxed_status["sandboxMode"])

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

    def test_delete_session_removes_backend_history_rows_and_attachments(self):
        session = self.manager.create_session("grok", str(self.workspace))
        image_bytes = b"\x89PNG\r\n\x1a\n" + b"delete-image"
        attachment = self.manager.upload_attachment(
            session["id"],
            "delete.png",
            "image/png",
            len(image_bytes),
            io.BytesIO(image_bytes),
        )
        attachment_path = Path(self.store.attachment_directory, f"{attachment['id']}.png")
        turn = self.store.create_turn(session["id"], "delete this", [attachment["id"]])
        self.store.finish_turn(turn["id"], "completed")
        self.store.set_backend_session_id(session["id"], "linked-grok-session")

        with mock.patch("host.durable_jobs.manager.subprocess.run") as delete:
            delete.return_value.returncode = 0
            result = self.manager.delete_session(session["id"])

        self.assertEqual({"deleted": True}, result)
        self.assertEqual(
            [str(self.grok), "sessions", "delete", "linked-grok-session"],
            delete.call_args.args[0],
        )
        with self.assertRaises(NotFoundError):
            self.store.get_session(session["id"])
        self.assertFalse(attachment_path.exists())
        with self.store._connect() as connection:
            self.assertEqual(0, connection.execute(
                "SELECT COUNT(*) FROM turns WHERE session_id = ?", (session["id"],)
            ).fetchone()[0])
            self.assertEqual(0, connection.execute(
                "SELECT COUNT(*) FROM messages WHERE session_id = ?", (session["id"],)
            ).fetchone()[0])
            self.assertEqual(0, connection.execute(
                "SELECT COUNT(*) FROM events WHERE session_id = ?", (session["id"],)
            ).fetchone()[0])

    def test_delete_session_rejects_active_turn(self):
        session = self.store.create_session("grok", str(self.workspace))
        turn = self.store.create_turn(session["id"], "still active")

        with mock.patch("host.durable_jobs.manager.subprocess.run") as delete:
            with self.assertRaises(ConflictError):
                self.manager.delete_session(session["id"])
            delete.assert_not_called()

        self.store.finish_turn(turn["id"], "cancelled")

    def test_backend_delete_failure_keeps_agentremote_session(self):
        session = self.store.create_session("codex", str(self.workspace))
        self.store.set_backend_session_id(session["id"], "linked-codex-session")

        with mock.patch("host.durable_jobs.manager.subprocess.run") as delete:
            delete.return_value.returncode = 1
            with self.assertRaises(StoreError):
                self.manager.delete_session(session["id"])

        self.assertEqual(session["id"], self.store.get_session(session["id"])["id"])
        self.assertEqual(
            [str(self.codex), "delete", "--force", "linked-codex-session"],
            delete.call_args.args[0],
        )

    def test_old_session_cleanup_merges_full_catalog_and_preserves_protected_sessions(self):
        old_timestamp = "2020-01-01T00:00:00.000Z"
        linked_grok = self.store.create_session("grok", str(self.workspace))
        self.store.set_backend_session_id(linked_grok["id"], "old-linked-grok")
        local_placeholder = self.store.create_session("codex", str(self.workspace))
        pinned = self.store.create_session("codex", str(self.workspace))
        self.store.set_backend_session_id(pinned["id"], "old-pinned-codex")
        self.store.update_session_metadata(pinned["id"], pinned=True)
        active = self.store.create_session("grok", str(self.workspace))
        self.store.set_backend_session_id(active["id"], "old-active-grok")
        active_turn = self.store.create_turn(active["id"], "still active")
        recent = self.store.create_session("codex", str(self.workspace))
        self.store.set_backend_session_id(recent["id"], "recent-linked-codex")

        with self.store._connect() as connection:
            connection.execute(
                "UPDATE sessions SET updated_at = ? WHERE id IN (?, ?, ?, ?)",
                (
                    old_timestamp,
                    linked_grok["id"],
                    local_placeholder["id"],
                    pinned["id"],
                    active["id"],
                ),
            )

        catalog = [
            {
                "backend": "grok",
                "backendSessionId": "old-linked-grok",
                "updatedAt": old_timestamp,
            },
            {
                "backend": "codex",
                "backendSessionId": "old-external-codex",
                "updatedAt": old_timestamp,
            },
            {
                "backend": "codex",
                "backendSessionId": "old-pinned-codex",
                "updatedAt": old_timestamp,
            },
            {
                "backend": "grok",
                "backendSessionId": "old-active-grok",
                "updatedAt": old_timestamp,
            },
            {
                "backend": "codex",
                "backendSessionId": "recent-linked-codex",
                "updatedAt": old_timestamp,
            },
        ]

        with mock.patch.object(self.manager.catalog, "cleanup_sessions", return_value=catalog):
            preview = self.manager.preview_session_cleanup(30)

        self.assertEqual({"grok": 1, "codex": 2, "total": 3}, preview["eligible"])
        self.assertEqual(1, preview["skippedPinned"])
        self.assertEqual(1, preview["skippedActive"])

        with (
            mock.patch.object(self.manager.catalog, "cleanup_sessions", return_value=catalog),
            mock.patch("host.durable_jobs.manager.subprocess.run") as delete,
        ):
            delete.return_value.returncode = 0
            result = self.manager.delete_old_sessions(30)

        self.assertEqual({"grok": 1, "codex": 2, "total": 3}, result["deleted"])
        self.assertEqual({"grok": 0, "codex": 0, "total": 0}, result["failed"])
        self.assertEqual(1, result["skippedPinned"])
        self.assertEqual(1, result["skippedActive"])
        commands = [call.args[0] for call in delete.call_args_list]
        self.assertIn([str(self.grok), "sessions", "delete", "old-linked-grok"], commands)
        self.assertIn([str(self.codex), "delete", "--force", "old-external-codex"], commands)
        with self.assertRaises(NotFoundError):
            self.store.get_session(linked_grok["id"])
        with self.assertRaises(NotFoundError):
            self.store.get_session(local_placeholder["id"])
        self.assertTrue(self.store.get_session(pinned["id"])["pinned"])
        self.assertEqual("queued", self.store.get_session(active["id"])["status"])
        self.assertEqual(recent["id"], self.store.get_session(recent["id"])["id"])
        self.store.finish_turn(active_turn["id"], "cancelled")

        with self.assertRaises(StoreError):
            self.manager.preview_session_cleanup(0)

    def test_cleanup_catalog_includes_grok_and_paginated_codex_archives(self):
        catalog = LegacyCatalog(self.store, str(self.codex))
        old_timestamp = "2020-01-01T00:00:00Z"

        def codex_page(_method, params):
            if params["archived"]:
                return {
                    "data": [{"id": "archived-codex", "updatedAt": 2}],
                    "nextCursor": None,
                }
            if params.get("cursor") == "page-2":
                return {
                    "data": [{"id": "second-codex", "updatedAt": 3}],
                    "nextCursor": None,
                }
            return {
                "data": [{"id": "first-codex", "updatedAt": 1}],
                "nextCursor": "page-2",
            }

        with (
            mock.patch.object(
                catalog,
                "_all_grok_summaries",
                return_value=[{"sessionId": "old-grok", "updatedAt": old_timestamp}],
            ),
            mock.patch.object(catalog, "_codex_rpc", side_effect=codex_page),
        ):
            sessions = catalog.cleanup_sessions()

        self.assertEqual(
            {
                ("grok", "old-grok"),
                ("codex", "first-codex"),
                ("codex", "second-codex"),
                ("codex", "archived-codex"),
            },
            {(session["backend"], session["backendSessionId"]) for session in sessions},
        )

    def test_session_metadata_pin_order_and_unread_lifecycle(self):
        older = self.store.create_session("codex", str(self.workspace))
        newer = self.store.create_session("codex", str(self.workspace))

        updated = self.store.update_session_metadata(
            older["id"],
            title="Pinned work",
            pinned=True,
        )
        self.assertEqual("Pinned work", updated["title"])
        self.assertTrue(updated["pinned"])
        self.assertFalse(updated["unread"])
        self.assertEqual(
            older["id"],
            self.store.list_sessions("codex", str(self.workspace))[0]["id"],
        )

        turn = self.store.create_turn(older["id"], "a later prompt")
        self.assertEqual("Pinned work", self.store.get_session(older["id"])["title"])
        self.assertFalse(self.store.get_session(older["id"])["unread"])
        self.store.finish_turn(turn["id"], "completed", stop_reason="completed")
        self.assertTrue(self.store.get_session(older["id"])["unread"])

        read = self.store.update_session_metadata(older["id"], unread=False)
        self.assertFalse(read["unread"])
        self.assertFalse(newer["pinned"])

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
            self.assertTrue(created["session"]["codexFullAccess"])

            metadata_request = urllib.request.Request(
                base + f"/api/v1/sessions/{created['session']['id']}",
                data=json.dumps({"title": "Phone task", "pinned": True}).encode(),
                headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
                method="PATCH",
            )
            with urllib.request.urlopen(metadata_request, timeout=2) as response:
                metadata = json.load(response)
            self.assertEqual("Phone task", metadata["session"]["title"])
            self.assertTrue(metadata["session"]["pinned"])
            self.assertFalse(metadata["session"]["unread"])

            delete_target = self.manager.create_session("grok", str(self.workspace))
            delete_request = urllib.request.Request(
                base + f"/api/v1/sessions/{delete_target['id']}",
                headers={"Authorization": f"Bearer {token}"},
                method="DELETE",
            )
            with urllib.request.urlopen(delete_request, timeout=2) as response:
                deleted = json.load(response)
            self.assertTrue(deleted["deleted"])
            with self.assertRaises(NotFoundError):
                self.store.get_session(delete_target["id"])

            preview_payload = {
                "olderThanDays": 30,
                "cutoff": "2020-01-01T00:00:00.000Z",
                "eligible": {"grok": 1, "codex": 2, "total": 3},
                "skippedPinned": 1,
                "skippedActive": 0,
            }
            with mock.patch.object(
                self.manager,
                "preview_session_cleanup",
                return_value=preview_payload,
            ) as preview_cleanup:
                cleanup_preview_request = urllib.request.Request(
                    base + "/api/v1/session-cleanup?olderThanDays=30",
                    headers={"Authorization": f"Bearer {token}"},
                )
                with urllib.request.urlopen(cleanup_preview_request, timeout=2) as response:
                    cleanup_preview = json.load(response)
            self.assertEqual(3, cleanup_preview["cleanup"]["eligible"]["total"])
            preview_cleanup.assert_called_once_with(30)

            cleanup_result_payload = {
                **preview_payload,
                "deleted": {"grok": 1, "codex": 2, "total": 3},
                "failed": {"grok": 0, "codex": 0, "total": 0},
            }
            with mock.patch.object(
                self.manager,
                "delete_old_sessions",
                return_value=cleanup_result_payload,
            ) as delete_cleanup:
                cleanup_delete_request = urllib.request.Request(
                    base + "/api/v1/session-cleanup?olderThanDays=30",
                    headers={"Authorization": f"Bearer {token}"},
                    method="DELETE",
                )
                with urllib.request.urlopen(cleanup_delete_request, timeout=2) as response:
                    cleanup_result = json.load(response)
            self.assertEqual(3, cleanup_result["cleanup"]["deleted"]["total"])
            delete_cleanup.assert_called_once_with(30)

            sandboxed_request = urllib.request.Request(
                base + "/api/v1/sessions",
                data=json.dumps({
                    "backend": "codex",
                    "cwd": str(self.workspace),
                    "codexFullAccess": False,
                }).encode(),
                headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(sandboxed_request, timeout=2) as response:
                sandboxed = json.load(response)
            self.assertFalse(sandboxed["session"]["codexFullAccess"])

            commands_request = urllib.request.Request(
                base + f"/api/v1/sessions/{created['session']['id']}/commands",
                headers={"Authorization": f"Bearer {token}"},
            )
            with urllib.request.urlopen(commands_request, timeout=2) as response:
                commands = json.load(response)
            self.assertEqual([], commands["commands"])

            models_request = urllib.request.Request(
                base + "/api/v1/models?backend=codex",
                headers={"Authorization": f"Bearer {token}"},
            )
            with urllib.request.urlopen(models_request, timeout=2) as response:
                models = json.load(response)
            self.assertEqual(["gpt-test", "gpt-fast"], [item["id"] for item in models["models"]])

            select_request = urllib.request.Request(
                base + f"/api/v1/sessions/{created['session']['id']}/model",
                data=json.dumps({"modelId": "gpt-fast"}).encode(),
                headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(select_request, timeout=2) as response:
                selected = json.load(response)
            self.assertEqual("gpt-fast", selected["session"]["modelOverride"])

            image_bytes = b"\x89PNG\r\n\x1a\n" + b"api-image"
            upload_request = urllib.request.Request(
                base + f"/api/v1/sessions/{created['session']['id']}/attachments",
                data=image_bytes,
                headers={
                    "Authorization": f"Bearer {token}",
                    "Content-Type": "image/png",
                    "X-File-Name": "phone%20screen.png",
                },
                method="POST",
            )
            with urllib.request.urlopen(upload_request, timeout=2) as response:
                uploaded = json.load(response)
            self.assertEqual("phone screen.png", uploaded["attachment"]["fileName"])

            turn_request = urllib.request.Request(
                base + f"/api/v1/sessions/{created['session']['id']}/turns",
                data=json.dumps({
                    "prompt": "inspect",
                    "attachmentIds": [uploaded["attachment"]["id"]],
                }).encode(),
                headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(turn_request, timeout=2) as response:
                queued = json.load(response)
            self.assertEqual("phone screen.png", queued["turn"]["attachments"][0]["fileName"])
            self.assertNotIn("path", queued["turn"]["attachments"][0])

            status_request = urllib.request.Request(
                base + f"/api/v1/sessions/{created['session']['id']}/status",
                headers={"Authorization": f"Bearer {token}"},
            )
            with urllib.request.urlopen(status_request, timeout=2) as response:
                status = json.load(response)
            self.assertEqual(40, status["status"]["rateLimits"]["primary"]["usedPercent"])

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

    def test_database_uses_one_serialized_full_sync_wal_connection(self):
        with self.store._connect() as first:
            first_identity = id(first)
            self.assertEqual("wal", first.execute("PRAGMA journal_mode").fetchone()[0])
            self.assertEqual(2, first.execute("PRAGMA synchronous").fetchone()[0])
            self.assertEqual(1000, first.execute("PRAGMA wal_autocheckpoint").fetchone()[0])
            self.assertEqual(8 * 1024 * 1024, first.execute("PRAGMA journal_size_limit").fetchone()[0])
        with self.store._connect() as second:
            self.assertEqual(first_identity, id(second))

    def test_existing_v1_database_adds_current_session_columns(self):
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
            message_columns = {
                row["name"] for row in connection.execute("PRAGMA table_info(messages)")
            }
            attachment_columns = {
                row["name"] for row in connection.execute("PRAGMA table_info(attachments)")
            }
            version = connection.execute("PRAGMA user_version").fetchone()[0]

        self.assertIn("model_name", columns)
        self.assertIn("model_override", columns)
        self.assertIn("codex_full_access", columns)
        self.assertIn("title_is_manual", columns)
        self.assertIn("pinned", columns)
        self.assertIn("unread", columns)
        self.assertIn("ordinal", message_columns)
        self.assertIn("stored_name", attachment_columns)
        self.assertEqual(7, version)

    def test_v4_migration_repairs_coalesced_codex_timeline(self):
        path = Path(self.temporary.name, "v4-timeline.sqlite3")
        with sqlite3.connect(path) as connection:
            connection.executescript(
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
                    model_name TEXT,
                    model_override TEXT,
                    reasoning_effort TEXT,
                    used_tokens INTEGER,
                    context_window_tokens INTEGER,
                    codex_full_access INTEGER NOT NULL DEFAULT 1,
                    last_error TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
                CREATE TABLE turns (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    prompt TEXT NOT NULL,
                    status TEXT NOT NULL,
                    stop_reason TEXT,
                    error TEXT,
                    cancellation_requested INTEGER NOT NULL DEFAULT 0,
                    worker_pid INTEGER,
                    created_at TEXT NOT NULL,
                    started_at TEXT,
                    completed_at TEXT
                );
                CREATE TABLE messages (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    turn_id TEXT,
                    role TEXT NOT NULL,
                    text TEXT NOT NULL DEFAULT '',
                    status TEXT,
                    kind TEXT,
                    detail TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
                INSERT INTO sessions(
                    id, backend, cwd, title, status, created_at, updated_at
                ) VALUES ('session', 'codex', '/tmp', 'Task', 'idle', '2026-01-01', '2026-01-01');
                INSERT INTO turns(
                    id, session_id, prompt, status, stop_reason, created_at
                ) VALUES
                    ('turn-1', 'session', 'first', 'completed', 'completed', '2026-01-01T00:00:00Z'),
                    ('turn-2', 'session', 'second', 'completed', 'completed', '2026-01-01T00:01:00Z');
                INSERT INTO messages(
                    id, session_id, turn_id, role, text, status, kind, created_at, updated_at
                ) VALUES
                    ('user-1', 'session', 'turn-1', 'user', 'first', NULL, NULL, '1', '1'),
                    ('turn-1:assistant', 'session', 'turn-1', 'assistant', 'commentary + final', 'completed', NULL, '2', '5'),
                    ('turn-1:tool', 'session', 'turn-1', 'tool', 'tool', 'completed', 'command_execution', '3', '4'),
                    ('user-2', 'session', 'turn-2', 'user', 'second', NULL, NULL, '6', '6');
                PRAGMA user_version = 4;
                """
            )

        migrated = JobStore(path)
        bundle = migrated.session_bundle("session")

        self.assertEqual(
            ["user", "tool", "assistant", "user"],
            [message["role"] for message in bundle["messages"]],
        )
        self.assertEqual(
            list(range(4)),
            [message["ordinal"] for message in bundle["messages"]],
        )
        with migrated._connect() as connection:
            self.assertEqual(7, connection.execute("PRAGMA user_version").fetchone()[0])
            self.assertEqual("ok", connection.execute("PRAGMA integrity_check").fetchone()[0])

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

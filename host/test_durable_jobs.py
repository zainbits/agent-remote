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
    active_turn = None
    pending_approval = None

    def emit(value):
        print(json.dumps(value), flush=True)

    def completed_turn(thread_id, turn_id, status="completed", error=None):
        turn = {"id": turn_id, "items": [], "status": status}
        if error is not None:
            turn["error"] = {"message": error}
        emit({"method": "turn/completed", "params": {"threadId": thread_id, "turn": turn}})

    def finish_prompt(thread_id, turn_id, prompt):
        if "INTERLEAVED" in prompt:
            emit({"method": "item/completed", "params": {
                "threadId": thread_id, "turnId": turn_id, "item": {
                    "id": "commentary", "type": "agentMessage", "text": "checking first"
                }
            }})
            emit({"method": "item/started", "params": {
                "threadId": thread_id, "turnId": turn_id, "item": {
                    "id": "tool", "type": "commandExecution", "command": "test command",
                    "aggregatedOutput": "", "status": "inProgress"
                }
            }})
            emit({"method": "item/commandExecution/outputDelta", "params": {
                "threadId": thread_id, "turnId": turn_id, "itemId": "tool", "delta": "done"
            }})
            emit({"method": "item/completed", "params": {
                "threadId": thread_id, "turnId": turn_id, "item": {
                    "id": "tool", "type": "commandExecution", "command": "test command",
                    "aggregatedOutput": "done", "exitCode": 0, "status": "completed"
                }
            }})
            emit({"method": "item/started", "params": {
                "threadId": thread_id, "turnId": turn_id,
                "item": {"id": "final", "type": "agentMessage", "text": ""}
            }})
            for delta in ("final ", "answer"):
                emit({"method": "item/agentMessage/delta", "params": {
                    "threadId": thread_id, "turnId": turn_id,
                    "itemId": "final", "delta": delta
                }})
            emit({"method": "item/completed", "params": {
                "threadId": thread_id, "turnId": turn_id,
                "item": {"id": "final", "type": "agentMessage", "text": "final answer"}
            }})
        else:
            text = "approval-declined" if "REQUEST_APPROVAL" in prompt else "codex-result"
            emit({"method": "item/started", "params": {
                "threadId": thread_id, "turnId": turn_id,
                "item": {"id": "agent", "type": "agentMessage", "text": ""}
            }})
            midpoint = max(1, len(text) // 2)
            for delta in (text[:midpoint], text[midpoint:]):
                if delta:
                    emit({"method": "item/agentMessage/delta", "params": {
                        "threadId": thread_id, "turnId": turn_id,
                        "itemId": "agent", "delta": delta
                    }})
            emit({"method": "item/completed", "params": {
                "threadId": thread_id, "turnId": turn_id,
                "item": {"id": "agent", "type": "agentMessage", "text": text}
            }})
        emit({"method": "thread/tokenUsage/updated", "params": {
            "threadId": thread_id, "turnId": turn_id,
            "tokenUsage": {
                "last": {"totalTokens": 14},
                "total": {"totalTokens": 14},
                "modelContextWindow": 4096
            }
        }})
        completed_turn(thread_id, turn_id)

    for line in sys.stdin:
        request = json.loads(line)
        request_id = request.get("id")
        method = request.get("method")
        if method is None:
            if pending_approval is not None and request_id == pending_approval[0]:
                _, thread_id, turn_id, prompt = pending_approval
                finish_prompt(thread_id, turn_id, prompt)
                pending_approval = None
            continue
        if request_id is None:
            continue
        if method == "initialize":
            result = {"userAgent": "fake-codex"}
        elif method == "thread/start":
            result = {
                "thread": {"id": "codex-thread-test", "cwd": os.getcwd(), "turns": []},
                "model": request.get("params", {}).get("model") or "gpt-test",
                "reasoningEffort": "high",
            }
        elif method == "thread/resume":
            thread_id = request.get("params", {}).get("threadId")
            result = {
                "thread": {"id": thread_id, "cwd": os.getcwd(), "turns": []},
                "model": request.get("params", {}).get("model") or "gpt-test",
                "reasoningEffort": "high",
            }
        elif method == "turn/start":
            params = request.get("params", {})
            thread_id = params.get("threadId")
            turn_id = "codex-turn-test"
            prompt = "\n".join(
                item.get("text", "") for item in params.get("input", [])
                if item.get("type") == "text"
            )
            emit({"id": request_id, "result": {
                "turn": {"id": turn_id, "items": [], "status": "inProgress"}
            }})
            emit({"method": "turn/started", "params": {
                "threadId": thread_id,
                "turn": {"id": turn_id, "items": [], "status": "inProgress"}
            }})
            if "WAIT_FOR_CANCEL" in prompt:
                active_turn = (thread_id, turn_id)
            elif "REQUEST_APPROVAL" in prompt:
                pending_approval = (700, thread_id, turn_id, prompt)
                emit({"id": 700, "method": "item/commandExecution/requestApproval", "params": {
                    "threadId": thread_id, "turnId": turn_id, "itemId": "approval"
                }})
            else:
                if "SLOW" in prompt:
                    time.sleep(0.4)
                finish_prompt(thread_id, turn_id, prompt)
            continue
        elif method == "turn/interrupt":
            emit({"id": request_id, "result": {}})
            if active_turn is not None:
                completed_turn(active_turn[0], active_turn[1], status="interrupted")
                active_turn = None
            continue
        elif method == "thread/read":
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
        emit({"id": request_id, "result": result})
    raise SystemExit(0)
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
if prompt == "INTERLEAVED_GROK":
    print(json.dumps({"type": "text", "data": "progress update"}), flush=True)
    print(json.dumps({"type": "thought", "data": "checking again"}), flush=True)
    print(json.dumps({"type": "text", "data": "final answer"}), flush=True)
elif prompt == "/session-info":
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


class MetadataProviderTest(unittest.TestCase):
    @staticmethod
    def _grok_initialize(model_id: str) -> dict:
        return {
            "_meta": {
                "modelState": {
                    "currentModelId": model_id,
                    "availableModels": [
                        {
                            "modelId": model_id,
                            "name": model_id,
                            "_meta": {},
                        }
                    ],
                },
                "availableCommands": [],
            }
        }

    def test_grok_catalog_refreshes_after_cache_expiry(self):
        provider = MetadataProvider(home=tempfile.gettempdir())
        responses = [
            self._grok_initialize("grok-build"),
            self._grok_initialize("grok-4.5"),
        ]

        with mock.patch.object(
            provider,
            "_probe_grok_initialize",
            side_effect=responses,
        ) as probe, mock.patch(
            "host.durable_jobs.metadata.time.monotonic",
            side_effect=[100.0, 200.0, 401.0],
        ):
            first = provider.models_for("grok")
            cached = provider.models_for("grok")
            refreshed = provider.models_for("grok")

        self.assertEqual(["grok-build"], [model["id"] for model in first])
        self.assertEqual(["grok-build"], [model["id"] for model in cached])
        self.assertEqual(["grok-4.5"], [model["id"] for model in refreshed])
        self.assertEqual(2, probe.call_count)

    def test_failed_grok_catalog_probe_is_retried_immediately(self):
        provider = MetadataProvider(home=tempfile.gettempdir())

        with mock.patch.object(
            provider,
            "_probe_grok_initialize",
            side_effect=[
                RuntimeError("temporarily unavailable"),
                self._grok_initialize("grok-4.5"),
            ],
        ) as probe, mock.patch(
            "host.durable_jobs.metadata.time.monotonic",
            side_effect=[100.0, 101.0],
        ):
            failed = provider.models_for("grok")
            recovered = provider.models_for("grok")

        self.assertEqual([], failed)
        self.assertEqual(["grok-4.5"], [model["id"] for model in recovered])
        self.assertEqual(2, probe.call_count)

    def test_failed_refresh_keeps_last_good_grok_catalog(self):
        provider = MetadataProvider(home=tempfile.gettempdir())

        with mock.patch.object(
            provider,
            "_probe_grok_initialize",
            side_effect=[
                self._grok_initialize("grok-build"),
                RuntimeError("temporarily unavailable"),
            ],
        ), mock.patch(
            "host.durable_jobs.metadata.time.monotonic",
            side_effect=[100.0, 401.0],
        ):
            initial = provider.models_for("grok")
            stale_fallback = provider.models_for("grok")

        self.assertEqual(["grok-build"], [model["id"] for model in initial])
        self.assertEqual(["grok-build"], [model["id"] for model in stale_fallback])


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

    def _wait_cleanup_terminal(self, operation_id: str, timeout: float = 5.0):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            operation = self.manager.get_session_cleanup(operation_id)
            if operation["status"] in {"completed", "failed"}:
                return operation
            time.sleep(0.01)
        self.fail("session cleanup did not reach a terminal state")

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

    def test_grok_interleaved_stages_keep_live_and_replay_order(self):
        session = self.manager.create_session("grok", str(self.workspace))
        self.manager.start_turn(session["id"], "INTERLEAVED_GROK")

        _, events = self._wait_terminal(session["id"])
        messages = self.store.session_bundle(session["id"])["messages"]

        self.assertEqual(
            ["user", "thought", "assistant", "thought", "assistant"],
            [message["role"] for message in messages],
        )
        self.assertEqual(
            [
                "INTERLEAVED_GROK",
                "checking",
                "progress update",
                "checking again",
                "final answer",
            ],
            [message["text"] for message in messages],
        )
        self.assertEqual(5, len({message["id"] for message in messages}))
        self.assertEqual(list(range(5)), [message["ordinal"] for message in messages])
        self.assertTrue(all(message["status"] == "completed" for message in messages[1:]))

        streamed_roles = [
            event["data"]["role"]
            for event in events
            if event["type"] == "message.delta"
        ]
        self.assertEqual(
            ["thought", "assistant", "thought", "assistant"],
            streamed_roles,
        )

    def test_images_are_durable_and_mapped_to_app_server_turn_input(self):
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
        turn_params = self.manager._codex_turn_params(
            session, turn, "codex-thread-test"
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
            next(item["path"] for item in turn_params["input"] if item["type"] == "localImage"),
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
        thread_params = self.manager._codex_thread_params(session)
        turn = {"prompt": "first", "attachments": []}
        turn_params = self.manager._codex_turn_params(session, turn, "new-thread")
        self.assertTrue(session["codexFullAccess"])
        self.assertEqual([str(self.codex), "app-server"], new_command)
        self.assertEqual("danger-full-access", thread_params["sandbox"])
        self.assertEqual("never", thread_params["approvalPolicy"])
        self.assertEqual({"type": "dangerFullAccess"}, turn_params["sandboxPolicy"])

        self.store.set_backend_session_id(session["id"], "existing-thread")
        resumed_session = self.store.get_session(session["id"])
        resumed = self.manager._codex_thread_params(resumed_session)
        self.assertEqual("danger-full-access", resumed["sandbox"])

    def test_codex_workers_can_opt_out_to_networked_workspace_sandbox(self):
        session = self.manager.create_session(
            "codex",
            str(self.workspace),
            codex_full_access=False,
        )
        thread_params = self.manager._codex_thread_params(session)
        turn_params = self.manager._codex_turn_params(
            session, {"prompt": "first", "attachments": []}, "new-thread"
        )
        self.assertFalse(session["codexFullAccess"])
        self.assertEqual("workspace-write", thread_params["sandbox"])
        self.assertEqual("never", thread_params["approvalPolicy"])
        self.assertEqual("workspaceWrite", turn_params["sandboxPolicy"]["type"])
        self.assertTrue(turn_params["sandboxPolicy"]["networkAccess"])
        self.assertEqual([str(self.workspace)], turn_params["sandboxPolicy"]["writableRoots"])

        self.store.set_backend_session_id(session["id"], "existing-thread")
        resumed_session = self.store.get_session(session["id"])
        resumed = self.manager._codex_turn_params(
            resumed_session, {"prompt": "next", "attachments": []}, "existing-thread"
        )
        self.assertEqual("workspaceWrite", resumed["sandboxPolicy"]["type"])
        self.assertTrue(resumed["sandboxPolicy"]["networkAccess"])

    def test_selected_model_is_persisted_and_applied_to_new_and_resumed_turns(self):
        session = self.manager.create_session("codex", str(self.workspace))

        selected = self.manager.select_model(session["id"], "gpt-fast")

        self.assertEqual("gpt-fast", selected["modelOverride"])
        self.assertEqual("GPT Fast", selected["modelName"])
        self.assertEqual("low", selected["reasoningEffort"])
        self.assertEqual(2048, selected["contextWindowTokens"])
        new_thread = self.manager._codex_thread_params(selected)
        new_turn = self.manager._codex_turn_params(
            selected, {"prompt": "first", "attachments": []}, "new-thread"
        )
        self.assertEqual("gpt-fast", new_thread["model"])
        self.assertEqual("gpt-fast", new_turn["model"])
        self.assertEqual("low", new_turn["effort"])

        self.store.set_backend_session_id(session["id"], "existing-thread")
        resumed_session = self.store.get_session(session["id"])
        resumed = self.manager._codex_thread_params(resumed_session)
        self.assertEqual("gpt-fast", resumed["model"])

        bundle = self.manager.session_bundle(session["id"])
        self.assertEqual("gpt-fast", bundle["session"]["modelId"])
        self.assertEqual("GPT Fast", bundle["session"]["modelName"])

        self.manager.start_turn(session["id"], "use the selected configuration")
        terminal, _ = self._wait_terminal(session["id"])
        self.assertEqual("low", terminal["reasoningEffort"])

    def test_reasoning_effort_is_validated_and_applied_for_codex(self):
        session = self.manager.create_session("codex", str(self.workspace))
        self.manager.select_model(session["id"], "gpt-test")

        selected = self.manager.select_reasoning_effort(session["id"], "low")
        turn_params = self.manager._codex_turn_params(
            selected, {"prompt": "first", "attachments": []}, "new-thread"
        )

        self.assertEqual("gpt-test", selected["modelOverride"])
        self.assertEqual("low", selected["reasoningEffort"])
        self.assertEqual("low", turn_params["effort"])
        with self.assertRaises(StoreError):
            self.manager.select_reasoning_effort(session["id"], "ultra")

    def test_codex_app_server_resumes_the_durable_thread(self):
        session = self.manager.create_session("codex", str(self.workspace))
        self.manager.start_turn(session["id"], "first turn")
        first, _ = self._wait_terminal(session["id"])

        self.manager.start_turn(session["id"], "second turn")
        second, _ = self._wait_terminal(session["id"])
        bundle = self.store.session_bundle(session["id"])

        self.assertEqual("codex-thread-test", first["backendSessionId"])
        self.assertEqual(first["backendSessionId"], second["backendSessionId"])
        self.assertEqual(
            ["codex-result", "codex-result"],
            [message["text"] for message in bundle["messages"] if message["role"] == "assistant"],
        )

    def test_codex_app_server_declines_unhandled_approval_requests(self):
        session = self.manager.create_session("codex", str(self.workspace))
        self.manager.start_turn(session["id"], "REQUEST_APPROVAL")

        terminal, _ = self._wait_terminal(session["id"])
        bundle = self.store.session_bundle(session["id"])

        self.assertEqual("idle", terminal["status"])
        self.assertEqual("approval-declined", bundle["messages"][-1]["text"])

    def test_grok_selected_model_is_applied_without_unsupported_effort(self):
        session = self.manager.create_session("grok", str(self.workspace))

        selected = self.manager.select_model(session["id"], "grok-fast")
        command = self.manager._command(selected, "first", "/tmp/prompt")

        self.assertEqual("grok-fast", selected["modelOverride"])
        self.assertIsNone(selected["reasoningEffort"])
        self.assertEqual("grok-fast", command[command.index("--model") + 1])
        self.assertNotIn("--reasoning-effort", command)

    def test_reasoning_effort_is_validated_and_applied_for_grok(self):
        session = self.manager.create_session("grok", str(self.workspace))

        selected = self.manager.select_reasoning_effort(session["id"], "low")
        command = self.manager._command(selected, "first", "/tmp/prompt")

        self.assertEqual("grok-test", selected["modelOverride"])
        self.assertEqual("low", selected["reasoningEffort"])
        self.assertEqual("grok-test", command[command.index("--model") + 1])
        self.assertEqual("low", command[command.index("--reasoning-effort") + 1])
        with self.assertRaises(StoreError):
            self.manager.select_reasoning_effort(session["id"], "medium")

    def test_model_change_is_rejected_while_turn_is_active(self):
        session = self.manager.create_session("codex", str(self.workspace))
        self.manager.select_model(session["id"], "gpt-test")
        self.manager.start_turn(session["id"], "WAIT_FOR_CANCEL")

        with self.assertRaises(ConflictError):
            self.manager.select_model(session["id"], "gpt-fast")
        with self.assertRaises(ConflictError):
            self.manager.select_reasoning_effort(session["id"], "low")

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

    def test_async_session_cleanup_reports_aggregate_progress_and_partial_failures(self):
        old_timestamp = "2020-01-01T00:00:00.000Z"
        catalog = [
            {
                "backend": "grok",
                "backendSessionId": "first-old-session",
                "updatedAt": old_timestamp,
            },
            {
                "backend": "codex",
                "backendSessionId": "second-old-session",
                "updatedAt": old_timestamp,
            },
        ]
        second_started = threading.Event()
        release_second = threading.Event()

        def delete_backend(_backend, backend_id):
            if backend_id == "first-old-session":
                raise StoreError("simulated backend failure")
            second_started.set()
            if not release_second.wait(2):
                raise StoreError("test did not release the second deletion")

        with (
            mock.patch.object(self.manager.catalog, "cleanup_sessions", return_value=catalog),
            mock.patch.object(self.manager, "_delete_backend_session", side_effect=delete_backend),
        ):
            operation = self.manager.start_session_cleanup(30)
            try:
                self.assertTrue(second_started.wait(2))
                progress = self.manager.get_session_cleanup(operation["id"])
                self.assertEqual("running", progress["status"])
                self.assertEqual(1, progress["processed"])
                self.assertEqual({"grok": 1, "codex": 1, "total": 2}, progress["eligible"])
                self.assertEqual({"grok": 1, "codex": 0, "total": 1}, progress["failed"])
                self.assertNotIn("first-old-session", json.dumps(progress))
                self.assertNotIn("second-old-session", json.dumps(progress))
                with self.assertRaises(ConflictError):
                    self.manager.start_session_cleanup(3)
                with self.assertRaises(ConflictError):
                    self.manager.delete_old_sessions(3)
            finally:
                release_second.set()
            terminal = self._wait_cleanup_terminal(operation["id"])

        self.assertEqual("completed", terminal["status"])
        self.assertEqual(2, terminal["processed"])
        self.assertEqual({"grok": 0, "codex": 1, "total": 1}, terminal["deleted"])
        self.assertEqual({"grok": 1, "codex": 0, "total": 1}, terminal["failed"])

    def test_async_session_cleanup_reports_terminal_scan_failure_without_details(self):
        with mock.patch.object(
            self.manager.catalog,
            "cleanup_sessions",
            side_effect=StoreError("private catalog detail"),
        ):
            operation = self.manager.start_session_cleanup(30)
            terminal = self._wait_cleanup_terminal(operation["id"])

        self.assertEqual("failed", terminal["status"])
        self.assertEqual("Couldn't complete session cleanup.", terminal["error"])
        self.assertNotIn("private catalog detail", terminal["error"])

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

    def test_session_list_hides_untouched_drafts(self):
        draft = self.store.create_session("codex", str(self.workspace))
        started = self.store.create_session("codex", str(self.workspace))
        linked = self.store.create_session("codex", str(self.workspace))
        renamed = self.store.create_session("codex", str(self.workspace))

        turn = self.store.create_turn(started["id"], "started conversation")
        self.store.finish_turn(turn["id"], "completed")
        self.store.set_backend_session_id(linked["id"], "existing-codex-thread")
        self.store.update_session_metadata(renamed["id"], title="Saved draft")

        listed_ids = {
            session["id"]
            for session in self.store.list_sessions("codex", str(self.workspace))
        }
        self.assertNotIn(draft["id"], listed_ids)
        self.assertEqual({started["id"], linked["id"], renamed["id"]}, listed_ids)

    def test_discard_draft_deletes_only_untouched_placeholder(self):
        draft = self.manager.create_session("grok", str(self.workspace))
        image_bytes = b"\x89PNG\r\n\x1a\n" + b"draft-image"
        attachment = self.manager.upload_attachment(
            draft["id"],
            "draft.png",
            "image/png",
            len(image_bytes),
            io.BytesIO(image_bytes),
        )
        attachment_path = Path(self.store.attachment_directory, f"{attachment['id']}.png")

        self.assertEqual(
            {"discarded": True},
            self.manager.discard_draft_session(draft["id"]),
        )
        with self.assertRaises(NotFoundError):
            self.store.get_session(draft["id"])
        self.assertFalse(attachment_path.exists())

        started = self.store.create_session("grok", str(self.workspace))
        turn = self.store.create_turn(started["id"], "keep this conversation")
        self.store.finish_turn(turn["id"], "completed")
        self.assertEqual(
            {"discarded": False},
            self.manager.discard_draft_session(started["id"]),
        )
        self.assertEqual(started["id"], self.store.get_session(started["id"])["id"])

        linked = self.store.create_session("grok", str(self.workspace))
        self.store.set_backend_session_id(linked["id"], "existing-grok-session")
        renamed = self.store.create_session("grok", str(self.workspace))
        self.store.update_session_metadata(renamed["id"], title="Keep this draft")
        for protected in (linked, renamed):
            self.assertEqual(
                {"discarded": False},
                self.manager.discard_draft_session(protected["id"]),
            )
            self.assertEqual(
                protected["id"],
                self.store.get_session(protected["id"])["id"],
            )

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

            operation_payload = {
                "id": "cleanup-operation-test",
                "status": "running",
                "olderThanDays": 30,
                "cutoff": "2020-01-01T00:00:00.000Z",
                "eligible": {"grok": 1, "codex": 2, "total": 3},
                "processed": 1,
                "skippedPinned": 1,
                "skippedActive": 0,
                "deleted": {"grok": 1, "codex": 0, "total": 1},
                "failed": {"grok": 0, "codex": 0, "total": 0},
                "error": None,
            }
            with mock.patch.object(
                self.manager,
                "start_session_cleanup",
                return_value=operation_payload,
            ) as start_cleanup:
                cleanup_start_request = urllib.request.Request(
                    base + "/api/v1/session-cleanups",
                    data=json.dumps({"olderThanDays": 30}).encode(),
                    headers={
                        "Authorization": f"Bearer {token}",
                        "Content-Type": "application/json",
                    },
                    method="POST",
                )
                with urllib.request.urlopen(cleanup_start_request, timeout=2) as response:
                    self.assertEqual(202, response.status)
                    cleanup_started = json.load(response)
            self.assertEqual(
                "cleanup-operation-test",
                cleanup_started["cleanupOperation"]["id"],
            )
            start_cleanup.assert_called_once_with(30)

            with mock.patch.object(
                self.manager,
                "get_session_cleanup",
                return_value=operation_payload,
            ) as get_cleanup:
                cleanup_status_request = urllib.request.Request(
                    base + "/api/v1/session-cleanups/cleanup-operation-test",
                    headers={"Authorization": f"Bearer {token}"},
                )
                with urllib.request.urlopen(cleanup_status_request, timeout=2) as response:
                    cleanup_status = json.load(response)
            self.assertEqual(1, cleanup_status["cleanupOperation"]["processed"])
            get_cleanup.assert_called_once_with("cleanup-operation-test")

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

            discard_draft_request = urllib.request.Request(
                base + f"/api/v1/sessions/{sandboxed['session']['id']}/draft",
                headers={"Authorization": f"Bearer {token}"},
                method="DELETE",
            )
            with urllib.request.urlopen(discard_draft_request, timeout=2) as response:
                discarded_draft = json.load(response)
            self.assertTrue(discarded_draft["discarded"])
            with self.assertRaises(NotFoundError):
                self.store.get_session(sandboxed["session"]["id"])

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

            effort_request = urllib.request.Request(
                base + f"/api/v1/sessions/{created['session']['id']}/reasoning-effort",
                data=json.dumps({"reasoningEffort": "low"}).encode(),
                headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(effort_request, timeout=2) as response:
                selected_effort = json.load(response)
            self.assertEqual("low", selected_effort["session"]["reasoningEffort"])

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

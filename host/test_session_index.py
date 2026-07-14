import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from host.session_index import load_cleanup_summaries, visible_message_count


def write_jsonl(path: Path, values: list[dict]) -> None:
    path.write_text(
        "".join(json.dumps(value) + "\n" for value in values),
        encoding="utf-8",
    )


class VisibleMessageCountTest(unittest.TestCase):
    def session(self, history: list[dict], events: list[dict], **summary_values):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        session_dir = Path(temporary.name)
        summary_path = session_dir / "summary.json"
        summary = {"num_chat_messages": len(history), **summary_values}
        summary_path.write_text(json.dumps(summary), encoding="utf-8")
        write_jsonl(session_dir / "chat_history.jsonl", history)
        write_jsonl(session_dir / "events.jsonl", events)
        return summary_path, summary

    def test_excludes_bootstrap_synthetic_and_reasoning_records(self):
        history = [
            {"type": "system", "content": "system"},
            {"type": "user", "content": [{"type": "text", "text": "bootstrap"}]},
            {"type": "user", "content": [], "synthetic_reason": "context"},
            {"type": "user", "content": [], "synthetic_reason": "context"},
            {"type": "user", "content": [], "synthetic_reason": "context"},
            {"type": "user", "content": [{"type": "text", "text": "hello"}]},
            {"type": "reasoning", "content": "hidden"},
            {"type": "assistant", "content": "hi"},
        ]
        summary_path, summary = self.session(
            history,
            [{"type": "turn_started", "conversation_message_count": 4}],
        )

        self.assertEqual(visible_message_count(summary_path, summary), 2)

    def test_collapses_tool_loop_assistant_records_and_hides_slash_turns(self):
        history = [
            {"type": "system", "content": "system"},
            {"type": "user", "content": [{"type": "text", "text": "bootstrap"}]},
            {"type": "user", "content": [{"type": "text", "text": "build it"}]},
            {"type": "assistant", "content": "starting"},
            {"type": "tool_result", "content": "done"},
            {"type": "assistant", "content": "finished"},
            {"type": "user", "content": [{"type": "text", "text": "/context"}]},
            {"type": "assistant", "content": "command output"},
        ]
        summary_path, summary = self.session(
            history,
            [{"type": "turn_started", "conversation_message_count": 1}],
        )

        self.assertEqual(visible_message_count(summary_path, summary), 2)

    def test_empty_history_falls_back_to_completed_turn_metadata(self):
        summary_path, summary = self.session([], [], next_trace_turn=3)

        self.assertEqual(visible_message_count(summary_path, summary), 6)

    def test_cleanup_summaries_read_only_ids_and_activity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            session_dir = root / "sessions" / "encoded-cwd" / "session-id"
            session_dir.mkdir(parents=True)
            (session_dir / "summary.json").write_text(
                json.dumps({
                    "info": {"id": "session-id", "cwd": "/workspace"},
                    "created_at": "2020-01-01T00:00:00Z",
                    "last_active_at": "2020-02-01T00:00:00Z",
                }),
                encoding="utf-8",
            )
            with mock.patch.dict(os.environ, {"GROK_HOME": temporary}):
                summaries = load_cleanup_summaries()

        self.assertEqual(
            [{"sessionId": "session-id", "updatedAt": "2020-02-01T00:00:00Z"}],
            summaries,
        )


if __name__ == "__main__":
    unittest.main()

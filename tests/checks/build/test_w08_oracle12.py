"""Diagnostic negative controls for the approved W08 consumer/wire contract."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("oracle12", ROOT / "scripts/check_w08_oracle12.py")
oracle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oracle)


class Oracle12Test(unittest.TestCase):
    def setUp(self):
        call = {"name": "read_file", "args": {"path": "a.txt"}}
        self.live = [{"message_index": 1, "kind": "ai", "text": "step", "tool_calls": [copy.deepcopy(call)]}]
        self.replay = copy.deepcopy(self.live)
        self.replay[0]["truncated"] = False
        self.fixture = {"taskId": "task-1", "live": [{"task_id": "task-1", "type": "task_running",
                         "message_index": 1, "message": {"type": "ai", "content": "step", "truncated": False,
                                                        "tool_calls": [copy.deepcopy(call)]}}],
                        "replay": [{"seq": 5, "event_type": "subagent.step", "content": {
                            "task_id": "task-1", "message_index": 1, "kind": "ai", "text": "step",
                            "truncated": False, "tool_calls": [copy.deepcopy(call)]}}]}
        self.sources = {"texts": [], "calls": [], "evidence": []}

    def test_only_approved_metadata_asymmetry_is_accepted(self):
        self.replay[0]["tool_calls"][0]["args_truncated"] = True
        self.assertEqual([], oracle.content_failures(self.live, self.replay))
        self.assertNotEqual(self.live, self.replay)

    def test_content_changes_are_rejected(self):
        for path in ["text", "kind", "message_index", "new_unapproved_field"]:
            with self.subTest(path=path):
                replay = copy.deepcopy(self.replay)
                replay[0][path] = 2 if path == "message_index" else "changed"
                self.assertTrue(oracle.content_failures(self.live, replay))

    def test_tool_name_arguments_and_unknown_metadata_changes_are_rejected(self):
        for key, value in [("name", "other_tool"), ("args", {"path": "b.txt"}), ("other", True)]:
            with self.subTest(key=key):
                replay = copy.deepcopy(self.replay)
                replay[0]["tool_calls"][0][key] = value
                self.assertTrue(oracle.content_failures(self.live, replay))

    def test_json_boolean_and_integer_are_not_equal(self):
        self.live[0]["tool_calls"][0]["args"] = {"value": True}
        self.replay[0]["tool_calls"][0]["args"] = {"value": 1}
        self.assertTrue(oracle.content_failures(self.live, self.replay))

    def test_suite_requires_large_original_sources_and_four_groups(self):
        short = {"providerLargeTextSources": 0, "providerLargeArgumentSources": 0}
        large = {"providerLargeTextSources": 1, "providerLargeArgumentSources": 1}
        self.assertEqual([], oracle.coverage_failures([short, short, short, large]))
        self.assertTrue(oracle.coverage_failures([short, short, large]))
        self.assertTrue(oracle.coverage_failures([short] * 4))

    def test_empty_dropped_duplicated_or_reordered_steps_are_rejected(self):
        second = copy.deepcopy(self.live[0]); second["message_index"] = 2
        cases = [([], []), (self.live, []), (self.live * 2, self.live * 2),
                 ([second, self.live[0]], [second, self.live[0]])]
        for live, replay in cases:
            with self.subTest(live=live):
                self.assertTrue(oracle.content_failures(live, replay))

    def test_wire_positive_control(self):
        self.assertEqual([], oracle.wire_failures(self.fixture, self.sources))

    def test_missing_wrong_type_or_mismatched_text_flags_are_rejected(self):
        for value in [None, "false", 0, True]:
            with self.subTest(value=value):
                f = copy.deepcopy(self.fixture)
                if value is None:
                    del f["live"][0]["message"]["truncated"]
                else:
                    f["live"][0]["message"]["truncated"] = value
                self.assertTrue(oracle.wire_failures(f, self.sources))

    def test_wire_metadata_cannot_be_deleted_from_both_lanes(self):
        del self.fixture["live"][0]["message"]["truncated"]
        del self.fixture["replay"][0]["content"]["truncated"]
        self.assertTrue(oracle.wire_failures(self.fixture, self.sources))

    def test_wire_identity_sequence_and_index_controls(self):
        mutations = [("task_id", "wrong"), ("message_index", 2)]
        for key, value in mutations:
            with self.subTest(key=key):
                f = copy.deepcopy(self.fixture); f["replay"][0]["content"][key] = value
                self.assertTrue(oracle.wire_failures(f, self.sources))
        for sequence in [0, True, 2**53]:
            f = copy.deepcopy(self.fixture); f["replay"][0]["seq"] = sequence
            self.assertTrue(oracle.wire_failures(f, self.sources))

    def test_untruncated_arguments_remain_structured(self):
        for lane in [self.fixture["live"][0]["message"], self.fixture["replay"][0]["content"]]:
            lane["tool_calls"][0]["args"] = "already serialized"
        self.assertTrue(oracle.wire_failures(self.fixture, self.sources))

    def large_text_fixture(self, original):
        f = copy.deepcopy(self.fixture)
        for step, key in [(f["live"][0]["message"], "content"), (f["replay"][0]["content"], "text")]:
            step[key] = original[:oracle.LIMIT]; step["truncated"] = True
        return f, {"texts": [original], "calls": [], "evidence": []}

    def test_unicode_boundary_and_original_provider_prefix(self):
        original = "x" * 8191 + "😀界" + "x" * 100
        f, sources = self.large_text_fixture(original)
        self.assertEqual([], oracle.wire_failures(f, sources))
        self.assertEqual("😀", f["replay"][0]["content"]["text"][-1])
        for lane, key in [(f["live"][0]["message"], "content"), (f["replay"][0]["content"], "text")]:
            lane[key] = "y" + lane[key][1:]
        self.assertTrue(oracle.wire_failures(f, sources))

    def test_large_text_cannot_be_shortened_or_overflowed_or_false_flagged(self):
        original = "x" * 9000
        for text, flag in [("x" * 8191, True), ("x" * 8193, True), ("x" * 8192, False)]:
            f, sources = self.large_text_fixture(original)
            for lane, key in [(f["live"][0]["message"], "content"), (f["replay"][0]["content"], "text")]:
                lane[key] = text; lane["truncated"] = flag
            self.assertTrue(oracle.wire_failures(f, sources))

    def test_large_argument_original_values_and_flag_are_checked(self):
        original = {"path": "a.txt", "content": "x" * 20000}
        expected = next(iter(oracle.serialized_prefixes(original)))
        sources = {"texts": [], "calls": [{"name": "read_file", "args": original}], "evidence": []}
        for value, flag, passing in [(expected, True, True), (expected, False, False),
                                     ("y" + expected[1:], True, False), (expected[:-1], True, False)]:
            f = copy.deepcopy(self.fixture)
            for step in [f["live"][0]["message"], f["replay"][0]["content"]]:
                step["tool_calls"][0].update(args=value, args_truncated=flag)
            self.assertEqual(passing, not oracle.wire_failures(f, sources))

    def test_argument_metadata_must_match_and_have_boolean_type(self):
        f = copy.deepcopy(self.fixture)
        f["live"][0]["message"]["tool_calls"][0]["args_truncated"] = True
        self.assertTrue(oracle.wire_failures(f, self.sources))
        for step in [f["live"][0]["message"], f["replay"][0]["content"]]:
            step["tool_calls"][0]["args_truncated"] = "true"
        self.assertTrue(oracle.wire_failures(f, self.sources))

    def test_large_original_source_cannot_disappear_from_wire(self):
        self.sources["calls"] = [{"name": "different_tool", "args": {"content": "x" * 20000}}]
        self.assertTrue(oracle.wire_failures(self.fixture, self.sources))

    def test_original_provider_stream_fragments_are_reassembled(self):
        with tempfile.TemporaryDirectory() as directory:
            arg = json.dumps({"content": "x" * 20000})
            deltas = [{"content": "x" * 8191}, {"content": "😀界"},
                      {"tool_calls": [{"index": 0, "function": {"name": "read_file", "arguments": arg[:100]}}]},
                      {"tool_calls": [{"index": 0, "function": {"arguments": arg[100:]}}]}]
            wire = "\n".join("data: " + json.dumps({"choices": [{"delta": delta}]}) for delta in deltas)
            (Path(directory) / "provider-response-1.sse").write_text(wire + "\ndata: [DONE]\n", encoding="utf-8")
            sources = oracle.provider_sources(Path(directory))
            self.assertEqual("x" * 8191 + "😀界", sources["texts"][0])
            self.assertEqual({"content": "x" * 20000}, sources["calls"][0]["args"])
            self.assertEqual(1, len(sources["evidence"]))


if __name__ == "__main__":
    unittest.main()

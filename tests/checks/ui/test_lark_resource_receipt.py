"""Fail-closed tests for raw Skill resource receipts; isolated test SQLite only."""
import hashlib
import importlib.util
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location(
    "lark_resource_receipt", ROOT / "tests/ui/fixtures/w20/lark_resource_receipt.py")
receipt = importlib.util.module_from_spec(spec)
spec.loader.exec_module(receipt)

CONTENT = "# docs +fetch\nlark-cli docs +fetch --doc \"https://example.feishu.cn/docx/Z1\"\n"
SHA = hashlib.sha256(CONTENT.encode("utf-8")).hexdigest()
PATH = "references/lark-doc-fetch.md"
RUN = "run-1"
CALL = "call-1"


def payload(content=CONTENT, digest=None, successful=True, truncated=False):
    return json.dumps({
        "successful": successful, "truncated": truncated,
        "summary": "Read activated Skill resource", "artifacts": [], "assets": [],
        "structuredData": {
            "content": content,
            "digest": digest if digest is not None else f"sha256:{SHA}",
            "mediaType": "text/markdown", "path": PATH,
            "securityLabel": "untrusted-skill-resource", "skill": "lark-doc",
            "source": "lark-doc@sha256:0" * 1,
        },
    }, ensure_ascii=False)


class ResourceReceiptTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.data = self.root / "local-tmp" / "data"
        runtime = self.data / "runtime"
        runtime.mkdir(parents=True)
        self.database = runtime / "haifa-agent.sqlite"
        self.connection = sqlite3.connect(self.database)
        self.connection.executescript(
            "CREATE TABLE run (run_id TEXT PRIMARY KEY, status TEXT NOT NULL);"
            "CREATE TABLE tool_call (tool_call_id TEXT PRIMARY KEY, run_id TEXT NOT NULL,"
            " provider_correlation_id TEXT, tool_name TEXT NOT NULL, status TEXT NOT NULL,"
            " result_schema_version TEXT, result_payload BLOB NOT NULL, result_hash TEXT);")
        self.connection.execute("INSERT INTO run VALUES (?, 'COMPLETED')", (RUN,))
        self.record(CALL, "corr-1", payload())
        self.connection.commit()
        self.fingerprint = self.snapshot()

    def tearDown(self):
        self.connection.close()

    def record(self, call, correlation, body, run=RUN, name="skill_resource_read",
               status="COMPLETED"):
        self.connection.execute(
            "INSERT INTO tool_call VALUES (?, ?, ?, ?, ?, '1', ?, 'sha256:fixture')",
            (call, run, correlation, name, status, body.encode("utf-8")))

    def snapshot(self):
        data = self.database.read_bytes()
        return (len(data), hashlib.sha256(data).hexdigest(),
                self.database.stat().st_mtime_ns)

    def read(self, call=CALL, run=RUN, expected=SHA, path=PATH):
        return receipt.read_receipt(self.root, self.data, run, call, "lark-doc", path, expected)

    def assert_unchanged(self):
        self.assertEqual(self.snapshot(), self.fingerprint)

    def test_exact_record_returns_verified_raw_bytes(self):
        result = self.read()
        self.assertEqual(result["content"], CONTENT)
        self.assertEqual(result["receipt"]["sha256"], SHA)
        self.assertEqual(result["receipt"]["digest"], f"sha256:{SHA}")
        self.assertEqual(result["receipt"]["sdkToolCallId"], CALL)
        self.assertEqual(result["receipt"]["runId"], RUN)
        self.assertFalse(result["receipt"]["correlationUsed"])
        self.assertEqual(result["receipt"]["status"], "COMPLETED")
        self.assert_unchanged()

    def test_wrong_run_or_call_fails_without_content(self):
        for kwargs in ({"run": "run-other"}, {"call": "call-other"}):
            with self.assertRaises(receipt.ReceiptError) as caught:
                self.read(**kwargs)
            self.assertNotIn(CONTENT, str(caught.exception))
        self.assert_unchanged()

    def test_mismatched_expected_hash_never_exports_content(self):
        with self.assertRaisesRegex(receipt.ReceiptError, "official reference"):
            self.read(expected="0" * 64)
        self.assert_unchanged()

    def test_stored_digest_mismatch_fails_closed(self):
        self.connection.execute("DELETE FROM tool_call")
        self.record(CALL, "corr-1", payload(digest="sha256:" + "1" * 64))
        self.connection.commit()
        self.fingerprint = self.snapshot()
        with self.assertRaisesRegex(receipt.ReceiptError, "digest"):
            self.read()
        self.assert_unchanged()

    def test_unsuccessful_truncated_or_incomplete_records_fail(self):
        cases = ((payload(successful=False), "successful"),
                 (payload(truncated=True), "truncated"))
        for body, message in cases:
            self.connection.execute("DELETE FROM tool_call")
            self.record(CALL, "corr-1", body)
            self.connection.commit()
            with self.assertRaisesRegex(receipt.ReceiptError, message):
                self.read()
        self.connection.execute("DELETE FROM tool_call")
        self.record(CALL, "corr-1", payload(), status="FAILED")
        self.connection.commit()
        with self.assertRaisesRegex(receipt.ReceiptError, "COMPLETED"):
            self.read()

    def test_provider_correlation_requires_one_exact_match(self):
        self.connection.execute("DELETE FROM tool_call")
        self.record(CALL, "corr-1", payload())
        self.connection.commit()
        self.fingerprint = self.snapshot()
        result = self.read(call="corr-1")
        self.assertTrue(result["receipt"]["correlationUsed"])
        self.assertEqual(result["receipt"]["sdkToolCallId"], CALL)
        self.record("call-2", "corr-1", payload())
        self.connection.commit()
        with self.assertRaisesRegex(receipt.ReceiptError, "exactly one"):
            self.read(call="corr-1")

    def test_links_and_reparse_points_are_rejected(self):
        from unittest.mock import patch
        real = Path.lstat

        def linked(path, *args, **kwargs):
            if path == self.data.resolve():
                class Reparse:
                    st_mode = 0o40755
                    st_file_attributes = 0x400
                return Reparse()
            return real(path, *args, **kwargs)

        with patch.object(Path, "lstat", linked):
            with self.assertRaisesRegex(receipt.ReceiptError, "reparse"):
                self.read()


if __name__ == "__main__":
    unittest.main()

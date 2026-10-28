"""Read the raw SDK tool result of one exact owned Run, read-only.

The public ``ToolCallView`` projects a bounded, redacted display copy. This fixture
inspects the owned Runtime database instead, binds the record to the exact Run and
tool call, verifies the stored raw bytes against the official SHA-256, and returns
them only after that guard passes. It never writes, migrates or projects Runtime
state, and it never falls back to public/projected content or the source file.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from lark_seed import safe_path  # noqa: E402  reuse link/reparse rejection

SKILL = "skill_resource_read"
MAX_PAYLOAD_BYTES = 2 * 1024 * 1024
MAX_CONTENT_BYTES = 1024 * 1024
OFFICIAL_MEDIA_TYPE = "text/markdown"
OFFICIAL_SECURITY_LABEL = "untrusted-skill-resource"


class ReceiptError(ValueError):
    """Fail-closed receipt error; never carries record content."""


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _checked(path: Path) -> Path:
    try:
        return safe_path(path)
    except ValueError as error:
        raise ReceiptError(str(error)) from None


def _owned_data_root(repo_root: Path, data_root: Path) -> Path:
    owned = repo_root.resolve() / "local-tmp"
    candidate = _checked(data_root)
    if candidate == owned or owned not in candidate.parents:
        raise ReceiptError("Data root must stay inside this worktree local-tmp")
    return candidate


def _database(data_root: Path) -> Path:
    database = data_root / "runtime" / "haifa-agent.sqlite"
    if not database.is_file():
        raise ReceiptError("Owned Runtime database is missing")
    _checked(database)
    return database


def _connect(database: Path) -> sqlite3.Connection:
    connection = sqlite3.connect(f"{database.as_uri()}?mode=ro", uri=True, timeout=5)
    connection.execute("PRAGMA query_only=ON")
    return connection


def _payload(row: tuple) -> dict:
    kind = row[0]
    if isinstance(kind, memoryview):
        kind = kind.tobytes()
    if not isinstance(kind, (bytes, bytearray)):
        raise ReceiptError("Tool result payload is not stored as bytes")
    if len(kind) > MAX_PAYLOAD_BYTES:
        raise ReceiptError("Tool result payload exceeds the byte limit")
    try:
        payload = json.loads(kind)
    except ValueError:
        raise ReceiptError("Tool result payload is not JSON") from None
    if not isinstance(payload, dict):
        raise ReceiptError("Tool result payload is not an object")
    return payload


def _validate(
    payload: dict, skill: str, resource_path: str, expected_sha256: str
) -> tuple[str, dict]:
    if not re.fullmatch(r"[0-9a-f]{64}", expected_sha256):
        raise ReceiptError("Expected SHA-256 must be 64 lowercase hex characters")
    if payload.get("successful") is not True:
        raise ReceiptError("Tool result is not successful")
    if payload.get("truncated") is not False:
        raise ReceiptError("Tool result is truncated or not explicitly complete")
    structured = payload.get("structuredData")
    if not isinstance(structured, dict):
        raise ReceiptError("Tool result has no structured data")
    if structured.get("skill") != skill:
        raise ReceiptError("Tool result skill does not match the requested skill")
    if structured.get("path") != resource_path:
        raise ReceiptError("Tool result path does not match the official reference")
    if structured.get("mediaType") != OFFICIAL_MEDIA_TYPE:
        raise ReceiptError("Tool result media type is not the official one")
    if structured.get("securityLabel") != OFFICIAL_SECURITY_LABEL:
        raise ReceiptError("Tool result security label is not the official one")
    content = structured.get("content")
    if not isinstance(content, str):
        raise ReceiptError("Tool result has no text content")
    data = content.encode("utf-8")
    if len(data) > MAX_CONTENT_BYTES:
        raise ReceiptError("Tool result content exceeds the byte limit")
    raw_sha256 = _sha256(data)
    if structured.get("digest") != f"sha256:{expected_sha256}":
        raise ReceiptError("Stored raw digest does not match the official reference")
    if raw_sha256 != expected_sha256:
        raise ReceiptError("Raw tool result bytes do not match the official reference")
    receipt = {
        "toolName": SKILL,
        "status": "COMPLETED",
        "successful": True,
        "truncated": False,
        "digest": f"sha256:{raw_sha256}",
        "sha256": raw_sha256,
        "byteLength": len(data),
        "path": structured["path"],
        "skill": structured["skill"],
        "mediaType": structured["mediaType"],
        "securityLabel": structured["securityLabel"],
    }
    return content, receipt


def read_receipt(
    repo_root: Path,
    data_root: Path,
    run_id: str,
    call_id: str,
    skill: str,
    resource_path: str,
    expected_sha256: str,
) -> dict:
    """Return ``{"receipt": ..., "content": ...}`` for one exact, verified record."""
    data_root = _owned_data_root(Path(repo_root), Path(data_root))
    database = _database(data_root)
    connection = _connect(database)
    try:
        columns = (
            "SELECT tool_call_id, run_id, provider_correlation_id, tool_name, status, "
            "result_schema_version, result_payload, result_hash FROM tool_call "
            "WHERE run_id = ? AND tool_call_id = ? AND tool_name = ?"
        )
        rows = list(connection.execute(columns, (run_id, call_id, SKILL)))
        correlated = False
        if not rows:
            fallback = (
                "SELECT tool_call_id, run_id, provider_correlation_id, tool_name, status, "
                "result_schema_version, result_payload, result_hash FROM tool_call "
                "WHERE run_id = ? AND provider_correlation_id = ? AND tool_name = ?"
            )
            rows = list(connection.execute(fallback, (run_id, call_id, SKILL)))
            correlated = bool(rows)
        if len(rows) != 1:
            raise ReceiptError("Expected exactly one owned tool call record")
        sdk_id, row_run_id, correlation_id, tool_name, status, schema, payload_bytes, result_hash = rows[0]
        if row_run_id != run_id or tool_name != SKILL:
            raise ReceiptError("Resolved record does not belong to this Run and tool")
        if correlated and correlation_id != call_id:
            raise ReceiptError("Provider correlation does not match the public call id")
        if status != "COMPLETED":
            raise ReceiptError("Tool call is not COMPLETED")
        harness = connection.execute(
            "SELECT status FROM run WHERE run_id = ?", (run_id,)
        ).fetchone()
        if not harness or harness[0] != "COMPLETED":
            raise ReceiptError("Run is not COMPLETED")
        payload = _payload((payload_bytes,))
        content, receipt = _validate(payload, skill, resource_path, expected_sha256)
        receipt.update(
            {
                "schemaVersion": schema,
                "runId": run_id,
                "callId": call_id,
                "sdkToolCallId": sdk_id,
                "providerCorrelationId": correlation_id,
                "correlationUsed": correlated,
                "resultHash": result_hash,
            }
        )
        return {"receipt": receipt, "content": content}
    finally:
        connection.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--data-root", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--call-id", required=True)
    parser.add_argument("--skill", required=True)
    parser.add_argument("--path", dest="resource_path", required=True)
    parser.add_argument("--expected-sha256", required=True)
    args = parser.parse_args()
    try:
        if args.repo_root.resolve() != Path(__file__).resolve().parents[4]:
            raise ReceiptError("Receipt CLI must stay in its own worktree")
        result = read_receipt(
            args.repo_root,
            args.data_root,
            args.run_id,
            args.call_id,
            args.skill,
            args.resource_path,
            args.expected_sha256,
        )
    except (ReceiptError, OSError, sqlite3.Error) as error:
        print(json.dumps({"status": "FAIL", "error": str(error)}), file=sys.stderr)
        return 1
    print(json.dumps({"status": "OK", **result}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

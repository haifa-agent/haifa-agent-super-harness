"""Decides whether existing gate evidence can be reused for a target commit.

Two ways to run it:

    python scripts/governance/evidence_reuse.py --target <commit> <evidence file or dir>...
    python scripts/governance/evidence_reuse.py --reusable-summary <dir> --fingerprint <sha256>

The first is the read-only checker for executors and reviewers. It discovers, recursively
and by the same shapes as ``shpm_record_gates.py``:

- full-gate summaries (``verify-full-*.json``) -> reusable when the Java input fingerprint
  equals the target commit's; a summary that is not PASS, or that lacks the fingerprint, is
  STALE;
- script-regression summaries (``verify-scripts-*.json``) and UI evidence directories
  (``flow-health.json`` + ``case-results.json``) -> reusable only when ``sourceTree`` and
  ``sdkIdentity`` both equal the target commit's and ``sourceDirty`` is false; evidence from
  before content fingerprints existed is STALE.

All reusable exits 0; otherwise 2. The second form is what ``verify-full.ps1`` calls: it
returns the newest real (non-reuse) PASS summary in a directory whose
``javaInputFingerprint`` matches, or nothing. A summary that is itself a reuse result is
never reused again.
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))

import quick_checks  # noqa: E402  (path added above)
import source_fingerprint  # noqa: E402  (path added above)

STALE_OLD_EVIDENCE = "证据早于内容判定"
TIMESTAMP_PATTERN = re.compile(r"(\d{8})-(\d{6})-(\d{1,6})")


def read_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except (json.JSONDecodeError, UnicodeDecodeError, OSError):
        return None


def is_ui_evidence_dir(path: Path) -> bool:
    return (path / "flow-health.json").is_file() and (path / "case-results.json").is_file()


def classify_json(path: Path) -> str:
    data = read_json(path)
    if not isinstance(data, dict):
        return "unrecognized"
    if path.name.startswith("refresh-") and "jarSha256" in data and "status" in data:
        return "refresh"
    if "sourceSha" in data and "status" in data and ("forks" in data or "preflightFailures" in data or "sdkSelection" in data):
        return "full-gate"
    if "checks" in data and "logDirectory" in data and "sourceSha" in data:
        return "script-regressions"
    return "unrecognized"


def parse_summary_start(identity: str | None):
    match = TIMESTAMP_PATTERN.search(identity or "")
    if not match:
        return None
    date, clock, fraction = match.groups()
    fraction = (fraction + "000000")[:6]
    try:
        return datetime.datetime.strptime(date + clock + fraction, "%Y%m%d%H%M%S%f")
    except ValueError:
        return None


def find_reusable_summary(directory: Path, fingerprint: str | None) -> Path | None:
    """The newest real PASS full-gate summary in ``directory`` with the same fingerprint."""
    if not fingerprint or not directory.is_dir():
        return None
    candidates: list[tuple[datetime.datetime, Path]] = []
    for path in sorted(directory.glob("verify-full-*.json")):
        data = read_json(path)
        if not isinstance(data, dict):
            continue
        if data.get("status") != "PASS" or "reuse" in data:
            continue
        if data.get("javaInputFingerprint") != fingerprint:
            continue
        candidates.append((parse_summary_start(path.stem) or datetime.datetime.min, path))
    if not candidates:
        return None
    candidates.sort(key=lambda item: (item[0], item[1].name))
    return candidates[-1][1]


def sdk_unknown(value) -> bool:
    return not value or value == source_fingerprint.SDK_UNAVAILABLE


def evaluate_full_gate(summary: dict, target: dict) -> tuple[str, str]:
    fingerprint = summary.get("javaInputFingerprint")
    if fingerprint is None:
        return "STALE", STALE_OLD_EVIDENCE
    # Two unknown SDK identities being "equal" proves nothing about the SDK contents.
    if sdk_unknown(target.get("sdkIdentity")):
        return "STALE", "目标提交的 SDK 身份不可用"
    if sdk_unknown(summary.get("sdkIdentity")):
        return "STALE", "证据的 SDK 身份不可用"
    if summary.get("status") != "PASS":
        return "STALE", f"摘要状态不是 PASS（{summary.get('status')}）"
    if fingerprint != target["javaInputFingerprint"]:
        return "STALE", "Java 输入指纹不同"
    return "REUSABLE", "Java 输入指纹相同"


def evaluate_tree_evidence(summary: dict, target: dict) -> tuple[str, str]:
    if "sourceTree" not in summary or "sdkIdentity" not in summary or "sourceDirty" not in summary:
        return "STALE", STALE_OLD_EVIDENCE
    if summary.get("sourceDirty"):
        return "STALE", "证据来自有未提交修改的工作区"
    if sdk_unknown(target.get("sdkIdentity")):
        return "STALE", "目标提交的 SDK 身份不可用"
    if sdk_unknown(summary.get("sdkIdentity")):
        return "STALE", "证据的 SDK 身份不可用"
    if summary.get("sourceTree") != target["sourceTree"]:
        return "STALE", "源码树哈希不同"
    if summary.get("sdkIdentity") != target["sdkIdentity"]:
        return "STALE", "SDK 身份不同"
    return "REUSABLE", "源码树哈希与 SDK 身份相同"


def discover(paths: list[Path]) -> list[dict]:
    """Every evidence item under the inputs, as ``{kind, path, data}``."""
    found: list[dict] = []
    for raw in paths:
        root = raw.resolve()
        if root.is_dir():
            for dirpath, dirnames, filenames in os.walk(root):
                current = Path(dirpath)
                if is_ui_evidence_dir(current):
                    flow = read_json(current / "flow-health.json") or {}
                    found.append({"kind": "ui-evidence", "path": current, "data": flow})
                    dirnames[:] = []
                    continue
                for filename in sorted(filenames):
                    if not filename.lower().endswith(".json"):
                        continue
                    file_path = current / filename
                    kind = classify_json(file_path)
                    if kind in ("full-gate", "script-regressions"):
                        found.append({"kind": kind, "path": file_path, "data": read_json(file_path) or {}})
        elif root.is_file():
            kind = classify_json(root)
            if kind in ("full-gate", "script-regressions"):
                found.append({"kind": kind, "path": root, "data": read_json(root) or {}})
        else:
            raise FileNotFoundError(f"evidence path does not exist: {root}")
    return found


def evaluate(evidence: dict, target: dict) -> dict:
    data = evidence["data"]
    original = data.get("sourceSha") or ""
    if evidence["kind"] == "full-gate":
        verdict, reason = evaluate_full_gate(data, target)
    else:
        verdict, reason = evaluate_tree_evidence(data, target)
    return {
        "path": str(evidence["path"]),
        "kind": evidence["kind"],
        "originalCommit": original,
        "verdict": verdict,
        "reason": reason,
    }


def main(argv: list[str] | None = None) -> int:
    quick_checks.force_utf8_stdout()
    parser = argparse.ArgumentParser(description="Decide whether gate evidence can be reused")
    parser.add_argument("results", nargs="*", type=Path)
    parser.add_argument("--target")
    parser.add_argument("--reusable-summary", type=Path)
    parser.add_argument("--fingerprint")
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--maven-repo", type=Path, default=None)
    parser.add_argument("--version")
    args = parser.parse_args(argv)

    if args.reusable_summary is not None:
        found = find_reusable_summary(args.reusable_summary, args.fingerprint)
        print(json.dumps({"reusableSummary": str(found) if found else None}, ensure_ascii=False))
        return 0

    if not args.target:
        print("--target is required unless --reusable-summary is used", file=sys.stderr)
        return 1
    try:
        target = source_fingerprint.fingerprint(args.repo, args.target, args.maven_repo, args.version)
        evidence = discover(args.results)
        rows = [evaluate(item, target) for item in evidence]
    except (source_fingerprint.FingerprintError, OSError) as error:
        print(str(error), file=sys.stderr)
        return 1
    if not rows:
        print("no gate evidence found under the given paths", file=sys.stderr)
        return 2
    print(json.dumps(rows, indent=2, ensure_ascii=False))
    return 0 if all(row["verdict"] == "REUSABLE" for row in rows) else 2


if __name__ == "__main__":
    sys.exit(main())

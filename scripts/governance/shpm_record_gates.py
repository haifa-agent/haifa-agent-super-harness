"""Records gate results in SHPM straight from the files the gates wrote.

Accepts any mix of:

- verify-full summaries (``local-tmp/verify-full/*.json``) -> one verification ``verify-full``;
- UI round evidence directories (with ``case-results.json`` and ``flow-health.json``)
  -> one verification per case.

Results are copied exactly: PASS stays PASS; FAIL, FAIL_PREFLIGHT and FAIL_NO_TESTS_MATCHED
become FAIL; anything else is recorded as reported. The script never turns a failure into a
pass, never retries, and never delivers, accepts or decides a stage. Without --execute the
``shpm verify add`` commands are only printed.

    python scripts/governance/shpm_record_gates.py --task W05-task-12 \\
        local-tmp/verify-full/verify-full-20261003-011624-032.json \\
        local-tmp/slot-2-w05-demo-artifact/evidence/run-20261003-012745-472-c251f482 [--execute]
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
import re
import subprocess
import sys
from pathlib import Path

DEFAULT_DB = r"C:\Users\wangr\.shpm\data\project.db"
FAILURE_STATUSES = {"FAIL", "FAIL_PREFLIGHT", "FAIL_NO_TESTS_MATCHED"}


def shpm_result(status: str | None) -> str:
    if status == "PASS":
        return "PASS"
    if status in FAILURE_STATUSES:
        return "FAIL"
    return status or "NOT_RUN"


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def records_for(path: Path) -> list[dict]:
    if path.is_file():
        summary = read_json(path)
        failures = summary.get("preflightFailures") or []
        detail = (
            "preflight: " + "; ".join(failures)
            if failures
            else "tests={tests} failures={failures} errors={errors} skipped={skipped} forks={forks} sdk={sdk}".format(
                tests=summary.get("tests"), failures=summary.get("failures"), errors=summary.get("errors"),
                skipped=summary.get("skipped"), forks=summary.get("forks"), sdk=summary.get("haifaVersion"))
        )
        return [{
            "key": "verify-full",
            "criteria": "Offline mvn clean verify passes at the delivered commit",
            "result": shpm_result(summary.get("status")),
            "command": summary.get("command") or "scripts/verify-full.ps1",
            "sha": summary.get("sourceSha") or "",
            "raw": summary.get("logPath") or str(path),
            "duration": f"{int((summary.get('elapsedMs') or 0) / 1000)}s",
            "retry": 0,
            "summary": detail,
        }]
    flow = read_json(path / "flow-health.json")
    cases = read_json(path / "case-results.json")
    round_name = flow.get("buildMode") or "ui"
    cleanup_ok = (flow.get("cleanup") or {}).get("success")
    records = []
    for case in cases:
        name = case.get("name") or "case"
        slug = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")[:48].rstrip("-")
        first_failure = case.get("firstFailure")
        records.append({
            "key": f"ui-{round_name}-{slug}",
            "criteria": name,
            "result": shpm_result(case.get("result")),
            "command": f"scripts/ui/run-w18-ui-smoke.ps1 ({round_name})",
            "sha": flow.get("sourceSha") or "",
            "raw": str(path),
            "duration": f"{int((flow.get('totalMs') or 0) / 1000)}s",
            "retry": int(flow.get("retryCount") or 0),
            "summary": f"retries={flow.get('retryCount')} cleanup={'ok' if cleanup_ok else 'FAILED'}"
                       + (f" first failure: {first_failure}" if first_failure else ""),
        })
    return records


def build_command(task: str, record: dict, db: str, day: str) -> list[str]:
    key = f"{day}-{task}-{record['key']}-{record['sha'][:8]}"
    return ["verify", "add", "--task", task, "--key", record["key"], "--criteria", record["criteria"],
            "--result", record["result"], "--required", "--command", record["command"],
            "--repo-sha", record["sha"], "--raw", record["raw"], "--duration", record["duration"],
            "--retry-count", str(record["retry"]), "--summary", record["summary"],
            "--idempotency-key", key, "--json", "--db", db]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Record gate results in SHPM")
    parser.add_argument("--task", required=True)
    parser.add_argument("results", nargs="+", type=Path)
    parser.add_argument("--db", default=DEFAULT_DB)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args(argv)
    day = datetime.date.today().isoformat()
    commands = [build_command(args.task, record, args.db, day)
                for path in args.results for record in records_for(path.resolve())]
    env = dict(os.environ, SHPM_DB=args.db, PYTHONIOENCODING="utf-8")
    for command in commands:
        display = "shpm " + " ".join(f"'{part}'" if " " in part else part for part in command)
        if not args.execute:
            print(display)
            continue
        result = subprocess.run(["shpm", *command], capture_output=True, text=True, encoding="utf-8", errors="replace", env=env)
        if result.returncode != 0:
            print(display)
            print(result.stdout + result.stderr)
            print(f"shpm verify add failed with exit code {result.returncode}; later records were not added.", file=sys.stderr)
            return result.returncode
    if not args.execute:
        print("(dry run: pass --execute to write these records)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""Records gate results in SHPM straight from the files the gates wrote.

Accepts any mix of files and directories and discovers, recursively:

- full-gate summaries (``local-tmp/verify-full/*.json``) -> one verification ``verify-full``;
- script-regression summaries (``local-tmp/verify-scripts/*.json``) -> one verification per
  check (``scripts-<check>``);
- UI round evidence directories (with ``case-results.json`` and ``flow-health.json``) -> one
  verification per case named ``ui-<profile>-<case>``;
- main-service refresh summaries (``main-service/<sha>/refresh-*.json``) -> one verification
  ``main-service-refresh``.

JSON files that match none of these shapes are ignored and listed. Results are copied
exactly: PASS stays PASS; FAIL, FAIL_PREFLIGHT and FAIL_NO_TESTS_MATCHED become FAIL;
``NOT_RUN`` cases are recorded as ``NOT_RUN``. The script never turns a failure into a pass,
never retries, and never delivers, accepts or decides a stage. Without --execute the table of
``shpm verify add`` rows is only printed.

The idempotency key carries the evidence identity (a summary file name, or the UI evidence
directory's ``run-*`` identity taken from ``flow-health.json``'s ``evidenceRoot``), so the same
evidence is idempotent while a second round with different evidence writes separately. UI check
names carry the profile; two evidence directories for the same profile and commit in one call
get ``-r1`` / ``-r2`` by their recorded start time.

    python scripts/governance/shpm_record_gates.py --task W05-task-12 \\
        local-tmp/slot-2-w05-demo-artifact/evidence/final-33fa688 [--raw-root <moved root>] [--execute]
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
RUN_ID_PATTERN = re.compile(r"run-(\d{8})-(\d{6})-(\d{3})-([0-9a-fA-F]+)")


class EvidenceError(RuntimeError):
    """Raised when evidence cannot be registered as requested."""


def shpm_result(status: str | None) -> str:
    if status == "PASS":
        return "PASS"
    if status in FAILURE_STATUSES:
        return "FAIL"
    return status or "NOT_RUN"


def read_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except (json.JSONDecodeError, UnicodeDecodeError):
        return None


def slugify(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", (name or "case").lower()).strip("-")[:48].rstrip("-")


def parse_run_start(identity: str | None):
    match = RUN_ID_PATTERN.search(identity or "")
    if not match:
        return None
    try:
        return datetime.datetime.strptime("".join(match.group(1, 2, 3)), "%Y%m%d%H%M%S%f")
    except ValueError:
        return None


def is_ui_evidence_dir(path: Path) -> bool:
    return (path / "flow-health.json").is_file() and (path / "case-results.json").is_file()


def classify_json(path: Path) -> tuple[str, dict | None]:
    data = read_json(path)
    if not isinstance(data, dict):
        return "unrecognized", None
    if path.name.startswith("refresh-") and "jarSha256" in data and "status" in data:
        return "refresh", data
    if "forks" in data and "sdkSelection" in data and "status" in data:
        return "verify-full", data
    if "checks" in data and "logDirectory" in data and "sourceSha" in data:
        return "script-regressions", data
    return "unrecognized", None


def verify_full_records(path: Path, summary: dict, anchor: Path) -> list[dict]:
    failures = summary.get("preflightFailures") or []
    detail = (
        "preflight: " + "; ".join(failures)
        if failures
        else "tests={tests} failures={failures} errors={errors} skipped={skipped} forks={forks} sdk={sdk}".format(
            tests=summary.get("tests"), failures=summary.get("failures"), errors=summary.get("errors"),
            skipped=summary.get("skipped"), forks=summary.get("forks"), sdk=summary.get("haifaVersion"))
    )
    return [{
        "base_name": "verify-full",
        "round": False,
        "criteria": "Offline mvn clean verify passes at the delivered commit",
        "result": shpm_result(summary.get("status")),
        "command": summary.get("command") or "scripts/verify-full.ps1",
        "sha": summary.get("sourceSha") or "",
        "raw": str(path),
        "duration": f"{int((summary.get('elapsedMs') or 0) / 1000)}s",
        "retry": 0,
        "summary": detail,
        "identity": path.stem,
        "start": None,
        "local_path": path,
        "anchor": anchor,
    }]


def script_records(path: Path, summary: dict, anchor: Path) -> list[dict]:
    checks = summary.get("checks") or []
    if not checks:
        return [{
            "base_name": "verify-scripts",
            "round": False,
            "criteria": "Script regressions pass",
            "result": shpm_result(summary.get("status")),
            "command": "scripts/verify-scripts.ps1",
            "sha": summary.get("sourceSha") or "",
            "raw": str(path),
            "duration": f"{int((summary.get('elapsedMs') or 0) / 1000)}s",
            "retry": 0,
            "summary": "; ".join(summary.get("manifestErrors") or []) or "no checks recorded",
            "identity": path.stem,
            "start": None,
            "local_path": path,
            "anchor": anchor,
        }]
    records = []
    for check in checks:
        records.append({
            "base_name": f"scripts-{check.get('name')}",
            "round": False,
            "criteria": f"Script regression {check.get('name')} passes",
            "result": shpm_result(check.get("status")),
            "command": " ".join(check.get("command") or ["scripts/verify-scripts.ps1"]),
            "sha": summary.get("sourceSha") or "",
            "raw": check.get("log") or str(path),
            "duration": f"{int((check.get('elapsedMs') or 0) / 1000)}s",
            "retry": 0,
            "summary": f"exitCode={check.get('exitCode')}",
            "identity": f"{path.stem}-{check.get('name')}",
            "start": None,
            "local_path": path,
            "anchor": anchor,
        })
    return records


def refresh_records(path: Path, summary: dict, anchor: Path) -> list[dict]:
    return [{
        "base_name": "main-service-refresh",
        "round": False,
        "criteria": "Main service refresh runs the copied JAR",
        "result": shpm_result(summary.get("status")),
        "command": "scripts/refresh-main-service.ps1",
        "sha": summary.get("sourceSha") or "",
        "raw": str(path),
        "duration": f"{int((summary.get('elapsedMs') or 0) / 1000)}s",
        "retry": 0,
        "summary": f"jarSha256={summary.get('jarSha256')} oldPid={summary.get('oldGatewayPid')} newPid={summary.get('newGatewayPid')}",
        "identity": path.stem,
        "start": None,
        "local_path": path,
        "anchor": anchor,
    }]


def ui_records(path: Path, anchor: Path) -> list[dict]:
    flow = read_json(path / "flow-health.json") or {}
    cases = read_json(path / "case-results.json") or []
    profile = flow.get("acceptanceProfile")
    if not profile:
        raise EvidenceError(
            f"UI evidence '{path}' has no 'acceptanceProfile' in flow-health.json; stop and report "
            "instead of guessing. Do not add the field to the runner.")
    identity = Path(str(flow.get("evidenceRoot") or "")).name or path.name
    start = parse_run_start(identity)
    cleanup_ok = (flow.get("cleanup") or {}).get("success")
    records = []
    for case in cases:
        name = case.get("name") or "case"
        first_failure = case.get("firstFailure")
        records.append({
            "base_name": f"ui-{profile}-{slugify(name)}",
            "round": True,
            "criteria": name,
            "result": shpm_result(case.get("result")),
            "command": f"scripts/ui/run-w18-ui-smoke.ps1 ({profile})",
            "sha": flow.get("sourceSha") or "",
            "raw": str(path),
            "duration": f"{int((flow.get('totalMs') or 0) / 1000)}s",
            "retry": int(flow.get("retryCount") or 0),
            "summary": f"retries={flow.get('retryCount')} cleanup={'ok' if cleanup_ok else 'FAILED'}"
                       + (f" first failure: {first_failure}" if first_failure else ""),
            "identity": identity,
            "start": start,
            "local_path": path,
            "anchor": anchor,
        })
    return records


def records_for(path: Path) -> list[dict]:
    """Records for a single file or a single UI evidence directory."""
    resolved = path.resolve()
    if resolved.is_dir():
        if is_ui_evidence_dir(resolved):
            return ui_records(resolved, resolved)
        raise EvidenceError(f"directory '{resolved}' is not a UI evidence directory")
    kind, data = classify_json(resolved)
    if kind == "verify-full":
        return verify_full_records(resolved, data, resolved)
    if kind == "script-regressions":
        return script_records(resolved, data, resolved)
    if kind == "refresh":
        return refresh_records(resolved, data, resolved)
    raise EvidenceError(f"unrecognized JSON '{resolved}'")


def discover(roots: list[Path]) -> tuple[list[dict], list[Path]]:
    records: list[dict] = []
    unrecognized: list[Path] = []
    for raw in roots:
        root = raw.resolve()
        if root.is_dir():
            for dirpath, dirnames, filenames in os.walk(root):
                current = Path(dirpath)
                if is_ui_evidence_dir(current):
                    records.extend(ui_records(current, root))
                    dirnames[:] = []
                    continue
                for filename in filenames:
                    if not filename.lower().endswith(".json"):
                        continue
                    file_path = current / filename
                    kind, data = classify_json(file_path)
                    if kind == "verify-full":
                        records.extend(verify_full_records(file_path, data, root))
                    elif kind == "script-regressions":
                        records.extend(script_records(file_path, data, root))
                    elif kind == "refresh":
                        records.extend(refresh_records(file_path, data, root))
                    else:
                        unrecognized.append(file_path)
        elif root.is_file():
            kind, data = classify_json(root)
            if kind == "verify-full":
                records.extend(verify_full_records(root, data, root))
            elif kind == "script-regressions":
                records.extend(script_records(root, data, root))
            elif kind == "refresh":
                records.extend(refresh_records(root, data, root))
            else:
                unrecognized.append(root)
        else:
            raise EvidenceError(f"evidence path does not exist: {root}")
    return records, unrecognized


def assign_rounds(records: list[dict]) -> None:
    """Adds -r1 / -r2 to UI records that share a profile and commit in this call."""
    groups: dict[tuple[str, str], list[dict]] = {}
    for record in records:
        if record["round"]:
            groups.setdefault((record["base_name"], record["sha"]), []).append(record)
        else:
            record["name"] = record["base_name"]
    for group in groups.values():
        if len(group) == 1:
            group[0]["name"] = group[0]["base_name"]
            continue
        group.sort(key=lambda record: (record["start"] is None, record["start"] or 0, record["identity"]))
        for index, record in enumerate(group, 1):
            record["name"] = f"{record['base_name']}-r{index}"


def apply_raw_root(records: list[dict], raw_root: Path) -> None:
    if not raw_root.is_dir():
        raise EvidenceError(f"--raw-root '{raw_root}' is not a directory")
    for record in records:
        local = record["local_path"]
        anchor = record["anchor"]
        relative = local.relative_to(anchor) if local != anchor else Path(local.name)
        target = raw_root / relative
        if not target.exists():
            raise EvidenceError(f"--raw-root target does not exist: {target}")
        record["raw"] = str(target)


def idempotency_key(day: str, task: str, record: dict) -> str:
    identity = re.sub(r"[^A-Za-z0-9]+", "-", record["identity"]).strip("-")
    return f"{day}-{task}-{record['name']}-{record['sha'][:8]}-{identity}"


def build_command(task: str, record: dict, db: str, day: str) -> list[str]:
    return ["verify", "add", "--task", task, "--key", record["name"], "--criteria", record["criteria"],
            "--result", record["result"], "--required", "--command", record["command"],
            "--repo-sha", record["sha"], "--raw", record["raw"], "--duration", record["duration"],
            "--retry-count", str(record["retry"]), "--summary", record["summary"],
            "--idempotency-key", idempotency_key(day, task, record), "--json", "--db", db]


def format_table(records: list[dict], task: str, day: str) -> str:
    rows = [("CHECK", "RESULT", "COMMIT", "DURATION", "RAW", "IDEMPOTENCY KEY")]
    for record in records:
        rows.append((record["name"], record["result"], record["sha"][:8], record["duration"],
                     record["raw"], idempotency_key(day, task, record)))
    widths = [max(len(row[index]) for row in rows) for index in range(len(rows[0]))]
    return "\n".join("  ".join(value.ljust(widths[index]) for index, value in enumerate(row)) for row in rows)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Record gate results in SHPM")
    parser.add_argument("--task", required=True)
    parser.add_argument("results", nargs="+", type=Path)
    parser.add_argument("--db", default=DEFAULT_DB)
    parser.add_argument("--raw-root", type=Path)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args(argv)
    try:
        records, unrecognized = discover(args.results)
        assign_rounds(records)
        if args.raw_root:
            apply_raw_root(records, args.raw_root.resolve())
    except EvidenceError as error:
        print(str(error), file=sys.stderr)
        return 1

    day = datetime.date.today().isoformat()
    print(format_table(records, args.task, day))
    if unrecognized:
        print("unrecognized JSON (ignored):")
        for path in unrecognized:
            print(f"  {path}")

    commands = [build_command(args.task, record, args.db, day) for record in records]
    env = dict(os.environ, SHPM_DB=args.db, PYTHONIOENCODING="utf-8")
    for command in commands:
        display = "shpm " + " ".join(f"'{part}'" if " " in part else part for part in command)
        if not args.execute:
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

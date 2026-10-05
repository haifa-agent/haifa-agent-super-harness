"""Runs every script regression check under an explicit manifest.

``tests/checks/build`` and ``tests/checks/ui`` hold the script-level regression checks.
This module owns the list of checks, runs each one independently, keeps every check's
output in its own log file and writes one JSON summary. One failing check fails the whole
run, but the remaining checks still run; a check file that exists in a directory but is not
in the manifest fails the run before any check starts. There is deliberately no switch to
skip an individual check.

    python scripts/lib/script_regressions.py [--repo <worktree root>]

The summary is written to ``local-tmp/verify-scripts/verify-scripts-<timestamp>.json`` with
``status`` (PASS / FAIL / FAIL_MANIFEST), ``sourceSha``, the per-check status and duration,
``elapsedMs`` and the log directory. ``tests/checks/evidence/verify-evidence-report.ps1`` is
not part of this set.
"""

from __future__ import annotations

import argparse
import datetime
import json
import subprocess
import sys
import time
from pathlib import Path

BUILD_DIR = "tests/checks/build"
UI_DIR = "tests/checks/ui"
RECOGNIZED_SUFFIXES = (".ps1", ".py", ".cjs")

# Every script regression check, in run order. Adding a check file to one of the two
# directories without adding it here makes the entry point fail on purpose.
CHECKS = (
    {"name": "verify-haifa-version-baseline", "kind": "pwsh",
     "path": "tests/checks/build/verify-haifa-version-baseline.ps1"},
    {"name": "verify-build-entry", "kind": "pwsh",
     "path": "tests/checks/build/verify-build-entry.ps1"},
    {"name": "verify-smoke-preflight", "kind": "pwsh", "evidenceDir": True,
     "path": "tests/checks/build/verify-smoke-preflight.ps1"},
    {"name": "verify-run-fixture", "kind": "pwsh", "evidenceDir": True,
     "path": "tests/checks/build/verify-run-fixture.ps1"},
    {"name": "python-pipeline-tools", "kind": "python",
     "path": "tests/checks/build/test_pipeline_tools.py"},
    {"name": "python-w08-oracle12", "kind": "python",
     "path": "tests/checks/build/test_w08_oracle12.py"},
    {"name": "node-evaluation-report", "kind": "node",
     "path": "tests/checks/ui/evaluation-report.test.cjs"},
    {"name": "node-pending-write", "kind": "node",
     "path": "tests/checks/ui/pending-write.test.cjs"},
    {"name": "node-skill-read-order", "kind": "node",
     "path": "tests/checks/ui/skill-read-order.test.cjs"},
)


def check_directory_files(root: Path) -> set[str]:
    """Every recognised check file under the two directories, as posix relative paths."""
    found: set[str] = set()
    for relative in (BUILD_DIR, UI_DIR):
        directory = root / relative
        if not directory.is_dir():
            continue
        for path in directory.iterdir():
            if path.is_file() and path.suffix.lower() in RECOGNIZED_SUFFIXES:
                found.add(path.relative_to(root).as_posix())
    return found


def manifest_errors(root: Path) -> list[str]:
    """Differences between the manifest and the files actually present."""
    expected = {check["path"] for check in CHECKS}
    found = check_directory_files(root)
    errors = [f"manifest lists '{path}', which does not exist" for path in sorted(expected - found)]
    errors += [f"check file '{path}' is not listed in the manifest" for path in sorted(found - expected)]
    duplicates = len(CHECKS) - len({check["name"] for check in CHECKS})
    if duplicates:
        errors.append("manifest contains duplicate check names")
    return errors


def build_command(check: dict, root: Path, run_dir: Path) -> list[str]:
    path = root / check["path"]
    if check["kind"] == "pwsh":
        command = ["pwsh", "-NoLogo", "-NoProfile", "-File", str(path)]
    elif check["kind"] == "python":
        command = [sys.executable, "-B", "-m", "unittest", check["path"]]
    elif check["kind"] == "node":
        command = ["node", str(path)]
    else:
        raise ValueError(f"unsupported check kind '{check['kind']}'")
    if check.get("evidenceDir"):
        command += ["-EvidenceDirectory", str(run_dir / check["name"] / "evidence")]
    return command


def run_check(check: dict, root: Path, run_dir: Path, runner=subprocess.run) -> dict:
    command = build_command(check, root, run_dir)
    log_path = run_dir / f"{check['name']}.log"
    started = time.monotonic()
    try:
        result = runner(
            command, cwd=str(root), capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=check.get("timeoutSeconds", 900),
        )
        exit_code = result.returncode
        output = (result.stdout or "") + (result.stderr or "")
    except subprocess.TimeoutExpired as error:
        exit_code = 124
        output = (error.stdout or "") + (error.stderr or "") if isinstance(error.stdout, str) else ""
        output += f"\nTIMEOUT after {check.get('timeoutSeconds', 900)}s\n"
    elapsed_ms = int((time.monotonic() - started) * 1000)
    log_path.write_text(output, encoding="utf-8")
    return {
        "name": check["name"],
        "path": check["path"],
        "status": "PASS" if exit_code == 0 else "FAIL",
        "exitCode": exit_code,
        "elapsedMs": elapsed_ms,
        "command": command,
        "log": str(log_path),
    }


def aggregate_status(checks: list[dict], errors: list[str]) -> str:
    if errors:
        return "FAIL_MANIFEST"
    return "PASS" if checks and all(check["status"] == "PASS" for check in checks) else "FAIL"


def source_sha(root: Path) -> str:
    result = subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"],
                            capture_output=True, text=True, encoding="utf-8", check=True)
    return result.stdout.strip()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run the script regression checks")
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args(argv)
    root = args.repo.resolve()

    stem = f"verify-scripts-{datetime.datetime.now():%Y%m%d-%H%M%S-%f}"
    output_root = root / "local-tmp/verify-scripts"
    run_dir = output_root / stem
    run_dir.mkdir(parents=True, exist_ok=True)
    summary_path = run_dir.with_suffix(".json")

    errors = manifest_errors(root)
    checks: list[dict] = []
    if not errors:
        for check in CHECKS:
            checks.append(run_check(check, root, run_dir))
    status = aggregate_status(checks, errors)
    summary = {
        "status": status,
        "sourceSha": source_sha(root),
        "manifestErrors": errors,
        "checks": checks,
        "elapsedMs": sum(check["elapsedMs"] for check in checks),
        "logDirectory": str(run_dir),
        "summaryPath": str(summary_path),
    }
    summary_path.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({key: summary[key] for key in ("status", "sourceSha", "manifestErrors", "summaryPath")},
                     ensure_ascii=False))
    return 0 if status == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())

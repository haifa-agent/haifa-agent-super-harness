"""Decides whether a failed full gate only hit known-intermittent tests.

All decision logic lives here (AGENTS.md, verified knowledge); ``verify-full.ps1`` only runs
the commands the plan lists. The inputs are the surefire reports of the failed run, the
manifest ``tests/known-intermittent.json``, the files changed since the merge base, and the
reactor log excerpt that names the skipped modules.

The plan uses the intermittent branch only when every condition holds:

1. at most two failed/errored tests, and every one is in the manifest;
2. the change since the merge base touches none of the matched entries' ``covers``;
3. every *other* test class of a failed module has a surefire report (the module really ran);
4. ``-NoKnownIntermittent`` was not given.

    python scripts/lib/known_intermittent.py --repo <root> --log <maven log>
        [--manifest tests/known-intermittent.json] [--base main] [--no-known-intermittent]

The JSON plan always carries ``failedTests`` so the caller can record them even when the branch
is not taken. ``use=true`` additionally carries the matched entries, the classes to rerun, the
rerun count and the reactor-skipped modules the caller must still build.
"""

from __future__ import annotations

import argparse
import fnmatch
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

DEFAULT_MANIFEST = "tests/known-intermittent.json"
DEFAULT_RERUNS = 3
REACTOR_LINE = re.compile(r"^\[INFO\]\s+(.+?)\s+\.{2,}\s+(\S+)")


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def git(repo: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(repo), *args], capture_output=True, text=True, encoding="utf-8", errors="replace"
    )
    if result.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def load_manifest(path: Path) -> list[dict]:
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    entries = data.get("entries") if isinstance(data, dict) else data
    if not isinstance(entries, list):
        raise ValueError(f"'{path}' must be a list or contain an 'entries' list")
    for entry in entries:
        for key in ("test", "module", "issue"):
            if not entry.get(key):
                raise ValueError(f"'{path}' entry is missing '{key}'")
    return entries


def parse_surefire_xml(path: Path, module: str | None = None) -> dict:
    root = ET.parse(path).getroot()
    name = root.get("name") or path.stem
    failed: list[dict] = []
    for case in root:
        if local_name(case.tag) != "testcase":
            continue
        problems = [child for child in case if local_name(child.tag) in ("failure", "error")]
        if not problems:
            continue
        node = problems[0]
        failed.append({
            "class": case.get("classname") or name,
            "method": case.get("name") or "",
            "kind": local_name(node.tag),
            "type": node.get("type") or "",
            "message": (node.get("message") or "").strip(),
        })
    return {
        "module": module,
        "name": name,
        "tests": int(root.get("tests") or 0),
        "failures": int(root.get("failures") or 0),
        "errors": int(root.get("errors") or 0),
        "skipped": int(root.get("skipped") or 0),
        "failed": failed,
        "path": str(path),
    }


def parse_surefire_dir(directory: Path, module: str | None = None) -> list[dict]:
    return [parse_surefire_xml(path, module or directory.name)
            for path in sorted(directory.glob("TEST-*.xml"))]


def collect_reports(repo: Path) -> list[dict]:
    reports: list[dict] = []
    for module_dir in sorted(repo.glob("super-harness-*")):
        reports_dir = module_dir / "target/surefire-reports"
        if reports_dir.is_dir():
            reports.extend(parse_surefire_dir(reports_dir, module_dir.name))
    return reports


def failed_tests(reports: list[dict]) -> list[dict]:
    rows: list[dict] = []
    for report in reports:
        for problem in report["failed"]:
            rows.append({
                "class": problem["class"],
                "test": problem["class"].rsplit(".", 1)[-1],
                "method": problem["method"],
                "module": report["module"],
                "kind": problem["kind"],
                "type": problem["type"],
                "message": problem["message"],
            })
    return rows


def matches_entry(failed: dict, entry: dict) -> bool:
    spec = entry.get("test", "")
    class_name, separator, method = spec.partition("#")
    if not (failed["class"] == class_name or failed["class"].endswith("." + class_name)
            or failed["test"] == class_name):
        return False
    if separator and failed["method"] != method:
        return False
    return True


def path_matches_cover(path: str, pattern: str) -> bool:
    normalized = path.replace("\\", "/")
    if any(character in pattern for character in "*?["):
        return fnmatch.fnmatch(normalized, pattern.replace("\\", "/"))
    stripped = pattern.replace("\\", "/").rstrip("/")
    return normalized == stripped or normalized.startswith(stripped + "/")


def covers_touched(changed_files: list[str], entries: list[dict]):
    for entry in entries:
        for pattern in entry.get("covers", []):
            for changed in changed_files:
                if path_matches_cover(changed, pattern):
                    return True, changed, entry
    return False, None, None


def expected_test_classes(module_dir: Path) -> set[str]:
    """Surefire's default includes: Test*, *Test, *Tests, *TestCase."""
    names: set[str] = set()
    test_root = module_dir / "src/test/java"
    if not test_root.is_dir():
        return names
    for path in test_root.rglob("*.java"):
        stem = path.stem
        if stem.startswith("Test") or stem.endswith(("Test", "Tests", "TestCase")):
            names.add(stem)
    return names


def module_report_gaps(module_dir: Path, reports: list[dict]) -> list[str]:
    reported = {report["name"].rsplit(".", 1)[-1] for report in reports}
    return sorted(name for name in expected_test_classes(module_dir) if name not in reported)


def reactor_skipped_modules(log_text: str, name_to_module: dict[str, str]) -> list[str]:
    skipped: list[str] = []
    for line in log_text.splitlines():
        match = REACTOR_LINE.match(line)
        if match and match.group(2) == "SKIPPED":
            module = name_to_module.get(match.group(1).strip())
            if module and module not in skipped:
                skipped.append(module)
    return skipped


def module_names(repo: Path) -> dict[str, str]:
    mapping: dict[str, str] = {}
    for pom in sorted(repo.glob("super-harness-*/pom.xml")):
        try:
            root = ET.parse(pom).getroot()
        except ET.ParseError:
            continue
        for child in root:
            if local_name(child.tag) == "name" and (child.text or "").strip():
                mapping[child.text.strip()] = pom.parent.name
                break
    return mapping


def changed_files(repo: Path, base: str) -> list[str]:
    try:
        merge_base = git(repo, "merge-base", "HEAD", base).strip()
        tracked = git(repo, "diff", "--name-only", merge_base).splitlines()
    except RuntimeError:
        tracked = []
    untracked = git(repo, "ls-files", "--others", "--exclude-standard").splitlines()
    return sorted({line for line in tracked + untracked if line})


def _deny(reason: str, failed: list[dict]) -> dict:
    return {"use": False, "reason": reason, "failedTests": failed, "matched": [],
            "rerunClasses": [], "rerunModules": [], "reruns": 0, "skippedModules": []}


def intermittent_plan(failed: list[dict], manifest: list[dict], changed: list[str],
                      module_gaps: dict[str, list[str]], skipped: list[str] | None = None,
                      no_known_intermittent: bool = False, reruns: int = DEFAULT_RERUNS) -> dict:
    if no_known_intermittent:
        return _deny("-NoKnownIntermittent was given", failed)
    if not failed:
        return _deny("no failed test was read from the surefire reports", failed)
    if len(failed) > 2:
        return _deny(f"more than two failed tests ({len(failed)})", failed)
    matched: list[dict] = []
    for problem in failed:
        entry = next((candidate for candidate in manifest if matches_entry(problem, candidate)), None)
        if entry is None:
            return _deny(f"{problem['test']} is not in the manifest", failed)
        matched.append(entry)
    touched, path, entry = covers_touched(changed, matched)
    if touched:
        return _deny(f"changed file '{path}' is covered by {entry['issue']}", failed)
    gaps = {module: missing for module, missing in module_gaps.items() if missing}
    if gaps:
        detail = "; ".join(f"{module}: {', '.join(missing)}" for module, missing in gaps.items())
        return _deny(f"failed module has test classes with no surefire report: {detail}", failed)
    return {
        "use": True,
        "reason": "every failed test is a registered intermittent one",
        "failedTests": failed,
        "matched": [{"test": entry["test"], "issue": entry["issue"], "module": entry["module"],
                     "covers": entry.get("covers", [])} for entry in matched],
        "rerunClasses": sorted({problem["class"] for problem in failed}),
        "rerunModules": sorted({problem["module"] for problem in failed}),
        "reruns": reruns,
        "skippedModules": list(skipped or []),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Plan the known-intermittent branch of the full gate")
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--manifest", default=DEFAULT_MANIFEST)
    parser.add_argument("--base", default="main")
    parser.add_argument("--no-known-intermittent", action="store_true")
    args = parser.parse_args(argv)
    repo = args.repo.resolve()
    reports = collect_reports(repo)
    failed = failed_tests(reports)
    manifest_path = repo / args.manifest
    manifest = load_manifest(manifest_path) if manifest_path.is_file() else []
    log_text = args.log.read_text(encoding="utf-8", errors="replace") if args.log.is_file() else ""
    skipped = reactor_skipped_modules(log_text, module_names(repo))
    module_gaps = {}
    for module in {problem["module"] for problem in failed}:
        module_reports = [report for report in reports if report["module"] == module]
        module_gaps[module] = module_report_gaps(repo / module, module_reports)
    plan = intermittent_plan(failed, manifest, changed_files(repo, args.base), module_gaps,
                             skipped, args.no_known_intermittent)
    print(json.dumps(plan, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())

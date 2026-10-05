"""Tells which gates a change needs, from the files it touches since main.

Classifies every changed or untracked file and prints the required gates as JSON
(AGENTS.md, change-scoped gates). The widest class wins:

  docs       *.md, docs/                               -> docs check only
  scripts    scripts/, tests/checks/, build-support/, .mvn/, mvnw*, tests/known-intermittent.json
                                                       -> script regressions, plus one warm UI
                                                          round when scripts/lib/ or scripts/ui/
                                                          changed
  java-test  super-harness-*/src/test/                 -> verify-full-reuse when the Java input
                                                          fingerprint is unchanged, else
                                                          verify-full
  ui         tests/ui/, scripts/ui/, scripts/frontend/ -> two UI rounds
  frontend   frontend/                                 -> a cold UI round, then warm rounds
  product    super-harness-*/src/main/, pom.xml files, resources/, contracts/, third-party/,
             anything unrecognised                     -> verify-full and two UI rounds

Rule B uses the Java input fingerprint, not the commit: a change whose files are all in the
fingerprint exclusion list (docs, scripts, tests, root *.md) keeps the last full-gate result
reusable and gets ``verify-full-reuse``, which needs a real run only when no valid PASS record
with that fingerprint exists.

The output is advice for executor and controller. It runs nothing, and a reviewer may always
ask for more.

    python scripts/governance/change_scope.py [--repo <root>] [--base main]
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))

import source_fingerprint  # noqa: E402  (path added above)

RULES = (
    ("frontend", re.compile(r"^frontend/")),
    ("ui", re.compile(r"^(tests/ui|scripts/ui|scripts/frontend)/")),
    ("java-test", re.compile(r"^super-harness-[^/]+/src/test/")),
    ("scripts", re.compile(r"^(scripts|tests/checks|build-support|\.mvn)/|^mvnw(\.cmd)?$|^tests/known-intermittent\.json$")),
    ("docs", re.compile(r"(^docs/|\.md$)")),
)


def classify(path: str) -> str:
    normalized = path.replace("\\", "/")
    for name, pattern in RULES:
        if pattern.search(normalized):
            return name
    return "product"


def java_inputs_changed(files: list[str]) -> bool:
    """Whether any changed file is outside the fingerprint's exclusion list."""
    return any(not source_fingerprint.is_excluded(path) for path in files)


def required_gates(files: list[str]) -> list[str]:
    classes = {classify(path) for path in files}
    shared_scripts = any(re.match(r"^scripts/(lib|ui)/", path.replace("\\", "/")) for path in files)
    gates: list[str] = []
    if not classes or "docs" in classes:
        gates.append("docs-check")
    if "scripts" in classes:
        gates.append("script-regressions")
    if java_inputs_changed(files):
        gates.append("verify-full")
    else:
        gates.append("verify-full-reuse")
    if "frontend" in classes:
        gates.append("ui-cold-round")
    if classes & {"product", "ui", "frontend"}:
        gates.append("ui-warm-round-x2")
    elif "scripts" in classes and shared_scripts:
        gates.append("ui-warm-round-x1")
    return gates


def git(repo: Path, *args: str) -> str:
    return subprocess.run(
        ["git", "-C", str(repo), *args], capture_output=True, text=True, encoding="utf-8", check=True
    ).stdout


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Change-scoped gate advice")
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--base", default="main")
    args = parser.parse_args(argv)
    repo = args.repo.resolve()
    merge_base = git(repo, "merge-base", "HEAD", args.base).strip()
    files = sorted(
        {
            line
            for line in (
                git(repo, "diff", "--name-only", merge_base).splitlines()
                + git(repo, "ls-files", "--others", "--exclude-standard").splitlines()
            )
            if line
        }
    )
    fingerprint = None
    try:
        fingerprint = source_fingerprint.fingerprint(repo, "HEAD")
    except (source_fingerprint.FingerprintError, OSError):
        fingerprint = None
    print(
        json.dumps(
            {
                "base": args.base,
                "mergeBase": merge_base,
                "head": git(repo, "rev-parse", "HEAD").strip(),
                "sourceTree": fingerprint["sourceTree"] if fingerprint else None,
                "javaInputFingerprint": fingerprint["javaInputFingerprint"] if fingerprint else None,
                "changedFiles": len(files),
                "byClass": dict(sorted(Counter(classify(path) for path in files).items())),
                "gates": required_gates(files),
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())

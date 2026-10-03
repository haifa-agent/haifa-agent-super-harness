"""Tells which gates a change needs, from the files it touches since main.

Classifies every changed or untracked file and prints the required gates as JSON
(AGENTS.md, change-scoped gates). The widest class wins:

  docs       *.md, docs/                               -> docs check only
  scripts    scripts/, tests/checks/, build-support/, .mvn/, mvnw*
                                                       -> script regressions, plus one warm UI
                                                          round when scripts/lib/ or scripts/ui/
                                                          changed
  java-test  super-harness-*/src/test/                 -> verify-full
  ui         tests/ui/, scripts/ui/, scripts/frontend/ -> two warm UI rounds
  frontend   frontend/                                 -> a cold UI round, then warm rounds
  product    super-harness-*/src/main/, pom.xml files, resources/, contracts/, third-party/,
             anything unrecognised                     -> verify-full and two warm UI rounds

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

RULES = (
    ("frontend", re.compile(r"^frontend/")),
    ("ui", re.compile(r"^(tests/ui|scripts/ui|scripts/frontend)/")),
    ("java-test", re.compile(r"^super-harness-[^/]+/src/test/")),
    ("scripts", re.compile(r"^(scripts|tests/checks|build-support|\.mvn)/|^mvnw(\.cmd)?$")),
    ("docs", re.compile(r"(^docs/|\.md$)")),
)


def classify(path: str) -> str:
    normalized = path.replace("\\", "/")
    for name, pattern in RULES:
        if pattern.search(normalized):
            return name
    return "product"


def required_gates(files: list[str]) -> list[str]:
    classes = {classify(path) for path in files}
    shared_scripts = any(re.match(r"^scripts/(lib|ui)/", path.replace("\\", "/")) for path in files)
    gates: list[str] = []
    if not classes or "docs" in classes:
        gates.append("docs-check")
    if "scripts" in classes:
        gates.append("script-regressions")
    if classes & {"java-test", "product"}:
        gates.append("verify-full")
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
    print(
        json.dumps(
            {
                "base": args.base,
                "mergeBase": merge_base,
                "head": git(repo, "rev-parse", "HEAD").strip(),
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

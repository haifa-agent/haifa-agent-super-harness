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

Rule D names every UI round ``ui-<model>-round-<n>``: the model is ``live`` unless every changed
path is inside ``UI_CONTROLLED_PATTERNS``, and ``ui-live`` is forced by any pom.xml, a
``tests/ui``/``scripts/ui`` spec or runner whose name contains ``live``, server
run/prompt/tool/config, main resources, other module src/main/, an SDK identity change since
the merge base, or a path the table does not know.

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

# Docs first: any *.md is documentation even under scripts/ or tests/.
RULES = (
    ("docs", re.compile(r"(^docs/|\.md$)")),
    ("frontend", re.compile(r"^frontend/")),
    ("ui", re.compile(r"^(tests/ui|scripts/ui|scripts/frontend)/")),
    ("java-test", re.compile(r"^super-harness-[^/]+/src/test/")),
    ("scripts", re.compile(r"^(scripts|tests/checks|build-support|\.mvn)/|^mvnw(\.cmd)?$|^tests/known-intermittent\.json$")),
)

# Rule D: which model a formal UI round must use. Default is the real model; a change stays
# controlled only when every changed path is inside the controlled table. Each entry is data,
# not logic, so the controller can review and adjust the boundary in one place. A path that
# matches no controlled entry (including an unknown/new directory) forces the real model.
SERVER_API_PREFIX = "super-harness-server/src/main/java/io/haifa/agent/superharness/server/api/"
UI_CONTROLLED_PATTERNS = (
    ("docs/ or any *.md", re.compile(r"(^docs/|\.md$)")),
    ("scripts/", re.compile(r"^scripts/")),
    ("tests/checks/", re.compile(r"^tests/checks/")),
    ("module src/test/", re.compile(r"^super-harness-[^/]+/src/test/")),
    ("tests/ui/ spec or support file without 'live' in the name", re.compile(r"^tests/ui/")),
)
UI_LIVE_PATTERNS = (
    ("a pom.xml", re.compile(r"(^|/)pom\.xml$")),
    ("server run/prompt/tool/config",
     re.compile(r"^super-harness-server/src/main/java/io/haifa/agent/superharness/server/(run|prompt|tool|config)/")),
    ("main resources", re.compile(r"^(super-harness-[^/]+/)?src/main/resources/")),
    ("other module src/main/", re.compile(r"^super-harness-[^/]+/src/main/")),
)
LIVE_FILE_NAME = re.compile(r"live", re.IGNORECASE)


def classify(path: str) -> str:
    normalized = path.replace("\\", "/")
    for name, pattern in RULES:
        if pattern.search(normalized):
            return name
    return "product"


def classify_ui_path(path: str) -> tuple[str, str | None]:
    """One changed path's model requirement and, when live, the matched reason."""
    normalized = path.replace("\\", "/")
    basename = normalized.rsplit("/", 1)[-1]
    if normalized.startswith(("tests/ui/", "scripts/ui/", "scripts/frontend/")) and LIVE_FILE_NAME.search(basename):
        return "live", f"{path}: spec/runner 文件名含 live"
    if normalized.startswith(SERVER_API_PREFIX):
        return "controlled", None
    for reason, pattern in UI_LIVE_PATTERNS:
        if pattern.search(normalized):
            return "live", f"{path}: {reason}"
    for reason, pattern in UI_CONTROLLED_PATTERNS:
        if pattern.search(normalized):
            return "controlled", None
    return "live", f"{path}: 表里没有的路径"


def ui_model_decision(files: list[str], sdk_identity_changed: bool = False) -> tuple[str, str]:
    """Whether a formal UI round may use the controlled model, and why."""
    if sdk_identity_changed:
        return "live", "SDK 身份相对合并基点发生变化"
    if not files:
        return "live", "无可判定的改动"
    for path in files:
        model, reason = classify_ui_path(path)
        if model == "live":
            return "live", reason or path
    return "controlled", "全部改动都落在受控范围内"


def java_inputs_changed(files: list[str]) -> bool:
    """Whether any changed file is outside the fingerprint's exclusion list."""
    return any(not source_fingerprint.is_excluded(path) for path in files)


def required_gates(files: list[str], sdk_identity_changed: bool = False) -> list[str]:
    classes = {classify(path) for path in files}
    shared_scripts = any(re.match(r"^scripts/(lib|ui)/", path.replace("\\", "/")) for path in files)
    model, _ = ui_model_decision(files, sdk_identity_changed)
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
        gates.append(f"ui-{model}-round-x2")
    elif "scripts" in classes and shared_scripts:
        gates.append(f"ui-{model}-round-x1")
    return gates


def git(repo: Path, *args: str) -> str:
    return subprocess.run(
        ["git", "-C", str(repo), *args], capture_output=True, text=True, encoding="utf-8", check=True
    ).stdout


def haifa_version_at(repo: Path, commit: str) -> str | None:
    try:
        pom = git(repo, "show", f"{commit}:pom.xml")
    except subprocess.CalledProcessError:
        return None
    match = re.search(r"<haifa\.version>([^<]+)</haifa\.version>", pom)
    return match.group(1).strip() if match else None


def sdk_identity_changed(repo: Path, merge_base: str) -> bool:
    """Proxy for "the SDK identity changed since the merge base": the pinned version differs.

    The actual JAR identity is content-based and lives in source_fingerprint; a republish under
    the same version is caught there, not here. A version change always changes the identity, so
    treating it as live is correct and conservative.
    """
    before = haifa_version_at(repo, merge_base)
    after = haifa_version_at(repo, git(repo, "rev-parse", "HEAD").strip())
    return before is not None and after is not None and before != after


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
    sdk_changed = sdk_identity_changed(repo, merge_base)
    model, model_reason = ui_model_decision(files, sdk_changed)
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
                "uiModel": model,
                "uiModelReason": model_reason,
                "gates": required_gates(files, sdk_changed),
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())

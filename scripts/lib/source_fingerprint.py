"""Content fingerprints of a commit, so evidence survives squash merges and doc-only commits.

Three values are produced for any commit (not only the working tree):

- ``sourceTree``  ``git rev-parse <commit>^{tree}``, the content identity of the whole tree;
- ``sdkIdentity`` the SHA-256 of the actual Haifa JARs the worktree consumes, so re-publishing
  an SDK under the same version changes it;
- ``javaInputFingerprint`` the sorted, exclusion-filtered ``git ls-tree -r`` entries combined
  with the SDK identity, the JDK version and the Maven version. This is what a Java build
  actually depends on, so a commit that only touches docs, scripts, tests or a root ``*.md``
  has the same fingerprint and can reuse a previous full-gate run.

The exclusion list is ``docs/``, ``tests/``, ``scripts/`` and root-level ``*.md``. It is an
exclusion list on purpose: a newly added directory counts by default. ``excluded_references``
is the regression guard: the moment a POM or a Java test reads one of the excluded directories,
the fingerprint stops being a safe reuse key.

    python scripts/lib/source_fingerprint.py --repo <worktree> [--commit <sha>]
        [--maven-repo <repo>] [--version <v>]

The shared JAR locating and hashing lives in ``scripts/governance/sdk_api_digest.py``; this
module loads it by path instead of copying the logic. The working tree does not produce a
fingerprint while it has uncommitted changes on a covered path (``covered_dirty_paths``).
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

EXCLUDED_PREFIXES = ("docs/", "tests/", "scripts/")
SDK_UNAVAILABLE = "UNAVAILABLE"
BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"(?<!:)//[^\n]*")
EXCLUDED_REFERENCE = re.compile(r"(?<![A-Za-z0-9_./-])((?:docs|scripts|tests)/[A-Za-z0-9_./-]*)")


class FingerprintError(RuntimeError):
    """Raised when a fingerprint cannot be computed reliably."""


def load_sdk_api_digest():
    path = Path(__file__).resolve().parents[1] / "governance" / "sdk_api_digest.py"
    spec = importlib.util.spec_from_file_location("sdk_api_digest", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def git(repo: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(repo), *args], capture_output=True, text=True, encoding="utf-8", errors="replace"
    )
    if result.returncode != 0:
        raise FingerprintError(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def source_tree(repo: Path, commit: str) -> str:
    return git(repo, "rev-parse", f"{commit}^{{tree}}").strip()


def tree_entries(repo: Path, commit: str) -> list[tuple[str, str, str]]:
    """Every ``(mode, blob, path)`` in the commit, recursively."""
    entries: list[tuple[str, str, str]] = []
    for line in git(repo, "ls-tree", "-r", "--full-tree", commit).splitlines():
        meta, tab, path = line.partition("\t")
        if not tab:
            continue
        fields = meta.split()
        if len(fields) != 3:
            continue
        entries.append((fields[0], fields[2], path))
    return entries


def is_excluded(path: str) -> bool:
    normalized = path.replace("\\", "/")
    if normalized.startswith(EXCLUDED_PREFIXES):
        return True
    return "/" not in normalized and normalized.lower().endswith(".md")


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def java_tree_digest(repo: Path, commit: str) -> str:
    entries = sorted(entry for entry in tree_entries(repo, commit) if not is_excluded(entry[2]))
    payload = "\n".join(f"{mode} {blob} {path}" for mode, blob, path in entries)
    return sha256_text(payload)


def jdk_version() -> str:
    try:
        result = subprocess.run(["java", "-version"], capture_output=True, text=True, encoding="utf-8", errors="replace")
    except OSError:
        return "unknown"
    match = re.search(r'version "([^"]+)"', result.stderr or result.stdout or "")
    return match.group(1) if match else "unknown"


def maven_version() -> str:
    executable = shutil.which("mvn") or shutil.which("mvn.cmd")
    if not executable:
        return "unknown"
    try:
        result = subprocess.run([executable, "-version"], capture_output=True, text=True, encoding="utf-8", errors="replace")
    except OSError:
        return "unknown"
    match = re.search(r"Apache Maven (\S+)", (result.stdout or "") + (result.stderr or ""))
    return match.group(1) if match else "unknown"


def fingerprint(repo: Path, commit: str, maven_repo: Path | None = None, version: str | None = None,
                jdk: str | None = None, maven: str | None = None) -> dict:
    repo = repo.resolve()
    sdk_api_digest = load_sdk_api_digest()
    if maven_repo is None:
        maven_repo = sdk_api_digest.default_maven_repo().resolve()
    else:
        maven_repo = maven_repo.resolve()
    sdk = sdk_api_digest.sdk_identity(repo, maven_repo, version)
    jdk = jdk or jdk_version()
    maven = maven or maven_version()
    digest = java_tree_digest(repo, commit)
    combined = "\n".join([digest, sdk["identity"], sdk["version"], jdk, maven])
    tree = source_tree(repo, commit)
    resolved = git(repo, "rev-parse", commit).strip()
    # Dirty paths only matter when the fingerprint describes the checked-out commit: the
    # files on disk are then not the files the fingerprint was computed from.
    dirty = covered_dirty_paths(repo) if resolved == git(repo, "rev-parse", "HEAD").strip() else []
    sdk_known = sdk["identity"] != SDK_UNAVAILABLE
    return {
        "commit": resolved,
        "sourceTree": tree,
        "sdkVersion": sdk["version"],
        "sdkIdentity": sdk["identity"],
        "jdkVersion": jdk,
        "mavenVersion": maven,
        "javaInputFingerprint": sha256_text(combined),
        "dirtyPaths": dirty,
        # Evidence may be reused by fingerprint only when the SDK content is known and the
        # working tree has no uncommitted change on a covered path.
        "reusable": sdk_known and not dirty,
        "notReusableReason": (None if sdk_known and not dirty else
                              ("SDK identity unavailable" if not sdk_known else
                               "uncommitted changes on covered paths")),
    }


def covered_dirty_paths(repo: Path) -> list[str]:
    """Working-tree paths with uncommitted changes that the fingerprint covers."""
    dirty: list[str] = []
    for line in git(repo, "status", "--porcelain").splitlines():
        if len(line) < 4:
            continue
        path = line[3:].strip().strip('"')
        if " -> " in path:
            path = path.split(" -> ", 1)[1].strip().strip('"')
        if path and not is_excluded(path):
            dirty.append(path)
    return dirty


def excluded_references(repo: Path) -> list[str]:
    """References to the excluded directories from a Java test or a POM.

    A hit means the exclusion list is no longer safe: a change under that directory could
    alter the build without changing the fingerprint. Only real, repo-relative paths count:
    comments are removed first, path fragments preceded by another separator are ignored, and
    a candidate must actually exist in the repo, so a skill bundle's own internal
    ``scripts/run.sh`` string is not a false positive. The format is ``<path>: <match>``.
    """
    findings: list[str] = []
    # Tracked files only: walking the working tree would descend into frontend/node_modules
    # (74 s on a prepared worktree) and could pick up files that are not part of the build.
    try:
        tracked = git(repo, "ls-files", "--", "pom.xml", "*/pom.xml", "super-harness-*/src/test/*.java").splitlines()
        candidates = [repo / name for name in sorted(tracked) if name]
    except FingerprintError:
        # Not a git checkout (a test fixture): look only where the build files can be.
        candidates = sorted(repo.glob("pom.xml")) + sorted(repo.glob("*/pom.xml"))
        candidates += sorted(repo.glob("super-harness-*/src/test/**/*.java"))
    for path in candidates:
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        stripped = LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", text))
        for match in EXCLUDED_REFERENCE.finditer(stripped):
            candidate = match.group(1)
            if (repo / candidate).exists():
                findings.append(f"{path.relative_to(repo).as_posix()}: {candidate}")
    return findings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Content fingerprint of a commit")
    parser.add_argument("--repo", required=True, type=Path)
    parser.add_argument("--commit", default="HEAD")
    parser.add_argument("--maven-repo", type=Path, default=None)
    parser.add_argument("--version")
    args = parser.parse_args(argv)
    try:
        result = fingerprint(args.repo, args.commit, args.maven_repo, args.version)
    except (FingerprintError, OSError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())

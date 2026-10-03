"""Cache key for a passed frozen-frontend source gate.

scripts/frontend/verify-deerflow-frontend.ps1 hashes every frozen frontend file (and the pinned
DeerFlow checkout) on each UI round, which takes over half a minute although the frozen frontend
never changes. The key covers:

- HEAD:frontend and HEAD:third-party/deerflow tree ids and the verifier script's bytes;
- path, length and modification time of every tracked frontend file, so bytes that changed on
  disk without git noticing (for example a line-ending re-checkout) produce a new key;
- the DeerFlow source checkout's location and HEAD.

No key is produced, so the full gate runs, while frontend/, third-party/deerflow/ or the source
checkout's frontend has uncommitted or untracked changes.

    python scripts/lib/frontend_gate_cache.py --repo <root> [--source <deer-flow checkout>]

Prints the key, or nothing when the inputs are not cacheable.
"""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
from pathlib import Path


def git(repo: Path, *args: str) -> str:
    return subprocess.run(
        ["git", "-C", str(repo), *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=True,
    ).stdout


def cache_key(repo: Path, source: Path | None) -> str | None:
    if git(repo, "status", "--porcelain", "--untracked-files=all", "--", "frontend", "third-party/deerflow").strip():
        return None
    parts = [
        "frontend-source-gate-v1",
        git(repo, "rev-parse", "HEAD:frontend").strip(),
        git(repo, "rev-parse", "HEAD:third-party/deerflow").strip(),
        hashlib.sha256((repo / "scripts" / "frontend" / "verify-deerflow-frontend.ps1").read_bytes()).hexdigest(),
    ]
    fingerprint = hashlib.sha256()
    for relative in git(repo, "ls-files", "-z", "--", "frontend").split("\0"):
        if not relative:
            continue
        try:
            stat = (repo / relative).stat()
        except FileNotFoundError:
            return None
        fingerprint.update(f"{relative}|{stat.st_size}|{stat.st_mtime_ns}\n".encode("utf-8"))
    parts.append(fingerprint.hexdigest())
    if source is not None:
        if git(source, "status", "--porcelain", "--untracked-files=all", "--", "frontend").strip():
            return None
        parts.append(str(source.resolve()).lower())
        parts.append(git(source, "rev-parse", "HEAD").strip())
    return hashlib.sha256("\n".join(parts).encode("utf-8")).hexdigest()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Frozen frontend source gate cache key")
    parser.add_argument("--repo", required=True, type=Path)
    parser.add_argument("--source", type=Path)
    args = parser.parse_args(argv)
    key = cache_key(args.repo.resolve(), args.source)
    if key:
        print(key)
    return 0


if __name__ == "__main__":
    sys.exit(main())

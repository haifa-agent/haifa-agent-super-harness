"""Shared cache of frozen-frontend production builds.

The frozen frontend almost never changes, yet every new worktree used to rebuild it (about two
minutes). A production build depends only on the frozen sources, the lockfile, the Node version
and the Gateway URL that ``next.config.js`` bakes into its rewrites, so builds are cached under
that key in the main checkout (``local-tmp/shared/frontend-build/<key>/``) and restored into any
worktree that needs the same build. ``.next/cache`` (build-time cache, not needed by
``next start``) is left out.

Nothing is cached or restored while ``frontend/`` has uncommitted or untracked changes.

    python scripts/lib/frontend_build_cache.py restore --repo <worktree> --gateway <url>
        exit 0 and print RESTORED when a cached build was copied into frontend/.next,
        exit 3 when there is no cached build (or the inputs are not cacheable)
    python scripts/lib/frontend_build_cache.py store --repo <worktree> --gateway <url>
        copy frontend/.next into the cache (no-op when already cached or not cacheable)
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import os
import shutil
import subprocess
import sys
import uuid
from pathlib import Path

MISS = 3


def run(*args: str, cwd: Path | None = None) -> str:
    return subprocess.run(list(args), cwd=cwd, capture_output=True, text=True, encoding="utf-8", check=True).stdout.strip()


def cache_root(repo: Path) -> Path:
    common = Path(run("git", "-C", str(repo), "rev-parse", "--path-format=absolute", "--git-common-dir"))
    return common.parent / "local-tmp" / "shared" / "frontend-build"


def cache_key(repo: Path, gateway: str) -> str | None:
    if run("git", "-C", str(repo), "status", "--porcelain", "--untracked-files=all", "--", "frontend"):
        return None
    lockfile = hashlib.sha256((repo / "frontend" / "pnpm-lock.yaml").read_bytes()).hexdigest()
    parts = [
        "frozen-frontend-build-v1",
        run("git", "-C", str(repo), "rev-parse", "HEAD:frontend"),
        lockfile,
        gateway.rstrip("/"),
        run("node", "--version"),
    ]
    return hashlib.sha256("\n".join(parts).encode("utf-8")).hexdigest()[:32]


def ignore_build_cache(directory: str, names: list[str]) -> list[str]:
    return ["cache"] if Path(directory).name == ".next" else []


def copy_build_tree(source: Path, target: Path) -> None:
    # Next emits thousands of small files. On Windows, sequential copy2 can exceed
    # the reference runner's fixed 120-second restore deadline before any UI starts.
    futures = []
    with ThreadPoolExecutor(max_workers=8) as copies:
        for directory, subdirectories, filenames in os.walk(source):
            parent = Path(directory)
            destination = target / parent.relative_to(source)
            destination.mkdir(parents=True, exist_ok=True)
            for name in subdirectories + filenames:
                if (parent / name).is_symlink():
                    raise OSError(f"Frontend build cache contains a symbolic link: {parent / name}")
            for name in filenames:
                futures.append(copies.submit(shutil.copy2, parent / name, destination / name))
        for future in futures:
            future.result()


def restore(repo: Path, gateway: str) -> int:
    key = cache_key(repo, gateway)
    source = cache_root(repo) / key / ".next" if key else None
    if source is None or not (source / "BUILD_ID").is_file():
        print("MISS")
        return MISS
    target = repo / "frontend" / ".next"
    if target.exists():
        shutil.rmtree(target)
    copy_build_tree(source, target)
    print("RESTORED")
    return 0


def store(repo: Path, gateway: str) -> int:
    key = cache_key(repo, gateway)
    source = repo / "frontend" / ".next"
    if key is None or not (source / "BUILD_ID").is_file():
        print("SKIPPED")
        return 0
    final = cache_root(repo) / key
    if (final / ".next" / "BUILD_ID").is_file():
        print("ALREADY_CACHED")
        return 0
    staging = final.parent / f".staging-{key}-{uuid.uuid4().hex}"
    shutil.copytree(source, staging / ".next", ignore=ignore_build_cache)
    try:
        os.replace(staging, final)
    except OSError:
        # Another worktree stored the same build first.
        shutil.rmtree(staging, ignore_errors=True)
    print("STORED")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Frozen frontend build cache")
    parser.add_argument("action", choices=["restore", "store"])
    parser.add_argument("--repo", required=True, type=Path)
    parser.add_argument("--gateway", required=True)
    args = parser.parse_args(argv)
    repo = args.repo.resolve()
    return restore(repo, args.gateway) if args.action == "restore" else store(repo, args.gateway)


if __name__ == "__main__":
    sys.exit(main())

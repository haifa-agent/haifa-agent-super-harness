"""Second-scale checks that run before any long Maven or UI round.

Runs the SDK baseline gate, ``git diff --check`` and a secret scan of the changes since the
merge base with ``main`` (committed and uncommitted). Prints a JSON object with a
``failures`` list and exits 1 when it is not empty.

    python scripts/lib/quick_checks.py --repo <worktree root> [--base main]
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

SECRET_PATTERNS = {
    "private key block": re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    "AWS access key id": re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    "OpenAI-style secret key": re.compile(r"\bsk-[A-Za-z0-9_-]{20,}\b"),
    "GitHub token": re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
    "Slack token": re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}\b"),
    "bearer token literal": re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._~+/-]{24,}={0,2}"),
    "assigned credential literal": re.compile(
        r"""(?i)\b(api[_-]?key|secret|password|access[_-]?token)\b\s*[:=]\s*["'][^"'\s]{12,}["']"""
    ),
}


def force_utf8_stdout() -> None:
    """Force stdout to UTF-8 so JSON with Chinese text is valid when redirected on Windows.

    Without this, a redirected stdout uses the system code page (for example GBK), so Chinese
    ``ensure_ascii=False`` output is written with bytes that are not valid UTF-8.
    """
    reconfigure = getattr(sys.stdout, "reconfigure", None)
    if reconfigure is not None:
        reconfigure(encoding="utf-8")


def git(repo: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(repo), *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=check,
    )


def find_secrets(diff_text: str) -> list[str]:
    """Returns ``"<rule> in <file>"`` for every added line that looks like a credential.

    The matched text itself is never reported.
    """
    findings: list[str] = []
    current = ""
    for line in diff_text.splitlines():
        if line.startswith("+++ b/"):
            current = line[6:].strip()
            continue
        if not line.startswith("+") or line.startswith("+++"):
            continue
        for name, pattern in SECRET_PATTERNS.items():
            finding = f"{name} in {current}"
            if pattern.search(line) and finding not in findings:
                findings.append(finding)
    return findings


def quick_checks(repo: Path, base: str) -> list[str]:
    failures: list[str] = []

    gate = repo / "tests" / "checks" / "build" / "verify-haifa-version-baseline.ps1"
    result = subprocess.run(
        ["pwsh", "-NoLogo", "-NoProfile", "-File", str(gate), "-RepositoryRoot", str(repo)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        failures.append("SDK baseline gate: " + " ".join((result.stdout + result.stderr).split()))

    merge_base = git(repo, "merge-base", "HEAD", base, check=False).stdout.strip() or "HEAD"
    whitespace = git(repo, "diff", "--check", merge_base, check=False)
    if whitespace.returncode != 0:
        failures.append("git diff --check: " + " | ".join(whitespace.stdout.splitlines()[:5]))

    diff = git(repo, "diff", "--no-color", "--unified=0", merge_base, check=False).stdout
    failures.extend(f"secret scan: {finding}" for finding in find_secrets(diff))
    return failures


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", required=True, type=Path)
    parser.add_argument("--base", default="main")
    args = parser.parse_args(argv)
    failures = quick_checks(args.repo.resolve(), args.base)
    print(json.dumps({"failures": failures}, ensure_ascii=False))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())

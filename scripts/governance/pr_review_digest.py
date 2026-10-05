"""Read a pull request's automated review without writing anything.

The frozen workflow (AGENTS.md, "验收提速") asks a task to read the bot review once
after opening a draft PR, instead of discovering it after the formal gate. This script
performs only ``gh api`` GET requests: it reads the pull request, its reactions, its
review comments and its reviews, and prints a JSON digest. It never posts a comment,
never changes the PR and never triggers a new review; it does not print credentials.

    python scripts/governance/pr_review_digest.py --repo owner/name --pr 14

``botVerdict`` and the process exit code are:

- ``CLEAN`` (0): the PR has the bot's thumbs up and no bot comment targets the current head.
- ``FINDINGS`` (2): at least one bot comment targets the current head.
- ``NO_REVIEW_YET`` (3): the bot has not reviewed this PR yet.
- process failure (1): a ``gh api`` call failed or its answer could not be parsed.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys

DEFAULT_BOT = "chatgpt-codex-connector[bot]"
PRIORITY_PATTERN = re.compile(r"badge/(P[0-3])[-)]", re.IGNORECASE)
EXIT_CODES = {"CLEAN": 0, "FINDINGS": 2, "NO_REVIEW_YET": 3}


def gh_api(path: str, paginate: bool = False):
    """Reads one ``gh api`` GET endpoint. Raises RuntimeError on any failure."""
    args = ["gh", "api"]
    if paginate:
        args += ["--paginate", "--slurp"]
    args.append(path)
    result = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode != 0:
        raise RuntimeError(f"gh api {path} failed with exit code {result.returncode}: {result.stderr.strip()}")
    try:
        data = json.loads(result.stdout or "null")
    except json.JSONDecodeError as error:
        raise RuntimeError(f"gh api {path} returned invalid JSON: {error}") from error
    if paginate and isinstance(data, list):
        flattened = []
        for page in data:
            if isinstance(page, list):
                flattened.extend(page)
        return flattened
    return data


def is_bot(entry: dict, bot: str) -> bool:
    return ((entry.get("user") or {}).get("login") or "") == bot


def parse_priority(body: str | None) -> str | None:
    match = PRIORITY_PATTERN.search(body or "")
    return match.group(1).upper() if match else None


def first_line(body: str | None) -> str:
    """First non-empty line with the review badge markup removed."""
    for line in (body or "").splitlines():
        text = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", line)
        text = re.sub(r"</?sub>", "", text)
        text = text.replace("*", "").strip()
        if text:
            return re.sub(r"\s+", " ", text)
    return ""


def finding(comment: dict, head_sha: str) -> dict:
    line = comment.get("line")
    if line is None:
        line = comment.get("original_line")
    return {
        "file": comment.get("path"),
        "line": line,
        "commit": comment.get("commit_id"),
        "outdated": comment.get("commit_id") != head_sha,
        "priority": parse_priority(comment.get("body")),
        "summary": first_line(comment.get("body")),
        "url": comment.get("html_url"),
    }


def bot_findings(comments: list[dict], head_sha: str, bot: str) -> list[dict]:
    """Top-level review comments authored by the bot, oldest first."""
    ordered = sorted(comments, key=lambda comment: comment.get("created_at") or "")
    return [
        finding(comment, head_sha)
        for comment in ordered
        if is_bot(comment, bot) and comment.get("in_reply_to_id") is None
    ]


def thumbs_up(reactions: list[dict], bot: str) -> dict | None:
    matches = [
        reaction
        for reaction in reactions
        if reaction.get("content") == "+1" and is_bot(reaction, bot)
    ]
    if not matches:
        return None
    matches.sort(key=lambda reaction: reaction.get("created_at") or "")
    return {"createdAt": matches[0].get("created_at"), "url": matches[0].get("html_url")}


def compute_verdict(head_sha: str, comments: list[dict], reactions: list[dict], bot: str) -> str:
    """Pure decision function so the three outcomes can be tested without the network."""
    bot_comments = [comment for comment in comments if is_bot(comment, bot)]
    current_head = [comment for comment in bot_comments if comment.get("commit_id") == head_sha]
    has_thumbs_up = thumbs_up(reactions, bot) is not None
    if current_head:
        return "FINDINGS"
    if has_thumbs_up:
        return "CLEAN"
    if not bot_comments:
        return "NO_REVIEW_YET"
    return "FINDINGS"


def build_digest(pull: dict, comments: list[dict], reactions: list[dict], bot: str) -> dict:
    head_sha = (pull.get("head") or {}).get("sha") or ""
    findings = bot_findings(comments, head_sha, bot)
    verdict = compute_verdict(head_sha, comments, reactions, bot)
    return {
        "repo": (pull.get("base") or {}).get("repo", {}).get("full_name"),
        "pr": pull.get("number"),
        "headSha": head_sha,
        "isDraft": bool(pull.get("draft")),
        "bot": bot,
        "botVerdict": verdict,
        "thumbsUp": thumbs_up(reactions, bot),
        "counts": {
            "comments": len(comments),
            "botComments": sum(1 for comment in comments if is_bot(comment, bot)),
            "findings": len(findings),
            "currentHeadFindings": sum(1 for item in findings if not item["outdated"]),
        },
        "findings": findings,
    }


def collect(repo: str, pr: int, bot: str) -> dict:
    pull = gh_api(f"repos/{repo}/pulls/{pr}")
    reactions = gh_api(f"repos/{repo}/issues/{pr}/reactions", paginate=True)
    comments = gh_api(f"repos/{repo}/pulls/{pr}/comments", paginate=True)
    return build_digest(pull, comments, reactions, bot)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Read a PR's automated review (read-only)")
    parser.add_argument("--repo", required=True, help="owner/name")
    parser.add_argument("--pr", required=True, type=int)
    parser.add_argument("--bot", default=DEFAULT_BOT)
    args = parser.parse_args(argv)
    try:
        digest = collect(args.repo, args.pr, args.bot)
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        return 1
    print(json.dumps(digest, indent=2, ensure_ascii=False))
    return EXIT_CODES[digest["botVerdict"]]


if __name__ == "__main__":
    sys.exit(main())

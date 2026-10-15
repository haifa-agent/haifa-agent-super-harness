"""Read a pull request's automated review without writing anything.

The frozen workflow (AGENTS.md, "验收分层") asks a task to read the bot review once
after opening a draft PR, instead of discovering it after the formal gate. This script
performs only ``gh api`` GET requests: it reads the pull request, its reactions, its
review comments and the bot's review summary comment, and prints a JSON digest. It never posts a comment,
never changes the PR and never triggers a new review; it does not print credentials.

    python scripts/governance/pr_review_digest.py --repo owner/name --pr 14

``botVerdict`` and the process exit code are:

- ``CLEAN`` (0): the PR has the bot's thumbs up and no bot comment targets the current head.
- ``FINDINGS`` (2): at least one bot comment targets the current head.
- ``NO_REVIEW_YET`` (3): the bot has not reviewed this PR yet.
- ``REVIEW_FAILED`` (4): the bot's summary comment reports that its latest code review failed
  and there is neither a thumbs up nor a comment on the current head.
- process failure (1): a ``gh api`` call failed or its answer could not be parsed.

The bot reviews a PR when it is opened for review, when a draft is marked ready, or when
someone comments ``@codex review``; it does not review drafts. Its summary comment names the
commit and trigger of the latest review; the digest returns that as ``reviewSummary`` and
``reviewedHead`` says whether that commit is the current head.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))

import quick_checks  # noqa: E402  (path added above)

DEFAULT_BOT = "chatgpt-codex-connector[bot]"
PRIORITY_PATTERN = re.compile(r"badge/(P[0-3])[-)]", re.IGNORECASE)
SUMMARY_MARKER = "codex-pull-request-review-summary"
SUMMARY_ROW_PATTERN = re.compile(
    r"^\|[^|]*\*\*(?P<review>[^*|]+)\*\*\s*\|[^|]*?\*\*(?P<status>[^*|]+)\*\*(?P<rest>[^|]*)\|"
    r"\s*`(?P<commit>[0-9a-fA-F]{7,40})`\s*\|\s*(?P<trigger>[^|]*?)\s*\|\s*$")
SUMMARY_TIME_PATTERN = re.compile(r'datetime="([^"]+)"')
EXIT_CODES = {"CLEAN": 0, "FINDINGS": 2, "NO_REVIEW_YET": 3, "REVIEW_FAILED": 4}


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


def review_summary(issue_comments: list[dict], bot: str) -> list[dict]:
    """Rows of the bot's latest review summary comment (review, status, commit, trigger)."""
    summaries = [comment for comment in issue_comments
                 if is_bot(comment, bot) and SUMMARY_MARKER in (comment.get("body") or "")]
    if not summaries:
        return []
    summaries.sort(key=lambda comment: comment.get("updated_at") or comment.get("created_at") or "")
    rows = []
    for line in (summaries[-1].get("body") or "").splitlines():
        match = SUMMARY_ROW_PATTERN.match(line.strip())
        if not match:
            continue
        moment = SUMMARY_TIME_PATTERN.search(match.group("rest"))
        rows.append({
            "review": re.sub(r"[^A-Za-z ]", "", match.group("review")).strip(),
            "status": match.group("status").strip(),
            "at": moment.group(1) if moment else None,
            "commit": match.group("commit").lower(),
            "trigger": match.group("trigger").strip(),
        })
    return rows


def code_review_row(summary: list[dict]) -> dict | None:
    for row in summary:
        if row["review"].lower() == "code review":
            return row
    return summary[0] if summary else None


def compute_verdict(head_sha: str, comments: list[dict], reactions: list[dict], bot: str,
                    summary: list[dict] | None = None) -> str:
    """Pure decision function so every outcome can be tested without the network."""
    bot_comments = [comment for comment in comments if is_bot(comment, bot)]
    current_head = [comment for comment in bot_comments if comment.get("commit_id") == head_sha]
    has_thumbs_up = thumbs_up(reactions, bot) is not None
    latest = code_review_row(summary or [])
    if current_head:
        return "FINDINGS"
    if has_thumbs_up:
        return "CLEAN"
    if latest is not None and latest["status"].lower() == "failed":
        return "REVIEW_FAILED"
    if not bot_comments:
        return "NO_REVIEW_YET"
    return "FINDINGS"


def build_digest(pull: dict, comments: list[dict], reactions: list[dict], bot: str,
                 issue_comments: list[dict] | None = None) -> dict:
    head_sha = (pull.get("head") or {}).get("sha") or ""
    findings = bot_findings(comments, head_sha, bot)
    summary = review_summary(issue_comments or [], bot)
    latest = code_review_row(summary)
    verdict = compute_verdict(head_sha, comments, reactions, bot, summary)
    return {
        "repo": (pull.get("base") or {}).get("repo", {}).get("full_name"),
        "pr": pull.get("number"),
        "headSha": head_sha,
        "isDraft": bool(pull.get("draft")),
        "bot": bot,
        "botVerdict": verdict,
        "thumbsUp": thumbs_up(reactions, bot),
        "reviewSummary": summary,
        "reviewedHead": bool(latest and head_sha and head_sha.lower().startswith(latest["commit"])),
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
    issue_comments = gh_api(f"repos/{repo}/issues/{pr}/comments", paginate=True)
    return build_digest(pull, comments, reactions, bot, issue_comments)


def main(argv: list[str] | None = None) -> int:
    quick_checks.force_utf8_stdout()
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

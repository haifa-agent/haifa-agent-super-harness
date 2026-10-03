"""Registers and dispatches one task in SHPM with a single call.

Runs, in order and stopping at the first failure:

    shpm task create -> shpm sprint link (with --stage) -> shpm task start
    -> shpm assign create (id asg-<task>, base SHA = current main)

Every write carries --json, --db and an idempotency key ``<yyyy-mm-dd>-<task>-<action>``,
so a rerun after a partial failure is safe. Without --execute the commands are only printed.
Delivery, acceptance, stage results and GO stay manual.

    python scripts/governance/shpm_dispatch.py --task W05-task-12 --wp W05 --title "..." \\
        --prompt private-docs/prompts/.../W05-Example.md --exit "..." --executor opencode \\
        --ref opencode-w05 --model deepseek/deepseek-flash --branch codex/w05-example \\
        --worktree D:/workspace/haifa-agent-super-harness-worktrees/w05-example --slot 1 \\
        --data-root local-tmp/slot-1-w05-example/data [--stage SPRINT/STAGE] [--execute]
"""

from __future__ import annotations

import argparse
import datetime
import os
import subprocess
import sys
from pathlib import Path

DEFAULT_DB = r"C:\Users\wangr\.shpm\data\project.db"


def main_checkout() -> Path:
    """private-docs and the SHPM-registered repository path live in the main checkout."""
    common = subprocess.run(
        ["git", "-C", str(Path(__file__).resolve().parents[2]), "rev-parse", "--path-format=absolute", "--git-common-dir"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    return Path(common).parent


def build_steps(args: argparse.Namespace, repo: Path, base_sha: str, day: str) -> list[list[str]]:
    task = args.task
    steps = [
        ["task", "create", task, "--wp", args.wp, "--title", args.title, "--prompt", args.prompt,
         "--exit", args.exit, "--priority", args.priority, "--next", args.next,
         "--idempotency-key", f"{day}-{task}-create"],
    ]
    if args.stage:
        steps.append(["sprint", "link", args.stage, "--task", task, "--relation", "delivers", "--required",
                      "--idempotency-key", f"{day}-{task}-link"])
    steps.append(["task", "start", task, "--idempotency-key", f"{day}-{task}-start"])
    assign = ["assign", "create", task, "--id", f"asg-{task}", "--executor", args.executor,
              "--repo", str(repo), "--base-sha", base_sha, "--branch", args.branch, "--worktree", args.worktree,
              "--checkpoint-in", "15m", "--idempotency-key", f"{day}-{task}-assign"]
    if args.ref:
        assign += ["--ref", args.ref]
    if args.model:
        assign += ["--model", args.model]
    if args.slot:
        gateway = 10618 + 10000 * args.slot
        assign += ["--slot", str(args.slot), "--ports", f"{gateway}/{gateway + 5000}"]
    if args.data_root:
        assign += ["--data-root", args.data_root]
    steps.append(assign)
    return [step + ["--json", "--db", args.db] for step in steps]


def display(step: list[str]) -> str:
    return "shpm " + " ".join(f"'{part}'" if " " in part else part for part in step)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Register and dispatch one SHPM task")
    parser.add_argument("--task", required=True)
    parser.add_argument("--wp", required=True)
    parser.add_argument("--title", required=True)
    parser.add_argument("--prompt", required=True)
    parser.add_argument("--exit", required=True)
    parser.add_argument("--priority", default="P1", choices=["P0", "P1", "P2"])
    parser.add_argument("--next", default="Dispatch and implement")
    parser.add_argument("--stage")
    parser.add_argument("--executor", required=True, choices=["main", "codex", "opencode", "human"])
    parser.add_argument("--ref")
    parser.add_argument("--model")
    parser.add_argument("--branch", required=True)
    parser.add_argument("--worktree", required=True)
    parser.add_argument("--slot", type=int, choices=[1, 2, 3])
    parser.add_argument("--data-root")
    parser.add_argument("--db", default=DEFAULT_DB)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args(argv)

    repo = main_checkout()
    if not (repo / args.prompt).is_file():
        print(f"Prompt '{args.prompt}' does not exist under {repo}; SHPM cannot start a task without it.", file=sys.stderr)
        return 2
    base_sha = subprocess.run(["git", "-C", str(repo), "rev-parse", "main"], capture_output=True, text=True, check=True).stdout.strip()
    steps = build_steps(args, repo, base_sha, datetime.date.today().isoformat())
    if not args.execute:
        for step in steps:
            print(display(step))
        print("(dry run: pass --execute to write these records)")
        return 0
    env = dict(os.environ, SHPM_DB=args.db, PYTHONIOENCODING="utf-8")
    for step in steps:
        result = subprocess.run(["shpm", *step], capture_output=True, text=True, encoding="utf-8", errors="replace", env=env)
        if result.returncode != 0:
            print(display(step))
            print(result.stdout + result.stderr)
            print(f"SHPM step failed with exit code {result.returncode}; later steps were not run.", file=sys.stderr)
            return result.returncode
    return 0


if __name__ == "__main__":
    sys.exit(main())

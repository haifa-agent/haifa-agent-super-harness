"""Registers and dispatches one task in SHPM with a single call.

Runs, in order and stopping at the first failure:

    shpm task create -> shpm sprint link (with --stage) -> shpm task start
    -> shpm assign create (id asg-<task>, base SHA = HEAD of --worktree)

Every write carries --json, --db and an idempotency key ``<yyyy-mm-dd>-<task>-<action>``,
so a rerun after a partial failure is safe. Without --execute the commands are only printed.
Delivery, acceptance, stage results and GO stay manual.

The slot, its ports, the data root and the worktree are checked before any step is printed or
run, because ``shpm assign create`` rejects an assignment without ports or a data root and a
failure there would leave a started task with no assignment. --repo names the repository the
worktree belongs to (default: this repository); pass it for upstream SDK work in a worktree of
D:/workspace/haifa-agent.

    python scripts/governance/shpm_dispatch.py --task W05-task-12 --wp W05 --title "..." \\
        --prompt private-docs/prompts/.../W05-Example.md --exit "..." --executor opencode \\
        --ref opencode-w05 --model deepseek/deepseek-flash --branch codex/w05-example \\
        --worktree D:/workspace/haifa-agent-super-harness-worktrees/w05-example --slot 1 \\
        --data-root local-tmp/slot-1-w05-example/data [--repo D:/workspace/haifa-agent] \\
        [--stage SPRINT/STAGE] [--execute]
"""

from __future__ import annotations

import argparse
import datetime
import os
import subprocess
import sys
from pathlib import Path

DEFAULT_DB = r"C:\Users\wangr\.shpm\data\project.db"

# Slot -> (Gateway, Frontend), copied from the port table in AGENTS.md. Slot 4 is outside the
# +10000 series on purpose, so the ports are listed rather than computed.
SLOT_PORTS = {1: (20618, 25618), 2: (30618, 35618), 3: (40618, 45618), 4: (11618, 16618)}


class DispatchError(Exception):
    """An input that would make a later SHPM step fail."""


def git_value(directory: Path | str, *command: str) -> str | None:
    result = subprocess.run(["git", "-C", str(directory), *command], capture_output=True, text=True)
    return result.stdout.strip() if result.returncode == 0 else None


def main_checkout() -> Path:
    """private-docs and the SHPM-registered repository path live in the main checkout."""
    common = subprocess.run(
        ["git", "-C", str(Path(__file__).resolve().parents[2]), "rev-parse", "--path-format=absolute", "--git-common-dir"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    return Path(common).parent


def resource_errors(args: argparse.Namespace) -> list[str]:
    """What ``shpm assign create`` would reject, found before the task is created and started."""
    errors = []
    table = ", ".join(f"{slot} = {gateway}/{frontend}" for slot, (gateway, frontend) in sorted(SLOT_PORTS.items()))
    if args.slot is None:
        errors.append(f"--slot is required: shpm assign create rejects an assignment without ports ({table}).")
    elif args.slot not in SLOT_PORTS:
        errors.append(f"Slot {args.slot} has no ports in the AGENTS.md port table ({table}).")
    if not args.data_root:
        errors.append("--data-root is required: shpm assign create rejects an assignment without it.")
    return errors


def worktree_base_sha(repo: Path, worktree: str) -> str:
    """HEAD of the worktree, after confirming the worktree is a checkout of ``repo``."""
    # git treats -C '' as the current directory, so an empty value would pass as this checkout.
    base_sha = git_value(worktree, "rev-parse", "--verify", "HEAD") if worktree.strip() else None
    if base_sha is None:
        raise DispatchError(f"Worktree '{worktree}' is not a git checkout with a commit; create it before dispatching.")
    common = [git_value(path, "rev-parse", "--path-format=absolute", "--git-common-dir") for path in (repo, worktree)]
    if common[0] is None:
        raise DispatchError(f"Repository '{repo}' is not a git repository.")
    if Path(common[0]).resolve() != Path(common[1]).resolve():
        raise DispatchError(
            f"Worktree '{worktree}' is not a checkout of '{repo}'; pass --repo with the repository it belongs to.")
    return base_sha


def build_steps(args: argparse.Namespace, repo: Path, base_sha: str, day: str) -> list[list[str]]:
    errors = resource_errors(args)
    if errors:
        raise DispatchError(" ".join(errors))
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
    gateway, frontend = SLOT_PORTS[args.slot]
    assign = ["assign", "create", task, "--id", f"asg-{task}", "--executor", args.executor,
              "--repo", str(repo), "--base-sha", base_sha, "--branch", args.branch, "--worktree", args.worktree,
              "--slot", str(args.slot), "--ports", f"{gateway}/{frontend}", "--data-root", args.data_root,
              "--checkpoint-in", "15m", "--idempotency-key", f"{day}-{task}-assign"]
    if args.ref:
        assign += ["--ref", args.ref]
    if args.model:
        assign += ["--model", args.model]
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
    parser.add_argument("--repo", help="repository the worktree belongs to (default: this repository)")
    parser.add_argument("--branch", required=True)
    parser.add_argument("--worktree", required=True)
    parser.add_argument("--slot", type=int, choices=sorted(SLOT_PORTS))
    parser.add_argument("--data-root")
    parser.add_argument("--db", default=DEFAULT_DB)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args(argv)

    errors = resource_errors(args)
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        print("Nothing was written to SHPM.", file=sys.stderr)
        return 2
    harness = main_checkout()
    if not (harness / args.prompt).is_file():
        print(f"Prompt '{args.prompt}' does not exist under {harness}; SHPM cannot start a task without it.", file=sys.stderr)
        return 2
    repo = Path(args.repo).resolve() if args.repo else harness
    try:
        base_sha = worktree_base_sha(repo, args.worktree)
        steps = build_steps(args, repo, base_sha, datetime.date.today().isoformat())
    except DispatchError as error:
        print(f"{error} Nothing was written to SHPM.", file=sys.stderr)
        return 2
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

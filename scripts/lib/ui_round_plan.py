"""Plans repeated and merged UI smoke rounds and merges their evidence (R8).

The frozen ``run-w18-ui-smoke.ps1`` can start one service and run a spec more than once
(``-Passes n``) or run several compatible profiles against the same service
(``-MergeProfiles a,b``).  The pure decisions live here so they can be unit tested without
starting a browser or a server:

* :func:`validate_run` rejects option combinations that must never reach a real run
  (a cold build, ``-CaseTitle`` or ``-PreflightOnly`` together with ``-Passes > 1``; a
  merged profile set that does not share one server startup class);
* :func:`run_layout` maps a profile set and pass count onto the evidence directories;
* :func:`merge_case_results` concatenates the per-pass rows and annotates each with its
  profile and pass while preserving order;
* :func:`merge_passes_summary` turns per-pass flow-health facts into the ``passes`` array
  that the top-level ``flow-health.json`` carries.

CLI (used by the runner)::

    python -B scripts/lib/ui_round_plan.py validate --passes 2 --profiles a,b ...
    python -B scripts/lib/ui_round_plan.py plan --root <evidence> --profiles a --passes 2
    python -B scripts/lib/ui_round_plan.py merge --root <evidence> --runs <plan.json>
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

DEFAULT_PASSES = 1
MAX_PASSES = 5
PASS_INDEX_ENV = "W18_PASS_INDEX"

# profile tag -> Playwright spec, mirroring the selection table in run-w18-ui-smoke.ps1.
PROFILE_SPECS = {
    "w15-package-differential": "w15-package-differential.spec.cjs",
    "w15-live-skill": "w15-live-skill.spec.cjs",
    "ux-v1-soul": "ux-v1-agent-soul.spec.cjs",
    "ux-v1": "ux-v1-default-tools-live.spec.cjs",
    "w18-deepseek": "w18-deepseek-live.spec.cjs",
    "w05-active-reconnect": "w05-active-reconnect.spec.cjs",
    "w05-interaction": "w05-interaction.spec.cjs",
    "w05-demo-artifact": "w05-demo-artifact.spec.cjs",
    "w03-title-suggestions": "w03-title-suggestions.spec.cjs",
    "w03-input-polish-ui": "w03-input-polish.spec.cjs",
    "w03-v2-model-switch": "w03-v2-model-switch.spec.cjs",
    "w04-v2-file-context": "w04-v2-file-context.spec.cjs",
    "w15-v2-skill": "w15-v2-skill.spec.cjs",
    "w21-diagnostics": "w21-v2-diagnostics.spec.cjs",
    "w08-catalog": "w08-subagent-catalog.spec.cjs",
    "w18-ui-smoke": "w18-ui-smoke.spec.cjs",
}

# Profiles that may share one service start.  The startup class is defined by the server
# side: the controlled loopback Provider plus the packaged exec JAR (never the live
# managed model, never the Interaction test-classpath Gateway).  The scripted Provider
# answers and the managed-model PUT/probe are generic, so only the per-pass spec, timeout,
# trace mode and W15_DIFFERENTIAL_BACKEND differ, and all of those are passed to the
# Playwright process, not baked into the server start.
STARTUP_CLASSES = {
    "controlled-exec-jar": (
        "w05-demo-artifact",
        "w15-v2-skill",
        "w15-package-differential",
    ),
}
_STARTUP_CLASS_BY_PROFILE = {profile: name for name, profiles in STARTUP_CLASSES.items() for profile in profiles}


# Profiles whose spec was run twice against one service start and passed both times
# (controller, 2026-10-05). Everything else is rejected for -Passes > 1 until someone verifies
# it the same way: a spec may leave state behind that its own second pass trips over.
# w15-v2-skill is known not to repeat: its first pass leaves a Skill disabled.
REPEATABLE_PROFILES = (
    "w18-ui-smoke",
    "w05-demo-artifact",
    "w15-package-differential",
)


class PlanError(RuntimeError):
    """Raised when the requested round cannot be planned without changing semantics."""


def validate_run(
    *,
    passes: int = DEFAULT_PASSES,
    profiles: list[str] | None = None,
    case_title: str | None = None,
    preflight_only: bool = False,
    skip_build: bool = False,
    profile: str | None = None,
) -> None:
    """Rejects impossible option combinations; returns None when the run is valid."""
    profiles = list(profiles or [])
    problems: list[str] = []
    if isinstance(passes, bool) or not isinstance(passes, int) or not 1 <= passes <= MAX_PASSES:
        problems.append(f"-Passes must be an integer between 1 and {MAX_PASSES}, got {passes!r}")
    if passes > 1:
        if case_title:
            problems.append("-Passes > 1 cannot be combined with -CaseTitle")
        if preflight_only:
            problems.append("-Passes > 1 cannot be combined with -PreflightOnly")
        if not skip_build:
            problems.append("-Passes > 1 requires a warm round (-SkipBuild); a cold round cannot repeat")
        if profile is not None and profile not in REPEATABLE_PROFILES:
            problems.append(
                f"profile '{profile}' is not verified for repeated passes "
                f"(verified: {', '.join(REPEATABLE_PROFILES)}); run it as separate rounds")
    if profiles:
        if len(profiles) < 2:
            problems.append("-MergeProfiles needs at least two profile names")
        unknown = [profile for profile in profiles if profile not in PROFILE_SPECS]
        if unknown:
            problems.append(f"unknown profile(s): {', '.join(unknown)}")
        known = [profile for profile in profiles if profile in PROFILE_SPECS]
        unmergeable = [profile for profile in known if profile not in _STARTUP_CLASS_BY_PROFILE]
        if unmergeable:
            problems.append(f"profile(s) have no mergeable startup class: {', '.join(unmergeable)}")
        classes = {_STARTUP_CLASS_BY_PROFILE[profile] for profile in known if profile in _STARTUP_CLASS_BY_PROFILE}
        if len(classes) > 1:
            problems.append(
                "profiles do not share a server startup configuration: "
                + ", ".join(f"{profile}={_STARTUP_CLASS_BY_PROFILE.get(profile)}" for profile in known))
    if problems:
        raise PlanError("; ".join(problems))


def run_layout(root: Path, profiles: list[str], passes: int = DEFAULT_PASSES) -> list[dict]:
    """Evidence directory for every (profile, pass) that a valid run will execute.

    A single profile with one pass keeps the historical layout: the evidence lands
    directly under ``root``.  Repetition uses ``pass-<i>/``; merging always nests under
    ``<profile>/pass-<i>/`` so per-profile evidence never collides.
    """
    profiles = list(profiles) or ["w18-ui-smoke"]
    if passes < 1 or passes > MAX_PASSES:
        raise PlanError(f"passes must be between 1 and {MAX_PASSES}, got {passes!r}")
    multi = len(profiles) > 1
    runs = []
    for profile in profiles:
        if profile not in PROFILE_SPECS:
            raise PlanError(f"unknown profile: {profile}")
        for index in range(1, passes + 1):
            if not multi and passes == 1:
                relative = Path(".")
            elif multi:
                relative = Path(profile) / f"pass-{index}"
            else:
                relative = Path(f"pass-{index}")
            directory = root if str(relative) == "." else root / relative
            runs.append({
                "profile": profile,
                "pass": index,
                "spec": PROFILE_SPECS[profile],
                "directory": str(directory),
                "relativeDirectory": str(relative).replace("\\", "/"),
                "env": {PASS_INDEX_ENV: str(index)},
            })
    return runs


def merge_case_results(pass_rows: list[dict]) -> list[dict]:
    """Concatenate per-pass case rows, annotating each with its profile and pass.

    ``pass_rows`` is an ordered list of ``{"profile": str, "pass": int, "rows": [...]}``;
    the result preserves that order so the merged file matches how the passes ran.  An
    empty pass still contributes nothing; the caller decides whether that is a failure.
    """
    merged: list[dict] = []
    for entry in pass_rows:
        for row in entry.get("rows") or []:
            annotated = dict(row)
            annotated["profile"] = entry.get("profile")
            annotated["pass"] = int(entry.get("pass"))
            merged.append(annotated)
    return merged


def merge_passes_summary(entries: list[dict]) -> list[dict]:
    """Build the ``passes`` array for the top-level ``flow-health.json``.

    Each entry keeps the pass's assertion duration, exit code, first failure and the
    relative path of its own ``case-results.json``; the fields are copied, never inferred.
    """
    summary = []
    for entry in entries:
        summary.append({
            "profile": entry.get("profile"),
            "pass": int(entry.get("pass")),
            "assertionMs": int(entry.get("assertionMs") or 0),
            "exitCode": entry.get("exitCode"),
            "firstFailure": entry.get("firstFailure"),
            "caseResults": entry.get("caseResults"),
        })
    return summary


def _load_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, json.JSONDecodeError, UnicodeDecodeError):
        return None


def _plan(root: Path, profiles: list[str], passes: int) -> dict:
    validate_run(passes=passes, profiles=profiles if profiles else None, skip_build=True)
    return {"root": str(root), "runs": run_layout(root, profiles, passes)}


def _read_pass(root: Path, run: dict) -> dict:
    relative = run.get("caseResults")
    if relative:
        # The runner writes each pass's facts into the plan manifest; cases come from that
        # pass's own case-results.json.
        rows = _load_json(root / str(relative)) or []
        return {
            "profile": run.get("profile"),
            "pass": int(run.get("pass")),
            "assertionMs": run.get("assertionMs") or 0,
            "exitCode": run.get("exitCode"),
            "firstFailure": run.get("firstFailure"),
            "caseResults": str(relative).replace("\\", "/"),
            "rows": rows if isinstance(rows, list) else [],
        }
    directory = Path(run["directory"])
    health = _load_json(directory / "flow-health.json") or {}
    timings = health.get("timings") or {}
    cases = _load_json(directory / "case-results.json") or []
    relative = run.get("relativeDirectory") or "."
    case_results_relative = str(Path(relative) / "case-results.json").replace("\\", "/") if relative != "." else "case-results.json"
    return {
        "profile": run.get("profile"),
        "pass": int(run.get("pass")),
        "assertionMs": timings.get("assertionsMs") or timings.get("assertionMs") or 0,
        "exitCode": health.get("testExitCode"),
        "firstFailure": health.get("firstFailure"),
        "caseResults": case_results_relative,
        "rows": cases if isinstance(cases, list) else [],
    }


def _merge_into_root(root: Path, runs: list[dict]) -> dict:
    passes = [_read_pass(root, run) for run in runs]
    merged = merge_case_results(passes)
    (root / "case-results.json").write_text(json.dumps(merged, ensure_ascii=False, indent=2), encoding="utf-8")
    summary = merge_passes_summary(passes)
    health_path = root / "flow-health.json"
    health = _load_json(health_path)
    if isinstance(health, dict):
        health["passes"] = summary
        health_path.write_text(json.dumps(health, ensure_ascii=False, indent=2), encoding="utf-8")
    return {"passes": summary, "caseCount": len(merged), "caseResults": "case-results.json"}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Plan repeated or merged UI smoke runs")
    sub = parser.add_subparsers(dest="command", required=True)

    validate = sub.add_parser("validate")
    validate.add_argument("--passes", type=int, default=DEFAULT_PASSES)
    validate.add_argument("--profiles", default="")
    validate.add_argument("--case-title", default=None)
    validate.add_argument("--preflight-only", action="store_true")
    validate.add_argument("--skip-build", action="store_true")
    validate.add_argument("--profile", default=None)

    plan = sub.add_parser("plan")
    plan.add_argument("--root", type=Path, required=True)
    plan.add_argument("--profiles", default="")
    plan.add_argument("--passes", type=int, default=DEFAULT_PASSES)
    plan.add_argument("--output", type=Path)

    merge = sub.add_parser("merge")
    merge.add_argument("--root", type=Path, required=True)
    merge.add_argument("--runs", type=Path, required=True)

    args = parser.parse_args(argv)
    profiles = [name for name in (args.profiles or "").split(",") if name] if hasattr(args, "profiles") else []
    try:
        if args.command == "validate":
            validate_run(passes=args.passes, profiles=profiles, case_title=args.case_title,
                         preflight_only=args.preflight_only, skip_build=args.skip_build,
                         profile=args.profile)
            print("ok")
        elif args.command == "plan":
            document = _plan(args.root.resolve(), profiles, args.passes)
            text = json.dumps(document, ensure_ascii=False, indent=2)
            if args.output:
                args.output.write_text(text, encoding="utf-8")
            print(text)
        else:
            document = _load_json(args.runs)
            if not isinstance(document, dict) or not isinstance(document.get("runs"), list):
                raise PlanError(f"run plan '{args.runs}' is not a plan document")
            result = _merge_into_root(args.root.resolve(), document["runs"])
            print(json.dumps(result, ensure_ascii=False))
    except PlanError as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

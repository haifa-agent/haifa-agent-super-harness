"""Validates positive scheduled-task fixtures against the frozen DeerFlow scheduler.

The frozen frontend sends ``schedule_spec`` with a ``cron`` key (see
``frontend/src/core/scheduled-tasks/cron.ts`` and ``recipes.ts``), not ``expression``.
The shipped core-protocol fixtures must use that frozen key, and the cron value must
produce a real next run time under the frozen scheduler semantics
(``backend/packages/harness/deerflow/scheduler/schedules.py``, which drives croniter).

This is a static semantics check: it imports the frozen module and calls
``next_run_at`` with a fixed ``now`` derived from the fixture, then compares the result
with the fixture's declared ``next_run_at`` where the scheduler is deterministic
(``cron`` and ``once``). Interval schedules are relative to the run instant, so only a
non-null, timezone-aware result is asserted. No server, database or clock is touched.

    python -B scripts/contracts/verify-schedule-semantics.py \\
        --fixtures contracts/upstream/deerflow/fixtures/core-protocol-fixtures.json \\
        --frozen-schedules <deer-flow>/backend/packages/harness/deerflow/scheduler/schedules.py

Exit 0 when every assertion holds; a non-zero exit prints the first failure signature.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import sys
from datetime import datetime, timezone
from pathlib import Path


def load_frozen_schedules(path: Path):
    if not path.is_file():
        raise RuntimeError(f"frozen schedules module is missing: {path}")
    spec = importlib.util.spec_from_file_location("frozen_deerflow_schedules", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load frozen schedules module: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    if not hasattr(module, "next_run_at"):
        raise RuntimeError(f"frozen schedules module has no next_run_at: {path}")
    return module


def schedule_nodes(value):
    """Every dict that carries both schedule_type and schedule_spec, at any depth."""
    if isinstance(value, dict):
        if "schedule_type" in value and "schedule_spec" in value:
            yield value
        for child in value.values():
            yield from schedule_nodes(child)
    elif isinstance(value, list):
        for child in value:
            yield from schedule_nodes(child)


def parse_now(value: object) -> datetime:
    if not isinstance(value, str) or not value:
        raise RuntimeError("fixture has no timestamp to anchor the schedule computation")
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def check(fixtures_path: Path, schedules_path: Path) -> list[str]:
    fixtures = json.loads(fixtures_path.read_text(encoding="utf-8"))
    module = load_frozen_schedules(schedules_path)
    failures: list[str] = []
    passes: list[str] = []

    nodes = list(schedule_nodes(fixtures))
    cron_nodes = [node for node in nodes if node.get("schedule_type") == "cron"]
    if not cron_nodes:
        failures.append("no cron schedule node found in fixtures")

    for node in cron_nodes:
        spec = node.get("schedule_spec")
        if not isinstance(spec, dict):
            failures.append("cron schedule_spec is not an object")
            continue
        if "expression" in spec:
            failures.append("cron schedule_spec still uses 'expression' instead of frozen 'cron'")
            continue
        if "cron" not in spec:
            failures.append("cron schedule_spec has no frozen 'cron' key")
            continue
        if not isinstance(spec["cron"], str) or len(spec["cron"].split()) != 5:
            failures.append(f"cron expression is not five fields: {spec['cron']!r}")
    passes.append(f"PASS every cron schedule_spec uses the frozen 'cron' key ({len(cron_nodes)} node(s))")

    for fixture in fixtures.get("fixtures", []):
        if fixture.get("contract_id") != "rest.schedules.create.exchange":
            continue
        request = fixture.get("request")
        response = fixture.get("response")
        if not isinstance(request, dict) or not isinstance(response, dict):
            continue
        schedule_type = request.get("schedule_type")
        if schedule_type is None:
            continue
        computed = module.next_run_at(
            schedule_type,
            request["schedule_spec"],
            request["timezone"],
            now=parse_now(response.get("created_at")),
        )
        if computed is None or computed.tzinfo is None:
            failures.append(f"fixture '{fixture.get('id')}' computed no timezone-aware next run")
            continue
        passes.append(
            f"PASS fixture '{fixture.get('id')}' yields a valid next run ({computed.isoformat()})"
        )
        declared = response.get("next_run_at")
        if schedule_type in ("cron", "once") and isinstance(declared, str):
            expected = datetime.fromisoformat(declared.replace("Z", "+00:00"))
            if computed.astimezone(timezone.utc) != expected.astimezone(timezone.utc):
                failures.append(
                    f"fixture '{fixture.get('id')}' declares next_run_at {declared} but the frozen "
                    f"scheduler computes {computed.astimezone(timezone.utc).isoformat()}"
                )

    return passes, failures


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validate schedule fixtures against frozen semantics")
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--frozen-schedules", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        passes, failures = check(args.fixtures, args.frozen_schedules)
    except Exception as error:  # noqa: BLE001 - report the exact first failure signature
        print(f"FAIL {type(error).__name__}: {error}")
        return 1
    for line in passes:
        print(line)
    if failures:
        print(f"FAIL {failures[0]}")
        return 1
    print("PASS frozen schedule semantics verified")
    return 0


if __name__ == "__main__":
    sys.exit(main())

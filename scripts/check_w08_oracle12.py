"""Check the approved W08 Oracle 12 against actual HTTP fixtures and frozen functions.

Oracle 12A excludes only the two known truncation metadata fields from consumer
object equality. Oracle 12B checks the original wire metadata and bounds, with
independent original Provider responses for the large-output prefix checks.
"""
from __future__ import annotations

import argparse
import copy
import datetime
import hashlib
import itertools
import json
from pathlib import Path
import shutil
import subprocess
import uuid

LIMIT = 8192
FROZEN_HASH = "da469a7a23d27343afb4e4ce31d882195ecc3131a1f43206373c088d5d9807b7"
CONTRACT = "W08-Oracle12-v2-20261004"


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def json_equal(left, right) -> bool:
    # Python's True == 1 must not hide a JSON type mismatch.
    return json.dumps(left, sort_keys=True) == json.dumps(right, sort_keys=True)


def consumer_content(step: dict) -> dict:
    value = copy.deepcopy(step)
    value.pop("truncated", None)
    for call in value.get("tool_calls", []):
        call.pop("args_truncated", None)
    return value


def content_failures(live: list, replay: list) -> list[str]:
    failures = []
    if not live or len(live) != len(replay):
        failures.append("Step counts must be nonzero and equal")
    for lane, steps in [("live", live), ("replay", replay)]:
        indexes = [step.get("message_index") for step in steps]
        if any(type(index) is not int or not 0 < index <= 2**53 - 1 for index in indexes):
            failures.append(f"{lane}: invalid message index")
        elif any(left >= right for left, right in zip(indexes, indexes[1:])):
            failures.append(f"{lane}: duplicate or unordered steps")
    for index, (left, right) in enumerate(zip(live, replay)):
        if not json_equal(consumer_content(left), consumer_content(right)):
            failures.append(f"step[{index}]: consumer content differs")
    return failures


def provider_sources(directory: Path) -> dict:
    """Read original streamed Provider responses, retaining their evidence hashes."""
    texts, calls, evidence = [], [], []
    for path in sorted(directory.glob("provider-response-*.sse")):
        text_parts, fragments = [], {}
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            if not line.startswith("data: ") or line[6:].strip() == "[DONE]":
                continue
            for choice in json.loads(line[6:]).get("choices", []):
                delta = choice.get("delta", {})
                text_parts.append(delta.get("content") or "")
                for call in delta.get("tool_calls", []):
                    entry = fragments.setdefault(call.get("index", 0), {"name": "", "arguments": ""})
                    function = call.get("function", {})
                    entry["name"] += function.get("name") or ""
                    entry["arguments"] += function.get("arguments") or ""
        text = "".join(text_parts)
        if len(text) > LIMIT:
            texts.append(text)
        for entry in fragments.values():
            if len(entry["arguments"]) > LIMIT:
                calls.append({"name": entry["name"], "args": json.loads(entry["arguments"])})
        evidence.append({"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    return {"texts": texts, "calls": calls, "evidence": evidence}


def serialized_prefixes(arguments: dict) -> set[str]:
    # The recorded fixture has a flat two-key object. JSON object ordering is
    # immaterial; compare values from the independent source in every permitted
    # order rather than deriving expected values from the already clipped args.
    if len(arguments) > 6 or any(isinstance(value, (dict, list)) for value in arguments.values()):
        raise ValueError("Large argument source needs explicit serialization evidence")
    return {json.dumps(dict(items), ensure_ascii=False, separators=(",", ":"))[:LIMIT]
            for items in itertools.permutations(arguments.items())}


def wire_failures(fixture: dict, sources: dict) -> list[str]:
    failures, seen_texts, seen_calls = [], set(), set()
    live, replay = fixture["live"], fixture["replay"]
    if not live or len(live) != len(replay):
        failures.append("Wire step counts must be nonzero and equal")
    previous_index = previous_seq = 0
    for index, (event, persisted) in enumerate(zip(live, replay)):
        message, step = event["message"], persisted["content"]
        label = f"wire[{index}]"
        if event.get("task_id") != fixture["taskId"] or step.get("task_id") != fixture["taskId"]:
            failures.append(label + ": task identity differs")
        sequence, message_index = persisted.get("seq"), step.get("message_index")
        if type(sequence) is not int or not previous_seq < sequence <= 2**53 - 1:
            failures.append(label + ": invalid persistent sequence")
        else:
            previous_seq = sequence
        if (type(message_index) is not int or not previous_index < message_index <= 2**53 - 1
                or type(event.get("message_index")) is not int or event.get("message_index") != message_index):
            failures.append(label + ": invalid message index")
        else:
            previous_index = message_index
        if persisted.get("event_type") != "subagent.step" or event.get("type") != "task_running":
            failures.append(label + ": incorrect event type")
        if message.get("type") not in ["ai", "tool"] or message.get("type") != step.get("kind"):
            failures.append(label + ": incorrect message kind")
        if type(message.get("truncated")) is not bool or type(step.get("truncated")) is not bool:
            failures.append(label + ": text truncation flag must be present and boolean")
        if message.get("truncated") != step.get("truncated"):
            failures.append(label + ": text truncation flags differ")
        for lane, text in [("live", message.get("content")), ("replay", step.get("text"))]:
            if not isinstance(text, str) or len(text) > LIMIT:
                failures.append(label + f": {lane} text exceeds the code-point limit")
        if message.get("content") != step.get("text"):
            failures.append(label + ": wire text differs")
        if not json_equal(message.get("tool_calls", []), step.get("tool_calls", [])):
            failures.append(label + ": wire Tool calls or their metadata differ")
        for call in step.get("tool_calls", []):
            flag, args = call.get("args_truncated"), call.get("args")
            if "args_truncated" in call and type(flag) is not bool:
                failures.append(label + ": argument truncation flag must be boolean")
            if flag is True:
                if not isinstance(args, str) or len(args) > LIMIT:
                    failures.append(label + ": truncated arguments must be a bounded string")
            elif not isinstance(args, dict):
                failures.append(label + ": untruncated arguments must remain structured")
            for source_index, source in enumerate(sources["calls"]):
                if call.get("name") == source["name"]:
                    if flag is not True or args not in serialized_prefixes(source["args"]):
                        failures.append(label + ": large arguments do not match the original Provider prefix/flag")
                    seen_calls.add(source_index)
        for source_index, text in enumerate(sources["texts"]):
            # A Provider's final long answer is the fixture's final AI message.
            if index == len(replay) - 1 and step.get("kind") == "ai":
                if step.get("text") != text[:LIMIT] or step.get("truncated") is not True:
                    failures.append(label + ": large text does not match the original Provider prefix/flag")
                seen_texts.add(source_index)
    if len(seen_texts) != len(sources["texts"]) or len(seen_calls) != len(sources["calls"]):
        failures.append("Large original Provider outputs have no corresponding wire step")
    return failures


def coverage_failures(groups: list) -> list[str]:
    failures = []
    if len(groups) < 4:
        failures.append("Expected primary, large, and two interleaved fixture groups")
    if not any(group["providerLargeTextSources"] for group in groups):
        failures.append("Missing independent large-text Provider evidence")
    if not any(group["providerLargeArgumentSources"] for group in groups):
        failures.append("Missing independent large-argument Provider evidence")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path, required=True, help="Cases directory exported by the Java HTTP tests")
    parser.add_argument("--fixture-source-sha", required=True)
    parser.add_argument("--evidence-root", type=Path, default=Path("local-tmp/slot-1-w08/evidence"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    source = root / "frontend/src/core/tasks/steps.ts"
    assert hashlib.sha256(source.read_bytes()).hexdigest() == FROZEN_HASH, "Frozen frontend source changed"
    # Script-only changes can reuse original HTTP fixtures, but product equality
    # is checked by Git tree, not assumed from an old full-gate PASS.
    changed = subprocess.check_output(["git", "diff", "--name-only", args.fixture_source_sha, "--",
                                       ":(glob)**/src/main/**", ":(glob)**/pom.xml", "pom.xml",
                                       "frontend", "contracts", "third-party"], cwd=root, text=True).strip()
    assert not changed, "Product inputs differ from the fixture-producing source SHA"
    inputs = sorted(args.fixtures.resolve().glob("*/frozen-consumer-*.json"))
    assert inputs, "No frozen consumer fixtures found"
    label = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    out = args.evidence_root.resolve() / ("oracle12-v2-" + label)
    out.mkdir(parents=True)
    groups = []
    for index, original in enumerate(inputs, 1):
        copied = out / f"{index}-fixture-original.json"
        shutil.copy2(original, copied)
        output = out / f"{index}-consumer.json"
        command = ["node", "--experimental-strip-types", str(root / "scripts/w08_frozen_steps.mjs"),
                   str(source), str(copied), str(output)]
        with (out / f"{index}-stdout.log").open("wb") as stdout, (out / f"{index}-stderr.log").open("wb") as stderr:
            subprocess.run(command, cwd=root, stdout=stdout, stderr=stderr, timeout=30, check=True)
        consumed = read_json(output)
        sources = provider_sources(original.parent)
        a = content_failures(consumed["live"], consumed["replay"])
        b = wire_failures(read_json(copied), sources)
        groups.append({"oracle12A": "FAIL" if a else "PASS", "oracle12B": "FAIL" if b else "PASS",
                       "failuresA": a, "failuresB": b, "steps": len(consumed["live"]),
                       "legacyFullEqual": json_equal(consumed["live"], consumed["replay"]),
                       "providerLargeTextSources": len(sources["texts"]),
                       "providerLargeArgumentSources": len(sources["calls"]),
                       "originalFixture": str(original), "fixtureSha256": hashlib.sha256(copied.read_bytes()).hexdigest(),
                       "originalProviderEvidence": sources["evidence"], "command": command})
    coverage = coverage_failures(groups)
    summary = {"contract": CONTRACT, "fixtureSourceSha": args.fixture_source_sha,
               "checkerSourceSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
               "checkerDirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True)),
               "checkerSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
               "frozenSourceSha256": FROZEN_HASH, "groups": groups, "retryCount": 0,
               "coverageFailures": coverage,
               "result": "PASS" if not coverage and all(g["oracle12A"] == g["oracle12B"] == "PASS" for g in groups) else "FAIL"}
    (out / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps({"result": summary["result"], "contract": CONTRACT, "groups": len(groups), "evidence": str(out)}))
    return 0 if summary["result"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())

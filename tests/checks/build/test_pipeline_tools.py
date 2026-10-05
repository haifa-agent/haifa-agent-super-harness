"""Regression for the pipeline helper scripts.

    python -m unittest discover -s tests/checks/build -p "test_*.py"
"""

from __future__ import annotations

import argparse
import importlib.util
import io
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def load(relative: str):
    spec = importlib.util.spec_from_file_location(Path(relative).stem, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


change_scope = load("scripts/governance/change_scope.py")
quick_checks = load("scripts/lib/quick_checks.py")
record_gates = load("scripts/governance/shpm_record_gates.py")
dispatch = load("scripts/governance/shpm_dispatch.py")
gate_cache = load("scripts/lib/frontend_gate_cache.py")
build_cache = load("scripts/lib/frontend_build_cache.py")
pr_review = load("scripts/governance/pr_review_digest.py")
script_regressions = load("scripts/lib/script_regressions.py")
sdk_api_digest = load("scripts/governance/sdk_api_digest.py")
main_service = load("scripts/lib/main_service.py")
gate_resources = load("scripts/lib/gate_resources.py")
source_fingerprint = load("scripts/lib/source_fingerprint.py")
evidence_reuse = load("scripts/governance/evidence_reuse.py")
known_intermittent = load("scripts/lib/known_intermittent.py")
ui_round_plan = load("scripts/lib/ui_round_plan.py")

SUREFIRE_FIXTURES = ROOT / "tests/checks/build/fixtures/surefire"

BOT = pr_review.DEFAULT_BOT


class ChangeScopeTest(unittest.TestCase):
    def test_classifies_paths(self):
        cases = {
            "README.md": "docs",
            "docs/guide.md": "docs",
            "scripts/verify-full.ps1": "scripts",
            "tests/checks/build/verify-build-entry.ps1": "scripts",
            "tests/known-intermittent.json": "scripts",
            "super-harness-server/src/test/java/X.java": "java-test",
            "super-harness-server/src/main/java/X.java": "product",
            "pom.xml": "product",
            "tests/ui/w18-ui-smoke/w05-demo-artifact.spec.cjs": "ui",
            "scripts/ui/run-w18-ui-smoke.ps1": "ui",
            "frontend/package.json": "frontend",
            "contracts/x.json": "product",
        }
        for path, expected in cases.items():
            self.assertEqual(change_scope.classify(path), expected, path)

    def test_gates_follow_the_widest_class(self):
        self.assertEqual(change_scope.required_gates(["README.md"]), ["docs-check"])
        self.assertEqual(change_scope.ui_model_decision(["tests/known-intermittent.json", "scripts/lib/x.py"])[0], "controlled")
        self.assertEqual(change_scope.required_gates(["scripts/governance/x.py"]), ["script-regressions"])
        self.assertEqual(
            change_scope.required_gates(["tests/ui/smoke/x.spec.cjs"]),
            ["verify-full-reuse", "ui-controlled-round-x2"],
        )
        self.assertEqual(
            change_scope.required_gates(["tests/ui/smoke/x-live.spec.cjs"]),
            ["verify-full-reuse", "ui-live-round-x2"],
        )
        self.assertEqual(
            change_scope.required_gates(["scripts/lib/x.ps1"]),
            ["script-regressions", "verify-full-reuse", "ui-controlled-round-x1"],
        )
        self.assertEqual(
            change_scope.required_gates(["super-harness-server/src/test/java/X.java"]),
            ["verify-full"],
        )
        self.assertEqual(
            change_scope.required_gates(["super-harness-server/src/main/java/X.java", "README.md"]),
            ["docs-check", "verify-full", "ui-live-round-x2"],
        )
        self.assertEqual(
            change_scope.required_gates(["frontend/pnpm-lock.yaml"]),
            ["verify-full", "ui-cold-round", "ui-live-round-x2"],
        )

    def test_any_markdown_is_documentation(self):
        self.assertEqual(change_scope.classify("scripts/README.md"), "docs")
        self.assertEqual(change_scope.classify("tests/ui/notes.md"), "docs")


class UiModelDecisionTest(unittest.TestCase):
    def test_controlled_paths(self):
        controlled = [
            "docs/guide.md", "README.md", "AGENTS.md", "scripts/tool.py",
            "tests/checks/build/check.py", "super-harness-server/src/test/java/X.java",
            "tests/ui/w18-ui-smoke/w05-demo-artifact.spec.cjs",
            "tests/ui/smoke/loopback-openai-provider.cjs",
            "docs/engineering/002-feature-delivery-workflows.md",
            "super-harness-server/src/main/java/io/haifa/agent/superharness/server/api/X.java",
        ]
        for path in controlled:
            self.assertEqual(change_scope.ui_model_decision([path])[0], "controlled", path)

    def test_live_paths(self):
        cases = {
            "super-harness-server/src/main/java/io/haifa/agent/superharness/server/run/X.java": "run",
            "super-harness-server/src/main/java/io/haifa/agent/superharness/server/prompt/X.java": "prompt",
            "super-harness-server/src/main/java/io/haifa/agent/superharness/server/tool/X.java": "tool",
            "super-harness-server/src/main/java/io/haifa/agent/superharness/server/config/X.java": "config",
            "super-harness-server/src/main/resources/application.yml": "resources",
            "super-harness-events/src/main/java/X.java": "src/main",
            "pom.xml": "pom.xml",
            "tests/checks/customer-demo-v2/mcp-servers/pom.xml": "pom.xml",
            "tests/ui/smoke/live-deepseek.spec.cjs": "live",
            "scripts/ui/run-live-rounds.ps1": "live",
            "frontend/app/page.tsx": "表里没有的路径",
            "brand-new-dir/x.txt": "表里没有的路径",
        }
        for path, fragment in cases.items():
            model, reason = change_scope.ui_model_decision([path])
            self.assertEqual(model, "live", path)
            self.assertIn(fragment, reason, path)

    def test_sdk_identity_change_and_empty_change_force_live(self):
        model, reason = change_scope.ui_model_decision(["docs/x.md"], sdk_identity_changed=True)
        self.assertEqual(model, "live")
        self.assertIn("SDK", reason)
        model, reason = change_scope.ui_model_decision([])
        self.assertEqual(model, "live")

    def test_one_live_path_overrides_many_controlled_paths(self):
        model, _ = change_scope.ui_model_decision(["docs/x.md", "scripts/y.py", "pom.xml"])
        self.assertEqual(model, "live")


class SecretScanTest(unittest.TestCase):
    def test_reports_rule_and_file_without_the_value(self):
        token = "sk-" + "A" * 30
        diff = "\n".join([
            "+++ b/src/config.txt",
            f"+api_key = '{token}'",
            "+++ b/README.md",
            "+plain text",
            "-removed sk-" + "B" * 30,
        ])
        findings = quick_checks.find_secrets(diff)
        self.assertIn("OpenAI-style secret key in src/config.txt", findings)
        self.assertIn("assigned credential literal in src/config.txt", findings)
        self.assertTrue(all(token not in finding for finding in findings))
        self.assertFalse(any("README.md" in finding for finding in findings))


class RecordGatesTest(unittest.TestCase):
    def ui_dir(self, root, tag, identity, profile, cases, sha="abcdef0123"):
        directory = root / tag
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "flow-health.json").write_text(json.dumps({
            "acceptanceProfile": profile, "sourceSha": sha, "totalMs": 97000, "retryCount": 0,
            "cleanup": {"success": True}, "evidenceRoot": f"D:/evidence/{identity}",
        }), encoding="utf-8")
        (directory / "case-results.json").write_text(json.dumps(cases), encoding="utf-8")
        return directory

    def keys(self, roots):
        records, _ = record_gates.discover([Path(root) for root in roots])
        record_gates.assign_rounds(records)
        return [(r["name"], record_gates.idempotency_key("T", r)) for r in records]

    def test_results_are_copied_without_promotion(self):
        self.assertEqual(record_gates.shpm_result("PASS"), "PASS")
        for failure in ("FAIL", "FAIL_PREFLIGHT", "FAIL_NO_TESTS_MATCHED", "FAIL_MANIFEST"):
            self.assertEqual(record_gates.shpm_result(failure), "FAIL")
        self.assertEqual(record_gates.shpm_result(None), "NOT_RUN")
        self.assertEqual(record_gates.shpm_result("BLOCKED_EXTERNAL"), "BLOCKED_EXTERNAL")

    def test_idempotency_key_has_no_date(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "verify-full-20261005-100000-000.json").write_text(json.dumps({
                "status": "PASS", "sourceSha": "abcdef0123", "forks": 3, "sdkSelection": "ABSENT"}), encoding="utf-8")
            records, _ = record_gates.discover([root])
            record_gates.assign_rounds(records)
            key = record_gates.idempotency_key("T", records[0])
            self.assertEqual(key, "T-verify-full-abcdef01-verify-full-20261005-100000-000")
            self.assertNotIn("2026-10-05", key)

    def test_summaries_are_written_in_time_order(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            failing = root / "verify-full-20261005-100000-000.json"
            passing = root / "verify-full-20261005-110000-000.json"
            failing.write_text(json.dumps({
                "status": "FAIL", "sourceSha": "abcdef0123", "forks": 3, "sdkSelection": "ABSENT"}), encoding="utf-8")
            passing.write_text(json.dumps({
                "status": "PASS", "sourceSha": "abcdef0123", "forks": 3, "sdkSelection": "ABSENT"}), encoding="utf-8")
            for roots in ([passing, failing], [failing, passing]):
                records, _ = record_gates.discover(roots)
                record_gates.assign_rounds(records)
                record_gates.sort_by_time(records)
                self.assertEqual([r["result"] for r in records], ["FAIL", "PASS"])

    def test_reads_verify_full_and_ui_with_profile_in_the_name(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            summary = root / "verify-full-1.json"
            summary.write_text(json.dumps({
                "status": "FAIL_PREFLIGHT", "exitCode": 1, "sourceSha": "abcdef0123",
                "preflightFailures": ["git diff --check: x"],
            }), encoding="utf-8")
            ui = self.ui_dir(root, "run", "run-20261005-100000-000-aaaaaaaa", "w05-demo-artifact", [
                {"name": "Artifact survives reload", "result": "PASS", "firstFailure": None},
                {"name": "Second case", "result": "FAIL", "firstFailure": "timeout"},
            ])
            gate = record_gates.records_for(summary)
            self.assertEqual([r["result"] for r in gate], ["FAIL"])
            self.assertIn("preflight", gate[0]["summary"])
            self.assertEqual(gate[0]["identity"], "verify-full-1")
            cases = record_gates.records_for(ui)
            self.assertEqual([r["result"] for r in cases], ["PASS", "FAIL"])
            record_gates.assign_rounds(cases)
            self.assertEqual(cases[0]["name"], "ui-w05-demo-artifact-artifact-survives-reload")
            self.assertIn("first failure: timeout", cases[1]["summary"])
            command = record_gates.build_command("W05-task-1", cases[1], "db")
            self.assertEqual(command[command.index("--result") + 1], "FAIL")

    def test_two_rounds_get_distinct_names_and_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            early = self.ui_dir(root, "a", "run-20261005-100000-000-aaaaaaaa", "w05-demo-artifact",
                                [{"name": "Case One", "result": "FAIL", "firstFailure": None}])
            late = self.ui_dir(root, "b", "run-20261005-110000-000-bbbbbbbb", "w05-demo-artifact",
                               [{"name": "Case One", "result": "PASS", "firstFailure": None}])
            rows = dict(self.keys([late, early]))
            self.assertEqual(set(rows), {"ui-w05-demo-artifact-case-one-r1", "ui-w05-demo-artifact-case-one-r2"})
            self.assertIn("run-20261005-100000-000-aaaaaaaa", rows["ui-w05-demo-artifact-case-one-r1"])
            self.assertIn("run-20261005-110000-000-bbbbbbbb", rows["ui-w05-demo-artifact-case-one-r2"])

    def test_same_directory_is_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            ui = self.ui_dir(root, "run", "run-20261005-100000-000-aaaaaaaa", "p", [{"name": "C", "result": "PASS"}])
            self.assertEqual(self.keys([ui]), self.keys([ui]))

    def test_different_profiles_do_not_collide(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            one = self.ui_dir(root, "a", "run-20261005-100000-000-aaaaaaaa", "p1", [{"name": "Same", "result": "PASS"}])
            two = self.ui_dir(root, "b", "run-20261005-100000-000-bbbbbbbb", "p2", [{"name": "Same", "result": "PASS"}])
            names = {name for name, _ in self.keys([one, two])}
            self.assertEqual(names, {"ui-p1-same", "ui-p2-same"})

    def test_recursively_discovers_the_four_kinds(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "full-gate").mkdir()
            (root / "full-gate" / "verify-full-1.json").write_text(json.dumps({
                "status": "PASS", "sourceSha": "abcdef0123", "forks": 1, "sdkSelection": "ABSENT"}), encoding="utf-8")
            (root / "scripts").mkdir()
            (root / "scripts" / "verify-scripts-1.json").write_text(json.dumps({
                "status": "PASS", "sourceSha": "abcdef0123", "logDirectory": "d",
                "checks": [{"name": "node-x", "status": "PASS", "exitCode": 0, "elapsedMs": 100, "log": "l"}]}),
                encoding="utf-8")
            self.ui_dir(root, "ui/x/original-evidence", "run-20261005-100000-000-aaaaaaaa", "p",
                        [{"name": "C", "result": "FAIL"}])
            (root / "service").mkdir()
            (root / "service" / "refresh-1.json").write_text(json.dumps({
                "status": "PASS", "sourceSha": "abcdef0123", "jarSha256": "aa", "steps": []}), encoding="utf-8")
            (root / "unknown.json").write_text(json.dumps({"anything": True}), encoding="utf-8")

            records, unrecognized = record_gates.discover([root])
            self.assertEqual(len(records), 4)
            self.assertEqual({r["base_name"] for r in records},
                             {"verify-full", "scripts-node-x", "ui-p-c", "main-service-refresh"})
            self.assertEqual(unrecognized, [root / "unknown.json"])
            record_gates.assign_rounds(records)
            results = {r["base_name"]: r["result"] for r in records}
            self.assertEqual(results, {"verify-full": "PASS", "scripts-node-x": "PASS",
                                       "ui-p-c": "FAIL", "main-service-refresh": "PASS"})

    def test_known_intermittent_records_pass_and_a_per_issue_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            summary = root / "verify-full-20261005-100000-000.json"
            summary.write_text(json.dumps({
                "status": "PASS", "sourceSha": "abcdef0123", "forks": 1, "sdkSelection": "ABSENT",
                "knownIntermittent": {
                    "reruns": 3,
                    "entries": [{"test": "X", "module": "m", "issue": "W04-X",
                                 "firstFailureEvidence": "local-tmp/verify-full/x-first-fail"}],
                },
            }), encoding="utf-8")
            records = record_gates.records_for(summary)
            record_gates.assign_rounds(records)
            self.assertEqual([record["result"] for record in records], ["PASS", "FAIL"])
            self.assertEqual(records[0]["retry"], 3)
            self.assertIn("W04-X", records[0]["summary"])
            issue = records[1]
            self.assertEqual(issue["base_name"], "known-intermittent-W04-X")
            self.assertFalse(issue["required"])
            self.assertIn("first-fail", issue["raw"])
            command = record_gates.build_command("T", issue, "db")
            self.assertNotIn("--required", command)
            primary = record_gates.build_command("T", records[0], "db")
            self.assertIn("--required", primary)

    def test_reuse_summary_is_described_in_the_record(self):
        with tempfile.TemporaryDirectory() as directory:
            summary = Path(directory) / "verify-full-20261005-100000-000.json"
            summary.write_text(json.dumps({
                "status": "PASS", "sourceSha": "abcdef0123", "forks": 1, "sdkSelection": "ABSENT",
                "reuse": {"fromSummary": "old.json", "fromSourceSha": "0123456789", "fingerprint": "fp"},
            }), encoding="utf-8")
            records = record_gates.records_for(summary)
            self.assertIn("reused from old.json", records[0]["summary"])
            self.assertIn("fp", records[0]["summary"])

    def test_missing_acceptance_profile_stops(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            ui = self.ui_dir(root, "run", "run-20261005-100000-000-aaaaaaaa", "p", [{"name": "C", "result": "PASS"}])
            flow = json.loads((ui / "flow-health.json").read_text(encoding="utf-8"))
            del flow["acceptanceProfile"]
            (ui / "flow-health.json").write_text(json.dumps(flow), encoding="utf-8")
            with self.assertRaises(record_gates.EvidenceError):
                record_gates.discover([ui])

    def test_raw_root_rewrites_existing_paths_and_rejects_missing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            ui = self.ui_dir(root, "ui/run", "run-20261005-100000-000-aaaaaaaa", "p", [{"name": "C", "result": "PASS"}])
            records, _ = record_gates.discover([root])
            moved = root / "moved"
            (moved / "ui").mkdir(parents=True)
            (moved / "ui" / "run").mkdir()
            record_gates.apply_raw_root(records, moved)
            self.assertEqual(records[0]["raw"], str(moved / "ui" / "run"))
            with self.assertRaises(record_gates.EvidenceError):
                record_gates.apply_raw_root(records, root / "does-not-exist")


class ScriptRegressionsTest(unittest.TestCase):
    def test_manifest_matches_the_repository(self):
        self.assertEqual(script_regressions.manifest_errors(ROOT), [])

    def test_extra_check_file_fails_before_any_check_runs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "tests/checks/build").mkdir(parents=True)
            (root / "tests/checks/ui").mkdir(parents=True)
            (root / "tests/checks/build/verify-haifa-version-baseline.ps1").write_text("# x", encoding="utf-8")
            (root / "tests/checks/ui/unlisted.test.cjs").write_text("// x", encoding="utf-8")
            errors = script_regressions.manifest_errors(root)
            self.assertEqual(script_regressions.aggregate_status([], errors), "FAIL_MANIFEST")
            self.assertTrue(any("not listed" in error for error in errors))
            self.assertTrue(any("does not exist" in error for error in errors))

    def test_one_failure_fails_the_run_and_others_still_count(self):
        checks = [{"name": "a", "status": "PASS"}, {"name": "b", "status": "FAIL"}]
        self.assertEqual(script_regressions.aggregate_status(checks, []), "FAIL")
        self.assertEqual(script_regressions.aggregate_status([{"name": "a", "status": "PASS"}], []), "PASS")
        self.assertEqual(script_regressions.aggregate_status([], []), "FAIL")

    def test_run_check_logs_output_and_marks_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            run_dir = root / "run"
            run_dir.mkdir()
            check = {"name": "demo", "kind": "node", "path": "tests/checks/ui/x.test.cjs"}

            def fake_runner(command, **kwargs):
                return subprocess.CompletedProcess(command, 3, stdout="out", stderr="err")

            result = script_regressions.run_check(check, root, run_dir, runner=fake_runner)
            self.assertEqual(result["status"], "FAIL")
            self.assertEqual(result["exitCode"], 3)
            self.assertIn("out", (run_dir / "demo.log").read_text(encoding="utf-8"))


class DispatchTest(unittest.TestCase):
    def test_builds_idempotent_steps_in_order(self):
        args = argparse.Namespace(
            task="W05-task-9", wp="W05", title="Title", prompt="private-docs/p.md", exit="Exit", priority="P1",
            next="Next", stage="V2/M2", executor="opencode", ref="ref", model="deepseek/deepseek-flash",
            branch="codex/x", worktree="D:/w", slot=2, data_root="local-tmp/d", db="db",
        )
        steps = dispatch.build_steps(args, Path("D:/repo"), "sha", "2026-10-03")
        self.assertEqual([step[:2] for step in steps],
                         [["task", "create"], ["sprint", "link"], ["task", "start"], ["assign", "create"]])
        assign = steps[-1]
        self.assertEqual(assign[assign.index("--ports") + 1], "30618/35618")
        self.assertTrue(all("--idempotency-key" in step and "--json" in step for step in steps))


class FrontendGateCacheTest(unittest.TestCase):
    def test_key_is_stable_and_changes_with_file_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            for relative, text in {
                "frontend/a.txt": "a", "third-party/deerflow/m.json": "{}",
                "scripts/frontend/verify-deerflow-frontend.ps1": "# verifier",
            }.items():
                (repo / relative).parent.mkdir(parents=True, exist_ok=True)
                (repo / relative).write_text(text, encoding="utf-8")
            for command in (["init", "-q"], ["config", "core.autocrlf", "false"], ["add", "-A"],
                            ["-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-q", "-m", "seed"]):
                subprocess.run(["git", "-C", str(repo), *command], check=True, capture_output=True)

            first = gate_cache.cache_key(repo, None)
            self.assertIsNotNone(first)
            self.assertEqual(gate_cache.cache_key(repo, None), first)

            (repo / "frontend" / "untracked.txt").write_text("x", encoding="utf-8")
            self.assertIsNone(gate_cache.cache_key(repo, None))
            (repo / "frontend" / "untracked.txt").unlink()

            target = repo / "frontend" / "a.txt"
            stat = target.stat()
            import os
            os.utime(target, ns=(stat.st_atime_ns, stat.st_mtime_ns + 10_000_000_000))
            self.assertNotEqual(gate_cache.cache_key(repo, None), first)


class FrontendBuildCacheTest(unittest.TestCase):
    def test_cache_requires_non_static_build_profile(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            frontend = repo / "frontend"
            frontend.mkdir()
            (frontend / "pnpm-lock.yaml").write_text("lockfile", encoding="utf-8")
            with patch.object(build_cache, "run", side_effect=lambda *args: "" if "status" in args else "value"):
                with patch.dict(build_cache.os.environ, {"NEXT_PUBLIC_STATIC_WEBSITE_ONLY": "true"}):
                    self.assertIsNone(build_cache.cache_key(repo, "http://127.0.0.1:11619"))
                with patch.dict(build_cache.os.environ, {"NEXT_PUBLIC_STATIC_WEBSITE_ONLY": "false"}):
                    self.assertIsNotNone(build_cache.cache_key(repo, "http://127.0.0.1:11619"))

    def test_restore_copies_complete_build_and_removes_previous_target(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "shared" / "key" / ".next"
            (source / "server" / "app").mkdir(parents=True)
            (source / "BUILD_ID").write_text("reference", encoding="utf-8")
            (source / "server" / "app" / "route.js").write_bytes(b"frozen route")
            repo = root / "repo"
            target = repo / "frontend" / ".next"
            target.mkdir(parents=True)
            (target / "obsolete.js").write_bytes(b"old build")
            with patch.object(build_cache, "cache_key", return_value="key"), \
                    patch.object(build_cache, "cache_root", return_value=root / "shared"):
                self.assertEqual(build_cache.restore(repo, "http://127.0.0.1:11619"), 0)
            self.assertEqual((target / "BUILD_ID").read_text(encoding="utf-8"), "reference")
            self.assertEqual((target / "server" / "app" / "route.js").read_bytes(), b"frozen route")
            self.assertFalse((target / "obsolete.js").exists())
            self.assertEqual((source / "server" / "app" / "route.js").read_bytes(), b"frozen route")


class PrReviewDigestTest(unittest.TestCase):
    def bot_comment(self, commit, body, reply_to=None, line=10):
        return {
            "user": {"login": BOT}, "commit_id": commit, "body": body, "path": "src/X.java",
            "line": line, "original_line": line, "in_reply_to_id": reply_to, "html_url": f"https://x/{line}",
        }

    def thumbs_up(self, created="2026-10-05T02:38:37Z"):
        return {"content": "+1", "user": {"login": BOT}, "created_at": created, "html_url": "https://x/r"}

    def test_three_verdicts(self):
        head = "a" * 40
        self.assertEqual(pr_review.compute_verdict(head, [], [self.thumbs_up()], BOT), "CLEAN")
        self.assertEqual(pr_review.compute_verdict(head, [], [], BOT), "NO_REVIEW_YET")
        finding = self.bot_comment(head, "**<sub>![P2 Badge](https://img.shields.io/badge/P2-yellow)</sub> Problem**\n\nDetails")
        self.assertEqual(pr_review.compute_verdict(head, [finding], [], BOT), "FINDINGS")
        self.assertEqual(pr_review.compute_verdict(head, [finding], [self.thumbs_up()], BOT), "FINDINGS")

    def summary_comment(self, status, commit, trigger):
        return {"user": {"login": BOT}, "created_at": "2026-10-05T06:23:54Z", "body": (
            "<!-- codex-pull-request-review-summary -->\n\n## Codex Review Summary\n\n"
            "| Review | Status | Commit | Review trigger |\n| --- | --- | --- | --- |\n"
            f"| \U0001F4DD **Code Review** | \u26A0\uFE0F **{status}** "
            '<relative-time datetime="2026-10-05T06:24:53.490867Z">2026-10-05T06:24:53.490867Z</relative-time> '
            f"| `{commit}` | {trigger} |\n")}

    def test_review_summary_rows_and_failed_review(self):
        head = "de233fb" + "0" * 33
        failed = pr_review.review_summary([self.summary_comment("Failed", "de233fb", "Draft marked ready")], BOT)
        self.assertEqual(failed, [{"review": "Code Review", "status": "Failed",
                                   "at": "2026-10-05T06:24:53.490867Z", "commit": "de233fb",
                                   "trigger": "Draft marked ready"}])
        self.assertEqual(pr_review.compute_verdict(head, [], [], BOT, failed), "REVIEW_FAILED")
        # A thumbs up or a comment on the current head still wins over a failed attempt.
        self.assertEqual(pr_review.compute_verdict(head, [], [self.thumbs_up()], BOT, failed), "CLEAN")
        self.assertEqual(pr_review.compute_verdict(head, [self.bot_comment(head, "**t**")], [], BOT, failed), "FINDINGS")
        completed = pr_review.review_summary([self.summary_comment("Completed", "33fa688", "PR opened")], BOT)
        self.assertEqual(completed[0]["status"], "Completed")
        self.assertEqual(pr_review.compute_verdict(head, [], [], BOT, completed), "NO_REVIEW_YET")
        self.assertEqual(pr_review.review_summary([{"user": {"login": "someone"}, "body": "plain"}], BOT), [])

    def test_digest_reports_whether_the_latest_review_covers_the_head(self):
        head = "de233fb" + "0" * 33
        pull = {"number": 17, "draft": False, "head": {"sha": head}, "base": {"repo": {"full_name": "o/r"}}}
        covered = pr_review.build_digest(pull, [], [], BOT, [self.summary_comment("Failed", "de233fb", "Draft marked ready")])
        self.assertEqual(covered["botVerdict"], "REVIEW_FAILED")
        self.assertTrue(covered["reviewedHead"])
        stale = pr_review.build_digest(pull, [], [self.thumbs_up()], BOT, [self.summary_comment("Completed", "33fa688", "PR opened")])
        self.assertEqual(stale["botVerdict"], "CLEAN")
        self.assertFalse(stale["reviewedHead"])

    def test_human_replies_are_not_findings_but_block_clean_only_when_bot_authored(self):
        head = "a" * 40
        bot = self.bot_comment(head, "**title**\n\nbody")
        bot_reply = self.bot_comment(head, "reply", reply_to=1)
        human = {"user": {"login": "someone"}, "commit_id": head, "body": "human", "in_reply_to_id": None}
        self.assertEqual(len(pr_review.bot_findings([bot, bot_reply, human], head, BOT)), 1)
        self.assertEqual(pr_review.compute_verdict(head, [human], [self.thumbs_up()], BOT), "CLEAN")

    def test_outdated_flag_tracks_the_head(self):
        head = "a" * 40
        self.assertTrue(pr_review.finding(self.bot_comment("b" * 40, "old"), head)["outdated"])
        self.assertFalse(pr_review.finding(self.bot_comment(head, "new"), head)["outdated"])

    def test_priority_and_first_line(self):
        body = "**<sub><sub>![P1 Badge](https://img.shields.io/badge/P1-orange?style=flat)</sub></sub>  Redact only the value**\n\nMore."
        self.assertEqual(pr_review.parse_priority(body), "P1")
        self.assertIsNone(pr_review.parse_priority("**No badge**\n\ntext"))
        self.assertEqual(pr_review.first_line(body), "Redact only the value")
        self.assertEqual(pr_review.first_line("plain first line"), "plain first line")

    def test_digest_counts_bot_findings_only(self):
        head = "a" * 40
        digest = pr_review.build_digest(
            {"number": 9, "draft": False, "head": {"sha": head},
             "base": {"repo": {"full_name": "o/r"}}},
            [self.bot_comment(head, "**t**\n\nb")], [self.thumbs_up()], BOT)
        self.assertEqual(digest["botVerdict"], "FINDINGS")
        self.assertEqual(digest["counts"]["findings"], 1)
        self.assertEqual(digest["headSha"], head)


class SdkApiDigestTest(unittest.TestCase):
    VERSION = "0.1.2-SNAPSHOT"
    BOM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>io.haifa</groupId>
    <artifactId>haifa-agent-bom</artifactId>
    <version>{version}</version>
    <packaging>pom</packaging>
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>io.haifa</groupId>
                <artifactId>haifa-agent-fixture</artifactId>
                <version>{version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
"""
    WORKTREE_POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>example</groupId>
    <artifactId>fixture-worktree</artifactId>
    <version>1.0.0</version>
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>io.haifa</groupId>
                <artifactId>haifa-agent-bom</artifactId>
                <version>${{haifa.version}}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
"""

    def git(self, repo, *args):
        subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True)

    @classmethod
    def setUpClass(cls):
        # Every test needs the same fixture JAR, and a fresh javac/jar JVM pair costs more than
        # the rest of each test. Build the bytes once for the whole class and copy them per test.
        template = Path(tempfile.mkdtemp())
        try:
            sources = template / "src/fixture"
            sources.mkdir(parents=True)
            (sources / "Sample.java").write_text(
                "package fixture;\npublic record Sample(int a, String b) {}\n", encoding="utf-8")
            (sources / "Widget.java").write_text(
                "package fixture;\npublic class Widget {\n"
                "    public void visible() {}\n    protected void guarded() {}\n    void hidden() {}\n"
                "    public static class Nested { public int n; }\n"
                "    public static Runnable anonymous() { return new Runnable() { public void run() {} }; }\n}\n",
                encoding="utf-8")
            (sources / "Hidden.java").write_text(
                "package fixture;\nclass Hidden { public void x() {} }\n", encoding="utf-8")
            classes = template / "classes"
            classes.mkdir()
            subprocess.run(["javac", "-d", str(classes), *[str(p) for p in sorted(sources.glob("*.java"))]], check=True)
            jar = template / "fixture.jar"
            subprocess.run(["jar", "--create", "--file", str(jar), "-C", str(classes), "."], check=True)
            cls._fixture_jar_bytes = jar.read_bytes()
        finally:
            shutil.rmtree(template, ignore_errors=True)
        super().setUpClass()

    def fixture(self, root):
        worktree = root / "worktree"
        (worktree / "local-tmp").mkdir(parents=True)
        (worktree / ".gitignore").write_text("local-tmp/\n", encoding="utf-8")
        (worktree / "pom.xml").write_text(self.WORKTREE_POM, encoding="utf-8")
        self.git(worktree, "init", "-q")
        self.git(worktree, "config", "core.autocrlf", "false")
        self.git(worktree, "add", "-A")
        self.git(worktree, "-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-q", "-m", "seed")

        maven = root / "repo"
        bom_dir = maven / "io/haifa/haifa-agent-bom" / self.VERSION
        bom_dir.mkdir(parents=True)
        (bom_dir / f"haifa-agent-bom-{self.VERSION}.pom").write_text(
            self.BOM.format(version=self.VERSION), encoding="utf-8")
        jar_dir = maven / "io/haifa/haifa-agent-fixture" / self.VERSION
        jar_dir.mkdir(parents=True)
        self.jar = jar_dir / f"haifa-agent-fixture-{self.VERSION}.jar"
        self.jar.write_bytes(self._fixture_jar_bytes)
        return worktree, maven

    def test_version_resolution_two_cases(self):
        with tempfile.TemporaryDirectory() as directory:
            worktree = Path(directory)
            selection = worktree / "local-tmp/sdk-selection.json"
            selection.parent.mkdir(parents=True)
            self.assertEqual(sdk_api_digest.read_version(selection, None), self.VERSION)
            selection.write_text(json.dumps({"haifaVersion": "0.1.2-feature-SNAPSHOT"}), encoding="utf-8")
            self.assertEqual(sdk_api_digest.read_version(selection, None), "0.1.2-feature-SNAPSHOT")
            self.assertEqual(sdk_api_digest.read_version(selection, "9.9.9"), "9.9.9")
            selection.write_text(json.dumps({"haifaVersion": "not-a-version"}), encoding="utf-8")
            with self.assertRaises(sdk_api_digest.SdkDigestError):
                sdk_api_digest.read_version(selection, None)

    def test_output_directory_must_be_ignored(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            worktree, _ = self.fixture(root)
            target = sdk_api_digest.output_directory(worktree, self.VERSION)
            self.assertEqual(target, (worktree / "local-tmp/sdk-api" / self.VERSION).resolve())

            bare = root / "bare"
            bare.mkdir()
            self.git(bare, "init", "-q")
            with self.assertRaises(sdk_api_digest.SdkDigestError):
                sdk_api_digest.output_directory(bare, self.VERSION)

    def test_signature_filtering_record_accessors_and_expiry(self):
        with tempfile.TemporaryDirectory() as directory:
            worktree, maven = self.fixture(Path(directory))
            result = sdk_api_digest.generate(worktree, maven, self.VERSION)
            self.assertEqual(result["status"], "GENERATED")
            output = worktree / "local-tmp/sdk-api" / self.VERSION

            widget = (output / "haifa-agent-fixture.api.txt").read_text(encoding="utf-8")
            self.assertIn("public void visible()", widget)
            self.assertIn("protected void guarded()", widget)
            self.assertNotIn("hidden()", widget)

            sample = (output / "haifa-agent-fixture.api.txt").read_text(encoding="utf-8")
            self.assertIn("int a()", sample)
            self.assertIn("java.lang.String b()", sample)

            classes = (output / "classes.txt").read_text(encoding="utf-8")
            self.assertIn("fixture.Sample\thaifa-agent-fixture", classes)
            self.assertIn("fixture.Widget\thaifa-agent-fixture", classes)
            self.assertNotIn("Widget$1", classes)
            self.assertNotIn("fixture.Hidden", classes)

            index = json.loads((output / "index.json").read_text(encoding="utf-8"))
            self.assertEqual(index["artifacts"][0]["artifactId"], "haifa-agent-fixture")
            self.assertEqual(index["artifacts"][0]["length"], self.jar.stat().st_size)
            self.assertGreaterEqual(index["artifacts"][0]["classCount"], 3)

            self.assertEqual(sdk_api_digest.generate(worktree, maven, self.VERSION)["status"], "UNCHANGED")
            with zipfile.ZipFile(self.jar, "a") as archive:
                archive.writestr("fixture/extra.txt", "changed")
            self.assertEqual(sdk_api_digest.generate(worktree, maven, self.VERSION)["status"], "GENERATED")

    def test_batched_javap_matches_per_class_filtering(self):
        with tempfile.TemporaryDirectory() as directory:
            self.fixture(Path(directory))
            names = sdk_api_digest.classes_of(self.jar)
            batched = []
            for batch in sdk_api_digest.batch_class_names(names, batch_size=2, max_command_chars=24000):
                self.assertLessEqual(len(batch), 2)
                sections = sdk_api_digest.split_javap_output(
                    sdk_api_digest.javap_batch(self.jar, batch), batch)
                for name in batch:
                    self.assertIn(name, sections)
                    batched.append((name, sdk_api_digest.is_public_surface(sections[name])))
            individual = [(name, sdk_api_digest.is_public_surface(sdk_api_digest.javap(self.jar, name)))
                          for name in names]
            self.assertEqual(batched, individual)
            surface = {name for name, public in individual if public}
            self.assertIn("fixture.Sample", surface)
            self.assertIn("fixture.Widget", surface)
            self.assertIn("fixture.Widget$Nested", surface)
            self.assertNotIn("fixture.Hidden", surface)

    def test_batch_class_names_respects_count_and_length(self):
        names = [f"fixture.Class{i:02d}" for i in range(250)]
        batches = sdk_api_digest.batch_class_names(names)
        self.assertTrue(all(len(batch) <= 100 for batch in batches))
        self.assertTrue(all(sum(len(name) + 1 for name in batch) <= 24000 for batch in batches))
        self.assertEqual([name for batch in batches for name in batch], names)
        narrow = sdk_api_digest.batch_class_names(["a" * 100, "b" * 100, "c" * 100], max_command_chars=150)
        self.assertEqual([len(batch) for batch in narrow], [1, 1, 1])

    def test_publish_manifest_name_and_sdk_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            _, maven = self.fixture(Path(directory))
            bom = maven / "io/haifa/haifa-agent-bom" / self.VERSION / f"haifa-agent-bom-{self.VERSION}.pom"
            (bom.parent / "haifa-sdk-publish-manifest.json").write_text(
                json.dumps({"sdkCommit": "deadbeef", "version": self.VERSION}), encoding="utf-8")
            self.assertEqual(sdk_api_digest.MANIFEST_NAMES[0], "haifa-sdk-publish-manifest.json")
            self.assertEqual(sdk_api_digest.parse_sdk_commit(bom), "deadbeef")


class MainServiceTest(unittest.TestCase):
    def test_command_line_feature_detection(self):
        jar = "D:\\w\\main-service\\abcdef12\\super-harness-server-0.1.0-SNAPSHOT-exec.jar"
        self.assertTrue(main_service.looks_like_main_service(f'"D:\\jdk\\bin\\java.exe" -jar "{jar}"'))
        self.assertFalse(main_service.looks_like_main_service('java -jar D:\\w\\other-tool.jar'))
        self.assertFalse(main_service.looks_like_main_service(None))
        self.assertFalse(main_service.looks_like_main_service('pwsh -File start.ps1'))
        self.assertTrue(main_service.looks_like_main_service(f'java -jar "{jar}"', jar_path="D:\\w\\main-service\\abcdef12\\super-harness-server-0.1.0-SNAPSHOT-exec.jar"))

    def test_jar_path_from_command_line(self):
        jar = "D:\\a b\\main-service\\abcdef12\\super-harness-server-0.1.0-SNAPSHOT-exec.jar"
        self.assertEqual(main_service.jar_from_command_line(f'java -jar "{jar}" --x'), jar)
        self.assertIsNone(main_service.jar_from_command_line("java -jar other.jar"))

    def test_build_stamp_check(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "super-harness-server-0.1.0-SNAPSHOT-exec.jar"
            jar.write_bytes(b"jar")
            with self.assertRaises(main_service.MainServiceError):
                main_service.stamp_source_sha(jar)
            (Path(directory) / "haifa-build-stamp.json").write_text(
                json.dumps({"sourceSha": "abc123", "haifaVersion": "0.1.2-SNAPSHOT"}), encoding="utf-8")
            self.assertEqual(main_service.stamp_source_sha(jar), "abc123")

    def test_rollback_decision(self):
        self.assertEqual(main_service.rollback_decision(True, False), "keep")
        self.assertEqual(main_service.rollback_decision(False, True), "rollback")
        self.assertEqual(main_service.rollback_decision(False, False), "stop")

    def test_scope_enforcement(self):
        with tempfile.TemporaryDirectory() as directory:
            worktree = Path(directory).resolve()
            fixed_data = worktree / "local-tmp/main-service-data"
            main_service.validate_scope(worktree, 18018, 33018, fixed_data, worktree / "local-tmp/main-service", False)
            with self.assertRaises(main_service.MainServiceError):
                main_service.validate_scope(worktree, 20618, 25618, fixed_data, worktree / "local-tmp/main-service", False)
            with self.assertRaises(main_service.MainServiceError):
                main_service.validate_scope(worktree, 18018, 33018, worktree / "other-data", worktree / "local-tmp/main-service", False)
            slot_data = worktree / "local-tmp/slot-1/data"
            main_service.validate_scope(worktree, 20618, 25618, slot_data, worktree / "local-tmp/main-service", True)
            with self.assertRaises(main_service.MainServiceError):
                main_service.validate_scope(worktree, 20618, 25618, Path("C:/outside"), worktree / "local-tmp/main-service", True)

    def test_stamp_matches_same_commit_or_same_tree(self):
        self.assertEqual(main_service.stamps_match("a", "a", None, None), (True, False))
        self.assertEqual(main_service.stamps_match("a", "b", "tree", "tree"), (True, True))
        self.assertEqual(main_service.stamps_match("a", "b", "t1", "t2"), (False, False))
        self.assertEqual(main_service.stamps_match("a", "b", None, "tree"), (False, False))
        self.assertEqual(main_service.stamps_match("a", "b", "tree", None), (False, False))

    def test_frontend_tree_resolves_the_subtree_of_a_commit(self):
        repo = Path(__file__).resolve().parents[3]
        head = main_service.rev_parse(repo, "HEAD")
        subtree = main_service.frontend_tree_of(repo, head)
        self.assertRegex(subtree or "", r"^[0-9a-f]{40}$")
        self.assertEqual(subtree, main_service.rev_parse(repo, "HEAD:frontend"))
        self.assertNotEqual(subtree, main_service.tree_of(repo, head))
        self.assertIsNone(main_service.frontend_tree_of(repo, "0" * 40))

    def test_frontend_decision_branches(self):
        self.assertEqual(main_service.frontend_decision(True, False, False, False), "FAIL_NO_ROOT")
        self.assertEqual(main_service.frontend_decision(False, True, True, False), "REUSED")
        self.assertEqual(main_service.frontend_decision(False, True, False, True), "FAIL_TREE")
        self.assertEqual(main_service.frontend_decision(True, True, False, True), "REUSED")
        self.assertEqual(main_service.frontend_decision(True, False, True, True), "FAIL_FOREIGN")
        self.assertEqual(main_service.frontend_decision(True, False, False, True), "START")

    def test_frontend_start_args_match_the_production_launcher(self):
        launcher = Path("C:/fr/scripts/frontend/start-haifa-frontend.ps1")
        args = main_service.frontend_start_args(launcher, Path("C:/fr"), Path("D:/w/deer-flow"),
                                                Path("D:/w/slot/data"), "http://127.0.0.1:20618", 25618)
        self.assertEqual(args[0], "pwsh")
        self.assertEqual(args[args.index("-File") + 1], str(launcher))
        self.assertEqual(args[args.index("-RepositoryRoot") + 1], str(Path("C:/fr")))
        self.assertEqual(args[args.index("-SourceRepository") + 1], str(Path("D:/w/deer-flow")))
        self.assertEqual(args[args.index("-DataDirectory") + 1], str(Path("D:/w/slot/data")))
        self.assertEqual(args[args.index("-GatewayBaseUrl") + 1], "http://127.0.0.1:20618")
        self.assertEqual(args[args.index("-FrontendPort") + 1], "25618")
        self.assertIn("-Production", args)

    def test_evaluate_stream_answer_error_empty_and_tools_only(self):
        answer = ('event: messages\n'
                  'data: [{"type":"ai","content":"pong"},{"langgraph_node":"model"}]\n\n'
                  'event: end\ndata:\n\n')
        self.assertEqual(main_service.evaluate_stream(answer), {"hasAnswer": True, "hasError": False})
        error = 'event: error\ndata: {"message":"boom"}\n\n'
        self.assertEqual(main_service.evaluate_stream(error), {"hasAnswer": False, "hasError": True})
        self.assertEqual(main_service.evaluate_stream(""), {"hasAnswer": False, "hasError": False})
        tools_only = ('event: messages\n'
                      'data: [{"type":"ai","content":"","tool_calls":[{"name":"read_file"}]},{}]\n\n')
        self.assertEqual(main_service.evaluate_stream(tools_only), {"hasAnswer": False, "hasError": False})
        parts = 'event: messages\ndata: [{"type":"ai","content":[{"type":"text","text":"hi"}]},{}]\n\n'
        self.assertEqual(main_service.evaluate_stream(parts), {"hasAnswer": True, "hasError": False})

    def test_model_name_picks_the_first_executable_model(self):
        self.assertEqual(main_service.model_name({"models": [{"name": "first"}, {"name": "second"}]}), "first")
        self.assertIsNone(main_service.model_name({"models": []}))
        self.assertIsNone(main_service.model_name({"models": [{"display": "x"}]}))
        self.assertIsNone(main_service.model_name(None))


class GateResourcesTest(unittest.TestCase):
    def test_high_memory_selects_three_forks(self):
        self.assertEqual(gate_resources.select_forks(16000, 32000), (3, False))
        self.assertEqual(
            gate_resources.select_forks(gate_resources.THREE_FORKS_MIN_FREE_PHYSICAL_MB,
                                        gate_resources.THREE_FORKS_MIN_FREE_COMMIT_MB), (3, False))

    def test_there_is_no_two_fork_tier(self):
        # Two forks need at least as much memory as three (measured), so the choice is 3 or 1.
        for physical in range(1024, 8192, 512):
            for commit in range(2048, 16384, 1024):
                self.assertIn(gate_resources.select_forks(physical, commit)[0], (1, 3))
        self.assertEqual(gate_resources.select_forks(4623, 10851), (3, False),
                         "the lowest free memory at which a three-fork gate was observed to pass")

    def test_low_memory_selects_one_fork_with_low_memory(self):
        self.assertEqual(gate_resources.select_forks(1024, 2048), (1, True))
        self.assertEqual(
            gate_resources.select_forks(gate_resources.THREE_FORKS_MIN_FREE_PHYSICAL_MB - 1, 32000), (1, True))
        self.assertEqual(
            gate_resources.select_forks(16000, gate_resources.THREE_FORKS_MIN_FREE_COMMIT_MB - 1), (1, True))

    def test_missing_probe_falls_back_to_default(self):
        self.assertEqual(gate_resources.select_forks(None, 32000), (gate_resources.DEFAULT_FORKS, False))
        self.assertEqual(gate_resources.select_forks(16000, None), (gate_resources.DEFAULT_FORKS, False))

    def test_selection_shape(self):
        result = gate_resources.selection()
        self.assertIn(result["forks"], (1, 3))
        self.assertIsInstance(result["lowMemory"], bool)
        self.assertIn("probeAvailable", result)


class SourceFingerprintTest(unittest.TestCase):
    VERSION = SdkApiDigestTest.VERSION
    BOM = SdkApiDigestTest.BOM
    WORKTREE_POM = SdkApiDigestTest.WORKTREE_POM

    @classmethod
    def setUpClass(cls):
        # The seed repository is identical for every test; initialising and committing it once
        # and copying it per test removes repeated git init/commit from the class.
        cls._seed_root = Path(tempfile.mkdtemp())
        seed = cls._seed_root / "worktree"
        (seed / "local-tmp").mkdir(parents=True)
        (seed / ".gitignore").write_text("local-tmp/\n", encoding="utf-8")
        (seed / "pom.xml").write_text(cls.WORKTREE_POM, encoding="utf-8")
        for relative, text in {
            "docs/guide.md": "doc", "scripts/tool.py": "print()", "tests/data.txt": "data",
            "AGENTS.md": "rules", "src/App.java": "class App {}",
        }.items():
            (seed / relative).parent.mkdir(parents=True, exist_ok=True)
            (seed / relative).write_text(text, encoding="utf-8")
        for command in (["init", "-q"], ["config", "core.autocrlf", "false"], ["add", "-A"],
                        ["-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-q", "-m", "seed"]):
            subprocess.run(["git", "-C", str(seed), *command], check=True, capture_output=True)
        super().setUpClass()

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls._seed_root, ignore_errors=True)
        super().tearDownClass()

    def git(self, repo, *args):
        subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True)

    def fixture(self, root):
        worktree = root / "worktree"
        shutil.copytree(self._seed_root / "worktree", worktree)

        maven = root / "maven"
        bom_dir = maven / "io/haifa/haifa-agent-bom" / self.VERSION
        bom_dir.mkdir(parents=True)
        (bom_dir / f"haifa-agent-bom-{self.VERSION}.pom").write_text(
            self.BOM.format(version=self.VERSION), encoding="utf-8")
        jar_dir = maven / "io/haifa/haifa-agent-fixture" / self.VERSION
        jar_dir.mkdir(parents=True)
        jar = jar_dir / f"haifa-agent-fixture-{self.VERSION}.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("fixture/Sample.class", b"v1")
        return worktree, maven, jar

    def commit(self, worktree, message):
        self.git(worktree, "add", "-A")
        self.git(worktree, "-c", "user.name=t", "-c", "user.email=t@example.invalid",
                 "commit", "-q", "-m", message)

    def test_exclusion_classification(self):
        for path in ("docs/a.md", "tests/a.txt", "scripts/a.py", "README.md", "AGENTS.md"):
            self.assertTrue(source_fingerprint.is_excluded(path), path)
        for path in ("super-harness-server/src/main/java/X.java", "pom.xml", "frontend/x", "src/App.java"):
            self.assertFalse(source_fingerprint.is_excluded(path), path)

    def test_excluded_edits_keep_the_fingerprint(self):
        with tempfile.TemporaryDirectory() as directory:
            worktree, maven, _ = self.fixture(Path(directory))
            first = source_fingerprint.fingerprint(worktree, "HEAD", maven, self.VERSION, jdk="jdk", maven="mvn")
            for relative in ("docs/guide.md", "scripts/tool.py", "tests/data.txt", "AGENTS.md"):
                (worktree / relative).write_text("changed", encoding="utf-8")
            self.commit(worktree, "docs and tests")
            second = source_fingerprint.fingerprint(worktree, "HEAD", maven, self.VERSION, jdk="jdk", maven="mvn")
            self.assertNotEqual(first["sourceTree"], second["sourceTree"])
            self.assertEqual(first["javaInputFingerprint"], second["javaInputFingerprint"])

            (worktree / "src/App.java").write_text("class App { int x; }", encoding="utf-8")
            self.commit(worktree, "java change")
            third = source_fingerprint.fingerprint(worktree, "HEAD", maven, self.VERSION, jdk="jdk", maven="mvn")
            self.assertNotEqual(first["javaInputFingerprint"], third["javaInputFingerprint"])

    def test_sdk_identity_and_fingerprint_change_when_jar_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            worktree, maven, jar = self.fixture(Path(directory))
            first = source_fingerprint.fingerprint(worktree, "HEAD", maven, self.VERSION, jdk="jdk", maven="mvn")
            with zipfile.ZipFile(jar, "a") as archive:
                archive.writestr("fixture/extra.class", b"v2")
            second = source_fingerprint.fingerprint(worktree, "HEAD", maven, self.VERSION, jdk="jdk", maven="mvn")
            self.assertNotEqual(first["sdkIdentity"], second["sdkIdentity"])
            self.assertNotEqual(first["javaInputFingerprint"], second["javaInputFingerprint"])
            self.assertEqual(first["sourceTree"], second["sourceTree"])

    def test_fingerprint_is_reusable_only_when_clean_and_the_sdk_is_known(self):
        with tempfile.TemporaryDirectory() as directory:
            worktree, maven, _ = self.fixture(Path(directory))
            clean = source_fingerprint.fingerprint(worktree, "HEAD", maven, self.VERSION, jdk="jdk", maven="mvn")
            self.assertTrue(clean["reusable"])
            self.assertEqual(clean["dirtyPaths"], [])
            self.assertIsNone(clean["notReusableReason"])

            (worktree / "docs/guide.md").write_text("dirty doc", encoding="utf-8")
            docs_dirty = source_fingerprint.fingerprint(worktree, "HEAD", maven, self.VERSION, jdk="jdk", maven="mvn")
            self.assertTrue(docs_dirty["reusable"], "an excluded path does not make the build inputs dirty")

            (worktree / "src/App.java").write_text("class App { int y; }", encoding="utf-8")
            dirty = source_fingerprint.fingerprint(worktree, "HEAD", maven, self.VERSION, jdk="jdk", maven="mvn")
            self.assertFalse(dirty["reusable"])
            self.assertEqual(dirty["dirtyPaths"], ["src/App.java"])
            self.assertEqual(dirty["javaInputFingerprint"], clean["javaInputFingerprint"],
                             "the value still describes the commit; the flag says it must not be reused")

            self.git(worktree, "checkout", "-q", "--", "src/App.java")
            empty = Path(directory) / "empty-maven"
            empty.mkdir()
            unknown = source_fingerprint.fingerprint(worktree, "HEAD", empty, self.VERSION, jdk="jdk", maven="mvn")
            self.assertEqual(unknown["sdkIdentity"], "UNAVAILABLE")
            self.assertFalse(unknown["reusable"])
            self.assertEqual(unknown["notReusableReason"], "SDK identity unavailable")

    def test_excluded_references_reads_tracked_files_only(self):
        with tempfile.TemporaryDirectory() as directory:
            worktree, _, _ = self.fixture(Path(directory))
            (worktree / "super-harness-server/src/test/java").mkdir(parents=True)
            (worktree / "super-harness-server/src/test/java/X.java").write_text(
                'String p = "scripts/tool.py";', encoding="utf-8")
            (worktree / "ignored-deps/pkg").mkdir(parents=True)
            (worktree / "ignored-deps/pkg/pom.xml").write_text("<project>docs/guide.md</project>", encoding="utf-8")
            (worktree / ".gitignore").write_text("local-tmp/\nignored-deps/\n", encoding="utf-8")
            self.commit(worktree, "test reference")
            findings = source_fingerprint.excluded_references(worktree)
            self.assertEqual(findings, ["super-harness-server/src/test/java/X.java: scripts/tool.py"])

    def test_covered_dirty_paths_ignores_excluded_edits(self):
        with tempfile.TemporaryDirectory() as directory:
            worktree, _, _ = self.fixture(Path(directory))
            (worktree / "docs/guide.md").write_text("dirty doc", encoding="utf-8")
            (worktree / "src/App.java").write_text("class App { int y; }", encoding="utf-8")
            self.assertEqual(source_fingerprint.covered_dirty_paths(worktree), ["src/App.java"])

    def test_excluded_references_detect_and_real_repo_is_clean(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory) / "repo"
            (repo / "super-harness-server/src/test/java").mkdir(parents=True)
            (repo / "scripts").mkdir()
            (repo / "scripts/tool.py").write_text("print()", encoding="utf-8")
            (repo / "pom.xml").write_text("<project/>", encoding="utf-8")
            (repo / "super-harness-server/src/test/java/X.java").write_text(
                'String p = "scripts/tool.py";', encoding="utf-8")
            findings = source_fingerprint.excluded_references(repo)
            self.assertTrue(any("X.java" in finding for finding in findings), findings)
        self.assertEqual(source_fingerprint.excluded_references(ROOT), [])


class ForceUtf8StdoutTest(unittest.TestCase):
    def test_redirected_stream_is_forced_to_utf8(self):
        # A redirected stdout defaults to the system code page (for example GBK on Chinese
        # Windows), which makes ``ensure_ascii=False`` JSON invalid UTF-8. The helper restores
        # UTF-8 so the bytes decode as UTF-8 again.
        buffer = io.BytesIO()
        stream = io.TextIOWrapper(buffer, encoding="cp1252")
        original = sys.stdout
        sys.stdout = stream
        try:
            quick_checks.force_utf8_stdout()
            sys.stdout.write("证据早于内容判定")
            sys.stdout.flush()
        finally:
            sys.stdout = original
        self.assertEqual(buffer.getvalue().decode("utf-8"), "证据早于内容判定")


class EvidenceReuseTest(unittest.TestCase):
    def test_find_reusable_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def write(name, **fields):
                (root / name).write_text(json.dumps(fields), encoding="utf-8")

            write("verify-full-20261005-100000-000.json", status="PASS", javaInputFingerprint="fp", tests=5)
            write("verify-full-20261005-110000-000.json", status="PASS", javaInputFingerprint="fp",
                  reuse={"fromSummary": "x"})
            write("verify-full-20261005-120000-000.json", status="FAIL", javaInputFingerprint="fp")
            write("verify-full-20261005-130000-000.json", status="PASS", javaInputFingerprint="other")
            write("verify-full-20261005-140000-000.json", status="PASS")
            found = evidence_reuse.find_reusable_summary(root, "fp")
            self.assertEqual(found.name, "verify-full-20261005-100000-000.json")
            self.assertIsNone(evidence_reuse.find_reusable_summary(root, "missing"))
            self.assertIsNone(evidence_reuse.find_reusable_summary(root, None))
            self.assertIsNone(evidence_reuse.find_reusable_summary(root / "does-not-exist", "fp"))

    def test_full_gate_verdicts(self):
        target = {"sourceTree": "tree", "sdkIdentity": "sdk", "javaInputFingerprint": "fp"}
        self.assertEqual(evidence_reuse.evaluate_full_gate(
            {"status": "PASS", "javaInputFingerprint": "fp", "sdkIdentity": "sdk"}, target)[0], "REUSABLE")
        self.assertEqual(evidence_reuse.evaluate_full_gate(
            {"status": "FAIL", "javaInputFingerprint": "fp", "sdkIdentity": "sdk"}, target)[0], "STALE")
        self.assertEqual(evidence_reuse.evaluate_full_gate(
            {"status": "PASS", "javaInputFingerprint": "other", "sdkIdentity": "sdk"}, target)[0], "STALE")
        self.assertEqual(evidence_reuse.evaluate_full_gate({"status": "PASS"}, target),
                         ("STALE", evidence_reuse.STALE_OLD_EVIDENCE))

    def test_tree_evidence_verdicts(self):
        target = {"sourceTree": "tree", "sdkIdentity": "sdk", "javaInputFingerprint": "fp"}
        reusable = {"sourceTree": "tree", "sdkIdentity": "sdk", "sourceDirty": False}
        self.assertEqual(evidence_reuse.evaluate_tree_evidence(reusable, target)[0], "REUSABLE")
        unknown = {"sourceTree": "tree", "sdkIdentity": "UNAVAILABLE", "sourceDirty": False}
        unknown_target = dict(target, sdkIdentity="UNAVAILABLE")
        self.assertEqual(evidence_reuse.evaluate_tree_evidence(unknown, unknown_target)[0], "STALE")
        self.assertEqual(evidence_reuse.evaluate_tree_evidence(unknown, target)[0], "STALE")
        self.assertEqual(evidence_reuse.evaluate_tree_evidence(reusable, unknown_target)[0], "STALE")
        gate = {"status": "PASS", "javaInputFingerprint": target["javaInputFingerprint"], "sdkIdentity": "UNAVAILABLE"}
        self.assertEqual(evidence_reuse.evaluate_full_gate(gate, target)[0], "STALE")
        self.assertEqual(evidence_reuse.evaluate_full_gate(dict(gate, sdkIdentity="sdk"), unknown_target)[0], "STALE")
        self.assertEqual(evidence_reuse.evaluate_tree_evidence(
            {"sourceTree": "tree", "sdkIdentity": "sdk", "sourceDirty": True}, target)[0], "STALE")
        self.assertEqual(evidence_reuse.evaluate_tree_evidence(
            {"sourceTree": "other", "sdkIdentity": "sdk", "sourceDirty": False}, target)[0], "STALE")
        self.assertEqual(evidence_reuse.evaluate_tree_evidence(
            {"sourceTree": "tree", "sdkIdentity": "other", "sourceDirty": False}, target)[0], "STALE")
        self.assertEqual(evidence_reuse.evaluate_tree_evidence({"sourceTree": "tree"}, target),
                         ("STALE", evidence_reuse.STALE_OLD_EVIDENCE))


class KnownIntermittentTest(unittest.TestCase):
    def entries(self):
        return [
            {"test": "DeerFlowUploadRunContextHttpTest", "module": "super-harness-server",
             "issue": "W04-UPLOAD-DUPLICATE-INTERMITTENT",
             "covers": ["super-harness-server/src/main/java/io/haifa/agent/superharness/server/api/DeerFlowUploadContext.java"]},
            {"test": "DeerFlowSubtaskProjectionApiTest#cancellingThreeActualChildrenClosesTwoWaitingRequestsWithoutInventingChildFrames",
             "module": "super-harness-server", "issue": "W08-SUBTASK-CANCEL-INTERMITTENT",
             "covers": ["super-harness-server/src/main/java/io/haifa/agent/superharness/server/api/DeerFlowSubtaskProjection.java"]},
        ]

    def upload_failure(self):
        return {"class": "io.haifa.agent.superharness.server.api.DeerFlowUploadRunContextHttpTest",
                "test": "DeerFlowUploadRunContextHttpTest",
                "method": "duplicateUploadsAndReferencesStayDeterministicAndOriginalsAreImmutable",
                "module": "super-harness-server", "kind": "error", "type": "java.lang.NullPointerException", "message": ""}

    def test_parse_surefire_failure_and_pass(self):
        upload = known_intermittent.parse_surefire_xml(
            SUREFIRE_FIXTURES / "TEST-io.haifa.agent.superharness.server.api.DeerFlowUploadRunContextHttpTest.xml",
            "super-harness-server")
        self.assertEqual(upload["errors"], 1)
        self.assertEqual(len(upload["failed"]), 1)
        self.assertEqual(upload["failed"][0]["method"],
                         "duplicateUploadsAndReferencesStayDeterministicAndOriginalsAreImmutable")
        passing = known_intermittent.parse_surefire_xml(
            SUREFIRE_FIXTURES / "TEST-io.haifa.agent.superharness.server.api.AgentSoulBootstrapHttpTest.xml")
        self.assertEqual(passing["failed"], [])
        failed = known_intermittent.failed_tests([upload])
        self.assertEqual(failed[0]["test"], "DeerFlowUploadRunContextHttpTest")
        self.assertEqual(failed[0]["module"], "super-harness-server")

    def test_reactor_skipped_modules_from_the_log_excerpt(self):
        log = (SUREFIRE_FIXTURES / "reactor-log-excerpt.txt").read_text(encoding="utf-8")
        mapping = {"Super Harness DeerFlow Compatibility Server": "super-harness-server",
                   "Super Harness Evidence Harness": "super-harness-harness"}
        self.assertEqual(known_intermittent.reactor_skipped_modules(log, mapping), ["super-harness-harness"])

    def test_abstract_and_interface_test_types_are_not_report_gaps(self):
        with tempfile.TemporaryDirectory() as directory:
            module = Path(directory) / "super-harness-server"
            tests = module / "src/test/java/pkg"
            tests.mkdir(parents=True)
            (tests / "AbstractBaseApiTest.java").write_text(
                "package pkg;\nabstract class AbstractBaseApiTest { }\n", encoding="utf-8")
            (tests / "ContractTest.java").write_text("package pkg;\npublic interface ContractTest { }\n", encoding="utf-8")
            (tests / "RealApiTest.java").write_text(
                "package pkg;\nclass RealApiTest extends AbstractBaseApiTest { }\n", encoding="utf-8")
            (tests / "Helper.java").write_text("package pkg;\nclass Helper { }\n", encoding="utf-8")
            self.assertEqual(known_intermittent.expected_test_classes(module), {"RealApiTest"})
            self.assertEqual(known_intermittent.module_report_gaps(module, []), ["RealApiTest"])
            self.assertEqual(known_intermittent.module_report_gaps(module, [{"name": "pkg.RealApiTest"}]), [])

    def test_plan_names_targeted_reruns_per_module_and_skipped_module_tests(self):
        entries = self.entries()
        upload = self.upload_failure()
        plan = known_intermittent.intermittent_plan(
            [upload], entries, [], {}, skipped=["super-harness-harness"],
            skipped_tests={"super-harness-harness": ["EvidenceReportTest", "ContractOracleTest"]})
        self.assertTrue(plan["use"])
        self.assertEqual(plan["rerunByModule"],
                         [{"module": "super-harness-server", "classes": ["DeerFlowUploadRunContextHttpTest"]}])
        self.assertEqual(plan["skippedModuleTests"],
                         [{"module": "super-harness-harness", "classes": ["ContractOracleTest", "EvidenceReportTest"]}])
        denied = known_intermittent.intermittent_plan([upload], entries, [], {}, no_known_intermittent=True)
        self.assertEqual(denied["rerunByModule"], [])
        self.assertEqual(denied["skippedModuleTests"], [])

    def test_unknown_change_scope_denies_the_branch(self):
        entries = self.entries()
        upload = self.upload_failure()
        denied = known_intermittent.intermittent_plan([upload], entries, None, {})
        self.assertFalse(denied["use"])
        self.assertIn("change scope", denied["reason"])
        self.assertTrue(known_intermittent.intermittent_plan([upload], entries, [], {})["use"])

    def test_change_scope_on_a_branch_on_the_base_branch_and_without_a_base(self):
        def git(repo, *args):
            subprocess.run(["git", "-C", str(repo), "-c", "user.name=t", "-c", "user.email=t@example.invalid", *args],
                           check=True, capture_output=True)
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory) / "repo"
            repo.mkdir()
            git(repo, "init", "-q", "-b", "main")
            (repo / "a.txt").write_text("a", encoding="utf-8")
            git(repo, "add", "-A"); git(repo, "commit", "-q", "-m", "one")
            (repo / "covered.java").write_text("x", encoding="utf-8")
            git(repo, "add", "-A"); git(repo, "commit", "-q", "-m", "two")
            # On the base branch itself the scope is the last commit, not "nothing".
            files, described = known_intermittent.change_scope(repo, "main")
            self.assertEqual(files, ["covered.java"])
            self.assertIn("last commit", described)
            git(repo, "checkout", "-q", "-b", "feature")
            (repo / "b.txt").write_text("b", encoding="utf-8")
            git(repo, "add", "-A"); git(repo, "commit", "-q", "-m", "three")
            (repo / "untracked.txt").write_text("u", encoding="utf-8")
            files, described = known_intermittent.change_scope(repo, "main")
            self.assertEqual(files, ["b.txt", "untracked.txt"])
            self.assertIn("merge base", described)
            files, described = known_intermittent.change_scope(repo, "no-such-branch")
            self.assertIsNone(files)
            self.assertIn("no merge base", described)

    def test_manifest_loads(self):
        entries = known_intermittent.load_manifest(ROOT / "tests/known-intermittent.json")
        self.assertEqual([entry["issue"] for entry in entries],
                         ["W04-UPLOAD-DUPLICATE-INTERMITTENT", "W08-SUBTASK-CANCEL-INTERMITTENT"])
        self.assertTrue(all("covers" in entry for entry in entries))

    def test_plan_allowed_and_each_denial(self):
        entries = self.entries()
        upload = self.upload_failure()
        allowed = known_intermittent.intermittent_plan(
            [upload], entries, ["super-harness-server/src/main/java/Other.java"], {})
        self.assertTrue(allowed["use"])
        self.assertEqual(allowed["reruns"], 3)
        self.assertEqual(allowed["rerunClasses"], [upload["class"]])

        unregistered = {**upload, "class": "io.x.OtherTest", "test": "OtherTest"}
        self.assertFalse(known_intermittent.intermittent_plan([unregistered], entries, [], {})["use"])
        self.assertFalse(known_intermittent.intermittent_plan([upload, upload, upload], entries, [], {})["use"])
        touched = known_intermittent.intermittent_plan(
            [upload], entries,
            ["super-harness-server/src/main/java/io/haifa/agent/superharness/server/api/DeerFlowUploadContext.java"], {})
        self.assertFalse(touched["use"])
        self.assertIn("covered", touched["reason"])
        gap = known_intermittent.intermittent_plan([upload], entries, [], {"super-harness-server": ["NestedTest"]})
        self.assertFalse(gap["use"])
        self.assertIn("no surefire report", gap["reason"])
        disabled = known_intermittent.intermittent_plan([upload], entries, [], {}, no_known_intermittent=True)
        self.assertFalse(disabled["use"])

    def test_method_qualified_entry_requires_the_method(self):
        entries = self.entries()
        base = {"class": "io.haifa.agent.superharness.server.api.DeerFlowSubtaskProjectionApiTest",
                "test": "DeerFlowSubtaskProjectionApiTest", "module": "super-harness-server",
                "kind": "failure", "type": "java.lang.AssertionError", "message": ""}
        wrong = {**base, "method": "someOtherMethod"}
        self.assertFalse(known_intermittent.intermittent_plan([wrong], entries, [], {})["use"])
        right = {**base, "method": "cancellingThreeActualChildrenClosesTwoWaitingRequestsWithoutInventingChildFrames"}
        self.assertTrue(known_intermittent.intermittent_plan([right], entries, [], {})["use"])

    def test_slow_tagged_methods_do_not_change_module_report_gaps(self):
        # @Tag("slow") removes a method from the default gate, not its class from surefire's
        # includes: the class still runs and reports its untagged cases, so the known-intermittent
        # "every other class has a report" rule is unaffected.
        with tempfile.TemporaryDirectory() as directory:
            module = Path(directory) / "super-harness-server"
            tests = module / "src/test/java/pkg"
            tests.mkdir(parents=True)
            (tests / "MixedApiTest.java").write_text(
                "package pkg;\nclass MixedApiTest {\n"
                "  @Tag(\"slow\") void slowCase() {}\n  void fastCase() {}\n}\n", encoding="utf-8")
            self.assertEqual(known_intermittent.expected_test_classes(module), {"MixedApiTest"})
            self.assertEqual(known_intermittent.module_report_gaps(module, [{"name": "pkg.MixedApiTest"}]), [])
            self.assertEqual(known_intermittent.module_report_gaps(module, []), ["MixedApiTest"])

    def test_module_report_gaps_from_source_files(self):
        with tempfile.TemporaryDirectory() as directory:
            module = Path(directory) / "super-harness-server"
            (module / "src/test/java/a").mkdir(parents=True)
            (module / "src/test/java/a/AlphaTest.java").write_text("", encoding="utf-8")
            (module / "src/test/java/a/BetaTest.java").write_text("", encoding="utf-8")
            (module / "src/test/java/a/Helper.java").write_text("", encoding="utf-8")
            self.assertEqual(known_intermittent.module_report_gaps(module, [{"name": "a.AlphaTest"}]), ["BetaTest"])

    def test_cover_matching(self):
        self.assertTrue(known_intermittent.path_matches_cover(
            "super-harness-server/src/main/java/x/A.java", "super-harness-server/src/main/java/x/A.java"))
        self.assertTrue(known_intermittent.path_matches_cover("a/b/c.java", "a/b/*.java"))
        self.assertFalse(known_intermittent.path_matches_cover("a/bc/c.java", "a/b"))


class UiRoundPlanTest(unittest.TestCase):
    def test_passes_requires_a_warm_round(self):
        ui_round_plan.validate_run(passes=2, skip_build=True)
        with self.assertRaises(ui_round_plan.PlanError):
            ui_round_plan.validate_run(passes=2, skip_build=False)

    def test_passes_rejects_selected_case_and_preflight(self):
        for kwargs in ({"case_title": "X"}, {"preflight_only": True}):
            with self.assertRaises(ui_round_plan.PlanError):
                ui_round_plan.validate_run(passes=2, skip_build=True, **kwargs)

    def test_passes_bounds(self):
        for bad in (0, 6, True):
            with self.assertRaises(ui_round_plan.PlanError):
                ui_round_plan.validate_run(passes=bad, skip_build=True)

    def test_merge_accepts_only_a_shared_startup_class(self):
        ui_round_plan.validate_run(
            profiles=["w05-demo-artifact", "w15-v2-skill", "w15-package-differential"], skip_build=True)
        with self.assertRaises(ui_round_plan.PlanError):
            ui_round_plan.validate_run(profiles=["w05-demo-artifact", "w03-title-suggestions"], skip_build=True)
        with self.assertRaises(ui_round_plan.PlanError):
            ui_round_plan.validate_run(profiles=["w05-demo-artifact"], skip_build=True)
        with self.assertRaises(ui_round_plan.PlanError):
            ui_round_plan.validate_run(profiles=["w05-demo-artifact", "nope"], skip_build=True)

    def test_layout_preserves_the_single_default(self):
        runs = ui_round_plan.run_layout(Path("E"), ["w18-ui-smoke"], 1)
        self.assertEqual(len(runs), 1)
        self.assertEqual(runs[0]["relativeDirectory"], ".")
        self.assertEqual(runs[0]["env"], {"W18_PASS_INDEX": "1"})

    def test_layout_repeats_under_pass_directories(self):
        runs = ui_round_plan.run_layout(Path("E"), ["w18-ui-smoke"], 2)
        self.assertEqual([r["relativeDirectory"] for r in runs], ["pass-1", "pass-2"])
        self.assertEqual([r["env"]["W18_PASS_INDEX"] for r in runs], ["1", "2"])

    def test_layout_merges_under_profile_and_pass(self):
        runs = ui_round_plan.run_layout(Path("E"), ["w05-demo-artifact", "w15-v2-skill"], 2)
        self.assertEqual([r["relativeDirectory"] for r in runs], [
            "w05-demo-artifact/pass-1", "w05-demo-artifact/pass-2",
            "w15-v2-skill/pass-1", "w15-v2-skill/pass-2",
        ])

    def test_merge_case_results_annotates_and_preserves_order(self):
        merged = ui_round_plan.merge_case_results([
            {"profile": "p1", "pass": 1, "rows": [{"name": "A", "result": "PASS"}]},
            {"profile": "p1", "pass": 2, "rows": [{"name": "A", "result": "FAIL"}]},
        ])
        self.assertEqual([(row["profile"], row["pass"], row["name"]) for row in merged],
                         [("p1", 1, "A"), ("p1", 2, "A")])

    def test_merge_passes_summary_copies_fields(self):
        summary = ui_round_plan.merge_passes_summary([
            {"profile": "p", "pass": 1, "assertionMs": 1234, "exitCode": 0,
             "firstFailure": None, "caseResults": "pass-1/case-results.json"},
        ])
        self.assertEqual(summary, [{
            "profile": "p", "pass": 1, "assertionMs": 1234, "exitCode": 0,
            "firstFailure": None, "caseResults": "pass-1/case-results.json"}])

    def test_plan_and_merge_round_trip(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runs = ui_round_plan.run_layout(root, ["w18-ui-smoke"], 2)
            for index, run in enumerate(runs, 1):
                run_dir = root if run["relativeDirectory"] == "." else root / run["relativeDirectory"]
                run_dir.mkdir(parents=True, exist_ok=True)
                (run_dir / "case-results.json").write_text(json.dumps(
                    [{"name": "Case A", "result": "PASS" if index == 1 else "FAIL", "firstFailure": None}]),
                    encoding="utf-8")
                (run_dir / "flow-health.json").write_text(json.dumps(
                    {"timings": {"assertionsMs": 1000 * index}, "testExitCode": 0 if index == 1 else 1,
                     "firstFailure": None}), encoding="utf-8")
            (root / "flow-health.json").write_text(json.dumps({"sourceSha": "abcdef0123"}), encoding="utf-8")
            (root / "ui-round-plan.json").write_text(json.dumps({"runs": runs}), encoding="utf-8")
            self.assertEqual(ui_round_plan.main(["merge", "--root", str(root), "--runs", str(root / "ui-round-plan.json")]), 0)
            merged = json.loads((root / "case-results.json").read_text(encoding="utf-8"))
            self.assertEqual([(row["pass"], row["result"]) for row in merged], [(1, "PASS"), (2, "FAIL")])
            health = json.loads((root / "flow-health.json").read_text(encoding="utf-8"))
            self.assertEqual([entry["pass"] for entry in health["passes"]], [1, 2])
            self.assertEqual([entry["exitCode"] for entry in health["passes"]], [0, 1])

    def test_validate_cli_reports_problems(self):
        self.assertEqual(ui_round_plan.main(["validate", "--passes", "2", "--skip-build"]), 0)
        self.assertEqual(ui_round_plan.main(["validate", "--passes", "2"]), 1)


class RecordGatesPassesTest(unittest.TestCase):
    def write(self, root, flow, pass_files, merged=None):
        root.mkdir(parents=True, exist_ok=True)
        (root / "flow-health.json").write_text(json.dumps(flow), encoding="utf-8")
        if merged is not None:
            (root / "case-results.json").write_text(json.dumps(merged), encoding="utf-8")
        for relative, rows in pass_files.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(rows), encoding="utf-8")

    def test_repeated_passes_get_r_suffixes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write(root, {
                "acceptanceProfile": "w18-ui-smoke", "sourceSha": "abcdef0123", "retryCount": 0,
                "cleanup": {"success": True}, "evidenceRoot": "D:/e/run-20261005-100000-000-aaaaaaaa",
                "passes": [
                    {"profile": "w18-ui-smoke", "pass": 1, "assertionMs": 1000, "exitCode": 0,
                     "firstFailure": None, "caseResults": "pass-1/case-results.json"},
                    {"profile": "w18-ui-smoke", "pass": 2, "assertionMs": 2000, "exitCode": 1,
                     "firstFailure": "boom", "caseResults": "pass-2/case-results.json"},
                ],
            }, {
                "pass-1/case-results.json": [{"name": "Case A", "result": "PASS"}],
                "pass-2/case-results.json": [{"name": "Case A", "result": "FAIL", "firstFailure": "boom"}],
            })
            records = record_gates.records_for(root)
            record_gates.assign_rounds(records)
            self.assertEqual([r["name"] for r in records],
                             ["ui-w18-ui-smoke-case-a-r1", "ui-w18-ui-smoke-case-a-r2"])
            self.assertEqual([r["result"] for r in records], ["PASS", "FAIL"])
            self.assertEqual([r["duration"] for r in records], ["1s", "2s"])
            self.assertIn("pass=2", records[1]["summary"])

    def test_merged_profiles_do_not_collide(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write(root, {
                "acceptanceProfile": "w05-demo-artifact", "sourceSha": "abcdef0123", "retryCount": 0,
                "cleanup": {"success": True}, "evidenceRoot": "D:/e/run-20261005-100000-000-aaaaaaaa",
                "passes": [
                    {"profile": "w05-demo-artifact", "pass": 1, "assertionMs": 1, "exitCode": 0,
                     "firstFailure": None, "caseResults": "w05-demo-artifact/pass-1/case-results.json"},
                    {"profile": "w15-v2-skill", "pass": 1, "assertionMs": 1, "exitCode": 0,
                     "firstFailure": None, "caseResults": "w15-v2-skill/pass-1/case-results.json"},
                ],
            }, {
                "w05-demo-artifact/pass-1/case-results.json": [{"name": "Same", "result": "PASS"}],
                "w15-v2-skill/pass-1/case-results.json": [{"name": "Same", "result": "PASS"}],
            })
            records = record_gates.records_for(root)
            record_gates.assign_rounds(records)
            self.assertEqual({r["name"] for r in records},
                             {"ui-w05-demo-artifact-same-r1", "ui-w15-v2-skill-same-r1"})

    def test_passes_fall_back_to_the_merged_case_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write(root, {
                "acceptanceProfile": "w18-ui-smoke", "sourceSha": "abcdef0123", "retryCount": 0,
                "cleanup": {"success": True}, "evidenceRoot": "D:/e/run-20261005-100000-000-aaaaaaaa",
                "passes": [
                    {"profile": "w18-ui-smoke", "pass": 1, "assertionMs": 1, "exitCode": 0, "firstFailure": None},
                    {"profile": "w18-ui-smoke", "pass": 2, "assertionMs": 1, "exitCode": 0, "firstFailure": None},
                ],
            }, {}, merged=[
                {"name": "Case A", "result": "PASS", "profile": "w18-ui-smoke", "pass": 1},
                {"name": "Case A", "result": "PASS", "profile": "w18-ui-smoke", "pass": 2},
            ])
            records = record_gates.records_for(root)
            record_gates.assign_rounds(records)
            self.assertEqual([r["name"] for r in records],
                             ["ui-w18-ui-smoke-case-a-r1", "ui-w18-ui-smoke-case-a-r2"])


if __name__ == "__main__":
    unittest.main()

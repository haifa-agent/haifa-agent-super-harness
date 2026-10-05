"""Regression for the pipeline helper scripts.

    python -m unittest discover -s tests/checks/build -p "test_*.py"
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import subprocess
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

BOT = pr_review.DEFAULT_BOT


class ChangeScopeTest(unittest.TestCase):
    def test_classifies_paths(self):
        cases = {
            "README.md": "docs",
            "docs/guide.md": "docs",
            "scripts/verify-full.ps1": "scripts",
            "tests/checks/build/verify-build-entry.ps1": "scripts",
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
        self.assertEqual(change_scope.required_gates(["scripts/governance/x.py"]), ["script-regressions"])
        self.assertEqual(change_scope.required_gates(["scripts/lib/x.ps1"]), ["script-regressions", "ui-warm-round-x1"])
        self.assertEqual(change_scope.required_gates(["super-harness-server/src/test/java/X.java"]), ["verify-full"])
        self.assertEqual(
            change_scope.required_gates(["super-harness-server/src/main/java/X.java", "README.md"]),
            ["docs-check", "verify-full", "ui-warm-round-x2"],
        )
        self.assertEqual(change_scope.required_gates(["frontend/pnpm-lock.yaml"]), ["ui-cold-round", "ui-warm-round-x2"])


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
        return [(r["name"], record_gates.idempotency_key("2026-10-05", "T", r)) for r in records]

    def test_results_are_copied_without_promotion(self):
        self.assertEqual(record_gates.shpm_result("PASS"), "PASS")
        for failure in ("FAIL", "FAIL_PREFLIGHT", "FAIL_NO_TESTS_MATCHED"):
            self.assertEqual(record_gates.shpm_result(failure), "FAIL")
        self.assertEqual(record_gates.shpm_result(None), "NOT_RUN")
        self.assertEqual(record_gates.shpm_result("BLOCKED_EXTERNAL"), "BLOCKED_EXTERNAL")

    def test_reads_verify_full_and_ui_with_profile_in_the_name(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            summary = root / "verify-full-1.json"
            summary.write_text(json.dumps({
                "status": "FAIL_PREFLIGHT", "sourceSha": "abcdef0123", "preflightFailures": ["git diff --check: x"],
                "forks": 1, "sdkSelection": "ABSENT",
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
            command = record_gates.build_command("W05-task-1", cases[1], "db", "2026-10-03")
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

        sources = root / "src/fixture"
        sources.mkdir(parents=True)
        (sources / "Sample.java").write_text("package fixture;\npublic record Sample(int a, String b) {}\n", encoding="utf-8")
        (sources / "Widget.java").write_text(
            "package fixture;\npublic class Widget {\n"
            "    public void visible() {}\n    protected void guarded() {}\n    void hidden() {}\n"
            "    public static Runnable anonymous() { return new Runnable() { public void run() {} }; }\n}\n",
            encoding="utf-8")
        (sources / "Hidden.java").write_text("package fixture;\nclass Hidden { public void x() {} }\n", encoding="utf-8")
        classes = root / "classes"
        classes.mkdir()
        subprocess.run(["javac", "-d", str(classes), *[str(p) for p in sorted(sources.glob("*.java"))]], check=True)
        jar_dir = maven / "io/haifa/haifa-agent-fixture" / self.VERSION
        jar_dir.mkdir(parents=True)
        self.jar = jar_dir / f"haifa-agent-fixture-{self.VERSION}.jar"
        subprocess.run(["jar", "--create", "--file", str(self.jar), "-C", str(classes), "."], check=True)
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


if __name__ == "__main__":
    unittest.main()

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
    def test_results_are_copied_without_promotion(self):
        self.assertEqual(record_gates.shpm_result("PASS"), "PASS")
        for failure in ("FAIL", "FAIL_PREFLIGHT", "FAIL_NO_TESTS_MATCHED"):
            self.assertEqual(record_gates.shpm_result(failure), "FAIL")
        self.assertEqual(record_gates.shpm_result(None), "NOT_RUN")
        self.assertEqual(record_gates.shpm_result("BLOCKED_EXTERNAL"), "BLOCKED_EXTERNAL")

    def test_reads_verify_full_summary_and_ui_round(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            summary = root / "verify-full.json"
            summary.write_text(json.dumps({
                "status": "FAIL_PREFLIGHT", "sourceSha": "abcdef0123", "preflightFailures": ["git diff --check: x"],
            }), encoding="utf-8")
            round_dir = root / "run"
            round_dir.mkdir()
            (round_dir / "flow-health.json").write_text(json.dumps({
                "buildMode": "warm-reuse", "sourceSha": "abcdef0123", "totalMs": 97000, "retryCount": 0,
                "cleanup": {"success": True},
            }), encoding="utf-8")
            (round_dir / "case-results.json").write_text(json.dumps([
                {"name": "Artifact survives reload", "result": "PASS", "firstFailure": None},
                {"name": "Second case", "result": "FAIL", "firstFailure": "timeout"},
            ]), encoding="utf-8")

            gate = record_gates.records_for(summary)
            self.assertEqual([r["result"] for r in gate], ["FAIL"])
            self.assertIn("preflight", gate[0]["summary"])
            ui = record_gates.records_for(round_dir)
            self.assertEqual([r["result"] for r in ui], ["PASS", "FAIL"])
            self.assertEqual(ui[0]["key"], "ui-warm-reuse-artifact-survives-reload")
            self.assertIn("first failure: timeout", ui[1]["summary"])
            command = record_gates.build_command("W05-task-1", ui[1], "db", "2026-10-03")
            self.assertEqual(command[command.index("--result") + 1], "FAIL")
            self.assertIn("2026-10-03-W05-task-1-ui-warm-reuse-second-case-abcdef01", command)


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


if __name__ == "__main__":
    unittest.main()

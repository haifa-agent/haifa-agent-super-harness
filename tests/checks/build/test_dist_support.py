"""Unit and regression tests for dist-support packaging and service installation scripts.

    python -m unittest tests/checks/build/test_dist_support.py
"""

from __future__ import annotations

import importlib.util
import json
import os
import shutil
import subprocess
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch, MagicMock

ROOT = Path(__file__).resolve().parents[3]


def load_module(relative: str):
    path = ROOT / relative
    spec = importlib.util.spec_from_file_location(path.stem, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


package_release = load_module("dist-support/scripts/package-release.py")
install_service = load_module("dist-support/scripts/install-service.py")


class DistSupportSandboxTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="sh-dist-test-")
        self.addCleanup(lambda: shutil.rmtree(self.temp_dir, ignore_errors=True))

    def test_image_lock_manifest_validity(self):
        lock_file = ROOT / "dist-support" / "sandbox" / "image-lock.json"
        self.assertTrue(lock_file.is_file(), "dist-support/sandbox/image-lock.json must exist")
        data = json.loads(lock_file.read_text(encoding="utf-8"))
        self.assertEqual(data.get("schema_version"), 1)
        self.assertEqual(data.get("policy_version"), "docker-aio/v2")
        self.assertEqual(data.get("platform"), "linux/amd64")
        self.assertIn("@sha256:", data.get("image", ""))
        self.assertTrue(data.get("digest", "").startswith("sha256:"))
        self.assertEqual(data.get("default_lifecycle_mode"), "warm")
        self.assertEqual(data.get("default_idle_timeout_ms"), 600000)
        self.assertIn("unprivileged", data.get("required_capabilities", []))
        self.assertIn("no-new-privileges", data.get("required_capabilities", []))
        network = data.get("network", {})
        self.assertEqual(network.get("driver"), "bridge")
        self.assertTrue(network.get("internal"))
        self.assertFalse(network.get("enable_icc"))

    def test_write_env_file_default_and_sandbox_overrides(self):
        prefix = Path(self.temp_dir)
        config_dir = prefix / "config"
        config_dir.mkdir(parents=True, exist_ok=True)
        env_tmpl = ROOT / "dist-support" / "templates" / "gateway.env.tmpl"
        shutil.copy2(str(env_tmpl), str(config_dir / "gateway.env.tmpl"))

        sandbox_dir = prefix / "sandbox"
        sandbox_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(
            str(ROOT / "dist-support" / "sandbox" / "image-lock.json"),
            str(sandbox_dir / "image-lock.json")
        )

        args = types.SimpleNamespace(
            prefix=str(prefix),
            data_root=None,
            gateway_port=18018,
            frontend_port=33018,
            execution_provider=None,
            docker_image=None,
            docker_socket=None,
            docker_lifecycle=None,
            docker_idle_timeout_ms=None,
            skip_frontend=False
        )

        console = MagicMock()
        ctx = install_service.Context(args)

        # 1. First run: default disabled
        key_state = install_service.write_env_file(ctx, 18018, 33018, console)
        self.assertEqual(key_state, "CREATED")
        env_values = install_service.read_env_file(ctx.env_file)
        self.assertEqual(env_values.get("HAIFA_SUPER_HARNESS_EXECUTION_PROVIDER"), "disabled")
        self.assertEqual(env_values.get("HAIFA_SUPER_HARNESS_SCHEDULER_ENABLED"), "0")
        self.assertIn("enterprise-public-cn-beijing", env_values.get("HAIFA_SUPER_HARNESS_DOCKER_IMAGE", ""))
        self.assertEqual(env_values.get("HAIFA_SUPER_HARNESS_DOCKER_LIFECYCLE"), "warm")
        self.assertEqual(env_values.get("HAIFA_SUPER_HARNESS_DOCKER_IDLE_TIMEOUT_MS"), "600000")
        continuation_key = env_values.get("HAIFA_SH_CONTINUATION_KEY")
        self.assertTrue(bool(continuation_key))

        # 2. Second run: switch to docker-aio with explicit overrides, key must be KEPT
        args.execution_provider = "docker-aio"
        args.scheduler_enabled = "1"
        args.docker_socket = "/custom/docker.sock"
        args.docker_lifecycle = "ephemeral"
        args.docker_idle_timeout_ms = 300000
        key_state2 = install_service.write_env_file(ctx, 18018, 33018, console)
        self.assertEqual(key_state2, "KEPT")
        env_values2 = install_service.read_env_file(ctx.env_file)
        self.assertEqual(env_values2.get("HAIFA_SUPER_HARNESS_EXECUTION_PROVIDER"), "docker-aio")
        self.assertEqual(env_values2.get("HAIFA_SUPER_HARNESS_SCHEDULER_ENABLED"), "1")
        self.assertEqual(env_values2.get("HAIFA_SUPER_HARNESS_DOCKER_SOCKET"), "/custom/docker.sock")
        self.assertEqual(env_values2.get("HAIFA_SUPER_HARNESS_DOCKER_LIFECYCLE"), "ephemeral")
        self.assertEqual(env_values2.get("HAIFA_SUPER_HARNESS_DOCKER_IDLE_TIMEOUT_MS"), "300000")
        self.assertEqual(env_values2.get("HAIFA_SH_CONTINUATION_KEY"), continuation_key)

    def test_do_doctor_json_output(self):
        prefix = Path(self.temp_dir)
        args = types.SimpleNamespace(
            prefix=str(prefix),
            data_root=None,
            gateway_port=18018,
            frontend_port=33018,
            skip_frontend=True,
            json=True,
            java_bin=None
        )

        console = MagicMock()
        ctx = install_service.Context(args)
        with patch("sys.stdout") as mock_stdout:
            exit_code = install_service.do_doctor(ctx, 18018, 33018, console)
            self.assertIn(exit_code, (install_service.EXIT_OK, install_service.EXIT_PREREQ))

    def test_do_prepare_offline_tar_verification(self):
        prefix = Path(self.temp_dir)
        sandbox_dir = prefix / "sandbox"
        sandbox_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(
            str(ROOT / "dist-support" / "sandbox" / "image-lock.json"),
            str(sandbox_dir / "image-lock.json")
        )
        fake_tar = prefix / "sandbox-fake.tar"
        fake_tar.write_bytes(b"dummy-tar-bytes")
        fake_sha = install_service.sha256_file(fake_tar)

        args = types.SimpleNamespace(
            prefix=str(prefix),
            data_root=None,
            image_tar=str(fake_tar)
        )

        console = MagicMock()
        ctx = install_service.Context(args)

        # Test failure when docker CLI missing
        with patch.object(install_service, "which", return_value=""):
            with self.assertRaises(install_service.InstallError) as cm:
                install_service.do_prepare(ctx, console)
            self.assertIn("docker CLI not found", str(cm.exception))

        # Test failure when docker load fails
        with patch.object(install_service, "which", return_value="/usr/bin/docker"), \
             patch.object(install_service, "run", side_effect=[
                 (0, "29.1.3"),  # docker info
                 (1, "corrupted archive error")  # docker load
             ]):
            with self.assertRaises(install_service.InstallError) as cm:
                install_service.do_prepare(ctx, console)
            self.assertIn("docker load failed", str(cm.exception))

    def test_load_image_lock_corrupt_fails_closed(self):
        prefix = Path(self.temp_dir)
        sandbox_dir = prefix / "sandbox"
        sandbox_dir.mkdir(parents=True, exist_ok=True)
        lock_file = sandbox_dir / "image-lock.json"
        lock_file.write_text("invalid-json{", encoding="utf-8")
        ctx = types.SimpleNamespace(prefix=prefix)
        with self.assertRaises(install_service.InstallError) as cm:
            install_service.load_image_lock(ctx)
        self.assertIn("failed to parse sandbox image lock", str(cm.exception))

    def test_run_helper_timeout_and_not_found(self):
        # 1. Non-existent binary
        code, out = install_service.run(["/non/existent/binary/haifa-test"])
        self.assertEqual(code, 127)
        self.assertIn("executable not found", out)
        with self.assertRaises(install_service.InstallError):
            install_service.run(["/non/existent/binary/haifa-test"], check=True)

        # 2. TimeoutExpired
        with patch("subprocess.run", side_effect=subprocess.TimeoutExpired(cmd=["test"], timeout=1)):
            code, out = install_service.run(["test"], timeout=1)
            self.assertEqual(code, -1)
            self.assertIn("timed out", out)
            with self.assertRaises(install_service.InstallError):
                install_service.run(["test"], timeout=1, check=True)

    def test_do_prepare_offline_tar_success(self):
        prefix = Path(self.temp_dir)
        sandbox_dir = prefix / "sandbox"
        sandbox_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(
            str(ROOT / "dist-support" / "sandbox" / "image-lock.json"),
            str(sandbox_dir / "image-lock.json")
        )
        fake_tar = prefix / "sandbox-fake.tar"
        fake_tar.write_bytes(b"dummy-tar-bytes")
        fake_sha = install_service.sha256_file(fake_tar)

        manifest_file = prefix / "manifest.json"
        manifest_file.write_text(json.dumps({
            "image": "enterprise-public-cn-beijing.cr.volces.com/vefaas-public/all-in-one-sandbox:1.11.0",
            "file": fake_tar.name,
            "sha256": fake_sha,
            "image_id": "sha256:d8c5f111222333"
        }), encoding="utf-8")

        args = types.SimpleNamespace(
            prefix=str(prefix),
            data_root=None,
            image_tar=str(fake_tar)
        )
        console = MagicMock()
        ctx = install_service.Context(args)

        with patch.object(install_service, "which", return_value="/usr/bin/docker"), \
             patch.object(install_service, "run", side_effect=[
                 (0, "29.1.3"),  # docker info
                 (0, "Loaded image: enterprise-public-cn-beijing.cr.volces.com/vefaas-public/all-in-one-sandbox:1.11.0"),  # docker load
                 (0, "sha256:d8c5f111222333 amd64 []")  # docker image inspect
             ]):
            code = install_service.do_prepare(ctx, console)
            self.assertEqual(code, install_service.EXIT_OK)

    def test_do_doctor_provider_aware(self):
        prefix = Path(self.temp_dir)
        sandbox_dir = prefix / "sandbox"
        sandbox_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(
            str(ROOT / "dist-support" / "sandbox" / "image-lock.json"),
            str(sandbox_dir / "image-lock.json")
        )
        console = MagicMock()

        # Case 1: execution_provider is docker-aio, but docker CLI missing -> FAIL
        args_docker = types.SimpleNamespace(
            prefix=str(prefix),
            data_root=None,
            gateway_port=18018,
            frontend_port=33018,
            skip_frontend=True,
            json=True,
            java_bin=None,
            execution_provider="docker-aio"
        )
        ctx_docker = install_service.Context(args_docker)
        with patch.object(install_service, "which", return_value=""), \
             patch("sys.stdout"):
            exit_code = install_service.do_doctor(ctx_docker, 18018, 33018, console)
            self.assertEqual(exit_code, install_service.EXIT_PREREQ)

        # Case 2: execution_provider is disabled, docker CLI missing -> WARN, overall PASS
        args_disabled = types.SimpleNamespace(
            prefix=str(prefix),
            data_root=None,
            gateway_port=18018,
            frontend_port=33018,
            skip_frontend=True,
            json=True,
            java_bin=None,
            execution_provider="disabled"
        )
        ctx_disabled = install_service.Context(args_disabled)
        with patch.object(install_service, "which", return_value=""), \
             patch.object(install_service, "port_bindable", return_value=True), \
             patch.object(install_service, "run", return_value=(0, 'openjdk version "21.0.2"')), \
             patch("sys.stdout"):
            exit_code = install_service.do_doctor(ctx_disabled, 18018, 33018, console)
            self.assertEqual(exit_code, install_service.EXIT_OK)

    def test_package_release_copy_sandbox(self):
        stage = Path(self.temp_dir) / "stage"
        stage.mkdir(parents=True, exist_ok=True)
        console = MagicMock()

        lock_data = package_release.copy_sandbox(ROOT, stage, console)
        self.assertEqual(lock_data.get("policy_version"), "docker-aio/v2")
        self.assertTrue((stage / "sandbox" / "image-lock.json").is_file())
        self.assertTrue((stage / "sandbox" / "README.md").is_file())

        # Test with offline image tar
        fake_tar = Path(self.temp_dir) / "image-archive.tar"
        fake_tar.write_bytes(b"sample-tar-data")
        lock_data2 = package_release.copy_sandbox(ROOT, stage, console, image_tar=str(fake_tar))
        self.assertTrue((stage / "sandbox" / "images" / "image-archive.tar").is_file())
        manifest = stage / "sandbox" / "images" / "manifest.json"
        self.assertTrue(manifest.is_file())
        manifest_data = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertEqual(manifest_data["file"], "image-archive.tar")
        self.assertEqual(manifest_data["sha256"], package_release.sha256_file(fake_tar))


if __name__ == "__main__":
    unittest.main()

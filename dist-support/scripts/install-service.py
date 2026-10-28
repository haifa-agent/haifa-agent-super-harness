#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Haifa Super Harness release 安装/服务化脚本（随发布包分发）。

在解压后的「单前缀」目录内运行：把应用注册为 Linux systemd 服务或 Windows WinSW 服务，
并幂等地准备配置、数据与日志目录。

命令：
  install    创建目录 -> 渲染 config/gateway.env（首次生成 continuation key）-> 注册并启动服务
  uninstall  停止并删除服务定义（保留 config/ data/ logs/）
  status     只读检查：服务状态 + loopback 监听 + /health + /api/v1/auth/me
  render     只渲染配置文件与单元/服务定义，不注册、不启动

约束：
  * 端口仅 loopback，禁止 80/443，避开 2026/3000/8001/8002；
  * continuation key 已存在则永不轮换；gateway.env 含密钥，权限 0600，不打印其值；
  * 服务定义是唯一越界文件（/etc/systemd/system/*.service 或 Windows 服务注册项）。
"""

from __future__ import annotations

import argparse
import base64
import getpass
import hashlib
import json
import os
import platform
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path
from string import Template

GATEWAY_ID = "super-harness-gateway"
FRONTEND_ID = "super-harness-frontend"
ENTRY_NAME = "install-service.py"
CONTINUATION_KEY_VAR = "HAIFA_SH_CONTINUATION_KEY"
CONTINUATION_KEY_REF_VAR = "HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF"
FORBIDDEN_PORTS = (80, 443)
RESERVED_PORTS = (2026, 3000, 8001, 8002)
DEFAULT_GATEWAY_PORT = 18018
DEFAULT_FRONTEND_PORT = 33018

EXIT_OK = 0
EXIT_USAGE = 1
EXIT_PREREQ = 2
EXIT_NOT_RUNNING = 10


class InstallError(RuntimeError):
    pass


def _reconfigure_stdio():
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass


def which(name):
    return shutil.which(name) or ""


def sha256_file(path):
    digest = hashlib.sha256()
    with open(str(path), "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run(argv, timeout=120, check=False, shell=False):
    try:
        proc = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, errors="replace", timeout=timeout, shell=shell)
    except subprocess.TimeoutExpired as exc:
        out = (exc.stdout or "").strip() if isinstance(exc.stdout, str) else ""
        if check:
            raise InstallError(f"command timed out after {timeout}s: {argv}\n{out[:800]}")
        return -1, f"command timed out after {timeout}s"
    except FileNotFoundError:
        if check:
            raise InstallError(f"executable not found: {argv[0]}")
        return 127, f"executable not found: {argv[0]}"
    if check and proc.returncode != 0:
        raise InstallError("command failed (exit %d): %s\n%s"
                           % (proc.returncode, argv, (proc.stdout or "").strip()[:800]))
    return proc.returncode, (proc.stdout or "").strip()


_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def http_json(url, timeout=5.0):
    """GET a JSON endpoint. A non-JSON body still returns its HTTP status (never 0)."""
    try:
        with _OPENER.open(url, timeout=timeout) as response:
            status = int(response.status)
            body = response.read(65536).decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        status = int(error.code)
        try:
            body = error.read(65536).decode("utf-8", "replace")
        except Exception:
            body = ""
    except Exception:
        return 0, None
    try:
        return status, json.loads(body)
    except ValueError:
        return status, None


def http_status(url, timeout=5.0):
    try:
        with _OPENER.open(url, timeout=timeout) as response:
            response.read(1024)
            return int(response.status)
    except urllib.error.HTTPError as error:
        return int(error.code)
    except Exception:
        return 0


def is_loopback_address(address):
    bare = address.strip().strip("[]")
    if bare in ("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost"):
        return True
    if bare.lower().startswith("::ffff:127."):
        return True
    return bare.startswith("127.") and ":" not in bare


def listening_addresses(ports):
    wanted = {int(port) for port in ports}
    found = []
    if os.name == "nt":
        code, output = run(["netstat", "-ano", "-p", "tcp"])
        for line in output.splitlines():
            parts = line.split()
            if len(parts) < 4 or parts[0].upper() != "TCP" or parts[3].upper() != "LISTENING":
                continue
            address, _, port = parts[1].rpartition(":")
            try:
                if int(port) in wanted:
                    found.append((address, int(port)))
            except ValueError:
                continue
    else:
        code, output = run(["ss", "-H", "-ltn"])
        for line in output.splitlines():
            parts = line.split()
            if len(parts) < 4:
                continue
            address, _, port = parts[3].rpartition(":")
            try:
                if int(port) in wanted:
                    found.append((address, int(port)))
            except ValueError:
                continue
    return found


def port_bindable(port):
    probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(("127.0.0.1", int(port)))
        return True
    except OSError:
        return False
    finally:
        probe.close()


def wait_for_http(url, timeout, validator=None):
    deadline = time.time() + timeout
    last = 0
    while time.time() < deadline:
        status, payload = http_json(url, timeout=4.0)
        last = status
        if status == 200 and (validator is None or validator(payload)):
            return True, status
        time.sleep(1.0)
    return False, last


def validate_ports(gateway, frontend, skip_frontend):
    for name, port in (("gateway", gateway), ("frontend", frontend)):
        if port in FORBIDDEN_PORTS or port < 1024 or port > 65535:
            raise InstallError("%s port must be 1024-65535 and never 80/443; got %d" % (name, port))
        if port in RESERVED_PORTS:
            raise InstallError("%s port %d is reserved by the frozen DeerFlow reference topology"
                               % (name, port))
    if not skip_frontend and gateway == frontend:
        raise InstallError("gateway and frontend ports must differ")


def read_env_file(path):
    values = {}
    if not path.is_file():
        return values
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def now_stamp():
    return datetime.now().strftime("%Y%m%d-%H%M%S")


def build_launch_env(ctx):
    """Process environment for a direct (non-service) run: host env + config/gateway.env."""
    env = dict(os.environ)
    env.update(read_env_file(ctx.env_file))
    env.setdefault("NO_PROXY", "127.0.0.1,localhost")
    env.setdefault("no_proxy", "127.0.0.1,localhost")
    return env


def spawn(argv, cwd, env, out_path, err_path):
    out = open(str(out_path), "ab")
    err = open(str(err_path), "ab")
    kwargs = {"cwd": str(cwd), "env": env, "stdin": subprocess.DEVNULL,
              "stdout": out, "stderr": err, "close_fds": True}
    if os.name == "nt":
        kwargs["creationflags"] = 0x00000008 | 0x00000200  # DETACHED_PROCESS | NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    try:
        proc = subprocess.Popen(argv, **kwargs)
    finally:
        out.close()
        err.close()
    return proc


def stop_pid(pid, timeout=20):
    if os.name == "nt":
        run(["taskkill", "/PID", str(pid), "/T", "/F"], timeout=timeout)
    else:
        import signal
        try:
            os.kill(pid, signal.SIGTERM)
        except OSError:
            return
    deadline = time.time() + timeout
    while time.time() < deadline:
        if os.name == "nt":
            code, _ = run(["tasklist", "/FI", "PID eq %d" % pid, "/NH"])
            if str(pid) not in (_ or ""):
                return
        else:
            try:
                os.kill(pid, 0)
            except OSError:
                return
        time.sleep(0.5)


class Context:
    def __init__(self, args):
        self.args = args
        self.prefix = Path(args.prefix).resolve()
        self.config = self.prefix / "config"
        self.data = Path(args.data_root).resolve() if args.data_root else self.prefix / "data"
        self.logs = self.prefix / "logs"
        self.service_dir = self.prefix / "service"
        self.env_file = self.config / "gateway.env"
        self.env_template = self.config / "gateway.env.tmpl"
        self.run_state = self.config / "run.json"
        self.systemd_template = self.service_dir / "systemd"
        self.winsw_template = self.service_dir / "winsw"

    @property
    def is_windows(self):
        return os.name == "nt"

    def gateway_ports(self):
        existing = read_env_file(self.env_file)
        gateway = self.args.gateway_port or int(existing.get("SUPER_HARNESS_PORT") or DEFAULT_GATEWAY_PORT)
        frontend = self.args.frontend_port or DEFAULT_FRONTEND_PORT
        origin = existing.get("DEER_FLOW_TRUSTED_ORIGINS") or ""
        match = re.search(r":(\d+)\s*$", origin)
        if not self.args.frontend_port and match:
            frontend = int(match.group(1))
        return gateway, frontend

    def java_bin(self):
        if self.args.java_bin:
            return self.args.java_bin
        found = which("java")
        if found:
            return found
        return "C:\\Program Files\\Java\\bin\\java.exe" if self.is_windows else "/usr/bin/java"

    def node_bin(self):
        if self.args.node_bin:
            return self.args.node_bin
        return which("node") or ("node.exe" if self.is_windows else "/usr/bin/node")

    def frontend_entry(self):
        return self.prefix / "repo" / "frontend" / "node_modules" / "next" / "dist" / "bin" / "next"


def render_template(path, mapping):
    text = Path(path).read_text(encoding="utf-8")
    return Template(text).substitute(**mapping)


def ensure_continuation_key(env_values, console):
    if env_values.get(CONTINUATION_KEY_VAR):
        return "KEPT"
    env_values[CONTINUATION_KEY_VAR] = base64.b64encode(os.urandom(32)).decode("ascii")
    console.log("[config] continuation key generated (value never logged)")
    return "CREATED"


def normalize_execution_provider(raw):
    if not raw:
        return "disabled"
    val = raw.strip().lower().replace("_", "-")
    if val in ("docker-aio", "docker"):
        return "docker-aio"
    if val in ("host-guarded", "host"):
        return "host-guarded"
    if val in ("disabled", "off", "none", "false"):
        return "disabled"
    return val


def read_docker_tar_config(tar_path):
    """Extract Image Config ID (sha256:...) from a docker save tar archive."""
    try:
        import tarfile
        with tarfile.open(str(tar_path), "r:*") as tar:
            for member in tar.getmembers():
                clean_name = member.name.lstrip("./")
                if clean_name == "manifest.json":
                    f = tar.extractfile(member)
                    if f:
                        data = json.loads(f.read().decode("utf-8"))
                        if isinstance(data, list) and len(data) > 0:
                            cfg = data[0].get("Config", "")
                            cfg_base = cfg.split("/")[-1]
                            if cfg_base.endswith(".json"):
                                cfg_base = cfg_base[:-5]
                            if cfg_base:
                                return f"sha256:{cfg_base}"
                elif clean_name == "index.json":
                    f = tar.extractfile(member)
                    if f:
                        data = json.loads(f.read().decode("utf-8"))
                        manifests = data.get("manifests", [])
                        if manifests and "digest" in manifests[0]:
                            return manifests[0]["digest"]
    except Exception:
        pass
    return None


def inspect_and_verify_image(docker_cli, lock_data, manifest_data=None, tar_config_id=None):
    """
    Inspect local docker image and verify its identity against image lock data.
    Shared by do_doctor and do_prepare.
    Returns (ok: bool, detail: str, img_id: str, img_arch: str).
    """
    locked_img = lock_data.get("image", "")
    expected_digest = lock_data.get("digest")
    expected_config = lock_data.get("config_digest") or (manifest_data.get("image_id") if manifest_data else None) or tar_config_id

    # 1. Inspect image by locked_img
    code, out = run([docker_cli, "image", "inspect", locked_img, "--format", "{{.Id}} {{.Architecture}} {{json .RepoDigests}}"])
    if code != 0:
        repo_tag = f"{lock_data.get('repository')}:{lock_data.get('tag')}"
        code, out = run([docker_cli, "image", "inspect", repo_tag, "--format", "{{.Id}} {{.Architecture}} {{json .RepoDigests}}"])
        if code != 0 and expected_config:
            code, out = run([docker_cli, "image", "inspect", expected_config, "--format", "{{.Id}} {{.Architecture}} {{json .RepoDigests}}"])

    if code != 0 or not out.strip():
        return False, f"Image {locked_img} not present locally (run 'install-service.py prepare')", "", ""

    parts = out.strip().split(maxsplit=2)
    img_id = parts[0]
    img_arch = parts[1] if len(parts) > 1 else "unknown"
    repo_digests = parts[2] if len(parts) > 2 else ""

    matched = False
    if expected_digest:
        digest_clean = expected_digest.replace("sha256:", "")
        if digest_clean in img_id or digest_clean in repo_digests:
            matched = True
    if not matched and expected_config:
        cfg_clean = expected_config.replace("sha256:", "")
        if cfg_clean in img_id:
            matched = True

    if not matched:
        return False, f"Image present but digest mismatch: expected digest={expected_digest} or config={expected_config}, got id={img_id}, repoDigests={repo_digests}", img_id, img_arch

    expected_platform = lock_data.get("platform", "linux/amd64")
    if "amd64" in expected_platform and platform.machine().lower() in ("x86_64", "amd64") and img_arch != "amd64":
        return False, f"Architecture mismatch: {img_arch} vs amd64", img_id, img_arch

    return True, f"Present and verified ({img_arch}, {img_id[:19]}...)", img_id, img_arch


def load_image_lock(ctx):
    lock_file = ctx.prefix / "sandbox" / "image-lock.json"
    if not lock_file.is_file():
        raise InstallError("required sandbox image lock is missing: %s" % lock_file)
    try:
        data = json.loads(lock_file.read_text(encoding="utf-8"))
    except Exception as e:
        raise InstallError("failed to parse sandbox image lock %s: %s" % (lock_file, e))
    required_keys = ("image", "repository", "tag", "digest")
    missing = [k for k in required_keys if not data.get(k)]
    if missing:
        raise InstallError("sandbox image lock %s missing required keys: %s" % (lock_file, missing))
    return data


def write_env_file(ctx, gateway, frontend, console):
    if not ctx.env_template.is_file():
        raise InstallError("env template is missing: %s" % ctx.env_template)
    existing = read_env_file(ctx.env_file)
    key_state = ensure_continuation_key(existing, console)
    raw_provider = (
        getattr(ctx.args, "execution_provider", None)
        or existing.get("HAIFA_SUPER_HARNESS_EXECUTION_PROVIDER")
        or "disabled"
    )
    execution_provider = normalize_execution_provider(raw_provider)
    is_docker = execution_provider == "docker-aio"
    lock_data = {}
    if is_docker:
        lock_data = load_image_lock(ctx)
    else:
        try:
            lock_data = load_image_lock(ctx)
        except Exception:
            pass
    scheduler_enabled = str(
        getattr(ctx.args, "scheduler_enabled", None)
        or existing.get("HAIFA_SUPER_HARNESS_SCHEDULER_ENABLED")
        or "0"
    )
    docker_image = (
        getattr(ctx.args, "docker_image", None)
        or existing.get("HAIFA_SUPER_HARNESS_DOCKER_IMAGE")
        or lock_data.get("image", "")
    )
    default_socket = "//./pipe/docker_engine" if ctx.is_windows else "/var/run/docker.sock"
    docker_socket = (
        getattr(ctx.args, "docker_socket", None)
        or existing.get("HAIFA_SUPER_HARNESS_DOCKER_SOCKET")
        or default_socket
    )
    docker_lifecycle = (
        getattr(ctx.args, "docker_lifecycle", None)
        or existing.get("HAIFA_SUPER_HARNESS_DOCKER_LIFECYCLE")
        or lock_data.get("default_lifecycle_mode", "warm")
    )
    docker_idle_timeout = str(
        getattr(ctx.args, "docker_idle_timeout_ms", None)
        or existing.get("HAIFA_SUPER_HARNESS_DOCKER_IDLE_TIMEOUT_MS")
        or lock_data.get("default_idle_timeout_ms", 600000)
    )
    rendered = render_template(ctx.env_template, {
        "gateway_port": gateway,
        "frontend_port": frontend,
        "data_root": str(ctx.data).replace("\\", "/"),
        "skills_dir": str(ctx.prefix / "skills").replace("\\", "/"),
        "execution_provider": execution_provider,
        "scheduler_enabled": scheduler_enabled,
        "docker_image": docker_image,
        "docker_socket": docker_socket,
        "docker_lifecycle": docker_lifecycle,
        "docker_idle_timeout_ms": docker_idle_timeout,
    })
    managed = set()
    lines = []
    for line in rendered.splitlines():
        if "=" in line and not line.strip().startswith("#"):
            managed.add(line.split("=", 1)[0].strip())
        lines.append(line)
    for name, value in existing.items():
        if name in managed or name == CONTINUATION_KEY_VAR:
            continue
        lines.append("%s=%s" % (name, value))
    lines.append("%s=%s" % (CONTINUATION_KEY_VAR, existing[CONTINUATION_KEY_VAR]))
    ctx.config.mkdir(parents=True, exist_ok=True)
    with open(str(ctx.env_file), "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(lines) + "\n")
    try:
        os.chmod(str(ctx.env_file), 0o600)
    except OSError:
        pass
    console.log("[config] %s key=%s" % (ctx.env_file, key_state))
    return key_state


def xml_escape(value):
    return (str(value).replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace('"', "&quot;"))


def winsw_env_entries(env_values):
    lines = []
    for name, value in env_values.items():
        lines.append('  <env name="%s" value="%s"/>' % (xml_escape(name), xml_escape(value)))
    return "\n".join(lines) + ("\n" if lines else "")


def backend_dir(path):
    """Directory of a resolved executable, or '' for a bare command name."""
    if not path:
        return ""
    resolved = Path(path)
    return str(resolved.parent) if resolved.is_file() else ""


def systemd_mapping(ctx, gateway, frontend):
    return {
        "prefix": str(ctx.prefix),
        "gateway_unit": GATEWAY_ID,
        "gateway_port": gateway,
        "frontend_port": frontend,
        "jar_name": _jar_name(ctx.prefix),
        "java_bin": ctx.java_bin(),
        "node_bin": ctx.node_bin(),
        "node_dir": backend_dir(ctx.node_bin()),
        "pnpm_dir": backend_dir(which("pnpm") or which("pnpm.cmd")),
        "service_user": ctx.args.service_user or getpass.getuser(),
        "service_group": ctx.args.service_group or ctx.args.service_user or getpass.getuser(),
    }


def winsw_mapping(ctx, gateway, frontend, unit):
    env_entries = winsw_env_entries(read_env_file(ctx.env_file))
    account = ""
    if ctx.args.service_account:
        account = ("  <serviceaccount>\n    <username>%s</username>\n  </serviceaccount>\n"
                   % xml_escape(ctx.args.service_account))
    return {
        "prefix": str(ctx.prefix),
        "service_id": unit,
        "jar_name": _jar_name(ctx.prefix),
        "java_bin": ctx.java_bin(),
        "node_bin": ctx.node_bin(),
        "frontend_port": frontend,
        "env_entries": env_entries,
        "service_account_xml": account,
        "depend_xml": "  <depend>%s</depend>\n" % GATEWAY_ID,
    }


def do_render(ctx, gateway, frontend, console):
    """Render only: write the platform service definition and env file into config/ for review."""
    ctx.config.mkdir(parents=True, exist_ok=True)
    ctx.logs.mkdir(parents=True, exist_ok=True)
    ctx.data.mkdir(parents=True, exist_ok=True)
    # The env file must exist first: WinSW env entries and the systemd EnvironmentFile both
    # derive from it.
    write_env_file(ctx, gateway, frontend, console)
    units = [GATEWAY_ID] if ctx.args.skip_frontend else [GATEWAY_ID, FRONTEND_ID]
    rendered = []
    if ctx.is_windows:
        if not ctx.winsw_template.is_dir():
            raise InstallError("winsw templates are missing: %s" % ctx.winsw_template)
        for unit in units:
            template = ctx.winsw_template / ("%s.xml.tmpl" % unit)
            text = render_template(template, winsw_mapping(ctx, gateway, frontend, unit))
            target = ctx.config / ("%s.xml" % unit)
            target.write_text(text, encoding="utf-8", newline="\n")
            console.log("[render] %s" % target)
            rendered.append(str(target))
    else:
        if not ctx.systemd_template.is_dir():
            raise InstallError("systemd templates are missing: %s" % ctx.systemd_template)
        mapping = systemd_mapping(ctx, gateway, frontend)
        for unit in units:
            template = ctx.systemd_template / ("%s.service.tmpl" % unit)
            text = render_template(template, mapping)
            target = ctx.config / ("%s.service" % unit)
            target.write_text(text, encoding="utf-8", newline="\n")
            console.log("[render] %s" % target)
            rendered.append(str(target))
    return rendered


def _jar_name(prefix):
    app = Path(prefix) / "app"
    jars = sorted(app.glob("*.jar")) if app.is_dir() else []
    if not jars:
        raise InstallError("exec JAR is missing under %s" % app)
    return jars[0].name


def install_systemd(ctx, gateway, frontend, console):
    if os.geteuid() != 0 and not ctx.args.dry_run:
        raise InstallError("systemd install requires root; re-run with sudo")
    (ctx.config / "unit-backup").mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    mapping = systemd_mapping(ctx, gateway, frontend)
    if not ctx.args.service_user:
        mapping["service_user"] = "root"
        mapping["service_group"] = "root"
    units = [GATEWAY_ID]
    if not ctx.args.skip_frontend:
        units.append(FRONTEND_ID)
    for unit in units:
        template = ctx.systemd_template / ("%s.service.tmpl" % unit)
        if not template.is_file():
            raise InstallError("unit template is missing: %s" % template)
        text = render_template(template, mapping)
        target = Path("/etc/systemd/system/%s.service" % unit)
        if target.exists():
            backup = ctx.config / "unit-backup" / ("%s.service.%s" % (unit, stamp))
            shutil.copy2(str(target), str(backup))
            console.log("[install] backed up %s -> %s" % (target, backup.name))
        if ctx.args.dry_run:
            console.log("[install] DRY-RUN would write %s" % target)
            continue
        target.write_text(text, encoding="utf-8", newline="\n")
        console.log("[install] wrote %s" % target)
    if ctx.args.dry_run:
        return {"dryRun": True}
    run(["systemctl", "daemon-reload"], check=True)
    run(["systemctl", "enable", "%s.service" % GATEWAY_ID], check=True)
    run(["systemctl", "restart", "%s.service" % GATEWAY_ID], check=True)
    ok, status = wait_for_http("http://127.0.0.1:%d/health" % gateway, ctx.args.ready_timeout,
                               validator=lambda payload: isinstance(payload, dict)
                               and payload.get("status") == "ok")
    if not ok:
        raise InstallError("gateway did not become ready (last status=%s); see %s/gateway.log"
                           % (status, ctx.logs))
    if not ctx.args.skip_frontend:
        run(["systemctl", "enable", "%s.service" % FRONTEND_ID], check=True)
        run(["systemctl", "restart", "%s.service" % FRONTEND_ID], check=True)
        ok, status = wait_for_http("http://127.0.0.1:%d/" % frontend, ctx.args.ready_timeout,
                                   validator=None)
        if not ok:
            raise InstallError("frontend did not become ready (last status=%s); see %s/frontend.log"
                               % (status, ctx.logs))
    return {"units": units}


def install_winsw(ctx, gateway, frontend, console):
    if not ctx.args.winsw_exe:
        raise InstallError("Windows install requires --winsw-exe <path to WinSW.exe>")
    winsw = Path(ctx.args.winsw_exe).resolve()
    if not winsw.is_file():
        raise InstallError("WinSW executable not found: %s" % winsw)
    if not ctx.winsw_template.is_dir():
        raise InstallError("winsw templates are missing: %s" % ctx.winsw_template)
    generated = ctx.winsw_template.parent
    units = [GATEWAY_ID]
    if not ctx.args.skip_frontend:
        units.append(FRONTEND_ID)
    for unit in units:
        template = ctx.winsw_template / ("%s.xml.tmpl" % unit)
        if not template.is_file():
            raise InstallError("winsw template is missing: %s" % template)
        text = render_template(template, winsw_mapping(ctx, gateway, frontend, unit))
        exe = generated / ("%s.exe" % unit)
        xml = generated / ("%s.xml" % unit)
        if ctx.args.dry_run:
            console.log("[install] DRY-RUN would write %s and %s" % (exe, xml))
            continue
        shutil.copy2(str(winsw), str(exe))
        xml.write_text(text, encoding="utf-8", newline="\r\n")
        console.log("[install] registered service from %s" % xml)
        run([str(exe), "install"], check=True)
    if ctx.args.dry_run:
        return {"dryRun": True}
    run([str(generated / ("%s.exe" % GATEWAY_ID)), "start"], check=True)
    ok, status = wait_for_http("http://127.0.0.1:%d/health" % gateway, ctx.args.ready_timeout,
                               validator=lambda payload: isinstance(payload, dict)
                               and payload.get("status") == "ok")
    if not ok:
        raise InstallError("gateway did not become ready (last status=%s); see %s\\gateway.log"
                           % (status, ctx.logs))
    if not ctx.args.skip_frontend:
        run([str(generated / ("%s.exe" % FRONTEND_ID)), "start"], check=True)
    return {"units": units}


def uninstall(ctx, console):
    if ctx.is_windows:
        generated = ctx.winsw_template.parent
        for unit in (FRONTEND_ID, GATEWAY_ID):
            exe = generated / ("%s.exe" % unit)
            if exe.is_file():
                run([str(exe), "stop"])
                run([str(exe), "uninstall"])
                console.log("[uninstall] removed service %s" % unit)
        return {"removed": [FRONTEND_ID, GATEWAY_ID]}
    if os.geteuid() != 0 and not ctx.args.dry_run:
        raise InstallError("systemd uninstall requires root; re-run with sudo")
    removed = []
    for unit in (FRONTEND_ID, GATEWAY_ID):
        target = Path("/etc/systemd/system/%s.service" % unit)
        if not target.exists():
            continue
        if ctx.args.dry_run:
            console.log("[uninstall] DRY-RUN would remove %s" % target)
            continue
        run(["systemctl", "disable", "--now", "%s.service" % unit])
        target.unlink()
        removed.append(unit)
    if not ctx.args.dry_run:
        run(["systemctl", "daemon-reload"], check=True)
    console.log("[uninstall] removed=%s (config/data/logs kept)" % (removed or "none"))
    return {"removed": removed}


def do_start(ctx, gateway, frontend, console):
    """Direct (non-service) launch: quick-start without root, PID recorded in config/run.json."""
    if ctx.run_state.is_file():
        raise InstallError("already started according to %s; run stop first" % ctx.run_state)
    if not ctx.env_template.is_file():
        raise InstallError("env template is missing: %s" % ctx.env_template)
    write_env_file(ctx, gateway, frontend, console)
    for name, port in (("gateway", gateway), ("frontend", frontend)):
        if ctx.args.skip_frontend and name == "frontend":
            continue
        if not port_bindable(port):
            raise InstallError("%s port %d is already in use; stop the other instance first"
                               % (name, port))
    if not ctx.args.skip_frontend and not ctx.frontend_entry().is_file():
        raise InstallError("frontend dependency is missing: %s (run pnpm install, or use "
                           "--skip-frontend)" % ctx.frontend_entry())
    env = build_launch_env(ctx)
    ctx.logs.mkdir(parents=True, exist_ok=True)
    jar = ctx.prefix / "app" / _jar_name(ctx.prefix)
    stamp = now_stamp()
    gw_out = ctx.logs / ("gateway-%s.out.log" % stamp)
    gw_err = ctx.logs / ("gateway-%s.err.log" % stamp)
    console.log("[start] gateway exec %s -jar %s" % (ctx.java_bin(), jar.name))
    gateway_proc = spawn([ctx.java_bin(), "-Xms256m", "-Xmx768m", "-jar", str(jar)],
                         ctx.prefix / "repo", env, gw_out, gw_err)
    ok, status = wait_for_http("http://127.0.0.1:%d/health" % gateway, ctx.args.ready_timeout,
                               validator=lambda payload: isinstance(payload, dict)
                               and payload.get("status") == "ok")
    if not ok:
        stop_pid(gateway_proc.pid)
        raise InstallError("gateway did not become ready (last status=%s); see %s, %s"
                           % (status, gw_out, gw_err))
    run_state = {"gatewayPid": gateway_proc.pid, "gatewayUrl": "http://127.0.0.1:%d" % gateway,
                 "gatewayLogs": [str(gw_out), str(gw_err)], "startedAt": datetime.now().isoformat()}
    if not ctx.args.skip_frontend:
        fe_out = ctx.logs / ("frontend-%s.out.log" % stamp)
        fe_err = ctx.logs / ("frontend-%s.err.log" % stamp)
        argv = [ctx.node_bin(), str(ctx.frontend_entry()), "start",
                "--hostname", "127.0.0.1", "--port", str(frontend)]
        console.log("[start] frontend exec %s" % " ".join(argv))
        frontend_proc = spawn(argv, ctx.prefix / "repo" / "frontend", env, fe_out, fe_err)
        ok, status = wait_for_http("http://127.0.0.1:%d/" % frontend, ctx.args.ready_timeout)
        if not ok:
            stop_pid(frontend_proc.pid)
            stop_pid(gateway_proc.pid)
            raise InstallError("frontend did not become ready (last status=%s); see %s, %s"
                               % (status, fe_out, fe_err))
        run_state.update({"frontendPid": frontend_proc.pid,
                          "frontendUrl": "http://127.0.0.1:%d" % frontend,
                          "frontendLogs": [str(fe_out), str(fe_err)]})
    with open(str(ctx.run_state), "w", encoding="utf-8", newline="\n") as handle:
        json.dump(run_state, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    console.log("[start] gateway=%s frontend=%s"
                % (run_state["gatewayUrl"], run_state.get("frontendUrl", "SKIPPED")))
    console.log("[start] open http://127.0.0.1:%d/ and stop with quick-stop" % frontend)
    return run_state


def do_stop(ctx, console):
    state = {}
    try:
        state = json.loads(ctx.run_state.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        state = {}
    pids = [state.get("frontendPid"), state.get("gatewayPid")]
    stopped = []
    for pid in pids:
        if isinstance(pid, int):
            stop_pid(pid)
            stopped.append(pid)
    if ctx.run_state.is_file():
        ctx.run_state.unlink()
    console.log("[stop] stopped=%s (config/data/logs kept)" % (stopped or "none"))
    return {"stopped": stopped}


def service_state(unit):
    if os.name == "nt":
        code, output = run(["sc.exe", "query", unit])
        if code != 0:
            return "not-installed"
        return "running" if "RUNNING" in output.upper() else "stopped"
    code, output = run(["systemctl", "is-active", unit])
    return output or "unknown"


def do_status(ctx, gateway, frontend, console):
    report = {
        "prefix": str(ctx.prefix),
        "gatewayUnit": service_state(GATEWAY_ID),
        "frontendUnit": service_state(FRONTEND_ID) if not ctx.args.skip_frontend else "SKIPPED",
        "gatewayHealthy": False,
        "principalOk": False,
        "frontendHttp": 0,
        "nonLoopback": [],
        "running": False,
    }
    status, health = http_json("http://127.0.0.1:%d/health" % gateway, timeout=5.0)
    report["gatewayHealthy"] = status == 200 and isinstance(health, dict) and health.get("status") == "ok"
    status, principal = http_json("http://127.0.0.1:%d/api/v1/auth/me" % gateway, timeout=5.0)
    report["principalOk"] = (status == 200 and isinstance(principal, dict)
                             and principal.get("id") == "default"
                             and principal.get("system_role") == "admin"
                             and principal.get("needs_setup") is False)
    if not ctx.args.skip_frontend:
        report["frontendHttp"] = http_status("http://127.0.0.1:%d/" % frontend)
    report["nonLoopback"] = ["%s:%d" % (address, port)
                             for address, port in listening_addresses((gateway, frontend))
                             if not is_loopback_address(address)]
    report["running"] = bool(report["gatewayHealthy"] and report["principalOk"]
                             and not report["nonLoopback"]
                             and (ctx.args.skip_frontend or report["frontendHttp"] == 200))
    if ctx.args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
        return EXIT_OK if report["running"] else EXIT_NOT_RUNNING
    checks = []
    if report["gatewayUnit"] != "not-installed":
        checks.append(("gateway service", report["gatewayUnit"] in ("active", "running"),
                       report["gatewayUnit"]))
    else:
        console.log("  [SKIP] gateway service -> not installed (quick-start mode)")
    checks += [
        ("gateway /health", report["gatewayHealthy"], report["gatewayHealthy"]),
        ("principal id=default", report["principalOk"], report["principalOk"]),
        ("loopback only", not report["nonLoopback"], report["nonLoopback"] or "none"),
    ]
    if not ctx.args.skip_frontend:
        if report["frontendUnit"] != "not-installed":
            checks.append(("frontend service", report["frontendUnit"] in ("active", "running"),
                           report["frontendUnit"]))
        else:
            console.log("  [SKIP] frontend service -> not installed (quick-start mode)")
        checks.append(("frontend / == 200", report["frontendHttp"] == 200, report["frontendHttp"]))
    for name, ok, detail in checks:
        console.log("  [%s] %s -> %s" % ("PASS" if ok else "FAIL", name, detail))
    console.log("[status] verdict: %s" % ("RUNNING" if report["running"] else "NOT_RUNNING"))
    return EXIT_OK if report["running"] else EXIT_NOT_RUNNING


def do_doctor(ctx, gateway, frontend, console):
    checks = []
    os_name = platform.system()
    arch = platform.machine().lower()
    is_linux = os_name == "Linux"
    checks.append({
        "name": "operating_system",
        "title": "Operating System",
        "status": "PASS" if is_linux else "WARN",
        "detail": f"{os_name} {platform.release()} ({arch})"
    })

    java_exec = ctx.java_bin()
    code, out = run([java_exec, "-version"])
    java_ok = False
    java_detail = "java executable not found"
    if code == 0 or out:
        m = re.search(r'version "((\d+)[^"]*)"', out) or re.search(r'version ((\d+)[^\s]*)', out)
        if m:
            major = int(m.group(2))
            java_ok = major >= 21
            java_detail = f"Java {m.group(1)} (>=21 required)"
        elif out.strip():
            java_detail = out.strip().splitlines()[0]
    checks.append({
        "name": "java_runtime",
        "title": "Java Runtime (>=21)",
        "status": "PASS" if java_ok else "FAIL",
        "detail": java_detail
    })

    if not ctx.args.skip_frontend:
        node_exec = ctx.node_bin()
        code, out = run([node_exec, "--version"])
        node_ok = False
        node_detail = "node executable not found"
        if code == 0 and out.strip().startswith("v"):
            m = re.match(r"^v(\d+)\.", out.strip())
            if m:
                major = int(m.group(1))
                node_ok = major >= 22
                node_detail = f"{out.strip()} (>=22 required)"
        checks.append({
            "name": "node_runtime",
            "title": "Node.js Runtime (>=22)",
            "status": "PASS" if node_ok else "FAIL",
            "detail": node_detail
        })

    gw_available = port_bindable(gateway)
    fe_available = ctx.args.skip_frontend or port_bindable(frontend)
    checks.append({
        "name": "gateway_port",
        "title": f"Gateway Port {gateway}",
        "status": "PASS" if gw_available else "WARN",
        "detail": "bindable" if gw_available else "in use or reserved"
    })
    if not ctx.args.skip_frontend:
        checks.append({
            "name": "frontend_port",
            "title": f"Frontend Port {frontend}",
            "status": "PASS" if fe_available else "WARN",
            "detail": "bindable" if fe_available else "in use or reserved"
        })

    existing_env = read_env_file(ctx.env_file)
    active_provider = (
        getattr(ctx.args, "execution_provider", None)
        or existing_env.get("HAIFA_SUPER_HARNESS_EXECUTION_PROVIDER")
        or "disabled"
    )
    is_docker_provider = normalize_execution_provider(active_provider) == "docker-aio"

    checks.append({
        "name": "execution_provider",
        "title": "Execution Provider",
        "status": "PASS",
        "detail": f"{active_provider}"
    })

    docker_cli = which("docker")
    docker_cli_status = "PASS" if docker_cli else ("FAIL" if is_docker_provider else "WARN")
    checks.append({
        "name": "docker_cli",
        "title": "Docker CLI",
        "status": docker_cli_status,
        "detail": docker_cli or ("docker command not found in PATH" + (" (required for docker-aio)" if is_docker_provider else " (optional)"))
    })

    daemon_ok = False
    daemon_detail = "docker CLI missing"
    if docker_cli:
        code, out = run([docker_cli, "info", "--format", "{{.ServerVersion}}"])
        if code == 0 and out.strip():
            daemon_ok = True
            daemon_detail = f"Docker Engine {out.strip()} reachable"
        else:
            daemon_detail = f"Failed to connect to Docker daemon: {out.strip()[:200]}"
    daemon_status = "PASS" if daemon_ok else ("FAIL" if is_docker_provider else "WARN")
    checks.append({
        "name": "docker_daemon",
        "title": "Docker Daemon",
        "status": daemon_status,
        "detail": daemon_detail
    })

    img_ok = False
    img_detail = "Docker daemon unreachable"
    lock_data = {}
    try:
        lock_data = load_image_lock(ctx)
    except Exception as e:
        img_detail = f"Sandbox image lock unavailable: {e}"

    if daemon_ok and lock_data:
        img_ok, img_detail, _, _ = inspect_and_verify_image(docker_cli, lock_data)
    elif not daemon_ok:
        img_ok = False
        img_detail = "Docker daemon unreachable"
    else:
        img_ok = False

    image_status = "PASS" if img_ok else ("FAIL" if is_docker_provider else "WARN")
    if not is_docker_provider and not img_ok:
        img_detail += f" (optional when execution provider is '{active_provider}')"
    checks.append({
        "name": "sandbox_aio_image",
        "title": "Sandbox AIO Image",
        "status": image_status,
        "detail": img_detail
    })

    net_ok = False
    net_detail = "Docker daemon unreachable"
    if daemon_ok:
        code, out = run([docker_cli, "network", "inspect", "bridge", "--format", "{{.Driver}}"])
        if code == 0 and "bridge" in out:
            net_ok = True
            net_detail = "Docker bridge driver supported"
        else:
            net_detail = f"Bridge inspect failed: {out.strip()[:200]}"
    net_status = "PASS" if net_ok else ("FAIL" if is_docker_provider else "WARN")
    checks.append({
        "name": "docker_network_bridge",
        "title": "Docker Network Bridge",
        "status": net_status,
        "detail": net_detail
    })

    has_fail = any(c["status"] == "FAIL" for c in checks)
    overall = "FAIL" if has_fail else "PASS"
    result = {
        "overall_status": overall,
        "operating_system": os_name,
        "architecture": arch,
        "checks": checks
    }
    if ctx.args.json:
        print(json.dumps(result, indent=2, ensure_ascii=False))
    else:
        console.log("=== Super Harness Linux Sandbox Doctor ===")
        for c in checks:
            tag = f"[{c['status']}]"
            console.log(f"  {tag:<7} {c['title']:<25} -> {c['detail']}")
        console.log("==========================================")
        console.log(f"[doctor] verdict: {overall}")
    return EXIT_OK if overall == "PASS" else EXIT_PREREQ


def do_prepare(ctx, console):
    docker_cli = which("docker")
    if not docker_cli:
        raise InstallError("docker CLI not found in PATH")
    code, out = run([docker_cli, "info", "--format", "{{.ServerVersion}}"])
    if code != 0:
        raise InstallError(f"Docker daemon unreachable: {out.strip()}")

    lock_data = load_image_lock(ctx)
    locked_img = lock_data.get("image", "")
    if not locked_img:
        raise InstallError("image lock data missing 'image'")
    expected_digest = lock_data.get("digest")

    image_tar = ctx.args.image_tar
    if not image_tar:
        local_images_dir = ctx.prefix / "sandbox" / "images"
        if local_images_dir.is_dir():
            tars = sorted(list(local_images_dir.glob("*.tar")) + list(local_images_dir.glob("*.tar.gz")))
            if tars:
                image_tar = str(tars[0])

    manifest_data = None
    tar_config_id = None
    if image_tar:
        tar_path = Path(image_tar).resolve()
        if not tar_path.is_file():
            raise InstallError(f"Image archive file not found: {tar_path}")
        console.log(f"[prepare] loading offline image from {tar_path}...")

        manifest_file = tar_path.parent / "manifest.json"
        if manifest_file.is_file():
            try:
                manifest_data = json.loads(manifest_file.read_text(encoding="utf-8"))
                manifest_img = manifest_data.get("image")
                if manifest_img and manifest_img != locked_img:
                    raise InstallError(f"Manifest image '{manifest_img}' does not match locked image '{locked_img}'")
                expected_sha = manifest_data.get("sha256")
                if expected_sha:
                    actual_sha = sha256_file(tar_path)
                    if actual_sha != expected_sha:
                        raise InstallError(f"Tar archive sha256 mismatch: expected {expected_sha}, got {actual_sha}")
                    console.log("[prepare] verified tar archive sha256 successfully")
            except Exception as e:
                if isinstance(e, InstallError):
                    raise
                console.log(f"[prepare] warning: failed to parse manifest: {e}")

        tar_config_id = read_docker_tar_config(tar_path)
        if manifest_data and manifest_data.get("image_id") and tar_config_id:
            if manifest_data.get("image_id") != tar_config_id:
                raise InstallError(f"Archive config ID mismatch: manifest expects {manifest_data.get('image_id')} but tar contains {tar_config_id}")

        code, out = run([docker_cli, "load", "-i", str(tar_path)], timeout=600)
        if code != 0:
            raise InstallError(f"docker load failed: {out.strip()}")
        console.log(f"[prepare] docker load output: {out.strip()}")
    else:
        console.log(f"[prepare] pulling locked image {locked_img} from registry...")
        code, out = run([docker_cli, "pull", locked_img], timeout=600)
        if code != 0:
            raise InstallError(f"docker pull failed: {out.strip()}")
        console.log("[prepare] docker pull completed")

    ok, detail, img_id, img_arch = inspect_and_verify_image(
        docker_cli, lock_data, manifest_data=manifest_data, tar_config_id=tar_config_id)
    if not ok:
        raise InstallError(f"Prepared image verification failed: {detail}")

    console.log(f"[prepare] successfully prepared and verified {locked_img} ({detail})")
    return EXIT_OK


def build_parser():
    parser = argparse.ArgumentParser(prog=ENTRY_NAME, description="Haifa Super Harness 安装/服务化工具")
    parser.add_argument("command", nargs="?", default="install",
                        choices=("install", "uninstall", "status", "render", "start", "stop", "doctor", "prepare"))
    parser.add_argument("--prefix", default=str(Path(__file__).resolve().parent.parent))
    parser.add_argument("--data-root", default=None)
    parser.add_argument("--gateway-port", type=int, default=0)
    parser.add_argument("--frontend-port", type=int, default=0)
    parser.add_argument("--service-user", default=None)
    parser.add_argument("--service-group", default=None)
    parser.add_argument("--service-account", default=None, help="Windows 服务账户（默认 LocalSystem）")
    parser.add_argument("--java-bin", default=None)
    parser.add_argument("--node-bin", default=None)
    parser.add_argument("--winsw-exe", default=None)
    parser.add_argument("--execution-provider", choices=("disabled", "docker-aio", "host-guarded"), default=None,
                        help="Execution provider: disabled (default) | docker-aio | host-guarded")
    parser.add_argument("--scheduler-enabled", choices=("0", "1"), default=None,
                        help="Background scheduler daemon: 0 (default) | 1")
    parser.add_argument("--docker-image", default=None, help="Docker AIO sandbox image reference")
    parser.add_argument("--docker-socket", default=None, help="Docker daemon socket path")
    parser.add_argument("--docker-lifecycle", choices=("warm", "ephemeral"), default=None, help="Container lifecycle mode")
    parser.add_argument("--docker-idle-timeout-ms", type=int, default=None, help="Warm container idle timeout in ms")
    parser.add_argument("--image-tar", default=None, help="Path to offline Docker image tar archive for prepare")
    parser.add_argument("--skip-frontend", action="store_true")
    parser.add_argument("--ready-timeout", type=int, default=300)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--json", action="store_true")
    return parser


def main(argv=None):
    _reconfigure_stdio()
    argv = list(sys.argv[1:] if argv is None else argv)
    args = build_parser().parse_args(argv)
    console = _Console()
    try:
        ctx = Context(args)
        if not ctx.prefix.is_dir():
            raise InstallError("prefix not found: %s" % ctx.prefix)
        gateway, frontend = ctx.gateway_ports()
        validate_ports(gateway, frontend, args.skip_frontend)
        if args.command not in ("doctor", "status", "stop"):
            for path in (ctx.config, ctx.data, ctx.logs):
                path.mkdir(parents=True, exist_ok=True)
        if args.command == "doctor":
            return do_doctor(ctx, gateway, frontend, console)
        if args.command == "prepare":
            return do_prepare(ctx, console)
        if args.command == "status":
            return do_status(ctx, gateway, frontend, console)
        if args.command == "stop":
            do_stop(ctx, console)
            return EXIT_OK
        if args.command == "start":
            do_start(ctx, gateway, frontend, console)
            return EXIT_OK
        if args.command == "uninstall":
            uninstall(ctx, console)
            return EXIT_OK
        if args.command == "render":
            do_render(ctx, gateway, frontend, console)
            return EXIT_OK
        write_env_file(ctx, gateway, frontend, console)
        if ctx.is_windows:
            install_winsw(ctx, gateway, frontend, console)
        else:
            if not ctx.args.skip_frontend and not ctx.frontend_entry().is_file():
                raise InstallError("frontend dependency is missing: %s (run pnpm install, or "
                                   "re-install with --skip-frontend)" % ctx.frontend_entry())
            install_systemd(ctx, gateway, frontend, console)
        console.log("[install] done prefix=%s gateway=%d frontend=%s"
                    % (ctx.prefix, gateway, "SKIPPED" if args.skip_frontend else frontend))
        console.log("[install] next: open http://127.0.0.1:%d/ and configure a model API key"
                    % frontend)
        return EXIT_OK
    except InstallError as error:
        print("[main] failed: %s" % error, file=sys.stderr)
        return EXIT_PREREQ
    except KeyboardInterrupt:
        print("[main] interrupted", file=sys.stderr)
        return EXIT_USAGE


class _Console:
    def log(self, message=""):
        print(str(message), flush=True)


if __name__ == "__main__":
    sys.exit(main())

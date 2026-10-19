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
import json
import os
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


def run(argv, timeout=120, check=False, shell=False):
    proc = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          text=True, errors="replace", timeout=timeout, shell=shell)
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


def write_env_file(ctx, gateway, frontend, console):
    if not ctx.env_template.is_file():
        raise InstallError("env template is missing: %s" % ctx.env_template)
    existing = read_env_file(ctx.env_file)
    key_state = ensure_continuation_key(existing, console)
    rendered = render_template(ctx.env_template, {
        "gateway_port": gateway,
        "frontend_port": frontend,
        "data_root": str(ctx.data).replace("\\", "/"),
        "skills_dir": str(ctx.prefix / "skills").replace("\\", "/"),
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


def build_parser():
    parser = argparse.ArgumentParser(prog=ENTRY_NAME, description="Haifa Super Harness 安装/服务化工具")
    parser.add_argument("command", nargs="?", default="install",
                        choices=("install", "uninstall", "status", "render", "start", "stop"))
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
        for path in (ctx.config, ctx.data, ctx.logs):
            path.mkdir(parents=True, exist_ok=True)
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

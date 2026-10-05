"""Status checks and one-command refresh for the fixed-port main service.

The main worktree runs a Gateway copied to ``local-tmp/main-service/<short sha>/`` and a
production frontend from another checkout. This module owns the logic; the PowerShell
wrappers (``scripts/refresh-main-service.ps1`` and ``scripts/start-main-super-harness.ps1``)
only resolve paths, set the process environment and pass arguments through.

``status`` only reads: the listening PIDs on both ports, whether each command line is the
main service, the running Gateway JAR and the source commit from the build stamp next to it,
``/health``, ``/api/v1/auth/me``, the frontend home page and the frontend-proxied Gateway.

``refresh`` runs in a fixed order and stops at the first failure:

1. the build stamp beside ``--jar`` must record ``--expect-sha``;
2. the JAR is copied to ``main-service/<short sha>/`` with the stamp, never run from a
   ``target`` directory;
3. only PIDs whose command line is the main service are stopped; a foreign listener on a
   port is reported with its PID and left running;
4. both ports must actually accept a loopback bind within 20 seconds;
5. the copied Gateway starts and must answer ``/health`` and ``/api/v1/auth/me``;
6. the frontend checkout's ``HEAD:frontend`` tree must match ``--expect-sha`` and a healthy
   frontend is reused (Gateway-only runs skip the frontend);
7. smoke checks mirror ``status``; ``--live-smoke`` additionally sends one shortest real
   conversation Run through the configured managed model and asserts a final answer with no
   ``error`` frame (recorded as NOT_RUN without the switch);
8. a Gateway that never becomes ready is stopped and the previous JAR from ``service.json``
   is started again, with the round recorded as a failure.

The round summary is written to ``main-service/<short sha>/refresh-<timestamp>.json``.
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
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
from pathlib import Path

DEFAULT_GATEWAY_PORT = 18018
DEFAULT_FRONTEND_PORT = 33018
DEFAULT_DATA_DIRECTORY = "local-tmp/main-service-data"
STAMP_NAME = "haifa-build-stamp.json"
JAR_MARKER = "super-harness-server"
EXEC_SUFFIX = "-exec.jar"
READY_TIMEOUT_SECONDS = 180
PORT_RELEASE_TIMEOUT_SECONDS = 20


class MainServiceError(RuntimeError):
    """Raised when the service scope or a required input is invalid."""


# ---------------------------------------------------------------------------
# Pure decisions (unit tested without any process, port or network)
# ---------------------------------------------------------------------------

def looks_like_main_service(command_line: str | None, jar_path: str | None = None) -> bool:
    if not command_line:
        return False
    lowered = command_line.lower()
    if jar_path and jar_path.lower().replace("\\", "/") in lowered.replace("\\", "/"):
        return True
    return "java" in lowered and "-jar" in lowered and JAR_MARKER in lowered and EXEC_SUFFIX in lowered


def jar_from_command_line(command_line: str | None) -> str | None:
    for token in re.findall(r'"[^"]*"|\S+', command_line or ""):
        token = token.strip('"')
        if token.endswith(EXEC_SUFFIX):
            return token
    return None


def stamp_source_sha(jar_path: Path) -> str:
    stamp = jar_path.parent / STAMP_NAME
    if not stamp.is_file():
        raise MainServiceError(f"build stamp is missing at '{stamp}'; the JAR was not built by the gate.")
    data = json.loads(stamp.read_text(encoding="utf-8-sig"))
    source_sha = str(data.get("sourceSha", "")).strip()
    if not source_sha:
        raise MainServiceError(f"build stamp '{stamp}' has no sourceSha.")
    return source_sha


def rollback_decision(new_gateway_ready: bool, previous_jar_exists: bool) -> str:
    if new_gateway_ready:
        return "keep"
    return "rollback" if previous_jar_exists else "stop"


def validate_scope(worktree: Path, gateway_port: int, frontend_port: int, data_root: Path,
                   main_service_root: Path, allow_slot: bool) -> None:
    """Without the regression switch the main service is fixed to 18018/33018 and its data dir."""
    if allow_slot:
        if not data_root.resolve().is_relative_to(worktree.resolve()):
            raise MainServiceError(f"data directory '{data_root}' is outside the worktree '{worktree}'.")
        return
    if gateway_port != DEFAULT_GATEWAY_PORT or frontend_port != DEFAULT_FRONTEND_PORT:
        raise MainServiceError(
            f"the main service must use Gateway {DEFAULT_GATEWAY_PORT} / Frontend {DEFAULT_FRONTEND_PORT}; "
            "use the regression switch only in a slot rehearsal.")
    expected = (worktree / DEFAULT_DATA_DIRECTORY).resolve()
    if data_root.resolve() != expected:
        raise MainServiceError(f"the main service data directory must be '{expected}'.")


# ---------------------------------------------------------------------------
# OS and network helpers
# ---------------------------------------------------------------------------

def listeners(port: int) -> list[int]:
    result = subprocess.run(["netstat", "-ano", "-p", "tcp"], capture_output=True, text=True,
                            encoding="utf-8", errors="replace")
    pids: set[int] = set()
    for line in result.stdout.splitlines():
        parts = line.split()
        if len(parts) >= 5 and parts[0].upper() == "TCP" and parts[3].upper() == "LISTENING":
            local = parts[1]
            if local.rsplit(":", 1)[-1] == str(port):
                pids.add(int(parts[4]))
    return sorted(pids)


def process_command_line(pid: int) -> str:
    script = f"(Get-CimInstance Win32_Process -Filter 'ProcessId={pid}').CommandLine"
    result = subprocess.run(["pwsh", "-NoProfile", "-Command", script], capture_output=True, text=True,
                            encoding="utf-8", errors="replace")
    return result.stdout.strip()


def port_bindable(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        try:
            probe.bind(("127.0.0.1", port))
            return True
        except OSError:
            return False


def wait_for_free_ports(ports: list[int], timeout: float = PORT_RELEASE_TIMEOUT_SECONDS) -> tuple[bool, float]:
    started = time.monotonic()
    while time.monotonic() - started < timeout:
        if all(port_bindable(port) for port in ports):
            return True, time.monotonic() - started
        time.sleep(0.5)
    return False, time.monotonic() - started


def http_json(url: str, timeout: float = 3.0) -> tuple[int, object]:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            return response.status, json.loads(response.read().decode("utf-8", "replace") or "null")
    except urllib.error.HTTPError as error:
        return error.code, None
    except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError):
        return 0, None


def http_status(url: str, timeout: float = 3.0) -> int:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code
    except (urllib.error.URLError, TimeoutError, OSError):
        return 0


def gateway_ready(base: str) -> bool:
    status, health = http_json(f"{base}/health")
    if status != 200 or not isinstance(health, dict) or health.get("status") != "ok":
        return False
    status, me = http_json(f"{base}/api/v1/auth/me")
    return status == 200 and isinstance(me, dict) and me.get("id") == "default"


def wait_for_gateway(base: str, timeout: int, process: subprocess.Popen | None = None) -> tuple[bool, float]:
    started = time.monotonic()
    while time.monotonic() - started < timeout:
        if process is not None and process.poll() is not None:
            return False, time.monotonic() - started
        if gateway_ready(base):
            return True, time.monotonic() - started
        time.sleep(0.5)
    return False, time.monotonic() - started


def stop_pid(pid: int) -> bool:
    result = subprocess.run(["taskkill", "/PID", str(pid), "/F", "/T"], capture_output=True, text=True,
                            encoding="utf-8", errors="replace")
    return result.returncode == 0


def git_output(repo: Path, *args: str) -> str:
    result = subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True,
                            encoding="utf-8", errors="replace")
    if result.returncode != 0:
        raise MainServiceError(f"git {' '.join(args)} failed in '{repo}': {result.stderr.strip()}")
    return result.stdout.strip()


def java_executable() -> str:
    return shutil.which("java") or "java"


def install_jar(jar: Path, main_service_root: Path, short: str) -> Path:
    target_dir = main_service_root / short
    target_dir.mkdir(parents=True, exist_ok=True)
    target_jar = target_dir / jar.name
    shutil.copy2(jar, target_jar)
    stamp = jar.parent / STAMP_NAME
    if stamp.is_file():
        shutil.copy2(stamp, target_dir / STAMP_NAME)
    return target_jar


def read_service_record(main_service_root: Path) -> dict:
    path = main_service_root / "service.json"
    if path.is_file():
        return json.loads(path.read_text(encoding="utf-8-sig"))
    return {"active": None, "previous": None}


def write_service_record(main_service_root: Path, active: dict, previous: dict | None) -> None:
    main_service_root.mkdir(parents=True, exist_ok=True)
    (main_service_root / "service.json").write_text(
        json.dumps({"active": active, "previous": previous}, indent=2, ensure_ascii=False), encoding="utf-8")


def start_gateway(jar: Path, worktree: Path, data_root: Path, gateway_port: int, frontend_port: int,
                  log_dir: Path, stamp: str) -> tuple[subprocess.Popen, list[str]]:
    log_dir.mkdir(parents=True, exist_ok=True)
    out = log_dir / f"gateway-{stamp}.out.log"
    err = log_dir / f"gateway-{stamp}.err.log"
    environment = dict(os.environ)
    environment.update({
        "SUPER_HARNESS_GATEWAY_KIND": "haifa",
        "SUPER_HARNESS_PROFILE": "local",
        "SUPER_HARNESS_BIND_ADDRESS": "127.0.0.1",
        "SUPER_HARNESS_PORT": str(gateway_port),
        "SUPER_HARNESS_DATA_ROOT": str(data_root),
        "SUPER_HARNESS_DATA_DIR": str(data_root),
        "SUPER_HARNESS_ENVIRONMENT": "development",
        "DEER_FLOW_AUTH_DISABLED": "1",
        "DEER_FLOW_ENV": "development",
        "ENVIRONMENT": "development",
        "DEER_FLOW_TRUSTED_ORIGINS": f"http://127.0.0.1:{frontend_port}",
        "NO_PROXY": "127.0.0.1,localhost",
        "no_proxy": "127.0.0.1,localhost",
    })
    handle_out = out.open("w", encoding="utf-8")
    handle_err = err.open("w", encoding="utf-8")
    process = subprocess.Popen([java_executable(), "-jar", str(jar)], cwd=str(worktree),
                               stdout=handle_out, stderr=handle_err, env=environment)
    return process, [str(out), str(err)]


# ---------------------------------------------------------------------------
# Status
# ---------------------------------------------------------------------------

def gateway_status(gateway_port: int) -> dict:
    pids = listeners(gateway_port)
    matched = []
    for pid in pids:
        command = process_command_line(pid)
        if looks_like_main_service(command):
            jar = jar_from_command_line(command)
            source_sha = None
            stamp_error = None
            if jar:
                try:
                    source_sha = stamp_source_sha(Path(jar))
                except (MainServiceError, OSError, json.JSONDecodeError) as error:
                    stamp_error = str(error)
            matched.append({"pid": pid, "jar": jar, "sourceSha": source_sha, "stampError": stamp_error})
    return {"port": gateway_port, "listeners": pids, "matched": matched}


def status_impl(gateway_port: int, frontend_port: int, gateway_only: bool) -> dict:
    gateway_url = f"http://127.0.0.1:{gateway_port}"
    frontend_url = f"http://127.0.0.1:{frontend_port}"
    gateway = gateway_status(gateway_port)
    checks = []

    def add(name: str, ok: bool, detail: str) -> None:
        checks.append({"name": name, "ok": bool(ok), "detail": detail})

    add("gateway-listener", bool(gateway["matched"]),
        f"listeners={gateway['listeners']} matched={[m['pid'] for m in gateway['matched']]}")
    jar_ok = bool(gateway["matched"]) and all(m["sourceSha"] for m in gateway["matched"])
    add("gateway-jar-stamp", jar_ok,
        "; ".join(f"pid={m['pid']} jar={m['jar']} sha={m['sourceSha']} {m['stampError'] or ''}".strip()
                  for m in gateway["matched"]) or "no matched gateway process")

    health_ok = gateway_ready(gateway_url)
    status, me = http_json(f"{gateway_url}/api/v1/auth/me")
    add("health", health_ok, f"GET /health ok={health_ok}")
    add("auth-me", status == 200 and isinstance(me, dict) and me.get("id") == "default", f"status={status}")

    frontend = {"port": frontend_port, "listeners": listeners(frontend_port)}
    if gateway_only:
        frontend["home"] = "NOT_RUN"
        frontend["proxy"] = "NOT_RUN"
    else:
        home = http_status(frontend_url)
        proxy = http_status(f"{frontend_url}/api/v1/auth/me")
        frontend["home"] = home
        frontend["proxy"] = proxy
        add("frontend-listener", bool(frontend["listeners"]), f"listeners={frontend['listeners']}")
        add("frontend-home", 200 <= home < 400, f"status={home}")
        add("frontend-proxy", proxy == 200, f"GET /api/v1/auth/me status={proxy}")

    return {
        "ok": all(check["ok"] for check in checks),
        "gateway": gateway,
        "frontend": frontend,
        "checks": checks,
    }


# ---------------------------------------------------------------------------
# Refresh
# ---------------------------------------------------------------------------

def live_smoke(gateway_url: str, timeout: float = 120.0) -> dict:
    """One shortest real conversation Run against the configured managed model."""
    started = time.monotonic()
    status, thread = http_json(f"{gateway_url}/api/threads", timeout=10)
    if status != 200 or not isinstance(thread, dict) or not thread.get("thread_id"):
        return {"status": "FAIL", "reason": f"thread create status={status}", "elapsedMs": 0}
    thread_id = thread["thread_id"]
    model = None
    models_status, models = http_json(f"{gateway_url}/api/managed-models", timeout=10)
    if models_status == 200 and isinstance(models, dict):
        entries = models.get("models") or []
        if isinstance(entries, list) and entries:
            first = entries[0]
            model = first.get("name") if isinstance(first, dict) else None
    payload = {
        "assistant_id": "lead_agent",
        "input": {"messages": [{"type": "human", "content": [{"type": "text", "text": "Reply with the single word: pong"}],
                                "additional_kwargs": {}}]},
        "context": {"model_name": model} if model else {},
        "stream_mode": ["messages-tuple", "updates", "custom"],
        "on_disconnect": "continue",
    }
    request = urllib.request.Request(f"{gateway_url}/api/threads/{thread_id}/runs/stream",
                                     data=json.dumps(payload).encode("utf-8"),
                                     headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            run_location = response.headers.get("content-location") or ""
            body = response.read().decode("utf-8", "replace")
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        return {"status": "FAIL", "reason": f"run stream failed: {error}", "elapsedMs": int((time.monotonic() - started) * 1000)}
    match = re.search(r"/runs/([^/?]+)", run_location)
    run_id = match.group(1) if match else None
    has_error = bool(re.search(r"(?m)^event:\s*error\b", body)) or '"type": "error"' in body
    answered = bool(body.strip())
    return {
        "status": "PASS" if answered and not has_error else "FAIL",
        "model": model,
        "threadId": thread_id,
        "runId": run_id,
        "elapsedMs": int((time.monotonic() - started) * 1000),
        "hasErrorFrame": has_error,
        "bytes": len(body),
    }


def refresh_impl(args) -> tuple[int, dict]:
    worktree = args.worktree.resolve()
    data_root = args.data_root.resolve()
    main_service_root = args.main_service_root.resolve()
    validate_scope(worktree, args.gateway_port, args.frontend_port, data_root, main_service_root, args.allow_slot)
    gateway_url = f"http://127.0.0.1:{args.gateway_port}"
    frontend_url = f"http://127.0.0.1:{args.frontend_port}"
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    log_dir = main_service_root / "logs"
    steps: list[dict] = []

    def step(name: str, ok: bool, detail: str, elapsed_ms: int) -> None:
        steps.append({"name": name, "status": "PASS" if ok else "FAIL", "detail": detail, "elapsedMs": elapsed_ms})

    jar = args.jar.resolve()
    # 1. build stamp
    started = time.monotonic()
    recorded = stamp_source_sha(jar)
    if recorded != args.expect_sha:
        step("build-stamp", False, f"stamp sourceSha={recorded} expected={args.expect_sha}", 0)
        return 1, {"status": "FAIL", "sourceSha": args.expect_sha, "reason": "build stamp mismatch", "steps": steps}
    step("build-stamp", True, f"sourceSha={recorded}", int((time.monotonic() - started) * 1000))

    short = args.expect_sha[:8]
    # 2. copy the JAR
    started = time.monotonic()
    record = read_service_record(main_service_root)
    previous = record.get("active")
    installed = install_jar(jar, main_service_root, short)
    jar_sha = _sha256(installed)
    active = {"sha": args.expect_sha, "short": short, "jar": str(installed), "sha256": jar_sha,
              "length": installed.stat().st_size}
    step("copy-jar", True, f"copied to {installed} sha256={jar_sha[:12]}", int((time.monotonic() - started) * 1000))

    # 3. stop only the exact main-service processes
    started = time.monotonic()
    old_pids: list[int] = []
    for port in (args.gateway_port,):
        for pid in listeners(port):
            command = process_command_line(pid)
            if looks_like_main_service(command):
                old_pids.append(pid)
            else:
                step("stop-old", False, f"port {port} is held by foreign PID {pid}: {command}", 0)
                return 1, {"status": "FAIL", "sourceSha": args.expect_sha, "reason": "foreign listener",
                           "foreignPid": pid, "steps": steps}
    for pid in sorted(set(old_pids)):
        stop_pid(pid)
    step("stop-old", True, f"stopped pids={sorted(set(old_pids))}", int((time.monotonic() - started) * 1000))

    # 4. ports must actually bind again
    started = time.monotonic()
    free, waited = wait_for_free_ports([args.gateway_port])
    if not free:
        step("port-release", False, f"gateway port {args.gateway_port} not bindable within {PORT_RELEASE_TIMEOUT_SECONDS}s", int(waited * 1000))
        return 1, {"status": "FAIL", "sourceSha": args.expect_sha, "reason": "port not released", "steps": steps}
    step("port-release", True, f"bound after {waited:.1f}s", int(waited * 1000))

    # 5. start the copied Gateway
    started = time.monotonic()
    process, logs = start_gateway(installed, worktree, data_root, args.gateway_port, args.frontend_port, log_dir, stamp)
    ready, ready_ms = wait_for_gateway(gateway_url, args.ready_timeout_seconds, process)
    step("start-gateway", ready, f"pid={process.pid} ready={ready} after {ready_ms:.1f}s logs={logs}",
         int((time.monotonic() - started) * 1000))

    new_pid = process.pid if ready else None
    if ready:
        write_service_record(main_service_root, active, previous)
    else:
        stop_pid(process.pid)

    summary = {
        "status": "PASS" if ready else "FAIL",
        "sourceSha": args.expect_sha,
        "jarSha256": jar_sha,
        "jarLength": active["length"],
        "installedJar": str(installed),
        "previous": previous,
        "oldGatewayPid": old_pids[0] if old_pids else None,
        "newGatewayPid": new_pid,
        "gatewayPid": process.pid,
        "steps": steps,
        "logs": logs,
        "frontend": "NOT_RUN" if args.gateway_only else _frontend_result(args, worktree, gateway_url),
        "smoke": status_impl(args.gateway_port, args.frontend_port, args.gateway_only) if ready else None,
        "liveSmoke": {"status": "NOT_RUN"} if not args.live_smoke else (live_smoke(gateway_url) if ready else {"status": "FAIL"}),
    }

    # 6. rollback when the new Gateway never became ready
    if not ready:
        previous_jar = Path(previous["jar"]) if previous and previous.get("jar") else None
        decision = rollback_decision(False, bool(previous_jar and previous_jar.is_file()))
        summary["rollback"] = decision
        if decision == "rollback":
            rolled, roll_ms = _start_previous(previous_jar, worktree, data_root, args, log_dir, stamp)
            summary["rollbackReady"] = rolled
            summary["rollbackMs"] = int(roll_ms * 1000)

    summary_path = main_service_root / short / f"refresh-{stamp}.json"
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    summary["elapsedMs"] = sum(s["elapsedMs"] for s in steps)
    summary["summaryPath"] = str(summary_path)
    summary_path.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    return (0 if ready else 1), summary


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _frontend_result(args, worktree: Path, gateway_url: str) -> dict:
    if not args.frontend_root:
        return {"status": "NOT_RUN", "reason": "no --frontend-root"}
    frontend_root = args.frontend_root.resolve()
    expected_tree = git_output(worktree, "rev-parse", f"{args.expect_sha}:frontend")
    actual_tree = git_output(frontend_root, "rev-parse", "HEAD:frontend")
    healthy = http_status(f"http://127.0.0.1:{args.frontend_port}") in range(200, 400)
    if actual_tree != expected_tree:
        return {"status": "FAIL", "reason": "frontend tree does not match the expected commit",
                "expectedTree": expected_tree, "actualTree": actual_tree}
    if healthy:
        return {"status": "REUSED", "tree": actual_tree}
    return {"status": "FAIL", "reason": "frontend is not healthy and automatic restart was not attempted"}


def _start_previous(previous_jar: Path, worktree: Path, data_root: Path, args, log_dir: Path,
                    stamp: str) -> tuple[bool, float]:
    process, _ = start_gateway(previous_jar, worktree, data_root, args.gateway_port, args.frontend_port,
                               log_dir, f"rollback-{stamp}")
    ready, elapsed = wait_for_gateway(f"http://127.0.0.1:{args.gateway_port}", args.ready_timeout_seconds, process)
    if not ready:
        stop_pid(process.pid)
    return ready, elapsed


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Main service status and refresh")
    parser.add_argument("--worktree", required=True, type=Path)
    parser.add_argument("--gateway-port", type=int, default=DEFAULT_GATEWAY_PORT)
    parser.add_argument("--frontend-port", type=int, default=DEFAULT_FRONTEND_PORT)
    parser.add_argument("--allow-slot", action="store_true")
    sub = parser.add_subparsers(dest="command", required=True)

    status = sub.add_parser("status")
    status.add_argument("--gateway-only", action="store_true")

    refresh = sub.add_parser("refresh")
    refresh.add_argument("--jar", required=True, type=Path)
    refresh.add_argument("--expect-sha", required=True)
    refresh.add_argument("--data-root", required=True, type=Path)
    refresh.add_argument("--main-service-root", required=True, type=Path)
    refresh.add_argument("--frontend-root", type=Path)
    refresh.add_argument("--live-smoke", action="store_true")
    refresh.add_argument("--gateway-only", action="store_true")
    refresh.add_argument("--ready-timeout-seconds", type=int, default=READY_TIMEOUT_SECONDS)

    args = parser.parse_args(argv)
    try:
        if args.command == "status":
            result = status_impl(args.gateway_port, args.frontend_port, args.gateway_only)
            print(json.dumps(result, indent=2, ensure_ascii=False))
            return 0 if result["ok"] else 1
        code, summary = refresh_impl(args)
        print(json.dumps(summary, indent=2, ensure_ascii=False))
        return code
    except MainServiceError as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

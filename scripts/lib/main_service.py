"""Status checks and one-command refresh for the fixed-port main service.

The main worktree runs a Gateway copied to ``local-tmp/main-service/<short sha>/`` and a
production frontend from another checkout. This module owns the logic; the PowerShell
wrappers (``scripts/refresh-main-service.ps1`` and ``scripts/start-main-super-harness.ps1``)
only resolve paths, set the process environment and pass arguments through.

``start`` launches the Gateway for ``start-main-super-harness.ps1``: the local profile, the
persisted product key fallback and the readiness contract (``/health`` plus the expected
``/api/v1/auth/me`` profile) live here, and the call reports the pid, readiness, log paths
and the scope each product key came from. The wrapper keeps only the environment it shares
with the frontend child, so credentials never reach the frontend process.

``status`` only reads: the listening PIDs on both ports, whether each command line is the
main service, the running Gateway JAR and the source commit from the build stamp next to it,
``/health``, ``/api/v1/auth/me``, the frontend home page and the frontend-proxied Gateway.

``refresh`` runs in a fixed order and stops at the first failure:

1. the build stamp beside ``--jar`` must record ``--expect-sha`` or, when both commits resolve
   in ``--worktree``, the same ``^{tree}`` (a squash merge keeps the tree but changes the SHA);
2. the JAR is copied to ``main-service/<short sha>/`` with the stamp, never run from a
   ``target`` directory;
3. only PIDs whose command line is the main service are stopped; a foreign listener on a
   port is reported with its PID and left running; when ``service.json`` has no usable
   ``active``, the stopped process's command-line JAR is kept as ``previous`` for rollback;
4. both ports must actually accept a loopback bind within 20 seconds;
5. the copied Gateway starts (detached from the caller) and must answer ``/health`` and
   ``/api/v1/auth/me``. Its environment is the caller's plus the product keys the caller
   is missing, filled from the persisted Windows user/machine environment
   (``PRODUCT_ENV_KEYS``); the summary records only each key's scope, never its value;
6. the frontend checkout's ``HEAD:frontend`` tree must match ``--expect-sha``; a healthy
   frontend is reused, an unhealthy one on a free port is started in production mode from
   ``--frontend-root`` and must answer the home page and the proxied ``/api/v1/auth/me``
   (Gateway-only runs skip the frontend);
7. smoke mirrors ``status``; ``--live-smoke`` additionally sends one shortest real
   conversation Run through the first executable model from ``/api/models`` and asserts an
   AI answer with non-empty text and no ``error`` frame (recorded as NOT_RUN without the
   switch);
8. the round is ``PASS`` only when the Gateway is ready and smoke, frontend and live-smoke
   all pass; a ready Gateway whose later checks fail is still a failure but is neither
   rolled back nor stopped. A Gateway that never becomes ready is stopped and the
   ``previous`` JAR is started again, with the round recorded as a failure.

Gateway and frontend processes are started detached (new process group, no window, null
stdin) so closing the caller's terminal does not stop them. The round summary is written to
``main-service/<short sha>/refresh-<timestamp>.json``.
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

sys.path.insert(0, str(Path(__file__).resolve().parent))

import quick_checks  # noqa: E402  (path added above)

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


def stamps_match(recorded_sha: str, expected_sha: str, recorded_tree: str | None, expected_tree: str | None) -> tuple[bool, bool]:
    """Accept a stamp when the commit is the same or both resolve to the same tree."""
    tree_match = bool(recorded_tree and expected_tree and recorded_tree == expected_tree)
    return (recorded_sha == expected_sha or tree_match), tree_match


def frontend_decision(tree_match: bool, healthy: bool, port_busy: bool, has_root: bool) -> str:
    """One of FAIL_NO_ROOT, FAIL_TREE, REUSED, FAIL_FOREIGN, START.

    Without ``--frontend-root`` a healthy frontend is reused as it is (its tree cannot be
    compared); only an unhealthy one needs the root to be started.
    """
    if not has_root:
        return "REUSED" if healthy else "FAIL_NO_ROOT"
    if not tree_match:
        return "FAIL_TREE"
    if healthy:
        return "REUSED"
    if port_busy:
        return "FAIL_FOREIGN"
    return "START"


def frontend_start_args(launcher: Path, frontend_root: Path, source_repository: Path, data_root: Path,
                        gateway_url: str, frontend_port: int) -> list[str]:
    """Production frontend launch matching ``start-main-super-harness.ps1``."""
    return ["pwsh", "-NoProfile", "-File", str(launcher),
            "-RepositoryRoot", str(frontend_root),
            "-SourceRepository", str(source_repository),
            "-DataDirectory", str(data_root),
            "-GatewayBaseUrl", gateway_url,
            "-FrontendPort", str(frontend_port),
            "-Production"]


def sse_events(text: str) -> list[tuple[str, str]]:
    """Parse an SSE body into ``(event name, data)`` pairs."""
    events: list[tuple[str, str]] = []
    event_name = "message"
    data_lines: list[str] = []
    for raw in text.splitlines():
        line = raw.rstrip("\r")
        if line.startswith("event:"):
            event_name = line[len("event:"):].strip()
        elif line.startswith("data:"):
            data_lines.append(line[len("data:"):].lstrip())
        elif line == "":
            if data_lines:
                events.append((event_name, "\n".join(data_lines)))
                data_lines = []
                event_name = "message"
    if data_lines:
        events.append((event_name, "\n".join(data_lines)))
    return events


def _message_text(content) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(part["text"] for part in content
                       if isinstance(part, dict) and isinstance(part.get("text"), str))
    return ""


def _is_ai_message(message) -> bool:
    if not isinstance(message, dict):
        return False
    return str(message.get("type", "")).lower() in ("ai", "aimessage", "aimessagechunk", "assistant")


def evaluate_stream(text: str) -> dict:
    """Decide from an SSE body whether an AI answer was streamed and whether it errored."""
    has_answer = False
    has_error = False
    for event_name, data in sse_events(text):
        normalized = event_name.lower()
        if normalized == "error":
            has_error = True
        try:
            payload = json.loads(data)
        except (json.JSONDecodeError, ValueError):
            payload = None
        if isinstance(payload, dict) and str(payload.get("type", "")).lower() == "error":
            has_error = True
        if normalized in ("messages", "messages-tuple"):
            candidates = payload if isinstance(payload, list) else [payload]
            for candidate in candidates:
                if _is_ai_message(candidate) and _message_text(candidate.get("content")).strip():
                    has_answer = True
        if normalized == "values" and isinstance(payload, dict):
            values = payload.get("values") if isinstance(payload.get("values"), dict) else {}
            for message in values.get("messages") or []:
                if _is_ai_message(message) and _message_text(message.get("content")).strip():
                    has_answer = True
    return {"hasAnswer": has_answer, "hasError": has_error}


def model_name(models_payload) -> str | None:
    """First executable model name from ``GET /api/models``."""
    models = models_payload.get("models") if isinstance(models_payload, dict) else None
    if not isinstance(models, list):
        return None
    for entry in models:
        if isinstance(entry, dict) and entry.get("name"):
            return str(entry["name"])
    return None


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


def http_json_post(url: str, body: object, timeout: float = 10.0) -> tuple[int, object]:
    request = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"),
                                     headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
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


def frontend_ready(frontend_url: str) -> bool:
    home = http_status(frontend_url)
    proxy = http_status(f"{frontend_url}/api/v1/auth/me")
    return 200 <= home < 400 and proxy == 200


def wait_for_frontend(frontend_url: str, timeout: int,
                      process: subprocess.Popen | None = None) -> tuple[bool, float]:
    started = time.monotonic()
    while time.monotonic() - started < timeout:
        if process is not None and process.poll() is not None:
            return False, time.monotonic() - started
        if frontend_ready(frontend_url):
            return True, time.monotonic() - started
        time.sleep(0.5)
    return False, time.monotonic() - started


def auth_ready(base: str, auth: str = "default") -> bool:
    """``unauthorized`` expects 401 from ``/api/v1/auth/me``; ``default`` the seeded admin."""
    status, me = http_json(f"{base}/api/v1/auth/me")
    if auth == "unauthorized":
        return status == 401
    return (status == 200 and isinstance(me, dict) and me.get("id") == "default"
            and me.get("email") == "default@test.local" and me.get("system_role") == "admin"
            and me.get("needs_setup") is False)


def gateway_ready(base: str, auth: str = "default") -> bool:
    status, health = http_json(f"{base}/health")
    if status != 200 or not isinstance(health, dict) or health.get("status") != "ok":
        return False
    return auth_ready(base, auth)


def wait_for_gateway(base: str, timeout: int, process: subprocess.Popen | None = None,
                     auth: str = "default") -> tuple[bool, float]:
    started = time.monotonic()
    while time.monotonic() - started < timeout:
        if process is not None and process.poll() is not None:
            return False, time.monotonic() - started
        if gateway_ready(base, auth):
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


def rev_parse(repo: Path, spec: str) -> str | None:
    """``git rev-parse <spec>`` in ``repo``, or None when it cannot be resolved."""
    result = subprocess.run(["git", "-C", str(repo), "rev-parse", "--verify", "--quiet", spec],
                            capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode != 0:
        return None
    return result.stdout.strip() or None


def tree_of(repo: Path, revision: str) -> str | None:
    """``<revision>^{tree}`` in ``repo``, or None when the revision cannot be resolved."""
    return rev_parse(repo, f"{revision}^{{tree}}")


def frontend_tree_of(repo: Path, revision: str) -> str | None:
    """The ``frontend`` subtree of ``revision`` (``<revision>:frontend``), or None."""
    return rev_parse(repo, f"{revision}:frontend")


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


def creation_flags() -> int:
    return subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW


# Product keys the Gateway reads from its own process environment
# (``WebToolConfig.fromEnvironment``). A refresh started from a shell that predates a
# value silently strips it: with ``HAIFA_SUPER_HARNESS_WEB_SEARCH_PROVIDER=deepseek``
# but no ``DEEPSEEK_TOOL_API_KEY`` every ``web_search``/``web_fetch`` call fails in the
# SDK credential gate before the Tool is dispatched. The persisted Windows
# user/machine environment is therefore the fallback authority. Values are never
# logged; only the key name and the scope that supplied it are reported.
PRODUCT_ENV_KEYS = (
    "DEEPSEEK_TOOL_API_KEY",
    "JINA_API_KEY",
    "HAIFA_SUPER_HARNESS_WEB_SEARCH_PROVIDER",
    "HAIFA_SUPER_HARNESS_WEB_FETCH_PROVIDER",
)


def persisted_environment(key: str) -> tuple[str, str] | None:
    """``(value, scope)`` for ``key`` from the Windows user/machine environment."""
    if sys.platform != "win32":
        return None
    try:
        import winreg
    except ImportError:  # pragma: no cover - winreg is part of CPython on Windows
        return None
    locations = (
        ("user", winreg.HKEY_CURRENT_USER, "Environment"),
        ("machine", winreg.HKEY_LOCAL_MACHINE,
         r"SYSTEM\CurrentControlSet\Control\Session Manager\Environment"),
    )
    for scope, hive, path in locations:
        try:
            with winreg.OpenKey(hive, path) as handle:
                value, _kind = winreg.QueryValueEx(handle, key)
        except OSError:
            continue
        if isinstance(value, str) and value.strip():
            return value, scope
    return None


def resolve_product_environment(environment, lookup=None) -> tuple[dict, dict]:
    """Fill ``PRODUCT_ENV_KEYS`` the caller's shell is missing from the persisted scope.

    Returns ``(resolved, sources)`` where ``sources`` maps every key the Gateway will
    see to ``process``/``user``/``machine``. Keys absent everywhere stay absent so the
    Gateway keeps its own default (DuckDuckGo/Jina, no credential requirement).
    """
    resolve = lookup if lookup is not None else persisted_environment
    resolved = dict(environment)
    sources: dict[str, str] = {}
    for key in PRODUCT_ENV_KEYS:
        current = resolved.get(key)
        if isinstance(current, str) and current.strip():
            sources[key] = "process"
            continue
        found = resolve(key)
        if not found:
            continue
        value, scope = found
        if not isinstance(value, str) or not value.strip():
            continue
        resolved[key] = value
        sources[key] = scope
    return resolved, sources


def describe_product_environment(sources: dict) -> str:
    """Value-free ``key:scope`` summary for the refresh report and step details."""
    return ",".join(f"{key}:{sources[key]}" for key in sorted(sources))


def gateway_environment(data_root: Path, gateway_port: int, frontend_port: int,
                        local_auth: bool = False, base=None, lookup=None) -> tuple[dict, dict]:
    """The Gateway child environment: the caller's plus the local profile and product keys.

    ``local_auth`` selects between the two auth profiles the launch entry points offer.
    Only the returned mapping is handed to the Java child, so a credential filled from the
    persisted scope never reaches the frontend process that the same wrapper starts, and
    the caller's own environment is never mutated.
    """
    environment, sources = resolve_product_environment(os.environ if base is None else base, lookup)
    environment.update({
        "SUPER_HARNESS_GATEWAY_KIND": "haifa",
        "SUPER_HARNESS_PROFILE": "local",
        "SUPER_HARNESS_BIND_ADDRESS": "127.0.0.1",
        "SUPER_HARNESS_PORT": str(gateway_port),
        "SUPER_HARNESS_DATA_ROOT": str(data_root),
        "SUPER_HARNESS_DATA_DIR": str(data_root),
        "SUPER_HARNESS_ENVIRONMENT": "development",
        "DEER_FLOW_ENV": "development",
        "ENVIRONMENT": "development",
        "DEER_FLOW_TRUSTED_ORIGINS": f"http://127.0.0.1:{frontend_port}",
        "NO_PROXY": "127.0.0.1,localhost",
        "no_proxy": "127.0.0.1,localhost",
    })
    environment.pop("HAIFA_SUPER_HARNESS_LOCAL_AUTH", None)
    environment.pop("HAIFA_SUPER_HARNESS_AUTH_ALLOWED_ORIGINS", None)
    if local_auth:
        environment.pop("DEER_FLOW_AUTH_DISABLED", None)
        environment["HAIFA_SUPER_HARNESS_LOCAL_AUTH"] = "1"
        environment["HAIFA_SUPER_HARNESS_AUTH_ALLOWED_ORIGINS"] = f"http://127.0.0.1:{frontend_port}"
    else:
        environment["DEER_FLOW_AUTH_DISABLED"] = "1"
    return environment, sources


def start_gateway(jar: Path, worktree: Path, data_root: Path, gateway_port: int, frontend_port: int,
                  log_dir: Path, stamp: str, local_auth: bool = False) -> tuple[subprocess.Popen, list[str], dict]:
    log_dir.mkdir(parents=True, exist_ok=True)
    out = log_dir / f"gateway-{stamp}.out.log"
    err = log_dir / f"gateway-{stamp}.err.log"
    environment, product_env = gateway_environment(data_root, gateway_port, frontend_port, local_auth)
    handle_out = out.open("w", encoding="utf-8")
    handle_err = err.open("w", encoding="utf-8")
    process = subprocess.Popen([java_executable(), "-jar", str(jar)], cwd=str(worktree),
                               stdout=handle_out, stderr=handle_err, env=environment,
                               stdin=subprocess.DEVNULL, creationflags=creation_flags())
    return process, [str(out), str(err)], product_env


def start_frontend(command: list[str], worktree: Path, log_dir: Path,
                   stamp: str) -> tuple[subprocess.Popen, list[str]]:
    log_dir.mkdir(parents=True, exist_ok=True)
    out = log_dir / f"frontend-{stamp}.out.log"
    err = log_dir / f"frontend-{stamp}.err.log"
    handle_out = out.open("w", encoding="utf-8")
    handle_err = err.open("w", encoding="utf-8")
    process = subprocess.Popen(command, cwd=str(worktree), stdout=handle_out, stderr=handle_err,
                               stdin=subprocess.DEVNULL, creationflags=creation_flags())
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
    """One shortest real conversation Run against the first executable managed model."""
    started = time.monotonic()
    models_status, models = http_json(f"{gateway_url}/api/models", timeout=10)
    model = model_name(models) if models_status == 200 else None
    if not model:
        return {"status": "FAIL", "reason": "没有可执行模型",
                "elapsedMs": int((time.monotonic() - started) * 1000)}
    status, thread = http_json_post(f"{gateway_url}/api/threads", {}, timeout=10)
    if status != 200 or not isinstance(thread, dict) or not thread.get("thread_id"):
        return {"status": "FAIL", "model": model, "reason": f"thread create status={status}",
                "elapsedMs": int((time.monotonic() - started) * 1000)}
    thread_id = thread["thread_id"]
    payload = {
        "assistant_id": "lead_agent",
        "input": {"messages": [{"type": "human", "content": [{"type": "text", "text": "Reply with the single word: pong"}],
                                "additional_kwargs": {}}]},
        "context": {"model_name": model},
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
        return {"status": "FAIL", "model": model, "threadId": thread_id,
                "reason": f"run stream failed: {error}",
                "elapsedMs": int((time.monotonic() - started) * 1000)}
    match = re.search(r"/runs/([^/?]+)", run_location)
    run_id = match.group(1) if match else None
    outcome = evaluate_stream(body)
    token_status, token_usage = http_json(f"{gateway_url}/api/threads/{thread_id}/token-usage", timeout=10)
    return {
        "status": "PASS" if outcome["hasAnswer"] and not outcome["hasError"] else "FAIL",
        "model": model,
        "threadId": thread_id,
        "runId": run_id,
        "elapsedMs": int((time.monotonic() - started) * 1000),
        "hasAnswer": outcome["hasAnswer"],
        "hasError": outcome["hasError"],
        "tokenUsage": token_usage if token_status == 200 else None,
    }


def start_impl(args) -> tuple[int, dict]:
    """Start the Gateway for ``scripts/start-main-super-harness.ps1`` and report readiness.

    The wrapper still resolves paths and prepares the environment it shares with the
    frontend child; this module owns the local profile, the persisted product key fallback
    and the readiness contract, so a direct start and a refresh cannot drift apart and the
    credentials filled from the persisted scope only ever reach the Java child.
    """
    jar = args.jar.resolve()
    if not jar.is_file():
        raise MainServiceError(f"server executable JAR is missing: {jar}")
    process, logs, product_env = start_gateway(
        jar, args.worktree.resolve(), args.data_root.resolve(), args.gateway_port,
        args.frontend_port, args.log_dir.resolve(), args.stamp, local_auth=args.local_auth)
    ready, elapsed = wait_for_gateway(f"http://127.0.0.1:{args.gateway_port}",
                                      args.ready_timeout_seconds, process, args.auth)
    summary = {
        "pid": process.pid,
        "ready": ready,
        "readyMs": int(elapsed * 1000),
        "exited": process.poll() is not None,
        "logs": logs,
        # Key names and the scope that supplied them; never the values.
        "productEnvironment": product_env,
    }
    return (0 if ready else 1), summary


def refresh_impl(args) -> tuple[int, dict]:
    worktree = args.worktree.resolve()
    data_root = args.data_root.resolve()
    main_service_root = args.main_service_root.resolve()
    validate_scope(worktree, args.gateway_port, args.frontend_port, data_root, main_service_root, args.allow_slot)
    gateway_url = f"http://127.0.0.1:{args.gateway_port}"
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    log_dir = main_service_root / "logs"
    steps: list[dict] = []

    def step(name: str, ok: bool, detail: str, elapsed_ms: int) -> None:
        steps.append({"name": name, "status": "PASS" if ok else "FAIL", "detail": detail, "elapsedMs": elapsed_ms})

    jar = args.jar.resolve()
    # 1. build stamp: same commit, or both commits resolve to the same tree in --worktree
    started = time.monotonic()
    recorded = stamp_source_sha(jar)
    recorded_tree = None
    expected_tree = None
    if recorded != args.expect_sha:
        recorded_tree = tree_of(worktree, recorded)
        expected_tree = tree_of(worktree, args.expect_sha)
    matched, tree_match = stamps_match(recorded, args.expect_sha, recorded_tree, expected_tree)
    if not matched:
        step("build-stamp", False,
             f"stamp sourceSha={recorded} expected={args.expect_sha} treeMatch={tree_match}", 0)
        return 1, {"status": "FAIL", "sourceSha": args.expect_sha, "stampSourceSha": recorded,
                   "treeMatch": tree_match, "reason": "build stamp mismatch", "steps": steps}
    step("build-stamp", True, f"sourceSha={recorded} treeMatch={tree_match}",
         int((time.monotonic() - started) * 1000))

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
    old_jars: list[str] = []
    for port in (args.gateway_port,):
        for pid in listeners(port):
            command = process_command_line(pid)
            if looks_like_main_service(command):
                old_pids.append(pid)
                old_jar = jar_from_command_line(command)
                if old_jar:
                    old_jars.append(old_jar)
            else:
                step("stop-old", False, f"port {port} is held by foreign PID {pid}: {command}", 0)
                return 1, {"status": "FAIL", "sourceSha": args.expect_sha, "reason": "foreign listener",
                           "foreignPid": pid, "steps": steps}
    for pid in sorted(set(old_pids)):
        stop_pid(pid)
    # With no usable service.json ``active``, keep the stopped process's JAR as the rollback
    # target so the first real refresh can still fall back.
    if not (previous and previous.get("jar")):
        fallback_jar = next((old_jar for old_jar in old_jars if Path(old_jar).is_file()), None)
        if fallback_jar:
            previous = {"jar": fallback_jar, "sha": None, "short": None, "sha256": None, "length": None}
    step("stop-old", True, f"stopped pids={sorted(set(old_pids))} previous={previous.get('jar') if previous else None}",
         int((time.monotonic() - started) * 1000))

    # 4. ports must actually bind again
    started = time.monotonic()
    free, waited = wait_for_free_ports([args.gateway_port])
    if not free:
        step("port-release", False, f"gateway port {args.gateway_port} not bindable within {PORT_RELEASE_TIMEOUT_SECONDS}s", int(waited * 1000))
        return 1, {"status": "FAIL", "sourceSha": args.expect_sha, "reason": "port not released", "steps": steps}
    step("port-release", True, f"bound after {waited:.1f}s", int(waited * 1000))

    # 5. start the copied Gateway
    started = time.monotonic()
    process, logs, product_env = start_gateway(installed, worktree, data_root, args.gateway_port,
                                               args.frontend_port, log_dir, stamp)
    ready, ready_ms = wait_for_gateway(gateway_url, args.ready_timeout_seconds, process)
    step("start-gateway", ready,
         f"pid={process.pid} ready={ready} after {ready_ms:.1f}s logs={logs} "
         f"productEnv={describe_product_environment(product_env) or 'none'}",
         int((time.monotonic() - started) * 1000))

    new_pid = process.pid if ready else None
    if not ready:
        stop_pid(process.pid)

    # 6. frontend, smoke and live-smoke. A ready Gateway whose later checks fail is still a
    # failure but is neither rolled back nor stopped.
    if args.gateway_only:
        frontend: object = "NOT_RUN"
    elif ready:
        frontend = _frontend_result(args, worktree, gateway_url, log_dir, stamp)
    else:
        frontend = {"status": "NOT_RUN", "reason": "gateway not ready"}
    smoke = status_impl(args.gateway_port, args.frontend_port, args.gateway_only) if ready else None
    if not args.live_smoke:
        live = {"status": "NOT_RUN"}
    elif ready:
        live = live_smoke(gateway_url)
    else:
        live = {"status": "FAIL"}

    failures: list[str] = []
    if not ready:
        failures.append("gateway")
    if not (isinstance(smoke, dict) and smoke.get("ok")):
        failures.append("smoke")
    if isinstance(frontend, dict) and frontend.get("status") == "FAIL":
        failures.append("frontend")
    if live.get("status") == "FAIL":
        failures.append("live-smoke")
    overall = "PASS" if not failures else "FAIL"

    if ready:
        active["frontendRoot"] = str(args.frontend_root.resolve()) if args.frontend_root else None
        active["frontendTree"] = frontend.get("tree") if isinstance(frontend, dict) else None
        write_service_record(main_service_root, active, previous)

    summary = {
        "status": overall,
        "failedChecks": failures,
        "sourceSha": args.expect_sha,
        "stampSourceSha": recorded,
        "expectSha": args.expect_sha,
        "treeMatch": tree_match,
        "jarSha256": jar_sha,
        "jarLength": active["length"],
        "installedJar": str(installed),
        "previous": previous,
        "oldGatewayPid": old_pids[0] if old_pids else None,
        "newGatewayPid": new_pid,
        "gatewayPid": process.pid,
        "steps": steps,
        "logs": logs,
        "frontend": frontend,
        "smoke": smoke,
        "liveSmoke": live,
        # Key names and the scope that supplied them; never the values.
        "productEnvironment": product_env,
    }

    # 7. rollback only when the new Gateway never became ready
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
    return (0 if overall == "PASS" else 1), summary


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _frontend_result(args, worktree: Path, gateway_url: str, log_dir: Path, stamp: str) -> dict:
    frontend_root = args.frontend_root.resolve() if args.frontend_root else None
    expected_tree = frontend_tree_of(worktree, args.expect_sha) if frontend_root else None
    actual_tree = frontend_tree_of(frontend_root, "HEAD") if frontend_root else None
    tree_match = bool(expected_tree and actual_tree and expected_tree == actual_tree)
    frontend_url = f"http://127.0.0.1:{args.frontend_port}"
    healthy = http_status(frontend_url) in range(200, 400)
    busy = bool(listeners(args.frontend_port))
    decision = frontend_decision(tree_match, healthy, busy, frontend_root is not None)

    if decision == "REUSED":
        return {"status": "REUSED", "tree": actual_tree,
                "frontendRoot": str(frontend_root) if frontend_root else None}
    if decision == "FAIL_TREE":
        return {"status": "FAIL", "reason": "frontend tree does not match the expected commit",
                "expectedTree": expected_tree, "actualTree": actual_tree}
    if decision == "FAIL_FOREIGN":
        foreign = [{"pid": pid, "commandLine": process_command_line(pid)}
                   for pid in listeners(args.frontend_port)]
        return {"status": "FAIL", "reason": "frontend port is held by another process",
                "frontendRoot": str(frontend_root), "listeners": foreign}
    if decision == "FAIL_NO_ROOT":
        return {"status": "FAIL", "reason": "frontend is not healthy; --frontend-root is required"}

    launcher = frontend_root / "scripts/frontend/start-haifa-frontend.ps1"
    if not launcher.is_file():
        return {"status": "FAIL", "reason": f"frontend launcher is missing: {launcher}",
                "frontendRoot": str(frontend_root)}
    source_repository = args.source_repository or (worktree / "deer-flow")
    command = frontend_start_args(launcher, frontend_root, source_repository, args.data_root,
                                  gateway_url, args.frontend_port)
    process, logs = start_frontend(command, worktree, log_dir, stamp)
    ready, elapsed = wait_for_frontend(frontend_url, args.ready_timeout_seconds, process)
    if not ready:
        stop_pid(process.pid)
        return {"status": "FAIL", "reason": "frontend did not become ready", "frontendRoot": str(frontend_root),
                "logs": logs, "elapsedMs": int(elapsed * 1000)}
    return {"status": "STARTED", "tree": actual_tree, "frontendRoot": str(frontend_root),
            "frontendPid": process.pid, "logs": logs, "elapsedMs": int(elapsed * 1000)}


def _start_previous(previous_jar: Path, worktree: Path, data_root: Path, args, log_dir: Path,
                    stamp: str) -> tuple[bool, float]:
    process, _, _ = start_gateway(previous_jar, worktree, data_root, args.gateway_port,
                                  args.frontend_port, log_dir, f"rollback-{stamp}")
    ready, elapsed = wait_for_gateway(f"http://127.0.0.1:{args.gateway_port}", args.ready_timeout_seconds, process)
    if not ready:
        stop_pid(process.pid)
    return ready, elapsed


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> int:
    quick_checks.force_utf8_stdout()
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
    refresh.add_argument("--source-repository", type=Path)
    refresh.add_argument("--live-smoke", action="store_true")
    refresh.add_argument("--gateway-only", action="store_true")
    refresh.add_argument("--ready-timeout-seconds", type=int, default=READY_TIMEOUT_SECONDS)

    start = sub.add_parser("start")
    start.add_argument("--jar", required=True, type=Path)
    start.add_argument("--data-root", required=True, type=Path)
    start.add_argument("--log-dir", required=True, type=Path)
    start.add_argument("--stamp", required=True)
    start.add_argument("--ready-timeout-seconds", type=int, default=READY_TIMEOUT_SECONDS)
    start.add_argument("--local-auth", action="store_true")
    start.add_argument("--auth", choices=("default", "unauthorized"), default="default")

    args = parser.parse_args(argv)
    try:
        if args.command == "status":
            result = status_impl(args.gateway_port, args.frontend_port, args.gateway_only)
            print(json.dumps(result, indent=2, ensure_ascii=False))
            return 0 if result["ok"] else 1
        if args.command == "start":
            code, started = start_impl(args)
            print(json.dumps(started, indent=2, ensure_ascii=False))
            return code
        code, summary = refresh_impl(args)
        print(json.dumps(summary, indent=2, ensure_ascii=False))
        return code
    except MainServiceError as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

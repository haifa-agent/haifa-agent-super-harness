"""Run the real frozen reference package UI in the controller-authorized slot 4.

No source/dependency changes, provider retries or alternate backend fallback. All
processes, data and original evidence belong to this invocation. Haifa executes
the identical spec through its ordinary production runner.
"""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
import urllib.parse
import uuid

FROZEN = "e545c28ac301e6f9feae247c513d8ea8f83df294"


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def bind(port):
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", port))
        probe.listen(1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True)
    args = parser.parse_args()
    repo, source = args.repo.resolve(), args.source.resolve()
    if subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip() != FROZEN:
        raise RuntimeError("Frozen reference SHA drifted")
    stamp = time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8]
    evidence = repo / "local-tmp/slot-4-w15-reference/evidence" / stamp
    data = repo / "local-tmp/slot-4-w15-reference/data" / stamp
    evidence.mkdir(parents=True)
    data.mkdir(parents=True)
    processes, handles = [], []
    health = {"sourceSha": subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip(),
              "referenceSha": FROZEN, "backend": "reference", "retry": False, "result": "FAIL", "firstFailure": None,
              "evidence": str(evidence), "ports": [11619, 16618, 11628], "phases": {}}
    start = time.monotonic()
    creation = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    env = os.environ.copy()
    env.update({"COREPACK_ENABLE_NETWORK": "0", "NO_PROXY": "127.0.0.1,localhost", "no_proxy": "127.0.0.1,localhost",
                "DEER_FLOW_INTERNAL_GATEWAY_BASE_URL": "http://127.0.0.1:11619", "DEER_FLOW_AUTH_DISABLED": "1",
                "DEER_FLOW_ENV": "development", "ENVIRONMENT": "development", "NEXT_PUBLIC_STATIC_WEBSITE_ONLY": "false",
                "DEER_FLOW_TRUSTED_ORIGINS": "http://127.0.0.1:16618", "DEER_FLOW_PROJECT_ROOT": str(source),
                "DEER_FLOW_CONFIG_PATH": str(repo / "tests/ui/dual-backend/reference-config.yaml"),
                "DEER_FLOW_EXTENSIONS_CONFIG_PATH": str(data / "extensions_config.json"), "DEER_FLOW_HOME": str(data),
                "W18_PROVIDER_BASE_URL": "http://127.0.0.1:11628/v1", "W18_REFERENCE_SKILLS_ROOT": str(data / "skills"),
                "W18_REFERENCE_DB_ROOT": str(data / "db"), "PYTHONDONTWRITEBYTECODE": "1",
                "PYTHONPATH": os.pathsep.join(str(source / "backend/packages" / name) for name in ["extension-api", "harness"]),
                "E2E_APP_URL": "http://127.0.0.1:16618", "E2E_FRONTEND_PORT": "16618", "W18_EVIDENCE_DIR": str(evidence),
                "W18_PLAYWRIGHT_SPEC": "w15-package-differential.spec.cjs", "W18_PLAYWRIGHT_TIMEOUT_MS": "120000",
                "W18_PLAYWRIGHT_TRACE": "on", "W15_DIFFERENTIAL_BACKEND": "reference"})
    def launch(name, command, cwd):
        out = (evidence / f"{name}.stdout.log").open("wb")
        err = (evidence / f"{name}.stderr.log").open("wb")
        handles.extend([out, err])
        child = subprocess.Popen(command, cwd=cwd, env=env, stdout=out, stderr=err, creationflags=creation)
        processes.append((name, child))
        return child
    def ready(url, child, seconds, expected=None):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if child.poll() is not None:
                raise RuntimeError(f"Process {child.pid} exited before {url} became ready")
            try:
                with urllib.request.urlopen(url, timeout=2) as response:
                    if response.status == 200 and (expected is None or json.load(response).get("status") == expected):
                        return
            except Exception:
                pass
            time.sleep(.25)
        raise TimeoutError(f"{url} did not become ready within {seconds}s")
    def run(name, command, cwd, timeout):
        child = launch(name, command, cwd)
        code = child.wait(timeout=timeout)
        if code:
            raise RuntimeError(f"{name} failed with exit {code}; see original logs")
    try:
        for port in health["ports"]:
            bind(port)
        for command in [["netsh", "interface", "ipv4", "show", "dynamicport", "tcp"],
                        ["netsh", "interface", "ipv4", "show", "excludedportrange", "protocol=tcp"]]:
            with (evidence / (command[4] + ".log")).open("wb") as output:
                subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, check=True, timeout=10, creationflags=creation)
        for directory in [data / "skills/public", data / "skills/custom", data / "db"]:
            directory.mkdir(parents=True)
        write(data / "extensions_config.json", {"mcpServers": {}, "skills": {}})
        python = source / "backend/.venv/Scripts/python.exe"
        if not python.is_file():
            raise RuntimeError("Frozen reference interpreter missing; dependencies are NOT_RUN")
        run("reference-import", [str(python), "-B", "-c", "import deerflow_extension_api, deerflow, app.gateway.app"], source / "backend", 30)
        frontend = repo / "frontend"
        if not (frontend / ".next").resolve().is_relative_to(repo):
            raise RuntimeError("Frontend build directory leaves the assigned worktree")
        if not (frontend / "node_modules/.bin/playwright.cmd").is_file():
            raise RuntimeError("Own worktree Playwright dependencies missing")
        health["phases"]["preparationSeconds"] = time.monotonic() - start
        built = time.monotonic()
        cache = subprocess.run([sys.executable, str(repo / "scripts/lib/frontend_build_cache.py"), "restore", "--repo", str(repo),
                                "--gateway", env["DEER_FLOW_INTERNAL_GATEWAY_BASE_URL"]],
                               env=env, capture_output=True, text=True, timeout=120)
        if cache.returncode == 3:
            run("frontend-build", [shutil.which("corepack.cmd"), "pnpm", "build"], frontend, 600)
            run("frontend-cache", [sys.executable, str(repo / "scripts/lib/frontend_build_cache.py"), "store", "--repo", str(repo),
                                   "--gateway", env["DEER_FLOW_INTERNAL_GATEWAY_BASE_URL"]], repo, 120)
            health["frontendBuild"] = "BUILT"
        elif cache.returncode == 0:
            health["frontendBuild"] = "RESTORED"
        else:
            raise RuntimeError("Frozen frontend cache restore failed")
        rewrites = json.loads((frontend / ".next/routes-manifest.json").read_text(encoding="utf-8"))["rewrites"]
        entries = rewrites if isinstance(rewrites, list) else [entry for group in rewrites.values() for entry in group]
        targets = {urllib.parse.urlsplit(entry["destination"]).netloc for entry in entries if entry.get("destination", "").startswith("http")}
        if targets != {"127.0.0.1:11619"}:
            raise RuntimeError("Frontend rewrite points outside the real reference backend")
        health["phases"]["buildSeconds"] = time.monotonic() - built
        services = time.monotonic()
        node = shutil.which("node.exe") or shutil.which("node")
        provider = launch("provider", [node, str(repo / "tests/ui/smoke/loopback-openai-provider.cjs"), "11628", str(evidence / "provider.ready.json")], repo)
        ready("http://127.0.0.1:11628/health", provider, 15, "ok")
        gateway = launch("gateway", [str(python), "-B", "-m", "uvicorn", "app.gateway.app:app", "--host", "127.0.0.1", "--port", "11619"], source / "backend")
        ready("http://127.0.0.1:11619/health", gateway, 60, "healthy")
        web = launch("frontend", [node, str(frontend / "node_modules/next/dist/bin/next"), "start", "--hostname", "127.0.0.1", "--port", "16618"], frontend)
        ready("http://127.0.0.1:16618/workspace/capabilities?tab=skills", web, 60)
        health["phases"]["serviceReadySeconds"] = time.monotonic() - services
        assertions = time.monotonic()
        run("playwright", [node, str(frontend / "node_modules/@playwright/test/cli.js"), "test", "--config", "tests/ui/smoke/playwright.config.cjs"], repo, 180)
        health["phases"]["assertionSeconds"] = time.monotonic() - assertions
        health["result"] = "PASS"
    except Exception as failure:
        health["firstFailure"] = {"type": type(failure).__name__, "message": str(failure)}
    finally:
        cleanup = time.monotonic()
        health["ownedPids"] = [{"name": name, "pid": process.pid} for name, process in processes]
        for name, process in reversed(processes):
            if process.poll() is None:
                if os.name == "nt":
                    subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL, timeout=10, creationflags=creation)
                else:
                    process.kill()
                process.wait(timeout=10)
        for handle in handles:
            handle.close()
        released, first_bind = [], {}
        deadline = cleanup + 15
        for port in health["ports"]:
            while time.monotonic() < deadline:
                try:
                    bind(port)
                    released.append(port)
                    break
                except OSError as failure:
                    first_bind.setdefault(str(port), str(failure))
                    time.sleep(.05)
        health["cleanup"] = {"releasedPorts": released, "firstBindError": first_bind, "seconds": time.monotonic() - cleanup,
                             "remainingOwnedPids": [process.pid for _, process in processes if process.poll() is None]}
        if set(released) != set(health["ports"]):
            health["result"] = "FAIL"
        health["totalSeconds"] = time.monotonic() - start
        write(evidence / "flow-health.json", health)
        print(json.dumps(health, ensure_ascii=False))
    return 0 if health["result"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())

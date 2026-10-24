"""Verify runtime.failure diagnostics from the actual executable Gateway JAR.

Uses one new data root and a loopback Provider that always rejects requests. No
external model, saved user credentials, or existing Gateway is used or changed.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid


class RejectProvider(BaseHTTPRequestHandler):
    calls = 0

    def do_POST(self):
        type(self).calls += 1
        self.rfile.read(int(self.headers.get("Content-Length", "0")))
        body = json.dumps({"error": {"type": "invalid_request_error", "code": "controlled_refusal",
                                     "message": "Packaged diagnostic probe refusal"}}).encode()
        self.send_response(400)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_args):
        pass


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    jar = args.jar.resolve(strict=True)
    if not jar.is_file() or not jar.name.endswith("-exec.jar"):
        parser.error("--jar must name an executable Gateway JAR")
    evidence = args.evidence.resolve() / ("packaged-failure-" + uuid.uuid4().hex)
    evidence.mkdir(parents=True)
    data = evidence / "data"
    data.mkdir()
    provider = ThreadingHTTPServer(("127.0.0.1", 0), RejectProvider)
    worker = threading.Thread(target=provider.serve_forever, daemon=True)
    worker.start()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    result = {"status": "FAIL", "jar": str(jar), "jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
              "data_root": str(data), "provider_port": provider.server_port, "automatic_case_retries": 0}
    process = None
    gateway_port = None
    started = time.monotonic()

    def request(base: str, method: str, path: str, payload=None):
        body = None if payload is None else json.dumps(payload).encode()
        req = urllib.request.Request(base + path, data=body, method=method,
                                     headers={"Content-Type": "application/json"})
        with opener.open(req, timeout=45) as response:
            return response.read().decode(), dict(response.headers)

    try:
        environment = os.environ.copy()
        environment.update({"SUPER_HARNESS_PROFILE": "local", "DEER_FLOW_AUTH_DISABLED": "1",
                            "SUPER_HARNESS_BIND_ADDRESS": "127.0.0.1", "SUPER_HARNESS_PORT": "0",
                            "SUPER_HARNESS_DATA_DIR": str(data), "SUPER_HARNESS_DATA_ROOT": str(data),
                            "HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF": "env://PACKAGED_PROBE_KEY",
                            "PACKAGED_PROBE_KEY": base64.b64encode(os.urandom(32)).decode(),
                            "NO_PROXY": "127.0.0.1,localhost", "no_proxy": "127.0.0.1,localhost"})
        environment.pop("HAIFA_SUPER_HARNESS_LOCAL_AUTH", None)
        java = shutil.which("java")
        if not java:
            raise RuntimeError("Java is unavailable")
        stdout_path, stderr_path = evidence / "gateway.stdout.log", evidence / "gateway.stderr.log"
        with stdout_path.open("wb") as stdout, stderr_path.open("wb") as stderr:
            process = subprocess.Popen([java, "-Xmx192m", "-XX:+UseSerialGC", "-jar", str(jar)],
                                       env=environment, stdout=stdout, stderr=stderr, stdin=subprocess.DEVNULL)
            result["pid"] = process.pid
            deadline = started + 30
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError("Packaged Gateway exited before readiness")
                banner = re.search(r"Super Harness local API listening on http://127\.0\.0\.1:(\d+)",
                                   stdout_path.read_text(encoding="utf-8", errors="replace"))
                if banner:
                    gateway_port = int(banner[1])
                    break
                time.sleep(0.05)
            if gateway_port is None:
                raise TimeoutError("Packaged Gateway readiness exceeded 30 seconds")
            base = f"http://127.0.0.1:{gateway_port}"
            request(base, "GET", "/health")
            request(base, "PUT", "/api/managed-models", {"expected_revision": None, "config": {
                "name": "packaged-log-probe", "provider": "openai-compatible", "model": "packaged-log-probe",
                "base_url": f"http://127.0.0.1:{provider.server_port}/v1", "enabled": True,
                "context_window": 32768, "max_tokens": 128, "api_key": uuid.uuid4().hex}})
            request(base, "POST", "/api/threads", {"thread_id": "packaged-log-probe"})
            stream, headers = request(base, "POST", "/api/threads/packaged-log-probe/runs/stream", {
                "assistant_id": "lead_agent", "input": {"messages": [{"id": "probe-human", "type": "human",
                "content": "Return a short response"}]}, "context": {"model_name": "packaged-log-probe",
                "thinking_enabled": False, "subagent_enabled": False}, "stream_mode": ["messages-tuple", "updates"],
                "on_disconnect": "continue"})
            (evidence / "run.sse").write_text(stream, encoding="utf-8")
            location = next(value for key, value in headers.items() if key.lower() == "content-location")
            run_text, _ = request(base, "GET", location)
            run = json.loads(run_text)
            diagnostic = run.get("diagnostics", {}).get("diagnostic_id")
            if run.get("status") != "error" or not diagnostic or not RejectProvider.calls:
                raise AssertionError("The controlled packaged Run did not fail with a diagnostic ID")
            log = stderr_path.read_text(encoding="utf-8", errors="replace")
            lines = [line for line in log.splitlines() if "event=runtime.failure" in line
                     and f"diagnosticId={diagnostic}" in line and f"runId={run['run_id']}" in line]
            if not lines or "failureTypes=" not in lines[0] or "NOP logger" in log:
                raise AssertionError("Packaged stderr lacks the correlated runtime.failure diagnostic")
            result.update({"status": "PASS", "run_id": run["run_id"], "diagnostic_id": diagnostic,
                           "error_code": run.get("diagnostics", {}).get("error_code"),
                           "gateway_port": gateway_port, "runtime_failure_line": lines[0]})
    except Exception as error:
        result["first_failure"] = type(error).__name__ + ": " + str(error)
    finally:
        if process and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
                result.update(status="FAIL", cleanup_failure="Gateway did not stop within 10 seconds")
        provider.shutdown()
        provider.server_close()
        worker.join(timeout=5)
        result.update(provider_calls=RejectProvider.calls, elapsed_seconds=round(time.monotonic() - started, 3),
                      process_exited=process is None or process.poll() is not None)
        with socket.socket() as connection:
            result["gateway_port_closed"] = gateway_port is None or connection.connect_ex(("127.0.0.1", gateway_port)) != 0
        if not result["process_exited"] or not result["gateway_port_closed"]:
            result["status"] = "FAIL"
        (evidence / "summary.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({"status": result["status"], "summary": str(evidence / "summary.json")}))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())

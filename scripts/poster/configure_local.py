"""Register the poster assistant through the Gateway model, Skill and Agent APIs."""

import argparse
import json
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

MODEL_NAME = "poster-text"
AGENT_NAME = "poster-assistant"


def send(base, path, method="GET", data=None):
    payload = json.dumps(data, ensure_ascii=False).encode("utf-8") if data is not None else None
    request = Request(base + path, data=payload, headers={"Content-Type": "application/json"}, method=method)
    try:
        with urlopen(request, timeout=30) as response:
            return json.load(response)
    except HTTPError as failure:
        raise RuntimeError(f"{method} {path}: HTTP {failure.code} {failure.read(800).decode('utf-8', 'replace')}") from failure


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True)
    models = parser.add_mutually_exclusive_group()
    models.add_argument("--model", default="deepseek-v4-pro", help="Text model to configure for a fresh local instance.")
    models.add_argument("--existing-model", help="Use this configured model and the already enabled baoyu skill.")
    args = parser.parse_args()
    base = args.base.rstrip("/")
    parts = urlsplit(base)
    if parts.scheme != "http" or parts.hostname != "127.0.0.1" or parts.path or parts.username:
        parser.error("the Gateway must be a local 127.0.0.1 HTTP origin")
    if args.existing_model:
        skill = send(base, "/api/skills/baoyu-image-gen")
    else:
        skill = send(base, "/api/skills/baoyu-image-gen", "PUT", {"enabled": True})
    if skill.get("enabled") is not True:
        raise RuntimeError("baoyu-image-gen is not enabled")
    name = args.existing_model or MODEL_NAME
    current = send(base, "/api/managed-models").get("models", [])
    model = next((entry for entry in current if entry["name"] == name), None)
    if not args.existing_model and (model is None or model.get("model") != args.model):
        model = send(base, "/api/managed-models", "PUT", {
            "expected_revision": model.get("revision") if model is not None else None,
            "config": {
                "name": name, "display_name": "海报助手文本模型", "provider": "openai-compatible",
                "model": args.model, "base_url": "https://api.deepseek.com",
                "credential_ref": "os://DEEPSEEK_API_KEY", "enabled": True, "supports_vision": False,
                "context_window": 1048576, "max_tokens": 4096,
            },
        })
    if not model or not model.get("enabled") or not model.get("has_credential"):
        raise RuntimeError(f"Configured model {name} is unavailable or has no credential")
    preset = {
        "name": AGENT_NAME, "display_name": "海报助手",
        "description": "做活动招募海报，选 A/B 稿，改日期或文案，下载 PNG。",
        "soul": Path(__file__).with_name("agent-soul.md").read_text(encoding="utf-8"),
        "model": name, "skills": ["baoyu-image-gen"], "thinking_enabled": False,
    }
    existing = send(base, "/api/agents").get("agents", [])
    assistant = next((agent for agent in existing if agent["name"] == AGENT_NAME), None)
    if assistant is None:
        assistant = send(base, "/api/agents", "POST", preset)
    if assistant.get("name") != AGENT_NAME or assistant.get("model") != name:
        raise RuntimeError("Unexpected poster assistant configuration. Inspect the existing Agent before updating it.")
    print(json.dumps({"agent": AGENT_NAME, "display_name": assistant.get("display_name"), "model": name},
                     ensure_ascii=False))


if __name__ == "__main__":
    main()

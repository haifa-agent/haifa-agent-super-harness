"""Run only frozen deterministic analyzers; never import or execute target files."""
from __future__ import annotations
import importlib.util
import json
import re
import sys
import types
from pathlib import Path

root = Path(__file__).resolve().parent
for namespace in ("deerflow", "deerflow.skills", "deerflow.skills.skillscan", "deerflow.skills.review"):
    module = types.ModuleType(namespace)
    module.__path__ = []
    sys.modules[namespace] = module
for name in ("package_files", "package_paths", "skillscan.models", "skillscan.orchestrator"):
    identifier = "deerflow.skills." + name
    spec = importlib.util.spec_from_file_location(identifier, root / (name.replace(".", "/") + ".py"))
    module = importlib.util.module_from_spec(spec)
    sys.modules[identifier] = module
    spec.loader.exec_module(module)
from deerflow.skills.skillscan.orchestrator import scan_skill_dir
from deerflow.skills.package_files import is_code_file

result = scan_skill_dir(Path(sys.argv[1]))
result["code_files"] = [path.relative_to(Path(sys.argv[1])).as_posix()
                        for path in sorted(Path(sys.argv[1]).rglob("*"))
                        if path.is_file() and not path.is_symlink()
                        and is_code_file(path.relative_to(Path(sys.argv[1])), path.read_bytes()[:4096])]
if len(sys.argv) > 2:
    options = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
    for name in ("frontmatter", "review_declarations", "review.models", "review.digest", "review.eval_schema",
                 "review.resource_graph", "review.readers", "review.analyzer", "review.renderer"):
        identifier = "deerflow.skills.parser" if name == "review_declarations" else "deerflow.skills." + name
        spec = importlib.util.spec_from_file_location(identifier, root / (name.replace(".", "/") + ".py"))
        module = importlib.util.module_from_spec(spec)
        sys.modules[identifier] = module
        spec.loader.exec_module(module)
    from deerflow.skills.review.readers import LocalDirectoryReader, build_inline_snapshot
    from deerflow.skills.review.analyzer import analyze_skill_package
    from deerflow.skills.review.renderer import build_static_report, render_report_markdown
    snapshot = (build_inline_snapshot((Path(sys.argv[1]) / "SKILL.md").read_text(encoding="utf-8"), name_hint=options["target"])
                if options["target"].startswith("inline://") else LocalDirectoryReader(Path(sys.argv[1])).read())
    snapshot["subject"]["display_ref"] = options["target"]
    if options["target"].startswith("skill://"):
        category, _, relative = options["target"][len("skill://"):].partition("/")
        snapshot["subject"].update(source="installed", category=category, name_hint=relative.rsplit("/", 1)[-1])
    facts = analyze_skill_package(snapshot, profile=options["profile"])
    report = build_static_report(facts, scope=options["scope"])
    artifacts = []
    remaining = 80_000
    if options["include_content"] == "semantic-review":
        for entry in snapshot["files"]:
            path = entry["path"]
            if entry["kind"] != "text" or not (path == "SKILL.md" or path.startswith(("references/", "templates/", "evals/"))
                    and path.endswith((".md", ".json", ".txt", ".yaml", ".yml"))):
                continue
            content = entry.get("content", "")
            artifacts.append({"path": path, "content": content[:remaining], "truncated": len(content) > remaining,
                              "untrusted_review_data": True})
            remaining -= min(len(content), remaining)
            if not remaining:
                break
    result = {"untrusted_review_data": True, "facts": facts, "artifacts": artifacts, "static_report": report,
              "markdown": {locale: render_report_markdown(report, facts, locale=locale) for locale in ("en", "zh")},
              "evidence_level": "static", "target_executed": False, "safety_proven": False}
# Findings' evidence can contain matching secrets; only constant explanations and locations cross the boundary.
def scrub(value):
    if isinstance(value, dict):
        return {key: scrub(item) for key, item in value.items() if key != "evidence"}
    if isinstance(value, list):
        return [scrub(item) for item in value]
    if isinstance(value, str):
        value = re.sub(r"(?im)(\b(?:[A-Z0-9_]*(?:TOKEN|SECRET|PASSWORD|API_KEY|PRIVATE_KEY)[A-Z0-9_]*)\s*[:=]\s*)[^\r\n]+", r"\1[REDACTED]", value)
        value = re.sub(r"\b(?:sk-|ghp_|github_pat_)[A-Za-z0-9_-]{12,}\b", "[REDACTED]", value)
        value = re.sub(r"\bxox[baprs]-[A-Za-z0-9-]{12,}\b|\bAKIA[A-Z0-9]{16}\b", "[REDACTED]", value)
        value = re.sub(r"(?is)-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----.*?-----END (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", "[REDACTED PRIVATE KEY]", value)
        value = re.sub(r"(?i)(\bBearer\s+)[A-Za-z0-9._~+/=-]+", r"\1[REDACTED]", value)
        return re.sub(r"<(\/?)(?:system|assistant|developer|user|tool)(?=[\s>])", r"[untrusted-tag \1", value, flags=re.I)
    return value
result = scrub(result)
result["evidence_level"] = "static"
result["target_executed"] = False
result["safety_proven"] = False
print(json.dumps(result, ensure_ascii=True))

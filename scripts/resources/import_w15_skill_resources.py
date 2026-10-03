"""Import frozen non-executing SkillScan utilities and creator/reviewer resources."""
from __future__ import annotations
import argparse
import ast
import hashlib
import json
import subprocess
from pathlib import Path

PIN = "e545c28ac301e6f9feae247c513d8ea8f83df294"
SCANNER = ["package_files.py", "package_paths.py", "skillscan/models.py", "skillscan/orchestrator.py",
           "frontmatter.py", "review/models.py", "review/digest.py", "review/eval_schema.py",
           "review/resource_graph.py", "review/readers.py", "review/analyzer.py", "review/renderer.py"]

def git(source: Path, *args: str) -> bytes:
    return subprocess.run(["git", "-C", str(source), *args], check=True, capture_output=True).stdout

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--repository", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    if git(args.source, "rev-parse", "HEAD").decode().strip() != PIN:
        raise SystemExit("Reference checkout is not the frozen DeerFlow SHA")
    resource_root = args.repository / "super-harness-server/src/main/resources/upstream/deerflow/w15"
    records = []
    descriptions_path = args.repository / "super-harness-server/src/main/resources/upstream/deerflow/tool-descriptions/manifest.json"
    descriptions = json.loads(descriptions_path.read_text(encoding="utf-8"))
    for name in SCANNER:
        source = "backend/packages/harness/deerflow/skills/" + name
        data = git(args.source, "show", f"{PIN}:{source}")
        target = resource_root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        records.append({"path": name, "source": source, "sha256": hashlib.sha256(data).hexdigest()})
    for tool, source, function in [
        ("skill_manage", "backend/packages/harness/deerflow/tools/skill_manage_tool.py", "skill_manage_tool"),
        ("review_skill_package", "backend/packages/harness/deerflow/tools/builtins/review_skill_package_tool.py", "review_skill_package"),
    ]:
        raw = git(args.source, "show", f"{PIN}:{source}")
        tree = ast.parse(raw)
        node = next(node for node in tree.body if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == function)
        text = ast.get_docstring(node, clean=True).encode("utf-8")
        target = args.repository / "super-harness-server/src/main/resources/upstream/deerflow/tool-descriptions" / f"{tool}.txt"
        target.write_bytes(text)
        descriptions["descriptions"] = [row for row in descriptions["descriptions"] if row["tool"] != tool] + [{
            "tool": tool, "source_file": source.removeprefix("backend/packages/harness/"),
            "source_function": function, "source_lineno": node.lineno,
            "description_sha256": hashlib.sha256(text).hexdigest()}]
        records.append({"path": f"../tool-descriptions/{tool}.txt", "source": source,
                        "source_sha256": hashlib.sha256(raw).hexdigest(), "sha256": hashlib.sha256(text).hexdigest()})
    descriptions_path.write_text(json.dumps(descriptions, indent=2) + "\n", encoding="utf-8")
    # Only the frozen declarations used by diagnostics: no runtime Skill loader/storage/backend.
    parser_source = "backend/packages/harness/deerflow/skills/parser.py"
    raw = git(args.source, "show", f"{PIN}:{parser_source}")
    tree = ast.parse(raw)
    helpers = {"_normalize_unscoped_allowed_tool", "_split_portable_allowed_tools", "parse_allowed_tools", "parse_required_secrets"}
    chunks = ["from __future__ import annotations\nimport re\nimport logging\nlogger = logging.getLogger(__name__)\nfrom pathlib import Path\nfrom dataclasses import dataclass\n"
              "@dataclass(frozen=True)\nclass SecretRequirement:\n    name: str\n    optional: bool = False\n"]
    source_text = raw.decode("utf-8")
    for node in tree.body:
        if isinstance(node, ast.FunctionDef) and node.name in helpers or isinstance(node, ast.Assign) and any(
                isinstance(target, ast.Name) and target.id in {"_ENV_VAR_NAME_RE", "_PORTABLE_TOOL_ALIASES"} for target in node.targets):
            chunks.append(ast.get_source_segment(source_text, node))
    data = ("\n\n".join(chunks) + "\n").encode("utf-8")
    (resource_root / "review_declarations.py").write_bytes(data)
    records.append({"path": "review_declarations.py", "source": parser_source, "source_sha256": hashlib.sha256(raw).hexdigest(),
                    "sha256": hashlib.sha256(data).hexdigest(), "extraction": "diagnostic declaration helpers only"})
    manifest_path = args.repository / "third-party/deerflow/skills/manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    imported_packages = []
    for name in ("skill-creator", "skill-reviewer"):
        prefix = f"skills/public/{name}/"
        paths = git(args.source, "ls-tree", "-r", "--name-only", PIN, "--", prefix).decode().splitlines()
        contents = {path[len(prefix):]: git(args.source, "show", f"{PIN}:{path}") for path in paths}
        revision = hashlib.sha256(b"".join(key.encode() + b"\0" + contents[key] for key in sorted(contents))).hexdigest()
        root = Path("third-party/deerflow/skills") / PIN / revision / "public"
        for relative, data in contents.items():
            target = args.repository / root / name / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        license_path = "third-party/deerflow/LICENSES/deer-flow-MIT.txt"
        license_name = "MIT"
        if name == "skill-creator":
            license_path = "third-party/deerflow/LICENSES/skill-creator-Apache-2.0.txt"
            (args.repository / license_path).write_bytes(contents["LICENSE.txt"])
            license_name = "Apache-2.0"
        entry = {"name": name, "category": "public", "license": license_name, "license_file": license_path,
                 "license_sha256": hashlib.sha256((args.repository / license_path).read_bytes()).hexdigest(),
                 "source": "bytedance/deer-flow", "source_sha": PIN, "version": "sha256:" + revision,
                 "active": True, "enabled_by_default": False, "root": root.as_posix(),
                 "files": {name + "/" + path: hashlib.sha256(data).hexdigest() for path, data in sorted(contents.items())},
                 "parser": "compatible", "max_instruction_lines": 2000, "max_estimated_tokens": 20000}
        manifest["packages"] = [pkg for pkg in manifest["packages"] if pkg["name"] != name] + [entry]
        imported_packages.append({"name": name, "files": len(contents), "revision": revision})
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (resource_root / "manifest.json").write_text(json.dumps({"source_sha": PIN, "files": records}, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"sourceSha": PIN, "importedScannerFiles": len(SCANNER), "toolDescriptions": 2, "packages": imported_packages}))

if __name__ == "__main__":
    main()

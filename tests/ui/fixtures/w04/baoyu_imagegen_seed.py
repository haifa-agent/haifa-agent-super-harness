"""Materialize the bounded baoyu-image-gen admission seed (W04 imagegen UI profile).

The seed is the only Skill payload this lane can admit: the frozen baoyu-image-gen
package is not available inside this worktree, and upstream entry 038-32 owns the real
skill-script execution contract. Materialization therefore produces a bounded admission
fixture whose bytes, package digest and EXTEND seeding can be verified without running
any Skill code:

    python -B tests/ui/fixtures/w04/baoyu_imagegen_seed.py \
        --package <dataRoot>/skills-fixture/baoyu-image-gen \
        --extend <dataRoot>/.baoyu-skills \
        --manifest <dataRoot>/skill-seed-manifest.json

`--verify` re-reads the materialized files and re-checks every recorded digest; exit
code 0 means the on-disk seed still matches the manifest.

Package digest: the locked JDK aggregate (SkillPackageFiles.digest) over the sorted
(relative-path, content) map - big-endian 8-byte length prefix per key and per value -
so the Gateway's external-directory version and this manifest describe the same bytes.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

NAME = "baoyu-image-gen"
PACKAGE_FILES = {
    "SKILL.md": (
        "---\n"
        "name: baoyu-image-gen\n"
        "description: Generate or edit images by running the package script with bun under the host shell\n"
        "license: MIT\n"
        "---\n"
        "# baoyu-image-gen (W04 bounded admission seed)\n"
        "\n"
        "This is the isolated admission seed the W04 imagegen UI profile plants in its own\n"
        "data root while upstream 038-32 is unmerged. It proves Skill admission, package\n"
        "digest integrity and EXTEND seeding only; every executed assertion (A1-A8) stays\n"
        "NOT_RUN until the frozen baoyu-image-gen bytes replace this package.\n"
        "\n"
        "- Runtime contract: `${BUN_X} {baseDir}/scripts/main.ts` with `anyBins: bun / npx`.\n"
        "- Output contract: generated artifacts must land in this Run workspace's `outputs/`.\n"
        "- Extension config: read `EXTEND.md` beside this file; it declares\n"
        "  `default_provider: codex-cli`.\n"
        "- Process isolation: the seeded directory lives inside the profile data root; the\n"
        "  user home and any external directory stay untouched.\n"
        "\n"
        "Do not execute package code until the skill script Tool is registered and the\n"
        "human approval card has been answered.\n"
    ),
    "EXTEND.md": (
        "---\n"
        "default_provider: codex-cli\n"
        "---\n"
        "# baoyu-image-gen extension config (isolated seed)\n"
        "\n"
        "`default_provider: codex-cli` is the only extension fact this admission seed\n"
        "declares. The frozen baoyu-image-gen package owns the authoritative schema;\n"
        "this copy is materialized beside `SKILL.md` in this profile's skills-fixture\n"
        "directory and mirrored at `<dataRoot>/.baoyu-skills/baoyu-image-gen/EXTEND.md`\n"
        "so no user home directory is ever written.\n"
    ),
    "scripts/main.ts": (
        "// W04 bounded admission seed entry point. It only answers --help and --provider\n"
        "// probes; the frozen baoyu-image-gen package replaces these bytes before the\n"
        "// deferred A1-A8 execution lane runs.\n"
        "const args = process.argv.slice(2);\n"
        "if (args.length === 0 || args.includes(\"--help\")) {\n"
        "  console.log(\"W04_SEED_MAIN_OK baoyu-image-gen; usage: main.ts --provider <name> (default from EXTEND.md)\");\n"
        "  process.exit(0);\n"
        "}\n"
        "if (args[0] === \"--provider\") {\n"
        "  console.log(`W04_SEED_PROVIDER=${args[1] ?? \"codex-cli\"}`);\n"
        "  process.exit(0);\n"
        "}\n"
        "console.error(\"W04_SEED_MAIN_UNKNOWN_ARGUMENT\");\n"
        "process.exit(2);\n"
    ),
}
COMPANION_FILE = "EXTEND.md"
MANIFEST_SCHEMA = 1


def package_digest(files: dict[str, bytes]) -> str:
    digest = hashlib.sha256()
    for relative in sorted(files):
        key = relative.encode("utf-8")
        content = files[relative]
        digest.update(len(key).to_bytes(8, "big"))
        digest.update(key)
        digest.update(len(content).to_bytes(8, "big"))
        digest.update(content)
    return digest.hexdigest()


def sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def read_package(directory: Path) -> dict[str, bytes]:
    files: dict[str, bytes] = {}
    for path in sorted(directory.rglob("*")):
        if path.is_dir():
            continue
        if not path.is_file():
            raise SystemExit(f"Unsafe seed entry outside regular files: {path}")
        files[path.relative_to(directory).as_posix()] = path.read_bytes()
    if not files:
        raise SystemExit(f"Seed package is empty: {directory}")
    return files


def write_seed(package: Path, extend: Path, manifest: Path) -> dict:
    files = {name: text.encode("utf-8") for name, text in PACKAGE_FILES.items()}
    (package / "scripts").mkdir(parents=True, exist_ok=True)
    for relative, content in sorted(files.items()):
        target = package.joinpath(*relative.split("/"))
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    companion = (extend / NAME / COMPANION_FILE)
    companion.parent.mkdir(parents=True, exist_ok=True)
    companion.write_bytes(files[COMPANION_FILE])
    document = {
        "schemaVersion": MANIFEST_SCHEMA,
        "skill": NAME,
        "files": {relative: sha256(content) for relative, content in sorted(files.items())},
        "packageSha256": package_digest(files),
        "companion": {
            "path": ".baoyu-skills/baoyu-image-gen/" + COMPANION_FILE,
            "sha256": sha256(files[COMPANION_FILE]),
        },
    }
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    return document


def verify_seed(package: Path, extend: Path, manifest: Path) -> dict:
    document = json.loads(manifest.read_text(encoding="utf-8").replace("\r\n", "\n"))
    if document.get("skill") != NAME:
        raise SystemExit(f"Manifest describes another Skill: {document.get('skill')}")
    files = read_package(package)
    issues = []
    for relative, expected in sorted(document.get("files", {}).items()):
        content = files.get(relative)
        if content is None:
            issues.append(f"{relative}: missing")
        elif sha256(content) != expected:
            issues.append(f"{relative}: digest mismatch")
    extra = sorted(set(files) - set(document.get("files", {})))
    # Every extra file would also change the JDK aggregate the Gateway records as the
    # external-directory version, so the admitted package admits no drift probes at all.
    for relative in extra:
        issues.append(f"{relative}: unexpected seed file")
    if package_digest(files) != document.get("packageSha256"):
        issues.append("packageSha256 drift")
    companion = document.get("companion", {})
    onDisk = extend / NAME / COMPANION_FILE
    if not onDisk.is_file() or sha256(onDisk.read_bytes()) != companion.get("sha256"):
        issues.append("companion EXTEND.md drift")
    if sha256(files[COMPANION_FILE]) != companion.get("sha256"):
        issues.append("companion and package EXTEND.md differ")
    if issues:
        raise SystemExit("Seed verification failed: " + "; ".join(issues))
    return {"status": "PASS", "skill": NAME, "packageSha256": document["packageSha256"],
            "files": sorted(files)}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--package", type=Path, required=True, help="seed package directory")
    parser.add_argument("--extend", type=Path, required=True, help="data root .baoyu-skills directory")
    parser.add_argument("--manifest", type=Path, required=True, help="recorded digest manifest path")
    parser.add_argument("--verify", action="store_true", help="re-check the materialized seed instead of writing")
    args = parser.parse_args()
    if args.verify:
        result = verify_seed(args.package, args.extend, args.manifest)
    else:
        document = write_seed(args.package, args.extend, args.manifest)
        result = {"status": "PASS", "skill": NAME, "packageSha256": document["packageSha256"],
                  "files": sorted(document["files"]), "digestAlgorithm": "jdk-locked SkillPackageFiles.digest"}
    json.dump(result, sys.stdout)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()

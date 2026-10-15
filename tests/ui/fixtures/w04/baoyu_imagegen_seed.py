"""Materialize the real upstream baoyu-image-gen package (W04 skill-imagegen profile).

The W04 imagegen profile plants the real upstream package bytes in its own data
root: every file of the controller-staged upstream copy is copied one by one,
preserving the relative structure, and every content digest is checked against
the upstream manifest before and after materialization:

    python -B tests/ui/fixtures/w04/baoyu_imagegen_seed.py \
        --source local-tmp/w04-real-skill/baoyu-image-gen \
        --source-manifest local-tmp/w04-real-skill/MANIFEST.sha256 \
        --package <dataRoot>/skills-fixture/baoyu-image-gen \
        --extend <dataRoot>/.baoyu-skills \
        --manifest <dataRoot>/skill-seed-manifest.json

``--verify`` re-reads the materialized files and re-checks every recorded digest;
exit code 0 means the on-disk package still matches the manifest.

The upstream copy (JimLiu/baoyu-skills@1567581 ``skills/baoyu-image-gen``, 49
files) declares bun as its runtime and ``scripts/main.ts`` as its entry. The
package carries no EXTEND.md of its own, so this fixture author writes the
Harness companion (``<dataRoot>/.baoyu-skills/baoyu-image-gen/EXTEND.md``)
declaring ``default_provider: codex-cli`` beside the admitted package; no
upstream byte is modified and no external directory is ever written.

Package digest: the locked JDK aggregate (SkillPackageFiles.digest) over the
sorted (relative-path, content) map - big-endian 8-byte length prefix per key
and per value - so the Gateway's external-directory version and this manifest
describe the same bytes.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

NAME = "baoyu-image-gen"

COMPANION_FILE = "EXTEND.md"
COMPANION_MD = (
    "---\n"
    "default_provider: codex-cli\n"
    "---\n"
    "# baoyu-image-gen extension config (W04 acceptance companion)\n"
    "\n"
    "`default_provider: codex-cli` is the only extension fact this Harness-authored\n"
    "companion declares. The upstream baoyu-image-gen package\n"
    "(JimLiu/baoyu-skills@1567581) carries no EXTEND.md of its own, so this\n"
    "data-root companion provides the extension fact beside the admitted package\n"
    "without modifying any upstream byte and without ever touching an external\n"
    "directory.\n"
)
MANIFEST_SCHEMA = 2

CLI_USAGE_MARKERS = ("Usage:", "codex-cli", "--image")


def sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


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


def read_upstream_manifest(path: Path) -> dict[str, str]:
    entries: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").replace("\r\n", "\n").splitlines():
        line = line.strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) != 2:
            raise SystemExit(f"Unsafe upstream manifest line: {line}")
        digest = parts[1].lower()
        if len(digest) != 64 or not all(c in "0123456789abcdef" for c in digest):
            raise SystemExit(f"Unsafe upstream manifest line: {line}")
        entries[parts[0]] = digest
    if not entries:
        raise SystemExit(f"Upstream manifest is empty: {path}")
    return entries


def read_package(directory: Path) -> dict[str, bytes]:
    files: dict[str, bytes] = {}
    for path in sorted(directory.rglob("*")):
        if path.is_dir():
            continue
        if not path.is_file():
            raise SystemExit(f"Unsafe package entry outside regular files: {path}")
        files[path.relative_to(directory).as_posix()] = path.read_bytes()
    if not files:
        raise SystemExit(f"Package is empty: {directory}")
    return files


def write_seed(source: Path, source_manifest: Path, package: Path, extend: Path, manifest: Path) -> dict:
    expected = read_upstream_manifest(source_manifest)
    prefix = NAME + "/"
    sheets: dict[str, str] = {}
    for entry, digest in expected.items():
        if not entry.startswith(prefix):
            raise SystemExit(f"Upstream manifest entry outside {NAME}: {entry}")
        sheets[entry[len(prefix):]] = digest
    files = read_package(source)
    missing = sorted(set(sheets) - set(files))
    extra = sorted(set(files) - set(sheets))
    if missing or extra:
        raise SystemExit(
            "Upstream copy does not match its manifest; "
            f"missing={missing[:8]} unexpected={extra[:8]}"
        )
    for relative, content in sorted(files.items()):
        if sha256(content) != sheets[relative]:
            raise SystemExit(f"Upstream digest mismatch: {relative}")
    for relative, content in sorted(files.items()):
        target = package.joinpath(*relative.split("/"))
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    companion = extend / NAME / COMPANION_FILE
    companion.parent.mkdir(parents=True, exist_ok=True)
    companion_bytes = COMPANION_MD.encode("utf-8")
    companion.write_bytes(companion_bytes)
    if read_package(package) != files:
        raise SystemExit("Materialized package drifted from the verified upstream bytes")
    document = {
        "schemaVersion": MANIFEST_SCHEMA,
        "skill": NAME,
        "source": source.as_posix(),
        "sourceManifestSha256": sha256(source_manifest.read_bytes()),
        "files": {relative: sha256(content) for relative, content in sorted(files.items())},
        "packageSha256": package_digest(files),
        "entry": "scripts/main.ts",
        "runtime": "bun",
        "companion": {
            "path": ".baoyu-skills/baoyu-image-gen/" + COMPANION_FILE,
            "sha256": sha256(companion_bytes),
        },
    }
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    return document


def verify_seed(source_manifest: Path, package: Path, extend: Path, manifest: Path) -> dict:
    document = json.loads(manifest.read_text(encoding="utf-8").replace("\r\n", "\n"))
    if document.get("skill") != NAME:
        raise SystemExit(f"Manifest describes another Skill: {document.get('skill')}")
    if source_manifest is not None and sha256(source_manifest.read_bytes()) != document.get("sourceManifestSha256"):
        raise SystemExit("Upstream manifest SHA-256 drift")
    files = read_package(package)
    issues = []
    for relative, expected in sorted(document.get("files", {}).items()):
        content = files.get(relative)
        if content is None:
            issues.append(f"{relative}: missing")
        elif sha256(content) != expected:
            issues.append(f"{relative}: digest mismatch")
    for relative in sorted(set(files) - set(document.get("files", {}))):
        issues.append(f"{relative}: unexpected package file")
    if package_digest(files) != document.get("packageSha256"):
        issues.append("packageSha256 drift")
    document_manifest = read_upstream_manifest(source_manifest) if source_manifest is not None else {}
    for relative, expected in sorted(document.get("files", {}).items()):
        sheet = document_manifest.get(NAME + "/" + relative)
        if sheet is not None and sheet != expected:
            issues.append(f"{relative}: upstream sheet mismatch")
    companion = document.get("companion", {})
    on_disk = extend / NAME / COMPANION_FILE
    if not on_disk.is_file() or sha256(on_disk.read_bytes()) != companion.get("sha256"):
        issues.append("companion EXTEND.md drift")
    if issues:
        raise SystemExit("Package verification failed: " + "; ".join(issues))
    return {"status": "PASS", "skill": NAME, "packageSha256": document["packageSha256"],
            "files": sorted(files)}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--package", type=Path, required=True, help="materialized package directory")
    parser.add_argument("--extend", type=Path, required=True, help="data root .baoyu-skills directory")
    parser.add_argument("--manifest", type=Path, required=True, help="recorded digest manifest path")
    parser.add_argument("--source", type=Path, required=True, help="controller-staged upstream package directory")
    parser.add_argument("--source-manifest", type=Path, default=None,
                        help="upstream per-file SHA-256 manifest (written on materialization)")
    parser.add_argument("--verify", action="store_true", help="re-check the materialized package instead of writing")
    args = parser.parse_args()
    if args.source_manifest is None and not args.verify:
        raise SystemExit("--source-manifest is required when materializing")
    if args.verify:
        result = verify_seed(args.source_manifest, args.package, args.extend, args.manifest)
    else:
        write_seed(args.source, args.source_manifest, args.package, args.extend, args.manifest)
        result = {"status": "PASS", "skill": NAME,
                  "packageSha256": json.loads(args.manifest.read_text(encoding="utf-8"))["packageSha256"],
                  "files": sorted(read_package(args.package)), "digestAlgorithm": "jdk-locked SkillPackageFiles.digest"}
    json.dump(result, sys.stdout)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()

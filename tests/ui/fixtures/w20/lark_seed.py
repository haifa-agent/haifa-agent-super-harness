"""Validate and copy a controller-provided PRODUCT-installed Lark skill seed.

This fixture never installs packages or creates a product manifest. It copies only
an existing shared skills tree, checked against an official source archive. No
user directories, credentials, enable config or Runtime state are accepted.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

NAMES = tuple("lark-approval lark-apps lark-attendance lark-base lark-calendar lark-contact lark-doc lark-drive lark-event lark-im lark-mail lark-markdown lark-minutes lark-note lark-okr lark-openapi-explorer lark-shared lark-sheets lark-skill-maker lark-slides lark-task lark-vc lark-vc-agent lark-whiteboard lark-wiki lark-workflow-meeting-summary lark-workflow-standup-report".split())
MANIFEST = ".haifa-lark-manifest.json"
REFERENCE = "lark-doc/references/lark-doc-fetch.md"
MAX_ARCHIVE = 128 * 1024 * 1024
MAX_TOTAL = 256 * 1024 * 1024
MAX_FILE = 16 * 1024 * 1024
MAX_FILES = 10000


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def safe_path(path: Path) -> Path:
    # Windows junctions/reparse points must be rejected as well as POSIX links.
    for part in (path, *path.parents):
        if part.exists() or part.is_symlink():
            info = part.lstat()
            if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
                raise ValueError("Seed paths must not contain links/reparse points")
    return path.resolve()


def bounded_read(path: Path, maximum: int = MAX_FILE) -> bytes:
    safe_path(path)
    if not path.is_file() or path.stat().st_size > maximum:
        raise ValueError("Seed file is missing or exceeds its byte limit")
    with path.open("rb") as stream:
        data = stream.read(maximum + 1)
    if len(data) > maximum:
        raise ValueError("Seed file grew beyond its byte limit")
    return data


def archive_files(path: Path, version: str) -> tuple[dict[str, bytes], dict[str, bytes]]:
    safe_path(path)
    if not path.is_file() or path.stat().st_size > MAX_ARCHIVE:
        raise ValueError("Official archive missing or oversized")
    skills, licenses = {}, {}
    total = 0
    with zipfile.ZipFile(path) as archive:
        if len(archive.infolist()) > MAX_FILES:
            raise ValueError("Official archive has too many entries")
        for item in archive.infolist():
            raw = item.filename.replace("\\", "/")
            parts = PurePosixPath(raw).parts
            if raw.startswith("/") or re.match(r"^[A-Za-z]:", raw) or ".." in parts:
                raise ValueError("Unsafe archive member")
            if not parts or parts[0] != f"cli-{version[1:]}":
                raise ValueError("Official archive root does not match the product version")
            if stat.S_ISLNK(item.external_attr >> 16):
                raise ValueError("Archive links are not permitted")
            if item.is_dir():
                continue
            if item.file_size > MAX_FILE:
                raise ValueError("Archive member is oversized")
            total += item.file_size
            if total > MAX_TOTAL:
                raise ValueError("Archive expansion exceeds byte limit")
            key = None
            if "skills" in parts:
                index = parts.index("skills")
                if len(parts) > index + 2 and parts[index + 1] in NAMES:
                    key = "/".join(parts[index + 1:])
            elif len(parts) == 2 and parts[-1].upper().startswith("LICENSE"):
                key = parts[-1]
            if key is None:
                continue
            target = skills if "skills" in parts else licenses
            if key in target:
                raise ValueError("Duplicate archive member")
            with archive.open(item) as stream:
                data = stream.read(MAX_FILE + 1)
            if len(data) > MAX_FILE or len(data) != item.file_size:
                raise ValueError("Archive member exceeds its declared byte limit")
            target[key] = data
    if {name for name in NAMES if f"{name}/SKILL.md" in skills} != set(NAMES):
        raise ValueError("Official archive must contain exact frozen 27 skills")
    if not licenses:
        raise ValueError("Official archive has no root license")
    return skills, licenses


def validate_seed(source: Path, archive: Path, guidance: Path) -> tuple[dict[str, bytes], dict]:
    source = safe_path(source)
    manifest_bytes = bounded_read(source / MANIFEST)
    manifest = json.loads(manifest_bytes)
    version = manifest.get("version", "")
    if not re.fullmatch(r"v\d+\.\d+\.\d+", version):
        raise ValueError("Product manifest version must be an actual release tag")
    if manifest.get("provider") != "lark-cli" or sorted(manifest.get("skills", [])) != sorted(NAMES):
        raise ValueError("Product manifest must describe exact frozen 27 skills")
    if manifest.get("source") != f"https://codeload.github.com/larksuite/cli/zip/refs/tags/{version}":
        raise ValueError("Product manifest must name the official version-bound archive")
    if not manifest.get("license") or not manifest.get("platforms") or not manifest.get("installed_at"):
        raise ValueError("Product manifest lacks license/platform/provenance")
    originals, licenses = archive_files(archive, version)
    # The product installer redistributes the exact root license with each
    # package. Validate those additions; never generate/repair them here.
    if "LICENSE" not in licenses:
        raise ValueError("Official archive root LICENSE is required")
    for name in NAMES:
        originals[f"{name}/LICENSE.txt"] = licenses["LICENSE"]
    archive_sha256 = digest(bounded_read(archive, MAX_ARCHIVE))
    if manifest.get("archive_sha256") != archive_sha256 or manifest.get("license_sha256") != digest(licenses["LICENSE"]):
        raise ValueError("Product archive/license provenance digest does not match official bytes")
    files = {}
    total = 0
    for directory, dirs, names in os.walk(source, followlinks=False):
        safe_path(Path(directory))
        for child in dirs:
            safe_path(Path(directory) / child)
            if Path(directory) == source and child not in NAMES:
                raise ValueError("Seed contains directories outside the shared skill pack")
        for name in names:
            path = Path(directory) / name
            relative = path.relative_to(source).as_posix()
            if relative not in originals and relative not in {MANIFEST, *licenses, "PROVENANCE.json", "provenance.json"}:
                raise ValueError("Seed contains files outside shared skills/license/provenance")
            data = bounded_read(path)
            total += len(data)
            if total > MAX_TOTAL or len(files) >= MAX_FILES:
                raise ValueError("Prepared seed exceeds byte/file limit")
            files[relative] = data
    allowed_extra = {MANIFEST, *licenses}
    # Product installer may redistribute its provenance next to LICENSE; retain
    # only explicitly named non-secret product metadata, never arbitrary extras.
    for name in ("PROVENANCE.json", "provenance.json"):
        if name in files:
            json.loads(files[name])
            allowed_extra.add(name)
    if set(files) - set(originals) - allowed_extra:
        raise ValueError("Seed contains files outside shared skills/license/provenance")
    if set(originals) - set(files):
        raise ValueError("Prepared seed is missing original official skill files")
    shared = "lark-shared/SKILL.md"
    addition = bounded_read(guidance).decode("utf-8").strip()
    for relative, original in originals.items():
        expected = original
        if relative == shared:
            expected = (original.decode("utf-8").rstrip() + "\n\n" + addition + "\n").encode("utf-8")
        if files[relative] != expected:
            raise ValueError(f"Prepared seed differs from official/product bytes: {relative}")
    for name, content in licenses.items():
        if files.get(name) != content:
            raise ValueError("Prepared seed must retain the original official license")
    # Product uses Path.sorted(): Windows compares paths without case, Unix
    # compares case. The prepared seed can be transported across those hosts.
    aggregates = {}
    for order, key in (("unix", None), ("windows", str.casefold)):
        aggregate = hashlib.sha256()
        # Product groups by skill name FIRST (lark-vc before lark-vc-agent),
        # then sorts that package's file paths. A global path sort is different.
        for name in sorted(NAMES):
            for relative in sorted((item for item in originals if item.startswith(name + "/")), key=key):
                aggregate.update(relative.encode("utf-8") + b"\0" + files[relative] + b"\0")
        aggregates[order] = aggregate.hexdigest()
    content_sha = manifest.get("content_sha256")
    if content_sha not in aggregates.values():
        raise ValueError("Product content digest does not match prepared skill bytes")
    if not any(name.startswith("lark-doc/scripts/") for name in files):
        raise ValueError("Official lark-doc must include real scripts")
    report = {"schemaVersion": 1, "preparedAt": datetime.now(timezone.utc).isoformat(),
              "version": version, "source": manifest["source"],
              "officialArchiveSha256": archive_sha256,
              "productContentSha256": content_sha, "skills": list(NAMES),
              "productPathOrder": next(order for order, value in aggregates.items() if value == content_sha),
              "files": {name: digest(data) for name, data in sorted(files.items())},
              "reference": REFERENCE, "referenceSha256": digest(files[REFERENCE]),
              "officeOAuth": "BLOCKED_EXTERNAL", "officeDocumentRun": "BLOCKED_EXTERNAL"}
    return files, report


def prepare(source: Path, archive: Path, guidance: Path, data_root: Path, evidence: Path) -> dict:
    files, report = validate_seed(source, archive, guidance)
    data_root = safe_path(data_root)
    if data_root.exists():
        raise ValueError("Lark profile requires a fresh, nonexistent data root")
    destination = data_root / "integrations/lark/skills"
    destination.mkdir(parents=True)
    try:
        for relative, content in files.items():
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
            if digest(bounded_read(target)) != report["files"][relative]:
                raise ValueError("Copied seed content changed")
    except BaseException:
        # Only this newly created data root is eligible for cleanup.
        shutil.rmtree(data_root)
        raise
    report["packageDirectory"] = str(destination)
    evidence = safe_path(evidence)
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("source", "archive", "guidance", "data-root", "evidence"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = prepare(args.source, args.archive, args.guidance, args.data_root, args.evidence)
        print(json.dumps({"status": "PREPARED", "skills": len(report["skills"]), "version": report["version"]}))
        return 0
    except (ValueError, OSError, zipfile.BadZipFile, KeyError) as error:
        print(json.dumps({"status": "FAIL", "error": str(error)}), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

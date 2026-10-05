"""Summarizes the public Haifa SDK surface from the published JARs.

An executor that cannot read the SDK sources or the local Maven repository receives a
signed-off text summary instead: the public and protected signatures of every class in
every Haifa SDK artifact. The controller runs this script before dispatch and points the
prompt at ``<worktree>/local-tmp/sdk-api/<version>/``.

    python scripts/governance/sdk_api_digest.py --worktree <worktree> [--maven-repo <repo>] [--version <v>]

The version follows the same rule as ``scripts/lib/sdk-selection.ps1``: an explicit
``--version`` wins, then ``local-tmp/sdk-selection.json``, then the baseline. The artifact
list is parsed from the worktree POMs (the imported SDK BOM coordinate) and the BOM itself;
membership is trusted only when the BOM's own group id matches the managed artifacts, so an
ambiguous BOM stops with a report instead of guessing. JARs are read in place, never copied,
and ``javap -protected`` keeps public and protected signatures only; anonymous inner classes
are skipped, and package-private members and method bodies never appear.

The output directory must be inside the worktree's git-ignored ``local-tmp/``. Re-running
with every JAR hash unchanged returns ``UNCHANGED`` and writes nothing; any change to any JAR
regenerates the whole summary (the SDK is re-published under the same version number).
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

BASELINE_VERSION = "0.1.2-SNAPSHOT"
SELECTED_PATTERN = re.compile(r"^0\.1\.2-[A-Za-z0-9.-]+-SNAPSHOT$")
ANONYMOUS_PATTERN = re.compile(r"\$\d")
DECLARATION_PATTERN = re.compile(r"^(public|protected)\s")
MANIFEST_NAMES = ("publish-manifest.json", "sdk-publish-manifest.json", "sdk-publish.json")


class SdkDigestError(RuntimeError):
    """Raised when the SDK artifacts cannot be determined or written reliably."""


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def children(element, name: str):
    return [child for child in element if local_name(child.tag) == name]


def first(element, name: str):
    matches = children(element, name)
    return matches[0] if matches else None


def text(element, name: str) -> str | None:
    if element is None:
        return None
    found = first(element, name)
    return found.text.strip() if found is not None and found.text else None


def read_version(selection_path: Path, override: str | None) -> str:
    if override:
        return override
    if selection_path.is_file():
        data = json.loads(selection_path.read_text(encoding="utf-8-sig"))
        version = str(data.get("haifaVersion", "")).strip()
        if not version:
            raise SdkDigestError(f"'{selection_path}' must contain a non-empty 'haifaVersion'.")
        if version != BASELINE_VERSION and not SELECTED_PATTERN.match(version):
            raise SdkDigestError(f"'{selection_path}' has unsupported haifaVersion '{version}'.")
        return version
    return BASELINE_VERSION


def find_bom_coordinate(worktree: Path) -> tuple[str, str]:
    """The SDK BOM import declared by the worktree POMs."""
    candidates: set[tuple[str, str]] = set()
    for pom in sorted(worktree.glob("pom.xml")) + sorted(worktree.glob("*/pom.xml")):
        try:
            project = ET.parse(pom).getroot()
        except ET.ParseError:
            continue
        management = first(project, "dependencyManagement")
        if management is None:
            continue
        dependencies = first(management, "dependencies")
        if dependencies is None:
            continue
        for dependency in children(dependencies, "dependency"):
            group = text(dependency, "groupId") or ""
            artifact = text(dependency, "artifactId") or ""
            kind = text(dependency, "type") or "jar"
            scope = text(dependency, "scope") or ""
            if kind == "pom" and scope == "import" and group.startswith("io.haifa") and artifact.endswith("-bom"):
                candidates.add((group, artifact))
    if len(candidates) != 1:
        raise SdkDigestError(
            "could not reliably determine the Haifa SDK BOM import from the worktree POMs; "
            f"found {sorted(candidates) or 'none'}. Stop and report.")
    return candidates.pop()


def bom_path(maven_repo: Path, group: str, artifact: str, version: str) -> Path:
    return maven_repo.joinpath(*group.split("."), artifact, version, f"{artifact}-{version}.pom")


def artifact_path(maven_repo: Path, group: str, artifact: str, version: str, extension: str) -> Path:
    return maven_repo.joinpath(*group.split("."), artifact, version, f"{artifact}-{version}.{extension}")


def resolve_properties(project) -> dict[str, str]:
    properties: dict[str, str] = {}
    props = first(project, "properties")
    if props is not None:
        for prop in props:
            properties[local_name(prop.tag)] = (prop.text or "").strip()
    version = text(project, "version")
    if version:
        properties.setdefault("project.version", version)
    return properties


def resolve(value: str | None, properties: dict[str, str]) -> str | None:
    if value is None:
        return None
    for match in reversed(re.findall(r"\$\{([^}]+)\}", value)):
        value = value.replace(f"${{{match}}}", properties.get(match, value))
    return value


def sdk_artifacts(bom_pom: Path) -> list[dict]:
    """Every managed jar artifact whose group id matches the BOM's own group id."""
    try:
        project = ET.parse(bom_pom).getroot()
    except ET.ParseError as error:
        raise SdkDigestError(f"the SDK BOM '{bom_pom}' is not valid XML: {error}") from error
    group = text(project, "groupId")
    if group is None:
        parent = first(project, "parent")
        group = text(parent, "groupId")
    if not group:
        raise SdkDigestError(f"the SDK BOM '{bom_pom}' has no group id; cannot decide membership. Stop and report.")
    properties = resolve_properties(project)
    management = first(project, "dependencyManagement")
    dependencies = first(management, "dependencies") if management is not None else None
    if dependencies is None:
        raise SdkDigestError(f"the SDK BOM '{bom_pom}' manages no dependencies; cannot decide membership. Stop and report.")
    artifacts: list[dict] = []
    seen: set[str] = set()
    for dependency in children(dependencies, "dependency"):
        entry_group = resolve(text(dependency, "groupId"), properties) or group
        artifact = resolve(text(dependency, "artifactId"), properties)
        version = resolve(text(dependency, "version"), properties)
        kind = (text(dependency, "type") or "jar").lower()
        if entry_group != group or not artifact or kind == "pom" or artifact in seen:
            continue
        seen.add(artifact)
        artifacts.append({"groupId": entry_group, "artifactId": artifact, "version": version or ""})
    if not artifacts:
        raise SdkDigestError(
            f"the SDK BOM '{bom_pom}' lists no jar artifacts under group '{group}'; cannot decide membership. "
            "Stop and report.")
    return sorted(artifacts, key=lambda entry: entry["artifactId"])


def parse_sdk_commit(bom_pom: Path) -> str | None:
    """Best-effort upstream commit from a publish manifest or the BOM SCM."""
    for name in MANIFEST_NAMES:
        manifest = bom_pom.parent / name
        if manifest.is_file():
            data = json.loads(manifest.read_text(encoding="utf-8-sig"))
            for key in ("sdkCommit", "commit", "revision"):
                if isinstance(data, dict) and data.get(key):
                    return str(data[key])
    try:
        project = ET.parse(bom_pom).getroot()
    except ET.ParseError:
        return None
    scm = first(project, "scm")
    if scm is not None:
        for key in ("revision", "tag"):
            value = text(scm, key)
            if value:
                return value
    return None


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def classes_of(jar: Path) -> list[str]:
    names: list[str] = []
    with zipfile.ZipFile(jar) as archive:
        for entry in archive.namelist():
            if not entry.endswith(".class") or entry.startswith("META-INF/"):
                continue
            if entry.endswith("module-info.class"):
                continue
            name = entry[:-len(".class")].replace("/", ".")
            if ANONYMOUS_PATTERN.search(name):
                continue
            names.append(name)
    return sorted(set(names))


def javap(jar: Path, class_name: str, executable: str = "javap") -> str:
    result = subprocess.run(
        [executable, "-protected", "-classpath", str(jar), class_name],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if result.returncode != 0:
        raise SdkDigestError(f"javap failed for {class_name} in {jar.name}: {result.stderr.strip()}")
    return result.stdout


def is_public_surface(output: str) -> bool:
    """The javap class declaration must be public or protected, not package-private."""
    for line in output.splitlines():
        stripped = line.strip()
        if stripped.endswith("{"):
            return bool(DECLARATION_PATTERN.match(stripped))
    return False


def output_directory(worktree: Path, version: str) -> Path:
    local_tmp = (worktree / "local-tmp").resolve()
    target = (local_tmp / "sdk-api" / version).resolve()
    if target != local_tmp and local_tmp not in target.parents:
        raise SdkDigestError(f"output directory '{target}' is not inside '{local_tmp}'.")
    ignored = subprocess.run(["git", "-C", str(worktree), "check-ignore", "-q", "local-tmp"],
                             capture_output=True, text=True)
    if ignored.returncode != 0:
        raise SdkDigestError("'local-tmp' is not ignored by git; refusing to write the SDK summary there.")
    return target


def collect(worktree: Path, maven_repo: Path, version: str) -> tuple[list[dict], list[str], str | None]:
    group, artifact = find_bom_coordinate(worktree)
    pom = bom_path(maven_repo, group, artifact, version)
    if not pom.is_file():
        raise SdkDigestError(f"the SDK BOM is not published at '{pom}'. Stop and report.")
    sdk_commit = parse_sdk_commit(pom)
    artifacts: list[dict] = []
    missing: list[str] = []
    for entry in sdk_artifacts(pom):
        jar = artifact_path(maven_repo, entry["groupId"], entry["artifactId"], version, "jar")
        if not jar.is_file():
            missing.append(entry["artifactId"])
            continue
        artifacts.append({
            "groupId": entry["groupId"],
            "artifactId": entry["artifactId"],
            "jar": jar.name,
            "sha256": sha256(jar),
            "length": jar.stat().st_size,
            "classCount": len(classes_of(jar)),
        })
    if not artifacts:
        raise SdkDigestError(f"no published SDK JARs found under '{maven_repo}' for version '{version}'. Stop and report.")
    return artifacts, missing, sdk_commit


def generate(worktree: Path, maven_repo: Path, override: str | None = None,
             javap_executable: str = "javap") -> dict:
    worktree = worktree.resolve()
    version = read_version(worktree / "local-tmp/sdk-selection.json", override)
    target = output_directory(worktree, version)
    artifacts, missing, sdk_commit = collect(worktree, maven_repo, version)
    index_path = target / "index.json"
    current = {entry["artifactId"]: entry["sha256"] for entry in artifacts}
    if index_path.is_file():
        previous = json.loads(index_path.read_text(encoding="utf-8-sig"))
        if {entry["artifactId"]: entry["sha256"] for entry in previous.get("artifacts", [])} == current:
            return {"status": "UNCHANGED", "version": version, "outputDir": str(target),
                    "artifacts": len(current), "sdkCommit": previous.get("sdkCommit")}

    target.mkdir(parents=True, exist_ok=True)
    classes_lines: list[str] = []
    for entry in artifacts:
        jar = maven_repo.joinpath(*entry["groupId"].split("."), entry["artifactId"], version, entry["jar"])
        names = classes_of(jar)
        blocks = []
        for name in names:
            output = javap(jar, name, javap_executable)
            if not is_public_surface(output):
                continue
            blocks.append(output)
            classes_lines.append(f"{name}\t{entry['artifactId']}")
        (target / f"{entry['artifactId']}.api.txt").write_text(
            "\n".join(block.rstrip() for block in blocks) + "\n", encoding="utf-8")
    (target / "classes.txt").write_text("\n".join(classes_lines) + "\n", encoding="utf-8")
    index = {
        "version": version,
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "worktree": str(worktree),
        "mavenRepo": str(maven_repo),
        "sdkCommit": sdk_commit,
        "artifacts": artifacts,
        "missingArtifacts": missing,
    }
    index_path.write_text(json.dumps(index, indent=2, ensure_ascii=False), encoding="utf-8")
    return {"status": "GENERATED", "version": version, "outputDir": str(target),
            "artifacts": len(artifacts), "sdkCommit": sdk_commit, "missingArtifacts": missing}


def default_maven_repo() -> Path:
    settings = Path.home() / ".m2" / "settings.xml"
    if settings.is_file():
        try:
            project = ET.parse(settings).getroot()
            local = first(project, "localRepository")
            if local is not None and local.text and local.text.strip():
                return Path(local.text.strip())
        except ET.ParseError:
            pass
    return Path.home() / ".m2" / "repository"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Summarize the public Haifa SDK signatures")
    parser.add_argument("--worktree", required=True, type=Path)
    parser.add_argument("--maven-repo", type=Path, default=None)
    parser.add_argument("--version")
    args = parser.parse_args(argv)
    maven_repo = (args.maven_repo or default_maven_repo()).resolve()
    try:
        result = generate(args.worktree, maven_repo, args.version)
    except (SdkDigestError, OSError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())

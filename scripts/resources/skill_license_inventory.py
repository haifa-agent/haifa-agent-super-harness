"""Reconcile frozen source inventory with the product's bundled Skill manifest."""
import argparse
import hashlib
import json
from pathlib import Path

PIN = "e545c28ac301e6f9feae247c513d8ea8f83df294"

def bundled(repository):
    manifest = json.loads((repository / "third-party/deerflow/skills/manifest.json").read_text(encoding="utf-8"))
    return {entry["name"]: entry for entry in manifest["packages"] if entry["active"] and entry["source"] == "bytedance/deer-flow"}

def checked(repository, relative, expected):
    path = repository / relative
    if not path.resolve().is_relative_to((repository / "third-party/deerflow").resolve()) or path.is_symlink():
        raise ValueError("Bundled Skill resource leaves its declared import root")
    if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise ValueError("Bundled Skill resource or license hash drifted: " + relative)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    inventory = json.loads(args.inventory.read_text(encoding="utf-8"))
    packages = bundled(args.repository)
    for row in inventory["non_imported_skills"]:
        name = row["id"].removeprefix("deerflow.skill.")
        package = packages.get(name)
        if package is None:
            if row["import_status"] != "NOT_IMPORTED":
                raise ValueError("Non-imported skill '" + row["id"] + "' was mislabeled as imported")
            continue
        if package["source_sha"] != PIN:
            raise ValueError("Bundled Skill source is not frozen")
        for relative, digest in package["files"].items():
            checked(args.repository, package["root"] + "/" + relative, digest)
        checked(args.repository, package["license_file"], package["license_sha256"])
        expected = {"category": "bundled_skill", "import_status": "BUNDLED",
                    "intended_local_path": package["root"] + "/" + name,
                    "license_expression": package["license"], "license_basis": "BUNDLED_LICENSE_TEXT",
                    "license_basis_verified": True, "review_status": "SOURCE_VERIFIED", "manifest_reviewed": True,
                    "review_note": "Frozen complete resource bytes and bundled license provenance verified; execution, safety and legal clearance are separate gates.",
                    "evidence": {"kind": "BUNDLED_LICENSE_TEXT", "location": "third-party/deerflow/skills/manifest.json",
                                 "license_path": package["license_file"], "license_sha256": package["license_sha256"],
                                 "package_version": package["version"], "file_count": len(package["files"])}}
        if row["sha256"] != package["files"][name + "/SKILL.md"]:
            raise ValueError("Bundled Skill SKILL.md differs from frozen source inventory")
        if args.verify:
            if any(row.get(key) != value for key, value in expected.items()):
                raise ValueError("Bundled Skill inventory differs from its authoritative resource manifest: " + name)
        else:
            row.update(expected)
    if not args.verify:
        inventory["known_not_covered"] = [note for note in inventory["known_not_covered"] if not note.startswith("All 23 frozen public Skill packages")]
        inventory["known_not_covered"].append("The legacy non_imported_skills field lists all 23 frozen source packages; BUNDLED entries are reconciled to the actual product manifest. Source verification does not prove execution or safety.")
        args.inventory.write_text(json.dumps(inventory, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": "PASS", "bundled_skills": len(packages), "mode": "verify" if args.verify else "reconcile"}))

if __name__ == "__main__":
    main()

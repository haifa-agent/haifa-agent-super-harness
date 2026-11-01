"""Focused safety tests for the prepared-product-seed copier; no service or CLI."""
import hashlib
import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("lark_seed", ROOT / "tests/ui/fixtures/w20/lark_seed.py")
seed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(seed)


class PreparedSeedTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "prepared"
        self.source.mkdir()
        self.archive = self.root / "official-fixture.zip"
        self.guidance = self.root / "guidance.md"
        self.guidance.write_text("known product guidance\n", encoding="utf-8")
        originals = {f"{name}/SKILL.md": f"---\nname: {name}\n---\nOriginal\n".encode() for name in seed.NAMES}
        originals.update({seed.REFERENCE: b"# docs +fetch\n--scope keyword\n", "lark-doc/scripts/test.py": b"print('original')\n"})
        with zipfile.ZipFile(self.archive, "w") as archive:
            for relative, content in originals.items():
                archive.writestr("cli-1.0.65/skills/" + relative, content)
            archive.writestr("cli-1.0.65/LICENSE", b"MIT fixture license\n")
        files = dict(originals)
        files.update({f"{name}/LICENSE.txt": b"MIT fixture license\n" for name in seed.NAMES})
        files["lark-shared/SKILL.md"] = files["lark-shared/SKILL.md"].rstrip() + b"\n\nknown product guidance\n"
        aggregate = hashlib.sha256()
        for name in sorted(seed.NAMES):
            for relative in sorted(item for item in files if item.startswith(name + "/")):
                aggregate.update(relative.encode() + b"\0" + files[relative] + b"\0")
        for relative, content in files.items():
            path = self.source / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        (self.source / "LICENSE").write_bytes(b"MIT fixture license\n")
        self.manifest = {"provider": "lark-cli", "version": "v1.0.65", "skills": list(seed.NAMES),
                         "source": "https://codeload.github.com/larksuite/cli/zip/refs/tags/v1.0.65",
                         "content_sha256": aggregate.hexdigest(), "installed_at": "2026-10-27T12:00:00Z",
                         "archive_sha256": seed.digest(self.archive.read_bytes()),
                         "license_sha256": seed.digest(b"MIT fixture license\n"),
                         "license": "MIT", "platforms": "gateway-host"}
        self.write_manifest()

    def write_manifest(self):
        (self.source / seed.MANIFEST).write_text(json.dumps(self.manifest), encoding="utf-8")

    def validate(self):
        return seed.validate_seed(self.source, self.archive, self.guidance)

    def test_copy_preserves_official_bytes_and_only_shared_state(self):
        report = seed.prepare(self.source, self.archive, self.guidance, self.root / "data", self.root / "evidence/seed.json")
        self.assertEqual(len(report["skills"]), 27)
        target = Path(report["packageDirectory"])
        self.assertEqual((target / seed.REFERENCE).read_bytes(), (self.source / seed.REFERENCE).read_bytes())
        self.assertFalse((self.root / "data/integrations/lark/users").exists())
        self.assertEqual(report["officeOAuth"], "BLOCKED_EXTERNAL")

    def test_tampered_script_cannot_be_copied(self):
        (self.source / "lark-doc/scripts/test.py").write_text("changed", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "differs"):
            self.validate()

    def test_extra_credential_tree_is_rejected_without_copy(self):
        (self.source / "users").mkdir()
        (self.source / "users/secret.json").write_text("DO NOT COPY", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "directories outside"):
            self.validate()

    def test_missing_official_license_is_rejected(self):
        (self.source / "LICENSE").unlink()
        with self.assertRaisesRegex(ValueError, "original official license"):
            self.validate()

    def test_reused_data_root_is_rejected(self):
        data = self.root / "data"
        data.mkdir()
        with self.assertRaisesRegex(ValueError, "fresh"):
            seed.prepare(self.source, self.archive, self.guidance, data, self.root / "evidence.json")

    def test_version_drift_is_rejected(self):
        self.manifest["version"] = "v1.0.66"
        self.manifest["source"] = self.manifest["source"].replace("65", "66")
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "archive root"):
            self.validate()

    def test_archive_traversal_is_rejected(self):
        with zipfile.ZipFile(self.archive, "a") as archive:
            archive.writestr("cli-1.0.65/../outside", "bad")
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            self.validate()

    def test_archive_byte_and_entry_limits_are_enforced(self):
        from unittest.mock import patch
        with patch.object(seed, "MAX_ARCHIVE", 10):
            with self.assertRaisesRegex(ValueError, "oversized"):
                self.validate()
        with patch.object(seed, "MAX_FILES", 1):
            with self.assertRaisesRegex(ValueError, "too many entries"):
                self.validate()

    def test_license_copy_and_provenance_tampering_are_rejected(self):
        (self.source / "lark-doc/LICENSE.txt").write_text("changed", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "differs"):
            self.validate()

    def test_case_insensitive_order_is_not_a_second_pack_identity(self):
        # The product hashes one canonical order. A digest built with Windows
        # case-insensitive Path ordering must not be accepted, otherwise the same
        # official pack would carry two identities depending on the host.
        aggregate = hashlib.sha256()
        for name in sorted(seed.NAMES):
            files = sorted((self.source / name).rglob("*"), key=lambda path: str(path).casefold())
            for path in files:
                if path.is_file():
                    aggregate.update(path.relative_to(self.source).as_posix().encode() + b"\0" + path.read_bytes() + b"\0")
        self.manifest["content_sha256"] = aggregate.hexdigest()
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "content digest"):
            self.validate()

    def test_canonical_digest_is_reported_as_a_single_order(self):
        self.assertEqual(self.validate()[1]["productPathOrder"], "canonical-relative-path")

    def test_arbitrary_manifest_digest_is_never_accepted(self):
        self.manifest["content_sha256"] = "0" * 64
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "content digest"):
            self.validate()

    def test_links_and_reparse_points_are_rejected(self):
        from unittest.mock import patch
        real = Path.lstat

        def linked(path, *args, **kwargs):
            if path == self.source:
                class Reparse:
                    st_mode = 0o40755
                    st_file_attributes = 0x400
                return Reparse()
            return real(path, *args, **kwargs)

        with patch.object(Path, "lstat", linked):
            with self.assertRaisesRegex(ValueError, "reparse"):
                self.validate()


if __name__ == "__main__":
    unittest.main()

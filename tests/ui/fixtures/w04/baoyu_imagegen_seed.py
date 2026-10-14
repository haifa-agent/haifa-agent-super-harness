"""Materialize the bounded baoyu-image-gen execution seed (W04 imagegen UI profile).

The frozen baoyu-image-gen package is not available inside this worktree, so the
W04 imagegen profile plants this bounded execution seed in its own data root. The
seed is a real runnable multi-file package: the declared entry ``scripts/main.ts``
is executed by bun through the trusted Skill script Tool, resolves cross-directory
relative imports (``../lib/...``), reads ``EXTEND.md`` beside the package, produces
a deterministic PNG artifact under the Run workspace's ``outputs/`` and prints the
artifact digest. Its bounded business contract matches the product declaration:

    bun {baseDir}/scripts/main.ts --help
    bun {baseDir}/scripts/main.ts --provider codex-cli --image outputs/<name>.png --prompt <text>

Materialization and verification:

    python -B tests/ui/fixtures/w04/baoyu_imagegen_seed.py \
        --package <dataRoot>/skills-fixture/baoyu-image-gen \
        --extend <dataRoot>/.baoyu-skills \
        --manifest <dataRoot>/skill-seed-manifest.json

``--verify`` re-reads the materialized files and re-checks every recorded digest;
exit code 0 means the on-disk seed still matches the manifest.

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

SKILL_MD = (
    "---\n"
    "name: baoyu-image-gen\n"
    "description: Generate bounded seeded images by running the package entry with bun through the trusted Skill script Tool\n"
    "license: MIT\n"
    "---\n"
    "# baoyu-image-gen (W04 bounded execution seed)\n"
    "\n"
    "This is the isolated execution seed the W04 imagegen UI profile plants in its\n"
    "own data root. It proves the full trusted Skill script chain: the frozen\n"
    "package identity, multi-file relative imports, the declared bun runtime and a\n"
    "deterministic artifact under this Run's `outputs/`.\n"
    "\n"
    "## Execution contract\n"
    "\n"
    "- Entry: `scripts/main.ts`, executed by bun as a file (never through stdin).\n"
    "- Generation: `--provider codex-cli --image outputs/<name>.png` with `--prompt`.\n"
    "- Probe: `--help` prints the banner and the provider declared in `EXTEND.md`.\n"
    "- The `sleep_millis` argument is an acceptance probe only.\n"
    "\n"
    "## How to run it in this Harness\n"
    "\n"
    "Do not try to resolve `{baseDir}` or run shell commands: the runtime, script,\n"
    "working directory and `outputs/` location are fixed by the Harness. Use the\n"
    "`baoyu_image_gen` tool with the arguments above (prompt and image_name); every\n"
    "call requires approval. The generated file lands in `/mnt/user-data/outputs/`.\n"
    "\n"
    "Read `EXTEND.md` beside this file for the extension config; the seeded\n"
    "provider is `codex-cli` (no API key).\n"
)

EXTEND_MD = (
    "---\n"
    "default_provider: codex-cli\n"
    "---\n"
    "# baoyu-image-gen extension config (isolated seed)\n"
    "\n"
    "`default_provider: codex-cli` is the only extension fact this seed declares.\n"
    "The package copy and the `<dataRoot>/.baoyu-skills/baoyu-image-gen/EXTEND.md`\n"
    "companion are byte-identical and no external directory is ever written.\n"
)

MAIN_TS = '''// W04 bounded execution seed entry. bun executes this file directly; its relative
// imports reach outside scripts/ to prove multi-file package materialization.
import { parseArguments, usageBanner } from "../lib/argv.ts";
import { extendProvider } from "../lib/extend.ts";
import { renderImageArtifact } from "../lib/render.ts";

const args = parseArguments(process.argv.slice(2));
if (args === null) {
  console.error("W04_SEED_MAIN_UNKNOWN_ARGUMENT");
  process.exit(2);
}
if (args === "help") {
  console.log(usageBanner(extendProvider()));
  process.exit(0);
}

const command = args as GenerateCommand;
if (command.sleepMillis > 0) {
  console.log(`W04_SEED_SLEEP_ENTERED_MS=${command.sleepMillis}`);
  await new Promise((resolve) => setTimeout(resolve, command.sleepMillis));
}
if (command.provider !== extendProvider()) {
  console.error(`W04_SEED_PROVIDER_REFUSED expected=${extendProvider()} actual=${command.provider}`);
  process.exit(3);
}
const artifact = renderImageArtifact(command.imageName, command.prompt);
console.log(`W04_SEED_PROVIDER_OK=${command.provider}`);
console.log(`W04_SEED_ARTIFACT=outputs/${artifact.fileName}`);
console.log(`W04_SEED_ARTIFACT_BYTES=${artifact.byteSize}`);
console.log(`W04_SEED_IMAGE_SHA256=${artifact.sha256}`);
process.exit(0);
'''

ARGV_TS = '''// W04 bounded execution seed argument parser. Mirrors the product mapper: unknown
// flags, non-outputs image paths and out-of-bound probes fail closed before any
// artifact is written.
export const PROVIDER_ARGUMENT = "codex-cli";

export interface GenerateCommand {
  kind: "generate";
  provider: string;
  imageName: string;
  prompt: string;
  sleepMillis: number;
}

const IMAGE_PATTERN = /^[a-z0-9][a-z0-9._-]{0,55}\\.png$/;

export function usageBanner(provider: string): string {
  return [
    "W04_SEED_MAIN_OK baoyu-image-gen",
    `W04_SEED_PROVIDER_DEFAULT=${provider}`,
    "usage: main.ts --provider " + provider + " --image outputs/<name>.png --prompt <text>",
    "usage: main.ts --help",
  ].join("\\n");
}

export function parseArguments(argv: string[]): GenerateCommand | "help" | null {
  if (argv.length === 0 || argv.includes("--help")) return "help";
  let provider: string | null = null;
  let imageName: string | null = null;
  let prompt: string | null = null;
  let sleepMillis = 0;
  let index = 0;
  while (index < argv.length) {
    const flag = argv[index];
    const value = argv[index + 1];
    if (value === undefined) return null;
    if (flag === "--provider") provider = value;
    else if (flag === "--image") imageName = value;
    else if (flag === "--prompt") prompt = value;
    else if (flag === "--sleep") sleepMillis = Number(value);
    else return null;
    index += 2;
  }
  if (provider === null || imageName === null || prompt === null) return null;
  if (provider !== PROVIDER_ARGUMENT) return null;
  if (!imageName.startsWith("outputs/")) return null;
  const fileName = imageName.substring("outputs/".length);
  if (!IMAGE_PATTERN.test(fileName) || !fileName.endsWith(".png")) return null;
  if (prompt.length < 1 || prompt.length > 4096) return null;
  if (!Number.isInteger(sleepMillis) || sleepMillis < 0 || sleepMillis > 600000) return null;
  return { kind: "generate", provider, imageName: fileName, prompt, sleepMillis };
}
'''

EXTEND_TS = '''// W04 bounded execution seed extension reader. Reads EXTEND.md from the package
// root through a relative import time (Bun-native readFileSync), proving the
// package's own trusted bytes are already visible to the executed entry.
import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";

export const FALLBACK_PROVIDER = "codex-cli";

export function extendProvider(): string {
  try {
    const markdown = readFileSync(join(import.meta.dir, "..", "EXTEND.md"), "utf8");
    const match = markdown.match(/default_provider:\\s*([a-z0-9-]+)/);
    if (match) return match[1];
  } catch (error) {
    console.error("W04_SEED_EXTEND_UNREADABLE");
  }
  return FALLBACK_PROVIDER;
}
'''

RENDER_TS = '''// W04 bounded execution seed renderer. Produces a deterministic, valid PNG whose
// pixels embed the prompt digest, writes it under outputs/ (workspace-root relative)
// and returns size + SHA-256 facts that the tool result proves for the artifact chain.
import { createHash } from "node:crypto";
import { deflateSync } from "node:zlib";
import { existsSync, mkdirSync, writeFileSync, readFileSync } from "node:fs";
import { basename, join } from "node:path";

const WIDTH = 192;
const HEIGHT = 96;
const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let value = 0; value < 256; value++) {
    let current = value;
    for (let bit = 0; bit < 8; bit++) current = current & 1 ? 0xedb88320 ^ (current >>> 1) : current >>> 1;
    table[value] = current >>> 0;
  }
  return table;
})();

function crc32(bytes: Uint8Array): number {
  let crc = 0xffffffff;
  for (const byte of bytes) crc = CRC_TABLE[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(kind: string, payload: Uint8Array): Uint8Array {
  const head = new Uint8Array(4 + kind.length + payload.length + 4);
  const view = new DataView(head.buffer);
  view.setUint32(0, payload.length);
  head.set(Buffer.from(kind, "latin1"), 4);
  head.set(payload, 4 + kind.length);
  view.setUint32(4 + kind.length + payload.length, crc32(head.subarray(4, 4 + kind.length + payload.length)));
  return head;
}

export interface RenderedArtifact {
  fileName: string;
  byteSize: number;
  sha256: string;
}

export function renderImageArtifact(imageName: string, prompt: string): RenderedArtifact {
  const promptDigest = createHash("sha256").update("W04_SEED_PROMPT\\n" + prompt, "utf8").digest();
  const pixels = Buffer.alloc(HEIGHT * (WIDTH * 3 + 1));
  for (let row = 0; row < HEIGHT; row++) {
    const line = row * (WIDTH * 3 + 1);
    pixels[line] = 0;
    for (let column = 0; column < WIDTH; column++) {
      const byte = promptDigest[(row * WIDTH + column) % promptDigest.length];
      const start = line + 1 + column * 3;
      pixels[start] = 0x18 + byte;
      pixels[start + 1] = byte;
      pixels[start + 2] = 0x30 + (byte >>> 1);
    }
  }
  const ihdr = new Uint8Array(13);
  const view = new DataView(ihdr.buffer);
  view.setUint32(0, WIDTH);
  view.setUint32(4, HEIGHT);
  ihdr[8] = 8; ihdr[9] = 2;
  const signature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const compressed = deflateSync(pixels, { level: 6 });
  const png = Buffer.concat([
    signature,
    Buffer.from(chunk("IHDR", ihdr)),
    Buffer.from(chunk("IDAT", new Uint8Array(compressed))),
    Buffer.from(chunk("IEND", new Uint8Array(0))),
  ]);
  const sha256 = createHash("sha256").update(png).digest("hex");
  const outputsDirectory = join("outputs");
  if (!existsSync(outputsDirectory)) mkdirSync(outputsDirectory, { recursive: true });
  const target = join(outputsDirectory, basename(imageName));
  writeFileSync(target, png);
  const written = readFileSync(target);
  if (written.length !== png.length) {
    console.error("W04_SEED_ARTIFACT_DURABILITY_FAILED");
    process.exit(3);
  }
  return { fileName: basename(imageName), byteSize: written.length, sha256 };
}
'''

PACKAGE_FILES = {
    "SKILL.md": SKILL_MD,
    "EXTEND.md": EXTEND_MD,
    "scripts/main.ts": MAIN_TS,
    "lib/argv.ts": ARGV_TS,
    "lib/extend.ts": EXTEND_TS,
    "lib/render.ts": RENDER_TS,
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
        "entry": "scripts/main.ts",
        "runtime": "bun",
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

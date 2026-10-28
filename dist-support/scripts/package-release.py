#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Haifa Super Harness release 打包工具（dist-support）。

把仓库里的构建产物收集成一个「单前缀」发布目录，供外部下载部署；不执行单元测试。

命令：
  assemble  从仓库收集制品 -> outputs/super-harness-<version>/（单前缀目录树）
  archive   把 outputs/super-harness-<version>/ 打成 releases/ 下的 .tar.gz 与 .zip
  all       assemble + archive（默认）
  clean     删除 outputs/<version> 与对应 releases 归档

约定：
  * JAR 用 `mvnw -o -ntp -pl super-harness-server -am package -DskipTests` 构建（可 --skip-build 复用）。
  * 前端随包携带源码 + 预构建 .next；node_modules 默认不带（同一平台/离线安装可加 --with-node-modules）。
  * 包内不含密钥；continuation key 由安装脚本在目标机首次生成。
  * 服务定义模板在 templates/，安装期由包内 scripts/install-service.py 渲染。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tarfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from string import Template

HERE = Path(__file__).resolve().parent
DIST_ROOT = HERE.parent
TEMPLATES_DIR = DIST_ROOT / "templates"
DEFAULT_OUTPUTS_DIR = DIST_ROOT / "outputs"
DEFAULT_RELEASES_DIR = DIST_ROOT / "releases"

ENTRY_NAME = "package-release.py"
JAR_NAME_TEMPLATE = "super-harness-server-%s-exec.jar"
PACKAGE_NAME = "super-harness"
EXPECTED_PNPM = "10.26.2"
FORBIDDEN_PORTS = (80, 443)
RESERVED_PORTS = (2026, 3000, 8001, 8002)
DEFAULT_GATEWAY_PORT = 18018
DEFAULT_FRONTEND_PORT = 33018
OFFICIAL_SKILLS_RELATIVE = ("deer-flow", "skills", "public")
# Product choice: these upstream skills are shipped from deer-flow/skills/public but excluded
# from the official list. Pass --exclude-official-skills "" to keep full deer-flow alignment.
DEFAULT_EXCLUDED_OFFICIAL_SKILLS = ("consulting-analysis", "skill-creator", "skill-reviewer")
EMPTY_SKILLS_MANIFEST = '{\n  "schema_version": 1,\n  "packages": []\n}\n'

FRONTEND_IGNORE = shutil.ignore_patterns(
    "node_modules", ".next", ".turbo", ".vercel", "coverage",
    "playwright-report", "test-results", ".pnpm-store",
)
INSTALL_SCRIPT_FILES = (
    "install-service.py",
    "install-service.ps1",
    "install-service.sh",
)
QUICK_SCRIPT_FILES = (
    "quick-start.bat",
    "quick-start.sh",
    "quick-stop.bat",
    "quick-stop.sh",
)

EXIT_OK = 0
EXIT_USAGE = 1


class PackageError(RuntimeError):
    pass


def sha256_file(path):
    digest = hashlib.sha256()
    with open(str(path), "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def which(name):
    return shutil.which(name) or ""


def _reconfigure_stdio():
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass


class Console:
    def __init__(self, log_path):
        self.handle = open(str(log_path), "a", encoding="utf-8", newline="\n")

    def log(self, message=""):
        text = str(message)
        print(text, flush=True)
        self.handle.write(text + "\n")
        self.handle.flush()

    def close(self):
        try:
            self.handle.close()
        except Exception:
            pass


def run(argv, cwd=None, env=None, timeout=3600, capture=True):
    merged = dict(os.environ)
    if env:
        merged.update(env)
    proc = subprocess.run(argv, cwd=str(cwd) if cwd else None, env=merged,
                          stdout=subprocess.PIPE if capture else None,
                          stderr=subprocess.STDOUT if capture else None,
                          text=True, errors="replace", timeout=timeout)
    if proc.returncode != 0:
        tail = "\n".join((proc.stdout or "").splitlines()[-40:]) if capture else ""
        raise PackageError("command failed (exit %d): %s\n%s"
                           % (proc.returncode, " ".join(argv), tail))
    return (proc.stdout or "")


def find_repo_root(start):
    current = Path(start).resolve()
    for candidate in [current] + list(current.parents):
        if (candidate / "pom.xml").is_file() and (candidate / "super-harness-server").is_dir():
            return candidate
    return None


def git(repo_root, *arguments):
    proc = subprocess.run(["git", "-C", str(repo_root)] + list(arguments),
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          text=True, errors="replace")
    if proc.returncode != 0:
        raise PackageError("git %s failed: %s" % (" ".join(arguments), (proc.stdout or "").strip()))
    return (proc.stdout or "").strip()


def detect_version(repo_root):
    pom = (Path(repo_root) / "pom.xml").read_text(encoding="utf-8")
    match = re.search(r"<artifactId>haifa-super-harness-parent</artifactId>\s*<version>([^<]+)</version>",
                      pom)
    if not match:
        match = re.search(r"<version>([^<]+)</version>", pom)
    if not match:
        raise PackageError("cannot detect project version from pom.xml")
    return match.group(1).strip()


def validate_ports(gateway, frontend):
    for name, port in (("gateway", gateway), ("frontend", frontend)):
        if port in FORBIDDEN_PORTS or port < 1024 or port > 65535:
            raise PackageError("%s port must be 1024-65535 and never 80/443; got %d" % (name, port))
        if port in RESERVED_PORTS:
            raise PackageError("%s port %d is reserved by the frozen DeerFlow reference topology"
                               % (name, port))
    if gateway == frontend:
        raise PackageError("gateway and frontend ports must differ")


def preflight(repo_root, need_build, need_frontend):
    missing = []
    if not which("git"):
        missing.append("git")
    if need_build:
        wrapper = Path(repo_root) / ("mvnw.cmd" if os.name == "nt" else "mvnw")
        if not wrapper.is_file():
            missing.append(str(wrapper))
        if not which("java"):
            missing.append("java")
    if need_frontend:
        if not which("node"):
            missing.append("node")
        if not which("pnpm") and not which("pnpm.cmd"):
            missing.append("pnpm")
    if not TEMPLATES_DIR.is_dir():
        missing.append("templates dir %s" % TEMPLATES_DIR)
    for name in INSTALL_SCRIPT_FILES:
        if not (HERE / name).is_file():
            missing.append("scripts/%s" % name)
    for name in QUICK_SCRIPT_FILES:
        if not (TEMPLATES_DIR / name).is_file():
            missing.append("templates/%s" % name)
    if missing:
        raise PackageError("prerequisites missing: %s" % ", ".join(missing))


def build_jar(repo_root, version, console, skip_test_compile=False):
    wrapper = Path(repo_root) / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    skip = "-Dmaven.test.skip=true" if skip_test_compile else "-DskipTests"
    maven_args = ["-o", "-ntp", "-pl", "super-harness-server", "-am", "package", skip]
    command = (["cmd", "/c", str(wrapper)] if os.name == "nt" else [str(wrapper)]) + maven_args
    console.log("[build] %s (no unit tests)" % " ".join(maven_args))
    output = run(command, cwd=repo_root)
    for line in output.splitlines()[-25:]:
        console.log("[build] " + line)
    jar = Path(repo_root) / "super-harness-server" / "target" / (JAR_NAME_TEMPLATE % version)
    if not jar.is_file():
        raise PackageError("expected artifact is missing: %s" % jar)
    return jar


def check_frontend_tooling(frontend_root, console):
    node_version = run([which("node"), "--version"]).strip()
    match = re.match(r"^v(\d+)\.", node_version)
    if not match or int(match.group(1)) < 22:
        raise PackageError("the frozen frontend requires Node.js 22 or newer; got %s" % node_version)
    pnpm = which("pnpm") or which("pnpm.cmd")
    # The corepack shim resolves the pnpm version from the cwd's package.json, so the frozen
    # 10.26.2 only appears when running inside frontend/ (repo root resolves 10.26.1).
    pnpm_version = run([pnpm, "--version"], cwd=frontend_root).strip()
    if pnpm_version != EXPECTED_PNPM:
        raise PackageError("the frozen frontend requires pnpm %s; got %s"
                           % (EXPECTED_PNPM, pnpm_version))
    console.log("[frontend] node=%s pnpm=%s" % (node_version, pnpm_version))
    return pnpm


def copy_frontend(repo_root, stage, with_node_modules, console):
    source = Path(repo_root) / "frontend"
    target = stage / "repo" / "frontend"
    if not source.is_dir():
        raise PackageError("frozen frontend source is missing: %s" % source)
    shutil.copytree(str(source), str(target), ignore=FRONTEND_IGNORE, symlinks=False)
    console.log("[stage] frontend source copied (%d entries)"
                % len(list(target.iterdir())))
    node_modules = source / "node_modules"
    if with_node_modules:
        if not node_modules.is_dir():
            raise PackageError("--with-node-modules requested but %s is missing" % node_modules)
        console.log("[stage] copying node_modules (platform-specific, may be slow)")
        shutil.copytree(str(node_modules), str(target / "node_modules"), symlinks=False)
    return target


def build_frontend(frontend_root, gateway_port, pnpm, force_install, console):
    next_bin = frontend_root / "node_modules" / "next" / "dist" / "bin" / "next"
    if force_install or not next_bin.is_file():
        console.log("[frontend] pnpm install --offline --frozen-lockfile")
        output = run([pnpm, "install", "--offline", "--frozen-lockfile"], cwd=frontend_root,
                     env={"COREPACK_ENABLE_NETWORK": "0"})
        for line in output.splitlines()[-15:]:
            console.log("[frontend] " + line)
        if not next_bin.is_file():
            raise PackageError("repo-local Next.js executable is missing after install: %s" % next_bin)
    build_env = {
        "DEER_FLOW_INTERNAL_GATEWAY_BASE_URL": "http://127.0.0.1:%d" % gateway_port,
        "NEXT_PUBLIC_STATIC_WEBSITE_ONLY": "false",
        "COREPACK_ENABLE_NETWORK": "0",
    }
    console.log("[frontend] pnpm build (gateway=%s)" % build_env["DEER_FLOW_INTERNAL_GATEWAY_BASE_URL"])
    output = run([pnpm, "build"], cwd=frontend_root, env=build_env)
    for line in output.splitlines()[-15:]:
        console.log("[frontend] " + line)
    build_id = frontend_root / ".next" / "BUILD_ID"
    if not build_id.is_file():
        raise PackageError("production build produced no .next/BUILD_ID")
    console.log("[frontend] build ready: %s" % build_id)


def copy_frozen_resources(repo_root, stage, console):
    source = Path(repo_root) / "third-party" / "deerflow"
    target = stage / "repo" / "third-party" / "deerflow"
    if not (source / "manifest.json").is_file():
        raise PackageError("frozen DeerFlow resources are missing: %s" % source)
    shutil.copytree(str(source), str(target), symlinks=False)
    console.log("[stage] frozen resources copied: repo/third-party/deerflow")
    prune_bundled_skills(stage, console)


def prune_bundled_skills(stage, console):
    """Drop the 3 frozen bundled Skill packages; official skills come from skills/public."""
    skills_dir = stage / "repo" / "third-party" / "deerflow" / "skills"
    if skills_dir.exists():
        shutil.rmtree(str(skills_dir))
    skills_dir.mkdir(parents=True, exist_ok=True)
    (skills_dir / "manifest.json").write_text(EMPTY_SKILLS_MANIFEST, encoding="utf-8",
                                              newline="\n")
    console.log("[stage] bundled frozen Skill packages removed; "
                "repo/third-party/deerflow/skills/manifest.json reset to empty")


def frozen_upstream_commit(repo_root):
    manifest = Path(repo_root) / "third-party" / "deerflow" / "manifest.json"
    try:
        payload = json.loads(manifest.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise PackageError("cannot read frozen DeerFlow manifest: %s" % error)
    commit = str((payload.get("upstream") or {}).get("commit") or "").strip()
    if not commit:
        raise PackageError("frozen DeerFlow commit is missing from %s" % manifest)
    return commit


def copy_official_skills(repo_root, stage, source_dir, excluded, console):
    source = Path(source_dir).resolve()
    if not source.is_dir():
        raise PackageError("official skills source is missing: %s" % source)
    expected = frozen_upstream_commit(repo_root)
    checkout = source.parents[1] if len(source.parents) > 1 else source
    actual = ""
    if (checkout / ".git").exists():
        actual = git(checkout, "rev-parse", "HEAD")
        if actual != expected:
            raise PackageError("official skills checkout %s is at %s but the frozen DeerFlow "
                               "commit is %s" % (checkout, actual, expected))
    target = stage / "skills" / "public"
    target.mkdir(parents=True, exist_ok=True)
    copied = []
    skipped = []
    available = set()
    for directory in sorted(source.iterdir()):
        if not directory.is_dir() or not (directory / "SKILL.md").is_file():
            continue
        available.add(directory.name)
        if directory.name in excluded:
            skipped.append(directory.name)
            continue
        shutil.copytree(str(directory), str(target / directory.name), symlinks=False)
        copied.append(directory.name)
    unknown = sorted(set(excluded) - available)
    if unknown:
        console.log("[stage] WARNING excluded official skills not found upstream: %s"
                    % ", ".join(unknown))
    if not copied:
        raise PackageError("official skills source contains no skill packages: %s" % source)
    console.log("[stage] official skills copied: %d -> skills/public (source=%s); excluded=%s"
                % (len(copied), source, ", ".join(sorted(skipped)) or "none"))
    return {"count": len(copied), "source": str(source), "commit": actual or expected,
            "excluded": sorted(skipped)}


def copy_tooling(stage, console):
    (stage / "config").mkdir(parents=True, exist_ok=True)
    shutil.copy2(str(TEMPLATES_DIR / "gateway.env.tmpl"), str(stage / "config" / "gateway.env.tmpl"))
    for platform_dir in ("systemd", "winsw"):
        source = TEMPLATES_DIR / platform_dir
        target = stage / "service" / platform_dir
        shutil.copytree(str(source), str(target), symlinks=False)
    (stage / "scripts").mkdir(parents=True, exist_ok=True)
    for name in INSTALL_SCRIPT_FILES:
        shutil.copy2(str(HERE / name), str(stage / "scripts" / name))
    for name in QUICK_SCRIPT_FILES:
        shutil.copy2(str(TEMPLATES_DIR / name), str(stage / name))
    console.log("[stage] templates + install scripts + quick-start copied: "
                "config/ service/ scripts/ quick-start.*")


def copy_sandbox(repo_root, stage, console, image_tar=None):
    sandbox_src = DIST_ROOT / "sandbox"
    sandbox_dst = stage / "sandbox"
    sandbox_dst.mkdir(parents=True, exist_ok=True)
    if sandbox_src.is_dir():
        for item in sandbox_src.iterdir():
            if item.is_file():
                shutil.copy2(str(item), str(sandbox_dst / item.name))
    lock_file = sandbox_dst / "image-lock.json"
    if not lock_file.is_file():
        raise PackageError("required sandbox image lock is missing: %s" % lock_file)
    lock_data = json.loads(lock_file.read_text(encoding="utf-8"))

    if image_tar:
        tar_path = Path(image_tar).resolve()
        if not tar_path.is_file():
            raise PackageError("specified --with-sandbox-image-tar not found: %s" % tar_path)
        images_dir = sandbox_dst / "images"
        images_dir.mkdir(parents=True, exist_ok=True)
        dest_tar = images_dir / tar_path.name
        shutil.copy2(str(tar_path), str(dest_tar))
        tar_sha = sha256_file(dest_tar)
        manifest_payload = {
            "image": lock_data.get("image"),
            "file": tar_path.name,
            "sha256": tar_sha,
            "size": dest_tar.stat().st_size,
            "platform": lock_data.get("platform", "linux/amd64")
        }
        (images_dir / "manifest.json").write_text(
            json.dumps(manifest_payload, indent=2), encoding="utf-8"
        )
        console.log("[stage] sandbox offline image archive staged: %s (sha256=%s)" % (dest_tar.name, tar_sha))
    else:
        console.log("[stage] sandbox image-lock staged: %s" % lock_file)
    return lock_data


def render_install_md(stage, version, commit, built_at, gateway_port, frontend_port):
    template = Template((TEMPLATES_DIR / "INSTALL.md.tmpl").read_text(encoding="utf-8"))
    text = template.substitute(version=version, commit=commit, built_at=built_at,
                               gateway_port=gateway_port, frontend_port=frontend_port)
    (stage / "INSTALL.md").write_text(text, encoding="utf-8", newline="\n")


def write_version_file(stage, payload):
    with open(str(stage / "VERSION"), "w", encoding="utf-8", newline="\n") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")


def write_manifest(stage):
    lines = []
    for path in sorted(stage.rglob("*")):
        if path.is_file():
            relative = path.relative_to(stage).as_posix()
            lines.append("%s  %s" % (sha256_file(path), relative))
    (stage / "manifest.sha256").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    return len(lines)


def make_archive(stage, releases_dir, version, console):
    releases_dir.mkdir(parents=True, exist_ok=True)
    base = "%s-%s" % (PACKAGE_NAME, version)
    tar_path = releases_dir / ("%s.tar.gz" % base)
    zip_path = releases_dir / ("%s.zip" % base)
    for path in (tar_path, zip_path):
        if path.exists():
            path.unlink()

    def _filter(info):
        info.uid = info.gid = 0
        info.uname = info.gname = "root"
        if info.isdir():
            info.mode = 0o755
        elif info.name.endswith((".sh", ".py")) or info.name.endswith("/next"):
            info.mode = 0o755
        else:
            info.mode = 0o644
        return info

    with tarfile.open(str(tar_path), "w:gz", format=tarfile.PAX_FORMAT) as handle:
        handle.add(str(stage), arcname=base, filter=_filter)
    with zipfile.ZipFile(str(zip_path), "w", zipfile.ZIP_DEFLATED) as handle:
        for path in sorted(stage.rglob("*")):
            relative = path.relative_to(stage).as_posix()
            handle.write(str(path), "%s/%s" % (base, relative))

    sums = releases_dir / "SHA256SUMS"
    existing = []
    for path in sorted(releases_dir.glob("%s*.tar.gz" % PACKAGE_NAME)):
        existing.append("%s  %s" % (sha256_file(path), path.name))
    for path in sorted(releases_dir.glob("%s*.zip" % PACKAGE_NAME)):
        existing.append("%s  %s" % (sha256_file(path), path.name))
    sums.write_text("\n".join(existing) + "\n", encoding="utf-8", newline="\n")
    console.log("[archive] %s" % tar_path)
    console.log("[archive] %s" % zip_path)
    console.log("[archive] %s" % sums)
    return tar_path, zip_path


def do_assemble(args, console):
    repo_root = Path(args.repo_root).resolve()
    if not repo_root.is_dir():
        raise PackageError("repo root not found: %s" % repo_root)
    validate_ports(args.gateway_port, args.frontend_port)
    version = args.version or detect_version(repo_root)
    need_frontend = not args.skip_frontend
    preflight(repo_root, need_build=not args.skip_build, need_frontend=need_frontend)

    dirty = git(repo_root, "status", "--porcelain", "--untracked-files=no")
    if dirty and not args.allow_dirty_tree:
        raise PackageError("tracked files have uncommitted changes (use --allow-dirty-tree):\n" + dirty)
    commit = git(repo_root, "rev-parse", "HEAD")

    stage = Path(args.outputs_dir).resolve() / ("%s-%s" % (PACKAGE_NAME, version))
    if args.dry_run:
        console.log("[assemble] DRY-RUN target=%s version=%s commit=%s" % (stage, version, commit))
        return stage, version, commit

    if stage.exists():
        shutil.rmtree(str(stage))
    for path in ("app", "repo", "config", "data", "logs", "scripts", "service"):
        (stage / path).mkdir(parents=True, exist_ok=True)
    console.log("[assemble] staging %s" % stage)

    (stage / "app").mkdir(exist_ok=True)

    if args.skip_build:
        source_jar = (repo_root / "super-harness-server" / "target"
                      / (JAR_NAME_TEMPLATE % version))
        if not source_jar.is_file():
            raise PackageError("--skip-build requested but exec JAR is missing: %s" % source_jar)
    else:
        source_jar = build_jar(repo_root, version, console, args.skip_test_compile)
    shutil.copy2(str(source_jar), str(stage / "app" / source_jar.name))
    jar_sha = sha256_file(stage / "app" / source_jar.name)
    console.log("[stage] app/%s sha256=%s" % (source_jar.name, jar_sha))

    copy_frozen_resources(repo_root, stage, console)
    official_source = args.official_skills_dir or repo_root.joinpath(*OFFICIAL_SKILLS_RELATIVE)
    if args.exclude_official_skills is None:
        excluded = set(DEFAULT_EXCLUDED_OFFICIAL_SKILLS)
    else:
        excluded = {item.strip() for item in args.exclude_official_skills.split(",")
                    if item.strip()}
    official = copy_official_skills(repo_root, stage, official_source, excluded, console)
    if need_frontend:
        frontend_root = copy_frontend(repo_root, stage, args.with_node_modules, console)
        if args.skip_frontend_build:
            source_next = Path(repo_root) / "frontend" / ".next"
            if not (source_next / "BUILD_ID").is_file():
                raise PackageError("--skip-frontend-build requested but source .next/BUILD_ID "
                                   "is missing: %s" % source_next)
            shutil.copytree(str(source_next), str(frontend_root / ".next"), symlinks=False)
            console.log("[frontend] reused prebuilt .next from source checkout")
        else:
            pnpm = check_frontend_tooling(frontend_root, console)
            build_frontend(frontend_root, args.gateway_port, pnpm, args.force_frontend_install, console)
        next_cache = frontend_root / ".next" / "cache"
        if next_cache.is_dir():
            shutil.rmtree(str(next_cache), ignore_errors=True)
            console.log("[stage] dropped repo/frontend/.next/cache")
        if not args.with_node_modules:
            dropped = frontend_root / "node_modules"
            if dropped.is_dir():
                shutil.rmtree(str(dropped), ignore_errors=True)
                console.log("[stage] dropped repo/frontend/node_modules "
                            "(use --with-node-modules to bundle it)")
    copy_tooling(stage, console)
    sandbox_info = copy_sandbox(repo_root, stage, console, getattr(args, "with_sandbox_image_tar", None))

    built_at = datetime.now(timezone.utc).isoformat()
    write_version_file(stage, {
        "version": version, "commit": commit, "builtAt": built_at,
        "gatewayPort": args.gateway_port, "frontendPort": args.frontend_port,
        "jar": source_jar.name, "jarSha256": jar_sha,
        "frontendBundled": need_frontend, "nodeModulesBundled": bool(args.with_node_modules),
        "officialSkills": official["count"], "officialSkillsCommit": official["commit"],
        "officialSkillsExcluded": official["excluded"],
        "sandboxImage": sandbox_info.get("image"),
        "sandboxDigest": sandbox_info.get("digest"),
        "sandboxPolicyVersion": sandbox_info.get("policy_version"),
        "sandboxOfflineImageBundled": bool(getattr(args, "with_sandbox_image_tar", None)),
        "host": socket.gethostname(), "python": sys.version.split()[0],
    })
    render_install_md(stage, version, commit, built_at, args.gateway_port, args.frontend_port)
    count = write_manifest(stage)
    console.log("[assemble] OK stage=%s files=%d" % (stage, count))
    return stage, version, commit


def do_clean(args, console):
    version = args.version
    stage = Path(args.outputs_dir).resolve() / ("%s-%s" % (PACKAGE_NAME, version)) if version else None
    removed = []
    if stage and stage.exists():
        shutil.rmtree(str(stage))
        removed.append(str(stage))
    releases_dir = Path(args.releases_dir).resolve()
    for pattern in ("%s-%s.tar.gz" % (PACKAGE_NAME, version), "%s-%s.zip" % (PACKAGE_NAME, version)):
        path = releases_dir / pattern
        if path.exists():
            path.unlink()
            removed.append(str(path))
    if releases_dir.is_dir():
        sums = releases_dir / "SHA256SUMS"
        entries = []
        for pattern in ("%s*.tar.gz" % PACKAGE_NAME, "%s*.zip" % PACKAGE_NAME):
            for path in sorted(releases_dir.glob(pattern)):
                entries.append("%s  %s" % (sha256_file(path), path.name))
        if entries:
            sums.write_text("\n".join(entries) + "\n", encoding="utf-8", newline="\n")
        elif sums.exists():
            sums.unlink()
    console.log("[clean] removed: %s" % (", ".join(removed) if removed else "nothing"))
    return EXIT_OK


def build_parser():
    parser = argparse.ArgumentParser(prog=ENTRY_NAME, description="Haifa Super Harness release 打包工具")
    parser.add_argument("command", nargs="?", default="all",
                        choices=("assemble", "archive", "all", "clean"))
    parser.add_argument("--repo-root", default=None)
    parser.add_argument("--version", default=None, help="默认从 pom.xml 探测")
    parser.add_argument("--outputs-dir", default=str(DEFAULT_OUTPUTS_DIR))
    parser.add_argument("--releases-dir", default=str(DEFAULT_RELEASES_DIR))
    parser.add_argument("--gateway-port", type=int, default=DEFAULT_GATEWAY_PORT,
                        help="前端构建期固化的 Gateway 地址端口")
    parser.add_argument("--frontend-port", type=int, default=DEFAULT_FRONTEND_PORT)
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--skip-test-compile", action="store_true")
    parser.add_argument("--skip-frontend", action="store_true")
    parser.add_argument("--skip-frontend-build", action="store_true",
                        help="复用源检出的 frontend/.next，不执行 pnpm build")
    parser.add_argument("--official-skills-dir", default=None,
                        help="官方技能来源（默认 <repo-root>/deer-flow/skills/public）")
    parser.add_argument("--exclude-official-skills", default=None,
                        help="逗号分隔的官方技能名；默认排除 %s（传空串表示与 deer-flow 全量对齐）"
                             % ",".join(DEFAULT_EXCLUDED_OFFICIAL_SKILLS))
    parser.add_argument("--with-node-modules", action="store_true",
                        help="把 node_modules 一并打包（同平台离线安装用，体积大）")
    parser.add_argument("--with-sandbox-image-tar", default=None,
                        help="随包携带已导出的 Docker 沙箱镜像归档（离线包制作）")
    parser.add_argument("--force-frontend-install", action="store_true")
    parser.add_argument("--allow-dirty-tree", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--json", action="store_true")
    return parser


def main(argv=None):
    _reconfigure_stdio()
    argv = list(sys.argv[1:] if argv is None else argv)
    args = build_parser().parse_args(argv)
    if not args.repo_root:
        detected = find_repo_root(DIST_ROOT)
        if detected is None:
            print("[usage] cannot locate repo root; pass --repo-root", file=sys.stderr)
            return EXIT_USAGE
        args.repo_root = str(detected)

    logs_dir = Path(args.outputs_dir).resolve() / "logs"
    logs_dir.mkdir(parents=True, exist_ok=True)
    console = Console(logs_dir / ("package-release-%s.log"
                                  % datetime.now().strftime("%Y%m%d-%H%M%S")))
    try:
        if args.command == "clean":
            return do_clean(args, console)
        stage, version, commit = do_assemble(args, console)
        if args.dry_run:
            return EXIT_OK
        if args.command in ("archive", "all"):
            tar_path, zip_path = make_archive(stage, Path(args.releases_dir).resolve(), version, console)
            if args.json:
                print(json.dumps({"stage": str(stage), "version": version, "commit": commit,
                                  "tar": str(tar_path), "zip": str(zip_path)},
                                 ensure_ascii=False, indent=2))
        return EXIT_OK
    except PackageError as error:
        console.log("[main] failed: %s" % error)
        return EXIT_USAGE
    except KeyboardInterrupt:
        console.log("[main] interrupted")
        return EXIT_USAGE
    finally:
        console.close()


if __name__ == "__main__":
    sys.exit(main())

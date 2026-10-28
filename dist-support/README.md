# dist-support：发布包与安装基础设施

把 Super Harness 打成**单前缀、可外部下载**的发布包，并把它安装成 Linux systemd 服务或
Windows WinSW 后台服务。开发机上的部署工具在 `local-tmp/deploy/`（ssh + git bundle + 从源码构建），
本目录面向"给别人下载"的场景：包内带预构建产物，目标机不需要仓库、Maven 或 git。

## 目录

```text
dist-support/
  scripts/     package-release.py（打包）+ install-service.py（服务化/快速启动），各带 .ps1/.sh 薄封装
  templates/   gateway.env、systemd、winsw、quick-start/quick-stop、INSTALL.md 模板
  outputs/     中间半成品：outputs/super-harness-<version>/ 单前缀目录树（不入库）
  releases/    最终成品：super-harness-<version>.tar.gz / .zip 与 SHA256SUMS（不入库）
```

`outputs/` 与 `releases/` 已在根 `.gitignore` 忽略；`scripts/`、`templates/` 入库。

## 命令

核心逻辑全在 Python（标准库），`.ps1` / `.sh` 只定位解释器并透传参数与退出码。

```powershell
python dist-support/scripts/package-release.py all      # assemble + archive（默认命令即 all）
python dist-support/scripts/package-release.py assemble --skip-frontend   # 只打包 Gateway（更快）
python dist-support/scripts/package-release.py clean --version <version>

# 包内安装（在解压后的前缀目录里）
python scripts/install-service.py install --dry-run     # 只看将写入的服务单元
python scripts/install-service.py render                # 只渲染 config/ 下的服务定义预览
python scripts/install-service.py status --json
python scripts/install-service.py uninstall

# 包内快速启动（不注册服务，无需 root/管理员）
./quick-start.sh          # 或 Windows: .\quick-start.bat（等价 install-service.py start）
./quick-stop.sh
```

### package-release.py 要点

- 从 `--repo-root`（默认自动定位）构建 exec JAR：`mvnw -o -ntp -pl super-harness-server -am package
  -DskipTests`；`--skip-build` 复用已有 JAR。
- 前端有两种跳过方式：`--skip-frontend` **完全跳过前端**（不收集、不跑 pnpm，包里没有
  `repo/frontend`，安装时也要带 `--skip-frontend`）；`--skip-frontend-build` 收集前端源码但复用源检出的
  `frontend/.next`，不执行 `pnpm install/build`。日常只验证 Gateway 时用前者，最快。
- 收集 `third-party/deerflow` 冻结资源 + `frontend/`（源码与预构建 `.next`，默认**不**带
  `node_modules`）；`--with-node-modules` 才一并打包（同平台离线安装用，体积大）。
- 官方技能来自 `deer-flow/skills/public`（默认路径，可用 `--official-skills-dir` 覆盖），复制到
  包内 `skills/public/`；同时删除 `repo/third-party/deerflow/skills/<sha>` 下原有的 3 个冻结技能，
  并把 `repo/third-party/deerflow/skills/manifest.json` 重置为空 packages（保留文件本身供服务端校验）。
  打包前会核对 `deer-flow` 检出 HEAD 与冻结的 DeerFlow commit 一致。
- 默认**排除** `consulting-analysis`、`skill-creator`、`skill-reviewer`（它们在上游 public 目录中，
  但作为产品子集不随包发布）：`--exclude-official-skills "a,b"` 自定义，`--exclude-official-skills ""`
  表示与 `deer-flow` 全量对齐。排除项记入 `VERSION.officialSkillsExcluded`。
- 默认在暂存目录里跑 `pnpm install --offline --frozen-lockfile` + `pnpm build`（gateway 地址由
  `--gateway-port` 固化）；`--skip-frontend-build` 复用源检出的 `.next`。
- 生成 `VERSION`、`manifest.sha256`（逐文件 SHA-256）与 `INSTALL.md`，再输出 `.tar.gz` 与 `.zip`，
  并把两个归档的 SHA-256 写入 `releases/SHA256SUMS`。
- 已跟踪文件有未提交改动时默认拒绝，需 `--allow-dirty-tree`。

### install-service.py 要点

- **单前缀 + 可外置数据**：默认前缀是脚本上一层目录，`--data-root` 可指到包外单独备份。
- **配置**：从 `config/gateway.env.tmpl` 渲染 `config/gateway.env`（0600）；首次生成
  continuation key 后**永不轮换**，重复 install 保留已有 key 与 operator 额外键。同时写入
  `HAIFA_SUPER_HARNESS_SKILLS_DIR=<prefix>/skills`，让服务端加载 `skills/public` 官方技能。
- **定时任务调度**：模板默认写入 `HAIFA_SUPER_HARNESS_SCHEDULER_ENABLED=1`，启用单实例后台调度线程；
  缺省、空或 `0` 时保持关闭，已有定时任务不会自动运行（REST 手动触发仍可用）。轮询间隔等调优键为
  `HAIFA_SUPER_HARNESS_SCHEDULER_POLL_SECONDS`（默认 5）、`..._MIN_ONCE_DELAY_SECONDS`（默认 60）、
  `..._MAX_CONCURRENT_RUNS`（默认 3）、`..._QUEUE_TIMEOUT_SECONDS`（默认 3600）；修改后需重启 Gateway。
- **Linux**：渲染 `service/systemd/*.tmpl` 到 `/etc/systemd/system/`，`enable --now`；覆盖前把旧 unit
  备份到 `config/unit-backup/`。需要 root。前端 unit 只用 `After=`/`Wants=`，**不** `Requires=` gateway，
  这样后端单独更新（`systemctl stop super-harness-gateway`）不会连带停掉前端。
- **Windows**：需要 `--winsw-exe <WinSW.exe>`；按 WinSW 约定把 exe 复制为
  `service/winsw/<service-id>.exe` 并把 `<service-id>.xml` 放其旁，再从 `gateway.env` 展开 `<env>`。
- **uninstall** 只停止并删除服务定义，保留 `config/` `data/` `logs/`。
- **快速启动/停止**：`start`（`quick-start.bat/.sh`）不注册服务，直接后台启动 Gateway 与前端、
  把 PID 写入 `config/run.json` 并等待 `/health` 就绪；`stop`（`quick-stop.*`）只停止该文件记录的进程。
- 端口仅 loopback、禁 `80/443`、避开 `2026/3000/8001/8002`；`status` 校验 loopback 监听 +
  `/health` + `/api/v1/auth/me`。

## 发布包内布局

```text
super-harness-<version>/
  app/        预构建 exec JAR
  repo/       Gateway 工作目录：third-party/deerflow 冻结资源 + frontend/ 预构建产物
  skills/
    public/     官方技能（随包只读，来源 deer-flow/skills/public）
    community/  社区技能归档（可选；仅 .skill/.zip，不自动加载）
  config/     gateway.env.tmpl（安装时渲染为 gateway.env）
  data/       默认数据根（可用 --data-root 外置）；我的技能在 data/skills/custom/
  logs/       gateway.log / frontend.log
  scripts/    install-service.py（+ .ps1/.sh）
  service/    systemd/ 与 winsw/ 服务模板
  quick-start.bat / quick-start.sh / quick-stop.bat / quick-stop.sh
  VERSION / manifest.sha256 / INSTALL.md
```

技能分类（与 UI 一致）：

- **官方技能**：`skills/public/`，服务端按 `HAIFA_SUPER_HARNESS_SKILLS_DIR` 只读加载。
- **我的技能**：`<data-root>/skills/custom/`，由 UI 创建/导入，随数据根备份，不随包分发。
- **社区技能**：UI「社区」是导入入口；`skills/community/` 只存放可导入的归档，导入后进入「我的技能」。
  当前服务端只扫描一个外部根（`<root>/public`），因此社区技能不做自动加载。

## 已知边界

- 前端生产构建会把 Gateway 地址固化进 `.next`：发布包按打包时的 `--gateway-port` 构建，安装时改用
  其它 Gateway 端口需重新 `pnpm build`。
- 不带 `node_modules` 的包在目标机先执行 `pnpm install --frozen-lockfile`；`.next` 已预构建，无需再
  `pnpm build`。pnpm 默认在**项目所在磁盘**上选 store（例如项目在 `D:` 用 `D:\.pnpm-store`，在 `C:` 用
  `%LOCALAPPDATA%\pnpm\store`），因此 `--offline` 只在该盘的 store 已填充时可用；否则去掉 `--offline`
  （联网安装）或同平台交付时用 `--with-node-modules`。
- WinSW 包装器 exe 不随仓库入库，由使用方按 `INSTALL.md` 放入 `service/winsw/`。
- 打包不执行单元测试；发布前请按主控流程在源码侧完成门禁。

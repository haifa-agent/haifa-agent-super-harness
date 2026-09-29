# 公共规则

## 范围与依赖

- 根目录 `README.md` 统一使用中文维护；新增或修改构建、启动、验收说明时同步更新中文正文，命令、路径、配置键、API 名称和依赖坐标保留原样。
- 以 `private-docs/roadmap/35-super-harness-deerflow-parity.md` 及其产品、PRD 配套目录为范围与验收依据。
- DeerFlow 前后端固定为 `bytedance/deer-flow@e545c28ac301e6f9feae247c513d8ea8f83df294`，禁止随 `main` 漂移。
- 直接复用冻结的 DeerFlow 前端并由 Haifa 后端兼容其真实 REST/SSE/WS/文件/auth 协议，禁止自研平行 UI 或回落 DeerFlow 后端。
- 仅通过本地 Maven 仓库消费 Haifa Agent 公开 JAR/BOM `0.1.2-SNAPSHOT`（HA 0.1.2 开发线专门承接本项目 SDK 功能缺口，见 `private-docs/prompts/038-*`），禁止源码路径依赖、复制 SDK 或依赖产品 JAR。
- 如验收过程中确认必须完善上游 Haifa Agent SDK，只能在 `D:\workspace\haifa-agent` 仓库以 `dev`（HA `0.1.2-SNAPSHOT`，不设 baseline 分支）为起点，为每个功能创建独立 worktree 和新的 `feat-*` 开发分支；禁止直接修改或提交到 `dev`。完成后须将公开 JAR/BOM 发布到本地 Maven 仓库并由本项目继续按 `0.1.2-SNAPSHOT` 坐标消费，同时记录 SDK commit、发布与回归证据；随后从该 `feat-*` 分支向 `dev` 发起 GitHub PR，由主控在 SHPM 登记对应任务与 PR/验证状态（`shpm verify add`/`shpm issue add`），并明确提醒人工审查合并。任何智能体不得自行合并该 PR，除非人工针对该具体 PR 另行书面授权。
- 为同一功能引入的领域模型复杂度必须与冻结 DeerFlow 的对应实现保持平衡或更低，禁止为了协议兼容复制、叠加或扩张不必要的领域对象、状态机、持久化概念和抽象层。若有证据表明只有更高的领域复杂度才能实现验收范围，执行智能体必须在编码前提交包含 DeerFlow 对照、复杂度增量、不可替代原因、风险与简化备选的设计说明，先经主控 Agent 评审，再等待人工裁决，未获人工明确同意不得实施或合入。

## 开发、测试与证据

- 每个工作包使用独立 branch/worktree、端口和数据目录。
- 提交前须运行范围内定向测试、可执行的全仓门禁、`git diff --check`、secret scan 与真实 smoke，并保留真实 Run/Tool/Artifact 证据；失败项和未运行项分别记录为 `FAIL`/`NOT_RUN`，不得用静态验证替代运行证据，不得放宽断言或删除失败测试换取绿灯。
- Playwright 验收除功能断言外还必须报告流程健康度：cold/warm 启动与总耗时、是否重试、首个超时或卡点、Next 动态编译时间、trace/screenshot/日志位置，以及结束后端口和子进程是否清理；至少做一次同脚本重复运行以区分稳定问题与偶发问题。不得用无界等待掩盖启动或断言失败；等待必须有明确对象、超时和失败证据。
- 原始证据外置：运行任何 `mvn clean` 或重跑前，须把需保留的 Playwright trace、截图及前后端/Provider 原始日志复制到该工作包的外置独立证据目录（例如 worktree 的 `local-tmp/<slot>/evidence`，不能是另一个 `target`），并记录原始与复制路径。原始日志已消失时，只能明确标注为从先前终端输出重建的摘要，不得冒充原始证据。首次失败的证据同样保留，不得改记为 `NOT_RUN`。

## 环境与排障记录

软件安装、依赖准备、配置、凭据或权限问题实行“首次发现、立即沉淀、后续先查”：首次发现者须在不泄露密钥的前提下，把症状、根因、影响范围、精确检查命令、最小修复步骤和修复后验证写入本节对应主题，并在 checkpoint 中通知主控；后续智能体开工前必须先读取本节，禁止重复试错，禁止用复制 SDK/`node_modules`、全局安装、放宽系统权限或污染用户目录来绕过。本节只保留仍有效的结论，已解决的一次性历史不在此累积。

### Maven 与构建

- 根目录已提交 Maven Wrapper（`mvnw`/`mvnw.cmd` + `.mvn/wrapper/maven-wrapper.properties`，only-script 类型，固定 Maven 3.9.15 且带 `distributionSha256Sum`）。已有脚本与门禁直接调用系统 `mvn`/`mvn.cmd`（本机亦为 3.9.15），二者等价。wrapper 首次运行必须联网，把分发包校验哈希后解压到 `%USERPROFILE%\.m2\wrapper\dists\<distributionUrl 的 SHA-256>`，之后同一用户可离线复用（`-o` 不会阻止这次下载）。检查：`.\mvnw.cmd -v` 须输出 `Apache Maven 3.9.15` 且 Maven home 位于该 dists 目录（Git Bash 用 `./mvnw -v`）。校验值必须是小写十六进制（`mvnw.cmd` 用 `(Get-FileHash).Hash.ToLower()` 比较）；写错时退出码 1 并报 `Failed to validate Maven distribution SHA-256`，不会启动 Maven。`mvnw` 以 LF、`mvnw.cmd` 以 CRLF 提交，由根 `.gitattributes` 固定，避免 `core.autocrlf=true` 下 Git Bash 无法执行 `./mvnw`。
- PowerShell 中带逗号的 Maven `-Dtest=...,...` 参数必须整体加引号。
- 本项目模块间以 `0.1.0-SNAPSHOT` 互相依赖，本地仓库 `D:\DevCache\maven\repository\io\haifa\agent\superharness\` 下可能残留旧快照。症状：只用 `-pl super-harness-server` 而不加 `-am` 运行测试时，服务端线程抛出 `NoSuchMethodError`（例如 `WorkspaceChanges.sensitivePaths()`），相关测试挂起。根因：Maven 从本地仓库解析兄弟模块，而非使用当前 worktree 源码。定向测试一律使用 `mvn -o -ntp -pl super-harness-server -am "-Dtest=..." "-Dsurefire.failIfNoSpecifiedTests=false" test`，禁止为此执行 `install` 覆盖共享快照。
- Windows 上从模块 `target` 目录运行的主服务会锁定 `super-harness-server-*-exec.jar`，使 `mvn clean` 报 `Failed to delete`。主控执行 clean gate 前须先用 `Get-NetTCPConnection` 从主端口解析 PID，再用 `Get-CimInstance Win32_Process` 核对命令行确属当前 Super Harness JAR，停止该精确进程，完成构建后按原外置数据目录和配置重启；禁止按进程名批量结束 Java。
- 上游 `0.1.2-SNAPSHOT` 发布后若本项目编译仍提示新增公开方法不存在，或 Maven 读取 snapshot 报 `ZipException`/`EOFException`，不得改为源码依赖或复制 JAR。根因：Windows 本地仓库上并发 reactor `install` 会覆盖或短暂破坏同坐标 snapshot，单次成功日志不证明最终落盘内容。检查：先用 `Get-CimInstance Win32_Process` 查其他 Haifa Agent worktree 是否正在对同一坐标执行 Maven `install`，不得终止无关发布者，须等其退出并确认稳定停止；再用 `javap -classpath D:\DevCache\maven\repository\io\haifa\haifa-agent-store-sqlite\0.1.2-SNAPSHOT\haifa-agent-store-sqlite-0.1.2-SNAPSHOT.jar io.haifa.agent.store.sqlite.SqliteSdkContributions` 核对实际字节码。最小修复：发布者停止后，从已通过门禁的 SDK worktree 对该模块执行 Maven `install-file`，同时传入模块 `target` JAR 与 `.flattened-pom.xml`，随后记录 SDK commit 与 JAR SHA-256、再次 `javap` 并重跑本项目 clean gate；不得覆盖成未经验证的构建物。

- 2026-09-29 实测：本地仓库缺失 `maven-dependency-plugin:3.6.1`，但根 POM 管理的 `3.8.1` 可离线解析；独立小工程（不在根 reactor）的 `pom.xml` 应显式用 `3.8.1`，不要写 `3.6.1`。`org.apache.tomcat.embed:tomcat-embed-core`、`org.eclipse.jetty:jetty-server`/`jetty-ee10-servlet` 均不可离线解析。影响：MCP Java SDK 的官方 Server Streamable HTTP transport（`HttpServletStreamableServerTransportProvider`）依赖 servlet 容器，离线不可用。最小修复：本机测试 MCP fixture 的 Server 端改用 JDK `com.sun.net.httpserver` 实现最小 Streamable HTTP（`initialize`/`notifications/initialized`/`tools/list`/`tools/call`，协议 `2025-06-18`），一致性由官方 SDK 客户端（`McpClient` + `HttpClientStreamableHttpTransport`，`mcp-core:2.0.0`）真实连接证明；禁止联网下载或复制依赖。
- 2026-09-29 实测：MCP Java SDK `2.0.0` 中 `McpSchema.CallToolRequest` 的 `new CallToolRequest(String,Map)`、`CallToolRequest.builder()` 与 `JsonNode.fields()` 已过时；离线 SDK 客户端宜用 `new CallToolRequest(McpJsonMapper, name, argumentsJson)` 与 `JsonNode.properties()`，否则 `-Dmaven.compiler.showDeprecation=true` 报 warning。

- 2026-09-28 实测：主工作树 `super-harness-server/target/classes` 可能被外部构建（IDE 后台编译或他人在主树的运行）改写成不一致状态。症状：同一提交在干净 worktree `mvn -o -ntp clean verify` 通过，但主树 `mvn -o -ntp -pl super-harness-server -am package -DskipTests` 在测试编译阶段报大量 `cannot find symbol`（如 `List.getFirst()`、record 访问器），`target/classes` 的时间戳晚于主控最后一次构建。影响：主控在主树重新打包并重启主端口 Gateway 会失败，期间主服务已停。检查：`Get-ChildItem super-harness-server/target/classes/... | Select LastWriteTime` 对比主控构建时间，并用 `Get-CimInstance Win32_Process` 查主树相关 java/mvn 进程。最小修复：先用原 exec JAR 立即恢复主服务；再从已通过 `clean verify` 的同提交临时 worktree 取 `super-harness-server/target/*-exec.jar`，按端口核对 PID 停止主 Gateway、备份旧 JAR 后覆盖并以 `scripts/start-main-super-harness.ps1 -GatewayOnly` 重启；不得在主服务运行时对主树执行 `mvn clean`，不得用放宽编译或跳过测试编译掩盖。修复后验证 health、`/api/v1/auth/me`、前端与本次改动的 smoke。

### PowerShell 7

- 仓库 PowerShell 脚本依赖 PowerShell 7（`pwsh`，.NET Core），不能用 Windows PowerShell 5.1 代替；本机已安装 7.6.6（WindowsApps 下的 `pwsh.exe`，在 PATH 中）。5.1 下的典型症状：`scripts/start-haifa-frontend.ps1` 遇 `??` 报 ParserError；`scripts/verify-haifa-frontend-profile.ps1` 报 `[System.IO.Path]` 缺少 `IsPathFullyQualified`；`run-w04-frozen-ui-files.ps1` 的 `$PSScriptRoot` 默认参数为空，且 inventory digest 因 .NET Framework 区域排序而与 7.x 不一致（假阳性，不代表前端漂移）。因此 5.1 下的任何结果都不得记为 PASS/FAIL，只能记为 `NOT_RUN`；Maven `clean verify` 不受影响。检查：`Get-Command pwsh` 可解析，且 `pwsh -NoLogo -NoProfile -Command '$PSVersionTable.PSVersion.ToString()'` 返回 7.x；启动器可用 `pwsh -NoProfile -File scripts/start-haifa-frontend.ps1 -DryRun -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow` 验证，须输出 `frontendSourceGate=PASS`、`dryRun=true`。若新环境缺 `pwsh`，经人工同意后安装官方 PowerShell 7（例如 `winget install --id Microsoft.PowerShell --source winget`）；禁止把脚本降级改写成 5.1 语法，禁止跳过 profile 校验直接运行 `pnpm dev`。
- 2026-09-29 实测：脚本顶部开 `Set-StrictMode -Version Latest` 后，访问不存在的属性会抛 `PropertyNotFoundException`，**包括对空数组取 `.X`**（`@().X` 也抛）。取集合字段一律写 `@($arr | ForEach-Object { $_.X })`。另：`param([string]$Foo)` 会对变量 `$Foo` 加类型约束，后续把解析出的 `PSCustomObject` 赋给同名 `$Foo` 会被强制转成字符串并按路径误用；JSON 解析结果变量须另起名（如 `$ManifestPath` 参数 + `$manifest` 局部变量）。

### 冻结前端（pnpm / Next.js）

- 每个新 worktree 的 `frontend/node_modules` 不会自动继承，运行 Next/Playwright 前先检查 `frontend/node_modules/.bin/next.cmd`、`playwright.cmd` 和 Playwright browser。
- 系统 `pnpm --version` 可能与 `frontend/package.json` 固定的 `10.26.2` 不一致（本机实测 `10.26.1`），启动器会做 package-manager 精确版本检查，因此一律不用系统 `pnpm`。缺依赖时在该 worktree 的 `frontend/` 目录（否则会报根目录缺少 `package.json`）先执行 `$env:COREPACK_ENABLE_NETWORK='0'; corepack pnpm --version`，只有精确返回 `10.26.2` 才执行 `$env:COREPACK_ENABLE_NETWORK='0'; corepack pnpm install --offline --frozen-lockfile`。修复后须确认安装输出为 `downloaded 0`、`next.cmd` 与 `playwright.cmd` 存在，并再次验证版本。禁止联网下载、全局安装或复制其他 worktree 的依赖；离线依赖不齐则如实标记 UI `NOT_RUN` 并继续可执行的 HTTP/Java 门禁。
- Next.js dev 对同一 `frontend/.next` 目录实行单实例保护：主工作树前端已在 `33018` 运行时，从同一主工作树再启动任何 UI runner，frontend stderr 会报 `Another next dev server is already running`，随后 runner 报 `Frozen frontend failed readiness`，功能断言并未执行。检查：读取该次 `frontend-*.stderr.log`，再用 `Get-NetTCPConnection -State Listen -LocalPort 33018,<槽位 Frontend>` 与 `Get-CimInstance Win32_Process` 核对主进程。最小修复：在独立工作包 worktree 使用独立 `frontend/.next`、槽位端口和数据目录做 UI 回归；禁止为此停止供人工验收的主前端或共享/复制 `.next`、`node_modules`。未执行的 UI 断言标为 `NOT_RUN`。
- Next production build 会把 `frontend/next.config.js` 的 `DEER_FLOW_INTERNAL_GATEWAY_BASE_URL` 代理目标写入构建产物。若在未设置槽位环境变量时先执行 `corepack pnpm build`，再以 `run-w18-ui-smoke.ps1 -SkipBuild` 运行，前端仍代理默认 `127.0.0.1:8001`：Playwright 在首次 Run stream 前超时，页面提示 `Models couldn't be loaded`，`frontend-*.stderr.log` 记录 `/api/models` 代理 `8001` 并 `ECONNREFUSED`。检查：核对 runner `profile-*.json` 的 `internalGatewayBaseUrl` 与槽位，再查前端 stderr 的 `Failed to proxy` 目标，不能仅凭 profile 正确就认定构建产物正确。最小修复：不带 `-SkipBuild` 运行，让 runner 以 `DEER_FLOW_INTERNAL_GATEWAY_BASE_URL=http://127.0.0.1:<槽位 Gateway>` 重新构建；仅在已确认构建时使用同一槽位配置后才用 `-SkipBuild`。此坑只影响错误构建产物的 UI smoke，不代表 Java/HTTP 失败。
- Windows `core.autocrlf=true` 可能让新 worktree 的已跟踪 `frontend`、`third-party`、`contracts` 文本以 CRLF 落盘，导致冻结前端 license/lockfile/inventory digest 与固定 CSV fixture SHA-256 同时报错，虽 `git status` 仍 clean。先以 `git status --short` 确认这些路径无任何待保留改动，再用 `git hash-object <文件>` 对照工作树 `Get-FileHash -Algorithm SHA256 <文件>`；前者是 Git blob（LF），后者是磁盘字节。仅在**全新且 clean 的目标 worktree**，可用 `git archive --format=tar -o <该 worktree 内的临时 tar> HEAD frontend third-party contracts`、`tar -xf <临时 tar> -C <该 worktree 根>` 将已提交字节重新落盘；这是覆盖工作树文件的操作，dirty worktree 禁止执行。修复后重跑冻结来源/profile gate 与 CSV fixture 哈希，须与提交的固定值一致；不能放宽 hash、修改冻结资源、复制其它 worktree 的依赖或全局改 Git 配置。此为 checkout 换行问题，不代表源提交漂移。

### 端口

- Windows 的 Hyper-V/WSL/NAT（`winnat`）会动态排除 TCP 端口（本机动态范围从 `49152` 起，曾有排除段覆盖旧端口导致 Next.js `listen EACCES`）；现行子槽位均低于 `49152`，但排除范围仍可能变化。每次派工和启动前先执行 `netsh interface ipv4 show dynamicport tcp`、`netsh interface ipv4 show excludedportrange protocol=tcp`，并对分配的全部端口逐个用 loopback `TcpListener.Start()` 做实际绑定预检；`Get-NetTCPConnection` 只能发现监听者，不能排除 NAT 保留。预检失败时保留端口/进程/排除段证据，由主控重新裁决整组槽位或把 UI 标为 `NOT_RUN`；不得私自重启 NAT、提权、随意改单个脚本端口或停止主服务。修复后按同一完整端口组重复预检、服务就绪和 Playwright，并核实端口释放。

### 凭据与数据目录

- 持久 SQLite Runtime 的 model continuation 使用 AES-GCM 稳定密钥。Windows 默认使用按规范化绝对 data-root 的 SHA-256 前 16 个十六进制字符分槽的 Windows Credential Manager target：`haifa:os:HAIFA_SUPER_HARNESS_CONTINUATION_KEY_<data-root-hash>`；仅当该数据目录尚无 `runtime/haifa-agent.sqlite` 时首次启动可生成并写入，已有凭据不得覆盖；已有数据库但对应凭据丢失必须启动失败，禁止静默换钥。显式 `os://NAME` 属于外部托管引用，缺失时即使数据库尚不存在也必须失败。Linux/CI 必须通过 `HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF=env://NAME` 指向外部注入的 Base64 256-bit key。症状：启动前报 continuation key unavailable/invalid。检查：`Test-Path <data-root>/runtime/haifa-agent.sqlite` 确认数据库是否存在，`cmdkey /list` 只检查对应 target 是否存在（不得输出 secret），并查看 `server-*.stderr.log`。最小修复：恢复该 data-root 原 OS 凭据或原外部 secret 引用，不能删除数据库或生成替代 key；修复后以同一数据目录完成关闭重启并验证 transcript/history/runs 可读。
- 由上一条推出：不同启动方式不得共用同一 data-root。例如 W05 Playwright 的 Interaction 测试 classpath profile 与生产 JAR ActiveReconnect profile 混用时，后者会报 `continuation key credential is unavailable for the existing Runtime database`。从首次运行起就为每种 profile 分配独立的新 data-root 子目录。

### 冻结 DeerFlow 参考后端

- 冻结源码中已有的 `backend/.venv` 不是完整的 locked 安装：直接用其中 Python 启动 Uvicorn 时，导入 `app.gateway.app` 报 `ModuleNotFoundError: No module named 'deerflow_extension_api'`；且 `uv sync --offline --locked --dry-run` 显示需下载 223 个包，不能假定可离线重建。检查：在 `deer-flow/backend` 下执行 `.venv/Scripts/python.exe -c 'import deerflow_extension_api, deerflow, app.gateway.app'`。最小修复：在**进程环境**的 `PYTHONPATH` 中只读加入冻结源码的 `backend/packages/extension-api` 与 `backend/packages/harness`，用独立 `DEER_FLOW_HOME`、`DEER_FLOW_CONFIG_PATH`、`DEER_FLOW_EXTENSIONS_CONFIG_PATH` 启动，不改冻结源码或现有 venv。验证 `/health` 返回 `{"status":"healthy"}`，且退出后释放所属槽位端口；若缺少真正的第三方包，记录 `BLOCKED_EXTERNAL`，不得联网安装或复制其他 worktree 依赖。

### OpenCode CLI

- 本机 `opencode` 1.18.32 在独立 worktree 中默认拒绝读取 worktree 外的主目录 `AGENTS.md` 或 `private-docs/prompts/...`，非交互 `opencode run` 提示 `permission requested: external_directory ... auto-rejecting`；用 `--auto` 关闭审批不是修复。`--file PATH` 与后续位置消息同用时会把整段消息误当文件名并报 `File not found`。检查：`opencode --version`、`opencode run --help`、目标 worktree 的 `git status --short`。工作法：主控在启动命令所在的 PowerShell 中以 `Get-Content -Raw` 读取提示词及必要的冻结源码摘录，作为**一个位置参数字符串**交给 `opencode run --model deepseek/deepseek-flash`；OpenCode 只读取自身 worktree 的 `AGENTS.md` 和代码，不授权外部目录、不复制 SDK/依赖、不传任何密钥。启动后须从输出确认它已核对 branch/worktree、在预期目录内读取代码；无法安全获取必要上下文则标记阻塞并由主控改派。
- 2026-09-28 实测：OpenCode 在 worktree 内执行含 `cd ..`、`../` 或 `..\` 的 shell 命令（即使最终仍落在 worktree 内，例如 `sha256sum -c ../x.SHA256SUMS`）也会被判为访问上级目录，触发 `external_directory (<worktrees 父目录>\*)` 自动拒绝，非交互运行随即在未修改任何文件的情况下以 exit 0 结束。检查：在 `opencode-run.log` 中搜索 `permission requested`，并核对 `git log`/`checkpoints.md` 是否真有进展（exit 0 不代表完成）。最小修复：主控提供的上下文目录与 `SHA256SUMS` 一律使用**相对 worktree 根目录**的路径，派单提示词明确禁止 `..` 路径，然后原样重派；不得授权外部目录或用 `--auto` 绕过。PowerShell `Start-Job` 启动的 `opencode run` 会随工具调用的 host 退出而被终止，须改用后台任务直接运行。

# 主控 Agent 规则

## 授权与客户演示

- 2026-09-27 起，人工已对本项目后续开发、回归与客户演示所需的真实模型调用给出持续授权，不限制调用次数或预算，主控无需再逐次申请；该授权不等于任何演示阶段已执行或通过。主控仍须限定调用在当前任务范围内，使用已托管凭据引用，记录实际模型、Provider、时间、Usage、失败与重试，保留 W24 原始证据，并遵守密钥脱敏、客户数据隔离、无隐藏重试和阶段门禁规则。
- 客户演示按轮次推进，每轮的派工索引与阶段退出标准放在 `private-docs/prompts/39-deerflow-parity-delivery/show-roadmap-N/README.md`：
  - 客户演示 v0（用户亦称 V1，路线图 `private-docs/roadmap/36-super-harness-customer-demo-v0.md`，索引 `show-roadmap-1`）已于 2026-09-28 在 `main@c112012` 结算 M0–M4 `PASS`、人工验收通过、决策 `GO`。它只证明“真实模型 + 生产 `write_file` + Approval + Artifact + Reload/Reconnect + 同 Run Steer”在冻结 DeerFlow UI 上的本地纵向链，不等于 PRD D01–D05 的完整 Customer Demo Gate 或 Full Parity，也不关闭任何 W00–W24 工作包。后续若同一整链回归失败，须如实记录并重新评估该决策，不得保留过期 `GO`。
  - 客户演示 V2 以 `show-roadmap-2` 为派工总索引，未派工前全部阶段为 `NOT_RUN`、总体 `NO_GO`；实现任务仍落回 W00–W24，不创建平行工作包。
- 各轮通用门禁：主控把每个新任务映射到当前轮次的阶段、真实缺口和退出门槛，并在 SHPM 中登记（`shpm sprint link <SPRINT>/<阶段> --task <ID> --relation delivers --required`）。L1/L2 受控 Provider、测试 Tool、静态合同、组件测试、旧轮次证据或分开的单项 PASS，都不能替代同一主线 SHA 的连续两轮 L3 整链证据；任一产品修复合入后，后续差分和 L3 须基于新的主线 SHA 重新开始连续轮次。两轮不得隐匿失败或重试；真实 Artifact 必须有可信 ID/provenance 与可核验下载字节。阶段 `workStatus` 只表示执行状态，`result` 独立使用 PASS/FAIL/NOT_RUN 等 case 口径；已有分项证据不得提升阶段门禁。只有该轮全部阶段 PASS、同一主线 SHA、人工验收记录齐全，才可把演示决策改为 `GO`。W04 等已知范围外风险须披露，演示脚本实际触及其故障路径则如实 FAIL，不为追演示绿灯放宽门禁。
- 执行采用“双轨交付”：SHPM sprint 阶段（`shpm sprint show/gate <SPRINT> --json`）是客户演示阶段门禁，W00–W24 是不变的长期能力工作包台账。开发仍按工作包 task 提示词、独立 worktree、定向测试和主控验收交付；演示任务卡另外标注 `demoStages` 和该阶段原子断言。工作包切片/整包完成不自动使演示阶段 PASS，演示 `GO` 也不自动关闭工作包或 PRD D01–D05。W00 等长期横切工作只消费演示实际触及的来源，不因整包未完成而阻断正向演示。

## 派单与子智能体调度

- 主控每次开工、派单或向人工汇报前运行 `pwsh -NoProfile -File scripts/audit-work-package-delivery.ps1`，并同时运行 `shpm reconcile --json`（只报告不修改），检查演示阶段、工作包、worktree、checkpoint、端口和实际子智能体列表；二者都无法读取智能体状态，须另行人工核对。主控在每次派工、复审、合入、失败或门禁变更时同次核对 SHPM 记录与实际 worktree；失败史保留。
- 端口：主目录固定 Gateway `18018`、Frontend `33018`，供人工验收，不因子槽冲突迁移，不参与子槽公式。三个子智能体槽位：槽位 1 Gateway `20618`、Frontend `25618`；槽位 2 Gateway `30618`、Frontend `35618`；槽位 3 Gateway `40618`、Frontend `45618`（逐组 `+10000`，槽内 Frontend=Gateway+`5000`）。W18 差分 runner 的受控测试 Provider 独占 `46618`，不属于槽位，也需绑定预检。每次均须按公共规则预检。派单前须确认目标 worktree 的启动器与本端口表一致，否则从当前 main 新建独立 worktree，不得用旧默认端口运行。派单必须声明槽位、完整端口组和独立数据目录；同一时刻每槽仅一任务，完成后释放。主线合入后主控刷新或重启固定端口服务并做 smoke。
- 并发上限：每个需要启动服务的子智能体（含 OpenCode 进程）必须独占一个子槽位，因此同时运行的此类子智能体最多 3 个。
- 选择执行者：范围清楚、无需频繁与主控交互、可凭最终提交和独立门禁验收的任务优先交给 OpenCode；需要持续协商失败语义、架构或跨仓边界的任务优先交给可交互子智能体。不设 OpenCode 派单比例。派给 OpenCode 时必须在启动命令中显式指定 `--model deepseek/deepseek-flash`，不得依赖默认模型，checkpoint 与交付证据须记录实际模型。主控在 `shpm assign create` 中记录实际执行者（`--executor`/`--ref`）与模型（`--model`），分配理由写入首个 `shpm assign checkpoint`；工具选择不放宽设计提示词、代码评审、自动门禁、真实证据、密钥和外部目录边界。OpenCode 同样遵守独立 worktree/端口/数据目录与 15 分钟 checkpoint/失联恢复规则；无法安全取得上下文或执行门禁时由主控收回或改派，不算已完成派单。
- 多智能体执行采用“15 分钟无声上限”：每个运行中的子智能体至少每 15 分钟向主控回报一次 checkpoint，至少包含当前工作包、已完成证据、正在执行的命令或步骤、首个失败或阻塞、worktree 变更及下一交付点；长时间命令须在启动前说明目的，并在结束后立即回报。若距上次有效回报或可观察进展达到 15 分钟，主控必须发送一次非中断式点名消息，不得因智能体状态仍显示 `running` 而无限等待。
- 点名后 5 分钟仍无应答，或主控通过 worktree、测试进程、端口与日志审计确认任务停滞、跑偏、越界或反复等待无结果时，按“失联/失控 lane”恢复：先保留未提交变更、日志、数据目录和进程证据，只中止受影响智能体的当前回合，再用原 worktree 恢复同一任务或明确交接给替代智能体，并收紧范围、端口与下一 checkpoint；禁止打断正常推进的其他智能体。主控须用 `shpm assign lost` / `shpm assign recover --preserve-worktree` 和 `shpm assign checkpoint` 及时登记该 lane 的实际工作包映射、恢复动作、可验证进展和仍为 `NOT_RUN` 的项目，避免用旧智能体名称误报任务进度。

## 提示词与过程文档

- 每个 Super Harness 工作包任务交给子智能体开发前，主控必须先编写可执行的设计文档提示词，放在 `private-docs/prompts/39-deerflow-parity-delivery/` 下对应工作包目录，并以该文档作为派单依据；不得只靠聊天消息口头分派。提示词至少写明冻结 DeerFlow 对照、roadmap/产品/PRD 范围、现状与依赖、明确的实现边界和领域复杂度约束、接口/数据/失败语义、验收条件、定向及全仓门禁、真实运行证据与 `NOT_RUN` 边界。一个工作包需拆分时，每个可独立开发和验收的任务使用单独 Markdown 文件；派单消息引用具体文件，并补充本次 branch/worktree、端口槽位、数据目录与 checkpoint 要求。
- 不得在 `docs/development/` 新增方案、设计、review、裁决草案或开发过程复盘等过程文档；这类内容一律维护在 `private-docs/prompts/39-deerflow-parity-delivery/` 的对应工作包目录。`docs/development/` 只承载面向使用者的稳定开发、运行说明；既有过程文档归档在各工作包的 `references/` 子目录，属于历史证据而非任务提示词，不以文件名推断任务状态或占用任务名额。主控派单与评审时负责检查文档落点，子智能体不得自行在公开目录新增过程文档。
- 命名与路径冻结：提示词文件一旦登记进 SHPM（`prompt_path`），就不再改名或移动；已有带 `Pending`/`Developing`/`Finished` 后缀的目录和文件保持原名。新建任务文件用 `工作包-任务名.md` 命名（例如 `W05-Interaction.md`），不带状态后缀；新建工作包目录同样不带状态后缀，此后也不改名。任务名采用稳定、简短、无空格的英文标识。客户演示轮次索引目录 `show-roadmap-N` 沿用原名。任务状态只看 SHPM，目录名和文件名不表示状态。
- 登记：`shpm task create <Wxx-task-N> --wp <Wxx> --title .. --prompt <private-docs/...md 相对路径> --exit .. --priority P0|P1 --next ..`；没有 `--prompt` 的任务无法 start 或派工。SHPM 没有修改 `prompt_path` 的命令，`reconcile` 也不检查路径是否存在，因此必须遵守路径冻结。子智能体只报告状态建议与证据，不写 SHPM、不宣布验收完成。
- 子智能体的交付报告继续写到 `private-docs/ai-coding-report/...` 的指定路径；原始 trace、日志和截图继续放在外置证据目录。SHPM 只登记路径：报告放在 `shpm task deliver --handoff`，或放进 `shpm verify add --raw`/`--summary`。

## 项目管理（SHPM）

- 任务状态、派工、checkpoint、验收证据和演示门禁由 SHPM（Super Harness Project Manager）维护，只能通过 `shpm` 命令写入；人工通过 http://127.0.0.1:8787/ 只读查看。提示词、roadmap、交付报告和原始证据的正文仍在原位置（`private-docs`、`contracts`、worktree 的 `local-tmp` 等），SHPM 只保存路径。
- 开工自检：在 pwsh 中先执行 `$env:SHPM_DB='C:\Users\wangr\.shpm\data\project.db'; [Console]::OutputEncoding=[Text.Encoding]::UTF8; $env:PYTHONIOENCODING='utf-8'`（不设 `SHPM_DB` 时 PATH 中的 `shpm` 会在当前目录新建空的 `local-data/project.db`；不设编码时中文 JSON 乱码）。再运行 `shpm doctor --json`（`status` 应为 `HEALTHY`）与 `shpm status --json`；不符合时停下报告人工，不自行修复数据。
- 一律加 `--json`：成功输出到 stdout，失败 JSON 输出到 stderr。退出码：0 成功；2 校验失败或门禁未过；3 对象不存在；4 状态或资源冲突；5 环境漂移；10 内部错误（遇到时停止并报告）。所有写命令带 `--idempotency-key <日期>-<对象>-<动作>`，重试复用同一 key。PowerShell 会把中文弯引号当作字符串定界符，写入参数的文本改用「」。
- 导航：接手或恢复任务先运行 `shpm show <任务ID> --json`，`prompt_path`（相对仓库根）是派单和复审依据，`state`/`next_action`/`exit_criteria` 给出现状；派工信息看 `shpm assign list --task <ID> --json`；历史验收与原始证据路径看 `shpm verify list --task <ID> --json` 的 `raw_path`；演示范围看 `shpm sprint show <SPRINT> --json` 的 `roadmap_path`；工作包全貌看 `shpm wp show <Wxx> --json`（依赖在 `relations`，kind=requires）。登记路径不存在或与实际文件不符时停下报告人工，不得猜测。
- 派工流程：写好提示词 → `shpm task create` → 服务于演示阶段时 `shpm sprint link <SPRINT>/<阶段> --task <ID> --relation delivers --required` → `shpm task start <ID>` → 按端口规则预检 → `shpm assign create <ID> --id asg-<ID> --executor opencode|codex|main|human --ref .. --model .. --repo D:\workspace\haifa-agent-super-harness --base-sha <main SHA> --branch .. --worktree .. --slot N --ports <gw>/<fe> --data-root .. --checkpoint-in 15m`。派单消息写明“提示词见 <prompt_path>；SHPM 任务 <ID>，派工 asg-<ID>”。
- 过程中：每收到一次 checkpoint 或每 15 分钟执行 `shpm assign checkpoint asg-.. --completed .. --current .. --failure .. --next .. --command .. --next-in 15m`；失联用 `shpm assign lost` / `shpm assign recover --preserve-worktree`；阻塞用 `shpm task block --reason ..`，需长期挂账的用 `shpm issue add`。
- 交付与验收：子智能体不写 SHPM，由主控执行 `shpm task deliver <ID> --assignment asg-.. --commit <候选SHA> [--handoff <报告路径>]`，任务只进入 `ready_for_acceptance`。每一次门禁执行（含 FAIL 和 NOT_RUN）单独追加 `shpm verify add --task <ID> --key <check> --criteria .. --result PASS|FAIL|NOT_RUN|BLOCKED_EXTERNAL|UPSTREAM_UNSUPPORTED --required --command .. --repo-sha .. --raw <原始证据路径> --duration .. --retry-count N --summary ..`。独立复审并合入后执行 `shpm task accept <ID> --assignment asg-.. --main-commit <main SHA>`，再 `shpm assign release asg-..` 并确认端口已释放；验收失败执行 `shpm task reopen`。
- 工作包：状态用 `shpm wp state`；完成用 `shpm wp complete`，要求所有任务已完成且仍须满足整包退出条件和人工验收规则。`ready_for_acceptance` 表示该对象的退出条件、提交、自动测试和真实证据均已具备、等待复验或人工验收；无可验证代码或测试证据不得完成。
- 演示阶段：`shpm sprint stage result <SPRINT>/<阶段> <RESULT> --status <work_status> --reason ..`；里程碑用 `shpm sprint milestone <SPRINT> --title .. --detail ..`；`GO` 只能通过 `shpm sprint decide <SPRINT> GO --reason ..` 设置，门禁不过时以退出码 2 拒绝，不存在 `--force`。
- SHPM 已知缺口，靠自律遵守、不得利用：不检查不同槽位共用端口，派工前仍按端口表和绑定预检人工核对；不阻止阶段跳关，也不要求 PASS 前先有 Verification——前置阶段未 PASS 不推进后续阶段，标 PASS 前必须先有对应 `verify add` 原始证据；无修改 `prompt_path` 的命令。`shpm task reopen` 当前仅接受 `finished`，对复审失败的 `ready_for_acceptance` 返回 `STATE_CONFLICT`；此时保留必需验证 FAIL 和阻断 issue，不得先虚假 accept 再 reopen，须停下该任务并报告人工决定合法状态恢复方式。禁止对线上库执行 `shpm migrate *`（apply 为 upsert，会用旧文件覆盖状态）；禁止直接写 SQL 或改 SHPM 代码/数据文件，命令表达不了的操作停下报告人工。
- 门禁：原 `work-packages.js`、`docs/todo.md`、`dashboard.html` 与 `work-package-dag.html` 已于 2026-09-28 删除，不得重建或手工维护平行进度文件，也不再使用 `shpm render`。证据门禁 `scripts/generate-evidence-report.ps1` 与审计 `scripts/audit-work-package-delivery.ps1` 通过 `shpm export snapshot` 读取 SHPM（共享规则在 `scripts/lib/shpm-governance.ps1`），显式传 `--db`，数据库缺失时 fail closed。任务、派工、阶段或工作包状态变更后运行 `pwsh -NoProfile -File tests/evidence/verify-evidence-report.ps1`；证据门禁失败时不得播报 PASS 或更新 `GO`。人工只读视图为 SHPM 网页 http://127.0.0.1:8787/。

# 子智能体规则

- 接单后先完整阅读本文件、工作包对应 roadmap/产品/PRD、冻结 DeerFlow 源码与已有证据；首个 checkpoint 必须复述实际工作包、branch/worktree、槽位端口、数据目录、范围边界和下一交付点。子智能体不得执行任何 `shpm` 写命令，不得重建已删除的进度文件，也不得改动主目录服务。
- 只能使用主控分配的 worktree、数据目录和端口组。启动服务前须检查已分配端口；若被占用，可以查明 PID 并核对命令行后直接终止占用该组端口的进程，但不得终止主端口、其他槽位、未分配端口或仅按进程名批量结束进程。任务结束时须清理自己启动的服务并验证端口释放。
- 按主控规则的“15 分钟无声上限”回报 checkpoint；收到主控非中断式点名后，应在当前安全命令边界立即应答，不得为回报而破坏正在运行的测试或写入。
- 发现软件安装、依赖、配置、凭据或权限新坑位时，先保存可复现证据，再按“环境与排障记录”更新本文件；涉及密钥只能记录凭据引用、存在性和脱敏结果，禁止输出或提交密钥值。若问题需要全局安装、联网取依赖、提升权限或改变用户/系统配置，先停止扩张并报告主控，不得自行绕过。
- 编码前按公共规则对照冻结实现评估领域复杂度，需要更高复杂度时停在编码前提交设计说明；提交前按公共规则运行门禁并记录 `FAIL`/`NOT_RUN`。
- 交付 checkpoint 必须包含分支、最终 commit SHA、变更文件、测试/Playwright 次数与耗时、真实 Run/Tool/Artifact 或 HTTP/SSE 证据、已释放端口、worktree clean 状态、明确 `NOT_RUN` 清单以及建议的下一切片；提交后不得 amend/rebase 已交给主控评审的 SHA，除非主控明确要求。

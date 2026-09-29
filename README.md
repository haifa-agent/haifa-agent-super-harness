# Haifa Super Harness

本仓库是冻结 DeerFlow 对齐项目的独立 Java 21 产品工程。项目只通过 Maven 仓库中已发布的 `io.haifa:haifa-agent-bom:0.1.2-SNAPSHOT` 及公开 JAR 消费 Haifa Agent，不依赖 Haifa Agent 源码目录。

## 客户演示与项目看板

项目的任务状态、派工、checkpoint、验收证据和客户演示门禁由 SHPM（Super Harness Project Manager）维护；人工可在 http://127.0.0.1:8787/ 只读查看。只有主控智能体通过 `shpm` 命令写入，子智能体不写 SHPM。提示词、roadmap、交付报告和原始证据的正文仍在 `private-docs`、`contracts` 与各 worktree 的 `local-tmp` 等原位置，SHPM 只保存路径。

原 `work-packages.js`、`docs/todo.md`、`dashboard.html` 与 `work-package-dag.html` 已删除，进度只看 SHPM。W24 证据门禁与主控派工审计直接读取 SHPM 快照（数据库缺失时 fail closed，不会新建空库）：

```powershell
$env:SHPM_DB='C:\Users\wangr\.shpm\data\project.db'
pwsh -NoProfile -File scripts/audit-work-package-delivery.ps1
pwsh -NoProfile -File tests/evidence/verify-evidence-report.ps1
```

客户演示按轮次推进：客户演示 v0（亦称 V1，[路线图](private-docs/roadmap/36-super-harness-customer-demo-v0.md)）已在 `main@c112012` 结算 `GO`；客户演示 V2 的范围与阶段退出标准见 [show-roadmap-2](private-docs/prompts/39-deerflow-parity-delivery/show-roadmap-2/README.md)，当前状态以 `shpm sprint gate DEMO-V2 --json` 为准。演示阶段通过不会自动关闭工作包，也不代表 PRD D01–D05 完整 Customer Demo Gate 或 Full Parity。

## W01 基础工程

- `super-harness-app` 验证真实的公开 SDK `Run → Java 类型化 Tool → Artifact` 链路：确定性 Tool 生成内容，应用再通过公开 Artifact 服务发布，保留真实 Run/Session 来源并校验存储内容的哈希。
- `super-harness-harness` 是最小化、失败即关闭的证据校验器。必需用例缺失、显式失败、重复，或标为 PASS 却没有证据时，校验均失败。
- Maven Enforcer 拒绝直接或传递依赖 Haifa 产品、演示、测试 JAR，并拒绝直接依赖实现模块；ArchUnit 测试禁止生产代码导入 SDK 内部包和实现包。

## 构建与验证

使用 Java 21 和 Maven 3.9.15，或使用仓库提交的 Maven Wrapper。Wrapper 的 Maven 3.9.15 分发包固定了 SHA-256 校验值。

```powershell
.\mvnw.cmd -o -ntp verify   # Windows；Linux/macOS 使用 ./mvnw
mvn -o -ntp verify
./build-support/verify-clean-consumer.ps1
```

前两条 `verify` 命令使用配置好的本地 Maven 仓库，验证同一套完全离线的构建。每台机器首次使用 Wrapper 时需要联网下载一次 Maven 分发包，写入 `%USERPROFILE%\.m2\wrapper\dists`；此后可从缓存离线运行。`verify-clean-consumer.ps1` 从空的临时依赖缓存开始，通过配置好的 Maven 仓库只读文件镜像解析依赖，以证明构建没有使用 `D:\workspace\haifa-agent` 或任何 Reactor/源码路径依赖。

冒烟测试会输出 `W01_SDK_EVIDENCE`，其中包含真实 Run ID、Tool 别名、Artifact ID、Artifact SHA-256 和字节数。测试报告位于各模块的 `target/surefire-reports`。

## W18 冻结前端本地配置

以下命令只校验冻结前端、已记录的最小 BFF 补丁、仅连接 Haifa 的 loopback 配置，以及固定的 Node/pnpm 启动工具链，不启动服务：

```powershell
pwsh -NoProfile -File scripts/start-haifa-frontend.ps1 `
  -DryRun `
  -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow
pwsh -NoProfile -File tests/frontend/verify-haifa-frontend-profile.ps1
```

受保护的主工作树拓扑与正式启动流程见下文“启动主工作树供人工验收”；历史 W18 配置设计与验收记录已归档至 `private-docs/prompts/39-deerflow-parity-delivery/W18-Developing/references/`。仅通过上述配置检查，不代表聊天功能或双后端差分验收通过。

## W05 客户演示 M2 Artifact UI 验收

W05 的受控 L2 验收使用子槽位 1（Gateway `20618`、Frontend `25618`）、生产 Super Harness JAR、冻结 DeerFlow UI 和本地受控 Provider，验证 `write_file → Approval → Artifact → Reload/Reconnect → UI 下载`。它不会调用真实模型；真实模型整链属于后续 M4。

首次运行会执行 Maven package 和按槽位代理地址构建冻结前端：

```powershell
pwsh -NoProfile -File scripts/run-w05-demo-artifact-ui.ps1
```

仅当 Java exec JAR 已在本次源码变更后重新生成、且 `.next` 已用同一槽位 Gateway 地址构建时，才可复用构建产物：

```powershell
pwsh -NoProfile -File scripts/run-w05-demo-artifact-ui.ps1 -SkipBuild
```

脚本固定 `retries=0`，每次生成独立 Thread 和文件，并把 trace、截图、前后端/Provider 日志及 Artifact 字节/SHA 证据写入 `local-tmp/slot-1-w05-m2-evidence/playwright/run-<时间戳>/`。正式验收须连续运行两次且都通过；第一次数据不得清理，结束后还须确认 `20618`、`25618` 和子进程均已释放。

## W03 后续建议配置

Gateway 通过 `GET /api/suggestions/config` 暴露冻结前端的后续建议全局配置。以下两个环境变量在进程启动时读取一次，均为可选：

- `HAIFA_SUPER_HARNESS_SUGGESTIONS_ENABLED`：是否启用后续建议，默认启用。设为 `0`、`false` 或 `no` 时关闭；关闭状态下 `POST /api/threads/{thread_id}/suggestions` 不调用 Provider，直接返回空 `suggestions` 列表。
- `HAIFA_SUPER_HARNESS_SUGGESTIONS_MAX`：`max_suggestions` 上限，默认 `3`；非法值回落为默认值，取值被限制在 1 到 5 之间，并按该上限截断请求中的 `n`。

线程标题由 Gateway 在首个完成的 user+assistant 交换后按冻结 `TitleMiddleware` 的本地回退规则生成（首轮被取消时允许仅凭首个 user 消息生成），持久化到线程 metadata 与 `values.title`；显式重命名在 Run 运行期间返回 409，且不会被后续自动标题覆盖。

同一 Conversation 可在前一个 Run 终止后的下一 Turn 选择另一模型；当前 Run 的模型保持冻结，历史中的 Tool 与待审批 Interaction 仍归原 Run。可在独立槽位 1 用冻结 UI 和两个本地受控模型复验（不调用真实模型）：

```powershell
pwsh -NoProfile -File scripts/run-w18-ui-smoke.ps1 -AgentSlot 1 -GatewayPort 20618 -FrontendPort 25618 -V2ModelSwitch -DataDirectory local-tmp/slot-1-w03-v2-model-switch/data/ui-round-1
pwsh -NoProfile -File scripts/run-w18-ui-smoke.ps1 -AgentSlot 1 -GatewayPort 20618 -FrontendPort 25618 -V2ModelSwitch -DataDirectory local-tmp/slot-1-w03-v2-model-switch/data/ui-round-2 -SkipBuild
```

第二轮的 `-SkipBuild` 只能用于首轮已按同一 Gateway 代理目标构建的前端；每轮使用独立数据目录，trace、截图及前后端/Provider 日志写入 `local-tmp/slot-1-w03-v2-model-switch/evidence/ui/run-<时间戳>/`。该受控 UI 回归不代替 W18 双后端差分、真实模型链或 V2 阶段门禁。

## W18 V2-M2 MCP 双后端差分

`scripts/run-w18-v2-m2-mcp-rounds.ps1` 在 W18 独立 worktree 中按 reference 冷/暖、Haifa 冷/暖顺序运行同一套冻结 UI MCP 用例；每轮由 `scripts/run-w18-v2-m2-mcp.ps1` 管理独立 Gateway、Frontend、受控 Provider 和两个真实 Streamable HTTP MCP Server。槽位固定为 `20618/25618`，测试 MCP 使用 `20628/20629`，负例无监听端口为 `20630`，Provider 使用 `46618`。脚本会对整组端口查询 NAT 排除范围并实际绑定预检，保存每轮原始日志、trace、截图、Run 与配置证据，结束时只清理自己启动的进程。失败轮次保留 `FAIL`，不会暗中重试；MCP 切片通过也不自动提升 V2-M2 阶段。

本切片的负例只在 W18 runner 中启用：区域 fixture 检查测试专用 `Authorization` 值，缺失或错误时返回真实 HTTP 401；报告 fixture 可在完成一次 `get_status` 调用后、发送响应头前断开连接。测试读取 fixture 的拒绝和调用计数，以核对失效凭据及“结果未知”时没有自动重放。普通 `scripts/start-v2-test-mcp-servers.ps1` 启动不启用这些控制点。

运行前须在该 worktree 以离线 Maven 门禁生成 `super-harness-server/target/*-exec.jar`，并在 `frontend/` 用精确版本 Corepack `pnpm 10.26.2` 离线安装依赖。只有空闲物理内存超过 4 GB、其他 Maven 构建或发布已停止时才运行重型门禁和四轮脚本。完整命令和证据目录由 W18 任务报告记录。
## W15 V2 最小 Skill 承载

Gateway 从 `third-party/deerflow/skills/manifest.json` 读取受信、不可变的 Skill 版本目录，核对整包文件及来源许可哈希后，交由本地发布的 Haifa Agent `0.1.2-SNAPSHOT` 公开 Skill API 执行发现、激活与 Run Context 注入。当前生产 manifest 只登记冻结 DeerFlow `consulting-analysis`；`contracts/customer-demo-v2/fixtures/skills/v2-format-control/` 只供独立测试数据目录使用，不会在生产配置中启用。旧版本目录须随发行物保留，以便重启后的旧 Run 校验原 binding；不可原位覆盖。

冻结前端使用 `GET /api/skills`、`GET/PUT /api/skills/{name}` 与 `POST /api/skills/reload`。启停状态按数据目录原子保存到 `skills_config.json`，只影响后续新 Run；`Agent.skills` 的 `null` 表示全部已启用 Skill，`[]` 表示不使用 Skill，名称列表为允许名单。启动 Gateway 时工作目录须为仓库根；模块测试可从 `super-harness-server/` 运行。缺包、哈希/许可漂移或无法读取 manifest 会拒绝接入，不会回落到 DeerFlow 后端。完整双 Skill HTTP/Run 断言与冻结 UI 两轮仍以 V2 W15/W18 验收记录为准。

W15 冻结 UI 回归从独立槽位运行，先在该 worktree 的 `frontend/` 用 `COREPACK_ENABLE_NETWORK=0` 执行 `corepack pnpm install --offline --frozen-lockfile`，确认版本为 `10.26.2` 且 `downloaded 0`，再在仓库根执行同一脚本两轮：

```powershell
pwsh -NoProfile -File scripts/run-w18-ui-smoke.ps1 -AgentSlot 2 -GatewayPort 30618 -FrontendPort 35618 -V2Skill -DataDirectory local-tmp/slot-2-w15-v2-skill/data/ui-round-1
pwsh -NoProfile -File scripts/run-w18-ui-smoke.ps1 -AgentSlot 2 -GatewayPort 30618 -FrontendPort 35618 -V2Skill -DataDirectory local-tmp/slot-2-w15-v2-skill/data/ui-round-2 -SkipBuild
```

第二轮 `-SkipBuild` 仅在首轮已按 Gateway `30618` 构建成功后使用。每轮脚本在结束时清理槽位进程，Playwright trace、截图及前后端/受控 Provider 日志写到 `local-tmp/slot-2-w15-v2-skill/evidence/ui/run-<时间戳>/`；测试需检查 `skill_load` ToolCall、启用后的模型 Context 和禁用后新 Run 的隔离。完整验收仍以开发报告的实际运行结果为准。

## 启动主工作树供人工验收

主工作树固定使用本地回环端口：Gateway `18018`、冻结前端 `33018`。重启时应保持数据目录不变；默认使用被 Git 忽略的 `local-tmp/main-service-data`，其中包含持久化 SQLite Runtime 和日志。重启时不要删除该目录或 Windows 凭据管理器中的 continuation key。

准备好本地 Haifa Agent `0.1.2-SNAPSHOT` 公开 JAR/BOM 和冻结前端依赖后，如果端口已占用，应先核对进程身份，只停止确认属于主工作树的进程，再构建并启动：

```powershell
mvn -o -ntp clean verify
pwsh -NoProfile -File scripts/start-main-super-harness.ps1
```

启动脚本会校验冻结 DeerFlow 源码和前端工具链的精确版本；在隐藏窗口中启动 Haifa Gateway 和现有的受保护前端启动器；在有界超时内等待 `/health`、`/api/v1/auth/me` 与前端就绪；最后输出访问地址、精确 PID、数据目录和日志路径。人工验收入口为 `http://127.0.0.1:33018/`。遇到端口占用时，脚本会拒绝启动，不会自行结束未知进程。若尚未运行完整验证，可传入 `-Build`，由脚本执行离线 Maven Reactor `package` 门禁。脚本退出后，服务仍会继续运行。

只需重建或重启 Gateway、而受保护的前端仍在运行时，先核对监听 `18018` 的 Java 进程并只停止该 PID，再运行相关 Maven 测试和 `mvn -o -ntp -pl super-harness-server -am package`，随后执行：

```powershell
pwsh -NoProfile -File scripts/start-main-super-harness.ps1 -GatewayOnly
```

此模式只检查并启动 Gateway，沿用原数据目录，不会启动第二个前端。确认 Gateway 健康后刷新浏览器。

代码合入后重启前，先用 `Get-NetTCPConnection -State Listen -LocalPort 18018,33018` 查明监听 PID，再用 `Get-CimInstance Win32_Process` 核对精确命令行；只停止确认属于主工作树的进程，然后重跑上述命令。禁止按进程名批量停止所有工作树中的 Java 或 Node。页面能打开仅证明服务就绪；真实模型、Tool 和双后端对齐仍需分别提供 Run/Playwright 证据。

### 客户演示 v0 doctor

客户演示固定 Oracle 位于 `contracts/customer-demo/customer-demo-v0-oracle.json`。在启动或派发演示任务前，先运行只读 doctor；它会核对冻结 DeerFlow 来源、Haifa Agent `0.1.2-SNAPSHOT` 本地 Maven 构件、主工作树端口及进程、同源模型目录、OS 凭据存在性和外置数据目录，但不会调用真实模型或读取凭据值：

```powershell
pwsh -NoProfile -File scripts/verify-customer-demo-doctor.ps1 `
  -OutputPath local-tmp/customer-demo-m0/doctor-live-read-only.json
```

只检查 Oracle、冻结来源和 SDK 构件时加 `-StaticOnly`。未获人工真实模型预算授权时，doctor 会将 `budget-authorization` 和整体结果保留为 `NOT_RUN`；不得仅为获得 `PASS` 擅自添加 `-BudgetApproved`，该开关只记录已经取得的明确人工授权，本身不会发起模型请求。脚本的正反合同测试为：

```powershell
pwsh -NoProfile -File tests/customer-demo/verify-customer-demo-doctor.ps1
```

### 客户演示 v0 证据包与 fail-closed 门禁

客户演示两轮 Run 的原始证据由 `contracts/customer-demo/customer-demo-v0-evidence-bundle.json` 聚合，字段与字段约束见 `contracts/customer-demo/schemas/customer-demo-v0-run-manifest.schema.json` 和 `contracts/customer-demo/schemas/customer-demo-v0-evidence-bundle.schema.json`。它绑定 M0 Oracle 与其 SHA-256，记录主线/前端/SDK/config/input 哈希、模型/Provider（不含 secret）、预算、两轮的 Thread/Run/ToolCall/Interaction/Artifact ID、审批前后状态、Steer、网络目标、文件与下载 SHA、Usage、时间线、Playwright/日志路径、进程/端口清理，以及逐阶段原始证据引用和人工验收。每一轮 PASS 还必须提供仓库内真实下载字节文件（`downloadEvidence` 的 repo path + SHA，校验实际文件存在且实际 SHA 与字节长度分别等于声明的 `downloadSha256`/`downloadByteCount`）和 Artifact provenance 原件引用（`artifactProvenanceEvidence` 的 repo path + SHA）。为保证生产 `write_file` 生成的文件与 Artifact 下载字节一致，该轮 `fileSha256` 必须同时等于 `downloadSha256` 与该下载文件的实际 SHA；provenance 原件 JSON 除 `artifactId`/`runId`/`toolCallId` 外，还必须绑定 Oracle `story.tool`（`write_file`）、Oracle `story.artifact`（`sdk-owned-real-artifact`）、`downloadSha256` 与 `downloadByteCount`，校验器用传入的 OraclePlan 对照这些字段，只填字符串不再算 PASS。

以下命令只做只读校验与聚合，输出面向主控的机器 JSON 与简明汇总，不会启动服务、不会调用任何真实或付费模型，也不会写入 SHPM 或自动宣布阶段完成：

```powershell
pwsh -NoProfile -File scripts/verify-customer-demo-evidence.ps1 `
  -OutputDirectory target/customer-demo-evidence
pwsh -NoProfile -File tests/customer-demo/verify-customer-demo-evidence.ps1
```

校验会拒绝缺轮次、重复 ID、两轮 SHA 或配置不一致、隐藏 retry、缺失首次失败、只报最佳轮、Artifact 无 provenance/下载 hash、缺失或漂移的真实下载字节（文件不存在、SHA 不符、字节数不符）、`fileSha256` 与 `downloadSha256` 或真实下载字节不一致、provenance 原件未绑定 Oracle tool/artifact 或 `downloadSha256`/`downloadByteCount`、provenance 原件 SHA 漂移、无参考回落证据、跳阶段、M4 无人工验收、自报 GO、GO 与 M4 SHA 不一致以及敏感值；结果严格区分 `PASS`/`FAIL`/`NOT_RUN`/`BLOCKED_EXTERNAL`/`UPSTREAM_UNSUPPORTED`：任一 stage 或 round `FAIL` 都会使 `customerDemo.status` 与 `overallStatus` 变为 `FAIL`，`BLOCKED_EXTERNAL`/`UPSTREAM_UNSUPPORTED` 在机器摘要中单独列出，绝不折叠成 `NOT_RUN`。当前 bundle 为全阶段 `NOT_RUN`、演示决策 `NO_GO`，只有当 M0–M4 全部 PASS、两轮在同一主线 SHA、同一模型/配置/数据根上连续完成且有人工验收时才会接受 `GO`；可用 `-RequireGo` 把“尚未 GO”升级为失败，供 M4 最终门禁使用。

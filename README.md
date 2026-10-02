# Haifa Super Harness

本仓库是冻结 DeerFlow 对齐项目的独立 Java 21 产品工程。项目只通过 Maven 仓库中已发布的 `io.haifa:haifa-agent-bom:0.1.2-SNAPSHOT` 及公开 JAR 消费 Haifa Agent，不依赖 Haifa Agent 源码目录。

## 架构边界

Super Harness 是产品、兼容与集成层，不是第二套 Agent Runtime。可复用的 Runtime 能力（Run/Loop、Memory、Tool/Skill、MCP、Sandbox、Approval、Artifact、Checkpoint、Parent/Child 等）归 Haifa Agent；Super Harness 负责冻结 DeerFlow 协议映射、产品语义、UI/BFF、业务集成与控制面展示。新增基础能力前须按 [Runtime / Product Boundary](docs/runtime-product-boundary.md) 做归属判断；如果缺的是通用 Runtime 能力，应先补 Haifa Agent 公开 API/SPI，再由本项目消费。

## 客户演示与项目看板

项目的任务状态、派工、checkpoint、验收证据和客户演示门禁由 SHPM（Super Harness Project Manager）维护；人工可在 http://127.0.0.1:8787/ 只读查看。只有主控智能体通过 `shpm` 命令写入，子智能体不写 SHPM。提示词、roadmap、交付报告和原始证据的正文仍在 `private-docs`、`contracts` 与各 worktree 的 `local-tmp` 等原位置，SHPM 只保存路径。

原 `work-packages.js`、`docs/todo.md`、`dashboard.html` 与 `work-package-dag.html` 已删除，进度只看 SHPM。W00–W24 仍是必需的产品工作包；2026-10-02 人工批准另建 `ENG` 横切工程工作包，不能用它替代任何产品工作包。W24 证据门禁与主控派工审计直接读取 SHPM 快照（数据库缺失时 fail closed，不会新建空库）：

`requires` 支持工作包前置依赖和阶段到既有任务或工作包的依赖。工作包仍检查存在性、自引用、重复和环；阶段依赖检查合法来源与目标。阶段 `PASS` 时，所有 `required` 的 `requires` 和 `delivers` 任务必须已由主控验收为 `finished`，合法关系不自动提升阶段结果。

聚合器校验 `IMPORTED` 资源时优先使用已声明的 `local_sha256`，缺失时才回退原 `sha256`；本地哈希无效或内容不符会失败。来源文件的 SHA 和完整冻结来源仍由 `verify-deerflow-contracts.ps1` 校验。在没有 `private-docs` 的独立 worktree 运行 verifier 单元脚本时，用 `-RepositoryRoot D:\workspace\haifa-agent-super-harness` 提供真实 prompt fixture 输入；被测工具仍来自该脚本自身的 checkout，不能用另一分支脚本替代。

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

### 定向测试与 SDK 版本选择

新 Java 验收测试从 `ProductionWriteFileHttpTest.productionApprovalGateRejectsMismatchedCrossThreadDuplicateAndRejectedWrites` 起步：真实 SDK 请求 `write_file`、审批当前 Run、执行生产工具、下载 Artifact 并核验字节和重启后的哈希。`ControlledModel`/`ControlledProvider` 提供含 usage 的受控模型响应，`SseEvents.PendingApproval` 处理冻结 SSE 与当前 Run 审批，`GatewayFixture` 提供生产装配和 HTTP 证据，`GatewayProcessFixture` 管理真实 Gateway 子进程。SOUL 与 Lead 特有断言仍留在原测试；`AcceptanceFixturesTest` 验证夹具的失败路径。数据与证据默认外置到 `local-tmp/acceptance-fixtures/`，验收时通过进程级 `ENG_ACCEPTANCE_DATA_ROOT`、`ENG_ACCEPTANCE_EVIDENCE_ROOT`、`ENG_ACCEPTANCE_GATEWAY_PORT` 指定独立槽位。

```powershell
pwsh -NoProfile -File scripts/test-targeted.ps1 -Test 'ProductionWriteFileHttpTest,AcceptanceFixturesTest' -LowMemory
```

开发内环的定向测试统一使用：

```powershell
pwsh -NoProfile -File scripts/test-targeted.ps1 -Test LocalServerConfigurationTest
pwsh -NoProfile -File scripts/test-targeted.ps1 -Test 'AgentSoulPromptTest,UpdateAgentToolTest' -LowMemory
```

它固定带 `-o -ntp -pl super-harness-server -am`（`-Module` 可换模块），Maven 输出写到 `local-tmp/test-targeted/`，stdout 只输出一份 JSON 汇总（实际命令、退出码、tests/failures/errors/skipped、耗时、日志路径）。匹配 0 个测试算失败；不接受 `clean`、`install`、`-DskipTests`、`-Dhaifa.version` 等额外参数。

SDK 版本规则见 `AGENTS.md`「范围与依赖」。工作包 worktree 消费带标识的 SDK 版本时，在该 worktree 写入不被跟踪的 `local-tmp/sdk-selection.json`：

```json
{ "haifaVersion": "0.1.2-012-02a-delegation-SNAPSHOT" }
```

`scripts/test-targeted.ps1`、`scripts/run-w18-ui-smoke.ps1` 和 `scripts/run-w04-frozen-ui-files.ps1` 会读取它并传 `-Dhaifa.version`；文件不存在时使用基线并输出 `sdkSelection=ABSENT`。直接运行 `mvn` 不会读取该文件，仍使用基线。

UI runner 在构建成功后写入构建戳（`frontend/.next/haifa-build-stamp.json`、`super-harness-server/target/haifa-build-stamp.json`），`-SkipBuild`/`-SkipServerBuild` 复用构建产物前先核对源码 SHA、前端 lockfile、构建时代理目标和 Haifa 版本；不一致时在启动任何进程前失败并点名字段，不会自动重建，需去掉 `-SkipBuild` 重跑。

这些入口的回归与提交前的 SDK 基线门禁：

```powershell
pwsh -NoProfile -File tests/build/verify-build-entry.ps1 -Maven
pwsh -NoProfile -File tests/build/verify-haifa-version-baseline.ps1
```

验收采用“先核对测试要证明什么，再运行完整门禁”的顺序：开发期间可用定向测试快速排障；正式验收前须核对 Oracle 与实际断言、环境和槽位配置，最终仍要完成全仓验证、真实 smoke、规定的 UI 重复轮次及主控独立复验。同一源码和代理配置下的暖轮可复用已验证的前端构建，但每轮的运行状态、原始证据和失败记录必须独立保留；详细执行规则见 `AGENTS.md` 的“验收提速（不降低门禁）”。

## W18 冻结前端本地配置

客户 V2 验收按“演示主链优先、独立用例批量执行、问题集中修复”推进。局部失败留档后继续无依赖用例，批次结束按根因统一处理；高级路径先记录风险，必需门禁仍按真实结果结算。人工操作见 [简要测试说明](private-docs/prompts/39-deerflow-parity-delivery/show-roadmap-2/V2-Manual-Test-Guide.md)，批次划分和结果记录见 [剩余测试用例](private-docs/prompts/39-deerflow-parity-delivery/show-roadmap-2/V2-Remaining-Test-Cases.md)。

以下命令只校验冻结前端、已记录的最小 BFF 补丁、仅连接 Haifa 的 loopback 配置，以及固定的 Node/pnpm 启动工具链，不启动服务：

```powershell
pwsh -NoProfile -File scripts/start-haifa-frontend.ps1 `
  -DryRun `
  -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow
pwsh -NoProfile -File tests/frontend/verify-haifa-frontend-profile.ps1
```

受保护的主工作树拓扑与正式启动流程见下文“启动主工作树供人工验收”；历史 W18 配置设计与验收记录已归档至 `private-docs/prompts/39-deerflow-parity-delivery/W18-Developing/references/`。仅通过上述配置检查，不代表聊天功能或双后端差分验收通过。

验收 `.cjs` 脚本（`tests/**`，含 Playwright 用例、受控 Provider 与 Steer 契约断言）的功能与运行方法见 [验收 .cjs 脚本功能与运行说明](docs/engineering/001-cjs-scripts.md)；相关前端 `.mjs` 脚本见该文附录 A。

`run-w18-ui-smoke.ps1` 的本地受控模型使用 `context_window=32768`，让完整冻结 Lead prompt、生产 Tool catalog 和可选 Skill 正文容纳在同一次真实 SDK 请求中。若 Run 在 Provider 请求前返回 `MODEL_CONTEXT_TOO_LONG`，按 `AGENTS.md` 的受控模型排障记录核对配置和原始 trace。

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
## Lead 系统提示词装配

Gateway 从 `resources/upstream/deerflow/prompts/` 读取冻结 DeerFlow 原文和有序适配清单，校验资源 SHA，并通过 Haifa SDK 公开 `ProductProfile` 装配每个 Bridge 的系统指令。默认文件、Web、澄清工具已对应恢复原提示；Skill 加载说明映射为 `skill_load` / `skill_resource_read`。渲染中提及未注册工具、资源漂移或超长时拒绝创建 Run，错误不含提示词正文。

后续 Turn 的提示词变化会重建 Bridge，活跃 Run 保持原声明。线程产品元数据保留已准入 Run 的 Skill 选择、agent 身份、bootstrap 标志和提示词签名；pending Run 重启恢复通过公开 SDK 的 `frozenInstructionDiagnostic` 核对原冻结指令摘要，允许当前配置已更新，缺失或不一致则拒绝继续执行。用户边界只用于模型输入，state/history 保持用户原文。资源来源与适配说明见 `resources/upstream/deerflow/prompts/lead-agent-assembly.md`。

## Agent SOUL 与冻结新建页

有身份的 agent 将已保存 SOUL 转义后装配到 Lead 指令，并声明 `update_agent`；更新只影响下一 Turn。bootstrap 跳过不存在的 agent 配置，使用默认指令和基础工具加 `setup_agent`，不接入 MCP 或普通 Skill。两个工具通过既有配置存储原子写入当前 agent，实际声明 `FILE_WRITE`；Bridge 使用公开 `autoApproveStandardFileWriteTools` 仅豁免这两个生产工具的标准写审批，普通 `write_file` 等仍需审批。缺少身份、空 SOUL 或完整渲染超过 32000 字符时不写入、不截断。

本切片 `update_agent` 只支持 `soul`、`description`、`display_name`，能力字段明确报错；省略字段保持原值，冻结 OptionalText 的 `null`/`none`/`undefined` 字符串视为未传。SOUL 使用现有 agent 配置 JSON 保存，不另建 SOUL 文件；bootstrap Skill、全局 SOUL 和 legacy pending Run 自动迁移为已登记差异。旧 Thread 保留历史读取；旧 Run 全部结束后可按当前配置准入新 Turn，成功后记录新 Run 的身份与签名。缺少原身份或签名的活跃 Run 不能恢复执行或 Steer。

M3 消费本地公开 `0.1.2-SNAPSHOT` JAR，SDK 来源与构件 hash 见 [集成报告](private-docs/ai-coding-report/39-deerflow-parity-delivery/W03/W03-AgentSoulSdkIntegration.md)。PR266 已于 2026-10-02 人工合入 `dev@d97dd584`。按本次人工精简要求，W03-task-5 经生产/构建源码一致、26 项公开构件 hash 不变和原始证据复核后，复用既有 SDK L3、公开消费者两 JVM、M3 全仓/UI/smoke 结果，取消重复发布和重跑；合并 SHA 的 SDK L3 如实标记 `NOT_RUN`。原测试整理日志缺失项在首批验收后，以完整合并 tree 上9类123项定向测试及新原始日志补证，首次FAIL保留。task-5 已由主控在 SHPM 验收为 finished，详见 [验收报告](private-docs/ai-coding-report/39-deerflow-parity-delivery/W03/W03-FrozenInstructionControllerAcceptance.md) 与 [038-13 收尾范围](private-docs/prompts/038-sdk-012-super-harness-gaps/13-frozen-instruction-diagnostic-prompt.md)。W03 整包仍为 in_progress：非flash模式和思考参数的正向执行合同尚未交付，阻断issue为 `W03-F03-MODE-CONTRACT-001`；M4和演示GO由其各自门禁结算。

模式缺口的 [开发提示词](private-docs/prompts/39-deerflow-parity-delivery/W03-Developing/W03-ModeThinkingConfiguration.md) 已按冻结 DeerFlow、DeepSeek 官方文档和实际 SDK JAR 核对。思考模式（开/关）与推理级别（强度）是两个不同属性，SDK 已有 `ModelReasoningMode`、`ModelReasoningEffort`，通过 `ModelReasoningPolicy` 组合；界面沿用冻结 DeerFlow 的交互与字段映射。Flash/Pro 都支持思考，SH 需要接入每 Run 的冻结参数；SDK 另需补 DeepSeek 方言的 LOW、新 Flash 精确 Binding 和工具历史续接。W03负责模型配置与这两个属性接线，计划执行归W07、子Agent执行归W08，后续按对应工作包交付组织pro/ultra联调。研究与文档交付不代表产品已实现或真实模型验收通过。

2026-10-02 人工批准本切片精简：保留原Case与逐参数断言，W03按八组共用准备和必要运行轨迹；SDK编码前只复现LOW、Binding与历史续接缺口，完整恢复/安全验证放在稳定版本。SDK负责底层参数矩阵，SH验证配置映射、错误边界和Gateway恢复；满足断言的正式UI真实Run可同时作为smoke证据。两层最终构建门禁、真实smoke、规定UI cold/warm重复轮次和首次FAIL原件均保留，不继承task-5的特殊L3豁免。

冻结 UI 的正式验收由主控在独立 worktree 运行，使用真实模型和合成 SOUL，验证“对话 → 保存 → setup_agent → 已创建 → 刷新可见”。第一轮 cold，第二轮在相同源码、依赖和构建时代理目标下 warm 复用构建，每轮使用新数据目录：

```powershell
pwsh -NoProfile -File scripts/run-w18-ui-smoke.ps1 -AgentSlot 2 -GatewayPort 30618 -FrontendPort 35618 -UxV1AgentSoul -SkipServerBuild -DataDirectory local-tmp/slot-2-ux-v1-soul/data/ui-cold
pwsh -NoProfile -File scripts/run-w18-ui-smoke.ps1 -AgentSlot 2 -GatewayPort 30618 -FrontendPort 35618 -UxV1AgentSoul -SkipBuild -DataDirectory local-tmp/slot-2-ux-v1-soul/data/ui-warm
```

`-SkipServerBuild` 只在本轮源码已经完成全仓门禁、exec JAR 已生成时使用。脚本固定 retries=0，将 trace、截图、前后端原始日志和 `flow-health.json` 保存到 `local-tmp/slot-2-ux-v1-soul/evidence/run-<时间戳>/`，结束后核对端口和子进程清理。trace 包含实际合成 SOUL 的浏览器流量；派生 JSON 只记录 hash、长度与 ID。命令和局部测试通过不自动结算阶段 PASS。

## W15 V2 最小 Skill 承载

Gateway 从 `third-party/deerflow/skills/manifest.json` 读取受信、不可变的 Skill 版本目录，核对整包文件及来源许可哈希后，交由本地发布的 Haifa Agent `0.1.2-SNAPSHOT` 公开 Skill API 执行发现、激活与 Run Context 注入。当前生产 manifest 只登记冻结 DeerFlow `consulting-analysis`；`contracts/customer-demo-v2/fixtures/skills/v2-format-control/` 只供独立测试数据目录使用，不会在生产配置中启用。旧版本目录须随发行物保留，以便重启后的旧 Run 校验原 binding；不可原位覆盖。

冻结前端使用 `GET /api/skills`、`GET/PUT /api/skills/{name}` 与 `POST /api/skills/reload`。启停状态按数据目录原子保存到 `skills_config.json`，只影响后续新 Run；`Agent.skills` 的 `null` 表示全部已启用 Skill，`[]` 表示不使用 Skill，名称列表为允许名单。启动 Gateway 时工作目录须为仓库根；模块测试可从 `super-harness-server/` 运行。缺包、哈希/许可漂移或无法读取 manifest 会拒绝接入，不会回落到 DeerFlow 后端。完整双 Skill HTTP/Run 断言与冻结 UI 两轮仍以 V2 W15/W18 验收记录为准。

W15 冻结 UI 回归从独立槽位运行，先在该 worktree 的 `frontend/` 用 `COREPACK_ENABLE_NETWORK=0` 执行 `corepack pnpm install --offline --frozen-lockfile`，确认版本为 `10.26.2` 且 `downloaded 0`，再在仓库根执行同一脚本两轮：

```powershell
pwsh -NoProfile -File scripts/run-w18-ui-smoke.ps1 -AgentSlot 2 -GatewayPort 30618 -FrontendPort 35618 -V2Skill -DataDirectory local-tmp/slot-2-w15-v2-skill/data/ui-round-1
pwsh -NoProfile -File scripts/run-w18-ui-smoke.ps1 -AgentSlot 2 -GatewayPort 30618 -FrontendPort 35618 -V2Skill -DataDirectory local-tmp/slot-2-w15-v2-skill/data/ui-round-2 -SkipBuild
```

第二轮 `-SkipBuild` 仅在首轮已按 Gateway `30618` 构建成功后使用。每轮脚本在结束时清理槽位进程，Playwright trace、截图及前后端/受控 Provider 日志写到 `local-tmp/slot-2-w15-v2-skill/evidence/ui/run-<时间戳>/`；测试需检查 `skill_load` ToolCall、启用后的模型 Context 和禁用后新 Run 的隔离。完整验收仍以开发报告的实际运行结果为准。

## 默认 Web 工具的环境代理

三个生产 Web 工具通过公开 SDK 的 `JavaToolSpec.networkAccess(hosts)` 声明 `NETWORK_ACCESS` 与逻辑服务主机；覆盖 endpoint 时主机元数据随实例配置变化，代理地址不进入逻辑服务名单。Bridge 仅对这三个生产工具显式配置 `autoApproveReadOnlyNetworkTools`，复用原策略 evaluator 并保留其它 MCP、文件写入、自定义 ASK/DENY 和危险工具的审批。主机元数据参与冻结 Catalog/Policy digest，SDK 不拦截工具自建 HTTP 客户端。上游交付与人工审查入口见 [SDK 消费记录](docs/todo.md)。

UX-V1 默认工具验收在独立工作树运行 `pwsh -NoProfile -File scripts/run-w18-ui-smoke.ps1 -AgentSlot 2 -GatewayPort 30618 -FrontendPort 35618 -UxV1DefaultTools -DataDirectory <本工作树内的新数据目录>`。DDG 挑战仍在时，按人工指示追加 `-DeferUxV1Web`：A 文件/Artifact、B 澄清继续执行，C 搜索依赖链显式记为 NOT_RUN；延期或局部通过不能记为 M1 全部通过。源码、前端依赖和构建时代理目标相同时，第二轮可加 `-SkipBuild`，仍须使用新的 data-root、Run 与证据。原始 SSE、Run diagnostics/Usage、trace、截图及 `flow-health.json` 外置到该槽位 `local-tmp`；保留失败与首次卡点。

主控已经在同一源码运行全仓 `clean verify` 并留存 JAR 哈希、SDK 构件与原始门禁证据时，冷轮可加 `-SkipServerBuild` 复用该 JAR，仍按本槽位重新构建 Next；它不会执行 Java 门禁，不能用来替代或跳过失败的全仓验证。源码或 SDK 构件改变后必须重新构建、验证。

默认 `web_search`、`image_search` 和 `web_fetch` 的 HTTP 客户端优先读取产品精确键 `HAIFA_SUPER_HARNESS_TOOL_HTTP_PROXY`：非空时它作为三种工具的 HTTP 代理，并通过 CONNECT 承载 HTTPS 目标，且优先于通用 `HTTP_PROXY`、`HTTPS_PROXY`、`ALL_PROXY`、`NO_PROXY` 和 JDK 默认 `ProxySelector`；该键为空或缺失时，才回退到通用环境映射。通用映射读取启动进程的 `HTTP_PROXY`、`HTTPS_PROXY`、`ALL_PROXY` 和 `NO_PROXY`，小写同名变量优先；对应协议没有代理时使用 `ALL_PROXY`，仍未配置时保留 JDK 默认 `ProxySelector`。不修改 JVM 全局选择器、用户环境或 Windows 系统代理。

代理地址支持 `http://host:port`（省略协议时按 HTTP 解释，缺省端口为 80）；HTTPS 目标通过 HTTP 代理的 CONNECT 转发。当前不支持 SOCKS、代理地址自身为 HTTPS、代理认证、`NO_PROXY` CIDR 或任意通配表达式；配置不支持时明确报错，错误不含代理凭据，也不会静默改成直连。

`NO_PROXY` 用逗号分隔：裸域名匹配本身及点分隔的子域名，前导点域名只匹配子域名，IP 地址精确匹配；可添加端口或 `http://` / `https://` 来限制排除范围，`*` 表示全部直连。示例 `127.0.0.1,localhost,example.com:443` 会保留本地回环访问。只配置 `HTTPS_PROXY` 时，未配置的 HTTP 请求仍交给原 JDK 选择器。

需在启动 Gateway 的同一个 PowerShell 进程显式注入代理变量，优先用产品键 `$env:HAIFA_SUPER_HARNESS_TOOL_HTTP_PROXY='http://<代理主机>:<端口>'`；若沿用通用变量，则设置 `$env:HTTP_PROXY='http://<代理主机>:<端口>'`、`$env:HTTPS_PROXY=$env:HTTP_PROXY`、`$env:NO_PROXY='127.0.0.1,localhost'`，然后执行启动脚本。产品键非空会覆盖通用变量与 `NO_PROXY`。Web 工具只消费这些变量；它不会自动读取 WinINET，也不改变 MCP、模型 Provider 或审批配置。代理接入不代表 DuckDuckGo 挑战页已解决：202/403 仍保留无结果语义与诊断日志，不重试或切换引擎。

## W21 V2 诊断投影

`GET /api/threads/{thread_id}/runs/{run_id}` 的 `diagnostics` 只读投影使用公开 SDK 的 Run、ModelCall、ToolCall、Interaction 与 Artifact 事实；状态、稳定 ID、错误代码及停止原因均保留 SDK 来源。ToolCall 的实际 MCP/Native 来源、无法证实的 Artifact 路径或 SHA、精确 Run 起止时间，以及失败或缺失的模型 Usage（含 LLM 调用数）返回 `null`，不从当前配置猜测历史事实；SDK 最后更新时间另记 `last_updated_at`。单 Run 最多扫描 20 页、每页 100 条 SDK 事件；超限返回 `projection_complete=false` 与未知 Usage。Run 列表只读 SDK 快照摘要，不扫描每条 Run 的事件；其明细集合为 `null`，须按 ID 单独读取，不把未扫描误报为空集合。

`GET /api/threads/{thread_id}/token-usage?include_active=false` 聚合同一 Thread 中已结束且可核对的 Run，整次请求最多扫描 50 页 SDK 事件；历史被截断或 Usage 无法完整核对时返回 JSON `null`，供冻结前端显示未知而不是 `0`。`by_model` 键采用 `<providerId 字符数>:<providerId><modelId>`，避免不同 Provider 的同名 Model 合并。该投影不创建新事件库，也不改变 Run、审批或 Artifact 权威。

W21 冻结 UI 诊断用例使用独立槽位 3、受控 Provider 和两次相同脚本运行。首轮完成构建，次轮只在源码、前端依赖和 Gateway 代理目标完全一致时复用构建；两轮使用不同数据目录，原始日志、trace、截图和诊断 JSON 保存到 `local-tmp/slot-3-w21/evidence/ui/run-<时间戳>/`。执行前需按 `AGENTS.md` 检查整组端口、空闲内存和冻结来源：

```powershell
pwsh -NoProfile -File scripts/run-w18-ui-smoke.ps1 -AgentSlot 3 -GatewayPort 40618 -FrontendPort 45618 -V2Diagnostics -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow -DataDirectory local-tmp/slot-3-w21/data/ui-cold
pwsh -NoProfile -File scripts/run-w18-ui-smoke.ps1 -AgentSlot 3 -GatewayPort 40618 -FrontendPort 45618 -V2Diagnostics -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow -DataDirectory local-tmp/slot-3-w21/data/ui-warm -SkipBuild
```

用例在真实冻结页面批准生产 `write_file`，核对同一 Run 的 ModelCall、ToolCall、Interaction、Artifact ID 和下载字节，并核对页面请求的 `/token-usage`。受控 Provider 另有真实省略 Usage SSE 帧的负例，要求 SDK 报 `PARTIAL_RESPONSE` 且诊断和 HTTP Usage 保持未知。冻结页面当前关闭可见 token badge，因此不把 badge 当作通过断言；跨身份隔离仍须另行执行。

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

### 当前主工作区 Utility MCP 体验（2026-09-30）

主 Gateway `18018` 已部署 main@530dfb2 的验收 JAR，冻结前端仍为 `33018`。已启用 `haifa-utility`，连接 `http://127.0.0.1:8091/mcp`，19 个工具来自实际 MCP 发现，协议 `2025-06-18`；在能力中心的 Plugins 页面可查看，在 Agent 的 MCP 选择中可启用。新会话可输入「请实际调用 MCP calculate 计算 23 * 19」，出现审批后点击 Approve。

Utility 使用另一项目 `D:/workspace/haifa-ai-deerflow/utility-mcp-server` 的已有编译产物和离线 runtime classpath，以单 Java 进程运行（`-Xmx384m -XX:+UseSerialGC`，loopback 8091），避免常驻 Maven。运行清单、启动/停止脚本和部署原件在 `local-tmp/main-utility-preview-20260930/`。仅停止此 Utility 服务可运行 `pwsh -NoProfile -File local-tmp/main-utility-preview-20260930/start-utility.ps1 -Stop`；之后用同脚本不带 `-Stop` 恢复。脚本依赖已生成的 classpath 与 Utility target/classes，不删除这些文件。主服务数据及 continuation key 保持原样，本次连接不代表 V2 阶段通过。

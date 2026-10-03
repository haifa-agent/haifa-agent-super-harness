# Haifa Super Harness

本仓库是冻结 DeerFlow 对齐项目的独立 Java 21 产品工程。项目只通过 Maven 仓库中已发布的 `io.haifa:haifa-agent-bom:0.1.2-SNAPSHOT` 及公开 JAR 消费 Haifa Agent，不依赖 Haifa Agent 源码目录。

## 架构边界

Super Harness 是产品、兼容与集成层，不是第二套 Agent Runtime。可复用的 Runtime 能力（Run/Loop、Memory、Tool/Skill、MCP、Sandbox、Approval、Artifact、Checkpoint、Parent/Child 等）归 Haifa Agent；Super Harness 负责冻结 DeerFlow 协议映射、产品语义、UI/BFF、业务集成与控制面展示。新增基础能力前须按 [Runtime / Product Boundary](docs/runtime-product-boundary.md) 做归属判断；如果缺的是通用 Runtime 能力，应先补 Haifa Agent 公开 API/SPI，再由本项目消费。

## 客户演示与项目看板

项目的任务状态、派工、checkpoint、验收证据和客户演示门禁由 SHPM（Super Harness Project Manager）维护；人工可在 http://127.0.0.1:8787/ 只读查看。只有主控智能体通过 `shpm` 命令写入，子智能体不写 SHPM。提示词、roadmap、交付报告和原始证据的正文仍在 `private-docs`、`contracts` 与各 worktree 的 `local-tmp` 等原位置，SHPM 只保存路径。环境准备和完整操作流程见 [SHPM 使用手册](docs/shpm-manual.md)。

原 `work-packages.js`、`docs/todo.md`、`dashboard.html` 与 `work-package-dag.html` 已删除，进度只看 SHPM。W00–W24 仍是必需的产品工作包；2026-10-02 人工批准另建 `ENG` 横切工程工作包，不能用它替代任何产品工作包。W24 证据门禁与主控派工审计直接读取 SHPM 快照（数据库缺失时 fail closed，不会新建空库）：

`requires` 支持工作包前置依赖和阶段到既有任务或工作包的依赖。工作包仍检查存在性、自引用、重复和环；阶段依赖检查合法来源与目标。阶段 `PASS` 时，所有 `required` 的 `requires` 和 `delivers` 任务必须已由主控验收为 `finished`，合法关系不自动提升阶段结果。

聚合器校验 `IMPORTED` 资源时优先使用已声明的 `local_sha256`，缺失时才回退原 `sha256`；本地哈希无效或内容不符会失败。来源文件的 SHA 和完整冻结来源仍由 `verify-deerflow-contracts.ps1` 校验。在没有 `private-docs` 的独立 worktree 运行 verifier 单元脚本时，用 `-RepositoryRoot D:\workspace\haifa-agent-super-harness` 提供真实 prompt fixture 输入；被测工具仍来自该脚本自身的 checkout，不能用另一分支脚本替代。

```powershell
$env:SHPM_DB='C:\Users\wangr\.shpm\data\project.db'
pwsh -NoProfile -File scripts/governance/audit-work-package-delivery.ps1
pwsh -NoProfile -File tests/checks/evidence/verify-evidence-report.ps1
```

客户演示按轮次推进：客户演示 v0（亦称 V1，[路线图](private-docs/roadmap/36-super-harness-customer-demo-v0.md)）已在 `main@c112012` 结算 `GO`；客户演示 V2 的范围与阶段退出标准见 [show-roadmap-2](private-docs/prompts/39-deerflow-parity-delivery/show-roadmap-2/README.md)，当前状态以 `shpm sprint gate DEMO-V2 --json` 为准。演示阶段通过不会自动关闭工作包，也不代表 PRD D01–D05 完整 Customer Demo Gate 或 Full Parity。

## W01 基础工程

- `super-harness-app` 验证真实的公开 SDK `Run → Java 类型化 Tool → Artifact` 链路：确定性 Tool 生成内容，应用再通过公开 Artifact 服务发布，保留真实 Run/Session 来源并校验存储内容的哈希。
- `super-harness-harness` 是最小化、失败即关闭的证据校验器。必需用例缺失、显式失败、重复，或标为 PASS 却没有证据时，校验均失败。
- Maven Enforcer 拒绝直接或传递依赖 Haifa 产品、演示、测试 JAR，并拒绝直接依赖实现模块；ArchUnit 测试禁止生产代码导入 SDK 内部包和实现包。

## 脚本与测试目录

`scripts/` 根目录保留 `test-targeted.ps1`、`verify-full.ps1` 和 `start-main-super-harness.ps1` 三个常用入口，其余按前端、协议、资源、doctor、UI、MCP、证据与治理分类，共用函数位于 `scripts/lib/`。完整入口说明见 [scripts/README.md](scripts/README.md)。

`tests/checks/` 保存脚本与校验器的回归检查；`tests/ui/` 按 `smoke/`、`files/`、`dual-backend/` 保存浏览器验收用例与夹具。Java 单元测试仍位于各模块的 `src/test/java/`。目录用途、前置条件及旧路径对应关系见 [tests/README.md](tests/README.md)。

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

`scripts/test-targeted.ps1`、`scripts/ui/run-w18-ui-smoke.ps1` 和 `scripts/ui/run-w04-frozen-ui-files.ps1` 会读取它并传 `-Dhaifa.version`；文件不存在时使用基线并输出 `sdkSelection=ABSENT`。直接运行 `mvn` 不会读取该文件，仍使用基线。

UI runner 在构建成功后写入构建戳（`frontend/.next/haifa-build-stamp.json`、`super-harness-server/target/haifa-build-stamp.json`），`-SkipBuild`/`-SkipServerBuild` 复用构建产物前先核对源码 SHA、前端 lockfile、构建时代理目标和 Haifa 版本；不一致时在启动任何进程前失败并点名字段，不会自动重建，需去掉 `-SkipBuild` 重跑。

全仓门禁入口还会先跑秒级预检（`scripts/lib/quick_checks.py`：SDK 基线门禁、`git diff --check`、secret scan），失败时直接输出 `FAIL_PREFLIGHT`，不启动 Maven；Maven 默认三个并行 fork（`-Forks`，1–4）。UI runner 会自动复用构建戳匹配的后端 JAR 和前端构建，前端还可从主检出的共享缓存 `local-tmp/shared/frontend-build/` 恢复，只有都不可用时才重新构建。摘要 JSON 同时写到日志旁的同名 `.json`。`test-targeted.ps1`、`verify-full.ps1` 和 UI runner 遇到含通配符或 CIDR 的 `NO_PROXY` 时，只在本进程内改用 `127.0.0.1,localhost`。

流水线辅助脚本（Python，核心逻辑在脚本内）：

```powershell
python scripts/governance/change_scope.py                      # 按 main 以来的改动给出需要的门禁
python scripts/governance/shpm_dispatch.py --task ... --execute # 一次完成登记、启动与派工
python scripts/governance/shpm_record_gates.py --task ... local-tmp/verify-full/<run>.json <UI 证据目录> --execute
python -m unittest discover -s tests/checks/build -p "test_*.py"
```

两个 SHPM 脚本不带 `--execute` 时只打印将要执行的命令。

全仓门禁用 `pwsh -NoProfile -File scripts/verify-full.ps1`：它运行 `mvn -o -ntp clean verify`（同样读取 `local-tmp/sdk-selection.json`），Maven 输出写到 `local-tmp/verify-full/`，stdout 输出 JSON 汇总；通过后为它产出的 exec JAR 写入服务端构建戳。随后在同一提交上，任意 profile 的 UI 冷轮都可加 `-SkipServerBuild` 直接复用该 JAR，只重新构建前端。不加 `-SkipServerBuild` 的冷轮仍会自行打包后端，但以 `-DskipTests` 只打包、不重复执行单元测试；单元测试只由全仓门禁证明。

这些入口的回归与提交前的 SDK 基线门禁：

```powershell
pwsh -NoProfile -File tests/checks/build/verify-build-entry.ps1 -Maven
pwsh -NoProfile -File tests/checks/build/verify-haifa-version-baseline.ps1
```

验收采用“先核对测试要证明什么，再运行完整门禁”的顺序：开发期间可用定向测试快速排障；正式验收前须核对 Oracle 与实际断言、环境和槽位配置，最终仍要完成全仓验证、真实 smoke、规定的 UI 重复轮次及主控独立复验。同一源码和代理配置下的暖轮可复用已验证的前端构建，但每轮的运行状态、原始证据和失败记录必须独立保留；详细执行规则见 `AGENTS.md` 的“验收提速（不降低门禁）”。

`-LiveDeepSeek` 使用托管凭据直连真实模型，不启动本地受控 Provider；runner 显式将可选 Provider 进程初始化为空，兼容 PowerShell 7 StrictMode 下的诊断输出与清理。真实回答 smoke 与受控模型的 Tool/Artifact 验证分别保留证据，不据此提升完整 W08 验收。

## W18 冻结前端本地配置

### 验收快速反馈

清理在原有共享停止预算内同时等待端口可绑定、Runtime进程退出与已登记子进程（包括浏览器）退出；仍有残留时返回FAIL，不延长预算或重试功能用例。进程命令指纹不匹配时拒绝终止该 PID，只在原预算内等待登记的 PID 与创建时间对应的进程自行退出；持续存活仍为 FAIL，已复用 PID 的新进程受保护。`flow-health.json` 的 `cleanup.identityChanges` 记录脱敏身份指纹与退出观察，不记录完整命令。

开发顺序为实现、定向测试或单case冒烟、集中自审修复、稳定版本正式门禁。独立读取批量执行，终端默认只汇总退出码、计数、首个失败和原件路径。按 [交接模板](docs/acceptance-handoff-template.md) 在各worktree的`local-tmp/acceptance-handoff.md`记录短交接；等待构建时做独立检查，保留有界等待和进展更新。

先检查本轮依赖、浏览器、环境和槽位，不构建或启动服务：

```powershell
pwsh -NoProfile -File scripts/ui/run-w18-ui-smoke.ps1 -AgentSlot 3 -GatewayPort 40618 -FrontendPort 45618 -PreflightOnly
```

只跑默认profile中一条真实SSE用例（标题精确匹配，未知/歧义标题在构建前失败）：

```powershell
pwsh -NoProfile -File scripts/ui/run-w18-ui-smoke.ps1 -AgentSlot 3 -GatewayPort 40618 -FrontendPort 45618 -CaseTitle 'creates a thread and renders a real Haifa SSE answer'
```

已验证同源码、依赖、SDK构件和代理目标的构建可按原规则加`-SkipBuild`，每次使用新数据目录。SDK同版本重新发布后必须去掉复用开关重新构建。`preflight.json`记录检查结果，`flow-health.json`记录执行范围和选中case；单case运行的其余case在`case-results.json`中保持`NOT_RUN`。空选择、缺依赖/浏览器、NO_PROXY通配符及不足的受控模型窗口均在构建前失败，不自动修改环境或安装依赖。预检/局部冒烟不替代正式全套冷暖轮、真实smoke或阶段验收。

生产JAR profile（包括默认受控profile）可用已有`-SkipServerBuild`复用经过门禁和构建戳核对的同SHA/SDK JAR，同时仍执行本槽位Next冷构建，避免UI准备再次执行已通过的Java测试。Interaction使用测试classpath，拒绝此开关；缺构建戳、源码/SDK不匹配仍在启动前失败。它不执行或替代Java门禁，主控必须先保存本轮门禁与JAR身份；SDK重新发布后仍须重建。

客户 V2 验收按“演示主链优先、独立用例批量执行、问题集中修复”推进。局部失败留档后继续无依赖用例，批次结束按根因统一处理；高级路径先记录风险，必需门禁仍按真实结果结算。人工操作见 [简要测试说明](private-docs/prompts/39-deerflow-parity-delivery/show-roadmap-2/V2-Manual-Test-Guide.md)，批次划分和结果记录见 [剩余测试用例](private-docs/prompts/39-deerflow-parity-delivery/show-roadmap-2/V2-Remaining-Test-Cases.md)。

以下命令只校验冻结前端、已记录的最小 BFF 补丁、仅连接 Haifa 的 loopback 配置，以及固定的 Node/pnpm 启动工具链，不启动服务：

```powershell
pwsh -NoProfile -File scripts/frontend/start-haifa-frontend.ps1 `
  -DryRun `
  -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow
pwsh -NoProfile -File tests/checks/frontend/verify-haifa-frontend-profile.ps1
```

受保护的主工作树拓扑与正式启动流程见下文“启动主工作树供人工验收”；历史 W18 配置设计与验收记录已归档至 `private-docs/prompts/39-deerflow-parity-delivery/W18-Developing/references/`。仅通过上述配置检查，不代表聊天功能或双后端差分验收通过。

验收 `.cjs` 脚本（`tests/**`，含 Playwright 用例、受控 Provider 与 Steer 契约断言）的功能与运行方法见 [验收 .cjs 脚本功能与运行说明](docs/engineering/001-cjs-scripts.md)；相关前端 `.mjs` 脚本见该文附录 A。

`run-w18-ui-smoke.ps1` 的本地受控模型使用 `context_window=32768`，让完整冻结 Lead prompt、生产 Tool catalog 和可选 Skill 正文容纳在同一次真实 SDK 请求中。运行前按 `AGENTS.md`「冻结前端」的使用说明准备依赖、槽位与受控模型配置。

## W05 客户演示 M2 Artifact UI 验收

W05 的受控 L2 验收使用子槽位 1（Gateway `20618`、Frontend `25618`）、生产 Super Harness JAR、冻结 DeerFlow UI 和本地受控 Provider，验证 `write_file → Approval → Artifact → Reload/Reconnect → UI 下载`。它不会调用真实模型；真实模型整链属于后续 M4。

首次运行会执行 Maven package 和按槽位代理地址构建冻结前端：

```powershell
pwsh -NoProfile -File scripts/ui/run-w05-demo-artifact-ui.ps1
```

仅当 Java exec JAR 已在本次源码变更后重新生成、且 `.next` 已用同一槽位 Gateway 地址构建时，才可复用构建产物：

```powershell
pwsh -NoProfile -File scripts/ui/run-w05-demo-artifact-ui.ps1 -SkipBuild
```

脚本固定 `retries=0`，每次生成独立 Thread 和文件，并把 trace、截图、前后端/Provider 日志及 Artifact 字节/SHA 证据写入 `local-tmp/slot-1-w05-m2-evidence/playwright/run-<时间戳>/`。正式验收须连续运行两次且都通过；第一次数据不得清理，结束后还须确认 `20618`、`25618` 和子进程均已释放。

## W03 思考配置开发切片

本分支通过公开 `ModelReasoningPolicy` 分别接入思考开关与推理级别：请求显式值优先于 Agent
非 null 默认值；显式 `thinking_enabled:null` 按冻结后端关闭思考，省略则继承。
开启时 `minimal/low` 映射 LOW、`medium/high` 映射 HIGH；flash 关闭且省略 effort。
Agent 的 `model_settings` 冻结到新 Run，启用思考与显式 temperature 组合明确拒绝。
同型号同 revision 的参数变化通过配置 digest 重建下一 Turn 的 Bridge；SDK 保持旧 Run 的冻结参数。
Thread 仅保存服务器维护的 digest 索引，用于拒绝 Steer 改参，不保存第二份模型 snapshot。

目录 `supports_thinking`、`supports_reasoning_effort` 暂保持 false，直到 SDK 038-14 发布、联调以及
W07 默认 pro 路径通过发布验收。显式 thinking 后端接线已开发，完整 UI、真实模型 smoke、
Gateway 审批恢复和最终门禁待流程/CI 优化完成后执行。本切片不提供计划、子 Agent 或思考正文展示。
## W08 子智能体目录

Gateway 提供 `GET/POST /api/subagents`、`PUT/DELETE /api/subagents/{name}`，managed 定义按现有 owner 配置布局保存到 `<data-root>/owners/default/subagents/<name>.json`。创建名称转小写，更新仅应用实际出现的字段；`tools=null` 保留继承语义，`tools=[]` 保留空集。创建拒绝覆盖同名文件，更新在同目录原子替换；损坏定义警告并跳过，不隐藏其他条目。读写每次读取文件，不新增运行状态或缓存。

本地 profile 沿用 `/api/v1/auth/me` 的固定管理员身份；角色判定会对非管理员隐藏 `system_prompt` 并禁止写入，但当前无真实非管理员 HTTP 身份入口。内置目录只列 `general-purpose`（`max_turns=150`）；因无生产 shell 工具，暂不列 `bash`。SQL managed 存储、config.yaml 来源及 config overrides 未接入。本切片提供只读目录与稳定内容指纹，不表示已实现 Child 执行或冻结 UI 验收。

目录真实 HTTP 定向测试与 CRUD smoke：

```powershell
pwsh -NoProfile -File scripts/test-targeted.ps1 -Test DeerFlowSubagentCatalogApiTest -LowMemory
```

原始合成请求/响应保存在当前 worktree 的 `local-tmp/acceptance-fixtures/evidence/`；可在启动测试的会话设置 `ENG_ACCEPTANCE_DATA_ROOT`、`ENG_ACCEPTANCE_EVIDENCE_ROOT` 指向该槽位的独立目录。目录测试不调用模型；完整 W08 的 SDK、Run、回放与 UI 门禁由对应任务验收。

生产 Run 的 `context.subagent_enabled=true` 才声明 SDK Child 与 `task`；未提供、null、false 或 bootstrap 时关闭。Custom Agent 的 `allowed_subagents=null` 表示全部有效定义，`[]` 表示禁止全部，列表取有效目录交集。每次新 Run 读取目录、模型和实际 Tool 声明生成快照与指纹；正在执行的 Run 沿用已冻结声明，重启历史读取公开 SDK 事实。新启用委托的 Parent 使用 `haifa-super-harness:lead` 定义 ID，避免与合法 Child 名称冲突；未启用及历史恢复保留原定义 ID，历史 Run 不改名。

每个 Gateway 以公开 SDK `ChildRunCapacity` 持有一个共享容量，全部 Thread、后续 Bridge 重建与恢复 Bridge 复用同一实例；不同 Gateway/data-root 独立，准入、等待唤醒、取消与实际执行结束后的容量释放由 SDK 管理。

进程启动配置 `HAIFA_SUPER_HARNESS_SUBAGENT_MAX_RUNNING` 默认 3，`HAIFA_SUPER_HARNESS_SUBAGENT_MAX_CONCURRENT` 默认取进程容量；并发夹取到 1–64 且不超过容量。`HAIFA_SUPER_HARNESS_SUBAGENT_MAX_TOTAL_PER_RUN` 默认 6，夹取到 1–50。非法整数启动失败；本期不支持 context 覆盖。Child 的 `timeout_seconds`、`max_turns` 通过公开 profile 映射到 SDK，深度绝对上限 1；`tools=null` 继承 Parent 实际 Tool 集后减禁用项，`[]` 保持空集，Child 的需审批操作由 SDK 非交互策略拒绝。`skills` 存储保留，但本期不装配。

启用委托的 Parent wall 授权为 `120000 + max_total_per_run * 最大有效 Child timeout_ms`，以免原 120 秒 Parent wall 提前终止委托等待。Parent 原有 16 次迭代、模型/Tool 调用预算等限制仍生效；执行限额与终止由 SDK 拥有。默认内置 timeout900秒、总量6时 wall 授权为5520000毫秒。未启用委托仍使用原120秒授权。

生产装配定向验证：`pwsh -NoProfile -File scripts/test-targeted.ps1 -Test 'DeerFlowTaskDelegationApiTest,SubagentRunConfigurationTest,LeadPromptComposerTest' -LowMemory`。这些测试消费主控指定的公开候选 JAR；完整回放/UI/全仓门禁仍须独立验收，未合入上游 dev 的 SDK 候选不能使本分支合入 main。

W08 子任务步骤兼容层通过公开 SDK 的 `messages` 持久游标和 `subscribe` 信号投影既有 Parent SSE
`custom` 流；不新增步骤存储或执行线程。刷新回放入口为
`GET /api/threads/{thread_id}/runs/{run_id}/events?event_types=subagent.step&task_id={task_id}&limit=500&after_seq=0`。
`task_id` 必填且使用 Parent 的 Runtime task Tool Call ID；只支持 `subagent.step`，limit 为 1–500，
after_seq 为非负 JavaScript 安全整数。未知 task 返回 `[]`，未知或不属于该线程的 Parent 返回 404。
步骤正文与序列化参数最多 8192 个 Unicode 字符并保留截断标记；模型与已知累计 Usage 来自该 Child 的
公开事件，不读取当前目录推测历史。该接线需要包含公开 `RunMessage*` API 的 SDK 候选，当前完整
HTTP/SSE、重启与冻结 UI 验收以工作包证据为准；接口静态实现不表示 W08 全部通过。

## W03 后续建议配置

Gateway 通过 `GET /api/suggestions/config` 暴露冻结前端的后续建议全局配置。以下两个环境变量在进程启动时读取一次，均为可选：

- `HAIFA_SUPER_HARNESS_SUGGESTIONS_ENABLED`：是否启用后续建议，默认启用。设为 `0`、`false` 或 `no` 时关闭；关闭状态下 `POST /api/threads/{thread_id}/suggestions` 不调用 Provider，直接返回空 `suggestions` 列表。
- `HAIFA_SUPER_HARNESS_SUGGESTIONS_MAX`：`max_suggestions` 上限，默认 `3`；非法值回落为默认值，取值被限制在 1 到 5 之间，并按该上限截断请求中的 `n`。

线程标题由 Gateway 在首个完成的 user+assistant 交换后按冻结 `TitleMiddleware` 的本地回退规则生成（首轮被取消时允许仅凭首个 user 消息生成），持久化到线程 metadata 与 `values.title`；显式重命名在 Run 运行期间返回 409，且不会被后续自动标题覆盖。

同一 Conversation 可在前一个 Run 终止后的下一 Turn 选择另一模型；当前 Run 的模型保持冻结，历史中的 Tool 与待审批 Interaction 仍归原 Run。可在独立槽位 1 用冻结 UI 和两个本地受控模型复验（不调用真实模型）：

```powershell
pwsh -NoProfile -File scripts/ui/run-w18-ui-smoke.ps1 -AgentSlot 1 -GatewayPort 20618 -FrontendPort 25618 -V2ModelSwitch -DataDirectory local-tmp/slot-1-w03-v2-model-switch/data/ui-round-1
pwsh -NoProfile -File scripts/ui/run-w18-ui-smoke.ps1 -AgentSlot 1 -GatewayPort 20618 -FrontendPort 25618 -V2ModelSwitch -DataDirectory local-tmp/slot-1-w03-v2-model-switch/data/ui-round-2 -SkipBuild
```

第二轮的 `-SkipBuild` 只能用于首轮已按同一 Gateway 代理目标构建的前端；每轮使用独立数据目录，trace、截图及前后端/Provider 日志写入 `local-tmp/slot-1-w03-v2-model-switch/evidence/ui/run-<时间戳>/`。该受控 UI 回归不代替 W18 双后端差分、真实模型链或 V2 阶段门禁。

## W18 V2-M2 MCP 双后端差分

`scripts/ui/run-w18-v2-m2-mcp-rounds.ps1` 在 W18 独立 worktree 中按 reference 冷/暖、Haifa 冷/暖顺序运行同一套冻结 UI MCP 用例；每轮由 `scripts/ui/run-w18-v2-m2-mcp.ps1` 管理独立 Gateway、Frontend、受控 Provider 和两个真实 Streamable HTTP MCP Server。槽位固定为 `20618/25618`，测试 MCP 使用 `20628/20629`，负例无监听端口为 `20630`，Provider 使用 `46618`。脚本会对整组端口查询 NAT 排除范围并实际绑定预检，保存每轮原始日志、trace、截图、Run 与配置证据，结束时只清理自己启动的进程。失败轮次保留 `FAIL`，不会暗中重试；MCP 切片通过也不自动提升 V2-M2 阶段。

本切片的负例只在 W18 runner 中启用：区域 fixture 检查测试专用 `Authorization` 值，缺失或错误时返回真实 HTTP 401；报告 fixture 可在完成一次 `get_status` 调用后、发送响应头前断开连接。测试读取 fixture 的拒绝和调用计数，以核对失效凭据及“结果未知”时没有自动重放。普通 `scripts/mcp/start-v2-test-mcp-servers.ps1` 启动不启用这些控制点。

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
pwsh -NoProfile -File scripts/ui/run-w18-ui-smoke.ps1 -AgentSlot 2 -GatewayPort 30618 -FrontendPort 35618 -UxV1AgentSoul -SkipServerBuild -DataDirectory local-tmp/slot-2-ux-v1-soul/data/ui-cold
pwsh -NoProfile -File scripts/ui/run-w18-ui-smoke.ps1 -AgentSlot 2 -GatewayPort 30618 -FrontendPort 35618 -UxV1AgentSoul -SkipBuild -DataDirectory local-tmp/slot-2-ux-v1-soul/data/ui-warm
```

`-SkipServerBuild` 只在本轮源码已经完成全仓门禁、exec JAR 已生成时使用。脚本固定 retries=0，将 trace、截图、前后端原始日志和 `flow-health.json` 保存到 `local-tmp/slot-2-ux-v1-soul/evidence/run-<时间戳>/`，结束后核对端口和子进程清理。trace 包含实际合成 SOUL 的浏览器流量；派生 JSON 只记录 hash、长度与 ID。命令和局部测试通过不自动结算阶段 PASS。

## W15 Skill 整包管理

Gateway 从 `third-party/deerflow/skills/manifest.json` 读取冻结 Skill 完整资源及来源许可哈希，交由已发布的 Haifa Agent 公开 Skill API 负责发现、激活和 Run Context。生产资源包含默认启用的 `consulting-analysis`，以及默认关闭的完整 `skill-creator`、`skill-reviewer`；测试用 `v2-format-control` 不进入生产配置。带脚本包启用前须取得配置的安全审查结果，精确绑定 SDK 包与注册 digest；该审查不授予脚本执行权限。旧版本资源必须保留，不可原位覆盖。

冻结前端使用 `GET /api/skills`、`GET/PUT /api/skills/{name}` 与 `POST /api/skills/reload`。启停状态按数据目录原子保存到 `skills_config.json`，只影响后续新 Run；`Agent.skills` 的 `null` 表示全部已启用 Skill，`[]` 表示不使用 Skill，名称列表为允许名单。启动 Gateway 时工作目录须为仓库根；模块测试可从 `super-harness-server/` 运行。缺包、哈希/许可漂移或无法读取 manifest 会拒绝接入，不会回落到 DeerFlow 后端。完整双 Skill HTTP/Run 断言与冻结 UI 两轮仍以 V2 W15/W18 验收记录为准。

用户包通过 `POST /api/skills/install/upload`（multipart 字段 `archive`，`.skill`，最多 100 MiB）或 `/api/skills/install`（当前 Thread 的 `thread_id`、`path`）安装。custom 路由支持读取/编辑/删除、`history`、`rollback`、`export-manifest` 和 `export?expected_revision=<revision>`；revision 过期返回 409，错误 JSON 或字段类型返回 422，超限请求返回 413。导入、管理发布与导出共用可移植路径冲突规则；版本与导出摘要保留空目录和规范化后的文件可执行标记，不恢复特权权限位，支持文件回滚按历史目标路径恢复原始字节。完整用户版本存于数据根 `skills/custom/.versions/`，当前版本、启停及 history 统一由原子发布的 `skills_config.json` 指定。vendor 资源只读，用户来源由 SDK USER scope 绑定本地用户；编辑后新 Run 使用新版本，旧 Run 保留已冻结的完整版本。普通关闭与审查授权撤销是两个操作；显式撤销后旧绑定的后续读取也由公开 SDK grant `matches()` 拒绝。

生产 `skill_manage` 沿用 SDK 文件写入审批；静态 `review_skill_package` 只读取已安装包、`inline://SKILL.md` 或当前 Thread `/mnt/user-data/` 内的归档/包目录，输出证据等级为 `static`，不会激活或执行目标。审查解释器通过进程变量 `HAIFA_SUPER_HARNESS_SKILL_PYTHON` 指定，必须支持 Python 3 和隔离模式 `-I` 下的 PyYAML；不读取用户 site-packages 或安装目标依赖。写入审查发现明确凭据时在模型调用前拒绝，其他待审文本以脱敏副本构造请求；语义筛查使用当前托管模型且不提供 Tools，缺模型、解析失败或脚本无法审查时拒绝发布，扫描期间取消或超时的 Run 也不得发布版本；无隐藏重试。安全筛查的模型/Provider、耗时、Usage 和输入文件 hash 记录于数据根 `skills/scanner/screen-receipts.jsonl`，不记录原内容或凭据。完整 creator 的脚本评测还依赖真实执行环境；单纯资源导入与静态 review 不证明评测通过。

运行 W15 定向测试或全仓门禁前，在**同一 PowerShell 会话**预检并设置静态审查解释器；普通 `python` 能导入 `yaml` 不代表隔离模式可用。本机冻结 reference venv 已验证 PyYAML 6.0.3。并行工作包占用 W05 旧测试的默认端口 `20618` 时，可把该测试绑定到已分配且实际可绑定的本工作树槽位端口，例如槽位 4 的 `11618`；正式 UI 与全仓门禁仍顺序运行。

```powershell
$env:HAIFA_SUPER_HARNESS_SKILL_PYTHON='D:/workspace/haifa-agent-super-harness/deer-flow/backend/.venv/Scripts/python.exe'
& $env:HAIFA_SUPER_HARNESS_SKILL_PYTHON -I -B -c 'import yaml; print(yaml.__version__)'
$env:HAIFA_SUPER_HARNESS_W05_TEST_GATEWAY_PORT='11618'
pwsh -NoProfile -File scripts/verify-full.ps1 -Forks 1 -LowMemory
```

W15 冻结 UI 回归从独立槽位运行，先在该 worktree 的 `frontend/` 用 `COREPACK_ENABLE_NETWORK=0` 执行 `corepack pnpm install --offline --frozen-lockfile`，确认版本为 `10.26.2` 且 `downloaded 0`，再在仓库根执行同一脚本两轮：

```powershell
pwsh -NoProfile -File scripts/ui/run-w18-ui-smoke.ps1 -AgentSlot 2 -GatewayPort 30618 -FrontendPort 35618 -V2Skill -DataDirectory local-tmp/slot-2-w15-v2-skill/data/ui-round-1
pwsh -NoProfile -File scripts/ui/run-w18-ui-smoke.ps1 -AgentSlot 2 -GatewayPort 30618 -FrontendPort 35618 -V2Skill -DataDirectory local-tmp/slot-2-w15-v2-skill/data/ui-round-2 -SkipBuild
```

第二轮 `-SkipBuild` 仅在首轮已按 Gateway `30618` 构建成功后使用。每轮脚本在结束时清理槽位进程，Playwright trace、截图及前后端/受控 Provider 日志写到 `local-tmp/slot-2-w15-v2-skill/evidence/ui/run-<时间戳>/`；测试需检查 `skill_load` ToolCall、启用后的模型 Context 和禁用后新 Run 的隔离。完整验收仍以开发报告的实际运行结果为准。

真实模型代表性链路使用同一 runner 的 `-W15LiveSkill`：托管 `deepseek-v4-pro`、`https://api.deepseek.com` 和 `os://DEEPSEEK_API_KEY`，验证生产 `skill_manage` 审批/编辑、后续新会话 `skill_load` 与完整资源读取、实际评测 JSON 的 Artifact 下载/hash 及只读 review。该用例会产生真实模型调用；脚本评测仍按 W14 的实际能力单独验收。独立额外槽位 4 的入口如下，运行前需设置上述扫描解释器变量：

```powershell
pwsh -NoProfile -File scripts/ui/run-w18-ui-smoke.ps1 -AgentSlot 4 -GatewayPort 11618 -FrontendPort 16618 -W15LiveSkill
```

同一份 `w15-package-differential.spec.cjs` 在冻结 DeerFlow 后端和 Haifa 后端分别运行，核对上传、开关、编辑、rollback、history 与删除。冻结参考后端在 Windows 缺少安全目录捕获所需的 POSIX 能力，导出 manifest 明确返回 `422 skill_export_unsupported`；用例将它记录为平台限制，不能计作导出通过。Haifa 后端另须通过全字节导出及过期 revision `409` 断言。参考端使用独立的 `11619` Gateway、`11628` 受控 Provider、槽位 4 的 `16618` 前端和独立 data-root；两端顺序运行，不能同时占用前端端口。参考入口由 Python 管理有界就绪、原始日志和精确进程清理，Haifa 入口沿用现有 UI runner：

```powershell
python -B tests/fixtures/w15/run_reference_ui.py --repo (Get-Location).Path --source D:/workspace/haifa-agent-super-harness/deer-flow
pwsh -NoProfile -File scripts/ui/run-w18-ui-smoke.ps1 -AgentSlot 4 -GatewayPort 11618 -FrontendPort 16618 -W15PackageDifferential
```

两轮完整受控 Skill UI 回归使用 `-V2Skill` 且每轮独立 data-root；实际 `flow-health.json`、trace、截图、Provider 原件及端口清理结果决定验收状态。独立参考差分与真实模型代表链均不代替本工作包的最终 SDK 基线和全仓门禁。

## 默认 Web 工具的环境代理

三个生产 Web 工具通过公开 SDK 的 `JavaToolSpec.networkAccess(hosts)` 声明 `NETWORK_ACCESS` 与逻辑服务主机；覆盖 endpoint 时主机元数据随实例配置变化，代理地址不进入逻辑服务名单。Bridge 仅对这三个生产工具显式配置 `autoApproveReadOnlyNetworkTools`，复用原策略 evaluator 并保留其它 MCP、文件写入、自定义 ASK/DENY 和危险工具的审批。主机元数据参与冻结 Catalog/Policy digest，SDK 不拦截工具自建 HTTP 客户端。上游交付与人工审查入口见 [SDK 消费记录](docs/todo.md)。

UX-V1 默认工具验收在独立工作树运行 `pwsh -NoProfile -File scripts/ui/run-w18-ui-smoke.ps1 -AgentSlot 2 -GatewayPort 30618 -FrontendPort 35618 -UxV1DefaultTools -DataDirectory <本工作树内的新数据目录>`。DDG 挑战仍在时，按人工指示追加 `-DeferUxV1Web`：A 文件/Artifact、B 澄清继续执行，C 搜索依赖链显式记为 NOT_RUN；延期或局部通过不能记为 M1 全部通过。源码、前端依赖和构建时代理目标相同时，第二轮可加 `-SkipBuild`，仍须使用新的 data-root、Run 与证据。原始 SSE、Run diagnostics/Usage、trace、截图及 `flow-health.json` 外置到该槽位 `local-tmp`；保留失败与首次卡点。

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
pwsh -NoProfile -File scripts/ui/run-w18-ui-smoke.ps1 -AgentSlot 3 -GatewayPort 40618 -FrontendPort 45618 -V2Diagnostics -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow -DataDirectory local-tmp/slot-3-w21/data/ui-cold
pwsh -NoProfile -File scripts/ui/run-w18-ui-smoke.ps1 -AgentSlot 3 -GatewayPort 40618 -FrontendPort 45618 -V2Diagnostics -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow -DataDirectory local-tmp/slot-3-w21/data/ui-warm -SkipBuild
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

需要避免开发模式首次打开聊天页时的动态编译，可先在本工作树按固定代理目标构建冻结前端，再用已验证的构建戳启动生产模式。构建失败不得写入构建戳；代理目标、冻结前端源码或 lockfile 变化后必须重新构建。

```powershell
$env:DEER_FLOW_INTERNAL_GATEWAY_BASE_URL='http://127.0.0.1:18018'
$env:NEXT_PUBLIC_STATIC_WEBSITE_ONLY='false'
$env:COREPACK_ENABLE_NETWORK='0'
Push-Location frontend
try {
    corepack pnpm build
    if ($LASTEXITCODE -ne 0) { throw 'Frozen frontend build failed.' }
} finally { Pop-Location }
. scripts/lib/build-stamp.ps1
Write-FrontendBuildStamp -RepositoryRoot (Get-Location).Path -InternalGatewayBaseUrl $env:DEER_FLOW_INTERNAL_GATEWAY_BASE_URL
pwsh -NoProfile -File scripts/start-main-super-harness.ps1 -ProductionFrontend
```

`-ProductionFrontend` 只使用构建戳与当前前端树、lockfile、代理目标及 `NEXT_PUBLIC_STATIC_WEBSITE_ONLY=false` 构建配置完全匹配的 `.next`；预检不符即拒绝启动。构建前必须设置该变量，避免把只读静态站点配置固化到客户端。该模式沿用相同的冻结来源、同源代理、身份与固定端口检查。默认启动方式仍为开发模式。

只需重建或重启 Gateway、而受保护的前端仍在运行时，先核对监听 `18018` 的 Java 进程并只停止该 PID，再运行相关 Maven 测试和 `mvn -o -ntp -pl super-harness-server -am package`，随后执行：

```powershell
pwsh -NoProfile -File scripts/start-main-super-harness.ps1 -GatewayOnly
```

此模式只检查并启动 Gateway，沿用原数据目录，不会启动第二个前端。确认 Gateway 健康后刷新浏览器。

代码合入后重启前，先用 `Get-NetTCPConnection -State Listen -LocalPort 18018,33018` 查明监听 PID，再用 `Get-CimInstance Win32_Process` 核对精确命令行；只停止确认属于主工作树的进程，然后重跑上述命令。禁止按进程名批量停止所有工作树中的 Java 或 Node。页面能打开仅证明服务就绪；真实模型、Tool 和双后端对齐仍需分别提供 Run/Playwright 证据。

### 客户演示 v0 doctor

客户演示固定 Oracle 位于 `contracts/customer-demo/customer-demo-v0-oracle.json`。在启动或派发演示任务前，先运行只读 doctor；它会核对冻结 DeerFlow 来源、Haifa Agent `0.1.2-SNAPSHOT` 本地 Maven 构件、主工作树端口及进程、同源模型目录、OS 凭据存在性和外置数据目录，但不会调用真实模型或读取凭据值：

```powershell
pwsh -NoProfile -File scripts/doctor/verify-customer-demo-doctor.ps1 `
  -OutputPath local-tmp/customer-demo-m0/doctor-live-read-only.json
```

只检查 Oracle、冻结来源和 SDK 构件时加 `-StaticOnly`。未获人工真实模型预算授权时，doctor 会将 `budget-authorization` 和整体结果保留为 `NOT_RUN`；不得仅为获得 `PASS` 擅自添加 `-BudgetApproved`，该开关只记录已经取得的明确人工授权，本身不会发起模型请求。脚本的正反合同测试为：

```powershell
pwsh -NoProfile -File tests/checks/customer-demo/verify-customer-demo-doctor.ps1
```

### 客户演示 v0 证据包与 fail-closed 门禁

客户演示两轮 Run 的原始证据由 `contracts/customer-demo/customer-demo-v0-evidence-bundle.json` 聚合，字段与字段约束见 `contracts/customer-demo/schemas/customer-demo-v0-run-manifest.schema.json` 和 `contracts/customer-demo/schemas/customer-demo-v0-evidence-bundle.schema.json`。它绑定 M0 Oracle 与其 SHA-256，记录主线/前端/SDK/config/input 哈希、模型/Provider（不含 secret）、预算、两轮的 Thread/Run/ToolCall/Interaction/Artifact ID、审批前后状态、Steer、网络目标、文件与下载 SHA、Usage、时间线、Playwright/日志路径、进程/端口清理，以及逐阶段原始证据引用和人工验收。每一轮 PASS 还必须提供仓库内真实下载字节文件（`downloadEvidence` 的 repo path + SHA，校验实际文件存在且实际 SHA 与字节长度分别等于声明的 `downloadSha256`/`downloadByteCount`）和 Artifact provenance 原件引用（`artifactProvenanceEvidence` 的 repo path + SHA）。为保证生产 `write_file` 生成的文件与 Artifact 下载字节一致，该轮 `fileSha256` 必须同时等于 `downloadSha256` 与该下载文件的实际 SHA；provenance 原件 JSON 除 `artifactId`/`runId`/`toolCallId` 外，还必须绑定 Oracle `story.tool`（`write_file`）、Oracle `story.artifact`（`sdk-owned-real-artifact`）、`downloadSha256` 与 `downloadByteCount`，校验器用传入的 OraclePlan 对照这些字段，只填字符串不再算 PASS。

以下命令只做只读校验与聚合，输出面向主控的机器 JSON 与简明汇总，不会启动服务、不会调用任何真实或付费模型，也不会写入 SHPM 或自动宣布阶段完成：

```powershell
pwsh -NoProfile -File scripts/evidence/verify-customer-demo-evidence.ps1 `
  -OutputDirectory target/customer-demo-evidence
pwsh -NoProfile -File tests/checks/customer-demo/verify-customer-demo-evidence.ps1
```

校验会拒绝缺轮次、重复 ID、两轮 SHA 或配置不一致、隐藏 retry、缺失首次失败、只报最佳轮、Artifact 无 provenance/下载 hash、缺失或漂移的真实下载字节（文件不存在、SHA 不符、字节数不符）、`fileSha256` 与 `downloadSha256` 或真实下载字节不一致、provenance 原件未绑定 Oracle tool/artifact 或 `downloadSha256`/`downloadByteCount`、provenance 原件 SHA 漂移、无参考回落证据、跳阶段、M4 无人工验收、自报 GO、GO 与 M4 SHA 不一致以及敏感值；结果严格区分 `PASS`/`FAIL`/`NOT_RUN`/`BLOCKED_EXTERNAL`/`UPSTREAM_UNSUPPORTED`：任一 stage 或 round `FAIL` 都会使 `customerDemo.status` 与 `overallStatus` 变为 `FAIL`，`BLOCKED_EXTERNAL`/`UPSTREAM_UNSUPPORTED` 在机器摘要中单独列出，绝不折叠成 `NOT_RUN`。当前 bundle 为全阶段 `NOT_RUN`、演示决策 `NO_GO`，只有当 M0–M4 全部 PASS、两轮在同一主线 SHA、同一模型/配置/数据根上连续完成且有人工验收时才会接受 `GO`；可用 `-RequireGo` 把“尚未 GO”升级为失败，供 M4 最终门禁使用。

### 当前主工作区 Utility MCP 体验（2026-09-30）

主 Gateway `18018` 已部署 main@530dfb2 的验收 JAR，冻结前端仍为 `33018`。已启用 `haifa-utility`，连接 `http://127.0.0.1:8091/mcp`，19 个工具来自实际 MCP 发现，协议 `2025-06-18`；在能力中心的 Plugins 页面可查看，在 Agent 的 MCP 选择中可启用。新会话可输入「请实际调用 MCP calculate 计算 23 * 19」，出现审批后点击 Approve。

Utility 使用另一项目 `D:/workspace/haifa-ai-deerflow/utility-mcp-server` 的已有编译产物和离线 runtime classpath，以单 Java 进程运行（`-Xmx384m -XX:+UseSerialGC`，loopback 8091），避免常驻 Maven。运行清单、启动/停止脚本和部署原件在 `local-tmp/main-utility-preview-20260930/`。仅停止此 Utility 服务可运行 `pwsh -NoProfile -File local-tmp/main-utility-preview-20260930/start-utility.ps1 -Stop`；之后用同脚本不带 `-Stop` 恢复。脚本依赖已生成的 classpath 与 Utility target/classes，不删除这些文件。主服务数据及 continuation key 保持原样，本次连接不代表 V2 阶段通过。


新增 UI 验收从 `tests/ui/smoke/w05-demo-artifact.spec.cjs` 起步：该黄金样例使用生产 `write_file`、真实批准卡片、Artifact 下载及刷新重连。共享 `fixtures.cjs` 提供 runner 环境、进入工作区、Run 状态等待、当前批准卡片与截图/JSON/下载字节归档；默认 smoke 使用同一 fixtures，保留 4 个用例。

### W08 独立目录 UI 验收

`-W08Catalog` 选择 `tests/ui/smoke/w08-subagent-catalog.spec.cjs` 的 8 个用例，使用未修改的冻结设置页与真实 `/api/subagents`：内置条目不可编辑、表单创建及刷新、完整编辑表单、单独 enabled 局部 PUT、非法名称禁止提交、重名 409、删除 204，以及显式模型和长合法名称的保存。每个用例使用独立条目，编辑/删除等前置通过真实 REST 建立；不拦截请求、不模拟后端。显式模型保存不代表 Child 模型执行，长目录名称不替代 UI-14 的 task 标题验收。

主控确认完整槽位和 SDK 选择后，先执行受影响单 case：

```powershell
pwsh -NoProfile -File scripts/ui/run-w18-ui-smoke.ps1 `
  -W08Catalog -AgentSlot 1 -GatewayPort 20618 -FrontendPort 25618 -ProviderPort 0 `
  -CaseTitle 'W08 UI-02 creates a managed subagent and survives reload'
```

`-CaseTitle` 必须与 spec 中的静态标题完整一致；`-PreflightOnly` 只做预检。完整目录轮次去掉 `-CaseTitle`，同一稳定源码/SDK 上运行两轮，每轮使用新的 `-DataDirectory`；只有构建戳一致时第二轮可使用 `-SkipBuild`。该 profile 与其他 W18 profile 互斥，沿用 runner 的端口、依赖、Provider、构建和进程清理，证据位于 `local-tmp/slot-1-w08-catalog/evidence/run-<时间戳>/`，包含 trace、截图、真实请求响应、前后目录和流程健康度。未执行的轮次保持 `NOT_RUN`。

目录 profile 只覆盖 W08 UI-01–05及补充保存用例，不发送委托 Run。Ultra 的真实 thinking/plan/subagent 载荷须另行验收：最新 W03 已接通思考执行，但 W07 plan/pro/ultra 门禁及 UI 能力发布前置仍需满足；被阻断的委托 UI 用例保持 `NOT_RUN`。本 profile 不改变 Task4 Oracle12 的既有 `FAIL`，也不声明整个 Task5 或 W08 完成。

`scripts/ui/run-w18-ui-smoke.ps1` 通过 `scripts/lib/run-fixture.ps1` 管理完整槽位绑定预检、脱离调用方管道的启动、15/30/60 秒 Provider/Gateway/前端就绪等待及精确 PID 清理。所有模式的证据写入 `local-tmp/slot-<N>-<任务标签>/evidence/run-<时间戳>/`，包含原始日志、`managed-processes.json`、`flow-health.json`、`case-results.json` 和 Playwright 原件；冷暖轮分别建目录和数据，不覆盖首次失败。清理必须在原停止预算内实际绑定端口成功，失败时 runner 返回非零。MCP 启动器使用同一脱离管道的启动函数；共享库只转述 case 结果，不登记 SHPM 或提升验收状态。

# Haifa Super Harness

本仓库是冻结 DeerFlow 对齐项目的独立 Java 21 产品工程。项目只通过 Maven 仓库中已发布的
`io.haifa:haifa-agent-bom:0.1.2-SNAPSHOT` 及公开 JAR 消费 Haifa Agent，不依赖 Haifa Agent 源码目录。
Super Harness 直接复用冻结的 DeerFlow 前端，并以 Haifa Agent 作为唯一 Agent Runtime，兼容其真实
REST/SSE/WS/文件/auth 协议。冻结 DeerFlow 固定为
`bytedance/deer-flow@e545c28ac301e6f9feae247c513d8ea8f83df294`，不随 `main` 漂移。

## 架构边界

Super Harness 是产品、兼容与集成层，不是第二套 Agent Runtime。可复用的 Runtime 能力（Run/Loop、
Memory、Tool/Skill、MCP、Sandbox、Approval、Artifact、Checkpoint、Parent/Child 等）归 Haifa Agent；
Super Harness 负责冻结 DeerFlow 协议映射、产品语义、UI/BFF、业务集成与控制面展示。新增基础能力前须按
[Runtime / Product Boundary](docs/runtime-product-boundary.md) 做归属判断；如果缺的是通用 Runtime 能力，
应先补 Haifa Agent 公开 API/SPI，再由本项目消费。

## 模块

- `super-harness-app`：最小应用基础，验证公开 SDK 的 `Run → Java 类型化 Tool → Artifact` 链路。
- `super-harness-events`：事件投影核心，把 SDK Run 事件投影为冻结 UI 可消费的兼容事件。
- `super-harness-workspace`：受限工作区与文件快照，承载 Run 变更的发布与恢复。
- `super-harness-server`：DeerFlow 兼容网关，承载 HTTP/REST/SSE/WS 协议、BFF 目标与全部产品能力。
- `super-harness-harness`：最小化、失败即关闭的证据校验器；必需用例缺失、显式失败或标为 PASS 却无证据时校验失败。

## 能力概貌

- **DeerFlow 兼容网关**：`super-harness-server` 提供冻结前端所需的 REST/SSE/WS/文件/身份接口，并作为
  BFF 的唯一下游；不自研平行 UI，也不回落 DeerFlow 后端。
- **Lead 系统提示词装配**：从 `resources/upstream/deerflow/prompts/` 读取冻结原文和有序适配清单，校验
  资源 SHA，通过公开 `ProductProfile` 装配系统指令；渲染中提及未注册工具、资源漂移或超长时拒绝创建 Run。
- **Agent 身份与 SOUL**：有身份的 agent 将已保存 SOUL 装配到 Lead 指令并声明 `update_agent`；新建流程使用
  `setup_agent`。两个工具通过 `autoApproveStandardFileWriteTools` 仅豁免自身标准写审批，普通 `write_file`
  等仍需审批。
- **子智能体（Parent/Child）**：`GET/POST /api/subagents`、`PUT/DELETE /api/subagents/{name}` 管理目录定义；
  生产 Run 在 `context.subagent_enabled=true` 时声明 SDK Child 与 `task`。容量、超时、深度与终止由 SDK 拥有，
  子任务步骤通过公开 `RunMessage*` API 投影。Child 明确关闭继承模型的 thinking，并通过公开 SDK Profile
  选择接受非空普通 `LENGTH` 输出；原输出 token 与时间限制不变。封顶结果保留真实 Usage、
  `TRUNCATED:LENGTH` 警告及 Parent Tool 结果元数据，Parent 自身仍采用严格截断策略。
  同一 Run 的多个 SSE 观察者分别接收完整 Tool 请求与结果；连接内按消息身份去重，重连使用独立的投影游标。
  游标已过期时返回 `stream_replay_gap`，按 `reload_durable_state` 恢复权威状态后再从可用游标读取。
- **Run 预算与限制**：主对话 Run 的结构限制对齐冻结 DeerFlow 默认值（`config.example.yaml` 的
  `recursion_limit: 100`、`max_recursion_limit: 1000`、`token_budget.enabled: false`）：迭代 100 轮、
  模型调用 100 次、工具调用 1000 次、墙钟基数 3600000 毫秒（委托开启时按现有算法叠加「子 Run 总数 × 子 Run
  最大超时」）、空闲超时 600000 毫秒；输入、输出、缓存输入 token 与费用预算不限（SDK `QuotaMode.DISABLED`）。
  已知差异：冻结 DeerFlow 有循环检测（同一调用重复 5 次或单个工具累计 50 次强制收尾），Super Harness 目前
  没有，失控的 Run 会运行到 100 轮迭代上限。
- **Skill 平台**：默认从 `third-party/deerflow/skills/manifest.json` 读取冻结 Skill 资源，也可显式加载外部目录；`GET /api/skills`、
  `GET/PUT /api/skills/{name}`、`POST /api/skills/reload` 管理启停，用户包通过 `/api/skills/install/upload`
  或 `/api/skills/install` 安装。带脚本包启用前须获得安全审查结果；静态 `review_skill_package` 只读且不执行目标。
  目录配置按 JVM 属性 `haifa.superharness.skills.dir`、进程变量 `HAIFA_SUPER_HARNESS_SKILLS_DIR`、
  `<data-root>/skills_config.json` 的 `external_directory` 顺序选择；持久化配置须为绝对路径，配置变化后重启 Gateway。
  目录可指向 `skills` 或 `skills/public`，不自动发现邻近检出。目录缺失、链接、无效包或重复名称会拒绝加载；
  包版本按完整原始文件摘要生成，文件变化会使旧授权失效。带脚本包默认关闭，启用时执行现有静态与语义安全审查。
  2026-10-05 人工明确授权冻结来源的 23 个 W16 包跳过安全审查并启用；此授权按 SDK registration/package 摘要落盘，
  标记为 `human`，不生成 scanner PASS，也不对其他目录或新版本自动放行。
  已发布的外部包原始文件保留在 `<data-root>/skills/external/.versions/`，供 SDK 恢复旧 Run；缓存被改动时拒绝加载。
  W16 将冻结来源的 23 个公共包导入目录；技能成果依赖各自模型、工具、凭据和外部服务，导入不代表逐项成果验收通过。
- **MCP 平台**：`/api/mcp/config` 管理 `mcp_servers`，按 allowlist 投影为公开 `McpServerSpec`，连接、发现、
  命名、调用与释放由 SDK 拥有。`read_only` 的 server 工具走 SDK 只读网络预设并由 Bridge 自动放行；非
  `read_only` 的 MCP 工具保留强制审批。
- **默认 Web 工具**：三个生产 Web 工具通过 `JavaToolSpec.networkAccess(hosts)` 声明 `NETWORK_ACCESS` 与逻辑
  服务主机，仅它们被 `autoApproveReadOnlyNetworkTools` 豁免；代理配置见下文。
- **模型与思考配置**：通过公开 `ModelReasoningPolicy` 接入思考开关与推理级别；Agent 的 `model_settings`
  冻结到新 Run，同型号参数变化通过配置 digest 重建下一 Turn 的 Bridge。
- **运行诊断与用量投影**：`GET /api/threads/{thread_id}/runs/{run_id}` 的 `diagnostics` 与
  `GET /api/threads/{thread_id}/token-usage` 只读聚合公开 SDK 事实；无法证实的历史信息返回 `null`，
  不猜测、不从当前配置推断。
- **后续建议与标题**：`GET /api/suggestions/config` 暴露全局配置（`HAIFA_SUPER_HARNESS_SUGGESTIONS_ENABLED`、
  `HAIFA_SUPER_HARNESS_SUGGESTIONS_MAX`）；线程标题按冻结回退规则生成，可在 Run 之间切换模型。
- **冻结前端与 BFF**：直接复用冻结 DeerFlow 前端，Next BFF 只指向本槽位 Gateway；启动时校验冻结来源、
  工具链版本与同源代理目标。

## 目录结构

- `super-harness-*/`：Java 模块，见上。
- `scripts/`：常用入口 `test-targeted.ps1`、`verify-full.ps1`、`start-main-super-harness.ps1`，其余按前端、
  协议、资源、doctor、UI、MCP、证据与治理分类，共用函数在 `scripts/lib/`。完整说明见
  [scripts/README.md](scripts/README.md)。
- `tests/`：`tests/checks/` 保存脚本与校验器回归，`tests/ui/` 按 `smoke/`、`files/`、`dual-backend/`
  保存浏览器用例与夹具；Java 测试在各模块 `src/test/java/`。说明见 [tests/README.md](tests/README.md)。
- `docs/`：稳定的开发与使用手册。
- `contracts/`、`third-party/`、`resources/`、`frontend/`、`deer-flow/`：冻结协议、资源、前端与来源校验。
- `private-docs/`：提示词、roadmap、评审与交付报告，正文不在本 README 展开。

旧 worktree 分支与 `main` 历史不连通时，可用
`python scripts/governance/audit_disconnected_branches.py` 批量核对内容：先验证新旧主线的完整文件树桥接，
再检查祖先关系、精确文件变更和内容/模式，输出已确认项与待核对差异。脚本只读 Git，不调用模型或删除分支；
参数、判定边界和复现命令见 [脚本目录的治理说明](scripts/README.md#governance--shpm-治理与门禁建议)。

## 构建与验证

使用 Java 21 和 Maven 3.9.15，或使用仓库提交的 Maven Wrapper（固定 Maven 3.9.15 分发包 SHA-256）。

```powershell
.\mvnw.cmd -o -ntp verify   # Windows；Linux/macOS 使用 ./mvnw
mvn -o -ntp verify
./build-support/verify-clean-consumer.ps1
```

前两条使用配置好的本地 Maven 仓库做完全离线构建；Wrapper 首次需联网下载一次分发包，之后可离线复用。
`verify-clean-consumer.ps1` 从空缓存证明构建未使用 `D:\workspace\haifa-agent` 或任何 Reactor/源码路径依赖。
冒烟输出 `W01_SDK_EVIDENCE`，包含真实 Run ID、Tool 别名、Artifact ID、Artifact SHA-256 和字节数。

全仓门禁：

```powershell
pwsh -NoProfile -File scripts/verify-full.ps1
```

先跑秒级预检（SDK 基线门禁、`git diff --check`、对 main 以来改动的 secret scan），失败即输出
`FAIL_PREFLIGHT` 且不启动 Maven。预检通过后按提交的 Java 输入指纹（排除 `docs/`、`tests/`、`scripts/`、
根目录 `*.md` 后的源码树，加每个 Haifa JAR 的 SHA-256 身份及 JDK/Maven 版本）查找本 worktree 内同指纹的
真实 PASS 摘要：找到就直接复用、摘要带 `reuse` 来源，`-NoReuse` 强制真跑；否则运行
`mvn -o -ntp clean verify`，摘要 JSON 写到 `local-tmp/verify-full/`，并为产出的 exec JAR 写入服务端构建戳。
失败时从 surefire 报告读出 `failedTests`；只命中 `tests/known-intermittent.json` 且未触及条目 `covers` 时
按已登记偶发失败分支重跑失败的测试类 3 次并补完构建，摘要带 `knownIntermittent` 与重跑次数，
`-NoKnownIntermittent` 关闭该分支。不传 `-Forks` 时按当时空闲物理内存与空闲提交内存自动选择 3 个
并行进程或 1 个进程（空闲物理内存不低于 4096 MB 且空闲提交内存不低于 6144 MB 时用 3 个，否则 1 个并自动加 `-LowMemory`）；显式 `-Forks` 或 `-LowMemory` 行为不变，内存探测失败回退默认 3 个进程。
2026-10-05 实测：1 进程低内存约 811 秒、3 进程约 250–380 秒；各进程数的内存峰值相近（3–4 GB），
所以不设 2 进程一档。

UI runner 的服务端构建戳同样以 Java 输入指纹比较：只改文档、脚本、测试或 UI 用例的提交不再使 JAR 失效，
`-SkipServerBuild` 可继续复用并报告 `REUSED`；`flow-health.json` 记录 `sourceTree` 和 `sdkIdentity` 供合入后
按内容核对。合入后用 `python scripts/governance/evidence_reuse.py --target <合入后提交> <证据>` 逐份判断
是否 `REUSABLE`。

前端构建戳只含冻结前端源码树、lockfile 和代理目标，不含仓库 HEAD：匹配就复用本地构建，否则从主检出的
共享缓存 `local-tmp/shared/frontend-build/` 恢复（键见 `scripts/lib/frontend_build_cache.py`），两者都没有才
执行 `pnpm build` 并存入缓存；`flow-health.json` 的 `serverBuild`/`frontendBuild` 记录
`REUSED`/`RESTORED`/`BUILT`。`-SkipServerBuild`/`-SkipBuild` 强制复用，构建戳不符时失败。冻结前端来源校验在
源码与 DeerFlow 检出未变时复用本 worktree 的上次 PASS（`local-tmp/cache/frontend-source-gate/`，键见
`scripts/lib/frontend_gate_cache.py`）。全仓门禁并行进程数的选择依据见 `scripts/lib/gate_resources.py`，摘要含
`forksSelection`/`lowMemory`/`freePhysicalMb`/`freeCommitMb`。每轮 UI 仍使用独立数据与证据。

脚本回归入口统一运行 `tests/checks/build/` 与 `tests/checks/ui/` 清单内的检查，摘要写到
`local-tmp/verify-scripts/`：

```powershell
pwsh -NoProfile -File scripts/verify-scripts.ps1
```

## 定向测试与 SDK 版本选择

普通生产 HTTP 测试通过 `GatewayFixture.productionEnvironment` 使用显式 `env://GATEWAY_FIXTURE_CONTINUATION_KEY` 测试密钥，避免为临时目录向 Windows Credential Manager 累积凭据；显式传入的凭据引用原样保留。凭据首次配置、丢失与重启语义由 `ContinuationKeyConfigurationTest` 单独验证。

开发内环使用 `scripts/test-targeted.ps1`，固定带 `-o -ntp -pl super-harness-server -am`（`-Module` 可换模块），
Maven 输出写到 `local-tmp/test-targeted/`，stdout 只输出一份 JSON 汇总（命令、退出码、tests/failures/errors/
skipped、耗时、日志路径）。匹配 0 个测试算失败；不接受 `clean`、`install`、`-DskipTests`、`-Dhaifa.version`
等额外参数。

```powershell
pwsh -NoProfile -File scripts/test-targeted.ps1 -Test LocalServerConfigurationTest
pwsh -NoProfile -File scripts/test-targeted.ps1 -Test 'AgentSoulPromptTest,UpdateAgentToolTest' -LowMemory
pwsh -NoProfile -File scripts/test-targeted.ps1 -Test DeerFlowCreatedChildZeroExecutionHttpTest -LowMemory
```

`W05LiveValuesStreamContractTest` 在 fixture 证据目录保存实际 HTTP/SSE 原件。将这些原件路径写成 JSON 数组后，
可用冻结前端消费库核对直播与终态消息身份、Steer 输入、重载和任务卡片：

```powershell
python -B tests/checks/contracts/verify_w05_live_output_consumer.py --inputs <原件路径数组.json>
```

此回归只证明真实 wire 与冻结消费库的集成，浏览器验收继续使用 UI runner。
输入需覆盖普通输出、Tool/Child 和 Steer 的实际直播记录；仅含 `end` 的已确认终态重连由 HTTP 回归单独核对。
`-LiveDeepSeek` 还包含真实长回答用例：在 Run 仍运行且尚无已提交助手消息时观察两次文本增长，
并核对结束与重载后的单条用户消息和完整回答；使用已托管凭据，不修改 Provider 响应。

固定耗时很长的用例标注 `@Tag("slow")`，默认门禁不运行。用 `-Slow` 只运行慢测试，可省略 `-Test` 运行全部，
加 `-Test <类>` 只跑该类的慢测试：

```powershell
pwsh -NoProfile -File scripts/test-targeted.ps1 -Slow
pwsh -NoProfile -File scripts/test-targeted.ps1 -Slow -Test DeerFlowTaskDelegationApiTest
```

Oracle16 的 `DeerFlowCreatedChildZeroExecutionHttpTest` 使用公开 SDK 的测试装配入口，确定性保留真实 Child
执行任务，覆盖 Parent HTTP 取消及实际 SDK 墙钟到期、零物理执行、HTTP/SSE 和同数据目录重启；
超时夹具仅在测试装配时声明 2 秒 Parent 墙钟限制，不提交模拟超时的取消指令，生产限制与虚拟线程执行器保持默认值。
测试的任务释放与关闭使用有界等待，原始 HTTP/SSE 和安全的 Parent/Child/Task ID 摘要保存至夹具证据目录。

SDK 版本规则见 `AGENTS.md`「范围与依赖」。工作包 worktree 消费带标识的 SDK 版本时，在该 worktree 写入不被
跟踪的 `local-tmp/sdk-selection.json`：

```json
{ "haifaVersion": "0.1.2-<worktree>-SNAPSHOT" }
```

`test-targeted.ps1` 与 UI runner 读取它并传 `-Dhaifa.version`；文件不存在时使用基线并输出
`sdkSelection=ABSENT`。直接运行 `mvn` 不读取该文件，仍使用基线。

需要把 SDK 公开类型事实交给执行者时，主控运行 `python scripts/governance/sdk_api_digest.py --worktree <worktree>`
生成只含 public/protected 签名的摘要到 `local-tmp/sdk-api/<版本>/`；执行者读该摘要而不访问本地 Maven 仓库，
SDK 重新发布后重跑脚本。

流水线辅助脚本：

```powershell
python scripts/governance/change_scope.py
python scripts/governance/evidence_reuse.py --target <合入后提交> <证据文件或目录>
python scripts/governance/pr_review_digest.py --repo <owner/name> --pr <编号>
python scripts/governance/shpm_dispatch.py --task ... --execute
python scripts/governance/shpm_record_gates.py --task ... local-tmp/verify-full/<run>.json <UI 证据目录> --execute
python scripts/lib/source_fingerprint.py --repo . --commit HEAD
python -m unittest discover -s tests/checks/build -p "test_*.py"
```

两个 SHPM 脚本不带 `--execute` 时只打印将执行的命令。提交前的 SDK 基线门禁：

```powershell
pwsh -NoProfile -File tests/checks/build/verify-haifa-version-baseline.ps1
```

## 冻结前端本地准备与启动

每个验收 worktree 使用独立的 `frontend/node_modules`、`.next`、端口和数据目录；冻结来源按固定 DeerFlow
提交校验。先确认 Corepack pnpm 版本精确为 `10.26.2`，再离线安装：

```powershell
$env:COREPACK_ENABLE_NETWORK='0'
corepack pnpm --version
corepack pnpm install --offline --frozen-lockfile
```

安装输出须为 `downloaded 0`。以下命令只校验冻结前端、已记录的最小 BFF 补丁、仅连接 Haifa 的 loopback
配置与固定 Node/pnpm 工具链，不启动服务：

```powershell
pwsh -NoProfile -File scripts/frontend/start-haifa-frontend.ps1 `
  -DryRun -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow
pwsh -NoProfile -File tests/checks/frontend/verify-haifa-frontend-profile.ps1
```

### W08 真实委托 UI profile

`scripts/ui/run-w18-ui-smoke.ps1` 新增 `-W08LiveDelegation` 开关，运行 `tests/ui/smoke/w08-delegation-live.spec.cjs`
的三个真实模型用例（UI-06 单 Child 完成+刷新、UI-09 两个物理并发 Child、UI-10 运行中停止+终态刷新）。
该开关自动复用真实 DeepSeek 托管模型（等效 `-LiveDeepSeek`），会真实调用 `https://api.deepseek.com`，
仅限已授权轮次、独立 data-root，且不与其他 profile 并用：

```powershell
pwsh -NoProfile -File scripts/ui/run-w18-ui-smoke.ps1 `
  -AgentSlot 1 -GatewayPort 20618 -FrontendPort 25618 `
  -W08LiveDelegation -SourceRepository <冻结 DeerFlow checkout 的绝对路径> `
  -DataDirectory local-tmp/slot-1-w08-real-profile/data/<run>
```

每个 case 超时 120 秒，Child `timeout_seconds=90`；保持产品的 2048 tokens 输出预算，runner 不改写这些限制。

## 启动主工作树供人工验收

主工作树固定使用本地回环端口：Gateway `18018`、冻结前端 `33018`。默认数据目录为被 Git 忽略的
`local-tmp/main-service-data`（含持久化 SQLite Runtime 与日志）。重启时保持数据目录不变，不要删除该目录或
Windows 凭据管理器中的 continuation key。先核对监听 PID 与命令行，只停止确认属于主工作树的进程。

```powershell
mvn -o -ntp clean verify
pwsh -NoProfile -File scripts/start-main-super-harness.ps1
```

脚本校验冻结 DeerFlow 来源和前端工具链后，启动 Gateway 与受保护前端，在有界超时内等待 `/health`、
`/api/v1/auth/me` 与前端就绪，最后输出访问地址、精确 PID、数据目录与日志路径。人工验收入口为
`http://127.0.0.1:33018/`。端口占用时脚本拒绝启动，不结束未知进程；加 `-Build` 可先执行离线 Maven Reactor
`package`；`-JarPath` 指定要运行的 exec JAR，`-FrontendRoot` 指定前端所在检出。

主线合入后刷新固定端口服务用 `scripts/refresh-main-service.ps1`：只读检查用 `-Status`；刷新用
`-Jar <exec JAR> -ExpectSha <提交> [-FrontendRoot <检出>] [-LiveSmoke]`，会校验 JAR 旁的构建戳（提交相同，或
在 `--worktree` 仓库里两个提交的 `^{tree}` 相同，squash 合并后仍可复用）、复制到
`local-tmp/main-service/<短 SHA>/` 并记录 `service.json`、只按精确 PID 停止命令行匹配的旧 Gateway、等待端口
释放、脱离调用者启动复制的 Gateway。`service.json` 没有可用 `active` 时，被停止进程命令行里的 JAR 会作为
回退目标。前端按 `--frontend-root` 的 `HEAD:frontend` 树与 `--expect-sha` 的 `frontend` 树比对：健康则复用，
不健康且端口空闲则用该检出的 `scripts/frontend/start-haifa-frontend.ps1` 以生产模式启动；端口被别的进程占用
则失败且不停止它。`-LiveSmoke` 取 `GET /api/models` 的第一个可执行模型发起一次最短 Run，要求有非空 AI 文本且
无 `error` 帧。整体 `PASS` 要求 Gateway 就绪、smoke、前端和 live-smoke 全部通过；Gateway 就绪但后续检查失败只
记为 `FAIL`，不回退也不停止 Gateway。摘要写 `refresh-<时间戳>.json`。不带回归开关时只接受 `18018`/`33018`
与固定数据目录。

- 只重启 Gateway（受保护前端仍在运行）：先核对并只停止监听 `18018` 的 PID，再执行

```powershell
pwsh -NoProfile -File scripts/start-main-super-harness.ps1 -GatewayOnly
```

- 生产模式前端（避免开发模式首次打开聊天页的动态编译）：先设置
  `DEER_FLOW_INTERNAL_GATEWAY_BASE_URL='http://127.0.0.1:18018'` 与 `NEXT_PUBLIC_STATIC_WEBSITE_ONLY='false'`，
  在 `frontend/` 执行 `corepack pnpm build` 并用 `scripts/lib/build-stamp.ps1` 写入前端构建戳，再用
  `start-main-super-harness.ps1 -ProductionFrontend` 启动；预检不符即拒绝启动。

## 本地登录 profile

默认启动保持免登录：Gateway 与前端都设置 `DEER_FLOW_AUTH_DISABLED=1`，`/api/v1/auth/me` 仍返回合成管理员。
本地登录是另一条 profile，必须显式传入独立于 `local-tmp/main-service-data` 的 `-DataDirectory`，并使用独立端口，不占用主工作树的 `18018`/`33018`。已有免登录 Runtime 或 `owners/default`、且还没有 `auth/users.json` 的目录会被拒绝：

```powershell
pwsh -NoProfile -File scripts/start-main-super-harness.ps1 -LocalAuth -AllowSlot `
  -GatewayPort 40618 -FrontendPort 45618 `
  -DataDirectory local-tmp/slot3/local-auth-data
```

该 profile 设置 `HAIFA_SUPER_HARNESS_LOCAL_AUTH=1`，清掉 `DEER_FLOW_AUTH_DISABLED`，并把
`HAIFA_SUPER_HARNESS_AUTH_ALLOWED_ORIGINS` 设为该槽位的前端 Origin。浏览器 Origin 只和这个允许列表比较。
空 data-root 会打开初始化页。账号、口令和会话写在该 data-root 的 `auth/` 下，不写进 Runtime SQLite。

## 默认 Web 工具的环境代理

在启动 Gateway 的同一 PowerShell 会话配置进程级变量，然后启动或重启 Gateway，优先使用产品键：

```powershell
$env:HAIFA_SUPER_HARNESS_TOOL_HTTP_PROXY='http://<代理主机>:<端口>'
$env:NO_PROXY='127.0.0.1,localhost'
```

产品键非空时覆盖通用代理变量、`NO_PROXY` 与 JDK 默认代理；未配置产品键时按协议读取 `HTTP_PROXY` /
`HTTPS_PROXY`，再回退 `ALL_PROXY` 与 JDK 默认选择器（通用变量小写形式优先）。代理地址使用
`http://host:port`，HTTPS 目标经 CONNECT 转发；不支持 SOCKS、HTTPS 代理地址或代理认证。`NO_PROXY`
使用逗号分隔的主机/域名，不使用通配符或 CIDR。只设置启动进程的环境变量，不自动读取 WinINET，不修改用户、
系统或 JVM 全局代理。Web 工具只消费这些变量，不改变 MCP、模型 Provider 或审批配置。

## 项目管理（SHPM）

项目的任务状态、派工、checkpoint、验收证据和客户演示门禁由 SHPM（Super Harness Project Manager）维护，
人工可在 http://127.0.0.1:8787/ 只读查看；只有主控通过 `shpm` 写入，子智能体不写 SHPM。提示词、roadmap、
交付报告和原始证据正文仍在 `private-docs`、`contracts` 与各 worktree 的 `local-tmp`，SHPM 只保存路径。
SHPM 的治理侧交付任务上限为 32（人工 2026-10-09 决策）；已登记的任务 `prompt_path` 接受仓库内相对路径（按仓库根解析）
和绝对路径（按原路径解析），缺失路径严格失败。
证据聚合回归只在 SHPM 导出副本内建立受控演示和派工基线；原始导出与真实审计保留各自状态，不修改真实数据库。
环境准备和完整操作流程见 [SHPM 使用手册](docs/shpm-manual.md)。

```powershell
$env:SHPM_DB='C:\Users\wangr\.shpm\data\project.db'
pwsh -NoProfile -File scripts/governance/audit-work-package-delivery.ps1
```

## 相关文档

- [Runtime / Product Boundary](docs/runtime-product-boundary.md)
- [SHPM 使用手册](docs/shpm-manual.md)
- [scripts/README.md](scripts/README.md)、[tests/README.md](tests/README.md)
- [W08 委托兼容手册](docs/w08-delegation-transcript.md)
- [验收 .cjs 脚本功能与运行说明](docs/engineering/001-cjs-scripts.md)
- [功能交付流程（纯产品与跨 SDK 协同）](docs/engineering/002-feature-delivery-workflows.md)：最终门禁前 rebase，按改动范围和 Oracle 验收；SDK 切回基线后重新构建并回归，再由主控合入与结算。
- [验收交接模板](docs/acceptance-handoff-template.md)
- [SDK 消费记录](docs/todo.md)

同一模型轮次的并行 Tool 结果按原请求顺序向各 SSE 观察者投影，实时与重放使用同一规则；Child 生命周期事件仍独立推进。

W08 参考差分使用 `scripts/ui/run-w18-dual-backend.ps1 -Backend reference -W08Catalog` 或 `-W08Delegation`，指定冻结 `-SourceRepository` 和已核验的 `-ReferencePython`；使用槽位 3 的 40618/45618/46618 全端口组和每轮新的 `-DataDirectory`，只作为参考验收，不替代 Haifa 后端。受控委派与真实模型 UI 门禁分开登记。

参考 W08 进行中 SSE 可显式加 `-ReferenceIdentityEncoding`，通过请求 `Accept-Encoding: identity` 避免压缩缓冲；此参数只允许参考 W08 profile，不改冻结来源、响应或模型请求。已逐项人工批准的参考差异在 SHPM 登记：UI07 结束前验真实 SSE/卡片步骤，结束后仍验持久化、精确前缀和重载；UI15 验真实写入的字节及无审批；UI10 用原生事件/状态替代不存在的 Haifa 专属字段，其 Child HTTP 取消缺陷保留 `FAIL`、后续依赖断言保留 `NOT_RUN`，按人工裁决作为非阻断参考风险。Haifa 对应全部严格断言保留；其余参考失败不能据此豁免，脚本不自动提升阶段结果或 `GO`。

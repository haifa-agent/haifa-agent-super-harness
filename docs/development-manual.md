# 开发与运行手册

本手册集中说明构建、定向测试、SDK 版本选择、主工作树人工验收启动，以及能力配置与限制。
以下命令均从仓库根目录执行；[冻结前端依赖准备](../README.md#冻结前端本地准备与启动)与[本地登录 profile](../README.md#本地登录-profile)见 README，交付与验收分层遵循 [AGENTS.md](../AGENTS.md)。

- [构建与验证](#构建与验证)
- [定向测试与 SDK 版本选择](#定向测试与-sdk-版本选择)
- [启动主工作树供人工验收](#启动主工作树供人工验收)
- [能力配置与限制](#能力配置与限制)

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
{ "haifaVersion": "0.1.4-<worktree>-SNAPSHOT" }
```

`test-targeted.ps1` 与 UI runner 读取它并传 `-Dhaifa.version`；文件不存在时使用基线并输出
`sdkSelection=ABSENT`。直接运行 `mvn` 不读取该文件，仍使用基线。

需要把 SDK 公开类型事实交给执行者时，主控运行 `python scripts/governance/sdk_api_digest.py --worktree <worktree>`
生成只含 public/protected 签名的摘要到 `local-tmp/sdk-api/<版本>/`；执行者读该摘要而不访问本地 Maven 仓库，
SDK 重新发布后重跑脚本。

流水线辅助脚本（验收分 **L1 日常 PR** / **L2 工作包双后端差分** / **L3 客户演示**，见
[AGENTS.md「验收分层」](../AGENTS.md) 与 [功能交付流程](engineering/002-feature-delivery-workflows.md)；
`change_scope.py` 只输出 L1 下限，`acceptanceTier` 标明双后端属 L2）：

```powershell
python scripts/governance/change_scope.py
python scripts/governance/evidence_reuse.py --target <合入后提交> <证据文件或目录>
python scripts/governance/pr_review_digest.py --repo <owner/name> --pr <编号>
shpm task dispatch <任务ID> --wp <工作包ID> --executor <执行者> --branch <分支> --worktree <worktree> --slot <1-6> --data-root <数据目录> --dry-run
shpm verify ingest local-tmp/verify-full/<run>.json <UI 证据目录> --task <任务ID> --dry-run
python scripts/lib/source_fingerprint.py --repo . --commit HEAD
python -m unittest discover -s tests/checks/build -p "test_*.py"
```

两条 SHPM 命令先加 `--dry-run` 核对将写入的对象，确认后去掉该开关；槽位端口由 `.shpm/slots.json` 解析。提交前的 SDK 基线门禁：

```powershell
pwsh -NoProfile -File tests/checks/build/verify-haifa-version-baseline.ps1
```

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

## 能力配置与限制

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
  服务主机，仅它们被 `autoApproveReadOnlyNetworkTools` 豁免；代理配置见 [README「默认 Web 工具的环境代理」](../README.md#默认-web-工具的环境代理)。
- **全局审批级别（默认 `ask`）**：进程变量 `HAIFA_SUPER_HARNESS_APPROVAL_MODE=ask|auto|deny` 选择交给 SDK 的策略数据集，
  缺省、空、`ask` 或非法值一律保持今天的逐次审批（fail closed，拼错不会放大权限）。`auto` 对齐上游 CodingAgent 的
  `--approval auto`：不再声明 `FILE_WRITE` / `PROCESS_EXECUTION` / `NETWORK_ACCESS` / `EXTERNAL_SYSTEM_MUTATION` /
  `PERMISSION_ELEVATION` 的 ASK 规则，并以 `ApprovalMode.AUTO` 装配，因此 `write_file`、宿主 `execution_run`、
  非只读 MCP 与技能脚本不再逐个弹审批；`deny` 把同样的能力改为 DENY。两者都保留 CRITICAL 风险 DENY，凭据
  `REAUTHENTICATE` 在 `auto` 下仍要求审批。级别只在 Gateway 启动时读取、对之后新建的 Run 生效；已在等待的审批按原级别处理。
  该开关是信任扩大，不得用作验收或演示证据，也不得据此放宽任何 Oracle（登记见 SHPM issue `W05-APPROVAL-LEVEL`，归属 W21）。
- **定时任务调度（默认关闭）**：进程变量 `HAIFA_SUPER_HARNESS_SCHEDULER_ENABLED=1`（或 `true`/`yes`）启动单实例
  后台调度线程，按任务 cron / interval / once 计划触发 Run；缺省、空或 `0` 时调度器保持关闭，已有任务不会自动运行，
  REST 手动触发仍可用。可选 `HAIFA_SUPER_HARNESS_SCHEDULER_POLL_SECONDS`（默认 5）、
  `HAIFA_SUPER_HARNESS_SCHEDULER_MIN_ONCE_DELAY_SECONDS`（默认 60）、`..._MAX_CONCURRENT_RUNS`（默认 3）、
  `..._QUEUE_TIMEOUT_SECONDS`（默认 3600）。只在 Gateway 启动时读取，修改后需重启；发布包安装与模板默认保持关闭（`0`），可通过 `--scheduler-enabled 1` 或在 `config/gateway.env` 中显式启用。
- **宿主 Shell 工具（默认关闭）**：设进程变量 `HAIFA_SUPER_HARNESS_HOST_SHELL=on`（缺省、空、`off` 或非法值
  均保持关闭，关闭时工具面与披露逐字不变）后，经公开 `HostExecutionPlatforms.hostGuarded` 注册 SDK 的
  `execution_run`。命令或脚本以 Gateway OS 用户在**唯一注册的宿主 workspace** 内执行；模型不能选择 workspace
  或工作目录（不暴露 `workdir`，传 `workdir` 会在审批前被拒），单次默认超时 120000 ms、上限 600000 ms
  （对齐冻结 DeerFlow `bash_command_timeout: 600`），输出有界。每次调用一次审批，拒绝后命令不执行、不再放行。
  **该宿主进程不是安全边界**：它拥有当前 OS 用户的完整文件系统、网络与内核权限，与冻结 DeerFlow
  `sandbox.allow_host_bash` 同款；该风险已由人工书面接受，审批卡亦明示「no strong isolation」。
- **技能脚本工具（与宿主 Shell 开关同链路，默认关闭）**：开关打开且通过进程变量
  `HAIFA_SUPER_HARNESS_BUN_PATH` 声明 bun 可执行文件（绝对路径；也可为其所在目录）后，经公开
  `SkillScriptPlatforms.trusted` 注册 `baoyu-image-gen` 的技能脚本 Tool（别名 `baoyu_image_gen`）。
  运行时、脚本、工作目录与 `outputs/` 位置均为产品固定事实：工作目录是当前 Thread 自己的 workspace
  user-data 根，产物写在 `/mnt/user-data/outputs/` 并经既有 Artifact 链下载核验；模型只提供业务参数
   （prompt、image_name 等，越界 argument 在审批前被稳定错误拒绝）。每次调用 ALWAYS 审批，冻结 Tool
   身份绑定该包版本与 runtime/timeout/purpose 声明；关闭或缺任一前置时工具面逐字不变。
   图像 Provider 同属产品/部署事实、不是模型选项：进程变量 `HAIFA_SUPER_HARNESS_SKILL_PROVIDER` 指定传给
   脚本的固定 `--provider`，缺省与空值为 `codex-cli`；取值限于包支持集
   `codex-cli|openai|azure|google|openrouter|dashscope|zai|minimax|jimeng|seedream|replicate|agnes`，
   集外值使工具面关闭（fail-closed），避免误路由已审批的执行；对应 Provider 的密钥由脚本自身按
   `<cwd>/.baoyu-skills/.env`、`~/.baoyu-skills/.env` 等位置读取，不经 Gateway 进程环境注入。
   测试配置变化后重启 Gateway 生效。 UI 验收 -W04ImageGen profile 用真实上游包字节（JimLiu/baoyu-skills@1567581 的 skills/baoyu-image-gen，主控暂存在 local-tmp/w04-real-skill/，49 项 SHA-256 清单）逐文件植入 data-root 技能目录并逐项校验；默认 `codex-cli` Provider（走本机 Codex CLI OAuth），其在途缓存与 JSONL 诊断分别钉进该轮 data-root 与证据目录，不写用户目录。
- **模型与思考配置**：通过公开 `ModelReasoningPolicy` 接入思考开关与推理级别；Agent 的 `model_settings`
  冻结到新 Run，同型号参数变化通过配置 digest 重建下一 Turn 的 Bridge。
- **运行诊断与用量投影**：`GET /api/threads/{thread_id}/runs/{run_id}` 的 `diagnostics` 与
  `GET /api/threads/{thread_id}/token-usage` 只读聚合公开 SDK 事实；无法证实的历史信息返回 `null`，
  不猜测、不从当前配置推断。
- **后续建议与标题**：`GET /api/suggestions/config` 暴露全局配置（`HAIFA_SUPER_HARNESS_SUGGESTIONS_ENABLED`、
  `HAIFA_SUPER_HARNESS_SUGGESTIONS_MAX`）；线程标题按冻结回退规则生成，可在 Run 之间切换模型。
- **冻结前端与 BFF**：直接复用冻结 DeerFlow 前端，Next BFF 只指向本槽位 Gateway；启动时校验冻结来源、
  工具链版本与同源代理目标。

## Lark 办公插件定向 UI 验收

`-W20Lark` 复用现有受控模型、生产 Gateway 与冻结前端。主控须提供真实产品 installer 产出的共享 `skills` 目录（含 `.haifa-lark-manifest.json`、根 LICENSE 与各技能 LICENSE.txt）及对应官方归档。Python 预置检查完整文件集合、官方逐文件字节、版本、归档与许可证 hash、产品 manifest 的内容 hash；只允许当前产品的明确 `lark-shared` 指引追加。Java Windows 与 Unix 的路径排序不同，检查器分别重算这两种确定的聚合 hash，并记录匹配的平台顺序。它不生成产品 manifest、不执行安装，不复制用户凭据、启用配置、Runtime 状态或 CLI。

```powershell
pwsh -NoProfile -File scripts/ui/run-w18-ui-smoke.ps1 `
  -SourceRepository <主控核对的冻结检出> -AgentSlot 2 -GatewayPort 30618 -FrontendPort 35618 `
  -W20Lark -LarkSkillSeedDirectory <本工作树内的真实安装skills目录> `
  -LarkOfficialArchive <本工作树内的官方zip> `
  -CaseTitle 'W20 Lark capability center loads the official lark-doc package through Haifa' -PreflightOnly
```

每次调用使用独立新 data-root，默认由 runner 分配；预检也会准备种子，正式调用必须使用另一个新 data-root。主控放行后去掉 `-PreflightOnly`，构建戳匹配时才使用 `-SkipBuild`。该 profile 尚未验证同次启动重复运行，x2 使用两次独立启动，不传 `-Passes 2`。

唯一用例打开能力中心真实 Lark 设置，通过真实 BFF 检查精确 27 个技能和未连接状态；再通过冻结技能开关与生产 screen 启用 `lark-doc`，真实 Run 执行 `skill_load` 和 `skill_resource_read`，核对激活结果及官方 reference 的完整字节 hash。受控模型只定向发出调用，不伪造 Tool 结果，不拦截页面响应。证据包含 seed 清单、HTTP 状态、Run/Tool 身份、reference hash、截图、trace 与 runner 流程健康度。此切片不执行 CLI/OAuth/办公文档读取；真实 OAuth 与文档链记 `BLOCKED_EXTERNAL`，其余未运行项保留 `NOT_RUN`，不能据此标记 LARK-05 完整通过。

小范围脚本自测（不启动服务、浏览器或构建）：

```powershell
python -B -m unittest discover -s tests/checks/ui -p test_lark_seed.py -v
node --test tests/checks/ui/lark-office.test.cjs
```

修改 UI 用例或 runner 后正式 L1 默认 x2；最终模型与其余门禁仍以整合候选的 `change_scope.py` 为准。

# 公共规则

## 范围与依赖

- 根目录 `README.md` 统一使用中文维护；新增或修改构建、启动、验收说明时同步更新中文正文，命令、路径、配置键、API 名称和依赖坐标保留原样。
- 以 `private-docs/roadmap/35-super-harness-deerflow-parity.md` 及其产品、PRD 配套目录为范围与验收依据。
- DeerFlow 前后端固定为 `bytedance/deer-flow@e545c28ac301e6f9feae247c513d8ea8f83df294`，禁止随 `main` 漂移。
- 直接复用冻结的 DeerFlow 前端并由 Haifa 后端兼容其真实 REST/SSE/WS/文件/auth 协议，禁止自研平行 UI 或回落 DeerFlow 后端。
- 仅通过本地 Maven 仓库消费 Haifa Agent 公开 JAR/BOM（HA 0.1.2 开发线专门承接本项目 SDK 功能缺口，见 `private-docs/prompts/038-*`），禁止源码路径依赖、复制 SDK 或依赖产品 JAR。SDK 版本规则：
  - 上游每个 SDK worktree 发布到自己的坐标 `0.1.2-<worktree 目录名>-SNAPSHOT`；基线 `0.1.2-SNAPSHOT` 只从上游主检出的 `dev` 发布，代表已由人工合并的 SDK。
  - `main` 只消费基线；带 worktree 标识的版本不得进入被跟踪文件（提交前门禁见 README「定向测试与 SDK 版本选择」）。
  - 工作包 worktree 消费哪个上游 worktree 的 SDK 由主控通过 `local-tmp/sdk-selection.json` 指定；执行者不得自行更换版本或直接传 `-Dhaifa.version`。
  - 上游 SDK worktree 只在主控验收发现 SDK 问题后才修改并重新发布；重新发布后主控须让消费方不带 `-SkipBuild`/`-SkipServerBuild` 重新构建（版本号不变，构建戳看不出 SDK 已更新）。
  - 依赖尚未合入上游 `dev` 的 SDK 改动的分支不能合入 `main`：须等上游 PR 人工合并、基线从 `dev` 重新发布，删除 `local-tmp/sdk-selection.json` 后在基线上重跑门禁。带标识版本下的验证结果只证明候选分支。
- 新增或修改能力前必须先按 `docs/runtime-product-boundary.md` 判断其归属：可复用的 Agent Runtime 语义必须上游到 Haifa Agent，并通过公开 API/SPI 与已发布 JAR/BOM 消费；Super Harness 只拥有 DeerFlow 兼容、产品语义、UI/BFF、业务集成与窄适配层，禁止实现第二套 Run/Loop/Memory/Tool/Skill/MCP/Sandbox/Artifact/Checkpoint/Permission/Multi-Agent Runtime。若归属不清，在新增领域对象、状态机或持久化表之前停止并进行架构裁决。
- 如验收过程中确认必须完善上游 Haifa Agent SDK，只能在 `D:\workspace\haifa-agent` 仓库以 `dev`（HA `0.1.2-SNAPSHOT`，不设 baseline 分支）为起点，为每个功能创建独立 worktree 和新的 `feat-*` 开发分支；禁止直接修改或提交到 `dev`。完成后须用上游发布脚本把公开 JAR/BOM 发布到该 worktree 自己的坐标，由本项目按上一条的 SDK 版本规则消费，同时记录 SDK commit（取自上游发布清单）、发布与回归证据；随后从该 `feat-*` 分支向 `dev` 发起 GitHub PR，由主控在 SHPM 登记对应任务与 PR/验证状态（`shpm verify add`/`shpm issue add`），并明确提醒人工审查合并。任何智能体不得自行合并该 PR，除非人工针对该具体 PR 另行书面授权。
- 为同一功能引入的领域模型复杂度必须与冻结 DeerFlow 的对应实现保持平衡或更低，禁止为了协议兼容复制、叠加或扩张不必要的领域对象、状态机、持久化概念和抽象层。若有证据表明只有更高的领域复杂度才能实现验收范围，执行智能体必须在编码前提交包含 DeerFlow 对照、复杂度增量、不可替代原因、风险与简化备选的设计说明，先经主控 Agent 评审，再等待人工裁决，未获人工明确同意不得实施或合入。

## 开发、测试与证据

- 后续新增脚本以 Python 为主，核心逻辑集中在 Python 中；如需方便不同平台调用，可提供 `.ps1` 和 `.sh` 薄封装，仅负责必要的环境准备、调用 Python、透传参数与退出码，避免在封装中重复实现核心逻辑。
- 每个工作包使用独立 branch/worktree、端口和数据目录。
- Git commit message（标题与正文）以及 GitHub PR 的标题、描述和评论（含 review comment）必须使用英文；适用于本仓库及上游 `D:\workspace\haifa-agent` 的 PR。命令、路径、配置键和 API 名称保留原样。
- 提交前须运行范围内定向测试、按改动范围确定的门禁（见「验收提速」B 条）、`git diff --check`、secret scan 与真实 smoke，并保留真实 Run/Tool/Artifact 证据；失败项和未运行项分别记录为 `FAIL`/`NOT_RUN`，不得用静态验证替代运行证据，不得放宽断言或删除失败测试换取绿灯。
- Playwright 验收除功能断言外还必须报告流程健康度：各轮（冷轮仅在跑了时）启动与总耗时、是否重试、首个超时或卡点、Next 动态编译时间、trace/screenshot/日志位置，以及结束后端口和子进程是否清理；至少做一次同脚本重复运行以区分稳定问题与偶发问题。不得用无界等待掩盖启动或断言失败；等待必须有明确对象、超时和失败证据。
- 原始证据外置：运行任何 `mvn clean` 或重跑前，须把需保留的 Playwright trace、截图及前后端/Provider 原始日志复制到该工作包的外置独立证据目录（例如 worktree 的 `local-tmp/<slot>/evidence`，不能是另一个 `target`），并记录原始与复制路径。原始日志已消失时，只能明确标注为从先前终端输出重建的摘要，不得冒充原始证据。首次失败的证据同样保留，不得改记为 `NOT_RUN`。

### 验收提速（不降低门禁）

- 开发按“实现 → 定向测试 / 单 case 冒烟 → 集中自审修复 → 推送分支并开 draft PR → 一次性读取机器人审查并集中处理 → 固定候选提交 → 正式门禁 → PR 转 ready”执行。自审发现的问题先按根因攒批，禁止每修一个点就重跑全仓/UI整套；正式门禁失败先保存原件并定位，再按影响范围修复复验。最终源码、SDK构件或断言改变时，不沿用失效PASS；该规则不减少提交前门禁或人工验收。
- draft PR 不代表任何门禁通过，PR 描述须写明正式门禁尚未运行。
- 开 PR 后最多等机器人 10 分钟，超时按 `NO_REVIEW_YET` 记录并继续。
- 审查意见只处理一轮：正确的采纳，不采纳的各写一句理由；不再主动请求重新审查。
- 正式门禁通过后才出现的新意见，只有确认会导致错误行为的才修改并重跑受影响门禁，其余登记为后续事项。
- 向人工报告 PR 可合并之前，必须运行 `python scripts/governance/pr_review_digest.py --repo <owner/name> --pr <编号>` 并给出结论。
- 独立检索和读取合并到同一工具轮次，默认回传退出码、测试计数、首个失败签名和原始证据路径；仅为定位问题展开必要日志片段，不反复输出同一全文。依赖步骤和修改顺序仍串行，重型构建不为减少轮次而并发。
- 每个执行worktree在`local-tmp/acceptance-handoff.md`维护短交接，模板见`docs/acceptance-handoff-template.md`：只记录范围、源码SHA/dirty、SDK选择与发布来源/hash、断言/证据映射、首个FAIL、未运行项及下一步。事实变化时更新，不复制长日志、密钥或reasoning正文；该交接不是另一份任务账本，不替代SHPM。
- 后台任务使用有界进程等待并在完成时合并读取结果摘要；等待期间推进不依赖它的检查，避免固定sleep后反复读完整日志。每次等待必须有对象、deadline和超时证据，不增加原预算或自动重试；长任务至少每60秒给出有意义的进展/卡点更新。
- UI spec或fixture配置变更先用`scripts/ui/run-w18-ui-smoke.ps1 -CaseTitle <完整标题>`执行受影响case，再进入正式轮次；`-PreflightOnly`仅做本轮预检。局部冒烟/预检不能宣布整套PASS；未执行case保留NOT_RUN，最终全套冷暖轮与真实smoke照常执行。

- 2026-09-30 人工批准批量验收：先跑客户演示正向主链，按依赖拆分独立用例，通常每批 10–15 项。执行前标明前置、依赖和影响范围。局部失败先保存原始证据、登记问题并继续不受影响的用例；批次中不边测边改产品。共享服务/身份/数据/端口等前置失效时只停止受影响批次，主链失败后的依赖用例记 NOT_RUN，其他独立组继续。
- 批次结束按根因归并问题，一次修复后重跑受影响用例及必要回归；代码与断言稳定后才运行昂贵正式门禁。演示范围外的高级/偏门缺陷先挂 issue 和风险，不阻断无依赖的演示验证；必需 Oracle 失败仍保留 FAIL/NO_GO，演示触及故障路径时作为阻断处理，不缩改 Oracle，不把延期记 PASS。
- 节约上下文：复用明确的路径、SHA 和断言表；回报优先给逐 case 结果、首个失败签名、依赖影响及原始证据路径，按需读日志片段。主控保持可见进展输出，子智能体每 15 分钟 checkpoint；重型构建/UI 及共享 Provider 由主控排队，避免内存和槽位冲突。

- 昂贵的全仓构建或双后端 UI 轮次开始前，先由执行者列出本任务的 Oracle case → 实际断言 → 必需前置条件 → 预期原始证据对应表，主控复核关键正反例；尤其不能用“不存在资源”的 404 冒充“已存在但跨身份不可访问”的隔离证据。映射不完整时先修测试，不启动正式轮次。
- 开发内环先运行受影响模块的定向测试、合同/语法检查和必要的单 case UI 排障；这些结果只用于快速定位，不能替代提交前全仓门禁、真实 smoke、同脚本重复运行或主控独立验收。代码或断言修改后，先保存首次 FAIL 原件，再按变更范围重跑；不得靠反复整套重跑碰运气。
- 正式 UI 前一次性核对冻结来源、SDK 实际落盘构件、前端依赖与浏览器、完整槽位端口绑定、独立 data-root/凭据引用、Provider、Gateway 和前端代理目标。预检失败先修环境或如实记 `NOT_RUN`/`BLOCKED_EXTERNAL`，不要等长轮次超时后才发现配置错误。
- 同一源码 SHA、前端依赖和构建时代理目标完全一致时，可在同一独立 worktree 内复用已验证的构建产物完成后续暖轮；变更代码、依赖、代理目标或槽位后必须重建并重新验证。复用构建不等于复用 Run、Thread、数据目录或测试结果，不得跳过规定的 cold/warm、双后端、连续两轮与最终主线 SHA 门禁。
- 2026-10-03 人工批准以下四项流程裁剪（A–D），用于减少不产生新信息的重复运行：
  - A. 快进合入不重跑：最终门禁前先把分支变基到 main 最新提交，合入使用快进。快进后 main 的 SHA 与已验证 SHA 相同，主控核对门禁摘要 JSON（`local-tmp/verify-full/*.json`）的 `sourceSha`、`haifaVersion` 和 UI `flow-health.json` 的 `sourceSha` 与合入后 HEAD 一致，即直接复用这些结果，不在 main 上重跑门禁和 UI；非快进合入（merge commit、冲突解决、合入前 main 已前进）必须在合入后的 SHA 上重跑。固定端口主服务的刷新与 smoke 照常执行；客户演示「同一主线 SHA 连续两轮 L3」不受本条影响。
  - B. 按改动范围定门禁：运行 `python scripts/governance/change_scope.py` 得到建议门禁。只改文档 → 文档检查；只改脚本 → 脚本回归，改到 `scripts/lib/`、`scripts/ui/` 再加一轮暖轮；只改 Java 测试 → 全仓门禁；改产品代码、UI 脚本或用例 → 全仓门禁加两轮暖轮；改冻结前端 → 加一轮冷轮。建议只是下限，主控或复审可以要求更多。
  - C. UI 默认两次暖轮：同一提交上默认跑两次暖轮（满足同脚本重复运行）；冷轮只在冻结前端、前端依赖、槽位或代理目标变化、构建戳失效时跑。新 worktree 首次运行没有构建戳，第一轮自然就是冷轮。
  - D. SHPM 登记用薄封装：派工用 `python scripts/governance/shpm_dispatch.py`（一次完成 task create、sprint link、task start、assign create），门禁结果用 `python scripts/governance/shpm_record_gates.py` 从门禁摘要 JSON 和 UI 证据目录登记。两者默认只打印命令，加 `--execute` 才写入；结果原样登记（FAIL 仍是 FAIL），`deliver`、`accept`、阶段结果和 `GO` 仍由主控手工执行。
- 每轮分别记录准备/构建、服务就绪、功能断言、证据归档、复审及返工耗时和首次卡点；证据目录与 SHPM 记录可由脚本汇总，但原始日志、首次 FAIL、`NOT_RUN` 和人工裁决须保留，脚本不得自动提升阶段 `PASS`/`GO`。仅并行执行真正独立且资源、端口、数据目录隔离的检查；本机内存不足时不要并发重型 Maven/Next/Java 任务。

## 环境与排障记录

软件安装、依赖准备、配置、凭据或权限问题实行“首次发现、立即沉淀、后续先查”：首次发现者须在不泄露密钥的前提下，把症状、根因、影响范围、精确检查命令、最小修复步骤和修复后验证写入本节对应主题，并在 checkpoint 中通知主控；后续智能体开工前必须先读取本节，禁止重复试错，禁止用复制 SDK/`node_modules`、全局安装、放宽系统权限或污染用户目录来绕过。本节只保留仍有效的结论，已解决的一次性历史不在此累积。

### Maven 与构建

- 2026-10-04 实测：新 worktree 在 `core.autocrlf=true` 下可把 `super-harness-server/src/main/resources/upstream/deerflow/w15/` 的冻结 Python 资源检出为 CRLF，Gateway 构造时报告 `Frozen Skill scanner resource hash mismatch`。检查：逐项比对该目录 `manifest.json` 中的 SHA-256 与实际字节；修复使用仓库 `.gitattributes` 的 `text eol=lf` 规则，在相关路径原先 clean 的独立 worktree 用 `git archive HEAD <该目录>` 恢复提交原始字节，再重建。不能改 manifest 哈希或关闭校验；首次错误保留为环境前置 FAIL。

- 在仓库根目录使用 Java 21、Maven 3.9.15 和 PowerShell 7（`pwsh`）；也可用 `mvnw.cmd` / `./mvnw`，Wrapper 首次使用需下载分发包，之后可离线复用。
- 新 Java 验收测试从 `ProductionWriteFileHttpTest` 黄金样例起步，复用 `testsupport` 中的模型、审批、Gateway 与进程夹具；独立数据、证据和端口的配置见 [README「定向测试与 SDK 版本选择」](README.md#定向测试与-sdk-版本选择)。
- 定向测试：`pwsh -NoProfile -File scripts/test-targeted.ps1 -Test 'ProductionWriteFileHttpTest,AcceptanceFixturesTest' -LowMemory`。替换 `-Test` 即可选择用例，其他模块用 `-Module`；入口固定带 `-am`，匹配 0 个测试算失败，不传额外 Maven goal、`-DskipTests` 或 `-Dhaifa.version`。
- 全仓门禁：`pwsh -NoProfile -File scripts/verify-full.ps1`，先跑秒级预检（SDK 基线门禁、`git diff --check`、对 main 以来改动的 secret scan），失败即停、不启动 Maven；再以三个并行 fork（`-Forks 3`，内存紧张时 `-Forks 1 -LowMemory`）运行离线 `clean verify`，生成 exec JAR、构建戳和摘要 JSON。执行前保存需保留的原始证据，并停止当前 worktree 中占用该 JAR 的精确进程。
- SDK 选择沿用「范围与依赖」规则，由入口读取本 worktree 的 `local-tmp/sdk-selection.json`；提交前运行 `pwsh -NoProfile -File tests/checks/build/verify-haifa-version-baseline.ps1`。测试日志位于 `local-tmp/test-targeted/`、`local-tmp/verify-full/`，stdout 输出 JSON 汇总。
- `scripts/ui/run-w18-ui-smoke.ps1` 自动避免无意义的构建：后端 JAR 的构建戳（源码 SHA、SDK 版本）仍匹配时直接复用，例如同一提交的全仓门禁或上一轮的产物；前端构建戳只含冻结前端源码树、lockfile 和代理目标，不含仓库 HEAD，匹配就复用本地构建，否则从主检出的共享缓存 `local-tmp/shared/frontend-build/` 恢复（键见 `scripts/lib/frontend_build_cache.py`），两者都没有才执行 `pnpm build` 并存入缓存；`flow-health.json` 的 `serverBuild`/`frontendBuild` 记录 `REUSED`/`RESTORED`/`BUILT`。`-SkipServerBuild`/`-SkipBuild` 仍可用于强制复用并在构建戳不符时失败。冻结前端来源校验在源码与 DeerFlow 检出未变时复用本 worktree 的上次 PASS（`local-tmp/cache/frontend-source-gate/`，键见 `scripts/lib/frontend_gate_cache.py`）。复用由构建戳校验源码、SDK 版本、前端依赖和代理目标，输入变化后重新构建验证；每轮仍使用独立数据与证据。

### 默认 Web 工具环境代理

- 在启动 Gateway 的同一 PowerShell 会话中配置进程级变量，然后启动或重启 Gateway：

```powershell
$env:HAIFA_SUPER_HARNESS_TOOL_HTTP_PROXY='http://<代理主机>:<端口>'
$env:NO_PROXY='127.0.0.1,localhost'
$env:no_proxy=$env:NO_PROXY
```

- 产品键必须全大写；非空时覆盖通用代理变量、`NO_PROXY` 和 JDK 默认代理。未配置产品键时，按协议读取 `HTTP_PROXY` / `HTTPS_PROXY`，再回退 `ALL_PROXY` 与 JDK 默认选择器；通用变量的小写形式优先。
- 代理地址使用 `http://host:port`，HTTPS 目标经 CONNECT 转发；不支持 SOCKS、HTTPS 代理地址或代理认证。通用 `NO_PROXY` 使用逗号分隔的主机 / 域名，不使用通配符或 CIDR。
- 仅设置启动进程的环境变量；不自动读取 WinINET，不修改用户、系统或 JVM 全局代理。检查配置时只输出变量是否存在，不打印代理值或凭据。完整配置说明见 [README](README.md)。

### MCP 承载（Haifa SDK 0.1.2）

- 2026-09-29 实测：公开 `io.haifa.agent.starter.McpServerSpec.streamableHttp` 默认协议版本为 `2025-11-25`，而 `scripts/mcp/start-v2-test-mcp-servers.ps1` 起的本地 fixture 只协商到 `2025-06-18`。症状：`McpToolPlatforms.connect` 成功返回（不抛异常），但 SDK `optional()` 会静默降级成 0 个 Tool，模型侧 `request.tools()` 为空，表现为“桥接成功却没有 MCP 工具”。检查：对照 fixture `initialize` 的 `protocolVersion` 与 spec 的 `protocolVersion()`。最小修复：映射时显式 `.protocolVersion("2025-06-18")`（协议常量，非服务器/Tool 名称硬编码）。
- 2026-09-29 实测：只读 MCP Tool（`McpServerSpec.readOnly()`）仍会命中 `PolicyPresets.standardApproval()` 的 `NETWORK_ACCESS` 规则并进入 APPROVAL。症状：Run 首次请求工具后停在 `WAITING_APPROVAL`，不会自动执行。检查：SSE 里出现 `artifact.human_input.request_id` 卡片。集成测试须以冻结隐藏回复（`additional_kwargs.human_input_response`，`option_id=approve`）批准后 join 同一 Run 的 stream，才能拿到真实 Tool 结果。
- 2026-09-29 实测：仅由 MCP 提供 Tool 的 `HaifaRunBridge` 必须让 `hasTools()` 为真，否则 SDK 的 `tool.call.requested` 在冻结 transcript 投影里无法映射，SSE 直接报 `unsupported_event_projection` 并提前结束。最小修复：`hasTools = !tools.isEmpty() || !mcpSpecs.isEmpty()`。
- 2026-09-29 实测（本机 FreeCommit 曾低至约 0.4GB）：低内存时 `mvn` 与 fixture JVM 会报 `Could not reserve enough space` / `Native memory allocation (mmap) failed ... G1 virtual space`。规避：fixture 用 `JAVA_TOOL_OPTIONS=-Xmx96m -XX:+UseSerialGC`，Maven 用 `MAVEN_OPTS=-Xmx512m -XX:+UseSerialGC`，surefire 用 `-DargLine=-Xmx768m -XX:+UseSerialGC`；不要用默认堆（默认约为物理内存的 1/4，会预留数百 MB 虚拟空间）。

### 真实模型诊断

- W15 静态 review 使用 `python -I -B`，隔离模式不加载用户 site-packages。系统 Python 普通导入 `yaml` 成功，不能证明隔离子进程可用；检查：`<python> -I -B -c 'import yaml; print(yaml.__version__)'`。缺 PyYAML 时 review 拒绝，不能去掉 `-I` 或在线安装目标依赖绕过。以进程变量 `HAIFA_SUPER_HARNESS_SKILL_PYTHON` 指向已经具备该依赖的解释器；本机冻结 reference 的 `backend/.venv/Scripts/python.exe` 已验证 PyYAML 6.0.3，仅用于运行纯静态审查模块，不启用其后端或目标脚本。首次失败原件保留于 W15 独立证据目录。

- 2026-09-30 实测：managed model 的 `base_url` 会影响 SDK 方言；`DeerFlowConfigurationApi.binding` 仅在 endpoint 精确为 `https://api.deepseek.com` 时使用 DeepSeek 方言并固定 `thinking=disabled`。把真实模型临时改到本地 `/w16-live` 代理，实际落盘 SDK 的 standard 方言会在发 HTTP 前拒绝（`provider endpoint path must be /v1`，Run 为 `MODEL_CALL_FAILED`）；改 `/v1` 虽可发出本地请求，standard 请求缺少原 DeepSeek thinking 选项，不能视为同语义采集。检查：核对实际 model config、SDK bytecode `validateSnapshot`、原 `model.call.failed` 与 Provider 是否收到 HTTP；本地无真实凭据定向证明见 W16 `local-tmp/slot-2-w16/evidence/phase2-scheme-b-preflight-20260930-144134/a-only-capture-diagnostic/sdk-local-endpoint-proof/`。最小处理：恢复直接 HTTPS 配置并把采集前置 FAIL 单独保留，不从它解释此前 `PARTIAL_RESPONSE`，不以代理改写请求或更换方言掩盖差异；后续采集须先证明保持原请求语义。

### Windows 快照目录观察

- 2026-10-01 实测：Windows/JDK 21 的 `BasicFileAttributes.fileKey()` 可返回 null，不能据此把配置零写入反例退化为只比内容 hash。检查命名配置文件：`fsutil file queryfileid <data-root>/owners/default/configuration.json`，只读返回非零 128 位文件 ID。本机无需提权即可运行；测试先使用非空 JDK fileKey，缺失时仅 Windows 可用此原生观察器（有界 5 秒、exit 0、输出上限 4096 bytes、严格一个非零 32 位 hex ID）。同值 REST 更新的正控制必须实际原子替换并改变文件 ID；拒绝反例同时断言文件 ID、mtime、size 和 bytes/hash 不变。不能添加生产 Store/写计数器、忽略观察器失败、推断没有临时写尝试，或以 hash-only 冒充没有配置发布。M3 `UpdateAgentToolTest.blankSoulCannotReplaceExistingConfiguration` 已通过真实 HTTP/SDK 正反控制，首次 fileKey 前置 FAIL 仍外置保留。

- 2026-10-01 实测：Windows 上 `Files.walk` 进入正在写入的 `.completed-*` 快照目录会持有目录句柄，阻止 recorder 将其原子重命名为 `completed`（`AccessDeniedException`）；关闭遍历后同一次 rename 成功。验收观察器禁止递归扫描未发布目录，须按真实 workspace/Run 定位确切 `completed/baseline.properties` 并检查存在，再验证重启后读取；保留原有有界等待、无 lazy-read 要求与内容断言。复现：`java local-tmp/<slot>/evidence/windows-directory-probe/DirectoryMoveProbe.java <该证据目录>`，输出 `openWalkMove=AccessDeniedException`、`closedWalkMove=SUCCEEDED`；用例 `DeerFlowWorkspaceChangesApiTest.continueDisconnectCompletesInBackgroundAndSurvivesRestartWithoutLazyRead` 的首次 FAIL 仍保留，不能延长超时或靠重跑掩盖。

### PowerShell 7

- 仓库 PowerShell 脚本依赖 PowerShell 7（`pwsh`，.NET Core），不能用 Windows PowerShell 5.1 代替；本机已安装 7.6.6（WindowsApps 下的 `pwsh.exe`，在 PATH 中）。5.1 下的典型症状：`scripts/frontend/start-haifa-frontend.ps1` 遇 `??` 报 ParserError；`scripts/frontend/verify-haifa-frontend-profile.ps1` 报 `[System.IO.Path]` 缺少 `IsPathFullyQualified`；`run-w04-frozen-ui-files.ps1` 的 `$PSScriptRoot` 默认参数为空，且 inventory digest 因 .NET Framework 区域排序而与 7.x 不一致（假阳性，不代表前端漂移）。因此 5.1 下的任何结果都不得记为 PASS/FAIL，只能记为 `NOT_RUN`；Maven `clean verify` 不受影响。检查：`Get-Command pwsh` 可解析，且 `pwsh -NoLogo -NoProfile -Command '$PSVersionTable.PSVersion.ToString()'` 返回 7.x；启动器可用 `pwsh -NoProfile -File scripts/frontend/start-haifa-frontend.ps1 -DryRun -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow` 验证，须输出 `frontendSourceGate=PASS`、`dryRun=true`。若新环境缺 `pwsh`，经人工同意后安装官方 PowerShell 7（例如 `winget install --id Microsoft.PowerShell --source winget`）；禁止把脚本降级改写成 5.1 语法，禁止跳过 profile 校验直接运行 `pnpm dev`。
- 2026-09-29 实测：脚本顶部开 `Set-StrictMode -Version Latest` 后，访问不存在的属性会抛 `PropertyNotFoundException`，**包括对空数组取 `.X`**（`@().X` 也抛）。取集合字段一律写 `@($arr | ForEach-Object { $_.X })`。另：`param([string]$Foo)` 会对变量 `$Foo` 加类型约束，后续把解析出的 `PSCustomObject` 赋给同名 `$Foo` 会被强制转成字符串并按路径误用；JSON 解析结果变量须另起名（如 `$ManifestPath` 参数 + `$manifest` 局部变量）。

### 冻结前端（pnpm / Next.js）

- 每个验收 worktree 使用独立的 `frontend/node_modules`、`.next`、端口和数据目录（`.next` 可由 runner 从共享缓存复制恢复，但不同 worktree 不得共用同一个 `.next` 目录）；主前端继续供人工验收使用。前端来源按「范围与依赖」的冻结版本校验。
- 在该 worktree 的 `frontend/` 目录准备依赖，先确认版本精确为 `10.26.2`，再安装：

```powershell
$env:COREPACK_ENABLE_NETWORK='0'
corepack pnpm --version
corepack pnpm install --offline --frozen-lockfile
```

- 安装输出须为 `downloaded 0`，并确认 `frontend/node_modules/.bin/next.cmd`、`playwright.cmd` 和 Playwright browser 可用。不使用系统 pnpm、全局安装或其他 worktree 的依赖副本；离线依赖不齐时 UI 记 `NOT_RUN`。
- 从仓库根目录检查启动配置：`pwsh -NoProfile -File scripts/frontend/start-haifa-frontend.ps1 -DryRun -SourceRepository <冻结 DeerFlow checkout 的绝对路径>`。正式启动用同一入口去掉 `-DryRun`；UI 验收使用 `scripts/ui/` 中的对应 runner。
- Next 构建前设置本槽位的 `DEER_FLOW_INTERNAL_GATEWAY_BASE_URL`，优先让 runner 完成构建和服务管理。源码、依赖与代理目标一致且构建戳通过时才用 `-SkipBuild`；全仓门禁后的服务端复用见「Maven 与构建」。本地受控模型 fixture 使用 `context_window=32768`。
- 冻结文件按 Git 提交的原始字节落盘，来源 / 哈希校验必须通过。需要重新落盘时，仅在全新、相关路径 clean 的 worktree 使用 `git archive` 导出 `HEAD` 的 `frontend`、`third-party`、`contracts` 并解包；不修改固定哈希、冻结资源或全局 Git 配置。完整启动与验收命令见 [README](README.md)。

### 端口

- 2026-10-03 W15 全仓门禁在并行工作包运行时发现 `W05ExactGatewayProcessRestartApprovalTest` 默认固定端口 `20618` 可能被其它槽位短时占用；症状为用例入口 `requirePortAvailable` 抛 `Address already in use`，即使事后 `Get-NetTCPConnection -LocalPort 20618` 为空也不能倒推当时未占用。原件见 W15 `local-tmp/w15-package/evidence/fullgate-third-fail-20261003-1840/`。先核对 `Get-NetTCPConnection -LocalPort 20618`、`netsh interface ipv4 show excludedportrange protocol=tcp`，再对本任务已分配端口做 loopback bind 预检。在启动门禁的同一 PowerShell 会话设置进程变量 `HAIFA_SUPER_HARNESS_W05_TEST_GATEWAY_PORT=11618`，W05 测试的两个精确 Gateway 进程将共用该槽位端口；不得停止其它工作包服务或占用主服务端口。修复后用该变量定向复测 W05 类并重跑全仓门禁，结束确认端口释放。

- 2026-10-01 实测：Windows 子进程 `waitFor` 完成且 `isAlive=false` 后，TCP 栈仍可能短暂保留该已退出 PID 的 `LISTEN`，同端口即时 `ServerSocket.bind` 会失败。真实 Gateway 的失败现场由现有 JNA `GetExtendedTcpTable` 在失败后 1 微秒启动采集确认；不能把约数秒后的空表或客户端 `TIME_WAIT` 倒推成失败瞬间的唯一原因。验收停止阶段须在原有共享停止 deadline 内等待**同一精确端口的默认 loopback bind 实际成功**，记录首次 bind 错误、原生安全 TCP 状态、探测次数和释放耗时；超时仍 FAIL。不得延长原停止预算、启用 `SO_REUSEADDR` 掩盖占用、换端口、跳过绑定断言或把资源释放探测冒充功能重试。诊断只记录状态、PID 与端口，不记录请求、凭据或内容。
- Windows 的 Hyper-V/WSL/NAT（`winnat`）会动态排除 TCP 端口（本机动态范围从 `49152` 起，曾有排除段覆盖旧端口导致 Next.js `listen EACCES`）；现行子槽位均低于 `49152`，但排除范围仍可能变化。每次派工和启动前先执行 `netsh interface ipv4 show dynamicport tcp`、`netsh interface ipv4 show excludedportrange protocol=tcp`，并对分配的全部端口逐个用 loopback `TcpListener.Start()` 做实际绑定预检；`Get-NetTCPConnection` 只能发现监听者，不能排除 NAT 保留。预检失败时保留端口/进程/排除段证据，由主控重新裁决整组槽位或把 UI 标为 `NOT_RUN`；不得私自重启 NAT、提权、随意改单个脚本端口或停止主服务。修复后按同一完整端口组重复预检、服务就绪和 Playwright，并核实端口释放。

### 凭据与数据目录

- 持久 SQLite Runtime 的 model continuation 使用 AES-GCM 稳定密钥。Windows 默认使用按规范化绝对 data-root 的 SHA-256 前 16 个十六进制字符分槽的 Windows Credential Manager target：`haifa:os:HAIFA_SUPER_HARNESS_CONTINUATION_KEY_<data-root-hash>`；仅当该数据目录尚无 `runtime/haifa-agent.sqlite` 时首次启动可生成并写入，已有凭据不得覆盖；已有数据库但对应凭据丢失必须启动失败，禁止静默换钥。显式 `os://NAME` 属于外部托管引用，缺失时即使数据库尚不存在也必须失败。Linux/CI 必须通过 `HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF=env://NAME` 指向外部注入的 Base64 256-bit key。症状：启动前报 continuation key unavailable/invalid。检查：`Test-Path <data-root>/runtime/haifa-agent.sqlite` 确认数据库是否存在，`cmdkey /list` 只检查对应 target 是否存在（不得输出 secret），并查看 `server-*.stderr.log`。最小修复：恢复该 data-root 原 OS 凭据或原外部 secret 引用，不能删除数据库或生成替代 key；修复后以同一数据目录完成关闭重启并验证 transcript/history/runs 可读。
- 由上一条推出：不同启动方式不得共用同一 data-root。例如 W05 Playwright 的 Interaction 测试 classpath profile 与生产 JAR ActiveReconnect profile 混用时，后者会报 `continuation key credential is unavailable for the existing Runtime database`。从首次运行起就为每种 profile 分配独立的新 data-root 子目录。

### 冻结 DeerFlow 参考后端

- 冻结源码中已有的 `backend/.venv` 不是完整的 locked 安装：直接用其中 Python 启动 Uvicorn 时，导入 `app.gateway.app` 报 `ModuleNotFoundError: No module named 'deerflow_extension_api'`；且 `uv sync --offline --locked --dry-run` 显示需下载 223 个包，不能假定可离线重建。检查：在 `deer-flow/backend` 下执行 `.venv/Scripts/python.exe -c 'import deerflow_extension_api, deerflow, app.gateway.app'`。最小修复：在**进程环境**的 `PYTHONPATH` 中只读加入冻结源码的 `backend/packages/extension-api` 与 `backend/packages/harness`，用独立 `DEER_FLOW_HOME`、`DEER_FLOW_CONFIG_PATH`、`DEER_FLOW_EXTENSIONS_CONFIG_PATH` 启动，不改冻结源码或现有 venv。验证 `/health` 返回 `{"status":"healthy"}`，且退出后释放所属槽位端口；若缺少真正的第三方包，记录 `BLOCKED_EXTERNAL`，不得联网安装或复制其他 worktree 依赖。

### OpenCode CLI

- 主控确认任务的独立 branch/worktree、端口、数据目录和验收范围，在派单提示词中提供 SDK 坐标、本地 Maven 仓库路径、测试命令及证据要求。提示词正文由主控读取，作为一个位置参数传入；显式指定模型 `deepseek/deepseek-flash`。

```powershell
$taskPrompt = Get-Content -LiteralPath '<主控已确认的提示词文件>' -Raw
Push-Location '<已分配的 worktree 根目录>'
try {
    New-Item -ItemType Directory -Force -Path local-tmp | Out-Null
    opencode run --model deepseek/deepseek-flash $taskPrompt 2>&1 |
        Tee-Object -FilePath local-tmp/opencode-run.log
} finally {
    Pop-Location
}
```

- OpenCode 只读取分配的 worktree；命令、文件清单和哈希清单使用相对该根目录的路径，不使用 `..`，不读取 `~/.m2` 等外部目录。Maven 通过仓库入口使用已配置的本地仓库。
- `private-docs` 的提示词正文内联传入；需要的冻结源码由主控将最小只读副本放到 `local-tmp/<slot>/frozen-deerflow/`，注明路径和冻结 SHA。提示词中不包含密钥、SDK 源码或依赖副本。
- 验收服务优先由仓库 runner 管理；自行启动的常驻服务在同一次 shell 调用的 `try/finally` 中启动、使用和清理，避免继承调用方管道后留存。CLI 直接运行或交给既有后台执行器，不使用 PowerShell `Start-Job` 承载。
- 用 `opencode-run.log`、Git 改动、checkpoint 和真实测试证据检查进展与交付；退出码 0 不能单独判定完成。权限拒绝或上下文不足时交回主控，不放宽外部目录权限绕过；检查和清理只针对本任务的精确进程。

# 主控 Agent 规则

## 授权与客户演示

- 2026-09-27 起，人工已对本项目后续开发、回归与客户演示所需的真实模型调用给出持续授权，不限制调用次数或预算，主控无需再逐次申请；该授权不等于任何演示阶段已执行或通过。主控仍须限定调用在当前任务范围内，使用已托管凭据引用，记录实际模型、Provider、时间、Usage、失败与重试，保留 W24 原始证据，并遵守密钥脱敏、客户数据隔离、无隐藏重试和阶段门禁规则。
- 客户演示按轮次推进，每轮的派工索引与阶段退出标准放在 `private-docs/prompts/39-deerflow-parity-delivery/show-roadmap-N/README.md`：
  - 客户演示 v0（用户亦称 V1，路线图 `private-docs/roadmap/36-super-harness-customer-demo-v0.md`，索引 `show-roadmap-1`）已于 2026-09-28 在 `main@c112012` 结算 M0–M4 `PASS`、人工验收通过、决策 `GO`。它只证明“真实模型 + 生产 `write_file` + Approval + Artifact + Reload/Reconnect + 同 Run Steer”在冻结 DeerFlow UI 上的本地纵向链，不等于 PRD D01–D05 的完整 Customer Demo Gate 或 Full Parity，也不关闭任何 W00–W24 工作包。后续若同一整链回归失败，须如实记录并重新评估该决策，不得保留过期 `GO`。
  - 客户演示 V2 以 `show-roadmap-2` 为派工总索引，未派工前全部阶段为 `NOT_RUN`、总体 `NO_GO`；实现任务仍落回 W00–W24，不创建平行工作包。
- 各轮通用门禁：主控把每个新任务映射到当前轮次的阶段、真实缺口和退出门槛，并在 SHPM 中登记（`shpm sprint link <SPRINT>/<阶段> --task <ID> --relation delivers --required`）。L1/L2 受控 Provider、测试 Tool、静态合同、组件测试、旧轮次证据或分开的单项 PASS，都不能替代同一主线 SHA 的连续两轮 L3 整链证据；任一产品修复合入后，后续差分和 L3 须基于新的主线 SHA 重新开始连续轮次。两轮不得隐匿失败或重试；真实 Artifact 必须有可信 ID/provenance 与可核验下载字节。阶段 `workStatus` 只表示执行状态，`result` 独立使用 PASS/FAIL/NOT_RUN 等 case 口径；已有分项证据不得提升阶段门禁。只有该轮全部阶段 PASS、同一主线 SHA、人工验收记录齐全，才可把演示决策改为 `GO`。W04 等已知范围外风险须披露，演示脚本实际触及其故障路径则如实 FAIL，不为追演示绿灯放宽门禁。
- 执行采用“双轨交付”：SHPM sprint 阶段（`shpm sprint show/gate <SPRINT> --json`）是客户演示阶段门禁，W00–W24 是不变的长期能力工作包台账。开发仍按工作包 task 提示词、独立 worktree、定向测试和主控验收交付；演示任务卡另外标注 `demoStages` 和该阶段原子断言。工作包切片/整包完成不自动使演示阶段 PASS，演示 `GO` 也不自动关闭工作包或 PRD D01–D05。W00 等长期横切工作只消费演示实际触及的来源，不因整包未完成而阻断正向演示。

## 派单与子智能体调度

- 主控每次开工、派单或向人工汇报前运行 `pwsh -NoProfile -File scripts/governance/audit-work-package-delivery.ps1`，并同时运行 `shpm reconcile --json`（只报告不修改），检查演示阶段、工作包、worktree、checkpoint、端口和实际子智能体列表；二者都无法读取智能体状态，须另行人工核对。主控在每次派工、复审、合入、失败或门禁变更时同次核对 SHPM 记录与实际 worktree；失败史保留。
- 端口：主目录固定 Gateway `18018`、Frontend `33018`，供人工验收，不因子槽冲突迁移，不参与子槽公式。三个子智能体槽位：槽位 1 Gateway `20618`、Frontend `25618`；槽位 2 Gateway `30618`、Frontend `35618`；槽位 3 Gateway `40618`、Frontend `45618`（逐组 `+10000`，槽内 Frontend=Gateway+`5000`）。W18 差分 runner 的受控测试 Provider 独占 `46618`，不属于槽位，也需绑定预检。每次均须按公共规则预检。派单前须确认目标 worktree 的启动器与本端口表一致，否则从当前 main 新建独立 worktree，不得用旧默认端口运行。派单必须声明槽位、完整端口组和独立数据目录；同一时刻每槽仅一任务，完成后释放。主线合入后主控刷新或重启固定端口服务并做 smoke。
- 并发上限：每个需要启动服务的子智能体（含 OpenCode 进程）必须独占一个子槽位，因此同时运行的此类子智能体最多 3 个。
- 选择执行者：范围清楚、无需频繁与主控交互、可凭最终提交和独立门禁验收的任务优先交给 OpenCode；需要持续协商失败语义、架构或跨仓边界的任务优先交给可交互子智能体。不设 OpenCode 派单比例。派给 OpenCode 时必须在启动命令中显式指定 `--model deepseek/deepseek-flash`，不得依赖默认模型，checkpoint 与交付证据须记录实际模型。主控在 `shpm assign create` 中记录实际执行者（`--executor`/`--ref`）与模型（`--model`），分配理由写入首个 `shpm assign checkpoint`；工具选择不放宽设计提示词、代码评审、自动门禁、真实证据、密钥和外部目录边界。OpenCode 同样遵守独立 worktree/端口/数据目录与 15 分钟 checkpoint/失联恢复规则；无法安全取得上下文或执行门禁时由主控收回或改派，不算已完成派单。
- 多智能体执行采用“15 分钟无声上限”：每个运行中的子智能体至少每 15 分钟向主控回报一次 checkpoint，至少包含当前工作包、已完成证据、正在执行的命令或步骤、首个失败或阻塞、worktree 变更及下一交付点；长时间命令须在启动前说明目的，并在结束后立即回报。若距上次有效回报或可观察进展达到 15 分钟，主控必须发送一次非中断式点名消息，不得因智能体状态仍显示 `running` 而无限等待。
- 点名后 5 分钟仍无应答，或主控通过 worktree、测试进程、端口与日志审计确认任务停滞、跑偏、越界或反复等待无结果时，按“失联/失控 lane”恢复：先保留未提交变更、日志、数据目录和进程证据，只中止受影响智能体的当前回合，再用原 worktree 恢复同一任务或明确交接给替代智能体，并收紧范围、端口与下一 checkpoint；禁止打断正常推进的其他智能体。主控须用 `shpm assign lost` / `shpm assign recover --preserve-worktree` 和 `shpm assign checkpoint` 及时登记该 lane 的实际工作包映射、恢复动作、可验证进展和仍为 `NOT_RUN` 的项目，避免用旧智能体名称误报任务进度。

## 提示词与过程文档

- 派工前在 `private-docs/prompts/39-deerflow-parity-delivery/` 对应工作包目录编写任务提示词，写明冻结来源、范围与依赖、实现边界、接口及失败语义、退出条件、测试命令和证据要求；每个可独立交付的任务使用单独文件。
- 新任务文件用 `工作包-任务名.md` 命名，如 `W05-Interaction.md`；任务名简短、无空格，不在新增目录 / 文件名中加入状态后缀。既有路径和 `show-roadmap-N` 轮次索引沿用；登记为 SHPM `prompt_path` 后不改名、不移动，任务状态在 SHPM 中维护。
- 用 `shpm task create --prompt` 登记提示词；派单消息注明任务 / 派工 ID、提示词路径、branch/worktree、端口、数据目录和 checkpoint 要求。交付与验收登记方法见 [SHPM 使用手册](docs/shpm-manual.md)。
- 方案、评审和复盘放在对应私有工作包目录，交付报告放在 `private-docs/ai-coding-report/` 指定位置，原始日志、trace 和截图放在外置证据目录；SHPM 只登记路径。`docs/` 保存稳定的开发与使用手册，不新增过程文档。

## 项目管理（SHPM）

- 工作包、任务、派工、checkpoint、证据和演示门禁统一在 SHPM 管理；只有主控通过 `shpm` 写入，子智能体报告进展与证据，人工在 http://127.0.0.1:8787/ 只读查看。正文和原始证据保留在各自目录，SHPM 登记路径。
- 操作前明确设置 `SHPM_DB`，运行 `shpm doctor --json` 和 `shpm status --json`；环境异常时停止写入。状态与写入命令使用 `--json`，写入带 `--idempotency-key`，同一次操作重试复用原 key。
- 按「登记提示词与任务 → start / assign → checkpoint → deliver → verify → 主控独立验收 → accept / release」推进；`deliver` 只进入 `ready_for_acceptance`。接手先用 `show`、`assign list`、`verify list` 查看状态和证据。
- 每次验收如实登记 `PASS`、`FAIL`、`NOT_RUN` 等结果与实际 SHA、命令、原始证据；任务、工作包和阶段完成须满足各自门禁及人工验收要求，`GO` 通过 `sprint decide` 设置。状态变化后运行 `tests/checks/evidence/verify-evidence-report.ps1` 检查证据聚合。

未覆盖的操作、参数、状态恢复和门禁说明，请看 [SHPM 使用手册](docs/shpm-manual.md)。

# 子智能体规则

- 接单后先完整阅读本文件、工作包对应 roadmap/产品/PRD、冻结 DeerFlow 源码与已有证据；首个 checkpoint 必须复述实际工作包、branch/worktree、槽位端口、数据目录、范围边界和下一交付点。子智能体不得执行任何 `shpm` 写命令，不得重建已删除的进度文件，也不得改动主目录服务。
- 只能使用主控分配的 worktree、数据目录和端口组。启动服务前须检查已分配端口；若被占用，可以查明 PID 并核对命令行后直接终止占用该组端口的进程，但不得终止主端口、其他槽位、未分配端口或仅按进程名批量结束进程。任务结束时须清理自己启动的服务并验证端口释放。
- 按主控规则的“15 分钟无声上限”回报 checkpoint；收到主控非中断式点名后，应在当前安全命令边界立即应答，不得为回报而破坏正在运行的测试或写入。
- 发现软件安装、依赖、配置、凭据或权限新坑位时，先保存可复现证据，再按“环境与排障记录”更新本文件；涉及密钥只能记录凭据引用、存在性和脱敏结果，禁止输出或提交密钥值。若问题需要全局安装、联网取依赖、提升权限或改变用户/系统配置，先停止扩张并报告主控，不得自行绕过。
- 编码前按公共规则对照冻结实现评估领域复杂度，需要更高复杂度时停在编码前提交设计说明；提交前按公共规则运行门禁并记录 `FAIL`/`NOT_RUN`。
- 交付 checkpoint 必须包含分支、最终 commit SHA、变更文件、测试/Playwright 次数与耗时、真实 Run/Tool/Artifact 或 HTTP/SSE 证据、已释放端口、worktree clean 状态、明确 `NOT_RUN` 清单以及建议的下一切片；提交后不得 amend/rebase 已交给主控评审的 SHA，除非主控明确要求。

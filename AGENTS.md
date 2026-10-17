# 公共规则

## 范围与依赖

- 根目录 `README.md` 统一使用中文维护；新增或修改构建、启动、验收说明时同步更新中文正文，命令、路径、配置键、API 名称和依赖坐标保留原样。
- 以 `private-docs/roadmap/35-super-harness-deerflow-parity.md` 及其产品、PRD 配套目录为范围与验收依据。
- DeerFlow 前后端固定为 `bytedance/deer-flow@e545c28ac301e6f9feae247c513d8ea8f83df294`，禁止随 `main` 漂移。
- 直接复用冻结的 DeerFlow 前端并由 Haifa 后端兼容其真实 REST/SSE/WS/文件/auth 协议，禁止自研平行 UI 或让产品回落到 DeerFlow 后端；冻结参考后端只用于差分对照（见「冻结 DeerFlow 参考后端」）。
- 仅通过本地 Maven 仓库消费 Haifa Agent 公开 JAR/BOM（HA 0.1.3 开发线专门承接本项目 SDK 功能缺口，见 `private-docs/prompts/038-*`），禁止源码路径依赖、复制 SDK 或依赖产品 JAR。SDK 版本规则：
  - 上游每个 SDK worktree 发布到自己的坐标 `0.1.3-<worktree 目录名>-SNAPSHOT`；基线 `0.1.3-SNAPSHOT` 只从上游主检出的 `dev` 发布，代表已由人工合并的 SDK。
  - `main` 只消费基线；带 worktree 标识的版本不得进入被跟踪文件（检查见 README「定向测试与 SDK 版本选择」）。
  - 工作包 worktree 消费哪个上游 worktree 的 SDK 由主控通过 `local-tmp/sdk-selection.json` 指定；执行者不得自行更换版本或直接传 `-Dhaifa.version`。
  - 已发布的上游 SDK worktree 只在主控验收发现 SDK 问题后才修改并重新发布。构建戳以 Java 输入指纹（含每个 Haifa JAR 的 SHA-256 身份）比较，能识别同版本重新发布的内容变化，消费方据此自动重建。
  - 依赖尚未合入上游 `dev` 的 SDK 改动的分支不能合入 `main`：须等上游 PR 人工合并、基线从 `dev` 重新发布，删除 `local-tmp/sdk-selection.json` 后在基线上重跑门禁。带标识版本下的验证结果只证明候选分支。
- 新增或修改能力前必须先按 `docs/runtime-product-boundary.md` 判断其归属：可复用的 Agent Runtime 语义必须上游到 Haifa Agent，并通过公开 API/SPI 与已发布 JAR/BOM 消费；Super Harness 只拥有 DeerFlow 兼容、产品语义、UI/BFF、业务集成与窄适配层，禁止实现第二套 Run/Loop/Memory/Tool/Skill/MCP/Sandbox/Artifact/Checkpoint/Permission/Multi-Agent Runtime。若归属不清，在新增领域对象、状态机或持久化表之前停止并进行架构裁决。
- 按上一条判定须上游，或验收过程中确认必须完善上游 Haifa Agent SDK 时，只能在 `D:\workspace\haifa-agent` 仓库以 `dev`（HA `0.1.3-SNAPSHOT`，不设 baseline 分支）为起点，为每个功能创建独立 worktree 和新的 `feat-*` 开发分支；禁止直接修改或提交到 `dev`。完成后须用上游发布脚本把公开 JAR/BOM 发布到该 worktree 自己的坐标，由本项目按上面的 SDK 版本规则消费，同时记录 SDK commit（取自上游发布清单）、发布与回归证据；随后从该 `feat-*` 分支向 `dev` 发起 GitHub PR，由主控在 SHPM 登记对应任务与 PR/验证状态（`shpm verify add`/`shpm issue add`），并明确提醒人工审查合并。任何智能体不得自行合并该 PR，除非人工针对该具体 PR 另行书面授权。
- 为同一功能引入的领域模型复杂度必须与冻结 DeerFlow 的对应实现保持平衡或更低，禁止为了协议兼容复制、叠加或扩张不必要的领域对象、状态机、持久化概念和抽象层。若有证据表明只有更高的领域复杂度才能实现验收范围，执行智能体必须在编码前提交包含 DeerFlow 对照、复杂度增量、不可替代原因、风险与简化备选的设计说明，先经主控 Agent 评审，再等待人工裁决，未获人工明确同意不得实施或合入。

## 开发、测试与证据

- 后续新增脚本以 Python 为主，核心逻辑集中在 Python 中；如需方便不同平台调用，可提供 `.ps1` 和 `.sh` 薄封装，仅负责必要的环境准备、调用 Python、透传参数与退出码，避免在封装中重复实现核心逻辑。
- 隔离：每个工作包使用独立 branch/worktree、端口组和数据目录；每种启动方式（profile）和每次 UI 启动使用独立的新 data-root（原因见「凭据与数据目录」）。
- 结果口径：失败项记 `FAIL`，未运行项记 `NOT_RUN`，外部前置缺失记 `BLOCKED_EXTERNAL`，并写明首个失败签名或缺失的前置。不得用静态验证、局部冒烟、预检或旧证据替代运行证据，不得把失败或延期记成 `PASS` 或 `NOT_RUN`，脚本不得自动提升阶段 `PASS`/`GO`。不得放宽断言、缩改 Oracle 或删除失败测试换取绿灯；确需裁剪或放宽某条 Oracle 时，须经人工逐项批准并在 SHPM 登记为已知差异，批准只对该条有效。
- 检查时点：commit/push 前运行范围内定向测试、`git diff --check`、secret scan 和 SDK 基线检查（`pwsh -NoProfile -File tests/checks/build/verify-haifa-version-baseline.ps1`）。报告可合并前必须完成「按改动范围定门禁」给出的门禁和真实 smoke，并保留真实 Run/Tool/Artifact 证据。源码、SDK 构件或断言改变后，受影响的旧 `PASS` 失效。
- 原始证据外置：运行任何 `mvn clean` 或重跑前，须把需保留的 Playwright trace、截图及前后端/Provider 原始日志复制到该工作包的外置独立证据目录（例如 worktree 的 `local-tmp/<slot>/evidence`，不能是另一个 `target`），并记录原始与复制路径。首次失败的证据同样保留。原始日志已消失时，只能明确标注为从先前终端输出重建的摘要，不得冒充原始证据。本文件其余各处的「保存原件」均指本条。
- Playwright 验收除功能断言外还必须报告流程健康度：各轮（冷轮仅在跑了时）的准备/构建、服务就绪、功能断言、证据归档耗时与总耗时，是否重试，首个超时或卡点，Next 动态编译时间，trace/screenshot/日志位置，以及结束后端口和子进程是否清理；复审及返工耗时另行记录。
- 等待必须有明确对象、deadline 和超时证据，不增加原预算或自动重试；不得用无界等待掩盖启动或断言失败。
- 回报默认给退出码、测试计数、逐 case 结果、首个失败签名、依赖影响和原始证据路径；复用明确的路径、SHA 和断言表，仅为定位问题展开必要日志片段，不反复输出同一全文。独立检索和读取合并到同一工具轮次；依赖步骤和修改顺序仍串行。
- 仅并行执行真正独立且资源、端口、数据目录隔离的检查。不同槽位的定向 Java 测试、合同检查与 Haifa 单后端受控 UI 可以并行。重型全仓 Maven、双后端差分、正式 live UI 与共享 Provider 由主控排队，不为减少轮次而并发。

### 验收分层（L1 日常 / L2 工作包差分 / L3 演示）

本节是现行规则，人工批准记录见节末。把「证明切片正确」与「与冻结 DeerFlow 参考后端差分一致」「客户演示 GO」分开，避免把里程碑级差分叠进每个日常 PR。

#### 三档定义

| 档位 | 用途 | 默认证明 | 不默认要求 |
| --- | --- | --- | --- |
| **L1 日常合入** | 单个可合并切片 / PR | `change_scope.py` 给出的门禁：定向或全仓按指纹、`ui-*-round-x1`（产品/前端）、协议面可用 `controlled` + 合同/HTTP·SSE 断言 | 双后端差分、默认两轮暖轮、演示连续双轮 L3 |
| **L2 工作包差分** | 工作包或相关合入批次出口 | 冻结前端 + Haifa / 参考后端双后端差分（如 `run-w18-dual-backend`），按 WP Oracle 攒批 | 每个日常 PR 强制双后端 |
| **L3 客户演示** | 演示阶段 `GO` | 同一主线 SHA 连续两轮整链证据等既有演示门禁 | 用 L1/L2 单项 PASS 提升阶段 `GO` |

- 日常 PR **不**强制双后端；双后端是 L2，由主控在工作包出口或相关批次安排。
- 产品改动默认 **一轮**暖轮（`x1`）；`x2` 仅当改动落在 `tests/ui` / `scripts/ui` / `scripts/frontend`（UI 用例或 runner 自身），或主控/复审点名提升。
- 协议/投影/REST·SSE 形状类改动（`server/api`、`server/auth`、`super-harness-events`、`super-harness-workspace` 等，以 `change_scope.py` 表为准）默认 `controlled`；真正吃模型语义的路径（`run` / `prompt` / `tool` / `config`、主资源、`pom.xml`、文件名含 `live`、SDK 身份变化、未知路径）仍为 `live`。
- 各条 L1/L2 裁剪**不适用于**客户演示「同一主线 SHA 连续两轮 L3」。

#### 流程与分工

- L1 顺序：实现 → 定向测试 / 合同 / 单 case 冒烟 → 集中自审修复 → 推送并开 PR → 一次性读取机器人审查并集中处理 → 变基到最新 `main` 并固定候选 → 跑 `change_scope` 给出的 L1 门禁 → 报告可合并。
- 内环与 L1 合并：当执行者在本槽位跑完的定向测试 + 单 case/一轮 UI **恰好覆盖** `change_scope` 输出的 gates，且证据绑定最终候选 SHA、`sourceDirty=false` 时，主控抽检通过后可计为该 PR 的正式 L1 门禁，不必为同一 gates 再整套重跑。未覆盖的项仍须补跑；L2 差分与 L3 演示不在此合并范围内。
- UI spec 或 fixture 配置变更先用 `scripts/ui/run-w18-ui-smoke.ps1 -CaseTitle <完整标题>` 执行受影响 case，`-PreflightOnly` 仅做本轮预检。
- 攒批：自审和审查发现的问题按根因攒批，一次修复后重跑受影响用例；禁止每修一个点就重跑全仓或 UI 整套。正式门禁失败先保存原件并定位，再按影响范围修复复验。
- L1 的 `verify-full`、正式 UI 仍由主控排队或明确放行后在执行者槽位执行；L2 双后端与 live 重轮次优先由主控排队。执行者未获放行不启动昂贵项，未运行的记 `NOT_RUN`。
- 变基在审查意见处理完、固定候选提交之前进行，由主控要求或执行；候选固定后不再 amend/rebase，除非主控明确要求。

#### PR 与机器人审查

- PR 以可审查状态打开，不用 draft：机器人只在 PR 打开、draft 转 ready 或评论 `@codex review` 时审查，不审查 draft。此时的 PR 不代表任何门禁通过，PR 描述须写明 L1 正式门禁是否已跑、L2 是否延后到工作包出口。
- 开 PR 后最多等机器人 10 分钟，超时按 `NO_REVIEW_YET` 记录并继续；**不因等待审查而阻塞已就绪的 L1 门禁排队**。结论为 `REVIEW_FAILED` 时，可以评论一次 `@codex review` 重新触发；仍然失败就如实记录并继续。
- 审查意见只处理一轮：正确的采纳，不采纳的各写一句理由；不再主动请求重新审查。
- 正式门禁通过后才出现的新意见，只有确认会导致错误行为的才修改并重跑受影响门禁，其余登记为后续事项。
- 向人工报告 PR 可合并之前，必须运行 `python scripts/governance/pr_review_digest.py --repo <owner/name> --pr <编号>` 并给出结论。
- 机器人不可用期间（额度用尽或服务故障，以人工告知为准）：开 PR 后运行一次 `pr_review_digest.py` 记录当时状态，不再等待 10 分钟，结论照实记 `NO_REVIEW_YET`，并在 SHPM 写明原因。改动产品代码的 PR，主控在报告可合并前对完整 diff 做一次独立代码审查（`/code-review` 或等价方式），意见按上面的规则处理一轮，结论写入 PR 评论和 SHPM；只改测试、脚本或文档的 PR 沿用主控的源码复核。向人工报告时分别写明机器人结论和替代审查结论，不得把替代审查记为机器人审查。机器人恢复后，对仍未合并的 PR 评论一次 `@codex review`；已合并的不追溯，除非人工要求。

#### 按改动范围定门禁

- 运行 `python scripts/governance/change_scope.py`，以输出的 `gates`、`uiModel` 和 `acceptanceTier` 为准；分类表只在该脚本里维护。输出是 **L1 下限**，主控或复审可以要求更多（含提升为 `x2` 或追加 L2）。
- 全仓门禁的结果绑定 Java 输入指纹：指纹相同且已有真实运行的通过记录时，由 `verify-full.ps1` 自动复用并注明来源。
- UI 正式轮次：`uiModel` 为 `controlled` 时用受控模型；为 `live` 时用真实模型。

#### 正式轮次前的核对

- L1 小切片：任务卡保留 3～5 条原子断言即可，不必为每个 PR 写完整 Oracle 映射表。
- 昂贵的全仓构建或 **L2 双后端** UI 开始前，仍须列出 Oracle case → 实际断言 → 必需前置 → 预期原件，主控复核关键正反例；尤其不能用“不存在资源”的 404 冒充“已存在但跨身份不可访问”的隔离证据。映射不完整时先修测试，不启动该昂贵轮次。
- 正式 UI 前一次性核对冻结来源、SDK 实际落盘构件、前端依赖与浏览器、完整槽位端口绑定、独立 data-root/凭据引用、Provider、Gateway 和前端代理目标。预检失败先修环境或如实记 `NOT_RUN`/`BLOCKED_EXTERNAL`，不要等长轮次超时后才发现配置错误。

#### UI 轮次

- L1 产品/前端改动默认 **一轮**暖轮（`ui-<model>-round-x1`）；该轮失败须先用同一脚本重跑一次，再定位。`x2` 仅见于 UI 用例/runner 自身变更或主控提升。
- 冷轮只在 `gates` 含 `ui-cold-round`，或前端依赖、槽位、代理目标变化、构建戳失效时跑。新 worktree 优先从主检出共享缓存恢复前端构建戳；无法恢复时首轮自然为冷轮。冷轮单独启动。
- 同一提交上的重复运行可以在一次启动内完成（`-Passes 2 -SkipBuild`），仅限 runner 里已在真实运行中验证过可重复的 profile，其余 profile 分开启动。
- 构建产物能否复用以构建戳为准（见「Maven 与构建」）。复用构建不等于复用 Run、Thread 或数据目录；测试结果能否复用只按「合入后复用」和全仓门禁的指纹复用判断。

#### 合入后复用

- 合入后 `main` 的源码树哈希和 SDK 身份与已验证候选相同，即可复用该候选的门禁和 UI 结果，合入方式不限。用 `python scripts/governance/evidence_reuse.py --target <合入后提交> <证据>` 核对，全部 `REUSABLE` 才复用；否则在合入后的提交上重跑。
- **冲突感知（并行合入）**：他任务合入使 `main` 前进后，本任务变基到新 `main` 时：Java 输入指纹未变则可复用 `verify-full`；与本任务改动路径、共享协议面无交集且对应证据仍 `REUSABLE` 的 UI/脚本门禁可保留；仅对指纹变化、路径冲突或 `STALE` 的项重跑。不得在未变基、未核对的情况下用旧基线证据合入。
- 固定端口主服务的刷新与 smoke 在每次合入后照常执行（命令见「派单与子智能体调度」）。

#### 已登记的偶发失败

- 全仓门禁只失败 `tests/known-intermittent.json` 内的用例（不超过两个，且本次改动未触及其覆盖范围）时，入口把失败的测试类重跑 3 次，全部通过则记通过并注明；首次失败原件照常保留并计入该 issue 的发生次数。
- 清单只由主控增删，新增时告知人工；同一条累计三次必须安排修复。

#### 批量验收与 L2 差分

- L2 双后端差分按工作包或相关合入批次攒批，不边合入边对每个小 PR 跑全量差分。
- 先跑客户演示正向主链，再按依赖拆分独立用例，通常每批 10–15 项；执行前标明前置、依赖和影响范围。
- 批次中不边测边改产品。局部失败先保存原件、登记问题并继续不受影响的用例；共享服务/身份/数据/端口等前置失效时只停止受影响批次，主链失败后的依赖用例记 `NOT_RUN`，其他独立组继续。
- 批次结束按根因归并问题，一次修复后重跑受影响用例及必要回归；代码与断言稳定后才运行 L2/L3 昂贵轮次。
- 演示范围外的高级/偏门缺陷先挂 issue 和风险，不阻断无依赖的演示验证；必需 Oracle 失败仍保留 `FAIL`/`NO_GO`，演示触及故障路径时作为阻断处理。

#### SHPM 登记封装

- 派工用 `python scripts/governance/shpm_dispatch.py`，一次完成 task create（含提示词登记）、sprint link、task start、assign create。
- 脚本回归用 `pwsh -NoProfile -File scripts/verify-scripts.ps1`，摘要写 `local-tmp/verify-scripts/`。
- 门禁结果用 `python scripts/governance/shpm_record_gates.py` 从证据组目录登记全仓门禁摘要、脚本回归摘要、UI 证据目录和主服务刷新摘要；默认只打印一张表，加 `--execute` 才写入，参数见 [SHPM 使用手册](docs/shpm-manual.md)。结果原样登记，`deliver`、`accept`、阶段结果和 `GO` 仍由主控手工执行。登记时区分 L1 日常门禁与 L2 差分证据，避免把 WP 出口差分误记为每个子任务的 PR 门禁。

#### 交接与等待

- 每个执行 worktree 在 `local-tmp/acceptance-handoff.md` 维护短交接，模板见 `docs/acceptance-handoff-template.md`：只记录范围、源码 SHA/dirty、SDK 选择与发布来源/hash、断言/证据映射、首个 FAIL、未运行项、本任务是 L1 还是附带 L2，以及下一步。事实变化时更新，不复制长日志、密钥或 reasoning 正文；该交接不是另一份任务账本，不替代 SHPM。
- 后台任务使用有界进程等待并在完成时合并读取结果摘要；等待期间推进不依赖它的检查，避免固定 sleep 后反复读完整日志。等待方至少每 60 秒在自己的会话输出里给出有意义的进展或卡点；这不同于子智能体向主控的 15 分钟 checkpoint（见「派单与子智能体调度」）。

批准记录：2026-09-30 批量验收；2026-10-03 按改动范围定门禁、UI 默认两次暖轮、SHPM 登记封装；2026-10-05 合入后按树哈希复用、指纹复用与 UI 模型选择、已登记的偶发失败、一次启动内重复运行；2026-10-06 本节合并改写，废止「快进合入不重跑」和手工门禁映射表，门禁时点改为报告可合并前，正式门禁由主控排队，Oracle 放宽须逐项批准，槽位 4（2026-10-03 批准为附加槽位）转为常规槽位，子智能体槽位共四个；同日批准机器人审查不可用期间的替代审查流程；**2026-10-15 验收分层 L1/L2/L3：日常 PR 默认一轮暖轮、协议面 controlled、双后端改工作包出口；产品默认 x2 暖轮废止为 L1 默认 x1。**；2026-10-16 子智能体槽位由四个扩容为六个，新增槽位 5 `21618/26618`、槽位 6 `22618/27618`（经 loopback 绑定预检，均低于动态端口范围）。

## Git 与 GitHub

- Git commit message（标题与正文）以及 GitHub PR 的标题、描述和评论（含 review comment）必须使用英文；适用于本仓库及上游 `D:\workspace\haifa-agent` 的 PR。命令、路径、配置键和 API 名称保留原样。
- `haifa-agent-super-harness` 的 GitHub PR 一律使用 squash merge。

## 环境与排障记录

软件安装、依赖准备、配置、凭据或权限问题实行“首次发现、立即沉淀、后续先查”：首次发现者须在不泄露密钥的前提下，把症状、根因、影响范围、精确检查命令、最小修复步骤和修复后验证写入本节对应主题，并在 checkpoint 中通知主控；后续智能体开工前必须先读取本节，禁止重复试错，禁止用复制 SDK/`node_modules`、全局安装、放宽系统权限或污染用户目录来绕过。本节只保留仍有效的结论：不写某次任务的证据路径、用例通过情况或处理经过，这些留在该任务的证据目录和 SHPM。

### Maven 与构建

- 在仓库根目录使用 Java 21、Maven 3.9.15 和 PowerShell 7（`pwsh`）；也可用 `mvnw.cmd` / `./mvnw`，Wrapper 首次使用需下载分发包，之后可离线复用。
- 新 Java 验收测试从 `ProductionWriteFileHttpTest` 黄金样例起步，复用 `testsupport` 中的模型、审批、Gateway 与进程夹具；独立数据、证据和端口的配置见 [README「定向测试与 SDK 版本选择」](README.md#定向测试与-sdk-版本选择)。
- 定向测试：`pwsh -NoProfile -File scripts/test-targeted.ps1 -Test 'ProductionWriteFileHttpTest,AcceptanceFixturesTest' -LowMemory`。替换 `-Test` 即可选择用例，其他模块用 `-Module`；入口固定带 `-am`，匹配 0 个测试算失败，不传额外 Maven goal、`-DskipTests` 或 `-Dhaifa.version`。
- 慢测试：单个用例固定耗时很长的测试标注 `@Tag("slow")`，全仓门禁和定向测试默认不运行它们。用 `pwsh -NoProfile -File scripts/test-targeted.ps1 -Slow` 运行全部慢测试，加 `-Test <类>` 只跑指定类里的慢测试。改动触及慢测试覆盖的代码时，以及工作包结算前，由主控运行慢测试并登记结果。新增 `slow` 标签须在交接里说明理由，不能用它回避失败的测试。
- 全仓门禁：`pwsh -NoProfile -File scripts/verify-full.ps1`。先跑秒级预检（SDK 基线、`git diff --check`、secret scan），失败即停；同 Java 输入指纹已有真实通过摘要时复用（`-NoReuse` 强制真跑），否则运行离线 `clean verify`，生成 exec JAR、构建戳和摘要 JSON。并行进程数按空闲内存自动选择，内存不足时自动加 `-LowMemory`；`-NoKnownIntermittent` 关闭已登记偶发失败分支。执行前保存原件，并停止当前 worktree 中占用该 JAR 的精确进程。阈值、摘要字段和实测耗时见 [README「构建与验证」](README.md#构建与验证)。
- SDK 选择由入口读取本 worktree 的 `local-tmp/sdk-selection.json`（规则见「范围与依赖」）。测试日志位于 `local-tmp/test-targeted/`、`local-tmp/verify-full/`，stdout 输出 JSON 汇总。
- 构建复用：`scripts/ui/run-w18-ui-smoke.ps1` 按构建戳决定是否重建。后端 JAR 的构建戳是 Java 输入指纹，只改文档、脚本或 UI 用例的提交不使 JAR 失效；前端构建戳只含冻结前端源码树、lockfile 和代理目标，本地不匹配时先从主检出的共享缓存恢复，再不行才 `pnpm build`。`-SkipServerBuild`/`-SkipBuild` 强制复用，构建戳不符时失败。`flow-health.json` 的 `serverBuild`/`frontendBuild` 记录 `REUSED`/`RESTORED`/`BUILT`。缓存位置和键见 [README「构建与验证」](README.md#构建与验证)。
- 低内存：空闲提交内存很低时，默认堆的 `mvn` 与 fixture JVM 会报 `Could not reserve enough space` / `Native memory allocation (mmap) failed ... G1 virtual space`。使用入口的 `-LowMemory`；自行启动 JVM 时，fixture 用 `JAVA_TOOL_OPTIONS=-Xmx96m -XX:+UseSerialGC`，Maven 用 `MAVEN_OPTS=-Xmx512m -XX:+UseSerialGC`，surefire 用 `-DargLine=-Xmx768m -XX:+UseSerialGC`。
- 冻结资源字节：冻结文件必须按 Git 提交的原始字节落盘，来源 / 哈希校验必须通过。`core.autocrlf=true` 的新 worktree 可能把冻结资源检出为 CRLF，例如 `super-harness-server/src/main/resources/upstream/deerflow/w15/` 下的 Python 资源，Gateway 构造时报 `Frozen Skill scanner resource hash mismatch`。检查：逐项比对该目录 `manifest.json` 的 SHA-256 与实际字节。修复：依靠仓库 `.gitattributes` 的 `text eol=lf` 规则，在相关路径 clean 的独立 worktree 用 `git archive HEAD <路径>` 导出并解包（冻结前端对应 `frontend`、`third-party`、`contracts`），再重建。不修改 manifest 或固定哈希、不关闭校验、不改全局 Git 配置。

### 已有 data-root 的 Skill scanner 派生缓存

- 症状：升级已审核的产品 JAR 后，Gateway 启动报 `Skill scanner cache changed`；新 data-root 测试通过不能证明旧 data-root 缓存兼容。先保存失败日志，分别从旧／新 exec JAR 的 `upstream/deerflow/w15/` 读取资源原字节，用 SHA-256 和逐字节比较核对 `skills/scanner/` 对应文件；同时核对该文件在两版本之间的实际 Git 变更。
- 仅当缓存精确等于旧 JAR、资源确实随已审核的新 JAR 更新时，将该单个派生文件归档并移到独立证据目录，让原构造器按新 JAR 和原 manifest 校验重新生成。保留原失败，不关闭 hash 校验、不改 manifest、不移动整个 `skills/`，不触碰数据库、凭据或用户 Skill 源包。缓存不匹配旧资源、存在链接或变更来源不明时停止，不按升级处理。
- 按原入口和原期限刷新后，核对新缓存字节、健康检查、真实模型 smoke、原 data-root／凭据及端口／PID。新版本失败需要回滚旧 JAR 时，先恢复归档的对应旧派生资源；不得在缓存不兼容时把服务回滚启动失败记为 ready。

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

### MCP 承载（Haifa SDK 0.1.3）

- 协议版本：公开 `io.haifa.agent.starter.McpServerSpec.streamableHttp` 默认协议版本为 `2025-11-25`，而 `scripts/mcp/start-v2-test-mcp-servers.ps1` 起的本地 fixture 只协商到 `2025-06-18`。症状：`McpToolPlatforms.connect` 成功返回（不抛异常），但 SDK `optional()` 会静默降级成 0 个 Tool，模型侧 `request.tools()` 为空，表现为“桥接成功却没有 MCP 工具”。检查：对照 fixture `initialize` 的 `protocolVersion` 与 spec 的 `protocolVersion()`。最小修复：映射时显式 `.protocolVersion("2025-06-18")`（协议常量，非服务器/Tool 名称硬编码）。
- 只读 Tool 也要审批：只读 MCP Tool（`McpServerSpec.readOnly()`）仍会命中 `PolicyPresets.standardApproval()` 的 `NETWORK_ACCESS` 规则并进入 APPROVAL。症状：Run 首次请求工具后停在 `WAITING_APPROVAL`，不会自动执行。检查：SSE 里出现 `artifact.human_input.request_id` 卡片。集成测试须以冻结隐藏回复（`additional_kwargs.human_input_response`，`option_id=approve`）批准后 join 同一 Run 的 stream，才能拿到真实 Tool 结果。
- `hasTools()`：仅由 MCP 提供 Tool 的 `HaifaRunBridge` 必须让 `hasTools()` 为真，否则 SDK 的 `tool.call.requested` 在冻结 transcript 投影里无法映射，SSE 直接报 `unsupported_event_projection` 并提前结束。最小修复：`hasTools = !tools.isEmpty() || !mcpSpecs.isEmpty()`。

### 受信技能脚本链（SDK 0.1.3，sdkCommit 52cac0db）

- Product schema 子集限制：SDK `ToolDefinitionValidator` 校验输入 schema 使用「有界 schema 子集」；`pattern` 等关键词被拒（`inputSchema pattern is outside the bounded schema subset`，在 ToolAssembly.register 时抛出）。检查：装配即报错。最小修复：产品 input schema 只使用 type/minLength/maxLength/minimum/maximum/description/enum/required/additionalProperties，格式校验移到 argumentMapper（fail closed 同款）。
- 技能脚本启用链：`PUT /api/skills/{name}` 对含 `scripts/` 的外部技能在启用时自动跑冻结静态 screen 并记 `bundled_reviews` 证据；语义 review 默认关闭（W15 parity）。此前 `SkillReviewApprovals.evidence` 假定 screen 结果带 `decision` 字段，静态-only 模式下返回的冻结 facts 没有 `decision`，`PUT` 直接 422 `Skill review did not permit publication`。最小修复：evidence() 在缺 `decision` 时接受未 blocked 的冻结 facts（decision=allow、authorization_source=configured-skill-screen）。
- Loader 双接缝：技能脚本平台经 `SkillContentLoader.loadResourceBytes` 读取冻结包字节做物化；SH 的 store 级 wrapper（`currentReviewLoader`）此前只实现 `load(...)`，`(binding, visibility) -> ...` lambda 使字节读取落到接口 default（派生自可读 activation content），执行入口报 `TOOL_INVOCATION_FAILED` 技术细节外无 stack、无法定位。最小修复：wrapper 实现 load 与 loadResourceBytes 两条接缝，各自先做 grant 权限校验再委托 Composite loader；诊断时优先用公开入口的直接 JVM 探针（平台 + 真实 runtime + provider.invoke）复现，投资该接缝能让失败带完整 stack。
- Shade 签名文件：基线升级后部分依赖 JAR 带 META-INF 签名文件；maven-shade uber JAR 必须排除 `META-INF/*.SF|*.DSA|*.RSA|SIG-*`，否则 exec JAR 启动即 `SecurityException: Invalid signature file digest for Manifest main attributes`。
- Credential Manager 饱和：本机 Credential Manager 已存在大量 `haifa:os:HAIFA_SUPER_HARNESS_*` 凭据（实测 869 条）；新建 data-root 首次启动的 continuation key 写入报 `CredWrite failed with error 8 (STORE_FAILURE)`，Extensions 现象与条数未做阈值验证。规避：该 lane 用 `HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF=env://NAME` + 进程环境注入 256-bit Base64 key（每 profile 每轮一个新 data-root + 新 key，不打印、不落盘）。该引用在 Windows 也被产品代码支持；存量凭据的清理与阈值裁决由主控处置（删除会影响对应 data-root 的重启）。

- bun 运行时必须是 exact file（符号链接曾打死 Run 准入）：SDK 的 `SkillScriptPlatforms$SkillScriptRuntime.preflight` 要求声明的 bun 可执行文件是**非符号链接的普通文件**，否则 `SkillScriptPlatforms.trusted` 抛 `HaifaAgentException: trusted script runtime executable does not exist as an exact file`（`SkillScriptPlatforms.java:356/182`）。该异常发生在 `HaifaRunBridge` 构造期，被 `DeerFlowThreadRunApi` 的准入 catch 收敛为 `500 {"detail":"Run could not start"}`，Run 从未准入（无 durable 记录），被吞后日志里也看不到原因。症状矩阵：`HAIFA_SUPER_HARNESS_HOST_SHELL` 关闭、或未声明 `HAIFA_SUPER_HARNESS_BUN_PATH` 时 Run 正常（技能脚本平台不装配）；两者同时满足且 bun 路径是 symlink 时**每个**新 Run 都 500。检查：`ls -la <bun>`、`stat -c '%A %n' <bun>`。现行修复：`SkillScriptToolConfig.bunExecutable` 把声明的 symlink 解析到真实文件（`/usr/bin/bun -> ../lib/node_modules/bun/bin/bun.exe` 这类打包布局可直接使用），解析不到则保持工具关闭；准入 catch 另补一条有界诊断（frozen route + 异常类 + 有界 cause 类链，不记 reason 文本，避免客户端文本/凭据经异常消息入日志）。只能停在未含该修复的旧构件时，把 `HAIFA_SUPER_HARNESS_BUN_PATH` 写成真实文件绝对路径。

### 真实模型诊断

- 静态 review 的 Python：W15 静态 review 使用 `python -I -B`，隔离模式不加载用户 site-packages。系统 Python 普通导入 `yaml` 成功，不能证明隔离子进程可用；检查：`<python> -I -B -c 'import yaml; print(yaml.__version__)'`。缺 PyYAML 时 review 拒绝，不能去掉 `-I` 或在线安装目标依赖绕过。以进程变量 `HAIFA_SUPER_HARNESS_SKILL_PYTHON` 指向已经具备该依赖的解释器；本机现有 `D:/agents/deer-flow/backend/.venv/Scripts/python.exe` 带 PyYAML 6.0.3（须先实际 `Test-Path` 并用该解释器 `-I -B -c 'import yaml; print(yaml.__version__)'` 核对）；`D:/workspace/haifa-agent-super-harness/deer-flow/backend/.venv/Scripts/python.exe` 当前不存在，不能因前端冻结来源在该目录就推断解释器也在，仅用于运行纯静态审查模块，不启用其后端或目标脚本。
- `base_url` 决定 SDK 方言：`DeerFlowConfigurationApi.binding` 仅在 endpoint 精确为 `https://api.deepseek.com` 时使用 DeepSeek 方言并固定 `thinking=disabled`。把真实模型临时改到本地代理路径时，standard 方言会在发 HTTP 前拒绝（`provider endpoint path must be /v1`，Run 为 `MODEL_CALL_FAILED`）；改成 `/v1` 虽可发出本地请求，但请求缺少原 DeepSeek thinking 选项，不能视为同语义采集。检查：核对实际 model config、SDK bytecode `validateSnapshot`、原 `model.call.failed` 与 Provider 是否收到 HTTP。处理：恢复直接 HTTPS 配置，采集前置 FAIL 单独保留，不以代理改写请求或更换方言掩盖差异；后续采集须先证明保持原请求语义。

### Windows 快照目录观察

- 文件身份：Windows/JDK 21 的 `BasicFileAttributes.fileKey()` 可返回 null，不能据此把配置零写入反例退化为只比内容 hash。检查命名配置文件：`fsutil file queryfileid <data-root>/owners/default/configuration.json`，只读返回非零 128 位文件 ID，本机无需提权。测试先使用非空 JDK fileKey，缺失时仅 Windows 可用此原生观察器（有界 5 秒、exit 0、输出上限 4096 bytes、严格一个非零 32 位 hex ID）。同值 REST 更新的正控制必须实际原子替换并改变文件 ID；拒绝反例同时断言文件 ID、mtime、size 和 bytes/hash 不变。不能添加生产 Store/写计数器、忽略观察器失败、推断没有临时写尝试，或以 hash-only 冒充没有配置发布。
- 目录句柄：Windows 上 `Files.walk` 进入正在写入的 `.completed-*` 快照目录会持有目录句柄，阻止 recorder 将其原子重命名为 `completed`（`AccessDeniedException`）；关闭遍历后同一次 rename 成功。验收观察器禁止递归扫描未发布目录，须按真实 workspace/Run 定位确切 `completed/baseline.properties` 并检查存在，再验证重启后读取；保留原有有界等待、无 lazy-read 要求与内容断言，不能延长超时或靠重跑掩盖。

### PowerShell 7

- 仓库 PowerShell 脚本依赖 PowerShell 7（`pwsh`，.NET Core），不能用 Windows PowerShell 5.1 代替；本机已安装 7.6.6（WindowsApps 下的 `pwsh.exe`，在 PATH 中）。5.1 下的典型症状：`scripts/frontend/start-haifa-frontend.ps1` 遇 `??` 报 ParserError；`scripts/frontend/verify-haifa-frontend-profile.ps1` 报 `[System.IO.Path]` 缺少 `IsPathFullyQualified`；`run-w04-frozen-ui-files.ps1` 的 `$PSScriptRoot` 默认参数为空，且 inventory digest 因 .NET Framework 区域排序而与 7.x 不一致（假阳性，不代表前端漂移）。因此 5.1 下的任何结果都不得记为 PASS/FAIL，只能记为 `NOT_RUN`；Maven `clean verify` 不受影响。检查：`Get-Command pwsh` 可解析，且 `pwsh -NoLogo -NoProfile -Command '$PSVersionTable.PSVersion.ToString()'` 返回 7.x；启动器可用 `pwsh -NoProfile -File scripts/frontend/start-haifa-frontend.ps1 -DryRun -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow` 验证，须输出 `frontendSourceGate=PASS`、`dryRun=true`。若新环境缺 `pwsh`，经人工同意后安装官方 PowerShell 7（例如 `winget install --id Microsoft.PowerShell --source winget`）；禁止把脚本降级改写成 5.1 语法，禁止跳过 profile 校验直接运行 `pnpm dev`。
- 脚本顶部开 `Set-StrictMode -Version Latest` 后，访问不存在的属性会抛 `PropertyNotFoundException`，**包括对空数组取 `.X`**（`@().X` 也抛）。取集合字段一律写 `@($arr | ForEach-Object { $_.X })`。另：`param([string]$Foo)` 会对变量 `$Foo` 加类型约束，后续把解析出的 `PSCustomObject` 赋给同名 `$Foo` 会被强制转成字符串并按路径误用；JSON 解析结果变量须另起名（如 `$ManifestPath` 参数 + `$manifest` 局部变量）。
- PowerShell 7.4+ 的 `Test-Json`（JsonSchema.Net）默认拒绝文件相对的 `$ref`，报 `The JSON schema contains a reference that could not be resolved ... use the 'FetchExternalReference' parameter`。本机 7.6.7 复现：`contracts/upstream/deerflow/schemas/` 下用兄弟文件 `$ref` 的 schema（如 `projects-list-response.schema.json`、`thread-runs-list-response.schema.json`）不加 `-FetchExternalReference` 时 `Test-Json` 直接判无效，会让 `verify-deerflow-contracts.ps1` 的夹具校验假失败。检查：`pwsh -NoLogo -NoProfile -File scripts/contracts/verify-deerflow-contracts.ps1`。最小修复：给 `Test-Json` 加 `-FetchExternalReference`（该脚本已修）；不要改成复制 schema 或内联 `$ref` 绕过。

- 经执行入口传入含中文/emoji 的 here-string 写盘会损坏编码：`$x = @'…中文…'@; Set-Content -Encoding utf8` 落盘的是"UTF-8 编码的 GBK 乱码"，读回出现 `锝滈涔` 一类字符，但 `\ufffd`（replacement char）计数仍为 0，容易被误判为正常。检查：用只含 ASCII 的探针读取文件并按 `\uXXXX` 转义比对，如 `io.open(p, encoding="utf-8").read().count("\u4eba\u8bdd\u63cf\u8ff0")`，命中 0 即已损坏。最小修复：中文/emoji 文档与源码改用文件写入工具（`file_write`/`file_patch`）直接落 UTF-8；必须走 shell 时脚本源码只用 ASCII，正文用 `\uXXXX` 转义构造，或先由文件工具写好 UTF-8 正文再按字节复制。同一原因也会让 Python 脚本里的中文模式串匹配失败（`assert` 报 0 命中），不要据此认为目标文件缺少该内容。

### 冻结前端（pnpm / Next.js）

- 每个验收 worktree 使用独立的 `frontend/node_modules` 和 `.next`（`.next` 可由 runner 从共享缓存复制恢复，但不同 worktree 不得共用同一个 `.next` 目录）；主前端继续供人工验收使用。前端来源按「范围与依赖」的冻结版本校验。
- 在该 worktree 的 `frontend/` 目录准备依赖，先确认版本精确为 `10.26.2`，再安装：

```powershell
$env:COREPACK_ENABLE_NETWORK='0'
corepack pnpm --version
corepack pnpm install --offline --frozen-lockfile
```

- 安装输出须为 `downloaded 0`，并确认 `frontend/node_modules/.bin/next.cmd`、`playwright.cmd` 和 Playwright browser 可用。不使用系统 pnpm、全局安装或其他 worktree 的依赖副本；离线依赖不齐时 UI 记 `NOT_RUN`。
- 从仓库根目录检查启动配置：`pwsh -NoProfile -File scripts/frontend/start-haifa-frontend.ps1 -DryRun -SourceRepository <冻结 DeerFlow checkout 的绝对路径>`。正式启动用同一入口去掉 `-DryRun`；UI 验收使用 `scripts/ui/` 中的对应 runner。
- Next 构建前设置本槽位的 `DEER_FLOW_INTERNAL_GATEWAY_BASE_URL`，优先让 runner 完成构建和服务管理；构建复用见「Maven 与构建」。本地受控模型 fixture 使用 `context_window=32768`。
- 冻结文件重新落盘的方法见「Maven 与构建」的冻结资源字节条目。完整启动与验收命令见 [README](README.md)。

### 端口

- 端口表见「派单与子智能体调度」。
- 绑定预检：Windows 的 Hyper-V/WSL/NAT（`winnat`）会动态排除 TCP 端口（本机动态范围从 `49152` 起，排除段覆盖端口时 Next.js 报 `listen EACCES`）；现行端口均低于 `49152`，但排除范围仍可能变化。每次派工和启动前先执行 `netsh interface ipv4 show dynamicport tcp`、`netsh interface ipv4 show excludedportrange protocol=tcp`，并对分配的全部端口逐个用 loopback `TcpListener.Start()` 做实际绑定预检；`Get-NetTCPConnection` 只能发现监听者，不能排除 NAT 保留。预检失败时保留端口/进程/排除段证据，由主控重新裁决整组槽位或把 UI 标为 `NOT_RUN`；不得私自重启 NAT、提权、随意改单个脚本端口或停止主服务。修复后按同一完整端口组重复预检、服务就绪和 Playwright，并核实端口释放。
- 进程退出后的 `LISTEN` 残留：Windows 子进程 `waitFor` 完成且 `isAlive=false` 后，TCP 栈仍可能短暂保留该已退出 PID 的 `LISTEN`，同端口即时 `ServerSocket.bind` 会失败；不能把数秒后的空表或客户端 `TIME_WAIT` 倒推成失败瞬间的原因。验收停止阶段须在原有共享停止 deadline 内等待**同一精确端口的默认 loopback bind 实际成功**，记录首次 bind 错误、原生 TCP 状态、探测次数和释放耗时；超时仍 FAIL。不得延长原停止预算、启用 `SO_REUSEADDR` 掩盖占用、换端口、跳过绑定断言或把资源释放探测冒充功能重试。诊断只记录状态、PID 与端口，不记录请求、凭据或内容。
- W05 测试的固定端口：`W05ExactGatewayProcessRestartApprovalTest` 默认使用 `20618`，与槽位 1 的 Gateway 相同。槽位 1 有任务运行时跑全仓门禁，用例入口 `requirePortAvailable` 会抛 `Address already in use`；事后 `Get-NetTCPConnection -LocalPort 20618` 为空不能倒推当时未占用。处理：在启动门禁的同一 PowerShell 会话把进程变量 `HAIFA_SUPER_HARNESS_W05_TEST_GATEWAY_PORT` 设为本 worktree 所分配槽位的 Gateway 端口（例如槽位 4 为 `11618`），测试的两个精确 Gateway 进程共用该端口；主检出没有槽位，由主控指定一个当时空闲槽位的 Gateway 端口。设置前先停止本槽位自己的服务并对该端口做 loopback bind 预检。不得停止其它工作包的服务或占用主服务端口；结束后确认端口释放。

### 凭据与数据目录

- 临时 HTTP 测试的 OS 凭据累积：本机普通 `GatewayFixture.production` 曾持续报 `CredWrite failed with error 8 (STORE_FAILURE)`，单类复验也失败；不能仅据错误码断言物理内存不足或随意清理凭据。普通测试使用 `GatewayFixture.productionEnvironment` 的显式测试环境密钥，保留调用方显式引用；默认 OS 首次配置、丢失与重启由 `ContinuationKeyConfigurationTest` 验证。检查：`pwsh -NoProfile -File scripts/test-targeted.ps1 -Test "PlanModeLifecycleHttpTest,ContinuationKeyConfigurationTest,AcceptanceFixturesTest" -LowMemory`；只输出凭据存在性和计数，不输出值，不删已有数据库或主服务凭据。
- 持久 SQLite Runtime 的 model continuation 使用 AES-GCM 稳定密钥。Windows 默认使用**单个固定** Windows Credential Manager target：`haifa:os:HAIFA_SUPER_HARNESS_CONTINUATION_KEY`（不再按 data-root 分槽）。历史实现曾按规范化绝对 data-root 的 SHA-256 前 16 个十六进制字符分槽（`haifa:os:HAIFA_SUPER_HARNESS_CONTINUATION_KEY_<data-root-hash>`），每个 data-root 写一条凭据并最终写满 Windows Credential Manager，故已废止。仅当该固定凭据尚不存在时首次启动可生成并写入，已有凭据不得覆盖；已有数据库但凭据丢失必须启动失败，禁止静默换钥；固定凭据缺失时会回退读取旧的按 data-root 分槽凭据（只读复用、不改动其 key、也不迁移）以兼容既存数据库。由此，本机新建 data-root 共用同一把密钥。显式 `os://NAME` 属于外部托管引用，缺失时即使数据库尚不存在也必须失败。Linux/CI 必须通过 `HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF=env://NAME` 指向外部注入的 Base64 256-bit key。症状：启动前报 continuation key unavailable/invalid。检查：`Test-Path <data-root>/runtime/haifa-agent.sqlite` 确认数据库是否存在，`cmdkey /list` 只检查 `haifa:os:HAIFA_SUPER_HARNESS_CONTINUATION_KEY` 是否存在（不得输出 secret），并查看 `server-*.stderr.log`。最小修复：恢复该固定 OS 凭据或原外部 secret 引用，不能删除数据库或生成替代 key；修复后以同一数据目录完成关闭重启并验证 transcript/history/runs 可读。
- 由上一条推出：不同启动方式不得共用同一 data-root。例如 W05 Playwright 的 Interaction 测试 classpath profile 与生产 JAR ActiveReconnect profile 混用时，后者会报 `continuation key credential is unavailable for the existing Runtime database`。从首次运行起就为每种 profile 分配独立的新 data-root 子目录。

### 冻结 DeerFlow 参考后端

- 冻结源码中已有的 `backend/.venv` 不是完整的 locked 安装：直接用其中 Python 启动 Uvicorn 时，导入 `app.gateway.app` 报 `ModuleNotFoundError: No module named 'deerflow_extension_api'`；且 `uv sync --offline --locked --dry-run` 显示需下载 223 个包，不能假定可离线重建。检查：在 `deer-flow/backend` 下执行 `.venv/Scripts/python.exe -c 'import deerflow_extension_api, deerflow, app.gateway.app'`。最小修复：在**进程环境**的 `PYTHONPATH` 中只读加入冻结源码的 `backend/packages/extension-api` 与 `backend/packages/harness`，用独立 `DEER_FLOW_HOME`、`DEER_FLOW_CONFIG_PATH`、`DEER_FLOW_EXTENSIONS_CONFIG_PATH` 启动，不改冻结源码或现有 venv。验证 `/health` 返回 `{"status":"healthy"}`，且退出后释放所属槽位端口；若缺少真正的第三方包，记录 `BLOCKED_EXTERNAL`，不得联网安装或复制其他 worktree 依赖。

### OpenCode CLI

- 主控确认任务的独立 branch/worktree、端口、数据目录和验收范围，在派单提示词中提供 SDK 坐标、测试命令及证据要求。提示词正文由主控读取，作为一个位置参数传入；模型固定为 `opencode-go/deepseek-v4.1-flash`，必须在命令中显式指定。

```powershell
$taskPrompt = Get-Content -LiteralPath '<主控已确认的提示词文件>' -Raw
Push-Location '<已分配的 worktree 根目录>'
try {
    New-Item -ItemType Directory -Force -Path local-tmp | Out-Null
    opencode run --model opencode-go/deepseek-v4.1-flash $taskPrompt 2>&1 |
        Tee-Object -FilePath local-tmp/opencode-run.log
} finally {
    Pop-Location
}
```

- OpenCode 只读取分配的 worktree；命令、文件清单和哈希清单使用相对该根目录的路径，不使用 `..`，不读取 `~/.m2` 等外部目录。Maven 通过仓库入口使用已配置的本地仓库。
- `private-docs` 的提示词正文内联传入；需要的冻结源码由主控将最小只读副本放到 `local-tmp/<slot>/frozen-deerflow/`，注明路径和冻结 SHA。提示词中不包含密钥、SDK 源码或依赖副本。
- 任务需要 SDK 类型事实时，主控在派工前运行 `python scripts/governance/sdk_api_digest.py --worktree <worktree>`，并在提示词里写明摘要位于 `local-tmp/sdk-api/<版本>/`。执行者在该目录检索公开签名；摘要里没有的信息在交接里写明并停下。SDK 重新发布后主控重新运行脚本。
- 验收服务优先由仓库 runner 管理；自行启动的常驻服务在同一次 shell 调用的 `try/finally` 中启动、使用和清理，避免继承调用方管道后留存。CLI 直接运行或交给既有后台执行器，不使用 PowerShell `Start-Job` 承载。
- 用 `opencode-run.log`、Git 改动、checkpoint 和真实测试证据检查进展与交付；退出码 0 不能单独判定完成。权限拒绝或上下文不足时交回主控，不放宽外部目录权限绕过；检查和清理只针对本任务的精确进程。
- 有界调用需要续接已读上下文时，先确认原 CLI 的精确进程已终止并保存原件；从本任务 `--format json` 原始日志核对唯一 `sessionID`，用 `opencode run --session <sessionID> --model opencode-go/deepseek-v4.1-flash` 在同一 worktree 续接，仍设置独立 deadline。不得以默认 `--continue` 猜测全局最近会话；续接不改变功能验收预算，也不把先前 deadline 或 FAIL 改为 PASS。
`- Windows 下 Python `subprocess` 不通过 `opencode.cmd` 传递多行提示词：`.cmd` 的 `%*` 转发可截断换行后的正文，使模型仅收到首段但仍退出 0。使用已安装 shim 指向的原生 `opencode.exe`，以参数数组、明确 `cwd` 和单个完整正文参数调用，不拼接 shell 命令、不改全局配置。首次使用派单封装时，用该任务的 `opencode export <sessionId>` 保存首条 user text 的原始字节数和 SHA-256；若 CLI 使用带外层引号的参数包裹格式，先按该格式解码，再核对正文与提示词字节数和 SHA-256 完全一致；导出仅存本任务外置证据目录，不能只凭 CLI 0 或模型自行描述判断提示词完整。忽略目录中的上下文文件须提供准确绝对路径直接读取，不能因 glob 默认过滤 `local-tmp` 就越界搜索或放宽权限。
- 继承的 `PWD`：OpenCode 以环境变量 `PWD` 为工作目录，优先于进程 `cwd`。从 Git Bash 等会导出 `PWD` 的 shell 用 `subprocess.Popen(..., cwd=worktree)` 启动时，子进程仍带着调用方的 `PWD`。症状：执行者把调用方所在仓库当成当前目录，访问已分配 worktree 时 stderr 报 `permission requested: external_directory (...); auto-rejecting`，十几秒内以退出码 0 结束且没有任何改动。检查：stdout 里第一条 `pwd` 的输出。修复：启动前从子进程环境中移除 `PWD` 和 `OLDPWD`，或按上面的示例在 PowerShell 7 会话里用 `Push-Location` 启动。修复后用同一提示词重新启动，并核对 `pwd` 输出为已分配的 worktree。

- OpenCode 提示词传输保真：Windows 原生 executable 的位置参数在会话导出中**始终**比传入正文多出两个外层双引号；正文内的双引号还会被转义为 `\"`（2026-10-17 用原生 `.exe` + Python `subprocess` 参数数组复现，排除 PowerShell 绑定因素）。因此长提示词不要走位置参数：把精确提示词原样放到该 worktree 的 `local-tmp/<slot>/prompt.md`，位置参数只传一句不含引号的短指令指向该文件（已用有界探针验证模型会读取文件并输出文件中的标记）。仍须以 `sessionID` 执行 `opencode export <sessionID>`，对照 user/text part；位置参数正文不相同就记保真 `FAIL`，外层引号/转义归一化只能作诊断，不能冒充精确通过；提示词文件的字节数和 SHA-256 作为正文保真证据。不得读取无关会话，不使用 `--continue`，不以退出码或保真检查替代源码交付和运行验收。

### agy CLI（Antigravity）

- 固定使用 `--model gemini-3.8-flash-high`；任务隔离、SDK 摘要、提示词与验收边界沿用 OpenCode 章节。
- 执行目录由进程 `cwd` 指定：PowerShell 用 `Push-Location`，Python 用 `subprocess.Popen(..., cwd=worktree)`；`--add-dir` 仅添加目录。提示词文件先按 UTF-8 读取正文，再传给 `--print`（别名 `--prompt`），不能把文件路径当作正文。
- 在专用 PowerShell 7 启动会话注入 HTTP 代理，仅作用于进程。单轮调用：

```powershell
$env:HTTP_PROXY='http://127.0.0.1:2081'
$env:HTTPS_PROXY=$env:HTTP_PROXY
$env:ALL_PROXY=$env:HTTP_PROXY
$env:NO_PROXY='127.0.0.1,localhost,::1'
$taskPrompt = Get-Content -LiteralPath '<提示词文件绝对路径>' -Raw -Encoding utf8
Push-Location '<已分配的 worktree 根目录>'
try {
    agy --model gemini-3.8-flash-high --output-format json --print $taskPrompt
} finally {
    Pop-Location
}
```

- 流式调用用 `agy --model gemini-3.8-flash-high --input-format stream-json --output-format stream-json --print ""`。stdin 逐行写 `{"event":"user","message":{"role":"user","content":"提示词正文"}}` 并 flush；stdout 逐行解析 `init`、`step_update`、`result`，可实时读取文本增量与工具结果，stderr 单独保存。追加消息实测排队到下一轮，不是当前轮即时 steering。
- 写权限：无头模式（`--print`）无法弹出权限确认，需要写文件的任务不加模式参数时，`write_file` 会被自动拒绝，stderr 报 `no output produced — a tool required the "write_file" permission that headless mode cannot prompt for`，最终回复为空。需要写文件或执行命令的任务在每次调用时加 `--mode accept-edits`；2026-10-06 实测该模式下可在 worktree 内写文件，并可执行 `git status`、`pwsh -NoProfile -Command ...`。不修改 agy 的 `settings.json`，不使用 `--dangerously-skip-permissions`。该模式下 JSON 结果可能出现 `status=ERROR`（`The stream was interrupted`）而各步骤实际已完成，以文件和实际改动为准。
- 调用方设置独立 deadline，保留 stdout/stderr 与 `--log-file` 日志；不能仅依赖 `--print-timeout`。只读任务加 `--mode plan`；交付须核对最终状态、文件改动和真实验收证据，不能只看退出码。
- 需要续接时，先确认原精确进程已终止并保存原件；只从本任务 `--log-file` 中核对唯一 `Created conversation <ID>`，用 `agy --conversation <ID> --model gemini-3.8-flash-high` 在同一 worktree 续接，写入交接文件时仍须 `--mode accept-edits`。不得默认续接全局最近会话，且每次续接须有独立 deadline、原会话归属和新交付证据。

### SHPM 写入与文件工具

- SHPM 正文含非 ASCII 时不要经命令行参数或 PowerShell 管道传递。症状：`shpm issue add --detail '<中文>'`，
  或 `shpm ... --json | python -c ...` 回读时出现 `'�\udc80'`、孤立代理字符或乱码，看起来像数据库被写坏；
  实际是命令行/控制台编码转换。检查：在**一个** Python 进程里
  `subprocess.run(['shpm','issue','show',ID,'--json'], capture_output=True).stdout.decode('utf-8')`，
  统计 CJK 字符数与 `0xd800..0xdfff` 代理数，只打印 ASCII 结论（如 `equal True`、`cjk 865`、`surrogates 0`），
  不要把中文经控制台回显后再比对。最小修复：正文先落 UTF-8 文件，用 `--body-file <路径>` 写入
  （`issue add` 与 `issue edit` 都支持）；需要非阻断时先 `add` 再 `issue edit --no-blocking`
  （`issue add --blocking` 只能置真）。
- 同一轮里并发发起多个文件写入调用时，工具可能只落盘截断占位文本：首行形如
  `[Code content truncated (N chars); file written to <路径>]`，字节数远小于预期，后续编译/执行会报语法错误。
  检查：写完立刻核对首行、字节数和 `sha256`（或 `python -c` 断言内容里不含该占位串）。最小修复：一次只写
  一个文件，命中占位符就整文件重写，不要在其上打补丁；长中文改动优先"UTF-8 片段文件 + Python 拼接，
  拼接后逐字核对"，不要整段放进 `file_patch`。

# 主控 Agent 规则

## 授权与客户演示

- 2026-09-27 起，人工已对本项目后续开发、回归与客户演示所需的真实模型调用给出持续授权，不限制调用次数或预算，主控无需再逐次申请；该授权不等于任何演示阶段已执行或通过。主控仍须限定调用在当前任务范围内，使用已托管凭据引用，记录实际模型、Provider、时间、Usage、失败与重试，保留 W24 原始证据，并遵守密钥脱敏、客户数据隔离、无隐藏重试和阶段门禁规则。
- 客户演示按轮次推进，每轮的派工索引与阶段退出标准放在 `private-docs/prompts/39-deerflow-parity-delivery/show-roadmap-N/README.md`；各轮各阶段的当前状态和决策以 SHPM 为准，本文件不记录。实现任务一律落回 W00–W24，不创建平行工作包。
  - 客户演示 v0（用户亦称 V1，路线图 `private-docs/roadmap/36-super-harness-customer-demo-v0.md`，索引 `show-roadmap-1`）的 `GO` 只证明“真实模型 + 生产 `write_file` + Approval + Artifact + Reload/Reconnect + 同 Run Steer”在冻结 DeerFlow UI 上的本地纵向链，不等于 PRD D01–D05 的完整 Customer Demo Gate 或 Full Parity，也不关闭任何 W00–W24 工作包。
  - 客户演示 V2 以 `show-roadmap-2` 为派工总索引。
  - 任何一轮的 `GO` 作出后，若同一整链回归失败，须如实记录并重新评估该决策，不得保留过期 `GO`。
- 各轮通用门禁：主控把每个新任务映射到当前轮次的阶段、真实缺口和退出门槛，并在 SHPM 中登记（`shpm sprint link <SPRINT>/<阶段> --task <ID> --relation delivers --required`）。L1/L2 受控 Provider、测试 Tool、静态合同、组件测试、旧轮次证据或分开的单项 PASS，都不能替代同一主线 SHA 的连续两轮 L3 整链证据；任一产品修复合入后，后续差分和 L3 须基于新的主线 SHA 重新开始连续轮次。两轮不得隐匿失败或重试；真实 Artifact 必须有可信 ID/provenance 与可核验下载字节。阶段 `workStatus` 只表示执行状态，`result` 独立使用 PASS/FAIL/NOT_RUN 等 case 口径；未执行的阶段为 `NOT_RUN`、总体 `NO_GO`，已有分项证据不得提升阶段门禁。只有该轮全部阶段 PASS、同一主线 SHA、人工验收记录齐全，才可把演示决策改为 `GO`。W04 等已知范围外风险须披露，演示脚本实际触及其故障路径则如实 FAIL。
- 执行采用“双轨交付”：SHPM sprint 阶段（`shpm sprint show/gate <SPRINT> --json`）是客户演示阶段门禁，W00–W24 是不变的长期能力工作包台账。开发仍按工作包 task 提示词、独立 worktree、定向测试和主控验收交付；演示任务卡另外标注 `demoStages` 和该阶段原子断言。工作包切片/整包完成不自动使演示阶段 PASS，演示 `GO` 也不自动关闭工作包或 PRD D01–D05。W00 等长期横切工作只消费演示实际触及的来源，不因整包未完成而阻断正向演示。

## 派单与子智能体调度

- 主控每次开工、派单或向人工汇报前运行 `pwsh -NoProfile -File scripts/governance/audit-work-package-delivery.ps1`，并同时运行 `shpm reconcile --json`（只报告不修改），检查演示阶段、工作包、worktree、checkpoint、端口和实际子智能体列表；二者都无法读取智能体状态，须另行人工核对。主控在每次派工、复审、合入、失败或门禁变更时同次核对 SHPM 记录与实际 worktree；失败史保留。
- 端口表（每次使用前按「端口」做绑定预检）：
  - 主目录：Gateway `18018`、Frontend `33018`，供人工验收，不因子槽冲突迁移，不参与子槽公式。
  - 子智能体槽位共六个，槽内 Frontend=Gateway+`5000`：槽位 1 Gateway `20618`、Frontend `25618`；槽位 2 Gateway `30618`、Frontend `35618`；槽位 3 Gateway `40618`、Frontend `45618`；槽位 4 Gateway `11618`、Frontend `16618`；槽位 5 Gateway `21618`、Frontend `26618`；槽位 6 Gateway `22618`、Frontend `27618`。槽位 1–3 逐组 `+10000`；槽位 4–6 不沿用该公式，以避开 `49152` 起的动态端口范围。
  - W18 差分 runner 的受控测试 Provider 独占 `46618`，不属于槽位。
- 派单前须确认目标 worktree 的启动器与端口表一致，否则从当前 main 新建独立 worktree，不得用旧默认端口运行。派单必须声明槽位、完整端口组和独立数据目录；同一时刻每槽仅一任务，完成后释放。每个需要启动服务的子智能体（含 OpenCode 进程）独占一个槽位，因此同时运行的此类子智能体最多 6 个。
- 主线合入后主控用 `pwsh -NoProfile -File scripts/refresh-main-service.ps1 -Status` 检查、用 `-Jar <exec JAR> -ExpectSha <提交> [-LiveSmoke]` 刷新固定端口服务并做 smoke。
- 选择执行者：范围清楚、无需频繁与主控交互、可凭最终提交和独立门禁验收的任务优先交给 OpenCode；需要持续协商失败语义、架构或跨仓边界的任务优先交给可交互子智能体。不设 OpenCode 派单比例。模型按「OpenCode CLI」「agy CLI」固定，checkpoint 与交付证据须记录实际模型。主控在 `shpm assign create` 中记录实际执行者（`--executor`/`--ref`）与模型（`--model`），分配理由写入首个 `shpm assign checkpoint`；工具选择不放宽设计提示词、代码评审、自动门禁、真实证据、密钥和外部目录边界，隔离、checkpoint 和失联恢复规则对所有执行者相同。执行者无法安全取得上下文或执行门禁时由主控收回或改派，不算已完成派单。
- 多智能体执行采用“15 分钟无声上限”：每个运行中的子智能体至少每 15 分钟向主控回报一次 checkpoint，至少包含当前工作包、已完成证据、正在执行的命令或步骤、首个失败或阻塞、worktree 变更及下一交付点；长时间命令须在启动前说明目的，并在结束后立即回报。若距上次有效回报或可观察进展达到 15 分钟，主控必须发送一次非中断式点名消息，不得因智能体状态仍显示 `running` 而无限等待。
- 点名后 5 分钟仍无应答，或主控通过 worktree、测试进程、端口与日志审计确认任务停滞、跑偏、越界或反复等待无结果时，按“失联/失控 lane”恢复：先保留未提交变更、日志、数据目录和进程证据，只中止受影响智能体的当前回合，再用原 worktree 恢复同一任务或明确交接给替代智能体，并收紧范围、端口与下一 checkpoint；禁止打断正常推进的其他智能体。主控须用 `shpm assign lost` / `shpm assign recover --preserve-worktree` 和 `shpm assign checkpoint` 及时登记该 lane 的实际工作包映射、恢复动作、可验证进展和仍为 `NOT_RUN` 的项目，避免用旧智能体名称误报任务进度。

## 提示词与过程文档

- 派工前在 `private-docs/prompts/39-deerflow-parity-delivery/` 对应工作包目录编写任务提示词，写明冻结来源、范围与依赖、实现边界、接口及失败语义、退出条件、测试命令和证据要求；每个可独立交付的任务使用单独文件。
- 新任务文件用 `工作包-任务名.md` 命名，如 `W05-Interaction.md`；任务名简短、无空格，不在新增目录 / 文件名中加入状态后缀。既有路径和 `show-roadmap-N` 轮次索引沿用；登记为 SHPM `prompt_path` 后不改名、不移动，任务状态在 SHPM 中维护。
- 提示词随任务登记（见「SHPM 登记封装」）；派单消息注明任务 / 派工 ID、提示词路径、branch/worktree、端口、数据目录和 checkpoint 要求。
- 方案、评审和复盘放在对应私有工作包目录，交付报告放在 `private-docs/ai-coding-report/` 指定位置，原始日志、trace 和截图放在外置证据目录；SHPM 只登记路径。`docs/` 保存稳定的开发与使用手册，不新增过程文档。

## 项目管理（SHPM）

- 工作包、任务、派工、checkpoint、证据和演示门禁统一在 SHPM 管理；只有主控通过 `shpm` 写入，子智能体报告进展与证据，人工在 http://127.0.0.1:8787/ 只读查看。正文和原始证据保留在各自目录，SHPM 登记路径。
- 操作前明确设置 `SHPM_DB`，运行 `shpm doctor --json` 和 `shpm status --json`；环境异常时停止写入。状态与写入命令使用 `--json`，写入带 `--idempotency-key`，同一次操作重试复用原 key。
- 按「登记提示词与任务 → start / assign → checkpoint → deliver → verify → 主控独立验收 → accept / release」推进；`deliver` 只进入 `ready_for_acceptance`。接手先用 `show`、`assign list`、`verify list` 查看状态和证据。
- 每次验收登记结果与实际 SHA、命令、原始证据；任务、工作包和阶段完成须满足各自门禁及人工验收要求，`GO` 通过 `sprint decide` 设置。状态变化后运行 `tests/checks/evidence/verify-evidence-report.ps1` 检查证据聚合。

未覆盖的操作、参数、状态恢复和门禁说明，请看 [SHPM 使用手册](docs/shpm-manual.md)。

# 子智能体规则

- 接单后先完整阅读本文件、工作包对应 roadmap/产品/PRD、冻结 DeerFlow 源码与已有证据；首个 checkpoint 必须复述实际工作包、branch/worktree、槽位端口、数据目录、范围边界和下一交付点。子智能体不得执行任何 `shpm` 写命令，不得重建已删除的进度文件，也不得改动主目录服务。
- 只能使用主控分配的 worktree、数据目录和端口组。启动服务前须检查已分配端口；若被占用，先查明 PID 并核对命令行：确认是本 worktree 自己遗留的进程才可以直接终止；占用者属于其他 worktree、其他槽位、主服务或归属无法确认时，保留证据并报告主控，不得终止。不得仅按进程名批量结束进程。任务结束时须清理自己启动的服务并验证端口释放。
- 按主控规则的“15 分钟无声上限”回报 checkpoint；收到主控非中断式点名后，应在当前安全命令边界立即应答，不得为回报而破坏正在运行的测试或写入。
- 发现软件安装、依赖、配置、凭据或权限新坑位时，先保存可复现证据，再按“环境与排障记录”更新本文件；涉及密钥只能记录凭据引用、存在性和脱敏结果，禁止输出或提交密钥值。若问题需要全局安装、联网取依赖、提升权限或改变用户/系统配置，先停止扩张并报告主控，不得自行绕过。
- 编码前按公共规则对照冻结实现评估领域复杂度，需要更高复杂度时停在编码前提交设计说明。commit/push 前运行公共规则「检查时点」列出的检查；正式门禁须经主控放行后才启动，未运行的记 `NOT_RUN`。
- 交付 checkpoint 必须包含分支、最终 commit SHA、变更文件、测试/Playwright 次数与耗时、真实 Run/Tool/Artifact 或 HTTP/SSE 证据、已释放端口、worktree clean 状态、明确 `NOT_RUN` 清单以及建议的下一切片；提交后不得 amend/rebase 已交给主控评审的 SHA，除非主控明确要求。

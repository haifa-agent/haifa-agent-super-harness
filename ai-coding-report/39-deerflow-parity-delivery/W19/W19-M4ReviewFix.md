# W19-M4ReviewFix — 修复 #90 审查意见开发报告

> 状态：本报告记录 PR #90 的 3 条 Codex 意见（全部采纳，均为 UI 验收有效性）的处置、断言与证据。任务状态、门禁结果与阶段决策以 SHPM 为准，本报告不预填阶段 `PASS`。
> 里程碑：M4 返工（review-fix）。分支 / worktree：`feat-w19-frozen-ui-acceptance`（本目录）。基线 HEAD：`841ec89`。冻结 DF：`e545c28ac301e6f9feae247c513d8ea8f83df294`。
> 范围：只改两份 W19 UI 用例与文档，不扩大范围；未改冻结前端源码、未改产品 Java、未改 W00 契约。

## 1. 逐条处置

| # | 审查意见 | 处置 | 断言/证据 |
| --- | --- | --- | --- |
| 1 | 重启恢复需真实老发生（Codex P1，`w19-scheduled-tasks-restart.spec.cjs:93`）：重启轮重开“已干净完成”的暖轮数据，只统计残留活跃行；所有 Run 在关停前已终态，即使恢复被完全禁用计数也为 0，收敛结论为空断言。 | 在暖轮新增用例 `W19-M4-07a`：停进程前**显式落一个 `launching`（无已提交 Run）发生**到 `scheduler/state.json`（模拟 claim→关联写之间的崩溃窗口），并写 seed 文件。为保证注入不被在途持久化覆盖，先暂停唯一到期任务并等待所有活跃发生收敛，使 live scheduler 不再有任何 store 变更。重启轮读 seed，断言该具体发生**必须离开 `launching` 并最终由真实重新派发的 Run 收敛到 `success`**；若恢复被禁用则停留在 `launching`，该断言与 `danglingLaunches==0` 同时失败。 | 暖轮：`w19-m4-07a-seed.json`（`status=launching`，`occ-stale-*`）；重启轮：同一 `occurrenceId` 收敛为 `success` 且带真实 `run_id`。`danglingLaunches=0`、`activeWithoutRun=0`。比较两轮证据：seed `seededAt=2026-10-18T02:56:52.545Z` → 重启后 `startedAt=2026-10-18T02:57:14.856Z`（新进程、新 Run）。 |
| 2 | 活跃态重叠手动触发（Codex P1，`w19-scheduled-tasks.spec.cjs:269`）：断言在首个手动发生已终态后才跑，从未在活跃窗口内制造竞争触发；并发触发若产生两个活跃发生也能通过。 | `W19-M4-03` 改为：用长输出 prompt 触发首个手动发生，**在其仍活跃时并发发出 5 个 `POST /trigger`**，断言全部被单一活跃约束拒绝（409），并在整个重叠窗口轮询观测活跃计数峰值。 | `w19-m4-03.json`：`burstStatuses=[409,409,409,409,409]`、`peakActive=1`、`manualCount=2`、`statusAfterPausedTrigger=paused`。 |
| 3 | `-CaseTitle` 单 case 自包含（Codex P2，`w19-scheduled-tasks.spec.cjs:177`）：M4-02 依赖 M4-01 的模块内状态、M4-08 依赖 M4-02，单独选择会因缺前置立即失败。 | 新增 `ensureEnabledIntervalTask(...)`（发现已有 interval 任务；暂停态则恢复；否则新建）与 `ensureSuccessfulOccurrence(...)`（发现已成功发生；否则新建任务并真实触发）。M4-02/M4-08 改为“有模块态用模块态，否则自行创建/发现”；M4-06 的历史检查也改为可发现回退。 | 逐 case 运行（均在**全新 data-root** 上以 `-CaseTitle` 单独执行）：M4-01、M4-02、M4-03、M4-08 各自 `1 passed / 7 NOT_RUN / exitCode 0`。 |

领域复杂度：只改 UI 用例与可发现前置，未新增领域对象、状态机、持久化概念或产品接口；未放宽或删除任何 Oracle，三条修复都**加强**断言。

## 2. 命令与证据

- 变更文件：`tests/ui/smoke/w19-scheduled-tasks.spec.cjs`、`tests/ui/smoke/w19-scheduled-tasks-restart.spec.cjs`（代码候选 `befd4064fb4b71ee054d7555c15c968953ff6f04` 只含这两份 spec；本报告为紧随其后的独立文档提交）。`node --check` 两份 spec 通过；`git diff --check` 干净。
- 真实模型轮次（冻结前端 + 托管 `https://api.deepseek.com`，profile `-W19ScheduledTasks` / `-W19ScheduledTasksRestart`，槽位 5 `21618/26618`）：
  - 暖轮 A（8/8 PASS，exitCode 0）：`local-tmp/slot-5-w19-scheduled-tasks/evidence/run-20261018-105411-770-778d0d54`。
  - 暖轮 A3（x2，8/8 PASS，exitCode 0）：`local-tmp/slot-5-w19-scheduled-tasks/evidence/run-20261018-111630-731-9911165d`。
  - 重启轮 A（1/1 PASS）：`local-tmp/slot-5-w19-scheduled-tasks-restart/evidence/run-20261018-105707-721-19fa3a10`。
  - 重启轮 A3（1/1 PASS）：`local-tmp/slot-5-w19-scheduled-tasks-restart/evidence/run-20261018-111834-075-25ab75f5`。
  - 逐 case 自包含：M4-01 `run-20261018-105754-599-40161306`、M4-02 `run-20261018-105825-201-0d6522b6`、M4-03 `run-20261018-110651-087-bfbcffd8`、M4-08 `run-20261018-110738-154-1fa9c09d`。
  - 原件另存 `local-tmp/slot-5-w19-m4fix/evidence/`（含首个 FAIL 分段）。
- 全仓门禁（复用绑定代码候选 `befd406` 的同指纹通过记录）：
  - 候选 `befd406` 的 `javaInputFingerprint=747b30ee5938dbe87efd9f39d2305bee3beb692c527f248901604fe10bb98f67`、`sdkIdentity=8f169eb254c216b71ddc4991eed46907cff03914f30635c3cedb068a81191556`、`sdkSelection=ABSENT`、`haifaVersion=0.1.3-SNAPSHOT`。两份 spec 均落在 `tests/`（指纹排除），故候选指纹与只跑过 `clean verify` 的 `841ec89` 同值，按「同指纹复用」绑定既有通过记录。
  - `HAIFA_SUPER_HARNESS_SKILL_PYTHON=D:/agents/deer-flow/backend/.venv/Scripts/python.exe pwsh -NoProfile -File scripts/verify-full.ps1` → `status=PASS`，957 通过 / 0 失败 / 0 错误 / 25 跳过，`elapsedMs≈1143758`；摘要 `local-tmp/verify-full/verify-full-20261018-112737-417.json`（`sourceSha=841ec89…`、`javaInputFingerprint=747b30ee…`），日志同名 `.log`。另有一次覆盖“含本报告树”的 `clean verify` 全绿（同测试计数），确证候选树本身可跑。
- 脚本回归：`pwsh -NoProfile -File scripts/verify-scripts.ps1` → `status=PASS`，`local-tmp/verify-scripts/verify-scripts-20261018-105045-774009.json`。
- 门禁范围：`python scripts/governance/change_scope.py`（本改动全落 `tests/ui` + `docs`）→ `gates` 含 `docs-check`、`verify-full`、`ui-*-round-x2`；UI 模型取本 worktree 的 W19 profile 既有口径（`live`，真实模型），严格强于 `controlled`。逐 case 与全套 profile 均以真实模型执行。

## 3. 首个 FAIL（保留）

- `verify-full` 首轮（`verify-full-20261018-112021-137.json`）3 条失败，全部为环境前置：静态 review 子进程 `python -I -B` 缺 PyYAML（`ModuleNotFoundError: No module named 'yaml'`），不是产品/断言缺陷。按 AGENTS「真实模型诊断」设置 `HAIFA_SUPER_HARNESS_SKILL_PYTHON` 指向已核验含 PyYAML 6.0.3 的解释器后重跑全绿。
- UI：`local-tmp/slot-5-w19-scheduled-tasks/evidence/run-20261018-110008-069-8ed5a832`（M4-03 首个单 case）与 `run-20261018-110813-907-8b9c2dbf`（暖轮 A2 首 case M4-01）均为冻结前端首轮 hydration 卡点（`createIntervalTask` 等待 `Every` 输入/`schedule-interval-unit` 超时），与本次改动无关；按「同一脚本重跑一次」重跑后 M4-03 与暖轮 A3 全绿。原 FAIL 分段已存 `local-tmp/slot-5-w19-m4fix/evidence/fail-m4-03-hydration`、`fail-round-a2-m4-01-hydration`。
- 无 Oracle 放宽、无断言删减、无重试掩盖。

## 4. 未运行项（NOT_RUN）

- `docs-check`：仓库内没有该门禁的独立可执行 runner，按其含义补齐文档卫生（本报告），独立 runner 记 `NOT_RUN`。
- L2 冻结前端双后端差分：工作包出口由主控攒批，记 `NOT_RUN`。
- L3 客户演示整链：不在本任务范围，记 `NOT_RUN`。
- 慢测试（`@Tag("slow")`）：未新增、未运行。

## 5. 交接

- 变更文件：`tests/ui/smoke/w19-scheduled-tasks.spec.cjs`、`tests/ui/smoke/w19-scheduled-tasks-restart.spec.cjs`（UI，代码候选 `befd406`）；本报告（文档，独立尾随提交）。未改 runner、冻结前端或产品 Java。
- 代码候选 `befd406` 的 `javaInputFingerprint=747b30ee…` 与既有 `verify-full` 通过记录同指纹，可按「同指纹复用」绑定。注意：指纹排除表为 `docs/`、`tests/`、`scripts/`，`ai-coding-report/` **不在**其中，故本报告这个尾随文档提交会给源码树哈希带来新的取值；这是文档提交的既有约定（同 M3 报告），权威代码候选是 `befd406`。
- 原始证据：`local-tmp/slot-5-w19-m4fix/evidence/`（暖轮 A/A3、重启 A/A3、逐 case、首 FAIL）。
- 未写 SHPM；commit/push 后更新 PR #90。

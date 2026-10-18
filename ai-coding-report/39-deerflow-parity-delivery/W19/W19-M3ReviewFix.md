# W19-M3ReviewFix — 修复 #82 审查意见开发报告

> 状态：本报告记录 PR #82 的 4 条 Codex 意见（全部采纳）的处置、断言与证据。任务状态、门禁结果与阶段决策以 SHPM 为准，本报告不预填阶段 `PASS`。
> 里程碑：M3 返工（review-fix）。分支 / worktree：`feat-w19-schedule-rest-api`（本目录）。基线 HEAD：`359270d`。冻结 DF：`e545c28ac301e6f9feae247c513d8ea8f83df294`。
> 范围：只改 `api/DeerFlowScheduledTasksApi.java`、`schedule/ScheduleStore.java` 与其测试，不扩大范围；未改冻结前端、未改 W00 契约。

## 1. 逐条处置

| # | 审查意见 | 处置 | 断言/证据 |
| --- | --- | --- | --- |
| 1 | 拒绝终态一次性任务的 resume（Codex P1，`DeerFlowScheduledTasksApi.java:333`） | 新增 `ScheduleStore.resumeTask(taskId, ownerId, now)`：在同一锁内先查活跃 occurrence（有则 409），再判定状态——终态抛 `ScheduleValidationException`（HTTP 422），非 `PAUSED` 也抛 422。终态 `once` 保留已过去的 `next_run_at`，不再能被 resume 重新置 `enabled` 并被 `listDueTasks` 二次准入；只能先 PATCH schedule 推到未来来重新启用。 | `W19ScheduleRestApiHttpTest.terminalOnceResumeIsRejectedUntilReplanned`（完成态 resume→422 且仍 `completed`，未来 PATCH→`enabled`）；`ScheduleStoreTest.resumeRequiresPausedNonTerminalTask`（enabled 拒绝、paused→enabled、completed 拒绝、跨 owner 空） |
| 2 | 活跃 Run 校验与 PATCH 原子化（Codex P2，:251） | 新增 `ScheduleStore.updateTaskIfMutable(...)`：`RUNNING` 状态检查、单活跃 occurrence 检查与 `applyUpdates`+写盘同处一个 `synchronized(lock)`；`updateTask` 的 API 调用点改用该方法，去掉“先 `requireMutable` 再 `updateTask`”的两段式（原先两锁之间可插入一次 occurrence 准入）。 | `ScheduleStoreTest.guardedUpdateRejectsActiveOccurrenceWithoutWriting`（活跃时抛 `ActiveOccurrenceConflict` 且行未改；终态后写入生效）；`W19ScheduleRestApiHttpTest.updatesApplyAndActiveOccurrenceConflicts` 增加 409 后 `prompt` 不变的断言 |
| 3 | null assistant 视为真实 patch（Codex P2，:271） | `updateTask` 改为 `if (body.has("assistant_id"))`（字段存在，含显式 null）：`resolveAssistantId(null)` 返回默认 `lead_agent`，因此 `{"assistant_id": null}` 重置为默认；字段缺失才保留原值。 | `W19ScheduleRestApiHttpTest.explicitNullAssistantIdResetsToDefault`（自定义 agent→null patch→`lead_agent`；缺失该字段时保留 `custom-agent`） |
| 4 | update 载荷拒绝 thread_id（Codex P2，:73） | 从 `UPDATE_FIELDS` 移除 `thread_id`，并删除 update 路径对它的写入；“有效 Thread”改为始终取已存值，`context_mode` 切到 `fresh_thread_per_run` 时仍按冻结语义清空。原始 PATCH 携带 `thread_id` 触发 `rejectUnknownFields`→422，不再重绑 reuse-thread 任务。 | `W19ScheduleRestApiHttpTest.updatesApplyAndActiveOccurrenceConflicts` 增加 `thread_id` PATCH→422；冻结 `contracts/upstream/deerflow/schemas/scheduled-task-update-request.schema.json`（body `additionalProperties:false`，无 `thread_id`）与前端 `updateScheduledTask` 类型（`Omit<..., "thread_id">`）对照 |

领域复杂度：均为既有对象/接缝的行为修正（2 个新 store 方法），未新增领域对象、状态机、事件或持久化概念。

## 2. 命令与证据

- 定向测试（候选 `fda7369` 提交前的工作树；仅测试语义）：
  - `pwsh -NoProfile -File scripts/test-targeted.ps1 -Test 'W19ScheduleRestApiHttpTest,W19ScheduleTriggerHttpTest,ScheduleStoreTest' -LowMemory` → `status=PASS`，26 通过 / 0 失败 / 0 错误 / 0 跳过，`elapsedMs≈43596`，日志 `local-tmp/test-targeted/test-targeted-20261018-082340-699.log`。
  - `pwsh -NoProfile -File scripts/test-targeted.ps1 -Test 'W19BackgroundDispatchHttpTest,ScheduleDispatchServiceTest,ScheduleWireShapeTest' -LowMemory` → `status=PASS`，26 通过 / 0 失败 / 0 错误 / 0 跳过，`elapsedMs≈20278`，日志 `local-tmp/test-targeted/test-targeted-20261018-082431-276.log`（证明未回退派发层）。
- 全仓门禁（绑定代码候选 `fda7369`）：
  - `HAIFA_SUPER_HARNESS_SKILL_PYTHON=D:/agents/deer-flow/backend/.venv/Scripts/python.exe pwsh -NoProfile -File scripts/verify-full.ps1`
  - 结果：`status=PASS`，941 通过 / 0 失败 / 0 错误 / 21 跳过，`elapsedMs≈542294`。
  - 绑定：`sourceSha=fda7369200f8ede01cca9e6e3eb669ddf2d3c4ea`、`sourceTree=eb039ecd0f486bdedf7e6c8e20b1e4436f9a6c5f`、`javaInputFingerprint=162222275b6552d2e0f1dfb6cc05a59a986924cac4287f7c2e16f8a73586301a`、`sdkIdentity=c4752f840c3b03f903c269fbb9cd745e0c37b6337024f3ca3468efbb7786204b`、`sdkSelection=ABSENT`、`haifaVersion=0.1.3-SNAPSHOT`。
  - 摘要：`local-tmp/verify-full/verify-full-20261018-084640-165.json`；日志同名 `.log`。原件另存 `local-tmp/slot-5-w19-m3fix/evidence/`。
- 门禁范围：`python scripts/governance/change_scope.py` → `gates=["docs-check","verify-full","ui-live-round-x1"]`，`uiModel=live`（`schedule/ScheduleStore.java` 落入 `other module src/main/`），`acceptanceTier.dailyPr=L1`（原件 `local-tmp/slot-5-w19-m3fix/evidence/change-scope.json`）。

## 3. 首个 FAIL（保留）

- 无产品/断言 FAIL。首轮定向运行（`test-targeted-20261018-082208-282.log`）在 `ScheduleStoreTest` 尚未加入新增两条 store 用例时执行，全绿；补齐用例后重跑（`...082340...`）仍全绿。无需要保留的首个失败签名。
- 诊断轮次：提交前的 `verify-full`（`verify-full-20261018-082500-590.json`）运行于未提交工作树（`sourceSha=359270d`、`javaInputFingerprint=null`），仅作过渡诊断；权威证据为绑定 `fda7369` 的 `verify-full-20261018-084640-165.json`。

## 4. 未运行项（NOT_RUN）

- `ui-live-round-x1`：本改动只收敛 REST 校验/原子性与 store 守卫，未新增前端可观察行为或接线；冻结前端与 W00 契约未动。按 AGENTS「L1 正式 UI 由主控排队」，记 `NOT_RUN`。
- `docs-check`：仓库内没有该门禁的独立可执行 runner，按其含义补齐文档卫生（本报告），独立 runner 记 `NOT_RUN`。
- L2 冻结前端双后端差分：工作包出口由主控攒批，记 `NOT_RUN`。
- 慢测试（`@Tag("slow")`）：未新增、未运行。

## 5. 交接

- 代码候选 SHA：`fda7369200f8ede01cca9e6e3eb669ddf2d3c4ea`（`sourceTree=eb039ecd...`、`javaInputFingerprint=162222275b...`）；`verify-full` 在其上 PASS。其后的提交只改本报告文字，Java 输入指纹不变（可按 AGENTS「合入后复用」复用）。
- 变更文件：`api/DeerFlowScheduledTasksApi.java`、`schedule/ScheduleStore.java`（产品）；`api/W19ScheduleRestApiHttpTest.java`、`schedule/ScheduleStoreTest.java`（测试）；本报告（文档）。
- 接口变化：`ScheduleStore` 新增 `updateTaskIfMutable(...)` 与 `resumeTask(...)`；既有方法签名未变，`updateTask` 保留供既有调用方/测试使用。
- 原始证据：`local-tmp/slot-5-w19-m3fix/evidence/`。
- 未写 SHPM。

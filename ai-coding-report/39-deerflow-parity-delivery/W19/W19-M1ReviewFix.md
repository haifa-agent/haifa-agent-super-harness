# W19-M1ReviewFix — 修复 PR #69 审查意见 开发报告

> 状态：本报告记录 PR #69 审查意见（7 条全部采纳）的处置、断言与证据。任务状态、门禁结果与阶段决策以 SHPM 为准，本报告不预填阶段 `PASS`。
> 里程碑：M1 返工。分支 / worktree：`feat-w19-schedule-model-store`（本目录）。基线：`c5fa1bd`（`feat-w19-schedule-model-store` 承接 HEAD）。冻结 DF：`e545c28ac301e6f9feae247c513d8ea8f83df294`（`croniter` 6.2.2）。
> 只影响 `super-harness-server` 的调度包；本轮未启动服务，未占用端口。

## 1. 范围与归属

- 修复 #69 的 7 条审查意见（ScheduleStore 3 条、计算器/cron 4 条）。其中 ScheduleStore / ScheduleCalculator 缺陷同样影响 #70，在本分支修复，后续 #70 通过 rebase 继承。
- 未新增领域对象 / 状态机 / 持久化概念；cron 语义全部对齐冻结 `deerflow/scheduler/schedules.py` + `croniter`，无新增抽象层。
- 未使用任何 SDK 类型；未启动 Gateway / Frontend；`local-tmp/sdk-selection.json` ABSENT → 基线 `io.haifa:haifa-agent-bom:0.1.3-SNAPSHOT`。

## 2. 逐条处置

### 2.1 ScheduleStore

| # | 审查意见 | 处置 | 证据 |
| --- | --- | --- | --- |
| 1 | 记账变更纳入回滚边界（P1，`ScheduleStore.java:322`） | `updateTaskAfterLaunch` 现在先在内存里算出 `accounted` occurrence 与 `updated` task，再用**同一个** `mutate(() -> { runs.put(accounted); tasks.put(updated); })` 发布；失败时两者一起回滚。 | `ScheduleStore.java:304-357`；`ScheduleStoreTest.launchAccountingRollsBackWithFailedPersistence` |
| 2 | 在文件上限前裁剪发生历史（P1，`ScheduleStore.java:443`） | 新增 `pruneHistory(taskId)`：每任务只保留最近 `MAXIMUM_HISTORY_PER_TASK=200` 条**终态** occurrence（活跃 occurrence 永不裁剪），在 `createRun` 与 `updateRunStatus`（转终态）的 mutate 内执行；`deleteTask` 同事务删除该任务全部 occurrence。 | `ScheduleStore.java:36-38,132-144,183-187,294-300,383-403`；`ScheduleStoreTest.occurrenceHistoryIsBoundedPerTask`、`ScheduleStoreTest.deleteTaskRemovesItsOccurrences` |
| 3 | `run_count` 递增受 occurrence 记账标记约束（P2，`ScheduleStore.java:316`） | `shouldIncrement` 改为：当 occurrence 存在时以 `launchAccounted` 为准（已记账不再递增），只有 occurrence 缺失时才回退比较稳定非空 `lastRunId`。 | `ScheduleStore.java:326-341`；`ScheduleStoreTest.occurrenceAccountingDoesNotDoubleIncrementWithoutStableRunId`、`ScheduleStoreTest.associationWriteFailureKeepsActiveSlotAndRunCountOnce` |

### 2.2 计算器 / cron

| # | 审查意见 | 处置 | 证据 |
| --- | --- | --- | --- |
| 4 | 拒绝 Java 带前缀的固定偏移时区（P2，`ScheduleCalculator.java:38`） | `validateTimezone` 除 `instanceof ZoneOffset` 外，新增 `isFixedOffsetAlias`：拒绝 `UTC±…` / `GMT±…` / `UT±…` 形式（Java `ZoneId.of` 接受但 `zoneinfo` 拒绝）与裸 `UT`；保留 `UTC`、`GMT`、`Etc/*` 等合法标识。 | `ScheduleCalculator.java:28-64`；`ScheduleCalculatorTest.timezoneRejectsFixedOffsetAliasesButKeepsNamedRegions` |
| 5 | DST gap/重叠与 croniter 对齐（P2，`CronExpression.java:92`） | 用冻结 `croniter` 的算法重写 DST 解析：不存在墙钟前进到首个存在分钟（gap）；重叠取 `now` 的后继（首个已过则取第二次）；偏移变化时比较另一偏移的更近后继。 | `CronExpression.java:108-175`；`CronDifferentialTest.dstOverlapBothSidesMatchFrozenCroniter`、`dstGapBothSidesMatchFrozenCroniter`、`ScheduleCalculatorTest.cronDstFoldSecondOccurrenceIsUsedOnceFirstPasses` |
| 6 | cron 步长展开防整数溢出（P2，`CronExpression.java:168`） | 步长展开循环改用 `long` 计数，`1/2147483647 * * * *` 不再 `value += step` 溢出。 | `CronExpression.java:566-571`；`CronDifferentialTest.oversizedStepDoesNotOverflowAndMatchesFrozenCroniter` |
| 7 | 支持合法的五字段 day 表达式（P2，`CronExpression.java:182`） | 新增 day 表达式解析：DOM `L`、`a-L`、`nW`/`Wn`、`?`；DOW `L<dow>`（当月最后一个）、`<dow>#n`（当月第 n 个）、`?`；并保持冻结的非法组合拒绝（W 与普通项混用、`#`/`L<dow>` 与普通项混用）。 | `CronExpression.java:238-420`；`CronDifferentialTest.dayOfMonthExpressionsMatchFrozenCroniter`、`referenceRejectedFormsRemainRejected` |

## 3. 与冻结 croniter 的差分

冻结参考就地落在 `local-tmp/slot-5-w19-m1fix/frozen/`（`croniter` 包 + `schedules.py`）。用本地冻结副本对同一输入求 `next_run_at`，与 Java 结果逐条比较：

- 语料：`local-tmp/slot-5-w19-m1fix/cases.txt`（75 条，覆盖 DST gap/overlap 两侧、`L`/`a-L`/`nW`/`#`/`L<dow>`/`?`、超大步长、普通/跨日/跨年、多时区，以及冻结的非法形式）。
- 比较脚本：`local-tmp/slot-5-w19-m1fix/compare.py`（读语料 → 跑冻结 Python 与 `CronDiff` 驱动 → 归一化比较）。
- 结果：`mismatches=0/75`（合法用例瞬时逐条一致，非法用例两侧同报错）。
- 入库回归：`super-harness-server/src/test/java/io/haifa/agent/superharness/server/schedule/CronDifferentialTest.java`（5 个用例，期望值取自上述冻结运行）。

## 4. 命令与证据

- 定向测试（返工后）：
  `pwsh -NoProfile -File scripts/test-targeted.ps1 -Test 'CronDifferentialTest,ScheduleCalculatorTest,ScheduleStoreTest,ScheduleWireShapeTest,SchedulerRunConfigurationTest' -LowMemory`
  - 结果：`status=PASS`，37 通过 / 0 失败 / 0 错误 / 0 跳过，`elapsedMs≈41137`。
  - 原始日志：`local-tmp/test-targeted/test-targeted-20261017-152218-797.log`。
- 架构边界：`... -Test 'ServerSdkBoundaryArchitectureTest' -LowMemory` → PASS（1/0/0），日志 `local-tmp/test-targeted/test-targeted-20261017-152341-812.log`。
- 预检：`python scripts/lib/quick_checks.py --repo . --base main` → `{"failures": []}`（SDK 基线、`git diff --check`、变更 secret scan）。
- 文档/管道回归：`python -m unittest discover -s tests/checks/build -p "test_pipeline_tools.py"` → `OK`（101 tests）。
- 全仓门禁（`docs-check` 归类见 §6）：
  `$env:HAIFA_SUPER_HARNESS_SKILL_PYTHON='D:/agents/deer-flow/backend/.venv/Scripts/python.exe'; pwsh -NoProfile -File scripts/verify-full.ps1`
  - **PASS**：814 通过 / 0 失败 / 0 错误 / 21 跳过（`elapsedMs≈300480`）。
  - 绑定候选的 `sourceSha` / `sourceTree` / `javaInputFingerprint`、日志与摘要路径见 `local-tmp/acceptance-handoff.md`。因 `ai-coding-report/**` 不在 `source_fingerprint` 排除列表，本报告的文字提交会改变 Java 输入指纹，故最终指纹以 handoff 登记的**最后一次**绑定运行（提交后干净树）为准。

## 5. 首个 FAIL（保留）

1. **返工首个 FAIL**：`ScheduleStoreTest.occurrenceHistoryIsBoundedPerTask`（`Expecting empty but was: [occ-…, run-59, occurrenceSeq=60]`）。
   - 根因：裁剪只在 `createRun` 内执行；本轮最后一个 occurrence 在最后一次裁剪之后才转为终态，导致终态历史为 201 条，比上限多 1。
   - 修复：把裁剪同时挂到 `updateRunStatus` 转终态的 mutate 上（活跃 occurrence 仍不裁剪），计数回到上界 200。
   - 原始日志：`local-tmp/test-targeted/test-targeted-20261017-152100-134.log`。
2. **`verify-full` 前三次 FAIL（环境，非本改动）**：`DeerFlowTaskDelegationApiTest` / `DeerFlowDelegationAdmissionApiTest` 连续报 `Port already in use … 14618`，另有一次 `HostShellToolApprovalTest … SQLite unit of work failed`。这些类不属于本任务范围，且 14618 在三次失败之间处于空闲，属并发车道占用固定测试端口叠加 Windows `LISTEN` 残留；期间本任务 schedule 用例均通过。原始日志：`verify-full-20261017-152409-470.*`、`verify-full-20261017-154051-981.*`、`verify-full-20261017-155713-427.*`。第四次重跑（无并发占用）PASS。

## 6. `docs-check`

`change_scope.py` 因本报告（`ai-coding-report/**/*.md` 归类为 `docs`）输出 `docs-check`。本任务按其含义补齐文档：新增本报告，并在 `W19-ScheduleModelAndStore.md` 更正此前「未逐值对照 croniter」与 DST gap 期望的描述。文档/管道回归见 §4。

## 7. 未运行项（NOT_RUN）

- `ui-live-round-x1`：本改动为纯 Java 调度语义 / 文件 store 修复，**未接线任何 REST/UI/Run 路径**，无 UI 表面可跑（与 M1 原任务一致）。记 `NOT_RUN`，需主控裁决。
- L2 冻结前端双后端差分：工作包出口由主控攒批，记 `NOT_RUN`。
- 慢测试（`@Tag("slow")`）：未新增、未运行。

## 8. 交接

- 最终候选 SHA、`sourceTree`、`javaInputFingerprint` 与绑定本候选的 `verify-full` PASS 摘要路径登记在 `local-tmp/acceptance-handoff.md`；工作树 clean。
- 变更文件（相对基线上游）：`CronExpression.java`、`ScheduleCalculator.java`、`ScheduleStore.java`（产品）；`CronDifferentialTest.java`、`ScheduleCalculatorTest.java`、`ScheduleStoreTest.java`（测试）；本报告 + `W19-ScheduleModelAndStore.md`（文档）。
- 计算器签名与 store 接口未变，M2 消费面不变；`ScheduleStore` 新增有界保留（每任务最近 200 条终态）与删除清理语义。
- 未推送、未开 PR、未写 SHPM；待主控裁决 `ui-live-round-x1` 并推送更新 PR #69。

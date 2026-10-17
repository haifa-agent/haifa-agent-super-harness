# W19-M2ReviewFix — 修复 PR #70 审查意见 开发报告

> 状态：本报告记录 PR #70 的 9 条审查意见（全部采纳）的处置、断言与证据。任务状态、门禁结果与阶段决策以 SHPM 为准，本报告不预填阶段 `PASS`。
> 里程碑：M2 返工。分支 / worktree：`feat-w19-background-dispatch`（本目录）。前置：先 rebase 到修复后的 M1 `c557b51`（`feat-w19-schedule-model-store`），rebase 后 M2 功能提交为 `b1c76ea`。冻结 DF：`e545c28ac301e6f9feae247c513d8ea8f83df294`（`croniter` 6.2.2）。
> 只影响 `super-harness-server` 的调度包与后台 Run 接缝；本轮未启动常驻服务，未占用槽位端口。
> 位置注记（2026-10-17）：本报告表格中的 `DeerFlowThreadRunApi.java:*` 行号对应 W20 抽取前的旧架构；rebase 到 post-W20 后，这些接缝整体迁至 `api/DeerFlowConversationService.java`，迁移对照见 `W19-M2RebaseReconcile.md`。语义与断言不变。

## 1. 范围与归属

- 先在 `c557b51` 上 rebase，解决 `ScheduleStore.deleteTask` 冲突（保留 M2 的 `launching/running` 活跃冲突守卫，同时继承 M1 的 `deleteTask` 连带删除 occurrence 清理）。
- 修复 #70 的 9 条审查意见（派发/恢复 7 条、计算器/存储 2 条），全部在既有 SH 接缝内完成；未新增领域对象、Run 状态机、事件总线或持久化表。
- 未使用任何新 SDK 类型；`local-tmp/sdk-selection.json` ABSENT → 基线 `io.haifa:haifa-agent-bom:0.1.3-SNAPSHOT`。

## 2. 逐条处置

| # | 审查意见 | 处置 | 断言/证据 |
| --- | --- | --- | --- |
| 1 | 恢复后重挂 follower（Codex P1，`ScheduleDispatchService.java:190`） | `recoverIfNeeded` 每轮对“已关联 Run 且仍非终态”的 occurrence 幂等补挂 `launcher.watch`；`watch` 以 `followedRuns` 去重，避免重复 follower。 | `ScheduleDispatchServiceTest.recoveryReattachesFollowerAndConvergesActiveRun`；`W19BackgroundDispatchHttpTest` |
| 2 | 检查暂不可用时保持 fail-closed（Codex P1，`DeerFlowThreadRunApi.java:1981`） | `inspect` 仅在 thread 不存在/归属不符或 `findRun` 为空时返回 `empty`（确属消失）；bridge 重建或快照等瞬态异常改抛 `ScheduledRunLauncher.InspectionUnavailableException`，恢复保持活跃槽位并在后续轮次重试。 | `ScheduleDispatchServiceTest.unavailableInspectionKeepsActiveSlotAndRetries` |
| 3 | 关联落盘后仍启动终态观测（Codex P1，`ScheduleDispatchService.java:292`） | 关联写入（`updateRunStatus`）与父任务记账拆成两个 `try`；关联成功后父任务记账失败只记诊断，仍继续 `launcher.watch`。 | `ScheduleDispatchServiceTest.postAssociationBookkeepingFailureStillStartsFollower` |
| 4 | follower 重试瞬态快照失败（Codex P1，`DeerFlowThreadRunApi.java:1934`） | follower 循环内 `snapshot` 异常不再 `return`，记录异常类并保持休眠/退避，在既有有界 deadline 内继续轮询。 | 代码路径 + `W19BackgroundDispatchHttpTest`（正常终态收敛） |
| 5 | 轮询在单轮失败后继续（AarenWang P1，`ScheduleDispatchService.java:147`） | `runLoop` 改为每轮独立 `try/catch`：单轮 `RuntimeException` 记录后按配置间隔继续，`running`/关闭语义不变。 | `ScheduleDispatchServiceTest.transientPollRoundFailureKeepsWorkerPolling` |
| 6 | 准入时应用 assistant 身份（AarenWang P1，`DeerFlowThreadRunApi.java:1873`） | `launch` 用携带 `assistant_id` 的 resolver body 走既有 `runModelResolver`/`agentPromptResolver` 校验路径，所选 Agent 的 model/Soul/工具组合生效。 | `W19BackgroundDispatchHttpTest.scheduledRunAppliesNonDefaultAssistantSoul`（`review-agent` 的非默认 Soul 出现在真实 Provider 请求） |
| 7 | 定时启动复用 Thread 活跃 Run 守卫（AarenWang P1，`DeerFlowThreadRunApi.java:1883`） | `launch` 在 `synchronized(thread)` 内先 `restoreDurableAdmission` 再复用 `activeRunInFlight` 守卫（读取不确定时 fail-closed），冲突抛 `ActiveRunConflict`；派发侧收到后 `revertLaunchingToQueued`，重叠 occurrence 保持 `queued`。 | `ScheduleDispatchServiceTest.threadActiveRunConflictKeepsOccurrenceQueued`；`W19BackgroundDispatchHttpTest.overlappingReuseThreadRunStaysQueued` |
| 8 | interval 边界先校验再窄化 int（Codex P2，`ScheduleCalculator.java:60`） | `validateIntervalSeconds`/`parseIntervalSeconds` 改用 `long` 边界比较后再窄化；`2^32+60` 不再回绕成 60 秒。 | `ScheduleCalculatorTest.intervalRejectsIntOverflowWithoutWrapping` |
| 9 | 启动记账纳入原子变更（Codex P2，`ScheduleStore.java:342`） | **M1 已修复并随 rebase 继承**：`updateTaskAfterLaunch` 把 `accounted` occurrence 与 task 行放在同一次 `mutate` 回滚边界内发布（`ScheduleStore.java:382-387`）。 | `ScheduleStoreTest`（既有 `launchAccountingRollsBackWithFailedPersistence` 等） |

领域复杂度：以上均为既有对象/接缝的行为修正，未新增抽象层。

## 3. 断言与故障注入

- 瞬态 `listDueTasks` 异常后继续轮询：`transientPollRoundFailureKeepsWorkerPolling`。
- 关联后父任务记账失败仍有 follower：`postAssociationBookkeepingFailureStillStartsFollower`。
- 恢复后活跃 Run 完成收敛：`recoveryReattachesFollowerAndConvergesActiveRun`。
- 检查暂不可用保持活跃并重试：`unavailableInspectionKeepsActiveSlotAndRetries`。
- 同 Thread 第二个定时 Run 保持 `queued`：`threadActiveRunConflictKeepsOccurrenceQueued`（dispatch 级）+ `overlappingReuseThreadRunStaysQueued`（真实 HTTP/SDK 链）。
- 非默认 assistant 的 Soul 出现在 Provider 请求：`scheduledRunAppliesNonDefaultAssistantSoul`。
- interval 上限不被 int 回绕：`intervalRejectsIntOverflowWithoutWrapping`。

## 4. 命令与证据

- 定向测试（返工后）：
  - `pwsh -NoProfile -File scripts/test-targeted.ps1 -Test 'ScheduleDispatchServiceTest,ScheduleStoreTest,ScheduleCalculatorTest' -LowMemory` → `status=PASS`，42 通过 / 0 失败 / 0 错误 / 0 跳过，`elapsedMs≈24373`，日志 `local-tmp/test-targeted/test-targeted-20261017-170303-574.log`。
  - `pwsh -NoProfile -File scripts/test-targeted.ps1 -Test 'W19BackgroundDispatchHttpTest' -LowMemory` → `status=PASS`，4 通过 / 0 失败 / 0 错误 / 0 跳过，`elapsedMs≈23669`，日志 `local-tmp/test-targeted/test-targeted-20261017-170559-422.log`。
- 预检：`python scripts/lib/quick_checks.py --repo . --base main` → `{"failures": []}`（SDK 基线、`git diff --check`、变更 secret scan）。
- 文档/管道回归：`python -m unittest discover -s tests/checks/build -p "test_pipeline_tools.py"` → `OK`（101 tests）。
- 全仓门禁 `verify-full`：由主控要求执行，绑定 rebase 后的最终候选 HEAD（`sourceSha`/`sourceTree`/`javaInputFingerprint` 与摘要路径登记在 `local-tmp/acceptance-handoff.md`）；`HAIFA_SUPER_HARNESS_W05_TEST_GATEWAY_PORT=21618`（槽位 5）在启动门禁的同一会话设置。
- `docs-check`：`change_scope.py` 输出 `docs-check`（`ai-coding-report/**` 归类为 `docs`）。本轮新增本报告，并更正 `W19-BackgroundDispatch.md` 中「未消费 `assistant_id`」与「恢复只读收敛」两处过时描述。

## 5. 首个 FAIL（保留）

1. **返工首个 FAIL（测试期望，非产品）**：新增用例首次运行 2 条断言失败——`recoveryReattachesFollowerAndConvergesActiveRun` 误断言恢复收敛也递增 `run_count`（恢复收敛走 `finishTask`，不递增），`postAssociationBookkeepingFailureStillStartsFollower` 误把 interval 任务期望为 `completed`（应为 `enabled`）。按真实语义修正断言后通过。日志：`local-tmp/test-targeted/test-targeted-20261017-170144-101.log`。
2. **HTTP 用例首个 FAIL（测试顺序假设）**：`overlappingReuseThreadRunStaysQueued` 首版假定先创建的任务先派发；两 occurrence 的 `created_at` 相同，`listQueuedRuns` 以随机 `occ-<hex>` id 次序排序，实际是 `task-second` 先启动。改为“恰有一条 `running`、另一条保持 `queued`”的顺序无关断言后通过。日志：`local-tmp/test-targeted/test-targeted-20261017-170335-572.log`。

## 6. 未运行项（NOT_RUN）

- `ui-live-round-x1`：本改动未接线任何新 REST/UI 路由（REST 属 M3），无冻结 UI 表面可跑；记 `NOT_RUN`，需主控裁决。
- L2 冻结前端双后端差分：工作包出口由主控攒批，记 `NOT_RUN`。
- W19-M2-09 多实例/跨实例接管：本期不支持，`NOT_RUN`。
- 慢测试（`@Tag("slow")`）：未新增、未运行。

## 7. 交接

- rebase 后 M2 功能提交 `b1c76ea`；返工提交 SHA、`sourceTree`、`javaInputFingerprint` 与绑定 `verify-full` PASS 摘要路径登记在 `local-tmp/acceptance-handoff.md`，工作树以最终候选为准。
- 变更文件：`ScheduledRunLauncher.java`、`ScheduleDispatchService.java`、`ScheduleCalculator.java`、`api/DeerFlowThreadRunApi.java`（产品）；`ScheduleDispatchServiceTest.java`、`ScheduleCalculatorTest.java`、`W19BackgroundDispatchHttpTest.java`（测试）；本报告 + `W19-BackgroundDispatch.md`（文档）；另含 rebase 继承的 M1 修复（`ScheduleStore.java` 冲突解决等）。
- 计算器签名与 `ScheduleStore` 接口未变；`ScheduledRunLauncher` 增加 `watch(owner,thread,run,cb)` 重载语义（签名替换）与两个失败词汇（`InspectionUnavailableException`、`ActiveRunConflict`）。
- 未写 SHPM；待主控裁决 `ui-live-round-x1` 并审查更新后的 PR #70。

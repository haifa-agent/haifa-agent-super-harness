# W19-M2ReviewFix2 — 修复 #70 rebase 后新增 5 条 P1 开发报告

> 状态：本报告记录 PR #70 在 rebase 到 post-W20 main 后新增的 5 条 P1 审查意见（全部采纳）的处置、断言与证据。任务状态、门禁结果与阶段决策以 SHPM 为准，本报告不预填阶段 `PASS`。
> 里程碑：M2 返工（review-fix-2）。分支 / worktree：`feat-w19-background-dispatch`（本目录）。基线 HEAD：`4ae51c9`（已 rebase 到 post-W20 main）。冻结 DF：`e545c28ac301e6f9feae247c513d8ea8f83df294`。
> 保持 W20 抽取架构：`DeerFlowThreadRunApi` 仍为 458 行薄适配器，逻辑在 `DeerFlowConversationService` / `run/` 接缝。

## 1. 范围与归属

- 只修审查列出的 5 条 P1（3 条针对 `4ae51c9`、2 条针对 `35a3235` 仍未解决），未扩大范围到其它 review 项或新功能。
- 全部在既有 SH 接缝（`ScheduleDispatchService`、`ScheduleStore`、`ScheduledRunLauncher`、`DeerFlowConversationService`）内完成；未新增领域对象、Run 状态机、事件总线或持久化表，未把抽取逻辑搬回 `DeerFlowThreadRunApi`。
- 未使用任何新 SDK 类型；`local-tmp/sdk-selection.json` ABSENT → 基线 `io.haifa:haifa-agent-bom:0.1.3-SNAPSHOT`。

## 2. 逐条处置

| # | 审查意见 | 处置 | 断言/证据 |
| --- | --- | --- | --- |
| 1 | 定时启动复用 Thread active-Run 守卫（AarenWang P1，`DeerFlowThreadRunApi.java`，rebase 后为 `DeerFlowConversationService.launch`） | 抽出共享守卫 `activeRunRecord(thread)` + `runIsActive(run)`：交互式准入与定时 `launch` 在同一 Thread 锁内都走 `activeRunRecord`/`runIsActive`，只有 SDK 状态非终态才视为占用；定时路径冲突抛 `ActiveRunConflict`，派发侧 `revertLaunchingToQueued` 保持 `queued`，不替换 `thread.activeRunId`、不破坏首个 Run 的准入/交互路由。 | `ScheduleDispatchServiceTest.threadActiveRunConflictKeepsOccurrenceQueued`；`W19BackgroundDispatchHttpTest.overlappingReuseThreadRunStaysQueued`（真实 HTTP/SDK：第二个任务保持 `queued`，首个 `WAITING_APPROVAL`） |
| 2 | 审批前持久化定时 Run 的 prompt/agent 元数据（Codex P1，`DeerFlowThreadRunApi.java`） | `launch` 在 `persistThread` 前写入与交互式准入相同的 `setLeadPromptSelection(null, bridge.promptSignature(), agentPrompt)`（agent name、bootstrap、lead-prompt signature）并记录 framing。`pendingPromptAvailable` 因此可解析，审批不再 409。 | `W19BackgroundDispatchHttpTest.scheduledRunApprovalResponseIsAcceptedAndResumesRun`（审批 POST 返回 200，Run 恢复至 `SUCCESS`，Provider 调用 2 次） |
| 3 | 终态 occurrence 与父任务记账原子提交（Codex P1，`ScheduleDispatchService:378`） | 新增 `ScheduleStore.completeOccurrenceAndTask(...)`：occurrence 终态与父任务 `TaskFinish` 在同一次 `mutate` 回滚边界内发布；`recordTerminal`、`expireQueuedRuns`、`releaseFailedLaunch` 全部改用它。父任务写入失败时 occurrence 也不落终态，不再出现“终态 occurrence + `running` 父任务”。 | `ScheduleDispatchServiceTest.terminalOccurrenceAndParentCommitAtomically`（注入第 1 次 persist 失败：occurrence/task 双双保持 `RUNNING`；恢复后同轮收敛为 `SUCCESS`/`ENABLED`） |
| 4 | 启动恢复成功后才置完成标记（Codex P1，`ScheduleDispatchService:172`） | `recoverIfNeeded` 改为仅当整轮成功（无 store 异常且无“恢复读暂不可用”）才 `recovered.set(true)`；`recoverUnassociatedRuns` 在 `recoverLaunch` 抛 `InspectionUnavailableException` 时返回 `false`，阶段标记保持未完成，后续轮次重试。 | `ScheduleDispatchServiceTest.recoveryRetriesAfterTransientFailureAndStillRevertsLaunching`（首轮 `listExecutingRuns` 瞬态失败：行保持 `LAUNCHING`；后续轮次仍恢复为 `QUEUED`） |
| 5 | 关联失败后仍能恢复已启动的 Run（Codex P1，`ScheduleDispatchService:320`） | `ScheduledRunLauncher` 新增 `recoverLaunch(owner, threadId, launchedAfter)`：从 Thread 的 SDK 会话轮次恢复该 claim 提交的 Run 身份（仅取创建时间不早于 occurrence 的 Run，避免误认复用 Thread 上的旧终态 Run）。`launching`+无 `runId` 的 occurrence 先尝试采纳该 Run（置 `RUNNING` + `runId`）再观测收敛；仅当确无提交 Run 时才回到 `queued`。 | `ScheduleDispatchServiceTest.launchRecoveryAdoptsCommittedRunAfterAssociationFailure`（不重复启动、采纳 `run-committed`、可收敛）；`unavailableLaunchRecoveryKeepsClaimAndRetries`（恢复读不可用时保持 claim 并重试） |

领域复杂度：以上均为既有对象/接缝的行为修正（1 个新 store 方法 + 1 个 launcher 纯读接缝 + 1 个共享守卫抽取），未新增抽象层或持久化概念。

## 3. 断言与故障注入

- 同 Thread 第二个定时 Run 保持 `queued`：`threadActiveRunConflictKeepsOccurrenceQueued`（dispatch 级）、`overlappingReuseThreadRunStaysQueued`（真实 HTTP/SDK 链）。
- 审批答复不再 409：`scheduledRunApprovalResponseIsAcceptedAndResumesRun`。
- `finishTask` 失败后不发生永久卡住：`terminalOccurrenceAndParentCommitAtomically`。
- 首轮恢复瞬态失败后下一轮仍恢复：`recoveryRetriesAfterTransientFailureAndStillRevertsLaunching`。
- 关联失败 + 重启不重复启动：`launchRecoveryAdoptsCommittedRunAfterAssociationFailure`、`unavailableLaunchRecoveryKeepsClaimAndRetries`。
- 启动失败释放 claim 并恢复父任务计划：`failedLaunchReleasesOccurrenceAndReenablesTask`。

## 4. 命令与证据

- 定向测试：
  - `pwsh -NoProfile -File scripts/test-targeted.ps1 -Test 'ScheduleDispatchServiceTest' -LowMemory` → `status=PASS`，19 通过 / 0 失败 / 0 错误 / 0 跳过，`elapsedMs≈27725`，日志 `local-tmp/test-targeted/test-targeted-20261017-232725-646.log`。
  - `pwsh -NoProfile -File scripts/test-targeted.ps1 -Test 'W19BackgroundDispatchHttpTest' -LowMemory` → `status=PASS`，5 通过 / 0 失败 / 0 错误 / 0 跳过，`elapsedMs≈18726`，日志 `local-tmp/test-targeted/test-targeted-20261017-231454-291.log`。
  - `pwsh -NoProfile -File scripts/test-targeted.ps1 -Test 'DeerFlowSharedConversationServiceHttpTest,DeerFlowThreadRunApiTest,DeerFlowInteractionHttpApiTest' -LowMemory` → `status=PASS`，41 通过 / 0 失败 / 0 错误 / 0 跳过，`elapsedMs≈90347`，日志 `local-tmp/test-targeted/test-targeted-20261017-231521-513.log`（证明未回退 W20 抽取与共享用例）。
- 全仓门禁（绑定最终代码候选 `b8a8ab7`）：
  - `HAIFA_SUPER_HARNESS_SKILL_PYTHON=D:/agents/deer-flow/backend/.venv/Scripts/python.exe HAIFA_SUPER_HARNESS_W05_TEST_GATEWAY_PORT=22618 pwsh -NoProfile -File scripts/verify-full.ps1`
  - 结果：`status=PASS`，919 通过 / 0 失败 / 0 错误 / 21 跳过，`elapsedMs≈424345`。
  - 绑定：`sourceSha=b8a8ab706fdd9d66ed2791d761b249cb6011b071`、`sourceTree=4f872f43e29b454ea722983d3eb764e50b1ba9af`、`javaInputFingerprint=095fbe9871625fc93050d231943c6d8611d03b6bdbb25df35b50d86efce3fcaf`、`sdkIdentity=c1cc9b0c...`、`sdkSelection=ABSENT`、`haifaVersion=0.1.3-SNAPSHOT`。
  - 摘要：`local-tmp/verify-full/verify-full-20261017-232914-344.json`；日志同名 `.log`。原件另存 `local-tmp/slot-6-w19-m2fix2/evidence/`。
- 门禁范围：`python scripts/governance/change_scope.py` → `gates=["docs-check","verify-full","ui-live-round-x1"]`，`uiModel=live`，`acceptanceTier.dailyPr=L1`（原件 `local-tmp/slot-6-w19-m2fix2/evidence/change-scope.json`）。
- 未回退 W20：`DeerFlowThreadRunApi` 未改（保持 458 行）；`DeerFlowSharedConversationServiceHttpTest` 等共享用例在本改动后仍 PASS。

## 5. 首个 FAIL（保留）

1. **首个 FAIL（测试期望，非产品，保留原件）**：新增的 `launchRecoveryAdoptsCommittedRunAfterAssociationFailure` / `unavailableLaunchRecoveryKeepsClaimAndRetries` 首版失败——`recoverUnassociatedRuns` 仅做 `associateRun`（不置 `RUNNING`），occurrence 停留在 `LAUNCHING` 且 `runId=null`；同时首轮 `recoverLaunch` 不可用时仍置了 `recovered` 标记，导致第二轮不再重试。修正为采纳时 `updateRunStatus(RUNNING, runId, ...)`，并让 `recoverUnassociatedRuns` 在恢复读不可用时返回 `false`。原件 `local-tmp/slot-6-w19-m2fix2/evidence/first-fail-schedule-dispatch-service-test.log`。
2. **HTTP 首个 FAIL（测试断言，非产品，保留原件）**：`scheduledRunApprovalResponseIsAcceptedAndResumesRun` 首版从 `state.values.messages[].content.artifact.human_input.request_id` 取 request id，但产品投影的卡片 request id 位于 `message.artifact.human_input.request_id`（`content` 为字符串）。改为正确路径后通过。原件 `local-tmp/slot-6-w19-m2fix2/evidence/first-fail-w19-http-approval.log`。
3. **诊断轮次（非门禁，保留原件）**：提交前的 `verify-full` 运行于未提交工作树（`sourceSha=4ae51c9`、`javaInputFingerprint=null`），仅作过渡诊断；权威证据为绑定 `b8a8ab7` 的第二次运行。原件 `local-tmp/slot-6-w19-m2fix2/evidence/verify-full-uncommitted-diagnostic.json`。

## 6. 未运行项（NOT_RUN）

- `ui-live-round-x1`：本改动未接线任何新 REST/UI 路由（定时 REST 属 M3），无冻结 UI 表面可跑；记 `NOT_RUN`，需主控裁决。
- `docs-check`：仓库内没有该门禁的可执行 runner（仅在 `change_scope`/`test_pipeline_tools` 中出现名称），按其含义补齐文档卫生（本报告），独立 runner 记 `NOT_RUN`。
- L2 冻结前端双后端差分：工作包出口由主控攒批，记 `NOT_RUN`。
- W19-M2-09 多实例/跨实例接管：本期不支持，`NOT_RUN`。
- 慢测试（`@Tag("slow")`）：未新增、未运行。

## 7. 交接

- 最终代码候选 SHA：`b8a8ab706fdd9d66ed2791d761b249cb6011b071`（`sourceTree=4f872f43...`、`javaInputFingerprint=095fbe98...`）；`verify-full` 在其上 PASS。其后的提交只改本报告文字，Java 输入指纹不变（可按 AGENTS「合入后复用」复用）。
- 变更文件：`api/DeerFlowConversationService.java`、`schedule/ScheduleDispatchService.java`、`schedule/ScheduleStore.java`、`schedule/ScheduledRunLauncher.java`（产品）；`api/W19BackgroundDispatchHttpTest.java`、`schedule/ScheduleDispatchServiceTest.java`（测试）；本报告（文档）。`DeerFlowThreadRunApi.java` 未改。
- 接口变化：`ScheduledRunLauncher` 新增纯读 `recoverLaunch(...)`；`ScheduleStore` 新增 `completeOccurrenceAndTask(...)` 与 `TaskFinish` record；既有方法签名未变。
- 建议：`git push --force-with-lease origin feat-w19-background-dispatch` 更新 PR #70；若推送前 main 前进，按 AGENTS「冲突感知（并行合入）」复用 `javaInputFingerprint` 证据。
- 原始证据：`local-tmp/slot-6-w19-m2fix2/evidence/`。
- 未写 SHPM。

# W19-task-2（M2）— 单实例后台派发与启动恢复 开发报告

> 状态：本报告由 W19-task-2 执行者编写，记录实际交付、证据与偏差。任务状态、门禁结果与阶段决策以 SHPM 为准，本报告不预填 PASS。
> 里程碑：M2。冻结 DF：`e545c28ac301e6f9feae247c513d8ea8f83df294`。

## 1. 任务基本信息

- 任务：W19-task-2「单实例后台派发与启动恢复」（M2）。
- 分支 / worktree：`feat-w19-background-dispatch`，本目录（stacked on M1 `c5fa1bd`）。
- M1 基线：`c5fa1bd`（M1 交付提交 `f90fda1` 的文档收尾）。
- 交付提交：源码 + 测试 = `695e8d46b8d1627dec96068a3291498ab297703a`（`sourceTree=754decda5f404f141d78125ffa42a5f451720aa0`，`javaInputFingerprint=e40bd661ebc0925ce2da76d0f3bb16c069d428190c67fb22e7e93be6181b146a`）。其后的提交只改本报告文字；最终 HEAD 与指纹见 `local-tmp/acceptance-handoff.md`。
- 槽位 / 端口 / 数据目录：Slot 5，Gateway `21618` / Frontend `26618`；本轮**未启动固定端口服务**，未占用槽位端口。测试使用进程内 SDK + 临时 loopback Provider（ephemeral 端口）与 `local-tmp/acceptance-fixtures/` 下的独立 data-root。
- SDK：`local-tmp/sdk-selection.json` ABSENT → 基线 `io.haifa:haifa-agent-bom:0.1.3-SNAPSHOT`（`test-targeted` 报告 `sdkSelection=ABSENT`、`haifaVersion=0.1.3-SNAPSHOT`）。未使用带 worktree 标识的版本；未向被跟踪文件写入版本覆盖。
- M0 前置：`W19-DesignNote.md` §3/§4 已确认“无 SDK 缺口”，六处 SH 内部接缝由 SH 交付；本任务按 §4.2/§4.3/§4.4/§4.5 实现派发、非交互、配置与恢复，未新增领域对象、Run 状态机、事件总线或第二套持久化表。

## 2. 范围与归属

- 只交付：单实例后台轮询、到期派发成真实非交互 Run、occurrence↔Run 关联、完成跟随、启动恢复、排队超时与幂等/并发边界、相应配置读取与测试。
- 未做：REST 路由（M3）、多实例/跨实例租约（本期 NOT_RUN）、冻结 UI 与真实模型闭环（M4）。
- 归属裁决（README §4.1）：Run 生命周期/执行/取消仍归 Haifa Runtime；SH 只新增“时间计算 + 轮询 + 派发 + occurrence 记账 + 配置”。派发触发的 Run 走 `HaifaRunBridge.start`（内部经 `agent.conversations().start/submit`，非阻塞、持久化）；观测用有界 follower 轮询公开 `bridge.snapshot(runId)`（与既有 workspace/title 观察器同层），**未新建事件总线**，**未新增公开 HTTP 后台 Run 路由**。
- 存储：沿用 M1 文件型 `ScheduleStore`（`<dataDirectory>/scheduler/state.json`），未向 SDK SQLite 加表。

## 3. 变更内容清单

新增（`super-harness-server/src/main/java/io/haifa/agent/superharness/server/`）：

- `schedule/ScheduledRunLauncher.java`：窄的进程内派发接缝接口（`launch`/`watch`/`inspect`）与其值对象（`LaunchRequest`/`LaunchedRun`/`ScheduledRunOutcome`/`ScheduledRunInspection`）。终态词汇复用 `ScheduledRunStatus`。
- `schedule/ScheduleDispatchService.java`：单实例守护 worker（`enabled` 默认关）。每轮：启动恢复（一次、幂等）→ 排队超时 → 领取到期任务 → 按容量派发排队项。先领取（`queued→launching`）后启动；启动后关联写入失败保留活跃槽位且不重复启动；关联写入先于 follower 启动，快速完成不被迟到记账覆盖；终态由真实 Run 结果投影。

修改：

- `schedule/ScheduleStore.java`：新增 `listDueTasks`/`listQueuedRuns`/`listExecutingRuns`（派发读）、`cancelQueuedRun`、`revertLaunchingToQueued`、`pauseTask`、`finishTask`；`deleteTask` 增加取消 `queued` 并拒绝 `launching/running`（保留原 2 参重载）。
- `api/DeerFlowThreadRunApi.java`：`implements ScheduledRunLauncher`；新增非交互装配（从线程工具中排除 `ask_clarification`）、后台线程 ensure（owner 校验）、`HaifaRunBridge.start` 启动、Run 关联与有界 follower（`watch`）；`inspect` 供恢复读取真实 Run 结果；`close()` 有界中断/join follower。
- `api/DeerFlowBootstrapApi.java`：解析 `SchedulerRunConfiguration`（生产从环境）、创建并持有 `ScheduleStore` + `ScheduleDispatchService`、`start()` 启动 worker（`enabled=false` 时 inert）、`close()` 先有界停 worker 再拆 Run API；新增 `scheduler()` 访问器与 `createForSchedulerTests(...)` 测试装配。

新增（测试）：

- `schedule/ScheduleDispatchServiceTest.java`（9）：M2-02/03/04/05/06/07/08/12/13 的确定性记账断言（fake launcher，无 SDK）。
- `api/W19BackgroundDispatchHttpTest.java`（2）：M2-01/02/10/11 的真实 HTTP/SDK 链（生产 Gateway + 受控 loopback Provider）。

## 4. Oracle 对照

| case | 实际断言 | 结果 |
| --- | --- | --- |
| W19-M2-01 | `once` 到期经 `pollOnce` 真实启动一次 Run；occurrence `run_id` 非空并与任务 `last_run_id` 一致；`thread_id` 为 `sched-` 前缀新线程；Provider 真实收到 1 次调用 | PASS |
| W19-M2-02 | 触发后 occurrence→`running`（`attempt_count=1`）、任务 `last_run_id`/`last_thread_id` 正确；`interval` 完成回 `enabled` 且 `next_run_at=now+every_seconds`；`run_count` 累加 1 | PASS |
| W19-M2-03 | Follow Run 结果投影：`COMPLETED→success`、`FAILED/TIMEOUT→failed`、`CANCELLED→interrupted`；`once` 成功→`completed`、失败→`failed`、中断→`cancelled`；重复计划回 `enabled` | PASS |
| W19-M2-04 | `max_concurrent_runs=1` 下两条到期任务仅 1 条启动、另一条持久为 `queued`（不 skip/不丢）；完成释放后按序派发排队项 | PASS |
| W19-M2-05 | 同一任务连续 3 轮到期仅 1 个 Run、1 行活跃 occurrence | PASS |
| W19-M2-06 | 暂停态手动触发可运行且任务保持 `paused`、原 `next_run_at` 不变；`pauseTask`/`deleteTask` 取消 `queued`（→`skipped`）；对 `launching/running` 抛 `ActiveOccurrenceConflict` | PASS |
| W19-M2-07 | 重启分级恢复：`queued` 保留；无 Run 的 `launching` 退回 `queued`；有关联 Run 的执行态按真实结果收敛（success/failed/interrupted）；无 Run 的 `running`→`interrupted`；仍活跃 Run 保持 `running`；恢复不启动任何 Run | PASS |
| W19-M2-08 | `enabled=false` 时 `start()` 不启动（`running()==false`）；`enabled=true` 正常启动并在 `close()` 有界停止（join ≤5s 预算） | PASS |
| W19-M2-09 | 多实例/跨实例租约/`recoverAbandoned` 跨实例接管：**NOT_RUN**（本期不支持，见 §8） | NOT_RUN |
| W19-M2-10 | 真实定时 Run 工具集不含 `ask_clarification`（provider-request-1 `tools` 实测）；需审批的 `write_file` 进入 `WAITING_APPROVAL`（HTTP run `diagnostics.sdk_status`）而非静默放行；occurrence 保持 `running` 活跃 | PASS |
| W19-M2-11 | 无任何 SSE 客户端：有界 follower 自行观测到终态并落 occurrence（`success`/完成时间）；Run 不因缺少订阅者丢失 | PASS |
| W19-M2-12 | `queue_timeout_seconds` 到期：`queued`→`failed` 并释放活跃槽位；自动 `once` 父任务→`failed`；重复计划重算 `next_run_at` 且保持 `enabled`；手动触发保留原计划与状态；暂停态不被覆盖（仅清等待项）；全程 `launcher.requests` 为空（无真实 Run/Tool） | PASS |
| W19-M2-13 | 启动前占位（先 claim 后 launch）；注入“启动后关联写入失败”→ occurrence 保持活跃、下一轮不重复启动（`launch` 只 1 次）；终态写入后迟到 `running` 写入不覆盖（`protect_terminal`）；同一次启动 `run_count` 只累加一次（M1 store 用例 + 本任务服务用例） | PASS |

## 5. 命令与证据

- M2 确定性记账 + 真实 HTTP/SDK + M1 回归（66 用例）：`pwsh -NoProfile -File scripts/test-targeted.ps1 -Test 'ScheduleDispatchServiceTest,W19BackgroundDispatchHttpTest,ScheduleStoreTest,ScheduleCalculatorTest,ScheduleWireShapeTest,SchedulerRunConfigurationTest,ServerSdkBoundaryArchitectureTest,DeerFlowThreadRunApiTest,DeerFlowBootstrapApiTest' -LowMemory`
  - 结果：`status=PASS`，66 通过 / 0 失败 / 0 错误 / 0 跳过，`elapsedMs≈80337`。
  - 原始日志：`local-tmp/test-targeted/test-targeted-20261017-101558-389.log`（另存 `local-tmp/slot-5-w19-m2/evidence/`）。
- 仅 M2 服务记账（9 用例）：`... -Test 'ScheduleDispatchServiceTest' -LowMemory` → `status=PASS`，9/0/0，`elapsedMs≈57460`；日志 `test-targeted-20261017-101447-665.log`。
- 真实 HTTP/SDK 证据（GatewayFixture production + 受控 Provider）：
  - once 派发：`local-tmp/slot-5-w19-m2/evidence/http-once-dispatch/`（`provider-request-1.json`、`provider-response-1.sse`、`fixture.summary.json`）。
    - 工具集实测：`glob,grep,image_search,list_uploaded_files,ls,present_files,read_file,review_skill_package,skill_manage,str_replace,view_image,web_fetch,web_search,write_file`；`ask_clarification present: false`。
  - 审批路径：`local-tmp/slot-5-w19-m2/evidence/http-approval/`（`scheduled-run.json` 记录 `sdk_status`，`provider-request-1.json` 含 `write_file`）。
- 预检：`python scripts/lib/quick_checks.py --repo . --base main` → `{"failures": []}`（含 SDK 基线门禁、`git diff --check`、变更 secret scan）。
- 门禁范围：`python scripts/governance/change_scope.py` → `gates=["docs-check","verify-full","ui-live-round-x1"]`，`uiModel=live`（因 `config/` 归类为 run/prompt/tool/config），`acceptanceTier.dailyPr=L1`。

## 6. 首个 FAIL（保留）

- 首次运行 36 用例中 6 失败（`local-tmp/slot-5-w19-m2/evidence/test-targeted-20261017-100348-880.log`）：2 个真实 HTTP 用例 + 4 个服务记账用例。均为**测试或产品边界问题**，未放宽任何断言、未跳过用例：
  1. `ScheduleDispatchServiceTest.capacityFullQueuesAndDispatchesWhenReleased`：断言依赖“先创建的任务先派发”，但 `ScheduleStore` 以随机 `occ-<hex>` id 作同 `created_at` 的次排序键 → 断言假定了不确定的顺序。**测试缺陷**：改为断言“恰好 1 条 `running`、1 条 `queued`”并跟踪被排队任务。
  2. `ScheduleDispatchServiceTest.manualTriggerAndPauseDeleteBoundaries`：期望手动触发后暂停任务保持 `paused`，实测为 `running`。**产品缺陷**：启动记账无条件写 `running`，会对暂停任务/manual 触发借用自动运行状态。修复：`launchOccurrence` 对 `paused` 或 `manual` 传 `status=null`（保持任务自身状态）。
  3. `ScheduleDispatchServiceTest.queueTimeoutFailsWaitingOccurrenceAndAppliesParentRules`：手动任务的 `next_run_at` 被设为过去时间，超时后又被当作自动到期重新领取。**测试缺陷**（现实中手动触发任务自身计划在将来）：将手动任务 `next_run_at` 设为将来。
  4. `ScheduleDispatchServiceTest.terminalProjectionAndOnceTaskStatus`：断言 `last_error="run failed"`，但测试用的 fake launcher 传了 `error=null`。**测试缺陷**：让 fake 按真实 follower 的 `scheduledTerminalError` 映射 error。
  5. `W19BackgroundDispatchHttpTest.dueOnce...`：偶发在 occurrence 落终态与任务终态两次写之间读取任务。**测试竞态**：增加任务终态的有界等待。
  6. `W19BackgroundDispatchHttpTest.scheduledRunApproval...`：在 Run 尚未到达审批前即断言 Provider 调用次数。**测试竞态**：先有界等待 `WAITING_APPROVAL`，再断言 Provider 调用与工具集。
- 修复后重跑：66/0/0 PASS（`test-targeted-20261017-101558-389.log`）。

## 7. 与冻结实现的有意差异（须单列）

1. **单实例**：未移植 lease/fence/Postgres advisory lock/多实例接管；worker 只有一条守护线程。`enabled=true` 但存在第二个 worker/实例时按 SH 现状 fail-closed（单实例进程内单 worker）。
2. **模型与上下文选择**：定时 Run 默认解析“当前默认可执行 managed model + 默认 Agent prompt”（`runModelResolver.apply(empty)` / `agentPromptResolver.apply(empty)`）；暂未消费任务的 `assistant_id` 做 Agent/模型选择（`assistant_id` 仅记入 RunRecord 元数据）。M3/M4 若需按任务 Agent 选模，需在 M3 接线并在 M4 用冻结 UI 验证。
3. **完成跟随**：冻结由 gateway 的 Run 完成回调驱动；本任务用**有界轮询 follower**（`bridge.snapshot`，25ms 起指数退避到 1s，上限 24h，与既有 workspace/title 观察器同层）。`WAITING_APPROVAL` 属非终态：不落终态、保持 `running`（对齐 W05：不静默放行、不因缺订阅者丢失）。跨进程重启后 SDK 无自动恢复，SH 只读真实结果收敛；`launching` 无 Run 退回 `queued` 的窗口与冻结一致。
4. **队列超时时间基准**：以 occurrence `created_at` 计 `queue_timeout_seconds`（M1 store 无独立 `queued_at`；`created_at` 即入队时间）。
5. **`running` 无关联 Run 的恢复**：冻结扫描集为 `launching/running`，本任务对“`running` 且无 `run_id`”按 `interrupted` 收敛（无法定位已提交 Run，不重放），对“`launching` 且无 `run_id`”退回 `queued`（对齐冻结 §3.5）。
6. **REST/HTTP**：本任务不新增公开后台 Run 路由；派发经 `DeerFlowThreadRunApi` 上的**进程内 Java 接缝**（`ScheduledRunLauncher`）完成，不暴露 HTTP 后台 Run。

## 8. 未运行项

- `verify-full`：L1 正式门禁，按 AGENTS 由主控排队，记 `NOT_RUN`（本任务只跑范围内定向测试 + 预检）。
- `ui-live-round-x1`：`change_scope` 因 `config` 归类给出 `live`；本改动**未接线到任何 REST/UI 路由**（REST 属 M3），无 UI 表面可跑，记 `NOT_RUN`。
- `docs-check`：本报告按模板交付；独立 docs 门禁 runner 未在改动范围内识别，记 `NOT_RUN`（随 `verify-full`/主控排队）。
- L2 冻结前端 + reference/Haifa 双后端差分：工作包出口，记 `NOT_RUN`。
- W19-M2-09 多实例/跨实例接管：本期不支持，明确 `NOT_RUN`。
- 真实模型路径（`live`）：留给 M4；本任务用确定性受控 Provider + 进程内 SDK 链。
- 慢测试（`@Tag("slow")`）：未新增、未运行。
- 冻结 UI：未触碰、未运行。

## 9. 交接 M3/M4

- 后台服务入口：`DeerFlowBootstrapApi.scheduler()` → `ScheduleDispatchService`（`start()`/`close()`/`pollOnce()`/`recover()`/`triggerManually(taskId, ownerId)`/`store()`）。
- 配置键与默认值：`HAIFA_SUPER_HARNESS_SCHEDULER_ENABLED`（false）、`..._POLL_SECONDS`（5）、`..._MIN_ONCE_DELAY_SECONDS`（60）、`..._MAX_CONCURRENT_RUNS`（3）、`..._QUEUE_TIMEOUT_SECONDS`（3600）。
- occurrence↔Run 关联：`ScheduleStore.updateRunStatus(occ, RUNNING, runId, ..., protectTerminal=true)` 写 `run_id`/`started_at`；`updateTaskAfterLaunch(..., lastRunId/lastThreadId, incrementRunCount=true)` 写任务 `last_run_id`/`last_thread_id`/`run_count`（同次启动只加一次）。终态由 follower 回调 `recordTerminal` 落 occurrence + 任务。
- 恢复语义：见 Oracle W19-M2-07；`recover()` 幂等、仅在启动时执行一次，`queued` 不入扫描集。
- M3 需要的 store 接口：`pauseTask`/`deleteTask(...,now)`（对 `launching/running` 抛 `ActiveOccurrenceConflict` → 409）、`triggerManually`（冲突 → 409）、`listRuns` 分页/过滤。
- 实际 SH SHA：`695e8d4`（`sourceTree=754decda…`，`javaInputFingerprint=e40bd661…`）。
- SDK 制品身份：`sdk-selection.json` ABSENT → 基线 `io.haifa:haifa-agent-bom:0.1.3-SNAPSHOT`。
- 通过/失败/未运行：见 §4/§8。剩余限制：§7 第 1/2/3 项。
- 原始证据路径：`local-tmp/slot-5-w19-m2/evidence/`（HTTP once/approval 原始 Provider 请求响应、定向测试日志）。

## 10. 下一步

1. 主控按 L1 排队 `verify-full` 并裁决 `ui-live-round-x1`（本改动无 REST/UI 表面）。
2. M3 在 `ScheduleStore` + `ScheduleDispatchService` 之上实现 9 个 REST 接口、权限与 422/409 语义；`pause/delete` 复用 `pauseTask`/`deleteTask` 的活跃冲突。
3. M4 用冻结 UI + 真实模型验证创建/触发/暂停/历史/刷新闭环，并由主控排队补 L2 双后端差分。

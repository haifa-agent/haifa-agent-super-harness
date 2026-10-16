# W19-task-1（M1）— 调度时间语义与持久化契约 开发报告

> 状态：本报告由 W19-task-1 执行者编写，记录实际交付、证据与偏差。任务状态、门禁结果与阶段决策以 SHPM 为准，本报告不预填 PASS。
> 里程碑：M1。冻结 DF：`e545c28ac301e6f9feae247c513d8ea8f83df294`。

## 1. 任务基本信息

- 任务：W19-task-1「调度时间语义与持久化契約」（M1）。
- 分支 / worktree：`feat-w19-schedule-model-store`，本目录。
- 基线：`a71dd89e7ce31a8c28f1cb691215ae176c3fd3d0`；`sourceTree=1bafc442e98f419e577e3f5ffcc65993ea73b23f`；`javaInputFingerprint=1a0caa585ddc3949b29939aaa9795d595913b4a66ca74614c68d67dbbc7e127d`（`scripts/governance/change_scope.py`）。
- 源码 + 测试交付提交：`f90fda14a001c41478736374ac6babe778bb0ee7`；本报告为其上的文档更新提交（仅文档，`javaInputFingerprint` 不变）。
- 槽位 / 端口 / 数据目录：Slot 5，Gateway `21618` / Frontend `26618`；本轮未启动服务，未占用端口。
- SDK：`local-tmp/sdk-selection.json` ABSENT → 基线 `io.haifa:haifa-agent-bom:0.1.3-SNAPSHOT`（`test-targeted` 报告 `sdkSelection=ABSENT`、`haifaVersion=0.1.3-SNAPSHOT`）。本任务未使用任何 SDK 类型，仅消费 SH 自身与 JDK 公开 API。
- M0 前置：`local-tmp/slot-5-w19/docrefs/W19-DesignNote.md` 为主控产出的 M0 设计说明（含 §4.1 存储/配置/派发建议）；本任务按该说明的 §4.1「文件型产品 store，不向 SDK SQLite 加表」实施，未引入超出 M0 范围的领域对象/状态机/持久化表。

## 2. 范围与归属

- 只交付：时间语义（`once`/`cron`/`interval` + 时区/DST）、任务与发生的文件型持久化、owner 隔离、单活跃约束、M2 所需的最小原子操作、对应配置读取与确定性单元/自检测试。
- 未做：后台轮询、Run 启动、REST 路由、Run 生命周期语义（属 M2/M3）。
- 归属：调度时间计算、任务/发生记账、产品状态持久化属 SH 产品层；未新增 SDK 依赖、未读取 SDK 私表、未复制 Runtime。
- 存储位置：`<dataDirectory>/scheduler/state.json`（文件型产品 store），沿用 `AtomicMoves.replace` 原子发布。未向 `runtime/haifa-agent.sqlite` 加表。

## 3. 变更内容清单

新增（`super-harness-server/src/main/java/io/haifa/agent/superharness/server/`）：

- `schedule/ScheduleCalculator.java`：对齐冻结 `deerflow/scheduler/schedules.py` 的时间语义；`validateTimezone`、`normalizeCronExpression`、`parseIntervalSeconds`、`validateIntervalSeconds`、`nextRunAt`、`validateOnceCreation`；全部用可注入 `now`；错误信息与冻结一致。
- `schedule/CronExpression.java`：5 字段 cron 解析与求值（`*`、单值、名字、范围、列表、`/step`；DOM/DOW 的 OR 规则），按任务时区以本地墙钟推进、输出 UTC。
- `schedule/ScheduleValidationException.java`：冻结 `ValueError` 语义（M3 映射 422）。
- `schedule/ScheduledTaskStatus.java`、`ScheduledRunStatus.java`、`ScheduledRunTrigger.java`：与冻结模型一致的词汇（含 `terminal()`/`active()`）。
- `schedule/ScheduledTask.java`、`ScheduledTaskRun.java`：产品记录；`wire()` 投影冻结前端字段形状（`frontend/src/core/scheduled-tasks/types.ts`），内部字段（`user_id`、`last_occurrence_seq`、`occurrence_seq`、`launch_accounted`）不投影。
- `schedule/ScheduleStore.java`：文件型 store，单锁串行 + 原子发布 + 失败回滚；owner 过滤、单活跃约束、`occurrence_seq` 唯一、原子领取、终态保护、可恢复关联、单次 `run_count` 记账。
- `config/SchedulerRunConfiguration.java`：对齐冻结 `scheduler_config.py` 的 `enabled/poll_interval_seconds/min_once_delay_seconds/max_concurrent_runs/queue_timeout_seconds` 边界与环境键。

新增（测试）：

- `schedule/ScheduleCalculatorTest.java`（13）、`schedule/ScheduleStoreTest.java`（8）、`schedule/ScheduleWireShapeTest.java`（2）、`config/SchedulerRunConfigurationTest.java`（3）。

## 4. Oracle 对照

| case | 实际断言 | 结果 |
| --- | --- | --- |
| W19-M1-01 | `once` 未来返回 UTC 瞬时；过去返回 `null`；naive 按任务时区解释；`min_once_delay_seconds` 边界（=60s 接受、59s 拒绝、过去拒绝） | PASS |
| W19-M1-02 | `cron` 常规/跨日/周/月；DST 跳变（NY 2026-03-08 02:00→03:00）与折叠（NY 2026-11-01 02:00→01:00）稳定输出 UTC；墙钟 noon 跨 DST 的 UTC 偏移变化 | PASS |
| W19-M1-03 | `interval` 下限（`min_once_delay_seconds`）、上限（`MAX_INTERVAL_SECONDS`）、非法值（0/负/浮点/布尔/字符串/缺失） | PASS |
| W19-M1-04 | 未知时区、固定偏移时区、cron 字段数错误、越界字段、非法 `schedule_type`、缺失 `run_at`/`cron` 均返回明确错误 | PASS |
| W19-M1-05 | 创建/读取/更新持久化；新建 store 实例跨重启读回；二次实例写入被三次实例读回 | PASS |
| W19-M1-06 | 并发创建第二个活跃发生：仅 1 成功、1 冲突，活跃行仅 1 行 | PASS |
| W19-M1-07 | 另一身份不可读（find/list/thread/runs 为空）、不可改（update 空、createRun 抛错）、不可删（false）；原 owner 不受影响 | PASS |
| W19-M1-08 | W00 `schedules` schema/fixture 尚未交付 → **BLOCKED_EXTERNAL / NOT_RUN**；仅提供补充性字段形状自检（`ScheduleWireShapeTest`），不作为 W00 契约门禁 | NOT_RUN |
| W19-M1-09 | 并发 `queued→launching` 仅 1 成功；`protect_terminal` 不覆盖已终态（仅回填 `run_id`/`started_at`）；注入持久化失败后活跃槽位保留、不重复领取、单活跃仍拒绝；`run_count` 同一次启动只累加一次 | PASS |

补充断言（非 Oracle）：`cron` 名字/步长、DOM/DOW OR 规则；`SchedulerRunConfiguration` 边界与非法环境值。

## 5. 命令与证据

- 定向测试：`pwsh -NoProfile -File scripts/test-targeted.ps1 -Test 'ScheduleCalculatorTest,ScheduleStoreTest,ScheduleWireShapeTest,SchedulerRunConfigurationTest' -LowMemory`
  - 结果：`status=PASS`，26 通过 / 0 失败 / 0 错误 / 0 跳过，`elapsedMs≈14838`。
  - 原始日志：`local-tmp/test-targeted/test-targeted-20261017-004003-996.log`。
- 架构边界：`... -Test 'ServerSdkBoundaryArchitectureTest' -LowMemory` → PASS（1/0/0）。
  - 原始日志：`local-tmp/test-targeted/test-targeted-20261017-004146-673.log`。
- 预检：`python scripts/lib/quick_checks.py --repo . --base main` → `{"failures": []}`（含 SDK 基线门禁、`git diff --check`、变更 secret scan）。
- 门禁范围：`python scripts/governance/change_scope.py` → `gates=["verify-full","ui-live-round-x1"]`，`uiModel=live`（因 `config/` 归类为 run/prompt/tool/config），`acceptanceTier.dailyPr=L1`。

## 6. 首个 FAIL（保留）

- 首次运行 26 用例中 1 失败：`ScheduleCalculatorTest.onceMinimumDelayBoundary`（断言「期望抛异常但未抛」）。
  - 根因：**测试自身的 `now` 取值错误**（用了 2026-01-01 而 `run_at` 是 2026-06-01，最小延迟边界失真），非产品缺陷。
  - 修复：将边界用例的 `now` 改为 `2026-06-01T00:00:00Z` 后重跑通过；未放宽任何断言、未跳过用例。
  - 首次失败原始日志：`local-tmp/test-targeted/test-targeted-20261017-003909-294.log`。

## 7. 与冻结实现的有意差异（须单列）

1. **存储介质**：冻结用 SQLAlchemy（SQLite/Postgres）；本任务按 M0 §4.1 用文件型 `state.json`。单实例、单文档原子发布；未移植多实例 lease/fence、Postgres advisory lock、`BEGIN IMMEDIATE` 等。
2. **裁剪列**：未移植 `lease_owner/lease_expires_at`、`overlap_policy`。`overlap_policy` 冻结默认 `enqueue` 且冻结前端 `types.ts` 不消费，本期固定「单活跃 + 入队等待」；`last_occurrence_seq` 保留为内部序号。删除任务仅删任务行（冻结 `delete_with_queue_cancellation` 同款），历史 occurrence 行保留。
3. **cron 引擎**：冻结用 Python `croniter`；本任务用自含 Java 5 字段求值器。本机无 `croniter` 且离线，未能逐值对照参考实现；DST 期望值依据「aware 本地构造选前偏移（fold=0）」与 Java `LocalDateTime.atZone` 的 gap/fold 解析等价推导（见 `ScheduleCalculatorTest` 注释）。**偏差风险**：`croniter` 对折叠窗口、`a/n` 语法、不可达表达式（>5 年）的处理未与参考逐项比对；不可达表达式抛 `ScheduleValidationException`（冻结 `croniter` 抛 `CroniterBadDateError`）。
4. **`can_project`/sequence 排序**：冻结有多处按 `occurrence_seq` 的父任务投影资格判定；单实例本期未移植，列表按 `created_at desc, id desc`（对齐冻结 `list_by_task`）。
5. **Run id 前缀**：store 生成的 occurrence id 前缀 `occ-`、task id `task-<32hex>`（task 对齐冻结 `task-{uuid4.hex}`）。

## 8. 未运行项

- `verify-full`：L1 正式门禁，按 AGENTS 由主控排队，记 `NOT_RUN`。
- `ui-live-round-x1`：`change_scope` 因 `config` 归类给出 `live`；本改动**未接线到任何 REST/UI/Run 路径**，无 UI 表面可跑。记 `NOT_RUN`，待主控裁决是否需要。
- L2 冻结前端 + reference/Haifa 双后端差分：工作包出口，记 `NOT_RUN`。
- 真实 Run 关联/真实模型证据：留给 M2/M4。
- 慢测试（`@Tag("slow")`）：未新增、未运行。

## 9. 交接 M2

- 时间计算签名：`ScheduleCalculator.nextRunAt(scheduleType, spec, timezone, Instant now) -> Instant|null`；`validateOnceCreation(spec, timezone, now, minSeconds)`；`validateIntervalSeconds(spec, minSeconds)`；`normalizeCronExpression`、`validateTimezone`。
- 存储接口：`ScheduleStore`（owner 过滤读、`createTask`/`updateTask`/`deleteTask`、`createRun` 单活跃 + 序号、`claimQueuedRun`、`associateRun`、`updateRunStatus(protectTerminal)`、`updateTaskAfterLaunch`、`listRuns` 分页/过滤、`findTaskInternal`/`ownerOfRun` worker 读）。
- 状态词汇：任务 `enabled/paused/running/completed/failed/cancelled`；发生 `queued/launching/running/success/failed/skipped/interrupted`；触发 `scheduled/manual`。
- 配置键：`HAIFA_SUPER_HARNESS_SCHEDULER_ENABLED/_POLL_SECONDS/_MIN_ONCE_DELAY_SECONDS/_MAX_CONCURRENT_RUNS/_QUEUE_TIMEOUT_SECONDS`（默认关、5、60、3、3600）。
- 单实例保证：单 store 实例内方法级锁；不支持多进程并发写同一目录（M2 必须单实例）。

## 10. 下一步

1. 主控按 L1 排队 `verify-full` 并裁决 `ui-live-round-x1`（本改动无 UI 表面）。
2. M2 在 `ScheduleStore` 之上实现非交互派发、完成跟随与启动恢复，消费上述原子操作。
3. W00 交付 `schedules` schema/fixture 后补跑 W19-M1-08 契约门禁。

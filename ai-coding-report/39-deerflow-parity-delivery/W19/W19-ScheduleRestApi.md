# W19-task-3（M3）— 调度 REST 兼容层 开发报告

> 状态：本报告由 W19-task-3 执行者编写，记录实际交付、证据与偏差。任务状态、门禁结果与阶段决策以 SHPM 为准，本报告不预填 PASS。
> 里程碑：M3。冻结 DF：`e545c28ac301e6f9feae247c513d8ea8f83df294`。

## 1. 任务基本信息

- 任务：W19-task-3「调度 REST 兼容层」（M3）。
- 分支 / worktree：`feat-w19-schedule-rest-api`，本目录（base `main` `ccaec0d`，已含 #68 契约、#69 M1、#70 M2）。
- 交付提交：`e7f9fe843b91c6507ac319c3b66f99862c1ab061`（源码 + 测试；其后的提交仅本报告文字）。
- 槽位 / 端口 / 数据目录：Slot 5，Gateway `21618` / Frontend `26618`；本任务**未启动固定端口服务**，未占用槽位端口。测试使用进程内 Gateway（ephemeral 端口）+ 受控 loopback Provider，data-root 落在 `local-tmp/acceptance-fixtures/data/case-*`。
- SDK：`local-tmp/sdk-selection.json` ABSENT → 基线 `io.haifa:haifa-agent-bom:0.1.3-SNAPSHOT`（`test-targeted` 报告 `sdkSelection=ABSENT`、`haifaVersion=0.1.3-SNAPSHOT`）。未使用带 worktree 标识的版本；未向被跟踪文件写入版本覆盖。
- 复用 M1/M2：只新增 REST 映射、校验与投影，直接调用 `ScheduleStore` 与 `ScheduleDispatchService`，未复制调度计算、未复制 Run 启动逻辑、未新增第二套状态。

## 2. 范围与边界

- 交付 9 个冻结前端消费的 REST 接口，形状与错误语义对齐冻结 DF，不含多实例语义。
- 响应形状严格对齐 `frontend/src/core/scheduled-tasks/types.ts`，并实际对照 W00 `contracts/upstream/deerflow/schemas/scheduled-task*.schema.json` 校验（W19-M3-08 / W00-SCH-04）。
- 未做：`preview-cron`（可选，本期 NOT_RUN）、多实例/跨实例语义（NOT_RUN）、冻结 UI 与真实模型闭环（M4）。

## 3. 路由清单与权限映射

| 方法 | 路径 | 处理 | 权限 / owner 语义 |
| --- | --- | --- | --- |
| GET | `/api/scheduled-tasks` | `listTasks` | 认证（401）；按请求 owner 过滤 |
| GET | `/api/threads/{id}/scheduled-tasks` | `tryHandle`（`/api/threads` 组合路由内） | 认证；thread 须存在且属于 owner，否则 404 |
| GET | `/api/scheduled-tasks/{id}/runs?limit&offset[&status]` | `listRuns` | 认证；task 须属于 owner，否则 404 |
| POST | `/api/scheduled-tasks` | `createTask` | 认证；owner 为任务 owner |
| PATCH | `/api/scheduled-tasks/{id}` | `updateTask` | 认证；task 须属于 owner，否则 404 |
| POST | `/api/scheduled-tasks/{id}/pause` | `pauseTask` | 认证；task 须属于 owner |
| POST | `/api/scheduled-tasks/{id}/resume` | `resumeTask` | 认证；task 须属于 owner |
| POST | `/api/scheduled-tasks/{id}/trigger` | `triggerTask` | 认证；task 须属于 owner |
| DELETE | `/api/scheduled-tasks/{id}` | `deleteTask` | 认证；task 须属于 owner |

- 认证复用既有 Gateway 守卫：本地 auth 关闭时仅 loopback；开启时无会话一律 401。
- 权限复用既有基础设施：SH 现有 auth 面（`DEFAULT_PERMISSIONS`）对所有已认证用户授予 `threads:read/write` 与 `runs:create`，未新造授权引擎；本层落实的是“认证 + owner 作用域”。越权读取一律 404（不做存在性预言机）。

## 4. 错误码矩阵

| 条件 | 冻结 DF 分支 | SH 实现 |
| --- | --- | --- |
| 未认证 | 401 | Gateway 守卫 401 |
| task/thread 不属于 owner 或不存在 | 404（复用读取） | 404 `Scheduled task not found` / `Thread not found` |
| `schedule_type` 非 `once/cron/interval` | 422 | 422 |
| `context_mode` 非枚举 | 422 | 422 |
| `reuse_thread` 缺 `thread_id` | 422 | 422 |
| `reuse_thread` 线程不可访问 | 404 | 404 |
| `cron` 缺 `schedule_spec.cron` / 非法表达式 | 422 | 422 |
| `interval` 非正整数、低于 `min_once_delay_seconds` 或超 `MAX_INTERVAL_SECONDS` | 422 | 422 |
| `once` 非未来时间或低于最小延迟 | 422 | 422 |
| 更新/恢复遇到 running 任务或任何活跃 occurrence（queued/launching/running） | 409 | 409（含 queued 提示） |
| 暂停/删除遇到 launching/running | 409 | 409 |
| 暂停/删除取消 queued | queued→interrupted（DF）/ skipped（SH 枚举） | 原子取消为 `skipped` |
| 触发遇活跃 occurrence | 409 | 409 |
| 触发启动失败（Run 未提交） | 502 | 502 |
| 未知请求字段 | W00 schema `additionalProperties:false` | 422 |

`interval` 更新且 `every_seconds` 未变化时保留原 `next_run_at`；`once` 改到未来时重算并校验，改到过去拒绝 422；终态任务改到未来重新置 `enabled`。

## 5. 响应形状

- 任务对象键集合与 `scheduled-task.schema.json` 的 `required` 完全一致（18 字段）；运行对象与 `scheduled-task-run.schema.json` 一致（12 字段）。时间字段为可解析 ISO-8601 字符串；`next_run_at/last_run_at/last_run_id/last_thread_id/last_error/run_id/error/started_at/finished_at` 允许 null。
- 内部字段 `user_id`、`last_occurrence_seq`、`occurrence_seq`、`launch_accounted` 不投影。
- `create/update/pause/resume` 返回任务对象；`trigger` 返回 `{id, triggered}`；`delete` 返回 `{id, deleted}`；列表返回数组；运行历史返回数组。

## 6. Oracle 对照

| case | 实际断言 | 结果 |
| --- | --- | --- |
| W19-M3-01 | 列表/线程列表返回正确子集；运行历史 `limit/offset` 分页、`status` 过滤正确；形状键集合与契约一致 | PASS |
| W19-M3-02 | once/cron/interval 创建成功且形状正确（cron 归一化、interval next≈now+every）；非法 type/spec/timezone/过去时间/超限 interval/未知字段分别 422；reuse 缺线程 422、不可访问 404 | PASS |
| W19-M3-03 | 合法更新生效且不扰动 `next_run_at`；活跃 occurrence 409；once 改未来重算、改过去 422；interval 同 cadence 保留时间；fresh 清空 `thread_id`；终态重新置 `enabled` | PASS |
| W19-M3-04 | pause 取消 queued（→skipped）且任务 paused；resume 恢复 enabled；活跃 queued 上 resume 409；running 上 pause/resume 409 | PASS |
| W19-M3-05 | 真实派发：手动触发经生产 Gateway + 受控 Provider 启动真实 Run，occurrence success、`run_id`/时间戳齐全、Provider 实收 1 次；暂停态触发可用且任务保持 paused；活跃冲突 409；未知任务 404；模型不可用启动失败 502 | PASS |
| W19-M3-06 | 删除 queued 取消并返回 `{deleted:true}`，删除后列表/运行历史不可再查（404）；running 上删除 409，随后删除成功且再删 404 | PASS |
| W19-M3-07 | 未认证 401；跨 owner 列表为空、runs/PATCH/DELETE/线程列表均 404；本 owner 读取 200 | PASS |
| W19-M3-08 | 实时响应键集合等于 W00 `scheduled-task`/`scheduled-task-run` schema 的 `required`，枚举取值在 schema enum 内 | PASS |

## 7. 命令与证据

- M3 定向（2 类 10 用例）：`pwsh -NoProfile -File scripts/test-targeted.ps1 -Test 'W19ScheduleRestApiHttpTest,W19ScheduleTriggerHttpTest' -LowMemory`
  - 结果：`status=PASS`，10 通过 / 0 失败 / 0 错误 / 0 跳过，`elapsedMs=22819`。
  - 原始日志：`local-tmp/test-targeted/test-targeted-20261018-012641-001.log`（副本 `local-tmp/slot-5-w19-m3/evidence/`）。
- M1/M2 + 路由回归（6 类 60 用例）：`... -Test 'ScheduleStoreTest,ScheduleDispatchServiceTest,ScheduleCalculatorTest,ScheduleWireShapeTest,W19BackgroundDispatchHttpTest,DeerFlowBootstrapApiTest' -LowMemory`
  - 结果：`status=PASS`，60 通过 / 0 失败 / 0 错误 / 0 跳过，`elapsedMs=24502`。
  - 原始日志：`local-tmp/test-targeted/test-targeted-20261018-012711-501.log`（副本同上）。
- 全仓门禁：`pwsh -NoProfile -File scripts/verify-full.ps1`（进程内设 `HAIFA_SUPER_HARNESS_SKILL_PYTHON=D:/agents/deer-flow/backend/.venv/Scripts/python.exe`）
  - 结果：`status=PASS`，`tests=937`、failures 0、errors 0、skipped 21，`elapsedMs=300540`，`sourceSha=ccaec0d`（未提交时跑）。
  - 原始日志 / 摘要：`local-tmp/verify-full/verify-full-20261018-012809-180.log`、`.json`（副本同上）。
- 真实 HTTP/Provider 原始证据：`local-tmp/acceptance-fixtures/evidence/case-*`（`http-*.summary.json`、`model.json`、`fixture.summary.json`）。
- 契约：`contracts/upstream/deerflow/schemas/scheduled-task*.schema.json`、`fixtures/core-protocol-fixtures.json`（`schedules.*` 12 项，未改写）。

## 8. 首个 FAIL（保留）

1. 首次 `test-targeted` 编译失败（`test-targeted-20261018-012253-318.log`）：测试辅助方法 `object()` 被无参调用。**测试缺陷**，改为 `json.createObjectNode()`。
2. 第二次运行 10 用例中 2 失败（`test-targeted-20261018-012347-400.log`）：
   - `listsTasksThreadScopeAndPaginatesRuns`：同一毫秒内两行 `createdAt` 相同，排序由随机 `occ-<hex>` id 决定，首行断言不稳定。**测试缺陷**：`seedRun` 改用互不相同的 `scheduled_for` 作为创建时钟，排序确定。
   - `updatesApplyAndActiveOccurrenceConflicts`：仅改 timezone 时 interval 的 `next_run_at` 被重算（差 ~10ms）。**产品缺陷**：未实现冻结的“同 cadence 保留原 `next_run_at`”。修复：`preservedIntervalNext` 仅在 `every_seconds` 未变且原值非空时保留。
   - 修复后 10/0/0 PASS；未放宽任何断言、未跳过用例。

## 9. 与冻结实现的有意差异 / 剩余限制

1. **单实例**：未移植 lease/fence/多实例接管；后台派发仍为单进程单 worker。
2. **pause 取消 queued 的终态词汇**：DF 记 `interrupted`，SH 复用 M1 的 `skipped`（前端枚举同时接受两者）。
3. **assistant_id 校验**：默认 `lead_agent`；自定义名规范化后须存在（复用 `ConfigurationStore` 的 owner=default 目录）。多用户下自定义 Agent 仍是全局目录，未做每用户目录。
4. **权限粒度**：SH 现有 auth 面未提供按 permission 的细粒度拒绝（所有已认证用户持有 `DEFAULT_PERMISSIONS`），本层落实认证 + owner 作用域，未新造授权引擎。
5. **`preview-cron` 未实现**：冻结前端不消费，按提示词记 NOT_RUN。
6. **响应细节**：`assistant_id` 缺省返回 `lead_agent`；W00 fixture 中存在 `null` 样例，两者均在 schema/types 允许范围内（`string|null`）。

## 10. 未运行项

- `preview-cron`（`POST /api/scheduled-tasks/preview-cron`）：本期可选，NOT_RUN。
- `ui-controlled-round-x1`：`change_scope` 给出 `controlled`；本改动未改冻结前端，正式 UI 轮次由主控排队，NOT_RUN。
- L2 冻结前端 + reference/Haifa 双后端差分：工作包出口，NOT_RUN。
- 真实模型（live）链路：留给 M4。
- 多实例/跨实例语义：本期不支持，NOT_RUN。
- 慢测试（`@Tag("slow")`）：未新增、未运行。

## 11. 交接 M4

- 路由与权限矩阵：见 §3；错误矩阵见 §4；响应形状见 §5。
- 实际 SH SHA：`e7f9fe843b91c6507ac319c3b66f99862c1ab061`（base `ccaec0d`）。
- 通过/失败/未运行：§6 PASS 8/8；§10 NOT_RUN 项如上。
- 剩余限制：§9（单实例、preview-cron、多用户 Agent 目录、权限粒度）。
- 原始证据路径：`local-tmp/slot-5-w19-m3/evidence/`（定向/回归/verify-full 日志与摘要）、`local-tmp/acceptance-fixtures/evidence/case-*`（HTTP/Provider 原始件）。
- 复用说明：本报告提交仅改文档，`verify-full` 复用同一 Java 输入指纹的通过摘要（报告不改变指纹）；若后续代码或断言变化，需重新运行受影响门禁。

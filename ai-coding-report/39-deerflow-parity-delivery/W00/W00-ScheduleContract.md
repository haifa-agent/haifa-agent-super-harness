# W00-ScheduleContract 开发与交付报告

## 1. 任务基本信息

- **任务**：W00-task-3「定时任务消费契约（schedules 组）」，工作包 W00。
- **分支 / worktree**：`feat-w00-schedule-contract`（基线 `a71dd89`）。
- **槽位与端口**：Slot 6，Gateway `22618` / Frontend `27618`；数据目录 `local-tmp/slot-6-w00-schedule-contract/data`（本任务为纯契约，未启动服务，未使用端口与数据目录）。
- **冻结来源**：`bytedance/deer-flow@e545c28ac301e6f9feae247c513d8ea8f83df294`。
- **消费方**：W19（`W19-ScheduleRestApi` 等）只消费本契约，不重定字段语义。

## 2. 变更内容清单

新增 schema（`contracts/upstream/deerflow/schemas/`）：

| 文件 | 内容 |
| --- | --- |
| `scheduled-task.schema.json` | 任务对象，18 个字段，`additionalProperties:false` |
| `scheduled-task-run.schema.json` | 发生对象，12 个字段，`additionalProperties:false` |
| `scheduled-tasks-list-response.schema.json` | `ScheduledTask[]`（裸数组） |
| `scheduled-task-runs-list-response.schema.json` | `ScheduledTaskRun[]`（裸数组） |
| `scheduled-task-create-request.schema.json` | 创建请求体（`ScheduledTaskPayload`） |
| `scheduled-task-update-request.schema.json` | 更新请求体，形如 `{task_id, body: Partial<...>}` |
| `scheduled-task-id-request.schema.json` | pause/resume/trigger/delete 的路径参数请求 `{task_id}` |
| `scheduled-task-thread-list-request.schema.json` | `{thread_id}` |
| `scheduled-task-runs-request.schema.json` | `{task_id, query:{limit,offset}}` |
| `scheduled-task-trigger-response.schema.json` | `{id, triggered}` |
| `scheduled-task-delete-response.schema.json` | `{id, deleted}` |

登记改动：

- `contracts/upstream/deerflow/core-protocol-oracle.json`：新增 9 条 `rest.schedules.*.exchange` 契约到 `contracts` 与 `required_contract_ids`，证据全部锚定冻结前端 `frontend/src/core/scheduled-tasks/{types.ts,api.ts}`；`known_not_covered` 增补两条边界说明。
- `contracts/upstream/deerflow/fixtures/core-protocol-fixtures.json`：新增 15 条夹具（三类计划正例、列表/分页/更新/暂停/恢复/触发/删除正例、4 条错误负例）。错误负例复用既有 `error.fastapi.detail` 契约与 `http-error-response.schema.json`。
- `scripts/contracts/verify-deerflow-contracts.ps1`：`Assert-JsonMatchesSchema` 的 `Test-Json` 增加 `-FetchExternalReference`（见 §5 环境缺陷）。
- `tests/checks/contracts/verify-deerflow-contracts.ps1`：新增 6 条 schedules 负例闸门（未知任务状态、缺 `schedule_spec`、未知 occurrence trigger、可空时间戳塞数字、未声明字段泄漏、删除必选契约）。
- `AGENTS.md`：在「PowerShell 7」记录 `Test-Json` 相对 `$ref` 的环境事实与最小修复。

未做：后端实现、调度计算、REST 路由（W19）；`/api/scheduled-tasks/preview-cron`（冻结前端不消费，本期不纳入）；未新建平行契约目录。

## 3. Oracle 验收对照

| case | 断言 | 结果 | 证据 |
| --- | --- | --- | --- |
| W00-SCH-01 | 任务与发生 schema 对 once/cron/interval 正例通过；字段名/类型/可空性对齐 `types.ts` | PASS | §4.1、§4.2 |
| W00-SCH-02 | 422/409/401/404 错误形状与现有 HTTP 错误 schema 一致 | PASS | §4.3 |
| W00-SCH-03 | 枚举取值集合与冻结 `types.ts` 完全一致 | PASS | §4.2 |
| W00-SCH-04 | 契约门禁在 W19 REST 响应上可实际校验通过 | NOT_RUN | 依赖 W19-task-3 真实响应；本 worktree 无冻结 DeerFlow 后端检出，无法运行需要 `-DeerFlowRoot` 的整链门禁 |

## 4. 验证命令与原始证据

本任务为契约/静态交付，按仓库约定「不另建平行门禁」：提交的门禁仍是 `scripts/contracts/verify-deerflow-contracts.ps1`（经其 `tests/checks/contracts/` 封装）。由于本 worktree 只含冻结前端与 `contracts/`、不含冻结 DeerFlow 后端检出，整链门禁（含 `git HEAD == e545c28` 与后端 source 证据）只能由主控在有冻结检出时运行；本节记录可在本 worktree 内确定性复现的对等校验。所有输出原件见 `local-tmp/slot-6-w00/scratch/*.out.txt`。

### 4.1 夹具 schema 校验（与门禁同一 `Test-Json` 机制）

命令：`pwsh -NoLogo -NoProfile -File local-tmp/slot-6-w00/scratch/replay-fixtures.ps1`

结果（`-FetchExternalReference`，修复后的门禁语义）：

```
TOTAL fixtures=34 failDefault=5 failFetch=0
```

- 新 15 条 schedules 夹具全部 `PASS`。
- `failDefault=5` 全是使用兄弟文件 `$ref` 的既有/新增 schema 在未加 `-FetchExternalReference` 时被 PowerShell 7.6.7 拒绝；加该参数后为 0（见 §5）。

### 4.2 types.ts ↔ schema 对齐（枚举/字段/可空性）

命令：`python local-tmp/slot-6-w00/scratch/validate-schedule-contract.py`

结果：`RESULT PASS (all local alignment checks passed)`，其中：

```
PASS scheduled-task.status: enum set equals types.ts (['cancelled','completed','enabled','failed','paused','running'])
PASS scheduled-task-run.status: enum set equals types.ts (['failed','interrupted','launching','queued','running','skipped','success'])
PASS scheduled-task.context_mode / schedule_type: enum set equals types.ts
PASS scheduled-task-run.trigger: enum set equals types.ts (['manual','scheduled'])
PASS scheduled-task / scheduled-task-run: field set、required、每个字段的空值性（schema 含 null ⇔ types.ts 含 | null）、number→integer、Record<...>→object
PASS create request fields/required equal ScheduledTaskPayload keys
PASS update body fields equal Partial 去掉 thread_id/schedule_type
PASS trigger response {id,triggered}; delete response {id,deleted}
```

未匹配 `types.ts` 的字段数为 0（`FAIL` 计数 0）。

### 4.3 错误负例与 schema 拒绝力

命令：`pwsh -NoLogo -NoProfile -File local-tmp/slot-6-w00/scratch/negative-checks.ps1`

结果：`ALL NEGATIVE CASES REJECTED`（5/5）：未知任务状态、缺 `schedule_spec`、未知 occurrence trigger、可空时间戳塞数字、未声明字段泄漏。

4 条错误夹具（`schedules.error.validation-422 / active-conflict-409 / unauthenticated-401 / not-found-404`）对 `http-error-response.schema.json` 校验通过（§4.1）。

### 4.4 其它检查

```
pwsh -NoProfile -File tests/checks/build/verify-haifa-version-baseline.ps1
PASS: tracked POMs pin Haifa SDK baseline 0.1.3-SNAPSHOT (6 POMs checked).

git diff --check                  -> rc=0
quick_checks secret scan (diff)   -> failures: none
PowerShell AST parse (两个改动脚本) -> OK / OK
```

## 5. 首个失败与环境缺陷（如实记录）

1. **PowerShell 7.6.7 `Test-Json` 相对 `$ref` 缺陷（已修，非本任务引入）**：首次运行夹具校验时，`projects.list.active` 与 `thread.runs.bare-array` 即失败：
   `The JSON schema contains a reference that could not be resolved. External schema references are not supported by default, use the 'FetchExternalReference' parameter to enable it.`
   即提交版门禁在文档所列的 pwsh 7.6.7 上对任何使用兄弟文件 `$ref` 的 schema 都会假失败。最小修复：给 `scripts/contracts/verify-deerflow-contracts.ps1` 的 `Test-Json` 加 `-FetchExternalReference`（未改 schema、未内联 `$ref`、未改冻结字节）。修复后 34/34 夹具通过。已按「环境与排障记录」写入 `AGENTS.md` 的「PowerShell 7」。
2. **整链契约门禁的 DF 检出前置**：`tests/checks/contracts/verify-deerflow-contracts.ps1` 需要 `-DeerFlowRoot` 且要求其 `git HEAD == e545c28`，本 worktree 无该检出（派单亦禁止读取外部目录），故 W00-SCH-04 记 `NOT_RUN`；新增的 source 证据锚点改用本 worktree 内冻结前端（W18 导入，未被 W18 patch 触及，字节等同冻结版本）验证通过。

## 6. 明确 NOT_RUN / 后置与风险

- **W00-SCH-04**：`NOT_RUN`。需 W19 REST 真实响应与冻结 DeerFlow 检出运行整链门禁；不由本任务代跑或预填。
- **preview-cron**：按 W00-ScheduleContract §3 不纳入本期必做契约（冻结前端不消费）。
- **风险 1（记录给 W19）**：冻结 `types.ts` 中 `ScheduledTaskRun.thread_id` 为非空 `string`；若 W19 在派发前持久化 `queued` 发生而尚无 thread，须保证响应仍满足该形状（本契约严格对齐 `types.ts`，未放宽为可空）。
- **风险 2（记录给 W19）**：`http_status.success` 采用 create=201、其余=200。冻结前端只判 `response.ok`；若 DF 对 trigger 返回 202，也在 `ok` 范围内，不影响前端消费，但 W19 若要严格对齐 DF 需在设计说明中确认。
- **schedule_spec 形状**：按冻结前端 `Record<string, unknown>` 保持自由对象；per-type 校验（cron 语法、interval 上下限、once 过去时间）属 W19 REST 的 422 职责，不在消费 schema 中新增语义。

## 7. 身份与下一步

- 冻结 DF SHA：`e545c28ac301e6f9feae247c513d8ea8f83df294`。
- SDK 基线：`io.haifa:haifa-agent-bom:0.1.3-SNAPSHOT`（本任务未改 `pom.xml`，未消费新 SDK）。
- 基线提交：`a71dd89`；本任务提交见交接文件 `local-tmp/acceptance-handoff.md` 与实际 `git log`。
- 建议下一步：主控在持有冻结 DeerFlow 检出（HEAD=e545c28）的环境运行 `pwsh -NoProfile -File tests/checks/contracts/verify-deerflow-contracts.ps1 -DeerFlowRoot <检出>`，确认整链门禁含新增 schedules 契约与负例；随后 W19-task-3 以本契约校验真实 REST 响应（W00-SCH-04）。

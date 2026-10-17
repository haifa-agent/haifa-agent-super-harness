# W00-ScheduleContractReviewFix 返工报告

## 1. 任务基本信息

- **任务**：W00-ScheduleContractReviewFix，承接 PR #68 的 Codex/AarenWang 审查意见（4 条全部采纳）。
- **分支 / worktree**：`feat-w00-schedule-contract`；`D:\workspace\haifa-agent-super-harness-worktrees\w00-schedule-contract`（Slot 6，Gateway `22618` / Frontend `27618`；本任务为纯契约，未启动服务、未占用端口与 data-root）。
- **返工基线**：`a5e9ab45be20ce7986f04684955316c2f273f17e`（W00-task-3 原提交）。
- **冻结来源**：`bytedance/deer-flow@e545c28ac301e6f9feae247c513d8ea8f83df294`（冻结检出 `D:\workspace\haifa-agent-super-harness\deer-flow`，`git rev-parse HEAD` 已在门禁内断言）。冻结前端与 `schedules.py` 语义副本见 `local-tmp/slot-6-w00-reviewfix/frozen/`。
- **原始证据目录**：`local-tmp/slot-6-w00-reviewfix/evidence/`。

## 2. 逐条处置

### 2.1 运行历史请求的 page 可选（Codex P2）

- `contracts/upstream/deerflow/schemas/scheduled-task-runs-request.schema.json`：`required` 由 `["task_id","query"]` 改为 `["task_id"]`。
- `contracts/upstream/deerflow/fixtures/core-protocol-fixtures.json`：新增正值夹具 `schedules.runs.no-query`（`request` 只有 `task_id`），保留 `schedules.runs.paged`。
- 独立断言：verifier 内 `ExpectedRunHistoryRequestRequired`（`task_id`）直接校验 schema 的 `required` 不含 `query` 且集合一致；负例集新增“把 `schedules.runs.paged` 的 `request.query` 删除后整链门禁仍通过”，若有人把 `query` 重新设为必选即会失败。

### 2.2 独立钉住 schedules 契约基线（Codex P2）

- `scripts/contracts/verify-deerflow-contracts.ps1` 新增 verifier 自有的 `$ExpectedScheduleContractIds`（9 条 `rest.schedules.*`）与 `$ExpectedScheduleFixtureIds`（16 条 `schedules.*`），并按集合相等比较已声明清单；不依赖被检查清单自身的 `required_contract_ids`。
- `tests/checks/contracts/verify-deerflow-contracts.ps1` 新增负例：同时删除 `rest.schedules.trigger.exchange` 契约、其 `required_contract_ids` 项、以及夹具 `schedules.trigger.manual`，门禁仍须以 `independent verifier baseline` 拒绝。断言签名 `Schedule contract inventory drifted from the independent verifier baseline.`。

### 2.3 正值 fixture 使用冻结的 cron 键（AarenWang P2）

- `core-protocol-fixtures.json` 中 5 处 `schedule_spec` 由 `{"expression": ...}` 改为冻结的 `{"cron": "0 9 * * *"}`（`schedules.create.cron` 请求/响应、`schedules.update.prompt`、`schedules.pause.enabled`、`schedules.resume.paused`）。冻结键来源：`frontend/src/core/scheduled-tasks/cron.ts`（`hasScheduleSpec`/`serializeCron`）与 `recipes.ts`（`schedule_spec: { cron: "0 9 * * *" }`）。
- 新增 `scripts/contracts/verify-schedule-semantics.py`：导入冻结 `backend/packages/harness/deerflow/scheduler/schedules.py`（croniter），对每个 `schedule_type`/`schedule_spec` 节点断言 `cron` 键存在且为 5 段、禁止 `expression`；对 `rest.schedules.create.exchange` 的正值夹具以 `response.created_at` 为 `now` 计算下一次时间，`cron`/`once` 与声明的 `next_run_at` 精确相等（`schedules.create.cron` → `2026-09-24T01:00:00+00:00`）。
- 由 `verify-deerflow-contracts.ps1` 调用该 helper；负例新增“把 `schedules.create.cron` 的 `schedule_spec` 改回 `expression`”，门禁须以 `Frozen schedule semantics check failed` 拒绝。
- 解释器选择：`-SchedulePython` → `HAIFA_CONTRACT_PYTHON` → 冻结检出 `backend/.venv` → `AGENTS.md` 记录的冻结静态审查解释器 → PATH 上的 `python`/`python3`；都不含 croniter 时按前置失败（fail-closed）。

### 2.4 兼容缺少 FetchExternalReference 的 Test-Json（AarenWang P2）

- `scripts/contracts/verify-deerflow-contracts.ps1` 的 `Assert-JsonMatchesSchema` 先探测 `Test-Json` 是否存在 `FetchExternalReference` 参数：存在则沿用 `-SchemaFile ... -FetchExternalReference`；不存在（如 7.6.5）则读取 schema，按 `Resolve-SchemaExternalReferences` 把兄弟文件 `$ref` 内联（去掉内联子 schema 的 `$schema`/`$id`），再用 `Test-Json -Json <payload> -Schema <resolved>` 校验。`HAIFA_CONTRACT_FORCE_LEGACY_TEST_JSON=1` 可在支持该参数的运行时上强制走降级路径做回归。
- 既有契约检查不再因缺少该参数在任何受支持运行时上整体失败。

## 3. 门禁与证据

| 门禁 / 断言 | 结果 | 命令（均在本 worktree） | 原始证据 |
|---|---|---|---|
| 冻结契约门禁（7.6.7，默认 `-FetchExternalReference`） | PASS | `pwsh -NoProfile -File scripts/contracts/verify-deerflow-contracts.ps1 -DeerFlowRoot D:\workspace\haifa-agent-super-harness\deer-flow` | `evidence/contract-gate-7.6.7.txt`、`evidence/contract-gate-final.txt` |
| 冻结契约门禁（降级路径，等价 7.6.5 无该参数行为） | PASS | 同上 + `HAIFA_CONTRACT_FORCE_LEGACY_TEST_JSON=1` | `evidence/contract-gate-forced-legacy.txt` |
| 契约负例集（7.6.7，43/43 PASS，含 co-removal、cron key drift、no-query 三条新例） | PASS | `pwsh -NoProfile -File tests/checks/contracts/verify-deerflow-contracts.ps1 -DeerFlowRoot <检出>` | `evidence/contract-tests-7.6.7.txt`、`contract-tests-final.txt` |
| 契约负例集（降级路径，43/43 PASS） | PASS | 同上 + `HAIFA_CONTRACT_FORCE_LEGACY_TEST_JSON=1` | `evidence/contract-tests-forced-legacy.txt` |
| 脚本回归 `script-regressions` | PASS | `pwsh -NoProfile -File scripts/verify-scripts.ps1` | `evidence/verify-scripts.txt`（摘要 `local-tmp/verify-scripts/verify-scripts-20261017-151510-659280.json`） |
| 全仓门禁 `verify-full` | PASS（777 tests / 0 fail / 21 skipped） | `pwsh -NoProfile -File scripts/verify-full.ps1`（同一会话设 `HAIFA_SUPER_HARNESS_SKILL_PYTHON=D:/agents/deer-flow/backend/.venv/Scripts/python.exe`） | `evidence/verify-full-rerun3.txt`（摘要 `local-tmp/verify-full/verify-full-20261017-155735-243.json`） |
| SDK 基线 | PASS | `pwsh -NoProfile -File tests/checks/build/verify-haifa-version-baseline.ps1` | `evidence/verify-haifa-version-baseline.txt` |
| `git diff --check` | PASS（rc=0） | `git diff --check` | 本报告 §2、§5 |
| 变更范围 | 参考 | `python scripts/governance/change_scope.py` | `evidence/change-scope.json`（gates：docs-check、script-regressions、verify-full、ui-live-round-x1） |

- 新负例检出能力：`schedule contract/manifest-id/fixture co-removal`、`a cron schedule_spec that drifts off the frozen cron key is rejected`、`run history request without a query page is accepted` 均已在 7.6.7 与降级路径各跑一次并 PASS。
- 契约门禁语义校验实跑输出：`PASS every cron schedule_spec uses the frozen 'cron' key (5 node(s))`、`PASS fixture 'schedules.create.cron' yields a valid next run (2026-09-24T01:00:00+00:00)`、`PASS frozen schedule semantics verified`。

## 4. NOT_RUN（如实记录）

- **PowerShell 7.6.5 运行时**：本机只有 7.6.7（`$PSVersionTable.PSVersion` 7.6.7；`C:\Program Files\PowerShell` 无 7.6.x 目录，`AppData\Local\Microsoft\PowerShell\7.6.6` 只剩更新哨兵，无 `pwsh.exe`）。7.6.5 的“无 `FetchExternalReference`”行为用 `HAIFA_CONTRACT_FORCE_LEGACY_TEST_JSON=1` 在 7.6.7 上走同一条降级代码路径验证（见 §3），不把该替代证据记为 7.6.5 实机运行。
- **`ui-live-round-x1`**：改动的产品面只有冻结契约 schema/fixture（`contracts/`）、门禁脚本与文档，未改动任何前端、REST/SSE 服务端代码或运行语义；`change_scope` 将 `contracts/` 判为未知路径而给出 live，但本改动无法通过真实模型 UI 轮次获得额外覆盖。故记 `NOT_RUN`，不声称 PASS。
- **`docs-check`**：仓库内没有该门禁的可执行入口（仅在 `change_scope`/`test_pipeline_tools` 中出现名称），记 `NOT_RUN`；文档改动为 `scripts/README.md` 与本报告。

## 5. 首个 FAIL 与处理（保留原件）

1. **`verify-full` 首次失败（技能静态扫描缺 PyYAML）**：`SkillPackageRunHttpTest`（3 failures）与 `SkillPackageScreeningTest`（1 error），签名 `ModuleNotFoundError: No module named 'yaml'`（`skills/scanner/frontmatter.py` 在隔离解释器里导入 yaml）。按 `AGENTS.md`「真实模型诊断」在同一会话设 `HAIFA_SUPER_HARNESS_SKILL_PYTHON=D:/agents/deer-flow/backend/.venv/Scripts/python.exe`（实测 `-I -B` 下 `yaml 6.0.3`）后该 4 例全部通过。原件：`evidence/verify-full.txt`。
2. **`verify-full` 第二次失败（共享固定端口 14618）**：`DeerFlowSharedChildCapacityApiTest.cancellingAnotherThreadClosesItsPendingCallWithoutFakeStartOrSlotLeak`，签名 `JavalinBindException: Port already in use ... port 14618`。W08 委托类用例默认 `Integer.getInteger("w08.gatewayPort", 14618)`，多 worktree 并发全仓门禁时互相抢占该固定端口；改为单 fork 的低内存轮次进一步级联 9 例同类失败。原件：`evidence/verify-full-rerun.txt`、`evidence/verify-full-rerun2.txt`。等待 14618 空闲（loopback bind 预检 `bindable=True`）后重跑，`verify-full` PASS（原件 `evidence/verify-full-rerun3.txt`）。该冲突与本次改动无关，属跨槽位共享固定端口，未改测试、未放宽断言。

## 6. 影响与后续

- 本改动不触及 Java 输入以外的运行语义；`contracts/` 参与 Java 输入指纹，故 `verify-full` 按 `change_scope` 真跑（未复用旧结果）。
- 解释器兜底包含 `AGENTS.md` 记录的冻结静态审查解释器绝对路径；若目标机没有 croniter，契约门禁按明确前置 fail-closed，提示 `HAIFA_CONTRACT_PYTHON`/`-SchedulePython`。
- W08 共享端口 `14618` 的并发脆弱性建议由主控纳入「端口」环境记录或在后续任务中治理；本任务不改动测试端口。
- 未改动 W00-task-3 已交付的契约语义与断言强度：无放宽、无删除失败用例。

# W19-task-4 冻结前端与真实模型闭环验收（M4）开发报告

> 本文同时是 W19 工作包的整包验收报告。W19 整包结论（含 L2 双后端）由主控按证据结算；本报告只给单实例范围的执行证据与 NOT_RUN 清单，不宣布整包 GO。

## 1. 身份与范围

| 项目 | 值 |
| --- | --- |
| 分支 | `feat-w19-frozen-ui-acceptance` |
| 基线 SH SHA | `4aaf3beed6256fed9a38e8099fa8dba2407041be`（main，含 W00 契约 + M1 存储 + M2 派发 + M3 REST） |
| 交付提交 | 本报告所在提交（仅改 UI runner 与 UI 用例，见 §2） |
| 源码树 | flow-health `sourceSha=4aaf3be…`；本轮运行期间 `sourceDirty=true`（本次新增/修改的 runner 与 spec 未提交）。Java 输入指纹与冻结前端树未变，故构建戳 `REUSED`，被验收代码等价于基线树 |
| DF SHA | `e545c28ac301e6f9feae247c513d8ea8f83df294`（frontend 树 `993ef89baf273257b7940ceae002200c416f380d`） |
| SDK | `io.haifa:haifa-agent-bom:0.1.3-SNAPSHOT`（`local-tmp/sdk-selection.json` 缺失 → 基线；`sdkIdentity=c4752f840c3b03f903c269fbb9cd745e0c37b6337024f3ca3468efbb7786204b`） |
| 槽位/端口 | 槽位 5：Gateway `21618`、Frontend `26618`（`netsh` 排除段与实际 loopback bind 预检均通过） |
| data-root | 每轮独立：`local-tmp/slot-5-w19-m4/data/round-a4`（暖轮1）、`round-a5`（暖轮2）、`round-b` 复用 `round-a4`（重启轮） |
| 模型 | 真实 `https://api.deepseek.com`（managed model `w18-deepseek-live` / `deepseek-v4-pro`，`credential_ref=os://DEEPSEEK_API_KEY`，`credentialSource=os`） |
| 调度配置 | `HAIFA_SUPER_HARNESS_SCHEDULER_ENABLED=1`、`POLL_SECONDS=2`、`MIN_ONCE_DELAY_SECONDS=30`；interval 下界仍取冻结 60s（冻结 UI 对 interval 编辑钳到 ≥60s） |

## 2. 改动

仅改 UI runner 配置与新增 UI 用例，未改冻结前端源码、未改后端与契约：

- `scripts/ui/run-w18-ui-smoke.ps1`
  - 新增 profile `-W19ScheduledTasks`（spec `w19-scheduled-tasks.spec.cjs`）与 `-W19ScheduledTasksRestart`（spec `w19-scheduled-tasks-restart.spec.cjs`），复用真实托管模型，timeout 360s、trace `on`。
  - 两个 profile 注入调度开关（enabled/poll/min_once_delay）。
  - 修正槽位 5 端口映射：`12618/17618` → `21618/26618`，与 `AGENTS.md`、`shpm_dispatch.py`、`audit-work-package-delivery.ps1` 等端口表一致（原映射为陈旧值）。
- 新增 `tests/ui/smoke/w19-scheduled-tasks.spec.cjs`（7 个 case：M4-01/02/03/04/06/08/05）。
- 新增 `tests/ui/smoke/w19-scheduled-tasks-restart.spec.cjs`（1 个 case：M4-07）。

`python scripts/governance/change_scope.py`：`changedFiles=3`（全部 `ui`），`gates=["verify-full-reuse","ui-controlled-round-x2"]`，`acceptanceTier.dailyPr=L1`。
本轮 UI 正式轮次按 W19 M4 派单要求使用**真实模型**（严格强于 `controlled`），并跑了**两轮独立暖轮**（x2）以满足 `ui-controlled-round-x2` 的轮次要求；两轮均同一提交、独立 data-root。

## 3. Case → 断言 → 前置 → 原件

| case | 实际断言 | 前置 | 原件 |
| --- | --- | --- | --- |
| W19-M4-01 | 冻结 UI 建 `interval` 任务；API 列表一致（`schedule_type=interval`、`every_seconds=60`、`status=enabled`、`next_run_at` 非空）；详情显示标题/Interval，列表行显示 `Interval · Enabled` | 冻结前端 + M3 REST + 调度开关 | `round-a4-pass/artifacts/…/w19-m4-01.json`、`w19-m4-01-create.png` |
| W19-M4-02 | 到期自动触发真实 Run：出现 `scheduled` occurrence，终态 `success`，含真实 `run_id`/`thread_id`/`started_at`/`finished_at`；任务 `run_count≥1`；UI 运行历史显示 `scheduled · Success` | 真实模型 + 60s interval | `…/w19-m4-02.json`、`w19-m4-02-history.png` |
| W19-M4-03 | 手动触发可用；暂停态手动触发可用且任务保持 `paused`；`manual` occurrence≥2；任意时刻活跃 occurrence≤1 | 冻结 UI + M2 派发 | `…/w19-m4-03.json` |
| W19-M4-04 | UI Pause→Resume 语义与后端 `paused/enabled` 一致；运行中 Pause 返回 **409**；Delete 后行消失且列表不含该 id | 真实模型长输出 Run 撑开活跃窗口 | `…/w19-m4-04.json`（`pauseConflict=409`）、`w19-m4-04-lifecycle.png` |
| W19-M4-06 | 刷新后任务集合与历史与 API 一致；无重复行、无幽灵/丢失行；被删任务不回归 | — | `…/w19-m4-06.json`、`w19-m4-06-refresh.png` |
| W19-M4-08 | 定时 Run 可核验：`GET /runs/{id}` 终态 `success`；线程 state 有非空 assistant 消息（真实模型输出，`outputSha256` 记录） | M4-02 的成功 occurrence | `…/w19-m4-08.json` |
| W19-M4-05 | 线程页 `thread-scheduled-tasks-link` 可见并跳转 `?thread_id=`；绑定任务在过滤视图展示 | 真实模型建线程 + reuse_thread 任务 | `…/w19-m4-05.json`、`w19-m4-05-thread-link.png` |
| W19-M4-07 | 同一 data-root 重启：store 持久；列出的任务都在重启后的 store 中；**无遗留 `launching`**、活跃 occurrence 必带已提交 `run_id`；重启前成功 occurrence 历史可读；重启后 60s interval **再次派发并终态 success** | 复用 `round-a4` data-root | `round-b-restart-pass/artifacts/…/w19-m4-07.json` |
| W19-M4-09 | 多实例/分布式：本期不支持 | — | 见 §5 NOT_RUN |
| W19-M4-10 | 工作包出口 L2 双后端差分 | 主控排队 | 见 §5 NOT_RUN |

### 3.1 关键真实证据

- 自动触发（M4-02）：occurrence `occ-0288fae0cbe447d0bbafc0e8afe47dcf`，`run_id=01a14cb6-e329-7945-92be-a2944604272a`，`thread_id=sched-a6ca484f-0cad-4afd-bb4f-67660bf2e30c`，`scheduled_for=2026-10-18T01:53:34Z`，`finished_at=2026-10-18T01:53:39Z`，等待 65.2s。
- 真实模型输出（M4-08）：`runStatus=success`，`assistantLength=18`，`outputSha256=6bf5a485a33087e8a19770802c96f0357e4d140793764405106de005e46724a4`，`provider=https://api.deepseek.com`，`credentialSource=os`。
- 409 边界（M4-04）：运行中 `POST /pause` → `409`。
- 重启（M4-07）：`danglingLaunches=0`、`activeWithoutRun=0`；重启前 2 条 `scheduled` occurrence 均 `success` 可读；重启后新 occurrence 派发并 `success`。

## 4. 轮次健康度与首 FAIL

| 轮次 | profile | 结果 | frontendBuild/serverBuild | 断言耗时 | 总耗时 | 清理 |
| --- | --- | --- | --- | --- | --- | --- |
| 暖轮1 | `w19-scheduled-tasks` | 7/7 PASS（exitCode 0） | REUSED/REUSED | 93.1s | 110.4s | 成功；端口 21618/26618 释放 |
| 暖轮2（x2） | `w19-scheduled-tasks` | 7/7 PASS（exitCode 0） | REUSED/REUSED | — | 111.3s | 成功；端口释放 |
| 重启轮 | `w19-scheduled-tasks-restart` | 1/1 PASS（exitCode 0） | REUSED/REUSED | 63.8s | 80.4s | 成功；端口释放 |

- 首次冷构建：前端 `BUILT`（Next 编译 101s、TypeScript 75s；`nextDynamicCompilation=N/A: production next start`），服务端 exec JAR 由 `mvn package` 产出；此后各轮 `REUSED`。
- 无重试（`retryCount=0`）；`firstFailure=null`；`cleanup.success=true`，`remainingPids=[]`。
- 首 FAIL（原件保留）：第一轮 `round-a-fail-1` M4-01 `created interval task must appear…`（用例在 UI 建单后立即查 API，未等待异步 mutation 落库）；第二轮 `round-a2-fail-1` M4-01 详情页不含独立状态文本（状态只在列表行 `Interval · Enabled`）；第三轮 `round-a3-fail-1` M4-02 未先导航到页面即选行。三处均为**用例缺陷**，非产品缺陷，已按根因修复；无 Oracle 放宽、无断言删减。

## 5. NOT_RUN / 未闭合

- **W19-M4-10 L2 双后端差分**：`NOT_RUN`，由主控在工作包出口排队（未执行）。不扩为客户演示 L3。
- **W19-M4-09 多实例/分布式、跨实例接管**：`NOT_RUN`（本期不支持）。
- **`preview-cron`**：`NOT_RUN`（冻结前端不消费，M3 未做）。
- **全仓门禁 `verify-full`**：`NOT_RUN`。`change_scope` 给出 `verify-full-reuse`，但本 worktree 无同 Java 输入指纹（`4e3a5207036624710e9709453d8d0239dfb451397ca12c43e261d96de31558b9`）的既有通过摘要可复用；昂贵全仓门禁按规则由主控排队或放行后执行。
- 已执行的前置/提交检查：`git diff --check` 干净；`verify-haifa-version-baseline.ps1` PASS（6 个 POM 钉 `0.1.3-SNAPSHOT`）；secret scan `failures=[]`；`scripts/verify-scripts.ps1` PASS。

## 6. 范围与结论（单实例）

- W19 单实例范围（单实例轮询 + 启动恢复 + 冻结前端 9 接口）在本轮真实模型冻结 UI 闭环中逐项成立：创建/编辑语义、到期自动触发、手动触发（含暂停态）、运行历史、刷新一致性、线程侧入口、同 data-root 重启恢复与续排均通过。
- 冻结前端来源门禁 `frontendSourceGate=PASS`（按 `third-party/deerflow/manifest.json` 的 fileCount/bytes/inventory sha 校验；未使用 worktree 外冻结检出，故按 manifest 清单口径而非外部 `SourceRepository` HEAD 口径核验）。
- 整包 GO 与 L2 结算不在本报告结论内；未闭合项与多实例后置见 §5。

## 7. 原始证据路径

- 暖轮1（7/7 PASS）：`local-tmp/slot-5-w19-m4/evidence/round-a4-pass/`
- 暖轮2 x2（7/7 PASS）：`local-tmp/slot-5-w19-m4/evidence/round-a5-pass-x2/`
- 重启轮（1/1 PASS）：`local-tmp/slot-5-w19-m4/evidence/round-b-restart-pass/`
- 首 FAIL 原件：`local-tmp/slot-5-w19-m4/evidence/round-a-fail-1/`、`round-a2-fail-1/`、`round-a3-fail-1/`
- runner 现场证据根：`local-tmp/slot-5-w19-scheduled-tasks/evidence/`、`local-tmp/slot-5-w19-scheduled-tasks-restart/evidence/`
- 脚本回归摘要：`local-tmp/verify-scripts/verify-scripts-20261018-100451-291277.json`

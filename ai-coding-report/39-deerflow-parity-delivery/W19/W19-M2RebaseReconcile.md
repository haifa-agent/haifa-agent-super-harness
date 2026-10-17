# W19-M2RebaseReconcile — PR #70 rebase 到 post-W20 架构 开发报告

> 状态：本报告记录把 PR #70（M2 单实例后台派发）从旧 `DeerFlowThreadRunApi` 架构重放到 W20 抽取后架构的过程、迁移位置、断言与证据。任务状态、门禁结果与阶段决策以 SHPM 为准，本报告不预填阶段 `PASS`。
> 里程碑：M2 返工（rebase-reconcile）。分支 / worktree：`feat-w19-background-dispatch`（本目录）。冻结 DF：`e545c28ac301e6f9feae247c513d8ea8f83df294`。
> 只影响 `super-harness-server` 的调度包、共享会话服务与 Bootstrap 装配；本报告不预填阶段 `PASS`。

## 1. 背景与目标

- W20（#65/#71/#73）把 `DeerFlowThreadRunApi.java` 从 5000+ 行抽取为 458 行的 HTTP 适配器，产品用例迁到 `api/DeerFlowConversationService.java`（共享给 Web 与 Feishu）与 `run/` 接缝；#68/#69 也已在 main。
- M2 旧实现把 `ScheduledRunLauncher` 实现在 `DeerFlowThreadRunApi` 上（因为当时所有 Thread/Run/Bridge 状态都在该文件）。rebase 到新架构后，该文件不再持有任何 Thread/Run/Bridge 状态，必须把 M2 逻辑重落到 `DeerFlowConversationService`。
- 目标：保留 M2 全部功能与 PR #70 的 9 条 review 修复语义（旧返工提交 `35a3235`），不文本式硬合并旧文件，不回退 W20，不复制共享用例。

## 2. rebase 方式与冲突点

- 旧分支：`35a3235`（M2 返工）叠在旧 M1 提交 `c557b51..f90fda1` 之上；这些 M1 提交已作为 #69 以 squash 提交进入 main。
- 第一次重放：`git rebase --onto origin/main c557b51 feat-w19-background-dispatch`，当时 `origin/main=15ba6db`（#68/#69 + W20 #71/#65），只重放 M2 三提交 `b1c76ea`、`109f93d`、`35a3235`；旧 M1 提交因已 squash 进 main 被跳过。产出的 M2 提交：`25f4fa2`、`60a5619`、`a79e6c4`。
- **并行合入（冲突感知）**：重放期间 `origin/main` 前进到 `616899d`（W20 Phase 2 #73）。第一次 rebase 的结果相对新 main 会“删除” Phase 2 的 Feishu 文件（等于回退 W20），因此把 M2 三提交再 `git rebase --onto origin/main 15ba6db feat-w19-background-dispatch` 到 `616899d`。最终 M2 提交：`4afb6e3`、`b6b9554`、`cec8382`（tip）。第二次 rebase 无冲突（#73 对 `DeerFlowConversationService` 的 25 行改动与 M2 增补自动合并）。
- 冲突点（唯一文件冲突，首次重放时）：`api/DeerFlowThreadRunApi.java` 在 `b1c76ea`、`35a3235` 两次冲突。
  - 处置：该文件整体取 `origin/main` 版本（458 行，抽取后的适配器），不把 5000+ 行搬回；M2 的 `DeerFlowThreadRunApi` 改动全部改落在 `DeerFlowConversationService`。
- 非冲突文件（`ScheduleStore`、`ScheduleCalculator`、`ScheduleDispatchService`、`ScheduledRunLauncher`、`W19*Test`）按 M2/返工原始改动重放；`DeerFlowBootstrapApi` 由 git 自动合并后人工修正装配目标（见 §3）。
- 未回退 W20：最终树中 `DeerFlowThreadRunApi.java` 与 `origin/main` 逐字节相同（无 diff，458 行）；`git diff origin/main --stat` 只含 M2 文件；W20 共享用例 `DeerFlowSharedConversationServiceHttpTest` 及 Phase 1 run/interaction/history 回归在 rebase 后 PASS。

## 3. 迁移位置（旧 → 新）

| M2 旧位置（`DeerFlowThreadRunApi`） | 新位置 | 说明 |
| --- | --- | --- |
| `implements ScheduledRunLauncher` | `DeerFlowConversationService implements AutoCloseable, ScheduledRunLauncher` | 唯一持有 Thread/Run/Bridge 状态的产品层 |
| `launch/ensureBackgroundThread/nonInteractiveTools/newBackgroundBridge` | `DeerFlowConversationService` 同名逻辑 | 复用既有 `runModelResolver`/`agentPromptResolver`/`toolsForThread`/`persistThread`/`childRunCapacity`/`hostShellConfig`/`executionExecutorFactory` |
| `watch`（有界 follower） | `DeerFlowConversationService.watch(owner,thread,run,cb)` | `followedRuns` 幂等、`bridgeForPersistedThread` 重挂、瞬态快照重试 |
| `inspect` | `DeerFlowConversationService.inspect(...)` | `findRun` 定位；瞬态失败抛 `InspectionUnavailableException`（fail-closed） |
| `activeRunInFlight` | `DeerFlowConversationService.activeRunInFlight` | 复用 `restoreDurableAdmission`/`findOrRestoreRun` |
| `backgroundBridges/backgroundFollowers/followedRuns` 字段；`close()` 中断/join follower | `DeerFlowConversationService` | 关闭路径有界中断/join background followers |
| Bootstrap `new ScheduleDispatchService(store, threadRunApi, ...)` | `new ScheduleDispatchService(store, threadRunApi.conversations(), ...)` | 派发接缝指向共享会话服务；HTTP 适配器与 Feishu bridge 共用同一实例 |

- 唯一实现：`ScheduledRunLauncher` 仅由 `DeerFlowConversationService` 实现（产品侧），测试用 `ScheduleDispatchServiceTest.FakeLauncher`；没有第二份 Run/Loop 逻辑，没有把抽取逻辑复制回 `DeerFlowThreadRunApi`。

## 4. 命令与证据

- 定向测试（绑定最终候选 `cec8382`）：
  - `pwsh -NoProfile -File scripts/test-targeted.ps1 -Test 'ScheduleDispatchServiceTest,W19BackgroundDispatchHttpTest,DeerFlowSharedConversationServiceHttpTest,DeerFlowThreadRunApiTest,DeerFlowInteractionHttpApiTest' -LowMemory` → `status=PASS`，59 通过 / 0 失败 / 0 错误 / 0 跳过，`elapsedMs≈126022`，日志 `local-tmp/test-targeted/test-targeted-20261017-204759-913.log`。
  - （第一次重放到 `a79e6c4` 时的等价定向：`ScheduleDispatchServiceTest` 14/0/0；`W19BackgroundDispatchHttpTest,DeerFlowSharedConversationServiceHttpTest` 9/0/0；`DeerFlowThreadRunApiTest,DeerFlowInteractionHttpApiTest,DeerFlowThreadHistoryApiTest` 44/0/0；日志 `...-202343-507.log`、`...-202425-656.log`、`...-202547-349.log`。）
- 全仓门禁（绑定最终候选 `cec8382`）：
  - `HAIFA_SUPER_HARNESS_SKILL_PYTHON=D:/agents/deer-flow/backend/.venv/Scripts/python.exe HAIFA_SUPER_HARNESS_W05_TEST_GATEWAY_PORT=22618 pwsh -NoProfile -File scripts/verify-full.ps1`
  - 结果：`status=PASS`，893 通过 / 0 失败 / 0 错误 / 21 跳过，`elapsedMs≈421217`。
  - 绑定：`sourceSha=cec8382a49d9674152951b8a513bc2eefb2f47aa`、`sourceTree=0ec9bd90e1b6c9f708a9e4e91ecf85d07fa9ce04`、`javaInputFingerprint=b8aba47466193bcdc0ca0a2aff2089375e77bdc6c527aded819cda756d06b53f`、`sdkIdentity=c1cc9b0c...`、`sdkSelection=ABSENT`、`haifaVersion=0.1.3-SNAPSHOT`。
  - 摘要：`local-tmp/verify-full/verify-full-20261017-211046-350.json`；日志同名 `.log`。原件另存 `local-tmp/slot-6-w19-m2reconcile/evidence/`。
- 门禁范围：`python scripts/governance/change_scope.py` → `gates=["docs-check","verify-full","ui-live-round-x1"]`，`uiModel=live`，`acceptanceTier.dailyPr=L1`（原件 `local-tmp/slot-6-w19-m2reconcile/evidence/change-scope.json`）。

## 5. 首个 FAIL（保留）

1. **首次重放冲突（保留）**：`DeerFlowThreadRunApi.java` 在 `b1c76ea`/`35a3235` 两处产生内容冲突（旧 5372 行上下文 vs 458 行新文件）。按 §2 处置（整文件取新架构，M2 逻辑迁到 `DeerFlowConversationService`），未使用 `-X ours/theirs` 吞冲突、未 `--skip`。`DeerFlowBootstrapApi` 自动合并后仍指向 `threadRunApi` 作为 launcher（不编译），人工修正为 `threadRunApi.conversations()`。
2. **并行合入回退风险（保留）**：第一次 rebase（到 `15ba6db`）后 `git diff origin/main` 显示 Feishu Phase 2 文件被删除——若直接推送会在合入时回退 #73。已按 §2 第二次 rebase 到 `616899d` 消除；最终 `git diff origin/main` 只含 M2 文件。
3. **verify-full 瞬态失败（保留原件）**：在未提交文档的树上有一次 `clean verify` 因 `DeerFlowSubagentCatalogApiTest` 报 `Port already in use ... 30618` 而产生 6 个 error（前一次 pwsh 崩溃留下的监听残留）；端口释放后同树重跑 PASS。失败原件 `local-tmp/verify-full/verify-full-20261017-210237-901.json` 另存 `local-tmp/slot-6-w19-m2reconcile/evidence/`。这不是产品缺陷，未放宽任何断言。

## 6. 未运行项（NOT_RUN）

- `ui-live-round-x1`：本改动未接线任何新 REST/UI 路由（REST 属 M3），无冻结 UI 表面可跑；记 `NOT_RUN`，需主控裁决。
- `docs-check`：仓库内没有该门禁的可执行 runner（仅在 `change_scope`/`test_pipeline_tools` 中出现名称），按其含义补齐文档卫生（本报告 + 更正 `W19-BackgroundDispatch.md` 旧位置引用 + `W19-M2ReviewFix.md` 位置注记），独立 runner 记 `NOT_RUN`。
- L2 冻结前端双后端差分：工作包出口由主控攒批，记 `NOT_RUN`。
- W19-M2-09 多实例/跨实例接管：本期不支持，`NOT_RUN`。
- 慢测试（`@Tag("slow")`）：未新增、未运行。

## 7. 交接

- 最终代码候选 SHA：`cec8382a49d9674152951b8a513bc2eefb2f47aa`（`sourceTree=0ec9bd90...`、`javaInputFingerprint=b8aba474...`）；`verify-full` 在其上 PASS。其后的提交只改本报告与两处文档文字，Java 输入指纹不变。
- 建议：`git push --force-with-lease origin feat-w19-background-dispatch` 更新 PR #70；不改写 W20 提交（`origin/main=616899d` 未触碰）。
- SDK：`local-tmp/sdk-selection.json` ABSENT → 基线 `io.haifa:haifa-agent-bom:0.1.3-SNAPSHOT`；未使用带 worktree 标识版本。
- 原始证据：`local-tmp/slot-6-w19-m2reconcile/evidence/`（verify-full PASS 摘要/日志、verify-full 瞬态失败摘要、定向测试日志、change-scope）。
- 未写 SHPM。

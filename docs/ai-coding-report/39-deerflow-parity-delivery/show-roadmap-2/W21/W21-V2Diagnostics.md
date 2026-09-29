# W21 V2 权威事实诊断投影（执行中）

> 本报告记录 W21 的 V2 只读切片。`main@14e516b` 的定向 L2、全仓 `clean verify`、合同/证据门禁与冻结 UI 冷暖两轮已运行；主控独立验收尚未完成。V2-M4 阶段仍以 SHPM 主控判定为准，不得据此自行提升阶段或 W21 工作包状态。

## 主控收尾（2026-09-29 23:43 CST）

- 主控复审后将交付提交 `fd86e2a`、`2a21893` 依次 cherry-pick 到 SH `main@390673f`、`14e8c05`，并推送 `origin/main`。合入时单独保存并恢复主工作树原有的 `README.md` 未提交修改；`AGENTS.md` 和未跟踪诊断测试也未纳入这两次提交。W21 产品 JAR SHA-256 `6a69729c562a428ada70e67da80b90d87d646819d787f27537b45cb186348fb4` 与已验门禁的工作树构建一致。
- 主控按 18018 端口精确核对原 Gateway PID 19688 的 Super Harness JAR 命令行，备份原 JAR 至 `local-tmp/main-jar-backup-20260929-233956/`，替换后用 `scripts/start-main-super-harness.ps1 -GatewayOnly` 重启为 PID 48504。`/health`、`/api/v1/auth/me`、`/api/mcp/config` 与前端 `33018/` 均返回 HTTP 200；未知 Thread 的 `/token-usage` 和 Run 详情均返回预期 404。主控为加快收尾，未在合入后重复已在相同产品代码和最终 spec 上完成的 W21 双轮或全仓 `clean verify`；主端口检查只是 smoke，不替代完整 V2-M4 退出门禁。
- SHPM 最终 spec 冷/暖证据为 `ver-ae5c26d3`、`ver-5ede34ac`；跨 owner 隔离登记 `ver-98348515` 为 `NOT_RUN`。`W21-task-1` 交付为 `ready_for_acceptance`，assignment 已释放，留待人工验收；V2-M4 保持 `NOT_RUN`，总体仍 `NO_GO`。下文“尚未合入 main”和“主控复审尚未完成”描述的是子智能体交付当时状态，以本段收尾状态为准。

## 工作范围与来源

- 分支：`codex/w21-v2-diagnostics`；复用独立 W05 管理工作树，起点 SH main `09549e160b070b579d60759bb447945664749cc0`。2026-09-29 已将 W21 未提交变更安全保存后 rebase 到 `main@14e516b8f9c0e0f900b522c2ded0988b0fc492ac`（包含 W16 连续 Interaction 展示修复）；W21 12 路径产品、测试与报告首提交为 `fd86e2a9c1392fe1b7f77152bc63597aa39102fd`，尚未合入 main。rebase 备份为 `local-tmp/slot-3-w21/evidence/pre-rebase-main14e516b/`，含 binary patch、9 文件 SHA manifest 与独立 Git stash commit；恢复无冲突，W16 产品/测试 diff 保留。
- SHPM 派工：原 `asg-W21-task-1-diagnostics`，主线恢复 assignment `asg-W21-task-1-main14e516b-resume`，映射 `DEMO-V2/V2-M4`；阶段和任务状态仅由主控更新。
- 冻结 DeerFlow：`bytedance/deer-flow@e545c28ac301e6f9feae247c513d8ea8f83df294`。本工作树 `local-tmp/slot-3-w21/context/` 的 10 份只读材料与 SHA manifest 已核对，未修改冻结源码。
- W24 evidence gate 所需、SHPM 已登记的 28 份 ignored `private-docs/` 材料由主控逐件 SHA 核对后复制到本工作树同相对路径；清单 `local-tmp/slot-3-w21/context/shpm-registered-private-docs-copy-manifest.json`，mismatch=0。仅用于门禁，未改 tracked 文件。
- SDK：本地 Maven 仓库公开 `0.1.2-SNAPSHOT` JAR；事实签名的只读 `javap` 输出在 `local-tmp/slot-3-w21/evidence/sdk-public-api-javap.txt`。
- 字段来源矩阵：`local-tmp/slot-3-w21/evidence/field-source-matrix.md`；SDK 事实 spike：`diagnostics-spike-decision.md`；Oracle 到断言和原始证据映射：`oracle-assertion-plan.md`。这些文件均位于本工作树外置证据目录，不是测试 PASS。

## 变更与边界

- `DeerFlowThreadRunApi` 复用 `/api/threads/{id}/runs/{runId}` 和冻结 UI 已请求的 `/api/threads/{id}/token-usage`。前者增加只读 `diagnostics`，后者仅在同 Thread 的已结束 Run 都有完整可验证 Usage 时返回总数，否则返回 JSON `null`。
- `RunDiagnosticProjection` 只从公开 SDK `AgentRunSnapshot`、durable `AgentRunEvent`、`ToolCallView`、`InteractionView`、`ArtifactRef` 投影稳定 ID、状态、代码、Usage 可得性和时间。ToolCall 的实际 MCP/Native 来源、旧 Run 完整模型配置 digest、Run attempt 无公开持久事实时为 `null`。Artifact 优先取 SDK `ArtifactRef`；生产 `write_file` 成功但 SDK 无 ArtifactRef 时，核对同 Run、同 ToolCall、Artifact ID、规范 `/mnt/user-data/` path 与 SHA-256 后，仅从该 Tool 的结构化结果只读回填，并按 Artifact ID 去重。新增 `diagnostics` 对象不回显错误文本、prompt、凭据或文件字节；原有 Run 输入字段仍按既有授权语义返回。
- SDK `ModelLifecycle.modelId` 记录的是 Provider 模型 ID（如 `demo-provider-id`），不是 SH 管理配置显示名（如 `demo-model`）。跨重启仅以该公开持久 ID 判模型切换；显示名和完整配置 digest 无 Run 级公开事实时保持未知，不由当前 Thread 配置回填。
- Run 列表只返回 SDK snapshot 摘要，不扫每条 Run 事件。单 Run 明细最多 20×100 事件，超限保留部分事实并标 `projection_complete=false`、Usage unknown。Thread Usage 整次查询最多 50 页，历史截断或未核实完整度时返回 JSON `null`。`by_model` 用 Provider 长度前缀加 Model ID，避免跨 Provider 同名 Model 误聚合。
- 不新增 Run/Interaction/Artifact 权威、持久化事件库、平行 UI、账号管理或上游 SDK PR。

## 旧基线定向记录（2026-09-29 21:30 CST；保留历史）

| 检查 | 当前结果 | 证据 |
| --- | --- | --- |
| `V2-M4-06` 成功/reject/cancel/MCP failure | L2 定向 `PASS`，阶段仍 `NOT_RUN` | 四结局 4/4、0 skipped：`local-tmp/slot-3-w21/evidence/targeted-four-outcomes-pass-20260929-2128.log` 与 `targeted-four-outcomes-pass-surefire/`；真实 MCP 断线 1/1、0 skipped：`targeted-mcp-disconnect-first-20260929-2129.log`、`targeted-mcp-disconnect-pass-surefire/`、`mcp-fixture-20260929-2129/`。断线后 ToolCall `FAILED`、code `TOOL_OUTCOME_UNKNOWN`，HTTP fixture 的 call/disconnect 均为 1；恢复 SSE SHA-256 `97c4a270802bfa0f3a208b09621c2ffd7d84efa38dbeb3e656a2752450ee2593`。生产 Artifact 下载字节与 SHA-256 的反证在 `targeted-production-artifact-rerun-20260929-2126.log`。 |
| `V2-M4-06-N1` 同 owner 跨 Thread Run ID 404 | L2 定向 `PASS`，阶段仍 `NOT_RUN` | `DeerFlowConfigurationApiTest.savedManagedModelDrivesRealSdkRunAndToolCallProbeAgainstControlledProvider` 在已存在的 `managed-run` Run ID 上，以另一个真实 Thread `unrelated-thread` 请求 `/runs/{id}` 并断言 404；证据同四结局定向日志。该断言不替代已延期跨身份用例。 |
| `V2-M4-07` Provider 省略 Usage 与显式 0 | L2 定向 `PASS`，阶段仍 `NOT_RUN` | 1/1、0 skipped：`targeted-usage-rerun-20260929-2122.log` 与 `targeted-usage-pass-surefire/`。原始受控 Provider SSE 帧在 Surefire stdout；省略 Usage SHA-256 `60efc5529d1f5621ba9bb46a66927865681448648cb76ab4a9ed867ad4b4569a`，SDK `PARTIAL_RESPONSE`、Usage `unknown/null`；显式 0 SHA-256 `5c2da9e2fc4c234e346a7fa39c208873647827351af7e0009554636c95caf0c2`，Usage `known/0`。 |
| Reactor 定向、全仓 `clean verify`、合同/证据门禁 | 旧基线当时仅指定定向 `PASS` | 该行保留 21:30 CST 的历史状态；当前主线全仓及门禁结果见下节。 |
| 冻结 UI 同脚本两轮、真实服务 smoke | `NOT_RUN` | 待端口和 Provider 46618 协调。 |
| `git diff --check` | `PASS`（静态、非产品验收） | 2026-09-29 21:31 CST，exit 0；仅 Windows CRLF 警告。 |
| 受界 secret scan | `PASS`（静态、非产品验收） | W21 9 个变更源码、测试、README、报告路径的高置信密钥模式零命中；`local-tmp/slot-3-w21/evidence/scoped-secret-scan-20260929-2132.json`。 |

首次 FAIL 未抹除：编译错误 `InteractionKind.name()`（`targeted-usage-first-20260929-2118.log`）；省略 Usage 的 SDK 原始失败语义为 `PARTIAL_RESPONSE`，初次断言不符（`targeted-usage-rerun-20260929-2120.log` 与 `targeted-usage-first-assertion-surefire/`）；四结局初次将 Provider 模型 ID 误当 SH 显示名（`targeted-four-outcomes-first-20260929-2123.log` 与 Surefire 副本）；第二次发现生产 `write_file` 的 SDK ArtifactRef 为空（`targeted-four-outcomes-rerun-20260929-2124.log` 与 Surefire 副本），经主控复审允许上述严格只读回填。原件均在同一外置 evidence 目录。其后的 Artifact ID 去重、path 限定、错误代码白名单和 rebase 后 W16 交互语义，已在下节基于 `main@14e516b` 重跑定向；旧 PASS 不作继承。

上述定向 Maven 命令统一设置 `MAVEN_OPTS=-Xmx512m -XX:+UseSerialGC`，并带 `-o -ntp -pl super-harness-server -am`、`'-Dsurefire.failIfNoSpecifiedTests=false'`、`'-DargLine=-Xmx768m -XX:+UseSerialGC'` 和末尾 `test`：

- Usage 三次运行的 `-Dtest` 均为 `DeerFlowConfigurationApiTest#omittedProviderUsageRemainsUnknownWhileExplicitZeroIsKnown`，前两次 exit 1，第三次 exit 0。
- 四结局两次失败、一次通过的 `-Dtest` 均为 `DeerFlowConfigurationApiTest#savedManagedModelDrivesRealSdkRunAndToolCallProbeAgainstControlledProvider,DeerFlowInteractionHttpApiTest#frozenCardRejectionResumesWithoutExecutingTheTool,DeerFlowThreadRunApiTest#cancelCallsHaifaHandleAndSecondCancelConflicts,ProductionWriteFileHttpTest#productionApprovalGateRejectsMismatchedCrossThreadDuplicateAndRejectedWrites`，exit 顺序为 1、1、0。
- 生产 Artifact 单项的 `-Dtest` 为 `ProductionWriteFileHttpTest#productionApprovalGateRejectsMismatchedCrossThreadDuplicateAndRejectedWrites`，exit 0。
- 真 MCP 断线的 `-Dtest` 为 `V2McpTransportIntegrationTest#disconnectedMcpResultRetainsFailedToolCallAndRunIdentity`，另带 `'-Dv2.mcp.reporting.url=http://127.0.0.1:40628/mcp'`、`'-Dv2.mcp.region.url=http://127.0.0.1:40629/mcp'`、`'-Dv2.mcp.negative.controls=true'`，exit 0。fixture 由 `pwsh -NoProfile -File scripts/start-v2-test-mcp-servers.ps1 -ReportingPort 40628 -RegionPort 40629 -EvidenceDir local-tmp/slot-3-w21/evidence/mcp-fixture-20260929-2129 -EnableNegativeControls` 启动，在同一次调用的 `finally` 用同脚本 `-EvidenceDir ... -Stop` 停止。

旧基线真 MCP fixture PID 47080/50156 由启动脚本 manifest 精确停止，当时 40618/45618/40628/40629/46618 端口均空闲，W21 worktree 无 Maven/Java 进程；start/stop 日志在 `targeted-mcp-fixture-*-20260929-2129.log`。旧基线当时未运行冷暖 UI、全仓门禁或真实服务 smoke；当前主线全仓结果见下节。最终 commit 和主控独立复审待后续。

## `main@14e516b` 恢复后的定向回归（2026-09-29 22:29 CST）

复用槽位 3：Gateway `40618`、Frontend `45618`、真 MCP fixture `40628/40629`；共享 Provider `46618` 当前由 W16 占用，不抢占。独立 data-root 为 `local-tmp/slot-3-w21/data-resume-14e516b`。运行前读取 `Get-CimInstance Win32_OperatingSystem` 的 `FreePhysicalMemory` 并换算 GiB，须超过 4 GiB；用 `Get-CimInstance Win32_Process` 检查无其它 Maven/Java Maven Launcher，保留 PID 与命令行所属工作树证据，不停止他人进程。再运行 `netsh interface ipv4 show dynamicport tcp`、`netsh interface ipv4 show excludedportrange protocol=tcp`，并对 `40618/45618/40628/40629/46618` 逐端口用 loopback `TcpListener.Start()` 实绑预检；失败即停止本轮并报主控。

主控放行后重新实测 `FreePhysical=6.39 GiB`、无 Maven Launcher；第一条定向命令（包括 W16 的连续两次 Approval、Gateway 重启、持久化失败全类回归，以及 W21 Model/Usage/cancel/Artifact）为：

```powershell
$env:MAVEN_OPTS='-Xmx512m -XX:+UseSerialGC'
mvn -o -ntp -pl super-harness-server -am '-Dtest=DeerFlowInteractionHttpApiTest,DeerFlowConfigurationApiTest,DeerFlowThreadRunApiTest,ProductionWriteFileHttpTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx768m -XX:+UseSerialGC' test
```

结果：66/66、fail 0、error 0、skipped 0，exit 0，2 分 53 秒。`DeerFlowInteractionHttpApiTest` 9/9 包含 W16 连续审批、重启和持久化故障；`DeerFlowConfigurationApiTest` 13/13、`DeerFlowThreadRunApiTest` 43/43、`ProductionWriteFileHttpTest` 1/1。原始日志 `local-tmp/slot-3-w21/evidence/targeted-four-classes-main14e516b-first.log`，四类 Surefire 外置 `targeted-four-classes-main14e516b-pass-surefire/`。该轮无失败，不覆盖旧 SHA 首次 FAIL 历史。

真 MCP 断线在同一次 PowerShell 调用内启动/测试/`finally` 停止 fixture，并把 start、Maven、stop 原始日志及 Surefire 副本写入独立外置 evidence；fixture 启动参数 `-ReportingPort 40628 -RegionPort 40629 -EvidenceDir local-tmp/slot-3-w21/evidence/mcp-fixture-resume-14e516b -EnableNegativeControls`，定向命令沿用上文 `V2McpTransportIntegrationTest#disconnectedMcpResultRetainsFailedToolCallAndRunIdentity`，且显式传两 URL 与 `-Dv2.mcp.negative.controls=true`。结果 1/1、fail 0、error 0、skipped 0、exit 0；真实 HTTP fixture `get_status_calls=1`、`disconnects_after_call=1`，ToolCall `FAILED`、code `TOOL_OUTCOME_UNKNOWN`，恢复 SSE SHA-256 `2a9686a87c7c90516b3c80d9841aa46439a4fdff4f91bb4d4e0c90e2baeecf69`。原始日志 `targeted-mcp-disconnect-main14e516b-first.log`、Surefire `targeted-mcp-disconnect-main14e516b-pass-surefire/`、fixture 原件 `mcp-fixture-resume-14e516b/` 和 start/stop 日志均在同一外置 evidence。fixture PID 31664/38968 已精确停止，40618/45618/40628/40629/46618 均空闲，W21 Java 进程 0。

该定向轮结束时曾暂缓全仓 `clean verify`，先给 W16 短 L2 探针窗口；随后已完成下节所列全仓门禁。该句只记录当时执行顺序，后续合同/证据门禁与冻结 UI 结果见文末。此前旧 SHA 的 L2 PASS 仅作历史证据；本轮 L2 PASS 亦不提升 V2-M4 阶段。

## 全仓构建与当前门禁（2026-09-29 22:40 CST）

在再次核对外置 targeted 日志、Surefire、MCP fixture 原件完整且 `FreePhysical=6.78 GiB`、其它 Maven Launcher 为 0 后，执行 `MAVEN_OPTS='-Xmx512m -XX:+UseSerialGC'` 的 `mvn -o -ntp clean verify '-DargLine=-Xmx768m -XX:+UseSerialGC'`，exit 0、`BUILD SUCCESS`、耗时 6 分 22 秒。原始完整 stdout/stderr 为 `local-tmp/slot-3-w21/evidence/full-clean-verify-main14e516b-first.log`；各模块 Surefire 原件复制到 `full-clean-verify-main14e516b-first/<module>-surefire/`，汇总 `summary.json` 与 `skipped-tests.json` 同目录。

Surefire 合计 **250 tests、0 failure、0 error、11 skipped**：app 3/0 skip、events 7/0、workspace 34/1、server 201/10、harness 5/0。11 个跳过项均保留：workspace symlink 条件 1、DeepSeek live 1、Interaction acceptance server 1、需独立 MCP fixture 的 8；其中 W21 真实 MCP 断线已在上节独立定向 1/1 执行，不能用全仓的跳过项冒充全量 MCP 通过。可执行 JAR `super-harness-server/target/super-harness-server-0.1.0-SNAPSHOT-exec.jar`，26,572,907 bytes，SHA-256 `6a69729c562a428ada70e67da80b90d87d646819d787f27537b45cb186348fb4`。

`git diff --check` exit 0，受界 9 路径高置信 secret scan 零命中，记录 `local-tmp/slot-3-w21/evidence/scoped-secret-scan-after-full-main14e516b.json`。该时点 W21 的 40618/45618/40628/40629 均空闲、无其 worktree Java/Node/Maven 进程；共享 `46618` 当时由 W16 短模型探针监听 PID 41852，未触碰。随后合同/证据门禁与冻结 UI 双轮结果见下节；主控继续负责 SHPM 和 V2-M4 判定。

冻结 UI 前置已补齐：本工作树 `frontend/` 用 corepack `pnpm 10.26.2` 执行 `install --offline --frozen-lockfile`，1088 packages、`downloaded 0`，`next.cmd`/`playwright.cmd` 与缓存 Chromium 均存在。原始安装日志 `local-tmp/slot-3-w21/evidence/frontend-pnpm-install-offline-main14e516b-first.log`。主控只读核对冻结 `D:\workspace\haifa-agent-super-harness\deer-flow` 的 HEAD 为 `e545c28`、status clean，并批准用作 UI runner 的 `-SourceRepository`；W21 未修改冻结源码。

## 合同、证据及冻结 UI 冷暖两轮（2026-09-29 23:34 CST）

主控按 W00 source-index 从冻结 SHA 复制 23 个文件到本工作树 `local-tmp/slot-1-w00-v2-oracle/upstream-snapshot/`，逐文件 SHA 复核，清单 `local-tmp/slot-3-w21/evidence/controller-frozen-snapshot-copy.json`。`tests/customer-demo-v2/verify-v2-contract-oracle.ps1` 在该快照上 **3698 assertions、0 skipped、exit 0**，原始日志 `v2-oracle-gate-main14e516b-with-snapshot.log`；首次无快照运行的 163 skipped 结果仍保留在 `v2-oracle-gate-main14e516b-first.log`，不冒充当时通过。`tests/evidence/verify-evidence-report.ps1` **101 assertions、exit 0**，原始日志 `evidence-gate-main14e516b-first.log`。`scripts/verify-deerflow-contracts.ps1 -DeerFlowRoot D:\workspace\haifa-agent-super-harness\deer-flow` exit 0、冻结 SHA 精确匹配，原始日志 `deerflow-contract-gate-main14e516b-first.log`；其自身仍报告 runtime `NOT_RUN`、core completion `PARTIAL`，本轮冻结 UI 诊断仅覆盖 W21 指定断言，不提升完整协议覆盖。

新增 `scripts/run-w18-ui-smoke.ps1 -V2Diagnostics` 受界 profile、`tests/w18-ui-smoke/w21-v2-diagnostics.spec.cjs` 和受控 Provider `w21-omit-usage-*` 真 SSE 省略帧。测试让冻结页面发起 Run、批准生产 `write_file`，按同一 Run 核对 SDK status、ModelCall 的 `provider_id=super-harness-managed-openai-compatible` / `model_id=w18-controlled-model`、ToolCall、Interaction request ID、Artifact ID/path/SHA，并下载真实字节复算 SHA；页面真实请求 `/token-usage`，HTTP 返回已知 16 tokens。第二 case 的 Provider 确实返回无 Usage 帧，Run `error` / SDK `FAILED` / Run 错误码 `MODEL_RESPONSE_INVALID` / ModelCall `PARTIAL_RESPONSE`，Run 和 `/token-usage` 均是 `unknown/null`，不能显示伪 0。冻结 UI 当前关闭可见 token badge，故未把 badge 当可见 PASS。最终两轮脚本文件 SHA-256：runner `FBB87FE5B3DDCA7F31C75562AB3D6A9C811E6E1D9639FF2A7869D0A0104AC632`；spec `17DC0BCFDBF6682D344D77CD332C3502C4ABA0EF059BBADCF857709CB31CAB5F`；Provider `29EFA0ADD5520EFC49225406D5A161CA4299CC83B4E2C7A2D82051B205EFEA51`。

首对冷暖轮使用 spec SHA `03396D6D9F03D919647FE6F8D0CFDBF2033190FDDB99BD0B696B94D9AF14EDB2`，经 trace 复审发现负例虽核对 ModelCall `PARTIAL_RESPONSE`，但未精确断言 Run 层错误码；主控要求补 `MODEL_RESPONSE_INVALID` 断言后重跑。因此以下首对结果仅是**历史 PASS**，不能代表最终 spec。冷轮命令 `pwsh -NoProfile -File scripts/run-w18-ui-smoke.ps1 -AgentSlot 3 -GatewayPort 40618 -FrontendPort 45618 -V2Diagnostics -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow -DataDirectory local-tmp/slot-3-w21/data-resume-14e516b/ui-cold`，暖轮只改 data-root 为 `.../ui-warm` 并加 `-SkipBuild`。运行前空闲物理内存 7.29 GiB、无 Maven，40618/45618/40628/40629/46618 全组 bind PASS；冷轮 Maven package BUILD SUCCESS 用时 6:33，Next 编译 64 秒、TypeScript 68 秒、静态页 4.2 秒。冷轮从启动 23:05:26 到 Playwright 开始 23:15:06 约 9:40；Next 服务报告 Ready in 461ms，生产构建无动态编译。Playwright **2/2 PASS、0 retry、0 skipped、18.8 秒**（正向 14.1 秒、负例 2.6 秒），Run ID `01a0edbb-e2cd-75c7-8d84-ed342164761b` / `01a0edbc-1a39-730e-9fc8-23c504979a09`，生产 Artifact `81a6c5bd-16de-4318-a87a-ba9563ac4835`，下载 SHA-256 `e9d37d3745c635552b1e8055c768da1182177e5a47093545037d5caf09cafaae`。原始外层日志 `ui-cold-main14e516b-first.log`，run 目录 `ui/run-20260929-230526/` 含 Playwright `results.json`、两份 `trace.zip`、每 case JSON、Gateway/Frontend/Provider 原始日志与 profile；PASS 未生成 screenshot，Playwright 配置仅失败时截图。

暖轮使用相同源码、脚本、前端构建代理目标 `40618`，独立 data-root，启动到 Playwright 约 40 秒；Next Ready in 461ms、无需动态编译。Playwright **2/2 PASS、0 retry、0 skipped、24.1 秒**（正向 16.9 秒、负例 4.9 秒），Run ID `01a0edbd-2705-7d8a-a00c-94defb729cb7` / `01a0edbd-6198-7535-a9c7-8861f1ef3c09`，Artifact `b5cb00d2-d249-40e5-a198-a426f5953434`，下载 SHA-256 `2c30abef793bbea5910270b535d40c129e1f334e215c0c11899a048e8d4193b2`。原始外层日志 `ui-warm-main14e516b-first.log`，run 目录 `ui/run-20260929-231547/` 同样保留两份 trace、JSON 和服务日志；PASS 未生成 screenshot。两轮首次卡点均无、无重试；每轮结束经 TcpListener 实绑确认五个端口释放，W21 Java/Node/Maven 残留进程为 0。Provider 原始日志各记录 `w21_usage_omitted`，L2 的完整原始 SSE 帧与 SHA 在前述 Surefire 日志。

| 轮次 | 准备/构建 | 服务就绪 | 功能断言 | 证据归档与复审 | 首次超时/返工 |
| --- | --- | --- | --- | --- | --- |
| cold | 23:05:26 开始；Maven 6:33，Next 约 2:27，至 Provider 启动约 9:33 | Provider/Gateway/Frontend 约 23:15:00–03；Next Ready 461ms，Playwright 23:15:06 开始 | 18.8s，2/2、retry 0 | `results.json` 和 trace 至 23:15:24 写入外置目录；随后核对 Run/下载 SHA、端口与脚本哈希 | 无超时；无返工 |
| warm | 23:15:47 开始；`-SkipBuild` 复用同代理构建，独立 data-root，约 34s 至 Provider 启动 | Provider/Gateway/Frontend 约 23:16:21–24；Next Ready 461ms，Playwright 23:16:27 开始 | 24.1s，2/2、retry 0 | `results.json` 和 trace 至 23:16:51 写入外置目录；随后核对 Run/下载 SHA、端口与脚本哈希 | 无超时；无返工 |

归档与复审时间为文件创建/核对时点，runner 未单独计时该阶段，不能据此报精确秒数。两轮动态编译均不适用：前端运行的是已验证生产 build，而不是 Next dev。

最终 spec SHA `17DC0BCFDBF6682D344D77CD332C3502C4ABA0EF059BBADCF857709CB31CAB5F` 的两轮重跑前，旧轮外置 raw/log/trace 共 36 文件逐一 SHA 记录在 `pre-error-code-assertion-ui-evidence-manifest.json`，未覆盖。新冷轮 data-root `.../ui-cold-errorcode`，暖轮 `.../ui-warm-errorcode -SkipBuild`，其余参数和 Gateway 代理目标与首对完全一致。新冷轮预检 FreePhysical 5.83 GiB、Maven 0、全端口 bind PASS；暖轮为 6.93 GiB、Maven 0、全端口 bind PASS。冷轮 Maven package BUILD SUCCESS 6:32，Next 编译 27.2 秒、TypeScript 43 秒、静态页 4.7 秒；自 23:23:18 启动至 23:32:21 Playwright 开始约 9:03，服务约 23:32:03–07 就绪。Playwright **2/2 PASS、0 retry、0 skipped、0 flaky、23.4 秒**（正向 16.2 秒、负例 4.4 秒）；Run `01a0edcb-afc5-733d-8cfe-67a9d8c542b1` / `01a0edcb-f0fb-7c90-98ea-b3b8f3d83006`，Artifact `09c971d9-9af7-4f87-8c0f-1e60d60bef95`，下载 SHA-256 `0fd2744304975143679d3d970ef956fe1ea23c8905efced2d288ff68c13ce76e`。原始外层日志 `ui-cold-errorcode-main14e516b-first.log`，原始目录 `ui/run-20260929-232318/`。

新暖轮自 23:33:09 启动至 23:33:47 Playwright 开始约 38 秒，Provider/Gateway/Frontend 约 23:33:41–45 就绪；**2/2 PASS、0 retry、0 skipped、0 flaky、24.0 秒**（正向 18.7 秒、负例 2.9 秒）。Run `01a0edcc-fdda-7329-8e2c-7b432acb3c5f` / `01a0edcd-48e6-7d99-a4f0-034dec0fd90b`，Artifact `d0134f26-9870-458f-9858-db928b3e7f72`，下载 SHA-256 `b68f2f8f24d0d2abcb25f98ff57066893a81b1eaa842c3fd5fe76eccce230ed3`。原始外层日志 `ui-warm-errorcode-main14e516b-first.log`，原始目录 `ui/run-20260929-233309/`。两轮正向 Usage 均为 16 tokens；省略 Usage 均为 `unknown/null`，负例 SDK Run `FAILED`、HTTP `error`、Run 错误码 `MODEL_RESPONSE_INVALID` 与 ModelCall 原因 `PARTIAL_RESPONSE` 同时通过。每个目录都有 `results.json`、两份 trace、逐 case JSON、Gateway/Frontend/Provider 日志；PASS 无 screenshot，Next 生产构建无动态编译。新两轮均无首次超时/卡点、无重试或返工；后端、前端和 Provider 进程随 runner 精确停止，全端口 40618/45618/40628/40629/46618 再次 bind PASS，W21 残留 Java/Node/Maven 进程为 0。

**保留范围与未运行项**：跨 owner 隔离仍 `NOT_RUN`（同 owner 跨 Thread 404 不可替代）；Tool 的实际 MCP/Native 来源、旧 Run 完整模型配置 digest、Run attempt 和精确起止时间仍为 `unknown/null`。本报告只交付 W21 子任务证据，主控独立复审与 V2-M4 阶段门禁尚未结算。

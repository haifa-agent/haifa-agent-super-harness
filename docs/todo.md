# 项目待办与上游 SDK 交接

本文件用中文维护。状态以实际合并、已发布的本地 JAR、Super Harness 验证证据分别判定；上游 PR 已合并不等于本仓产品工作包完成。工作包状态的唯一数据源是根目录 `work-packages.js`。

## 当前需要推进

- [ ] W05 Interaction 收尾已提交 `codex/w05-message-identity-steer@3ab6d93`，派单依据为 `private-docs/prompts/39-deerflow-parity-delivery/W05-Developing/W05-InteractionClosure-Developing.md`。长会话 wire ID、重启 pending Interaction、Steer 游标预检/最后 human ID 幂等与 unknown 状态 fail-closed 均有定向证据；更新 HA SDK 后的分支全仓 `clean verify` 原样复跑 PASS（server 97 项、0 失败/错误、2 项有意跳过），合同 13、证据 41 PASS，修后 Interaction 与 ActiveReconnect 各冷/热双跑 PASS。首轮曾因 Windows `%TEMP%` AccessDenied FAIL，保留记录。主控仍须完成合入态门禁、主端口 smoke 和 W18 活跃重连差分复验；生产 Tool、真实模型及非 approval/多 Interaction 仍 `NOT_RUN`，W05 整包不关单。
- [ ] HA `0.1.2-SNAPSHOT` 本地基线已从含 #249/#250/#251 的干净 `origin/dev@c0e2c1c6` 独立 worktree 发布：L3 `clean verify` 2207 项 PASS，L2 公开 JAR/BOM `install` 1202 项 PASS；本地 SDK/starter/SQLite JAR SHA-256 与该 worktree 的 `target` 产物一致，SQLite `javap` 显示公开 `memory(ProductMemoryPolicy)`。本仓 clean-consumer、W08 公共 JAR 双 Child smoke 与更新后主线 clean gate 仍待复验；避免与其他进程并发覆盖同一快照。
- [ ] W18 双后端差分正在 `codex/w18-dual-backend-differential` 隔离 worktree 执行，派单依据为 `private-docs/prompts/39-deerflow-parity-delivery/W18-Developing/W18-DualBackendDifferential-Developing.md`。同一冻结 UI/controlled provider 下，DeerFlow 参考侧连续两轮 4/4 PASS；当前 Haifa main@045e8e0 连续两轮 3/4，active reload/join 后同组消息重复渲染两次（期望一次），Run success、GET join 200、SSE `messages,end` 均正常，失败截图/trace/JSON 已保留，待 W05 最终 SHA 合入后复验。首次 Haifa `package` 的 Windows `%TEMP%` `AccessDeniedException` 已保留为失败记录，精确定向复跑 1/1 与随后完整 `package`（Workspace 23/23、Server 82 项）PASS，未跳测。
- [ ] W03 Input Polish 由 OpenCode 在独立分支提交 `acf8061`，依据 `private-docs/prompts/39-deerflow-parity-delivery/W03-Developing/W03-InputPolish-Developing.md`；仅做冻结 `/api/input-polish` 后端，不代表 W03 整包完成。首次 `opencode/mimo-v2.6-flash-free` 运行因外部目录权限拒绝而无产出；使用用户指定的 `--model deepseek/deepseek-flash` 重派后，定向 14/14、整仓 `mvn test` PASS，controlled-provider 2/2 复跑 PASS，`clean verify` 正补跑。产品测试未调用真实付费 Provider；主控仍须复审代码与合入态。
- [x] W02 基础聊天已于 2026-09-26 13:44 +08:00 通过人工体验验收并关单：真实 DeepSeek 对话、SSE、取消、页面刷新恢复通过；重启历史归 W18，Interaction 归 W05。
- [ ] W03 补齐同一 Conversation 换模型、title/polish/suggestions 与冻结 UI 差分。038-03 仍处于待实施授权，不能把方案当成已交付功能。
- [ ] W05 核心协议稳定后推进 W07 的薄 Goal/Todo/Context/终止语义，再展开 W08 的产品层 Child 投影与 W13 的 Memory 捕获/召回。W10 的 SDK 动态凭据前置已解除，但产品配置、Tool allowlist 与交互仍待 W03/W05。
- [ ] W04 的 archive/restore/delete、document conversion、PDF/二进制预览及 25 MiB 浏览器边界仍未完成。若拆分 demo-critical 与 full-parity-tail，须先修改路线图和验收口径并经人工裁决，不得直接将原整包标为完成。

## HA `0.1.2-SNAPSHOT` SDK 缺口

缺口索引和需求提示词见 `private-docs/prompts/038-sdk-012-super-harness-gaps/`（`haifa-agent-internal-docs` 仓库）。本仓 `haifa.version` 与 clean-consumer BOM 路径已切换至 `0.1.2-SNAPSHOT`。公开产物最初于 2026-09-25 17:11–17:16 +08:00 从 HA `feat-bump-0.1.2-snapshot@c66a3b76` 发布；2026-09-26 已从包含 #249/#250/#251 的干净 `origin/dev@c0e2c1c6` 独立重发布。SDK 侧 L3/L2 门禁和落盘哈希已通过，本仓对最新基线的 clean-consumer、主线 Playwright 与真实 Provider 回归须另记，不能用历史 119 项替代。

- [x] 038-01a：删除无消费者的 Memory 治理入口。HA `feat-012-sh-sdk-gaps@12c24127`；2026-09-25 `install -DskipTests`、`javap`（确认被删 API 不存在）及本仓 clean verify 119 项通过，2 项有意跳过。
- [x] 038-01b：Memory 直接 CRUD、AGENT scope 与 SQLite V15 clean cut。HA `6540b2ec` + `6013bc8a`；`AgentMemories.put/update/delete/find/list/clear`、`MemoryScopeSpec.user()/agent(id)/session(id)`、`SqliteSdkContributions.memory(policy)` 已公开。SH 异步捕获须传 `observedAt` 防止 clear 后迟到写复活；不同自定义 Agent 须使用独立 `definitionId`。
- [x] 038-02a：最小父子委托。HA `24a990f5` + `1114ef5c`，含 SQLite V14 `run(parent_run_id, created_at)` 索引；公开 `ChildAgentSpec`、`ProductProfile.allowedChildAgents`、`HaifaAgentBuilder.childAgent/maxConcurrentChildRuns`、`AgentRuns.children`、`child.run.*`。本仓 `SdkParentChildDelegationSmokeTest` 用 latch 验证双 Child 真并行。
- [x] 038-02b：SDK Run 输入 Steer。HA `97451fac` + `ff0fb0ee`；公开 `AgentRuns.submitInput`，有待处理输入时 Final 推迟为 `completion.deferred/PENDING_RUN_INPUT`。SH 须将被替代的首次回答正确投影，W05 产品实现尚未验收。
- [x] 038-01/02 集成分支经 [HA PR #250](https://github.com/haifa-agent/haifa-agent/pull/250) 于 2026-09-26 11:15 +08:00 人工合入 `dev`，merge commit `83a936ee`。评审修复 `88ea0d22` 覆盖 Child slot 生命周期、调度拒绝结算、父级恰一次终态事件、输入引用继承、Memory 按 intent 幂等及 Unicode SQLite 搜索。PR 记录 HA L3 2191 项 PASS；本仓 clean verify 120 项 PASS，含公开 JAR 双 Child smoke。较早集成态 `6013bc8a` 的 HA L3 2181 项、clean install、`javap` 及本仓 119 项亦通过。
- [x] MCP 动态认证经 [HA PR #249](https://github.com/haifa-agent/haifa-agent/pull/249) 于 2026-09-26 11:08 +08:00 人工合入 `dev`（`91d38f48`）：动态 Token/Header Supplier、`McpOAuthClientCredentials`、token 缓存/刷新、CRLF 注入防护及密钥脱敏。W10 的 SDK blocker 解除，SH 产品实现未开始。
- [x] Memory Recall 增强经 [HA PR #251](https://github.com/haifa-agent/haifa-agent/pull/251) 于 2026-09-26 12:39 +08:00 人工合入 `dev`（`c0e2c1c6`）：相关性优先、近期项补足至 16 条、Prompt Cache 稳定排序。W13 的 SDK 前置完成，SH 捕获、召回与 M01–M18 尚未开始。

每项新 SDK 缺口从 HA `dev` 建独立 `feat-*` 分支/worktree，公开 JAR/BOM 发布到本地 Maven 后由本仓按坐标消费；完成开发须向 `dev` 发起 GitHub PR、在此记录验证并提醒人工审查合并，智能体不得自行合并。当前 `DeerFlowConfigurationApi` 托管 provider 版本标签仍为 `0.1.1-SNAPSHOT`，它参与配置模型 digest；改动前须先决策旧 data-root 兼容性。

## HA `0.1.1-SNAPSHOT` 历史交接

- [x] [HA PR #239](https://github.com/haifa-agent/haifa-agent/pull/239) 于 2026-09-24 08:10:02Z 合入 `feat-0.1.1-baseline`（`5e95cc78`），经 [#241](https://github.com/haifa-agent/haifa-agent/pull/241) 于 09:40:43Z 回退，再由 [#242](https://github.com/haifa-agent/haifa-agent/pull/242) 于 09:41:18Z 重新落地（`78a0ee02`，merge `e89a8302`）；以上均由人工合并。SDK 功能分支 `feat-sqlite-durable-assembly@6a4575f13c5a5153ab4781a4ae96cc9988426a82` 的公开 API 为 `SqliteSdkContributions.initializeWithKey(..., SecretKey)` 和 `borrowedPersistence()`，未上移 SH 领域模型。HA `-Pci-fast clean verify` 2095 项通过；continuation reopen/wrong-key/invalid-key/borrowed-lifecycle 定向测试通过；当时本地 SQLite JAR SHA-256 为 `6958A9EDCFE974DE493580FD42DB2F02676EEC5B1721919557E1DAEF0005B70E`，`javap` 确认两项公开方法，本仓 clean verify 95 项通过、1 项凭据门禁 live test 跳过。
- [x] 从 `feat-0.1.1-baseline@22c9261c` 统一重发布旧线的待办已于 2026-09-25 被 HA `0.1.1` 正式发布及本仓切换 `0.1.2-SNAPSHOT` 取代；不再执行。此前本地 SQLite JAR 构建于合并前的 `6a4575f`，与 `22c9261c` 的主代码仅 `requireAes256Key` 从 private 改为包可见，公开 API 相同；其他旧线 JAR 曾混有 2026-09-22/24 安装版本。旧线统一重发布验证状态为 `NOT_RUN`，不应误写为 PASS。

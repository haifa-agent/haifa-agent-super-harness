# 项目待办与上游 SDK 交接

本文件用中文维护。状态以实际合并、已发布的本地 JAR、Super Harness 验证证据分别判定；上游 PR 已合并不等于本仓产品工作包完成。工作包状态的唯一数据源是根目录 `work-packages.js`。

## 当前需要推进

- [ ] W05 Interaction 收尾 `codex/w05-message-identity-steer@3ab6d93` 已经主控复审并合入 `main@cc971c7`，派单依据为 `private-docs/prompts/39-deerflow-parity-delivery/W05-Developing/W05-InteractionClosure-Developing.md`。主线 `clean verify` server 111 项、0 失败/错误、2 项有意跳过，主端口 18018/33018 smoke 通过；合入态 W18 active reload 差分 4/4×2 PASS。首次空缓存消费门禁中，`pendingInteractionSurvivesServerRestartAndResumesTheSameRun` 返回 409，111 项中 1 FAIL；同工作树定向复跑 4/4 PASS，二次空缓存门禁全 6 模块 PASS（server 111/0/2），未判定间歇性失败根因。生产 Tool、真正独立 OS 进程重启、非 approval/多 Interaction 仍 `NOT_RUN`，W05 整包不关单。
- [x] HA `0.1.2-SNAPSHOT` 本地基线已从含 #249/#250/#251 的干净 `origin/dev@c0e2c1c6` 独立 worktree 发布：L3 `clean verify` 2207 项 PASS，L2 公开 JAR/BOM `install` 1202 项 PASS；本地 SDK/starter/SQLite JAR SHA-256 与该 worktree 的 `target` 产物一致，SQLite `javap` 显示公开 `memory(ProductMemoryPolicy)`。本仓主线 `clean verify` server 111/0/2、公共 JAR 双 Child smoke 1/1、二次空缓存消费验证 6 模块及合入态同代码 W04 冻结 UI 2/2×2、W05 分支 Interaction/ActiveReconnect 双跑、W18 Haifa 差分 4/4×2 均 PASS。首次 clean-consumer 因上述 W05 间歇 409 FAIL，单列 W05 问题继续定位，不抹除；后续禁止与其他进程并发覆盖同一 SDK 快照。
- [x] W18 文本/SSE 双后端差分切片：runner `0ee36ed` 已合入 `main@f47b425`，派单依据为 `private-docs/prompts/39-deerflow-parity-delivery/W18-Developing/W18-DualBackendDifferential-Finished.md`。同一冻结 UI/controlled provider 下，DeerFlow 参考后端 4/4×3、合入 W05 后 Haifa 后端 4/4×2 PASS（92/84 秒，0 重试，槽位端口全部释放），含 active reload/join 消息唯一性。旧 main@045e8e0 的 Haifa 3/4×3 FAIL 与 trace 继续保留，不抹除。上传→生产文件 Tool→下载 hash、Interaction 和 WS/文件尾项尚未做双后端差分，W18 整包仍 `in_progress`。
- [x] W03 Input Polish 后端切片：OpenCode 使用 `--model deepseek/deepseek-flash` 提交 `acf8061`，主控复审后合入 `main@85b2a5b`；派单依据为 `private-docs/prompts/39-deerflow-parity-delivery/W03-Developing/W03-InputPolish-Finished.md`。定向 14/14、分支 `clean verify`、controlled-provider 2/2 连续两轮和主端口同源空输入 400 通过；未调用真实付费 Provider。槽位 2 前端 53018 落在 NAT 排除范围，UI smoke 记 `NOT_RUN`。首次 `opencode/mimo-v2.6-flash-free` 因外部目录权限拒绝而无产出，保留试点失败记录；W03 的 title/suggestions、同一 Conversation 换模型和 UI 差分仍未完成，整包不关单。
- [x] W02 基础聊天已于 2026-09-26 13:44 +08:00 通过人工体验验收并关单：真实 DeepSeek 对话、SSE、取消、页面刷新恢复通过；重启历史归 W18，Interaction 归 W05。
- [ ] W03 补齐同一 Conversation 换模型、title/polish/suggestions 与冻结 UI 差分。038-03 仍处于待实施授权，不能把方案当成已交付功能。
- [ ] W05 核心协议稳定后推进 W07 的薄 Goal/Todo/Context/终止语义，再展开 W08 的产品层 Child 投影与 W13 的 Memory 捕获/召回。W10 的 SDK 动态凭据前置已解除，但产品配置、Tool allowlist 与交互仍待 W03/W05。
- [ ] W04 主路径在更新 SDK 后由独立 `codex/w04-main-regression@c7d6c6e` 工作树以冻结 UI 双轮 2/2×2 PASS（142 秒/106 秒，零重试，端口释放）；17 份原始日志从 `target/w04-frozen-ui-files-evidence` 复制至 `local-tmp/slot-1-w04-main-20260926/evidence` 并逐一哈希核对。主工作树同目录第二个 Next dev 因 33018 已运行而拒绝启动，原始失败日志另存主树 `local-tmp/slot-1-w04-main-20260926/evidence`，并非功能断言失败。archive/restore/delete、document conversion、PDF/二进制预览及 25 MiB 浏览器边界仍未完成。若拆分 demo-critical 与 full-parity-tail，须先修改路线图和验收口径并经人工裁决，不得直接将原整包标为完成。

## HA `0.1.2-SNAPSHOT` SDK 缺口

缺口索引和需求提示词见 `private-docs/prompts/038-sdk-012-super-harness-gaps/`（`haifa-agent-internal-docs` 仓库）。本仓 `haifa.version` 与 clean-consumer BOM 路径已切换至 `0.1.2-SNAPSHOT`。公开产物最初于 2026-09-25 17:11–17:16 +08:00 从 HA `feat-bump-0.1.2-snapshot@c66a3b76` 发布；2026-09-26 已从包含 #249/#250/#251 的干净 `origin/dev@c0e2c1c6` 独立重发布。SDK 侧 L3/L2 门禁和落盘哈希已通过；本仓最新主线 `clean verify` server 111/0/2、二次空缓存消费验证 6 模块和 W18 controlled-provider 差分 4/4×2 通过，首次空缓存 1 FAIL 保留。不得以历史 119 项或本轮受控 Provider 替代真实付费 Provider 回归。

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

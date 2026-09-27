# 项目待办

本文件仅保留未完成任务；已完成的合入与验收历史见 `work-packages.js` 里程碑及对应私有交付报告。工作包状态以 `work-packages.js` 为准。W04 Archive/Restore/Delete 切片已按人工风险裁决合入 `main@3db883d`，主线 `mvn -o -ntp clean verify` 六模块通过，原数据目录的 Gateway `18018` 和前端 `33018` 烟测通过；已知风险被接受合入，不等于缺陷已修复。

## 客户演示 v0：真实模型纵向闭环（整链尚未验收）

目标、双轨工作流及逐阶段 Go/No-Go 见[客户演示路线图](../private-docs/roadmap/36-super-harness-customer-demo-v0.md)：真实模型 + 生产 Tool + Approval + Artifact + Reload/Reconnect + Steer，在冻结 DeerFlow UI 连续两轮无异常。Dashboard 默认“客户演示交付”Tab 是 M0–M4 控制台，`work-packages.js.customerDemo` 是阶段状态数据源；“工作包总览”仍保留 W00–W24 完整范围。W00 静态矩阵与 W05 安全新建文件切片已复审合入，但五个阶段门禁均 `NOT_RUN`、演示决策 `NO_GO`；它不是 PRD D01–D05 全部完成。

- [ ] P0｜M0：主控已固定 `contracts/customer-demo/customer-demo-v0-oracle.json`（两轮、8 case、同 Run Steer 窗口），只读 doctor 的静态正例/4 个负例及主端口前端、SDK、模型、OS 凭据、数据根核验 PASS，未调用付费模型；真实模型预算授权和真实 Steer 窗口仍为 `NOT_RUN`，不得把 doctor 当整链 PASS。W00 矩阵仅按脚本触及来源消费。任务分工见[演示派工索引](../private-docs/prompts/39-deerflow-parity-delivery/show-roadmap-1/README.md)。
- [ ] P0｜M1：W05 受限生产文件 Tool `c6c2be9` 已复审合入；主控在 `main@45a7ea6` 补齐受控 Provider 的批准前 404、错误 request/跨线程/重复 409、批准恰好一次、拒绝后无文件，以及取消/过期定向覆盖；定向 11/11、六模块 `clean verify`（Server 157/0/0/2 skip）和主端口 health/page/models smoke 均 PASS，未调用付费模型。M0 尚未通过，首次 Steer SSE gap `FAIL` 与 Windows 符号链接负例 `NOT_RUN` 继续保留，M1 不提升为 PASS；受控 Provider 不能代替真实模型整链。
- [ ] P0｜M2：补真实 Artifact 发布与冻结 UI Tool/Approval/Artifact 卡片；子智能体提示词为 [W05-DemoArtifactUi-Pending](../private-docs/prompts/39-deerflow-parity-delivery/show-roadmap-1/W05-Developing/W05-DemoArtifactUi-Pending.md)。主控先评审上游 SDK 完整 Tool transcript、普通 Tool 持久 Interaction 的最小公开接口，必要时走 HA `feat-*` PR 和人工复杂度裁决，不在 SH 另建权威。
- [ ] P0｜M3：W18 在冻结 UI 把 pending→reload/reconnect→approve→Tool→Artifact→active Steer→terminal 做 L2 两轮及相关 reference/Haifa 逐 case 差分，提示词为 [W18-DemoL2Differential-Pending](../private-docs/prompts/39-deerflow-parity-delivery/show-roadmap-1/W18-Developing/W18-DemoL2Differential-Pending.md)。保留旧 W04 上传 `FAIL`，不用 fixture 冒充生产能力。
- [ ] P1｜W24 演示证据框架：OpenCode（`--model deepseek/deepseek-flash`）候选 `3fcc2f7` 已在独立 worktree 交付，六模块构建、61+41 assertions 与 Dashboard 2/2×2 均 PASS，但尚未合入。主控源码评审发现两个阻断并已退回同一 lane：阶段/轮次真实 `FAIL` 被总报告折叠为 `NOT_RUN`；Artifact 的下载 SHA/字节数及 provenance 只验证声明字符串，未绑定实际下载文件和原始证据。必须以独立修复提交补齐负例后再复审；不得运行付费模型或替主控宣告 GO，后续阶段无原始证据时继续保持 `NOT_RUN`。
- [ ] P0｜M4：由主控在主线 `18018/33018` 使用真实模型按同配置/数据根连续两轮 L3 全链 `PASS`，零隐藏重试，W24 汇总原始 trace、Run/Tool/Interaction/Artifact、下载 hash、Usage 与失败史，再请人工体验验收；否则仍为 `FAIL`/`NOT_RUN`，不宣称演示版就绪。当前路线止于 M4，没有独立 M5。

## W04：已人工接受 Archive/Restore/Delete 切片；偶现风险挂账，不阻断其他工作包

- [ ] P0｜[W04-BUG-001 项目删除偶发失败后的数据一致性风险](../private-docs/bugfixs/018-deerflow-parity-delivery/W04/W04-ProjectDeleteConsistency.md)：归档了逐文件删除失败、Trash 字节未核验、线程解绑补偿和并发边界的触发条件、影响及 `NOT_RUN` 门禁。人工接受已合入切片带风险继续交付，现暂停 W04 修复、保留 P0；不阻断 W00/W05 等独立工作包，但不能把该缺陷写成已修复或据此宣布 W04 整包验收通过。
- [ ] P1｜W04 UI 尾项：生命周期冻结 UI 新旧脚本最终两轮 4/4 PASS，但尚未核验恢复后下载真实字节/hash 和代表性错误提示；`local-tmp/slot-1-w04-ard/evidence/flow-20260927-104032.json` 另有一次退出码 1，须保留并分类，不用后续 PASS 抹除。W04 整包另有 document conversion、PDF/二进制预览及浏览器 25 MiB 边界；当前按人工裁决暂停修复，状态 `blocked`，不阻断其它独立工作包。
- [ ] P1｜W18 差分发现的 W04 文件消费缺口：冻结 UI 新会话首条上传返回 404 `Thread not found`；已有 Thread 上传 200 后模型上下文仍缺上传路径。修复后原样复跑同一双后端 runner 两轮。

## 其他产品工作包

- [ ] W05 Interaction：历史首次合法重启回复 HTTP 409 仍无原始 cause；受控独立 JVM 重启两组首次合法回复均 200、冻结 UI 两轮 2/2 PASS，不能据此宣告旧故障已修复。下一次失败须保留脱敏请求/响应、内部错误链与 SQLite/Run/pending 状态，稳定复现后再修。生产受限 `write_file` 安全新建切片 `c6c2be9/main@cf04388` 已合入，非完整 M1；覆盖/append、真实 Artifact 和完整 Tool 卡片未验收，Windows 符号链接负例 `NOT_RUN`。公开 SDK 当前无普通 JavaTool 发起持久 `ask_clarification` 及只读完整 Tool call/result transcript 的入口，须先做上游最小 API 设计/独立 `feat-*` 分支，不在 SH 自建第二权威。生产 JAR 跨进程重启、非 approval/多 Interaction 仍未完成；测试类路径 fixture 不算生产能力。
- [ ] W18/W12：W18 诊断 runner 的 reference 9/9×2、Haifa 5 PASS/2 FAIL/2 NOT_RUN×2 是已保存的差分基线，不是产品全绿。对应产品缺口修复后逐 case 双轮复验，W04 P0 不阻止其它独立 case；浏览器 WS 目前只验证禁用态拒绝握手，启用态 screencast 两侧 `NOT_RUN`，归 W12。
- [ ] W00：资源事实盘点已合入 `main@224db89`，40 项媒体、23 项未导入 Skill 和 104 项前端依赖有静态来源/哈希清单；hooks 消费矩阵 `f49fad7+894cec2/main@735fa22` 已复审合入，28 条静态消费及独立 baseline 负例通过，运行互通仍 `NOT_RUN`。品牌/favicon 复用授权、依赖 NOTICE、未导入 Skill 许可和本地缺失的 SDK StreamManager 包边界仍待核定；W00 整包完成不是演示 v0 的硬前置。
- [ ] W03：同一 Conversation 换模型和 Input Polish 专项冻结 UI 差分仍待验收；title/suggestions 后端切片已合入不代表整包完成。
- [ ] W07/W08/W13：待 W05 协议稳定后实现薄 Goal/Todo/Context/终止语义、产品层 Child 投影、Memory 捕获与召回；W10 仍需产品配置、Tool allowlist 和交互，不把已完成 SDK 前置当产品完成。

## 上游 SDK 新缺口

新 SDK 缺口从 HA `dev` 建独立 `feat-*` 分支/worktree，公开 JAR/BOM 发布到本地 Maven 后由本仓按 `0.1.2-SNAPSHOT` 消费；开发完成须向 `dev` 发 GitHub PR 并提醒人工审查合并，智能体不得自行合并。当前 `DeerFlowConfigurationApi` 托管 provider 版本标签仍为 `0.1.1-SNAPSHOT`，参与配置模型 digest；改动前须裁决旧 data-root 兼容性。

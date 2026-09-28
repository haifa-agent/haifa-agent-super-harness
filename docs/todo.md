# 项目待办

本文件仅保留未完成任务；已完成的合入与验收历史见 `work-packages.js` 里程碑及对应私有交付报告。工作包状态以 `work-packages.js` 为准。W04 Archive/Restore/Delete 切片已按人工风险裁决合入 `main@3db883d`，主线 `mvn -o -ntp clean verify` 六模块通过，原数据目录的 Gateway `18018` 和前端 `33018` 烟测通过；已知风险被接受合入，不等于缺陷已修复。

## 客户演示 v0：真实模型纵向闭环（M0–M2 已通过，整链尚未验收）

目标、双轨工作流及逐阶段 Go/No-Go 见[客户演示路线图](../private-docs/roadmap/36-super-harness-customer-demo-v0.md)：真实模型 + 生产 Tool + Approval + Artifact + Reload/Reconnect + Steer，在冻结 DeerFlow UI 连续两轮无异常。Dashboard 默认“客户演示交付”Tab 是 M0–M4 控制台，`work-packages.js.customerDemo` 是阶段状态数据源；“工作包总览”仍保留 W00–W24 完整范围。M0/M1 已在 `main@23271ee` 结算 `PASS`，M2 已在 `main@06edace` 结算 `PASS`；M3/M4 仍 `NOT_RUN`，演示决策仍为 `NO_GO`；它不是 PRD D01–D05 全部完成。

- [x] P0｜M0：固定 Oracle 与授权版 live doctor 在 `main@23271ee` 结算 `PASS`。`contracts/customer-demo/evidence/m0-doctor-authorized.json` 记录冻结前端 `e545c28...`、SDK `0.1.2-SNAPSHOT`、主端口、真实模型、OS 凭据引用和数据根全部通过；M0 本身不调用付费模型。最终 GO 前仍须在最终主线 SHA 重验证，M3/M4 的真实 Steer 不由 M0 代替。任务分工见[演示派工索引](../private-docs/prompts/39-deerflow-parity-delivery/show-roadmap-1/README.md)。
- [x] P0｜M1：在冻结 UI 使用真实 `main-deepseek` 发起生产 `write_file`，Thread `bd0e6b9b...` / Run `01a0e31b...` / ToolCall `01a0e31b-0dbb...` / Interaction `01a0e31b-1028...`；审批前文件 404，reload 后同一审批卡恢复，批准后同 Run 成功并生成精确 27 字节、SHA-256 `aee4acb8...` 的文件，二次 reload 显示卡片已回答且按钮禁用。Usage 1399 tokens/2 次 LLM；M1 定向 53/0/0/0 PASS。原始证据见 `contracts/customer-demo/evidence/m1-real-model-production-tool.json` 及配套 PNG/TXT。Windows symlink 负例仍 `NOT_RUN`，旧 Steer gap 归 M3，均不冒充最终 GO 绿灯。
- [x] P0｜M2：HA PR [#254](https://github.com/haifa-agent/haifa-agent/pull/254) 已合入 `dev@279afcc6`，`0.1.2-SNAPSHOT` 发布/JAR/`javap` 与 SH clean consumer 门禁通过。产品提交 `49e48a4` 经主控复审，以非改写 merge 合入 `main@06edace`；复用 SDK ToolCall/Artifact、现有 Workspace 与 Policy/Interaction 权威，没有新增 store、领域实体、状态机或第二权威。主控定向 23 项 0 failure/error、1 Windows symlink 条件 skip；精确主线 SHA clean package 共 207 项 0 failure/error、3 skip。主控冻结 UI 两轮均 1/1 PASS、0 retry，独立 Run/ToolCall/Artifact、65 字节下载 SHA、reload/reconnect 后单卡单结果与端口释放全部通过。候选首次 reload 卡片消失 FAIL 原件保留；真实模型 M4、M3 active Steer 整链、进程级重启后的完成卡浏览器恢复及人工 GO 仍 `NOT_RUN`。提示词见 [W05-DemoArtifactUi-Finished](../private-docs/prompts/39-deerflow-parity-delivery/show-roadmap-1/W05-Developing/W05-DemoArtifactUi-Finished.md)，报告见 [W05-DemoArtifactUi](../private-docs/ai-coding-report/39-deerflow-parity-delivery/show-roadmap-1/W05/W05-DemoArtifactUi.md)。
- [ ] P0｜M3（开发中）：W18 已从 `main@7c10af8` 派到 `codex/w18-demo-l2-differential` 独立 worktree，使用槽位 3 的 `40618/45618/46618` 和独立 data-root；端口排除段及 loopback 绑定预检通过。目标是在冻结 UI 把 pending→reload/reconnect→approve→Tool→Artifact→active Steer→terminal 做 L2 两轮及 reference/Haifa 逐 case 差分，提示词为 [W18-DemoL2Differential-Developing](../private-docs/prompts/39-deerflow-parity-delivery/show-roadmap-1/W18-Developing/W18-DemoL2Differential-Developing.md)。保留旧 W04 上传 `FAIL`，不用 fixture 冒充生产能力；当前尚无运行结果。
- [ ] P1｜W24 持续演示证据：基础证据包任务已完成，OpenCode（`--model deepseek/deepseek-flash`）提交 `3fcc2f7+3421a7a+a814bef` 经主控两轮阻断复审后合入 `main@17cf028`。阶段/轮次 `FAIL` 会传染总结果，Artifact 的 file/download SHA、真实字节、provenance 及 Oracle `write_file`/`sdk-owned-real-artifact` 已端到端绑定；主控独立复验客户演示 64 assertions/29 cases、W24 总门禁 81 assertions PASS。任务提示词为 [W24-DemoEvidenceBundle-Finished](../private-docs/prompts/39-deerflow-parity-delivery/show-roadmap-1/W24-Developing/W24-DemoEvidenceBundle-Finished.md)，交付报告见 [W24-DemoEvidenceBundle](../private-docs/ai-coding-report/39-deerflow-parity-delivery/show-roadmap-1/W24/W24-DemoEvidenceBundle.md)。W24 是长期横切工作包，现已消费 M0/M1 的真实原始证据；仍需消费 M2–M4，当前总决策保持 `NO_GO`。
- [ ] P0｜M4：模型调用已获持续授权，无需再次申请预算；前置门禁通过后由主控在主线 `18018/33018` 使用真实模型按同配置/数据根连续两轮 L3 全链 `PASS`，零隐藏重试，W24 汇总原始 trace、Run/Tool/Interaction/Artifact、下载 hash、Usage 与失败史，再请人工体验验收；否则仍为 `FAIL`/`NOT_RUN`，不宣称演示版就绪。当前路线止于 M4，没有独立 M5。

## W04：已人工接受 Archive/Restore/Delete 切片；偶现风险挂账，不阻断其他工作包

- [ ] P0｜[W04-BUG-001 项目删除偶发失败后的数据一致性风险](../private-docs/bugfixs/018-deerflow-parity-delivery/W04/W04-ProjectDeleteConsistency.md)：归档了逐文件删除失败、Trash 字节未核验、线程解绑补偿和并发边界的触发条件、影响及 `NOT_RUN` 门禁。人工接受已合入切片带风险继续交付，现暂停 W04 修复、保留 P0；不阻断 W00/W05 等独立工作包，但不能把该缺陷写成已修复或据此宣布 W04 整包验收通过。
- [ ] P1｜W04 UI 尾项：生命周期冻结 UI 新旧脚本最终两轮 4/4 PASS，但尚未核验恢复后下载真实字节/hash 和代表性错误提示；`local-tmp/slot-1-w04-ard/evidence/flow-20260927-104032.json` 另有一次退出码 1，须保留并分类，不用后续 PASS 抹除。W04 整包另有 document conversion、PDF/二进制预览及浏览器 25 MiB 边界；当前按人工裁决暂停修复，状态 `blocked`，不阻断其它独立工作包。
- [ ] P1｜W18 差分发现的 W04 文件消费缺口：冻结 UI 新会话首条上传返回 404 `Thread not found`；已有 Thread 上传 200 后模型上下文仍缺上传路径。修复后原样复跑同一双后端 runner 两轮。

## 其他产品工作包

- [ ] W05 Interaction：历史首次合法重启回复 HTTP 409 仍无原始 cause，不能用已关闭的相邻竞态倒推同因。038-05 SDK 切片已完成：HA `feat@f822bd18` 经主控复审关闭 successor 调度 P1，PR [#253](https://github.com/haifa-agent/haifa-agent/pull/253) 合入 `dev@5c967bc`，SH 消费回归通过。M1 真实模型同链已在 `main@23271ee` 结算 PASS；M2 的 038-06 与真实 Artifact/冻结 UI 已在 `main@06edace` 结算 PASS。W05 整包仍保留旧 409 原始 cause、task-3 非 approval/多 Interaction、生产 JAR 跨进程浏览器恢复等尾项；这些不回退 M2，也不能据 M2 PASS 关闭整包。
- [ ] W18/W12：W18 诊断 runner 的 reference 9/9×2、Haifa 5 PASS/2 FAIL/2 NOT_RUN×2 是已保存的差分基线，不是产品全绿。对应产品缺口修复后逐 case 双轮复验，W04 P0 不阻止其它独立 case；浏览器 WS 目前只验证禁用态拒绝握手，启用态 screencast 两侧 `NOT_RUN`，归 W12。
- [ ] W00：资源事实盘点已合入 `main@224db89`，40 项媒体、23 项未导入 Skill 和 104 项前端依赖有静态来源/哈希清单；hooks 消费矩阵 `f49fad7+894cec2/main@735fa22` 已复审合入，28 条静态消费及独立 baseline 负例通过，运行互通仍 `NOT_RUN`。品牌/favicon 复用授权、依赖 NOTICE、未导入 Skill 许可和本地缺失的 SDK StreamManager 包边界仍待核定；W00 整包完成不是演示 v0 的硬前置。
- [ ] W03：同一 Conversation 换模型仍等待 W05 准入/恢复边界。Input Polish 冻结 UI task-2 已完成并从最近安排移除：OpenCode（`deepseek/deepseek-flash`）候选 `c03c524+3e12a6e+53e9d1b` 经主控独立 W00 合同 34 项、冻结 UI 10/10×2、成功态双轮 screenshot 实检、`reasoning_effort` 属性不存在和每轮端口/归属进程自动释放验收，合入 `main@6a3a17c`；报告见 [W03-InputPolishUi](../private-docs/ai-coding-report/39-deerflow-parity-delivery/show-roadmap-1/W03/W03-InputPolishUi.md)。首次 W05 SQLite 竞态 FAIL 如上保留；task-2 完成不代表 W03 整包完成。
- [ ] W07/W08/W13：待 W05 协议稳定后实现薄 Goal/Todo/Context/终止语义、产品层 Child 投影、Memory 捕获与召回；W10 仍需产品配置、Tool allowlist 和交互，不把已完成 SDK 前置当产品完成。

## 上游 SDK 新缺口

新 SDK 缺口从 HA `dev` 建独立 `feat-*` 分支/worktree，公开 JAR/BOM 发布到本地 Maven 后由本仓按 `0.1.2-SNAPSHOT` 消费；开发完成须向 `dev` 发 GitHub PR 并提醒人工审查合并，智能体不得自行合并。当前 `DeerFlowConfigurationApi` 托管 provider 版本标签仍为 `0.1.1-SNAPSHOT`，参与配置模型 digest；改动前须裁决旧 data-root 兼容性。

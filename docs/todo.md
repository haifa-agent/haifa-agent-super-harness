# 项目待办

本文件仅保留未完成任务；已完成的合入与验收历史见 `work-packages.js` 里程碑及对应私有交付报告。工作包状态以 `work-packages.js` 为准。W04 Archive/Restore/Delete 切片已按人工风险裁决合入 `main@3db883d`，主线 `mvn -o -ntp clean verify` 六模块通过，原数据目录的 Gateway `18018` 和前端 `33018` 烟测通过；已知风险被接受合入，不等于缺陷已修复。

## 客户演示 v0：M0–M4 与人工体验已通过（GO）

客户演示路线与完整退出标准见[路线图](../private-docs/roadmap/36-super-harness-customer-demo-v0.md)。`main@c112012` 在冻结 DeerFlow UI 以真实 DeepSeek、生产 `write_file`、Approval、Artifact、Reload/Reconnect 与同 Run Steer 连续两轮 PASS，零隐藏重试；人工已明确回复“人工验收通过”。[W24 证据包](../contracts/customer-demo/customer-demo-v0-evidence-bundle.json) 的 `-RequireGo` 本机门禁 PASS，原始 trace/日志留在 `local-tmp/m4-main-c112012-20260928-evidence/`，[人工声明](../contracts/customer-demo/evidence/m4-human-acceptance.md)已记录。此 GO 仅为本地纵向演示 v0，不代表 PRD D01–D05 或 W00–W24 整包完成；W04 P0、上传差分 FAIL、历史 W05 409 等继续挂账。

- [ ] P1｜W24 长期证据与发布：演示 v0 的 M0–M4、真实模型双轮和人工验收已聚合，[W24 证据包](../contracts/customer-demo/customer-demo-v0-evidence-bundle.json) `-RequireGo` 本机 PASS；失败史和 reference `UPSTREAM_UNSUPPORTED`、W04 两项 `FAIL`、生产 `ask_clarification` `NOT_RUN` 保留。基础证据聚合切片已完成，W24 整包仍需外置原始 trace/日志跨机归档、PRD D01–D05、完整发布与 Full Parity 证据，不能因演示 GO 改为 complete。

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

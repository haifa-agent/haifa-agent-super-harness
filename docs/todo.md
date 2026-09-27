# 项目待办

本文件仅保留未完成任务；已完成的合入与验收历史见 `work-packages.js` 里程碑及对应私有交付报告。工作包状态以 `work-packages.js` 为准。W04 Archive/Restore/Delete 切片已按人工风险裁决合入 `main@3db883d`，主线 `mvn -o -ntp clean verify` 六模块通过，原数据目录的 Gateway `18018` 和前端 `33018` 烟测通过；已知风险被接受合入，不等于缺陷已修复。

## W04：已人工接受 Archive/Restore/Delete 切片；偶现风险挂账，不阻断其他工作包

- [ ] P0｜[W04-BUG-001 项目删除偶发失败后的数据一致性风险](../private-docs/bugfixs/018-deerflow-parity-delivery/W04/W04-ProjectDeleteConsistency.md)：归档了逐文件删除失败、Trash 字节未核验、线程解绑补偿和并发边界的触发条件、影响及 `NOT_RUN` 门禁。人工接受已合入切片带风险继续交付，现暂停 W04 修复、保留 P0；不阻断 W00/W05 等独立工作包，但不能把该缺陷写成已修复或据此宣布 W04 整包验收通过。
- [ ] P1｜W04 UI 尾项：生命周期冻结 UI 新旧脚本最终两轮 4/4 PASS，但尚未核验恢复后下载真实字节/hash 和代表性错误提示；`local-tmp/slot-1-w04-ard/evidence/flow-20260927-104032.json` 另有一次退出码 1，须保留并分类，不用后续 PASS 抹除。W04 整包另有 document conversion、PDF/二进制预览及浏览器 25 MiB 边界；当前按人工裁决暂停修复，状态 `blocked`，不阻断其它独立工作包。
- [ ] P1｜W18 差分发现的 W04 文件消费缺口：冻结 UI 新会话首条上传返回 404 `Thread not found`；已有 Thread 上传 200 后模型上下文仍缺上传路径。修复后原样复跑同一双后端 runner 两轮。

## 其他产品工作包

- [ ] W05 Interaction：历史首次合法重启回复 HTTP 409 仍无原始 cause；受控独立 JVM 重启两组首次合法回复均 200、冻结 UI 两轮 2/2 PASS，不能据此宣告旧故障已修复。下一次失败须保留脱敏请求/响应、内部错误链与 SQLite/Run/pending 状态，稳定复现后再修。生产受限 `write_file` 新建切片已独立派工，覆盖/append 和完整 Tool 卡片未验收；公开 SDK 当前无普通 JavaTool 发起持久 `ask_clarification` 及只读完整 Tool call/result transcript 的入口，须先做上游最小 API 设计/独立 `feat-*` 分支，不在 SH 自建第二权威。生产 JAR 跨进程重启、非 approval/多 Interaction 仍未完成；测试类路径 fixture 不算生产能力。
- [ ] W18/W12：W18 诊断 runner 的 reference 9/9×2、Haifa 5 PASS/2 FAIL/2 NOT_RUN×2 是已保存的差分基线，不是产品全绿。对应产品缺口修复后逐 case 双轮复验，W04 P0 不阻止其它独立 case；浏览器 WS 目前只验证禁用态拒绝握手，启用态 screencast 两侧 `NOT_RUN`，归 W12。
- [ ] W00：资源事实盘点已合入 `main@224db89`，40 项媒体、23 项未导入 Skill 和 104 项前端依赖有静态来源/哈希清单；仍须补齐 `threads/hooks.ts` 与 StreamManager 分支/载荷矩阵、品牌/favicon 复用授权、依赖 NOTICE 及未导入 Skill 许可。法律授权未确认，静态 Oracle 不冒充运行互通。
- [ ] W03：同一 Conversation 换模型和 Input Polish 专项冻结 UI 差分仍待验收；title/suggestions 后端切片已合入不代表整包完成。
- [ ] W07/W08/W13：待 W05 协议稳定后实现薄 Goal/Todo/Context/终止语义、产品层 Child 投影、Memory 捕获与召回；W10 仍需产品配置、Tool allowlist 和交互，不把已完成 SDK 前置当产品完成。

## 上游 SDK 新缺口

新 SDK 缺口从 HA `dev` 建独立 `feat-*` 分支/worktree，公开 JAR/BOM 发布到本地 Maven 后由本仓按 `0.1.2-SNAPSHOT` 消费；开发完成须向 `dev` 发 GitHub PR 并提醒人工审查合并，智能体不得自行合并。当前 `DeerFlowConfigurationApi` 托管 provider 版本标签仍为 `0.1.1-SNAPSHOT`，参与配置模型 digest；改动前须裁决旧 data-root 兼容性。

# 项目待办

本文件仅保留未完成任务；已完成的合入与验收历史见 `work-packages.js` 里程碑及对应私有交付报告。工作包状态以 `work-packages.js` 为准。W04 Archive/Restore/Delete 切片已按人工风险裁决合入 `main@3db883d`，主线 `mvn -o -ntp clean verify` 六模块通过，原数据目录的 Gateway `18018` 和前端 `33018` 烟测通过；已知风险被接受合入，不等于缺陷已修复。

## W04：已人工接受 Archive/Restore/Delete 切片，继续修复已知缺陷

- [ ] P0｜项目删除失败一致性：人工于 2026-09-27 明确接受 `codex/w04-archive-restore-delete@63c910f` 切片带风险合入，但当前 `deleteProject` 在解绑线程后逐文件删除项目目录；中途失败会清理暂存 trash，却无法恢复已删文档/字节。需受控目录删除失败注入、重启回读及重试，保证不删除唯一可恢复副本；在修复前不得声称失败回滚原子。
- [ ] P0｜W04 复杂度裁决：子智能体已只读对照冻结 DeerFlow 的单 SQL 事务与 SH 的项目/Trash/线程分文件权威；完整重启一致性可能需增加持久删除意图和恢复路径。详细方案及未证实门禁见 `private-docs/prompts/39-deerflow-parity-delivery/W04-Developing/W04-ProjectDeleteConsistency-Developing.md`。人工已要求先看设计；方案 A/B 未获最终裁决，W04 task-1 暂停编码，失败注入与真实运行 `NOT_RUN`。
- [ ] P0｜文档字节与线程解绑补偿：项目删除暂存阶段未核验源及既有 trash payload 的大小/SHA，可成功产生不可恢复 trash 行；多线程解绑持久化回滚再次失败只记警告。补缺失/损坏 payload、Nth 线程写入/补偿失败的 fail-closed 测试和重启证据。若最小方案仍要求超过冻结 DeerFlow 的领域复杂度，按 AGENTS.md 先经主控评审与人工裁决。
- [ ] P1｜W04 UI 尾项：生命周期冻结 UI 新旧脚本最终两轮 4/4 PASS，但尚未核验恢复后下载真实字节/hash 和代表性错误提示；`local-tmp/slot-1-w04-ard/evidence/flow-20260927-104032.json` 另有一次退出码 1，须保留并分类，不用后续 PASS 抹除。W04 整包另有 document conversion、PDF/二进制预览及浏览器 25 MiB 边界，继续 `in_progress`。
- [ ] P1｜W18 差分发现的 W04 文件消费缺口：冻结 UI 新会话首条上传返回 404 `Thread not found`；已有 Thread 上传 200 后模型上下文仍缺上传路径。修复后原样复跑同一双后端 runner 两轮。

## 其他产品工作包

- [ ] W05 Interaction：历史首次合法重启回复 HTTP 409 仍无原始 cause；保留脱敏请求/响应、内部错误链与 SQLite/Run/pending 状态，稳定复现后修复。生产 `write_file`、`ask_clarification` 未注册，真正独立 OS 进程重启、非 approval/多 Interaction 仍未完成；测试类路径 fixture 不算生产能力。
- [ ] W18/W12：W18 诊断 runner 的 reference 9/9×2、Haifa 5 PASS/2 FAIL/2 NOT_RUN×2 是已保存的差分基线，不是产品全绿。W04/W05 修复后双轮复验；浏览器 WS 目前只验证禁用态拒绝握手，启用态 screencast 两侧 `NOT_RUN`，归 W12。
- [ ] W00：补齐 `threads/hooks.ts` 与 StreamManager 分支/载荷矩阵、品牌/favicon 复用授权、依赖 NOTICE 及未导入 Skill 许可；静态 Oracle 不冒充运行互通。
- [ ] W03：同一 Conversation 换模型和 Input Polish 专项冻结 UI 差分仍待验收；title/suggestions 后端切片已合入不代表整包完成。
- [ ] W07/W08/W13：待 W05 协议稳定后实现薄 Goal/Todo/Context/终止语义、产品层 Child 投影、Memory 捕获与召回；W10 仍需产品配置、Tool allowlist 和交互，不把已完成 SDK 前置当产品完成。

## 上游 SDK 新缺口

新 SDK 缺口从 HA `dev` 建独立 `feat-*` 分支/worktree，公开 JAR/BOM 发布到本地 Maven 后由本仓按 `0.1.2-SNAPSHOT` 消费；开发完成须向 `dev` 发 GitHub PR 并提醒人工审查合并，智能体不得自行合并。当前 `DeerFlowConfigurationApi` 托管 provider 版本标签仍为 `0.1.1-SNAPSHOT`，参与配置模型 digest；改动前须裁决旧 data-root 兼容性。

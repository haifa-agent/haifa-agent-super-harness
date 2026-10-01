# 上游 SDK 消费与待人工审查事项

本页只记录当前公开 SDK 消费来源；任务与阶段结果以 SHPM 为准。

- 只读网络 Java Tool：SDK commit `1a4689823ba885e464de7f9793b11db8f7c0bb94`，基于 `dev` 开发线 `04a5e537`；[PR #265](https://github.com/haifa-agent/haifa-agent/pull/265) 向 `dev` 发起，待人工审查合并，智能体未合并。
- 已验证并发布的公开坐标为 `io.haifa:haifa-agent-sdk:0.1.2-SNAPSHOT`，JAR SHA-256 `3c1f128791f1e89e2aa852eb5079a3830092e1da2d552d1dcb4f23a8fd64744b`。SDK 同 SHA 的 L3 `-Pci-fast clean verify` 通过：2,269 tests，0 failures/errors，23 skips。
- 本项目只通过本地 Maven 仓库消费该 JAR；生产 Web 工具使用公开 `networkAccess(hosts)` 和按名只读审批装配，无 SDK 源码依赖或 Runtime 内部策略类型。
- SH 消费的首次定向检查通过 45 项；最终源码的全仓门禁、MCP 负例和冷/暖 UI 结果由 SHPM 及对应原始证据独立记录，不能由 SDK 或定向结果推定。

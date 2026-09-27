# Haifa Super Harness

本仓库是冻结 DeerFlow 对齐项目的独立 Java 21 产品工程。项目只通过 Maven 仓库中已发布的 `io.haifa:haifa-agent-bom:0.1.2-SNAPSHOT` 及公开 JAR 消费 Haifa Agent，不依赖 Haifa Agent 源码目录。

## 客户演示与项目看板

双击根目录 `dashboard.html` 可离线查看项目看板，默认进入“客户演示交付”Tab，按 M0–M4 显示当前演示阶段的执行状态、独立门禁结果、负责人、阻断和下一步；点击阶段上的 W 编号可跳到“工作包总览”。“最近工作安排”显示当前派工，“工作包总览”保留 W00–W24 的完整长期范围。三个视图只读根目录 `work-packages.js`，刷新页面获取最新记录；看板显示不等于运行验收。

已按冻结 lockfile 准备好 `frontend/node_modules` 和本地 Playwright 浏览器时，可在仓库根目录运行看板的本地 `file://` 切换回归：

```powershell
frontend/node_modules/.bin/playwright.cmd test -c tests/dashboard/playwright.config.cjs
```

本轮演示目标与 Go/No-Go 见[客户演示 v0 路线图](private-docs/roadmap/36-super-harness-customer-demo-v0.md)：真实模型、生产 Tool、Approval、Artifact、Reload/Reconnect、Steer 在冻结 DeerFlow UI 上连续两轮。演示阶段通过不会自动关闭工作包，也不代表 PRD D01–D05 完整 Customer Demo Gate 或 Full Parity。当前整链尚未验收，不能把已通过的文本聊天或受控 Provider 测试当成客户演示完成。

## W01 基础工程

- `super-harness-app` 验证真实的公开 SDK `Run → Java 类型化 Tool → Artifact` 链路：确定性 Tool 生成内容，应用再通过公开 Artifact 服务发布，保留真实 Run/Session 来源并校验存储内容的哈希。
- `super-harness-harness` 是最小化、失败即关闭的证据校验器。必需用例缺失、显式失败、重复，或标为 PASS 却没有证据时，校验均失败。
- Maven Enforcer 拒绝直接或传递依赖 Haifa 产品、演示、测试 JAR，并拒绝直接依赖实现模块；ArchUnit 测试禁止生产代码导入 SDK 内部包和实现包。

## 构建与验证

使用 Java 21 和 Maven 3.9.15，或使用仓库提交的 Maven Wrapper。Wrapper 的 Maven 3.9.15 分发包固定了 SHA-256 校验值。

```powershell
.\mvnw.cmd -o -ntp verify   # Windows；Linux/macOS 使用 ./mvnw
mvn -o -ntp verify
./build-support/verify-clean-consumer.ps1
```

前两条 `verify` 命令使用配置好的本地 Maven 仓库，验证同一套完全离线的构建。每台机器首次使用 Wrapper 时需要联网下载一次 Maven 分发包，写入 `%USERPROFILE%\.m2\wrapper\dists`；此后可从缓存离线运行。`verify-clean-consumer.ps1` 从空的临时依赖缓存开始，通过配置好的 Maven 仓库只读文件镜像解析依赖，以证明构建没有使用 `D:\workspace\haifa-agent` 或任何 Reactor/源码路径依赖。

冒烟测试会输出 `W01_SDK_EVIDENCE`，其中包含真实 Run ID、Tool 别名、Artifact ID、Artifact SHA-256 和字节数。测试报告位于各模块的 `target/surefire-reports`。

## W18 冻结前端本地配置

以下命令只校验冻结前端、已记录的最小 BFF 补丁、仅连接 Haifa 的 loopback 配置，以及固定的 Node/pnpm 启动工具链，不启动服务：

```powershell
pwsh -NoProfile -File scripts/start-haifa-frontend.ps1 `
  -DryRun `
  -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow
pwsh -NoProfile -File tests/frontend/verify-haifa-frontend-profile.ps1
```

受保护的主工作树拓扑与正式启动流程见下文“启动主工作树供人工验收”；历史 W18 配置设计与验收记录已归档至 `private-docs/prompts/39-deerflow-parity-delivery/W18-Developing/references/`。仅通过上述配置检查，不代表聊天功能或双后端差分验收通过。

## W03 后续建议配置

Gateway 通过 `GET /api/suggestions/config` 暴露冻结前端的后续建议全局配置。以下两个环境变量在进程启动时读取一次，均为可选：

- `HAIFA_SUPER_HARNESS_SUGGESTIONS_ENABLED`：是否启用后续建议，默认启用。设为 `0`、`false` 或 `no` 时关闭；关闭状态下 `POST /api/threads/{thread_id}/suggestions` 不调用 Provider，直接返回空 `suggestions` 列表。
- `HAIFA_SUPER_HARNESS_SUGGESTIONS_MAX`：`max_suggestions` 上限，默认 `3`；非法值回落为默认值，取值被限制在 1 到 5 之间，并按该上限截断请求中的 `n`。

线程标题由 Gateway 在首个完成的 user+assistant 交换后按冻结 `TitleMiddleware` 的本地回退规则生成（首轮被取消时允许仅凭首个 user 消息生成），持久化到线程 metadata 与 `values.title`；显式重命名在 Run 运行期间返回 409，且不会被后续自动标题覆盖。

## 启动主工作树供人工验收

主工作树固定使用本地回环端口：Gateway `18018`、冻结前端 `33018`。重启时应保持数据目录不变；默认使用被 Git 忽略的 `local-tmp/main-service-data`，其中包含持久化 SQLite Runtime 和日志。重启时不要删除该目录或 Windows 凭据管理器中的 continuation key。

准备好本地 Haifa Agent `0.1.2-SNAPSHOT` 公开 JAR/BOM 和冻结前端依赖后，如果端口已占用，应先核对进程身份，只停止确认属于主工作树的进程，再构建并启动：

```powershell
mvn -o -ntp clean verify
pwsh -NoProfile -File scripts/start-main-super-harness.ps1
```

启动脚本会校验冻结 DeerFlow 源码和前端工具链的精确版本；在隐藏窗口中启动 Haifa Gateway 和现有的受保护前端启动器；在有界超时内等待 `/health`、`/api/v1/auth/me` 与前端就绪；最后输出访问地址、精确 PID、数据目录和日志路径。人工验收入口为 `http://127.0.0.1:33018/`。遇到端口占用时，脚本会拒绝启动，不会自行结束未知进程。若尚未运行完整验证，可传入 `-Build`，由脚本执行离线 Maven Reactor `package` 门禁。脚本退出后，服务仍会继续运行。

只需重建或重启 Gateway、而受保护的前端仍在运行时，先核对监听 `18018` 的 Java 进程并只停止该 PID，再运行相关 Maven 测试和 `mvn -o -ntp -pl super-harness-server -am package`，随后执行：

```powershell
pwsh -NoProfile -File scripts/start-main-super-harness.ps1 -GatewayOnly
```

此模式只检查并启动 Gateway，沿用原数据目录，不会启动第二个前端。确认 Gateway 健康后刷新浏览器。

代码合入后重启前，先用 `Get-NetTCPConnection -State Listen -LocalPort 18018,33018` 查明监听 PID，再用 `Get-CimInstance Win32_Process` 核对精确命令行；只停止确认属于主工作树的进程，然后重跑上述命令。禁止按进程名批量停止所有工作树中的 Java 或 Node。页面能打开仅证明服务就绪；真实模型、Tool 和双后端对齐仍需分别提供 Run/Playwright 证据。

### 客户演示 v0 doctor

客户演示固定 Oracle 位于 `contracts/customer-demo/customer-demo-v0-oracle.json`。在启动或派发演示任务前，先运行只读 doctor；它会核对冻结 DeerFlow 来源、Haifa Agent `0.1.2-SNAPSHOT` 本地 Maven 构件、主工作树端口及进程、同源模型目录、OS 凭据存在性和外置数据目录，但不会调用真实模型或读取凭据值：

```powershell
pwsh -NoProfile -File scripts/verify-customer-demo-doctor.ps1 `
  -OutputPath local-tmp/customer-demo-m0/doctor-live-read-only.json
```

只检查 Oracle、冻结来源和 SDK 构件时加 `-StaticOnly`。未获人工真实模型预算授权时，doctor 会将 `budget-authorization` 和整体结果保留为 `NOT_RUN`；不得仅为获得 `PASS` 擅自添加 `-BudgetApproved`，该开关只记录已经取得的明确人工授权，本身不会发起模型请求。脚本的正反合同测试为：

```powershell
pwsh -NoProfile -File tests/customer-demo/verify-customer-demo-doctor.ps1
```

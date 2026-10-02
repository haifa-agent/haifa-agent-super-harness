# 001 验收 `.cjs` 脚本功能与运行说明

| 项目 | 内容 |
| --- | --- |
| 文档编号 | 001 |
| 主题 | 仓库内 23 个 `.cjs` 脚本：Playwright 用例、Playwright 配置、受控 Provider、Steer 契约断言 |
| 适用仓库 | `D:\workspace\haifa-agent-super-harness`（分支 `main`） |
| 基线提交 | `5b8c8ac46cd152a25d6530c9926258291fcb120e` |
| 编写日期 | 2026-10-01 |
| 验证环境 | Windows 11；PowerShell 7（`pwsh`）；Node `v22.22.3`；corepack `pnpm 10.26.2`（离线）；本地 Playwright `1.59.1`（`frontend/node_modules`）；Chromium `chromium-1217` |

本文只说明仓库中真实存在的 `.cjs` 入口及其运行方法。标注“实测”的结论都在上述基线提交的真实工作树上执行过；未执行的检查在 §6 按 `NOT_RUN` 列出，不用静态推断或 `--list` 结果冒充运行证据。

冻结前端来源固定在 `bytedance/deer-flow@e545c28ac301e6f9feae247c513d8ea8f83df294`（`third-party/deerflow/manifest.json`），**不得修改**；该清单只记录 2 个已文档化的前端补丁（`0001-memory-bff-use-server-gateway`、`0002-active-steer-composer`）。这些 `.cjs` 脚本是本仓库自己的验收代码，不修改冻结源码。

## 0. 速查

| 想做什么 | 命令（在仓库根执行） |
| --- | --- |
| 只跑一个自含契约脚本（不需要服务） | `node tests\ui\dual-backend\verify-demo-l2-steer-contract.cjs` |
| 只列举冻结 UI 的 4 个用例（不启动浏览器/服务） | `node frontend\node_modules\@playwright\test\cli.js test --config tests/ui/smoke\playwright.config.cjs --list` |
| 跑冻结 UI + Haifa 网关 + 受控 Provider 的完整 L2 验收 | `pwsh -NoProfile -File scripts/ui/run-w18-ui-smoke.ps1 -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow` |
| 单独起受控假模型 Provider | `node tests\ui\smoke\loopback-openai-provider.cjs 0 <ready.json 路径>` |
| 双后端差分 / M2-MCP | `pwsh -NoProfile -File scripts/ui/run-w18-dual-backend.ps1 -Backend haifa` ／ `pwsh -NoProfile -File scripts/ui/run-w18-v2-m2-mcp.ps1 -Backend haifa` |
| 冻结 UI 文件与项目工作区 | `pwsh -NoProfile -File scripts/ui/run-w04-frozen-ui-files.ps1` |

## 1. 脚本清单与可运行性

| 路径 | 类型 | 作用 | 能否直接 `node` 运行 |
| --- | --- | --- | --- |
| `tests/ui/smoke/loopback-openai-provider.cjs` | 独立 HTTP 服务 | 受控 OpenAI 兼容 Provider（假模型） | 可以（见 §2.3） |
| `tests/ui/dual-backend/verify-demo-l2-steer-contract.cjs` | 自含断言脚本 | Steer 交换/终态契约检查 | 可以（见 §2.1） |
| `tests/ui/dual-backend/demo-l2-steer-contract.cjs` | 库模块 | 被上面脚本与 `differential.spec.cjs` `require` | 单跑无输出、无副作用（见 §2.2） |
| `tests/ui/smoke/*.spec.cjs`（11 个） | Playwright 用例 | 冻结 UI 浏览器验收（基础 4 例 + 各 V2 专项 profile） | 不可以：需要 Playwright runner + 运行中的前端与网关 |
| `tests/ui/dual-backend/differential.spec.cjs`、`v2-m1-model-file.spec.cjs`、`v2-m2-mcp.spec.cjs`（3 个） | Playwright 用例 | DeerFlow 参考后端 vs Haifa 差分 | 同上 |
| `tests/ui/files/w04-files-project-workspace.spec.cjs`、`w04-archive-restore-delete.spec.cjs`（2 个） | Playwright 用例 | 冻结 UI 文件/项目工作区 | 同上 |
| `tests/ui/smoke/playwright.config.cjs`、`tests/ui/dual-backend/playwright.config.cjs`、`tests/ui/dual-backend/v2-m2-playwright.config.cjs`、`tests/ui/files/playwright.config.cjs`（4 个） | Playwright 配置 | 测试目录、端口、超时、证据输出 | 不可以：只作为 `--config` 参数 |

`local-tmp/m4-main-c112012-20260928-evidence/` 下的 2 个 `.cjs` 是历史证据副本，不是可运行的验收入口。前端 `frontend/scripts/` 下的 3 个 `.mjs` 脚本与本文主题无关，简述见附录 A。

## 2. 能独立运行的 `.cjs`

### 2.1 `verify-demo-l2-steer-contract.cjs` — Steer 契约断言

功能：不依赖服务，用 `node:assert/strict` 校验“同 Run Steer”交换与终态契约（端点 `POST /api[/langgraph]/threads/{thread_id}/runs/stream`、`X-Haifa-Run-Input: steer` 头、HTTP 200、`Content-Location` 指向同一 Run、instruction 与既有消息隔离等），构造 1 个正当样本加多个反例。

```powershell
node tests\ui\dual-backend\verify-demo-l2-steer-contract.cjs
```

实测输出：`W18_DEMO_L2_STEER_CONTRACT PASS assertions=20`，exit 0。工作目录不影响结果（脚本内路径基于 `__dirname`）。

### 2.2 `demo-l2-steer-contract.cjs` — 纯库模块

导出 `verifySteerExchange`、`verifySteerTerminal`，被 §2.1 的验证脚本和 `tests/ui/dual-backend/differential.spec.cjs`（第 9 行 `require("./demo-l2-steer-contract.cjs")`）复用。直接 `node` 运行它没有任何输出或断言，属于正常行为，不是“跑失败”。

### 2.3 `loopback-openai-provider.cjs` — 受控假模型 Provider

功能：本地 OpenAI 兼容 Provider，让 UI 用例在不调用真实模型、不需要凭据的前提下得到确定性回答。

```powershell
node tests\ui\smoke\loopback-openai-provider.cjs 0 target\provider-manual.ready.json
```

- 参数：`<port> <readyFile> path>`。端口传 `0` 表示由 OS 分配；`readyFile` 必填。
- 就绪元数据用 `flag: "wx"` 新建（**文件已存在会启动失败**，重跑前先删除），内容并同时打印为：
  `{"type":"ready","address":"http://127.0.0.1:<port>","port":<port>,"pid":<pid>}`
- 端点：`GET /health` 返回 `{"status":"ok"}`（runner 用它判定就绪，并校验 `pid` 与 `address` 的安全性，必须为 `127.0.0.1`）；模型调用走 `<address>/v1`（`w18-ui-smoke` runner 注册受控模型时用的 base URL）；模型名为 `w18-controlled-model`。
- 行为要点（读源码确认）：按请求中的用户文本返回确定性内容（`Controlled provider: <prompt>` 形式），包含 Tool 结果、上传路径相关分支、`__polish_slow__` 的 4 秒延迟分支等；只监听 `127.0.0.1`，不联网、不需要密钥。
- 退出：响应 `SIGINT`/`SIGTERM` 并关闭 server；手动运行时请自行记录 PID 与端口并在用完时关闭。
- 本轮未实际启动它（见 §6），以上为源码与 runner 行为核对结果。

## 3. Playwright 用例（`*.spec.cjs`）

### 3.1 为什么不能 `node xxx.spec.cjs`

用例用 `@playwright/test` 的 `test()`/`expect()`，必须由 Playwright runner 加载；且它们断言的对象是**真实运行的冻结 Next 前端 + Haifa 网关**（部分用例还需受控 Provider 与注册好的受控模型）。四个 config 都用相对路径 `path.resolve(__dirname, "../../../frontend/node_modules/@playwright/test")` 加载本地 Playwright，因此工作目录在仓库根即可，但**必须先在该 worktree 的 `frontend/` 完成离线依赖安装**。

各 config 的关键差异（读源码确认）：

| config | testMatch | 端口/baseURL 缺省 | 证据输出 | 强制要求 |
| --- | --- | --- | --- | --- |
| `tests/ui/smoke/playwright.config.cjs` | `W18_PLAYWRIGHT_SPEC`，缺省 `w18-ui-smoke.spec.cjs` | `E2E_APP_URL` ?? `http://127.0.0.1:${E2E_FRONTEND_PORT ?? 35618}` | `W18_EVIDENCE_DIR` ?? `target/w18-ui-smoke-evidence/playwright` 下的 `artifacts/`、`results.json` | 无（但受控用例另需 `W18_PROVIDER_URL`） |
| `tests/ui/files/playwright.config.cjs` | 固定 2 个 w04 spec | `E2E_APP_URL` ?? `…:${E2E_FRONTEND_PORT ?? 25618}` | `W04_EVIDENCE_DIR` ?? `target/w04-frozen-ui-files-evidence` | 两个 spec 在 import 时要求 `W04_DATA_ROOT` 为**绝对路径** |
| `tests/ui/dual-backend/playwright.config.cjs` | 固定 `differential.spec.cjs`、`v2-m1-model-file.spec.cjs` | 无缺省，直接用 `E2E_APP_URL` | `W18_DIFFERENTIAL_EVIDENCE` | 缺少证据目录直接抛 `W18_DIFFERENTIAL_EVIDENCE is required` |
| `tests/ui/dual-backend/v2-m2-playwright.config.cjs` | 固定 `v2-m2-mcp.spec.cjs` | 无缺省，直接用 `E2E_APP_URL` | `W18_DIFFERENTIAL_EVIDENCE` | 还要求 `W18_MCP_SLICE === "1"`，否则抛 `Explicit V2-M2 MCP evidence directory and slice are required` |

四个 config 都固定 `workers: 1`、`retries: 0`，项目只用 `chromium`。

### 3.2 推荐入口：仓库的 PowerShell runner

runner 负责端口预检、构建、起服务、注入全部 env、跑 Playwright、结束清理。**默认槽位端口按公式校验，参数写错会直接抛错**：Gateway = `10618 + 10000×slot`，Frontend = `15618 + 10000×slot`。

| runner | 覆盖用例 | 缺省槽位端口 | 关键参数 |
| --- | --- | --- | --- |
| `scripts/ui/run-w18-ui-smoke.ps1` | `tests/ui/smoke/<profile>.spec.cjs` | slot 2 → `30618`/`35618`；受控 Provider 用 OS 分配端口（`-ProviderPort` 必须为 0） | `-AgentSlot 1..3`、`-GatewayPort`、`-FrontendPort`、`-SourceRepository`、`-DataDirectory`、`-SkipBuild`，profile 开关见下 |
| `scripts/ui/run-w05-demo-artifact-ui.ps1` | `w05-demo-artifact.spec.cjs`（`-DemoArtifact`） | slot 1 → `20618`/`25618` | 数据目录 `local-tmp/slot-1-w05-m2` |
| `scripts/ui/run-w05-interaction-smoke.ps1` | `w05-interaction.spec.cjs`（`-Interaction`，走 Maven 测试 classpath fixture，不需要 exec JAR） | slot 2 → `30618`/`35618` | 数据目录 `target/agent-slot-2-interaction-data` |
| `scripts/ui/run-w03-input-polish-ui.ps1` | `w03-input-polish.spec.cjs`，同脚本连跑两轮独立 data-root | slot 1 → `20618`/`25618` | 证据 `local-tmp/slot-1-w03-input-polish-ui/evidence` |
| `scripts/ui/run-w04-frozen-ui-files.ps1` | 2 个 w04 spec，附加 `--repeat-each 2` | 固定 `20618`/`25618` | 数据目录 `target/agent-slot-1-ui-data` |
| `scripts/ui/run-w18-dual-backend.ps1` | `differential.spec.cjs`、`v2-m1-model-file.spec.cjs` | `40618`/`45618`，受控 Provider `46618` | `-Backend reference\|haifa`（必填）、`-Round 1\|2`、`-SkipBuild`、`-Grep` |
| `scripts/ui/run-w18-v2-m2-mcp.ps1` | `v2-m2-mcp.spec.cjs` | `20618`/`25618`，Provider `46618`，另有报告端口 `20628`/`20629`/`20630` | `-Backend reference\|haifa`（必填）、`-Round 1\|2`、`-SkipBuild`、`-Grep` |

`run-w18-ui-smoke.ps1` 的 profile 开关互斥，一次只能选一个，对应 spec 与数据/证据目录如下：

| 开关 | spec | 数据目录缺省 |
| --- | --- | --- |
| （默认，无开关） | `w18-ui-smoke.spec.cjs`（4 个独立用例） | `target/agent-slot-<n>-data` |
| `-ActiveReconnect` | `w05-active-reconnect.spec.cjs` | 同上 |
| `-DemoArtifact` | `w05-demo-artifact.spec.cjs` | `local-tmp/slot-<n>-w05-m2` |
| `-TitleSuggestions` | `w03-title-suggestions.spec.cjs` | `target/agent-slot-<n>-title-suggestions-data` |
| `-InputPolish` | `w03-input-polish.spec.cjs` | `target/agent-slot-<n>-input-polish-data` |
| `-V2ModelSwitch` | `w03-v2-model-switch.spec.cjs` | `local-tmp/slot-<n>-w03-v2-model-switch/data/ui` |
| `-V2FileContext` | `w04-v2-file-context.spec.cjs` | `local-tmp/slot-<n>-w04-v2-file-context/ui-data` |
| `-V2Skill` | `w15-v2-skill.spec.cjs` | `local-tmp/slot-<n>-w15-v2-skill/data/ui` |
| `-V2Diagnostics` | `w21-v2-diagnostics.spec.cjs` | `local-tmp/slot-<n>-w21/data/ui` |
| `-LiveDeepSeek` | `w18-deepseek-live.spec.cjs`（真实外部模型调用，只在被授权时跑） | `target/agent-slot-<n>-deepseek-data` |
| `-Interaction` | `w05-interaction.spec.cjs` | `target/agent-slot-<n>-interaction-data` |

典型用法：

```powershell
# 基础 4 例（含 Maven package 与按槽位网关地址构建前端）
pwsh -NoProfile -File scripts/ui/run-w18-ui-smoke.ps1 `
  -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow

# 自己的槽位与数据目录；第二轮的暖轮复用已验证构建
pwsh -NoProfile -File scripts/ui/run-w18-ui-smoke.ps1 -AgentSlot 3 -GatewayPort 40618 -FrontendPort 45618 `
  -V2Diagnostics -DataDirectory local-tmp/slot-3-w21/data/ui-warm -SkipBuild
```

### 3.3 runner 生命周期（它替你做了什么）

以 `run-w18-ui-smoke.ps1` 为例（读源码确认，其他 runner 同构）：

1. 校验槽位端口公式与 profile 互斥，逐端口用 `Get-NetTCPConnection` 与 loopback `TcpListener` 双重预检；数据目录必须落在本 worktree 内。
2. 写入受管环境变量（`SUPER_HARNESS_*`、`DEER_FLOW_INTERNAL_GATEWAY_BASE_URL`、`E2E_APP_URL`、`E2E_FRONTEND_PORT`、`W18_EVIDENCE_DIR`、`W18_PLAYWRIGHT_SPEC/TIMEOUT_MS/TRACE`、`COREPACK_ENABLE_NETWORK=0` 等），结束时恢复原值。
3. 调 `scripts/frontend/verify-haifa-frontend-profile.ps1` 校验冻结来源、已记录补丁与启动工具链，结果写入 `profile-<stamp>.json`。
4. 非 `-SkipBuild` 时执行 `mvn.cmd -o -ntp -pl super-harness-server -am package` 与 `frontend/` 下的 `corepack pnpm build`（前端构建会读 `DEER_FLOW_INTERNAL_GATEWAY_BASE_URL`，因此必须用本次槽位网关端口构建）。
5. 启动受控 Provider（`node loopback-openai-provider.cjs 0 <ready.json>`，15 秒就绪超时，校验 `address`/`pid` 与 `GET /health`），把地址写入 `W18_PROVIDER_URL`。
6. 启动网关：默认 `java -jar super-harness-server/target/super-harness-server-0.1.0-SNAPSHOT-exec.jar`，等 `/health` 为 `ok`（30 秒）；`-Interaction` 改为用 Maven surefire 跑 `InteractionAcceptanceServerTest`（`-DforkCount=0`，并在进程环境置 `W05_INTERACTION_ACCEPTANCE=1`）。
7. 读 `/api/managed-models`，创建或更新受控模型（`w18-controlled`；`-LiveDeepSeek` 为 `w18-deepseek-live`），并保存 `managed-model-<stamp>.json` 证据。
8. 用 `node frontend/node_modules/next/dist/bin/next start --hostname 127.0.0.1 --port <FrontendPort>` 起前端（60 秒内等 `/workspace/chats/new` 返回 200）。
9. 在仓库根执行 `node frontend/node_modules/@playwright/test/cli.js test --config tests/ui/smoke/playwright.config.cjs`，用其退出码决定 runner 结果。
10. `finally`：按 CIM `ParentProcessId` 递归收集子进程（新→旧）后强制结束，再结束自己启动的 Provider/网关/前端，并恢复环境变量。

### 3.4 裸跑 Playwright 的最小配方

需要你自行准备：冻结前端 `next start`（构建时代理目标必须是本次网关端口）、Haifa 网关 exec JAR（独立 data-root 与对应凭据）、受控 Provider，然后注入 runner 用到的那组变量：

```powershell
$env:E2E_APP_URL              = 'http://127.0.0.1:35618'   # 前端地址（不是网关地址）
$env:E2E_FRONTEND_PORT        = '35618'
$env:W18_EVIDENCE_DIR         = "$PWD\target\manual-evidence"
$env:W18_PROVIDER_URL         = 'http://127.0.0.1:<provider 端口>'
$env:W18_PLAYWRIGHT_SPEC      = 'w18-ui-smoke.spec.cjs'
node frontend\node_modules\@playwright\test\cli.js test --config tests/ui/smoke\playwright.config.cjs
# 只跑其中一例：追加 --grep "reload restores"
```

各用例读取的变量（实测检索 `tests/**/*.cjs` 的 `process.env`）：

| 变量 | 谁需要 | 行为 |
| --- | --- | --- |
| `E2E_APP_URL` | 全部用例 | 前端基址；w18-ui-smoke/w04 有端口缺省，两个 dual-backend config 无缺省 |
| `E2E_FRONTEND_PORT` | w18-ui-smoke（缺省 35618）、w04（缺省 25618）、w03 系列 | 仅用于拼基址缺省值 |
| `W18_EVIDENCE_DIR` | `w03-v2-model-switch`、`w04-v2-file-context`、`w15-v2-skill`、`w21-v2-diagnostics`、config 输出目录 | 证据根目录 |
| `W18_PLAYWRIGHT_SPEC` / `W18_PLAYWRIGHT_TIMEOUT_MS` / `W18_PLAYWRIGHT_TRACE` | config | 选择 spec、超时、trace 策略 |
| `W18_PROVIDER_URL` | `w03-input-polish.spec.cjs`（直接对 Provider 做对照） | 受控 Provider 基址 |
| `W18_MODEL_NAME` | `w18-deepseek-live.spec.cjs`（缺省 `w18-deepseek-live`） | 模型名 |
| `W04_DATA_ROOT` / `W04_EVIDENCE_DIR` | w04 两个 spec / w04 config | 必须在 import 时给出绝对路径 |
| `W18_DIFFERENTIAL_EVIDENCE` | 两个 dual-backend config | 缺失直接抛错 |
| `W18_DIFFERENTIAL_BACKEND` / `_GATEWAY` / `_ROUND` | 差分用例 | runner 按 `-Backend`/`-Round` 注入 |
| `W18_DISABLED_MODEL` / `W18_DISABLED_MODEL_CONFIGURED`、`DEER_FLOW_AUTH_DISABLED` | `v2-m1-model-file.spec.cjs` | 禁用模型分支与双身份前置说明 |
| `W18_MCP_MANIFEST` / `W18_MCP_CONFIG` / `W18_MCP_SLICE` | `v2-m2-mcp.spec.cjs` / `v2-m2-playwright.config.cjs` | MCP fixture 清单、配置与 slice 开关 |

### 3.5 常见坑

- **前端构建时代理目标**：`frontend/next.config.js` 的 rewrite 读 `DEER_FLOW_INTERNAL_GATEWAY_BASE_URL`，缺省回落到 `http://127.0.0.1:8001`。未带槽位变量先 `corepack pnpm build`，再 `-SkipBuild` 跑用例，页面会提示 `Models couldn't be loaded`、前端 stderr 出现 `/api/models` 代理 `8001` 的 `ECONNREFUSED`；只能在同一槽位配置下重新构建，或确认产物就是本次槽位构建的。
- **裸跑不会清理**：直接调 Playwright 不会结束网关/前端/Provider，也不会释放端口；请按 PID 关闭并确认 `20618/25618`、`30618/35618`、`40618/45618` 与本轮 Provider 端口已释放。
- **`-SkipBuild` 的前提**：只能复用在**同一源码 SHA、同一槽位网关地址**下已构建的 exec JAR 与 `.next`；换槽位或改代码后必须重建。
- **数据目录隔离**：不同启动方式/不同 profile 不得共用 data-root，否则会报 continuation key 不可用（数据库已存在但凭据缺失时必须失败，禁止静默换钥）。
- **`-LiveDeepSeek`**：会真实调用 `https://api.deepseek.com`（凭据引用 `os://DEEPSEEK_API_KEY`），只在明确授权时运行，且不得作为受控用例的替代。
- **过滤不是轮次结果**：两个 dual runner 的 `-Grep` / `--grep` 只用于排障，不能用来掩盖失败用例或充当正式轮次证据。

## 4. 端口与数据目录前提

| 用途 | 端口 |
| --- | --- |
| 主目录（人工验收，不参与子槽公式） | Gateway `18018`、Frontend `33018` |
| 子槽 1 / 2 / 3 | `20618`/`25618`、`30618`/`35618`、`40618`/`45618` |
| W18 差分与 M2-MCP 的受控 Provider | `46618`（不属于任何槽位，也需绑定预检） |
| M2-MCP 报告/区域/失败 fixture | `20628`、`20629`、`20630` |

启动前先看 `netsh interface ipv4 show dynamicport tcp` 与 `netsh interface ipv4 show excludedportrange protocol=tcp`，再逐端口用 loopback `TcpListener` 实际绑定预检——`Get-NetTCPConnection` 只能发现监听者，发现不了 NAT 保留段。同一槽位同一时刻只跑一个任务。

## 5. 本次验证记录（实测）

| # | 命令 | 结果 | 说明 |
| --- | --- | --- | --- |
| 1 | `node frontend\node_modules\@playwright\test\cli.js --version` | PASS | `Version 1.59.1`，本地 Playwright 可用 |
| 2 | `node tests\ui\dual-backend\verify-demo-l2-steer-contract.cjs` | PASS | `W18_DEMO_L2_STEER_CONTRACT PASS assertions=20`，exit 0 |
| 3 | `… cli.js test --config tests/ui/smoke\playwright.config.cjs --list` | PASS（仅列举） | `Total: 4 tests in 1 file`，exit 0；未启动浏览器 |
| 4 | 同上，`--config tests/ui/dual-backend\playwright.config.cjs`（不带 `W18_DIFFERENTIAL_EVIDENCE`） | FAIL（预期） | `Error: W18_DIFFERENTIAL_EVIDENCE is required`，exit 1 |
| 5 | 同上，`--config tests/ui/files\playwright.config.cjs`（不带 `W04_DATA_ROOT`） | FAIL（预期） | 两个 spec 各报 `Error: W04_DATA_ROOT must be an absolute path` → `No tests found`，exit 1 |
| 6 | 打印 chromium/firefox/webkit 的 `executablePath()` 并检查存在性 | PASS | chromium `true`（`chromium-1217`）；firefox/webkit `false`，但所有 config 只用 chromium |
| 7 | 核对 runner 参数与端口（`run-w18-ui-smoke`、`run-w04-frozen-ui-files`、`run-w18-dual-backend`、`run-w18-v2-m2-mcp`、`run-w05-demo-artifact-ui`、`run-w05-interaction-smoke`、`run-w03-input-polish-ui`） | PASS（读源码） | 与 §3.2 表格一致 |
| 8 | `third-party/deerflow/manifest.json` 上游 pin 与补丁清单 | PASS（读文件） | `e545c28…`，仅 2 个前端补丁，本文脚本不在补丁范围 |

## 6. 未执行项与边界

- 任何需要“前端 + 网关 + Provider”齐活的 Playwright 轮次：**`NOT_RUN`**。本轮不占槽位、不启动服务，只做了静态/列举类检查。
- `loopback-openai-provider.cjs` 未实际启动（源码与 runner 行为已核对）；其真实就绪行为由 runner 的 `provider-<stamp>.ready.json` 与 `/health` 校验覆盖，需要时再单独采集。
- `mvn package`、`corepack pnpm build`、`-LiveDeepSeek` 真实模型调用均未执行。
- §5 第 3/4/5 项只证明“配置与用例能/不能加载”，**不能**替代功能验收；正式轮次仍须按仓库规则跑连续两轮并保留原始 trace/日志。
- 本文档不改变任何冻结文件；`.cjs` 脚本本身的修改仍需按工作包流程走定向测试与门禁。

## 附录 A：相关但非本文主题的前端 `.mjs` 脚本

`frontend/scripts/` 下有 3 个 `.mjs`（未改动的上游文件，不属于上述 23 个 `.cjs`），因与“跑脚本”相关，简记如下：

| 路径 | 作用 | 运行（工作目录必须是 `frontend/`） |
| --- | --- | --- |
| `dev.mjs` | 包装 `next dev`；默认 bundler 为 `webpack`，可用 `DEER_FLOW_DEV_BUNDLER=turbo\|webpack` 覆盖；`--` 之后参数透传 | `corepack pnpm dev -- --port 3302` |
| `measure-route-assets.mjs` | 两次生产构建 + 临时 `next start`，统计 6 条路由的 `/_next/static/**` JS/CSS 字节，`--check` 对照 `frontend/performance-budgets.json` | `corepack pnpm perf:check` |
| `sync-capability-catalog.mjs` | 由 `backend/packages/harness/deerflow/capabilities/builtin.json` 生成 `frontend/src/core/capabilities/builtin.demo.json` | `corepack pnpm catalog:sync` |

要留意的两点（本机实测）：`sync-capability-catalog.mjs` 解析出的来源是 `<repo>/backend/...`，本仓库该路径不存在（冻结后端在 `deer-flow/backend/`），因此直接运行报 `ENOENT ... builtin.json`、exit 1（先读后写，不会污染目标文件）；`measure-route-assets.mjs` 内部用 PATH 上的 `pnpm` 启动子构建，而本机只有 `pnpm.cmd`/`pnpm.ps1`，`spawn('pnpm')` 报 `ENOENT`，故整跑会失败。前置条件一律用 Corepack 离线 `pnpm 10.26.2`；三个脚本的单元测试入口：`cd frontend; corepack pnpm exec rstest run tests/unit/scripts`（本轮实测 4 文件 11 用例通过，1.82 秒）。

## 7. 修订记录

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| 001 | 2026-10-01 | 初版主题误写为 `.mjs`（文件名 `001-scripts-runbook.md`）；同日按人工确认“mjs 是拼写错误”，改为以 `.cjs` 为主题并更名为 `001-cjs-scripts.md`，`.mjs` 内容降为附录 A |

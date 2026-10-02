# 脚本目录

从仓库根目录使用 PowerShell 7（`pwsh`）调用。文件名和参数保持原样，分类后需要使用下表中的新目录。

| 目录 / 入口 | 用途 |
| --- | --- |
| `test-targeted.ps1` | 定向 Maven 测试，固定带 `-am`，读取本 worktree 的 SDK 选择 |
| `verify-full.ps1` | 完整离线 Maven 门禁与服务端构建戳 |
| `start-main-super-harness.ps1` | 主工作树服务启动 |
| `frontend/` | 冻结前端来源、profile 校验与启动 |
| `contracts/` | 冻结 DeerFlow 协议与 Oracle 校验 |
| `resources/` | 资源、许可证清单生成与校验 |
| `doctor/` | 客户演示 V1 / V2 前置条件检查 |
| `ui/` | W03 / W04 / W05 / W18 UI 验收、双后端差分与 MCP 轮次 |
| `mcp/` | 本地 V2 MCP fixture 服务启动、停止与验证 |
| `evidence/` | 客户演示证据校验与证据报告生成 |
| `governance/` | SHPM 工作包交付审计 |
| `lib/` | 共用函数，由入口脚本加载 |

```powershell
pwsh -NoProfile -File scripts/test-targeted.ps1 -Test LocalServerConfigurationTest
pwsh -NoProfile -File scripts/frontend/start-haifa-frontend.ps1 -DryRun -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow
pwsh -NoProfile -File scripts/doctor/verify-customer-demo-v2-doctor.ps1
```

原根目录中的其他 `.ps1` 已移入对应子目录；例如 `scripts/run-w18-ui-smoke.ps1` 改为 `scripts/ui/run-w18-ui-smoke.ps1`。参数、端口、数据目录与证据输出位置沿用原约定。构建环境、SDK 版本选择及验收前置条件见根目录 [README](../README.md) 和 [AGENTS.md](../AGENTS.md)。

脚本自身的回归检查见 [tests/checks](../tests/README.md)，浏览器用例见 `tests/ui/`。新增入口按用途放入相应子目录；可复用函数放入 `lib/`。

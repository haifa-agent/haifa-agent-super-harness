# 测试目录

这里保存仓库脚本的回归检查、验收夹具和浏览器用例。Java 单元测试继续位于各 Maven 模块的 `src/test/java/`。

| 目录 | 内容 | 运行特点 |
| --- | --- | --- |
| `checks/build/` | SDK 基线、构建入口与进程夹具检查 | 构建入口的 Maven 用例通过 `-Maven` 显式启用 |
| `checks/contracts/` | 冻结协议和资源许可证校验器的正反例 | DeerFlow 校验需要冻结来源 checkout |
| `checks/frontend/` | 前端 profile 与启动器的正反例 | 检查配置及 dry-run |
| `checks/evidence/` | 证据报告聚合器的正反例 | 需要相应的 prompt fixture 输入 |
| `checks/customer-demo/` | V1 doctor 与证据校验器的正反例 | 使用临时 fixture |
| `checks/customer-demo-v2/` | V2 Oracle、doctor、MCP launcher 检查与夹具 | MCP launcher 检查会构建并启动本地 fixture 服务 |
| `ui/smoke/` | 冻结 UI 与专项 profile、受控 Provider、历史证据 | 由 `scripts/ui/` 中的 runner 管理服务和证据 |
| `ui/files/` | 文件、项目工作区、归档恢复删除的 UI 用例 | 使用独立端口和数据目录 |
| `ui/dual-backend/` | DeerFlow / Haifa 差分与 M1 / M2 MCP 用例 | 差分需要对应后端前置条件 |

从仓库根目录调用指定检查，不默认运行整套 UI：

```powershell
pwsh -NoProfile -File tests/checks/build/verify-haifa-version-baseline.ps1
pwsh -NoProfile -File tests/checks/build/verify-build-entry.ps1
node tests/ui/dual-backend/verify-demo-l2-steer-contract.cjs
```

## 旧目录对应关系

| 原目录 | 当前目录 |
| --- | --- |
| `tests/build/` | `tests/checks/build/` |
| `tests/contracts/` | `tests/checks/contracts/` |
| `tests/frontend/` | `tests/checks/frontend/` |
| `tests/evidence/` | `tests/checks/evidence/` |
| `tests/customer-demo/` | `tests/checks/customer-demo/` |
| `tests/customer-demo-v2/` | `tests/checks/customer-demo-v2/` |
| `tests/w18-ui-smoke/` | `tests/ui/smoke/` |
| `tests/w04-frozen-ui-files/` | `tests/ui/files/` |
| `tests/w18-dual-backend/` | `tests/ui/dual-backend/` |

文件名、Oracle 断言和 JSON fixture 内容保持原样。`ui/smoke/evidence/` 保存历史记录，JSON 内的路径和 SHA 反映当时的执行现场，查找迁移后的文件时使用上表；新运行的原始证据仍按各 runner 的约定写到外置目录。

UI smoke 的参数与 profile 说明见 [ui/smoke/README.md](ui/smoke/README.md)，脚本入口见 [scripts/README.md](../scripts/README.md)。

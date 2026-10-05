# 脚本目录导航

本目录是仓库的脚本入口集合。统一从仓库根目录使用 PowerShell 7（`pwsh`）调用；核心逻辑尽量放在 Python
中，`.ps1` / `.sh` 只做环境准备、调用与参数透传。根目录只保留最常用的通用入口，其余按用途分类到子目录，
可复用函数放在 `lib/`。

## 顶层入口

| 文件 | 用途 |
| --- | --- |
| `test-targeted.ps1` | 定向 Maven 测试，固定带 `-am`，读取本 worktree 的 SDK 版本选择 |
| `verify-full.ps1` | 完整离线门禁（`mvn clean verify`）与服务端构建戳 |
| `verify-scripts.ps1` | 运行 `tests/checks/build/` 与 `tests/checks/ui/` 清单内的全部脚本回归检查 |
| `start-main-super-harness.ps1` | 主工作树 Gateway（18018）+ 冻结前端（33018）启动 |
| `check_w08_oracle12.py` | W08 Oracle12 静态一致性/截断检查（Python） |
| `w08_frozen_steps.mjs` | Oracle12 的 Node 桥，执行冻结 TypeScript 函数，断言在 Python 中 |

## 子目录

### `frontend/` — 冻结前端来源、profile 与启动

| 文件 | 用途 |
| --- | --- |
| `start-haifa-frontend.ps1` | 校验冻结前端来源/profile 并启动；`-DryRun` 只校验 |
| `verify-deerflow-frontend.ps1` | 冻结前端来源哈希校验 |
| `verify-haifa-frontend-profile.ps1` | Haifa 前端启动 profile 校验 |

### `contracts/` — 冻结协议与来源契约

| 文件 | 用途 |
| --- | --- |
| `verify-deerflow-contracts.ps1` | 冻结 DeerFlow 协议、来源、Oracle 与资源清单校验 |

### `resources/` — 资源与许可证清单

| 文件 | 用途 |
| --- | --- |
| `generate-resource-license-inventory.ps1` | 生成冻结资源/许可证清单 |
| `verify-resource-license-inventory.ps1` | 校验资源/许可证清单 |
| `import_w15_skill_resources.py` | 导入 W15 冻结 Skill 资源（Python） |
| `skill_license_inventory.py` | Skill 资源许可证清单（Python） |

### `doctor/` — 客户演示前置检查（只读）

| 文件 | 用途 |
| --- | --- |
| `verify-customer-demo-doctor.ps1` | 客户演示 v0 只读 doctor |
| `verify-customer-demo-v2-doctor.ps1` | 客户演示 v2 只读 doctor |

### `ui/` — 冻结 UI 验收、双后端差分与 L3

| 文件 | 用途 |
| --- | --- |
| `run-w18-ui-smoke.ps1` | 通用冻结 UI smoke runner，按 profile 选择用例（见下） |
| `run-w03-input-polish-ui.ps1` | W03 输入打磨 UI 两轮 |
| `run-w04-frozen-ui-files.ps1` | W04 文件上下文 UI 验收 |
| `run-w05-demo-artifact-ui.ps1` | W05 客户演示 M2 Artifact UI 验收（转调 `run-w18-ui-smoke.ps1`） |
| `run-w05-interaction-smoke.ps1` | W05 交互 smoke（转调 `run-w18-ui-smoke.ps1`） |
| `run-w18-dual-backend.ps1` | W18 冻结前端双后端差分（`reference` / `haifa`） |
| `run-w18-v2-m2-mcp.ps1` | W18 V2-M2 MCP 双后端差分单轮 |
| `run-w18-v2-m2-mcp-rounds.ps1` | W18 V2-M2 MCP 冷/暖四轮包装 |
| `run-w24-l3-rounds.ps1` | L3 Golden Scenario 两轮包装（V2-M6） |
| `run_l3_golden_scenario.py` | L3 Golden Scenario runner（真实模型 + MCP 8091） |

`run-w18-ui-smoke.ps1` 的 profile 开关：`-Interaction`、`-DemoArtifact`、`-ActiveReconnect`、
`-TitleSuggestions`、`-InputPolish`、`-V2ModelSwitch`、`-V2FileContext`、`-V2Skill`、`-V2Diagnostics`、
`-W08Catalog`、`-LiveDeepSeek`、`-W15LiveSkill`、`-W15PackageDifferential`、`-UxV1DefaultTools`、
`-UxV1AgentSoul`；通用开关：`-AgentSlot`、`-GatewayPort`、`-FrontendPort`、`-ProviderPort`、`-DataDirectory`、
`-CaseTitle`、`-PreflightOnly`、`-SkipServerBuild`、`-SkipBuild`。

### `mcp/` — 本地 V2 MCP fixture

| 文件 | 用途 |
| --- | --- |
| `start-v2-test-mcp-servers.ps1` | 启动/停止两个独立 V2 本地测试 MCP server（`v2-reporting` / `v2-region`） |
| `verify-v2-test-mcp-servers.ps1` | 一轮 V2 本地 MCP transport 验证 |

### `evidence/` — 证据校验与报告

| 文件 | 用途 |
| --- | --- |
| `generate-evidence-report.ps1` | 从 SHPM 快照导出证据报告 |
| `verify-customer-demo-evidence.ps1` | 客户演示 v0 证据包校验与聚合 |

### `governance/` — SHPM 治理与门禁建议

| 文件 | 用途 |
| --- | --- |
| `audit-work-package-delivery.ps1` | SHPM 与实际交付/worktree 审计 |
| `audit_disconnected_branches.py` | 用相同文件树连接新旧历史，批量核对旧分支内容并导出未确认差异 |
| `change_scope.py` | 按 main 以来的改动给出所需门禁（Python） |
| `pr_review_digest.py` | 只读读取 PR 机器人审查并输出 `botVerdict`/`findings`（Python） |
| `shpm_dispatch.py` | 一次性完成 task create、sprint link、task start、assign create |
| `shpm_record_gates.py` | 从门禁摘要 JSON 与 UI 证据目录登记结果 |

历史更换后的旧分支内容审计（只读 Git，不调用模型、不删除分支）：

```powershell
python scripts/governance/audit_disconnected_branches.py `
  --inventory local-tmp/governance/reviews/worktree-release-20261004/recommendations.json `
  --old-main 9111e66edab07fedc7301b83421b60b7d969391f `
  --bridge c112012e147cc11daec891af3d602aeefcdff1de `
  --output local-tmp/governance/reviews/disconnected-content-<新批次名>
```

`--old-main` 是历史更换前的主线，`--bridge` 是更换后具有相同完整文件树的提交；脚本验证树相等、
桥接点属于当前 `main`、仓库不是浅克隆。清单只选择 `history_relation=UNRELATED_HISTORY` 的条目，
每个 worktree 的路径、HEAD、分支和 dirty/untracked 状态会重新读取。输出目录必须不存在，每个 Git
子进程默认限时 60 秒，进度打印为 JSON。

`HISTORY_CONFIRMED` 证明旧提交经祖先关系和相同文件树进入过主线；`CONTENT_ACCOUNTED_FOR` 证明净变更有
精确文件内容/模式或完整变更记录证据；`REVIEW_REQUIRED` 保留无法确认的文件。内容相似、同名文件、
其他路径同 blob、历史曾出现某 blob 或忽略空白的 patch-id 均不能单独判定通过。完整记录在 `audit.json`，
中文摘要在 `README.md`，未确认的分支净变更及其与当前主线的差异在 `branches/<worktree>/`。
每次完整运行还生成 `history-index.json`；之后可加 `--history-cache <该文件>` 复用主线历史索引，
仅在旧主线和当前主线 SHA 完全相同时允许复用，每个 worktree 的引用和工作树状态仍重新读取。
历史已进入主线不等于当前行为验收；未跟踪/忽略文件和嵌套仓库仍须单独处理。本脚本不授权删除，也不修改 SHPM。

### `lib/` — 共用函数（由入口脚本加载）

| 文件 | 用途 |
| --- | --- |
| `preflight.ps1` | 入口公共预检（代理/端口等） |
| `quick_checks.py` | 秒级预检：SDK 基线门禁、`git diff --check`、secret scan |
| `script_regressions.py` | 脚本回归清单、运行与摘要（供 `verify-scripts.ps1`） |
| `sdk-selection.ps1` | 解析本 worktree 消费的 SDK 版本 |
| `build-stamp.ps1` | 构建戳读写，决定 `-SkipBuild` 能否复用 |
| `frontend-gate-cache.ps1` / `frontend_gate_cache.py` | 复用冻结前端来源门禁结果 |
| `frontend_build_cache.py` | 冻结前端构建缓存（共享 `.next`） |
| `run-fixture.ps1` | UI 验收生命周期：槽位预检、启动、就绪等待、精确清理 |
| `smoke-preflight.ps1` | UI smoke 预检与 case 标题选择 |
| `shpm-governance.ps1` | SHPM 快照读取与治理规则 |
| `v2-evidence-contract.ps1` | V2 证据合同校验与聚合 |
| `w18-differential-results.ps1` | Playwright 标题 → Oracle case 映射与状态分类 |
| `customer-demo-evidence.ps1` | 客户演示 v0 证据校验共享逻辑 |

## 常用命令

```powershell
# 定向测试与全仓门禁
pwsh -NoProfile -File scripts/test-targeted.ps1 -Test LocalServerConfigurationTest
pwsh -NoProfile -File scripts/verify-full.ps1

# 冻结前端来源/启动预检
pwsh -NoProfile -File scripts/frontend/start-haifa-frontend.ps1 -DryRun -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow

# 主工作树服务
pwsh -NoProfile -File scripts/start-main-super-harness.ps1            # Gateway + 前端
pwsh -NoProfile -File scripts/start-main-super-harness.ps1 -GatewayOnly

# doctor 与证据
pwsh -NoProfile -File scripts/doctor/verify-customer-demo-v2-doctor.ps1
pwsh -NoProfile -File scripts/evidence/generate-evidence-report.ps1
```

## 约定

- 新增入口按用途放入对应子目录；可复用逻辑放 `lib/`，不要在入口里重复实现。
- 端口、数据目录、证据外置位置沿用 `AGENTS.md` 的槽位与验收规则；脚本只清理自己启动且能核对命令行的进程。
- 构建环境、SDK 版本选择与验收前置条件见根目录 [README](../README.md) 和 [AGENTS.md](../AGENTS.md)。
- 脚本自身的回归检查见 [tests/checks](../tests/README.md)，浏览器用例见 `tests/ui/`。

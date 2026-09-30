# Frozen DeerFlow lead-agent system prompt — assembly ledger

上游来源：`bytedance/deer-flow@e545c28ac301e6f9feae247c513d8ea8f83df294`
上游装配函数：`backend/packages/harness/deerflow/agents/lead_agent/prompt.py::apply_prompt_template`（`prompt.py:1011-1131`）
冻结模板：`SYSTEM_PROMPT_TEMPLATE`（`prompt.py:542-705`，实测 8577 字符 / 8655 UTF-8 字节）
本地真源：`resources/upstream/deerflow/prompts/lead-agent-system-template.txt`（`local_sha256` 见 `lead-agent-system-template.txt.sha256` 与 `contracts/upstream/deerflow/resource-seed.json`）
本地适配：`resources/upstream/deerflow/prompts/lead-agent-adaptations.json`（有序精确替换）

## 1. 注入位置

模型看到的 AGENT_DEFINITION 指令层，由 super harness 在 `HaifaRunBridge` 生产构造器里按 Bridge 冻结渲染，
写入 `ProductProfile.create(...)` 的 `instructions` 参数（第 5 参数）。SDK 对 `instructions` 先 `trim()`，
再限制 32000 字符；本模块的 `promptSignature` 取 `trim()` 后字符串的 SHA-256。
SDK 另有 SYSTEM_SAFETY / PLATFORM_POLICY / TOOL_PROTOCOL / CONTEXT 等层，SH 不控制，也不在本清单内。

## 2. 输入与分支（本切片取值）

| 占位符 | 本切片取值 | 解锁条件 |
|---|---|---|
| `agent_name` | 默认 `DeerFlow 2.0`（`LeadPromptInputs.DEFAULT_AGENT_NAME`） | `W03-AgentSoulPromptLoop` |
| `soul` | 空（`soul == null`）；非空时按冻结 `get_agent_soul` 转义 `&`/`<`/`>` 后包进 `<soul>` | `W03-AgentSoulPromptLoop` |
| `self_update_section` | 空（`selfUpdate == false` 恒成立） | `W03-AgentSoulPromptLoop` |
| `interaction_thinking_guidance` | 空（D2） | W05 `ask_clarification` 入 `allowedTools` |
| `clarification_system` | 空（D2） | W05 |
| `clarification_reminder` | 空（D2） | W05 |
| `subagent_thinking` | 空 | W08/W09 `task` |
| `subagent_section` | 空 | W08/W09 |
| `subagent_reminder` | 空 | W08/W09 |
| `skills_section` | 有启用 Skill 时按 `<available_skills>` 结构渲染，加载步骤改用 `skill_load`/`skill_resource_read`；无 Skill 时为空（D3，见 §5） | — |
| `memory_tool_section` | 空 | W13 |
| `deferred_tools_section` | 空（冻结默认 `tool_search.enabled=false`） | W10 |
| `mcp_routing_hints_section` | 空（SH 无 routing） | W10 |
| `acp_section` | 空（ACP 与自定义挂载均未配置） | — |
| `skill_first_reminder` | 冻结 legacy 措辞 `- Skill First: Always load the relevant skill before starting **complex** tasks.`（不含工具名） | — |

## 3. 模板内的静态删除与改写（`lead-agent-adaptations.json`）

| id | 冻结位置 | 处置 | 解锁 |
|---|---|---|---|
| `A1-write-file-append` | `prompt.py:687-694` | 删除 `str_replace` 优先与 `append=True` 分段续写，改写为 SH `write_file` 只能新建、单次 ≤80 KiB、长内容拆多文件 | W04-DeerFlowFileTools |
| `A2-present-files` | `609` | 删除 `present_files` 句，保留交付物位于 `/mnt/user-data/outputs/` | W04-PresentFiles |
| `A2-present-files-image` | `699` | 删除图片前的 `present_files` 句，保留完整虚拟路径引用约束 | W04-PresentFiles |
| `A3-skill-manage-deliverables` | `609` | 删除 `skill_manage` 引用，保留 “Skills are NOT deliverables” | W15 |
| `A3-skill-manage-output` | `686` | 同上（critical reminders 中的副本） | W15 |
| `A4-list-uploaded-files-grep` | `596` | 删除 `list_uploaded_files`/`grep`，改写为“历史上传不自动列出；已知文件名用 `read_file`” | W04-DeerFlowFileTools |
| `A4-list-uploaded-files` | `604` | 删除 `list_uploaded_files`，改写为“仅 `<current_uploads>` 列出的文件可用” | W04-DeerFlowFileTools |
| `A5-citations` | `619-680` | 整块删除（无反引号引用 `web_search`/`web_fetch`，D5） | W11-DdgJinaWebTools |
| `A7-tool-word-task-*` | `557`/`575`/`701` 附近 | 将英文词 `task`（`Multi-task` 等）改写以避免整词对账假命中 | W08/W09 |

`A6-user-input-boundary` 未启用：D1 采用“实现包裹”，模板对用户输入边界的声明（`prompt.py:547-548`）保留。

## 4. 工具名对账

`LeadPromptComposer` 以整词（前后不接 `[A-Za-z0-9_]`）在**渲染结果全文**（含无反引号文本与代码块）匹配
冻结工具名全集 ∪ SH 现役工具名；命中集合必须 ⊆ 本 Bridge 实际注册工具集合，否则 Bridge 构建 fail closed。
本切片启用后（无 Skill）命中集合为 `{read_file, write_file}`；启用 Skill 时为
`{read_file, write_file, skill_load, skill_resource_read}`。

## 5. D3 实测结论（P3）

`local-tmp/slot-2/report/W03-task-3-preflight.md` 记录：安装 Skill 平台后 `promptDiagnostics` 的组件 `source`
列表**没有** `SKILL`；只多出 `TOOL_PROTOCOL`（`skill_load`/`skill_resource_read` 的 schema）。
因此 SDK 未注入 Skill 目录/说明文本，本切片按冻结 `<available_skills>` 结构自行渲染，
并把载入步骤改写为 SDK 的 `skill_load`/`skill_resource_read`（参数名取自实测 schema：`skill`、`path`）。

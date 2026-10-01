# 冻结 DeerFlow Lead 提示词装配清单

上游：`bytedance/deer-flow@e545c28ac301e6f9feae247c513d8ea8f83df294`。
原模板：`backend/packages/harness/deerflow/agents/lead_agent/prompt.py:542-705` 的 `SYSTEM_PROMPT_TEMPLATE`，8577 字符 / 8655 UTF-8 字节，首字符 LF，原行尾空格保持。
本地真源：`lead-agent-system-template.txt`；有序适配：`lead-agent-adaptations.json`。导入字节与本清单 SHA 在 `contracts/upstream/deerflow/resource-seed.json` 登记。

## 注入与边界

生产 `HaifaRunBridge` 通过公开 `ProductProfile.create(...)` instructions 参数注入。SDK trim 后限制 32000 字符；promptSignature 为 trim 后文本 SHA-256。SDK 独占其余 prompt 层、Run、Skill binding、权限及执行语义。兼容 starter 构造器仅测试使用。

新 Turn/Steer 在既有 modelInput 接缝包裹用户边界；完整用户伪造 frame 也中和。仅标记附近反斜杠编码为可逆形式，普通 Windows 路径/代码保留；state/history 恢复原文。current_uploads 位于边界外。既有线程按服务端 metadata 中首个编码 SDK Turn 的 sequence 界线解包，更早的未编码用户文字保持原样；升级时仍活跃的旧 Run 的 Steer 沿用其原始输入语义，不改变该 Run 的冻结声明。冻结 blocked-tag 转义仍为本切片明确差异。

## D1–D5 与占位符

| 项 | 实际值 |
|---|---|
| D1 | 实现用户边界与可逆展示 |
| D2 | 注册 ask_clarification 时恢复冻结 interactive clarification_system / thinking_guidance / reminder；未注册时三段为空 |
| D3 | SDK promptDiagnostics 未出现 SKILL 组件；SH 渲染 available_skills，加载映射 skill_load / skill_resource_read；无选定 Skill 时为空 |
| D4 | 默认 agent_name 为 DeerFlow 2.0 |
| D5 | M1 已注册 web_search / web_fetch，恢复原 citations 静态块 |

interactive 系统段原文字节来自 `agents/interaction_policy.py:22-90`，保存为 `interactive-clarification-system.txt` 并校验伴随 SHA；两条单行 guidance/reminder 分别对照同文件 129/142 行。主模板双花括号按 Python format 还原。

soul/self_update 留给 M3，当前为空/false；subagent、memory tool、deferred tools、MCP routing、ACP/custom mounts 为空；Skill First 沿用冻结 legacy 文案。动态日期/项目/用户内容不进入 instructions。

## 适配与工具对账

M1 后撤销 A1（write_file/str_replace）、A2（present_files）、A4（历史上传/grep）、A5（citations），恢复原文。剩余 A3 两项仅移除 skill_manage，保留 present_files；A7 三项将普通英语 task 改写为 work/request/Parallelism，避免未注册 Tool 整词假命中。D1 已兑现，不启用 A6。

门禁扫描全文，包括代码块和无反引号引用；命中 Tool 必须属于本 Bridge 实际注册集合，不设负向白名单。默认命中 read_file、write_file、str_replace、present_files、list_uploaded_files、grep、web_search、web_fetch、ask_clarification；启用 Skill 增加 skill_load、skill_resource_read。其余默认工具仍注册，但静态提示词未必提及。

## 恢复与失败

后续 Turn 比较 model/MCP/Skill/prompt signature，变化则重建 Bridge；活跃 Run 不热改。admitted Run 的实际 Skill 名单和 promptSignature 保存于既有线程产品 metadata，剥除客户端伪造值；重启按原名单恢复，不受后续 enable toggle 影响，SDK 仍验证 retained immutable source/binding。恢复签名不一致明确失败。缺字段的 pre-M2 thread 沿用 legacy fallback，不声称历史 Run 使用过新模板。

缺资源、hash 漂移、适配零次/多次、未知占位符、超长和未注册工具均 fail closed，按 Run admission 错误返回，不回落常量。异常只含路径/位置/固定标识/hash/长度，不保留可能带正文的解析器 cause。证据只记录 source/layer/digest/tokens/signature，不输出 prompt/SOUL/Skill 正文。

# Super Harness Runtime / Product Boundary

状态：稳定架构护栏（normative architecture guardrail）。

本文定义 **Haifa Agent** 与 **Haifa Super Harness** 的能力归属边界。它不是某个工作包的任务提示词；具体开发任务仍按 `AGENTS.md` 要求，将任务提示词维护在 `private-docs/prompts/39-deerflow-parity-delivery/`。

## 一句话原则

当 Super Harness 在产品对齐或客户场景中发现一个新的基础能力缺口时，第一步不是在 Super Harness 里实现，而是先判断：

> **这个能力究竟属于通用 Agent Runtime，还是只属于 Super Harness 产品层？**

如果是可被多个 Agent 产品复用的 Runtime 能力，它应进入 **Haifa Agent**，通过公开 API/SPI 和发布的 JAR/BOM 暴露；Super Harness 再通过公开依赖消费。

**Super Harness 不得因为上游暂时缺一个能力，就在自己内部实现第二套 Agent Runtime。**

Super Harness 的职责是产品、兼容和集成：把 Haifa Agent 的通用能力组合成冻结 DeerFlow 所要求的产品体验，而不是成为 Haifa Agent 的另一份实现。

## 能力归属

| 能力 | 主归属 | Super Harness 的职责 |
| --- | --- | --- |
| Agent Runtime / Agent Loop | Haifa Agent | 调用并适配公开 Runtime API |
| Run / Thread / Session 执行语义 | Haifa Agent | 映射 DeerFlow REST/SSE/UI 语义 |
| Context 管理 / Compaction | Haifa Agent | 产品配置与兼容映射 |
| Memory 模型与存储后端 | Haifa Agent | 产品配置、管理与 UI |
| Tool 执行生命周期 | Haifa Agent | Tool 暴露、产品映射与 UI |
| Skill Registry / 激活语义 | Haifa Agent | Skill 选择、Preset 与 UI |
| MCP Client / Runtime 生命周期 | Haifa Agent | 产品配置与业务接入 |
| Parent/Child、Delegation、Steer | Haifa Agent | 产品交互和展示 |
| Sandbox 生命周期与隔离语义 | Haifa Agent | Provider 选择和产品策略 |
| Policy / Permission / Approval | Haifa Agent | Approval UI 与产品流程展示 |
| Artifact 生命周期 / Provenance | Haifa Agent | 展示、下载和协议映射 |
| Checkpoint / Recovery | Haifa Agent | 产品 UX 与兼容映射 |
| Workspace 通用契约 | Haifa Agent | 在公开 Port 已存在但缺具体 Provider 时提供窄适配 |
| Model 抽象与调用 | Haifa Agent | Model Catalog 与产品配置 |
| Principal / Runtime Identity / Credential Injection / 通用 RBAC | Haifa Agent | 管理界面与租户侧产品体验 |
| Runtime Event / Observability 基础能力 | Haifa Agent | Dashboard、Projection 与运营视图 |
| DeerFlow REST/SSE/WS 兼容层 | Super Harness | 拥有兼容 Adapter |
| 冻结 DeerFlow 前端接入 | Super Harness | BFF、协议适配与产品 Wiring |
| 产品 Prompt / Persona / Preset | Super Harness | 拥有产品语义 |
| 产品特定 Workflow / Use Case | Super Harness | 组合已有 Runtime 能力 |
| ERP、财务等业务系统 Adapter | Super Harness / Integration | 使用 Haifa 扩展点，不替换 Runtime |
| Control Plane UI / 管理视图 | Super Harness | 管理和展示 Runtime 能力 |
| 客户演示与 Parity 编排 | Super Harness | 产品级验收与证据 |

这里划分的是**语义所有权**，不要求每一个实现类都必须位于 Haifa Agent。

如果 Haifa 已经定义了公开 Port，但当前没有适合 Super Harness 的具体实现，Super Harness 可以提供一个窄 Adapter。例如 Workspace 存储适配可以存在于 Super Harness，但它不能继续长成独立的 Workspace Domain、生命周期或第二份权威状态。

## Super Harness 明确不做什么

Super Harness 不得新建平行的：

- Run Engine / Agent Loop；
- Thread / Run 执行状态机；
- Memory Engine；
- Tool Engine 或独立 Tool Registry；
- Skill Runtime 或独立 Skill Registry；
- MCP Runtime；
- Sandbox Manager；
- Artifact Lifecycle；
- Checkpoint System；
- Permission / Approval Engine；
- 通用 Multi-Agent Runtime。

Compatibility DTO、Projection、Cache、Adapter 可以存在，但前提是它们只负责把冻结 DeerFlow 契约映射到 Haifa 的公开能力。

把一个重复子系统命名为 `Adapter`、`Manager`、`Service` 或 `Core`，并不会改变它正在拥有第二套 Runtime 语义这一事实。

## 新能力的判断顺序

编码前按以下顺序判断。

### 1. 这个能力能否被多个 Haifa 产品复用？

如果 Coding Agent、SDK 应用、Personal Assistant、Super Harness 或未来其他产品都可能需要同样的语义，默认归 Haifa Agent。

### 2. 它是否定义执行生命周期或 Runtime 真相？

如果它决定或持久化以下任一内容，默认属于 Haifa Agent：

- Run 生命周期；
- Agent 下一步执行；
- Memory / Context；
- Tool / Skill 可见性或执行；
- Sandbox；
- 安全、权限和审批；
- Checkpoint / Recovery；
- Artifact 生命周期；
- Parent/Child Agent；
- 模型可见的 Runtime 行为。

### 3. 它是否只是产品层能力？

以下内容通常属于 Super Harness：

- DeerFlow 协议兼容；
- UI / BFF；
- 产品 Prompt、Preset、Persona；
- 特定客户或业务 Workflow；
- ERP、财务、IM 等业务 Adapter；
- Control Plane 的配置与展示；
- 客户演示和产品验收。

### 4. Haifa 是否已经有公开 API/SPI？

有则直接复用。

不得因为调用不方便而绕过公开 API 去：

- 依赖 Haifa 源码路径；
- 导入内部实现包；
- 直接访问私有表；
- 复制 SDK / Runtime 代码；
- 在 Super Harness 再做一个功能等价的子系统。

### 5. 缺的是 Haifa 的公开能力怎么办？

先明确记录 Haifa Agent 的具体能力缺口，在 Haifa Agent 上游补齐公开 API/SPI 和实现，发布新的 `0.1.3-SNAPSHOT` JAR/BOM，再由 Super Harness 消费。

不要用 Super Harness 内部的第二套实现“临时补上”，最后形成两套长期并存的 Runtime。

### 6. 只是缺一个具体 Adapter 怎么办？

如果通用契约和生命周期已经由 Haifa Agent 定义，而缺少的只是 Super Harness 特定 Provider / Adapter，可以在 Super Harness 实现。

判断标准是：这个实现只能做映射、I/O 或产品集成，不能拥有一份可与 Haifa Runtime 真相分叉的状态。

## 第二套 Runtime 的预警信号

出现以下情况必须触发架构 Review：

- Super Harness 新建表，并开始权威保存 Haifa 已拥有的 Run、Thread、Message、Memory、Tool、Skill、Approval、Checkpoint、Artifact 或 Sandbox 生命周期；
- 新增循环或 Scheduler，独立决定 Agent 下一步做什么；
- Super Harness 自己定义 Run 的 retry、cancel、suspend、resume、terminal 语义；
- 新增一份可以与 Haifa 不一致的 Tool / Skill Registry；
- Adapter 开始积累通用执行策略；
- 直接读写 Haifa 私有存储；
- 复制 Haifa SDK / Runtime 代码；
- 同一个 Run 是否正确依赖两套实现长期保持行为同步。

这类问题的默认修复不是继续给 Super Harness 加抽象层，而是把缺失的通用能力上移到 Haifa Agent，再把 Super Harness 缩回兼容、配置、组合和展示。

## 允许的例外

只有存在明确证据，证明冻结 DeerFlow 契约无法通过当前 Haifa 公开能力实现，并且一个窄 Super Harness Adapter 是复杂度最低的方案时，才允许例外。

编码前必须说明：

1. 冻结 DeerFlow 要求的精确行为；
2. Haifa 当前最接近的公开能力；
3. 缺少的公开 Seam；
4. 为什么不能优先在 Haifa Agent 补齐；
5. Super Harness 将新增哪些领域对象、状态或持久化；
6. 为什么它仍然只是 Adapter，而不是第二份 Source of Truth；
7. Haifa 将来补齐能力后，这部分代码如何迁移或删除。

这条规则与 `AGENTS.md` 现有的“Super Harness 领域复杂度不得高于冻结 DeerFlow 对应实现”共同生效。

## 可直接复用的开发 / Review 提示词

下面这段可以直接附加到后续开发或 Review 对话中，作为固定架构护栏。它不能替代具体工作包的任务提示词。

> 你正在实现或 Review Haifa Super Harness 的一个改动。
>
> 编码前，先把本次新增的每个能力明确归类为：
>
> 1. **通用 Agent Runtime 能力**；或
> 2. **Super Harness 产品 / 兼容 / 集成能力**。
>
> Haifa Agent 是通用 Runtime 语义的唯一所有者，包括 Agent Loop、Run/Thread 生命周期、Context/Memory、Tool 执行、Skill Runtime、MCP 生命周期、Parent/Child 与 Steer、Sandbox、Policy/Approval、Artifact、Checkpoint/Recovery、Workspace 通用契约、Model 调用、Runtime Identity / Credential Injection / RBAC 以及 Observability 基础能力。
>
> Super Harness 只拥有 DeerFlow 协议兼容、冻结前端接入、产品 Prompt/Preset、产品特定 Workflow、业务 Adapter、Control Plane 展示以及产品验收。
>
> 开始设计前，先检查 Haifa 已有公开 API/SPI 和 Super Harness 已有 Adapter，优先复用。若缺的是通用 Runtime 能力，明确指出 Haifa Agent 的具体缺口，并把能力上移到 Haifa Agent；不要在 Super Harness 实现平行子系统。
>
> 明确拒绝第二套 Run Engine、Agent Loop、Memory Engine、Tool/Skill Registry、MCP Runtime、Sandbox Manager、Artifact Lifecycle、Checkpoint System、Permission Engine 或通用 Multi-Agent Runtime。
>
> Super Harness 只有在 Haifa 已定义公开 Port、但缺少产品特定具体 Provider 时，才可以实现窄 Adapter；该 Adapter 不得拥有独立 Runtime 生命周期或第二份权威状态。
>
> 在实现或 Review 结论中必须回答：
>
> 1. 本次复用了哪些 Haifa 现有能力；
> 2. 哪些行为确实只属于 Super Harness；
> 3. 是否发现 Haifa 上游能力缺口；
> 4. 曾考虑并拒绝了什么重复造 Runtime 的方案；
> 5. 新增的 Super Harness 状态或持久化为什么只是兼容/产品状态，不会与 Haifa Runtime 真相分叉。
>
> 如果归属仍不清楚，在新增领域模型、状态机或持久化表之前停止实现，先进行架构裁决。

## PR Review Gate

涉及 Runtime 邻接能力的 Super Harness PR，至少满足以下条件才能进入合入评审：

- 只通过 Haifa 公开 JAR/BOM API 消费上游能力；
- 没有建立独立 Runtime Source of Truth；
- 新增状态可以明确证明属于兼容或产品状态；
- 在增加本地抽象前已经检查并复用了现有 Haifa 能力；
- 通用 Haifa 能力缺口在上游解决，而不是用 Super Harness 平行 Engine 掩盖；
- Super Harness Adapter 保持窄、可替换，并且复杂度低于其映射的 Runtime 语义；
- 改动继续满足冻结 DeerFlow 合同和仓库既有依赖门禁。

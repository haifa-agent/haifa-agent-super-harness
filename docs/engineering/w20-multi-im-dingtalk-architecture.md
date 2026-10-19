# W20 多 IM 适配层架构设计与钉钉（DingTalk）接入技术路线

> 状态：设计完成 / 评审就绪  
> 归属工作包：`W20（五 IM、GitHub 与 Lark）`  
> 责任角色：主控智能体（Controller）  
> 目标渠道：钉钉（DingTalk）Stream 模式及后续多企业 IM（WeCom、Slack 等）

---

## 1. 背景与核心架构原则

### 1.1 演进背景
在完成飞书（Feishu / Lark）长连接、多轮对话、卡片交互与审批流（Phase 1 / Phase 2）的建设后，系统证明了“**以轻量级 IM Worker 适配层直连底层统一 Runtime（DeerFlowConversationService）**”的高效性与可靠性。
当前系统的下一目标是接入**钉钉（DingTalk）**，并为未来接入企业微信（WeCom）、Slack 等更多 IM 工具建立清晰、适度、低复杂度的通用通道抽象层。

### 1.2 架构边界与防腐原则（遵循 AGENTS.md 与 runtime-product-boundary）
1. **禁止自研第二套 Agent Runtime**：
   所有的 Run 调度、状态机、多 Agent 协作、工具执行拦截、会话历史管理、Memory/Artifact 均归属于底层 Haifa Agent Runtime 与 `DeerFlowConversationService`。IM 适配层仅作为纯粹的**双向协议投影与中继适配器（Narrow Adapter）**。
2. **拒绝过度设计，控制领域复杂度**：
   不引入重型 MessageBus、复杂分布式事件总线或平行的复杂状态机。保持与冻结 DeerFlow 架构同等或更简的复杂度。
3. **隔离与可测试性**：
   每个 IM 渠道必须具备完全独立可替换的 `Transport` 桩与单测套件，具备离线无网络、无外部凭据下的回放与测试能力。

---

## 2. 钉钉 SDK 与接入技术深度调研

### 2.1 模式选型：Stream 模式（WebSocket 长连接）vs Webhook HTTP 回调

| 维度 | Webhook HTTP 模式 | **Stream 模式（推荐选型）** |
|---|---|---|
| **网络要求** | 必须具备**公网 IP / 公网域名**，需配置 SSL 证书或内网穿透 | **无需公网 IP / 域名**，客户端对外发起安全 WebSocket 长连接 |
| **对齐飞书机制** | 飞书已采用 WebSocket 长连接（`oapi-sdk` wsClient） | **与飞书高度对齐**，本地开发、测试套件、私有部署开箱即用 |
| **消息接收** | HTTP POST 接口接收，需验签与解密 | 基于 WebSocket 订阅 Topic，SDK 内部解包分发 |
| **卡片回调** | HTTP POST 回调，超时要求 3 秒内响应 | WebSocket 双向通道推送，可直接快速返回 ACK |

**选型结论**：全面采用**钉钉官方 Stream 模式**。

### 2.2 核心 SDK 选型与 Maven 坐标
采用阿里巴巴与钉钉官方维护的现代 SDK 体系：
```xml
<!-- 钉钉 Stream 模式核心 SDK (长连接接收消息与卡片回调) -->
<dependency>
    <groupId>com.dingtalk.open</groupId>
    <artifactId>dingtalk-stream</artifactId>
    <version>1.3.0</version>
</dependency>

<!-- 钉钉官方 OpenAPI SDK (用于调用高级服务端 API、发卡片、上传媒体等) -->
<dependency>
    <groupId>com.aliyun</groupId>
    <artifactId>dingtalk</artifactId>
    <version>2.2.65</version>
</dependency>
```

### 2.3 关键协议与交互模式对照（飞书 vs 钉钉）

| 核心能力 | 飞书（Feishu / Lark） | 钉钉（DingTalk） | 钉钉技术实现方案 |
|---|---|---|---|
| **配置凭证** | `app_id` + `app_secret` + `domain` | `client_id` (AppKey) + `client_secret` (AppSecret) | `ChannelRuntimeConfigStore` 存储凭据 |
| **长连接客户端** | `com.lark.oapi.ws.Client` | `com.dingtalk.open.app.stream.core.OpenDingTalkClient` | 由 `DingTalkTransport` 封装生命周期 |
| **消息事件订阅** | `im.message.receive_v1` | Topic: `/v1.0/im/bot/messages/get` | `registerCallbackListener(topic, consumer)` |
| **卡片回调订阅** | `card.action.trigger` | Topic: `/v1.0/card/instances/callback` | 接收按钮动作 `actionId`，返回 `AckMessage.OK()` |
| **消息发送方式** | REST API `im.message.reply` | 推送自带 `sessionWebhook` 或 OpenAPI 统一发消息 | `sessionWebhook`（90分钟有效）支持快速回复 |
| **富文本与排版** | Card 2.0 JSON (`tag: "markdown"`, `tag: "table"`) | Markdown 消息 或 互动卡片（AI Card） | `DingTalkCardPresenter` 生成 Markdown 或卡片 |
| **用户身份配对** | `/connect <code>` 匹配一次性绑定码 | `/connect <code>` 匹配一次性绑定码 | 完全复用 `ChannelConnectionStore` |
| **处理状态感知** | Emoji Reaction（`Typing` / `CrossMark`） | 无单消息 Reaction API，支持卡片局部刷新 | `ChannelCapabilities` 声明，降级为文字进度提示 |

---

## 3. 多 IM 适度抽象设计（Architecture Abstraction）

为了从“单飞书实现”平滑演进到“多 IM 通道”，避免在控制层反复累加 `if-else`，采取如下**轻量级接口抽象**：

```
io.haifa.agent.superharness.server.channels
├── core/                                # 通用通道核心 (对底层 Runtime 的中继)
│   ├── ChannelWorker.java               # 统一生命周期接口 (start/stop/running/provider)
│   ├── ChannelCapabilities.java         # 渠道能力矩阵 (声明式特性支持与降级)
│   ├── ChannelInboundMessage.java       # 统一上行输入消息规范
│   ├── ChannelChatThreadStore.java      # 通用 (provider, chat_id, alias) → thread_id 存储
│   ├── InProcessChannelAgentBridge.java # 统一桥接 DeerFlowConversationService
│   └── ChannelRunProjector.java         # 统一事件汇聚投影器 (进度/工具/产物/待办)
├── providers/
│   ├── feishu/                          # 飞书 Provider 具体适配实现
│   │   ├── FeishuChannelWorker.java
│   │   ├── FeishuTransport.java
│   │   ├── FeishuCardPresenter.java
│   │   └── FeishuInteraction.java
│   └── dingtalk/                        # 钉钉 Provider 具体适配实现
│       ├── DingTalkChannelWorker.java
│       ├── DingTalkTransport.java
│       ├── DingTalkStreamTransport.java
│       ├── DingTalkCardPresenter.java
│       └── DingTalkInteraction.java
└── api/
    └── DeerFlowChannelsApi.java         # REST 控制器 (注入 Map<String, ChannelWorker>)
```

### 3.1 核心公共契约

#### (1) `ChannelWorker`（通道工作器生命周期接口）
```java
public interface ChannelWorker extends AutoCloseable {
    String provider();
    boolean running();
    void startOrRestart(Map<String, String> credentials) throws Exception;
    void stop();
    ChannelCapabilities capabilities();
}
```

#### (2) `ChannelCapabilities`（渠道能力矩阵）
用于指导上层业务针对不同 IM 平台的差异进行声明式降级：
```java
public record ChannelCapabilities(
    boolean supportsEmojiReaction,      // 飞书=true, 钉钉=false (降级为文字进度)
    boolean supportsInteractiveCard,    // 飞书=Card2.0, 钉钉=AI/互动卡片
    boolean supportsNativeTables,       // 飞书=true, 钉钉=false (表格需转Markdown列表)
    boolean supportsStreamingCard       // 是否支持卡片局部流式刷新
) {}
```

#### (3) `ChannelInboundMessage`（统一输入模型）
解耦飞书特定命名（如 `openId`），泛化为通用平台字段：
```java
public record ChannelInboundMessage(
    String provider,
    String chatId,
    String messageId,
    String senderId,                    // 飞书 open_id / 钉钉 senderStaffId
    String text,
    String chatType,                    // p2p 或 group
    List<Attachment> attachments,
    CardAction cardAction,
    String rootId,                      // 话题/根消息 ID
    String parentId                     // 父消息 ID
) {
    public boolean isGroupChat() { return !"p2p".equalsIgnoreCase(chatType); }
}
```

#### (4) `DeerFlowChannelsApi` 控制层多态解耦
控制器不再直接注入 `FeishuChannelWorker`，而是注入通道注册表：
```java
private final Map<String, ChannelWorker> channelWorkers;

// 在 configureRuntime、clearRuntime 和 restore 时根据 provider 参数动态分发：
ChannelWorker worker = channelWorkers.get(provider);
if (worker != null) {
    worker.startOrRestart(credentials);
}
```

---

## 4. 实施阶段规划（Two-Stage Roadmap）

### 阶段一：通用抽象提炼与架构多态化（W20-task-16）
- **目标**：不增加新功能，对现有 Feishu 代码完成核心接口下沉与解耦。
- **改动范围**：
  1. 提炼 `ChannelWorker`、`ChannelCapabilities`、`ChannelInboundMessage`。
  2. 将 `InProcessFeishuAgentBridge` 升级为 `InProcessChannelAgentBridge`；将 `FeishuRunProjector` 升级为通用的 `ChannelRunProjector`。
  3. 将 `FeishuChatThreadStore` 重构为多渠道支持的 `ChannelChatThreadStore`（保持原文件向前兼容）。
  4. 重构 `DeerFlowChannelsApi`，支持多 Provider 注入与分发。
- **退出条件**：现有 Feishu 44+ 定向单元测试、前端 Channels 接口 100% 保持绿灯，输入指纹无额外抖动。

### 阶段二：钉钉 Stream 基础渠道建设（W20-task-17）
- **目标**：实现钉钉双向收发、身份配对与基础问答。
- **改动范围**：
  1. 引入 `dingtalk-stream` 依赖。
  2. 实现 `DingTalkTransport`（封装长连接、消息消费与会话回复）。
  3. 实现 `DingTalkChannelWorker`：处理 `/connect` 码消费、单聊/群聊路由、`/new`、`/status`、`/stop`、`/steer` 命令。
  4. 完善 `ChannelConnectionStore` 与 `DeerFlowChannelsApi` 中钉钉凭据（`client_id` / `client_secret`）的元数据注册。
- **退出条件**：单聊与群聊会话状态机跑通，`RecordingDingTalkTransport` 桩测试覆盖全正反例，`/connect` 绑定成功并能触发 Agent 回复。

### 阶段三：钉钉互动卡片与审批流（W20-task-18）
- **目标**：对齐人机交互（Clarification / Approval / Form）在钉钉上的卡片呈现与回调处理。
- **改动范围**：
  1. 实现 `DingTalkCardPresenter`：Markdown 输出优化，表格自动降级为可读排版。
  2. 实现 `DingTalkInteraction`：钉钉卡片按钮交互、审批（批准/拒绝）回调处理（Topic: `/v1.0/card/instances/callback`）。
  3. 异常与回退策略：卡片投递失败自动降级为文本/Markdown 引导。
- **退出条件**：打通人机审批完整闭环（Agent 挂起 -> 钉钉卡片交互 -> 回填用户决策 -> Agent 继续运行）。

---

## 5. 验收与质量门禁

1. **测试策略**：
   - 黄金标准参照 `FeishuReactionsTest` 和 `FeishuRunProjectorTest`，为钉钉实现对应的 `DingTalkChannelWorkerTest`、`DingTalkTransportTest`。
   - 使用 Fake/Recording Transport 模拟长连接推送和 sessionWebhook 回复，不强依赖外部钉钉真实公网账号即可完成 CI 门禁闭环。
2. **门禁口径**：
   - L1 单切片门禁：`scripts/test-targeted.ps1` 覆盖率 100% PASS，`git diff --check`，secret scan。
   - 资源与进程防护：长连接客户端的优雅停机（`stop`），不遗留僵尸线程或未释放连接。

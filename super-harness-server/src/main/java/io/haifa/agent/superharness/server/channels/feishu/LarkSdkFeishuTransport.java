package io.haifa.agent.superharness.server.channels.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.Client;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler;
import com.lark.oapi.event.cardcallback.model.CallBackAction;
import com.lark.oapi.event.cardcallback.model.CallBackCard;
import com.lark.oapi.event.cardcallback.model.CallBackContext;
import com.lark.oapi.event.cardcallback.model.CallBackOperator;
import com.lark.oapi.event.cardcallback.model.CallBackToast;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody;
import com.lark.oapi.service.im.v1.model.UserId;
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq;
import com.lark.oapi.service.im.v1.model.GetMessageResourceResp;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Official {@code com.larksuite.oapi:oapi-sdk} WebSocket long-connection transport. No public
 * webhook route is registered.
 */
public final class LarkSdkFeishuTransport implements FeishuTransport {
    private static final System.Logger LOG = System.getLogger(LarkSdkFeishuTransport.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Object lock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread wsThread;
    private com.lark.oapi.ws.Client wsClient;
    private Client apiClient;
    private String domain = FeishuOpenDomain.DEFAULT;
    private volatile CardActionHandler cardActions;

    @Override
    public void bindCardActions(CardActionHandler handler) {
        this.cardActions = handler;
    }

    @Override
    public void start(String appId, String appSecret, String domain, InboundHandler handler) {
        synchronized (lock) {
            stopLocked();
            String resolvedDomain = FeishuOpenDomain.normalizeOrThrow(domain);
            if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
                throw new IllegalArgumentException("Feishu app_id and app_secret are required");
            }
            this.domain = resolvedDomain;
            EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                    .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                        @Override
                        public void handle(P2MessageReceiveV1 event) {
                            deliver(event, handler);
                        }
                    })
                    .onP2CardActionTrigger(new P2CardActionTriggerHandler() {
                        @Override
                        public P2CardActionTriggerResponse handle(P2CardActionTrigger event) {
                            return respondToCard(event);
                        }
                    })
                    .build();
            com.lark.oapi.ws.Client client = new com.lark.oapi.ws.Client.Builder(appId, appSecret)
                    .eventHandler(dispatcher)
                    .autoReconnect(true)
                    .domain(resolvedDomain)
                    .build();
            this.wsClient = client;
            this.apiClient = Client.newBuilder(appId, appSecret)
                    .openBaseUrl(resolvedDomain)
                    .build();
            Thread thread = new Thread(
                    () -> {
                        try {
                            client.start();
                        } catch (Throwable failure) {
                            LOG.log(System.Logger.Level.WARNING, "Feishu WebSocket ended", failure);
                        } finally {
                            running.set(false);
                        }
                    },
                    "feishu-ws");
            thread.setDaemon(true);
            this.wsThread = thread;
            running.set(true);
            thread.start();
            LOG.log(System.Logger.Level.INFO, "Feishu channel started (domain={0})", resolvedDomain);
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            stopLocked();
        }
    }

    private void stopLocked() {
        running.set(false);
        com.lark.oapi.ws.Client client = wsClient;
        wsClient = null;
        apiClient = null;
        if (client != null) {
            tryDisconnect(client);
        }
        Thread thread = wsThread;
        wsThread = null;
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            try {
                thread.join(5_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        LOG.log(System.Logger.Level.INFO, "Feishu channel stopped");
    }

    @Override
    public boolean running() {
        return running.get();
    }

    @Override
    public void replyCard(String messageId, String text) throws Exception {
        replyInteractive(messageId, cardPayloadMap(text == null ? "" : text));
    }

    @Override
    public void replyInteractive(String messageId, Map<String, Object> card) throws Exception {
        Client api;
        synchronized (lock) {
            api = apiClient;
        }
        if (api == null || messageId == null || messageId.isBlank() || card == null) {
            return;
        }
        String content = Jsons.DEFAULT.toJson(card);
        ReplyMessageReq request = ReplyMessageReq.newBuilder()
                .messageId(messageId)
                .replyMessageReqBody(ReplyMessageReqBody.newBuilder()
                        .msgType("interactive")
                        .content(content)
                        .build())
                .build();
        api.im().message().reply(request);
    }

    @Override
    public byte[] downloadResource(String messageId, String resourceKey, String resourceType) throws Exception {
        Client api;
        synchronized (lock) {
            api = apiClient;
        }
        if (api == null
                || messageId == null
                || messageId.isBlank()
                || resourceKey == null
                || resourceKey.isBlank()) {
            return new byte[0];
        }
        String type = resourceType == null || resourceType.isBlank() ? "file" : resourceType;
        GetMessageResourceResp response = api.im()
                .messageResource()
                .get(GetMessageResourceReq.newBuilder()
                        .messageId(messageId)
                        .fileKey(resourceKey)
                        .type(type)
                        .build());
        if (response == null || !response.success()) {
            throw new IllegalStateException("Feishu media download failed");
        }
        Object data = response.getData();
        if (data instanceof ByteArrayOutputStream stream) {
            return stream.toByteArray();
        }
        return new byte[0];
    }

    public String domain() {
        return domain;
    }

    private static void tryDisconnect(com.lark.oapi.ws.Client client) {
        try {
            Method disconnect = com.lark.oapi.ws.Client.class.getDeclaredMethod("disconnect");
            disconnect.setAccessible(true);
            disconnect.invoke(client);
        } catch (ReflectiveOperationException ignored) {
            // Protected disconnect; interrupt+join is the DeerFlow-parity fallback.
        }
    }

    private static void deliver(P2MessageReceiveV1 event, InboundHandler handler) {
        if (handler == null || event == null || event.getEvent() == null) {
            return;
        }
        EventMessage message = event.getEvent().getMessage();
        EventSender sender = event.getEvent().getSender();
        if (message == null) {
            return;
        }
        String openId = "";
        if (sender != null) {
            UserId senderId = sender.getSenderId();
            if (senderId != null && senderId.getOpenId() != null) {
                openId = senderId.getOpenId();
            }
        }
        String messageType = message.getMessageType() == null || message.getMessageType().isBlank()
                ? "text"
                : message.getMessageType().trim().toLowerCase(Locale.ROOT);
        ParsedContent parsed = parseContent(messageType, message.getContent());
        // Always deliver: unsupported / media-only messages must reach the worker so it can reply
        // instead of looking hung (Phase 1 essential).
        handler.onMessage(new FeishuInboundMessage(
                message.getChatId(),
                message.getMessageId(),
                openId,
                parsed.text(),
                message.getChatType(),
                messageType,
                parsed.attachments(),
                null));
    }

    private P2CardActionTriggerResponse respondToCard(P2CardActionTrigger event) {
        FeishuInboundMessage message = cardMessage(event);
        String toast = FeishuInteraction.INVALID;
        CardActionHandler handler = cardActions;
        if (handler != null) {
            try {
                toast = handler.toastFor(message);
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING, "Feishu card action was rejected", failure);
                toast = FeishuInteraction.INVALID;
            }
        }
        return callbackResponse(toast, FeishuInteraction.settledCard(message, toast));
    }

    static P2CardActionTriggerResponse toastResponse(String content) {
        return callbackResponse(content, null);
    }

    static P2CardActionTriggerResponse callbackResponse(String content, Map<String, Object> card) {
        CallBackToast toast = new CallBackToast();
        toast.setType("info");
        toast.setContent(content == null || content.isBlank() ? FeishuInteraction.PROCESSING : content);
        P2CardActionTriggerResponse response = new P2CardActionTriggerResponse();
        response.setToast(toast);
        if (card != null) {
            CallBackCard update = new CallBackCard();
            update.setType("raw");
            update.setData(card);
            response.setCard(update);
        }
        return response;
    }

    static FeishuInboundMessage cardMessage(P2CardActionTrigger event) {
        P2CardActionTriggerData data = event == null ? null : event.getEvent();
        CallBackContext context = data == null ? null : data.getContext();
        CallBackOperator operator = data == null ? null : data.getOperator();
        CallBackAction action = data == null ? null : data.getAction();
        Map<String, Object> value = action == null ? null : action.getValue();
        String requestId = textValue(value, "request_id");
        String actionName = action == null || action.getName() == null ? "" : action.getName().trim();
        String typed = firstText(
                textValue(action == null ? null : action.getFormValue(), "other"),
                action == null || action.getInputValue() == null ? "" : action.getInputValue().trim());
        boolean other = FeishuInteraction.OTHER_OPTION.equals(textValue(value, "action"))
                || "other_submit".equals(actionName);
        String optionId = other
                ? FeishuInteraction.OTHER_OPTION
                : firstText(textValue(value, "option_id"), textValue(value, "action"));
        String submitted = other ? typed : firstText(textValue(value, "value"), optionId);
        return new FeishuInboundMessage(
                context == null ? "" : context.getOpenChatId(),
                context == null ? "" : context.getOpenMessageId(),
                operator == null ? "" : operator.getOpenId(),
                "",
                "",
                new FeishuInboundMessage.CardAction(
                        textValue(value, "source"),
                        requestId,
                        optionId,
                        submitted,
                        textValue(value, "card_schema")));
    }

    /** Package-visible for unit assertions on inbound content shaping. */
    record ParsedContent(String text, List<FeishuInboundMessage.Attachment> attachments) {}

    static ParsedContent parseContent(String messageType, String contentJson) {
        String type = messageType == null ? "text" : messageType;
        if (contentJson == null || contentJson.isBlank()) {
            return new ParsedContent("", List.of());
        }
        try {
            JsonNode content = JSON.readTree(contentJson);
            if ("image".equals(type)) {
                String key = content.path("image_key").asText("").trim();
                List<FeishuInboundMessage.Attachment> attachments = key.isEmpty()
                        ? List.of()
                        : List.of(new FeishuInboundMessage.Attachment("image", key, "image.jpg"));
                return new ParsedContent("", attachments);
            }
            if ("file".equals(type) || "media".equals(type)) {
                String key = content.path("file_key").asText("").trim();
                String name = content.path("file_name").asText("").trim();
                if (name.isEmpty()) {
                    name = "file.bin";
                }
                List<FeishuInboundMessage.Attachment> attachments = key.isEmpty()
                        ? List.of()
                        : List.of(new FeishuInboundMessage.Attachment("file", key, name));
                return new ParsedContent("", attachments);
            }
            if ("post".equals(type)) {
                return new ParsedContent(extractPostText(content), List.of());
            }
            if (content.hasNonNull("text")) {
                return new ParsedContent(content.get("text").asText("").trim(), List.of());
            }
            return new ParsedContent("", List.of());
        } catch (Exception ignored) {
            return new ParsedContent("text".equals(type) ? contentJson.trim() : "", List.of());
        }
    }

    /** Best-effort plain text from a Feishu post/rich-text payload. */
    private static String extractPostText(JsonNode content) {
        StringBuilder builder = new StringBuilder();
        collectPostText(content, builder);
        return builder.toString().trim();
    }

    private static void collectPostText(JsonNode node, StringBuilder builder) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            appendWord(builder, node.asText());
            return;
        }
        if (node.isObject()) {
            if (node.hasNonNull("text")) {
                appendWord(builder, node.get("text").asText());
            }
            node.fields().forEachRemaining(entry -> {
                if (!"text".equals(entry.getKey())) {
                    collectPostText(entry.getValue(), builder);
                }
            });
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectPostText(child, builder);
            }
        }
    }

    private static void appendWord(StringBuilder builder, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(' ');
        }
        builder.append(text.trim());
    }

    private static String textValue(Map<String, Object> value, String key) {
        if (value == null || key == null) {
            return "";
        }
        Object raw = value.get(key);
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private static String firstText(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? (fallback == null ? "" : fallback) : preferred;
    }

    /**
     * DeerFlow interactive card: markdown element so code fences, headings, and emphasis render in
     * the Feishu client ({@code feishu.py} {@code _build_card_content}).
     */
    static Object cardPayload(String text) {
        return cardPayloadMap(text);
    }

    private static Map<String, Object> cardPayloadMap(String text) {
        return Map.of(
                "config",
                Map.of("wide_screen_mode", true, "update_multi", true),
                "elements",
                java.util.List.of(Map.of("tag", "markdown", "content", text)));
    }
}

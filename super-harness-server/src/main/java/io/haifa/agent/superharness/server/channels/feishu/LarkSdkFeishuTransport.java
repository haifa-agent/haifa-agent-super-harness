package io.haifa.agent.superharness.server.channels.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.Client;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody;
import com.lark.oapi.service.im.v1.model.UserId;
import java.lang.reflect.Method;
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
        Client api;
        synchronized (lock) {
            api = apiClient;
        }
        if (api == null || messageId == null || messageId.isBlank()) {
            return;
        }
        String content = Jsons.DEFAULT.toJson(cardPayload(text == null ? "" : text));
        ReplyMessageReq request = ReplyMessageReq.newBuilder()
                .messageId(messageId)
                .replyMessageReqBody(ReplyMessageReqBody.newBuilder()
                        .msgType("interactive")
                        .content(content)
                        .build())
                .build();
        api.im().message().reply(request);
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
        String text = extractText(message.getContent());
        if (text.isBlank()) {
            return;
        }
        handler.onMessage(new FeishuInboundMessage(
                message.getChatId(),
                message.getMessageId(),
                openId,
                text,
                message.getChatType()));
    }

    private static String extractText(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return "";
        }
        try {
            JsonNode content = JSON.readTree(contentJson);
            if (content.hasNonNull("text")) {
                return content.get("text").asText("").trim();
            }
            return "";
        } catch (Exception ignored) {
            return contentJson.trim();
        }
    }

    /**
     * DeerFlow interactive card: markdown element so code fences, headings, and emphasis render in
     * the Feishu client ({@code feishu.py} {@code _build_card_content}).
     */
    static Object cardPayload(String text) {
        return java.util.Map.of(
                "config",
                java.util.Map.of("wide_screen_mode", true, "update_multi", true),
                "elements",
                java.util.List.of(java.util.Map.of("tag", "markdown", "content", text)));
    }
}

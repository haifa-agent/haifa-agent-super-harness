package io.haifa.agent.superharness.server.channels.feishu;

import com.fasterxml.jackson.databind.ObjectNode;
import io.haifa.agent.superharness.server.auth.AccessOwner;
import io.haifa.agent.superharness.server.config.ChannelConnectionStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin Feishu IM adapter: transport lifecycle + {@code /connect} bind + bound chat → DeerFlow
 * thread/Run. Does not port ChannelManager/MessageBus.
 */
public final class FeishuChannelWorker implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(FeishuChannelWorker.class.getName());
    private static final Pattern CONNECT_CODE =
            Pattern.compile("^/connect\\s+(\\S+)\\s*$", Pattern.CASE_INSENSITIVE);

    private final ChannelConnectionStore connections;
    private final FeishuChatThreadStore threadMap;
    private final FeishuAgentBridge agentBridge;
    private final Object lock = new Object();
    private final AtomicBoolean requireBoundIdentity = new AtomicBoolean(true);
    private final ExecutorService inboundExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "feishu-inbound");
                thread.setDaemon(true);
                return thread;
            });

    private FeishuTransport.Factory transportFactory;
    private FeishuTransport transport;
    private volatile boolean running;

    public FeishuChannelWorker(
            ChannelConnectionStore connections,
            FeishuChatThreadStore threadMap,
            FeishuAgentBridge agentBridge,
            FeishuTransport.Factory transportFactory) {
        this.connections = connections;
        this.threadMap = threadMap;
        this.agentBridge = agentBridge;
        this.transportFactory = transportFactory;
    }

    /** Package/test seam: swap the transport factory before the next start. */
    public void overrideTransportFactory(FeishuTransport.Factory factory) {
        synchronized (lock) {
            this.transportFactory = factory;
        }
    }

    public boolean running() {
        synchronized (lock) {
            return running && transport != null && transport.running();
        }
    }

    /**
     * Start or restart with the given runtime credentials. Throws on invalid domain or transport
     * construction failure (mapped to HTTP 400 by the channels API).
     */
    public void startOrRestart(String appId, String appSecret, String domain) throws Exception {
        synchronized (lock) {
            stopLocked();
            // Channel connections default require_bound_identity=True (DeerFlow ChannelConnectionsConfig).
            requireBoundIdentity.set(true);
            FeishuTransport next = transportFactory.create();
            next.start(appId, appSecret, domain, this::enqueueInbound);
            this.transport = next;
            this.running = true;
        }
    }

    public void stop() {
        synchronized (lock) {
            stopLocked();
        }
    }

    private void stopLocked() {
        running = false;
        FeishuTransport active = transport;
        transport = null;
        if (active != null) {
            try {
                active.stop();
            } catch (RuntimeException ignored) {
                // Best-effort stop.
            }
        }
    }

    /** Test helper: active transport when it is an {@link ImmediateFeishuTransport}. */
    public Optional<ImmediateFeishuTransport> immediateTransport() {
        synchronized (lock) {
            if (transport instanceof ImmediateFeishuTransport immediate) {
                return Optional.of(immediate);
            }
            return Optional.empty();
        }
    }

    private void enqueueInbound(FeishuInboundMessage message) {
        inboundExecutor.execute(() -> {
            try {
                handleInbound(message);
            } catch (Exception failure) {
                LOG.log(System.Logger.Level.WARNING, "Feishu inbound handling failed", failure);
            }
        });
    }

    /** Processes one inbound message on the caller thread (also used by the inbound executor). */
    public void handleInbound(FeishuInboundMessage message) throws Exception {
        if (message == null || message.text().isBlank()) {
            return;
        }
        Matcher connect = CONNECT_CODE.matcher(message.text().trim());
        if (connect.matches()) {
            handleConnect(message, connect.group(1));
            return;
        }
        Optional<ObjectNode> bound = connections.findConnectionByExternalIdentity(
                "feishu", message.openId(), message.chatId());
        if (bound.isEmpty() || !"connected".equals(bound.get().path("status").asText())) {
            if (requireBoundIdentity.get()) {
                reply(message.messageId(), "Please connect this Feishu account in DeerFlow Settings first.");
                return;
            }
            runChat(AccessOwner.DEFAULT, "local-unbound", message);
            return;
        }
        ObjectNode connection = bound.get();
        String owner = connection.path("owner_user_id").asText(AccessOwner.DEFAULT);
        String connectionId = connection.path("id").asText("");
        runChat(owner, connectionId, message);
    }

    private void handleConnect(FeishuInboundMessage message, String code) throws Exception {
        Optional<ObjectNode> bound = connections.bindFeishuFromConnectCode(
                code,
                message.openId(),
                message.chatId(),
                Map.of("chat_id", message.chatId(), "message_id", message.messageId()));
        if (bound.isEmpty()) {
            reply(message.messageId(), "Feishu connection code is invalid or expired.");
            return;
        }
        reply(message.messageId(), "Feishu connected to DeerFlow.");
    }

    private void runChat(String ownerUserId, String connectionId, FeishuInboundMessage message)
            throws Exception {
        String threadId = threadMap.getOrCreate(connectionId, message.chatId());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("channel", "feishu");
        metadata.put("chat_id", message.chatId());
        metadata.put("connection_id", connectionId);
        metadata.put("feishu_open_id", message.openId());
        FeishuAgentBridge.TurnResult result =
                agentBridge.runTurn(ownerUserId, threadId, metadata, message.text());
        String text = result.assistantText();
        if (text == null || text.isBlank()) {
            text = "(no assistant response)";
        }
        reply(message.messageId(), text);
    }

    private void reply(String messageId, String text) throws Exception {
        FeishuTransport active;
        synchronized (lock) {
            active = transport;
        }
        if (active != null) {
            active.replyCard(messageId, text);
        }
    }

    @Override
    public void close() {
        stop();
        inboundExecutor.shutdownNow();
    }
}

package io.haifa.agent.superharness.server.channels.feishu;

import com.fasterxml.jackson.databind.node.ObjectNode;
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
 *
 * <p>Inbound events share one daemon thread today (DeerFlow also serializes per-chat work). Decoupling
 * fast {@code /connect} replies from long {@code runTurn} waits is a follow-up concurrency slice,
 * not part of L1b parity.
 */
public final class FeishuChannelWorker implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(FeishuChannelWorker.class.getName());
    private static final Pattern CONNECT_CODE =
            Pattern.compile("^/connect\\s+(\\S+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final String INBOUND_FAILURE_REPLY =
            "An error occurred while processing your request. Please try again.";

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
            next.bindCardActions(this::acceptCardAction);
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
                FeishuRunRejected rejected = FeishuRunRejected.find(failure);
                String notice = rejected != null && rejected.detail().contains("already accepted")
                        ? FeishuInteraction.ALREADY_HANDLED
                        : INBOUND_FAILURE_REPLY;
                // Surface a card to the user; silent log-only failures look like a hung bot.
                try {
                    if (message != null && message.messageId() != null && !message.messageId().isBlank()) {
                        reply(message.messageId(), notice);
                    }
                } catch (Exception replyFailure) {
                    LOG.log(
                            System.Logger.Level.WARNING,
                            "Feishu inbound failure reply also failed",
                            replyFailure);
                }
            }
        });
    }

    /** Processes one inbound message on the caller thread (also used by the inbound executor). */
    public void handleInbound(FeishuInboundMessage message) throws Exception {
        if (message == null) {
            return;
        }
        if (message.cardAction() != null) {
            continueCardAction(message);
            return;
        }
        if (message.text().isBlank()) {
            return;
        }
        // Group chats leave "@bot /connect <code>" with the mention in the text (DeerFlow
        // strip_leading_mentions). Strip only for the command match; ordinary chat keeps mentions.
        Matcher connect = CONNECT_CODE.matcher(stripLeadingMentions(message.text().trim()));
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

    /**
     * Drop leading platform mention tokens ({@code @_user_1}, {@code @bot}, {@code <@id>}) so group
     * {@code @bot /connect <code>} binds like a bare connect. Matches DeerFlow
     * {@code strip_leading_mentions}.
     */
    static String stripLeadingMentions(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String remainder = text;
        while (!remainder.isEmpty()) {
            if (Character.isWhitespace(remainder.charAt(0))) {
                break;
            }
            String[] parts = remainder.split("\\s+", 2);
            if (parts.length == 0 || !isLeadingMentionToken(parts[0])) {
                break;
            }
            remainder = parts.length > 1 ? parts[1] : "";
        }
        return remainder;
    }

    private static boolean isLeadingMentionToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        if (token.startsWith("<@") && token.endsWith(">") && token.length() > 3) {
            return true;
        }
        return token.startsWith("@") && token.length() > 1;
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

    /**
     * Card callback thread: authorize and enqueue, then return the toast without waiting for the Run.
     */
    public String acceptCardAction(FeishuInboundMessage message) {
        if (message == null
                || message.cardAction() == null
                || message.cardAction().requestId().isBlank()
                || message.cardAction().optionId().isBlank()) {
            return FeishuInteraction.INVALID;
        }
        if (!authorized(message)) {
            return FeishuInteraction.FORBIDDEN;
        }
        if (alreadyClosed(message)) {
            return FeishuInteraction.ALREADY_HANDLED;
        }
        enqueueInbound(message);
        return FeishuInteraction.PROCESSING;
    }

    /** Local state read only. A failure still enqueues so a valid click is not dropped. */
    private boolean alreadyClosed(FeishuInboundMessage message) {
        try {
            Optional<ObjectNode> bound = connections.findConnectionByExternalIdentity(
                    "feishu", message.openId(), message.chatId());
            if (bound.isEmpty()) {
                return true;
            }
            ObjectNode connection = bound.get();
            String owner = connection.path("owner_user_id").asText(AccessOwner.DEFAULT);
            String connectionId = connection.path("id").asText("");
            String threadId = threadMap.getOrCreate(connectionId, message.chatId());
            FeishuPendingInput pending = agentBridge.currentPending(owner, threadId).orElse(null);
            FeishuInboundMessage.CardAction action = message.cardAction();
            return pending == null
                    || pending.formUnsupported()
                    || !pending.requestId().equals(action.requestId());
        } catch (Exception failure) {
            LOG.log(System.Logger.Level.WARNING, "Feishu pending read failed; queueing the card action", failure);
            return false;
        }
    }

    private void continueCardAction(FeishuInboundMessage message) throws Exception {
        if (!authorized(message)) {
            reply(message.messageId(), FeishuInteraction.FORBIDDEN);
            return;
        }
        Optional<ObjectNode> bound = connections.findConnectionByExternalIdentity(
                "feishu", message.openId(), message.chatId());
        if (bound.isEmpty()) {
            reply(message.messageId(), FeishuInteraction.FORBIDDEN);
            return;
        }
        ObjectNode connection = bound.get();
        String owner = connection.path("owner_user_id").asText(AccessOwner.DEFAULT);
        String connectionId = connection.path("id").asText("");
        String threadId = threadMap.getOrCreate(connectionId, message.chatId());
        FeishuPendingInput pending = agentBridge.currentPending(owner, threadId).orElse(null);
        FeishuInboundMessage.CardAction action = message.cardAction();
        if (pending == null
                || pending.formUnsupported()
                || !pending.requestId().equals(action.requestId())) {
            reply(message.messageId(), FeishuInteraction.ALREADY_HANDLED);
            return;
        }
        Optional<FeishuPendingInput.Option> option = FeishuInteraction.optionById(pending, action.optionId());
        if (option.isEmpty()) {
            reply(message.messageId(), FeishuInteraction.INVALID);
            return;
        }
        FeishuPendingInput.Option selected = option.get();
        FeishuHumanResponse response = new FeishuHumanResponse(
                pending.source(), pending.requestId(), "option", selected.id(), selected.value());
        present(message, runSubmitted(owner, connectionId, message, threadId, response, pending));
    }

    private void runChat(String ownerUserId, String connectionId, FeishuInboundMessage message)
            throws Exception {
        String threadId = threadMap.getOrCreate(connectionId, message.chatId());
        FeishuPendingInput pending = agentBridge.currentPending(ownerUserId, threadId).orElse(null);
        if (pending != null && pending.formUnsupported()) {
            reply(message.messageId(), FeishuInteraction.formNotice(pending));
            return;
        }
        if (pending != null) {
            Optional<FeishuHumanResponse> matched = FeishuInteraction.match(pending, message.text());
            if (matched.isPresent()) {
                present(message, runSubmitted(ownerUserId, connectionId, message, threadId, matched.get(), pending));
                return;
            }
            if (pending.singleChoice()) {
                reply(message.messageId(), "haifa_interaction".equals(pending.source())
                        ? FeishuInteraction.APPROVAL_HINT
                        : FeishuInteraction.CHOICE_HINT);
                return;
            }
        }
        Map<String, Object> metadata = channelMetadata(connectionId, message);
        FeishuAgentBridge.TurnResult result =
                agentBridge.runTurn(ownerUserId, threadId, metadata, message.text());
        present(message, result);
    }

    private FeishuAgentBridge.TurnResult runSubmitted(
            String ownerUserId,
            String connectionId,
            FeishuInboundMessage message,
            String threadId,
            FeishuHumanResponse response,
            FeishuPendingInput pending)
            throws Exception {
        String content = FeishuInteraction.responseContent(pending, response);
        return agentBridge.runTurn(ownerUserId, threadId, channelMetadata(connectionId, message), content, response);
    }

    private static Map<String, Object> channelMetadata(String connectionId, FeishuInboundMessage message) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("channel", "feishu");
        metadata.put("chat_id", message.chatId());
        metadata.put("connection_id", connectionId);
        metadata.put("feishu_open_id", message.openId());
        return metadata;
    }

    private void present(FeishuInboundMessage message, FeishuAgentBridge.TurnResult result) throws Exception {
        FeishuPendingInput pending = result.pending();
        if (pending != null && pending.formUnsupported()) {
            reply(message.messageId(), FeishuInteraction.formNotice(pending));
            return;
        }
        if (pending != null) {
            replyInteractive(message.messageId(), FeishuInteraction.interactiveCard(pending));
            return;
        }
        String text = result.assistantText();
        if (text == null || text.isBlank()) {
            text = "(no assistant response)";
        }
        reply(message.messageId(), text);
    }

    private boolean authorized(FeishuInboundMessage message) {
        Optional<ObjectNode> bound = connections.findConnectionByExternalIdentity(
                "feishu", message.openId(), message.chatId());
        return bound.isPresent()
                && "connected".equals(bound.get().path("status").asText())
                && message.openId().equals(bound.get().path("external_account_id").asText());
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

    private void replyInteractive(String messageId, Map<String, Object> card) throws Exception {
        FeishuTransport active;
        synchronized (lock) {
            active = transport;
        }
        if (active != null) {
            active.replyInteractive(messageId, card);
        }
    }

    @Override
    public void close() {
        stop();
        inboundExecutor.shutdownNow();
    }
}

package io.haifa.agent.superharness.server.channels.feishu;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.server.auth.AccessOwner;
import io.haifa.agent.superharness.server.config.ChannelConnectionStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin Feishu IM adapter: transport lifecycle + {@code /connect} bind + bound chat → DeerFlow
 * thread/Run. Does not port ChannelManager/MessageBus.
 *
 * <p>Phase 1 essential: connect/card-fast paths are not blocked by other chats' long {@code
 * runTurn}; per-chat work is serialized; unsupported media is acknowledged; image/file uploads
 * enter the shared Thread uploads store.
 *
 * <p>Phase 2: RunEventSink progress, Artifact/Todo appendix, Stop/Steer, mode, Form v2 subset,
 * approval metadata, and busy-path steer instead of silent queueing.
 *
 * <p>Processing ACK follows Hermes-agent: {@code Typing} reaction while working, cleared on
 * success; {@code CrossMark} on failure after a successful clear. Disable with
 * {@code HAIFA_SUPER_HARNESS_FEISHU_REACTIONS=false} (falls back to the legacy text progress card).
 */
public final class FeishuChannelWorker implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(FeishuChannelWorker.class.getName());
    private static final Pattern CONNECT_CODE =
            Pattern.compile("^/connect\\s+(\\S+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEW_COMMAND = Pattern.compile("^/new\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATUS_COMMAND = Pattern.compile("^/status\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MODE_COMMAND =
            Pattern.compile("^/mode(?:\\s+(\\S+))?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern STOP_COMMAND =
            Pattern.compile("^/(?:stop|cancel)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern STEER_COMMAND =
            Pattern.compile("^/steer\\s+(.+)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern WEB_ONLY_COMMAND =
            Pattern.compile("^/(?:skill|goal|compact)(?:\\s.*)?$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final String INBOUND_FAILURE_REPLY =
            "An error occurred while processing your request. Please try again.";
    private static final int MAX_ARTIFACT_IMAGES = 4;
    private static final long MAX_ARTIFACT_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final Set<String> ARTIFACT_IMAGE_EXTENSIONS =
            Set.of("png", "jpg", "jpeg", "webp", "gif", "bmp");
    private static final String METADATA_MODE_KEY = "run_mode";
    private static final String REACTIONS_ENV = "HAIFA_SUPER_HARNESS_FEISHU_REACTIONS";
    private static final Set<String> REACTIONS_OFF = Set.of("0", "false", "no", "off");
    /** Hermes-bounded cache for message_id → Typing reaction_id. */
    private static final int PROCESSING_REACTION_CACHE_SIZE = 1024;

    private final ChannelConnectionStore connections;
    private final FeishuChatThreadStore threadMap;
    private final FeishuAgentBridge agentBridge;
    private final Object lock = new Object();
    private final AtomicBoolean requireBoundIdentity = new AtomicBoolean(true);
    /**
     * Per-chat Run mutual exclusion. Held only for the duration of {@code runTurn}; a second inbound
     * on the same chat gets steer/stop (Phase 2) or {@link FeishuInteraction#BUSY} when neither
     * applies.
     */
    private final ConcurrentHashMap<String, Boolean> chatBusy = new ConcurrentHashMap<>();
    /** message_id → Typing reaction_id (LRU-capped). */
    private final Map<String, String> processingReactions = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > PROCESSING_REACTION_CACHE_SIZE;
                }
            });
    /** Fast path for /connect so other chats' long Runs cannot starve binding. */
    private final ExecutorService connectExecutor =
            Executors.newCachedThreadPool(r -> {
                Thread thread = new Thread(r, "feishu-connect");
                thread.setDaemon(true);
                return thread;
            });
    /** Bounded pool for chat Turns; busy map rejects overlapping Runs on the same chat. */
    private final ExecutorService chatExecutor =
            Executors.newFixedThreadPool(4, r -> {
                Thread thread = new Thread(r, "feishu-chat");
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
        if (message == null) {
            return;
        }
        if (isConnectCommand(message)) {
            connectExecutor.execute(() -> runSafely(message, () -> handleInbound(message)));
            return;
        }
        // Busy acknowledgement / steer / stop must not wait for a free Turn worker. Thread-level:
        // another thread of the same chat must not be forced onto the busy path.
        if (isBusyMessage(message)) {
            connectExecutor.execute(() -> runSafely(message, () -> handleBusyInbound(message)));
            return;
        }
        chatExecutor.execute(() -> runSafely(message, () -> handleInbound(message)));
    }

    private static String busyKey(String connectionId, String threadId) {
        return (connectionId == null ? "" : connectionId) + "|" + (threadId == null ? "" : threadId);
    }

    private static String requestAlias(String requestId) {
        return "request:" + (requestId == null ? "" : requestId);
    }

    /**
     * Resolve the target SH Thread for an inbound message. Read-only when {@code allowCreate} is
     * false ({@code /status}, busy probe, card callbacks, Stop/Steer). When true, only the three
     * allowed writes happen: a single-chat first message / current pointer, a group topic key
     * ({@code root_id} else {@code message_id}), and a {@code /new} binding.
     *
     * <p>Lookup order: card {@code request_id} alias, then {@code root_id}, {@code parent_id},
     * {@code feishu_thread_id} aliases (first hit wins), then the single-chat current pointer.
     * Group messages never read the chat pointer.
     */
    private String resolveThread(String connectionId, FeishuInboundMessage message, boolean allowCreate) {
        String chat = message.chatId();
        if (message.cardAction() != null) {
            // A card callback is answered only through its immutable request_id alias. Without one it
            // is unroutable: never fall back to the current chat pointer or bind a card message id.
            return threadMap.findAlias(connectionId, chat, requestAlias(message.cardAction().requestId()))
                    .orElse(null);
        }
        for (String alias : List.of(message.rootId(), message.parentId(), message.feishuThreadId())) {
            if (!alias.isBlank()) {
                Optional<String> stored = threadMap.findAlias(connectionId, chat, alias);
                if (stored.isPresent()) {
                    return stored.get();
                }
            }
        }
        if (message.isGroupChat()) {
            if (!allowCreate) {
                return null;
            }
            String topic = !message.rootId().isBlank() ? message.rootId() : message.messageId();
            if (topic.isBlank()) {
                return null;
            }
            // First writer wins so two concurrent first messages share one topic thread.
            return threadMap.bindAliasIfAbsent(
                    connectionId, chat, topic, "feishu-" + java.util.UUID.randomUUID());
        }
        if (!allowCreate) {
            return threadMap.find(connectionId, chat).orElse(null);
        }
        return threadMap.getOrCreate(connectionId, chat);
    }

    /** Resolve and remember this message's own id + reply/topic aliases for future replies. */
    private String resolveAndRemember(String connectionId, FeishuInboundMessage message) {
        String threadId = resolveThread(connectionId, message, true);
        if (threadId == null || threadId.isBlank()) {
            return threadId;
        }
        String chat = message.chatId();
        if (!message.messageId().isBlank()) {
            threadMap.bindAliasIfAbsent(connectionId, chat, message.messageId(), threadId);
        }
        if (!message.rootId().isBlank()) {
            threadMap.bindAliasIfAbsent(connectionId, chat, message.rootId(), threadId);
        }
        if (!message.feishuThreadId().isBlank()) {
            threadMap.bindAliasIfAbsent(connectionId, chat, message.feishuThreadId(), threadId);
        }
        return threadId;
    }

    private boolean isBusyMessage(FeishuInboundMessage message) {
        try {
            Optional<ObjectNode> connection = connections.findConnectionByExternalIdentity(
                    "feishu", message.openId(), message.chatId());
            if (connection.isEmpty()) {
                return false;
            }
            String connectionId = connection.get().path("id").asText("");
            String threadId = resolveThread(connectionId, message, false);
            return threadId != null && Boolean.TRUE.equals(chatBusy.get(busyKey(connectionId, threadId)));
        } catch (Exception failure) {
            // Broken identity/routing storage must not be mistaken for a new conversation.
            return false;
        }
    }

    private void runSafely(FeishuInboundMessage message, InboundTask task) {
        try {
            task.run();
        } catch (Exception failure) {
            LOG.log(System.Logger.Level.WARNING, "Feishu inbound handling failed", failure);
            FeishuRunRejected rejected = FeishuRunRejected.find(failure);
            String notice = rejected != null && rejected.detail().contains("already accepted")
                    ? FeishuInteraction.ALREADY_HANDLED
                    : INBOUND_FAILURE_REPLY;
            try {
                if (message != null && message.messageId() != null && !message.messageId().isBlank()) {
                    markProcessingFailure(message.messageId());
                    reply(message.messageId(), notice);
                }
            } catch (Exception replyFailure) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        "Feishu inbound failure reply also failed",
                        replyFailure);
            }
        }
    }

    @FunctionalInterface
    private interface InboundTask {
        void run() throws Exception;
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
        String stripped = stripLeadingMentions(message.text().trim());
        Matcher connect = CONNECT_CODE.matcher(stripped);
        if (connect.matches()) {
            handleConnect(message, connect.group(1));
            return;
        }
        if (!message.isRunnableInbound()) {
            // Unsupported sticker/audio/etc. or blank text: never silent (Phase 1).
            reply(message.messageId(), FeishuInteraction.UNSUPPORTED_MEDIA);
            return;
        }
        Optional<ObjectNode> bound = connections.findConnectionByExternalIdentity(
                "feishu", message.openId(), message.chatId());
        if (bound.isEmpty() || !"connected".equals(bound.get().path("status").asText())) {
            if (requireBoundIdentity.get()) {
                reply(message.messageId(), "Please connect this Feishu account in DeerFlow Settings first.");
                return;
            }
            runChat(AccessOwner.DEFAULT, "local-unbound", message, stripped);
            return;
        }
        ObjectNode connection = bound.get();
        String owner = connection.path("owner_user_id").asText(AccessOwner.DEFAULT);
        String connectionId = connection.path("id").asText("");
        if (handleChannelCommands(owner, connectionId, message, stripped, connection)) {
            return;
        }
        runChat(owner, connectionId, message, stripped);
    }

    /**
     * While a Turn holds {@code chatBusy}, Stop cancels and other text steers the active Run.
     * Falls back to {@link FeishuInteraction#BUSY} when there is no active Run to steer.
     */
    private void handleBusyInbound(FeishuInboundMessage message) throws Exception {
        if (message == null) {
            return;
        }
        if (message.cardAction() != null) {
            // Card actions enqueue through the same busy path; prefer explicit notice over a silent drop.
            reply(message.messageId(), FeishuInteraction.BUSY);
            return;
        }
        String stripped = stripLeadingMentions(message.text().trim());
        Optional<ObjectNode> bound = connections.findConnectionByExternalIdentity(
                "feishu", message.openId(), message.chatId());
        if (bound.isEmpty() || !"connected".equals(bound.get().path("status").asText())) {
            reply(message.messageId(), FeishuInteraction.BUSY);
            return;
        }
        ObjectNode connection = bound.get();
        String owner = connection.path("owner_user_id").asText(AccessOwner.DEFAULT);
        String connectionId = connection.path("id").asText("");
        // Mode / web-only / stop / explicit /steer must not fall through as steer text.
        if (handleChannelCommands(owner, connectionId, message, stripped, connection, false)) {
            return;
        }
        String threadId = resolveThread(connectionId, message, false);
        String steerText = stripped;
        if (threadId == null || threadId.isBlank() || steerText.isBlank()) {
            reply(message.messageId(), FeishuInteraction.BUSY);
            return;
        }
        if (!agentBridge.hasActiveRun(owner, threadId)) {
            reply(message.messageId(), FeishuInteraction.BUSY);
            return;
        }
        try {
            agentBridge.runTurn(
                    owner,
                    threadId,
                    channelMetadata(connectionId, message, connection),
                    steerText,
                    null,
                    List.of(),
                    FeishuAgentBridge.TurnOptions.defaults()
                            .withMode(readMode(connection))
                            .asSteer());
            reply(message.messageId(), FeishuInteraction.STEER_ACCEPTED);
        } catch (Exception failure) {
            FeishuRunRejected rejected = FeishuRunRejected.find(failure);
            reply(
                    message.messageId(),
                    rejected != null ? FeishuInteraction.STEER_FAILED : FeishuInteraction.BUSY);
        }
    }

    private boolean handleChannelCommands(
            String owner,
            String connectionId,
            FeishuInboundMessage message,
            String stripped,
            ObjectNode connection)
            throws Exception {
        return handleChannelCommands(owner, connectionId, message, stripped, connection, true);
    }

    /**
     * @param allowNewTurn when false (busy path), {@code /steer} without an active Run is BUSY
     *     instead of opening a new Turn behind the busy lock.
     */
    private boolean handleChannelCommands(
            String owner,
            String connectionId,
            FeishuInboundMessage message,
            String stripped,
            ObjectNode connection,
            boolean allowNewTurn)
            throws Exception {
        if (NEW_COMMAND.matcher(stripped).matches()) {
            handleNewCommand(owner, connectionId, message, connection);
            return true;
        }
        if (STATUS_COMMAND.matcher(stripped).matches()) {
            // Read-only: /status must never create or rebind a mapping.
            reply(message.messageId(),
                    FeishuInteraction.statusNotice(resolveThread(connectionId, message, false)));
            return true;
        }
        if (STOP_COMMAND.matcher(stripped).matches()) {
            // Read-only: Stop must never create or rebind a mapping.
            String threadId = resolveThread(connectionId, message, false);
            boolean cancelled = threadId != null
                    && !threadId.isBlank()
                    && agentBridge.cancelActiveRun(owner, threadId);
            reply(message.messageId(), cancelled ? FeishuInteraction.STOPPED : FeishuInteraction.STOP_FAILED);
            return true;
        }
        Matcher mode = MODE_COMMAND.matcher(stripped);
        if (mode.matches()) {
            String requested = mode.group(1);
            if (requested == null || requested.isBlank()) {
                reply(message.messageId(), "当前模式：" + readMode(connection) + "\n" + FeishuInteraction.MODE_INVALID);
                return true;
            }
            if (!InProcessFeishuAgentBridge.isValidMode(requested)) {
                reply(message.messageId(), FeishuInteraction.MODE_INVALID);
                return true;
            }
            String normalized = InProcessFeishuAgentBridge.normalizeMode(requested);
            connections.mergeConnectionMetadata(connectionId, owner, Map.of(METADATA_MODE_KEY, normalized));
            reply(message.messageId(), FeishuInteraction.MODE_UPDATED + " `" + normalized + "`");
            return true;
        }
        if (WEB_ONLY_COMMAND.matcher(stripped).matches()) {
            reply(message.messageId(), FeishuInteraction.COMMAND_WEB_ONLY);
            return true;
        }
        Matcher steer = STEER_COMMAND.matcher(stripped);
        if (steer.matches()) {
            String threadId = resolveThread(connectionId, message, false);
            if (threadId == null || threadId.isBlank() || !agentBridge.hasActiveRun(owner, threadId)) {
                if (!allowNewTurn) {
                    reply(message.messageId(), FeishuInteraction.BUSY);
                    return true;
                }
                // No active run: treat as a normal new turn with the steer body.
                FeishuInboundMessage rewritten = new FeishuInboundMessage(
                        message.chatId(),
                        message.messageId(),
                        message.openId(),
                        steer.group(1).trim(),
                        message.chatType(),
                        message.messageType(),
                        message.attachments(),
                        null,
                        message.rootId(),
                        message.parentId(),
                        message.feishuThreadId());
                runChat(owner, connectionId, rewritten, steer.group(1).trim());
                return true;
            }
            try {
                agentBridge.runTurn(
                        owner,
                        threadId,
                        channelMetadata(connectionId, message, connection),
                        steer.group(1).trim(),
                        null,
                        List.of(),
                        FeishuAgentBridge.TurnOptions.defaults()
                                .withMode(readMode(connection))
                                .asSteer());
                reply(message.messageId(), FeishuInteraction.STEER_ACCEPTED);
            } catch (Exception failure) {
                reply(message.messageId(), FeishuInteraction.STEER_FAILED);
            }
            return true;
        }
        return false;
    }

    /**
     * {@code /new}: create a real SH Thread through the shared conversation use case and switch the
     * single-chat current pointer. Group {@code /new} without reply context anchors the new thread on
     * this command's message id (never the group topic key); a {@code /new} inside an existing topic
     * does not rebind that topic. No existing alias is re-pointed and no running Run is cancelled.
     */
    private void handleNewCommand(
            String owner, String connectionId, FeishuInboundMessage message, ObjectNode connection)
            throws Exception {
        String chat = message.chatId();
        boolean insideTopic = message.isGroupChat()
                && (!message.rootId().isBlank()
                        || !message.parentId().isBlank()
                        || !message.feishuThreadId().isBlank());
        if (insideTopic) {
            reply(message.messageId(), FeishuInteraction.NEW_THREAD_IN_TOPIC);
            return;
        }
        // Idempotent for redelivered events: claim this command's message id first. The first
        // delivery creates and anchors the thread; a retry reuses the winner without creating a new
        // thread or rotating the chat pointer again.
        String candidate = "feishu-" + java.util.UUID.randomUUID();
        String claimed = message.messageId().isBlank()
                ? candidate
                : threadMap.bindAliasIfAbsent(connectionId, chat, message.messageId(), candidate);
        if (!claimed.equals(candidate)) {
            replyAndBind(message, connectionId, claimed, FeishuInteraction.newThreadNotice(claimed));
            return;
        }
        agentBridge.createThread(owner, claimed, channelMetadata(connectionId, message, connection));
        if (!message.isGroupChat()) {
            threadMap.bindCurrent(connectionId, chat, claimed);
        }
        replyAndBind(message, connectionId, claimed, FeishuInteraction.newThreadNotice(claimed));
    }

    private static boolean isConnectCommand(FeishuInboundMessage message) {
        if (message == null || message.cardAction() != null) {
            return false;
        }
        return CONNECT_CODE.matcher(stripLeadingMentions(message.text().trim())).matches();
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
        if (FeishuInteraction.OTHER_OPTION.equals(message.cardAction().optionId())
                && message.cardAction().value().isBlank()) {
            return FeishuInteraction.EMPTY_OTHER;
        }
        // Form v2: do not reject an empty form_values map here — optional-only forms may submit
        // {} when untouched controls are omitted. Required-field checks run in continueCardAction.
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
            String threadId = resolveThread(connectionId, message, false);
            if (threadId == null || threadId.isBlank()) {
                return true;
            }
            FeishuPendingInput pending = agentBridge.currentPending(owner, threadId).orElse(null);
            FeishuInboundMessage.CardAction action = message.cardAction();
            return pending == null
                    || (pending.formUnsupported() && !pending.formSupported())
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
        // Card callbacks are read-only routing: only the immutable request_id alias may resolve them.
        String threadId = resolveThread(connectionId, message, false);
        FeishuPendingInput pending = threadId == null || threadId.isBlank()
                ? null
                : agentBridge.currentPending(owner, threadId).orElse(null);
        FeishuInboundMessage.CardAction action = message.cardAction();
        if (pending == null
                || (pending.formUnsupported() && !pending.formSupported())
                || !pending.requestId().equals(action.requestId())) {
            reply(message.messageId(), FeishuInteraction.ALREADY_HANDLED);
            return;
        }
        if (action.formSubmit()) {
            if (!pending.formSupported()) {
                reply(message.messageId(), FeishuInteraction.formNotice(pending, threadId));
                return;
            }
            if (FeishuInteraction.formMissingRequired(pending, action.formValues())) {
                reply(message.messageId(), FeishuInteraction.EMPTY_FORM);
                return;
            }
            String submitted = FeishuInteraction.buildFormSubmissionValue(pending, action.formValues());
            FeishuHumanResponse response = new FeishuHumanResponse(
                    pending.source(), pending.requestId(), "text", null, submitted);
            presentSubmitted(message, owner, connectionId, threadId, response, pending, connection);
            return;
        }
        if (FeishuInteraction.OTHER_OPTION.equals(action.optionId())) {
            String text = action.value() == null ? "" : action.value().trim();
            if (!"choice_with_other".equals(pending.inputMode()) || text.isEmpty()) {
                reply(message.messageId(), FeishuInteraction.EMPTY_OTHER);
                return;
            }
            FeishuHumanResponse response = new FeishuHumanResponse(
                    pending.source(), pending.requestId(), "text", null, text);
            presentSubmitted(message, owner, connectionId, threadId, response, pending, connection);
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
        presentSubmitted(message, owner, connectionId, threadId, response, pending, connection);
    }

    private void presentSubmitted(
            FeishuInboundMessage message,
            String owner,
            String connectionId,
            String threadId,
            FeishuHumanResponse response,
            FeishuPendingInput pending,
            ObjectNode connection)
            throws Exception {
        FeishuAgentBridge.TurnResult result =
                runSubmitted(owner, connectionId, message, threadId, response, pending, connection);
        present(message, result, threadId);
        deliverArtifactImages(message, result, owner, threadId);
        markProcessingSuccess(message.messageId());
    }

    /**
     * DeerFlow/Hermes parity for generated images: upload each image artifact and reply with a real
     * image message instead of a gateway-relative path Feishu cannot open. Bounded and best effort:
     * a delivery failure must never replace or delay the text reply already sent.
     */
    private void deliverArtifactImages(
            FeishuInboundMessage message,
            FeishuAgentBridge.TurnResult result,
            String owner,
            String threadId) {
        if (message == null || result == null || result.artifacts().isEmpty()) {
            return;
        }
        FeishuTransport active;
        synchronized (lock) {
            active = transport;
        }
        if (active == null) {
            return;
        }
        int sent = 0;
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (String path : result.artifacts()) {
            if (sent >= MAX_ARTIFACT_IMAGES) {
                return;
            }
            if (path == null || path.isBlank() || !seen.add(path) || !isArtifactImage(path)) {
                continue;
            }
            try {
                Optional<FeishuAgentBridge.ArtifactBytes> artifact =
                        agentBridge.readArtifact(owner, threadId, path);
                if (artifact.isEmpty()) {
                    continue;
                }
                FeishuAgentBridge.ArtifactBytes image = artifact.get();
                if (image.bytes() == null
                        || image.bytes().length == 0
                        || image.bytes().length > MAX_ARTIFACT_IMAGE_BYTES) {
                    continue;
                }
                Optional<String> imageKey = active.uploadImage(image.bytes(), image.filename());
                if (imageKey.isEmpty()) {
                    continue;
                }
                active.replyImage(message.messageId(), imageKey.get());
                sent++;
            } catch (Exception failure) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        "Feishu artifact image delivery failed (" + failure.getClass().getSimpleName() + ")");
            }
        }
    }

    private static boolean isArtifactImage(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return false;
        }
        return ARTIFACT_IMAGE_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private void runChat(
            String ownerUserId, String connectionId, FeishuInboundMessage message, String strippedText)
            throws Exception {
        String threadId = resolveAndRemember(connectionId, message);
        Optional<ObjectNode> bound = connections.findConnectionByExternalIdentity(
                "feishu", message.openId(), message.chatId());
        ObjectNode connection = bound.orElse(null);
        FeishuPendingInput pending = agentBridge.currentPending(ownerUserId, threadId).orElse(null);
        if (pending != null && pending.formUnsupported() && !pending.formSupported()) {
            replyUnsupportedFormHandoff(message, pending, threadId);
            return;
        }
        if (pending != null) {
            Optional<FeishuHumanResponse> matched = FeishuInteraction.match(pending, strippedText);
            if (matched.isPresent()) {
                presentSubmitted(
                        message, ownerUserId, connectionId, threadId, matched.get(), pending, connection);
                return;
            }
            if (pending.singleChoice()) {
                reply(message.messageId(), "haifa_interaction".equals(pending.source())
                        ? FeishuInteraction.APPROVAL_HINT
                        : FeishuInteraction.CHOICE_HINT);
                return;
            }
        }
        if (Boolean.TRUE.equals(chatBusy.putIfAbsent(busyKey(connectionId, threadId), Boolean.TRUE))) {
            handleBusyInbound(message);
            return;
        }
        try {
            List<FeishuAgentBridge.UploadedFile> files = materializeUploads(ownerUserId, threadId, message);
            String userText = strippedText == null ? message.text() : strippedText;
            if (userText.isBlank() && !files.isEmpty()) {
                userText = "请查看我发送的附件。";
            }
            markProcessingStart(message.messageId());
            Map<String, Object> metadata = channelMetadata(connectionId, message, connection);
            String mode = readMode(connection);
            FeishuAgentBridge.TurnResult result = agentBridge.runTurn(
                    ownerUserId,
                    threadId,
                    metadata,
                    userText,
                    null,
                    files,
                    FeishuAgentBridge.TurnOptions.defaults()
                            .withMode(mode)
                            .withProgress(summary -> reply(message.messageId(), summary)));
            present(message, result, threadId);
            deliverArtifactImages(message, result, ownerUserId, threadId);
            markProcessingSuccess(message.messageId());
        } finally {
            chatBusy.remove(busyKey(connectionId, threadId));
        }
    }

    private List<FeishuAgentBridge.UploadedFile> materializeUploads(
            String ownerUserId, String threadId, FeishuInboundMessage message) throws Exception {
        if (!message.hasDownloadableAttachments()) {
            return List.of();
        }
        FeishuTransport active;
        synchronized (lock) {
            active = transport;
        }
        if (active == null) {
            reply(message.messageId(), FeishuInteraction.MEDIA_FAILED);
            throw new IllegalStateException("Feishu transport unavailable for media download");
        }
        List<FeishuAgentBridge.UploadedFile> uploaded = new ArrayList<>();
        for (FeishuInboundMessage.Attachment attachment : message.attachments()) {
            if (!attachment.downloadable()) {
                continue;
            }
            byte[] bytes = active.downloadResource(
                    message.messageId(), attachment.resourceKey(), attachment.kind());
            if (bytes == null || bytes.length == 0) {
                reply(message.messageId(), FeishuInteraction.MEDIA_FAILED);
                throw new IllegalStateException("Feishu media download returned empty bytes");
            }
            String mediaType = "image".equals(attachment.kind()) ? "image/jpeg" : "application/octet-stream";
            uploaded.add(agentBridge.storeUpload(
                    ownerUserId, threadId, attachment.filename(), bytes, mediaType));
        }
        return uploaded;
    }

    private FeishuAgentBridge.TurnResult runSubmitted(
            String ownerUserId,
            String connectionId,
            FeishuInboundMessage message,
            String threadId,
            FeishuHumanResponse response,
            FeishuPendingInput pending,
            ObjectNode connection)
            throws Exception {
        if (Boolean.TRUE.equals(chatBusy.putIfAbsent(busyKey(connectionId, threadId), Boolean.TRUE))) {
            reply(message.messageId(), FeishuInteraction.BUSY);
            return new FeishuAgentBridge.TurnResult(threadId, FeishuInteraction.BUSY, pending);
        }
        try {
            String content = FeishuInteraction.responseContent(pending, response);
            markProcessingStart(message.messageId());
            FeishuAgentBridge.TurnResult result = agentBridge.runTurn(
                    ownerUserId,
                    threadId,
                    channelMetadata(connectionId, message, connection),
                    content,
                    response,
                    List.of(),
                    FeishuAgentBridge.TurnOptions.defaults()
                            .withMode(readMode(connection))
                            .withProgress(summary -> reply(message.messageId(), summary)));
            return result;
        } finally {
            chatBusy.remove(busyKey(connectionId, threadId));
        }
    }

    private static Map<String, Object> channelMetadata(
            String connectionId, FeishuInboundMessage message, ObjectNode connection) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("channel", "feishu");
        metadata.put("chat_id", message.chatId());
        metadata.put("connection_id", connectionId);
        metadata.put("feishu_open_id", message.openId());
        metadata.put(METADATA_MODE_KEY, readMode(connection));
        return metadata;
    }

    private static String readMode(ObjectNode connection) {
        if (connection == null) {
            return "flash";
        }
        String mode = connection.path("metadata").path(METADATA_MODE_KEY).asText("");
        return InProcessFeishuAgentBridge.normalizeMode(mode);
    }

    private void present(FeishuInboundMessage message, FeishuAgentBridge.TurnResult result, String threadId)
            throws Exception {
        FeishuPendingInput pending = result.pending();
        if (pending != null && pending.formUnsupported() && !pending.formSupported()) {
            replyUnsupportedFormHandoff(message, pending, threadId);
            return;
        }
        if (pending != null) {
            deliverInteractiveCard(message, pending, threadId);
            return;
        }
        String text = result.assistantText();
        if (text == null || text.isBlank()) {
            text = "(no assistant response)";
        }
        if (FeishuInteraction.BUSY.equals(text)) {
            return;
        }
        deliverTextAndBind(message, text, threadId);
    }

    /**
     * Delivers the interactive approval / clarification card. A rejected delivery is never treated
     * as success: it is logged with correlation identifiers (no message or form content) and the
     * pending request is re-sent as a plain text fallback so it cannot be silently lost. The
     * immutable {@code request_id} alias and the outbound card message id are bound to the thread so
     * a later callback or reply lands on the originating thread even after {@code /new}.
     */
    private void deliverInteractiveCard(FeishuInboundMessage message, FeishuPendingInput pending, String threadId)
            throws Exception {
        Map<String, Object> card = FeishuInteraction.interactiveCard(pending);
        String cardType = pending.formSupported() ? "form" : pending.inputMode();
        bindPendingRequest(message, pending, threadId);
        try {
            FeishuTransport active;
            synchronized (lock) {
                active = transport;
            }
            if (active == null) {
                return;
            }
            Optional<String> replyId = active.replyInteractiveId(message.messageId(), card);
            bindReplyId(message, replyId, threadId);
            LOG.log(
                    System.Logger.Level.INFO,
                    "feishu.card.delivered channel=feishu chat={0} thread={1} request={2} cardType={3}",
                    message.chatId(),
                    threadId,
                    pending.requestId(),
                    cardType);
        } catch (Exception failure) {
            FeishuDeliveryException delivery = FeishuDeliveryException.find(failure);
            LOG.log(
                    System.Logger.Level.WARNING,
                    "feishu.card.delivery.failed channel=feishu chat={0} thread={1} request={2} cardType={3} failure={4} code={5}",
                    message.chatId(),
                    threadId,
                    pending.requestId(),
                    cardType,
                    delivery == null ? failure.getClass().getSimpleName() : delivery.failure(),
                    delivery == null ? -1 : delivery.code());
            replyTextAndBind(message, FeishuInteraction.cardDeliveryFallback(pending, threadId), threadId);
        }
    }

    /**
     * Ordinary text / progress / error reply; binds the outbound message id to the thread (so a reply
     * to the assistant answer routes back) and falls back to a plain text message on card failure.
     */
    private void deliverTextAndBind(FeishuInboundMessage source, String text, String threadId) throws Exception {
        try {
            FeishuTransport active;
            synchronized (lock) {
                active = transport;
            }
            if (active == null) {
                return;
            }
            Optional<String> replyId = active.replyCardId(source.messageId(), text);
            bindReplyId(source, replyId, threadId);
        } catch (Exception failure) {
            FeishuDeliveryException delivery = FeishuDeliveryException.find(failure);
            LOG.log(
                    System.Logger.Level.WARNING,
                    "feishu.text.card.failed channel=feishu message={0} thread={1} failure={2} code={3}",
                    source.messageId(),
                    threadId,
                    delivery == null ? failure.getClass().getSimpleName() : delivery.failure(),
                    delivery == null ? -1 : delivery.code());
            replyTextAndBind(source, text, threadId);
        }
    }

    /** Bind the immutable {@code request_id} alias so card callbacks route to the originating thread. */
    private void bindPendingRequest(FeishuInboundMessage message, FeishuPendingInput pending, String threadId) {
        if (threadId == null || threadId.isBlank() || pending.requestId().isBlank()) {
            return;
        }
        connections.findConnectionByExternalIdentity("feishu", message.openId(), message.chatId())
                .ifPresent(connection -> threadMap.bindAliasIfAbsent(
                        connection.path("id").asText(""),
                        message.chatId(),
                        requestAlias(pending.requestId()),
                        threadId));
    }

    /** Bind a delivered outbound message id to its thread so a later reply routes back to it. */
    private void bindReplyId(FeishuInboundMessage source, Optional<String> replyId, String threadId) {
        if (replyId == null || replyId.isEmpty() || replyId.get().isBlank()
                || threadId == null || threadId.isBlank()) {
            return;
        }
        connections.findConnectionByExternalIdentity("feishu", source.openId(), source.chatId())
                .ifPresent(connection -> threadMap.bindAliasIfAbsent(
                        connection.path("id").asText(""), source.chatId(), replyId.get(), threadId));
    }

    /**
     * Plain-text fallback that still binds its provider message id to the thread, so a reply to the
     * fallback continues the pending request instead of starting a new group topic.
     */
    private void replyTextAndBind(FeishuInboundMessage source, String text, String threadId) throws Exception {
        FeishuTransport active;
        synchronized (lock) {
            active = transport;
        }
        if (active == null) {
            return;
        }
        Optional<String> replyId = active.replyTextId(source.messageId(), text);
        bindReplyId(source, replyId, threadId);
    }

    /** Reply on the source message and bind the outbound message id (used by {@code /new}). */
    private void replyAndBind(FeishuInboundMessage source, String connectionId, String threadId, String text)
            throws Exception {
        FeishuTransport active;
        synchronized (lock) {
            active = transport;
        }
        if (active == null) {
            return;
        }
        Optional<String> replyId = active.replyCardId(source.messageId(), text);
        if (replyId.isPresent() && !replyId.get().isBlank() && threadId != null && !threadId.isBlank()) {
            threadMap.bindAliasIfAbsent(connectionId, source.chatId(), replyId.get(), threadId);
        }
    }

    /**
     * Intentional Web handoff for a form the Feishu card subset cannot render. Logged distinctly so
     * operators can separate "by design" from an attempted-but-failed card delivery.
     */
    private void replyUnsupportedFormHandoff(
            FeishuInboundMessage message, FeishuPendingInput pending, String threadId) throws Exception {
        LOG.log(
                System.Logger.Level.INFO,
                "feishu.form.handoff channel=feishu chat={0} thread={1} request={2} reason=unsupported-form",
                message.chatId(),
                threadId,
                pending.requestId());
        reply(message.messageId(), FeishuInteraction.formNotice(pending, threadId));
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

    /**
     * Hermes processing-start: add {@code Typing} when reactions are enabled; otherwise fall back to
     * the legacy text progress card.
     */
    private void markProcessingStart(String messageId) throws Exception {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        if (!reactionsEnabled()) {
            reply(messageId, FeishuInteraction.PROGRESS);
            return;
        }
        synchronized (processingReactions) {
            if (processingReactions.containsKey(messageId)) {
                return;
            }
        }
        FeishuTransport active;
        synchronized (lock) {
            active = transport;
        }
        if (active == null) {
            reply(messageId, FeishuInteraction.PROGRESS);
            return;
        }
        Optional<String> reactionId = active.addReaction(messageId, FeishuInteraction.REACTION_IN_PROGRESS);
        if (reactionId.isPresent()) {
            processingReactions.put(messageId, reactionId.get());
            return;
        }
        // Reaction API unavailable — keep the user informed with the text ACK.
        reply(messageId, FeishuInteraction.PROGRESS);
    }

    /** Hermes success: clear Typing only (no DONE badge — reply itself is the success signal). */
    private void markProcessingSuccess(String messageId) {
        clearProcessingReaction(messageId);
    }

    /**
     * Hermes failure: clear Typing, then add CrossMark. If delete fails, skip CrossMark so the UI
     * does not show Typing and CrossMark together.
     */
    private void markProcessingFailure(String messageId) {
        if (messageId == null || messageId.isBlank() || !reactionsEnabled()) {
            return;
        }
        String reactionId;
        synchronized (processingReactions) {
            reactionId = processingReactions.get(messageId);
        }
        if (reactionId == null) {
            return;
        }
        FeishuTransport active;
        synchronized (lock) {
            active = transport;
        }
        if (active == null) {
            return;
        }
        if (!active.deleteReaction(messageId, reactionId)) {
            return;
        }
        processingReactions.remove(messageId);
        active.addReaction(messageId, FeishuInteraction.REACTION_FAILURE);
    }

    private void clearProcessingReaction(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        String reactionId;
        synchronized (processingReactions) {
            reactionId = processingReactions.remove(messageId);
        }
        if (reactionId == null) {
            return;
        }
        FeishuTransport active;
        synchronized (lock) {
            active = transport;
        }
        if (active == null) {
            return;
        }
        if (!active.deleteReaction(messageId, reactionId)) {
            // Keep the handle so LRU can eventually drop it; do not claim success.
            processingReactions.put(messageId, reactionId);
        }
    }

    /** Package/test seam: reactions on unless env/property explicitly disables. */
    static boolean reactionsEnabled() {
        String property = System.getProperty(REACTIONS_ENV);
        String env = System.getenv(REACTIONS_ENV);
        String value = property != null && !property.isBlank() ? property : env;
        if (value == null || value.isBlank()) {
            return true;
        }
        return !REACTIONS_OFF.contains(value.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public void close() {
        stop();
        connectExecutor.shutdownNow();
        chatExecutor.shutdownNow();
    }
}

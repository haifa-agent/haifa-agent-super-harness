package io.haifa.agent.superharness.server.testsupport.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.superharness.server.channels.feishu.FeishuChannelWorker;
import io.haifa.agent.superharness.server.channels.feishu.FeishuChatThreadStore;
import io.haifa.agent.superharness.server.channels.feishu.FeishuInboundMessage;
import io.haifa.agent.superharness.server.channels.feishu.FeishuOpenDomain;
import io.haifa.agent.superharness.server.channels.feishu.RecordingFeishuTransport;
import io.haifa.agent.superharness.server.config.ChannelConnectionStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared Feishu worker unit-test fixture: connections + thread map + scripted bridge + recording
 * transport, with a default owner/chat binding.
 */
public final class FeishuWorkerHarness implements AutoCloseable {
    public static final String OWNER_USER_ID = "ou_owner";
    public static final String DEFAULT_CHAT_ID = "oc_chat";
    public static final String DEFAULT_THREAD_ID = "feishu-test-thread";

    public final ObjectMapper json;
    public final ChannelConnectionStore connections;
    public final FeishuChatThreadStore threads;
    /** Scripted double when opened via {@link #open(Path)} / {@link #open(Path, ScriptedFeishuBridge)}. */
    public final ScriptedFeishuBridge bridge;
    public final RecordingFeishuTransport transport;
    public final FeishuChannelWorker worker;

    private FeishuWorkerHarness(
            ObjectMapper json,
            ChannelConnectionStore connections,
            FeishuChatThreadStore threads,
            ScriptedFeishuBridge bridge,
            RecordingFeishuTransport transport,
            FeishuChannelWorker worker) {
        this.json = json;
        this.connections = connections;
        this.threads = threads;
        this.bridge = bridge;
        this.transport = transport;
        this.worker = worker;
    }

    /** Open with a fresh {@link ScriptedFeishuBridge} ({@code continued:<text>} replies). */
    public static FeishuWorkerHarness open(Path dataDirectory) throws Exception {
        return open(dataDirectory, new ScriptedFeishuBridge());
    }

    public static FeishuWorkerHarness open(Path dataDirectory, ScriptedFeishuBridge bridge)
            throws Exception {
        return openInternal(dataDirectory, bridge);
    }

    private static FeishuWorkerHarness openInternal(Path dataDirectory, ScriptedFeishuBridge bridge)
            throws Exception {
        ObjectMapper json = new ObjectMapper();
        ChannelConnectionStore connections = new ChannelConnectionStore(json, dataDirectory);
        FeishuChatThreadStore threads = new FeishuChatThreadStore(json, dataDirectory);
        AtomicReference<RecordingFeishuTransport> transportRef = new AtomicReference<>();
        FeishuChannelWorker worker = new FeishuChannelWorker(
                connections,
                threads,
                bridge,
                () -> {
                    RecordingFeishuTransport transport = new RecordingFeishuTransport();
                    transportRef.set(transport);
                    return transport;
                });
        worker.startOrRestart("cli_x", "secret_x", FeishuOpenDomain.DEFAULT);
        FeishuWorkerHarness harness =
                new FeishuWorkerHarness(
                        json, connections, threads, bridge, transportRef.get(), worker);
        harness.bindChat(DEFAULT_CHAT_ID);
        return harness;
    }

    /** Bind owner {@link #OWNER_USER_ID} to {@code chatId}. */
    public void bindChat(String chatId) throws Exception {
        String code = ChannelConnectionStore.newBindingCode();
        connections.createOauthStateWithinCap(
                "owner",
                "feishu",
                code,
                java.time.Instant.now().plusSeconds(60),
                ChannelConnectionStore.MAX_PENDING_CONNECT_CODES_PER_PROVIDER);
        connections.bindFeishuFromConnectCode(code, OWNER_USER_ID, chatId, Map.of());
    }

    /** Pre-bind a stable current thread so command tests do not depend on UUID generation. */
    public FeishuWorkerHarness withStableThread() throws Exception {
        return withStableThread(DEFAULT_THREAD_ID);
    }

    public FeishuWorkerHarness withStableThread(String threadId) throws Exception {
        threads.bindCurrent(connectionId(), DEFAULT_CHAT_ID, threadId);
        return this;
    }

    /** Bind the immutable card request alias the way the production card-delivery path does. */
    public void bindRequest(String requestId) {
        bindRequest(requestId, DEFAULT_THREAD_ID);
    }

    public void bindRequest(String requestId, String threadId) {
        threads.bindAliasIfAbsent(
                connectionId(), DEFAULT_CHAT_ID, "request:" + requestId, threadId);
    }

    public String connectionId() {
        return connections
                .findConnectionByExternalIdentity("feishu", OWNER_USER_ID, DEFAULT_CHAT_ID)
                .orElseThrow()
                .path("id")
                .asText("");
    }

    public String connectionId(String chatId) {
        return connections
                .findConnectionByExternalIdentity("feishu", OWNER_USER_ID, chatId)
                .orElseThrow()
                .path("id")
                .asText("");
    }

    public static FeishuInboundMessage p2p(String messageId, String text) {
        return new FeishuInboundMessage(DEFAULT_CHAT_ID, messageId, OWNER_USER_ID, text, "p2p");
    }

    public static FeishuInboundMessage p2p(String chatId, String messageId, String text) {
        return new FeishuInboundMessage(chatId, messageId, OWNER_USER_ID, text, "p2p");
    }

    public String lastCardReply() {
        List<RecordingFeishuTransport.CardReply> replies = transport.replies();
        assertThat(replies).isNotEmpty();
        return replies.get(replies.size() - 1).text();
    }

    @Override
    public void close() {
        worker.close();
    }
}

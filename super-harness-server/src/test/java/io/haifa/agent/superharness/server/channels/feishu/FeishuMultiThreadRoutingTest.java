package io.haifa.agent.superharness.server.channels.feishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import io.haifa.agent.superharness.server.config.ChannelConnectionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression checks for durable Feishu chat pointers and historical reply aliases. */
class FeishuMultiThreadRoutingTest {
    @TempDir Path dataDirectory;

    @Test
    void newConversationRotatesOnlyActivePointerAndRetainsOldReply() {
        var store = new FeishuChatThreadStore(new ObjectMapper(), dataDirectory);
        String first = store.getOrCreate("connection", "chat");
        store.bindAlias("connection", "chat", "old-message", first);
        String second = store.startNew("connection", "chat");
        assertThat(second).isNotEqualTo(first);
        assertThat(store.find("connection", "chat")).contains(second);
        assertThat(store.findAlias("connection", "chat", "old-message")).contains(first);
        assertThat(new FeishuChatThreadStore(new ObjectMapper(), dataDirectory)
                .findAlias("connection", "chat", "old-message")).contains(first);
    }

    @Test
    void aliasIsImmutableAndScopedByConnectionAndChat() {
        var store = new FeishuChatThreadStore(new ObjectMapper(), dataDirectory);
        store.bindAlias("c1", "a", "m1", "thread-1");
        store.bindAlias("c1", "a", "m1", "thread-1");
        assertThat(store.findAlias("c1", "a", "m1")).contains("thread-1");
        assertThat(store.findAlias("c2", "a", "m1")).isEmpty();
        assertThat(store.findAlias("c1", "b", "m1")).isEmpty();
        assertThatThrownBy(() -> store.bindAlias("c1", "a", "m1", "thread-2"))
                .isInstanceOf(IllegalStateException.class);
    }


    @Test
    void workerNewStatusAndOldReplySelectTheCorrectThread() throws Exception {
        var connections = new ChannelConnectionStore(new ObjectMapper(), dataDirectory);
        var store = new FeishuChatThreadStore(new ObjectMapper(), dataDirectory);
        var seen = new ArrayList<String>();
        var replies = new ArrayList<String>();
        FeishuTransport transport = new FeishuTransport() {
            @Override public void start(String appId, String secret, String domain, InboundHandler handler) {}
            @Override public void stop() {}
            @Override public boolean running() { return true; }
            @Override public void replyCard(String messageId, String text) { replies.add(text); }
            @Override public void replyInteractive(String messageId, Map<String, Object> card) {}
        };
        var worker = new FeishuChannelWorker(
                connections, store,
                (owner, threadId, metadata, prompt) -> {
                    seen.add(threadId);
                    return new FeishuAgentBridge.TurnResult(threadId, "ok");
                },
                () -> transport);
        worker.startOrRestart("app", "secret", FeishuOpenDomain.DEFAULT);
        String code = ChannelConnectionStore.newBindingCode();
        connections.createOauthStateWithinCap("owner", "feishu", code,
                Instant.now().plusSeconds(60),
                ChannelConnectionStore.MAX_PENDING_CONNECT_CODES_PER_PROVIDER);
        connections.bindFeishuFromConnectCode(code, "user", "chat", Map.of());

        worker.handleInbound(new FeishuInboundMessage("chat", "message-A", "user", "hello", "p2p"));
        worker.handleInbound(new FeishuInboundMessage("chat", "command-new", "user", "/new", "p2p"));
        worker.handleInbound(new FeishuInboundMessage("chat", "message-B", "user", "another", "p2p"));
        worker.handleInbound(new FeishuInboundMessage(
                "chat", "reply-to-A", "user", "follow up", "p2p", "text", List.of(), null,
                "message-A", "message-A", ""));
        worker.handleInbound(new FeishuInboundMessage("chat", "command-status", "user", "/status", "p2p"));
        assertThat(seen).hasSize(3);
        assertThat(seen.get(0)).isNotEqualTo(seen.get(1));
        assertThat(seen.get(2)).isEqualTo(seen.get(0));
        assertThat(replies).anyMatch(msg -> msg.startsWith("Active thread: " + seen.get(1)));
        worker.close();
    }

    @Test
    void oldMapFileKeepsWorkingWithoutMigration() {
        var store = new FeishuChatThreadStore(new ObjectMapper(), dataDirectory);
        String original = store.getOrCreate("old", "chat");
        var restarted = new FeishuChatThreadStore(new ObjectMapper(), dataDirectory);
        assertThat(restarted.getOrCreate("old", "chat")).isEqualTo(original);
    }
}

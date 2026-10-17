package io.haifa.agent.superharness.server.channels.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.superharness.server.config.ChannelConnectionStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Hermes-aligned Typing / CrossMark processing reactions. */
class FeishuReactionsTest {
    private final ObjectMapper json = new ObjectMapper();
    private String previousProperty;

    @TempDir
    Path dataDirectory;

    @AfterEach
    void restoreProperty() {
        if (previousProperty == null) {
            System.clearProperty("HAIFA_SUPER_HARNESS_FEISHU_REACTIONS");
        } else {
            System.setProperty("HAIFA_SUPER_HARNESS_FEISHU_REACTIONS", previousProperty);
        }
    }

    @Test
    void successClearsTypingWithoutDoneBadge() throws Exception {
        rememberAndClearReactionsProperty();
        Harness harness = harness();
        harness.worker.handleInbound(new FeishuInboundMessage(
                "oc_chat", "om_ok", "ou_owner", "hello", "p2p"));

        assertThat(harness.transport.reactionAdds())
                .extracting(RecordingFeishuTransport.ReactionAdd::emojiType)
                .containsExactly(FeishuInteraction.REACTION_IN_PROGRESS);
        assertThat(harness.transport.reactionDeletes()).hasSize(1);
        assertThat(harness.transport.reactionDeletes().getFirst().success()).isTrue();
        assertThat(harness.transport.replies())
                .noneMatch(reply -> FeishuInteraction.PROGRESS.equals(reply.text()));
        assertThat(harness.transport.replies().getLast().text()).isEqualTo("done:hello");
        harness.worker.close();
    }

    @Test
    void failureClearsTypingThenAddsCrossMark() throws Exception {
        rememberAndClearReactionsProperty();
        Harness harness = harnessFailing();
        harness.transport.deliverAndWait(
                new FeishuInboundMessage("oc_chat", "om_bad", "ou_owner", "boom", "p2p"), 500);

        List<String> emojis = harness.transport.reactionAdds().stream()
                .map(RecordingFeishuTransport.ReactionAdd::emojiType)
                .toList();
        assertThat(emojis)
                .containsExactly(
                        FeishuInteraction.REACTION_IN_PROGRESS, FeishuInteraction.REACTION_FAILURE);
        assertThat(harness.transport.reactionDeletes()).hasSize(1);
        assertThat(harness.transport.replies().getLast().text()).contains("An error occurred");
        harness.worker.close();
    }

    @Test
    void deleteFailureDoesNotStackCrossMark() throws Exception {
        rememberAndClearReactionsProperty();
        Harness harness = harnessFailing();
        harness.transport.failNextDelete();
        harness.transport.deliverAndWait(
                new FeishuInboundMessage("oc_chat", "om_stuck", "ou_owner", "boom", "p2p"), 500);

        assertThat(harness.transport.reactionAdds())
                .extracting(RecordingFeishuTransport.ReactionAdd::emojiType)
                .containsExactly(FeishuInteraction.REACTION_IN_PROGRESS);
        assertThat(harness.transport.reactionDeletes()).hasSize(1);
        assertThat(harness.transport.reactionDeletes().getFirst().success()).isFalse();
        harness.worker.close();
    }

    @Test
    void disabledReactionsFallBackToTextProgress() throws Exception {
        rememberAndClearReactionsProperty();
        System.setProperty("HAIFA_SUPER_HARNESS_FEISHU_REACTIONS", "false");
        assertThat(FeishuChannelWorker.reactionsEnabled()).isFalse();

        Harness harness = harness();
        harness.worker.handleInbound(new FeishuInboundMessage(
                "oc_chat", "om_plain", "ou_owner", "hello", "p2p"));

        assertThat(harness.transport.reactionAdds()).isEmpty();
        assertThat(harness.transport.replies().getFirst().text()).isEqualTo(FeishuInteraction.PROGRESS);
        assertThat(harness.transport.replies().getLast().text()).isEqualTo("done:hello");
        harness.worker.close();
    }

    private void rememberAndClearReactionsProperty() {
        previousProperty = System.getProperty("HAIFA_SUPER_HARNESS_FEISHU_REACTIONS");
        System.clearProperty("HAIFA_SUPER_HARNESS_FEISHU_REACTIONS");
    }

    private Harness harness() throws Exception {
        return harnessWithBridge((owner, threadId, metadata, text) ->
                new FeishuAgentBridge.TurnResult(threadId, "done:" + text));
    }

    private Harness harnessFailing() throws Exception {
        return harnessWithBridge((owner, threadId, metadata, text) -> {
            throw new IllegalStateException("model unavailable");
        });
    }

    private Harness harnessWithBridge(FeishuAgentBridge bridge) throws Exception {
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
        String code = ChannelConnectionStore.newBindingCode();
        connections.createOauthStateWithinCap(
                "owner",
                "feishu",
                code,
                java.time.Instant.now().plusSeconds(60),
                ChannelConnectionStore.MAX_PENDING_CONNECT_CODES_PER_PROVIDER);
        connections.bindFeishuFromConnectCode(code, "ou_owner", "oc_chat", Map.of());
        return new Harness(worker, transportRef.get());
    }

    private record Harness(FeishuChannelWorker worker, RecordingFeishuTransport transport) {}
}

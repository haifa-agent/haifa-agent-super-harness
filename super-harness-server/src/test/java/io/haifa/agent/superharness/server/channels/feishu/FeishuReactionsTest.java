package io.haifa.agent.superharness.server.channels.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.superharness.server.testsupport.feishu.FeishuWorkerHarness;
import io.haifa.agent.superharness.server.testsupport.feishu.ScriptedFeishuBridge;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Hermes-aligned Typing / CrossMark processing reactions. */
class FeishuReactionsTest {
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
        try (FeishuWorkerHarness harness = reactionsHarness()) {
            harness.worker.handleInbound(FeishuWorkerHarness.p2p("om_ok", "hello"));

            assertThat(harness.transport.reactionAdds())
                    .extracting(RecordingFeishuTransport.ReactionAdd::emojiType)
                    .containsExactly(FeishuInteraction.REACTION_IN_PROGRESS);
            assertThat(harness.transport.reactionDeletes()).hasSize(1);
            assertThat(harness.transport.reactionDeletes().getFirst().success()).isTrue();
            assertThat(harness.transport.replies())
                    .noneMatch(reply -> FeishuInteraction.PROGRESS.equals(reply.text()));
            assertThat(harness.transport.replies().getLast().text()).isEqualTo("done:hello");
        }
    }

    @Test
    void failureClearsTypingThenAddsCrossMark() throws Exception {
        rememberAndClearReactionsProperty();
        try (FeishuWorkerHarness harness = failingHarness()) {
            harness.transport.deliverAndWait(FeishuWorkerHarness.p2p("om_bad", "boom"), 500);

            List<String> emojis = harness.transport.reactionAdds().stream()
                    .map(RecordingFeishuTransport.ReactionAdd::emojiType)
                    .toList();
            assertThat(emojis)
                    .containsExactly(
                            FeishuInteraction.REACTION_IN_PROGRESS, FeishuInteraction.REACTION_FAILURE);
            assertThat(harness.transport.reactionDeletes()).hasSize(1);
            assertThat(harness.transport.replies().getLast().text()).contains("An error occurred");
        }
    }

    @Test
    void deleteFailureDoesNotStackCrossMark() throws Exception {
        rememberAndClearReactionsProperty();
        try (FeishuWorkerHarness harness = failingHarness()) {
            harness.transport.failNextDelete();
            harness.transport.deliverAndWait(FeishuWorkerHarness.p2p("om_stuck", "boom"), 500);

            assertThat(harness.transport.reactionAdds())
                    .extracting(RecordingFeishuTransport.ReactionAdd::emojiType)
                    .containsExactly(FeishuInteraction.REACTION_IN_PROGRESS);
            assertThat(harness.transport.reactionDeletes()).hasSize(1);
            assertThat(harness.transport.reactionDeletes().getFirst().success()).isFalse();
        }
    }

    @Test
    void disabledReactionsFallBackToTextProgress() throws Exception {
        rememberAndClearReactionsProperty();
        System.setProperty("HAIFA_SUPER_HARNESS_FEISHU_REACTIONS", "false");
        assertThat(FeishuChannelWorker.reactionsEnabled()).isFalse();

        try (FeishuWorkerHarness harness = reactionsHarness()) {
            harness.worker.handleInbound(FeishuWorkerHarness.p2p("om_plain", "hello"));

            assertThat(harness.transport.reactionAdds()).isEmpty();
            assertThat(harness.transport.replies().getFirst().text()).isEqualTo(FeishuInteraction.PROGRESS);
            assertThat(harness.transport.replies().getLast().text()).isEqualTo("done:hello");
        }
    }

    private void rememberAndClearReactionsProperty() {
        previousProperty = System.getProperty("HAIFA_SUPER_HARNESS_FEISHU_REACTIONS");
        System.clearProperty("HAIFA_SUPER_HARNESS_FEISHU_REACTIONS");
    }

    private FeishuWorkerHarness reactionsHarness() throws Exception {
        return FeishuWorkerHarness.open(
                dataDirectory, new ScriptedFeishuBridge().withDoneAssistantReplies());
    }

    private FeishuWorkerHarness failingHarness() throws Exception {
        ScriptedFeishuBridge bridge = new ScriptedFeishuBridge().withDoneAssistantReplies();
        bridge.failTurns.set(true);
        return FeishuWorkerHarness.open(dataDirectory, bridge);
    }
}

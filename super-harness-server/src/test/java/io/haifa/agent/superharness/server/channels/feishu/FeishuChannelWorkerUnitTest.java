package io.haifa.agent.superharness.server.channels.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.superharness.server.config.ChannelConnectionStore;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Focused worker/transport unit checks without a full Gateway listen. */
class FeishuChannelWorkerUnitTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path dataDirectory;

    @Test
    void stripLeadingMentionsMatchesDeerFlowCommandPath() {
        assertThat(FeishuChannelWorker.stripLeadingMentions("@_user_1 /connect abc"))
                .isEqualTo("/connect abc");
        assertThat(FeishuChannelWorker.stripLeadingMentions("@bot <@U123> /connect abc"))
                .isEqualTo("/connect abc");
        assertThat(FeishuChannelWorker.stripLeadingMentions("/connect abc")).isEqualTo("/connect abc");
        assertThat(FeishuChannelWorker.stripLeadingMentions(" /connect abc")).isEqualTo(" /connect abc");
        assertThat(FeishuChannelWorker.stripLeadingMentions("hello @bot")).isEqualTo("hello @bot");
    }

    @Test
    void cardPayloadUsesDeerFlowMarkdownElement() {
        @SuppressWarnings("unchecked")
        Map<String, Object> card = (Map<String, Object>) LarkSdkFeishuTransport.cardPayload("**hi**");
        assertThat(card.get("config")).isEqualTo(Map.of("wide_screen_mode", true, "update_multi", true));
        assertThat(card.get("elements")).isEqualTo(java.util.List.of(Map.of("tag", "markdown", "content", "**hi**")));
    }

    @Test
    void inboundExecutorRepliesWhenRunTurnFails() throws Exception {
        ChannelConnectionStore connections = new ChannelConnectionStore(json, dataDirectory);
        FeishuChatThreadStore threads = new FeishuChatThreadStore(json, dataDirectory);
        AtomicReference<RecordingFeishuTransport> transportRef = new AtomicReference<>();
        FeishuChannelWorker worker = new FeishuChannelWorker(
                connections,
                threads,
                (owner, threadId, metadata, text) -> {
                    throw new IllegalStateException("model unavailable");
                },
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
        connections.bindFeishuFromConnectCode(code, "ou_fail", "oc_fail", Map.of());

        RecordingFeishuTransport transport = transportRef.get();
        transport.deliverAndWait(
                new FeishuInboundMessage("oc_fail", "om_fail", "ou_fail", "please fail", "p2p"), 500);

        assertThat(transport.replies()).hasSize(1);
        assertThat(transport.replies().getFirst().messageId()).isEqualTo("om_fail");
        assertThat(transport.replies().getFirst().text()).contains("An error occurred");
        worker.close();
    }
}

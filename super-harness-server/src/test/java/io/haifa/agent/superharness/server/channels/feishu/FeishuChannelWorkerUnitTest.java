package io.haifa.agent.superharness.server.channels.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.superharness.server.config.ChannelConnectionStore;
import java.nio.file.Path;
import java.util.List;
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
    void larkParseContentRecognizesImageFileAndLeavesStickerRunnableFalse() {
        var image = LarkSdkFeishuTransport.parseContent("image", "{\"image_key\":\"img_abc\"}");
        assertThat(image.text()).isEmpty();
        assertThat(image.attachments()).hasSize(1);
        assertThat(image.attachments().getFirst().kind()).isEqualTo("image");
        assertThat(image.attachments().getFirst().resourceKey()).isEqualTo("img_abc");

        var file = LarkSdkFeishuTransport.parseContent(
                "file", "{\"file_key\":\"file_1\",\"file_name\":\"notes.pdf\"}");
        assertThat(file.attachments().getFirst().filename()).isEqualTo("notes.pdf");

        var sticker = LarkSdkFeishuTransport.parseContent("sticker", "{\"file_key\":\"sticker_1\"}");
        FeishuInboundMessage stickerMessage = new FeishuInboundMessage(
                "oc", "om", "ou", sticker.text(), "p2p", "sticker", sticker.attachments(), null);
        assertThat(stickerMessage.isRunnableInbound()).isFalse();
    }

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

        assertThat(transport.replies()).hasSize(2);
        assertThat(transport.replies().getFirst().messageId()).isEqualTo("om_fail");
        assertThat(transport.replies().getFirst().text()).isEqualTo(FeishuInteraction.PROGRESS);
        assertThat(transport.replies().getLast().text()).contains("An error occurred");
        worker.close();
    }

    @Test
    void unsupportedMediaIsAcknowledgedWithoutRunning() throws Exception {
        ChannelConnectionStore connections = new ChannelConnectionStore(json, dataDirectory);
        FeishuChatThreadStore threads = new FeishuChatThreadStore(json, dataDirectory);
        AtomicReference<RecordingFeishuTransport> transportRef = new AtomicReference<>();
        AtomicReference<Integer> turns = new AtomicReference<>(0);
        FeishuChannelWorker worker = new FeishuChannelWorker(
                connections,
                threads,
                (owner, threadId, metadata, text) -> {
                    turns.updateAndGet(n -> n + 1);
                    return new FeishuAgentBridge.TurnResult(threadId, "should-not-run");
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
        connections.bindFeishuFromConnectCode(code, "ou_sticker", "oc_sticker", Map.of());

        RecordingFeishuTransport transport = transportRef.get();
        worker.handleInbound(new FeishuInboundMessage(
                "oc_sticker",
                "om_sticker",
                "ou_sticker",
                "",
                "p2p",
                "sticker",
                List.of(),
                null));

        assertThat(turns.get()).isZero();
        assertThat(transport.replies()).hasSize(1);
        assertThat(transport.replies().getFirst().text()).isEqualTo(FeishuInteraction.UNSUPPORTED_MEDIA);
        worker.close();
    }

    @Test
    void imageInboundDownloadsUploadsAndRunsWithFiles() throws Exception {
        ChannelConnectionStore connections = new ChannelConnectionStore(json, dataDirectory);
        FeishuChatThreadStore threads = new FeishuChatThreadStore(json, dataDirectory);
        AtomicReference<RecordingFeishuTransport> transportRef = new AtomicReference<>();
        AtomicReference<List<FeishuAgentBridge.UploadedFile>> captured = new AtomicReference<>();
        FeishuChannelWorker worker = new FeishuChannelWorker(
                connections,
                threads,
                new FeishuAgentBridge() {
                    @Override
                    public TurnResult runTurn(
                            String ownerUserId,
                            String threadId,
                            Map<String, Object> channelMetadata,
                            String userText) {
                        return runTurn(ownerUserId, threadId, channelMetadata, userText, null, List.of());
                    }

                    @Override
                    public TurnResult runTurn(
                            String ownerUserId,
                            String threadId,
                            Map<String, Object> channelMetadata,
                            String userText,
                            FeishuHumanResponse response,
                            List<UploadedFile> files) {
                        captured.set(List.copyOf(files));
                        return new TurnResult(threadId, "saw " + files.size() + " file(s)");
                    }

                    @Override
                    public UploadedFile storeUpload(
                            String ownerUserId,
                            String threadId,
                            String filename,
                            byte[] content,
                            String mediaType) {
                        return new UploadedFile(
                                filename, "/mnt/user-data/uploads/" + filename, content.length);
                    }
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
        connections.bindFeishuFromConnectCode(code, "ou_img", "oc_img", Map.of());

        RecordingFeishuTransport transport = transportRef.get();
        byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};
        transport.putResource("om_img", "img_key_1", jpeg);
        worker.handleInbound(new FeishuInboundMessage(
                "oc_img",
                "om_img",
                "ou_img",
                "",
                "p2p",
                "image",
                List.of(new FeishuInboundMessage.Attachment("image", "img_key_1", "image.jpg")),
                null));

        assertThat(captured.get()).isNotNull().hasSize(1);
        assertThat(captured.get().getFirst().filename()).isEqualTo("image.jpg");
        assertThat(captured.get().getFirst().path()).contains("/mnt/user-data/uploads/");
        assertThat(transport.replies().getFirst().text()).isEqualTo(FeishuInteraction.PROGRESS);
        assertThat(transport.replies().getLast().text()).contains("saw 1 file(s)");
        worker.close();
    }

    @Test
    void busyChatGetsBoundedNoticeWhileConnectStillWorks() throws Exception {
        ChannelConnectionStore connections = new ChannelConnectionStore(json, dataDirectory);
        FeishuChatThreadStore threads = new FeishuChatThreadStore(json, dataDirectory);
        AtomicReference<RecordingFeishuTransport> transportRef = new AtomicReference<>();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        FeishuChannelWorker worker = new FeishuChannelWorker(
                connections,
                threads,
                new FeishuAgentBridge() {
                    @Override
                    public TurnResult runTurn(
                            String ownerUserId,
                            String threadId,
                            Map<String, Object> channelMetadata,
                            String userText)
                            throws Exception {
                        entered.countDown();
                        if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
                            throw new IllegalStateException("release timed out");
                        }
                        return new TurnResult(threadId, "done:" + userText);
                    }
                },
                () -> {
                    RecordingFeishuTransport transport = new RecordingFeishuTransport();
                    transportRef.set(transport);
                    return transport;
                });
        worker.startOrRestart("cli_x", "secret_x", FeishuOpenDomain.DEFAULT);
        String codeA = ChannelConnectionStore.newBindingCode();
        connections.createOauthStateWithinCap(
                "owner-a",
                "feishu",
                codeA,
                java.time.Instant.now().plusSeconds(60),
                ChannelConnectionStore.MAX_PENDING_CONNECT_CODES_PER_PROVIDER);
        connections.bindFeishuFromConnectCode(codeA, "ou_busy", "oc_busy", Map.of());

        RecordingFeishuTransport transport = transportRef.get();
        transport.deliver(new FeishuInboundMessage("oc_busy", "om_long", "ou_busy", "long run", "p2p"));
        assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        worker.handleInbound(new FeishuInboundMessage("oc_busy", "om_again", "ou_busy", "ping", "p2p"));
        assertThat(transport.replies())
                .anyMatch(reply -> FeishuInteraction.BUSY.equals(reply.text()));

        String codeB = ChannelConnectionStore.newBindingCode();
        connections.createOauthStateWithinCap(
                "owner-b",
                "feishu",
                codeB,
                java.time.Instant.now().plusSeconds(60),
                ChannelConnectionStore.MAX_PENDING_CONNECT_CODES_PER_PROVIDER);
        long connectStarted = System.nanoTime();
        transport.deliverAndWait(
                new FeishuInboundMessage(
                        "oc_other", "om_connect", "ou_other", "/connect " + codeB, "p2p"),
                800);
        long connectMillis = (System.nanoTime() - connectStarted) / 1_000_000L;
        assertThat(connectMillis).isLessThan(2_500L);
        assertThat(transport.replies())
                .anyMatch(reply -> reply.text().contains("Feishu connected to DeerFlow."));

        release.countDown();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (transport.replies().stream().noneMatch(r -> r.text().startsWith("done:"))
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(transport.replies()).anyMatch(reply -> reply.text().startsWith("done:"));
        worker.close();
    }

    @Test
    void busyAckDoesNotWaitBehindSaturatedChatWorkers() throws Exception {
        ChannelConnectionStore connections = new ChannelConnectionStore(json, dataDirectory);
        FeishuChatThreadStore threads = new FeishuChatThreadStore(json, dataDirectory);
        AtomicReference<RecordingFeishuTransport> transportRef = new AtomicReference<>();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(4);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        FeishuChannelWorker worker = new FeishuChannelWorker(
                connections,
                threads,
                new FeishuAgentBridge() {
                    @Override
                    public TurnResult runTurn(
                            String ownerUserId,
                            String threadId,
                            Map<String, Object> channelMetadata,
                            String userText)
                            throws Exception {
                        entered.countDown();
                        if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                            throw new IllegalStateException("release timed out");
                        }
                        return new TurnResult(threadId, "done:" + userText);
                    }
                },
                () -> {
                    RecordingFeishuTransport transport = new RecordingFeishuTransport();
                    transportRef.set(transport);
                    return transport;
                });
        worker.startOrRestart("cli_x", "secret_x", FeishuOpenDomain.DEFAULT);

        RecordingFeishuTransport transport = transportRef.get();
        for (int index = 0; index < 4; index++) {
            String openId = "ou_sat_" + index;
            String chatId = "oc_sat_" + index;
            String code = ChannelConnectionStore.newBindingCode();
            connections.createOauthStateWithinCap(
                    "owner-" + index,
                    "feishu",
                    code,
                    java.time.Instant.now().plusSeconds(60),
                    ChannelConnectionStore.MAX_PENDING_CONNECT_CODES_PER_PROVIDER);
            connections.bindFeishuFromConnectCode(code, openId, chatId, Map.of());
            transport.deliver(new FeishuInboundMessage(
                    chatId, "om_long_" + index, openId, "long-" + index, "p2p"));
        }
        assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        long busyStarted = System.nanoTime();
        transport.deliver(new FeishuInboundMessage("oc_sat_0", "om_busy_again", "ou_sat_0", "again", "p2p"));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (transport.replies().stream().noneMatch(r -> FeishuInteraction.BUSY.equals(r.text()))
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        long busyMillis = (System.nanoTime() - busyStarted) / 1_000_000L;
        assertThat(transport.replies())
                .as("replies under saturation: %s", transport.replies())
                .anyMatch(reply -> "om_busy_again".equals(reply.messageId())
                        && FeishuInteraction.BUSY.equals(reply.text()));
        assertThat(busyMillis).as("BUSY must not wait for a free Turn worker").isLessThan(1_500L);

        release.countDown();
        worker.close();
    }

    @Test
    void storedUploadFilenameUsesLogicalPathBasename() {
        assertThat(InProcessFeishuAgentBridge.storedUploadFilename("/mnt/user-data/uploads/image.jpg"))
                .isEqualTo("image.jpg");
        assertThat(InProcessFeishuAgentBridge.storedUploadFilename(
                        "/mnt/user-data/uploads/image__abcdef012345.jpg"))
                .isEqualTo("image__abcdef012345.jpg");
    }
}

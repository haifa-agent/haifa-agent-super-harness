package io.haifa.agent.superharness.server.channels.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.server.config.ChannelConnectionStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 2 Feishu interaction: Form v2, mode, Stop/Steer, approval metadata. */
class FeishuPhase2InteractionTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path dataDirectory;

    @Test
    void formV2CardAndSubmissionMatchWebTextProtocol() throws Exception {
        ArrayNode messages = json.createArrayNode();
        ObjectNode form = toolForm(
                "req-form",
                List.of(
                        field("version", "版本号", "text", true),
                        field("env", "环境", "select", true, List.of(option("prod", "生产", "prod")))));
        messages.add(form);
        FeishuPendingInput pending = FeishuInteraction.pendingFromMessages(messages).orElseThrow();
        assertThat(pending.formUnsupported()).isFalse();
        assertThat(pending.formSupported()).isTrue();
        Map<String, Object> card = FeishuInteraction.interactiveCard(pending);
        assertThat(card.get("schema")).isEqualTo("2.0");
        assertThat(card.toString()).contains("human_input_form").contains("提交");

        Map<String, Object> values = Map.of("version", "1.2.3", "env", "prod");
        String submitted = FeishuInteraction.buildFormSubmissionValue(pending, values);
        assertThat(submitted).contains("版本号: 1.2.3").contains("环境: prod").contains("[values:");
        assertThat(submitted).contains("\"version\":\"1.2.3\"").contains("\"env\":\"prod\"");

        Harness harness = harness();
        harness.bridge.pending = pending;
        harness.worker.handleInbound(new FeishuInboundMessage(
                "oc_chat",
                "om_form",
                "ou_owner",
                "",
                "p2p",
                new FeishuInboundMessage.CardAction(
                        "ask_clarification",
                        "req-form",
                        FeishuInteraction.FORM_SUBMIT,
                        "",
                        "2.0",
                        values)));
        assertThat(harness.bridge.turns.get()).isEqualTo(1);
        assertThat(harness.bridge.responses.get()).isNotNull();
        assertThat(harness.bridge.responses.get().responseKind()).isEqualTo("text");
        assertThat(harness.bridge.responses.get().value()).contains("[values:");
        harness.worker.close();
    }

    @Test
    void approvalCardIncludesRiskAndEnvironmentFacts() throws Exception {
        ArrayNode messages = json.createArrayNode();
        ObjectNode card = toolCard("req-1", "haifa_interaction", "single_choice", "是否允许写入？");
        ObjectNode human = (ObjectNode) card.path("artifact").path("human_input");
        human.put("risk_level", "HIGH");
        human.put("purpose", "写入部署脚本");
        human.putArray("environment_facts")
                .addObject()
                .put("label", "cwd")
                .put("value", "/tmp/demo");
        human.putArray("technical_facts")
                .addObject()
                .put("label", "tool")
                .put("value", "write_file");
        human.putArray("options")
                .addObject()
                .put("id", "approve")
                .put("label", "Approve")
                .put("value", "approve");
        messages.add(card);
        FeishuPendingInput pending = FeishuInteraction.pendingFromMessages(messages).orElseThrow();
        Map<String, Object> interactive = FeishuInteraction.interactiveCard(pending);
        String markdown = interactive.toString();
        assertThat(markdown).contains("风险级别").contains("HIGH");
        assertThat(markdown).contains("环境事实").contains("cwd");
        assertThat(markdown).contains("技术事实").contains("write_file");
    }

    @Test
    void modeCommandPersistsOnConnectionMetadata() throws Exception {
        Harness harness = harness();
        harness.worker.handleInbound(new FeishuInboundMessage(
                "oc_chat", "om_mode", "ou_owner", "/mode thinking", "p2p"));
        assertThat(harness.transport.replies().getLast().text())
                .contains(FeishuInteraction.MODE_UPDATED)
                .contains("thinking");
        var listed = harness.connections.findConnectionByExternalIdentity("feishu", "ou_owner", "oc_chat");
        assertThat(listed).isPresent();
        assertThat(listed.get().path("metadata").path("run_mode").asText()).isEqualTo("thinking");

        harness.worker.handleInbound(new FeishuInboundMessage(
                "oc_chat", "om_bad", "ou_owner", "/mode turbo", "p2p"));
        assertThat(harness.transport.replies().getLast().text()).isEqualTo(FeishuInteraction.MODE_INVALID);
        harness.worker.close();
    }

    @Test
    void stopAndSteerWhileBusyUseSharedBridgeSeams() throws Exception {
        Harness harness = harness();
        harness.bridge.activeRun.set(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        harness.bridge.blockEnter = entered;
        harness.bridge.blockRelease = release;

        Thread longTurn = new Thread(() -> {
            try {
                harness.worker.handleInbound(new FeishuInboundMessage(
                        "oc_chat", "om_long", "ou_owner", "long run", "p2p"));
            } catch (Exception ignored) {
            }
        });
        longTurn.start();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        harness.worker.handleInbound(new FeishuInboundMessage(
                "oc_chat", "om_steer", "ou_owner", "please also check Germany", "p2p"));
        assertThat(harness.bridge.steerCalls.get()).isEqualTo(1);
        assertThat(harness.transport.replies())
                .anyMatch(reply -> FeishuInteraction.STEER_ACCEPTED.equals(reply.text()));

        harness.worker.handleInbound(new FeishuInboundMessage(
                "oc_chat", "om_stop", "ou_owner", "/stop", "p2p"));
        assertThat(harness.bridge.cancelCalls.get()).isEqualTo(1);
        assertThat(harness.transport.replies())
                .anyMatch(reply -> FeishuInteraction.STOPPED.equals(reply.text()));

        release.countDown();
        longTurn.join(3_000);
        harness.worker.close();
    }

    @Test
    void busyPathRoutesModeAndWebOnlyBeforeSteerFallback() throws Exception {
        Harness harness = harness();
        harness.bridge.activeRun.set(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        harness.bridge.blockEnter = entered;
        harness.bridge.blockRelease = release;

        Thread longTurn = new Thread(() -> {
            try {
                harness.worker.handleInbound(new FeishuInboundMessage(
                        "oc_chat", "om_long", "ou_owner", "long run", "p2p"));
            } catch (Exception ignored) {
            }
        });
        longTurn.start();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        harness.worker.handleInbound(new FeishuInboundMessage(
                "oc_chat", "om_mode", "ou_owner", "/mode thinking", "p2p"));
        assertThat(harness.bridge.steerCalls.get()).isZero();
        assertThat(harness.transport.replies())
                .anyMatch(reply -> reply.text().contains(FeishuInteraction.MODE_UPDATED)
                        && reply.text().contains("thinking"));
        assertThat(harness.connections
                        .findConnectionByExternalIdentity("feishu", "ou_owner", "oc_chat")
                        .orElseThrow()
                        .path("metadata")
                        .path("run_mode")
                        .asText())
                .isEqualTo("thinking");

        harness.worker.handleInbound(new FeishuInboundMessage(
                "oc_chat", "om_skill", "ou_owner", "/skill demo", "p2p"));
        assertThat(harness.bridge.steerCalls.get()).isZero();
        assertThat(harness.transport.replies())
                .anyMatch(reply -> FeishuInteraction.COMMAND_WEB_ONLY.equals(reply.text()));

        release.countDown();
        longTurn.join(3_000);
        harness.worker.close();
    }

    @Test
    void optionalOnlyFormAcceptsEmptyFormValues() throws Exception {
        ArrayNode messages = json.createArrayNode();
        messages.add(toolForm(
                "req-opt",
                List.of(field("notes", "备注", "text", false))));
        FeishuPendingInput pending = FeishuInteraction.pendingFromMessages(messages).orElseThrow();
        assertThat(pending.formSupported()).isTrue();
        assertThat(FeishuInteraction.formMissingRequired(pending, Map.of())).isFalse();

        Harness harness = harness();
        harness.bridge.pending = pending;
        String toast = harness.worker.acceptCardAction(new FeishuInboundMessage(
                "oc_chat",
                "om_opt",
                "ou_owner",
                "",
                "p2p",
                new FeishuInboundMessage.CardAction(
                        "ask_clarification",
                        "req-opt",
                        FeishuInteraction.FORM_SUBMIT,
                        "",
                        "2.0",
                        Map.of())));
        assertThat(toast).isEqualTo(FeishuInteraction.PROCESSING);
        // Drain the enqueued card action on the chat executor.
        Thread.sleep(400);
        assertThat(harness.bridge.turns.get()).isEqualTo(1);
        assertThat(harness.bridge.responses.get()).isNotNull();
        assertThat(harness.bridge.responses.get().value()).contains("[values: {}]");
        harness.worker.close();
    }

    @Test
    void runTurnReceivesConfiguredMode() throws Exception {
        Harness harness = harness();
        harness.connections.mergeConnectionMetadata(
                harness.connections
                        .findConnectionByExternalIdentity("feishu", "ou_owner", "oc_chat")
                        .orElseThrow()
                        .path("id")
                        .asText(),
                "owner",
                Map.of("run_mode", "pro"));
        harness.worker.handleInbound(new FeishuInboundMessage(
                "oc_chat", "om_chat", "ou_owner", "hello", "p2p"));
        assertThat(harness.bridge.lastMode.get()).isEqualTo("pro");
        harness.worker.close();
    }

    private ObjectNode toolForm(String requestId, List<FeishuPendingInput.Field> fields) {
        ObjectNode humanInput = json.createObjectNode();
        humanInput.put("version", 2);
        humanInput.put("kind", "human_input_request");
        humanInput.put("source", "ask_clarification");
        humanInput.put("request_id", requestId);
        humanInput.put("input_mode", "form");
        humanInput.put("question", "请填写发布信息");
        ArrayNode fieldNodes = humanInput.putArray("fields");
        for (FeishuPendingInput.Field field : fields) {
            ObjectNode node = fieldNodes.addObject();
            node.put("name", field.name());
            node.put("label", field.label());
            node.put("type", field.type());
            node.put("required", field.required());
            if (!field.options().isEmpty()) {
                ArrayNode options = node.putArray("options");
                for (FeishuPendingInput.Option option : field.options()) {
                    options.addObject()
                            .put("id", option.id())
                            .put("label", option.label())
                            .put("value", option.value());
                }
            }
        }
        ObjectNode message = json.createObjectNode();
        message.put("type", "tool");
        message.put("name", "ask_clarification");
        message.put("content", "请填写发布信息");
        message.putObject("artifact").set("human_input", humanInput);
        return message;
    }

    private ObjectNode toolCard(String requestId, String source, String inputMode, String content) {
        ObjectNode humanInput = json.createObjectNode();
        humanInput.put("version", 1);
        humanInput.put("kind", "human_input_request");
        humanInput.put("source", source);
        humanInput.put("request_id", requestId);
        humanInput.put("input_mode", inputMode);
        humanInput.put("question", content);
        ObjectNode message = json.createObjectNode();
        message.put("type", "tool");
        message.put("name", "ask_clarification");
        message.put("content", content);
        message.putObject("artifact").set("human_input", humanInput);
        return message;
    }

    private static FeishuPendingInput.Field field(String name, String label, String type, boolean required) {
        return new FeishuPendingInput.Field(name, label, type, required, "", List.of());
    }

    private static FeishuPendingInput.Field field(
            String name, String label, String type, boolean required, List<FeishuPendingInput.Option> options) {
        return new FeishuPendingInput.Field(name, label, type, required, "", options);
    }

    private static FeishuPendingInput.Option option(String id, String label, String value) {
        return new FeishuPendingInput.Option(id, label, value);
    }

    private Harness harness() throws Exception {
        ChannelConnectionStore connections = new ChannelConnectionStore(json, dataDirectory);
        FeishuChatThreadStore threads = new FeishuChatThreadStore(json, dataDirectory);
        ScriptedBridge bridge = new ScriptedBridge();
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
        return new Harness(worker, transportRef.get(), bridge, connections);
    }

    private record Harness(
            FeishuChannelWorker worker,
            RecordingFeishuTransport transport,
            ScriptedBridge bridge,
            ChannelConnectionStore connections) {}

    private static final class ScriptedBridge implements FeishuAgentBridge {
        private final AtomicInteger turns = new AtomicInteger();
        private final AtomicInteger steerCalls = new AtomicInteger();
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final AtomicReference<FeishuHumanResponse> responses = new AtomicReference<>();
        private final AtomicReference<String> lastMode = new AtomicReference<>("flash");
        private final AtomicBoolean activeRun = new AtomicBoolean(false);
        private volatile FeishuPendingInput pending;
        private volatile CountDownLatch blockEnter;
        private volatile CountDownLatch blockRelease;

        @Override
        public TurnResult runTurn(
                String ownerUserId, String threadId, Map<String, Object> channelMetadata, String userText)
                throws Exception {
            return runTurn(
                    ownerUserId,
                    threadId,
                    channelMetadata,
                    userText,
                    null,
                    List.of(),
                    TurnOptions.defaults());
        }

        @Override
        public TurnResult runTurn(
                String ownerUserId,
                String threadId,
                Map<String, Object> channelMetadata,
                String userText,
                FeishuHumanResponse response,
                List<UploadedFile> files,
                TurnOptions options)
                throws Exception {
            TurnOptions resolved = options == null ? TurnOptions.defaults() : options;
            lastMode.set(resolved.mode());
            if (resolved.steer()) {
                // Steer must not wait on the busy Turn latch — it runs on the connectExecutor path.
                steerCalls.incrementAndGet();
                return new TurnResult(threadId, "steered");
            }
            CountDownLatch enter = blockEnter;
            if (enter != null) {
                enter.countDown();
            }
            CountDownLatch release = blockRelease;
            if (release != null) {
                release.await(3, TimeUnit.SECONDS);
            }
            turns.incrementAndGet();
            responses.set(response);
            pending = null;
            activeRun.set(false);
            return new TurnResult(threadId, "continued");
        }

        @Override
        public Optional<FeishuPendingInput> currentPending(String ownerUserId, String threadId) {
            return Optional.ofNullable(pending);
        }

        @Override
        public boolean cancelActiveRun(String ownerUserId, String threadId) {
            cancelCalls.incrementAndGet();
            activeRun.set(false);
            return true;
        }

        @Override
        public boolean hasActiveRun(String ownerUserId, String threadId) {
            return activeRun.get();
        }
    }
}

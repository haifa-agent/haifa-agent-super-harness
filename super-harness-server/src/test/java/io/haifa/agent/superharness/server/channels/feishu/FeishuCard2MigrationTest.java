package io.haifa.agent.superharness.server.channels.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.core.response.error.Error;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody;
import com.lark.oapi.service.im.v1.model.ReplyMessageResp;
import com.lark.oapi.service.im.v1.model.ReplyMessageRespBody;
import io.haifa.agent.superharness.server.config.ChannelConnectionStore;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Feishu Card 2.0 migration and reliable-delivery behavior (issue #83). */
class FeishuCard2MigrationTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path dataDirectory;

    // ---- Card 2.0 schema -------------------------------------------------------------------

    @Test
    void singleChoiceCardUsesCard2CallbackButtons() {
        FeishuPendingInput pending = approval();
        Map<String, Object> card = FeishuInteraction.interactiveCard(pending);
        assertThat(card.get("schema")).isEqualTo("2.0");
        assertThat(card).doesNotContainKey("elements");
        assertThat(card).doesNotContainKey("action");

        List<Map<String, Object>> elements = bodyElements(card);
        assertThat(elements).noneMatch(element -> "action".equals(element.get("tag")));
        Map<String, Object> approve = elements.stream()
                .filter(element -> "button".equals(element.get("tag")))
                .filter(element -> "approve".equals(buttonValue(element).get("option_id")))
                .findFirst()
                .orElseThrow();
        assertThat(approve.get("type")).isEqualTo("primary");
        assertThat(buttonValue(approve))
                .containsEntry("source", "haifa_interaction")
                .containsEntry("request_id", "req-1")
                .containsEntry("option_id", "approve")
                .containsEntry("value", "approve")
                .containsEntry("card_schema", "2.0");
        assertThat(elements.stream()
                        .filter(element -> "button".equals(element.get("tag")))
                        .map(element -> element.get("type")))
                .contains("primary", "danger");
    }

    @Test
    void freeTextCardUsesCard2WithoutLegacyActionRow() {
        FeishuPendingInput pending = new FeishuPendingInput(
                "ask_clarification",
                "req-free",
                "missing_info",
                "free_text",
                "目标环境的名称是什么？",
                "",
                "目标环境的名称是什么？",
                List.of(),
                false);
        Map<String, Object> card = FeishuInteraction.interactiveCard(pending);
        assertThat(card.get("schema")).isEqualTo("2.0");
        assertThat(card).doesNotContainKey("elements");
        List<Map<String, Object>> elements = bodyElements(card);
        assertThat(elements).hasSize(1);
        assertThat(elements.getFirst().get("tag")).isEqualTo("markdown");
    }

    @Test
    void formFieldTypesMapToCard2InputTypes() {
        FeishuPendingInput pending = new FeishuPendingInput(
                "ask_clarification",
                "req-form",
                "form",
                "form",
                "请填写发布信息",
                "",
                "请填写发布信息",
                List.of(),
                false,
                List.of(
                        field("version", "版本号", "text", true, List.of()),
                        field("notes", "备注", "textarea", false, List.of()),
                        field("count", "数量", "number", true, List.of()),
                        field("env", "环境", "select", true, List.of(option("prod", "生产", "prod"))),
                        field(
                                "angles",
                                "切入角度",
                                "multi_select",
                                true,
                                List.of(
                                        option("a", "技术经济", "技术经济"),
                                        option("b", "产业格局", "产业格局"))),
                        field("agree", "确认继续", "checkbox", true, List.of()),
                        field("ship", "发布日", "date", false, List.of())),
                "",
                "",
                List.of(),
                List.of());
        Map<String, Object> card = FeishuInteraction.interactiveCard(pending);
        assertThat(card.get("schema")).isEqualTo("2.0");

        Map<String, Map<String, Object>> byName = formElementsByName(card);
        assertThat(byName.get("version").get("input_type")).isEqualTo("text");
        assertThat(byName.get("notes").get("input_type")).isEqualTo("multiline_text");
        assertThat(byName.get("notes").get("rows")).isEqualTo(4);
        assertThat(byName.get("notes").get("required")).isEqualTo(false);
        assertThat(byName.get("count").get("input_type")).isEqualTo("text");
        assertThat(byName.get("count").get("required")).isEqualTo(true);
        assertThat(byName.get("env").get("tag")).isEqualTo("select_static");
        assertThat(byName.get("env").get("required")).isEqualTo(true);
        assertThat(byName.get("angles").get("tag")).isEqualTo("multi_select_static");
        assertThat(byName.get("angles").get("required")).isEqualTo(true);
        assertThat(byName.get("agree").get("tag")).isEqualTo("checker");
        assertThat(byName.get("agree").get("checked")).isEqualTo(false);
        assertThat(byName.get("ship").get("tag")).isEqualTo("date_picker");
        assertThat(byName.get("ship").get("required")).isEqualTo(false);
    }

    @Test
    void settledCardRespectsSourceCardVersion() {
        FeishuInboundMessage card2 = new FeishuInboundMessage(
                "oc",
                "om",
                "ou",
                "",
                "p2p",
                new FeishuInboundMessage.CardAction("haifa_interaction", "req-1", "approve", "approve", "2.0"));
        Map<String, Object> settled2 = FeishuInteraction.settledCard(card2, FeishuInteraction.PROCESSING);
        assertThat(settled2.get("schema")).isEqualTo("2.0");
        assertThat(settled2).doesNotContainKey("elements");

        FeishuInboundMessage legacy = new FeishuInboundMessage(
                "oc",
                "om",
                "ou",
                "",
                "p2p",
                new FeishuInboundMessage.CardAction("haifa_interaction", "req-1", "approve", "approve"));
        Map<String, Object> settledLegacy = FeishuInteraction.settledCard(legacy, FeishuInteraction.PROCESSING);
        assertThat(settledLegacy).doesNotContainKey("schema");
        assertThat(settledLegacy.get("elements")).isNotNull();
    }

    // ---- delivery validation ---------------------------------------------------------------

    @Test
    void providerCardDiagnosticsExposeOnlySubcodesAndKnownPropertyNames() {
        String message = "Failed to create card content, ext=ErrCode: 200621; "
                + "ErrMsg: unknown property, property: label; ErrorValue: private-user-text";
        assertThat(LarkSdkFeishuTransport.cardErrorCode(message)).isEqualTo(200621);
        assertThat(LarkSdkFeishuTransport.cardErrorProperty(message)).isEqualTo("label");
        assertThat(LarkSdkFeishuTransport.cardErrorProperty("property: user_secret")).isEmpty();
        assertThat(LarkSdkFeishuTransport.cardErrorProperty("property: label_secret")).isEmpty();
        assertThat(LarkSdkFeishuTransport.cardErrorCode("ErrCode: 12345678901234")).isEqualTo(-1);
        assertThat(LarkSdkFeishuTransport.cardErrorCode(null)).isEqualTo(-1);
        assertThat(LarkSdkFeishuTransport.cardErrorProperty(null)).isEmpty();
        assertThat(LarkSdkFeishuTransport.cardErrorCode("x".repeat(2048) + " ErrCode: 200621")).isEqualTo(-1);
    }

    @Test
    void validateReplyRejectsUnsuccessfulResponses() {
        assertThat(LarkSdkFeishuTransport.validateReply(response(0, "om_out"), "interactive", "2.0"))
                .isNull();

        FeishuDeliveryException missingId =
                LarkSdkFeishuTransport.validateReply(response(0, ""), "interactive", "2.0");
        assertThat(missingId).isNotNull();
        assertThat(missingId.failure()).isEqualTo(FeishuDeliveryException.Failure.UNKNOWN);

        ReplyMessageResp invalid = response(200830, "om_out");
        invalid.setError(errorWithLogId("log-200830"));
        FeishuDeliveryException rejected =
                LarkSdkFeishuTransport.validateReply(invalid, "interactive", "2.0");
        assertThat(rejected).isNotNull();
        assertThat(rejected.failure()).isEqualTo(FeishuDeliveryException.Failure.INVALID);
        assertThat(rejected.retryable()).isFalse();
        assertThat(rejected.code()).isEqualTo(200830);
        assertThat(rejected.logId()).isEqualTo("log-200830");
    }

    @Test
    void failureClassificationDistinguishesRetryableFromPermanent() {
        assertThat(LarkSdkFeishuTransport.classifyFailure(0, 429))
                .isEqualTo(FeishuDeliveryException.Failure.RATE_LIMIT);
        assertThat(LarkSdkFeishuTransport.classifyFailure(0, 503))
                .isEqualTo(FeishuDeliveryException.Failure.TRANSPORT);
        assertThat(LarkSdkFeishuTransport.classifyFailure(0, 403))
                .isEqualTo(FeishuDeliveryException.Failure.PERMISSION);
        assertThat(LarkSdkFeishuTransport.classifyFailure(0, 400))
                .isEqualTo(FeishuDeliveryException.Failure.INVALID);
        assertThat(LarkSdkFeishuTransport.classifyFailure(99991400, 200))
                .isEqualTo(FeishuDeliveryException.Failure.RATE_LIMIT);
        // Business codes win over the generic HTTP 400 fallback.
        assertThat(LarkSdkFeishuTransport.classifyFailure(99991400, 400))
                .isEqualTo(FeishuDeliveryException.Failure.RATE_LIMIT);
        assertThat(LarkSdkFeishuTransport.classifyFailure(200830, 400))
                .isEqualTo(FeishuDeliveryException.Failure.INVALID);
        assertThat(LarkSdkFeishuTransport.classifyFailure(230099, 0))
                .isEqualTo(FeishuDeliveryException.Failure.INVALID);
        assertThat(new FeishuDeliveryException(
                                FeishuDeliveryException.Failure.RATE_LIMIT, 99991400, "", "interactive", "2.0", "")
                        .retryable())
                .isTrue();
        assertThat(new FeishuDeliveryException(
                                FeishuDeliveryException.Failure.INVALID, 200830, "", "interactive", "2.0", "")
                        .retryable())
                .isFalse();
    }

    @Test
    void replyRetryReusesOneIdempotencyKey() {
        ReplyMessageReq request = ReplyMessageReq.newBuilder()
                .messageId("om_in")
                .replyMessageReqBody(ReplyMessageReqBody.newBuilder()
                        .msgType("interactive")
                        .content("{}")
                        .build())
                .build();
        LarkSdkFeishuTransport.ensureIdempotencyKey(request);
        String first = request.getReplyMessageReqBody().getUuid();
        assertThat(first).isNotBlank();
        LarkSdkFeishuTransport.ensureIdempotencyKey(request);
        assertThat(request.getReplyMessageReqBody().getUuid()).isEqualTo(first);
    }

    // ---- worker fallback -------------------------------------------------------------------

    @Test
    void interactiveCardDeliveryFailureFallsBackToText() throws Exception {
        Harness harness = harness(true);
        harness.bridge.turnPending = approval();
        harness.worker.handleInbound(new FeishuInboundMessage(
                "oc_chat", "om_card", "ou_owner", "please approve", "p2p"));

        assertThat(harness.transport.interactiveReplies()).isEmpty();
        assertThat(harness.transport.textReplies()).hasSize(1);
        assertThat(harness.transport.textReplies().getFirst().text())
                .contains(FeishuInteraction.CARD_DELIVERY_FAILED)
                .contains("Approve");
        harness.worker.close();
    }

    @Test
    void ordinaryTextReplyFallsBackToTextOnCardFailure() throws Exception {
        Harness harness = harness(true);
        harness.worker.handleInbound(new FeishuInboundMessage(
                "oc_chat", "om_text", "ou_owner", "hello", "p2p"));

        assertThat(harness.transport.textReplies()).anyMatch(reply -> reply.text().contains("continued"));
        harness.worker.close();
    }

    // ---- helpers ---------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> bodyElements(Map<String, Object> card) {
        return (List<Map<String, Object>>) ((Map<String, Object>) card.get("body")).get("elements");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buttonValue(Map<String, Object> button) {
        List<Map<String, Object>> behaviors = (List<Map<String, Object>>) button.get("behaviors");
        return (Map<String, Object>) behaviors.getFirst().get("value");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> formElementsByName(Map<String, Object> card) {
        Map<String, Object> form = bodyElements(card).stream()
                .filter(element -> "form".equals(element.get("tag")))
                .findFirst()
                .orElseThrow();
        List<Map<String, Object>> formElements = (List<Map<String, Object>>) form.get("elements");
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> element : formElements) {
            Object name = element.get("name");
            if (name != null && !"form_submit".equals(name)) {
                byName.put(String.valueOf(name), element);
            }
        }
        return byName;
    }

    private static ReplyMessageResp response(int code, String messageId) {
        ReplyMessageResp response = new ReplyMessageResp();
        response.setCode(code);
        if (messageId != null && !messageId.isBlank()) {
            ReplyMessageRespBody body = new ReplyMessageRespBody();
            body.setMessageId(messageId);
            response.setData(body);
        }
        return response;
    }

    private static Error errorWithLogId(String logId) {
        Error error = new Error();
        error.setLogId(logId);
        return error;
    }

    private static FeishuPendingInput approval() {
        return new FeishuPendingInput(
                "haifa_interaction",
                "req-1",
                "risk_confirmation",
                "single_choice",
                "是否允许写入？",
                "",
                "是否允许写入？",
                List.of(option("approve", "Approve", "approve"), option("reject", "Reject", "reject")),
                false);
    }

    private static FeishuPendingInput.Option option(String id, String label, String value) {
        return new FeishuPendingInput.Option(id, label, value);
    }

    private static FeishuPendingInput.Field field(
            String name, String label, String type, boolean required, List<FeishuPendingInput.Option> options) {
        return new FeishuPendingInput.Field(name, label, type, required, "", options);
    }

    private Harness harness(boolean failInteractive) throws Exception {
        ChannelConnectionStore connections = new ChannelConnectionStore(json, dataDirectory);
        FeishuChatThreadStore threads = new FeishuChatThreadStore(json, dataDirectory);
        Bridge bridge = new Bridge();
        AtomicReference<RecordingFeishuTransport> transportRef = new AtomicReference<>();
        FeishuChannelWorker worker = new FeishuChannelWorker(connections, threads, bridge, () -> {
            RecordingFeishuTransport transport = new RecordingFeishuTransport();
            if (failInteractive) {
                transport.failInteractiveDelivery();
            }
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
        return new Harness(worker, transportRef.get(), bridge);
    }

    private record Harness(
            FeishuChannelWorker worker, RecordingFeishuTransport transport, Bridge bridge) {}

    private static final class Bridge implements FeishuAgentBridge {
        private volatile FeishuPendingInput turnPending;

        @Override
        public TurnResult runTurn(
                String ownerUserId, String threadId, Map<String, Object> channelMetadata, String userText) {
            return new TurnResult(threadId, "continued", turnPending);
        }

        @Override
        public Optional<FeishuPendingInput> currentPending(String ownerUserId, String threadId) {
            return Optional.empty();
        }
    }
}

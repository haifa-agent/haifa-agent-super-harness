package io.haifa.agent.superharness.server.channels.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lark.oapi.event.cardcallback.model.CallBackAction;
import com.lark.oapi.event.cardcallback.model.CallBackContext;
import com.lark.oapi.event.cardcallback.model.CallBackOperator;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import io.haifa.agent.superharness.server.config.ChannelConnectionStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Feishu human-input card parsing, exact replies, and card-callback toasts. */
class FeishuInteractionTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path dataDirectory;

    @Test
    void approvalTailIsExtractedAndHistoricalCardsAreNot() throws Exception {
        ArrayNode messages = json.createArrayNode();
        messages.add(toolCard("ask-old", "haifa_interaction", "single_choice", "old?", List.of(
                option("approve", "Approve", "approve"))));
        messages.add(ai("already continued"));
        messages.add(human("next"));
        assertThat(FeishuInteraction.pendingFromMessages(messages)).isEmpty();

        messages.add(ai("我将把部署脚本写到生产目录。"));
        messages.add(toolCard("req-1", "haifa_interaction", "single_choice", "是否允许写入？", List.of(
                option("approve", "Approve", "approve"),
                option("reject", "Reject", "reject"))));
        FeishuPendingInput pending = FeishuInteraction.pendingFromMessages(messages).orElseThrow();
        assertThat(pending.requestId()).isEqualTo("req-1");
        assertThat(pending.source()).isEqualTo("haifa_interaction");
        assertThat(pending.preface()).isEqualTo("我将把部署脚本写到生产目录。");
        assertThat(pending.options()).extracting(FeishuPendingInput.Option::id).containsExactly("approve", "reject");
        assertThat(pending.options().getFirst().id()).isEqualTo(pending.options().getFirst().value());

        messages.add(ai("I'll create a file with one line of content."));
        FeishuPendingInput followedByAssistant = FeishuInteraction.pendingFromMessages(messages).orElseThrow();
        assertThat(followedByAssistant.requestId()).isEqualTo("req-1");
        assertThat(followedByAssistant.preface()).contains("我将把部署脚本写到生产目录。");
        assertThat(followedByAssistant.preface()).contains("I'll create a file with one line of content.");

        Map<String, Object> card = FeishuInteraction.interactiveCard(followedByAssistant);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> elements = (List<Map<String, Object>>) card.get("elements");
        assertThat(elements).anyMatch(element -> "action".equals(element.get("tag")));
        assertThat(elements).noneMatch(element -> "button".equals(element.get("tag")));
        assertThat(elements.getFirst().get("content").toString()).contains("1. Approve").contains("2. Reject");
    }

    @Test
    void clarificationOptionsKeepOptionIdsAndOriginalValues() throws Exception {
        ArrayNode messages = json.createArrayNode();
        messages.add(toolCard(
                "clarification:call-1",
                "ask_clarification",
                "choice_with_other",
                "❓ 先灰度，还是直接全量？\n\n  1. 先灰度再全量\n  2. 直接全量",
                "先灰度，还是直接全量？",
                List.of(
                        option("option-1", "先灰度再全量", "先灰度再全量"),
                        option("option-2", "直接全量", "直接全量"))));
        FeishuPendingInput pending = FeishuInteraction.pendingFromMessages(messages).orElseThrow();
        FeishuHumanResponse selected = FeishuInteraction.match(pending, "1").orElseThrow();
        assertThat(selected.optionId()).isEqualTo("option-1");
        assertThat(selected.value()).isEqualTo("先灰度再全量");
        assertThat(FeishuInteraction.responseContent(pending, selected))
                .isEqualTo("For your clarification \"先灰度，还是直接全量？\", my answer is: 先灰度再全量");

        FeishuHumanResponse other = FeishuInteraction.match(pending, "先出公告").orElseThrow();
        assertThat(other.responseKind()).isEqualTo("text");
        assertThat(other.value()).isEqualTo("先出公告");
    }

    @Test
    void choiceWithOtherCardIncludesTheWebFreeTextField() throws Exception {
        FeishuPendingInput pending = new FeishuPendingInput(
                "ask_clarification",
                "clarification:abc",
                "ambiguous_requirement",
                "choice_with_other",
                "请选择一个选项（体验澄清组件）：",
                "",
                "请选择一个选项（体验澄清组件）：",
                List.of(
                        option("option-1", "A（选项一）", "A（选项一）"),
                        option("option-2", "B（选项二）", "B（选项二）"),
                        option("option-3", "C（选项三）", "C（选项三）")),
                false);
        Map<String, Object> built = FeishuInteraction.interactiveCard(pending);
        assertThat(built.get("schema")).isEqualTo("2.0");
        String card = built.toString();
        assertThat(card).contains("其他回答").contains("输入其他回答...");
        assertThat(card).contains("A（选项一）").contains("B（选项二）").contains("C（选项三）");

        P2CardActionTrigger trigger = new P2CardActionTrigger();
        P2CardActionTriggerData data = new P2CardActionTriggerData();
        CallBackAction action = new CallBackAction();
        action.setName("other_submit");
        action.setValue(Map.of(
                "action", "other",
                "request_id", "clarification:abc",
                "source", "ask_clarification",
                "card_schema", "2.0"));
        action.setFormValue(Map.of("other", "自定义答案"));
        data.setAction(action);
        CallBackContext context = new CallBackContext();
        context.setOpenChatId("oc_chat");
        context.setOpenMessageId("om_other");
        CallBackOperator operator = new CallBackOperator();
        operator.setOpenId("ou_owner");
        data.setContext(context);
        data.setOperator(operator);
        trigger.setEvent(data);
        FeishuInboundMessage message = LarkSdkFeishuTransport.cardMessage(trigger);
        assertThat(message.cardAction().optionId()).isEqualTo("other");
        assertThat(message.cardAction().value()).isEqualTo("自定义答案");
        assertThat(message.cardAction().cardSchema()).isEqualTo("2.0");
        Map<String, Object> settled = FeishuInteraction.settledCard(message, FeishuInteraction.PROCESSING);
        assertThat(settled.get("schema")).isEqualTo("2.0");
        assertThat(settled).doesNotContainKey("elements");
        assertThat(settled.get("body").toString()).contains("已提交：自定义答案").doesNotContain("button");

        Harness harness = harness();
        harness.bridge.pending = pending;
        harness.worker.handleInbound(new FeishuInboundMessage(
                "oc_chat", "om_other", "ou_owner", "", "p2p", message.cardAction()));
        assertThat(harness.bridge.responses.get().responseKind()).isEqualTo("text");
        assertThat(harness.bridge.responses.get().value()).isEqualTo("自定义答案");
        assertThat(harness.bridge.responses.get().optionId()).isNull();

        harness.bridge.pending = pending;
        String empty = harness.worker.acceptCardAction(new FeishuInboundMessage(
                "oc_chat", "om_empty", "ou_owner", "", "p2p",
                new FeishuInboundMessage.CardAction("ask_clarification", "clarification:abc", "other", "  ")));
        assertThat(empty).isEqualTo(FeishuInteraction.EMPTY_OTHER);
        assertThat(harness.bridge.turns.get()).isEqualTo(1);
        harness.worker.close();
    }

    @Test
    void approvalTextMatchesExactAliasesOnly() throws Exception {
        FeishuPendingInput pending = approval();
        assertThat(FeishuInteraction.match(pending, "1").orElseThrow().optionId()).isEqualTo("approve");
        assertThat(FeishuInteraction.match(pending, "Approve").orElseThrow().value()).isEqualTo("approve");
        assertThat(FeishuInteraction.match(pending, "批准").orElseThrow().optionId()).isEqualTo("approve");
        assertThat(FeishuInteraction.match(pending, "拒绝").orElseThrow().optionId()).isEqualTo("reject");
        assertThat(FeishuInteraction.match(pending, "批准一下")).isEmpty();
    }

    @Test
    void freeTextAndFormDoNotInventOptionResponses() throws Exception {
        FeishuPendingInput free = new FeishuPendingInput(
                "ask_clarification", "req-free", "missing_info", "free_text",
                "目标环境的名称是什么？", "", "目标环境的名称是什么？", List.of(), false);
        FeishuHumanResponse text = FeishuInteraction.match(free, "prod").orElseThrow();
        assertThat(text.responseKind()).isEqualTo("text");
        assertThat(text.value()).isEqualTo("prod");

        ArrayNode messages = json.createArrayNode();
        ObjectNode form = toolCard("req-form", "ask_clarification", "form", "需要补齐发布单", List.of());
        form.withObject("/artifact/human_input").put("version", 2);
        messages.add(form);
        FeishuPendingInput pending = FeishuInteraction.pendingFromMessages(messages).orElseThrow();
        assertThat(pending.formUnsupported()).isTrue();
        assertThat(FeishuInteraction.match(pending, "1.0.0")).isEmpty();
        assertThat(FeishuInteraction.interactiveCard(pending).get("elements").toString()).doesNotContain("button");
        assertThat(FeishuInteraction.formNotice(pending)).contains("请在 Web 端完成该表单");

        Harness harness = harness();
        harness.bridge.pending = pending;
        harness.worker.handleInbound(new FeishuInboundMessage(
                "oc_chat", "om_form", "ou_owner", "1.0.0", "p2p"));
        assertThat(harness.bridge.turns.get()).isZero();
        assertThat(harness.transport.replies().getLast().text()).contains("请在 Web 端完成该表单");
        harness.worker.close();
    }

    @Test
    void uplinkMessageCarriesHiddenHumanInputResponse() throws Exception {
        ObjectNode message = json.createObjectNode();
        FeishuInteraction.writeHumanMessage(
                message,
                "Approve",
                new FeishuHumanResponse("haifa_interaction", "req-1", "option", "approve", "approve"));
        assertThat(message.path("content").asText()).isEqualTo("Approve");
        JsonNode human = message.path("additional_kwargs").path("human_input_response");
        assertThat(message.path("additional_kwargs").path("hide_from_ui").asBoolean()).isTrue();
        assertThat(human.path("version").asInt()).isEqualTo(1);
        assertThat(human.path("kind").asText()).isEqualTo("human_input_response");
        assertThat(human.path("option_id").asText()).isEqualTo("approve");
        assertThat(human.path("value").asText()).isEqualTo(human.path("option_id").asText());
    }

    @Test
    void cardCallbackMapsButtonValueWithoutWaitingForTheRun() throws Exception {
        P2CardActionTrigger trigger = new P2CardActionTrigger();
        P2CardActionTriggerData data = new P2CardActionTriggerData();
        CallBackOperator operator = new CallBackOperator();
        operator.setOpenId("ou_owner");
        CallBackContext context = new CallBackContext();
        context.setOpenChatId("oc_chat");
        context.setOpenMessageId("om_card");
        CallBackAction action = new CallBackAction();
        action.setValue(Map.of("action", "approve", "request_id", "req-1", "source", "haifa_interaction"));
        data.setOperator(operator);
        data.setContext(context);
        data.setAction(action);
        trigger.setEvent(data);

        FeishuInboundMessage message = LarkSdkFeishuTransport.cardMessage(trigger);
        assertThat(message.cardAction().requestId()).isEqualTo("req-1");
        assertThat(message.cardAction().optionId()).isEqualTo("approve");
        assertThat(LarkSdkFeishuTransport.toastResponse(FeishuInteraction.PROCESSING).getToast().getContent())
                .isEqualTo(FeishuInteraction.PROCESSING);
        Map<String, Object> settled = FeishuInteraction.settledCard(message, FeishuInteraction.PROCESSING);
        assertThat(settled.get("elements").toString()).doesNotContain("button");
        assertThat(settled.get("elements").toString()).contains("已提交：Approve");
        var updated = LarkSdkFeishuTransport.callbackResponse(FeishuInteraction.PROCESSING, settled);
        assertThat(updated.getCard().getType()).isEqualTo("raw");
        assertThat(updated.getCard().getData()).isSameAs(settled);

        Harness harness = harness();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        harness.bridge.pending = approval();
        harness.bridge.blockEnter = entered;
        harness.bridge.blockRelease = release;
        String toast = harness.worker.acceptCardAction(new FeishuInboundMessage(
                "oc_chat", "om_card", "ou_owner", "", "p2p", message.cardAction()));
        assertThat(toast).isEqualTo(FeishuInteraction.PROCESSING);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(harness.bridge.responses.get()).isNull();
        release.countDown();
        assertThat(harness.transport.replies().isEmpty() || true).isTrue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (harness.bridge.responses.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(harness.bridge.responses.get().optionId()).isEqualTo("approve");
        assertThat(harness.bridge.responses.get().value()).isEqualTo("approve");
        harness.worker.close();
    }

    @Test
    void unmatchedApprovalTextDoesNotSubmitAndStrangerClicksAreRejected() throws Exception {
        Harness harness = harness();
        harness.bridge.pending = approval();
        harness.worker.handleInbound(new FeishuInboundMessage(
                "oc_chat", "om_text", "ou_owner", "批准一下", "p2p"));
        assertThat(harness.bridge.turns.get()).isZero();
        assertThat(harness.transport.replies().getLast().text()).contains("等待审批");

        String toast = harness.worker.acceptCardAction(new FeishuInboundMessage(
                "oc_chat", "om_card", "ou_stranger", "", "group",
                new FeishuInboundMessage.CardAction("haifa_interaction", "req-1", "approve", "approve")));
        assertThat(toast).isEqualTo(FeishuInteraction.FORBIDDEN);
        Thread.sleep(100);
        assertThat(harness.bridge.turns.get()).isZero();

        harness.bridge.pending = new FeishuPendingInput(
                "ask_clarification",
                "req-2",
                "approach_choice",
                "single_choice",
                "用哪种方式？",
                "",
                "用哪种方式？",
                List.of(option("option-1", "本地构建", "本地构建")),
                false);
        harness.worker.handleInbound(new FeishuInboundMessage(
                "oc_chat", "om_text2", "ou_owner", "随便", "p2p"));
        assertThat(harness.bridge.turns.get()).isZero();
        assertThat(harness.transport.replies().getLast().text()).isEqualTo(FeishuInteraction.CHOICE_HINT);
        harness.worker.close();
    }

    @Test
    void duplicateClickIsToldTheActionWasAlreadyHandled() throws Exception {
        Harness harness = harness();
        harness.bridge.pending = null;
        harness.worker.handleInbound(new FeishuInboundMessage(
                "oc_chat", "om_card", "ou_owner", "", "p2p",
                new FeishuInboundMessage.CardAction("haifa_interaction", "req-1", "approve", "approve")));
        assertThat(harness.transport.replies().getLast().text()).isEqualTo(FeishuInteraction.ALREADY_HANDLED);
        assertThat(harness.bridge.turns.get()).isZero();
        String toast = harness.worker.acceptCardAction(new FeishuInboundMessage(
                "oc_chat", "om_card", "ou_owner", "", "p2p",
                new FeishuInboundMessage.CardAction("haifa_interaction", "req-1", "approve", "approve")));
        assertThat(toast).isEqualTo(FeishuInteraction.ALREADY_HANDLED);
        assertThat(harness.bridge.turns.get()).isZero();
        harness.worker.close();
    }

    private FeishuPendingInput approval() {
        return new FeishuPendingInput(
                "haifa_interaction",
                "req-1",
                "risk_confirmation",
                "single_choice",
                "是否允许写入？",
                "我将把部署脚本写到生产目录。",
                "是否允许写入？",
                List.of(option("approve", "Approve", "approve"), option("reject", "Reject", "reject")),
                false);
    }

    private ObjectNode toolCard(
            String requestId, String source, String inputMode, String content, List<FeishuPendingInput.Option> options)
            throws Exception {
        return toolCard(requestId, source, inputMode, content, content, options);
    }

    private ObjectNode toolCard(
            String requestId,
            String source,
            String inputMode,
            String content,
            String question,
            List<FeishuPendingInput.Option> options)
            throws Exception {
        ObjectNode humanInput = json.createObjectNode();
        humanInput.put("version", 1);
        humanInput.put("kind", "human_input_request");
        humanInput.put("source", source);
        humanInput.put("request_id", requestId);
        humanInput.put("input_mode", inputMode);
        humanInput.put("question", question);
        ArrayNode optionNodes = humanInput.putArray("options");
        for (FeishuPendingInput.Option option : options) {
            optionNodes.addObject().put("id", option.id()).put("label", option.label()).put("value", option.value());
        }
        ObjectNode message = json.createObjectNode();
        message.put("type", "tool");
        message.put("name", "ask_clarification");
        message.put("content", content);
        message.putObject("artifact").set("human_input", humanInput);
        return message;
    }

    private ObjectNode human(String text) {
        ObjectNode message = json.createObjectNode();
        message.put("type", "human");
        message.put("content", text);
        return message;
    }

    private ObjectNode ai(String text) {
        ObjectNode message = json.createObjectNode();
        message.put("type", "ai");
        message.put("content", text);
        return message;
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
                connections, threads, bridge, () -> {
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
        return new Harness(worker, transportRef.get(), bridge);
    }

    private record Harness(FeishuChannelWorker worker, RecordingFeishuTransport transport, ScriptedBridge bridge) {}

    private static final class ScriptedBridge implements FeishuAgentBridge {
        private final AtomicInteger turns = new AtomicInteger();
        private final AtomicReference<FeishuHumanResponse> responses = new AtomicReference<>();
        private volatile FeishuPendingInput pending;
        private volatile CountDownLatch blockEnter;
        private volatile CountDownLatch blockRelease;

        @Override
        public TurnResult runTurn(
                String ownerUserId, String threadId, Map<String, Object> channelMetadata, String userText)
                throws Exception {
            return runTurn(ownerUserId, threadId, channelMetadata, userText, null);
        }

        @Override
        public TurnResult runTurn(
                String ownerUserId,
                String threadId,
                Map<String, Object> channelMetadata,
                String userText,
                FeishuHumanResponse response)
                throws Exception {
            CountDownLatch enter = blockEnter;
            if (enter != null) {
                enter.countDown();
            }
            CountDownLatch release = blockRelease;
            if (release != null) {
                release.await(2, TimeUnit.SECONDS);
            }
            turns.incrementAndGet();
            responses.set(response);
            pending = null;
            return new TurnResult(threadId, "continued");
        }

        @Override
        public Optional<FeishuPendingInput> currentPending(String ownerUserId, String threadId) {
            return Optional.ofNullable(pending);
        }
    }
}

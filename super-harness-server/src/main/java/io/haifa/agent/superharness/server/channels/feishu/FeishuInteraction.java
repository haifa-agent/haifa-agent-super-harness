package io.haifa.agent.superharness.server.channels.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Parses frozen human-input cards and builds the Feishu card plus the uplink human message. */
public final class FeishuInteraction {
    static final String APPROVAL_HINT =
            "当前任务正在等待审批，请点击卡片按钮，或精确回复「批准」/「拒绝」。";
    static final String CHOICE_HINT = "请点击卡片上的选项，或精确回复序号 / 选项原文。";
    static final String ALREADY_HANDLED = "该操作已处理，无需重复点击";
    static final String PROCESSING = "正在处理操作...";
    static final String FORBIDDEN = "您无权审批该任务";
    static final String INVALID = "操作无效";

    private FeishuInteraction() {}

    public static Optional<FeishuPendingInput> pendingFromMessages(JsonNode messages) {
        if (messages == null || !messages.isArray() || messages.isEmpty()) {
            return Optional.empty();
        }
        JsonNode tail = messages.get(messages.size() - 1);
        if (!isClarification(tail)) {
            return Optional.empty();
        }
        JsonNode humanInput = tail.path("artifact").path("human_input");
        if (!humanInput.isObject()) {
            return Optional.empty();
        }
        int version = humanInput.path("version").asInt(1);
        String inputMode = humanInput.path("input_mode").asText("");
        boolean form = version != 1 || "form".equals(inputMode);
        List<FeishuPendingInput.Option> options = new ArrayList<>();
        if (!form && humanInput.path("options").isArray()) {
            for (JsonNode option : humanInput.path("options")) {
                options.add(new FeishuPendingInput.Option(
                        option.path("id").asText(""),
                        option.path("label").asText(""),
                        option.path("value").asText("")));
            }
        }
        String content = messageText(tail);
        String question = humanInput.path("question").asText("");
        if (content.isBlank()) {
            content = question;
        }
        return Optional.of(new FeishuPendingInput(
                humanInput.path("source").asText(""),
                humanInput.path("request_id").asText(""),
                humanInput.path("clarification_type").asText(""),
                inputMode,
                question,
                preface(messages),
                content,
                options,
                form));
    }

    public static Optional<FeishuHumanResponse> match(FeishuPendingInput pending, String text) {
        if (pending == null || pending.formUnsupported() || text == null) {
            return Optional.empty();
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        if ("free_text".equals(pending.inputMode())) {
            return Optional.of(textResponse(pending, trimmed));
        }
        Optional<FeishuPendingInput.Option> option = matchOption(pending, trimmed);
        if (option.isPresent()) {
            FeishuPendingInput.Option selected = option.get();
            return Optional.of(new FeishuHumanResponse(
                    pending.source(), pending.requestId(), "option", selected.id(), selected.value()));
        }
        if ("choice_with_other".equals(pending.inputMode())) {
            return Optional.of(textResponse(pending, trimmed));
        }
        return Optional.empty();
    }

    public static String responseContent(FeishuPendingInput pending, FeishuHumanResponse response) {
        if (response == null) {
            return "";
        }
        if ("ask_clarification".equals(response.source())) {
            String question = pending == null ? "" : pending.question();
            return "For your clarification \"" + question + "\", my answer is: " + response.value();
        }
        if ("option".equals(response.responseKind())) {
            return labelFor(pending, response.optionId());
        }
        return response.value() == null ? "" : response.value();
    }

    public static void writeHumanMessage(ObjectNode message, String content, FeishuHumanResponse response) {
        message.put("id", UUID.randomUUID().toString());
        message.put("type", "human");
        message.put("content", content == null ? "" : content);
        if (response == null) {
            return;
        }
        ObjectNode additional = message.putObject("additional_kwargs");
        additional.put("hide_from_ui", true);
        ObjectNode human = additional.putObject("human_input_response");
        human.put("version", 1);
        human.put("kind", "human_input_response");
        human.put("source", response.source());
        human.put("request_id", response.requestId());
        human.put("response_kind", response.responseKind());
        if ("option".equals(response.responseKind())) {
            human.put("option_id", response.optionId());
        }
        human.put("value", response.value());
    }

    public static Map<String, Object> interactiveCard(FeishuPendingInput pending) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("config", Map.of("wide_screen_mode", true, "update_multi", true));
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(Map.of("tag", "markdown", "content", markdown(pending)));
        if (!pending.formUnsupported() && !pending.options().isEmpty()) {
            List<Map<String, Object>> actions = new ArrayList<>();
            for (FeishuPendingInput.Option option : pending.options()) {
                String label = option.label().isBlank() ? option.id() : option.label();
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("source", pending.source());
                value.put("request_id", pending.requestId());
                value.put("option_id", option.id());
                value.put("value", option.value());
                actions.add(Map.of(
                        "tag", "button",
                        "text", Map.of("tag", "plain_text", "content", label),
                        "type", buttonType(option.id()),
                        "value", value));
            }
            elements.add(Map.of("tag", "action", "actions", actions));
        }
        card.put("elements", elements);
        return card;
    }

    public static String formNotice(FeishuPendingInput pending) {
        String body = pending.content().isBlank() ? pending.question() : pending.content();
        return body + "\n\n请在 Web 端完成该表单。";
    }

    public static Optional<FeishuPendingInput.Option> optionById(FeishuPendingInput pending, String optionId) {
        if (pending == null || optionId == null || optionId.isBlank()) {
            return Optional.empty();
        }
        for (FeishuPendingInput.Option option : pending.options()) {
            if (option.id().equals(optionId)) {
                return Optional.of(option);
            }
        }
        return Optional.empty();
    }

    static String messageText(JsonNode message) {
        JsonNode content = message.get("content");
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode part : content) {
                if (part.isTextual()) {
                    builder.append(part.asText());
                } else if (part.hasNonNull("text")) {
                    builder.append(part.get("text").asText());
                }
            }
            return builder.toString();
        }
        return content.toString();
    }

    private static FeishuHumanResponse textResponse(FeishuPendingInput pending, String text) {
        return new FeishuHumanResponse(pending.source(), pending.requestId(), "text", null, text);
    }

    private static Optional<FeishuPendingInput.Option> matchOption(FeishuPendingInput pending, String trimmed) {
        if (trimmed.matches("[1-9]\\d{0,2}")) {
            int index = Integer.parseInt(trimmed);
            if (index >= 1 && index <= pending.options().size()) {
                return Optional.of(pending.options().get(index - 1));
            }
        }
        for (FeishuPendingInput.Option option : pending.options()) {
            if (equalsFold(trimmed, option.label())
                    || equalsFold(trimmed, option.value())
                    || equalsFold(trimmed, option.id())) {
                return Optional.of(option);
            }
        }
        if ("haifa_interaction".equals(pending.source())) {
            if (isApproveAlias(trimmed)) {
                return optionById(pending, "approve");
            }
            if (isRejectAlias(trimmed)) {
                return optionById(pending, "reject");
            }
        }
        return Optional.empty();
    }

    private static boolean isApproveAlias(String trimmed) {
        return "approve".equals(trimmed.toLowerCase(Locale.ROOT))
                || "批准".equals(trimmed)
                || "同意".equals(trimmed);
    }

    private static boolean isRejectAlias(String trimmed) {
        return "reject".equals(trimmed.toLowerCase(Locale.ROOT))
                || "拒绝".equals(trimmed)
                || "否决".equals(trimmed);
    }

    private static boolean equalsFold(String left, String right) {
        return !right.isBlank() && left.equalsIgnoreCase(right);
    }

    private static String labelFor(FeishuPendingInput pending, String optionId) {
        return optionById(pending, optionId).map(FeishuPendingInput.Option::label).filter(label -> !label.isBlank())
                .orElse(optionId == null ? "" : optionId);
    }

    private static String markdown(FeishuPendingInput pending) {
        StringBuilder body = new StringBuilder();
        if (!pending.preface().isBlank()) {
            body.append(pending.preface().strip()).append("\n\n");
        }
        if (!pending.content().isBlank()) {
            body.append(pending.content().strip());
        }
        if (!pending.formUnsupported()
                && !pending.options().isEmpty()
                && !pending.content().contains("\n  1. ")) {
            body.append("\n");
            for (int index = 0; index < pending.options().size(); index++) {
                FeishuPendingInput.Option option = pending.options().get(index);
                String label = option.label().isBlank() ? option.id() : option.label();
                body.append("\n  ").append(index + 1).append(". ").append(label);
            }
        }
        body.append("\n\n").append(footer(pending));
        return body.toString().strip();
    }

    private static String footer(FeishuPendingInput pending) {
        if (pending.formUnsupported()) {
            return "请在 Web 端完成该表单。";
        }
        if ("free_text".equals(pending.inputMode())) {
            return "请直接回复要补充的内容。";
        }
        if ("haifa_interaction".equals(pending.source())) {
            return "也可精确回复 1、2、Approve、Reject、approve、reject、批准、同意、拒绝、否决。";
        }
        if ("choice_with_other".equals(pending.inputMode())) {
            return "可点按钮，或精确回复序号 / 选项原文。其他原文按 other 文本提交。";
        }
        return "可点按钮，或精确回复序号 / 选项原文。";
    }

    private static String buttonType(String optionId) {
        if ("approve".equals(optionId)) {
            return "primary";
        }
        if ("reject".equals(optionId)) {
            return "danger";
        }
        return "default";
    }

    private static boolean isClarification(JsonNode message) {
        return "tool".equals(message.path("type").asText())
                && "ask_clarification".equals(message.path("name").asText());
    }

    private static String preface(JsonNode messages) {
        List<String> parts = new ArrayList<>();
        for (int index = messages.size() - 2; index >= 0; index--) {
            JsonNode message = messages.get(index);
            String type = message.path("type").asText(message.path("role").asText());
            if (!"ai".equals(type) && !"assistant".equals(type)) {
                break;
            }
            String text = messageText(message);
            if (!text.isBlank()) {
                parts.add(text);
            }
        }
        StringBuilder builder = new StringBuilder();
        for (int index = parts.size() - 1; index >= 0; index--) {
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append(parts.get(index));
        }
        return builder.toString();
    }
}

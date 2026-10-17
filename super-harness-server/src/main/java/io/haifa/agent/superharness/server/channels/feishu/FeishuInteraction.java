package io.haifa.agent.superharness.server.channels.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Parses frozen human-input cards and builds the Feishu card plus the uplink human message. */
public final class FeishuInteraction {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FORM_FIELD_TYPES =
            Set.of("text", "textarea", "number", "select");

    public static final String APPROVAL_HINT =
            "当前任务正在等待审批，请点击卡片按钮，或精确回复「批准」/「拒绝」。";
    public static final String CHOICE_HINT = "请点击卡片上的选项，或精确回复序号 / 选项原文。";
    public static final String ALREADY_HANDLED = "该操作已处理，无需重复点击";
    public static final String PROCESSING = "正在处理操作...";
    public static final String FORBIDDEN = "您无权审批该任务";
    public static final String INVALID = "操作无效";
    /** Card-only id for the web "其他回答" field. It is not an option in thread state. */
    public static final String OTHER_OPTION = "other";
    /** Card-only id for Form v2 submit. */
    public static final String FORM_SUBMIT = "form_submit";
    public static final String EMPTY_OTHER = "请输入回答后再提交。";
    public static final String EMPTY_FORM = "请填写必填字段后再提交。";
    public static final String PROGRESS = "正在处理您的请求…";
    public static final String BUSY = "当前对话仍在处理上一轮请求，请稍后再试。";
    public static final String STOPPED = "已停止当前任务。";
    public static final String STOP_FAILED = "当前没有可停止的任务，或停止失败。";
    public static final String STEER_ACCEPTED = "已向进行中的任务追加指引。";
    public static final String STEER_FAILED = "无法追加指引（没有进行中的任务，或当前状态不接受 steer）。";
    public static final String MODE_UPDATED = "已更新运行模式。";
    public static final String MODE_INVALID =
            "模式无效。请使用：/mode flash | thinking | pro | ultra";
    public static final String COMMAND_WEB_ONLY =
            "该命令请在 Web 端使用（飞书侧暂未接入完整 UI）。";
    public static final String UNSUPPORTED_MEDIA =
            "暂不支持该类型的飞书消息。请发送文字，或发送图片 / 文件。";
    public static final String MEDIA_FAILED = "无法读取您发送的图片或文件，请重试或改用网页端上传。";

    private FeishuInteraction() {}

    public static Optional<FeishuPendingInput> pendingFromMessages(JsonNode messages) {
        if (messages == null || !messages.isArray() || messages.isEmpty()) {
            return Optional.empty();
        }
        int cardIndex = unansweredCardIndex(messages);
        if (cardIndex < 0) {
            return Optional.empty();
        }
        JsonNode tail = messages.get(cardIndex);
        JsonNode humanInput = tail.path("artifact").path("human_input");
        if (!humanInput.isObject()) {
            return Optional.empty();
        }
        int version = humanInput.path("version").asInt(1);
        String inputMode = humanInput.path("input_mode").asText("");
        boolean form = version != 1 || "form".equals(inputMode);
        List<FeishuPendingInput.Field> fields = parseFields(humanInput.path("fields"));
        boolean formUnsupported = form && !supportsFormFields(fields);
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
                preface(messages, cardIndex),
                content,
                options,
                formUnsupported,
                fields,
                humanInput.path("risk_level").asText(""),
                humanInput.path("purpose").asText(""),
                parseFacts(humanInput.path("environment_facts")),
                parseFacts(humanInput.path("technical_facts"))));
    }

    static boolean supportsFormFields(List<FeishuPendingInput.Field> fields) {
        if (fields == null || fields.isEmpty()) {
            return false;
        }
        for (FeishuPendingInput.Field field : fields) {
            if (!FORM_FIELD_TYPES.contains(field.type())) {
                return false;
            }
            if (field.name().isBlank()) {
                return false;
            }
        }
        return true;
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
        writeHumanMessage(message, content, response, List.of());
    }

    public static void writeHumanMessage(
            ObjectNode message,
            String content,
            FeishuHumanResponse response,
            List<FeishuAgentBridge.UploadedFile> files) {
        message.put("id", UUID.randomUUID().toString());
        message.put("type", "human");
        message.put("content", content == null ? "" : content);
        boolean hasFiles = files != null && !files.isEmpty();
        if (response == null && !hasFiles) {
            return;
        }
        ObjectNode additional = message.putObject("additional_kwargs");
        if (hasFiles) {
            var array = additional.putArray("files");
            for (FeishuAgentBridge.UploadedFile file : files) {
                ObjectNode entry = array.addObject();
                entry.put("filename", file.filename());
                entry.put("path", file.path());
                entry.put("size", file.size());
                entry.put("status", "uploaded");
            }
        }
        if (response == null) {
            return;
        }
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
        if (pending.formSupported()) {
            return formCard(pending);
        }
        if ("choice_with_other".equals(pending.inputMode()) && !pending.formUnsupported()) {
            return choiceWithOtherCard(pending);
        }
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

    /**
     * Web-compatible Form v2 submission value: readable summary plus {@code [values: {...}]} JSON
     * block (see frozen {@code buildHumanInputFormSubmissionValue}).
     */
    public static String buildFormSubmissionValue(
            FeishuPendingInput pending, Map<String, Object> formValues) {
        Map<String, Object> values = formValues == null ? Map.of() : formValues;
        List<String> summaryParts = new ArrayList<>();
        Map<String, Object> record = new LinkedHashMap<>();
        for (FeishuPendingInput.Field field : pending.fields()) {
            Object raw = values.get(field.name());
            if (isEmptyFormValue(raw)) {
                continue;
            }
            Object normalized = normalizeFormValue(raw);
            record.put(field.name(), normalized);
            summaryParts.add(field.label() + ": " + formatFormValue(normalized));
        }
        try {
            return String.join("; ", summaryParts) + " [values: " + JSON.writeValueAsString(record) + "]";
        } catch (Exception failed) {
            return String.join("; ", summaryParts);
        }
    }

    public static boolean formMissingRequired(FeishuPendingInput pending, Map<String, Object> formValues) {
        Map<String, Object> values = formValues == null ? Map.of() : formValues;
        for (FeishuPendingInput.Field field : pending.fields()) {
            if (field.required() && isEmptyFormValue(values.get(field.name()))) {
                return true;
            }
        }
        return false;
    }

    public static String formNotice(FeishuPendingInput pending) {
        return formNotice(pending, "");
    }

    /** Web handoff for form cards; includes thread id when known so the operator can open the chat. */
    public static String formNotice(FeishuPendingInput pending, String threadId) {
        String body = pending == null || pending.content().isBlank()
                ? (pending == null ? "" : pending.question())
                : pending.content();
        StringBuilder notice = new StringBuilder();
        if (body != null && !body.isBlank()) {
            notice.append(body.strip()).append("\n\n");
        }
        notice.append("请在 Web 端完成该表单。");
        if (threadId != null && !threadId.isBlank()) {
            notice.append("\n对话 ID：").append(threadId.trim());
            notice.append("\n打开路径：/workspace/chats/").append(threadId.trim());
        }
        return notice.toString();
    }

    /** Card shown after a click. No buttons, so the same action cannot be submitted again. */
    public static Map<String, Object> settledCard(FeishuInboundMessage message, String toast) {
        if (!PROCESSING.equals(toast) && !ALREADY_HANDLED.equals(toast)) {
            return null;
        }
        String body = ALREADY_HANDLED.equals(toast)
                ? ALREADY_HANDLED + "\n\n按钮已关闭。"
                : "已提交：" + choiceLabel(message) + "\n\n按钮已关闭，不能再次点击。";
        Map<String, Object> markdown = Map.of("tag", "markdown", "content", body);
        if (schema2(message)) {
            // Feishu 200830: a JSON 2.0 card cannot be replaced by a JSON 1.0 card.
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("schema", "2.0");
            card.put("config", Map.of("update_multi", true));
            card.put("body", Map.of("elements", List.of(markdown)));
            return card;
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("config", Map.of("wide_screen_mode", true, "update_multi", true));
        card.put("elements", List.of(markdown));
        return card;
    }

    private static boolean schema2(FeishuInboundMessage message) {
        if (message == null || message.cardAction() == null) {
            return false;
        }
        return "2.0".equals(message.cardAction().cardSchema())
                || OTHER_OPTION.equals(message.cardAction().optionId());
    }

    private static String choiceLabel(FeishuInboundMessage message) {
        if (message == null || message.cardAction() == null) {
            return "";
        }
        String value = message.cardAction().value();
        if (value == null || value.isBlank()) {
            value = message.cardAction().optionId();
        }
        if ("approve".equalsIgnoreCase(value)) {
            return "Approve";
        }
        if ("reject".equalsIgnoreCase(value)) {
            return "Reject";
        }
        return value;
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
        appendApprovalMetadata(body, pending);
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

    private static void appendApprovalMetadata(StringBuilder body, FeishuPendingInput pending) {
        if (!pending.riskLevel().isBlank()) {
            body.append("\n\n**风险级别：** ").append(pending.riskLevel().strip());
        }
        if (!pending.purpose().isBlank()) {
            body.append("\n**目的：** ").append(truncate(pending.purpose().strip(), 240));
        }
        appendFacts(body, "环境事实", pending.environmentFacts());
        appendFacts(body, "技术事实", pending.technicalFacts());
    }

    private static void appendFacts(StringBuilder body, String title, List<FeishuPendingInput.Fact> facts) {
        if (facts == null || facts.isEmpty()) {
            return;
        }
        body.append("\n**").append(title).append("：**");
        int shown = 0;
        for (FeishuPendingInput.Fact fact : facts) {
            if (shown >= 6) {
                body.append("\n- …");
                break;
            }
            String label = fact.label().isBlank() ? "item" : truncate(fact.label(), 80);
            String value = truncate(fact.value(), 160);
            body.append("\n- ").append(label).append(": ").append(value);
            shown++;
        }
    }

    private static String footer(FeishuPendingInput pending) {
        if (pending.formSupported()) {
            return "请在卡片中填写字段后提交。";
        }
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
            return "可点选项，或在「其他回答」中输入后提交。直接回复其他原文也会按自由输入提交。";
        }
        return "可点按钮，或精确回复序号 / 选项原文。";
    }

    /** Schema 2.0 Form v2 card for the approved Feishu field subset. */
    private static Map<String, Object> formCard(FeishuPendingInput pending) {
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(Map.of("tag", "markdown", "content", markdown(pending)));
        List<Map<String, Object>> formElements = new ArrayList<>();
        for (FeishuPendingInput.Field field : pending.fields()) {
            formElements.add(formFieldElement(field));
        }
        Map<String, Object> value = buttonValue(pending);
        value.put("action", FORM_SUBMIT);
        value.put("option_id", FORM_SUBMIT);
        Map<String, Object> submit = new LinkedHashMap<>();
        submit.put("tag", "button");
        submit.put("name", "form_submit");
        submit.put("text", Map.of("tag", "plain_text", "content", "提交"));
        submit.put("type", "primary");
        submit.put("width", "fill");
        submit.put("form_action_type", "submit");
        submit.put("behaviors", List.of(Map.of("type", "callback", "value", value)));
        formElements.add(submit);
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("tag", "form");
        form.put("name", "human_input_form");
        form.put("elements", formElements);
        elements.add(form);
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("schema", "2.0");
        card.put("config", Map.of("update_multi", true));
        card.put("body", Map.of("elements", elements));
        return card;
    }

    private static Map<String, Object> formFieldElement(FeishuPendingInput.Field field) {
        if ("select".equals(field.type())) {
            List<Map<String, Object>> options = new ArrayList<>();
            for (FeishuPendingInput.Option option : field.options()) {
                options.add(Map.of(
                        "text", Map.of(
                                "tag", "plain_text",
                                "content", option.label().isBlank() ? option.id() : option.label()),
                        "value", option.value().isBlank() ? option.id() : option.value()));
            }
            Map<String, Object> select = new LinkedHashMap<>();
            select.put("tag", "select_static");
            select.put("name", field.name());
            select.put("required", field.required());
            select.put("placeholder", Map.of(
                    "tag",
                    "plain_text",
                    "content",
                    field.placeholder().isBlank() ? "请选择" : field.placeholder()));
            select.put("options", options);
            select.put("label", Map.of(
                    "tag",
                    "plain_text",
                    "content",
                    field.label().isBlank() ? field.name() : field.label()));
            return select;
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("tag", "textarea".equals(field.type()) ? "input" : "input");
        input.put("name", field.name());
        input.put("required", field.required());
        input.put("width", "fill");
        input.put("label", Map.of(
                "tag",
                "plain_text",
                "content",
                field.label().isBlank() ? field.name() : field.label()));
        input.put("label_position", "top");
        input.put(
                "placeholder",
                Map.of(
                        "tag",
                        "plain_text",
                        "content",
                        field.placeholder().isBlank()
                                ? ("number".equals(field.type()) ? "输入数字…" : "输入…")
                                : field.placeholder()));
        return input;
    }

    private static List<FeishuPendingInput.Field> parseFields(JsonNode fieldsNode) {
        if (fieldsNode == null || !fieldsNode.isArray()) {
            return List.of();
        }
        List<FeishuPendingInput.Field> fields = new ArrayList<>();
        for (JsonNode field : fieldsNode) {
            List<FeishuPendingInput.Option> options = new ArrayList<>();
            if (field.path("options").isArray()) {
                for (JsonNode option : field.path("options")) {
                    options.add(new FeishuPendingInput.Option(
                            option.path("id").asText(""),
                            option.path("label").asText(""),
                            option.path("value").asText("")));
                }
            }
            fields.add(new FeishuPendingInput.Field(
                    field.path("name").asText(""),
                    field.path("label").asText(""),
                    field.path("type").asText(""),
                    field.path("required").asBoolean(false),
                    field.path("placeholder").asText(""),
                    options));
        }
        return List.copyOf(fields);
    }

    private static List<FeishuPendingInput.Fact> parseFacts(JsonNode factsNode) {
        if (factsNode == null || !factsNode.isArray()) {
            return List.of();
        }
        List<FeishuPendingInput.Fact> facts = new ArrayList<>();
        for (JsonNode fact : factsNode) {
            facts.add(new FeishuPendingInput.Fact(
                    fact.path("label").asText(""), fact.path("value").asText("")));
        }
        return List.copyOf(facts);
    }

    private static boolean isEmptyFormValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.trim().isEmpty();
        }
        if (value instanceof List<?> list) {
            return list.isEmpty();
        }
        return false;
    }

    private static Object normalizeFormValue(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                out.add(String.valueOf(item));
            }
            return out;
        }
        return String.valueOf(value).trim();
    }

    private static String formatFormValue(Object value) {
        if (value instanceof List<?> list) {
            return String.join(", ", list.stream().map(String::valueOf).toList());
        }
        if (value instanceof Boolean bool) {
            return bool ? "yes" : "no";
        }
        return String.valueOf(value);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** Schema 2.0 card: the listed choices plus the same free-text field the web card shows. */
    private static Map<String, Object> choiceWithOtherCard(FeishuPendingInput pending) {
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(Map.of("tag", "markdown", "content", markdown(pending)));
        for (FeishuPendingInput.Option option : pending.options()) {
            elements.add(callbackButton(pending, option));
        }
        elements.add(otherForm(pending));
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("schema", "2.0");
        card.put("config", Map.of("update_multi", true));
        card.put("body", Map.of("elements", elements));
        return card;
    }

    private static Map<String, Object> callbackButton(FeishuPendingInput pending, FeishuPendingInput.Option option) {
        String label = option.label().isBlank() ? option.id() : option.label();
        Map<String, Object> value = buttonValue(pending);
        value.put("option_id", option.id());
        value.put("value", option.value());
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("tag", "button");
        button.put("text", Map.of("tag", "plain_text", "content", label));
        button.put("type", "default");
        button.put("width", "fill");
        button.put("behaviors", List.of(Map.of("type", "callback", "value", value)));
        return button;
    }

    private static Map<String, Object> otherForm(FeishuPendingInput pending) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("tag", "input");
        input.put("name", "other");
        input.put("required", false);
        input.put("width", "fill");
        input.put("label", Map.of("tag", "plain_text", "content", "其他回答"));
        input.put("label_position", "top");
        input.put("placeholder", Map.of("tag", "plain_text", "content", "输入其他回答..."));
        Map<String, Object> value = buttonValue(pending);
        value.put("action", OTHER_OPTION);
        Map<String, Object> submit = new LinkedHashMap<>();
        submit.put("tag", "button");
        submit.put("name", "other_submit");
        submit.put("text", Map.of("tag", "plain_text", "content", "提交"));
        submit.put("type", "primary");
        submit.put("width", "fill");
        submit.put("form_action_type", "submit");
        submit.put("behaviors", List.of(Map.of("type", "callback", "value", value)));
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("tag", "form");
        form.put("name", "other_form");
        form.put("elements", List.of(input, submit));
        return form;
    }

    private static Map<String, Object> buttonValue(FeishuPendingInput pending) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source", pending.source());
        value.put("request_id", pending.requestId());
        value.put("card_schema", "2.0");
        return value;
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

    /**
     * The open card is the latest {@code ask_clarification} in this turn. Assistant text projected
     * after that card does not close it; a human message does.
     */
    private static int unansweredCardIndex(JsonNode messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            JsonNode message = messages.get(index);
            if (isAssistant(message)) {
                continue;
            }
            if (isClarification(message) && message.path("artifact").path("human_input").isObject()) {
                return index;
            }
            return -1;
        }
        return -1;
    }

    private static boolean isClarification(JsonNode message) {
        return "tool".equals(message.path("type").asText())
                && "ask_clarification".equals(message.path("name").asText());
    }

    private static boolean isAssistant(JsonNode message) {
        String type = message.path("type").asText(message.path("role").asText());
        return "ai".equals(type) || "assistant".equals(type);
    }

    private static String preface(JsonNode messages, int cardIndex) {
        List<String> parts = new ArrayList<>();
        for (int index = cardIndex - 1; index >= 0; index--) {
            JsonNode message = messages.get(index);
            if (!isAssistant(message)) {
                break;
            }
            String text = messageText(message);
            if (!text.isBlank()) {
                parts.add(text);
            }
        }
        java.util.Collections.reverse(parts);
        for (int index = cardIndex + 1; index < messages.size(); index++) {
            JsonNode message = messages.get(index);
            if (!isAssistant(message)) {
                break;
            }
            String text = messageText(message);
            if (!text.isBlank()) {
                parts.add(text);
            }
        }
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append(part);
        }
        return builder.toString();
    }
}

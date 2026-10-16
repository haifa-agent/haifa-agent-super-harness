package io.haifa.agent.superharness.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionInputType;
import io.haifa.agent.runtime.api.InteractionKind;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.ApprovalPresentation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Exact frozen-card wire projection over the SDK-owned Interaction lifecycle. */
final class DeerFlowInteractionHttpProjection {
    static final String SOURCE = "haifa_interaction";
    /** The frozen {@code HumanInputRequest.source} for the real {@code ask_clarification} tool. */
    static final String CLARIFICATION_SOURCE = "ask_clarification";
    private static final String TOOL_NAME = "ask_clarification";
    private static final Set<InteractionAction> APPROVAL_ACTIONS =
            Set.of(InteractionAction.APPROVE, InteractionAction.REJECT);

    private DeerFlowInteractionHttpProjection() {}

    static Optional<FrozenResponse> parseResponse(JsonNode messages) {
        if (messages == null || !messages.isArray()) {
            return Optional.empty();
        }
        JsonNode lastHuman = null;
        for (JsonNode message : messages) {
            String type = message.path("type").asText(message.path("role").asText());
            if (type.equals("human") || type.equals("user")) {
                lastHuman = message;
            }
        }
        if (lastHuman == null) {
            return Optional.empty();
        }
        JsonNode additional = lastHuman.get("additional_kwargs");
        if (additional == null || !additional.isObject() || !additional.has("human_input_response")) {
            return Optional.empty();
        }
        JsonNode raw = additional.get("human_input_response");
        if (raw == null
                || !raw.isObject()
                || raw.path("version").asInt(-1) != 1
                || !"human_input_response".equals(raw.path("kind").asText())) {
            throw invalidResponse();
        }
        String source = requiredText(raw, "source");
        String requestId = requiredText(raw, "request_id");
        String responseKind = requiredText(raw, "response_kind");
        String value = requiredText(raw, "value");
        String optionId = null;
        if (responseKind.equals("option")) {
            optionId = requiredText(raw, "option_id");
        } else if (!responseKind.equals("text")) {
            throw invalidResponse();
        }
        return Optional.of(new FrozenResponse(source, requestId, responseKind, optionId, value));
    }

    record ApprovalData(
            String title,
            String question,
            String context,
            String purpose,
            String content,
            String contentType,
            String riskLevel,
            List<Map<String, String>> environmentFacts,
            List<Map<String, String>> technicalFacts) {}

    static ApprovalData buildApprovalData(InteractionView interaction) {
        if (interaction.approvalPresentation().isPresent()) {
            ApprovalPresentation presentation = interaction.approvalPresentation().get();
            String title = presentation.title();
            String purpose = presentation.purpose();
            String content = presentation.content();
            String contentType = presentation.contentType();
            String riskLevel = presentation.risk().orElse("HIGH");
            List<Map<String, String>> env = presentation.environment().stream()
                    .map(fact -> Map.of("label", fact.label(), "value", fact.value()))
                    .toList();
            List<Map<String, String>> tech = presentation.technical().stream()
                    .map(fact -> Map.of("label", fact.label(), "value", fact.value()))
                    .toList();
            String question = purpose.isBlank() ? "Agent 请求执行该操作以继续任务，是否批准？" : purpose;
            return new ApprovalData(title, question, "风险级别: " + riskLevel, purpose, content, contentType, riskLevel, env, tech);
        }

        String toolRef = interaction.target().reference();
        String simpleName = simpleToolName(toolRef);
        String safePrompt = interaction.safePrompt();

        String title = friendlyTitle(interaction.title(), simpleName);
        List<Map<String, String>> techFacts = new ArrayList<>();
        List<Map<String, String>> envFacts = new ArrayList<>();
        String content = null;
        String contentType = null;
        String purpose = null;
        String riskLevel = "HIGH";

        if (safePrompt.contains("\n") && (safePrompt.contains("Mode:") || safePrompt.contains("Command:") || safePrompt.contains("Script:"))) {
            String[] lines = safePrompt.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("Mode:")) {
                    techFacts.add(Map.of("label", "模式", "value", trimmed.substring("Mode:".length()).trim()));
                } else if (trimmed.startsWith("Language:")) {
                    contentType = trimmed.substring("Language:".length()).trim();
                    techFacts.add(Map.of("label", "语言", "value", contentType));
                } else if (trimmed.startsWith("Command:")) {
                    content = trimmed.substring("Command:".length()).trim();
                } else if (trimmed.startsWith("Script:")) {
                    content = trimmed.substring("Script:".length()).trim();
                } else if (trimmed.startsWith("Purpose:")) {
                    purpose = trimmed.substring("Purpose:".length()).trim();
                } else if (trimmed.startsWith("Timeout:")) {
                    techFacts.add(Map.of("label", "超时", "value", trimmed.substring("Timeout:".length()).trim()));
                } else if (trimmed.startsWith("Workspace:")) {
                    envFacts.add(Map.of("label", "工作目录", "value", trimmed.substring("Workspace:".length()).trim()));
                } else if (trimmed.startsWith("Provider:")) {
                    envFacts.add(Map.of("label", "提供方", "value", trimmed.substring("Provider:".length()).trim()));
                } else if (trimmed.startsWith("Network:")) {
                    envFacts.add(Map.of("label", "网络访问", "value", trimmed.substring("Network:".length()).trim()));
                } else if (trimmed.startsWith("Invocation digest:")) {
                    techFacts.add(Map.of("label", "调用摘要", "value", trimmed.substring("Invocation digest:".length()).trim()));
                } else if (trimmed.startsWith("Risks:")) {
                    riskLevel = trimmed.contains("CRITICAL") ? "CRITICAL" : (trimmed.contains("HIGH") ? "HIGH" : "MEDIUM");
                }
            }
        }

        if (purpose == null || purpose.isBlank()) {
            purpose = "Agent 请求执行 " + simpleName + " 工具以继续推进任务。";
        }
        if (techFacts.isEmpty()) {
            techFacts.add(Map.of("label", "工具标识", "value", toolRef));
            interaction.target().digest().ifPresent(digest -> techFacts.add(Map.of("label", "调用摘要", "value", digest)));
        }

        String question = purpose;
        String context = "风险级别: " + riskLevel;
        return new ApprovalData(title, question, context, purpose, content, contentType, riskLevel, envFacts, techFacts);
    }

    private static String simpleToolName(String reference) {
        if (reference == null || reference.isBlank()) {
            return "tool";
        }
        int at = reference.indexOf('@');
        if (at > 0) {
            return reference.substring(0, at);
        }
        int hash = reference.indexOf('#');
        if (hash > 0) {
            return reference.substring(0, hash);
        }
        return reference;
    }

    private static String friendlyTitle(String originalTitle, String simpleName) {
        if (originalTitle != null && !originalTitle.isBlank()
                && !originalTitle.equals("Approve tool")
                && !originalTitle.equals("Approval required")
                && !originalTitle.contains("@")
                && !originalTitle.contains("#")) {
            return originalTitle;
        }
        return switch (simpleName) {
            case "execution_run" -> "执行宿主命令 (execution_run)";
            case "write_file" -> "修改/写入文件 (write_file)";
            case "edit_file" -> "编辑文件 (edit_file)";
            case "web_search" -> "网络搜索 (web_search)";
            case "web_fetch" -> "抓取网页内容 (web_fetch)";
            default -> "申请批准工具调用: " + simpleName;
        };
    }

    static Map<String, Object> requestMessage(InteractionView interaction, String runId) {
        requireApprovalCard(interaction);
        ApprovalData data = buildApprovalData(interaction);
        return requestMessage(
                runId,
                interaction.requestId().value(),
                interaction.target().reference(),
                data.title(),
                data.question(),
                data.context(),
                interaction.allowedActions().stream().map(InteractionAction::value).toList(),
                data.purpose(),
                data.content(),
                data.contentType(),
                data.riskLevel(),
                data.environmentFacts(),
                data.technicalFacts());
    }

    static Map<String, Object> requestMessage(
            String runId, String requestId, String toolCallId, String title, String question,
            String context, List<String> actions) {
        return requestMessage(
                runId, requestId, toolCallId, title, question, context, actions,
                null, null, null, null, List.of(), List.of());
    }

    static Map<String, Object> requestMessage(
            String runId, String requestId, String toolCallId, String title, String question,
            String context, List<String> actions, String purpose, String content, String contentType,
            String riskLevel, List<Map<String, String>> envFacts, List<Map<String, String>> techFacts) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (String value : actions) {
            options.add(Map.of(
                    "id", value,
                    "label", label(value),
                    "value", value));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", 1);
        payload.put("kind", "human_input_request");
        payload.put("source", SOURCE);
        payload.put("request_id", requestId);
        payload.put("tool_call_id", toolCallId);
        payload.put("clarification_type", "risk_confirmation");
        payload.put("title", title);
        payload.put("question", question);
        payload.put("context", context);
        payload.put("input_mode", "single_choice");
        payload.put("options", options);
        if (purpose != null && !purpose.isBlank()) {
            payload.put("purpose", purpose);
        }
        if (content != null && !content.isBlank()) {
            payload.put("content", content);
        }
        if (contentType != null && !contentType.isBlank()) {
            payload.put("content_type", contentType);
        }
        if (riskLevel != null && !riskLevel.isBlank()) {
            payload.put("risk_level", riskLevel);
        }
        if (envFacts != null && !envFacts.isEmpty()) {
            payload.put("environment_facts", envFacts);
        }
        if (techFacts != null && !techFacts.isEmpty()) {
            payload.put("technical_facts", techFacts);
        }

        String interactionMessageId = "interaction-" + requestId;
        // The top-level tool_call_id is only the frozen UI grouping identity. It must not collide
        // with the authoritative Runtime ToolCall result; the nested payload retains the real
        // ToolCall target that this Interaction approves.
        return humanInputMessage(runId, interactionMessageId, interactionMessageId, question, payload);
    }

    /**
     * Shared frozen Human Input Card wire shape: a {@code type: tool} message named
     * {@code ask_clarification} whose {@code artifact.human_input} carries the request payload. The
     * approval card and the real clarification tool both use this so the frozen UI parses them with
     * one code path; the approval path's {@code requireApprovalCard} constraint is unchanged.
     */
    static Map<String, Object> humanInputMessage(
            String runId, String messageId, String toolCallId, String content, Map<String, Object> payload) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", messageId);
        message.put("type", "tool");
        message.put("name", TOOL_NAME);
        message.put("content", content);
        message.put("tool_call_id", toolCallId);
        message.put("artifact", Map.of("human_input", payload));
        message.put("additional_kwargs", Map.of());
        message.put("run_id", runId);
        return message;
    }

    static InteractionResponseSubmission submission(
            InteractionView interaction, FrozenResponse response, Instant respondedAt) {
        requireApprovalCard(interaction);
        if (!SOURCE.equals(response.source())
                || !interaction.requestId().value().equals(response.requestId())
                || !"option".equals(response.responseKind())
                || response.optionId() == null
                || !response.optionId().equals(response.value())) {
            throw new IllegalArgumentException("Human input response does not match the pending interaction");
        }
        InteractionAction action = new InteractionAction(response.optionId());
        if (!interaction.allowedActions().contains(action)) {
            throw new IllegalArgumentException("Human input response action is not allowed");
        }
        String stableId = "deerflow:" + response.requestId() + ":" + action.value();
        return new InteractionResponseSubmission(
                new InteractionResponseId(stableId),
                interaction.requestId(),
                interaction.runId(),
                interaction.revision(),
                action,
                List.of(),
                stableId,
                sdkMillisecondTime(respondedAt));
    }

    // The SDK Runtime records receivedAt with millisecond precision and rejects a
    // respondedAt that is later within the same millisecond.
    static Instant sdkMillisecondTime(Instant instant) {
        return Instant.ofEpochMilli(instant.toEpochMilli());
    }

    static Map<String, Object> responseMessage(
            FrozenResponse response, String submittedText, String runId) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", "interaction-response-" + response.requestId());
        message.put("type", "human");
        message.put("content", submittedText);
        message.put("tool_calls", List.of());
        message.put(
                "additional_kwargs",
                Map.of(
                        "hide_from_ui", true,
                        "human_input_response", response.toWireValue()));
        message.put("run_id", runId);
        return message;
    }

    private static void requireApprovalCard(InteractionView interaction) {
        if (interaction.state() != InteractionState.PENDING
                || !interaction.kind().equals(InteractionKind.APPROVAL)
                || !interaction.inputContract().type().equals(InteractionInputType.NONE)
                || interaction.allowedActions().isEmpty()
                || !APPROVAL_ACTIONS.containsAll(interaction.allowedActions())) {
            throw new IllegalArgumentException(
                    "Pending Interaction cannot be represented by the frozen approval card");
        }
    }

    private static String label(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String requiredText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalidResponse();
        }
        return value.textValue();
    }

    private static IllegalArgumentException invalidResponse() {
        return new IllegalArgumentException("Invalid human_input_response");
    }

    record FrozenResponse(
            String source, String requestId, String responseKind, String optionId, String value) {
        Map<String, Object> toWireValue() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("version", 1);
            value.put("kind", "human_input_response");
            value.put("source", source);
            value.put("request_id", requestId);
            value.put("response_kind", responseKind);
            if (optionId != null) {
                value.put("option_id", optionId);
            }
            value.put("value", this.value);
            return value;
        }
    }
}

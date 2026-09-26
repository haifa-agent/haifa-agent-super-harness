package io.haifa.agent.superharness.server;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionInputType;
import io.haifa.agent.runtime.api.InteractionKind;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.InteractionView;
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

    static Map<String, Object> requestMessage(InteractionView interaction, String runId) {
        requireApprovalCard(interaction);
        List<Map<String, Object>> options = new ArrayList<>();
        for (InteractionAction action : interaction.allowedActions()) {
            String value = action.value();
            options.add(Map.of(
                    "id", value,
                    "label", label(value),
                    "value", value));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", 1);
        payload.put("kind", "human_input_request");
        payload.put("source", SOURCE);
        payload.put("request_id", interaction.requestId().value());
        payload.put("tool_call_id", interaction.target().reference());
        payload.put("clarification_type", "risk_confirmation");
        payload.put("title", interaction.title());
        payload.put("question", interaction.safePrompt());
        payload.put("context", interaction.target().safeSummary());
        payload.put("input_mode", "single_choice");
        payload.put("options", options);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", "interaction-" + interaction.requestId().value());
        message.put("type", "tool");
        message.put("name", TOOL_NAME);
        message.put("content", interaction.safePrompt());
        message.put("tool_call_id", interaction.target().reference());
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

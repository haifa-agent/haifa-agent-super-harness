package io.haifa.agent.superharness.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.runtime.api.ToolCallView;
import io.haifa.agent.runtime.api.ToolResultView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Frozen LangGraph message projection over the caller-authorized SDK ToolCall view. */
final class DeerFlowToolTranscriptProjection {
    static final String ASK_CLARIFICATION = "ask_clarification";
    private static final ObjectMapper JSON = new ObjectMapper();

    private DeerFlowToolTranscriptProjection() {}

    /**
     * The completed {@code ask_clarification} call ends the Run in frozen DeerFlow (its middleware
     * returns {@code Command(goto=END)}). SH only projects the Run up to that tool result: later
     * model text and tool calls are never shown, whether the Run ends COMPLETED or CANCELLED. The
     * boundary is derived from the durable SDK ToolCall view, so it is identical after a refresh or a
     * gateway restart.
     */
    static int clarificationBoundaryIndex(List<ToolCallView> calls) {
        for (int index = 0; index < calls.size(); index++) {
            ToolCallView call = calls.get(index);
            if (ASK_CLARIFICATION.equals(call.toolName())
                    && call.status() == ToolCallStatus.COMPLETED
                    && call.result().isPresent()) {
                return index;
            }
        }
        return -1;
    }

    static boolean hasClarificationBoundary(List<ToolCallView> calls) {
        return clarificationBoundaryIndex(calls) >= 0;
    }

    static List<ToolCallView> upToClarificationBoundary(List<ToolCallView> calls) {
        int boundary = clarificationBoundaryIndex(calls);
        return boundary < 0 ? calls : List.copyOf(calls.subList(0, boundary + 1));
    }

    static List<Map<String, Object>> messages(List<ToolCallView> calls) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ToolCallView call : upToClarificationBoundary(calls)) {
            messages.add(requestMessage(call));
            resultMessage(call).ifPresent(messages::add);
        }
        return List.copyOf(messages);
    }

    static Map<String, Object> requestMessage(ToolCallView call) {
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", call.id().value());
        toolCall.put("name", call.toolName());
        toolCall.put("args", call.arguments().values());
        toolCall.put("type", "tool_call");

        Map<String, Object> message = baseMessage(call, "request", "ai");
        message.put("content", "");
        message.put("tool_calls", List.of(toolCall));
        message.put("additional_kwargs", Map.of("haifa_tool_status", wireStatus(call.status())));
        return message;
    }

    static Optional<Map<String, Object>> resultMessage(ToolCallView call) {
        if (call.result().isEmpty() && !terminal(call.status())) {
            return Optional.empty();
        }
        if (ASK_CLARIFICATION.equals(call.toolName())
                && call.status() == ToolCallStatus.COMPLETED
                && call.result().isPresent()) {
            return clarificationCard(call);
        }
        Map<String, Object> message = baseMessage(call, "result", "tool");
        message.put("name", call.toolName());
        message.put("tool_call_id", call.id().value());
        message.put("tool_calls", List.of());

        Map<String, Object> additional = new LinkedHashMap<>();
        additional.put("haifa_tool_status", wireStatus(call.status()));
        if (call.result().isPresent()) {
            ToolResultView result = call.result().orElseThrow();
            message.put("content", result.summary().text());
            additional.put("haifa_tool_result", result.structuredData().values());
            additional.put("haifa_tool_result_truncated", result.truncated());
            additional.put(
                    "haifa_artifact_refs",
                    result.artifacts().stream()
                            .map(reference -> Map.of(
                                    "id", reference.artifactId(),
                                    "type", reference.artifactType(),
                                    "version", reference.version(),
                                    "title", reference.title()))
                            .toList());
        } else {
            String error = call.error().map(value -> value.wireCode()).orElse("tool_call_failed");
            message.put("content", agentArgumentNotice(call)
                    .map(notice -> "Error: " + error + ". " + notice)
                    .orElse(error));
            additional.put("haifa_tool_error", error);
        }
        message.put("additional_kwargs", Map.copyOf(additional));
        return Optional.of(message);
    }

    /**
     * Display-only notice for the two product field contracts. This is reached only for a
     * terminal call without a result; it does not validate types or required fields, control
     * execution, or infer why the SDK stopped the call. Unknown keys and all values remain
     * unprinted, including keys that themselves contain user-authored text.
     */
    private static Optional<String> agentArgumentNotice(ToolCallView call) {
        List<String> allowed = switch (call.toolName()) {
            case "setup_agent" -> List.of("soul", "description", "skills");
            case "update_agent" -> List.of("soul", "description", "display_name");
            default -> List.of();
        };
        if (allowed.isEmpty() || call.arguments().values().keySet().stream().allMatch(allowed::contains)) {
            return Optional.empty();
        }
        return Optional.of("Recorded arguments include unsupported, undeclared parameters; allowed fields for "
                + call.toolName() + ": " + String.join(", ", allowed) + ".");
    }

    static List<String> artifactPaths(List<ToolCallView> calls) {
        return upToClarificationBoundary(calls).stream()
                .flatMap(call -> presentedPaths(call).stream())
                .filter(path -> path.startsWith("/mnt/user-data/"))
                .distinct()
                .toList();
    }

    /**
     * Frozen {@code ClarificationMiddleware} ToolMessage: the readable fallback is the content and
     * the structured request payload is {@code artifact.human_input}. The tool already normalized the
     * payload; SH only re-serializes the exact frozen shape, sharing the wire builder with the
     * approval card.
     */
    static Optional<Map<String, Object>> clarificationCard(ToolCallView call) {
        Map<String, Object> payload = clarificationPayload(call).orElse(null);
        if (payload == null) {
            return Optional.empty();
        }
        String messageId = clarificationRequestId(call, payload);
        return Optional.of(DeerFlowInteractionHttpProjection.humanInputMessage(
                call.runId().value(), messageId, call.id().value(),
                call.result().orElseThrow().summary().text(), payload));
    }

    /** The normalized {@code artifact.human_input} payload of a completed clarification call. */
    static Optional<Map<String, Object>> clarificationPayload(ToolCallView call) {
        if (call.result().isEmpty()) {
            return Optional.empty();
        }
        Object raw = call.result().orElseThrow().structuredData().values().get("humanInput");
        if (!(raw instanceof String humanInput) || humanInput.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> payload =
                    JSON.readValue(humanInput, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            return Optional.ofNullable(payload);
        } catch (JsonProcessingException invalid) {
            return Optional.empty();
        }
    }

    static String clarificationRequestId(ToolCallView call, Map<String, Object> payload) {
        Object requestId = payload.get("request_id");
        return requestId instanceof String text && !text.isBlank()
                ? text
                : "clarification:" + call.id().value();
    }

    /**
     * The output paths of one completed file tool call. {@code write_file}/{@code str_replace}
     * publish an Artifact with a durable id, and {@code present_files} returns the normalized
     * outputs paths it validated (it publishes any that were not already listed), so both keep the
     * frozen UI's automatic artifact list complete without duplicating a path.
     */
    private static List<String> presentedPaths(ToolCallView call) {
        if (call.status() != ToolCallStatus.COMPLETED || call.result().isEmpty()) {
            return List.of();
        }
        Map<String, Object> values = call.result().orElseThrow().structuredData().values();
        if ("present_files".equals(call.toolName())) {
            return values.get("paths") instanceof List<?> paths
                    ? paths.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                    : List.of();
        }
        if ("write_file".equals(call.toolName()) || "str_replace".equals(call.toolName())) {
            if (values.get("artifactId") instanceof String id && !id.isBlank()
                    && values.get("path") instanceof String path) {
                return List.of(path);
            }
        }
        return List.of();
    }

    private static Map<String, Object> baseMessage(ToolCallView call, String suffix, String type) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", "tool-call-" + call.id().value() + "-" + suffix);
        message.put("type", type);
        message.put("run_id", call.runId().value());
        return message;
    }

    private static String wireStatus(ToolCallStatus status) {
        return status.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean terminal(ToolCallStatus status) {
        return status == ToolCallStatus.COMPLETED
                || status == ToolCallStatus.FAILED
                || status == ToolCallStatus.DENIED
                || status == ToolCallStatus.CANCELLED
                || status == ToolCallStatus.TIMEOUT;
    }
}

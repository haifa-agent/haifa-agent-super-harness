package io.haifa.agent.superharness.server;

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
    private DeerFlowToolTranscriptProjection() {}

    static List<Map<String, Object>> messages(List<ToolCallView> calls) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ToolCallView call : calls) {
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
            message.put("content", error);
            additional.put("haifa_tool_error", error);
        }
        message.put("additional_kwargs", Map.copyOf(additional));
        return Optional.of(message);
    }

    static List<String> artifactPaths(List<ToolCallView> calls) {
        return calls.stream()
                .filter(call -> call.status() == ToolCallStatus.COMPLETED)
                .filter(call -> "write_file".equals(call.toolName()) || "str_replace".equals(call.toolName()))
                .flatMap(call -> call.result().stream())
                .map(result -> result.structuredData().values())
                .filter(values -> values.get("artifactId") instanceof String id && !id.isBlank())
                .map(values -> values.get("path"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(path -> path.startsWith("/mnt/user-data/"))
                .distinct()
                .toList();
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

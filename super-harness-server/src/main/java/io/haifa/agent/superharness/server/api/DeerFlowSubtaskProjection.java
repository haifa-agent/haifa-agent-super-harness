package io.haifa.agent.superharness.server.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.runtime.api.RunMessageView;
import io.haifa.agent.runtime.api.ToolCallView;
import java.util.LinkedHashMap;
import java.util.Map;

/** Frozen DeerFlow step shapes, derived only from caller-authorized SDK message facts. */
final class DeerFlowSubtaskProjection {
    static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    static final int MAX_CHARS = 8_192;
    private static final ObjectMapper JSON = new ObjectMapper();

    private DeerFlowSubtaskProjection() {}

    static Map<String, Object> event(String taskId, RunMessageView source) {
        checkNumber(source.sequence(), false);
        Map<String, Object> content = step(taskId, source);
        return Map.of(
                "seq", source.sequence(),
                "event_type", "subagent.step",
                "category", "subagent",
                "content", content,
                "metadata", Map.of("task_id", taskId, "message_index", source.messageIndex()));
    }

    static Map<String, Object> running(String taskId, RunMessageView source, Map<String, Object> facts) {
        Map<String, Object> step = step(taskId, source);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", step.get("kind"));
        message.put("content", step.get("text"));
        message.put("truncated", step.get("truncated"));
        if (step.containsKey("tool_calls")) message.put("tool_calls", step.get("tool_calls"));
        if (step.containsKey("tool_name")) message.put("name", step.get("tool_name"));
        Map<String, Object> event = lifecycle("task_running", taskId, facts);
        event.put("message", Map.copyOf(message));
        event.put("message_index", source.messageIndex());
        return Map.copyOf(event);
    }

    static Map<String, Object> lifecycle(String type, String taskId, Map<String, Object> facts) {
        if (taskId == null || taskId.isBlank()) throw new IllegalStateException("Missing task identity");
        Map<String, Object> event = new LinkedHashMap<>(facts);
        event.put("type", type);
        event.put("task_id", taskId);
        return event;
    }

    static long checkNumber(long value, boolean zeroAllowed) {
        if (value < (zeroAllowed ? 0 : 1) || value > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("Subtask cursor or index is outside the safe integer range");
        }
        return value;
    }

    static String bounded(String value) {
        return value.codePointCount(0, value.length()) > MAX_CHARS
                ? value.substring(0, value.offsetByCodePoints(0, MAX_CHARS)) : value;
    }

    private static Map<String, Object> step(String taskId, RunMessageView source) {
        if (taskId == null || taskId.isBlank()) throw new IllegalStateException("Missing task identity");
        checkNumber(source.sequence(), false);
        checkNumber(source.messageIndex(), false);
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("task_id", taskId);
        step.put("message_index", source.messageIndex());
        step.put("text", bounded(source.text()));
        step.put("truncated", source.textTruncated()
                || source.text().codePointCount(0, source.text().length()) > MAX_CHARS);
        switch (source.role()) {
            case "ASSISTANT" -> {
                step.put("kind", "ai");
                step.put("tool_calls", source.toolCalls().stream().map(DeerFlowSubtaskProjection::toolCall).toList());
            }
            case "TOOL" -> {
                if (source.toolCalls().size() > 1) throw new IllegalStateException("Tool step has ambiguous Tool bindings");
                step.put("kind", "tool");
                // Public redaction retains the message/index while withholding every Tool reference.
                // Preserve that authorized step; a name is optional and must never be reconstructed.
                if (!source.toolCalls().isEmpty()) step.put("tool_name", source.toolCalls().getFirst().toolName());
            }
            default -> throw new IllegalStateException("Unsupported SDK step role");
        }
        return Map.copyOf(step);
    }

    private static Map<String, Object> toolCall(ToolCallView call) {
        Map<String, Object> projected = new LinkedHashMap<>();
        projected.put("name", call.toolName());
        try {
            String serialized = JSON.writeValueAsString(call.arguments().values());
            boolean clipped = serialized.codePointCount(0, serialized.length()) > MAX_CHARS;
            projected.put("args", clipped ? bounded(serialized) : call.arguments().values());
            if (clipped || call.arguments().truncated()) projected.put("args_truncated", true);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("SDK Tool arguments cannot be projected");
        }
        return Map.copyOf(projected);
    }
}

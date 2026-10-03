package io.haifa.agent.superharness.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.model.api.ModelReasoningEffort;
import io.haifa.agent.model.api.ModelReasoningPolicy;
import java.util.Set;

/** Maps the frozen composer's separate thinking and effort fields to the SDK policy. */
final class DeerFlowReasoningSettings {
    private DeerFlowReasoningSettings() {}

    static ModelReasoningPolicy resolve(JsonNode context, JsonNode agent, boolean supported) {
        String mode = validateExecutionPreset(context);
        boolean enabled;
        if (context.has("thinking_enabled")) {
            enabled = Boolean.TRUE.equals(booleanValue(context, "thinking_enabled"));
        } else if (mode != null) {
            enabled = !"flash".equals(mode);
        } else if (agent != null && agent.hasNonNull("thinking_enabled")) {
            enabled = Boolean.TRUE.equals(booleanValue(agent, "thinking_enabled"));
        } else {
            enabled = supported;
        }
        if ("flash".equals(mode) && enabled) {
            throw new IllegalArgumentException("flash mode does not support thinking");
        }
        JsonNode effort = context.has("reasoning_effort") ? context.get("reasoning_effort")
                : agent != null && agent.hasNonNull("reasoning_effort") ? agent.get("reasoning_effort") : null;
        ModelReasoningEffort effective = effort(effort);
        if (!enabled) {
            if (context.hasNonNull("reasoning_effort") && !"minimal".equals(effort.asText())) {
                throw new IllegalArgumentException("reasoning_effort is not supported in flash mode");
            }
            return ModelReasoningPolicy.disabled();
        }
        if (!supported) {
            throw new IllegalArgumentException("Selected model does not support thinking");
        }
        return ModelReasoningPolicy.enabled(effective == null
                ? "thinking".equals(mode) ? ModelReasoningEffort.LOW : ModelReasoningEffort.HIGH : effective);
    }

    static String validateExecutionPreset(JsonNode context) {
        String mode = null;
        if (context.hasNonNull("mode")) {
            JsonNode value = context.get("mode");
            if (!value.isTextual() || !Set.of("flash", "thinking", "pro", "ultra").contains(value.asText())) {
                throw new IllegalArgumentException("Unsupported mode '" + value.asText() + "'");
            }
            mode = value.asText();
        }
        if (Boolean.TRUE.equals(booleanValue(context, "is_plan_mode")) || "pro".equals(mode)
                || "ultra".equals(mode)) {
            throw new IllegalArgumentException("Plan mode requires W07 plan execution");
        }
        // W08 admits the Child declaration separately; this parser validates the field
        // without treating delegation as a reasoning or plan capability.
        booleanValue(context, "subagent_enabled");
        booleanValue(context, "thinking_enabled");
        effort(context.get("reasoning_effort"));
        if ("flash".equals(mode) && Boolean.TRUE.equals(booleanValue(context, "thinking_enabled"))) {
            throw new IllegalArgumentException("flash mode does not support thinking");
        }
        if ("flash".equals(mode) && context.hasNonNull("reasoning_effort")
                && !"minimal".equals(context.get("reasoning_effort").asText())) {
            throw new IllegalArgumentException("reasoning_effort is not supported in flash mode");
        }
        return mode;
    }

    private static Boolean booleanValue(JsonNode source, String field) {
        JsonNode value = source.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isBoolean()) throw new IllegalArgumentException(field + " must be boolean or null");
        return value.booleanValue();
    }

    private static ModelReasoningEffort effort(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("Invalid reasoning_effort");
        return switch (value.asText()) {
            case "minimal", "low" -> ModelReasoningEffort.LOW;
            case "medium", "high" -> ModelReasoningEffort.HIGH;
            default -> throw new IllegalArgumentException("Invalid reasoning_effort");
        };
    }
}

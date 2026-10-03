package io.haifa.agent.superharness.server.api;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure wire adaptation of already-authorized SDK task arguments and results; owns no execution state. */
final class DeerFlowDelegationProjection {
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "DENIED", "CANCELLED", "TIMEOUT");
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private DeerFlowDelegationProjection() {}

    static Map<String, Object> arguments(Map<String, Object> values) {
        Objects.requireNonNull(values, "values must not be null");
        Map<String, Object> projected = new LinkedHashMap<>();
        copyText(values, "agent", projected, "subagent_type");
        copyText(values, "objective", projected, "prompt");
        // The SDK currently has no description argument. No synthetic description is necessary:
        // the frozen card falls back to prompt. context/expected_output stay in the SDK transcript.
        return Map.copyOf(projected);
    }

    static Map<String, Object> result(
            String toolStatus, Boolean successful, Map<String, Object> data,
            boolean dataTruncated, String errorCode) {
        Objects.requireNonNull(toolStatus, "toolStatus must not be null");
        Objects.requireNonNull(data, "data must not be null");
        if (!TERMINAL.contains(toolStatus)) return Map.of();
        String status = terminalStatus(toolStatus, successful, data.get("status"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("subagent_status", status);
        if ("completed".equals(status)) {
            if (data.get("outputPreview") instanceof String preview
                    && data.get("outputTruncated") instanceof Boolean) {
                if (!preview.isBlank()) metadata.put("subagent_result_brief", brief(preview));
                // A missing digest is unknown. Never hash a bounded or redacted preview as the full result.
                if (data.get("outputSha256") instanceof String digest && digest.matches("[0-9a-f]{64}")) {
                    metadata.put("subagent_result_sha256", digest);
                }
            } else if (!dataTruncated && Boolean.FALSE.equals(data.get("truncated"))
                    && data.get("summary") instanceof String summary && !summary.isBlank()) {
                // Older SDK result: display its summary, but do not claim complete-output identity.
                metadata.put("subagent_result_brief", brief(summary));
            }
        } else {
            String reason = "REJECTED".equals(data.get("status")) ? nonblank(data.get("reason")) : null;
            if (reason == null) reason = nonblank(data.get("reasonCode"));
            if (reason == null) reason = nonblank(errorCode);
            metadata.put("subagent_error", brief(reason == null ? "Child delegation did not complete successfully." : reason));
        }
        String model = nonblank(data.get("modelName"));
        if (model != null) metadata.put("subagent_model_name", model);
        if (data.get("usage") instanceof Map<?, ?> usage) {
            Long input = tokenCount(usage.get("inputTokens"));
            Long output = tokenCount(usage.get("outputTokens"));
            if (input != null && output != null && input <= MAX_SAFE_INTEGER - output) {
                metadata.put("subagent_token_usage", Map.of(
                        "input_tokens", input, "output_tokens", output, "total_tokens", input + output));
            }
        }
        String stop = switch (String.valueOf(data.get("reasonCode"))) {
            case "TOKEN_CAPPED", "token_capped" -> "token_capped";
            case "TURN_CAPPED", "turn_capped" -> "turn_capped";
            case "LOOP_CAPPED", "loop_capped" -> "loop_capped";
            default -> null;
        };
        if (stop != null) metadata.put("subagent_stop_reason", stop);
        return Map.copyOf(metadata);
    }

    private static String terminalStatus(String toolStatus, Boolean successful, Object childStatus) {
        if ("CANCELLED".equals(toolStatus)) return "cancelled";
        if ("TIMEOUT".equals(toolStatus)) return "timed_out";
        if ("DENIED".equals(toolStatus) || successful == null) return "failed";
        return switch (String.valueOf(childStatus)) {
            case "COMPLETED" -> Boolean.TRUE.equals(successful) && "COMPLETED".equals(toolStatus)
                    ? "completed" : "failed";
            case "CANCELLED" -> "cancelled";
            case "TIMEOUT" -> "timed_out";
            default -> "failed";
        };
    }

    private static void copyText(Map<String, Object> source, String from, Map<String, Object> target, String to) {
        if (source.get(from) instanceof String text) target.put(to, text);
    }

    private static String nonblank(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static Long tokenCount(Object value) {
        try {
            long count;
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                count = ((Number) value).longValue();
            } else if (value instanceof BigInteger integer) {
                count = integer.longValueExact();
            } else if (value instanceof BigDecimal decimal) {
                count = decimal.longValueExact();
            } else {
                return null;
            }
            return count >= 0 && count <= MAX_SAFE_INTEGER ? count : null;
        } catch (ArithmeticException invalid) {
            return null;
        }
    }

    private static String brief(String text) {
        int length = text.codePointCount(0, text.length());
        if (length <= 2_000) return text;
        int head = (2_000 - 5) * 2 / 3;
        int tail = 2_000 - 5 - head;
        return text.substring(0, text.offsetByCodePoints(0, head)) + "\n...\n"
                + text.substring(text.offsetByCodePoints(text.length(), -tail));
    }
}

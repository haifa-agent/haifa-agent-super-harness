package io.haifa.agent.superharness.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.runtime.api.ToolCallView;
import io.haifa.agent.runtime.api.ToolDataView;
import io.haifa.agent.runtime.api.ToolResultView;
import io.haifa.agent.runtime.api.display.BoundedText;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DeerFlowDelegationProjectionTest {
    @Test
    void wiresTaskArgumentsAndMetadataWithoutChangingSdkContentOrIdentity() {
        Map<String, Object> args = Map.of("agent", "researcher", "objective", "Find evidence", "context", "brief");
        var call = call("task", args, result(Map.of(
                "status", "COMPLETED", "summary", "child summary", "truncated", false)));
        var request = DeerFlowToolTranscriptProjection.requestMessage(call);
        var tool = (Map<?, ?>) ((List<?>) request.get("tool_calls")).getFirst();
        var response = DeerFlowToolTranscriptProjection.resultMessage(call).orElseThrow();
        var metadata = (Map<?, ?>) response.get("additional_kwargs");
        assertEquals(Map.of("subagent_type", "researcher", "prompt", "Find evidence"), tool.get("args"));
        assertEquals(call.id().value(), tool.get("id"));
        assertEquals(tool.get("id"), response.get("tool_call_id"));
        assertEquals("SDK readable result", response.get("content"));
        assertEquals("completed", metadata.get("subagent_status"));
        assertEquals("child summary", metadata.get("subagent_result_brief"));
        assertFalse(metadata.containsKey("subagent_result_sha256"));
        assertEquals(args, call.arguments().values());
    }

    @Test
    void ordinaryToolsKeepTheirExistingWireShape() {
        Map<String, Object> args = Map.of("path", "/workspace/readme.md");
        var call = call("read_file", args, result(Map.of("status", "COMPLETED")));
        var request = DeerFlowToolTranscriptProjection.requestMessage(call);
        var tool = (Map<?, ?>) ((List<?>) request.get("tool_calls")).getFirst();
        var response = DeerFlowToolTranscriptProjection.resultMessage(call).orElseThrow();
        assertEquals(args, tool.get("args"));
        assertEquals("SDK readable result", response.get("content"));
        assertFalse(((Map<?, ?>) response.get("additional_kwargs")).containsKey("subagent_status"));
    }

    @Test
    void toolCompletionCannotPromoteFailedOrUnknownDelegationToSuccess() {
        for (String childStatus : List.of("FAILED", "REJECTED", "INTERRUPTED", "RUNNING", "future-status")) {
            assertEquals("failed", metadata("COMPLETED", true, Map.of("status", childStatus)).get("subagent_status"));
        }
        assertEquals("failed", metadata("COMPLETED", false, Map.of("status", "COMPLETED")).get("subagent_status"));
        assertEquals("failed", metadata("COMPLETED", true, Map.of()).get("subagent_status"));
        assertTrue(metadata("RUNNING", null, Map.of()).isEmpty());
    }

    @Test
    void preChildRejectionAndNotStartedCancellationNeedNoInventedChildFacts() {
        var rejected = metadata("FAILED", false, Map.of("status", "REJECTED", "reason", "Agent is not allowed."));
        assertEquals("failed", rejected.get("subagent_status"));
        assertEquals("Agent is not allowed.", rejected.get("subagent_error"));
        assertFalse(rejected.containsKey("subagent_token_usage"));
        assertFalse(rejected.containsKey("subagent_model_name"));
        var cancelled = metadata("CANCELLED", null, Map.of());
        assertEquals("cancelled", cancelled.get("subagent_status"));
        assertEquals("timed_out", metadata("TIMEOUT", null, Map.of()).get("subagent_status"));
        assertEquals("timed_out", metadata("FAILED", false, Map.of("status", "TIMEOUT")).get("subagent_status"));
    }

    @Test
    void forwardsOnlyAnAvailableFullOutputDigestAndBoundsUnicodeDisplay() {
        String digest = "ab".repeat(32);
        var projected = metadata("COMPLETED", true, Map.of("status", "COMPLETED", "outputPreview",
                "😀".repeat(2_001), "outputTruncated", true, "outputSha256", digest));
        String preview = (String) projected.get("subagent_result_brief");
        assertEquals(2_000, preview.codePointCount(0, preview.length()));
        assertTrue(preview.contains("\n...\n"));
        assertEquals(digest, projected.get("subagent_result_sha256"));
        var malformed = metadata("COMPLETED", true, Map.of("status", "COMPLETED", "outputPreview", "ok",
                "outputTruncated", false, "outputSha256", "not-a-digest"));
        assertFalse(malformed.containsKey("subagent_result_sha256"));
        var unavailable = DeerFlowDelegationProjection.result("COMPLETED", true,
                Map.of("status", "COMPLETED", "summary", "bounded", "truncated", false), true, null);
        assertFalse(unavailable.containsKey("subagent_result_sha256"));
        assertFalse(unavailable.containsKey("subagent_result_brief"));
    }

    @Test
    void usageRequiresCompleteNonnegativeSafeIntegerCounts() {
        var known = metadata("COMPLETED", true, Map.of("status", "COMPLETED", "usage",
                Map.of("inputTokens", 2, "outputTokens", 3L)));
        assertEquals(Map.of("input_tokens", 2L, "output_tokens", 3L, "total_tokens", 5L),
                known.get("subagent_token_usage"));
        for (Object invalid : List.of(-1, 1.5, Double.NaN, Double.POSITIVE_INFINITY, "3",
                BigInteger.ONE.shiftLeft(80), new BigDecimal("2.1"), Long.MAX_VALUE)) {
            var data = metadata("COMPLETED", true, Map.of("status", "COMPLETED", "usage",
                    Map.of("inputTokens", invalid, "outputTokens", 0)));
            assertFalse(data.containsKey("subagent_token_usage"));
        }
        var overflow = metadata("COMPLETED", true, Map.of("status", "COMPLETED", "usage",
                Map.of("inputTokens", 9_007_199_254_740_991L, "outputTokens", 1)));
        assertFalse(overflow.containsKey("subagent_token_usage"));
        assertFalse(metadata("COMPLETED", true, Map.of("status", "COMPLETED", "usage",
                Map.of("inputTokens", 0))).containsKey("subagent_token_usage"));
    }

    @Test
    void optionalFactsAreNotInventedAndReturnedMapsAreImmutable() {
        var unknown = metadata("COMPLETED", true, Map.of("status", "COMPLETED", "reasonCode", "unknown"));
        assertFalse(unknown.containsKey("subagent_stop_reason"));
        assertFalse(unknown.containsKey("subagent_model_name"));
        assertEquals("turn_capped", metadata("COMPLETED", true,
                Map.of("status", "COMPLETED", "reasonCode", "TURN_CAPPED")).get("subagent_stop_reason"));
        assertThrows(UnsupportedOperationException.class, () -> unknown.put("subagent_status", "bad"));
        assertTrue(DeerFlowDelegationProjection.arguments(Map.of("objective", 42)).isEmpty());
    }

    private static Map<String, Object> metadata(String status, Boolean successful, Map<String, Object> data) {
        return DeerFlowDelegationProjection.result(status, successful, data, false, "tool_rejected");
    }

    private static ToolResultView result(Map<String, Object> data) {
        return new ToolResultView(true, BoundedText.of("SDK readable result", 8_192, 100),
                new ToolDataView(data, false), List.of(), List.of(), false);
    }

    private static ToolCallView call(String name, Map<String, Object> arguments, ToolResultView result) {
        Instant at = Instant.parse("2026-10-03T00:00:00Z");
        return new ToolCallView(new ToolCallId("runtime-tool-1"), new AgentRunId("parent-run"), name, "1.0.0",
                new ToolDataView(arguments, false), ToolCallStatus.COMPLETED, Optional.of(result), Optional.empty(),
                at, Optional.of(at), Optional.of(at));
    }
}

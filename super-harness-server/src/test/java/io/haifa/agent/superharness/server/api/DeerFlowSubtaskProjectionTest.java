package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.runtime.api.RunMessageView;
import io.haifa.agent.runtime.api.ToolCallView;
import io.haifa.agent.runtime.api.ToolDataView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DeerFlowSubtaskProjectionTest {
    @Test
    void liveAndReplayShareExactStepIdentityTextAndToolArguments() {
        var source = message("ASSISTANT", "Inspect the file", false, List.of(call(Map.of("path", "a.txt"), false)), 12, 3);
        var replay = content(DeerFlowSubtaskProjection.event("task-id", source));
        var live = DeerFlowSubtaskProjection.running("task-id", source, Map.of("model_name", "actual-model"));
        var wire = (Map<?, ?>) live.get("message");
        assertThat(replay).containsEntry("kind", "ai").containsEntry("message_index", 3L).containsEntry("task_id", "task-id");
        assertThat(live).containsEntry("task_id", "task-id").containsEntry("message_index", 3L);
        assertThat(wire.get("content")).isEqualTo(replay.get("text"));
        assertThat(wire.get("tool_calls")).isEqualTo(replay.get("tool_calls"));
        assertThat(live).containsEntry("model_name", "actual-model");
    }

    @Test
    void clipsUnicodeTextAndSerializedArgumentsWithoutDroppingSourceTruncation() {
        String text = "😀".repeat(25_000);
        var source = message("ASSISTANT", text, false, List.of(call(Map.of("value", "x".repeat(20_000)), false)), 3, 1);
        var replay = content(DeerFlowSubtaskProjection.event("task", source));
        String clipped = (String) replay.get("text");
        assertThat(clipped.codePointCount(0, clipped.length())).isEqualTo(8_192);
        assertThat(clipped).doesNotEndWith("\uD83D");
        assertThat(replay).containsEntry("truncated", true);
        var projected = (Map<?, ?>) ((List<?>) replay.get("tool_calls")).getFirst();
        assertThat(projected.get("args")).isInstanceOf(String.class);
        assertThat(((String) projected.get("args")).length()).isEqualTo(8_192);
        assertThat(projected.get("args_truncated")).isEqualTo(true);
        var boundedAtSdk = content(DeerFlowSubtaskProjection.event("task",
                message("ASSISTANT", "bounded", true, List.of(call(Map.of("preview", "bounded"), true)), 4, 2)));
        assertThat(boundedAtSdk).containsEntry("truncated", true);
        assertThat(((Map<?, ?>) ((List<?>) boundedAtSdk.get("tool_calls")).getFirst()).get("args_truncated")).isEqualTo(true);
    }

    @Test
    void toolStepsUseExactAvailableBindingsAndPreserveRedactedIdentity() {
        var source = message("TOOL", "actual Tool result", false, List.of(call(Map.of(), false)), 8, 2);
        var step = content(DeerFlowSubtaskProjection.event("task", source));
        assertThat(step).containsEntry("kind", "tool").containsEntry("tool_name", "read_file");
        assertThat(step).doesNotContainKey("tool_calls");
        var redactedSource = message("TOOL", "[REDACTED]", false, List.of(), 9, 3);
        var redacted = content(DeerFlowSubtaskProjection.event("task", redactedSource));
        assertThat(redacted).containsEntry("kind", "tool").containsEntry("text", "[REDACTED]")
                .containsEntry("message_index", 3L).doesNotContainKeys("tool_name", "tool_calls");
        var live = (Map<?, ?>) DeerFlowSubtaskProjection.running("task", redactedSource, Map.of()).get("message");
        assertThat(live.get("content")).isEqualTo(redacted.get("text"));
        assertThat(live.containsKey("name")).isFalse();
        assertThat(live.containsKey("tool_calls")).isFalse();
        assertThatThrownBy(() -> DeerFlowSubtaskProjection.checkNumber(DeerFlowSubtaskProjection.MAX_SAFE_INTEGER + 1, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transportParametersRejectNegativeFractionalOverflowAndOutOfRangeLimits() {
        for (String invalid : List.of("-1", "1.5", "1e2", " ", "9223372036854775808", "9007199254740992")) {
            assertThatThrownBy(() -> DeerFlowConversationService.subtaskQueryNumber(invalid, 0, DeerFlowSubtaskProjection.MAX_SAFE_INTEGER))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(DeerFlowConversationService.subtaskQueryNumber("9007199254740991", 0, DeerFlowSubtaskProjection.MAX_SAFE_INTEGER))
                .isEqualTo(DeerFlowSubtaskProjection.MAX_SAFE_INTEGER);
        for (String invalid : List.of("0", "501")) {
            assertThatThrownBy(() -> DeerFlowConversationService.subtaskQueryNumber(invalid, 1, 500))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void usageSumKeepsValuesAndReturnsUnknownOnLongOverflow() {
        assertThat(DeerFlowSubtaskStream.sumUsage(0, 17)).hasValue(17);
        assertThat(DeerFlowSubtaskStream.sumUsage(DeerFlowSubtaskProjection.MAX_SAFE_INTEGER, 1))
                .hasValue(DeerFlowSubtaskProjection.MAX_SAFE_INTEGER + 1);
        assertThat(DeerFlowSubtaskStream.sumUsage(Long.MAX_VALUE - 1, 1)).hasValue(Long.MAX_VALUE);
        assertThat(DeerFlowSubtaskStream.sumUsage(Long.MAX_VALUE, 1)).isEmpty();
        assertThat(DeerFlowSubtaskStream.sumUsage(1, Long.MAX_VALUE)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> content(Map<String, Object> event) {
        return (Map<String, Object>) event.get("content");
    }

    private static RunMessageView message(String role, String text, boolean truncated, List<ToolCallView> calls, long sequence, long index) {
        return new RunMessageView("message-" + sequence, sequence, index, role, text, truncated, calls,
                calls.isEmpty() ? Map.of() : Map.of("read-id", "provider-read-id"), 0);
    }

    private static ToolCallView call(Map<String, Object> arguments, boolean truncated) {
        return new ToolCallView(new ToolCallId("read-id"), new AgentRunId("child"), "read_file", "1.0.0",
                new ToolDataView(arguments, truncated), ToolCallStatus.COMPLETED, Optional.empty(), Optional.empty(),
                Instant.EPOCH, Optional.empty(), Optional.empty());
    }
}

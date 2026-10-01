package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.runtime.api.ToolCallView;
import io.haifa.agent.runtime.api.ToolDataView;
import io.haifa.agent.runtime.api.ToolResultView;
import io.haifa.agent.runtime.api.display.BoundedText;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * W05-task-8 B: the frozen clarification card projection and the stop boundary derived from the
 * durable SDK ToolCall view.
 */
class ClarificationTranscriptProjectionTest {
    private static final String PAYLOAD = """
            {"version":1,"kind":"human_input_request","source":"ask_clarification",
            "request_id":"clarification:call-ask","tool_call_id":"call-ask",
            "clarification_type":"missing_info","question":"Which env?","input_mode":"free_text"}
            """;

    @Test
    void projectsTheFrozenHumanInputCardForACompletedClarificationCall() {
        ToolCallView ask = completed("ask_clarification", Map.of(
                "content", "\u2753 Which env?", "humanInput", PAYLOAD, "error", ""));

        List<Map<String, Object>> messages = DeerFlowToolTranscriptProjection.messages(List.of(ask));
        assertThat(messages).hasSize(2);

        Map<String, Object> card = messages.get(1);
        assertThat(card)
                .containsEntry("id", "clarification:call-ask")
                .containsEntry("type", "tool")
                .containsEntry("name", "ask_clarification")
                .containsEntry("content", "\u2753 Which env?")
                .containsEntry("tool_call_id", "call-ask")
                .containsEntry("run_id", "run-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> artifact = (Map<String, Object>) card.get("artifact");
        assertThat(artifact).containsKey("human_input");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) artifact.get("human_input");
        assertThat(payload.get("source")).isEqualTo("ask_clarification");
        assertThat(payload.get("request_id")).isEqualTo("clarification:call-ask");
    }

    @Test
    void boundaryStopsProjectionAtTheClarificationResult() {
        ToolCallView read = completed("read_file", Map.of("path", "/mnt/user-data/workspace/a.txt", "sha256", "x"));
        ToolCallView ask = completed("ask_clarification", Map.of(
                "content", "question", "humanInput", PAYLOAD, "error", ""));
        ToolCallView write = completed("write_file", Map.of(
                "path", "/mnt/user-data/outputs/after.txt", "artifactId", "artifact-1"));

        List<ToolCallView> calls = List.of(read, ask, write);
        assertThat(DeerFlowToolTranscriptProjection.hasClarificationBoundary(calls)).isTrue();
        assertThat(DeerFlowToolTranscriptProjection.clarificationBoundaryIndex(calls)).isEqualTo(1);
        assertThat(DeerFlowToolTranscriptProjection.upToClarificationBoundary(calls))
                .containsExactly(read, ask);

        // No tool call after the boundary contributes messages or artifact paths: the first AI
        // tool-call request carries the name only inside tool_calls, so the projected result names
        // (read_file, then the clarification card) are the boundary-visible ones.
        List<Map<String, Object>> messages = DeerFlowToolTranscriptProjection.messages(calls);
        assertThat(messages).hasSize(4);
        assertThat(messages.get(1).get("name")).isEqualTo("read_file");
        assertThat(messages.get(3).get("name")).isEqualTo("ask_clarification");
        assertThat(messages.stream().map(message -> String.valueOf(message.get("id"))).toList())
                .noneMatch(id -> id.contains("write_file"));
        assertThat(DeerFlowToolTranscriptProjection.artifactPaths(calls)).isEmpty();
    }

    @Test
    void onlyACompletedClarificationCallDefinesTheBoundary() {
        ToolCallView failed = new ToolCallView(
                new ToolCallId("call-ask"), new AgentRunId("run-1"), "ask_clarification", "1.0.0",
                ToolDataView.empty(), ToolCallStatus.FAILED, Optional.empty(), Optional.empty(),
                Instant.now(), Optional.empty(), Optional.empty());
        assertThat(DeerFlowToolTranscriptProjection.hasClarificationBoundary(List.of(failed))).isFalse();
        assertThat(DeerFlowToolTranscriptProjection.upToClarificationBoundary(List.of(failed)))
                .containsExactly(failed);
    }

    private static ToolCallView completed(String tool, Map<String, Object> values) {
        ToolResultView result = new ToolResultView(
                true, BoundedText.of(String.valueOf(values.get("content")), 100, 100),
                new ToolDataView(values, false), List.of(), List.of(), false);
        return new ToolCallView(
                new ToolCallId("call-" + ("ask_clarification".equals(tool) ? "ask" : tool)),
                new AgentRunId("run-1"), tool, "1.0.0",
                ToolDataView.empty(), ToolCallStatus.COMPLETED, Optional.of(result), Optional.empty(),
                Instant.now(), Optional.empty(), Optional.empty());
    }
}

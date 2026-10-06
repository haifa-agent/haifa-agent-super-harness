package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.superharness.server.testsupport.ControlledProvider;
import io.haifa.agent.superharness.server.testsupport.DelegationHttpFixture;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * W05: a single Run with several model rounds must project its Tool messages per round, so a
 * later round's Tool call can never precede an earlier round's Tool result in thread state/history.
 */
class StateMessageOrderHttpTest {
    private Path dataRoot;
    private Path evidence;
    private GatewayFixture.HttpEvidence http;

    @BeforeEach
    void prepare() throws Exception {
        dataRoot = GatewayFixture.dataDirectory();
        evidence = GatewayFixture.evidenceDirectory();
        http = new GatewayFixture.HttpEvidence(evidence, Duration.ofSeconds(40));
    }

    @AfterEach
    void close() throws InterruptedException {
        if (http != null) {
            http.close();
        }
    }

    @Test
    void multiRoundToolCallsKeepPerRoundOrderInThreadState() throws Exception {
        Files.createDirectories(evidence);
        String threadId = "state-message-order";
        Map<String, String> environment = Map.of(
                "HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W05_ORDER_KEY",
                "W05_ORDER_KEY", java.util.Base64.getEncoder().encodeToString(new byte[32]));

        AtomicInteger leadCalls = new AtomicInteger();
        try (var provider = new ControlledProvider(evidence, true, (request, call) -> {
            if (DelegationHttpFixture.isChild(request)) {
                return ControlledProvider.stop("child-" + call, "w08-model", "CHILD-RESEARCH-RESULT", 11, 13);
            }
            int current = leadCalls.incrementAndGet();
            if (current == 1) {
                return ControlledProvider.call("lead-" + call, "w08-model", "tc-plan-1",
                        "write_todos", Map.of("todos", List.of(
                                Map.of("content", "Coordinate research", "status", "in_progress"))));
            }
            if (current == 2) {
                return ControlledProvider.call("lead-" + call, "w08-model", "tc-task-1",
                        "task", Map.of("agent", "researcher", "objective", "Investigate facts"), 2, 3);
            }
            if (current == 3) {
                return ControlledProvider.call("lead-" + call, "w08-model", "tc-plan-2",
                        "write_todos", Map.of("todos", List.of(
                                Map.of("content", "Coordinate research", "status", "completed"))));
            }
            return ControlledProvider.stop("lead-" + call, "w08-model", "Plan and subtask complete.", 5, 7);
        })) {
            try (var api = GatewayFixture.production(dataRoot, 0, environment, evidence)) {
                URI base = api.base;
                DelegationHttpFixture.configure(http, base, provider.port());
                DelegationHttpFixture.seed(http, base, "researcher", 30, 3, List.of("list_uploaded_files"));

                assertThat(http.request(base, "POST", "/api/threads", "{\"thread_id\":\"" + threadId + "\"}")
                        .statusCode()).isEqualTo(200);

                String runBody = """
                        {"assistant_id":"lead_agent","input":{"messages":[{"type":"human","content":"run ultra plan"}]},
                        "stream_mode":["messages-tuple","updates","custom"],
                        "context":{"model_name":"w08","mode":"ultra","reasoning_effort":"minimal",
                        "thinking_enabled":false,"is_plan_mode":true,"subagent_enabled":true},
                        "on_disconnect":"continue","multitask_strategy":"reject"}
                        """;
                HttpResponse<String> response = http.request(
                        base, "POST", "/api/threads/" + threadId + "/runs/stream", runBody, "state-order.sse");
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                assertThat(response.body()).contains("event: end");
                assertThat(leadCalls.get()).isEqualTo(4);

                JsonNode state = http.get(base, "/api/threads/" + threadId + "/state", "state-order-state.json");
                List<JsonNode> messages = DelegationHttpFixture.messages(state);

                List<String> types = new ArrayList<>();
                for (JsonNode message : messages) {
                    types.add(message.path("type").asText());
                }
                assertThat(types)
                        .as("each model round's Tool result must directly follow its Tool call")
                        .containsExactly("human", "ai", "tool", "ai", "tool", "ai", "tool", "ai");

                List<String> callNames = new ArrayList<>();
                for (JsonNode message : messages) {
                    if (!message.path("tool_calls").isEmpty()) {
                        callNames.add(message.path("tool_calls").get(0).path("name").asText());
                    }
                }
                assertThat(callNames).containsExactly("write_todos", "task", "write_todos");

                for (JsonNode message : messages) {
                    if (!"tool".equals(message.path("type").asText())) {
                        continue;
                    }
                    String toolCallId = message.path("tool_call_id").asText();
                    assertThat(toolCallId).isNotBlank();
                    assertThat(message.path("name").asText()).isNotBlank();
                    JsonNode owner = nearestPrecedingCall(messages, message);
                    assertThat(owner).as("Tool result must belong to the preceding AI call").isNotNull();
                    assertThat(owner.path("tool_calls").get(0).path("id").asText()).isEqualTo(toolCallId);
                }

                JsonNode taskResult = messages.stream()
                        .filter(message -> "tool".equals(message.path("type").asText()))
                        .filter(message -> "task".equals(message.path("name").asText()))
                        .findFirst().orElseThrow();
                assertThat(taskResult.path("additional_kwargs").path("subagent_status").asText())
                        .isEqualTo("completed");
            }
        }
    }

    private static JsonNode nearestPrecedingCall(List<JsonNode> messages, JsonNode toolMessage) {
        int index = messages.indexOf(toolMessage);
        for (int cursor = index - 1; cursor >= 0; cursor--) {
            if (!messages.get(cursor).path("tool_calls").isEmpty()) {
                return messages.get(cursor);
            }
        }
        return null;
    }
}

package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * W08: a single Ultra model round that emits two parallel {@code task} calls. The test records the
 * current thread-state shape that makes the frozen frontend open two {@code assistant:subagent}
 * groups and loop, so the two candidate projections can be diffed against one deterministic wire
 * fact. This is a characterization test: it pins today's output, not the desired output.
 */
class ParallelSubtaskStateHttpTest {
    private static final String OBJECTIVE_A = "PARALLEL-OBJECTIVE-ALPHA";
    private static final String OBJECTIVE_B = "PARALLEL-OBJECTIVE-BETA";

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
    void oneUltraRoundWithTwoParallelTasksRecordsTheThreadStateShape() throws Exception {
        Files.createDirectories(evidence);
        String threadId = "parallel-subtask-state";
        Map<String, String> environment = Map.of(
                "HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W08_PARALLEL_KEY",
                "W08_PARALLEL_KEY", java.util.Base64.getEncoder().encodeToString(new byte[32]));

        try (var provider = new ControlledProvider(evidence, true, (request, call) -> {
            if (DelegationHttpFixture.isChild(request)) {
                boolean alpha = request.path("messages").toString().contains(OBJECTIVE_A);
                return ControlledProvider.stop("child-" + call, "w08-model",
                        alpha ? "ALPHA-RESULT" : "BETA-RESULT", 11, 13);
            }
            boolean followedUp = request.path("messages").findValuesAsText("role").contains("tool");
            if (!followedUp) {
                return twoParallelTasks(call);
            }
            return ControlledProvider.stop("lead-" + call, "w08-model", "Both parallel subtasks complete.", 5, 7);
        })) {
            try (var api = GatewayFixture.production(dataRoot, 0, environment, evidence)) {
                URI base = api.base;
                DelegationHttpFixture.configure(http, base, provider.port());
                DelegationHttpFixture.seed(http, base, "researcher", 30, 3, List.of("list_uploaded_files"));

                assertThat(http.request(base, "POST", "/api/threads", "{\"thread_id\":\"" + threadId + "\"}")
                        .statusCode()).isEqualTo(200);

                String runBody = """
                        {"assistant_id":"lead_agent","input":{"messages":[{"type":"human","content":"run parallel research"}]},
                        "stream_mode":["messages-tuple","updates","custom"],
                        "context":{"model_name":"w08","mode":"ultra","reasoning_effort":"minimal",
                        "thinking_enabled":false,"is_plan_mode":false,"subagent_enabled":true},
                        "on_disconnect":"continue","multitask_strategy":"reject"}
                        """;
                HttpResponse<String> response = http.request(
                        base, "POST", "/api/threads/" + threadId + "/runs/stream", runBody,
                        "parallel-subtask.sse");
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                assertThat(response.body()).contains("event: end");

                JsonNode state = http.get(base, "/api/threads/" + threadId + "/state", "parallel-subtask-state.json");
                List<JsonNode> messages = DelegationHttpFixture.messages(state);

                List<String> types = new ArrayList<>();
                for (JsonNode message : messages) {
                    types.add(message.path("type").asText());
                }
                assertThat(types)
                        .as("current shape: one AI message per Tool call, all calls before all results")
                        .containsExactly("human", "ai", "ai", "tool", "tool", "ai");

                List<String> taskCallIds = new ArrayList<>();
                for (JsonNode message : messages) {
                    for (JsonNode call : message.path("tool_calls")) {
                        if ("task".equals(call.path("name").asText())) {
                            taskCallIds.add(call.path("id").asText());
                        }
                    }
                }
                assertThat(taskCallIds).hasSize(2).doesNotHaveDuplicates();

                for (JsonNode message : messages) {
                    if (!"task".equals(message.path("name").asText())) {
                        continue;
                    }
                    assertThat(message.path("additional_kwargs").path("subagent_status").asText())
                            .isEqualTo("completed");
                }
                assertThat(messages.stream()
                        .filter(message -> "task".equals(message.path("name").asText())).count())
                        .isEqualTo(2);
            }
        }
    }

    private static ObjectNode twoParallelTasks(int call) {
        ObjectNode response = ControlledProvider.call("lead-" + call, "w08-model", "tc-task-alpha", "task",
                Map.of("agent", "researcher", "objective", OBJECTIVE_A), 2, 3);
        var calls = ((ObjectNode) response.path("choices").get(0).path("message")).withArray("tool_calls");
        ObjectNode second = ((ObjectNode) calls.get(0)).deepCopy();
        second.put("id", "tc-task-beta");
        second.withObject("function").put("arguments",
                GatewayFixture.JSON.valueToTree(Map.of("agent", "researcher", "objective", OBJECTIVE_B)).toString());
        calls.add(second);
        return response;
    }
}

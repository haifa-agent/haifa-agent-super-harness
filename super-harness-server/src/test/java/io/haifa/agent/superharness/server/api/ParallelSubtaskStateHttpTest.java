package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.runtime.api.ToolCallView;
import io.haifa.agent.runtime.api.ToolDataView;
import io.haifa.agent.superharness.server.testsupport.ControlledProvider;
import io.haifa.agent.superharness.server.testsupport.DelegationHttpFixture;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import io.haifa.agent.superharness.server.testsupport.SseEvents;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * W08: a single Ultra model round that emits two parallel {@code task} calls must project one frozen
 * LangGraph assistant message carrying both calls. The previous per-call projection opened two
 * frontend {@code assistant:subagent} groups and looped React (#185).
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
    void oneUltraRoundWithTwoParallelTasksProjectsOneRoundMessage() throws Exception {
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
                        .as("frozen shape: one AI message for the whole round, then each result")
                        .containsExactly("human", "ai", "tool", "tool", "ai");

                JsonNode roundRequest = messages.get(1);
                assertThat(roundRequest.path("type").asText()).isEqualTo("ai");
                assertThat(roundRequest.path("tool_calls")).as("both parallel calls share one message").hasSize(2);
                assertThat(roundRequest.path("tool_calls"))
                        .allSatisfy(call -> assertThat(call.path("name").asText()).isEqualTo("task"));
                String firstCallId = roundRequest.path("tool_calls").get(0).path("id").asText();
                assertThat(roundRequest.path("id").asText())
                        .as("the round message id is derived from its first call")
                        .isEqualTo("tool-call-" + firstCallId + "-request");
                JsonNode roundKwargs = roundRequest.path("additional_kwargs");
                assertThat(roundKwargs).hasSize(2);
                assertThat(roundKwargs.path("deerflow_seq").asLong()).isEqualTo(2);
                assertThat(roundKwargs.path("usage_metadata").path("input_tokens").asLong()).isEqualTo(2);
                assertThat(roundKwargs.path("usage_metadata").path("output_tokens").asLong()).isEqualTo(3);
                assertThat(roundKwargs.path("usage_metadata").path("total_tokens").asLong()).isEqualTo(5);

                List<String> taskCallIds = new ArrayList<>();
                for (JsonNode message : messages) {
                    for (JsonNode call : message.path("tool_calls")) {
                        if ("task".equals(call.path("name").asText())) {
                            taskCallIds.add(call.path("id").asText());
                        }
                    }
                }
                assertThat(taskCallIds).hasSize(2).doesNotHaveDuplicates();
                assertThat(taskCallIds.get(0)).isEqualTo(firstCallId);

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

                assertLiveStreamHasOneRoundMessage(base, threadId, response, firstCallId);
            }
        }
    }

    @Test
    void parallelRoundThenSingleCallRoundKeepsPerRoundOrder() throws Exception {
        Files.createDirectories(evidence);
        String threadId = "parallel-mixed-rounds";
        Map<String, String> environment = Map.of(
                "HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W08_MIXED_KEY",
                "W08_MIXED_KEY", java.util.Base64.getEncoder().encodeToString(new byte[32]));

        AtomicInteger leadCalls = new AtomicInteger();
        try (var provider = new ControlledProvider(evidence, true, (request, call) -> {
            if (DelegationHttpFixture.isChild(request)) {
                boolean alpha = request.path("messages").toString().contains(OBJECTIVE_A);
                return ControlledProvider.stop("child-" + call, "w08-model",
                        alpha ? "ALPHA-RESULT" : "BETA-RESULT", 11, 13);
            }
            int current = leadCalls.incrementAndGet();
            if (current == 1) {
                return twoParallelTasks(call);
            }
            if (current == 2) {
                return ControlledProvider.call("lead-" + call, "w08-model", "tc-plan-2", "write_todos",
                        Map.of("todos", List.of(Map.of("content", "Coordinate research", "status", "in_progress"))));
            }
            return ControlledProvider.stop("lead-" + call, "w08-model", "Plan and subtasks complete.", 5, 7);
        })) {
            try (var api = GatewayFixture.production(dataRoot, 0, environment, evidence)) {
                URI base = api.base;
                DelegationHttpFixture.configure(http, base, provider.port());
                DelegationHttpFixture.seed(http, base, "researcher", 30, 3, List.of("list_uploaded_files"));

                assertThat(http.request(base, "POST", "/api/threads", "{\"thread_id\":\"" + threadId + "\"}")
                        .statusCode()).isEqualTo(200);

                String runBody = """
                        {"assistant_id":"lead_agent","input":{"messages":[{"type":"human","content":"run parallel research then plan"}]},
                        "stream_mode":["messages-tuple","updates","custom"],
                        "context":{"model_name":"w08","mode":"ultra","reasoning_effort":"minimal",
                        "thinking_enabled":false,"is_plan_mode":true,"subagent_enabled":true},
                        "on_disconnect":"continue","multitask_strategy":"reject"}
                        """;
                HttpResponse<String> response = http.request(
                        base, "POST", "/api/threads/" + threadId + "/runs/stream", runBody, "parallel-mixed.sse");
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                assertThat(response.body()).contains("event: end");
                assertThat(leadCalls.get()).as("two parallel tasks then one plan round then the answer").isEqualTo(3);

                JsonNode state = http.get(base, "/api/threads/" + threadId + "/state", "parallel-mixed-state.json");
                List<JsonNode> messages = DelegationHttpFixture.messages(state);

                List<String> types = new ArrayList<>();
                for (JsonNode message : messages) {
                    types.add(message.path("type").asText());
                }
                assertThat(types)
                        .as("each round's message and results stay before the next round")
                        .containsExactly("human", "ai", "tool", "tool", "ai", "tool", "ai");

                assertThat(messages.get(1).path("tool_calls")).hasSize(2);
                assertThat(messages.get(1).path("tool_calls"))
                        .allSatisfy(call -> assertThat(call.path("name").asText()).isEqualTo("task"));
                JsonNode planCall = messages.get(4).path("tool_calls");
                assertThat(planCall).hasSize(1);
                assertThat(planCall.get(0).path("name").asText()).isEqualTo("write_todos");
                assertThat(messages.get(5).path("name").asText()).isEqualTo("write_todos");
                assertThat(messages.get(6).path("tool_calls")).hasSize(0);
            }
        }
    }

    private void assertLiveStreamHasOneRoundMessage(
            URI base, String threadId, HttpResponse<String> response, String firstCallId) throws Exception {
        SseEvents events = SseEvents.read(response.body());
        List<JsonNode> taskCallMessages = new ArrayList<>();
        Set<String> taskCallMessageIds = new LinkedHashSet<>();
        int taskResults = 0;
        for (SseEvents.Event event : events.named("messages")) {
            JsonNode item = event.data().get(0);
            if ("ai".equals(item.path("type").asText()) && containsTaskCall(item)) {
                taskCallMessageIds.add(item.path("id").asText());
                taskCallMessages.add(item);
            }
            if ("tool".equals(item.path("type").asText()) && "task".equals(item.path("name").asText())) {
                taskResults++;
            }
        }
        assertThat(taskCallMessageIds)
                .as("a parallel round must not open two different AI message ids")
                .hasSize(1);
        assertThat(taskCallMessageIds).containsExactly("tool-call-" + firstCallId + "-request");
        assertThat(taskCallMessages.getLast().path("tool_calls"))
                .as("the last occurrence of the round message carries both calls")
                .hasSize(2);
        assertThat(taskResults).as("each task result frame is written once").isEqualTo(2);

        String cursor = response.body().lines()
                .map(String::strip)
                .filter(line -> line.startsWith("id:"))
                .map(line -> line.substring(3).strip())
                .filter(value -> !value.isEmpty())
                .findFirst()
                .orElseThrow();
        String location = response.headers().firstValue("content-location").orElseThrow();
        HttpResponse<String> rejoined = reconnect(base, location, cursor);
        Files.writeString(
                evidence.resolve("parallel-subtask-reconnect.sse"), rejoined.body(), StandardCharsets.UTF_8);
        assertThat(rejoined.statusCode()).as(rejoined.body()).isEqualTo(200);
        // The projector keeps a short replay window, so a late observer joining before the tool frames
        // gets the frozen gap and follows its durable-state recovery exactly like the real client.
        SseEvents joined = SseEvents.read(rejoined.body());
        if (!joined.named("gap").isEmpty()) {
            JsonNode gap = joined.named("gap").getFirst().data();
            assertThat(gap.path("code").asText()).isEqualTo("stream_replay_gap");
            assertThat(gap.path("recovery").asText()).isEqualTo("reload_durable_state");
            String latest = gap.path("latest_available_event_id").asText();
            assertThat(latest).isNotBlank();
            rejoined = reconnect(base, location, latest);
            Files.writeString(
                    evidence.resolve("parallel-subtask-recovered.sse"), rejoined.body(), StandardCharsets.UTF_8);
            assertThat(rejoined.statusCode()).as(rejoined.body()).isEqualTo(200);
        }
        assertThat(rejoined.body()).contains("event: end");

        // Whatever the join re-emits must be a consistent suffix of the first stream, never a second
        // conflicting round message. The durable state the frozen client reloads is identical.
        List<JsonNode> firstMessages = messagePayloads(response.body());
        List<JsonNode> rejoinedMessages = messagePayloads(rejoined.body());
        assertThat(firstMessages.subList(firstMessages.size() - rejoinedMessages.size(), firstMessages.size()))
                .as("a mid-Run reconnect replays only frames consistent with the first stream")
                .isEqualTo(rejoinedMessages);

        JsonNode joinedState =
                http.get(base, "/api/threads/" + threadId + "/state", "parallel-subtask-joined-state.json");
        List<JsonNode> joinedMessages = DelegationHttpFixture.messages(joinedState);
        assertThat(joinedMessages).hasSize(5);
        assertThat(joinedMessages.get(1).path("id").asText())
                .isEqualTo("tool-call-" + firstCallId + "-request");
        assertThat(joinedMessages.get(1).path("tool_calls")).hasSize(2);
        assertThat(joinedMessages.stream()
                .filter(message -> "task".equals(message.path("name").asText())).count())
                .isEqualTo(2);
    }

    private HttpResponse<String> reconnect(URI base, String location, String cursor) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(location + "/stream"))
                .timeout(Duration.ofSeconds(40)).header("Last-Event-ID", cursor).GET().build();
        return http.client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aMultiCallRoundProjectsOneMessageWhileASingleCallRoundStaysByteIdentical() {
        ToolCallView first = roundCall("call-a");
        ToolCallView second = roundCall("call-b");

        Map<String, Object> singleCallRound = DeerFlowToolTranscriptProjection.requestMessage(List.of(first));
        assertThat(singleCallRound)
                .as("a single-call round is byte-for-byte the previous per-call request")
                .isEqualTo(DeerFlowToolTranscriptProjection.requestMessage(first));

        Map<String, Object> multiCallRound = DeerFlowToolTranscriptProjection.requestMessage(List.of(first, second));
        assertThat(multiCallRound.get("id")).isEqualTo("tool-call-call-a-request");
        assertThat((List<?>) multiCallRound.get("tool_calls")).hasSize(2);
        assertThat((Map<?, ?>) multiCallRound.get("additional_kwargs"))
                .as("a multi-call round carries no per-call haifa_tool_status")
                .isEmpty();

        List<Map<String, Object>> projected =
                DeerFlowToolTranscriptProjection.messages(List.of(List.of(first, second)));
        assertThat(projected.stream().filter(message -> "ai".equals(message.get("type"))).count())
                .as("one AI request message per round even with two calls")
                .isEqualTo(1);
    }

    private static ToolCallView roundCall(String id) {
        return new ToolCallView(new ToolCallId(id), new AgentRunId("round-run"), "task", "1.0.0",
                ToolDataView.empty(), ToolCallStatus.REQUESTED, Optional.empty(), Optional.empty(),
                Instant.parse("2026-10-06T00:00:00Z"), Optional.empty(), Optional.empty());
    }

    private static List<JsonNode> messagePayloads(String body) {
        List<JsonNode> payloads = new ArrayList<>();
        for (SseEvents.Event event : SseEvents.read(body).named("messages")) {
            payloads.add(event.data().get(0));
        }
        return payloads;
    }

    private static boolean containsTaskCall(JsonNode message) {
        for (JsonNode call : message.path("tool_calls")) {
            if ("task".equals(call.path("name").asText())) {
                return true;
            }
        }
        return false;
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

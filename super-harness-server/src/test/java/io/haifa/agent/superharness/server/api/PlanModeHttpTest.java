package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.superharness.server.testsupport.ControlledProvider;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlanModeHttpTest {
    private Path dataRoot;
    private Path evidence;
    private GatewayFixture.HttpEvidence http;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void prepare() throws Exception {
        dataRoot = GatewayFixture.dataDirectory();
        evidence = GatewayFixture.evidenceDirectory();
        http = new GatewayFixture.HttpEvidence(evidence, Duration.ofSeconds(30));
    }

    @AfterEach
    void close() throws InterruptedException {
        if (http != null) {
            http.close();
        }
    }

    @Test
    void proModeEnablesPlanAuthoringAndProjectsTodosInStreamAndState() throws Exception {
        Files.createDirectories(evidence);
        String threadId = "thread-pro-plan";
        List<Map<String, Object>> writtenTodos = List.of(
                Map.of("content", "Task 1", "status", "completed"),
                Map.of("content", "Task 2", "status", "in_progress"),
                Map.of("content", "Task 3", "status", "pending"));

        try (var provider = new ControlledProvider(evidence, true, (request, call) -> {
            if (call == 1) {
                return ControlledProvider.call("resp-plan-1", "demo-provider-id", "call-todo-1",
                        "write_todos", Map.of("todos", writtenTodos));
            }
            return ControlledProvider.stop("resp-plan-2", "demo-provider-id", "All planned.");
        })) {
            try (var api = GatewayFixture.production(dataRoot, 0, Map.of(), evidence)) {
                URI base = api.base;
                String model = """
                        {"config":{"name":"demo-model","display_name":"Demo Model",
                        "provider":"openai-compatible","model":"demo-provider-id",
                        "base_url":"http://127.0.0.1:%s/v1","enabled":true,"supports_vision":false,
                        "context_window":32768,"max_tokens":1024,"api_key":"controlled-key"},
                        "expected_revision":null}
                        """.formatted(provider.port());
                assertThat(http.request(base, "PUT", "/api/managed-models", model).statusCode()).isEqualTo(200);
                assertThat(http.request(base, "POST", "/api/threads", "{\"thread_id\":\"" + threadId + "\"}")
                        .statusCode()).isEqualTo(200);

                String runBody = """
                        {"assistant_id":"lead_agent","input":{"messages":[{"type":"human","content":"plan my project"}]},
                        "stream_mode":["messages-tuple","updates","custom"],
                        "context":{"model_name":"demo-model","mode":"pro","reasoning_effort":"minimal",
                        "thinking_enabled":false,"is_plan_mode":true,"subagent_enabled":false},
                        "on_disconnect":"continue","multitask_strategy":"reject"}
                        """;

                HttpResponse<String> response = http.request(base, "POST", "/api/threads/" + threadId + "/runs/stream", runBody, "pro-plan.sse");
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                assertThat(response.body()).contains("event: end");

                // Check provider request 1:
                JsonNode firstReq = json.readTree(Files.readString(evidence.resolve("provider-request-1.json")));
                assertThat(firstReq.path("tools").findValuesAsText("name")).contains("write_todos");
                assertThat(firstReq.path("messages").findValuesAsText("content"))
                        .anyMatch(content -> content.contains("<todo_list_system>"));

                // Check updates frame in SSE:
                assertThat(response.body()).contains("event: updates");
                assertThat(response.body()).contains("\"tools\":{\"todos\":[");

                // Check state endpoint:
                JsonNode state = http.get(base, "/api/threads/" + threadId + "/state", "pro-state.json");
                JsonNode todos = state.path("values").path("todos");
                assertThat(todos.isArray()).isTrue();
                assertThat(todos).hasSize(3);
                assertThat(todos.get(0).path("content").asText()).isEqualTo("Task 1");
                assertThat(todos.get(0).path("status").asText()).isEqualTo("completed");
                assertThat(todos.get(1).path("content").asText()).isEqualTo("Task 2");
                assertThat(todos.get(1).path("status").asText()).isEqualTo("in_progress");
                assertThat(todos.get(2).path("content").asText()).isEqualTo("Task 3");
                assertThat(todos.get(2).path("status").asText()).isEqualTo("pending");
            }
        }
    }

    @Test
    void flashModeOmitsWriteTodosAndStateHasNoTodos() throws Exception {
        Files.createDirectories(evidence);
        String threadId = "thread-flash-noplan";

        try (var provider = new ControlledProvider(evidence, true, (request, call) ->
                ControlledProvider.stop("resp-flash-1", "demo-provider-id", "Quick answer."))) {
            try (var api = GatewayFixture.production(dataRoot, 0, Map.of(), evidence)) {
                URI base = api.base;
                String model = """
                        {"config":{"name":"demo-model","display_name":"Demo Model",
                        "provider":"openai-compatible","model":"demo-provider-id",
                        "base_url":"http://127.0.0.1:%s/v1","enabled":true,"supports_vision":false,
                        "context_window":32768,"max_tokens":1024,"api_key":"controlled-key"},
                        "expected_revision":null}
                        """.formatted(provider.port());
                assertThat(http.request(base, "PUT", "/api/managed-models", model).statusCode()).isEqualTo(200);
                assertThat(http.request(base, "POST", "/api/threads", "{\"thread_id\":\"" + threadId + "\"}")
                        .statusCode()).isEqualTo(200);

                String runBody = """
                        {"assistant_id":"lead_agent","input":{"messages":[{"type":"human","content":"hello"}]},
                        "stream_mode":["messages-tuple","updates","custom"],
                        "context":{"model_name":"demo-model","mode":"flash","reasoning_effort":"minimal",
                        "thinking_enabled":false,"is_plan_mode":false,"subagent_enabled":false},
                        "on_disconnect":"continue","multitask_strategy":"reject"}
                        """;

                HttpResponse<String> response = http.request(base, "POST", "/api/threads/" + threadId + "/runs/stream", runBody, "flash.sse");
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).contains("event: end");

                JsonNode firstReq = json.readTree(Files.readString(evidence.resolve("provider-request-1.json")));
                assertThat(firstReq.path("tools").findValuesAsText("name")).doesNotContain("write_todos");

                JsonNode state = http.get(base, "/api/threads/" + threadId + "/state", "flash-state.json");
                assertThat(state.path("values").has("todos")).isFalse();
            }
        }
    }
}

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

class PlanModeLifecycleHttpTest {
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
    void historyAndRefreshExposeIdenticalTodosAndToolMessage() throws Exception {
        Files.createDirectories(evidence);
        String threadId = "thread-history-refresh";
        List<Map<String, Object>> writtenTodos = List.of(
                Map.of("content", "Design specs", "status", "completed"),
                Map.of("content", "Write code", "status", "in_progress"),
                Map.of("content", "Run tests", "status", "pending"));

        try (var provider = new ControlledProvider(evidence, true, (request, call) -> {
            if (call == 1) {
                return ControlledProvider.call("resp-hist-1", "demo-provider-id", "call-todo-1",
                        "write_todos", Map.of("todos", writtenTodos));
            }
            return ControlledProvider.stop("resp-hist-2", "demo-provider-id", "Plan created.");
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
                        {"assistant_id":"lead_agent","input":{"messages":[{"type":"human","content":"create project plan"}]},
                        "stream_mode":["messages-tuple","updates","custom"],
                        "context":{"model_name":"demo-model","mode":"pro","reasoning_effort":"minimal",
                        "thinking_enabled":false,"is_plan_mode":true,"subagent_enabled":false},
                        "on_disconnect":"continue","multitask_strategy":"reject"}
                        """;

                HttpResponse<String> response = http.request(base, "POST", "/api/threads/" + threadId + "/runs/stream", runBody, "history-refresh.sse");
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                assertThat(response.body()).contains("event: end");

                // 1. Check thread endpoint
                JsonNode threadRes = http.get(base, "/api/threads/" + threadId, "thread-resp.json");
                JsonNode threadTodos = threadRes.path("values").path("todos");
                assertThat(threadTodos.isArray()).isTrue();
                assertThat(threadTodos).hasSize(3);

                // 2. Check state endpoint
                JsonNode stateRes = http.get(base, "/api/threads/" + threadId + "/state", "state-resp.json");
                JsonNode stateTodos = stateRes.path("values").path("todos");
                assertThat(stateTodos).isEqualTo(threadTodos);

                // 3. Check history endpoint (POST /api/threads/{threadId}/history)
                HttpResponse<String> histHttp = http.request(base, "POST", "/api/threads/" + threadId + "/history", "{}", "history-resp.json");
                assertThat(histHttp.statusCode()).isEqualTo(200);
                JsonNode histRes = json.readTree(histHttp.body());
                assertThat(histRes.isArray()).isTrue();
                assertThat(histRes.size()).isGreaterThan(0);
                JsonNode histTodos = histRes.get(0).path("values").path("todos");
                assertThat(histTodos).isEqualTo(threadTodos);

                // 4. Check tool call and ToolMessage in state messages
                JsonNode messages = stateRes.path("values").path("messages");
                assertThat(messages.isArray()).isTrue();

                JsonNode callMsg = null;
                JsonNode toolMsg = null;
                for (JsonNode msg : messages) {
                    if (msg.has("tool_calls")) {
                        for (JsonNode tc : msg.path("tool_calls")) {
                            if ("write_todos".equals(tc.path("name").asText())) {
                                callMsg = msg;
                            }
                        }
                    }
                    if ("tool".equals(msg.path("type").asText()) || "tool".equals(msg.path("role").asText())) {
                        if ("write_todos".equals(msg.path("name").asText())) {
                            toolMsg = msg;
                        }
                    }
                }
                assertThat(callMsg).as("Tool call message for write_todos").isNotNull();
                assertThat(toolMsg).as("Tool result message for write_todos").isNotNull();
                assertThat(toolMsg.path("content").asText()).startsWith("Updated todo list to ");
            }
        }
    }

    @Test
    void restartWithSameDataRootPreservesTodosAcrossEndpoints() throws Exception {
        Files.createDirectories(evidence);
        String threadId = "thread-restart-test";
        List<Map<String, Object>> writtenTodos = List.of(
                Map.of("content", "Step Alpha", "status", "completed"),
                Map.of("content", "Step Beta", "status", "in_progress"));

        try (var provider = new ControlledProvider(evidence, true, (request, call) -> {
            if (call == 1) {
                return ControlledProvider.call("resp-rst-1", "demo-provider-id", "call-rst-1",
                        "write_todos", Map.of("todos", writtenTodos));
            }
            return ControlledProvider.stop("resp-rst-2", "demo-provider-id", "Saved.");
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
                        {"assistant_id":"lead_agent","input":{"messages":[{"type":"human","content":"setup plan"}]},
                        "stream_mode":["messages-tuple","updates","custom"],
                        "context":{"model_name":"demo-model","mode":"pro","reasoning_effort":"minimal",
                        "thinking_enabled":false,"is_plan_mode":true,"subagent_enabled":false},
                        "on_disconnect":"continue","multitask_strategy":"reject"}
                        """;

                HttpResponse<String> response = http.request(base, "POST", "/api/threads/" + threadId + "/runs/stream", runBody, "before-restart.sse");
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                assertThat(response.body()).contains("event: end");

                JsonNode beforeState = http.get(base, "/api/threads/" + threadId + "/state", "before-state.json");
                assertThat(beforeState.path("values").path("todos")).hasSize(2);
            }

            // Re-open with same dataRoot
            try (var restarted = GatewayFixture.production(dataRoot, 0, Map.of(), evidence)) {
                URI base2 = restarted.base;
                JsonNode threadRes = http.get(base2, "/api/threads/" + threadId, "after-restart-thread.json");
                JsonNode threadTodos = threadRes.path("values").path("todos");
                assertThat(threadTodos).hasSize(2);
                assertThat(threadTodos.get(0).path("content").asText()).isEqualTo("Step Alpha");
                assertThat(threadTodos.get(0).path("status").asText()).isEqualTo("completed");
                assertThat(threadTodos.get(1).path("content").asText()).isEqualTo("Step Beta");
                assertThat(threadTodos.get(1).path("status").asText()).isEqualTo("in_progress");

                JsonNode stateRes = http.get(base2, "/api/threads/" + threadId + "/state", "after-restart-state.json");
                assertThat(stateRes.path("values").path("todos")).isEqualTo(threadTodos);

                HttpResponse<String> histHttp = http.request(base2, "POST", "/api/threads/" + threadId + "/history", "{}", "after-restart-history.json");
                assertThat(histHttp.statusCode()).isEqualTo(200);
                JsonNode histRes = json.readTree(histHttp.body());
                assertThat(histRes.get(0).path("values").path("todos")).isEqualTo(threadTodos);
            }
        }
    }

    @Test
    void invalidInputRecoversAndCompletesWithoutErrorEvent() throws Exception {
        Files.createDirectories(evidence);
        String threadId = "thread-invalid-recovery";
        List<Map<String, Object>> validTodos = List.of(
                Map.of("content", "Valid step", "status", "completed"));

        try (var provider = new ControlledProvider(evidence, true, (request, call) -> {
            if (call == 1) {
                // First call: invalid status "unknown_status"
                return ControlledProvider.call("resp-inv-1", "demo-provider-id", "call-inv-1",
                        "write_todos", Map.of("todos", List.of(
                                Map.of("content", "Invalid step", "status", "unknown_status"))));
            }
            if (call == 2) {
                // Second call: fixed with valid status
                return ControlledProvider.call("resp-inv-2", "demo-provider-id", "call-inv-2",
                        "write_todos", Map.of("todos", validTodos));
            }
            return ControlledProvider.stop("resp-inv-3", "demo-provider-id", "Recovered successfully.");
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
                        {"assistant_id":"lead_agent","input":{"messages":[{"type":"human","content":"fix plan"}]},
                        "stream_mode":["messages-tuple","updates","custom"],
                        "context":{"model_name":"demo-model","mode":"pro","reasoning_effort":"minimal",
                        "thinking_enabled":false,"is_plan_mode":true,"subagent_enabled":false},
                        "on_disconnect":"continue","multitask_strategy":"reject"}
                        """;

                HttpResponse<String> response = http.request(base, "POST", "/api/threads/" + threadId + "/runs/stream", runBody, "invalid-recovery.sse");
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                assertThat(response.body()).contains("event: end");
                assertThat(response.body()).doesNotContain("event: error");

                JsonNode state = http.get(base, "/api/threads/" + threadId + "/state", "invalid-recovery-state.json");
                JsonNode todos = state.path("values").path("todos");
                assertThat(todos).hasSize(1);
                assertThat(todos.get(0).path("content").asText()).isEqualTo("Valid step");
                assertThat(todos.get(0).path("status").asText()).isEqualTo("completed");
            }
        }
    }

    @Test
    void multiRunPreservesEarlierTodosUntilOverwritten() throws Exception {
        Files.createDirectories(evidence);
        String threadId = "thread-multi-run";
        List<Map<String, Object>> run1Todos = List.of(
                Map.of("content", "Initial 1", "status", "pending"),
                Map.of("content", "Initial 2", "status", "pending"));
        List<Map<String, Object>> run3Todos = List.of(
                Map.of("content", "Initial 1", "status", "completed"),
                Map.of("content", "Initial 2", "status", "in_progress"),
                Map.of("content", "New 3", "status", "pending"));

        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();
        try (var provider = new ControlledProvider(evidence, true, (request, call) -> {
            int current = callCount.incrementAndGet();
            if (current == 1) { // Run 1: writes todos
                return ControlledProvider.call("r1-call-1", "demo-provider-id", "tc-1",
                        "write_todos", Map.of("todos", run1Todos));
            }
            if (current == 2) { // Run 1: finishes
                return ControlledProvider.stop("r1-call-2", "demo-provider-id", "Run 1 done.");
            }
            if (current == 3) { // Run 2: normal reply without write_todos
                return ControlledProvider.stop("r2-call-1", "demo-provider-id", "Run 2 answered directly.");
            }
            if (current == 4) { // Run 3: updates todos
                return ControlledProvider.call("r3-call-1", "demo-provider-id", "tc-3",
                        "write_todos", Map.of("todos", run3Todos));
            }
            return ControlledProvider.stop("r3-call-2", "demo-provider-id", "Run 3 finished.");
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

                // Run 1: write initial todos
                String run1Body = """
                        {"assistant_id":"lead_agent","input":{"messages":[{"type":"human","content":"start plan"}]},
                        "stream_mode":["messages-tuple","updates","custom"],
                        "context":{"model_name":"demo-model","mode":"pro","reasoning_effort":"minimal",
                        "thinking_enabled":false,"is_plan_mode":true,"subagent_enabled":false},
                        "on_disconnect":"continue","multitask_strategy":"reject"}
                        """;
                HttpResponse<String> r1 = http.request(base, "POST", "/api/threads/" + threadId + "/runs/stream", run1Body, "run1.sse");
                assertThat(r1.statusCode()).as(r1.body()).isEqualTo(200);

                JsonNode state1 = http.get(base, "/api/threads/" + threadId + "/state", "state1.json");
                assertThat(state1.path("values").path("todos")).hasSize(2);

                // Run 2: no write_todos called
                String run2Body = """
                        {"assistant_id":"lead_agent","input":{"messages":[{"type":"human","content":"how is it going?"}]},
                        "stream_mode":["messages-tuple","updates","custom"],
                        "context":{"model_name":"demo-model","mode":"pro","reasoning_effort":"minimal",
                        "thinking_enabled":false,"is_plan_mode":true,"subagent_enabled":false},
                        "on_disconnect":"continue","multitask_strategy":"reject"}
                        """;
                HttpResponse<String> r2 = http.request(base, "POST", "/api/threads/" + threadId + "/runs/stream", run2Body, "run2.sse");
                assertThat(r2.statusCode()).as(r2.body()).isEqualTo(200);

                JsonNode state2 = http.get(base, "/api/threads/" + threadId + "/state", "state2.json");
                // Must still have Run 1's todos
                assertThat(state2.path("values").path("todos")).hasSize(2);
                assertThat(state2.path("values").path("todos").get(0).path("content").asText()).isEqualTo("Initial 1");

                // Run 3: overwrite todos
                String run3Body = """
                        {"assistant_id":"lead_agent","input":{"messages":[{"type":"human","content":"update plan"}]},
                        "stream_mode":["messages-tuple","updates","custom"],
                        "context":{"model_name":"demo-model","mode":"pro","reasoning_effort":"minimal",
                        "thinking_enabled":false,"is_plan_mode":true,"subagent_enabled":false},
                        "on_disconnect":"continue","multitask_strategy":"reject"}
                        """;
                HttpResponse<String> r3 = http.request(base, "POST", "/api/threads/" + threadId + "/runs/stream", run3Body, "run3.sse");
                assertThat(r3.statusCode()).as(r3.body()).isEqualTo(200);

                JsonNode state3 = http.get(base, "/api/threads/" + threadId + "/state", "state3.json");
                assertThat(state3.path("values").path("todos")).hasSize(3);
                assertThat(state3.path("values").path("todos").get(0).path("status").asText()).isEqualTo("completed");
                assertThat(state3.path("values").path("todos").get(2).path("content").asText()).isEqualTo("New 3");
            }
        }
    }

    @Test
    void ultraModeCombinesPlanAuthoringAndChildDelegation() throws Exception {
        Files.createDirectories(evidence);
        String threadId = "thread-ultra-plan";
        Map<String, String> env = Map.of(
                "HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W08_ASSEMBLY_KEY",
                "W08_ASSEMBLY_KEY", java.util.Base64.getEncoder().encodeToString(new byte[32]));

        java.util.concurrent.atomic.AtomicInteger leadCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean childToolChecked = new java.util.concurrent.atomic.AtomicBoolean();

        try (var provider = new ControlledProvider(evidence, true, (request, call) -> {
            if (io.haifa.agent.superharness.server.testsupport.DelegationHttpFixture.isChild(request)) {
                // Child tool list must NOT contain write_todos
                assertThat(request.path("tools").findValuesAsText("name")).doesNotContain("write_todos");
                childToolChecked.set(true);
                return ControlledProvider.stop("child-" + call, "w08-model", "CHILD-RESEARCH-RESULT", 11, 13);
            }
            int current = leadCalls.incrementAndGet();
            if (current == 1) {
                // Lead writes plan first
                return ControlledProvider.call("lead-" + call, "w08-model", "tc-plan-1",
                        "write_todos", Map.of("todos", List.of(
                                Map.of("content", "Coordinate research", "status", "in_progress"))));
            }
            if (current == 2) {
                // Lead delegates to researcher child
                return ControlledProvider.call("lead-" + call, "w08-model", "tc-task-1",
                        "task", Map.of("agent", "researcher", "objective", "Investigate facts"), 2, 3);
            }
            return ControlledProvider.stop("lead-" + call, "w08-model", "Plan and subtask complete.", 5, 7);
        })) {
            try (var api = GatewayFixture.production(dataRoot, 0, env, evidence)) {
                URI base = api.base;
                io.haifa.agent.superharness.server.testsupport.DelegationHttpFixture.configure(http, base, provider.port());
                io.haifa.agent.superharness.server.testsupport.DelegationHttpFixture.seed(
                        http, base, "researcher", 30, 3, List.of("list_uploaded_files"));

                assertThat(http.request(base, "POST", "/api/threads", "{\"thread_id\":\"" + threadId + "\"}")
                        .statusCode()).isEqualTo(200);

                String runBody = """
                        {"assistant_id":"lead_agent","input":{"messages":[{"type":"human","content":"run ultra plan"}]},
                        "stream_mode":["messages-tuple","updates","custom"],
                        "context":{"model_name":"w08","mode":"ultra","reasoning_effort":"minimal",
                        "thinking_enabled":false,"is_plan_mode":true,"subagent_enabled":true},
                        "on_disconnect":"continue","multitask_strategy":"reject"}
                        """;

                HttpResponse<String> response = http.request(base, "POST", "/api/threads/" + threadId + "/runs/stream", runBody, "ultra-plan.sse");
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                assertThat(response.body()).contains("event: end");
                assertThat(childToolChecked.get()).isTrue();

                // Lead request 1 must contain BOTH write_todos and task
                JsonNode leadReq1 = provider.requests.stream()
                        .filter(r -> !io.haifa.agent.superharness.server.testsupport.DelegationHttpFixture.isChild(r))
                        .findFirst().orElseThrow();
                assertThat(leadReq1.path("tools").findValuesAsText("name")).contains("write_todos", "task");

                // Stream must contain child results and subagent status
                assertThat(response.body()).contains("CHILD-RESEARCH-RESULT");

                // State values.todos must be readable
                JsonNode state = http.get(base, "/api/threads/" + threadId + "/state", "ultra-state.json");
                JsonNode todos = state.path("values").path("todos");
                assertThat(todos).hasSize(1);
                assertThat(todos.get(0).path("content").asText()).isEqualTo("Coordinate research");
                assertThat(todos.get(0).path("status").asText()).isEqualTo("in_progress");
            }
        }
    }

    @Test
    void cancelledRunPreservesWrittenTodos() throws Exception {
        Files.createDirectories(evidence);
        String threadId = "thread-cancel-plan";
        List<Map<String, Object>> writtenTodos = List.of(
                Map.of("content", "Step before cancel", "status", "in_progress"));

        java.util.concurrent.CountDownLatch modelBlocked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch cancelTriggered = new java.util.concurrent.CountDownLatch(1);

        try (var provider = new ControlledProvider(evidence, true, (request, call) -> {
            if (call == 1) {
                // Call 1: write plan
                return ControlledProvider.call("resp-c-1", "demo-provider-id", "call-c-1",
                        "write_todos", Map.of("todos", writtenTodos));
            }
            // Call 2: block until cancelled
            modelBlocked.countDown();
            try {
                cancelTriggered.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ControlledProvider.stop("resp-c-2", "demo-provider-id", "Should be cancelled.");
        }, (req, auth) -> true, req -> true)) {
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
                        {"assistant_id":"lead_agent","input":{"messages":[{"type":"human","content":"start cancellable plan"}]},
                        "stream_mode":["messages-tuple","updates","custom"],
                        "context":{"model_name":"demo-model","mode":"pro","reasoning_effort":"minimal",
                        "thinking_enabled":false,"is_plan_mode":true,"subagent_enabled":false},
                        "on_disconnect":"continue","multitask_strategy":"reject"}
                        """;

                var streamFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        return http.request(base, "POST", "/api/threads/" + threadId + "/runs/stream", runBody, "cancel-stream.sse");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                assertThat(modelBlocked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

                // Find active run
                JsonNode runs = http.get(base, "/api/threads/" + threadId + "/runs", "runs-before-cancel.json");
                String runId = runs.get(0).path("run_id").asText();
                String location = "/api/threads/" + threadId + "/runs/" + runId;

                // Send cancel
                HttpResponse<String> cancelRes = http.request(base, "POST", location + "/cancel?action=interrupt&wait=0", "", "cancel.http");
                assertThat(cancelRes.statusCode()).isEqualTo(202);
                cancelTriggered.countDown();

                // Bounded await for terminal cancelled status
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                JsonNode runObj;
                do {
                    runObj = http.get(base, location, "cancel-status.json");
                    if ("interrupted".equals(runObj.path("status").asText())
                            || "CANCELLED".equals(runObj.path("diagnostics").path("sdk_status").asText())) {
                        break;
                    }
                    Thread.sleep(30);
                } while (System.nanoTime() < deadline);

                assertThat(runObj.path("status").asText()).isEqualTo("interrupted");

                HttpResponse<String> sseRes = streamFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(sseRes.statusCode()).isEqualTo(200);

                JsonNode state = http.get(base, "/api/threads/" + threadId + "/state", "state-after-cancel.json");
                JsonNode todos = state.path("values").path("todos");
                assertThat(todos).hasSize(1);
                assertThat(todos.get(0).path("content").asText()).isEqualTo("Step before cancel");
                assertThat(todos.get(0).path("status").asText()).isEqualTo("in_progress");
            }
        }
    }
}

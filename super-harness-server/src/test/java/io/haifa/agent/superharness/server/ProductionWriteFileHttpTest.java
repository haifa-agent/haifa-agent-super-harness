package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceChangeLimits;
import io.haifa.agent.superharness.workspace.WorkspaceLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Production bootstrap, controlled OpenAI-compatible Provider, SDK approval, real file transport. */
class ProductionWriteFileHttpTest {
    @TempDir Path dataRoot;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void productionPendingWriteCancellationLeavesNoFileOrArtifactAfterLateApprovalAndRestart() throws Exception {
        String assignedRoot = System.getProperty("w05.pendingCancel.dataRoot");
        if (assignedRoot != null) {
            Files.createDirectories(Path.of(assignedRoot));
            dataRoot = Files.createTempDirectory(Path.of(assignedRoot), "pending-cancel-");
        }
        String evidenceRoot = System.getProperty("w05.pendingCancel.evidence");
        Path evidence = evidenceRoot == null ? dataRoot.resolve("evidence") : Path.of(evidenceRoot);
        Files.createDirectories(evidence);
        String threadId = "production-pending-cancel";
        String path = "/mnt/user-data/outputs/pending-cancel.txt";
        AtomicInteger calls = new AtomicInteger();
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/chat/completions", exchange -> {
            JsonNode body = json.readTree(exchange.getRequestBody());
            int call = calls.incrementAndGet();
            // Provider headers and managed-model credentials are deliberately absent from evidence.
            Files.writeString(evidence.resolve("provider-request-" + call + ".json"),
                    body.toPrettyString(), StandardCharsets.UTF_8);
            String arguments = json.writeValueAsString(Map.of("path", path, "content", "must never be written\n"));
            String chunk = json.writeValueAsString(Map.of(
                    "id", "provider-pending-cancel-" + call, "model", "demo-provider-id",
                    "choices", List.of(Map.of("index", 0, "finish_reason", "tool_calls",
                            "delta", Map.of("tool_calls", List.of(Map.of(
                                    "index", 0, "id", "call-pending-cancel-" + call, "type", "function",
                                    "function", Map.of("name", "write_file", "arguments", arguments))))))));
            byte[] response = ("data: " + chunk + "\n\n"
                    + "data: {\"id\":\"provider-pending-cancel-" + call
                    + "\",\"model\":\"demo-provider-id\",\"choices\":[],"
                    + "\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":3}}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            Files.write(evidence.resolve("provider-response-" + call + ".sse"), response);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        provider.start();
        int gatewayPort = Integer.getInteger("w05.pendingCancel.gatewayPort", 0);
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        Map<String, String> environment = Map.of(
                "HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W05_TEST_KEY", "W05_TEST_KEY", key);
        String location;
        String runId;
        String toolCallId;
        try {
            try (var api = DeerFlowBootstrapApi.create(
                    new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), gatewayPort),
                    dataRoot, environment)) {
                api.start();
                URI base = URI.create("http://127.0.0.1:" + api.address().getPort());
                String model = """
                        {"config":{"name":"demo-model","display_name":"Demo Model",
                        "provider":"openai-compatible","model":"demo-provider-id",
                        "base_url":"http://127.0.0.1:%s/v1","enabled":true,"supports_vision":false,
                        "context_window":8192,"max_tokens":1024,"api_key":"controlled-key"},
                        "expected_revision":null}
                        """.formatted(provider.getAddress().getPort());
                assertThat(request(base, "PUT", "/api/managed-models", model).statusCode()).isEqualTo(200);
                assertThat(request(base, "POST", "/api/threads", "{\"thread_id\":\"" + threadId + "\"}")
                        .statusCode()).isEqualTo(200);
                String runBody = """
                        {"assistant_id":"lead_agent","input":{"messages":[{"type":"human","content":"create a file"}]},
                        "stream_mode":["messages-tuple","updates","custom"],
                        "context":{"model_name":"demo-model","mode":"flash","reasoning_effort":"minimal",
                        "thinking_enabled":false,"is_plan_mode":false,"subagent_enabled":false},
                        "on_disconnect":"continue","multitask_strategy":"reject"}
                        """;
                HttpResponse<String> pending = recordedRequest(
                        evidence, "pending.sse", base, "POST", "/api/threads/" + threadId + "/runs/stream", runBody);
                assertThat(pending.statusCode()).as(pending.body()).isEqualTo(200);
                assertThat(pending.body()).contains("event: end");
                location = pending.headers().firstValue("content-location").orElseThrow();
                runId = location.substring(location.lastIndexOf('/') + 1);
                JsonNode pendingRun = recordedJson(evidence, "pending-run.json", base, location);
                assertThat(pendingRun.path("run_id").asText()).isEqualTo(runId);
                assertThat(pendingRun.path("thread_id").asText()).isEqualTo(threadId);
                assertThat(pendingRun.path("diagnostics").path("sdk_status").asText()).isEqualTo("WAITING_APPROVAL");
                JsonNode pendingState = recordedJson(
                        evidence, "pending-state.json", base, "/api/threads/" + threadId + "/state");
                JsonNode call = firstMessageWith(pendingState, "tool_calls").path("tool_calls").get(0);
                assertThat(call.path("name").asText()).isEqualTo("write_file");
                assertThat(call.path("args").path("path").asText()).isEqualTo(path);
                toolCallId = call.path("id").asText();
                JsonNode card = firstHumanInputCard(pendingState).path("artifact").path("human_input");
                assertThat(card.path("tool_call_id").asText()).isEqualTo(toolCallId);
                String requestId = card.path("request_id").asText();
                assertThat(requestId).isNotBlank();
                assertThat(json.readTree(Files.readString(evidence.resolve("provider-request-1.json")))
                        .path("tools").findValuesAsText("name")).contains("write_file");
                assertNoPendingWriteSideEffect(evidence, "pending", base, threadId, path, runId, toolCallId);

                HttpResponse<String> cancelled = recordedRequest(evidence, "cancel.http", base, "POST",
                        location + "/cancel?action=interrupt&wait=0", "");
                assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(202);
                awaitCancelled(evidence, base, location, runId, threadId);
                HttpResponse<String> repeated = recordedRequest(evidence, "repeated-cancel.http", base, "POST",
                        location + "/cancel?action=interrupt&wait=0", "");
                assertThat(repeated.statusCode()).as(repeated.body()).isEqualTo(409);
                assertThat(repeated.body()).contains("not cancellable");
                HttpResponse<String> lateApproval = recordedRequest(evidence, "late-approval.http", base, "POST",
                        "/api/threads/" + threadId + "/runs/stream", interactionResponse(requestId, "approve"));
                assertThat(lateApproval.statusCode()).as(lateApproval.body()).isEqualTo(409);
                assertThat(calls).hasValue(1);
                assertNoPendingWriteSideEffect(evidence, "cancelled", base, threadId, path, runId, toolCallId);
                System.out.println("W05_PENDING_WRITE_CANCEL_EVIDENCE run=" + runId + " thread=" + threadId
                        + " toolCall=" + toolCallId + " interaction=" + requestId
                        + " sdk_status=CANCELLED http_status=interrupted cancel=202 repeated_cancel=409 late_approve=409");
            }
            // Closing the production Runtime joins its completion observers. Reopening the same
            // SQLite and workspace proves durable cancellation without a time-based quiet window.
            try (var restarted = DeerFlowBootstrapApi.create(
                    new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), gatewayPort),
                    dataRoot, environment)) {
                restarted.start();
                URI base = URI.create("http://127.0.0.1:" + restarted.address().getPort());
                awaitCancelled(evidence, base, location, runId, threadId);
                assertNoPendingWriteSideEffect(evidence, "restarted", base, threadId, path, runId, toolCallId);
                JsonNode runs = recordedJson(evidence, "restarted-runs.json", base,
                        "/api/threads/" + threadId + "/runs");
                assertThat(runs.findValuesAsText("run_id")).containsExactly(runId);
                assertThat(calls).hasValue(1);
                assertThat(dataRoot.resolve("runtime/haifa-agent.sqlite")).exists();
                System.out.println("W05_PENDING_WRITE_CANCEL_EVIDENCE restart_same_run=true provider_calls=1"
                        + " production_file=false persisted_artifacts=0 dataRoot=" + dataRoot + " evidence=" + evidence);
            }
        } finally {
            provider.stop(0);
        }
    }

    private JsonNode awaitCancelled(Path evidence, URI base, String location, String runId, String threadId)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        JsonNode run;
        do {
            run = recordedJson(evidence, "cancel-status-latest.json", base, location);
            assertThat(run.path("run_id").asText()).isEqualTo(runId);
            assertThat(run.path("thread_id").asText()).isEqualTo(threadId);
            if ("CANCELLED".equals(run.path("diagnostics").path("sdk_status").asText())) {
                assertThat(run.path("status").asText()).isEqualTo("interrupted");
                return run;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Run did not durably cancel within 10 seconds: " + run);
    }

    private void assertNoPendingWriteSideEffect(Path evidence, String phase, URI base, String threadId,
            String path, String runId, String toolCallId) throws Exception {
        assertThat(recordedRequest(evidence, phase + "-download.http", base, "GET",
                "/api/threads/" + threadId + "/artifacts" + path, null).statusCode()).isEqualTo(404);
        JsonNode state = recordedJson(evidence, phase + "-state.json", base, "/api/threads/" + threadId + "/state");
        assertThat(state.path("values").path("artifacts")).isEmpty();
        assertThat(messagesNamed(state, "write_file")).isEmpty();
        JsonNode run = recordedJson(evidence, phase + "-run.json", base, "/api/threads/" + threadId + "/runs/" + runId);
        assertThat(run.path("diagnostics").path("tool_calls").findValuesAsText("status")).doesNotContain("COMPLETED");
        assertThat(run.path("diagnostics").path("artifacts")).isEmpty();
        var bindings = new PersistentProjectWorkspaceService(
                dataRoot.resolve("workspace"), WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
        var artifacts = new RestrictedWorkspaceService(dataRoot.resolve("workspace"), WorkspaceLimits.defaults());
        var binding = bindings.requireThreadWorkspace("default", threadId);
        var published = artifacts.findArtifacts("default", binding.workspaceId(), "thread:" + threadId);
        Path target = dataRoot.resolve("workspace/workspaces").resolve(binding.workspaceId().value())
                .resolve("user-data/outputs/pending-cancel.txt");
        Files.writeString(evidence.resolve(phase + "-workspace-proof.json"), json.writeValueAsString(Map.of(
                "run_id", runId, "tool_call_id", toolCallId, "path", target.toString(),
                "file_exists", Files.exists(target), "persisted_artifact_count", published.size())), StandardCharsets.UTF_8);
        assertThat(target).doesNotExist();
        assertThat(published).isEmpty();
    }

    private JsonNode recordedJson(Path evidence, String name, URI base, String path) throws Exception {
        HttpResponse<String> response = recordedRequest(evidence, name, base, "GET", path, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private HttpResponse<String> recordedRequest(Path evidence, String name, URI base,
            String method, String path, String body) throws Exception {
        HttpResponse<String> response = request(base, method, path, body);
        Files.writeString(evidence.resolve(name), response.body(), StandardCharsets.UTF_8);
        Files.writeString(evidence.resolve(name + ".metadata.json"), json.writeValueAsString(Map.of(
                "method", method, "path", path, "status", response.statusCode(),
                "content_location", response.headers().firstValue("content-location").orElse(""))), StandardCharsets.UTF_8);
        return response;
    }

    @Test
    void productionApprovalGateRejectsMismatchedCrossThreadDuplicateAndRejectedWrites() throws Exception {
        byte[] expected = "真实文件内容\n".getBytes(StandardCharsets.UTF_8);
        String path = "/mnt/user-data/outputs/production.txt";
        List<JsonNode> providerRequests = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger calls = new AtomicInteger();
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            providerRequests.add(request);
            int call = calls.getAndIncrement();
            boolean hasToolResult = false;
            for (JsonNode message : request.path("messages")) {
                if ("tool".equals(message.path("role").asText())) {
                    hasToolResult = true;
                    break;
                }
            }
            String chunk;
            String responseId = "provider-write-" + (call + 1);
            if (!hasToolResult) {
                String arguments = json.writeValueAsString(Map.of(
                        "path", path, "content", new String(expected, StandardCharsets.UTF_8)));
                chunk = json.writeValueAsString(Map.of(
                        "id", responseId, "model", "demo-provider-id",
                        "choices", List.of(Map.of("index", 0, "finish_reason", "tool_calls",
                                "delta", Map.of("tool_calls", List.of(Map.of(
                                        "index", 0, "id", "call-production-write-" + (call + 1), "type", "function",
                                        "function", Map.of("name", "write_file", "arguments", arguments))))))));
            } else {
                chunk = json.writeValueAsString(Map.of(
                        "id", responseId, "model", "demo-provider-id",
                        "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                                "delta", Map.of("content", "Created the file.")))));
            }
            byte[] response = ("data: " + chunk + "\n\n"
                    + "data: {\"id\":\"" + responseId + "\",\"model\":\"demo-provider-id\",\"choices\":[],"
                    + "\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":3}}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        provider.start();
        try {
            String key = Base64.getEncoder().encodeToString(new byte[32]);
            String[] persistedProjection = new String[3];
            try (var api = DeerFlowBootstrapApi.create(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataRoot,
                Map.of("HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W05_TEST_KEY",
                        "W05_TEST_KEY", key))) {
            api.start();
            URI base = URI.create("http://127.0.0.1:" + api.address().getPort());
            String endpoint = "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1";
            String model = """
                    {"config":{"name":"demo-model","display_name":"Demo Model",
                    "provider":"openai-compatible","model":"demo-provider-id",
                    "base_url":"%s","enabled":true,"supports_vision":false,
                    "context_window":8192,"max_tokens":1024,"api_key":"controlled-key"},
                    "expected_revision":null}
                    """.formatted(endpoint);
            assertThat(request(base, "PUT", "/api/managed-models", model).statusCode()).isEqualTo(200);
            assertThat(request(base, "POST", "/api/threads", "{\"thread_id\":\"production-file\"}")
                    .statusCode()).isEqualTo(200);
            String runBody = """
                    {"assistant_id":"lead_agent","input":{"messages":[{"type":"human","content":"create a file"}]},
                    "stream_mode":["messages-tuple","updates","custom"],
                    "context":{"model_name":"demo-model","mode":"flash","reasoning_effort":"minimal",
                    "thinking_enabled":false,"is_plan_mode":false,"subagent_enabled":false},
                    "on_disconnect":"continue","multitask_strategy":"reject"}
                    """;
            HttpResponse<String> pending = request(base, "POST", "/api/threads/production-file/runs/stream", runBody);
            assertThat(pending.statusCode()).as(pending.body()).isEqualTo(200);
            String location = pending.headers().firstValue("content-location").orElseThrow();
            String runId = location.substring(location.lastIndexOf('/') + 1);
            var requestIdMatch = Pattern.compile("\\\"request_id\\\":\\\"([^\\\"]+)\\\"").matcher(pending.body());
            assertThat(requestIdMatch.find()).as(pending.body()).isTrue();
            String requestId = requestIdMatch.group(1);
            var toolCallIdMatch = Pattern.compile("\\\"id\\\":\\\"tool-call-([^\\\"]+)-request\\\"")
                    .matcher(pending.body());
            assertThat(toolCallIdMatch.find()).as(pending.body()).isTrue();
            String pendingToolCallId = toolCallIdMatch.group(1);
            assertThat(pending.body()).contains("tool-call-" + pendingToolCallId + "-request");
            assertThat(providerRequests).hasSize(1);
            assertThat(providerRequests.getFirst().path("tools").findValuesAsText("name"))
                    .contains("write_file", "ask_clarification");
            URI artifact = base.resolve("/api/threads/production-file/artifacts" + path);
            assertThat(client.send(HttpRequest.newBuilder(artifact).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(404);
            JsonNode pendingState = json.readTree(
                    request(base, "GET", "/api/threads/production-file/state", null).body());
            JsonNode pendingToolCall = firstMessageWith(pendingState, "tool_calls");
            assertThat(pendingToolCall.path("tool_calls").get(0).path("name").asText()).isEqualTo("write_file");
            assertThat(pendingToolCall.path("tool_calls").get(0).path("args").path("path").asText())
                    .isEqualTo(path);
            JsonNode pendingCard = firstHumanInputCard(pendingState);
            assertThat(pendingCard.path("tool_call_id").asText()).isEqualTo(pendingCard.path("id").asText());
            assertThat(pendingCard.path("tool_call_id").asText()).isNotEqualTo(pendingToolCallId);
            assertThat(pendingCard.path("artifact").path("human_input").path("tool_call_id").asText())
                    .isEqualTo(pendingToolCallId);
            assertThat(pendingState.path("values").path("artifacts")).isEmpty();

            String wrongRequest = interactionResponse("another-request", "approve");
            assertThat(request(base, "POST", "/api/threads/production-file/runs/stream", wrongRequest)
                    .statusCode()).isEqualTo(409);
            assertThat(request(base, "POST", "/api/threads", "{\"thread_id\":\"production-file-other\"}")
                    .statusCode()).isEqualTo(200);
            String crossThread = interactionResponse(requestId, "approve");
            assertThat(request(base, "POST", "/api/threads/production-file-other/runs/stream", crossThread)
                    .statusCode()).isEqualTo(409);
            assertThat(client.send(HttpRequest.newBuilder(artifact).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(404);

            String approval = interactionResponse(requestId, "approve");
            HttpResponse<String> resumed = request(base, "POST", "/api/threads/production-file/runs/stream", approval);
            assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
            assertThat(resumed.body()).contains("Created the file.");
            assertThat(resumed.body()).contains("tool-call-" + pendingToolCallId + "-result", "artifactId");
            assertThat(json.readTree(request(base, "GET", location, null).body()).path("status").asText())
                    .isEqualTo("success");
            assertThat(providerRequests).hasSize(2);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected));
            JsonNode runDiagnostics = json.readTree(request(base, "GET", location, null).body())
                    .path("diagnostics");
            assertThat(runDiagnostics.path("model_calls")).hasSize(2);
            assertThat(runDiagnostics.path("model_calls").findValuesAsText("model_id"))
                    .containsExactly("demo-provider-id", "demo-provider-id");
            JsonNode threadUsage = json.readTree(request(
                    base, "GET", "/api/threads/production-file/token-usage", null).body());
            assertThat(threadUsage.path("total_runs").asInt()).isEqualTo(1);
            assertThat(threadUsage.path("total_tokens").asLong()).isEqualTo(14);
            assertThat(threadUsage.path("by_model").size()).isEqualTo(1);
            JsonNode modelUsage = threadUsage.path("by_model").elements().next();
            assertThat(modelUsage.path("tokens").asLong()).isEqualTo(14);
            assertThat(modelUsage.path("runs").asInt()).isEqualTo(1);
            assertThat(runDiagnostics.path("tool_calls").findValuesAsText("tool_call_id"))
                    .contains(pendingToolCallId);
            assertThat(runDiagnostics.path("tool_calls").findValuesAsText("status"))
                    .contains("COMPLETED");
            assertThat(runDiagnostics.path("interactions").findValuesAsText("request_id"))
                    .contains(requestId);
            assertThat(runDiagnostics.path("artifacts").findValuesAsText("artifact_id"))
                    .hasSize(1);
            assertThat(runDiagnostics.path("artifacts").findValuesAsText("path")).contains(path);
            assertThat(runDiagnostics.path("artifacts").findValuesAsText("sha256"))
                    .contains("sha256:" + sha);
            assertThat(providerRequests.get(1).toString())
                    .contains("Created", "call-production-write", path, sha);
            JsonNode toolResult = null;
            for (JsonNode message : providerRequests.get(1).path("messages")) {
                if ("tool".equals(message.path("role").asText())) toolResult = message;
            }
            assertThat(toolResult).isNotNull();
            assertThat(toolResult.path("tool_call_id").asText()).isEqualTo("call-production-write-1");
            String toolSummary = toolResult.path("content").asText();
            assertThat(toolSummary).contains(path, sha);

            HttpResponse<byte[]> downloaded = client.send(
                    HttpRequest.newBuilder(artifact).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(downloaded.statusCode()).isEqualTo(200);
            assertThat(downloaded.body()).isEqualTo(expected);
            assertThat(downloaded.headers().firstValue("etag")).contains("\"" + sha + "\"");

            JsonNode completedState = json.readTree(
                    request(base, "GET", "/api/threads/production-file/state", null).body());
            JsonNode projectedCall = firstMessageWith(completedState, "tool_calls");
            String projectedCallId = projectedCall.path("tool_calls").get(0).path("id").asText();
            assertThat(projectedCallId).isEqualTo(pendingToolCallId);
            JsonNode projectedResult = firstToolResult(completedState, projectedCallId);
            assertThat(projectedResult.path("name").asText()).isEqualTo("write_file");
            assertThat(projectedResult.path("content").asText()).contains("artifact:");
            JsonNode structured = projectedResult.path("additional_kwargs").path("haifa_tool_result");
            assertThat(structured.path("path").asText()).isEqualTo(path);
            assertThat(runDiagnostics.path("artifacts").findValuesAsText("artifact_id"))
                    .contains(structured.path("artifactId").asText());
            assertThat(structured.path("artifactExecutionRef").asText()).isEqualTo(projectedCallId);
            assertThat(structured.path("artifactSha256").asText()).isEqualTo("sha256:" + sha);
            assertThat(completedState.path("values").path("artifacts").toString()).contains(path);
            assertThat(messagesNamed(completedState, "ask_clarification")).hasSize(1);
            assertThat(messagesNamed(completedState, "write_file")).hasSize(1);
            JsonNode repeatedState = json.readTree(
                    request(base, "GET", "/api/threads/production-file/state", null).body());
            assertThat(repeatedState.path("values").path("messages")).isEqualTo(
                    completedState.path("values").path("messages"));
            JsonNode history = json.readTree(
                    request(base, "POST", "/api/threads/production-file/history", "{\"limit\":10}").body());
            JsonNode page = json.readTree(request(
                    base, "GET", "/api/threads/production-file/messages/page?limit=50", null).body());
            assertThat(page.path("data")).isNotEmpty();
            assertThat(page.path("data").findValuesAsText("seq")).doesNotHaveDuplicates();
            assertThat(history.get(0).path("values").path("artifacts").toString()).contains(path);
            assertThat(firstToolResult(history.get(0), projectedCallId)
                            .path("additional_kwargs").path("haifa_tool_result").path("artifactId").asText())
                    .isEqualTo(structured.path("artifactId").asText());
            persistedProjection[0] = projectedCallId;
            persistedProjection[1] = structured.path("artifactId").asText();
            persistedProjection[2] = sha;

            Path workspaceRoot = dataRoot.resolve("workspace");
            var bindings = new PersistentProjectWorkspaceService(
                    workspaceRoot, WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
            var artifacts = new RestrictedWorkspaceService(workspaceRoot, WorkspaceLimits.defaults());
            var workspaceBinding = bindings.requireThreadWorkspace("default", "production-file");
            var published = artifacts.findArtifacts(
                    "default", workspaceBinding.workspaceId(), "thread:production-file");
            assertThat(published).singleElement().satisfies(value -> {
                assertThat(value.id().value()).isEqualTo(structured.path("artifactId").asText());
                assertThat(value.provenance().runId().value()).isEqualTo(runId);
                assertThat(value.provenance().executionRef()).isEqualTo(projectedCallId);
                assertThat(value.provenance().sourceHash()).isEqualTo("sha256:" + sha);
                assertThat(value.payload().sha256()).isEqualTo("sha256:" + sha);
                assertThat(artifacts.loadArtifact("default", value)).isEqualTo(expected);
            });

            JsonNode changes = json.readTree(request(base, "GET",
                    location + "/workspace-changes?include_files=true", null).body());
            assertThat(changes.path("available").asBoolean()).as(changes.toString()).isTrue();
            assertThat(changes.path("files").toString()).contains(path, sha);

            HttpResponse<String> duplicate = request(
                    base, "POST", "/api/threads/production-file/runs/stream", approval);
            assertThat(duplicate.statusCode()).as(duplicate.body()).isEqualTo(409);
            assertThat(providerRequests).hasSize(2);
            assertThat(client.send(HttpRequest.newBuilder(artifact).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()).body()).isEqualTo(expected);

            assertThat(request(base, "POST", "/api/threads", "{\"thread_id\":\"production-file-rejected\"}")
                    .statusCode()).isEqualTo(200);
            HttpResponse<String> rejectedPending = request(
                    base, "POST", "/api/threads/production-file-rejected/runs/stream", runBody);
            assertThat(rejectedPending.statusCode()).as(rejectedPending.body()).isEqualTo(200);
            var rejectedRequestMatch = Pattern.compile("\\\"request_id\\\":\\\"([^\\\"]+)\\\"")
                    .matcher(rejectedPending.body());
            assertThat(rejectedRequestMatch.find()).as(rejectedPending.body()).isTrue();
            URI rejectedArtifact = base.resolve("/api/threads/production-file-rejected/artifacts" + path);
            assertThat(client.send(HttpRequest.newBuilder(rejectedArtifact).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(404);
            HttpResponse<String> rejected = request(
                    base,
                    "POST",
                    "/api/threads/production-file-rejected/runs/stream",
                    interactionResponse(rejectedRequestMatch.group(1), "reject"));
            assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(200);
            assertThat(client.send(HttpRequest.newBuilder(rejectedArtifact).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(404);

            System.out.println("W05_PRODUCTION_FILE_EVIDENCE run=" + runId
                    + " toolCall=call-production-write-1 size=" + expected.length + " sha256=" + sha
                    + " wrong_request=409 cross_thread=409 duplicate=409 rejected_file=404"
                    + " toolSummary=" + toolSummary);
            }
            try (var restarted = DeerFlowBootstrapApi.create(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataRoot,
                Map.of(
                        "HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF",
                        "env://W05_TEST_KEY",
                        "W05_TEST_KEY",
                        key))) {
            restarted.start();
            URI restartedBase = URI.create("http://127.0.0.1:" + restarted.address().getPort());
            JsonNode reloaded = json.readTree(
                    request(restartedBase, "GET", "/api/threads/production-file/state", null).body());
            JsonNode reloadedCall = firstMessageWith(reloaded, "tool_calls");
            assertThat(reloadedCall.path("tool_calls").get(0).path("id").asText())
                    .isEqualTo(persistedProjection[0]);
            JsonNode reloadedResult = firstToolResult(reloaded, persistedProjection[0]);
            assertThat(reloadedResult.path("additional_kwargs").path("haifa_tool_result").path("artifactId")
                            .asText())
                    .isEqualTo(persistedProjection[1]);
            assertThat(reloadedResult.path("additional_kwargs").path("haifa_tool_result").path("artifactSha256")
                            .asText())
                    .isEqualTo("sha256:" + persistedProjection[2]);
            HttpResponse<byte[]> restartedDownload = client.send(
                    HttpRequest.newBuilder(restartedBase.resolve(
                            "/api/threads/production-file/artifacts" + path))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(restartedDownload.statusCode()).isEqualTo(200);
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(restartedDownload.body())))
                    .isEqualTo(persistedProjection[2]);
            assertThat(restartedDownload.headers().firstValue("etag"))
                    .contains("\"" + persistedProjection[2] + "\"");
            System.out.println("W05_RESTART_DOWNLOAD_EVIDENCE artifact=" + persistedProjection[1]
                    + " sha256=" + persistedProjection[2] + " http=200");
            assertThat(reloaded.path("values").path("artifacts").toString()).contains(path);
            }
        } finally {
            provider.stop(0);
        }
    }

    private static JsonNode firstMessageWith(JsonNode state, String field) {
        for (JsonNode message : state.path("values").path("messages")) {
            if (message.has(field) && !message.path(field).isEmpty()) return message;
        }
        throw new AssertionError("missing message field " + field + ": " + state);
    }

    private static JsonNode firstToolResult(JsonNode state, String toolCallId) {
        for (JsonNode message : state.path("values").path("messages")) {
            if ("tool".equals(message.path("type").asText())
                    && toolCallId.equals(message.path("tool_call_id").asText())
                    && "write_file".equals(message.path("name").asText())) {
                return message;
            }
        }
        throw new AssertionError("missing Tool result " + toolCallId + ": " + state);
    }

    private static JsonNode firstHumanInputCard(JsonNode state) {
        for (JsonNode message : state.path("values").path("messages")) {
            if (message.path("artifact").path("human_input").hasNonNull("request_id")) return message;
        }
        throw new AssertionError("missing HumanInput card: " + state);
    }

    private static List<JsonNode> messagesNamed(JsonNode state, String name) {
        List<JsonNode> messages = new ArrayList<>();
        for (JsonNode message : state.path("values").path("messages")) {
            if (name.equals(message.path("name").asText())) messages.add(message);
        }
        return List.copyOf(messages);
    }

    private static String interactionResponse(String requestId, String option) {
        return """
                {"assistant_id":"lead_agent","input":{"messages":[{"id":"response","type":"human",
                "content":[{"type":"text","text":"%s"}],
                "additional_kwargs":{"hide_from_ui":true,"human_input_response":
                {"version":1,"kind":"human_input_response","source":"haifa_interaction",
                "request_id":"%s","response_kind":"option","option_id":"%s","value":"%s"}}}]},
                "stream_mode":["messages-tuple","updates","custom"],
                "on_disconnect":"continue","multitask_strategy":"reject"}
                """.formatted(option, requestId, option, option);
    }

    private HttpResponse<String> request(URI base, String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(30));
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}

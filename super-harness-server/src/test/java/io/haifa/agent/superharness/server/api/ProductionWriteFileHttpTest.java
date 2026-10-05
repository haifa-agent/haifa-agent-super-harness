package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.superharness.server.run.HaifaRunBridge;
import io.haifa.agent.superharness.server.testsupport.ControlledProvider;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import io.haifa.agent.superharness.server.testsupport.SseEvents;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceChangeLimits;
import io.haifa.agent.superharness.workspace.WorkspaceLimits;
import java.net.URI;
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
import org.junit.jupiter.api.Test;

/** Start new acceptance tests here: shared production Gateway/Provider, current Run approval and verified Artifact bytes. */
class ProductionWriteFileHttpTest {
    Path dataRoot;
    Path evidence;
    GatewayFixture.HttpEvidence http;
    private final ObjectMapper json = new ObjectMapper();
    @org.junit.jupiter.api.BeforeEach
    void prepare() throws Exception {
        dataRoot = GatewayFixture.dataDirectory();
        evidence = GatewayFixture.evidenceDirectory();
        http = new GatewayFixture.HttpEvidence(evidence, Duration.ofSeconds(30));
    }

    @org.junit.jupiter.api.AfterEach
    void close() throws InterruptedException { if (http != null) http.close(); }

    @Test
    void productionPendingWriteCancellationLeavesNoFileOrArtifactAfterLateApprovalAndRestart() throws Exception {
        String assignedRoot = System.getProperty("w05.pendingCancel.dataRoot");
        if (assignedRoot != null) {
            Files.createDirectories(Path.of(assignedRoot));
            dataRoot = Files.createTempDirectory(Path.of(assignedRoot), "pending-cancel-");
        }
        String evidenceRoot = System.getProperty("w05.pendingCancel.evidence");
        if (evidenceRoot != null) evidence = Path.of(evidenceRoot);
        if (evidenceRoot != null) { http.close(); http = new GatewayFixture.HttpEvidence(evidence, Duration.ofSeconds(30)); }
        Files.createDirectories(evidence);
        String threadId = "production-pending-cancel";
        String path = "/mnt/user-data/outputs/pending-cancel.txt";
        int gatewayPort = Integer.getInteger("w05.pendingCancel.gatewayPort", 0);
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        Map<String, String> environment = Map.of(
                "HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W05_TEST_KEY", "W05_TEST_KEY", key);
        String location;
        String runId;
        String toolCallId;
        try (var provider = new ControlledProvider(evidence, true, (request, call) ->
                ControlledProvider.call("provider-pending-cancel-" + call, "demo-provider-id",
                        "call-pending-cancel-" + call, "write_file",
                        Map.of("path", path, "content", "must never be written\n")))) {
            var calls = provider.calls;
            try (var api = GatewayFixture.production(dataRoot, gatewayPort, environment, evidence)) {
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
                        {"assistant_id":"lead_agent","input":{"messages":[{"type":"human","content":"create a file"}]},
                        "stream_mode":["messages-tuple","updates","custom"],
                        "context":{"model_name":"demo-model","mode":"flash","reasoning_effort":"minimal",
                        "thinking_enabled":false,"is_plan_mode":false,"subagent_enabled":false},
                        "on_disconnect":"continue","multitask_strategy":"reject"}
                        """;
                HttpResponse<String> pending = http.request(base, "POST", "/api/threads/" + threadId + "/runs/stream", runBody, "pending.sse");
                assertThat(pending.statusCode()).as(pending.body()).isEqualTo(200);
                assertThat(pending.body()).contains("event: end");
                location = pending.headers().firstValue("content-location").orElseThrow();
                runId = location.substring(location.lastIndexOf('/') + 1);
                JsonNode pendingRun = http.get(base, location, "pending-run.json");
                assertThat(pendingRun.path("run_id").asText()).isEqualTo(runId);
                assertThat(pendingRun.path("thread_id").asText()).isEqualTo(threadId);
                assertThat(pendingRun.path("diagnostics").path("sdk_status").asText()).isEqualTo("WAITING_APPROVAL");
                JsonNode pendingState = http.get(base, "/api/threads/" + threadId + "/state", "pending-state.json");
                JsonNode call = firstMessageWith(pendingState, "tool_calls").path("tool_calls").get(0);
                assertThat(call.path("name").asText()).isEqualTo("write_file");
                assertThat(call.path("args").path("path").asText()).isEqualTo(path);
                toolCallId = call.path("id").asText();
                JsonNode card = SseEvents.PendingApproval.locate(threadId, location, pendingRun, pendingState, evidence).card().path("artifact").path("human_input");
                assertThat(card.path("tool_call_id").asText()).isEqualTo(toolCallId);
                String requestId = card.path("request_id").asText();
                assertThat(requestId).isNotBlank();
                assertThat(json.readTree(Files.readString(evidence.resolve("provider-request-1.json")))
                        .path("tools").findValuesAsText("name")).contains("write_file");
                assertNoPendingWriteSideEffect(evidence, "pending", base, threadId, path, runId, toolCallId);

                HttpResponse<String> cancelled = http.request(base, "POST", location + "/cancel?action=interrupt&wait=0", "", "cancel.http");
                assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(202);
                awaitCancelled(evidence, base, location, runId, threadId);
                HttpResponse<String> repeated = http.request(base, "POST", location + "/cancel?action=interrupt&wait=0", "", "repeated-cancel.http");
                assertThat(repeated.statusCode()).as(repeated.body()).isEqualTo(409);
                assertThat(repeated.body()).contains("not cancellable");
                HttpResponse<String> lateApproval = http.request(base, "POST", "/api/threads/" + threadId + "/runs/stream", SseEvents.PendingApproval.reply(requestId, "approve"), "late-approval.http");
                assertThat(lateApproval.statusCode()).as(lateApproval.body()).isEqualTo(409);
                assertThat(calls).hasValue(1);
                assertNoPendingWriteSideEffect(evidence, "cancelled", base, threadId, path, runId, toolCallId);
                System.out.println("W05_PENDING_WRITE_CANCEL_EVIDENCE run=" + runId + " thread=" + threadId
                        + " toolCall=" + toolCallId + " interaction=" + requestId
                        + " sdk_status=CANCELLED http_status=interrupted cancel=202 repeated_cancel=409 late_approve=409");
            }
            // Closing the production Runtime joins its completion observers. Reopening the same
            // SQLite and workspace proves durable cancellation without a time-based quiet window.
            try (var restarted = GatewayFixture.production(dataRoot, gatewayPort, environment, evidence)) {
                URI base = restarted.base;
                awaitCancelled(evidence, base, location, runId, threadId);
                assertNoPendingWriteSideEffect(evidence, "restarted", base, threadId, path, runId, toolCallId);
                JsonNode runs = http.get(base, "/api/threads/" + threadId + "/runs", "restarted-runs.json");
                assertThat(runs.findValuesAsText("run_id")).containsExactly(runId);
                assertThat(calls).hasValue(1);
                assertThat(dataRoot.resolve("runtime/haifa-agent.sqlite")).exists();
                System.out.println("W05_PENDING_WRITE_CANCEL_EVIDENCE restart_same_run=true provider_calls=1"
                        + " production_file=false persisted_artifacts=0 dataRoot=" + dataRoot + " evidence=" + evidence);
            }
        }
    }

    private JsonNode awaitCancelled(Path evidence, URI base, String location, String runId, String threadId)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        JsonNode run;
        do {
            run = http.get(base, location, "cancel-status-latest.json");
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
        assertThat(http.request(base, "GET", "/api/threads/" + threadId + "/artifacts" + path, null, phase + "-download.http").statusCode()).isEqualTo(404);
        JsonNode state = http.get(base, "/api/threads/" + threadId + "/state", phase + "-state.json");
        assertThat(state.path("values").path("artifacts")).isEmpty();
        assertThat(messagesNamed(state, "write_file")).isEmpty();
        JsonNode run = http.get(base, "/api/threads/" + threadId + "/runs/" + runId, phase + "-run.json");
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

    @Test
    void productionApprovalGateRejectsMismatchedCrossThreadDuplicateAndRejectedWrites() throws Exception {
        byte[] expected = "真实文件内容\n".getBytes(StandardCharsets.UTF_8);
        String path = "/mnt/user-data/outputs/production.txt";
        try (var provider = new ControlledProvider(evidence, true, (request, call) -> {
            boolean hasToolResult = false;
            for (JsonNode message : request.path("messages")) {
                if ("tool".equals(message.path("role").asText())) { hasToolResult = true; break; }
            }
            return hasToolResult ? ControlledProvider.stop("provider-write-" + call, "demo-provider-id", "Created the file.")
                    : ControlledProvider.call("provider-write-" + call, "demo-provider-id",
                            "call-production-write-" + call, "write_file",
                            Map.of("path", path, "content", new String(expected, StandardCharsets.UTF_8)));
        })) {
            var providerRequests = provider.requests;
            String key = Base64.getEncoder().encodeToString(new byte[32]);
            String[] persistedProjection = new String[3];
            try (var api = GatewayFixture.production(dataRoot, 0, Map.of("HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W05_TEST_KEY",
                        "W05_TEST_KEY", key), evidence)) {
            URI base = api.base;
            String endpoint = "http://127.0.0.1:" + provider.port() + "/v1";
            String model = """
                    {"config":{"name":"demo-model","display_name":"Demo Model",
                    "provider":"openai-compatible","model":"demo-provider-id",
                    "base_url":"%s","enabled":true,"supports_vision":false,
                    "context_window":32768,"max_tokens":1024,"api_key":"controlled-key"},
                    "expected_revision":null}
                    """.formatted(endpoint);
            assertThat(http.request(base, "PUT", "/api/managed-models", model).statusCode()).isEqualTo(200);
            assertThat(http.request(base, "POST", "/api/threads", "{\"thread_id\":\"production-file\"}")
                    .statusCode()).isEqualTo(200);
            String runBody = """
                    {"assistant_id":"lead_agent","input":{"messages":[{"type":"human","content":"create a file"}]},
                    "stream_mode":["messages-tuple","updates","custom"],
                    "context":{"model_name":"demo-model","mode":"flash","reasoning_effort":"minimal",
                    "thinking_enabled":false,"is_plan_mode":false,"subagent_enabled":false},
                    "on_disconnect":"continue","multitask_strategy":"reject"}
                    """;
            HttpResponse<String> pending = http.request(base, "POST", "/api/threads/production-file/runs/stream", runBody);
            assertThat(pending.statusCode()).as(pending.body()).isEqualTo(200);
            String location = pending.headers().firstValue("content-location").orElseThrow();
            String runId = location.substring(location.lastIndexOf('/') + 1);
            var selected = api.pending("production-file", location);
            assertThat(selected.requestId()).as(pending.body()).isNotBlank();
            String requestId = selected.requestId();
            String pendingToolCallId = selected.card().path("artifact").path("human_input").path("tool_call_id").asText();
            assertThat(pendingToolCallId).as(pending.body()).isNotBlank();
            assertThat(pending.body()).contains("tool-call-" + pendingToolCallId + "-request");
            assertThat(providerRequests).hasSize(1);
            assertThat(providerRequests.getFirst().path("tools").findValuesAsText("name"))
                    .contains("write_file", "ask_clarification");
            URI artifact = base.resolve("/api/threads/production-file/artifacts" + path);
            assertThat(http.download(artifact).statusCode()).isEqualTo(404);
            JsonNode pendingState = json.readTree(
                    http.request(base, "GET", "/api/threads/production-file/state", null).body());
            JsonNode pendingToolCall = firstMessageWith(pendingState, "tool_calls");
            assertThat(pendingToolCall.path("tool_calls").get(0).path("name").asText()).isEqualTo("write_file");
            assertThat(pendingToolCall.path("tool_calls").get(0).path("args").path("path").asText())
                    .isEqualTo(path);
            JsonNode pendingCard = api.pending("production-file", location).card();
            assertThat(pendingCard.path("tool_call_id").asText()).isEqualTo(pendingCard.path("id").asText());
            assertThat(pendingCard.path("tool_call_id").asText()).isNotEqualTo(pendingToolCallId);
            assertThat(pendingCard.path("artifact").path("human_input").path("tool_call_id").asText())
                    .isEqualTo(pendingToolCallId);
            assertThat(pendingState.path("values").path("artifacts")).isEmpty();

            String wrongRequest = SseEvents.PendingApproval.reply("another-request", "approve");
            assertThat(http.request(base, "POST", "/api/threads/production-file/runs/stream", wrongRequest)
                    .statusCode()).isEqualTo(409);
            assertThat(http.request(base, "POST", "/api/threads", "{\"thread_id\":\"production-file-other\"}")
                    .statusCode()).isEqualTo(200);
            String crossThread = SseEvents.PendingApproval.reply(requestId, "approve");
            HttpResponse<String> crossThreadResponse = http.request(base, "POST",
                    "/api/threads/production-file-other/runs/stream", crossThread);
            assertThat(crossThreadResponse.statusCode()).as(crossThreadResponse.body()).isEqualTo(409);
            assertThat(http.download(artifact).statusCode()).isEqualTo(404);

            String approval = SseEvents.PendingApproval.reply(requestId, "approve");
            HttpResponse<String> resumed = api.approve("production-file", location);
            Files.writeString(evidence.resolve("approved-run.raw.sse"), resumed.body());
            assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
            assertThat(resumed.body()).contains("Created the file.");
            assertThat(resumed.body()).contains("tool-call-" + pendingToolCallId + "-result", "artifactId");
            assertThat(json.readTree(http.request(base, "GET", location, null).body()).path("status").asText())
                    .isEqualTo("success");
            assertThat(providerRequests).hasSize(2);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected));
            JsonNode runDiagnostics = json.readTree(http.request(base, "GET", location, null).body())
                    .path("diagnostics");
            assertThat(runDiagnostics.path("model_calls")).hasSize(2);
            assertThat(runDiagnostics.path("model_calls").findValuesAsText("model_id"))
                    .containsExactly("demo-provider-id", "demo-provider-id");
            JsonNode threadUsage = json.readTree(http.request(base, "GET", "/api/threads/production-file/token-usage", null).body());
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

            HttpResponse<byte[]> downloaded = http.download(artifact);
            assertThat(downloaded.statusCode()).isEqualTo(200);
            assertThat(downloaded.body()).isEqualTo(expected);
            assertThat(downloaded.headers().firstValue("etag")).contains("\"" + sha + "\"");

            JsonNode completedState = json.readTree(
                    http.request(base, "GET", "/api/threads/production-file/state", null).body());
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
                    http.request(base, "GET", "/api/threads/production-file/state", null).body());
            assertThat(repeatedState.path("values").path("messages")).isEqualTo(
                    completedState.path("values").path("messages"));
            JsonNode history = json.readTree(
                    http.request(base, "POST", "/api/threads/production-file/history", "{\"limit\":10}").body());
            JsonNode page = json.readTree(http.request(base, "GET", "/api/threads/production-file/messages/page?limit=50", null).body());
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

            JsonNode changes = json.readTree(http.request(base, "GET",
                    location + "/workspace-changes?include_files=true", null).body());
            assertThat(changes.path("available").asBoolean()).as(changes.toString()).isTrue();
            assertThat(changes.path("files").toString()).contains(path, sha);

            HttpResponse<String> duplicate = http.request(base, "POST", "/api/threads/production-file/runs/stream", approval);
            assertThat(duplicate.statusCode()).as(duplicate.body()).isEqualTo(409);
            assertThat(providerRequests).hasSize(2);
            assertThat(http.download(artifact).body()).isEqualTo(expected);

            assertThat(http.request(base, "POST", "/api/threads", "{\"thread_id\":\"production-file-rejected\"}")
                    .statusCode()).isEqualTo(200);
            HttpResponse<String> rejectedPending = http.request(base, "POST", "/api/threads/production-file-rejected/runs/stream", runBody);
            assertThat(rejectedPending.statusCode()).as(rejectedPending.body()).isEqualTo(200);
            String rejectedLocation = rejectedPending.headers().firstValue("content-location").orElseThrow();
            var rejectedSelection = api.pending("production-file-rejected", rejectedLocation);
            assertThat(rejectedSelection.requestId()).as(rejectedPending.body()).isNotBlank();
            URI rejectedArtifact = base.resolve("/api/threads/production-file-rejected/artifacts" + path);
            assertThat(http.download(rejectedArtifact).statusCode()).isEqualTo(404);
            HttpResponse<String> rejected = http.request(base,
                    "POST",
                    "/api/threads/production-file-rejected/runs/stream",
                    SseEvents.PendingApproval.reply(rejectedSelection.requestId(), "reject"));
            assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(200);
            assertThat(http.download(rejectedArtifact).statusCode()).isEqualTo(404);

            System.out.println("W05_PRODUCTION_FILE_EVIDENCE run=" + runId
                    + " toolCall=call-production-write-1 size=" + expected.length + " sha256=" + sha
                    + " wrong_request=409 cross_thread=409 duplicate=409 rejected_file=404"
                    + " toolSummary=" + toolSummary);
            }
            try (var restarted = GatewayFixture.production(dataRoot, 0, Map.of(
                        "HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF",
                        "env://W05_TEST_KEY",
                        "W05_TEST_KEY",
                        key), evidence)) {
            URI restartedBase = restarted.base;
            JsonNode reloaded = json.readTree(
                    http.request(restartedBase, "GET", "/api/threads/production-file/state", null).body());
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
            HttpResponse<byte[]> restartedDownload = http.download(restartedBase.resolve("/api/threads/production-file/artifacts" + path), Duration.ofSeconds(10));
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
        }
    }

    /**
     * W15-task-13: a Run that crosses an SDK budget threshold must keep its SSE stream open. The SDK
     * emits a {@code budget.threshold-reached} DeliveryLifecycle event; frozen DeerFlow has no wire
     * mapping for it, so the Gateway must skip it without writing an error frame or ending the
     * stream. The controlled Provider issues nine read-only Tool calls (16-call Run budget, so the
     * 50% threshold is crossed) and then answers. The same frame writer serves the live creator
     * stream and every replay/join, so this case checks the live stream and the terminal replay.
     */
    @Test
    void runStreamIgnoresSdkBudgetThresholdEventAndProjectsEveryToolMessage() throws Exception {
        int toolCalls = 9;
        String answer = "BUDGET_THRESHOLD_DONE";
        String threadId = "budget-threshold";
        try (var provider = new ControlledProvider(evidence, true, (request, call) ->
                toolMessages(request) < toolCalls
                        ? ControlledProvider.call("budget-provider-" + call, "demo-provider-id",
                                "budget-call-" + call, "list_uploaded_files", Map.of())
                        : ControlledProvider.stop("budget-provider-" + call, "demo-provider-id", answer))) {
            String key = Base64.getEncoder().encodeToString(new byte[32]);
            Map<String, String> environment = Map.of(
                    "HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W15_BUDGET_TEST_KEY",
                    "W15_BUDGET_TEST_KEY", key);
            try (var api = GatewayFixture.production(dataRoot, 0, environment, evidence)) {
                URI base = api.base;
                String model = """
                        {"config":{"name":"budget-model","display_name":"Budget Model",
                        "provider":"openai-compatible","model":"demo-provider-id",
                        "base_url":"http://127.0.0.1:%s/v1","enabled":true,"supports_vision":false,
                        "context_window":32768,"max_tokens":1024,"api_key":"controlled-key"},
                        "expected_revision":null}
                        """.formatted(provider.port());
                assertThat(http.request(base, "PUT", "/api/managed-models", model).statusCode()).isEqualTo(200);
                assertThat(http.request(base, "POST", "/api/threads", "{\"thread_id\":\"" + threadId + "\"}")
                        .statusCode()).isEqualTo(200);
                String runBody = """
                        {"assistant_id":"lead_agent","input":{"messages":[{"type":"human","content":"list files repeatedly"}]},
                        "stream_mode":["messages-tuple","updates","custom"],
                        "context":{"model_name":"budget-model","mode":"flash","reasoning_effort":"minimal",
                        "thinking_enabled":false,"is_plan_mode":false,"subagent_enabled":false},
                        "on_disconnect":"continue","multitask_strategy":"reject"}
                        """;
                HttpResponse<String> run = http.request(base, "POST",
                        "/api/threads/" + threadId + "/runs/stream", runBody, "budget-threshold.sse");
                assertThat(run.statusCode()).as(run.body()).isEqualTo(200);
                String location = run.headers().firstValue("content-location").orElseThrow();
                String runId = location.substring(location.lastIndexOf('/') + 1);

                var events = SseEvents.read(run.body());
                // 1. The threshold event must not surface as an error or end the stream.
                assertThat(events.named("error")).isEmpty();
                assertThat(run.body()).doesNotContain("unsupported_event_projection");
                // 2. The stream ends normally and the model's final answer is projected.
                assertThat(events.named("end")).hasSize(1);
                assertThat(run.body()).contains(answer);
                // 3. Every model-issued Tool call is projected as a request and a result, including
                //    the calls before and after the threshold.
                assertThat(toolRequests(events)).as("Tool request messages projected").isEqualTo(toolCalls);
                assertThat(toolResults(events)).as("Tool result messages projected").isEqualTo(toolCalls);
                // 4. The Run itself succeeded.
                assertThat(http.get(base, location, "budget-run.json").path("status").asText()).isEqualTo("success");
                // 5. The threshold event really happened; otherwise this case could pass vacuously.
                var sdkEvents = sdkRunEvents(api.api, runId);
                assertThat(sdkEvents.stream().map(AgentRunEvent::eventType).toList())
                        .as("SDK budget threshold event").contains("budget.threshold-reached");
                // 6. Replays of the finished Run use the same writer and must not error either: the
                //    existing terminal-resume pattern and a full join from the beginning.
                long terminalSequence = lastSseId(run.body());
                HttpResponse<String> resumed = api.request(
                        "GET", location + "/stream", null, (terminalSequence - 1) + "-0");
                assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
                assertThat(SseEvents.read(resumed.body()).named("error")).isEmpty();
                assertThat(resumed.body()).contains("event: end");
                HttpResponse<String> rejoin = http.request(
                        base, "GET", location + "/stream", null, "budget-replay.sse");
                assertThat(rejoin.statusCode()).as(rejoin.body()).isEqualTo(200);
                assertThat(SseEvents.read(rejoin.body()).named("error")).isEmpty();
                System.out.println("W15_BUDGET_THRESHOLD_EVIDENCE run=" + runId + " tool_calls=" + toolCalls
                        + " requests=" + toolCalls + " results=" + toolCalls
                        + " threshold_event=true replay_errors=0 evidence=" + evidence);
            }
        }
    }

    private static long toolMessages(JsonNode request) {
        long toolMessages = 0;
        for (JsonNode message : request.path("messages")) {
            if ("tool".equals(message.path("role").asText())) toolMessages++;
        }
        return toolMessages;
    }

    private static long toolRequests(SseEvents events) {
        long requests = 0;
        for (var event : events.named("messages")) {
            JsonNode tuple = event.data();
            if (!tuple.isArray() || tuple.size() != 2) continue;
            if (!"tools".equals(tuple.get(1).path("langgraph_node").asText())) continue;
            if (!tuple.get(0).path("tool_calls").isEmpty()) requests++;
        }
        return requests;
    }

    private static long toolResults(SseEvents events) {
        long results = 0;
        for (var event : events.named("messages")) {
            JsonNode tuple = event.data();
            if (!tuple.isArray() || tuple.size() != 2) continue;
            if (!"tools".equals(tuple.get(1).path("langgraph_node").asText())) continue;
            if ("tool".equals(tuple.get(0).path("type").asText())) results++;
        }
        return results;
    }

    private static long lastSseId(String transcript) {
        var matcher = java.util.regex.Pattern.compile("(?m)^id: (\\d+)-0$").matcher(transcript);
        long last = 0;
        while (matcher.find()) last = Long.parseLong(matcher.group(1));
        return last;
    }

    /** Reads the actual SDK event feed of one Run through the attached product Bridge. */
    private static List<AgentRunEvent> sdkRunEvents(DeerFlowBootstrapApi api, String runId) throws Exception {
        var runsField = DeerFlowThreadRunApi.class.getDeclaredField("runs");
        runsField.setAccessible(true);
        Object record = ((Map<?, ?>) runsField.get(api.threadRunApi())).get(runId);
        assertThat(record).as("cached RunRecord for %s", runId).isNotNull();
        var bridgeField = record.getClass().getDeclaredField("bridge");
        bridgeField.setAccessible(true);
        HaifaRunBridge bridge = (HaifaRunBridge) bridgeField.get(record);
        AgentRunId id = new AgentRunId(runId);
        var cursor = bridge.beforeFirstEvent(id);
        List<AgentRunEvent> events = new ArrayList<>();
        while (true) {
            var page = bridge.events(id, cursor, 200);
            events.addAll(page.items());
            if (!page.hasMore()) return events;
            cursor = page.nextCursor();
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

    private static List<JsonNode> messagesNamed(JsonNode state, String name) {
        List<JsonNode> messages = new ArrayList<>();
        for (JsonNode message : state.path("values").path("messages")) {
            if (name.equals(message.path("name").asText())) messages.add(message);
        }
        return List.copyOf(messages);
    }

}

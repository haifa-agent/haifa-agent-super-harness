package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceChangeLimits;
import io.haifa.agent.superharness.workspace.WorkspaceLimits;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rework-1 L2 proof for the "exact Gateway process restart" half of case V2-M4-03: the production
 * Super Harness Gateway process (the same {@code SuperHarnessServer} main used in production) is
 * started twice against the same data-root and continuation key. The first process admits a Run, the
 * production {@code write_file} policy raises a pending approval, then the exact process is stopped;
 * a brand-new Gateway process recovers the pending card from the authoritative SDK/SQLite facts and
 * the approval is submitted over the same HTTP request shape the frozen DeerFlow UI uses. The tool
 * must execute exactly once and exactly one Artifact must be published whose downloaded bytes match
 * the workspace file.
 *
 * <p>Unlike {@link W05CrossProcessPendingApprovalRestartTest} (which approves through the public SDK
 * in a child JVM) this test drives the full Gateway HTTP projection, so it also exercises the
 * frozen-UI reconnect/join card re-projection path. It is still L2: the model is a controlled
 * OpenAI-compatible Provider on loopback, not a real model, and no frozen UI is driven.
 */
class W05ExactGatewayProcessRestartApprovalTest {
    private static final int GATEWAY_PORT = 20618;
    private static final String THREAD_ID = "restart-gateway-thread";
    private static final String ARTIFACT_PATH = "/mnt/user-data/outputs/restart-approved.txt";
    private static final String CONTENT = "restart approved\n";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final List<JsonNode> providerRequests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger providerCalls = new AtomicInteger();
    private final SecretKey continuationKey = randomKey();

    @TempDir
    Path dataDirectory;

    private HttpServer provider;
    private Process gateway;

    @Test
    void exactGatewayProcessRestartRecoversThePendingApprovalOverTheFrozenHttpPathAndWritesExactlyOnce()
            throws Exception {
        requirePortAvailable(GATEWAY_PORT);
        startProvider();
        try {
            URI base = URI.create("http://127.0.0.1:" + GATEWAY_PORT);
            gateway = startGateway();
            configureManagedModel(base);
            assertThat(post(base, "/api/threads", "{\"thread_id\":\"" + THREAD_ID + "\"}")
                            .statusCode())
                    .isEqualTo(200);

            HttpResponse<String> requested =
                    post(base, "/api/threads/" + THREAD_ID + "/runs/stream", runBody());
            assertThat(requested.statusCode()).as(requested.body()).isEqualTo(200);
            String location = requested.headers().firstValue("content-location").orElseThrow();
            String runId = location.substring(location.lastIndexOf('/') + 1);
            JsonNode pendingCard = humanInputCard(requested.body());
            String requestId = pendingCard.path("artifact").path("human_input").path("request_id").asText();
            String toolCallId = pendingCard.path("artifact").path("human_input").path("tool_call_id").asText();
            String cardId = pendingCard.path("id").asText();
            assertThat(requestId).isNotBlank();
            assertThat(toolCallId).isNotBlank();
            assertThat(pendingCard.path("artifact").path("human_input").path("options").findValuesAsText("id"))
                    .containsExactly("approve", "reject");
            assertThat(getJson(base, location).path("status").asText()).isEqualTo("interrupted");
            assertThat(providerRequests).hasSize(1);

            // Stop the exact Gateway process. The durable SDK/SQLite facts must survive it.
            stopGateway();
            awaitPortAvailable(GATEWAY_PORT);

            // A brand-new Gateway process on the same data-root and continuation key.
            gateway = startGateway();
            URI restarted = URI.create("http://127.0.0.1:" + GATEWAY_PORT);

            // (1) Frozen-UI HTTP read path: force the accepted replay gap, then resume from the
            // retained tail. The restarted process must re-project the same pending card from the
            // authoritative SDK pending view instead of returning a heartbeat-only stream.
            HttpResponse<String> gap = get(restarted, location + "/stream", "-1");
            assertThat(gap.statusCode()).as(gap.body()).isEqualTo(200);
            assertThat(gap.body()).contains("event: gap", "stream_replay_gap");
            JsonNode gapData = firstEventData(gap.body(), "gap");
            assertThat(gapData.path("run_id").asText()).isEqualTo(runId);
            assertThat(gapData.path("recovery").asText()).isEqualTo("reload_durable_state");
            HttpResponse<String> resumed =
                    get(restarted, location + "/stream", gapData.path("latest_available_event_id").asText());
            assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
            assertThat(resumed.body()).contains("event: messages", "event: end");
            JsonNode resumedCard = humanInputCard(resumed.body());
            assertThat(resumedCard.path("id").asText()).isEqualTo(cardId);
            assertThat(resumedCard.path("artifact").path("human_input").path("request_id").asText())
                    .isEqualTo(requestId);
            assertThat(resumedCard.path("artifact").path("human_input").path("tool_call_id").asText())
                    .isEqualTo(toolCallId);

            // The frozen UI reload path reads the same authoritative pending card and ToolCall id.
            JsonNode state = getJson(restarted, "/api/threads/" + THREAD_ID + "/state");
            JsonNode stateCard = firstHumanInputCard(state);
            assertThat(stateCard.path("artifact").path("human_input").path("request_id").asText())
                    .isEqualTo(requestId);
            assertThat(stateCard.path("artifact").path("human_input").path("tool_call_id").asText())
                    .isEqualTo(toolCallId);
            assertThat(firstToolCallMessage(state).path("tool_calls").get(0).path("id").asText())
                    .isEqualTo(toolCallId);
            assertThat(getJson(restarted, location).path("status").asText()).isEqualTo("interrupted");
            assertThat(providerRequests).hasSize(1);

            // (2) Frozen-UI HTTP approval over the restarted Gateway.
            HttpResponse<String> approved =
                    post(restarted, "/api/threads/" + THREAD_ID + "/runs/stream", approvalBody(requestId));
            assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
            assertThat(approved.body()).contains("Created the file.", "event: end");
            assertThat(getJson(restarted, location).path("status").asText()).isEqualTo("success");
            assertThat(providerRequests).hasSize(2);

            // (3) exactly-once write_file and exactly one Artifact whose bytes match the workspace.
            byte[] expected = CONTENT.getBytes(StandardCharsets.UTF_8);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected));
            URI artifact = restarted.resolve("/api/threads/" + THREAD_ID + "/artifacts" + ARTIFACT_PATH);
            HttpResponse<byte[]> downloaded = client.send(
                    HttpRequest.newBuilder(artifact).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(downloaded.statusCode()).isEqualTo(200);
            assertThat(downloaded.body()).isEqualTo(expected);
            assertThat(downloaded.headers().firstValue("etag")).contains("\"" + sha + "\"");
            assertThat(new String(downloaded.body(), StandardCharsets.UTF_8).lines().count())
                    .as("the written file must contain exactly one line")
                    .isEqualTo(1);
            assertThat(getJson(restarted, "/api/threads/" + THREAD_ID + "/state")
                            .path("values")
                            .path("artifacts")
                            .toString())
                    .contains(ARTIFACT_PATH);

            // (4) a second identical approval is rejected and must not re-run the tool.
            HttpResponse<String> duplicate =
                    post(restarted, "/api/threads/" + THREAD_ID + "/runs/stream", approvalBody(requestId));
            assertThat(duplicate.statusCode()).as(duplicate.body()).isEqualTo(409);
            assertThat(providerRequests).hasSize(2);

            System.out.println("W05_GATEWAY_RESTART_EVIDENCE run=" + runId
                    + " request=" + requestId + " toolCall=" + toolCallId + " card=" + cardId
                    + " artifactPath=" + ARTIFACT_PATH + " sha256=" + sha
                    + " providerCalls=" + providerCalls.get() + " duplicate=409");
        } finally {
            stopGateway();
            stopProvider();
        }
        // After the Gateway is fully stopped, one authoritative Artifact must remain and its bytes
        // must match both the download and the on-disk workspace file.
        assertWorkspaceArtifactExactlyOnceAndMatchingBytes();
        awaitPortAvailable(GATEWAY_PORT);
    }

    private void assertWorkspaceArtifactExactlyOnceAndMatchingBytes() throws Exception {
        byte[] expected = CONTENT.getBytes(StandardCharsets.UTF_8);
        String expectedSha =
                "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected));
        Path workspaceRoot = dataDirectory.resolve("workspace");
        var projects = new PersistentProjectWorkspaceService(
                workspaceRoot, WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
        var artifacts = new RestrictedWorkspaceService(workspaceRoot, WorkspaceLimits.defaults());
        var binding = projects.requireThreadWorkspace("default", THREAD_ID);
        // The working file the production write_file tool wrote must equal the downloaded bytes.
        assertThat(artifacts.readVerifiedBytes("default", binding.workspaceId(), ARTIFACT_PATH))
                .isEqualTo(expected);
        var published = artifacts.findArtifacts("default", binding.workspaceId(), "thread:" + THREAD_ID);
        assertThat(published).singleElement().satisfies(value -> {
            assertThat(artifacts.loadArtifact("default", value)).isEqualTo(expected);
            assertThat(value.payload().sha256()).isEqualTo(expectedSha);
        });
    }

    private void configureManagedModel(URI base) throws Exception {
        String endpoint = "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1";
        String model = """
                {"config":{"name":"demo-model","display_name":"Demo Model",
                "provider":"openai-compatible","model":"demo-provider-id",
                "base_url":"%s","enabled":true,"supports_vision":false,
                "context_window":8192,"max_tokens":1024,"api_key":"controlled-key"},
                "expected_revision":null}
                """.formatted(endpoint);
        HttpResponse<String> saved = put(base, "/api/managed-models", model);
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
    }

    private void startProvider() throws IOException {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            providerRequests.add(request);
            int call = providerCalls.getAndIncrement();
            boolean hasToolResult = false;
            for (JsonNode message : request.path("messages")) {
                if ("tool".equals(message.path("role").asText())) {
                    hasToolResult = true;
                    break;
                }
            }
            String responseId = "provider-write-" + (call + 1);
            String chunk;
            if (!hasToolResult) {
                String arguments = json.writeValueAsString(Map.of("path", ARTIFACT_PATH, "content", CONTENT));
                chunk = json.writeValueAsString(Map.of(
                        "id", responseId, "model", "demo-provider-id",
                        "choices", List.of(Map.of("index", 0, "finish_reason", "tool_calls",
                                "delta", Map.of("tool_calls", List.of(Map.of(
                                        "index", 0, "id", "call-restart-write-" + (call + 1),
                                        "type", "function",
                                        "function", Map.of("name", "write_file", "arguments", arguments))))))));
            } else {
                chunk = json.writeValueAsString(Map.of(
                        "id", responseId, "model", "demo-provider-id",
                        "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                                "delta", Map.of("content", "Created the file.")))));
            }
            byte[] response = ("data: " + chunk + "\n\n"
                            + "data: {\"id\":\"" + responseId + "\",\"model\":\"demo-provider-id\","
                            + "\"choices\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":3}}\n\n"
                            + "data: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        provider.start();
    }

    private Process startGateway() throws Exception {
        String javaHome = System.getProperty("java.home");
        String javaExecutable = javaHome + (isWindows() ? "\\bin\\java.exe" : "/bin/java");
        Path argumentFile = dataDirectory.resolve("gateway-java-args.txt");
        List<String> lines = new ArrayList<>();
        lines.add("-cp");
        lines.add(quote(System.getProperty("java.class.path").replace('\\', '/')));
        lines.add(SuperHarnessServer.class.getName());
        Files.write(
                argumentFile,
                lines,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);

        Path logFile = dataDirectory.resolve("gateway-" + System.nanoTime() + ".log");
        ProcessBuilder builder = new ProcessBuilder(javaExecutable, "@" + argumentFile.toString());
        Map<String, String> environment = builder.environment();
        environment.put("SUPER_HARNESS_PROFILE", "local");
        environment.put("DEER_FLOW_AUTH_DISABLED", "1");
        environment.put("SUPER_HARNESS_BIND_ADDRESS", "127.0.0.1");
        environment.put("SUPER_HARNESS_PORT", Integer.toString(GATEWAY_PORT));
        environment.put("SUPER_HARNESS_DATA_DIR", dataDirectory.toAbsolutePath().normalize().toString());
        environment.put("HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W05_PROCESS_KEY");
        environment.put("W05_PROCESS_KEY", Base64.getEncoder().encodeToString(continuationKey.getEncoded()));
        builder.redirectErrorStream(true);
        builder.redirectOutput(logFile.toFile());
        Process process = builder.start();
        awaitHealthy(process, logFile);
        return process;
    }

    private void awaitHealthy(Process process, Path logFile) throws Exception {
        URI health = URI.create("http://127.0.0.1:" + GATEWAY_PORT + "/health");
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new AssertionError(
                        "Gateway process exited early (code " + process.exitValue() + "):\n" + readLog(logFile));
            }
            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(health).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) return;
            } catch (IOException notReadyYet) {
                // The listener is not bound yet; retry until the bounded deadline.
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Gateway process did not become healthy:\n" + readLog(logFile));
    }

    private void stopGateway() throws Exception {
        Process process = gateway;
        gateway = null;
        if (process == null) return;
        process.destroy();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        assertThat(process.isAlive()).as("Gateway process %s must stop", process.pid()).isFalse();
    }

    private void stopProvider() {
        if (provider != null) {
            provider.stop(0);
            provider = null;
        }
    }

    private static void requirePortAvailable(int port) throws IOException {
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        }
    }

    private static void awaitPortAvailable(int port) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try {
                requirePortAvailable(port);
                return;
            } catch (IOException stillBound) {
                last = stillBound;
                Thread.sleep(200);
            }
        }
        throw new AssertionError("port " + port + " was not released after the Gateway stopped", last);
    }

    private static String readLog(Path logFile) {
        try {
            return Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "<no log>";
        } catch (IOException failure) {
            return "<unreadable log: " + failure.getMessage() + ">";
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private static SecretKey randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new SecretKeySpec(key, "AES");
    }

    private String runBody() {
        return """
                {"assistant_id":"lead_agent","input":{"messages":[{"type":"human","content":"create a file"}]},
                "stream_mode":["messages-tuple","updates","custom"],
                "context":{"model_name":"demo-model","mode":"flash","reasoning_effort":"minimal",
                "thinking_enabled":false,"is_plan_mode":false,"subagent_enabled":false},
                "on_disconnect":"continue","multitask_strategy":"reject"}
                """;
    }

    private String approvalBody(String requestId) {
        return """
                {"assistant_id":"lead_agent","input":{"messages":[{"id":"response","type":"human",
                "content":[{"type":"text","text":"approve"}],
                "additional_kwargs":{"hide_from_ui":true,"human_input_response":
                {"version":1,"kind":"human_input_response","source":"haifa_interaction",
                "request_id":"%s","response_kind":"option","option_id":"approve","value":"approve"}}}]},
                "stream_mode":["messages-tuple","updates","custom"],
                "on_disconnect":"continue","multitask_strategy":"reject"}
                """.formatted(requestId);
    }

    private HttpResponse<String> post(URI base, String path, String body) throws Exception {
        return sendBounded(
                HttpRequest.newBuilder(base.resolve(path))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                60);
    }

    private HttpResponse<String> put(URI base, String path, String body) throws Exception {
        return sendBounded(
                HttpRequest.newBuilder(base.resolve(path))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .PUT(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                30);
    }

    private HttpResponse<String> get(URI base, String path, String lastEventId) throws Exception {
        return sendBounded(
                HttpRequest.newBuilder(base.resolve(path))
                        .header("Last-Event-ID", lastEventId)
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .build(),
                20);
    }

    private JsonNode getJson(URI base, String path) throws Exception {
        HttpResponse<String> response = sendBounded(
                HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(20)).GET().build(),
                30);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    /**
     * A bounded request. An SSE stream can keep sending heartbeats forever when the projection is
     * broken, so a plain request timeout is not enough; the whole exchange gets an explicit deadline
     * and the failure is reported instead of hanging the suite.
     */
    private HttpResponse<String> sendBounded(HttpRequest request, long timeoutSeconds) throws Exception {
        try {
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .get();
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof java.util.concurrent.TimeoutException timeout) {
                throw new AssertionError(
                        "HTTP request did not complete within " + timeoutSeconds + "s: " + request.uri(),
                        timeout);
            }
            if (cause instanceof Exception exception) throw exception;
            throw failure;
        }
    }

    private JsonNode humanInputCard(String transcript) throws Exception {
        for (JsonNode message : messageEvents(transcript)) {
            if (message.path("artifact").path("human_input").hasNonNull("request_id")) return message;
        }
        throw new AssertionError("missing human input card: " + transcript);
    }

    private List<JsonNode> messageEvents(String transcript) throws Exception {
        Matcher matcher = Pattern.compile("event: messages\\ndata: ([^\\n]+)\\n").matcher(transcript);
        List<JsonNode> messages = new ArrayList<>();
        while (matcher.find()) {
            JsonNode tuple = json.readTree(matcher.group(1));
            if (tuple.isArray() && !tuple.isEmpty()) messages.add(tuple.get(0));
        }
        assertThat(messages).as("SSE transcript: %s", transcript).isNotEmpty();
        return List.copyOf(messages);
    }

    private JsonNode firstEventData(String transcript, String eventName) throws Exception {
        Matcher matcher = Pattern.compile("event: " + Pattern.quote(eventName) + "\\ndata: ([^\\n]+)\\n")
                .matcher(transcript);
        assertThat(matcher.find()).as("SSE transcript must contain %s: %s", eventName, transcript).isTrue();
        return json.readTree(matcher.group(1));
    }

    private static JsonNode firstHumanInputCard(JsonNode state) {
        for (JsonNode message : state.path("values").path("messages")) {
            if (message.path("artifact").path("human_input").hasNonNull("request_id")) return message;
        }
        throw new AssertionError("missing human input card: " + state);
    }

    private static JsonNode firstToolCallMessage(JsonNode state) {
        for (JsonNode message : state.path("values").path("messages")) {
            if (!message.path("tool_calls").isEmpty()) return message;
        }
        throw new AssertionError("missing tool call message: " + state);
    }
}

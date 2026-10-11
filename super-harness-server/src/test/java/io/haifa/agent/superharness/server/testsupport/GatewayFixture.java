package io.haifa.agent.superharness.server.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.server.api.DeerFlowBootstrapApi;
import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
import java.io.InputStream;
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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;

/** Shared production SDK/HTTP assembly; feature assertions remain in their tests. */
public final class GatewayFixture implements AutoCloseable {
    public static final ObjectMapper JSON = new ObjectMapper();

    public static Path dataDirectory() throws Exception {
        Path root = Path.of(System.getProperty("m3.dataRoot", System.getenv().getOrDefault("ENG_ACCEPTANCE_DATA_ROOT", "../local-tmp/acceptance-fixtures/data")));
        return Files.createTempDirectory(Files.createDirectories(root), "case-");
    }

    public static ObjectNode safeRunSummary(JsonNode run) {
        ObjectNode summary = JSON.createObjectNode();
        for (String field : List.of("run_id", "thread_id", "status")) summary.set(field, safeCode(run.path(field)));
        var diagnostics = run.path("diagnostics");
        ObjectNode safe = summary.putObject("diagnostics");
        for (String field : List.of("sdk_status", "error_code", "diagnostic_id", "stop_reason")) {
            safe.set(field, safeCode(diagnostics.path(field)));
        }
        for (String group : List.of("model_calls", "tool_calls")) {
            var entries = safe.putArray(group);
            for (JsonNode item : diagnostics.path(group)) {
                ObjectNode entry = entries.addObject();
                for (String field : List.of("model_call_id", "tool_call_id", "run_id", "name", "status", "error_code",
                        "reason_code", "finish_reason")) {
                    if (item.has(field)) entry.set(field, safeCode(item.path(field)));
                }
            }
        }
        return summary;
    }

    static JsonNode safeCode(JsonNode value) {
        return value.isTextual() && value.asText().matches("[A-Za-z0-9_.:-]{1,128}") ? value : JSON.nullNode();
    }

    public static Path evidenceDirectory() throws Exception {
        return Files.createDirectories(Path.of(System.getProperty("m3.evidence", System.getenv().getOrDefault("ENG_ACCEPTANCE_EVIDENCE_ROOT", "../local-tmp/acceptance-fixtures/evidence")))
                .resolve("case-" + UUID.randomUUID()));
    }

    public static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("test hash unavailable", exception);
        }
    }

    public static String drain(InputStream stream) throws Exception {
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        var task = executor.submit(() -> new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        try {
            return task.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            stream.close();
            task.cancel(true);
            executor.shutdownNow();
        }
    }

    public final Path data;
    public final Path evidence;
    public final DeerFlowBootstrapApi api;
    public final URI base;
    public final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final AtomicInteger records = new AtomicInteger();

    public GatewayFixture(Path data, ControlledModel model) throws Exception {
        this(data, model, evidenceDirectory());
    }

    public GatewayFixture(Path data, ControlledModel model, Path evidence) throws Exception {
        this(data, DeerFlowBootstrapApi.createForTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0), data,
                ignored -> LocalProbeModelTestSupport.binding(model), new SecretKeySpec(new byte[32], "AES")), evidence);
    }

    public GatewayFixture(Path data, DeerFlowBootstrapApi api) throws Exception {
        this(data, api, evidenceDirectory());
    }

    public HttpResponse<String> request(String method, String path, JsonNode body) throws Exception {
        return request(method, path, body, null);
    }

    public HttpResponse<String> request(String method, String path, JsonNode body, String cursor) throws Exception {
        var builder = builder(method, path, body);
        if (cursor != null) builder.header("Last-Event-ID", cursor);
        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        Files.writeString(evidence.resolve("http-" + records.incrementAndGet() + ".summary.json"),
                JSON.writeValueAsString(Map.of("method", method, "path", path, "status", response.statusCode(),
                        "body_chars", response.body().length(), "body_sha256", hash(response.body()),
                        "content_location", response.headers().firstValue("content-location").orElse(""))));
        if ("GET".equals(method) && path.matches("/api/threads/[^/]+/runs/[^/]+")
                && !path.endsWith("/stream") && response.statusCode() == 200
                && response.headers().firstValue("content-type")
                        .map(value -> value.split(";", 2)[0].trim())
                        .filter(value -> value.equalsIgnoreCase("application/json")).isPresent()) {
            JsonNode run = JSON.readTree(response.body());
            if (run.has("diagnostics")) {
                Files.writeString(evidence.resolve("run-" + records.get() + ".safe-diagnostics.json"),
                        JSON.writeValueAsString(safeRunSummary(run)));
            }
        }
        return response;
    }

    public HttpRequest.Builder builder(String method, String path, JsonNode body) {
        return HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body.toString()));
    }

    public JsonNode get(String path) throws Exception {
        var response = request("GET", path, null);
        assertThat(response.statusCode()).as("GET status; body hash=%s", hash(response.body())).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    public ObjectNode seed(String name, String soul) throws Exception {
        ObjectNode agent = JSON.createObjectNode().put("name", name).put("display_name", "Display " + name)
                .put("description", "description " + name).put("soul", soul);
        agent.putArray("skills");
        agent.putArray("mcp_plugins");
        var response = request("POST", "/api/agents", agent);
        assertThat(response.statusCode()).as("seed status; body hash=%s", hash(response.body())).isEqualTo(201);
        return (ObjectNode) JSON.readTree(response.body());
    }

    public JsonNode agent(String name) throws Exception {
        return get("/api/agents/" + name);
    }

    public void update(String name, JsonNode update) throws Exception {
        assertThat(request("PUT", "/api/agents/" + name, update).statusCode()).isEqualTo(200);
    }

    public void thread(String name) throws Exception {
        assertThat(request("POST", "/api/threads", JSON.createObjectNode().put("thread_id", name)).statusCode())
                .isEqualTo(200);
    }

    public ObjectNode input(String agent, Boolean bootstrap, String message) {
        ObjectNode body = JSON.createObjectNode().put("assistant_id", "lead_agent")
                .put("on_disconnect", "continue");
        body.putObject("input").putArray("messages").addObject().put("id", UUID.randomUUID().toString())
                .put("type", "human").put("content", message);
        body.putArray("stream_mode").add("messages-tuple").add("updates").add("custom");
        ObjectNode context = body.putObject("context").put("mode", "flash");
        if (agent != null) context.put("agent_name", agent);
        if (bootstrap != null) context.put("is_bootstrap", bootstrap);
        return body;
    }

    public HttpResponse<String> run(String thread, String agent, Boolean bootstrap) throws Exception {
        return request("POST", "/api/threads/" + thread + "/runs/stream", input(agent, bootstrap, "execute fixture"));
    }

    public String completed(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as("Run status; body hash=%s", hash(response.body())).isEqualTo(200);
        assertThat(response.body().contains("event: end")).as("bounded stream ended").isTrue();
        String location = response.headers().firstValue("content-location").orElseThrow();
        JsonNode run = get(location);
        assertThat(run.path("diagnostics").path("sdk_status").asText())
                .as("public safe Run diagnostics: %s", safeRunSummary(run)).isEqualTo("COMPLETED");
        return location;
    }

    public JsonNode state(String thread) throws Exception {
        return get("/api/threads/" + thread + "/state");
    }

    public List<JsonNode> results(String thread, String tool) throws Exception {
        var messages = state(thread).path("values").path("messages");
        var results = new java.util.ArrayList<JsonNode>();
        for (JsonNode message : messages) {
            if ("tool".equals(message.path("type").asText()) && tool.equals(message.path("name").asText())) {
                results.add(message);
            }
        }
        return results;
    }

    @Override
    public void close() throws Exception {
        try { api.close(); } finally { closeClient(client); }
        portReleased(base.getPort());
    }

    public SseEvents.PendingApproval pending(String thread, String runLocation) throws Exception {
        HttpResponse<String> response = request("GET", runLocation, null);
        assertThat(response.statusCode()).as("Current Run %s must exist (HTTP %s)", runLocation, response.statusCode())
                .isEqualTo(200);
        return SseEvents.PendingApproval.locate(thread, runLocation, JSON.readTree(response.body()), state(thread), evidence);
    }

    String currentPendingRun(String thread) throws Exception {
        var current = new java.util.ArrayList<String>();
        for (JsonNode run : get("/api/threads/" + thread + "/runs")) {
            String location = "/api/threads/" + thread + "/runs/" + run.path("run_id").asText();
            if (get(location).path("diagnostics").path("sdk_status").asText().equals("WAITING_APPROVAL")) current.add(location);
        }
        assertThat(current).as("Exactly one current pending Run for thread %s", thread).hasSize(1);
        return current.getFirst();
    }

    public String approvalId(String thread) throws Exception { return approvalId(thread, currentPendingRun(thread)); }
    public String approvalId(String thread, String runLocation) throws Exception { return pending(thread, runLocation).requestId(); }
    public HttpResponse<String> approve(String thread) throws Exception { return approve(thread, currentPendingRun(thread)); }

    public HttpResponse<String> approve(String thread, String runLocation) throws Exception {
        String selectedRequestId = approvalId(thread, runLocation);
        HttpResponse<String> response = request("POST", "/api/threads/" + thread + "/runs/stream",
                JSON.readTree(SseEvents.PendingApproval.reply(selectedRequestId, "approve")));
        String currentRunId = runLocation.substring(runLocation.lastIndexOf('/') + 1);
        var summary = JSON.createObjectNode().put("evidence_kind", "derived_safe_summary")
                .put("current_run_id", currentRunId).put("submitted_request_id", selectedRequestId)
                .put("http_status", response.statusCode())
                .put("content_location", response.headers().firstValue("content-location").orElse(""));
        Files.writeString(evidence.resolve("approval-" + currentRunId + ".submission.safe-summary.json"),
                JSON.writeValueAsString(summary));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-location")).contains(runLocation);
        var joined = request("GET", runLocation + "/stream", null);
        if (!SseEvents.read(joined.body()).named("gap").isEmpty()) {
            var gap = SseEvents.read(joined.body()).event("gap");
            assertThat(gap.path("run_id").asText()).isEqualTo(currentRunId);
            String cursor = gap.path("latest_available_event_id").asText();
            assertThat(cursor).isNotBlank();
            joined = request("GET", runLocation + "/stream", null, cursor);
        }
        assertThat(joined.statusCode()).as("Join the same approved Run").isEqualTo(200);
        assertThat(joined.body()).contains("event: end");
        return response;
    }

    /** Ordinary HTTP fixtures use an explicit test key; OS credential behavior has dedicated tests. */
    public static Map<String, String> productionEnvironment(Map<String, String> environment) {
        if (environment.containsKey("HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF")) {
            return environment;
        }
        var configured = new java.util.LinkedHashMap<>(environment);
        configured.put("HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://GATEWAY_FIXTURE_CONTINUATION_KEY");
        configured.put("GATEWAY_FIXTURE_CONTINUATION_KEY", Base64.getEncoder().encodeToString(new byte[32]));
        return Map.copyOf(configured);
    }

    public static GatewayFixture production(Path data, int port, Map<String, String> environment, Path evidence) throws Exception {
        return new GatewayFixture(data, DeerFlowBootstrapApi.create(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), port),
                data, productionEnvironment(environment)), evidence);
    }

    public GatewayFixture(Path data, DeerFlowBootstrapApi api, Path evidence) throws Exception {
        this.data = data;
        this.api = api;
        this.evidence = Files.createDirectories(evidence);
        try {
            api.start();
            base = URI.create("http://127.0.0.1:" + api.address().getPort());
            Files.writeString(evidence.resolve("fixture.summary.json"), JSON.writeValueAsString(
                    Map.of("data_directory", data.toAbsolutePath().toString(), "gateway_port", base.getPort(),
                            "evidence_kind", "derived_hash_summary_not_raw_body")));
        } catch (Exception | Error failure) {
            try { api.close(); } catch (Exception closing) { failure.addSuppressed(closing); }
            try { closeClient(client); } catch (Exception | Error closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }

    public static Path directory(String property, Path fallback, String prefix) throws Exception {
        String configured = System.getProperty(property);
        Path root = configured == null ? fallback : Path.of(configured).toAbsolutePath();
        Files.createDirectories(root);
        return Files.createDirectory(root.resolve(prefix + UUID.randomUUID()));
    }

    static void closeClient(HttpClient client) throws InterruptedException {
        client.shutdownNow();
        assertThat(client.awaitTermination(Duration.ofSeconds(5)) && client.isTerminated())
                .as("Fixture HTTP client must terminate within five seconds").isTrue();
    }

    public static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public static String location(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("SSE HTTP status").isEqualTo(200);
        assertThat(response.body()).as("complete SSE stream").contains("event: end");
        return response.headers().firstValue("content-location").orElseThrow();
    }

    public static void portReleased(int port) throws Exception {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        }
    }

    /** Actual managed-model resolver and the production Tool assembly, without a test resolver. */
    public static final class Managed implements AutoCloseable {
        final ControlledProvider provider;
        public final List<JsonNode> requests;
        public final GatewayFixture http;
        final Path providerEvidence;

        public Managed(Path data) throws Exception {
            providerEvidence = evidenceDirectory();
            provider = new ControlledProvider(providerEvidence, false,
                    (request, call) -> ControlledProvider.stop("m3-provider", "m3-model", "completed"));
            requests = provider.requests;
            GatewayFixture started = null;
            try {
                started = production(data, 0, Map.of(
                        "HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://M3_TEST_KEY", "M3_TEST_KEY",
                        Base64.getEncoder().encodeToString(new byte[32])), evidenceDirectory());
                http = started;
                ObjectNode model = JSON.createObjectNode();
                model.putObject("config").put("name", "m3-model").put("provider", "openai-compatible")
                        .put("model", "m3-model").put("enabled", true).put("base_url", provider.endpoint().toString())
                        .put("context_window", 32768).put("max_tokens", 1024).put("api_key", "controlled-fixture");
                model.putNull("expected_revision");
                assertThat(http.request("PUT", "/api/managed-models", model).statusCode()).isEqualTo(200);
            } catch (Exception | Error failure) {
                if (started != null) {
                    try { started.close(); } catch (Exception closing) { failure.addSuppressed(closing); }
                }
                try { provider.close(); } catch (Exception | Error closing) { failure.addSuppressed(closing); }
                throw failure;
            }
        }

        @Override public void close() throws Exception {
            try (provider; http) {
                Files.writeString(providerEvidence.resolve("gateway-final.summary.json"),
                        JSON.writeValueAsString(Map.of("provider_call_count", requests.size(),
                                "gateway_evidence", http.evidence.toString(),
                                "data_directory", http.data.toAbsolutePath().toString())));
            }
        }
    }

    public static final class HttpEvidence implements AutoCloseable {
        public final ObjectMapper json = JSON;
        public final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        final Path evidence;
        final Duration timeout;
        private int sequence;
        public HttpEvidence(Path evidence) throws Exception { this(evidence, Duration.ofSeconds(150)); }
        public HttpEvidence(Path evidence, Duration timeout) throws Exception {
            this.evidence = Files.createDirectories(evidence);
            this.timeout = timeout;
        }
            public HttpResponse<String> request(URI base, String method, String path, String body, String evidenceName)
                    throws Exception {
                HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path)).timeout(timeout);
                if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
                else builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                long started = System.nanoTime();
                HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                Files.writeString(evidence.resolve(evidenceName), response.body(), StandardCharsets.UTF_8);
                save(evidenceName + ".metadata.json", Map.of("method", method, "path", path,
                        "status", response.statusCode(), "elapsed_ms", (System.nanoTime() - started) / 1_000_000,
                        "content_location", response.headers().firstValue("content-location").orElse("")));
                return response;
            }
            public JsonNode get(URI base, String path, String evidenceName) throws Exception {
                var response = request(base, "GET", path, null, evidenceName);
                assertThat(response.statusCode()).as("HTTP GET %s; evidence=%s", path, evidenceName).isEqualTo(200);
                return json.readTree(response.body());
            }
            public void save(String name, Object value) throws Exception {
                Files.write(evidence.resolve(name), json.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
            }
        public HttpResponse<String> request(URI base, String method, String path, String body) throws Exception {
            return request(base, method, path, body, "http-" + (++sequence) + ".raw.txt");
        }
        public HttpResponse<byte[]> download(URI uri) throws Exception {
            return download(uri, timeout);
        }
        public HttpResponse<byte[]> download(URI uri, Duration downloadTimeout) throws Exception {
            var response = client.send(HttpRequest.newBuilder(uri).timeout(downloadTimeout).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            String name = "artifact-download-" + (++sequence);
            Files.write(evidence.resolve(name + ".bin"), response.body());
            save(name + ".metadata.json", Map.of("path", uri.getPath(), "status", response.statusCode(),
                    "bytes", response.body().length, "sha256", hash(response.body()),
                    "etag", response.headers().firstValue("etag").orElse("")));
            return response;
        }
        @Override public void close() throws InterruptedException { closeClient(client); }
    }
}

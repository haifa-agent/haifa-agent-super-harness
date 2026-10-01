package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelStreamSink;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import javax.crypto.spec.SecretKeySpec;

/** Test-only real SDK/HTTP fixtures. Synthetic SOUL text stays in assertion memory. */
final class AgentSoulTestSupport {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Set<String> BASE_TOOLS = Set.of("read_file", "write_file", "str_replace", "ls", "glob", "grep",
            "list_uploaded_files", "present_files", "ask_clarification", "web_search", "web_fetch", "image_search");

    private AgentSoulTestSupport() {}

    static Path dataDirectory() throws Exception {
        Path root = Path.of(System.getProperty("m3.dataRoot", "local-tmp/slot-2-m3/data"));
        return Files.createTempDirectory(Files.createDirectories(root), "case-");
    }

    static ObjectNode safeRunSummary(JsonNode run) {
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

    private static JsonNode safeCode(JsonNode value) {
        return value.isTextual() && value.asText().matches("[A-Za-z0-9_.:-]{1,128}") ? value : JSON.nullNode();
    }

    static Path evidenceDirectory() throws Exception {
        return Files.createDirectories(Path.of(System.getProperty("m3.evidence", "local-tmp/slot-2-m3/evidence"))
                .resolve("case-" + UUID.randomUUID()));
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("test hash unavailable", exception);
        }
    }

    static String drain(InputStream stream) throws Exception {
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

    static io.haifa.agent.sdk.tool.JavaToolContext context(String principal, boolean cancelled, boolean expired,
            boolean session) {
        return new io.haifa.agent.sdk.tool.JavaToolContext(new io.haifa.agent.core.run.AgentRunId("m3-component"),
                session ? java.util.Optional.of(new io.haifa.agent.core.session.AgentSessionId("m3-session"))
                        : java.util.Optional.empty(),
                new io.haifa.agent.core.tool.ToolCallId("m3-call"), new io.haifa.agent.core.reference.TenantRef("public"),
                new io.haifa.agent.core.reference.PrincipalRef(principal, "user"),
                expired ? java.time.Instant.now().minusSeconds(1) : java.time.Instant.now().plusSeconds(30),
                java.util.Optional.empty(), () -> cancelled, Map.of());
    }

    static String instructions(AgentChatRequest request) {
        var candidates = request.messages().stream()
                .filter(message -> message.role().name().equals("SYSTEM"))
                .map(message -> message.content())
                .filter(text -> text.contains("<role>") && text.contains("open-source super agent"))
                .toList();
        assertThat(candidates.size()).as("one actual Lead instructions layer").isEqualTo(1);
        return candidates.getFirst();
    }

    static Set<String> names(AgentChatRequest request) {
        return request.tools().stream().map(tool -> tool.name()).collect(java.util.stream.Collectors.toSet());
    }

    static String catalogHash(AgentChatRequest request) {
        return hash(request.tools().stream().map(tool -> tool.name() + ":" + tool.inputJsonSchema())
                .sorted().collect(java.util.stream.Collectors.joining("\n")));
    }

    static void soulEquals(String instructions, String soul) {
        String escaped = soul.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        assertThat(instructions.contains("<soul>\n" + escaped + "\n</soul>"))
                .as("actual escaped SOUL matches stored synthetic text; hash=%s chars=%s", hash(soul), soul.length())
                .isTrue();
    }

    static String customSoulAtLimit(String agent) {
        var tools = new java.util.HashSet<>(BASE_TOOLS);
        tools.add("update_agent");
        int fixed = io.haifa.agent.superharness.server.prompt.LeadPromptComposer.classpath()
                .compose(new io.haifa.agent.superharness.server.prompt.LeadPromptInputs(agent, "x", true, List.of()), tools)
                .instructions().trim().length() - 1;
        return "x".repeat(32000 - fixed);
    }

    static AgentChatResponse stop(AgentChatRequest request) {
        return new AgentChatResponse(request.callId().value(), request.model().providerModelId(), "completed",
                List.of(), ModelFinishReason.STOP, ModelUsage.unpriced(1, 1), "", Map.of());
    }

    static AgentChatResponse call(AgentChatRequest request, String name, Map<String, Object> arguments) {
        return new AgentChatResponse(request.callId().value(), request.model().providerModelId(), "",
                List.of(new ModelToolCall(new ProviderToolCallCorrelationId("m3-" + request.callId().value()),
                        name, arguments)), ModelFinishReason.TOOL_CALLS, ModelUsage.unpriced(1, 1), "", Map.of());
    }

    static final class RecordingModel implements AgentChatModel {
        final List<AgentChatRequest> requests = new CopyOnWriteArrayList<>();
        final BiFunction<AgentChatRequest, Integer, AgentChatResponse> responder;

        RecordingModel() {
            this((request, index) -> stop(request));
        }

        RecordingModel(BiFunction<AgentChatRequest, Integer, AgentChatResponse> responder) {
            this.responder = responder;
        }

        @Override
        public AgentChatResponse invoke(AgentChatRequest request) {
            requests.add(request);
            return responder.apply(request, requests.size());
        }

        @Override
        public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
            return invoke(request);
        }
    }

    /** Observes the known product configuration replace path, without inspecting credentials. */
    record ConfigFingerprint(Object key, FileTime modified, long size, String digest) {
        static ConfigFingerprint read(Path data) throws Exception {
            Path file = data.resolve("owners/default/configuration.json").toRealPath();
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            Object identity = attributes.fileKey();
            if (identity == null) identity = windowsFileId(file);
            return new ConfigFingerprint(identity, attributes.lastModifiedTime(), attributes.size(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        }

        private static String windowsFileId(Path file) throws Exception {
            assertThat(System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows"))
                    .as("null JDK fileKey requires the explicitly approved Windows FileID observer").isTrue();
            Process observer = new ProcessBuilder("fsutil.exe", "file", "queryfileid", file.toString())
                    .redirectErrorStream(true).start();
            try {
                assertThat(observer.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
                        .as("bounded fsutil file queryfileid observer").isTrue();
                byte[] output = observer.getInputStream().readNBytes(4097);
                assertThat(output.length).as("bounded FileID observer output").isLessThanOrEqualTo(4096);
                assertThat(observer.exitValue()).as("FileID observer exit code; output hash=%s",
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(output))).isZero();
                var match = java.util.regex.Pattern.compile("(?i)(?<![0-9a-f])0x([0-9a-f]{32})(?![0-9a-f])")
                        .matcher(new String(output, java.nio.charset.Charset.defaultCharset()));
                assertThat(match.find()).as("exact 128-bit FileID must be present").isTrue();
                String id = match.group(1).toLowerCase(java.util.Locale.ROOT);
                assertThat(id).as("FileID must be nonzero").isNotEqualTo("0".repeat(32));
                assertThat(match.find()).as("FileID observer must return exactly one identity").isFalse();
                return "windows-file-id:" + id;
            } finally {
                if (observer.isAlive()) observer.destroyForcibly();
                observer.getInputStream().close();
                observer.getOutputStream().close();
                observer.getErrorStream().close();
            }
        }

        void assertUnchanged(Path data) throws Exception {
            assertThat(read(data)).as("no successful configuration replace: fileKey/mtime/bytes unchanged")
                    .isEqualTo(this);
        }

        ObjectNode safeSummary() {
            return JSON.createObjectNode().put("file_identity", key.toString())
                    .put("last_modified", modified.toString()).put("size_bytes", size).put("bytes_sha256", digest);
        }
    }

    static final class Fixture implements AutoCloseable {
        final Path data;
        final Path evidence;
        final DeerFlowBootstrapApi api;
        final URI base;
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        final AtomicInteger records = new AtomicInteger();

        Fixture(Path data, RecordingModel model) throws Exception {
            this(data, DeerFlowBootstrapApi.createForTests(
                    new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0), data,
                    ignored -> LocalProbeModelTestSupport.binding(model), new SecretKeySpec(new byte[32], "AES")));
        }

        Fixture(Path data, DeerFlowBootstrapApi api) throws Exception {
            this.data = data;
            this.api = api;
            evidence = evidenceDirectory();
            api.start();
            base = URI.create("http://127.0.0.1:" + api.address().getPort());
            Files.writeString(evidence.resolve("fixture.summary.json"), JSON.writeValueAsString(
                    Map.of("data_directory", data.toAbsolutePath().toString(), "gateway_port", base.getPort(),
                            "evidence_kind", "derived_hash_summary_not_raw_body")));
        }

        HttpResponse<String> request(String method, String path, JsonNode body) throws Exception {
            HttpResponse<String> response = client.send(builder(method, path, body).build(),
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

        HttpRequest.Builder builder(String method, String path, JsonNode body) {
            return HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body.toString()));
        }

        JsonNode get(String path) throws Exception {
            var response = request("GET", path, null);
            assertThat(response.statusCode()).as("GET status; body hash=%s", hash(response.body())).isEqualTo(200);
            return JSON.readTree(response.body());
        }

        ObjectNode seed(String name, String soul) throws Exception {
            ObjectNode agent = JSON.createObjectNode().put("name", name).put("display_name", "Display " + name)
                    .put("description", "description " + name).put("soul", soul);
            agent.putArray("skills");
            agent.putArray("mcp_plugins");
            var response = request("POST", "/api/agents", agent);
            assertThat(response.statusCode()).as("seed status; body hash=%s", hash(response.body())).isEqualTo(201);
            return (ObjectNode) JSON.readTree(response.body());
        }

        JsonNode agent(String name) throws Exception {
            return get("/api/agents/" + name);
        }

        void update(String name, JsonNode update) throws Exception {
            assertThat(request("PUT", "/api/agents/" + name, update).statusCode()).isEqualTo(200);
        }

        ObjectNode replacePositiveControl(String name) throws Exception {
            ConfigFingerprint before = ConfigFingerprint.read(data);
            update(name, JSON.createObjectNode().put("description", agent(name).path("description").asText()));
            ConfigFingerprint after = ConfigFingerprint.read(data);
            ObjectNode summary = JSON.createObjectNode().put("evidence_kind", "derived_safe_summary")
                    .put("same_value_rest_update", true).put("identity_changed", !after.key().equals(before.key()));
            summary.set("before", before.safeSummary());
            summary.set("after", after.safeSummary());
            Files.writeString(evidence.resolve("config-positive-" + UUID.randomUUID() + ".safe-summary.json"),
                    JSON.writeValueAsString(summary));
            assertThat(after.key()).as("same-value REST update replaces physical configuration file").isNotEqualTo(before.key());
            return summary;
        }

        void thread(String name) throws Exception {
            assertThat(request("POST", "/api/threads", JSON.createObjectNode().put("thread_id", name)).statusCode())
                    .isEqualTo(200);
        }

        ObjectNode input(String agent, Boolean bootstrap, String message) {
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

        HttpResponse<String> run(String thread, String agent, Boolean bootstrap) throws Exception {
            return request("POST", "/api/threads/" + thread + "/runs/stream", input(agent, bootstrap, "execute fixture"));
        }

        String completed(HttpResponse<String> response) throws Exception {
            assertThat(response.statusCode()).as("Run status; body hash=%s", hash(response.body())).isEqualTo(200);
            assertThat(response.body().contains("event: end")).as("bounded stream ended").isTrue();
            String location = response.headers().firstValue("content-location").orElseThrow();
            JsonNode run = get(location);
            assertThat(run.path("diagnostics").path("sdk_status").asText())
                    .as("public safe Run diagnostics: %s", safeRunSummary(run)).isEqualTo("COMPLETED");
            return location;
        }

        JsonNode state(String thread) throws Exception {
            return get("/api/threads/" + thread + "/state");
        }

        List<JsonNode> results(String thread, String tool) throws Exception {
            var messages = state(thread).path("values").path("messages");
            var results = new java.util.ArrayList<JsonNode>();
            for (JsonNode message : messages) {
                if ("tool".equals(message.path("type").asText()) && tool.equals(message.path("name").asText())) {
                    results.add(message);
                }
            }
            return results;
        }

        String approvalId(String thread) throws Exception {
            for (JsonNode message : state(thread).path("values").path("messages")) {
                var input = message.path("artifact").path("human_input");
                if (input.hasNonNull("request_id")) return input.path("request_id").asText();
            }
            throw new AssertionError("missing approval card");
        }

        String approvalId(String thread, String runLocation) throws Exception {
            String currentRunId = runLocation.substring(runLocation.lastIndexOf('/') + 1);
            JsonNode run = get(runLocation);
            var pending = new java.util.ArrayList<JsonNode>();
            var summary = JSON.createObjectNode().put("evidence_kind", "derived_safe_summary")
                    .put("thread_id", thread).put("run_location", runLocation).put("current_run_id", currentRunId)
                    .put("sdk_status", run.path("diagnostics").path("sdk_status").asText());
            var interactionFacts = summary.putArray("current_run_interactions");
            for (JsonNode interaction : run.path("diagnostics").path("interactions")) {
                var fact = interactionFacts.addObject();
                for (String field : List.of("run_id", "request_id", "kind", "state")) {
                    fact.set(field, safeCode(interaction.path(field)));
                }
                if ("PENDING".equals(interaction.path("state").asText())
                        && "approval".equals(interaction.path("kind").asText())
                        && currentRunId.equals(interaction.path("run_id").asText())) pending.add(interaction);
            }
            String pendingId = pending.size() == 1 ? pending.getFirst().path("request_id").asText() : "";
            summary.put("pending_approval_count", pending.size()).put("current_pending_request_id", pendingId);
            var matches = new java.util.ArrayList<JsonNode>();
            var cards = summary.putArray("state_approval_cards");
            for (JsonNode message : state(thread).path("values").path("messages")) {
                var input = message.path("artifact").path("human_input");
                if (!input.hasNonNull("request_id")) continue;
                String cardRunId = message.path("run_id").asText();
                String cardRequestId = input.path("request_id").asText();
                boolean match = currentRunId.equals(cardRunId) && pendingId.equals(cardRequestId)
                        && "haifa_interaction".equals(input.path("source").asText());
                var fact = cards.addObject().put("is_current_run", currentRunId.equals(cardRunId))
                        .put("matches_current_pending", match);
                fact.set("run_id", safeCode(message.path("run_id")));
                fact.set("request_id", safeCode(input.path("request_id")));
                fact.set("tool_call_id", safeCode(input.path("tool_call_id")));
                if (match) matches.add(message);
            }
            summary.put("matching_state_card_count", matches.size());
            Files.writeString(evidence.resolve("approval-" + currentRunId + ".selection.safe-summary.json"),
                    JSON.writeValueAsString(summary));
            assertThat(run.path("run_id").asText()).isEqualTo(currentRunId);
            assertThat(run.path("diagnostics").path("sdk_status").asText()).isEqualTo("WAITING_APPROVAL");
            assertThat(pending.size()).as("one actual pending approval belongs to the requested Run").isEqualTo(1);
            assertThat(pendingId).matches("[A-Za-z0-9_.:-]{1,128}");
            assertThat(matches.size()).as("state contains exactly one card for the current Run and pending request")
                    .isEqualTo(1);
            return pendingId;
        }

        HttpResponse<String> approve(String thread) throws Exception {
            return request("POST", "/api/threads/" + thread + "/runs/stream",
                    JSON.readTree(LeadPromptAcceptanceTestSupport.approval(approvalId(thread))));
        }

        HttpResponse<String> approve(String thread, String runLocation) throws Exception {
            String selectedRequestId = approvalId(thread, runLocation);
            HttpResponse<String> response = request("POST", "/api/threads/" + thread + "/runs/stream",
                    JSON.readTree(LeadPromptAcceptanceTestSupport.approval(selectedRequestId)));
            String currentRunId = runLocation.substring(runLocation.lastIndexOf('/') + 1);
            var summary = JSON.createObjectNode().put("evidence_kind", "derived_safe_summary")
                    .put("current_run_id", currentRunId).put("submitted_request_id", selectedRequestId)
                    .put("http_status", response.statusCode())
                    .put("content_location", response.headers().firstValue("content-location").orElse(""));
            Files.writeString(evidence.resolve("approval-" + currentRunId + ".submission.safe-summary.json"),
                    JSON.writeValueAsString(summary));
            return response;
        }

        void requestEvidence(AgentChatRequest request) throws Exception {
            String text = instructions(request);
            Files.writeString(evidence.resolve("request-" + request.callId().value() + ".summary.json"),
                    JSON.writeValueAsString(Map.of("run_id", request.runId().value(), "instructions_chars", text.length(),
                            "instructions_sha256", hash(text.trim()), "tools", names(request),
                            "catalog_sha256", catalogHash(request))));
        }

        @Override
        public void close() throws Exception {
            try { api.close(); } finally { client.close(); }
            LeadPromptAcceptanceTestSupport.portReleased(base.getPort());
        }
    }

    /** Actual managed-model resolver fixture; no testRunModelResolver is supplied. */
    static final class ProductionFixture implements AutoCloseable {
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        final HttpServer provider;
        final Fixture http;
        final Path providerEvidence;

        ProductionFixture(Path data) throws Exception {
            providerEvidence = evidenceDirectory();
            provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            provider.createContext("/v1/chat/completions", exchange -> {
                JsonNode request = JSON.readTree(exchange.getRequestBody());
                requests.add(request);
                int call = requests.size();
                byte[] response = ("data: {\"id\":\"m3-provider\",\"model\":\"m3-model\",\"choices\":[{\"index\":0,"
                        + "\"finish_reason\":\"stop\",\"delta\":{\"content\":\"completed\"}}]}\n\n"
                        + "data: {\"id\":\"m3-provider\",\"model\":\"m3-model\",\"choices\":[],"
                        + "\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":3,\"total_tokens\":7}}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                Files.writeString(providerEvidence.resolve("provider-" + call + ".summary.json"), JSON.writeValueAsString(
                        Map.of("provider_call_count", call, "request_chars", request.toString().length(),
                                "request_sha256", hash(request.toString()), "response_bytes", response.length,
                                "response_sha256", hash(new String(response, StandardCharsets.UTF_8)),
                                "response_has_usage", true)));
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, response.length);
                try (var output = exchange.getResponseBody()) { output.write(response); }
                exchange.close();
            });
            provider.start();
            Fixture started = null;
            try {
                started = new Fixture(data, DeerFlowBootstrapApi.create(
                        new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0), data,
                        Map.of("HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://M3_TEST_KEY", "M3_TEST_KEY",
                                Base64.getEncoder().encodeToString(new byte[32]))));
                http = started;
                ObjectNode model = JSON.createObjectNode();
                model.putObject("config").put("name", "m3-model").put("provider", "openai-compatible")
                        .put("model", "m3-model").put("enabled", true)
                        .put("base_url", "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1")
                        .put("context_window", 32768).put("max_tokens", 1024).put("api_key", "controlled-fixture");
                model.putNull("expected_revision");
                assertThat(http.request("PUT", "/api/managed-models", model).statusCode()).isEqualTo(200);
            } catch (Exception | Error failure) {
                if (started != null) {
                    try { started.close(); } catch (Exception closing) { failure.addSuppressed(closing); }
                }
                provider.stop(0);
                throw failure;
            }
        }

        String instructions(int index) {
            for (JsonNode message : requests.get(index).path("messages")) {
                if ("system".equals(message.path("role").asText())
                        && message.path("content").asText().contains("open-source super agent")) {
                    return message.path("content").asText();
                }
            }
            throw new AssertionError("managed Provider request missing Lead instructions");
        }

        Set<String> names(int index) {
            JsonNode tools = requests.get(index).path("tools");
            assertThat(tools.isArray()).as("actual OpenAI request tools is an array").isTrue();
            Set<String> names = new java.util.HashSet<>();
            int position = 0;
            for (JsonNode tool : tools) {
                assertThat(tool.isObject()).as("OpenAI Tool %s is an object", position).isTrue();
                assertThat(tool.path("type").isTextual() && tool.path("type").asText().equals("function"))
                        .as("OpenAI Tool %s declares type=function", position).isTrue();
                JsonNode function = tool.path("function");
                assertThat(function.isObject()).as("OpenAI Tool %s has function declaration", position).isTrue();
                JsonNode name = function.path("name");
                assertThat(name.isTextual() && !name.asText().isBlank())
                        .as("OpenAI Tool %s has a nonblank textual function.name", position).isTrue();
                JsonNode schema = function.path("parameters");
                assertThat(schema.isObject() && schema.path("type").asText().equals("object"))
                        .as("OpenAI Tool %s has an object input schema", position).isTrue();
                assertThat(names.add(name.asText())).as("OpenAI Tool %s name is unique", position).isTrue();
                position++;
            }
            return Set.copyOf(names);
        }

        @Override
        public void close() throws Exception {
            try {
                try {
                    Files.writeString(providerEvidence.resolve("provider-final.summary.json"),
                            JSON.writeValueAsString(Map.of("provider_call_count", requests.size(),
                                    "gateway_evidence", http.evidence.toString(), "data_directory", http.data.toAbsolutePath().toString())));
                } finally { http.close(); }
            } finally { provider.stop(0); }
        }
    }
}

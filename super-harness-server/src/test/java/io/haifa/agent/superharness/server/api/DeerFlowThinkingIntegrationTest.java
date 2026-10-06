package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.openai.OpenAiCompatibleChatModel;
import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
import io.haifa.agent.superharness.server.run.HaifaRunBridge;
import io.haifa.agent.superharness.server.testsupport.ControlledProvider;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import io.haifa.agent.superharness.server.testsupport.SseEvents;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Public-JAR consumer: real DeepSeek dialect and unchanged official snapshot, isolated HTTP transport. */
class DeerFlowThinkingIntegrationTest {
    private static final String MODEL = "deepseek-v4-pro";
    private static final String ORDINARY = "synthetic ordinary continuation";
    private static final String TOOL = "synthetic tool continuation";
    private static final String FILE = "/mnt/user-data/outputs/w03-thinking.txt";

    @Test
    void lowOrdinaryHistoryApprovalRestartAndNextTurnUseTheFrozenPublicSdk() throws Exception {
        Path data = GatewayFixture.dataDirectory();
        Path evidence = GatewayFixture.evidenceDirectory();
        try (var provider = new ControlledProvider(evidence, false, (request, call) -> {
            ObjectNode response = call == 2
                    ? ControlledProvider.call("thinking-" + call, MODEL, "thinking-write", "write_file",
                            Map.of("path", FILE, "content", "W03_THINKING_OK"))
                    : ControlledProvider.stop("thinking-" + call, MODEL, "W03 answer " + call);
            if (call <= 3) ((ObjectNode) response.path("choices").get(0).path("message"))
                    .put("reasoning_content", call == 2 ? TOOL : ORDINARY);
            return response;
        }); var transport = new FixtureTransport(provider.port())) {
            String pendingLocation;
            String requestId;
            try (var gateway = gateway(data, evidence, transport)) {
                var model = GatewayFixture.JSON.createObjectNode().put("name", "thinking-model")
                        .put("display_name", "Thinking fixture").put("provider", "openai-compatible")
                        .put("model", MODEL).put("base_url", "https://api.deepseek.com")
                        .put("api_key", "fixture-key").put("enabled", true).put("supports_vision", false)
                        .put("context_window", 32768).put("max_tokens", 1024);
                var save = GatewayFixture.JSON.createObjectNode().putNull("expected_revision");
                save.set("config", model);
                assertThat(gateway.request("PUT", "/api/managed-models", save).statusCode()).isEqualTo(200);
                gateway.thread("thinking-thread");
                var ordinary = gateway.request("POST", "/api/threads/thinking-thread/runs/stream", input(gateway, "thinking"));
                gateway.completed(ordinary);
                var pending = gateway.request("POST", "/api/threads/thinking-thread/runs/stream", input(gateway, "thinking"));
                assertThat(pending.statusCode()).isEqualTo(200);
                pendingLocation = pending.headers().firstValue("content-location").orElseThrow();
                requestId = gateway.approvalId("thinking-thread", pendingLocation);
                assertThat(provider.calls).hasValue(2);
                assertThat(assistantReasoning(provider.requests.get(1))).contains(ORDINARY);
                var changed = input(gateway, "flash");
                var conflict = gateway.client.send(gateway.builder("POST", "/api/threads/thinking-thread/runs/stream", changed)
                        .header("X-Haifa-Run-Input", "steer").build(), HttpResponse.BodyHandlers.ofString());
                assertThat(conflict.statusCode()).isEqualTo(409);
                assertThat(conflict.body()).contains("Active Run model settings are frozen");
                assertThat(provider.calls).hasValue(2);
                assertPublicBody(ordinary.body());
                assertPublicBody(pending.body());
            }
            try (var restarted = gateway(data, evidence.resolve("restarted"), transport)) {
                restarted.get(pendingLocation);
                restarted.get("/api/threads/thinking-thread/state");
                assertThat(provider.calls).hasValue(2);
                var approved = restarted.approve("thinking-thread", pendingLocation);
                assertPublicBody(approved.body());
                if (provider.calls.get() != 3) {
                    Files.writeString(evidence.resolve("restart-approval-failure.sse"), approved.body());
                    Files.writeString(evidence.resolve("restart-approval-failure.safe-diagnostics.json"),
                            GatewayFixture.safeRunSummary(restarted.get(pendingLocation)).toString());
                }
                assertThat(provider.calls).hasValue(3);
                JsonNode resumedRequest = provider.requests.get(2);
                assertThat(resumedRequest.path("thinking").path("type").asText()).isEqualTo("enabled");
                assertThat(resumedRequest.path("reasoning_effort").asText()).isEqualTo("low");
                assertThat(assistantReasoning(resumedRequest)).contains(ORDINARY, TOOL);
                assertThat(restarted.get(pendingLocation).path("status").asText()).isEqualTo("success");
                var download = restarted.request("GET", "/api/threads/thinking-thread/artifacts" + FILE, null);
                assertThat(download.statusCode()).isEqualTo(200);
                assertThat(download.body()).isEqualTo("W03_THINKING_OK");
                assertThat(restarted.request("POST", "/api/threads/thinking-thread/runs/stream",
                        GatewayFixture.JSON.readTree(SseEvents.PendingApproval.reply(requestId, "approve"))).statusCode())
                        .isEqualTo(409);
                assertThat(provider.calls).hasValue(3);
                var flash = restarted.request("POST", "/api/threads/thinking-thread/runs/stream", input(restarted, "flash"));
                restarted.completed(flash);
                assertThat(provider.calls).hasValue(4);
                JsonNode finalRequest = provider.requests.get(3);
                assertThat(finalRequest.path("thinking").path("type").asText()).isEqualTo("disabled");
                assertThat(finalRequest.has("reasoning_effort")).isFalse();
                assertThat(assistantReasoning(finalRequest)).doesNotContain(ORDINARY, TOOL);
                assertPublicBody(restarted.get("/api/threads/thinking-thread/state").toString());
            }
        }
    }

    @Test
    void frozenComposerProContextIsAcceptedAndRequestsThinking() throws Exception {
        assertFrozenComposerContextRequestsThinking(
                "thread-pro-flags",
                """
                {"model_name":"thinking-model","mode":"pro","thinking_enabled":true,
                 "is_plan_mode":true,"reasoning_effort":"medium","subagent_enabled":false}
                """);
    }

    @Test
    void frozenComposerUltraContextIsAcceptedAndRequestsThinking() throws Exception {
        assertFrozenComposerContextRequestsThinking(
                "thread-ultra-flags",
                """
                {"model_name":"thinking-model","mode":"ultra","thinking_enabled":true,
                 "is_plan_mode":true,"reasoning_effort":"high","subagent_enabled":true}
                """);
    }

    /**
     * The frozen composer's pro/ultra contexts carry thinking_enabled=true, so they only bind on the
     * official DeepSeek endpoint form. This routes the unchanged official snapshot to a loopback
     * Controlled Provider and asserts the composed request really enables thinking.
     */
    private void assertFrozenComposerContextRequestsThinking(String threadId, String contextJson) throws Exception {
        Path data = GatewayFixture.dataDirectory();
        Path evidence = GatewayFixture.evidenceDirectory();
        try (var provider = new ControlledProvider(evidence, true, (request, call) ->
                        ControlledProvider.stop("flags-" + call, MODEL, "flags answer " + call));
                var transport = new FixtureTransport(provider.port())) {
            try (var gateway = gateway(data, evidence, transport)) {
                saveOfficialThinkingModel(gateway);
                gateway.thread(threadId);
                ObjectNode body = gateway.input(null, null, "perform the configured turn");
                body.set("context", GatewayFixture.JSON.readTree(contextJson));
                HttpResponse<String> stream =
                        gateway.request("POST", "/api/threads/" + threadId + "/runs/stream", body);
                assertThat(stream.statusCode()).as(stream.body()).isEqualTo(200);
                assertThat(stream.body()).contains("event: end");
                assertThat(provider.calls).hasValue(1);
                JsonNode request = provider.requests.get(0);
                assertThat(request.path("thinking").path("type").asText()).isEqualTo("enabled");
            }
        }
    }

    private static void saveOfficialThinkingModel(GatewayFixture gateway) throws Exception {
        var model = GatewayFixture.JSON.createObjectNode().put("name", "thinking-model")
                .put("display_name", "Thinking fixture").put("provider", "openai-compatible")
                .put("model", MODEL).put("base_url", "https://api.deepseek.com")
                .put("api_key", "fixture-key").put("enabled", true).put("supports_vision", false)
                .put("context_window", 32768).put("max_tokens", 1024);
        var save = GatewayFixture.JSON.createObjectNode().putNull("expected_revision");
        save.set("config", model);
        assertThat(gateway.request("PUT", "/api/managed-models", save).statusCode()).isEqualTo(200);
    }

    private static void assertPublicBody(String body) {
        assertThat(body.contains(ORDINARY) || body.contains(TOOL)).as("protected payload stays out of public output").isFalse();
        assertThat(body).doesNotContain("modelContinuationId", "modelContinuationDigest", "modelContinuationBytes");
    }

    private static List<String> assistantReasoning(JsonNode request) {
        return java.util.stream.StreamSupport.stream(request.path("messages").spliterator(), false)
                .filter(value -> "assistant".equals(value.path("role").asText()))
                .map(value -> value.path("reasoning_content").asText()).toList();
    }

    private static ObjectNode input(GatewayFixture gateway, String mode) {
        var body = gateway.input(null, null, "perform the configured turn");
        var context = (ObjectNode) body.path("context");
        context.put("model_name", "thinking-model").put("mode", mode).put("thinking_enabled", mode.equals("thinking"))
                .put("reasoning_effort", mode.equals("thinking") ? "low" : "minimal");
        return body;
    }

    private static GatewayFixture gateway(Path data, Path evidence, FixtureTransport transport) throws Exception {
        var config = new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0);
        var api = DeerFlowBootstrapApi.createForTests(config, data, body -> {
            try {
                var binding = new DeerFlowConfigurationApi(GatewayFixture.JSON, data).resolveRunModel(body);
                var adapter = new OpenAiCompatibleChatModel("openai-compatible", "1.0.0", transport,
                        GatewayFixture.JSON, ignored -> new ResolvedCredential("fixture-key"), false, 4 * 1024 * 1024);
                return new HaifaRunBridge.ModelBinding(binding.name(), binding.revision(), adapter, binding.snapshot());
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("fixture configuration unavailable", failure);
            }
        }, new SecretKeySpec(new byte[32], "AES"));
        return new GatewayFixture(data, api, evidence);
    }

    /** Routes only the fixture's official DeepSeek request to loopback without changing its snapshot or body. */
    private static final class FixtureTransport extends HttpClient {
        private final HttpClient delegate = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private final int port;
        FixtureTransport(int port) { this.port = port; }
        private HttpRequest route(HttpRequest request) {
            if (!request.uri().equals(URI.create("https://api.deepseek.com/chat/completions")))
                throw new IllegalArgumentException("unexpected fixture transport destination");
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
                    .method(request.method(), request.bodyPublisher().orElseThrow());
            request.headers().map().forEach((key, values) -> values.forEach(value -> builder.header(key, value)));
            request.timeout().ifPresent(builder::timeout);
            return builder.build();
        }
        @Override public <T> HttpResponse<T> send(HttpRequest r, HttpResponse.BodyHandler<T> h)
                throws java.io.IOException, InterruptedException { return delegate.send(route(r), h); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) {
            return delegate.sendAsync(route(r), h);
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h,
                HttpResponse.PushPromiseHandler<T> p) { return delegate.sendAsync(route(r), h, p); }
        @Override public Optional<java.net.CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
        @Override public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
        @Override public Redirect followRedirects() { return delegate.followRedirects(); }
        @Override public Optional<java.net.ProxySelector> proxy() { return delegate.proxy(); }
        @Override public javax.net.ssl.SSLContext sslContext() { return delegate.sslContext(); }
        @Override public javax.net.ssl.SSLParameters sslParameters() { return delegate.sslParameters(); }
        @Override public Optional<java.net.Authenticator> authenticator() { return delegate.authenticator(); }
        @Override public Version version() { return delegate.version(); }
        @Override public Optional<java.util.concurrent.Executor> executor() { return delegate.executor(); }
        @Override public void close() { delegate.close(); }
    }
}

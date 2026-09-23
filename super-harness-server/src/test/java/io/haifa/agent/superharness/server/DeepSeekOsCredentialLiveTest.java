package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Explicit L3 check. Reads only os://DEEPSEEK_API_KEY and never the migration source file. */
@EnabledIfSystemProperty(named = "haifa.deepseek.live", matches = "true")
public class DeepSeekOsCredentialLiveTest {
    private static final int SLOT_3_GATEWAY_PORT = 48_018;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void probesDeepSeekAndCompletesARealSuperHarnessToolRun() throws Exception {
        Path dataDirectory = Path.of(requiredProperty("super.harness.live.data.dir"))
                .toAbsolutePath()
                .normalize();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", SLOT_3_GATEWAY_PORT), 0);
        DeerFlowConfigurationApi configuration = new DeerFlowConfigurationApi(json, dataDirectory);
        server.createContext("/api/managed-models", configuration);
        server.start();
        try {
            URI baseUri = URI.create("http://127.0.0.1:" + SLOT_3_GATEWAY_PORT);
            String name = "deepseek-live-" + UUID.randomUUID().toString().substring(0, 8);
            ObjectNode model = json.createObjectNode();
            model.put("name", name);
            model.put("display_name", "DeepSeek live validation");
            model.put("provider", "openai-compatible");
            model.put("model", "deepseek-v4-pro");
            model.put("base_url", "https://api.deepseek.com");
            model.put("credential_ref", "os://DEEPSEEK_API_KEY");
            model.put("enabled", true);
            model.put("supports_vision", false);
            model.put("context_window", 1_048_576);
            model.put("max_tokens", 2_048);
            ObjectNode saveRequest = json.createObjectNode();
            saveRequest.set("config", model);
            saveRequest.putNull("expected_revision");
            HttpResponse<String> saved = request(baseUri, "PUT", "/api/managed-models", saveRequest);
            assertThat(saved.statusCode()).isEqualTo(200);
            String revision = json.readTree(saved.body()).path("revision").asText();

            ObjectNode probeRequest = json.createObjectNode();
            probeRequest.set("config", model);
            probeRequest.put("expected_revision", revision);
            HttpResponse<String> probe = request(baseUri, "POST", "/api/managed-models/test", probeRequest);
            assertThat(probe.statusCode()).isEqualTo(200);
            assertThat(json.readTree(probe.body()))
                    .isEqualTo(json.readTree("{\"ok\":true,\"message\":\"success\"}"));
            System.out.println("W03_DEEPSEEK_PROBE=PASS");

            ObjectNode runRequest = json.createObjectNode();
            runRequest.set(
                    "context",
                    json.createObjectNode().put("model_name", name).put("mode", "flash"));
            HaifaRunBridge.ModelBinding binding = configuration.resolveRunModel(runRequest);
            AtomicBoolean invoked = new AtomicBoolean();
            try (HaifaRunBridge bridge = new HaifaRunBridge(
                    binding, List.of(new ConnectionCheckTool(invoked)))) {
                HaifaRunBridge.StartedRun started = bridge.start(
                        "deepseek-live-tool-run",
                        "Call connection_check with an empty object. After the tool result, answer TOOL_CHAIN_OK.",
                        Optional.empty());
                AgentRunSnapshot snapshot = awaitTerminal(bridge, started, Duration.ofSeconds(90));
                assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
                assertThat(invoked).isTrue();
                assertThat(snapshot.usage().toolCalls()).isEqualTo(1);
                assertThat(snapshot.usage().modelCalls()).isGreaterThanOrEqualTo(2);
                System.out.println("W03_DEEPSEEK_RUN=PASS run=" + snapshot.runId().value()
                        + " tool=connection_check model_calls=" + snapshot.usage().modelCalls());
            }
        } finally {
            server.stop(0);
        }
    }

    private HttpResponse<String> request(URI baseUri, String method, String path, JsonNode body)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static AgentRunSnapshot awaitTerminal(
            HaifaRunBridge bridge, HaifaRunBridge.StartedRun started, Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        AgentRunSnapshot snapshot;
        do {
            snapshot = bridge.snapshot(started.runId());
            if (snapshot.status().isTerminal()) return snapshot;
            Thread.sleep(200);
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("DeepSeek Run did not reach a terminal status before the deadline");
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    public record ConnectionCheckRequest() {}

    public record ConnectionCheckResponse(String status) {}

    private static final class ConnectionCheckTool
            implements JavaTool<ConnectionCheckRequest, ConnectionCheckResponse> {
        private static final JavaToolSpec<ConnectionCheckRequest, ConnectionCheckResponse> SPEC =
                JavaToolSpec.builder(
                                "connection_check",
                                ConnectionCheckRequest.class,
                                ConnectionCheckResponse.class)
                        .description("Confirm the Super Harness tool execution path")
                        .pure()
                        .build();
        private final AtomicBoolean invoked;

        private ConnectionCheckTool(AtomicBoolean invoked) {
            this.invoked = invoked;
        }

        @Override
        public JavaToolSpec<ConnectionCheckRequest, ConnectionCheckResponse> spec() {
            return SPEC;
        }

        @Override
        public ConnectionCheckResponse invoke(
                ConnectionCheckRequest input, JavaToolContext context) {
            invoked.set(true);
            return new ConnectionCheckResponse("ok");
        }
    }
}

package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
import java.lang.reflect.Field;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Test-side HTTP evidence and read-only SH bridge observation; no SDK private access. */
final class LeadPromptAcceptanceTestSupport {
    final ObjectMapper json = new ObjectMapper();
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final Path evidence;

    LeadPromptAcceptanceTestSupport(Path evidence) throws Exception {
        this.evidence = evidence;
        Files.createDirectories(evidence);
    }

    static Path directory(String property, Path fallback, String prefix) throws Exception {
        String configured = System.getProperty(property);
        Path root = configured == null ? fallback : Path.of(configured).toAbsolutePath();
        Files.createDirectories(root);
        return Files.createDirectory(root.resolve(prefix + UUID.randomUUID()));
    }

    HttpResponse<String> request(URI base, String method, String path, String body, String evidenceName)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(150));
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

    JsonNode get(URI base, String path, String evidenceName) throws Exception {
        var response = request(base, "GET", path, null, evidenceName);
        assertThat(response.statusCode()).as("HTTP GET %s; evidence=%s", path, evidenceName).isEqualTo(200);
        return json.readTree(response.body());
    }

    void save(String name, Object value) throws Exception {
        Files.write(evidence.resolve(name), json.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
    }

    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static String location(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("SSE HTTP status").isEqualTo(200);
        assertThat(response.body()).as("complete SSE stream").contains("event: end");
        return response.headers().firstValue("content-location").orElseThrow();
    }

    // Reflection is restricted to this product's ThreadRecord and map; the returned bridge uses
    // only its existing SDK public API wrappers to retrieve diagnostics and snapshots.
    static HaifaRunBridge bridge(DeerFlowBootstrapApi api, String threadId) throws Exception {
        Field threads = DeerFlowThreadRunApi.class.getDeclaredField("threads");
        threads.setAccessible(true);
        Object record = ((Map<?, ?>) threads.get(api.threadRunApi())).get(threadId);
        assertThat(record).as("real SH ThreadRecord").isNotNull();
        Field field = record.getClass().getDeclaredField("bridge");
        field.setAccessible(true);
        HaifaRunBridge bridge = (HaifaRunBridge) field.get(record);
        assertThat(bridge).as("bridge attached by actual HTTP/recovery path").isNotNull();
        return bridge;
    }

    void diagnostics(DeerFlowBootstrapApi api, String threadId, String runId) throws Exception {
        HaifaRunBridge bridge = bridge(api, threadId);
        var diagnostics = bridge.promptDiagnostics(new AgentRunId(runId));
        List<Map<String, Object>> components = new ArrayList<>();
        diagnostics.components().forEach(component -> components.add(Map.of(
                "source", String.valueOf(component.source()), "layer", component.layer(),
                "contentDigest", component.contentDigest(), "estimatedTokens", component.estimatedTokens())));
        save("prompt-diagnostics.json", Map.of("run_id", runId,
                "promptSignature", bridge.promptSignature(), "available", diagnostics.available(),
                "status", diagnostics.statusCode(), "components", components));
        assertThat(diagnostics.available()).as("real Run prompt diagnostics").isTrue();
        var instructions = diagnostics.components().stream()
                .filter(component -> "AGENT_INSTRUCTIONS".equals(String.valueOf(component.source()))).toList();
        assertThat(instructions).as("AGENT_INSTRUCTIONS component").hasSize(1);
        assertThat(instructions.get(0).contentDigest().replace("sha256:", ""))
                .as("SDK digest matches exact Bridge instructions signature")
                .isEqualTo(bridge.promptSignature());
        assertThat(instructions.get(0).estimatedTokens()).isPositive();
    }

    static String approval(String requestId) {
        return """
                {"assistant_id":"lead_agent","input":{"messages":[{"id":"approval-response",
                "type":"human","content":[{"type":"text","text":"approve"}],
                "additional_kwargs":{"hide_from_ui":true,"human_input_response":{
                "version":1,"kind":"human_input_response","source":"haifa_interaction",
                "request_id":"%s","response_kind":"option","option_id":"approve","value":"approve"}}}]},
                "stream_mode":["messages-tuple","updates","custom"],
                "on_disconnect":"continue","multitask_strategy":"reject"}
                """.formatted(requestId);
    }

    static void portReleased(int port) throws Exception {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        }
    }
}

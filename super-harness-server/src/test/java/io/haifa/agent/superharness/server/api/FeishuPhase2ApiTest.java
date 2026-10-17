package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.superharness.server.channels.feishu.FeishuInboundMessage;
import io.haifa.agent.superharness.server.channels.feishu.FeishuInteraction;
import io.haifa.agent.superharness.server.channels.feishu.RecordingFeishuTransport;
import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 2 Feishu API: mode persistence and Phase 1 regression through the real bridge. */
class FeishuPhase2ApiTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @TempDir
    Path dataDirectory;

    private DeerFlowBootstrapApi api;
    private URI baseUri;
    private final AtomicReference<RecordingFeishuTransport> transportRef = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        api = DeerFlowBootstrapApi.createForTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory,
                ignored -> LocalProbeModelTestSupport.binding());
        api.channelsApi().overrideFeishuTransportFactory(() -> {
            RecordingFeishuTransport transport = new RecordingFeishuTransport();
            transportRef.set(transport);
            return transport;
        });
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
    }

    @AfterEach
    void stopServer() {
        if (api != null) {
            api.close();
        }
    }

    @Test
    void modeCommandSurvivesIntoNextTurnContextMetadata() throws Exception {
        postJson(
                "/api/channels/feishu/runtime-config",
                "{\"values\":{\"app_id\":\"cli_a\",\"app_secret\":\"secret-a\"}}");
        String code = postJson("/api/channels/feishu/connect", "").path("code").asText();
        RecordingFeishuTransport transport = transportRef.get();
        api.feishuWorker()
                .handleInbound(new FeishuInboundMessage(
                        "oc_mode", "om_bind", "ou_mode", "/connect " + code, "p2p"));
        transport.replies().clear();

        api.feishuWorker()
                .handleInbound(new FeishuInboundMessage(
                        "oc_mode", "om_mode", "ou_mode", "/mode pro", "p2p"));
        assertThat(transport.replies().getLast().text())
                .contains(FeishuInteraction.MODE_UPDATED)
                .contains("pro");

        JsonNode listed = get("/api/channels/connections");
        assertThat(listed.path("connections").get(0).path("metadata").path("run_mode").asText())
                .isEqualTo("pro");

        // Probe model has no thinking support; switch back to flash before a real Turn so mode
        // persistence is proven without requiring a thinking-capable binding.
        transport.replies().clear();
        api.feishuWorker()
                .handleInbound(new FeishuInboundMessage(
                        "oc_mode", "om_flash", "ou_mode", "/mode flash", "p2p"));
        assertThat(transport.replies().getLast().text()).contains("flash");

        transport.replies().clear();
        api.feishuWorker()
                .handleInbound(new FeishuInboundMessage(
                        "oc_mode", "om_chat", "ou_mode", "hello mode", "p2p"));
        assertThat(transport.replies())
                .anyMatch(reply -> reply.text().contains("Haifa Agent SDK: hello mode"));
        // Stop with no active run is a bounded notice (Phase 2 command path).
        transport.replies().clear();
        api.feishuWorker()
                .handleInbound(new FeishuInboundMessage(
                        "oc_mode", "om_stop", "ou_mode", "/stop", "p2p"));
        assertThat(transport.replies().getLast().text()).isEqualTo(FeishuInteraction.STOP_FAILED);
    }

    private JsonNode postJson(String path, String body) throws Exception {
        HttpResponse<String> response = postJsonRaw(path, body);
        assertThat(response.statusCode()).isBetween(200, 299);
        return json.readTree(response.body());
    }

    private HttpResponse<String> postJsonRaw(String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve(path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(baseUri.resolve(path))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }
}

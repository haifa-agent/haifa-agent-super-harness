package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.superharness.server.channels.feishu.FeishuInboundMessage;
import io.haifa.agent.superharness.server.channels.feishu.FeishuOpenDomain;
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

/**
 * L1b Feishu worker: domain allowlist, lifecycle, /connect bind, bound chat → Run → outbound reply.
 */
class FeishuChannelWorkerTest {
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
    void runtimeConfigAcceptsFeishuAndLarkDomainsAndRejectsEvilHost() throws Exception {
        JsonNode feishu = postJson(
                "/api/channels/feishu/runtime-config",
                "{\"values\":{\"app_id\":\"cli_a\",\"app_secret\":\"secret-a\",\"domain\":\"https://open.feishu.cn\"}}");
        assertThat(feishu.path("configured").asBoolean()).isTrue();
        assertThat(feishu.path("credential_values").path("domain").asText())
                .isEqualTo(FeishuOpenDomain.FEISHU_CN);
        assertThat(feishu.path("credential_values").path("app_secret").asText()).isEqualTo("********");
        assertThat(api.feishuWorker().running()).isTrue();
        assertThat(transportRef.get().domain()).isEqualTo(FeishuOpenDomain.FEISHU_CN);

        JsonNode lark = postJson(
                "/api/channels/feishu/runtime-config",
                "{\"values\":{\"app_id\":\"cli_b\",\"app_secret\":\"secret-b\",\"domain\":\"https://open.larksuite.com\"}}");
        assertThat(lark.path("credential_values").path("domain").asText())
                .isEqualTo(FeishuOpenDomain.LARK_SUITE);
        assertThat(transportRef.get().domain()).isEqualTo(FeishuOpenDomain.LARK_SUITE);

        HttpResponse<String> evil = postJsonRaw(
                "/api/channels/feishu/runtime-config",
                "{\"values\":{\"app_id\":\"cli_c\",\"app_secret\":\"secret-c\",\"domain\":\"https://evil.example\"}}");
        assertThat(evil.statusCode()).isEqualTo(400);
        assertThat(evil.body()).contains("Unsupported Feishu domain");

        JsonNode blankDomain = postJson(
                "/api/channels/feishu/runtime-config",
                "{\"values\":{\"app_id\":\"cli_d\",\"app_secret\":\"secret-d\"}}");
        assertThat(blankDomain.path("credential_values").path("domain").asText())
                .isEqualTo(FeishuOpenDomain.DEFAULT);
    }

    @Test
    void connectCodeBindsAndListsOnConnections() throws Exception {
        postJson(
                "/api/channels/feishu/runtime-config",
                "{\"values\":{\"app_id\":\"cli_a\",\"app_secret\":\"secret-a\"}}");
        String code = postJson("/api/channels/feishu/connect", "").path("code").asText();
        RecordingFeishuTransport transport = transportRef.get();
        api.feishuWorker()
                .handleInbound(new FeishuInboundMessage(
                        "oc_chat_1", "om_1", "ou_user_1", "/connect " + code, "p2p"));

        assertThat(transport.replies())
                .anyMatch(reply -> reply.text().contains("Feishu connected to DeerFlow."));
        JsonNode listed = get("/api/channels/connections");
        assertThat(listed.path("connections")).hasSize(1);
        assertThat(listed.path("connections").get(0).path("external_account_id").asText())
                .isEqualTo("ou_user_1");
        assertThat(listed.path("connections").get(0).path("status").asText()).isEqualTo("connected");
    }

    @Test
    void boundChatCreatesThreadCompletesRunAndReplies() throws Exception {
        postJson(
                "/api/channels/feishu/runtime-config",
                "{\"values\":{\"app_id\":\"cli_a\",\"app_secret\":\"secret-a\"}}");
        String code = postJson("/api/channels/feishu/connect", "").path("code").asText();
        RecordingFeishuTransport transport = transportRef.get();
        api.feishuWorker()
                .handleInbound(new FeishuInboundMessage(
                        "oc_chat_run", "om_bind", "ou_runner", "/connect " + code, "p2p"));
        transport.replies().clear();

        api.feishuWorker()
                .handleInbound(new FeishuInboundMessage(
                        "oc_chat_run", "om_chat", "ou_runner", "hello feishu", "p2p"));

        assertThat(transport.replies())
                .as("outbound replies: %s", transport.replies())
                .isNotEmpty();
        assertThat(transport.replies().getLast().messageId()).isEqualTo("om_chat");
        assertThat(transport.replies().getLast().text()).contains("Haifa Agent SDK: hello feishu");
        assertThat(dataDirectory.resolve("channels").resolve("feishu-thread-map.json")).exists();
        JsonNode map = json.readTree(
                dataDirectory.resolve("channels").resolve("feishu-thread-map.json").toFile());
        assertThat(map.path("mappings").size()).isGreaterThan(0);
        String threadId = map.path("mappings").elements().next().path("thread_id").asText();
        assertThat(threadId).isNotBlank();
        assertThat(api.threadRunApi().conversations().hasThread(threadId)).isTrue();
    }

    @Test
    void unboundChatDoesNotCreateRunOrReplyWithAssistant() throws Exception {
        postJson(
                "/api/channels/feishu/runtime-config",
                "{\"values\":{\"app_id\":\"cli_a\",\"app_secret\":\"secret-a\"}}");
        RecordingFeishuTransport transport = transportRef.get();
        api.feishuWorker()
                .handleInbound(new FeishuInboundMessage(
                        "oc_unbound", "om_u", "ou_stranger", "should not run", "p2p"));

        assertThat(transport.replies()).hasSize(1);
        assertThat(transport.replies().getFirst().text()).contains("Please connect");
        assertThat(transport.replies().getFirst().text()).doesNotContain("Haifa Agent SDK");
        assertThat(dataDirectory.resolve("channels").resolve("feishu-thread-map.json")).doesNotExist();
    }

    @Test
    void deleteRuntimeConfigStopsWorker() throws Exception {
        postJson(
                "/api/channels/feishu/runtime-config",
                "{\"values\":{\"app_id\":\"cli_a\",\"app_secret\":\"secret-a\"}}");
        assertThat(api.feishuWorker().running()).isTrue();
        RecordingFeishuTransport transport = transportRef.get();
        assertThat(transport.running()).isTrue();

        delete("/api/channels/feishu/runtime-config");
        assertThat(api.feishuWorker().running()).isFalse();
        assertThat(transport.running()).isFalse();
    }

    @Test
    void gatewayRestartRestoresFeishuWorkerFromPersistedRuntimeConfig() throws Exception {
        postJson(
                "/api/channels/feishu/runtime-config",
                "{\"values\":{\"app_id\":\"cli_persist\",\"app_secret\":\"secret-persist\","
                        + "\"domain\":\"https://open.larksuite.com\"}}");
        assertThat(api.feishuWorker().running()).isTrue();
        api.close();

        AtomicReference<RecordingFeishuTransport> restarted = new AtomicReference<>();
        api = DeerFlowBootstrapApi.createForTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory,
                ignored -> LocalProbeModelTestSupport.binding());
        api.channelsApi().overrideFeishuTransportFactory(() -> {
            RecordingFeishuTransport transport = new RecordingFeishuTransport();
            restarted.set(transport);
            return transport;
        });
        assertThat(api.feishuWorker().running()).isFalse();
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());

        assertThat(api.feishuWorker().running()).isTrue();
        assertThat(restarted.get()).isNotNull();
        assertThat(restarted.get().running()).isTrue();
        assertThat(restarted.get().appId()).isEqualTo("cli_persist");
        assertThat(restarted.get().domain()).isEqualTo(FeishuOpenDomain.LARK_SUITE);
        assertThat(get("/api/channels/providers").path("providers").get(0).path("configured").asBoolean())
                .isTrue();
    }

    @Test
    void gatewayRestartDoesNotRestoreDisconnectedFeishuWorker() throws Exception {
        postJson(
                "/api/channels/feishu/runtime-config",
                "{\"values\":{\"app_id\":\"cli_gone\",\"app_secret\":\"secret-gone\"}}");
        delete("/api/channels/feishu/runtime-config");
        assertThat(api.feishuWorker().running()).isFalse();
        api.close();

        AtomicReference<RecordingFeishuTransport> restarted = new AtomicReference<>();
        api = DeerFlowBootstrapApi.createForTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory,
                ignored -> LocalProbeModelTestSupport.binding());
        api.channelsApi().overrideFeishuTransportFactory(() -> {
            RecordingFeishuTransport transport = new RecordingFeishuTransport();
            restarted.set(transport);
            return transport;
        });
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());

        assertThat(api.feishuWorker().running()).isFalse();
        assertThat(restarted.get()).isNull();
    }

    @Test
    void groupChatMentionPrefixStillBindsConnectCode() throws Exception {
        postJson(
                "/api/channels/feishu/runtime-config",
                "{\"values\":{\"app_id\":\"cli_a\",\"app_secret\":\"secret-a\"}}");
        String code = postJson("/api/channels/feishu/connect", "").path("code").asText();
        RecordingFeishuTransport transport = transportRef.get();
        api.feishuWorker()
                .handleInbound(new FeishuInboundMessage(
                        "oc_group", "om_group", "ou_group_user", "@_user_1 /connect " + code, "group"));

        assertThat(transport.replies())
                .anyMatch(reply -> reply.text().contains("Feishu connected to DeerFlow."));
        assertThat(get("/api/channels/connections").path("connections")).hasSize(1);
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private JsonNode postJson(String path, String body) throws Exception {
        HttpResponse<String> response = postJsonRaw(path, body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private HttpResponse<String> postJsonRaw(String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path));
        if (body == null || body.isBlank()) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode delete(String path) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(baseUri.resolve(path)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
}

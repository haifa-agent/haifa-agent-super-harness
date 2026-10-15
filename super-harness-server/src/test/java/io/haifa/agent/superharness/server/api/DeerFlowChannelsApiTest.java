package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.superharness.server.auth.AccessOwner;
import io.haifa.agent.superharness.server.auth.LocalAuthService;
import io.haifa.agent.superharness.server.config.ChannelConnectionStore;
import io.haifa.agent.superharness.server.config.ChannelRuntimeConfigStore;
import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * L1 HTTP contract for Feishu IM channel connections (frozen DeerFlow Channels Settings UI).
 * No Feishu WebSocket / lark-oapi; bind uses the package-visible store seam.
 */
class DeerFlowChannelsApiTest {
    private static final String ORIGIN = "http://127.0.0.1:16618";
    private static final String PHRASE = "Sup3r-secret-pass";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @TempDir
    Path dataDirectory;

    private DeerFlowBootstrapApi api;
    private URI baseUri;
    private ChannelConnectionStore connections;
    private ChannelRuntimeConfigStore runtime;

    @BeforeEach
    void startServer() throws Exception {
        connections = new ChannelConnectionStore(json, dataDirectory);
        runtime = new ChannelRuntimeConfigStore(json, dataDirectory);
        api = DeerFlowBootstrapApi.createForTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory,
                ignored -> LocalProbeModelTestSupport.binding());
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
    void providersListFeishuAndMaskSecretsAfterConfigure() throws Exception {
        JsonNode before = get("/api/channels/providers");
        assertThat(before.path("enabled").asBoolean()).isTrue();
        assertThat(before.path("providers")).hasSize(1);
        JsonNode feishu = before.path("providers").get(0);
        assertThat(feishu.path("provider").asText()).isEqualTo("feishu");
        assertThat(feishu.path("display_name").asText()).isEqualTo("Feishu");
        assertThat(feishu.path("auth_mode").asText()).isEqualTo("binding_code");
        assertThat(feishu.path("configured").asBoolean()).isFalse();
        assertThat(feishu.path("connectable").asBoolean()).isFalse();
        assertThat(feishu.path("credential_fields")).extracting(node -> node.path("name").asText())
                .containsExactly("app_id", "app_secret");

        JsonNode configured = postJson(
                "/api/channels/feishu/runtime-config",
                "{\"values\":{\"app_id\":\"cli_test_app\",\"app_secret\":\"feishu-secret-value\"}}");
        assertThat(configured.path("configured").asBoolean()).isTrue();
        assertThat(configured.path("connectable").asBoolean()).isTrue();
        assertThat(configured.path("credential_values").path("app_id").asText()).isEqualTo("cli_test_app");
        assertThat(configured.path("credential_values").path("app_secret").asText()).isEqualTo("********");
        assertThat(configured.toString()).doesNotContain("feishu-secret-value");

        JsonNode after = get("/api/channels/providers");
        assertThat(after.path("providers").get(0).path("credential_values").path("app_secret").asText())
                .isEqualTo("********");
        assertThat(after.toString()).doesNotContain("feishu-secret-value");
    }

    @Test
    void connectRequiresConfiguredRuntimeAndReturnsBindingCode() throws Exception {
        HttpResponse<String> denied = postJsonRaw("/api/channels/feishu/connect", "");
        assertThat(denied.statusCode()).isEqualTo(400);
        assertThat(denied.body()).contains("Enter the required Feishu credentials");

        postJson(
                "/api/channels/feishu/runtime-config",
                "{\"values\":{\"app_id\":\"cli_a\",\"app_secret\":\"secret-a\"}}");
        JsonNode connect = postJson("/api/channels/feishu/connect", "");
        assertThat(connect.path("provider").asText()).isEqualTo("feishu");
        assertThat(connect.path("mode").asText()).isEqualTo("binding_code");
        assertThat(connect.path("url").isNull()).isTrue();
        assertThat(connect.path("code").asText()).isNotBlank();
        assertThat(connect.path("expires_in").asInt()).isEqualTo(600);
        assertThat(connect.path("instruction").asText())
                .isEqualTo("Send /connect " + connect.path("code").asText() + " to the DeerFlow Feishu bot.");
    }

    @Test
    void bindSeamListConnectionsAndDisconnect() throws Exception {
        postJson(
                "/api/channels/feishu/runtime-config",
                "{\"values\":{\"app_id\":\"cli_a\",\"app_secret\":\"secret-a\"}}");
        String code = postJson("/api/channels/feishu/connect", "").path("code").asText();

        Optional<ObjectNode> bound = connections.bindFeishuFromConnectCode(
                code,
                "ou_feishu_user_1",
                "oc_chat_1",
                Map.of("chat_id", "oc_chat_1", "message_id", "om_msg_1"));
        assertThat(bound).isPresent();
        assertThat(bound.get().path("status").asText()).isEqualTo("connected");
        assertThat(bound.get().path("external_account_id").asText()).isEqualTo("ou_feishu_user_1");

        JsonNode listed = get("/api/channels/connections");
        assertThat(listed.path("connections")).hasSize(1);
        JsonNode connection = listed.path("connections").get(0);
        assertThat(connection.path("provider").asText()).isEqualTo("feishu");
        assertThat(connection.path("status").asText()).isEqualTo("connected");
        assertThat(connection.path("workspace_id").asText()).isEqualTo("oc_chat_1");
        String connectionId = connection.path("id").asText();

        HttpResponse<String> deleted = deleteRaw("/api/channels/connections/" + connectionId);
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertThat(deleted.body()).isEmpty();

        JsonNode after = get("/api/channels/connections");
        assertThat(after.path("connections")).hasSize(1);
        assertThat(after.path("connections").get(0).path("status").asText()).isEqualTo("revoked");
    }

    @Test
    void ownershipTransferRevokesPriorOwner() throws Exception {
        postJson(
                "/api/channels/feishu/runtime-config",
                "{\"values\":{\"app_id\":\"cli_a\",\"app_secret\":\"secret-a\"}}");

        String codeA = mintConnectCodeForOwner("owner-a");
        connections.bindFeishuFromConnectCode(codeA, "ou_shared", "oc_ws", Map.of());
        assertThat(connections.listConnections("owner-a")).hasSize(1);
        assertThat(connections.listConnections("owner-a").getFirst().path("status").asText())
                .isEqualTo("connected");
        assertThat(connections.listConnections("owner-b")).isEmpty();

        String codeB = mintConnectCodeForOwner("owner-b");
        connections.bindFeishuFromConnectCode(codeB, "ou_shared", "oc_ws", Map.of());

        assertThat(connections.listConnections("owner-a").getFirst().path("status").asText())
                .isEqualTo("revoked");
        assertThat(connections.listConnections("owner-b")).hasSize(1);
        assertThat(connections.listConnections("owner-b").getFirst().path("status").asText())
                .isEqualTo("connected");

        // Default auth-disabled owner must not see the transferred binding.
        JsonNode listed = get("/api/channels/connections");
        assertThat(listed.path("connections")).isEmpty();
    }

    @Test
    void runtimeConfigDeleteClearsCredentialsAndConnections() throws Exception {
        postJson(
                "/api/channels/feishu/runtime-config",
                "{\"values\":{\"app_id\":\"cli_a\",\"app_secret\":\"secret-a\"}}");
        String code = postJson("/api/channels/feishu/connect", "").path("code").asText();
        connections.bindFeishuFromConnectCode(code, "ou_user", "oc_chat", Map.of());
        assertThat(get("/api/channels/connections").path("connections")).hasSize(1);

        JsonNode cleared = delete("/api/channels/feishu/runtime-config");
        assertThat(cleared.path("provider").asText()).isEqualTo("feishu");
        assertThat(cleared.path("configured").asBoolean()).isFalse();
        assertThat(cleared.path("credential_values").size()).isZero();
        assertThat(runtime.getProviderConfig("feishu").path("_runtime_disabled").asBoolean()).isTrue();

        JsonNode connectionsAfter = get("/api/channels/connections");
        assertThat(connectionsAfter.path("connections")).hasSize(1);
        assertThat(connectionsAfter.path("connections").get(0).path("status").asText()).isEqualTo("revoked");

        HttpResponse<String> connectDenied = postJsonRaw("/api/channels/feishu/connect", "");
        assertThat(connectDenied.statusCode()).isEqualTo(400);
    }

    @Test
    void unknownProviderIsRejected() throws Exception {
        assertThat(postJsonRaw("/api/channels/telegram/connect", "").statusCode()).isEqualTo(404);
        assertThat(postJsonRaw(
                                "/api/channels/telegram/runtime-config",
                                "{\"values\":{\"bot_token\":\"x\"}}")
                        .statusCode())
                .isEqualTo(404);
        assertThat(deleteRaw("/api/channels/slack/runtime-config").statusCode()).isEqualTo(404);
    }

    @Test
    void authDisabledLoopbackAllowsRuntimeConfigAsAdmin() throws Exception {
        // Auth-disabled AccessOwner.admin() is true; bindPrefix admin gate is inactive.
        HttpResponse<String> response = postJsonRaw(
                "/api/channels/feishu/runtime-config",
                "{\"values\":{\"app_id\":\"cli_loop\",\"app_secret\":\"secret-loop\"}}");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("configured").asBoolean()).isTrue();
    }

    @Test
    void nonAdminCannotMutateRuntimeConfigWhenSessionAuthActive() throws Exception {
        Path authRoot = dataDirectory.resolve("auth-lane");
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put(LocalAuthService.ENABLED, "1");
        environment.put(LocalAuthService.ALLOWED_ORIGINS, ORIGIN);
        environment.put(LocalServerConfiguration.DATA_DIR_ENV, authRoot.toString());
        DeerFlowBootstrapApi authApi = DeerFlowBootstrapApi.create(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0, true),
                authRoot,
                GatewayFixture.productionEnvironment(environment));
        authApi.start();
        try {
            URI authBase = URI.create("http://127.0.0.1:" + authApi.address().getPort());
            HttpResponse<String> user = send(
                    authBase,
                    "POST",
                    "/api/v1/auth/register",
                    "{\"email\":\"user@example.com\",\"password\":\"" + PHRASE + "\"}",
                    Map.of("Content-Type", "application/json", "Origin", ORIGIN));
            assertThat(user.statusCode()).isEqualTo(201);
            assertThat(json.readTree(user.body()).path("system_role").asText()).isEqualTo("user");
            Session session = Session.from(user);

            HttpResponse<String> denied = send(
                    authBase,
                    "POST",
                    "/api/channels/feishu/runtime-config",
                    "{\"values\":{\"app_id\":\"cli_x\",\"app_secret\":\"secret-x\"}}",
                    session.jsonHeaders(ORIGIN));
            assertThat(denied.statusCode()).isEqualTo(403);
            assertThat(denied.body()).contains("Admin privileges");

            HttpResponse<String> deleteDenied = send(
                    authBase,
                    "DELETE",
                    "/api/channels/feishu/runtime-config",
                    null,
                    session.headers(ORIGIN));
            assertThat(deleteDenied.statusCode()).isEqualTo(403);
        } finally {
            authApi.close();
        }
    }

    @Test
    void roleParameterizedHandlerRejectsNonAdminRuntimeMutations() throws Exception {
        var store = new ChannelConnectionStore(json, dataDirectory.resolve("role-param"));
        var runtimeStore = new ChannelRuntimeConfigStore(json, dataDirectory.resolve("role-param"));
        var handler = new DeerFlowChannelsApi(json, runtimeStore, store);
        HttpServer roleServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        roleServer.createContext("/api/channels", exchange -> {
            AccessOwner.bind("user-1", "user");
            try {
                handler.handle(exchange);
            } finally {
                AccessOwner.clear();
            }
        });
        roleServer.start();
        try {
            URI roleBase = URI.create("http://127.0.0.1:" + roleServer.getAddress().getPort());
            HttpResponse<String> denied = client.send(
                    HttpRequest.newBuilder(roleBase.resolve("/api/channels/feishu/runtime-config"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"values\":{\"app_id\":\"cli\",\"app_secret\":\"s\"}}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(denied.statusCode()).isEqualTo(403);
            assertThat(denied.body()).contains(DeerFlowChannelsApi.ADMIN_REQUIRED);
        } finally {
            roleServer.stop(0);
        }
    }

    private String mintConnectCodeForOwner(String ownerId) {
        String code = ChannelConnectionStore.newBindingCode();
        boolean inserted = AccessOwner.call(ownerId, () -> connections.createOauthStateWithinCap(
                ownerId,
                "feishu",
                code,
                java.time.Instant.now().plusSeconds(ChannelConnectionStore.STATE_TTL_SECONDS),
                ChannelConnectionStore.MAX_PENDING_CONNECT_CODES_PER_PROVIDER));
        assertThat(inserted).isTrue();
        return code;
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private JsonNode postJson(String path, String body) throws Exception {
        HttpResponse<String> response = postJsonRaw(path, body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private HttpResponse<String> postJsonRaw(String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path));
        if (body == null || body.isEmpty()) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode delete(String path) throws Exception {
        HttpResponse<String> response = deleteRaw(path);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private HttpResponse<String> deleteRaw(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve(path)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(
            URI base, String method, String path, String body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path));
        if (headers != null) {
            headers.forEach(request::header);
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static final class Session {
        private final String accessToken;
        private final String csrfToken;

        private Session(String accessToken, String csrfToken) {
            this.accessToken = accessToken;
            this.csrfToken = csrfToken;
        }

        static Session from(HttpResponse<String> response) {
            String access = cookieValue(response, "access_token");
            String csrf = cookieValue(response, "csrf_token");
            return new Session(access, csrf);
        }

        Map<String, String> headers(String origin) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("Cookie", "access_token=" + accessToken + "; csrf_token=" + csrfToken);
            values.put("X-CSRF-Token", csrfToken);
            values.put("Origin", origin);
            return values;
        }

        Map<String, String> jsonHeaders(String origin) {
            Map<String, String> headers = headers(origin);
            headers.put("Content-Type", "application/json");
            return headers;
        }

        private static String cookieValue(HttpResponse<?> response, String name) {
            String setCookie = response.headers().allValues("set-cookie").stream()
                    .filter(value -> value.startsWith(name + "="))
                    .findFirst()
                    .orElseThrow();
            int start = setCookie.indexOf('=');
            int end = setCookie.indexOf(';');
            return setCookie.substring(start + 1, end);
        }
    }
}

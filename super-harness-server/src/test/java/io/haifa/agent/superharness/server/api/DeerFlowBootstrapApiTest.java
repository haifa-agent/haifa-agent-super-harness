package io.haifa.agent.superharness.server.api;

import java.util.Map;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeerFlowBootstrapApiTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    @TempDir
    Path dataDirectory;
    private DeerFlowBootstrapApi api;
    private URI baseUri;

    @BeforeEach
    void startServer() throws Exception {
        api =
                DeerFlowBootstrapApi.create(
                        new LocalServerConfiguration(
                                "local", InetAddress.getByName("127.0.0.1"), 0),
                        dataDirectory,
                        GatewayFixture.productionEnvironment(Map.of()));
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
    }

    @AfterEach
    void stopServer() {
        api.close();
    }

    @Test
    void authMeMatchesFrozenNoCookieContractAndIgnoresForgedIdentity() throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(baseUri.resolve("/api/v1/auth/me"))
                        .header("X-Expected-User-Id", "attacker")
                        .header("X-User-Id", "attacker")
                        .header("X-System-Role", "owner")
                        .header("Authorization", "Bearer forged")
                        .GET()
                        .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type")).contains("application/json");
        assertThat(json(response.body()))
                .isEqualTo(
                        json(
                                """
                                {
                                  "id": "default",
                                  "email": "default@test.local",
                                  "system_role": "admin",
                                  "needs_setup": false,
                                  "oauth_provider": null,
                                  "permissions": [
                                    "threads:read", "threads:write", "threads:delete",
                                    "runs:create", "runs:read", "runs:cancel",
                                    "projects:read", "projects:write", "projects:delete"
                                  ]
                                }
                                """));
    }

    @Test
    void setupStatusKeepsLocalModeOutOfAccountSetup() throws Exception {
        assertGetJson(
                "/api/v1/auth/setup-status",
                """
                {"needs_setup":false,"registration_enabled":false}
                """);
    }

    @Test
    void featuresFailClosedForCapabilitiesNotYetImplemented() throws Exception {
        assertGetJson(
                "/api/features",
                """
                {
                  "agents_api":{"enabled":true},
                  "browser_control":{"enabled":false},
                  "mcp_tasks":{"enabled":false},
                  "subagent_batches":{
                    "enabled":false,
                    "repository_available":false,
                    "worker_running":false,
                    "max_running":3
                  },
                  "conversation_references":{"enabled":false,"max_references":3},
                  "knowledge_base":{"scope_selection_enabled":false}
                }
                """);
    }

    @Test
    void modelsUseFrozenSnakeCaseDefaultsWithoutInventingAvailability() throws Exception {
        assertGetJson(
                "/api/models",
                """
                {"models":[],"token_usage":{"enabled":false}}
                """);
    }

    @Test
    void listenerAndPeerAreLoopbackOnly() throws Exception {
        assertThat(api.address().getAddress().isLoopbackAddress()).isTrue();
        assertGetJson(
                "/health",
                """
                {"status":"ok"}
                """);
    }

    @Test
    void unsupportedMethodsAndUnknownPathsReturnJsonErrors() throws Exception {
        HttpResponse<String> methodResponse =
                client.send(
                        HttpRequest.newBuilder(baseUri.resolve("/api/models"))
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(methodResponse.statusCode()).isEqualTo(405);
        assertThat(methodResponse.headers().firstValue("allow")).contains("GET");
        assertThat(json(methodResponse.body()))
                .isEqualTo(
                        json(
                                """
                                {"detail":"Method Not Allowed"}
                                """));

        HttpResponse<String> missingResponse =
                client.send(
                        HttpRequest.newBuilder(baseUri.resolve("/api/not-implemented")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(missingResponse.statusCode()).isEqualTo(404);
        assertThat(json(missingResponse.body()))
                .isEqualTo(
                        json(
                                """
                                {"detail":"Not Found"}
                                """));
    }

    private void assertGetJson(String path, String expected) throws Exception {
        HttpResponse<String> response =
                client.send(
                        HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        assertThat(json(response.body())).isEqualTo(json(expected));
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}

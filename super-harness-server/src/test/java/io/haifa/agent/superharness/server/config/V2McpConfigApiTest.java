package io.haifa.agent.superharness.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.starter.McpServerSpec;
import io.haifa.agent.superharness.server.api.DeerFlowBootstrapApi;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * L1 contract coverage for the frozen DeerFlow MCP configuration REST projection
 * (0..N servers, duplicate rejection, independent enable/disable, masking and the
 * config -> SDK declaration mapping). No MCP transport is exercised here.
 */
class V2McpConfigApiTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    @TempDir
    Path dataDirectory;
    private DeerFlowBootstrapApi api;
    private URI baseUri;

    @BeforeEach
    void startServer() throws Exception {
        api = DeerFlowBootstrapApi.createForTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory,
                ignored -> LocalProbeModelTestSupport.binding());
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
    }

    @AfterEach
    void stopServer() {
        api.close();
    }

    @Test
    void getEmptyPatchAndMaskRoundTripAFullServerCollection() throws Exception {
        assertThat(get("/api/mcp/config").path("mcp_servers").isObject()).isTrue();
        assertThat(get("/api/mcp/config").path("mcp_servers").size()).isZero();

        JsonNode added = postJson(
                "/api/mcp/config/servers",
                """
                {"mcp_servers":{
                  "v2-reporting":{"type":"http","url":"https://reporting.example/mcp",
                    "tools":{"get_reporting_policy":{},"get_status":{}},
                    "headers":{"Authorization":"$REPORTING_TOKEN"}},
                  "v2-region":{"type":"http","url":"https://region.example/mcp",
                    "tools":{"get_region_label":{},"get_status":{}}}}}
                """);
        assertThat(added.path("mcp_servers").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("v2-reporting", "v2-region");

        JsonNode masked = get("/api/mcp/config");
        JsonNode reporting = masked.path("mcp_servers").path("v2-reporting");
        assertThat(reporting.path("headers").path("Authorization").asText()).isEqualTo("***");
        assertThat(reporting.path("type").asText()).isEqualTo("http");
        assertThat(reporting.path("tool_name_prefix").asBoolean()).isTrue();

        JsonNode patched = patch("/api/mcp/config", "{\"server_name\":\"v2-region\",\"enabled\":false}");
        assertThat(patched.path("mcp_servers").path("v2-region").path("enabled").asBoolean())
                .isFalse();
        assertThat(patched.path("mcp_servers").path("v2-reporting").path("enabled").asBoolean())
                .isTrue();

        JsonNode third = postJson(
                "/api/mcp/config/servers",
                "{\"mcp_servers\":{\"v2-extra\":{\"type\":\"http\",\"url\":\"https://extra.example/mcp\","
                        + "\"tools\":{\"get_status\":{}}}}}");
        assertThat(third.path("mcp_servers").size()).isEqualTo(3);

        JsonNode deleted = delete("/api/mcp/config/servers/v2-extra");
        assertThat(deleted.path("mcp_servers").has("v2-extra")).isFalse();
        assertThat(deleted.path("mcp_servers").size()).isEqualTo(2);
    }

    @Test
    void duplicateServerIdIsRejectedWithConflict() throws Exception {
        postJson(
                "/api/mcp/config/servers",
                "{\"mcp_servers\":{\"v2-reporting\":{\"type\":\"http\",\"url\":\"https://reporting.example/mcp\","
                        + "\"tools\":{\"get_status\":{}}}}}");
        HttpResponse<String> duplicate = postJsonRaw(
                "/api/mcp/config/servers",
                "{\"mcp_servers\":{\"v2-reporting\":{\"type\":\"http\",\"url\":\"https://other.example/mcp\","
                        + "\"tools\":{\"get_status\":{}}}}}");
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(duplicate.body()).contains("already exists");
        // The conflicting add must not replace the existing server.
        assertThat(get("/api/mcp/config")
                        .path("mcp_servers")
                        .path("v2-reporting")
                        .path("url")
                        .asText())
                .isEqualTo("https://reporting.example/mcp");
    }

    @Test
    void invalidServerConfigurationsFailClosed() throws Exception {
        assertThat(postJsonRaw(
                                "/api/mcp/config/servers",
                                "{\"mcp_servers\":{\"Bad_Id\":{\"type\":\"http\",\"url\":\"https://x.example/mcp\","
                                        + "\"tools\":{\"get_status\":{}}}}}")
                        .statusCode())
                .isEqualTo(422);
        assertThat(postJsonRaw(
                                "/api/mcp/config/servers",
                                "{\"mcp_servers\":{\"v2-x\":{\"type\":\"http\",\"tools\":{\"get_status\":{}}}}}")
                        .statusCode())
                .isEqualTo(422);
        assertThat(postJsonRaw(
                                "/api/mcp/config/servers",
                                "{\"mcp_servers\":{\"v2-x\":{\"type\":\"http\",\"url\":\"https://x.example/mcp\","
                                        + "\"tools\":\"nope\"}}}")
                        .statusCode())
                .isEqualTo(422);
        assertThat(patchRaw("/api/mcp/config", "{\"server_name\":\"missing\",\"enabled\":false}")
                        .statusCode())
                .isEqualTo(404);
    }

    @Test
    void mcpPluginsTriStateMatchesFrozenNoneAllSemantics() {
        assertThat(McpRunTools.Selection.fromJson(json.nullNode()).admits("a")).isTrue();
        assertThat(McpRunTools.Selection.fromJson(json.createArrayNode()).admits("a")).isFalse();
        var only = McpRunTools.Selection.fromJson(json.createArrayNode().add("a"));
        assertThat(only.admits("a")).isTrue();
        assertThat(only.admits("b")).isFalse();
        assertThatThrownBy(() -> McpRunTools.Selection.fromJson(json.createArrayNode().add("")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configMapsToPublicSdkDeclarationsWithoutHardcodedServerNames() {
        Map<String, ObjectNode> servers = new LinkedHashMap<>();
        servers.put("alpha", serverNode("http://127.0.0.1:1234/mcp", true, true, "{\"t1\":{},\"t2\":{}}"));
        servers.put("beta", serverNode("https://beta.example/mcp", false, false, "{\"t3\":{}}"));
        servers.put("gamma", serverNode("https://gamma.example/mcp", true, false, "{}"));
        List<String> diagnostics = new ArrayList<>();

        List<McpServerSpec> specs = McpRunTools.specs(servers, McpRunTools.Selection.all(), diagnostics);

        assertThat(specs).hasSize(1);
        McpServerSpec alpha = specs.getFirst();
        assertThat(alpha.name()).isEqualTo("alpha");
        assertThat(alpha.endpoint()).isEqualTo(URI.create("http://127.0.0.1:1234/mcp"));
        assertThat(alpha.allowedTools()).containsExactlyInAnyOrder("t1", "t2");
        assertThat(alpha.toolNamePrefix()).isEqualTo("alpha");
        assertThat(alpha.requirement().name()).isEqualTo("OPTIONAL");
        // Disabled (beta) and empty-allowlist (gamma) servers are never faked.
        assertThat(diagnostics).anyMatch(line -> line.startsWith("gamma:"));
    }

    @Test
    void readOnlyServerProjectsAutoApprovableLocalToolNamesWhileWritableServerDoesNot() {
        Map<String, ObjectNode> servers = new LinkedHashMap<>();
        servers.put("ro", serverNode("https://ro.example/mcp", true, true, "{\"get_status\":{},\"get_more\":{}}"));
        servers.put("rw", serverNode("https://rw.example/mcp", true, false, "{\"get_status\":{}}"));
        servers.put("disabled", serverNode("https://off.example/mcp", false, true, "{\"get_status\":{}}"));
        List<String> diagnostics = new ArrayList<>();

        McpRunTools.Projection projection =
                McpRunTools.projection(servers, McpRunTools.Selection.all(), diagnostics);

        assertThat(projection.readOnlyToolNames())
                .containsExactlyInAnyOrder("ro_get_status", "ro_get_more");
    }

    @Test
    void canonicalFingerprintCoversCredentialRefsTimeoutsAndReadOnlyWithoutPlaintext() {
        ObjectNode baseline = serverNode(
                "https://reporting.example/mcp", true, true, "{\"get_status\":{}}");
        baseline.putObject("headers").put("Authorization", "$REPORTING_TOKEN_A");
        baseline.put("session_init_timeout", 20);
        baseline.put("tool_call_timeout", 20);

        String original = fingerprint(baseline);
        assertThat(original).hasSize(64).matches("[0-9a-f]{64}");
        // The fingerprint is a hash only; the credential reference must not leak through it.
        assertThat(original).doesNotContain("REPORTING_TOKEN_A", "Authorization");
        // An unchanged projection must be byte-identical so a later Run never rebuilds.
        assertThat(fingerprint(baseline.deepCopy())).isEqualTo(original);

        ObjectNode credentialChanged = baseline.deepCopy();
        ((ObjectNode) credentialChanged.path("headers")).put("Authorization", "$REPORTING_TOKEN_B");
        assertThat(fingerprint(credentialChanged)).isNotEqualTo(original);

        ObjectNode requestTimeoutChanged = baseline.deepCopy();
        requestTimeoutChanged.put("tool_call_timeout", 21);
        assertThat(fingerprint(requestTimeoutChanged)).isNotEqualTo(original);

        ObjectNode connectTimeoutChanged = baseline.deepCopy();
        connectTimeoutChanged.put("session_init_timeout", 21);
        assertThat(fingerprint(connectTimeoutChanged)).isNotEqualTo(original);

        ObjectNode readOnlyChanged = baseline.deepCopy();
        readOnlyChanged.put("read_only", false);
        assertThat(fingerprint(readOnlyChanged)).isNotEqualTo(original);

        ObjectNode toolsChanged = baseline.deepCopy();
        toolsChanged.putObject("tools").putObject("get_reporting_policy");
        assertThat(fingerprint(toolsChanged)).isNotEqualTo(original);

        // A disabled server contributes no declaration, so it has the empty fingerprint.
        ObjectNode disabled = baseline.deepCopy();
        disabled.put("enabled", false);
        assertThat(fingerprint(disabled)).isEmpty();
    }

    private static String fingerprint(ObjectNode server) {
        Map<String, ObjectNode> servers = new LinkedHashMap<>();
        servers.put("reporting", server);
        return McpRunTools.projection(servers, McpRunTools.Selection.all(), new ArrayList<>())
                .fingerprint();
    }

    private static ObjectNode serverNode(
            String url, boolean enabled, boolean readOnly, String toolsJson) {
        try {
            ObjectNode node = (ObjectNode) new ObjectMapper().readTree(
                    "{\"type\":\"http\",\"url\":\"" + url + "\",\"tools\":" + toolsJson + "}");
            node.put("enabled", enabled);
            node.put("read_only", readOnly);
            return node;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
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
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode patch(String path, String body) throws Exception {
        HttpResponse<String> response = patchRaw(path, body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private HttpResponse<String> patchRaw(String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve(path))
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode delete(String path) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(baseUri.resolve(path)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
}

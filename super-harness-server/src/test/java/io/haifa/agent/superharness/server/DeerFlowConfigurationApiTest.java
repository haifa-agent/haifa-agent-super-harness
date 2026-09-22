package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeerFlowConfigurationApiTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    @TempDir
    Path dataDirectory;
    private DeerFlowBootstrapApi api;
    private URI baseUri;

    @BeforeEach
    void startServer() throws Exception {
        start();
    }

    @AfterEach
    void stopServer() {
        if (api != null) api.close();
    }

    @Test
    void modelSaveIsRevisionProtectedSecretSafeAndRestartPersistent() throws Exception {
        HttpResponse<String> saved = request("PUT", "/api/managed-models", saveModel(null, "test-secret"));
        assertThat(saved.statusCode()).isEqualTo(200);
        JsonNode first = json.readTree(saved.body());
        assertThat(first.path("source").asText()).isEqualTo("managed");
        assertThat(first.path("has_api_key").asBoolean()).isTrue();
        assertThat(saved.body()).doesNotContain("test-secret", "\"api_key\":");
        assertThat(Files.readString(dataDirectory.resolve("owners/default/configuration.json")))
                .doesNotContain("test-secret", "\"api_key\":");
        String revision = first.path("revision").asText();

        assertThat(request("PUT", "/api/managed-models", saveModel("stale", null)).statusCode())
                .isEqualTo(409);
        HttpResponse<String> updated = request("PUT", "/api/managed-models", saveModel(revision, null));
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(updated.body()).contains("\"has_api_key\":true");
        assertThat(updated.body()).doesNotContain("test-secret", "\"api_key\":");

        restart();
        HttpResponse<String> catalog = request("GET", "/api/managed-models", null);
        assertThat(catalog.statusCode()).isEqualTo(200);
        assertThat(catalog.body()).contains("demo-model", "\"has_api_key\":true");
        assertThat(catalog.body()).doesNotContain("test-secret", "\"api_key\":");

        String updatedRevision = json.readTree(updated.body()).path("revision").asText();
        HttpResponse<String> cleared = request("PUT", "/api/managed-models", saveModel(updatedRevision, ""));
        assertThat(cleared.statusCode()).isEqualTo(200);
        assertThat(cleared.body()).contains("\"has_api_key\":false");
        String persisted = Files.readString(dataDirectory.resolve("owners/default/configuration.json"));
        assertThat(persisted).doesNotContain("test-secret", "api_key_ciphertext", "\"api_key\"");
        restart();
        assertThat(request("GET", "/api/managed-models", null).body())
                .contains("demo-model", "\"has_api_key\":false");

        JsonNode usable = json.readTree(request("GET", "/api/models", null).body());
        assertThat(usable.path("models")).isEmpty();
        assertThat(usable.toString()).doesNotContain("test-secret", "base_url");
    }

    @Test
    void missingCredentialAndUnknownProviderFailClosedWithoutFakeProbe() throws Exception {
        HttpResponse<String> missing = request("POST", "/api/managed-models/test", saveModel(null, null));
        assertThat(missing.statusCode()).isEqualTo(422);
        assertThat(missing.body()).contains("credential is required");

        String invalid = saveModel(null, null).replace("openai-compatible", "unregistered-provider");
        HttpResponse<String> unknown = request("POST", "/api/managed-models/test", invalid);
        assertThat(unknown.statusCode()).isEqualTo(422);
        assertThat(unknown.body()).contains("public Haifa openai-compatible provider");
        assertThat(request("GET", "/api/models", null).body()).contains("\"models\":[]");

        JsonNode saved = json.readTree(request("PUT", "/api/managed-models", saveModel(null, "saved-secret")).body());
        String revision = saved.path("revision").asText();
        for (String explicitKey : new String[] {"\"\"", "null"}) {
            HttpResponse<String> explicitClear = request(
                    "POST",
                    "/api/managed-models/test",
                    saveModelWithApiKeyLiteral(revision, explicitKey));
            assertThat(explicitClear.statusCode()).isEqualTo(422);
            assertThat(explicitClear.body())
                    .contains("credential is required")
                    .doesNotContain("saved-secret");
        }
    }

    @Test
    void probeDistinguishesSuccessMissingToolCallAndConnectionFailureWithoutLeaking() throws Exception {
        HttpServer contractServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        DeerFlowConfigurationApi handler = new DeerFlowConfigurationApi(json, dataDirectory.resolve("probe-contract"), config -> {
            return switch (config.path("model").asText()) {
                case "tool" -> probeResponse(List.of(new ModelToolCall(
                        new ProviderToolCallCorrelationId("call-1"), "connection_check", Map.of())));
                case "text" -> probeResponse(List.of());
                case "error" -> throw new IllegalStateException("provider rejected probe-secret");
                default -> throw new AssertionError("unexpected test model");
            };
        });
        contractServer.createContext("/api/managed-models/test", handler);
        contractServer.start();
        try {
            URI contractBase = URI.create("http://127.0.0.1:" + contractServer.getAddress().getPort());
            HttpResponse<String> success = requestAt(
                    contractBase,
                    "POST",
                    "/api/managed-models/test",
                    withModel(saveModel(null, "probe-secret"), "tool"));
            assertThat(success.statusCode()).isEqualTo(200);
            assertThat(json.readTree(success.body()))
                    .isEqualTo(json.readTree("{\"message\":\"success\",\"ok\":true}"));

            HttpResponse<String> missingTool = requestAt(
                    contractBase,
                    "POST",
                    "/api/managed-models/test",
                    withModel(saveModel(null, "probe-secret"), "text"));
            assertThat(missingTool.statusCode()).isEqualTo(200);
            assertThat(json.readTree(missingTool.body()))
                    .isEqualTo(json.readTree("{\"message\":\"tool_call_missing\",\"ok\":false}"));
            assertThat(missingTool.body()).doesNotContain("probe-secret");

            HttpResponse<String> connectionFailed = requestAt(
                    contractBase,
                    "POST",
                    "/api/managed-models/test",
                    withModel(saveModel(null, "probe-secret"), "error"));
            assertThat(connectionFailed.statusCode()).isEqualTo(200);
            assertThat(json.readTree(connectionFailed.body()))
                    .isEqualTo(json.readTree("{\"message\":\"connection_failed\",\"ok\":false}"));
            assertThat(connectionFailed.body()).doesNotContain("probe-secret", "provider rejected");
        } finally {
            contractServer.stop(0);
        }
    }

    @Test
    void modelRequestEnvelopeRejectsExtraFields() throws Exception {
        ObjectNode body = (ObjectNode) json.readTree(saveModel(null, "test-secret"));
        body.put("unexpected", true);
        for (String path : new String[] {"/api/managed-models", "/api/managed-models/test"}) {
            HttpResponse<String> response = request(path.endsWith("test") ? "POST" : "PUT", path, body.toString());
            assertThat(response.statusCode()).isEqualTo(422);
            assertThat(response.body()).contains("Unknown field 'unexpected'").doesNotContain("test-secret");
        }
    }

    @Test
    void agentCrudPreservesNoneEmptyFalseSoulAndUneditedFieldsAcrossRestart() throws Exception {
        assertThat(request("PUT", "/api/managed-models", saveModel(null, "test-secret")).statusCode())
                .isEqualTo(200);
        String create =
                """
                {
                  "name":"Research-Lead",
                  "display_name":"Research Lead",
                  "description":"source grounded",
                  "model":null,
                  "tool_groups":null,
                  "skills":[],
                  "mcp_plugins":null,
                  "allowed_subagents":[],
                  "model_settings":{"temperature":0.2,"max_tokens":128},
                  "thinking_enabled":false,
                  "reasoning_effort":"low",
                  "soul":"Always cite the source."
                }
                """;
        HttpResponse<String> created = request("POST", "/api/agents", create);
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode agent = json.readTree(created.body());
        assertThat(agent.path("name").asText()).isEqualTo("research-lead");
        assertThat(agent.get("tool_groups").isNull()).isTrue();
        assertThat(agent.path("skills").isArray()).isTrue();
        assertThat(agent.path("skills")).isEmpty();
        assertThat(agent.path("thinking_enabled").asBoolean()).isFalse();

        HttpResponse<String> updated = request(
                "PUT",
                "/api/agents/research-lead",
                "{\"display_name\":\"Evidence Lead\",\"model_settings\":{\"temperature\":0.7},\"soul\":null}");
        assertThat(updated.statusCode()).isEqualTo(200);
        JsonNode afterUpdate = json.readTree(updated.body());
        assertThat(afterUpdate.path("display_name").asText()).isEqualTo("Evidence Lead");
        assertThat(afterUpdate.path("soul").asText()).isEqualTo("Always cite the source.");
        assertThat(afterUpdate.get("model").isNull()).isTrue();
        assertThat(afterUpdate.path("model_settings").path("temperature").asDouble()).isEqualTo(0.7);
        assertThat(afterUpdate.path("model_settings").path("max_tokens").asInt()).isEqualTo(128);

        restart();
        JsonNode afterRestart = json.readTree(request("GET", "/api/agents/research-lead", null).body());
        assertThat(afterRestart).isEqualTo(afterUpdate);
        assertThat(request("GET", "/api/agents/check?name=Research-Lead", null).body())
                .contains("\"available\":false", "\"name\":\"research-lead\"");
        assertThat(request("DELETE", "/api/agents/research-lead", null).statusCode()).isEqualTo(204);
        assertThat(request("GET", "/api/agents/research-lead", null).statusCode()).isEqualTo(404);
    }

    @Test
    void invalidModelAndModeAreRejectedInsteadOfFallingBack() throws Exception {
        HttpResponse<String> unknownModel = request(
                "POST",
                "/api/agents",
                "{\"name\":\"bad-model\",\"model\":\"w02-local-probe\",\"soul\":\"x\"}");
        assertThat(unknownModel.statusCode()).isEqualTo(422);
        assertThat(unknownModel.body()).contains("not configured and usable");

        HttpResponse<String> badMode = request(
                "POST",
                "/api/agents",
                "{\"name\":\"bad-mode\",\"reasoning_effort\":\"ultra\",\"soul\":\"x\"}");
        assertThat(badMode.statusCode()).isEqualTo(422);
        assertThat(badMode.body()).contains("Invalid reasoning_effort");

        assertThat(request("POST", "/api/threads", "{\"thread_id\":\"w03-context\"}").statusCode())
                .isEqualTo(200);
        String input =
                "\"input\":{\"messages\":[{\"type\":\"human\",\"content\":\"hello\"}]},"
                        + "\"stream_mode\":[\"messages-tuple\",\"updates\",\"custom\"]";
        HttpResponse<String> unsupportedMode = request(
                "POST",
                "/api/threads/w03-context/runs/stream",
                "{" + input + ",\"context\":{\"mode\":\"turbo\"}}");
        assertThat(unsupportedMode.statusCode()).isEqualTo(422);
        assertThat(unsupportedMode.body()).contains("Unsupported mode 'turbo'");

        HttpResponse<String> unavailableModel = request(
                "POST",
                "/api/threads/w03-context/runs/stream",
                "{" + input + ",\"context\":{\"mode\":\"flash\",\"model_name\":\"demo-model\"}}");
        assertThat(unavailableModel.statusCode()).isEqualTo(422);
        assertThat(unavailableModel.body()).contains("not available for Run execution");
    }

    @Test
    void forgedIdentityHeadersCannotEscapeServerSelectedOwner() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/managed-models"))
                .header("X-User-Id", "attacker")
                .header("X-Expected-User-Id", "attacker")
                .header("X-System-Role", "owner")
                .header("Authorization", "Bearer forged")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"models\":[]}");
        assertThat(dataDirectory.resolve("owners/default/configuration.json")).exists();
        assertThat(dataDirectory.resolve("owners/attacker/configuration.json")).doesNotExist();
    }

    @Test
    void oversizedConfigurationIsRejectedWithoutPublishingPartialState() throws Exception {
        String oversized = "{\"name\":\"large\",\"soul\":\"" + "x".repeat(140_000) + "\"}";
        HttpResponse<String> response = request("POST", "/api/agents", oversized);
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(request("GET", "/api/agents", null).body()).isEqualTo("{\"agents\":[]}");
    }

    private void start() throws Exception {
        api = DeerFlowBootstrapApi.create(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory);
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
    }

    private void restart() throws Exception {
        api.close();
        api = null;
        start();
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        return requestAt(baseUri, method, path, body);
    }

    private HttpResponse<String> requestAt(URI root, String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(root.resolve(path));
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String saveModel(String revision, String apiKey) {
        String revisionJson = revision == null ? "null" : "\"" + revision + "\"";
        String key = apiKey == null ? "" : ",\"api_key\":\"" + apiKey + "\"";
        return """
                {"config":{
                  "name":"demo-model","display_name":"Demo Model",
                  "provider":"openai-compatible","model":"demo-provider-id",
                  "base_url":"https://provider.invalid/v1","enabled":true,
                  "supports_vision":false,"context_window":8192,"max_tokens":1024%s
                },"expected_revision":%s}
                """
                .formatted(key, revisionJson);
    }

    private static String saveModelWithApiKeyLiteral(String revision, String apiKeyLiteral) {
        return saveModel(revision, null).replace(
                "\"max_tokens\":1024", "\"max_tokens\":1024,\"api_key\":" + apiKeyLiteral);
    }

    private static String withModel(String request, String model) {
        return request.replace("demo-provider-id", model);
    }

    private static AgentChatResponse probeResponse(List<ModelToolCall> toolCalls) {
        return new AgentChatResponse(
                "response-1",
                "test",
                toolCalls.isEmpty() ? "connected" : "",
                toolCalls,
                toolCalls.isEmpty() ? ModelFinishReason.STOP : ModelFinishReason.TOOL_CALLS,
                ModelUsage.unpriced(1, 1),
                "",
                Map.of());
    }
}

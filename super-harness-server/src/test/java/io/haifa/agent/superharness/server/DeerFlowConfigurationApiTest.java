package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.openai.OpenAiCompatibleDialects;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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
    void osCredentialReferenceWinsWithoutPersistenceOrResponseSecretAndSelectsDeepSeekDialect()
            throws Exception {
        AtomicReference<CredentialRef> resolvedReference = new AtomicReference<>();
        HttpServer contractServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        DeerFlowConfigurationApi handler = new DeerFlowConfigurationApi(
                json,
                dataDirectory.resolve("deepseek-contract"),
                null,
                reference -> {
                    resolvedReference.set(reference);
                    return new ResolvedCredential("resolved-os-secret");
                });
        contractServer.createContext("/api/managed-models", handler);
        contractServer.start();
        try {
            URI contractBase = URI.create("http://127.0.0.1:" + contractServer.getAddress().getPort());
            String request = withCredentialRef(
                    saveModelAt(null, "ignored-api-key", "https://api.deepseek.com"),
                    "os://MODEL_KEY");
            HttpResponse<String> saved = requestAt(contractBase, "PUT", "/api/managed-models", request);
            assertThat(saved.statusCode()).isEqualTo(200);
            JsonNode response = json.readTree(saved.body());
            assertThat(response.path("has_api_key").asBoolean()).isFalse();
            assertThat(response.path("has_credential").asBoolean()).isTrue();
            assertThat(response.path("credential_source").asText()).isEqualTo("os");
            assertThat(saved.body()).doesNotContain("ignored-api-key", "MODEL_KEY", "credential_ref");

            String persisted = Files.readString(dataDirectory.resolve(
                    "deepseek-contract/owners/default/configuration.json"));
            assertThat(persisted)
                    .contains("os://MODEL_KEY")
                    .doesNotContain("ignored-api-key", "resolved-os-secret", "api_key_ciphertext");

            HaifaRunBridge.ModelBinding binding = handler.resolveRunModel(json.readTree(
                    "{\"context\":{\"model_name\":\"demo-model\",\"mode\":\"flash\"}}"));
            assertThat(resolvedReference.get()).isEqualTo(new CredentialRef("os://MODEL_KEY"));
            assertThat(binding.snapshot().credentialRef()).isEqualTo(new CredentialRef("os://MODEL_KEY"));
            assertThat(binding.snapshot().dialect()).isEqualTo(OpenAiCompatibleDialects.DEEPSEEK);
            assertThat(binding.snapshot().endpoint()).isEqualTo(URI.create("https://api.deepseek.com"));
            assertThat(binding.snapshot().invocationOptions()).containsEntry("thinking", "disabled");

            ObjectNode apiKeyFallback =
                    (ObjectNode) json.readTree(saveModelAt(
                            response.path("revision").asText(),
                            "replacement-api-key",
                            "https://api.deepseek.com"));
            ((ObjectNode) apiKeyFallback.path("config")).putNull("credential_ref");
            HttpResponse<String> switched =
                    requestAt(contractBase, "PUT", "/api/managed-models", apiKeyFallback.toString());
            assertThat(switched.statusCode()).isEqualTo(200);
            JsonNode switchedResponse = json.readTree(switched.body());
            assertThat(switchedResponse.path("has_api_key").asBoolean()).isTrue();
            assertThat(switchedResponse.path("has_credential").asBoolean()).isTrue();
            assertThat(switchedResponse.path("credential_source").asText()).isEqualTo("api_key");
            assertThat(switched.body()).doesNotContain("replacement-api-key", "MODEL_KEY");
            String apiKeyPersisted = Files.readString(dataDirectory.resolve(
                    "deepseek-contract/owners/default/configuration.json"));
            assertThat(apiKeyPersisted)
                    .contains("api_key_ciphertext")
                    .doesNotContain("replacement-api-key", "credential_ref", "MODEL_KEY");
        } finally {
            contractServer.stop(0);
        }
    }

    @Test
    void credentialReferenceRejectsUnsupportedOrMalformedSchemes() throws Exception {
        for (String reference : List.of("env://DEEPSEEK_API_KEY", "os://invalid/name", "", "os://1INVALID")) {
            HttpResponse<String> response = request(
                    "PUT",
                    "/api/managed-models",
                    withCredentialRef(saveModel(null, null), reference));
            assertThat(response.statusCode()).isEqualTo(422);
            assertThat(response.body()).contains("credential_ref must use os://NAME");
        }
    }

    @Test
    void disabledCredentialLessAndRemotePlainHttpModelsNeverEnterRunCatalog() throws Exception {
        HttpResponse<String> credentialLess =
                request("PUT", "/api/managed-models", saveModel(null, null));
        assertThat(credentialLess.statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/models", null).body()).contains("\"models\":[]");
        assertThat(request("POST", "/api/threads", "{\"thread_id\":\"unusable-model\"}")
                        .statusCode())
                .isEqualTo(200);
        String run = """
                {"input":{"messages":[{"type":"human","content":"must fail closed"}]},"context":{"model_name":"demo-model","mode":"flash"}}
                """;
        HttpResponse<String> credentialLessRun =
                request("POST", "/api/threads/unusable-model/runs/stream", run);
        assertThat(credentialLessRun.statusCode()).isEqualTo(422);
        assertThat(credentialLessRun.body()).contains("not configured and usable");

        String firstRevision = json.readTree(credentialLess.body()).path("revision").asText();
        String disabled = saveModel(firstRevision, "disabled-secret").replace("\"enabled\":true", "\"enabled\":false");
        HttpResponse<String> disabledSaved = request("PUT", "/api/managed-models", disabled);
        assertThat(disabledSaved.statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/models", null).body())
                .contains("\"models\":[]")
                .doesNotContain("disabled-secret");
        HttpResponse<String> disabledRun =
                request("POST", "/api/threads/unusable-model/runs/stream", run);
        assertThat(disabledRun.statusCode()).isEqualTo(422);
        assertThat(disabledRun.body())
                .contains("not configured and usable")
                .doesNotContain("disabled-secret");

        String disabledRevision = json.readTree(disabledSaved.body()).path("revision").asText();
        HttpResponse<String> remoteHttp = request(
                "PUT",
                "/api/managed-models",
                saveModelAt(disabledRevision, "remote-secret", "http://192.0.2.10/v1"));
        assertThat(remoteHttp.statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/models", null).body())
                .contains("\"models\":[]")
                .doesNotContain("remote-secret", "192.0.2.10");

        HttpResponse<String> rejected =
                request("POST", "/api/threads/unusable-model/runs/stream", run);
        assertThat(rejected.statusCode()).isEqualTo(422);
        assertThat(rejected.body())
                .contains("not configured and usable")
                .doesNotContain("remote-secret", "192.0.2.10");
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

        HttpResponse<String> unsupportedCapability = request(
                "POST",
                "/api/threads/w03-context/runs/stream",
                "{" + input
                        + ",\"context\":{\"mode\":\"flash\",\"thinking_enabled\":true}}");
        assertThat(unsupportedCapability.statusCode()).isEqualTo(422);
        assertThat(unsupportedCapability.body())
                .contains("do not support thinking")
                .doesNotContain("api_key");

        HttpResponse<String> unavailableModel = request(
                "POST",
                "/api/threads/w03-context/runs/stream",
                "{" + input + ",\"context\":{\"mode\":\"flash\",\"model_name\":\"demo-model\"}}");
        assertThat(unavailableModel.statusCode()).isEqualTo(422);
        assertThat(unavailableModel.body()).contains("not configured and usable");
    }

    @Test
    void savedManagedModelDrivesRealSdkRunAndToolCallProbeAgainstControlledProvider()
            throws Exception {
        List<JsonNode> providerRequests = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> authorizations = java.util.Collections.synchronizedList(new ArrayList<>());
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            providerRequests.add(request);
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            boolean probe = request.path("tools").isArray() && !request.path("tools").isEmpty();
            String payload = probe
                    ? """
                    data: {"id":"probe-1","model":"controlled-model","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"connection_check","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}

                    data: {"id":"probe-1","model":"controlled-model","choices":[],"usage":{"prompt_tokens":2,"completion_tokens":1}}

                    data: [DONE]

                    """
                    : """
                    data: {"id":"run-1","model":"controlled-model","choices":[{"index":0,"delta":{"content":"controlled "},"finish_reason":null}]}

                    data: {"id":"run-1","model":"controlled-model","choices":[{"index":0,"delta":{"content":"provider response"},"finish_reason":"stop"}]}

                    data: {"id":"run-1","model":"controlled-model","choices":[],"usage":{"prompt_tokens":4,"completion_tokens":3}}

                    data: [DONE]

                    """;
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        provider.start();
        try {
            String endpoint = "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1";
            HttpResponse<String> saved =
                    request("PUT", "/api/managed-models", saveModelAt(null, "controlled-secret", endpoint));
            assertThat(saved.statusCode()).isEqualTo(200);
            JsonNode savedModel = json.readTree(saved.body());
            String revision = savedModel.path("revision").asText();

            JsonNode catalog = json.readTree(request("GET", "/api/models", null).body());
            assertThat(catalog.path("models")).hasSize(1);
            assertThat(catalog.path("models").get(0).path("name").asText())
                    .isEqualTo("demo-model");
            assertThat(catalog.toString()).doesNotContain("controlled-secret", endpoint);

            HttpResponse<String> probe = request(
                    "POST", "/api/managed-models/test", saveModelAt(revision, null, endpoint));
            assertThat(probe.statusCode()).isEqualTo(200);
            assertThat(json.readTree(probe.body()))
                    .isEqualTo(json.readTree("{\"message\":\"success\",\"ok\":true}"));

            assertThat(request("POST", "/api/threads", "{\"thread_id\":\"managed-run\"}")
                            .statusCode())
                    .isEqualTo(200);
            String runBody = """
                    {"assistant_id":"lead_agent","input":{"messages":[{"type":"human","content":"use the configured provider"}]},"stream_mode":["messages-tuple","updates","custom"],"context":{"model_name":"demo-model","mode":"flash","thinking_enabled":false},"on_disconnect":"continue","multitask_strategy":"reject"}
                    """;
            HttpResponse<String> run =
                    request("POST", "/api/threads/managed-run/runs/stream", runBody);
            assertThat(run.statusCode()).isEqualTo(200);
            assertThat(run.body())
                    .contains("controlled provider response", "event: end")
                    .doesNotContain("controlled-secret");
            String runLocation = run.headers().firstValue("content-location").orElseThrow();
            JsonNode runView = json.readTree(request("GET", runLocation, null).body());
            assertThat(runView.path("status").asText()).isEqualTo("success");
            assertThat(runView.path("llm_call_count").asLong()).isEqualTo(1);

            HttpResponse<String> updated = request(
                    "PUT", "/api/managed-models", saveModelAt(revision, null, endpoint));
            assertThat(updated.statusCode()).isEqualTo(200);
            HttpResponse<String> drifted =
                    request("POST", "/api/threads/managed-run/runs/stream", runBody);
            assertThat(drifted.statusCode()).isEqualTo(409);
            assertThat(drifted.body()).contains("different model revision");

            assertThat(providerRequests).hasSize(2);
            assertThat(providerRequests.get(0).path("tools")).isNotEmpty();
            assertThat(providerRequests.get(0).path("stream").asBoolean()).isTrue();
            assertThat(providerRequests.get(1).path("tools")).isEmpty();
            assertThat(providerRequests.get(1).path("stream").asBoolean()).isTrue();
            assertThat(authorizations).containsOnly("Bearer controlled-secret");
            System.out.println("W03_MANAGED_RUN_EVIDENCE run="
                    + runView.path("run_id").asText()
                    + " model=demo-model revision=" + revision
                    + " provider_calls=" + providerRequests.size());
        } finally {
            provider.stop(0);
        }
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
        return saveModelAt(revision, apiKey, "https://provider.invalid/v1");
    }

    private static String saveModelAt(String revision, String apiKey, String endpoint) {
        String revisionJson = revision == null ? "null" : "\"" + revision + "\"";
        String key = apiKey == null ? "" : ",\"api_key\":\"" + apiKey + "\"";
        return """
                {"config":{
                  "name":"demo-model","display_name":"Demo Model",
                  "provider":"openai-compatible","model":"demo-provider-id",
                  "base_url":"%s","enabled":true,
                  "supports_vision":false,"context_window":8192,"max_tokens":1024%s
                },"expected_revision":%s}
                """
                .formatted(endpoint, key, revisionJson);
    }

    private static String saveModelWithApiKeyLiteral(String revision, String apiKeyLiteral) {
        return saveModel(revision, null).replace(
                "\"max_tokens\":1024", "\"max_tokens\":1024,\"api_key\":" + apiKeyLiteral);
    }

    private static String withCredentialRef(String request, String credentialReference) {
        return request.replace(
                "\"max_tokens\":1024",
                "\"max_tokens\":1024,\"credential_ref\":\"" + credentialReference + "\"");
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

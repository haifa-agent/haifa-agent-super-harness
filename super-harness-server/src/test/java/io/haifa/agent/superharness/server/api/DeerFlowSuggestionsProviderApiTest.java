package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeerFlowSuggestionsProviderApiTest {
    private static final String THREAD = "suggestions-provider-thread";
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    @TempDir
    Path dataDirectory;
    private DeerFlowBootstrapApi api;
    private URI baseUri;

    @BeforeEach
    void startServer() throws Exception {
        api = DeerFlowBootstrapApi.create(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory);
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
        assertThat(request("POST", "/api/threads", "{\"thread_id\":\"" + THREAD + "\"}").statusCode())
                .isEqualTo(200);
    }

    @AfterEach
    void stopServer() {
        if (api != null) api.close();
    }

    @Test
    void suggestionsCallTheConfiguredProviderOverRealHttpWithoutCreatingRunFacts()
            throws Exception {
        List<JsonNode> providerRequests = Collections.synchronizedList(new ArrayList<>());
        List<String> authorizations = Collections.synchronizedList(new ArrayList<>());
        HttpServer provider = controlledProvider(
                providerRequests, authorizations, "[\"How does it work?\",\"What is next?\"]");
        provider.start();
        try {
            String endpoint = "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1";
            assertThat(request("PUT", "/api/managed-models", saveModel(endpoint)).statusCode())
                    .isEqualTo(200);

            HttpResponse<String> response = request(
                    "POST",
                    "/api/threads/" + THREAD + "/suggestions",
                    """
                    {"messages":[{"role":"user","content":"Explain the cursor."},
                    {"role":"assistant","content":"Use before_seq."}],"n":2}
                    """);

            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode body = json.readTree(response.body());
            assertThat(body.path("suggestions").get(0).asText()).isEqualTo("How does it work?");
            assertThat(body.path("suggestions").get(1).asText()).isEqualTo("What is next?");
            assertThat(response.body()).doesNotContain("controlled-secret");

            assertThat(providerRequests).hasSize(1);
            JsonNode outbound = providerRequests.get(0);
            assertThat(outbound.path("tools")).isEmpty();
            assertThat(outbound.path("messages")).hasSize(2);
            assertThat(outbound.path("messages").get(0).path("role").asText()).isEqualTo("system");
            assertThat(outbound.path("messages").get(0).path("content").asText())
                    .contains("EXACTLY 2");
            assertThat(outbound.path("messages").get(1).path("content").asText())
                    .contains("User: Explain the cursor.")
                    .contains("Assistant: Use before_seq.");
            assertThat(authorizations).containsExactly("Bearer controlled-secret");

            assertThat(api.cachedRunCount()).isZero();
            assertThat(api.hasAttachedBridge(THREAD)).isFalse();
        } finally {
            provider.stop(0);
        }
    }

    @Test
    void providerFailureStillReturnsAnEmptySuggestionList() throws Exception {
        List<JsonNode> providerRequests = Collections.synchronizedList(new ArrayList<>());
        List<String> authorizations = Collections.synchronizedList(new ArrayList<>());
        HttpServer provider = controlledProvider(providerRequests, authorizations, "unused");
        provider.start();
        try {
            String endpoint = "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1";
            assertThat(request("PUT", "/api/managed-models", saveModel(endpoint)).statusCode())
                    .isEqualTo(200);

            HttpResponse<String> response = request(
                    "POST",
                    "/api/threads/" + THREAD + "/suggestions",
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.readTree(response.body()).path("suggestions")).isEmpty();
            assertThat(response.body()).doesNotContain("controlled-secret");
        } finally {
            provider.stop(0);
        }
    }

    private HttpServer controlledProvider(
            List<JsonNode> providerRequests,
            List<String> authorizations,
            String assistantContent) throws Exception {
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            providerRequests.add(request);
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            boolean streaming = request.path("stream").asBoolean(false);
            String payload = streaming
                    ? "data: {\"id\":\"sug-1\",\"model\":\"demo-provider-id\",\"choices\":[{\"index\":0,"
                            + "\"delta\":{\"content\":\"[]\"},\"finish_reason\":null}]}\n\n"
                            + "data: [DONE]\n\n"
                    : "{\"id\":\"sug-1\",\"object\":\"chat.completion\",\"model\":\"demo-provider-id\","
                            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\""
                            + assistantContent.replace("\"", "\\\"")
                            + "\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":5,"
                            + "\"completion_tokens\":5}}";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders()
                    .set("Content-Type", streaming ? "text/event-stream; charset=utf-8" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        return provider;
    }

    private static String saveModel(String endpoint) {
        return """
                {"config":{
                  "name":"demo-model","display_name":"Demo Model",
                  "provider":"openai-compatible","model":"demo-provider-id",
                  "base_url":"%s","enabled":true,
                  "supports_vision":false,"context_window":8192,"max_tokens":1024,
                  "api_key":"controlled-secret"
                },"expected_revision":null}
                """
                .formatted(endpoint);
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path));
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}

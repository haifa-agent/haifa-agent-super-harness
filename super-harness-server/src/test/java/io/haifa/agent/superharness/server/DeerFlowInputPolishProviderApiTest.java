package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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

class DeerFlowInputPolishProviderApiTest {
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
    }

    @AfterEach
    void stopServer() {
        if (api != null) api.close();
    }

    @Test
    void polishCallsTheConfiguredProviderOverRealHttpWithoutCreatingRunFacts() throws Exception {
        List<JsonNode> providerRequests = Collections.synchronizedList(new ArrayList<>());
        List<String> authorizations = Collections.synchronizedList(new ArrayList<>());
        HttpServer provider = controlledProvider(providerRequests, authorizations, false);
        provider.start();
        try {
            String endpoint = "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1";
            HttpResponse<String> saved = request("PUT", "/api/managed-models", saveModel(endpoint));
            assertThat(saved.statusCode()).isEqualTo(200);

            HttpResponse<String> polished =
                    request("POST", "/api/input-polish", "{\"text\":\"rough draft\",\"locale\":\"en-US\"}");

            assertThat(polished.statusCode()).isEqualTo(200);
            JsonNode body = json.readTree(polished.body());
            assertThat(body.path("rewritten_text").asText()).isEqualTo("Polished draft text");
            assertThat(body.path("changed").asBoolean()).isTrue();
            assertThat(polished.body()).doesNotContain("controlled-secret");

            assertThat(providerRequests).hasSize(1);
            JsonNode outbound = providerRequests.get(0);
            assertThat(outbound.path("tools")).isEmpty();
            assertThat(outbound.path("messages")).hasSize(2);
            assertThat(outbound.path("messages").get(0).path("role").asText()).isEqualTo("system");
            assertThat(outbound.path("messages").get(1).path("content").asText())
                    .contains("Locale hint: en-US")
                    .contains("rough draft");
            assertThat(authorizations).containsExactly("Bearer controlled-secret");

            assertThat(api.cachedRunCount()).isZero();
            assertThat(api.hasAttachedBridge("polish-thread")).isFalse();
        } finally {
            provider.stop(0);
        }
    }

    @Test
    void providerFailureBecomes503WithoutLeakingProviderDetails() throws Exception {
        List<JsonNode> providerRequests = Collections.synchronizedList(new ArrayList<>());
        List<String> authorizations = Collections.synchronizedList(new ArrayList<>());
        HttpServer provider = controlledProvider(providerRequests, authorizations, true);
        provider.start();
        try {
            String endpoint = "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1";
            assertThat(request("PUT", "/api/managed-models", saveModel(endpoint)).statusCode())
                    .isEqualTo(200);

            HttpResponse<String> polished =
                    request("POST", "/api/input-polish", "{\"text\":\"rough draft\"}");

            assertThat(polished.statusCode()).isEqualTo(503);
            assertThat(json.readTree(polished.body()).path("detail").asText())
                    .isEqualTo("Failed to polish input");
            assertThat(polished.body())
                    .doesNotContain("controlled-secret", "provider exploded", "Bearer");
        } finally {
            provider.stop(0);
        }
    }

    private HttpServer controlledProvider(
            List<JsonNode> providerRequests,
            List<String> authorizations,
            boolean fail) throws Exception {
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            providerRequests.add(request);
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            if (fail) {
                byte[] error = "provider exploded".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, error.length);
                exchange.getResponseBody().write(error);
                exchange.close();
                return;
            }
            boolean streaming = request.path("stream").asBoolean(false);
            String payload = streaming
                    ? """
                    data: {"id":"polish-1","model":"demo-provider-id","choices":[{"index":0,"delta":{"content":"Polished draft text"},"finish_reason":null}]}

                    data: {"id":"polish-1","model":"demo-provider-id","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                    data: [DONE]

                    """
                    : """
                    {"id":"polish-1","object":"chat.completion","model":"demo-provider-id","choices":[{"index":0,"message":{"role":"assistant","content":"Polished draft text"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":5}}
                    """;
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

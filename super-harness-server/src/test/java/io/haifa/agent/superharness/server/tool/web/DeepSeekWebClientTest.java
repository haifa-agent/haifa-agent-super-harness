package io.haifa.agent.superharness.server.tool.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeepSeekWebClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String API_KEY = "deepseek-tool-secret-key-456";

    private final List<Map<String, String>> recordedHeaders = new ArrayList<>();
    private final List<String> recordedBodies = new ArrayList<>();
    private HttpServer server;
    private WebToolConfig config;
    private DeepSeekWebClient client;

    @BeforeEach
    void start() throws IOException {
        server = WebToolTestSupport.loopbackServer();
        server.createContext("/v1/tools/search", this::handleSearch);
        server.createContext("/v1/tools/fetch", this::handleFetch);
        server.start();

        config = WebToolTestSupport.deepseekConfig(server, "/v1/tools/search", "/v1/tools/fetch");
        client = new DeepSeekWebClient(config);
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void searchSuccessSendsBearerHeaderAndParsesSummaryAndSnippet() throws Exception {
        var results = client.search("haifa agent", 5, Optional.empty(), API_KEY);
        assertThat(results).hasSize(2);

        assertThat(results.get(0).title()).isEqualTo("Result 1");
        assertThat(results.get(0).url()).isEqualTo("https://example.com/1");
        assertThat(results.get(0).content()).isEqualTo("Detailed summary for LLM");

        assertThat(results.get(1).title()).isEqualTo("Result 2");
        assertThat(results.get(1).content()).isEqualTo("Snippet fallback text");

        assertThat(recordedHeaders.get(0).get("authorization")).isEqualTo("Bearer " + API_KEY);
        assertThat(recordedHeaders.get(0).get("content-type")).isEqualTo("application/json");

        JsonNode sentBody = JSON.readTree(recordedBodies.get(0));
        assertThat(sentBody.path("query").asText()).isEqualTo("haifa agent");
        assertThat(sentBody.path("count").asInt()).isEqualTo(5);
        assertThat(sentBody.path("need_content").asBoolean()).isFalse();
    }

    @Test
    void searchNon200ThrowsDeepSeekWebException() {
        assertThatThrownBy(() -> client.search("error-402", 5, Optional.empty(), API_KEY))
                .isInstanceOf(DeepSeekWebClient.DeepSeekWebException.class)
                .satisfies(ex -> {
                    var failure = (DeepSeekWebClient.DeepSeekWebException) ex;
                    assertThat(failure.statusCode()).isEqualTo(402);
                    assertThat(failure.getMessage()).contains("402");
                });
    }

    @Test
    void searchEmptyResultsReturnsEmptyList() throws Exception {
        var results = client.search("empty", 5, Optional.empty(), API_KEY);
        assertThat(results).isEmpty();
    }

    @Test
    void fetchSuccessSendsBearerHeaderAndExtractsMarkdown() throws Exception {
        String content = client.fetch("https://example.com/page", API_KEY);
        assertThat(content).isEqualTo("# Page Title\n\nFetched markdown content body.");

        assertThat(recordedHeaders.get(0).get("authorization")).isEqualTo("Bearer " + API_KEY);
        JsonNode sentBody = JSON.readTree(recordedBodies.get(0));
        assertThat(sentBody.path("url").asText()).isEqualTo("https://example.com/page");
    }

    @Test
    void fetchNon200ThrowsDeepSeekWebException() {
        assertThatThrownBy(() -> client.fetch("https://example.com/fail-422", API_KEY))
                .isInstanceOf(DeepSeekWebClient.DeepSeekWebException.class)
                .satisfies(ex -> {
                    var failure = (DeepSeekWebClient.DeepSeekWebException) ex;
                    assertThat(failure.statusCode()).isEqualTo(422);
                    assertThat(failure.getMessage()).contains("422");
                });
    }

    private void handleSearch(HttpExchange exchange) throws IOException {
        recordRequest(exchange);
        String body = WebToolTestSupport.bodyText(exchange);
        recordedBodies.add(body);

        if (body.contains("error-402")) {
            byte[] resp = "{\"error\":{\"message\":\"Insufficient Balance\",\"type\":\"insufficient_balance\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(402, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
            return;
        }

        if (body.contains("empty")) {
            byte[] resp = "{\"results\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
            return;
        }

        String json = """
                {
                  "results": [
                    {
                      "title": "Result 1",
                      "url": "https://example.com/1",
                      "snippet": "Short snippet 1",
                      "summary": "Detailed summary for LLM",
                      "score": 2.8
                    },
                    {
                      "title": "Result 2",
                      "url": "https://example.com/2",
                      "snippet": "Snippet fallback text",
                      "summary": "",
                      "score": 2.1
                    }
                  ]
                }
                """;
        byte[] resp = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, resp.length);
        exchange.getResponseBody().write(resp);
        exchange.close();
    }

    private void handleFetch(HttpExchange exchange) throws IOException {
        recordRequest(exchange);
        String body = WebToolTestSupport.bodyText(exchange);
        recordedBodies.add(body);

        if (body.contains("fail-422")) {
            byte[] resp = "{\"error\":{\"message\":\"Page cannot be fetched\",\"type\":\"fetch_error\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(422, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
            return;
        }

        String json = """
                {
                  "title": "Page Title",
                  "content": "# Page Title\\n\\nFetched markdown content body."
                }
                """;
        byte[] resp = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, resp.length);
        exchange.getResponseBody().write(resp);
        exchange.close();
    }

    private void recordRequest(HttpExchange exchange) {
        var map = new java.util.HashMap<String, String>();
        for (var entry : exchange.getRequestHeaders().entrySet()) {
            if (!entry.getValue().isEmpty()) {
                map.put(entry.getKey().toLowerCase(java.util.Locale.ROOT), entry.getValue().get(0));
            }
        }
        recordedHeaders.add(map);
    }
}

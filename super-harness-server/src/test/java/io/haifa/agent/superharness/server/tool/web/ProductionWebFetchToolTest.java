package io.haifa.agent.superharness.server.tool.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.sdk.tool.JavaRecordSchemaGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Frozen {@code web_fetch} contract over a JDK {@code HttpServer} fixture that replays the Jina
 * Reader HTTP contract. The fixture also records the headers so the {@code X-Return-Format} and the
 * optional credential can be asserted without any real network access.
 */
class ProductionWebFetchToolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String API_KEY = "jina-secret-token-123";
    private final List<Map<String, String>> requests = java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer server;
    private ProductionWebFetchTool tool;

    @BeforeEach
    void start() throws IOException {
        server = WebToolTestSupport.loopbackServer();
        server.createContext("/reader", this::handle);
        server.start();
        tool = new ProductionWebFetchTool(WebToolTestSupport.config(server, "/reader"));
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void successReturnsJinaMarkdownTruncatedTo4096() {
        var output = fetch("https://example.com/long", Map.of());
        assertThat(output.content()).hasSize(ProductionWebFetchTool.MAX_CONTENT_CHARACTERS);
        assertThat(output.error()).isEmpty();
        assertThat(tool.summarize(output)).hasSize(4096);
    }

    @Test
    void successForwardsTheMarkdownAndTimeoutHeaders() {
        fetch("https://example.com/ok", Map.of());
        Map<String, String> headers = requests.get(0);
        assertThat(headers.get("x-return-format")).isEqualTo("markdown");
        assertThat(headers.get("x-timeout")).isNotBlank();
    }

    @Test
    void non200ReturnsTheFrozenErrorText() {
        var output = fetch("https://example.com/fail", Map.of());
        assertThat(output.content()).isEmpty();
        assertThat(output.error()).startsWith("Error: Jina API returned status 500: boom");
    }

    @Test
    void emptyBodyReturnsTheFrozenErrorText() {
        var output = fetch("https://example.com/empty", Map.of());
        assertThat(output.error()).isEqualTo("Error: Jina API returned empty response");
    }

    @Test
    void timeoutReturnsTheFrozenErrorText() {
        var output = fetch("https://example.com/slow", Map.of());
        assertThat(output.error()).startsWith("Error: Request to Jina API failed:");
    }

    @Test
    void nonHttpUrlsAreRejectedWithoutAnyRequest() {
        int before = calls.get();
        for (String invalid : List.of("example.com", "ftp://example.com", "file:///etc/passwd", "/relative")) {
            var output = new ProductionWebFetchTool(WebToolTestSupport.config(server, "/reader"))
                    .invoke(new ProductionWebFetchTool.Input(invalid), WebToolTestSupport.context());
            assertThat(tool.summarize(output)).as(invalid).isEqualTo(ProductionWebFetchTool.INVALID_URL_ERROR);
        }
        assertThat(calls.get()).isEqualTo(before);
    }

    @Test
    void apiKeyIsSentButNeverAppearsInTheResult() {
        var output = new ProductionWebFetchTool(WebToolTestSupport.config(server, "/reader"))
                .invoke(new ProductionWebFetchTool.Input("https://example.com/ok"),
                        WebToolTestSupport.context(Map.of(ProductionWebFetchTool.API_KEY_CREDENTIAL, API_KEY)));
        assertThat(requests.get(0).get("authorization")).isEqualTo("Bearer " + API_KEY);
        assertThat(tool.summarize(output)).doesNotContain(API_KEY);
        assertThat(output.toString()).doesNotContain(API_KEY);
    }

    @Test
    void schemaAndDescriptionMatchTheFrozenContract() throws Exception {
        Map<String, Object> document = new JavaRecordSchemaGenerator()
                .generate("web_fetch", "1.0.0", ProductionWebFetchTool.Input.class)
                .document();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) document.get("properties");
        assertThat(properties.keySet()).containsExactly("url");
        assertThat(document.get("required")).isEqualTo(List.of("url"));
        String resource;
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("upstream/deerflow/tool-descriptions/web_fetch.txt")) {
            resource = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(tool.spec().description()).isEqualTo(resource);
        assertThat(tool.spec().sideEffects())
                .as("published SDK declares the actual logical network side effect")
                .containsExactly(io.haifa.agent.tool.api.ToolSideEffect.NETWORK_ACCESS);
        assertThat(tool.spec().networkHosts()).containsExactly("127.0.0.1");
        assertThat(tool.spec().pure()).isFalse();
    }

    private ProductionWebFetchTool.Output fetch(String url, Map<String, String> credentials) {
        return tool.invoke(new ProductionWebFetchTool.Input(url), WebToolTestSupport.context(credentials));
    }

    private void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        JsonNode body = JSON.readTree(WebToolTestSupport.bodyText(exchange));
        String url = body.path("url").asText();
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) ->
                headers.put(name.toLowerCase(java.util.Locale.ROOT), values.isEmpty() ? "" : values.get(0)));
        requests.add(headers);
        int status = 200;
        String response;
        if (url.endsWith("/long")) {
            response = "a".repeat(5_000);
        } else if (url.endsWith("/empty")) {
            response = "   ";
        } else if (url.endsWith("/fail")) {
            status = 500;
            response = "boom";
        } else if (url.endsWith("/slow")) {
            try {
                Thread.sleep(1_500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            response = "late";
        } else {
            response = "# Example\n\nbody";
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

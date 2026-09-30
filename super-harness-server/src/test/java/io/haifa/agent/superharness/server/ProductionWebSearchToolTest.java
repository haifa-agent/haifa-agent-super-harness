package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.sdk.tool.JavaRecordSchemaGenerator;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Frozen {@code web_search} contract over a JDK {@code HttpServer} fixture that replays a recorded
 * DuckDuckGo HTML page shape (frozen {@code ddgs.engines.duckduckgo}). No test touches the network:
 * every endpoint resolves to loopback.
 */
class ProductionWebSearchToolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final List<Map<String, String>> requests = java.util.Collections.synchronizedList(new ArrayList<>());
    private HttpServer server;
    private ProductionWebSearchTool tool;

    @BeforeEach
    void start() throws IOException {
        server = WebToolTestSupport.loopbackServer();
        server.createContext("/html/", this::handle);
        server.start();
        tool = new ProductionWebSearchTool(WebToolTestSupport.config(server, "/reader"));
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void parsesTitlesUrlsAndSnippetsAndFiltersAdvertisements() throws Exception {
        JsonNode output = run("haifa", Optional.empty(), Optional.empty());
        assertThat(output.path("query").asText()).isEqualTo("haifa");
        assertThat(output.path("total_results").asInt()).isEqualTo(2);
        JsonNode first = output.path("results").get(0);
        assertThat(first.path("title").asText()).isEqualTo("Alpha Title");
        assertThat(first.path("url").asText()).isEqualTo("https://example.com/alpha");
        assertThat(first.path("content").asText()).isEqualTo("Alpha snippet text");
        assertThat(output.path("results").get(1).path("title").asText()).isEqualTo("Beta");
        assertThat(output.toString()).doesNotContain("y.js", "Ad");
    }

    @Test
    void timeRangeWeekIsSentAsTheDfParameter() throws Exception {
        run("haifa", Optional.empty(), Optional.of(ProductionWebSearchTool.TimeRange.week));
        assertThat(requests.get(0).get("df")).isEqualTo("w");
        requests.clear();
        run("haifa", Optional.empty(), Optional.of(ProductionWebSearchTool.TimeRange.day));
        assertThat(requests.get(0).get("df")).isEqualTo("d");
    }

    @Test
    void maxResultsTruncatesTheParsedItems() throws Exception {
        JsonNode output = run("haifa", Optional.of(1), Optional.empty());
        assertThat(output.path("total_results").asInt()).isEqualTo(1);
        assertThat(output.path("results").get(0).path("title").asText()).isEqualTo("Alpha Title");
    }

    @Test
    void noResultsReturnsTheFrozenErrorJson() throws Exception {
        JsonNode output = run("empty", Optional.empty(), Optional.empty());
        assertThat(output.path("error").asText()).isEqualTo("No results found");
        assertThat(output.path("query").asText()).isEqualTo("empty");
    }

    @Test
    void rateLimitReturnsTheFrozenErrorJson() throws Exception {
        JsonNode output = run("blocked", Optional.empty(), Optional.empty());
        assertThat(output.path("error").asText()).isEqualTo("No results found");
    }

    @Test
    void schemaMatchesTheFrozenParameterContract() {
        Map<String, Object> document = new JavaRecordSchemaGenerator()
                .generate("web_search", "1.0.0", ProductionWebSearchTool.Input.class)
                .document();
        assertThat(document.keySet()).contains("properties", "required");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) document.get("properties");
        assertThat(properties.keySet()).containsExactlyInAnyOrder("query", "max_results", "time_range");
        assertThat(document.get("required")).isEqualTo(List.of("query"));
    }

    @Test
    void descriptionMatchesTheImportedFrozenDocstring() throws Exception {
        String resource;
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("upstream/deerflow/tool-descriptions/web_search.txt")) {
            resource = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(tool.spec().description()).isEqualTo(resource);
        assertThat(tool.spec().name().value()).isEqualTo("web_search");
        assertThat(tool.spec().sideEffects())
                .as("SDK forbids JavaTool NETWORK_ACCESS; see the W11 report")
                .isEmpty();
    }

    private JsonNode run(String query, Optional<Integer> maxResults, Optional<ProductionWebSearchTool.TimeRange> range)
            throws Exception {
        var output = tool.invoke(
                new ProductionWebSearchTool.Input(query, maxResults, range), WebToolTestSupport.context());
        return JSON.readTree(tool.summarize(output));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String form = WebToolTestSupport.bodyText(exchange);
        requests.add(parse(form));
        String query = requests.get(requests.size() - 1).getOrDefault("q", "");
        int status = 200;
        String html;
        switch (query) {
            case "haifa" -> html = """
                    <html><body>
                    <div class="result__body">
                      <h2 class="result__title"><a rel="nofollow" href="https://example.com/alpha">Alpha <b>Title</b></a></h2>
                      <a class="result__snippet" href="https://example.com/alpha">Alpha snippet text</a>
                    </div>
                    <div class="result__body">
                      <h2 class="result__title"><a href="https://duckduckgo.com/y.js?ad=1">Ad</a></h2>
                      <a class="result__snippet" href="https://duckduckgo.com/y.js?ad=1">Ad snippet</a>
                    </div>
                    <div class="result__body">
                      <h2 class="result__title"><a href="https://example.com/beta">Beta</a></h2>
                      <a class="result__snippet" href="https://example.com/beta">Beta snippet</a>
                    </div>
                    </body></html>
                    """;
            case "empty" -> html = "<html><body><div class=\"no-results\">nothing</div></body></html>";
            case "blocked" -> {
                status = 403;
                html = "forbidden";
            }
            default -> html = "<html><body></body></html>";
        }
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Map<String, String> parse(String form) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : form.split("&")) {
            if (pair.isEmpty()) continue;
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? "" : pair.substring(separator + 1);
            fields.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return fields;
    }
}

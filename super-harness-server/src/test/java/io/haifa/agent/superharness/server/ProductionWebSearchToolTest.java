package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.sdk.tool.JavaRecordSchemaGenerator;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private final List<Headers> requestHeaders = java.util.Collections.synchronizedList(new ArrayList<>());
    private final List<String> requestMethods = java.util.Collections.synchronizedList(new ArrayList<>());
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
        assertThat(requests.get(0).get("kl")).isEqualTo("wt-wt");
        requests.clear();
        run("haifa", Optional.empty(), Optional.of(ProductionWebSearchTool.TimeRange.day));
        assertThat(requests.get(0).get("df")).isEqualTo("d");
    }

    @Test
    void textSearchUsesGetWithoutContentTypeAndSendsQueryParameters() throws Exception {
        run("haifa", Optional.empty(), Optional.of(ProductionWebSearchTool.TimeRange.week));

        assertThat(requestMethods.get(0)).isEqualTo("GET");
        assertThat(header(requestHeaders.get(0), "Content-Type")).isEmpty();
        Map<String, String> params = requests.get(0);
        assertThat(params.get("q")).isEqualTo("haifa");
        assertThat(params.get("kl")).isEqualTo("wt-wt");
        assertThat(params.get("df")).isEqualTo("w");
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
    void challengePageReturnsNoResultsAndLogsDiagnostic() throws Exception {
        List<String> logs = new CopyOnWriteArrayList<>();
        tool = new ProductionWebSearchTool(WebToolTestSupport.config(server, "/reader"), captor(logs));

        JsonNode output = run("challenge", Optional.empty(), Optional.empty());

        assertThat(output.path("error").asText()).isEqualTo("No results found");
        assertThat(output.path("query").asText()).isEqualTo("challenge");
        assertThat(logs).anySatisfy(record -> assertThat(record)
                .contains("engine=duckduckgo")
                .contains("stage=text")
                .contains("status=202")
                .contains("query=challenge"));
    }

    @Test
    void requestsUseABrowserUserAgentWithoutProductIdentifier() throws Exception {
        run("haifa", Optional.empty(), Optional.empty());
        Headers headers = requestHeaders.get(0);
        assertThat(header(headers, "User-Agent"))
                .startsWith("Mozilla/5.0")
                .doesNotContain("HaifaSuperHarness");
        assertThat(header(headers, "Accept")).contains("text/html");
        assertThat(header(headers, "Accept-Language")).contains("en-US");
        assertThat(header(headers, "Content-Type")).isEmpty();
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
        requestMethods.add(exchange.getRequestMethod());
        requestHeaders.add(exchange.getRequestHeaders());
        requests.add(parse(exchange.getRequestURI().getRawQuery()));
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
            // Shape of the real 202 anomaly/bot challenge recorded by the controller in
            // local-tmp/slot-1/controller-gates/ddg-probe/sh-ua.html; reduced to the marker text.
            case "challenge" -> {
                status = 202;
                html = "<html><head><title>DuckDuckGo</title></head>"
                        + "<body><div id=\"anomaly-modal\">Unfortunately, bots use DuckDuckGo too.</div></body></html>";
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
        if (form == null) return fields;
        for (String pair : form.split("&")) {
            if (pair.isEmpty()) continue;
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? "" : pair.substring(separator + 1);
            fields.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return fields;
    }

    private static String header(Headers headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return "";
    }

    private static System.Logger captor(List<String> records) {
        return new System.Logger() {
            @Override
            public String getName() {
                return "web-search-test-captor";
            }

            @Override
            public boolean isLoggable(System.Logger.Level level) {
                return true;
            }

            @Override
            public void log(System.Logger.Level level, ResourceBundle bundle, String msg, Throwable thrown) {
                records.add(msg);
            }

            @Override
            public void log(
                    System.Logger.Level level, ResourceBundle bundle, String format, Object... params) {
                records.add(MessageFormat.format(format, params));
            }
        };
    }
}

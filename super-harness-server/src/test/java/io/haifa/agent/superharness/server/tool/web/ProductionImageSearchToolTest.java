package io.haifa.agent.superharness.server.tool.web;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Frozen {@code image_search} contract over a JDK {@code HttpServer} fixture. The fixture proves the
 * two-step DuckDuckGo Images flow: the {@code vqd} handshake against the home page, then the
 * {@code /i.js} JSON request carrying the {@code f} filter string, exactly like frozen
 * {@code ddgs.engines.duckduckgo_images}. No test touches the network.
 */
class ProductionImageSearchToolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VQD = "vqd-123";
    private final AtomicInteger homeCalls = new AtomicInteger();
    private final List<Map<String, String>> imageRequests =
            java.util.Collections.synchronizedList(new ArrayList<>());
    private final List<Headers> requestHeaders = java.util.Collections.synchronizedList(new ArrayList<>());
    private HttpServer server;
    private ProductionImageSearchTool tool;
    private volatile boolean returnResults = true;
    private volatile boolean includeVqd = true;

    @BeforeEach
    void start() throws IOException {
        server = WebToolTestSupport.loopbackServer();
        server.createContext("/i.js", this::handleImages);
        server.createContext("/", this::handleHome);
        server.start();
        tool = new ProductionImageSearchTool(WebToolTestSupport.config(server, "/reader"));
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void performsVqdHandshakeThenFilteredImageRequest() throws Exception {
        JsonNode output = run("cats", Optional.empty(), Optional.of("Large"), Optional.of("photo"),
                Optional.of("Square"));
        assertThat(homeCalls.get()).isEqualTo(1);
        assertThat(imageRequests).hasSize(1);
        Map<String, String> params = imageRequests.get(0);
        assertThat(params.get("o")).isEqualTo("json");
        assertThat(params.get("q")).isEqualTo("cats");
        assertThat(params.get("l")).isEqualTo("wt-wt");
        assertThat(params.get("vqd")).isEqualTo(VQD);
        assertThat(params.get("p")).isEqualTo("1");
        assertThat(params.get("ct")).isEqualTo("AT");
        assertThat(params.get("f")).isEqualTo(",size:Large,,type:photo,layout:Square,");

        assertThat(output.path("total_results").asInt()).isEqualTo(2);
        JsonNode first = output.path("results").get(0);
        assertThat(first.path("title").asText()).isEqualTo("Cat");
        assertThat(first.path("image_url").asText()).isEqualTo("https://img.example/1.jpg");
        assertThat(first.path("thumbnail_url").asText()).isEqualTo("https://thumb.example/1.jpg");
        assertThat(output.path("usage_hint").asText()).isEqualTo(ProductionImageSearchTool.USAGE_HINT);
    }

    @Test
    void vqdAndImageRequestsUseABrowserUserAgentWithoutProductIdentifier() throws Exception {
        run("cats", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(requestHeaders).hasSize(2);
        for (Headers headers : requestHeaders) {
            assertThat(header(headers, "User-Agent"))
                    .startsWith("Mozilla/5.0")
                    .doesNotContain("HaifaSuperHarness");
            assertThat(header(headers, "Accept-Language")).contains("en-US");
        }
    }

    @Test
    void noResultsReturnsTheFrozenErrorJson() throws Exception {
        returnResults = false;
        JsonNode output = run("cats", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(output.path("error").asText()).isEqualTo("No images found");
        assertThat(output.path("query").asText()).isEqualTo("cats");
    }

    @Test
    void missingVqdReturnsTheFrozenErrorJson() throws Exception {
        includeVqd = false;
        JsonNode output = run("cats", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(output.path("error").asText()).isEqualTo("No images found");
    }

    @Test
    void schemaAndDescriptionMatchTheFrozenContract() throws Exception {
        Map<String, Object> document = new JavaRecordSchemaGenerator()
                .generate("image_search", "1.0.0", ProductionImageSearchTool.Input.class)
                .document();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) document.get("properties");
        assertThat(properties.keySet()).containsExactlyInAnyOrder(
                "query", "max_results", "size", "type_image", "layout");
        assertThat(document.get("required")).isEqualTo(List.of("query"));
        String resource;
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("upstream/deerflow/tool-descriptions/image_search.txt")) {
            resource = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(tool.spec().description()).isEqualTo(resource);
        assertThat(tool.spec().sideEffects())
                .as("published SDK declares the actual logical network side effect")
                .containsExactly(io.haifa.agent.tool.api.ToolSideEffect.NETWORK_ACCESS);
        assertThat(tool.spec().networkHosts()).containsExactly("127.0.0.1");
        assertThat(tool.spec().pure()).isFalse();
    }

    private JsonNode run(
            String query,
            Optional<Integer> maxResults,
            Optional<String> size,
            Optional<String> typeImage,
            Optional<String> layout) throws Exception {
        var output = tool.invoke(
                new ProductionImageSearchTool.Input(query, maxResults, size, typeImage, layout),
                WebToolTestSupport.context());
        return JSON.readTree(tool.summarize(output));
    }

    private void handleHome(HttpExchange exchange) throws IOException {
        homeCalls.incrementAndGet();
        requestHeaders.add(exchange.getRequestHeaders());
        String html = includeVqd
                ? "<html><body><script>var x = 'vqd=\"" + VQD + "\"';</script></body></html>"
                : "<html><body>no token here</body></html>";
        respond(exchange, 200, html);
    }

    private void handleImages(HttpExchange exchange) throws IOException {
        requestHeaders.add(exchange.getRequestHeaders());
        imageRequests.add(parse(exchange.getRequestURI().getRawQuery()));
        String body = returnResults
                ? "{\"results\":[{\"title\":\"Cat\",\"image\":\"https://img.example/1.jpg\","
                        + "\"thumbnail\":\"https://thumb.example/1.jpg\"},"
                        + "{\"title\":\"Kitten\",\"image\":\"https://img.example/2.jpg\","
                        + "\"thumbnail\":\"https://thumb.example/2.jpg\"}]}"
                : "{\"results\":[]}";
        respond(exchange, 200, body);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Map<String, String> parse(String query) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (query == null) return fields;
        for (String pair : query.split("&")) {
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
}

package io.haifa.agent.superharness.server.tool.web;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * HTTP client for DeepSeek standalone Search and Fetch API endpoints:
 * <ul>
 *   <li>{@code POST https://api.deepseek.com/v1/tools/search}</li>
 *   <li>{@code POST https://api.deepseek.com/v1/tools/fetch}</li>
 * </ul>
 *
 * <p>Authentication is handled via Bearer tokens ({@code Authorization: Bearer <key>}) using
 * {@code DEEPSEEK_TOOL_API_KEY} dynamically supplied from the SDK credential map.
 */
public class DeepSeekWebClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final WebToolConfig config;
    private final HttpClient http;
    private final Clock clock;

    public record SearchResultItem(String title, String url, String content) {
        public SearchResultItem {
            title = title == null ? "" : title;
            url = url == null ? "" : url;
            content = content == null ? "" : content;
        }
    }

    public static class DeepSeekWebException extends Exception {
        private final int statusCode;

        public DeepSeekWebException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }

    public DeepSeekWebClient(WebToolConfig config) {
        this(config, WebHttpClients.shared(config), Clock.systemUTC());
    }

    public DeepSeekWebClient(WebToolConfig config, HttpClient http) {
        this(config, http, Clock.systemUTC());
    }

    public DeepSeekWebClient(WebToolConfig config, HttpClient http, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.http = Objects.requireNonNull(http, "http");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Translates a relative time range (day, week, month, year) into DeepSeek's {@code after:YYYY-MM-DD}
     * query filter operator if not already present.
     */
    static String appendTimeRangeFilter(String query, String range, LocalDate today) {
        if (range == null || range.isBlank() || query.contains("after:")) {
            return query;
        }
        LocalDate afterDate = switch (range.toLowerCase(Locale.ROOT)) {
            case "d", "day" -> today.minusDays(1);
            case "w", "week" -> today.minusWeeks(1);
            case "m", "month" -> today.minusMonths(1);
            case "y", "year" -> today.minusYears(1);
            default -> null;
        };
        if (afterDate != null) {
            return query + " after:" + afterDate;
        }
        return query;
    }

    /**
     * Executes web search through DeepSeek Search API.
     *
     * @param query search query
     * @param count number of results to return (1..50)
     * @param timeRange optional time range filter
     * @param apiKey DeepSeek API key
     * @return structured results list
     */
    public List<SearchResultItem> search(String query, int count, Optional<String> timeRange, String apiKey)
            throws IOException, InterruptedException, DeepSeekWebException {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(apiKey, "apiKey");

        String effectiveQuery = timeRange
                .map(r -> appendTimeRangeFilter(query, r, LocalDate.now(clock)))
                .orElse(query);

        ObjectNode body = JSON.createObjectNode();
        body.put("query", effectiveQuery);
        body.put("count", Math.max(1, Math.min(count, 50)));
        body.put("need_content", false);

        String payload;
        try {
            payload = JSON.writeValueAsString(body);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Failed to serialize DeepSeek search request", impossible);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(config.deepseekSearchUrl()))
                .timeout(config.timeout())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload, UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
        int statusCode = response.statusCode();
        if (statusCode != 200) {
            throw new DeepSeekWebException(
                    statusCode, errorMessage("DeepSeek Search API", statusCode, response.body(), apiKey));
        }

        String responseBody = response.body();
        if (responseBody == null || responseBody.isBlank()) {
            return List.of();
        }

        JsonNode root = JSON.readTree(responseBody);
        JsonNode resultsNode = root.path("results");
        List<SearchResultItem> items = new ArrayList<>();
        if (resultsNode.isArray()) {
            for (JsonNode item : resultsNode) {
                String title = item.path("title").asText("");
                String url = item.path("url").asText("");
                // Prefer 'summary' optimized for LLMs (600-800 chars), fall back to 'snippet' (100-200 chars)
                String summary = item.path("summary").asText("");
                String snippet = item.path("snippet").asText("");
                String content = !summary.isBlank() ? summary : snippet;
                items.add(new SearchResultItem(title, url, content));
            }
        }
        return items;
    }

    /**
     * Fetches a web page content in Markdown format through DeepSeek Fetch API.
     *
     * @param targetUrl target web page URL
     * @param apiKey DeepSeek API key
     * @return markdown content
     */
    public String fetch(String targetUrl, String apiKey)
            throws IOException, InterruptedException, DeepSeekWebException {
        Objects.requireNonNull(targetUrl, "targetUrl");
        Objects.requireNonNull(apiKey, "apiKey");

        ObjectNode body = JSON.createObjectNode();
        body.put("url", targetUrl);

        String payload;
        try {
            payload = JSON.writeValueAsString(body);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Failed to serialize DeepSeek fetch request", impossible);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(config.deepseekFetchUrl()))
                .timeout(config.timeout())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload, UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
        int statusCode = response.statusCode();
        if (statusCode != 200) {
            throw new DeepSeekWebException(
                    statusCode, errorMessage("DeepSeek Fetch API", statusCode, response.body(), apiKey));
        }

        String responseBody = response.body();
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }

        JsonNode root = JSON.readTree(responseBody);
        return root.path("content").asText("");
    }

    /** Error text kept diagnostic without echoing the credential a provider may reflect back. */
    private static String errorMessage(String api, int statusCode, String body, String apiKey) {
        String redacted = ToolSecretRedactor.redact(body, apiKey);
        return api + " returned HTTP " + statusCode + (redacted.isEmpty() ? "" : ": " + redacted);
    }
}

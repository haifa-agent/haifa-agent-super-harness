package io.haifa.agent.superharness.server.tool.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.superharness.server.tool.ToolDescriptions;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Frozen DeerFlow default {@code web_search} tool backed by DuckDuckGo (frozen
 * {@code community/ddg_search/tools.py:web_search_tool}) or DeepSeek standalone Search API.
 *
 * <p>The model-facing contract is identical regardless of the underlying provider: parameters
 * {@code query}, {@code max_results=5} and {@code time_range∈{day,week,month,year}}, and the result
 * JSON carries {@code query}, {@code total_results} and {@code results[]} with {@code title}/{@code url}/
 * {@code content}. Any transport, rate-limit, missing key, or parse failure is reported as the frozen
 * error JSON payload rather than throwing an unhandled exception.
 */
public final class ProductionWebSearchTool
        implements JavaTool<ProductionWebSearchTool.Input, ProductionWebSearchTool.Output> {
    static final String SDK_LOCAL_PRINCIPAL = "default-public-user";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JavaToolSpec<Input, Output> spec;

    private final WebToolConfig config;
    private final DuckDuckGoClient duckDuckGoClient;
    private final DeepSeekWebClient deepSeekClient;

    public ProductionWebSearchTool(WebToolConfig config) {
        this(config, DuckDuckGoClient.defaultLog());
    }

    public ProductionWebSearchTool(WebToolConfig config, System.Logger webLog) {
        this(config, new DuckDuckGoClient(config, webLog), new DeepSeekWebClient(config));
    }

    public ProductionWebSearchTool(WebToolConfig config, DuckDuckGoClient client) {
        this(config, client, new DeepSeekWebClient(config));
    }

    public ProductionWebSearchTool(WebToolConfig config, DeepSeekWebClient deepSeekClient) {
        this(config, new DuckDuckGoClient(config, DuckDuckGoClient.defaultLog()), deepSeekClient);
    }

    public ProductionWebSearchTool(
            WebToolConfig config,
            DuckDuckGoClient duckDuckGoClient,
            DeepSeekWebClient deepSeekClient) {
        this.config = Objects.requireNonNull(config, "config");
        this.duckDuckGoClient = Objects.requireNonNull(duckDuckGoClient, "duckDuckGoClient");
        this.deepSeekClient = Objects.requireNonNull(deepSeekClient, "deepSeekClient");
        String networkHost = config.searchProvider() == WebToolConfig.WebSearchProvider.DEEPSEEK
                ? WebToolConfig.endpointHost(config.deepseekSearchUrl())
                : WebToolConfig.endpointHost(config.duckduckgoHtmlUrl());
        this.spec = JavaToolSpec.builder("web_search", Input.class, Output.class)
                .title("Search the web")
                .description(ToolDescriptions.load("web_search"))
                .networkAccess(networkHost)
                .build();
    }

    @Override
    public JavaToolSpec<Input, Output> spec() {
        return spec;
    }

    @Override
    public Output invoke(Input input, JavaToolContext context) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(context, "context");
        if (!SDK_LOCAL_PRINCIPAL.equals(context.principal().principalId())
                || !"user".equals(context.principal().principalType())) {
            throw new IllegalArgumentException("web_search requires the local Thread owner");
        }
        context.cancellation().throwIfCancellationRequested();
        if (!Instant.now().isBefore(context.deadline())) {
            throw new IllegalStateException("web_search deadline has expired");
        }

        int maxResults = input.max_results().orElse(config.maxResults());
        Optional<String> timeRange = input.time_range().map(TimeRange::code);

        if (config.searchProvider() == WebToolConfig.WebSearchProvider.DEEPSEEK) {
            String apiKey = context.credentials().get(WebToolConfig.DEEPSEEK_TOOL_API_KEY_CREDENTIAL);
            if (apiKey == null || apiKey.isBlank()) {
                return new Output(errorPayload("DEEPSEEK_TOOL_API_KEY is not configured", input.query()));
            }
            List<DeepSeekWebClient.SearchResultItem> deepSeekResults;
            try {
                deepSeekResults = deepSeekClient.search(input.query(), maxResults, timeRange, apiKey);
            } catch (DeepSeekWebClient.DeepSeekWebException failure) {
                return new Output(errorPayload("DeepSeek Search API error: HTTP " + failure.statusCode(), input.query()));
            } catch (Exception failure) {
                return new Output(errorPayload("DeepSeek Search request failed: " + failure.getMessage(), input.query()));
            }
            if (deepSeekResults.isEmpty()) {
                return new Output(noResults(input.query()));
            }
            return new Output(deepSeekResults(input.query(), deepSeekResults));
        }

        List<DuckDuckGoClient.TextResult> results;
        try {
            results = duckDuckGoClient.search(input.query(), maxResults, timeRange);
        } catch (DuckDuckGoClient.WebToolException failure) {
            return new Output(noResults(input.query()));
        }
        if (results.isEmpty()) {
            return new Output(noResults(input.query()));
        }
        return new Output(results(input.query(), results));
    }

    @Override
    public String summarize(Output output) {
        return output.result();
    }

    private static String noResults(String query) {
        return errorPayload("No results found", query);
    }

    private static String errorPayload(String message, String query) {
        ObjectNode node = JSON.createObjectNode();
        node.put("error", message);
        node.put("query", query);
        return write(node);
    }

    private static String results(String query, List<DuckDuckGoClient.TextResult> results) {
        ObjectNode root = JSON.createObjectNode();
        root.put("query", query);
        root.put("total_results", results.size());
        ArrayNode items = root.putArray("results");
        for (DuckDuckGoClient.TextResult result : results) {
            ObjectNode item = items.addObject();
            item.put("title", result.title());
            item.put("url", result.url());
            item.put("content", result.content());
        }
        return write(root);
    }

    private static String deepSeekResults(String query, List<DeepSeekWebClient.SearchResultItem> results) {
        ObjectNode root = JSON.createObjectNode();
        root.put("query", query);
        root.put("total_results", results.size());
        ArrayNode items = root.putArray("results");
        for (DeepSeekWebClient.SearchResultItem result : results) {
            ObjectNode item = items.addObject();
            item.put("title", result.title());
            item.put("url", result.url());
            item.put("content", result.content());
        }
        return write(root);
    }

    private static String write(ObjectNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("failed to serialize web_search result", impossible);
        }
    }

    /** Frozen {@code SearchTimeRange} literal values mapped to provider parameters. */
    public enum TimeRange {
        day("d"),
        week("w"),
        month("m"),
        year("y");

        private final String code;

        TimeRange(String code) {
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    public record Input(String query, Optional<Integer> max_results, Optional<TimeRange> time_range) {
        public Input {
            Objects.requireNonNull(query, "query");
            max_results = max_results == null ? Optional.empty() : max_results;
            time_range = time_range == null ? Optional.empty() : time_range;
        }
    }

    public record Output(String result) {
        public Output {
            Objects.requireNonNull(result, "result");
        }
    }
}

package io.haifa.agent.superharness.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Frozen DeerFlow default {@code web_search} (frozen
 * {@code community/ddg_search/tools.py:web_search_tool}) backed by DuckDuckGo.
 *
 * <p>The frozen provider aggregates several ddgs backends ({@code backend=auto}, including Wikipedia
 * region inference); SH deliberately implements only the {@code duckduckgo} engine, a registered
 * known difference. The model-facing contract is identical: parameters {@code query},
 * {@code max_results=5} and {@code time_range∈{day,week,month,year}}, and the result JSON carries
 * {@code query}, {@code total_results} and {@code results[]} with {@code title}/{@code url}/
 * {@code content}. Any transport, rate-limit or parse failure is reported as the frozen
 * {@code {"error":"No results found",...}} payload rather than an exception.
 */
public final class ProductionWebSearchTool
        implements JavaTool<ProductionWebSearchTool.Input, ProductionWebSearchTool.Output> {
    static final String SDK_LOCAL_PRINCIPAL = "default-public-user";
    private static final ObjectMapper JSON = new ObjectMapper();
    // The SDK JavaTool builder rejects ToolSideEffect.NETWORK_ACCESS ("register through the Tool API
    // with constrained hosts"), so this public JavaTool cannot declare its real network side effect;
    // see the W11 report for the open SDK gap. It therefore has no side-effect rule and no approval
    // prompt, matching the human decision (2026-09-30) that the default web tools do not need one.
    private static final JavaToolSpec<Input, Output> SPEC =
            JavaToolSpec.builder("web_search", Input.class, Output.class)
                    .title("Search the web")
                    .description(ToolDescriptions.load("web_search"))
                    .build();

    private final WebToolConfig config;
    private final DuckDuckGoClient client;

    ProductionWebSearchTool(WebToolConfig config) {
        this(config, DuckDuckGoClient.defaultLog());
    }

    ProductionWebSearchTool(WebToolConfig config, System.Logger webLog) {
        this.config = Objects.requireNonNull(config, "config");
        this.client = new DuckDuckGoClient(config, webLog);
    }

    @Override
    public JavaToolSpec<Input, Output> spec() {
        return SPEC;
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
        List<DuckDuckGoClient.TextResult> results;
        try {
            results = client.search(input.query(), maxResults, timeRange);
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
        ObjectNode node = JSON.createObjectNode();
        node.put("error", "No results found");
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

    private static String write(ObjectNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("failed to serialize web_search result", impossible);
        }
    }

    /** Frozen {@code SearchTimeRange} literal values mapped to the ddgs {@code df} parameter. */
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

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
 * Frozen DeerFlow default {@code image_search} (frozen
 * {@code community/image_search/tools.py:image_search_tool}) backed by DuckDuckGo Images.
 *
 * <p>Contract matches the frozen tool: parameters {@code query}, {@code max_results=5},
 * {@code size}, {@code type_image} and {@code layout}; result JSON carries {@code title},
 * {@code image_url}, {@code thumbnail_url} and the frozen {@code usage_hint}. An empty or failed
 * search returns the frozen {@code {"error":"No images found",...}} payload.
 */
public final class ProductionImageSearchTool
        implements JavaTool<ProductionImageSearchTool.Input, ProductionImageSearchTool.Output> {
    static final String SDK_LOCAL_PRINCIPAL = "default-public-user";
    static final String USAGE_HINT =
            "Use the 'image_url' values as reference images in image generation. Download them first if needed.";
    private static final ObjectMapper JSON = new ObjectMapper();
    // See ProductionWebSearchTool: the SDK JavaTool builder rejects NETWORK_ACCESS, so this tool
    // cannot declare its real network side effect from the public JavaTool API.
    private static final JavaToolSpec<Input, Output> SPEC =
            JavaToolSpec.builder("image_search", Input.class, Output.class)
                    .title("Search for images")
                    .description(ToolDescriptions.load("image_search"))
                    .build();

    private final WebToolConfig config;
    private final DuckDuckGoClient client;

    ProductionImageSearchTool(WebToolConfig config) {
        this(config, new DuckDuckGoClient(config));
    }

    ProductionImageSearchTool(WebToolConfig config, DuckDuckGoClient client) {
        this.config = Objects.requireNonNull(config, "config");
        this.client = Objects.requireNonNull(client, "client");
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
            throw new IllegalArgumentException("image_search requires the local Thread owner");
        }
        context.cancellation().throwIfCancellationRequested();
        if (!Instant.now().isBefore(context.deadline())) {
            throw new IllegalStateException("image_search deadline has expired");
        }
        int maxResults = input.max_results().orElse(config.maxResults());
        List<DuckDuckGoClient.ImageResult> results;
        try {
            results = client.images(
                    input.query(), maxResults, input.size(), input.type_image(), input.layout());
        } catch (DuckDuckGoClient.WebToolException failure) {
            return new Output(noImages(input.query()));
        }
        if (results.isEmpty()) {
            return new Output(noImages(input.query()));
        }
        return new Output(results(input.query(), results));
    }

    @Override
    public String summarize(Output output) {
        return output.result();
    }

    private static String noImages(String query) {
        ObjectNode node = JSON.createObjectNode();
        node.put("error", "No images found");
        node.put("query", query);
        return write(node);
    }

    private static String results(String query, List<DuckDuckGoClient.ImageResult> results) {
        ObjectNode root = JSON.createObjectNode();
        root.put("query", query);
        root.put("total_results", results.size());
        ArrayNode items = root.putArray("results");
        for (DuckDuckGoClient.ImageResult result : results) {
            ObjectNode item = items.addObject();
            item.put("title", result.title());
            item.put("image_url", result.imageUrl());
            item.put("thumbnail_url", result.thumbnailUrl());
        }
        root.put("usage_hint", USAGE_HINT);
        return write(root);
    }

    private static String write(ObjectNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("failed to serialize image_search result", impossible);
        }
    }

    public record Input(
            String query,
            Optional<Integer> max_results,
            Optional<String> size,
            Optional<String> type_image,
            Optional<String> layout) {
        public Input {
            Objects.requireNonNull(query, "query");
            max_results = max_results == null ? Optional.empty() : max_results;
            size = size == null ? Optional.empty() : size;
            type_image = type_image == null ? Optional.empty() : type_image;
            layout = layout == null ? Optional.empty() : layout;
        }
    }

    public record Output(String result) {
        public Output {
            Objects.requireNonNull(result, "result");
        }
    }
}

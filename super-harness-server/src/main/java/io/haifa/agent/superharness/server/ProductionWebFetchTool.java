package io.haifa.agent.superharness.server;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Frozen DeerFlow default {@code web_fetch} (frozen {@code community/jina_ai/tools.py:web_fetch_tool})
 * backed by the Jina Reader.
 *
 * <p>The frozen tool requests HTML from Jina and then runs its own readability extraction, truncating
 * the Markdown to 4096 characters. SH asks Jina for Markdown directly and performs no readability
 * pass (registered known difference); the truncation to 4096 characters is preserved. Only absolute
 * {@code http}/{@code https} URLs are accepted. A non-200 response, an empty body, a transport
 * failure or an invalid URL produces the frozen-style {@code Error: ...} text, never a partial page.
 *
 * <p>The optional {@code JINA_API_KEY} is read only from the SDK credential map and is never written
 * to a result, event or log line.
 */
public final class ProductionWebFetchTool
        implements JavaTool<ProductionWebFetchTool.Input, ProductionWebFetchTool.Output> {
    static final String SDK_LOCAL_PRINCIPAL = "default-public-user";
    static final int MAX_CONTENT_CHARACTERS = 4096;
    static final String API_KEY_CREDENTIAL = "JINA_API_KEY";
    static final String INVALID_URL_ERROR = "Error: URL must be an absolute http(s) URL";
    private final JavaToolSpec<Input, Output> spec;

    private final WebToolConfig config;
    private final HttpClient http;

    ProductionWebFetchTool(WebToolConfig config) {
        this(config, WebHttpClients.create(config));
    }

    ProductionWebFetchTool(WebToolConfig config, HttpClient http) {
        this.config = Objects.requireNonNull(config, "config");
        this.http = Objects.requireNonNull(http, "http");
        this.spec = JavaToolSpec.builder("web_fetch", Input.class, Output.class)
                .title("Fetch a web page")
                .description(ToolDescriptions.load("web_fetch"))
                .networkAccess(WebToolConfig.endpointHost(config.jinaReaderUrl()))
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
            throw new IllegalArgumentException("web_fetch requires the local Thread owner");
        }
        context.cancellation().throwIfCancellationRequested();
        if (!Instant.now().isBefore(context.deadline())) {
            throw new IllegalStateException("web_fetch deadline has expired");
        }
        String url = input.url();
        if (!isAbsoluteHttpUrl(url)) {
            return Output.failure(url, INVALID_URL_ERROR);
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("url", url);
        String payload;
        try {
            payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            return Output.failure(url, "Error: Request to Jina API failed: JsonProcessingException");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(config.jinaReaderUrl()))
                .timeout(config.timeout())
                .header("Content-Type", "application/json")
                .header("X-Return-Format", "markdown")
                .header("X-Timeout", Long.toString(config.timeout().toSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(payload, UTF_8));
        String apiKey = context.credentials().get(API_KEY_CREDENTIAL);
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(UTF_8));
        } catch (IOException exception) {
            return Output.failure(url, "Error: Request to Jina API failed: "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Output.failure(url, "Error: Request to Jina API failed: interrupted");
        }
        if (response.statusCode() != 200) {
            return Output.failure(url, "Error: Jina API returned status " + response.statusCode()
                    + ": " + response.body());
        }
        String content = response.body();
        if (content == null || content.strip().isEmpty()) {
            return Output.failure(url, "Error: Jina API returned empty response");
        }
        return Output.success(url, truncate(content));
    }

    @Override
    public String summarize(Output output) {
        return output.error().isEmpty() ? output.content() : output.error();
    }

    private static boolean isAbsoluteHttpUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = new URI(url);
            if (!uri.isAbsolute()) return false;
            String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null;
        } catch (URISyntaxException invalid) {
            return false;
        }
    }

    /** Python {@code text[:4096]} slices code points; keep the boundary identical for supplementary characters. */
    static String truncate(String content) {
        if (content.length() <= MAX_CONTENT_CHARACTERS) return content;
        int codePoints = content.codePointCount(0, content.length());
        if (codePoints <= MAX_CONTENT_CHARACTERS) return content;
        int end = content.offsetByCodePoints(0, MAX_CONTENT_CHARACTERS);
        return content.substring(0, end);
    }

    public record Input(String url) {
        public Input {
            Objects.requireNonNull(url, "url");
        }
    }

    public record Output(String url, String content, String error) {
        static Output success(String url, String content) {
            return new Output(url, content, "");
        }

        static Output failure(String url, String error) {
            return new Output(url, "", error);
        }
    }
}

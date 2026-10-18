package io.haifa.agent.superharness.server.tool.web;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.haifa.agent.credential.api.CredentialRequirement;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.superharness.server.tool.ToolDescriptions;
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
 * Frozen DeerFlow default {@code web_fetch} tool backed by Jina Reader (frozen
 * {@code community/jina_ai/tools.py:web_fetch_tool}) or DeepSeek standalone Fetch API.
 *
 * <p>The model-facing contract is identical: only absolute {@code http}/{@code https} URLs are
 * accepted, results are formatted as clean Markdown truncated to 4096 characters, and any transport,
 * non-200, missing key, or parse failure is reported as the frozen-style {@code Error: ...} text.
 *
 * <p>API keys ({@code JINA_API_KEY} or {@code DEEPSEEK_TOOL_API_KEY}) are declared through
 * {@link JavaToolSpec.Builder#credentialRequirements}, so the SDK resolves them, injects them into
 * {@link JavaToolContext#credentials()} and registers them for scoped redaction; they are never
 * written to a result, event or log line.
 */
public final class ProductionWebFetchTool
        implements JavaTool<ProductionWebFetchTool.Input, ProductionWebFetchTool.Output> {
    static final String SDK_LOCAL_PRINCIPAL = "default-public-user";
    private static final System.Logger LOG =
            System.getLogger("io.haifa.agent.superharness.server.tool.web");
    static final int MAX_CONTENT_CHARACTERS = 4096;
    static final String JINA_API_KEY_CREDENTIAL = "JINA_API_KEY";
    static final String DEEPSEEK_TOOL_API_KEY_CREDENTIAL = WebToolConfig.DEEPSEEK_TOOL_API_KEY_CREDENTIAL;
    static final String INVALID_URL_ERROR = "Error: URL must be an absolute http(s) URL";
    private final JavaToolSpec<Input, Output> spec;

    private final WebToolConfig config;
    private final HttpClient http;
    private final DeepSeekWebClient deepSeekClient;

    public ProductionWebFetchTool(WebToolConfig config) {
        this(config, WebHttpClients.shared(config), new DeepSeekWebClient(config));
    }

    public ProductionWebFetchTool(WebToolConfig config, HttpClient http) {
        this(config, http, new DeepSeekWebClient(config, http));
    }

    public ProductionWebFetchTool(WebToolConfig config, DeepSeekWebClient deepSeekClient) {
        this(config, WebHttpClients.shared(config), deepSeekClient);
    }

    public ProductionWebFetchTool(
            WebToolConfig config,
            HttpClient http,
            DeepSeekWebClient deepSeekClient) {
        this.config = Objects.requireNonNull(config, "config");
        this.http = Objects.requireNonNull(http, "http");
        this.deepSeekClient = Objects.requireNonNull(deepSeekClient, "deepSeekClient");
        String networkHost = config.fetchProvider() == WebToolConfig.WebFetchProvider.DEEPSEEK
                ? WebToolConfig.endpointHost(config.deepseekFetchUrl())
                : WebToolConfig.endpointHost(config.jinaReaderUrl());
        JavaToolSpec.Builder<Input, Output> builder = JavaToolSpec.builder("web_fetch", Input.class, Output.class)
                .title("Fetch a web page")
                .description(ToolDescriptions.load("web_fetch"))
                .networkAccess(networkHost);
        if (config.fetchProvider() == WebToolConfig.WebFetchProvider.DEEPSEEK) {
            builder.credentialRequirements(new CredentialRequirement(DEEPSEEK_TOOL_API_KEY_CREDENTIAL));
        } else if (config.jinaApiKey().isPresent()) {
            builder.credentialRequirements(new CredentialRequirement(JINA_API_KEY_CREDENTIAL));
        }
        this.spec = builder.build();
    }

    @Override
    public JavaToolSpec<Input, Output> spec() {
        return spec;
    }

    @Override
    public Output invoke(Input input, JavaToolContext context) {
        try {
            return doInvoke(input, context);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            return unexpected(failure, input == null ? "" : input.url());
        }
    }

    private Output doInvoke(Input input, JavaToolContext context) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(context, "context");
        if (!SDK_LOCAL_PRINCIPAL.equals(context.principal().principalId())
                || !"user".equals(context.principal().principalType())) {
            return refused("principal", input.url(), context,
                    "web_fetch requires the local Thread owner");
        }
        context.cancellation().throwIfCancellationRequested();
        if (!Instant.now().isBefore(context.deadline())) {
            return refused("deadline", input.url(), context, "web_fetch deadline has expired");
        }
        String url = input.url();
        if (!isAbsoluteHttpUrl(url)) {
            return Output.failure(url, INVALID_URL_ERROR);
        }

        if (config.fetchProvider() == WebToolConfig.WebFetchProvider.DEEPSEEK) {
            String apiKey = context.credentials().get(DEEPSEEK_TOOL_API_KEY_CREDENTIAL);
            if (apiKey == null || apiKey.isBlank()) {
                apiKey = config.deepseekToolApiKey().orElse(null);
            }
            if (apiKey == null || apiKey.isBlank()) {
                return Output.failure(url, "Error: " + DEEPSEEK_TOOL_API_KEY_CREDENTIAL + " is not configured");
            }
            String content;
            try {
                content = deepSeekClient.fetch(url, apiKey);
            } catch (DeepSeekWebClient.DeepSeekWebException exception) {
                return Output.failure(url, ToolSecretRedactor.redact(
                        "Error: DeepSeek API returned status " + exception.statusCode()
                                + ": " + exception.getMessage(), apiKey));
            } catch (IOException exception) {
                return Output.failure(url, ToolSecretRedactor.redact(
                        "Error: Request to DeepSeek API failed: "
                                + exception.getClass().getSimpleName() + ": " + exception.getMessage(), apiKey));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Output.failure(url, "Error: Request to DeepSeek API failed: interrupted");
            }
            if (content == null || content.strip().isEmpty()) {
                return Output.failure(url, "Error: DeepSeek API returned empty response");
            }
            return Output.success(url, truncate(content));
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
        String apiKey = context.credentials().get(JINA_API_KEY_CREDENTIAL);
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = config.jinaApiKey().orElse(null);
        }
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(UTF_8));
        } catch (IOException exception) {
            return Output.failure(url, ToolSecretRedactor.redact(
                    "Error: Request to Jina API failed: "
                            + exception.getClass().getSimpleName() + ": " + exception.getMessage(), apiKey));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Output.failure(url, "Error: Request to Jina API failed: interrupted");
        }
        if (response.statusCode() != 200) {
            return Output.failure(url, ToolSecretRedactor.redact(
                    "Error: Jina API returned status " + response.statusCode() + ": " + response.body(), apiKey));
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

    /** Same contract as {@code web_search}: a guard rejection is a Tool error, not a Run error. */
    /** A Tool must not take the Run down: report the failure as the frozen error payload. */
    private static Output unexpected(Throwable failure, String url) {
        LOG.log(System.Logger.Level.WARNING, "web_fetch invocation failed: {0}: {1}",
                failure.getClass().getName(), bounded(failure.getMessage()));
        return Output.failure(url,
                "web_fetch invocation failed: " + failure.getClass().getSimpleName());
    }

    private static String bounded(String message) {
        if (message == null) {
            return "";
        }
        String single = message.replace((char) 10, ' ').replace((char) 13, ' ');
        return single.length() <= 200 ? single : single.substring(0, 200);
    }

    private static Output refused(String guard, String url, JavaToolContext context, String message) {
        LOG.log(System.Logger.Level.WARNING,
                "web_fetch refused guard={0} principal={1}/{2} deadline={3} now={4}",
                guard,
                context.principal().principalId(),
                context.principal().principalType(),
                context.deadline(),
                Instant.now());
        return Output.failure(url, message);
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

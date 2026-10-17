package io.haifa.agent.superharness.server.tool.web;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Endpoint, provider selection, and default-value configuration shared by the default web tools
 * ({@code web_search}, {@code web_fetch}, {@code image_search}).
 *
 * <p>Supports both the frozen key-free default set (DuckDuckGo and Jina Reader) and authenticated
 * commercial providers like DeepSeek ({@code POST https://api.deepseek.com/v1/tools/search} and
 * {@code POST https://api.deepseek.com/v1/tools/fetch}).
 *
 * <p>The DeepSeek provider uses {@code DEEPSEEK_TOOL_API_KEY} (separated from LLM's
 * {@code DEEPSEEK_API_KEY}) read dynamically from the SDK credential map.
 */
public record WebToolConfig(
        WebSearchProvider searchProvider,
        WebFetchProvider fetchProvider,
        String deepseekSearchUrl,
        String deepseekFetchUrl,
        String duckduckgoHtmlUrl,
        String duckduckgoHomeUrl,
        String duckduckgoImagesUrl,
        String jinaReaderUrl,
        Duration timeout,
        int maxResults,
        String region,
        String safeSearch,
        Optional<String> deepseekToolApiKey,
        Optional<String> jinaApiKey) {

    public enum WebSearchProvider {
        DUCKDUCKGO,
        DEEPSEEK;

        public static WebSearchProvider fromString(String value) {
            if (value == null || value.isBlank()) return DUCKDUCKGO;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "deepseek" -> DEEPSEEK;
                default -> DUCKDUCKGO;
            };
        }
    }

    public enum WebFetchProvider {
        JINA,
        DEEPSEEK;

        public static WebFetchProvider fromString(String value) {
            if (value == null || value.isBlank()) return JINA;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "deepseek" -> DEEPSEEK;
                default -> JINA;
            };
        }
    }

    public static final String DEEPSEEK_TOOL_API_KEY_CREDENTIAL = "DEEPSEEK_TOOL_API_KEY";
    public static final String JINA_API_KEY_CREDENTIAL = "JINA_API_KEY";

    static final String HTML_URL_ENVIRONMENT = "HAIFA_SUPER_HARNESS_DDG_HTML_URL";
    static final String HOME_URL_ENVIRONMENT = "HAIFA_SUPER_HARNESS_DDG_HOME_URL";
    static final String IMAGE_URL_ENVIRONMENT = "HAIFA_SUPER_HARNESS_DDG_IMAGE_URL";
    static final String JINA_URL_ENVIRONMENT = "HAIFA_SUPER_HARNESS_JINA_READER_URL";
    static final String TIMEOUT_ENVIRONMENT = "HAIFA_SUPER_HARNESS_WEB_TIMEOUT_SECONDS";
    static final String MAX_RESULTS_ENVIRONMENT = "HAIFA_SUPER_HARNESS_WEB_MAX_RESULTS";

    static final String DEEPSEEK_SEARCH_URL_ENVIRONMENT = "HAIFA_SUPER_HARNESS_DEEPSEEK_SEARCH_URL";
    static final String DEEPSEEK_FETCH_URL_ENVIRONMENT = "HAIFA_SUPER_HARNESS_DEEPSEEK_FETCH_URL";
    static final String SEARCH_PROVIDER_ENVIRONMENT = "HAIFA_SUPER_HARNESS_WEB_SEARCH_PROVIDER";
    static final String FETCH_PROVIDER_ENVIRONMENT = "HAIFA_SUPER_HARNESS_WEB_FETCH_PROVIDER";

    static final String DEFAULT_HTML_URL = "https://html.duckduckgo.com/html/";
    static final String DEFAULT_HOME_URL = "https://duckduckgo.com";
    static final String DEFAULT_IMAGES_URL = "https://duckduckgo.com/i.js";
    static final String DEFAULT_JINA_URL = "https://r.jina.ai/";
    static final String DEFAULT_DEEPSEEK_SEARCH_URL = "https://api.deepseek.com/v1/tools/search";
    static final String DEFAULT_DEEPSEEK_FETCH_URL = "https://api.deepseek.com/v1/tools/fetch";
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    static final int DEFAULT_MAX_RESULTS = 5;
    static final String DEFAULT_REGION = "wt-wt";
    static final String DEFAULT_SAFE_SEARCH = "moderate";

    public WebToolConfig {
        Objects.requireNonNull(searchProvider, "searchProvider");
        Objects.requireNonNull(fetchProvider, "fetchProvider");
        Objects.requireNonNull(deepseekSearchUrl, "deepseekSearchUrl");
        Objects.requireNonNull(deepseekFetchUrl, "deepseekFetchUrl");
        Objects.requireNonNull(duckduckgoHtmlUrl, "duckduckgoHtmlUrl");
        Objects.requireNonNull(duckduckgoHomeUrl, "duckduckgoHomeUrl");
        Objects.requireNonNull(duckduckgoImagesUrl, "duckduckgoImagesUrl");
        Objects.requireNonNull(jinaReaderUrl, "jinaReaderUrl");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(safeSearch, "safeSearch");
        deepseekToolApiKey = deepseekToolApiKey == null ? Optional.empty() : deepseekToolApiKey;
        jinaApiKey = jinaApiKey == null ? Optional.empty() : jinaApiKey;
        if (maxResults < 1) throw new IllegalArgumentException("maxResults must be >= 1");
    }

    /** 12-argument constructor defaulting optional API keys to empty. */
    public WebToolConfig(
            WebSearchProvider searchProvider,
            WebFetchProvider fetchProvider,
            String deepseekSearchUrl,
            String deepseekFetchUrl,
            String duckduckgoHtmlUrl,
            String duckduckgoHomeUrl,
            String duckduckgoImagesUrl,
            String jinaReaderUrl,
            Duration timeout,
            int maxResults,
            String region,
            String safeSearch) {
        this(
                searchProvider,
                fetchProvider,
                deepseekSearchUrl,
                deepseekFetchUrl,
                duckduckgoHtmlUrl,
                duckduckgoHomeUrl,
                duckduckgoImagesUrl,
                jinaReaderUrl,
                timeout,
                maxResults,
                region,
                safeSearch,
                Optional.empty(),
                Optional.empty());
    }

    /** Backward-compatible constructor for existing tests and call sites. */
    public WebToolConfig(
            String duckduckgoHtmlUrl,
            String duckduckgoHomeUrl,
            String duckduckgoImagesUrl,
            String jinaReaderUrl,
            Duration timeout,
            int maxResults,
            String region,
            String safeSearch) {
        this(
                WebSearchProvider.DUCKDUCKGO,
                WebFetchProvider.JINA,
                DEFAULT_DEEPSEEK_SEARCH_URL,
                DEFAULT_DEEPSEEK_FETCH_URL,
                duckduckgoHtmlUrl,
                duckduckgoHomeUrl,
                duckduckgoImagesUrl,
                jinaReaderUrl,
                timeout,
                maxResults,
                region,
                safeSearch,
                Optional.empty(),
                Optional.empty());
    }

    public static WebToolConfig defaults() {
        return new WebToolConfig(
                WebSearchProvider.DUCKDUCKGO,
                WebFetchProvider.JINA,
                DEFAULT_DEEPSEEK_SEARCH_URL,
                DEFAULT_DEEPSEEK_FETCH_URL,
                DEFAULT_HTML_URL,
                DEFAULT_HOME_URL,
                DEFAULT_IMAGES_URL,
                DEFAULT_JINA_URL,
                DEFAULT_TIMEOUT,
                DEFAULT_MAX_RESULTS,
                DEFAULT_REGION,
                DEFAULT_SAFE_SEARCH,
                Optional.empty(),
                Optional.empty());
    }

    /** Logical service host for catalog metadata; a transport proxy is not the service host. */
    static String endpointHost(String endpoint) {
        URI uri = URI.create(endpoint);
        String host = uri.getHost();
        if (host == null || host.isBlank() || uri.getUserInfo() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Web tool endpoint must be an absolute http(s) service URL: " + endpoint);
        }
        return host;
    }

    /** Test/production override seam: every endpoint, provider and default can be replaced without code changes. */
    public static WebToolConfig fromEnvironment(Map<String, String> environment) {
        WebToolConfig defaults = defaults();
        WebSearchProvider searchProvider = WebSearchProvider.fromString(environment.get(SEARCH_PROVIDER_ENVIRONMENT));
        WebFetchProvider fetchProvider = WebFetchProvider.fromString(environment.get(FETCH_PROVIDER_ENVIRONMENT));
        String deepseekKey = environment.get(DEEPSEEK_TOOL_API_KEY_CREDENTIAL);
        String jinaKey = environment.get(JINA_API_KEY_CREDENTIAL);
        return new WebToolConfig(
                searchProvider,
                fetchProvider,
                value(environment, DEEPSEEK_SEARCH_URL_ENVIRONMENT, defaults.deepseekSearchUrl()),
                value(environment, DEEPSEEK_FETCH_URL_ENVIRONMENT, defaults.deepseekFetchUrl()),
                value(environment, HTML_URL_ENVIRONMENT, defaults.duckduckgoHtmlUrl()),
                value(environment, HOME_URL_ENVIRONMENT, defaults.duckduckgoHomeUrl()),
                value(environment, IMAGE_URL_ENVIRONMENT, defaults.duckduckgoImagesUrl()),
                value(environment, JINA_URL_ENVIRONMENT, defaults.jinaReaderUrl()),
                Duration.ofSeconds(positiveLong(environment, TIMEOUT_ENVIRONMENT, defaults.timeout().toSeconds())),
                positiveInt(environment, MAX_RESULTS_ENVIRONMENT, defaults.maxResults()),
                defaults.region(),
                defaults.safeSearch(),
                Optional.ofNullable(deepseekKey).filter(k -> !k.isBlank()),
                Optional.ofNullable(jinaKey).filter(k -> !k.isBlank()));
    }

    private static String value(Map<String, String> environment, String key, String fallback) {
        String candidate = environment.get(key);
        return candidate == null || candidate.isBlank() ? fallback : candidate.trim();
    }

    private static long positiveLong(Map<String, String> environment, String key, long fallback) {
        String candidate = environment.get(key);
        if (candidate == null || candidate.isBlank()) return fallback;
        try {
            long parsed = Long.parseLong(candidate.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static int positiveInt(Map<String, String> environment, String key, int fallback) {
        String candidate = environment.get(key);
        if (candidate == null || candidate.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(candidate.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }
}

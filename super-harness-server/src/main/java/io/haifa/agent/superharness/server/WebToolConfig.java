package io.haifa.agent.superharness.server;

import java.time.Duration;
import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * Endpoint and default-value configuration shared by the frozen default web tools
 * ({@code web_search}, {@code web_fetch}, {@code image_search}).
 *
 * <p>The frozen provider set is key-free: DuckDuckGo for text and images and the Jina Reader for
 * page fetching. Defaults are the real public endpoints; tests inject loopback base URLs so no unit
 * test ever touches the network. Values follow {@code config.yaml} ({@code max_results: 5},
 * {@code web_fetch.timeout: 10}) and the frozen ddgs defaults ({@code region=wt-wt},
 * {@code safesearch=moderate}).
 */
record WebToolConfig(
        String duckduckgoHtmlUrl,
        String duckduckgoHomeUrl,
        String duckduckgoImagesUrl,
        String jinaReaderUrl,
        Duration timeout,
        int maxResults,
        String region,
        String safeSearch) {
    static final String HTML_URL_ENVIRONMENT = "HAIFA_SUPER_HARNESS_DDG_HTML_URL";
    static final String HOME_URL_ENVIRONMENT = "HAIFA_SUPER_HARNESS_DDG_HOME_URL";
    static final String IMAGE_URL_ENVIRONMENT = "HAIFA_SUPER_HARNESS_DDG_IMAGE_URL";
    static final String JINA_URL_ENVIRONMENT = "HAIFA_SUPER_HARNESS_JINA_READER_URL";
    static final String TIMEOUT_ENVIRONMENT = "HAIFA_SUPER_HARNESS_WEB_TIMEOUT_SECONDS";
    static final String MAX_RESULTS_ENVIRONMENT = "HAIFA_SUPER_HARNESS_WEB_MAX_RESULTS";

    static final String DEFAULT_HTML_URL = "https://html.duckduckgo.com/html/";
    static final String DEFAULT_HOME_URL = "https://duckduckgo.com";
    static final String DEFAULT_IMAGES_URL = "https://duckduckgo.com/i.js";
    static final String DEFAULT_JINA_URL = "https://r.jina.ai/";
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    static final int DEFAULT_MAX_RESULTS = 5;
    static final String DEFAULT_REGION = "wt-wt";
    static final String DEFAULT_SAFE_SEARCH = "moderate";

    WebToolConfig {
        Objects.requireNonNull(duckduckgoHtmlUrl, "duckduckgoHtmlUrl");
        Objects.requireNonNull(duckduckgoHomeUrl, "duckduckgoHomeUrl");
        Objects.requireNonNull(duckduckgoImagesUrl, "duckduckgoImagesUrl");
        Objects.requireNonNull(jinaReaderUrl, "jinaReaderUrl");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(safeSearch, "safeSearch");
        if (maxResults < 1) throw new IllegalArgumentException("maxResults must be >= 1");
    }

    static WebToolConfig defaults() {
        return new WebToolConfig(
                DEFAULT_HTML_URL,
                DEFAULT_HOME_URL,
                DEFAULT_IMAGES_URL,
                DEFAULT_JINA_URL,
                DEFAULT_TIMEOUT,
                DEFAULT_MAX_RESULTS,
                DEFAULT_REGION,
                DEFAULT_SAFE_SEARCH);
    }

    /** Logical service host for catalog metadata; a transport proxy is not the service host. */
    static String endpointHost(String endpoint) {
        URI uri = URI.create(endpoint);
        String host = uri.getHost();
        if (host == null || host.isBlank() || uri.getUserInfo() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Web tool endpoint must be an absolute http(s) service URL");
        }
        return host;
    }

    /** Test/production override seam: every endpoint and default can be replaced without code changes. */
    static WebToolConfig fromEnvironment(Map<String, String> environment) {
        WebToolConfig defaults = defaults();
        return new WebToolConfig(
                value(environment, HTML_URL_ENVIRONMENT, defaults.duckduckgoHtmlUrl()),
                value(environment, HOME_URL_ENVIRONMENT, defaults.duckduckgoHomeUrl()),
                value(environment, IMAGE_URL_ENVIRONMENT, defaults.duckduckgoImagesUrl()),
                value(environment, JINA_URL_ENVIRONMENT, defaults.jinaReaderUrl()),
                Duration.ofSeconds(positiveLong(environment, TIMEOUT_ENVIRONMENT, defaults.timeout().toSeconds())),
                positiveInt(environment, MAX_RESULTS_ENVIRONMENT, defaults.maxResults()),
                defaults.region(),
                defaults.safeSearch());
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

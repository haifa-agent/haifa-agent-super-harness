package io.haifa.agent.superharness.server;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Minimal DuckDuckGo HTTP client for the frozen default {@code web_search} and
 * {@code image_search} tools. It mirrors the request shapes used by the frozen {@code ddgs} package:
 * the HTML endpoint for text and the {@code /i.js} JSON endpoint (with a {@code vqd} handshake) for
 * images. The frozen implementation only enables the {@code duckduckgo} engine; the multi-engine
 * {@code backend=auto} aggregation and Wikipedia region inference are a registered known difference.
 */
final class DuckDuckGoClient {
    // Frozen ddgs impersonates a real browser (primp) and never sends a product token; an
    // identifying UA made the HTML endpoint answer with a 202 bot challenge (see W11-task-2).
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/131.0.0.0 Safari/537.36";
    private static final String BROWSER_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";
    private static final String BROWSER_ACCEPT_LANGUAGE = "en-US,en;q=0.5";
    private static final String ENGINE = "duckduckgo";
    private static final String AD_PREFIX = "https://duckduckgo.com/y.js?";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final System.Logger DEFAULT_LOG =
            System.getLogger(DuckDuckGoClient.class.getName() + ".web");

    private final WebToolConfig config;
    private final HttpClient http;
    private final System.Logger webLog;

    static System.Logger defaultLog() {
        return DEFAULT_LOG;
    }

    DuckDuckGoClient(WebToolConfig config) {
        this(config, DEFAULT_LOG);
    }

    DuckDuckGoClient(WebToolConfig config, System.Logger webLog) {
        this(config, webLog, WebHttpClients.create(config));
    }

    DuckDuckGoClient(WebToolConfig config, System.Logger webLog, HttpClient http) {
        this.config = Objects.requireNonNull(config, "config");
        this.webLog = Objects.requireNonNull(webLog, "webLog");
        this.http = Objects.requireNonNull(http, "http");
    }

    record TextResult(String title, String url, String content) {}

    record ImageResult(String title, String imageUrl, String thumbnailUrl) {}

    /**
     * Frozen {@code _search_text} for the single {@code duckduckgo} backend. The query is sent as a
     * GET (query params {@code q}, {@code kl} for region, {@code df} for time range) without a
     * {@code Content-Type} header, as on the candidate browser request used in W11-task-2.
     * These request headers do not guarantee challenge-free replies: 202/403 remain failures.
     */
    List<TextResult> search(String query, int maxResults, Optional<String> timeRange) {
        StringBuilder url = new StringBuilder(config.duckduckgoHtmlUrl())
                .append("?q=").append(encode(query))
                .append("&kl=").append(encode(config.region()));
        timeRange.ifPresent(value -> url.append("&df=").append(encode(value)));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(config.timeout())
                .header("Accept", BROWSER_ACCEPT)
                .header("Accept-Language", BROWSER_ACCEPT_LANGUAGE)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        Document document;
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
            if (response.statusCode() != 200) {
                logNon200("text", response.statusCode(), query);
                throw new WebToolException("DuckDuckGo HTML endpoint returned status " + response.statusCode());
            }
            document = Jsoup.parse(response.body());
        } catch (IOException exception) {
            throw new WebToolException("DuckDuckGo text request failed: " + exception.getClass().getSimpleName());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new WebToolException("DuckDuckGo text request was interrupted");
        }

        List<TextResult> results = new ArrayList<>();
        for (Element item : document.select("div[class*=body]")) {
            // Frozen xpath uses "./a" (a direct child), not any descendant `a`; the direct child is
            // the result snippet anchor, while the title link sits inside the heading.
            Element anchor = directChildAnchor(item);
            if (anchor == null) continue;
            String href = normalizeHref(anchor.attr("href"));
            if (href.isEmpty() || href.startsWith(AD_PREFIX)) continue;
            Element heading = item.selectFirst("h2");
            String title = heading == null ? "" : collapse(heading.text());
            String body = collapse(anchor.text());
            results.add(new TextResult(title, href, body));
            if (results.size() >= maxResults) break;
        }
        return results;
    }

    /** Frozen {@code _search_images} for the single {@code duckduckgo} backend. */
    List<ImageResult> images(
            String query,
            int maxResults,
            Optional<String> size,
            Optional<String> typeImage,
            Optional<String> layout) {
        String vqd = vqd(query);
        List<String> filters = List.of(
                "",
                size.map(value -> "size:" + value).orElse(""),
                "",
                typeImage.map(value -> "type:" + value).orElse(""),
                layout.map(value -> "layout:" + value).orElse(""),
                "");
        String filter = String.join(",", filters);
        StringBuilder url = new StringBuilder(config.duckduckgoImagesUrl())
                .append("?o=json")
                .append("&q=").append(encode(query))
                .append("&l=").append(encode(config.region()))
                .append("&vqd=").append(encode(vqd))
                .append("&p=").append(encode(safeSearchCode()))
                .append("&ct=AT");
        if (!filter.replace(",", "").isEmpty()) {
            url.append("&f=").append(encode(filter));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(config.timeout())
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.5")
                .header("Referer", config.duckduckgoHomeUrl() + "/")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        JsonNode payload;
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
            if (response.statusCode() != 200) {
                logNon200("images", response.statusCode(), query);
                throw new WebToolException("DuckDuckGo image endpoint returned status " + response.statusCode());
            }
            payload = JSON.readTree(response.body());
        } catch (IOException exception) {
            throw new WebToolException("DuckDuckGo image request failed: " + exception.getClass().getSimpleName());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new WebToolException("DuckDuckGo image request was interrupted");
        }

        List<ImageResult> results = new ArrayList<>();
        for (JsonNode item : payload.path("results")) {
            if (results.size() >= maxResults) break;
            results.add(new ImageResult(
                    text(item, "title"), text(item, "image"), text(item, "thumbnail")));
        }
        return results;
    }

    /** Frozen {@code ddgs.utils._extract_vqd}: first {@code vqd="..."}, then {@code vqd=...&}, then {@code vqd='...'}. */
    String vqd(String query) {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(config.duckduckgoHomeUrl() + "/?q=" + encode(query)))
                .timeout(config.timeout())
                .header("Accept", BROWSER_ACCEPT)
                .header("Accept-Language", BROWSER_ACCEPT_LANGUAGE)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        String body;
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
            if (response.statusCode() != 200) {
                logNon200("vqd", response.statusCode(), query);
                throw new WebToolException("DuckDuckGo vqd handshake returned status " + response.statusCode());
            }
            body = response.body();
        } catch (IOException exception) {
            throw new WebToolException("DuckDuckGo vqd handshake failed: " + exception.getClass().getSimpleName());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new WebToolException("DuckDuckGo vqd handshake was interrupted");
        }
        for (String[] pattern : List.of(
                new String[] {"vqd=\"", "\""}, new String[] {"vqd=", "&"}, new String[] {"vqd='", "'"})) {
            int start = body.indexOf(pattern[0]);
            if (start < 0) continue;
            int from = start + pattern[0].length();
            int end = body.indexOf(pattern[1], from);
            if (end >= 0) return body.substring(from, end);
        }
        throw new WebToolException("Could not extract vqd from DuckDuckGo response");
    }

    /**
     * Diagnostic for a non-200 DuckDuckGo reply: the frozen contract still maps it to the
     * no-result payload, so without this record an external challenge (202/403) is indistinguishable
     * from an empty/parse result. The query is the only caller data included.
     */
    private void logNon200(String stage, int status, String query) {
        webLog.log(
                System.Logger.Level.WARNING,
                "DuckDuckGo non-200 reply: engine={0} stage={1} status={2} query={3}",
                ENGINE,
                stage,
                status,
                query);
    }

    private String safeSearchCode() {
        return switch (config.safeSearch().toLowerCase(java.util.Locale.ROOT)) {
            case "off" -> "-1";
            case "on", "moderate" -> "1";
            default -> "1";
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static Element directChildAnchor(Element item) {
        for (Element child : item.children()) {
            if (child.tagName().equals("a")) return child;
        }
        return null;
    }

    private static String normalizeHref(String href) {
        String trimmed = href == null ? "" : href.trim();
        if (trimmed.startsWith("//")) return "https:" + trimmed;
        return trimmed;
    }

    private static String collapse(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, UTF_8);
    }

    /** Internal transport/parse failure; tools translate it into the frozen no-result/error shape. */
    static final class WebToolException extends RuntimeException {
        WebToolException(String message) {
            super(message);
        }
    }
}

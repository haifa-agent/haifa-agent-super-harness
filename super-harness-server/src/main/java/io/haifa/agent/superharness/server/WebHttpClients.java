package io.haifa.agent.superharness.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** Process-environment mapping confined to the default Web tools; never changes JVM globals. */
final class WebHttpClients {
    private WebHttpClients() {}

    static HttpClient create(WebToolConfig config) {
        return create(config, System.getenv());
    }

    static HttpClient create(WebToolConfig config, Map<String, String> environment) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(config.timeout())
                .followRedirects(HttpClient.Redirect.NORMAL);
        ProxySelector selector = selector(environment, ProxySelector.getDefault());
        if (selector != null) builder.proxy(selector);
        return builder.build();
    }

    /**
     * Exact product key for the default Web tools. A non-empty value is the HTTP proxy for every
     * Web tool scheme (HTTPS targets use CONNECT through it) and wins over the generic variables
     * below and the JDK selector; empty or missing keeps the generic compatibility mapping.
     */
    static final String TOOL_HTTP_PROXY_ENVIRONMENT = "HAIFA_SUPER_HARNESS_TOOL_HTTP_PROXY";

    /** An explicit tool proxy wins; otherwise a missing protocol variable falls back to ALL_PROXY, then the JDK selector. */
    static ProxySelector selector(Map<String, String> environment, ProxySelector fallback) {
        Objects.requireNonNull(environment, "environment");
        String explicit = explicitToolProxy(environment);
        if (!explicit.isEmpty()) {
            Proxy proxy = parseProxy(explicit, TOOL_HTTP_PROXY_ENVIRONMENT);
            return mapping(proxy, proxy, List.of(), fallback);
        }
        String all = value(environment, "ALL_PROXY");
        String http = value(environment, "HTTP_PROXY");
        String https = value(environment, "HTTPS_PROXY");
        Proxy httpProxy = parseProxy(http.isEmpty() ? all : http, "HTTP_PROXY/ALL_PROXY");
        Proxy httpsProxy = parseProxy(https.isEmpty() ? all : https, "HTTPS_PROXY/ALL_PROXY");
        List<Predicate<URI>> exclusions = exclusions(value(environment, "NO_PROXY"));
        if (httpProxy == null && httpsProxy == null && exclusions.isEmpty()) return fallback;
        return mapping(httpProxy, httpsProxy, exclusions, fallback);
    }

    private static ProxySelector mapping(Proxy httpProxy, Proxy httpsProxy,
            List<Predicate<URI>> exclusions, ProxySelector fallback) {
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                Objects.requireNonNull(uri, "uri");
                if (exclusions.stream().anyMatch(rule -> rule.test(uri))) return List.of(Proxy.NO_PROXY);
                Proxy proxy = switch (uri.getScheme().toLowerCase(Locale.ROOT)) {
                    case "http" -> httpProxy;
                    case "https" -> httpsProxy;
                    default -> null;
                };
                if (proxy != null) return List.of(proxy);
                return fallback == null ? List.of(Proxy.NO_PROXY) : fallback.select(uri);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                // The JDK receives one proxy, never a hidden direct-connect fallback.
                if (fallback != null && !Objects.equals(address, addressOf(httpProxy))
                        && !Objects.equals(address, addressOf(httpsProxy))) {
                    fallback.connectFailed(uri, address, failure);
                }
            }
        };
    }

    /** Only the exact uppercase product key is honored, independent of the lowercase-preferring env convention. */
    private static String explicitToolProxy(Map<String, String> environment) {
        String raw = environment.get(TOOL_HTTP_PROXY_ENVIRONMENT);
        return raw == null ? "" : raw.trim();
    }

    private static SocketAddress addressOf(Proxy proxy) {
        return proxy == null ? null : proxy.address();
    }

    private static String value(Map<String, String> environment, String name) {
        // Match HTTPX/Python environment convention: an explicit lowercase entry wins,
        // including an empty value that disables the uppercase equivalent.
        String candidate = environment.containsKey(name.toLowerCase(Locale.ROOT))
                ? environment.get(name.toLowerCase(Locale.ROOT)) : environment.get(name);
        return candidate == null ? "" : candidate.trim();
    }

    private static Proxy parseProxy(String value, String name) {
        if (value.isEmpty()) return null;
        URI uri;
        try {
            uri = URI.create(value.contains("://") ? value : "http://" + value);
        } catch (IllegalArgumentException invalid) {
            // Never attach the parse exception: its message can contain proxy credentials.
            throw invalidProxy(name);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (!uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))) {
            throw invalidProxy(name);
        }
        int port = uri.getPort() == -1 ? 80 : uri.getPort();
        if (port < 1 || port > 65535) throw invalidProxy(name);
        return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(uri.getHost(), port));
    }

    private static IllegalArgumentException invalidProxy(String name) {
        return new IllegalArgumentException(name
                + " requires an HTTP proxy without credentials, query or fragment;"
                + " SOCKS, TLS-to-proxy and proxy authentication are unsupported");
    }

    /**
     * Bare domains match themselves and subdomains at a dot boundary; a leading dot matches
     * subdomains only. IP literals match exactly. Ports and optional http(s) schemes restrict a rule.
     * Wildcard '*', exact/domain/port exclusions suffice for the frozen Web tool deployment.
     */
    private static List<Predicate<URI>> exclusions(String configured) {
        List<Predicate<URI>> result = new ArrayList<>();
        for (String entry : configured.split(",")) {
            String token = entry.trim();
            if (token.isEmpty()) continue;
            if (token.equals("*")) {
                result.add(uri -> true);
                continue;
            }
            boolean explicitScheme = token.contains("://");
            String scheme = explicitScheme ? token.substring(0, token.indexOf("://")) : "";
            String authority = explicitScheme ? token.substring(token.indexOf("://") + 3) : token;
            boolean childrenOnly = authority.startsWith(".");
            if (childrenOnly) authority = authority.substring(1);
            URI rule;
            try {
                rule = URI.create("http://" + authority);
            } catch (IllegalArgumentException invalid) {
                throw invalidExclusion();
            }
            if ((!scheme.isEmpty() && !scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
                    || rule.getHost() == null || rule.getRawUserInfo() != null
                    || !rule.getRawPath().isEmpty() || rule.getRawQuery() != null
                    || rule.getRawFragment() != null || rule.getPort() == 0 || rule.getPort() > 65535) {
                throw invalidExclusion();
            }
            String host = normalizeHost(rule.getHost());
            int port = rule.getPort();
            boolean addressLiteral = host.indexOf(':') >= 0 || host.matches("[0-9.]+");
            result.add(uri -> {
                if (uri.getHost() == null) return false;
                if (!scheme.isEmpty() && !scheme.equalsIgnoreCase(uri.getScheme())) return false;
                int targetPort = uri.getPort() == -1
                        ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
                if (port != -1 && port != targetPort) return false;
                String target = normalizeHost(uri.getHost());
                return (!childrenOnly && target.equals(host))
                        || (!addressLiteral && target.endsWith("." + host));
            });
        }
        return List.copyOf(result);
    }

    private static String normalizeHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private static IllegalArgumentException invalidExclusion() {
        return new IllegalArgumentException("NO_PROXY requires comma-separated hosts/domains with optional"
                + " port or http(s) scheme, or '*'; CIDR and other wildcard forms are unsupported");
    }
}

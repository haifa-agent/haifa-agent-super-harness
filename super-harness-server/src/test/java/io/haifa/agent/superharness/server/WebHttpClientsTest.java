package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real loopback HTTP proxy traffic; *.invalid targets never resolve or receive direct requests. */
@Timeout(10)
class WebHttpClientsTest {
    @Test
    void emptyEnvironmentPreservesDefaultSelectorAndNoGlobalMutation() {
        ProxySelector previous = ProxySelector.getDefault();
        assertThat(WebHttpClients.selector(Map.of(), previous)).isSameAs(previous);
        WebHttpClients.create(WebToolConfig.defaults(), Map.of("HTTP_PROXY", "http://127.0.0.1:1234"));
        assertThat(ProxySelector.getDefault()).isSameAs(previous);
    }

    @Test
    void lowercaseProtocolValuesWinAndAllProxyIsOnlyAFallback() {
        ProxySelector selector = WebHttpClients.selector(Map.of(
                "HTTP_PROXY", "http://upper.invalid:1111",
                "http_proxy", "lower.invalid:2222",
                "HTTPS_PROXY", "http://secure.invalid:3333",
                "ALL_PROXY", "http://all.invalid:4444"), null);
        assertProxy(selector, "http://target.invalid", "lower.invalid", 2222);
        assertProxy(selector, "https://target.invalid", "secure.invalid", 3333);
        ProxySelector all = WebHttpClients.selector(Map.of(
                "all_proxy", "http://all.invalid:4444", "ALL_PROXY", "http://other.invalid:5555"), null);
        assertProxy(all, "http://target.invalid", "all.invalid", 4444);
        assertProxy(all, "https://target.invalid", "all.invalid", 4444);
    }

    @Test
    void missingProtocolUsesTheOriginalSelectorAndEmptyLowercaseDisablesUppercase() {
        Proxy fallback = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("jdk.invalid", 6666));
        ProxySelector original = fixed(fallback);
        ProxySelector selector = WebHttpClients.selector(Map.of(
                "HTTP_PROXY", "http://env.invalid:1111", "http_proxy", "",
                "HTTPS_PROXY", "http://secure.invalid:3333"), original);
        assertThat(selector.select(URI.create("http://target.invalid"))).containsExactly(fallback);
        assertProxy(selector, "https://target.invalid", "secure.invalid", 3333);
    }

    @Test
    void explicitToolProxyOverridesGenericVariablesAndNoProxy() {
        ProxySelector selector = WebHttpClients.selector(Map.of(
                WebHttpClients.TOOL_HTTP_PROXY_ENVIRONMENT, "http://explicit.invalid:9090",
                "HTTP_PROXY", "http://http.invalid:1111",
                "HTTPS_PROXY", "http://https.invalid:2222",
                "ALL_PROXY", "http://all.invalid:3333",
                "NO_PROXY", "target.invalid,*"), null);
        assertProxy(selector, "http://target.invalid", "explicit.invalid", 9090);
        assertProxy(selector, "https://target.invalid", "explicit.invalid", 9090);
    }

    @Test
    void blankOrMissingExplicitToolProxyKeepsEnvironmentMapping() {
        ProxySelector blank = WebHttpClients.selector(Map.of(
                WebHttpClients.TOOL_HTTP_PROXY_ENVIRONMENT, "   ",
                "HTTP_PROXY", "http://env.invalid:1111",
                "NO_PROXY", "excluded.invalid"), null);
        assertProxy(blank, "http://target.invalid", "env.invalid", 1111);
        assertThat(blank.select(URI.create("http://excluded.invalid"))).containsExactly(Proxy.NO_PROXY);
        ProxySelector absent = WebHttpClients.selector(Map.of(
                "HTTPS_PROXY", "http://secure.invalid:3333"), null);
        assertProxy(absent, "https://target.invalid", "secure.invalid", 3333);
    }

    @Test
    void invalidExplicitToolProxyFailsWithoutLeakingCredentialsOrCause() {
        for (String value : List.of("socks5://127.0.0.1:1080", "https://proxy.invalid:443",
                "http://private-user:private-password@proxy.invalid:8080", "http://private-user:private-password@bad[host")) {
            assertThatThrownBy(() -> WebHttpClients.selector(Map.of(
                    WebHttpClients.TOOL_HTTP_PROXY_ENVIRONMENT, value), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(WebHttpClients.TOOL_HTTP_PROXY_ENVIRONMENT)
                    .hasMessageNotContaining("private-user").hasMessageNotContaining("private-password")
                    .hasNoCause();
        }
    }

    @Test
    void noProxyMatchesDomainBoundariesPortsSchemesAndAddressLiterals() {
        ProxySelector selector = WebHttpClients.selector(Map.of(
                "ALL_PROXY", "http://proxy.invalid:8080",
                "NO_PROXY", "example.com,.children.test,exact.test:8081,https://scheme.test,[::1]:8082,127.0.0.1"), null);
        for (String url : List.of("http://example.com", "https://sub.example.com",
                "http://EXAMPLE.COM.", "http://sub.children.test", "http://exact.test:8081",
                "https://scheme.test", "http://[::1]:8082", "http://127.0.0.1:9999")) {
            assertThat(selector.select(URI.create(url))).as(url).containsExactly(Proxy.NO_PROXY);
        }
        for (String url : List.of("http://notexample.com", "http://example.com.evil.test",
                "http://children.test", "http://exact.test:8080", "http://scheme.test",
                "http://[::1]:8083", "http://127.0.0.10")) {
            assertProxy(selector, url, "proxy.invalid", 8080);
        }
        assertThat(WebHttpClients.selector(Map.of("NO_PROXY", "*"), fixed(new Proxy(
                Proxy.Type.HTTP, InetSocketAddress.createUnresolved("jdk.invalid", 6666))))
                .select(URI.create("https://anything.invalid"))).containsExactly(Proxy.NO_PROXY);
    }

    @Test
    void invalidOrUnsupportedConfigurationFailsWithoutLeakingCredentialsOrCauses() {
        for (String value : List.of("socks5://127.0.0.1:1080", "https://proxy.invalid:443",
                "http://private-user:private-password@proxy.invalid:8080", "http://proxy.invalid:70000",
                "http://proxy.invalid/path", "http://private-user:private-password@bad[host")) {
            assertThatThrownBy(() -> WebHttpClients.selector(Map.of("HTTP_PROXY", value), null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HTTP_PROXY")
                    .hasMessageNotContaining("private-user").hasMessageNotContaining("private-password")
                    .hasNoCause();
        }
        for (String value : List.of("10.0.0.0/8", "*.example.com", "private-user:private-password@bad[host")) {
            assertThatThrownBy(() -> WebHttpClients.selector(Map.of("NO_PROXY", value), null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NO_PROXY")
                    .hasMessageNotContaining("private-password").hasNoCause();
        }
    }

    @Test
    void searchFetchAndImageHandshakeActuallyUseTheConfiguredProxy() throws Exception {
        HttpServer proxy = WebToolTestSupport.loopbackServer();
        List<String> requests = new CopyOnWriteArrayList<>();
        proxy.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            assertThat(exchange.getRequestURI().isAbsolute()).isTrue();
            assertThat(exchange.getRequestHeaders().getFirst("User-Agent")).doesNotContain("HaifaSuperHarness");
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/reader")) {
                assertThat(WebToolTestSupport.bodyText(exchange)).contains("https://page.invalid/article");
                respond(exchange, 200, "# Proxy reader result");
            } else if (path.equals("/html/")) {
                assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).isNull();
                respond(exchange, 200, "<div class='result__body'><h2>Proxy title</h2>"
                        + "<a href='https://result.invalid'>Proxy result</a></div>");
            } else if (path.equals("/i.js")) {
                respond(exchange, 200, "{\"results\":[{\"title\":\"Proxy image\","
                        + "\"image\":\"https://image.invalid/x.png\",\"thumbnail\":\"https://image.invalid/t.png\"}]}");
            } else respond(exchange, 200, "vqd=\"fixture-vqd\"");
        });
        proxy.start();
        try {
            WebToolConfig config = fakeConfig("http");
            var http = WebHttpClients.create(config, Map.of(
                    WebHttpClients.TOOL_HTTP_PROXY_ENVIRONMENT, address(proxy)));
            var ddg = new DuckDuckGoClient(config, DuckDuckGoClient.defaultLog(), http);
            var search = new ProductionWebSearchTool(config, ddg).invoke(new ProductionWebSearchTool.Input(
                    "proxy query + 中文", Optional.empty(), Optional.empty()), WebToolTestSupport.context());
            var fetch = new ProductionWebFetchTool(config, http).invoke(
                    new ProductionWebFetchTool.Input("https://page.invalid/article"), WebToolTestSupport.context());
            var images = new ProductionImageSearchTool(config, ddg).invoke(new ProductionImageSearchTool.Input(
                    "proxy images", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                    WebToolTestSupport.context());
            assertThat(search.result()).contains("Proxy title", "Proxy result");
            assertThat(fetch.content()).isEqualTo("# Proxy reader result");
            assertThat(fetch.error()).isEmpty();
            assertThat(images.result()).contains("Proxy image", "https://image.invalid/x.png");
            assertThat(requests).hasSize(4);
            assertThat(requests.get(0)).contains("GET http://origin.invalid/html/?q=proxy+query+%2B+%E4%B8%AD%E6%96%87");
            assertThat(requests.get(1)).isEqualTo("POST http://origin.invalid/reader");
            System.out.println("WEB_PROXY_SMOKE PASS: search, fetch, vqd, image HTTP requests=" + requests);
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void noProxyPortExclusionIsProvedByRealDirectAndProxyCounters() throws Exception {
        HttpServer target = WebToolTestSupport.loopbackServer();
        HttpServer proxy = WebToolTestSupport.loopbackServer();
        AtomicInteger direct = new AtomicInteger();
        AtomicInteger proxied = new AtomicInteger();
        target.createContext("/", exchange -> { direct.incrementAndGet(); respond(exchange, 200, "direct"); });
        proxy.createContext("/", exchange -> { proxied.incrementAndGet(); respond(exchange, 200, "proxied"); });
        target.start();
        proxy.start();
        try {
            String excluded = "127.0.0.1:" + target.getAddress().getPort();
            var http = WebHttpClients.create(fakeConfig("http"), Map.of(
                    "HTTP_PROXY", address(proxy), "NO_PROXY", excluded));
            URI directUri = URI.create(address(target) + "/direct");
            assertThat(http.proxy().orElseThrow().select(directUri)).containsExactly(Proxy.NO_PROXY);
            var directReply = http.send(HttpRequest.newBuilder(directUri)
                    .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
            var proxyReply = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:1/proxied"))
                    .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(directReply.body()).isEqualTo("direct");
            assertThat(proxyReply.body()).isEqualTo("proxied");
            assertThat(direct).hasValue(1);
            assertThat(proxied).hasValue(1);
            System.out.println("WEB_NO_PROXY_SMOKE PASS: direct=1 proxy=1; exact loopback port exclusion");
        } finally {
            System.out.println("WEB_NO_PROXY_DIAGNOSTIC: targetPort=" + target.getAddress().getPort()
                    + " proxyPort=" + proxy.getAddress().getPort() + " direct=" + direct.get() + " proxy=" + proxied.get());
            target.stop(0);
            proxy.stop(0);
        }
    }

    @Test
    void explicitToolProxyOverridesGenericAndNoProxyInRealRouting() throws Exception {
        HttpServer target = WebToolTestSupport.loopbackServer();
        HttpServer proxy = WebToolTestSupport.loopbackServer();
        AtomicInteger direct = new AtomicInteger();
        AtomicInteger proxied = new AtomicInteger();
        target.createContext("/", exchange -> { direct.incrementAndGet(); respond(exchange, 200, "direct"); });
        proxy.createContext("/", exchange -> { proxied.incrementAndGet(); respond(exchange, 200, "proxied"); });
        target.start();
        proxy.start();
        try {
            var http = WebHttpClients.create(fakeConfig("http"), Map.of(
                    WebHttpClients.TOOL_HTTP_PROXY_ENVIRONMENT, address(proxy),
                    "HTTP_PROXY", "http://127.0.0.1:1",
                    "NO_PROXY", "127.0.0.1:" + target.getAddress().getPort()));
            var reply = http.send(HttpRequest.newBuilder(URI.create(address(target) + "/explicit"))
                    .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(reply.body()).isEqualTo("proxied");
            assertThat(direct).hasValue(0);
            assertThat(proxied).hasValue(1);
            System.out.println("WEB_EXPLICIT_PROXY_OVERRIDE_SMOKE PASS: explicit key bypassed generic HTTP_PROXY+NO_PROXY");
        } finally {
            target.stop(0);
            proxy.stop(0);
        }
    }

    @Test
    void httpsTargetsReachTheHttpProxyUsingConnectWithoutDirectFallback() throws Exception {
        // JDK HttpServer does not dispatch CONNECT's authority-form URI to '/'. Read the
        // actual proxy bytes instead, with bounded accept/read/future waits and one connection.
        try (ServerSocket proxy = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            proxy.setSoTimeout(2000);
            var executor = Executors.newSingleThreadExecutor();
            var request = executor.submit(() -> {
                try (var socket = proxy.accept()) {
                    socket.setSoTimeout(2000);
                    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String firstLine = reader.readLine();
                    String header;
                    while ((header = reader.readLine()) != null && !header.isEmpty()) {}
                    socket.getOutputStream().write(("HTTP/1.1 403 Forbidden\r\n"
                            + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    return firstLine;
                }
            });
            try {
                var http = WebHttpClients.create(fakeConfig("https"), Map.of(
                        WebHttpClients.TOOL_HTTP_PROXY_ENVIRONMENT, "http://127.0.0.1:" + proxy.getLocalPort()));
                assertThatThrownBy(() -> http.send(HttpRequest.newBuilder(URI.create("https://origin.invalid/reader"))
                        .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString()))
                        .isInstanceOf(IOException.class);
                assertThat(request.get(3, TimeUnit.SECONDS)).isEqualTo("CONNECT origin.invalid:443 HTTP/1.1");
                System.out.println("WEB_HTTPS_PROXY_SMOKE PASS: CONNECT origin.invalid:443; rejected tunnel retained as failure");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static WebToolConfig fakeConfig(String scheme) {
        String base = scheme + "://origin.invalid";
        return new WebToolConfig(base + "/html/", base, base + "/i.js", base + "/reader",
                Duration.ofSeconds(2), 5, "wt-wt", "moderate");
    }

    private static String address(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
        exchange.close();
    }

    private static void assertProxy(ProxySelector selector, String url, String host, int port) {
        Proxy proxy = selector.select(URI.create(url)).getFirst();
        assertThat(proxy.type()).isEqualTo(Proxy.Type.HTTP);
        assertThat(proxy.address()).isEqualTo(InetSocketAddress.createUnresolved(host, port));
    }

    private static ProxySelector fixed(Proxy proxy) {
        return new ProxySelector() {
            @Override public List<Proxy> select(URI uri) { return List.of(proxy); }
            @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) {}
        };
    }
}

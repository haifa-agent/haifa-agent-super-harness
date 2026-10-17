package io.haifa.agent.superharness.server.tool.web;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.sdk.tool.JavaToolContext;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Shared loopback fixtures for the frozen default web tool contract tests (no real network). */
final class WebToolTestSupport {
    private WebToolTestSupport() {}

    static JavaToolContext context() {
        return context(Map.of());
    }

    static JavaToolContext context(Map<String, String> credentials) {
        return new JavaToolContext(
                new AgentRunId("run-1"),
                Optional.of(new AgentSessionId("session-1")),
                new ToolCallId("tool-call-1"),
                new TenantRef("public"),
                new PrincipalRef("default-public-user", "user"),
                Instant.now().plusSeconds(60),
                Optional.empty(),
                () -> false,
                credentials);
    }

    static WebToolConfig config(com.sun.net.httpserver.HttpServer server, String jinaPath) {
        String base = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getAddress().getPort();
        return new WebToolConfig(
                base + "/html/",
                base,
                base + "/i.js",
                base + jinaPath,
                Duration.ofMillis(400),
                WebToolConfig.DEFAULT_MAX_RESULTS,
                WebToolConfig.DEFAULT_REGION,
                WebToolConfig.DEFAULT_SAFE_SEARCH);
    }

    static WebToolConfig deepseekConfig(com.sun.net.httpserver.HttpServer server, String searchPath, String fetchPath) {
        String base = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getAddress().getPort();
        return new WebToolConfig(
                WebToolConfig.WebSearchProvider.DEEPSEEK,
                WebToolConfig.WebFetchProvider.DEEPSEEK,
                base + searchPath,
                base + fetchPath,
                base + "/html/",
                base,
                base + "/i.js",
                base + "/reader",
                Duration.ofMillis(400),
                WebToolConfig.DEFAULT_MAX_RESULTS,
                WebToolConfig.DEFAULT_REGION,
                WebToolConfig.DEFAULT_SAFE_SEARCH);
    }

    static com.sun.net.httpserver.HttpServer loopbackServer() throws java.io.IOException {
        return com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    static String bodyText(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        return new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
}

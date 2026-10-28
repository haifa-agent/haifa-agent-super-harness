package io.haifa.agent.superharness.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;

/**
 * Frozen DeerFlow {@code GET /api/capabilities/catalog}.
 *
 * <p>Capability discovery is readable by authenticated users and backed by the bundled
 * inert manifest catalog. Installation and mutation routes are intentionally out of scope.
 */
final class DeerFlowCapabilityCatalogApi implements HttpHandler {
    static final String PATH = "/api/capabilities";
    private static final String CATALOG_PATH = PATH + "/catalog";

    private final ObjectMapper json;
    private final byte[] catalogPayload;

    DeerFlowCapabilityCatalogApi(ObjectMapper json) {
        this.json = json;
        this.catalogPayload = payload(json);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!DeerFlowBootstrapApi.isLoopbackPeer(exchange)
                && !io.haifa.agent.superharness.server.auth.LocalAuthService.sessionAuthActive()) {
            send(exchange, 403, Map.of("detail", "Local profile accepts loopback clients only"));
            return;
        }
        if (!CATALOG_PATH.equals(exchange.getRequestURI().getPath())) {
            send(exchange, 404, Map.of("detail", "Not Found"));
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            send(exchange, 405, Map.of("detail", "Method Not Allowed"));
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, catalogPayload.length);
        try (var output = exchange.getResponseBody()) {
            output.write(catalogPayload);
        }
    }

    private static byte[] payload(ObjectMapper json) {
        try {
            return json.writeValueAsBytes(CapabilityCatalog.catalog());
        } catch (IOException failure) {
            throw new IllegalStateException("Frozen capability catalog could not be serialized", failure);
        }
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, payload.length);
        try (var output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }
}

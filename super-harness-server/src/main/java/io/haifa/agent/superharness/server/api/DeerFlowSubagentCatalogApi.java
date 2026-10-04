package io.haifa.agent.superharness.server.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.haifa.agent.superharness.server.config.SubagentCatalogStore;
import io.haifa.agent.superharness.server.config.SubagentCatalogStore.CatalogException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Frozen subagent management routes over the existing local profile identity. */
final class DeerFlowSubagentCatalogApi implements HttpHandler {
    private static final String PATH = "/api/subagents";
    private static final int MAX_BODY_BYTES = 128 * 1024;
    static final String ADMIN_REQUIRED = "Admin privileges are required to manage subagents.";
    private final ObjectMapper json;
    private final SubagentCatalogStore store;
    private final Predicate<String> configuredModel;
    private final Predicate<HttpExchange> admin;

    DeerFlowSubagentCatalogApi(ObjectMapper json, SubagentCatalogStore store, Predicate<String> configuredModel,
            Predicate<HttpExchange> admin) {
        this.json = json;
        this.store = store;
        this.configuredModel = configuredModel;
        this.admin = admin;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!DeerFlowBootstrapApi.isLoopbackPeer(exchange)) {
                send(exchange, 403, Map.of("detail", "Local profile accepts loopback clients only"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            boolean collection = path.equals(PATH);
            String name = path.startsWith(PATH + "/") ? path.substring(PATH.length() + 1) : null;
            if (!collection && (name == null || name.isEmpty() || name.contains("/"))) {
                send(exchange, 404, Map.of("detail", "Not Found"));
                return;
            }
            boolean isAdmin = admin.test(exchange);
            if (collection && method.equals("GET")) {
                send(exchange, 200, Map.of("subagents", store.catalog(isAdmin)));
            } else if ((collection && method.equals("POST")) || (!collection && (method.equals("PUT") || method.equals("DELETE")))) {
                requireAdmin(isAdmin);
                if (method.equals("POST")) send(exchange, 201, store.create(readObject(exchange), configuredModel));
                else if (method.equals("PUT")) send(exchange, 200, store.update(name, readObject(exchange), configuredModel));
                else {
                    store.delete(name);
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                }
            } else {
                exchange.getResponseHeaders().set("Allow", collection ? "GET, POST" : "PUT, DELETE");
                send(exchange, 405, Map.of("detail", "Method Not Allowed"));
            }
        } catch (CatalogException exception) {
            send(exchange, exception.status(), Map.of("detail", exception.detail()));
        } catch (IOException | RuntimeException exception) {
            send(exchange, 500, Map.of("detail", "Subagent configuration store is unavailable"));
        }
    }

    static void requireAdmin(boolean isAdmin) {
        if (!isAdmin) throw new CatalogException(403, ADMIN_REQUIRED);
    }

    private JsonNode readObject(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) throw new CatalogException(413, "Request body is too large");
        JsonNode value;
        try {
            value = json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body);
        } catch (JsonProcessingException exception) {
            throw invalidBody("Invalid JSON body");
        }
        if (value == null || !value.isObject()) throw invalidBody("Expected a JSON object");
        return value;
    }

    private static CatalogException invalidBody(String message) {
        return new CatalogException(422, List.of(Map.of("loc", List.of("body"), "msg", message)));
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, payload.length);
        try (var output = exchange.getResponseBody()) { output.write(payload); }
    }
}

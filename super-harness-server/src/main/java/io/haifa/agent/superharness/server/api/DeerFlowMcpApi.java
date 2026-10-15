package io.haifa.agent.superharness.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.haifa.agent.superharness.server.api.dto.McpCacheResetResponse;
import io.haifa.agent.superharness.server.config.McpConfigStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Frozen DeerFlow MCP configuration REST projection.
 *
 * <p>Path and wire shape mirror {@code deer-flow/backend/app/gateway/routers/mcp.py}
 * and the frozen frontend client {@code frontend/src/core/mcp/api.ts}:
 * {@code GET/PUT/PATCH /api/mcp/config}, {@code POST /api/mcp/config/servers},
 * {@code PUT /api/mcp/config/server} and
 * {@code DELETE /api/mcp/config/servers/{name}}. Only the thin projection is stored;
 * secrets are never echoed (header values are masked).</p>
 */
final class DeerFlowMcpApi implements HttpHandler {
    private static final int MAX_BODY_BYTES = 128 * 1024;
    private static final Pattern SERVER_PATH = Pattern.compile("^/api/mcp/config/servers/([^/]+)$");

    private final ObjectMapper json;
    private final McpConfigStore store;

    DeerFlowMcpApi(ObjectMapper json, McpConfigStore store) {
        this.json = json;
        this.store = store;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!isLoopbackPeer(exchange) && !io.haifa.agent.superharness.server.auth.LocalAuthService.sessionAuthActive()) {
                send(exchange, 403, Map.of("detail", "Local profile accepts loopback clients only"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/api/mcp/config")) {
                if (method.equals("GET")) {
                    send(exchange, 200, store.maskedSnapshot());
                } else if (method.equals("PUT")) {
                    send(exchange, 200, store.replaceAll(requireServers(readObject(exchange))));
                } else if (method.equals("PATCH")) {
                    patch(exchange);
                } else {
                    methodNotAllowed(exchange, "GET, PUT, PATCH");
                }
                return;
            }
            if (path.equals("/api/mcp/config/servers") && method.equals("POST")) {
                send(exchange, 200, store.addServers(requireServers(readObject(exchange))));
                return;
            }
            if (path.equals("/api/mcp/config/server") && method.equals("PUT")) {
                putServer(exchange);
                return;
            }
            if (path.equals("/api/mcp/config/cache/reset") && method.equals("POST")) {
                send(exchange, 200, new McpCacheResetResponse(true, "MCP tools cache reset"));
                return;
            }
            Matcher server = SERVER_PATH.matcher(path);
            if (server.matches() && method.equals("DELETE")) {
                send(exchange, 200, store.deleteServer(java.net.URLDecoder.decode(
                        server.group(1), java.nio.charset.StandardCharsets.UTF_8)));
                return;
            }
            send(exchange, 404, Map.of("detail", "Not Found"));
        } catch (McpConfigStore.McpStoreException invalid) {
            send(exchange, invalid.status(), Map.of("detail", invalid.getMessage()));
        } catch (InvalidMcpRequest invalid) {
            send(exchange, invalid.status, Map.of("detail", invalid.getMessage()));
        } catch (Exception exception) {
            send(exchange, 500, Map.of("detail", "MCP configuration operation failed"));
        }
    }

    private void patch(HttpExchange exchange) throws IOException {
        ObjectNode body = readObject(exchange);
        String name = requireText(body, "server_name");
        JsonNode enabled = body.get("enabled");
        if (enabled == null || !enabled.isBoolean()) {
            throw new InvalidMcpRequest(422, "enabled must be boolean");
        }
        send(exchange, 200, store.setEnabled(name, enabled.asBoolean()));
    }

    private void putServer(HttpExchange exchange) throws IOException {
        ObjectNode body = readObject(exchange);
        String name = requireText(body, "server_name");
        JsonNode server = body.get("server");
        if (!(server instanceof ObjectNode)) {
            throw new InvalidMcpRequest(422, "server must be an object");
        }
        send(exchange, 200, store.replaceServer(name, server));
    }

    private static JsonNode requireServers(ObjectNode body) {
        JsonNode servers = body.get("mcp_servers");
        if (servers == null) {
            throw new InvalidMcpRequest(422, "mcp_servers is required");
        }
        return servers;
    }

    private ObjectNode readObject(HttpExchange exchange) throws IOException {
        byte[] payload = readBounded(exchange.getRequestBody());
        JsonNode node;
        try {
            node = json.readTree(payload);
        } catch (Exception exception) {
            throw new InvalidMcpRequest(422, "Invalid JSON body");
        }
        if (!(node instanceof ObjectNode object)) {
            throw new InvalidMcpRequest(422, "JSON body must be an object");
        }
        return object;
    }

    private static String requireText(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new InvalidMcpRequest(422, field + " must be a non-empty string");
        }
        return value.asText();
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int read; (read = input.read(buffer)) >= 0; ) {
            total += read;
            if (total > MAX_BODY_BYTES) {
                throw new InvalidMcpRequest(413, "Request body exceeds 131072 bytes");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void methodNotAllowed(HttpExchange exchange, String allow) throws IOException {
        exchange.getResponseHeaders().set("Allow", allow);
        send(exchange, 405, Map.of("detail", "Method Not Allowed"));
    }

    private static boolean isLoopbackPeer(HttpExchange exchange) {
        return exchange.getRemoteAddress() != null
                && exchange.getRemoteAddress().getAddress() != null
                && exchange.getRemoteAddress().getAddress().isLoopbackAddress();
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, payload.length);
        try (var responseBody = exchange.getResponseBody()) {
            responseBody.write(payload);
        }
    }

    private static final class InvalidMcpRequest extends RuntimeException {
        private final int status;

        private InvalidMcpRequest(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}

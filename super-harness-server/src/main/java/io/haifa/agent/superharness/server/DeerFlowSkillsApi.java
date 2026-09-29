package io.haifa.agent.superharness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Frozen Skill list, enable and reload routes backed by trusted SDK sources. */
final class DeerFlowSkillsApi implements HttpHandler {
    private static final Pattern SKILL_PATH = Pattern.compile("^/api/skills/([a-z][a-z0-9-]{0,62})$");
    private static final int MAX_BODY_BYTES = 8 * 1024;

    private final ObjectMapper json;
    private final SkillConfigStore store;

    DeerFlowSkillsApi(ObjectMapper json, SkillConfigStore store) {
        this.json = json;
        this.store = store;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (exchange.getRemoteAddress() == null || exchange.getRemoteAddress().getAddress() == null
                || !exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
            send(exchange, 403, Map.of("detail", "Local profile accepts loopback clients only"));
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        try {
            if (path.equals("/api/skills") && method.equals("GET")) {
                send(exchange, 200, Map.of("skills", store.list()));
                return;
            }
            if (path.equals("/api/skills") && !method.equals("GET")) {
                methodNotAllowed(exchange, "GET");
                return;
            }
            if (path.equals("/api/skills/reload") && method.equals("POST")) {
                store.reload();
                send(exchange, 200, Map.of(
                        "success", true,
                        "scope", "process",
                        "message", "Skill catalog reloaded for subsequent Runs in this Gateway process."));
                return;
            }
            if (path.equals("/api/skills/reload")) {
                methodNotAllowed(exchange, "POST");
                return;
            }
            Matcher skill = SKILL_PATH.matcher(path);
            if (skill.matches() && method.equals("GET")) {
                var detail = store.detail(skill.group(1));
                if (detail.isEmpty()) {
                    send(exchange, 404, Map.of("detail", "Skill '" + skill.group(1) + "' not found"));
                } else {
                    send(exchange, 200, detail.orElseThrow());
                }
                return;
            }
            if (skill.matches() && method.equals("PUT")) {
                if (store.detail(skill.group(1)).isEmpty()) {
                    send(exchange, 404, Map.of("detail", "Skill '" + skill.group(1) + "' not found"));
                    return;
                }
                byte[] payload = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
                if (payload.length > MAX_BODY_BYTES) {
                    send(exchange, 413, Map.of("detail", "Skill update body is too large"));
                    return;
                }
                JsonNode body;
                try {
                    body = json.readTree(payload);
                } catch (JsonProcessingException invalid) {
                    send(exchange, 422, Map.of("detail", "Invalid JSON body"));
                    return;
                }
                if (body == null || !body.isObject() || !body.path("enabled").isBoolean()) {
                    send(exchange, 422, Map.of("detail", "enabled must be boolean"));
                    return;
                }
                store.setEnabled(skill.group(1), body.path("enabled").asBoolean());
                send(exchange, 200, store.detail(skill.group(1)).orElseThrow());
                return;
            }
            if (skill.matches()) {
                methodNotAllowed(exchange, "GET, PUT");
                return;
            }
            // General installation, archive upload and export remain outside V2 W15.
            send(exchange, 404, Map.of("detail", "Skill operation is unavailable"));
        } catch (IllegalArgumentException invalid) {
            send(exchange, 422, Map.of("detail", invalid.getMessage()));
        } catch (IOException | IllegalStateException invalid) {
            send(exchange, 409, Map.of("detail", "Trusted Skill configuration could not be applied"));
        }
    }

    private void methodNotAllowed(HttpExchange exchange, String allow) throws IOException {
        exchange.getResponseHeaders().set("Allow", allow);
        send(exchange, 405, Map.of("detail", "Method Not Allowed"));
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, payload.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(payload);
        }
    }
}

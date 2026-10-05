package io.haifa.agent.superharness.server.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.haifa.agent.superharness.server.api.dto.SkillDeleteResponse;
import io.haifa.agent.superharness.server.api.dto.SkillReloadResponse;
import io.haifa.agent.superharness.server.api.dto.SkillsListResponse;
import io.haifa.agent.superharness.server.config.SkillConfigStore;
import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Frozen Skill list, enable and reload routes backed by trusted SDK sources. */
public final class DeerFlowSkillsApi implements HttpHandler {
    private static final Pattern SKILL_PATH = Pattern.compile("^/api/skills/([a-z][a-z0-9-]{0,62})$");
    private static final int MAX_BODY_BYTES = 8 * 1024;

    private final ObjectMapper json;
    private final SkillConfigStore store;
    private final DeerFlowWorkspaceApi workspace;

    public DeerFlowSkillsApi(ObjectMapper json, SkillConfigStore store) {
        this(json, store, null);
    }

    DeerFlowSkillsApi(ObjectMapper json, SkillConfigStore store, DeerFlowWorkspaceApi workspace) {
        this.json = json;
        this.store = store;
        this.workspace = workspace;
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
            if (path.equals("/api/skills/install/upload") && method.equals("POST")) {
                if (workspace == null) throw new IllegalStateException("Skill upload workspace is unavailable");
                if (!workspace.tryAcquireUploadSlot()) { send(exchange, 429, Map.of("detail", "Upload slot is busy")); return; }
                try {
                    var file = workspace.parseSkillArchive(exchange);
                    if (!file.filename().toLowerCase(java.util.Locale.ROOT).endsWith(".skill")) {
                        throw new IllegalArgumentException("Skill archive filename must end with .skill");
                    }
                    installed(exchange, store.install(file.content(), null));
                } finally { workspace.releaseUploadSlot(); }
                return;
            }
            if (path.equals("/api/skills/install") && method.equals("POST")) {
                JsonNode body = body(exchange, MAX_BODY_BYTES);
                String thread = requiredText(body, "thread_id");
                String archivePath = requiredText(body, "path");
                if (workspace == null) throw new IllegalStateException("Skill install workspace is unavailable");
                installed(exchange, store.install(workspace.readThreadSkillArchive(thread, archivePath), thread));
                return;
            }
            if (path.equals("/api/skills/install") || path.equals("/api/skills/install/upload")) {
                methodNotAllowed(exchange, "POST"); return;
            }
            if (path.equals("/api/skills/custom")) {
                if (!method.equals("GET")) { methodNotAllowed(exchange, "GET"); return; }
                send(exchange, 200, new SkillsListResponse<>(store.list().stream().filter(row -> Boolean.TRUE.equals(row.get("editable"))).toList()));
                return;
            }
            Matcher custom = Pattern.compile("^/api/skills/custom/([a-z][a-z0-9-]{0,62})(?:/(history|rollback))?$").matcher(path);
            if (custom.matches()) {
                String name = custom.group(1), operation = custom.group(2);
                if ("history".equals(operation) && method.equals("GET")) send(exchange, 200, Map.of("history", store.history(name)));
                else if ("rollback".equals(operation) && method.equals("POST")) {
                    JsonNode body = body(exchange, MAX_BODY_BYTES);
                    if (body.has("history_index") && !body.get("history_index").isIntegralNumber()) throw new RequestFailure(422, "history_index must be an integer");
                    store.rollback(name, body.path("history_index").asInt(-1)); send(exchange, 200, store.customDetail(name));
                } else if (operation == null && method.equals("GET")) send(exchange, 200, store.customDetail(name));
                else if (operation == null && method.equals("PUT")) {
                    JsonNode body = body(exchange, 1024 * 1024);
                    store.manage("human_edit", name, requiredText(body, "content"), null, null, null, null, "human", null);
                    send(exchange, 200, store.customDetail(name));
                } else if (operation == null && method.equals("DELETE")) {
                    store.manage("delete", name, null, null, null, null, null, "human", null);
                    send(exchange, 200, new SkillDeleteResponse(true, "Custom skill '" + name + "' deleted"));
                } else methodNotAllowed(exchange, "history".equals(operation) ? "GET" : "rollback".equals(operation) ? "POST" : "GET, PUT, DELETE");
                return;
            }
            Matcher export = Pattern.compile("^/api/skills/custom/([a-z][a-z0-9-]{0,62})/(export-manifest|export)$").matcher(path);
            if (export.matches()) {
                if (!method.equals("GET")) { methodNotAllowed(exchange, "GET"); return; }
                String name = export.group(1);
                if (export.group(2).equals("export-manifest")) send(exchange, 200, store.exportManifest(name));
                else {
                    String revision = null;
                    String query = exchange.getRequestURI().getRawQuery();
                    if (query != null) for (String field : query.split("&")) {
                        String[] pair = field.split("=", 2);
                        if (pair[0].equals("expected_revision") && pair.length == 2) revision = java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8);
                    }
                    byte[] archive = store.exportArchive(name, revision);
                    exchange.getResponseHeaders().set("Content-Type", "application/zip");
                    exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name + ".skill\"");
                    exchange.getResponseHeaders().set("Cache-Control", "no-store");
                    exchange.sendResponseHeaders(200, archive.length);
                    try (var output = exchange.getResponseBody()) { output.write(archive); }
                }
                return;
            }
            if (path.equals("/api/skills") && method.equals("GET")) {
                send(exchange, 200, new SkillsListResponse<>(store.list()));
                return;
            }
            if (path.equals("/api/skills") && !method.equals("GET")) {
                methodNotAllowed(exchange, "GET");
                return;
            }
            if (path.equals("/api/skills/reload") && method.equals("POST")) {
                store.reload();
                send(exchange, 200, new SkillReloadResponse(
                        true,
                        "process",
                        "Skill catalog reloaded for subsequent Runs in this Gateway process."));
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
            send(exchange, 404, Map.of("detail", "Skill operation is unavailable"));
        } catch (DeerFlowWorkspaceApi.HttpFailure invalid) {
            send(exchange, invalid.status(), Map.of("detail", invalid.getMessage()));
        } catch (RequestFailure invalid) {
            send(exchange, invalid.status, Map.of("detail", invalid.getMessage()));
        } catch (java.io.FileNotFoundException missing) {
            send(exchange, 404, Map.of("detail", missing.getMessage()));
        } catch (java.nio.file.FileAlreadyExistsException duplicate) {
            send(exchange, 409, Map.of("detail", duplicate.getMessage()));
        } catch (IllegalArgumentException invalid) {
            send(exchange, path.endsWith("/export") ? 422 : path.contains("install") || path.contains("custom/") ? 400 : 422,
                    Map.of("detail", invalid.getMessage() == null ? "Invalid Skill operation" : invalid.getMessage()));
        } catch (IOException | IllegalStateException invalid) {
            send(exchange, 409, Map.of("detail", "skill_changed".equals(invalid.getMessage())
                    ? Map.of("code", "skill_changed", "message", "Skill changed; request a new export manifest")
                    : "Skill configuration could not be applied"));
        }
    }

    private void installed(HttpExchange exchange, String name) throws IOException {
        send(exchange, 200, Map.of("success", true, "skill_name", name, "message", "Skill installed successfully", "warnings", java.util.List.of()));
    }

    private JsonNode body(HttpExchange exchange, int limit) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(limit + 1);
        if (bytes.length > limit) throw new RequestFailure(413, "Skill request body is too large");
        JsonNode body;
        try { body = json.readTree(bytes); }
        catch (JsonProcessingException invalid) { throw new RequestFailure(422, "Invalid JSON body"); }
        if (body == null || !body.isObject()) throw new RequestFailure(422, "JSON object is required");
        return body;
    }

    private static String requiredText(JsonNode body, String field) {
        if (!body.path(field).isTextual()) throw new RequestFailure(422, field + " must be a string");
        return body.path(field).asText();
    }

    private static final class RequestFailure extends RuntimeException {
        private final int status;
        private RequestFailure(int status, String message) { super(message); this.status = status; }
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

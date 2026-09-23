package io.haifa.agent.superharness.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.ProjectProfile;
import io.haifa.agent.superharness.workspace.WorkspaceException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Frozen DeerFlow Project transport backed by the persistent Project shelf authority. */
final class DeerFlowProjectApi implements HttpHandler {
    private static final String OWNER_ID = DeerFlowBootstrapApi.DEFAULT_USER_ID;
    private static final int MAX_JSON_BYTES = 32 * 1024;
    private static final Pattern PROJECT = Pattern.compile("^/api/projects/([^/]+)$");
    private static final Pattern THREADS = Pattern.compile("^/api/projects/([^/]+)/threads$");

    private final ObjectMapper json;
    private final PersistentProjectWorkspaceService projects;
    private final DeerFlowThreadRunApi threads;

    DeerFlowProjectApi(
            ObjectMapper json, PersistentProjectWorkspaceService projects, DeerFlowThreadRunApi threads) {
        this.json = java.util.Objects.requireNonNull(json, "json");
        this.projects = java.util.Objects.requireNonNull(projects, "projects");
        this.threads = java.util.Objects.requireNonNull(threads, "threads");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!isLoopbackPeer(exchange)) {
            sendJson(exchange, 403, Map.of("detail", "Local profile accepts loopback clients only"));
            return;
        }
        String path = exchange.getRequestURI().getPath();
        try {
            if (path.equals("/api/projects/config")) {
                if (!requireMethod(exchange, "GET")) {
                    return;
                }
                sendJson(exchange, 200, Map.of(
                        "instructions_max_bytes", PersistentProjectWorkspaceService.PROJECT_INSTRUCTIONS_MAX_BYTES,
                        "trash_retention_days", 30));
                return;
            }
            if (path.equals("/api/projects")) {
                if (exchange.getRequestMethod().equals("GET")) {
                    list(exchange);
                } else if (exchange.getRequestMethod().equals("POST")) {
                    create(exchange);
                } else {
                    methodNotAllowed(exchange, "GET, POST");
                }
                return;
            }
            Matcher match = THREADS.matcher(path);
            if (match.matches()) {
                if (!requireMethod(exchange, "GET")) {
                    return;
                }
                listThreads(exchange, match.group(1));
                return;
            }
            match = PROJECT.matcher(path);
            if (match.matches()) {
                if (exchange.getRequestMethod().equals("GET")) {
                    sendJson(exchange, 200, response(projects.getProject(OWNER_ID, match.group(1))));
                } else if (exchange.getRequestMethod().equals("PATCH")) {
                    patch(exchange, match.group(1));
                } else {
                    methodNotAllowed(exchange, "GET, PATCH");
                }
                return;
            }
            sendJson(exchange, 404, Map.of("detail", "Not Found"));
        } catch (HttpFailure failure) {
            sendJson(exchange, failure.status, Map.of("detail", failure.getMessage()));
        } catch (WorkspaceException failure) {
            int status = switch (failure.error()) {
                case PROJECT_NOT_FOUND, OWNER_MISMATCH, INVALID_IDENTITY -> 404;
                case INVALID_PATH, FILE_TOO_LARGE, READ_OUT_OF_BOUNDS -> 422;
                default -> 500;
            };
            sendJson(exchange, status, Map.of("detail", status == 404 ? "Project not found" : failure.getMessage()));
        } catch (IllegalArgumentException failure) {
            sendJson(exchange, 422, Map.of("detail", failure.getMessage()));
        }
    }

    private void list(HttpExchange exchange) throws IOException {
        Optional<String> status = queryValue(exchange, "status");
        if (status.isPresent() && !status.orElseThrow().equals("active")
                && !status.orElseThrow().equals("archived")) {
            throw new HttpFailure(422, "status must be active or archived");
        }
        List<Map<String, Object>> values = projects.listProjects(OWNER_ID, status).stream()
                .map(this::response)
                .toList();
        sendJson(exchange, 200, Map.of("projects", values));
    }

    private void create(HttpExchange exchange) throws IOException {
        JsonNode body = readObject(exchange);
        String name = requiredText(body, "name");
        String instructions = body.has("instructions") ? requiredText(body, "instructions") : "";
        Map<String, String> presentation = body.has("presentation")
                ? presentation(body.get("presentation"), true)
                : Map.of();
        ProjectProfile created = projects.createProject(OWNER_ID, name, instructions, presentation);
        sendJson(exchange, 201, response(created));
    }

    private void patch(HttpExchange exchange, String projectId) throws IOException {
        JsonNode body = readObject(exchange);
        Optional<String> name = nullableText(body, "name");
        Optional<String> instructions = nullableText(body, "instructions");
        Optional<Map<String, String>> presentation = body.has("presentation") && !body.get("presentation").isNull()
                ? Optional.of(presentation(body.get("presentation"), true))
                : Optional.empty();
        ProjectProfile updated = projects.patchProject(OWNER_ID, projectId, name, instructions, presentation);
        sendJson(exchange, 200, response(updated));
    }

    private void listThreads(HttpExchange exchange, String projectId) throws IOException {
        projects.getProject(OWNER_ID, projectId);
        int limit = queryInt(exchange, "limit", 100, 1, 1000);
        int offset = queryInt(exchange, "offset", 0, 0, Integer.MAX_VALUE);
        sendJson(exchange, 200, threads.listProjectThreads(projectId, limit, offset));
    }

    private Map<String, Object> response(ProjectProfile project) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", project.id());
        result.put("name", project.name());
        result.put("instructions", project.instructions());
        result.put("presentation", project.presentation());
        result.put("status", project.status());
        result.put("created_at", project.createdAt().toString());
        result.put("updated_at", project.updatedAt().toString());
        return result;
    }

    private JsonNode readObject(HttpExchange exchange) throws IOException {
        long declared = parseContentLength(exchange);
        if (declared > MAX_JSON_BYTES) {
            throw new HttpFailure(413, "JSON body exceeds the size limit");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        for (int count; (count = exchange.getRequestBody().read(buffer)) >= 0;) {
            total += count;
            if (total > MAX_JSON_BYTES) {
                throw new HttpFailure(413, "JSON body exceeds the size limit");
            }
            output.write(buffer, 0, count);
        }
        if (declared >= 0 && total != declared) {
            throw new HttpFailure(422, "JSON body length does not match Content-Length");
        }
        try {
            JsonNode body = json.readTree(output.toByteArray());
            if (body == null || !body.isObject()) {
                throw new HttpFailure(422, "JSON body must be an object");
            }
            return body;
        } catch (JsonProcessingException malformed) {
            throw new HttpFailure(422, "Invalid JSON body");
        }
    }

    private static long parseContentLength(HttpExchange exchange) {
        String value = exchange.getRequestHeaders().getFirst("Content-Length");
        if (value == null) {
            return -1;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new HttpFailure(400, "Invalid Content-Length");
        }
    }

    private static String requiredText(JsonNode body, String field) {
        return nullableText(body, field).orElseThrow(() -> new HttpFailure(422, field + " is required"));
    }

    private static Optional<String> nullableText(JsonNode body, String field) {
        if (!body.has(field) || body.get(field).isNull()) {
            return Optional.empty();
        }
        if (!body.get(field).isTextual()) {
            throw new HttpFailure(422, field + " must be a string");
        }
        return Optional.of(body.get(field).asText());
    }

    private static Map<String, String> presentation(JsonNode value, boolean required) {
        if (value == null || value.isNull()) {
            if (required) {
                throw new HttpFailure(422, "presentation must be an object");
            }
            return Map.of();
        }
        if (!value.isObject()) {
            throw new HttpFailure(422, "presentation must be an object");
        }
        Map<String, String> result = new LinkedHashMap<>();
        value.properties().forEach(entry -> {
            if ((!entry.getKey().equals("icon") && !entry.getKey().equals("color"))
                    || !entry.getValue().isTextual()) {
                throw new HttpFailure(422, "presentation only supports string icon and color");
            }
            result.put(entry.getKey(), entry.getValue().asText());
        });
        return result;
    }

    private static int queryInt(HttpExchange exchange, String name, int fallback, int minimum, int maximum) {
        Optional<String> value = queryValue(exchange, name);
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.orElseThrow());
            if (parsed < minimum || parsed > maximum) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new HttpFailure(422, "Invalid project thread pagination");
        }
    }

    private static Optional<String> queryValue(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(name)) {
                return Optional.of(parts.length == 2 ? parts[1] : "");
            }
        }
        return Optional.empty();
    }

    private boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (!exchange.getRequestMethod().equals(method)) {
            methodNotAllowed(exchange, method);
            return false;
        }
        return true;
    }

    private void methodNotAllowed(HttpExchange exchange, String allow) throws IOException {
        exchange.getResponseHeaders().set("Allow", allow);
        sendJson(exchange, 405, Map.of("detail", "Method Not Allowed"));
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    private static boolean isLoopbackPeer(HttpExchange exchange) {
        return exchange.getRemoteAddress() != null
                && exchange.getRemoteAddress().getAddress() != null
                && exchange.getRemoteAddress().getAddress().isLoopbackAddress();
    }

    private static class HttpFailure extends RuntimeException {
        private final int status;

        private HttpFailure(int status, String message) {
            super(message);
            this.status = status;
        }
    }

}

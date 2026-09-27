package io.haifa.agent.superharness.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.ProjectDocument;
import io.haifa.agent.superharness.workspace.RestoreDocumentResult;
import io.haifa.agent.superharness.workspace.TrashDocument;
import io.haifa.agent.superharness.workspace.TrashDocumentList;
import io.haifa.agent.superharness.workspace.WorkspaceException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Frozen DeerFlow Trash transport backed by the persistent Project shelf authority. */
final class DeerFlowTrashApi implements HttpHandler {
    private static final String OWNER_ID = DeerFlowBootstrapApi.DEFAULT_USER_ID;
    private static final int MAX_JSON_BYTES = 32 * 1024;
    private static final Pattern TRASH_DOCUMENTS = Pattern.compile("^/api/trash/documents$");
    private static final Pattern TRASH_RESTORE = Pattern.compile("^/api/trash/documents/([^/]+)/restore$");
    private static final Pattern TRASH_PURGE_DOCUMENT = Pattern.compile("^/api/trash/documents/([^/]+)/purge$");
    private static final Pattern TRASH_PURGE_ALL = Pattern.compile("^/api/trash/purge$");

    private final ObjectMapper json;
    private final PersistentProjectWorkspaceService projects;

    DeerFlowTrashApi(ObjectMapper json, PersistentProjectWorkspaceService projects) {
        this.json = Objects.requireNonNull(json, "json");
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!isLoopbackPeer(exchange)) {
            sendJson(exchange, 403, Map.of("detail", "Local profile accepts loopback clients only"));
            return;
        }
        String path = exchange.getRequestURI().getPath();
        try {
            Matcher match = TRASH_DOCUMENTS.matcher(path);
            if (match.matches()) {
                if (!requireMethod(exchange, "GET")) {
                    return;
                }
                listTrash(exchange);
                return;
            }
            match = TRASH_RESTORE.matcher(path);
            if (match.matches()) {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                restoreDocument(exchange, match.group(1));
                return;
            }
            match = TRASH_PURGE_DOCUMENT.matcher(path);
            if (match.matches()) {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                purgeDocument(exchange, match.group(1));
                return;
            }
            match = TRASH_PURGE_ALL.matcher(path);
            if (match.matches()) {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                emptyTrash(exchange);
                return;
            }
            sendJson(exchange, 404, Map.of("detail", "Not Found"));
        } catch (HttpFailure failure) {
            sendJson(exchange, failure.status, Map.of("detail", failure.getMessage()));
        } catch (WorkspaceException failure) {
            int status = switch (failure.error()) {
                case PROJECT_NOT_FOUND, OWNER_MISMATCH, INVALID_IDENTITY, DOCUMENT_NOT_FOUND -> 404;
                case CONTENT_INTEGRITY -> 409;
                case INVALID_PATH -> 422;
                default -> 500;
            };
            String detail = switch (failure.error()) {
                case PROJECT_NOT_FOUND, OWNER_MISMATCH, INVALID_IDENTITY, DOCUMENT_NOT_FOUND -> "Trash document not found";
                case CONTENT_INTEGRITY -> "content_missing";
                default -> failure.getMessage();
            };
            sendJson(exchange, status, Map.of("detail", detail));
        } catch (IllegalArgumentException failure) {
            sendJson(exchange, 422, Map.of("detail", failure.getMessage()));
        }
    }

    private void listTrash(HttpExchange exchange) throws IOException {
        int limit = queryInt(exchange, "limit", 100, 1, 1000);
        int offset = queryInt(exchange, "offset", 0, 0, Integer.MAX_VALUE);
        TrashDocumentList list = projects.listTrashDocuments(OWNER_ID, limit, offset);
        sendJson(exchange, 200, Map.of(
                "documents", list.documents().stream().map(this::trashResponse).toList(),
                "total", list.total(),
                "limit", limit,
                "offset", offset));
    }

    private void restoreDocument(HttpExchange exchange, String documentId) throws IOException {
        Optional<String> targetProjectId = Optional.empty();
        if (exchange.getRequestHeaders().getFirst("Content-Length") != null
                && parseContentLength(exchange) > 0) {
            JsonNode body = readObject(exchange);
            targetProjectId = nullableText(body, "project_id");
        }
        RestoreDocumentResult result = projects.restoreTrashDocument(OWNER_ID, documentId, targetProjectId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("outcome", result.outcome());
        response.put("document", documentResponse(result.document()));
        sendJson(exchange, 200, response);
    }

    private void purgeDocument(HttpExchange exchange, String documentId) throws IOException {
        projects.purgeTrashDocument(OWNER_ID, documentId);
        sendNoContent(exchange);
    }

    private void emptyTrash(HttpExchange exchange) throws IOException {
        int purged = projects.emptyTrash(OWNER_ID);
        sendJson(exchange, 200, Map.of("purged", purged));
    }

    private Map<String, Object> trashResponse(TrashDocument doc) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", doc.id());
        map.put("name", doc.name());
        map.put("size_bytes", doc.sizeBytes());
        map.put("sha256", doc.sha256());
        map.put("source_thread_id", doc.sourceThreadId().orElse(null));
        map.put("source_kind", doc.sourceKind() == null ? null : doc.sourceKind().name().toLowerCase(Locale.ROOT));
        map.put("source_name", doc.sourceName().orElse(null));
        map.put("created_at", doc.createdAt().toString());
        map.put("updated_at", doc.updatedAt().toString());
        map.put("trashed_at", doc.trashedAt().toString());
        map.put("trash_origin", doc.trashOrigin().map(origin -> Map.of(
                "project_id", origin.projectId(),
                "project_name", origin.projectName()
        )).orElse(null));
        return map;
    }

    private Map<String, Object> documentResponse(ProjectDocument doc) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", doc.id());
        map.put("name", doc.name());
        map.put("size_bytes", doc.sizeBytes());
        map.put("sha256", doc.sha256());
        map.put("content_missing", false);
        map.put("source_thread_id", doc.sourceThreadId().orElse(null));
        map.put("source_kind", doc.sourceKind() == null ? null : doc.sourceKind().name().toLowerCase(Locale.ROOT));
        map.put("source_name", doc.sourceName().orElse(null));
        map.put("created_at", doc.createdAt().toString());
        map.put("updated_at", doc.updatedAt().toString());
        return map;
    }

    private void sendNoContent(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
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

    private static boolean isLoopbackPeer(HttpExchange exchange) {
        return exchange.getRemoteAddress() != null
                && exchange.getRemoteAddress().getAddress() != null
                && exchange.getRemoteAddress().getAddress().isLoopbackAddress();
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
            throw new HttpFailure(422, "Invalid pagination parameter");
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

    private static Optional<String> nullableText(JsonNode body, String field) {
        if (!body.has(field) || body.get(field).isNull()) {
            return Optional.empty();
        }
        if (!body.get(field).isTextual()) {
            throw new HttpFailure(422, field + " must be a string");
        }
        return Optional.of(body.get(field).asText());
    }

    private static class HttpFailure extends RuntimeException {
        private final int status;

        private HttpFailure(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}

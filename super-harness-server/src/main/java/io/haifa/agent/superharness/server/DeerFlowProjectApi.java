package io.haifa.agent.superharness.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.ProjectDocument;
import io.haifa.agent.superharness.workspace.ProjectDocumentContent;
import io.haifa.agent.superharness.workspace.ProjectDocumentReceipt;
import io.haifa.agent.superharness.workspace.ProjectProfile;
import io.haifa.agent.superharness.workspace.WorkspaceError;
import io.haifa.agent.superharness.workspace.WorkspaceException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Frozen DeerFlow Project transport backed by the persistent Project shelf authority. */
final class DeerFlowProjectApi implements HttpHandler {
    private static final String OWNER_ID = DeerFlowBootstrapApi.DEFAULT_USER_ID;
    private static final int MAX_JSON_BYTES = 32 * 1024;
    private static final Pattern PROJECT = Pattern.compile("^/api/projects/([^/]+)$");
    private static final Pattern PROJECT_ARCHIVE = Pattern.compile("^/api/projects/([^/]+)/archive$");
    private static final Pattern PROJECT_RESTORE = Pattern.compile("^/api/projects/([^/]+)/restore$");
    private static final Pattern THREADS = Pattern.compile("^/api/projects/([^/]+)/threads$");
    private static final Pattern DOCUMENTS = Pattern.compile("^/api/projects/([^/]+)/documents$");
    private static final Pattern DOCUMENT = Pattern.compile("^/api/projects/([^/]+)/documents/([^/]+)$");
    private static final Pattern DOCUMENT_CONTENT =
            Pattern.compile("^/api/projects/([^/]+)/documents/([^/]+)/content$");
    private static final Pattern DOCUMENT_ATTACH = Pattern.compile(
            "^/api/projects/([^/]+)/documents/([^/]+)/attach-to-thread/([^/]+)$");
    private static final Pattern BYTE_RANGE = Pattern.compile("^bytes=(\\d*)-(\\d*)$");

    private final ObjectMapper json;
    private final PersistentProjectWorkspaceService projects;
    private final DeerFlowThreadRunApi threads;
    private final DeerFlowWorkspaceApi workspace;

    DeerFlowProjectApi(
            ObjectMapper json,
            PersistentProjectWorkspaceService projects,
            DeerFlowThreadRunApi threads,
            DeerFlowWorkspaceApi workspace) {
        this.json = java.util.Objects.requireNonNull(json, "json");
        this.projects = java.util.Objects.requireNonNull(projects, "projects");
        this.threads = java.util.Objects.requireNonNull(threads, "threads");
        this.workspace = java.util.Objects.requireNonNull(workspace, "workspace");
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
            Matcher match = PROJECT_ARCHIVE.matcher(path);
            if (match.matches()) {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                sendJson(exchange, 200, response(projects.archiveProject(OWNER_ID, match.group(1))));
                return;
            }
            match = PROJECT_RESTORE.matcher(path);
            if (match.matches()) {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                sendJson(exchange, 200, response(projects.restoreProject(OWNER_ID, match.group(1))));
                return;
            }
            match = THREADS.matcher(path);
            if (match.matches()) {
                if (!requireMethod(exchange, "GET")) {
                    return;
                }
                listThreads(exchange, match.group(1));
                return;
            }
            match = DOCUMENT_CONTENT.matcher(path);
            if (match.matches()) {
                if (!requireMethod(exchange, "GET")) {
                    return;
                }
                readDocument(exchange, match.group(1), match.group(2));
                return;
            }
            match = DOCUMENT_ATTACH.matcher(path);
            if (match.matches()) {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                attachDocument(exchange, match.group(1), match.group(2), match.group(3));
                return;
            }
            match = DOCUMENT.matcher(path);
            if (match.matches()) {
                if (!requireMethod(exchange, "DELETE")) {
                    return;
                }
                projects.trashProjectDocument(OWNER_ID, match.group(1), match.group(2));
                sendNoContent(exchange);
                return;
            }
            match = DOCUMENTS.matcher(path);
            if (match.matches()) {
                if (exchange.getRequestMethod().equals("GET")) {
                    listDocuments(exchange, match.group(1));
                } else if (exchange.getRequestMethod().equals("POST")) {
                    uploadDocument(exchange, match.group(1));
                } else {
                    methodNotAllowed(exchange, "GET, POST");
                }
                return;
            }
            match = PROJECT.matcher(path);
            if (match.matches()) {
                if (exchange.getRequestMethod().equals("GET")) {
                    sendJson(exchange, 200, response(projects.getProject(OWNER_ID, match.group(1))));
                } else if (exchange.getRequestMethod().equals("PATCH")) {
                    patch(exchange, match.group(1));
                } else if (exchange.getRequestMethod().equals("DELETE")) {
                    deleteProject(exchange, match.group(1));
                } else {
                    methodNotAllowed(exchange, "GET, PATCH, DELETE");
                }
                return;
            }
            sendJson(exchange, 404, Map.of("detail", "Not Found"));
        } catch (HttpFailure failure) {
            if (failure.contentRange != null) {
                exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                exchange.getResponseHeaders().set("Content-Range", failure.contentRange);
            }
            sendJson(exchange, failure.status, Map.of("detail", failure.getMessage()));
        } catch (DeerFlowWorkspaceApi.HttpFailure failure) {
            sendJson(exchange, failure.status(), Map.of("detail", failure.getMessage()));
        } catch (WorkspaceException failure) {
            int status = switch (failure.error()) {
                case PROJECT_NOT_FOUND, OWNER_MISMATCH, INVALID_IDENTITY, DOCUMENT_NOT_FOUND -> 404;
                case FILE_TOO_LARGE -> 413;
                case READ_OUT_OF_BOUNDS -> 416;
                case CONTENT_INTEGRITY, FILE_NOT_FOUND -> 409;
                case INVALID_PATH -> 422;
                default -> 500;
            };
            String detail = switch (failure.error()) {
                case PROJECT_NOT_FOUND, OWNER_MISMATCH, INVALID_IDENTITY -> "Project not found";
                case DOCUMENT_NOT_FOUND -> "Project document not found";
                case CONTENT_INTEGRITY -> "content_missing";
                default -> failure.getMessage();
            };
            sendJson(exchange, status, Map.of("detail", detail));
        } catch (IllegalArgumentException failure) {
            sendJson(exchange, 422, Map.of("detail", failure.getMessage()));
        }
    }

    private void deleteProject(HttpExchange exchange, String projectId) throws IOException {
        projects.deleteProject(OWNER_ID, projectId, () -> threads.unbindProject(projectId));
        sendNoContent(exchange);
    }

    private void sendNoContent(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
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

    private void listDocuments(HttpExchange exchange, String projectId) throws IOException {
        int limit = queryInt(exchange, "limit", 100, 1, 1000);
        int offset = queryInt(exchange, "offset", 0, 0, Integer.MAX_VALUE);
        List<ProjectDocument> all = projects.listProjectDocuments(OWNER_ID, projectId);
        int from = Math.min(offset, all.size());
        int to = Math.min(all.size(), Math.addExact(from, Math.min(limit, all.size() - from)));
        sendJson(exchange, 200, Map.of(
                "documents", all.subList(from, to).stream().map(this::documentResponse).toList(),
                "total", all.size(),
                "limit", limit,
                "offset", offset));
    }

    private void uploadDocument(HttpExchange exchange, String projectId) throws IOException {
        projects.getProject(OWNER_ID, projectId);
        if (!workspace.tryAcquireUploadSlot()) {
            throw new HttpFailure(429, "Another upload request is already using the bounded local slot");
        }
        try {
            DeerFlowWorkspaceApi.MultipartFile file = workspace.parseSingleFile(exchange, "file");
            ProjectDocumentReceipt receipt;
            try {
                receipt = projects.uploadProjectDocument(OWNER_ID, projectId, file.filename(), file.content());
            } catch (WorkspaceException failure) {
                // Frozen shelf rejects an unusable upload/display name with 400 at the HTTP boundary.
                // Every other Workspace failure keeps its existing status and authority mapping.
                if (failure.error() == WorkspaceError.INVALID_PATH) {
                    throw new HttpFailure(400, failure.getMessage());
                }
                throw failure;
            } catch (IllegalArgumentException failure) {
                // Only the frozen zero-byte rejection is a 400; every other
                // IllegalArgumentException keeps the pre-existing global mapping.
                if ("project document content must not be empty".equals(failure.getMessage())) {
                    throw new HttpFailure(400, failure.getMessage());
                }
                throw failure;
            }
            sendJson(exchange, receipt.deduplicated() ? 200 : 201, Map.of(
                    "document", documentResponse(receipt.document()),
                    "deduplicated", receipt.deduplicated()));
        } finally {
            workspace.releaseUploadSlot();
        }
    }

    private void readDocument(HttpExchange exchange, String projectId, String documentId) throws IOException {
        ProjectDocument document = requireDocument(projectId, documentId);
        ByteSelection selection = selectRange(exchange.getRequestHeaders().getFirst("Range"), document.sizeBytes());
        ProjectDocumentContent content = projects.readProjectDocument(
                OWNER_ID, projectId, documentId, selection.start(), Math.max(1, selection.length()));
        byte[] bytes = selection.length() == 0 ? new byte[0] : content.bytes();
        String mediaType = mediaType(document.name(), bytes);
        boolean download = queryValue(exchange, "download").map("true"::equalsIgnoreCase).orElse(false);
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        exchange.getResponseHeaders().set("ETag", "\"" + document.sha256() + "\"");
        exchange.getResponseHeaders().set("Content-Type", mediaType);
        exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
        exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set(
                "Content-Disposition",
                (download || isActiveContent(mediaType) ? "attachment" : "inline")
                        + "; filename*=UTF-8''" + encodePathSegment(document.name()));
        if (selection.partial()) {
            exchange.getResponseHeaders().set(
                    "Content-Range",
                    "bytes " + selection.start() + "-" + selection.end() + "/" + document.sizeBytes());
        }
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(selection.status(), -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(selection.status(), bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void attachDocument(HttpExchange exchange, String projectId, String documentId, String threadId)
            throws IOException {
        if (!threads.threadBelongsToProject(threadId, projectId)) {
            throw new HttpFailure(404, "Project thread not found");
        }
        ProjectDocument document = requireDocument(projectId, documentId);
        ProjectDocumentContent content = projects.readProjectDocument(
                OWNER_ID, projectId, documentId, 0, Math.max(1, Math.toIntExact(document.sizeBytes())));
        sendJson(exchange, 200, workspace.attachProjectDocument(
                threadId, document.name(), content.bytes(), mediaType(document.name(), content.bytes())));
    }

    private ProjectDocument requireDocument(String projectId, String documentId) {
        return projects.listProjectDocuments(OWNER_ID, projectId).stream()
                .filter(document -> document.id().equals(documentId))
                .findFirst()
                .orElseThrow(() -> new HttpFailure(404, "Document not found"));
    }

    private Map<String, Object> documentResponse(ProjectDocument document) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", document.id());
        result.put("name", document.name());
        result.put("size_bytes", document.sizeBytes());
        result.put("sha256", document.sha256());
        result.put("content_missing", false);
        result.put("source_thread_id", document.sourceThreadId().orElse(null));
        result.put("source_kind", document.sourceKind() == null ? null : document.sourceKind().name().toLowerCase(Locale.ROOT));
        result.put("source_name", document.sourceName().orElse(null));
        result.put("created_at", document.createdAt().toString());
        result.put("updated_at", document.updatedAt().toString());
        return result;
    }

    private static ByteSelection selectRange(String header, long total) {
        if (header == null) {
            return new ByteSelection(0, Math.toIntExact(total), total == 0 ? -1 : total - 1, 200, false);
        }
        Matcher match = BYTE_RANGE.matcher(header.trim());
        if (!match.matches() || (match.group(1).isEmpty() && match.group(2).isEmpty()) || total == 0) {
            throw unsatisfied(total);
        }
        try {
            long start;
            long end;
            if (match.group(1).isEmpty()) {
                long suffix = Long.parseLong(match.group(2));
                if (suffix < 1) {
                    throw unsatisfied(total);
                }
                start = Math.max(0, total - suffix);
                end = total - 1;
            } else {
                start = Long.parseLong(match.group(1));
                if (start >= total) {
                    throw unsatisfied(total);
                }
                end = match.group(2).isEmpty()
                        ? total - 1
                        : Math.min(Long.parseLong(match.group(2)), total - 1);
                if (end < start) {
                    throw unsatisfied(total);
                }
            }
            return new ByteSelection(start, Math.toIntExact(end - start + 1), end, 206, true);
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw unsatisfied(total);
        }
    }

    private static HttpFailure unsatisfied(long total) {
        return new HttpFailure(416, "Requested range is not satisfiable", "bytes */" + total);
    }

    private static String mediaType(String filename, byte[] content) {
        String guessed = URLConnection.guessContentTypeFromName(filename);
        if (guessed != null) {
            return guessed;
        }
        int sample = Math.min(content.length, 8192);
        for (int index = 0; index < sample; index++) {
            if (content[index] == 0) {
                return "application/octet-stream";
            }
        }
        return "text/plain";
    }

    private static String encodePathSegment(String value) {
        StringBuilder result = new StringBuilder();
        for (byte current : value.getBytes(StandardCharsets.UTF_8)) {
            int character = current & 0xff;
            if ((character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9') || character == '-' || character == '_'
                    || character == '.' || character == '~') {
                result.append((char) character);
            } else {
                result.append('%').append(String.format("%02X", character));
            }
        }
        return result.toString();
    }

    private static boolean isActiveContent(String mediaType) {
        String value = mediaType.toLowerCase(Locale.ROOT);
        return value.equals("text/html") || value.equals("application/xhtml+xml")
                || value.equals("image/svg+xml") || value.equals("text/xml")
                || value.equals("application/xml") || value.equals("text/xsl") || value.endsWith("+xml");
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
        private final String contentRange;

        private HttpFailure(int status, String message) {
            this(status, message, null);
        }

        private HttpFailure(int status, String message, String contentRange) {
            super(message);
            this.status = status;
            this.contentRange = contentRange;
        }
    }

    private record ByteSelection(long start, int length, long end, int status, boolean partial) {}

}

package io.haifa.agent.superharness.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import io.haifa.agent.project.filesystem.FileEntry;
import io.haifa.agent.project.filesystem.FileListPage;
import io.haifa.agent.project.filesystem.FileListRequest;
import io.haifa.agent.project.filesystem.FileMetadata;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.filesystem.WorkspaceFileService;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.UploadReceipt;
import io.haifa.agent.superharness.workspace.WorkspaceBinding;
import io.haifa.agent.superharness.workspace.WorkspaceError;
import io.haifa.agent.superharness.workspace.WorkspaceException;
import io.haifa.agent.superharness.workspace.WorkspaceLimits;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Frozen DeerFlow upload and artifact transport over the owner-scoped Workspace service.
 *
 * <p>This adapter deliberately does not create another thread or owner registry. The local server
 * selects the owner and supplies the authoritative thread predicate.
 */
final class DeerFlowWorkspaceApi {
    private static String ownerId() {
        return io.haifa.agent.superharness.server.auth.AccessOwner.current();
    }
    private static final int MAX_FILES = 10;
    private static final long MAX_TOTAL_BYTES = 25L * 1024 * 1024;
    private static final long MAX_MULTIPART_OVERHEAD = 256L * 1024;
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int LIST_PAGE_SIZE = 256;
    private static final int MAX_LISTED_FILES = 4096;

    private static final Pattern UPLOADS = Pattern.compile("^/api/threads/([^/]+)/uploads$");
    private static final Pattern UPLOAD_LIMITS =
            Pattern.compile("^/api/threads/([^/]+)/uploads/limits$");
    private static final Pattern UPLOAD_LIST =
            Pattern.compile("^/api/threads/([^/]+)/uploads/list$");
    private static final Pattern ARTIFACT = Pattern.compile(
            "^/api/threads/([^/]+)/artifacts(/mnt/user-data/(?:uploads|workspace|outputs)/.+)$");
    private static final Pattern BOUNDARY = Pattern.compile(
            "(?:^|;)\\s*boundary=(?:\"([^\"]+)\"|([^;\\s]+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORM_NAME =
            Pattern.compile("(?:^|;)\\s*name=(?:\"([^\"]*)\"|([^;\\s]+))");
    private static final Pattern FORM_FILENAME =
            Pattern.compile("(?:^|;)\\s*filename=(?:\"([^\"]*)\"|([^;\\s]+))");
    private static final Pattern BYTE_RANGE = Pattern.compile("^bytes=(\\d*)-(\\d*)$");
    /** Frozen {@code deerflow.utils.thread_id.THREAD_ID_PATTERN}. */
    private static final Pattern THREAD_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final ObjectMapper json;
    private final WorkspaceLimits limits;
    private final RestrictedWorkspaceService workspace;
    private final Predicate<String> threadExists;
    private final Semaphore uploadSlot = new Semaphore(1);

    DeerFlowWorkspaceApi(ObjectMapper json, java.nio.file.Path storageRoot, Predicate<String> threadExists) {
        this.json = java.util.Objects.requireNonNull(json, "json");
        this.threadExists = java.util.Objects.requireNonNull(threadExists, "threadExists");
        this.limits = WorkspaceLimits.defaults();
        this.workspace = new RestrictedWorkspaceService(storageRoot, limits);
    }

    boolean tryHandle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        // Frozen uploads.py guards upload/limits/list with require_existing=False: the frozen UI
        // uploads a new conversation's first files before thread.submit creates the Thread row.
        Matcher match = UPLOAD_LIMITS.matcher(path);
        if (match.matches()) {
            return route(exchange, match.group(1), "GET", false, this::uploadLimits);
        }
        match = UPLOAD_LIST.matcher(path);
        if (match.matches()) {
            return route(exchange, match.group(1), "GET", false, this::listUploads);
        }
        match = UPLOADS.matcher(path);
        if (match.matches()) {
            return route(exchange, match.group(1), "POST", false, this::upload);
        }
        match = ARTIFACT.matcher(path);
        if (match.matches()) {
            String threadId = match.group(1);
            String logicalPath = match.group(2);
            return route(exchange, threadId, "GET", true,
                    (request, binding) -> readArtifact(request, binding, logicalPath));
        }
        return false;
    }

    private boolean route(
            HttpExchange exchange, String threadId, String method, boolean requireExisting, Route route)
            throws IOException {
        if (!isLoopbackPeer(exchange)) {
            sendJson(exchange, 403, Map.of("detail", "Local profile accepts loopback clients only"));
            return true;
        }
        if (!exchange.getRequestMethod().equals(method)) {
            exchange.getResponseHeaders().set("Allow", method);
            sendJson(exchange, 405, Map.of("detail", "Method Not Allowed"));
            return true;
        }
        if (!THREAD_ID.matcher(threadId).matches()) {
            sendJson(exchange, 422, Map.of(
                    "detail", "Invalid thread_id: expected 1-64 ASCII letters, digits, hyphens, or underscores"));
            return true;
        }
        if (requireExisting && !threadExists.test(threadId)) {
            sendJson(exchange, 404, Map.of("detail", "Thread not found"));
            return true;
        }
        try {
            route.handle(exchange, workspace.bind(ownerId(), threadId));
        } catch (HttpFailure failure) {
            if (failure.contentRange != null) {
                exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                exchange.getResponseHeaders().set("Content-Range", failure.contentRange);
            }
            sendJson(exchange, failure.status, Map.of("detail", failure.getMessage()));
        } catch (WorkspaceException failure) {
            sendJson(exchange, workspaceStatus(failure.error()), Map.of("detail", failure.getMessage()));
        } catch (IllegalArgumentException failure) {
            sendJson(exchange, 422, Map.of("detail", failure.getMessage()));
        }
        return true;
    }

    private void uploadLimits(HttpExchange exchange, WorkspaceBinding ignored) throws IOException {
        sendJson(exchange, 200, orderedMap(
                "max_files", MAX_FILES,
                "max_file_size", limits.maxUploadBytes(),
                "max_total_size", MAX_TOTAL_BYTES));
    }

    private void upload(HttpExchange exchange, WorkspaceBinding binding) throws IOException {
        if (!uploadSlot.tryAcquire()) {
            throw new HttpFailure(429, "Another upload request is already using the bounded local slot");
        }
        try {
            List<MultipartFile> files = parseMultipart(exchange);
            List<UploadReceipt> receipts = new ArrayList<>(files.size());
            try {
                for (MultipartFile file : files) {
                    receipts.add(workspace.upload(
                            ownerId(), binding.workspaceId(), file.filename, file.content, file.mediaType));
                }
            } catch (RuntimeException failure) {
                rollbackNewUploads(binding, receipts, failure);
                throw failure;
            }
            List<Map<String, Object>> uploaded = receipts.stream()
                    .map(receipt -> uploadInfo(binding.threadId(), receipt, Optional.empty()))
                    .toList();
            sendJson(exchange, 200, orderedMap(
                    "success", true,
                    "files", uploaded,
                    "message", "Successfully uploaded " + uploaded.size() + " file(s)",
                    "skipped_files", List.of()));
        } finally {
            uploadSlot.release();
        }
    }

    private void rollbackNewUploads(
            WorkspaceBinding binding, List<UploadReceipt> receipts, RuntimeException originalFailure) {
        RuntimeException rollbackFailure = null;
        for (int index = receipts.size() - 1; index >= 0; index--) {
            UploadReceipt receipt = receipts.get(index);
            if (receipt.deduplicated()) {
                continue;
            }
            try {
                workspace.rollbackNewUpload(ownerId(), binding.workspaceId(), receipt);
            } catch (RuntimeException failure) {
                if (rollbackFailure == null) {
                    rollbackFailure = failure;
                } else {
                    rollbackFailure.addSuppressed(failure);
                }
            }
        }
        if (rollbackFailure != null) {
            rollbackFailure.addSuppressed(originalFailure);
            throw new HttpFailure(500, "Upload failed and its bounded compensation was incomplete");
        }
    }

    private void listUploads(HttpExchange exchange, WorkspaceBinding binding) throws IOException {
        WorkspaceFileService files = workspace.workspaceFiles(ownerId(), binding.workspaceId());
        WorkspacePath directory = new WorkspacePath(binding.workspaceId(), ProjectPath.of("uploads"));
        List<Map<String, Object>> result = new ArrayList<>();
        int offset = 0;
        while (true) {
            FileListPage page = files.list(new FileListRequest(directory, offset, LIST_PAGE_SIZE));
            for (FileEntry entry : page.entries()) {
                FileMetadata metadata = entry.metadata();
                if (metadata.type() != FileType.FILE || metadata.link()) {
                    throw new HttpFailure(409, "Upload listing contains an unsupported filesystem entry");
                }
                result.add(uploadInfo(binding.threadId(), metadata));
                if (result.size() > MAX_LISTED_FILES) {
                    throw new HttpFailure(413, "Upload listing exceeds the bounded response limit");
                }
            }
            if (!page.truncated()) {
                break;
            }
            if (page.nextOffset() <= offset) {
                throw new HttpFailure(409, "Upload listing cursor did not advance");
            }
            offset = page.nextOffset();
        }
        sendJson(exchange, 200, orderedMap("files", result, "count", result.size()));
    }

    private void readArtifact(HttpExchange exchange, WorkspaceBinding binding, String logicalPath) throws IOException {
        String relativePath = logicalPath.substring("/mnt/user-data/".length());
        byte[] all = workspace.readVerifiedBytes(ownerId(), binding.workspaceId(), logicalPath);
        long total = all.length;
        String expectedHash = sha256(all);
        ByteSelection selection = parseRange(exchange.getRequestHeaders().getFirst("Range"), total);

        String filename = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        String mediaType = mediaType(filename, all);
        boolean download = queryValue(exchange, "download").map("true"::equalsIgnoreCase).orElse(false);
        boolean activeContent = isActiveContent(mediaType);
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        exchange.getResponseHeaders().set("ETag", "\"" + expectedHash + "\"");
        exchange.getResponseHeaders().set("Content-Type", mediaType);
        exchange.getResponseHeaders().set("Content-Length", Integer.toString(selection.length));
        exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set(
                "Content-Disposition",
                (download || activeContent ? "attachment" : "inline")
                        + "; filename*=UTF-8''" + encodePathSegment(filename));
        if (selection.partial) {
            exchange.getResponseHeaders().set(
                    "Content-Range", "bytes " + selection.start + "-" + selection.end + "/" + total);
        }
        if (selection.length == 0) {
            exchange.sendResponseHeaders(selection.status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(selection.status, selection.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(all, Math.toIntExact(selection.start), selection.length);
        }
    }

    private List<MultipartFile> parseMultipart(HttpExchange exchange) throws IOException {
        return parseMultipart(exchange, "files", MAX_FILES);
    }

    MultipartFile parseSingleFile(HttpExchange exchange, String fieldName) throws IOException {
        return parseMultipart(exchange, fieldName, 1).getFirst();
    }

    MultipartFile parseSkillArchive(HttpExchange exchange) throws IOException {
        return parseMultipart(exchange, "archive", 1,
                io.haifa.agent.superharness.server.config.SkillPackageFiles.MAX_ARCHIVE_BYTES,
                io.haifa.agent.superharness.server.config.SkillPackageFiles.MAX_ARCHIVE_BYTES).getFirst();
    }

    byte[] readThreadSkillArchive(String threadId, String path) throws IOException {
        if (!threadExists.test(threadId)) throw new java.io.FileNotFoundException("Thread not found");
        if (path == null || !path.startsWith("/mnt/user-data/") || !path.toLowerCase(Locale.ROOT).endsWith(".skill")) {
            throw new IllegalArgumentException("Skill archive requires a thread user-data .skill path");
        }
        WorkspaceBinding binding = workspace.bind(ownerId(), threadId);
        return workspace.readVerifiedBytes(ownerId(), binding.workspaceId(), path);
    }

    static Map<String, byte[]> readSkillReviewTarget(
            io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService projects,
            RestrictedWorkspaceService workspace, String threadId, String requested) throws IOException {
        if (requested == null || !requested.startsWith("/mnt/user-data/")) throw new IllegalArgumentException("Review target must belong to the current Thread");
        String owner = io.haifa.agent.superharness.server.auth.ThreadOwners.of(threadId);
        var binding = projects.requireThreadWorkspace(owner, threadId);
        if (requested.endsWith(".skill")) return io.haifa.agent.superharness.server.config.SkillPackageFiles.archive(
                workspace.readVerifiedBytes(owner, binding.workspaceId(), requested));
        String relative = RestrictedWorkspaceService.normalizeLogicalPath(requested);
        WorkspacePath root = new WorkspacePath(binding.workspaceId(), ProjectPath.of(relative));
        WorkspaceFileService files = workspace.workspaceFiles(owner, binding.workspaceId());
        if (files.stat(root, false).type() != FileType.DIRECTORY) throw new IllegalArgumentException("Review requires a package directory or .skill archive");
        var queue = new java.util.ArrayDeque<WorkspacePath>(); queue.add(root);
        Map<String, byte[]> result = new java.util.TreeMap<>();
        int entries = 0; long bytes = 0;
        java.time.Instant deadline = java.time.Instant.now().plusSeconds(15);
        while (!queue.isEmpty()) {
            WorkspacePath directory = queue.remove(); int offset = 0;
            while (true) {
                if (!java.time.Instant.now().isBefore(deadline)) throw new IOException("Review target enumeration timed out");
                FileListPage page = files.list(new FileListRequest(directory, offset, LIST_PAGE_SIZE));
                for (FileEntry entry : page.entries()) {
                    if (++entries > 4096) throw new IOException("Review target has too many entries");
                    String path = entry.metadata().path().projectPath().value();
                    String member = path.substring(relative.length() + 1);
                    if (member.split("/", -1).length > 32) throw new IOException("Review target is too deep");
                    if (entry.metadata().type() == FileType.DIRECTORY) queue.add(entry.metadata().path());
                    else if (entry.metadata().type() == FileType.FILE) {
                        if (entry.metadata().size() > 64L * 1024 * 1024 || (bytes += entry.metadata().size()) > 100L * 1024 * 1024) {
                            throw new IOException("Review target exceeds size limits");
                        }
                        result.put(member, workspace.readVerifiedBytes(owner, binding.workspaceId(), "/mnt/user-data/" + path));
                    } else throw new IOException("Linked or special review target is unavailable");
                }
                if (!page.truncated()) break;
                if (page.nextOffset() <= offset) throw new IOException("Review enumeration did not advance");
                offset = page.nextOffset();
            }
        }
        if (!result.containsKey("SKILL.md")) throw new IllegalArgumentException("Review directory requires a root SKILL.md");
        return result;
    }

    boolean tryAcquireUploadSlot() {
        return uploadSlot.tryAcquire();
    }

    void releaseUploadSlot() {
        uploadSlot.release();
    }

    Map<String, Object> attachProjectDocument(
            String threadId, String filename, byte[] content, String mediaType) {
        if (!threadExists.test(threadId)) {
            throw new IllegalArgumentException("Thread not found");
        }
        WorkspaceBinding binding = workspace.bind(ownerId(), threadId);
        UploadReceipt receipt = workspace.upload(ownerId(), binding.workspaceId(), filename, content, mediaType);
        Map<String, Object> uploaded = uploadInfo(binding.threadId(), receipt, Optional.empty());
        return orderedMap(
                "filename", uploaded.get("filename"),
                "size_bytes", uploaded.get("size"),
                "virtual_path", uploaded.get("virtual_path"),
                "artifact_url", uploaded.get("artifact_url"));
    }

    private List<MultipartFile> parseMultipart(HttpExchange exchange, String expectedField, int maxFiles)
            throws IOException {
        return parseMultipart(exchange, expectedField, maxFiles, limits.maxUploadBytes(), MAX_TOTAL_BYTES);
    }

    private List<MultipartFile> parseMultipart(HttpExchange exchange, String expectedField, int maxFiles,
            long maxFileBytes, long maxTotalBytes) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            throw new HttpFailure(422, "Content-Type must be multipart/form-data");
        }
        Matcher boundaryMatch = BOUNDARY.matcher(contentType);
        if (!boundaryMatch.find()) {
            throw new HttpFailure(422, "Multipart boundary is missing");
        }
        String boundary = boundaryMatch.group(1) != null ? boundaryMatch.group(1) : boundaryMatch.group(2);
        if (boundary.isEmpty() || boundary.length() > 70
                || boundary.chars().anyMatch(character -> character < 33 || character > 126)) {
            throw new HttpFailure(422, "Multipart boundary is invalid");
        }
        long maxBody = maxTotalBytes + MAX_MULTIPART_OVERHEAD;
        long declaredLength = -1;
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            try {
                declaredLength = Long.parseLong(contentLength);
                if (declaredLength < 0) {
                    throw new HttpFailure(422, "Content-Length is invalid");
                }
                if (declaredLength > maxBody) {
                    throw new HttpFailure(413, "Multipart request exceeds the total size limit");
                }
            } catch (NumberFormatException malformed) {
                throw new HttpFailure(422, "Content-Length is invalid");
            }
        }
        byte[] body = readBounded(exchange.getRequestBody(), maxBody, declaredLength);
        List<MultipartFile> files = decodeMultipart(body, boundary, expectedField, maxFiles);
        if (files.isEmpty()) {
            throw new HttpFailure(400, "No files provided");
        }
        if (files.size() > maxFiles) {
            throw new HttpFailure(413, "Too many files: maximum is " + maxFiles);
        }
        long total = 0;
        for (MultipartFile file : files) {
            if (file.content.length > maxFileBytes) {
                throw new HttpFailure(413, "File exceeds the per-file size limit: " + file.filename);
            }
            total = Math.addExact(total, file.content.length);
            if (total > maxTotalBytes) {
                throw new HttpFailure(413, "Files exceed the total size limit");
            }
        }
        return files;
    }

    private List<MultipartFile> decodeMultipart(
            byte[] body, String boundary, String expectedField, int maxFiles) {
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] nextDelimiter = concat(new byte[] {'\r', '\n'}, delimiter);
        byte[] headerEnd = new byte[] {'\r', '\n', '\r', '\n'};
        int cursor = 0;
        if (!startsWith(body, cursor, delimiter)) {
            throw new HttpFailure(422, "Multipart body does not start with its boundary");
        }
        cursor += delimiter.length;
        List<MultipartFile> files = new ArrayList<>();
        while (true) {
            if (startsWith(body, cursor, new byte[] {'-', '-'})) {
                cursor += 2;
                if (cursor == body.length) {
                    return files;
                }
                if (cursor + 2 == body.length && body[cursor] == '\r' && body[cursor + 1] == '\n') {
                    return files;
                }
                throw new HttpFailure(422, "Multipart epilogue is invalid");
            }
            if (!startsWith(body, cursor, new byte[] {'\r', '\n'})) {
                throw new HttpFailure(422, "Multipart boundary line is invalid");
            }
            cursor += 2;
            int headersEnd = indexOf(body, headerEnd, cursor);
            if (headersEnd < 0 || headersEnd - cursor > MAX_HEADER_BYTES) {
                throw new HttpFailure(422, "Multipart part headers are invalid or too large");
            }
            Map<String, String> headers = parseHeaders(Arrays.copyOfRange(body, cursor, headersEnd));
            int contentStart = headersEnd + headerEnd.length;
            int contentEnd = indexOf(body, nextDelimiter, contentStart);
            if (contentEnd < 0) {
                throw new HttpFailure(422, "Multipart closing boundary is missing");
            }
            String disposition = headers.get("content-disposition");
            if (disposition == null || !disposition.toLowerCase(Locale.ROOT).startsWith("form-data")) {
                throw new HttpFailure(422, "Multipart part requires form-data content disposition");
            }
            String name = capture(FORM_NAME, disposition)
                    .orElseThrow(() -> new HttpFailure(422, "Multipart field name is missing"));
            if (!name.equals(expectedField)) {
                throw new HttpFailure(422, "Unexpected multipart field: " + name);
            }
            String filename = capture(FORM_FILENAME, disposition)
                    .orElseThrow(() -> new HttpFailure(422, "Multipart filename is missing"));
            String mediaType = headers.getOrDefault("content-type", "application/octet-stream");
            files.add(new MultipartFile(filename, mediaType, Arrays.copyOfRange(body, contentStart, contentEnd)));
            if (files.size() > maxFiles) {
                throw new HttpFailure(413, "Too many files: maximum is " + maxFiles);
            }
            cursor = contentEnd + nextDelimiter.length;
        }
    }

    private Map<String, String> parseHeaders(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line : text.split("\\r\\n", -1)) {
            int separator = line.indexOf(':');
            if (separator <= 0 || headers.size() >= 32) {
                throw new HttpFailure(422, "Multipart part headers are invalid");
            }
            String name = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).trim();
            if (name.isEmpty() || value.isEmpty() || headers.putIfAbsent(name, value) != null) {
                throw new HttpFailure(422, "Multipart part headers are invalid");
            }
        }
        return headers;
    }

    private ByteSelection parseRange(String header, long total) {
        if (header == null) {
            return new ByteSelection(0, Math.toIntExact(total), total == 0 ? -1 : total - 1, 200, false);
        }
        Matcher match = BYTE_RANGE.matcher(header.trim());
        if (!match.matches() || (match.group(1).isEmpty() && match.group(2).isEmpty())) {
            throw unsatisfied(total);
        }
        if (total == 0) {
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
            long length = end - start + 1;
            if (length > Integer.MAX_VALUE) {
                throw unsatisfied(total);
            }
            return new ByteSelection(start, (int) length, end, 206, true);
        } catch (NumberFormatException overflow) {
            throw unsatisfied(total);
        }
    }

    private HttpFailure unsatisfied(long total) {
        return new HttpFailure(416, "Requested range is not satisfiable", "bytes */" + total);
    }

    private Map<String, Object> uploadInfo(
            String threadId, UploadReceipt receipt, Optional<Instant> modifiedAt) {
        String filename = receipt.logicalPath().substring(receipt.logicalPath().lastIndexOf('/') + 1);
        Map<String, Object> result = uploadInfo(
                threadId, filename, receipt.byteCount(), receipt.logicalPath(), modifiedAt);
        result.put("original_filename", receipt.originalFilename());
        return result;
    }

    private Map<String, Object> uploadInfo(String threadId, FileMetadata metadata) {
        String relative = metadata.path().projectPath().value();
        String filename = relative.substring(relative.lastIndexOf('/') + 1);
        return uploadInfo(
                threadId,
                filename,
                metadata.size(),
                "/mnt/user-data/" + relative,
                metadata.lastModifiedAt());
    }

    private Map<String, Object> uploadInfo(
            String threadId, String filename, long size, String virtualPath, Optional<Instant> modifiedAt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filename", filename);
        result.put("size", size);
        result.put("path", virtualPath);
        result.put("virtual_path", virtualPath);
        result.put("artifact_url", "/api/threads/" + encodePathSegment(threadId)
                + "/artifacts" + encodeArtifactPath(virtualPath));
        int dot = filename.lastIndexOf('.');
        if (dot >= 0) {
            result.put("extension", filename.substring(dot));
        }
        modifiedAt.ifPresent(value -> result.put(
                "modified", value.getEpochSecond() + value.getNano() / 1_000_000_000.0));
        return result;
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

    private static byte[] readBounded(InputStream input, long maxBytes, long declaredLength) throws IOException {
        if (declaredLength >= 0) {
            byte[] exact = input.readNBytes(Math.toIntExact(declaredLength));
            if (exact.length != declaredLength) {
                throw new HttpFailure(422, "Multipart body is shorter than Content-Length");
            }
            if (input.read() >= 0) {
                throw new HttpFailure(413, "Multipart body exceeds Content-Length");
            }
            return exact;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        for (int count; (count = input.read(buffer)) >= 0;) {
            if (count == 0) {
                continue;
            }
            total += count;
            if (total > maxBytes) {
                throw new HttpFailure(413, "Multipart request exceeds the total size limit");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
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

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static int indexOf(byte[] value, byte[] needle, int from) {
        outer:
        for (int index = Math.max(0, from); index <= value.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (value[index + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    private static boolean startsWith(byte[] value, int offset, byte[] prefix) {
        if (offset < 0 || offset + prefix.length > value.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[offset + index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] result = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }

    private static Optional<String> capture(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.ofNullable(matcher.group(1)).or(() -> Optional.ofNullable(matcher.group(2)));
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

    private static String encodeArtifactPath(String value) {
        return Arrays.stream(value.split("/", -1))
                .map(DeerFlowWorkspaceApi::encodePathSegment)
                .reduce((left, right) -> left + "/" + right)
                .orElse("");
    }

    private static String encodePathSegment(String value) {
        StringBuilder result = new StringBuilder();
        for (byte item : value.getBytes(StandardCharsets.UTF_8)) {
            int octet = item & 0xff;
            if ((octet >= 'a' && octet <= 'z') || (octet >= 'A' && octet <= 'Z')
                    || (octet >= '0' && octet <= '9') || octet == '-' || octet == '_'
                    || octet == '.' || octet == '~') {
                result.append((char) octet);
            } else {
                result.append('%').append(String.format(Locale.ROOT, "%02X", octet));
            }
        }
        return result.toString();
    }

    private static boolean isActiveContent(String mediaType) {
        String value = mediaType.toLowerCase(Locale.ROOT);
        return value.equals("text/html") || value.equals("application/xhtml+xml")
                || value.equals("image/svg+xml");
    }

    private static boolean isLoopbackPeer(HttpExchange exchange) {
        return exchange.getRemoteAddress() != null
                && exchange.getRemoteAddress().getAddress() != null
                && exchange.getRemoteAddress().getAddress().isLoopbackAddress();
    }

    private static int workspaceStatus(WorkspaceError error) {
        return switch (error) {
            case WORKSPACE_NOT_FOUND, FILE_NOT_FOUND -> 404;
            case OWNER_MISMATCH -> 403;
            case FILE_TOO_LARGE -> 413;
            case READ_OUT_OF_BOUNDS -> 416;
            case IMMUTABLE_UPLOAD, HASH_CONFLICT, CONTENT_INTEGRITY, IO_FAILURE -> 409;
            default -> 422;
        };
    }

    private static Map<String, Object> orderedMap(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    @FunctionalInterface
    private interface Route {
        void handle(HttpExchange exchange, WorkspaceBinding binding) throws IOException;
    }

    record MultipartFile(String filename, String mediaType, byte[] content) {}

    private record ByteSelection(long start, int length, long end, int status, boolean partial) {}

    static final class HttpFailure extends RuntimeException {
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

        int status() {
            return status;
        }
    }
}

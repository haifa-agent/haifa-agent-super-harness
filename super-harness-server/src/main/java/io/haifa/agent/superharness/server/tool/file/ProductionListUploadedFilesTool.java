package io.haifa.agent.superharness.server.tool.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.project.filesystem.FileEntry;
import io.haifa.agent.project.filesystem.FileListPage;
import io.haifa.agent.project.filesystem.FileListRequest;
import io.haifa.agent.project.filesystem.FileMetadata;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.filesystem.WorkspaceFileService;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.superharness.server.auth.ThreadOwners;
import io.haifa.agent.superharness.server.tool.ToolDescriptions;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceBinding;
import io.haifa.agent.superharness.workspace.WorkspaceException;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Thread-bound implementation of frozen DeerFlow's builtin {@code list_uploaded_files}.
 *
 * <p>Reports only files uploaded in earlier turns (the current Run's files are already in
 * {@code <current_uploads>}). Filtering, ordering, the JSON result shape and messages match frozen
 * {@code _list_uploaded_files_impl}. SH has no document-outline extractor, so {@code include_outline}
 * is accepted but never fabricates an outline.
 */
public final class ProductionListUploadedFilesTool
        implements JavaTool<ProductionListUploadedFilesTool.Input, ProductionListUploadedFilesTool.Output> {
    private static final int DEFAULT_MAX_RESULTS = 20;
    private static final int MAX_MAX_RESULTS = 100;
    private static final int LIST_PAGE_SIZE = 500;
    private static final String SDK_LOCAL_PRINCIPAL = "default-public-user";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JavaToolSpec<Input, Output> SPEC =
            JavaToolSpec.builder("list_uploaded_files", Input.class, Output.class)
                    .title("List historical uploads")
                    .description(ToolDescriptions.load("list_uploaded_files"))
                    .sideEffects(ToolSideEffect.FILE_READ)
                    .build();

    private final String threadId;
    private final PersistentProjectWorkspaceService bindings;
    private final RestrictedWorkspaceService workspace;
    private final CurrentRunUploads currentRunUploads;

    public ProductionListUploadedFilesTool(
            String threadId,
            PersistentProjectWorkspaceService bindings,
            RestrictedWorkspaceService workspace,
            CurrentRunUploads currentRunUploads) {
        this.threadId = Objects.requireNonNull(threadId, "threadId");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.currentRunUploads = Objects.requireNonNull(currentRunUploads, "currentRunUploads");
    }

    @Override
    public JavaToolSpec<Input, Output> spec() {
        return SPEC;
    }

    @Override
    public Output invoke(Input input, JavaToolContext context) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(context, "context");
        if (!SDK_LOCAL_PRINCIPAL.equals(context.principal().principalId())
                || !"user".equals(context.principal().principalType())) {
            throw new IllegalArgumentException("list_uploaded_files requires the local Thread owner");
        }
        context.cancellation().throwIfCancellationRequested();
        if (!Instant.now().isBefore(context.deadline())) {
            throw new IllegalStateException("list_uploaded_files deadline has expired");
        }
        WorkspaceBinding binding = bindings.requireThreadWorkspace(ThreadOwners.of(threadId), threadId);
        WorkspaceFileService files = workspace.workspaceFiles(binding.ownerId(), binding.workspaceId());
        List<UploadedFile> candidates;
        try {
            candidates = historicalUploads(files, binding.workspaceId());
        } catch (WorkspaceException failure) {
            if (FileToolSupport.missing(failure)) {
                return Output.json("{\"files\":[],\"message\":\"No uploads directory for this thread.\"}");
            }
            return Output.failure("Failed to read uploads directory: " + failure.getMessage());
        }
        if (candidates.isEmpty()) {
            return Output.json("{\"files\":[],\"message\":\"No historical uploaded files in this thread.\"}");
        }
        Optional<String> query = normalizeQuery(input.query());
        Optional<Set<String>> extensions = normalizeExtensions(input.extensions());
        if (query.isPresent() || extensions.isPresent()) {
            List<UploadedFile> filtered = new ArrayList<>();
            for (UploadedFile candidate : candidates) {
                if (extensions.isPresent() && !extensions.get().contains(candidate.extension().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (query.isPresent()
                        && !candidate.filename().toLowerCase(Locale.ROOT).contains(query.get())) {
                    continue;
                }
                filtered.add(candidate);
            }
            candidates = filtered;
            if (candidates.isEmpty()) {
                return Output.json("{\"files\":[],\"total_count\":0,"
                        + "\"message\":\"No uploaded files matched the given filters.\"}");
            }
        }
        candidates.sort(Comparator.comparing(UploadedFile::lastModified).reversed()
                .thenComparing(UploadedFile::filename));
        int maxResults = Math.max(1, Math.min(input.max_results().orElse(DEFAULT_MAX_RESULTS), MAX_MAX_RESULTS));
        int totalCount = candidates.size();
        boolean truncated = totalCount > maxResults;
        List<UploadedFile> visible = candidates.subList(0, Math.min(totalCount, maxResults));

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (UploadedFile file : visible) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("filename", file.filename());
            info.put("size", file.size());
            info.put("path", "/mnt/user-data/uploads/" + file.filename());
            info.put("extension", file.extension());
            entries.add(info);
        }
        result.put("files", entries);
        result.put("total_count", totalCount);
        if (truncated) {
            result.put("truncated", true);
            result.put("omitted_summary", omittedSummary(candidates.subList(maxResults, totalCount)));
        }
        result.put("message", "Found " + totalCount + " historical file(s).");
        try {
            return new Output(JSON.writeValueAsString(result), totalCount, truncated, "", "");
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            return Output.failure("Unexpected error listing uploaded files");
        }
    }

    private List<UploadedFile> historicalUploads(WorkspaceFileService files,
            io.haifa.agent.project.workspace.WorkspaceId workspaceId) {
        WorkspacePath uploads = new WorkspacePath(workspaceId, ProjectPath.of("uploads"));
        List<FileEntry> all = new ArrayList<>();
        int offset = 0;
        while (all.size() < FileToolSupport.MAX_SCAN_ENTRIES) {
            FileListPage page = files.list(new FileListRequest(uploads, offset, LIST_PAGE_SIZE));
            all.addAll(page.entries());
            if (!page.truncated() || page.nextOffset() <= offset) {
                break;
            }
            offset = page.nextOffset();
        }
        Set<String> currentRun = currentRunUploads.filenames();
        List<UploadedFile> candidates = new ArrayList<>();
        Set<String> allNames = new java.util.HashSet<>();
        for (FileEntry entry : all) {
            FileMetadata metadata = entry.metadata();
            if (metadata.type() == FileType.FILE && !metadata.link()) {
                allNames.add(FileToolSupport.leafName(metadata.path()));
            }
        }
        for (FileEntry entry : all) {
            FileMetadata metadata = entry.metadata();
            if (metadata.type() != FileType.FILE || metadata.link()) {
                continue;
            }
            String filename = FileToolSupport.leafName(metadata.path());
            if (currentRun.contains(filename)) {
                continue;
            }
            if (isHiddenConversionArtifact(filename, allNames)) {
                continue;
            }
            candidates.add(new UploadedFile(
                    filename, metadata.size(), suffix(filename),
                    metadata.lastModifiedAt().orElse(Instant.EPOCH)));
        }
        return candidates;
    }

    private static boolean isHiddenConversionArtifact(String filename, Set<String> allNames) {
        if (!filename.endsWith(".md")) {
            return false;
        }
        String stem = filename.substring(0, filename.length() - 3);
        for (String other : allNames) {
            if (!other.equals(filename) && stemOf(other).equals(stem)) {
                return true;
            }
        }
        return false;
    }

    private static String stemOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot <= 0 ? filename : filename.substring(0, dot);
    }

    private static String suffix(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot);
    }

    private static Optional<String> normalizeQuery(Optional<String> query) {
        return query.map(String::strip).filter(value -> !value.isEmpty());
    }

    private static Optional<Set<String>> normalizeExtensions(Optional<List<String>> extensions) {
        if (extensions.isEmpty()) {
            return Optional.empty();
        }
        Set<String> normalized = new java.util.LinkedHashSet<>();
        for (String item : extensions.get()) {
            if (item == null) {
                continue;
            }
            String token = item.strip().toLowerCase(Locale.ROOT);
            while (token.startsWith("*")) {
                token = token.substring(1);
            }
            if (token.isEmpty()) {
                continue;
            }
            normalized.add(token.startsWith(".") ? token : "." + token);
        }
        return normalized.isEmpty() ? Optional.empty() : Optional.of(normalized);
    }

    private static String omittedSummary(List<UploadedFile> omitted) {
        Map<String, Integer> counts = new TreeMap<>();
        for (UploadedFile file : omitted) {
            String label = file.extension().isEmpty() ? "(no extension)" : file.extension().toLowerCase(Locale.ROOT);
            counts.merge(label, 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((extension, count) -> parts.add(count + " " + extension));
        return String.join(", ", parts);
    }

    @Override
    public String summarize(Output output) {
        return output.error().isEmpty() ? output.content() : "Error: " + output.error();
    }

    private record UploadedFile(String filename, long size, String extension, Instant lastModified) {}

    public record Input(
            Optional<Boolean> include_outline,
            Optional<Integer> max_results,
            Optional<String> query,
            Optional<List<String>> extensions) {
        public Input {
            include_outline = Objects.requireNonNull(include_outline, "include_outline");
            max_results = Objects.requireNonNull(max_results, "max_results");
            query = Objects.requireNonNull(query, "query");
            extensions = Objects.requireNonNull(extensions, "extensions");
        }
    }

    public record Output(String content, int totalCount, boolean truncated, String message, String error) {
        static Output json(String content) {
            return new Output(content, 0, false, "", "");
        }

        static Output failure(String error) {
            return new Output("", 0, false, "", error);
        }
    }
}

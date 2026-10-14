package io.haifa.agent.superharness.server.tool.file;

import io.haifa.agent.project.filesystem.FileEntry;
import io.haifa.agent.project.filesystem.FileMetadata;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.filesystem.WorkspaceFileService;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread-bound implementation of frozen DeerFlow's sandbox {@code ls}.
 *
 * <p>The frozen listing text is produced by {@code sandbox.list_dir}; that provider implementation
 * is not part of the frozen excerpt, so SH renders a deterministic two-level tree (directory names
 * suffixed with {@code /}) and head-truncates exactly like frozen {@code _truncate_ls_output}.
 */
public final class ProductionLsTool implements JavaTool<ProductionLsTool.Input, ProductionLsTool.Output> {
    private static final String SDK_LOCAL_PRINCIPAL = "default-public-user";
    private static final JavaToolSpec<Input, Output> SPEC =
            JavaToolSpec.builder("ls", Input.class, Output.class)
                    .title("List a workspace directory")
                    .description(ToolDescriptions.load("ls"))
                    .sideEffects(ToolSideEffect.FILE_READ)
                    .build();

    private final String threadId;
    private final PersistentProjectWorkspaceService bindings;
    private final RestrictedWorkspaceService workspace;

    public ProductionLsTool(
            String threadId, PersistentProjectWorkspaceService bindings, RestrictedWorkspaceService workspace) {
        this.threadId = Objects.requireNonNull(threadId, "threadId");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
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
            throw new IllegalArgumentException("ls requires the local Thread owner");
        }
        context.cancellation().throwIfCancellationRequested();
        if (!Instant.now().isBefore(context.deadline())) {
            throw new IllegalStateException("ls deadline has expired");
        }
        String path = input.path();
        String normalized;
        try {
            normalized = FileToolSupport.normalizeDirectory(path);
        } catch (WorkspaceException failure) {
            return Output.failure(path, unreadable(path, failure));
        }
        WorkspaceBinding binding = bindings.requireThreadWorkspace(ThreadOwners.of(threadId), threadId);
        WorkspaceFileService files = workspace.workspaceFiles(binding.ownerId(), binding.workspaceId());
        WorkspacePath root = FileToolSupport.workspacePath(binding.workspaceId(), normalized);
        List<FileEntry> entries;
        try {
            FileMetadata metadata = files.stat(root, false);
            if (metadata.type() != FileType.DIRECTORY) {
                return Output.failure(path, "Path is not a directory: " + path);
            }
            entries = FileToolSupport.listDirectory(files, root, FileToolSupport.MAX_SCAN_ENTRIES);
        } catch (WorkspaceException failure) {
            if (FileToolSupport.missing(failure)) {
                return Output.failure(path, "Directory not found: " + path);
            }
            return Output.failure(path, "Unexpected error listing directory: " + failure.getMessage());
        }
        if (entries.isEmpty()) {
            return new Output(path, "(empty)", 0);
        }
        List<String> lines = new ArrayList<>();
        for (FileEntry entry : entries) {
            boolean directory = FileToolSupport.isDirectory(entry);
            lines.add(FileToolSupport.leafName(entry.metadata().path()) + (directory ? "/" : ""));
            if (!directory) {
                continue;
            }
            List<FileEntry> children;
            try {
                children = FileToolSupport.listDirectory(
                        files, entry.metadata().path(), FileToolSupport.MAX_SCAN_ENTRIES);
            } catch (WorkspaceException ignored) {
                continue;
            }
            List<FileEntry> sorted = sort(children);
            for (FileEntry child : sorted) {
                boolean childDirectory = FileToolSupport.isDirectory(child);
                lines.add("  " + FileToolSupport.leafName(child.metadata().path()) + (childDirectory ? "/" : ""));
            }
        }
        String output = FileToolSupport.truncateLsOutput(
                String.join("\n", lines), FileToolSupport.LS_OUTPUT_MAX_CHARS);
        return new Output(path, output, lines.size());
    }

    private static List<FileEntry> sort(List<FileEntry> entries) {
        List<FileEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(entry -> FileToolSupport.leafName(entry.metadata().path())));
        return sorted;
    }

    @Override
    public String summarize(Output output) {
        return output.error().isEmpty() ? output.content() : "Error: " + output.error();
    }

    private static String unreadable(String path, WorkspaceException failure) {
        return switch (failure.error()) {
            case INVALID_PATH, PATH_ESCAPE -> path + " is not a readable path under /mnt/user-data/";
            default -> "Unexpected error listing directory: " + failure.getMessage();
        };
    }

    public record Input(String path, Optional<String> description) {
        public Input {
            Objects.requireNonNull(path, "path");
            description = Objects.requireNonNull(description, "description");
        }
    }

    public record Output(String path, String content, int entryCount, String error) {
        public Output(String path, String content, int entryCount) {
            this(path, content, entryCount, "");
        }

        static Output failure(String path, String error) {
            return new Output(path, "", 0, error);
        }
    }
}

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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Thread-bound implementation of frozen DeerFlow's sandbox {@code glob}.
 *
 * <p>Bounded recursive traversal over the owner-bound workspace listing with a
 * recursive-glob-compatible matcher (frozen is Python-backed, so {@code **}/{@code *} follow
 * Python semantics). Result formatting matches frozen {@code _format_glob_results}.
 */
public final class ProductionGlobTool implements JavaTool<ProductionGlobTool.Input, ProductionGlobTool.Output> {
    private static final int DEFAULT_MAX_RESULTS = 200;
    private static final int MAX_MAX_RESULTS = 1000;
    private static final String SDK_LOCAL_PRINCIPAL = "default-public-user";
    private static final JavaToolSpec<Input, Output> SPEC =
            JavaToolSpec.builder("glob", Input.class, Output.class)
                    .title("Find paths by glob")
                    .description(ToolDescriptions.load("glob"))
                    .sideEffects(ToolSideEffect.FILE_READ)
                    .build();

    private final String threadId;
    private final PersistentProjectWorkspaceService bindings;
    private final RestrictedWorkspaceService workspace;

    public ProductionGlobTool(
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
            throw new IllegalArgumentException("glob requires the local Thread owner");
        }
        context.cancellation().throwIfCancellationRequested();
        if (!Instant.now().isBefore(context.deadline())) {
            throw new IllegalStateException("glob deadline has expired");
        }
        String path = input.path();
        int maxResults = resolveMax(input.max_results().orElse(DEFAULT_MAX_RESULTS));
        boolean includeDirs = input.include_dirs().orElse(false);
        String normalized;
        try {
            normalized = FileToolSupport.normalizeDirectory(path);
        } catch (WorkspaceException failure) {
            return Output.failure(path, unreadable(path, failure));
        }
        Pattern matcher = FileToolSupport.globToRegex(input.pattern());
        WorkspaceBinding binding = bindings.requireThreadWorkspace(ThreadOwners.of(threadId), threadId);
        WorkspaceFileService files = workspace.workspaceFiles(binding.ownerId(), binding.workspaceId());
        WorkspacePath root = FileToolSupport.workspacePath(binding.workspaceId(), normalized);
        List<String> matches = new ArrayList<>();
        boolean[] truncated = {false};
        try {
            FileMetadata metadata = files.stat(root, false);
            if (metadata.type() != FileType.DIRECTORY) {
                return Output.failure(path, "Path is not a directory: " + path);
            }
            walk(files, root, normalized.length() + 1, matcher, includeDirs, maxResults, matches, truncated);
        } catch (WorkspaceException failure) {
            if (FileToolSupport.missing(failure)) {
                return Output.failure(path, "Directory not found: " + path);
            }
            return Output.failure(path, "Unexpected error searching paths: " + failure.getMessage());
        }
        matches.sort(String::compareTo);
        String output = format(path, matches, truncated[0]);
        return new Output(path, output, matches.size(), truncated[0]);
    }

    private void walk(
            WorkspaceFileService files,
            WorkspacePath directory,
            int prefixLength,
            Pattern matcher,
            boolean includeDirs,
            int maxResults,
            List<String> matches,
            boolean[] truncated) {
        List<FileEntry> entries = FileToolSupport.listDirectory(files, directory, FileToolSupport.MAX_SCAN_ENTRIES);
        for (FileEntry entry : entries) {
            if (FileToolSupport.isLink(entry)) {
                continue;
            }
            String relativePath = entry.metadata().path().projectPath().value();
            if (relativePath.length() < prefixLength) {
                continue;
            }
            String relative = relativePath.substring(prefixLength);
            boolean isDirectory = entry.metadata().type() == FileType.DIRECTORY;
            if (matcher.matcher(relative).matches() && (!isDirectory || includeDirs)) {
                if (matches.size() >= maxResults) {
                    truncated[0] = true;
                    return;
                }
                matches.add(FileToolSupport.virtualPath(relativePath));
            }
            if (isDirectory) {
                walk(files, entry.metadata().path(), prefixLength, matcher, includeDirs,
                        maxResults, matches, truncated);
                if (truncated[0]) {
                    return;
                }
            }
        }
    }

    private static int resolveMax(int requested) {
        if (requested <= 0) {
            return DEFAULT_MAX_RESULTS;
        }
        return Math.min(requested, MAX_MAX_RESULTS);
    }

    private static String format(String rootPath, List<String> matches, boolean truncated) {
        if (matches.isEmpty()) {
            if (truncated) {
                return "Search under " + rootPath + " stopped at its result limit with no files matched in the "
                        + "part it covered; results are incomplete. Narrow the path or pattern.";
            }
            return "No files matched under " + rootPath;
        }
        StringBuilder output = new StringBuilder("Found " + matches.size() + " paths under " + rootPath);
        if (truncated) {
            output.append(" (showing first ").append(matches.size()).append(")");
        }
        int index = 1;
        for (String match : matches) {
            output.append('\n').append(index++).append(". ").append(match);
        }
        if (truncated) {
            output.append("\nResults truncated. Narrow the path or pattern to see fewer matches.");
        }
        return output.toString();
    }

    @Override
    public String summarize(Output output) {
        return output.error().isEmpty() ? output.content() : "Error: " + output.error();
    }

    private static String unreadable(String path, WorkspaceException failure) {
        return switch (failure.error()) {
            case INVALID_PATH, PATH_ESCAPE -> path + " is not a readable path under /mnt/user-data/";
            default -> "Unexpected error searching paths: " + failure.getMessage();
        };
    }

    public record Input(
            String pattern,
            String path,
            Optional<String> description,
            Optional<Boolean> include_dirs,
            Optional<Integer> max_results) {
        public Input {
            Objects.requireNonNull(pattern, "pattern");
            Objects.requireNonNull(path, "path");
            description = Objects.requireNonNull(description, "description");
            include_dirs = Objects.requireNonNull(include_dirs, "include_dirs");
            max_results = Objects.requireNonNull(max_results, "max_results");
        }
    }

    public record Output(String path, String content, int matchCount, boolean truncated, String error) {
        public Output(String path, String content, int matchCount, boolean truncated) {
            this(path, content, matchCount, truncated, "");
        }

        static Output failure(String path, String error) {
            return new Output(path, "", 0, false, error);
        }
    }
}

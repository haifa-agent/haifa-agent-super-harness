package io.haifa.agent.superharness.server;

import io.haifa.agent.project.filesystem.FileEntry;
import io.haifa.agent.project.filesystem.FileMetadata;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.filesystem.WorkspaceFileService;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceBinding;
import io.haifa.agent.superharness.workspace.WorkspaceException;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Thread-bound implementation of frozen DeerFlow's sandbox {@code grep}.
 *
 * <p>Human ruling 2026-09-30: SH scans with {@code java.util.regex} on a bounded read surface.
 * A deadline-checking {@link CharSequence} guards against catastrophic backtracking so a hostile
 * pattern cannot hang the Run; binary / non-UTF-8 files are skipped. Formatting matches frozen
 * {@code _format_grep_results}.
 */
public final class ProductionGrepTool implements JavaTool<ProductionGrepTool.Input, ProductionGrepTool.Output> {
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_MAX_RESULTS = 500;
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final long TOTAL_BYTES_BUDGET = 32L * 1024 * 1024;
    private static final long MATCH_TIMEOUT_MILLIS = 2_000;
    private static final String SDK_LOCAL_PRINCIPAL = "default-public-user";
    private static final JavaToolSpec<Input, Output> SPEC =
            JavaToolSpec.builder("grep", Input.class, Output.class)
                    .title("Search file contents")
                    .description(ToolDescriptions.load("grep"))
                    .sideEffects(ToolSideEffect.FILE_READ)
                    .build();

    private final String threadId;
    private final PersistentProjectWorkspaceService bindings;
    private final RestrictedWorkspaceService workspace;

    ProductionGrepTool(
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
            throw new IllegalArgumentException("grep requires the local Thread owner");
        }
        context.cancellation().throwIfCancellationRequested();
        if (!Instant.now().isBefore(context.deadline())) {
            throw new IllegalStateException("grep deadline has expired");
        }
        String path = input.path();
        int maxResults = resolveMax(input.max_results().orElse(DEFAULT_MAX_RESULTS));
        int flags = Pattern.UNICODE_CASE;
        if (!input.case_sensitive().orElse(false)) {
            flags |= Pattern.CASE_INSENSITIVE;
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(input.literal().orElse(false) ? Pattern.quote(input.pattern())
                    : input.pattern(), flags);
        } catch (PatternSyntaxException invalid) {
            return Output.failure(path, "Invalid regex pattern: " + invalid.getMessage());
        }
        Optional<Pattern> globFilter = input.glob().filter(value -> !value.isBlank())
                .map(FileToolSupport::globToRegex);
        String normalized;
        try {
            normalized = FileToolSupport.normalizeDirectory(path);
        } catch (WorkspaceException failure) {
            return Output.failure(path, unreadable(path, failure));
        }
        WorkspaceBinding binding = bindings.requireThreadWorkspace(DeerFlowBootstrapApi.DEFAULT_USER_ID, threadId);
        WorkspaceFileService files = workspace.workspaceFiles(binding.ownerId(), binding.workspaceId());
        WorkspacePath root = FileToolSupport.workspacePath(binding.workspaceId(), normalized);
        List<Match> matches = new ArrayList<>();
        boolean[] truncated = {false};
        try {
            FileMetadata metadata = files.stat(root, false);
            if (metadata.type() == FileType.FILE && !metadata.link()) {
                scanFile(binding, normalized, pattern, maxResults, matches, truncated, context);
            } else if (metadata.type() == FileType.DIRECTORY) {
                List<String> candidates = new ArrayList<>();
                collectFiles(files, root, candidates, truncated);
                int prefixLength = normalized.length() + 1;
                long[] budget = {TOTAL_BYTES_BUDGET};
                for (String relativePath : candidates) {
                    if (truncated[0] || matches.size() >= maxResults) {
                        truncated[0] = true;
                        break;
                    }
                    String relativeToRoot = relativePath.length() >= prefixLength
                            ? relativePath.substring(prefixLength)
                            : relativePath;
                    if (globFilter.isPresent() && !globFilter.get().matcher(relativeToRoot).matches()) {
                        continue;
                    }
                    if (budget[0] <= 0) {
                        truncated[0] = true;
                        break;
                    }
                    budget[0] -= Math.max(scanFile(
                            binding, relativePath, pattern, maxResults, matches, truncated, context), 0);
                }
            } else {
                return Output.failure(path, "Path is not a directory: " + path);
            }
        } catch (WorkspaceException failure) {
            if (FileToolSupport.missing(failure)) {
                return Output.failure(path, "Directory not found: " + path);
            }
            return Output.failure(path, "Unexpected error searching file contents: " + failure.getMessage());
        } catch (RegexTimeout timeout) {
            return Output.timeout(path);
        }
        String output = format(path, matches, truncated[0]);
        return new Output(path, output, matches.size(), truncated[0]);
    }

    /** Scan one workspace file; returns bytes consumed (or -1 for a non-file / skipped candidate). */
    private long scanFile(
            WorkspaceBinding binding,
            String relative,
            Pattern pattern,
            int maxResults,
            List<Match> matches,
            boolean[] truncated,
            JavaToolContext context) {
        String virtual = FileToolSupport.virtualPath(relative);
        byte[] bytes;
        try {
            bytes = workspace.readVerifiedBytes(binding.ownerId(), binding.workspaceId(), virtual);
        } catch (WorkspaceException failure) {
            return -1;
        }
        if (bytes.length > MAX_FILE_BYTES) {
            return -1;
        }
        String text = decodeUtf8(bytes);
        if (text == null) {
            return bytes.length;
        }
        String[] lines = text.split("\n", -1);
        DeadlineCharSequence deadline = new DeadlineCharSequence(System.nanoTime()
                + MATCH_TIMEOUT_MILLIS * 1_000_000L);
        for (int index = 0; index < lines.length; index++) {
            if (matches.size() >= maxResults) {
                truncated[0] = true;
                return bytes.length;
            }
            String line = lines[index];
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            deadline.reset(line);
            try {
                Matcher matcher = pattern.matcher(deadline);
                if (matcher.find()) {
                    matches.add(new Match(virtual, index + 1, line));
                }
            } catch (RegexTimeout timeout) {
                throw timeout;
            }
        }
        return bytes.length;
    }

    private void collectFiles(
            WorkspaceFileService files,
            WorkspacePath directory,
            List<String> candidates,
            boolean[] truncated) {
        List<FileEntry> entries = FileToolSupport.listDirectory(files, directory, FileToolSupport.MAX_SCAN_ENTRIES);
        for (FileEntry entry : entries) {
            if (candidates.size() >= FileToolSupport.MAX_SCAN_FILES) {
                truncated[0] = true;
                return;
            }
            if (FileToolSupport.isLink(entry)) {
                continue;
            }
            String relativePath = entry.metadata().path().projectPath().value();
            if (entry.metadata().type() == FileType.FILE) {
                candidates.add(relativePath);
            } else if (entry.metadata().type() == FileType.DIRECTORY) {
                collectFiles(files, entry.metadata().path(), candidates, truncated);
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

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException malformed) {
            return null;
        }
    }

    private static String format(String rootPath, List<Match> matches, boolean truncated) {
        if (matches.isEmpty()) {
            if (truncated) {
                return "Search under " + rootPath + " stopped at its result limit with no matches in the part "
                        + "it covered; results are incomplete. Narrow the path or add a glob filter.";
            }
            return "No matches found under " + rootPath;
        }
        StringBuilder output = new StringBuilder("Found " + matches.size() + " matches under " + rootPath);
        if (truncated) {
            output.append(" (showing first ").append(matches.size()).append(")");
        }
        for (Match match : matches) {
            output.append('\n').append(match.path()).append(':').append(match.lineNumber()).append(": ")
                    .append(match.line());
        }
        if (truncated) {
            output.append("\nResults truncated. Narrow the path or add a glob filter.");
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
            default -> "Unexpected error searching file contents: " + failure.getMessage();
        };
    }

    private record Match(String path, int lineNumber, String line) {}

    /** Thrown from the deadline-guarded {@link CharSequence} when matching overruns its budget. */
    private static final class RegexTimeout extends RuntimeException {}

    /**
     * A read-only {@link CharSequence} whose {@code charAt} fails once the deadline passes.
     * Java's regex engine calls {@code charAt} on every step, so a catastrophic backtracking
     * pattern aborts instead of pinning a worker thread.
     */
    private static final class DeadlineCharSequence implements CharSequence {
        private final long deadlineNanos;
        private String value = "";

        private DeadlineCharSequence(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }

        void reset(String next) {
            this.value = next;
        }

        @Override
        public int length() {
            return value.length();
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() > deadlineNanos) {
                throw new RegexTimeout();
            }
            return value.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return value.subSequence(start, end);
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public record Input(
            String pattern,
            String path,
            Optional<String> description,
            Optional<String> glob,
            Optional<Boolean> literal,
            Optional<Boolean> case_sensitive,
            Optional<Integer> max_results) {
        public Input {
            Objects.requireNonNull(pattern, "pattern");
            Objects.requireNonNull(path, "path");
            description = Objects.requireNonNull(description, "description");
            glob = Objects.requireNonNull(glob, "glob");
            literal = Objects.requireNonNull(literal, "literal");
            case_sensitive = Objects.requireNonNull(case_sensitive, "case_sensitive");
            max_results = Objects.requireNonNull(max_results, "max_results");
        }
    }

    public record Output(String path, String content, int matchCount, boolean truncated, String error) {
        Output(String path, String content, int matchCount, boolean truncated) {
            this(path, content, matchCount, truncated, "");
        }

        static Output failure(String path, String error) {
            return new Output(path, "", 0, false, error);
        }

        static Output timeout(String path) {
            return new Output(path, "", 0, false,
                    "grep timed out while matching; narrow the pattern or restrict the path.");
        }
    }
}

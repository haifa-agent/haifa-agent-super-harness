package io.haifa.agent.superharness.server.tool.file;

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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread-bound read-only counterpart of frozen DeerFlow's sandbox {@code read_file}.
 *
 * <p>It reads the saved original bytes of this Thread's {@code /mnt/user-data/} files only; the
 * model never supplies owner or Thread identity. Expected read failures (missing, foreign, not UTF-8,
 * bad range) are returned as {@code Error: ...} text like the frozen Tool, never as guessed content.
 */
public final class ProductionReadFileTool
        implements JavaTool<ProductionReadFileTool.Input, ProductionReadFileTool.Output> {
    /**
     * The SDK truncates a Tool summary at 4000 characters and keeps a result inline only below 16384
     * characters (summary plus structured data, which both carry the window). One read therefore
     * returns at most a 3 KiB line window; the header line names the next start_line.
     */
    static final int MAX_RESULT_BYTES = 3 * 1024;
    private static final String SDK_LOCAL_PRINCIPAL = "default-public-user";
    private static final JavaToolSpec<Input, Output> SPEC = JavaToolSpec.builder("read_file", Input.class, Output.class)
            .title("Read a saved file")
            .description(ToolDescriptions.load("read_file"))
            .sideEffects(ToolSideEffect.FILE_READ)
            .build();

    private final String threadId;
    private final PersistentProjectWorkspaceService bindings;
    private final RestrictedWorkspaceService workspace;
    private final FileToolSession readState;

    public ProductionReadFileTool(
            String threadId,
            PersistentProjectWorkspaceService bindings,
            RestrictedWorkspaceService workspace,
            FileToolSession readState) {
        this.threadId = Objects.requireNonNull(threadId, "threadId");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.readState = Objects.requireNonNull(readState, "readState");
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
            throw new IllegalArgumentException("read_file requires the local Thread owner");
        }
        context.cancellation().throwIfCancellationRequested();
        if (!Instant.now().isBefore(context.deadline())) {
            throw new IllegalStateException("read_file deadline has expired");
        }
        String path = input.path();
        int start = input.start_line().orElse(1);
        if (start < 1) {
            return Output.failure(path, "start_line must be >= 1");
        }
        if (input.end_line().isPresent() && input.end_line().orElseThrow() < 1) {
            return Output.failure(path, "end_line must be >= 1");
        }
        if (input.end_line().isPresent() && start > input.end_line().orElseThrow()) {
            return Output.failure(path, "start_line > end_line, no lines in range");
        }
        byte[] bytes;
        try {
            WorkspaceBinding binding =
                    bindings.requireThreadWorkspace(ThreadOwners.of(threadId), threadId);
            bytes = workspace.readVerifiedBytes(binding.ownerId(), binding.workspaceId(), path);
        } catch (WorkspaceException failure) {
            return switch (failure.error()) {
                case WORKSPACE_NOT_FOUND, FILE_NOT_FOUND ->
                        Output.failure(path, "file not found in this conversation: " + path);
                case INVALID_PATH, PATH_ESCAPE -> Output.failure(path,
                        "path must be a file below /mnt/user-data/uploads/, /mnt/user-data/workspace/ "
                                + "or /mnt/user-data/outputs/");
                case FILE_TOO_LARGE -> Output.failure(path, "file exceeds the readable size limit");
                default -> throw failure;
            };
        }
        UploadFileFacts facts = UploadFileFacts.of(bytes);
        if (facts.text().isEmpty()) {
            return new Output(path, bytes.length, facts.sha256(), facts.encoding(), 0, 0, 0, false, "",
                    "cannot read '" + path + "' as text \u2014 it appears to be a binary file (e.g. .xlsx, .pdf, or an image). "
                            + "read_file only supports UTF-8 text. Use bash with a suitable library instead (pandas/openpyxl for spreadsheets), or view_image for images.");
        }
        String text = facts.text().orElseThrow();
        // Frozen ReadBeforeWriteMiddleware stamps the hash of every successful read_file result;
        // the write tools consult the same mark before changing an existing file.
        readState.recordRead(RestrictedWorkspaceService.normalizeLogicalPath(path), facts.sha256());
        String[] lines = text.split("(?<=\n)", -1);
        int total = text.isEmpty() ? 0 : (text.endsWith("\n") ? lines.length - 1 : lines.length);
        if (start > Math.max(total, 1)) {
            return new Output(path, bytes.length, facts.sha256(), facts.encoding(), total, start, start - 1,
                    false, "", "start_line " + start + " is beyond the last line " + total);
        }
        int requestedEnd = Math.min(input.end_line().orElse(total), total);
        StringBuilder window = new StringBuilder();
        int used = 0;
        int end = start - 1;
        boolean truncated = false;
        for (int line = start; line <= requestedEnd; line++) {
            String value = lines[line - 1];
            int size = value.getBytes(StandardCharsets.UTF_8).length;
            if (used + size > MAX_RESULT_BYTES) {
                truncated = true;
                if (line == start) {
                    window.append(prefixWithin(value, MAX_RESULT_BYTES));
                    end = start;
                }
                break;
            }
            window.append(value);
            used += size;
            end = line;
        }
        return new Output(path, bytes.length, facts.sha256(), facts.encoding(), total, start, end, truncated,
                window.toString(), "");
    }

    @Override
    public String summarize(Output output) {
        if (!output.error().isEmpty()) {
            return "Error: " + output.error() + "\npath=" + output.path()
                    + (output.sha256().isEmpty() ? "" : " sha256=" + output.sha256() + " bytes=" + output.size())
                    + (output.encoding().isEmpty() ? "" : " encoding=" + output.encoding());
        }
        StringBuilder summary = new StringBuilder()
                .append("path=").append(output.path())
                .append(" sha256=").append(output.sha256())
                .append(" bytes=").append(output.size())
                .append(" encoding=").append(output.encoding())
                .append(" lines=").append(output.startLine()).append('-').append(output.endLine())
                .append(" of ").append(output.totalLines())
                .append(" truncated=").append(output.truncated());
        if (output.truncated()) {
            summary.append(" next_start_line=").append(output.endLine() + 1);
        }
        summary.append('\n')
                .append(output.content());
        if (output.truncated()) {
            if (!output.content().endsWith("\n")) summary.append('\n');
            summary.append("[truncated=true: continue with start_line=")
                    .append(output.endLine() + 1).append(']');
        }
        return summary.toString();
    }

    private static String prefixWithin(String value, int maxBytes) {
        StringBuilder prefix = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            int size = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (used + size > maxBytes) break;
            prefix.appendCodePoint(codePoint);
            used += size;
            offset += Character.charCount(codePoint);
        }
        return prefix.toString();
    }

    public record Input(
            String path, Optional<String> description, Optional<Integer> start_line, Optional<Integer> end_line) {
        public Input {
            Objects.requireNonNull(path, "path");
            description = Objects.requireNonNull(description, "description");
            start_line = Objects.requireNonNull(start_line, "start_line");
            end_line = Objects.requireNonNull(end_line, "end_line");
        }
    }

    public record Output(
            String path,
            long size,
            String sha256,
            String encoding,
            int totalLines,
            int startLine,
            int endLine,
            boolean truncated,
            String content,
            String error) {
        static Output failure(String path, String error) {
            return new Output(path, 0, "", "", 0, 0, 0, false, "", error);
        }
    }
}

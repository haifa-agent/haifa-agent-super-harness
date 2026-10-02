package io.haifa.agent.superharness.server.tool.file;

import io.haifa.agent.artifact.Artifact;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.superharness.server.api.DeerFlowBootstrapApi;
import io.haifa.agent.superharness.server.tool.ToolDescriptions;
import io.haifa.agent.superharness.server.tool.clarification.ClarificationBoundaryRegistry;
import io.haifa.agent.superharness.workspace.ArtifactPublishContext;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceBinding;
import io.haifa.agent.superharness.workspace.WorkspaceError;
import io.haifa.agent.superharness.workspace.WorkspaceException;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread-bound implementation of frozen DeerFlow's sandbox {@code str_replace}.
 *
 * <p>READ-BEFORE-WRITE applies: the current file must have been read with {@code read_file} and the
 * write is committed with the workspace hash-CAS, so a concurrent change blocks the edit. The
 * frozen implementation replaces only the first occurrence when {@code replace_all=False} (despite
 * its docstring), and SH matches the implementation.
 */
public final class ProductionStrReplaceTool
        implements JavaTool<ProductionStrReplaceTool.Input, ProductionStrReplaceTool.Output> {
    private static final String SDK_LOCAL_PRINCIPAL = "default-public-user";
    private static final JavaToolSpec<Input, Output> SPEC =
            JavaToolSpec.builder("str_replace", Input.class, Output.class)
                    .title("Replace a substring in a workspace file")
                    .description(ToolDescriptions.load("str_replace"))
                    .sideEffects(ToolSideEffect.FILE_WRITE)
                    .build();

    private final String threadId;
    private final String artifactProjectRef;
    private final PersistentProjectWorkspaceService bindings;
    private final RestrictedWorkspaceService workspace;
    private final FileToolSession readState;

    public ProductionStrReplaceTool(
            String threadId,
            String artifactProjectRef,
            PersistentProjectWorkspaceService bindings,
            RestrictedWorkspaceService workspace,
            FileToolSession readState) {
        this.threadId = Objects.requireNonNull(threadId, "threadId");
        this.artifactProjectRef = Objects.requireNonNull(artifactProjectRef, "artifactProjectRef");
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
            throw new IllegalArgumentException("str_replace requires the local Thread owner");
        }
        context.cancellation().throwIfCancellationRequested();
        if (!Instant.now().isBefore(context.deadline())) {
            throw new IllegalStateException("str_replace deadline has expired");
        }
        if (ClarificationBoundaryRegistry.active(context.runId().value())) {
            return Output.failure(input.path(), "replace", ClarificationBoundaryRegistry.DISCARD_MESSAGE);
        }
        String path = input.path();
        String normalized;
        try {
            normalized = RestrictedWorkspaceService.normalizeLogicalPath(path);
        } catch (WorkspaceException failure) {
            return Output.failure(path, "replace", pathError(path, failure));
        }
        WorkspaceBinding binding = bindings.requireThreadWorkspace(DeerFlowBootstrapApi.DEFAULT_USER_ID, threadId);
        byte[] current;
        try {
            current = workspace.readVerifiedBytes(binding.ownerId(), binding.workspaceId(), path);
        } catch (WorkspaceException failure) {
            if (FileToolSupport.missing(failure)) {
                return Output.failure(path, "replace", "File not found: " + path);
            }
            return Output.failure(path, "replace", pathError(path, failure));
        }
        String currentHash = UploadFileFacts.of(current).sha256();
        if (!readState.matchesRead(normalized, currentHash)) {
            return Output.failure(path, "replace", ProductionWriteFileTool.readBeforeWriteError(path));
        }
        Optional<String> decoded = strictUtf8(current);
        if (decoded.isEmpty()) {
            return Output.failure(path, "replace", "Unexpected error replacing string: file is not valid UTF-8 text");
        }
        String text = decoded.orElseThrow();
        if (input.old_str().isEmpty()) {
            return new Output(path, "OK", "", 0, "", "", "");
        }
        if (!text.contains(input.old_str())) {
            return Output.failure(path, "replace", "String to replace not found in file: " + path);
        }
        String replaced = input.replace_all().orElse(false)
                ? text.replace(input.old_str(), input.new_str())
                : text.replaceFirst(java.util.regex.Pattern.quote(input.old_str()),
                        java.util.regex.Matcher.quoteReplacement(input.new_str()));
        byte[] next = replaced.getBytes(StandardCharsets.UTF_8);
        try {
            workspace.writeWorkingFile(
                    binding.ownerId(), binding.workspaceId(), path, next, Optional.of(currentHash));
        } catch (WorkspaceException failure) {
            if (failure.error() == WorkspaceError.HASH_CONFLICT) {
                return Output.failure(path, "replace", ProductionWriteFileTool.readBeforeWriteError(path));
            }
            return Output.failure(path, "replace", pathError(path, failure));
        }
        readState.invalidate(normalized);
        var sessionId = context.sessionId()
                .orElseThrow(() -> new IllegalStateException("str_replace requires a trusted Session identity"));
        Artifact artifact = workspace.publish(
                binding.ownerId(),
                binding.workspaceId(),
                path,
                leaf(path),
                "workspace-file",
                "text/plain; charset=utf-8",
                new ArtifactPublishContext(
                        artifactProjectRef,
                        context.runId().value(),
                        sessionId.value(),
                        context.toolCallId().value(),
                        "local-owner-download"));
        return new Output(
                artifact.provenance().sourceLogicalPath(),
                "OK",
                artifact.payload().sha256(),
                artifact.payload().byteCount(),
                artifact.id().value(),
                artifact.provenance().executionRef(),
                "");
    }

    @Override
    public String summarize(Output output) {
        return output.error().isEmpty() ? "OK" : "Error: " + output.error();
    }

    private static Optional<String> strictUtf8(byte[] bytes) {
        try {
            return Optional.of(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString());
        } catch (java.nio.charset.CharacterCodingException malformed) {
            return Optional.empty();
        }
    }

    private static String leaf(String logicalPath) {
        int separator = logicalPath.lastIndexOf('/');
        return separator < 0 ? logicalPath : logicalPath.substring(separator + 1);
    }

    private static String pathError(String path, WorkspaceException failure) {
        return switch (failure.error()) {
            case IMMUTABLE_UPLOAD -> "uploads are read-only; replace in workspace/ or outputs/";
            case INVALID_PATH, PATH_ESCAPE ->
                    path + " is not a writable path under /mnt/user-data/workspace/ or /mnt/user-data/outputs/";
            case FILE_NOT_FOUND, WORKSPACE_NOT_FOUND -> "File not found: " + path;
            default -> "Unexpected error replacing string: " + failure.getMessage();
        };
    }

    public record Input(
            String path,
            String old_str,
            String new_str,
            Optional<String> description,
            Optional<Boolean> replace_all) {
        public Input {
            Objects.requireNonNull(path, "path");
            old_str = Objects.requireNonNull(old_str, "old_str");
            new_str = Objects.requireNonNull(new_str, "new_str");
            description = Objects.requireNonNull(description, "description");
            replace_all = Objects.requireNonNull(replace_all, "replace_all");
        }
    }

    public record Output(
            String path, String message, String artifactSha256, long size, String artifactId,
            String artifactExecutionRef, String error) {
        static Output failure(String path, String message, String error) {
            return new Output(path, message, "", 0, "", "", error);
        }
    }
}

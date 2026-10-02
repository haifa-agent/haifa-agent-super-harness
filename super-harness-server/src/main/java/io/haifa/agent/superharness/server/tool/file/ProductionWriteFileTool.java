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
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread-bound implementation of frozen DeerFlow's sandbox {@code write_file}.
 *
 * <p>Defaults to overwriting an existing target; {@code append=True} concatenates. An existing
 * target (including append) must have been read with {@code read_file} since its newest change,
 * enforced here against the shared {@link FileToolSession} and committed with the workspace
 * hash-CAS write. {@code uploads/} stays immutable; new files may only be created below
 * {@code workspace/} or {@code outputs/}.
 */
public final class ProductionWriteFileTool
        implements JavaTool<ProductionWriteFileTool.Input, ProductionWriteFileTool.Output> {
    private static final int MAX_SINGLE_WRITE_BYTES = 80 * 1024;
    private static final String SDK_LOCAL_PRINCIPAL = "default-public-user";
    private static final JavaToolSpec<Input, Output> SPEC = JavaToolSpec.builder("write_file", Input.class, Output.class)
            .title("Write a workspace file")
            .description(ToolDescriptions.load("write_file"))
            .sideEffects(ToolSideEffect.FILE_WRITE)
            .build();

    private final String threadId;
    private final String artifactProjectRef;
    private final PersistentProjectWorkspaceService bindings;
    private final RestrictedWorkspaceService workspace;
    private final FileToolSession readState;

    public ProductionWriteFileTool(
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
            throw new IllegalArgumentException("write_file requires the local Thread owner");
        }
        context.cancellation().throwIfCancellationRequested();
        if (!Instant.now().isBefore(context.deadline())) {
            throw new IllegalStateException("write_file deadline has expired");
        }
        if (ClarificationBoundaryRegistry.active(context.runId().value())) {
            return Output.failure(input.path(), ClarificationBoundaryRegistry.DISCARD_MESSAGE);
        }
        String path = input.path();
        boolean append = input.append().orElse(false);
        byte[] content = input.content().getBytes(StandardCharsets.UTF_8);
        if (!append && content.length > MAX_SINGLE_WRITE_BYTES) {
            return Output.failure(path, "write_file content (" + content.length + " bytes) exceeds the "
                    + MAX_SINGLE_WRITE_BYTES + "-byte single-call limit. Split the content into smaller "
                    + "pieces: either (a) write the first section now, then use `str_replace` for further "
                    + "edits, or (b) call write_file again with append=True carrying the next section. See "
                    + "SIZE POLICY in the tool docstring or issue #3189 for the rationale.");
        }
        String normalized;
        try {
            normalized = RestrictedWorkspaceService.normalizeLogicalPath(path);
        } catch (WorkspaceException failure) {
            return Output.failure(path, writePathError(path, failure));
        }
        if (normalized.startsWith("uploads/")) {
            return Output.failure(path, "uploads are read-only; write to workspace/ or outputs/");
        }
        WorkspaceBinding binding;
        try {
            binding = bindings.requireThreadWorkspace(DeerFlowBootstrapApi.DEFAULT_USER_ID, threadId);
        } catch (WorkspaceException failure) {
            return Output.failure(path, "No workspace is bound to this Thread");
        }
        byte[] current = null;
        boolean exists = false;
        try {
            current = workspace.readVerifiedBytes(binding.ownerId(), binding.workspaceId(), path);
            exists = true;
        } catch (WorkspaceException failure) {
            if (!FileToolSupport.missing(failure)) {
                return Output.failure(path, writePathError(path, failure));
            }
        }
        String currentHash = exists ? UploadFileFacts.of(current).sha256() : null;
        if (exists && !readState.matchesRead(normalized, currentHash)) {
            return Output.failure(path, readBeforeWriteError(path));
        }
        var sessionId = context.sessionId()
                .orElseThrow(() -> new IllegalStateException("write_file requires a trusted Session identity"));
        String runId = context.runId().value();
        String toolCallId = context.toolCallId().value();
        ArtifactPublishContext publishContext = new ArtifactPublishContext(
                artifactProjectRef, runId, sessionId.value(), toolCallId, "local-owner-download");
        Artifact artifact;
        if (exists) {
            byte[] next = append ? concatenate(current, content) : content;
            try {
                workspace.writeWorkingFile(
                        binding.ownerId(), binding.workspaceId(), path, next, Optional.of(currentHash));
            } catch (WorkspaceException failure) {
                if (failure.error() == WorkspaceError.HASH_CONFLICT) {
                    return Output.failure(path, readBeforeWriteError(path));
                }
                return Output.failure(path, writePathError(path, failure));
            }
            readState.invalidate(normalized);
            artifact = workspace.publish(
                    binding.ownerId(),
                    binding.workspaceId(),
                    path,
                    artifactTitle(path),
                    "workspace-file",
                    "text/plain; charset=utf-8",
                    publishContext);
        } else {
            try {
                artifact = workspace.createAndPublishWorkingFile(
                        binding.ownerId(),
                        binding.workspaceId(),
                        path,
                        content,
                        artifactTitle(path),
                        "workspace-file",
                        "text/plain; charset=utf-8",
                        publishContext);
            } catch (WorkspaceException failure) {
                return Output.failure(path, writePathError(path, failure));
            }
        }
        String sourcePath = artifact.provenance().sourceLogicalPath();
        String artifactSha256 = artifact.payload().sha256();
        String sha256 = artifactSha256.substring("sha256:".length());
        return new Output(
                sourcePath,
                artifact.payload().byteCount(),
                sha256,
                runId,
                threadId,
                artifact.id().value(),
                artifact.version().value(),
                artifact.type().value(),
                artifactSha256,
                artifact.payload().byteCount(),
                artifact.payload().mediaType(),
                artifact.provenance().project().projectId(),
                artifact.provenance().workspaceRef(),
                artifact.provenance().sessionId().value(),
                artifact.provenance().executionRef(),
                "");
    }

    @Override
    public String summarize(Output output) {
        if (!output.error().isEmpty()) {
            return "Error: " + output.error();
        }
        return "Created " + output.path() + " (" + output.size() + " bytes, sha256:" + output.sha256()
                + ", artifact:" + output.artifactId() + ")";
    }

    private static byte[] concatenate(byte[] head, byte[] tail) {
        ByteArrayOutputStream joined = new ByteArrayOutputStream(head.length + tail.length);
        joined.writeBytes(head);
        joined.writeBytes(tail);
        return joined.toByteArray();
    }

    static String readBeforeWriteError(String path) {
        return "Cannot write to '" + path + "' because its current version has not been read with "
                + "read_file since it last changed. Read the file first, then retry.";
    }

    private static String writePathError(String path, WorkspaceException failure) {
        return switch (failure.error()) {
            case IMMUTABLE_UPLOAD -> "uploads are read-only; write to workspace/ or outputs/";
            case INVALID_PATH, PATH_ESCAPE ->
                    path + " is not a writable path under /mnt/user-data/workspace/ or /mnt/user-data/outputs/";
            case FILE_TOO_LARGE -> "write_file content exceeds the workspace size limit";
            case HASH_CONFLICT -> readBeforeWriteError(path);
            default -> "Failed to write file '" + path + "': " + failure.getMessage();
        };
    }

    private static String artifactTitle(String logicalPath) {
        int separator = logicalPath.lastIndexOf('/');
        return separator < 0 ? logicalPath : logicalPath.substring(separator + 1);
    }

    public record Input(String path, String content, Optional<String> description, Optional<Boolean> append) {
        public Input {
            Objects.requireNonNull(path, "path");
            content = Objects.requireNonNull(content, "content");
            description = Objects.requireNonNull(description, "description");
            append = Objects.requireNonNull(append, "append");
        }
    }

    public record Output(
            String path,
            long size,
            String sha256,
            String runId,
            String threadId,
            String artifactId,
            long artifactVersion,
            String artifactType,
            String artifactSha256,
            long artifactByteCount,
            String artifactMediaType,
            String artifactProjectRef,
            String artifactWorkspaceRef,
            String artifactSessionId,
            String artifactExecutionRef,
            String error) {
        static Output failure(String path, String error) {
            return new Output(path, 0, "", "", "", "", 0, "", "", 0, "", "", "", "", "", error);
        }
    }
}

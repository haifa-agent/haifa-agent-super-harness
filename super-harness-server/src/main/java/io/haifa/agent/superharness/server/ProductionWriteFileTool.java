package io.haifa.agent.superharness.server;

import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceBinding;
import io.haifa.agent.superharness.workspace.WorkspaceFileSnapshot;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** One Thread-bound production Tool; the model never supplies owner or Thread identity. */
public final class ProductionWriteFileTool implements JavaTool<ProductionWriteFileTool.Input, ProductionWriteFileTool.Output> {
    private static final int MAX_SINGLE_WRITE_BYTES = 80 * 1024;
    private static final String SDK_LOCAL_PRINCIPAL = "default-public-user";
    private static final JavaToolSpec<Input, Output> SPEC = JavaToolSpec.builder("write_file", Input.class, Output.class)
            .title("Create a workspace file")
            .description("Create a new UTF-8 file under /mnt/user-data/workspace/ or /mnt/user-data/outputs/. "
                    + "Existing files and append are not supported. Maximum content is 80 KiB per call.")
            .sideEffects(ToolSideEffect.FILE_WRITE)
            .build();

    private final String threadId;
    private final PersistentProjectWorkspaceService bindings;
    private final RestrictedWorkspaceService workspace;

    ProductionWriteFileTool(
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
            throw new IllegalArgumentException("write_file requires the local Thread owner");
        }
        if (input.append().orElse(false)) {
            throw new IllegalArgumentException("write_file append is not available in this profile");
        }
        if (input.content() == null) {
            throw new IllegalArgumentException("write_file content is required");
        }
        byte[] bytes = input.content().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_SINGLE_WRITE_BYTES) {
            throw new IllegalArgumentException("write_file content exceeds the 80 KiB single-call limit");
        }
        context.cancellation().throwIfCancellationRequested();
        if (!Instant.now().isBefore(context.deadline())) {
            throw new IllegalStateException("write_file deadline has expired");
        }
        WorkspaceBinding binding = bindings.requireThreadWorkspace(DeerFlowBootstrapApi.DEFAULT_USER_ID, threadId);
        // The SDK owns Run admission and invokes this Tool from this Thread's frozen catalog.
        String runId = context.runId().value();
        WorkspaceFileSnapshot created = workspace.createWorkingFile(
                binding.ownerId(), binding.workspaceId(), input.path(), bytes);
        return new Output("/mnt/user-data/" + created.logicalPath(), created.totalByteCount(), created.sha256(), runId);
    }

    @Override
    public String summarize(Output output) {
        return "Created " + output.path() + " (" + output.size() + " bytes, sha256:" + output.sha256() + ")";
    }

    public record Input(String path, String content, Optional<String> description, Optional<Boolean> append) {
        public Input {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(content, "content");
            description = Objects.requireNonNull(description, "description");
            append = Objects.requireNonNull(append, "append");
        }
    }

    public record Output(String path, long size, String sha256, String runId) {}
}

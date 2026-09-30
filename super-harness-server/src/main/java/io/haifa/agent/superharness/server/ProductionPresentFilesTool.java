package io.haifa.agent.superharness.server;

import io.haifa.agent.artifact.Artifact;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.superharness.workspace.ArtifactPublishContext;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceBinding;
import io.haifa.agent.superharness.workspace.WorkspaceException;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Thread-bound implementation of frozen DeerFlow's builtin {@code present_files}.
 *
 * <p>Only files below {@code /mnt/user-data/outputs} can be presented. The frozen tool validates the
 * virtual path prefix and records the normalized paths in {@code ThreadState.artifacts}; SH lists
 * outputs automatically, so this tool keeps that behaviour and is idempotent for files a write tool
 * already published. Unlike frozen, SH also requires each presented file to exist and be readable
 * (see the W04-task-8 report "known differences"), and only a fully valid batch succeeds.
 *
 * <p>When a presented file exists but has no published Artifact yet (for example a future producer
 * other than {@code write_file}/{@code str_replace}), this tool publishes it so the frozen UI's
 * artifact list stays consistent. It declares {@link ToolSideEffect#FILE_READ}: it never changes
 * workspace bytes, and publishing an immutable copy of already-present bytes must not force an
 * approval.
 */
public final class ProductionPresentFilesTool
        implements JavaTool<ProductionPresentFilesTool.Input, ProductionPresentFilesTool.Output> {
    static final String OUTPUTS_LOGICAL_PREFIX = "outputs/";
    static final String SUCCESS_TEXT = "Successfully presented files";
    private static final String SDK_LOCAL_PRINCIPAL = "default-public-user";
    private static final JavaToolSpec<Input, Output> SPEC =
            JavaToolSpec.builder("present_files", Input.class, Output.class)
                    .title("Present output files to the user")
                    .description(ToolDescriptions.load("present_files"))
                    .sideEffects(ToolSideEffect.FILE_READ)
                    .build();

    private final String threadId;
    private final String artifactProjectRef;
    private final PersistentProjectWorkspaceService bindings;
    private final RestrictedWorkspaceService workspace;

    ProductionPresentFilesTool(
            String threadId,
            String artifactProjectRef,
            PersistentProjectWorkspaceService bindings,
            RestrictedWorkspaceService workspace) {
        this.threadId = Objects.requireNonNull(threadId, "threadId");
        this.artifactProjectRef = Objects.requireNonNull(artifactProjectRef, "artifactProjectRef");
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
            throw new IllegalArgumentException("present_files requires the local Thread owner");
        }
        context.cancellation().throwIfCancellationRequested();
        if (!Instant.now().isBefore(context.deadline())) {
            throw new IllegalStateException("present_files deadline has expired");
        }
        if (ClarificationBoundaryRegistry.active(context.runId().value())) {
            return Output.failure(ClarificationBoundaryRegistry.DISCARD_MESSAGE);
        }
        List<String> requested = input.filepaths();
        if (requested == null || requested.isEmpty()) {
            return Output.failure("filepaths must contain at least one path");
        }
        WorkspaceBinding binding = bindings.requireThreadWorkspace(DeerFlowBootstrapApi.DEFAULT_USER_ID, threadId);
        // Validate the whole batch first: a single bad path fails the call without presenting a part.
        List<PresentedFile> presented = new ArrayList<>(requested.size());
        for (String filepath : requested) {
            String normalized;
            try {
                normalized = RestrictedWorkspaceService.normalizeLogicalPath(filepath);
            } catch (WorkspaceException invalid) {
                return Output.failure(onlyOutputsError(filepath));
            }
            if (!normalized.startsWith(OUTPUTS_LOGICAL_PREFIX)
                    || normalized.length() == OUTPUTS_LOGICAL_PREFIX.length()) {
                return Output.failure(onlyOutputsError(filepath));
            }
            try {
                workspace.readVerifiedBytes(binding.ownerId(), binding.workspaceId(), filepath);
            } catch (WorkspaceException failure) {
                if (FileToolSupport.missing(failure)) {
                    return Output.failure("Cannot present '" + filepath + "': file does not exist");
                }
                return Output.failure("Cannot present '" + filepath + "': " + failure.getMessage());
            }
            presented.add(new PresentedFile(filepath, normalized));
        }
        String sessionId = context.sessionId()
                .orElseThrow(() -> new IllegalStateException("present_files requires a trusted Session identity"))
                .value();
        Set<String> publishedSources = publishedSources(binding);
        try {
            for (PresentedFile file : presented) {
                if (!publishedSources.contains(FileToolSupport.virtualPath(file.normalized()))) {
                    publish(file, binding, context, sessionId);
                }
            }
        } catch (WorkspaceException failure) {
            return Output.failure("Cannot present '" + failure.getMessage() + "'");
        }
        return Output.success(presented.stream()
                .map(file -> FileToolSupport.virtualPath(file.normalized()))
                .toList());
    }

    @Override
    public String summarize(Output output) {
        return output.error().isEmpty() ? SUCCESS_TEXT : "Error: " + output.error();
    }

    private Set<String> publishedSources(WorkspaceBinding binding) {
        Set<String> sources = new HashSet<>();
        for (Artifact artifact : workspace.findArtifacts(
                binding.ownerId(), binding.workspaceId(), artifactProjectRef)) {
            sources.add(artifact.provenance().sourceLogicalPath());
        }
        return sources;
    }

    private Artifact publish(
            PresentedFile file, WorkspaceBinding binding, JavaToolContext context, String sessionId) {
        String logicalPath = file.normalized();
        return workspace.publish(
                binding.ownerId(),
                binding.workspaceId(),
                logicalPath,
                leafName(logicalPath),
                "workspace-file",
                "text/plain; charset=utf-8",
                new ArtifactPublishContext(
                        artifactProjectRef,
                        context.runId().value(),
                        sessionId,
                        context.toolCallId().value(),
                        "local-owner-download"));
    }

    private static String onlyOutputsError(String filepath) {
        return "Only files in " + FileToolSupport.VIRTUAL_PREFIX + "outputs can be presented: " + filepath;
    }

    private static String leafName(String logicalPath) {
        int separator = logicalPath.lastIndexOf('/');
        return separator < 0 ? logicalPath : logicalPath.substring(separator + 1);
    }

    private record PresentedFile(String filepath, String normalized) {}

    public record Input(List<String> filepaths) {
        public Input {
            Objects.requireNonNull(filepaths, "filepaths");
        }
    }

    public record Output(List<String> paths, String message, String error) {
        static Output success(List<String> paths) {
            return new Output(List.copyOf(paths), SUCCESS_TEXT, "");
        }

        static Output failure(String error) {
            return new Output(List.of(), "", error);
        }
    }
}

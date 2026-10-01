package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.artifact.Artifact;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.runtime.api.ToolCallView;
import io.haifa.agent.runtime.api.ToolDataView;
import io.haifa.agent.runtime.api.ToolResultView;
import io.haifa.agent.runtime.api.display.BoundedText;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceBinding;
import io.haifa.agent.superharness.workspace.WorkspaceChangeLimits;
import io.haifa.agent.superharness.workspace.WorkspaceLimits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Behaviour coverage for the frozen DeerFlow {@code present_files} tool (W04-task-8). */
class PresentFilesToolTest {
    private static final String OUTPUT_PATH = "/mnt/user-data/outputs/report.md";

    @TempDir Path dataRoot;

    @Test
    void presentingAnAlreadyPublishedOutputSucceedsWithoutDuplicatingTheArtifactList() {
        Fixture fixture = new Fixture(dataRoot.resolve("workspace"), "thread-present-published");
        JavaToolContext trusted = context();
        fixture.write.invoke(
                new ProductionWriteFileTool.Input(
                        OUTPUT_PATH, "report body", Optional.empty(), Optional.empty()),
                trusted);

        var presented = fixture.present.invoke(
                new ProductionPresentFilesTool.Input(List.of(OUTPUT_PATH)), trusted);

        assertThat(presented.error()).isEmpty();
        assertThat(fixture.present.summarize(presented)).isEqualTo("Successfully presented files");
        assertThat(presented.paths()).containsExactly(OUTPUT_PATH);
        // The publish is idempotent: the presented file keeps its single write_file artifact.
        assertThat(fixture.artifacts()).hasSize(1);

        assertThat(DeerFlowToolTranscriptProjection.artifactPaths(List.of(
                        writeCall(OUTPUT_PATH, "artifact-1"),
                        presentCall(List.of(OUTPUT_PATH)))))
                .containsExactly(OUTPUT_PATH);
    }

    @Test
    void presentingAnUnpublishedOutputFilePublishesItOnce() {
        Fixture fixture = new Fixture(dataRoot.resolve("workspace"), "thread-present-unpublished");
        fixture.createOutputFile("made-by-other-producer.txt", "bytes");
        assertThat(fixture.artifacts()).isEmpty();

        var presented = fixture.present.invoke(
                new ProductionPresentFilesTool.Input(
                        List.of("/mnt/user-data/outputs/made-by-other-producer.txt")),
                context());

        assertThat(presented.error()).isEmpty();
        assertThat(fixture.present.summarize(presented)).isEqualTo("Successfully presented files");
        assertThat(fixture.artifacts()).hasSize(1);
        assertThat(fixture.artifacts().get(0).provenance().sourceLogicalPath())
                .isEqualTo("/mnt/user-data/outputs/made-by-other-producer.txt");
    }

    @Test
    void rejectsPathsOutsideOutputsAndMissingFilesWithoutChangingTheList() {
        Fixture fixture = new Fixture(dataRoot.resolve("workspace"), "thread-present-reject");
        JavaToolContext trusted = context();
        fixture.write.invoke(
                new ProductionWriteFileTool.Input(
                        "/mnt/user-data/workspace/notes.md", "workspace", Optional.empty(), Optional.empty()),
                trusted);
        fixture.upload("y.txt", "upload");

        // write_file published notes.md; rejecting a present call must not change the list at all.
        assertThat(fixture.artifacts()).hasSize(1);
        for (String rejected : new String[] {
            "/mnt/user-data/workspace/notes.md",
            "/mnt/user-data/uploads/y.txt",
            "../outside.md",
            "C:/Windows/win.ini",
            "/mnt/user-data/outputs/missing.md"
        }) {
            var result = fixture.present.invoke(
                    new ProductionPresentFilesTool.Input(List.of(rejected)), trusted);
            assertThat(result.error()).as(rejected).isNotEmpty();
            assertThat(result.paths()).as(rejected).isEmpty();
            assertThat(fixture.present.summarize(result)).as(rejected).startsWith("Error: ");
            assertThat(fixture.artifacts()).as(rejected).hasSize(1);
        }
    }

    @Test
    void aSingleIllegalPathFailsTheWholeBatchWithoutPartialPublishing() {
        Fixture fixture = new Fixture(dataRoot.resolve("workspace"), "thread-present-batch");
        JavaToolContext trusted = context();
        fixture.createOutputFile("good.txt", "good");

        var result = fixture.present.invoke(
                new ProductionPresentFilesTool.Input(
                        List.of("/mnt/user-data/outputs/good.txt", "/mnt/user-data/outputs/missing.txt")),
                trusted);

        assertThat(result.error()).isNotEmpty();
        assertThat(result.paths()).isEmpty();
        assertThat(fixture.present.summarize(result)).startsWith("Error: ");
        // Nothing in the batch was presented, so even the valid member was never published.
        assertThat(fixture.artifacts()).isEmpty();
    }

    private static ToolCallView writeCall(String path, String artifactId) {
        return completedCall("write_file", Map.of("path", path, "artifactId", artifactId));
    }

    private static ToolCallView presentCall(List<String> paths) {
        return completedCall("present_files", Map.of("paths", paths, "error", ""));
    }

    private static ToolCallView completedCall(String tool, Map<String, Object> values) {
        ToolResultView result = new ToolResultView(
                true, BoundedText.of("OK", 100, 100), new ToolDataView(values, false),
                List.of(), List.of(), false);
        return new ToolCallView(
                new ToolCallId("call-" + tool), new AgentRunId("run-1"), tool, "1.0.0",
                ToolDataView.empty(), ToolCallStatus.COMPLETED, Optional.of(result), Optional.empty(),
                Instant.now(), Optional.empty(), Optional.empty());
    }

    private static JavaToolContext context() {
        return new JavaToolContext(new AgentRunId("run-1"), Optional.of(new AgentSessionId("session-1")),
                new ToolCallId("tool-call-1"), new TenantRef("public"),
                new PrincipalRef("default-public-user", "user"),
                Instant.now().plusSeconds(60), Optional.empty(), () -> false, Map.of());
    }

    private static final class Fixture {
        private final RestrictedWorkspaceService workspace;
        private final WorkspaceBinding binding;
        private final String projectRef;
        private final ProductionWriteFileTool write;
        private final ProductionPresentFilesTool present;

        private Fixture(Path root, String threadId) {
            var bindings = new PersistentProjectWorkspaceService(
                    root, WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
            this.workspace = new RestrictedWorkspaceService(root, WorkspaceLimits.defaults());
            this.binding = bindings.bindThreadWorkspace("default", threadId);
            this.projectRef = "thread:" + threadId;
            FileToolSession session = new FileToolSession();
            this.write = new ProductionWriteFileTool(threadId, projectRef, bindings, workspace, session);
            this.present = new ProductionPresentFilesTool(threadId, projectRef, bindings, workspace);
        }

        private void createOutputFile(String name, String content) {
            workspace.createWorkingFile(binding.ownerId(), binding.workspaceId(),
                    "outputs/" + name, content.getBytes(StandardCharsets.UTF_8));
        }

        private void upload(String filename, String content) {
            workspace.upload(binding.ownerId(), binding.workspaceId(), filename,
                    content.getBytes(StandardCharsets.UTF_8), "text/plain");
        }

        private List<Artifact> artifacts() {
            return workspace.findArtifacts(binding.ownerId(), binding.workspaceId(), projectRef);
        }
    }
}

package io.haifa.agent.superharness.server.tool.clarification;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.superharness.server.tool.file.FileToolSession;
import io.haifa.agent.superharness.server.tool.file.ProductionWriteFileTool;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceChangeLimits;
import io.haifa.agent.superharness.workspace.WorkspaceLimits;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * W05-task-8 B: the shared SH workspace must not gain side effects from a sibling tool once the Run
 * has requested clarification at the boundary, while the {@code ask_clarification} tool itself still
 * returns its frozen payload.
 */
class ClarificationBoundaryRegistryTest {
    @TempDir Path dataRoot;

    @AfterEach
    void clearRegistry() {
        ClarificationBoundaryRegistry.resetForTests();
    }

    @Test
    void writeFileIsRefusedAndLeavesNoFileAfterTheClarificationBoundary() {
        String threadId = "thread-clarification-boundary";
        Path root = dataRoot.resolve("workspace");
        var bindings = new PersistentProjectWorkspaceService(
                root, WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
        var workspace = new RestrictedWorkspaceService(root, WorkspaceLimits.defaults());
        var binding = bindings.bindThreadWorkspace("default", threadId);
        var write = new ProductionWriteFileTool(
                threadId, "thread:" + threadId, bindings, workspace, new FileToolSession());

        ClarificationBoundaryRegistry.mark("run-clarified", "call-ask");
        var output = write.invoke(
                new ProductionWriteFileTool.Input(
                        "/mnt/user-data/outputs/after-clarification.txt",
                        "must-not-be-written",
                        Optional.empty(),
                        Optional.empty()),
                context("run-clarified"));

        assertThat(output.error()).isEqualTo(ClarificationBoundaryRegistry.DISCARD_MESSAGE);
        assertThat(write.summarize(output)).startsWith("Error: Discarded:");
        assertThat(workspace.findArtifacts(binding.ownerId(), binding.workspaceId(), "thread:" + threadId))
                .isEmpty();
        assertThat(root.resolve("workspaces").resolve(binding.workspaceId().value())
                .resolve("user-data/outputs/after-clarification.txt")).doesNotExist();
    }

    @Test
    void aDifferentRunKeepsWritingNormally() throws Exception {
        String threadId = "thread-other-run";
        Path root = dataRoot.resolve("workspace");
        var bindings = new PersistentProjectWorkspaceService(
                root, WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
        var workspace = new RestrictedWorkspaceService(root, WorkspaceLimits.defaults());
        var binding = bindings.bindThreadWorkspace("default", threadId);
        var write = new ProductionWriteFileTool(
                threadId, "thread:" + threadId, bindings, workspace, new FileToolSession());

        ClarificationBoundaryRegistry.mark("run-clarified", "call-ask");
        var output = write.invoke(
                new ProductionWriteFileTool.Input(
                        "/mnt/user-data/outputs/other-run.txt", "written", Optional.empty(), Optional.empty()),
                context("run-other"));

        assertThat(output.error()).isEmpty();
        assertThat(Files.readString(root.resolve("workspaces").resolve(binding.workspaceId().value())
                .resolve("user-data/outputs/other-run.txt"))).isEqualTo("written");
        assertThat(workspace.findArtifacts(binding.ownerId(), binding.workspaceId(), "thread:" + threadId))
                .hasSize(1);
    }

    @Test
    void clearingTheBoundaryStopsRefusingLaterWork() {
        ClarificationBoundaryRegistry.mark("run-cleared", "call-ask");
        assertThat(ClarificationBoundaryRegistry.active("run-cleared")).isTrue();
        ClarificationBoundaryRegistry.mark(null, "ignored");
        ClarificationBoundaryRegistry.mark("", "ignored");
        ClarificationBoundaryRegistry.clear(null);
        ClarificationBoundaryRegistry.clear("run-cleared");
        assertThat(ClarificationBoundaryRegistry.active("run-cleared")).isFalse();
    }

    private static JavaToolContext context(String runId) {
        return new JavaToolContext(new AgentRunId(runId), Optional.of(new AgentSessionId("session-1")),
                new ToolCallId("tool-call-1"), new TenantRef("public"),
                new PrincipalRef("default-public-user", "user"),
                Instant.now().plusSeconds(60), Optional.empty(), () -> false, Map.of());
    }
}

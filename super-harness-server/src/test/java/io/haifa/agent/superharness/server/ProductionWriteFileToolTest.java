package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceChangeLimits;
import io.haifa.agent.superharness.workspace.WorkspaceLimits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductionWriteFileToolTest {
    @TempDir Path dataRoot;

    @Test
    void writesOnlyInsideTheBoundThreadAndReportsExactBytes() {
        Fixture fixture = fixture("thread-1");
        String path = "/mnt/user-data/workspace/report.txt";
        var output = fixture.tool.invoke(input(path, "中文\n"), context("default-public-user", false, false));

        assertThat(output.path()).isEqualTo(path);
        assertThat(output.runId()).isEqualTo("run-1");
        byte[] saved = fixture.workspace.readVerifiedBytes("default", fixture.binding.workspaceId(), path);
        assertThat(saved).isEqualTo("中文\n".getBytes(StandardCharsets.UTF_8));
        assertThat(output.size()).isEqualTo(saved.length);
        assertThat(output.sha256()).hasSize(64);
        assertThat(fixture.tool.summarize(output)).contains(path, "sha256:" + output.sha256());
    }

    @Test
    void rejectsExistingUploadsEscapesAppendAndOversizeWithoutChangingFiles() {
        Fixture fixture = fixture("thread-2");
        var trusted = context("default-public-user", false, false);
        String path = "/mnt/user-data/outputs/report.md";
        fixture.tool.invoke(input(path, "original"), trusted);
        assertThatThrownBy(() -> fixture.tool.invoke(input(path, "replacement"), trusted))
                .hasMessageContaining("already exists");
        assertThat(new String(fixture.workspace.readVerifiedBytes("default", fixture.binding.workspaceId(), path),
                StandardCharsets.UTF_8)).isEqualTo("original");

        for (String rejected : new String[] {
                "/mnt/user-data/uploads/input.txt", "../outside.txt", "/etc/passwd",
                "C:/Windows/win.ini", "/mnt/user-data/workspace/../outside.txt",
                "workspace\\escape.txt"}) {
            assertThatThrownBy(() -> fixture.tool.invoke(input(rejected, "forbidden"), trusted))
                    .as(rejected).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> fixture.tool.invoke(
                new ProductionWriteFileTool.Input(path, "append", Optional.empty(), Optional.of(true)), trusted))
                .hasMessageContaining("append");
        assertThatThrownBy(() -> fixture.tool.invoke(input(
                "/mnt/user-data/workspace/oversize.txt", "界".repeat(30_000)), trusted))
                .hasMessageContaining("80 KiB");
    }

    @Test
    void rejectsWrongPrincipalCancellationAndExpiredDeadlineBeforeWrite() {
        Fixture fixture = fixture("thread-3");
        String path = "/mnt/user-data/workspace/blocked.txt";
        assertThatThrownBy(() -> fixture.tool.invoke(input(path, "no"), context("attacker", false, false)))
                .hasMessageContaining("owner");
        assertThatThrownBy(() -> fixture.tool.invoke(input(path, "no"), context("default-public-user", true, false)))
                .hasMessageContaining("cancelled");
        assertThatThrownBy(() -> fixture.tool.invoke(input(path, "no"), context("default-public-user", false, true)))
                .hasMessageContaining("deadline");
        assertThatThrownBy(() -> fixture.workspace.readVerifiedBytes("default", fixture.binding.workspaceId(), path))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsMissingThreadBindingInsteadOfCreatingOne() {
        Path root = dataRoot.resolve("workspace");
        var bindings = new PersistentProjectWorkspaceService(root,
                WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
        var workspace = new RestrictedWorkspaceService(root, WorkspaceLimits.defaults());
        var tool = new ProductionWriteFileTool("unknown-thread", bindings, workspace);
        assertThatThrownBy(() -> tool.invoke(
                input("/mnt/user-data/workspace/no.txt", "no"), context("default-public-user", false, false)))
                .isInstanceOf(RuntimeException.class);
        assertThat(Files.exists(root.resolve("workspaces"))).isTrue();
    }

    private Fixture fixture(String threadId) {
        Path root = dataRoot.resolve("workspace");
        var bindings = new PersistentProjectWorkspaceService(root,
                WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
        var workspace = new RestrictedWorkspaceService(root, WorkspaceLimits.defaults());
        var binding = bindings.bindThreadWorkspace("default", threadId);
        return new Fixture(workspace, binding, new ProductionWriteFileTool(threadId, bindings, workspace));
    }

    private static ProductionWriteFileTool.Input input(String path, String content) {
        return new ProductionWriteFileTool.Input(path, content, Optional.empty(), Optional.empty());
    }

    private static JavaToolContext context(String principal, boolean cancelled, boolean expired) {
        return new JavaToolContext(new AgentRunId("run-1"), Optional.of(new AgentSessionId("session-1")),
                new ToolCallId("tool-call-1"), new TenantRef("public"),
                new PrincipalRef(principal, "user"),
                expired ? Instant.now().minusSeconds(1) : Instant.now().plusSeconds(60),
                Optional.empty(), () -> cancelled, Map.of());
    }

    private record Fixture(
            RestrictedWorkspaceService workspace,
            io.haifa.agent.superharness.workspace.WorkspaceBinding binding,
            ProductionWriteFileTool tool) {}
}

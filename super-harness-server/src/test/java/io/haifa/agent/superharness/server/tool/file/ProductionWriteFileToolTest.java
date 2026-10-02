package io.haifa.agent.superharness.server.tool.file;

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
import io.haifa.agent.superharness.workspace.WorkspaceBinding;
import io.haifa.agent.superharness.workspace.WorkspaceChangeLimits;
import io.haifa.agent.superharness.workspace.WorkspaceLimits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Frozen write_file semantics: overwrite by default, append, and READ-BEFORE-WRITE (issue #3857). */
class ProductionWriteFileToolTest {
    @TempDir Path dataRoot;

    @Test
    void newFileWriteThenReadGatedOverwriteAndAppendRoundTrip() {
        Fixture fixture = new Fixture(dataRoot.resolve("workspace"), "thread-1");
        String path = "/mnt/user-data/outputs/report.txt";
        JavaToolContext trusted = context("default-public-user", false, false);

        var created = fixture.write.invoke(input(path, "original"), trusted);
        assertThat(created.error()).isEmpty();
        assertThat(created.path()).isEqualTo(path);
        assertThat(created.artifactId()).isNotBlank();
        assertThat(fixture.read.invoke(readInput(path), trusted).error()).isEmpty();

        var overwrite = fixture.write.invoke(input(path, "replaced"), trusted);
        assertThat(overwrite.error()).isEmpty();
        assertThat(fixture.bytes(path)).isEqualTo("replaced");

        // The overwrite invalidated the read mark: a second blind write is blocked.
        var blocked = fixture.write.invoke(input(path, "sneaky"), trusted);
        assertThat(blocked.error()).contains("has not been read");
        assertThat(fixture.bytes(path)).isEqualTo("replaced");

        assertThat(fixture.read.invoke(readInput(path), trusted).error()).isEmpty();
        var appended = fixture.write.invoke(appendInput(path, " tail"), trusted);
        assertThat(appended.error()).isEmpty();
        assertThat(fixture.bytes(path)).isEqualTo("replaced tail");
    }

    @Test
    void appendToMissingFileCreatesItAndStillRequiresReadOnceItExists() {
        Fixture fixture = new Fixture(dataRoot.resolve("workspace"), "thread-append-new");
        String path = "/mnt/user-data/workspace/new.txt";
        var created = fixture.write.invoke(appendInput(path, "first"), context("default-public-user", false, false));
        assertThat(created.error()).isEmpty();
        assertThat(fixture.bytes(path)).isEqualTo("first");

        var blocked = fixture.write.invoke(appendInput(path, "second"), context("default-public-user", false, false));
        assertThat(blocked.error()).contains("has not been read");
        assertThat(fixture.bytes(path)).isEqualTo("first");
    }

    @Test
    void readBeforeWriteBlocksWhenTheFileChangedAfterTheRead() {
        Fixture fixture = new Fixture(dataRoot.resolve("workspace"), "thread-changed");
        String path = "/mnt/user-data/workspace/changing.txt";
        JavaToolContext trusted = context("default-public-user", false, false);
        fixture.write.invoke(input(path, "version-1"), trusted);
        fixture.read.invoke(readInput(path), trusted);

        // A different writer (another approval path) swaps the bytes under the model.
        byte[] replacement = "version-2".getBytes(StandardCharsets.UTF_8);
        fixture.workspace.writeWorkingFile(
                fixture.binding.ownerId(), fixture.binding.workspaceId(), path, replacement,
                Optional.of(UploadFileFacts.of("version-1".getBytes(StandardCharsets.UTF_8)).sha256()));

        var blocked = fixture.write.invoke(input(path, "version-3"), trusted);
        assertThat(blocked.error()).contains("has not been read");
        assertThat(fixture.bytes(path)).isEqualTo("version-2");
    }

    @Test
    void reportsFrozenStyleErrorsForUploadsEscapesAndOversizeWithoutChangingFiles() {
        Fixture fixture = new Fixture(dataRoot.resolve("workspace"), "thread-errors");
        JavaToolContext trusted = context("default-public-user", false, false);

        for (String rejected : new String[] {
                "../outside.txt", "/etc/passwd", "C:/Windows/win.ini",
                "/mnt/user-data/workspace/../outside.txt", "workspace\\escape.txt"}) {
            var result = fixture.write.invoke(input(rejected, "forbidden"), trusted);
            assertThat(result.error()).as(rejected).isNotEmpty();
            assertThat(result.error()).as(rejected).doesNotContain("forbidden");
        }
        var upload = fixture.write.invoke(input("/mnt/user-data/uploads/input.txt", "forbidden"), trusted);
        assertThat(upload.error()).contains("read-only");

        var oversize = fixture.write.invoke(input(
                "/mnt/user-data/workspace/oversize.txt", "界".repeat(30_000)), trusted);
        assertThat(oversize.error()).contains("single-call limit");
        assertThatThrownBy(() -> fixture.bytes("/mnt/user-data/workspace/oversize.txt"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsWrongPrincipalCancellationAndExpiredDeadlineBeforeWrite() {
        Fixture fixture = new Fixture(dataRoot.resolve("workspace"), "thread-3");
        String path = "/mnt/user-data/workspace/blocked.txt";
        assertThatThrownBy(() -> fixture.write.invoke(input(path, "no"), context("attacker", false, false)))
                .hasMessageContaining("owner");
        assertThatThrownBy(() -> fixture.write.invoke(input(path, "no"), context("default-public-user", true, false)))
                .hasMessageContaining("cancelled");
        assertThatThrownBy(() -> fixture.write.invoke(input(path, "no"), context("default-public-user", false, true)))
                .hasMessageContaining("deadline");
        assertThatThrownBy(() -> fixture.bytes(path)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsMissingThreadBindingInsteadOfCreatingOne() {
        Path root = dataRoot.resolve("workspace");
        var bindings = new PersistentProjectWorkspaceService(root,
                WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
        var workspace = new RestrictedWorkspaceService(root, WorkspaceLimits.defaults());
        var tool = new ProductionWriteFileTool(
                "unknown-thread", "thread:unknown-thread", bindings, workspace, new FileToolSession());
        var result = tool.invoke(
                input("/mnt/user-data/workspace/no.txt", "no"), context("default-public-user", false, false));
        assertThat(result.error()).isNotEmpty();
    }

    @Test
    void artifactPublishFailureRollsBackOnlyTheNewWorkingFile() throws Exception {
        Fixture fixture = new Fixture(dataRoot.resolve("workspace"), "thread-publish-failure");
        Path metadata = fixture.root.resolve("artifacts/metadata");
        java.nio.file.Files.delete(metadata);
        java.nio.file.Files.writeString(metadata, "force metadata persistence failure", StandardCharsets.UTF_8);
        String path = "/mnt/user-data/outputs/not-published.txt";

        var result = fixture.write.invoke(input(path, "must not survive"), context("default-public-user", false, false));
        assertThat(result.error()).contains("write");
        assertThatThrownBy(() -> fixture.bytes(path)).isInstanceOf(RuntimeException.class);
        try (var payloads = java.nio.file.Files.list(fixture.root.resolve("artifacts/payloads"))) {
            assertThat(payloads).isEmpty();
        }
    }

    private static ProductionWriteFileTool.Input input(String path, String content) {
        return new ProductionWriteFileTool.Input(path, content, Optional.empty(), Optional.empty());
    }

    private static ProductionWriteFileTool.Input appendInput(String path, String content) {
        return new ProductionWriteFileTool.Input(path, content, Optional.empty(), Optional.of(true));
    }

    private static ProductionReadFileTool.Input readInput(String path) {
        return new ProductionReadFileTool.Input(path, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static JavaToolContext context(String principal, boolean cancelled, boolean expired) {
        return new JavaToolContext(new AgentRunId("run-1"), Optional.of(new AgentSessionId("session-1")),
                new ToolCallId("tool-call-1"), new TenantRef("public"),
                new PrincipalRef(principal, "user"),
                expired ? Instant.now().minusSeconds(1) : Instant.now().plusSeconds(60),
                Optional.empty(), () -> cancelled, Map.of());
    }

    private static final class Fixture {
        private final Path root;
        private final RestrictedWorkspaceService workspace;
        private final WorkspaceBinding binding;
        private final ProductionWriteFileTool write;
        private final ProductionReadFileTool read;

        private Fixture(Path root, String threadId) {
            this.root = root;
            var bindings = new PersistentProjectWorkspaceService(root,
                    WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
            this.workspace = new RestrictedWorkspaceService(root, WorkspaceLimits.defaults());
            this.binding = bindings.bindThreadWorkspace("default", threadId);
            FileToolSession session = new FileToolSession();
            this.write = new ProductionWriteFileTool(
                    threadId, "thread:" + threadId, bindings, workspace, session);
            this.read = new ProductionReadFileTool(threadId, bindings, workspace, session);
        }

        private String bytes(String path) {
            return new String(workspace.readVerifiedBytes(binding.ownerId(), binding.workspaceId(), path),
                    StandardCharsets.UTF_8);
        }
    }
}

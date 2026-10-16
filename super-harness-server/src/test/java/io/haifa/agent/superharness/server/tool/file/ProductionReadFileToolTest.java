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
import io.haifa.agent.tool.api.ToolSideEffect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductionReadFileToolTest {

    @TempDir
    Path tempDir;

    private RestrictedWorkspaceService workspace;
    private WorkspaceBinding binding;
    private FileToolSession session;
    private ProductionReadFileTool tool;

    @BeforeEach
    void setUp() {
        Path root = tempDir.resolve("workspace-root");
        PersistentProjectWorkspaceService bindings = new PersistentProjectWorkspaceService(
                root, WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
        this.workspace = new RestrictedWorkspaceService(root, WorkspaceLimits.defaults());
        String threadId = "thread-read-file";
        this.binding = bindings.bindThreadWorkspace("default", threadId);
        this.session = new FileToolSession();
        this.tool = new ProductionReadFileTool(threadId, bindings, workspace, session);
    }

    private static JavaToolContext context() {
        return new JavaToolContext(
                new AgentRunId("run-read-1"),
                Optional.of(new AgentSessionId("session-read-1")),
                new ToolCallId("tool-call-read-1"),
                new TenantRef("public"),
                new PrincipalRef("default-public-user", "user"),
                Instant.now().plusSeconds(60),
                Optional.empty(),
                () -> false,
                Map.of());
    }

    private static ProductionReadFileTool.Input input(String path) {
        return new ProductionReadFileTool.Input(path, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ProductionReadFileTool.Input input(String path, int start, int end) {
        return new ProductionReadFileTool.Input(path, Optional.empty(), Optional.of(start), Optional.of(end));
    }

    @Test
    void specDeclaresContractAccurately() {
        var spec = tool.spec();
        assertThat(spec.name().value()).isEqualTo("read_file");
        assertThat(spec.title()).isEqualTo("Read a saved file");
        assertThat(spec.sideEffects()).containsExactly(ToolSideEffect.FILE_READ);
    }

    @Test
    void readsTextFileSuccessfully() {
        String content = "Line 1\nLine 2\nLine 3\n";
        workspace.upload(
                binding.ownerId(), binding.workspaceId(), "sample.txt",
                content.getBytes(StandardCharsets.UTF_8), "text/plain");

        String path = "/mnt/user-data/uploads/sample.txt";
        var output = tool.invoke(input(path), context());

        assertThat(output.error()).isEmpty();
        assertThat(output.content()).isEqualTo(content);
        assertThat(output.totalLines()).isEqualTo(3);
        assertThat(output.startLine()).isEqualTo(1);
        assertThat(output.endLine()).isEqualTo(3);
        assertThat(output.truncated()).isFalse();

        // Stamped read mark in session
        assertThat(session.matchesRead(RestrictedWorkspaceService.normalizeLogicalPath(path), output.sha256())).isTrue();

        String summary = tool.summarize(output);
        assertThat(summary).contains("path=" + path);
        assertThat(summary).contains("lines=1-3 of 3");
        assertThat(summary).contains(content);
    }

    @Test
    void readingBinaryFileReturnsFriendlyRedirectionGuidance() {
        byte[] binaryData = new byte[] {
                (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47,
                (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A,
                0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R'
        };
        workspace.upload(
                binding.ownerId(), binding.workspaceId(), "test_image.png",
                binaryData, "image/png");

        String path = "/mnt/user-data/uploads/test_image.png";
        var output = tool.invoke(input(path), context());

        assertThat(output.error()).contains("it appears to be a binary file");
        assertThat(output.error()).contains("or view_image for images.");
        assertThat(output.error()).isEqualTo(
                "cannot read '" + path + "' as text \u2014 it appears to be a binary file (e.g. .xlsx, .pdf, or an image). "
                        + "read_file only supports UTF-8 text. Use bash with a suitable library instead (pandas/openpyxl for spreadsheets), or view_image for images.");

        String summary = tool.summarize(output);
        assertThat(summary).startsWith("Error: cannot read '" + path + "' as text \u2014 it appears to be a binary file");
        assertThat(summary).contains("or view_image for images.");
        assertThat(summary).contains("encoding=not valid UTF-8");
    }

    @Test
    void rejectsMissingFile() {
        String path = "/mnt/user-data/workspace/absent.txt";
        var output = tool.invoke(input(path), context());

        assertThat(output.error()).contains("file not found in this conversation: " + path);
        String summary = tool.summarize(output);
        assertThat(summary).startsWith("Error: file not found in this conversation: " + path);
    }

    @Test
    void rejectsInvalidLineRanges() {
        String content = "Row 1\nRow 2\n";
        workspace.upload(
                binding.ownerId(), binding.workspaceId(), "lines.txt",
                content.getBytes(StandardCharsets.UTF_8), "text/plain");

        String path = "/mnt/user-data/uploads/lines.txt";

        // start_line < 1
        var out1 = tool.invoke(new ProductionReadFileTool.Input(path, Optional.empty(), Optional.of(0), Optional.of(1)), context());
        assertThat(out1.error()).isEqualTo("start_line must be >= 1");

        // end_line < 1
        var out2 = tool.invoke(new ProductionReadFileTool.Input(path, Optional.empty(), Optional.of(1), Optional.of(0)), context());
        assertThat(out2.error()).isEqualTo("end_line must be >= 1");

        // start_line > end_line
        var out3 = tool.invoke(input(path, 3, 2), context());
        assertThat(out3.error()).isEqualTo("start_line > end_line, no lines in range");

        // start_line beyond total
        var out4 = tool.invoke(input(path, 10, 15), context());
        assertThat(out4.error()).contains("start_line 10 is beyond the last line 2");
    }

    @Test
    void securityAndLifecycleChecks() {
        JavaToolContext foreignContext = new JavaToolContext(
                new AgentRunId("run-read-2"),
                Optional.empty(),
                new ToolCallId("tool-call-read-2"),
                new TenantRef("public"),
                new PrincipalRef("foreign-user", "user"),
                Instant.now().plusSeconds(60),
                Optional.empty(),
                () -> false,
                Map.of());

        assertThatThrownBy(() -> tool.invoke(input("/mnt/user-data/workspace/test.txt"), foreignContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read_file requires the local Thread owner");
    }
}

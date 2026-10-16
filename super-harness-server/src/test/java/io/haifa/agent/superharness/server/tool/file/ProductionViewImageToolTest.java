package io.haifa.agent.superharness.server.tool.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.superharness.server.tool.ToolDescriptions;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceBinding;
import io.haifa.agent.superharness.workspace.WorkspaceChangeLimits;
import io.haifa.agent.superharness.workspace.WorkspaceLimits;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductionViewImageToolTest {

    @TempDir
    Path tempDir;

    private RestrictedWorkspaceService workspace;
    private WorkspaceBinding binding;
    private ProductionViewImageTool tool;
    private ProductionReadFileTool readTool;

    private static final byte[] PNG_HEADER = new byte[] {
            (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47,
            (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A
    };

    private static final byte[] JPEG_HEADER = new byte[] {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0x00, 0x10, (byte) 'J', (byte) 'F', (byte) 'I', (byte) 'F', 0x00
    };

    private static final byte[] WEBP_HEADER = new byte[] {
            (byte) 'R', (byte) 'I', (byte) 'F', (byte) 'F',
            0x24, 0x00, 0x00, 0x00,
            (byte) 'W', (byte) 'E', (byte) 'B', (byte) 'P'
    };

    private static final byte[] GIF_HEADER = new byte[] {
            (byte) 'G', (byte) 'I', (byte) 'F', (byte) '8', (byte) '9', (byte) 'a',
            0x01, 0x00, 0x01, 0x00
    };

    @BeforeEach
    void setUp() {
        Path root = tempDir.resolve("workspace-root");
        PersistentProjectWorkspaceService bindings = new PersistentProjectWorkspaceService(
                root, WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
        this.workspace = new RestrictedWorkspaceService(root, WorkspaceLimits.defaults());
        String threadId = "thread-view-image";
        this.binding = bindings.bindThreadWorkspace("default", threadId);
        this.tool = new ProductionViewImageTool(threadId, bindings, workspace);
        this.readTool = new ProductionReadFileTool(threadId, bindings, workspace, new FileToolSession());
    }

    private static JavaToolContext context() {
        return new JavaToolContext(
                new AgentRunId("run-view-image-1"),
                Optional.of(new AgentSessionId("session-view-image-1")),
                new ToolCallId("tool-call-view-image-1"),
                new TenantRef("public"),
                new PrincipalRef("default-public-user", "user"),
                Instant.now().plusSeconds(60),
                Optional.empty(),
                () -> false,
                Map.of());
    }

    private byte[] makeImageBytes(byte[] header, int totalSize) {
        byte[] bytes = new byte[totalSize];
        System.arraycopy(header, 0, bytes, 0, header.length);
        for (int i = header.length; i < totalSize; i++) {
            bytes[i] = (byte) (i & 0xFF);
        }
        return bytes;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void specDeclaresContractAccurately() {
        var spec = tool.spec();
        assertThat(spec.name().value()).isEqualTo("view_image");
        assertThat(spec.title()).isEqualTo("Read an image file");
        assertThat(spec.sideEffects()).containsExactly(ToolSideEffect.FILE_READ);
        assertThat(spec.description()).isEqualTo(ToolDescriptions.load("view_image").stripTrailing());
        assertThat(spec.description()).contains("When to use the view_image tool:");
        assertThat(spec.description()).contains("When NOT to use the view_image tool:");
    }

    @Test
    void readsValidPngImageSuccessfully() throws Exception {
        byte[] pngData = makeImageBytes(PNG_HEADER, 1024);
        workspace.upload(binding.ownerId(), binding.workspaceId(), "chart.png", pngData, "image/png");

        String path = "/mnt/user-data/uploads/chart.png";
        var output = tool.invoke(new ProductionViewImageTool.Input(path), context());

        assertThat(output.status()).isEqualTo("success");
        assertThat(output.message()).isEqualTo("Successfully read image");
        assertThat(output.path()).isEqualTo(path);
        assertThat(output.mime_type()).isEqualTo("image/png");
        assertThat(output.size()).isEqualTo(1024);
        assertThat(output.sha256()).isEqualTo(sha256(pngData));

        // summarize returns plain text, no JSON or binary
        String summary = tool.summarize(output);
        assertThat(summary).isEqualTo("Successfully read image");
        assertThat(summary).doesNotContain("{", "}", "data:image", "base64");
    }

    @Test
    void readsValidJpegWebpAndGifSuccessfully() throws Exception {
        JavaToolContext ctx = context();

        // JPEG in workspace/
        byte[] jpegData = makeImageBytes(JPEG_HEADER, 512);
        workspace.createWorkingFile(binding.ownerId(), binding.workspaceId(), "workspace/photo.jpg", jpegData);
        var jpegOutput = tool.invoke(new ProductionViewImageTool.Input("/mnt/user-data/workspace/photo.jpg"), ctx);
        assertThat(jpegOutput.status()).isEqualTo("success");
        assertThat(jpegOutput.mime_type()).isEqualTo("image/jpeg");
        assertThat(jpegOutput.size()).isEqualTo(512);
        assertThat(jpegOutput.sha256()).isEqualTo(sha256(jpegData));
        assertThat(tool.summarize(jpegOutput)).isEqualTo("Successfully read image");

        // JPEG with .jpeg extension
        workspace.createWorkingFile(binding.ownerId(), binding.workspaceId(), "workspace/photo.jpeg", jpegData);
        var jpegOutput2 = tool.invoke(new ProductionViewImageTool.Input("/mnt/user-data/workspace/photo.jpeg"), ctx);
        assertThat(jpegOutput2.status()).isEqualTo("success");
        assertThat(jpegOutput2.mime_type()).isEqualTo("image/jpeg");

        // WEBP in outputs/
        byte[] webpData = makeImageBytes(WEBP_HEADER, 256);
        workspace.createWorkingFile(binding.ownerId(), binding.workspaceId(), "outputs/banner.webp", webpData);
        var webpOutput = tool.invoke(new ProductionViewImageTool.Input("/mnt/user-data/outputs/banner.webp"), ctx);
        assertThat(webpOutput.status()).isEqualTo("success");
        assertThat(webpOutput.mime_type()).isEqualTo("image/webp");
        assertThat(webpOutput.size()).isEqualTo(256);
        assertThat(webpOutput.sha256()).isEqualTo(sha256(webpData));
        assertThat(tool.summarize(webpOutput)).isEqualTo("Successfully read image");

        // GIF in uploads/
        byte[] gifData = makeImageBytes(GIF_HEADER, 128);
        workspace.upload(binding.ownerId(), binding.workspaceId(), "animation.gif", gifData, "image/gif");
        var gifOutput = tool.invoke(new ProductionViewImageTool.Input("/mnt/user-data/uploads/animation.gif"), ctx);
        assertThat(gifOutput.status()).isEqualTo("success");
        assertThat(gifOutput.mime_type()).isEqualTo("image/gif");
        assertThat(gifOutput.size()).isEqualTo(128);
        assertThat(gifOutput.sha256()).isEqualTo(sha256(gifData));
        assertThat(tool.summarize(gifOutput)).isEqualTo("Successfully read image");
    }

    @Test
    void rejectsDisallowedVirtualPathRoots() {
        JavaToolContext ctx = context();
        String expectedError = "Error: Only image paths under /mnt/user-data/workspace, /mnt/user-data/uploads, /mnt/user-data/outputs are allowed";

        var out1 = tool.invoke(new ProductionViewImageTool.Input("/etc/passwd"), ctx);
        assertThat(out1.status()).isEqualTo("error");
        assertThat(out1.message()).isEqualTo(expectedError);
        assertThat(tool.summarize(out1)).isEqualTo(expectedError);

        var out2 = tool.invoke(new ProductionViewImageTool.Input("C:\\Windows\\System32\\cmd.exe"), ctx);
        assertThat(out2.status()).isEqualTo("error");
        assertThat(out2.message()).isEqualTo(expectedError);

        var out3 = tool.invoke(new ProductionViewImageTool.Input("/mnt/user-data/other/image.png"), ctx);
        assertThat(out3.status()).isEqualTo("error");
        assertThat(out3.message()).isEqualTo(expectedError);

        var out4 = tool.invoke(new ProductionViewImageTool.Input("/mnt/user-data/uploads/../secret.png"), ctx);
        assertThat(out4.status()).isEqualTo("error");
        assertThat(out4.message()).isEqualTo(expectedError);
    }

    @Test
    void rejectsMissingFile() {
        JavaToolContext ctx = context();
        String missingPath = "/mnt/user-data/workspace/nonexistent.png";
        var out = tool.invoke(new ProductionViewImageTool.Input(missingPath), ctx);

        assertThat(out.status()).isEqualTo("error");
        assertThat(out.message()).isEqualTo("Error: Image file not found: " + missingPath);
        assertThat(tool.summarize(out)).isEqualTo("Error: Image file not found: " + missingPath);
    }

    @Test
    void rejectsUnsupportedExtensions() {
        JavaToolContext ctx = context();
        String exePath = "/mnt/user-data/uploads/program.exe";
        var out = tool.invoke(new ProductionViewImageTool.Input(exePath), ctx);

        assertThat(out.status()).isEqualTo("error");
        assertThat(out.message()).isEqualTo("Error: Unsupported image format: .exe. Supported formats: .jpg, .jpeg, .png, .webp, .gif");

        String noExtPath = "/mnt/user-data/uploads/image";
        var out2 = tool.invoke(new ProductionViewImageTool.Input(noExtPath), ctx);
        assertThat(out2.status()).isEqualTo("error");
        assertThat(out2.message()).isEqualTo("Error: Unsupported image format: . Supported formats: .jpg, .jpeg, .png, .webp, .gif");
    }

    @Test
    void detectsExtensionSpoofingAndInvalidContent() {
        JavaToolContext ctx = context();

        // 1. Non-image binary disguised as PNG
        byte[] fakeData = new byte[] { 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07 };
        workspace.upload(binding.ownerId(), binding.workspaceId(), "fake.png", fakeData, "image/png");
        var out1 = tool.invoke(new ProductionViewImageTool.Input("/mnt/user-data/uploads/fake.png"), ctx);
        assertThat(out1.status()).isEqualTo("error");
        assertThat(out1.message()).isEqualTo("Error: File contents do not match a supported image format");

        // 2. JPEG renamed to .png
        byte[] jpegAsPng = makeImageBytes(JPEG_HEADER, 100);
        workspace.upload(binding.ownerId(), binding.workspaceId(), "jpeg_as.png", jpegAsPng, "image/png");
        var out2 = tool.invoke(new ProductionViewImageTool.Input("/mnt/user-data/uploads/jpeg_as.png"), ctx);
        assertThat(out2.status()).isEqualTo("error");
        assertThat(out2.message()).isEqualTo("Error: Image contents are image/jpeg, but file extension indicates image/png");

        // 3. PNG renamed to .jpg
        byte[] pngAsJpg = makeImageBytes(PNG_HEADER, 100);
        workspace.upload(binding.ownerId(), binding.workspaceId(), "png_as.jpg", pngAsJpg, "image/jpeg");
        var out3 = tool.invoke(new ProductionViewImageTool.Input("/mnt/user-data/uploads/png_as.jpg"), ctx);
        assertThat(out3.status()).isEqualTo("error");
        assertThat(out3.message()).isEqualTo("Error: Image contents are image/png, but file extension indicates image/jpeg");
    }

    @Test
    void validatesSizeBoundariesAt10MAnd20M() {
        JavaToolContext ctx = context();

        // 1. 10 MiB (10485760 bytes) - passes tool layer
        int tenMb = 10 * 1024 * 1024;
        byte[] tenMbBytes = makeImageBytes(PNG_HEADER, tenMb);
        workspace.createWorkingFile(binding.ownerId(), binding.workspaceId(), "workspace/10mb.png", tenMbBytes);
        var out10Mb = tool.invoke(new ProductionViewImageTool.Input("/mnt/user-data/workspace/10mb.png"), ctx);
        assertThat(out10Mb.status()).isEqualTo("success");
        assertThat(out10Mb.size()).isEqualTo(tenMb);
        assertThat(tool.summarize(out10Mb)).isEqualTo("Successfully read image");

        // 2. 10 MiB + 1 byte (10485761 bytes) - passes tool layer (<= 20 MiB contract)
        int tenMbPlusOne = tenMb + 1;
        byte[] tenMbPlusOneBytes = makeImageBytes(PNG_HEADER, tenMbPlusOne);
        workspace.createWorkingFile(binding.ownerId(), binding.workspaceId(), "workspace/10mb1b.png", tenMbPlusOneBytes);
        var out10MbPlusOne = tool.invoke(new ProductionViewImageTool.Input("/mnt/user-data/workspace/10mb1b.png"), ctx);
        assertThat(out10MbPlusOne.status()).isEqualTo("success");
        assertThat(out10MbPlusOne.size()).isEqualTo(tenMbPlusOne);
        assertThat(tool.summarize(out10MbPlusOne)).isEqualTo("Successfully read image");

        // 3. 20 MiB (20971520 bytes) - passes tool layer
        int twentyMb = 20 * 1024 * 1024;
        byte[] twentyMbBytes = makeImageBytes(PNG_HEADER, twentyMb);
        workspace.createWorkingFile(binding.ownerId(), binding.workspaceId(), "workspace/20mb.png", twentyMbBytes);
        var out20Mb = tool.invoke(new ProductionViewImageTool.Input("/mnt/user-data/workspace/20mb.png"), ctx);
        assertThat(out20Mb.status()).isEqualTo("success");
        assertThat(out20Mb.size()).isEqualTo(twentyMb);
        assertThat(tool.summarize(out20Mb)).isEqualTo("Successfully read image");

        // 4. 20 MiB + 1 byte (20971521 bytes) - intercepted with error message
        int twentyMbPlusOne = twentyMb + 1;
        byte[] twentyMbPlusOneBytes = makeImageBytes(PNG_HEADER, twentyMbPlusOne);
        workspace.createWorkingFile(binding.ownerId(), binding.workspaceId(), "workspace/20mb1b.png", twentyMbPlusOneBytes);
        var out20MbPlusOne = tool.invoke(new ProductionViewImageTool.Input("/mnt/user-data/workspace/20mb1b.png"), ctx);
        assertThat(out20MbPlusOne.status()).isEqualTo("error");
        assertThat(out20MbPlusOne.message()).isEqualTo(
                "Error: Image file is too large: " + twentyMbPlusOne + " bytes. Maximum supported size is 20971520 bytes");
        assertThat(tool.summarize(out20MbPlusOne)).isEqualTo(
                "Error: Image file is too large: " + twentyMbPlusOne + " bytes. Maximum supported size is 20971520 bytes");
    }

    @Test
    void readBinaryFileWithReadFileToolGivesHelpfulRedirectionGuidance() {
        JavaToolContext ctx = context();
        byte[] pngData = makeImageBytes(PNG_HEADER, 64);
        workspace.upload(binding.ownerId(), binding.workspaceId(), "diagram.png", pngData, "image/png");

        String path = "/mnt/user-data/uploads/diagram.png";
        var readOutput = readTool.invoke(
                new ProductionReadFileTool.Input(path, Optional.empty(), Optional.empty(), Optional.empty()),
                ctx);

        assertThat(readOutput.error()).contains("it appears to be a binary file");
        assertThat(readOutput.error()).contains("or view_image for images.");
        assertThat(readOutput.error()).isEqualTo(
                "cannot read '" + path + "' as text \u2014 it appears to be a binary file (e.g. .xlsx, .pdf, or an image). "
                        + "read_file only supports UTF-8 text. Use bash with a suitable library instead (pandas/openpyxl for spreadsheets), or view_image for images.");

        String summary = readTool.summarize(readOutput);
        assertThat(summary).contains("it appears to be a binary file");
        assertThat(summary).contains("or view_image for images.");
    }

    @Test
    void toolMessageIsStrictlyPlainText() {
        // Successful read
        var successOutput = ProductionViewImageTool.Output.success(
                "/mnt/user-data/workspace/test.png", "abcd1234", "image/png", 100);
        String successSummary = tool.summarize(successOutput);
        assertThat(successSummary).isEqualTo("Successfully read image");
        assertThat(successSummary).doesNotContain("{", "}", "\"", ":");

        // Error read
        var failureOutput = ProductionViewImageTool.Output.failure(
                "/mnt/user-data/workspace/test.png", "Image file not found: /mnt/user-data/workspace/test.png");
        String failureSummary = tool.summarize(failureOutput);
        assertThat(failureSummary).isEqualTo("Error: Image file not found: /mnt/user-data/workspace/test.png");
        assertThat(failureSummary).doesNotContain("{", "}", "\"");
    }

    @Test
    void securityAndLifecycleChecks() {
        // Foreign principal throws
        JavaToolContext foreignContext = new JavaToolContext(
                new AgentRunId("run-2"),
                Optional.empty(),
                new ToolCallId("tool-call-2"),
                new TenantRef("public"),
                new PrincipalRef("foreign-user", "user"),
                Instant.now().plusSeconds(60),
                Optional.empty(),
                () -> false,
                Map.of());

        assertThatThrownBy(() -> tool.invoke(new ProductionViewImageTool.Input("/mnt/user-data/workspace/test.png"), foreignContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("view_image requires the local Thread owner");

        // Expired deadline throws
        JavaToolContext expiredContext = new JavaToolContext(
                new AgentRunId("run-3"),
                Optional.empty(),
                new ToolCallId("tool-call-3"),
                new TenantRef("public"),
                new PrincipalRef("default-public-user", "user"),
                Instant.now().minusSeconds(10),
                Optional.empty(),
                () -> false,
                Map.of());

        assertThatThrownBy(() -> tool.invoke(new ProductionViewImageTool.Input("/mnt/user-data/workspace/test.png"), expiredContext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deadline has expired");
    }
}

package io.haifa.agent.superharness.server.tool.file;

import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.superharness.server.auth.ThreadOwners;
import io.haifa.agent.superharness.server.tool.ToolDescriptions;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceBinding;
import io.haifa.agent.superharness.workspace.WorkspaceException;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Thread-bound implementation of frozen DeerFlow's builtin {@code view_image}.
 *
 * <p>Reads and validates image files below {@code /mnt/user-data/workspace},
 * {@code /mnt/user-data/uploads}, or {@code /mnt/user-data/outputs}. It verifies file existence,
 * maximum supported size (20 MiB), allowed extensions (.jpg, .jpeg, .png, .webp, .gif),
 * and strict magic bytes to prevent extension spoofing.
 *
 * <p>Metadata is recorded in {@code Output} and retained via {@code ToolResult.structuredData()},
 * while {@link #summarize(Output)} returns a plain text summary for the model.
 */
public final class ProductionViewImageTool
        implements JavaTool<ProductionViewImageTool.Input, ProductionViewImageTool.Output> {

    public static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024; // 20971520 bytes (20 MiB)
    private static final String SDK_LOCAL_PRINCIPAL = "default-public-user";

    private static final Set<String> ALLOWED_ROOTS = Set.of(
            "/mnt/user-data/workspace",
            "/mnt/user-data/uploads",
            "/mnt/user-data/outputs");

    private static final String ALLOWED_ROOTS_ERROR =
            "Error: Only image paths under /mnt/user-data/workspace, /mnt/user-data/uploads, /mnt/user-data/outputs are allowed";

    private static final Map<String, String> EXTENSION_TO_MIME = Map.of(
            ".jpg", "image/jpeg",
            ".jpeg", "image/jpeg",
            ".png", "image/png",
            ".webp", "image/webp",
            ".gif", "image/gif");

    private static final JavaToolSpec<Input, Output> SPEC =
            JavaToolSpec.builder("view_image", Input.class, Output.class)
                    .title("Read an image file")
                    .description(ToolDescriptions.load("view_image"))
                    .sideEffects(ToolSideEffect.FILE_READ)
                    .build();

    private final String threadId;
    private final PersistentProjectWorkspaceService bindings;
    private final RestrictedWorkspaceService workspace;

    public ProductionViewImageTool(
            String threadId,
            PersistentProjectWorkspaceService bindings,
            RestrictedWorkspaceService workspace) {
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
            throw new IllegalArgumentException("view_image requires the local Thread owner");
        }
        context.cancellation().throwIfCancellationRequested();
        if (!Instant.now().isBefore(context.deadline())) {
            throw new IllegalStateException("view_image deadline has expired");
        }

        String imagePath = input.image_path();
        if (!isAllowedImageVirtualPath(imagePath)) {
            return Output.failure(imagePath, ALLOWED_ROOTS_ERROR);
        }

        String suffix = extensionOf(imagePath);
        String expectedMime = EXTENSION_TO_MIME.get(suffix.toLowerCase(Locale.ROOT));
        if (expectedMime == null) {
            return Output.failure(
                    imagePath,
                    "Error: Unsupported image format: " + suffix
                            + ". Supported formats: .jpg, .jpeg, .png, .webp, .gif");
        }

        byte[] bytes;
        try {
            WorkspaceBinding binding = bindings.requireThreadWorkspace(ThreadOwners.of(threadId), threadId);
            bytes = workspace.readVerifiedBytes(binding.ownerId(), binding.workspaceId(), imagePath);
        } catch (WorkspaceException failure) {
            return switch (failure.error()) {
                case WORKSPACE_NOT_FOUND, FILE_NOT_FOUND ->
                        Output.failure(imagePath, "Error: Image file not found: " + imagePath);
                case INVALID_PATH, PATH_ESCAPE ->
                        Output.failure(imagePath, ALLOWED_ROOTS_ERROR);
                case FILE_TOO_LARGE ->
                        Output.failure(
                                imagePath,
                                "Error: Image file is too large: file exceeds workspace size limit. Maximum supported size is "
                                        + MAX_IMAGE_BYTES + " bytes");
                default ->
                        Output.failure(imagePath, "Error: " + failure.getMessage());
            };
        }

        if (bytes.length > MAX_IMAGE_BYTES) {
            return Output.failure(
                    imagePath,
                    "Error: Image file is too large: " + bytes.length
                            + " bytes. Maximum supported size is " + MAX_IMAGE_BYTES + " bytes");
        }

        String detectedMime = detectImageMime(bytes);
        if (detectedMime == null) {
            return Output.failure(imagePath, "Error: File contents do not match a supported image format");
        }
        if (!detectedMime.equals(expectedMime)) {
            return Output.failure(
                    imagePath,
                    "Error: Image contents are " + detectedMime + ", but file extension indicates " + expectedMime);
        }

        String sha256 = sha256(bytes);
        return Output.success(imagePath, sha256, detectedMime, bytes.length);
    }

    @Override
    public String summarize(Output output) {
        return output.message();
    }

    private static boolean isAllowedImageVirtualPath(String path) {
        if (path == null) {
            return false;
        }
        for (String root : ALLOWED_ROOTS) {
            if (path.equals(root) || path.startsWith(root + "/")) {
                return true;
            }
        }
        return false;
    }

    private static String extensionOf(String path) {
        if (path == null) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        String filename = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = filename.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        return filename.substring(dot);
    }

    static String detectImageMime(byte[] data) {
        if (data == null) {
            return null;
        }
        // JPEG: 0xFF, 0xD8, 0xFF
        if (data.length >= 3
                && (data[0] & 0xFF) == 0xFF
                && (data[1] & 0xFF) == 0xD8
                && (data[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        // PNG: 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'
        if (data.length >= 8
                && (data[0] & 0xFF) == 0x89
                && data[1] == 'P'
                && data[2] == 'N'
                && data[3] == 'G'
                && data[4] == '\r'
                && data[5] == '\n'
                && data[6] == 0x1A
                && data[7] == '\n') {
            return "image/png";
        }
        // WEBP: starts with "RIFF" and bytes 8..11 are "WEBP"
        if (data.length >= 12
                && data[0] == 'R'
                && data[1] == 'I'
                && data[2] == 'F'
                && data[3] == 'F'
                && data[8] == 'W'
                && data[9] == 'E'
                && data[10] == 'B'
                && data[11] == 'P') {
            return "image/webp";
        }
        // GIF: starts with "GIF87a" or "GIF89a"
        if (data.length >= 6
                && data[0] == 'G'
                && data[1] == 'I'
                && data[2] == 'F'
                && data[3] == '8'
                && (data[4] == '7' || data[4] == '9')
                && data[5] == 'a') {
            return "image/gif";
        }
        return null;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record Input(String image_path) {
        public Input {
            Objects.requireNonNull(image_path, "image_path");
        }
    }

    public record Output(
            String status,
            String message,
            String path,
            String sha256,
            String mime_type,
            long size) {

        public static Output success(String path, String sha256, String mimeType, long size) {
            return new Output("success", "Successfully read image", path, sha256, mimeType, size);
        }

        public static Output failure(String path, String error) {
            return new Output(
                    "error",
                    error.startsWith("Error: ") ? error : "Error: " + error,
                    path,
                    "",
                    "",
                    0L);
        }
    }
}

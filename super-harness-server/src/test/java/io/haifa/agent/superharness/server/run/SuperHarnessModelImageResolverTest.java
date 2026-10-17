package io.haifa.agent.superharness.server.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.content.StoredImageContentPart;
import io.haifa.agent.model.api.ImageDataPart;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceBinding;
import io.haifa.agent.superharness.workspace.WorkspaceChangeLimits;
import io.haifa.agent.superharness.workspace.WorkspaceLimits;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuperHarnessModelImageResolverTest {

    @TempDir
    Path tempDir;

    private RestrictedWorkspaceService workspace;
    private WorkspaceBinding binding;
    private SuperHarnessModelImageResolver resolver;

    private static final byte[] PNG_BYTES = new byte[] {
            (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47,
            (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A,
            1, 2, 3, 4
    };

    @BeforeEach
    void setUp() throws Exception {
        Path root = tempDir.resolve("workspace-root");
        PersistentProjectWorkspaceService bindings = new PersistentProjectWorkspaceService(
                root, WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
        this.workspace = new RestrictedWorkspaceService(root, WorkspaceLimits.defaults());
        String threadId = "thread-vision-test";
        this.binding = bindings.bindThreadWorkspace("default", threadId);
        this.resolver = new SuperHarnessModelImageResolver(workspace, "default", threadId);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void resolve_validImage_returnsImageDataPart() throws Exception {
        workspace.createWorkingFile(binding.ownerId(), binding.workspaceId(), "workspace/test.png", PNG_BYTES);
        String hash = "sha256:" + sha256(PNG_BYTES);

        StoredImageContentPart part = new StoredImageContentPart(
                "workspace",
                "/mnt/user-data/workspace/test.png",
                "image/png",
                PNG_BYTES.length,
                hash,
                "test.png");

        ImageDataPart result = resolver.resolve(part);
        assertThat(result).isNotNull();
        assertThat(result.mediaType()).isEqualTo("image/png");
        assertThat(result.bytes()).isEqualTo(PNG_BYTES);
    }

    @Test
    void resolve_forgedCrossThreadStoreId_throwsSecurityException() throws Exception {
        workspace.createWorkingFile(binding.ownerId(), binding.workspaceId(), "workspace/test.png", PNG_BYTES);
        String hash = "sha256:" + sha256(PNG_BYTES);

        StoredImageContentPart part = new StoredImageContentPart(
                "other-tenant-thread-store-id",
                "/mnt/user-data/workspace/test.png",
                "image/png",
                PNG_BYTES.length,
                hash,
                "test.png");

        assertThatThrownBy(() -> resolver.resolve(part))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("does not match authorized workspace context");
    }

    @Test
    void resolve_pathTraversalInStoreId_throwsSecurityException() throws Exception {
        String hash = "sha256:" + sha256(PNG_BYTES);
        StoredImageContentPart part = new StoredImageContentPart(
                "../escape",
                "/mnt/user-data/workspace/test.png",
                "image/png",
                PNG_BYTES.length,
                hash,
                "test.png");

        assertThatThrownBy(() -> resolver.resolve(part))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("storeId contains illegal path characters");
    }

    @Test
    void resolve_pathTraversalInImageId_throwsSecurityException() throws Exception {
        String hash = "sha256:" + sha256(PNG_BYTES);
        StoredImageContentPart part = new StoredImageContentPart(
                "workspace",
                "/mnt/user-data/workspace/../../secret.txt",
                "image/png",
                PNG_BYTES.length,
                hash,
                "secret.txt");

        assertThatThrownBy(() -> resolver.resolve(part))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("path traversal in imageId");
    }

    @Test
    void resolve_imageIdOutsideAllowedRoots_throwsSecurityException() throws Exception {
        String hash = "sha256:" + sha256(PNG_BYTES);
        StoredImageContentPart part = new StoredImageContentPart(
                "workspace",
                "/etc/passwd",
                "image/png",
                PNG_BYTES.length,
                hash,
                "passwd");

        assertThatThrownBy(() -> resolver.resolve(part))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("outside authorized workspace roots");
    }

    @Test
    void resolve_missingImageFile_throwsSecurityException() throws Exception {
        String hash = "sha256:" + sha256(PNG_BYTES);
        StoredImageContentPart part = new StoredImageContentPart(
                "workspace",
                "/mnt/user-data/workspace/nonexistent.png",
                "image/png",
                PNG_BYTES.length,
                hash,
                "nonexistent.png");

        assertThatThrownBy(() -> resolver.resolve(part))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Failed to read image file in workspace");
    }

    @Test
    void resolve_tamperedImageContent_throwsSecurityException() throws Exception {
        workspace.createWorkingFile(binding.ownerId(), binding.workspaceId(), "workspace/tampered.png", PNG_BYTES);
        String forgedHash = "sha256:0000000000000000000000000000000000000000000000000000000000000000";

        StoredImageContentPart part = new StoredImageContentPart(
                "workspace",
                "/mnt/user-data/workspace/tampered.png",
                "image/png",
                PNG_BYTES.length,
                forgedHash,
                "tampered.png");

        assertThatThrownBy(() -> resolver.resolve(part))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Image content integrity check failed: SHA-256 mismatch");
    }

    @Test
    void resolve_imageExceeding10MiB_throwsIllegalArgumentException() throws Exception {
        // Physical file on disk is larger than 10 MiB (10485760 bytes)
        int size = 10_485_761;
        byte[] largeBytes = new byte[size];
        largeBytes[0] = (byte) 0x89;
        largeBytes[1] = (byte) 0x50;
        largeBytes[2] = (byte) 0x4E;
        largeBytes[3] = (byte) 0x47;
        workspace.createWorkingFile(binding.ownerId(), binding.workspaceId(), "workspace/large.png", largeBytes);
        String hash = "sha256:" + sha256(largeBytes);

        // StoredImageContentPart requires sizeBytes <= 10485760, so we specify a valid size in part,
        // but the actual file on disk exceeds 10 MiB limit
        StoredImageContentPart part = new StoredImageContentPart(
                "workspace",
                "/mnt/user-data/workspace/large.png",
                "image/png",
                100,
                hash,
                "large.png");

        assertThatThrownBy(() -> resolver.resolve(part))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds maximum supported size");
    }
}

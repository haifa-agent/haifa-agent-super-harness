package io.haifa.agent.superharness.server.run;

import io.haifa.agent.core.content.StoredImageContentPart;
import io.haifa.agent.model.api.ImageDataPart;
import io.haifa.agent.sdk.api.ModelImageResolver;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceBinding;
import io.haifa.agent.superharness.workspace.WorkspaceException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/**
 * Thread-bound implementation of {@link ModelImageResolver}.
 *
 * <p>Enforces multi-tenant and cross-thread isolation by binding to a specific
 * {@code (ownerId, threadId)} context upon construction. Resolves stored image
 * parts only if they belong to the authorized workspace, verifying path safety,
 * storage boundaries, and SHA-256 integrity.
 */
public final class SuperHarnessModelImageResolver implements ModelImageResolver {

    private static final Set<String> ALLOWED_ROOTS = Set.of(
            "/mnt/user-data/workspace",
            "/mnt/user-data/uploads",
            "/mnt/user-data/outputs",
            "workspace",
            "uploads",
            "outputs");

    private final RestrictedWorkspaceService workspace;
    private final String ownerId;
    private final String threadId;
    private final WorkspaceBinding binding;

    public SuperHarnessModelImageResolver(
            RestrictedWorkspaceService workspace,
            String ownerId,
            String threadId) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.threadId = Objects.requireNonNull(threadId, "threadId");
        this.binding = workspace.bind(ownerId, threadId);
    }

    public String ownerId() {
        return ownerId;
    }

    public String threadId() {
        return threadId;
    }

    public WorkspaceBinding binding() {
        return binding;
    }

    @Override
    public ImageDataPart resolve(StoredImageContentPart part) {
        Objects.requireNonNull(part, "part must not be null");

        // 1. Validate storeId: must match current thread's workspace or thread, or allowed root names
        validateStoreId(part.storeId());

        // 2. Validate imageId: prevent path traversal and enforce allowed workspace roots
        String imageId = part.imageId();
        validateImageId(imageId);

        // 3. Read physical file bytes via RestrictedWorkspaceService
        byte[] bytes;
        try {
            bytes = workspace.readVerifiedBytes(ownerId, binding.workspaceId(), imageId);
        } catch (WorkspaceException failure) {
            throw new SecurityException("Failed to read image file in workspace: " + failure.getMessage(), failure);
        }

        if (bytes == null || bytes.length == 0) {
            throw new SecurityException("Image file is empty or not found: " + imageId);
        }

        if (bytes.length > ImageDataPart.MAXIMUM_BYTES) {
            throw new IllegalArgumentException(
                    "Image exceeds maximum supported size: " + bytes.length + " bytes > " + ImageDataPart.MAXIMUM_BYTES);
        }

        // 4. Validate SHA-256 integrity
        String actualHash = sha256(bytes);
        String expectedHash = part.sha256();
        if (expectedHash != null && !expectedHash.isBlank()) {
            String cleanExpected = expectedHash.startsWith("sha256:")
                    ? expectedHash.substring(7)
                    : expectedHash;
            if (!actualHash.equalsIgnoreCase(cleanExpected)) {
                throw new SecurityException("Image content integrity check failed: SHA-256 mismatch for " + imageId);
            }
        }

        // 5. Construct and return ImageDataPart
        return new ImageDataPart(part.mediaType(), bytes);
    }

    private void validateStoreId(String storeId) {
        if (storeId == null || storeId.isBlank()) {
            return;
        }
        if (storeId.contains("..") || storeId.contains("/") || storeId.contains("\\")) {
            throw new SecurityException("Access denied: storeId contains illegal path characters: " + storeId);
        }
        String workspaceIdStr = binding.workspaceId().value();
        if (storeId.equals(workspaceIdStr)
                || storeId.equals(threadId)
                || storeId.equals("workspace")
                || storeId.equals("uploads")
                || storeId.equals("outputs")
                || storeId.equals("default")) {
            return;
        }
        throw new SecurityException("Access denied: storeId '" + storeId
                + "' does not match authorized workspace context (" + workspaceIdStr + " / " + threadId + ")");
    }

    private void validateImageId(String imageId) {
        if (imageId == null || imageId.isBlank()) {
            throw new SecurityException("Access denied: imageId must not be blank");
        }
        if (imageId.contains("..") || imageId.contains("\\")) {
            throw new SecurityException("Access denied: path traversal in imageId: " + imageId);
        }
        boolean underAllowedRoot = false;
        for (String root : ALLOWED_ROOTS) {
            if (imageId.equals(root) || imageId.startsWith(root + "/")) {
                underAllowedRoot = true;
                break;
            }
        }
        if (!underAllowedRoot) {
            throw new SecurityException("Access denied: imageId outside authorized workspace roots: " + imageId);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}

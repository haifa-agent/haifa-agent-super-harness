package io.haifa.agent.superharness.workspace;

import io.haifa.agent.artifact.Artifact;
import io.haifa.agent.artifact.ArtifactProvenance;
import io.haifa.agent.artifact.ArtifactService;
import io.haifa.agent.artifact.ArtifactType;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.filesystem.WorkspaceFileService;
import io.haifa.agent.project.workspace.WorkspaceId;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Product-level binding and policy around Haifa's public Workspace and Artifact APIs.
 * Network protocol adaptation intentionally lives outside this module.
 */
public final class RestrictedWorkspaceService {
    private final WorkspaceRegistry registry;
    private final WorkspaceLimits limits;
    private final ArtifactService artifacts;

    public RestrictedWorkspaceService(Path storageRoot, WorkspaceLimits limits) {
        Objects.requireNonNull(storageRoot, "storageRoot");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.registry = new WorkspaceRegistry(storageRoot);
        this.artifacts = new ArtifactService(
                new PersistentArtifactStore(storageRoot),
                new PersistentArtifactPayloadStore(storageRoot),
                () -> UUID.randomUUID().toString(),
                Instant::now);
    }

    public WorkspaceBinding bind(String ownerId, String threadId) {
        return registry.bind(ownerId, threadId);
    }

    public UploadReceipt upload(String ownerId, WorkspaceId workspaceId, String originalFilename,
                                byte[] content, String mediaType) {
        WorkspaceBinding binding = registry.requireOwner(ownerId, workspaceId);
        byte[] copy = requireContent(content, limits.maxUploadBytes());
        String filename = validateFilename(originalFilename);
        String validatedMediaType = requireText(mediaType, "mediaType");
        String hash = Hashing.sha256(copy);
        Path userDataRoot = registry.userDataRoot(binding.workspaceId());
        String logical = "uploads/" + filename;
        Path target = LogicalWorkspacePath.resolve(userDataRoot, logical);
        if (Files.exists(target)) {
            byte[] current = readAllVerified(userDataRoot, target, limits.maxUploadBytes());
            if (Arrays.equals(current, copy)) {
                return receipt(logical, filename, hash, copy.length, validatedMediaType, true);
            }
            logical = collisionName(filename, hash);
            target = LogicalWorkspacePath.resolve(userDataRoot, logical);
            if (Files.exists(target)) {
                byte[] collision = readAllVerified(userDataRoot, target, limits.maxUploadBytes());
                if (Arrays.equals(collision, copy)) {
                    return receipt(logical, filename, hash, copy.length, validatedMediaType, true);
                }
                throw new WorkspaceException(WorkspaceError.IMMUTABLE_UPLOAD,
                        "upload collision cannot replace an immutable original");
            }
        }
        atomicCreate(userDataRoot, target, copy);
        return receipt(logical, filename, hash, copy.length, validatedMediaType, false);
    }

    public WorkspaceFileSnapshot writeWorkingFile(String ownerId, WorkspaceId workspaceId, String logicalPath,
                                                  byte[] content, Optional<String> expectedSha256) {
        WorkspaceBinding binding = registry.requireOwner(ownerId, workspaceId);
        byte[] copy = requireContent(content, limits.maxWorkingFileBytes());
        Objects.requireNonNull(expectedSha256, "expectedSha256");
        String normalized = LogicalWorkspacePath.normalize(logicalPath);
        if (normalized.startsWith("uploads/")) {
            throw new WorkspaceException(WorkspaceError.IMMUTABLE_UPLOAD,
                    "uploaded originals cannot be changed");
        }
        Path userDataRoot = registry.userDataRoot(binding.workspaceId());
        Path target = LogicalWorkspacePath.resolve(userDataRoot, normalized);
        expectedSha256.ifPresent(expected -> {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkspaceException(WorkspaceError.HASH_CONFLICT,
                        "expected hash was supplied for a missing file");
            }
            String current = Hashing.sha256(readAllVerified(userDataRoot, target, limits.maxWorkingFileBytes()));
            if (!current.equals(expected)) {
                throw new WorkspaceException(WorkspaceError.HASH_CONFLICT,
                        "working file changed since it was read");
            }
        });
        atomicReplace(userDataRoot, target, copy);
        return snapshot(normalized, copy, 0, false);
    }

    public WorkspaceFileSnapshot read(String ownerId, WorkspaceId workspaceId, String logicalPath,
                                      long offset, int maxBytes) {
        WorkspaceBinding binding = registry.requireOwner(ownerId, workspaceId);
        if (offset < 0 || maxBytes < 1 || maxBytes > limits.maxReadBytes()) {
            throw new WorkspaceException(WorkspaceError.READ_OUT_OF_BOUNDS, "invalid read range");
        }
        String normalized = LogicalWorkspacePath.normalize(logicalPath);
        Path root = registry.userDataRoot(binding.workspaceId());
        Path target = LogicalWorkspacePath.resolve(root, normalized);
        ensureReadableFile(root, target);
        try {
            long size = Files.size(target);
            if (size > limits.maxWorkingFileBytes()) {
                throw new WorkspaceException(WorkspaceError.FILE_TOO_LARGE, "file exceeds workspace size limit");
            }
            if (offset > size) {
                throw new WorkspaceException(WorkspaceError.READ_OUT_OF_BOUNDS, "read offset exceeds file size");
            }
            int count = (int) Math.min(maxBytes, size - offset);
            ByteBuffer buffer = ByteBuffer.allocate(count);
            try (FileChannel channel = FileChannel.open(target, StandardOpenOption.READ)) {
                channel.position(offset);
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                    // FileChannel may return a short read.
                }
            }
            byte[] all = readAllVerified(root, target, limits.maxWorkingFileBytes());
            return new WorkspaceFileSnapshot(normalized, buffer.array(), Hashing.sha256(all), size, offset,
                    offset + count < size);
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to read workspace file", exception);
        }
    }

    public Artifact publish(String ownerId, WorkspaceId workspaceId, String logicalPath, String title,
                            String artifactType, String mediaType, ArtifactPublishContext context) {
        WorkspaceBinding binding = registry.requireOwner(ownerId, workspaceId);
        String normalized = LogicalWorkspacePath.normalize(logicalPath);
        Path root = registry.userDataRoot(binding.workspaceId());
        byte[] bytes = readAllVerified(root, LogicalWorkspacePath.resolve(root, normalized),
                limits.maxWorkingFileBytes());
        String sourceHash = "sha256:" + Hashing.sha256(bytes);
        ArtifactProvenance provenance = new ArtifactProvenance(
                new ProjectRef(context.projectId()), binding.workspaceId().value(),
                new AgentRunId(context.runId()), new AgentSessionId(context.sessionId()),
                sourceHash, context.executionRef(), LogicalWorkspacePath.upstream(normalized),
                sourceHash, context.exportPolicy(), new PrincipalRef(binding.ownerId(), "user"));
        Artifact artifact = artifacts.publish(new ArtifactType(artifactType), title, bytes, mediaType, provenance);
        if (!artifact.payload().sha256().equals(sourceHash)
                || artifact.payload().byteCount() != bytes.length) {
            throw new IllegalStateException("published artifact does not match workspace source");
        }
        return artifact;
    }

    public byte[] loadArtifact(String ownerId, Artifact artifact) {
        Objects.requireNonNull(artifact, "artifact");
        WorkspaceBinding binding = registry.requireOwner(ownerId,
                new WorkspaceId(artifact.provenance().workspaceRef()));
        if (!artifact.provenance().createdBy().principalId().equals(binding.ownerId())) {
            throw new WorkspaceException(WorkspaceError.OWNER_MISMATCH,
                    "artifact provenance belongs to a different owner");
        }
        byte[] bytes = artifacts.load(artifact);
        if (!("sha256:" + Hashing.sha256(bytes)).equals(artifact.payload().sha256())) {
            throw new IllegalStateException("artifact bytes do not match immutable hash");
        }
        return bytes;
    }

    public List<Artifact> findArtifacts(String ownerId, WorkspaceId workspaceId, String projectId) {
        WorkspaceBinding binding = registry.requireOwner(ownerId, workspaceId);
        return artifacts.findByProject(projectId).stream()
                .filter(artifact -> artifact.provenance().workspaceRef().equals(binding.workspaceId().value()))
                .filter(artifact -> artifact.provenance().createdBy().principalId().equals(binding.ownerId()))
                .toList();
    }

    public WorkspaceFileService workspaceFiles(String ownerId, WorkspaceId workspaceId) {
        WorkspaceBinding binding = registry.requireOwner(ownerId, workspaceId);
        return new OwnerBoundWorkspaceFileService(binding.workspaceId(), registry.userDataRoot(workspaceId), limits);
    }

    private static UploadReceipt receipt(String logical, String original, String hash, long count,
                                         String mediaType, boolean deduplicated) {
        return new UploadReceipt(LogicalWorkspacePath.upstream(logical), original, hash, count, mediaType,
                deduplicated);
    }

    private static String collisionName(String filename, String hash) {
        int dot = filename.lastIndexOf('.');
        String suffix = "__" + hash.substring(0, 12);
        String renamed = dot > 0
                ? filename.substring(0, dot) + suffix + filename.substring(dot)
                : filename + suffix;
        return "uploads/" + renamed;
    }

    private static String validateFilename(String filename) {
        if (filename == null || filename.isBlank() || filename.length() > 255
                || filename.contains("/") || filename.contains("\\")
                || !filename.equals(filename.trim()) || filename.equals(".") || filename.equals("..")) {
            throw new WorkspaceException(WorkspaceError.INVALID_PATH, "upload filename must be a plain filename");
        }
        LogicalWorkspacePath.normalize("uploads/" + filename);
        return filename;
    }

    private static byte[] requireContent(byte[] content, long maxBytes) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        if (content.length > maxBytes) {
            throw new WorkspaceException(WorkspaceError.FILE_TOO_LARGE, "content exceeds configured size limit");
        }
        return content.clone();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static WorkspaceFileSnapshot snapshot(String logical, byte[] bytes, long offset, boolean truncated) {
        return new WorkspaceFileSnapshot(logical, bytes, Hashing.sha256(bytes), bytes.length, offset, truncated);
    }

    private static void atomicCreate(Path root, Path target, byte[] content) {
        ensureNoLinks(root, target);
        try {
            Files.createDirectories(target.getParent());
            Path part = Files.createTempFile(target.getParent(), ".upload-", ".part");
            Files.write(part, content);
            WorkspaceRegistry.moveAtomically(part, target);
        } catch (java.nio.file.FileAlreadyExistsException exception) {
            throw new WorkspaceException(WorkspaceError.IMMUTABLE_UPLOAD,
                    "upload target already exists", exception);
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to store upload", exception);
        }
    }

    private static void atomicReplace(Path root, Path target, byte[] content) {
        ensureNoLinks(root, target);
        try {
            Files.createDirectories(target.getParent());
            ensureNoLinks(root, target);
            Path part = Files.createTempFile(target.getParent(), ".write-", ".part");
            Files.write(part, content);
            try {
                Files.move(part, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to write workspace file", exception);
        }
    }

    static byte[] readAllVerified(Path root, Path target, long maxBytes) {
        ensureReadableFile(root, target);
        try {
            long size = Files.size(target);
            if (size > maxBytes) {
                throw new WorkspaceException(WorkspaceError.FILE_TOO_LARGE, "file exceeds configured size limit");
            }
            return Files.readAllBytes(target);
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to read workspace file", exception);
        }
    }

    static void ensureReadableFile(Path root, Path target) {
        ensureNoLinks(root, target);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException(WorkspaceError.FILE_NOT_FOUND, "workspace file does not exist");
        }
    }

    static void ensureNoLinks(Path root, Path target) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot)) {
            throw new WorkspaceException(WorkspaceError.PATH_ESCAPE, "path escapes workspace root");
        }
        Path cursor = normalizedRoot;
        for (Path segment : normalizedRoot.relativize(normalizedTarget)) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)) {
                throw new WorkspaceException(WorkspaceError.PATH_ESCAPE, "symbolic links are not allowed");
            }
        }
    }
}

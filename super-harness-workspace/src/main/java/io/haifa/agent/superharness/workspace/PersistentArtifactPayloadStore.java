package io.haifa.agent.superharness.workspace;

import io.haifa.agent.artifact.ArtifactPayloadRef;
import io.haifa.agent.artifact.ArtifactPayloadStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

final class PersistentArtifactPayloadStore implements ArtifactPayloadStore {
    private final Path payloadRoot;

    PersistentArtifactPayloadStore(Path storageRoot) {
        this.payloadRoot = storageRoot.toAbsolutePath().normalize().resolve("artifacts/payloads");
        WorkspaceRegistry.createDirectories(payloadRoot);
    }

    @Override
    public synchronized ArtifactPayloadRef put(byte[] content, String mediaType) {
        byte[] copy = content.clone();
        String hash = Hashing.sha256(copy);
        Path target = payloadRoot.resolve(hash + ".bin");
        try {
            if (Files.exists(target)) {
                if (!Arrays.equals(copy, Files.readAllBytes(target))) {
                    throw new IllegalStateException("content-addressed payload collision: " + hash);
                }
            } else {
                Path part = Files.createTempFile(payloadRoot, hash + "-", ".part");
                Files.write(part, copy);
                try {
                    WorkspaceRegistry.moveAtomically(part, target);
                } catch (java.nio.file.FileAlreadyExistsException concurrentPublish) {
                    Files.deleteIfExists(part);
                }
            }
            return new ArtifactPayloadRef("payload-" + hash, "sha256:" + hash, copy.length, mediaType);
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to store artifact payload", exception);
        }
    }

    @Override
    public Optional<byte[]> load(ArtifactPayloadRef ref) {
        Path path = payloadPath(ref);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length != ref.byteCount() || !("sha256:" + Hashing.sha256(bytes)).equals(ref.sha256())) {
                throw new IllegalStateException("persisted artifact payload failed hash verification");
            }
            return Optional.of(bytes);
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to load artifact payload", exception);
        }
    }

    @Override
    public void delete(ArtifactPayloadRef ref) {
        try {
            Files.deleteIfExists(payloadPath(ref));
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to delete artifact payload", exception);
        }
    }

    private Path payloadPath(ArtifactPayloadRef ref) {
        if (ref == null || !ref.payloadId().matches("payload-[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid artifact payload id");
        }
        return payloadRoot.resolve(ref.payloadId().substring("payload-".length()) + ".bin");
    }
}

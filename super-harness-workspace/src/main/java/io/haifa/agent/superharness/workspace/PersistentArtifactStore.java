package io.haifa.agent.superharness.workspace;

import io.haifa.agent.artifact.Artifact;
import io.haifa.agent.artifact.ArtifactId;
import io.haifa.agent.artifact.ArtifactPayloadRef;
import io.haifa.agent.artifact.ArtifactProvenance;
import io.haifa.agent.artifact.ArtifactStatus;
import io.haifa.agent.artifact.ArtifactStore;
import io.haifa.agent.artifact.ArtifactType;
import io.haifa.agent.artifact.ArtifactVersion;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

final class PersistentArtifactStore implements ArtifactStore {
    private final Path metadataRoot;

    PersistentArtifactStore(Path storageRoot) {
        this.metadataRoot = storageRoot.toAbsolutePath().normalize().resolve("artifacts/metadata");
        WorkspaceRegistry.createDirectories(metadataRoot);
    }

    @Override
    public synchronized void create(Artifact artifact) {
        Path target = metadataPath(artifact.id(), artifact.version());
        if (Files.exists(target)) {
            throw new IllegalStateException("artifact version already exists");
        }
        Properties p = new Properties();
        p.setProperty("id", artifact.id().value());
        p.setProperty("version", Long.toString(artifact.version().value()));
        p.setProperty("type", artifact.type().value());
        p.setProperty("title", artifact.title());
        p.setProperty("payloadId", artifact.payload().payloadId());
        p.setProperty("sha256", artifact.payload().sha256());
        p.setProperty("byteCount", Long.toString(artifact.payload().byteCount()));
        p.setProperty("mediaType", artifact.payload().mediaType());
        ArtifactProvenance provenance = artifact.provenance();
        p.setProperty("projectId", provenance.project().projectId());
        p.setProperty("workspaceRef", provenance.workspaceRef());
        p.setProperty("runId", provenance.runId().value());
        p.setProperty("sessionId", provenance.sessionId().value());
        p.setProperty("fileChangeSetRef", provenance.fileChangeSetRef());
        p.setProperty("executionRef", provenance.executionRef());
        p.setProperty("sourceLogicalPath", provenance.sourceLogicalPath());
        p.setProperty("sourceHash", provenance.sourceHash());
        p.setProperty("exportPolicy", provenance.exportPolicy());
        p.setProperty("createdById", provenance.createdBy().principalId());
        p.setProperty("createdByType", provenance.createdBy().principalType());
        p.setProperty("status", artifact.status().name());
        p.setProperty("createdAt", artifact.createdAt().toString());
        try {
            Path part = Files.createTempFile(metadataRoot, artifact.id().value() + "-", ".part");
            try (OutputStream output = Files.newOutputStream(part)) {
                p.store(output, "Immutable Haifa artifact metadata");
            }
            WorkspaceRegistry.moveAtomically(part, target);
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to store artifact metadata", exception);
        }
    }

    @Override
    public Optional<Artifact> find(ArtifactId id, ArtifactVersion version) {
        Path path = metadataPath(id, version);
        return Files.isRegularFile(path) ? Optional.of(load(path)) : Optional.empty();
    }

    @Override
    public List<Artifact> findByProject(String projectId) {
        List<Artifact> matches = new ArrayList<>();
        try (Stream<Path> paths = Files.list(metadataRoot)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .map(this::load)
                    .filter(artifact -> artifact.provenance().project().projectId().equals(projectId))
                    .sorted(java.util.Comparator.comparing(Artifact::createdAt))
                    .forEach(matches::add);
            return List.copyOf(matches);
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to list artifact metadata", exception);
        }
    }

    private Artifact load(Path path) {
        Properties p = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            p.load(input);
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to read artifact metadata", exception);
        }
        ArtifactPayloadRef payload = new ArtifactPayloadRef(
                required(p, "payloadId"), required(p, "sha256"),
                Long.parseLong(required(p, "byteCount")), required(p, "mediaType"));
        ArtifactProvenance provenance = new ArtifactProvenance(
                new ProjectRef(required(p, "projectId")), required(p, "workspaceRef"),
                new AgentRunId(required(p, "runId")), new AgentSessionId(required(p, "sessionId")),
                required(p, "fileChangeSetRef"), required(p, "executionRef"),
                required(p, "sourceLogicalPath"), required(p, "sourceHash"), required(p, "exportPolicy"),
                new PrincipalRef(required(p, "createdById"), required(p, "createdByType")));
        return new Artifact(new ArtifactId(required(p, "id")),
                new ArtifactVersion(Long.parseLong(required(p, "version"))),
                new ArtifactType(required(p, "type")), required(p, "title"), payload, provenance,
                ArtifactStatus.valueOf(required(p, "status")), Instant.parse(required(p, "createdAt")));
    }

    private Path metadataPath(ArtifactId id, ArtifactVersion version) {
        if (id == null || version == null || !id.value().matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("invalid artifact identity");
        }
        return metadataRoot.resolve(id.value() + "-v" + version.value() + ".properties");
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalStateException("artifact metadata is missing " + key);
        }
        return value;
    }
}

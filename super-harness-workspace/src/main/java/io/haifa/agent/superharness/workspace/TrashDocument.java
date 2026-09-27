package io.haifa.agent.superharness.workspace;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Persistent metadata for a trashed project document. */
public record TrashDocument(
        String id,
        String name,
        long sizeBytes,
        String sha256,
        Optional<String> sourceThreadId,
        ProjectDocumentSourceKind sourceKind,
        Optional<String> sourceName,
        Instant createdAt,
        Instant updatedAt,
        Instant trashedAt,
        Optional<TrashOrigin> trashOrigin) {

    public TrashDocument {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(trashedAt, "trashedAt");
        sourceThreadId = sourceThreadId == null ? Optional.empty() : sourceThreadId;
        sourceName = sourceName == null ? Optional.empty() : sourceName;
        trashOrigin = trashOrigin == null ? Optional.empty() : trashOrigin;
    }

    public record TrashOrigin(String projectId, String projectName) {
        public TrashOrigin {
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(projectName, "projectName");
        }
    }
}

package io.haifa.agent.superharness.workspace;

import java.time.Instant;
import java.util.Optional;

/** Persistent immutable project-shelf metadata; HTTP serialization is deliberately out of scope. */
public record ProjectDocument(
        String id,
        String projectId,
        String name,
        long sizeBytes,
        String sha256,
        Optional<String> sourceThreadId,
        ProjectDocumentSourceKind sourceKind,
        Optional<String> sourceName,
        Instant createdAt,
        Instant updatedAt) {

    public ProjectDocument {
        sourceThreadId = sourceThreadId == null ? Optional.empty() : sourceThreadId;
        sourceName = sourceName == null ? Optional.empty() : sourceName;
    }
}

package io.haifa.agent.superharness.workspace;

import java.util.Objects;

/** Result of restoring a trashed document. */
public record RestoreDocumentResult(
        String outcome,
        ProjectDocument document) {

    public RestoreDocumentResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(document, "document");
    }
}

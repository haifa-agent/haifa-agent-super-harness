package io.haifa.agent.superharness.workspace;

import java.util.List;
import java.util.Objects;

/** Paged list of trashed documents. */
public record TrashDocumentList(
        List<TrashDocument> documents,
        int total,
        int limit,
        int offset) {

    public TrashDocumentList {
        Objects.requireNonNull(documents, "documents");
        documents = List.copyOf(documents);
    }
}

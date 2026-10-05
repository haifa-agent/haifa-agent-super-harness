package io.haifa.agent.superharness.server.api.dto;

import java.util.List;

public record ProjectDocumentsResponse(
        List<ProjectDocumentResponse> documents,
        int total,
        int limit,
        int offset) {}

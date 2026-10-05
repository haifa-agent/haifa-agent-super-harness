package io.haifa.agent.superharness.server.api.dto;

import java.util.List;

public record TrashListResponse(
        List<TrashDocumentResponse> documents,
        int total,
        int limit,
        int offset) {}

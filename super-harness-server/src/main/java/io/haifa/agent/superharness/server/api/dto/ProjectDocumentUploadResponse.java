package io.haifa.agent.superharness.server.api.dto;

public record ProjectDocumentUploadResponse(
        ProjectDocumentResponse document,
        boolean deduplicated) {}

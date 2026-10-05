package io.haifa.agent.superharness.server.api.dto;

public record TrashRestoreResponse(
        String outcome,
        ProjectDocumentResponse document) {}

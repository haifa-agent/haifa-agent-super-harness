package io.haifa.agent.superharness.server.api.dto;

public record ProjectDocumentResponse(
        String id,
        String name,
        long size_bytes,
        String sha256,
        boolean content_missing,
        String source_thread_id,
        String source_kind,
        String source_name,
        String created_at,
        String updated_at) {}

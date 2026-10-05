package io.haifa.agent.superharness.server.api.dto;

public record TrashDocumentResponse(
        String id,
        String name,
        long size_bytes,
        String sha256,
        String source_thread_id,
        String source_kind,
        String source_name,
        String created_at,
        String updated_at,
        String trashed_at,
        TrashOriginDto trash_origin) {}

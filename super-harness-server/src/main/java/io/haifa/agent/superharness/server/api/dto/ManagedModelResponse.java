package io.haifa.agent.superharness.server.api.dto;

public record ManagedModelResponse(
        String name,
        String display_name,
        String provider,
        String model,
        String base_url,
        Boolean enabled,
        Boolean supports_vision,
        Integer context_window,
        Integer max_tokens,
        String revision,
        String source,
        boolean has_api_key,
        boolean has_credential,
        String credential_source) {}

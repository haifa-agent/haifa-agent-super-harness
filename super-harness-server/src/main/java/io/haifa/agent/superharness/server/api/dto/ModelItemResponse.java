package io.haifa.agent.superharness.server.api.dto;

public record ModelItemResponse(
        String id,
        String name,
        String model,
        String display_name,
        boolean supports_thinking,
        boolean supports_reasoning_effort) {}

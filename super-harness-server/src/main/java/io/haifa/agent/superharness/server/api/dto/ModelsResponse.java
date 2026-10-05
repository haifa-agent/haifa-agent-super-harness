package io.haifa.agent.superharness.server.api.dto;

import java.util.List;

public record ModelsResponse(
        List<ModelItemResponse> models,
        TokenUsageResponse token_usage) {}

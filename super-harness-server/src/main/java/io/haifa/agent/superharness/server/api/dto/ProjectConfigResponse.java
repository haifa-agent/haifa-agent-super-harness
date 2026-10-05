package io.haifa.agent.superharness.server.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ProjectConfigResponse(
        @JsonProperty("instructions_max_bytes") long instructionsMaxBytes,
        @JsonProperty("trash_retention_days") int trashRetentionDays) {}

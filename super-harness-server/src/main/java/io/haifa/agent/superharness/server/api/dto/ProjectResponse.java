package io.haifa.agent.superharness.server.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record ProjectResponse(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("instructions") String instructions,
        @JsonProperty("presentation") Map<String, String> presentation,
        @JsonProperty("status") String status,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {}

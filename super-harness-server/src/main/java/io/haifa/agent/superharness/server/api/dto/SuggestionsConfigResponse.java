package io.haifa.agent.superharness.server.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SuggestionsConfigResponse(
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("max_suggestions") int maxSuggestions) {}

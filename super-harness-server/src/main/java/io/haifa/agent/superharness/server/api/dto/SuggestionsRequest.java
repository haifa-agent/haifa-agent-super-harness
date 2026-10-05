package io.haifa.agent.superharness.server.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SuggestionsRequest(
        @JsonProperty("messages") List<SuggestionMessageDto> messages,
        @JsonProperty("n") Integer n,
        @JsonProperty("model_name") String modelName) {
    public record SuggestionMessageDto(
            @JsonProperty("role") String role,
            @JsonProperty("content") String content) {}
}

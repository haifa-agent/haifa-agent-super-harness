package io.haifa.agent.superharness.server.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record SuggestionsResponse(
        @JsonProperty("suggestions") List<String> suggestions) {}

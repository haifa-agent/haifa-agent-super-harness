package io.haifa.agent.superharness.server.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record InputPolishResponse(
        @JsonProperty("rewritten_text") String rewrittenText,
        @JsonProperty("changed") boolean changed) {}

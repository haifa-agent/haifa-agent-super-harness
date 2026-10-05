package io.haifa.agent.superharness.server.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InputPolishRequest(
        @JsonProperty("text") String text,
        @JsonProperty("locale") String locale,
        @JsonProperty("thread_id") String threadId) {}

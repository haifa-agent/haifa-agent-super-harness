package io.haifa.agent.superharness.server.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SetupStatusResponse(
        @JsonProperty("needs_setup") boolean needsSetup,
        @JsonProperty("registration_enabled") boolean registrationEnabled) {}

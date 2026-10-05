package io.haifa.agent.superharness.server.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record AuthMeResponse(
        @JsonProperty("id") String id,
        @JsonProperty("email") String email,
        @JsonProperty("system_role") String systemRole,
        @JsonProperty("needs_setup") boolean needsSetup,
        @JsonProperty("oauth_provider") String oauthProvider,
        @JsonProperty("permissions") List<String> permissions) {}

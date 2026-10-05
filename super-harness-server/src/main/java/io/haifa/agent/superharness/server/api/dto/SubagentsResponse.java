package io.haifa.agent.superharness.server.api.dto;

import java.util.List;

public record SubagentsResponse<T>(List<T> subagents) {}

package io.haifa.agent.superharness.server.api.dto;

import java.util.List;

public record SkillsListResponse<T>(List<T> skills) {}

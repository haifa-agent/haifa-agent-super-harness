package io.haifa.agent.superharness.server.api.dto;

import java.util.List;
import java.util.Map;

public record AgentResponse(
        String name,
        String display_name,
        String description,
        String model,
        List<String> tool_groups,
        List<String> skills,
        List<String> mcp_plugins,
        String knowledge_scope,
        List<String> allowed_subagents,
        Map<String, Object> model_settings,
        Boolean thinking_enabled,
        String reasoning_effort,
        String soul) {}

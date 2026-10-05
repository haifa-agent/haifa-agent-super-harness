package io.haifa.agent.superharness.server.api.dto;

public record FeaturesResponse(
        AgentsApiFeature agents_api,
        BrowserControlFeature browser_control,
        McpTasksFeature mcp_tasks,
        SubagentBatchesFeature subagent_batches,
        ConversationReferencesFeature conversation_references,
        KnowledgeBaseFeature knowledge_base) {

    public record AgentsApiFeature(boolean enabled) {}
    public record BrowserControlFeature(boolean enabled) {}
    public record McpTasksFeature(boolean enabled) {}
    public record SubagentBatchesFeature(
            boolean enabled,
            boolean repository_available,
            boolean worker_running,
            int max_running) {}
    public record ConversationReferencesFeature(boolean enabled, int max_references) {}
    public record KnowledgeBaseFeature(boolean scope_selection_enabled) {}

    public static FeaturesResponse defaults() {
        return new FeaturesResponse(
                new AgentsApiFeature(true),
                new BrowserControlFeature(false),
                new McpTasksFeature(false),
                new SubagentBatchesFeature(false, false, false, 3),
                new ConversationReferencesFeature(false, 3),
                new KnowledgeBaseFeature(false));
    }
}

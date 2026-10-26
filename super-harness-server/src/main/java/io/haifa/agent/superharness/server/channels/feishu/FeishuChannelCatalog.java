package io.haifa.agent.superharness.server.channels.feishu;

import java.util.List;

/**
 * Read-only model/agent catalog for Feishu IM commands ({@code /models}, {@code /agent}). Keeps the
 * channel adapter off package-private configuration HTTP handlers.
 */
public interface FeishuChannelCatalog {
    List<String> listExecutableModelNames();

    List<FeishuAgentBridge.AgentListItem> listAgents();

    boolean agentExists(String name);

    /** Empty catalog used by unit tests and when configuration is unavailable. */
    static FeishuChannelCatalog empty() {
        return new FeishuChannelCatalog() {
            @Override
            public List<String> listExecutableModelNames() {
                return List.of();
            }

            @Override
            public List<FeishuAgentBridge.AgentListItem> listAgents() {
                return List.of();
            }

            @Override
            public boolean agentExists(String name) {
                return false;
            }
        };
    }
}

package io.haifa.agent.superharness.server.prompt;

import io.haifa.agent.superharness.server.prompt.LeadPromptInputs;
import java.util.List;

/** Immutable product inputs for one declaration; the SDK owns the frozen Run snapshot. */
public record AgentPromptContext(String agentName, boolean bootstrap, String soul) {
    public static AgentPromptContext defaults() {
        return new AgentPromptContext(null, false, null);
    }

    public LeadPromptInputs promptInputs(List<LeadPromptInputs.Skill> skills) {
        boolean custom = agentName != null && !bootstrap;
        return new LeadPromptInputs(custom ? agentName : null, custom ? soul : null, custom, skills);
    }
}

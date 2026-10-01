package io.haifa.agent.superharness.server;

import io.haifa.agent.superharness.server.prompt.LeadPromptInputs;
import java.util.List;

/** Immutable product inputs for one declaration; the SDK owns the frozen Run snapshot. */
record AgentPromptContext(String agentName, boolean bootstrap, String soul) {
    static AgentPromptContext defaults() {
        return new AgentPromptContext(null, false, null);
    }

    LeadPromptInputs promptInputs(List<LeadPromptInputs.Skill> skills) {
        boolean custom = agentName != null && !bootstrap;
        return new LeadPromptInputs(custom ? agentName : null, custom ? soul : null, custom, skills);
    }
}

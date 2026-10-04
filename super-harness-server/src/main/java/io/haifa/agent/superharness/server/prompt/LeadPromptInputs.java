package io.haifa.agent.superharness.server.prompt;

import java.util.List;
import java.util.Objects;

/**
 * Product inputs frozen with a Bridge: selected identity, Skill list and admitted Child list.
 * Runtime execution, messages and permission decisions remain SDK-owned.
 */
public record LeadPromptInputs(String agentName, String soul, boolean selfUpdate, List<Skill> skills,
        List<Subagent> subagents, int parallelSubagents, int totalSubagents) {
    public static final String DEFAULT_AGENT_NAME = "DeerFlow 2.0";

    public LeadPromptInputs {
        skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
        subagents = List.copyOf(Objects.requireNonNull(subagents, "subagents"));
    }

    public LeadPromptInputs(String agentName, String soul, boolean selfUpdate, List<Skill> skills) {
        this(agentName, soul, selfUpdate, skills, List.of(), 3, 6);
    }

    public LeadPromptInputs withSubagents(List<Subagent> children, int parallel, int total) {
        return new LeadPromptInputs(agentName, soul, selfUpdate, skills, children, parallel, total);
    }

    public record Subagent(String name, String description) {
        public Subagent { Objects.requireNonNull(name); description = description == null ? "" : description; }
    }

    public String agentNameOrDefault() {
        return agentName == null || agentName.isBlank() ? DEFAULT_AGENT_NAME : agentName;
    }

    /** One enabled Skill exposed to the model; description text is escaped at render time. */
    public record Skill(String name, String description) {
        public Skill {
            Objects.requireNonNull(name, "name");
            description = description == null ? "" : description;
        }
    }
}

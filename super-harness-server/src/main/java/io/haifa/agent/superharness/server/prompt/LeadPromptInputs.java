package io.haifa.agent.superharness.server.prompt;

import java.util.List;
import java.util.Objects;

/**
 * The only per-Bridge inputs that shape the frozen lead-agent system prompt in this slice.
 *
 * <p>Segments that stay empty until their capability exists (subagent / memory tool / deferred
 * tools / MCP routing hints / ACP+mounts / the three clarification strings) are deliberately not
 * fields; the composer renders them as empty. {@code selfUpdate} is reserved for the paired
 * {@code W03-AgentSoulPromptLoop} slice, which owns agent identity; this slice always passes
 * {@code agentName == null} (default name), {@code soul == null} and {@code selfUpdate == false}.
 */
public record LeadPromptInputs(String agentName, String soul, boolean selfUpdate, List<Skill> skills) {
    public static final String DEFAULT_AGENT_NAME = "DeerFlow 2.0";

    public LeadPromptInputs {
        skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
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

package io.haifa.agent.superharness.server.prompt;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure combiner that renders the adapted frozen template into one Bridge-frozen {@code instructions}
 * string, computes its signature, and machine-checks every tool name mentioned against the tools
 * this Bridge actually registered.
 *
 * <p>No prompt text is ever logged or attached to an error: failures carry only resource/adaptation
 * identifiers, hashes or lengths.
 */
public final class LeadPromptComposer {
    private static final int SDK_MAX_INSTRUCTIONS_CHARS = 32_000;
    private static final String LEGACY_SKILL_FIRST_REMINDER =
            "- Skill First: Always load the relevant skill before starting **complex** tasks.\n";

    /**
     * Frozen tool names referenced by the lead-agent prompt family plus the SH tool names in use.
     * The exact set is the union required by the task prompt: inventory sections 3, 4 and 7 plus
     * the SH current tools. Matching is whole-word across the entire rendered text so plain-text
     * references (the deleted {@code <citations>} block, code blocks) cannot slip through.
     */
    static final Set<String> KNOWN_TOOL_NAMES = Set.of(
            "present_files",
            "ask_clarification",
            "review_skill_package",
            "list_uploaded_files",
            "ls",
            "read_file",
            "glob",
            "grep",
            "write_file",
            "str_replace",
            "web_search",
            "web_fetch",
            "image_search",
            "view_image",
            "bash",
            "task",
            "batch_task",
            "batch_status",
            "cancel_batch",
            "skill_manage",
            "tool_search",
            "describe_skill",
            "invoke_acp_agent",
            "knowledge_search",
            "list_knowledge_bases",
            "web_capture",
            "list_background_tasks",
            "cancel_background_task",
            "history_search",
            "history_read",
            "task_note",
            "memory_search",
            "memory_add",
            "memory_update",
            "memory_delete",
            "read_conversation",
            "write_todos",
            "setup_agent",
            "update_agent",
            "skill_load",
            "skill_resource_read");

    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9_])(?:" + String.join("|", KNOWN_TOOL_NAMES) + ")(?![A-Za-z0-9_])");

    private final LeadPromptTemplate template;

    LeadPromptComposer(LeadPromptTemplate template) {
        this.template = Objects.requireNonNull(template, "template");
    }

    public static LeadPromptComposer classpath() {
        return new LeadPromptComposer(LeadPromptTemplate.load());
    }

    public Composition compose(LeadPromptInputs inputs, Set<String> registeredToolNames) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(registeredToolNames, "registeredToolNames");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("agent_name", inputs.agentNameOrDefault());
        values.put("soul", renderSoul(inputs.soul()));
        values.put("self_update_section", renderSelfUpdate(inputs));
        boolean interactive = registeredToolNames.contains("ask_clarification");
        values.put("interaction_thinking_guidance", interactive
                ? "- **PRIORITY CHECK: If anything is unclear, missing, or has multiple interpretations, you MUST ask for clarification FIRST - do NOT proceed with work**"
                : "");
        boolean delegation = !inputs.subagents().isEmpty() && registeredToolNames.contains("task");
        values.put("subagent_thinking", delegation
                ? "- Before delegating, verify a clear benefit from specialist capability, context isolation, or independent parallel execution." : "");
        values.put("clarification_system", interactive ? template.interactiveClarification() : "");
        values.put("skills_section", renderSkills(inputs.skills()));
        values.put("memory_tool_section", "");
        values.put("deferred_tools_section", "");
        values.put("mcp_routing_hints_section", "");
        values.put("subagent_section", delegation ? renderSubagents(inputs, true) : "");
        values.put("acp_section", "");
        values.put("clarification_reminder", interactive
                ? "- **Clarification First**: ALWAYS clarify unclear/missing/ambiguous requirements BEFORE starting work - never assume or guess"
                : "");
        values.put("subagent_reminder", delegation
                ? "- Execute directly by default. Delegate only bounded, independent work with clear benefit; wait for the returned result and verify it." : "");
        values.put("skill_first_reminder", LEGACY_SKILL_FIRST_REMINDER);

        String instructions = format(template.adaptedTemplate(), values);
        String trimmed = instructions.trim();
        if (trimmed.length() > SDK_MAX_INSTRUCTIONS_CHARS) {
            throw new IllegalStateException(
                    "composed lead-agent instructions are " + trimmed.length() + " characters, over the SDK limit of "
                            + SDK_MAX_INSTRUCTIONS_CHARS);
        }
        // Catalog names/descriptions are data in the Agent namespace. Validate the same
        // template and all guidance, SOUL/Skill/other inputs with just that generated data
        // omitted. Never regex-remove blocks from user-controlled rendered text.
        Map<String, String> toolInstructionValues = new LinkedHashMap<>(values);
        if (delegation) toolInstructionValues.put("subagent_section", renderSubagents(inputs, false));
        Set<String> mentioned = mentionedTools(format(template.adaptedTemplate(), toolInstructionValues));
        Set<String> unregistered = new LinkedHashSet<>(mentioned);
        unregistered.removeAll(registeredToolNames);
        if (!unregistered.isEmpty()) {
            throw new IllegalStateException(
                    "composed lead-agent instructions reference unregistered tools: " + unregistered);
        }
        return new Composition(instructions, LeadPromptTemplate.sha256(trimmed.getBytes(StandardCharsets.UTF_8)), mentioned);
    }

    static String format(String template, Map<String, String> values) {
        StringBuilder out = new StringBuilder(template.length() + 256);
        int index = 0;
        while (index < template.length()) {
            char character = template.charAt(index);
            if (character == '{' && index + 1 < template.length() && template.charAt(index + 1) == '{') {
                out.append('{');
                index += 2;
            } else if (character == '}' && index + 1 < template.length() && template.charAt(index + 1) == '}') {
                out.append('}');
                index += 2;
            } else if (character == '{') {
                int close = template.indexOf('}', index + 1);
                String name = template.substring(index + 1, close);
                String value = values.get(name);
                if (value == null) {
                    throw new IllegalStateException("no value supplied for lead-agent placeholder at index " + index);
                }
                out.append(value);
                index = close + 1;
            } else {
                out.append(character);
                index++;
            }
        }
        return out.toString();
    }

    static Set<String> mentionedTools(String text) {
        Matcher matcher = TOOL_NAME_PATTERN.matcher(text);
        Set<String> result = new LinkedHashSet<>();
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    private static String renderSoul(String soul) {
        if (soul == null || soul.isBlank()) {
            return "";
        }
        return "<soul>\n" + escape(soul) + "\n</soul>\n";
    }

    private static String renderSelfUpdate(LeadPromptInputs inputs) {
        // Reserved for W03-AgentSoulPromptLoop; this slice always passes selfUpdate=false.
        if (!inputs.selfUpdate()) {
            return "";
        }
        return "<self_update>\n"
                + "You are running as the custom agent **" + inputs.agentNameOrDefault() + "** with a persisted SOUL and configuration.\n"
                + "When the user asks you to update your own description, personality or behaviour, you MUST persist the change with the `update_agent` tool.\n"
                + "Always pass the FULL replacement text for `soul`; only pass the fields that should change.\n"
                + "</self_update>\n";
    }

    private static String renderSkills(List<LeadPromptInputs.Skill> skills) {
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append("<skill_system>\n")
                .append("You have access to skills that provide optimized workflows for specific tasks.\n\n")
                .append("**Loading Pattern:**\n")
                .append("1. When a user query matches a skill's use case, call `skill_load` with that skill's name in the `skill` argument.\n")
                .append("2. Read and follow the returned instructions precisely.\n")
                .append("3. Call `skill_resource_read` with the `skill` and `path` arguments for referenced resources only when needed during execution.\n\n")
                .append("<available_skills>\n");
        for (LeadPromptInputs.Skill skill : skills) {
            out.append("    <skill>\n")
                    .append("        <name>").append(escape(skill.name())).append("</name>\n")
                    .append("        <description>").append(escape(skill.description())).append("</description>\n")
                    .append("    </skill>\n");
        }
        out.append("</available_skills>\n</skill_system>");
        return out.toString();
    }

    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Adapted frozen prompt.py delegation guidance, rendered through its existing placeholders. */
    private static String renderSubagents(LeadPromptInputs inputs, boolean includeCatalogData) {
        StringBuilder out = new StringBuilder("<subagent_system>\n")
                .append("Execute directly by default. Delegate only when specialist capability, context isolation, or independent parallel execution provides a clear benefit.\n")
                .append("Do not delegate tightly coupled steps, shared mutable state, or work whose dependencies prevent independent execution.\n")
                .append("Give each child a bounded objective, necessary context, constraints, and a precise expected output.\n")
                .append("You may run up to ").append(inputs.parallelSubagents())
                .append(" child agents concurrently and delegate at most ").append(inputs.totalSubagents())
                .append(" times in this Run. Excess concurrent calls wait for SDK admission; do not repeat them.\n")
                .append("The `task` tool waits for the child result. No polling is needed. Review the result before responding.\n")
                .append("Use the actual SDK arguments: {\"agent\":\"a name from available_subagents\",\"objective\":\"A bounded objective with necessary context\"}.\n")
                .append("<available_subagents>\n");
        for (var child : includeCatalogData ? inputs.subagents() : List.<LeadPromptInputs.Subagent>of()) {
            String description = child.name().equals("general-purpose")
                    ? "For bounded work with clear delegation benefit from specialist capability, context isolation, or independent parallel execution."
                    : child.description().lines().findFirst().orElse("");
            out.append("  <subagent><name>").append(escape(child.name())).append("</name><description>")
                    .append(escape(description)).append("</description></subagent>\n");
        }
        return out.append("</available_subagents>\n</subagent_system>").toString();
    }

    /** Full instructions/hash, with Tool instruction references excluding generated Agent catalog data. */
    public record Composition(String instructions, String signature, Set<String> mentionedToolNames) {}

}

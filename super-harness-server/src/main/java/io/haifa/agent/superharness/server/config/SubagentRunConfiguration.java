package io.haifa.agent.superharness.server.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.server.run.HaifaRunBridge.ModelBinding;
import io.haifa.agent.sdk.tool.JavaTool;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/** A product declaration frozen at Run admission; contains no execution state. */
public record SubagentRunConfiguration(List<Child> children, Settings settings, String fingerprint) {
    public SubagentRunConfiguration {
        children = List.copyOf(children);
    }

    public static SubagentRunConfiguration disabled() {
        return new SubagentRunConfiguration(List.of(), Settings.defaults(), "disabled");
    }

    public record Settings(int parallel, int total, int runningCapacity) {
        public Settings {
            runningCapacity = Math.max(1, Math.min(64, runningCapacity));
            parallel = Math.max(1, Math.min(runningCapacity, Math.min(64, parallel)));
            total = Math.max(1, Math.min(50, total));
        }
        public static Settings defaults() { return new Settings(3, 6, 3); }
        public static Settings fromEnvironment(java.util.Map<String, String> environment) {
            int capacity = configured(environment, "HAIFA_SUPER_HARNESS_SUBAGENT_MAX_RUNNING", 3);
            return new Settings(configured(environment, "HAIFA_SUPER_HARNESS_SUBAGENT_MAX_CONCURRENT", capacity),
                    configured(environment, "HAIFA_SUPER_HARNESS_SUBAGENT_MAX_TOTAL_PER_RUN", 6), capacity);
        }
        private static int configured(java.util.Map<String, String> environment, String key, int fallback) {
            String value = environment.get(key);
            if (value == null || value.isBlank()) return fallback;
            try { return Integer.parseInt(value.trim()); }
            catch (NumberFormatException failure) { throw new IllegalArgumentException(key + " must be an integer"); }
        }
    }

    public record Child(ObjectNode definition, ModelBinding model) {
        public Child { definition = definition.deepCopy(); }
        @Override public ObjectNode definition() { return definition.deepCopy(); }
        public String name() { return definition.path("name").asText(); }
        public String description() { return definition.path("description").asText(); }
        public String instructions() { return definition.path("system_prompt").asText(); }
        public int turns() { return definition.path("max_turns").intValue(); }
        public long timeoutMillis() { return Math.multiplyExact(definition.path("timeout_seconds").longValue(), 1000L); }
        public String profileId() { return "child/" + name(); }

        /** Null inherits the actual Parent declaration, whereas [] grants nothing. */
        public Set<String> allowedTools(Set<String> parentTools) {
            Set<String> allowed = new TreeSet<>();
            JsonNode tools = definition.get("tools");
            if (tools == null || tools.isNull()) allowed.addAll(parentTools);
            else tools.forEach(tool -> allowed.add(tool.asText()));
            allowed.retainAll(parentTools);
            definition.path("disallowed_tools").forEach(tool -> allowed.remove(tool.asText()));
            allowed.removeAll(Set.of("task", "ask_clarification", "present_files"));
            return Set.copyOf(allowed);
        }
    }

    public static SubagentRunConfiguration project(SubagentCatalogStore.RuntimeSnapshot catalog,
            Optional<Set<String>> allowedNames, ModelBinding parent, Function<String, ModelBinding> models,
            Settings settings) {
        List<Child> children = catalog.definitions().stream()
                .filter(definition -> allowedNames.map(names -> names.contains(definition.path("name").asText())).orElse(true))
                .map(definition -> new Child(definition,
                        "inherit".equals(definition.path("model").asText())
                                || parent.name().equals(definition.path("model").asText())
                                ? parent : models.apply(definition.path("model").asText())))
                .toList();
        StringBuilder content = new StringBuilder(settings.toString());
        children.forEach(child -> content.append('\n').append(child.definition())
                .append('\n').append(child.model().name()).append('@').append(child.model().revision())
                .append(':').append(child.model().snapshot().configurationDigest()));
        return new SubagentRunConfiguration(children, settings, hash(content.toString()));
    }

    /** Includes the real Tool specs, MCP and Skill inputs used by this Bridge. */
    public String signature(List<? extends JavaTool<?, ?>> tools, String mcpSignature, String skillSignature) {
        return signature(tools, mcpSignature, skillSignature, Set.of());
    }

    public String signature(List<? extends JavaTool<?, ?>> tools, String mcpSignature, String skillSignature,
            Set<String> platformToolNames) {
        StringBuilder value = new StringBuilder(fingerprint).append('\n').append(mcpSignature).append('\n').append(skillSignature);
        append(value, new TreeSet<>(platformToolNames));
        tools.stream().sorted(java.util.Comparator.comparing(tool -> tool.spec().name().value()))
                .forEach(tool -> {
                    var spec = tool.spec();
                    // JavaToolSpec is an ordinary class: Object.toString() would fingerprint
                    // object identity and rebuild otherwise identical declarations every Turn.
                    append(value, spec.name().value()); append(value, spec.alias()); append(value, spec.version());
                    append(value, spec.inputType().getName()); append(value, spec.outputType().getName());
                    append(value, spec.title()); append(value, spec.description()); append(value, spec.timeout());
                    append(value, spec.pure()); append(value, spec.sideEffects().stream().map(Object::toString).sorted().toList());
                    append(value, new TreeSet<>(spec.networkHosts())); append(value, spec.idempotency());
                    append(value, spec.risk()); append(value, spec.approvalRequirement());
                });
        return hash(value.toString());
    }

    public Set<String> names() { return children.stream().map(Child::name).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
    public boolean enabled() { return !children.isEmpty(); }

    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 is required", failure); }
    }
    private static void append(StringBuilder target, Object value) {
        String text = String.valueOf(value);
        target.append('\n').append(text.length()).append(':').append(text);
    }
}

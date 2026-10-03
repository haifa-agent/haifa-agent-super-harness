package io.haifa.agent.superharness.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SubagentRunConfigurationTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void nullAndEmptyToolsStayDistinctAndDeniedToolsCannotReappear() throws Exception {
        var definition = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("""
                {"name":"researcher","description":"Research","system_prompt":"Do bounded work",
                "tools":null,"disallowed_tools":["write_file","task"],"model":"inherit","max_turns":150,"timeout_seconds":900}
                """);
        var child = new SubagentRunConfiguration.Child(definition, LocalProbeModelTestSupport.binding());
        assertThat(child.allowedTools(Set.of("read_file", "write_file", "task", "ask_clarification", "present_files"))).containsExactly("read_file");
        definition.putArray("tools");
        var empty = new SubagentRunConfiguration.Child(definition, child.model());
        assertThat(empty.allowedTools(Set.of("read_file"))).isEmpty();
        assertThat(child.allowedTools(Set.of("read_file"))).containsExactly("read_file"); // detached snapshot
        assertThat(child.allowedTools(Set.of("read_file", "mcp.discovered.read"))).containsExactlyInAnyOrder("read_file", "mcp.discovered.read");
        assertThat(child.turns()).isEqualTo(150);
        assertThat(child.timeoutMillis()).isEqualTo(900_000L);
    }
    @Test void selectionIntersectsEligibleDefinitionsAndProducesAStableSnapshot() throws Exception {
        var first = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("{\"name\":\"researcher\",\"model\":\"inherit\"}");
        var second = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("{\"name\":\"general-purpose\",\"model\":\"inherit\"}");
        var catalog = new SubagentCatalogStore.RuntimeSnapshot(List.of(first, second), "catalog-source");
        var binding = LocalProbeModelTestSupport.binding();
        var settings = SubagentRunConfiguration.Settings.defaults();
        var projected = SubagentRunConfiguration.project(catalog, Optional.of(Set.of("researcher", "missing")), binding,
                ignored -> { throw new AssertionError("inherit must not resolve a second model"); }, settings);
        assertThat(projected.names()).containsExactly("researcher");
        assertThat(projected.fingerprint()).isEqualTo(SubagentRunConfiguration.project(catalog,
                Optional.of(Set.of("researcher")), binding, ignored -> binding, settings).fingerprint());
        assertThat(SubagentRunConfiguration.project(catalog, Optional.of(Set.of()), binding, ignored -> binding, settings).enabled()).isFalse();
        first.put("description", "changed");
        assertThat(projected.children().get(0).description()).isEmpty();
    }
    @Test void frozenSafetyClampsAreSharedByPromptAndSdkDeclarations() {
        assertThat(new SubagentRunConfiguration.Settings(80, 80, 3)).isEqualTo(new SubagentRunConfiguration.Settings(3, 50, 3));
        assertThat(new SubagentRunConfiguration.Settings(-1, 0, 0)).isEqualTo(new SubagentRunConfiguration.Settings(1, 1, 1));
        assertThat(new SubagentRunConfiguration.Settings(64, 6, 128).parallel()).isEqualTo(64);
    }

    @Test void equivalentToolInstancesHaveStableSignaturesAndChangedSpecRebuilds() {
        var projection = SubagentRunConfiguration.disabled();
        assertThat(projection.signature(List.of(tool("same")), "mcp", "skill"))
                .isEqualTo(projection.signature(List.of(tool("same")), "mcp", "skill"))
                .isNotEqualTo(projection.signature(List.of(tool("changed")), "mcp", "skill"));
    }
    private record Input() {}
    private record Output(String value) {}
    private static io.haifa.agent.sdk.tool.JavaTool<Input, Output> tool(String description) {
        return new io.haifa.agent.sdk.tool.JavaTool<>() {
            @Override public io.haifa.agent.sdk.tool.JavaToolSpec<Input, Output> spec() {
                return io.haifa.agent.sdk.tool.JavaToolSpec.builder("fixture_read", Input.class, Output.class)
                        .description(description).build();
            }
            @Override public Output invoke(Input input, io.haifa.agent.sdk.tool.JavaToolContext context) { return new Output("same"); }
        };
    }
}

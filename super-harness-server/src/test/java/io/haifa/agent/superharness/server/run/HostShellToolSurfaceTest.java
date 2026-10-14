package io.haifa.agent.superharness.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.runtime.api.ChildRunCapacity;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.superharness.server.config.HostShellToolConfig;
import io.haifa.agent.superharness.server.config.McpRunTools;
import io.haifa.agent.superharness.server.config.SubagentRunConfiguration;
import io.haifa.agent.superharness.server.prompt.AgentPromptContext;
import io.haifa.agent.superharness.server.testsupport.ControlledModel;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T-H: the host shell Tool appears on the model-visible surface only when the switch is on. */
public class HostShellToolSurfaceTest {
    public record Surface() {}

    @Test
    void keepsTheFrozenSurfaceIdenticalWhenTheSwitchIsOff(@TempDir Path temporary) throws Exception {
        try (var foundation = new DurableHaifaFoundation(
                        temporary.resolve("compatibility"), new SecretKeySpec(new byte[32], "AES"));
                var compatibility = bridge(LocalProbeModelTestSupport.binding(), foundation, null);
                var declaredOff = bridge(
                        LocalProbeModelTestSupport.binding(),
                        foundation,
                        HostShellToolConfig.fromEnvironment(Map.of(), temporary, temporary)
                                .orElse(null))) {
            assertThat(compatibility.hasTools()).isTrue();
            assertThat(declaredOff.promptSignature()).isEqualTo(compatibility.promptSignature());
            assertThat(declaredOff.hasTools()).isEqualTo(compatibility.hasTools());
        }
    }

    @Test
    void advertisesTheHostExecutionToolOnlyWhenTheSwitchIsOn(@TempDir Path temporary) throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        var config = HostShellToolConfig.fromEnvironment(
                        Map.of(HostShellToolConfig.ENVIRONMENT_KEY, "on"), workspace, temporary)
                .orElseThrow();

        assertThat(modelToolNames(temporary.resolve("off"), null)).doesNotContain("execution_run");
        assertThat(modelToolNames(temporary.resolve("on"), config)).contains("execution_run");
    }

    private static List<String> modelToolNames(Path foundationDir, HostShellToolConfig hostShell) throws Exception {
        var model = new ControlledModel();
        try (var foundation = new DurableHaifaFoundation(foundationDir, new SecretKeySpec(new byte[32], "AES"));
                var bridge = bridge(LocalProbeModelTestSupport.binding(model), foundation, hostShell)) {
            bridge.start("thread-surface-" + System.nanoTime(), "Surface probe", Optional.empty());
            long deadline = System.currentTimeMillis() + 30_000;
            while (model.requests.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }
            assertThat(model.requests).isNotEmpty();
            return model.requests.get(0).tools().stream()
                    .map(ModelToolSpecification::name)
                    .toList();
        }
    }

    private static HaifaRunBridge bridge(
            HaifaRunBridge.ModelBinding binding, DurableHaifaFoundation foundation, HostShellToolConfig hostShell) {
        var delegation = SubagentRunConfiguration.disabled();
        return new HaifaRunBridge(
                binding,
                promptTools(),
                foundation,
                McpRunTools.Projection.empty(),
                null,
                AgentPromptContext.defaults(),
                delegation,
                new ChildRunCapacity(delegation.settings().runningCapacity()),
                false,
                null,
                hostShell,
                null);
    }

    private static List<? extends JavaTool<?, ?>> promptTools() {
        return Stream.of("read_file", "write_file", "str_replace", "present_files", "list_uploaded_files",
                        "grep", "web_search", "web_fetch")
                .map(HostShellToolSurfaceTest::namedTool)
                .toList();
    }

    private static JavaTool<Surface, Surface> namedTool(String name) {
        var spec = JavaToolSpec.builder(name, Surface.class, Surface.class)
                .description("Surface fixture")
                .pure()
                .build();
        return new JavaTool<>() {
            @Override
            public JavaToolSpec<Surface, Surface> spec() {
                return spec;
            }

            @Override
            public Surface invoke(Surface input, JavaToolContext context) {
                throw new AssertionError("Controlled model must not call fixture tools");
            }
        };
    }
}
package io.haifa.agent.superharness.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.runtime.api.ChildRunCapacity;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.superharness.server.config.McpRunTools;
import io.haifa.agent.superharness.server.config.SkillRunTools;
import io.haifa.agent.superharness.server.config.SkillScriptProjections;
import io.haifa.agent.superharness.server.config.SkillScriptToolConfig;
import io.haifa.agent.superharness.server.config.SubagentRunConfiguration;
import io.haifa.agent.superharness.server.prompt.AgentPromptContext;
import io.haifa.agent.superharness.server.testsupport.ControlledModel;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bridge-level assembly probe for the trusted Skill script Tool: mirrors the production wiring so
 * an assembly failure surfaces with its stack instead of the interaction boundary's generic 500,
 * and the declared Tool must be model-visible exactly like the host shell Tool.
 */
public class SkillScriptBridgeAssemblyTest {

    @Test
    void assemblesTheBridgeWithTheDeclaredSkillScriptTool(@TempDir Path temporary) throws Exception {
        var bun = SkillScriptToolConfig.bunExecutable(
                System.getenv(SkillScriptToolConfig.BUN_ENVIRONMENT_KEY));
        org.junit.jupiter.api.Assumptions.assumeTrue(bun != null,
                "the declared bun runtime is required for the bridge assembly probe");
        var projection = SkillScriptProjections.projection(true);
        var skillScript = new SkillScriptToolConfig(
                Files.createDirectories(temporary.resolve("workspace")),
                Files.createDirectories(temporary.resolve("data")),
                bun,
                Duration.ofMillis(120_000), Duration.ofMillis(600_000), 262_144, 5_000,
                SkillScriptToolConfig.DEFAULT_PROVIDER);

        var model = new ControlledModel();
        try (var foundation = new DurableHaifaFoundation(
                        temporary.resolve("foundation"), new SecretKeySpec(new byte[32], "AES"));
                var bridge = bridge(LocalProbeModelTestSupport.binding(model), foundation, projection, skillScript)) {
            assertThat(bridge.skillScriptToolNames()).containsExactly(SkillScriptToolConfig.TOOL_NAME);
            bridge.start("thread-skill-script-" + System.nanoTime(), "Skill script surface probe", Optional.empty());
            long deadline = System.currentTimeMillis() + 30_000;
            while (model.requests.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }
            assertThat(model.requests).isNotEmpty();
            List<String> tools = model.requests.get(0).tools().stream()
                    .map(ModelToolSpecification::name).toList();
            assertThat(tools).contains(SkillScriptToolConfig.TOOL_NAME);
        }
    }

    private static HaifaRunBridge bridge(
            HaifaRunBridge.ModelBinding binding, DurableHaifaFoundation foundation,
            SkillRunTools.Projection projection, SkillScriptToolConfig skillScript) {
        var delegation = SubagentRunConfiguration.disabled();
        return new HaifaRunBridge(
                binding,
                promptTools(),
                foundation,
                McpRunTools.Projection.empty(),
                projection,
                AgentPromptContext.defaults(),
                delegation,
                new ChildRunCapacity(delegation.settings().runningCapacity()),
                false,
                null,
                null,
                skillScript,
                null);
    }

    private static List<? extends JavaTool<Surface, Surface>> promptTools() {
        return Stream.of("read_file", "write_file", "str_replace", "present_files", "list_uploaded_files",
                        "grep", "web_search", "web_fetch")
                .map(SkillScriptBridgeAssemblyTest::namedTool)
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

    public record Surface() {}
}

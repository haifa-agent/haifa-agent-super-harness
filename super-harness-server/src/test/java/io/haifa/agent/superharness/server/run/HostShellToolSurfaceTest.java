package io.haifa.agent.superharness.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.runtime.api.ChildRunCapacity;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.superharness.server.config.HostShellToolConfig;
import io.haifa.agent.superharness.server.config.McpRunTools;
import io.haifa.agent.superharness.server.config.SubagentRunConfiguration;
import io.haifa.agent.superharness.server.prompt.AgentPromptContext;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T-H: with the switch off the frozen surface is exactly the compatibility assembly. */
public class HostShellToolSurfaceTest {
    public record Surface() {}

    @Test
    void keepsTheFrozenSurfaceIdenticalWhenTheSwitchIsOff(@TempDir Path temporary) throws Exception {
        try (var foundation = new DurableHaifaFoundation(
                        temporary.resolve("compatibility"), new SecretKeySpec(new byte[32], "AES"));
                var compatibility = bridge(foundation, null);
                var declaredOff = bridge(
                        foundation,
                        HostShellToolConfig.fromEnvironment(Map.of(), temporary, temporary)
                                .orElse(null))) {
            assertThat(compatibility.hasTools()).isTrue();
            assertThat(declaredOff.promptSignature()).isEqualTo(compatibility.promptSignature());
            assertThat(declaredOff.promptSignature()).isNotBlank();
            assertThat(declaredOff.hasTools()).isEqualTo(compatibility.hasTools());
        }
    }

    private static HaifaRunBridge bridge(DurableHaifaFoundation foundation, HostShellToolConfig hostShell) {
        var delegation = SubagentRunConfiguration.disabled();
        return new HaifaRunBridge(
                LocalProbeModelTestSupport.binding(),
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
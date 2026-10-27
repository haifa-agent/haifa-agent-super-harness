package io.haifa.agent.superharness.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.runtime.api.ChildRunCapacity;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.superharness.server.config.ExecutionProvider;
import io.haifa.agent.superharness.server.config.ExecutionProviderConfig;
import io.haifa.agent.superharness.server.config.McpRunTools;
import io.haifa.agent.superharness.server.config.SubagentRunConfiguration;
import io.haifa.agent.superharness.server.prompt.AgentPromptContext;
import io.haifa.agent.superharness.server.testsupport.ControlledModel;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ContainerExecutionSurfaceTest {
    private static final String VALID_IMAGE =
            "deer-flow-aio@sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void advertisesExecutionRunToolWhenDockerAioIsEnabled(@TempDir Path temporary) throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        var dockerConfig = ExecutionProviderConfig.fromEnvironment(
                        Map.of(
                                ExecutionProviderConfig.PROVIDER_ENVIRONMENT_KEY, "docker-aio",
                                ExecutionProviderConfig.DOCKER_IMAGE_KEY, VALID_IMAGE),
                        workspace,
                        temporary)
                .orElseThrow();

        assertThat(dockerConfig.provider()).isEqualTo(ExecutionProvider.DOCKER_AIO);

        assertThat(modelToolNames(temporary.resolve("disabled"), null)).doesNotContain("execution_run");
        assertThat(modelToolNames(temporary.resolve("docker-aio"), dockerConfig)).contains("execution_run");
    }

    private static List<String> modelToolNames(Path foundationDir, ExecutionProviderConfig executionProvider)
            throws Exception {
        var model = new ControlledModel();
        try (var foundation = new DurableHaifaFoundation(foundationDir, new SecretKeySpec(new byte[32], "AES"));
                var bridge = bridge(LocalProbeModelTestSupport.binding(model), foundation, executionProvider)) {
            bridge.start("thread-surface-" + System.nanoTime(), "Surface probe", java.util.Optional.empty());
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

    public record Surface() {}

    private static JavaTool<Surface, Surface> namedTool(String name) {
        var spec = io.haifa.agent.sdk.tool.JavaToolSpec.builder(name, Surface.class, Surface.class)
                .description("Surface fixture")
                .pure()
                .build();
        return new JavaTool<>() {
            @Override
            public io.haifa.agent.sdk.tool.JavaToolSpec<Surface, Surface> spec() {
                return spec;
            }

            @Override
            public Surface invoke(Surface input, io.haifa.agent.sdk.tool.JavaToolContext context) {
                throw new AssertionError("Controlled model must not call fixture tools");
            }
        };
    }

    private static List<? extends JavaTool<?, ?>> promptTools() {
        return java.util.stream.Stream.of(
                        "read_file", "write_file", "str_replace", "present_files", "list_uploaded_files",
                        "grep", "web_search", "web_fetch")
                .map(ContainerExecutionSurfaceTest::namedTool)
                .toList();
    }

    private static HaifaRunBridge bridge(
            HaifaRunBridge.ModelBinding binding,
            DurableHaifaFoundation foundation,
            ExecutionProviderConfig executionProvider) {
        List<? extends JavaTool<?, ?>> tools = promptTools();
        return new HaifaRunBridge(
                binding,
                tools,
                foundation,
                McpRunTools.Projection.empty(),
                null,
                AgentPromptContext.defaults(),
                SubagentRunConfiguration.disabled(),
                new ChildRunCapacity(1),
                false,
                null,
                executionProvider,
                null,
                null,
                null,
                "test-owner",
                "test-thread",
                null);
    }
}

package io.haifa.agent.superharness.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.sdk.api.SdkCaller;
import io.haifa.agent.starter.HostExecutionPlatforms;
import io.haifa.agent.tool.api.ToolSideEffect;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The declared host shell Tool assembles through the public SDK entry with the SH product bounds. */
class HostShellExecutionPlatformTest {
    @Test
    void declaresTheHostShellToolWithTheProductBounds(@TempDir Path temporary) throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path dataRoot = Files.createDirectories(temporary.resolve("data"));
        var caller = SdkCaller.defaultPublicUser();

        try (var platform = HostExecutionPlatforms.hostGuarded(
                new HostExecutionPlatforms.HostExecutionSpec(
                        workspace, dataRoot, Duration.ofMillis(120_000), Duration.ofMillis(600_000), 65_536, 1_000),
                caller.tenant(),
                caller.principal())) {
            assertThat(platform.toolNames()).contains("execution_run");
            assertThat(platform.registration().alias().value()).isEqualTo("execution_run");
            assertThat(platform.registration().definition().timeout()).isEqualTo(Duration.ofMillis(600_000));
            assertThat(platform.registration().definition().sideEffects())
                    .contains(ToolSideEffect.PROCESS_EXECUTION, ToolSideEffect.NETWORK_ACCESS);
            assertThat(platform.registration().providerBindingReference()).startsWith("host:execution:v1:sha256:");
            assertThat(platform.securitySummary()).contains("network=host");
            assertThat(platform.registration().definition().name().value()).isEqualTo("execution_run");
            assertThat(platform.registration().definition().inputSchema().document()).containsKey("properties");
            assertThat(inputProperties(platform))
                    .containsKeys("mode", "content", "purpose")
                    .doesNotContainKeys("workdir", "cwd", "workingDirectory", "workspaceId");
        }
    }

    /** T-F subset: the declared schema rejects an oversized timeout and an oversized command. */
    @Test
    void rejectsInputOutsideTheDeclaredProductBounds(@TempDir Path temporary) throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path dataRoot = Files.createDirectories(temporary.resolve("data"));
        var caller = SdkCaller.defaultPublicUser();
        var validator = new JsonSchema202012Validator();

        try (var platform = HostExecutionPlatforms.hostGuarded(
                new HostExecutionPlatforms.HostExecutionSpec(
                        workspace, dataRoot, Duration.ofMillis(120_000), Duration.ofMillis(600_000), 65_536, 1_000),
                caller.tenant(),
                caller.principal())) {
            var schema = platform.registration().definition().inputSchema();

            assertThat(validator.validate(schema, command(600_000, "echo haifa")).errors())
                    .isEmpty();
            assertThat(validator.validate(schema, command(600_001, "echo haifa")).errors())
                    .isNotEmpty();
            assertThat(validator.validate(schema, command(600_000, "x".repeat(40_000))).errors())
                    .isNotEmpty();
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> inputProperties(
            HostExecutionPlatforms.HostExecutionPlatform platform) {
        return (java.util.Map<String, Object>)
                platform.registration().definition().inputSchema().document().get("properties");
    }

    private static Map<String, Object> command(long timeoutMillis, String content) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("mode", "COMMAND");
        arguments.put("content", content);
        arguments.put("purpose", "host shell wiring acceptance");
        arguments.put("timeoutMillis", timeoutMillis);
        return arguments;
    }
}
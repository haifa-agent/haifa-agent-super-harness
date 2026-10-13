package io.haifa.agent.superharness.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.sdk.api.SdkCaller;
import io.haifa.agent.starter.HostExecutionPlatforms;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
        }
    }
}

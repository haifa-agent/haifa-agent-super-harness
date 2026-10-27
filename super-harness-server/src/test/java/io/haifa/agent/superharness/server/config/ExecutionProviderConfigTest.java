package io.haifa.agent.superharness.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.sandbox.docker.DockerAioLifecycleMode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutionProviderConfigTest {
    private static final Path WORKSPACE = Path.of("target/test-workspace");
    private static final Path DATA = Path.of("target/test-data");
    private static final String VALID_IMAGE =
            "deer-flow-aio@sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void emptyEnvironmentReturnsEmpty() {
        assertThat(ExecutionProviderConfig.fromEnvironment(Map.of(), WORKSPACE, DATA)).isEmpty();
    }

    @Test
    void legacyHostShellSwitchWorks() {
        var on = ExecutionProviderConfig.fromEnvironment(
                Map.of(ExecutionProviderConfig.LEGACY_HOST_SHELL_KEY, "on"), WORKSPACE, DATA);
        assertThat(on).isPresent();
        assertThat(on.get().provider()).isEqualTo(ExecutionProvider.HOST_GUARDED);
        assertThat(on.get().isEnabled()).isTrue();

        var off = ExecutionProviderConfig.fromEnvironment(
                Map.of(ExecutionProviderConfig.LEGACY_HOST_SHELL_KEY, "off"), WORKSPACE, DATA);
        assertThat(off).isEmpty();
    }

    @Test
    void explicitDisabledReturnsEmpty() {
        var disabled = ExecutionProviderConfig.fromEnvironment(
                Map.of(ExecutionProviderConfig.PROVIDER_ENVIRONMENT_KEY, "disabled"), WORKSPACE, DATA);
        assertThat(disabled).isEmpty();
    }

    @Test
    void disabledWithHostShellOnThrowsConflict() {
        assertThatThrownBy(() -> ExecutionProviderConfig.fromEnvironment(
                        Map.of(
                                ExecutionProviderConfig.PROVIDER_ENVIRONMENT_KEY, "disabled",
                                ExecutionProviderConfig.LEGACY_HOST_SHELL_KEY, "on"),
                        WORKSPACE,
                        DATA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicts with");
    }

    @Test
    void explicitHostGuardedResolves() {
        var host = ExecutionProviderConfig.fromEnvironment(
                Map.of(ExecutionProviderConfig.PROVIDER_ENVIRONMENT_KEY, "host-guarded"), WORKSPACE, DATA);
        assertThat(host).isPresent();
        assertThat(host.get().provider()).isEqualTo(ExecutionProvider.HOST_GUARDED);
        assertThat(host.get().dockerConfiguration()).isEmpty();
    }

    @Test
    void dockerAioWithoutImageThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> ExecutionProviderConfig.fromEnvironment(
                        Map.of(ExecutionProviderConfig.PROVIDER_ENVIRONMENT_KEY, "docker-aio"),
                        WORKSPACE,
                        DATA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ExecutionProviderConfig.DOCKER_IMAGE_KEY);
    }

    @Test
    void dockerAioWithInvalidImagePatternThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> ExecutionProviderConfig.fromEnvironment(
                        Map.of(
                                ExecutionProviderConfig.PROVIDER_ENVIRONMENT_KEY, "docker-aio",
                                ExecutionProviderConfig.DOCKER_IMAGE_KEY, "latest"),
                        WORKSPACE,
                        DATA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("image must be a locked repository@sha256 digest");
    }

    @Test
    void dockerAioWithHostShellOnThrowsConflict() {
        assertThatThrownBy(() -> ExecutionProviderConfig.fromEnvironment(
                        Map.of(
                                ExecutionProviderConfig.PROVIDER_ENVIRONMENT_KEY, "docker-aio",
                                ExecutionProviderConfig.DOCKER_IMAGE_KEY, VALID_IMAGE,
                                ExecutionProviderConfig.LEGACY_HOST_SHELL_KEY, "on"),
                        WORKSPACE,
                        DATA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be used together");
    }

    @Test
    void dockerAioResolvesWithDefaults() {
        var config = ExecutionProviderConfig.fromEnvironment(
                Map.of(
                        ExecutionProviderConfig.PROVIDER_ENVIRONMENT_KEY, "docker-aio",
                        ExecutionProviderConfig.DOCKER_IMAGE_KEY, VALID_IMAGE),
                WORKSPACE,
                DATA);
        assertThat(config).isPresent();
        assertThat(config.get().provider()).isEqualTo(ExecutionProvider.DOCKER_AIO);
        assertThat(config.get().isEnabled()).isTrue();

        var docker = config.get().dockerConfiguration().orElseThrow();
        assertThat(docker.image()).isEqualTo(VALID_IMAGE);
        assertThat(docker.containerWorkspaceRoot()).isEqualTo(ExecutionProviderConfig.DEFAULT_CONTAINER_WORKSPACE_ROOT);
        assertThat(docker.installationId()).isEqualTo(ExecutionProviderConfig.DEFAULT_INSTALLATION_ID);
        assertThat(docker.nanoCpus()).isEqualTo(ExecutionProviderConfig.DEFAULT_NANO_CPUS);
        assertThat(docker.memoryBytes()).isEqualTo(ExecutionProviderConfig.DEFAULT_MEMORY_BYTES);
        assertThat(docker.pidsLimit()).isEqualTo(ExecutionProviderConfig.DEFAULT_PIDS_LIMIT);
        assertThat(docker.maxConcurrentSessions()).isEqualTo(ExecutionProviderConfig.DEFAULT_MAX_CONCURRENT_SESSIONS);
        assertThat(docker.aioPort()).isEqualTo(ExecutionProviderConfig.DEFAULT_AIO_PORT);
        assertThat(docker.engineTimeout()).isEqualTo(ExecutionProviderConfig.DEFAULT_ENGINE_TIMEOUT);
        assertThat(docker.stopTimeout()).isEqualTo(ExecutionProviderConfig.DEFAULT_STOP_TIMEOUT);
        assertThat(docker.lifecycleMode()).isEqualTo(DockerAioLifecycleMode.WARM_SESSION);
        assertThat(docker.idleTimeout()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void dockerAioResolvesCustomLifecycleAndIdleTimeout() {
        var config = ExecutionProviderConfig.fromEnvironment(
                Map.of(
                        ExecutionProviderConfig.PROVIDER_ENVIRONMENT_KEY, "docker-aio",
                        ExecutionProviderConfig.DOCKER_IMAGE_KEY, VALID_IMAGE,
                        ExecutionProviderConfig.DOCKER_LIFECYCLE_KEY, "ephemeral",
                        ExecutionProviderConfig.DOCKER_IDLE_TIMEOUT_MS_KEY, "300000"),
                WORKSPACE,
                DATA);
        assertThat(config).isPresent();
        var docker = config.get().dockerConfiguration().orElseThrow();
        assertThat(docker.lifecycleMode()).isEqualTo(DockerAioLifecycleMode.EPHEMERAL);
        assertThat(docker.idleTimeout()).isEqualTo(Duration.ofMillis(300_000));
    }

    @Test
    void dockerAioResolvesCustomLimits() {
        var config = ExecutionProviderConfig.fromEnvironment(
                Map.of(
                        ExecutionProviderConfig.PROVIDER_ENVIRONMENT_KEY, "docker-aio",
                        ExecutionProviderConfig.DOCKER_IMAGE_KEY, VALID_IMAGE,
                        ExecutionProviderConfig.DOCKER_NANO_CPUS_KEY, "4000000000",
                        ExecutionProviderConfig.DOCKER_MEMORY_BYTES_KEY, "2147483648",
                        ExecutionProviderConfig.DOCKER_PIDS_LIMIT_KEY, "512",
                        ExecutionProviderConfig.DOCKER_MAX_CONCURRENT_SESSIONS_KEY, "16",
                        ExecutionProviderConfig.DOCKER_ENGINE_TIMEOUT_MS_KEY, "15000",
                        ExecutionProviderConfig.DOCKER_STOP_TIMEOUT_MS_KEY, "8000"),
                WORKSPACE,
                DATA);
        assertThat(config).isPresent();
        var docker = config.get().dockerConfiguration().orElseThrow();
        assertThat(docker.nanoCpus()).isEqualTo(4_000_000_000L);
        assertThat(docker.memoryBytes()).isEqualTo(2_147_483_648L);
        assertThat(docker.pidsLimit()).isEqualTo(512);
        assertThat(docker.maxConcurrentSessions()).isEqualTo(16);
        assertThat(docker.engineTimeout()).isEqualTo(Duration.ofMillis(15000));
        assertThat(docker.stopTimeout()).isEqualTo(Duration.ofMillis(8000));
    }

    @Test
    void unrecognizedProviderThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> ExecutionProviderConfig.fromEnvironment(
                        Map.of(ExecutionProviderConfig.PROVIDER_ENVIRONMENT_KEY, "unknown-backend"),
                        WORKSPACE,
                        DATA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unrecognized");
    }

    @Test
    void toHostShellToolConfigMapsFields() {
        var host = ExecutionProviderConfig.fromEnvironment(
                        Map.of(ExecutionProviderConfig.PROVIDER_ENVIRONMENT_KEY, "host-guarded"), WORKSPACE, DATA)
                .orElseThrow();
        HostShellToolConfig shell = host.toHostShellToolConfig();
        assertThat(shell.enabled()).isTrue();
        assertThat(shell.workspaceRoot()).isEqualTo(WORKSPACE);
        assertThat(shell.dataRoot()).isEqualTo(DATA);
    }
}

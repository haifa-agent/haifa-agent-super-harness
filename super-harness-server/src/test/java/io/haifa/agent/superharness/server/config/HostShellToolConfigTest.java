package io.haifa.agent.superharness.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The host shell Tool stays off by default and declares product bounds when enabled. */
class HostShellToolConfigTest {
    private static final Path WORKSPACE = Path.of("local-tmp", "workspace");
    private static final Path DATA = Path.of("local-tmp", "data");

    @Test
    void keepsTheToolOffUnlessTheHostExplicitlyEnablesIt() {
        assertThat(HostShellToolConfig.fromEnvironment(Map.of(), WORKSPACE, DATA)).isEmpty();
        assertThat(HostShellToolConfig.fromEnvironment(switchValue(""), WORKSPACE, DATA))
                .isEmpty();
        assertThat(HostShellToolConfig.fromEnvironment(switchValue("off"), WORKSPACE, DATA))
                .isEmpty();
        assertThat(HostShellToolConfig.fromEnvironment(switchValue("maybe"), WORKSPACE, DATA))
                .isEmpty();
        assertThat(HostShellToolConfig.isEnabled(null)).isFalse();
        assertThat(HostShellToolConfig.isEnabled("OFF")).isFalse();
    }

    @Test
    void declaresTheProductBoundsWhenEnabled() {
        HostShellToolConfig config = HostShellToolConfig.fromEnvironment(switchValue("on"), WORKSPACE, DATA)
                .orElseThrow();

        assertThat(config.enabled()).isTrue();
        assertThat(config.workspaceRoot()).isEqualTo(WORKSPACE);
        assertThat(config.dataRoot()).isEqualTo(DATA);
        assertThat(config.defaultTimeout()).isEqualTo(Duration.ofMillis(120_000));
        assertThat(config.maximumTimeout()).isEqualTo(Duration.ofMillis(600_000));
        assertThat(config.maximumOutputBytes()).isPositive();
        assertThat(config.maximumOutputLines()).isPositive();
    }

    @Test
    void acceptsTheTruthySpellingsTheEnvironmentUses() {
        for (String raw : new String[] {"on", "ON", "true", "1", "yes"}) {
            assertThat(HostShellToolConfig.isEnabled(raw)).as(raw).isTrue();
        }
    }

    @Test
    void rejectsADeclarationTheHostAssemblyCannotServe() {
        assertThatThrownBy(() -> new HostShellToolConfig(
                        true, WORKSPACE, DATA, Duration.ofSeconds(120), Duration.ofSeconds(60), 4_096, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximumTimeout");
        assertThatThrownBy(() -> new HostShellToolConfig(
                        true, WORKSPACE, DATA, Duration.ZERO, Duration.ofSeconds(60), 4_096, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultTimeout");
        assertThatThrownBy(() -> new HostShellToolConfig(
                        true, WORKSPACE, DATA, Duration.ofSeconds(60), Duration.ofSeconds(120), 0, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("output bounds");
    }

    private static Map<String, String> switchValue(String value) {
        return Map.of(HostShellToolConfig.ENVIRONMENT_KEY, value);
    }
}

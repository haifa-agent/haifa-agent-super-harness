package io.haifa.agent.superharness.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SchedulerRunConfigurationTest {
    @Test
    void defaultsMatchFrozenSchedulerConfigAndStayDisabled() {
        SchedulerRunConfiguration defaults = SchedulerRunConfiguration.defaults();
        assertThat(defaults.enabled()).isFalse();
        assertThat(defaults.pollIntervalSeconds()).isEqualTo(5);
        assertThat(defaults.minOnceDelaySeconds()).isEqualTo(60);
        assertThat(defaults.maxConcurrentRuns()).isEqualTo(3);
        assertThat(defaults.queueTimeoutSeconds()).isEqualTo(3600);
    }

    @Test
    void readsOverridesFromEnvironment() {
        SchedulerRunConfiguration configuration = SchedulerRunConfiguration.fromEnvironment(Map.of(
                SchedulerRunConfiguration.ENABLED_ENV, "true",
                SchedulerRunConfiguration.POLL_SECONDS_ENV, "10",
                SchedulerRunConfiguration.MIN_ONCE_DELAY_ENV, "30",
                SchedulerRunConfiguration.MAX_CONCURRENT_ENV, "5",
                SchedulerRunConfiguration.QUEUE_TIMEOUT_ENV, "120"));
        assertThat(configuration.enabled()).isTrue();
        assertThat(configuration.pollIntervalSeconds()).isEqualTo(10);
        assertThat(configuration.minOnceDelaySeconds()).isEqualTo(30);
        assertThat(configuration.maxConcurrentRuns()).isEqualTo(5);
        assertThat(configuration.queueTimeoutSeconds()).isEqualTo(120);
    }

    @Test
    void rejectsOutOfRangeAndMalformedValues() {
        assertThatThrownBy(() -> new SchedulerRunConfiguration(true, 0, 60, 3, 3600))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SchedulerRunConfiguration(true, 5, 60, 33, 3600))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SchedulerRunConfiguration.fromEnvironment(
                Map.of(SchedulerRunConfiguration.ENABLED_ENV, "maybe")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SchedulerRunConfiguration.fromEnvironment(
                Map.of(SchedulerRunConfiguration.POLL_SECONDS_ENV, "soon")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

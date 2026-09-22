package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalServerConfigurationTest {
    @Test
    void explicitLocalModeDefaultsOnlyTheLoopbackAddressAndPort() {
        LocalServerConfiguration configuration =
                LocalServerConfiguration.fromEnvironment(localEnvironment());

        assertThat(configuration.profile()).isEqualTo("local");
        assertThat(configuration.bindAddress().isLoopbackAddress()).isTrue();
        assertThat(configuration.port()).isEqualTo(8001);
    }

    @Test
    void rejectsImplicitOrPartiallyEnabledAnonymousMode() {
        assertThatThrownBy(() -> LocalServerConfiguration.fromEnvironment(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SUPER_HARNESS_PROFILE=local");
        assertThatThrownBy(
                        () ->
                                LocalServerConfiguration.fromEnvironment(
                                        Map.of(LocalServerConfiguration.PROFILE_ENV, "local")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DEER_FLOW_AUTH_DISABLED=1");
    }

    @Test
    void rejectsNonLoopbackBindAddress() throws Exception {
        assertThatThrownBy(
                        () ->
                                new LocalServerConfiguration(
                                        "local", InetAddress.getByName("0.0.0.0"), 8001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void rejectsNonLocalProfile() throws Exception {
        assertThatThrownBy(
                        () ->
                                new LocalServerConfiguration(
                                        "shared", InetAddress.getLoopbackAddress(), 8001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("local profile");
    }

    @Test
    void rejectsEveryProductionEnvironmentMarker() {
        for (String marker :
                new String[] {"DEER_FLOW_ENV", "ENVIRONMENT", "SUPER_HARNESS_ENVIRONMENT"}) {
            Map<String, String> environment = new HashMap<>();
            environment.putAll(localEnvironment());
            environment.put(marker, "production");
            assertThatThrownBy(() -> LocalServerConfiguration.fromEnvironment(environment))
                    .as(marker)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("forbidden");
        }
    }

    private static Map<String, String> localEnvironment() {
        return Map.of(
                LocalServerConfiguration.PROFILE_ENV,
                "local",
                LocalServerConfiguration.AUTH_DISABLED_ENV,
                "1");
    }
}

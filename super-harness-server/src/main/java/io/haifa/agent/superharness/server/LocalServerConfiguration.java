package io.haifa.agent.superharness.server;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Validated configuration for the deliberately local-only server profile.
 *
 * <p>The first Super Harness slice has no shared-server authentication. It therefore refuses to
 * start unless the profile is {@code local}, every relevant environment marker is non-production,
 * and the resolved listener is loopback. Authentication cannot be weakened by request headers.
 */
public record LocalServerConfiguration(String profile, InetAddress bindAddress, int port) {
    public static final String PROFILE_ENV = "SUPER_HARNESS_PROFILE";
    public static final String BIND_ENV = "SUPER_HARNESS_BIND_ADDRESS";
    public static final String PORT_ENV = "SUPER_HARNESS_PORT";
    public static final String AUTH_DISABLED_ENV = "DEER_FLOW_AUTH_DISABLED";
    public static final String DATA_DIR_ENV = "SUPER_HARNESS_DATA_DIR";

    private static final String LOCAL_PROFILE = "local";

    public LocalServerConfiguration {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(bindAddress, "bindAddress");
        if (!LOCAL_PROFILE.equals(profile.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "The auth-disabled bootstrap server only supports the explicit local profile");
        }
        if (!bindAddress.isLoopbackAddress()) {
            throw new IllegalArgumentException(
                    "The auth-disabled bootstrap server must bind to a loopback address");
        }
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
    }

    public InetSocketAddress socketAddress() {
        return new InetSocketAddress(bindAddress, port);
    }

    /** Repository-local by default; work packages set an explicit isolated directory. */
    public Path dataDirectory(Map<String, String> environment) {
        String configured = environment.get(DATA_DIR_ENV);
        Path path = configured == null || configured.isBlank()
                ? Path.of(".super-harness", "data")
                : Path.of(configured);
        return path.toAbsolutePath().normalize();
    }

    public static LocalServerConfiguration fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        rejectProduction(environment, "DEER_FLOW_ENV");
        rejectProduction(environment, "ENVIRONMENT");
        rejectProduction(environment, "SUPER_HARNESS_ENVIRONMENT");

        String profile = environment.get(PROFILE_ENV);
        if (profile == null || profile.isBlank()) {
            throw new IllegalArgumentException(
                    PROFILE_ENV + "=local must be set explicitly for auth-disabled mode");
        }
        if (!"1".equals(environment.get(AUTH_DISABLED_ENV))) {
            throw new IllegalArgumentException(
                    AUTH_DISABLED_ENV + "=1 must be set explicitly for auth-disabled mode");
        }
        String host = environment.getOrDefault(BIND_ENV, "127.0.0.1");
        String portValue = environment.getOrDefault(PORT_ENV, "8001");
        try {
            return new LocalServerConfiguration(
                    profile, InetAddress.getByName(host), Integer.parseInt(portValue));
        } catch (UnknownHostException | NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid local server bind configuration", exception);
        }
    }

    private static void rejectProduction(Map<String, String> environment, String name) {
        String value = environment.getOrDefault(name, "").trim().toLowerCase(Locale.ROOT);
        if (value.equals("prod") || value.equals("production")) {
            throw new IllegalArgumentException(
                    "Auth-disabled local mode is forbidden when " + name + "=" + value);
        }
    }
}

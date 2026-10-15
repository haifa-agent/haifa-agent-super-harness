package io.haifa.agent.superharness.server.config;

import io.haifa.agent.superharness.server.auth.LocalAuthService;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Validated configuration for the deliberately local-only server profile.
 *
 * <p>The auth-disabled bootstrap has no shared-server authentication. It refuses to start unless
 * the profile is {@code local}, every relevant environment marker is non-production, and the
 * resolved listener is loopback. The local auth profile authenticates every request with a
 * session, so it may bind a non-loopback address, but it must have an explicit data directory
 * that does not carry legacy auth-disabled runtime state.
 */
public record LocalServerConfiguration(
        String profile, InetAddress bindAddress, int port, boolean localAuth) {
    public static final String PROFILE_ENV = "SUPER_HARNESS_PROFILE";
    public static final String BIND_ENV = "SUPER_HARNESS_BIND_ADDRESS";
    public static final String PORT_ENV = "SUPER_HARNESS_PORT";
    public static final String AUTH_DISABLED_ENV = "DEER_FLOW_AUTH_DISABLED";
    public static final String DATA_DIR_ENV = "SUPER_HARNESS_DATA_DIR";

    private static final String LOCAL_PROFILE = "local";

    /** Convenience constructor for existing auth-disabled call sites and tests. */
    public LocalServerConfiguration(String profile, InetAddress bindAddress, int port) {
        this(profile, bindAddress, port, false);
    }

    public LocalServerConfiguration {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(bindAddress, "bindAddress");
        if (!LOCAL_PROFILE.equals(profile.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "The auth-disabled bootstrap server only supports the explicit local profile");
        }
        if (!localAuth && !bindAddress.isLoopbackAddress()) {
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

    public static LocalServerConfiguration fromEnvironment(Map<String, String> environment)
            throws IOException {
        Objects.requireNonNull(environment, "environment");
        rejectProduction(environment, "DEER_FLOW_ENV");
        rejectProduction(environment, "ENVIRONMENT");
        rejectProduction(environment, "SUPER_HARNESS_ENVIRONMENT");

        String profile = environment.get(PROFILE_ENV);
        if (profile == null || profile.isBlank()) {
            throw new IllegalArgumentException(
                    PROFILE_ENV + "=local must be set explicitly for auth-disabled mode");
        }
        boolean localAuth = "1".equals(environment.get(LocalAuthService.ENABLED));
        if (!localAuth && !"1".equals(environment.get(AUTH_DISABLED_ENV))) {
            throw new IllegalArgumentException(
                    AUTH_DISABLED_ENV + "=1 must be set explicitly for auth-disabled mode");
        }
        if (localAuth) {
            requireIsolatedLocalAuthDataRoot(environment);
        }
        String host = environment.getOrDefault(BIND_ENV, "127.0.0.1");
        String portValue = environment.getOrDefault(PORT_ENV, "8001");
        try {
            return new LocalServerConfiguration(
                    profile, InetAddress.getByName(host), Integer.parseInt(portValue), localAuth);
        } catch (UnknownHostException | NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid local server bind configuration", exception);
        }
    }

    /**
     * The local auth profile stores users and secrets in its own data root. Enforce server-side
     * that the directory is explicit and free of legacy auth-disabled state, so the checks do not
     * depend on the launcher script.
     */
    private static void requireIsolatedLocalAuthDataRoot(Map<String, String> environment)
            throws IOException {
        String configured = environment.get(DATA_DIR_ENV);
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException(
                    DATA_DIR_ENV + " must be set explicitly for local auth mode");
        }
        Path dataRoot = Path.of(configured).toAbsolutePath().normalize();
        boolean legacyRuntime = Files.exists(dataRoot.resolve("runtime/haifa-agent.sqlite"));
        boolean legacyOwner = Files.exists(dataRoot.resolve("owners/default"));
        boolean authUsers = Files.exists(dataRoot.resolve("auth/users.json"));
        if ((legacyRuntime || legacyOwner) && !authUsers) {
            throw new IllegalArgumentException(
                    DATA_DIR_ENV + " carries auth-disabled runtime or owners/default state; "
                            + "local auth requires a fresh data root or one with auth/users.json");
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

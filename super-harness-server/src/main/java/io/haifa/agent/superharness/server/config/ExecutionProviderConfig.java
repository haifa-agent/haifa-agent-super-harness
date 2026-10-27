package io.haifa.agent.superharness.server.config;

import io.haifa.agent.sandbox.docker.DockerAioLifecycleMode;
import io.haifa.agent.sandbox.docker.DockerAioSandboxConfiguration;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Resolved execution provider configuration for Super Harness.
 *
 * <p>Enforces the configuration truth table between {@code HAIFA_SUPER_HARNESS_EXECUTION_PROVIDER}
 * and legacy {@code HAIFA_SUPER_HARNESS_HOST_SHELL}. Conflicting configurations fail closed with a
 * descriptive {@link IllegalStateException}.
 */
public record ExecutionProviderConfig(
        ExecutionProvider provider,
        Path workspaceRoot,
        Path dataRoot,
        Duration defaultTimeout,
        Duration maximumTimeout,
        int maximumOutputBytes,
        int maximumOutputLines,
        Optional<DockerAioSandboxConfiguration> dockerConfiguration) {

    public static final String PROVIDER_ENVIRONMENT_KEY = "HAIFA_SUPER_HARNESS_EXECUTION_PROVIDER";
    public static final String LEGACY_HOST_SHELL_KEY = HostShellToolConfig.ENVIRONMENT_KEY;

    public static final String DOCKER_SOCKET_KEY = "HAIFA_SUPER_HARNESS_DOCKER_SOCKET";
    public static final String DOCKER_IMAGE_KEY = "HAIFA_SUPER_HARNESS_DOCKER_IMAGE";
    public static final String DOCKER_CONTAINER_WORKSPACE_ROOT_KEY =
            "HAIFA_SUPER_HARNESS_DOCKER_CONTAINER_WORKSPACE_ROOT";
    public static final String DOCKER_INSTALLATION_ID_KEY = "HAIFA_SUPER_HARNESS_DOCKER_INSTALLATION_ID";
    public static final String DOCKER_AIO_PORT_KEY = "HAIFA_SUPER_HARNESS_DOCKER_AIO_PORT";
    public static final String DOCKER_MAX_CONCURRENT_SESSIONS_KEY =
            "HAIFA_SUPER_HARNESS_DOCKER_MAX_CONCURRENT_SESSIONS";
    public static final String DOCKER_NANO_CPUS_KEY = "HAIFA_SUPER_HARNESS_DOCKER_NANO_CPUS";
    public static final String DOCKER_MEMORY_BYTES_KEY = "HAIFA_SUPER_HARNESS_DOCKER_MEMORY_BYTES";
    public static final String DOCKER_PIDS_LIMIT_KEY = "HAIFA_SUPER_HARNESS_DOCKER_PIDS_LIMIT";
    public static final String DOCKER_ENGINE_TIMEOUT_MS_KEY = "HAIFA_SUPER_HARNESS_DOCKER_ENGINE_TIMEOUT_MS";
    public static final String DOCKER_STOP_TIMEOUT_MS_KEY = "HAIFA_SUPER_HARNESS_DOCKER_STOP_TIMEOUT_MS";
    public static final String DOCKER_LIFECYCLE_KEY = "HAIFA_SUPER_HARNESS_DOCKER_LIFECYCLE";
    public static final String DOCKER_IDLE_TIMEOUT_MS_KEY = "HAIFA_SUPER_HARNESS_DOCKER_IDLE_TIMEOUT_MS";
    public static final String DOCKER_UID_KEY = "HAIFA_SUPER_HARNESS_DOCKER_UID";
    public static final String DOCKER_GID_KEY = "HAIFA_SUPER_HARNESS_DOCKER_GID";

    public static final Path DEFAULT_DOCKER_SOCKET = Path.of("/var/run/docker.sock");
    public static final String DEFAULT_CONTAINER_WORKSPACE_ROOT = "/mnt/user-data/workspace";
    public static final String DEFAULT_INSTALLATION_ID = "super-harness";
    public static final DockerAioLifecycleMode DEFAULT_LIFECYCLE = DockerAioLifecycleMode.WARM_SESSION;
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(10);
    public static final int DEFAULT_AIO_PORT = DockerAioSandboxConfiguration.DEFAULT_AIO_PORT;
    public static final int DEFAULT_MAX_CONCURRENT_SESSIONS =
            DockerAioSandboxConfiguration.DEFAULT_MAX_CONCURRENT_SESSIONS;
    public static final long DEFAULT_NANO_CPUS = 2_000_000_000L;
    public static final long DEFAULT_MEMORY_BYTES = 1024L * 1024 * 1024;
    public static final long DEFAULT_PIDS_LIMIT = 256;
    public static final Duration DEFAULT_ENGINE_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_STOP_TIMEOUT = Duration.ofSeconds(5);
    public static final Map<String, String> DEFAULT_RUNTIMES =
            Map.of("bash", "/bin/bash", "python", "/usr/bin/python3");

    public ExecutionProviderConfig {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
        Objects.requireNonNull(dataRoot, "dataRoot must not be null");
        Objects.requireNonNull(defaultTimeout, "defaultTimeout must not be null");
        Objects.requireNonNull(maximumTimeout, "maximumTimeout must not be null");
        Objects.requireNonNull(dockerConfiguration, "dockerConfiguration must not be null");
        if (defaultTimeout.isNegative() || defaultTimeout.isZero()) {
            throw new IllegalArgumentException("defaultTimeout must be positive");
        }
        if (maximumTimeout.compareTo(defaultTimeout) < 0) {
            throw new IllegalArgumentException("maximumTimeout must not be below defaultTimeout");
        }
        if (maximumOutputBytes <= 0 || maximumOutputLines <= 0) {
            throw new IllegalArgumentException("output bounds must be positive");
        }
        if (provider == ExecutionProvider.DOCKER_AIO && dockerConfiguration.isEmpty()) {
            throw new IllegalArgumentException("dockerConfiguration must be present when provider is DOCKER_AIO");
        }
    }

    public boolean isEnabled() {
        return provider.isEnabled();
    }

    /**
     * Resolves the execution provider from the process environment and trusted paths.
     *
     * @param environment process environment
     * @param workspaceRoot workspace root path
     * @param dataRoot product data directory
     * @return resolved config, or empty if disabled
     */
    public static Optional<ExecutionProviderConfig> fromEnvironment(
            Map<String, String> environment, Path workspaceRoot, Path dataRoot) {
        Objects.requireNonNull(environment, "environment must not be null");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
        Objects.requireNonNull(dataRoot, "dataRoot must not be null");

        String rawProvider = environment.get(PROVIDER_ENVIRONMENT_KEY);
        String rawLegacy = environment.get(LEGACY_HOST_SHELL_KEY);
        boolean legacyEnabled = HostShellToolConfig.isEnabled(rawLegacy);

        if (rawProvider != null && !rawProvider.isBlank()) {
            String normalized = rawProvider.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "disabled", "off", "none", "false" -> {
                    if (legacyEnabled) {
                        throw new IllegalStateException(
                                "Conflicting execution configuration: " + PROVIDER_ENVIRONMENT_KEY
                                        + "=" + rawProvider + " conflicts with "
                                        + LEGACY_HOST_SHELL_KEY + "=on");
                    }
                    yield Optional.empty();
                }
                case "host-guarded", "host_guarded", "host" -> Optional.of(new ExecutionProviderConfig(
                        ExecutionProvider.HOST_GUARDED,
                        workspaceRoot,
                        dataRoot,
                        HostShellToolConfig.DEFAULT_TIMEOUT,
                        HostShellToolConfig.MAXIMUM_TIMEOUT,
                        HostShellToolConfig.MAXIMUM_OUTPUT_BYTES,
                        HostShellToolConfig.MAXIMUM_OUTPUT_LINES,
                        Optional.empty()));
                case "docker-aio", "docker_aio", "docker" -> {
                    if (legacyEnabled) {
                        throw new IllegalStateException(
                                "Conflicting execution configuration: " + PROVIDER_ENVIRONMENT_KEY
                                        + "=" + rawProvider + " cannot be used together with "
                                        + LEGACY_HOST_SHELL_KEY + "=on");
                    }
                    DockerAioSandboxConfiguration dockerConfig = parseDockerConfiguration(environment);
                    yield Optional.of(new ExecutionProviderConfig(
                            ExecutionProvider.DOCKER_AIO,
                            workspaceRoot,
                            dataRoot,
                            HostShellToolConfig.DEFAULT_TIMEOUT,
                            HostShellToolConfig.MAXIMUM_TIMEOUT,
                            HostShellToolConfig.MAXIMUM_OUTPUT_BYTES,
                            HostShellToolConfig.MAXIMUM_OUTPUT_LINES,
                            Optional.of(dockerConfig)));
                }
                default -> throw new IllegalArgumentException(
                        "Unrecognized " + PROVIDER_ENVIRONMENT_KEY + " value: '" + rawProvider
                                + "'. Expected one of: disabled, host-guarded, docker-aio");
            };
        }

        // Provider variable not set: fall back to legacy switch
        if (legacyEnabled) {
            return Optional.of(new ExecutionProviderConfig(
                    ExecutionProvider.HOST_GUARDED,
                    workspaceRoot,
                    dataRoot,
                    HostShellToolConfig.DEFAULT_TIMEOUT,
                    HostShellToolConfig.MAXIMUM_TIMEOUT,
                    HostShellToolConfig.MAXIMUM_OUTPUT_BYTES,
                    HostShellToolConfig.MAXIMUM_OUTPUT_LINES,
                    Optional.empty()));
        }

        return Optional.empty();
    }

    public static ExecutionProviderConfig fromHostShell(HostShellToolConfig hostShell) {
        Objects.requireNonNull(hostShell, "hostShell must not be null");
        return new ExecutionProviderConfig(
                hostShell.enabled() ? ExecutionProvider.HOST_GUARDED : ExecutionProvider.DISABLED,
                hostShell.workspaceRoot(),
                hostShell.dataRoot(),
                hostShell.defaultTimeout(),
                hostShell.maximumTimeout(),
                hostShell.maximumOutputBytes(),
                hostShell.maximumOutputLines(),
                Optional.empty());
    }

    public HostShellToolConfig toHostShellToolConfig() {
        return new HostShellToolConfig(
                isEnabled(),
                workspaceRoot,
                dataRoot,
                defaultTimeout,
                maximumTimeout,
                maximumOutputBytes,
                maximumOutputLines);
    }

    private static DockerAioSandboxConfiguration parseDockerConfiguration(Map<String, String> env) {
        String image = env.get(DOCKER_IMAGE_KEY);
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required configuration '" + DOCKER_IMAGE_KEY + "' when provider is docker-aio");
        }

        Path socket = env.containsKey(DOCKER_SOCKET_KEY) && !env.get(DOCKER_SOCKET_KEY).isBlank()
                ? Path.of(env.get(DOCKER_SOCKET_KEY).trim())
                : DEFAULT_DOCKER_SOCKET;

        String containerWorkspaceRoot = env.getOrDefault(
                DOCKER_CONTAINER_WORKSPACE_ROOT_KEY, DEFAULT_CONTAINER_WORKSPACE_ROOT);
        String installationId = env.getOrDefault(DOCKER_INSTALLATION_ID_KEY, DEFAULT_INSTALLATION_ID);

        int aioPort = parseIntOrDefault(env.get(DOCKER_AIO_PORT_KEY), DEFAULT_AIO_PORT);
        int maxConcurrent = parseIntOrDefault(
                env.get(DOCKER_MAX_CONCURRENT_SESSIONS_KEY), DEFAULT_MAX_CONCURRENT_SESSIONS);
        long nanoCpus = parseLongOrDefault(env.get(DOCKER_NANO_CPUS_KEY), DEFAULT_NANO_CPUS);
        long memoryBytes = parseLongOrDefault(env.get(DOCKER_MEMORY_BYTES_KEY), DEFAULT_MEMORY_BYTES);
        long pidsLimit = parseLongOrDefault(env.get(DOCKER_PIDS_LIMIT_KEY), DEFAULT_PIDS_LIMIT);

        Duration engineTimeout = env.containsKey(DOCKER_ENGINE_TIMEOUT_MS_KEY)
                ? Duration.ofMillis(parseLongOrDefault(env.get(DOCKER_ENGINE_TIMEOUT_MS_KEY), 10_000))
                : DEFAULT_ENGINE_TIMEOUT;

        Duration stopTimeout = env.containsKey(DOCKER_STOP_TIMEOUT_MS_KEY)
                ? Duration.ofMillis(parseLongOrDefault(env.get(DOCKER_STOP_TIMEOUT_MS_KEY), 5_000))
                : DEFAULT_STOP_TIMEOUT;

        DockerAioLifecycleMode lifecycle = DEFAULT_LIFECYCLE;
        String rawLifecycle = env.get(DOCKER_LIFECYCLE_KEY);
        if (rawLifecycle != null && !rawLifecycle.isBlank()) {
            String norm = rawLifecycle.trim().toLowerCase(Locale.ROOT);
            if ("ephemeral".equals(norm) || "one-shot".equals(norm) || "oneshot".equals(norm)) {
                lifecycle = DockerAioLifecycleMode.EPHEMERAL;
            } else if ("warm".equals(norm) || "warm-session".equals(norm) || "warm_session".equals(norm) || "persistent".equals(norm)) {
                lifecycle = DockerAioLifecycleMode.WARM_SESSION;
            } else {
                throw new IllegalArgumentException("Unrecognized " + DOCKER_LIFECYCLE_KEY + " value: '" + rawLifecycle
                        + "'. Expected 'warm' or 'ephemeral'.");
            }
        }

        Duration idleTimeout = env.containsKey(DOCKER_IDLE_TIMEOUT_MS_KEY)
                ? Duration.ofMillis(parseLongOrDefault(env.get(DOCKER_IDLE_TIMEOUT_MS_KEY), 600_000))
                : DEFAULT_IDLE_TIMEOUT;

        OptionalInt uid = env.containsKey(DOCKER_UID_KEY) && !env.get(DOCKER_UID_KEY).isBlank()
                ? OptionalInt.of(Integer.parseInt(env.get(DOCKER_UID_KEY).trim()))
                : OptionalInt.empty();

        OptionalInt gid = env.containsKey(DOCKER_GID_KEY) && !env.get(DOCKER_GID_KEY).isBlank()
                ? OptionalInt.of(Integer.parseInt(env.get(DOCKER_GID_KEY).trim()))
                : OptionalInt.empty();

        return new DockerAioSandboxConfiguration(
                installationId,
                socket,
                image,
                containerWorkspaceRoot,
                DEFAULT_RUNTIMES,
                uid,
                gid,
                nanoCpus,
                memoryBytes,
                pidsLimit,
                engineTimeout,
                stopTimeout,
                aioPort,
                maxConcurrent,
                lifecycle,
                idleTimeout);
    }

    private static int parseIntOrDefault(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long parseLongOrDefault(String raw, long defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}

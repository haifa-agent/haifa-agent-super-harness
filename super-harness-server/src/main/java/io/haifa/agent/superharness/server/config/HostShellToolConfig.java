package io.haifa.agent.superharness.server.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Product declaration of the host shell Tool (the SDK's own {@code execution_run}).
 *
 * <p>The Tool is <strong>off</strong> unless the host explicitly enables it, so the default Tool
 * surface, its disclosure and the frozen prompt signature stay byte-identical: the switch is read
 * from the process environment with the same shape the other Super Harness switches use
 * ({@code HAIFA_SUPER_HARNESS_*}).
 *
 * <p>The declared bounds are a product decision, not a Runtime floor: the reference stack freezes
 * {@code bash_command_timeout: 600} seconds, so the model-visible maximum is ten minutes and the
 * default is two minutes. The SDK entry validates every value again when it assembles the platform.
 *
 * <p>{@code workspaceRoot} is the directory the Agent may execute in and {@code dataRoot} is the
 * product data directory that owns the host scratch space below it. The model can select neither a
 * workspace nor a working directory: both are trusted product facts.
 */
public record HostShellToolConfig(
        boolean enabled,
        Path workspaceRoot,
        Path dataRoot,
        Duration defaultTimeout,
        Duration maximumTimeout,
        int maximumOutputBytes,
        int maximumOutputLines) {

    /** Environment variable that enables the host shell Tool; any other value keeps it off. */
    public static final String ENVIRONMENT_KEY = "HAIFA_SUPER_HARNESS_HOST_SHELL";

    /** Product default of one invocation when the model omits {@code timeoutMillis}. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(120_000);

    /** Product maximum of one invocation; the reference stack freezes 600 seconds. */
    public static final Duration MAXIMUM_TIMEOUT = Duration.ofMillis(600_000);

    /** Bounded output budget of one invocation, aligned with the Server byte budget. */
    public static final int MAXIMUM_OUTPUT_BYTES = 262_144;

    /** Bounded output line budget of one invocation. */
    public static final int MAXIMUM_OUTPUT_LINES = 5_000;

    public HostShellToolConfig {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
        Objects.requireNonNull(dataRoot, "dataRoot must not be null");
        Objects.requireNonNull(defaultTimeout, "defaultTimeout must not be null");
        Objects.requireNonNull(maximumTimeout, "maximumTimeout must not be null");
        if (defaultTimeout.isNegative() || defaultTimeout.isZero()) {
            throw new IllegalArgumentException("defaultTimeout must be positive");
        }
        if (maximumTimeout.compareTo(defaultTimeout) < 0) {
            throw new IllegalArgumentException("maximumTimeout must not be below defaultTimeout");
        }
        if (maximumOutputBytes <= 0 || maximumOutputLines <= 0) {
            throw new IllegalArgumentException("output bounds must be positive");
        }
    }

    /**
     * Resolves the switch and the declared product facts, or an empty value when the Tool is off.
     *
     * @param environment process environment the switch is read from
     * @param workspaceRoot the single host directory the Agent may execute in
     * @param dataRoot product data directory that owns the host scratch space
     * @return the enabled declaration, or empty for the default (off) surface
     */
    public static Optional<HostShellToolConfig> fromEnvironment(
            Map<String, String> environment, Path workspaceRoot, Path dataRoot) {
        Objects.requireNonNull(environment, "environment must not be null");
        if (!isEnabled(environment.get(ENVIRONMENT_KEY))) {
            return Optional.empty();
        }
        return Optional.of(new HostShellToolConfig(
                true,
                workspaceRoot,
                dataRoot,
                DEFAULT_TIMEOUT,
                MAXIMUM_TIMEOUT,
                MAXIMUM_OUTPUT_BYTES,
                MAXIMUM_OUTPUT_LINES));
    }

    /**
     * Only an explicit, recognised truthy value enables the Tool; every other value - including a
     * missing one - keeps the default surface, so a typo can never widen the Tool surface.
     */
    public static boolean isEnabled(String rawValue) {
        if (rawValue == null) {
            return false;
        }
        return switch (rawValue.strip().toLowerCase(Locale.ROOT)) {
            case "on", "true", "1", "yes" -> true;
            default -> false;
        };
    }
}

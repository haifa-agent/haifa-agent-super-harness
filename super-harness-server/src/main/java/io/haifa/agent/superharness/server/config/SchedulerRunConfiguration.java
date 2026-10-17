package io.haifa.agent.superharness.server.config;

import java.util.Locale;
import java.util.Map;

/**
 * Single-instance scheduler configuration, mirroring the frozen DeerFlow {@code SchedulerConfig}
 * bounds. Multi-instance/lease keys are intentionally absent.
 */
public record SchedulerRunConfiguration(
        boolean enabled,
        int pollIntervalSeconds,
        int minOnceDelaySeconds,
        int maxConcurrentRuns,
        int queueTimeoutSeconds) {

    public static final String ENABLED_ENV = "HAIFA_SUPER_HARNESS_SCHEDULER_ENABLED";
    public static final String POLL_SECONDS_ENV = "HAIFA_SUPER_HARNESS_SCHEDULER_POLL_SECONDS";
    public static final String MIN_ONCE_DELAY_ENV = "HAIFA_SUPER_HARNESS_SCHEDULER_MIN_ONCE_DELAY_SECONDS";
    public static final String MAX_CONCURRENT_ENV = "HAIFA_SUPER_HARNESS_SCHEDULER_MAX_CONCURRENT_RUNS";
    public static final String QUEUE_TIMEOUT_ENV = "HAIFA_SUPER_HARNESS_SCHEDULER_QUEUE_TIMEOUT_SECONDS";

    public SchedulerRunConfiguration {
        pollIntervalSeconds = bounded(pollIntervalSeconds, 1, 300, "poll_interval_seconds");
        minOnceDelaySeconds = bounded(minOnceDelaySeconds, 1, 86_400, "min_once_delay_seconds");
        maxConcurrentRuns = bounded(maxConcurrentRuns, 1, 32, "max_concurrent_runs");
        queueTimeoutSeconds = bounded(queueTimeoutSeconds, 60, 604_800, "queue_timeout_seconds");
    }

    /** Frozen defaults with the scheduler off until explicitly enabled. */
    public static SchedulerRunConfiguration defaults() {
        return new SchedulerRunConfiguration(false, 5, 60, 3, 3600);
    }

    public static SchedulerRunConfiguration fromEnvironment(Map<String, String> environment) {
        return new SchedulerRunConfiguration(
                flag(environment, ENABLED_ENV, false),
                integer(environment, POLL_SECONDS_ENV, 5),
                integer(environment, MIN_ONCE_DELAY_ENV, 60),
                integer(environment, MAX_CONCURRENT_ENV, 3),
                integer(environment, QUEUE_TIMEOUT_ENV, 3600));
    }

    private static boolean flag(Map<String, String> environment, String key, boolean fallback) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("1") || normalized.equals("true") || normalized.equals("yes")) {
            return true;
        }
        if (normalized.equals("0") || normalized.equals("false") || normalized.equals("no")) {
            return false;
        }
        throw new IllegalArgumentException(key + " must be a boolean");
    }

    private static int integer(Map<String, String> environment, String key, int fallback) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(key + " must be an integer", failure);
        }
    }

    private static int bounded(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }
}

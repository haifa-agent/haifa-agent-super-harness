package io.haifa.agent.superharness.server.schedule;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Schedule time semantics for {@code once} / {@code cron} / {@code interval}, ported from the
 * frozen DeerFlow {@code deerflow.scheduler.schedules} module.
 *
 * <p>Every method takes an explicit {@code now} so callers can compute deterministically; no system
 * clock is read here. Instants are always normalized to UTC. Unknown time zones, malformed cron
 * expressions, out-of-range values and non-positive intervals raise
 * {@link ScheduleValidationException}.
 */
public final class ScheduleCalculator {
    public static final int MAX_INTERVAL_SECONDS = 30 * 24 * 60 * 60;
    public static final int DEFAULT_MIN_ONCE_DELAY_SECONDS = 60;

    private ScheduleCalculator() {
    }

    public static String validateTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            throw new ScheduleValidationException("Unknown timezone: " + timezone);
        }
        try {
            ZoneId zone = ZoneId.of(timezone);
            // Python zoneinfo rejects fixed-offset identifiers and Java's "UTC+08:00"-style
            // prefixed-offset aliases; only named regions (UTC, Etc/*, America/*) resolve.
            if (zone instanceof ZoneOffset || isFixedOffsetAlias(zone.getId())) {
                throw new ScheduleValidationException("Unknown timezone: " + timezone);
            }
            return timezone;
        } catch (DateTimeException failure) {
            throw new ScheduleValidationException("Unknown timezone: " + timezone);
        }
    }

    private static boolean isFixedOffsetAlias(String id) {
        if ("UT".equals(id)) {
            return true;
        }
        for (String prefix : new String[] {"UTC", "GMT", "UT"}) {
            if (id.length() > prefix.length() && id.startsWith(prefix)) {
                char suffix = id.charAt(prefix.length());
                if (suffix == '+' || suffix == '-') {
                    return true;
                }
            }
        }
        return false;
    }

    public static String normalizeCronExpression(String expression) {
        String[] parts = (expression == null ? "" : expression).trim().split("\\s+");
        int length = parts.length == 1 && parts[0].isEmpty() ? 0 : parts.length;
        if (length != 5) {
            throw new ScheduleValidationException("Cron expression must contain exactly 5 fields");
        }
        return String.join(" ", parts);
    }

    public static int parseIntervalSeconds(Map<String, Object> scheduleSpec) {
        long seconds = parseIntervalSecondsAsLong(scheduleSpec);
        if (seconds > Integer.MAX_VALUE) {
            // Validate before narrowing: intValue() would silently wrap a larger long (for example
            // 2^32 + 60) into an accepted, high-frequency interval.
            throw new ScheduleValidationException(
                    "interval schedule requires every_seconds as a positive integer");
        }
        return (int) seconds;
    }

    /** Validates the configured lower and frozen upper bounds; returns the accepted interval. */
    public static int validateIntervalSeconds(Map<String, Object> scheduleSpec, int minimumSeconds) {
        long everySeconds = parseIntervalSecondsAsLong(scheduleSpec);
        if (everySeconds < minimumSeconds) {
            throw new ScheduleValidationException(
                    "interval schedule must be at least " + minimumSeconds + " seconds");
        }
        if (everySeconds > MAX_INTERVAL_SECONDS) {
            throw new ScheduleValidationException(
                    "interval schedule must be at most " + MAX_INTERVAL_SECONDS + " seconds");
        }
        return (int) everySeconds;
    }

    private static long parseIntervalSecondsAsLong(Map<String, Object> scheduleSpec) {
        Object raw = scheduleSpec == null ? null : scheduleSpec.get("every_seconds");
        if (raw instanceof Boolean || !(raw instanceof Number number) || !isIntegral(number)
                || number.longValue() < 1) {
            throw new ScheduleValidationException(
                    "interval schedule requires every_seconds as a positive integer");
        }
        return number.longValue();
    }

    /**
     * The next trigger instant, or {@code null} for an already-past {@code once} schedule.
     *
     * <p>Matches frozen {@code next_run_at}: a naive {@code run_at} is read as wall-clock time in
     * the task's zone, cron is evaluated in the task's zone, and interval is measured from {@code
     * now}. An unsupported {@code schedule_type} raises.
     */
    public static Instant nextRunAt(String scheduleType, Map<String, Object> scheduleSpec,
            String timezone, Instant now) {
        validateTimezone(timezone);
        ZoneId zone = ZoneId.of(timezone);
        switch (scheduleType == null ? "" : scheduleType) {
            case "once" -> {
                Object raw = scheduleSpec == null ? null : scheduleSpec.get("run_at");
                if (!(raw instanceof String text)) {
                    throw new ScheduleValidationException("once schedule requires run_at");
                }
                Instant runAt = onceInstant(text, zone);
                return runAt.isAfter(now) ? runAt : null;
            }
            case "cron" -> {
                Object raw = scheduleSpec == null ? null : scheduleSpec.get("cron");
                if (!(raw instanceof String text)) {
                    throw new ScheduleValidationException("cron schedule requires schedule_spec.cron");
                }
                return CronExpression.parse(normalizeCronExpression(text)).next(now, zone);
            }
            case "interval" -> {
                int everySeconds = parseIntervalSeconds(scheduleSpec);
                return now.plusSeconds(everySeconds);
            }
            default -> throw new ScheduleValidationException("Unsupported schedule_type: " + scheduleType);
        }
    }

    /**
     * Creation-time validation for a {@code once} schedule: it must be in the future and at least
     * {@code minimumSeconds} away. Returns the accepted instant; otherwise raises with the frozen
     * REST detail strings.
     */
    public static Instant validateOnceCreation(Map<String, Object> scheduleSpec, String timezone,
            Instant now, int minimumSeconds) {
        Instant next = nextRunAt("once", scheduleSpec, timezone, now);
        if (next == null) {
            throw new ScheduleValidationException("once schedule must be in the future");
        }
        if (Duration.between(now, next).getSeconds() < minimumSeconds) {
            throw new ScheduleValidationException(
                    "once schedule must be at least " + minimumSeconds + " seconds in the future");
        }
        return next;
    }

    private static Instant onceInstant(String text, ZoneId zone) {
        try {
            return java.time.OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // Fall through to a naive local wall-clock value interpreted in the task's zone.
        }
        try {
            return LocalDateTime.parse(text).atZone(zone).toInstant();
        } catch (DateTimeParseException failure) {
            throw new ScheduleValidationException("once schedule requires a valid ISO-8601 run_at");
        }
    }

    private static boolean isIntegral(Number number) {
        return number instanceof Integer || number instanceof Long || number instanceof Short
                || number instanceof Byte;
    }
}

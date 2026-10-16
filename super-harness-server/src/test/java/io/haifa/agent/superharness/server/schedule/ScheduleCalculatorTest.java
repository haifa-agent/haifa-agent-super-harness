package io.haifa.agent.superharness.server.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScheduleCalculatorTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    // W19-M1-01 once: future / past / minimum-delay boundary.
    @Test
    void onceReturnsFutureInstantAndRejectsPast() {
        Instant next = ScheduleCalculator.nextRunAt("once", Map.of("run_at", "2026-06-01T12:00:00Z"),
                "UTC", NOW);
        assertThat(next).isEqualTo(Instant.parse("2026-06-01T12:00:00Z"));
        assertThat(ScheduleCalculator.nextRunAt("once", Map.of("run_at", "2025-01-01T00:00:00Z"),
                "UTC", NOW)).isNull();
    }

    @Test
    void onceNaiveValueIsReadInTaskTimezone() {
        assertThat(ScheduleCalculator.nextRunAt("once", Map.of("run_at", "2026-06-01T12:00:00"),
                "America/New_York", NOW)).isEqualTo(Instant.parse("2026-06-01T16:00:00Z"));
        assertThat(ScheduleCalculator.nextRunAt("once", Map.of("run_at", "2026-06-01T12:00:00+02:00"),
                "UTC", NOW)).isEqualTo(Instant.parse("2026-06-01T10:00:00Z"));
    }

    @Test
    void onceMinimumDelayBoundary() {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        Instant boundary = Instant.parse("2026-06-01T00:01:00Z");
        assertThat(ScheduleCalculator.validateOnceCreation(Map.of("run_at", boundary.toString()),
                "UTC", now, 60)).isEqualTo(boundary);
        assertThatThrownBy(() -> ScheduleCalculator.validateOnceCreation(
                Map.of("run_at", "2026-06-01T00:00:59Z"), "UTC", now, 60))
                .isInstanceOf(ScheduleValidationException.class)
                .hasMessage("once schedule must be at least 60 seconds in the future");
        assertThatThrownBy(() -> ScheduleCalculator.validateOnceCreation(
                Map.of("run_at", "2025-01-01T00:00:00Z"), "UTC", now, 60))
                .isInstanceOf(ScheduleValidationException.class)
                .hasMessage("once schedule must be in the future");
    }

    // W19-M1-02 cron: regular / cross-day / DST fold and gap, all normalized to UTC.
    @Test
    void cronRegularAndCrossDay() {
        assertThat(ScheduleCalculator.nextRunAt("cron", Map.of("cron", "0 9 * * *"), "UTC", NOW))
                .isEqualTo(Instant.parse("2026-01-01T09:00:00Z"));
        assertThat(ScheduleCalculator.nextRunAt("cron", Map.of("cron", "0 9 * * *"), "UTC",
                Instant.parse("2026-01-01T09:00:00Z")))
                .isEqualTo(Instant.parse("2026-01-02T09:00:00Z"));
        assertThat(ScheduleCalculator.nextRunAt("cron", Map.of("cron", "30 14 * * 1"), "UTC", NOW))
                .isEqualTo(Instant.parse("2026-01-05T14:30:00Z"));
        assertThat(ScheduleCalculator.nextRunAt("cron", Map.of("cron", "0 0 1 * *"), "UTC",
                Instant.parse("2026-01-15T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
    }

    @Test
    void cronDstSpringForwardGapResolvesForward() {
        // America/New_York 2026-03-08 02:00 -> 03:00; a 02:30 wall clock does not exist.
        assertThat(ScheduleCalculator.nextRunAt("cron", Map.of("cron", "30 2 * * *"),
                "America/New_York", Instant.parse("2026-03-07T12:00:00Z")))
                .isEqualTo(Instant.parse("2026-03-08T07:30:00Z"));
        assertThat(ScheduleCalculator.nextRunAt("cron", Map.of("cron", "0 2 * * *"),
                "America/New_York", Instant.parse("2026-03-07T12:00:00Z")))
                .isEqualTo(Instant.parse("2026-03-08T07:00:00Z"));
    }

    @Test
    void cronDstFallBackFoldUsesFirstOccurrence() {
        // America/New_York 2026-11-01 02:00 -> 01:00; 01:30 happens twice (EDT then EST).
        assertThat(ScheduleCalculator.nextRunAt("cron", Map.of("cron", "30 1 * * *"),
                "America/New_York", Instant.parse("2026-10-31T12:00:00Z")))
                .isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
    }

    @Test
    void cronWallClockShiftsUtcAcrossDst() {
        // Same noon wall clock maps to a different UTC instant before and after spring-forward.
        Instant before = ScheduleCalculator.nextRunAt("cron", Map.of("cron", "0 12 * * *"),
                "America/New_York", Instant.parse("2026-03-07T00:00:00Z"));
        Instant after = ScheduleCalculator.nextRunAt("cron", Map.of("cron", "0 12 * * *"),
                "America/New_York", before);
        assertThat(before).isEqualTo(Instant.parse("2026-03-07T17:00:00Z"));
        assertThat(after).isEqualTo(Instant.parse("2026-03-08T16:00:00Z"));
    }

    @Test
    void cronDayOfMonthAndDayOfWeekUseOrRule() {
        // "20th OR Monday": from Tue 2026-06-16 the 20th (Sat) is nearer than the next Monday.
        assertThat(ScheduleCalculator.nextRunAt("cron", Map.of("cron", "0 0 20 * 1"), "UTC",
                Instant.parse("2026-06-16T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-06-20T00:00:00Z"));
        // From Sun 2026-06-21 the next Monday (22nd) is nearer than the July 20th.
        assertThat(ScheduleCalculator.nextRunAt("cron", Map.of("cron", "0 0 20 * 1"), "UTC",
                Instant.parse("2026-06-21T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-06-22T00:00:00Z"));
    }

    // W19-M1-03 interval: lower bound, upper bound, invalid values.
    @Test
    void intervalLowerAndUpperBounds() {
        assertThat(ScheduleCalculator.validateIntervalSeconds(
                Map.of("every_seconds", 60), 60)).isEqualTo(60);
        assertThat(ScheduleCalculator.validateIntervalSeconds(
                Map.of("every_seconds", ScheduleCalculator.MAX_INTERVAL_SECONDS), 60))
                .isEqualTo(ScheduleCalculator.MAX_INTERVAL_SECONDS);
        assertThatThrownBy(() -> ScheduleCalculator.validateIntervalSeconds(
                Map.of("every_seconds", 59), 60))
                .isInstanceOf(ScheduleValidationException.class)
                .hasMessage("interval schedule must be at least 60 seconds");
        assertThatThrownBy(() -> ScheduleCalculator.validateIntervalSeconds(
                Map.of("every_seconds", ScheduleCalculator.MAX_INTERVAL_SECONDS + 1), 60))
                .isInstanceOf(ScheduleValidationException.class)
                .hasMessage("interval schedule must be at most " + ScheduleCalculator.MAX_INTERVAL_SECONDS
                        + " seconds");
    }

    @Test
    void intervalRejectsNonPositiveAndNonInteger() {
        assertThatThrownBy(() -> ScheduleCalculator.parseIntervalSeconds(Map.of("every_seconds", 0)))
                .isInstanceOf(ScheduleValidationException.class);
        assertThatThrownBy(() -> ScheduleCalculator.parseIntervalSeconds(Map.of("every_seconds", -5)))
                .isInstanceOf(ScheduleValidationException.class);
        assertThatThrownBy(() -> ScheduleCalculator.parseIntervalSeconds(Map.of("every_seconds", 3600.5)))
                .isInstanceOf(ScheduleValidationException.class);
        assertThatThrownBy(() -> ScheduleCalculator.parseIntervalSeconds(Map.of("every_seconds", 3600.0)))
                .isInstanceOf(ScheduleValidationException.class);
        assertThatThrownBy(() -> ScheduleCalculator.parseIntervalSeconds(Map.of("every_seconds", true)))
                .isInstanceOf(ScheduleValidationException.class);
        assertThatThrownBy(() -> ScheduleCalculator.parseIntervalSeconds(Map.of("every_seconds", "600")))
                .isInstanceOf(ScheduleValidationException.class);
        assertThatThrownBy(() -> ScheduleCalculator.parseIntervalSeconds(Map.of()))
                .isInstanceOf(ScheduleValidationException.class);
    }

    @Test
    void intervalNextRunIsNowPlusSeconds() {
        assertThat(ScheduleCalculator.nextRunAt("interval", Map.of("every_seconds", 300), "UTC", NOW))
                .isEqualTo(NOW.plusSeconds(300));
    }

    // W19-M1-04 explicit errors for unknown timezone, wrong cron field count and illegal specs.
    @Test
    void explicitErrors() {
        assertThatThrownBy(() -> ScheduleCalculator.validateTimezone("Mars/Phobos"))
                .isInstanceOf(ScheduleValidationException.class)
                .hasMessage("Unknown timezone: Mars/Phobos");
        assertThatThrownBy(() -> ScheduleCalculator.validateTimezone("+08:00"))
                .isInstanceOf(ScheduleValidationException.class);
        assertThatThrownBy(() -> ScheduleCalculator.normalizeCronExpression("0 9 * *"))
                .isInstanceOf(ScheduleValidationException.class)
                .hasMessage("Cron expression must contain exactly 5 fields");
        assertThatThrownBy(() -> ScheduleCalculator.nextRunAt("cron", Map.of("cron", "0 99 * * *"),
                "UTC", NOW))
                .isInstanceOf(ScheduleValidationException.class);
        assertThatThrownBy(() -> ScheduleCalculator.nextRunAt("weekly", Map.of(), "UTC", NOW))
                .isInstanceOf(ScheduleValidationException.class)
                .hasMessage("Unsupported schedule_type: weekly");
        assertThatThrownBy(() -> ScheduleCalculator.nextRunAt("once", Map.of(), "UTC", NOW))
                .isInstanceOf(ScheduleValidationException.class)
                .hasMessage("once schedule requires run_at");
        assertThatThrownBy(() -> ScheduleCalculator.nextRunAt("cron", Map.of(), "UTC", NOW))
                .isInstanceOf(ScheduleValidationException.class)
                .hasMessage("cron schedule requires schedule_spec.cron");
    }

    @Test
    void cronNamesAndStepsAreSupported() {
        assertThat(ScheduleCalculator.nextRunAt("cron", Map.of("cron", "0 6 * * MON"), "UTC", NOW))
                .isEqualTo(Instant.parse("2026-01-05T06:00:00Z"));
        assertThat(ScheduleCalculator.nextRunAt("cron", Map.of("cron", "*/15 * * * *"), "UTC",
                Instant.parse("2026-01-01T00:07:00Z")))
                .isEqualTo(Instant.parse("2026-01-01T00:15:00Z"));
    }
}

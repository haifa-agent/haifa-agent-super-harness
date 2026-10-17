package io.haifa.agent.superharness.server.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Differential cases against the frozen DeerFlow scheduler reference
 * ({@code e545c28ac301e6f9feae247c513d8ea8f83df294}, {@code croniter} 6.2.2).
 *
 * <p>Every expected instant below was produced by running the frozen
 * {@code deerflow/scheduler/schedules.py} {@code next_run_at} (delegating to {@code croniter}) on
 * the local frozen copy with the same inputs; {@code ERR} rows were verified to raise in the
 * reference. They lock DST gap/overlap, day expressions ({@code L}/{@code nW}/{@code #}),
 * oversized steps and fixed-offset alias rejection to the reference behavior.
 */
class CronDifferentialTest {

    @Test
    void dstOverlapBothSidesMatchFrozenCroniter() {
        // America/New_York 2026-11-01 fall-back: 01:00 wall clock repeats at 05:00Z and 06:00Z.
        assertNext("0 1 * * *", "America/New_York", "2026-10-31T12:00:00Z", "2026-11-01T05:00:00Z");
        assertNext("0 1 * * *", "America/New_York", "2026-11-01T04:30:00Z", "2026-11-01T05:00:00Z");
        assertNext("0 1 * * *", "America/New_York", "2026-11-01T05:00:00Z", "2026-11-01T06:00:00Z");
        assertNext("0 1 * * *", "America/New_York", "2026-11-01T05:30:00Z", "2026-11-01T06:00:00Z");
        assertNext("0 1 * * *", "America/New_York", "2026-11-01T05:59:00Z", "2026-11-01T06:00:00Z");
        assertNext("0 1 * * *", "America/New_York", "2026-11-01T06:00:00Z", "2026-11-02T06:00:00Z");
        assertNext("30 1 * * *", "America/New_York", "2026-10-31T12:00:00Z", "2026-11-01T05:30:00Z");
    }

    @Test
    void dstGapBothSidesMatchFrozenCroniter() {
        // America/New_York 2026-03-08 spring-forward: 02:xx does not exist and nudges to 03:00.
        assertNext("30 2 * * *", "America/New_York", "2026-03-07T12:00:00Z", "2026-03-08T07:00:00Z");
        assertNext("30 2 * * *", "America/New_York", "2026-03-08T06:00:00Z", "2026-03-08T07:00:00Z");
        assertNext("30 2 * * *", "America/New_York", "2026-03-08T06:59:00Z", "2026-03-08T07:00:00Z");
        assertNext("30 2 * * *", "America/New_York", "2026-03-08T07:00:00Z", "2026-03-09T06:30:00Z");
        assertNext("0 2 * * *", "America/New_York", "2026-03-07T12:00:00Z", "2026-03-08T07:00:00Z");
    }

    @Test
    void dayOfMonthExpressionsMatchFrozenCroniter() {
        assertNext("0 9 L * *", "UTC", "2026-01-01T00:00:00Z", "2026-01-31T09:00:00Z");
        assertNext("0 9 L * *", "UTC", "2026-01-31T09:00:00Z", "2026-02-28T09:00:00Z");
        assertNext("0 9 L * *", "UTC", "2024-02-01T00:00:00Z", "2024-02-29T09:00:00Z");
        assertNext("0 9 29-L * *", "UTC", "2026-01-01T00:00:00Z", "2026-01-29T09:00:00Z");
        assertNext("0 0 7,14,21,L * *", "UTC", "2026-01-01T00:00:00Z", "2026-01-07T00:00:00Z");
        assertNext("0 9 15W * *", "UTC", "2026-01-01T00:00:00Z", "2026-01-15T09:00:00Z");
        assertNext("0 9 15W * *", "UTC", "2026-08-01T00:00:00Z", "2026-08-14T09:00:00Z");
        assertNext("0 9 W15 * *", "UTC", "2026-01-01T00:00:00Z", "2026-01-15T09:00:00Z");
        assertNext("0 0 * * sat#1", "UTC", "2026-01-01T00:00:00Z", "2026-01-03T00:00:00Z");
        assertNext("0 0 * * 6#3", "UTC", "2026-01-01T00:00:00Z", "2026-01-17T00:00:00Z");
        assertNext("0 0 * * L6", "UTC", "2026-01-01T00:00:00Z", "2026-01-31T00:00:00Z");
        assertNext("0 0 ? * 1", "UTC", "2026-01-01T00:00:00Z", "2026-01-05T00:00:00Z");
    }

    @Test
    void oversizedStepDoesNotOverflowAndMatchesFrozenCroniter() {
        // The frozen reference returns 00:01Z; a naive "value += step" overflows int.
        assertNext("1/2147483647 * * * *", "UTC", "2026-01-01T00:00:00Z", "2026-01-01T00:01:00Z");
        assertNext("1/2147483647 * * * *", "UTC", "2026-01-01T00:01:00Z", "2026-01-01T01:01:00Z");
    }

    @Test
    void referenceRejectedFormsRemainRejected() {
        assertThatThrownBy(() -> next("0 9 15W,16 * *")).isInstanceOf(ScheduleValidationException.class);
        assertThatThrownBy(() -> next("0 9 L-2 * *")).isInstanceOf(ScheduleValidationException.class);
        assertThatThrownBy(() -> next("0 0 * * 5,6#3")).isInstanceOf(ScheduleValidationException.class);
        assertThatThrownBy(() -> next("0 0 * * 5L")).isInstanceOf(ScheduleValidationException.class);
        assertThatThrownBy(() -> next("0 9 32W * *")).isInstanceOf(ScheduleValidationException.class);
        assertThatThrownBy(() -> next("0 0 * * 1#7")).isInstanceOf(ScheduleValidationException.class);
    }

    private static void assertNext(String expression, String zone, String now, String expected) {
        assertThat(next(expression, zone, now)).isEqualTo(Instant.parse(expected));
    }

    private static Instant next(String expression) {
        return next(expression, "UTC", "2026-01-01T00:00:00Z");
    }

    private static Instant next(String expression, String zone, String now) {
        return ScheduleCalculator.nextRunAt("cron", Map.of("cron", expression), zone, Instant.parse(now));
    }
}

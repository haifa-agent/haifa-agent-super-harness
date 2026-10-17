package io.haifa.agent.superharness.server.schedule;

/**
 * A schedule definition that cannot be accepted as written.
 *
 * <p>Mirrors the frozen DeerFlow {@code ValueError} branch that both the schedule calculation
 * helpers and the REST validation layer raise; the REST layer maps it to 422. It carries only a
 * bounded message and never a credential or model input.
 */
public final class ScheduleValidationException extends RuntimeException {
    public ScheduleValidationException(String message) {
        super(message);
    }
}

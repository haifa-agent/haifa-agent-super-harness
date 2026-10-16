package io.haifa.agent.superharness.server.schedule;

import java.util.Locale;
import java.util.Set;

/** Occurrence status vocabulary, identical to the frozen DeerFlow scheduled-task-run model. */
public enum ScheduledRunStatus {
    QUEUED,
    LAUNCHING,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED,
    INTERRUPTED;

    private static final Set<ScheduledRunStatus> TERMINAL =
            Set.of(SUCCESS, FAILED, SKIPPED, INTERRUPTED);
    private static final Set<ScheduledRunStatus> ACTIVE = Set.of(QUEUED, LAUNCHING, RUNNING);

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean terminal() {
        return TERMINAL.contains(this);
    }

    public boolean active() {
        return ACTIVE.contains(this);
    }

    public static ScheduledRunStatus fromWire(String value) {
        for (ScheduledRunStatus status : values()) {
            if (status.wire().equals(value)) {
                return status;
            }
        }
        throw new ScheduleValidationException("Unsupported run status: " + value);
    }
}

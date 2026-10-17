package io.haifa.agent.superharness.server.schedule;

import java.util.Locale;
import java.util.Set;

/** Task status vocabulary, identical to the frozen DeerFlow scheduled-task model. */
public enum ScheduledTaskStatus {
    ENABLED,
    PAUSED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    private static final Set<ScheduledTaskStatus> TERMINAL = Set.of(COMPLETED, FAILED, CANCELLED);

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean terminal() {
        return TERMINAL.contains(this);
    }

    public static ScheduledTaskStatus fromWire(String value) {
        for (ScheduledTaskStatus status : values()) {
            if (status.wire().equals(value)) {
                return status;
            }
        }
        throw new ScheduleValidationException("Unsupported task status: " + value);
    }
}

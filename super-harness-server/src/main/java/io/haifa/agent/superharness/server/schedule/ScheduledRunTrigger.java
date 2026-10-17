package io.haifa.agent.superharness.server.schedule;

import java.util.Locale;

/** How an occurrence was admitted: by the scheduler or by an explicit user trigger. */
public enum ScheduledRunTrigger {
    SCHEDULED,
    MANUAL;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ScheduledRunTrigger fromWire(String value) {
        for (ScheduledRunTrigger trigger : values()) {
            if (trigger.wire().equals(value)) {
                return trigger;
            }
        }
        throw new ScheduleValidationException("Unsupported run trigger: " + value);
    }
}

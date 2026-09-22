package io.haifa.agent.superharness.events;

import io.haifa.agent.core.run.AgentRunStatus;

public enum RunTerminalKind {
    ACTIVE,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMEOUT;

    static RunTerminalKind from(AgentRunStatus status) {
        return switch (status) {
            case COMPLETED -> COMPLETED;
            case FAILED -> FAILED;
            case CANCELLED -> CANCELLED;
            case TIMEOUT -> TIMEOUT;
            default -> ACTIVE;
        };
    }

    public boolean terminal() {
        return this != ACTIVE;
    }
}

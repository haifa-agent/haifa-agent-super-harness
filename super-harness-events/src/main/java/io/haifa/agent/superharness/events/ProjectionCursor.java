package io.haifa.agent.superharness.events;

public record ProjectionCursor(String runId, long sequence) {
    public ProjectionCursor {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
    }
}

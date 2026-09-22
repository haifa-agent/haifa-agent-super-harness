package io.haifa.agent.superharness.events;

public record ProjectionGap(Reason reason, long requestedSequence, long earliestAvailableSequence, long latestSequence) {
    public enum Reason {
        SOURCE_SEQUENCE_GAP,
        RETENTION_WINDOW_EXCEEDED
    }
}

package io.haifa.agent.superharness.events;

public record ProjectionGap(Reason reason, long requestedSequence, long earliestAvailableSequence, long latestSequence) {
    public enum Reason {
        RETENTION_WINDOW_EXCEEDED,
        // Reserved for an explicit upstream gap signal. Sparse public SDK sequences are valid
        // because internal durable events are intentionally filtered from AgentRunEvent.
        SOURCE_SEQUENCE_GAP
    }
}

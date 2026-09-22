package io.haifa.agent.superharness.events;

import io.haifa.agent.runtime.api.AgentRunEvent;

public record StableEventKey(String runId, String eventId) {
    public StableEventKey {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
    }

    public static StableEventKey from(AgentRunEvent event) {
        return new StableEventKey(event.runId().value(), event.eventId());
    }
}

package io.haifa.agent.superharness.workspace;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owner-scoped Project metadata shared by the project shelf and the frozen HTTP adapter. */
public record ProjectProfile(
        String id,
        String name,
        String instructions,
        Map<String, String> presentation,
        String status,
        Instant createdAt,
        Instant updatedAt) {
    public ProjectProfile {
        presentation = Collections.unmodifiableMap(new LinkedHashMap<>(presentation));
    }
}

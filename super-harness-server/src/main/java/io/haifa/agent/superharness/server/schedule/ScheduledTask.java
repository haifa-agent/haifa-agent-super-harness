package io.haifa.agent.superharness.server.schedule;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Product-layer scheduled-task definition; it references a Run id but never owns Run truth.
 *
 * <p>Deliberately narrower than the frozen DeerFlow row: the multi-instance lease/fence columns and
 * the unused {@code overlap_policy} column are not carried (single-instance scope). {@code
 * lastOccurrenceSeq} is internal sequencing state and is not projected to the frozen UI.
 */
public record ScheduledTask(
        String id,
        String userId,
        String threadId,
        String contextMode,
        String assistantId,
        String title,
        String prompt,
        String scheduleType,
        Map<String, Object> scheduleSpec,
        String timezone,
        ScheduledTaskStatus status,
        Instant nextRunAt,
        Instant lastRunAt,
        String lastRunId,
        String lastThreadId,
        String lastError,
        int runCount,
        long lastOccurrenceSeq,
        Instant createdAt,
        Instant updatedAt) {

    public static final String FRESH_THREAD_PER_RUN = "fresh_thread_per_run";
    public static final String REUSE_THREAD = "reuse_thread";

    public ScheduledTask {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(scheduleType, "scheduleType");
        Objects.requireNonNull(timezone, "timezone");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (!FRESH_THREAD_PER_RUN.equals(contextMode) && !REUSE_THREAD.equals(contextMode)) {
            throw new ScheduleValidationException("Unsupported context_mode: " + contextMode);
        }
        if (runCount < 0 || lastOccurrenceSeq < 0) {
            throw new ScheduleValidationException("run_count and last_occurrence_seq must not be negative");
        }
        scheduleSpec = scheduleSpec == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(scheduleSpec));
    }

    /** Frozen frontend task shape ({@code core/scheduled-tasks/types.ts}). */
    public Map<String, Object> wire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", id);
        wire.put("thread_id", threadId);
        wire.put("context_mode", contextMode);
        wire.put("assistant_id", assistantId);
        wire.put("title", title);
        wire.put("prompt", prompt);
        wire.put("schedule_type", scheduleType);
        wire.put("schedule_spec", scheduleSpec);
        wire.put("timezone", timezone);
        wire.put("status", status.wire());
        wire.put("next_run_at", iso(nextRunAt));
        wire.put("last_run_at", iso(lastRunAt));
        wire.put("last_run_id", lastRunId);
        wire.put("last_thread_id", lastThreadId);
        wire.put("last_error", lastError);
        wire.put("run_count", runCount);
        wire.put("created_at", createdAt.toString());
        wire.put("updated_at", updatedAt.toString());
        return wire;
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}

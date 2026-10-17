package io.haifa.agent.superharness.server.schedule;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One scheduled-task occurrence (frozen DeerFlow {@code scheduled_task_runs} row).
 *
 * <p>{@code occurrenceSeq} and {@code launchAccounted} are internal sequencing/accounting state and
 * are excluded from the frozen UI projection, matching the frozen repository's field exclusions.
 */
public record ScheduledTaskRun(
        String id,
        String taskId,
        Long occurrenceSeq,
        String threadId,
        String runId,
        Instant scheduledFor,
        ScheduledRunTrigger trigger,
        ScheduledRunStatus status,
        String error,
        Instant startedAt,
        Instant finishedAt,
        int attemptCount,
        Boolean launchAccounted,
        Instant createdAt) {

    public ScheduledTaskRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(scheduledFor, "scheduledFor");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Frozen frontend occurrence shape ({@code core/scheduled-tasks/types.ts}). */
    public Map<String, Object> wire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", id);
        wire.put("task_id", taskId);
        wire.put("thread_id", threadId);
        wire.put("run_id", runId);
        wire.put("scheduled_for", scheduledFor.toString());
        wire.put("trigger", trigger.wire());
        wire.put("status", status.wire());
        wire.put("error", error);
        wire.put("attempt_count", attemptCount);
        wire.put("started_at", startedAt == null ? null : startedAt.toString());
        wire.put("finished_at", finishedAt == null ? null : finishedAt.toString());
        wire.put("created_at", createdAt.toString());
        return wire;
    }
}

package io.haifa.agent.superharness.server.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Consumption-level self-check that the store's wire projection matches the frozen frontend
 * shape ({@code frontend/src/core/scheduled-tasks/types.ts}). This is supplementary evidence;
 * the W00 {@code schedules} schema/fixture gate is still required to close W19-M1-08.
 */
class ScheduleWireShapeTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void taskWireMatchesFrozenFrontendFields() {
        ScheduledTask task = new ScheduledTask("task-1", "owner-1", null,
                ScheduledTask.FRESH_THREAD_PER_RUN, "lead_agent", "title", "prompt", "interval",
                Map.of("every_seconds", 600), "UTC", ScheduledTaskStatus.ENABLED, NOW.plusSeconds(1),
                null, null, null, null, 0, 0, NOW, NOW);
        assertThat(task.wire().keySet()).isEqualTo(new LinkedHashSet<>(Set.of(
                "id", "thread_id", "context_mode", "assistant_id", "title", "prompt", "schedule_type",
                "schedule_spec", "timezone", "status", "next_run_at", "last_run_at", "last_run_id",
                "last_thread_id", "last_error", "run_count", "created_at", "updated_at")));
        assertThat(task.wire()).doesNotContainKeys("user_id", "last_occurrence_seq");
        assertThat(task.wire().get("status")).isEqualTo("enabled");
        assertThat(task.wire().get("next_run_at")).isEqualTo(NOW.plusSeconds(1).toString());
    }

    @Test
    void runWireMatchesFrozenFrontendFields() {
        ScheduledTaskRun run = new ScheduledTaskRun("occ-1", "task-1", 3L, "thread-1", "run-1", NOW,
                ScheduledRunTrigger.MANUAL, ScheduledRunStatus.QUEUED, null, null, null, 2,
                Boolean.FALSE, NOW);
        assertThat(run.wire().keySet()).isEqualTo(new LinkedHashSet<>(Set.of(
                "id", "task_id", "thread_id", "run_id", "scheduled_for", "trigger", "status", "error",
                "attempt_count", "started_at", "finished_at", "created_at")));
        assertThat(run.wire()).doesNotContainKeys("occurrence_seq", "launch_accounted");
        assertThat(run.wire().get("trigger")).isEqualTo("manual");
        assertThat(run.wire().get("status")).isEqualTo("queued");
    }
}

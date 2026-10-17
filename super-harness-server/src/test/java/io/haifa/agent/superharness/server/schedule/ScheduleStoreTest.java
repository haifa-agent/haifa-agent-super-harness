package io.haifa.agent.superharness.server.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScheduleStoreTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String OWNER = "owner-1";
    private static final String OTHER = "owner-2";

    // W19-M1-05 create / read / update persist and survive a store restart.
    @Test
    void taskPersistsAcrossStoreRestart(@TempDir Path dataDirectory) throws Exception {
        ScheduleStore store = new ScheduleStore(JSON, dataDirectory);
        ScheduledTask created = store.createTask(newTask("Fresh interval"), NOW);
        assertThat(created.status()).isEqualTo(ScheduledTaskStatus.ENABLED);
        assertThat(created.lastOccurrenceSeq()).isZero();

        store.updateTask(created.id(), OWNER, Map.of("title", "Renamed"), NOW.plusSeconds(5));

        ScheduleStore reopened = new ScheduleStore(JSON, dataDirectory);
        ScheduledTask reloaded = reopened.findTask(created.id(), OWNER).orElseThrow();
        assertThat(reloaded.title()).isEqualTo("Renamed");
        assertThat(reloaded.scheduleSpec()).isEqualTo(Map.of("every_seconds", 600));
        assertThat(reopened.listTasks(OWNER)).hasSize(1);

        // A mutation written through the reopened instance is visible to a third instance.
        reopened.updateTask(created.id(), OWNER, Map.of("prompt", "updated prompt"), NOW.plusSeconds(9));
        assertThat(new ScheduleStore(JSON, dataDirectory)
                .findTask(created.id(), OWNER).orElseThrow().prompt()).isEqualTo("updated prompt");
    }

    // W19-M1-06 single active occurrence: concurrent admission yields exactly one active row.
    @Test
    void singleActiveOccurrenceUnderConcurrency(@TempDir Path dataDirectory) throws Exception {
        ScheduleStore store = new ScheduleStore(JSON, dataDirectory);
        ScheduledTask task = store.createTask(newTask("Once"), NOW);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Callable<String> attempt = () -> {
                barrier.await();
                try {
                    store.createRun(newRun(task.id(), OWNER), NOW);
                    return "ok";
                } catch (ScheduleStore.ActiveOccurrenceConflict conflict) {
                    return "conflict";
                }
            };
            List<Future<String>> results = executor.invokeAll(List.of(attempt, attempt));
            long successes = results.stream().filter(this::isOk).count();
            assertThat(successes).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(store.listRuns(task.id(), OWNER, 50, 0, null)).hasSize(1);
        assertThat(store.findActiveRun(task.id())).isPresent();
        assertThatThrownBy(() -> store.createRun(newRun(task.id(), OWNER), NOW))
                .isInstanceOf(ScheduleStore.ActiveOccurrenceConflict.class);
    }

    // W19-M1-07 owner isolation: another identity cannot read, mutate or delete.
    @Test
    void ownerIsolation(@TempDir Path dataDirectory) throws Exception {
        ScheduleStore store = new ScheduleStore(JSON, dataDirectory);
        ScheduledTask task = store.createTask(newTask("Private"), NOW);
        store.createRun(newRun(task.id(), OWNER), NOW);

        assertThat(store.findTask(task.id(), OTHER)).isEmpty();
        assertThat(store.listTasks(OTHER)).isEmpty();
        assertThat(store.listTasksByThread(OTHER, null)).isEmpty();
        assertThat(store.updateTask(task.id(), OTHER, Map.of("title", "hijack"), NOW)).isEmpty();
        assertThat(store.deleteTask(task.id(), OTHER)).isFalse();
        assertThat(store.listRuns(task.id(), OTHER, 50, 0, null)).isEmpty();
        assertThatThrownBy(() -> store.createRun(
                new ScheduleStore.NewRun(null, task.id(), OTHER, "thread-1", NOW,
                        ScheduledRunTrigger.MANUAL, ScheduledRunStatus.QUEUED), NOW))
                .isInstanceOf(ScheduleValidationException.class);

        // The real owner is unaffected.
        assertThat(store.findTask(task.id(), OWNER)).isPresent();
        assertThat(store.listRuns(task.id(), OWNER, 50, 0, null)).hasSize(1);
    }

    // W19-M1-09 atomic claim: only one racing caller moves queued -> launching.
    @Test
    void claimQueuedRunSucceedsOnce(@TempDir Path dataDirectory) throws Exception {
        ScheduleStore store = new ScheduleStore(JSON, dataDirectory);
        ScheduledTask task = store.createTask(newTask("Claim"), NOW);
        ScheduledTaskRun run = store.createRun(newRun(task.id(), OWNER), NOW);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        long claimed;
        try {
            CyclicBarrier barrier = new CyclicBarrier(8);
            Callable<Boolean> attempt = () -> {
                barrier.await();
                return store.claimQueuedRun(run.id(), NOW);
            };
            claimed = executor.invokeAll(List.of(attempt, attempt, attempt, attempt, attempt, attempt,
                    attempt, attempt)).stream().filter(this::isTrue).count();
        } finally {
            executor.shutdownNow();
        }

        assertThat(claimed).isEqualTo(1);
        ScheduledTaskRun claimedRun = store.findRun(run.id()).orElseThrow();
        assertThat(claimedRun.status()).isEqualTo(ScheduledRunStatus.LAUNCHING);
        assertThat(claimedRun.attemptCount()).isEqualTo(1);
        assertThat(store.claimQueuedRun(run.id(), NOW)).isFalse();
    }

    // W19-M1-09 terminal protection: a late launch write never overwrites a terminal row.
    @Test
    void protectTerminalDoesNotOverwriteTerminalStatus(@TempDir Path dataDirectory) throws Exception {
        ScheduleStore store = new ScheduleStore(JSON, dataDirectory);
        ScheduledTask task = store.createTask(newTask("Terminal"), NOW);
        ScheduledTaskRun run = store.createRun(newRun(task.id(), OWNER), NOW);

        assertThat(store.updateRunStatus(run.id(), ScheduledRunStatus.SUCCESS, "run-1", null,
                NOW, NOW.plusSeconds(3), false)).isTrue();
        // A late "running" write with protect_terminal keeps success and does not clear bookkeeping.
        assertThat(store.updateRunStatus(run.id(), ScheduledRunStatus.RUNNING, "run-1", "late error",
                NOW.plusSeconds(1), null, true)).isTrue();
        ScheduledTaskRun protectedRun = store.findRun(run.id()).orElseThrow();
        assertThat(protectedRun.status()).isEqualTo(ScheduledRunStatus.SUCCESS);
        assertThat(protectedRun.error()).isNull();
        assertThat(protectedRun.finishedAt()).isEqualTo(NOW.plusSeconds(3));

        // Terminal row with a missing run_id only backfills the association.
        ScheduledTaskRun second = store.createRun(newRun(task.id(), OWNER), NOW.plusSeconds(10));
        store.updateRunStatus(second.id(), ScheduledRunStatus.INTERRUPTED, null, "boom",
                null, NOW.plusSeconds(12), false);
        assertThat(store.updateRunStatus(second.id(), ScheduledRunStatus.RUNNING, "run-2", "late",
                NOW.plusSeconds(11), null, true)).isTrue();
        ScheduledTaskRun backfilled = store.findRun(second.id()).orElseThrow();
        assertThat(backfilled.status()).isEqualTo(ScheduledRunStatus.INTERRUPTED);
        assertThat(backfilled.runId()).isEqualTo("run-2");
        assertThat(backfilled.startedAt()).isEqualTo(NOW.plusSeconds(11));
        assertThat(backfilled.error()).isEqualTo("boom");
    }

    // W19-M1-09 post-launch bookkeeping failure keeps the active slot and blocks re-claim.
    @Test
    void associationWriteFailureKeepsActiveSlotAndRunCountOnce(@TempDir Path dataDirectory)
            throws Exception {
        FailingStore store = new FailingStore(JSON, dataDirectory);
        ScheduledTask task = store.createTask(newTask("Fault"), NOW);
        ScheduledTaskRun run = store.createRun(newRun(task.id(), OWNER), NOW);
        assertThat(store.claimQueuedRun(run.id(), NOW)).isTrue();

        store.failNext = true;
        assertThatThrownBy(() -> store.associateRun(run.id(), "thread-9", "run-9", NOW))
                .isInstanceOf(IllegalStateException.class);

        ScheduledTaskRun afterFailure = store.findRun(run.id()).orElseThrow();
        assertThat(afterFailure.status()).isEqualTo(ScheduledRunStatus.LAUNCHING);
        assertThat(afterFailure.runId()).isNull();
        assertThat(store.claimQueuedRun(run.id(), NOW)).isFalse();
        assertThat(store.findActiveRun(task.id())).isPresent();
        assertThatThrownBy(() -> store.createRun(newRun(task.id(), OWNER), NOW))
                .isInstanceOf(ScheduleStore.ActiveOccurrenceConflict.class);

        // run_count increments once per distinct launch, even if bookkeeping is retried.
        ScheduleStore.TaskLaunchUpdate launch = new ScheduleStore.TaskLaunchUpdate("running",
                NOW.plusSeconds(600), NOW, "run-9", "thread-9", null, true, false, run.id());
        assertThat(store.updateTaskAfterLaunch(task.id(), launch, NOW)).isTrue();
        assertThat(store.findTaskInternal(task.id()).orElseThrow().runCount()).isEqualTo(1);
        assertThat(store.updateTaskAfterLaunch(task.id(), launch, NOW.plusSeconds(1))).isTrue();
        assertThat(store.findTaskInternal(task.id()).orElseThrow().runCount()).isEqualTo(1);
        ScheduleStore.TaskLaunchUpdate later = new ScheduleStore.TaskLaunchUpdate("running",
                NOW.plusSeconds(1200), NOW.plusSeconds(900), "run-10", "thread-9", null, true, false, null);
        assertThat(store.updateTaskAfterLaunch(task.id(), later, NOW.plusSeconds(900))).isTrue();
        assertThat(store.findTaskInternal(task.id()).orElseThrow().runCount()).isEqualTo(2);
    }

    @Test
    void taskTerminalStatusIsProtectedFromLateLaunchWrite(@TempDir Path dataDirectory) throws Exception {
        ScheduleStore store = new ScheduleStore(JSON, dataDirectory);
        ScheduledTask task = store.createTask(newTask("Once terminal"), NOW);
        store.updateTask(task.id(), OWNER, Map.of("status", "completed"), NOW.plusSeconds(1));

        store.updateTaskAfterLaunch(task.id(), new ScheduleStore.TaskLaunchUpdate("running", null,
                null, "run-x", "thread-x", "late", false, true, null), NOW.plusSeconds(2));
        ScheduledTask updated = store.findTaskInternal(task.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(ScheduledTaskStatus.COMPLETED);
        assertThat(updated.lastError()).isNull();
    }

    // W19-M1-09 launch accounting must roll back with the task row (Codex P1: runs.put before mutate).
    @Test
    void launchAccountingRollsBackWithFailedPersistence(@TempDir Path dataDirectory) throws Exception {
        FailingStore store = new FailingStore(JSON, dataDirectory);
        ScheduledTask task = store.createTask(newTask("Atomic accounting"), NOW);
        ScheduledTaskRun run = store.createRun(newRun(task.id(), OWNER), NOW);
        assertThat(store.claimQueuedRun(run.id(), NOW)).isTrue();

        ScheduleStore.TaskLaunchUpdate launch = new ScheduleStore.TaskLaunchUpdate("running",
                NOW.plusSeconds(600), NOW, null, "thread-9", null, true, false, run.id());
        store.failNext = true;
        assertThatThrownBy(() -> store.updateTaskAfterLaunch(task.id(), launch, NOW))
                .isInstanceOf(IllegalStateException.class);

        // The occurrence accounting marker rolled back with the failed task write.
        assertThat(store.findRun(run.id()).orElseThrow().launchAccounted()).isNotEqualTo(Boolean.TRUE);
        assertThat(store.findTaskInternal(task.id()).orElseThrow().runCount()).isZero();

        // A retry now accounts exactly once, and a further retry is a no-op.
        assertThat(store.updateTaskAfterLaunch(task.id(), launch, NOW)).isTrue();
        assertThat(store.findTaskInternal(task.id()).orElseThrow().runCount()).isEqualTo(1);
        assertThat(store.findRun(run.id()).orElseThrow().launchAccounted()).isEqualTo(Boolean.TRUE);
        assertThat(store.updateTaskAfterLaunch(task.id(), launch, NOW.plusSeconds(1))).isTrue();
        assertThat(store.findTaskInternal(task.id()).orElseThrow().runCount()).isEqualTo(1);
    }

    // W19-M1-09 recoverable accounting with no stable run id still increments at most once (Codex P2).
    @Test
    void occurrenceAccountingDoesNotDoubleIncrementWithoutStableRunId(@TempDir Path dataDirectory)
            throws Exception {
        ScheduleStore store = new ScheduleStore(JSON, dataDirectory);
        ScheduledTask task = store.createTask(newTask("Nil run id"), NOW);
        ScheduledTaskRun run = store.createRun(newRun(task.id(), OWNER), NOW);

        ScheduleStore.TaskLaunchUpdate launch = new ScheduleStore.TaskLaunchUpdate("running",
                NOW.plusSeconds(600), NOW, null, "thread-9", null, true, false, run.id());
        assertThat(store.updateTaskAfterLaunch(task.id(), launch, NOW)).isTrue();
        assertThat(store.updateTaskAfterLaunch(task.id(), launch, NOW.plusSeconds(1))).isTrue();
        assertThat(store.updateTaskAfterLaunch(task.id(), launch, NOW.plusSeconds(2))).isTrue();
        assertThat(store.findTaskInternal(task.id()).orElseThrow().runCount()).isEqualTo(1);
    }

    // W19-M1-05/06 bounded history: completed occurrences are trimmed before the document hits its
    // size bound, while the active occurrence is always retained (Codex P1: unbounded growth).
    @Test
    void occurrenceHistoryIsBoundedPerTask(@TempDir Path dataDirectory) throws Exception {
        ScheduleStore store = new ScheduleStore(JSON, dataDirectory);
        ScheduledTask task = store.createTask(newTask("Bounded"), NOW);
        for (int index = 0; index < 260; index++) {
            ScheduledTaskRun run = store.createRun(newRun(task.id(), OWNER), NOW.plusSeconds(index));
            store.updateRunStatus(run.id(), ScheduledRunStatus.SUCCESS, "run-" + index, null,
                    NOW.plusSeconds(index + 1), NOW.plusSeconds(index + 1), false);
        }
        assertThat(store.listRuns(task.id(), OWNER, 200, 0, null)).hasSize(200);
        assertThat(store.listRuns(task.id(), OWNER, 200, 200, null)).isEmpty();
        assertThat(Files.size(dataDirectory.resolve("scheduler").resolve("state.json")))
                .isLessThan(16L * 1024 * 1024);

        // The trimmed history survives a restart.
        assertThat(new ScheduleStore(JSON, dataDirectory).listRuns(task.id(), OWNER, 200, 200, null))
                .isEmpty();
    }

    // W19-M1-07 deleting a task also clears its occurrence history (Codex P1 cleanup).
    @Test
    void deleteTaskRemovesItsOccurrences(@TempDir Path dataDirectory) throws Exception {
        ScheduleStore store = new ScheduleStore(JSON, dataDirectory);
        ScheduledTask task = store.createTask(newTask("Delete"), NOW);
        ScheduledTaskRun run = store.createRun(newRun(task.id(), OWNER), NOW);

        assertThat(store.deleteTask(task.id(), OWNER)).isTrue();
        assertThat(store.findRun(run.id())).isEmpty();
        assertThat(new ScheduleStore(JSON, dataDirectory).findRun(run.id())).isEmpty();
    }

    @Test
    void runHistoryIsPagedAndFilteredByStatus(@TempDir Path dataDirectory) throws Exception {        ScheduleStore store = new ScheduleStore(JSON, dataDirectory);
        ScheduledTask task = store.createTask(newTask("History"), NOW);
        for (int index = 0; index < 3; index++) {
            ScheduledTaskRun run = store.createRun(newRun(task.id(), OWNER), NOW.plusSeconds(index));
            store.updateRunStatus(run.id(), index == 0 ? ScheduledRunStatus.SUCCESS
                    : ScheduledRunStatus.FAILED, "run-" + index, "e" + index, null,
                    NOW.plusSeconds(index + 1), false);
        }
        assertThat(store.listRuns(task.id(), OWNER, 50, 0, null)).hasSize(3);
        assertThat(store.listRuns(task.id(), OWNER, 50, 0, ScheduledRunStatus.SUCCESS)).hasSize(1);
        assertThat(store.listRuns(task.id(), OWNER, 2, 0, null)).hasSize(2);
        assertThat(store.listRuns(task.id(), OWNER, 2, 2, null)).hasSize(1);
    }

    private boolean isOk(Future<String> future) {
        try {
            return "ok".equals(future.get());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private boolean isTrue(Future<Boolean> future) {
        try {
            return Boolean.TRUE.equals(future.get());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static ScheduleStore.NewTask newTask(String title) {
        return new ScheduleStore.NewTask(null, OWNER, null, ScheduledTask.FRESH_THREAD_PER_RUN,
                "lead_agent", title, "prompt", "interval", Map.of("every_seconds", 600), "UTC",
                NOW.plusSeconds(600));
    }

    private static ScheduleStore.NewRun newRun(String taskId, String owner) {
        return new ScheduleStore.NewRun(null, taskId, owner, "thread-" + taskId, NOW,
                ScheduledRunTrigger.SCHEDULED, ScheduledRunStatus.QUEUED);
    }

    static final class FailingStore extends ScheduleStore {
        boolean failNext;

        FailingStore(ObjectMapper json, Path dataDirectory) throws IOException {
            super(json, dataDirectory);
        }

        @Override
        protected void persist() throws IOException {
            if (failNext) {
                failNext = false;
                throw new IOException("injected persistence failure");
            }
            super.persist();
        }
    }
}

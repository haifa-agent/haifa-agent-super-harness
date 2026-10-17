package io.haifa.agent.superharness.server.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.superharness.server.config.SchedulerRunConfiguration;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deterministic W19-M2 dispatch coverage: occurrence/Run association, terminal projection, capacity
 * queueing, single-active idempotency, graded recovery, queue timeout and post-launch bookkeeping
 * faults. A fake {@link ScheduledRunLauncher} keeps the worker free of the SDK so the accounting
 * boundaries are observed directly; the real HTTP/SDK chain is covered separately.
 */
class ScheduleDispatchServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String OWNER = "default";

    // W19-M2-01/02: a due task launches one Run, associates the occurrence, and projects the
    // schedule/business fields from the real Run result.
    @Test
    void dueTaskLaunchesRunAndAssociatesOccurrence(@TempDir Path dataDirectory) throws Exception {
        ScheduleStore store = new ScheduleStore(JSON, dataDirectory);
        ScheduledTask task = store.createTask(intervalTask("Due", NOW), NOW);
        FakeLauncher launcher = new FakeLauncher();
        ScheduleDispatchService service = service(store, launcher, configuration(3), new AtomicReference<>(NOW));

        service.pollOnce();

        assertThat(launcher.requests).hasSize(1);
        assertThat(launcher.requests.getFirst().message()).isEqualTo("run this prompt");
        assertThat(launcher.requests.getFirst().createThreadIfMissing()).isTrue();
        ScheduledTaskRun occurrence = store.findActiveRun(task.id()).orElseThrow();
        assertThat(occurrence.status()).isEqualTo(ScheduledRunStatus.RUNNING);
        assertThat(occurrence.runId()).isEqualTo("run-1");
        assertThat(occurrence.trigger()).isEqualTo(ScheduledRunTrigger.SCHEDULED);
        assertThat(occurrence.attemptCount()).isEqualTo(1);
        assertThat(store.findTaskInternal(task.id()).orElseThrow().lastRunId()).isEqualTo("run-1");

        launcher.complete("run-1", ScheduledRunStatus.SUCCESS);

        ScheduledTaskRun finished = store.findRun(occurrence.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(ScheduledRunStatus.SUCCESS);
        assertThat(finished.finishedAt()).isEqualTo(NOW);
        ScheduledTask updated = store.findTaskInternal(task.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(ScheduledTaskStatus.ENABLED);
        assertThat(updated.nextRunAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(updated.runCount()).isEqualTo(1);
        assertThat(updated.lastRunId()).isEqualTo("run-1");
        assertThat(updated.lastThreadId()).isEqualTo(occurrence.threadId());
    }

    // W19-M2-03: success/failed/interrupted and the once projection are read from the Run result.
    @Test
    void terminalProjectionAndOnceTaskStatus(@TempDir Path dataDirectory) throws Exception {
        ScheduleStore store = new ScheduleStore(JSON, dataDirectory);
        FakeLauncher launcher = new FakeLauncher();
        ScheduleDispatchService service = service(store, launcher, configuration(3), new AtomicReference<>(NOW));

        ScheduledTask recurring = store.createTask(intervalTask("Recurring", NOW), NOW);
        ScheduledTask once = store.createTask(onceTask("Once", NOW), NOW);
        service.pollOnce();
        ScheduledTaskRun recurringRun = store.findActiveRun(recurring.id()).orElseThrow();
        ScheduledTaskRun onceRun = store.findActiveRun(once.id()).orElseThrow();

        launcher.complete(recurringRun.runId(), ScheduledRunStatus.FAILED);
        launcher.complete(onceRun.runId(), ScheduledRunStatus.SUCCESS);

        assertThat(store.findTaskInternal(recurring.id()).orElseThrow().status())
                .isEqualTo(ScheduledTaskStatus.ENABLED);
        assertThat(store.findTaskInternal(recurring.id()).orElseThrow().lastError()).isEqualTo("run failed");
        assertThat(store.findTaskInternal(once.id()).orElseThrow().status())
                .isEqualTo(ScheduledTaskStatus.COMPLETED);

        ScheduledTask onceInterrupted = store.createTask(onceTask("Once interrupted", NOW), NOW);
        service.pollOnce();
        ScheduledTaskRun interruptedRun = store.findActiveRun(onceInterrupted.id()).orElseThrow();
        launcher.complete(interruptedRun.runId(), ScheduledRunStatus.INTERRUPTED);
        assertThat(store.findTaskInternal(onceInterrupted.id()).orElseThrow().status())
                .isEqualTo(ScheduledTaskStatus.CANCELLED);

        ScheduledTask onceFailed = store.createTask(onceTask("Once failed", NOW), NOW);
        service.pollOnce();
        // A single active occurrence is never stacked into a second row.
        assertThat(store.listRuns(onceFailed.id(), OWNER, 10, 0, null)).hasSize(1);
    }

    // W19-M2-04: a due task with no capacity stays queued (never skipped) and dispatches on release.
    @Test
    void capacityFullQueuesAndDispatchesWhenReleased(@TempDir Path dataDirectory) throws Exception {
        ScheduleStore store = new ScheduleStore(JSON, dataDirectory);
        FakeLauncher launcher = new FakeLauncher();
        ScheduleDispatchService service = service(store, launcher, configuration(1), new AtomicReference<>(NOW));

        ScheduledTask first = store.createTask(intervalTask("First", NOW, "task-a"), NOW);
        ScheduledTask second = store.createTask(intervalTask("Second", NOW, "task-b"), NOW);
        service.pollOnce();

        // Exactly one admission owns the single slot; the other waits as a durable queued item.
        long running = List.of(first, second).stream()
                .filter(task -> store.findActiveRun(task.id()).orElseThrow().status()
                        == ScheduledRunStatus.RUNNING)
                .count();
        assertThat(running).isEqualTo(1);
        long queued = List.of(first, second).stream()
                .filter(task -> store.findActiveRun(task.id()).orElseThrow().status()
                        == ScheduledRunStatus.QUEUED)
                .count();
        assertThat(queued).isEqualTo(1);
        assertThat(launcher.requests).hasSize(1);
        ScheduledTask waiting = List.of(first, second).stream()
                .filter(task -> store.findActiveRun(task.id()).orElseThrow().status()
                        == ScheduledRunStatus.QUEUED)
                .findFirst()
                .orElseThrow();

        launcher.complete("run-1", ScheduledRunStatus.SUCCESS);
        service.pollOnce();

        // The released slot dispatches the persisted waiting item; it is never skipped or dropped.
        assertThat(store.findActiveRun(waiting.id()).orElseThrow().status())
                .isEqualTo(ScheduledRunStatus.RUNNING);
        assertThat(launcher.requests).hasSize(2);
    }

    // W19-M2-05: repeated due polls on an active task never start a second Run.
    @Test
    void singleTaskSingleActiveAcrossRepeatedPolls(@TempDir Path dataDirectory) throws Exception {
        ScheduleStore store = new ScheduleStore(JSON, dataDirectory);
        FakeLauncher launcher = new FakeLauncher();
        ScheduleDispatchService service = service(store, launcher, configuration(3), new AtomicReference<>(NOW));

        ScheduledTask task = store.createTask(intervalTask("Once active", NOW), NOW);
        service.pollOnce();
        service.pollOnce();
        service.pollOnce();

        assertThat(launcher.requests).hasSize(1);
        assertThat(store.listRuns(task.id(), OWNER, 10, 0, null)).hasSize(1);
    }

    // W19-M2-07: graded recovery — queued preserved, launching-without-run returns to queued,
    // committed execution states converge from the Run result, an active Run keeps its slot.
    @Test
    void startupRecoveryGradesActiveOccurrences(@TempDir Path dataDirectory) throws Exception {
        ScheduleStore store = new ScheduleStore(JSON, dataDirectory);
        FakeLauncher launcher = new FakeLauncher();
        launcher.inspections.put("run-success", new ScheduledRunLauncher.ScheduledRunInspection(
                true, ScheduledRunStatus.SUCCESS, null));
        launcher.inspections.put("run-failed", new ScheduledRunLauncher.ScheduledRunInspection(
                true, ScheduledRunStatus.FAILED, "run failed"));
        launcher.inspections.put("run-active", new ScheduledRunLauncher.ScheduledRunInspection(
                false, null, null));

        ScheduledTask successTask = store.createTask(intervalTask("Success", NOW.plusSeconds(30)), NOW);
        ScheduledTask failedTask = store.createTask(onceTask("Failed", NOW.plusSeconds(30)), NOW);
        ScheduledTask launchingTask = store.createTask(intervalTask("Launching", NOW.plusSeconds(30)), NOW);
        ScheduledTask queuedTask = store.createTask(intervalTask("Queued", NOW.plusSeconds(30)), NOW);
        ScheduledTask interruptedTask = store.createTask(intervalTask("Interrupted", NOW.plusSeconds(30)), NOW);
        ScheduledTask activeTask = store.createTask(intervalTask("Active", NOW.plusSeconds(30)), NOW);

        ScheduledTaskRun success = store.createRun(activeRun(successTask, ScheduledRunStatus.RUNNING), NOW);
        store.associateRun(success.id(), success.threadId(), "run-success", NOW);
        ScheduledTaskRun failed = store.createRun(activeRun(failedTask, ScheduledRunStatus.RUNNING), NOW);
        store.associateRun(failed.id(), failed.threadId(), "run-failed", NOW);
        ScheduledTaskRun launching = store.createRun(activeRun(launchingTask, ScheduledRunStatus.LAUNCHING), NOW);
        ScheduledTaskRun queued = store.createRun(newRun(queuedTask, ScheduledRunStatus.QUEUED), NOW);
        ScheduledTaskRun interrupted = store.createRun(activeRun(interruptedTask, ScheduledRunStatus.RUNNING), NOW);
        store.associateRun(interrupted.id(), interrupted.threadId(), "run-unknown", NOW);
        ScheduledTaskRun active = store.createRun(activeRun(activeTask, ScheduledRunStatus.RUNNING), NOW);
        store.associateRun(active.id(), active.threadId(), "run-active", NOW);

        ScheduleDispatchService service = service(store, launcher, configuration(3), new AtomicReference<>(NOW));
        service.recover();

        assertThat(store.findRun(success.id()).orElseThrow().status()).isEqualTo(ScheduledRunStatus.SUCCESS);
        ScheduledTask successAfter = store.findTaskInternal(successTask.id()).orElseThrow();
        assertThat(successAfter.status()).isEqualTo(ScheduledTaskStatus.ENABLED);
        assertThat(successAfter.nextRunAt()).isEqualTo(NOW.plusSeconds(600));

        assertThat(store.findRun(failed.id()).orElseThrow().status()).isEqualTo(ScheduledRunStatus.FAILED);
        assertThat(store.findTaskInternal(failedTask.id()).orElseThrow().status())
                .isEqualTo(ScheduledTaskStatus.FAILED);

        assertThat(store.findRun(launching.id()).orElseThrow().status())
                .isEqualTo(ScheduledRunStatus.QUEUED);
        assertThat(store.findRun(queued.id()).orElseThrow().status()).isEqualTo(ScheduledRunStatus.QUEUED);
        assertThat(store.findRun(interrupted.id()).orElseThrow().status())
                .isEqualTo(ScheduledRunStatus.INTERRUPTED);
        assertThat(store.findRun(active.id()).orElseThrow().status()).isEqualTo(ScheduledRunStatus.RUNNING);
        assertThat(launcher.requests).isEmpty();
    }

    // W19-M2-12: queue timeout fails the waiting occurrence and applies the parent-task rules
    // without executing any Run.
    @Test
    void queueTimeoutFailsWaitingOccurrenceAndAppliesParentRules(@TempDir Path dataDirectory) throws Exception {
        ScheduleStore store = new ScheduleStore(JSON, dataDirectory);
        FakeLauncher launcher = new FakeLauncher();
        SchedulerRunConfiguration configuration = new SchedulerRunConfiguration(false, 5, 60, 3, 60);
        AtomicReference<Instant> clock = new AtomicReference<>(NOW.plusSeconds(120));
        ScheduleDispatchService service = service(store, launcher, configuration, clock);

        ScheduledTask onceAuto = store.createTask(onceTask("Once auto", NOW.plusSeconds(30)), NOW);
        ScheduledTask recurringAuto = store.createTask(intervalTask("Recurring auto", NOW.plusSeconds(30)), NOW);
        ScheduledTask manual = store.createTask(intervalTask("Manual", NOW.plusSeconds(600)), NOW);
        ScheduledTask paused = store.createTask(intervalTask("Paused", NOW.plusSeconds(30)), NOW);
        store.updateTask(paused.id(), OWNER, Map.of("status", "paused"), NOW);

        store.createRun(newRun(onceAuto, ScheduledRunStatus.QUEUED), NOW);
        store.createRun(newRun(recurringAuto, ScheduledRunStatus.QUEUED), NOW);
        store.createRun(newRun(manual, ScheduledRunTrigger.MANUAL, ScheduledRunStatus.QUEUED), NOW);
        store.createRun(newRun(paused, ScheduledRunStatus.QUEUED), NOW);

        service.pollOnce();

        assertThat(launcher.requests).isEmpty();
        assertThat(store.listRuns(onceAuto.id(), OWNER, 10, 0, null).getFirst().status())
                .isEqualTo(ScheduledRunStatus.FAILED);
        assertThat(store.findTaskInternal(onceAuto.id()).orElseThrow().status())
                .isEqualTo(ScheduledTaskStatus.FAILED);

        ScheduledTask recurringAfter = store.findTaskInternal(recurringAuto.id()).orElseThrow();
        assertThat(recurringAfter.status()).isEqualTo(ScheduledTaskStatus.ENABLED);
        assertThat(recurringAfter.nextRunAt()).isEqualTo(NOW.plusSeconds(120).plusSeconds(600));

        ScheduledTask manualAfter = store.findTaskInternal(manual.id()).orElseThrow();
        assertThat(manualAfter.status()).isEqualTo(ScheduledTaskStatus.ENABLED);
        assertThat(manualAfter.nextRunAt()).isEqualTo(NOW.plusSeconds(600));

        ScheduledTask pausedAfter = store.findTaskInternal(paused.id()).orElseThrow();
        assertThat(pausedAfter.status()).isEqualTo(ScheduledTaskStatus.PAUSED);
        assertThat(store.listRuns(paused.id(), OWNER, 10, 0, null).getFirst().status())
                .isEqualTo(ScheduledRunStatus.FAILED);
    }

    // W19-M2-13: a post-launch association write failure keeps the active slot, never re-launches,
    // and a late running write cannot overwrite a terminal occurrence.
    @Test
    void postLaunchBookkeepingFailureKeepsActiveSlot(@TempDir Path dataDirectory) throws Exception {
        FailingStore store = new FailingStore(JSON, dataDirectory);
        FakeLauncher launcher = new FakeLauncher();
        ScheduleDispatchService service = service(store, launcher, configuration(3), new AtomicReference<>(NOW));

        ScheduledTask task = store.createTask(intervalTask("Fault", NOW), NOW);
        // persist #1 createRun, #2 task running, #3 claim, #4 association write.
        store.failOnPersistNumber(4);
        service.pollOnce();

        ScheduledTaskRun occurrence = store.findActiveRun(task.id()).orElseThrow();
        assertThat(occurrence.status()).isEqualTo(ScheduledRunStatus.LAUNCHING);
        assertThat(occurrence.runId()).isNull();
        assertThat(launcher.requests).hasSize(1);

        service.pollOnce();
        assertThat(launcher.requests).hasSize(1);

        // A late launch write never overwrites a terminal occurrence.
        store.updateRunStatus(occurrence.id(), ScheduledRunStatus.SUCCESS, "run-late", null, NOW, NOW, false);
        store.updateRunStatus(occurrence.id(), ScheduledRunStatus.RUNNING, "run-late", "late", NOW, null, true);
        assertThat(store.findRun(occurrence.id()).orElseThrow().status())
                .isEqualTo(ScheduledRunStatus.SUCCESS);
    }

    // W19-M2-06: a manual trigger runs on a paused task without consuming its plan; pause/delete
    // cancel a waiting occurrence and reject an executing one.
    @Test
    void manualTriggerAndPauseDeleteBoundaries(@TempDir Path dataDirectory) throws Exception {
        ScheduleStore store = new ScheduleStore(JSON, dataDirectory);
        FakeLauncher launcher = new FakeLauncher();
        ScheduleDispatchService service = service(store, launcher, configuration(3), new AtomicReference<>(NOW));

        ScheduledTask task = store.createTask(intervalTask("Manual paused", NOW.plusSeconds(600)), NOW);
        store.updateTask(task.id(), OWNER, Map.of("status", "paused"), NOW);

        ScheduledTaskRun triggered = service.triggerManually(task.id(), OWNER);
        assertThat(triggered.trigger()).isEqualTo(ScheduledRunTrigger.MANUAL);
        assertThat(store.findRun(triggered.id()).orElseThrow().status()).isEqualTo(ScheduledRunStatus.RUNNING);
        assertThat(store.findTaskInternal(task.id()).orElseThrow().status())
                .isEqualTo(ScheduledTaskStatus.PAUSED);

        launcher.complete("run-1", ScheduledRunStatus.SUCCESS);
        ScheduledTask afterRun = store.findTaskInternal(task.id()).orElseThrow();
        assertThat(afterRun.status()).isEqualTo(ScheduledTaskStatus.PAUSED);
        assertThat(afterRun.nextRunAt()).isEqualTo(NOW.plusSeconds(600));

        // Pause cancels a waiting occurrence.
        ScheduledTask waiting = store.createTask(intervalTask("Waiting", NOW.plusSeconds(600)), NOW);
        store.createRun(newRun(waiting, ScheduledRunStatus.QUEUED), NOW);
        assertThat(store.pauseTask(waiting.id(), OWNER, NOW)).isTrue();
        assertThat(store.findTaskInternal(waiting.id()).orElseThrow().status())
                .isEqualTo(ScheduledTaskStatus.PAUSED);
        assertThat(store.listRuns(waiting.id(), OWNER, 10, 0, null).getFirst().status())
                .isEqualTo(ScheduledRunStatus.SKIPPED);

        // Delete cancels a waiting occurrence and removes the task.
        ScheduledTask deleting = store.createTask(intervalTask("Deleting", NOW.plusSeconds(600)), NOW);
        store.createRun(newRun(deleting, ScheduledRunStatus.QUEUED), NOW);
        assertThat(store.deleteTask(deleting.id(), OWNER, NOW)).isTrue();
        assertThat(store.findTaskInternal(deleting.id())).isEmpty();
        assertThat(store.listRuns(deleting.id(), OWNER, 10, 0, null)).isEmpty();

        // An executing occurrence conflicts with pause and delete.
        ScheduledTask executing = store.createTask(intervalTask("Executing", NOW.plusSeconds(600)), NOW);
        service.triggerManually(executing.id(), OWNER);
        assertThatThrownBy(() -> store.pauseTask(executing.id(), OWNER, NOW))
                .isInstanceOf(ScheduleStore.ActiveOccurrenceConflict.class);
        assertThatThrownBy(() -> store.deleteTask(executing.id(), OWNER, NOW))
                .isInstanceOf(ScheduleStore.ActiveOccurrenceConflict.class);
    }

    // W19-M2-08: the worker is inert when disabled and stops within its bounded deadline when on.
    @Test
    void serviceLifecycleRespectsEnabledFlag(@TempDir Path dataDirectory) throws Exception {
        ScheduleStore store = new ScheduleStore(JSON, dataDirectory);
        FakeLauncher launcher = new FakeLauncher();
        ScheduleDispatchService disabled = service(store, launcher,
                new SchedulerRunConfiguration(false, 1, 60, 3, 3600), new AtomicReference<>(NOW));
        disabled.start();
        assertThat(disabled.running()).isFalse();
        disabled.close();

        ScheduleDispatchService enabled = service(store, launcher,
                new SchedulerRunConfiguration(true, 1, 60, 3, 3600), new AtomicReference<>(NOW));
        enabled.start();
        assertThat(enabled.running()).isTrue();
        enabled.close();
        assertThat(enabled.running()).isFalse();
    }

    // ------------------------------------------------------------------- helpers

    private static ScheduleDispatchService service(ScheduleStore store, ScheduledRunLauncher launcher,
            SchedulerRunConfiguration configuration, AtomicReference<Instant> clock) {
        return new ScheduleDispatchService(store, launcher, configuration, clock::get);
    }

    private static SchedulerRunConfiguration configuration(int maxConcurrent) {
        return new SchedulerRunConfiguration(false, 5, 60, maxConcurrent, 3600);
    }

    private static ScheduleStore.NewTask intervalTask(String title, Instant nextRunAt) {
        return intervalTask(title, nextRunAt, null);
    }

    private static ScheduleStore.NewTask intervalTask(String title, Instant nextRunAt, String taskId) {
        return new ScheduleStore.NewTask(taskId, OWNER, null, ScheduledTask.FRESH_THREAD_PER_RUN,
                "lead_agent", title, "run this prompt", "interval", Map.of("every_seconds", 600), "UTC",
                nextRunAt);
    }

    private static ScheduleStore.NewTask onceTask(String title, Instant nextRunAt) {
        return new ScheduleStore.NewTask(null, OWNER, null, ScheduledTask.FRESH_THREAD_PER_RUN,
                "lead_agent", title, "run this prompt", "once",
                Map.of("run_at", nextRunAt.toString()), "UTC", nextRunAt);
    }

    private static ScheduleStore.NewRun newRun(ScheduledTask task, ScheduledRunStatus status) {
        return newRun(task, ScheduledRunTrigger.SCHEDULED, status);
    }

    private static ScheduleStore.NewRun newRun(ScheduledTask task, ScheduledRunTrigger trigger,
            ScheduledRunStatus status) {
        return new ScheduleStore.NewRun(null, task.id(), OWNER, "sched-thread-" + task.id(), NOW, trigger,
                status);
    }

    private static ScheduleStore.NewRun activeRun(ScheduledTask task, ScheduledRunStatus status) {
        return new ScheduleStore.NewRun(null, task.id(), OWNER, "sched-thread-" + task.id(), NOW,
                ScheduledRunTrigger.SCHEDULED, status);
    }

    static final class FakeLauncher implements ScheduledRunLauncher {
        final List<LaunchRequest> requests = new ArrayList<>();
        final Map<String, Consumer<ScheduledRunOutcome>> watchers = new HashMap<>();
        final Map<String, ScheduledRunInspection> inspections = new HashMap<>();
        boolean failNextLaunch;
        private final AtomicInteger launches = new AtomicInteger();

        @Override
        public LaunchedRun launch(LaunchRequest request) {
            if (failNextLaunch) {
                failNextLaunch = false;
                throw new IllegalStateException("injected launch failure");
            }
            requests.add(request);
            return new LaunchedRun(request.threadId(), "run-" + launches.incrementAndGet());
        }

        @Override
        public void watch(String runId, Consumer<ScheduledRunOutcome> onTerminal) {
            watchers.put(runId, onTerminal);
        }

        @Override
        public Optional<ScheduledRunInspection> inspect(String ownerId, String threadId, String runId) {
            return Optional.ofNullable(inspections.get(runId));
        }

        void complete(String runId, ScheduledRunStatus status) {
            Consumer<ScheduledRunOutcome> watcher = watchers.remove(runId);
            if (watcher == null) {
                throw new AssertionError("no watcher registered for " + runId);
            }
            String error = switch (status) {
                case FAILED -> "run failed";
                case INTERRUPTED -> "run interrupted";
                default -> null;
            };
            watcher.accept(new ScheduledRunOutcome(runId, status, error));
        }
    }

    /** Fails one selected persist call so a post-launch bookkeeping fault is deterministic. */
    static final class FailingStore extends ScheduleStore {
        private int target = -1;
        private int persists;

        FailingStore(ObjectMapper json, Path dataDirectory) throws IOException {
            super(json, dataDirectory);
        }

        void failOnPersistNumber(int number) {
            this.target = number;
            this.persists = 0;
        }

        @Override
        protected void persist() throws IOException {
            persists++;
            if (target > 0 && persists == target) {
                target = -1;
                throw new IOException("injected persistence failure");
            }
            super.persist();
        }
    }
}

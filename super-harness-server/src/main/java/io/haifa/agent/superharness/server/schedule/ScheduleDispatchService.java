package io.haifa.agent.superharness.server.schedule;

import io.haifa.agent.superharness.server.config.SchedulerRunConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Single-instance background dispatch for scheduled tasks.
 *
 * <p>One daemon worker polls on an injected clock. Each round: startup recovery (once) → queue
 * timeout → admit due tasks → dispatch queued occurrences within the concurrency capacity. Nothing
 * here owns Run truth: it only records occurrence bookkeeping and delegates launch/observation to a
 * {@link ScheduledRunLauncher} backed by the public Haifa Runtime.
 *
 * <p>Delivery uses a claim-then-launch order. An occurrence is atomically moved
 * {@code queued → launching} before any Run starts, so a crash between admission and bookkeeping can
 * never produce a real side effect that the store does not account for. A launch that succeeds but
 * whose association write fails keeps the active slot and is never claimed again.
 */
public final class ScheduleDispatchService implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ScheduleDispatchService.class.getName());
    private static final Duration STOP_DEADLINE = Duration.ofSeconds(5);
    private static final String QUEUE_TIMEOUT_ERROR = "queued occurrence timed out";
    private static final String LAUNCH_FAILURE_ERROR = "scheduled Run could not start";

    private final ScheduleStore store;
    private final ScheduledRunLauncher launcher;
    private final SchedulerRunConfiguration configuration;
    private final Supplier<Instant> clock;

    private final Object stateLock = new Object();
    private Thread worker;
    private volatile boolean running;
    private final AtomicBoolean recovered = new AtomicBoolean();

    public ScheduleDispatchService(ScheduleStore store, ScheduledRunLauncher launcher,
            SchedulerRunConfiguration configuration) {
        this(store, launcher, configuration, Instant::now);
    }

    /** Deterministic-clock seam for the bounded time/recovery cases. */
    public ScheduleDispatchService(ScheduleStore store, ScheduledRunLauncher launcher,
            SchedulerRunConfiguration configuration, Supplier<Instant> clock) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.launcher = java.util.Objects.requireNonNull(launcher, "launcher");
        this.configuration = java.util.Objects.requireNonNull(configuration, "configuration");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public ScheduleStore store() {
        return store;
    }

    public boolean running() {
        Thread active = worker;
        return running && active != null && active.isAlive();
    }

    /** Starts the single daemon worker; disabled configuration stays inert. */
    public void start() {
        synchronized (stateLock) {
            if (!configuration.enabled() || running) {
                return;
            }
            running = true;
            worker = new Thread(this::runLoop, "super-harness-scheduler");
            worker.setDaemon(true);
            worker.start();
        }
    }

    @Override
    public void close() {
        Thread active;
        synchronized (stateLock) {
            running = false;
            active = worker;
            worker = null;
            stateLock.notifyAll();
        }
        if (active == null) {
            return;
        }
        active.interrupt();
        try {
            active.join(STOP_DEADLINE);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** One bounded round; also the deterministic entry point for tests. */
    public void pollOnce() {
        synchronized (this) {
            Instant now = clock.get();
            recoverIfNeeded(now);
            expireQueuedRuns(now);
            admitDueTasks(now);
            dispatchQueuedRuns(now);
        }
    }

    /** Runs only the once-per-process startup recovery; idempotent. */
    public void recover() {
        synchronized (this) {
            recoverIfNeeded(clock.get());
        }
    }

    /**
     * Manual trigger. Allowed while the task is paused; an active occurrence is a conflict. A
     * trigger whose launch fails releases the slot without consuming the task's own plan.
     */
    public ScheduledTaskRun triggerManually(String taskId, String ownerId) {
        synchronized (this) {
            Instant now = clock.get();
            ScheduledTask task = store.findTask(taskId, ownerId)
                    .orElseThrow(() -> new ScheduleValidationException("Scheduled task not found"));
            store.findActiveRun(taskId).ifPresent(active -> {
                throw new ScheduleStore.ActiveOccurrenceConflict(taskId, active.status().wire());
            });
            ScheduledTaskRun occurrence = store.createRun(new ScheduleStore.NewRun(null, taskId, ownerId,
                    threadIdFor(task, now), now, ScheduledRunTrigger.MANUAL, ScheduledRunStatus.QUEUED), now);
            if (availableCapacity() > 0 && store.claimQueuedRun(occurrence.id(), now)) {
                launchOccurrence(store.findRun(occurrence.id()).orElseThrow(), now);
            }
            return store.findRun(occurrence.id()).orElseThrow();
        }
    }

    // ------------------------------------------------------------------ poll rounds

    private void runLoop() {
        while (running) {
            // One transient round failure (recovery, expiry, admission or persistence) must not
            // terminate the single worker; isolate the round, keep the diagnostic and continue
            // polling at the configured interval while still honouring shutdown.
            try {
                pollOnce();
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING,
                        "scheduler poll round failed: " + failure.getClass().getName());
            }
            awaitNextPoll();
        }
    }

    private void awaitNextPoll() {
        try {
            Thread.sleep(Duration.ofSeconds(configuration.pollIntervalSeconds()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Graded startup recovery, matching the frozen {@code mark_stale_active_runs}: {@code queued} is
     * a durable waiting item and is never converged; a {@code launching} occurrence without a
     * committed Run returns to {@code queued}; an executing occurrence with a committed Run is
     * converged from that Run's real result. A still-active Run keeps its slot and gets a
     * replacement follower, so a Run that outlived the previous process still records its terminal
     * state. An inspection read that is temporarily unavailable stays fail-closed and is retried on
     * a later round instead of releasing the slot.
     */
    private void recoverIfNeeded(Instant now) {
        if (recovered.compareAndSet(false, true)) {
            for (ScheduledTaskRun occurrence : store.listExecutingRuns()) {
                if (occurrence.runId() != null) {
                    continue;
                }
                if (occurrence.status() == ScheduledRunStatus.LAUNCHING) {
                    store.revertLaunchingToQueued(occurrence.id(), now);
                } else {
                    recordTerminal(occurrence.id(), ScheduledRunStatus.INTERRUPTED,
                            "run association unavailable", now);
                }
            }
        }
        List<ScheduledTaskRun> executing = store.listExecutingRuns();
        for (ScheduledTaskRun occurrence : executing) {
            String runId = occurrence.runId();
            if (runId == null) {
                continue;
            }
            String owner = store.ownerOfRun(occurrence.id()).orElse(null);
            if (owner == null) {
                recordTerminal(occurrence.id(), ScheduledRunStatus.INTERRUPTED, "task unavailable", now);
                continue;
            }
            Optional<ScheduledRunLauncher.ScheduledRunInspection> inspection;
            try {
                inspection = launcher.inspect(owner, occurrence.threadId(), runId);
            } catch (ScheduledRunLauncher.InspectionUnavailableException unavailable) {
                // Keep the active slot and retry the inspection on a later round.
                LOG.log(System.Logger.Level.WARNING,
                        "scheduled Run inspection unavailable for run " + runId);
                continue;
            }
            if (inspection.isEmpty()) {
                recordTerminal(occurrence.id(), ScheduledRunStatus.INTERRUPTED,
                        "run association unavailable", now);
                continue;
            }
            ScheduledRunLauncher.ScheduledRunInspection found = inspection.orElseThrow();
            if (found.terminal()) {
                ScheduledRunStatus status = found.terminalStatus();
                recordTerminal(occurrence.id(),
                        status == null || !status.terminal() ? ScheduledRunStatus.INTERRUPTED : status,
                        found.error(), now);
                continue;
            }
            // The Run still owns its active slot; ensure a follower is observing it so its
            // completion converges the occurrence instead of leaving it running and consuming
            // capacity. Reattaching is idempotent, so a Run already observed here is a no-op.
            try {
                launcher.watch(owner, occurrence.threadId(), runId,
                        outcome -> recordTerminal(occurrence.id(), outcome.terminal(), outcome.error()));
            } catch (RuntimeException watchFailure) {
                LOG.log(System.Logger.Level.WARNING,
                        "scheduled Run follower could not be reattached: "
                                + watchFailure.getClass().getName());
            }
        }
    }

    private void expireQueuedRuns(Instant now) {
        Instant deadline = now.minusSeconds(configuration.queueTimeoutSeconds());
        for (ScheduledTaskRun occurrence : store.listQueuedRuns()) {
            if (occurrence.createdAt().isAfter(deadline)) {
                continue;
            }
            store.updateRunStatus(occurrence.id(), ScheduledRunStatus.FAILED, occurrence.runId(),
                    QUEUE_TIMEOUT_ERROR, occurrence.startedAt(), now, false);
            ScheduledTask task = store.findTaskInternal(occurrence.taskId()).orElse(null);
            if (task == null || task.status() == ScheduledTaskStatus.PAUSED) {
                // A paused task is not overwritten by the timeout; only its waiting item is cleared.
                continue;
            }
            if (occurrence.trigger() == ScheduledRunTrigger.MANUAL) {
                // A manual trigger never consumes the task's own plan.
                continue;
            }
            ScheduledTaskStatus status =
                    "once".equals(task.scheduleType()) ? ScheduledTaskStatus.FAILED : ScheduledTaskStatus.ENABLED;
            store.finishTask(task.id(), status, computeNextRun(task, now), now, occurrence.runId(),
                    occurrence.threadId(), QUEUE_TIMEOUT_ERROR, true, now);
        }
    }

    private void admitDueTasks(Instant now) {
        for (ScheduledTask task : store.listDueTasks(now)) {
            if (store.findActiveRun(task.id()).isPresent()) {
                // Single active occurrence: repeated due times merge instead of stacking.
                continue;
            }
            ScheduledTaskRun occurrence;
            try {
                occurrence = store.createRun(new ScheduleStore.NewRun(null, task.id(), task.userId(),
                        threadIdFor(task, now), task.nextRunAt(), ScheduledRunTrigger.SCHEDULED,
                        ScheduledRunStatus.QUEUED), now);
            } catch (ScheduleStore.ActiveOccurrenceConflict conflict) {
                continue;
            }
            // Mark the task running so the same due time is not admitted again while active. The
            // recurring next_run_at is recomputed only when the plan returns to enabled.
            store.updateTaskAfterLaunch(task.id(), new ScheduleStore.TaskLaunchUpdate("running", null,
                    null, null, null, null, false, true, occurrence.id()), now);
        }
    }

    private void dispatchQueuedRuns(Instant now) {
        int capacity = availableCapacity();
        for (ScheduledTaskRun candidate : store.listQueuedRuns()) {
            if (capacity <= 0) {
                return;
            }
            if (!store.claimQueuedRun(candidate.id(), now)) {
                continue;
            }
            if (launchOccurrence(store.findRun(candidate.id()).orElseThrow(), now)) {
                capacity--;
            }
        }
    }

    // -------------------------------------------------------------------- launch

    private boolean launchOccurrence(ScheduledTaskRun occurrence, Instant now) {
        ScheduledTask task = store.findTaskInternal(occurrence.taskId()).orElse(null);
        if (task == null) {
            store.updateRunStatus(occurrence.id(), ScheduledRunStatus.FAILED, null, "task unavailable",
                    null, now, false);
            return false;
        }
        ScheduledRunLauncher.LaunchedRun launched;
        try {
            launched = launcher.launch(new ScheduledRunLauncher.LaunchRequest(task.userId(),
                    occurrence.threadId(), task.prompt(), task.assistantId(),
                    ScheduledTask.FRESH_THREAD_PER_RUN.equals(task.contextMode())));
        } catch (ScheduledRunLauncher.ActiveRunConflict conflict) {
            // Another Run still owns the target Thread: keep this occurrence waiting instead of
            // starting a second Run or failing it. The queue timeout still bounds the wait.
            store.revertLaunchingToQueued(occurrence.id(), now);
            return false;
        } catch (RuntimeException failure) {
            releaseFailedLaunch(occurrence, task, now);
            return false;
        }
        boolean associated = true;
        try {
            // Association write happens before the follower starts, so a fast-completing Run can
            // never be overwritten back to running by late launch bookkeeping.
            store.updateRunStatus(occurrence.id(), ScheduledRunStatus.RUNNING, launched.runId(), null, now,
                    null, true);
        } catch (RuntimeException associationFailure) {
            associated = false;
            LOG.log(System.Logger.Level.WARNING,
                    "scheduled launch association failed for run " + launched.runId());
        }
        if (associated) {
            try {
                // A paused task or a manual trigger keeps the task's own status instead of borrowing
                // the automatic "running". This is secondary bookkeeping: its failure must not stop
                // the terminal follower from observing the committed Run.
                String launchStatus = task.status() == ScheduledTaskStatus.PAUSED
                        || occurrence.trigger() == ScheduledRunTrigger.MANUAL ? null : "running";
                store.updateTaskAfterLaunch(task.id(), new ScheduleStore.TaskLaunchUpdate(launchStatus, null,
                        now, launched.runId(), launched.threadId(), null, true, true, occurrence.id()), now);
            } catch (RuntimeException bookkeepingFailure) {
                LOG.log(System.Logger.Level.WARNING,
                        "scheduled launch bookkeeping failed for run " + launched.runId());
            }
        }
        try {
            launcher.watch(task.userId(), launched.threadId(), launched.runId(),
                    outcome -> recordTerminal(occurrence.id(), outcome.terminal(), outcome.error()));
        } catch (RuntimeException watchFailure) {
            // A follower that could not start leaves the Run unobserved here; recovery retries the
            // observation on a later round rather than releasing the active slot.
            LOG.log(System.Logger.Level.WARNING, "scheduled Run follower could not start: "
                    + watchFailure.getClass().getName());
        }
        return true;
    }

    private void releaseFailedLaunch(ScheduledTaskRun occurrence, ScheduledTask task, Instant now) {
        store.updateRunStatus(occurrence.id(), ScheduledRunStatus.FAILED, null, LAUNCH_FAILURE_ERROR, null,
                now, false);
        if (task.status() == ScheduledTaskStatus.PAUSED
                || occurrence.trigger() == ScheduledRunTrigger.MANUAL) {
            return;
        }
        ScheduledTaskStatus status =
                "once".equals(task.scheduleType()) ? ScheduledTaskStatus.FAILED : ScheduledTaskStatus.ENABLED;
        store.finishTask(task.id(), status, computeNextRun(task, now), null, null, null, LAUNCH_FAILURE_ERROR,
                false, now);
    }

    // ------------------------------------------------------------------ terminal

    private void recordTerminal(String occurrenceId, ScheduledRunStatus terminal, String error) {
        recordTerminal(occurrenceId, terminal, error, clock.get());
    }

    private void recordTerminal(String occurrenceId, ScheduledRunStatus terminal, String error, Instant now) {
        ScheduledTaskRun occurrence = store.findRun(occurrenceId).orElse(null);
        if (occurrence == null) {
            return;
        }
        ScheduledRunStatus status = terminal == null || !terminal.terminal()
                ? ScheduledRunStatus.INTERRUPTED : terminal;
        store.updateRunStatus(occurrenceId, status, occurrence.runId(), error, occurrence.startedAt(), now,
                true);
        ScheduledTask task = store.findTaskInternal(occurrence.taskId()).orElse(null);
        if (task == null) {
            return;
        }
        if (task.status() == ScheduledTaskStatus.PAUSED) {
            // A manual Run on a paused task keeps the paused status and its plan.
            store.finishTask(task.id(), ScheduledTaskStatus.PAUSED, null, now, occurrence.runId(),
                    occurrence.threadId(), error, false, now);
            return;
        }
        if ("once".equals(task.scheduleType())) {
            ScheduledTaskStatus taskStatus = switch (status) {
                case SUCCESS -> ScheduledTaskStatus.COMPLETED;
                case FAILED -> ScheduledTaskStatus.FAILED;
                default -> ScheduledTaskStatus.CANCELLED;
            };
            store.finishTask(task.id(), taskStatus, null, now, occurrence.runId(), occurrence.threadId(),
                    error, true, now);
        } else {
            store.finishTask(task.id(), ScheduledTaskStatus.ENABLED, computeNextRun(task, now), now,
                    occurrence.runId(), occurrence.threadId(), error, false, now);
        }
    }

    // -------------------------------------------------------------------- helpers

    private int availableCapacity() {
        return Math.max(0, configuration.maxConcurrentRuns() - store.listExecutingRuns().size());
    }

    private static String threadIdFor(ScheduledTask task, Instant now) {
        String bound = task.threadId();
        if (ScheduledTask.REUSE_THREAD.equals(task.contextMode()) && bound != null && !bound.isBlank()) {
            return bound;
        }
        return "sched-" + UUID.randomUUID();
    }

    private Instant computeNextRun(ScheduledTask task, Instant now) {
        if ("once".equals(task.scheduleType())) {
            return null;
        }
        try {
            return ScheduleCalculator.nextRunAt(
                    task.scheduleType(), task.scheduleSpec(), task.timezone(), now);
        } catch (ScheduleValidationException invalid) {
            return null;
        }
    }
}

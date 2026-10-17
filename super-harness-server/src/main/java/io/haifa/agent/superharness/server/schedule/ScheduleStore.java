package io.haifa.agent.superharness.server.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.workspace.AtomicMoves;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * File-backed product store for scheduled tasks and their occurrences.
 *
 * <p>Single-instance scope: one JSON document under {@code <dataDirectory>/scheduler/state.json},
 * published with the repository's atomic replace helper. Every mutation runs under one lock and is
 * rolled back in memory when the atomic publish fails, so a failed association/bookkeeping write
 * leaves the last durable state intact (in particular a launch claim already on disk stays active).
 *
 * <p>Run execution is owned by the Haifa Runtime; this store only references a Run id. It carries no
 * lease/fence/queue machinery (single instance) but does expose the minimal atomic operations M2
 * needs: {@link #claimQueuedRun}, terminal-protected {@link #updateRunStatus}, recoverable
 * {@link #associateRun}, and once-only run accounting in {@link #updateTaskAfterLaunch}.
 */
public class ScheduleStore {
    private static final int FORMAT_VERSION = 1;
    private static final long MAXIMUM_BYTES = 16L * 1024 * 1024;
    private static final int MAXIMUM_PAGE_SIZE = 200;
    private static final int MAXIMUM_HISTORY_PER_TASK = 200;

    private final ObjectMapper json;
    private final Path file;
    private final Object lock = new Object();
    private final Map<String, ScheduledTask> tasks = new LinkedHashMap<>();
    private final Map<String, ScheduledTaskRun> runs = new LinkedHashMap<>();

    public ScheduleStore(ObjectMapper json, Path dataDirectory) throws IOException {
        this.json = Objects.requireNonNull(json, "json");
        Path directory = dataDirectory.toAbsolutePath().normalize().resolve("scheduler");
        Files.createDirectories(directory);
        this.file = directory.resolve("state.json");
        load();
    }

    // ---------------------------------------------------------------- tasks

    public ScheduledTask createTask(NewTask request, Instant now) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(now, "now");
        synchronized (lock) {
            String id = request.taskId() == null || request.taskId().isBlank()
                    ? "task-" + hex()
                    : request.taskId();
            String contextMode = request.contextMode() == null
                    ? ScheduledTask.FRESH_THREAD_PER_RUN
                    : request.contextMode();
            ScheduledTask task = new ScheduledTask(id, request.ownerId(), request.threadId(),
                    contextMode, request.assistantId(), request.title(), request.prompt(),
                    request.scheduleType(), request.scheduleSpec(), request.timezone(),
                    ScheduledTaskStatus.ENABLED, request.nextRunAt(), null, null, null, null, 0, 0,
                    now, now);
            mutate(() -> {
                if (tasks.putIfAbsent(id, task) != null) {
                    throw new ScheduleValidationException("Scheduled task already exists: " + id);
                }
            });
            return task;
        }
    }

    public Optional<ScheduledTask> findTask(String taskId, String ownerId) {
        synchronized (lock) {
            ScheduledTask task = tasks.get(taskId);
            return task != null && task.userId().equals(ownerId) ? Optional.of(task) : Optional.empty();
        }
    }

    /** Worker-scope read without an auth boundary; M2 dispatch uses it inside its own owner call. */
    public Optional<ScheduledTask> findTaskInternal(String taskId) {
        synchronized (lock) {
            return Optional.ofNullable(tasks.get(taskId));
        }
    }

    public List<ScheduledTask> listTasks(String ownerId) {
        synchronized (lock) {
            return tasks.values().stream()
                    .filter(task -> task.userId().equals(ownerId))
                    .sorted(byRecency())
                    .toList();
        }
    }

    public List<ScheduledTask> listTasksByThread(String ownerId, String threadId) {
        synchronized (lock) {
            return tasks.values().stream()
                    .filter(task -> task.userId().equals(ownerId) && Objects.equals(task.threadId(), threadId))
                    .sorted(byRecency())
                    .toList();
        }
    }

    /**
     * Applies an allow-listed patch to an owner's task. Recognized keys: {@code context_mode},
     * {@code thread_id}, {@code assistant_id}, {@code title}, {@code prompt}, {@code schedule_spec},
     * {@code timezone}, {@code next_run_at} (ISO string or {@link Instant}) and {@code status}.
     * Returns empty when the task does not exist for the owner.
     */
    public Optional<ScheduledTask> updateTask(String taskId, String ownerId, Map<String, Object> updates,
            Instant now) {
        Objects.requireNonNull(updates, "updates");
        Objects.requireNonNull(now, "now");
        synchronized (lock) {
            ScheduledTask current = tasks.get(taskId);
            if (current == null || !current.userId().equals(ownerId)) {
                return Optional.empty();
            }
            ScheduledTask updated = applyUpdates(current, updates, now);
            mutate(() -> tasks.put(taskId, updated));
            return Optional.of(updated);
        }
    }

    /**
     * Deletes an owner's task, cancelling any waiting {@code queued} occurrence first. A
     * launching/running occurrence makes the delete conflict ({@link ActiveOccurrenceConflict}) so a
     * deleting task never strands an executing Run.
     */
    public boolean deleteTask(String taskId, String ownerId, Instant now) {
        Objects.requireNonNull(now, "now");
        synchronized (lock) {
            ScheduledTask current = tasks.get(taskId);
            if (current == null || !current.userId().equals(ownerId)) {
                return false;
            }
            ScheduledTaskRun active = findActive(taskId).orElse(null);
            if (active != null && active.status() != ScheduledRunStatus.QUEUED) {
                throw new ActiveOccurrenceConflict(taskId, active.status().wire());
            }
            mutate(() -> {
                tasks.remove(taskId);
                runs.values().removeIf(run -> run.taskId().equals(taskId));
            });
            return true;
        }
    }

    /** Legacy delete without a clock; preserved for read-only checks and existing callers. */
    public boolean deleteTask(String taskId, String ownerId) {
        return deleteTask(taskId, ownerId, Instant.now());
    }

    // ----------------------------------------------------------- occurrences

    /**
     * Inserts an occurrence, enforcing the single-active-occurrence constraint and allocating the
     * per-task occurrence sequence. Raises {@link ActiveOccurrenceConflict} when the task already
     * has a queued/launching/running occurrence.
     */
    public ScheduledTaskRun createRun(NewRun request, Instant now) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(now, "now");
        synchronized (lock) {
            ScheduledTask task = tasks.get(request.taskId());
            if (task == null) {
                throw new ScheduleValidationException("Scheduled task not found: " + request.taskId());
            }
            if (!task.userId().equals(request.ownerId())) {
                throw new ScheduleValidationException("Scheduled task not found: " + request.taskId());
            }
            if (!request.status().active()) {
                throw new ScheduleValidationException(
                        "occurrence must start active: " + request.status().wire());
            }
            findActive(task.id()).ifPresent(active -> {
                throw new ActiveOccurrenceConflict(task.id(), active.status().wire());
            });
            String id = request.runRecordId() == null || request.runRecordId().isBlank()
                    ? "occ-" + hex()
                    : request.runRecordId();
            if (runs.containsKey(id)) {
                throw new ScheduleValidationException("Occurrence already exists: " + id);
            }
            long sequence = task.lastOccurrenceSeq() + 1;
            ScheduledTaskRun run = new ScheduledTaskRun(id, task.id(), sequence, request.threadId(),
                    null, request.scheduledFor(), request.trigger(), request.status(), null, null, null,
                    0, Boolean.FALSE, now);
            ScheduledTask updatedTask = new ScheduledTask(task.id(), task.userId(), task.threadId(),
                    task.contextMode(), task.assistantId(), task.title(), task.prompt(),
                    task.scheduleType(), task.scheduleSpec(), task.timezone(), task.status(),
                    task.nextRunAt(), task.lastRunAt(), task.lastRunId(), task.lastThreadId(),
                    task.lastError(), task.runCount(), sequence, task.createdAt(), task.updatedAt());
            mutate(() -> {
                tasks.put(task.id(), updatedTask);
                runs.put(id, run);
                pruneHistory(task.id());
            });
            return run;
        }
    }

    public Optional<ScheduledTaskRun> findRun(String runRecordId) {
        synchronized (lock) {
            return Optional.ofNullable(runs.get(runRecordId));
        }
    }

    public Optional<ScheduledTaskRun> findActiveRun(String taskId) {
        synchronized (lock) {
            return findActive(taskId);
        }
    }

    public List<ScheduledTaskRun> listRuns(String taskId, String ownerId, int limit, int offset,
            ScheduledRunStatus status) {
        if (limit < 1 || limit > MAXIMUM_PAGE_SIZE) {
            throw new ScheduleValidationException("limit must be between 1 and " + MAXIMUM_PAGE_SIZE);
        }
        if (offset < 0) {
            throw new ScheduleValidationException("offset must not be negative");
        }
        synchronized (lock) {
            ScheduledTask task = tasks.get(taskId);
            if (task == null || !task.userId().equals(ownerId)) {
                return List.of();
            }
            return runs.values().stream()
                    .filter(run -> run.taskId().equals(taskId))
                    .filter(run -> status == null || run.status() == status)
                    .sorted(Comparator.comparing(ScheduledTaskRun::createdAt).reversed()
                            .thenComparing(ScheduledTaskRun::id, Comparator.reverseOrder()))
                    .skip(offset)
                    .limit(limit)
                    .toList();
        }
    }

    /**
     * Atomically moves one {@code queued} occurrence to {@code launching}, incrementing its attempt
     * count. Returns {@code true} only for the caller that observed the queued state.
     */
    public boolean claimQueuedRun(String runRecordId, Instant now) {
        Objects.requireNonNull(now, "now");
        synchronized (lock) {
            ScheduledTaskRun run = runs.get(runRecordId);
            if (run == null || run.status() != ScheduledRunStatus.QUEUED) {
                return false;
            }
            ScheduledTaskRun claimed = new ScheduledTaskRun(run.id(), run.taskId(), run.occurrenceSeq(),
                    run.threadId(), run.runId(), run.scheduledFor(), run.trigger(),
                    ScheduledRunStatus.LAUNCHING, run.error(), run.startedAt(), run.finishedAt(),
                    run.attemptCount() + 1, run.launchAccounted(), run.createdAt());
            mutate(() -> runs.put(claimed.id(), claimed));
            return true;
        }
    }

    /**
     * Recoverable association write: fills {@code thread_id}/{@code run_id}/{@code started_at}
     * without changing the occurrence status, so a failed launch bookkeeping write never releases
     * the active slot. Returns false only when the occurrence is unknown.
     */
    public boolean associateRun(String runRecordId, String threadId, String runIdValue, Instant startedAt) {
        synchronized (lock) {
            ScheduledTaskRun run = runs.get(runRecordId);
            if (run == null) {
                return false;
            }
            ScheduledTaskRun associated = new ScheduledTaskRun(run.id(), run.taskId(),
                    run.occurrenceSeq(), threadId == null ? run.threadId() : threadId,
                    runIdValue == null ? run.runId() : runIdValue, run.scheduledFor(), run.trigger(),
                    run.status(), run.error(), startedAt == null ? run.startedAt() : startedAt,
                    run.finishedAt(), run.attemptCount(), run.launchAccounted(), run.createdAt());
            mutate(() -> runs.put(associated.id(), associated));
            return true;
        }
    }

    /**
     * Terminal-protected occurrence update. When {@code protectTerminal} is set and the row is
     * already terminal, the terminal status/error/finished time are kept and only absent
     * {@code run_id}/{@code started_at} bookkeeping is backfilled.
     */
    public boolean updateRunStatus(String runRecordId, ScheduledRunStatus status, String runIdValue,
            String error, Instant startedAt, Instant finishedAt, boolean protectTerminal) {
        Objects.requireNonNull(status, "status");
        synchronized (lock) {
            ScheduledTaskRun updated = runUpdate(runRecordId, status, runIdValue, error, startedAt,
                    finishedAt, protectTerminal);
            if (updated == null) {
                return false;
            }
            mutate(() -> {
                runs.put(updated.id(), updated);
                if (updated.status().terminal()) {
                    pruneHistory(updated.taskId());
                }
            });
            return true;
        }
    }

    /**
     * Terminal-protected occurrence update and the parent-task bookkeeping in one rollback boundary.
     * Recovery scans only {@code launching}/{@code running} occurrences and due-task admission
     * requires an {@code enabled} task, so a terminal occurrence whose parent write was lost would
     * strand the schedule permanently. {@code taskFinish} may be null when the parent task no longer
     * needs a write (already gone, paused or a manual trigger); the occurrence is still published.
     */
    public boolean completeOccurrenceAndTask(String runRecordId, ScheduledRunStatus status, String runIdValue,
            String error, Instant startedAt, Instant finishedAt, boolean protectTerminal,
            TaskFinish parentFinish, Instant now) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(now, "now");
        synchronized (lock) {
            ScheduledTaskRun updated = runUpdate(runRecordId, status, runIdValue, error, startedAt,
                    finishedAt, protectTerminal);
            if (updated == null) {
                return false;
            }
            ScheduledTask parent = parentFinish == null ? null : tasks.get(parentFinish.taskId());
            ScheduledTask updatedTask = parent == null ? null
                    : taskFinish(parent, parentFinish.status(), parentFinish.nextRunAt(),
                            parentFinish.lastRunAt(), parentFinish.lastRunId(), parentFinish.lastThreadId(),
                            parentFinish.lastError(), parentFinish.protectTerminal(), now);
            mutate(() -> {
                runs.put(updated.id(), updated);
                if (updated.status().terminal()) {
                    pruneHistory(updated.taskId());
                }
                if (updatedTask != null) {
                    tasks.put(updatedTask.id(), updatedTask);
                }
            });
            return true;
        }
    }

    private ScheduledTaskRun runUpdate(String runRecordId, ScheduledRunStatus status, String runIdValue,
            String error, Instant startedAt, Instant finishedAt, boolean protectTerminal) {
        ScheduledTaskRun run = runs.get(runRecordId);
        if (run == null) {
            return null;
        }
        if (protectTerminal && run.status().terminal()) {
            return new ScheduledTaskRun(run.id(), run.taskId(), run.occurrenceSeq(),
                    run.threadId(), run.runId() == null ? runIdValue : run.runId(),
                    run.scheduledFor(), run.trigger(), run.status(), run.error(),
                    run.startedAt() == null ? startedAt : run.startedAt(), run.finishedAt(),
                    run.attemptCount(), run.launchAccounted(), run.createdAt());
        }
        return new ScheduledTaskRun(run.id(), run.taskId(), run.occurrenceSeq(),
                run.threadId(), runIdValue, run.scheduledFor(), run.trigger(), status, error,
                startedAt == null ? run.startedAt() : startedAt, finishedAt, run.attemptCount(),
                run.launchAccounted(), run.createdAt());
    }

    /**
     * Launch bookkeeping for the parent task. The run count increments at most once per distinct
     * {@code lastRunId}; when {@code protectTerminal} is set a task or occurrence already terminal
     * keeps its status/error while schedule and association fields are still written.
     */
    public boolean updateTaskAfterLaunch(String taskId, TaskLaunchUpdate update, Instant now) {
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(now, "now");
        synchronized (lock) {
            ScheduledTask task = tasks.get(taskId);
            if (task == null) {
                return false;
            }
            ScheduledTaskRun occurrence = update.taskRunId() == null ? null : runs.get(update.taskRunId());
            // Once-only accounting: when the occurrence row tracks the launch, its launchAccounted
            // marker is authoritative, so a recoverable retry cannot increment run_count twice. Only
            // occurrence-less updates fall back to comparing the last stable run id.
            boolean accountLaunch;
            if (occurrence != null) {
                accountLaunch = update.incrementRunCount()
                        && !Boolean.TRUE.equals(occurrence.launchAccounted());
            } else {
                accountLaunch = update.incrementRunCount()
                        && (update.lastRunId() == null || !update.lastRunId().equals(task.lastRunId()));
            }
            ScheduledTaskRun accounted = accountLaunch && occurrence != null
                    ? new ScheduledTaskRun(occurrence.id(), occurrence.taskId(),
                            occurrence.occurrenceSeq(), occurrence.threadId(), occurrence.runId(),
                            occurrence.scheduledFor(), occurrence.trigger(), occurrence.status(),
                            occurrence.error(), occurrence.startedAt(), occurrence.finishedAt(),
                            occurrence.attemptCount(), Boolean.TRUE, occurrence.createdAt())
                    : null;
            boolean protectedTerminal = update.protectTerminal()
                    && (task.status().terminal()
                            || occurrence != null && occurrence.status().terminal());
            ScheduledTask updated = new ScheduledTask(task.id(), task.userId(), task.threadId(),
                    task.contextMode(), task.assistantId(), task.title(), task.prompt(),
                    task.scheduleType(), task.scheduleSpec(), task.timezone(),
                    protectedTerminal ? task.status()
                            : update.status() == null ? task.status()
                                    : ScheduledTaskStatus.fromWire(update.status()),
                    update.nextRunAt() == null ? task.nextRunAt() : update.nextRunAt(),
                    update.lastRunAt() == null ? task.lastRunAt() : update.lastRunAt(),
                    update.lastRunId() == null ? task.lastRunId() : update.lastRunId(),
                    update.lastThreadId() == null ? task.lastThreadId() : update.lastThreadId(),
                    protectedTerminal ? task.lastError() : update.lastError(),
                    task.runCount() + (accountLaunch ? 1 : 0), task.lastOccurrenceSeq(),
                    task.createdAt(), now);
            // The occurrence accounting marker and the task row publish in one rollback boundary, so a
            // failed write never leaves launchAccounted advanced without the matching run_count.
            mutate(() -> {
                if (accounted != null) {
                    runs.put(accounted.id(), accounted);
                }
                tasks.put(taskId, updated);
            });
            return true;
        }
    }

    /** Read an occurrence's parent owner, used to scope M2 worker reads. */
    public Optional<String> ownerOfRun(String runRecordId) {
        synchronized (lock) {
            ScheduledTaskRun run = runs.get(runRecordId);
            if (run == null) {
                return Optional.empty();
            }
            ScheduledTask task = tasks.get(run.taskId());
            return task == null ? Optional.empty() : Optional.of(task.userId());
        }
    }

    // ------------------------------------------------------------- dispatch reads

    /**
     * Enabled tasks whose authoritative {@code next_run_at} is due at {@code now}. A task may have a
     * due time and still not be admissible when it already holds its single active occurrence; the
     * dispatch worker checks that separately so repeated due times merge instead of queuing twice.
     */
    public List<ScheduledTask> listDueTasks(Instant now) {
        Objects.requireNonNull(now, "now");
        synchronized (lock) {
            return tasks.values().stream()
                    .filter(task -> task.status() == ScheduledTaskStatus.ENABLED)
                    .filter(task -> task.nextRunAt() != null && !task.nextRunAt().isAfter(now))
                    .sorted(Comparator.comparing(ScheduledTask::nextRunAt)
                            .thenComparing(ScheduledTask::id))
                    .toList();
        }
    }

    /** Persisted waiting items, oldest first. These survive a restart and are never converged. */
    public List<ScheduledTaskRun> listQueuedRuns() {
        synchronized (lock) {
            return runs.values().stream()
                    .filter(run -> run.status() == ScheduledRunStatus.QUEUED)
                    .sorted(Comparator.comparing(ScheduledTaskRun::createdAt)
                            .thenComparing(ScheduledTaskRun::id))
                    .toList();
        }
    }

    /** Occurrences whose Run is (or is expected to be) executing: {@code launching} and {@code running}. */
    public List<ScheduledTaskRun> listExecutingRuns() {
        synchronized (lock) {
            return runs.values().stream()
                    .filter(run -> run.status() == ScheduledRunStatus.LAUNCHING
                            || run.status() == ScheduledRunStatus.RUNNING)
                    .sorted(Comparator.comparing(ScheduledTaskRun::createdAt)
                            .thenComparing(ScheduledTaskRun::id))
                    .toList();
        }
    }

    // --------------------------------------------------------- dispatch mutations

    /**
     * Cancels one waiting {@code queued} occurrence atomically, marking it {@code skipped}. Used when
     * a task is paused/deleted: the active slot is released without touching a launching/running Run.
     */
    public boolean cancelQueuedRun(String runRecordId, Instant now) {
        Objects.requireNonNull(now, "now");
        synchronized (lock) {
            ScheduledTaskRun run = runs.get(runRecordId);
            if (run == null || run.status() != ScheduledRunStatus.QUEUED) {
                return false;
            }
            ScheduledTaskRun cancelled = new ScheduledTaskRun(run.id(), run.taskId(), run.occurrenceSeq(),
                    run.threadId(), run.runId(), run.scheduledFor(), run.trigger(),
                    ScheduledRunStatus.SKIPPED, run.error(), run.startedAt(), now, run.attemptCount(),
                    run.launchAccounted(), run.createdAt());
            mutate(() -> runs.put(cancelled.id(), cancelled));
            return true;
        }
    }

    /**
     * Restart recovery: a {@code launching} occurrence with no committed Run safely returns to
     * {@code queued} so the next poll can launch it again. A {@code running} occurrence or one that
     * already names a Run is left untouched (it owns an active slot and must be converged from the
     * Run's real result instead).
     */
    public boolean revertLaunchingToQueued(String runRecordId, Instant now) {
        Objects.requireNonNull(now, "now");
        synchronized (lock) {
            ScheduledTaskRun run = runs.get(runRecordId);
            if (run == null || run.status() != ScheduledRunStatus.LAUNCHING || run.runId() != null) {
                return false;
            }
            ScheduledTaskRun queued = new ScheduledTaskRun(run.id(), run.taskId(), run.occurrenceSeq(),
                    run.threadId(), null, run.scheduledFor(), run.trigger(), ScheduledRunStatus.QUEUED,
                    null, null, null, run.attemptCount(), run.launchAccounted(), run.createdAt());
            mutate(() -> runs.put(queued.id(), queued));
            return true;
        }
    }

    /**
     * Pauses an owner's task. A launching/running occurrence makes the pause conflict
     * ({@link ActiveOccurrenceConflict}); a waiting {@code queued} occurrence is cancelled first so
     * the pause is atomic and no orphaned waiting item survives.
     */
    public boolean pauseTask(String taskId, String ownerId, Instant now) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(now, "now");
        synchronized (lock) {
            ScheduledTask task = tasks.get(taskId);
            if (task == null || !task.userId().equals(ownerId)) {
                return false;
            }
            ScheduledTaskRun active = findActive(taskId).orElse(null);
            if (active != null && active.status() != ScheduledRunStatus.QUEUED) {
                throw new ActiveOccurrenceConflict(taskId, active.status().wire());
            }
            ScheduledTask paused = withStatus(task, ScheduledTaskStatus.PAUSED, now);
            mutate(() -> {
                if (active != null) {
                    runs.put(active.id(), withRunStatus(active, ScheduledRunStatus.SKIPPED, now));
                }
                tasks.put(taskId, paused);
            });
            return true;
        }
    }

    /**
     * Completion/expiry bookkeeping for the parent task: writes the task/occurrence terminal status,
     * the recomputed {@code next_run_at} for a recurring plan, the last-run association and the
     * error. Terminal-protected when {@code protectTerminal} is set (a late write never overwrites a
     * task that is already terminal). {@code nextRunAt} null keeps the existing value.
     */
    public boolean finishTask(String taskId, ScheduledTaskStatus status, Instant nextRunAt,
            Instant lastRunAt, String lastRunId, String lastThreadId, String lastError,
            boolean protectTerminal, Instant now) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(now, "now");
        synchronized (lock) {
            ScheduledTask task = tasks.get(taskId);
            if (task == null) {
                return false;
            }
            ScheduledTask updated = taskFinish(task, status, nextRunAt, lastRunAt, lastRunId, lastThreadId,
                    lastError, protectTerminal, now);
            mutate(() -> tasks.put(taskId, updated));
            return true;
        }
    }

    private static ScheduledTask taskFinish(ScheduledTask task, ScheduledTaskStatus status,
            Instant nextRunAt, Instant lastRunAt, String lastRunId, String lastThreadId, String lastError,
            boolean protectTerminal, Instant now) {
        boolean protectedTerminal = protectTerminal && task.status().terminal();
        return new ScheduledTask(task.id(), task.userId(), task.threadId(),
                task.contextMode(), task.assistantId(), task.title(), task.prompt(),
                task.scheduleType(), task.scheduleSpec(), task.timezone(),
                protectedTerminal ? task.status() : status,
                nextRunAt == null ? task.nextRunAt() : nextRunAt,
                lastRunAt == null ? task.lastRunAt() : lastRunAt,
                lastRunId == null ? task.lastRunId() : lastRunId,
                lastThreadId == null ? task.lastThreadId() : lastThreadId,
                lastError, task.runCount(), task.lastOccurrenceSeq(), task.createdAt(), now);
    }

    private static ScheduledTask withStatus(ScheduledTask task, ScheduledTaskStatus status, Instant now) {
        return new ScheduledTask(task.id(), task.userId(), task.threadId(), task.contextMode(),
                task.assistantId(), task.title(), task.prompt(), task.scheduleType(), task.scheduleSpec(),
                task.timezone(), status, task.nextRunAt(), task.lastRunAt(), task.lastRunId(),
                task.lastThreadId(), task.lastError(), task.runCount(), task.lastOccurrenceSeq(),
                task.createdAt(), now);
    }

    private static ScheduledTaskRun withRunStatus(ScheduledTaskRun run, ScheduledRunStatus status, Instant now) {
        return new ScheduledTaskRun(run.id(), run.taskId(), run.occurrenceSeq(), run.threadId(),
                run.runId(), run.scheduledFor(), run.trigger(), status, run.error(), run.startedAt(),
                now, run.attemptCount(), run.launchAccounted(), run.createdAt());
    }

    // ---------------------------------------------------------------- storage

    private Optional<ScheduledTaskRun> findActive(String taskId) {
        return runs.values().stream()
                .filter(run -> run.taskId().equals(taskId) && run.status().active())
                .min(Comparator.comparing(ScheduledTaskRun::createdAt)
                        .thenComparing(ScheduledTaskRun::id));
    }

    /**
     * Bounds the per-task occurrence history. Only terminal occurrences are eligible, newest first,
     * so the single active occurrence and every recent record are always retained. Called inside the
     * {@link #createRun} and terminal {@link #updateRunStatus} mutations, before the document can
     * approach the size bound.
     */
    private void pruneHistory(String taskId) {
        List<ScheduledTaskRun> terminal = runs.values().stream()
                .filter(run -> run.taskId().equals(taskId) && run.status().terminal())
                .sorted(Comparator.comparing(ScheduledTaskRun::createdAt).reversed()
                        .thenComparing(ScheduledTaskRun::id, Comparator.reverseOrder()))
                .toList();
        if (terminal.size() <= MAXIMUM_HISTORY_PER_TASK) {
            return;
        }
        for (ScheduledTaskRun run : terminal.subList(MAXIMUM_HISTORY_PER_TASK, terminal.size())) {
            runs.remove(run.id());
        }
    }

    private ScheduledTask applyUpdates(ScheduledTask current, Map<String, Object> updates, Instant now) {
        String contextMode = current.contextMode();
        String threadId = current.threadId();
        String assistantId = current.assistantId();
        String title = current.title();
        String prompt = current.prompt();
        Map<String, Object> scheduleSpec = current.scheduleSpec();
        String timezone = current.timezone();
        ScheduledTaskStatus status = current.status();
        Instant nextRunAt = current.nextRunAt();
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            switch (entry.getKey()) {
                case "context_mode" -> contextMode = requireText(entry.getValue(), "context_mode");
                case "thread_id" -> threadId = optionalText(entry.getValue());
                case "assistant_id" -> assistantId = optionalText(entry.getValue());
                case "title" -> title = requireText(entry.getValue(), "title");
                case "prompt" -> prompt = requireText(entry.getValue(), "prompt");
                case "schedule_spec" -> scheduleSpec = asMap(entry.getValue());
                case "timezone" -> timezone = requireText(entry.getValue(), "timezone");
                case "status" -> status = ScheduledTaskStatus.fromWire(requireText(entry.getValue(), "status"));
                case "next_run_at" -> nextRunAt = asInstant(entry.getValue());
                default -> throw new ScheduleValidationException("Unsupported task field: " + entry.getKey());
            }
        }
        return new ScheduledTask(current.id(), current.userId(), threadId, contextMode, assistantId,
                title, prompt, current.scheduleType(), scheduleSpec, timezone, status, nextRunAt,
                current.lastRunAt(), current.lastRunId(), current.lastThreadId(), current.lastError(),
                current.runCount(), current.lastOccurrenceSeq(), current.createdAt(), now);
    }

    private static Comparator<ScheduledTask> byRecency() {
        return Comparator.comparing(ScheduledTask::createdAt).reversed()
                .thenComparing(ScheduledTask::id, Comparator.reverseOrder());
    }

    private void load() throws IOException {
        tasks.clear();
        runs.clear();
        if (!Files.exists(file)) {
            return;
        }
        if (Files.size(file) > MAXIMUM_BYTES) {
            throw new IOException("scheduler state exceeds the configured size bound");
        }
        JsonNode root = json.readTree(file.toFile());
        if (root == null || root.path("version").asInt(-1) != FORMAT_VERSION
                || !root.path("tasks").isArray() || !root.path("runs").isArray()) {
            throw new IOException("scheduler state has an unsupported format");
        }
        for (JsonNode node : root.path("tasks")) {
            ScheduledTask task = readTask(node);
            if (tasks.putIfAbsent(task.id(), task) != null) {
                throw new IOException("scheduler state contains a duplicate task id");
            }
        }
        for (JsonNode node : root.path("runs")) {
            ScheduledTaskRun run = readRun(node);
            if (runs.putIfAbsent(run.id(), run) != null) {
                throw new IOException("scheduler state contains a duplicate occurrence id");
            }
        }
    }

    /** Package-private seam so tests can inject a bounded persistence failure. */
    protected void persist() throws IOException {
        ObjectNode root = json.createObjectNode();
        root.put("version", FORMAT_VERSION);
        ArrayNode taskNodes = root.putArray("tasks");
        for (ScheduledTask task : tasks.values()) {
            taskNodes.add(writeTask(task));
        }
        ArrayNode runNodes = root.putArray("runs");
        for (ScheduledTaskRun run : runs.values()) {
            runNodes.add(writeRun(run));
        }
        byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        if (bytes.length > MAXIMUM_BYTES) {
            throw new IOException("scheduler state exceeds the configured size bound");
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.write(temporary, bytes);
            AtomicMoves.replace(temporary, file);
        } finally {
            AtomicMoves.deleteQuietly(temporary);
        }
    }

    private void mutate(Runnable mutation) {
        Map<String, ScheduledTask> tasksBackup = new LinkedHashMap<>(tasks);
        Map<String, ScheduledTaskRun> runsBackup = new LinkedHashMap<>(runs);
        try {
            mutation.run();
            persist();
        } catch (IOException failure) {
            restore(tasksBackup, runsBackup);
            throw new IllegalStateException("scheduler state is unavailable", failure);
        } catch (RuntimeException | Error failure) {
            restore(tasksBackup, runsBackup);
            throw failure;
        }
    }

    private void restore(Map<String, ScheduledTask> tasksBackup, Map<String, ScheduledTaskRun> runsBackup) {
        tasks.clear();
        tasks.putAll(tasksBackup);
        runs.clear();
        runs.putAll(runsBackup);
    }

    private ObjectNode writeTask(ScheduledTask task) {
        ObjectNode node = json.createObjectNode();
        node.put("id", task.id());
        node.put("user_id", task.userId());
        putNullable(node, "thread_id", task.threadId());
        node.put("context_mode", task.contextMode());
        putNullable(node, "assistant_id", task.assistantId());
        node.put("title", task.title());
        node.put("prompt", task.prompt());
        node.put("schedule_type", task.scheduleType());
        node.set("schedule_spec", json.valueToTree(task.scheduleSpec()));
        node.put("timezone", task.timezone());
        node.put("status", task.status().wire());
        putNullable(node, "next_run_at", iso(task.nextRunAt()));
        putNullable(node, "last_run_at", iso(task.lastRunAt()));
        putNullable(node, "last_run_id", task.lastRunId());
        putNullable(node, "last_thread_id", task.lastThreadId());
        putNullable(node, "last_error", task.lastError());
        node.put("run_count", task.runCount());
        node.put("last_occurrence_seq", task.lastOccurrenceSeq());
        node.put("created_at", task.createdAt().toString());
        node.put("updated_at", task.updatedAt().toString());
        return node;
    }

    private ObjectNode writeRun(ScheduledTaskRun run) {
        ObjectNode node = json.createObjectNode();
        node.put("id", run.id());
        node.put("task_id", run.taskId());
        if (run.occurrenceSeq() == null) {
            node.putNull("occurrence_seq");
        } else {
            node.put("occurrence_seq", run.occurrenceSeq());
        }
        node.put("thread_id", run.threadId());
        putNullable(node, "run_id", run.runId());
        node.put("scheduled_for", run.scheduledFor().toString());
        node.put("trigger", run.trigger().wire());
        node.put("status", run.status().wire());
        putNullable(node, "error", run.error());
        putNullable(node, "started_at", iso(run.startedAt()));
        putNullable(node, "finished_at", iso(run.finishedAt()));
        node.put("attempt_count", run.attemptCount());
        if (run.launchAccounted() == null) {
            node.putNull("launch_accounted");
        } else {
            node.put("launch_accounted", run.launchAccounted());
        }
        node.put("created_at", run.createdAt().toString());
        return node;
    }

    private ScheduledTask readTask(JsonNode node) throws IOException {
        return new ScheduledTask(
                text(node, "id"),
                text(node, "user_id"),
                optionalText(node, "thread_id"),
                text(node, "context_mode"),
                optionalText(node, "assistant_id"),
                text(node, "title"),
                text(node, "prompt"),
                text(node, "schedule_type"),
                asMap(json.convertValue(node.path("schedule_spec"), Map.class)),
                text(node, "timezone"),
                ScheduledTaskStatus.fromWire(text(node, "status")),
                instant(node, "next_run_at"),
                instant(node, "last_run_at"),
                optionalText(node, "last_run_id"),
                optionalText(node, "last_thread_id"),
                optionalText(node, "last_error"),
                node.path("run_count").asInt(0),
                node.path("last_occurrence_seq").asLong(0),
                instant(node, "created_at"),
                instant(node, "updated_at"));
    }

    private ScheduledTaskRun readRun(JsonNode node) throws IOException {
        JsonNode sequence = node.get("occurrence_seq");
        JsonNode accounted = node.get("launch_accounted");
        return new ScheduledTaskRun(
                text(node, "id"),
                text(node, "task_id"),
                sequence == null || sequence.isNull() ? null : sequence.asLong(),
                text(node, "thread_id"),
                optionalText(node, "run_id"),
                instant(node, "scheduled_for"),
                ScheduledRunTrigger.fromWire(text(node, "trigger")),
                ScheduledRunStatus.fromWire(text(node, "status")),
                optionalText(node, "error"),
                instant(node, "started_at"),
                instant(node, "finished_at"),
                node.path("attempt_count").asInt(0),
                accounted == null || accounted.isNull() ? null : accounted.asBoolean(),
                instant(node, "created_at"));
    }

    private String text(JsonNode node, String field) throws IOException {
        String value = optionalText(node, field);
        if (value == null) {
            throw new IOException("scheduler state field is missing: " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isEmpty() ? null : text;
    }

    private static Instant instant(JsonNode node, String field) throws IOException {
        String value = optionalText(node, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException failure) {
            throw new IOException("scheduler state timestamp is invalid: " + field, failure);
        }
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static String hex() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String requireText(Object value, String field) {
        String text = optionalText(value);
        if (text == null) {
            throw new ScheduleValidationException(field + " must not be blank");
        }
        return text;
    }

    private static String optionalText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new ScheduleValidationException("schedule_spec must be an object");
        }
        return new LinkedHashMap<>((Map<String, Object>) map);
    }

    private static Instant asInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (java.time.format.DateTimeParseException failure) {
            throw new ScheduleValidationException("next_run_at must be an ISO instant");
        }
    }

    /** A new task definition; {@code taskId} may be null to allocate one. */
    public record NewTask(String taskId, String ownerId, String threadId, String contextMode,
            String assistantId, String title, String prompt, String scheduleType,
            Map<String, Object> scheduleSpec, String timezone, Instant nextRunAt) {
        public NewTask {
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(prompt, "prompt");
            Objects.requireNonNull(scheduleType, "scheduleType");
            Objects.requireNonNull(timezone, "timezone");
        }
    }

    /** A new occurrence; {@code runRecordId} may be null to allocate one. */
    public record NewRun(String runRecordId, String taskId, String ownerId, String threadId,
            Instant scheduledFor, ScheduledRunTrigger trigger, ScheduledRunStatus status) {
        public NewRun {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(scheduledFor, "scheduledFor");
            Objects.requireNonNull(trigger, "trigger");
            Objects.requireNonNull(status, "status");
        }
    }

    /** Parent-task bookkeeping after a launch; mirrors the frozen {@code update_after_launch}. */
    public record TaskLaunchUpdate(String status, Instant nextRunAt, Instant lastRunAt, String lastRunId,
            String lastThreadId, String lastError, boolean incrementRunCount, boolean protectTerminal,
            String taskRunId) {
    }

    /** Parent-task completion bookkeeping published together with a terminal occurrence. */
    public record TaskFinish(String taskId, ScheduledTaskStatus status, Instant nextRunAt, Instant lastRunAt,
            String lastRunId, String lastThreadId, String lastError, boolean protectTerminal) {
    }

    /** A concurrent admission already holds the task's single active-occurrence slot. */
    public static final class ActiveOccurrenceConflict extends RuntimeException {
        private final String taskId;
        private final String activeStatus;

        ActiveOccurrenceConflict(String taskId, String activeStatus) {
            super("scheduled task " + taskId + " already has an active " + activeStatus + " occurrence");
            this.taskId = taskId;
            this.activeStatus = activeStatus;
        }

        public String taskId() {
            return taskId;
        }

        public String activeStatus() {
            return activeStatus;
        }
    }
}

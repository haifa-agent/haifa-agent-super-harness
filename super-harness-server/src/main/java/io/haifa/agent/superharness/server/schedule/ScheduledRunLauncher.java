package io.haifa.agent.superharness.server.schedule;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Narrow in-process seam the dispatch worker uses to start and observe a scheduled Run without a
 * public HTTP background-Run route and without a second Run engine.
 *
 * <p>All execution truth stays in the Haifa Runtime; the implementation is the product API layer
 * that owns the Thread/session binding and the public SDK bridge. The worker only records the
 * returned identities and consumes a bounded terminal observation.
 */
public interface ScheduledRunLauncher {
    /**
     * Start one background Run. {@code createThreadIfMissing} distinguishes
     * {@code fresh_thread_per_run} (create the Thread on demand) from {@code reuse_thread}
     * (the bound Thread must already exist and belong to the owner).
     */
    LaunchedRun launch(LaunchRequest request);

    /**
     * Starts the bounded follower for a successfully started Run. The callback runs at most once,
     * on a follower thread, when the Run reaches a terminal SDK status; it is never called for a Run
     * that stays pending (for example {@code WAITING_APPROVAL}).
     */
    void watch(String runId, Consumer<ScheduledRunOutcome> onTerminal);

    /**
     * Read-only inspection of an already submitted Run for startup recovery. Empty when the Run
     * cannot be located for the owner; a present, non-terminal inspection means the Run still owns
     * its active slot and must not be converged by recovery.
     */
    Optional<ScheduledRunInspection> inspect(String ownerId, String threadId, String runId);

    record LaunchRequest(
            String ownerId, String threadId, String message, String assistantId, boolean createThreadIfMissing) {
        public LaunchRequest {
            java.util.Objects.requireNonNull(ownerId, "ownerId");
            java.util.Objects.requireNonNull(threadId, "threadId");
            java.util.Objects.requireNonNull(message, "message");
        }
    }

    record LaunchedRun(String threadId, String runId) {
        public LaunchedRun {
            java.util.Objects.requireNonNull(threadId, "threadId");
            java.util.Objects.requireNonNull(runId, "runId");
        }
    }

    /** A terminal occurrence projection: {@code success}, {@code failed} or {@code interrupted}. */
    record ScheduledRunOutcome(String runId, ScheduledRunStatus terminal, String error) {
        public ScheduledRunOutcome {
            java.util.Objects.requireNonNull(runId, "runId");
            java.util.Objects.requireNonNull(terminal, "terminal");
            if (!terminal.terminal()) {
                throw new IllegalArgumentException("outcome status must be terminal");
            }
        }
    }

    record ScheduledRunInspection(boolean terminal, ScheduledRunStatus terminalStatus, String error) {}
}

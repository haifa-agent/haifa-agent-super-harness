package io.haifa.agent.superharness.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local, Run-keyed marker for the frozen {@code ask_clarification} stop boundary.
 *
 * <p>Frozen DeerFlow drops same-turn sibling tool calls in {@code ClarificationMiddleware.after_model}
 * before the tools node runs them. SH cannot intercept the model output, so a real {@code JavaTool}
 * records the boundary when it executes and the side-effecting SH tools consult it before performing
 * any work. The SDK Run id is globally unique, so a single bounded map is sufficient; entries are
 * dropped once the Run is known terminal. This is best-effort correctness for the shared SH workspace:
 * a sibling that the Runtime starts before {@code ask_clarification} cannot be recalled (registered as
 * a known difference), while every sibling started after it is refused and left without side effects.
 */
final class ClarificationBoundaryRegistry {
    /** Frozen-style explanation returned instead of executing a dropped sibling SH tool. */
    static final String DISCARD_MESSAGE =
            "Discarded: this turn already requested clarification, so sibling tool calls are not "
                    + "executed until the user answers. Wait for the clarification response, then call "
                    + "the tool again in the next turn if the action is still required.";

    private static final Map<String, String> BOUNDARIES = new ConcurrentHashMap<>();

    private ClarificationBoundaryRegistry() {}

    /** Records that {@code runId} reached its clarification boundary at {@code toolCallId}. */
    static void mark(String runId, String toolCallId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        BOUNDARIES.put(runId, toolCallId == null ? "" : toolCallId);
    }

    /** Whether this Run has already requested clarification. */
    static boolean active(String runId) {
        return runId != null && BOUNDARIES.containsKey(runId);
    }

    /** Releases the marker after the Run is terminal. */
    static void clear(String runId) {
        if (runId != null) {
            BOUNDARIES.remove(runId);
        }
    }

    static void resetForTests() {
        BOUNDARIES.clear();
    }
}

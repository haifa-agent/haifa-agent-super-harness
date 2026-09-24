package io.haifa.agent.superharness.workspace;

/** Diff statistics retained even when the rendered fragment is truncated. */
public record WorkspaceLineCounts(int additions, int deletions) {
    public WorkspaceLineCounts {
        if (additions < 0 || deletions < 0) {
            throw new IllegalArgumentException("workspace line counts must not be negative");
        }
    }
}

package io.haifa.agent.superharness.workspace;

public record WorkspaceChangeLimits(int maxFiles, int maxFileBytes, int maxOutputBytes) {
    public WorkspaceChangeLimits {
        if (maxFiles < 1 || maxFiles > 1000 || maxFileBytes < 1 || maxFileBytes > 16 * 1024 * 1024
                || maxOutputBytes < 1 || maxOutputBytes > 32 * 1024 * 1024) {
            throw new IllegalArgumentException("workspace change limits are outside the public DiffRequest bounds");
        }
    }

    public static WorkspaceChangeLimits defaults() {
        return new WorkspaceChangeLimits(500, 1024 * 1024, 4 * 1024 * 1024);
    }
}

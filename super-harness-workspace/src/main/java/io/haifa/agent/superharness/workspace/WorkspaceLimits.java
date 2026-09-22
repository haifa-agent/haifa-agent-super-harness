package io.haifa.agent.superharness.workspace;

public record WorkspaceLimits(long maxUploadBytes, long maxWorkingFileBytes, int maxReadBytes) {
    public WorkspaceLimits {
        if (maxUploadBytes < 1 || maxWorkingFileBytes < 1 || maxReadBytes < 1) {
            throw new IllegalArgumentException("workspace limits must be positive");
        }
        if (maxUploadBytes > Integer.MAX_VALUE || maxWorkingFileBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("byte-array-backed workspace limits cannot exceed Integer.MAX_VALUE");
        }
    }

    public static WorkspaceLimits defaults() {
        return new WorkspaceLimits(25L * 1024 * 1024, 50L * 1024 * 1024, 1024 * 1024);
    }
}

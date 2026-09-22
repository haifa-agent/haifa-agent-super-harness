package io.haifa.agent.superharness.workspace;

public record WorkspaceFileSnapshot(String logicalPath, byte[] bytes, String sha256, long totalByteCount,
                                    long offset, boolean truncated) {
    public WorkspaceFileSnapshot {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}

package io.haifa.agent.superharness.workspace;

public final class WorkspaceException extends RuntimeException {
    private final WorkspaceError error;

    public WorkspaceException(WorkspaceError error, String message) {
        super(message);
        this.error = error;
    }

    public WorkspaceException(WorkspaceError error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    public WorkspaceError error() {
        return error;
    }
}

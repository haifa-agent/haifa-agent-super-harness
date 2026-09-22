package io.haifa.agent.superharness.workspace;

public enum WorkspaceError {
    INVALID_IDENTITY,
    WORKSPACE_NOT_FOUND,
    OWNER_MISMATCH,
    INVALID_PATH,
    PATH_ESCAPE,
    IMMUTABLE_UPLOAD,
    FILE_NOT_FOUND,
    FILE_TOO_LARGE,
    READ_OUT_OF_BOUNDS,
    HASH_CONFLICT,
    IO_FAILURE
}

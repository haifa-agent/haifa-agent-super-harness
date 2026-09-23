package io.haifa.agent.superharness.workspace;

/** Opaque pre-run baseline handle. Only the workspace service can create or inspect it. */
public final class StagedRunChanges {
    final String token;

    StagedRunChanges(String token) {
        this.token = token;
    }
}

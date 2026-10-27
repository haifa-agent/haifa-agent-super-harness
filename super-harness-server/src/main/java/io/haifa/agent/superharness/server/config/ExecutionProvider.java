package io.haifa.agent.superharness.server.config;

/**
 * Execution platform provider selected for Super Harness process execution.
 *
 * <p>Super Harness supports host-guarded execution (via {@code haifa-agent-sandbox-host}) and
 * Linux container execution (via {@code haifa-agent-sandbox-docker}).
 */
public enum ExecutionProvider {
    /** Execution is disabled; no execution tool is registered. */
    DISABLED,

    /** Host guarded execution with bounded paths, timeout, and output limits. */
    HOST_GUARDED,

    /** Linux Docker AIO one-shot container execution. */
    DOCKER_AIO;

    public boolean isEnabled() {
        return this != DISABLED;
    }
}

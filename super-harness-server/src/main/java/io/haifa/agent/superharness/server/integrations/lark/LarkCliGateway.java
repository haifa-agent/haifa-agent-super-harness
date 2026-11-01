package io.haifa.agent.superharness.server.integrations.lark;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Gateway-side operational adapter for the official {@code lark-cli} binary.
 *
 * <p>This is <strong>not</strong> an execution runtime. It performs trusted install/probe/config
 * and OAuth device-flow subprocess I/O with structured argv, bounded timeouts and bounded output.
 * Model-visible command execution remains the SDK {@code execution_run} platform; nothing here
 * decides an Agent's next step, approval or tool lifecycle.
 *
 * <p>Implementations must never interpolate arguments into a shell string and must never log
 * tokens, app secrets or raw credential files.
 */
public interface LarkCliGateway {
    /** Result of resolving and probing the CLI. */
    record Probe(boolean available, String path, String version, String error) {}

    /** Bounded subprocess result. */
    record ExecResult(int exitCode, String stdout, String stderr) {}

    /** Resolve the managed CLI first, then {@code PATH}; never downloads. */
    Probe probe();

    /**
     * Install or update the DeerFlow-managed Gateway CLI to {@code requestedVersion} using npm.
     *
     * @throws IOException when npm is unavailable or the install produced no runnable binary
     */
    Probe ensureManaged(String requestedVersion) throws IOException;

    /**
     * Run one structured argv invocation.
     *
     * @param environment complete child environment (callers pass the narrow product allowlist)
     * @param timeout bounded wall clock; expiry raises {@link LarkTimeoutException}
     * @param stdin UTF-8 standard input, or {@code null} to close it immediately
     */
    ExecResult run(List<String> argv, Map<String, String> environment, java.time.Duration timeout, String stdin)
            throws IOException;

    /** Managed binary path directory, used to prepend the child {@code PATH}. */
    Optional<String> managedBinDirectory();

    /** Safe inherited runtime paths plus the caller's owner-specific overlay. */
    default Map<String, String> childEnvironment(Map<String, String> overlay) {
        return Map.copyOf(overlay);
    }
}

package io.haifa.agent.superharness.server.integrations.lark;

/**
 * The Gateway cannot provide the official CLI (missing npm/binaries) or a flow needs it and it is
 * absent. Mirrors frozen DeerFlow {@code FileNotFoundError} mapping to {@code 404}.
 */
public final class LarkCliUnavailableException extends RuntimeException {
    public LarkCliUnavailableException(String message) {
        super(message);
    }

    public LarkCliUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

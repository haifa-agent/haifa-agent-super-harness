package io.haifa.agent.superharness.server.integrations.lark;

/**
 * A bounded Lark registration or authorization wait elapsed.
 *
 * <p>Mirrors frozen DeerFlow {@code TimeoutError} handling: the HTTP projection maps it to
 * {@code 504}. Callers must not retry internally beyond their declared deadline.
 */
public final class LarkTimeoutException extends RuntimeException {
    public LarkTimeoutException(String message) {
        super(message);
    }

    public LarkTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}

package io.haifa.agent.superharness.server.integrations.lark;

/**
 * An upstream Lark/GitHub dependency could not be reached or answered unusably.
 *
 * <p>This is deliberately not an {@link IllegalArgumentException}: a Gateway without internet
 * access, a stalled GitHub archive download or a Lark accounts outage is not client input error,
 * and reporting it as {@code 400} sent operators looking for a bad request that did not exist.
 * The HTTP projection maps it to {@code 502}.
 */
public final class LarkUpstreamUnavailableException extends RuntimeException {
    public LarkUpstreamUnavailableException(String message) {
        super(message);
    }

    public LarkUpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

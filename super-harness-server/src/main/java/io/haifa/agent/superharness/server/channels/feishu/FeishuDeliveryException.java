package io.haifa.agent.superharness.server.channels.feishu;

/**
 * Value-free failure of a Feishu outbound API call. Carries only classification, provider code,
 * provider log id, message type, and card version so diagnostics never include message or form
 * content, secrets, or credentials.
 */
public final class FeishuDeliveryException extends RuntimeException {

    /** Distinguishes the failure classes the delivery path must handle differently. */
    public enum Failure {
        /** Invalid payload or card schema; retrying the same content cannot help. */
        INVALID,
        /** Missing app/tenant permission; requires operator action, not a retry. */
        PERMISSION,
        /** Provider rate limit; a bounded, duplicate-safe retry may succeed. */
        RATE_LIMIT,
        /** Transient transport or provider 5xx; a bounded retry may succeed. */
        TRANSPORT,
        /** Anything else, including a success status without a provider message id. */
        UNKNOWN
    }

    private final Failure failure;
    private final int code;
    private final String logId;
    private final String messageType;
    private final String cardVersion;

    public FeishuDeliveryException(
            Failure failure,
            int code,
            String logId,
            String messageType,
            String cardVersion,
            String detail) {
        super("Feishu delivery failed (failure=" + failure + ", code=" + code + ", messageType=" + messageType
                + ", cardVersion=" + cardVersion + ")"
                + (detail == null || detail.isBlank() ? "" : ": " + detail));
        this.failure = failure == null ? Failure.UNKNOWN : failure;
        this.code = code;
        this.logId = logId == null ? "" : logId;
        this.messageType = messageType == null ? "" : messageType;
        this.cardVersion = cardVersion == null ? "" : cardVersion;
    }

    public Failure failure() {
        return failure;
    }

    public int code() {
        return code;
    }

    public String logId() {
        return logId;
    }

    public String messageType() {
        return messageType;
    }

    public String cardVersion() {
        return cardVersion;
    }

    /** Only rate limiting and transient transport failures are safe to retry within a bound. */
    public boolean retryable() {
        return failure == Failure.RATE_LIMIT || failure == Failure.TRANSPORT;
    }

    /** Unwraps the first {@link FeishuDeliveryException} in a cause chain, or {@code null}. */
    public static FeishuDeliveryException find(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof FeishuDeliveryException delivery) {
                return delivery;
            }
        }
        return null;
    }
}

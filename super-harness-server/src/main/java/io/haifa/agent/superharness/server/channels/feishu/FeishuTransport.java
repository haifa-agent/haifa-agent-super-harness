package io.haifa.agent.superharness.server.channels.feishu;

import java.util.Map;

/**
 * Transport seam for Feishu/Lark long-connection (or a test double). No public webhook path.
 */
public interface FeishuTransport extends AutoCloseable {

    @FunctionalInterface
    interface Factory {
        FeishuTransport create();
    }

    @FunctionalInterface
    interface InboundHandler {
        void onMessage(FeishuInboundMessage message);
    }

    /** Returns the toast text. Must not wait for the model continuation. */
    @FunctionalInterface
    interface CardActionHandler {
        String toastFor(FeishuInboundMessage message);
    }

    /**
     * Start receiving {@code im.message.receive_v1} events. Must return promptly; long-lived WS
     * work belongs on a dedicated thread (DeerFlow parity).
     */
    void start(String appId, String appSecret, String domain, InboundHandler handler) throws Exception;

    /** Installed before {@link #start} so a card callback can answer without waiting for a Run. */
    default void bindCardActions(CardActionHandler handler) {}

    /** Best-effort stop; may abandon a blocked WS thread after a short join (DeerFlow parity). */
    void stop();

    boolean running();

    /** Reply with a simple interactive card (final assistant text or bind status). */
    void replyCard(String messageId, String text) throws Exception;

    /** Reply with a card 1.0 payload that may include an {@code action} button row. */
    void replyInteractive(String messageId, Map<String, Object> card) throws Exception;

    /**
     * Download an inbound image/file resource. {@code resourceType} is the Feishu resource type
     * ({@code image} or {@code file}). Test doubles may return fixture bytes.
     */
    default byte[] downloadResource(String messageId, String resourceKey, String resourceType)
            throws Exception {
        throw new UnsupportedOperationException("Feishu media download is not available on this transport");
    }

    @Override
    default void close() {
        stop();
    }
}

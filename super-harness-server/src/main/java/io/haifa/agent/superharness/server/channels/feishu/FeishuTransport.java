package io.haifa.agent.superharness.server.channels.feishu;

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

    /**
     * Start receiving {@code im.message.receive_v1} events. Must return promptly; long-lived WS
     * work belongs on a dedicated thread (DeerFlow parity).
     */
    void start(String appId, String appSecret, String domain, InboundHandler handler) throws Exception;

    /** Best-effort stop; may abandon a blocked WS thread after a short join (DeerFlow parity). */
    void stop();

    boolean running();

    /** Reply with a simple interactive card (final assistant text or bind status). */
    void replyCard(String messageId, String text) throws Exception;

    @Override
    default void close() {
        stop();
    }
}

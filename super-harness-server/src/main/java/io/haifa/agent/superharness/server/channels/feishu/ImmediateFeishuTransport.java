package io.haifa.agent.superharness.server.channels.feishu;

import java.util.Map;

/**
 * No-network transport used by {@code createForTests} so runtime-config POST does not open a real
 * Feishu WebSocket. Production uses {@link LarkSdkFeishuTransport}.
 */
public final class ImmediateFeishuTransport implements FeishuTransport {
    private volatile boolean running;
    private volatile InboundHandler handler;
    private volatile String domain;

    @Override
    public synchronized void start(String appId, String appSecret, String domain, InboundHandler handler) {
        stop();
        this.handler = handler;
        this.domain = FeishuOpenDomain.normalizeOrThrow(domain);
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new IllegalArgumentException("Feishu app_id and app_secret are required");
        }
        this.running = true;
    }

    @Override
    public synchronized void stop() {
        running = false;
        handler = null;
    }

    @Override
    public boolean running() {
        return running;
    }

    @Override
    public void replyCard(String messageId, String text) {
        // No-op: unit fixtures that need outbound assertions use RecordingFeishuTransport.
    }

    @Override
    public void replyInteractive(String messageId, Map<String, Object> card) {
        // No-op, same as replyCard.
    }

    public String domain() {
        return domain;
    }

    /** Test helper: deliver an inbound event to the active handler. */
    public void deliver(FeishuInboundMessage message) {
        InboundHandler active = handler;
        if (!running || active == null) {
            throw new IllegalStateException("Feishu transport is not running");
        }
        active.onMessage(message);
    }
}

package io.haifa.agent.superharness.server.channels.feishu;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test double that records outbound replies and accepts injected inbound events. */
public final class RecordingFeishuTransport implements FeishuTransport {
    public record CardReply(String messageId, String text) {}

    private final CopyOnWriteArrayList<CardReply> replies = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private volatile InboundHandler handler;
    private volatile String domain;
    private volatile String appId;

    @Override
    public synchronized void start(String appId, String appSecret, String domain, InboundHandler handler) {
        stop();
        this.appId = appId;
        this.domain = FeishuOpenDomain.normalizeOrThrow(domain);
        this.handler = handler;
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
        replies.add(new CardReply(messageId, text));
    }

    public List<CardReply> replies() {
        return new ArrayList<>(replies);
    }

    public String domain() {
        return domain;
    }

    public String appId() {
        return appId;
    }

    public void deliver(FeishuInboundMessage message) {
        InboundHandler active = handler;
        if (!running || active == null) {
            throw new IllegalStateException("Feishu transport is not running");
        }
        active.onMessage(message);
    }

    /** Deliver and wait briefly for the worker's inbound executor. */
    public void deliverAndWait(FeishuInboundMessage message, long waitMillis) throws InterruptedException {
        deliver(message);
        Thread.sleep(waitMillis);
    }
}

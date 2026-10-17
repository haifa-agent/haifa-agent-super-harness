package io.haifa.agent.superharness.server.channels.feishu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test double that records outbound replies and accepts injected inbound events. */
public final class RecordingFeishuTransport implements FeishuTransport {
    public record CardReply(String messageId, String text) {}

    public record InteractiveReply(String messageId, Map<String, Object> card) {}

    private final CopyOnWriteArrayList<CardReply> replies = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<InteractiveReply> interactiveReplies = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, byte[]> resources = new ConcurrentHashMap<>();
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

    @Override
    public void replyInteractive(String messageId, Map<String, Object> card) {
        interactiveReplies.add(new InteractiveReply(messageId, card));
    }

    /** Fixture bytes for {@link #downloadResource}; keyed by {@code messageId + ":" + resourceKey}. */
    public void putResource(String messageId, String resourceKey, byte[] bytes) {
        resources.put(messageId + ":" + resourceKey, bytes == null ? new byte[0] : bytes.clone());
    }

    @Override
    public byte[] downloadResource(String messageId, String resourceKey, String resourceType) {
        byte[] bytes = resources.get(messageId + ":" + resourceKey);
        if (bytes == null) {
            return new byte[0];
        }
        return bytes.clone();
    }

    public List<CardReply> replies() {
        return new ArrayList<>(replies);
    }

    public List<InteractiveReply> interactiveReplies() {
        return new ArrayList<>(interactiveReplies);
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

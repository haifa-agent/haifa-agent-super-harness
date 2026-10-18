package io.haifa.agent.superharness.server.channels.feishu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test double that records outbound replies and accepts injected inbound events. */
public final class RecordingFeishuTransport implements FeishuTransport {
    public record CardReply(String messageId, String text) {}

    public record TextReply(String messageId, String text) {}

    public record InteractiveReply(String messageId, Map<String, Object> card) {}

    public record ReactionAdd(String messageId, String emojiType, String reactionId) {}

    public record ReactionDelete(String messageId, String reactionId, boolean success) {}

    private final CopyOnWriteArrayList<CardReply> replies = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<TextReply> textReplies = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<InteractiveReply> interactiveReplies = new CopyOnWriteArrayList<>();
    private final AtomicBoolean failInteractive = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<ReactionAdd> reactionAdds = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ReactionDelete> reactionDeletes = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, byte[]> resources = new ConcurrentHashMap<>();
    private final AtomicBoolean failNextDelete = new AtomicBoolean(false);
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
        if (failInteractive.get()) {
            throw new FeishuDeliveryException(
                    FeishuDeliveryException.Failure.INVALID, 200830, "log-x", "interactive", "2.0", "simulated");
        }
        replies.add(new CardReply(messageId, text));
    }

    @Override
    public void replyInteractive(String messageId, Map<String, Object> card) {
        if (failInteractive.get()) {
            throw new FeishuDeliveryException(
                    FeishuDeliveryException.Failure.INVALID, 200830, "log-x", "interactive", "2.0", "simulated");
        }
        interactiveReplies.add(new InteractiveReply(messageId, card));
    }

    @Override
    public Optional<String> replyCardId(String messageId, String text) {
        replyCard(messageId, text);
        return Optional.of("reply-card-" + replies.size());
    }

    @Override
    public Optional<String> replyInteractiveId(String messageId, Map<String, Object> card) {
        replyInteractive(messageId, card);
        return Optional.of("reply-interactive-" + interactiveReplies.size());
    }

    @Override
    public void replyText(String messageId, String text) {
        textReplies.add(new TextReply(messageId, text));
    }

    @Override
    public Optional<String> replyTextId(String messageId, String text) {
        replyText(messageId, text);
        return Optional.of("reply-text-" + textReplies.size());
    }

    /** Makes the next and subsequent {@link #replyInteractive} calls fail (delivery-fallback test). */
    public void failInteractiveDelivery() {
        failInteractive.set(true);
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

    @Override
    public Optional<String> addReaction(String messageId, String emojiType) {
        if (messageId == null || messageId.isBlank() || emojiType == null || emojiType.isBlank()) {
            return Optional.empty();
        }
        String reactionId = "react_" + UUID.randomUUID();
        reactionAdds.add(new ReactionAdd(messageId, emojiType, reactionId));
        return Optional.of(reactionId);
    }

    @Override
    public boolean deleteReaction(String messageId, String reactionId) {
        boolean success = !failNextDelete.getAndSet(false);
        reactionDeletes.add(new ReactionDelete(messageId, reactionId, success));
        return success;
    }

    /** Next {@link #deleteReaction} returns false once (Hermes delete-failure safety). */
    public void failNextDelete() {
        failNextDelete.set(true);
    }

    public List<CardReply> replies() {
        return new ArrayList<>(replies);
    }

    public List<InteractiveReply> interactiveReplies() {
        return new ArrayList<>(interactiveReplies);
    }

    public List<TextReply> textReplies() {
        return new ArrayList<>(textReplies);
    }

    public List<ReactionAdd> reactionAdds() {
        return new ArrayList<>(reactionAdds);
    }

    public List<ReactionDelete> reactionDeletes() {
        return new ArrayList<>(reactionDeletes);
    }

    public record ImageReply(String messageId, String imageKey) {}

    private final CopyOnWriteArrayList<ImageReply> imageReplies = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> uploadedImages = new CopyOnWriteArrayList<>();
    private final AtomicBoolean failImages = new AtomicBoolean(false);

    /** When set, {@link #uploadImage} reports failure so the fallback path can be checked. */
    public void failImageUpload() {
        failImages.set(true);
    }

    public List<ImageReply> imageReplies() {
        return new ArrayList<>(imageReplies);
    }

    public List<String> uploadedImages() {
        return new ArrayList<>(uploadedImages);
    }

    @Override
    public Optional<String> uploadImage(byte[] bytes, String filename) {
        if (failImages.get() || bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        uploadedImages.add(filename == null ? "" : filename);
        return Optional.of("img-key-" + uploadedImages.size());
    }

    @Override
    public void replyImage(String messageId, String imageKey) {
        imageReplies.add(new ImageReply(messageId, imageKey));
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

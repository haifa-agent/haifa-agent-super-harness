package io.haifa.agent.superharness.server.channels.feishu;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalized inbound Feishu IM message (transport-agnostic).
 */
public record FeishuInboundMessage(
        String chatId,
        String messageId,
        String openId,
        String text,
        String chatType,
        String messageType,
        List<Attachment> attachments,
        CardAction cardAction,
        String rootId,
        String parentId,
        String feishuThreadId) {

    public FeishuInboundMessage(String chatId, String messageId, String openId, String text, String chatType) {
        this(chatId, messageId, openId, text, chatType, "text", List.of(), null, "", "", "");
    }

    public FeishuInboundMessage(
            String chatId, String messageId, String openId, String text, String chatType, CardAction cardAction) {
        this(chatId, messageId, openId, text, chatType, "text", List.of(), cardAction, "", "", "");
    }

    /** Binary compatible construction for existing channels and tests. */
    public FeishuInboundMessage(String chatId, String messageId, String openId, String text,
            String chatType, String messageType, List<Attachment> attachments, CardAction cardAction) {
        this(chatId, messageId, openId, text, chatType, messageType, attachments, cardAction, "", "", "");
    }

    public FeishuInboundMessage {
        rootId = rootId == null ? "" : rootId;
        parentId = parentId == null ? "" : parentId;
        feishuThreadId = feishuThreadId == null ? "" : feishuThreadId;
        chatId = chatId == null ? "" : chatId;
        messageId = messageId == null ? "" : messageId;
        openId = openId == null ? "" : openId;
        text = text == null ? "" : text;
        chatType = chatType == null ? "" : chatType;
        messageType = messageType == null || messageType.isBlank() ? "text" : messageType;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** Image or file resource referenced by the inbound Feishu message. */
    public record Attachment(String kind, String resourceKey, String filename) {
        public Attachment {
            kind = kind == null ? "" : kind;
            resourceKey = resourceKey == null ? "" : resourceKey;
            filename = filename == null ? "" : filename;
        }

        public boolean downloadable() {
            return ("image".equals(kind) || "file".equals(kind)) && !resourceKey.isBlank();
        }
    }

    /**
     * Button / form payload copied from a card action. Option labels are re-read from thread state;
     * Form v2 field values ride {@link #formValues()}.
     */
    public record CardAction(
            String source,
            String requestId,
            String optionId,
            String value,
            String cardSchema,
            Map<String, Object> formValues) {
        public CardAction(String source, String requestId, String optionId, String value) {
            this(source, requestId, optionId, value, "", Map.of());
        }

        public CardAction(String source, String requestId, String optionId, String value, String cardSchema) {
            this(source, requestId, optionId, value, cardSchema, Map.of());
        }

        public CardAction {
            source = source == null ? "" : source;
            requestId = requestId == null ? "" : requestId;
            optionId = optionId == null ? "" : optionId;
            value = value == null ? "" : value;
            cardSchema = cardSchema == null ? "" : cardSchema;
            formValues = formValues == null || formValues.isEmpty()
                    ? Map.of()
                    : Map.copyOf(new LinkedHashMap<>(formValues));
        }

        public boolean formSubmit() {
            return "form_submit".equals(optionId);
        }
    }

    public boolean hasDownloadableAttachments() {
        for (Attachment attachment : attachments) {
            if (attachment.downloadable()) {
                return true;
            }
        }
        return false;
    }

    /** Text/post chat, card actions, or media that can become a Run input. */
    public boolean isRunnableInbound() {
        if (cardAction != null) {
            return true;
        }
        if (!text.isBlank()) {
            return true;
        }
        return hasDownloadableAttachments();
    }
}

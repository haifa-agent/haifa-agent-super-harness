package io.haifa.agent.superharness.server.channels.feishu;

/**
 * Normalized inbound Feishu IM message (transport-agnostic).
 */
public record FeishuInboundMessage(
        String chatId, String messageId, String openId, String text, String chatType, CardAction cardAction) {

    public FeishuInboundMessage(String chatId, String messageId, String openId, String text, String chatType) {
        this(chatId, messageId, openId, text, chatType, null);
    }

    public FeishuInboundMessage {
        chatId = chatId == null ? "" : chatId;
        messageId = messageId == null ? "" : messageId;
        openId = openId == null ? "" : openId;
        text = text == null ? "" : text;
        chatType = chatType == null ? "" : chatType;
    }

    /** Button payload copied from a card action. The option value is re-read from thread state. */
    public record CardAction(String source, String requestId, String optionId, String value, String cardSchema) {
        public CardAction(String source, String requestId, String optionId, String value) {
            this(source, requestId, optionId, value, "");
        }

        public CardAction {
            source = source == null ? "" : source;
            requestId = requestId == null ? "" : requestId;
            optionId = optionId == null ? "" : optionId;
            value = value == null ? "" : value;
            cardSchema = cardSchema == null ? "" : cardSchema;
        }
    }
}

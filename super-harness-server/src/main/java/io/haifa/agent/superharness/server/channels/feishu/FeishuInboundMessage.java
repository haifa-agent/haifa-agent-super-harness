package io.haifa.agent.superharness.server.channels.feishu;

/**
 * Normalized inbound Feishu IM message (transport-agnostic).
 */
public record FeishuInboundMessage(
        String chatId, String messageId, String openId, String text, String chatType) {
    public FeishuInboundMessage {
        chatId = chatId == null ? "" : chatId;
        messageId = messageId == null ? "" : messageId;
        openId = openId == null ? "" : openId;
        text = text == null ? "" : text;
        chatType = chatType == null ? "" : chatType;
    }
}

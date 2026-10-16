package io.haifa.agent.superharness.server.channels.feishu;

/**
 * Frozen {@code human_input_response} assembled by the Feishu adapter. The Run API still validates it.
 */
public record FeishuHumanResponse(
        String source, String requestId, String responseKind, String optionId, String value) {}

package io.haifa.agent.superharness.server.channels.feishu;

import java.util.Map;

/**
 * In-process seam from Feishu worker to DeerFlow thread + Haifa Run (no second agent loop).
 */
public interface FeishuAgentBridge {

    record TurnResult(String threadId, String assistantText) {}

    /**
     * Create or reuse {@code threadId} as {@code ownerUserId}, post {@code userText}, wait for the
     * Run to complete, and return the final assistant text.
     */
    TurnResult runTurn(
            String ownerUserId,
            String threadId,
            Map<String, Object> channelMetadata,
            String userText)
            throws Exception;
}

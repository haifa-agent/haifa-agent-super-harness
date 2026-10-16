package io.haifa.agent.superharness.server.channels.feishu;

import java.util.Map;
import java.util.Optional;

/**
 * In-process seam from Feishu worker to DeerFlow thread + Haifa Run (no second agent loop).
 */
public interface FeishuAgentBridge {

    record TurnResult(String threadId, String assistantText, FeishuPendingInput pending) {
        public TurnResult(String threadId, String assistantText) {
            this(threadId, assistantText, null);
        }
    }

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

    /**
     * Same turn, with a frozen human-input response attached to the user message.
     * The default drops the response so existing test bridges keep compiling.
     */
    default TurnResult runTurn(
            String ownerUserId,
            String threadId,
            Map<String, Object> channelMetadata,
            String userText,
            FeishuHumanResponse response)
            throws Exception {
        return runTurn(ownerUserId, threadId, channelMetadata, userText);
    }

    /** Tail unanswered human-input card, if thread state currently ends on one. */
    default Optional<FeishuPendingInput> currentPending(String ownerUserId, String threadId) throws Exception {
        return Optional.empty();
    }
}

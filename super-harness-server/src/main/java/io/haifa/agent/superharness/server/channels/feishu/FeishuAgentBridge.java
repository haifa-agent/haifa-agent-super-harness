package io.haifa.agent.superharness.server.channels.feishu;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-process seam from Feishu worker to DeerFlow thread + Haifa Run (no second agent loop).
 */
public interface FeishuAgentBridge {

    /** One Thread upload that will ride {@code additional_kwargs.files} like the web composer. */
    record UploadedFile(String filename, String path, long size) {
        public UploadedFile {
            filename = filename == null ? "" : filename;
            path = path == null ? "" : path;
        }
    }

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

    /**
     * Same turn with web-equivalent uploaded files. Default ignores files so existing test bridges
     * keep compiling.
     */
    default TurnResult runTurn(
            String ownerUserId,
            String threadId,
            Map<String, Object> channelMetadata,
            String userText,
            FeishuHumanResponse response,
            List<UploadedFile> files)
            throws Exception {
        return runTurn(ownerUserId, threadId, channelMetadata, userText, response);
    }

    /**
     * Materialize bytes into the Thread uploads area (same product store the web upload API uses).
     * Default rejects so production must wire a real implementation.
     */
    default UploadedFile storeUpload(String ownerUserId, String threadId, String filename, byte[] content, String mediaType)
            throws Exception {
        throw new UnsupportedOperationException("Feishu upload store is not configured");
    }

    /** Tail unanswered human-input card, if thread state currently ends on one. */
    default Optional<FeishuPendingInput> currentPending(String ownerUserId, String threadId) throws Exception {
        return Optional.empty();
    }
}

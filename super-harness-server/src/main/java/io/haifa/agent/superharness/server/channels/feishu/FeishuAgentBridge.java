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

    record TurnResult(
            String threadId,
            String assistantText,
            FeishuPendingInput pending,
            List<String> artifacts,
            List<String> todoLines,
            List<String> progressLog) {
        public TurnResult(String threadId, String assistantText) {
            this(threadId, assistantText, null, List.of(), List.of(), List.of());
        }

        public TurnResult(String threadId, String assistantText, FeishuPendingInput pending) {
            this(threadId, assistantText, pending, List.of(), List.of(), List.of());
        }

        public TurnResult {
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
            todoLines = todoLines == null ? List.of() : List.copyOf(todoLines);
            progressLog = progressLog == null ? List.of() : List.copyOf(progressLog);
        }
    }

    /**
     * Optional Phase 2 controls for one Turn: mode, progress listener, and whether this admission is
     * a mid-run steer.
     */
    record TurnOptions(
            String mode, FeishuRunProjector.ProgressListener progress, boolean steer) {
        public static TurnOptions defaults() {
            return new TurnOptions("flash", null, false);
        }

        public TurnOptions {
            mode = mode == null || mode.isBlank() ? "flash" : mode.trim();
        }

        public TurnOptions withMode(String nextMode) {
            return new TurnOptions(nextMode, progress, steer);
        }

        public TurnOptions withProgress(FeishuRunProjector.ProgressListener next) {
            return new TurnOptions(mode, next, steer);
        }

        public TurnOptions asSteer() {
            return new TurnOptions(mode, progress, true);
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
     * Full Phase 2 turn (mode / progress / steer). Default falls back to the files overload and
     * ignores options so test doubles keep compiling.
     */
    default TurnResult runTurn(
            String ownerUserId,
            String threadId,
            Map<String, Object> channelMetadata,
            String userText,
            FeishuHumanResponse response,
            List<UploadedFile> files,
            TurnOptions options)
            throws Exception {
        return runTurn(ownerUserId, threadId, channelMetadata, userText, response, files);
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

    /** Cancel the thread's active Run (Stop). Default reports no active run. */
    default boolean cancelActiveRun(String ownerUserId, String threadId) throws Exception {
        return false;
    }

    /** Whether the thread currently has a non-terminal active Run. */
    default boolean hasActiveRun(String ownerUserId, String threadId) throws Exception {
        return false;
    }
}

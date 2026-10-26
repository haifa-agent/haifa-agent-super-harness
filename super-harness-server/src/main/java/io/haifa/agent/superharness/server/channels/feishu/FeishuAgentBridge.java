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

    /**
     * Bounded bytes of one Run artifact for channel delivery (image upload). Default: unavailable so
     * test doubles keep compiling.
     */
    record ArtifactBytes(String filename, String mediaType, byte[] bytes) {}

    /** Read one artifact the Run produced; empty when the channel cannot resolve it. */
    default Optional<ArtifactBytes> readArtifact(
            String ownerUserId, String threadId, String virtualPath) {
        return Optional.empty();
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

    /** One Custom Agent row for {@code /agent list} (DeerFlow channel catalog shape). */
    record AgentListItem(String name, String description) {
        public AgentListItem {
            name = name == null ? "" : name;
            description = description == null ? "" : description;
        }
    }

    /**
     * Optional Phase 2 controls for one Turn: mode, progress listener, mid-run steer, bootstrap, and
     * an optional Custom Agent pin for this admission.
     */
    record TurnOptions(
            String mode,
            FeishuRunProjector.ProgressListener progress,
            boolean steer,
            boolean bootstrap,
            String agentName) {
        public static TurnOptions defaults() {
            return new TurnOptions("flash", null, false, false, null);
        }

        public TurnOptions {
            mode = mode == null || mode.isBlank() ? "flash" : mode.trim();
            agentName = agentName == null || agentName.isBlank() ? null : agentName.trim();
        }

        public TurnOptions withMode(String nextMode) {
            return new TurnOptions(nextMode, progress, steer, bootstrap, agentName);
        }

        public TurnOptions withProgress(FeishuRunProjector.ProgressListener next) {
            return new TurnOptions(mode, next, steer, bootstrap, agentName);
        }

        public TurnOptions asSteer() {
            return new TurnOptions(mode, progress, true, bootstrap, agentName);
        }

        public TurnOptions asBootstrap() {
            return new TurnOptions(mode, progress, steer, true, agentName);
        }

        public TurnOptions withAgentName(String nextAgent) {
            return new TurnOptions(mode, progress, steer, bootstrap, nextAgent);
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

    /**
     * Create the SH Thread for {@code /new} through the shared conversation use case so the id handed
     * back to the user is a real, Web-openable thread (not a locally generated UUID). Default is a
     * no-op for test doubles that do not own a Runtime.
     */
    default void createThread(String ownerUserId, String threadId, Map<String, Object> channelMetadata)
            throws Exception {
        // No-op: test doubles route without a Runtime; production uses InProcessFeishuAgentBridge.
    }

    /**
     * Create a thread and pin a Custom Agent (or clear to {@code lead_agent}) for {@code /agent use}.
     * Default delegates to {@link #createThread} and ignores the agent pin so test doubles keep
     * compiling.
     */
    default void createThreadWithAgent(
            String ownerUserId,
            String threadId,
            Map<String, Object> channelMetadata,
            String agentName)
            throws Exception {
        createThread(ownerUserId, threadId, channelMetadata);
    }

    /**
     * Custom Agent pin for {@code threadId}, if any. Subsequent ordinary Feishu turns must carry this
     * into {@code context.agent_name} so {@code admitTurn} does not fall back to the default lead.
     */
    default Optional<String> pinnedAgentName(String ownerUserId, String threadId) throws Exception {
        return Optional.empty();
    }

    /** Executable model names for {@code /models}. Default: empty catalog. */
    default List<String> listModelNames(String ownerUserId) {
        return List.of();
    }

    /**
     * Memory fact count for {@code /memory}. Default 0 until a Haifa Memory product surface is wired;
     * this channel command must not invent a second Memory Engine.
     */
    default int memoryFactCount(String ownerUserId) {
        return 0;
    }

    /** Custom Agents for {@code /agent list}. Default: empty. */
    default List<AgentListItem> listAgents(String ownerUserId) {
        return List.of();
    }

    /** Whether {@code agentName} exists as a Custom Agent (not {@code lead_agent}). */
    default boolean agentExists(String ownerUserId, String agentName) {
        return false;
    }

    /** Current thread goal objective for {@code /goal}, if any. */
    default Optional<String> currentGoal(String ownerUserId, String threadId) throws Exception {
        return Optional.empty();
    }

    /** Persist a thread goal objective for {@code /goal <condition>}. */
    default void setGoal(String ownerUserId, String threadId, String objective) throws Exception {
        throw new UnsupportedOperationException("Feishu thread goal store is not configured");
    }

    /** Clear the thread goal for {@code /goal clear}. */
    default void clearGoal(String ownerUserId, String threadId) throws Exception {
        // No-op for test doubles.
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

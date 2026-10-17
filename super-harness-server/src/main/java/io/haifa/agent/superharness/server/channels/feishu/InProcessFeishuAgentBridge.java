package io.haifa.agent.superharness.server.channels.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.server.api.DeerFlowConversationService;
import io.haifa.agent.superharness.server.auth.AccessOwner;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.UploadReceipt;
import io.haifa.agent.superharness.workspace.WorkspaceBinding;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reuses the shared {@link DeerFlowConversationService} product use cases directly under
 * {@link AccessOwner#call} so Feishu-bound owners get the correct thread bucket without a public
 * webhook, a second Run loop, or any HTTP/SSE byte round-trip. The service instance is the same one
 * the Web compatibility adapter serves, so both channels share one Runtime truth.
 */
public final class InProcessFeishuAgentBridge implements FeishuAgentBridge {
    private static final Set<String> MODES = Set.of("flash", "thinking", "pro", "ultra");

    private final ObjectMapper json;
    private final DeerFlowConversationService threads;
    private final RestrictedWorkspaceService workspace;

    public InProcessFeishuAgentBridge(ObjectMapper json, DeerFlowConversationService threads) {
        this(json, threads, null);
    }

    public InProcessFeishuAgentBridge(
            ObjectMapper json, DeerFlowConversationService threads, RestrictedWorkspaceService workspace) {
        this.json = json;
        this.threads = threads;
        this.workspace = workspace;
    }

    @Override
    public TurnResult runTurn(
            String ownerUserId,
            String threadId,
            Map<String, Object> channelMetadata,
            String userText)
            throws Exception {
        return runTurn(ownerUserId, threadId, channelMetadata, userText, null, List.of(), TurnOptions.defaults());
    }

    @Override
    public TurnResult runTurn(
            String ownerUserId,
            String threadId,
            Map<String, Object> channelMetadata,
            String userText,
            FeishuHumanResponse response)
            throws Exception {
        return runTurn(ownerUserId, threadId, channelMetadata, userText, response, List.of(), TurnOptions.defaults());
    }

    @Override
    public TurnResult runTurn(
            String ownerUserId,
            String threadId,
            Map<String, Object> channelMetadata,
            String userText,
            FeishuHumanResponse response,
            List<UploadedFile> files)
            throws Exception {
        return runTurn(
                ownerUserId, threadId, channelMetadata, userText, response, files, TurnOptions.defaults());
    }

    @Override
    public TurnResult runTurn(
            String ownerUserId,
            String threadId,
            Map<String, Object> channelMetadata,
            String userText,
            FeishuHumanResponse response,
            List<UploadedFile> files,
            TurnOptions options)
            throws Exception {
        String owner = ownerUserId == null || ownerUserId.isBlank() ? AccessOwner.DEFAULT : ownerUserId;
        TurnOptions resolved = options == null ? TurnOptions.defaults() : options;
        return AccessOwner.call(owner, () -> {
            try {
                ensureThread(threadId, channelMetadata);
                String content = userText;
                if ((content == null || content.isBlank()) && response != null) {
                    content = FeishuInteraction.responseContent(
                            currentPendingLocked(threadId).orElse(null), response);
                }
                if (content == null || content.isBlank()) {
                    content = "";
                }
                FeishuRunProjector projector = runStream(
                        threadId,
                        content,
                        response,
                        files == null ? List.of() : files,
                        resolved);
                return readTurn(threadId, projector);
            } catch (Exception exception) {
                throw new IllegalStateException("Feishu channel run failed", exception);
            }
        });
    }

    @Override
    public UploadedFile storeUpload(
            String ownerUserId, String threadId, String filename, byte[] content, String mediaType)
            throws Exception {
        if (workspace == null) {
            throw new UnsupportedOperationException("Feishu upload store is not configured");
        }
        String owner = ownerUserId == null || ownerUserId.isBlank() ? AccessOwner.DEFAULT : ownerUserId;
        return AccessOwner.call(owner, () -> {
            try {
                ensureThread(threadId, Map.of("channel", "feishu"));
                WorkspaceBinding binding = workspace.bind(owner, threadId);
                UploadReceipt receipt = workspace.upload(
                        owner,
                        binding.workspaceId(),
                        filename,
                        content,
                        mediaType == null || mediaType.isBlank() ? "application/octet-stream" : mediaType);
                // DeerFlowUploadContext resolves files by filename (ignores path). After a
                // collision the bytes live under image__<hash>.jpg while originalFilename stays
                // image.jpg — use the stored basename so the next Run reads this upload.
                return new UploadedFile(
                        storedUploadFilename(receipt.logicalPath()),
                        receipt.logicalPath(),
                        receipt.byteCount());
            } catch (Exception exception) {
                throw new IllegalStateException("Feishu upload store failed", exception);
            }
        });
    }

    /** Basename of the logical upload path (same rule as {@code DeerFlowWorkspaceApi.uploadInfo}). */
    public static String storedUploadFilename(String logicalPath) {
        if (logicalPath == null || logicalPath.isBlank()) {
            return "";
        }
        int slash = logicalPath.lastIndexOf('/');
        return slash < 0 ? logicalPath : logicalPath.substring(slash + 1);
    }

    @Override
    public Optional<FeishuPendingInput> currentPending(String ownerUserId, String threadId) throws Exception {
        String owner = ownerUserId == null || ownerUserId.isBlank() ? AccessOwner.DEFAULT : ownerUserId;
        return AccessOwner.call(owner, () -> {
            try {
                return currentPendingLocked(threadId);
            } catch (Exception exception) {
                throw new IllegalStateException("Feishu pending read failed", exception);
            }
        });
    }

    @Override
    public boolean cancelActiveRun(String ownerUserId, String threadId) throws Exception {
        String owner = ownerUserId == null || ownerUserId.isBlank() ? AccessOwner.DEFAULT : ownerUserId;
        return AccessOwner.call(owner, () -> {
            Optional<String> runId = threads.activeRunId(threadId);
            if (runId.isEmpty()) {
                return false;
            }
            try {
                threads.cancelRun(threadId, runId.get(), "wait=false&action=interrupt");
                return true;
            } catch (DeerFlowConversationService.ConversationException rejected) {
                return false;
            }
        });
    }

    @Override
    public boolean hasActiveRun(String ownerUserId, String threadId) throws Exception {
        String owner = ownerUserId == null || ownerUserId.isBlank() ? AccessOwner.DEFAULT : ownerUserId;
        return AccessOwner.call(owner, () -> threads.activeRunId(threadId).isPresent());
    }

    private void ensureThread(String threadId, Map<String, Object> channelMetadata) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("thread_id", threadId);
        ObjectNode metadata = body.putObject("metadata");
        if (channelMetadata != null) {
            for (Map.Entry<String, Object> entry : channelMetadata.entrySet()) {
                metadata.set(entry.getKey(), json.valueToTree(entry.getValue()));
            }
        }
        metadata.put("channel", "feishu");
        try {
            threads.createThread(body);
        } catch (DeerFlowConversationService.ConversationException rejected) {
            throw new IllegalStateException(
                    "Thread create failed: " + rejected.kind().status() + " " + rejected.detail());
        }
    }

    private FeishuRunProjector runStream(
            String threadId,
            String userText,
            FeishuHumanResponse response,
            List<UploadedFile> files,
            TurnOptions options)
            throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("assistant_id", "lead_agent");
        body.put("on_disconnect", "continue");
        ObjectNode input = body.putObject("input");
        FeishuInteraction.writeHumanMessage(
                input.putArray("messages").addObject(),
                userText == null ? "" : userText,
                response,
                files);
        body.putArray("stream_mode").add("messages-tuple").add("updates").add("custom");
        // Mode alone feeds DeerFlowReasoningSettings; do not force thinking_enabled so probe /
        // non-thinking models still admit under thinking/pro/ultra labels when the binding allows.
        body.putObject("context").put("mode", normalizeMode(options.mode()));
        String runInput = options.steer() ? "steer" : null;
        DeerFlowConversationService.AdmittedTurn turn;
        try {
            turn = threads.admitTurn(threadId, body, runInput, null);
        } catch (DeerFlowConversationService.ConversationException rejected) {
            throw new FeishuRunRejected(rejected.kind().status(), boundedDetail(rejected.detail()));
        }
        FeishuRunProjector projector = new FeishuRunProjector(options.progress());
        threads.observeTurn(turn, projector);
        if (!projector.sawEnd()
                && !projector.sawError()
                && !projector.sawValues()
                && !projector.sawMessages()) {
            // streamRunEvents normally emits end on completion; tolerate values-only fixtures.
            throw new IllegalStateException("Run stream ended without completion marker");
        }
        if (projector.sawError()) {
            throw new IllegalStateException("Run stream reported error");
        }
        return projector;
    }

    static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "flash";
        }
        String trimmed = mode.trim().toLowerCase();
        return MODES.contains(trimmed) ? trimmed : "flash";
    }

    static boolean isValidMode(String mode) {
        return mode != null && MODES.contains(mode.trim().toLowerCase());
    }

    private Optional<FeishuPendingInput> currentPendingLocked(String threadId) throws Exception {
        // The projected transcript orders cards by Tool-call round, so a waiting card is not
        // necessarily its last message. Ask the Run that owns the pending Interaction instead
        // (W04-BUG-010); the transcript scan stays as the fallback for legacy threads.
        Optional<JsonNode> authoritative = threads.pendingInteractionMessage(threadId);
        if (authoritative.isPresent()) {
            ArrayNode single = json.createArrayNode();
            single.add(authoritative.orElseThrow());
            return FeishuInteraction.pendingFromMessages(single);
        }
        JsonNode state = threadState(threadId);
        if (state == null) {
            return Optional.empty();
        }
        return FeishuInteraction.pendingFromMessages(state.path("values").path("messages"));
    }

    private TurnResult readTurn(String threadId, FeishuRunProjector projector) throws Exception {
        JsonNode state = threadState(threadId);
        if (state == null) {
            throw new IllegalStateException("Thread state failed: HTTP 404");
        }
        JsonNode messages = state.path("values").path("messages");
        FeishuPendingInput pending = FeishuInteraction.pendingFromMessages(messages).orElse(null);
        String assistant = "";
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                String type = message.path("type").asText();
                String role = message.path("role").asText();
                if ("ai".equals(type) || "assistant".equals(type) || "assistant".equals(role) || "ai".equals(role)) {
                    assistant = FeishuInteraction.messageText(message);
                }
            }
        }
        if (pending != null && !pending.preface().isBlank()) {
            assistant = pending.preface();
        }
        List<String> artifacts = projector == null ? List.of() : projector.artifacts();
        List<String> todos = projector == null ? List.of() : projector.todoLines();
        List<String> progress = projector == null ? List.of() : projector.progressLog();
        if (pending == null) {
            assistant = assistant
                    + FeishuRunProjector.todoAppendix(todos)
                    + FeishuRunProjector.artifactHints(threadId, artifacts);
        }
        return new TurnResult(threadId, assistant, pending, artifacts, todos, progress);
    }

    private JsonNode threadState(String threadId) throws Exception {
        try {
            return threads.readThreadState(threadId);
        } catch (DeerFlowConversationService.ConversationException rejected) {
            if (rejected.kind() == DeerFlowConversationService.ConversationException.Kind.NOT_FOUND) {
                return null;
            }
            throw rejected;
        }
    }

    private static String boundedDetail(String detail) {
        if (detail == null) return "";
        return detail.length() > 200 ? detail.substring(0, 200) : detail;
    }
}

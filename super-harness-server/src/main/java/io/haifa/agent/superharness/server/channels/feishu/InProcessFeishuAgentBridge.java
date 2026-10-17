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

/**
 * Reuses the shared {@link DeerFlowConversationService} product use cases directly under
 * {@link AccessOwner#call} so Feishu-bound owners get the correct thread bucket without a public
 * webhook, a second Run loop, or any HTTP/SSE byte round-trip. The service instance is the same one
 * the Web compatibility adapter serves, so both channels share one Runtime truth.
 */
public final class InProcessFeishuAgentBridge implements FeishuAgentBridge {
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
        return runTurn(ownerUserId, threadId, channelMetadata, userText, null, List.of());
    }

    @Override
    public TurnResult runTurn(
            String ownerUserId,
            String threadId,
            Map<String, Object> channelMetadata,
            String userText,
            FeishuHumanResponse response)
            throws Exception {
        return runTurn(ownerUserId, threadId, channelMetadata, userText, response, List.of());
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
        String owner = ownerUserId == null || ownerUserId.isBlank() ? AccessOwner.DEFAULT : ownerUserId;
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
                runStream(threadId, content, response, files == null ? List.of() : files);
                return readTurn(threadId);
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

    private void runStream(
            String threadId, String userText, FeishuHumanResponse response, List<UploadedFile> files)
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
        body.putObject("context").put("mode", "flash");
        DeerFlowConversationService.AdmittedTurn turn;
        try {
            turn = threads.admitTurn(threadId, body, null, null);
        } catch (DeerFlowConversationService.ConversationException rejected) {
            throw new FeishuRunRejected(rejected.kind().status(), boundedDetail(rejected.detail()));
        }
        CompletionProbe probe = new CompletionProbe();
        threads.observeTurn(turn, probe);
        if (!probe.sawEnd && !probe.sawError && !probe.sawValues && !probe.sawMessages) {
            // streamRunEvents normally emits end on completion; tolerate values-only fixtures.
            throw new IllegalStateException("Run stream ended without completion marker");
        }
        if (probe.sawError) {
            throw new IllegalStateException("Run stream reported error");
        }
    }

    /** Records only the frozen projection markers Feishu needs; it never touches SSE bytes. */
    private static final class CompletionProbe implements DeerFlowConversationService.RunEventSink {
        private boolean sawEnd;
        private boolean sawError;
        private boolean sawValues;
        private boolean sawMessages;

        @Override
        public void event(String event, Object data, String id) {
            switch (event) {
                case "end" -> sawEnd = true;
                case "error" -> sawError = true;
                case "values" -> sawValues = true;
                case "messages" -> sawMessages = true;
                default -> { }
            }
        }

        @Override
        public void heartbeat() {}
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

    private TurnResult readTurn(String threadId) throws Exception {
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
        return new TurnResult(threadId, assistant, pending);
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

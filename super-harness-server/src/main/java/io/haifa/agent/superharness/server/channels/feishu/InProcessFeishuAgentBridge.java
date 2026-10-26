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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    private static final System.Logger LOG = System.getLogger(InProcessFeishuAgentBridge.class.getName());

    private static final Set<String> MODES = Set.of("flash", "thinking", "pro", "ultra");

    /** Turns materialized by one Feishu state read; enough for the newest card and answer. */
    /** Upper bound for waiting on a Run whose live observation ended before its terminal frame. */
    static final java.time.Duration RUN_SETTLE_TIMEOUT = java.time.Duration.ofSeconds(60);
    static final int STATE_TAIL_TURNS = 24;
    /** Threads that keep a cross-Turn tool-card dedupe scope before the oldest is evicted. */
    static final int TOOL_DEDUPE_THREADS = 64;

    private final Map<String, Set<String>> toolCardScopes = Collections.synchronizedMap(
            new LinkedHashMap<String, Set<String>>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Set<String>> eldest) {
                    return size() > TOOL_DEDUPE_THREADS;
                }
            });

    private final ObjectMapper json;
    private final DeerFlowConversationService threads;
    private final RestrictedWorkspaceService workspace;
    private final FeishuChannelCatalog catalog;

    public InProcessFeishuAgentBridge(ObjectMapper json, DeerFlowConversationService threads) {
        this(json, threads, null, FeishuChannelCatalog.empty());
    }

    public InProcessFeishuAgentBridge(
            ObjectMapper json, DeerFlowConversationService threads, RestrictedWorkspaceService workspace) {
        this(json, threads, workspace, FeishuChannelCatalog.empty());
    }

    public InProcessFeishuAgentBridge(
            ObjectMapper json,
            DeerFlowConversationService threads,
            RestrictedWorkspaceService workspace,
            FeishuChannelCatalog catalog) {
        this.json = json;
        this.threads = threads;
        this.workspace = workspace;
        this.catalog = catalog == null ? FeishuChannelCatalog.empty() : catalog;
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
                // Steer acknowledgement is admission-only: observing the shared Run would block
                // until terminal and mis-report an already-accepted steer if the Run later errors.
                if (resolved.steer()) {
                    return new TurnResult(threadId, "", null, List.of(), List.of(), List.of());
                }
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

    @Override
    public void createThread(String ownerUserId, String threadId, Map<String, Object> channelMetadata)
            throws Exception {
        String owner = ownerUserId == null || ownerUserId.isBlank() ? AccessOwner.DEFAULT : ownerUserId;
        AccessOwner.call(owner, () -> {
            try {
                ensureThread(threadId, channelMetadata);
                return null;
            } catch (Exception exception) {
                throw new IllegalStateException("Feishu thread create failed", exception);
            }
        });
    }

    @Override
    public void createThreadWithAgent(
            String ownerUserId,
            String threadId,
            Map<String, Object> channelMetadata,
            String agentName)
            throws Exception {
        String owner = ownerUserId == null || ownerUserId.isBlank() ? AccessOwner.DEFAULT : ownerUserId;
        AccessOwner.call(owner, () -> {
            try {
                ensureThread(threadId, channelMetadata);
                String normalized = agentName == null ? "" : agentName.trim().toLowerCase(Locale.ROOT);
                if (FeishuInteraction.DEFAULT_ASSISTANT_ID.equalsIgnoreCase(normalized)) {
                    normalized = "";
                }
                threads.pinAgentSelection(threadId, normalized, false);
                return null;
            } catch (Exception exception) {
                throw new IllegalStateException("Feishu agent thread create failed", exception);
            }
        });
    }

    @Override
    public Optional<String> pinnedAgentName(String ownerUserId, String threadId) throws Exception {
        String owner = ownerUserId == null || ownerUserId.isBlank() ? AccessOwner.DEFAULT : ownerUserId;
        return AccessOwner.call(owner, () -> {
            try {
                return threads.pinnedAgentName(threadId);
            } catch (DeerFlowConversationService.ConversationException missing) {
                return Optional.empty();
            }
        });
    }

    @Override
    public List<String> listModelNames(String ownerUserId) {
        return List.copyOf(catalog.listExecutableModelNames());
    }

    @Override
    public List<AgentListItem> listAgents(String ownerUserId) {
        return List.copyOf(catalog.listAgents());
    }

    @Override
    public boolean agentExists(String ownerUserId, String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return false;
        }
        return catalog.agentExists(agentName.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public Optional<String> currentGoal(String ownerUserId, String threadId) throws Exception {
        String owner = ownerUserId == null || ownerUserId.isBlank() ? AccessOwner.DEFAULT : ownerUserId;
        return AccessOwner.call(owner, () -> {
            try {
                return threads.threadGoal(threadId);
            } catch (DeerFlowConversationService.ConversationException missing) {
                return Optional.empty();
            }
        });
    }

    @Override
    public void setGoal(String ownerUserId, String threadId, String objective) throws Exception {
        String owner = ownerUserId == null || ownerUserId.isBlank() ? AccessOwner.DEFAULT : ownerUserId;
        AccessOwner.call(owner, () -> {
            try {
                ensureThread(threadId, Map.of("channel", "feishu"));
                threads.setThreadGoal(threadId, objective);
                return null;
            } catch (Exception exception) {
                throw new IllegalStateException("Feishu goal set failed", exception);
            }
        });
    }

    @Override
    public void clearGoal(String ownerUserId, String threadId) throws Exception {
        String owner = ownerUserId == null || ownerUserId.isBlank() ? AccessOwner.DEFAULT : ownerUserId;
        AccessOwner.call(owner, () -> {
            try {
                threads.clearThreadGoal(threadId);
                return null;
            } catch (DeerFlowConversationService.ConversationException missing) {
                return null;
            } catch (Exception exception) {
                throw new IllegalStateException("Feishu goal clear failed", exception);
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
        ObjectNode context = body.putObject("context");
        context.put("mode", normalizeMode(options.mode()));
        if (options.bootstrap()) {
            context.put("is_bootstrap", true);
        }
        // Prefer an explicit TurnOptions pin; otherwise reuse the thread metadata written by
        // /agent use (or the last Run). Without this, admitTurn resolves the default lead and the
        // next setLeadPromptSelection would clear the pin.
        String agentName = options.agentName();
        if (agentName == null || agentName.isBlank()) {
            try {
                agentName = threads.pinnedAgentName(threadId).orElse(null);
            } catch (DeerFlowConversationService.ConversationException missing) {
                agentName = null;
            }
        }
        if (agentName != null && !agentName.isBlank()) {
            context.put("agent_name", agentName.trim().toLowerCase(Locale.ROOT));
        }
        String runInput = options.steer() ? "steer" : null;
        DeerFlowConversationService.AdmittedTurn turn;
        try {
            turn = threads.admitTurn(threadId, body, runInput, null);
        } catch (DeerFlowConversationService.ConversationException rejected) {
            throw new FeishuRunRejected(rejected.kind().status(), boundedDetail(rejected.detail()));
        }
        if (options.steer()) {
            // Creator/observer already owns observation; steer only needs admission success.
            return new FeishuRunProjector(options.progress());
        }
        FeishuRunProjector projector = new FeishuRunProjector(
                options.progress(),
                FeishuRunProjector.MIN_PROGRESS_GAP_MS,
                FeishuRunProjector.MAX_PROGRESS_EVENTS,
                toolCardScope(threadId));
        threads.observeTurn(turn, projector);
        if (projector.sawError()) {
            String detail = projector.errorDetail();
            LOG.log(System.Logger.Level.WARNING, "Feishu run stream reported an error: {0}", detail);
            throw new IllegalStateException("Run stream reported error: " + detail);
        }
        if (!projector.sawEnd()) {
            // The Feishu reply is projected from the durable thread state (readTurn), so an
            // observation that ended without a terminal frame (replay gap, transport hiccup,
            // reconnect) must not drop the final assistant message. Log a bounded diagnostic and
            // let the caller project the durable answer instead (W20-BUG-001).
            LOG.log(
                    System.Logger.Level.WARNING,
                    "Feishu run observation ended without a terminal frame (gap={0}, values={1}, "
                            + "messages={2}, diagnostics={3}); projecting the durable thread state",
                    projector.sawGap(),
                    projector.sawValues(),
                    projector.sawMessages(),
                    projector.gapDiagnostics());
            if (!projector.sawGap() && !projector.sawValues() && !projector.sawMessages()) {
                // Nothing was observed and no gap explained it: keep failing fast on this
                // genuinely empty observation instead of replying with an empty answer.
                throw new IllegalStateException("Run stream ended without completion marker");
            }
            // A gap can happen while the Run is still executing. Wait for its terminal status
            // before the durable read, otherwise the reply would carry an intermediate step.
            if (!threads.awaitRunTerminal(threadId, RUN_SETTLE_TIMEOUT)) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        "Feishu run did not settle within {0} ms; projecting the current durable state",
                        RUN_SETTLE_TIMEOUT.toMillis());
            }
        }
        return projector;
    }

    @Override
    public Optional<ArtifactBytes> readArtifact(String ownerUserId, String threadId, String virtualPath) {
        if (workspace == null || virtualPath == null || !virtualPath.startsWith("/mnt/user-data/")) {
            return Optional.empty();
        }
        String owner = ownerUserId == null || ownerUserId.isBlank() ? AccessOwner.DEFAULT : ownerUserId;
        return AccessOwner.call(owner, () -> {
            try {
                WorkspaceBinding binding = workspace.bind(owner, threadId);
                byte[] bytes = workspace.readVerifiedBytes(owner, binding.workspaceId(), virtualPath);
                if (bytes == null || bytes.length == 0) {
                    return Optional.<ArtifactBytes>empty();
                }
                return Optional.of(new ArtifactBytes(
                        filenameOf(virtualPath), mediaTypeOf(virtualPath), bytes));
            } catch (Exception unavailable) {
                return Optional.<ArtifactBytes>empty();
            }
        });
    }

    private static String filenameOf(String virtualPath) {
        int slash = virtualPath.lastIndexOf('/');
        return slash < 0 ? virtualPath : virtualPath.substring(slash + 1);
    }

    static String mediaTypeOf(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "application/octet-stream";
    }

    /** Bounded, Thread-scoped gate so a pending-card replay cannot re-post a tool card. */
    Set<String> toolCardScope(String threadId) {
        String scope = AccessOwner.current() + "|" + threadId;
        synchronized (toolCardScopes) {
            Set<String> keys = toolCardScopes.get(scope);
            if (keys == null) {
                keys = Collections.synchronizedSet(new LinkedHashSet<>());
                toolCardScopes.put(scope, keys);
            }
            return keys;
        }
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
            // Bounded tail: a long Thread must not be materialized end-to-start for one reply.
            return threads.readThreadStateTail(threadId, STATE_TAIL_TURNS);
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

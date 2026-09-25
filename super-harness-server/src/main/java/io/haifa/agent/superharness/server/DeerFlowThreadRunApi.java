package io.haifa.agent.superharness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunViewSnapshot;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.InteractionResponseReceiptStatus;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.project.changeset.FileChange;
import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.changeset.FileVersion;
import io.haifa.agent.superharness.events.AppendResult;
import io.haifa.agent.superharness.events.BoundedRunEventProjector;
import io.haifa.agent.superharness.events.EventProjectionException;
import io.haifa.agent.superharness.events.ProjectedRunEvent;
import io.haifa.agent.superharness.events.ProjectionCursor;
import io.haifa.agent.superharness.events.ProjectionGap;
import io.haifa.agent.superharness.events.ReplayResult;
import io.haifa.agent.superharness.events.RunTerminalKind;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.StagedRunChanges;
import io.haifa.agent.superharness.workspace.WorkspaceBinding;
import io.haifa.agent.superharness.workspace.WorkspaceChangeLimits;
import io.haifa.agent.superharness.workspace.WorkspaceChanges;
import io.haifa.agent.superharness.workspace.WorkspaceError;
import io.haifa.agent.superharness.workspace.WorkspaceException;
import io.haifa.agent.superharness.workspace.WorkspaceLineCounts;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import io.haifa.agent.sdk.conversation.ConversationTurn;
import io.haifa.agent.sdk.conversation.ConversationTurnCursor;
import io.haifa.agent.sdk.conversation.ConversationTurnQuery;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;

/** Frozen DeerFlow thread/run compatibility routes backed by the public Haifa Agent SDK. */
final class DeerFlowThreadRunApi implements HttpHandler, AutoCloseable {
    private static final Pattern THREAD = Pattern.compile("^/api/threads/([^/]+)$");
    private static final Pattern THREAD_STATE = Pattern.compile("^/api/threads/([^/]+)/state$");
    private static final Pattern THREAD_HISTORY =
            Pattern.compile("^/api/threads/([^/]+)/history$");
    private static final Pattern THREAD_MESSAGES_PAGE =
            Pattern.compile("^/api/threads/([^/]+)/messages/page$");
    private static final Pattern THREAD_RUNS = Pattern.compile("^/api/threads/([^/]+)/runs$");
    private static final Pattern STREAM = Pattern.compile("^/api/threads/([^/]+)/runs/stream$");
    private static final Pattern JOIN_STREAM =
            Pattern.compile("^/api/threads/([^/]+)/runs/([^/]+)/stream$");
    private static final Pattern RUN = Pattern.compile("^/api/threads/([^/]+)/runs/([^/]+)$");
    private static final Pattern WORKSPACE_CHANGES =
            Pattern.compile("^/api/threads/([^/]+)/runs/([^/]+)/workspace-changes$");
    private static final Pattern CANCEL =
            Pattern.compile("^/api/threads/([^/]+)/runs/([^/]+)/cancel$");
    private static final List<String> SUPPORTED_STREAM_MODES =
            List.of("messages-tuple", "updates", "custom");
    private static final Pattern SSE_CURSOR = Pattern.compile("^(\\d+)-0$");
    private static final int EVENT_RETENTION_CAPACITY = 8;
    private static final int EVENT_PAGE_SIZE = 4;
    static final Duration WORKSPACE_COMPLETION_OBSERVER_MAX_WAIT = Duration.ofHours(24);
    static final Duration WORKSPACE_COMPLETION_OBSERVER_INITIAL_POLL = Duration.ofMillis(25);
    static final Duration WORKSPACE_COMPLETION_OBSERVER_MAX_POLL = Duration.ofSeconds(1);
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            ".7z", ".avif", ".bmp", ".class", ".db", ".dll", ".dmg", ".doc", ".docx", ".exe",
            ".gif", ".gz", ".ico", ".jar", ".jpeg", ".jpg", ".mov", ".mp3", ".mp4", ".o",
            ".pdf", ".png", ".pyc", ".so", ".tar", ".webp", ".xls", ".xlsx", ".zip");
    private static final int TURN_SCAN_PAGE_SIZE = 100;
    private static final int MAXIMUM_TURN_SCAN_PAGES = 100;
    private static final int RUN_LIST_LIMIT = 100;
    private static final int HISTORY_MESSAGE_RETENTION = 200;
    private static final int TERMINAL_RUN_CACHE_LIMIT = 8;
    private static final java.time.Duration TERMINAL_EVENT_SETTLE_TIMEOUT =
            java.time.Duration.ofSeconds(2);
    static final Duration RUN_START_TIMEOUT = Duration.ofSeconds(5);

    private final ObjectMapper json;
    private final Function<JsonNode, HaifaRunBridge.ModelBinding> runModelResolver;
    private final Predicate<String> projectExists;
    private final PersistentProjectWorkspaceService workspaceChanges;
    private final List<? extends JavaTool<?, ?>> runTools;
    private final PersistentThreadCatalog threadCatalog;
    private final DurableHaifaFoundation foundation;
    private final Map<String, ThreadRecord> threads = new ConcurrentHashMap<>();
    private final Map<String, RunRecord> runs = new ConcurrentHashMap<>();
    private final Set<Thread> workspaceCompletionObservers = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    DeerFlowThreadRunApi(
            ObjectMapper json,
            Function<JsonNode, HaifaRunBridge.ModelBinding> runModelResolver,
            Predicate<String> projectExists,
            PersistentProjectWorkspaceService workspaceChanges,
            Path dataDirectory,
            SecretKey continuationKey,
            List<? extends JavaTool<?, ?>> runTools)
            throws IOException {
        this.json = json;
        this.runModelResolver = runModelResolver;
        this.projectExists = projectExists;
        this.workspaceChanges = workspaceChanges;
        this.runTools = List.copyOf(runTools);
        this.threadCatalog = new PersistentThreadCatalog(json, dataDirectory);
        this.foundation = new DurableHaifaFoundation(dataDirectory, continuationKey);
        for (PersistentThreadCatalog.Entry entry : threadCatalog.list()) {
            threads.put(entry.threadId(), new ThreadRecord(entry));
        }
    }

    boolean hasThread(String threadId) {
        return threads.containsKey(threadId);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!isLoopbackPeer(exchange)) {
                sendJson(exchange, 403, Map.of("detail", "Local profile accepts loopback clients only"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/api/threads") && exchange.getRequestMethod().equals("POST")) {
                createThread(exchange);
                return;
            }
            if (path.equals("/api/threads/search") && exchange.getRequestMethod().equals("POST")) {
                searchThreads(exchange);
                return;
            }
            Matcher match = THREAD_STATE.matcher(path);
            if (match.matches() && exchange.getRequestMethod().equals("GET")) {
                getThreadState(exchange, match.group(1));
                return;
            }
            match = THREAD_HISTORY.matcher(path);
            if (match.matches() && exchange.getRequestMethod().equals("POST")) {
                getThreadHistory(exchange, match.group(1));
                return;
            }
            match = THREAD_MESSAGES_PAGE.matcher(path);
            if (match.matches() && exchange.getRequestMethod().equals("GET")) {
                listThreadMessagesPage(exchange, match.group(1));
                return;
            }
            match = THREAD.matcher(path);
            if (match.matches() && exchange.getRequestMethod().equals("GET")) {
                getThread(exchange, match.group(1));
                return;
            }
            match = STREAM.matcher(path);
            if (match.matches() && exchange.getRequestMethod().equals("POST")) {
                streamRun(exchange, match.group(1));
                return;
            }
            match = JOIN_STREAM.matcher(path);
            if (match.matches() && exchange.getRequestMethod().equals("GET")) {
                joinRun(exchange, match.group(1), match.group(2));
                return;
            }
            match = CANCEL.matcher(path);
            if (match.matches() && exchange.getRequestMethod().equals("POST")) {
                cancelRun(exchange, match.group(1), match.group(2));
                return;
            }
            match = THREAD_RUNS.matcher(path);
            if (match.matches() && exchange.getRequestMethod().equals("GET")) {
                listRuns(exchange, match.group(1));
                return;
            }
            match = WORKSPACE_CHANGES.matcher(path);
            if (match.matches()) {
                if (exchange.getRequestMethod().equals("GET")) {
                    getWorkspaceChanges(exchange, match.group(1), match.group(2));
                } else {
                    exchange.getResponseHeaders().set("Allow", "GET");
                    sendJson(exchange, 405, Map.of("detail", "Method Not Allowed"));
                }
                return;
            }
            match = RUN.matcher(path);
            if (match.matches() && exchange.getRequestMethod().equals("GET")) {
                getRun(exchange, match.group(1), match.group(2));
                return;
            }
            sendJson(exchange, 404, Map.of("detail", "Not Found"));
        } catch (MalformedRequestException malformed) {
            sendJson(exchange, 422, Map.of("detail", "Invalid JSON body"));
        } catch (TranscriptScanLimitException exhausted) {
            sendJson(
                    exchange,
                    503,
                    Map.of(
                            "detail",
                            "Durable transcript exceeds the bounded scan budget; narrow the requested cursor"));
        } catch (IllegalStateException unavailable) {
            sendJson(exchange, 503, Map.of("detail", "Durable thread state is unavailable"));
        }
    }

    private void createThread(HttpExchange exchange) throws IOException {
        JsonNode body = readBody(exchange);
        String threadId = text(body, "thread_id").orElseGet(() -> java.util.UUID.randomUUID().toString());
        if (threadId.isBlank() || threadId.contains("/") || threadId.equals("new")) {
            sendJson(exchange, 422, Map.of("detail", "Invalid thread_id"));
            return;
        }
        Map<String, Object> metadata = objectMap(body.get("metadata"));
        // Identity and ownership are server-generated. Client-provided metadata cannot change them.
        metadata.remove("owner");
        metadata.remove("user_id");
        metadata.remove("system_role");
        metadata.remove("deerflow_project_id");
        if (body.has("project_id") && !body.get("project_id").isNull()) {
            if (!body.get("project_id").isTextual() || body.get("project_id").asText().isBlank()) {
                sendJson(exchange, 422, Map.of("detail", "Invalid project_id"));
                return;
            }
            String projectId = body.get("project_id").asText();
            if (!projectExists.test(projectId)) {
                sendJson(exchange, 404, Map.of("detail", "Project not found"));
                return;
            }
            metadata.put("deerflow_project_id", projectId);
        }
        ThreadRecord record = threads.computeIfAbsent(
                threadId, ignored -> new ThreadRecord(threadId, metadata, Instant.now()));
        persistThread(record);
        sendJson(exchange, 200, threadResponse(record));
    }

    private void getThread(HttpExchange exchange, String threadId) throws IOException {
        ThreadRecord record = threads.get(threadId);
        if (record == null) {
            sendJson(exchange, 404, Map.of("detail", "Thread not found"));
            return;
        }
        sendJson(exchange, 200, threadResponse(record));
    }

    private void getThreadState(HttpExchange exchange, String threadId) throws IOException {
        ThreadRecord thread = threads.get(threadId);
        if (thread == null) {
            sendJson(exchange, 404, Map.of("detail", "Thread not found"));
            return;
        }
        sendJson(exchange, 200, threadStateResponse(thread));
    }

    private void getThreadHistory(HttpExchange exchange, String threadId) throws IOException {
        ThreadRecord thread = threads.get(threadId);
        if (thread == null) {
            sendJson(exchange, 404, Map.of("detail", "Thread not found"));
            return;
        }
        JsonNode body = readBody(exchange);
        JsonNode limitNode = body.get("limit");
        if (limitNode != null
                && (!limitNode.canConvertToInt()
                        || !limitNode.isIntegralNumber()
                        || limitNode.intValue() < 1
                        || limitNode.intValue() > 100)) {
            sendJson(exchange, 422, Map.of("detail", "limit must be between 1 and 100"));
            return;
        }
        Long beforeSequence = null;
        if (body.has("before") && !body.get("before").isNull()) {
            JsonNode before = body.get("before");
            if (!before.isTextual() || !before.textValue().matches("turn-[1-9][0-9]*")) {
                sendJson(exchange, 422, Map.of("detail", "before must be a checkpoint cursor"));
                return;
            }
            try {
                beforeSequence = Long.parseLong(before.textValue().substring("turn-".length()));
            } catch (NumberFormatException overflow) {
                sendJson(exchange, 422, Map.of("detail", "before must be a checkpoint cursor"));
                return;
            }
        }
        int limit = limitNode == null ? 10 : limitNode.intValue();
        List<Map<String, Object>> response = new ArrayList<>(
                historyProjections(thread, limit, beforeSequence).stream()
                        .map(this::threadHistoryResponse)
                        .toList());
        if (beforeSequence == null) overlayHistoryHead(thread, response, limit);
        sendJson(exchange, 200, response);
    }

    private void listThreadMessagesPage(HttpExchange exchange, String threadId) throws IOException {
        ThreadRecord thread = threads.get(threadId);
        if (thread == null) {
            sendJson(exchange, 404, Map.of("detail", "Thread not found"));
            return;
        }
        if (queryValue(exchange, "after_seq").isPresent()) {
            sendJson(
                    exchange,
                    422,
                    Map.of("detail", "after_seq is not supported by this backward-only endpoint"));
            return;
        }
        Optional<Integer> limit = positiveQueryInteger(exchange, "limit", 50, 200);
        Optional<String> beforeRaw = queryValue(exchange, "before_seq");
        Long before = null;
        try {
            if (beforeRaw.isPresent()) {
                before = Long.parseLong(beforeRaw.orElseThrow());
                if (before < 1) {
                    throw new NumberFormatException();
                }
            }
        } catch (NumberFormatException invalid) {
            sendJson(exchange, 422, Map.of("detail", "Invalid message pagination"));
            return;
        }
        if (limit.isEmpty()) {
            sendJson(exchange, 422, Map.of("detail", "Invalid message pagination"));
            return;
        }

        int pageLimit = limit.orElseThrow();
        Long beforeSequence = before;
        List<ConversationTurn> durableTurns = scanTurns(
                thread, pageLimit + 2 * TERMINAL_RUN_CACHE_LIMIT + 3, beforeSequence);
        List<Map<String, Object>> eligible = new ArrayList<>(
                durableTurns.stream().map(this::messageRow).toList());
        Set<String> durableHumanRuns = durableHumanRunIds(durableTurns);
        for (RunRecord run : runs.values()) {
            if (!run.threadId.equals(threadId)) continue;
            if (isInFlightWithoutDurableHuman(run, durableHumanRuns)) {
                eligible.add(messageRow(run, run.humanSequence, run.createdAt, pendingHumanMessage(run)));
            }
            synchronized (run) {
                if (run.interactionRequestMessage != null) {
                    eligible.add(messageRow(run, run.interactionRequestSequence,
                            run.createdAt, run.interactionRequestMessage));
                }
                if (run.acceptedInteractionResponse != null) {
                    eligible.add(messageRow(run, run.interactionResponseSequence,
                            run.bridge.snapshot(run.runId).updatedAt(),
                            run.acceptedInteractionResponse.message()));
                }
            }
        }
        eligible.removeIf(row -> beforeSequence != null && ((Long) row.get("seq")) >= beforeSequence);
        eligible.sort((left, right) -> Long.compare((Long) left.get("seq"), (Long) right.get("seq")));
        int from = Math.max(0, eligible.size() - pageLimit);
        boolean hasMore = from > 0;
        List<Map<String, Object>> page = eligible.subList(from, eligible.size());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", page);
        response.put("has_more", hasMore);
        response.put("next_before_seq", hasMore ? page.getFirst().get("seq") : null);
        sendJson(exchange, 200, response);
    }

    private void searchThreads(HttpExchange exchange) throws IOException {
        JsonNode body = readBody(exchange);
        int limit = body.path("limit").asInt(100);
        int offset = body.path("offset").asInt(0);
        if (limit < 1 || limit > 1000 || offset < 0) {
            sendJson(exchange, 422, Map.of("detail", "Invalid search pagination"));
            return;
        }
        Map<String, Object> metadataFilter = objectMap(body.get("metadata"));
        Optional<String> statusFilter = text(body, "status");
        boolean archivedFilterPresent = body.has("archived") && !body.get("archived").isNull();
        boolean archived = body.path("archived").asBoolean(false);
        List<Map<String, Object>> matches = threads.values().stream()
                .filter(record -> !archivedFilterPresent
                        || Boolean.TRUE.equals(record.metadata.get("deerflow_archived")) == archived)
                .filter(record -> metadataFilter.entrySet().stream()
                        .allMatch(entry -> java.util.Objects.equals(
                                record.metadata.get(entry.getKey()), entry.getValue())))
                .filter(record -> statusFilter
                        .map(expected -> expected.equals(threadStatus(record)))
                        .orElse(true))
                .sorted((left, right) -> right.updatedAt.compareTo(left.updatedAt))
                .skip(offset)
                .limit(limit)
                .map(this::threadResponse)
                .toList();
        sendJson(exchange, 200, matches);
    }

    private void streamRun(HttpExchange exchange, String threadId) throws IOException {
        ThreadRecord thread = threads.get(threadId);
        if (thread == null) {
            sendJson(exchange, 404, Map.of("detail", "Thread not found"));
            return;
        }
        JsonNode body = readBody(exchange);
        Optional<String> validationError = validateRunRequest(body);
        if (validationError.isPresent()) {
            sendJson(exchange, 422, Map.of("detail", validationError.orElseThrow()));
            return;
        }
        String message = lastHumanMessage(body.path("input").path("messages"));
        if (message.isBlank()) {
            sendJson(exchange, 422, Map.of("detail", "input.messages requires a human message"));
            return;
        }
        Optional<DeerFlowInteractionHttpProjection.FrozenResponse> interactionResponse;
        try {
            interactionResponse = DeerFlowInteractionHttpProjection.parseResponse(
                    body.path("input").path("messages"));
        } catch (IllegalArgumentException invalid) {
            sendJson(exchange, 422, Map.of("detail", invalid.getMessage()));
            return;
        }
        if (interactionResponse.isPresent()) {
            respondToInteraction(
                    exchange,
                    thread,
                    interactionResponse.orElseThrow(),
                    message,
                    text(body, "on_disconnect").orElse("cancel"));
            return;
        }
        HaifaRunBridge.ModelBinding binding;
        try {
            binding = runModelResolver.apply(body);
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 422, Map.of("detail", exception.getMessage()));
            return;
        }

        RunRecord run;
        synchronized (thread) {
            if (thread.activeRunId != null) {
                RunRecord active = runs.get(thread.activeRunId);
                if (active == null || !active.bridge.snapshot(active.runId).status().isTerminal()) {
                    sendJson(exchange, 409, Map.of("detail", "Thread already has an active run"));
                    return;
                }
                completeWorkspaceChangesIfTerminal(active);
            }
            if (thread.bridge == null) {
                HaifaRunBridge bridge = new HaifaRunBridge(binding, runTools, foundation);
                if (thread.modelBindingIdentity != null
                        && !thread.modelBindingIdentity.equals(bridge.bindingIdentity())) {
                    bridge.close();
                    sendJson(
                            exchange,
                            409,
                            Map.of(
                                    "detail",
                                    "Thread is already bound to a different model revision; create a new thread"));
                    return;
                }
                attachBridge(thread, bridge);
            } else if (!thread.modelBindingIdentity.equals(binding.name() + "@" + binding.revision())) {
                sendJson(
                        exchange,
                        409,
                        Map.of(
                                "detail",
                                "Thread is already bound to a different model revision; create a new thread"));
                return;
            }
            StagedRunChanges staged = null;
            try {
                WorkspaceBinding workspace = workspaceChanges.bindThreadWorkspace(
                        DeerFlowBootstrapApi.DEFAULT_USER_ID, threadId);
                staged = workspaceChanges.stageRunChanges(
                        DeerFlowBootstrapApi.DEFAULT_USER_ID, threadId, workspace.workspaceId());
            } catch (RuntimeException ignored) {
                // DeerFlow treats workspace capture as diagnostic evidence: it cannot block the Run.
            }
            HaifaRunBridge.StartedRun started;
            try {
                started = thread.bridge.start(threadId, message, Optional.ofNullable(thread.session));
            } catch (RuntimeException failure) {
                workspaceChanges.discardRunChanges(staged);
                sendJson(exchange, 500, Map.of("detail", "Run could not start"));
                return;
            }
            boolean workspaceRecordingEnabled = false;
            if (staged != null) {
                try {
                    workspaceChanges.publishRunChanges(staged, started.runId().value());
                    workspaceRecordingEnabled = true;
                } catch (RuntimeException ignored) {
                    workspaceChanges.discardRunChanges(staged);
                }
            }
            thread.session = started.session();
            thread.persistedSessionId = started.session().sessionId();
            thread.updatedAt = Instant.now();
            thread.activeRunId = started.runId().value();
            thread.latestRunId = started.runId().value();
            long humanSequence = thread.nextMessageSequence;
            thread.nextMessageSequence += 2;
            run = new RunRecord(
                    started.runId(),
                    threadId,
                    text(body, "assistant_id").orElse(null),
                    message,
                    thread.bridge,
                    Instant.now(),
                    humanSequence,
                    workspaceRecordingEnabled,
                    requestedStreamModes(body));
            runs.put(run.runId.value(), run);
            persistThread(thread);
        }
        observeWorkspaceCompletion(run);

        try {
            streamRunEvents(
                    exchange,
                    thread,
                    run,
                    new ProjectionCursor(run.runId.value(), 0),
                    true,
                    text(body, "on_disconnect").orElse("cancel"),
                    true);
        } finally {
            completeWorkspaceChangesIfTerminal(run);
        }
    }

    private void respondToInteraction(
            HttpExchange exchange,
            ThreadRecord thread,
            DeerFlowInteractionHttpProjection.FrozenResponse response,
            String submittedText,
            String onDisconnect)
            throws IOException {
        RunRecord run;
        synchronized (thread) {
            String candidateRunId = thread.activeRunId != null
                    ? thread.activeRunId
                    : thread.latestRunId;
            run = candidateRunId == null ? null : runs.get(candidateRunId);
            if (run == null || !run.threadId.equals(thread.threadId)) {
                sendJson(exchange, 409, Map.of("detail", "Thread has no matching Interaction"));
                return;
            }
            synchronized (run) {
                if (run.acceptedInteractionResponse != null) {
                    if (!run.acceptedInteractionResponse.response().equals(response)) {
                        sendJson(
                                exchange,
                                409,
                                Map.of("detail", "Interaction already has a different response"));
                        return;
                    }
                    try {
                        var receipt = run.bridge.respond(
                                run.acceptedInteractionResponse.submission());
                        if (receipt.status() != InteractionResponseReceiptStatus.DUPLICATE) {
                            sendJson(
                                    exchange,
                                    409,
                                    Map.of("detail", "Interaction duplicate was not acknowledged"));
                            return;
                        }
                    } catch (RuntimeException rejected) {
                        sendJson(exchange, 409, Map.of("detail", "Interaction response was rejected"));
                        return;
                    }
                    sendJson(exchange, 409, Map.of("detail", "Interaction response already accepted"));
                    return;
                } else {
                    Optional<InteractionView> pending = run.bridge.pendingInteraction(run.runId);
                    if (pending.isEmpty()
                            || run.interactionRequestId == null
                            || !run.interactionRequestId.equals(response.requestId())) {
                        sendJson(exchange, 409, Map.of("detail", "Thread has no matching Interaction"));
                        return;
                    }
                    InteractionResponseSubmission submission;
                    try {
                        submission = DeerFlowInteractionHttpProjection.submission(
                                pending.orElseThrow(), response, Instant.now());
                        var receipt = run.bridge.respond(submission);
                        if (receipt.status() == InteractionResponseReceiptStatus.DUPLICATE) {
                            sendJson(
                                    exchange,
                                    409,
                                    Map.of("detail", "Interaction response projection is unavailable"));
                            return;
                        }
                    } catch (RuntimeException rejected) {
                        sendJson(exchange, 409, Map.of("detail", "Interaction response was rejected"));
                        return;
                    }
                    Map<String, Object> responseMessage =
                            DeerFlowInteractionHttpProjection.responseMessage(
                                    response, submittedText, run.runId.value());
                    run.acceptedInteractionResponse = new AcceptedInteractionResponse(
                            response, submission, responseMessage);
                    thread.updatedAt = Instant.now();
                }
            }
        }

        try {
            streamRunEvents(
                    exchange,
                    thread,
                    run,
                    run.projector.head(),
                    true,
                    onDisconnect,
                    true);
        } finally {
            completeWorkspaceChangesIfTerminal(run);
        }
    }

    private HaifaRunBridge bridgeForPersistedThread(ThreadRecord thread) {
        synchronized (thread) {
            if (thread.bridge != null) return thread.bridge;
            HaifaRunBridge.ModelBinding binding = runModelResolver.apply(json.createObjectNode());
            HaifaRunBridge bridge = new HaifaRunBridge(binding, List.of(), foundation);
            boolean claimBinding = thread.modelBindingIdentity == null
                    || thread.modelBindingIdentity.equals(bridge.bindingIdentity());
            // Conversation and run reads are backed by the shared SQLite authority and do not
            // execute the model. Preserve a historical binding across provider restarts so the
            // transcript remains readable, while streamRun still rejects a new run whose current
            // binding does not match the persisted identity.
            attachBridge(thread, bridge, claimBinding);
            return bridge;
        }
    }

    private void attachBridge(ThreadRecord thread, HaifaRunBridge bridge) {
        attachBridge(thread, bridge, true);
    }

    private void attachBridge(ThreadRecord thread, HaifaRunBridge bridge, boolean claimBinding) {
        HaifaRunBridge previousBridge = thread.bridge;
        String previousBinding = thread.modelBindingIdentity;
        HaifaRunBridge.SessionState previousSession = thread.session;
        long previousSequence = thread.nextMessageSequence;
        try {
            thread.bridge = bridge;
            if (claimBinding) thread.modelBindingIdentity = bridge.bindingIdentity();
            restoreSession(thread);
        } catch (RuntimeException | Error failure) {
            thread.bridge = previousBridge;
            thread.modelBindingIdentity = previousBinding;
            thread.session = previousSession;
            thread.nextMessageSequence = previousSequence;
            bridge.close();
            throw failure;
        }
    }

    private void restoreSession(ThreadRecord thread) {
        if (thread.session != null || thread.persistedSessionId == null || thread.bridge == null) return;
        var conversation = thread.bridge
                .findConversation(thread.persistedSessionId)
                .orElseThrow(() -> new IllegalStateException("persisted thread conversation is unavailable"));
        long latestSequence = latestVisibleTurnSequence(thread, thread.bridge);
        thread.session = new HaifaRunBridge.SessionState(conversation.sessionId(), conversation.revision());
        thread.nextMessageSequence = latestSequence + 1;
    }

    private long latestVisibleTurnSequence(ThreadRecord thread, HaifaRunBridge bridge) {
        long latest = 0;
        Optional<ConversationTurnCursor> cursor = Optional.empty();
        int pages = 0;
        while (true) {
            var page = bridge.turns(
                    thread.persistedSessionId,
                    new ConversationTurnQuery(cursor, TURN_SCAN_PAGE_SIZE));
            pages++;
            for (ConversationTurn turn : page.items()) {
                if (turn.role() == io.haifa.agent.core.message.MessageRole.USER
                        || turn.role() == io.haifa.agent.core.message.MessageRole.ASSISTANT) {
                    latest = Math.max(latest, turn.sequence());
                }
            }
            if (page.nextCursor().isEmpty()) return latest;
            requireAnotherScanPage(pages);
            cursor = page.nextCursor();
        }
    }

    private void persistThread(ThreadRecord thread) throws IOException {
        synchronized (thread) {
            threadCatalog.save(new PersistentThreadCatalog.Entry(
                    thread.threadId,
                    thread.metadata,
                    thread.createdAt,
                    thread.updatedAt,
                    Optional.ofNullable(thread.persistedSessionId),
                    Optional.ofNullable(thread.modelBindingIdentity)));
        }
    }

    private void joinRun(HttpExchange exchange, String threadId, String runId) throws IOException {
        if (queryValue(exchange, "action").isPresent()) {
            exchange.getResponseHeaders().set("Allow", "POST");
            sendJson(exchange, 405, Map.of("detail", "`action` is only supported on POST requests"));
            return;
        }
        ThreadRecord thread = threads.get(threadId);
        RunRecord run = thread == null ? null : findOrRestoreRun(thread, runId).orElse(null);
        if (run == null) {
            sendJson(exchange, 404, Map.of("detail", "Run " + runId + " not found"));
            return;
        }
        String rawCursor = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        ProjectionCursor cursor;
        try {
            cursor = parseSseCursor(runId, rawCursor);
            synchronizeAllAvailable(run);
            run.projector.replayAfter(cursor);
        } catch (IllegalArgumentException | EventProjectionException invalid) {
            sendJson(exchange, 409, Map.of("detail", invalid.getMessage()));
            return;
        }
        streamRunEvents(exchange, thread, run, cursor, false, "continue", false);
    }

    /**
     * DeerFlow moves a Run from pending to running before it publishes {@code metadata}, and the
     * frozen UI reads the Run as soon as it sees that frame. The durable SDK admits a Run as
     * pending, so wait (bounded) for it to leave admission before the first frame is written.
     */
    private void awaitRunStart(RunRecord run) {
        long deadline = System.nanoTime() + RUN_START_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            AgentRunStatus status = run.bridge.snapshot(run.runId).status();
            if (status != AgentRunStatus.PENDING && status != AgentRunStatus.QUEUED) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void streamRunEvents(
            HttpExchange exchange,
            ThreadRecord thread,
            RunRecord run,
            ProjectionCursor initialCursor,
            boolean creator,
            String onDisconnect,
            boolean includeMetadata)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        if (creator) {
            exchange.getResponseHeaders().set(
                    "Content-Location",
                    "/api/threads/" + run.threadId + "/runs/" + run.runId.value());
        }
        if (creator) awaitRunStart(run);
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream output = exchange.getResponseBody()) {
            if (includeMetadata) {
                writeSse(
                        output,
                        "metadata",
                        Map.of("run_id", run.runId.value(), "thread_id", run.threadId),
                        null);
            }
            ProjectionCursor delivered = initialCursor;
            long nextHeartbeat = System.nanoTime() + java.time.Duration.ofMillis(100).toNanos();
            long terminalWithoutEventSince = 0;
            while (true) {
                ReplayResult replay = run.projector.replayAfter(delivered);
                if (replay instanceof ReplayResult.Gap gap) {
                    writeReplayGap(output, run, gap.gap(), gap.authoritativeView(), delivered);
                    return;
                }
                ReplayResult.Events events = (ReplayResult.Events) replay;
                for (ProjectedRunEvent event : events.events()) {
                    delivered = event.cursor();
                    if (writeProjectedEvent(output, thread, run, event)) {
                        return;
                    }
                }
                Optional<AppendResult.Gap> sourceGap = synchronizeOnePage(run);
                if (sourceGap.isPresent()) {
                    AppendResult.Gap gap = sourceGap.orElseThrow();
                    writeReplayGap(output, run, gap.gap(), gap.authoritativeView(), delivered);
                    return;
                }
                if (run.projector.head().equals(delivered)
                        && run.bridge.snapshot(run.runId).status().isTerminal()) {
                    if (!creator && delivered.sequence() == initialCursor.sequence()) {
                        // The observer already acknowledged the durable terminal cursor. Match
                        // DeerFlow's terminal rejoin behavior with an id-less end sentinel.
                        writeSse(output, "end", null, null);
                        return;
                    }
                    if (terminalWithoutEventSince == 0) {
                        terminalWithoutEventSince = System.nanoTime();
                    }
                    if (System.nanoTime() - terminalWithoutEventSince
                            < TERMINAL_EVENT_SETTLE_TIMEOUT.toNanos()) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        continue;
                    }
                    // A public terminal snapshot without the matching durable lifecycle event
                    // would make resume ambiguous. Fail closed instead of inventing an end id.
                    writeSse(
                            output,
                            "error",
                            Map.of("message", "Durable terminal event is unavailable"),
                            null);
                    return;
                }
                if (System.nanoTime() >= nextHeartbeat) {
                    writeHeartbeat(output);
                    nextHeartbeat = System.nanoTime() + java.time.Duration.ofMillis(100).toNanos();
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    run.bridge.cancel(run.runId);
                    break;
                }
            }
        } catch (IOException disconnected) {
            if (creator && "cancel".equals(onDisconnect)) {
                run.bridge.cancel(run.runId);
            }
        } finally {
            synchronized (thread) {
                boolean terminal = run.bridge.snapshot(run.runId).status().isTerminal();
                if (terminal && run.runId.value().equals(thread.activeRunId)) {
                    thread.activeRunId = null;
                }
                thread.updatedAt = Instant.now();
            }
            persistThread(thread);
            trimRunCache();
        }
    }

    private void trimRunCache() {
        List<RunRecord> terminal = runs.values().stream()
                .filter(run -> run.bridge.findRun(run.runId)
                        .map(snapshot -> snapshot.status().isTerminal())
                        .orElse(true))
                .sorted(java.util.Comparator.comparing(run -> run.createdAt))
                .toList();
        int remove = terminal.size() - TERMINAL_RUN_CACHE_LIMIT;
        for (int index = 0; index < remove; index++) {
            RunRecord candidate = terminal.get(index);
            runs.remove(candidate.runId.value(), candidate);
        }
    }

    int cachedRunCount() {
        return runs.size();
    }

    private Optional<AppendResult.Gap> synchronizeOnePage(RunRecord run) {
        synchronized (run) {
            var page = run.bridge.events(run.runId, run.sdkCursor, EVENT_PAGE_SIZE);
            for (AgentRunEvent source : page.items()) {
                AppendResult append = run.projector.append(source);
                if (append instanceof AppendResult.Gap gap) {
                    return Optional.of(gap);
                }
            }
            run.sdkCursor = page.nextCursor();
            return Optional.empty();
        }
    }

    private void synchronizeAllAvailable(RunRecord run) {
        synchronized (run) {
            while (true) {
                var page = run.bridge.events(run.runId, run.sdkCursor, EVENT_PAGE_SIZE);
                for (AgentRunEvent source : page.items()) {
                    AppendResult append = run.projector.append(source);
                    if (append instanceof AppendResult.Gap gap) {
                        throw new EventProjectionException(
                                "durable event feed has a source gap at "
                                        + gap.gap().requestedSequence());
                    }
                }
                run.sdkCursor = page.nextCursor();
                if (!page.hasMore()) {
                    return;
                }
            }
        }
    }

    private ProjectionCursor parseSseCursor(String runId, String rawCursor) {
        if (rawCursor == null) {
            return new ProjectionCursor(runId, 0);
        }
        // The frozen LangGraph SDK uses -1 for the first observer join. Keep the
        // internal cursor non-negative while preserving its before-first meaning.
        if ("-1".equals(rawCursor)) {
            return new ProjectionCursor(runId, 0);
        }
        Matcher matcher = SSE_CURSOR.matcher(rawCursor);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid Last-Event-ID");
        }
        try {
            return new ProjectionCursor(runId, Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException overflow) {
            throw new IllegalArgumentException("Invalid Last-Event-ID", overflow);
        }
    }

    private boolean writeProjectedEvent(
            OutputStream output, ThreadRecord thread, RunRecord run, ProjectedRunEvent event)
            throws IOException {
        String cursor = sseCursor(event.cursor());
        if (event.interaction().isPresent()) {
            var projected = event.interaction().orElseThrow();
            if (projected.state() != InteractionState.PENDING) {
                return false;
            }
            Map<String, Object> requestMessage;
            synchronized (thread) {
                synchronized (run) {
                    if (run.interactionRequestMessage == null) {
                        Optional<InteractionView> pending = run.bridge.pendingInteraction(run.runId);
                        if (pending.isEmpty()
                                || !pending.orElseThrow()
                                        .requestId()
                                        .value()
                                        .equals(projected.requestId())) {
                            writeSse(
                                    output,
                                    "error",
                                    Map.of(
                                            "code", "interaction_view_unavailable",
                                            "request_id", projected.requestId(),
                                            "message", "Pending Interaction view is unavailable"),
                                    cursor);
                            return true;
                        }
                        try {
                            run.interactionRequestMessage =
                                    DeerFlowInteractionHttpProjection.requestMessage(
                                            pending.orElseThrow(), run.runId.value());
                        } catch (IllegalArgumentException unsupported) {
                            writeSse(
                                    output,
                                    "error",
                                    Map.of(
                                            "code", "unsupported_interaction_projection",
                                            "request_id", projected.requestId(),
                                            "message", unsupported.getMessage()),
                                    cursor);
                            return true;
                        }
                        run.interactionRequestId = projected.requestId();
                        run.interactionRequestSequence = run.humanSequence + 1;
                        run.interactionResponseSequence = run.humanSequence + 2;
                        run.assistantSequence = run.humanSequence + 3;
                        thread.nextMessageSequence += 2;
                    } else if (!projected.requestId().equals(run.interactionRequestId)) {
                        writeSse(
                                output,
                                "error",
                                Map.of(
                                        "code", "multiple_interactions_unsupported",
                                        "request_id", projected.requestId(),
                                        "message", "Only one Interaction per Run is supported"),
                                cursor);
                        return true;
                    }
                    requestMessage = run.interactionRequestMessage;
                }
            }
            writeSse(
                    output,
                    "messages",
                    List.of(requestMessage, Map.of("langgraph_node", "tools")),
                    cursor);
            // Frozen HumanInputCard disables itself while useStream is loading. End this
            // transport while the SDK-owned Run remains waiting; the hidden reply reopens the
            // same Run and streams its continuation.
            writeSse(output, "end", null, null);
            return true;
        }
        if (event.source().payload() instanceof RunEventPayloads.ToolLifecycle
                || event.source().payload() instanceof RunEventPayloads.ResourceAvailable
                || event.source().payload() instanceof RunEventPayloads.DeliveryLifecycle
                || event.source().payload() instanceof RunEventPayloads.RunInputLifecycle) {
            if ((event.source().payload() instanceof RunEventPayloads.ToolLifecycle
                            || event.source().payload() instanceof RunEventPayloads.ResourceAvailable)
                    && (!runTools.isEmpty()
                            || run.interactionRequestId != null
                            || run.bridge.pendingInteraction(run.runId).isPresent())) {
                // Approval runs publish tool/checkpoint lifecycle records around the richer
                // Interaction record. The card and authoritative SDK view are the frozen UI
                // surface; these supporting records carry no additional card state.
                return false;
            }
            writeSse(
                    output,
                    "error",
                    Map.of(
                            "code", "unsupported_event_projection",
                            "event_type", event.source().eventType(),
                            "message", "This durable event has no frozen DeerFlow wire mapping"),
                    cursor);
            return true;
        }
        if (!event.terminalKind().terminal()) {
            return false;
        }
        AgentRunSnapshot terminalSnapshot = run.bridge.snapshot(run.runId);
        if (event.terminalKind() == RunTerminalKind.COMPLETED
                && terminalSnapshot.output().isPresent()
                && run.streamModes.contains("messages-tuple")) {
            writeMessageDelta(output, run, terminalSnapshot.output().orElseThrow(), cursor);
        }
        if (event.terminalKind() == RunTerminalKind.FAILED) {
            String message = terminalSnapshot
                    .error()
                    .map(value -> value.message())
                    .orElse("Run failed");
            writeSse(output, "error", Map.of("message", message), null);
        }
        writeSse(output, "end", null, cursor);
        return true;
    }

    private void writeReplayGap(
            OutputStream output,
            RunRecord run,
            ProjectionGap gap,
            AgentRunViewSnapshot authoritativeView,
            ProjectionCursor requested)
            throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", "stream_replay_gap");
        payload.put("run_id", run.runId.value());
        payload.put("requested_event_id", sseCursor(requested));
        payload.put(
                "earliest_available_event_id",
                gap.earliestAvailableSequence() <= gap.latestSequence()
                        ? sseCursor(gap.earliestAvailableSequence())
                        : null);
        payload.put(
                "latest_available_event_id",
                gap.latestSequence() > 0 ? sseCursor(gap.latestSequence()) : null);
        payload.put("recovery", "reload_durable_state");
        payload.put("authoritative_run_view", authoritativeView(authoritativeView));
        writeSse(output, "gap", payload, null);
    }

    private Map<String, Object> authoritativeView(AgentRunViewSnapshot view) {
        AgentRunSnapshot snapshot = view.snapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run_id", snapshot.runId().value());
        result.put("status", deerFlowStatus(snapshot.status()));
        result.put("version", snapshot.version());
        result.put("updated_at", snapshot.updatedAt().toString());
        result.put("output", snapshot.output().orElse(null));
        return result;
    }

    private String sseCursor(ProjectionCursor cursor) {
        return sseCursor(cursor.sequence());
    }

    private String sseCursor(long sequence) {
        return sequence + "-0";
    }

    private void cancelRun(HttpExchange exchange, String threadId, String runId) throws IOException {
        String action = queryValue(exchange, "action").orElse("interrupt");
        String wait = queryValue(exchange, "wait").orElse("false");
        // The frozen JS SDK serializes boolean false as "0" while direct DeerFlow
        // callers commonly use "false"; FastAPI's bool query accepts both.
        if (!action.equals("interrupt") || !Set.of("false", "0").contains(wait)) {
            sendJson(
                    exchange,
                    422,
                    Map.of("detail", "Only wait=false/0&action=interrupt is implemented"));
            return;
        }
        ThreadRecord thread = threads.get(threadId);
        RunRecord run = thread == null ? null : findOrRestoreRun(thread, runId).orElse(null);
        if (run == null) {
            sendJson(exchange, 404, Map.of("detail", "Run " + runId + " not found"));
            return;
        }
        if (!run.bridge.cancel(run.runId)) {
            sendJson(
                    exchange,
                    409,
                    Map.of(
                            "detail",
                            "Run " + runId + " is not cancellable (status: "
                                    + deerFlowStatus(run.bridge.snapshot(run.runId).status()) + ")"));
            return;
        }
        exchange.sendResponseHeaders(202, -1);
        exchange.close();
    }

    private void getRun(HttpExchange exchange, String threadId, String runId) throws IOException {
        ThreadRecord thread = threads.get(threadId);
        RunRecord run = thread == null ? null : findOrRestoreRun(thread, runId).orElse(null);
        if (run == null) {
            sendJson(exchange, 404, Map.of("detail", "Run " + runId + " not found"));
            return;
        }
        sendJson(exchange, 200, runResponse(run));
    }

    private void listRuns(HttpExchange exchange, String threadId) throws IOException {
        ThreadRecord thread = threads.get(threadId);
        if (thread == null) {
            sendJson(exchange, 404, Map.of("detail", "Thread not found"));
            return;
        }
        List<Map<String, Object>> response = durableRuns(thread).stream()
                .map(this::runResponse)
                .toList();
        sendJson(exchange, 200, response);
    }

    private Map<String, Object> runResponse(RunRecord run) {
        AgentRunSnapshot snapshot = run.bridge.snapshot(run.runId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("run_id", run.runId.value());
        response.put("thread_id", run.threadId);
        response.put("assistant_id", run.assistantId);
        response.put("status", deerFlowStatus(snapshot.status()));
        response.put("metadata", Map.of());
        response.put(
                "kwargs",
                Map.of(
                        "input",
                        Map.of(
                                "messages",
                                List.of(message(
                                        "human-" + run.runId.value(),
                                        "human",
                                        run.humanMessage)))));
        response.put("multitask_strategy", "reject");
        response.put("created_at", run.createdAt.toString());
        response.put("updated_at", snapshot.updatedAt().toString());
        response.put("total_input_tokens", snapshot.usage().inputTokens());
        response.put("total_output_tokens", snapshot.usage().outputTokens());
        response.put("total_tokens", snapshot.usage().inputTokens() + snapshot.usage().outputTokens());
        response.put("llm_call_count", snapshot.usage().modelCalls());
        response.put("lead_agent_tokens", snapshot.usage().inputTokens() + snapshot.usage().outputTokens());
        response.put("subagent_tokens", 0);
        response.put("middleware_tokens", 0);
        response.put("message_count", 0);
        response.put("stop_reason", null);
        return response;
    }

    private Map<String, Object> threadStateResponse(ThreadRecord thread) {
        List<ConversationTurn> turns = scanTurns(thread, 200);
        ConversationTurn checkpoint = turns.stream()
                .filter(turn -> turn.role() == io.haifa.agent.core.message.MessageRole.ASSISTANT)
                .reduce((left, right) -> right)
                .orElse(null);
        Map<String, Object> response = checkpoint == null
                ? emptyThreadHistoryResponse(thread, turns)
                : threadHistoryResponse(new HistoryProjection(
                        checkpoint,
                        List.copyOf(turns),
                        turns.stream()
                                .filter(turn -> turn.role()
                                        == io.haifa.agent.core.message.MessageRole.ASSISTANT)
                                .map(ConversationTurn::sequence)
                                .filter(sequence -> sequence < checkpoint.sequence())
                                .max(Long::compareTo)
                                .orElse(null)));
        response.put("values", Map.of("messages", overlaidMessages(thread, turns)));
        response.put("checkpoint", Map.of());
        response.put("tasks", List.of());
        return response;
    }

    private Map<String, Object> emptyThreadHistoryResponse(
            ThreadRecord thread, List<ConversationTurn> turns) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("values", Map.of("messages", turns.stream().map(this::messageForTurn).toList()));
        response.put("next", List.of());
        response.put("metadata", Map.of());
        response.put("checkpoint_id", null);
        response.put("parent_checkpoint_id", null);
        response.put("created_at", thread.updatedAt.toString());
        return response;
    }

    private Map<String, Object> threadHistoryResponse(HistoryProjection projection) {
        ConversationTurn checkpoint = projection.checkpoint();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put(
                "values",
                Map.of(
                        "messages",
                        projection.messages().stream().map(this::messageForTurn).toList()));
        response.put("next", List.of());
        response.put("metadata", Map.of());
        response.put("checkpoint_id", historyCursor(checkpoint.sequence()));
        response.put(
                "parent_checkpoint_id",
                projection.parentSequence() == null
                        ? null
                        : historyCursor(projection.parentSequence()));
        response.put("created_at", checkpoint.createdAt().toString());
        return response;
    }

    private List<HistoryProjection> historyProjections(
            ThreadRecord thread, int limit, Long exclusiveBeforeSequence) {
        if (thread.persistedSessionId == null) return List.of();
        HaifaRunBridge bridge = bridgeForPersistedThread(thread);
        HistoryAccumulator accumulator = new HistoryAccumulator(limit, exclusiveBeforeSequence);
        Optional<ConversationTurnCursor> cursor = Optional.empty();
        int pages = 0;
        while (true) {
            var page = bridge.turns(
                    thread.persistedSessionId,
                    new ConversationTurnQuery(cursor, TURN_SCAN_PAGE_SIZE));
            pages++;
            page.items().forEach(accumulator::accept);
            if (page.nextCursor().isEmpty()) return accumulator.newestFirst();
            requireAnotherScanPage(pages);
            cursor = page.nextCursor();
        }
    }

    private Map<String, Object> messageRow(ConversationTurn turn) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("run_id", turn.runId().map(AgentRunId::value).orElse(null));
        row.put("seq", turn.sequence());
        row.put("content", messageForTurn(turn));
        row.put("metadata", Map.of("caller", "lead_agent"));
        row.put("created_at", turn.createdAt().toString());
        return row;
    }

    private Map<String, Object> messageRow(
            RunRecord run, long sequence, Instant createdAt, Map<String, Object> content) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("run_id", run.runId.value());
        row.put("seq", sequence);
        row.put("content", content);
        row.put("metadata", Map.of("caller", "lead_agent"));
        row.put("created_at", createdAt.toString());
        return row;
    }

    private Map<String, Object> messageForTurn(ConversationTurn turn) {
        String type = turn.role() == io.haifa.agent.core.message.MessageRole.USER ? "human" : "ai";
        Map<String, Object> projected = message(turn.messageId(), type, turn.text());
        turn.runId().ifPresent(runId -> projected.put("run_id", runId.value()));
        return projected;
    }

    private String historyCursor(long sequence) {
        return "turn-" + sequence;
    }

    /**
     * The SDK transcript only gains a Run's user turn once the Run commits it, while DeerFlow
     * checkpoints the human input before the agent starts. Cached Runs still carry what the
     * transcript lacks: the in-flight human message and the Interaction request/response,
     * which belong directly after that Run's human message.
     */
    private List<Map<String, Object>> overlaidMessages(
            ThreadRecord thread, List<ConversationTurn> turns) {
        Map<String, RunRecord> cached = new LinkedHashMap<>();
        runs.values().stream()
                .filter(run -> run.threadId.equals(thread.threadId))
                .sorted((left, right) -> Long.compare(left.humanSequence, right.humanSequence))
                .forEach(run -> cached.put(run.runId.value(), run));
        Set<String> durableHumanRuns = durableHumanRunIds(turns);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ConversationTurn turn : turns) {
            messages.add(messageForTurn(turn));
            if (turn.role() == io.haifa.agent.core.message.MessageRole.USER && turn.runId().isPresent()) {
                RunRecord run = cached.get(turn.runId().orElseThrow().value());
                if (run != null) addInteractionMessages(run, messages);
            }
        }
        for (RunRecord run : cached.values()) {
            if (!isInFlightWithoutDurableHuman(run, durableHumanRuns)) continue;
            messages.add(pendingHumanMessage(run));
            addInteractionMessages(run, messages);
        }
        return messages;
    }

    private void overlayHistoryHead(
            ThreadRecord thread, List<Map<String, Object>> response, int limit) {
        List<ConversationTurn> turns = scanTurns(thread, HISTORY_MESSAGE_RETENTION);
        List<Map<String, Object>> messages = overlaidMessages(thread, turns);
        Set<String> answeredRuns = new HashSet<>();
        for (ConversationTurn turn : turns) {
            if (turn.role() == io.haifa.agent.core.message.MessageRole.ASSISTANT) {
                turn.runId().ifPresent(runId -> answeredRuns.add(runId.value()));
            }
        }
        // A Run whose answer is not yet in the transcript has no durable checkpoint; DeerFlow
        // would already expose its input checkpoint, so project one from the overlay.
        Optional<RunRecord> inFlight = runs.values().stream()
                .filter(run -> run.threadId.equals(thread.threadId))
                .filter(run -> !answeredRuns.contains(run.runId.value()))
                .filter(run -> !run.bridge.snapshot(run.runId).status().isTerminal())
                .max((left, right) -> Long.compare(left.humanSequence, right.humanSequence));
        if (inFlight.isEmpty()) {
            if (response.isEmpty()) return;
            long headSequence = Long.parseLong(
                    ((String) response.getFirst().get("checkpoint_id")).substring("turn-".length()));
            List<ConversationTurn> headTurns = turns.stream()
                    .filter(turn -> turn.sequence() <= headSequence)
                    .toList();
            response.getFirst().put("values", Map.of("messages", overlaidMessages(thread, headTurns)));
            return;
        }
        RunRecord run = inFlight.orElseThrow();
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("values", Map.of("messages", messages));
        head.put("next", List.of());
        head.put("metadata", Map.of());
        head.put("checkpoint_id", historyCursor(run.humanSequence));
        head.put("parent_checkpoint_id", response.isEmpty() ? null : response.getFirst().get("checkpoint_id"));
        head.put("created_at", run.createdAt.toString());
        response.addFirst(head);
        while (response.size() > limit) response.removeLast();
    }

    private Set<String> durableHumanRunIds(List<ConversationTurn> turns) {
        Set<String> result = new HashSet<>();
        for (ConversationTurn turn : turns) {
            if (turn.role() == io.haifa.agent.core.message.MessageRole.USER) {
                turn.runId().ifPresent(runId -> result.add(runId.value()));
            }
        }
        return result;
    }

    private boolean isInFlightWithoutDurableHuman(RunRecord run, Set<String> durableHumanRuns) {
        return !durableHumanRuns.contains(run.runId.value())
                && !run.bridge.snapshot(run.runId).status().isTerminal();
    }

    private Map<String, Object> pendingHumanMessage(RunRecord run) {
        Map<String, Object> message = message("human-" + run.runId.value(), "human", run.humanMessage);
        message.put("run_id", run.runId.value());
        return message;
    }

    private void addInteractionMessages(RunRecord run, List<Map<String, Object>> messages) {
        synchronized (run) {
            if (run.interactionRequestMessage != null) messages.add(run.interactionRequestMessage);
            if (run.acceptedInteractionResponse != null) {
                messages.add(run.acceptedInteractionResponse.message());
            }
        }
    }

    private List<ConversationTurn> scanTurns(ThreadRecord thread, int retention) {
        return scanTurns(thread, retention, null);
    }

    private List<ConversationTurn> scanTurns(
            ThreadRecord thread, int retention, Long exclusiveBeforeSequence) {
        if (thread.persistedSessionId == null) return List.of();
        HaifaRunBridge bridge = bridgeForPersistedThread(thread);
        Deque<ConversationTurn> retained = new ArrayDeque<>(Math.min(retention, 256));
        Optional<ConversationTurnCursor> cursor = Optional.empty();
        int pages = 0;
        while (true) {
            var page = bridge.turns(
                    thread.persistedSessionId,
                    new ConversationTurnQuery(cursor, TURN_SCAN_PAGE_SIZE));
            pages++;
            for (ConversationTurn turn : page.items()) {
                if (turn.role() != io.haifa.agent.core.message.MessageRole.USER
                        && turn.role() != io.haifa.agent.core.message.MessageRole.ASSISTANT) {
                    continue;
                }
                if (exclusiveBeforeSequence != null && turn.sequence() >= exclusiveBeforeSequence) continue;
                retained.addLast(turn);
                while (retained.size() > retention) retained.removeFirst();
            }
            if (page.nextCursor().isEmpty()) break;
            requireAnotherScanPage(pages);
            cursor = page.nextCursor();
        }
        return List.copyOf(retained);
    }

    private List<RunRecord> durableRuns(ThreadRecord thread) {
        HaifaRunBridge bridge = bridgeForPersistedThread(thread);
        List<RunRecord> result = new ArrayList<>();
        for (ConversationTurn turn : newestRunTurns(thread, bridge)) {
            restoreRunFromTurn(thread, turn, bridge).ifPresent(result::add);
        }
        return result;
    }

    private List<ConversationTurn> newestRunTurns(ThreadRecord thread, HaifaRunBridge bridge) {
        if (thread.persistedSessionId == null) return List.of();
        LinkedHashMap<String, ConversationTurn> retained = new LinkedHashMap<>();
        Optional<ConversationTurnCursor> cursor = Optional.empty();
        int pages = 0;
        while (true) {
            var page = bridge.turns(
                    thread.persistedSessionId,
                    new ConversationTurnQuery(cursor, TURN_SCAN_PAGE_SIZE));
            pages++;
            for (ConversationTurn turn : page.items()) {
                if (turn.role() != io.haifa.agent.core.message.MessageRole.USER || turn.runId().isEmpty()) continue;
                String runId = turn.runId().orElseThrow().value();
                retained.remove(runId);
                retained.put(runId, turn);
                while (retained.size() > RUN_LIST_LIMIT) {
                    retained.remove(retained.keySet().iterator().next());
                }
            }
            if (page.nextCursor().isEmpty()) {
                List<ConversationTurn> newestFirst = new ArrayList<>(retained.values());
                Collections.reverse(newestFirst);
                return List.copyOf(newestFirst);
            }
            requireAnotherScanPage(pages);
            cursor = page.nextCursor();
        }
    }

    private Optional<RunRecord> restoreRunFromTurn(
            ThreadRecord thread, ConversationTurn turn, HaifaRunBridge bridge) {
        AgentRunId requested = turn.runId().orElseThrow();
        RunRecord cached = runs.get(requested.value());
        if (cached != null && cached.threadId.equals(thread.threadId)) return Optional.of(cached);
        if (bridge.findRun(requested).isEmpty()) return Optional.empty();
        RunRecord restored = new RunRecord(
                requested,
                thread.threadId,
                null,
                turn.text(),
                bridge,
                turn.createdAt(),
                turn.sequence(),
                false,
                Set.of("messages-tuple"));
        runs.put(requested.value(), restored);
        trimRunCache();
        return Optional.of(restored);
    }

    private Optional<RunRecord> findOrRestoreRun(ThreadRecord thread, String runId) {
        RunRecord cached = runs.get(runId);
        if (cached != null && cached.threadId.equals(thread.threadId)) return Optional.of(cached);
        if (thread.persistedSessionId == null) return Optional.empty();
        HaifaRunBridge bridge = bridgeForPersistedThread(thread);
        AgentRunId requested;
        try {
            requested = new AgentRunId(runId);
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
        Optional<ConversationTurnCursor> cursor = Optional.empty();
        int pages = 0;
        while (true) {
            var page = bridge.turns(
                    thread.persistedSessionId,
                    new ConversationTurnQuery(cursor, TURN_SCAN_PAGE_SIZE));
            pages++;
            for (ConversationTurn turn : page.items()) {
                if (turn.role() == io.haifa.agent.core.message.MessageRole.USER
                        && turn.runId().filter(requested::equals).isPresent()) {
                    return restoreRunFromTurn(thread, turn, bridge);
                }
            }
            if (page.nextCursor().isEmpty()) return Optional.empty();
            requireAnotherScanPage(pages);
            cursor = page.nextCursor();
        }
    }

    static void requireAnotherScanPage(int completedPages) {
        if (completedPages >= MAXIMUM_TURN_SCAN_PAGES) {
            throw new TranscriptScanLimitException();
        }
    }

    void resetTurnPageReads(String threadId) {
        ThreadRecord thread = java.util.Objects.requireNonNull(threads.get(threadId));
        bridgeForPersistedThread(thread).resetTurnPageReads();
    }

    long turnPageReads(String threadId) {
        ThreadRecord thread = java.util.Objects.requireNonNull(threads.get(threadId));
        return bridgeForPersistedThread(thread).turnPageReads();
    }

    boolean hasAttachedBridge(String threadId) {
        ThreadRecord thread = threads.get(threadId);
        return thread != null && thread.bridge != null;
    }

    private void getWorkspaceChanges(HttpExchange exchange, String threadId, String runId) throws IOException {
        boolean includeFiles;
        boolean includeDiff;
        try {
            includeFiles = queryBoolean(exchange, "include_files", true);
            includeDiff = queryBoolean(exchange, "include_diff", true);
        } catch (IllegalArgumentException invalid) {
            sendJson(exchange, 422, Map.of("detail", invalid.getMessage()));
            return;
        }
        RunRecord liveRun = runs.get(runId);
        boolean liveRunVisible = liveRun != null && liveRun.threadId.equals(threadId);
        if (liveRunVisible) {
            completeWorkspaceChangesIfTerminal(liveRun);
        }
        WorkspaceBinding binding;
        try {
            binding = workspaceChanges.requireThreadWorkspace(DeerFlowBootstrapApi.DEFAULT_USER_ID, threadId);
        } catch (WorkspaceException missing) {
            sendJson(exchange, 404, Map.of("detail", "Run " + runId + " not found"));
            return;
        }
        WorkspaceChanges changes;
        try {
            changes = workspaceChanges.readRunChanges(
                    DeerFlowBootstrapApi.DEFAULT_USER_ID, threadId, binding.workspaceId(), runId);
        } catch (WorkspaceException failure) {
            if ((failure.error() == WorkspaceError.RUN_BASELINE_NOT_FOUND
                    || failure.error() == WorkspaceError.RUN_CHANGES_NOT_COMPLETED) && liveRunVisible) {
                sendJson(exchange, 200, emptyWorkspaceChanges());
                return;
            }
            if (failure.error() == WorkspaceError.RUN_BASELINE_NOT_FOUND
                    || failure.error() == WorkspaceError.OWNER_MISMATCH
                    || failure.error() == WorkspaceError.WORKSPACE_NOT_FOUND) {
                sendJson(exchange, 404, Map.of("detail", "Run " + runId + " not found"));
                return;
            }
            sendJson(exchange, 500, Map.of("detail", "Workspace changes are unavailable"));
            return;
        }
        // DeerFlow records no workspace-change event for a completed run whose snapshots
        // are identical, so its read endpoint returns the same unavailable envelope as an
        // active run rather than an available response with an empty file list.
        if (changes.files().isEmpty()) {
            sendJson(exchange, 200, emptyWorkspaceChanges());
            return;
        }
        sendJson(exchange, 200, workspaceChangesResponse(changes, includeFiles, includeDiff));
    }

    private Map<String, Object> emptyWorkspaceChanges() {
        return orderedMap(
                "available", false,
                "version", 1,
                "summary", emptyWorkspaceSummary(false),
                "files", List.of(),
                "limits", Map.of());
    }

    private Map<String, Object> workspaceChangesResponse(
            WorkspaceChanges changes, boolean includeFiles, boolean includeDiff) {
        WorkspaceChangeLimits limits = workspaceChanges.workspaceChangeLimits();
        Set<String> unavailable = changes.diffUnavailablePaths().stream()
                .map(path -> path.value())
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        Set<String> binaryPaths = changes.binaryPaths().stream()
                .map(path -> path.value())
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        Set<String> sensitivePaths = changes.sensitivePaths().stream()
                .map(path -> path.value())
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        String unified = changes.diff().unifiedDiff();
        List<Map<String, Object>> files = new ArrayList<>();
        int created = 0;
        int modified = 0;
        int deleted = 0;
        int additions = 0;
        int deletions = 0;
        boolean large = false;
        for (FileChange change : changes.files()) {
            String status = switch (change.type()) {
                case CREATE -> "created";
                case DELETE -> "deleted";
                case REPLACE, MOVE -> "modified";
            };
            if (change.type() == FileChangeType.CREATE) created++;
            else if (change.type() == FileChangeType.DELETE) deleted++;
            else modified++;
            String logical = change.path().value();
            FileVersion before = change.before();
            FileVersion after = change.after();
            boolean fileLarge = (before != null && before.size() > limits.maxFileBytes())
                    || (after != null && after.size() > limits.maxFileBytes());
            boolean sensitive = sensitivePaths.contains(logical);
            String fragment = diffFragment(unified, logical);
            boolean binary = binaryPath(logical) || binaryPaths.contains(logical);
            String reason = sensitive ? "sensitive"
                    : fileLarge ? "large"
                    : binary ? "binary"
                    : unavailable.contains(logical) ? "truncated"
                    : null;
            if (reason != null) {
                fragment = "";
            }
            WorkspaceLineCounts counts = changes.lineCounts().getOrDefault(
                    change.path(), new WorkspaceLineCounts(0, 0));
            int fileAdditions = counts.additions();
            int fileDeletions = counts.deletions();
            additions += fileAdditions;
            deletions += fileDeletions;
            large |= fileLarge;
            if (includeFiles) {
                Map<String, Object> file = new LinkedHashMap<>();
                file.put("path", "/mnt/user-data/" + logical);
                file.put("root", logical.substring(0, logical.indexOf('/')));
                file.put("status", status);
                file.put("binary", binary);
                file.put("sensitive", sensitive);
                file.put("size_before", before == null ? null : before.size());
                file.put("size_after", after == null ? null : after.size());
                file.put("sha256_before", before == null || sensitive ? null : before.contentHash());
                file.put("sha256_after", after == null || sensitive ? null : after.contentHash());
                file.put("diff", includeDiff ? virtualizeDiff(fragment) : "");
                file.put("diff_truncated", "truncated".equals(reason));
                file.put("diff_unavailable_reason", reason);
                file.put("additions", fileAdditions);
                file.put("deletions", fileDeletions);
                file.put("symlink", false);
                file.put("symlink_target_before", null);
                file.put("symlink_target_after", null);
                files.add(file);
            }
        }
        Map<String, Object> summary = orderedMap(
                "created", created,
                "modified", modified,
                "deleted", deleted,
                "symlink_created", 0,
                "additions", additions,
                "deletions", deletions,
                "truncated", changes.truncated() || large);
        Map<String, Object> wireLimits = orderedMap(
                "max_files", limits.maxFiles(),
                "max_scanned_files", limits.maxFiles(),
                "max_file_bytes_for_diff", limits.maxFileBytes(),
                "max_total_diff_bytes", limits.maxOutputBytes());
        return orderedMap(
                "available", true,
                "version", 1,
                "summary", summary,
                "files", files,
                "limits", wireLimits);
    }

    private static String diffFragment(String unified, String path) {
        String marker = "--- a/" + path + "\n+++ b/" + path + "\n";
        int start = unified.indexOf(marker);
        if (start < 0) return "";
        int next = unified.indexOf("\n--- a/", start + marker.length());
        return next < 0 ? unified.substring(start) : unified.substring(start, next + 1);
    }

    private static String virtualizeDiff(String diff) {
        if (diff.isEmpty()) return diff;
        return diff.replaceFirst("(?m)^--- a/", "--- a/mnt/user-data/")
                .replaceFirst("(?m)^\\+\\+\\+ b/", "+++ b/mnt/user-data/");
    }

    private static boolean binaryPath(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return BINARY_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private static Map<String, Object> emptyWorkspaceSummary(boolean truncated) {
        return orderedMap(
                "created", 0,
                "modified", 0,
                "deleted", 0,
                "symlink_created", 0,
                "additions", 0,
                "deletions", 0,
                "truncated", truncated);
    }

    private boolean queryBoolean(HttpExchange exchange, String name, boolean fallback) {
        Optional<String> value = queryValue(exchange, name);
        if (value.isEmpty()) return fallback;
        if (value.orElseThrow().equals("true")) return true;
        if (value.orElseThrow().equals("false")) return false;
        throw new IllegalArgumentException(name + " must be true or false");
    }

    private static Map<String, Object> orderedMap(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    private Optional<String> validateRunRequest(JsonNode body) {
        JsonNode modes = body.get("stream_mode");
        if (modes != null && !modes.isNull()) {
            List<String> requested = new ArrayList<>();
            if (modes.isTextual()) {
                requested.add(modes.textValue());
            } else if (modes.isArray()) {
                for (JsonNode mode : modes) {
                    if (!mode.isTextual()) return Optional.of("stream_mode entries must be strings");
                    requested.add(mode.textValue());
                }
            } else {
                return Optional.of("stream_mode must be a string or array");
            }
            List<String> unsupported = requested.stream()
                    .filter(mode -> !SUPPORTED_STREAM_MODES.contains(mode))
                    .toList();
            if (!unsupported.isEmpty()) {
                return Optional.of("Unsupported stream mode(s): " + String.join(", ", unsupported));
            }
        }
        if (body.path("stream_resumable").asBoolean(false)) {
            return Optional.of("Run option 'stream_resumable' is not supported");
        }
        String onDisconnect = text(body, "on_disconnect").orElse("cancel");
        if (!onDisconnect.equals("cancel") && !onDisconnect.equals("continue")) {
            return Optional.of("on_disconnect must be 'cancel' or 'continue'");
        }
        String multitask = text(body, "multitask_strategy").orElse("reject");
        if (!multitask.equals("reject")) {
            return Optional.of("Only multitask_strategy='reject' is implemented");
        }
        return Optional.empty();
    }

    private Set<String> requestedStreamModes(JsonNode body) {
        JsonNode modes = body.get("stream_mode");
        if (modes == null || modes.isNull()) return Set.of("messages-tuple");
        if (modes.isTextual()) return Set.of(modes.textValue());
        Set<String> requested = new java.util.LinkedHashSet<>();
        modes.forEach(mode -> requested.add(mode.textValue()));
        return Set.copyOf(requested);
    }

    private Optional<String> queryValue(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(name)) {
                return Optional.of(parts.length == 2 ? parts[1] : "");
            }
        }
        return Optional.empty();
    }

    private Optional<Integer> positiveQueryInteger(
            HttpExchange exchange, String name, int defaultValue, int maximum) {
        Optional<String> raw = queryValue(exchange, name);
        if (raw.isEmpty()) {
            return Optional.of(defaultValue);
        }
        try {
            int value = Integer.parseInt(raw.orElseThrow());
            return value >= 1 && value <= maximum ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException invalid) {
            return Optional.empty();
        }
    }

    private Map<String, Object> threadResponse(ThreadRecord record) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("thread_id", record.threadId);
        response.put("status", threadStatus(record));
        response.put("created_at", record.createdAt.toString());
        response.put("updated_at", record.updatedAt.toString());
        response.put("metadata", record.metadata);
        Object title = record.metadata.get("title");
        response.put("values", title instanceof String ? Map.of("title", title) : Map.of());
        response.put("interrupts", Map.of());
        return response;
    }

    List<Map<String, Object>> listProjectThreads(String projectId, int limit, int offset) {
        return threads.values().stream()
                .filter(record -> projectId.equals(record.metadata.get("deerflow_project_id")))
                .filter(record -> !Boolean.TRUE.equals(record.metadata.get("deerflow_archived")))
                .sorted((left, right) -> right.updatedAt.compareTo(left.updatedAt))
                .skip(offset)
                .limit(limit)
                .map(record -> {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("thread_id", record.threadId);
                    Object displayName = record.metadata.get("title");
                    response.put("display_name", displayName instanceof String ? displayName : null);
                    response.put("created_at", record.createdAt.toString());
                    response.put("updated_at", record.updatedAt.toString());
                    response.put("metadata", record.metadata);
                    return response;
                })
                .toList();
    }

    boolean threadBelongsToProject(String threadId, String projectId) {
        ThreadRecord record = threads.get(threadId);
        return record != null && projectId.equals(record.metadata.get("deerflow_project_id"));
    }

    private String threadStatus(ThreadRecord record) {
        synchronized (record) {
            if (record.activeRunId == null) {
                return "idle";
            }
            RunRecord active = runs.get(record.activeRunId);
            if (active == null || active.bridge.snapshot(active.runId).status().isTerminal()) {
                if (active != null) completeWorkspaceChangesIfTerminal(active);
                record.activeRunId = null;
                record.updatedAt = Instant.now();
                return "idle";
            }
            return "busy";
        }
    }

    private boolean completeWorkspaceChangesIfTerminal(RunRecord run) {
        try {
            if (!run.bridge.snapshot(run.runId).status().isTerminal()) return false;
            if (!run.workspaceRecordingEnabled) return true;
            WorkspaceBinding binding = workspaceChanges.requireThreadWorkspace(
                    DeerFlowBootstrapApi.DEFAULT_USER_ID, run.threadId);
            workspaceChanges.completeRunChanges(
                    DeerFlowBootstrapApi.DEFAULT_USER_ID, run.threadId, binding.workspaceId(), run.runId.value());
            return true;
        } catch (RuntimeException ignored) {
            // Recording is best-effort and must never replace the authoritative Run/SSE outcome.
            return false;
        }
    }

    private void observeWorkspaceCompletion(RunRecord run) {
        if (!run.workspaceRecordingEnabled || closed) return;
        Thread observer = Thread.ofVirtual()
                .name("super-harness-workspace-completion-" + run.runId.value())
                .unstarted(() -> {
                    try {
                        long startedNanos = System.nanoTime();
                        long maxWaitNanos = WORKSPACE_COMPLETION_OBSERVER_MAX_WAIT.toNanos();
                        long pollMillis = WORKSPACE_COMPLETION_OBSERVER_INITIAL_POLL.toMillis();
                        while (!closed
                                && System.nanoTime() - startedNanos < maxWaitNanos
                                && !run.bridge.snapshot(run.runId).status().isTerminal()) {
                            Thread.sleep(pollMillis);
                            pollMillis = nextWorkspaceCompletionPollMillis(pollMillis);
                        }
                        if (closed || System.nanoTime() - startedNanos >= maxWaitNanos) return;
                        for (int attempt = 0; !closed && attempt < 3; attempt++) {
                            if (completeWorkspaceChangesIfTerminal(run)) break;
                            Thread.sleep(50);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException ignored) {
                        // A recorder or snapshot fault cannot mutate the Run result.
                    } finally {
                        workspaceCompletionObservers.remove(Thread.currentThread());
                    }
                });
        workspaceCompletionObservers.add(observer);
        observer.start();
    }

    static long nextWorkspaceCompletionPollMillis(long currentMillis) {
        return Math.min(WORKSPACE_COMPLETION_OBSERVER_MAX_POLL.toMillis(), currentMillis * 2);
    }

    int workspaceCompletionObserverCount() {
        return workspaceCompletionObservers.size();
    }

    private JsonNode readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return json.createObjectNode();
        }
        try {
            JsonNode value = json.readTree(bytes);
            if (value == null || !value.isObject()) {
                throw new MalformedRequestException();
            }
            return value;
        } catch (IOException malformed) {
            throw new MalformedRequestException();
        }
    }

    private String lastHumanMessage(JsonNode messages) {
        if (!messages.isArray()) {
            return "";
        }
        String result = "";
        for (JsonNode message : messages) {
            String type = message.path("type").asText(message.path("role").asText());
            if (type.equals("human") || type.equals("user")) {
                result = humanMessageContent(message.get("content"));
                if (result.isBlank()) {
                    return "";
                }
            }
        }
        return result;
    }

    private String humanMessageContent(JsonNode content) {
        if (content == null) {
            return "";
        }
        if (content.isTextual()) {
            return content.textValue();
        }
        if (!content.isArray() || content.isEmpty()) {
            return "";
        }
        // The frozen UI sends LangChain text content blocks. Match DeerFlow's
        // text projection by concatenating them in order without a separator,
        // but reject the whole message if any block cannot be represented by
        // this text-only Haifa bridge; never coerce or silently drop a block.
        StringBuilder combined = new StringBuilder();
        for (JsonNode block : content) {
            JsonNode blockType = block.get("type");
            JsonNode text = block.get("text");
            if (!block.isObject()
                    || blockType == null
                    || !blockType.isTextual()
                    || !blockType.textValue().equals("text")
                    || text == null
                    || !text.isTextual()
                    || text.textValue().isBlank()) {
                return "";
            }
            combined.append(text.textValue());
        }
        return combined.toString();
    }

    private Optional<String> text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value != null && value.isTextual() ? Optional.of(value.textValue()) : Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(json.convertValue(node, Map.class));
    }

    private void writeSse(OutputStream output, String event, Object data, String id)
            throws IOException {
        StringBuilder frame = new StringBuilder("event: ").append(event).append('\n');
        frame.append("data: ").append(json.writeValueAsString(data)).append('\n');
        if (id != null) {
            frame.append("id: ").append(id).append('\n');
        }
        frame.append('\n');
        output.write(frame.toString().getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void writeHeartbeat(OutputStream output) throws IOException {
        output.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void writeMessageDelta(OutputStream output, RunRecord run, String delta, String id)
            throws IOException {
        if (delta.isEmpty()) {
            return;
        }
        Map<String, Object> messagePayload = new LinkedHashMap<>();
        messagePayload.put("id", "assistant-" + run.runId.value());
        messagePayload.put("type", "ai");
        messagePayload.put("content", delta);
        messagePayload.put("tool_calls", List.of());
        messagePayload.put("additional_kwargs", Map.of());
        writeSse(
                output,
                // LangGraph calls the requested wire mode "messages-tuple", but its
                // platform SSE event is "messages" and the frozen SDK reducer matches
                // that exact name before consuming the tuple payload.
                "messages",
                List.of(messagePayload, Map.of("langgraph_node", "agent")),
                id);
    }

    private Map<String, Object> message(String id, String type, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", id);
        message.put("type", type);
        message.put("content", content);
        message.put("tool_calls", List.of());
        message.put("additional_kwargs", Map.of());
        return message;
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    private boolean isLoopbackPeer(HttpExchange exchange) {
        return exchange.getRemoteAddress() != null
                && exchange.getRemoteAddress().getAddress() != null
                && exchange.getRemoteAddress().getAddress().isLoopbackAddress();
    }

    private String deerFlowStatus(AgentRunStatus status) {
        return switch (status) {
            case PENDING, QUEUED -> "pending";
            case RUNNING, SUSPENDING, COMPLETING -> "running";
            case SUSPENDED, WAITING_INTERACTION, WAITING_APPROVAL -> "interrupted";
            case COMPLETED -> "success";
            case FAILED -> "error";
            // Frozen useStream treats user cancellation as an interrupted run; "cancelled" is
            // not in its terminal-status set and would otherwise trigger a stale reconnect.
            case CANCELLED -> "interrupted";
            case TIMEOUT -> "timeout";
        };
    }

    @Override
    public void close() {
        // A client may consume the final SSE frame before the HTTP handler's
        // finally block publishes its workspace snapshot. Flush terminal runs
        // before interrupting completion observers or closing the SDK.
        runs.values().forEach(this::completeWorkspaceChangesIfTerminal);
        closed = true;
        workspaceCompletionObservers.forEach(Thread::interrupt);
        threads.values().stream()
                .map(thread -> thread.bridge)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .forEach(HaifaRunBridge::close);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        for (Thread observer : List.copyOf(workspaceCompletionObservers)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try {
                observer.join(java.time.Duration.ofNanos(remaining));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        foundation.close();
    }

    private static final class ThreadRecord {
        private final String threadId;
        private final Map<String, Object> metadata;
        private final Instant createdAt;
        private volatile Instant updatedAt;
        private volatile HaifaRunBridge.SessionState session;
        private volatile AgentSessionId persistedSessionId;
        private volatile HaifaRunBridge bridge;
        private volatile String modelBindingIdentity;
        private volatile String activeRunId;
        private volatile String latestRunId;
        private long nextMessageSequence = 1;

        private ThreadRecord(String threadId, Map<String, Object> metadata, Instant now) {
            this.threadId = threadId;
            this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
            this.createdAt = now;
            this.updatedAt = now;
        }

        private ThreadRecord(PersistentThreadCatalog.Entry entry) {
            this.threadId = entry.threadId();
            this.metadata = entry.metadata();
            this.createdAt = entry.createdAt();
            this.updatedAt = entry.updatedAt();
            this.persistedSessionId = entry.sessionId().orElse(null);
            this.modelBindingIdentity = entry.modelBindingIdentity().orElse(null);
        }
    }

    private static final class RunRecord {
        private final AgentRunId runId;
        private final String threadId;
        private final String assistantId;
        private final String humanMessage;
        private final HaifaRunBridge bridge;
        private final BoundedRunEventProjector projector;
        private final Instant createdAt;
        private final long humanSequence;
        private final boolean workspaceRecordingEnabled;
        private long assistantSequence;
        private long interactionRequestSequence;
        private long interactionResponseSequence;
        private volatile String interactionRequestId;
        private Map<String, Object> interactionRequestMessage;
        private AcceptedInteractionResponse acceptedInteractionResponse;
        private final Set<String> streamModes;
        private io.haifa.agent.runtime.api.RunEventCursor sdkCursor;

        private RunRecord(
                AgentRunId runId,
                String threadId,
                String assistantId,
                String humanMessage,
                HaifaRunBridge bridge,
                Instant createdAt,
                long humanSequence,
                boolean workspaceRecordingEnabled,
                Set<String> streamModes) {
            this.runId = runId;
            this.threadId = threadId;
            this.assistantId = assistantId;
            this.humanMessage = humanMessage;
            this.bridge = bridge;
            this.projector = new BoundedRunEventProjector(
                    runId,
                    EVENT_RETENTION_CAPACITY,
                    requestedRunId -> Optional.of(bridge.authoritativeView(requestedRunId)));
            this.sdkCursor = bridge.beforeFirstEvent(runId);
            this.createdAt = createdAt;
            this.humanSequence = humanSequence;
            this.workspaceRecordingEnabled = workspaceRecordingEnabled;
            this.assistantSequence = humanSequence + 1;
            this.streamModes = Set.copyOf(streamModes);
        }
    }

    private record AcceptedInteractionResponse(
            DeerFlowInteractionHttpProjection.FrozenResponse response,
            InteractionResponseSubmission submission,
            Map<String, Object> message) {}

    private static final class MalformedRequestException extends RuntimeException {}

    static final class TranscriptScanLimitException extends RuntimeException {}

    static final class HistoryAccumulator {
        private final int limit;
        private final Long exclusiveBeforeSequence;
        private final Deque<ConversationTurn> messages = new ArrayDeque<>();
        private final Deque<HistoryProjection> checkpoints = new ArrayDeque<>();
        private Long parentSequence;

        HistoryAccumulator(int limit, Long exclusiveBeforeSequence) {
            this.limit = limit;
            this.exclusiveBeforeSequence = exclusiveBeforeSequence;
        }

        void accept(ConversationTurn turn) {
            if (turn.role() != io.haifa.agent.core.message.MessageRole.USER
                    && turn.role() != io.haifa.agent.core.message.MessageRole.ASSISTANT) {
                return;
            }
            if (exclusiveBeforeSequence != null && turn.sequence() >= exclusiveBeforeSequence) return;
            messages.addLast(turn);
            while (messages.size() > HISTORY_MESSAGE_RETENTION) messages.removeFirst();
            if (turn.role() == io.haifa.agent.core.message.MessageRole.ASSISTANT) {
                checkpoints.addLast(new HistoryProjection(turn, List.copyOf(messages), parentSequence));
                while (checkpoints.size() > limit) checkpoints.removeFirst();
                parentSequence = turn.sequence();
            }
        }

        List<HistoryProjection> newestFirst() {
            List<HistoryProjection> result = new ArrayList<>(checkpoints);
            Collections.reverse(result);
            return List.copyOf(result);
        }
    }

    record HistoryProjection(
            ConversationTurn checkpoint,
            List<ConversationTurn> messages,
            Long parentSequence) {}
}

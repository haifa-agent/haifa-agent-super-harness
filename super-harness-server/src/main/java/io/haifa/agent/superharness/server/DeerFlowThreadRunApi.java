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
import io.haifa.agent.runtime.api.RuntimeContractException;
import io.haifa.agent.runtime.api.ToolCallView;
import io.haifa.agent.store.sqlite.SqliteStoreException;
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
import java.sql.SQLException;
import java.time.Duration;
import io.haifa.agent.sdk.api.RunInputCommand;
import io.haifa.agent.sdk.api.RunInputResult;
import io.haifa.agent.sdk.api.RunInputStatus;
import io.haifa.agent.sdk.conversation.ConversationTurn;
import io.haifa.agent.sdk.conversation.ConversationTurnCursor;
import io.haifa.agent.sdk.conversation.ConversationTurnQuery;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;

/** Frozen DeerFlow thread/run compatibility routes backed by the public Haifa Agent SDK. */
final class DeerFlowThreadRunApi implements HttpHandler, AutoCloseable {
    private static final System.Logger INTERACTION_LOG =
            System.getLogger(DeerFlowThreadRunApi.class.getName() + ".interaction");
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
    static final Duration TITLE_DURABILITY_GRACE = Duration.ofSeconds(2);
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
    private final Function<ThreadToolScope, List<? extends JavaTool<?, ?>>> runToolsForThread;
    private final PersistentThreadCatalog threadCatalog;
    private final DurableHaifaFoundation foundation;
    private final Map<String, ThreadRecord> threads = new ConcurrentHashMap<>();
    private final Map<String, RunRecord> runs = new ConcurrentHashMap<>();
    private final Set<Thread> workspaceCompletionObservers = ConcurrentHashMap.newKeySet();
    private final Set<Thread> titleCompletionObservers = ConcurrentHashMap.newKeySet();
    private volatile DurableTurnScanner durableTurnScanner = this::scanPersistedUserTurns;
    private volatile boolean closed;

    DeerFlowThreadRunApi(
            ObjectMapper json,
            Function<JsonNode, HaifaRunBridge.ModelBinding> runModelResolver,
            Predicate<String> projectExists,
            PersistentProjectWorkspaceService workspaceChanges,
            Path dataDirectory,
            SecretKey continuationKey,
            Function<ThreadToolScope, List<? extends JavaTool<?, ?>>> runToolsForThread)
            throws IOException {
        this.json = json;
        this.runModelResolver = runModelResolver;
        this.projectExists = projectExists;
        this.workspaceChanges = workspaceChanges;
        this.runToolsForThread = runToolsForThread;
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
            if (match.matches() && exchange.getRequestMethod().equals("POST")) {
                updateThreadState(exchange, match.group(1));
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

    /**
     * Frozen thread state write used by the rename dialog (`threads.updateState`). Only the
     * {@code values.title} projection is durable here; unknown state fields fail closed and a Run
     * in flight rejects the write exactly like the frozen router.
     */
    private void updateThreadState(HttpExchange exchange, String threadId) throws IOException {
        ThreadRecord thread = threads.get(threadId);
        if (thread == null) {
            sendJson(exchange, 404, Map.of("detail", "Thread not found"));
            return;
        }
        JsonNode body = readBody(exchange);
        for (java.util.Iterator<String> fields = body.fieldNames(); fields.hasNext(); ) {
            String field = fields.next();
            if (!Set.of("values", "checkpoint", "checkpoint_id", "as_node").contains(field)) {
                sendJson(exchange, 422, Map.of("detail", "Unsupported state field '" + field + "'"));
                return;
            }
        }
        JsonNode values = body.get("values");
        if (values == null || values.isNull()) {
            sendJson(exchange, 422, Map.of("detail", "values is required"));
            return;
        }
        if (!values.isObject()) {
            sendJson(exchange, 422, Map.of("detail", "values must be an object"));
            return;
        }
        for (java.util.Iterator<String> fields = values.fieldNames(); fields.hasNext(); ) {
            String field = fields.next();
            if (!field.equals("title")) {
                sendJson(
                        exchange,
                        422,
                        Map.of("detail", "Unsupported thread state value '" + field + "'"));
                return;
            }
        }
        JsonNode title = values.get("title");
        String renamed = null;
        if (title != null && !title.isNull()) {
            if (!title.isTextual()) {
                sendJson(exchange, 422, Map.of("detail", "values.title must be a string"));
                return;
            }
            renamed = title.asText().strip();
            if (renamed.isEmpty()) {
                sendJson(exchange, 422, Map.of("detail", "values.title must be a non-empty string"));
                return;
            }
        }
        // The busy check and the write share the admission lock so a Run cannot start between
        // them; an explicit rename therefore either wins before admission or is rejected with 409.
        final String newTitle = renamed;
        synchronized (thread) {
            if (threadStatus(thread).equals("busy")) {
                sendJson(exchange, 409, Map.of("detail", "Thread has an active run"));
                return;
            }
            if (newTitle != null) {
                thread.setTitle(newTitle);
                thread.updatedAt = Instant.now();
                persistThread(thread);
            }
        }
        sendJson(exchange, 200, threadStateResponse(thread));
    }

    /** Frozen title fallback bound; the configured title model's max_chars is clamped to this. */
    static final int TITLE_FALLBACK_MAX_CHARS = 50;

    /**
     * DeerFlow TitleMiddleware's deterministic local fallback when no title model is configured:
     * the first user message, truncated so the ellipsis is included in the character bound.
     */
    static String fallbackTitle(String message) {
        return fallbackTitle(message, TITLE_FALLBACK_MAX_CHARS);
    }

    static String fallbackTitle(String message, int maxChars) {
        String item = message == null ? "" : message.strip();
        if (item.isEmpty()) {
            return "New Conversation";
        }
        int limit = Math.max(1, Math.min(maxChars, TITLE_FALLBACK_MAX_CHARS));
        // Frozen Python indexes by Unicode code point, not UTF-16 code unit, so supplementary
        // characters (emoji) must count as one character toward the bound.
        int codePoints = item.codePointCount(0, item.length());
        if (codePoints <= limit) {
            return item;
        }
        int body = Math.max(0, limit - 3);
        int end = item.offsetByCodePoints(0, body);
        return item.substring(0, end).stripTrailing() + "...";
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
        Map<Long, Integer> ordinals = computeTurnOrdinals(thread);
        List<Map<String, Object>> response = new ArrayList<>(
                historyProjections(thread, limit, beforeSequence).stream()
                        .map(projection -> threadHistoryResponse(thread, projection, ordinals))
                        .toList());
        if (beforeSequence == null) overlayHistoryHead(thread, response, limit, ordinals);
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
        restorePendingInteractionRuns(thread, durableTurns);
        Map<Long, Integer> ordinals = computeTurnOrdinals(thread);
        List<Map<String, Object>> eligible = new ArrayList<>(
                durableTurns.stream()
                        .map(turn -> messageRow(turn, ordinals.getOrDefault(turn.sequence(), 0)))
                        .toList());
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
        try {
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
        String runInput = exchange.getRequestHeaders().getFirst("X-Haifa-Run-Input");
        if (runInput != null && (!"steer".equals(runInput)
                || body.has("multitask_strategy"))) {
            sendJson(exchange, 422, Map.of("detail", "Invalid Haifa Run input mode"));
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
            if (runInput != null) {
                sendJson(exchange, 422, Map.of("detail", "Haifa Run input mode cannot answer an Interaction"));
                return;
            }
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

        RunRecord run = null;
        RunRecord steerRun = null;
        ProjectionCursor steerCursor = null;
        synchronized (thread) {
            restoreDurableAdmission(thread);
            if (thread.activeRunId != null) {
                RunRecord active = runs.get(thread.activeRunId);
                if (active == null) {
                    active = findOrRestoreRun(thread, thread.activeRunId).orElse(null);
                }
                if (active != null && !active.bridge.snapshot(active.runId).status().isTerminal()) {
                    if (runInput == null) {
                        sendJson(exchange, 409, Map.of("detail", "Thread already has an active run"));
                        return;
                    }
                    ProjectionCursor requestedCursor;
                    try {
                        requestedCursor = parseSseCursor(
                                active.runId.value(), exchange.getRequestHeaders().getFirst("Last-Event-ID"));
                        synchronizeAllAvailable(active);
                        active.projector.replayAfter(requestedCursor);
                    } catch (IllegalArgumentException | EventProjectionException invalid) {
                        sendJson(exchange, 409, Map.of("detail", invalid.getMessage()));
                        return;
                    }
                    String idempotencyKey = extractHumanMessageId(body.path("input").path("messages"))
                            .orElseGet(() -> "steer-" + UUID.randomUUID());
                    RunInputResult result;
                    try {
                        result = active.bridge.submitInput(
                                new RunInputCommand(active.runId, idempotencyKey, message));
                    } catch (RuntimeException failure) {
                        sendJson(exchange, 500, Map.of("detail", "Steer input could not be submitted"));
                        return;
                    }
                    if (result.status() == RunInputStatus.REJECTED) {
                        sendJson(
                                exchange,
                                409,
                                Map.of(
                                        "detail",
                                        "Run is not accepting input: "
                                                + result.reasonCode().orElse("rejected")));
                        return;
                    }
                    steerRun = active;
                    steerCursor = requestedCursor;
                } else if (active != null) {
                    completeWorkspaceChangesIfTerminal(active);
                }
            }
            if (runInput != null && steerRun == null) {
                sendJson(exchange, 409, Map.of("detail", "Thread has no active run to steer"));
                return;
            }
            if (steerRun == null) {
            if (thread.bridge == null) {
                HaifaRunBridge bridge = new HaifaRunBridge(binding, toolsForThread(thread), foundation);
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
            // Frozen TitleMiddleware names the thread only from its first user Turn. The boundary is
            // the in-memory message sequence (seeded from durable turns when a persisted session is
            // restored), so a prior failed turn keeps its slot even if its durable read lags, and no
            // auxiliary durable read can throw out of Run admission and block the main Run.
            boolean titleCandidate = thread.title() == null && thread.nextMessageSequence == 1;
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
                    requestedStreamModes(body),
                    titleCandidate);
            runs.put(run.runId.value(), run);
            persistThread(thread);
            }
        }
        if (steerRun != null) {
            streamRunEvents(
                    exchange,
                    thread,
                    steerRun,
                    steerCursor,
                    false,
                    text(body, "on_disconnect").orElse("continue"),
                    false,
                    false);
            return;
        }
        observeWorkspaceCompletion(run);
        observeTitleCompletion(thread, run);

        try {
            streamRunEvents(
                    exchange,
                    thread,
                    run,
                    new ProjectionCursor(run.runId.value(), 0),
                    true,
                    text(body, "on_disconnect").orElse("cancel"),
                    true,
                    false);
        } finally {
            completeWorkspaceChangesIfTerminal(run);
        }
        } catch (RuntimeException failure) {
            // Once SSE headers have been sent, this exchange cannot carry a JSON response.
            // Never reflect SDK/provider exception text into a client response.
            if (exchange.getResponseCode() < 0) {
                sendJson(exchange, 500, Map.of("detail", "Run stream is unavailable"));
            }
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
        ProjectionCursor responseCursor;
        synchronized (thread) {
            restoreDurableAdmission(thread);
            String candidateRunId = thread.activeRunId != null
                    ? thread.activeRunId
                    : thread.latestRunId;
            run = candidateRunId == null ? null : runs.get(candidateRunId);
            if (run == null) {
                run = durableRuns(thread).stream()
                        .filter(candidate -> candidate.bridge.pendingInteraction(candidate.runId)
                                .filter(view -> view.requestId().value().equals(response.requestId()))
                                .isPresent())
                        .findFirst()
                        .orElse(null);
            }
            if (run == null || !run.threadId.equals(thread.threadId)) {
                sendJson(exchange, 409, Map.of("detail", "Thread has no matching Interaction"));
                return;
            }
            synchronized (run) {
                // A restored Run starts with an empty in-memory projector. Consume its
                // pre-response durable events before accepting the reply, otherwise the
                // old pending card is replayed and prematurely ends the reply stream.
                try {
                    synchronizeAllAvailable(run);
                } catch (EventProjectionException terminalRace) {
                    // The Run reached a terminal state (for example a cancel) while this
                    // response was in flight; the reply can no longer apply and the tool must
                    // not be replayed.
                    sendJson(
                            exchange,
                            409,
                            Map.of("detail", "Run is no longer accepting an Interaction response"));
                    return;
                }
                responseCursor = run.projector.head();
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
                        sendInteractionFailure(exchange, run, "duplicate", rejected);
                        return;
                    }
                    sendJson(exchange, 409, Map.of("detail", "Interaction response already accepted"));
                    return;
                } else {
                    // A terminal Run (including a cancel) can no longer apply an Interaction
                    // response. The SDK may still expose a stale pending view, so reject here
                    // instead of surfacing an opaque 500 and never blindly replaying the tool.
                    if (run.bridge.snapshot(run.runId).status().isTerminal()) {
                        sendJson(
                                exchange,
                                409,
                                Map.of("detail", "Run is no longer accepting an Interaction response"));
                        return;
                    }
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
                    } catch (IllegalArgumentException invalid) {
                        sendJson(exchange, 409, Map.of("detail", "Thread has no matching Interaction"));
                        return;
                    }
                    try {
                        var receipt = run.bridge.respond(submission);
                        if (receipt.status() == InteractionResponseReceiptStatus.DUPLICATE) {
                            sendJson(
                                    exchange,
                                    409,
                                    Map.of("detail", "Interaction response projection is unavailable"));
                            return;
                        }
                    } catch (RuntimeException rejected) {
                        sendInteractionFailure(exchange, run, "first-response", rejected);
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
                    responseCursor,
                    true,
                    onDisconnect,
                    true,
                    false);
        } finally {
            completeWorkspaceChangesIfTerminal(run);
        }
    }

    private void sendInteractionFailure(
            HttpExchange exchange, RunRecord run, String phase, RuntimeException failure)
            throws IOException {
        int status = interactionFailureStatus(failure);
        INTERACTION_LOG.log(
                System.Logger.Level.WARNING,
                "Interaction response failed at " + phase + ": " + interactionFailureReason(failure)
                        + " causes=" + interactionFailureCauses(failure)
                        + " durable=" + interactionDurableState(run));
        String detail = switch (status) {
            case 403 -> "Interaction response is forbidden";
            case 409 -> "Interaction response was rejected";
            case 422 -> "Interaction response is invalid";
            default -> "Interaction response is unavailable";
        };
        sendJson(exchange, status, Map.of("detail", detail));
    }

    static int interactionFailureStatus(RuntimeException failure) {
        if (!(failure instanceof RuntimeContractException contract)) return 500;
        return switch (contract.code()) {
            case APPROVAL_AUTHORITY_DENIED, AUTHENTICATION_REQUIRED -> 403;
            case INTERACTION_ACTION_NOT_ALLOWED, CONTRACT_VERSION_UNSUPPORTED, PAYLOAD_TOO_LARGE -> 422;
            case RUN_STATE_CONFLICT, RUN_VERSION_CONFLICT, IDEMPOTENCY_CONFLICT,
                    INTERACTION_NOT_FOUND, INTERACTION_ALREADY_RESOLVED, INTERACTION_EXPIRED,
                    INTERACTION_REVISION_CONFLICT, APPROVAL_TARGET_STALE -> 409;
            default -> 500;
        };
    }

    static String interactionFailureReason(RuntimeException failure) {
        if (failure instanceof RuntimeContractException contract) return contract.code().name();
        if (failure instanceof SqliteStoreException sqlite) {
            return "SQLITE_" + sqlite.failure().name();
        }
        if (failure instanceof IllegalStateException
                && "previous execution attempt did not pause before resume".equals(failure.getMessage())) {
            return "PREVIOUS_ATTEMPT_NOT_PAUSED";
        }
        return failure.getClass().getSimpleName();
    }

    static String interactionFailureCauses(Throwable failure) {
        List<String> causes = new ArrayList<>();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 6; depth++, current = current.getCause()) {
            if (current instanceof SQLException sql) {
                int code = sql.getErrorCode();
                causes.add(current.getClass().getSimpleName()
                        + "[primary=" + (code & 0xff) + ",extended=" + code + "]");
            } else if (current instanceof SqliteStoreException sqlite) {
                causes.add("SqliteStoreException[" + sqlite.failure().name() + "]");
            } else {
                causes.add(current.getClass().getSimpleName());
            }
        }
        return String.join(">", causes);
    }

    private String interactionDurableState(RunRecord run) {
        try {
            return "run=" + run.bridge.snapshot(run.runId).status().name()
                    + ",pending=" + run.bridge.pendingInteraction(run.runId).isPresent();
        } catch (RuntimeException unavailable) {
            return "unavailable:" + unavailable.getClass().getSimpleName();
        }
    }

    private HaifaRunBridge bridgeForPersistedThread(ThreadRecord thread) {
        synchronized (thread) {
            if (thread.bridge != null) return thread.bridge;
            HaifaRunBridge.ModelBinding binding = runModelResolver.apply(json.createObjectNode());
            // The same published Tool registrations must be available when the SDK resumes
            // an approved Interaction after a process restart. The SDK still owns the
            // pending request and its side-effect admission; this bridge only restores it.
            HaifaRunBridge bridge = new HaifaRunBridge(
                    binding, toolsForThread(thread), foundation);
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

    private List<? extends JavaTool<?, ?>> toolsForThread(ThreadRecord thread) {
        Object projectId = thread.metadata.get("deerflow_project_id");
        Optional<String> project = projectId instanceof String value && !value.isBlank()
                ? Optional.of(value)
                : Optional.empty();
        return runToolsForThread.apply(new ThreadToolScope(thread.threadId, project));
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
        streamRunEvents(exchange, thread, run, cursor, false, "continue", false, true);
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
            boolean includeMetadata,
            boolean reemitPendingCard)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        // Every stream response is already bound to this authoritative RunRecord. Expose the
        // same canonical identity for creator, Steer observer and reconnect/joiner responses;
        // never derive a Run id from request input.
        exchange.getResponseHeaders().set(
                "Content-Location",
                "/api/threads/" + run.threadId + "/runs/" + run.runId.value());
        if (creator) awaitRunStart(run);
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream output = exchange.getResponseBody()) {
            writeCommittedSse(output, () -> {
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
                    if (reemitPendingCard && run.projector.head().equals(delivered)) {
                        // A reconnect can catch up past the durable interaction event (or the
                        // event left the bounded replay window). Re-project the SDK-authored
                        // pending card instead of leaving the client with a heartbeat-only
                        // stream that never observes the waiting Interaction.
                        Optional<InteractionView> pending = run.bridge.pendingInteraction(run.runId);
                        if (pending.isPresent()
                                && (run.interactionRequestId == null
                                        || run.interactionRequestId.equals(
                                                pending.orElseThrow().requestId().value()))) {
                            if (writePendingInteractionCard(
                                    output,
                                    thread,
                                    run,
                                    pending.orElseThrow().requestId().value(),
                                    sseCursor(delivered))) {
                                return;
                            }
                        }
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
            });
        } catch (IOException disconnected) {
            if (creator && "cancel".equals(onDisconnect)) {
                run.bridge.cancel(run.runId);
            }
        } finally {
            synchronized (thread) {
                boolean terminal = isKnownTerminal(() -> Optional.of(run.bridge.snapshot(run.runId)));
                if (terminal && run.runId.value().equals(thread.activeRunId)) {
                    thread.activeRunId = null;
                }
                thread.updatedAt = Instant.now();
            }
            persistThread(thread);
            trimRunCache();
        }
    }

    @FunctionalInterface
    interface SseFrames {
        void write() throws IOException;
    }

    void writeCommittedSse(OutputStream output, SseFrames frames) throws IOException {
        try {
            frames.write();
        } catch (RuntimeException failure) {
            // HTTP 200/SSE is already committed. Do not attempt JSON or expose SDK/provider text.
            writeSse(output, "error", Map.of("message", "Run stream is unavailable"), null);
        }
    }

    private void trimRunCache() {
        List<RunRecord> terminal = runs.values().stream()
                .filter(run -> isKnownTerminal(() -> run.bridge.findRun(run.runId)))
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

    static boolean isKnownTerminal(Supplier<Optional<AgentRunSnapshot>> lookup) {
        try {
            return lookup.get().map(snapshot -> snapshot.status().isTerminal()).orElse(false);
        } catch (RuntimeException unavailable) {
            // Unknown is not terminal: preserve active admission and hot cache until durable state is known.
            return false;
        }
    }

    private Optional<AppendResult.Gap> synchronizeOnePage(RunRecord run) {
        synchronized (run) {
            var page = run.bridge.events(run.runId, run.sdkCursor, EVENT_PAGE_SIZE);
            for (AgentRunEvent source : page.items()) {
                AppendResult append = run.projector.append(source);
                if (append instanceof AppendResult.Gap gap) {
                    return Optional.of(gap);
                }
                if ("completion.deferred".equals(source.eventType())) {
                    run.deferredEventSequences.add(source.sequence());
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
                    if ("completion.deferred".equals(source.eventType())) {
                        run.deferredEventSequences.add(source.sequence());
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
            return writePendingInteractionCard(output, thread, run, projected.requestId(), cursor);
        }
        if (event.source().payload() instanceof RunEventPayloads.RunInputLifecycle) {
            return false;
        }
        if ("completion.deferred".equals(event.source().eventType())) {
            if (run.streamModes.contains("messages-tuple")) {
                int ordinal = (int) run.deferredEventSequences.stream()
                        .filter(seq -> seq < event.source().sequence())
                        .count();
                Optional<String> supersededOutput = findAssistantTurnText(thread, run.runId, ordinal);
                if (supersededOutput.isEmpty()) {
                    // A replay must never attach a different answer to a stable message ID.
                    writeSse(output, "error", Map.of(
                            "code", "durable_assistant_turn_unavailable",
                            "message", "The durable assistant turn for this event is unavailable"), cursor);
                    return true;
                }
                if (!supersededOutput.orElseThrow().isEmpty()) {
                    writeMessageDelta(output, run, supersededOutput.orElseThrow(), cursor, ordinal);
                }
            }
            return false;
        }
        if (event.source().payload() instanceof RunEventPayloads.ToolLifecycle
                || event.source().payload() instanceof RunEventPayloads.ResourceAvailable
                || event.source().payload() instanceof RunEventPayloads.DeliveryLifecycle) {
            if (event.source().payload() instanceof RunEventPayloads.ToolLifecycle lifecycle
                    && run.bridge.hasTools()) {
                writeToolLifecycle(output, run, lifecycle.toolCallId(), cursor);
                return false;
            }
            if (event.source().payload() instanceof RunEventPayloads.ResourceAvailable
                    && (run.bridge.hasTools()
                            || run.interactionRequestId != null
                            || run.bridge.pendingInteraction(run.runId).isPresent())) {
                // The SDK Tool result is the authoritative Artifact projection. The supporting
                // resource event does not carry full arguments/result and must not create a
                // second card.
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
        if (run.bridge.hasTools() && run.streamModes.contains("messages-tuple")) {
            writeAllToolMessages(output, run);
            List<String> artifacts = DeerFlowToolTranscriptProjection.artifactPaths(
                    run.bridge.toolCalls(run.runId));
            if (!artifacts.isEmpty() && run.streamModes.contains("updates")) {
                writeSse(output, "updates", Map.of("__artifacts__", Map.of("artifacts", artifacts)), null);
            }
        }
        if (event.terminalKind() == RunTerminalKind.COMPLETED
                && terminalSnapshot.output().isPresent()
                && run.streamModes.contains("messages-tuple")) {
            int finalAssistantOrdinal = run.deferredEventSequences.size();
            writeMessageDelta(
                    output, run, terminalSnapshot.output().orElseThrow(), cursor, finalAssistantOrdinal);
        }
        if (event.terminalKind() == RunTerminalKind.FAILED) {
            String message = terminalSnapshot
                    .error()
                    .map(value -> value.message())
                    .orElse("Run failed");
            writeSse(output, "error", Map.of("message", message), null);
        }
        // The frozen UI refreshes thread metadata as soon as this stream ends. Finalize the
        // fallback before publishing the title/end frames, otherwise that refresh can race the
        // independent completion observer and cache an "Untitled" thread for this page lifetime.
        // The observer still covers disconnects and streams without the updates mode.
        if (run.titleCandidate
                && run.streamModes.contains("updates")
                && shouldGenerateFallbackTitle(
                        terminalSnapshot.status(), terminalSnapshot.output().isPresent())) {
            finalizeFallbackTitle(thread, run, terminalSnapshot);
            // A failed/lagging durable user-Turn read must not advertise a title that the
            // thread metadata does not actually contain.
            if (thread.title() != null) {
                maybePublishTitle(output, thread, run);
            }
        }
        writeSse(output, "end", null, cursor);
        return true;
    }

    /**
     * Emits the SDK-authoritative pending approval card exactly once for this transport and ends it.
     * A reconnect can join after the durable interaction event has left the bounded replay window (or
     * from a cursor past it), so the card must be rebuilt from the public SDK pending view instead of
     * assuming the interaction event itself will be replayed. The SDK still owns the pending request;
     * this only re-projects it.
     */
    private boolean writePendingInteractionCard(
            OutputStream output, ThreadRecord thread, RunRecord run, String projectedRequestId, String cursor)
            throws IOException {
        Map<String, Object> requestMessage;
        synchronized (thread) {
            synchronized (run) {
                if (run.interactionRequestMessage == null) {
                    Optional<InteractionView> pending = run.bridge.pendingInteraction(run.runId);
                    if (pending.isEmpty()
                            || !pending.orElseThrow()
                                    .requestId()
                                    .value()
                                    .equals(projectedRequestId)) {
                        writeSse(
                                output,
                                "error",
                                Map.of(
                                        "code", "interaction_view_unavailable",
                                        "request_id", projectedRequestId,
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
                                        "request_id", projectedRequestId,
                                        "message", unsupported.getMessage()),
                                cursor);
                        return true;
                    }
                    run.interactionRequestId = projectedRequestId;
                    run.interactionRequestSequence = run.humanSequence + 1;
                    run.interactionResponseSequence = run.humanSequence + 2;
                    run.assistantSequence = run.humanSequence + 3;
                    thread.nextMessageSequence += 2;
                } else if (!projectedRequestId.equals(run.interactionRequestId)) {
                    writeSse(
                            output,
                            "error",
                            Map.of(
                                    "code", "multiple_interactions_unsupported",
                                    "request_id", projectedRequestId,
                                    "message", "Only one Interaction per Run is supported"),
                            cursor);
                    return true;
                }
                requestMessage = run.interactionRequestMessage;
            }
        }
        writeToolRequestIfAvailable(output, run, null);
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

    private void writeToolLifecycle(OutputStream output, RunRecord run, String toolCallId, String cursor)
            throws IOException {
        Optional<ToolCallView> selected = run.bridge.toolCalls(run.runId).stream()
                .filter(call -> call.id().value().equals(toolCallId))
                .findFirst();
        if (selected.isEmpty()) {
            writeSse(
                    output,
                    "error",
                    Map.of(
                            "code", "tool_call_view_unavailable",
                            "tool_call_id", toolCallId,
                            "message", "Authoritative Tool call view is unavailable"),
                    cursor);
            return;
        }
        ToolCallView call = selected.orElseThrow();
        writeToolMessage(output, run, DeerFlowToolTranscriptProjection.requestMessage(call), null);
        Optional<Map<String, Object>> result = DeerFlowToolTranscriptProjection.resultMessage(call);
        if (result.isPresent()) {
            writeToolMessage(output, run, result.orElseThrow(), cursor);
        }
    }

    private void writeToolRequestIfAvailable(OutputStream output, RunRecord run, String cursor) throws IOException {
        List<ToolCallView> calls = run.bridge.toolCalls(run.runId);
        String targetReference = interactionTargetReference(run);
        Optional<ToolCallView> selected = calls.stream()
                .filter(call -> call.id().value().equals(targetReference))
                .findFirst();
        if (selected.isEmpty() && calls.size() == 1) {
            selected = Optional.of(calls.getFirst());
        }
        if (selected.isPresent()) {
            writeToolMessage(
                    output,
                    run,
                    DeerFlowToolTranscriptProjection.requestMessage(selected.orElseThrow()),
                    cursor);
        }
    }

    private String interactionTargetReference(RunRecord run) {
        return run.bridge.pendingInteraction(run.runId)
                .map(interaction -> interaction.target().reference())
                .orElse("");
    }

    private void writeAllToolMessages(OutputStream output, RunRecord run) throws IOException {
        for (Map<String, Object> message : DeerFlowToolTranscriptProjection.messages(
                run.bridge.toolCalls(run.runId))) {
            writeToolMessage(output, run, message, null);
        }
    }

    private void writeToolMessage(
            OutputStream output, RunRecord run, Map<String, Object> message, String cursor) throws IOException {
        String messageId = (String) message.get("id");
        if (!run.projectedToolMessageIds.add(messageId)) return;
        writeSse(output, "messages", List.of(message, Map.of("langgraph_node", "tools")), cursor);
    }

    private void maybePublishTitle(OutputStream output, ThreadRecord thread, RunRecord run)
            throws IOException {
        String title = thread.title();
        if (title == null) {
            title = fallbackTitle(run.humanMessage);
        }
        writeSse(output, "updates", Map.of("__title__", Map.of("title", title)), null);
    }

    /**
     * Frozen {@code TitleMiddleware._should_generate_title}: a completed first exchange with an
     * assistant answer, or (with {@code allow_partial_exchange=True}) an interrupted first turn
     * whose lone user message was persisted. Failed and timed-out runs never gain a fallback title.
     */
    static boolean shouldGenerateFallbackTitle(AgentRunStatus status, boolean hasOutput) {
        if (status == AgentRunStatus.COMPLETED) {
            return hasOutput;
        }
        return status == AgentRunStatus.CANCELLED;
    }

    /**
     * The terminal-status rule is necessary but not sufficient: the frozen middleware also requires
     * exactly one persisted user Turn. {@code run.titleCandidate} already asserts the thread had no
     * durable user Turn at admission; this confirms the current Run's lone user Turn actually
     * landed before naming the thread. The SSE frame uses only the admission boundary so a lagging
     * durable feed cannot drop the projection, while persistence waits out that lag here.
     */
    private boolean generatedFromLoneDurableUserTurn(ThreadRecord thread, RunRecord run) {
        if (!run.titleCandidate) {
            return false;
        }
        long deadline = System.nanoTime() + TITLE_DURABILITY_GRACE.toNanos();
        while (true) {
            int durableUserTurns;
            try {
                durableUserTurns = durableTurnScanner.scan(thread.threadId);
            } catch (RuntimeException failure) {
                // The auxiliary durable read may lag or fail like any SQLite/SDK read. Naming the
                // thread is optional, so fail conservative without leaking input text or blocking.
                logTitleDiagnostic("W03_TITLE_TURN_READ_FAILURE", run.runId.value(), failure);
                return false;
            }
            if (durableUserTurns == 1) {
                return true;
            }
            if (durableUserTurns > 1 || System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private int scanPersistedUserTurns(String threadId) {
        ThreadRecord thread = threads.get(threadId);
        return thread == null ? 0 : persistedUserTurnCount(thread);
    }

    @FunctionalInterface
    interface DurableTurnScanner {
        int scan(String threadId);
    }

    /** Package-private seam so a controlled test can force the auxiliary durable turn read to fail. */
    void setDurableTurnScannerForTests(DurableTurnScanner scanner) {
        this.durableTurnScanner = scanner == null ? this::scanPersistedUserTurns : scanner;
    }

    static void logTitleDiagnostic(String code, String runId, RuntimeException failure) {
        // A stable server code, the server-generated run id and the error class only. The client
        // controls thread_id (createThread permits CR/LF), so echoing it would allow log
        // injection; the exception message, request text, credential and provider body are also
        // excluded. run_id is SDK-generated and format-controlled.
        System.err.println(code + " run_id=" + runId + " error=" + failure.getClass().getName());
    }

    /** Bounded count of durable USER turns; stops as soon as a second turn proves the first-turn boundary is gone. */
    private int persistedUserTurnCount(ThreadRecord thread) {
        if (thread.persistedSessionId == null) {
            return 0;
        }
        HaifaRunBridge bridge = bridgeForPersistedThread(thread);
        Optional<ConversationTurnCursor> cursor = Optional.empty();
        int pages = 0;
        int count = 0;
        while (true) {
            var page = bridge.turns(
                    thread.persistedSessionId,
                    new ConversationTurnQuery(cursor, TURN_SCAN_PAGE_SIZE));
            pages++;
            for (ConversationTurn turn : page.items()) {
                if (turn.role() == io.haifa.agent.core.message.MessageRole.USER) {
                    count++;
                    if (count > 1) {
                        return count;
                    }
                }
            }
            if (page.nextCursor().isEmpty()) {
                return count;
            }
            requireAnotherScanPage(pages);
            cursor = page.nextCursor();
        }
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
                                        wireMessageId(run.runId.value(), "human", 0),
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
        Map<Long, Integer> ordinals = computeTurnOrdinals(thread);
        ConversationTurn checkpoint = turns.stream()
                .filter(turn -> turn.role() == io.haifa.agent.core.message.MessageRole.ASSISTANT)
                .reduce((left, right) -> right)
                .orElse(null);
        Map<String, Object> response = checkpoint == null
                ? emptyThreadHistoryResponse(thread, turns, ordinals)
                : threadHistoryResponse(thread, new HistoryProjection(
                        checkpoint,
                        List.copyOf(turns),
                        turns.stream()
                                .filter(turn -> turn.role()
                                        == io.haifa.agent.core.message.MessageRole.ASSISTANT)
                                .map(ConversationTurn::sequence)
                                .filter(sequence -> sequence < checkpoint.sequence())
                                .max(Long::compareTo)
                                .orElse(null)), ordinals);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("messages", overlaidMessages(thread, turns, ordinals, true));
        values.put("artifacts", artifactPaths(thread, turns, true));
        if (thread.title() != null) {
            values.put("title", thread.title());
        }
        response.put("values", values);
        response.put("checkpoint", Map.of());
        response.put("tasks", List.of());
        return response;
    }

    static String wireMessageId(String runId, String type, int ordinal) {
        return runId + "-" + type + "-" + ordinal;
    }

    private Map<Long, Integer> computeTurnOrdinals(ThreadRecord thread) {
        if (thread.persistedSessionId == null) return Map.of();
        HaifaRunBridge bridge = bridgeForPersistedThread(thread);
        // Wire IDs are per-Run ordinals, not ordinals within the requested page or retained
        // state window. Scan from the first durable turn under the existing 100-page cap.
        Map<String, Integer> counts = new HashMap<>();
        Map<Long, Integer> ordinals = new HashMap<>();
        Optional<ConversationTurnCursor> cursor = Optional.empty();
        int pages = 0;
        while (true) {
            var page = bridge.turns(thread.persistedSessionId, new ConversationTurnQuery(cursor, TURN_SCAN_PAGE_SIZE));
            pages++;
            for (ConversationTurn turn : page.items()) {
                if (turn.role() != io.haifa.agent.core.message.MessageRole.USER
                        && turn.role() != io.haifa.agent.core.message.MessageRole.ASSISTANT) continue;
                String key = turn.runId().map(AgentRunId::value).orElse("") + ":" + turn.role();
                int ordinal = counts.getOrDefault(key, 0);
                counts.put(key, ordinal + 1);
                ordinals.put(turn.sequence(), ordinal);
            }
            if (page.nextCursor().isEmpty()) return ordinals;
            requireAnotherScanPage(pages);
            cursor = page.nextCursor();
        }
    }

    private Optional<String> findAssistantTurnText(ThreadRecord thread, AgentRunId runId, int ordinal) {
        if (thread.persistedSessionId == null) return Optional.empty();
        HaifaRunBridge bridge = bridgeForPersistedThread(thread);
        Optional<ConversationTurnCursor> cursor = Optional.empty();
        int currentOrdinal = 0;
        int pages = 0;
        while (true) {
            var page = bridge.turns(thread.persistedSessionId, new ConversationTurnQuery(cursor, TURN_SCAN_PAGE_SIZE));
            pages++;
            for (ConversationTurn turn : page.items()) {
                if (turn.role() == io.haifa.agent.core.message.MessageRole.ASSISTANT
                        && turn.runId().filter(runId::equals).isPresent()) {
                    if (currentOrdinal == ordinal) return Optional.of(turn.text());
                    currentOrdinal++;
                }
            }
            if (page.nextCursor().isEmpty()) return Optional.empty();
            requireAnotherScanPage(pages);
            cursor = page.nextCursor();
        }
    }

    private Optional<String> extractHumanMessageId(JsonNode messages) {
        if (!messages.isArray()) return Optional.empty();
        for (int index = messages.size() - 1; index >= 0; index--) {
            JsonNode message = messages.get(index);
            String type = message.path("type").asText(message.path("role").asText());
            if (type.equals("human") || type.equals("user")) {
                String id = message.path("id").asText();
                if (!id.isBlank()) return Optional.of(id);
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private Map<String, Object> emptyThreadHistoryResponse(
            ThreadRecord thread, List<ConversationTurn> turns, Map<Long, Integer> ordinals) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("values", Map.of(
                "messages", overlaidMessages(thread, turns, ordinals, true),
                "artifacts", artifactPaths(thread, turns, true)));
        response.put("next", List.of());
        response.put("metadata", Map.of());
        response.put("checkpoint_id", null);
        response.put("parent_checkpoint_id", null);
        response.put("created_at", thread.updatedAt.toString());
        return response;
    }

    private Map<String, Object> threadHistoryResponse(
            ThreadRecord thread, HistoryProjection projection, Map<Long, Integer> ordinals) {
        ConversationTurn checkpoint = projection.checkpoint();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("values", Map.of(
                "messages", overlaidMessages(thread, projection.messages(), ordinals, false),
                "artifacts", artifactPaths(thread, projection.messages(), false)));
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

    private Map<String, Object> messageRow(ConversationTurn turn, int ordinal) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("run_id", turn.runId().map(AgentRunId::value).orElse(null));
        row.put("seq", turn.sequence());
        row.put("content", messageForTurn(turn, ordinal));
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

    private Map<String, Object> messageForTurn(ConversationTurn turn, int ordinal) {
        String type = turn.role() == io.haifa.agent.core.message.MessageRole.USER ? "human" : "ai";
        String id = turn.runId()
                .map(runId -> wireMessageId(runId.value(), type, ordinal))
                .orElseGet(turn::messageId);
        Map<String, Object> projected = message(id, type, turn.text());
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
            ThreadRecord thread,
            List<ConversationTurn> turns,
            Map<Long, Integer> ordinals,
            boolean includeInFlight) {
        restorePendingInteractionRuns(thread, turns);
        Map<String, RunRecord> cached = new LinkedHashMap<>();
        runs.values().stream()
                .filter(run -> run.threadId.equals(thread.threadId))
                .sorted((left, right) -> Long.compare(left.humanSequence, right.humanSequence))
                .forEach(run -> cached.put(run.runId.value(), run));
        Set<String> durableHumanRuns = durableHumanRunIds(turns);
        Set<String> supportProjectedRuns = new HashSet<>();
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ConversationTurn turn : turns) {
            int ordinal = ordinals.getOrDefault(turn.sequence(), 0);
            messages.add(messageForTurn(turn, ordinal));
            if (turn.role() == io.haifa.agent.core.message.MessageRole.USER
                    && turn.runId().isPresent()
                    && supportProjectedRuns.add(turn.runId().orElseThrow().value())) {
                RunRecord run = cached.get(turn.runId().orElseThrow().value());
                addRunSupportMessages(thread, turn.runId().orElseThrow(), run, messages);
            }
        }
        if (includeInFlight) {
            for (RunRecord run : cached.values()) {
                if (!isInFlightWithoutDurableHuman(run, durableHumanRuns)) continue;
                messages.add(pendingHumanMessage(run));
                if (supportProjectedRuns.add(run.runId.value())) {
                    addRunSupportMessages(thread, run.runId, run, messages);
                }
            }
        }
        return messages;
    }

    private void addRunSupportMessages(
            ThreadRecord thread,
            AgentRunId runId,
            RunRecord cached,
            List<Map<String, Object>> messages) {
        List<ToolCallView> calls = cached == null
                ? bridgeForPersistedThread(thread).toolCalls(runId)
                : cached.bridge.toolCalls(runId);
        calls.stream().map(DeerFlowToolTranscriptProjection::requestMessage).forEach(messages::add);
        if (cached != null) addInteractionMessages(cached, messages);
        calls.stream()
                .map(DeerFlowToolTranscriptProjection::resultMessage)
                .flatMap(Optional::stream)
                .forEach(messages::add);
    }

    private List<String> artifactPaths(
            ThreadRecord thread, List<ConversationTurn> turns, boolean includeInFlight) {
        LinkedHashMap<String, AgentRunId> runIds = new LinkedHashMap<>();
        for (ConversationTurn turn : turns) {
            turn.runId().ifPresent(runId -> runIds.putIfAbsent(runId.value(), runId));
        }
        if (includeInFlight) {
            runs.values().stream()
                    .filter(run -> run.threadId.equals(thread.threadId))
                    .forEach(run -> runIds.putIfAbsent(run.runId.value(), run.runId));
        }
        if (runIds.isEmpty()) return List.of();
        HaifaRunBridge bridge = bridgeForPersistedThread(thread);
        return runIds.values().stream()
                .flatMap(runId -> DeerFlowToolTranscriptProjection.artifactPaths(bridge.toolCalls(runId)).stream())
                .distinct()
                .toList();
    }

    private void overlayHistoryHead(
            ThreadRecord thread, List<Map<String, Object>> response, int limit, Map<Long, Integer> ordinals) {
        List<ConversationTurn> turns = scanTurns(thread, HISTORY_MESSAGE_RETENTION);
        List<Map<String, Object>> messages = overlaidMessages(thread, turns, ordinals, true);
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
            response.getFirst().put("values", Map.of(
                    "messages", overlaidMessages(thread, headTurns, ordinals, false),
                    "artifacts", artifactPaths(thread, headTurns, false)));
            return;
        }
        RunRecord run = inFlight.orElseThrow();
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("values", Map.of(
                "messages", messages,
                "artifacts", artifactPaths(thread, turns, true)));
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
        Map<String, Object> message = message(
                wireMessageId(run.runId.value(), "human", 0), "human", run.humanMessage);
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

    private void restoreDurableAdmission(ThreadRecord thread) {
        if (thread.admissionRecovered) return;
        if (thread.persistedSessionId == null) {
            thread.admissionRecovered = true;
            return;
        }
        List<RunRecord> active = durableRuns(thread).stream()
                .filter(run -> !run.bridge.snapshot(run.runId).status().isTerminal())
                .toList();
        if (active.size() > 1) {
            throw new IllegalStateException("Multiple durable active Runs require manual resolution");
        }
        if (!active.isEmpty()) {
            thread.activeRunId = active.getFirst().runId.value();
            thread.latestRunId = thread.activeRunId;
        }
        thread.admissionRecovered = true;
    }

    private void restorePendingInteractionRuns(ThreadRecord thread, List<ConversationTurn> turns) {
        if (thread.persistedSessionId == null) return;
        HaifaRunBridge bridge = bridgeForPersistedThread(thread);
        for (ConversationTurn turn : turns) {
            if (turn.role() != io.haifa.agent.core.message.MessageRole.USER || turn.runId().isEmpty()) continue;
            AgentRunId runId = turn.runId().orElseThrow();
            if (runs.containsKey(runId.value())) continue;
            if (bridge.pendingInteraction(runId).isPresent()) {
                restoreRunFromTurn(thread, turn, bridge);
            }
        }
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
                Set.of("messages-tuple"),
                false);
        bridge.pendingInteraction(requested).ifPresent(pending -> {
            try {
                restored.interactionRequestMessage =
                        DeerFlowInteractionHttpProjection.requestMessage(pending, requested.value());
                restored.interactionRequestId = pending.requestId().value();
                restored.interactionRequestSequence = restored.humanSequence + 1;
                restored.interactionResponseSequence = restored.humanSequence + 2;
                restored.assistantSequence = restored.humanSequence + 3;
            } catch (IllegalArgumentException unsupported) {
                // The public SDK view is still authoritative. An unsupported card is
                // reported by the SSE/response path, never manufactured in history.
            }
        });
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

    void unbindProject(String projectId) throws IOException {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        record ThreadSnapshot(ThreadRecord record, Map<String, Object> metadata, Instant updatedAt) {}
        List<ThreadSnapshot> modified = new ArrayList<>();
        try {
            for (ThreadRecord record : threads.values()) {
                synchronized (record) {
                    if (projectId.equals(record.metadata.get("deerflow_project_id"))
                            || projectId.equals(record.metadata.get("project_id"))) {
                        modified.add(new ThreadSnapshot(record, record.metadata, record.updatedAt));
                        Map<String, Object> updated = new LinkedHashMap<>(record.metadata);
                        updated.remove("deerflow_project_id");
                        updated.remove("project_id");
                        record.metadata = Collections.unmodifiableMap(updated);
                        record.updatedAt = Instant.now();
                        persistThread(record);
                    }
                }
            }
        } catch (Exception failure) {
            for (int i = modified.size() - 1; i >= 0; i--) {
                ThreadSnapshot snap = modified.get(i);
                synchronized (snap.record) {
                    snap.record.metadata = snap.metadata;
                    snap.record.updatedAt = snap.updatedAt;
                    try {
                        persistThread(snap.record);
                    } catch (Exception rollbackError) {
                        INTERACTION_LOG.log(System.Logger.Level.WARNING,
                                "Failed to rollback thread unbind persistence for " + snap.record.threadId, rollbackError);
                    }
                }
            }
            if (failure instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("failed to unbind project from threads", failure);
        }
    }

    private String threadStatus(ThreadRecord record) {
        synchronized (record) {
            restoreDurableAdmission(record);
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

    /**
     * Persists the deterministic fallback title once a Run is known terminal, independent of any
     * SSE connection. An observer per created Run outlives the creator handler so a disconnect
     * with {@code on_disconnect=continue} and a stream that omits the {@code updates} mode still
     * finalize the title.
     */
    private void observeTitleCompletion(ThreadRecord thread, RunRecord run) {
        if (closed) {
            return;
        }
        Thread observer = Thread.ofVirtual()
                .name("super-harness-title-completion-" + run.runId.value())
                .unstarted(() -> {
                    try {
                        long startedNanos = System.nanoTime();
                        long maxWaitNanos = WORKSPACE_COMPLETION_OBSERVER_MAX_WAIT.toNanos();
                        long pollMillis = WORKSPACE_COMPLETION_OBSERVER_INITIAL_POLL.toMillis();
                        while (!closed && System.nanoTime() - startedNanos < maxWaitNanos) {
                            AgentRunSnapshot snapshot;
                            try {
                                snapshot = run.bridge.snapshot(run.runId);
                            } catch (RuntimeException unavailable) {
                                return;
                            }
                            if (snapshot.status().isTerminal()) {
                                finalizeFallbackTitle(thread, run, snapshot);
                                return;
                            }
                            Thread.sleep(pollMillis);
                            pollMillis = nextWorkspaceCompletionPollMillis(pollMillis);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException failure) {
                        // Auxiliary title work must never escape as an uncaught observer failure.
                        logTitleDiagnostic("W03_TITLE_OBSERVER_FAILURE", run.runId.value(), failure);
                    } finally {
                        titleCompletionObservers.remove(Thread.currentThread());
                    }
                });
        titleCompletionObservers.add(observer);
        observer.start();
    }

    private void finalizeFallbackTitle(ThreadRecord thread, RunRecord run, AgentRunSnapshot snapshot) {
        if (thread.title() != null
                || !shouldGenerateFallbackTitle(snapshot.status(), snapshot.output().isPresent())
                || !generatedFromLoneDurableUserTurn(thread, run)) {
            return;
        }
        synchronized (thread) {
            if (thread.title() != null) {
                return;
            }
            thread.setTitle(fallbackTitle(run.humanMessage));
            thread.updatedAt = Instant.now();
            runBestEffort(run.runId.value(), () -> persistThread(thread));
        }
    }

    @FunctionalInterface
    interface PersistAction {
        void run() throws IOException;
    }

    /**
     * Best-effort title durability: a catalog failure must never turn a completed Run into a
     * failure, and the sanitized evidence (thread/run identity plus the error class) must not
     * reflect the user's request text.
     */
    static boolean runBestEffort(String runId, PersistAction action) {
        try {
            action.run();
            return true;
        } catch (IOException | RuntimeException failure) {
            // Stable code plus the SDK-generated run id and error class; never the client
            // thread_id, exception message, request text, credential or provider body.
            System.err.println("W03_TITLE_PERSIST_FAILURE run_id=" + runId
                    + " error=" + failure.getClass().getName());
            return false;
        }
    }

    int titleCompletionObserverCount() {
        return titleCompletionObservers.size();
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

    private void writeMessageDelta(
            OutputStream output, RunRecord run, String delta, String id, int ordinal)
            throws IOException {
        if (delta.isEmpty()) {
            return;
        }
        Map<String, Object> messagePayload = new LinkedHashMap<>();
        messagePayload.put("id", wireMessageId(run.runId.value(), "ai", ordinal));
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
        // A terminal Run whose title observer has not yet fired must still persist before the
        // process exits; a persistence fault is best-effort and never changes the Run outcome.
        runs.values().forEach(this::flushTerminalTitle);
        closed = true;
        workspaceCompletionObservers.forEach(Thread::interrupt);
        titleCompletionObservers.forEach(Thread::interrupt);
        threads.values().stream()
                .map(thread -> thread.bridge)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .forEach(HaifaRunBridge::close);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        List<Thread> observers = new ArrayList<>(workspaceCompletionObservers);
        observers.addAll(titleCompletionObservers);
        for (Thread observer : observers) {
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

    private void flushTerminalTitle(RunRecord run) {
        ThreadRecord thread = threads.get(run.threadId);
        if (thread == null) {
            return;
        }
        try {
            AgentRunSnapshot snapshot = run.bridge.snapshot(run.runId);
            if (snapshot.status().isTerminal()) {
                finalizeFallbackTitle(thread, run, snapshot);
            }
        } catch (RuntimeException failure) {
            // Shutdown must not be aborted by auxiliary title work.
            logTitleDiagnostic("W03_TITLE_FLUSH_FAILURE", run.runId.value(), failure);
        }
    }

    private static final class ThreadRecord {
        private final String threadId;
        private volatile Map<String, Object> metadata;
        private final Instant createdAt;
        private volatile Instant updatedAt;
        private volatile HaifaRunBridge.SessionState session;
        private volatile AgentSessionId persistedSessionId;
        private volatile HaifaRunBridge bridge;
        private volatile String modelBindingIdentity;
        private volatile String activeRunId;
        private volatile String latestRunId;
        private boolean admissionRecovered;
        private long nextMessageSequence = 1;

        private ThreadRecord(String threadId, Map<String, Object> metadata, Instant now) {
            this.threadId = threadId;
            this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
            this.createdAt = now;
            this.updatedAt = now;
            this.admissionRecovered = true;
        }

        private ThreadRecord(PersistentThreadCatalog.Entry entry) {
            this.threadId = entry.threadId();
            this.metadata = entry.metadata();
            this.createdAt = entry.createdAt();
            this.updatedAt = entry.updatedAt();
            this.persistedSessionId = entry.sessionId().orElse(null);
            this.modelBindingIdentity = entry.modelBindingIdentity().orElse(null);
        }

        private String title() {
            Object value = metadata.get("title");
            return value instanceof String text && !text.isBlank() ? text : null;
        }

        /** Explicit user rename wins over any later auto-title because auto-title is skipped once set. */
        private void setTitle(String title) {
            Map<String, Object> updated = new LinkedHashMap<>(metadata);
            updated.put("title", title);
            this.metadata = Collections.unmodifiableMap(updated);
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
        private final boolean titleCandidate;
        private io.haifa.agent.runtime.api.RunEventCursor sdkCursor;
        private final Set<Long> deferredEventSequences = ConcurrentHashMap.newKeySet();
        private final Set<String> projectedToolMessageIds = ConcurrentHashMap.newKeySet();

        private RunRecord(
                AgentRunId runId,
                String threadId,
                String assistantId,
                String humanMessage,
                HaifaRunBridge bridge,
                Instant createdAt,
                long humanSequence,
                boolean workspaceRecordingEnabled,
                Set<String> streamModes,
                boolean titleCandidate) {
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
            this.titleCandidate = titleCandidate;
        }
    }

    private record AcceptedInteractionResponse(
            DeerFlowInteractionHttpProjection.FrozenResponse response,
            InteractionResponseSubmission submission,
            Map<String, Object> message) {}

    record ThreadToolScope(String threadId, Optional<String> projectId) {
        ThreadToolScope {
            java.util.Objects.requireNonNull(threadId, "threadId");
            java.util.Objects.requireNonNull(projectId, "projectId");
        }

        String artifactProjectRef() {
            return projectId.orElse("thread:" + threadId);
        }
    }

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

package io.haifa.agent.superharness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunViewSnapshot;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.RunEventPayloads;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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

    private final ObjectMapper json;
    private final Function<JsonNode, HaifaRunBridge.ModelBinding> runModelResolver;
    private final Predicate<String> projectExists;
    private final PersistentProjectWorkspaceService workspaceChanges;
    private final Map<String, ThreadRecord> threads = new ConcurrentHashMap<>();
    private final Map<String, RunRecord> runs = new ConcurrentHashMap<>();
    private final Set<Thread> workspaceCompletionObservers = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    DeerFlowThreadRunApi(
            ObjectMapper json,
            Function<JsonNode, HaifaRunBridge.ModelBinding> runModelResolver,
            Predicate<String> projectExists,
            PersistentProjectWorkspaceService workspaceChanges) {
        this.json = json;
        this.runModelResolver = runModelResolver;
        this.projectExists = projectExists;
        this.workspaceChanges = workspaceChanges;
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
        if (body.has("before") && !body.get("before").isNull()) {
            sendJson(exchange, 422, Map.of("detail", "before history cursor is not implemented"));
            return;
        }
        if (threadRuns(threadId).isEmpty()) {
            sendJson(exchange, 200, List.of());
            return;
        }
        sendJson(exchange, 200, List.of(threadHistoryResponse(thread)));
    }

    private void listThreadMessagesPage(HttpExchange exchange, String threadId) throws IOException {
        if (!threads.containsKey(threadId)) {
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

        Long beforeSequence = before;
        List<Map<String, Object>> eligible = threadMessageRows(threadId).stream()
                .filter(row -> beforeSequence == null || ((Long) row.get("seq")) < beforeSequence)
                .toList();
        int from = Math.max(0, eligible.size() - limit.orElseThrow());
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
                thread.bridge = new HaifaRunBridge(binding);
                thread.modelBindingIdentity = thread.bridge.bindingIdentity();
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
                    workspaceRecordingEnabled);
            runs.put(run.runId.value(), run);
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

    private void joinRun(HttpExchange exchange, String threadId, String runId) throws IOException {
        if (queryValue(exchange, "action").isPresent()) {
            exchange.getResponseHeaders().set("Allow", "POST");
            sendJson(exchange, 405, Map.of("detail", "`action` is only supported on POST requests"));
            return;
        }
        ThreadRecord thread = threads.get(threadId);
        RunRecord run = runs.get(runId);
        if (thread == null || run == null || !run.threadId.equals(threadId)) {
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
            while (true) {
                ReplayResult replay = run.projector.replayAfter(delivered);
                if (replay instanceof ReplayResult.Gap gap) {
                    writeReplayGap(output, run, gap.gap(), gap.authoritativeView(), delivered);
                    return;
                }
                ReplayResult.Events events = (ReplayResult.Events) replay;
                for (ProjectedRunEvent event : events.events()) {
                    delivered = event.cursor();
                    if (writeProjectedEvent(output, run, event)) {
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

    private boolean writeProjectedEvent(OutputStream output, RunRecord run, ProjectedRunEvent event)
            throws IOException {
        String cursor = sseCursor(event.cursor());
        if (event.interaction().isPresent()) {
            // The frozen human-input card requires a richer request payload than the public
            // lifecycle event exposes. Never invent labels/options or claim support.
            writeSse(
                    output,
                    "error",
                    Map.of(
                            "code", "unsupported_interaction_projection",
                            "request_id", event.interaction().orElseThrow().requestId(),
                            "message", "Interaction SSE projection is not implemented"),
                    cursor);
            return true;
        }
        if (event.source().payload() instanceof RunEventPayloads.ToolLifecycle
                || event.source().payload() instanceof RunEventPayloads.ResourceAvailable
                || event.source().payload() instanceof RunEventPayloads.DeliveryLifecycle
                || event.source().payload() instanceof RunEventPayloads.RunInputLifecycle) {
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
                && terminalSnapshot.output().isPresent()) {
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
        RunRecord run = runs.get(runId);
        if (run == null || !run.threadId.equals(threadId)) {
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
        RunRecord run = runs.get(runId);
        if (run == null || !run.threadId.equals(threadId)) {
            sendJson(exchange, 404, Map.of("detail", "Run " + runId + " not found"));
            return;
        }
        sendJson(exchange, 200, runResponse(run));
    }

    private void listRuns(HttpExchange exchange, String threadId) throws IOException {
        if (!threads.containsKey(threadId)) {
            sendJson(exchange, 404, Map.of("detail", "Thread not found"));
            return;
        }
        Optional<Integer> limit = positiveQueryInteger(exchange, "limit", 10, 1000);
        Optional<Integer> offset = nonNegativeQueryInteger(exchange, "offset", 0);
        if (limit.isEmpty() || offset.isEmpty()) {
            sendJson(exchange, 422, Map.of("detail", "Invalid run pagination"));
            return;
        }
        Optional<String> status = queryValue(exchange, "status");
        List<Map<String, Object>> response = threadRuns(threadId).stream()
                .sorted((left, right) -> Long.compare(right.humanSequence, left.humanSequence))
                .map(this::runResponse)
                .filter(run -> status.map(expected -> expected.equals(run.get("status"))).orElse(true))
                .skip(offset.orElseThrow())
                .limit(limit.orElseThrow())
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
        Map<String, Object> response = threadHistoryResponse(thread);
        response.put("checkpoint", Map.of());
        response.put("tasks", List.of());
        return response;
    }

    private Map<String, Object> threadHistoryResponse(ThreadRecord thread) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("values", Map.of("messages", threadMessages(thread.threadId)));
        response.put("next", List.of());
        response.put("metadata", Map.of());
        response.put("checkpoint_id", thread.latestRunId);
        response.put("parent_checkpoint_id", null);
        response.put("created_at", thread.updatedAt.toString());
        return response;
    }

    private List<Map<String, Object>> threadMessages(String threadId) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (RunRecord run : threadRuns(threadId)) {
            messages.add(messageForRun(
                    "human-" + run.runId.value(), "human", run.humanMessage, run.runId.value()));
            run.bridge.snapshot(run.runId).output().ifPresent(output -> messages.add(messageForRun(
                    "assistant-" + run.runId.value(), "ai", output, run.runId.value())));
        }
        return messages;
    }

    private List<Map<String, Object>> threadMessageRows(String threadId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RunRecord run : threadRuns(threadId)) {
            rows.add(messageRow(
                    run,
                    run.humanSequence,
                    run.createdAt,
                    message("human-" + run.runId.value(), "human", run.humanMessage)));
            AgentRunSnapshot snapshot = run.bridge.snapshot(run.runId);
            snapshot.output().ifPresent(output -> rows.add(messageRow(
                    run,
                    run.humanSequence + 1,
                    snapshot.updatedAt(),
                    message("assistant-" + run.runId.value(), "ai", output))));
        }
        return rows;
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

    private List<RunRecord> threadRuns(String threadId) {
        return runs.values().stream()
                .filter(run -> run.threadId.equals(threadId))
                .sorted((left, right) -> Long.compare(left.humanSequence, right.humanSequence))
                .toList();
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
                modes.forEach(mode -> requested.add(mode.asText()));
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

    private Optional<Integer> nonNegativeQueryInteger(
            HttpExchange exchange, String name, int defaultValue) {
        Optional<String> raw = queryValue(exchange, name);
        if (raw.isEmpty()) {
            return Optional.of(defaultValue);
        }
        try {
            int value = Integer.parseInt(raw.orElseThrow());
            return value >= 0 ? Optional.of(value) : Optional.empty();
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
        response.put("values", Map.of());
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

    private Map<String, Object> messageForRun(
            String id, String type, String content, String runId) {
        Map<String, Object> result = message(id, type, content);
        result.put("run_id", runId);
        return result;
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
    }

    private static final class ThreadRecord {
        private final String threadId;
        private final Map<String, Object> metadata;
        private final Instant createdAt;
        private volatile Instant updatedAt;
        private volatile HaifaRunBridge.SessionState session;
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
        private io.haifa.agent.runtime.api.RunEventCursor sdkCursor;

        private RunRecord(
                AgentRunId runId,
                String threadId,
                String assistantId,
                String humanMessage,
                HaifaRunBridge bridge,
                Instant createdAt,
                long humanSequence,
                boolean workspaceRecordingEnabled) {
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
        }
    }

    private static final class MalformedRequestException extends RuntimeException {}
}

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
import io.haifa.agent.superharness.events.AppendResult;
import io.haifa.agent.superharness.events.BoundedRunEventProjector;
import io.haifa.agent.superharness.events.EventProjectionException;
import io.haifa.agent.superharness.events.ProjectedRunEvent;
import io.haifa.agent.superharness.events.ProjectionCursor;
import io.haifa.agent.superharness.events.ProjectionGap;
import io.haifa.agent.superharness.events.ReplayResult;
import io.haifa.agent.superharness.events.RunTerminalKind;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;

/** Frozen DeerFlow thread/run compatibility routes backed by the public Haifa Agent SDK. */
final class DeerFlowThreadRunApi implements HttpHandler, AutoCloseable {
    private static final Pattern THREAD = Pattern.compile("^/api/threads/([^/]+)$");
    private static final Pattern THREAD_STATE = Pattern.compile("^/api/threads/([^/]+)/state$");
    private static final Pattern STREAM = Pattern.compile("^/api/threads/([^/]+)/runs/stream$");
    private static final Pattern JOIN_STREAM =
            Pattern.compile("^/api/threads/([^/]+)/runs/([^/]+)/stream$");
    private static final Pattern RUN = Pattern.compile("^/api/threads/([^/]+)/runs/([^/]+)$");
    private static final Pattern CANCEL =
            Pattern.compile("^/api/threads/([^/]+)/runs/([^/]+)/cancel$");
    private static final List<String> SUPPORTED_STREAM_MODES =
            List.of("messages-tuple", "updates", "custom");
    private static final Pattern SSE_CURSOR = Pattern.compile("^(\\d+)-0$");
    private static final int EVENT_RETENTION_CAPACITY = 8;
    private static final int EVENT_PAGE_SIZE = 4;

    private final ObjectMapper json;
    private final Function<JsonNode, HaifaRunBridge.ModelBinding> runModelResolver;
    private final Map<String, ThreadRecord> threads = new ConcurrentHashMap<>();
    private final Map<String, RunRecord> runs = new ConcurrentHashMap<>();

    DeerFlowThreadRunApi(
            ObjectMapper json,
            Function<JsonNode, HaifaRunBridge.ModelBinding> runModelResolver) {
        this.json = json;
        this.runModelResolver = runModelResolver;
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
        List<Map<String, Object>> messages = new ArrayList<>();
        if (thread.latestRunId != null) {
            RunRecord run = runs.get(thread.latestRunId);
            if (run != null) {
                messages.add(message("human-" + run.runId.value(), "human", run.humanMessage));
                AgentRunSnapshot snapshot = run.bridge.snapshot(run.runId);
                snapshot.output().ifPresent(output -> messages.add(
                        message("assistant-" + run.runId.value(), "ai", output)));
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("values", Map.of("messages", messages));
        response.put("next", List.of());
        response.put("metadata", Map.of());
        response.put("checkpoint", Map.of());
        response.put("checkpoint_id", null);
        response.put("parent_checkpoint_id", null);
        response.put("created_at", null);
        response.put("tasks", List.of());
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
            HaifaRunBridge.StartedRun started =
                    thread.bridge.start(threadId, message, Optional.ofNullable(thread.session));
            thread.session = started.session();
            thread.updatedAt = Instant.now();
            thread.activeRunId = started.runId().value();
            thread.latestRunId = started.runId().value();
            run = new RunRecord(
                    started.runId(),
                    threadId,
                    text(body, "assistant_id").orElse(null),
                    message,
                    thread.bridge,
                    Instant.now());
            runs.put(run.runId.value(), run);
        }

        streamRunEvents(
                exchange,
                thread,
                run,
                new ProjectionCursor(run.runId.value(), 0),
                true,
                text(body, "on_disconnect").orElse("cancel"),
                true);
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
        if (rawCursor == null || rawCursor.isBlank()) {
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
        if (!action.equals("interrupt") || !wait.equals("false")) {
            sendJson(exchange, 422, Map.of("detail", "Only wait=false&action=interrupt is implemented"));
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
        AgentRunSnapshot snapshot = run.bridge.snapshot(run.runId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("run_id", runId);
        response.put("thread_id", threadId);
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
        sendJson(exchange, 200, response);
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

    private String threadStatus(ThreadRecord record) {
        synchronized (record) {
            if (record.activeRunId == null) {
                return "idle";
            }
            RunRecord active = runs.get(record.activeRunId);
            if (active == null || active.bridge.snapshot(active.runId).status().isTerminal()) {
                record.activeRunId = null;
                record.updatedAt = Instant.now();
                return "idle";
            }
            return "busy";
        }
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
                result = message.path("content").asText("");
            }
        }
        return result;
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
                "messages-tuple",
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
        threads.values().stream()
                .map(thread -> thread.bridge)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .forEach(HaifaRunBridge::close);
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
        private io.haifa.agent.runtime.api.RunEventCursor sdkCursor;

        private RunRecord(
                AgentRunId runId,
                String threadId,
                String assistantId,
                String humanMessage,
                HaifaRunBridge bridge,
                Instant createdAt) {
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
        }
    }

    private static final class MalformedRequestException extends RuntimeException {}
}

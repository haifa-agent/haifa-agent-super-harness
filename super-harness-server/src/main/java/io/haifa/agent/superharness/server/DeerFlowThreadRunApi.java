package io.haifa.agent.superharness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.runtime.api.AgentRunOutputEventType;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
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

/** Frozen DeerFlow thread/run compatibility routes backed by the public Haifa Agent SDK. */
final class DeerFlowThreadRunApi implements HttpHandler, AutoCloseable {
    private static final Pattern THREAD = Pattern.compile("^/api/threads/([^/]+)$");
    private static final Pattern THREAD_STATE = Pattern.compile("^/api/threads/([^/]+)/state$");
    private static final Pattern STREAM = Pattern.compile("^/api/threads/([^/]+)/runs/stream$");
    private static final Pattern RUN = Pattern.compile("^/api/threads/([^/]+)/runs/([^/]+)$");
    private static final Pattern CANCEL =
            Pattern.compile("^/api/threads/([^/]+)/runs/([^/]+)/cancel$");
    private static final List<String> SUPPORTED_STREAM_MODES =
            List.of("messages-tuple", "updates", "custom");

    private final ObjectMapper json;
    private final HaifaRunBridge bridge;
    private final Map<String, ThreadRecord> threads = new ConcurrentHashMap<>();
    private final Map<String, RunRecord> runs = new ConcurrentHashMap<>();

    DeerFlowThreadRunApi(ObjectMapper json, HaifaRunBridge bridge) {
        this.json = json;
        this.bridge = bridge;
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
        if (!threads.containsKey(threadId)) {
            sendJson(exchange, 404, Map.of("detail", "Thread not found"));
            return;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("values", Map.of());
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

        RunRecord run;
        synchronized (thread) {
            if (thread.activeRunId != null) {
                RunRecord active = runs.get(thread.activeRunId);
                if (active == null || !bridge.snapshot(active.runId).status().isTerminal()) {
                    sendJson(exchange, 409, Map.of("detail", "Thread already has an active run"));
                    return;
                }
            }
            HaifaRunBridge.StartedRun started =
                    bridge.start(threadId, message, Optional.ofNullable(thread.session));
            thread.session = started.session();
            thread.updatedAt = Instant.now();
            thread.activeRunId = started.runId().value();
            run = new RunRecord(
                    started.runId(),
                    threadId,
                    text(body, "assistant_id").orElse(null),
                    Instant.now());
            runs.put(run.runId.value(), run);
        }

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.getResponseHeaders().set(
                "Content-Location", "/api/threads/" + threadId + "/runs/" + run.runId.value());
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream output = exchange.getResponseBody()) {
            writeSse(output, "metadata", Map.of("run_id", run.runId.value(), "thread_id", threadId), null);
            long sequence = 0;
            StringBuilder emittedText = new StringBuilder();
            while (true) {
                for (var event : bridge.outputAfter(run.runId, sequence)) {
                    sequence = Math.max(sequence, event.sequence());
                    if (event.type() == AgentRunOutputEventType.ASSISTANT_TEXT_DELTA) {
                        emittedText.append(event.textDelta());
                        writeMessageDelta(output, run, event.textDelta(), Long.toString(event.sequence()));
                    }
                }
                AgentRunSnapshot snapshot = bridge.snapshot(run.runId);
                if (snapshot.status().isTerminal()) {
                    if (snapshot.status() == AgentRunStatus.COMPLETED && snapshot.output().isPresent()) {
                        String finalOutput = snapshot.output().orElseThrow();
                        String streamed = emittedText.toString();
                        if (finalOutput.startsWith(streamed) && finalOutput.length() > streamed.length()) {
                            writeMessageDelta(
                                    output,
                                    run,
                                    finalOutput.substring(streamed.length()),
                                    "final");
                        } else if (streamed.isEmpty()) {
                            writeMessageDelta(output, run, finalOutput, "final");
                        }
                    }
                    if (snapshot.status() == AgentRunStatus.FAILED) {
                        String error = snapshot.error().map(value -> value.message()).orElse("Run failed");
                        writeSse(output, "error", Map.of("message", error), null);
                    }
                    writeSse(output, "end", null, null);
                    break;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    bridge.cancel(run.runId);
                    break;
                }
            }
        } catch (IOException disconnected) {
            if ("cancel".equals(text(body, "on_disconnect").orElse("cancel"))) {
                bridge.cancel(run.runId);
            }
        } finally {
            synchronized (thread) {
                boolean terminal = bridge.snapshot(run.runId).status().isTerminal();
                if (terminal && run.runId.value().equals(thread.activeRunId)) {
                    thread.activeRunId = null;
                }
                thread.updatedAt = Instant.now();
            }
        }
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
        if (!bridge.cancel(run.runId)) {
            sendJson(
                    exchange,
                    409,
                    Map.of(
                            "detail",
                            "Run " + runId + " is not cancellable (status: "
                                    + deerFlowStatus(bridge.snapshot(run.runId).status()) + ")"));
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
        AgentRunSnapshot snapshot = bridge.snapshot(run.runId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("run_id", runId);
        response.put("thread_id", threadId);
        response.put("assistant_id", run.assistantId);
        response.put("status", deerFlowStatus(snapshot.status()));
        response.put("metadata", Map.of());
        response.put("kwargs", Map.of());
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
            if (active == null || bridge.snapshot(active.runId).status().isTerminal()) {
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
        bridge.close();
    }

    private static final class ThreadRecord {
        private final String threadId;
        private final Map<String, Object> metadata;
        private final Instant createdAt;
        private volatile Instant updatedAt;
        private volatile HaifaRunBridge.SessionState session;
        private volatile String activeRunId;

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
        private final Instant createdAt;
        private RunRecord(AgentRunId runId, String threadId, String assistantId, Instant createdAt) {
            this.runId = runId;
            this.threadId = threadId;
            this.assistantId = assistantId;
            this.createdAt = createdAt;
        }
    }

    private static final class MalformedRequestException extends RuntimeException {}
}

package io.haifa.agent.superharness.server.api;

import io.haifa.agent.superharness.server.api.DeerFlowConversationService.AdmittedTurn;
import io.haifa.agent.superharness.server.api.DeerFlowConversationService.ConversationException;
import io.haifa.agent.superharness.server.api.DeerFlowConversationService.DelegationProjectionProvider;
import io.haifa.agent.superharness.server.api.DeerFlowConversationService.MalformedRequestException;
import io.haifa.agent.superharness.server.api.DeerFlowConversationService.McpProjectionProvider;
import io.haifa.agent.superharness.server.api.DeerFlowConversationService.RunEventSink;
import io.haifa.agent.superharness.server.api.DeerFlowConversationService.SkillProjectionProvider;
import io.haifa.agent.superharness.server.api.DeerFlowConversationService.ThreadToolScope;
import io.haifa.agent.superharness.server.api.DeerFlowConversationService.TranscriptScanLimitException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.FrozenInstructionDiagnostic;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.superharness.server.auth.LocalAuthService;
import io.haifa.agent.superharness.server.prompt.AgentPromptContext;
import io.haifa.agent.superharness.server.run.HaifaRunBridge;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import javax.crypto.SecretKey;

/**
 * HTTP compatibility adapter for the frozen DeerFlow thread/run routes. It owns only transport
 * concerns — routing, request parsing, status mapping and SSE byte encoding — and delegates every
 * product use case to one shared {@link DeerFlowConversationService} instance. The Feishu channel
 * bridge receives that same instance, so both adapters share one Runtime truth.
 */
public final class DeerFlowThreadRunApi implements HttpHandler, AutoCloseable {

    private static final Pattern THREAD = Pattern.compile("^/api/threads/([^/]+)$");
    private static final Pattern THREAD_STATE = Pattern.compile("^/api/threads/([^/]+)/state$");
    private static final Pattern THREAD_HISTORY =
            Pattern.compile("^/api/threads/([^/]+)/history$");
    private static final Pattern THREAD_MESSAGES_PAGE =
            Pattern.compile("^/api/threads/([^/]+)/messages/page$");
    private static final Pattern THREAD_RUNS = Pattern.compile("^/api/threads/([^/]+)/runs$");
    private static final Pattern THREAD_TOKEN_USAGE =
            Pattern.compile("^/api/threads/([^/]+)/token-usage$");
    private static final Pattern STREAM = Pattern.compile("^/api/threads/([^/]+)/runs/stream$");
    private static final Pattern JOIN_STREAM =
            Pattern.compile("^/api/threads/([^/]+)/runs/([^/]+)/stream$");
    private static final Pattern RUN = Pattern.compile("^/api/threads/([^/]+)/runs/([^/]+)$");
    private static final Pattern RUN_EVENTS = Pattern.compile("^/api/threads/([^/]+)/runs/([^/]+)/events$");
    private static final Pattern WORKSPACE_CHANGES =
            Pattern.compile("^/api/threads/([^/]+)/runs/([^/]+)/workspace-changes$");
    private static final Pattern CANCEL =
            Pattern.compile("^/api/threads/([^/]+)/runs/([^/]+)/cancel$");
    private static final Pattern OWNED_THREAD =
            Pattern.compile("^/api/threads/([^/]+)(?:/.*)?$");

    /** Frozen client-facing detail for any request whose durable state cannot be projected. */
    private static final String DURABLE_STATE_UNAVAILABLE = "Durable thread state is unavailable";
    private static final System.Logger API_LOG =
            System.getLogger(DeerFlowThreadRunApi.class.getName());
    /** Bounded length of a client path or failure cause echoed into one diagnostic line. */
    private static final int DIAGNOSTIC_LENGTH = 160;

    private final DeerFlowConversationService conversations;

    DeerFlowThreadRunApi(
            ObjectMapper json,
            Function<JsonNode, HaifaRunBridge.ModelBinding> runModelResolver,
            Predicate<String> projectExists,
            PersistentProjectWorkspaceService workspaceChanges,
            Path dataDirectory,
            SecretKey continuationKey,
            Function<ThreadToolScope, List<? extends JavaTool<?, ?>>> runToolsForThread,
            McpProjectionProvider mcpProjections,
            SkillProjectionProvider skillProjections,
            DeerFlowUploadContext uploadContext)
            throws IOException {
        this.conversations = new DeerFlowConversationService(json, runModelResolver, projectExists, workspaceChanges, dataDirectory, continuationKey,
                runToolsForThread, mcpProjections, skillProjections, uploadContext);
    }

    DeerFlowThreadRunApi(
            ObjectMapper json,
            Function<JsonNode, HaifaRunBridge.ModelBinding> runModelResolver,
            Predicate<String> projectExists,
            PersistentProjectWorkspaceService workspaceChanges,
            Path dataDirectory,
            SecretKey continuationKey,
            Function<ThreadToolScope, List<? extends JavaTool<?, ?>>> runToolsForThread,
            McpProjectionProvider mcpProjections,
            SkillProjectionProvider skillProjections,
            DeerFlowUploadContext uploadContext,
            BiFunction<HaifaRunBridge, AgentRunId, Optional<FrozenInstructionDiagnostic>> instructionDiagnostics)
            throws IOException {
        this.conversations = new DeerFlowConversationService(json, runModelResolver, projectExists, workspaceChanges, dataDirectory, continuationKey,
                runToolsForThread, mcpProjections, skillProjections, uploadContext, instructionDiagnostics);
    }

    DeerFlowThreadRunApi(
            ObjectMapper json,
            Function<JsonNode, HaifaRunBridge.ModelBinding> runModelResolver,
            Predicate<String> projectExists,
            PersistentProjectWorkspaceService workspaceChanges,
            Path dataDirectory,
            SecretKey continuationKey,
            Function<ThreadToolScope, List<? extends JavaTool<?, ?>>> runToolsForThread,
            McpProjectionProvider mcpProjections,
            SkillProjectionProvider skillProjections,
            DeerFlowUploadContext uploadContext,
            Function<JsonNode, AgentPromptContext> agentPromptResolver,
            BiFunction<HaifaRunBridge, AgentRunId, Optional<FrozenInstructionDiagnostic>> instructionDiagnostics)
            throws IOException {
        this.conversations = new DeerFlowConversationService(json, runModelResolver, projectExists, workspaceChanges, dataDirectory, continuationKey,
                runToolsForThread, mcpProjections, skillProjections, uploadContext, agentPromptResolver,
                instructionDiagnostics);
    }

    DeerFlowThreadRunApi(ObjectMapper json,
            Function<JsonNode, HaifaRunBridge.ModelBinding> runModelResolver,
            Predicate<String> projectExists, PersistentProjectWorkspaceService workspaceChanges,
            Path dataDirectory, SecretKey continuationKey,
            Function<ThreadToolScope, List<? extends JavaTool<?, ?>>> runToolsForThread,
            McpProjectionProvider mcpProjections, SkillProjectionProvider skillProjections,
            DeerFlowUploadContext uploadContext, Function<JsonNode, AgentPromptContext> agentPromptResolver,
            BiFunction<HaifaRunBridge, AgentRunId, Optional<FrozenInstructionDiagnostic>> instructionDiagnostics,
            DelegationProjectionProvider delegationProjections) throws IOException {
        this.conversations = new DeerFlowConversationService(json, runModelResolver, projectExists, workspaceChanges, dataDirectory, continuationKey,
                runToolsForThread, mcpProjections, skillProjections, uploadContext, agentPromptResolver,
                instructionDiagnostics, delegationProjections);
    }

    DeerFlowThreadRunApi(ObjectMapper json,
            Function<JsonNode, HaifaRunBridge.ModelBinding> runModelResolver,
            Predicate<String> projectExists, PersistentProjectWorkspaceService workspaceChanges,
            Path dataDirectory, SecretKey continuationKey,
            Function<ThreadToolScope, List<? extends JavaTool<?, ?>>> runToolsForThread,
            McpProjectionProvider mcpProjections, SkillProjectionProvider skillProjections,
            DeerFlowUploadContext uploadContext, Function<JsonNode, AgentPromptContext> agentPromptResolver,
            BiFunction<HaifaRunBridge, AgentRunId, Optional<FrozenInstructionDiagnostic>> instructionDiagnostics,
            DelegationProjectionProvider delegationProjections,
            io.haifa.agent.runtime.api.ChildRunCapacity childRunCapacity) throws IOException {
        this.conversations = new DeerFlowConversationService(json, runModelResolver, projectExists, workspaceChanges, dataDirectory, continuationKey,
                runToolsForThread, mcpProjections, skillProjections, uploadContext, agentPromptResolver,
                instructionDiagnostics, delegationProjections, childRunCapacity);
    }

    DeerFlowThreadRunApi(ObjectMapper json,
            Function<JsonNode, HaifaRunBridge.ModelBinding> runModelResolver,
            Predicate<String> projectExists, PersistentProjectWorkspaceService workspaceChanges,
            Path dataDirectory, SecretKey continuationKey,
            Function<ThreadToolScope, List<? extends JavaTool<?, ?>>> runToolsForThread,
            McpProjectionProvider mcpProjections, SkillProjectionProvider skillProjections,
            DeerFlowUploadContext uploadContext, Function<JsonNode, AgentPromptContext> agentPromptResolver,
            BiFunction<HaifaRunBridge, AgentRunId, Optional<FrozenInstructionDiagnostic>> instructionDiagnostics,
            DelegationProjectionProvider delegationProjections,
            io.haifa.agent.runtime.api.ChildRunCapacity childRunCapacity,
            Supplier<? extends ExecutorService> executionExecutorFactory,
            Long parentWallTimeMillisForTests) throws IOException {
        this.conversations = new DeerFlowConversationService(json, runModelResolver, projectExists, workspaceChanges, dataDirectory, continuationKey,
                runToolsForThread, mcpProjections, skillProjections, uploadContext, agentPromptResolver,
                instructionDiagnostics, delegationProjections, childRunCapacity, executionExecutorFactory,
                parentWallTimeMillisForTests);
    }

    public DeerFlowConversationService conversations() {
        return conversations;
    }

    @Override
    public void close() {
        conversations.close();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!isLoopbackPeer(exchange) && !LocalAuthService.sessionAuthActive()) {
                sendJson(exchange, 403, Map.of("detail", "Local profile accepts loopback clients only"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            Matcher owned = OWNED_THREAD.matcher(path);
            boolean searchPost = "/api/threads/search".equals(path) && "POST".equals(exchange.getRequestMethod());
            if (owned.matches() && !searchPost && !visible(exchange, owned.group(1))) return;
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
            match = THREAD_TOKEN_USAGE.matcher(path);
            if (match.matches() && exchange.getRequestMethod().equals("GET")) {
                getThreadTokenUsage(exchange, match.group(1));
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
            match = RUN_EVENTS.matcher(path);
            if (match.matches() && exchange.getRequestMethod().equals("GET")) {
                getSubtaskEvents(exchange, match.group(1), match.group(2));
                return;
            }
            sendJson(exchange, 404, Map.of("detail", "Not Found"));
        } catch (ConversationException rejected) {
            sendJson(exchange, rejected.kind().status(), Map.of("detail", rejected.detail()));
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
            // The client keeps the frozen detail prefix; the log names the request and the real
            // cause, so a corrupt persisted projection is never reported as a silent missing store.
            API_LOG.log(System.Logger.Level.WARNING,
                    "Durable thread state unavailable for " + exchange.getRequestMethod() + " "
                            + bounded(exchange.getRequestURI().getPath(), DIAGNOSTIC_LENGTH) + ": "
                            + unavailable.getClass().getSimpleName() + ": "
                            + bounded(unavailable.getMessage(), DIAGNOSTIC_LENGTH));
            sendJson(exchange, 503, Map.of("detail", durableStateUnavailableDetail(unavailable)));
        }
    }

    private static String durableStateUnavailableDetail(IllegalStateException failure) {
        String cause = bounded(failure.getMessage(), DIAGNOSTIC_LENGTH);
        return cause.isEmpty() ? DURABLE_STATE_UNAVAILABLE : DURABLE_STATE_UNAVAILABLE + " (" + cause + ")";
    }

    private static String bounded(String value, int maximum) {
        if (value == null) return "";
        String single = value.replace('\n', ' ').replace('\r', ' ');
        return single.length() > maximum ? single.substring(0, maximum) : single;
    }

    private void createThread(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200, conversations.createThread(readBody(exchange)));
    }

    private void searchThreads(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200, conversations.searchThreads(readBody(exchange)));
    }

    private void getThread(HttpExchange exchange, String threadId) throws IOException {
        sendJson(exchange, 200, conversations.getThread(threadId));
    }

    private void getThreadState(HttpExchange exchange, String threadId) throws IOException {
        sendJson(exchange, 200, conversations.readThreadState(threadId));
    }

    private void updateThreadState(HttpExchange exchange, String threadId) throws IOException {
        sendJson(exchange, 200, conversations.updateThreadState(threadId, readBody(exchange)));
    }

    private void getThreadHistory(HttpExchange exchange, String threadId) throws IOException {
        sendJson(exchange, 200, conversations.getThreadHistory(threadId, readBody(exchange)));
    }

    private void listThreadMessagesPage(HttpExchange exchange, String threadId) throws IOException {
        sendJson(exchange, 200, conversations.listThreadMessagesPage(threadId, exchange.getRequestURI().getRawQuery()));
    }

    private void getRun(HttpExchange exchange, String threadId, String runId) throws IOException {
        sendJson(exchange, 200, conversations.getRun(threadId, runId));
    }

    private void listRuns(HttpExchange exchange, String threadId) throws IOException {
        sendJson(exchange, 200, conversations.listRuns(threadId));
    }

    private void getThreadTokenUsage(HttpExchange exchange, String threadId) throws IOException {
        sendJson(exchange, 200, conversations.getThreadTokenUsage(threadId, exchange.getRequestURI().getRawQuery()));
    }

    private void cancelRun(HttpExchange exchange, String threadId, String runId) throws IOException {
        conversations.cancelRun(threadId, runId, exchange.getRequestURI().getRawQuery());
        exchange.sendResponseHeaders(202, -1);
        exchange.close();
    }

    private void getSubtaskEvents(HttpExchange exchange, String threadId, String runId) throws IOException {
        sendJson(exchange, 200, conversations.getSubtaskEvents(threadId, runId, exchange.getRequestURI().getRawQuery()));
    }

    private void getWorkspaceChanges(HttpExchange exchange, String threadId, String runId) throws IOException {
        sendJson(exchange, 200, conversations.getWorkspaceChanges(threadId, runId, exchange.getRequestURI().getRawQuery()));
    }

    private void streamRun(HttpExchange exchange, String threadId) throws IOException {
        AdmittedTurn turn;
        try {
            turn = conversations.admitTurn(
                    threadId,
                    readBody(exchange),
                    exchange.getRequestHeaders().getFirst("X-Haifa-Run-Input"),
                    exchange.getRequestHeaders().getFirst("Last-Event-ID"));
        } catch (ConversationException rejected) {
            sendJson(exchange, rejected.kind().status(), Map.of("detail", rejected.detail()));
            return;
        } catch (RuntimeException failure) {
            // Admission failed before any SSE frame was committed. Never reflect SDK/provider text.
            if (exchange.getResponseCode() < 0) {
                sendJson(exchange, 500, Map.of("detail", "Run stream is unavailable"));
            }
            return;
        }
        streamAdmitted(exchange, turn);
    }

    private void joinRun(HttpExchange exchange, String threadId, String runId) throws IOException {
        if (queryValue(exchange, "action").isPresent()) {
            exchange.getResponseHeaders().set("Allow", "POST");
            sendJson(exchange, 405, Map.of("detail", "`action` is only supported on POST requests"));
            return;
        }
        AdmittedTurn turn;
        try {
            turn = conversations.admitJoin(threadId, runId, exchange.getRequestHeaders().getFirst("Last-Event-ID"));
        } catch (ConversationException rejected) {
            sendJson(exchange, rejected.kind().status(), Map.of("detail", rejected.detail()));
            return;
        }
        streamAdmitted(exchange, turn);
    }

    /**
     * Encodes the shared observation of an admitted Turn as the frozen SSE wire. The service owns
     * every product decision; this method only writes bytes and commits the response headers.
     */
    private void streamAdmitted(HttpExchange exchange, AdmittedTurn turn) throws IOException {
        try {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            // The frozen frontend reaches the Gateway through the Next.js rewrite proxy, which gzips
            // proxied responses and holds a compressed event stream back until the Run ends. Frozen
            // DeerFlow keeps the stream uncompressed in nginx; no-transform tells the Next.js proxy
            // the same thing.
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
            exchange.getResponseHeaders().set(
                    "Content-Location",
                    "/api/threads/" + turn.threadId() + "/runs/" + turn.runId());
            if (turn.interactionPresentationDegraded()) {
                exchange.getResponseHeaders().set(
                        "X-Haifa-Interaction-Presentation", "accepted-not-persisted");
            }
            if (turn.creator) conversations.awaitRunStart(turn.run);
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                conversations.observeTurn(turn, new SseSink(output));
            }
        } catch (RuntimeException failure) {
            // Once SSE headers have been sent, this exchange cannot carry a JSON response.
            // Never reflect SDK/provider exception text into a client response.
            if (exchange.getResponseCode() < 0) {
                sendJson(exchange, 500, Map.of("detail", "Run stream is unavailable"));
            }
        }
    }

    private boolean visible(HttpExchange exchange, String threadId) throws IOException {
        if (conversations.threadVisibleToCurrentOwner(threadId)) return true;
        sendJson(exchange, 404, Map.of("detail", "Thread not found"));
        return false;
    }

    private boolean isLoopbackPeer(HttpExchange exchange) {
        return exchange.getRemoteAddress() != null
                && exchange.getRemoteAddress().getAddress() != null
                && exchange.getRemoteAddress().getAddress().isLoopbackAddress();
    }

    private Optional<String> queryValue(HttpExchange exchange, String name) {
        return conversations.queryValue(exchange.getRequestURI().getRawQuery(), name);
    }

    private JsonNode readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return conversations.json.createObjectNode();
        }
        try {
            JsonNode value = conversations.json.readTree(bytes);
            if (value == null || !value.isObject()) {
                throw new MalformedRequestException();
            }
            return value;
        } catch (IOException malformed) {
            throw new MalformedRequestException();
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = conversations.json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
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

    private void writeSse(OutputStream output, String event, Object data, String id)
            throws IOException {
        StringBuilder frame = new StringBuilder("event: ").append(event).append('\n');
        frame.append("data: ").append(conversations.json.writeValueAsString(data)).append('\n');
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

    private final class SseSink implements RunEventSink {
        private final OutputStream output;

        SseSink(OutputStream output) {
            this.output = output;
        }

        @Override
        public void event(String event, Object data, String id) throws IOException {
            writeSse(output, event, data, id);
        }

        @Override
        public void heartbeat() throws IOException {
            writeHeartbeat(output);
        }
    }
}

package io.haifa.agent.superharness.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.changeset.FileChange;
import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.changeset.FileVersion;
import io.haifa.agent.runtime.api.AgentPlanView;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.superharness.server.plan.PlanAuthoringResources;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.AgentRunViewSnapshot;
import io.haifa.agent.runtime.api.FrozenInstructionDiagnostic;
import io.haifa.agent.runtime.api.InteractionResponseReceiptStatus;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.RunMessageCursor;
import io.haifa.agent.runtime.api.RunMessagePage;
import io.haifa.agent.runtime.api.RunMessageView;
import io.haifa.agent.runtime.api.RuntimeContractException;
import io.haifa.agent.runtime.api.ToolCallView;
import io.haifa.agent.sdk.api.RunInputCommand;
import io.haifa.agent.sdk.api.RunInputResult;
import io.haifa.agent.sdk.api.RunInputStatus;
import io.haifa.agent.sdk.conversation.ConversationTurn;
import io.haifa.agent.sdk.conversation.ConversationTurnCursor;
import io.haifa.agent.sdk.conversation.ConversationTurnQuery;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.store.sqlite.SqliteStoreException;
import io.haifa.agent.superharness.events.AppendResult;
import io.haifa.agent.superharness.events.BoundedRunEventProjector;
import io.haifa.agent.superharness.events.EventProjectionException;
import io.haifa.agent.superharness.events.ProjectedRunEvent;
import io.haifa.agent.superharness.events.ProjectionCursor;
import io.haifa.agent.superharness.events.ProjectionGap;
import io.haifa.agent.superharness.events.ReplayResult;
import io.haifa.agent.superharness.events.RunTerminalKind;
import io.haifa.agent.superharness.server.config.McpRunTools;
import io.haifa.agent.superharness.server.config.SkillRunTools;
import io.haifa.agent.superharness.server.prompt.AgentPromptContext;
import io.haifa.agent.superharness.server.prompt.InputTrustBoundary;
import io.haifa.agent.superharness.server.run.DurableHaifaFoundation;
import io.haifa.agent.superharness.server.run.HaifaRunBridge;
import io.haifa.agent.superharness.server.run.PersistentThreadCatalog;
import io.haifa.agent.superharness.server.tool.clarification.ClarificationBoundaryRegistry;
import io.haifa.agent.superharness.server.tool.file.CurrentRunUploads;
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
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;

/** Frozen DeerFlow thread/run compatibility routes backed by the public Haifa Agent SDK. */
public final class DeerFlowThreadRunApi implements HttpHandler, AutoCloseable {
    private static final System.Logger INTERACTION_LOG =
            System.getLogger(DeerFlowThreadRunApi.class.getName() + ".interaction");
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
    private static final List<String> SUPPORTED_STREAM_MODES =
            List.of("messages-tuple", "updates", "custom");
    private static final Pattern SSE_CURSOR = Pattern.compile("^(\\d+)-0$");
    private static final int EVENT_RETENTION_CAPACITY = 8;
    private static final int EVENT_PAGE_SIZE = 4;
    /** A stream that has not looped for this long no longer holds the shared projector back. */
    private static final long LIVE_OBSERVER_STALE_NANOS = Duration.ofSeconds(1).toNanos();
    /** How long a bulk synchronization lets attached streams catch up before it goes ahead. */
    private static final long LIVE_OBSERVER_CATCH_UP_NANOS = Duration.ofSeconds(2).toNanos();
    private static final int DIAGNOSTIC_EVENT_PAGE_SIZE = 100;
    private static final int RUN_DIAGNOSTIC_EVENT_PAGE_BUDGET = 20;
    private static final int THREAD_USAGE_EVENT_PAGE_BUDGET = 50;
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
    /** Page size for reading the SDK per-message Tool-call grouping during the round scan. */
    private static final int MESSAGE_ROUND_SCAN_PAGE_SIZE = 100;
    private static final int TERMINAL_RUN_CACHE_LIMIT = 8;
    private static final java.time.Duration TERMINAL_EVENT_SETTLE_TIMEOUT =
            java.time.Duration.ofSeconds(2);
    static final Duration RUN_START_TIMEOUT = Duration.ofSeconds(5);
    /**
     * Thread metadata key holding the last MCP Run's {@code mcp_plugins} tri-state so an
     * exact restart can recompute the same projection against the current configuration.
     * Server-owned; stripped from client-supplied metadata.
     */
    public static final String SKILL_SELECTION_METADATA_KEY = "haifa_skill_names";
    public static final String SKILL_VERSIONS_METADATA_KEY = "haifa_skill_versions";
    public static final String LEAD_SIGNATURE_METADATA_KEY = "haifa_lead_prompt_signature";
    static final String AGENT_NAME_METADATA_KEY = "haifa_agent_name";
    static final String AGENT_BOOTSTRAP_METADATA_KEY = "haifa_agent_bootstrap";
    public static final String USER_INPUT_FRAMING_METADATA_KEY = "haifa_user_input_framing_from_sequence";
    static final String MCP_SELECTION_METADATA_KEY = "haifa_mcp_plugins";
    private static final String RUN_STREAM_MODES_METADATA_KEY = "haifa_run_stream_modes";
    // The client id of the newest Run's user message, kept so a restarted Gateway projects the same
    // in-flight identity. One entry per thread: a thread has one active Run at a time.
    private static final String RUN_CLIENT_HUMAN_ID_METADATA_KEY = "haifa_run_client_human_id";
    // A digest-only admission index, never a second model snapshot. The SDK owns recovery parameters.
    static final String MODEL_CONFIGURATION_DIGEST_METADATA_KEY = "haifa_model_configuration_digest";
    // Frozen DynamicContextMiddleware re-keys a submitted HumanMessage(id=X) to HumanMessage(id=X__user);
    // the frozen UI folds that suffix back onto the optimistic X so one Turn stays one visible bubble.
    private static final String INJECTED_USER_MESSAGE_ID_SUFFIX = "__user";

    private final ObjectMapper json;
    private final Function<JsonNode, HaifaRunBridge.ModelBinding> runModelResolver;
    private final Function<JsonNode, AgentPromptContext> agentPromptResolver;
    private final BiFunction<HaifaRunBridge, AgentRunId, Optional<FrozenInstructionDiagnostic>> instructionDiagnostics;
    private final Predicate<String> projectExists;
    private final PersistentProjectWorkspaceService workspaceChanges;
    private final Function<ThreadToolScope, List<? extends JavaTool<?, ?>>> runToolsForThread;
    private final McpProjectionProvider mcpProjections;
    private final SkillProjectionProvider skillProjections;
    private final DelegationProjectionProvider delegationProjections;
    // One public SDK capacity owner per Gateway, shared by all Thread and recovery Bridges.
    private final io.haifa.agent.runtime.api.ChildRunCapacity childRunCapacity;
    private final DeerFlowUploadContext uploadContext;
    private final PersistentThreadCatalog threadCatalog;
    private final DurableHaifaFoundation foundation;
    private final Map<String, ThreadRecord> threads = new ConcurrentHashMap<>();
    private final Map<String, RunRecord> runs = new ConcurrentHashMap<>();
    // A terminal Run can leave the hot cache while its bridge still owns SDK resources.
    // Keep process-lifetime ownership separate from the bounded Run projection cache.
    private final Set<HaifaRunBridge> ownedBridges = ConcurrentHashMap.newKeySet();
    // Test/observability seam: how many bridges this process built (each MCP projection
    // rebuild and restart-recovery bridge counts once).
    private final java.util.concurrent.atomic.AtomicLong bridgeBuilds =
            new java.util.concurrent.atomic.AtomicLong();
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
            Function<ThreadToolScope, List<? extends JavaTool<?, ?>>> runToolsForThread,
            McpProjectionProvider mcpProjections,
            SkillProjectionProvider skillProjections,
            DeerFlowUploadContext uploadContext)
            throws IOException {
        this(json, runModelResolver, projectExists, workspaceChanges, dataDirectory, continuationKey,
                runToolsForThread, mcpProjections, skillProjections, uploadContext,
                ignored -> AgentPromptContext.defaults(), HaifaRunBridge::frozenInstructionDiagnostic);
    }

    /** Test-only diagnostic delegation; production always queries the public SDK typed diagnostic. */
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
        this(json, runModelResolver, projectExists, workspaceChanges, dataDirectory, continuationKey,
                runToolsForThread, mcpProjections, skillProjections, uploadContext,
                ignored -> AgentPromptContext.defaults(), instructionDiagnostics);
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
        this(json, runModelResolver, projectExists, workspaceChanges, dataDirectory, continuationKey,
                runToolsForThread, mcpProjections, skillProjections, uploadContext, agentPromptResolver,
                instructionDiagnostics, (body, model) -> io.haifa.agent.superharness.server.config.SubagentRunConfiguration.disabled());
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
        this(json, runModelResolver, projectExists, workspaceChanges, dataDirectory, continuationKey,
                runToolsForThread, mcpProjections, skillProjections, uploadContext, agentPromptResolver,
                instructionDiagnostics, delegationProjections,
                new io.haifa.agent.runtime.api.ChildRunCapacity(
                        io.haifa.agent.superharness.server.config.SubagentRunConfiguration.Settings.defaults().runningCapacity()));
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
        this.childRunCapacity = java.util.Objects.requireNonNull(childRunCapacity, "childRunCapacity");
        this.json = json;
        this.runModelResolver = runModelResolver;
        this.agentPromptResolver = java.util.Objects.requireNonNull(agentPromptResolver, "agentPromptResolver");
        this.instructionDiagnostics = java.util.Objects.requireNonNull(instructionDiagnostics, "instructionDiagnostics");
        this.projectExists = projectExists;
        this.workspaceChanges = workspaceChanges;
        this.runToolsForThread = runToolsForThread;
        this.mcpProjections = mcpProjections == null ? McpProjectionProvider.none() : mcpProjections;
        this.skillProjections = skillProjections == null ? SkillProjectionProvider.none() : skillProjections;
        this.delegationProjections = java.util.Objects.requireNonNull(delegationProjections);
        this.uploadContext = java.util.Objects.requireNonNull(uploadContext, "uploadContext");
        this.threadCatalog = new PersistentThreadCatalog(json, dataDirectory);
        this.foundation = new DurableHaifaFoundation(dataDirectory, continuationKey);
        for (PersistentThreadCatalog.Entry entry : threadCatalog.list()) {
            threads.put(entry.threadId(), new ThreadRecord(entry));
        }
    }

    /**
     * Resolves the MCP declaration for a Run body and, separately, for a persisted
     * selection replayed against the current configuration after a restart. Keeping both
     * in one seam makes the "current config + that selection" recovery explicit.
     */
    interface McpProjectionProvider {
        McpRunTools.Projection forRun(JsonNode body);

        McpRunTools.Projection forSelection(McpRunTools.Selection selection);

        static McpProjectionProvider none() {
            return new McpProjectionProvider() {
                @Override
                public McpRunTools.Projection forRun(JsonNode body) {
                    return McpRunTools.Projection.empty();
                }

                @Override
                public McpRunTools.Projection forSelection(McpRunTools.Selection selection) {
                    return McpRunTools.Projection.empty();
                }
            };
        }
    }

    interface SkillProjectionProvider {
        SkillRunTools.Projection forRun(JsonNode body);

        SkillRunTools.Projection forRecovery(SkillRunTools.Selection selection);

        default SkillRunTools.Projection forRecovery(SkillRunTools.Selection selection, Map<String, String> versions) {
            return forRecovery(selection);
        }

        static SkillProjectionProvider none() {
            return new SkillProjectionProvider() {
                @Override
                public SkillRunTools.Projection forRun(JsonNode body) {
                    return null;
                }

                @Override
                public SkillRunTools.Projection forRecovery(SkillRunTools.Selection selection) {
                    return null;
                }
            };
        }
    }

    @FunctionalInterface
    interface DelegationProjectionProvider {
        io.haifa.agent.superharness.server.config.SubagentRunConfiguration forRun(
                JsonNode body, HaifaRunBridge.ModelBinding parent);
    }

    boolean hasThread(String threadId) {
        return threads.containsKey(threadId);
    }

    /** Package-private seam for rebuild assertions in the V2 MCP integration tests. */
    public long bridgeBuildCount() {
        return bridgeBuilds.get();
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
        // Server-owned: only a Run may record the MCP projection used for restart recovery.
        metadata.remove(MCP_SELECTION_METADATA_KEY);
        metadata.remove(RUN_STREAM_MODES_METADATA_KEY);
        metadata.remove(RUN_CLIENT_HUMAN_ID_METADATA_KEY);
        metadata.remove(SKILL_SELECTION_METADATA_KEY);
        metadata.remove(SKILL_VERSIONS_METADATA_KEY);
        metadata.remove(LEAD_SIGNATURE_METADATA_KEY);
        metadata.remove(MODEL_CONFIGURATION_DIGEST_METADATA_KEY);
        metadata.remove(AGENT_NAME_METADATA_KEY);
        metadata.remove(AGENT_BOOTSTRAP_METADATA_KEY);
        metadata.remove(USER_INPUT_FRAMING_METADATA_KEY);
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
        Optional<List<Map<String, Object>>> todos = resolveThreadTodos(thread);
        List<Map<String, Object>> response = new ArrayList<>(
                historyProjections(thread, limit, beforeSequence).stream()
                        .map(projection -> threadHistoryResponse(thread, projection, ordinals, todos))
                        .toList());
        if (beforeSequence == null) overlayHistoryHead(thread, response, limit, ordinals, todos);
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
        // before_seq belongs to the display feed, not the SDK transcript. Project its
        // positions before trimming the requested window (the SDK scan is already bounded).
        List<ConversationTurn> durableTurns = scanTurns(thread, Integer.MAX_VALUE);
        restorePendingInteractionRuns(thread, durableTurns);
        Map<Long, Integer> ordinals = computeTurnOrdinals(thread);
        List<Map<String, Object>> eligible = paginatedMessageRows(thread, durableTurns, ordinals);
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

    /**
     * SDK sequences include Tool turns; the saved card counter does not. Give the frozen
     * feed one order by inserting each card after its Run's human turn, reserving both
     * request and hidden-response slots. Later SDK turns receive the same two-slot offset
     * even while a reply is pending or a stale card is hidden. This also repairs legacy
     * saved card positions without changing Runtime state or the persisted catalog.
     */
    private List<Map<String, Object>> paginatedMessageRows(
            ThreadRecord thread, List<ConversationTurn> turns, Map<Long, Integer> ordinals) {
        List<PersistentThreadCatalog.InteractionDisplay> displays;
        synchronized (thread) { displays = List.copyOf(thread.interactionDisplays); }
        Map<Long, List<PersistentThreadCatalog.InteractionDisplay>> anchored = new HashMap<>();
        Set<String> anchoredRequests = new HashSet<>();
        for (var display : displays) {
            List<ConversationTurn> humans = turns.stream()
                    .filter(turn -> turn.role() == io.haifa.agent.core.message.MessageRole.USER
                            && turn.runId().map(AgentRunId::value).filter(display.runId()::equals).isPresent())
                    .toList();
            if (humans.isEmpty()) continue;
            ConversationTurn anchor = humans.stream()
                    .filter(turn -> !turn.createdAt().isAfter(display.createdAt()))
                    .reduce((left, right) -> right).orElse(humans.getFirst());
            anchored.computeIfAbsent(anchor.sequence(), ignored -> new ArrayList<>()).add(display);
            anchoredRequests.add(display.requestId());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        long offset = 0;
        for (ConversationTurn turn : turns) {
            long sequence = turn.sequence() + offset;
            Map<String, Object> row = messageRow(thread, turn, ordinals.getOrDefault(turn.sequence(), 0));
            row.put("seq", sequence);
            rows.add(row);
            List<PersistentThreadCatalog.InteractionDisplay> cards = anchored.getOrDefault(turn.sequence(), List.of());
            offset += appendInteractionRows(thread, cards, sequence, rows);
        }
        Set<String> durableHumanRuns = durableHumanRunIds(turns);
        long latest = turns.isEmpty() ? 0 : turns.getLast().sequence();
        for (RunRecord run : runs.values().stream()
                .filter(candidate -> candidate.threadId.equals(thread.threadId))
                .sorted((left, right) -> left.createdAt.compareTo(right.createdAt)).toList()) {
            if (!isInFlightWithoutDurableHuman(run, durableHumanRuns)) continue;
            long baseSequence = Math.max(run.humanSequence, latest + 1);
            long sequence = baseSequence + offset;
            rows.add(messageRow(run, sequence, run.createdAt, pendingHumanMessage(run)));
            List<PersistentThreadCatalog.InteractionDisplay> cards = displays.stream()
                    .filter(display -> display.runId().equals(run.runId.value())
                            && !anchoredRequests.contains(display.requestId())).toList();
            offset += appendInteractionRows(thread, cards, sequence, rows);
            latest = baseSequence;
        }
        return rows;
    }

    private long appendInteractionRows(
            ThreadRecord thread, List<PersistentThreadCatalog.InteractionDisplay> displays,
            long humanSequence, List<Map<String, Object>> rows) {
        long offset = 0;
        for (var display : displays) {
            long requestSequence = humanSequence + offset + 1;
            offset += 2;
            if (!isVisibleInteractionDisplay(thread, display)) continue;
            rows.add(messageRow(display.runId(), requestSequence, display.createdAt(), requestMessage(display)));
            if (display.answeredOption() != null) {
                rows.add(messageRow(display.runId(), requestSequence + 1, display.createdAt(), responseMessage(display)));
            }
        }
        return offset;
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
        // Client-generated id of this Turn's user message; keys the in-flight pending projection
        // only, never the settled transcript or replay. See RunRecord#clientHumanMessageId.
        String clientHumanMessageId = extractHumanMessageId(body.path("input").path("messages")).orElse(null);
        // Whether this Turn's user message is a visible chat bubble. The frozen UI must fold its
        // optimistic copy onto the live echo to keep the Run's own steps ordered below it; a hidden
        // Human-Input reply or a non-chat reply must never be projected as a new user bubble.
        boolean liveHumanEcho = !hiddenHumanMessage(body.path("input").path("messages"));
        Optional<DeerFlowInteractionHttpProjection.FrozenResponse> interactionResponse;
        try {
            interactionResponse = DeerFlowInteractionHttpProjection.parseResponse(
                    body.path("input").path("messages"));
        } catch (IllegalArgumentException invalid) {
            sendJson(exchange, 422, Map.of("detail", invalid.getMessage()));
            return;
        }
        DeerFlowInteractionHttpProjection.FrozenResponse clarificationAck = null;
        if (interactionResponse.isPresent()) {
            if (runInput != null) {
                sendJson(exchange, 422, Map.of("detail", "Haifa Run input mode cannot answer an Interaction"));
                return;
            }
            DeerFlowInteractionHttpProjection.FrozenResponse response = interactionResponse.orElseThrow();
            if (DeerFlowInteractionHttpProjection.CLARIFICATION_SOURCE.equals(response.source())) {
                // Frozen has no server-side Interaction for ask_clarification: the hidden reply is a
                // normal human message that opens the next Run. A forged or already answered
                // request_id therefore never triggers an approval action; it just starts a Turn.
                clarificationAck = response;
            } else {
                respondToInteraction(
                        exchange,
                        thread,
                        response,
                        message,
                        text(body, "on_disconnect").orElse("cancel"));
                return;
            }
        }
        HaifaRunBridge.ModelBinding binding;
        AgentPromptContext agentPrompt;
        try {
            binding = runModelResolver.apply(body);
            agentPrompt = agentPromptResolver.apply(body);
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 422, Map.of("detail", exception.getMessage()));
            return;
        }
        // Frozen UploadsMiddleware: the files of this message reach the model as verified references
        // (read with read_file); title and optimistic display keep the user's own text.
        // Frozen InputSanitizationMiddleware: the genuine user text is wrapped in the boundary the
        // system prompt declares; the <current_uploads> block stays outside the boundary.
        String boundaryInput = InputTrustBoundary.wrap(message);
        String modelInput = runInput == null
                ? uploadContext.modelInput(threadId, body.path("input").path("messages"), boundaryInput)
                : boundaryInput;
        if (runInput == null) {
            // list_uploaded_files reports historical uploads only; the current Run's files are
            // already surfaced through <current_uploads>.
            thread.currentRunUploads.set(
                    DeerFlowUploadContext.currentUploadFilenames(body.path("input").path("messages")));
        }
        // A Steer must never re-declare tools: it only feeds the active Run's own bridge.
        McpRunTools.Projection mcpProjection = runInput == null
                ? mcpProjections.forRun(body)
                : McpRunTools.Projection.empty();
        SkillRunTools.Projection skillProjection;
        try {
            skillProjection = runInput == null ? skillProjections.forRun(body) : null;
        } catch (IllegalArgumentException invalid) {
            sendJson(exchange, 422, Map.of("detail", invalid.getMessage()));
            return;
        } catch (IllegalStateException unavailable) {
            sendJson(exchange, 409, Map.of("detail", "Trusted Skill configuration is unavailable"));
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
                    // Steer continues an existing declaration; terminal history may admit a fresh
                    // Turn from trusted current configuration without migrating its old Runs.
                    if (!pendingPromptAvailable(thread, active)) {
                        sendJson(exchange, 409, Map.of("detail", "Pending Run prompt or agent identity is unavailable"));
                        return;
                    }
                    JsonNode requestedContext = body.path("context");
                    boolean declaresModelSettings = requestedContext.has("mode")
                            || requestedContext.has("thinking_enabled") || requestedContext.has("reasoning_effort")
                            || requestedContext.has("model_name");
                    Object originalDigest = thread.metadata.get(MODEL_CONFIGURATION_DIGEST_METADATA_KEY);
                    String expectedDigest = originalDigest instanceof String value ? value
                            : active.bridge.modelConfigurationDigest();
                    if (declaresModelSettings && !expectedDigest.equals(binding.snapshot().configurationDigest())) {
                        sendJson(exchange, 409, Map.of("detail", "Active Run model settings are frozen"));
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
                        // A pre-M2 active Run retains its frozen raw-input semantics. Its first
                        // USER Turn may not yet be durable, so Steer cannot establish a sequence
                        // boundary without also misclassifying that old Turn. New M2 Runs have
                        // provenance from admission and continue to receive framed Steer text.
                        result = active.bridge.submitInput(
                                new RunInputCommand(active.runId, idempotencyKey,
                                        thread.framingFromSequence() == null ? message : boundaryInput));
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
            boolean createdBridge = thread.bridge == null;
            HaifaRunBridge selectedBridge;
            boolean switchingModel;
            boolean switchingPlanMode;
            boolean switchingMcp;
            boolean switchingSkills;
            boolean switchingPrompt;
            boolean switchingDelegation;
            io.haifa.agent.superharness.server.config.SubagentRunConfiguration delegation;
            try {
                delegation = delegationProjections.forRun(body, binding);
            } catch (IllegalArgumentException invalid) {
                sendJson(exchange, 422, Map.of("detail", invalid.getMessage()));
                return;
            } catch (IllegalStateException unavailable) {
                sendJson(exchange, 409, Map.of("detail", "Child configuration is unavailable"));
                return;
            }
            boolean isPlanMode = DeerFlowReasoningSettings.isPlanMode(body.path("context"));
            try {
                if (thread.bridge == null) {
                    HaifaRunBridge bridge = newBridge(binding, thread, mcpProjection, skillProjection, agentPrompt, delegation, isPlanMode);
                    // Restoring the Conversation is read-only; claim the selected model only
                    // after the SDK has admitted the new Run successfully.
                    attachBridge(thread, bridge, false);
                }
                // A Conversation is durable across Turns, but the SDK freezes its model per Run.
                // Keep the old Run's bridge for replay/Interaction; only the new Turn changes model.
                selectedBridge = thread.bridge;
                switchingModel = !selectedBridge.matchesModelBinding(binding);
                switchingPlanMode = selectedBridge.isPlanMode() != isPlanMode;
                // A configuration change only affects the tool snapshot of later Runs; a Turn whose
                // MCP projection differs from the attached bridge rebuilds it rather than hot-editing
                // the active Run.
                switchingMcp = !selectedBridge.mcpSignature().equals(mcpProjection.fingerprint());
                switchingSkills = !selectedBridge.skillSignature().equals(
                        skillProjection == null ? "" : skillProjection.fingerprint());
                // The prompt detects SOUL changes; identity also governs the retained Tool callbacks
                // and must rebuild independently when bootstrap/default prompts have identical text.
                switchingPrompt = !selectedBridge.matchesAgentIdentity(agentPrompt)
                        || !selectedBridge.promptSignature().equals(
                                HaifaRunBridge.promptSignatureFor(toolsForThread(thread, agentPrompt), skillProjection, agentPrompt, delegation,
                                        selectedBridge.mcpToolNames()));
                switchingDelegation = !selectedBridge.delegationSignature().equals(
                        delegation.signature(toolsForThread(thread, agentPrompt), mcpProjection.fingerprint(),
                                skillProjection == null ? "" : skillProjection.fingerprint(), selectedBridge.mcpToolNames()));
                if (switchingModel || switchingMcp || switchingSkills || switchingPrompt || switchingDelegation || switchingPlanMode) {
                    selectedBridge = newBridge(binding, thread, mcpProjection, skillProjection, agentPrompt, delegation, isPlanMode);
                }
            } catch (RuntimeException failure) {
                if (createdBridge && thread.bridge != null) {
                    ownedBridges.remove(thread.bridge);
                    thread.bridge.close();
                    thread.bridge = null;
                }
                sendJson(exchange, 500, Map.of("detail", "Run could not start"));
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
            long framingFromSequence;
            try {
                Long existingFraming = thread.framingFromSequence();
                // The SDK sequence, rather than the UI overlay sequence, distinguishes old raw
                // Turns from newly framed input. No legacy Run is active when a new Turn admits.
                framingFromSequence = existingFraming != null ? existingFraming
                        : thread.persistedSessionId == null ? 1
                        : Math.addExact(latestVisibleTurnSequence(thread, selectedBridge), 1);
                started = selectedBridge.start(threadId, modelInput, Optional.ofNullable(thread.session));
            } catch (RuntimeException failure) {
                // A newly attached bridge uses the requested binding, so createdBridge and
                // switchingModel cannot both be true. On a failed switch the old bridge stays
                // attached for durable history and a subsequent Turn; only the rejected new
                // bridge is closed here.
                if (switchingModel || switchingMcp || switchingSkills || switchingPrompt || switchingDelegation || createdBridge) {
                    if (createdBridge) thread.bridge = null;
                    ownedBridges.remove(selectedBridge);
                    selectedBridge.close();
                }
                workspaceChanges.discardRunChanges(staged);
                sendJson(exchange, 500, Map.of("detail", "Run could not start"));
                return;
            }
            if (switchingModel || switchingMcp || switchingSkills || switchingPrompt || switchingDelegation) {
                thread.bridge = selectedBridge;
                ownedBridges.add(selectedBridge);
            }
            thread.modelBindingIdentity = selectedBridge.bindingIdentity();
            Map<String, Object> modelMetadata = new LinkedHashMap<>(thread.metadata);
            modelMetadata.put(MODEL_CONFIGURATION_DIGEST_METADATA_KEY, binding.snapshot().configurationDigest());
            thread.metadata = modelMetadata;
            boolean workspaceRecordingEnabled = false;
            if (staged != null) {
                try {
                    workspaceChanges.publishRunChanges(staged, started.runId().value());
                    workspaceRecordingEnabled = true;
                } catch (RuntimeException failure) {
                    // Recording is diagnostic evidence and cannot block the Run, but a swallowed
                    // publish failure silently disables workspace changes for this Run. Record the
                    // error family and the underlying file-system reason only; never file content.
                    if (!closed) {
                        INTERACTION_LOG.log(System.Logger.Level.WARNING,
                                "Workspace baseline publish failed for run " + started.runId().value()
                                        + " (" + workspaceFailureDetail(failure) + ")");
                    }
                    workspaceChanges.discardRunChanges(staged);
                }
            }
            thread.session = started.session();
            thread.persistedSessionId = started.session().sessionId();
            thread.updatedAt = Instant.now();
            thread.activeRunId = started.runId().value();
            thread.latestRunId = started.runId().value();
            // The last Run's MCP selection is the recovery projection for a later restart.
            // A Run that produced no MCP declaration clears any stale selection.
            if (mcpProjection.specs().isEmpty()) {
                thread.clearMcpSelection();
            } else {
                thread.setMcpSelection(mcpProjection.selection());
            }
            // Product prompt inputs of the admitted Turn; the SDK owns frozen Skill execution.
            thread.setLeadPromptSelection(skillProjection, selectedBridge.promptSignature(), agentPrompt);
            thread.setFramingFromSequence(framingFromSequence);
            long humanSequence = thread.nextMessageSequence;
            thread.nextMessageSequence += 2;
            run = new RunRecord(
                    started.runId(),
                    threadId,
                    text(body, "assistant_id").orElse(null),
                    message,
                    clientHumanMessageId,
                    liveHumanEcho,
                    selectedBridge,
                    Instant.now(),
                    humanSequence,
                    workspaceRecordingEnabled,
                    requestedStreamModes(body),
                    titleCandidate);
            runs.put(run.runId.value(), run);
            thread.setRunStreamModes(run.runId, run.streamModes);
            thread.setClientHumanMessageId(run.runId, clientHumanMessageId);
            if (clarificationAck != null) {
                markClarificationAnswered(thread, clarificationAck, run);
            }
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
            if (!pendingPromptAvailable(thread, run)) {
                sendJson(exchange, 409, Map.of("detail", "Pending Run prompt or agent identity is unavailable"));
                return;
            }
            // A read-only fallback bridge may expose the durable pending card, but must never
            // admit its side effect if the original Run's model revision is unavailable.
            if (thread.modelBindingIdentity != null
                    && !thread.modelBindingIdentity.equals(run.bridge.bindingIdentity())) {
                sendJson(exchange, 409, Map.of("detail", "Pending Run model is unavailable"));
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
                            || !pending.orElseThrow().requestId().value().equals(response.requestId())) {
                        sendJson(exchange, 409, Map.of("detail", "Thread has no matching Interaction"));
                        return;
                    }
                    try {
                        // A legacy/restarted Run may have an SDK pending view but no saved
                        // presentation yet. Never accept its side effect before the card is durable.
                        ensureSavedPendingDisplay(thread, run, pending.orElseThrow());
                    } catch (IOException | IllegalArgumentException unavailablePresentation) {
                        sendJson(exchange, 503, Map.of(
                                "detail", "Interaction card could not be saved; approval was not submitted"));
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
                    List<PersistentThreadCatalog.InteractionDisplay> updated =
                            new ArrayList<>(thread.interactionDisplays);
                    boolean savedDisplay = false;
                    for (int index = 0; index < updated.size(); index++) {
                        var display = updated.get(index);
                        if (display.runId().equals(run.runId.value())
                                && display.requestId().equals(response.requestId())) {
                            updated.set(index, display.answered(response.optionId()));
                            savedDisplay = true;
                            break;
                        }
                    }
                    if (!savedDisplay) throw new IllegalStateException(
                            "Saved Interaction card disappeared after SDK accepted the response");
                    thread.interactionDisplays = List.copyOf(updated);
                    try {
                        persistThread(thread);
                    } catch (IOException | RuntimeException failedPersistence) {
                        // The SDK has already accepted this response. Keep the in-process
                        // display answered, expose the degraded durability explicitly, and
                        // continue the same Run. A restart filters the stale unaccepted card
                        // against the SDK's current pending ID.
                        exchange.getResponseHeaders().set(
                                "X-Haifa-Interaction-Presentation", "accepted-not-persisted");
                        INTERACTION_LOG.log(System.Logger.Level.ERROR,
                                "INTERACTION_PRESENTATION_SAVE_AFTER_ACCEPT_FAILURE run="
                                        + run.runId.value() + " request=" + response.requestId()
                                        + " sdk_receipt=ACCEPTED cause="
                                        + failedPersistence.getClass().getSimpleName());
                    }
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
                        + " sourceSite=" + interactionFailureSourceSite(failure)
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

    /** Safe location only: no exception message, arguments, filename, or stack dump. */
    static String interactionFailureSourceSite(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Throwable deepest = failure;
        for (int depth = 0; deepest != null && depth < 32; depth++) {
            if (!seen.add(deepest)) return "unavailable";
            Throwable next = deepest.getCause();
            if (next != null) {
                deepest = next;
                continue;
            }
            for (StackTraceElement frame : deepest.getStackTrace()) {
                String type = frame.getClassName();
                String method = frame.getMethodName();
                if (type.matches("io\\.haifa\\.[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
                        && method.matches("[A-Za-z_$][A-Za-z0-9_$]*|<init>|<clinit>")) {
                    return type + "#" + method + ":" + frame.getLineNumber();
                }
            }
            return "unavailable";
        }
        return "unavailable";
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
            HaifaRunBridge.ModelBinding binding = null;
            String lastBinding = thread.modelBindingIdentity;
            if (lastBinding != null && lastBinding.lastIndexOf('@') > 0) {
                var resolverBody = json.createObjectNode();
                resolverBody.putObject("context")
                        .put("model_name", lastBinding.substring(0, lastBinding.lastIndexOf('@')))
                        .put("mode", "flash").put("thinking_enabled", false);
                try {
                    binding = runModelResolver.apply(resolverBody);
                } catch (IllegalArgumentException unavailable) {
                    // Historical Conversation reads remain possible when a model is disabled.
                    // Interaction admission below still requires the exact original binding.
                }
            }
            if (binding == null) binding = runModelResolver.apply(json.createObjectNode());
            // The same published Tool registrations must be available when the SDK resumes
            // an approved Interaction after a process restart. The SDK still owns the
            // pending request and its side-effect admission; this bridge only restores it.
            // The last Run's persisted mcp_plugins selection is recomputed against the
            // current configuration; a thread with no recorded MCP Run restores no MCP.
            McpRunTools.Selection selection = thread.mcpSelection();
            McpRunTools.Projection mcpProjection = selection == null
                    ? McpRunTools.Projection.empty()
                    : mcpProjections.forSelection(selection);
            // The current profile may be B while the SDK's pending Run owns snapshot A.
            // Historical reads remain available; execution checks A's hash below before respond.
            AgentPromptContext restored = AgentPromptContext.defaults();
            if (validAgentMetadata(thread)) {
                var resolverBody = json.createObjectNode();
                var context = resolverBody.putObject("context");
                String name = (String) thread.metadata.get(AGENT_NAME_METADATA_KEY);
                if (!name.isEmpty()) context.put("agent_name", name);
                context.put("is_bootstrap", (Boolean) thread.metadata.get(AGENT_BOOTSTRAP_METADATA_KEY));
                try {
                    restored = agentPromptResolver.apply(resolverBody);
                } catch (IllegalArgumentException unavailable) {
                    // A deleted agent does not prevent read-only historical access.
                }
            }
            HaifaRunBridge bridge = newBridge(binding, thread, mcpProjection,
                    skillProjections.forRecovery(thread.skillSelection(), thread.skillVersions()), restored);
            boolean claimBinding = thread.modelBindingIdentity == null
                    || thread.modelBindingIdentity.equals(bridge.bindingIdentity());
            // Conversation/run reads use shared SQLite. The last selected model is preferred
            // so a pending Run resumes with its own model after a gateway restart.
            attachBridge(thread, bridge, claimBinding);
            return bridge;
        }
    }

    private void attachBridge(ThreadRecord thread, HaifaRunBridge bridge) {
        attachBridge(thread, bridge, true);
    }

    private HaifaRunBridge newBridge(
            HaifaRunBridge.ModelBinding binding,
            ThreadRecord thread,
            McpRunTools.Projection mcpProjection,
            SkillRunTools.Projection skillProjection,
            AgentPromptContext agentPrompt) {
        // Restart history only reads SDK facts. A later Run separately admits fresh declarations.
        return newBridge(binding, thread, mcpProjection, skillProjection, agentPrompt,
                io.haifa.agent.superharness.server.config.SubagentRunConfiguration.disabled(), false);
    }

    private HaifaRunBridge newBridge(HaifaRunBridge.ModelBinding binding, ThreadRecord thread,
            McpRunTools.Projection mcpProjection, SkillRunTools.Projection skillProjection,
            AgentPromptContext agentPrompt,
            io.haifa.agent.superharness.server.config.SubagentRunConfiguration delegation) {
        return newBridge(binding, thread, mcpProjection, skillProjection, agentPrompt, delegation, false);
    }

    private HaifaRunBridge newBridge(HaifaRunBridge.ModelBinding binding, ThreadRecord thread,
            McpRunTools.Projection mcpProjection, SkillRunTools.Projection skillProjection,
            AgentPromptContext agentPrompt,
            io.haifa.agent.superharness.server.config.SubagentRunConfiguration delegation,
            boolean isPlanMode) {
        bridgeBuilds.incrementAndGet();
        return new HaifaRunBridge(binding, toolsForThread(thread, agentPrompt), foundation, mcpProjection,
                skillProjection, agentPrompt, delegation, childRunCapacity, isPlanMode);
    }

    private List<? extends JavaTool<?, ?>> toolsForThread(ThreadRecord thread, AgentPromptContext agentPrompt) {
        Object projectId = thread.metadata.get("deerflow_project_id");
        Optional<String> project = projectId instanceof String value && !value.isBlank()
                ? Optional.of(value)
                : Optional.empty();
        return runToolsForThread.apply(
                new ThreadToolScope(thread.threadId, project, thread.currentRunUploads, agentPrompt));
    }

    private static boolean validAgentMetadata(ThreadRecord thread) {
        Object name = thread.metadata.get(AGENT_NAME_METADATA_KEY);
        return name instanceof String value
                && (value.isEmpty() || value.matches("[a-z0-9-]+"))
                && thread.metadata.get(AGENT_BOOTSTRAP_METADATA_KEY) instanceof Boolean;
    }

    private boolean pendingPromptAvailable(ThreadRecord thread, RunRecord run) {
        if (!validPromptMetadata(thread)) return false;
        String expected = (String) thread.metadata.get(LEAD_SIGNATURE_METADATA_KEY);
        try {
            var body = json.createObjectNode();
            var context = body.putObject("context");
            String name = (String) thread.metadata.get(AGENT_NAME_METADATA_KEY);
            if (!name.isEmpty()) context.put("agent_name", name);
            context.put("is_bootstrap", (Boolean) thread.metadata.get(AGENT_BOOTSTRAP_METADATA_KEY));
            agentPromptResolver.apply(body);
            return instructionDiagnostics.apply(run.bridge, run.runId)
                    .filter(diagnostic -> diagnostic.runId().equals(run.runId)
                            && diagnostic.instructionContentHash().equals("sha256:" + expected))
                    .isPresent();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static boolean validPromptMetadata(ThreadRecord thread) {
        return validAgentMetadata(thread)
                && thread.metadata.get(LEAD_SIGNATURE_METADATA_KEY) instanceof String signature
                && signature.matches("[0-9a-f]{64}");
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
            ownedBridges.add(bridge);
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
        long latestDisplaySequence = thread.interactionDisplays.stream()
                .mapToLong(display -> display.requestSequence() + 1)
                .max().orElse(0);
        long latestClarificationSequence = thread.clarificationDisplays.stream()
                .mapToLong(display -> display.requestSequence() + 1)
                .max().orElse(0);
        thread.nextMessageSequence = Math.max(
                Math.max(latestSequence, latestDisplaySequence), latestClarificationSequence) + 1;
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
                    Optional.ofNullable(thread.modelBindingIdentity),
                    thread.interactionDisplays,
                    thread.clarificationDisplays));
        }
    }

    /**
     * Detect the run's frozen clarification boundary from the durable SDK ToolCall view, persist the
     * card presentation and best-effort cancel the model continuation. Correctness never depends on
     * the cancel: the projection is bounded by the same durable ToolCall on every read.
     */
    private void observeClarificationBoundary(ThreadRecord thread, RunRecord run) {
        if (run.clarificationObserved) {
            return;
        }
        List<ToolCallView> calls;
        try {
            calls = run.bridge.toolCalls(run.runId);
        } catch (RuntimeException unavailable) {
            return;
        }
        int index = DeerFlowToolTranscriptProjection.clarificationBoundaryIndex(calls);
        if (index < 0) {
            return;
        }
        ToolCallView boundary = calls.get(index);
        Map<String, Object> payload =
                DeerFlowToolTranscriptProjection.clarificationPayload(boundary).orElse(null);
        if (payload == null) {
            return;
        }
        run.clarificationObserved = true;
        synchronized (thread) {
            try {
                ensureSavedClarificationDisplay(thread, run, boundary, payload);
            } catch (IOException | IllegalArgumentException failed) {
                INTERACTION_LOG.log(
                        System.Logger.Level.WARNING,
                        "CLARIFICATION_DISPLAY_PERSIST_FAILURE run=" + run.runId.value() + " request="
                                + DeerFlowToolTranscriptProjection.clarificationRequestId(boundary, payload)
                                + " cause=" + failed.getClass().getSimpleName());
            }
        }
        try {
            if (!run.bridge.snapshot(run.runId).status().isTerminal()) {
                run.bridge.cancel(run.runId);
            }
        } catch (RuntimeException ignored) {
            // Best-effort: a failed/late cancel cannot affect the boundary projection.
        }
    }

    private PersistentThreadCatalog.ClarificationDisplay ensureSavedClarificationDisplay(
            ThreadRecord thread, RunRecord run, ToolCallView boundary, Map<String, Object> payload)
            throws IOException {
        String requestId = DeerFlowToolTranscriptProjection.clarificationRequestId(boundary, payload);
        var saved = thread.clarificationDisplays.stream()
                .filter(display -> display.requestId().equals(requestId))
                .findFirst();
        if (saved.isPresent()) {
            return saved.orElseThrow();
        }
        long latest = thread.clarificationDisplays.stream()
                .mapToLong(PersistentThreadCatalog.ClarificationDisplay::requestSequence)
                .max()
                .orElse(0);
        long sequence = Math.max(Math.max(thread.nextMessageSequence, run.humanSequence + 1), latest + 1);
        var display = new PersistentThreadCatalog.ClarificationDisplay(
                run.runId.value(),
                requestId,
                sequence,
                boundary.completedAt().orElseGet(Instant::now),
                boundary.id().value(),
                null,
                null,
                null,
                null);
        List<PersistentThreadCatalog.ClarificationDisplay> updated =
                new ArrayList<>(thread.clarificationDisplays);
        updated.add(display);
        PersistentThreadCatalog.validateClarificationDisplays(updated);
        List<PersistentThreadCatalog.ClarificationDisplay> previous = thread.clarificationDisplays;
        thread.clarificationDisplays = List.copyOf(updated);
        try {
            persistThread(thread);
        } catch (IOException | RuntimeException failedPersistence) {
            thread.clarificationDisplays = previous;
            throw new IOException("clarification card presentation could not be persisted", failedPersistence);
        }
        return display;
    }

    private void markClarificationAnswered(
            ThreadRecord thread,
            DeerFlowInteractionHttpProjection.FrozenResponse response,
            RunRecord run) {
        List<PersistentThreadCatalog.ClarificationDisplay> updated =
                new ArrayList<>(thread.clarificationDisplays);
        boolean changed = false;
        for (int index = 0; index < updated.size(); index++) {
            var display = updated.get(index);
            if (display.answeredKind() == null && display.requestId().equals(response.requestId())) {
                updated.set(index, display.answered(
                        response.responseKind(), response.optionId(), response.value(), run.runId.value()));
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        try {
            PersistentThreadCatalog.validateClarificationDisplays(updated);
            thread.clarificationDisplays = List.copyOf(updated);
        } catch (IllegalArgumentException invalid) {
            INTERACTION_LOG.log(
                    System.Logger.Level.WARNING,
                    "CLARIFICATION_ANSWER_PERSIST_FAILURE run=" + run.runId.value()
                            + " request=" + response.requestId());
        }
    }

    private boolean runHasClarificationBoundary(RunRecord run) {
        try {
            return DeerFlowToolTranscriptProjection.hasClarificationBoundary(run.bridge.toolCalls(run.runId));
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Marks the durable user turn that answered a clarification card as hidden and carries the
     * frozen {@code additional_kwargs.human_input_response} so a reload derives the same answered
     * state the card showed live.
     */
    private void applyClarificationAnswer(
            ThreadRecord thread, ConversationTurn turn, Map<String, Object> message) {
        if (turn.role() != io.haifa.agent.core.message.MessageRole.USER) {
            return;
        }
        String runId = turn.runId().map(AgentRunId::value).orElse(null);
        if (runId == null) {
            return;
        }
        synchronized (thread) {
            for (var display : thread.clarificationDisplays) {
                if (runId.equals(display.answeredRunId()) && display.answeredKind() != null) {
                    Map<String, Object> additional = new LinkedHashMap<>();
                    additional.put("hide_from_ui", true);
                    additional.put("human_input_response", clarificationAnswerWire(display));
                    message.put("additional_kwargs", Map.copyOf(additional));
                    return;
                }
            }
        }
    }

    private static Map<String, Object> clarificationAnswerWire(
            PersistentThreadCatalog.ClarificationDisplay display) {
        var response = new DeerFlowInteractionHttpProjection.FrozenResponse(
                DeerFlowInteractionHttpProjection.CLARIFICATION_SOURCE,
                display.requestId(),
                display.answeredKind(),
                display.answeredOptionId(),
                display.answeredValue());
        return response.toWireValue();
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
        // The frozen frontend reaches the Gateway through the Next.js rewrite proxy, which gzips
        // proxied responses and holds a compressed event stream back until the Run ends. Frozen
        // DeerFlow keeps the stream uncompressed in nginx; no-transform tells the Next.js proxy
        // the same thing.
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
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

        Object observer = new Object();
        try (OutputStream output = exchange.getResponseBody()) {
            writeCommittedSse(output, () -> {
                try (DeerFlowSubtaskStream subtasks = run.streamModes.contains("custom")
                        ? new DeerFlowSubtaskStream(run.bridge, run.runId) : null) {
                    if (includeMetadata) {
                        writeSse(
                                output,
                                "metadata",
                                Map.of("run_id", run.runId.value(), "thread_id", run.threadId),
                                null);
                        // Echo this Turn's visible user bubble as the first message frame, under the
                        // same identity the frozen UI folds with its optimistic copy (client id plus
                        // `__user`). Without it the live Run's first step can merge above the still
                        // optimistic user bubble, so the frozen UI classifies the step as a settled
                        // pre-submit step and renders the subtask card above the user and without
                        // progress. The frame is display-only, carries no seq, and is deduped by
                        // identity against the durable history echo.
                        if (run.liveHumanEcho
                                && run.streamModes.contains("messages-tuple")
                                && !run.bridge.snapshot(run.runId).status().isTerminal()) {
                            writeSse(
                                    output,
                                    "messages",
                                    List.of(pendingHumanMessage(run), Map.of("langgraph_node", "agent")),
                                    null);
                        }
                    }
                    ProjectionCursor delivered = initialCursor;
                    long nextHeartbeat = System.nanoTime() + java.time.Duration.ofMillis(100).toNanos();
                    long terminalWithoutEventSince = 0;
                    long childTerminalWaitSince = 0;
                    boolean childTerminalDrainExpired = false;
                    while (true) {
                        ReplayResult replay = run.projector.replayAfter(delivered);
                        if (replay instanceof ReplayResult.Gap gap) {
                            writeReplayGap(output, run, gap.gap(), gap.authoritativeView(), delivered);
                            return;
                        }
                        ReplayResult.Events events = (ReplayResult.Events) replay;
                        for (ProjectedRunEvent event : events.events()) {
                            if (subtasks != null && !childTerminalDrainExpired && event.terminalKind().terminal()
                                    && !subtasks.drain(true, frame -> writeSse(output, "custom", frame, null))) {
                                if (childTerminalWaitSince == 0) childTerminalWaitSince = System.nanoTime();
                                if (System.nanoTime() - childTerminalWaitSince >= TERMINAL_EVENT_SETTLE_TIMEOUT.toNanos()) {
                                    writeChildDrainDiagnostic(output, run);
                                    childTerminalDrainExpired = true;
                                } else {
                                    // Retain this Parent cursor while the bounded drain can still finish.
                                    break;
                                }
                            }
                            delivered = event.cursor();
                            if (writeProjectedEvent(output, thread, run, event)) {
                                return;
                            }
                        }
                        if (subtasks != null && !childTerminalDrainExpired) {
                            subtasks.drain(false, frame -> writeSse(output, "custom", frame, null));
                        }
                        run.observerDelivered(observer, delivered.sequence());
                        Optional<AppendResult.Gap> sourceGap = synchronizeOnePage(run);
                        observeClarificationBoundary(thread, run);
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
                                if (subtasks != null && !childTerminalDrainExpired
                                        && !subtasks.drain(true, frame -> writeSse(output, "custom", frame, null))) {
                                    if (childTerminalWaitSince == 0) childTerminalWaitSince = System.nanoTime();
                                    if (System.nanoTime() - childTerminalWaitSince >= TERMINAL_EVENT_SETTLE_TIMEOUT.toNanos()) {
                                        writeChildDrainDiagnostic(output, run);
                                        childTerminalDrainExpired = true;
                                    } else {
                                        try {
                                            Thread.sleep(10);
                                        } catch (InterruptedException interrupted) {
                                            Thread.currentThread().interrupt();
                                            return;
                                        }
                                        continue;
                                    }
                                }
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
                }
            });
        } catch (IOException disconnected) {
            if (creator && "cancel".equals(onDisconnect)) {
                run.bridge.cancel(run.runId);
            }
        } finally {
            run.observerLeft(observer);
            observeClarificationBoundary(thread, run);
            synchronized (thread) {
                boolean terminal = isKnownTerminal(() -> Optional.of(run.bridge.snapshot(run.runId)));
                if (terminal && run.runId.value().equals(thread.activeRunId)) {
                    thread.activeRunId = null;
                }
                thread.updatedAt = Instant.now();
            }
            if (isKnownTerminal(() -> run.bridge.findRun(run.runId))) {
                ClarificationBoundaryRegistry.clear(run.runId.value());
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

    /**
     * The projector is shared by every stream attached to the Run and keeps only a short window.
     * Pulling durable events faster than an attached stream delivers them would push that stream
     * out of the window and end it with a replay gap, so a pull waits for the slowest live stream.
     */
    private boolean wouldOutrunLiveObserver(RunRecord run) {
        java.util.OptionalLong slowest = run.slowestLiveObserver(System.nanoTime());
        return slowest.isPresent()
                && run.projector.appendingWouldDropUndelivered(EVENT_PAGE_SIZE, slowest.getAsLong());
    }

    private Optional<AppendResult.Gap> synchronizeOnePage(RunRecord run) {
        synchronized (run) {
            if (wouldOutrunLiveObserver(run)) {
                return Optional.empty();
            }
            var page = run.bridge.events(run.runId, run.sdkCursor, EVENT_PAGE_SIZE);
            for (AgentRunEvent source : page.items()) {
                // Non-wire durable facts are read by the transcript/Child projections.
                // Late Child settlement must not append after the Parent terminal cursor.
                if (source.payload() instanceof RunEventPayloads.MessageCommitted
                        || source.payload() instanceof RunEventPayloads.ChildRunLifecycle) continue;
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

    /** SDK cancellation may settle Child execution after its Parent is already terminal. */
    private void writeChildDrainDiagnostic(OutputStream output, RunRecord run) throws IOException {
        if (run.bridge.children(run.runId).stream().allMatch(child -> child.status().isTerminal())) {
            writeSse(output, "error", Map.of("code", "child_terminal_projection_unavailable",
                    "message", "Child terminal facts could not be drained before Parent end"), null);
        }
        // Never invent a Child terminal fact or withhold the actual Parent terminal/end.
        // A later join reads the SDK's durable Child facts with a fresh connection cursor.
    }

    private void synchronizeAllAvailable(RunRecord run) {
        synchronized (run) {
            long catchUpDeadline = System.nanoTime() + LIVE_OBSERVER_CATCH_UP_NANOS;
            while (true) {
                awaitLiveObservers(run, catchUpDeadline);
                var page = run.bridge.events(run.runId, run.sdkCursor, EVENT_PAGE_SIZE);
                for (AgentRunEvent source : page.items()) {
                    if (source.payload() instanceof RunEventPayloads.MessageCommitted
                            || source.payload() instanceof RunEventPayloads.ChildRunLifecycle) continue;
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

    /**
     * Gives attached streams a bounded chance to deliver what is retained. Waiting on the Run
     * monitor releases it, which lets those streams run their own synchronization step meanwhile.
     */
    private void awaitLiveObservers(RunRecord run, long deadlineNanos) {
        while (wouldOutrunLiveObserver(run) && System.nanoTime() < deadlineNanos) {
            try {
                run.wait(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
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
            // A clarification Run projects only up to its tool result: no later model text.
            if (runHasClarificationBoundary(run)) {
                return false;
            }
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
            if (event.source().payload() instanceof RunEventPayloads.DeliveryLifecycle
                    && "budget.threshold-reached".equals(event.source().eventType())) {
                // Frozen DeerFlow has no budget concept and its UI consumes no such event. Skip the
                // frame without ending the stream; the Run's later events project normally. Handling
                // it in the one frame writer keeps the live stream, a mid-Run reconnect and a
                // terminal replay identical.
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
                && run.streamModes.contains("messages-tuple")
                && !runHasClarificationBoundary(run)) {
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
                Optional<InteractionView> pending = run.bridge.pendingInteraction(run.runId);
                if (pending.isEmpty()
                        || !pending.orElseThrow().requestId().value().equals(projectedRequestId)) {
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
                PersistentThreadCatalog.InteractionDisplay display;
                try {
                    display = ensureSavedPendingDisplay(thread, run, pending.orElseThrow());
                } catch (IOException failedPersistence) {
                    writeSse(output, "error", Map.of(
                            "code", "interaction_projection_not_persisted",
                            "request_id", projectedRequestId,
                            "message", "Interaction card could not be saved"), cursor);
                    return true;
                } catch (IllegalArgumentException unsupported) {
                    writeSse(output, "error", Map.of(
                            "code", "unsupported_interaction_projection",
                            "request_id", projectedRequestId,
                            "message", "Pending Interaction cannot be projected"), cursor);
                    return true;
                }
                if (!projectedRequestId.equals(run.interactionRequestId)) {
                    run.acceptedInteractionResponse = null;
                }
                run.interactionRequestId = projectedRequestId;
                run.interactionRequestSequence = display.requestSequence();
                run.interactionResponseSequence = display.requestSequence() + 1;
                run.assistantSequence = display.requestSequence() + 2;
                thread.nextMessageSequence = Math.max(
                        thread.nextMessageSequence, display.requestSequence() + 2);
                run.interactionRequestMessage = requestMessage(display);
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
        List<ToolCallView> calls = run.bridge.toolCalls(run.runId);
        int selectedIndex = -1;
        for (int index = 0; index < calls.size(); index++) {
            if (calls.get(index).id().value().equals(toolCallId)) {
                selectedIndex = index;
                break;
            }
        }
        int boundary = DeerFlowToolTranscriptProjection.clarificationBoundaryIndex(calls);
        if (boundary >= 0 && (selectedIndex < 0 || selectedIndex > boundary)) {
            // A tool call that the model emitted after ask_clarification is never projected.
            return;
        }
        Optional<ToolCallView> selected =
                selectedIndex < 0 ? Optional.empty() : Optional.of(calls.get(selectedIndex));
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
        // One assistant message per model round. When the SDK message view already names the whole
        // round, the complete call list is written once; otherwise the known calls are written first
        // and the same message id is rewritten with the fuller list, so a parallel round never opens
        // two frontend subagent groups.
        List<ToolCallView> round = liveRound(run.bridge, run.runId, calls, call.id().value());
        writeToolMessage(output, run, DeerFlowToolTranscriptProjection.requestMessage(round), null);
        Optional<Map<String, Object>> result = DeerFlowToolTranscriptProjection.resultMessage(call);
        if (result.isPresent()) {
            writeToolMessage(output, run, result.orElseThrow(), cursor);
        }
    }

    /**
     * The whole model round that owns {@code toolCallId}. The SDK message view is authoritative:
     * its assistant message carries exactly that round's Tool calls, so even a call whose siblings
     * have not reached their lifecycle yet is projected with the complete list. When the view cannot
     * place the call, the calls known so far are grouped the same way the transcript projection does;
     * that fallback still emits a single message per round, never one message per call.
     */
    private List<ToolCallView> liveRound(
            HaifaRunBridge bridge, AgentRunId runId, List<ToolCallView> known, String toolCallId) {
        List<ToolCallView> fromView = roundFromMessageView(bridge, runId, toolCallId);
        if (!fromView.isEmpty()) {
            return DeerFlowToolTranscriptProjection.upToClarificationBoundary(fromView);
        }
        for (List<ToolCallView> round : toolCallRounds(bridge, runId, known)) {
            if (round.stream().anyMatch(call -> call.id().value().equals(toolCallId))) {
                return round;
            }
        }
        return known.stream()
                .filter(call -> call.id().value().equals(toolCallId))
                .findFirst()
                .map(List::of)
                .orElse(List.of());
    }

    private List<ToolCallView> roundFromMessageView(
            HaifaRunBridge bridge, AgentRunId runId, String toolCallId) {
        RunMessageCursor cursor = RunMessageCursor.beforeFirst(runId);
        int pages = 0;
        while (true) {
            RunMessagePage page = bridge.messages(runId, cursor, MESSAGE_ROUND_SCAN_PAGE_SIZE);
            for (RunMessageView message : page.items()) {
                if (message.toolCalls().stream().anyMatch(call -> call.id().value().equals(toolCallId))) {
                    return message.toolCalls();
                }
            }
            pages++;
            if (!page.hasMore()) return List.of();
            requireAnotherScanPage(pages);
            cursor = page.nextCursor();
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
            ToolCallView call = selected.orElseThrow();
            List<ToolCallView> round = liveRound(run.bridge, run.runId, calls, call.id().value());
            writeToolMessage(
                    output, run, DeerFlowToolTranscriptProjection.requestMessage(round), cursor);
        }
    }

    private String interactionTargetReference(RunRecord run) {
        return run.bridge.pendingInteraction(run.runId)
                .map(interaction -> interaction.target().reference())
                .orElse("");
    }

    private void writeAllToolMessages(OutputStream output, RunRecord run) throws IOException {
        List<ToolCallView> calls = DeerFlowToolTranscriptProjection.upToClarificationBoundary(
                run.bridge.toolCalls(run.runId));
        for (List<ToolCallView> round : toolCallRounds(run.bridge, run.runId, calls)) {
            if (round.isEmpty()) continue;
            writeToolMessage(output, run, DeerFlowToolTranscriptProjection.requestMessage(round), null);
            for (ToolCallView call : round) {
                Optional<Map<String, Object>> result = DeerFlowToolTranscriptProjection.resultMessage(call);
                if (result.isPresent()) {
                    writeToolMessage(output, run, result.orElseThrow(), null);
                }
            }
        }
    }

    private void writeToolMessage(
            OutputStream output, RunRecord run, Map<String, Object> message, String cursor) throws IOException {
        String messageId = (String) message.get("id");
        // A round request can grow as more calls of the same round become known, so the same message
        // id is written again only when its tool_calls list changes (the frozen SDK overwrites a
        // non-chunked AI message by id). Every other message is still written once.
        String signature = DeerFlowToolTranscriptProjection.messageSignature(message);
        String previous = run.projectedToolMessages.put(messageId, signature);
        if (signature.equals(previous)) return;
        writeSse(output, "messages", List.of(message, Map.of("langgraph_node", "tools")), cursor);
        if ("tool".equals(message.get("type")) && "write_todos".equals(message.get("name"))) {
            maybePublishTodos(output, run);
        }
    }

    private void maybePublishTodos(OutputStream output, RunRecord run) throws IOException {
        if (!run.streamModes.contains("updates")) {
            return;
        }
        Optional<AgentPlanView> plan = run.bridge.plan(run.runId);
        if (plan.isPresent()) {
            Optional<List<Map<String, Object>>> todos =
                    PlanAuthoringResources.projectTodos(plan.get(), run.threadId, run.runId.value());
            if (todos.isPresent()) {
                writeSse(output, "updates", Map.of("tools", Map.of("todos", todos.get())), null);
            }
        }
    }

    private Optional<List<Map<String, Object>>> resolveThreadTodos(ThreadRecord thread) {
        List<AgentRunId> candidateRunIds = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        runs.values().stream()
                .filter(run -> run.threadId.equals(thread.threadId))
                .sorted((left, right) -> right.createdAt.compareTo(left.createdAt))
                .forEach(run -> {
                    if (seen.add(run.runId.value())) {
                        candidateRunIds.add(run.runId);
                    }
                });
        if (thread.persistedSessionId != null) {
            HaifaRunBridge bridge = bridgeForPersistedThread(thread);
            for (ConversationTurn turn : newestRunTurns(thread, bridge)) {
                turn.runId().ifPresent(runId -> {
                    if (seen.add(runId.value())) {
                        candidateRunIds.add(runId);
                    }
                });
            }
        }
        if (candidateRunIds.isEmpty()) {
            return Optional.empty();
        }
        HaifaRunBridge fallbackBridge = thread.persistedSessionId != null ? bridgeForPersistedThread(thread) : null;
        for (AgentRunId runId : candidateRunIds) {
            RunRecord run = runs.get(runId.value());
            HaifaRunBridge bridge = (run != null && run.bridge != null) ? run.bridge : fallbackBridge;
            if (bridge != null) {
                Optional<AgentPlanView> plan = bridge.plan(runId);
                if (plan.isPresent()) {
                    return PlanAuthoringResources.projectTodos(plan.get(), thread.threadId, runId.value());
                }
            }
        }
        return Optional.empty();
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

    private void getSubtaskEvents(HttpExchange exchange, String threadId, String runId) throws IOException {
        String taskId;
        int limit;
        long after;
        try {
            taskId = queryValue(exchange, "task_id").filter(value -> !value.isBlank())
                    .orElseThrow(() -> new IllegalArgumentException("task_id is required"));
            String types = queryValue(exchange, "event_types").orElse("subagent.step");
            if (java.util.Arrays.stream(types.split(",", -1)).map(String::trim)
                    .anyMatch(value -> !"subagent.step".equals(value))) {
                throw new IllegalArgumentException("Only subagent.step event_types are supported");
            }
            limit = (int) subtaskQueryNumber(queryValue(exchange, "limit").orElse("500"), 1, 500);
            after = subtaskQueryNumber(queryValue(exchange, "after_seq").orElse("0"), 0,
                    DeerFlowSubtaskProjection.MAX_SAFE_INTEGER);
        } catch (IllegalArgumentException invalid) {
            sendJson(exchange, 422, Map.of("detail", invalid.getMessage()));
            return;
        }
        ThreadRecord thread = threads.get(threadId);
        RunRecord run = thread == null ? null : findOrRestoreRun(thread, runId).orElse(null);
        if (run == null) {
            sendJson(exchange, 404, Map.of("detail", "Run not found"));
            return;
        }
        // Resolve only through the caller-visible Parent's actual children and exact Runtime Tool ID.
        var children = run.bridge.children(run.runId);
        var taskIds = run.bridge.childTaskIds(run.runId, children);
        var child = children.stream()
                .filter(candidate -> candidate.parentRunId().equals(run.runId))
                .filter(candidate -> taskId.equals(taskIds.get(candidate.runId())))
                .findFirst();
        if (child.isEmpty()) {
            sendJson(exchange, 200, List.of());
            return;
        }
        var task = run.bridge.runtimeToolCall(run.runId, taskId)
                .filter(call -> call.runId().equals(run.runId) && "task".equals(call.toolName()))
                .orElseThrow(() -> new IllegalStateException("Child has no exact Parent task Tool"));
        var childId = child.orElseThrow().runId();
        var page = run.bridge.messages(childId, new RunMessageCursor(childId, after), limit);
        if (page.items().size() > limit || !page.nextCursor().runId().equals(childId)
                || !page.headCursor().runId().equals(childId)) {
            throw new IllegalStateException("Invalid Child message page scope");
        }
        List<Map<String, Object>> events = new ArrayList<>();
        long previous = after;
        for (var message : page.items()) {
            if (message.sequence() <= previous) throw new IllegalStateException("Child message page is not ordered");
            previous = message.sequence();
            try {
                events.add(DeerFlowSubtaskProjection.event(task.id().value(), message));
            } catch (IllegalArgumentException invalidSource) {
                throw new IllegalStateException("Child message sequence is outside the transport range");
            }
        }
        sendJson(exchange, 200, events);
    }

    static long subtaskQueryNumber(String raw, long minimum, long maximum) {
        if (!raw.matches("[0-9]+")) throw new IllegalArgumentException("Invalid subtask numeric parameter");
        try {
            long value = Long.parseLong(raw);
            if (value < minimum || value > maximum) throw new IllegalArgumentException("Subtask parameter is outside its range");
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Subtask parameter is outside its range");
        }
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
                .map(run -> runResponse(run, false))
                .toList();
        sendJson(exchange, 200, response);
    }

    /** The frozen UI treats a JSON null response as unavailable Usage, without displaying zero. */
    private void getThreadTokenUsage(HttpExchange exchange, String threadId) throws IOException {
        ThreadRecord thread = threads.get(threadId);
        if (thread == null) {
            sendJson(exchange, 404, Map.of("detail", "Thread not found"));
            return;
        }
        if (queryValue(exchange, "include_active").filter(value -> !Set.of("false", "0").contains(value)).isPresent()) {
            sendJson(exchange, 422, Map.of("detail", "Active Run Usage is outside this local profile"));
            return;
        }
        long input = 0;
        long output = 0;
        int totalRuns = 0;
        int remainingEventPages = THREAD_USAGE_EVENT_PAGE_BUDGET;
        Map<String, long[]> byModel = new LinkedHashMap<>();
        List<RunRecord> threadRuns = durableRuns(thread);
        if (threadRuns.size() >= RUN_LIST_LIMIT) {
            sendJson(exchange, 200, null); // A capped history cannot prove a complete thread total.
            return;
        }
        for (RunRecord run : threadRuns) {
            AgentRunSnapshot snapshot = run.bridge.snapshot(run.runId);
            if (!snapshot.status().isTerminal()) continue;
            if (remainingEventPages == 0) {
                sendJson(exchange, 200, null);
                return;
            }
            DiagnosticScan scan = runDiagnosticFacts(run, snapshot, remainingEventPages, false);
            remainingEventPages -= scan.pagesRead();
            RunDiagnosticProjection.Facts facts = scan.facts();
            if (!facts.usageKnown()) {
                sendJson(exchange, 200, null);
                return;
            }
            Map<String, long[]> runModels = new LinkedHashMap<>();
            facts.usageByModel().forEach((model, values) -> runModels.put(model, values.clone()));
            int[] childPageBudget = {remainingEventPages};
            Optional<long[]> childTotals = childUsage(run, runModels, childPageBudget);
            remainingEventPages = childPageBudget[0];
            if (childTotals.isEmpty()) {
                sendJson(exchange, 200, null);
                return;
            }
            var nextInput = sumUsageTokens(input, snapshot.usage().inputTokens(), childTotals.orElseThrow()[0]);
            var nextOutput = sumUsageTokens(output, snapshot.usage().outputTokens(), childTotals.orElseThrow()[1]);
            if (nextInput.isEmpty() || nextOutput.isEmpty()
                    || sumUsageTokens(nextInput.orElseThrow(), nextOutput.orElseThrow()).isEmpty()) {
                sendJson(exchange, 200, null);
                return;
            }
            input = nextInput.orElseThrow();
            output = nextOutput.orElseThrow();
            totalRuns++;
            runModels.forEach((modelId, values) -> {
                long[] aggregate = byModel.computeIfAbsent(modelId, ignored -> new long[2]);
                aggregate[0] = Math.addExact(aggregate[0], Math.addExact(values[0], values[1]));
                aggregate[1]++;
            });
        }
        if (totalRuns == 0) {
            sendJson(exchange, 200, null);
            return;
        }
        Map<String, Object> modelBreakdown = new LinkedHashMap<>();
        byModel.forEach((modelId, values) -> modelBreakdown.put(
                modelId, Map.of("tokens", values[0], "runs", values[1])));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("thread_id", threadId);
        response.put("total_input_tokens", input);
        response.put("total_output_tokens", output);
        response.put("total_tokens", Math.addExact(input, output));
        response.put("total_runs", totalRuns);
        response.put("by_model", modelBreakdown);
        response.put("by_caller", null); // The public Run Usage has no caller attribution.
        response.put("context_usage", null);
        sendJson(exchange, 200, response);
    }

    private DiagnosticScan runDiagnosticFacts(
            RunRecord run, AgentRunSnapshot snapshot, int pageBudget, boolean includeRelatedFacts) {
        List<AgentRunEvent> events = new ArrayList<>();
        RunEventCursor cursor = run.bridge.beforeFirstEvent(run.runId);
        for (int pageNumber = 0; pageNumber < pageBudget; pageNumber++) {
            var page = run.bridge.events(run.runId, cursor, DIAGNOSTIC_EVENT_PAGE_SIZE);
            events.addAll(page.items());
            if (!page.hasMore()) {
                return new DiagnosticScan(RunDiagnosticProjection.from(snapshot, events,
                        includeRelatedFacts ? run.bridge.toolCalls(run.runId) : List.of(),
                        includeRelatedFacts ? run.bridge.pendingInteraction(run.runId) : java.util.Optional.empty(),
                        true, null),
                        pageNumber + 1);
            }
            if (page.items().isEmpty() || page.nextCursor().equals(cursor)) {
                return new DiagnosticScan(RunDiagnosticProjection.from(snapshot, events,
                        includeRelatedFacts ? run.bridge.toolCalls(run.runId) : List.of(),
                        includeRelatedFacts ? run.bridge.pendingInteraction(run.runId) : java.util.Optional.empty(),
                        false,
                        "event_cursor_stalled"), pageNumber + 1);
            }
            cursor = page.nextCursor();
        }
        return new DiagnosticScan(RunDiagnosticProjection.from(snapshot, events,
                includeRelatedFacts ? run.bridge.toolCalls(run.runId) : List.of(),
                includeRelatedFacts ? run.bridge.pendingInteraction(run.runId) : java.util.Optional.empty(), false,
                "event_page_budget"), pageBudget);
    }

    private Map<String, Object> runResponse(RunRecord run) {
        return runResponse(run, true);
    }

    private Map<String, Object> runResponse(RunRecord run, boolean includeDiagnostics) {
        AgentRunSnapshot snapshot = run.bridge.snapshot(run.runId);
        RunDiagnosticProjection.Facts facts = includeDiagnostics
                ? runDiagnosticFacts(run, snapshot, RUN_DIAGNOSTIC_EVENT_PAGE_BUDGET, true).facts()
                : RunDiagnosticProjection.from(snapshot, List.of(), List.of(), java.util.Optional.empty(),
                        false, "run_list_summary");
        boolean usageKnown = facts.usageKnown();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("run_id", run.runId.value());
        response.put("thread_id", run.threadId);
        response.put("assistant_id", run.assistantId);
        response.put("status", runStatus(run, snapshot));
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
        java.util.OptionalLong parentTokens = usageKnown
                ? sumUsageTokens(snapshot.usage().inputTokens(), snapshot.usage().outputTokens())
                : java.util.OptionalLong.empty();
        usageKnown = usageKnown && parentTokens.isPresent();
        Optional<long[]> childUsage = includeDiagnostics ? childUsage(run) : Optional.empty();
        java.util.OptionalLong totalInput = usageKnown && childUsage.isPresent()
                ? sumUsageTokens(snapshot.usage().inputTokens(), childUsage.orElseThrow()[0]) : java.util.OptionalLong.empty();
        java.util.OptionalLong totalOutput = usageKnown && childUsage.isPresent()
                ? sumUsageTokens(snapshot.usage().outputTokens(), childUsage.orElseThrow()[1]) : java.util.OptionalLong.empty();
        java.util.OptionalLong total = totalInput.isPresent() && totalOutput.isPresent()
                ? sumUsageTokens(totalInput.orElseThrow(), totalOutput.orElseThrow()) : java.util.OptionalLong.empty();
        boolean totalKnown = total.isPresent();
        response.put("total_input_tokens", totalKnown ? totalInput.orElseThrow() : null);
        response.put("total_output_tokens", totalKnown ? totalOutput.orElseThrow() : null);
        response.put("total_tokens", totalKnown ? total.orElseThrow() : null);
        response.put("llm_call_count", usageKnown ? snapshot.usage().modelCalls() : null);
        if (usageKnown) response.put("lead_agent_tokens", parentTokens.orElseThrow());
        childUsage.ifPresent(usage -> sumUsageTokens(usage[0], usage[1])
                .ifPresent(tokens -> response.put("subagent_tokens", tokens)));
        response.put("middleware_tokens", null);
        response.put("message_count", null);
        response.put("stop_reason", facts.values().get("stop_reason"));
        response.put("diagnostics", facts.values());
        return response;
    }

    private Optional<long[]> childUsage(RunRecord parent) {
        return childUsage(parent, null, null);
    }

    private Optional<long[]> childUsage(RunRecord parent, Map<String, long[]> byModel, int[] pageBudget) {
        long input = 0, output = 0;
        for (var child : parent.bridge.children(parent.runId)) {
            AgentRunSnapshot snapshot = parent.bridge.snapshot(child.runId());
            List<AgentRunEvent> events = new ArrayList<>();
            RunEventCursor cursor = parent.bridge.beforeFirstEvent(child.runId());
            boolean complete = false;
            for (int pages = 0; pages < RUN_DIAGNOSTIC_EVENT_PAGE_BUDGET; pages++) {
                if (pageBudget != null && pageBudget[0]-- <= 0) return Optional.empty();
                var page = parent.bridge.events(child.runId(), cursor, 256);
                events.addAll(page.items());
                if (!page.hasMore()) { complete = true; break; }
                cursor = page.nextCursor();
            }
            RunDiagnosticProjection.Facts facts;
            try {
                facts = RunDiagnosticProjection.from(snapshot, events, List.of(), Optional.empty(), complete,
                        complete ? null : "child_usage_scan_limit");
            } catch (ArithmeticException invalidUsage) {
                return Optional.empty();
            }
            boolean knownZero = complete && snapshot.status().isTerminal() && snapshot.usage().modelCalls() == 0
                    && snapshot.usage().inputTokens() == 0 && snapshot.usage().outputTokens() == 0
                    && events.stream().noneMatch(event -> event.payload() instanceof RunEventPayloads.ModelLifecycle);
            if (!facts.usageKnown() && !knownZero) return Optional.empty();
            var nextInput = sumUsageTokens(input, snapshot.usage().inputTokens());
            var nextOutput = sumUsageTokens(output, snapshot.usage().outputTokens());
            if (nextInput.isEmpty() || nextOutput.isEmpty()
                    || sumUsageTokens(nextInput.orElseThrow(), nextOutput.orElseThrow()).isEmpty()) return Optional.empty();
            input = nextInput.orElseThrow();
            output = nextOutput.orElseThrow();
            if (byModel != null) {
                for (var entry : facts.usageByModel().entrySet()) {
                    long[] values = byModel.computeIfAbsent(entry.getKey(), ignored -> new long[2]);
                    var modelInput = sumUsageTokens(values[0], entry.getValue()[0]);
                    var modelOutput = sumUsageTokens(values[1], entry.getValue()[1]);
                    if (modelInput.isEmpty() || modelOutput.isEmpty()
                            || sumUsageTokens(modelInput.orElseThrow(), modelOutput.orElseThrow()).isEmpty()) {
                        return Optional.empty();
                    }
                    values[0] = modelInput.orElseThrow();
                    values[1] = modelOutput.orElseThrow();
                }
            }
        }
        return Optional.of(new long[] {input, output});
    }

    /** Browser-facing usage is unknown when any source or its sum is not exactly representable. */
    static java.util.OptionalLong sumUsageTokens(long... tokens) {
        long total = 0;
        try {
            for (long value : tokens) {
                if (value < 0 || value > 9_007_199_254_740_991L) return java.util.OptionalLong.empty();
                total = Math.addExact(total, value);
                if (total > 9_007_199_254_740_991L) return java.util.OptionalLong.empty();
            }
        } catch (ArithmeticException invalidUsage) {
            return java.util.OptionalLong.empty();
        }
        return java.util.OptionalLong.of(total);
    }

    private record DiagnosticScan(RunDiagnosticProjection.Facts facts, int pagesRead) {}

    private Map<String, Object> threadStateResponse(ThreadRecord thread) {
        List<ConversationTurn> turns = scanTurns(thread, 200);
        Map<Long, Integer> ordinals = computeTurnOrdinals(thread);
        Optional<List<Map<String, Object>>> todos = resolveThreadTodos(thread);
        ConversationTurn checkpoint = turns.stream()
                .filter(turn -> turn.role() == io.haifa.agent.core.message.MessageRole.ASSISTANT)
                .reduce((left, right) -> right)
                .orElse(null);
        Map<String, Object> response = checkpoint == null
                ? emptyThreadHistoryResponse(thread, turns, ordinals, todos)
                : threadHistoryResponse(thread, new HistoryProjection(
                        checkpoint,
                        List.copyOf(turns),
                        turns.stream()
                                .filter(turn -> turn.role()
                                        == io.haifa.agent.core.message.MessageRole.ASSISTANT)
                                .map(ConversationTurn::sequence)
                                .filter(sequence -> sequence < checkpoint.sequence())
                                .max(Long::compareTo)
                                .orElse(null)), ordinals, todos);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("messages", overlaidMessages(thread, turns, ordinals, true));
        values.put("artifacts", artifactPaths(thread, turns, true));
        if (thread.title() != null) {
            values.put("title", thread.title());
        }
        todos.ifPresent(items -> values.put("todos", items));
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

    /**
     * Whether the last submitted human message is hidden from the UI (a Human-Input reply or another
     * control turn). Such a Turn has no visible user bubble to echo, so the live projection must not
     * invent one.
     */
    private boolean hiddenHumanMessage(JsonNode messages) {
        if (!messages.isArray()) return false;
        for (int index = messages.size() - 1; index >= 0; index--) {
            JsonNode message = messages.get(index);
            String type = message.path("type").asText(message.path("role").asText());
            if (type.equals("human") || type.equals("user")) {
                return message.path("additional_kwargs").path("hide_from_ui").asBoolean(false);
            }
        }
        return false;
    }

    private Map<String, Object> emptyThreadHistoryResponse(
            ThreadRecord thread, List<ConversationTurn> turns, Map<Long, Integer> ordinals,
            Optional<List<Map<String, Object>>> todos) {
        Map<String, Object> response = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("messages", overlaidMessages(thread, turns, ordinals, true));
        values.put("artifacts", artifactPaths(thread, turns, true));
        todos.ifPresent(items -> values.put("todos", items));
        response.put("values", values);
        response.put("next", List.of());
        response.put("metadata", Map.of());
        response.put("checkpoint_id", null);
        response.put("parent_checkpoint_id", null);
        response.put("created_at", thread.updatedAt.toString());
        return response;
    }

    private Map<String, Object> threadHistoryResponse(
            ThreadRecord thread, HistoryProjection projection, Map<Long, Integer> ordinals,
            Optional<List<Map<String, Object>>> todos) {
        ConversationTurn checkpoint = projection.checkpoint();
        Map<String, Object> response = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("messages", overlaidMessages(thread, projection.messages(), ordinals, false));
        values.put("artifacts", artifactPaths(thread, projection.messages(), false));
        todos.ifPresent(items -> values.put("todos", items));
        response.put("values", values);
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
        HistoryAccumulator accumulator =
                new HistoryAccumulator(limit, exclusiveBeforeSequence, clarificationRunIds(thread));
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

    /**
     * Runs that ended at an {@code ask_clarification} boundary. Frozen DeerFlow checkpoints the human
     * input and the tool result, so a clarification Run has a durable head even without a later
     * assistant turn; the SDK's {@code history} read (and the frozen UI) needs that head to place the
     * card after the human instead of leaving the submitted human optimistic. The boundary is read
     * from the persisted card, with the durable ToolCall as a fallback for a persistence failure.
     */
    private Set<String> clarificationRunIds(ThreadRecord thread) {
        Set<String> runIds = new HashSet<>();
        synchronized (thread) {
            for (var display : thread.clarificationDisplays) {
                runIds.add(display.runId());
            }
        }
        for (RunRecord run : runs.values()) {
            if (run.threadId.equals(thread.threadId) && runHasClarificationBoundary(run)) {
                runIds.add(run.runId.value());
            }
        }
        return runIds;
    }

    private Map<String, Object> messageRow(ThreadRecord thread, ConversationTurn turn, int ordinal) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("run_id", turn.runId().map(AgentRunId::value).orElse(null));
        row.put("seq", turn.sequence());
        // The frozen UI reads an active Run's user turn from messages/page too; apply the same
        // in-flight client-id projection there. It is a no-op for every settled Run.
        row.put("content", messageForTurn(thread, turn, ordinal, true));
        row.put("metadata", Map.of("caller", "lead_agent"));
        row.put("created_at", turn.createdAt().toString());
        return row;
    }

    private Map<String, Object> messageRow(
            RunRecord run, long sequence, Instant createdAt, Map<String, Object> content) {
        return messageRow(run.runId.value(), sequence, createdAt, content);
    }

    private Map<String, Object> messageRow(
            String runId, long sequence, Instant createdAt, Map<String, Object> content) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("run_id", runId);
        row.put("seq", sequence);
        row.put("content", content);
        row.put("metadata", Map.of("caller", "lead_agent"));
        row.put("created_at", createdAt.toString());
        return row;
    }

    private Map<String, Object> messageForTurn(
            ThreadRecord thread, ConversationTurn turn, int ordinal, boolean liveOverlay) {
        boolean human = turn.role() == io.haifa.agent.core.message.MessageRole.USER;
        String type = human ? "human" : "ai";
        String id = turn.runId()
                .map(runId -> human
                        ? humanWireId(thread, runId, ordinal, liveOverlay)
                        : wireMessageId(runId.value(), type, ordinal))
                .orElseGet(turn::messageId);
        // The model-facing transcript persists the wrapped user text; the frozen UI must not show
        // the boundary markers, so the display projection strips them.
        Long framingFrom = human ? thread.framingFromSequence() : null;
        String text = human && framingFrom != null && turn.sequence() >= framingFrom
                ? InputTrustBoundary.unwrapForDisplay(turn.text()) : turn.text();
        Map<String, Object> projected = message(id, type, text);
        turn.runId().ifPresent(runId -> projected.put("run_id", runId.value()));
        applyClarificationAnswer(thread, turn, projected);
        return projected;
    }

    /**
     * Frozen DynamicContextMiddleware re-keys the submitted user message to {@code X__user}, and the
     * frozen UI folds that suffix onto the optimistic {@code local-human-*} bubble. The SDK commits
     * this Run's user turn before the model call, so it (not the transient overlay) is what the UI
     * sees while the Run is active; project it with the captured client id for the live overlay
     * only. Once the Run settles, the durable id stays synthetic, so the settled transcript and
     * replay are unchanged. The client id is kept in thread metadata while its Run is the newest
     * one, so a Run restored after a Gateway restart projects the same in-flight id.
     */
    private String humanWireId(ThreadRecord thread, AgentRunId runId, int ordinal, boolean liveOverlay) {
        if (liveOverlay) {
            RunRecord run = runs.get(runId.value());
            if (run != null && run.threadId.equals(thread.threadId)
                    && run.clientHumanMessageId != null && !run.clientHumanMessageId.isBlank()
                    && !run.bridge.snapshot(runId).status().isTerminal()) {
                return run.clientHumanMessageId + INJECTED_USER_MESSAGE_ID_SUFFIX;
            }
        }
        return wireMessageId(runId.value(), "human", ordinal);
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
            messages.add(messageForTurn(thread, turn, ordinal, includeInFlight));
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
        HaifaRunBridge bridge = cached == null ? bridgeForPersistedThread(thread) : cached.bridge;
        List<ToolCallView> calls = DeerFlowToolTranscriptProjection.upToClarificationBoundary(
                bridge.toolCalls(runId));
        List<List<ToolCallView>> rounds = toolCallRounds(bridge, runId, calls);
        Map<Integer, List<Map<String, Object>>> interactions =
                interactionMessagesByRound(thread, runId.value(), cached, rounds);
        // Frozen DeerFlow stores one model round at a time: one assistant message carrying that
        // round's whole Tool call list, then its Interaction request/response, then that round's Tool
        // results, before the next round. A single-round Run therefore projects byte-for-byte the same
        // sequence as before.
        for (int index = 0; index < rounds.size(); index++) {
            List<ToolCallView> round = rounds.get(index);
            if (!round.isEmpty()) {
                messages.add(DeerFlowToolTranscriptProjection.requestMessage(round));
            }
            messages.addAll(interactions.getOrDefault(index, List.of()));
            round.stream()
                    .map(DeerFlowToolTranscriptProjection::resultMessage)
                    .flatMap(Optional::stream)
                    .forEach(messages::add);
        }
    }

    /**
     * Groups the caller-visible Tool calls by the model round that produced them. The public SDK
     * exposes one assistant message per model round carrying exactly that round's Tool calls
     * ({@link RunMessageView#toolCalls()}); SH only re-groups the existing flat Tool view and never
     * changes which calls are projected. When the message view cannot place every call, the flat
     * order is retained as one round (the previous projection). An empty call list keeps one empty
     * round so an Interaction without a Tool call still projects exactly as before.
     */
    private List<List<ToolCallView>> toolCallRounds(
            HaifaRunBridge bridge, AgentRunId runId, List<ToolCallView> calls) {
        if (calls.isEmpty()) {
            return List.of(new ArrayList<>());
        }
        Map<String, Integer> roundByCall = new LinkedHashMap<>();
        RunMessageCursor cursor = RunMessageCursor.beforeFirst(runId);
        int pages = 0;
        int round = 0;
        while (true) {
            RunMessagePage page = bridge.messages(runId, cursor, MESSAGE_ROUND_SCAN_PAGE_SIZE);
            for (RunMessageView message : page.items()) {
                if (message.toolCalls().isEmpty()) continue;
                for (ToolCallView call : message.toolCalls()) {
                    roundByCall.putIfAbsent(call.id().value(), round);
                }
                round++;
            }
            pages++;
            if (!page.hasMore()) break;
            requireAnotherScanPage(pages);
            cursor = page.nextCursor();
        }
        for (ToolCallView call : calls) {
            if (!roundByCall.containsKey(call.id().value())) {
                return List.of(new ArrayList<>(calls));
            }
        }
        List<List<ToolCallView>> rounds = new ArrayList<>();
        List<ToolCallView> current = null;
        Integer currentRound = null;
        for (ToolCallView call : calls) {
            Integer nextRound = roundByCall.get(call.id().value());
            if (current == null || !nextRound.equals(currentRound)) {
                current = new ArrayList<>();
                rounds.add(current);
                currentRound = nextRound;
            }
            current.add(call);
        }
        return rounds;
    }

    /**
     * Places each saved Interaction card with the model round that owns its Tool call. A card whose
     * Tool call cannot be placed (or a Run with no Tool call at all) keeps the previous position in
     * the first round, so the existing single-round approval ordering is unchanged.
     */
    private Map<Integer, List<Map<String, Object>>> interactionMessagesByRound(
            ThreadRecord thread, String runId, RunRecord run, List<List<ToolCallView>> rounds) {
        Map<String, Integer> roundByCall = new HashMap<>();
        for (int index = 0; index < rounds.size(); index++) {
            for (ToolCallView call : rounds.get(index)) {
                roundByCall.putIfAbsent(call.id().value(), index);
            }
        }
        Map<Integer, List<Map<String, Object>>> byRound = new LinkedHashMap<>();
        synchronized (thread) {
            for (var display : thread.interactionDisplays) {
                if (!display.runId().equals(runId)) continue;
                if (!isVisibleInteractionDisplay(thread, display)) continue;
                int round = roundByCall.getOrDefault(display.toolCallId(), 0);
                List<Map<String, Object>> bucket =
                        byRound.computeIfAbsent(round, ignored -> new ArrayList<>());
                bucket.add(requestMessage(display));
                if (display.answeredOption() != null) bucket.add(responseMessage(display));
            }
        }
        if (run != null) {
            synchronized (run) {
                if (run.interactionRequestMessage != null
                        && thread.interactionDisplays.stream().noneMatch(display ->
                                display.runId().equals(runId)
                                        && display.requestId().equals(run.interactionRequestId))) {
                    int round = roundByCall.getOrDefault(
                            interactionMessageToolCallId(run.interactionRequestMessage), 0);
                    byRound.computeIfAbsent(round, ignored -> new ArrayList<>())
                            .add(run.interactionRequestMessage);
                }
            }
        }
        return byRound;
    }

    private static String interactionMessageToolCallId(Map<String, Object> message) {
        return message.get("tool_call_id") instanceof String value ? value : "";
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
            ThreadRecord thread, List<Map<String, Object>> response, int limit, Map<Long, Integer> ordinals,
            Optional<List<Map<String, Object>>> todos) {
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
            Map<String, Object> headValues = new LinkedHashMap<>();
            headValues.put("messages", overlaidMessages(thread, headTurns, ordinals, false));
            headValues.put("artifacts", artifactPaths(thread, headTurns, false));
            todos.ifPresent(items -> headValues.put("todos", items));
            response.getFirst().put("values", headValues);
            return;
        }
        RunRecord run = inFlight.orElseThrow();
        Map<String, Object> head = new LinkedHashMap<>();
        Map<String, Object> headValues = new LinkedHashMap<>();
        headValues.put("messages", messages);
        headValues.put("artifacts", artifactPaths(thread, turns, true));
        todos.ifPresent(items -> headValues.put("todos", items));
        head.put("values", headValues);
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
        String id = run.clientHumanMessageId == null || run.clientHumanMessageId.isBlank()
                ? wireMessageId(run.runId.value(), "human", 0)
                : run.clientHumanMessageId + INJECTED_USER_MESSAGE_ID_SUFFIX;
        Map<String, Object> message = message(id, "human", run.humanMessage);
        message.put("run_id", run.runId.value());
        return message;
    }

    private Map<String, Object> requestMessage(PersistentThreadCatalog.InteractionDisplay display) {
        return DeerFlowInteractionHttpProjection.requestMessage(
                display.runId(), display.requestId(), display.toolCallId(), display.title(),
                display.question(), display.context(), display.options());
    }

    /** Persist only the SDK's safe card presentation before exposing or answering this request. */
    private PersistentThreadCatalog.InteractionDisplay ensureSavedPendingDisplay(
            ThreadRecord thread, RunRecord run, InteractionView view) throws IOException {
        String requestId = view.requestId().value();
        var saved = thread.interactionDisplays.stream()
                .filter(candidate -> candidate.requestId().equals(requestId))
                .findFirst();
        if (saved.isPresent()) {
            var display = saved.orElseThrow();
            if (!display.runId().equals(run.runId.value()) || display.answeredOption() != null) {
                throw new IllegalArgumentException("Pending Interaction conflicts with saved presentation");
            }
            return display;
        }
        DeerFlowInteractionHttpProjection.requestMessage(view, run.runId.value());
        var display = new PersistentThreadCatalog.InteractionDisplay(
                run.runId.value(), requestId,
                Math.max(thread.nextMessageSequence, run.humanSequence + 1),
                view.createdAt(), view.target().reference(), view.title(),
                view.safePrompt(), view.target().safeSummary(),
                view.allowedActions().stream().map(action -> action.value()).toList(), null);
        List<PersistentThreadCatalog.InteractionDisplay> updated =
                new ArrayList<>(thread.interactionDisplays);
        updated.add(display);
        PersistentThreadCatalog.validateDisplays(updated);
        List<PersistentThreadCatalog.InteractionDisplay> previous = thread.interactionDisplays;
        thread.interactionDisplays = List.copyOf(updated);
        try {
            persistThread(thread);
        } catch (IOException | RuntimeException failedPersistence) {
            thread.interactionDisplays = previous;
            throw new IOException("Interaction card presentation could not be persisted", failedPersistence);
        }
        return display;
    }

    private Map<String, Object> responseMessage(PersistentThreadCatalog.InteractionDisplay display) {
        String option = display.answeredOption();
        if (option == null) throw new IllegalArgumentException("Interaction has no display response");
        var response = new DeerFlowInteractionHttpProjection.FrozenResponse(
                DeerFlowInteractionHttpProjection.SOURCE, display.requestId(), "option", option, option);
        return DeerFlowInteractionHttpProjection.responseMessage(response, "", display.runId());
    }

    private boolean isVisibleInteractionDisplay(
            ThreadRecord thread, PersistentThreadCatalog.InteractionDisplay display) {
        if (display.answeredOption() != null) return true;
        try {
            RunRecord cached = runs.get(display.runId());
            HaifaRunBridge bridge = cached != null && cached.threadId.equals(thread.threadId)
                    ? cached.bridge : bridgeForPersistedThread(thread);
            return bridge.pendingInteraction(new AgentRunId(display.runId()))
                    .filter(view -> view.requestId().value().equals(display.requestId()))
                    .isPresent();
        } catch (RuntimeException unavailable) {
            // A stale or unverifiable saved card must never look actionable after restart.
            return false;
        }
    }

    private List<ConversationTurn> scanTurns(ThreadRecord thread, int retention) {
        return scanTurns(thread, retention, null);
    }

    private List<ConversationTurn> scanTurns(
            ThreadRecord thread, int retention, Long exclusiveBeforeSequence) {
        if (thread.persistedSessionId == null) return List.of();
        HaifaRunBridge bridge = bridgeForPersistedThread(thread);
        Set<String> clarificationRuns = clarificationRunIds(thread);
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
                if (isPostClarificationAnswer(turn, clarificationRuns)) continue;
                retained.addLast(turn);
                while (retained.size() > retention) retained.removeFirst();
            }
            if (page.nextCursor().isEmpty()) break;
            requireAnotherScanPage(pages);
            cursor = page.nextCursor();
        }
        return List.copyOf(retained);
    }

    /**
     * A Run that asked for clarification stops at that boundary. Cancelling it is best-effort, so a
     * fast model can still finish and persist an answer before the cancel lands; that answer is past
     * the boundary and is never projected, exactly like the live stream already drops it.
     */
    private static boolean isPostClarificationAnswer(ConversationTurn turn, Set<String> clarificationRuns) {
        return turn.role() == io.haifa.agent.core.message.MessageRole.ASSISTANT
                && turn.runId()
                        .map(AgentRunId::value)
                        .filter(clarificationRuns::contains)
                        .isPresent();
    }

    private List<RunRecord> durableRuns(ThreadRecord thread) {
        // A newly created Thread has no SDK session or durable Runs. Do not construct a
        // recovery bridge merely to reject a response addressed to another Thread.
        if (thread.persistedSessionId == null) return List.of();
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
                thread.clientHumanMessageId(requested),
                false,
                bridge,
                turn.createdAt(),
                turn.sequence(),
                false,
                thread.runStreamModes(requested),
                false);
        bridge.pendingInteraction(requested).ifPresent(pending -> {
            try {
                var saved = thread.interactionDisplays.stream()
                        .filter(display -> display.runId().equals(requested.value())
                                && display.requestId().equals(pending.requestId().value())
                                && display.answeredOption() == null)
                        .findFirst();
                restored.interactionRequestMessage = saved.isPresent()
                        ? requestMessage(saved.orElseThrow())
                        : DeerFlowInteractionHttpProjection.requestMessage(pending, requested.value());
                restored.interactionRequestId = pending.requestId().value();
                restored.interactionRequestSequence = saved
                        .map(PersistentThreadCatalog.InteractionDisplay::requestSequence)
                        .orElse(restored.humanSequence + 1);
                restored.interactionResponseSequence = restored.interactionRequestSequence + 1;
                restored.assistantSequence = restored.interactionRequestSequence + 2;
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
        response.put("metadata", record.publicMetadata());
        Map<String, Object> values = new LinkedHashMap<>();
        Object title = record.metadata.get("title");
        if (title instanceof String) {
            values.put("title", title);
        }
        resolveThreadTodos(record).ifPresent(todos -> values.put("todos", todos));
        response.put("values", values);
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
                    response.put("metadata", record.publicMetadata());
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
        } catch (RuntimeException failure) {
            // Recording is best-effort; keep the Run outcome while exposing a bounded diagnostic.
            if (!closed) INTERACTION_LOG.log(System.Logger.Level.WARNING,
                    "Workspace completion failed for run " + run.runId.value()
                            + " (" + workspaceFailureDetail(failure) + ")");
            return false;
        }
    }

    /**
     * Bounded diagnostic detail for a workspace failure. Includes the exception family, the persisted
     * error code when available, and the underlying file-system reason (for example a Windows sharing
     * or access denial). It never includes file content.
     */
    private static String workspaceFailureDetail(RuntimeException failure) {
        StringBuilder detail = new StringBuilder(failure.getClass().getSimpleName());
        if (failure instanceof WorkspaceException workspaceFailure) {
            detail.append(':').append(workspaceFailure.error().name());
        }
        Throwable cause = failure.getCause();
        if (cause != null) {
            detail.append(':').append(cause.getClass().getSimpleName());
            if (cause instanceof java.nio.file.FileSystemException fileSystemFailure) {
                String reason = fileSystemFailure.getReason();
                if (reason != null && !reason.isBlank()) {
                    detail.append('[').append(reason).append(']');
                }
            }
        }
        return detail.toString();
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
                    } catch (RuntimeException failure) {
                        // A recorder or snapshot fault cannot mutate the Run result.
                        if (!closed) INTERACTION_LOG.log(System.Logger.Level.WARNING,
                                "Workspace observer stopped for run " + run.runId.value()
                                        + " (" + failure.getClass().getSimpleName() + ")");
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

    /**
     * Frozen ends a clarification Run cleanly (its middleware returns {@code Command(goto=END)}), so
     * SH's best-effort cancel of the model continuation must be reported as {@code success}, never as
     * a user cancellation. The durable ToolCall boundary makes this identical after a restart.
     */
    private String runStatus(RunRecord run, AgentRunSnapshot snapshot) {
        if (snapshot.status() == AgentRunStatus.CANCELLED && runHasClarificationBoundary(run)) {
            return "success";
        }
        return deerFlowStatus(snapshot.status());
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
        ownedBridges.forEach(HaifaRunBridge::close);
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
        /** Files referenced by the newest Run input; {@code list_uploaded_files} excludes them. */
        private final CurrentRunUploads currentRunUploads = new CurrentRunUploads();
        /** Safe frozen-card presentation only; the SDK owns every pending/accepted decision. */
        private volatile List<PersistentThreadCatalog.InteractionDisplay> interactionDisplays = List.of();
        /** Durable clarification card boundary and answered state for restart-safe projection. */
        private volatile List<PersistentThreadCatalog.ClarificationDisplay> clarificationDisplays = List.of();
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
            this.interactionDisplays = entry.interactionDisplays();
            this.clarificationDisplays = entry.clarificationDisplays();
        }

        private String title() {
            Object value = metadata.get("title");
            return value instanceof String text && !text.isBlank() ? text : null;
        }

        private Map<String, Object> publicMetadata() {
            Map<String, Object> result = new LinkedHashMap<>(metadata);
            result.remove(RUN_STREAM_MODES_METADATA_KEY);
            result.remove(RUN_CLIENT_HUMAN_ID_METADATA_KEY);
            return Collections.unmodifiableMap(result);
        }

        private String clientHumanMessageId(AgentRunId runId) {
            return metadata.get(RUN_CLIENT_HUMAN_ID_METADATA_KEY) instanceof Map<?, ?> stored
                    && stored.get(runId.value()) instanceof String id && !id.isBlank() ? id : null;
        }

        /** Replaces the previous Run's entry; a Run without a client id clears it. */
        private void setClientHumanMessageId(AgentRunId runId, String clientHumanMessageId) {
            Map<String, Object> updated = new LinkedHashMap<>(metadata);
            if (clientHumanMessageId == null || clientHumanMessageId.isBlank()) {
                updated.remove(RUN_CLIENT_HUMAN_ID_METADATA_KEY);
            } else {
                updated.put(RUN_CLIENT_HUMAN_ID_METADATA_KEY, Map.of(runId.value(), clientHumanMessageId));
            }
            metadata = Collections.unmodifiableMap(updated);
        }

        private Set<String> runStreamModes(AgentRunId runId) {
            Object stored = metadata.get(RUN_STREAM_MODES_METADATA_KEY);
            if (stored == null) return Set.of("messages-tuple"); // Legacy selection was not retained.
            if (!(stored instanceof Map<?, ?> modes)) throw new IllegalStateException("Persisted Run stream modes are invalid");
            Object selected = modes.get(runId.value());
            if (selected == null) return Set.of("messages-tuple");
            if (!(selected instanceof List<?> values)
                    || values.stream().anyMatch(mode -> !(mode instanceof String text) || !SUPPORTED_STREAM_MODES.contains(text))) {
                throw new IllegalStateException("Persisted Run stream modes are invalid");
            }
            return values.stream().map(String.class::cast).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        private void setRunStreamModes(AgentRunId runId, Set<String> selected) {
            Object stored = metadata.get(RUN_STREAM_MODES_METADATA_KEY);
            if (stored != null && !(stored instanceof Map<?, ?>)) throw new IllegalStateException("Persisted Run stream modes are invalid");
            Map<String, Object> modes = new LinkedHashMap<>();
            if (stored instanceof Map<?, ?> previous) {
                previous.forEach((id, value) -> {
                    if (!(id instanceof String text)) throw new IllegalStateException("Persisted Run stream modes are invalid");
                    modes.put(text, value);
                });
            }
            modes.put(runId.value(), selected.stream().sorted().toList());
            Map<String, Object> updated = new LinkedHashMap<>(metadata);
            updated.put(RUN_STREAM_MODES_METADATA_KEY, Collections.unmodifiableMap(modes));
            metadata = Collections.unmodifiableMap(updated);
        }

        /** Explicit user rename wins over any later auto-title because auto-title is skipped once set. */
        private void setTitle(String title) {
            Map<String, Object> updated = new LinkedHashMap<>(metadata);
            updated.put("title", title);
            this.metadata = Collections.unmodifiableMap(updated);
        }

        private SkillRunTools.Selection skillSelection() {
            Object value = metadata.get(SKILL_SELECTION_METADATA_KEY);
            if (value == null) return SkillRunTools.Selection.all(); // Legacy pre-M2 thread.
            if (!(value instanceof List<?> values)) {
                throw new IllegalStateException("persisted Skill prompt selection is invalid");
            }
            Set<String> names = new LinkedHashSet<>();
            for (Object item : values) {
                if (!(item instanceof String name) || name.isBlank()) {
                    throw new IllegalStateException("persisted Skill prompt selection is invalid");
                }
                names.add(name);
            }
            return SkillRunTools.Selection.of(names);
        }

        private Map<String, String> skillVersions() {
            Object value = metadata.get(SKILL_VERSIONS_METADATA_KEY);
            if (value == null) return Map.of();
            if (!(value instanceof Map<?, ?> versions)) throw new IllegalStateException("Persisted Skill versions are invalid");
            Map<String, String> result = new LinkedHashMap<>();
            versions.forEach((name, version) -> {
                if (!(name instanceof String text) || !(version instanceof String reference)) throw new IllegalStateException("Persisted Skill version is invalid");
                result.put(text, reference);
            });
            return Map.copyOf(result);
        }

        private Long framingFromSequence() {
            Object value = metadata.get(USER_INPUT_FRAMING_METADATA_KEY);
            if (value == null) return null;
            if (!(value instanceof Integer) && !(value instanceof Long)) {
                throw new IllegalStateException("persisted user input framing sequence is invalid");
            }
            long sequence = ((Number) value).longValue();
            if (sequence < 1) {
                throw new IllegalStateException("persisted user input framing sequence is invalid");
            }
            return sequence;
        }

        private void setFramingFromSequence(long sequence) {
            Map<String, Object> updated = new LinkedHashMap<>(metadata);
            updated.putIfAbsent(USER_INPUT_FRAMING_METADATA_KEY, sequence);
            this.metadata = Collections.unmodifiableMap(updated);
        }

        private void setLeadPromptSelection(SkillRunTools.Projection projection, String signature, AgentPromptContext agentPrompt) {
            Map<String, Object> updated = new LinkedHashMap<>(metadata);
            updated.put(SKILL_SELECTION_METADATA_KEY, projection == null ? List.of()
                    : projection.selectedPackages().stream().map(SkillRunTools.Package::name).toList());
            updated.put(SKILL_VERSIONS_METADATA_KEY, projection == null ? Map.of() : projection.selectedPackages().stream()
                    .collect(java.util.stream.Collectors.toMap(SkillRunTools.Package::name,
                            pkg -> pkg.source().descriptor().reference().sourceId() + "@" + pkg.version())));
            updated.put(LEAD_SIGNATURE_METADATA_KEY, signature);
            updated.put(AGENT_NAME_METADATA_KEY, agentPrompt.agentName() == null ? "" : agentPrompt.agentName());
            updated.put(AGENT_BOOTSTRAP_METADATA_KEY, agentPrompt.bootstrap());
            this.metadata = Collections.unmodifiableMap(updated);
        }

        /** The last Run's MCP selection, or {@code null} when no MCP Run was recorded. */
        private McpRunTools.Selection mcpSelection() {
            return parseMcpSelection(metadata.get(MCP_SELECTION_METADATA_KEY));
        }

        private void setMcpSelection(McpRunTools.Selection selection) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            if (selection.ids().isEmpty()) {
                encoded.put("mode", "all");
            } else if (selection.ids().orElseThrow().isEmpty()) {
                encoded.put("mode", "none");
            } else {
                encoded.put("mode", "list");
                encoded.put("ids", List.copyOf(selection.ids().orElseThrow()));
            }
            Map<String, Object> updated = new LinkedHashMap<>(metadata);
            updated.put(MCP_SELECTION_METADATA_KEY, encoded);
            this.metadata = Collections.unmodifiableMap(updated);
        }

        private void clearMcpSelection() {
            if (!metadata.containsKey(MCP_SELECTION_METADATA_KEY)) return;
            Map<String, Object> updated = new LinkedHashMap<>(metadata);
            updated.remove(MCP_SELECTION_METADATA_KEY);
            this.metadata = Collections.unmodifiableMap(updated);
        }

        private static McpRunTools.Selection parseMcpSelection(Object raw) {
            if (raw == null) return null;
            if (!(raw instanceof Map<?, ?> encoded)) {
                throw new IllegalStateException("persisted MCP selection is invalid");
            }
            Object mode = encoded.get("mode");
            if ("all".equals(mode)) return McpRunTools.Selection.all();
            if ("none".equals(mode)) return McpRunTools.Selection.none();
            if ("list".equals(mode) && encoded.get("ids") instanceof List<?> ids) {
                Set<String> serverIds = new LinkedHashSet<>();
                for (Object id : ids) {
                    if (!(id instanceof String text) || text.isBlank()) {
                        throw new IllegalStateException("persisted MCP selection is invalid");
                    }
                    serverIds.add(text);
                }
                return McpRunTools.Selection.of(serverIds);
            }
            throw new IllegalStateException("persisted MCP selection is invalid");
        }
    }

    private static final class RunRecord {
        private final AgentRunId runId;
        private final String threadId;
        private final String assistantId;
        private final String humanMessage;
        /**
         * The client-generated id of this Turn's submitted user message, captured at admission
         * only to key the in-flight projection: the frozen UI folds the server echo {@code X__user}
         * onto the optimistic {@code X}, so the pending human must reuse it while the Run is
         * active. It never becomes the settled message id or part of replay; thread metadata
         * keeps it for the newest Run only, so a restored active Run projects the same id.
         */
        private final String clientHumanMessageId;
        /**
         * True when this Turn has a visible user bubble. The live stream echoes that bubble once
         * before its steps so the frozen UI folds its optimistic copy ({@code X} vs the echo
         * {@code X__user}) and orders the Run's steps below it. A hidden Human-Input reply stays
         * false and is never projected as a user bubble.
         */
        private final boolean liveHumanEcho;
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
        private volatile boolean clarificationObserved;
        private io.haifa.agent.runtime.api.RunEventCursor sdkCursor;
        private final Set<Long> deferredEventSequences = ConcurrentHashMap.newKeySet();
        /** Attached streams: last delivered projection sequence and when the stream last looped. */
        private final Map<Object, long[]> liveObservers = new ConcurrentHashMap<>();
        private final Map<String, String> projectedToolMessages = new ConcurrentHashMap<>();

        private RunRecord(
                AgentRunId runId,
                String threadId,
                String assistantId,
                String humanMessage,
                String clientHumanMessageId,
                boolean liveHumanEcho,
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
            this.clientHumanMessageId = clientHumanMessageId;
            this.liveHumanEcho = liveHumanEcho;
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

        void observerDelivered(Object observer, long deliveredSequence) {
            liveObservers.put(observer, new long[] {deliveredSequence, System.nanoTime()});
        }

        void observerLeft(Object observer) {
            liveObservers.remove(observer);
        }

        java.util.OptionalLong slowestLiveObserver(long nowNanos) {
            return liveObservers.values().stream()
                    .filter(progress -> nowNanos - progress[1] < LIVE_OBSERVER_STALE_NANOS)
                    .mapToLong(progress -> progress[0])
                    .min();
        }
    }

    private record AcceptedInteractionResponse(
            DeerFlowInteractionHttpProjection.FrozenResponse response,
            InteractionResponseSubmission submission,
            Map<String, Object> message) {}

    record ThreadToolScope(String threadId, Optional<String> projectId, CurrentRunUploads currentRunUploads,
            AgentPromptContext agentPrompt) {
        ThreadToolScope(String threadId, Optional<String> projectId, CurrentRunUploads currentRunUploads) {
            this(threadId, projectId, currentRunUploads, AgentPromptContext.defaults());
        }
        ThreadToolScope {
            java.util.Objects.requireNonNull(threadId, "threadId");
            java.util.Objects.requireNonNull(projectId, "projectId");
            java.util.Objects.requireNonNull(currentRunUploads, "currentRunUploads");
            java.util.Objects.requireNonNull(agentPrompt, "agentPrompt");
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
        private final Set<String> clarificationRunIds;
        private final Deque<ConversationTurn> messages = new ArrayDeque<>();
        private final Deque<HistoryProjection> checkpoints = new ArrayDeque<>();
        private Long parentSequence;

        HistoryAccumulator(int limit, Long exclusiveBeforeSequence) {
            this(limit, exclusiveBeforeSequence, Set.of());
        }

        HistoryAccumulator(int limit, Long exclusiveBeforeSequence, Set<String> clarificationRunIds) {
            this.limit = limit;
            this.exclusiveBeforeSequence = exclusiveBeforeSequence;
            this.clarificationRunIds = clarificationRunIds;
        }

        void accept(ConversationTurn turn) {
            if (turn.role() != io.haifa.agent.core.message.MessageRole.USER
                    && turn.role() != io.haifa.agent.core.message.MessageRole.ASSISTANT) {
                return;
            }
            if (exclusiveBeforeSequence != null && turn.sequence() >= exclusiveBeforeSequence) return;
            if (isPostClarificationAnswer(turn, clarificationRunIds)) return;
            messages.addLast(turn);
            while (messages.size() > HISTORY_MESSAGE_RETENTION) messages.removeFirst();
            // Frozen checkpoints the human input before the agent runs, so a clarification Run has a
            // durable head even though it produced no assistant turn.
            boolean checkpoint = turn.role() == io.haifa.agent.core.message.MessageRole.ASSISTANT
                    || (turn.role() == io.haifa.agent.core.message.MessageRole.USER
                            && turn.runId()
                                    .map(AgentRunId::value)
                                    .filter(clarificationRunIds::contains)
                                    .isPresent());
            if (checkpoint) {
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

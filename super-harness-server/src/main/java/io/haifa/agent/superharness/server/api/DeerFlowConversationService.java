package io.haifa.agent.superharness.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.changeset.FileChange;
import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.changeset.FileVersion;
import io.haifa.agent.runtime.api.AgentPlanView;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.superharness.server.auth.AccessOwner;
import io.haifa.agent.superharness.server.auth.ThreadOwners;
import io.haifa.agent.superharness.server.plan.PlanAuthoringResources;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.AgentRunViewSnapshot;
import io.haifa.agent.runtime.api.FrozenInstructionDiagnostic;
import io.haifa.agent.runtime.api.InteractionResponseReceiptStatus;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionResponseReceipt;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPage;
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
import io.haifa.agent.superharness.server.config.ApprovalPolicyConfig;
import io.haifa.agent.superharness.server.config.McpRunTools;
import io.haifa.agent.superharness.server.config.HostShellToolConfig;
import io.haifa.agent.superharness.server.config.SkillScriptToolConfig;
import io.haifa.agent.superharness.server.config.SkillRunTools;
import io.haifa.agent.superharness.server.prompt.AgentPromptContext;
import io.haifa.agent.superharness.server.prompt.InputTrustBoundary;
import io.haifa.agent.superharness.server.run.DurableHaifaFoundation;
import io.haifa.agent.superharness.server.run.HaifaRunBridge;
import io.haifa.agent.superharness.server.run.PersistentThreadCatalog;
import io.haifa.agent.superharness.server.schedule.ScheduledRunLauncher;
import io.haifa.agent.superharness.server.schedule.ScheduledRunStatus;
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
import java.util.concurrent.ExecutorService;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;

/** Frozen DeerFlow thread/run compatibility routes backed by the public Haifa Agent SDK. */
public class DeerFlowConversationService implements AutoCloseable, ScheduledRunLauncher {
    // Product display cache only. SDK facts remain authoritative; no message body is retained.
    private final Map<ThreadRecord, FeedPrefix> feedPrefixes = new LinkedHashMap<>(8, 0.75f, true);
    private final java.util.concurrent.atomic.AtomicLong positionMessagePageReads = new java.util.concurrent.atomic.AtomicLong();

    long positionMessagePageReads() { return positionMessagePageReads.get(); }

    private static final System.Logger INTERACTION_LOG =
            System.getLogger(DeerFlowConversationService.class.getName() + ".interaction");
    /** Frozen route an admission failure belongs to; a server constant, never the client path. */
    static final String RUN_STREAM_ROUTE = "POST /api/threads/{thread_id}/runs/stream";
    /** Bounded cause-chain depth of one admission diagnostic line. */
    private static final int ADMISSION_CAUSE_LIMIT = 3;
    /** Bounded server-side record of an admission failure the frozen response cannot carry. */
    private static final System.Logger ADMISSION_LOG =
            System.getLogger(DeerFlowConversationService.class.getName() + ".admission");
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
    /**
     * How long a bulk synchronization waits for attached streams to catch up before it stops
     * pulling further pages. It must not outrun live observers after the deadline: doing so
     * slides the shared retention window past their delivered cursor and ends those streams with
     * {@code stream_replay_gap} while the Run is still active (W08 Steer/join under load).
     */
    private static final long LIVE_OBSERVER_CATCH_UP_NANOS = Duration.ofSeconds(2).toNanos();
    private static final int DIAGNOSTIC_EVENT_PAGE_SIZE = 100;
    private static final int RUN_DIAGNOSTIC_EVENT_PAGE_BUDGET = 20;
    private static final int THREAD_USAGE_EVENT_PAGE_BUDGET = 50;
    static final Duration WORKSPACE_COMPLETION_OBSERVER_MAX_WAIT = Duration.ofHours(24);
    static final Duration WORKSPACE_COMPLETION_OBSERVER_INITIAL_POLL = Duration.ofMillis(25);
    static final Duration WORKSPACE_COMPLETION_OBSERVER_MAX_POLL = Duration.ofSeconds(1);
    static final Duration TITLE_DURABILITY_GRACE = Duration.ofSeconds(2);
    /** Bounded observation window for a scheduled Run with no SSE client. */
    static final Duration BACKGROUND_FOLLOWER_MAX_WAIT = WORKSPACE_COMPLETION_OBSERVER_MAX_WAIT;
    static final Duration BACKGROUND_FOLLOWER_INITIAL_POLL = WORKSPACE_COMPLETION_OBSERVER_INITIAL_POLL;
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
    private static final int RUN_USAGE_CACHE_LIMIT = 512;
    /**
     * Upper bound of Turns materialized by a whole-thread state read. Channel adapters that only
     * need the newest assistant text and pending card ask for a smaller tail instead
     * ({@link #readThreadStateTail(String, int)}); no caller should pull the whole transcript.
     */
    static final int THREAD_STATE_MAX_TURNS = 200;
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
    /**
     * Thread-scoped goal objective for DeerFlow {@code /goal} and the future HTTP goal surface.
     * Product compatibility state only — not a second Memory/Run engine. Server-owned; stripped from
     * client-supplied create metadata.
     */
    static final String THREAD_GOAL_METADATA_KEY = "haifa_thread_goal";
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

    final ObjectMapper json;
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
    /**
     * Single-flight claim of an in-flight Interaction response submission, keyed by Run and request. Two channels (Feishu and the
     * Web/API) can submit the same card within the same second; without this claim both submissions
     * could enter {@code bridge.respond} and race the SDK's resume transaction, which is how Run
     * 01a14594 was left with a recorded response but no progress (W04-BUG-011).
     */
    private final java.util.concurrent.ConcurrentHashMap<String, DeerFlowInteractionHttpProjection.FrozenResponse>
            claimedInteractionResponses = new java.util.concurrent.ConcurrentHashMap<>();
    private final DeerFlowUploadContext uploadContext;
    private final PersistentThreadCatalog threadCatalog;
    private final DurableHaifaFoundation foundation;
    private final Supplier<? extends ExecutorService> executionExecutorFactory;
    private final Long parentWallTimeMillisForTests;
    private final Map<String, ThreadRecord> threads = new ConcurrentHashMap<>();
    private final Map<String, RunRecord> runs = new ConcurrentHashMap<>();
    private final Map<String, RunUsageFacts> runUsageFacts = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, RunUsageFacts> eldest) {
                    return size() > RUN_USAGE_CACHE_LIMIT;
                }
            });
    // A terminal Run can leave the hot cache while its bridge still owns SDK resources.
    // Keep process-lifetime ownership separate from the bounded Run projection cache.
    private final Set<HaifaRunBridge> ownedBridges = ConcurrentHashMap.newKeySet();
    // Test/observability seam: how many bridges this process built (each MCP projection
    // rebuild and restart-recovery bridge counts once).
    private final java.util.concurrent.atomic.AtomicLong bridgeBuilds =
            new java.util.concurrent.atomic.AtomicLong();
    private final Set<Thread> workspaceCompletionObservers = ConcurrentHashMap.newKeySet();
    private final Set<Thread> titleCompletionObservers = ConcurrentHashMap.newKeySet();
    // Scheduled-Run bridges retained for the bounded follower; the bridge itself keeps the Run alive.
    private final Map<String, HaifaRunBridge> backgroundBridges = new ConcurrentHashMap<>();
    private final Set<Thread> backgroundFollowers = ConcurrentHashMap.newKeySet();
    // Runs this process already follows. Reattaching is idempotent so recovery can call watch for
    // every active Run each round without starting duplicate followers.
    private final Set<String> followedRuns = ConcurrentHashMap.newKeySet();
    private volatile DurableTurnScanner durableTurnScanner = this::scanPersistedUserTurns;
    private volatile boolean closed;

    DeerFlowConversationService(
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
    DeerFlowConversationService(
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

    DeerFlowConversationService(
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

    DeerFlowConversationService(ObjectMapper json,
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

    DeerFlowConversationService(ObjectMapper json,
            Function<JsonNode, HaifaRunBridge.ModelBinding> runModelResolver,
            Predicate<String> projectExists, PersistentProjectWorkspaceService workspaceChanges,
            Path dataDirectory, SecretKey continuationKey,
            Function<ThreadToolScope, List<? extends JavaTool<?, ?>>> runToolsForThread,
            McpProjectionProvider mcpProjections, SkillProjectionProvider skillProjections,
            DeerFlowUploadContext uploadContext, Function<JsonNode, AgentPromptContext> agentPromptResolver,
            BiFunction<HaifaRunBridge, AgentRunId, Optional<FrozenInstructionDiagnostic>> instructionDiagnostics,
            DelegationProjectionProvider delegationProjections,
            io.haifa.agent.runtime.api.ChildRunCapacity childRunCapacity) throws IOException {
        this(json, runModelResolver, projectExists, workspaceChanges, dataDirectory, continuationKey,
                runToolsForThread, mcpProjections, skillProjections, uploadContext, agentPromptResolver,
                instructionDiagnostics, delegationProjections, childRunCapacity, null, null);
    }

    /** Test-only host physical-execution seam; production always supplies no executor factory. */
    private final Path dataDirectoryRoot;

    DeerFlowConversationService(ObjectMapper json,
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
        this.executionExecutorFactory = executionExecutorFactory;
        this.parentWallTimeMillisForTests = parentWallTimeMillisForTests;
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
        this.dataDirectoryRoot = dataDirectory.toAbsolutePath().normalize();
        this.foundation = new DurableHaifaFoundation(dataDirectory, continuationKey);
        for (PersistentThreadCatalog.Entry entry : threadCatalog.list()) {
            ThreadOwners.remember(entry.threadId(), entry.ownerId());
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

    /**
     * Structured frozen-projection frames plus a heartbeat signal. The transport adapter owns the
     * byte-level encoding (SSE for the HTTP compatibility route, no-op for the Feishu channel);
     * this seam is what lets Web and Feishu share one product use-case implementation.
     */
    public interface RunEventSink {
        void event(String event, Object data, String id) throws IOException;

        void heartbeat() throws IOException;

        RunEventSink NOOP = new RunEventSink() {
            @Override
            public void event(String event, Object data, String id) {}

            @Override
            public void heartbeat() {}
        };
    }

    /**
     * Immutable description of one admitted Turn that an adapter can observe. It carries only
     * product facts; the caller supplies the transport sink and, for HTTP, the response metadata.
     */
    public static final class AdmittedTurn {
        final ThreadRecord thread;
        final RunRecord run;
        final ProjectionCursor cursor;
        final boolean creator;
        final String onDisconnect;
        final boolean includeMetadata;
        final boolean reemitPendingCard;
        final boolean interactionPresentationDegraded;

        AdmittedTurn(
                ThreadRecord thread,
                RunRecord run,
                ProjectionCursor cursor,
                boolean creator,
                String onDisconnect,
                boolean includeMetadata,
                boolean reemitPendingCard,
                boolean interactionPresentationDegraded) {
            this.thread = thread;
            this.run = run;
            this.cursor = cursor;
            this.creator = creator;
            this.onDisconnect = onDisconnect;
            this.includeMetadata = includeMetadata;
            this.reemitPendingCard = reemitPendingCard;
            this.interactionPresentationDegraded = interactionPresentationDegraded;
        }

        public String threadId() {
            return thread.threadId;
        }

        public String runId() {
            return run.runId.value();
        }

        public boolean interactionPresentationDegraded() {
            return interactionPresentationDegraded;
        }
    }

    /**
     * Bounded product rejection. The transport adapter maps {@link Kind} to its own status code and
     * error shape; the shared use cases never speak HTTP.
     */
    public static final class ConversationException extends RuntimeException {
        public enum Kind {
            NOT_FOUND(404),
            FORBIDDEN(403),
            CONFLICT(409),
            INVALID(422),
            UNAVAILABLE(503),
            INTERNAL(500);

            private final int status;

            Kind(int status) {
                this.status = status;
            }

            public int status() {
                return status;
            }
        }

        private final Kind kind;
        private final String detail;

        public ConversationException(Kind kind, String detail) {
            super(detail);
            this.kind = kind;
            this.detail = detail;
        }

        public ConversationException(Kind kind, String detail, Throwable cause) {
            super(detail, cause);
            this.kind = kind;
            this.detail = detail;
        }

        public Kind kind() {
            return kind;
        }

        public String detail() {
            return detail;
        }

        public static ConversationException forStatus(int status, String detail) {
            Kind kind = switch (status) {
                case 403 -> Kind.FORBIDDEN;
                case 404 -> Kind.NOT_FOUND;
                case 409 -> Kind.CONFLICT;
                case 422 -> Kind.INVALID;
                case 503 -> Kind.UNAVAILABLE;
                default -> Kind.INTERNAL;
            };
            return new ConversationException(kind, detail);
        }

        static ConversationException notFound(String detail) {
            return new ConversationException(Kind.NOT_FOUND, detail);
        }

        static ConversationException conflict(String detail) {
            return new ConversationException(Kind.CONFLICT, detail);
        }

        static ConversationException invalid(String detail) {
            return new ConversationException(Kind.INVALID, detail);
        }

        static ConversationException unavailable(String detail) {
            return new ConversationException(Kind.UNAVAILABLE, detail);
        }

        static ConversationException internal(String detail) {
            return new ConversationException(Kind.INTERNAL, detail);
        }
    }

    /** Query parsing without an HTTP exchange; adapters pass the raw query string. */
    static Optional<String> queryValue(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Optional.empty();
        }
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(name)) {
                return Optional.of(parts.length == 2 ? parts[1] : "");
            }
        }
        return Optional.empty();
    }

    static Optional<Integer> positiveQueryInteger(String rawQuery, String name, int defaultValue, int maximum) {
        Optional<String> raw = queryValue(rawQuery, name);
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

    static boolean queryBoolean(String rawQuery, String name, boolean fallback) {
        Optional<String> value = queryValue(rawQuery, name);
        if (value.isEmpty()) return fallback;
        if (value.orElseThrow().equals("true")) return true;
        if (value.orElseThrow().equals("false")) return false;
        throw ConversationException.invalid(name + " must be true or false");
    }

    boolean hasThread(String threadId) {
        return threads.containsKey(threadId);
    }

    /** Owner-aware visibility used by the HTTP adapter before dispatching a thread-scoped route. */
    boolean threadVisibleToCurrentOwner(String threadId) {
        ThreadRecord record = threads.get(threadId);
        return record == null || AccessOwner.current().equals(record.ownerId);
    }

    /** Package-private seam for rebuild assertions in the V2 MCP integration tests. */
    public long bridgeBuildCount() {
        return bridgeBuilds.get();
    }


    public Map<String, Object> createThread(JsonNode body) throws IOException {
        String threadId = text(body, "thread_id").orElseGet(() -> java.util.UUID.randomUUID().toString());
        if (threadId.isBlank() || threadId.contains("/") || threadId.equals("new")) {
            throw ConversationException.invalid("Invalid thread_id");
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
        metadata.remove(THREAD_GOAL_METADATA_KEY);
        metadata.remove(USER_INPUT_FRAMING_METADATA_KEY);
        if (body.has("project_id") && !body.get("project_id").isNull()) {
            if (!body.get("project_id").isTextual() || body.get("project_id").asText().isBlank()) {
                throw ConversationException.invalid("Invalid project_id");
            }
            String projectId = body.get("project_id").asText();
            if (!projectExists.test(projectId)) {
                throw ConversationException.notFound("Project not found");
            }
            metadata.put("deerflow_project_id", projectId);
        }
        String owner = AccessOwner.current();
        ThreadRecord existing = threads.get(threadId);
        if (existing != null && !owner.equals(existing.ownerId)) {
            throw ConversationException.notFound("Thread not found");
        }
        ThreadRecord record = threads.computeIfAbsent(
                threadId, ignored -> new ThreadRecord(threadId, metadata, Instant.now(), owner));
        persistThread(record);
        return threadResponse(record);
    }

    /**
     * Pin a Custom Agent (or clear to the default lead) on an existing thread for IM {@code /agent
     * use}. Empty {@code agentName} clears the custom pin.
     */
    public void pinAgentSelection(String threadId, String agentName, boolean bootstrap)
            throws ConversationException, IOException {
        ThreadRecord thread = requireOwnedThread(threadId);
        String normalized = agentName == null ? "" : agentName.trim().toLowerCase(java.util.Locale.ROOT);
        synchronized (thread) {
            Map<String, Object> updated = new LinkedHashMap<>(thread.metadata);
            updated.put(AGENT_NAME_METADATA_KEY, normalized);
            updated.put(AGENT_BOOTSTRAP_METADATA_KEY, bootstrap);
            thread.metadata = Collections.unmodifiableMap(updated);
            thread.updatedAt = Instant.now();
            persistThread(thread);
        }
    }

    /**
     * Custom Agent pin written by {@link #pinAgentSelection} or the last Run's lead-prompt
     * selection. Empty when the thread uses the default lead agent.
     */
    public Optional<String> pinnedAgentName(String threadId) throws ConversationException {
        ThreadRecord thread = requireOwnedThread(threadId);
        synchronized (thread) {
            Object value = thread.metadata.get(AGENT_NAME_METADATA_KEY);
            if (!(value instanceof String name) || name.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(name);
        }
    }

    /** Read the thread-scoped goal objective, if any. */
    public Optional<String> threadGoal(String threadId) throws ConversationException {
        ThreadRecord thread = requireOwnedThread(threadId);
        synchronized (thread) {
            Object value = thread.metadata.get(THREAD_GOAL_METADATA_KEY);
            if (!(value instanceof String objective) || objective.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objective);
        }
    }

    /** Persist a thread-scoped goal objective (DeerFlow {@code /goal <condition>} surface). */
    public void setThreadGoal(String threadId, String objective) throws ConversationException, IOException {
        if (objective == null || objective.isBlank()) {
            throw ConversationException.invalid("Goal objective must not be empty");
        }
        ThreadRecord thread = requireOwnedThread(threadId);
        synchronized (thread) {
            Map<String, Object> updated = new LinkedHashMap<>(thread.metadata);
            updated.put(THREAD_GOAL_METADATA_KEY, objective);
            thread.metadata = Collections.unmodifiableMap(updated);
            thread.updatedAt = Instant.now();
            persistThread(thread);
        }
    }

    /** Clear the thread-scoped goal (DeerFlow {@code /goal clear}). */
    public void clearThreadGoal(String threadId) throws ConversationException, IOException {
        ThreadRecord thread = requireOwnedThread(threadId);
        synchronized (thread) {
            if (!thread.metadata.containsKey(THREAD_GOAL_METADATA_KEY)) {
                return;
            }
            Map<String, Object> updated = new LinkedHashMap<>(thread.metadata);
            updated.remove(THREAD_GOAL_METADATA_KEY);
            thread.metadata = Collections.unmodifiableMap(updated);
            thread.updatedAt = Instant.now();
            persistThread(thread);
        }
    }

    private ThreadRecord requireOwnedThread(String threadId) throws ConversationException {
        ThreadRecord thread = threads.get(threadId);
        if (thread == null || !AccessOwner.current().equals(thread.ownerId)) {
            throw ConversationException.notFound("Thread not found");
        }
        return thread;
    }

    Map<String, Object> getThread(String threadId) throws ConversationException {
        ThreadRecord record = threads.get(threadId);
        if (record == null) {
            throw ConversationException.notFound("Thread not found");
        }
        return threadResponse(record);
    }

    /**
     * Product-level thread delete for the frozen {@code DELETE /api/threads/{id}} and
     * {@code DELETE /api/langgraph/threads/{id}} routes. It retires the thread catalog entry so the
     * thread disappears from {@code /api/threads/search} and every owner-scoped read returns 404,
     * which is the contract the frozen frontend relies on. The SDK owns the session/checkpoint/event
     * rows and exposes no delete for them yet, so those durable rows are intentionally retained.
     */
    public Map<String, Object> deleteThread(String threadId) throws ConversationException {
        ThreadRecord thread = threads.get(threadId);
        if (thread == null || !AccessOwner.current().equals(thread.ownerId)) {
            throw ConversationException.notFound("Thread not found");
        }
        // Serialize with every other thread mutation (state writes, admission, terminal stream
        // cleanup, title finalizer): they all synchronize on this record, so after this block no
        // stale mutation can re-add the retired catalog entry.
        synchronized (thread) {
            if (activeRunId(threadId).isPresent()) {
                throw ConversationException.conflict(
                        "Thread has work in flight. Delete it after the work finishes.");
            }
            threads.remove(threadId);
            ThreadOwners.forget(threadId);
            try {
                threadCatalog.remove(threadId);
            } catch (IOException failure) {
                threads.put(threadId, thread);
                ThreadOwners.remember(threadId, thread.ownerId);
                throw new IllegalStateException("Thread catalog delete failed", failure);
            }
        }
        return Map.of("success", true, "message", "Deleted local thread data for " + threadId);
    }

    /**
     * Bounded wait for this Thread's active Run to reach a terminal status, keeping its projection
     * caught up while waiting. A channel adapter whose live observation ended early (replay gap,
     * transport hiccup) needs the finished Run before it projects the durable answer; otherwise it
     * replies with an intermediate step instead of the final message (W20-BUG-001 review).
     *
     * @return true when the Run is terminal, the Thread has no Run, or the deadline already passed
     */
    public boolean awaitRunTerminal(String threadId, java.time.Duration timeout) {
        ThreadRecord thread = threads.get(threadId);
        if (thread == null || !AccessOwner.current().equals(thread.ownerId)) {
            return false;
        }
        RunRecord run;
        try {
            synchronized (thread) {
                restoreDurableAdmission(thread);
                String runId = thread.activeRunId != null ? thread.activeRunId : thread.latestRunId;
                if (runId == null) {
                    return true;
                }
                run = runs.get(runId);
                if (run == null) {
                    run = findOrRestoreRun(thread, runId).orElse(null);
                }
            }
        } catch (RuntimeException unavailable) {
            return false;
        }
        if (run == null) {
            return true;
        }
        long deadline = System.nanoTime() + Math.max(0L, timeout.toNanos());
        while (true) {
            boolean terminal = run.bridge.snapshot(run.runId).status().isTerminal();
            try {
                // Pulling keeps the durable transcript current, so the caller reads the final step
                // instead of whatever the gap left behind.
                synchronizeAllAvailable(run);
            } catch (RuntimeException projection) {
                if (terminal) {
                    return true;
                }
            }
            if (terminal) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Owner-isolated frozen thread-state read shared by the Web route and the Feishu bridge. */
    public JsonNode readThreadState(String threadId) throws ConversationException {
        ThreadRecord thread = threads.get(threadId);
        if (thread == null || !AccessOwner.current().equals(thread.ownerId)) {
            throw ConversationException.notFound("Thread not found");
        }
        return json.valueToTree(threadStateResponse(thread, THREAD_STATE_MAX_TURNS));
    }

    /**
     * Bounded tail of the same projection. Channel adapters (Feishu) only need the latest
     * assistant message and the newest Interaction card, so they must not materialize a long
     * transcript on every Turn.
     */
    public JsonNode readThreadStateTail(String threadId, int maxTurns) throws ConversationException {
        ThreadRecord thread = threads.get(threadId);
        if (thread == null || !AccessOwner.current().equals(thread.ownerId)) {
            throw ConversationException.notFound("Thread not found");
        }
        int bounded = Math.max(1, Math.min(THREAD_STATE_MAX_TURNS, maxTurns));
        return json.valueToTree(threadStateResponse(thread, bounded));
    }

    /**
     * Owner-isolated authoritative card of the active Run's pending Interaction, if any.
     *
     * <p>The projected transcript orders cards by Tool-call round, so an unanswered card is not
     * necessarily its last message; channel adapters that infer the pending card from message order
     * therefore miss it and treat the operator's answer as a brand-new turn (W04-BUG-010). Asking the
     * SDK pending view keeps that decision with the Run that owns it.
     */
    public Optional<JsonNode> pendingInteractionMessage(String threadId) throws ConversationException {
        ThreadRecord thread = threads.get(threadId);
        if (thread == null || !AccessOwner.current().equals(thread.ownerId)) {
            return Optional.empty();
        }
        RunRecord run;
        try {
            synchronized (thread) {
                restoreDurableAdmission(thread);
                String activeRunId = thread.activeRunId;
                if (activeRunId == null) {
                    return Optional.empty();
                }
                run = runs.get(activeRunId);
                if (run == null) {
                    run = findOrRestoreRun(thread, activeRunId).orElse(null);
                }
            }
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
        if (run == null || !run.threadId.equals(thread.threadId)) {
            return Optional.empty();
        }
        if (run.bridge.snapshot(run.runId).status().isTerminal()) {
            return Optional.empty();
        }
        Optional<InteractionView> pending = run.bridge.pendingInteraction(run.runId);
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(json.valueToTree(
                DeerFlowInteractionHttpProjection.requestMessage(pending.orElseThrow(),
                        run.runId.value())));
    }

    /**
     * Frozen thread state write used by the rename dialog (`threads.updateState`). Only the
     * {@code values.title} projection is durable here; unknown state fields fail closed and a Run
     * in flight rejects the write exactly like the frozen router.
     */
    Map<String, Object> updateThreadState(String threadId, JsonNode body) throws IOException {
        ThreadRecord thread = threads.get(threadId);
        if (thread == null) {
            throw ConversationException.notFound("Thread not found");
        }
        for (java.util.Iterator<String> fields = body.fieldNames(); fields.hasNext(); ) {
            String field = fields.next();
            if (!Set.of("values", "checkpoint", "checkpoint_id", "as_node").contains(field)) {
                throw ConversationException.invalid("Unsupported state field '" + field + "'");
            }
        }
        JsonNode values = body.get("values");
        if (values == null || values.isNull()) {
            throw ConversationException.invalid("values is required");
        }
        if (!values.isObject()) {
            throw ConversationException.invalid("values must be an object");
        }
        for (java.util.Iterator<String> fields = values.fieldNames(); fields.hasNext(); ) {
            String field = fields.next();
            if (!field.equals("title")) {
                throw ConversationException.invalid("Unsupported thread state value '" + field + "'");
            }
        }
        JsonNode title = values.get("title");
        String renamed = null;
        if (title != null && !title.isNull()) {
            if (!title.isTextual()) {
                throw ConversationException.invalid("values.title must be a string");
            }
            renamed = title.asText().strip();
            if (renamed.isEmpty()) {
                throw ConversationException.invalid("values.title must be a non-empty string");
            }
        }
        // The busy check and the write share the admission lock so a Run cannot start between
        // them; an explicit rename therefore either wins before admission or is rejected with 409.
        final String newTitle = renamed;
        synchronized (thread) {
            if (threadStatus(thread).equals("busy")) {
                throw ConversationException.conflict("Thread has an active run");
            }
            if (newTitle != null) {
                thread.setTitle(newTitle);
                thread.updatedAt = Instant.now();
                persistThread(thread);
            }
        }
        return threadStateResponse(thread, THREAD_STATE_MAX_TURNS);
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

    List<Map<String, Object>> getThreadHistory(String threadId, JsonNode body) throws ConversationException {
        ThreadRecord thread = threads.get(threadId);
        if (thread == null) {
            throw ConversationException.notFound("Thread not found");
        }
        JsonNode limitNode = body.get("limit");
        if (limitNode != null
                && (!limitNode.canConvertToInt()
                        || !limitNode.isIntegralNumber()
                        || limitNode.intValue() < 1
                        || limitNode.intValue() > 100)) {
            throw ConversationException.invalid("limit must be between 1 and 100");
        }
        Long beforeSequence = null;
        if (body.has("before") && !body.get("before").isNull()) {
            JsonNode before = body.get("before");
            if (!before.isTextual() || !before.textValue().matches("turn-[1-9][0-9]*")) {
                throw ConversationException.invalid("before must be a checkpoint cursor");
            }
            try {
                beforeSequence = Long.parseLong(before.textValue().substring("turn-".length()));
            } catch (NumberFormatException overflow) {
                throw ConversationException.invalid("before must be a checkpoint cursor");
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
        return response;
    }

    Map<String, Object> listThreadMessagesPage(String threadId, String rawQuery) throws ConversationException {
        ThreadRecord thread = threads.get(threadId);
        if (thread == null) {
            throw ConversationException.notFound("Thread not found");
        }
        if (queryValue(rawQuery, "after_seq").isPresent()) {
            throw ConversationException.invalid("after_seq is not supported by this backward-only endpoint");
        }
        Optional<Integer> limit = positiveQueryInteger(rawQuery, "limit", 50, 200);
        Optional<String> beforeRaw = queryValue(rawQuery, "before_seq");
        Long before = null;
        try {
            if (beforeRaw.isPresent()) {
                before = Long.parseLong(beforeRaw.orElseThrow());
                if (before < 1) {
                    throw new NumberFormatException();
                }
            }
        } catch (NumberFormatException invalid) {
            throw ConversationException.invalid("Invalid message pagination");
        }
        if (limit.isEmpty()) {
            throw ConversationException.invalid("Invalid message pagination");
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
        return response;
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
        FeedRows output = new FeedRows(false);
        projectMessageRows(thread, turns.stream().map(DisplayTurn::body).toList(), ordinals, output);
        applyLatestRunFailure(thread, output.rows.stream().map(row -> {
            @SuppressWarnings("unchecked") Map<String, Object> content = (Map<String, Object>) row.get("content");
            return content;
        }).toList());
        return output.rows;
    }

    /** Request-local metadata only: no historical text, Tool arguments or result bodies. */
    private record DisplayTurn(String messageId, io.haifa.agent.core.message.MessageRole role,
            Optional<AgentRunId> runId, long sequence, Instant createdAt, ConversationTurn content) {
        static DisplayTurn body(ConversationTurn turn) {
            return new DisplayTurn(turn.messageId(), turn.role(), turn.runId(), turn.sequence(), turn.createdAt(), turn);
        }
        static DisplayTurn position(ConversationTurn turn) {
            return new DisplayTurn(turn.messageId(), turn.role(), turn.runId(), turn.sequence(), turn.createdAt(), null);
        }
    }

    /** The existing global ordinal scan also supplies lightweight feed-position inputs. */
    private static final class TurnIndex extends HashMap<Long, Integer> {
        final List<DisplayTurn> turns = new ArrayList<>();
        Map<Object, Long> positions;
        FeedPrefix reusedPrefix;
    }

    private record FeedPrefix(List<DisplayTurn> turns, Map<Object, Long> positions, long offset,
            Object interactions, Object clarifications, Set<String> runIds) {}

    private FeedPrefix usablePrefix(ThreadRecord thread, List<DisplayTurn> turns, List<Map<String, Object>> wanted) {
        FeedPrefix prefix;
        synchronized (feedPrefixes) { prefix = feedPrefixes.get(thread); }
        if (prefix == null || !prefix.interactions().equals(thread.interactionDisplays.stream()
                        .filter(display -> prefix.runIds().contains(display.runId())).toList())
                || !prefix.clarifications().equals(thread.clarificationDisplays.stream()
                        .filter(display -> prefix.runIds().contains(display.runId())).toList())
                || prefix.turns().size() > turns.size()
                || !prefix.turns().equals(turns.subList(0, prefix.turns().size()))) return null;
        return containsWantedPrefix(prefix, wanted) ? prefix : null;
    }

    private static boolean containsWantedPrefix(FeedPrefix prefix, List<Map<String, Object>> wanted) {
        return wanted.stream().noneMatch(message -> (message.get("run_id") == null
                || prefix.runIds().contains(message.get("run_id")))
                && !prefix.positions().containsKey(message.get("id")));
    }

    private void rememberPrefix(ThreadRecord thread, List<DisplayTurn> turns, int boundary, FeedRows output, long offset) {
        Map<Object, Long> retained = new HashMap<>();
        output.positions.entrySet().stream().sorted(Map.Entry.<Object, Long>comparingByValue().reversed())
                .limit(8192).forEach(entry -> retained.put(entry.getKey(), entry.getValue()));
        List<DisplayTurn> source = List.copyOf(turns.subList(0, boundary));
        Set<String> runIds = source.stream().flatMap(turn -> turn.runId().stream()).map(AgentRunId::value)
                .collect(java.util.stream.Collectors.toSet());
        FeedPrefix prefix = new FeedPrefix(source, Map.copyOf(retained), offset,
                thread.interactionDisplays.stream().filter(display -> runIds.contains(display.runId())).toList(),
                thread.clarificationDisplays.stream().filter(display -> runIds.contains(display.runId())).toList(), runIds);
        synchronized (feedPrefixes) {
            feedPrefixes.put(thread, prefix);
            while (feedPrefixes.size() > 8) feedPrefixes.remove(feedPrefixes.keySet().iterator().next());
        }
    }

    private final class FeedRows {
        final boolean positionsOnly;
        final List<Map<String, Object>> rows = new ArrayList<>();
        final Map<Object, Long> positions = new HashMap<>();
        String supportRun;
        List<SupportRound> supportRounds;
        FeedPrefix prefix;
        boolean prefixSafe = true;

        FeedRows(boolean positionsOnly) { this.positionsOnly = positionsOnly; }

        void add(String runId, long sequence, Instant createdAt, String id, Supplier<Map<String, Object>> content) {
            positions.put(id, sequence);
            if (!positionsOnly) {
                Map<String, Object> message = content.get();
                stampMessagePosition(message, sequence);
                rows.add(messageRow(runId, sequence, createdAt, message));
            }
        }

        void add(String runId, long sequence, Instant createdAt, Map<String, Object> content) {
            add(runId, sequence, createdAt, (String) content.get("id"), () -> content);
        }
    }

    private String displayTurnId(ThreadRecord thread, DisplayTurn turn, int ordinal) {
        boolean human = turn.role() == io.haifa.agent.core.message.MessageRole.USER;
        return turn.runId().map(runId -> human ? humanWireId(thread, runId, ordinal, true)
                : wireMessageId(runId.value(), "ai", ordinal)).orElse(turn.messageId());
    }

    private void projectMessageRows(ThreadRecord thread, List<DisplayTurn> turns,
            Map<Long, Integer> ordinals, FeedRows output) {
        List<PersistentThreadCatalog.InteractionDisplay> displays;
        synchronized (thread) { displays = List.copyOf(thread.interactionDisplays); }
        Map<Long, List<PersistentThreadCatalog.InteractionDisplay>> anchored = new HashMap<>();
        Set<String> anchoredRequests = new HashSet<>();
        for (var display : displays) {
            List<DisplayTurn> humans = turns.stream()
                    .filter(turn -> turn.role() == io.haifa.agent.core.message.MessageRole.USER
                            && turn.runId().map(AgentRunId::value).filter(display.runId()::equals).isPresent())
                    .toList();
            if (humans.isEmpty()) continue;
            DisplayTurn anchor = humans.stream()
                    .filter(turn -> !turn.createdAt().isAfter(display.createdAt()))
                    .reduce((left, right) -> right).orElse(humans.getFirst());
            anchored.computeIfAbsent(anchor.sequence(), ignored -> new ArrayList<>()).add(display);
            anchoredRequests.add(display.requestId());
        }
        Map<String, RunRecord> cached = new LinkedHashMap<>();
        runs.values().stream()
                .filter(run -> run.threadId.equals(thread.threadId))
                .forEach(run -> cached.put(run.runId.value(), run));
        Set<String> supportProjectedRuns = new HashSet<>();
        Set<String> supportProjectedMessages = new HashSet<>();
        Map<String, RunUsageFacts> usageFactsByRun = new HashMap<>();
        Map<String, DisplayTurn> visibleAssistantTexts = new HashMap<>();
        turns.stream().filter(turn -> turn.role() == io.haifa.agent.core.message.MessageRole.ASSISTANT)
                .forEach(turn -> visibleAssistantTexts.put(turn.messageId(), turn));
        // Native chronology of a settled Run's Tool rounds is partitioned per USER turn: the window
        // opens after this USER turn's native sequence and closes before the next same-Run Steer
        // USER turn's native sequence (exclusive), so a Steer keeps its own later rounds after it.
        Map<Long, Long> runSteerCeilings = new HashMap<>();
        {
            Map<String, Long> lastUserSequenceByRun = new HashMap<>();
            for (DisplayTurn scan : turns) {
                if (scan.role() != io.haifa.agent.core.message.MessageRole.USER) continue;
                Long previous = lastUserSequenceByRun.put(
                        scan.runId().map(AgentRunId::value).orElse(""), scan.sequence());
                if (previous != null) runSteerCeilings.put(previous, scan.sequence());
            }
        }
        int boundary = 0;
        if (!turns.isEmpty() && turns.getLast().runId().isPresent()) {
            AgentRunId newest = turns.getLast().runId().orElseThrow();
            while (boundary < turns.size() && !turns.get(boundary).runId().filter(newest::equals).isPresent()) boundary++;
        }
        int start = output.prefix == null ? 0 : output.prefix.turns().size();
        long offset = output.prefix == null ? 0 : output.prefix.offset();
        if (output.prefix != null) output.positions.putAll(output.prefix.positions());
        for (int turnIndex = start; turnIndex < turns.size(); turnIndex++) {
            if (output.positionsOnly && turnIndex == boundary && boundary > 0 && output.prefixSafe
                    && (output.prefix == null || output.prefix.turns().size() != boundary)) {
                rememberPrefix(thread, turns, boundary, output, offset);
            }
            DisplayTurn turn = turns.get(turnIndex);
            if (supportProjectedMessages.contains(turn.messageId())) continue;
            long sequence = turn.sequence() + offset;
            int ordinal = ordinals.getOrDefault(turn.sequence(), 0);
            output.add(turn.runId().map(AgentRunId::value).orElse(null), sequence, turn.createdAt(),
                    displayTurnId(thread, turn, ordinal),
                    () -> messageForTurn(
                            thread, turn.content(), ordinal, true,
                            usageForTurn(thread, turn, cached, usageFactsByRun)));
            if (turn.role() == io.haifa.agent.core.message.MessageRole.USER && turn.runId().isPresent()) {
                output.positions.put(wireMessageId(turn.runId().orElseThrow().value(), "human", ordinal), sequence);
            }
            long supportSlots = 0;
            if (turn.role() == io.haifa.agent.core.message.MessageRole.USER
                    && turn.runId().isPresent()) {
                AgentRunId runId = turn.runId().orElseThrow();
                // The SDK turn scan keeps only User/Assistant turns: native Tool messages and pure
                // Tool assistants produce no durable ConversationTurn at all, so a settled Run's
                // Tool round vanishes from the page feed even when a final assistant answer exists.
                // Project all settled Tool rounds, including narration and failed Runs.
                // Terminal eligibility is remembered once per Run, but a later same-Run Steer
                // USER turn must still replay only its own Tool rounds, so this is never a skip.
                // Native public message IDs own the identity; the visibleAssistantTexts /
                // supportProjectedMessages dedup keeps ordinary Tool-assistant text on its round.
                boolean terminal = supportProjectedRuns.contains(runId.value())
                        || isTerminalRun(thread, runId, cached.get(runId.value()));
                if (!terminal) output.prefixSafe = false;
                if (terminal && !prepareSupportRounds(thread, runId, cached.get(runId.value()), output).isEmpty()) {
                    supportProjectedRuns.add(runId.value());
                    RunUsageFacts supportUsage = output.positionsOnly
                            ? RunUsageFacts.EMPTY
                            : usageFactsFor(thread, runId, cached.get(runId.value()), usageFactsByRun);
                    supportSlots = appendRunSupportRows(
                            thread, runId, cached.get(runId.value()), sequence + 1, turn.createdAt(), output,
                            visibleAssistantTexts, ordinals, supportProjectedMessages,
                            turn.sequence(), runSteerCeilings.get(turn.sequence()), supportUsage);
                }
            }
            List<PersistentThreadCatalog.InteractionDisplay> cards = anchored.getOrDefault(turn.sequence(), List.of())
                    .stream().filter(display -> !supportProjectedRuns.contains(display.runId())).toList();
            offset += supportSlots + appendInteractionRows(thread, cards, sequence + supportSlots, output);
        }
        Set<String> durableHumanRuns = turns.stream()
                .filter(turn -> turn.role() == io.haifa.agent.core.message.MessageRole.USER)
                .flatMap(turn -> turn.runId().stream()).map(AgentRunId::value)
                .collect(java.util.stream.Collectors.toSet());
        long latest = turns.isEmpty() ? 0 : turns.getLast().sequence();
        for (RunRecord run : runs.values().stream()
                .filter(candidate -> candidate.threadId.equals(thread.threadId))
                .sorted((left, right) -> left.createdAt.compareTo(right.createdAt)).toList()) {
            if (!isInFlightWithoutDurableHuman(run, durableHumanRuns)) continue;
            long baseSequence = Math.max(run.humanSequence, latest + 1);
            long sequence = baseSequence + offset;
            output.add(run.runId.value(), sequence, run.createdAt, pendingHumanMessage(run));
            List<PersistentThreadCatalog.InteractionDisplay> cards = displays.stream()
                    .filter(display -> display.runId().equals(run.runId.value())
                            && !anchoredRequests.contains(display.requestId())).toList();
            offset += appendInteractionRows(thread, cards, sequence, output);
            latest = baseSequence;
        }
    }

    private void applyLatestRunFailure(ThreadRecord thread, List<Map<String, Object>> rows) {
        // Authoritative Run metadata keeps the latest failure visible after a page reload.
        // One authoritative snapshot per projection, rather than one per historical human.
        Map<String, Object> latestHuman = null;
        for (Map<String, Object> content : rows) {
            if ("human".equals(content.get("type"))
                    && (!(content.get("additional_kwargs") instanceof Map<?, ?> metadata)
                            || !Boolean.TRUE.equals(metadata.get("hide_from_ui")))) latestHuman = content;
        }
        if (latestHuman != null && latestHuman.get("run_id") instanceof String value) {
            AgentRunId runId = new AgentRunId(value);
            RunRecord cachedRun = runs.get(value);
            HaifaRunBridge bridge = cachedRun == null ? bridgeForPersistedThread(thread) : cachedRun.bridge;
            AgentRunSnapshot snapshot = bridge.snapshot(runId);
            if (snapshot.status() == AgentRunStatus.FAILED || snapshot.status() == AgentRunStatus.TIMEOUT) {
                Map<String, Object> failure = new LinkedHashMap<>();
                failure.put("run_id", value);
                snapshot.error().ifPresent(error -> {
                    failure.put("code", error.code().wireCode());
                    error.optionalDiagnosticId().ifPresent(id -> failure.put("diagnostic_id", id));
                });
                @SuppressWarnings("unchecked")
                Map<String, Object> existing = (Map<String, Object>) latestHuman.getOrDefault("additional_kwargs", Map.of());
                Map<String, Object> metadata = new LinkedHashMap<>(existing);
                metadata.put("run_error", failure);
                latestHuman.put("additional_kwargs", metadata);
            }
        }
    }

    private static void stampMessagePosition(Map<String, Object> content, long sequence) {
        @SuppressWarnings("unchecked")
        Map<String, Object> existing = (Map<String, Object>) content.getOrDefault("additional_kwargs", Map.of());
        Map<String, Object> metadata = new LinkedHashMap<>(existing);
        metadata.put("deerflow_seq", sequence);
        content.put("additional_kwargs", metadata);
    }

    private long appendInteractionRows(
            ThreadRecord thread, List<PersistentThreadCatalog.InteractionDisplay> displays,
            long humanSequence, FeedRows rows) {
        long offset = 0;
        for (var display : displays) {
            long requestSequence = humanSequence + offset + 1;
            offset += 2;
            if (!isVisibleInteractionDisplay(thread, display)) continue;
            rows.add(display.runId(), requestSequence, display.createdAt(), requestMessage(display));
            if (display.answeredOption() != null) {
                rows.add(display.runId(), requestSequence + 1, display.createdAt(), responseMessage(display));
            }
        }
        return offset;
    }

    /**
     * The public SDK Run state decides whether its Tool transcript has settled.
     */
    private boolean isTerminalRun(ThreadRecord thread, AgentRunId runId, RunRecord cached) {
        HaifaRunBridge bridge = cached == null ? bridgeForPersistedThread(thread) : cached.bridge;
        return isKnownTerminal(() -> bridge.findRun(runId));
    }

    /**
     * Page rows for settled Tool rounds, windowed per USER turn.
     * Mirrors the history overlay's one-AI-message-per-model-round projection (that round's whole
     * Tool call list, then each call's result), retaining each Interaction before that round's
     * results and reserving both of its display slots even when hidden. Rounds are placed by the
     * native ASSISTANT RunMessageView sequence of the message owning the round: strictly after the
     * driving USER turn's native sequence and strictly before the next same-Run Steer USER turn's
     * native sequence when one exists. The ordinary turn loop does not emit these rows again.
     */
    private List<SupportRound> prepareSupportRounds(ThreadRecord thread, AgentRunId runId,
            RunRecord cached, FeedRows output) {
        if (!runId.value().equals(output.supportRun)) {
            output.supportRun = runId.value();
            HaifaRunBridge bridge = cached == null ? bridgeForPersistedThread(thread) : cached.bridge;
            output.supportRounds = supportRounds(bridge, runId);
        }
        return output.supportRounds;
    }

    private record SupportRound(String messageId, long sequence, Instant createdAt, List<ToolCallView> calls) {}

    private List<SupportRound> supportRounds(HaifaRunBridge bridge, AgentRunId runId) {
        List<ToolCallView> calls = DeerFlowToolTranscriptProjection.upToClarificationBoundary(bridge.toolCalls(runId));
        if (calls.isEmpty()) return List.of();
        Map<String, ToolCallView> visible = new HashMap<>();
        calls.forEach(call -> visible.put(call.id().value(), call));
        Set<String> placed = new HashSet<>();
        List<SupportRound> rounds = new ArrayList<>();
        RunMessageCursor cursor = RunMessageCursor.beforeFirst(runId);
        int pages = 0;
        while (true) {
            positionMessagePageReads.incrementAndGet();
            RunMessagePage page = bridge.messages(runId, cursor, MESSAGE_ROUND_SCAN_PAGE_SIZE);
            for (RunMessageView message : page.items()) {
                if (!"ASSISTANT".equals(message.role())) continue;
                List<ToolCallView> round = message.toolCalls().stream().map(call -> visible.get(call.id().value()))
                        .filter(java.util.Objects::nonNull).toList();
                if (round.isEmpty()) continue;
                round.forEach(call -> placed.add(call.id().value()));
                rounds.add(new SupportRound(message.messageId(), message.sequence(),
                        Instant.ofEpochMilli(message.createdAtEpochMillis()), round));
            }
            if (!page.hasMore()) break;
            requireAnotherScanPage(++pages);
            cursor = page.nextCursor();
        }
        if (!placed.containsAll(visible.keySet())) {
            throw new IllegalStateException("Tool round native message position is unavailable");
        }
        return rounds;
    }

    private long appendRunSupportRows(
            ThreadRecord thread, AgentRunId runId, RunRecord cached, long startSequence,
            Instant createdAt, FeedRows output, Map<String, DisplayTurn> visibleAssistantTexts,
            Map<Long, Integer> ordinals, Set<String> supportProjectedMessages,
            long afterNativeSequence, Long beforeNativeSequence, RunUsageFacts usageFacts) {
        // A Steer can reference the same Run several times. Read its public message
        // pages once, and release the prior Run's plan as the feed advances.
        List<SupportRound> rounds = prepareSupportRounds(thread, runId, cached, output);
        Map<String, Integer> roundByCall = new HashMap<>();
        for (int index = 0; index < rounds.size(); index++) {
            for (ToolCallView call : rounds.get(index).calls()) roundByCall.put(call.id().value(), index);
        }
        Map<Integer, List<PersistentThreadCatalog.InteractionDisplay>> cardsByRound = new HashMap<>();
        synchronized (thread) {
            for (var display : thread.interactionDisplays) {
                if (display.runId().equals(runId.value())) {
                    cardsByRound.computeIfAbsent(roundByCall.getOrDefault(display.toolCallId(), 0),
                            ignored -> new ArrayList<>()).add(display);
                }
            }
        }
        long slot = 0;
        for (int index = 0; index < rounds.size(); index++) {
            SupportRound round = rounds.get(index);
            if (round.sequence() <= afterNativeSequence
                    || (beforeNativeSequence != null && round.sequence() >= beforeNativeSequence)) continue;
            DisplayTurn narration = visibleAssistantTexts.get(round.messageId());
            if (narration != null) {
                int ordinal = ordinals.getOrDefault(narration.sequence(), 0);
                output.add(runId.value(), startSequence + slot++, round.createdAt(),
                        displayTurnId(thread, narration, ordinal),
                        () -> messageForTurn(
                                thread, narration.content(), ordinal, true, usageFacts.usageByMessageId()));
                supportProjectedMessages.add(round.messageId());
            }
            Map<String, Object> requestUsage = narration == null
                    ? usageFacts.usageByToolCallId().get(round.calls().getFirst().id().value())
                    : null;
            output.add(runId.value(), startSequence + slot++, createdAt,
                    DeerFlowToolTranscriptProjection.requestMessageId(round.calls().getFirst()),
                    () -> DeerFlowToolTranscriptProjection.requestMessage(round.calls(), requestUsage));
            slot += appendInteractionRows(thread, cardsByRound.getOrDefault(index, List.of()),
                    startSequence + slot - 1, output);
            for (ToolCallView call : round.calls()) {
                Optional<String> id = DeerFlowToolTranscriptProjection.resultMessageId(call);
                if (id.isPresent()) {
                    output.add(runId.value(), startSequence + slot++, createdAt, id.orElseThrow(),
                            () -> DeerFlowToolTranscriptProjection.resultMessage(call).orElseThrow());
                }
            }
        }
        return slot;
    }

    List<Map<String, Object>> searchThreads(JsonNode body) throws ConversationException {
        int limit = body.path("limit").asInt(100);
        int offset = body.path("offset").asInt(0);
        if (limit < 1 || limit > 1000 || offset < 0) {
            throw ConversationException.invalid("Invalid search pagination");
        }
        Map<String, Object> metadataFilter = objectMap(body.get("metadata"));
        Optional<String> statusFilter = text(body, "status");
        boolean archivedFilterPresent = body.has("archived") && !body.get("archived").isNull();
        boolean archived = body.path("archived").asBoolean(false);
        List<ThreadRecord> candidates = threads.values().stream()
                .filter(record -> AccessOwner.current().equals(record.ownerId))
                .filter(record -> !archivedFilterPresent
                        || Boolean.TRUE.equals(record.metadata.get("deerflow_archived")) == archived)
                .filter(record -> metadataFilter.entrySet().stream()
                        .allMatch(entry -> java.util.Objects.equals(
                                record.metadata.get(entry.getKey()), entry.getValue())))
                .sorted((left, right) -> right.updatedAt.compareTo(left.updatedAt))
                .toList();
        // Resolving one Thread's durable projection can fail on its own: its persisted session may
        // be gone, or its recorded Skill/Run projection may no longer be readable. Such a Thread is
        // skipped and recorded here; the page keeps every Thread it can describe instead of failing
        // the whole list for every client.
        List<ThreadRecord> matching = new ArrayList<>(candidates.size());
        for (ThreadRecord record : candidates) {
            if (statusFilter.isEmpty()) {
                matching.add(record);
                continue;
            }
            try {
                if (statusFilter.orElseThrow().equals(threadStatus(record))) matching.add(record);
            } catch (RuntimeException unavailable) {
                logSearchProjectionFailure("status", record, unavailable);
            }
        }
        int from = Math.min(offset, matching.size());
        int to = Math.min(from + limit, matching.size());
        List<Map<String, Object>> matches = new ArrayList<>(to - from);
        for (ThreadRecord record : matching.subList(from, to)) {
            try {
                matches.add(threadResponse(record));
            } catch (RuntimeException unavailable) {
                logSearchProjectionFailure("row", record, unavailable);
            }
        }
        return matches;
    }

    private static void logSearchProjectionFailure(String scope, ThreadRecord record, RuntimeException failure) {
        INTERACTION_LOG.log(System.Logger.Level.WARNING,
                "Thread search skipped a " + scope + " projection for " + record.threadId + ": "
                        + failure.getClass().getSimpleName() + ": " + boundedReason(failure));
    }

    public AdmittedTurn admitTurn(String threadId, JsonNode body, String runInput, String lastEventId) throws IOException {
        ThreadRecord thread = threads.get(threadId);
        if (thread == null || !AccessOwner.current().equals(thread.ownerId)) {
            throw ConversationException.notFound("Thread not found");
        }
        Optional<String> validationError = validateRunRequest(body);
        if (validationError.isPresent()) {
            throw ConversationException.invalid(validationError.orElseThrow());
        }
        if (runInput != null && (!"steer".equals(runInput)
                || body.has("multitask_strategy"))) {
            throw ConversationException.invalid("Invalid Haifa Run input mode");
        }
        String message = lastHumanMessage(body.path("input").path("messages"));
        if (message.isBlank()) {
            throw ConversationException.invalid("input.messages requires a human message");
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
            throw ConversationException.invalid(invalid.getMessage());
        }
        DeerFlowInteractionHttpProjection.FrozenResponse clarificationAck = null;
        if (interactionResponse.isPresent()) {
            if (runInput != null) {
                throw ConversationException.invalid("Haifa Run input mode cannot answer an Interaction");
            }
            DeerFlowInteractionHttpProjection.FrozenResponse response = interactionResponse.orElseThrow();
            if (DeerFlowInteractionHttpProjection.CLARIFICATION_SOURCE.equals(response.source())) {
                // Frozen has no server-side Interaction for ask_clarification: the hidden reply is a
                // normal human message that opens the next Run, and a forged request_id never
                // triggers an approval. An already answered request_id is rejected inside admission
                // before another Turn starts.
                clarificationAck = response;
            } else {
                return respondToInteraction(
                        thread, response, message, text(body, "on_disconnect").orElse("cancel"));
            }
        }
        HaifaRunBridge.ModelBinding binding;
        AgentPromptContext agentPrompt;
        try {
            binding = runModelResolver.apply(body);
            agentPrompt = agentPromptResolver.apply(body);
        } catch (IllegalArgumentException exception) {
            throw ConversationException.invalid(exception.getMessage());
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
            throw ConversationException.invalid(invalid.getMessage());
        } catch (IllegalStateException unavailable) {
            throw ConversationException.conflict("Trusted Skill configuration is unavailable");
        }

        RunRecord run = null;
        RunRecord steerRun = null;
        ProjectionCursor steerCursor = null;
        RunRecord synchronizedInputRun = null;
        if (runInput != null) {
            synchronized (thread) {
                restoreDurableAdmission(thread);
                if (thread.activeRunId != null) {
                    synchronizedInputRun = runs.get(thread.activeRunId);
                    if (synchronizedInputRun == null) {
                        synchronizedInputRun = findOrRestoreRun(thread, thread.activeRunId).orElse(null);
                    }
                }
            }
            if (synchronizedInputRun != null) {
                try {
                    // Catch-up lets live writers read this Thread's transcript. Holding its
                    // monitor here prevents that progress and can expire their bounded lease.
                    synchronizeAllAvailable(synchronizedInputRun);
                } catch (IllegalArgumentException | EventProjectionException invalid) {
                    throw ConversationException.conflict(invalid.getMessage());
                }
            }
        }
        synchronized (thread) {
            restoreDurableAdmission(thread);
            if (clarificationAck != null && clarificationAlreadyAnswered(thread, clarificationAck.requestId())) {
                throw ConversationException.conflict("Interaction response already accepted");
            }
            if (thread.activeRunId != null) {
                RunRecord active = activeRunRecord(thread);
                if (active != null && runIsActive(active)) {
                    if (runInput == null) {
                        throw ConversationException.conflict("Thread already has an active run");
                    }
                    if (active != synchronizedInputRun) {
                        throw ConversationException.conflict("Thread active run changed before Steer admission");
                    }
                    // Steer continues an existing declaration; terminal history may admit a fresh
                    // Turn from trusted current configuration without migrating its old Runs.
                    if (!pendingPromptAvailable(thread, active)) {
                        throw ConversationException.conflict("Pending Run prompt or agent identity is unavailable");
                    }
                    JsonNode requestedContext = body.path("context");
                    boolean declaresModelSettings = requestedContext.has("mode")
                            || requestedContext.has("thinking_enabled") || requestedContext.has("reasoning_effort")
                            || requestedContext.has("model_name");
                    Object originalDigest = thread.metadata.get(MODEL_CONFIGURATION_DIGEST_METADATA_KEY);
                    String expectedDigest = originalDigest instanceof String value ? value
                            : active.bridge.modelConfigurationDigest();
                    if (declaresModelSettings && !expectedDigest.equals(binding.snapshot().configurationDigest())) {
                        throw ConversationException.conflict("Active Run model settings are frozen");
                    }
                    ProjectionCursor requestedCursor;
                    try {
                        requestedCursor = parseSseCursor(active.runId.value(), lastEventId);
                        active.projector.replayAfter(requestedCursor);
                    } catch (IllegalArgumentException | EventProjectionException invalid) {
                        throw ConversationException.conflict(invalid.getMessage());
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
                        throw ConversationException.internal("Steer input could not be submitted");
                    }
                    if (result.status() == RunInputStatus.REJECTED) {
                        throw ConversationException.conflict(
                                "Run is not accepting input: " + result.reasonCode().orElse("rejected"));
                    }
                    steerRun = active;
                    steerCursor = requestedCursor;
                } else if (active != null) {
                    completeWorkspaceChangesIfTerminal(active);
                }
            }
            if (runInput != null && steerRun == null) {
                throw ConversationException.conflict("Thread has no active run to steer");
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
                throw ConversationException.invalid(invalid.getMessage());
            } catch (IllegalStateException unavailable) {
                throw ConversationException.conflict("Child configuration is unavailable");
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
                // The declared Skill script Tool names are environment-and-catalog facts, computed
                // fresh so a later enable/disable or bun change rebuilds the disclosure consistently.
                Set<String> platformToolNames = new java.util.LinkedHashSet<>(selectedBridge.mcpToolNames());
                platformToolNames.addAll(SkillScriptToolConfig.toolNames(System.getenv(), skillProjection));
                boolean visionSupported = HaifaRunBridge.supportsVision(binding.snapshot());
                switchingPrompt = !selectedBridge.matchesAgentIdentity(agentPrompt)
                        || !selectedBridge.promptSignature().equals(
                                HaifaRunBridge.promptSignatureFor(toolsForThread(thread, agentPrompt), skillProjection, agentPrompt, delegation,
                                        platformToolNames, visionSupported));
                switchingDelegation = !selectedBridge.delegationSignature().equals(
                        delegation.signature(toolsForThread(thread, agentPrompt), mcpProjection.fingerprint(),
                                skillProjection == null ? "" : skillProjection.fingerprint(), selectedBridge.mcpToolNames()));
                if (switchingModel || switchingMcp || switchingSkills || switchingPrompt || switchingDelegation || switchingPlanMode) {
                    selectedBridge = newBridge(binding, thread, mcpProjection, skillProjection, agentPrompt, delegation, isPlanMode);
                }
            } catch (RuntimeException failure) {
                ADMISSION_LOG.log(System.Logger.Level.WARNING, admissionFailureMessage(failure));
                if (createdBridge && thread.bridge != null) {
                    ownedBridges.remove(thread.bridge);
                    thread.bridge.close();
                    thread.bridge = null;
                }
                throw ConversationException.internal("Run could not start");
            }
            // Frozen TitleMiddleware names the thread only from its first user Turn. The boundary is
            // the in-memory message sequence (seeded from durable turns when a persisted session is
            // restored), so a prior failed turn keeps its slot even if its durable read lags, and no
            // auxiliary durable read can throw out of Run admission and block the main Run.
            boolean titleCandidate = thread.title() == null && thread.nextMessageSequence == 1;
            StagedRunChanges staged = null;
            try {
                WorkspaceBinding workspace = workspaceChanges.bindThreadWorkspace(
                        ThreadOwners.of(threadId), threadId);
                staged = workspaceChanges.stageRunChanges(
                        ThreadOwners.of(threadId), threadId, workspace.workspaceId());
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
                ADMISSION_LOG.log(System.Logger.Level.WARNING, admissionFailureMessage(failure));
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
                throw ConversationException.internal("Run could not start");
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
            return new AdmittedTurn(
                    thread,
                    steerRun,
                    steerCursor,
                    false,
                    text(body, "on_disconnect").orElse("continue"),
                    false,
                    false,
                    false);
        }
        observeWorkspaceCompletion(run);
        observeTitleCompletion(thread, run);
        return new AdmittedTurn(
                thread,
                run,
                new ProjectionCursor(run.runId.value(), 0),
                true,
                text(body, "on_disconnect").orElse("cancel"),
                true,
                false,
                false);
    }

    AdmittedTurn respondToInteraction(
            ThreadRecord thread,
            DeerFlowInteractionHttpProjection.FrozenResponse response,
            String submittedText,
            String onDisconnect)
            throws IOException {
        RunRecord run;
        ProjectionCursor responseCursor;
        boolean presentationDegraded = false;
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
                throw ConversationException.conflict("Thread has no matching Interaction");
            }
            if (!pendingPromptAvailable(thread, run)) {
                throw ConversationException.conflict("Pending Run prompt or agent identity is unavailable");
            }
            // A read-only fallback bridge may expose the durable pending card, but must never
            // admit its side effect if the original Run's model revision is unavailable.
            if (thread.modelBindingIdentity != null
                    && !thread.modelBindingIdentity.equals(run.bridge.bindingIdentity())) {
                throw ConversationException.conflict("Pending Run model is unavailable");
            }
        }
        // Restore pre-response durable events before accepting the reply, without holding the
        // Thread monitor needed by attached stream writers. Admission is revalidated below.
        try {
            synchronizeAllAvailable(run);
        } catch (EventProjectionException terminalRace) {
            throw ConversationException.conflict("Run is no longer accepting an Interaction response");
        }
        synchronized (thread) {
            if (!pendingPromptAvailable(thread, run)) {
                throw ConversationException.conflict("Pending Run prompt or agent identity is unavailable");
            }
            if (thread.modelBindingIdentity != null
                    && !thread.modelBindingIdentity.equals(run.bridge.bindingIdentity())) {
                throw ConversationException.conflict("Pending Run model is unavailable");
            }
            synchronized (run) {
                responseCursor = run.projector.head();
                if (run.acceptedInteractionResponse != null) {
                    if (!run.acceptedInteractionResponse.response().equals(response)) {
                        throw ConversationException.conflict("Interaction already has a different response");
                    }
                    try {
                        var receipt = run.bridge.respond(
                                run.acceptedInteractionResponse.submission());
                        if (receipt.status() != InteractionResponseReceiptStatus.DUPLICATE) {
                            throw ConversationException.conflict("Interaction duplicate was not acknowledged");
                        }
                    } catch (RuntimeException rejected) {
                        sendInteractionFailure(run, "duplicate", rejected);
                        throw ConversationException.conflict("Interaction response was rejected");
                    }
                    throw ConversationException.conflict("Interaction response already accepted");
                } else {
                    // A terminal Run (including a cancel) can no longer apply an Interaction
                    // response. The SDK may still expose a stale pending view, so reject here
                    // instead of surfacing an opaque 500 and never blindly replaying the tool.
                    if (run.bridge.snapshot(run.runId).status().isTerminal()) {
                        throw ConversationException.conflict("Run is no longer accepting an Interaction response");
                    }
                    Optional<InteractionView> pending = run.bridge.pendingInteraction(run.runId);
                    if (pending.isEmpty()
                            || !pending.orElseThrow().requestId().value().equals(response.requestId())) {
                        throw ConversationException.conflict("Thread has no matching Interaction");
                    }
                    try {
                        // A legacy/restarted Run may have an SDK pending view but no saved
                        // presentation yet. Never accept its side effect before the card is durable.
                        ensureSavedPendingDisplay(thread, run, pending.orElseThrow());
                    } catch (IOException | IllegalArgumentException unavailablePresentation) {
                        INTERACTION_LOG.log(System.Logger.Level.WARNING,
                                "Interaction card could not be saved: "
                                        + unavailablePresentation.getClass().getName()
                                        + ": " + boundedReason(unavailablePresentation));
                        throw ConversationException.unavailable(
                                "Interaction card could not be saved; approval was not submitted");
                    }
                    InteractionResponseSubmission submission;
                    try {
                        submission = DeerFlowInteractionHttpProjection.submission(
                                pending.orElseThrow(), response, Instant.now());
                    } catch (IllegalArgumentException invalid) {
                        throw ConversationException.conflict("Thread has no matching Interaction");
                    }
                    // Single-flight per Interaction request: two channels (Feishu and Web/API) can
                    // submit the same card in the same second. Only one may enter the SDK resume path,
                    // otherwise the second submission races the resume transaction and the Run is left
                    // with a recorded response but no progress (W04-BUG-011). The claim is scoped to
                    // this request and released as soon as the attempt settles, so a later approval of
                    // the same Run is never rejected by a stale claim.
                    String claimKey = run.runId.value() + "|" + response.requestId();
                    var claimed = claimedInteractionResponses.putIfAbsent(claimKey, response);
                    if (claimed != null) {
                        throw ConversationException.conflict(claimed.equals(response)
                                ? "Interaction response already accepted"
                                : "Interaction already has a different response");
                    }
                    InteractionResponseReceipt receipt;
                    try {
                        receipt = respondWithRetry(run, submission);
                    } catch (RuntimeException rejected) {
                        sendInteractionFailure(run, "first-response", rejected);
                        throw ConversationException.conflict("Interaction response was rejected");
                    } finally {
                        claimedInteractionResponses.remove(claimKey, response);
                    }
                    if (receipt.status() == InteractionResponseReceiptStatus.DUPLICATE) {
                        throw ConversationException.conflict("Interaction response already accepted");
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
                        presentationDegraded = true;
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

        return new AdmittedTurn(
                thread,
                run,
                responseCursor,
                true,
                onDisconnect,
                true,
                false,
                presentationDegraded);
    }

    private InteractionResponseReceipt respondWithRetry(
            RunRecord run, InteractionResponseSubmission submission) {
        RuntimeException failure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return run.bridge.respond(submission);
            } catch (RuntimeException rejected) {
                failure = rejected;
                if (attempt > 0 || !isTransientStoreFailure(rejected)) {
                    break;
                }
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw failure;
    }

    private static boolean isTransientStoreFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SqliteStoreException) {
                return true;
            }
            if (current == current.getCause()) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    private void sendInteractionFailure(
            RunRecord run, String phase, RuntimeException failure) {
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
        throw ConversationException.forStatus(status, detail);
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

    /** The host shell Tool stays off unless the host explicitly enables it. */
    private HostShellToolConfig hostShellConfig() {
        if (dataDirectoryRoot == null) return null;
        return HostShellToolConfig.fromEnvironment(
                        System.getenv(), dataDirectoryRoot.resolve("workspace"), dataDirectoryRoot)
                .orElse(null);
    }

    /**
     * Product approval level. The default keeps today's per-call approval; only an explicit
     * {@code auto} (or {@code deny}) value changes the policy handed to the SDK.
     */
    private ApprovalPolicyConfig.Mode approvalMode() {
        return ApprovalPolicyConfig.fromEnvironment(System.getenv()).mode();
    }

    /**
     * The trusted Skill script declaration is a host shell chain: it additionally requires the
     * bounded bun runtime and the admitted Skill binding, and its workspace root is this Thread's
     * own user-data tree (bound here, idempotent) so the executed package writes {@code outputs/}
     * exactly where the Artifact chain serves. Any missing or conflicting fact keeps the Tool off.
     */
    private SkillScriptToolConfig skillScriptConfig(ThreadRecord thread, SkillRunTools.Projection skillProjection) {
        if (dataDirectoryRoot == null) return null;
        if (SkillScriptToolConfig.declaredBinding(skillProjection).isEmpty()) return null;
        if (SkillScriptToolConfig.bunExecutable(
                System.getenv().get(SkillScriptToolConfig.BUN_ENVIRONMENT_KEY)) == null) {
            return null;
        }
        try {
            return SkillScriptToolConfig.fromEnvironment(
                    System.getenv(),
                    workspaceChanges.bindThreadWorkspaceDataRoot(thread.ownerId, thread.threadId),
                    dataDirectoryRoot,
                    skillProjection).orElse(null);
        } catch (RuntimeException unavailable) {
            // A missing or conflicting Thread workspace must never widen the Tool surface.
            return null;
        }
    }

    private HaifaRunBridge newBridge(HaifaRunBridge.ModelBinding binding, ThreadRecord thread,
            McpRunTools.Projection mcpProjection, SkillRunTools.Projection skillProjection,
            AgentPromptContext agentPrompt,
            io.haifa.agent.superharness.server.config.SubagentRunConfiguration delegation,
            boolean isPlanMode) {
        bridgeBuilds.incrementAndGet();
        return AccessOwner.call(thread.ownerId, () -> new HaifaRunBridge(binding, toolsForThread(thread, agentPrompt),
                foundation, mcpProjection, skillProjection, agentPrompt, delegation, childRunCapacity, isPlanMode,
                executionExecutorFactory, hostShellConfig(), skillScriptConfig(thread, skillProjection),
                parentWallTimeMillisForTests, approvalMode()));
    }

    /** Test-only: the exact bridge currently attached to a Thread, or null when none is. */
    HaifaRunBridge currentBridge(String threadId) {
        ThreadRecord thread = threads.get(threadId);
        if (thread == null) return null;
        synchronized (thread) {
            return thread.bridge;
        }
    }

    // -------------------------------------------------- scheduled background launch

    /**
     * Starts one non-interactive background Run in-process. No public HTTP background-Run route is
     * added and no Run engine is created: this only ensures the Thread, builds the same public SDK
     * bridge with {@code ask_clarification} excluded (the narrow frozen {@code non_interactive}
     * assembly), starts the Run without blocking, and records the thread/Run association.
     */
    @Override
    public LaunchedRun launch(LaunchRequest request) {
        return AccessOwner.call(request.ownerId(), () -> {
            ThreadRecord thread = ensureBackgroundThread(request.ownerId(), request.threadId(),
                    request.createThreadIfMissing());
            // The stored assistant identity flows through the same validated resolver path as an
            // interactive Run, so the selected Agent's Soul/model/tool composition is applied
            // instead of silently falling back to the default identity.
            var resolverBody = json.createObjectNode();
            if (request.assistantId() != null && !request.assistantId().isBlank()) {
                resolverBody.put("assistant_id", request.assistantId());
            }
            HaifaRunBridge.ModelBinding binding;
            AgentPromptContext agentPrompt;
            try {
                binding = runModelResolver.apply(resolverBody);
                agentPrompt = agentPromptResolver.apply(resolverBody);
            } catch (RuntimeException unavailable) {
                throw new IllegalStateException("Scheduled Run model is unavailable", unavailable);
            }
            List<? extends JavaTool<?, ?>> tools = nonInteractiveTools(thread, agentPrompt);
            HaifaRunBridge bridge = newBackgroundBridge(binding, thread, tools, agentPrompt);
            HaifaRunBridge.StartedRun started;
            RunRecord run;
            synchronized (thread) {
                restoreDurableAdmission(thread);
                if (activeRunInFlight(thread)) {
                    // A background scheduled Run must never replace an active Run on the same
                    // Thread; the caller keeps its occurrence waiting instead of starting a second.
                    bridge.close();
                    throw new ScheduledRunLauncher.ActiveRunConflict(thread.threadId);
                }
                try {
                    started = bridge.start(thread.threadId, request.message(),
                            Optional.ofNullable(thread.session));
                } catch (RuntimeException failure) {
                    bridge.close();
                    throw new IllegalStateException("Scheduled Run could not start", failure);
                }
                thread.session = started.session();
                thread.persistedSessionId = started.session().sessionId();
                thread.bridge = bridge;
                thread.modelBindingIdentity = bridge.bindingIdentity();
                thread.activeRunId = started.runId().value();
                thread.latestRunId = started.runId().value();
                // Record the same prompt/agent selection metadata interactive admission persists, so
                // an approval-gated scheduled Run can resolve its pending prompt and accept the
                // human response instead of failing closed with a 409.
                thread.setLeadPromptSelection(null, bridge.promptSignature(), agentPrompt);
                Long existingFraming = thread.framingFromSequence();
                if (existingFraming == null) {
                    try {
                        thread.setFramingFromSequence(
                                Math.addExact(latestVisibleTurnSequence(thread, bridge), 1));
                    } catch (RuntimeException unavailableFraming) {
                        ADMISSION_LOG.log(System.Logger.Level.WARNING,
                                "scheduled Run framing sequence could not be recorded");
                    }
                }
                thread.updatedAt = Instant.now();
                long humanSequence = thread.nextMessageSequence;
                thread.nextMessageSequence += 2;
                run = new RunRecord(started.runId(), thread.threadId, request.assistantId(),
                        request.message(), null, false, bridge, Instant.now(), humanSequence, false,
                        Set.of("messages-tuple"), false);
                runs.put(run.runId.value(), run);
                try {
                    persistThread(thread);
                } catch (IOException persistenceFailure) {
                    ADMISSION_LOG.log(System.Logger.Level.WARNING,
                            "scheduled Run thread could not be persisted");
                }
            }
            ownedBridges.add(bridge);
            backgroundBridges.put(started.runId().value(), bridge);
            return new LaunchedRun(thread.threadId, started.runId().value());
        });
    }

    /**
     * True when the Thread already owns a non-terminal Run. A read that cannot be made is treated as
     * active so a transient failure never admits a second Run.
     */
    private boolean activeRunInFlight(ThreadRecord thread) {
        RunRecord active = activeRunRecord(thread);
        if (active == null) {
            return false;
        }
        try {
            return runIsActive(active);
        } catch (RuntimeException unavailable) {
            return true;
        }
    }

    /** The Run currently owning the Thread's active slot, without a terminal-status read. */
    private RunRecord activeRunRecord(ThreadRecord thread) {
        if (thread.activeRunId == null) {
            return null;
        }
        RunRecord active = runs.get(thread.activeRunId);
        if (active == null) {
            active = findOrRestoreRun(thread, thread.activeRunId).orElse(null);
        }
        return active;
    }

    /**
     * Shared Thread active-Run predicate used by both interactive admission and scheduled launch: a
     * Run owns its slot until its SDK status is terminal.
     */
    private static boolean runIsActive(RunRecord run) {
        return !run.bridge.snapshot(run.runId).status().isTerminal();
    }

    @Override
    public void watch(String ownerId, String threadId, String runId, Consumer<ScheduledRunOutcome> onTerminal) {
        if (!followedRuns.add(runId)) {
            return;
        }
        HaifaRunBridge candidate = backgroundBridges.get(runId);
        if (candidate == null) {
            ThreadRecord thread = threads.get(threadId);
            if (thread == null || !ownerId.equals(thread.ownerId)) {
                followedRuns.remove(runId);
                throw new IllegalStateException("Background Run is unknown");
            }
            // A Run that outlived the previous process has no in-memory bridge: rebuild the read
            // bridge from the persisted Thread so its completion can still be observed.
            try {
                candidate = AccessOwner.call(ownerId, () -> bridgeForPersistedThread(thread));
            } catch (RuntimeException unavailable) {
                followedRuns.remove(runId);
                throw new ScheduledRunLauncher.InspectionUnavailableException(
                        "Background Run bridge is unavailable", unavailable);
            }
            backgroundBridges.put(runId, candidate);
        }
        final HaifaRunBridge bridge = candidate;
        AgentRunId id = new AgentRunId(runId);
        Thread follower = Thread.ofVirtual()
                .name("super-harness-scheduled-follower-" + runId)
                .unstarted(() -> {
                    try {
                        long startedNanos = System.nanoTime();
                        long maxWaitNanos = BACKGROUND_FOLLOWER_MAX_WAIT.toNanos();
                        long pollMillis = BACKGROUND_FOLLOWER_INITIAL_POLL.toMillis();
                        while (!closed && System.nanoTime() - startedNanos < maxWaitNanos) {
                            try {
                                AgentRunSnapshot snapshot = bridge.snapshot(id);
                                if (snapshot.status().isTerminal()) {
                                    onTerminal.accept(new ScheduledRunOutcome(runId,
                                            scheduledTerminal(snapshot.status()),
                                            scheduledTerminalError(snapshot.status())));
                                    return;
                                }
                            } catch (RuntimeException unavailable) {
                                // A single transient snapshot failure must not end the only follower
                                // without a terminal callback; keep retrying within the deadline.
                                ADMISSION_LOG.log(System.Logger.Level.WARNING,
                                        "scheduled Run follower snapshot unavailable: "
                                                + unavailable.getClass().getName());
                            }
                            Thread.sleep(pollMillis);
                            pollMillis = nextWorkspaceCompletionPollMillis(pollMillis);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException failure) {
                        ADMISSION_LOG.log(System.Logger.Level.WARNING,
                                "scheduled Run follower stopped: " + failure.getClass().getName());
                    } finally {
                        backgroundFollowers.remove(Thread.currentThread());
                        backgroundBridges.remove(runId);
                        followedRuns.remove(runId);
                    }
                });
        backgroundFollowers.add(follower);
        follower.start();
    }

    @Override
    public Optional<ScheduledRunInspection> inspect(String ownerId, String threadId, String runId) {
        return AccessOwner.call(ownerId, () -> {
            HaifaRunBridge bridge = backgroundBridges.get(runId);
            if (bridge == null) {
                ThreadRecord thread = threads.get(threadId);
                if (thread == null || !ownerId.equals(thread.ownerId)) {
                    return Optional.<ScheduledRunInspection>empty();
                }
                try {
                    bridge = bridgeForPersistedThread(thread);
                } catch (RuntimeException unavailable) {
                    throw new ScheduledRunLauncher.InspectionUnavailableException(
                            "Background Run bridge is unavailable", unavailable);
                }
            }
            final HaifaRunBridge inspectionBridge = bridge;
            try {
                Optional<AgentRunSnapshot> found = inspectionBridge.findRun(new AgentRunId(runId));
                if (found.isEmpty()) {
                    return Optional.<ScheduledRunInspection>empty();
                }
                AgentRunSnapshot snapshot = found.orElseThrow();
                boolean terminal = snapshot.status().isTerminal();
                return Optional.of(new ScheduledRunInspection(terminal,
                        terminal ? scheduledTerminal(snapshot.status()) : null,
                        scheduledTerminalError(snapshot.status())));
            } catch (RuntimeException unavailable) {
                // A transient read failure must stay fail-closed: recovery keeps the active slot and
                // retries instead of treating an unreadable Run as gone.
                throw new ScheduledRunLauncher.InspectionUnavailableException(
                        "Background Run snapshot is unavailable", unavailable);
            }
        });
    }

    /**
     * Recovers a Run the Thread already committed for a launch whose association write failed. The
     * Run is durably recorded on the Thread's SDK conversation turns with its Run id and creation
     * time; only a Run created at or after the occurrence was admitted can belong to this claim, so
     * an earlier terminal Run on a reused Thread is never mistaken for it. A read that cannot be made
     * stays fail-closed so recovery keeps the claim rather than re-queuing an already-started Run.
     */
    @Override
    public Optional<LaunchedRun> recoverLaunch(String ownerId, String threadId, Instant launchedAfter) {
        return AccessOwner.call(ownerId, () -> {
            ThreadRecord thread = threads.get(threadId);
            if (thread == null || !ownerId.equals(thread.ownerId)) {
                throw new ScheduledRunLauncher.InspectionUnavailableException(
                        "Scheduled Run thread is unavailable", new IllegalStateException("thread unavailable"));
            }
            List<RunRecord> committed;
            try {
                committed = durableRuns(thread);
            } catch (RuntimeException unavailable) {
                throw new ScheduledRunLauncher.InspectionUnavailableException(
                        "Scheduled Run recovery bridge is unavailable", unavailable);
            }
            for (RunRecord run : committed) {
                if (!run.createdAt.isBefore(launchedAfter)) {
                    return Optional.of(new LaunchedRun(thread.threadId, run.runId.value()));
                }
            }
            return Optional.<LaunchedRun>empty();
        });
    }

    private ThreadRecord ensureBackgroundThread(String ownerId, String threadId, boolean createIfMissing) {
        ThreadRecord thread = threads.get(threadId);
        if (thread == null) {
            if (!createIfMissing) {
                throw new IllegalStateException("Scheduled Run thread is unavailable");
            }
            ThreadRecord created = new ThreadRecord(threadId, Map.of(), Instant.now(), ownerId);
            ThreadRecord prior = threads.putIfAbsent(threadId, created);
            thread = prior != null ? prior : created;
            if (prior == null) {
                try {
                    persistThread(created);
                } catch (IOException persistenceFailure) {
                    ADMISSION_LOG.log(System.Logger.Level.WARNING,
                            "scheduled Run thread could not be persisted");
                }
            }
        }
        if (!ownerId.equals(thread.ownerId)) {
            throw new IllegalStateException("Scheduled Run thread belongs to another owner");
        }
        return thread;
    }

    private List<? extends JavaTool<?, ?>> nonInteractiveTools(ThreadRecord thread, AgentPromptContext agentPrompt) {
        // Frozen context.non_interactive: the lead tool set excludes ask_clarification. The frozen
        // prompt composer switches to non-interactive text purely from tool presence.
        return toolsForThread(thread, agentPrompt).stream()
                .filter(tool -> !"ask_clarification".equals(tool.spec().name().value()))
                .toList();
    }

    private HaifaRunBridge newBackgroundBridge(HaifaRunBridge.ModelBinding binding, ThreadRecord thread,
            List<? extends JavaTool<?, ?>> tools, AgentPromptContext agentPrompt) {
        bridgeBuilds.incrementAndGet();
        return AccessOwner.call(thread.ownerId, () -> new HaifaRunBridge(binding, tools, foundation,
                McpRunTools.Projection.empty(), null, agentPrompt,
                io.haifa.agent.superharness.server.config.SubagentRunConfiguration.disabled(),
                childRunCapacity, false, executionExecutorFactory, hostShellConfig(), null,
                parentWallTimeMillisForTests, approvalMode()));
    }

    static ScheduledRunStatus scheduledTerminal(AgentRunStatus status) {
        return switch (status) {
            case COMPLETED -> ScheduledRunStatus.SUCCESS;
            case FAILED, TIMEOUT -> ScheduledRunStatus.FAILED;
            case CANCELLED -> ScheduledRunStatus.INTERRUPTED;
            default -> ScheduledRunStatus.INTERRUPTED;
        };
    }

    static String scheduledTerminalError(AgentRunStatus status) {
        return switch (status) {
            case FAILED -> "run failed";
            case TIMEOUT -> "run timed out";
            case CANCELLED -> "run interrupted";
            default -> null;
        };
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
            // A thread retired by DELETE must not be resurrected by a late mutation that still holds
            // its record (title finalizer, terminal stream cleanup, background admission).
            if (threads.get(thread.threadId) != thread) {
                return;
            }
            threadCatalog.save(new PersistentThreadCatalog.Entry(
                    thread.threadId,
                    thread.metadata,
                    thread.createdAt,
                    thread.updatedAt,
                    Optional.ofNullable(thread.persistedSessionId),
                    Optional.ofNullable(thread.modelBindingIdentity),
                    thread.interactionDisplays,
                    thread.clarificationDisplays,
                    thread.ownerId));
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
        updated = new ArrayList<>(
                PersistentThreadCatalog.retainClarificationsWithinCapacity(updated, liveRunIds(thread)));
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

    private static boolean clarificationAlreadyAnswered(ThreadRecord thread, String requestId) {
        for (var display : thread.clarificationDisplays) {
            if (requestId.equals(display.requestId()) && display.answeredKind() != null) {
                return true;
            }
        }
        return false;
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

    AdmittedTurn admitJoin(String threadId, String runId, String lastEventId) throws ConversationException {
        ThreadRecord thread = threads.get(threadId);
        if (thread != null && !AccessOwner.current().equals(thread.ownerId)) {
            throw ConversationException.notFound("Run " + runId + " not found");
        }
        RunRecord run = thread == null ? null : findOrRestoreRun(thread, runId).orElse(null);
        if (run == null) {
            throw ConversationException.notFound("Run " + runId + " not found");
        }
        ProjectionCursor cursor;
        try {
            cursor = parseSseCursor(runId, lastEventId);
            synchronizeAllAvailable(run);
            run.projector.replayAfter(cursor);
        } catch (IllegalArgumentException | EventProjectionException invalid) {
            throw ConversationException.conflict(invalid.getMessage());
        }
        return new AdmittedTurn(thread, run, cursor, false, "continue", false, true, false);
    }

    /**
     * DeerFlow moves a Run from pending to running before it publishes {@code metadata}, and the
     * frozen UI reads the Run as soon as it sees that frame. The durable SDK admits a Run as
     * pending, so wait (bounded) for it to leave admission before the first frame is written.
     */
    void awaitRunStart(RunRecord run) {
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

    public void observeTurn(AdmittedTurn turn, RunEventSink sink) throws IOException {
        ThreadRecord thread = turn.thread;
        RunRecord run = turn.run;
        ProjectionCursor initialCursor = turn.cursor;
        boolean creator = turn.creator;
        String onDisconnect = turn.onDisconnect;
        boolean includeMetadata = turn.includeMetadata;
        boolean reemitPendingCard = turn.reemitPendingCard;
        Object observer = new Object();
        // Register before the first write/loop so Steer/join synchronizeAllAvailable cannot slide
        // the shared projector past this cursor while metadata and the opening frames are still
        // being flushed. observerLeft in finally drops the lease when the connection ends.
        run.observerDelivered(observer, initialCursor.sequence());
        // Each SSE connection owns its own transient live-values producer buffer and output
        // subscription; reconnects replay from BEFORE_FIRST on a fresh buffer, independently of
        // every other observer, and the connection closes its subscription on stream end.
        LiveParentValuesProjector connectionLiveValues = new LiveParentValuesProjector(run.runId.value());
        try {
            try {
                try (DeerFlowSubtaskStream subtasks = run.streamModes.contains("custom")
                        ? new DeerFlowSubtaskStream(run.bridge, run.runId) : null) {
                    if (includeMetadata) {
                        sink.event(
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
                            sink.event(
                                    "messages",
                                    List.of(pendingHumanMessage(run), Map.of("langgraph_node", "agent")),
                                    null);
                        }
                    }
                    ProjectionCursor delivered = initialCursor;
                    boolean liveValuesActive = run.streamModes.contains("messages-tuple")
                            && !run.bridge.snapshot(run.runId).status().isTerminal()
                            && connectionLiveValues.open(run.bridge);
                    List<Map<String, Object>> lastLiveValues = null;
                    // Every SSE connection owns its own Tool-message dedup state: two observers
                    // of the same Run are two separate consumers, each reading its own frames
                    // (connection-local map, not Run state).
                    Map<String, String> projectedToolMessages = new LinkedHashMap<>();
                    long nextHeartbeat = System.nanoTime() + java.time.Duration.ofMillis(100).toNanos();
                    long terminalWithoutEventSince = 0;
                    long childTerminalWaitSince = 0;
                    boolean childTerminalDrainExpired = false;
                    while (true) {
                        ReplayResult replay = run.projector.replayAfter(delivered);
                        if (replay instanceof ReplayResult.Gap gap) {
                            writeReplayGap(sink, run, gap.gap(), gap.authoritativeView(), delivered);
                            return;
                        }
                        ReplayResult.Events events = (ReplayResult.Events) replay;
                        for (ProjectedRunEvent event : events.events()) {
                            if (subtasks != null && !childTerminalDrainExpired && event.terminalKind().terminal()
                                    && !subtasks.drain(true, frame -> sink.event("custom", frame, null))) {
                                if (childTerminalWaitSince == 0) childTerminalWaitSince = System.nanoTime();
                                if (System.nanoTime() - childTerminalWaitSince >= TERMINAL_EVENT_SETTLE_TIMEOUT.toNanos()) {
                                    writeChildDrainDiagnostic(sink, run);
                                    childTerminalDrainExpired = true;
                                } else {
                                    // Retain this Parent cursor while the bounded drain can still finish.
                                    break;
                                }
                            }
                            delivered = event.cursor();
                            if (writeProjectedEvent(sink, thread, run, event, liveValuesActive,
                                    connectionLiveValues, projectedToolMessages)) {
                                return;
                            }
                        }
                        if (subtasks != null && !childTerminalDrainExpired) {
                            subtasks.drain(false, frame -> sink.event("custom", frame, null));
                        }
                        run.observerDelivered(observer, delivered.sequence());
                        Optional<AppendResult.Gap> sourceGap = synchronizeOnePage(run);
                        observeClarificationBoundary(thread, run);
                        if (sourceGap.isPresent()) {
                            AppendResult.Gap gap = sourceGap.orElseThrow();
                            writeReplayGap(sink, run, gap.gap(), gap.authoritativeView(), delivered);
                            return;
                        }
                        if (liveValuesActive) {
                            List<Map<String, Object>> frame = liveValuesMessages(thread, run, connectionLiveValues);
                            if (!frame.equals(lastLiveValues)) {
                                sink.event("values", Map.of("messages", frame), null);
                                lastLiveValues = frame;
                            }
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
                                if (writePendingInteractionCard(sink,
                                        thread,
                                        run,
                                        pending.orElseThrow().requestId().value(),
                                        sseCursor(delivered), projectedToolMessages)) {
                                    return;
                                }
                            }
                        }
                        if (run.projector.head().equals(delivered)
                                && run.bridge.snapshot(run.runId).status().isTerminal()) {
                            if (!creator && delivered.sequence() == initialCursor.sequence()) {
                                if (subtasks != null && !childTerminalDrainExpired
                                        && !subtasks.drain(true, frame -> sink.event("custom", frame, null))) {
                                    if (childTerminalWaitSince == 0) childTerminalWaitSince = System.nanoTime();
                                    if (System.nanoTime() - childTerminalWaitSince >= TERMINAL_EVENT_SETTLE_TIMEOUT.toNanos()) {
                                        writeChildDrainDiagnostic(sink, run);
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
                                sink.event("end", null, null);
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
                            sink.event(
                                    "error",
                                    Map.of("message", "Durable terminal event is unavailable"),
                                    null);
                            return;
                        }
                        if (System.nanoTime() >= nextHeartbeat) {
                            sink.heartbeat();
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
            } catch (RuntimeException failure) {
                // HTTP 200/SSE is already committed on the adapter side. Do not attempt JSON or
                // expose SDK/provider text.
                sink.event("error", Map.of("message", "Run stream is unavailable"), null);
            }
        } catch (IOException disconnected) {
            if (creator && "cancel".equals(onDisconnect)) {
                run.bridge.cancel(run.runId);
            }
        } finally {
            run.observerLeft(observer);
            connectionLiveValues.close();
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
    private void writeChildDrainDiagnostic(RunEventSink sink, RunRecord run) throws IOException {
        if (run.bridge.children(run.runId).stream().allMatch(child -> child.status().isTerminal())) {
            sink.event("error", Map.of("code", "child_terminal_projection_unavailable",
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
                // Prefer leaving unread durable events for attached streams over sliding the
                // retention window past their delivered cursor after the catch-up wait.
                if (wouldOutrunLiveObserver(run)) {
                    return;
                }
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
     * Callers must not append after this returns while {@link #wouldOutrunLiveObserver} is still
     * true; the deadline only bounds the wait, not permission to drop undelivered events.
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
            RunEventSink sink, ThreadRecord thread, RunRecord run, ProjectedRunEvent event,
            boolean liveValuesActive, LiveParentValuesProjector connectionLiveValues,
            Map<String, String> projectedToolMessages)
            throws IOException {
        String cursor = sseCursor(event.cursor());
        if (event.interaction().isPresent()) {
            var projected = event.interaction().orElseThrow();
            if (projected.state() != InteractionState.PENDING) {
                return false;
            }
            return writePendingInteractionCard(sink, thread, run, projected.requestId(), cursor, projectedToolMessages);
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
                    sink.event("error", Map.of(
                            "code", "durable_assistant_turn_unavailable",
                            "message", "The durable assistant turn for this event is unavailable"), cursor);
                    return true;
                }
                if (!supersededOutput.orElseThrow().isEmpty()) {
                    // Retire the committed provisional row before its stable legacy tuple;
                    // retained/reconnected messages-mode consumers still receive the turn.
                    if (liveValuesActive) {
                        writeLiveValuesFrame(sink, thread, run, connectionLiveValues, false);
                    }
                    writeMessageDelta(sink, run, supersededOutput.orElseThrow(), cursor, ordinal);
                    if (liveValuesActive) {
                        writeLiveValuesFrame(sink, thread, run, connectionLiveValues, false);
                    }
                }
            }
            return false;
        }
        if (event.source().payload() instanceof RunEventPayloads.ToolLifecycle
                || event.source().payload() instanceof RunEventPayloads.ResourceAvailable
                || event.source().payload() instanceof RunEventPayloads.DeliveryLifecycle) {
            if (event.source().payload() instanceof RunEventPayloads.ToolLifecycle lifecycle
                    && run.bridge.hasTools()) {
                writeToolLifecycle(sink, run, lifecycle.toolCallId(), cursor, projectedToolMessages);
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
            sink.event(
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
        if (liveValuesActive) {
            // Replace provisional rows before publishing terminal legacy tuples, whose stable
            // IDs match the durable values snapshot consumed by the frozen stream reducer.
            writeLiveValuesFrame(sink, thread, run, null, true);
        }
        if (run.bridge.hasTools() && run.streamModes.contains("messages-tuple")) {
            writeAllToolMessages(sink, run, projectedToolMessages);
            List<String> artifacts = DeerFlowToolTranscriptProjection.artifactPaths(
                    run.bridge.toolCalls(run.runId));
            if (!artifacts.isEmpty() && run.streamModes.contains("updates")) {
                sink.event("updates", Map.of("__artifacts__", Map.of("artifacts", artifacts)), null);
            }
        }
        if (event.terminalKind() == RunTerminalKind.COMPLETED
                && terminalSnapshot.output().isPresent()
                && run.streamModes.contains("messages-tuple")
                && !runHasClarificationBoundary(run)) {
            int finalAssistantOrdinal = run.deferredEventSequences.size();
            writeMessageDelta(sink, run, terminalSnapshot.output().orElseThrow(), cursor, finalAssistantOrdinal);
        }
        if (event.terminalKind() == RunTerminalKind.FAILED) {
            String message = terminalSnapshot
                    .error()
                    .map(value -> value.message())
                    .orElse("Run failed");
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("message", message);
            terminalSnapshot.error().ifPresent(error -> {
                failure.put("code", error.code().wireCode());
                error.optionalDiagnosticId().ifPresent(diagnosticId -> failure.put("diagnostic_id", diagnosticId));
            });
            failure.put("run_id", run.runId.value());
            sink.event("error", failure, null);
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
                maybePublishTitle(sink, thread, run);
            }
        }
        if (liveValuesActive) {
            // Legacy tuples register their own frozen SDK indexes after a values snapshot.
            // Restore the canonical rows after those tuples, before onFinish uses the state.
            writeLiveValuesFrame(sink, thread, run, null, true);
        }
        sink.event("end", null, cursor);
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
            RunEventSink sink, ThreadRecord thread, RunRecord run, String projectedRequestId, String cursor,
            Map<String, String> projectedToolMessages)
            throws IOException {
        Map<String, Object> requestMessage;
        synchronized (thread) {
            synchronized (run) {
                Optional<InteractionView> pending = run.bridge.pendingInteraction(run.runId);
                if (pending.isEmpty()
                        || !pending.orElseThrow().requestId().value().equals(projectedRequestId)) {
                    sink.event(
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
                    sink.event("error", Map.of(
                            "code", "interaction_projection_not_persisted",
                            "request_id", projectedRequestId,
                            "message", "Interaction card could not be saved"), cursor);
                    return true;
                } catch (IllegalArgumentException unsupported) {
                    sink.event("error", Map.of(
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
        writeToolRequestIfAvailable(sink, run, null, projectedToolMessages);
        sink.event(
                "messages",
                List.of(requestMessage, Map.of("langgraph_node", "tools")),
                cursor);
        // Frozen HumanInputCard disables itself while useStream is loading. End this
        // transport while the SDK-owned Run remains waiting; the hidden reply reopens the
        // same Run and streams its continuation.
        sink.event("end", null, null);
        return true;
    }

    private void writeToolLifecycle(RunEventSink sink, RunRecord run, String toolCallId, String cursor,
            Map<String, String> projectedToolMessages)
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
            sink.event(
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
        writeToolMessage(sink, run, DeerFlowToolTranscriptProjection.requestMessage(round), null, projectedToolMessages);
        writeAvailableRoundResults(sink, run, round, cursor, projectedToolMessages);
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

    private void writeToolRequestIfAvailable(RunEventSink sink, RunRecord run, String cursor,
            Map<String, String> projectedToolMessages) throws IOException {
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
            writeToolMessage(sink, run, DeerFlowToolTranscriptProjection.requestMessage(round), cursor, projectedToolMessages);
        }
    }

    private String interactionTargetReference(RunRecord run) {
        return run.bridge.pendingInteraction(run.runId)
                .map(interaction -> interaction.target().reference())
                .orElse("");
    }

    private void writeAllToolMessages(RunEventSink sink, RunRecord run,
            Map<String, String> projectedToolMessages) throws IOException {
        List<ToolCallView> calls = DeerFlowToolTranscriptProjection.upToClarificationBoundary(
                run.bridge.toolCalls(run.runId));
        for (List<ToolCallView> round : toolCallRounds(run.bridge, run.runId, calls)) {
            if (round.isEmpty()) continue;
            writeToolMessage(sink, run, DeerFlowToolTranscriptProjection.requestMessage(round), null, projectedToolMessages);
            writeAvailableRoundResults(sink, run, round, null, projectedToolMessages);
        }
    }

    private void writeAvailableRoundResults(RunEventSink sink, RunRecord run, List<ToolCallView> round,
            String cursor, Map<String, String> projectedToolMessages) throws IOException {
        // Live and replay snapshots may observe parallel completions at different moments.
        // Preserve the authoritative request order on both paths: a later result waits only
        // for earlier calls in this round, while native Child status events remain independent.
        for (ToolCallView call : round) {
            Optional<Map<String, Object>> result = DeerFlowToolTranscriptProjection.resultMessage(call);
            if (result.isEmpty()) return;
            writeToolMessage(sink, run, result.orElseThrow(), cursor, projectedToolMessages);
        }
    }

    private void writeToolMessage(
            RunEventSink sink, RunRecord run, Map<String, Object> message, String cursor,
            Map<String, String> projectedToolMessages) throws IOException {
        String messageId = (String) message.get("id");
        // A round request can grow as more calls of the same round become known, so the same message
        // id is written again only when its tool_calls list changes (the frozen SDK overwrites a
        // non-chunked AI message by id). Every other message is still written once.
        String signature = DeerFlowToolTranscriptProjection.messageSignature(message);
        String previous = projectedToolMessages.put(messageId, signature);
        if (signature.equals(previous)) return;
        sink.event("messages", List.of(message, Map.of("langgraph_node", "tools")), cursor);
        if ("tool".equals(message.get("type")) && "write_todos".equals(message.get("name"))) {
            maybePublishTodos(sink, run);
        }
    }

    private void maybePublishTodos(RunEventSink sink, RunRecord run) throws IOException {
        if (!run.streamModes.contains("updates")) {
            return;
        }
        Optional<AgentPlanView> plan = run.bridge.plan(run.runId);
        if (plan.isPresent()) {
            Optional<List<Map<String, Object>>> todos =
                    PlanAuthoringResources.projectTodos(plan.get(), run.threadId, run.runId.value());
            if (todos.isPresent()) {
                sink.event("updates", Map.of("tools", Map.of("todos", todos.get())), null);
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

    private void maybePublishTitle(RunEventSink sink, ThreadRecord thread, RunRecord run)
            throws IOException {
        String title = thread.title();
        if (title == null) {
            title = fallbackTitle(run.humanMessage);
        }
        sink.event("updates", Map.of("__title__", Map.of("title", title)), null);
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

    /**
     * Bounded server-side record of a Run admission failure. The frozen response stays the generic
     * {@code Run could not start}, so this line is the only place the failure survives: an admission
     * failure swallowed here is undiagnosable in production (a Run that never started has no durable
     * record). It carries the frozen route, the exception class and the class chain of its causes:
     * admission fails before the request is validated, so a message may echo client text or a
     * provider body and is never recorded. The client-controlled thread id is excluded for the same
     * reason - createThread permits CR/LF, which would allow log injection.
     */
    static String admissionFailureMessage(RuntimeException failure) {
        StringBuilder message = new StringBuilder("Run admission failure on ")
                .append(RUN_STREAM_ROUTE)
                .append(": ")
                .append(failure.getClass().getName());
        String causes = causeClasses(failure);
        if (causes != null) {
            message.append(" (").append(causes).append(')');
        }
        return message.toString();
    }

    /** Class names of the cause chain, bounded; an exception message is never recorded. */
    private static String causeClasses(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        Throwable cause = failure.getCause();
        for (int depth = 0; cause != null && depth < ADMISSION_CAUSE_LIMIT; depth++, cause = cause.getCause()) {
            if (chain.length() > 0) {
                chain.append(" <- ");
            }
            chain.append(cause.getClass().getName());
        }
        return chain.length() == 0 ? null : chain.toString();
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
            RunEventSink sink,
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
        sink.event("gap", payload, null);
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

    /**
     * Active Run id for {@code threadId}, if any. Used by the Feishu channel for Stop/Steer without
     * reconstructing HTTP/SSE.
     */
    public Optional<String> activeRunId(String threadId) {
        ThreadRecord thread = threads.get(threadId);
        if (thread == null) {
            return Optional.empty();
        }
        String active = thread.activeRunId;
        if (active == null || active.isBlank()) {
            return Optional.empty();
        }
        RunRecord run = runs.get(active);
        if (run == null) {
            run = findOrRestoreRun(thread, active).orElse(null);
        }
        if (run == null || run.bridge.snapshot(run.runId).status().isTerminal()) {
            return Optional.empty();
        }
        return Optional.of(active);
    }

    /** Cancels a Run; returns normally on 202, throws a bounded rejection otherwise. */
    public void cancelRun(String threadId, String runId, String rawQuery) throws ConversationException {
        String action = queryValue(rawQuery, "action").orElse("interrupt");
        String wait = queryValue(rawQuery, "wait").orElse("false");
        // The frozen JS SDK serializes boolean false as "0" while direct DeerFlow
        // callers commonly use "false"; FastAPI's bool query accepts both.
        if (!action.equals("interrupt") || !Set.of("false", "0").contains(wait)) {
            throw ConversationException.invalid("Only wait=false/0&action=interrupt is implemented");
        }
        ThreadRecord thread = threads.get(threadId);
        RunRecord run = thread == null ? null : findOrRestoreRun(thread, runId).orElse(null);
        if (run == null) {
            throw ConversationException.notFound("Run " + runId + " not found");
        }
        if (!run.bridge.cancel(run.runId)) {
            throw ConversationException.conflict(
                    "Run " + runId + " is not cancellable (status: "
                            + deerFlowStatus(run.bridge.snapshot(run.runId).status()) + ")");
        }
    }

    List<Map<String, Object>> getSubtaskEvents(String threadId, String runId, String rawQuery) throws ConversationException {
        String taskId;
        int limit;
        long after;
        try {
            taskId = queryValue(rawQuery, "task_id").filter(value -> !value.isBlank())
                    .orElseThrow(() -> new IllegalArgumentException("task_id is required"));
            String types = queryValue(rawQuery, "event_types").orElse("subagent.step");
            if (java.util.Arrays.stream(types.split(",", -1)).map(String::trim)
                    .anyMatch(value -> !"subagent.step".equals(value))) {
                throw new IllegalArgumentException("Only subagent.step event_types are supported");
            }
            limit = (int) subtaskQueryNumber(queryValue(rawQuery, "limit").orElse("500"), 1, 500);
            after = subtaskQueryNumber(queryValue(rawQuery, "after_seq").orElse("0"), 0,
                    DeerFlowSubtaskProjection.MAX_SAFE_INTEGER);
        } catch (IllegalArgumentException invalid) {
            throw ConversationException.invalid(invalid.getMessage());
        }
        ThreadRecord thread = threads.get(threadId);
        RunRecord run = thread == null ? null : findOrRestoreRun(thread, runId).orElse(null);
        if (run == null) {
            throw ConversationException.notFound("Run not found");
        }
        // Resolve only through the caller-visible Parent's actual children and exact Runtime Tool ID.
        var children = run.bridge.children(run.runId);
        var taskIds = run.bridge.childTaskIds(run.runId, children);
        var child = children.stream()
                .filter(candidate -> candidate.parentRunId().equals(run.runId))
                .filter(candidate -> taskId.equals(taskIds.get(candidate.runId())))
                .findFirst();
        if (child.isEmpty()) {
            return List.of();
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
        return events;
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

    Map<String, Object> getRun(String threadId, String runId) throws ConversationException {
        ThreadRecord thread = threads.get(threadId);
        RunRecord run = thread == null ? null : findOrRestoreRun(thread, runId).orElse(null);
        if (run == null) {
            throw ConversationException.notFound("Run " + runId + " not found");
        }
        return runResponse(run);
    }

    List<Map<String, Object>> listRuns(String threadId) throws ConversationException {
        ThreadRecord thread = threads.get(threadId);
        if (thread == null) {
            throw ConversationException.notFound("Thread not found");
        }
        return durableRuns(thread).stream()
                .map(run -> runResponse(run, false))
                .toList();
    }

    /** The frozen UI treats a JSON null response as unavailable Usage, without displaying zero. */
    Object getThreadTokenUsage(String threadId, String rawQuery) throws ConversationException {
        ThreadRecord thread = threads.get(threadId);
        if (thread == null) {
            throw ConversationException.notFound("Thread not found");
        }
        if (queryValue(rawQuery, "include_active").filter(value -> !Set.of("false", "0").contains(value)).isPresent()) {
            throw ConversationException.invalid("Active Run Usage is outside this local profile");
        }
        long input = 0;
        long output = 0;
        int totalRuns = 0;
        int remainingEventPages = THREAD_USAGE_EVENT_PAGE_BUDGET;
        Map<String, long[]> byModel = new LinkedHashMap<>();
        List<RunRecord> threadRuns = durableRuns(thread);
        if (threadRuns.size() >= RUN_LIST_LIMIT) {
            return null; // A capped history cannot prove a complete thread total.
        }
        for (RunRecord run : threadRuns) {
            AgentRunSnapshot snapshot = run.bridge.snapshot(run.runId);
            if (!snapshot.status().isTerminal()) continue;
            if (remainingEventPages == 0) {
                return null;
            }
            DiagnosticScan scan = runDiagnosticFacts(run, snapshot, remainingEventPages, false);
            remainingEventPages -= scan.pagesRead();
            RunDiagnosticProjection.Facts facts = scan.facts();
            if (!facts.usageKnown()) {
                return null;
            }
            Map<String, long[]> runModels = new LinkedHashMap<>();
            facts.usageByModel().forEach((model, values) -> runModels.put(model, values.clone()));
            int[] childPageBudget = {remainingEventPages};
            Optional<long[]> childTotals = childUsage(run, runModels, childPageBudget);
            remainingEventPages = childPageBudget[0];
            if (childTotals.isEmpty()) {
                return null;
            }
            var nextInput = sumUsageTokens(input, snapshot.usage().inputTokens(), childTotals.orElseThrow()[0]);
            var nextOutput = sumUsageTokens(output, snapshot.usage().outputTokens(), childTotals.orElseThrow()[1]);
            if (nextInput.isEmpty() || nextOutput.isEmpty()
                    || sumUsageTokens(nextInput.orElseThrow(), nextOutput.orElseThrow()).isEmpty()) {
                return null;
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
            return null;
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
        response.put("context_usage", contextUsage(thread, threadRuns));
        return response;
    }

    private Map<String, Object> contextUsage(ThreadRecord thread, List<RunRecord> threadRuns) {
        if (threadRuns.isEmpty()) return null;
        RunRecord latest = threadRuns.getFirst();
        int maxContextTokens = latest.bridge.modelContextWindow();
        if (maxContextTokens <= 0) return null;
        RunUsageFacts facts = usageFacts(thread, latest, latest.runId, isTerminalRun(thread, latest.runId, latest));
        long tokenCount = facts.complete() ? Math.max(0L, facts.lastInputTokens()) : 0L;
        double percentage = Math.round((double) tokenCount / maxContextTokens * 1000.0) / 10.0;
        return Map.of(
                "token_count", tokenCount,
                "max_context_tokens", (long) maxContextTokens,
                "percentage", Math.max(0.0, percentage));
    }

    private RunUsageFacts usageFactsFor(
            ThreadRecord thread, AgentRunId runId, RunRecord cached, Map<String, RunUsageFacts> requestCache) {
        return requestCache.computeIfAbsent(
                runId.value(),
                ignored -> usageFacts(thread, cached, runId, isTerminalRun(thread, runId, cached)));
    }

    private Map<String, Map<String, Object>> usageForTurn(
            ThreadRecord thread, DisplayTurn turn, Map<String, RunRecord> cached,
            Map<String, RunUsageFacts> requestCache) {
        if (turn.role() != io.haifa.agent.core.message.MessageRole.ASSISTANT
                || turn.runId().isEmpty()) return Map.of();
        AgentRunId runId = turn.runId().orElseThrow();
        return usageFactsFor(thread, runId, cached.get(runId.value()), requestCache).usageByMessageId();
    }

    private RunUsageFacts usageFacts(ThreadRecord thread, RunRecord cached, AgentRunId runId, boolean terminal) {
        RunUsageFacts known = runUsageFacts.get(runId.value());
        if (known != null) return known;
        HaifaRunBridge bridge = cached == null ? bridgeForPersistedThread(thread) : cached.bridge;
        if (!terminal) {
            return new RunUsageFacts(Map.of(), Map.of(), Map.of(), lastModelInputTokens(bridge, runId), true);
        }
        Map<String, String> modelCallByMessage = new LinkedHashMap<>();
        Map<String, String> messageByToolCall = new LinkedHashMap<>();
        RunMessageCursor messageCursor = RunMessageCursor.beforeFirst(runId);
        boolean complete = true;
        int pages = 0;
        while (true) {
            RunMessagePage page = bridge.messages(runId, messageCursor, MESSAGE_ROUND_SCAN_PAGE_SIZE);
            for (RunMessageView message : page.items()) {
                if (!"ASSISTANT".equals(message.role())) continue;
                message.modelCallId().ifPresent(modelCallId -> {
                    modelCallByMessage.put(message.messageId(), modelCallId);
                    message.toolCalls().forEach(call -> messageByToolCall.put(call.id().value(), message.messageId()));
                });
            }
            if (!page.hasMore()) break;
            if (++pages >= MAXIMUM_TURN_SCAN_PAGES) {
                complete = false;
                break;
            }
            messageCursor = page.nextCursor();
        }
        Map<String, Map<String, Object>> usageByModelCall = new LinkedHashMap<>();
        long lastInputTokens = -1L;
        long lastSequence = -1L;
        RunEventCursor eventCursor = bridge.beforeFirstEvent(runId);
        pages = 0;
        while (true) {
            RunEventPage page = bridge.events(runId, eventCursor, DIAGNOSTIC_EVENT_PAGE_SIZE);
            for (AgentRunEvent event : page.items()) {
                if (event.payload() instanceof RunEventPayloads.ModelLifecycle model
                        && "SUCCEEDED".equals(model.status())) {
                    usageByModelCall.putIfAbsent(
                            model.modelCallId(),
                            Map.of(
                                    "input_tokens", model.inputTokens(),
                                    "output_tokens", model.outputTokens(),
                                    "total_tokens", Math.addExact(model.inputTokens(), model.outputTokens())));
                    if (event.sequence() > lastSequence) {
                        lastSequence = event.sequence();
                        lastInputTokens = model.inputTokens();
                    }
                }
            }
            if (!page.hasMore()) break;
            if (++pages >= MAXIMUM_TURN_SCAN_PAGES) {
                complete = false;
                break;
            }
            eventCursor = page.nextCursor();
        }
        Map<String, Map<String, Object>> usageByMessage = new LinkedHashMap<>();
        modelCallByMessage.forEach((messageId, modelCallId) -> {
            Map<String, Object> usage = usageByModelCall.get(modelCallId);
            if (usage != null) usageByMessage.put(messageId, usage);
        });
        Map<String, Map<String, Object>> usageByToolCall = new LinkedHashMap<>();
        messageByToolCall.forEach((toolCallId, messageId) -> {
            Map<String, Object> usage = usageByMessage.get(messageId);
            if (usage != null) usageByToolCall.put(toolCallId, usage);
        });
        RunUsageFacts facts = new RunUsageFacts(
                Map.copyOf(usageByMessage),
                Map.copyOf(usageByToolCall),
                Map.copyOf(messageByToolCall),
                lastInputTokens,
                complete);
        if (terminal && complete) runUsageFacts.put(runId.value(), facts);
        return facts;
    }

    private long lastModelInputTokens(HaifaRunBridge bridge, AgentRunId runId) {
        long lastInputTokens = -1L;
        long lastSequence = -1L;
        RunEventCursor cursor = bridge.beforeFirstEvent(runId);
        int pages = 0;
        while (true) {
            RunEventPage page = bridge.events(runId, cursor, DIAGNOSTIC_EVENT_PAGE_SIZE);
            for (AgentRunEvent event : page.items()) {
                if (event.payload() instanceof RunEventPayloads.ModelLifecycle model
                        && "SUCCEEDED".equals(model.status())
                        && event.sequence() > lastSequence) {
                    lastSequence = event.sequence();
                    lastInputTokens = model.inputTokens();
                }
            }
            if (!page.hasMore()) return lastInputTokens;
            if (++pages >= MAXIMUM_TURN_SCAN_PAGES) return -1L;
            cursor = page.nextCursor();
        }
    }

    private record RunUsageFacts(
            Map<String, Map<String, Object>> usageByMessageId,
            Map<String, Map<String, Object>> usageByToolCallId,
            Map<String, String> messageIdByToolCallId,
            long lastInputTokens,
            boolean complete) {
        static final RunUsageFacts EMPTY = new RunUsageFacts(Map.of(), Map.of(), Map.of(), -1L, true);
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

    private Map<String, Object> threadStateResponse(ThreadRecord thread, int maxTurns) {
        List<ConversationTurn> turns = scanTurns(thread, maxTurns);
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

    private void writeLiveValuesFrame(
            RunEventSink sink, ThreadRecord thread, RunRecord run,
            LiveParentValuesProjector projector, boolean finalFlush) throws IOException {
        List<Map<String, Object>> messages = finalFlush
                ? buildLiveValuesDurableRows(thread, run)
                : liveValuesMessages(thread, run, projector);
        sink.event("values", Map.of("messages", messages), null);
    }

    /** Retire only associations represented in the same captured durable projection. */
    private List<Map<String, Object>> liveValuesMessages(
            ThreadRecord thread, RunRecord run, LiveParentValuesProjector projector) {
        List<Map<String, Object>> provisional = run.clarificationObserved
                ? List.of() : projector.liveRows();
        long sequence = run.projector.head().sequence();
        long revision = projector.durableRevision();
        if (projector.needsDurableSnapshot(sequence, revision, System.nanoTime())) {
            var snapshot = buildLiveValuesSnapshot(thread, run);
            projector.cacheDurableSnapshot(snapshot, sequence, revision, System.nanoTime());
        }
        var snapshot = projector.durableSnapshot();
        List<Map<String, Object>> messages = new ArrayList<>(snapshot.messages());
        Set<String> accepted = snapshot.visibleAcceptedCalls();
        for (String id : accepted) projector.retire(id);
        for (Map<String, Object> row : provisional) {
            if (!accepted.contains(row.get("id"))) messages.add(row);
        }
        return List.copyOf(messages);
    }

    private List<Map<String, Object>> buildLiveValuesDurableRows(ThreadRecord thread, RunRecord run) {
        return buildLiveValuesSnapshot(thread, run).messages();
    }

    private LiveParentValuesProjector.DurableSnapshot buildLiveValuesSnapshot(
            ThreadRecord thread, RunRecord run) {
        if (thread.persistedSessionId == null) {
            return new LiveParentValuesProjector.DurableSnapshot(List.of(pendingHumanMessage(run)), Set.of());
        }
        List<ConversationTurn> turns = scanTurns(thread, 200);
        Map<Long, Integer> ordinals = computeTurnOrdinals(thread);
        // Active values must use the same client human identity as the initial
        // live echo and messages/page; settled Runs still project durable IDs.
        List<Map<String, Object>> messages = overlaidMessages(thread, turns, ordinals, true);
        // This connection began while the Run was active. Its final canonical values must retain
        // the echoed optimistic human identity even after the SDK becomes terminal; switching to
        // the durable history id before `end` leaves two bubbles in the frozen values reducer.
        // Only the submitted human turn of this connection is re-keyed. History, terminal rejoin,
        // and any other Run's rows keep their normal identities.
        if (run.clientHumanMessageId != null && !run.clientHumanMessageId.isBlank()) {
            String durableHumanId = wireMessageId(run.runId.value(), "human", 0);
            String liveHumanId = run.clientHumanMessageId + INJECTED_USER_MESSAGE_ID_SUFFIX;
            messages = messages.stream().map(row -> {
                if (!"human".equals(row.get("type")) || !durableHumanId.equals(row.get("id"))) return row;
                Map<String, Object> liveRow = new LinkedHashMap<>(row);
                liveRow.put("id", liveHumanId);
                return liveRow;
            }).toList();
        }
        Set<String> wireIds = new HashSet<>();
        Set<String> visibleToolIds = new HashSet<>();
        for (Map<String, Object> message : messages) {
            Object id = message.get("id");
            if (id instanceof String value) wireIds.add(value);
            Object calls = message.get("tool_calls");
            if (calls instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> call && call.get("id") instanceof String value) {
                        visibleToolIds.add(value);
                    }
                }
            }
        }
        Set<String> visibleNativeMessages = new HashSet<>();
        for (ConversationTurn turn : turns) {
            if (turn.role() != io.haifa.agent.core.message.MessageRole.ASSISTANT) continue;
            String id = (String) messageForTurn(thread, turn, ordinals.getOrDefault(turn.sequence(), 0), true).get("id");
            if (wireIds.contains(id)) visibleNativeMessages.add(turn.messageId());
        }
        Set<String> accepted = new HashSet<>();
        RunMessageCursor cursor = RunMessageCursor.beforeFirst(run.runId);
        int pages = 0;
        while (true) {
            RunMessagePage page = run.bridge.messages(run.runId, cursor, MESSAGE_ROUND_SCAN_PAGE_SIZE);
            for (RunMessageView message : page.items()) {
                if (!"ASSISTANT".equals(message.role())) continue;
                List<String> toolIds = message.toolCalls().stream().map(call -> call.id().value()).toList();
                if (LiveParentValuesProjector.acceptedMessageIsVisible(
                        message.messageId(), toolIds, visibleNativeMessages, visibleToolIds)) {
                    message.modelCallId().ifPresent(accepted::add);
                }
            }
            if (!page.hasMore()) break;
            requireAnotherScanPage(++pages);
            cursor = page.nextCursor();
        }
        return new LiveParentValuesProjector.DurableSnapshot(
                messages.isEmpty() ? List.of(pendingHumanMessage(run)) : messages, accepted);
    }

    private Map<Long, Integer> computeTurnOrdinals(ThreadRecord thread) {
        if (thread.persistedSessionId == null) return Map.of();
        HaifaRunBridge bridge = bridgeForPersistedThread(thread);
        // Wire IDs are per-Run ordinals, not ordinals within the requested page or retained
        // state window. Scan from the first durable turn under the existing 100-page cap.
        Map<String, Integer> counts = new HashMap<>();
        TurnIndex ordinals = new TurnIndex();
        Set<String> clarificationRuns = clarificationRunIds(thread);
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
                if (!isPostClarificationAnswer(turn, clarificationRuns)) ordinals.turns.add(DisplayTurn.position(turn));
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
        return messageForTurn(thread, turn, ordinal, liveOverlay, Map.of());
    }

    private Map<String, Object> messageForTurn(
            ThreadRecord thread, ConversationTurn turn, int ordinal, boolean liveOverlay,
            Map<String, Map<String, Object>> usageByMessageId) {
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
        if (!human) {
            Map<String, Object> usage = usageByMessageId.get(turn.messageId());
            if (usage != null) projected.put("additional_kwargs", Map.of("usage_metadata", usage));
        }
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
        if (liveOverlay && ordinal == 0) {
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
        Map<String, RunUsageFacts> usageFactsByRun = new HashMap<>();
        Set<String> visibleAssistantMessageIds = turns.stream()
                .filter(turn -> turn.role() == io.haifa.agent.core.message.MessageRole.ASSISTANT)
                .map(ConversationTurn::messageId)
                .collect(java.util.stream.Collectors.toSet());
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ConversationTurn turn : turns) {
            int ordinal = ordinals.getOrDefault(turn.sequence(), 0);
            RunUsageFacts turnUsage = turn.role() == io.haifa.agent.core.message.MessageRole.ASSISTANT
                            && turn.runId().isPresent()
                    ? usageFactsFor(thread, turn.runId().orElseThrow(), cached.get(turn.runId().orElseThrow().value()),
                            usageFactsByRun)
                    : RunUsageFacts.EMPTY;
            messages.add(messageForTurn(thread, turn, ordinal, includeInFlight, turnUsage.usageByMessageId()));
            if (turn.role() == io.haifa.agent.core.message.MessageRole.USER
                    && turn.runId().isPresent()
                    && supportProjectedRuns.add(turn.runId().orElseThrow().value())) {
                AgentRunId runId = turn.runId().orElseThrow();
                RunRecord run = cached.get(runId.value());
                RunUsageFacts supportUsage = usageFactsFor(thread, runId, run, usageFactsByRun);
                addRunSupportMessages(
                        thread, runId, run, messages, visibleAssistantMessageIds, supportUsage);
            }
        }
        if (includeInFlight) {
            for (RunRecord run : cached.values()) {
                if (!isInFlightWithoutDurableHuman(run, durableHumanRuns)) continue;
                messages.add(pendingHumanMessage(run));
                if (supportProjectedRuns.add(run.runId.value())) {
                    RunUsageFacts supportUsage = usageFactsFor(thread, run.runId, run, usageFactsByRun);
                    addRunSupportMessages(
                            thread, run.runId, run, messages, visibleAssistantMessageIds, supportUsage);
                }
            }
        }
        // Reuse the caller's ordinal scan. Generate identity/sequence pairs once for
        // this response, without retaining text or materializing old Tool result bodies.
        Map<Object, Long> positions;
        if (ordinals instanceof TurnIndex index) {
            boolean evictedIdentity = index.reusedPrefix != null && !containsWantedPrefix(index.reusedPrefix, messages);
            if (index.positions == null || evictedIdentity) {
                FeedRows output = new FeedRows(true);
                output.prefix = evictedIdentity ? null : usablePrefix(thread, index.turns, messages);
                index.reusedPrefix = output.prefix;
                projectMessageRows(thread, index.turns, ordinals, output);
                index.positions = output.positions;
            }
            positions = index.positions;
        } else {
            FeedRows output = new FeedRows(true);
            projectMessageRows(thread, turns.stream().map(DisplayTurn::position).toList(), ordinals, output);
            positions = output.positions;
        }
        for (Map<String, Object> message : messages) {
            Long sequence = positions.get(message.get("id"));
            if (sequence != null) stampMessagePosition(message, sequence);
        }
        applyLatestRunFailure(thread, messages);
        // Pending Tool requests have no page row yet. Keep the active Run's established
        // request/card order together instead of moving its unpositioned rows to the tail.
        Map<String, Long> activeAnchors = new HashMap<>();
        for (Map<String, Object> message : messages) {
            if (!(message.get("run_id") instanceof String runId)
                    || !"human".equals(message.get("type")) || activeAnchors.containsKey(runId)) continue;
            RunRecord run = cached.get(runId);
            if (run != null && !run.bridge.snapshot(run.runId).status().isTerminal()) {
                activeAnchors.put(runId, positions.getOrDefault(message.get("id"), Long.MAX_VALUE));
            }
        }
        messages.sort(java.util.Comparator.comparingLong(message ->
                activeAnchors.getOrDefault(message.get("run_id"),
                        positions.getOrDefault(message.get("id"), Long.MAX_VALUE))));
        return messages;
    }

    private void addRunSupportMessages(
            ThreadRecord thread,
            AgentRunId runId,
            RunRecord cached,
            List<Map<String, Object>> messages,
            Set<String> visibleAssistantMessageIds,
            RunUsageFacts usageFacts) {
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
                String modelMessageId = usageFacts.messageIdByToolCallId().get(round.getFirst().id().value());
                Map<String, Object> usage = visibleAssistantMessageIds.contains(modelMessageId)
                        ? null
                        : usageFacts.usageByToolCallId().get(round.getFirst().id().value());
                messages.add(DeerFlowToolTranscriptProjection.requestMessage(round, usage));
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
            if (run != null) {
                synchronized (run) {
                    adoptPendingInteractionCard(thread, run, runId);
                }
            }
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

    /**
     * Makes a waiting Run's Interaction card durable before projecting Thread messages, so a card
     * raised while no client was attached (whose stream event has since left the bounded replay
     * window) is still visible and answerable after a reload. The SDK pending view stays
     * authoritative: a card that cannot be persisted is still projected in memory for this read, so
     * the durable-state recovery the reconnect advertises always yields the card.
     */
    private void adoptPendingInteractionCard(ThreadRecord thread, RunRecord run, String runId) {
        Optional<InteractionView> pending = run.bridge.pendingInteraction(new AgentRunId(runId));
        if (pending.isEmpty()) {
            return;
        }
        String requestId = pending.orElseThrow().requestId().value();
        if (!requestId.equals(run.interactionRequestId)) {
            // A new approval supersedes any response already accepted for the previous one. Without
            // this the responding path would see a stale accepted response and refuse the new
            // submission as a different response.
            run.acceptedInteractionResponse = null;
        }
        boolean saved = thread.interactionDisplays.stream()
                .anyMatch(display -> display.runId().equals(runId) && display.requestId().equals(requestId));
        if (saved) {
            run.interactionRequestId = requestId;
            return;
        }
        try {
            var display = ensureSavedPendingDisplay(thread, run, pending.orElseThrow());
            run.interactionRequestMessage = requestMessage(display);
        } catch (IOException | IllegalArgumentException unavailable) {
            INTERACTION_LOG.log(System.Logger.Level.WARNING,
                    "Pending Interaction card could not be persisted while reading the Thread: "
                            + unavailable.getClass().getName() + ": " + boundedReason(unavailable));
            run.interactionRequestMessage =
                    DeerFlowInteractionHttpProjection.requestMessage(pending.orElseThrow(), runId);
        }
        run.interactionRequestId = requestId;
    }

    private static String interactionMessageToolCallId(Map<String, Object> message) {
        return message.get("tool_call_id") instanceof String value ? value : "";
    }

    /**
     * Bounded single-line reason of an Interaction-card failure. The candidate messages are this
     * class's own invariant names ("invalid interaction display", "interaction display IDs or order
     * are invalid", "Pending Interaction conflicts with saved presentation"), never client text or a
     * provider body, so recording them is safe and keeps the failure diagnosable.
     */
    private static String boundedReason(Throwable failure) {
        String message = failure.getMessage();
        if (message == null) {
            return "";
        }
        String single = message.replace('\n', ' ').replace('\r', ' ');
        return single.length() > 160 ? single.substring(0, 160) : single;
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

    /** Cards of any other Run can never be answered again, so they are stale presentation. */
    private static java.util.Set<String> liveRunIds(ThreadRecord thread) {
        return thread.activeRunId == null
                ? java.util.Set.of()
                : java.util.Set.of(thread.activeRunId);
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
        // The saved card must keep a strictly increasing request sequence (validateDisplays); a
        // pending card raised while the client was disconnected can otherwise reuse a sequence that
        // an earlier saved card already holds, and the card would never persist.
        long latest = thread.interactionDisplays.stream()
                .mapToLong(PersistentThreadCatalog.InteractionDisplay::requestSequence)
                .max()
                .orElse(0);
        var display = new PersistentThreadCatalog.InteractionDisplay(
                run.runId.value(), requestId,
                Math.max(Math.max(thread.nextMessageSequence, run.humanSequence + 1), latest + 1),
                view.createdAt(), view.target().reference(), view.title(),
                view.safePrompt(), view.target().safeSummary(),
                view.allowedActions().stream().map(action -> action.value()).toList(), null);
        List<PersistentThreadCatalog.InteractionDisplay> updated =
                new ArrayList<>(thread.interactionDisplays);
        updated.add(display);
        // A long-lived Thread keeps every card it ever raised; drop the oldest answered ones so the
        // waiting card can always become durable (W04-BUG-010).
        updated = new ArrayList<>(
                PersistentThreadCatalog.retainDisplaysWithinCapacity(updated, liveRunIds(thread)));
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

    Map<String, Object> getWorkspaceChanges(String threadId, String runId, String rawQuery) throws ConversationException {
        boolean includeFiles = queryBoolean(rawQuery, "include_files", true);
        boolean includeDiff = queryBoolean(rawQuery, "include_diff", true);
        RunRecord liveRun = runs.get(runId);
        boolean liveRunVisible = liveRun != null && liveRun.threadId.equals(threadId);
        if (liveRunVisible) {
            completeWorkspaceChangesIfTerminal(liveRun);
        }
        WorkspaceBinding binding;
        try {
            binding = workspaceChanges.requireThreadWorkspace(ThreadOwners.of(threadId), threadId);
        } catch (WorkspaceException missing) {
            throw ConversationException.notFound("Run " + runId + " not found");
        }
        WorkspaceChanges changes;
        try {
            changes = workspaceChanges.readRunChanges(
                    ThreadOwners.of(threadId), threadId, binding.workspaceId(), runId);
        } catch (WorkspaceException failure) {
            if ((failure.error() == WorkspaceError.RUN_BASELINE_NOT_FOUND
                    || failure.error() == WorkspaceError.RUN_CHANGES_NOT_COMPLETED) && liveRunVisible) {
                return emptyWorkspaceChanges();
            }
            if (failure.error() == WorkspaceError.RUN_BASELINE_NOT_FOUND
                    || failure.error() == WorkspaceError.OWNER_MISMATCH
                    || failure.error() == WorkspaceError.WORKSPACE_NOT_FOUND) {
                throw ConversationException.notFound("Run " + runId + " not found");
            }
            throw ConversationException.internal("Workspace changes are unavailable");
        }
        // DeerFlow records no workspace-change event for a completed run whose snapshots
        // are identical, so its read endpoint returns the same unavailable envelope as an
        // active run rather than an available response with an empty file list.
        if (changes.files().isEmpty()) {
            return emptyWorkspaceChanges();
        }
        return workspaceChangesResponse(changes, includeFiles, includeDiff);
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
                    ThreadOwners.of(run.threadId), run.threadId);
            workspaceChanges.completeRunChanges(
                    ThreadOwners.of(run.threadId), run.threadId, binding.workspaceId(), run.runId.value());
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

    private void writeMessageDelta(
            RunEventSink sink, RunRecord run, String delta, String id, int ordinal)
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
        sink.event(
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
        backgroundFollowers.forEach(Thread::interrupt);
        ownedBridges.forEach(HaifaRunBridge::close);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        List<Thread> observers = new ArrayList<>(workspaceCompletionObservers);
        observers.addAll(titleCompletionObservers);
        observers.addAll(backgroundFollowers);
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

    static final class ThreadRecord {
        private final String threadId;
        private final String ownerId;
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
            this(threadId, metadata, now, AccessOwner.DEFAULT);
        }

        private ThreadRecord(String threadId, Map<String, Object> metadata, Instant now, String ownerId) {
            this.threadId = threadId;
            this.ownerId = ownerId == null || ownerId.isBlank() ? AccessOwner.DEFAULT : ownerId;
            this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
            this.createdAt = now;
            this.updatedAt = now;
            this.admissionRecovered = true;
            ThreadOwners.remember(threadId, this.ownerId);
        }

        private ThreadRecord(PersistentThreadCatalog.Entry entry) {
            this.threadId = entry.threadId();
            this.ownerId = entry.ownerId();
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

    static final class RunRecord {
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
            // Wake synchronizeAllAvailable waiters so catch-up can resume as soon as the
            // slowest live stream advances instead of only on the 5ms wait poll.
            synchronized (this) {
                notifyAll();
            }
        }

        void observerLeft(Object observer) {
            liveObservers.remove(observer);
            synchronized (this) {
                notifyAll();
            }
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

    static final class MalformedRequestException extends RuntimeException {}

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

package io.haifa.agent.superharness.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelAdapterCoordinate;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelStreamSink;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.policy.api.PolicyPresets;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.sdk.api.AgentMetadata;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.api.SdkCallerProvider;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.product.ProductId;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductRunProfileRef;
import io.haifa.agent.sdk.product.ProductVersion;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.store.sqlite.SqliteSdkContributions;
import io.haifa.agent.store.sqlite.SqliteStoreConfiguration;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceChangeLimits;
import io.haifa.agent.superharness.workspace.WorkspaceLimits;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.crypto.SecretKey;

/**
 * Test-only separate-OS-process entry point used by {@code W05CrossProcessPendingApprovalRestartTest}.
 *
 * <p>It opens the exact same data-root and continuation key the Super Harness Gateway used, reads the
 * pending Interaction from the authoritative SQLite/SDK facts (never an in-JVM RunRecord), approves it
 * through the public SDK, and writes machine-readable evidence. It is deliberately a real process so
 * the proof covers credential resolution, SQLite reopening and process-local caches.
 *
 * <p>Args: {@code <dataRoot> <runId> <evidenceFile> <markerFile>}. Exit code 0 only when the resumed
 * Run reaches {@code COMPLETED} and the approved tool executed exactly once.
 */
public final class W05RestartResumeProcess {
    private W05RestartResumeProcess() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("expected <dataRoot> <runId> <evidenceFile> <markerFile>");
            System.exit(64);
            return;
        }
        Path dataRoot = Path.of(args[0]).toAbsolutePath().normalize();
        String runIdValue = args[1];
        Path evidenceFile = Path.of(args[2]).toAbsolutePath().normalize();
        Path markerFile = Path.of(args[3]).toAbsolutePath().normalize();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("run_id", runIdValue);
        int exit = 1;
        try {
            // Resolve the continuation key exactly like the Gateway bootstrap: an env:// reference
            // must exist, and an existing Runtime database without its credential must fail closed.
            // env:// references never touch the OS credential store.
            SecretKey key = DeerFlowBootstrapApi.continuationKey(
                    System.getenv(), dataRoot, io.haifa.agent.auth.localmodel.WindowsCredentialManagerClient.defaultClient());
            evidence.put("continuation_key_resolved", true);

            var snapshot = HaifaPublicInteractionSpikeTest.modelSnapshot();
            var sqlite = SqliteSdkContributions.initializeWithKey(
                    SqliteStoreConfiguration.defaults(
                            dataRoot.resolve("runtime").resolve("haifa-agent.sqlite")),
                    Clock.systemUTC(),
                    key);
            try (HaifaAgent agent = buildAgent(snapshot, sqlite, dataRoot, new MarkerTool(markerFile))) {
                AgentRunId runId = new AgentRunId(runIdValue);
                Optional<InteractionView> pending = agent.runs().pendingInteraction(runId);
                evidence.put("pending_after_restart", pending.isPresent());
                if (pending.isEmpty()) {
                    evidence.put("error", "no pending interaction after restart");
                    exit = 31;
                } else {
                    InteractionView view = pending.orElseThrow();
                    evidence.put("request_id", view.requestId().value());
                    evidence.put("interaction_kind", view.kind().value());
                    evidence.put("interaction_state", view.state().name());
                    evidence.put("tool_call_id", view.target().reference());
                    var submission = DeerFlowInteractionHttpProjection.submission(
                            view,
                            new DeerFlowInteractionHttpProjection.FrozenResponse(
                                    DeerFlowInteractionHttpProjection.SOURCE,
                                    view.requestId().value(),
                                    "option",
                                    "approve",
                                    "approve"),
                            Instant.now());
                    evidence.put("submission_id", submission.responseId().value());
                    var receipt = agent.runs().respond(submission);
                    evidence.put("receipt_status", receipt.status().name());
                    var terminal = agent.runs().await(runId, Duration.ofSeconds(20)).orElseThrow();
                    evidence.put("final_status", terminal.status().name());
                    terminal.error().ifPresent(error -> evidence.put("run_error", error.toString()));
                    terminal.terminationReason().ifPresent(reason -> evidence.put("termination_reason", reason.toString()));
                    long markerLines = Files.exists(markerFile)
                            ? Files.readAllLines(markerFile).size()
                            : 0L;
                    evidence.put("tool_invocations", markerLines);
                    exit = terminal.status() == AgentRunStatus.COMPLETED && markerLines == 1 ? 0 : 32;
                }
            } finally {
                sqlite.persistence().close();
            }
        } catch (RuntimeException | IOException failure) {
            evidence.put("error", failure.getClass().getName());
            evidence.put("error_message", String.valueOf(failure.getMessage()));
            System.err.println("W05_RESTART_PROCESS_FAILURE " + failure.getClass().getName() + ": "
                    + failure.getMessage());
            failure.printStackTrace(System.err);
            exit = 33;
        }
        writeEvidence(evidenceFile, evidence);
        System.exit(exit);
    }

    private static HaifaAgent buildAgent(
            ResolvedModelSnapshot snapshot,
            SqliteSdkContributions sqlite,
            Path dataRoot,
            JavaTool<?, ?> tool) {
        var models = new ModelContribution(
                Map.of(ModelAdapterCoordinate.from(snapshot), new FinalAnswerModel()),
                snapshot,
                Map.of(snapshot.modelId().value(), snapshot));
        // The original Run was admitted by the Gateway bootstrap, which always declares the
        // production file tools; the SDK rejects a resume whose frozen tool catalog differs.
        PersistentProjectWorkspaceService projects = new PersistentProjectWorkspaceService(
                dataRoot.resolve("workspace"), WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
        RestrictedWorkspaceService toolWorkspace = new RestrictedWorkspaceService(
                dataRoot.resolve("workspace"), WorkspaceLimits.defaults());
        List<JavaTool<?, ?>> tools = new java.util.ArrayList<>();
        tools.add(tool);
        tools.addAll(DeerFlowBootstrapApi.fileTools(new DeerFlowThreadRunApi.ThreadToolScope(
                "w05-cross-process", Optional.empty(), new CurrentRunUploads()), projects, toolWorkspace));
        Set<String> names = tools.stream().map(item -> item.spec().name().value())
                .collect(java.util.stream.Collectors.toSet());
        ProductProfile profile = ProductProfile.create(
                new ProductId("haifa-super-harness"),
                new ProductVersion("1.0.0"),
                new AgentDefinitionId("haifa-super-harness-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                io.haifa.agent.superharness.server.prompt.LeadPromptComposer.classpath().compose(
                        new io.haifa.agent.superharness.server.prompt.LeadPromptInputs(null, null, false, List.of()),
                        names).instructions(),
                new ProductRunProfileRef(snapshot.modelId().value(), "1.0.0"),
                new AgentRunBudget(65_536, 8_192, 65_536, 16, 16, 0, "USD", 100),
                new AgentRunLimits(16, 0, 1, 120_000, 60_000, 16, 16, 0),
                names,
                Set.of());
        return HaifaAgents.builder(profile)
                .metadata(new AgentMetadata("W05 cross-process restart probe"))
                .callerProvider(SdkCallerProvider.defaultPublicUser())
                .model(models)
                .persistence(sqlite.borrowedPersistence())
                .conversation(() -> sqlite.conversation().conversationStore())
                .tools(tools)
                .autoApproveReadOnlyNetworkTools(tools.stream()
                        .filter(item -> item instanceof ProductionWebSearchTool
                                || item instanceof ProductionWebFetchTool || item instanceof ProductionImageSearchTool)
                        .map(item -> item.spec().name())
                        .collect(java.util.stream.Collectors.toSet()))
                .policy(new PolicyPlatformContribution(
                        PolicyPresets.standardApproval(), new DefaultPolicyDecisionService()))
                .build();
    }

    private static void writeEvidence(Path file, Map<String, Object> evidence) throws IOException {
        Files.createDirectories(file.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), evidence);
    }

    /** Deterministic continuation model: the resumed Run only needs a final answer. */
    static final class FinalAnswerModel implements AgentChatModel {
        @Override
        public AgentChatResponse invoke(AgentChatRequest request) {
            return new AgentChatResponse(
                    request.callId().value(),
                    request.model().providerModelId(),
                    "cross-process interaction resolved",
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(1, 1),
                    "",
                    Map.of());
        }

        @Override
        public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
            return invoke(request);
        }
    }

    /** Same tool name/side effect the Gateway registered; the side effect is observable across processes. */
    static final class MarkerTool implements JavaTool<HaifaPublicInteractionSpikeTest.MutatingProbeInput,
            HaifaPublicInteractionSpikeTest.MutatingProbeOutput> {
        private static final JavaToolSpec<HaifaPublicInteractionSpikeTest.MutatingProbeInput,
                        HaifaPublicInteractionSpikeTest.MutatingProbeOutput>
                // The definition (name/description/schema/side effects) must be identical to the
                // process that admitted the Run: the SDK freezes the tool coordinate and rejects a
                // resume whose definition hash differs. Only the side-effect implementation differs.
                SPEC = JavaToolSpec.builder(
                                "mutating_probe",
                                HaifaPublicInteractionSpikeTest.MutatingProbeInput.class,
                                HaifaPublicInteractionSpikeTest.MutatingProbeOutput.class)
                        .description("Test-only mutation whose policy requires approval")
                        .sideEffects(ToolSideEffect.EXTERNAL_SYSTEM_MUTATION)
                        .build();

        private final Path marker;

        MarkerTool(Path marker) {
            this.marker = marker;
        }

        @Override
        public JavaToolSpec<HaifaPublicInteractionSpikeTest.MutatingProbeInput,
                        HaifaPublicInteractionSpikeTest.MutatingProbeOutput>
                spec() {
            return SPEC;
        }

        @Override
        public HaifaPublicInteractionSpikeTest.MutatingProbeOutput invoke(
                HaifaPublicInteractionSpikeTest.MutatingProbeInput input, JavaToolContext context) {
            try {
                Files.writeString(
                        marker,
                        "invoked\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException failure) {
                throw new IllegalStateException("marker write failed", failure);
            }
            return new HaifaPublicInteractionSpikeTest.MutatingProbeOutput("applied:" + input.value());
        }
    }
}

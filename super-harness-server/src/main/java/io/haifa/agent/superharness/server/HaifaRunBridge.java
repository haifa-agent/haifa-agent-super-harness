package io.haifa.agent.superharness.server;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.ModelAdapterCoordinate;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.policy.api.PolicyPresets;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.runtime.api.AgentRunViewSnapshot;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.InteractionResponseReceipt;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPage;
import io.haifa.agent.runtime.api.ToolCallView;
import io.haifa.agent.sdk.api.AgentMetadata;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.api.HaifaAgentBuilder;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.api.RunInputCommand;
import io.haifa.agent.sdk.api.RunInputResult;
import io.haifa.agent.sdk.api.SdkCallerProvider;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.contribution.SkillPlatformContribution;
import io.haifa.agent.sdk.contribution.SkillToolContributions;
import io.haifa.agent.sdk.contribution.ToolRegistration;
import io.haifa.agent.sdk.conversation.ConversationRecord;
import io.haifa.agent.sdk.conversation.ConversationTurnPage;
import io.haifa.agent.sdk.conversation.ConversationTurnQuery;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.conversation.SubmitConversationTurnCommand;
import io.haifa.agent.sdk.product.ProductId;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductRunProfileRef;
import io.haifa.agent.sdk.product.ProductVersion;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.starter.HaifaAgentStarter;
import io.haifa.agent.starter.McpServerSpec;
import io.haifa.agent.starter.McpToolPlatforms;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/** Adapter that exposes only public Haifa Agent SDK types to the HTTP boundary. */
final class HaifaRunBridge implements AutoCloseable {
    private final HaifaAgent agent;
    private final ModelBinding binding;
    private final boolean hasTools;
    private final String mcpSignature;
    private final String skillSignature;
    private final AtomicLong turnPageReads = new AtomicLong();

    HaifaRunBridge(ModelBinding binding) {
        this(binding, List.of());
    }

    HaifaRunBridge(ModelBinding binding, List<? extends JavaTool<?, ?>> tools) {
        this.binding = binding;
        this.hasTools = !tools.isEmpty();
        this.mcpSignature = "";
        this.skillSignature = "";
        this.agent = HaifaAgentStarter.builder()
                .name("Super Harness local compatibility agent")
                .instructions("Return a concise answer to the local user's request.")
                .model(binding.model(), binding.snapshot())
                .defaultModel(binding.name())
                .tools(tools)
                .build();
    }

    HaifaRunBridge(
            ModelBinding binding,
            List<? extends JavaTool<?, ?>> tools,
            DurableHaifaFoundation foundation) {
        this(binding, tools, foundation, McpRunTools.Projection.empty());
    }

    HaifaRunBridge(
            ModelBinding binding,
            List<? extends JavaTool<?, ?>> tools,
            DurableHaifaFoundation foundation,
            McpRunTools.Projection mcpProjection) {
        this(binding, tools, foundation, mcpProjection, null);
    }

    HaifaRunBridge(
            ModelBinding binding,
            List<? extends JavaTool<?, ?>> tools,
            DurableHaifaFoundation foundation,
            McpRunTools.Projection mcpProjection,
            SkillRunTools.Projection skillProjection) {
        List<McpServerSpec> mcpSpecs = mcpProjection.specs();
        this.binding = binding;
        // MCP ToolLifecycle events are projected by the frozen transcript only when the
        // bridge advertises tools; a bridge whose only tools come from MCP still does.
        this.hasTools = !tools.isEmpty() || !mcpSpecs.isEmpty()
                || (skillProjection != null && skillProjection.installTools());
        // The canonical fingerprint covers every field that shaped the SDK declaration, so
        // a header credential reference or timeout change forces a later Run to rebuild.
        this.mcpSignature = mcpProjection.fingerprint();
        this.skillSignature = skillProjection == null ? "" :
                (skillProjection.recovery() ? "recovery:" : "") + skillProjection.fingerprint();
        ModelContribution models = new ModelContribution(
                Map.of(ModelAdapterCoordinate.from(binding.snapshot()), binding.model()),
                binding.snapshot(),
                Map.of(binding.name(), binding.snapshot()));
        Set<String> allowedTools = tools.stream()
                .map(tool -> tool.spec().name().value())
                .collect(java.util.stream.Collectors.toSet());
        if (skillProjection != null && skillProjection.installTools()) {
            allowedTools.add("skill_load");
            allowedTools.add("skill_resource_read");
        }
        HaifaAgentBuilder builder = HaifaAgents.builder(profile(
                        binding.snapshot(), allowedTools,
                        skillProjection == null ? Set.of() : skillProjection.allowedSkills()))
                .metadata(new AgentMetadata("Super Harness local compatibility agent"))
                .callerProvider(SdkCallerProvider.defaultPublicUser())
                .model(models)
                .persistence(foundation.persistence())
                .conversation(foundation.conversation())
                .tools(tools)
                .policy(new PolicyPlatformContribution(
                        PolicyPresets.standardApproval(), new DefaultPolicyDecisionService()));
        if (skillProjection != null && skillProjection.installTools()) {
            var contributions = SkillToolContributions.create(
                    foundation.persistence(), skillProjection.loader(), Instant::now);
            List<ToolRegistration> registrations = contributions.stream()
                    .map(contribution -> new ToolRegistration(
                            contribution.alias(), contribution.definition(),
                            contribution.providerBindingReference(), contribution.provider()))
                    .toList();
            builder.skillPlatform(new SkillPlatformContribution(
                            skillProjection.catalog(), skillProjection.loader()))
                    .toolRegistrations(registrations);
        }
        McpToolPlatforms.McpToolPlatform platform = null;
        if (!mcpSpecs.isEmpty()) {
            // The SDK owns per-server connection isolation, discovery, naming, invocation
            // and release; SH only declares the projections. applyTo sets the builder's
            // credentials contribution, so SH must not set another one.
            platform = McpToolPlatforms.connect(
                    mcpSpecs,
                    new TenantRef(McpRunTools.TENANT_ID),
                    new PrincipalRef(McpRunTools.PRINCIPAL_ID, McpRunTools.PRINCIPAL_TYPE));
            platform.applyTo(builder);
        }
        try {
            this.agent = builder.build();
        } catch (RuntimeException | Error failure) {
            // The agent never took ownership, so the caller closes the connected platform.
            if (platform != null) {
                platform.close();
            }
            throw failure;
        }
    }

    String mcpSignature() {
        return mcpSignature;
    }

    String skillSignature() {
        return skillSignature;
    }

    String bindingIdentity() {
        return binding.name() + "@" + binding.revision();
    }

    boolean hasTools() {
        return hasTools;
    }

    StartedRun start(String threadId, String message, Optional<SessionState> existing) {
        if (existing.isEmpty()) {
            var created = agent.conversations()
                    .start(new StartConversationCommand(
                            "thread:" + threadId + ":first", threadId, message));
            return new StartedRun(
                    created.runId(),
                    new SessionState(created.record().sessionId(), created.record().revision()));
        }
        SessionState state = existing.orElseThrow();
        var submitted = agent.conversations()
                .submit(new SubmitConversationTurnCommand(
                        state.sessionId(),
                        state.revision(),
                        "thread:" + threadId + ":revision:" + state.revision(),
                        message));
        return new StartedRun(
                submitted.runId(),
                new SessionState(submitted.record().sessionId(), submitted.record().revision()));
    }

    AgentRunSnapshot snapshot(AgentRunId runId) {
        return agent.runs().find(runId).orElseThrow();
    }

    Optional<AgentRunSnapshot> findRun(AgentRunId runId) {
        return agent.runs().find(runId);
    }

    Optional<ConversationRecord> findConversation(AgentSessionId sessionId) {
        return agent.conversations().find(sessionId);
    }

    ConversationTurnPage turns(AgentSessionId sessionId, ConversationTurnQuery query) {
        turnPageReads.incrementAndGet();
        return agent.conversations().turns(sessionId, query);
    }

    void resetTurnPageReads() {
        turnPageReads.set(0);
    }

    long turnPageReads() {
        return turnPageReads.get();
    }

    AgentRunViewSnapshot authoritativeView(AgentRunId runId) {
        return agent.runs().view(runId).orElseThrow();
    }

    RunEventCursor beforeFirstEvent(AgentRunId runId) {
        return RunEventCursor.beforeFirst(runId);
    }

    RunEventPage events(AgentRunId runId, RunEventCursor cursor, int limit) {
        return agent.runs().events(runId, cursor, limit);
    }

    Optional<InteractionView> pendingInteraction(AgentRunId runId) {
        return agent.runs().pendingInteraction(runId);
    }

    List<ToolCallView> toolCalls(AgentRunId runId) {
        return agent.runs().toolCalls(runId);
    }

    InteractionResponseReceipt respond(InteractionResponseSubmission submission) {
        return agent.runs().respond(submission);
    }

    RunInputResult submitInput(RunInputCommand command) {
        return agent.runs().submitInput(command);
    }

    boolean cancel(AgentRunId runId) {
        AgentRunStatus before = snapshot(runId).status();
        if (before.isTerminal()) {
            return false;
        }
        agent.runs().handle(runId).cancel();
        return true;
    }

    @Override
    public void close() {
        agent.close();
    }

    record SessionState(AgentSessionId sessionId, long revision) {}

    record StartedRun(AgentRunId runId, SessionState session) {}

    private static ProductProfile profile(
            ResolvedModelSnapshot snapshot, Set<String> allowedTools, Set<String> allowedSkills) {
        return ProductProfile.create(
                new ProductId("haifa-super-harness"),
                new ProductVersion("1.0.0"),
                new AgentDefinitionId("haifa-super-harness-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                "Return a concise answer to the local user's request.",
                new ProductRunProfileRef(snapshot.modelId().value(), "1.0.0"),
                new AgentRunBudget(65_536, 8_192, 65_536, 16, 16, 0, "USD", 100),
                new AgentRunLimits(16, 0, 1, 120_000, 60_000, 16, 16, 0),
                allowedTools,
                allowedSkills);
    }

    record ModelBinding(
            String name,
            String revision,
            AgentChatModel model,
            ResolvedModelSnapshot snapshot) {
        ModelBinding {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("model name must not be blank");
            if (revision == null || revision.isBlank()) {
                throw new IllegalArgumentException("model revision must not be blank");
            }
            java.util.Objects.requireNonNull(model, "model must not be null");
            java.util.Objects.requireNonNull(snapshot, "snapshot must not be null");
            if (!snapshot.modelId().value().equals(name)) {
                throw new IllegalArgumentException("model binding name must match the frozen snapshot");
            }
        }
    }

}

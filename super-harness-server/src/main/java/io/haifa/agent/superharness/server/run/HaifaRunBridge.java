package io.haifa.agent.superharness.server.run;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.superharness.server.auth.AccessOwner;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.EffectiveModelParameters;
import io.haifa.agent.model.api.ModelAdapterCoordinate;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelReasoningPolicy;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.policy.api.PolicyPresets;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.AgentRunViewSnapshot;
import io.haifa.agent.runtime.api.FrozenInstructionDiagnostic;
import io.haifa.agent.runtime.api.InteractionResponseReceipt;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPage;
import io.haifa.agent.runtime.api.ToolCallView;
import io.haifa.agent.runtime.api.AgentPlanView;
import io.haifa.agent.runtime.api.ChildRunView;
import io.haifa.agent.sdk.api.AgentMetadata;
import io.haifa.agent.sdk.plan.PlanAuthoringSpec;
import io.haifa.agent.superharness.server.plan.PlanAuthoringResources;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.api.HaifaAgentBuilder;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.product.ChildAgentSpec;
import io.haifa.agent.sdk.api.RunInputCommand;
import io.haifa.agent.sdk.api.RunInputResult;
import io.haifa.agent.sdk.api.SdkCallerProvider;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.contribution.SkillPlatformContribution;
import io.haifa.agent.sdk.contribution.SkillToolContributions;
import io.haifa.agent.sdk.conversation.ConversationRecord;
import io.haifa.agent.sdk.conversation.ConversationTurnPage;
import io.haifa.agent.sdk.conversation.ConversationTurnQuery;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.conversation.SubmitConversationTurnCommand;
import io.haifa.agent.sdk.diagnostics.PromptDiagnostics;
import io.haifa.agent.sdk.product.ProductId;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductRunProfileRef;
import io.haifa.agent.sdk.product.ProductRunProfile;
import io.haifa.agent.sdk.product.ProductVersion;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.api.SdkCaller;
import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.starter.HaifaAgentStarter;
import io.haifa.agent.starter.HostExecutionPlatforms;
import io.haifa.agent.starter.McpServerSpec;
import io.haifa.agent.starter.McpToolPlatforms;
import io.haifa.agent.starter.SkillScriptPlatforms;
import io.haifa.agent.superharness.server.config.HostShellToolConfig;
import io.haifa.agent.superharness.server.config.McpRunTools;
import io.haifa.agent.superharness.server.config.SkillRunTools;
import io.haifa.agent.superharness.server.config.SkillScriptToolConfig;
import io.haifa.agent.superharness.server.config.SubagentRunConfiguration;
import io.haifa.agent.superharness.server.prompt.AgentPromptContext;
import io.haifa.agent.superharness.server.prompt.LeadPromptComposer;
import io.haifa.agent.superharness.server.prompt.LeadPromptInputs;
import io.haifa.agent.superharness.server.tool.agent.ProductionSetupAgentTool;
import io.haifa.agent.superharness.server.tool.agent.ProductionUpdateAgentTool;
import io.haifa.agent.superharness.server.tool.web.ProductionImageSearchTool;
import io.haifa.agent.superharness.server.tool.web.ProductionWebFetchTool;
import io.haifa.agent.superharness.server.tool.web.ProductionWebSearchTool;
import io.haifa.agent.tool.api.ToolName;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Adapter that exposes only public Haifa Agent SDK types to the HTTP boundary. */
public final class HaifaRunBridge implements AutoCloseable {
    /** Test/compatibility starter path only; production always renders the frozen lead prompt. */
    private static final String STARTER_INSTRUCTIONS = "Return a concise answer to the local user's request.";

    private final HaifaAgent agent;
    private final ModelBinding binding;
    private final boolean hasTools;
    private final boolean isPlanMode;
    private final String mcpSignature;
    private final String skillSignature;
    private final String promptSignature;
    private final String delegationSignature;
    private final Set<String> mcpToolNames;
    private final Set<String> skillScriptToolNames;
    private final AgentPromptContext agentContext;
    private final AtomicLong turnPageReads = new AtomicLong();

    public HaifaRunBridge(ModelBinding binding) {
        this(binding, List.of());
    }

    public HaifaRunBridge(ModelBinding binding, List<? extends JavaTool<?, ?>> tools) {
        this.binding = binding;
        this.hasTools = !tools.isEmpty();
        this.isPlanMode = false;
        this.mcpSignature = "";
        this.skillSignature = "";
        this.promptSignature = "";
        this.delegationSignature = "";
        this.mcpToolNames = Set.of();
        this.skillScriptToolNames = Set.of();
        this.agentContext = AgentPromptContext.defaults();
        this.agent = HaifaAgentStarter.builder()
                .name("Super Harness local compatibility agent")
                .instructions(STARTER_INSTRUCTIONS)
                .model(binding.model(), binding.snapshot())
                .defaultModel(binding.name())
                .tools(tools)
                .build();
    }

    public HaifaRunBridge(
            ModelBinding binding,
            List<? extends JavaTool<?, ?>> tools,
            DurableHaifaFoundation foundation) {
        this(binding, tools, foundation, McpRunTools.Projection.empty());
    }

    public HaifaRunBridge(
            ModelBinding binding,
            List<? extends JavaTool<?, ?>> tools,
            DurableHaifaFoundation foundation,
            McpRunTools.Projection mcpProjection) {
        this(binding, tools, foundation, mcpProjection, null);
    }

    public HaifaRunBridge(
            ModelBinding binding,
            List<? extends JavaTool<?, ?>> tools,
            DurableHaifaFoundation foundation,
            McpRunTools.Projection mcpProjection,
            SkillRunTools.Projection skillProjection) {
        this(binding, tools, foundation, mcpProjection, skillProjection, AgentPromptContext.defaults());
    }

    public HaifaRunBridge(
            ModelBinding binding,
            List<? extends JavaTool<?, ?>> tools,
            DurableHaifaFoundation foundation,
            McpRunTools.Projection mcpProjection,
            SkillRunTools.Projection skillProjection,
            AgentPromptContext agentContext) {
        this(binding, tools, foundation, mcpProjection, skillProjection, agentContext,
                SubagentRunConfiguration.disabled());
    }

    public HaifaRunBridge(ModelBinding binding, List<? extends JavaTool<?, ?>> tools,
            DurableHaifaFoundation foundation, McpRunTools.Projection mcpProjection,
            SkillRunTools.Projection skillProjection, AgentPromptContext agentContext,
            SubagentRunConfiguration delegation) {
        this(binding, tools, foundation, mcpProjection, skillProjection, agentContext, delegation,
                new io.haifa.agent.runtime.api.ChildRunCapacity(delegation.settings().runningCapacity()));
    }

    public HaifaRunBridge(ModelBinding binding, List<? extends JavaTool<?, ?>> tools,
            DurableHaifaFoundation foundation, McpRunTools.Projection mcpProjection,
            SkillRunTools.Projection skillProjection, AgentPromptContext agentContext,
            SubagentRunConfiguration delegation, io.haifa.agent.runtime.api.ChildRunCapacity childRunCapacity) {
        this(binding, tools, foundation, mcpProjection, skillProjection, agentContext, delegation, childRunCapacity, false);
    }

    public HaifaRunBridge(ModelBinding binding, List<? extends JavaTool<?, ?>> tools,
            DurableHaifaFoundation foundation, McpRunTools.Projection mcpProjection,
            SkillRunTools.Projection skillProjection, AgentPromptContext agentContext,
            SubagentRunConfiguration delegation, io.haifa.agent.runtime.api.ChildRunCapacity childRunCapacity,
            boolean isPlanMode) {
        this(binding, tools, foundation, mcpProjection, skillProjection, agentContext, delegation,
                childRunCapacity, isPlanMode, null, null);
    }

    /** Compatibility overload: no host shell Tool is declared. */
    public HaifaRunBridge(ModelBinding binding, List<? extends JavaTool<?, ?>> tools,
            DurableHaifaFoundation foundation, McpRunTools.Projection mcpProjection,
            SkillRunTools.Projection skillProjection, AgentPromptContext agentContext,
            SubagentRunConfiguration delegation, io.haifa.agent.runtime.api.ChildRunCapacity childRunCapacity,
            boolean isPlanMode, Supplier<? extends ExecutorService> executionExecutorFactory,
            Long parentWallTimeMillisForTests) {
        this(binding, tools, foundation, mcpProjection, skillProjection, agentContext, delegation,
                childRunCapacity, isPlanMode, executionExecutorFactory, null, parentWallTimeMillisForTests);
    }

    /** Host physical-execution seam: an optional SDK execution executor factory, test-only. */
    public HaifaRunBridge(ModelBinding binding, List<? extends JavaTool<?, ?>> tools,
            DurableHaifaFoundation foundation, McpRunTools.Projection mcpProjection,
            SkillRunTools.Projection skillProjection, AgentPromptContext agentContext,
            SubagentRunConfiguration delegation, io.haifa.agent.runtime.api.ChildRunCapacity childRunCapacity,
            boolean isPlanMode, Supplier<? extends ExecutorService> executionExecutorFactory,
            HostShellToolConfig hostShell,
            Long parentWallTimeMillisForTests) {
        this(binding, tools, foundation, mcpProjection, skillProjection, agentContext, delegation,
                childRunCapacity, isPlanMode, executionExecutorFactory, hostShell, null,
                parentWallTimeMillisForTests);
    }

    /** Host physical-execution seam plus the optional trusted Skill script declaration. */
    public HaifaRunBridge(ModelBinding binding, List<? extends JavaTool<?, ?>> tools,
            DurableHaifaFoundation foundation, McpRunTools.Projection mcpProjection,
            SkillRunTools.Projection skillProjection, AgentPromptContext agentContext,
            SubagentRunConfiguration delegation, io.haifa.agent.runtime.api.ChildRunCapacity childRunCapacity,
            boolean isPlanMode, Supplier<? extends ExecutorService> executionExecutorFactory,
            HostShellToolConfig hostShell,
            SkillScriptToolConfig skillScript,
            Long parentWallTimeMillisForTests) {
        java.util.Objects.requireNonNull(childRunCapacity, "childRunCapacity");
        this.agentContext = java.util.Objects.requireNonNull(agentContext, "agent context must not be null");
        this.isPlanMode = isPlanMode;
        List<McpServerSpec> mcpSpecs = mcpProjection.specs();
        this.binding = binding;
        // MCP ToolLifecycle events are projected by the frozen transcript only when the
        // bridge advertises tools; a bridge whose only tools come from MCP still does.
        this.hasTools = isPlanMode || delegation.enabled() || !tools.isEmpty() || !mcpSpecs.isEmpty()
                || (skillProjection != null && skillProjection.installTools())
                || (hostShell != null && hostShell.enabled()) || skillScript != null;
        // The declared Skill script Tool adds its catalog names to the prompt set before the
        // composed prompt (and its signature) is rendered, so both stay switch-consistent.
        this.skillScriptToolNames = skillScript == null ? Set.of() : Set.of(SkillScriptToolConfig.TOOL_NAME);
        // The canonical fingerprint covers every field that shaped the SDK declaration, so
        // a header credential reference or timeout change forces a later Run to rebuild.
        this.mcpSignature = mcpProjection.fingerprint();
        this.skillSignature = skillProjection == null ? "" :
                (skillProjection.recovery() ? "recovery:" : "") + skillProjection.fingerprint();
        HostExecutionPlatforms.HostExecutionPlatform hostExecution = null;
        SkillScriptPlatforms.SkillScriptPlatform skillScriptPlatform = null;
        McpToolPlatforms.McpToolPlatform platform = mcpSpecs.isEmpty() ? null : McpToolPlatforms.connect(
                mcpSpecs, new TenantRef(McpRunTools.TENANT_ID),
                new PrincipalRef(AccessOwner.current(), McpRunTools.PRINCIPAL_TYPE));
        try {
            this.mcpToolNames = platform == null ? Set.of() : Set.copyOf(platform.toolNames());
            this.delegationSignature = delegation.signature(tools, this.mcpSignature, this.skillSignature, this.mcpToolNames);
            Map<ModelAdapterCoordinate, AgentChatModel> adapters = new java.util.LinkedHashMap<>();
            Map<String, ModelBinding> admittedModels = new java.util.LinkedHashMap<>();
            Map<String, ResolvedModelSnapshot> snapshots = new java.util.LinkedHashMap<>();
            admittedModels.put(binding.name(), binding);
            snapshots.put(binding.name(), binding.snapshot());
            for (var child : delegation.children()) {
                admittedModels.put(child.model().name(), child.model());
                snapshots.put(child.model().name(), child.model().snapshot());
            }
            // The public registry groups adapters by type/version. Managed configurations
            // may use the same adapter coordinate with distinct credentials; dispatch to
            // the already-frozen binding instead of reusing Parent's credential resolver.
            Map<String, ModelBinding> frozenModels = Map.copyOf(admittedModels);
            AgentChatModel configuredModels = new AgentChatModel() {
                private AgentChatModel delegate(io.haifa.agent.model.api.AgentChatRequest request) {
                    ModelBinding selected = frozenModels.get(request.model().modelId().value());
                    // Recovery registers the same managed revision with a read-only default
                    // policy. The SDK resumes with the original Run's frozen invocation
                    // options, which legitimately have a different digest.
                    // All other frozen fields must still match the credential-owning adapter.
                    if (selected == null || !matchesFrozenModelConfiguration(selected.snapshot(), request.model())) {
                        throw new IllegalArgumentException("Model request does not match an admitted configuration");
                    }
                    return selected.model();
                }
                @Override public io.haifa.agent.model.api.AgentChatResponse invoke(io.haifa.agent.model.api.AgentChatRequest request) {
                    return delegate(request).invoke(request);
                }
                @Override public io.haifa.agent.model.api.AgentChatResponse invokeStreaming(
                        io.haifa.agent.model.api.AgentChatRequest request, io.haifa.agent.model.api.ModelStreamSink sink) {
                    return delegate(request).invokeStreaming(request, sink);
                }
            };
            admittedModels.values().forEach(model -> adapters.put(ModelAdapterCoordinate.from(model.snapshot()), configuredModels));
            ModelContribution models = new ModelContribution(adapters, binding.snapshot(), snapshots);
            Set<String> allowedTools = registeredToolNames(tools, skillProjection);
            allowedTools.addAll(this.mcpToolNames);
            allowedTools.remove("task");
            // The host shell Tool is a product switch: off keeps the Tool surface and the frozen
            // lead prompt exactly as before, on adds the SDK's own execution_run alias that the
            // bridge registers below through the public SDK assembly entry.
            // A failed build closes it below.
            // The Skill script Tool is a product switch chained onto the host shell switch: off
            // keeps the Tool surface and the frozen lead prompt exactly as before, on adds the
            // declared alias through the public SDK skill script entry below.
            boolean hostShellEnabled = hostShell != null && hostShell.enabled();
            if (hostShellEnabled) {
                allowedTools.add("execution_run");
            }
            allowedTools.addAll(this.skillScriptToolNames);
            // Runtime adds its built-in task Tool from the Child authorization. It is not
            // a ToolPlatform catalog alias and must not enter ProductProfile.allowedTools.
            Set<String> promptTools = new LinkedHashSet<>(allowedTools);
            if (delegation.enabled()) promptTools.add("task");
            // The frozen lead prompt is rendered once per Bridge; its signature drives the later-Run
            // rebuild criterion in DeerFlowThreadRunApi, exactly like the MCP/Skill fingerprints.
            LeadPromptComposer.Composition prompt = composePrompt(promptTools, skillProjection, agentContext, delegation);
            this.promptSignature = prompt.signature();
            HaifaAgentBuilder builder = HaifaAgents.builder(profile(
                            binding.snapshot(), prompt.instructions(), allowedTools,
                            skillProjection == null ? Set.of() : skillProjection.allowedSkills(), delegation,
                            parentWallTimeMillisForTests))
                    .metadata(new AgentMetadata("Super Harness local compatibility agent"))
                    .callerProvider(SdkCallerProvider.defaultPublicUser())
                    .model(models)
                    .persistence(foundation.persistence())
                    .conversation(foundation.conversation())
                    .tools(tools)
                    .policy(new PolicyPlatformContribution(
                            PolicyPresets.standardApproval(), new DefaultPolicyDecisionService()));
            if (delegation.enabled()) {
                builder.childRunCapacity(childRunCapacity).nonInteractiveChildTools();
                for (var child : delegation.children()) {
                    Set<String> childTools = child.allowedTools(allowedTools);
                    Optional<EffectiveModelParameters> effectiveModelParameters = Optional.empty();
                    if (isReasoningCapableOrEnabled(child.model().snapshot())) {
                        ResolvedModelSnapshot snapshot = child.model().snapshot();
                        String profileDigest = "sha256:" + sha256Hex(delegation.fingerprint() + "\n"
                                + child.name() + "\n"
                                + snapshot.configurationDigest() + "\n"
                                + "e545c28ac301e6f9feae247c513d8ea8f83df294:thinking_enabled=False:reasoning_effort=None");
                        effectiveModelParameters = Optional.of(new EffectiveModelParameters(
                                snapshot.modelId(),
                                snapshot.modelVersion(),
                                profileDigest,
                                ModelReasoningPolicy.disabled(),
                                snapshot.maxOutputTokens(),
                                snapshot.imageInput()));
                    }
                    builder.runProfile(new ProductRunProfile(child.profileId(), "1.0.0", child.model().name(),
                            AgentRunType.CHAT, AgentRunBudget.disabled(),
                            new AgentRunLimits(child.turns(), 1, 1, child.timeoutMillis(),
                                    child.timeoutMillis(), child.turns(), Long.MAX_VALUE, 0),
                            Map.of(), effectiveModelParameters, Optional.of(childTools))
                            .withTruncatedOutputPolicy(
                                    io.haifa.agent.runtime.api.TruncatedOutputPolicy.ACCEPT_NONEMPTY_PLAIN_TEXT));
                    builder.childAgent(new ChildAgentSpec(child.name(), child.description(), child.instructions(),
                            Optional.of(new ProductRunProfileRef(child.profileId(), "1.0.0")), childTools));
                }
            }
            if (isPlanMode) {
                builder.planAuthoring(PlanAuthoringSpec.of(
                        "write_todos",
                        PlanAuthoringResources.toolDescription(),
                        PlanAuthoringResources.systemPrompt()));
            }
            // Human B1 decision: only these production read-only network tools skip the
            // standard network ASK. All other network/MCP and writing tools retain their policy.
            Set<ToolName> autoApproveReadOnlyNetwork = new LinkedHashSet<>(tools.stream()
                    .filter(tool -> tool instanceof ProductionWebSearchTool
                            || tool instanceof ProductionWebFetchTool || tool instanceof ProductionImageSearchTool)
                    .map(tool -> tool.spec().name())
                    .collect(java.util.stream.Collectors.toSet()));
            // Host-declared read_only MCP servers lower their tools to the SDK read-only network
            // preset; SH auto-approves exactly the imported aliases that actually registered.
            // A non-read-only MCP tool stays on its mandatory-approval policy.
            for (String name : mcpProjection.readOnlyToolNames()) {
                if (mcpToolNames.contains(name)) {
                    autoApproveReadOnlyNetwork.add(new ToolName(name));
                }
            }
            builder.autoApproveReadOnlyNetworkTools(autoApproveReadOnlyNetwork);
            builder.autoApproveStandardFileWriteTools(tools.stream()
                    .filter(tool -> tool instanceof ProductionSetupAgentTool || tool instanceof ProductionUpdateAgentTool)
                    .map(tool -> tool.spec().name())
                    .collect(java.util.stream.Collectors.toSet()));
            if (skillProjection != null && skillProjection.installTools()) {
                builder.skillPlatform(new SkillPlatformContribution(
                                skillProjection.catalog(), skillProjection.loader(), skillProjection.trust()))
                        .toolRegistrations(SkillToolContributions.createRegistrations(
                                foundation.persistence(), skillProjection.loader(), Instant::now));
            }
            if (hostShellEnabled) {
                // The SDK owns the host execution assembly: one Broker/Sandbox/Policy/Runtime, the
                // frozen assembly policy and the execution-aware Approval prompt. SH declares only
                // the workspace, the data root and the product timeout bounds.
                SdkCaller caller = SdkCaller.defaultPublicUser();
                hostExecution = HostExecutionPlatforms.hostGuarded(
                        new HostExecutionPlatforms.HostExecutionSpec(
                                hostShell.workspaceRoot(),
                                hostShell.dataRoot(),
                                hostShell.defaultTimeout(),
                                hostShell.maximumTimeout(),
                                hostShell.maximumOutputBytes(),
                                hostShell.maximumOutputLines()),
                        caller.tenant(),
                        caller.principal());
                // The Agent takes ownership on a successful build; a failed build closes it below.
                hostExecution.applyTo(builder);
            }
            if (skillScript != null) {
                // The SDK owns the trusted skill script assembly: its own provider/broker/sandbox/
                // policy path, the frozen package identity and the execution-aware approval prompt.
                // SH declares only the Thread workspace, the data root, the bun runtime, the product
                // timeout bounds and the bounded business argument mapping. The frozen binding is
                // the one the same skill Projection admitted, so no side channel re-derives bytes.
                SdkCaller scriptCaller = SdkCaller.defaultPublicUser();
                FrozenSkillBinding skillBinding = SkillScriptToolConfig.declaredBinding(skillProjection)
                        .orElseThrow();
                skillScriptPlatform = SkillScriptPlatforms.trusted(
                        new SkillScriptPlatforms.SkillScriptSpec(
                                skillScript.workspaceRoot(),
                                skillScript.dataRoot(),
                                List.of(new SkillScriptPlatforms.SkillScriptRuntime(
                                        SkillScriptToolConfig.SCRIPT_RUNTIME_REF, skillScript.bunExecutable())),
                                skillProjection.loader(),
                                List.of(new SkillScriptPlatforms.SkillScriptToolDeclaration(
                                        SkillScriptToolConfig.toolName(),
                                        SkillScriptToolConfig.title(),
                                        SkillScriptToolConfig.description(),
                                        SkillScriptToolConfig.inputSchema(),
                                        skillScript.maximumTimeout(),
                                        skillBinding,
                                        SkillScriptToolConfig.SCRIPT_RELATIVE_PATH,
                                        SkillScriptToolConfig.SCRIPT_RUNTIME_REF,
                                        skillScript.defaultTimeout(),
                                        SkillScriptToolConfig.purpose(),
                                        new SkillScriptToolConfig.ImageGenArguments(skillScript.provider()))),
                                skillScript.defaultTimeout(),
                                skillScript.maximumTimeout(),
                                skillScript.maximumOutputBytes(),
                                skillScript.maximumOutputLines()),
                        scriptCaller.tenant(),
                        scriptCaller.principal());
                // The Agent takes ownership on a successful build; a failed build closes it below.
                skillScriptPlatform.applyTo(builder);
            }
            if (platform != null) {
                // The SDK owns per-server connection isolation, discovery, naming, invocation
                // and release; SH only declares the projections. applyTo sets the builder's
                // credentials contribution, so SH must not set another one.
                platform.applyTo(builder);
            }
            // Optional host bootstrap: the default SDK virtual-thread executor stays in place
            // when no factory is supplied; the SDK owns the fresh executor and its closing.
            if (executionExecutorFactory != null) {
                builder.executionExecutorFactoryForTests(executionExecutorFactory);
            }
            this.agent = builder.build();
        } catch (RuntimeException | Error failure) {
            if (hostExecution != null) {
                // The Agent never took ownership, so the bridge releases the host execution platform.
                hostExecution.close();
            }
            if (skillScriptPlatform != null) {
                // The Agent never took ownership, so the bridge releases the skill script platform.
                try { skillScriptPlatform.close(); }
                catch (RuntimeException | Error closing) { failure.addSuppressed(closing); }
            }
            // The agent never took ownership, so the caller closes the connected platform.
            if (platform != null) {
                try { platform.close(); }
                catch (RuntimeException | Error closing) { failure.addSuppressed(closing); }
            }
            throw failure;
        }
    }

    public String mcpSignature() {
        return mcpSignature;
    }

    public String skillSignature() {
        return skillSignature;
    }

    public String promptSignature() {
        return promptSignature;
    }

    public String delegationSignature() { return delegationSignature; }
    public Set<String> mcpToolNames() { return mcpToolNames; }

    /** Declared Skill script Tool names this bridge registered (empty when the switch is off or a fact is missing). */
    public Set<String> skillScriptToolNames() { return skillScriptToolNames; }

    /** Tool callbacks retain the admitted identity even when two identities render the same prompt. */
    public boolean matchesAgentIdentity(AgentPromptContext candidate) {
        return java.util.Objects.equals(agentContext.agentName(), candidate.agentName())
                && agentContext.bootstrap() == candidate.bootstrap();
    }

    /** The rendered lead-agent prompt signature for a candidate declaration, without building it. */
    public static String promptSignatureFor(
            List<? extends JavaTool<?, ?>> tools, SkillRunTools.Projection skillProjection) {
        return promptSignatureFor(tools, skillProjection, AgentPromptContext.defaults());
    }

    public static String promptSignatureFor(List<? extends JavaTool<?, ?>> tools,
            SkillRunTools.Projection skillProjection, AgentPromptContext agentContext) {
        return promptSignatureFor(tools, skillProjection, agentContext, SubagentRunConfiguration.disabled());
    }

    public static String promptSignatureFor(List<? extends JavaTool<?, ?>> tools,
            SkillRunTools.Projection skillProjection, AgentPromptContext agentContext,
            SubagentRunConfiguration delegation) {
        return promptSignatureFor(tools, skillProjection, agentContext, delegation, Set.of());
    }

    public static String promptSignatureFor(List<? extends JavaTool<?, ?>> tools,
            SkillRunTools.Projection skillProjection, AgentPromptContext agentContext,
            SubagentRunConfiguration delegation, Set<String> platformToolNames) {
        Set<String> names = registeredToolNames(tools, skillProjection);
        names.addAll(platformToolNames);
        if (delegation.enabled()) names.add("task");
        return composePrompt(names, skillProjection, agentContext, delegation).signature();
    }

    private static Set<String> registeredToolNames(
            List<? extends JavaTool<?, ?>> tools, SkillRunTools.Projection skillProjection) {
        Set<String> names = tools.stream()
                .map(tool -> tool.spec().name().value())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (skillProjection != null && skillProjection.installTools()) {
            names.add("skill_load");
            names.add("skill_resource_read");
        }
        return names;
    }

    private static LeadPromptComposer.Composition composePrompt(
            Set<String> registeredTools, SkillRunTools.Projection skillProjection, AgentPromptContext agentContext,
            SubagentRunConfiguration delegation) {
        List<LeadPromptInputs.Skill> skills = skillProjection == null
                ? List.of()
                : skillProjection.selectedPackages().stream()
                        .map(pkg -> new LeadPromptInputs.Skill(pkg.name(), pkg.description()))
                        .toList();
        LeadPromptInputs inputs = agentContext.promptInputs(skills).withSubagents(
                delegation.children().stream().map(child -> new LeadPromptInputs.Subagent(
                        child.name(), child.description())).toList(), delegation.settings().parallel(), delegation.settings().total());
        return LeadPromptComposer.classpath().compose(inputs, registeredTools);
    }

    public String bindingIdentity() {
        return binding.name() + "@" + binding.revision();
    }

    public String modelConfigurationDigest() {
        return binding.snapshot().configurationDigest();
    }

    public boolean matchesModelBinding(ModelBinding candidate) {
        return bindingIdentity().equals(candidate.name() + "@" + candidate.revision())
                && binding.snapshot().configurationDigest().equals(candidate.snapshot().configurationDigest());
    }

    /** Recovery preserves SDK-owned Run options without relaxing adapter or credential identity. */
    static boolean matchesFrozenModelConfiguration(ResolvedModelSnapshot selected, ResolvedModelSnapshot request) {
        ResolvedModelSnapshot normalized = ResolvedModelSnapshot.create(
                selected.providerId(), selected.providerVersion(), selected.modelId(), selected.modelVersion(),
                selected.providerModelId(), selected.adapterType(), selected.adapterVersion(), selected.apiStyle(),
                selected.dialect(), selected.endpoint(), selected.credentialRef(), selected.nativeStreaming(),
                selected.capabilities(), selected.contextWindow(), selected.maxOutputTokens(),
                selected.providerOptions(), request.invocationOptions());
        return normalized.configurationDigest().equals(request.configurationDigest());
    }

    static boolean isReasoningCapableOrEnabled(ResolvedModelSnapshot snapshot) {
        if (snapshot.capabilities().contains(ModelCapability.REASONING)) {
            return true;
        }
        Object thinking = snapshot.invocationOptions().get("thinking");
        if (thinking != null && !"disabled".equalsIgnoreCase(String.valueOf(thinking))) {
            return true;
        }
        return snapshot.invocationOptions().containsKey("reasoning_effort")
                || snapshot.invocationOptions().containsKey("reasoning_token_budget");
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required", failure);
        }
    }

    public boolean hasTools() {
        return hasTools;
    }

    public StartedRun start(String threadId, String message, Optional<SessionState> existing) {
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

    public AgentRunSnapshot snapshot(AgentRunId runId) {
        return agent.runs().find(runId).orElseThrow();
    }

    public Optional<AgentRunSnapshot> findRun(AgentRunId runId) {
        return agent.runs().find(runId);
    }

    public boolean isPlanMode() {
        return isPlanMode;
    }

    public Optional<AgentPlanView> plan(AgentRunId runId) {
        return agent.runs().plan(runId);
    }

    /** Authoritative SDK facts; also available on a read bridge with no current declarations. */
    public List<ChildRunView> children(AgentRunId parent) { return agent.runs().children(parent); }

    public Optional<ToolCallView> runtimeToolCall(AgentRunId parent, String runtimeToolCallId) {
        return toolCalls(parent).stream().filter(call -> call.id().value().equals(runtimeToolCallId)).findFirst();
    }

    /** Exact SDK lifecycle association to the same Runtime ID used on Parent SSE/history. */
    public Optional<String> childTaskId(AgentRunId parent, AgentRunId child) {
        return Optional.ofNullable(childTaskIds(parent, children(parent)).get(child));
    }

    /** Resolve a caller-visible child batch with one bounded Parent scan and one Tool read. */
    public java.util.Map<AgentRunId, String> childTaskIds(AgentRunId parent, List<ChildRunView> children) {
        var visible = children.stream().filter(view -> view.parentRunId().equals(parent))
                .map(ChildRunView::runId).collect(java.util.stream.Collectors.toSet());
        if (visible.isEmpty()) return java.util.Map.of();
        var tasks = toolCalls(parent).stream().filter(call -> call.runId().equals(parent)
                        && "task".equals(call.toolName()))
                .map(call -> call.id().value()).collect(java.util.stream.Collectors.toSet());
        java.util.Map<AgentRunId, String> associations = new java.util.HashMap<>();
        RunEventCursor cursor = beforeFirstEvent(parent);
        for (int pages = 0; pages < 128; pages++) {
            RunEventPage page = events(parent, cursor, 256);
            for (var event : page.items()) {
                if (event.payload() instanceof io.haifa.agent.runtime.api.RunEventPayloads.ChildRunLifecycle lifecycle
                        && tasks.contains(lifecycle.toolCallId())) {
                    var child = new AgentRunId(lifecycle.childRunId());
                    if (visible.contains(child)) associations.putIfAbsent(child, lifecycle.toolCallId());
                }
            }
            if (!page.hasMore() || associations.size() == visible.size()) return java.util.Map.copyOf(associations);
            cursor = page.nextCursor();
        }
        throw new IllegalStateException("Child association exceeds the bounded event query budget");
    }

    public Optional<ConversationRecord> findConversation(AgentSessionId sessionId) {
        return agent.conversations().find(sessionId);
    }

    public ConversationTurnPage turns(AgentSessionId sessionId, ConversationTurnQuery query) {
        turnPageReads.incrementAndGet();
        return agent.conversations().turns(sessionId, query);
    }

    public void resetTurnPageReads() {
        turnPageReads.set(0);
    }

    public long turnPageReads() {
        return turnPageReads.get();
    }

    public AgentRunViewSnapshot authoritativeView(AgentRunId runId) {
        return agent.runs().view(runId).orElseThrow();
    }

    public RunEventCursor beforeFirstEvent(AgentRunId runId) {
        return RunEventCursor.beforeFirst(runId);
    }

    public RunEventPage events(AgentRunId runId, RunEventCursor cursor, int limit) {
        return agent.runs().events(runId, cursor, limit);
    }

    public io.haifa.agent.runtime.api.RunMessagePage messages(AgentRunId runId,
            io.haifa.agent.runtime.api.RunMessageCursor cursor, int limit) {
        return agent.runs().messages(runId, cursor, limit);
    }

    public io.haifa.agent.runtime.api.RunEventSubscription subscribe(AgentRunId runId,
            RunEventCursor cursor, io.haifa.agent.runtime.api.AgentRunEventListener listener) {
        return agent.runs().subscribe(runId, cursor, listener);
    }

    public io.haifa.agent.runtime.api.RunOutputSubscription subscribeOutput(AgentRunId runId,
            io.haifa.agent.runtime.api.RunOutputCursor cursor,
            io.haifa.agent.runtime.api.AgentRunOutputListener listener) {
        return agent.runs().subscribeOutput(runId, cursor, listener);
    }

    public Optional<InteractionView> pendingInteraction(AgentRunId runId) {
        return agent.runs().pendingInteraction(runId);
    }

    /** Read-only SDK prompt-layer diagnostics; never contains prompt text. */
    public PromptDiagnostics promptDiagnostics(AgentRunId runId) {
        return agent.runs().promptDiagnostics(runId);
    }

    public Optional<FrozenInstructionDiagnostic> frozenInstructionDiagnostic(AgentRunId runId) {
        return agent.runs().frozenInstructionDiagnostic(runId);
    }

    public List<ToolCallView> toolCalls(AgentRunId runId) {
        return agent.runs().toolCalls(runId);
    }

    public InteractionResponseReceipt respond(InteractionResponseSubmission submission) {
        return agent.runs().respond(submission);
    }

    public RunInputResult submitInput(RunInputCommand command) {
        return agent.runs().submitInput(command);
    }

    public boolean cancel(AgentRunId runId) {
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

    public record SessionState(AgentSessionId sessionId, long revision) {}

    public record StartedRun(AgentRunId runId, SessionState session) {}

    static ProductProfile profile(
            ResolvedModelSnapshot snapshot,
            String instructions,
            Set<String> allowedTools,
            Set<String> allowedSkills,
            SubagentRunConfiguration delegation) {
        return profile(snapshot, instructions, allowedTools, allowedSkills, delegation, null);
    }

    private static ProductProfile profile(ResolvedModelSnapshot snapshot, String instructions,
            Set<String> allowedTools, Set<String> allowedSkills, SubagentRunConfiguration delegation,
            Long parentWallTimeMillisForTests) {
        long wall = parentWallTimeMillisForTests == null ? 3_600_000 : parentWallTimeMillisForTests;
        if (parentWallTimeMillisForTests == null && delegation.enabled()) {
            long childWall = delegation.children().stream().mapToLong(SubagentRunConfiguration.Child::timeoutMillis).max().orElseThrow();
            // Directory seconds are positive int values; total is clamped to 50. Reject any
            // unsupported declaration rather than silently overflow or create an unbounded limit.
            if (childWall < 1 || childWall > Math.multiplyExact((long) Integer.MAX_VALUE, 1000L)) {
                throw new IllegalArgumentException("Child timeout is outside the supported configuration range");
            }
            wall = Math.addExact(wall, Math.multiplyExact(delegation.settings().total(), childWall));
        }
        return ProductProfile.create(
                new ProductId("haifa-super-harness"),
                new ProductVersion("1.0.0"),
                // A colon is outside the managed Child namespace. Existing disabled/recovery
                // profiles retain their historical ID; no stored Run is renamed.
                new AgentDefinitionId(delegation.enabled() ? "haifa-super-harness:lead" : "haifa-super-harness-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                instructions,
                new ProductRunProfileRef(snapshot.modelId().value(), "1.0.0"),
                // DeerFlow's default token/cost budget is disabled; only the structural
                // AgentRunLimits below constrain iterations, model calls and tool calls.
                new AgentRunBudget(0, 0, 0, 0, 0, delegation.enabled() ? delegation.settings().total() : 0, "USD", 0),
                new AgentRunLimits(100, delegation.enabled() ? 1 : 0, delegation.enabled() ? delegation.settings().parallel() : 1, wall,
                        600_000, 100, 1000, delegation.enabled() ? delegation.settings().total() : 0),
                allowedTools,
                allowedSkills).withAllowedChildAgents(delegation.names());
    }

    public record ModelBinding(
            String name,
            String revision,
            AgentChatModel model,
            ResolvedModelSnapshot snapshot) {
        public ModelBinding {
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

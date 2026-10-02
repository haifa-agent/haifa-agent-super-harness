package io.haifa.agent.superharness.server.tool.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelAdapterCoordinate;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelStreamSink;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyPresets;
import io.haifa.agent.policy.api.PolicyRule;
import io.haifa.agent.policy.api.PolicyRuleMatcher;
import io.haifa.agent.policy.api.PolicyRuleRef;
import io.haifa.agent.policy.api.PolicyRuleSet;
import io.haifa.agent.policy.api.PolicyRuleSource;
import io.haifa.agent.policy.api.PolicySideEffect;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionKind;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.sdk.api.AgentMetadata;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.api.SdkCallerProvider;
import io.haifa.agent.sdk.contribution.InMemoryConversationContribution;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.contribution.SdkContributions;
import io.haifa.agent.sdk.contribution.ToolRegistration;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.product.ProductId;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductRunProfileRef;
import io.haifa.agent.sdk.product.ProductVersion;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.tool.api.SemanticVersion;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * W11 public-consumer probe: can the published SDK declare a constrained network Tool through
 * {@link ToolRegistration} / {@link ToolDefinition} / {@link ToolProvider}, and what does the
 * standard policy actually decide for it?
 *
 * <p>Every case drives a real SDK {@code Run} through the public {@code HaifaAgents} builder rather
 * than a static policy call, because W11 forbids substituting a decision evaluation for
 * registration/approval/execution/terminal-state evidence. The network tool itself is an in-process
 * counter that returns a synthetic {@link ToolResult}; no case touches the network. The exact
 * observed outcome (ASK, DENY, fail-closed build) is printed as evidence so the controller can
 * distinguish an SDK gap from a probe bug.
 *
 * <p>The typed path rejects an unconstrained network declaration; the public networkAccess(hosts)
 * method now declares constrained hosts. Approval/execution evidence comes from actual Runs.
 */
class PublicNetworkToolRegistrationProbeTest {
    private static final String EVIDENCE_PREFIX = "W11_NETWORK_PROBE_EVIDENCE ";

    /**
     * N01 (registration half): the public {@code ToolDefinition} carries the real
     * {@code NETWORK_ACCESS} side effect and non-empty, effective constrained hosts, with the alias
     * equal to the definition name and the provider id equal to the definition provider id.
     */
    @Test
    void registeredNetworkToolDeclaresNetworkSideEffectAndConstrainedHosts() {
        AtomicInteger invocations = new AtomicInteger();
        String alias = "w11_network_probe_declaration";
        ToolRegistration registration = networkRegistration(
                alias,
                Set.of("example.com", "api.example.com"),
                ToolRisk.MEDIUM,
                ToolApprovalRequirement.POLICY,
                invocations);

        ToolDefinition definition = registration.definition();
        assertThat(registration.alias().value()).isEqualTo(alias);
        assertThat(definition.name().value()).isEqualTo(alias);
        assertThat(definition.providerId()).isEqualTo(registration.provider().id());
        assertThat(definition.sideEffects()).as("the real network side effect must be declared")
                .containsExactly(ToolSideEffect.NETWORK_ACCESS);
        assertThat(definition.resources().networkHosts()).as("the constrained hosts must not be empty")
                .containsExactlyInAnyOrder("example.com", "api.example.com");
        assertThat(definition.resources().filesystemCapabilities()).isEmpty();
        assertThat(definition.risk()).isEqualTo(ToolRisk.MEDIUM);
        assertThat(definition.inputSchema().document()).containsEntry("type", "object");
        assertThat(definition.outputSchema().document()).containsEntry("type", "object");
        assertThat(invocations).hasValue(0);
        System.out.println(EVIDENCE_PREFIX + "case=n01-declaration sideEffects="
                + definition.sideEffects() + " hosts=" + definition.resources().networkHosts()
                + " alias=" + registration.alias().value() + " providerId=" + definition.providerId());
    }

    /**
     * An unconstrained NETWORK_ACCESS declaration must continue to fail closed.
     */
    @Test
    void publishedJavaToolPathRejectsUnconstrainedNetworkAccessSideEffect() {
        assertThatThrownBy(() -> JavaToolSpec.builder(
                                "w11_network_probe_java", DeclarationInput.class, DeclarationOutput.class)
                        .description("W11 public network side-effect declaration probe")
                        .sideEffects(ToolSideEffect.NETWORK_ACCESS)
                        .build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("constrained hosts");
        var constrained = JavaToolSpec.builder("w11_network_probe_java", DeclarationInput.class, DeclarationOutput.class)
                .networkAccess("example.com").build();
        assertThat(constrained.sideEffects()).containsExactly(ToolSideEffect.NETWORK_ACCESS);
        assertThat(constrained.networkHosts()).containsExactly("example.com");
    }

    /**
     * Case 1: a registered {@code NETWORK_ACCESS} tool with constrained hosts stops at the standard
     * preset {@code ASK} (count 0), and an SDK-owned approval executes it exactly once in the same
     * Run. This is the decide/approve/execute lifecycle the W11 exemption must reuse.
     */
    @Test
    void registeredNetworkToolWithHostsAsksThenExecutesOnceAfterApproval() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        String alias = "w11_network_probe_hosts";
        ToolRegistration registration = networkRegistration(
                alias, Set.of("example.com"), ToolRisk.MEDIUM, ToolApprovalRequirement.POLICY, invocations);

        try (HaifaAgent agent = buildAgent(alias, registration, PolicyPresets.standardApproval())) {
            var started = agent.conversations()
                    .start(new StartConversationCommand("w11-net-ask", "w11-net-ask", "search the web"));
            AgentRunId runId = started.runId();

            InteractionView pending = awaitPending(agent, runId);
            assertThat(pending.kind())
                    .as("a declared NETWORK_ACCESS tool must go through an SDK-owned APPROVAL")
                    .isEqualTo(InteractionKind.APPROVAL);
            assertThat(agent.runs().find(runId).orElseThrow().status())
                    .isEqualTo(AgentRunStatus.WAITING_APPROVAL);
            assertThat(invocations)
                    .as("the standard preset must not execute the network tool before approval")
                    .hasValue(0);

            agent.runs()
                    .respond(new InteractionResponseSubmission(
                            new InteractionResponseId("w11-net-approve"),
                            pending.requestId(),
                            runId,
                            pending.revision(),
                            InteractionAction.APPROVE,
                            List.of(),
                            "w11-net-approve-key",
                            Instant.now()));

            assertThat(awaitTerminal(agent, runId).status())
                    .as("the approved network tool must complete the same Run")
                    .isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(invocations)
                    .as("the approved network tool must execute exactly once in the same Run")
                    .hasValue(1);
            System.out.println(EVIDENCE_PREFIX + "case=with-hosts outcome=ASK_THEN_APPROVED"
                    + " status=COMPLETED invocations=" + invocations.get());
        }
    }

    /**
     * Case 2: a narrow, capability-scoped {@code ALLOW} added on top of the standard preset still
     * cannot override the preset network {@code ASK}. The real Run must stay in approval with a zero
     * invocation count; the observed outcome is printed even when it disproves the oracle.
     */
    @Test
    void narrowCapabilityAllowCannotOverrideThePresetNetworkAsk() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        String alias = "w11_network_probe_narrow_allow";
        ToolRegistration registration = networkRegistration(
                alias, Set.of("example.com"), ToolRisk.MEDIUM, ToolApprovalRequirement.POLICY, invocations);

        try (HaifaAgent agent = buildAgent(alias, registration, narrowAllowPolicy(alias))) {
            var started = agent.conversations()
                    .start(new StartConversationCommand("w11-net-narrow", "w11-net-narrow", "search the web"));
            AgentRunId runId = started.runId();

            InteractionView pending = awaitPending(agent, runId);
            assertThat(pending.kind()).isEqualTo(InteractionKind.APPROVAL);
            assertThat(agent.runs().find(runId).orElseThrow().status())
                    .as("a narrow capability ALLOW must not lift the preset NETWORK_ACCESS ASK")
                    .isEqualTo(AgentRunStatus.WAITING_APPROVAL);
            assertThat(invocations)
                    .as("the narrow ALLOW must not let the network tool execute without approval")
                    .hasValue(0);
            System.out.println(EVIDENCE_PREFIX + "case=narrow-allow outcome=STILL_ASK"
                    + " status=WAITING_APPROVAL invocations=" + invocations.get());
        }
    }

    /**
     * Case 3 (N04 negative): a declared {@code NETWORK_ACCESS} tool whose constrained host set is
     * empty must fail closed and never execute. Either registration/build fails or the Run denies;
     * a mere {@code ASK} is not N04's DENY and makes this case fail.
     */
    @Test
    void registeredNetworkToolWithoutHostsFailsClosedAndNeverExecutes() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        String alias = "w11_network_probe_no_host";
        ToolRegistration registration = networkRegistration(
                alias, Set.of(), ToolRisk.MEDIUM, ToolApprovalRequirement.POLICY, invocations);
        assertNetworkToolCannotExecute(alias, registration, invocations, "no-host");
    }

    /**
     * Case 4 (N04 negative): a {@code CRITICAL}-risk network tool must still be denied even when its
     * definition asks for {@code NEVER} approval. Either registration/build fails or the Run denies;
     * a mere {@code ASK} makes this case fail.
     */
    @Test
    void criticalNetworkToolIsDeniedEvenWhenApprovalIsNeverRequired() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        String alias = "w11_network_probe_critical";
        ToolRegistration registration = networkRegistration(
                alias,
                Set.of("example.com"),
                ToolRisk.CRITICAL,
                ToolApprovalRequirement.NEVER,
                invocations);
        assertNetworkToolCannotExecute(alias, registration, invocations, "critical-never");
    }

    // ---- shared registration assembly -----------------------------------------------------------

    /**
     * Builds the smallest public {@link ToolRegistration} that declares {@code NETWORK_ACCESS} with
     * the given constrained hosts. The provider is an in-process counter, so no case touches the
     * network; the alias equals the definition name and the provider id equals the definition
     * provider id, as the public registration contract requires.
     */
    private static ToolRegistration networkRegistration(
            String name,
            Set<String> networkHosts,
            ToolRisk risk,
            ToolApprovalRequirement approval,
            AtomicInteger invocations) {
        ToolProviderId providerId = new ToolProviderId("w11-provider-" + name);
        ToolDefinition definition = new ToolDefinition(
                new ToolName(name),
                new SemanticVersion("1.0.0"),
                providerId,
                "W11 network probe",
                "Read-only network probe used to characterise the public SDK policy",
                new ToolSchema(name + "-input", "1.0.0", Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object")),
                new ToolSchema(name + "-output", "1.0.0", Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object")),
                ToolExecutionMode.IN_PROCESS,
                false,
                Duration.ofSeconds(5),
                "single",
                ToolIdempotency.UNKNOWN,
                risk,
                Set.of(ToolSideEffect.NETWORK_ACCESS),
                new ToolResourceRequirements(Set.of(), networkHosts, Set.of()),
                List.of(),
                approval,
                "w11-network-probe",
                false,
                Set.of());
        ToolProvider provider = new CountingNetworkProvider(providerId, invocations);
        return new ToolRegistration(
                new ToolAlias(name), definition, providerId.value(), provider);
    }

    /**
     * The narrow ALLOW is the only rule added on top of the standard preset: a user rule for the
     * exact capability, operation and resource type with a very high priority that grants
     * {@code NETWORK_ACCESS}. If the preset {@code ASK} still wins, the combination semantics work as
     * the W11 gap describes; if not, the real Run records the actual result.
     */
    private static PolicyRuleSet narrowAllowPolicy(String capability) {
        PolicyRuleSet standard = PolicyPresets.standardApproval();
        PolicyRule allow = new PolicyRule(
                new PolicyRuleRef("w11-narrow-network-allow", "1.0.0"),
                PolicyRuleSource.USER,
                1000,
                new PolicyRuleMatcher(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(capability),
                        Optional.of("invoke"),
                        Optional.of("tool"),
                        Optional.empty(),
                        Set.of(PolicySideEffect.NETWORK_ACCESS)),
                PolicyEffect.ALLOW,
                Optional.empty(),
                "W11_NARROW_NETWORK_ALLOW",
                "Narrow allow for the configured read-only web tool");
        return PolicyRuleSet.of(
                Stream.concat(standard.rules().stream(), Stream.of(allow)).toList(),
                standard.defaultRule(),
                standard.approvalMode());
    }

    /**
     * Assembles a real in-memory SDK agent whose only offered Tool is the given registration. Mirrors
     * the public {@code HaifaAgents.builder} assembly already used by production
     * {@code HaifaRunBridge}.
     */
    private static HaifaAgent buildAgent(String alias, ToolRegistration registration, PolicyRuleSet policy) {
        ResolvedModelSnapshot snapshot = modelSnapshot();
        AgentChatModel model = new ToolCallingModel(alias, Map.of("query", "haifa"));
        ModelContribution models = new ModelContribution(
                Map.of(ModelAdapterCoordinate.from(snapshot), model),
                snapshot,
                Map.of(snapshot.modelId().value(), snapshot));
        ProductProfile profile = ProductProfile.create(
                new ProductId("haifa-super-harness"),
                new ProductVersion("1.0.0"),
                new AgentDefinitionId("haifa-super-harness-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                "Return a concise answer to the local user's request.",
                new ProductRunProfileRef(snapshot.modelId().value(), "1.0.0"),
                new AgentRunBudget(100_000, 100_000, 100_000, 8, 8, 4, "USD", 1_000),
                new AgentRunLimits(16, 0, 1, 120_000, 60_000, 16, 16, 0),
                Set.of(alias),
                Set.of());
        return HaifaAgents.builder(profile)
                .metadata(new AgentMetadata("W11 public network registration probe"))
                .callerProvider(SdkCallerProvider.defaultPublicUser())
                .model(models)
                .persistence(SdkContributions.inMemoryPersistence())
                .conversation(new InMemoryConversationContribution())
                .policy(new PolicyPlatformContribution(policy, new DefaultPolicyDecisionService()))
                .toolRegistrations(List.of(registration))
                .build();
    }

    /**
     * N04 helper: the tool must never execute, and the outcome must be a denial or a fail-closed
     * build rather than an {@code ASK}. Accepting an {@code ASK} would let a forbidden tool be
     * approved, which is exactly what N04 forbids, so it is asserted against.
     */
    private static void assertNetworkToolCannotExecute(
            String alias, ToolRegistration registration, AtomicInteger invocations, String scenario)
            throws Exception {
        try (HaifaAgent agent = buildAgent(alias, registration, PolicyPresets.standardApproval())) {
            var started = agent.conversations()
                    .start(new StartConversationCommand(
                            "w11-" + scenario, "w11-" + scenario, "search the web"));
            AgentRunId runId = started.runId();
            RunOutcome outcome = awaitOutcome(agent, runId);
            assertThat(invocations)
                    .as("a forbidden network tool must never execute (" + scenario + "): " + outcome)
                    .hasValue(0);
            assertThat(outcome.pendingApproval())
                    .as("a forbidden network tool must fail closed (DENY), not merely ASK ("
                            + scenario + "): " + outcome)
                    .isFalse();
            assertThat(agent.runs().toolCalls(runId)).hasSize(1);
            assertThat(agent.runs().toolCalls(runId).getFirst().status()).isEqualTo(ToolCallStatus.DENIED);
            System.out.println(EVIDENCE_PREFIX + "case=" + scenario + " run=" + runId.value()
                    + " tool_status=DENIED outcome=" + outcome);
        }
    }

    private static RunOutcome awaitOutcome(HaifaAgent agent, AgentRunId runId) throws InterruptedException {
        for (int attempt = 0; attempt < 400; attempt++) {
            AgentRunSnapshot snapshot = agent.runs().find(runId).orElseThrow();
            if (snapshot.status().isTerminal()) {
                return new RunOutcome(snapshot.status().name(), false);
            }
            if (agent.runs().pendingInteraction(runId).isPresent()) {
                return new RunOutcome(snapshot.status().name(), true);
            }
            Thread.sleep(25);
        }
        AgentRunSnapshot snapshot = agent.runs().find(runId).orElseThrow();
        return new RunOutcome(snapshot.status().name(), agent.runs().pendingInteraction(runId).isPresent());
    }

    private static InteractionView awaitPending(HaifaAgent agent, AgentRunId runId)
            throws InterruptedException {
        for (int attempt = 0; attempt < 400; attempt++) {
            var pending = agent.runs().pendingInteraction(runId);
            if (pending.isPresent()) {
                return pending.orElseThrow();
            }
            Thread.sleep(25);
        }
        throw new AssertionError("the public SDK did not expose a pending approval; snapshot="
                + agent.runs().find(runId).orElseThrow());
    }

    private static AgentRunSnapshot awaitTerminal(HaifaAgent agent, AgentRunId runId)
            throws InterruptedException {
        for (int attempt = 0; attempt < 400; attempt++) {
            AgentRunSnapshot snapshot = agent.runs().find(runId).orElseThrow();
            if (snapshot.status().isTerminal()) {
                return snapshot;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("the Run did not settle; snapshot=" + agent.runs().find(runId).orElseThrow());
    }

    private static ResolvedModelSnapshot modelSnapshot() {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("w11-network-probe"),
                "1.0.0",
                new ModelDefinitionId("w11-network-probe"),
                "1.0.0",
                "w11-network-probe",
                "w11-network-probe-adapter",
                "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                URI.create("https://model.invalid"),
                new CredentialRef("env://SUPER_HARNESS_W11_NETWORK_PROBE"),
                false,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                8_192,
                1_024,
                Map.of(),
                Map.of());
    }

    private record RunOutcome(String status, boolean pendingApproval) {}

    public record DeclarationInput(String value) {}

    public record DeclarationOutput(String status) {}

    /** In-process counter: proves execution without any real network access. */
    static final class CountingNetworkProvider implements ToolProvider {
        private final ToolProviderId id;
        private final AtomicInteger invocations;

        CountingNetworkProvider(ToolProviderId id, AtomicInteger invocations) {
            this.id = id;
            this.invocations = invocations;
        }

        @Override
        public ToolProviderId id() {
            return id;
        }

        @Override
        public ToolResult invoke(ToolInvocationRequest request) {
            request.observer().dispatched();
            invocations.incrementAndGet();
            request.observer().acknowledged();
            return new ToolResult(
                    true, "w11 network probe executed", Map.of("body", "probe-ok"), List.of(), List.of(), false);
        }
    }

    /** Deterministic two-call model: request the tool once, then answer after its result. */
    static final class ToolCallingModel implements AgentChatModel {
        private final String toolName;
        private final Map<String, Object> arguments;
        private final AtomicInteger calls = new AtomicInteger();

        ToolCallingModel(String toolName, Map<String, Object> arguments) {
            this.toolName = toolName;
            this.arguments = arguments;
        }

        @Override
        public AgentChatResponse invoke(AgentChatRequest request) {
            if (calls.getAndIncrement() == 0) {
                return new AgentChatResponse(
                        "w11-tool-call",
                        request.model().providerModelId(),
                        "",
                        List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("w11-call-1"), toolName, arguments)),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unpriced(1, 1),
                        "",
                        Map.of());
            }
            return new AgentChatResponse(
                    "w11-final",
                    request.model().providerModelId(),
                    "tool resolved",
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
}

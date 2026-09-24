package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
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
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionInputType;
import io.haifa.agent.runtime.api.InteractionKind;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseReceiptStatus;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.starter.HaifaAgentStarter;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Test-only proof that the published public SDK can own an interaction from request through
 * response without a Super Harness interaction state machine.
 */
public class HaifaPublicInteractionSpikeTest {
    @Test
    void publicSdkApprovalWaitsRespondsAndCompletesTheSameRun() throws Exception {
        AtomicInteger toolInvocations = new AtomicInteger();
        try (var agent = HaifaAgentStarter.builder()
                .name("public-interaction-spike")
                .instructions("Call the offered tool, then report its result.")
                .model(new ApprovalProbeModel(), modelSnapshot())
                .defaultModel("interaction-probe")
                .tool(new MutatingProbeTool(toolInvocations))
                .build()) {
            var started = agent.conversations()
                    .start(new StartConversationCommand(
                            "interaction-spike-idempotency", "interaction-spike", "perform the change"));
            var runId = started.runId();

            var pending = awaitPendingInteraction(agent, runId, toolInvocations);
            assertThat(agent.runs().find(runId).orElseThrow().status())
                    .isIn(AgentRunStatus.WAITING_INTERACTION, AgentRunStatus.WAITING_APPROVAL);
            assertThat(pending.runId()).isEqualTo(runId);
            assertThat(pending.kind()).isEqualTo(InteractionKind.APPROVAL);
            assertThat(pending.state()).isEqualTo(InteractionState.PENDING);
            assertThat(pending.inputContract().type()).isEqualTo(InteractionInputType.NONE);
            assertThat(pending.allowedActions()).contains(InteractionAction.APPROVE, InteractionAction.REJECT);
            assertThat(toolInvocations).hasValue(0);

            assertThatThrownBy(() -> agent.runs()
                            .respond(new InteractionResponseSubmission(
                                    new InteractionResponseId("interaction-spike-stale-response"),
                                    pending.requestId(),
                                    runId,
                                    pending.revision() + 1,
                                    InteractionAction.APPROVE,
                                    List.of(),
                                    "interaction-spike-stale-idempotency",
                                    Instant.now())))
                    .isInstanceOf(RuntimeException.class);
            assertThat(agent.runs().pendingInteraction(runId)).contains(pending);
            assertThat(toolInvocations).hasValue(0);

            var submission = new InteractionResponseSubmission(
                    new InteractionResponseId("interaction-spike-response"),
                    pending.requestId(),
                    runId,
                    pending.revision(),
                    InteractionAction.APPROVE,
                    List.of(),
                    "interaction-spike-response-idempotency",
                    Instant.now());
            var receipt = agent.runs().respond(submission);

            assertThat(receipt.status())
                    .isIn(
                            InteractionResponseReceiptStatus.NEWLY_ACCEPTED,
                            InteractionResponseReceiptStatus.ACCEPTED_PENDING_APPLICATION,
                            InteractionResponseReceiptStatus.APPLIED);
            assertThat(receipt.runId()).isEqualTo(runId);
            assertThat(agent.runs().await(runId).status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(toolInvocations).hasValue(1);
            assertThat(agent.runs().pendingInteraction(runId)).isEmpty();
            assertThat(agent.runs().respond(submission).status())
                    .isEqualTo(InteractionResponseReceiptStatus.DUPLICATE);
        }
    }

    private static io.haifa.agent.runtime.api.InteractionView awaitPendingInteraction(
            io.haifa.agent.sdk.api.HaifaAgent agent,
            io.haifa.agent.core.run.AgentRunId runId,
            AtomicInteger toolInvocations)
            throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            var pending = agent.runs().pendingInteraction(runId);
            if (pending.isPresent()) {
                return pending.orElseThrow();
            }
            Thread.sleep(25);
        }
        throw new AssertionError(
                "public SDK did not expose a pending interaction; snapshot="
                        + agent.runs().find(runId).orElseThrow()
                        + ", toolInvocations="
                        + toolInvocations.get());
    }

    static ResolvedModelSnapshot modelSnapshot() {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("interaction-probe"),
                "1.0.0",
                new ModelDefinitionId("interaction-probe"),
                "1.0.0",
                "interaction-probe",
                "interaction-probe-adapter",
                "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                URI.create("https://model.invalid"),
                new CredentialRef("env://SUPER_HARNESS_INTERACTION_PROBE"),
                false,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                8_192,
                1_024,
                Map.of(),
                Map.of());
    }

    static final class ApprovalProbeModel implements AgentChatModel {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public AgentChatResponse invoke(AgentChatRequest request) {
            if (calls.getAndIncrement() == 0) {
                return new AgentChatResponse(
                        "interaction-probe-tool-call",
                        request.model().providerModelId(),
                        "",
                        List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("interaction-probe-call"),
                                "mutating_probe",
                                Map.of("value", "approved-value"))),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unpriced(1, 1),
                        "",
                        Map.of());
            }
            return new AgentChatResponse(
                    "interaction-probe-final",
                    request.model().providerModelId(),
                    "interaction resolved",
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

    public record MutatingProbeInput(String value) {}

    public record MutatingProbeOutput(String status) {}

    static final class MutatingProbeTool
            implements JavaTool<MutatingProbeInput, MutatingProbeOutput> {
        private static final JavaToolSpec<MutatingProbeInput, MutatingProbeOutput> SPEC =
                JavaToolSpec.builder(
                                "mutating_probe", MutatingProbeInput.class, MutatingProbeOutput.class)
                        .description("Test-only mutation whose policy requires approval")
                        .sideEffects(ToolSideEffect.EXTERNAL_SYSTEM_MUTATION)
                        .build();

        private final AtomicInteger invocations;

        MutatingProbeTool(AtomicInteger invocations) {
            this.invocations = invocations;
        }

        @Override
        public JavaToolSpec<MutatingProbeInput, MutatingProbeOutput> spec() {
            return SPEC;
        }

        @Override
        public MutatingProbeOutput invoke(MutatingProbeInput input, JavaToolContext context) {
            invocations.incrementAndGet();
            return new MutatingProbeOutput("applied:" + input.value());
        }
    }
}

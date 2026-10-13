package io.haifa.agent.superharness.server.api;

import static io.haifa.agent.superharness.server.testsupport.GatewayFixture.JSON;
import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunStatus;
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
import io.haifa.agent.model.api.ModelStreamEvent;
import io.haifa.agent.model.api.ModelStreamSink;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.policy.api.PolicyPresets;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEventType;
import io.haifa.agent.runtime.api.AgentRunOutputListener;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.RunOutputCursor;
import io.haifa.agent.runtime.api.RunOutputSubscription;
import io.haifa.agent.sdk.api.AgentMetadata;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.api.SdkCallerProvider;
import io.haifa.agent.sdk.contribution.InMemoryConversationContribution;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.contribution.SdkContributions;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.product.ProductId;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductRunProfileRef;
import io.haifa.agent.sdk.product.ProductVersion;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * W05 LIVE02 probe: the published SDK {@code subscribeOutput} must deliver the first assistant
 * text while the model invocation is still blocked mid-stream, before the model call ends. Uses
 * the same in-memory public SDK assembly as {@code PublicNetworkToolRegistrationProbeTest} with
 * native streaming forced on and no Tool.
 */
@Timeout(90)
class W05PublicOutputStreamingProbeTest {
    private static final String FIRST_TEXT = "W05_FIRST";
    private static final String SECOND_TEXT = "W05_SECOND";
    private static final String REASONING_MARKER = "private-fixture-marker";

    @Test
    void firstAssistantTextArrivesBeforeModelInvocationEnds() throws Exception {
        LiveStreamingModel liveModel = new LiveStreamingModel();
        try (HaifaAgent agent = buildAgent(liveModel)) {
            try {
                var started = agent.conversations().start(new StartConversationCommand(
                        "w05-live-text-probe", "W05 live text streaming probe", "produce one live streamed answer"));
                AgentRunId runId = started.runId();

                OutputCollector stream = new OutputCollector();
                try (RunOutputSubscription subscription =
                        agent.runs().subscribeOutput(runId, RunOutputCursor.BEFORE_FIRST, stream)) {
                    assertThat(subscription).isNotNull();

                    assertThat(stream.firstPublicText.await(15, TimeUnit.SECONDS))
                            .as("first assistant text must stream before the model invocation ends")
                            .isTrue();

                    assertThat(liveModel.releaseMidStream.getCount())
                            .as("model must still be blocked at mid-stream when first text arrives")
                            .isEqualTo(1);
                    AgentRunSnapshot midSnapshot = agent.runs().find(runId).orElseThrow();
                    assertThat(midSnapshot.status().isTerminal())
                            .as("run must still be non-terminal while blocked; actual=" + midSnapshot.status())
                            .isFalse();

                    List<String> midDeltas = stream.assistantTextDeltas();
                    assertThat(midDeltas).as("first public text delta").contains(FIRST_TEXT);
                    assertThat(String.join("", midDeltas)).as("second chunk withheld before release")
                            .doesNotContain(SECOND_TEXT);
                    assertThat(stream.allTextDeltasJoined())
                            .as("model reasoning must never surface as public text")
                            .doesNotContain(REASONING_MARKER);

                    liveModel.releaseMidStream.countDown();
                    assertThat(agent.runs().await(runId, Duration.ofSeconds(60)))
                            .as("run must settle after the release latch")
                            .isPresent()
                            .get()
                            .satisfies(snapshot -> assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED));

                    assertThat(stream.assistantTextDeltas()).as("final streamed deltas")
                            .containsExactly(FIRST_TEXT, SECOND_TEXT);
                    assertThat(stream.events.stream()
                            .anyMatch(event -> event.type() == AgentRunOutputEventType.ASSISTANT_TEXT_COMMITTED))
                            .as("public stream must carry the actual committed output event")
                            .isTrue();
                    assertThat(String.join("", stream.assistantTextDeltas()))
                            .as("no reasoning marker may appear as public text at any point")
                            .doesNotContain(REASONING_MARKER);

                    writeProbeSummary(agent, runId, stream);
                }
            } finally {
                liveModel.releaseMidStream.countDown();
            }
        }
    }

    @Test
    void boundedReplayExposesTheActualEvictedPrefixBeforeCommit() throws Exception {
        LiveStreamingModel liveModel = new LiveStreamingModel(2_049);
        try (HaifaAgent agent = buildAgent(liveModel)) {
            try {
                AgentRunId runId = agent.conversations().start(new StartConversationCommand(
                        "w05-retained-output", "Actual retained output", "produce one bounded burst")).runId();
                OutputCollector original = new OutputCollector();
                try (RunOutputSubscription first = agent.runs().subscribeOutput(
                        runId, RunOutputCursor.BEFORE_FIRST, original)) {
                    assertThat(first).isNotNull();
                    assertThat(original.firstPublicText.await(15, TimeUnit.SECONDS)).isTrue();
                    liveModel.releaseMidStream.countDown();
                    assertThat(liveModel.burstReady.await(15, TimeUnit.SECONDS)).isTrue();
                    assertThat(liveModel.releaseCompletion.getCount()).isEqualTo(1);
                    assertThat(agent.runs().find(runId).orElseThrow().status().isTerminal()).isFalse();
                    var retained = agent.runs().outputEvents(runId, RunOutputCursor.BEFORE_FIRST, 10_000);
                    assertThat(retained).isNotEmpty().hasSizeLessThanOrEqualTo(2_048);
                    assertThat(retained.stream().map(AgentRunOutputEvent::type).toList())
                            .contains(AgentRunOutputEventType.ASSISTANT_TEXT_DELTA)
                            .doesNotContain(AgentRunOutputEventType.RUN_OUTPUT_STARTED,
                                    AgentRunOutputEventType.ASSISTANT_TEXT_COMMITTED);
                    assertThat(retained.stream().map(AgentRunOutputEvent::textDelta).toList())
                            .doesNotContain(FIRST_TEXT);
                    var firstEvent = original.events.stream().filter(event ->
                            event.type() == AgentRunOutputEventType.RUN_OUTPUT_STARTED).findFirst().orElseThrow();
                    assertThat(retained).allSatisfy(event -> {
                        assertThat(event.runId()).isEqualTo(runId);
                        assertThat(event.modelCallId()).isEqualTo(firstEvent.modelCallId());
                        assertThat(event.generationId()).isEqualTo(firstEvent.generationId());
                    });
                    assertThat(retained.getFirst().sequence()).isGreaterThan(firstEvent.sequence());
                    assertThat(original.assistantTextDeltas()).hasSize(2_050);
                    assertThat(String.join("", original.assistantTextDeltas()))
                            .isEqualTo(FIRST_TEXT + "x".repeat(2_049));
                    OutputCollector late = new OutputCollector();
                    try (RunOutputSubscription replay = agent.runs().subscribeOutput(
                            runId, RunOutputCursor.BEFORE_FIRST, late)) {
                        assertThat(replay).isNotNull();
                        assertThat(late.events).containsExactlyElementsOf(retained);
                        assertThat(late.assistantTextDeltas()).doesNotContain(FIRST_TEXT);
                        liveModel.releaseCompletion.countDown();
                        assertThat(agent.runs().await(runId, Duration.ofSeconds(60))).isPresent().get()
                                .satisfies(snapshot -> assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED));
                        assertThat(original.events.stream().map(AgentRunOutputEvent::type).toList())
                                .contains(AgentRunOutputEventType.ASSISTANT_TEXT_COMMITTED);
                        assertThat(late.events.stream().map(AgentRunOutputEvent::type).toList())
                                .contains(AgentRunOutputEventType.ASSISTANT_TEXT_COMMITTED);
                        assertThat(String.join("", original.assistantTextDeltas()))
                                .isEqualTo(FIRST_TEXT + "x".repeat(2_049) + SECOND_TEXT);
                        var proof = JSON.createObjectNode().put("run_id", runId.value())
                                .put("model_call_id", firstEvent.modelCallId())
                                .put("generation_id", firstEvent.generationId())
                                .put("retained_count_before_commit", retained.size())
                                .put("retained_first_sequence", retained.getFirst().sequence())
                                .put("original_started_sequence", firstEvent.sequence())
                                .put("retained_started", false).put("retained_prefix", false)
                                .put("original_complete_text", String.join("", original.assistantTextDeltas()))
                                .put("late_text", String.join("", late.assistantTextDeltas()));
                        Files.writeString(GatewayFixture.evidenceDirectory().resolve(
                                "w05-actual-retained-output.json"), JSON.writeValueAsString(proof));
                    }
                }
            } finally {
                liveModel.releaseMidStream.countDown();
                liveModel.releaseCompletion.countDown();
            }
        }
    }

    private void writeProbeSummary(HaifaAgent agent, AgentRunId runId, OutputCollector stream) throws Exception {
        var durableEventsPage = agent.runs().events(runId, RunEventCursor.beforeFirst(runId), 200);
        assertThat(durableEventsPage.hasMore())
                .as("a single two-chunk call must fit in one durable events page of 200")
                .isFalse();

        Map<String, Integer> durableEventTypes = new LinkedHashMap<>();
        Map<String, Integer> durablePayloadClasses = new LinkedHashMap<>();
        int durableAssistantTextDeltas = 0;
        for (AgentRunEvent event : durableEventsPage.items()) {
            durableEventTypes.merge(event.eventType(), 1, Integer::sum);
            durablePayloadClasses.merge(event.payload().getClass().getSimpleName(), 1, Integer::sum);
            if (event.payload() instanceof RunEventPayloads.AssistantTextDelta) {
                durableAssistantTextDeltas++;
            }
        }

        Map<AgentRunOutputEventType, Integer> outputTypes = new LinkedHashMap<>();
        for (AgentRunOutputEvent event : stream.events) {
            outputTypes.merge(event.type(), 1, Integer::sum);
        }

        var summary = JSON.createObjectNode()
                .put("run_id", runId.value())
                .put("streamed_text", String.join("", stream.assistantTextDeltas()))
                .put("output_event_count", stream.events.size())
                .put("durable_assistant_text_delta_events", durableAssistantTextDeltas);
        var outputTypesNode = summary.putArray("output_event_types");
        outputTypes.forEach((type, count) ->
                outputTypesNode.addObject().put("type", type.name()).put("count", count));
        var durableTypesNode = summary.putArray("durable_event_types");
        durableEventTypes.forEach((type, count) ->
                durableTypesNode.addObject().put("event_type", type).put("count", count));
        var payloadClassesNode = summary.putArray("durable_payload_classes");
        durablePayloadClasses.forEach((name, count) ->
                payloadClassesNode.addObject().put("payload_class", name).put("count", count));

        var streamIdentityNode = summary.putArray("output_stream_sequence_identity");
        for (AgentRunOutputEvent event : stream.events) {
            streamIdentityNode.addObject()
                    .put("type", event.type().name())
                    .put("model_call_id", event.modelCallId())
                    .put("generation_id", event.generationId())
                    .put("physical_attempt", event.physicalAttempt())
                    .put("sequence", event.sequence())
                    .put("occurred_at", event.occurredAt().toString());
        }

        Path evidence = io.haifa.agent.superharness.server.testsupport.GatewayFixture.evidenceDirectory();
        Files.writeString(evidence.resolve("w05-public-output-probe.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n");
    }

    /** Collects the public output stream; counts down on the first streaming assistant text. */
    private static final class OutputCollector implements AgentRunOutputListener {
        private final CountDownLatch firstPublicText = new CountDownLatch(1);
        private final List<AgentRunOutputEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onOutput(AgentRunOutputEvent event) {
            events.add(event);
            if (event.type() == AgentRunOutputEventType.ASSISTANT_TEXT_DELTA
                    && FIRST_TEXT.equals(event.textDelta())) {
                firstPublicText.countDown();
            }
        }

        List<String> assistantTextDeltas() {
            return events.stream()
                    .filter(event -> event.type() == AgentRunOutputEventType.ASSISTANT_TEXT_DELTA)
                    .map(AgentRunOutputEvent::textDelta)
                    .toList();
        }

        String allTextDeltasJoined() {
            StringBuilder joined = new StringBuilder();
            for (AgentRunOutputEvent event : events) {
                if (event.textDelta() != null) {
                    joined.append(event.textDelta());
                }
            }
            return joined.toString();
        }
    }

    private static HaifaAgent buildAgent(AgentChatModel model) {
        ResolvedModelSnapshot snapshot = modelSnapshot();
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
                Set.of(),
                Set.of());
        return HaifaAgents.builder(profile)
                .metadata(new AgentMetadata("W05 public output streaming probe"))
                .callerProvider(SdkCallerProvider.defaultPublicUser())
                .model(models)
                .persistence(SdkContributions.inMemoryPersistence())
                .conversation(new InMemoryConversationContribution())
                .policy(new PolicyPlatformContribution(
                        PolicyPresets.standardApproval(), new DefaultPolicyDecisionService()))
                .build();
    }

    private static ResolvedModelSnapshot modelSnapshot() {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("w05-live-text-probe"),
                "1.0.0",
                new ModelDefinitionId("w05-live-text-probe"),
                "1.0.0",
                "w05-live-text-probe",
                "w05-live-text-probe-adapter",
                "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                URI.create("https://model.invalid"),
                new CredentialRef("env://SUPER_HARNESS_W05_LIVE_TEXT_PROBE"),
                true,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                8_192,
                1_024,
                Map.of(),
                Map.of());
    }

    /** Streaming fixture: delivers the first public chunk, then blocks mid-stream until released. */
    private static final class LiveStreamingModel implements AgentChatModel {
        private final CountDownLatch releaseMidStream = new CountDownLatch(1);
        private final int retentionBurst;
        private final CountDownLatch burstReady = new CountDownLatch(1);
        private final CountDownLatch releaseCompletion = new CountDownLatch(1);

        LiveStreamingModel() { this(0); }
        LiveStreamingModel(int retentionBurst) { this.retentionBurst = retentionBurst; }


        @Override
        public AgentChatResponse invoke(AgentChatRequest request) {
            throw new UnsupportedOperationException("invokeStreaming expected; blocking invoke was used");
        }

        @Override
        public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
            sink.emit(new ModelStreamEvent.ContentDelta(request.callId(), 1, FIRST_TEXT));
            sink.emit(new ModelStreamEvent.ReasoningDelta(request.callId(), 2, REASONING_MARKER));
            try {
                if (!releaseMidStream.await(60, TimeUnit.SECONDS)) {
                    throw new AssertionError(
                            "mid-stream release latch was not released within 60s at sequence 3; "
                                    + "second chunk withheld");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("mid-stream release await was interrupted", interrupted);
            }
            if (retentionBurst > 0) {
                for (int index = 0; index < retentionBurst; index++) {
                    sink.emit(new ModelStreamEvent.ContentDelta(request.callId(), 3L + index, "x"));
                }
                burstReady.countDown();
                try {
                    if (!releaseCompletion.await(60, TimeUnit.SECONDS)) {
                        throw new AssertionError("retained output fixture never released completion");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            sink.emit(new ModelStreamEvent.ContentDelta(request.callId(), 3L + retentionBurst, SECOND_TEXT));
            return new AgentChatResponse(
                    "w05-live-text-probe-final",
                    request.model().providerModelId(),
                    FIRST_TEXT + "x".repeat(retentionBurst) + SECOND_TEXT,
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(1, 1),
                    "",
                    Map.of());
        }
    }
}

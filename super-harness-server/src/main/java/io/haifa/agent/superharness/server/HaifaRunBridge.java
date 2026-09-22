package io.haifa.agent.superharness.server;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelStreamControl;
import io.haifa.agent.model.api.ModelStreamEvent;
import io.haifa.agent.model.api.ModelStreamSink;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.RunOutputCursor;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.conversation.SubmitConversationTurnCommand;
import io.haifa.agent.starter.HaifaAgentStarter;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Adapter that exposes only public Haifa Agent 0.1.1 SDK types to the HTTP boundary. */
final class HaifaRunBridge implements AutoCloseable {
    private final HaifaAgent agent;

    HaifaRunBridge(AgentChatModel model) {
        this.agent = HaifaAgentStarter.builder()
                .name("Super Harness local compatibility agent")
                .instructions("Return a concise answer to the local user's request.")
                .model(model, localSnapshot())
                .build();
    }

    static HaifaRunBridge localProbe() {
        return new HaifaRunBridge(new LocalProbeModel());
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

    List<AgentRunOutputEvent> outputAfter(AgentRunId runId, long exclusiveSequence) {
        return agent.runs().outputEvents(runId, new RunOutputCursor(exclusiveSequence), 100);
    }

    AgentRunSnapshot snapshot(AgentRunId runId) {
        return agent.runs().find(runId).orElseThrow();
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

    private static ResolvedModelSnapshot localSnapshot() {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("super-harness-local-probe"),
                "1.0.0",
                new ModelDefinitionId("super-harness-local-probe"),
                "1.0.0",
                "super-harness-local-probe",
                "super-harness-local-probe-adapter",
                "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                URI.create("https://model.invalid"),
                new CredentialRef("env://SUPER_HARNESS_LOCAL_PROBE"),
                false,
                Set.of(ModelCapability.TEXT_CHAT),
                8_192,
                1_024,
                Map.of(),
                Map.of());
    }

    /**
     * Bounded local model used only to prove the SDK run bridge before model-catalog wiring exists.
     * The run, event journal and cancellation are real Haifa runtime operations; W02 remains open for
     * configured provider execution.
     */
    private static final class LocalProbeModel implements AgentChatModel {
        @Override
        public AgentChatResponse invoke(io.haifa.agent.model.api.AgentChatRequest request) {
            return response(request, answer(request));
        }

        @Override
        public AgentChatResponse invokeStreaming(
                io.haifa.agent.model.api.AgentChatRequest request, ModelStreamSink sink) {
            String input = lastUserText(request);
            if (input.equals("__fail__")) {
                throw new IllegalStateException("requested local probe failure");
            }
            String answer = "Haifa Agent SDK: " + input;
            int chunks = input.equals("__slow__") ? 200 : input.equals("__continue_short__") ? 15 : 2;
            for (int index = 0; index < chunks; index++) {
                String delta = chunks == 2
                        ? (index == 0 ? "Haifa Agent SDK: " : input)
                        : ".";
                ModelStreamControl control = sink.emit(
                        new ModelStreamEvent.ContentDelta(request.callId(), index + 1L, delta));
                if (control == ModelStreamControl.CANCEL) {
                    throw new CancellationException("Haifa run cancelled by caller");
                }
                if (chunks > 2) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException("Haifa run interrupted");
                    }
                }
            }
            return response(request, answer);
        }

        private static String answer(io.haifa.agent.model.api.AgentChatRequest request) {
            return "Haifa Agent SDK: " + lastUserText(request);
        }

        private static String lastUserText(io.haifa.agent.model.api.AgentChatRequest request) {
            return request.messages().stream()
                    .filter(message -> message.role().name().equals("USER"))
                    .reduce((first, second) -> second)
                    .map(message -> message.content())
                    .orElse("");
        }

        private static AgentChatResponse response(
                io.haifa.agent.model.api.AgentChatRequest request, String answer) {
            return new AgentChatResponse(
                    "local-probe-response",
                    request.model().providerModelId(),
                    answer,
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(1, Math.max(1, answer.length() / 4)),
                    "",
                    Map.of());
        }
    }
}

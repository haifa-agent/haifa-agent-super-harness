package io.haifa.agent.superharness.server;

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
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Test-only deterministic model for W02 Run/SSE/cancellation contract coverage. */
final class LocalProbeModelTestSupport {
    private LocalProbeModelTestSupport() {}

    static HaifaRunBridge.ModelBinding binding() {
        return binding(new LocalProbeModel());
    }

    static HaifaRunBridge.ModelBinding binding(AgentChatModel model) {
        return new HaifaRunBridge.ModelBinding(
                "super-harness-local-probe", "1.0.0", model, snapshot());
    }

    private static ResolvedModelSnapshot snapshot() {
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
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                8_192,
                1_024,
                Map.of(),
                Map.of());
    }

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

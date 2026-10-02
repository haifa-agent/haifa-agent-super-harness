package io.haifa.agent.superharness.server.testsupport;

import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelStreamSink;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/** Scripted public model responses; both invocation paths share recording and validation. */
public final class ControlledModel implements AgentChatModel {
    public final List<AgentChatRequest> requests = new CopyOnWriteArrayList<>();
    public final AtomicInteger calls = new AtomicInteger();
    final BiFunction<AgentChatRequest, Integer, AgentChatResponse> responder;
    public volatile AgentChatResponse lastResponse;

    public ControlledModel() { this((request, index) -> stop(request)); }

    public ControlledModel(BiFunction<AgentChatRequest, Integer, AgentChatResponse> responder) {
        this.responder = Objects.requireNonNull(responder);
    }

    public static AgentChatResponse response(AgentChatRequest request, String id, String content,
            List<ModelToolCall> tools, ModelFinishReason finish, ModelUsage usage) {
        Objects.requireNonNull(usage, "Controlled model response requires usage");
        Objects.requireNonNull(finish, "Controlled model response requires finish reason");
        return new AgentChatResponse(id, request.model().providerModelId(), content, tools, finish, usage, "", Map.of());
    }

    public static AgentChatResponse stop(AgentChatRequest request) {
        return response(request, request.callId().value(), "completed", List.of(),
                ModelFinishReason.STOP, ModelUsage.unpriced(1, 1));
    }

    public static AgentChatResponse call(AgentChatRequest request, String name, Map<String, Object> arguments) {
        return response(request, request.callId().value(), "", List.of(new ModelToolCall(
                new ProviderToolCallCorrelationId("m3-" + request.callId().value()), name, arguments)),
                ModelFinishReason.TOOL_CALLS, ModelUsage.unpriced(1, 1));
    }

    @Override public AgentChatResponse invoke(AgentChatRequest request) {
        requests.add(request);
        AgentChatResponse response = Objects.requireNonNull(responder.apply(request, calls.incrementAndGet()),
                "Controlled model responder returned no response");
        Objects.requireNonNull(response.usage(), "Controlled model response requires usage");
        Objects.requireNonNull(response.finishReason(), "Controlled model response requires finish reason");
        lastResponse = response;
        return response;
    }

    @Override public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
        return invoke(request);
    }
}

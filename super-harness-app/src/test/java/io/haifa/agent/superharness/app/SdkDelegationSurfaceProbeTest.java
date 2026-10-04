package io.haifa.agent.superharness.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Public-JAR characterization of W08 admission, capability narrowing and durable child identity. */
public class SdkDelegationSurfaceProbeTest {
    @Test
    void noAllowedChildrenMeansNoTaskInModelSchema() throws Exception {
        CompletableFuture<List<String>> visible = new CompletableFuture<>();
        try (HaifaAgent agent = agent(request -> {
            visible.complete(request.tools().stream().map(tool -> tool.name()).toList());
            return answer("parent finished");
        }, Set.of(), Set.of())) {
            var started = agent.conversations().start(new StartConversationCommand("disabled", "Disabled", "Answer"));
            assertThat(agent.runs().await(started.runId(), Duration.ofSeconds(10)).orElseThrow().status())
                    .isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(visible.get(1, TimeUnit.SECONDS)).contains("probe_read").doesNotContain("task");
            assertThat(agent.runs().children(started.runId())).isEmpty();
        }
    }

    @Test
    void explicitEmptyChildToolsDoesNotInheritParentTools() throws Exception {
        CompletableFuture<List<String>> childTools = new CompletableFuture<>();
        AgentChatModel model = request -> {
            if (request.tools().stream().anyMatch(tool -> tool.name().equals("task"))) {
                return request.messages().stream().anyMatch(message -> message.role() == ModelMessageRole.TOOL)
                        ? answer("parent finished") : delegate("researcher");
            }
            childTools.complete(request.tools().stream().map(tool -> tool.name()).toList());
            return answer("child finished");
        };
        try (HaifaAgent agent = agent(model, Set.of("researcher"), Set.of())) {
            var started = agent.conversations().start(new StartConversationCommand("empty-tools", "Empty tools", "Research"));
            var parent = agent.runs().await(started.runId(), Duration.ofSeconds(10)).orElseThrow();
            assertThat(parent.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(childTools.get(1, TimeUnit.SECONDS)).isEmpty();
            assertThat(agent.runs().children(parent.runId())).hasSize(1);
            assertThat(agent.runs().children(parent.runId()).getFirst().usage().inputTokens()).isEqualTo(3);
            assertThat(parent.usage().inputTokens()).isEqualTo(6);
        }
    }

    @Test
    void unknownChildIsRejectedBeforeCreatingRunAndParentContinues() throws Exception {
        AgentChatModel model = request -> request.messages().stream()
                .anyMatch(message -> message.role() == ModelMessageRole.TOOL)
                ? answer("parent continues") : delegate("missing-agent");
        try (HaifaAgent agent = agent(model, Set.of("researcher"), Set.of())) {
            var started = agent.conversations().start(new StartConversationCommand("unknown", "Unknown", "Research"));
            var parent = agent.runs().await(started.runId(), Duration.ofSeconds(10)).orElseThrow();
            assertThat(parent.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(parent.output()).contains("parent continues");
            assertThat(agent.runs().children(parent.runId())).isEmpty();
            var call = agent.runs().toolCalls(parent.runId()).getFirst();
            assertThat(call.result().orElseThrow().structuredData().values()).containsEntry("status", "REJECTED");
            assertThat(call.result().orElseThrow().structuredData().values().get("reason")).isInstanceOf(String.class);
            assertThat(agent.runs().events(parent.runId(), RunEventCursor.beforeFirst(parent.runId()), 500).items())
                    .noneMatch(event -> event.eventType().startsWith("child.run."));
        }
    }

    @Test
    void childEventPagingPreservesSequenceAndRuntimeToolCallIdentity() throws Exception {
        AgentChatModel model = request -> {
            if (request.tools().stream().noneMatch(tool -> tool.name().equals("task"))) return answer("child finished");
            return request.messages().stream().anyMatch(message -> message.role() == ModelMessageRole.TOOL)
                    ? answer("parent finished") : delegate("researcher");
        };
        try (HaifaAgent agent = agent(model, Set.of("researcher"), Set.of())) {
            var started = agent.conversations().start(new StartConversationCommand("paging", "Paging", "Research"));
            var parent = agent.runs().await(started.runId(), Duration.ofSeconds(10)).orElseThrow();
            var child = agent.runs().children(parent.runId()).getFirst();
            var all = agent.runs().events(child.runId(), RunEventCursor.beforeFirst(child.runId()), 500);
            List<String> paged = new ArrayList<>();
            var cursor = RunEventCursor.beforeFirst(child.runId());
            for (int page = 0; page < 50; page++) {
                var next = agent.runs().events(child.runId(), cursor, 2);
                next.items().forEach(event -> paged.add(event.eventId()));
                cursor = next.nextCursor();
                if (!next.hasMore()) break;
            }
            assertThat(paged).containsExactlyElementsOf(all.items().stream().map(event -> event.eventId()).toList());
            assertThat(all.items()).isNotEmpty().allSatisfy(event -> {
                assertThat(event.runId()).isEqualTo(child.runId());
                assertThat(event.cursor().exclusiveSequence().orElseThrow()).isEqualTo(event.sequence());
            });
            var runtimeId = agent.runs().toolCalls(parent.runId()).getFirst().id().value();
            assertThat(runtimeId).isNotEqualTo("provider-probe-id");
            assertThat(agent.runs().events(parent.runId(), RunEventCursor.beforeFirst(parent.runId()), 500).items())
                    .filteredOn(event -> event.payload() instanceof RunEventPayloads.ChildRunLifecycle)
                    .isNotEmpty().allSatisfy(event -> assertThat(((RunEventPayloads.ChildRunLifecycle) event.payload()).toolCallId())
                            .isEqualTo(runtimeId));
        }
    }

    private static HaifaAgent agent(AgentChatModel model, Set<String> allowedChildren, Set<String> childTools) {
        return SdkDelegationTestSupport.builder(model, Set.of("probe_read"), childTools, allowedChildren)
                .tool(new ReadTool()).build();
    }

    private static AgentChatResponse delegate(String child) {
        return new AgentChatResponse("delegate", "probe", "", List.of(new ModelToolCall(
                new ProviderToolCallCorrelationId("provider-probe-id"), "task", Map.of("agent", child, "objective", "Research"))),
                ModelFinishReason.TOOL_CALLS, ModelUsage.unpriced(3, 2), "", Map.of());
    }

    private static AgentChatResponse answer(String text) {
        return new AgentChatResponse("answer", "probe", text, List.of(), ModelFinishReason.STOP,
                ModelUsage.unpriced(3, 2), "", Map.of());
    }

    public record Input(String value) {}
    public record Output(String value) {}

    private static final class ReadTool implements JavaTool<Input, Output> {
        @Override public JavaToolSpec<Input, Output> spec() {
            return JavaToolSpec.builder("probe_read", Input.class, Output.class)
                    .title("Probe read").description("Pure read probe").pure().build();
        }
        @Override public Output invoke(Input input, JavaToolContext context) { return new Output(input.value()); }
    }
}

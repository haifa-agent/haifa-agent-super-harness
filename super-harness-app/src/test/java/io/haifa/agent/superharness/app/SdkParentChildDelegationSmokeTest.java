package io.haifa.agent.superharness.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.ChildRunView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Public-JAR smoke for Haifa Agent 038-02a: a parent delegates to two children in one response through the
 * {@code task} Tool, both children run at the same time, and the parent combines their results.
 */
class SdkParentChildDelegationSmokeTest {
    @Test
    void parentDelegatesToTwoChildrenThatRunInParallel() throws Exception {
        CountDownLatch bothChildrenInsideModelCall = new CountDownLatch(2);
        AgentChatModel model = request -> {
            boolean parent = request.tools().stream()
                    .map(ModelToolSpecification::name)
                    .anyMatch("task"::equals);
            if (parent) {
                boolean hasResults =
                        request.messages().stream().anyMatch(message -> message.role() == ModelMessageRole.TOOL);
                if (hasResults) return answer("combined findings");
                return new AgentChatResponse(
                        "delegate",
                        request.model().providerModelId(),
                        "",
                        List.of(task("a", "find sources"), task("b", "check the design")),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unpriced(2, 1),
                        "",
                        Map.of());
            }
            // Each child waits until the other child is also inside a model call, which only completes if the
            // two children really overlap instead of running one after the other.
            bothChildrenInsideModelCall.countDown();
            try {
                if (!bothChildrenInsideModelCall.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("children did not run in parallel");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return answer("child result");
        };

        try (HaifaAgent agent = SdkDelegationTestSupport.builder(
                model, Set.of(), Set.of(), Set.of("researcher")).build()) {
            var started = agent.conversations()
                    .start(new StartConversationCommand("w08-delegation-smoke", "Delegation smoke", "Research"));
            var parent = agent.runs().await(started.runId(), Duration.ofSeconds(30)).orElseThrow();

            List<ChildRunView> children = agent.runs().children(parent.runId());
            var events = agent.runs().events(parent.runId(), RunEventCursor.beforeFirst(parent.runId()), 500);
            List<String> completedChildIds = events.items().stream()
                    .filter(event -> event.eventType().equals("child.run.completed"))
                    .map(event -> ((RunEventPayloads.ChildRunLifecycle) event.payload()).childRunId())
                    .toList();
            System.out.println("W08_DELEGATION_SMOKE_EVIDENCE parent=" + parent.runId().value() + " children="
                    + children.stream().map(child -> child.runId().value()).toList() + " completedEvents="
                    + completedChildIds.size());

            assertThat(parent.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(parent.output()).contains("combined findings");
            assertThat(bothChildrenInsideModelCall.getCount()).isZero();
            assertThat(children).hasSize(2).allSatisfy(child -> assertThat(child.status())
                    .isEqualTo(AgentRunStatus.COMPLETED));
            assertThat(children)
                    .extracting(ChildRunView::objective)
                    .containsExactlyInAnyOrder("find sources", "check the design");
            assertThat(completedChildIds)
                    .containsExactlyInAnyOrderElementsOf(children.stream()
                            .map(child -> child.runId().value())
                            .toList());
            assertThat(parent.usage().childRuns()).isEqualTo(2);

            // This public-JAR assertion intentionally requires the upstream output metadata change.
            // A stale SNAPSHOT must fail instead of passing with a digest invented by the consumer.
            var calls = agent.runs().toolCalls(parent.runId());
            assertThat(calls).filteredOn(call -> "task".equals(call.toolName())).hasSize(2).allSatisfy(call -> {
                var result = call.result().orElseThrow();
                assertThat(result.structuredData().values())
                        .containsEntry("status", "COMPLETED")
                        .containsEntry("outputPreview", "child result")
                        .containsEntry("outputTruncated", false)
                        .containsEntry("outputSha256", "9112a70c23a792fb2ecb8b78193daaf4f1f5bb2f9267ae3731d25573b4d8d9ea");
                assertThat(events.items())
                        .filteredOn(event -> event.eventType().equals("child.run.completed"))
                        .anySatisfy(event -> assertThat(((RunEventPayloads.ChildRunLifecycle) event.payload()).toolCallId())
                                .isEqualTo(call.id().value()));
            });
        }
    }

    private static ModelToolCall task(String correlation, String objective) {
        return new ModelToolCall(
                new ProviderToolCallCorrelationId("task-" + correlation),
                "task",
                Map.of("agent", "researcher", "objective", objective));
    }

    private static AgentChatResponse answer(String text) {
        return new AgentChatResponse(
                "final", "smoke", text, List.of(), ModelFinishReason.STOP, ModelUsage.unpriced(1, 1), "", Map.of());
    }
}

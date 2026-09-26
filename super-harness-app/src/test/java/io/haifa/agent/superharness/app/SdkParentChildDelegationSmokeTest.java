package io.haifa.agent.superharness.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelAdapterCoordinate;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.policy.api.PolicyPresets;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.runtime.api.ChildRunView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.contribution.InMemoryConversationContribution;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.contribution.SdkContributions;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.product.ChildAgentSpec;
import io.haifa.agent.sdk.product.ProductId;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductRunProfileRef;
import io.haifa.agent.sdk.product.ProductVersion;
import java.net.URI;
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
    private static final ResolvedModelSnapshot MODEL = ResolvedModelSnapshot.create(
            new ModelProviderId("super-harness-smoke"),
            "1.0.0",
            new ModelDefinitionId("super-harness-smoke-model"),
            "1.0.0",
            "super-harness-smoke-model",
            "super-harness-smoke-adapter",
            "1.0.0",
            ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
            ModelApiBindingDefinition.STANDARD_DIALECT,
            URI.create("https://model.invalid"),
            new CredentialRef("env://SUPER_HARNESS_SMOKE_KEY"),
            false,
            Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
            8_192,
            1_024,
            Map.of(),
            Map.of());

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

        try (HaifaAgent agent = HaifaAgents.builder(profile())
                .model(new ModelContribution(
                        Map.of(ModelAdapterCoordinate.from(MODEL), model),
                        MODEL,
                        Map.of(MODEL.modelId().value(), MODEL)))
                .persistence(SdkContributions.inMemoryPersistence())
                .conversation(new InMemoryConversationContribution())
                .policy(new PolicyPlatformContribution(
                        PolicyPresets.standardApproval(), new DefaultPolicyDecisionService()))
                .childAgent(ChildAgentSpec.of(
                        "researcher", "Researches one independent question", "Answer the objective.", Set.of()))
                .build()) {
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
        }
    }

    private static ProductProfile profile() {
        AgentRunBudget budget = new AgentRunBudget(100_000, 100_000, 100_000, 8, 8, 4, "USD", 1_000);
        return ProductProfile.create(
                        new ProductId("super-harness-delegation-smoke"),
                        new ProductVersion("1.0.0"),
                        new AgentDefinitionId("super-harness-lead"),
                        new AgentDefinitionVersion(1, 0, 0),
                        "Delegate independent research to child agents, then combine their findings.",
                        new ProductRunProfileRef("super-harness-delegation", "1.0.0"),
                        budget,
                        new AgentRunLimits(8, 1, 2, 30_000, 30_000, 8, 8, 4),
                        Set.of(),
                        Set.of())
                .withAllowedChildAgents(Set.of("researcher"));
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

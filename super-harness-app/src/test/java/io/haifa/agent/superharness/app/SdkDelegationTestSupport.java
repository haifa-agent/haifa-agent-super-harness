package io.haifa.agent.superharness.app;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelAdapterCoordinate;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.policy.api.PolicyPresets;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.sdk.api.HaifaAgentBuilder;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.contribution.InMemoryConversationContribution;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.contribution.SdkContributions;
import io.haifa.agent.sdk.product.ChildAgentSpec;
import io.haifa.agent.sdk.product.ProductId;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductRunProfileRef;
import io.haifa.agent.sdk.product.ProductVersion;
import java.net.URI;
import java.util.Map;
import java.util.Set;

/** Shared public-SDK assembly for delegation smoke and surface probes. */
final class SdkDelegationTestSupport {
    private SdkDelegationTestSupport() {}

    static final ResolvedModelSnapshot MODEL = ResolvedModelSnapshot.create(
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

    static ProductProfile profile(Set<String> allowedTools) {
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
                        allowedTools,
                        Set.of())
                .withAllowedChildAgents(Set.of("researcher"));
    }

    static HaifaAgentBuilder builder(AgentChatModel model, Set<String> parentTools,
            Set<String> childTools, Set<String> allowedChildren) {
        return HaifaAgents.builder(profile(parentTools).withAllowedChildAgents(allowedChildren))
                .model(new ModelContribution(Map.of(ModelAdapterCoordinate.from(MODEL), model),
                        MODEL, Map.of(MODEL.modelId().value(), MODEL)))
                .persistence(SdkContributions.inMemoryPersistence())
                .conversation(new InMemoryConversationContribution())
                .policy(new PolicyPlatformContribution(PolicyPresets.standardApproval(), new DefaultPolicyDecisionService()))
                .childAgent(ChildAgentSpec.of("researcher", "Researches one independent question", "Answer the objective.", childTools));
    }
}

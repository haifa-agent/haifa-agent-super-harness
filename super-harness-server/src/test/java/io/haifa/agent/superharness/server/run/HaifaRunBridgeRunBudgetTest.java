package io.haifa.agent.superharness.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.superharness.server.config.SubagentRunConfiguration;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * W15-task-14: the lead Run's structural limits and disabled token/cost budget must match the
 * frozen DeerFlow defaults. This unit test calls the package-visible profile factory directly and
 * asserts the assembled record without starting a Gateway.
 */
class HaifaRunBridgeRunBudgetTest {
    private static final long WALL_BASE_MILLIS = 3_600_000L;

    @Test
    void leadProfileUsesDeerFlowStructuralLimitsWithTheTokenBudgetDisabled() {
        ProductProfile profile = HaifaRunBridge.profile(
                snapshot(), "instructions", Set.of("read_file"), Set.of(), SubagentRunConfiguration.disabled());

        AgentRunLimits limits = profile.limits();
        assertThat(limits.maxIterations()).isEqualTo(100);
        assertThat(limits.maxModelCalls()).isEqualTo(100);
        assertThat(limits.maxToolCalls()).isEqualTo(1000);
        assertThat(limits.maxWallTimeMillis()).isEqualTo(WALL_BASE_MILLIS);
        assertThat(limits.maxIdleTimeMillis()).isEqualTo(600_000);

        AgentRunBudget budget = profile.budget();
        assertThat(budget.quotaMode()).isEqualTo(AgentRunBudget.disabled().quotaMode());
        assertThat(budget.maxInputTokens()).isZero();
        assertThat(budget.maxOutputTokens()).isZero();
        assertThat(budget.maxCachedInputTokens()).isZero();
        assertThat(budget.maxCostMinorUnits()).isZero();
    }

    @Test
    void delegatedLeadWallAddsTotalChildrenTimesTheirMaxTimeoutAndKeepsTheChildCap() throws Exception {
        ObjectNode definition = (ObjectNode) new ObjectMapper().readTree("""
                {"name":"researcher","description":"Research","system_prompt":"Do bounded work",
                "model":"inherit","max_turns":150,"timeout_seconds":900}
                """);
        var child = new SubagentRunConfiguration.Child(definition, LocalProbeModelTestSupport.binding());
        var settings = new SubagentRunConfiguration.Settings(2, 4, 2);
        var delegation = new SubagentRunConfiguration(List.of(child), settings, "delegation-test");

        ProductProfile profile = HaifaRunBridge.profile(
                snapshot(), "instructions", Set.of("read_file", "task"), Set.of(), delegation);

        long expectedWall = WALL_BASE_MILLIS + settings.total() * child.timeoutMillis();
        assertThat(profile.limits().maxWallTimeMillis()).isEqualTo(expectedWall);
        assertThat(profile.limits().maxChildRuns()).isEqualTo(settings.total());

        ProductProfile repeated = HaifaRunBridge.profile(
                snapshot(), "instructions", Set.of("read_file", "task"), Set.of(), delegation);
        assertThat(repeated).isEqualTo(profile);
    }

    private static ResolvedModelSnapshot snapshot() {
        return LocalProbeModelTestSupport.binding().snapshot();
    }
}

package io.haifa.agent.superharness.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyAction;
import io.haifa.agent.policy.api.PolicyContext;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyPresets;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyResource;
import io.haifa.agent.policy.api.PolicyRisk;
import io.haifa.agent.policy.api.PolicyRiskLevel;
import io.haifa.agent.policy.api.PolicySideEffect;
import io.haifa.agent.policy.api.PolicySubject;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.superharness.server.config.ApprovalPolicyConfig;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Characterises the three product approval levels without a live Run. */
class ApprovalPolicyPresetsTest {
    private final DefaultPolicyDecisionService service = new DefaultPolicyDecisionService();

    @Test
    void askKeepsTheStandardSdkPresetByteForByte() {
        assertThat(ApprovalPolicyPresets.rules(ApprovalPolicyConfig.Mode.ASK).contentDigest())
                .isEqualTo(PolicyPresets.standardApproval().contentDigest());
        assertThat(ApprovalPolicyPresets.rules(null).contentDigest())
                .isEqualTo(PolicyPresets.standardApproval().contentDigest());
        assertThat(ApprovalPolicyPresets.rules(null).approvalMode()).isEqualTo(ApprovalMode.ASK);
    }

    @Test
    void autoAllowsGuardedSideEffectsAndSwitchesTheSdkMode() {
        assertThat(ApprovalPolicyPresets.rules(ApprovalPolicyConfig.Mode.AUTO).approvalMode())
                .isEqualTo(ApprovalMode.AUTO);
        assertThat(effect(ApprovalPolicyConfig.Mode.AUTO, "write_file", PolicyRiskLevel.MEDIUM,
                        Set.of(PolicySideEffect.FILE_WRITE)))
                .isEqualTo(PolicyEffect.ALLOW);
        assertThat(effect(ApprovalPolicyConfig.Mode.AUTO, "execution_run", PolicyRiskLevel.HIGH,
                        Set.of(PolicySideEffect.PROCESS_EXECUTION)))
                .isEqualTo(PolicyEffect.ALLOW);
        assertThat(effect(ApprovalPolicyConfig.Mode.AUTO, "mcp__server__tool", PolicyRiskLevel.MEDIUM,
                        Set.of(PolicySideEffect.NETWORK_ACCESS)))
                .isEqualTo(PolicyEffect.ALLOW);
    }

    @Test
    void autoStillDeniesCriticalRisk() {
        assertThat(effect(ApprovalPolicyConfig.Mode.AUTO, "write_file", PolicyRiskLevel.CRITICAL,
                        Set.of(PolicySideEffect.FILE_WRITE)))
                .isEqualTo(PolicyEffect.DENY);
    }

    @Test
    void denyBlocksEveryGuardedSideEffect() {
        assertThat(ApprovalPolicyPresets.rules(ApprovalPolicyConfig.Mode.DENY).approvalMode())
                .isEqualTo(ApprovalMode.DENY);
        for (PolicySideEffect effect : Set.of(
                PolicySideEffect.FILE_WRITE,
                PolicySideEffect.PROCESS_EXECUTION,
                PolicySideEffect.NETWORK_ACCESS,
                PolicySideEffect.EXTERNAL_SYSTEM_MUTATION,
                PolicySideEffect.PERMISSION_ELEVATION)) {
            assertThat(effect(ApprovalPolicyConfig.Mode.DENY, "guarded", PolicyRiskLevel.HIGH, Set.of(effect)))
                    .as("deny level for " + effect)
                    .isEqualTo(PolicyEffect.DENY);
        }
    }

    @Test
    void askStillRequiresApprovalForTheSameSideEffects() {
        assertThat(effect(ApprovalPolicyConfig.Mode.ASK, "write_file", PolicyRiskLevel.MEDIUM,
                        Set.of(PolicySideEffect.FILE_WRITE)))
                .isEqualTo(PolicyEffect.ASK);
    }

    private PolicyEffect effect(
            ApprovalPolicyConfig.Mode mode, String capability, PolicyRiskLevel risk,
            Set<PolicySideEffect> sideEffects) {
        PolicyRequest request = new PolicyRequest(
                new PolicySubject(
                        new TenantRef("public"),
                        new PrincipalRef("default-public-user", "user"),
                        "haifa-super-harness"),
                new PolicyContext(
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        ApprovalMode.ASK, Optional.empty()),
                new PolicyAction(capability, "invoke"),
                new PolicyResource("tool", capability, Optional.empty(), "tool " + capability),
                new PolicyRisk(risk, sideEffects, false, Optional.empty()));
        return service.evaluate(request, ApprovalPolicyPresets.rules(mode)).effect();
    }
}

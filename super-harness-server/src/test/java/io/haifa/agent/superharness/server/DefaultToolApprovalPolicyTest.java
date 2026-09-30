package io.haifa.agent.superharness.server;

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
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * W11-task-1 approval characterisation without a live Run. The default web tools cannot declare
 * {@code NETWORK_ACCESS} from the public {@code JavaTool} API (see {@code ProductionWebSearchTool}),
 * so they carry no side effect and the SDK standard preset returns the default {@code ALLOW}. MCP
 * network tools and file write tools still match the preset {@code ASK} rules and require approval,
 * which the end-to-end {@code WebToolApprovalRunTest} confirms for {@code write_file}.
 */
class DefaultToolApprovalPolicyTest {
    private final DefaultPolicyDecisionService service = new DefaultPolicyDecisionService();

    @Test
    void aSideEffectFreeJavaToolIsAllowedByDefault() {
        assertThat(decision("web_search", Set.of()).effect()).isEqualTo(PolicyEffect.ALLOW);
    }

    @Test
    void aNetworkSideEffectToolStillRequiresApproval() {
        PolicyEffect effect = decision("mcp__v2-reporting__get_reporting_policy",
                Set.of(PolicySideEffect.NETWORK_ACCESS)).effect();
        assertThat(effect).isEqualTo(PolicyEffect.ASK);
    }

    @Test
    void aFileWriteToolStillRequiresApproval() {
        assertThat(decision("write_file", Set.of(PolicySideEffect.FILE_WRITE)).effect())
                .isEqualTo(PolicyEffect.ASK);
    }

    private io.haifa.agent.policy.api.PolicyDecision decision(
            String capability, Set<PolicySideEffect> sideEffects) {
        PolicyRequest request = new PolicyRequest(
                new PolicySubject(
                        new TenantRef("public"),
                        new PrincipalRef("default-public-user", "user"),
                        "haifa-super-harness"),
                new PolicyContext(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        ApprovalMode.ASK,
                        Optional.empty()),
                new PolicyAction(capability, "invoke"),
                new PolicyResource("tool", capability, Optional.empty(), "tool " + capability),
                new PolicyRisk(PolicyRiskLevel.MEDIUM, sideEffects, false, Optional.empty()));
        return service.evaluate(request, PolicyPresets.standardApproval());
    }
}

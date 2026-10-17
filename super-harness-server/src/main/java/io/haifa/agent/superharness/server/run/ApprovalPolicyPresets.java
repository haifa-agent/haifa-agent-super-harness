package io.haifa.agent.superharness.server.run;

import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyPresets;
import io.haifa.agent.policy.api.PolicyRiskLevel;
import io.haifa.agent.policy.api.PolicyRule;
import io.haifa.agent.policy.api.PolicyRuleMatcher;
import io.haifa.agent.policy.api.PolicyRuleRef;
import io.haifa.agent.policy.api.PolicyRuleSet;
import io.haifa.agent.policy.api.PolicyRuleSource;
import io.haifa.agent.policy.api.PolicySideEffect;
import io.haifa.agent.superharness.server.config.ApprovalPolicyConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Product approval policy data selected by {@link ApprovalPolicyConfig.Mode}.
 *
 * <p>This class only declares immutable {@link PolicyRuleSet} data; the SDK keeps owning the
 * evaluator, the approval runtime and the Tool execution path. {@link ApprovalPolicyConfig.Mode#ASK}
 * returns the SDK preset unchanged so the default product behaviour stays byte-identical.
 *
 * <p>{@link ApprovalPolicyConfig.Mode#AUTO} mirrors the upstream Coding Agent {@code --approval auto}
 * assembly: the side-effect {@code ASK} rules are not declared, the default rule allows, and the
 * SDK approval mode is {@code AUTO} so {@code approvalRequirement=ALWAYS} Tools execute. Critical
 * risk is still denied by a rule, and credential reauthentication still challenges. The SDK's own
 * Tool-level switch decides {@code ALWAYS} Tools, so an {@code AUTO} level never replaces the SDK
 * decision — it only removes the product's approval requirements.
 *
 * <p>{@link ApprovalPolicyConfig.Mode#DENY} keeps the same critical-risk rule and denies every
 * guarded side effect.
 */
public final class ApprovalPolicyPresets {

    /** Side effects that require approval under the standard preset, in the SDK preset order. */
    private static final List<PolicySideEffect> GUARDED_SIDE_EFFECTS = List.of(
            PolicySideEffect.FILE_WRITE,
            PolicySideEffect.PROCESS_EXECUTION,
            PolicySideEffect.NETWORK_ACCESS,
            PolicySideEffect.EXTERNAL_SYSTEM_MUTATION,
            PolicySideEffect.PERMISSION_ELEVATION);

    private ApprovalPolicyPresets() {}

    /** Resolves the policy data for one level; a null level keeps the fail-closed default. */
    public static PolicyRuleSet rules(ApprovalPolicyConfig.Mode mode) {
        ApprovalPolicyConfig.Mode resolved = mode == null ? ApprovalPolicyConfig.DEFAULT_MODE : mode;
        return switch (resolved) {
            case ASK -> PolicyPresets.standardApproval();
            case AUTO -> relaxed(ApprovalMode.AUTO);
            case DENY -> relaxed(ApprovalMode.DENY);
        };
    }

    private static PolicyRuleSet relaxed(ApprovalMode approvalMode) {
        List<PolicyRule> rules = new ArrayList<>();
        rules.add(new PolicyRule(
                new PolicyRuleRef("super-harness-approval-critical-risk", "1"),
                PolicyRuleSource.MANAGED,
                200,
                new PolicyRuleMatcher(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(PolicyRiskLevel.CRITICAL),
                        Set.of()),
                PolicyEffect.DENY,
                Optional.empty(),
                "APPROVAL_PRESET_CRITICAL_RISK_DENY",
                "Critical operations are denied"));
        if (approvalMode == ApprovalMode.DENY) {
            for (PolicySideEffect effect : GUARDED_SIDE_EFFECTS) {
                rules.add(new PolicyRule(
                        new PolicyRuleRef(
                                "super-harness-approval-deny-" + effect.name().toLowerCase(Locale.ROOT), "1"),
                        PolicyRuleSource.MANAGED,
                        100,
                        sideEffectMatcher(effect),
                        PolicyEffect.DENY,
                        Optional.empty(),
                        "APPROVAL_PRESET_SIDE_EFFECT_DENY",
                        "Side-effecting operations are denied"));
            }
        }
        PolicyRule defaultRule = new PolicyRule(
                new PolicyRuleRef("super-harness-approval-default", "1"),
                PolicyRuleSource.MANAGED,
                0,
                PolicyRuleMatcher.any(),
                PolicyEffect.ALLOW,
                Optional.empty(),
                "APPROVAL_PRESET_DEFAULT_ALLOW",
                "Allowed by the configured approval level");
        return PolicyRuleSet.of(rules, Optional.of(defaultRule), approvalMode);
    }

    private static PolicyRuleMatcher sideEffectMatcher(PolicySideEffect effect) {
        return new PolicyRuleMatcher(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Set.of(effect));
    }
}

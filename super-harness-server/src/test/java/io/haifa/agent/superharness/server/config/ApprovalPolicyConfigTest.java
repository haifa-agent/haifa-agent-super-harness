package io.haifa.agent.superharness.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The product approval level defaults to ask and a typo can never widen it. */
class ApprovalPolicyConfigTest {

    @Test
    void defaultsToAskWhenTheHostDoesNotDeclareALevel() {
        assertThat(ApprovalPolicyConfig.fromEnvironment(Map.of()).mode())
                .isEqualTo(ApprovalPolicyConfig.Mode.ASK);
        assertThat(ApprovalPolicyConfig.parse(null)).isEqualTo(ApprovalPolicyConfig.Mode.ASK);
        assertThat(ApprovalPolicyConfig.parse("")).isEqualTo(ApprovalPolicyConfig.Mode.ASK);
        assertThat(ApprovalPolicyConfig.parse("   ")).isEqualTo(ApprovalPolicyConfig.Mode.ASK);
        assertThat(ApprovalPolicyConfig.parse("ask")).isEqualTo(ApprovalPolicyConfig.Mode.ASK);
    }

    @Test
    void keepsAskForUnknownValuesSoATypoNeverApprovesTools() {
        assertThat(ApprovalPolicyConfig.parse("maybe")).isEqualTo(ApprovalPolicyConfig.Mode.ASK);
        assertThat(ApprovalPolicyConfig.parse("auto-approve"))
                .isEqualTo(ApprovalPolicyConfig.Mode.ASK);
        assertThat(ApprovalPolicyConfig.fromEnvironment(Map.of(
                        ApprovalPolicyConfig.ENVIRONMENT_KEY, "all"))
                .mode())
                .isEqualTo(ApprovalPolicyConfig.Mode.ASK);
    }

    @Test
    void readsAutoAndDenyCaseInsensitively() {
        assertThat(ApprovalPolicyConfig.fromEnvironment(Map.of(
                        ApprovalPolicyConfig.ENVIRONMENT_KEY, "auto"))
                .mode())
                .isEqualTo(ApprovalPolicyConfig.Mode.AUTO);
        assertThat(ApprovalPolicyConfig.fromEnvironment(Map.of(
                        ApprovalPolicyConfig.ENVIRONMENT_KEY, "  DENY "))
                .mode())
                .isEqualTo(ApprovalPolicyConfig.Mode.DENY);
    }

    @Test
    void keepsTheDocumentedEnvironmentKey() {
        assertThat(ApprovalPolicyConfig.ENVIRONMENT_KEY).isEqualTo("HAIFA_SUPER_HARNESS_APPROVAL_MODE");
    }
}

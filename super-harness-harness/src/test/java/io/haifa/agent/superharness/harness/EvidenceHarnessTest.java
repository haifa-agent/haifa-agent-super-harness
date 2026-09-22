package io.haifa.agent.superharness.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EvidenceHarnessTest {
    private static final String SDK_CASE = "T-G00-published-sdk-run-tool-artifact";

    @Test
    void acceptsPassingRequiredCaseWithEvidence() {
        var report = EvidenceHarness.verify(
                Set.of(SDK_CASE), List.of(CaseObservation.pass(SDK_CASE, "run=run-1 artifact=artifact-1")));

        assertThat(report.passed()).isTrue();
        assertThat(report.issues()).isEmpty();
    }

    @Test
    void rejectsAnIntentionalFailure() {
        var report = EvidenceHarness.verify(
                Set.of(SDK_CASE), List.of(CaseObservation.fail(SDK_CASE, "intentional verifier failure")));

        assertThat(report.passed()).isFalse();
        assertThat(report.issues()).containsExactly("FAILED_CASE:T-G00-published-sdk-run-tool-artifact");
        assertThatThrownBy(report::requirePassed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HARNESS_FAILED")
                .hasMessageContaining("FAILED_CASE");
    }

    @Test
    void rejectsAMissingRequiredCase() {
        var report = EvidenceHarness.verify(Set.of(SDK_CASE, "T-HARNESS-required-negative"), List.of(
                CaseObservation.pass(SDK_CASE, "run=run-1 artifact=artifact-1")));

        assertThat(report.passed()).isFalse();
        assertThat(report.issues()).containsExactly("MISSING_CASE:T-HARNESS-required-negative");
        assertThatThrownBy(report::requirePassed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MISSING_CASE");
    }

    @Test
    void rejectsAPassWithoutEvidence() {
        var report = EvidenceHarness.verify(Set.of(SDK_CASE), List.of(CaseObservation.pass(SDK_CASE, " ")));

        assertThat(report.passed()).isFalse();
        assertThat(report.issues()).containsExactly("MISSING_EVIDENCE:T-G00-published-sdk-run-tool-artifact");
        assertThatThrownBy(report::requirePassed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MISSING_EVIDENCE");
    }
}

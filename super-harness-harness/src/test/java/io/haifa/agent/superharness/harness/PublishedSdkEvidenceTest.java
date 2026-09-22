package io.haifa.agent.superharness.harness;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.superharness.app.SdkRunToolArtifactSmoke;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PublishedSdkEvidenceTest {
    @Test
    void routesRealSdkEvidenceThroughTheHarness() throws Exception {
        var evidence = SdkRunToolArtifactSmoke.execute();
        var caseId = "T-G00-published-sdk-run-tool-artifact";
        var observation = CaseObservation.pass(
                caseId,
                "run=" + evidence.runId() + " tool=" + evidence.toolAlias() + " artifact=" + evidence.artifactId()
                        + " sha256=" + evidence.artifactSha256());

        var report = EvidenceHarness.verify(Set.of(caseId), List.of(observation)).requirePassed();

        assertThat(report.passed()).isTrue();
        assertThat(report.observedCaseCount()).isOne();
    }
}

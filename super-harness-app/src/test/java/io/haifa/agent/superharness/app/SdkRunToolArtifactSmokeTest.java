package io.haifa.agent.superharness.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SdkRunToolArtifactSmokeTest {
    @Test
    void executesARealRunToolArtifactChainUsingPublishedHaifaArtifacts() throws Exception {
        var evidence = SdkRunToolArtifactSmoke.execute();
        System.out.println("W01_SDK_EVIDENCE run=" + evidence.runId() + " tool=" + evidence.toolAlias()
                + " artifact=" + evidence.artifactId() + " sha256=" + evidence.artifactSha256()
                + " bytes=" + evidence.artifactBytes());

        assertThat(evidence.runId()).isNotBlank();
        assertThat(evidence.toolAlias()).isEqualTo("artifact_render");
        assertThat(evidence.artifactId()).isNotBlank();
        assertThat(evidence.artifactSha256()).startsWith("sha256:");
        assertThat(evidence.artifactBytes()).isPositive();
        assertThat(evidence.output()).isEqualTo("Artifact published");
    }
}

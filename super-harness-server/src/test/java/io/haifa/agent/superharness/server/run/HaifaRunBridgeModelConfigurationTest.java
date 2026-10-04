package io.haifa.agent.superharness.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HaifaRunBridgeModelConfigurationTest {
    @Test
    void recoveryCanUseTheOriginalRunsFrozenInvocationOptions() {
        var selected = HaifaPublicInteractionSpikeTest.modelSnapshot();
        var request = requestSnapshot(selected, selected.credentialRef());

        assertThat(request.configurationDigest()).isNotEqualTo(selected.configurationDigest());
        assertThat(HaifaRunBridge.matchesFrozenModelConfiguration(selected, request)).isTrue();
    }

    @Test
    void sameModelAndRevisionCannotRouteAnotherCredential() {
        var selected = HaifaPublicInteractionSpikeTest.modelSnapshot();
        var request = requestSnapshot(selected, new CredentialRef("env://ANOTHER_MODEL_CREDENTIAL"));

        assertThat(request.modelId()).isEqualTo(selected.modelId());
        assertThat(request.modelVersion()).isEqualTo(selected.modelVersion());
        assertThat(HaifaRunBridge.matchesFrozenModelConfiguration(selected, request)).isFalse();
    }

    private static ResolvedModelSnapshot requestSnapshot(ResolvedModelSnapshot source, CredentialRef credential) {
        return ResolvedModelSnapshot.create(
                source.providerId(), source.providerVersion(), source.modelId(), source.modelVersion(),
                source.providerModelId(), source.adapterType(), source.adapterVersion(), source.apiStyle(),
                source.dialect(), source.endpoint(), credential, source.nativeStreaming(), source.capabilities(),
                source.contextWindow(), source.maxOutputTokens(), source.providerOptions(),
                Map.of("thinking", "enabled", "reasoning_effort", "low"));
    }
}

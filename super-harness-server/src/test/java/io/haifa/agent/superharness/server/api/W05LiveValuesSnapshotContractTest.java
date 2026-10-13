package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Pure transport-cache controls; actual SDK/HTTP evidence is tested separately. */
class W05LiveValuesSnapshotContractTest {
    @Test void acceptedButNotCapturedDoesNotRetireUntilItsExactMessageIsVisible() {
        assertThat(LiveParentValuesProjector.acceptedMessageIsVisible(
                "accepted-message", List.of(), Set.of("older-message"), Set.of())).isFalse();
        assertThat(LiveParentValuesProjector.acceptedMessageIsVisible(
                "accepted-message", List.of(), Set.of("accepted-message"), Set.of())).isTrue();
    }

    @Test void toolRoundNeedsEveryExactCallInTheCapturedProjection() {
        assertThat(LiveParentValuesProjector.acceptedMessageIsVisible(
                "round-message", List.of("call-a", "call-b"), Set.of(), Set.of("call-a"))).isFalse();
        assertThat(LiveParentValuesProjector.acceptedMessageIsVisible(
                "round-message", List.of("call-a", "call-b"), Set.of(), Set.of("call-a", "call-b"))).isTrue();
    }

    @Test void unchangedTextPollingReusesSnapshotAndInputOrAcceptanceInvalidatesIt() {
        var projector = new LiveParentValuesProjector("cache-controls");
        var snapshot = new LiveParentValuesProjector.DurableSnapshot(
                List.of(Map.of("id", "captured-row")), Set.of("accepted-call"));
        assertThat(projector.needsDurableSnapshot(5, 0, 0)).isTrue();
        projector.cacheDurableSnapshot(snapshot, 5, 0, 0);
        for (int tick = 1; tick <= 99; tick++) {
            assertThat(projector.needsDurableSnapshot(5, 0, tick * 10_000_000L)).isFalse();
        }
        assertThat(projector.durableSnapshot()).isSameAs(snapshot);
        assertThat(projector.needsDurableSnapshot(6, 0, 1)).isTrue();
        assertThat(projector.needsDurableSnapshot(5, 1, 1)).isTrue();
        assertThat(projector.needsDurableSnapshot(5, 0,
                LiveParentValuesProjector.DURABLE_REFRESH_NANOS)).isTrue();
    }

    @Test void acceptanceDuringReadRemainsDirtyAndClosingDropsCachedProjection() {
        var projector = new LiveParentValuesProjector("cache-controls");
        projector.cacheDurableSnapshot(new LiveParentValuesProjector.DurableSnapshot(List.of(), Set.of()),
                5, 0, 0);
        assertThat(projector.needsDurableSnapshot(5, 1, 1)).isTrue();
        projector.close();
        assertThat(projector.durableSnapshot()).isNull();
        assertThat(projector.needsDurableSnapshot(5, 0, 1)).isTrue();
    }
}

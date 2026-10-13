package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEventType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * W05 LIVE02 generation-lifecycle contracts for the per-connection live-values producer buffer,
 * driven by public output events only ({@link AgentRunOutputEvent}). No synthetic accepted
 * relationship is asserted here: the production API supplies the actual public accepted id and
 * retires the provisional generation with it, and this fixture drives that public {@code retire}
 * call directly.
 * <ul>
 *   <li>{@code RUN_OUTPUT_STARTED} must arrive before public deltas; an orphan delta never
 *       fabricates a full prefix row, and STARTED alone produces no row;</li>
 *   <li>retirement removes a generation entirely, so the ninth sequential generation is still
 *       tracked normally (there is no lifetime cap concept to overflow);</li>
 *   <li>FAILED and SUPERSEDED output-terminal events also remove the generation entirely;</li>
 *   <li>COMMITTED is an output-channel fact: the buffered text stays provisional until the exact
 *       accepted {@code retire(modelCallId)}; a different id never removes it;</li>
 *   <li>cumulative text is never truncated by an invented slice.</li>
 * </ul>
 */
@Timeout(30)
class W05LiveValuesGenerationsContractTest {
    private static final String RUN_ID = "w05-generations-run";

    private static AgentRunOutputEvent output(
            String modelCallId, AgentRunOutputEventType type, String textDelta) {
        return new AgentRunOutputEvent(
                new AgentRunId(RUN_ID), modelCallId, "gen-" + modelCallId, 1,
                System.nanoTime(), type, textDelta == null ? "" : textDelta, Instant.now());
    }

    private static AgentRunOutputEvent started(String modelCallId) {
        return output(modelCallId, AgentRunOutputEventType.RUN_OUTPUT_STARTED, null);
    }

    private static AgentRunOutputEvent delta(String modelCallId, String text) {
        return output(modelCallId, AgentRunOutputEventType.ASSISTANT_TEXT_DELTA, text);
    }

    private static String liveText(LiveParentValuesProjector projector, String modelCallId) {
        List<Map<String, Object>> rows = projector.liveRows().stream()
                .filter(row -> modelCallId.equals(row.get("id")))
                .toList();
        assertThat(rows).as("one tracked row for generation " + modelCallId).hasSize(1);
        return String.valueOf(rows.get(0).get("content"));
    }

    @Test
    void runOutputStartedMustPrecedeDeltasBeforeAnyRowIsPresented() {
        try (LiveParentValuesProjector projector = new LiveParentValuesProjector(RUN_ID)) {
            projector.onOutput(delta("orphan-call", "orphan delta"));
            assertThat(projector.liveRows())
                    .as("a delta without RUN_OUTPUT_STARTED must never be presented as a full prefix row")
                    .isEmpty();

            // The public RUN_OUTPUT_STARTED frame arrives before the deltas it belongs to.
            projector.onOutput(started("orphan-call"));
            assertThat(projector.liveRows())
                    .as("STARTED alone produces no row until text accumulates")
                    .isEmpty();
            projector.onOutput(delta("orphan-call", "text after started"));
            assertThat(liveText(projector, "orphan-call")).isEqualTo("text after started");
        }
    }

    @Test
    void nineSequentialGenerationsStayTrackedBecauseRetiredGenerationsAreRemoved() {
        try (LiveParentValuesProjector projector = new LiveParentValuesProjector(RUN_ID)) {
            for (int generation = 1; generation <= 9; generation++) {
                String modelCallId = "call-" + generation;
                projector.onOutput(started(modelCallId));
                projector.onOutput(delta(modelCallId, "answer " + generation));
                assertThat(projector.liveRows())
                        .as("only generation " + generation + " is tracked")
                        .hasSize(1);
                assertThat(liveText(projector, modelCallId)).isEqualTo("answer " + generation);

                // The accepted association retires the exact generation id.
                projector.retire(modelCallId);
                assertThat(projector.liveRows())
                        .as("a retired generation must be removed entirely before the next one starts")
                        .isEmpty();
            }
        }
    }

    @Test
    void failedAndSupersededGenerationsAreRemovedSoTheNinthStillTracks() {
        try (LiveParentValuesProjector projector = new LiveParentValuesProjector(RUN_ID)) {
            for (int generation = 1; generation <= 8; generation++) {
                boolean failed = generation % 2 == 1;
                String modelCallId = (failed ? "failed-" : "superseded-") + generation;
                AgentRunOutputEventType terminal = failed
                        ? AgentRunOutputEventType.RUN_OUTPUT_FAILED
                        : AgentRunOutputEventType.RUN_OUTPUT_SUPERSEDED;
                projector.onOutput(started(modelCallId));
                assertThat(projector.liveRows()).isEmpty();
                projector.onOutput(delta(modelCallId, "draft " + generation));
                assertThat(projector.liveRows()).hasSize(1);
                projector.onOutput(output(modelCallId, terminal, null));
                assertThat(projector.liveRows())
                        .as(terminal + " generation " + modelCallId + " is removed, not flagged in place")
                        .isEmpty();
            }
            // The ninth sequential generation is still tracked after the removals: FAILED and
            // SUPERSEDED retirement is complete removal, never a cap-based flag suppression.
            projector.onOutput(started("still-tracked"));
            projector.onOutput(delta("still-tracked", "tracked after eight removals"));
            assertThat(liveText(projector, "still-tracked")).isEqualTo("tracked after eight removals");
        }
    }

    @Test
    void committedTextStaysProvisionalUntilExactRetirement() {
        try (LiveParentValuesProjector projector = new LiveParentValuesProjector(RUN_ID)) {
            projector.onOutput(started("accepted-call"));
            projector.onOutput(delta("accepted-call", "final answer"));
            assertThat(liveText(projector, "accepted-call")).isEqualTo("final answer");

            // COMMITTED never removes the provisional buffer on its own, and no synthetic
            // accepted relationship is manufactured here: the production association uses the
            // actual public accepted id.
            projector.onOutput(
                    output("accepted-call", AgentRunOutputEventType.ASSISTANT_TEXT_COMMITTED, null));
            assertThat(liveText(projector, "accepted-call"))
                    .as("COMMITTED keeps the text provisional until the exact accepted retire")
                    .isEqualTo("final answer");

            projector.retire("wrong-call");
            assertThat(liveText(projector, "accepted-call"))
                    .as("a different id must not remove the provisional generation")
                    .isEqualTo("final answer");

            projector.retire("accepted-call");
            assertThat(projector.liveRows())
                    .as("the exact retire(modelCallId) removes the provisional generation")
                    .isEmpty();
        }
    }

    @Test
    void longCumulativeTextIsKeptWholeWithoutAnInventedSlice() {
        try (LiveParentValuesProjector projector = new LiveParentValuesProjector(RUN_ID)) {
            String body = "W05-LIVE-" + "x".repeat(70_000) + "-W05-TAIL";
            String head = body.substring(0, 35_000);
            String tail = body.substring(35_000);
            projector.onOutput(started("long-call"));
            projector.onOutput(delta("long-call", head));
            assertThat(liveText(projector, "long-call")).isEqualTo(head);
            projector.onOutput(delta("long-call", tail));
            assertThat(liveText(projector, "long-call"))
                    .as("cumulative 70k generation text must be presented whole, never sliced")
                    .isEqualTo(body);
        }
    }
}

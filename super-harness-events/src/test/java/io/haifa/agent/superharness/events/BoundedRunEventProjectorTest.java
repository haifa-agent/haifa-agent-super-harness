package io.haifa.agent.superharness.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.AgentRunViewSnapshot;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedRunEventProjectorTest {
    private static final AgentRunId RUN_ID = new AgentRunId("run-w05");
    private static final AgentSessionId SESSION_ID = new AgentSessionId("session-w05");

    @Test
    void assignsOrderedCursorsAndTreatsExactDuplicateAsIdempotent() {
        BoundedRunEventProjector projector = projector(8, AgentRunStatus.RUNNING);

        AgentRunEvent first = lifecycle(1, "event-1", "RUNNING");
        AppendResult.Accepted accepted = (AppendResult.Accepted) projector.append(first);
        AppendResult.Accepted duplicate = (AppendResult.Accepted) projector.append(first);
        AppendResult.Accepted second =
                (AppendResult.Accepted) projector.append(interaction(2, "event-2", "request-7"));

        assertThat(accepted.duplicate()).isFalse();
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(duplicate.event()).isEqualTo(accepted.event());
        assertThat(second.event().cursor()).isEqualTo(new ProjectionCursor(RUN_ID.value(), 2));
        assertThat(second.event().interaction()).hasValueSatisfying(value -> {
            assertThat(value.requestId()).isEqualTo("request-7");
            assertThat(value.state()).isEqualTo(InteractionState.PENDING);
        });
    }

    @Test
    void rejectsConflictingDuplicateAndNonMonotonicUnknownEvent() {
        BoundedRunEventProjector projector = projector(8, AgentRunStatus.RUNNING);
        projector.append(lifecycle(1, "event-1", "RUNNING"));

        assertThatThrownBy(() -> projector.append(lifecycle(1, "event-1", "COMPLETED")))
                .isInstanceOf(EventProjectionException.class)
                .hasMessageContaining("reused with different content");
        assertThatThrownBy(() -> projector.append(lifecycle(1, "other-id", "RUNNING")))
                .isInstanceOf(EventProjectionException.class)
                .hasMessageContaining("non-monotonic");
    }

    @Test
    void acceptsSparseCommittedSequencesWithoutInventingASourceGap() {
        AtomicInteger loads = new AtomicInteger();
        AgentRunViewSnapshot view = view(AgentRunStatus.RUNNING);
        BoundedRunEventProjector projector = new BoundedRunEventProjector(RUN_ID, 8, ignored -> {
            loads.incrementAndGet();
            return Optional.of(view);
        });
        projector.append(lifecycle(1, "event-1", "RUNNING"));

        AppendResult.Accepted result =
                (AppendResult.Accepted) projector.append(lifecycle(3, "event-3", "RUNNING"));

        assertThat(result.event().cursor()).isEqualTo(new ProjectionCursor(RUN_ID.value(), 3));
        assertThat(projector.head().sequence()).isEqualTo(3);
        assertThat(loads).hasValue(0);
    }

    @Test
    void boundedReplayGapRequiresAuthoritativeView() {
        AgentRunViewSnapshot view = view(AgentRunStatus.RUNNING);
        BoundedRunEventProjector projector =
                new BoundedRunEventProjector(RUN_ID, 2, ignored -> Optional.of(view));
        projector.append(lifecycle(1, "event-1", "RUNNING"));
        projector.append(lifecycle(2, "event-2", "RUNNING"));
        projector.append(lifecycle(3, "event-3", "RUNNING"));

        ReplayResult.Gap gap =
                (ReplayResult.Gap) projector.replayAfter(new ProjectionCursor(RUN_ID.value(), 0));
        ReplayResult.Events tail =
                (ReplayResult.Events) projector.replayAfter(new ProjectionCursor(RUN_ID.value(), 1));

        assertThat(gap.gap().reason()).isEqualTo(ProjectionGap.Reason.RETENTION_WINDOW_EXCEEDED);
        assertThat(gap.gap().earliestAvailableSequence()).isEqualTo(2);
        assertThat(gap.authoritativeView()).isSameAs(view);
        assertThat(tail.events()).extracting(event -> event.key().eventId()).containsExactly("event-2", "event-3");
    }

    @Test
    void failsClosedWhenGapCannotLoadMatchingAuthoritativeView() {
        BoundedRunEventProjector missing =
                new BoundedRunEventProjector(RUN_ID, 1, ignored -> Optional.empty());
        missing.append(lifecycle(1, "event-1", "RUNNING"));
        missing.append(lifecycle(3, "event-3", "RUNNING"));

        assertThatThrownBy(() -> missing.replayAfter(new ProjectionCursor(RUN_ID.value(), 0)))
                .isInstanceOf(EventProjectionException.class)
                .hasMessageContaining("authoritative run view is unavailable");

        AgentRunId other = new AgentRunId("other-run");
        BoundedRunEventProjector mismatched =
                new BoundedRunEventProjector(RUN_ID, 1, ignored -> Optional.of(view(other, AgentRunStatus.RUNNING)));
        mismatched.append(lifecycle(1, "event-1", "RUNNING"));
        mismatched.append(lifecycle(3, "event-3", "RUNNING"));
        assertThatThrownBy(() -> mismatched.replayAfter(new ProjectionCursor(RUN_ID.value(), 0)))
                .isInstanceOf(EventProjectionException.class)
                .hasMessageContaining("another run");
    }

    @Test
    void keepsCompletionFailureCancellationAndTimeoutDistinctAndRejectsLateEvents() {
        assertTerminal("COMPLETED", RunTerminalKind.COMPLETED);
        assertTerminal("FAILED", RunTerminalKind.FAILED);
        assertTerminal("CANCELLED", RunTerminalKind.CANCELLED);
        assertTerminal("TIMEOUT", RunTerminalKind.TIMEOUT);

        BoundedRunEventProjector projector = projector(8, AgentRunStatus.COMPLETED);
        AgentRunEvent completed = lifecycle(1, "terminal", "COMPLETED");
        projector.append(completed);
        assertThat(((AppendResult.Accepted) projector.append(completed)).duplicate()).isTrue();
        assertThatThrownBy(() -> projector.append(lifecycle(2, "late", "RUNNING")))
                .isInstanceOf(EventProjectionException.class)
                .hasMessageContaining("after terminal");
    }

    @Test
    void rejectsCrossRunEventsCursorsAndUnknownInteractionVocabulary() {
        BoundedRunEventProjector projector = projector(8, AgentRunStatus.RUNNING);
        AgentRunEvent crossRun = event(
                new AgentRunId("other-run"),
                1,
                "event-x",
                new RunEventPayloads.RunLifecycle("RUNNING", 1, "started"));

        assertThatThrownBy(() -> projector.append(crossRun))
                .isInstanceOf(EventProjectionException.class)
                .hasMessageContaining("another run");
        assertThatThrownBy(() -> projector.replayAfter(new ProjectionCursor("other-run", 0)))
                .isInstanceOf(EventProjectionException.class)
                .hasMessageContaining("another run");
        assertThatThrownBy(() -> projector.append(event(
                        RUN_ID,
                        1,
                        "event-unknown",
                        new RunEventPayloads.InteractionLifecycle(
                                "request-1", "made-up-kind", "PENDING", "ask"))))
                .isInstanceOf(EventProjectionException.class)
                .hasMessageContaining("unknown interaction kind");
    }

    private static void assertTerminal(String sourceStatus, RunTerminalKind expected) {
        BoundedRunEventProjector projector = projector(2, AgentRunStatus.valueOf(sourceStatus));
        projector.append(lifecycle(1, "terminal-" + sourceStatus, sourceStatus));
        assertThat(projector.terminalKind()).isEqualTo(expected);
    }

    private static BoundedRunEventProjector projector(int capacity, AgentRunStatus status) {
        return new BoundedRunEventProjector(RUN_ID, capacity, ignored -> Optional.of(view(status)));
    }

    private static AgentRunEvent lifecycle(long sequence, String eventId, String status) {
        return event(
                RUN_ID,
                sequence,
                eventId,
                new RunEventPayloads.RunLifecycle(status, sequence, "test"));
    }

    private static AgentRunEvent interaction(long sequence, String eventId, String requestId) {
        return event(
                RUN_ID,
                sequence,
                eventId,
                new RunEventPayloads.InteractionLifecycle(
                        requestId, "clarification", "PENDING", "submit"));
    }

    private static AgentRunEvent event(
            AgentRunId runId, long sequence, String eventId, AgentRunEvent.Payload payload) {
        return new AgentRunEvent(
                eventId,
                payload instanceof RunEventPayloads.InteractionLifecycle
                        ? "interaction.lifecycle"
                        : "run.lifecycle",
                "1",
                runId,
                SESSION_ID,
                sequence,
                new RunEventCursor(runId, "test-feed", OptionalLong.of(sequence)),
                Instant.parse("2026-09-22T10:00:00Z").plusSeconds(sequence),
                Optional.empty(),
                Optional.empty(),
                payload);
    }

    private static AgentRunViewSnapshot view(AgentRunStatus status) {
        return view(RUN_ID, status);
    }

    private static AgentRunViewSnapshot view(AgentRunId runId, AgentRunStatus status) {
        AgentRunSnapshot snapshot = new AgentRunSnapshot(
                runId,
                status,
                7,
                Instant.parse("2026-09-22T10:00:00Z"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        return new AgentRunViewSnapshot(SESSION_ID, snapshot);
    }
}

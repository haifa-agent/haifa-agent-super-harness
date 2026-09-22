package io.haifa.agent.superharness.events;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunViewSnapshot;
import io.haifa.agent.runtime.api.InteractionKind;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.RunEventPayloads;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A single-process, bounded projection over the public Haifa run-event feed.
 * This class is transport-neutral: SSE names and payload serialization belong
 * to the later compatibility-server adapter.
 */
public final class BoundedRunEventProjector {
    private final AgentRunId runId;
    private final int capacity;
    private final AuthoritativeRunViewSource authoritativeViews;
    private final ArrayDeque<ProjectedRunEvent> retained = new ArrayDeque<>();
    private final Map<StableEventKey, ProjectedRunEvent> retainedByKey = new HashMap<>();
    private long lastSourceSequence;
    private long projectionSequence;
    private RunTerminalKind terminalKind = RunTerminalKind.ACTIVE;

    public BoundedRunEventProjector(
            AgentRunId runId, int capacity, AuthoritativeRunViewSource authoritativeViews) {
        this(runId, capacity, authoritativeViews, 0);
    }

    public BoundedRunEventProjector(
            AgentRunId runId,
            int capacity,
            AuthoritativeRunViewSource authoritativeViews,
            long startingExclusiveSourceSequence) {
        this.runId = Objects.requireNonNull(runId, "runId");
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (startingExclusiveSourceSequence < 0) {
            throw new IllegalArgumentException("startingExclusiveSourceSequence must be non-negative");
        }
        this.capacity = capacity;
        this.authoritativeViews = Objects.requireNonNull(authoritativeViews, "authoritativeViews");
        this.lastSourceSequence = startingExclusiveSourceSequence;
    }

    public synchronized AppendResult append(AgentRunEvent source) {
        validateSource(source);
        StableEventKey key = StableEventKey.from(source);
        ProjectedRunEvent prior = retainedByKey.get(key);
        if (prior != null) {
            if (!prior.source().equals(source)) {
                throw new EventProjectionException("event key was reused with different content: " + key);
            }
            return new AppendResult.Accepted(prior, true);
        }
        if (terminalKind.terminal()) {
            throw new EventProjectionException("new event arrived after terminal state " + terminalKind);
        }
        long expected = lastSourceSequence + 1;
        if (source.sequence() > expected) {
            ProjectionGap gap = new ProjectionGap(
                    ProjectionGap.Reason.SOURCE_SEQUENCE_GAP,
                    expected,
                    earliestAvailableSequence(),
                    projectionSequence);
            return new AppendResult.Gap(gap, requireAuthoritativeView());
        }
        if (source.sequence() < expected) {
            throw new EventProjectionException(
                    "non-monotonic source sequence " + source.sequence() + ", expected " + expected);
        }

        RunTerminalKind eventTerminal = terminalKind(source);
        Optional<ProjectedInteraction> interaction = interaction(source);
        projectionSequence++;
        ProjectedRunEvent projected = new ProjectedRunEvent(
                key,
                new ProjectionCursor(runId.value(), projectionSequence),
                source,
                eventTerminal,
                interaction);
        retained.addLast(projected);
        retainedByKey.put(key, projected);
        lastSourceSequence = source.sequence();
        if (eventTerminal.terminal()) {
            terminalKind = eventTerminal;
        }
        while (retained.size() > capacity) {
            ProjectedRunEvent removed = retained.removeFirst();
            retainedByKey.remove(removed.key());
        }
        return new AppendResult.Accepted(projected, false);
    }

    public synchronized ReplayResult replayAfter(ProjectionCursor cursor) {
        Objects.requireNonNull(cursor, "cursor");
        if (!runId.value().equals(cursor.runId())) {
            throw new EventProjectionException("cursor belongs to another run");
        }
        if (cursor.sequence() > projectionSequence) {
            throw new EventProjectionException("cursor is ahead of the projection head");
        }
        long earliest = earliestAvailableSequence();
        if (cursor.sequence() < earliest - 1) {
            ProjectionGap gap = new ProjectionGap(
                    ProjectionGap.Reason.RETENTION_WINDOW_EXCEEDED,
                    cursor.sequence(),
                    earliest,
                    projectionSequence);
            return new ReplayResult.Gap(gap, requireAuthoritativeView());
        }
        List<ProjectedRunEvent> events = new ArrayList<>();
        for (ProjectedRunEvent event : retained) {
            if (event.cursor().sequence() > cursor.sequence()) {
                events.add(event);
            }
        }
        return new ReplayResult.Events(events, head());
    }

    public synchronized ProjectionCursor head() {
        return new ProjectionCursor(runId.value(), projectionSequence);
    }

    public synchronized RunTerminalKind terminalKind() {
        return terminalKind;
    }

    private void validateSource(AgentRunEvent source) {
        Objects.requireNonNull(source, "source");
        if (!runId.equals(source.runId())) {
            throw new EventProjectionException("event belongs to another run");
        }
        if (source.eventId() == null || source.eventId().isBlank()) {
            throw new EventProjectionException("eventId must not be blank");
        }
        if (source.eventType() == null || source.eventType().isBlank()) {
            throw new EventProjectionException("eventType must not be blank");
        }
        if (source.eventSchemaVersion() == null || source.eventSchemaVersion().isBlank()) {
            throw new EventProjectionException("eventSchemaVersion must not be blank");
        }
        if (source.sequence() < 1) {
            throw new EventProjectionException("source sequence must be positive");
        }
        if (!runId.equals(source.cursor().runId())) {
            throw new EventProjectionException("source cursor belongs to another run");
        }
    }

    private AgentRunViewSnapshot requireAuthoritativeView() {
        AgentRunViewSnapshot view = authoritativeViews
                .load(runId)
                .orElseThrow(() -> new EventProjectionException(
                        "authoritative run view is unavailable for gap recovery"));
        if (!runId.equals(view.snapshot().runId())) {
            throw new EventProjectionException("authoritative view belongs to another run");
        }
        return view;
    }

    private long earliestAvailableSequence() {
        return retained.isEmpty() ? projectionSequence + 1 : retained.getFirst().cursor().sequence();
    }

    private static RunTerminalKind terminalKind(AgentRunEvent source) {
        if (!(source.payload() instanceof RunEventPayloads.RunLifecycle lifecycle)) {
            return RunTerminalKind.ACTIVE;
        }
        try {
            AgentRunStatus status = AgentRunStatus.valueOf(lifecycle.status().toUpperCase(Locale.ROOT));
            return RunTerminalKind.from(status);
        } catch (IllegalArgumentException exception) {
            throw new EventProjectionException("unknown run lifecycle status: " + lifecycle.status());
        }
    }

    private static Optional<ProjectedInteraction> interaction(AgentRunEvent source) {
        if (!(source.payload() instanceof RunEventPayloads.InteractionLifecycle lifecycle)) {
            return Optional.empty();
        }
        if (lifecycle.requestId() == null || lifecycle.requestId().isBlank()) {
            throw new EventProjectionException("interaction requestId must not be blank");
        }
        final InteractionKind kind;
        try {
            kind = new InteractionKind(lifecycle.kind());
        } catch (IllegalArgumentException exception) {
            throw new EventProjectionException("invalid interaction kind: " + lifecycle.kind());
        }
        if (!kind.known()) {
            throw new EventProjectionException("unknown interaction kind: " + lifecycle.kind());
        }
        final InteractionState state;
        try {
            state = InteractionState.valueOf(lifecycle.state().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new EventProjectionException("unknown interaction state: " + lifecycle.state());
        }
        return Optional.of(new ProjectedInteraction(
                lifecycle.requestId(), kind, state, lifecycle.actionOrReason()));
    }
}

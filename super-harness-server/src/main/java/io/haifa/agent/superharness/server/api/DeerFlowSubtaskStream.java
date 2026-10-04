package io.haifa.agent.superharness.server.api;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunUsage;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.ChildRunView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.RunEventSubscription;
import io.haifa.agent.runtime.api.RunMessageCursor;
import io.haifa.agent.superharness.server.run.HaifaRunBridge;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;

/** One SSE connection's disposable cursors and public fact fold; no Child execution ownership. */
final class DeerFlowSubtaskStream implements AutoCloseable {
    private static final int PAGE_SIZE = 32;
    private static final int PAGES_PER_DRAIN = 16;
    private static final int MAX_CONNECTED_CHILDREN = 512;
    private static final long PERSISTENCE_RECHECK_NANOS = Duration.ofMillis(100).toNanos();
    private final HaifaRunBridge bridge;
    private final AgentRunId parent;
    private final java.util.function.Supplier<List<ChildRunView>> childQuery;
    private final java.util.function.LongSupplier clock;
    private long nextChildrenReadNanos = Long.MIN_VALUE;
    private final Map<AgentRunId, ChildCursor> cursors = new LinkedHashMap<>();

    @FunctionalInterface
    interface Frames {
        void write(Map<String, Object> event) throws IOException;
    }

    DeerFlowSubtaskStream(HaifaRunBridge bridge, AgentRunId parent) {
        this(bridge, parent, () -> bridge.children(parent), System::nanoTime);
    }

    /** Package-private query/clock observation seam; production always reads the public SDK. */
    DeerFlowSubtaskStream(HaifaRunBridge bridge, AgentRunId parent,
            java.util.function.Supplier<List<ChildRunView>> childQuery, java.util.function.LongSupplier clock) {
        this.bridge = bridge;
        this.parent = parent;
        this.childQuery = childQuery;
        this.clock = clock;
    }

    /** False at Parent end means actual Children or their persistent message prefix still need draining. */
    boolean drain(boolean parentTerminal, Frames frames) throws IOException {
        long now = clock.getAsLong();
        if (!parentTerminal && now < nextChildrenReadNanos
                && cursors.values().stream().noneMatch(cursor -> !cursor.terminalSent && cursor.dirty.get())) {
            return false;
        }
        nextChildrenReadNanos = now + PERSISTENCE_RECHECK_NANOS;
        List<ChildRunView> children = childQuery.get();
        List<ChildRunView> unseen = children.stream().filter(child -> !cursors.containsKey(child.runId())).toList();
        Map<AgentRunId, String> taskIds = unseen.isEmpty() ? Map.of() : bridge.childTaskIds(parent, unseen);
        boolean complete = true;
        for (ChildRunView child : children) {
            if (!child.parentRunId().equals(parent)) throw new IllegalStateException("Child belongs to a different Parent");
            ChildCursor cursor = cursors.get(child.runId());
            if (cursor == null) {
                if (cursors.size() >= MAX_CONNECTED_CHILDREN) throw new IllegalStateException("Subtask connection limit exceeded");
                // The public Bridge association verifies Parent task Tools once for this batch.
                String taskId = taskIds.get(child.runId());
                if (taskId == null) throw new IllegalStateException("Child has no exact task Tool identity");
                cursor = new ChildCursor(child.runId(), taskId);
                ChildCursor registered = cursor;
                // Register before reading any durable prefix. Listener callbacks never write HTTP frames.
                cursor.subscription = bridge.subscribe(child.runId(), cursor.events,
                        event -> registered.dirty.set(true));
                cursors.put(child.runId(), cursor);
            }
            if (cursor.terminalSent) continue;
            if (!parentTerminal && !child.status().isTerminal()
                    && !cursor.dirty.getAndSet(false) && now < cursor.nextReadNanos) {
                complete = false;
                continue;
            }
            cursor.nextReadNanos = now + PERSISTENCE_RECHECK_NANOS;
            boolean factsAtHead = readFacts(child, cursor);
            Map<String, Object> facts = facts(child.usage(), cursor, factsAtHead);
            if (!cursor.startedSent && child.startedAt().isPresent()) {
                Map<String, Object> started = DeerFlowSubtaskProjection.lifecycle("task_started", cursor.taskId, facts);
                started.put("description", child.objective());
                frames.write(Map.copyOf(started));
                cursor.startedSent = true;
            }
            boolean messagesAtHead = readMessages(cursor, facts, frames);
            if (child.status().isTerminal() && factsAtHead && messagesAtHead) {
                AgentRunSnapshot snapshot = bridge.snapshot(child.runId());
                if (!snapshot.status().isTerminal()) throw new IllegalStateException("Child terminal facts conflict");
                Map<String, Object> terminal = DeerFlowSubtaskProjection.lifecycle(terminalType(snapshot), cursor.taskId,
                        facts(snapshot.usage(), cursor, true));
                if (snapshot.status() == io.haifa.agent.core.run.AgentRunStatus.COMPLETED) {
                    String result = snapshot.output().orElse("");
                    terminal.put("result", DeerFlowSubtaskProjection.bounded(result));
                    if (result.codePointCount(0, result.length()) > DeerFlowSubtaskProjection.MAX_CHARS) {
                        terminal.put("result_truncated", true);
                    }
                } else {
                    terminal.put("error", snapshot.error().map(error -> error.code().wireCode())
                            .orElseGet(() -> snapshot.terminationReason().map(reason -> reason.code()).orElse("Child did not complete")));
                }
                frames.write(Map.copyOf(terminal));
                cursor.terminalSent = true;
                cursor.subscription.close();
                if (!cursor.subscription.closed()) throw new IllegalStateException("Child subscription remained open");
            } else {
                complete = false;
                if (!factsAtHead || !messagesAtHead) cursor.dirty.set(true);
            }
        }
        return complete;
    }

    private boolean readFacts(ChildRunView child, ChildCursor cursor) {
        for (int pageNumber = 0; pageNumber < PAGES_PER_DRAIN; pageNumber++) {
            var page = bridge.events(child.runId(), cursor.events, PAGE_SIZE);
            long previous = cursor.events.exclusiveSequence().orElse(0);
            for (var event : page.items()) {
                if (!event.runId().equals(child.runId())) throw new IllegalStateException("Child event belongs to another Run");
                if (event.sequence() <= previous) throw new IllegalStateException("Child event cursor did not advance");
                previous = event.sequence();
                if (event.payload() instanceof RunEventPayloads.ModelLifecycle model) {
                    cursor.modelName = model.modelId();
                    if ("model.call.succeeded".equals(event.eventType()) && "SUCCEEDED".equals(model.status())) {
                        var models = sumUsage(cursor.successfulModels, 1);
                        var input = sumUsage(cursor.input, model.inputTokens());
                        var output = sumUsage(cursor.output, model.outputTokens());
                        if (models.isPresent() && input.isPresent() && output.isPresent()) {
                            cursor.successfulModels = models.getAsLong();
                            cursor.input = input.getAsLong();
                            cursor.output = output.getAsLong();
                        } else {
                            cursor.unknownUsage = true;
                        }
                    } else if ("model.call.failed".equals(event.eventType())) {
                        cursor.unknownUsage = true;
                    }
                }
            }
            if (!page.nextCursor().runId().equals(child.runId())
                    || !page.nextCursor().feedVersion().equals(cursor.events.feedVersion())
                    || page.nextCursor().exclusiveSequence().orElse(0) < previous
                    || (page.hasMore() && page.nextCursor().exclusiveSequence().orElse(0)
                            <= cursor.events.exclusiveSequence().orElse(0))) {
                throw new IllegalStateException("Invalid Child event page cursor");
            }
            cursor.events = page.nextCursor();
            if (!page.hasMore()) return true;
        }
        return false;
    }

    private boolean readMessages(ChildCursor cursor, Map<String, Object> facts, Frames frames) throws IOException {
        for (int pageNumber = 0; pageNumber < PAGES_PER_DRAIN; pageNumber++) {
            var page = bridge.messages(cursor.childId, cursor.messages, PAGE_SIZE);
            // Child can start between the children() snapshot and this durable read. Leave the
            // prefix unread until the next actual startedAt snapshot; never invent a started frame.
            if (!cursor.startedSent && !page.items().isEmpty()) return false;
            long after = cursor.messages.exclusiveSequence();
            for (var message : page.items()) {
                if (message.sequence() <= after) throw new IllegalStateException("Child message cursor did not advance");
                after = message.sequence();
                frames.write(DeerFlowSubtaskProjection.running(cursor.taskId, message, facts));
            }
            if (!page.nextCursor().runId().equals(cursor.childId)
                    || !page.headCursor().runId().equals(cursor.childId)
                    || page.nextCursor().exclusiveSequence() < after
                    || page.headCursor().exclusiveSequence() < page.nextCursor().exclusiveSequence()
                    || (page.hasMore() && page.nextCursor().exclusiveSequence() <= cursor.messages.exclusiveSequence())) {
                throw new IllegalStateException("Invalid Child message page cursor");
            }
            cursor.messages = page.nextCursor();
            if (!page.hasMore()) return true;
        }
        return false;
    }

    private static Map<String, Object> facts(AgentRunUsage usage, ChildCursor cursor, boolean eventsAtHead) {
        Map<String, Object> facts = new LinkedHashMap<>();
        if (cursor.modelName != null && !cursor.modelName.isBlank()) facts.put("model_name", cursor.modelName);
        if (eventsAtHead && !cursor.unknownUsage && cursor.successfulModels > 0
                && cursor.successfulModels == usage.modelCalls()
                && cursor.input == usage.inputTokens() && cursor.output == usage.outputTokens()
                && cursor.input <= DeerFlowSubtaskProjection.MAX_SAFE_INTEGER - cursor.output) {
            facts.put("usage", Map.of("input_tokens", cursor.input, "output_tokens", cursor.output,
                    "total_tokens", cursor.input + cursor.output));
        }
        return Map.copyOf(facts);
    }

    static OptionalLong sumUsage(long accumulated, long addition) {
        try {
            return OptionalLong.of(Math.addExact(accumulated, addition));
        } catch (ArithmeticException overflow) {
            return OptionalLong.empty();
        }
    }

    private static String terminalType(AgentRunSnapshot snapshot) {
        return switch (snapshot.status()) {
            case COMPLETED -> "task_completed";
            case CANCELLED -> "task_cancelled";
            case TIMEOUT -> "task_timed_out";
            case FAILED -> "task_failed";
            default -> throw new IllegalStateException("Child has no legal terminal status");
        };
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (ChildCursor cursor : cursors.values()) {
            try {
                cursor.subscription.close();
                if (!cursor.subscription.closed()) throw new IllegalStateException("Child subscription remained open");
            } catch (RuntimeException unavailable) {
                if (failure == null) failure = unavailable;
            }
        }
        cursors.clear();
        if (failure != null) throw new IllegalStateException("Subtask subscriptions could not be closed");
    }

    private static final class ChildCursor {
        private final AgentRunId childId;
        private final String taskId;
        private final AtomicBoolean dirty = new AtomicBoolean(true);
        private RunEventCursor events;
        private RunMessageCursor messages;
        private RunEventSubscription subscription;
        private boolean startedSent;
        private boolean terminalSent;
        private boolean unknownUsage;
        private long successfulModels;
        private long input;
        private long output;
        private long nextReadNanos;
        private String modelName;

        private ChildCursor(AgentRunId childId, String taskId) {
            this.childId = childId;
            this.taskId = taskId;
            this.events = RunEventCursor.beforeFirst(childId);
            this.messages = new RunMessageCursor(childId, 0);
        }
    }
}

package io.haifa.agent.superharness.server.api;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEventType;
import io.haifa.agent.runtime.api.AgentRunOutputListener;
import io.haifa.agent.runtime.api.RunOutputCursor;
import io.haifa.agent.runtime.api.RunOutputSubscription;
import io.haifa.agent.superharness.server.run.HaifaRunBridge;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One SSE connection's transient text buffer; accepted history remains SDK-owned. */
final class LiveParentValuesProjector implements AgentRunOutputListener, AutoCloseable {
    private final AgentRunId runId;
    private final Map<String, StringBuilder> generations = new LinkedHashMap<>();
    private RunOutputSubscription subscription;
    private long durableRevision;
    private long cachedSourceSequence = -1;
    private long cachedDurableRevision = -1;
    private long refreshAfterNanos;
    private DurableSnapshot cachedSnapshot;
    static final long DURABLE_REFRESH_NANOS = java.time.Duration.ofSeconds(1).toNanos();

    /** Cached transport projection, not a second accepted-message store. */
    record DurableSnapshot(List<Map<String, Object>> messages, Set<String> visibleAcceptedCalls) {
        DurableSnapshot {
            messages = List.copyOf(messages);
            visibleAcceptedCalls = Set.copyOf(visibleAcceptedCalls);
        }
    }

    LiveParentValuesProjector(String runId) {
        this.runId = new AgentRunId(runId);
    }

    // Called exactly once, before this connection chooses its assistant wire protocol.
    boolean open(HaifaRunBridge bridge) {
        try {
            subscription = bridge.subscribeOutput(runId, RunOutputCursor.BEFORE_FIRST, this);
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override
    public synchronized void onOutput(AgentRunOutputEvent event) {
        if (!runId.equals(event.runId())) return;
        String id = event.modelCallId();
        if (id == null || id.isBlank()) return;
        if (event.type() != AgentRunOutputEventType.ASSISTANT_TEXT_DELTA) durableRevision++;
        if (event.type() == AgentRunOutputEventType.RUN_OUTPUT_STARTED) {
            generations.put(id, new StringBuilder());
        } else if (event.type() == AgentRunOutputEventType.ASSISTANT_TEXT_DELTA) {
            // The SDK channel has bounded replay. Without this generation's STARTED event,
            // a replayed delta may be a suffix: keep durable history instead of inventing a prefix.
            StringBuilder text = generations.get(id);
            if (text != null) text.append(event.textDelta());
        } else if (event.type() == AgentRunOutputEventType.RUN_OUTPUT_SUPERSEDED
                || event.type() == AgentRunOutputEventType.RUN_OUTPUT_FAILED) {
            generations.remove(id);
        }
        // COMMITTED is an output-channel fact, not the accepted-message association. Keep
        // provisional text until the caller sees the exact public accepted modelCallId.
    }

    synchronized List<Map<String, Object>> liveRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        generations.forEach((id, text) -> {
            if (!text.isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", id);
                row.put("type", "ai");
                row.put("content", text.toString());
                row.put("tool_calls", List.of());
                row.put("additional_kwargs", Map.of());
                rows.add(row);
            }
        });
        return rows;
    }

    synchronized void retire(String acceptedModelCallId) {
        generations.remove(acceptedModelCallId);
    }

    synchronized long durableRevision() { return durableRevision; }

    synchronized boolean needsDurableSnapshot(long sequence, long revision, long nowNanos) {
        return cachedSnapshot == null || sequence != cachedSourceSequence
                || revision != cachedDurableRevision || nowNanos >= refreshAfterNanos;
    }

    synchronized void cacheDurableSnapshot(DurableSnapshot snapshot, long sequence,
            long revisionBeforeRead, long nowNanos) {
        cachedSnapshot = snapshot;
        cachedSourceSequence = sequence;
        // A commit during the SDK read must remain dirty on the next iteration.
        cachedDurableRevision = revisionBeforeRead;
        refreshAfterNanos = nowNanos + DURABLE_REFRESH_NANOS;
    }

    synchronized DurableSnapshot durableSnapshot() { return cachedSnapshot; }

    static boolean acceptedMessageIsVisible(String nativeMessageId, List<String> nativeToolIds,
            Set<String> visibleNativeMessages, Set<String> visibleToolIds) {
        return visibleNativeMessages.contains(nativeMessageId)
                || (!nativeToolIds.isEmpty() && visibleToolIds.containsAll(nativeToolIds));
    }

    @Override
    public void close() {
        if (subscription != null) subscription.close();
        synchronized (this) { generations.clear(); cachedSnapshot = null; }
    }
}

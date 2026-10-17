package io.haifa.agent.superharness.server.channels.feishu;

import io.haifa.agent.superharness.server.api.DeerFlowConversationService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 2 observation seam: consumes shared {@link DeerFlowConversationService.RunEventSink} frames
 * and projects bounded IM progress (tools / artifacts / todos / subagent lifecycle) without claiming
 * web-grade Streamdown fidelity. Projection failures never abort the Run.
 */
public final class FeishuRunProjector implements DeerFlowConversationService.RunEventSink {
    /** Minimum gap between progress cards so Feishu is not flooded. */
    static final long MIN_PROGRESS_GAP_MS = 800L;
    static final int MAX_PROGRESS_EVENTS = 24;
    static final int TOOL_RESULT_TRUNCATE = 160;
    static final int MAX_GAP_DIAGNOSTICS = 4;

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String summary) throws Exception;
    }

    private final ProgressListener progress;
    private final long minGapMs;
    private final int maxProgressEvents;

    private boolean sawEnd;
    private boolean sawError;
    private boolean sawValues;
    private boolean sawMessages;
    private boolean sawGap;
    private long lastProgressAtMs;
    private final AtomicInteger progressCount = new AtomicInteger();
    private final Set<String> toolStarted = new LinkedHashSet<>();
    private final Set<String> toolFinished = new LinkedHashSet<>();
    private final List<String> artifacts = new ArrayList<>();
    private final List<String> todoLines = new ArrayList<>();
    private final List<String> progressLog = new ArrayList<>();
    private final List<String> gapDiagnostics = new ArrayList<>();
    private int subagentStarts;
    private int subagentEnds;

    public FeishuRunProjector(ProgressListener progress) {
        this(progress, MIN_PROGRESS_GAP_MS, MAX_PROGRESS_EVENTS);
    }

    FeishuRunProjector(ProgressListener progress, long minGapMs, int maxProgressEvents) {
        this.progress = progress == null ? summary -> {} : progress;
        this.minGapMs = Math.max(0L, minGapMs);
        this.maxProgressEvents = Math.max(1, maxProgressEvents);
    }

    @Override
    public void event(String event, Object data, String id) throws IOException {
        try {
            switch (event == null ? "" : event) {
                case "end" -> sawEnd = true;
                case "error" -> sawError = true;
                case "values" -> sawValues = true;
                case "gap" -> onGap(data);
                case "messages" -> {
                    sawMessages = true;
                    onMessages(data);
                }
                case "updates" -> onUpdates(data);
                case "custom" -> onCustom(data);
                default -> { }
            }
        } catch (RuntimeException ignored) {
            // Projection must not take down the shared observeTurn loop.
        }
    }

    @Override
    public void heartbeat() {}

    public boolean sawEnd() {
        return sawEnd;
    }

    public boolean sawError() {
        return sawError;
    }

    /**
     * True when the shared projection reported a replay gap. A gap terminates the observation
     * without an {@code end} frame, so the caller must fall back to the durable thread state
     * instead of treating the turn as finished (W20-BUG-001).
     */
    public boolean sawGap() {
        return sawGap;
    }

    /** Bounded gap diagnostics (never the reply text). */
    public List<String> gapDiagnostics() {
        return List.copyOf(gapDiagnostics);
    }

    public boolean sawValues() {
        return sawValues;
    }

    public boolean sawMessages() {
        return sawMessages;
    }

    public List<String> artifacts() {
        return List.copyOf(artifacts);
    }

    public List<String> todoLines() {
        return List.copyOf(todoLines);
    }

    public List<String> progressLog() {
        return List.copyOf(progressLog);
    }

    public int toolProgressCount() {
        return toolStarted.size() + toolFinished.size();
    }

    public int subagentStarts() {
        return subagentStarts;
    }

    public int subagentEnds() {
        return subagentEnds;
    }

    private void onMessages(Object data) {
        if (!(data instanceof List<?> frames) || frames.isEmpty()) {
            return;
        }
        Object first = frames.getFirst();
        if (!(first instanceof Map<?, ?> message)) {
            return;
        }
        String type = string(message.get("type"));
        if (type.isEmpty()) {
            type = string(message.get("role"));
        }
        if ("ai".equals(type) || "assistant".equals(type)) {
            Object calls = message.get("tool_calls");
            if (calls instanceof List<?> toolCalls) {
                for (Object call : toolCalls) {
                    if (call instanceof Map<?, ?> entry) {
                        String name = string(entry.get("name"));
                        String callId = string(entry.get("id"));
                        if (name.isBlank()) {
                            continue;
                        }
                        String key = callId.isBlank() ? name : callId;
                        if (toolStarted.add(key)) {
                            emit("🔧 工具开始：`" + name + "`");
                        }
                    }
                }
            }
            return;
        }
        if ("tool".equals(type)) {
            String name = string(message.get("name"));
            String callId = string(message.get("tool_call_id"));
            if (name.isBlank()) {
                return;
            }
            String key = callId.isBlank() ? name : callId;
            if (toolFinished.add(key)) {
                String result = truncate(messageText(message.get("content")), TOOL_RESULT_TRUNCATE);
                if (result.isBlank()) {
                    emit("✅ 工具完成：`" + name + "`");
                } else {
                    emit("✅ 工具完成：`" + name + "`\n" + result);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void onUpdates(Object data) {
        if (!(data instanceof Map<?, ?> updates)) {
            return;
        }
        Object artifactsNode = updates.get("__artifacts__");
        if (artifactsNode instanceof Map<?, ?> artifactMap) {
            Object list = artifactMap.get("artifacts");
            if (list instanceof List<?> paths) {
                boolean added = false;
                for (Object path : paths) {
                    String value = string(path);
                    if (!value.isBlank() && !artifacts.contains(value)) {
                        artifacts.add(value);
                        added = true;
                    }
                }
                if (added) {
                    emit("📎 产出文件 " + artifacts.size() + " 个（详见最终回复）");
                }
            }
        }
        Object tools = updates.get("tools");
        if (tools instanceof Map<?, ?> toolsMap) {
            Object todos = toolsMap.get("todos");
            if (todos instanceof List<?> items) {
                List<String> lines = new ArrayList<>();
                for (Object item : items) {
                    if (item instanceof Map<?, ?> todo) {
                        String content = firstNonBlank(string(todo.get("content")), string(todo.get("text")));
                        String status = firstNonBlank(string(todo.get("status")), "pending");
                        if (!content.isBlank()) {
                            lines.add("- [" + status + "] " + truncate(content, 120));
                        }
                    }
                }
                if (!lines.isEmpty()) {
                    todoLines.clear();
                    todoLines.addAll(lines);
                    emit("🧾 待办更新：\n" + String.join("\n", lines));
                }
            }
        }
    }

    private void onCustom(Object data) {
        if (!(data instanceof Map<?, ?> custom)) {
            return;
        }
        String type = firstNonBlank(string(custom.get("type")), string(custom.get("event_type")));
        if (type.contains("task_started") || "task_started".equals(type)) {
            subagentStarts++;
            String taskId = string(custom.get("task_id"));
            emit("🧩 子任务开始" + (taskId.isBlank() ? "" : " `" + truncate(taskId, 40) + "`"));
            return;
        }
        if (type.contains("task_completed")
                || type.contains("task_failed")
                || "task_completed".equals(type)
                || "task_failed".equals(type)) {
            subagentEnds++;
            emit("🧩 子任务结束");
        }
    }

    /**
     * A replay gap ends this observation: the shared Run projector can no longer replay the
     * frames this turn missed. Feishu never streams assistant text, so the durable thread state
     * (readTurn) stays the recovery source; record a bounded diagnostic instead of swallowing the
     * frame silently (W20-BUG-001).
     */
    private void onGap(Object data) {
        sawGap = true;
        if (gapDiagnostics.size() >= MAX_GAP_DIAGNOSTICS) {
            return;
        }
        String reason = data instanceof Map<?, ?> map
                ? firstNonBlank(string(map.get("code")), truncate(string(map.get("run_id")), 80))
                : truncate(string(data), 120);
        gapDiagnostics.add(reason);
    }

    private void emit(String summary) {
        if (summary == null || summary.isBlank()) {
            return;
        }
        String bounded = truncate(summary.strip(), 900);
        long now = System.currentTimeMillis();
        if (progressCount.get() >= maxProgressEvents) {
            return;
        }
        if (lastProgressAtMs > 0 && now - lastProgressAtMs < minGapMs) {
            return;
        }
        lastProgressAtMs = now;
        progressCount.incrementAndGet();
        progressLog.add(bounded);
        try {
            progress.onProgress(bounded);
        } catch (Exception ignored) {
            // Outbound card failure must not abort observation.
        }
    }

    static String messageText(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof List<?> parts) {
            StringBuilder builder = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof String text) {
                    builder.append(text);
                } else if (part instanceof Map<?, ?> map && map.get("text") != null) {
                    builder.append(string(map.get("text")));
                }
            }
            return builder.toString();
        }
        return content.toString();
    }

    static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip();
        if (stripped.length() <= max) {
            return stripped;
        }
        return stripped.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String left, String right) {
        return left == null || left.isBlank() ? (right == null ? "" : right) : left;
    }

    /** Formats final artifact download hints for the Feishu reply (Gateway-relative paths). */
    public static String artifactHints(String threadId, List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("\n\n---\n**产出文件**");
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            String normalized = path.startsWith("/") ? path : "/" + path;
            // Display keeps the raw logical path; the open hint URL-encodes each segment so
            // reserved characters (#, ?, %) cannot become fragments or query separators.
            String encoded = encodeArtifactPath(normalized);
            builder.append("\n- `").append(path).append("`");
            if (threadId != null && !threadId.isBlank()) {
                builder.append("\n  打开：`/api/threads/")
                        .append(threadId.trim())
                        .append("/artifacts")
                        .append(encoded)
                        .append("`");
            }
        }
        return builder.toString();
    }

    /**
     * Same per-segment UTF-8 encoding as {@code DeerFlowWorkspaceApi.encodeArtifactPath}: preserve
     * {@code /}, percent-encode everything else that is not unreserved.
     */
    static String encodeArtifactPath(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Arrays.stream(value.split("/", -1))
                .map(FeishuRunProjector::encodePathSegment)
                .reduce((left, right) -> left + "/" + right)
                .orElse("");
    }

    private static String encodePathSegment(String value) {
        StringBuilder result = new StringBuilder();
        for (byte item : value.getBytes(StandardCharsets.UTF_8)) {
            int octet = item & 0xff;
            if ((octet >= 'a' && octet <= 'z')
                    || (octet >= 'A' && octet <= 'Z')
                    || (octet >= '0' && octet <= '9')
                    || octet == '-'
                    || octet == '_'
                    || octet == '.'
                    || octet == '~') {
                result.append((char) octet);
            } else {
                result.append('%').append(String.format(Locale.ROOT, "%02X", octet));
            }
        }
        return result.toString();
    }

    public static String todoAppendix(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        return "\n\n---\n**待办清单**\n" + String.join("\n", lines);
    }

    /** Test helper: build a tool-request messages frame like the frozen projector. */
    public static Object toolRequestFrame(String toolName, String callId) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", callId);
        call.put("name", toolName);
        call.put("args", Map.of());
        call.put("type", "tool_call");
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "ai");
        message.put("content", "");
        message.put("tool_calls", List.of(call));
        return List.of(message, Map.of("langgraph_node", "tools"));
    }

    /** Test helper: build a tool-result messages frame. */
    public static Object toolResultFrame(String toolName, String callId, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "tool");
        message.put("name", toolName);
        message.put("tool_call_id", callId);
        message.put("content", content);
        return List.of(message, Map.of("langgraph_node", "tools"));
    }
}

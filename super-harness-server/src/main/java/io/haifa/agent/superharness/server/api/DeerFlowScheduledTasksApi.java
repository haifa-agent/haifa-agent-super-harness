package io.haifa.agent.superharness.server.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.haifa.agent.superharness.server.auth.AccessOwner;
import io.haifa.agent.superharness.server.config.SchedulerRunConfiguration;
import io.haifa.agent.superharness.server.schedule.ScheduleCalculator;
import io.haifa.agent.superharness.server.schedule.ScheduleDispatchService;
import io.haifa.agent.superharness.server.schedule.ScheduleStore;
import io.haifa.agent.superharness.server.schedule.ScheduleValidationException;
import io.haifa.agent.superharness.server.schedule.ScheduledRunStatus;
import io.haifa.agent.superharness.server.schedule.ScheduledTask;
import io.haifa.agent.superharness.server.schedule.ScheduledTaskRun;
import io.haifa.agent.superharness.server.schedule.ScheduledTaskStatus;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Frozen DeerFlow scheduled-task REST compatibility layer.
 *
 * <p>This adapter only performs protocol mapping and validation. Task/occurrence state is owned by
 * {@link ScheduleStore}; launching is owned by {@link ScheduleDispatchService}. No schedule
 * calculation, Run engine or second status machine is duplicated here.
 *
 * <p>Authentication is the existing Gateway guard (401 for an unauthenticated session, loopback
 * only when local auth is disabled). Authorization reuses the existing owner scoping: task reads
 * and writes are filtered by the request owner, and an inaccessible task or Thread is a 404 so the
 * response never becomes an existence oracle.
 */
final class DeerFlowScheduledTasksApi implements HttpHandler {
    private static final int MAX_JSON_BYTES = 128 * 1024;
    private static final int DEFAULT_RUN_PAGE_SIZE = 50;
    private static final int MAXIMUM_RUN_PAGE_SIZE = 200;
    private static final String DEFAULT_ASSISTANT_ID = "lead_agent";
    private static final Pattern AGENT_NAME = Pattern.compile("^[a-z0-9-]+$");
    private static final Pattern THREAD_TASKS =
            Pattern.compile("^/api/threads/([^/]+)/scheduled-tasks$");
    private static final Pattern TASK_RUNS =
            Pattern.compile("^/api/scheduled-tasks/([^/]+)/runs$");
    private static final Pattern TASK_PAUSE =
            Pattern.compile("^/api/scheduled-tasks/([^/]+)/pause$");
    private static final Pattern TASK_RESUME =
            Pattern.compile("^/api/scheduled-tasks/([^/]+)/resume$");
    private static final Pattern TASK_TRIGGER =
            Pattern.compile("^/api/scheduled-tasks/([^/]+)/trigger$");
    private static final Pattern TASK_ITEM =
            Pattern.compile("^/api/scheduled-tasks/([^/]+)$");

    private static final Set<String> CREATE_FIELDS = Set.of(
            "context_mode", "thread_id", "assistant_id", "title", "prompt", "schedule_type",
            "schedule_spec", "timezone");
    private static final Set<String> UPDATE_FIELDS = Set.of(
            "context_mode", "assistant_id", "title", "prompt", "schedule_spec", "timezone");

    private final ObjectMapper json;
    private final ScheduleStore store;
    private final ScheduleDispatchService scheduler;
    private final SchedulerRunConfiguration configuration;
    private final Predicate<String> threadAccessible;
    private final Predicate<String> agentExists;
    private final Supplier<Instant> clock;

    DeerFlowScheduledTasksApi(ObjectMapper json, ScheduleStore store, ScheduleDispatchService scheduler,
            SchedulerRunConfiguration configuration, Predicate<String> threadAccessible,
            Predicate<String> agentExists, Supplier<Instant> clock) {
        this.json = Objects.requireNonNull(json, "json");
        this.store = Objects.requireNonNull(store, "store");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.threadAccessible = Objects.requireNonNull(threadAccessible, "threadAccessible");
        this.agentExists = Objects.requireNonNull(agentExists, "agentExists");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!isLoopbackPeer(exchange)
                && !io.haifa.agent.superharness.server.auth.LocalAuthService.sessionAuthActive()) {
            sendJson(exchange, 403, Map.of("detail", "Local profile accepts loopback clients only"));
            return;
        }
        String path = exchange.getRequestURI().getPath();
        try {
            String method = exchange.getRequestMethod();
            if ("/api/scheduled-tasks".equals(path)) {
                if ("GET".equals(method)) {
                    listTasks(exchange);
                } else if ("POST".equals(method)) {
                    createTask(exchange);
                } else {
                    methodNotAllowed(exchange, "GET, POST");
                }
                return;
            }
            Matcher match = TASK_RUNS.matcher(path);
            if (match.matches()) {
                if (!"GET".equals(method)) {
                    methodNotAllowed(exchange, "GET");
                    return;
                }
                listRuns(exchange, decode(match.group(1)));
                return;
            }
            match = TASK_PAUSE.matcher(path);
            if (match.matches()) {
                if (!"POST".equals(method)) {
                    methodNotAllowed(exchange, "POST");
                    return;
                }
                pauseTask(exchange, decode(match.group(1)));
                return;
            }
            match = TASK_RESUME.matcher(path);
            if (match.matches()) {
                if (!"POST".equals(method)) {
                    methodNotAllowed(exchange, "POST");
                    return;
                }
                resumeTask(exchange, decode(match.group(1)));
                return;
            }
            match = TASK_TRIGGER.matcher(path);
            if (match.matches()) {
                if (!"POST".equals(method)) {
                    methodNotAllowed(exchange, "POST");
                    return;
                }
                triggerTask(exchange, decode(match.group(1)));
                return;
            }
            match = TASK_ITEM.matcher(path);
            if (match.matches()) {
                if ("PATCH".equals(method)) {
                    updateTask(exchange, decode(match.group(1)));
                } else if ("DELETE".equals(method)) {
                    deleteTask(exchange, decode(match.group(1)));
                } else {
                    methodNotAllowed(exchange, "PATCH, DELETE");
                }
                return;
            }
            sendJson(exchange, 404, Map.of("detail", "Not Found"));
        } catch (HttpFailure failure) {
            sendJson(exchange, failure.status, Map.of("detail", failure.getMessage()));
        } catch (ScheduleValidationException invalid) {
            sendJson(exchange, 422, Map.of("detail", invalid.getMessage()));
        } catch (ScheduleStore.ActiveOccurrenceConflict conflict) {
            sendJson(exchange, 409, Map.of("detail", activeConflictDetail(conflict.activeStatus())));
        } catch (IllegalStateException unavailable) {
            sendJson(exchange, 500, Map.of("detail", "Scheduler state is unavailable"));
        }
    }

    /**
     * Handles the Thread-scoped list route that is registered under the shared {@code /api/threads}
     * prefix. Returns false for every other Thread route so the Thread API keeps ownership.
     */
    boolean tryHandle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        Matcher match = THREAD_TASKS.matcher(path);
        if (!match.matches()) {
            return false;
        }
        if (!isLoopbackPeer(exchange)
                && !io.haifa.agent.superharness.server.auth.LocalAuthService.sessionAuthActive()) {
            sendJson(exchange, 403, Map.of("detail", "Local profile accepts loopback clients only"));
            return true;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return true;
        }
        try {
            String threadId = decode(match.group(1));
            if (!threadAccessible.test(threadId)) {
                throw new HttpFailure(404, "Thread not found");
            }
            sendJson(exchange, 200, projections(store.listTasksByThread(owner(), threadId)));
        } catch (HttpFailure failure) {
            sendJson(exchange, failure.status, Map.of("detail", failure.getMessage()));
        }
        return true;
    }

    // ------------------------------------------------------------------- routes

    private void listTasks(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200, projections(store.listTasks(owner())));
    }

    private void createTask(HttpExchange exchange) throws IOException {
        JsonNode body = readObject(exchange);
        rejectUnknownFields(body, CREATE_FIELDS);
        String contextMode = optionalText(body, "context_mode");
        if (contextMode == null) {
            contextMode = ScheduledTask.FRESH_THREAD_PER_RUN;
        }
        validateContextMode(contextMode);
        String threadId = optionalText(body, "thread_id");
        if (ScheduledTask.REUSE_THREAD.equals(contextMode)) {
            if (threadId == null) {
                throw new HttpFailure(422, "reuse_thread requires thread_id");
            }
            requireThreadAccess(threadId);
        }
        String title = requiredNonEmptyText(body, "title");
        String prompt = requiredNonEmptyText(body, "prompt");
        String scheduleType = requiredNonEmptyText(body, "schedule_type");
        if (!"once".equals(scheduleType) && !"cron".equals(scheduleType)
                && !"interval".equals(scheduleType)) {
            throw new HttpFailure(422, "Unsupported schedule_type");
        }
        Map<String, Object> scheduleSpec = scheduleSpec(body);
        String timezone = requiredNonEmptyText(body, "timezone");
        String assistantId = resolveAssistantId(optionalText(body, "assistant_id"));

        Instant now = clock.get();
        Instant nextRunAt = normalizeAndComputeNextRun(scheduleType, scheduleSpec, timezone,
                configuration.minOnceDelaySeconds(), now, null);

        ScheduledTask created = store.createTask(new ScheduleStore.NewTask(null, owner(), threadId,
                contextMode, assistantId, title, prompt, scheduleType, scheduleSpec, timezone,
                nextRunAt), now);
        sendJson(exchange, 200, created.wire());
    }

    private void updateTask(HttpExchange exchange, String taskId) throws IOException {
        ScheduledTask existing = requireTask(taskId);
        JsonNode body = readObject(exchange);
        rejectUnknownFields(body, UPDATE_FIELDS);

        Instant now = clock.get();
        Map<String, Object> updates = new LinkedHashMap<>();
        String contextMode = optionalText(body, "context_mode");
        if (contextMode != null) {
            validateContextMode(contextMode);
            updates.put("context_mode", contextMode);
        }
        if (body.hasNonNull("title")) {
            updates.put("title", requiredNonEmptyText(body, "title"));
        }
        if (body.hasNonNull("prompt")) {
            updates.put("prompt", requiredNonEmptyText(body, "prompt"));
        }
        // An explicit null is a real patch (the frozen schema allows null and null means the default
        // lead agent); only a missing field leaves the stored assistant untouched.
        if (body.has("assistant_id")) {
            updates.put("assistant_id", resolveAssistantId(optionalText(body, "assistant_id")));
        }
        String timezone = optionalText(body, "timezone");
        if (timezone != null) {
            ScheduleCalculator.validateTimezone(timezone);
            updates.put("timezone", timezone);
        }
        Map<String, Object> providedSpec = body.hasNonNull("schedule_spec")
                ? scheduleSpec(body) : null;
        if (providedSpec != null) {
            updates.put("schedule_spec", providedSpec);
        }

        // thread_id is not patchable: the frozen update schema excludes it, so a reuse_thread task
        // cannot be rebound here. The stored Thread only changes through context_mode below.
        String effectiveMode = contextMode != null ? contextMode : existing.contextMode();
        String effectiveThreadId = existing.threadId();
        if (ScheduledTask.REUSE_THREAD.equals(effectiveMode)) {
            if (effectiveThreadId == null || effectiveThreadId.isBlank()) {
                throw new HttpFailure(422, "reuse_thread requires thread_id");
            }
            requireThreadAccess(effectiveThreadId);
        } else {
            updates.put("thread_id", null);
        }

        if (providedSpec != null || timezone != null) {
            Map<String, Object> scheduleSpec = providedSpec != null
                    ? new LinkedHashMap<>(providedSpec)
                    : new LinkedHashMap<>(existing.scheduleSpec());
            String effectiveTimezone = timezone != null ? timezone : existing.timezone();
            Instant preservedIntervalNext = preservedIntervalNext(existing, scheduleSpec);
            Instant nextRunAt = normalizeAndComputeNextRun(existing.scheduleType(), scheduleSpec,
                    effectiveTimezone, configuration.minOnceDelaySeconds(), now,
                    preservedIntervalNext);
            updates.put("schedule_spec", scheduleSpec);
            updates.put("next_run_at", nextRunAt);
            if (nextRunAt != null && existing.status().terminal()) {
                updates.put("status", "enabled");
            }
        }

        // The active-occurrence check and the write are one store operation so an occurrence cannot
        // be admitted between them and then consume the patched prompt/agent/Thread.
        ScheduledTask updated = store.updateTaskIfMutable(taskId, owner(), updates, now)
                .orElseThrow(() -> new HttpFailure(404, "Scheduled task not found"));
        sendJson(exchange, 200, updated.wire());
    }

    private void pauseTask(HttpExchange exchange, String taskId) throws IOException {
        ScheduledTask task = requireTask(taskId);
        if (task.status() == ScheduledTaskStatus.RUNNING) {
            throw new HttpFailure(409,
                    "Scheduled task is currently running; retry after the active execution finishes");
        }
        if (!store.pauseTask(taskId, owner(), clock.get())) {
            throw new HttpFailure(404, "Scheduled task not found");
        }
        sendJson(exchange, 200, store.findTask(taskId, owner())
                .orElseThrow(() -> new HttpFailure(404, "Scheduled task not found")).wire());
    }

    private void resumeTask(HttpExchange exchange, String taskId) throws IOException {
        // Only a truly paused, non-terminal task may resume. A terminal once task keeps its past
        // next_run_at, so blindly re-enabling it would let listDueTasks admit it again and repeat
        // Tool side effects; such a task must be re-armed with a future schedule PATCH first.
        ScheduledTask updated = store.resumeTask(taskId, owner(), clock.get())
                .orElseThrow(() -> new HttpFailure(404, "Scheduled task not found"));
        sendJson(exchange, 200, updated.wire());
    }

    private void triggerTask(HttpExchange exchange, String taskId) throws IOException {
        requireTask(taskId);
        ScheduledTaskRun occurrence;
        try {
            occurrence = scheduler.triggerManually(taskId, owner());
        } catch (ScheduleValidationException missing) {
            throw new HttpFailure(404, "Scheduled task not found");
        }
        if (occurrence.runId() == null && occurrence.status() == ScheduledRunStatus.FAILED) {
            throw new HttpFailure(502, occurrence.error() == null
                    ? "Scheduled task trigger failed" : occurrence.error());
        }
        sendJson(exchange, 200, Map.of("id", taskId, "triggered", true));
    }

    private void deleteTask(HttpExchange exchange, String taskId) throws IOException {
        requireTask(taskId);
        if (!store.deleteTask(taskId, owner(), clock.get())) {
            throw new HttpFailure(404, "Scheduled task not found");
        }
        sendJson(exchange, 200, Map.of("id", taskId, "deleted", true));
    }

    private void listRuns(HttpExchange exchange, String taskId) throws IOException {
        requireTask(taskId);
        int limit = queryInt(exchange, "limit", DEFAULT_RUN_PAGE_SIZE, 1, MAXIMUM_RUN_PAGE_SIZE);
        int offset = queryInt(exchange, "offset", 0, 0, Integer.MAX_VALUE);
        ScheduledRunStatus status = null;
        Optional<String> rawStatus = queryValue(exchange, "status");
        if (rawStatus.isPresent()) {
            try {
                status = ScheduledRunStatus.fromWire(rawStatus.orElseThrow());
            } catch (ScheduleValidationException invalid) {
                throw new HttpFailure(422, "Unsupported run status: " + rawStatus.orElseThrow());
            }
        }
        List<ScheduledTaskRun> runs = store.listRuns(taskId, owner(), limit, offset, status);
        List<Map<String, Object>> wire = new ArrayList<>(runs.size());
        for (ScheduledTaskRun run : runs) {
            wire.add(run.wire());
        }
        sendJson(exchange, 200, wire);
    }

    // --------------------------------------------------------------- validation

    private static void validateContextMode(String contextMode) {
        if (!ScheduledTask.FRESH_THREAD_PER_RUN.equals(contextMode)
                && !ScheduledTask.REUSE_THREAD.equals(contextMode)) {
            throw new HttpFailure(422, "Unsupported context_mode");
        }
    }

    private void requireThreadAccess(String threadId) {
        if (!threadAccessible.test(threadId)) {
            throw new HttpFailure(404, "Thread not found");
        }
    }

    /**
     * Resolves the frozen {@code assistant_id}: absent means the default lead agent, custom names
     * are normalized like Run admission and must exist for the owner.
     */
    private String resolveAssistantId(String raw) {
        if (raw == null) {
            return DEFAULT_ASSISTANT_ID;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            throw new HttpFailure(422, "assistant_id must not be empty");
        }
        String normalized = value.toLowerCase(Locale.ROOT).replace('_', '-');
        if ("lead-agent".equals(normalized)) {
            return DEFAULT_ASSISTANT_ID;
        }
        if (!AGENT_NAME.matcher(normalized).matches() || !agentExists.test(normalized)) {
            throw new HttpFailure(422, "Unknown assistant_id '" + raw + "'");
        }
        return normalized;
    }

    /**
     * Applies the frozen creation/update schedule validation and returns the authoritative next
     * run instant. Mutates {@code scheduleSpec} to the normalized cron value, matching DeerFlow.
     */
    /**
     * Frozen update keeps an interval task's current {@code next_run_at} when its cadence is
     * unchanged, so editing unrelated metadata cannot silently shift the next occurrence.
     */
    private static Instant preservedIntervalNext(ScheduledTask existing,
            Map<String, Object> scheduleSpec) {
        if (!"interval".equals(existing.scheduleType()) || existing.nextRunAt() == null) {
            return null;
        }
        int previous;
        try {
            previous = ScheduleCalculator.parseIntervalSeconds(existing.scheduleSpec());
        } catch (ScheduleValidationException invalid) {
            return null;
        }
        int next = ScheduleCalculator.parseIntervalSeconds(scheduleSpec);
        return previous == next ? existing.nextRunAt() : null;
    }

    private static Instant normalizeAndComputeNextRun(String scheduleType,
            Map<String, Object> scheduleSpec, String timezone, int minimumSeconds, Instant now,
            Instant preservedIntervalNext) {
        ScheduleCalculator.validateTimezone(timezone);
        if ("cron".equals(scheduleType)) {
            Object raw = scheduleSpec.get("cron");
            if (!(raw instanceof String text)) {
                throw new ScheduleValidationException("cron schedule requires schedule_spec.cron");
            }
            scheduleSpec.put("cron", ScheduleCalculator.normalizeCronExpression(text));
        }
        if ("interval".equals(scheduleType)) {
            ScheduleCalculator.validateIntervalSeconds(scheduleSpec, minimumSeconds);
            if (preservedIntervalNext != null) {
                // Frozen update keeps the existing instant when every_seconds is unchanged.
                return preservedIntervalNext;
            }
        }
        Instant nextRunAt = ScheduleCalculator.nextRunAt(scheduleType, scheduleSpec, timezone, now);
        if ("once".equals(scheduleType)) {
            if (nextRunAt == null) {
                throw new ScheduleValidationException("once schedule must be in the future");
            }
            if (Duration.between(now, nextRunAt).getSeconds() < minimumSeconds) {
                throw new ScheduleValidationException(
                        "once schedule must be at least " + minimumSeconds + " seconds in the future");
            }
        }
        return nextRunAt;
    }

    private static String activeConflictDetail(String status) {
        String detail = "Scheduled task has an active " + status
                + " occurrence; retry after it finishes";
        if ("queued".equals(status)) {
            detail += " or cancel the queued occurrence by pausing the task";
        }
        return detail;
    }

    private ScheduledTask requireTask(String taskId) {
        return store.findTask(taskId, owner()).orElseThrow(
                () -> new HttpFailure(404, "Scheduled task not found"));
    }

    private static String requiredNonEmptyText(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isEmpty()) {
            throw new HttpFailure(422, field + " is required");
        }
        return value.asText();
    }

    private static String optionalText(JsonNode body, String field) {
        if (!body.hasNonNull(field)) {
            return null;
        }
        JsonNode value = body.get(field);
        if (!value.isTextual()) {
            throw new HttpFailure(422, field + " must be a string");
        }
        return value.asText();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> scheduleSpec(JsonNode body) {
        JsonNode value = body.get("schedule_spec");
        if (value == null || !value.isObject()) {
            throw new HttpFailure(422, "schedule_spec must be an object");
        }
        return new LinkedHashMap<>(json.convertValue(value, Map.class));
    }

    private static void rejectUnknownFields(JsonNode body, Set<String> allowed) {
        Iterator<String> names = body.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new HttpFailure(422, "Unsupported field: " + name);
            }
        }
    }

    private static List<Map<String, Object>> projections(List<ScheduledTask> tasks) {
        List<Map<String, Object>> wire = new ArrayList<>(tasks.size());
        for (ScheduledTask task : tasks) {
            wire.add(task.wire());
        }
        return wire;
    }

    // ---------------------------------------------------------------- transport

    private JsonNode readObject(HttpExchange exchange) throws IOException {
        long declared = parseContentLength(exchange);
        if (declared > MAX_JSON_BYTES) {
            throw new HttpFailure(413, "JSON body exceeds the size limit");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        for (int count; (count = exchange.getRequestBody().read(buffer)) >= 0;) {
            total += count;
            if (total > MAX_JSON_BYTES) {
                throw new HttpFailure(413, "JSON body exceeds the size limit");
            }
            output.write(buffer, 0, count);
        }
        try {
            JsonNode body = json.readTree(output.toByteArray());
            if (body == null || !body.isObject()) {
                throw new HttpFailure(422, "JSON body must be an object");
            }
            return body;
        } catch (JsonProcessingException malformed) {
            throw new HttpFailure(422, "Invalid JSON body");
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    private void methodNotAllowed(HttpExchange exchange, String allow) throws IOException {
        exchange.getResponseHeaders().set("Allow", allow);
        sendJson(exchange, 405, Map.of("detail", "Method Not Allowed"));
    }

    private static String owner() {
        return AccessOwner.current();
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int queryInt(HttpExchange exchange, String name, int fallback, int minimum,
            int maximum) {
        Optional<String> value = queryValue(exchange, name);
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.orElseThrow());
            if (parsed < minimum || parsed > maximum) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new HttpFailure(422, "Invalid pagination parameter");
        }
    }

    private static Optional<String> queryValue(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(name)) {
                return Optional.of(parts.length == 2 ? decode(parts[1]) : "");
            }
        }
        return Optional.empty();
    }

    private static long parseContentLength(HttpExchange exchange) {
        String value = exchange.getRequestHeaders().getFirst("Content-Length");
        if (value == null) {
            return -1;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new HttpFailure(400, "Invalid Content-Length");
        }
    }

    private static boolean isLoopbackPeer(HttpExchange exchange) {
        return exchange.getRemoteAddress() != null
                && exchange.getRemoteAddress().getAddress() != null
                && exchange.getRemoteAddress().getAddress().isLoopbackAddress();
    }

    private static final class HttpFailure extends RuntimeException {
        private final int status;

        private HttpFailure(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}

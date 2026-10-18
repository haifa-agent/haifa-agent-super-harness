package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.server.schedule.ScheduleStore;
import io.haifa.agent.superharness.server.schedule.ScheduledRunStatus;
import io.haifa.agent.superharness.server.schedule.ScheduledRunTrigger;
import io.haifa.agent.superharness.server.schedule.ScheduledTask;
import io.haifa.agent.superharness.server.schedule.ScheduledTaskRun;
import io.haifa.agent.superharness.server.schedule.ScheduledTaskStatus;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * W19-M3 frozen scheduled-task REST compatibility: route surface, response projection, validation,
 * conflict mapping and owner isolation against the real Gateway.
 */
class W19ScheduleRestApiHttpTest {
    private static final String PHRASE = "Sup3r-secret-pass";
    private static final Set<String> TASK_FIELDS = Set.of(
            "id", "thread_id", "context_mode", "assistant_id", "title", "prompt", "schedule_type",
            "schedule_spec", "timezone", "status", "next_run_at", "last_run_at", "last_run_id",
            "last_thread_id", "last_error", "run_count", "created_at", "updated_at");
    private static final Set<String> RUN_FIELDS = Set.of(
            "id", "task_id", "thread_id", "run_id", "scheduled_for", "trigger", "status", "error",
            "attempt_count", "started_at", "finished_at", "created_at");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private Path dataRoot;
    private Path evidence;
    private GatewayFixture.HttpEvidence http;
    private GatewayFixture fixture;

    @BeforeEach
    void prepare() throws Exception {
        dataRoot = GatewayFixture.dataDirectory();
        evidence = GatewayFixture.evidenceDirectory();
        http = new GatewayFixture.HttpEvidence(evidence, Duration.ofSeconds(30));
    }

    @AfterEach
    void close() throws Exception {
        if (fixture != null) fixture.close();
        if (http != null) http.close();
    }

    // W19-M3-01/W19-M3-08: list, thread list and run history shapes/paging/status filtering.
    @Test
    void listsTasksThreadScopeAndPaginatesRuns() throws Exception {
        start(Map.of());
        fixture.thread("thread-bound");

        Instant now = Instant.now();
        JsonNode once = createTask(createBody("once task", "once",
                object("run_at", now.plusSeconds(3600).toString()), "UTC"));
        JsonNode cron = createTask(createBody("cron task", "cron",
                object("cron", "0 9 * * *"), "UTC"));
        ObjectNode intervalBody = createBody("interval task", "interval",
                object("every_seconds", 600), "UTC");
        intervalBody.put("context_mode", "reuse_thread");
        intervalBody.put("thread_id", "thread-bound");
        JsonNode interval = createTask(intervalBody);

        JsonNode tasks = getJson("/api/scheduled-tasks", "list-tasks.json");
        assertThat(tasks.isArray()).isTrue();
        assertThat(tasks.size()).isEqualTo(3);
        for (JsonNode task : tasks) {
            assertTaskShape(task);
        }
        assertThat(ids(tasks)).containsExactlyInAnyOrder(
                once.path("id").asText(), cron.path("id").asText(), interval.path("id").asText());

        JsonNode threadTasks = getJson("/api/threads/thread-bound/scheduled-tasks",
                "thread-tasks.json");
        assertThat(threadTasks.size()).isEqualTo(1);
        assertThat(threadTasks.get(0).path("id").asText()).isEqualTo(interval.path("id").asText());
        assertTaskShape(threadTasks.get(0));

        ScheduleStore store = fixture.api.scheduler().store();
        String intervalId = interval.path("id").asText();
        seedRun(store, intervalId, "thread-bound", ScheduledRunStatus.SUCCESS, now.plusSeconds(10));
        seedRun(store, intervalId, "thread-bound", ScheduledRunStatus.FAILED, now.plusSeconds(20));

        JsonNode firstPage = getJson("/api/scheduled-tasks/" + intervalId + "/runs?limit=1&offset=0",
                "runs-page-0.json");
        assertThat(firstPage.size()).isEqualTo(1);
        assertRunShape(firstPage.get(0));
        assertThat(firstPage.get(0).path("status").asText()).isEqualTo("failed");

        JsonNode secondPage = getJson("/api/scheduled-tasks/" + intervalId + "/runs?limit=1&offset=1",
                "runs-page-1.json");
        assertThat(secondPage.size()).isEqualTo(1);
        assertThat(secondPage.get(0).path("status").asText()).isEqualTo("success");

        JsonNode failedOnly = getJson(
                "/api/scheduled-tasks/" + intervalId + "/runs?limit=10&offset=0&status=failed",
                "runs-failed.json");
        assertThat(failedOnly.size()).isEqualTo(1);
        assertThat(failedOnly.get(0).path("status").asText()).isEqualTo("failed");

        assertThat(request("GET", "/api/scheduled-tasks/" + intervalId + "/runs?status=bogus", null)
                .statusCode()).isEqualTo(422);
        assertThat(request("GET", "/api/scheduled-tasks/missing/runs", null).statusCode())
                .isEqualTo(404);
        assertThat(request("GET", "/api/threads/missing-thread/scheduled-tasks", null).statusCode())
                .isEqualTo(404);
    }

    // W19-M3-02: the three plan types succeed; unsupported type/spec/timezone/past time map to 422
    // and an inaccessible reuse Thread maps to 404.
    @Test
    void createValidatesThreePlanTypesAndRejectsInvalidInputs() throws Exception {
        start(Map.of());
        fixture.thread("thread-reuse");
        Instant now = Instant.now();

        JsonNode once = createTask(createBody("Morning briefing", "once",
                object("run_at", now.plusSeconds(3600).toString()), "UTC"));
        assertTaskShape(once);
        assertThat(once.path("status").asText()).isEqualTo("enabled");
        assertThat(once.path("assistant_id").asText()).isEqualTo("lead_agent");
        assertThat(Instant.parse(once.path("next_run_at").asText())).isAfter(now);

        JsonNode cron = createTask(createBody("Daily digest", "cron",
                object("cron", "  0   9   *  *  * "), "Asia/Shanghai"));
        assertTaskShape(cron);
        assertThat(cron.path("schedule_spec").path("cron").asText()).isEqualTo("0 9 * * *");
        assertThat(Instant.parse(cron.path("next_run_at").asText())).isAfter(now);

        ObjectNode intervalBody = createBody("Hourly check", "interval",
                object("every_seconds", 600), "UTC");
        intervalBody.put("context_mode", "reuse_thread");
        intervalBody.put("thread_id", "thread-reuse");
        JsonNode interval = createTask(intervalBody);
        assertTaskShape(interval);
        assertThat(interval.path("thread_id").asText()).isEqualTo("thread-reuse");
        Instant intervalNext = Instant.parse(interval.path("next_run_at").asText());
        assertThat(intervalNext).isBetween(now.plusSeconds(595), now.plusSeconds(605));

        assertRejected(createBody("bad type", "weekly", object("cron", "0 9 * * *"), "UTC"), 422);
        assertRejected(createBody("no cron", "cron", json.createObjectNode(), "UTC"), 422);
        assertRejected(createBody("bad cron", "cron", object("cron", "not a cron"), "UTC"), 422);
        assertRejected(createBody("bad zone", "cron", object("cron", "0 9 * * *"),
                "Mars/Olympus"), 422);
        assertRejected(createBody("past once", "once",
                object("run_at", now.minusSeconds(60).toString()), "UTC"), 422);
        assertRejected(createBody("imminent once", "once",
                object("run_at", Instant.now().toString()), "UTC"), 422);
        assertRejected(createBody("zero interval", "interval",
                object("every_seconds", 0), "UTC"), 422);
        assertRejected(createBody("huge interval", "interval",
                object("every_seconds", 31L * 24 * 60 * 60), "UTC"), 422);

        ObjectNode reuseMissingThread = createBody("reuse", "interval",
                object("every_seconds", 600), "UTC");
        reuseMissingThread.put("context_mode", "reuse_thread");
        assertRejected(reuseMissingThread, 422);
        ObjectNode reuseUnknownThread = createBody("reuse", "interval",
                object("every_seconds", 600), "UTC");
        reuseUnknownThread.put("context_mode", "reuse_thread");
        reuseUnknownThread.put("thread_id", "no-such-thread");
        assertRejected(reuseUnknownThread, 404);

        ObjectNode badMode = createBody("bad mode", "interval", object("every_seconds", 600), "UTC");
        badMode.put("context_mode", "shared_thread");
        assertRejected(badMode, 422);

        ObjectNode badAssistant = createBody("bad assistant", "interval",
                object("every_seconds", 600), "UTC");
        badAssistant.put("assistant_id", "missing-agent");
        assertRejected(badAssistant, 422);

        ObjectNode unknownField = createBody("unknown", "interval",
                object("every_seconds", 600), "UTC");
        unknownField.put("overlap_policy", "enqueue");
        assertRejected(unknownField, 422);
    }

    // W19-M3-03: legal updates apply; an active occurrence is a 409; once replanning is validated.
    @Test
    void updatesApplyAndActiveOccurrenceConflicts() throws Exception {
        start(Map.of());
        fixture.thread("thread-update");
        Instant now = Instant.now();
        JsonNode once = createTask(createBody("once edit", "once",
                object("run_at", now.plusSeconds(3600).toString()), "UTC"));
        String onceId = once.path("id").asText();
        String originalNext = once.path("next_run_at").asText();

        ObjectNode promptPatch = json.createObjectNode().put("prompt", "new prompt");
        JsonNode patched = patchTask(onceId, promptPatch);
        assertTaskShape(patched);
        assertThat(patched.path("prompt").asText()).isEqualTo("new prompt");
        assertThat(patched.path("next_run_at").asText()).isEqualTo(originalNext);

        ObjectNode futureOnce = json.createObjectNode();
        futureOnce.putObject("schedule_spec").put("run_at", now.plusSeconds(7200).toString());
        JsonNode replanned = patchTask(onceId, futureOnce);
        assertThat(Instant.parse(replanned.path("next_run_at").asText()))
                .isAfter(Instant.parse(originalNext));

        ObjectNode pastOnce = json.createObjectNode();
        pastOnce.putObject("schedule_spec").put("run_at", now.minusSeconds(60).toString());
        assertThat(request("PATCH", "/api/scheduled-tasks/" + onceId, pastOnce.toString())
                .statusCode()).isEqualTo(422);

        ObjectNode blankTitle = json.createObjectNode().put("title", "");
        assertThat(request("PATCH", "/api/scheduled-tasks/" + onceId, blankTitle.toString())
                .statusCode()).isEqualTo(422);
        ObjectNode unknown = json.createObjectNode().put("schedule_type", "cron");
        assertThat(request("PATCH", "/api/scheduled-tasks/" + onceId, unknown.toString())
                .statusCode()).isEqualTo(422);
        // W19-M3-10: the frozen update schema excludes thread_id, so a raw PATCH carrying it is a
        // 422 and never rebinds a reuse-thread task.
        ObjectNode rebind = json.createObjectNode().put("thread_id", "thread-update");
        assertThat(request("PATCH", "/api/scheduled-tasks/" + onceId, rebind.toString())
                .statusCode()).isEqualTo(422);

        ScheduleStore store = fixture.api.scheduler().store();
        seedRun(store, onceId, "thread-update", ScheduledRunStatus.QUEUED, now.plusSeconds(30));
        assertThat(request("PATCH", "/api/scheduled-tasks/" + onceId,
                json.createObjectNode().put("prompt", "blocked").toString()).statusCode())
                .isEqualTo(409);
        // W19-M3-10: the rejected patch is atomic, so the stored prompt is untouched.
        assertThat(store.findTask(onceId, "default").orElseThrow().prompt()).isEqualTo("new prompt");
        finishOpenRuns(store, onceId, now);
        assertThat(patchTask(onceId, json.createObjectNode().put("prompt", "allowed"))
                .path("prompt").asText()).isEqualTo("allowed");

        ObjectNode intervalBody = createBody("interval edit", "interval",
                object("every_seconds", 600), "UTC");
        intervalBody.put("context_mode", "reuse_thread");
        intervalBody.put("thread_id", "thread-update");
        JsonNode interval = createTask(intervalBody);
        String intervalId = interval.path("id").asText();
        String intervalNext = interval.path("next_run_at").asText();
        ObjectNode timezoneOnly = json.createObjectNode().put("timezone", "UTC");
        JsonNode intervalUpdated = patchTask(intervalId, timezoneOnly);
        assertThat(intervalUpdated.path("next_run_at").asText()).isEqualTo(intervalNext);

        ObjectNode fresh = json.createObjectNode().put("context_mode", "fresh_thread_per_run");
        JsonNode unbound = patchTask(intervalId, fresh);
        assertThat(unbound.path("thread_id").isNull()).isTrue();

        JsonNode cron = createTask(createBody("cron edit", "cron",
                object("cron", "0 9 * * *"), "UTC"));
        String cronId = cron.path("id").asText();
        ObjectNode zonePatch = json.createObjectNode().put("timezone", "America/New_York");
        JsonNode cronUpdated = patchTask(cronId, zonePatch);
        assertThat(Instant.parse(cronUpdated.path("next_run_at").asText())).isNotNull();

        // A terminal once task replanned into the future is re-armed (claim only admits enabled).
        String terminalId = createTask(createBody("terminal once", "once",
                object("run_at", now.plusSeconds(3600).toString()), "UTC")).path("id").asText();
        store.updateTask(terminalId, "default", Map.of("status", "completed"), Instant.now());
        ObjectNode rearm = json.createObjectNode();
        rearm.putObject("schedule_spec").put("run_at", now.plusSeconds(10800).toString());
        JsonNode rearmed = patchTask(terminalId, rearm);
        assertThat(rearmed.path("status").asText()).isEqualTo("enabled");
    }

    // W19-M3-09: an explicit null assistant_id is a real patch that resets to the default lead
    // agent, distinct from an omitted field which leaves the stored assistant untouched.
    @Test
    void explicitNullAssistantIdResetsToDefault() throws Exception {
        start(Map.of());
        fixture.seed("custom-agent", "custom soul");
        ObjectNode body = createBody("assistant reset", "interval",
                object("every_seconds", 600), "UTC");
        body.put("assistant_id", "custom-agent");
        JsonNode created = createTask(body);
        assertThat(created.path("assistant_id").asText()).isEqualTo("custom-agent");

        JsonNode reset = patchTask(created.path("id").asText(),
                json.createObjectNode().putNull("assistant_id"));
        assertThat(reset.path("assistant_id").asText()).isEqualTo("lead_agent");

        // An omitted assistant_id leaves the stored value (custom-agent) untouched.
        JsonNode reopened = patchTask(created.path("id").asText(),
                json.createObjectNode().put("assistant_id", "custom-agent"));
        assertThat(reopened.path("assistant_id").asText()).isEqualTo("custom-agent");
        JsonNode untouched = patchTask(created.path("id").asText(),
                json.createObjectNode().put("prompt", "still here"));
        assertThat(untouched.path("assistant_id").asText()).isEqualTo("custom-agent");
    }

    // W19-M3-09: a terminal once task cannot resume (its past next_run_at would re-admit it), and it
    // only returns to enabled through a future schedule PATCH.
    @Test
    void terminalOnceResumeIsRejectedUntilReplanned() throws Exception {
        start(Map.of());
        Instant now = Instant.now();
        String taskId = createTask(createBody("terminal resume", "once",
                object("run_at", now.plusSeconds(3600).toString()), "UTC")).path("id").asText();
        ScheduleStore store = fixture.api.scheduler().store();
        store.updateTask(taskId, "default", Map.of("status", "completed"), Instant.now());

        assertThat(request("POST", "/api/scheduled-tasks/" + taskId + "/resume", null).statusCode())
                .isEqualTo(422);
        assertThat(store.findTask(taskId, "default").orElseThrow().status())
                .isEqualTo(ScheduledTaskStatus.COMPLETED);

        ObjectNode rearm = json.createObjectNode();
        rearm.putObject("schedule_spec").put("run_at", now.plusSeconds(7200).toString());
        JsonNode rearmed = patchTask(taskId, rearm);
        assertThat(rearmed.path("status").asText()).isEqualTo("enabled");
        assertThat(Instant.parse(rearmed.path("next_run_at").asText()))
                .isAfter(Instant.parse(rearmed.path("created_at").asText()));
    }

    // W19-M3-04/W19-M3-06: queued cancellation on pause/delete, active conflicts, resume and delete
    // removal from every read.
    @Test
    void pauseResumeDeleteCancelQueuedAndConflictWithExecutingOccurrences() throws Exception {
        start(Map.of());
        Instant now = Instant.now();
        JsonNode task = createTask(createBody("pause me", "interval",
                object("every_seconds", 600), "UTC"));
        String taskId = task.path("id").asText();
        ScheduleStore store = fixture.api.scheduler().store();

        ScheduledTaskRun queued = seedRun(store, taskId, "thread-pause", ScheduledRunStatus.QUEUED,
                now.plusSeconds(1));
        JsonNode paused = postTask(taskId, "pause");
        assertTaskShape(paused);
        assertThat(paused.path("status").asText()).isEqualTo("paused");
        assertThat(store.findRun(queued.id()).orElseThrow().status())
                .isEqualTo(ScheduledRunStatus.SKIPPED);

        JsonNode resumed = postTask(taskId, "resume");
        assertThat(resumed.path("status").asText()).isEqualTo("enabled");

        seedRun(store, taskId, "thread-pause", ScheduledRunStatus.QUEUED, now.plusSeconds(2));
        assertThat(request("POST", "/api/scheduled-tasks/" + taskId + "/resume", null).statusCode())
                .isEqualTo(409);
        assertThat(request("POST", "/api/scheduled-tasks/" + taskId + "/pause", null).statusCode())
                .isEqualTo(200);

        ScheduledTaskRun running = seedRun(store, taskId, "thread-pause",
                ScheduledRunStatus.RUNNING, now.plusSeconds(3));
        assertThat(request("POST", "/api/scheduled-tasks/" + taskId + "/pause", null).statusCode())
                .isEqualTo(409);
        assertThat(request("POST", "/api/scheduled-tasks/" + taskId + "/resume", null).statusCode())
                .isEqualTo(409);
        finishOpenRuns(store, taskId, now);

        String queuedDeleteId = createTask(createBody("delete queued", "interval",
                object("every_seconds", 600), "UTC")).path("id").asText();
        seedRun(store, queuedDeleteId, "thread-delete", ScheduledRunStatus.QUEUED, now.plusSeconds(4));
        JsonNode deleted = json.readTree(request("DELETE",
                "/api/scheduled-tasks/" + queuedDeleteId, null).body());
        assertThat(deleted.path("deleted").asBoolean()).isTrue();
        assertThat(deleted.path("id").asText()).isEqualTo(queuedDeleteId);
        assertThat(store.findTask(queuedDeleteId, "default")).isEmpty();
        assertThat(request("GET", "/api/scheduled-tasks/" + queuedDeleteId + "/runs", null)
                .statusCode()).isEqualTo(404);
        assertThat(ids(getJson("/api/scheduled-tasks", "after-delete.json")))
                .doesNotContain(queuedDeleteId);

        String activeDeleteId = createTask(createBody("delete active", "interval",
                object("every_seconds", 600), "UTC")).path("id").asText();
        seedRun(store, activeDeleteId, "thread-delete", ScheduledRunStatus.RUNNING, now.plusSeconds(5));
        assertThat(request("DELETE", "/api/scheduled-tasks/" + activeDeleteId, null).statusCode())
                .isEqualTo(409);
        finishOpenRuns(store, activeDeleteId, now);
        assertThat(request("DELETE", "/api/scheduled-tasks/" + activeDeleteId, null).statusCode())
                .isEqualTo(200);
        assertThat(request("DELETE", "/api/scheduled-tasks/" + activeDeleteId, null).statusCode())
                .isEqualTo(404);
        assertThat(running.taskId()).isEqualTo(taskId);
    }

    // W19-M3-07: 401 without a session, 404 for cross-owner reads/writes, and per-owner lists.
    @Test
    void enforcesAuthenticationAndOwnerIsolation() throws Exception {
        start(Map.of("HAIFA_SUPER_HARNESS_LOCAL_AUTH", "1"));
        URI base = fixture.base;
        Session alice = Session.from(send(base, "POST", "/api/v1/auth/initialize",
                "{\"email\":\"alice@example.com\",\"password\":\"" + PHRASE + "\"}", Map.of(
                        "Content-Type", "application/json")));
        Session bob = Session.from(send(base, "POST", "/api/v1/auth/register",
                "{\"email\":\"bob@example.com\",\"password\":\"" + PHRASE + "\"}", Map.of(
                        "Content-Type", "application/json")));

        assertThat(send(base, "GET", "/api/scheduled-tasks", null, Map.of()).statusCode())
                .isEqualTo(401);
        assertThat(send(base, "GET", "/api/scheduled-tasks", null, bob.headers()).statusCode())
                .isEqualTo(200);

        assertThat(send(base, "POST", "/api/threads", "{\"thread_id\":\"thread-alice\"}",
                alice.jsonHeaders()).statusCode()).isEqualTo(200);
        ObjectNode body = createBody("alice task", "interval", object("every_seconds", 600), "UTC");
        body.put("context_mode", "reuse_thread");
        body.put("thread_id", "thread-alice");
        HttpResponse<String> created = send(base, "POST", "/api/scheduled-tasks",
                body.toString(), alice.jsonHeaders());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        String taskId = json.readTree(created.body()).path("id").asText();

        JsonNode aliceTasks = json.readTree(send(base, "GET", "/api/scheduled-tasks", null,
                alice.headers()).body());
        assertThat(ids(aliceTasks)).containsExactly(taskId);
        JsonNode bobTasks = json.readTree(send(base, "GET", "/api/scheduled-tasks", null,
                bob.headers()).body());
        assertThat(bobTasks.size()).isZero();

        assertThat(send(base, "GET", "/api/scheduled-tasks/" + taskId + "/runs", null,
                bob.headers()).statusCode()).isEqualTo(404);
        assertThat(send(base, "PATCH", "/api/scheduled-tasks/" + taskId,
                json.createObjectNode().put("prompt", "hijack").toString(), bob.jsonHeaders())
                .statusCode()).isEqualTo(404);
        assertThat(send(base, "DELETE", "/api/scheduled-tasks/" + taskId, null, bob.headers())
                .statusCode()).isEqualTo(404);
        assertThat(send(base, "GET", "/api/threads/thread-alice/scheduled-tasks", null,
                bob.headers()).statusCode()).isEqualTo(404);
        assertThat(send(base, "GET", "/api/scheduled-tasks/" + taskId + "/runs", null,
                alice.headers()).statusCode()).isEqualTo(200);
    }

    // W19-M3-08/W00-SCH-04: the live REST responses satisfy the merged W00 schedules contract.
    @Test
    void responsesSatisfyW00ScheduledContractSchemas() throws Exception {
        start(Map.of());
        JsonNode taskSchema = contractSchema("scheduled-task.schema.json");
        JsonNode runSchema = contractSchema("scheduled-task-run.schema.json");
        Instant now = Instant.now();
        JsonNode task = createTask(createBody("contract once", "once",
                object("run_at", now.plusSeconds(3600).toString()), "UTC"));
        assertThat(names(task)).isEqualTo(required(taskSchema));
        assertThat(task.path("schedule_type").asText())
                .isIn(enumValues(taskSchema, "schedule_type"));
        assertThat(task.path("status").asText()).isIn(enumValues(taskSchema, "status"));
        assertThat(task.path("context_mode").asText())
                .isIn(enumValues(taskSchema, "context_mode"));

        ScheduleStore store = fixture.api.scheduler().store();
        seedRun(store, task.path("id").asText(), "thread-contract",
                ScheduledRunStatus.SUCCESS, now.plusSeconds(10));
        JsonNode runs = getJson("/api/scheduled-tasks/" + task.path("id").asText() + "/runs",
                "contract-runs.json");
        assertThat(runs.size()).isEqualTo(1);
        JsonNode run = runs.get(0);
        assertThat(names(run)).isEqualTo(required(runSchema));
        assertThat(run.path("trigger").asText()).isIn(enumValues(runSchema, "trigger"));
        assertThat(run.path("status").asText()).isIn(enumValues(runSchema, "status"));
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode contractSchema(String name) throws Exception {
        return json.readTree(repositoryRoot()
                .resolve("contracts/upstream/deerflow/schemas").resolve(name).toFile());
    }

    private static Set<String> required(JsonNode schema) {
        Set<String> fields = new LinkedHashSet<>();
        schema.path("required").forEach(field -> fields.add(field.asText()));
        return fields;
    }

    private static List<String> enumValues(JsonNode schema, String property) {
        List<String> values = new ArrayList<>();
        schema.path("properties").path(property).path("enum")
                .forEach(value -> values.add(value.asText()));
        return values;
    }

    static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isRegularFile(current.resolve("contracts/upstream/deerflow/schemas/scheduled-task.schema.json"))) {
            return current;
        }
        if (current.getFileName() != null
                && current.getFileName().toString().equals("super-harness-server")
                && current.getParent() != null) {
            return current.getParent();
        }
        throw new IllegalStateException("repository root is unavailable from " + current);
    }

    private void start(Map<String, String> extra) throws Exception {
        Map<String, String> environment = new LinkedHashMap<>(extra);
        environment.put("HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W19_REST_TEST_KEY");
        environment.put("W19_REST_TEST_KEY",
                java.util.Base64.getEncoder().encodeToString(new byte[32]));
        environment.put("HAIFA_SUPER_HARNESS_SCHEDULER_MIN_ONCE_DELAY_SECONDS", "1");
        fixture = GatewayFixture.production(dataRoot, 0, environment, evidence);
    }

    private ObjectNode createBody(String title, String type, ObjectNode scheduleSpec,
            String timezone) {
        ObjectNode body = json.createObjectNode();
        body.put("context_mode", "fresh_thread_per_run");
        body.putNull("thread_id");
        body.put("title", title);
        body.put("prompt", "scheduled prompt");
        body.put("schedule_type", type);
        body.set("schedule_spec", scheduleSpec);
        body.put("timezone", timezone);
        return body;
    }

    private ObjectNode object(String key, Object value) {
        ObjectNode node = json.createObjectNode();
        if (value instanceof String text) {
            node.put(key, text);
        } else if (value instanceof Integer number) {
            node.put(key, number);
        } else if (value instanceof Long number) {
            node.put(key, number);
        } else {
            throw new IllegalArgumentException("unsupported fixture value");
        }
        return node;
    }

    private JsonNode createTask(ObjectNode body) throws Exception {
        HttpResponse<String> response = request("POST", "/api/scheduled-tasks", body.toString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private void assertRejected(ObjectNode body, int status) throws Exception {
        assertThat(request("POST", "/api/scheduled-tasks", body.toString()).statusCode())
                .isEqualTo(status);
    }

    private JsonNode patchTask(String taskId, ObjectNode body) throws Exception {
        HttpResponse<String> response = request("PATCH", "/api/scheduled-tasks/" + taskId,
                body.toString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private JsonNode postTask(String taskId, String action) throws Exception {
        HttpResponse<String> response = request("POST",
                "/api/scheduled-tasks/" + taskId + "/" + action, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        return http.request(fixture.base, method, path, body, "scheduled-" + method + ".json");
    }

    private JsonNode getJson(String path, String evidenceName) throws Exception {
        return http.get(fixture.base, path, evidenceName);
    }

    private static List<String> ids(JsonNode tasks) {
        List<String> ids = new ArrayList<>();
        tasks.forEach(task -> ids.add(task.path("id").asText()));
        return ids;
    }

    private void assertTaskShape(JsonNode task) {
        assertThat(names(task)).isEqualTo(TASK_FIELDS);
        assertThat(task.path("id").asText()).isNotBlank();
        assertThat(task.path("context_mode").asText())
                .isIn("fresh_thread_per_run", "reuse_thread");
        assertThat(task.path("schedule_type").asText()).isIn("once", "cron", "interval");
        assertThat(task.path("status").asText())
                .isIn("enabled", "paused", "running", "completed", "failed", "cancelled");
        assertThat(task.path("schedule_spec").isObject()).isTrue();
        assertThat(task.path("run_count").isIntegralNumber()).isTrue();
        assertNullableText(task, "thread_id");
        assertNullableText(task, "assistant_id");
        assertNullableText(task, "next_run_at");
        assertNullableText(task, "last_run_at");
        assertNullableText(task, "last_run_id");
        assertNullableText(task, "last_thread_id");
        assertNullableText(task, "last_error");
        assertInstant(task, "created_at", false);
        assertInstant(task, "updated_at", false);
        assertThat(task.has("user_id")).isFalse();
        assertThat(task.has("last_occurrence_seq")).isFalse();
    }

    private void assertRunShape(JsonNode run) {
        assertThat(names(run)).isEqualTo(RUN_FIELDS);
        assertThat(run.path("id").asText()).isNotBlank();
        assertThat(run.path("task_id").asText()).isNotBlank();
        assertThat(run.path("thread_id").asText()).isNotBlank();
        assertThat(run.path("trigger").asText()).isIn("scheduled", "manual");
        assertThat(run.path("status").asText()).isIn(
                "queued", "launching", "running", "success", "failed", "skipped", "interrupted");
        assertThat(run.path("attempt_count").isIntegralNumber()).isTrue();
        assertNullableText(run, "run_id");
        assertNullableText(run, "error");
        assertNullableText(run, "started_at");
        assertNullableText(run, "finished_at");
        assertInstant(run, "scheduled_for", false);
        assertInstant(run, "created_at", false);
        assertThat(run.has("occurrence_seq")).isFalse();
        assertThat(run.has("launch_accounted")).isFalse();
    }

    private static void assertNullableText(JsonNode node, String field) {
        assertThat(node.has(field)).as(field).isTrue();
        assertThat(node.get(field).isNull() || node.get(field).isTextual()).as(field).isTrue();
    }

    private static void assertInstant(JsonNode node, String field, boolean nullable) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            assertThat(nullable).as(field).isTrue();
            return;
        }
        assertThat(Instant.parse(value.asText())).as(field).isNotNull();
    }

    private static Set<String> names(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static ScheduledTaskRun seedRun(ScheduleStore store, String taskId, String threadId,
            ScheduledRunStatus status, Instant scheduledFor) {
        // Use the distinct scheduled_for instant as the creation clock so paging order is stable.
        Instant now = scheduledFor;
        ScheduledTaskRun created = store.createRun(new ScheduleStore.NewRun(null, taskId,
                "default", threadId, scheduledFor, ScheduledRunTrigger.MANUAL,
                ScheduledRunStatus.QUEUED), now);
        if (status != ScheduledRunStatus.QUEUED) {
            boolean terminal = status.terminal();
            store.updateRunStatus(created.id(), status, null, null, now,
                    terminal ? now : null, false);
        }
        return store.findRun(created.id()).orElseThrow();
    }

    private static void finishOpenRuns(ScheduleStore store, String taskId, Instant now) {
        ScheduledTaskRun active = store.findActiveRun(taskId).orElse(null);
        if (active != null) {
            store.updateRunStatus(active.id(), ScheduledRunStatus.INTERRUPTED, null,
                    "test cleanup", active.startedAt(), now, false);
        }
    }

    private HttpResponse<String> send(URI base, String method, String path, String body,
            Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(30));
        headers.forEach(builder::header);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static final class Session {
        private final String accessToken;
        private final String csrfToken;

        private Session(String accessToken, String csrfToken) {
            this.accessToken = accessToken;
            this.csrfToken = csrfToken;
        }

        static Session from(HttpResponse<String> response) throws Exception {
            String access = value(cookie(response, "access_token"));
            String csrf = value(cookie(response, "csrf_token"));
            return new Session(access, csrf);
        }

        Map<String, String> headers() {
            return Map.of("Cookie", cookieHeader(), "X-CSRF-Token", csrfToken);
        }

        Map<String, String> jsonHeaders() {
            return Map.of("Content-Type", "application/json", "Cookie", cookieHeader(),
                    "X-CSRF-Token", csrfToken);
        }

        String cookieHeader() {
            return "access_token=" + accessToken + "; csrf_token=" + csrfToken;
        }

        private static String cookie(HttpResponse<?> response, String name) {
            return response.headers().allValues("set-cookie").stream()
                    .filter(value -> value.startsWith(name + "="))
                    .findFirst().orElse("");
        }

        private static String value(String setCookie) {
            int start = setCookie.indexOf('=');
            int end = setCookie.indexOf(';');
            return setCookie.substring(start + 1, end);
        }
    }
}

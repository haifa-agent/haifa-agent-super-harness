package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.server.schedule.ScheduleStore;
import io.haifa.agent.superharness.server.schedule.ScheduledRunStatus;
import io.haifa.agent.superharness.server.schedule.ScheduledTaskRun;
import io.haifa.agent.superharness.server.schedule.ScheduledTaskStatus;
import io.haifa.agent.superharness.server.testsupport.ControlledProvider;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * W19-M3-05: manual trigger really dispatches one Run through the product launch seam, and
 * conflict/failure outcomes map to the frozen 409/502 statuses.
 */
class W19ScheduleTriggerHttpTest {
    private final ObjectMapper json = new ObjectMapper();
    private Path dataRoot;
    private Path evidence;
    private GatewayFixture.HttpEvidence http;
    private GatewayFixture fixture;
    private ControlledProvider provider;

    @BeforeEach
    void prepare() throws Exception {
        dataRoot = GatewayFixture.dataDirectory();
        evidence = GatewayFixture.evidenceDirectory();
        http = new GatewayFixture.HttpEvidence(evidence, Duration.ofSeconds(30));
    }

    @AfterEach
    void close() throws Exception {
        if (fixture != null) fixture.close();
        if (provider != null) provider.close();
        if (http != null) http.close();
    }

    @Test
    void manualTriggerDispatchesRealRunAndProjectsManualHistory() throws Exception {
        start();
        provider = new ControlledProvider(evidence, true, (request, call) ->
                ControlledProvider.stop("sched-rest-" + call, "demo-provider-id", "done"));
        configureModel();
        JsonNode task = createTask("trigger cron", "cron");
        String taskId = task.path("id").asText();

        JsonNode triggered = trigger(taskId);
        assertThat(triggered.path("id").asText()).isEqualTo(taskId);
        assertThat(triggered.path("triggered").asBoolean()).isTrue();

        ScheduledTaskRun occurrence = awaitTerminal(taskId);
        assertThat(occurrence.status()).isEqualTo(ScheduledRunStatus.SUCCESS);
        assertThat(occurrence.trigger()).isEqualTo(
                io.haifa.agent.superharness.server.schedule.ScheduledRunTrigger.MANUAL);
        assertThat(occurrence.runId()).isNotBlank();
        assertThat(occurrence.startedAt()).isNotNull();
        assertThat(occurrence.finishedAt()).isNotNull();
        assertThat(provider.calls).hasValue(1);

        JsonNode runs = http.get(fixture.base,
                "/api/scheduled-tasks/" + taskId + "/runs", "trigger-runs.json");
        assertThat(runs.size()).isEqualTo(1);
        assertThat(runs.get(0).path("trigger").asText()).isEqualTo("manual");
        assertThat(runs.get(0).path("status").asText()).isEqualTo("success");
        assertThat(runs.get(0).path("run_id").asText()).isEqualTo(occurrence.runId());
    }

    @Test
    void pausedTaskStillAcceptsManualTriggerAndKeepsPausedStatus() throws Exception {
        start();
        provider = new ControlledProvider(evidence, true, (request, call) ->
                ControlledProvider.stop("sched-paused-" + call, "demo-provider-id", "done"));
        configureModel();
        String taskId = createTask("paused trigger", "interval").path("id").asText();

        HttpResponse<String> paused = http.request(fixture.base, "POST",
                "/api/scheduled-tasks/" + taskId + "/pause", null, "pause.json");
        assertThat(paused.statusCode()).as(paused.body()).isEqualTo(200);
        assertThat(json.readTree(paused.body()).path("status").asText()).isEqualTo("paused");

        JsonNode triggered = trigger(taskId);
        assertThat(triggered.path("triggered").asBoolean()).isTrue();
        ScheduledTaskRun occurrence = awaitTerminal(taskId);
        assertThat(occurrence.status()).isEqualTo(ScheduledRunStatus.SUCCESS);
        assertThat(occurrence.trigger()).isEqualTo(
                io.haifa.agent.superharness.server.schedule.ScheduledRunTrigger.MANUAL);
        assertThat(fixture.api.scheduler().store().findTask(taskId, "default")
                .orElseThrow().status()).isEqualTo(ScheduledTaskStatus.PAUSED);
    }

    @Test
    void activeOccurrenceAndUnknownTaskMapTo409And404() throws Exception {
        start();
        String taskId = createTask("conflicting trigger", "interval").path("id").asText();
        ScheduleStore store = fixture.api.scheduler().store();
        Instant now = Instant.now();
        store.createRun(new ScheduleStore.NewRun(null, taskId, "default", "thread-trigger",
                now, io.haifa.agent.superharness.server.schedule.ScheduledRunTrigger.MANUAL,
                ScheduledRunStatus.QUEUED), now);
        assertThat(triggerStatus(taskId)).isEqualTo(409);
        assertThat(triggerStatus("missing-task")).isEqualTo(404);
    }

    @Test
    void failedLaunchMapsTo502() throws Exception {
        start();
        String taskId = createTask("no model", "interval").path("id").asText();
        assertThat(triggerStatus(taskId)).isEqualTo(502);
    }

    // ------------------------------------------------------------------ helpers

    private void start() throws Exception {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W19_TRIGGER_TEST_KEY");
        environment.put("W19_TRIGGER_TEST_KEY",
                java.util.Base64.getEncoder().encodeToString(new byte[32]));
        environment.put("HAIFA_SUPER_HARNESS_SCHEDULER_MIN_ONCE_DELAY_SECONDS", "1");
        fixture = GatewayFixture.production(dataRoot, 0, environment, evidence);
    }

    private void configureModel() throws Exception {
        String model = """
                {"config":{"name":"demo-model","display_name":"Demo Model",
                "provider":"openai-compatible","model":"demo-provider-id",
                "base_url":"http://127.0.0.1:%d/v1","enabled":true,"supports_vision":false,
                "context_window":32768,"max_tokens":1024,"api_key":"controlled-key"},
                "expected_revision":null}
                """.formatted(provider.port());
        assertThat(http.request(fixture.base, "PUT", "/api/managed-models", model,
                "model.json").statusCode()).isEqualTo(200);
    }

    private JsonNode createTask(String title, String type) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("context_mode", "fresh_thread_per_run");
        body.putNull("thread_id");
        body.put("title", title);
        body.put("prompt", "say hi");
        body.put("schedule_type", type);
        if ("once".equals(type)) {
            body.putObject("schedule_spec").put("run_at",
                    Instant.now().plusSeconds(3600).toString());
        } else if ("cron".equals(type)) {
            body.putObject("schedule_spec").put("cron", "0 9 * * *");
        } else {
            body.putObject("schedule_spec").put("every_seconds", 600);
        }
        body.put("timezone", "UTC");
        HttpResponse<String> response = http.request(fixture.base, "POST", "/api/scheduled-tasks",
                body.toString(), "create.json");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private JsonNode trigger(String taskId) throws Exception {
        HttpResponse<String> response = http.request(fixture.base, "POST",
                "/api/scheduled-tasks/" + taskId + "/trigger", null, "trigger.json");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private int triggerStatus(String taskId) throws Exception {
        return http.request(fixture.base, "POST",
                "/api/scheduled-tasks/" + taskId + "/trigger", null, "trigger-status.json")
                .statusCode();
    }

    private ScheduledTaskRun awaitTerminal(String taskId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        ScheduledTaskRun last = null;
        while (System.nanoTime() < deadline) {
            var runs = fixture.api.scheduler().store().listRuns(taskId, "default", 10, 0, null);
            if (!runs.isEmpty()) {
                last = runs.getFirst();
                if (last.status().terminal()) {
                    return last;
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("manual occurrence did not reach terminal within 30s: " + last);
    }
}

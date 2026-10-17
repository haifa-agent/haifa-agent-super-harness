package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.superharness.server.schedule.ScheduleStore;
import io.haifa.agent.superharness.server.schedule.ScheduledRunStatus;
import io.haifa.agent.superharness.server.schedule.ScheduledTask;
import io.haifa.agent.superharness.server.schedule.ScheduledTaskRun;
import io.haifa.agent.superharness.server.testsupport.ControlledProvider;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Real HTTP/SDK chain for W19-M2: the scheduler dispatches a due task into a genuine non-interactive
 * Run through the product Gateway and a deterministic loopback Provider, and a bounded follower with
 * no SSE client records the occurrence terminal state.
 */
class W19BackgroundDispatchHttpTest {
    Path dataRoot;
    Path evidence;
    GatewayFixture.HttpEvidence http;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void prepare() throws Exception {
        dataRoot = GatewayFixture.dataDirectory();
        evidence = GatewayFixture.evidenceDirectory();
        http = new GatewayFixture.HttpEvidence(evidence, Duration.ofSeconds(30));
    }

    @org.junit.jupiter.api.AfterEach
    void close() throws InterruptedException {
        if (http != null) http.close();
    }

    // W19-M2-01/02/11: a due once task starts one real non-interactive Run with no SSE client; the
    // bounded follower observes the terminal state and the occurrence/Run association is verifiable.
    @Test
    void dueOnceTaskDispatchesRealNonInteractiveRunAndFollowerRecordsSuccess() throws Exception {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        Map<String, String> environment = schedulerEnvironment(key, "3600");
        try (var provider = new ControlledProvider(evidence, true, (request, call) ->
                ControlledProvider.stop("sched-provider-" + call, "demo-provider-id", "scheduled done"))) {
            try (var fixture = GatewayFixture.production(dataRoot, 0, environment, evidence)) {
                configureModel(fixture, provider.port());
                ScheduleStore store = fixture.api.scheduler().store();
                Instant now = Instant.now();
                ScheduledTask task = store.createTask(new ScheduleStore.NewTask(null, "default", null,
                        ScheduledTask.FRESH_THREAD_PER_RUN, "lead_agent", "Scheduled once", "say hello",
                        "once", Map.of("run_at", now.minusSeconds(60).toString()), "UTC",
                        now.minusSeconds(1)), now);

                fixture.api.scheduler().pollOnce();

                ScheduledTaskRun occurrence = awaitTerminal(store, task.id());
                assertThat(occurrence.status()).isEqualTo(ScheduledRunStatus.SUCCESS);
                assertThat(occurrence.runId()).isNotBlank();
                assertThat(occurrence.trigger()).isEqualTo(io.haifa.agent.superharness.server.schedule.ScheduledRunTrigger.SCHEDULED);
                assertThat(occurrence.startedAt()).isNotNull();
                assertThat(occurrence.finishedAt()).isNotNull();
                // No SSE client was used: the follower alone drove the terminal bookkeeping.
                assertThat(occurrence.threadId()).startsWith("sched-");

                ScheduledTask updated = awaitTask(store, task.id(), io.haifa.agent.superharness.server.schedule.ScheduledTaskStatus.COMPLETED);
                assertThat(updated.lastRunId()).isEqualTo(occurrence.runId());
                assertThat(updated.runCount()).isEqualTo(1);
                assertThat(updated.lastThreadId()).isEqualTo(occurrence.threadId());

                // The dispatched Run really reached the Provider once.
                assertThat(provider.calls).hasValue(1);
                assertThat(providerRequestToolNames(1))
                        .as("non-interactive: ask_clarification is excluded")
                        .doesNotContain("ask_clarification");
                System.out.println("W19_M2_DISPATCH_EVIDENCE thread=" + occurrence.threadId()
                        + " run=" + occurrence.runId() + " status=" + occurrence.status().wire()
                        + " provider_calls=" + provider.calls.get() + " dataRoot=" + dataRoot
                        + " evidence=" + evidence);
            }
        }
    }

    // W19-M2-10: a scheduled Run that needs approval enters WAITING_APPROVAL instead of being
    // silently allowed, and the occurrence stays active rather than being projected terminal.
    @Test
    void scheduledRunApprovalEntersWaitingApprovalWithoutClarificationTool() throws Exception {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        Map<String, String> environment = schedulerEnvironment(key, "3600");
        try (var provider = new ControlledProvider(evidence, true, (request, call) ->
                ControlledProvider.call("sched-approval-" + call, "demo-provider-id",
                        "call-sched-approval-" + call, "write_file",
                        Map.of("path", "/mnt/user-data/outputs/scheduled.txt", "content", "x")))) {
            try (var fixture = GatewayFixture.production(dataRoot, 0, environment, evidence)) {
                configureModel(fixture, provider.port());
                ScheduleStore store = fixture.api.scheduler().store();
                Instant now = Instant.now();
                ScheduledTask task = store.createTask(new ScheduleStore.NewTask(null, "default", null,
                        ScheduledTask.FRESH_THREAD_PER_RUN, "lead_agent", "Scheduled approval", "write a file",
                        "once", Map.of("run_at", now.minusSeconds(60).toString()), "UTC",
                        now.minusSeconds(1)), now);

                fixture.api.scheduler().pollOnce();

                ScheduledTaskRun occurrence = awaitLaunch(store, task.id());
                JsonNode run = awaitRunStatus(fixture, occurrence);
                assertThat(run.path("diagnostics").path("sdk_status").asText())
                        .as(run.toString()).isEqualTo("WAITING_APPROVAL");
                assertThat(provider.calls).hasValue(1);
                assertThat(providerRequestToolNames(1)).contains("write_file");
                assertThat(providerRequestToolNames(1)).doesNotContain("ask_clarification");
                // The occurrence keeps its active slot; it is never silently converged.
                assertThat(store.findRun(occurrence.id()).orElseThrow().status())
                        .isEqualTo(ScheduledRunStatus.RUNNING);
                System.out.println("W19_M2_APPROVAL_EVIDENCE thread=" + occurrence.threadId()
                        + " run=" + occurrence.runId() + " sdk_status=WAITING_APPROVAL"
                        + " ask_clarification=false");
            }
        }
    }

    // W19-M2-19 (AarenWang P1): the scheduled task's assistant identity reaches the prompt/tool
    // composition, so a non-default Agent's Soul appears in the real Provider request.
    @Test
    void scheduledRunAppliesNonDefaultAssistantSoul() throws Exception {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        Map<String, String> environment = schedulerEnvironment(key, "3600");
        String soul = "review-agent soul marker & <review> synthetic.";
        try (var provider = new ControlledProvider(evidence, true, (request, call) ->
                ControlledProvider.stop("sched-soul-" + call, "demo-provider-id", "done"))) {
            try (var fixture = GatewayFixture.production(dataRoot, 0, environment, evidence)) {
                configureModel(fixture, provider.port());
                fixture.seed("review-agent", soul);
                ScheduleStore store = fixture.api.scheduler().store();
                Instant now = Instant.now();
                ScheduledTask task = store.createTask(new ScheduleStore.NewTask(null, "default", null,
                        ScheduledTask.FRESH_THREAD_PER_RUN, "review-agent", "Scheduled identity", "say hello",
                        "once", Map.of("run_at", now.minusSeconds(60).toString()), "UTC",
                        now.minusSeconds(1)), now);

                fixture.api.scheduler().pollOnce();

                ScheduledTaskRun occurrence = awaitTerminal(store, task.id());
                assertThat(occurrence.status()).isEqualTo(ScheduledRunStatus.SUCCESS);
                assertThat(provider.calls).hasValue(1);
                io.haifa.agent.superharness.server.testsupport.AgentSoulTestSupport
                        .soulEquals(providerInstructions(1), soul);
            }
        }
    }

    // W19-M2-20 (AarenWang P1): the Thread active-Run guard defers an overlapping scheduled Run; a
    // second task bound to the same Thread keeps its occurrence queued while the first is pending.
    @Test
    void overlappingReuseThreadRunStaysQueued() throws Exception {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        Map<String, String> environment = schedulerEnvironment(key, "3600");
        try (var provider = new ControlledProvider(evidence, true, (request, call) ->
                ControlledProvider.call("sched-overlap-" + call, "demo-provider-id",
                        "call-sched-overlap-" + call, "write_file",
                        Map.of("path", "/mnt/user-data/outputs/overlap.txt", "content", "x")))) {
            try (var fixture = GatewayFixture.production(dataRoot, 0, environment, evidence)) {
                configureModel(fixture, provider.port());
                fixture.thread("shared-sched-thread");
                ScheduleStore store = fixture.api.scheduler().store();
                Instant now = Instant.now();
                ScheduledTask first = store.createTask(new ScheduleStore.NewTask("task-first", "default",
                        "shared-sched-thread", ScheduledTask.REUSE_THREAD, "lead_agent", "First", "write a file",
                        "once", Map.of("run_at", now.minusSeconds(60).toString()), "UTC",
                        now.minusSeconds(1)), now);
                ScheduledTask second = store.createTask(new ScheduleStore.NewTask("task-second", "default",
                        "shared-sched-thread", ScheduledTask.REUSE_THREAD, "lead_agent", "Second", "write a file",
                        "once", Map.of("run_at", now.minusSeconds(60).toString()), "UTC",
                        now.minusSeconds(1)), now);

                fixture.api.scheduler().pollOnce();

                ScheduledTaskRun firstRun = store.findActiveRun(first.id()).orElseThrow();
                ScheduledTaskRun secondRun = store.findActiveRun(second.id()).orElseThrow();
                ScheduledTaskRun launched = firstRun.runId() != null ? firstRun : secondRun;
                ScheduledTaskRun waiting = launched == firstRun ? secondRun : firstRun;
                assertThat(launched.status()).isEqualTo(ScheduledRunStatus.RUNNING);
                assertThat(waiting.status()).as("overlapping occurrence stays queued")
                        .isEqualTo(ScheduledRunStatus.QUEUED);
                assertThat(waiting.runId()).isNull();
                JsonNode run = awaitRunStatus(fixture, launched);
                assertThat(run.path("diagnostics").path("sdk_status").asText())
                        .as(run.toString()).isEqualTo("WAITING_APPROVAL");
                assertThat(provider.calls).hasValue(1);
            }
        }
    }

    private void configureModel(GatewayFixture fixture, int providerPort) throws Exception {        String model = """
                {"config":{"name":"demo-model","display_name":"Demo Model",
                "provider":"openai-compatible","model":"demo-provider-id",
                "base_url":"http://127.0.0.1:%d/v1","enabled":true,"supports_vision":false,
                "context_window":32768,"max_tokens":1024,"api_key":"controlled-key"},
                "expected_revision":null}
                """.formatted(providerPort);
        assertThat(http.request(fixture.base, "PUT", "/api/managed-models", model, "model.http")
                .statusCode()).isEqualTo(200);
    }

    private static Map<String, String> schedulerEnvironment(String key, String queueTimeout) {
        return Map.of(
                "HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W19_DISPATCH_TEST_KEY",
                "W19_DISPATCH_TEST_KEY", key,
                "HAIFA_SUPER_HARNESS_SCHEDULER_ENABLED", "true",
                "HAIFA_SUPER_HARNESS_SCHEDULER_POLL_SECONDS", "300",
                "HAIFA_SUPER_HARNESS_SCHEDULER_MIN_ONCE_DELAY_SECONDS", "1",
                "HAIFA_SUPER_HARNESS_SCHEDULER_QUEUE_TIMEOUT_SECONDS", queueTimeout);
    }

    private String providerInstructions(int call) throws Exception {
        JsonNode request = json.readTree(Files.readString(evidence.resolve("provider-request-" + call + ".json")));
        for (JsonNode message : request.path("messages")) {
            if ("system".equals(message.path("role").asText())
                    && message.path("content").asText().contains("open-source super agent")) {
                return message.path("content").asText();
            }
        }
        throw new AssertionError("provider request has no Lead system instructions");
    }

    private java.util.List<String> providerRequestToolNames(int call) throws Exception {        JsonNode request = json.readTree(Files.readString(evidence.resolve("provider-request-" + call + ".json")));
        java.util.List<String> names = new java.util.ArrayList<>();
        request.path("tools").forEach(tool -> names.add(tool.path("function").path("name").asText()));
        return names;
    }

    private ScheduledTaskRun awaitLaunch(ScheduleStore store, String taskId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            var runs = store.listRuns(taskId, "default", 10, 0, null);
            if (!runs.isEmpty() && runs.getFirst().runId() != null) {
                return store.findRun(runs.getFirst().id()).orElseThrow();
            }
            Thread.sleep(25);
        }
        throw new AssertionError("scheduled occurrence did not launch within 15s");
    }

    private ScheduledTask awaitTask(ScheduleStore store, String taskId,
            io.haifa.agent.superharness.server.schedule.ScheduledTaskStatus expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        ScheduledTask last = null;
        while (System.nanoTime() < deadline) {
            last = store.findTaskInternal(taskId).orElse(null);
            if (last != null && last.status() == expected) {
                return last;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("scheduled task did not reach " + expected + " within 15s: " + last);
    }

    private ScheduledTaskRun awaitTerminal(ScheduleStore store, String taskId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        ScheduledTaskRun last = null;
        while (System.nanoTime() < deadline) {
            var runs = store.listRuns(taskId, "default", 10, 0, null);
            if (!runs.isEmpty()) {
                last = runs.getFirst();
                if (last.status().terminal()) {
                    return last;
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("scheduled occurrence did not reach terminal within 30s: " + last);
    }

    private JsonNode awaitRunStatus(GatewayFixture fixture, ScheduledTaskRun occurrence) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        JsonNode run = null;
        while (System.nanoTime() < deadline) {
            run = http.get(fixture.base,
                    "/api/threads/" + occurrence.threadId() + "/runs/" + occurrence.runId(),
                    "scheduled-run.json");
            if ("WAITING_APPROVAL".equals(run.path("diagnostics").path("sdk_status").asText())) {
                return run;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("scheduled Run did not reach WAITING_APPROVAL: " + run);
    }
}

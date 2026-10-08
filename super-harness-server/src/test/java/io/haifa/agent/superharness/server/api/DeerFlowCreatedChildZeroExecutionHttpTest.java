package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.runtime.api.RunCancellation;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.superharness.server.run.HaifaRunBridge;
import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
import io.haifa.agent.superharness.server.testsupport.ControlledModel;
import io.haifa.agent.superharness.server.testsupport.DelegationHttpFixture;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import io.haifa.agent.superharness.server.testsupport.SseEvents;
import java.net.InetAddress;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Restored Oracle16: a real persisted Child already CREATED (QUEUED) whose executor wrapper is
 * held before physical execution. Parent cancellation and Parent deadline timeout must settle the
 * actual Child, its actual task Tool result, frozen HTTP/SSE and restart without inventing a
 * {@code task_started}, model call or step. This is not the three-running-children cancellation
 * test: here the Child never physically executes.
 */
class DeerFlowCreatedChildZeroExecutionHttpTest {
    private static final String THREAD = "held-child";
    private static final String AGENT = "managed-child";

    private Path data;
    private Path evidence;
    private GatewayFixture.HttpEvidence http;

    @BeforeEach void prepare() throws Exception {
        data = GatewayFixture.dataDirectory();
        evidence = GatewayFixture.evidenceDirectory();
        http = new GatewayFixture.HttpEvidence(evidence, Duration.ofSeconds(40));
    }

    @AfterEach void close() throws InterruptedException { if (http != null) http.close(); }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void heldCreatedChildSettlesOnParentTerminationWithoutInventingPhysicalStart(boolean deadline) throws Exception {
        var held = new AtomicReference<HeldExecutionExecutor>();
        var childModelCalls = new AtomicInteger();
        var model = new ControlledModel((request, call) -> {
            if (request.messages().stream().anyMatch(message -> message.content().contains(DelegationHttpFixture.CHILD_MARKER))) {
                // The held Child wrapper must never reach its model.
                childModelCalls.incrementAndGet();
                return ControlledModel.stop(request);
            }
            if (call == 1) {
                return ControlledModel.call(request, "task",
                        Map.of("agent", AGENT, "objective", "Held child work must not physically start"));
            }
            return ControlledModel.stop(request);
        });
        Supplier<? extends ExecutorService> factory = () -> {
            var executor = new HeldExecutionExecutor(Executors.newVirtualThreadPerTaskExecutor());
            held.set(executor);
            return executor;
        };
        Map<String, String> environment = environment();
        var configuration = new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0);
        String location;
        String taskId;
        String terminalCursor;
        try (var gateway = new GatewayFixture(data, DeerFlowBootstrapApi.createForExecutorTests(
                configuration, data, environment, ignored -> LocalProbeModelTestSupport.binding(model), factory), evidence);
             AutoCloseable cleanup = () -> {
                 if (held.get() != null) held.get().drainHeld(Duration.ofSeconds(10));
             }) {
            DelegationHttpFixture.configure(http, gateway.base, 1);
            DelegationHttpFixture.seed(http, gateway.base, AGENT, 30, 3, List.of());
            assertThat(http.request(gateway.base, "POST", "/api/threads",
                    "{\"thread_id\":\"" + THREAD + "\"}").statusCode()).isEqualTo(200);
            var request = HttpRequest.newBuilder(gateway.base.resolve("/api/threads/" + THREAD + "/runs/stream"))
                    .timeout(Duration.ofSeconds(40)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(DelegationHttpFixture.input(true).toString())).build();
            var response = http.client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                assertThat(response.statusCode()).isEqualTo(200);
                location = response.headers().firstValue("content-location").orElseThrow();
                String runId = location.substring(location.lastIndexOf('/') + 1);
                var parent = new AgentRunId(runId);
                assertThat(held.get()).as("The SDK must build the Agent with the executor factory").isNotNull();
                assertThat(held.get().retained.await(10, TimeUnit.SECONDS))
                        .as("The Child execution wrapper must be retained before physical execution").isTrue();
                var bridge = api(gateway).currentBridge(THREAD);
                assertThat(bridge).isNotNull();

                // The actual Child is created and persisted QUEUED, but has no physical start.
                var children = bridge.children(parent);
                assertThat(children).hasSize(1);
                var child = children.getFirst();
                assertThat(child.parentRunId()).isEqualTo(parent);
                assertThat(child.status()).isEqualTo(AgentRunStatus.QUEUED);
                assertThat(child.startedAt()).isEmpty();
                assertThat(childModelCalls.get()).as("Held Child must not call its model").isZero();
                assertNoPhysicalExecution(bridge, parent, child.runId());

                // The actual task Tool identity is the public Parent task association.
                var taskIds = bridge.childTaskIds(parent, children);
                assertThat(taskIds).containsKey(child.runId());
                taskId = taskIds.get(child.runId());
                assertThat(taskId).isNotBlank();
                recordChildFacts(bridge, parent, child.runId(), taskId, "queued");
                assertThat(http.get(gateway.base, events(location, taskId), "held-child-events.json"))
                        .as("No subagent steps exist for a Child that never ran").isEmpty();
                assertThat(http.get(gateway.base, events(location, "unknown-task"), "held-child-unknown-task.json"))
                        .as("Existing protocol returns an empty page for an unassociated task").isEmpty();
                assertThat(http.request(gateway.base, "GET", events("/api/threads/missing/runs/missing", taskId),
                        "", "held-child-missing-run.http").statusCode()).isEqualTo(404);

                // Parent termination: real HTTP 202 cancel, or the public deadline reason through
                // the narrow host bridge test accessor (never a fabricated HTTP response).
                if (deadline) {
                    assertThat(bridge.cancel(parent, RunCancellation.deadlineExceeded(Duration.ofSeconds(1)))).isTrue();
                } else {
                    assertThat(http.request(gateway.base, "POST", location + "/cancel?action=interrupt&wait=0",
                            "", "held-child-cancel.http").statusCode()).isEqualTo(202);
                }

                String body = GatewayFixture.drain(stream);
                Files.writeString(evidence.resolve(deadline ? "held-child-deadline.sse" : "held-child-cancel.sse"), body);
                JsonNode run = http.get(gateway.base, location, "held-child-run.json");
                assertThat(run.path("diagnostics").path("sdk_status").asText())
                        .isEqualTo(deadline ? "TIMEOUT" : "CANCELLED");
                assertThat(customs(body))
                        .as("No invented task_started/task_running for a Child that never physically executed")
                        .noneMatch(event -> "task_started".equals(event.path("type").asText())
                                || "task_running".equals(event.path("type").asText()));
                assertThat(customs(body).stream()
                        .filter(event -> taskId.equals(event.path("task_id").asText())).toList())
                        .allSatisfy(event -> assertThat(event.path("type").asText())
                                .isNotIn("task_started", "task_running"));
                assertThat(customs(body).stream()
                        .filter(event -> taskId.equals(event.path("task_id").asText()))
                        .filter(event -> "task_cancelled".equals(event.path("type").asText())).toList())
                        .as("The actual created task must emit one terminal cancellation").hasSize(1);

                // The actual Child settled as an actual PARENT_CANCELLED Run.
                var settled = bridge.children(parent);
                assertThat(settled).hasSize(1);
                assertThat(settled.getFirst().status()).isEqualTo(AgentRunStatus.CANCELLED);
                assertThat(settled.getFirst().startedAt()).isEmpty();
                assertThat(bridge.snapshot(settled.getFirst().runId()).terminationReason()
                        .map(reason -> reason.code()).orElse("")).isEqualTo("PARENT_CANCELLED");
                recordChildFacts(bridge, parent, child.runId(), taskId, "terminal");

                // The actual task Tool result is cancelled.
                JsonNode state = http.get(gateway.base, "/api/threads/" + THREAD + "/state", "held-child-state.json");
                var result = DelegationHttpFixture.messages(state).stream()
                        .filter(message -> taskId.equals(message.path("tool_call_id").asText())).findFirst().orElseThrow();
                assertThat(result.path("additional_kwargs").path("subagent_status").asText()).isEqualTo("cancelled");
                assertThat(http.get(gateway.base, events(location, taskId), "held-child-events-terminal.json")).isEmpty();
                terminalCursor = body.lines().filter(line -> line.startsWith("id: ")).map(line -> line.substring(4))
                        .reduce((left, right) -> right).orElseThrow();

                // A bounded drain of the retained wrapper must skip it: no start, no model, no events.
                assertThat(held.get().drainHeld(Duration.ofSeconds(10)))
                        .as("The held wrapper must be bounded-drainable after the functional terminal").isTrue();
                assertThat(childModelCalls.get()).isZero();
                assertNoPhysicalExecution(bridge, parent, child.runId());
                recordChildFacts(bridge, parent, child.runId(), taskId, "after-drain");
                assertThat(http.get(gateway.base, events(location, taskId), "held-child-events-after-drain.json")).isEmpty();
            }
        }

        try (var reopened = new GatewayFixture(data, DeerFlowBootstrapApi.createForExecutorTests(
                configuration, data, environment, ignored -> LocalProbeModelTestSupport.binding(model), factory), evidence)) {
            JsonNode replayed = http.get(reopened.base, location, "held-child-run-reopened.json");
            assertThat(replayed.path("diagnostics").path("sdk_status").asText())
                    .isEqualTo(deadline ? "TIMEOUT" : "CANCELLED");
            var reopenedBridge = api(reopened).currentBridge(THREAD);
            assertThat(reopenedBridge).isNotNull();
            var reopenedChildren = reopenedBridge.children(new AgentRunId(location.substring(location.lastIndexOf('/') + 1)));
            assertThat(reopenedChildren).hasSize(1);
            assertThat(reopenedChildren.getFirst().parentRunId())
                    .isEqualTo(new AgentRunId(location.substring(location.lastIndexOf('/') + 1)));
            assertThat(reopenedChildren.getFirst().status()).isEqualTo(AgentRunStatus.CANCELLED);
            assertThat(reopenedChildren.getFirst().startedAt()).isEmpty();
            assertThat(reopenedBridge.snapshot(reopenedChildren.getFirst().runId()).terminationReason()
                    .map(reason -> reason.code()).orElse("")).isEqualTo("PARENT_CANCELLED");
            assertThat(reopenedBridge.childTaskIds(new AgentRunId(location.substring(location.lastIndexOf('/') + 1)),
                    reopenedChildren).get(reopenedChildren.getFirst().runId())).isEqualTo(taskId);
            assertNoPhysicalExecution(reopenedBridge, new AgentRunId(location.substring(location.lastIndexOf('/') + 1)), reopenedChildren.getFirst().runId());
            recordChildFacts(reopenedBridge, new AgentRunId(location.substring(location.lastIndexOf('/') + 1)),
                    reopenedChildren.getFirst().runId(), taskId, "reopened");
            JsonNode reopenedState = http.get(reopened.base, "/api/threads/" + THREAD + "/state",
                    "held-child-state-reopened.json");
            var reopenedResult = DelegationHttpFixture.messages(reopenedState).stream()
                    .filter(message -> taskId.equals(message.path("tool_call_id").asText())).findFirst().orElseThrow();
            assertThat(reopenedResult.path("additional_kwargs").path("subagent_status").asText()).isEqualTo("cancelled");
            assertThat(http.get(reopened.base, events(location, taskId), "held-child-events-reopened.json")).isEmpty();
            var joined = http.client.send(HttpRequest.newBuilder(reopened.base.resolve(location + "/stream"))
                    .timeout(Duration.ofSeconds(40)).header("Last-Event-ID", terminalCursor).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            Files.writeString(evidence.resolve("held-child-reopened-join.sse"), joined.body());
            assertThat(joined.statusCode()).isEqualTo(200);
            assertThat(joined.body()).contains("event: end").doesNotContain("event: error");
            assertThat(customs(joined.body()))
                    .noneMatch(event -> "task_started".equals(event.path("type").asText())
                            || "task_running".equals(event.path("type").asText()));
        }
    }

    private static DeerFlowThreadRunApi api(GatewayFixture gateway) {
        return gateway.api.threadRunApi();
    }

    private static void assertNoPhysicalExecution(HaifaRunBridge bridge, AgentRunId parent, AgentRunId childId) {
        var snapshot = bridge.snapshot(childId);
        assertThat(bridge.children(parent).stream().filter(child -> child.runId().equals(childId))
                .findFirst().orElseThrow().startedAt()).isEmpty();
        assertThat(snapshot.usage().modelCalls()).isZero();
        assertThat(snapshot.usage().inputTokens()).isZero();
        assertThat(snapshot.usage().outputTokens()).isZero();
        var events = bridge.events(childId, bridge.beforeFirstEvent(childId), 500).items();
        assertThat(events.stream().map(event -> event.payload())
                .filter(RunEventPayloads.RunLifecycle.class::isInstance)
                .map(RunEventPayloads.RunLifecycle.class::cast)
                .map(RunEventPayloads.RunLifecycle::status).toList()).doesNotContain("RUNNING");
        assertThat(events.stream().map(event -> event.eventType()).toList())
                .noneMatch(type -> type.startsWith("model.call") || type.startsWith("step."));
        assertThat(bridge.toolCalls(childId)).isEmpty();
    }

    private void recordChildFacts(HaifaRunBridge bridge, AgentRunId parent, AgentRunId child, String taskId,
            String phase) throws Exception {
        var snapshot = bridge.snapshot(child);
        var events = bridge.events(child, bridge.beforeFirstEvent(child), 500).items();
        var summary = Map.of("parent_run_id", parent.value(), "child_run_id", child.value(), "task_id", taskId,
                "status", snapshot.status().name(), "physically_started", bridge.children(parent).stream().filter(candidate -> candidate.runId().equals(child))
                        .findFirst().orElseThrow().startedAt().isPresent(),
                "model_calls", snapshot.usage().modelCalls(), "input_tokens", snapshot.usage().inputTokens(),
                "output_tokens", snapshot.usage().outputTokens(),
                "termination_reason", snapshot.terminationReason().map(reason -> reason.code()).orElse(""),
                "events", events.stream().map(event -> Map.of("type", event.eventType(),
                        "lifecycle_status", event.payload() instanceof RunEventPayloads.RunLifecycle lifecycle
                                ? lifecycle.status() : "")).toList());
        Files.writeString(evidence.resolve("held-child-facts-" + phase + ".json"),
                GatewayFixture.JSON.writeValueAsString(summary));
    }

    private static List<JsonNode> customs(String body) {
        assertThat(SseEvents.read(body).named("error")).isEmpty();
        assertThat(SseEvents.read(body).named("end")).hasSize(1);
        return SseEvents.read(body).named("custom").stream().map(SseEvents.Event::data).toList();
    }

    private static String events(String location, String taskId) {
        return location + "/events?event_types=subagent.step&task_id=" + taskId;
    }

    private static Map<String, String> environment() {
        return Map.of("HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W08_ORACLE16_TEST_KEY",
                "W08_ORACLE16_TEST_KEY", Base64.getEncoder().encodeToString(new byte[32]));
    }

    /**
     * Minimal test-only physical-execution boundary: the first submitted run wrapper (the Parent)
     * runs on the real executor; every later wrapper (an actual Child) is retained until the test
     * bounded-drains it after the functional terminal. The SDK owns the executor lifetime.
     */
    static final class HeldExecutionExecutor extends AbstractExecutorService {
        private final ExecutorService delegate;
        final CountDownLatch retained = new CountDownLatch(1);
        private final List<Runnable> held = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger submitted = new AtomicInteger();
        private volatile boolean shutdown;

        HeldExecutionExecutor(ExecutorService delegate) { this.delegate = delegate; }

        @Override public void execute(Runnable command) {
            if (submitted.incrementAndGet() == 1) {
                delegate.execute(command);
            } else {
                held.add(command);
                retained.countDown();
            }
        }

        boolean drainHeld(Duration timeout) throws Exception {
            List<Runnable> tasks;
            synchronized (held) {
                tasks = new ArrayList<>(held);
                held.clear();
            }
            if (!tasks.isEmpty()) delegate.submit(() -> { tasks.forEach(Runnable::run); return null; })
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        }

        @Override public void shutdown() { shutdown = true; delegate.shutdown(); }

        @Override public List<Runnable> shutdownNow() { shutdown = true; return delegate.shutdownNow(); }

        @Override public boolean isShutdown() { return shutdown || delegate.isShutdown(); }

        @Override public boolean isTerminated() { return delegate.isTerminated(); }

        @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}

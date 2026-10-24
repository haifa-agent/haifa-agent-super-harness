package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.superharness.server.testsupport.ControlledModel;
import io.haifa.agent.superharness.server.testsupport.ControlledProvider;
import io.haifa.agent.superharness.server.testsupport.DelegationHttpFixture;
import io.haifa.agent.superharness.server.testsupport.DirectoryMonitor;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import io.haifa.agent.superharness.server.testsupport.SseEvents;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real production Gateway/HTTP and published SDK; source cursors are never manufactured by the test. */
class DeerFlowSubtaskProjectionApiTest {
    private Path data;
    private Path evidence;
    private GatewayFixture.HttpEvidence http;

    @BeforeEach void prepare() throws Exception {
        data = GatewayFixture.dataDirectory();
        evidence = GatewayFixture.evidenceDirectory();
        http = new GatewayFixture.HttpEvidence(evidence, Duration.ofSeconds(40));
    }

    @AfterEach void close() throws InterruptedException { if (http != null) http.close(); }

    @Test void actualStepsMatchLivePagesAndSameDataReopenWithoutLeakingIntoParentHistory() throws Exception {
        String location;
        String taskId;
        String original;
        String terminalCursor;
        var environment = environment();
        try (var provider = new ControlledProvider(evidence, true, DelegationHttpFixture.readSequence("researcher"))) {
            try (var gateway = GatewayFixture.production(data, 0, environment, evidence)) {
                DelegationHttpFixture.configure(http, gateway.base, provider.port());
                DelegationHttpFixture.seed(http, gateway.base, "researcher", 30, 3, List.of("list_uploaded_files"));
                location = DelegationHttpFixture.execute(http, gateway.base, "steps", DelegationHttpFixture.input(true));
                JsonNode state = http.get(gateway.base, "/api/threads/steps/state", "parent-state.json");
                taskId = onlyTask(state).path("id").asText();
                assertThat(taskId).isNotBlank().isNotEqualTo("lead-provider-task");
                assertParentHistory(state, taskId, "completed");
                original = http.request(gateway.base, "GET", events(location, taskId), "", "steps-full.json").body();
                JsonNode replay = GatewayFixture.JSON.readTree(original);
                assertSteps(replay, taskId);
                List<JsonNode> custom = customs(Files.readString(evidence.resolve("steps.sse")));
                terminalCursor = Files.readString(evidence.resolve("steps.sse")).lines()
                        .filter(line -> line.startsWith("id: ")).map(line -> line.substring(4))
                        .reduce((left, right) -> right).orElseThrow();
                assertThat(custom.stream().filter(event -> event.path("type").asText().equals("task_started")))
                        .hasSize(1).allSatisfy(event -> {
                            assertThat(event.path("task_id").asText()).isEqualTo(taskId);
                            assertThat(event.path("description").asText()).isEqualTo(onlyTask(state).path("args").path("prompt").asText());
                        });
                assertLiveReplay(custom, replay, taskId);
                saveConsumerFixture("frozen-consumer-fixture.json", custom, replay, taskId);
                JsonNode terminal = custom.stream().filter(event -> "task_completed".equals(event.path("type").asText()))
                        .findFirst().orElseThrow();
                assertThat(terminal.path("result").asText()).isEqualTo("CHILD-OUTPUT");
                // The published ModelLifecycle events carry the configured provider model ID.
                assertThat(terminal.path("model_name").asText()).isEqualTo("w08-model");
                assertThat(terminal.path("usage").path("input_tokens").asLong()).isEqualTo(15);
                assertThat(terminal.path("usage").path("output_tokens").asLong()).isEqualTo(16);
                assertThat(terminal.path("usage").path("total_tokens").asLong()).isEqualTo(31);
                long prior = 0;
                for (JsonNode event : custom) {
                    if (event.has("usage")) {
                        long total = event.path("usage").path("total_tokens").asLong();
                        assertThat(total).isGreaterThanOrEqualTo(prior);
                        prior = total;
                    }
                }
                JsonNode first = http.get(gateway.base, events(location, taskId) + "&limit=2", "steps-page-1.json");
                assertThat(first.size()).isEqualTo(2);
                long last = first.get(1).path("seq").asLong();
                JsonNode rest = http.get(gateway.base, events(location, taskId) + "&limit=2&after_seq=" + last, "steps-page-2.json");
                ArrayNode combined = GatewayFixture.JSON.createArrayNode().addAll((ArrayNode) first).addAll((ArrayNode) rest);
                assertThat(combined).isEqualTo(replay);
                assertThat(http.request(gateway.base, "GET", events(location, taskId), "", "steps-repeat.json").body()).isEqualTo(original);
                assertValidationAndOwnership(gateway.base, location, taskId);
                assertCompletedJoin(gateway.base, location, terminalCursor, replay, taskId, "steps-cached-join.sse");
                // Evict the original terminal Run from the actual eight-entry cache.
                for (int index = 0; index < 9; index++) {
                    DelegationHttpFixture.execute(http, gateway.base, "evict-" + index, DelegationHttpFixture.input(false));
                }
                assertCompletedJoin(gateway.base, location, terminalCursor, replay, taskId, "steps-evicted-join.sse");
                assertThat(provider.failure.get()).isNull();
            }
            try (var reopened = GatewayFixture.production(data, 0, environment, evidence)) {
                assertThat(http.request(reopened.base, "GET", events(location, taskId), "", "steps-reopened.json").body())
                        .isEqualTo(original);
                assertParentHistory(http.get(reopened.base, "/api/threads/steps/state", "parent-state-reopened.json"), taskId, "completed");
                assertCompletedJoin(reopened.base, location, terminalCursor,
                        GatewayFixture.JSON.readTree(original), taskId, "steps-restarted-join.sse");
            }
        }
    }

    private void assertCompletedJoin(URI base, String location, String cursor, JsonNode replay,
            String taskId, String name) throws Exception {
        var response = http.client.send(HttpRequest.newBuilder(base.resolve(location + "/stream"))
                .timeout(Duration.ofSeconds(40)).header("Last-Event-ID", cursor).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Files.writeString(evidence.resolve(name), response.body());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("event: end").doesNotContain("event: error");
        var custom = customs(response.body());
        assertThat(custom.stream().filter(event -> "task_started".equals(event.path("type").asText()))).hasSize(1);
        assertThat(custom.stream().filter(event -> "task_completed".equals(event.path("type").asText()))).hasSize(1);
        assertLiveReplay(custom, replay, taskId);
    }

    @Test void unrequestedCustomModeStaysDisabledAfterEvictionAndRestart() throws Exception {
        String location;
        String cursor;
        try (var provider = new ControlledProvider(evidence, true, DelegationHttpFixture.readSequence("researcher"))) {
            try (var gateway = GatewayFixture.production(data, 0, environment(), evidence)) {
                DelegationHttpFixture.configure(http, gateway.base, provider.port());
                DelegationHttpFixture.seed(http, gateway.base, "researcher", 30, 3, List.of("list_uploaded_files"));
                var input = DelegationHttpFixture.input(true);
                input.putArray("stream_mode").add("messages-tuple").add("updates");
                location = DelegationHttpFixture.execute(http, gateway.base, "no-custom", input);
                String body = Files.readString(evidence.resolve("no-custom.sse"));
                cursor = body.lines().filter(line -> line.startsWith("id: ")).map(line -> line.substring(4))
                        .reduce((left, right) -> right).orElseThrow();
                assertThat(customs(body)).isEmpty();
                assertNoCustomJoin(gateway.base, location, cursor, "no-custom-cached.sse");
                // A later Turn in the same Thread selects custom; the original Run must keep its own choice.
                var next = http.request(gateway.base, "POST", "/api/threads/no-custom/runs/stream",
                        DelegationHttpFixture.input(false).toString(), "no-custom-later-turn.sse");
                assertThat(next.statusCode()).isEqualTo(200);
                assertThat(next.body()).contains("event: end");
                for (int index = 0; index < 9; index++) {
                    DelegationHttpFixture.execute(http, gateway.base, "no-custom-evict-" + index, DelegationHttpFixture.input(false));
                }
                assertNoCustomJoin(gateway.base, location, cursor, "no-custom-evicted.sse");
                assertThat(http.get(gateway.base, "/api/threads/no-custom", "no-custom-thread.json")
                        .path("metadata").has("haifa_run_stream_modes")).isFalse();
            }
            int calls = provider.calls.get();
            try (var reopened = GatewayFixture.production(data, 0, environment(), evidence)) {
                assertNoCustomJoin(reopened.base, location, cursor, "no-custom-restarted.sse");
                assertThat(provider.calls.get()).isEqualTo(calls);
            }
        }
    }

    private void assertNoCustomJoin(URI base, String location, String cursor, String name) throws Exception {
        var response = http.client.send(HttpRequest.newBuilder(base.resolve(location + "/stream"))
                .timeout(Duration.ofSeconds(40)).header("Last-Event-ID", cursor).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Files.writeString(evidence.resolve(name), response.body());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("event: end").doesNotContain("event: error", "event: custom");
    }

    @Test void slowChildCancellationPreservesParentEndAndLaterActualChildReplay() throws Exception {
        var admitted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var childFinished = new CountDownLatch(1);
        AtomicInteger lead = new AtomicInteger();
        var model = new ControlledModel((request, call) -> {
            if (request.messages().stream().anyMatch(message -> message.content().contains(DelegationHttpFixture.CHILD_MARKER))) {
                admitted.countDown();
                long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                try {
                    // Real in-flight execution deliberately survives interrupt until the test releases it.
                    while (release.getCount() != 0 && System.nanoTime() < deadline) {
                        try { release.await(100, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException ignored) { /* exercise asynchronous SDK cancellation */ }
                    }
                    if (release.getCount() != 0) throw new IllegalStateException("Slow Child cancellation deadline expired");
                    return ControlledModel.stop(request);
                } finally { childFinished.countDown(); }
            }
            if (lead.incrementAndGet() == 1) return ControlledModel.call(request, "task",
                    Map.of("agent", "researcher", "objective", "Observe bounded slow cancellation"));
            return ControlledModel.stop(request);
        });
        try (var gateway = new GatewayFixture(data, model, evidence)) {
            DelegationHttpFixture.configure(http, gateway.base, 1);
            DelegationHttpFixture.seed(http, gateway.base, "researcher", 30, 3, List.of());
            assertThat(http.request(gateway.base, "POST", "/api/threads", "{\"thread_id\":\"slow-cancel\"}").statusCode()).isEqualTo(200);
            var request = HttpRequest.newBuilder(gateway.base.resolve("/api/threads/slow-cancel/runs/stream"))
                    .timeout(Duration.ofSeconds(40)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(DelegationHttpFixture.input(true).toString())).build();
            var response = http.client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String location = response.headers().firstValue("content-location").orElseThrow();
            try (var stream = response.body()) {
                assertThat(admitted.await(10, TimeUnit.SECONDS)).isTrue();
                long cancelledAt = System.nanoTime();
                assertThat(http.request(gateway.base, "POST", location + "/cancel?action=interrupt&wait=0", "", "slow-cancel.http")
                        .statusCode()).isEqualTo(202);
                String body = GatewayFixture.drain(stream);
                Files.writeString(evidence.resolve("slow-cancel.sse"), body);
                assertThat(body).contains("event: end").doesNotContain("event: error");
                assertThat(Duration.ofNanos(System.nanoTime() - cancelledAt)).isGreaterThanOrEqualTo(Duration.ofSeconds(2));
                assertThat(childFinished.getCount()).as("Parent end must arrive before slow execution is released").isEqualTo(1);
                assertThat(customs(body)).noneMatch(event -> "task_cancelled".equals(event.path("type").asText()));
                assertThat(http.get(gateway.base, location, "slow-parent-terminal.json")
                        .path("diagnostics").path("sdk_status").asText()).isEqualTo("CANCELLED");
                release.countDown();
                assertThat(childFinished.await(10, TimeUnit.SECONDS)).isTrue();
                String cursor = body.lines().filter(line -> line.startsWith("id: ")).map(line -> line.substring(4))
                        .reduce((left, right) -> right).orElseThrow();
                var joined = http.client.send(HttpRequest.newBuilder(gateway.base.resolve(location + "/stream"))
                        .timeout(Duration.ofSeconds(40)).header("Last-Event-ID", cursor).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                Files.writeString(evidence.resolve("slow-cancel-late-join.sse"), joined.body());
                assertThat(joined.body()).contains("event: end").doesNotContain("event: error");
                assertThat(customs(joined.body()).stream().filter(event -> "task_cancelled".equals(event.path("type").asText())))
                        .hasSize(1);
            } finally { release.countDown(); }
        }
    }

    @Test void rejectedTaskHasAuthoritativeToolMessageButNoInventedChildCustomOrSteps() throws Exception {
        try (var provider = new ControlledProvider(evidence, true, DelegationHttpFixture.readSequence("absent-agent"));
                var gateway = GatewayFixture.production(data, 0, environment(), evidence)) {
            DelegationHttpFixture.configure(http, gateway.base, provider.port());
            // An actual directory exists: absence of this requested name is a creation-before-admission rejection.
            DelegationHttpFixture.seed(http, gateway.base, "researcher", 30, 3, List.of("list_uploaded_files"));
            String location = DelegationHttpFixture.execute(http, gateway.base, "rejected", DelegationHttpFixture.input(true));
            JsonNode state = http.get(gateway.base, "/api/threads/rejected/state", "rejected-state.json");
            String taskId = onlyTask(state).path("id").asText();
            assertParentHistory(state, taskId, "failed");
            assertThat(customs(Files.readString(evidence.resolve("rejected.sse"))))
                    .noneMatch(event -> event.path("type").asText().startsWith("task_"));
            assertThat(http.get(gateway.base, events(location, taskId), "rejected-steps.json")).isEmpty();
            assertThat(provider.requests).hasSize(2);
        }
    }

    @Test void largeAuthorizedTextAndArgumentsRemainBoundedWithRealTruncationFlags() throws Exception {
        String text = "x".repeat(8_191) + "😀界" + "x".repeat(45_000);
        String path = "/mnt/user-data/outputs/denied-large.txt";
        String content = "x".repeat(20_000);
        AtomicInteger parentCalls = new AtomicInteger(), childCalls = new AtomicInteger();
        try (var provider = new ControlledProvider(evidence, true, (request, call) -> {
            if (DelegationHttpFixture.isChild(request)) {
                assertThat(request.path("max_tokens").asInt()).isEqualTo(16_384);
                assertThat(request.path("model").asText()).isEqualTo("w08-model");
                if (childCalls.incrementAndGet() == 1) return ControlledProvider.call("long-" + call, "w08-model",
                        "child-long-args", "write_file", Map.of("path", path, "content", content));
                assertThat(java.util.stream.StreamSupport.stream(request.path("messages").spliterator(), false)
                        .filter(message -> message.path("role").asText().equals("tool"))
                        .map(message -> message.path("content").asText()).toList())
                        .anySatisfy(result -> assertThat(result).contains("denied by policy"));
                return ControlledProvider.stop("long-" + call, "w08-model", text);
            }
            assertThat(request.path("max_tokens").asInt()).isEqualTo(1_024);
            assertThat(request.path("model").asText()).isEqualTo("w08-model");
            if (parentCalls.incrementAndGet() == 1) return ControlledProvider.call("lead-" + call, "w08-model",
                    "lead-long-task", "task", Map.of("agent", "researcher", "objective", "Attempt the bounded write and report"));
            return ControlledProvider.stop("lead-" + call, "w08-model", "LEAD-OUTPUT");
        }); var gateway = GatewayFixture.production(data, 0, environment(), evidence)) {
            DelegationHttpFixture.configure(http, gateway.base, provider.port());
            String childModel = """
                    {"config":{"name":"w08-large-child","provider":"openai-compatible","model":"w08-model",
                    "base_url":"http://127.0.0.1:%d/v1","enabled":true,"supports_vision":false,
                    "context_window":32768,"max_tokens":16384,"api_key":"controlled-key"},"expected_revision":null}
                    """.formatted(provider.port());
            assertThat(http.request(gateway.base, "PUT", "/api/managed-models", childModel,
                    "large-child-model-put.json").statusCode()).isEqualTo(200);
            DelegationHttpFixture.seed(http, gateway.base, "researcher", 30, 3, List.of("write_file"));
            assertThat(http.request(gateway.base, "PUT", "/api/subagents/researcher",
                    "{\"model\":\"w08-large-child\"}", "large-child-model-selection.json").statusCode()).isEqualTo(200);
            JsonNode models = http.get(gateway.base, "/api/managed-models", "large-model-configurations.json");
            for (String name : List.of("w08", "w08-large-child")) {
                JsonNode model = java.util.stream.StreamSupport.stream(models.path("models").spliterator(), false)
                        .filter(candidate -> candidate.path("name").asText().equals(name)).findFirst().orElseThrow();
                assertThat(model.path("context_window").asInt()).isEqualTo(32_768);
                assertThat(model.path("max_tokens").asInt()).isEqualTo(name.equals("w08") ? 1_024 : 16_384);
                assertThat(model.path("model").asText()).isEqualTo("w08-model");
                assertThat(model.path("revision").asText()).isNotBlank();
            }
            String location = DelegationHttpFixture.execute(http, gateway.base, "large", DelegationHttpFixture.input(true));
            JsonNode state = http.get(gateway.base, "/api/threads/large/state", "large-state.json");
            String taskId = onlyTask(state).path("id").asText();
            assertParentHistory(state, taskId, "completed");
            assertThat(childCalls.get()).isEqualTo(2);
            assertThat(parentCalls.get()).isEqualTo(2);
            assertThat(http.request(gateway.base, "GET", "/api/threads/large/artifacts" + path, null,
                    "large-denied-file.http").statusCode()).isEqualTo(404);
            JsonNode replay = http.get(gateway.base, events(location, taskId), "large-steps.json");
            assertThat(replay.size()).isEqualTo(3);
            JsonNode args = replay.get(0).path("content").path("tool_calls").get(0);
            assertThat(args.path("args_truncated").asBoolean()).isTrue();
            String serialized = args.path("args").isTextual() ? args.path("args").asText()
                    : GatewayFixture.JSON.writeValueAsString(args.path("args"));
            assertThat(serialized.codePointCount(0, serialized.length())).isLessThanOrEqualTo(8_192);
            JsonNode finalText = replay.get(2).path("content");
            assertThat(finalText.path("text").asText()).isEqualTo(text.substring(0, text.offsetByCodePoints(0, 8_192)));
            assertThat(finalText.path("truncated").asBoolean()).isTrue();
            String largeBody = Files.readString(evidence.resolve("large.sse"));
            var custom = customs(largeBody);
            assertLiveReplay(custom, replay, taskId);
            assertThat(largeBody)
                    .doesNotContain("WAITING_APPROVAL", "human_input_request");
            assertThat(SseEvents.read(largeBody).named("messages")).isNotEmpty().allSatisfy(event -> {
                JsonNode tuple = event.data();
                assertThat(tuple.isArray() && tuple.size() == 2).isTrue();
                assertThat(tuple.get(0).path("artifact").has("human_input")).isFalse();
            });
            assertThat(DelegationHttpFixture.messages(state)).isNotEmpty().allSatisfy(message ->
                    assertThat(message.path("artifact").has("human_input")).isFalse());
            saveConsumerFixture("frozen-consumer-fixture.json", custom, replay, taskId);
            assertThat(provider.failure.get()).isNull();
        }
    }

    @Test void interleavedChildrenKeepIndependentPersistentCursorsAcrossConcurrentAppends() throws Exception {
        interleavedChildrenKeepIndependentPersistentCursors(null);
    }

    /**
     * Same real Run as the sibling test, but its data directory is held open by an external-style
     * watcher. Reproduces the observed flake where the disconnected prefix can contain no durable
     * {@code id:} because the id-less subtask frames arrive before any Parent projection frame.
     */
    @Test void interleavedChildrenKeepIndependentPersistentCursorsUnderMonitoredDataDirectory() throws Exception {
        try (var monitor = DirectoryMonitor.open(data)) {
            interleavedChildrenKeepIndependentPersistentCursors(monitor);
        }
    }

    private void interleavedChildrenKeepIndependentPersistentCursors(DirectoryMonitor monitor)
            throws Exception {
        CountDownLatch atSecondModel = new CountDownLatch(2), release = new CountDownLatch(1);
        AtomicInteger lead = new AtomicInteger();
        var phases = new ConcurrentHashMap<String, AtomicInteger>();
        var model = new ControlledModel((request, call) -> {
            String transcript = request.messages().stream().map(message -> message.content()).collect(java.util.stream.Collectors.joining("\n"));
            if (!transcript.contains(DelegationHttpFixture.CHILD_MARKER)) {
                int parentPhase = lead.incrementAndGet();
                // A real Parent Tool result supplies a durable SSE cursor before Child gates hold tasks.
                if (parentPhase == 1) return ControlledModel.call(request, "list_uploaded_files", Map.of());
                if (parentPhase == 2) return ControlledModel.response(request, "lead-" + call, "", List.of(
                        new ModelToolCall(new ProviderToolCallCorrelationId("provider-A"), "task", Map.of("agent", "researcher", "objective", "child A work")),
                        new ModelToolCall(new ProviderToolCallCorrelationId("provider-B"), "task", Map.of("agent", "researcher", "objective", "child B work"))),
                        ModelFinishReason.TOOL_CALLS, ModelUsage.unpriced(1, 1));
                return ControlledModel.response(request, "lead-" + call, "LEAD-OUTPUT", List.of(), ModelFinishReason.STOP, ModelUsage.unpriced(1, 1));
            }
            String name = transcript.contains("child A work") ? "A" : "B";
            int phase = phases.computeIfAbsent(name, ignored -> new AtomicInteger()).incrementAndGet();
            if (phase == 2) {
                atSecondModel.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Child append gate expired");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Child append gate interrupted", interrupted);
                }
            }
            if (phase <= 2) return ControlledModel.call(request, "list_uploaded_files", Map.of());
            return ControlledModel.response(request, name + "-" + call, name + " FINAL", List.of(), ModelFinishReason.STOP, ModelUsage.unpriced(1, 1));
        });
        try (var gateway = new GatewayFixture(data, model, evidence)) {
            DelegationHttpFixture.configure(http, gateway.base, 1); // The public fixture supplies the model; no Provider connection is made.
            DelegationHttpFixture.seed(http, gateway.base, "researcher", 30, 5, List.of("list_uploaded_files"));
            assertThat(http.request(gateway.base, "POST", "/api/threads", "{\"thread_id\":\"interleaved\"}").statusCode()).isEqualTo(200);
            if (monitor != null) monitor.arm();
            HttpRequest request = HttpRequest.newBuilder(gateway.base.resolve("/api/threads/interleaved/runs/stream"))
                    .timeout(Duration.ofSeconds(40)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(DelegationHttpFixture.input(true).toString())).build();
            var response = http.client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                assertThat(response.statusCode()).isEqualTo(200);
                String location = response.headers().firstValue("content-location").orElseThrow();
                assertThat(atSecondModel.await(10, TimeUnit.SECONDS)).as("Both actual Children produced a durable first Tool result").isTrue();
                JsonNode state = http.get(gateway.base, "/api/threads/interleaved/state", "interleaved-running-state.json");
                var calls = DelegationHttpFixture.messages(state).stream().flatMap(message ->
                        java.util.stream.StreamSupport.stream(message.path("tool_calls").spliterator(), false))
                        .filter(tool -> "task".equals(tool.path("name").asText())).toList();
                assertThat(calls).hasSize(2);
                String a = calls.stream().filter(tool -> tool.path("args").path("prompt").asText().equals("child A work"))
                        .findFirst().orElseThrow().path("id").asText();
                String b = calls.stream().filter(tool -> tool.path("args").path("prompt").asText().equals("child B work"))
                        .findFirst().orElseThrow().path("id").asText();
                JsonNode prefix = http.get(gateway.base, events(location, a) + "&limit=2", "A-prefix.json");
                assertThat(prefix.size()).isEqualTo(2);
                long after = prefix.get(1).path("seq").asLong();
                var prefixFrames = SseEvents.readUntil(stream, events -> !events.named("messages").isEmpty()
                        && events.named("custom").stream()
                        .filter(event -> "task_running".equals(event.data().path("type").asText())).count() >= 4,
                        Duration.ofSeconds(10), evidence.resolve("interleaved-disconnected-prefix.sse"), true);
                Files.writeString(evidence.resolve("interleaved-disconnected-prefix.sse"), prefixFrames.body());
                assertThat(prefixFrames.lastEventId()).isPresent();
                assertThat(prefixFrames.lastEventId().orElseThrow()).isNotBlank();
                assertThat(prefixFrames.body().split("\\r?\\n\\r?\\n"))
                        .anySatisfy(frame -> assertThat(frame).contains("id: ", "event: messages",
                                "\"type\":\"tool\"", "\"name\":\"list_uploaded_files\""));
                stream.close();
                var reconnect = HttpRequest.newBuilder(gateway.base.resolve(location + "/stream"))
                        .timeout(Duration.ofSeconds(40)).header("Last-Event-ID", prefixFrames.lastEventId().orElseThrow()).GET().build();
                var rejoined = http.client.send(reconnect, HttpResponse.BodyHandlers.ofInputStream());
                String body;
                try (var joined = rejoined.body()) {
                    assertThat(rejoined.statusCode()).isEqualTo(200);
                    var backfill = SseEvents.readUntil(joined, events -> events.named("custom").stream()
                            .filter(event -> "task_running".equals(event.data().path("type").asText())).count() >= 4
                            || !events.named("gap").isEmpty(),
                            Duration.ofSeconds(10), evidence.resolve("interleaved-rejoined-prefix.sse"));
                    var gaps = SseEvents.read(backfill.body()).named("gap");
                    if (gaps.isEmpty()) {
                        release.countDown();
                        body = backfill.body() + GatewayFixture.drain(joined);
                    } else {
                        // Follow the existing frozen API client's one bounded durable-state recovery.
                        assertThat(gaps).hasSize(1);
                        JsonNode gap = gaps.getFirst().data();
                        assertThat(gap.path("code").asText()).isEqualTo("stream_replay_gap");
                        assertThat(gap.path("recovery").asText()).isEqualTo("reload_durable_state");
                        assertThat(gap.path("run_id").asText()).isEqualTo(location.substring(location.lastIndexOf('/') + 1));
                        assertThat(gap.path("requested_event_id").asText()).isEqualTo(prefixFrames.lastEventId().orElseThrow());
                        String latest = gap.path("latest_available_event_id").asText();
                        assertThat(latest).isNotBlank();
                        joined.close();
                        JsonNode recoveredState = http.get(gateway.base, "/api/threads/interleaved/state", "interleaved-gap-state.json");
                        var recoveredTasks = DelegationHttpFixture.messages(recoveredState).stream().flatMap(message ->
                                java.util.stream.StreamSupport.stream(message.path("tool_calls").spliterator(), false))
                                .filter(tool -> "task".equals(tool.path("name").asText()))
                                .map(tool -> tool.path("id").asText()).toList();
                        assertThat(recoveredTasks).containsExactlyInAnyOrder(a, b);
                        assertThat(http.get(gateway.base, events(location, a) + "&limit=2", "A-prefix-after-gap.json"))
                                .isEqualTo(prefix);
                        var recoveredRequest = HttpRequest.newBuilder(gateway.base.resolve(location + "/stream"))
                                .timeout(Duration.ofSeconds(40)).header("Last-Event-ID", latest).GET().build();
                        var recoveredResponse = http.client.send(recoveredRequest, HttpResponse.BodyHandlers.ofInputStream());
                        try (var recovered = recoveredResponse.body()) {
                            assertThat(recoveredResponse.statusCode()).isEqualTo(200);
                            assertThat(recoveredResponse.headers().firstValue("content-location")).contains(location);
                            var recoveredPrefix = SseEvents.readUntil(recovered, events -> events.named("custom").stream()
                                    .filter(event -> "task_running".equals(event.data().path("type").asText())).count() >= 4
                                    || !events.named("gap").isEmpty(), Duration.ofSeconds(10),
                                    evidence.resolve("interleaved-recovered-prefix.sse"));
                            assertThat(SseEvents.read(recoveredPrefix.body()).named("gap")).isEmpty();
                            release.countDown();
                            body = recoveredPrefix.body() + GatewayFixture.drain(recovered);
                        }
                    }
                }
                Files.writeString(evidence.resolve("interleaved.sse"), body);
                var custom = customs(body);
                JsonNode suffix = http.get(gateway.base, events(location, a) + "&after_seq=" + after, "A-suffix.json");
                JsonNode allA = http.get(gateway.base, events(location, a), "A-full.json");
                JsonNode allB = http.get(gateway.base, events(location, b), "B-full.json");
                assertThat(allA.size()).isEqualTo(5);
                assertThat(allB.size()).isEqualTo(5);
                assertThat(suffix.size()).isEqualTo(3);
                assertThat(GatewayFixture.JSON.createArrayNode().addAll((ArrayNode) prefix).addAll((ArrayNode) suffix)).isEqualTo(allA);
                assertLiveReplay(custom, allA, a);
                assertLiveReplay(custom, allB, b);
                saveConsumerFixture("frozen-consumer-A.json", custom, allA, a);
                saveConsumerFixture("frozen-consumer-B.json", custom, allB, b);
                for (int index = 0; index < 5; index++) {
                    assertThat(allA.get(index).path("content").path("message_index").asInt()).isEqualTo(index + 1);
                    assertThat(allB.get(index).path("content").path("message_index").asInt()).isEqualTo(index + 1);
                }
                assertThat(allA.get(4).path("content").path("text").asText()).isEqualTo("A FINAL");
                assertThat(allB.get(4).path("content").path("text").asText()).isEqualTo("B FINAL");
                assertThat(lead.get()).isEqualTo(3);
            } finally {
                release.countDown();
            }
        }
    }

    @Test void cancellingThreeActualChildrenClosesTwoWaitingRequestsWithoutInventingChildFrames() throws Exception {
        cancellingThreeActualChildrenClosesTwoWaitingRequests(null);
    }

    @Test void cancelledNativeToolAssistantKeepsTextWithTaskCallsThroughPageRestart() throws Exception {
        cancellingThreeActualChildrenClosesTwoWaitingRequests(null, "Parent is delegating now.");
    }

    /** Same real cancellation Run under the external-style directory watcher. */
    @Test void cancellingThreeActualChildrenUnderMonitoredDataDirectory() throws Exception {
        try (var monitor = DirectoryMonitor.open(data)) {
            cancellingThreeActualChildrenClosesTwoWaitingRequests(monitor);
        }
    }

    private void cancellingThreeActualChildrenClosesTwoWaitingRequests(DirectoryMonitor monitor) throws Exception {
        cancellingThreeActualChildrenClosesTwoWaitingRequests(monitor, "");
    }

    private void cancellingThreeActualChildrenClosesTwoWaitingRequests(DirectoryMonitor monitor, String nativeText) throws Exception {
        CountDownLatch running = new CountDownLatch(3), release = new CountDownLatch(1);
        AtomicInteger childModels = new AtomicInteger(), lead = new AtomicInteger();
        var model = new ControlledModel((request, call) -> {
            boolean child = request.messages().stream().anyMatch(message -> message.content().contains(DelegationHttpFixture.CHILD_MARKER));
            if (child) {
                childModels.incrementAndGet();
                running.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Cancellation gate expired");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Cancellation gate interrupted", interrupted);
                }
                return ControlledModel.stop(request);
            }
            if (lead.incrementAndGet() > 1) return ControlledModel.stop(request);
            var calls = java.util.stream.IntStream.range(0, 5).mapToObj(index -> new ModelToolCall(
                    new ProviderToolCallCorrelationId("provider-cancel-" + index), "task",
                    Map.of("agent", "researcher", "objective", "cancel child " + index))).toList();
            return ControlledModel.response(request, "parent-" + call, nativeText, calls, ModelFinishReason.TOOL_CALLS, ModelUsage.unpriced(1, 1));
        });
        List<String> tasks = null;
        List<String> starts = null;
        String location = null;
        JsonNode firstPage = null;
        try (var gateway = new GatewayFixture(data, model, evidence)) {
            DelegationHttpFixture.configure(http, gateway.base, 1);
            DelegationHttpFixture.seed(http, gateway.base, "researcher", 30, 3, List.of("list_uploaded_files"));
            assertThat(http.request(gateway.base, "POST", "/api/threads", "{\"thread_id\":\"cancel-queued\"}").statusCode()).isEqualTo(200);
            if (monitor != null) monitor.arm();
            var request = HttpRequest.newBuilder(gateway.base.resolve("/api/threads/cancel-queued/runs/stream"))
                    .timeout(Duration.ofSeconds(40)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(DelegationHttpFixture.input(true).toString())).build();
            var response = http.client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                assertThat(response.statusCode()).isEqualTo(200);
                location = response.headers().firstValue("content-location").orElseThrow();
                assertThat(running.await(10, TimeUnit.SECONDS)).as("Three actual Children reached their public model calls").isTrue();
                JsonNode before = http.get(gateway.base, "/api/threads/cancel-queued/state", "cancel-before-state.json");
                tasks = DelegationHttpFixture.messages(before).stream().flatMap(message ->
                        java.util.stream.StreamSupport.stream(message.path("tool_calls").spliterator(), false))
                        .filter(tool -> "task".equals(tool.path("name").asText())).map(tool -> tool.path("id").asText()).toList();
                assertThat(tasks).hasSize(5).doesNotHaveDuplicates();
                Instant cancelRequestedAt = Instant.now();
                assertThat(http.request(gateway.base, "POST", location + "/cancel?action=interrupt&wait=0", "", "cancel-request.http").statusCode()).isEqualTo(202);
                Instant releaseGateOpenedAt = Instant.now();
                release.countDown();
                String body = GatewayFixture.drain(stream);
                Files.writeString(evidence.resolve("cancel-queued.sse"), body);
                List<JsonNode> custom = cancelCustomOrCollectFailure(gateway.base, location, tasks, body,
                        cancelRequestedAt, releaseGateOpenedAt);
                starts = custom.stream().filter(event -> "task_started".equals(event.path("type").asText()))
                        .map(event -> event.path("task_id").asText()).toList();
                var terminals = custom.stream().filter(event -> "task_cancelled".equals(event.path("type").asText()))
                        .map(event -> event.path("task_id").asText()).toList();
                assertThat(starts).hasSize(3).doesNotHaveDuplicates();
                assertThat(terminals).containsExactlyInAnyOrderElementsOf(starts);
                for (JsonNode event : custom) {
                    assertThat(event.path("task_id").asText()).isIn(starts);
                    assertThat(event.has("usage")).as("Cancelled in-flight model use is unknown").isFalse();
                }
                assertThat(childModels).hasValue(3);
                JsonNode run = http.get(gateway.base, location, "cancel-terminal-run.json");
                assertThat(run.path("diagnostics").path("sdk_status").asText()).isEqualTo("CANCELLED");
                JsonNode after = http.get(gateway.base, "/api/threads/cancel-queued/state", "cancel-refreshed-state.json");
                List<JsonNode> results = new ArrayList<>();
                for (JsonNode message : DelegationHttpFixture.messages(after)) {
                    if (tasks.contains(message.path("tool_call_id").asText())) results.add(message);
                }
                assertThat(results).hasSize(5);
                assertThat(results.stream().map(message -> message.path("tool_call_id").asText())).containsExactlyInAnyOrderElementsOf(tasks);
                assertThat(results).allSatisfy(message -> assertThat(message.path("additional_kwargs").path("subagent_status").asText()).isEqualTo("cancelled"));
                for (String waiting : tasks) {
                    if (starts.contains(waiting)) continue;
                    assertThat(http.get(gateway.base, events(location, waiting), "waiting-" + tasks.indexOf(waiting) + ".json")).isEmpty();
                }
                firstPage = assertCancelledDelegationPage(gateway.base, tasks, "cancel-message-page");
                if (!nativeText.isEmpty()) assertNativeToolText(firstPage, nativeText);
            } finally {
                release.countDown();
            }
        }
        // The frozen UI reloads through messages/page, so the cancelled Run's Tool round must survive
        // a real process restart where only durable facts remain and the in-memory Runs are gone.
        try (var restarted = new GatewayFixture(data, model, evidence.resolve("restart-page"))) {
            JsonNode restartedPage = assertCancelledDelegationPage(restarted.base, tasks, "restart-cancel-message-page");
            if (!nativeText.isEmpty()) assertNativeToolText(restartedPage, nativeText);
            assertThat(restartedPage).as("durable page is byte-identical after restart").isEqualTo(firstPage);
            for (String waiting : tasks) {
                if (starts.contains(waiting)) continue;
                assertThat(http.get(restarted.base, events(location, waiting), "restart-waiting-" + tasks.indexOf(waiting) + ".json"))
                        .as("a never-admitted Child still shows no steps after restart").isEmpty();
            }
        }
    }

    private static void assertNativeToolText(JsonNode rows, String nativeText) {
        List<JsonNode> contents = java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                .map(row -> row.path("content")).toList();
        List<JsonNode> narrations = contents.stream()
                .filter(message -> "ai".equals(message.path("type").asText())
                        && nativeText.equals(message.path("content").asText())).toList();
        assertThat(narrations).as("Native narration survives once under its own canonical identity").hasSize(1);
        JsonNode narration = narrations.getFirst();
        assertThat(narration.path("tool_calls")).isEmpty();
        assertThat(narration.path("id").asText()).startsWith(narration.path("run_id").asText() + "-ai-");
        List<JsonNode> requests = contents.stream().filter(message -> !message.path("tool_calls").isEmpty()).toList();
        assertThat(requests).hasSize(1);
        JsonNode request = requests.getFirst();
        assertThat(request.path("content").asText()).isEmpty();
        assertThat(request.path("id")).isNotEqualTo(narration.path("id"));
        assertThat(contents.indexOf(narration)).isLessThan(contents.indexOf(request));
        for (JsonNode result : contents) {
            if ("tool".equals(result.path("type").asText()))
                assertThat(contents.indexOf(request)).isLessThan(contents.indexOf(result));
        }
    }

    /**
     * Reads the cancelled Run's page through production HTTP and pins the frozen-UI contract: one AI
     * message carrying the whole Tool call list, one cancelled result per actual task, stable
     * ascending unique seq, and a backward cursor that loses or duplicates nothing.
     */
    private JsonNode assertCancelledDelegationPage(URI base, List<String> tasks, String evidenceName) throws Exception {
        JsonNode page = GatewayFixture.JSON.readTree(
                http.request(base, "GET", "/api/threads/cancel-queued/messages/page?limit=50", "", evidenceName + ".http").body());
        JsonNode rows = page.path("data");
        Files.writeString(evidence.resolve(evidenceName + ".json"), rows.toPrettyString());
        List<JsonNode> requestRows = new ArrayList<>();
        for (JsonNode row : rows) {
            JsonNode content = row.path("content");
            if (!"ai".equals(content.path("type").asText())) continue;
            List<JsonNode> calls = new ArrayList<>();
            content.path("tool_calls").forEach(calls::add);
            if (calls.stream().anyMatch(call -> "task".equals(call.path("name").asText()))) requestRows.add(row);
        }
        assertThat(requestRows).as(evidenceName + ": one AI message carries the Run's Tool call list").hasSize(1);
        assertThat(requestRows.getFirst().path("content").path("tool_calls")).hasSize(5);
        assertThat(requestRows.getFirst().path("content").path("tool_calls").findValuesAsText("name"))
                .as(evidenceName + ": the AI message keeps every projected task call").containsOnly("task");
        List<JsonNode> resultRows = new ArrayList<>();
        for (JsonNode row : rows) {
            if (tasks.contains(row.path("content").path("tool_call_id").asText())) resultRows.add(row);
        }
        assertThat(resultRows).as(evidenceName + ": every actual task has a result row").hasSize(5);
        assertThat(resultRows.stream().map(row -> row.path("content").path("tool_call_id").asText()).toList())
                .containsExactlyInAnyOrderElementsOf(tasks);
        assertThat(resultRows).allSatisfy(row -> assertThat(
                        row.path("content").path("additional_kwargs").path("subagent_status").asText())
                .isEqualTo("cancelled"));
        List<Long> seqs = new ArrayList<>();
        for (JsonNode row : rows) seqs.add(row.path("seq").asLong());
        assertThat(seqs).as(evidenceName + ": seq is unique").doesNotHaveDuplicates();
        List<Long> ascending = new ArrayList<>(seqs);
        java.util.Collections.sort(ascending);
        assertThat(seqs).as(evidenceName + ": seq is ascending").isEqualTo(ascending);
        List<String> backwards = new ArrayList<>();
        Long before = null;
        for (int index = rows.size() - 1; index >= 0; index--) {
            JsonNode singlePage = GatewayFixture.JSON.readTree(http.request(
                    base,
                    "GET",
                    "/api/threads/cancel-queued/messages/page?limit=1"
                            + (before == null ? "" : "&before_seq=" + before),
                    "",
                    evidenceName + "-cursor-" + index + ".http").body());
            JsonNode single = singlePage.path("data");
            assertThat(single).as(evidenceName + ": cursor page " + index).hasSize(1);
            assertThat(single.get(0)).as(evidenceName + ": cursor row " + index).isEqualTo(rows.get(index));
            assertThat(singlePage.path("has_more").asBoolean()).isEqualTo(index > 0);
            before = single.get(0).path("seq").asLong();
            backwards.add(single.get(0).path("content").path("id").asText());
        }
        java.util.Collections.reverse(backwards);
        assertThat(backwards).containsExactlyElementsOf(java.util.stream.StreamSupport
                .stream(rows.spliterator(), false)
                .map(row -> row.path("content").path("id").asText())
                .toList());
        return rows;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void childFailureAndTimeoutAgreeWithAuthoritativeTaskResultsAndOmitUnknownUsage(boolean timeout) throws Exception {
        AtomicInteger lead = new AtomicInteger();
        var model = new ControlledModel((request, call) -> {
            if (request.messages().stream().anyMatch(message -> message.content().contains(DelegationHttpFixture.CHILD_MARKER))) {
                if (!timeout) throw new IllegalStateException("Synthetic Child model failure");
                try {
                    new CountDownLatch(1).await(3, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Synthetic Child timed out", interrupted);
                }
                return ControlledModel.stop(request);
            }
            if (lead.incrementAndGet() == 1) return ControlledModel.call(request, "task", Map.of("agent", "researcher", "objective", "Bounded failure test"));
            return ControlledModel.response(request, "lead-" + call, "LEAD-OUTPUT", List.of(), ModelFinishReason.STOP, ModelUsage.unpriced(1, 1));
        });
        try (var gateway = new GatewayFixture(data, model, evidence)) {
            DelegationHttpFixture.configure(http, gateway.base, 1);
            DelegationHttpFixture.seed(http, gateway.base, "researcher", timeout ? 1 : 30, 3, List.of("list_uploaded_files"));
            String location = DelegationHttpFixture.execute(http, gateway.base, "failed-child", DelegationHttpFixture.input(true));
            JsonNode state = http.get(gateway.base, "/api/threads/failed-child/state", "failed-child-state.json");
            String taskId = onlyTask(state).path("id").asText();
            assertParentHistory(state, taskId, timeout ? "timed_out" : "failed");
            var custom = customs(Files.readString(evidence.resolve("failed-child.sse"))).stream()
                    .filter(event -> taskId.equals(event.path("task_id").asText())).toList();
            assertThat(custom.stream().filter(event -> "task_started".equals(event.path("type").asText()))).hasSize(1);
            var terminal = custom.stream().filter(event -> (timeout ? "task_timed_out" : "task_failed").equals(event.path("type").asText())).toList();
            assertThat(terminal).hasSize(1);
            assertThat(terminal.getFirst().path("error").asText()).isNotBlank();
            assertThat(terminal.getFirst().has("usage")).isFalse();
            assertThat(http.get(gateway.base, events(location, taskId), "failed-child-steps.json")).isEmpty();
        }
    }

    private void assertValidationAndOwnership(URI base, String location, String taskId) throws Exception {
        for (String suffix : List.of("", "?task_id=", "?task_id=" + taskId + "&limit=0", "?task_id=" + taskId + "&limit=501",
                "?task_id=" + taskId + "&after_seq=-1", "?task_id=" + taskId + "&after_seq=1.5",
                "?task_id=" + taskId + "&after_seq=9007199254740992", "?task_id=" + taskId + "&event_types=run.started")) {
            assertThat(http.request(base, "GET", location + "/events" + suffix, "").statusCode()).as(suffix).isEqualTo(422);
        }
        assertThat(http.get(base, events(location, "unknown-task"), "unknown-task.json")).isEmpty();
        assertThat(http.request(base, "POST", "/api/threads", "{\"thread_id\":\"other-existing\"}").statusCode()).isEqualTo(200);
        String other = location.replace("/threads/steps/", "/threads/other-existing/");
        assertThat(http.request(base, "GET", events(other, taskId), "", "cross-thread.http").statusCode()).isEqualTo(404);
        assertThat(http.request(base, "GET", "/api/threads/missing/runs/missing/events?task_id=" + taskId, "").statusCode()).isEqualTo(404);
    }

    static JsonNode onlyTask(JsonNode state) {
        List<JsonNode> tasks = DelegationHttpFixture.messages(state).stream().flatMap(message ->
                java.util.stream.StreamSupport.stream(message.path("tool_calls").spliterator(), false))
                .filter(call -> "task".equals(call.path("name").asText())).toList();
        assertThat(tasks).hasSize(1);
        return tasks.getFirst();
    }

    static void assertParentHistory(JsonNode state, String taskId, String status) {
        var messages = DelegationHttpFixture.messages(state);
        assertThat(messages).noneMatch(message -> message.path("content").asText().equals("CHILD-OUTPUT"));
        assertThat(messages).anyMatch(message -> message.path("content").asText().equals("LEAD-OUTPUT"));
        assertThat(messages).noneMatch(message -> message.path("type").asText().startsWith("subagent."));
        assertThat(messages.stream().flatMap(message -> java.util.stream.StreamSupport.stream(message.path("tool_calls").spliterator(), false))
                .map(call -> call.path("name").asText())).containsOnly("task");
        var result = messages.stream().filter(message -> taskId.equals(message.path("tool_call_id").asText())).toList();
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().path("additional_kwargs").path("subagent_status").asText()).isEqualTo(status);
    }

    static List<JsonNode> customs(String body) {
        assertThat(SseEvents.read(body).named("error")).isEmpty();
        assertThat(SseEvents.read(body).named("end")).hasSize(1);
        return SseEvents.read(body).named("custom").stream().map(SseEvents.Event::data).toList();
    }

    static void assertSteps(JsonNode replay, String taskId) {
        assertThat(replay.isArray()).isTrue();
        assertThat(replay.size()).isEqualTo(3);
        long previous = 0;
        for (int index = 0; index < replay.size(); index++) {
            JsonNode event = replay.get(index), step = event.path("content");
            assertThat(event.path("event_type").asText()).isEqualTo("subagent.step");
            assertThat(event.path("seq").asLong()).isGreaterThan(previous);
            previous = event.path("seq").asLong();
            assertThat(step.path("message_index").asInt()).isEqualTo(index + 1);
            assertThat(step.path("task_id").asText()).isEqualTo(taskId);
            assertThat(step.path("kind").asText()).isEqualTo(index == 1 ? "tool" : "ai");
        }
        assertThat(replay.get(0).path("content").path("tool_calls").get(0).path("name").asText()).isEqualTo("list_uploaded_files");
        assertThat(replay.get(1).path("content").path("tool_name").asText()).isEqualTo("list_uploaded_files");
        assertThat(replay.get(2).path("content").path("text").asText()).isEqualTo("CHILD-OUTPUT");
    }

    static void assertLiveReplay(List<JsonNode> custom, JsonNode replay, String taskId) {
        var live = custom.stream().filter(event -> "task_running".equals(event.path("type").asText())
                && taskId.equals(event.path("task_id").asText())).toList();
        assertThat(live).hasSize(replay.size());
        for (int index = 0; index < live.size(); index++) {
            JsonNode actual = live.get(index), stored = replay.get(index).path("content"), message = actual.path("message");
            assertThat(actual.path("message_index")).isEqualTo(stored.path("message_index"));
            assertThat(message.path("type")).isEqualTo(stored.path("kind"));
            assertThat(message.path("content")).isEqualTo(stored.path("text"));
            assertThat(message.path("truncated")).isEqualTo(stored.path("truncated"));
            assertThat(message.path("tool_calls")).isEqualTo(stored.path("tool_calls"));
            assertThat(message.path("name")).isEqualTo(stored.path("tool_name"));
        }
    }

    static String events(String location, String taskId) { return location + "/events?event_types=subagent.step&task_id=" + taskId; }

    private void saveConsumerFixture(String filename, List<JsonNode> custom, JsonNode replay, String taskId) throws Exception {
        http.save(filename, Map.of("taskId", taskId, "live", custom.stream().filter(event ->
                "task_running".equals(event.path("type").asText()) && taskId.equals(event.path("task_id").asText())).toList(), "replay", replay));
    }

    private static Map<String, String> environment() {
        return Map.of("HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W08_PROJECTION_TEST_KEY",
                "W08_PROJECTION_TEST_KEY", Base64.getEncoder().encodeToString(new byte[32]));
    }

    /**
     * Preserves the original "no error frame" assertion while capturing, only on failure, the public
     * Parent/Child terminal facts needed to settle whether the SDK cancellation ordering is at fault.
     */
    private List<JsonNode> cancelCustomOrCollectFailure(URI base, String location, List<String> taskOrder,
            String body, Instant cancelRequestedAt, Instant releaseGateOpenedAt) throws Exception {
        if (!SseEvents.read(body).named("error").isEmpty()) {
            Path report = null;
            String collectionFailure = null;
            try {
                report = writeCancelFailureEvidence(base, location, taskOrder, body, cancelRequestedAt, releaseGateOpenedAt);
            } catch (Exception collection) {
                collectionFailure = collection.getClass().getSimpleName() + ": " + collection.getMessage();
            }
            try {
                customs(body);
            } catch (AssertionError assertion) {
                String suffix = report != null
                        ? System.lineSeparator() + "Evidence file: " + report.toAbsolutePath()
                        : System.lineSeparator() + "Evidence collection failed: " + collectionFailure;
                throw new AssertionError(assertion.getMessage() + suffix, assertion);
            }
            throw new AssertionError(report != null
                    ? "Error frame present without an assertion failure; evidence: " + report.toAbsolutePath()
                    : "Error frame present without an assertion failure; evidence collection failed: " + collectionFailure);
        }
        return customs(body).stream().filter(event -> event.path("type").asText().startsWith("task_")).toList();
    }

    private Path writeCancelFailureEvidence(URI base, String location, List<String> taskOrder, String body,
            Instant cancelRequestedAt, Instant releaseGateOpenedAt) throws Exception {
        var report = new LinkedHashMap<String, Object>();
        report.put("evidence_kind", "cancel_error_frame_diagnostics");
        report.put("thread_id", "cancel-queued");
        report.put("run_location", location);
        report.put("cancel_requested_at", cancelRequestedAt.toString());
        report.put("release_gate_opened_at", releaseGateOpenedAt.toString());

        var parent = new LinkedHashMap<String, Object>();
        JsonNode run = null;
        var runResponse = http.request(base, "GET", location, null, "cancel-failure-run.json");
        parent.put("http_status", runResponse.statusCode());
        if (runResponse.statusCode() == 200) {
            run = GatewayFixture.JSON.readTree(runResponse.body());
            parent.put("status", textField(run, "status"));
            parent.put("stop_reason", textField(run, "stop_reason"));
            JsonNode diagnostics = run.path("diagnostics");
            for (String field : List.of("sdk_status", "stop_reason", "error_code", "diagnostic_id",
                    "incomplete_reason", "last_updated_at")) {
                parent.put("diagnostics_" + field, textField(diagnostics, field));
            }
            parent.put("diagnostics_projection_complete", diagnostics.path("projection_complete").asBoolean(false));
        } else {
            parent.put("unavailable", "GET " + location + " -> HTTP " + runResponse.statusCode());
        }
        var messages = new ArrayList<String>();
        for (var event : SseEvents.read(body).named("error")) messages.add(textField(event.data(), "message"));
        parent.put("error_frame_messages", messages);
        parent.put("error_message_source", "captured Parent SSE error frame; run diagnostics exposes wire code and diagnostic_id, not message text");
        report.put("parent_run", parent);

        var byToolCallId = new LinkedHashMap<String, JsonNode>();
        JsonNode toolCalls = run == null ? null : run.path("diagnostics").path("tool_calls");
        boolean toolCallsAvailable = toolCalls != null && toolCalls.isArray();
        if (toolCallsAvailable) {
            for (JsonNode call : toolCalls) {
                if ("task".equals(call.path("name").asText())) byToolCallId.put(call.path("tool_call_id").asText(), call);
            }
        }
        var taskFacts = new ArrayList<Object>();
        for (int index = 0; index < taskOrder.size(); index++) {
            var fact = new LinkedHashMap<String, Object>();
            fact.put("order", index + 1);
            fact.put("task_tool_call_id", taskOrder.get(index));
            JsonNode call = byToolCallId.get(taskOrder.get(index));
            if (call == null) {
                fact.put("parent_task_status", null);
                fact.put("parent_task_error_code", null);
                fact.put("unavailable", toolCallsAvailable
                        ? "not present in run diagnostics tool_calls"
                        : "run diagnostics tool_calls unavailable");
            } else {
                fact.put("parent_task_status", textField(call, "status"));
                fact.put("parent_task_error_code", textField(call, "error_code"));
                fact.put("parent_task_result_successful", call.path("result_successful").isBoolean()
                        ? call.path("result_successful").asBoolean() : null);
                fact.put("parent_task_requested_at", textField(call, "requested_at"));
                fact.put("parent_task_completed_at", textField(call, "completed_at"));
            }
            taskFacts.add(fact);
        }
        report.put("parent_task_tool_calls_in_model_order", taskFacts);

        var custom = SseEvents.read(body).named("custom").stream().map(SseEvents.Event::data).toList();
        var childFacts = new ArrayList<Object>();
        for (int index = 0; index < taskOrder.size(); index++) {
            String taskId = taskOrder.get(index);
            var fact = new LinkedHashMap<String, Object>();
            fact.put("order", index + 1);
            fact.put("task_tool_call_id", taskId);
            boolean created = custom.stream().anyMatch(event -> taskId.equals(textField(event, "task_id"))
                    && "task_started".equals(textField(event, "type")));
            fact.put("created", created);
            if (!created) {
                fact.put("child_run_status", "not_created");
                fact.put("note", "no task_started projection for this request; not treated as an actual Child Run");
            } else {
                var terminal = custom.stream().filter(event -> taskId.equals(textField(event, "task_id"))
                        && terminalChildType(textField(event, "type"))).findFirst();
                fact.put("child_run_terminal_event", terminal.map(event -> textField(event, "type")).orElse(null));
                fact.put("child_run_status", terminal.map(event -> childRunStatus(textField(event, "type"))).orElse(null));
                fact.put("child_run_termination_reason", terminal.map(event -> textField(event, "error")).orElse(null));
                if (terminal.isEmpty()) fact.put("note", "no Child terminal projection in the captured stream");
            }
            childFacts.add(fact);
        }
        report.put("actual_child_runs", childFacts);
        report.put("child_run_note", "Child status/reason is taken only from the public Parent custom lifecycle projection; "
                + "the Child's own AgentRunSnapshot is not exposed by any public HTTP endpoint or public bridge accessor.");

        var stateFacts = new ArrayList<Object>();
        var stateResponse = http.request(base, "GET", "/api/threads/cancel-queued/state", null, "cancel-failure-state.json");
        if (stateResponse.statusCode() == 200) {
            JsonNode state = GatewayFixture.JSON.readTree(stateResponse.body());
            for (int index = 0; index < taskOrder.size(); index++) {
                String taskId = taskOrder.get(index);
                var fact = new LinkedHashMap<String, Object>();
                fact.put("order", index + 1);
                fact.put("task_tool_call_id", taskId);
                JsonNode message = DelegationHttpFixture.messages(state).stream()
                        .filter(candidate -> taskId.equals(candidate.path("tool_call_id").asText())).findFirst().orElse(null);
                if (message == null) {
                    fact.put("unavailable", "no tool result message in refreshed state");
                } else {
                    JsonNode kwargs = message.path("additional_kwargs");
                    for (String field : List.of("subagent_status", "subagent_error", "subagent_stop_reason",
                            "subagent_model_name")) {
                        fact.put("state_" + field, textField(kwargs, field));
                    }
                }
                stateFacts.add(fact);
            }
        } else {
            stateFacts.add(Map.of("unavailable", "GET state -> HTTP " + stateResponse.statusCode()));
        }
        report.put("child_terminal_status_state_projection", stateFacts);

        report.put("parent_run_stream_last_frames", tailFrames(body, 16));
        try {
            var replay = http.request(base, "GET", location + "/stream", null, "cancel-failure-replay.sse");
            if (replay.statusCode() == 200) {
                var gaps = SseEvents.read(replay.body()).named("gap");
                if (gaps.isEmpty()) {
                    report.put("parent_persisted_event_stream_last_frames", tailFrames(replay.body(), 16));
                } else {
                    var gap = gaps.getFirst().data();
                    var gapFacts = new LinkedHashMap<String, Object>();
                    for (String field : List.of("code", "recovery", "requested_event_id",
                            "earliest_available_event_id", "latest_available_event_id")) {
                        gapFacts.put(field, textField(gap, field));
                    }
                    report.put("parent_persisted_event_stream_gap", gapFacts);
                    report.put("parent_persisted_event_stream_last_frames", tailFrames(body, 16));
                    report.put("parent_persisted_event_stream_note",
                            "A cursorless durable replay returned a replay gap (the in-memory projection window no longer "
                                    + "holds sequence 0), so the durable tail is represented by the captured run stream frames; "
                                    + "the SDK's raw event store is not exposed by any public HTTP endpoint.");
                }
            } else {
                report.put("parent_persisted_event_stream_last_frames",
                        Map.of("unavailable", "GET " + location + "/stream -> HTTP " + replay.statusCode()));
            }
        } catch (Exception replayFailure) {
            report.put("parent_persisted_event_stream_last_frames",
                    Map.of("unavailable", "durable replay failed: " + replayFailure.getClass().getSimpleName()));
        }
        report.put("event_frame_note", "Frames carry only event type, SSE id/sequence and any server timestamp field found "
                + "in the frame data; no prompt, model content or credentials are copied.");
        report.put("evidence_source", "public HTTP: GET run, GET thread state, GET run /stream, plus the captured Parent SSE body");

        Path path = evidence.resolve("cancel-failure-evidence.json");
        http.save("cancel-failure-evidence.json", report);
        return path;
    }

    private static boolean terminalChildType(String type) {
        return List.of("task_completed", "task_cancelled", "task_failed", "task_timed_out").contains(type);
    }

    private static String childRunStatus(String terminalEventType) {
        return switch (terminalEventType) {
            case "task_completed" -> "COMPLETED";
            case "task_cancelled" -> "CANCELLED";
            case "task_timed_out" -> "TIMEOUT";
            default -> "FAILED";
        };
    }

    private static List<Map<String, Object>> tailFrames(String body, int limit) {
        var all = new ArrayList<Map<String, Object>>();
        String event = "message";
        String id = null;
        var data = new StringBuilder();
        for (String line : (body + "\n\n").split("\r\n|\n|\r", -1)) {
            if (line.isEmpty()) {
                if (data.length() > 0 || id != null) {
                    var frame = new LinkedHashMap<String, Object>();
                    frame.put("event", event);
                    frame.put("id", id);
                    frame.put("timestamp", frameTimestamp(data.toString()));
                    all.add(frame);
                    data.setLength(0);
                    id = null;
                    event = "message";
                }
            } else if (line.startsWith("event:")) {
                event = line.substring("event:".length()).strip();
            } else if (line.startsWith("id:")) {
                id = line.substring("id:".length()).strip();
            } else if (line.startsWith("data:")) {
                data.append(line.substring("data:".length()).strip());
            }
        }
        return List.copyOf(all.subList(Math.max(0, all.size() - limit), all.size()));
    }

    private static String frameTimestamp(String raw) {
        if (raw.isBlank()) return null;
        try {
            JsonNode data = GatewayFixture.JSON.readTree(raw);
            for (String field : List.of("timestamp", "created_at", "at", "time", "updated_at")) {
                if (data.hasNonNull(field)) return data.get(field).asText();
            }
        } catch (Exception notJson) {
            return null;
        }
        return null;
    }

    private static String textField(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}

package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.superharness.server.testsupport.ControlledModel;
import io.haifa.agent.superharness.server.testsupport.DelegationHttpFixture;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real Gateway and published SDK: settled Tool rounds survive the frozen page/reload contract. */
@Timeout(60)
class W08CompletedDelegationPageTest {
    @Test
    void postSteerTaskRoundStaysAfterItsAcceptedHumanTurnAcrossPageAndRestart() throws Exception {
        Path data = GatewayFixture.dataDirectory();
        Path evidence = GatewayFixture.evidenceDirectory();
        CountDownLatch beforeSteer = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger parent = new AtomicInteger();
        var model = new ControlledModel((request, call) -> {
            if (request.messages().stream().anyMatch(message ->
                    message.content().contains(DelegationHttpFixture.CHILD_MARKER))) {
                return ControlledModel.response(request, "child-" + call, "CHILD-OUTPUT", List.of(),
                        ModelFinishReason.STOP, ModelUsage.unpriced(1, 1));
            }
            int phase = parent.incrementAndGet();
            if (phase == 2) {
                beforeSteer.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Steer model gate expired");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Steer model gate interrupted", interrupted);
                }
                return ControlledModel.response(request, "parent-" + call, "DEFERRED-ANSWER", List.of(),
                        ModelFinishReason.STOP, ModelUsage.unpriced(1, 1));
            }
            if (phase == 1 || phase == 3) {
                var task = new ModelToolCall(new ProviderToolCallCorrelationId("provider-steer-" + phase),
                        "task", Map.of("agent", "researcher", "objective", "Task phase " + phase));
                return ControlledModel.response(request, "parent-" + call, "", List.of(task),
                        ModelFinishReason.TOOL_CALLS, ModelUsage.unpriced(1, 1));
            }
            return ControlledModel.response(request, "parent-" + call, "PARENT-FINAL", List.of(),
                    ModelFinishReason.STOP, ModelUsage.unpriced(1, 1));
        });
        String thread = "completed-page-steer";
        JsonNode original;
        try (var http = new GatewayFixture.HttpEvidence(evidence, Duration.ofSeconds(40))) {
            try (var gateway = new GatewayFixture(data, model, evidence)) {
                DelegationHttpFixture.configure(http, gateway.base, 1);
                DelegationHttpFixture.seed(http, gateway.base, "researcher", 30, 3, List.of());
                assertThat(http.request(gateway.base, "POST", "/api/threads", "{\"thread_id\":\"" + thread + "\"}")
                        .statusCode()).isEqualTo(200);
                var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                HttpResponse<InputStream> started = client.send(HttpRequest.newBuilder()
                        .uri(gateway.base.resolve("/api/threads/" + thread + "/runs/stream"))
                        .timeout(Duration.ofSeconds(40)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(DelegationHttpFixture.input(true).toString()))
                        .build(), HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream first = started.body();
                        var drainExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
                    // Drain the creator concurrently so TCP backpressure cannot stall the live
                    // observer loop; product code also refuses to let Steer/join bulk sync slide the
                    // shared retention window past that observer's delivered cursor.
                    CompletableFuture<String> creatorDrain =
                            CompletableFuture.supplyAsync(() -> readSseUnbounded(first), drainExecutor);
                    assertThat(beforeSteer.await(15, TimeUnit.SECONDS)).isTrue();
                    HttpResponse<InputStream> steered = client.send(HttpRequest.newBuilder()
                            .uri(gateway.base.resolve("/api/threads/" + thread + "/runs/stream"))
                            .timeout(Duration.ofSeconds(40)).header("Content-Type", "application/json")
                            .header("X-Haifa-Run-Input", "steer")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"input\":{\"messages\":[{\"id\":\"page-steer\","
                                    + "\"type\":\"human\",\"content\":\"STEER-NATIVE-ORDER\"}]},\"on_disconnect\":\"continue\"}"))
                            .build(), HttpResponse.BodyHandlers.ofInputStream());
                    try (InputStream second = steered.body()) {
                        assertThat(steered.statusCode()).isEqualTo(200);
                        assertThat(steered.headers().firstValue("content-location"))
                                .isEqualTo(started.headers().firstValue("content-location"));
                        // The completed first Child has advanced the bounded replay window. The
                        // admission stream reports its actual gap; recover with that public cursor
                        // before releasing the model, without submitting a second Steer.
                        String admission = readSseBounded(second);
                        Files.writeString(evidence.resolve("steer-admission-gap.sse"), admission);
                        assertThat(admission).contains("event: gap");
                        String gapData = admission.lines().filter(line -> line.startsWith("data: "))
                                .findFirst().orElseThrow().substring(6);
                        JsonNode gap = GatewayFixture.JSON.readTree(gapData);
                        String cursor = gap.path("latest_available_event_id").asText();
                        assertThat(cursor).isNotBlank();
                        assertThat(release.getCount()).isEqualTo(1);
                        String location = started.headers().firstValue("content-location").orElseThrow();
                        HttpResponse<InputStream> recovered = client.send(HttpRequest.newBuilder()
                                .uri(gateway.base.resolve(location + "/stream"))
                                .timeout(Duration.ofSeconds(40)).header("Last-Event-ID", cursor).GET().build(),
                                HttpResponse.BodyHandlers.ofInputStream());
                        try (InputStream joined = recovered.body()) {
                            assertThat(recovered.statusCode()).isEqualTo(200);
                            release.countDown();
                            String secondText = readSseBounded(joined);
                            String firstText = creatorDrain.get(40, TimeUnit.SECONDS);
                            Files.writeString(evidence.resolve("steer-creator.sse"), firstText);
                            Files.writeString(evidence.resolve("steer-recovered-join.sse"), secondText);
                            assertThat(firstText).contains("event: end");
                            assertThat(secondText).contains("event: end");
                        }
                    }
                } finally { release.countDown(); }
                original = assertPageAndCursors(http, gateway.base, thread, "steer-before-restart");
                int steerIndex = -1;
                List<Integer> taskIndices = new ArrayList<>();
                for (int index = 0; index < original.size(); index++) {
                    JsonNode message = original.get(index).path("content");
                    if ("STEER-NATIVE-ORDER".equals(message.path("content").asText())) steerIndex = index;
                    if (!message.path("tool_calls").isEmpty()) taskIndices.add(index);
                }
                assertThat(steerIndex).isNotNegative();
                assertThat(taskIndices).hasSize(2);
                assertThat(taskIndices.get(0)).isLessThan(steerIndex);
                assertThat(taskIndices.get(1)).as("task generated after the accepted Steer remains after it")
                        .isGreaterThan(steerIndex);
            }
            try (var reopened = new GatewayFixture(data, model, evidence)) {
                assertThat(assertPageAndCursors(http, reopened.base, thread, "steer-after-restart"))
                        .isEqualTo(original);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void twoSuccessfulTaskRoundsRetainExactNativeMessagesAndBackwardPagesAfterRestart(boolean withText)
            throws Exception {
        Path data = GatewayFixture.dataDirectory();
        Path evidence = GatewayFixture.evidenceDirectory();
        AtomicInteger parent = new AtomicInteger();
        var model = new ControlledModel((request, call) -> {
            if (request.messages().stream().anyMatch(message ->
                    message.content().contains(DelegationHttpFixture.CHILD_MARKER))) {
                return ControlledModel.response(request, "child-" + call, "CHILD-OUTPUT", List.of(),
                        ModelFinishReason.STOP, ModelUsage.unpriced(1, 1));
            }
            int round = parent.incrementAndGet();
            if (round <= 2) {
                var task = new ModelToolCall(new ProviderToolCallCorrelationId("provider-task-" + round),
                        "task", Map.of("agent", "researcher", "objective", "Bounded task round " + round));
                return ControlledModel.response(request, "parent-" + call,
                        withText ? "Delegating round " + round + "." : "", List.of(task),
                        ModelFinishReason.TOOL_CALLS, ModelUsage.unpriced(1, 1));
            }
            return ControlledModel.response(request, "parent-" + call, "PARENT-FINAL", List.of(),
                    ModelFinishReason.STOP, ModelUsage.unpriced(1, 1));
        });
        String thread = "completed-page-" + withText;
        JsonNode original;
        try (var http = new GatewayFixture.HttpEvidence(evidence, Duration.ofSeconds(40))) {
            try (var gateway = new GatewayFixture(data, model, evidence)) {
                DelegationHttpFixture.configure(http, gateway.base, 1);
                DelegationHttpFixture.seed(http, gateway.base, "researcher", 30, 3, List.of());
                DelegationHttpFixture.execute(http, gateway.base, thread, DelegationHttpFixture.input(true));
                JsonNode state = http.get(gateway.base, "/api/threads/" + thread + "/state", "settled-state.json");
                original = assertPageAndCursors(http, gateway.base, thread, "before-restart");
                assertThat(original).hasSize(withText ? 8 : 6);
                List<JsonNode> stateMessages = DelegationHttpFixture.messages(state);
                List<JsonNode> taskRequests = stateMessages.stream()
                        .filter(message -> !message.path("tool_calls").isEmpty()).toList();
                assertThat(taskRequests).hasSize(2);
                List<String> taskIds = new ArrayList<>();
                for (int round = 0; round < taskRequests.size(); round++) {
                    JsonNode request = taskRequests.get(round);
                    String id = request.path("tool_calls").get(0).path("id").asText();
                    taskIds.add(id);
                    assertThat(id).isNotBlank().doesNotStartWith("provider-task-");
                    // Narration retains its own canonical identity in both sources.
                    JsonNode expectedRequest = request;
                    List<JsonNode> pageRequests = contents(original).stream()
                            .filter(message -> message.path("id").equals(request.path("id"))).toList();
                    assertThat(pageRequests).containsExactly(expectedRequest);
                    JsonNode result = stateMessages.stream()
                            .filter(message -> id.equals(message.path("tool_call_id").asText())).findFirst().orElseThrow();
                    assertThat(result.path("additional_kwargs").path("subagent_status").asText()).isEqualTo("completed");
                    assertThat(result.path("content").asText()).contains("CHILD-OUTPUT");
                    assertThat(contents(original).stream()
                            .filter(message -> id.equals(message.path("tool_call_id").asText())).toList())
                            .containsExactly(result);
                    assertThat(original.get(1 + round * (withText ? 3 : 2) + (withText ? 1 : 0)).path("content")).isEqualTo(expectedRequest);
                    assertThat(original.get(2 + round * (withText ? 3 : 2) + (withText ? 1 : 0)).path("content")).isEqualTo(result);
                }
                assertThat(stateMessages).containsExactlyElementsOf(contents(original));
                assertThat(taskIds).doesNotHaveDuplicates();
                assertThat(original.get(original.size() - 1).path("content").path("content").asText()).isEqualTo("PARENT-FINAL");
                assertThat(contents(original).stream().map(message -> message.path("id").asText()).toList())
                        .doesNotHaveDuplicates();
            }
            try (var reopened = new GatewayFixture(data, model, evidence)) {
                assertThat(assertPageAndCursors(http, reopened.base, thread, "after-restart"))
                        .isEqualTo(original);
            }
        }
    }

    private static String readSseBounded(InputStream input) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> new String(input.readAllBytes(), StandardCharsets.UTF_8));
            try { return result.get(40, TimeUnit.SECONDS); }
            finally { input.close(); result.cancel(true); }
        }
    }

    /** Background creator drain: do not close the stream from a helper while the caller owns it. */
    private static String readSseUnbounded(InputStream input) {
        try {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("creator SSE drain failed", exception);
        }
    }

    private static List<JsonNode> contents(JsonNode rows) {
        List<JsonNode> result = new ArrayList<>();
        rows.forEach(row -> result.add(row.path("content")));
        return result;
    }

    private static JsonNode assertPageAndCursors(GatewayFixture.HttpEvidence http, URI base, String thread,
            String evidenceName) throws Exception {
        String path = "/api/threads/" + thread + "/messages/page";
        JsonNode rows = http.get(base, path + "?limit=50", evidenceName + ".json").path("data");
        List<Long> seqs = new ArrayList<>();
        rows.forEach(row -> seqs.add(row.path("seq").asLong()));
        assertThat(seqs).doesNotHaveDuplicates();
        List<Long> sorted = new ArrayList<>(seqs);
        Collections.sort(sorted);
        assertThat(seqs).isEqualTo(sorted);
        Long before = null;
        for (int index = rows.size() - 1; index >= 0; index--) {
            JsonNode page = http.get(base, path + "?limit=1" + (before == null ? "" : "&before_seq=" + before),
                    evidenceName + "-cursor-" + index + ".json");
            assertThat(page.path("data")).hasSize(1);
            assertThat(page.path("data").get(0)).isEqualTo(rows.get(index));
            assertThat(page.path("has_more").asBoolean()).isEqualTo(index > 0);
            before = page.path("data").get(0).path("seq").asLong();
        }
        return rows;
    }
}

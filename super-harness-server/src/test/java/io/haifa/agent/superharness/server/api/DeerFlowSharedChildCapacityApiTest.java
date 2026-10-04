package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.server.testsupport.ControlledProvider;
import io.haifa.agent.superharness.server.testsupport.DelegationHttpFixture;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import io.haifa.agent.superharness.server.testsupport.SseEvents;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Real production Gateway and public SDK JAR; two distinct Threads share one capacity owner. */
class DeerFlowSharedChildCapacityApiTest {
    @Test void separateThreadBridgesShareThreeSlotsAndReleaseWakesTheFourthChild() throws Exception {
        scenario(false);
    }

    @Test void cancellingAnotherThreadClosesItsPendingCallWithoutFakeStartOrSlotLeak() throws Exception {
        scenario(true);
    }

    private void scenario(boolean cancelSecondParent) throws Exception {
        Path evidence = GatewayFixture.evidenceDirectory(), data = GatewayFixture.dataDirectory();
        var firstTwo = new CountDownLatch(2);
        var firstThree = new CountDownLatch(3);
        var fourth = new CountDownLatch(1);
        var releaseOne = new CountDownLatch(1);
        var releaseAll = new CountDownLatch(1);
        var releasedHandlers = new CountDownLatch(2);
        AtomicInteger children = new AtomicInteger(), active = new AtomicInteger(), maximum = new AtomicInteger();
        Map<String, String> environment = Map.of(
                "HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W08_CAPACITY_KEY",
                "W08_CAPACITY_KEY", Base64.getEncoder().encodeToString(new byte[32]));
        try (var http = new GatewayFixture.HttpEvidence(evidence, Duration.ofSeconds(30));
                var provider = new ControlledProvider(evidence, true, (request, call) -> {
                    if (!DelegationHttpFixture.isChild(request)) {
                        boolean followedUp = request.path("messages").findValuesAsText("role").contains("tool");
                        return followedUp ? ControlledProvider.stop("lead-" + call, "w08-model", "parent completed")
                                : twoTasks(call);
                    }
                    int ordinal = children.incrementAndGet();
                    maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                    firstTwo.countDown(); firstThree.countDown();
                    if (ordinal == 4) fourth.countDown();
                    try {
                        CountDownLatch gate = ordinal == 1 ? releaseOne : releaseAll;
                        if (!gate.await(20, TimeUnit.SECONDS)) throw new AssertionError("Child release deadline expired");
                        return ControlledProvider.stop("child-" + call, "w08-model", "bounded Child output");
                    } finally {
                        active.decrementAndGet();
                        if (ordinal == 2 || ordinal == 3) releasedHandlers.countDown();
                    }
                }, (request, authorization) -> true, DelegationHttpFixture::isChild);
                var gateway = GatewayFixture.production(data, Integer.getInteger("w08.gatewayPort", 14618), environment, evidence)) {
            DelegationHttpFixture.configure(http, gateway.base, provider.port());
            DelegationHttpFixture.seed(http, gateway.base, "researcher", 30, 5, List.of());
            try (var first = begin(http, gateway.base, "capacity-a")) {
                assertThat(firstTwo.await(10, TimeUnit.SECONDS)).as("first Thread has two actual Provider calls").isTrue();
                try (var second = begin(http, gateway.base, "capacity-b")) {
                    assertThat(firstThree.await(10, TimeUnit.SECONDS)).as("three actual Child model calls admitted").isTrue();
                    JsonNode before = http.get(gateway.base, "/api/threads/capacity-b/state", "second-before.json");
                    assertThat(taskCalls(before)).hasSize(2);
                    assertThat(fourth.await(400, TimeUnit.MILLISECONDS))
                            .as("fourth Child remains pending while all three shared slots are occupied").isFalse();
                    assertThat(children).hasValue(3);
                    assertThat(maximum).hasValue(3);
                    if (cancelSecondParent) {
                        assertThat(http.request(gateway.base, "POST", second.location + "/cancel?action=interrupt&wait=0",
                                "", "second-cancel.http").statusCode()).isEqualTo(202);
                        String secondBody = second.drain(evidence.resolve("second-cancelled.sse"));
                        assertThat(started(secondBody)).hasSize(1).doesNotHaveDuplicates();
                        assertThat(children).hasValue(3);
                        JsonNode cancelled = http.get(gateway.base, second.location, "second-cancelled-run.json");
                        assertThat(cancelled.path("diagnostics").path("sdk_status").asText()).isEqualTo("CANCELLED");
                        var terminals = DelegationHttpFixture.messages(http.get(gateway.base,
                                "/api/threads/capacity-b/state", "second-cancelled-state.json")).stream()
                                .filter(message -> "task".equals(message.path("name").asText())).toList();
                        assertThat(terminals).hasSize(2).allSatisfy(message -> assertThat(message.path("additional_kwargs")
                                .path("subagent_status").asText()).isEqualTo("cancelled"));
                        releaseAll.countDown();
                        assertThat(releasedHandlers.await(10, TimeUnit.SECONDS))
                                .as("released Provider responders settle before counting later Child concurrency").isTrue();
                        try (var later = begin(http, gateway.base, "capacity-c")) {
                            releaseOne.countDown();
                            String laterBody = later.drain(evidence.resolve("later.sse"));
                            assertThat(started(laterBody)).hasSize(2).doesNotHaveDuplicates();
                            assertThat(http.get(gateway.base, later.location, "later-run.json").path("status").asText()).isEqualTo("success");
                        }
                        assertThat(children).hasValue(5);
                    } else {
                        releaseOne.countDown();
                        assertThat(fourth.await(10, TimeUnit.SECONDS))
                                .as("releasing a slot in first Bridge wakes pending Child in second Bridge").isTrue();
                        assertThat(children).hasValue(4);
                        releaseAll.countDown();
                        String secondBody = second.drain(evidence.resolve("second.sse"));
                        assertThat(started(secondBody)).hasSize(2).doesNotHaveDuplicates();
                        assertThat(http.get(gateway.base, second.location, "second-run.json").path("status").asText()).isEqualTo("success");
                    }
                    String firstBody = first.drain(evidence.resolve("first.sse"));
                    assertThat(started(firstBody)).hasSize(2).doesNotHaveDuplicates();
                    assertThat(http.get(gateway.base, first.location, "first-run.json").path("status").asText()).isEqualTo("success");
                    assertThat(maximum.get()).isLessThanOrEqualTo(3);
                    assertThat(provider.failure.get()).isNull();
                    Files.writeString(evidence.resolve("capacity-summary.json"), GatewayFixture.JSON.writeValueAsString(Map.of(
                            "cancel_second_parent", cancelSecondParent, "actual_child_provider_calls", children.get(),
                            "maximum_concurrent_provider_calls", maximum.get(), "shared_capacity", 3)));
                }
            } finally { releaseOne.countDown(); releaseAll.countDown(); }
        }
    }

    private static ObjectNode twoTasks(int call) {
        ObjectNode response = ControlledProvider.call("lead-" + call, "w08-model", "task-first-" + call, "task",
                Map.of("agent", "researcher", "objective", "first bounded Child"));
        var calls = ((ObjectNode) response.path("choices").get(0).path("message")).withArray("tool_calls");
        ObjectNode second = ((ObjectNode) calls.get(0)).deepCopy();
        second.put("id", "task-second-" + call);
        calls.add(second);
        return response;
    }

    private static List<JsonNode> taskCalls(JsonNode state) {
        return DelegationHttpFixture.messages(state).stream().flatMap(message ->
                java.util.stream.StreamSupport.stream(message.path("tool_calls").spliterator(), false))
                .filter(call -> "task".equals(call.path("name").asText())).toList();
    }

    private static List<String> started(String body) {
        return SseEvents.read(body).named("custom").stream().map(SseEvents.Event::data)
                .filter(event -> "task_started".equals(event.path("type").asText()))
                .map(event -> event.path("task_id").asText()).toList();
    }

    private static Running begin(GatewayFixture.HttpEvidence http, URI base, String thread) throws Exception {
        assertThat(http.request(base, "POST", "/api/threads", "{\"thread_id\":\"" + thread + "\"}").statusCode()).isEqualTo(200);
        var request = HttpRequest.newBuilder(base.resolve("/api/threads/" + thread + "/runs/stream"))
                .timeout(Duration.ofSeconds(40)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(DelegationHttpFixture.input(true).toString())).build();
        var response = http.client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        return new Running(response.headers().firstValue("content-location").orElseThrow(), response.body());
    }

    private record Running(String location, InputStream stream) implements AutoCloseable {
        String drain(Path path) throws Exception {
            String body = GatewayFixture.drain(stream); Files.writeString(path, body);
            assertThat(body).contains("event: end"); return body;
        }
        @Override public void close() throws java.io.IOException { stream.close(); }
    }
}

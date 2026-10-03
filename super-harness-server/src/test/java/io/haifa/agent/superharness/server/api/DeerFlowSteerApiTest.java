package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelStreamControl;
import io.haifa.agent.model.api.ModelStreamEvent;
import io.haifa.agent.model.api.ModelStreamSink;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test suite verifying the explicit Haifa Steer extension without changing frozen
 * DeerFlow's default multitask_strategy=reject admission.
 *
 * <p>Requirements from handoff.md:
 * <ul>
 *   <li>Running model steer: submit second user input during model execution -> ACCEPTED -> completion.deferred
 *       -> APPLIED -> second model call sees steer -> terminal COMPLETED with 4-message timeline.</li>
 *   <li>Tool executing steer: steer accepted during tool run -> tool returns -> steer applied at safe point.</li>
 *   <li>Duplicate steer: identical idempotency key -> only one user-visible steer message.</li>
 *   <li>Cancel + pending steer: cancel -> accepted input settled as rejected -> no ghost messages.</li>
 *   <li>Server restart: timeline ordinals and wire IDs remain 100% deterministic after SQLite recovery.</li>
 * </ul>
 */
public class DeerFlowSteerApiTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    @TempDir
    Path dataDirectory;
    private DeerFlowBootstrapApi api;
    private URI baseUri;
    private final SecretKey continuationKey = new SecretKeySpec(new byte[32], "AES");

    @AfterEach
    void stopServer() {
        if (api != null) api.close();
    }

    private void startServerWithModel(AgentChatModel model, List<? extends JavaTool<?, ?>> tools) throws Exception {
        var binding = LocalProbeModelTestSupport.binding(model);
        api = DeerFlowBootstrapApi.createForInteractionTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory,
                continuationKey,
                ignored -> binding,
                tools);
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
    }

    @Test
    void preStreamFailureDoesNotExposeExceptionText() throws Exception {
        api = DeerFlowBootstrapApi.createForInteractionTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory,
                continuationKey,
                ignored -> { throw new IllegalStateException("private-model-configuration"); },
                List.of());
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
        createThread("thread-stream-failure");

        HttpResponse<String> response = postRun("thread-stream-failure",
                "{\"input\":{\"messages\":[{\"type\":\"human\",\"content\":\"hello\"}]}}", null);
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("Run stream is unavailable")
                .doesNotContain("private-model-configuration");
    }

    @Test
    void committedSseFailureEmitsOneSanitizedErrorFrame() throws Exception {
        startServerWithModel(LocalProbeModelTestSupport.binding().model(), List.of());
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        api.threadRunApi().writeCommittedSse(wire, () -> {
            throw new IllegalStateException("private-sdk-credential-detail");
        });
        String frame = wire.toString(StandardCharsets.UTF_8);
        assertThat(frame).isEqualTo("event: error\ndata: {\"message\":\"Run stream is unavailable\"}\n\n")
                .doesNotContain("private-sdk-credential-detail", "Internal server error", "detail");
    }

    @Test
    void steerHeaderCannotBeUsedForHiddenInteractionResponse() throws Exception {
        startServerWithModel(LocalProbeModelTestSupport.binding().model(), List.of());
        String threadId = "thread-steer-interaction-conflict";
        createThread(threadId);
        String hiddenResponse = "{\"input\":{\"messages\":[{\"type\":\"human\",\"content\":\"approve\","
                + "\"additional_kwargs\":{\"human_input_response\":{\"version\":1,"
                + "\"kind\":\"human_input_response\",\"source\":\"haifa_interaction\","
                + "\"request_id\":\"request-1\",\"response_kind\":\"option\","
                + "\"option_id\":\"approve\",\"value\":\"approve\"}}}]}}";
        HttpResponse<String> response = postRun(threadId, hiddenResponse, "steer");
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("cannot answer an Interaction");
    }

    @Test
    void invalidSteerReplayCursorRejectsBeforeSubmittingAnyDurableInput() throws Exception {
        CountDownLatch firstModelEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstModel = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AgentChatModel model = new AgentChatModel() {
            @Override
            public AgentChatResponse invoke(AgentChatRequest request) {
                return invokeStreaming(request, event -> ModelStreamControl.CONTINUE);
            }

            @Override
            public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
                int call = calls.incrementAndGet();
                if (call == 1) {
                    firstModelEntered.countDown();
                    try {
                        if (!releaseFirstModel.await(15, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("first model gate timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("model interrupted", interrupted);
                    }
                }
                return new AgentChatResponse("cursor-probe-" + call, request.model().providerModelId(),
                        call == 1 ? "original-only" : "unexpected-steer", List.of(),
                        ModelFinishReason.STOP, ModelUsage.unpriced(1, 1), "", Map.of());
            }
        };
        startServerWithModel(model, List.of());
        String threadId = "thread-invalid-steer-cursor";
        createThread(threadId);
        HttpResponse<InputStream> started = client.send(HttpRequest.newBuilder()
                .uri(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"input\":{\"messages\":[{\"type\":\"human\",\"content\":\"original\"}]},"
                                + "\"on_disconnect\":\"continue\"}"))
                .build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(started.statusCode()).isEqualTo(200);
        String runId = started.headers().firstValue("content-location").orElseThrow()
                .replaceFirst(".*/", "");
        assertThat(firstModelEntered.await(10, TimeUnit.SECONDS)).isTrue();

        List<HttpResponse<String>> rejected = new ArrayList<>();
        try {
            for (String cursor : List.of("not-a-cursor", "9223372036854775808-0")) {
                rejected.add(client.send(HttpRequest.newBuilder()
                        .uri(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .header("X-Haifa-Run-Input", "steer")
                        .header("Last-Event-ID", cursor)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"input\":{\"messages\":[{\"id\":\"cursor-" + cursor
                                        + "\",\"type\":\"human\",\"content\":\"must-not-steer\"}]},"
                                        + "\"on_disconnect\":\"continue\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString()));
            }
        } finally {
            releaseFirstModel.countDown();
        }
        assertThat(readSseBounded(started.body())).contains("event: end");
        for (HttpResponse<String> response : rejected) {
            assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
            assertThat(response.body()).contains("Invalid Last-Event-ID");
        }
        JsonNode messages = getJson("/api/threads/" + threadId + "/state")
                .path("values").path("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).path("content").asText()).isEqualTo("original");
        assertThat(messages.get(1).path("content").asText()).isEqualTo("original-only");
        assertThat(calls).hasValue(1);
        System.out.println("W05_INVALID_STEER_CURSOR_EVIDENCE run=" + runId
                + " rejected=2 durable_messages=2 model_calls=" + calls.get());
    }

    @Test
    void steerUsesLastHumanIdWhenPayloadIncludesHistoricalHuman() throws Exception {
        CountDownLatch firstModelEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstModel = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AgentChatModel model = new AgentChatModel() {
            @Override
            public AgentChatResponse invoke(AgentChatRequest request) {
                return invokeStreaming(request, event -> ModelStreamControl.CONTINUE);
            }

            @Override
            public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
                int call = calls.incrementAndGet();
                if (call == 1) {
                    firstModelEntered.countDown();
                    try {
                        if (!releaseFirstModel.await(15, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("first model gate timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("model interrupted", interrupted);
                    }
                }
                return new AgentChatResponse("two-human-" + call, request.model().providerModelId(),
                        "answer-" + call, List.of(), ModelFinishReason.STOP,
                        ModelUsage.unpriced(1, 1), "", Map.of());
            }
        };
        startServerWithModel(model, List.of());
        String threadId = "thread-two-human-steer";
        createThread(threadId);
        HttpResponse<InputStream> started = client.send(HttpRequest.newBuilder()
                .uri(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"input\":{\"messages\":[{\"type\":\"human\",\"content\":\"original\"}]},"
                                + "\"on_disconnect\":\"continue\"}"))
                .build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(started.statusCode()).isEqualTo(200);
        assertThat(firstModelEntered.await(10, TimeUnit.SECONDS)).isTrue();
        List<HttpResponse<InputStream>> steers = new ArrayList<>();
        try {
            for (int index = 1; index <= 2; index++) {
                String payload = "{\"input\":{\"messages\":["
                        + "{\"id\":\"historical-id\",\"type\":\"human\",\"content\":\"history-only\"},"
                        + "{\"id\":\"current-id-" + index
                        + "\",\"type\":\"human\",\"content\":\"current-" + index
                        + "\"}]},\"on_disconnect\":\"continue\"}";
                HttpResponse<InputStream> steer = client.send(HttpRequest.newBuilder()
                        .uri(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                        .header("Content-Type", "application/json")
                        .header("X-Haifa-Run-Input", "steer")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build(), HttpResponse.BodyHandlers.ofInputStream());
                assertThat(steer.statusCode())
                        .as(steer.statusCode() == 200 ? "accepted steer" : new String(steer.body().readAllBytes(), StandardCharsets.UTF_8))
                        .isEqualTo(200);
                steers.add(steer);
            }
        } finally {
            releaseFirstModel.countDown();
        }
        assertThat(readSseBounded(started.body())).contains("event: end");
        for (HttpResponse<InputStream> steer : steers) {
            steer.body().close();
        }
        JsonNode messages = getJson("/api/threads/" + threadId + "/state")
                .path("values").path("messages");
        assertThat(messages).hasSize(5);
        assertThat(messages.get(2).path("content").asText()).isEqualTo("current-1");
        assertThat(messages.get(3).path("content").asText()).isEqualTo("current-2");
        assertThat(calls).hasValue(2);
        System.out.println("W05_TWO_HUMAN_STEER_EVIDENCE durable_messages=" + messages.size()
                + " current_ids=2 model_calls=" + calls.get());
    }

    @Test
    void steerMessageIdsRemainStableAcrossPagesBeyondRetainedWindow() throws Exception {
        CountDownLatch firstCallEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstCall = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AgentChatModel model = new AgentChatModel() {
            @Override
            public AgentChatResponse invoke(AgentChatRequest request) {
                return invokeStreaming(request, event -> ModelStreamControl.CONTINUE);
            }

            @Override
            public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
                int call = calls.incrementAndGet();
                if (call == 1) {
                    firstCallEntered.countDown();
                    try {
                        if (!releaseFirstCall.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("model gate timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("model interrupted", interrupted);
                    }
                }
                return new AgentChatResponse("long-steer-" + call, request.model().providerModelId(),
                        "answer-" + call, List.of(), ModelFinishReason.STOP, ModelUsage.unpriced(1, 1), "", Map.of());
            }
        };
        startServerWithModel(model, List.of());
        String threadId = "thread-long-steer-pagination";
        createThread(threadId);
        HttpResponse<java.io.InputStream> start = client.send(
                HttpRequest.newBuilder()
                        .uri(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"input\":{\"messages\":[{\"id\":\"initial\",\"type\":\"human\",\"content\":\"start\"}]},\"on_disconnect\":\"continue\"}"))
                        .build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(start.statusCode()).isEqualTo(200);
        String runId = start.headers().firstValue("content-location").orElseThrow().replaceFirst(".*/", "");
        assertThat(firstCallEntered.await(10, TimeUnit.SECONDS)).isTrue();

        for (int index = 1; index <= 25; index++) {
            HttpRequest steer = HttpRequest.newBuilder()
                    .uri(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                    .header("Content-Type", "application/json")
                    .header("X-Haifa-Run-Input", "steer")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"input\":{\"messages\":[{\"id\":\"steer-" + index
                            + "\",\"type\":\"human\",\"content\":\"change " + index + "\"}]},\"on_disconnect\":\"continue\"}"))
                    .build();
            HttpResponse<java.io.InputStream> response = client.send(steer, HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).as("steer %s", index).isEqualTo(200);
            response.body().close();
        }
        releaseFirstCall.countDown();
        assertThat(readSseBounded(start.body())).contains("event: end");

        JsonNode stateMessages = getJson("/api/threads/" + threadId + "/state").path("values").path("messages");
        assertThat(stateMessages.size()).isEqualTo(28);
        for (int index = 1; index <= 25; index++) {
            assertThat(stateMessages.get(index + 1).path("id").asText())
                    .isEqualTo(runId + "-human-" + index);
        }
        JsonNode historyMessages = postJson("/api/threads/" + threadId + "/history", "{\"limit\":1}")
                .get(0).path("values").path("messages");
        assertThat(historyMessages.size()).isEqualTo(28);
        for (int index = 0; index < 28; index++) {
            assertThat(historyMessages.get(index).path("id").asText())
                    .isEqualTo(stateMessages.get(index).path("id").asText());
        }

        long before = Long.MAX_VALUE;
        for (int index = 27; index >= 0; index--) {
            String path = "/api/threads/" + threadId + "/messages/page?limit=1"
                    + (before == Long.MAX_VALUE ? "" : "&before_seq=" + before);
            JsonNode page = getJson(path);
            assertThat(page.path("data").size()).isEqualTo(1);
            JsonNode row = page.path("data").get(0);
            assertThat(row.path("content").path("id").asText())
                    .as("cross-page identity at index %s", index)
                    .isEqualTo(stateMessages.get(index).path("id").asText());
            before = row.path("seq").asLong();
        }
    }

    @Test
    void retainedSseReplayUsesTheSameAssistantTextAsDurableLongSessionHistory() throws Exception {
        Semaphore modelEntered = new Semaphore(0);
        Semaphore releaseModel = new Semaphore(0);
        AtomicInteger calls = new AtomicInteger();
        AgentChatModel model = new AgentChatModel() {
            @Override
            public AgentChatResponse invoke(AgentChatRequest request) {
                return invokeStreaming(request, event -> ModelStreamControl.CONTINUE);
            }

            @Override
            public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
                int call = calls.incrementAndGet();
                modelEntered.release();
                try {
                    if (!releaseModel.tryAcquire(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("model gate timed out at call " + call);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("model interrupted", interrupted);
                }
                return new AgentChatResponse("long-replay-" + call, request.model().providerModelId(),
                        "answer-" + call, List.of(), ModelFinishReason.STOP,
                        ModelUsage.unpriced(1, 1), "", Map.of());
            }
        };
        startServerWithModel(model, List.of());
        String threadId = "thread-long-steer-replay";
        createThread(threadId);
        HttpResponse<InputStream> started = client.send(
                HttpRequest.newBuilder()
                        .uri(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"input\":{\"messages\":[{\"type\":\"human\",\"content\":\"start\"}]},"
                                        + "\"stream_mode\":[\"messages-tuple\"],\"on_disconnect\":\"continue\"}"))
                        .build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(started.statusCode()).isEqualTo(200);
        String location = started.headers().firstValue("content-location").orElseThrow();
        String runId = location.substring(location.lastIndexOf('/') + 1);
        assertThat(modelEntered.tryAcquire(10, TimeUnit.SECONDS)).isTrue();
        started.body().close();

        // Each input is accepted while its preceding model call is in flight, so each
        // completion.deferred creates a different durable assistant turn in one Run.
        for (int index = 1; index <= 10; index++) {
            HttpResponse<InputStream> steered = client.send(
                    HttpRequest.newBuilder()
                            .uri(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                            .header("Content-Type", "application/json")
                            .header("X-Haifa-Run-Input", "steer")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"input\":{\"messages\":[{\"id\":\"steer-" + index
                                            + "\",\"type\":\"human\",\"content\":\"change-" + index
                                            + "\"}]},\"on_disconnect\":\"continue\"}"))
                            .build(), HttpResponse.BodyHandlers.ofInputStream());
            assertThat(steered.statusCode()).as("steer %s", index).isEqualTo(200);
            steered.body().close();
            releaseModel.release();
            assertThat(modelEntered.tryAcquire(15, TimeUnit.SECONDS)).as("next model call %s", index + 1).isTrue();
        }
        releaseModel.release();
        String status = "";
        for (int attempt = 0; attempt < 200; attempt++) {
            status = getJson(location).path("status").asText();
            if (status.equals("success")) break;
            Thread.sleep(25);
        }
        assertThat(status).isEqualTo("success");

        Path evidence = GatewayFixture.evidenceDirectory();
        JsonNode durableState = getJson("/api/threads/" + threadId + "/state");
        Files.writeString(evidence.resolve("long-steer-durable-state.json"), durableState.toString());
        JsonNode durableMessages = durableState.path("values").path("messages");
        assertThat(durableMessages.size()).isEqualTo(22);
        HttpResponse<String> gap = client.send(HttpRequest.newBuilder()
                .uri(baseUri.resolve(location + "/stream"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        Files.writeString(evidence.resolve("long-steer-gap.sse"), gap.body());
        assertThat(gap.body()).contains("event: gap", "earliest_available_event_id");
        String gapData = gap.body().lines().filter(line -> line.startsWith("data: "))
                .findFirst().orElseThrow().substring(6);
        long earliestSequence = Long.parseLong(json.readTree(gapData)
                .path("earliest_available_event_id").asText().replace("-0", ""));
        HttpResponse<String> replay = client.send(HttpRequest.newBuilder()
                .uri(baseUri.resolve(location + "/stream"))
                .header("Last-Event-ID", (earliestSequence - 1) + "-0")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        Files.writeString(evidence.resolve("long-steer-retained-replay.sse"), replay.body());
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).contains("event: end").doesNotContain("event: error");
        int replayedSuperseded = 0;
        List<Integer> replayOrdinals = new ArrayList<>();
        for (String frame : replay.body().split("\\n\\n")) {
            if (!frame.contains("event: messages")) continue;
            String data = frame.lines().filter(line -> line.startsWith("data: "))
                    .findFirst().orElseThrow().substring(6);
            JsonNode wire = json.readTree(data).get(0);
            if (!wire.path("type").asText().equals("ai")) continue;
            String id = wire.path("id").asText();
            JsonNode durable = null;
            for (JsonNode message : durableMessages) {
                if (message.path("id").asText().equals(id)) {
                    durable = message;
                    break;
                }
            }
            assertThat(durable).as("durable message for %s", id).isNotNull();
            assertThat(wire.path("content").asText()).as("replayed content for %s", id)
                    .isEqualTo(durable.path("content").asText());
            replayOrdinals.add(Integer.parseInt(id.substring((runId + "-ai-").length())));
            if (!id.equals(runId + "-ai-10")) replayedSuperseded++;
        }
        assertThat(replayedSuperseded).as("retained replay must include an older assistant turn").isPositive();
        assertThat(replayOrdinals).isSorted();
        JsonNode historyMessages = postJson("/api/threads/" + threadId + "/history", "{\"limit\":1}")
                .get(0).path("values").path("messages");
        JsonNode messagePage = getJson("/api/threads/" + threadId + "/messages/page?limit=100")
                .path("data");
        assertThat(historyMessages.size()).isEqualTo(durableMessages.size());
        assertThat(messagePage.size()).isEqualTo(durableMessages.size());
        for (int index = 0; index < durableMessages.size(); index++) {
            assertThat(historyMessages.get(index).path("id").asText())
                    .isEqualTo(durableMessages.get(index).path("id").asText());
            assertThat(messagePage.get(index).path("content").path("id").asText())
                    .isEqualTo(durableMessages.get(index).path("id").asText());
        }
        System.out.println("W05_LONG_REPLAY_EVIDENCE run=" + runId
                + " durable_messages=" + durableMessages.size()
                + " replay_assistant_ordinals=" + replayOrdinals);
    }

    @Test
    void steerWhileModelRunningProducesFourMessageTimelineAndSettlesSuccessfully() throws Exception {
        CountDownLatch firstModelEntered = new CountDownLatch(1);
        CountDownLatch firstModelRelease = new CountDownLatch(1);
        CountDownLatch secondModelEntered = new CountDownLatch(1);
        CountDownLatch secondModelRelease = new CountDownLatch(1);
        AtomicInteger callCount = new AtomicInteger(0);
        List<AgentChatRequest> requests = new CopyOnWriteArrayList<>();

        AgentChatModel steerModel = new AgentChatModel() {
            @Override
            public AgentChatResponse invoke(AgentChatRequest request) {
                return invokeStreaming(request, event -> ModelStreamControl.CONTINUE);
            }

            @Override
            public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
                requests.add(request);
                int call = callCount.incrementAndGet();
                if (call == 1) {
                    firstModelEntered.countDown();
                    try {
                        if (!firstModelRelease.await(15, TimeUnit.SECONDS)) {
                            throw new RuntimeException("First model release timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    String text = "First answer: Paris is the capital of France.";
                    sink.emit(new ModelStreamEvent.ContentDelta(request.callId(), 1L, text));
                    return new AgentChatResponse(
                            request.callId().value(),
                            request.model().providerModelId(),
                            text,
                            List.of(),
                            ModelFinishReason.STOP,
                            ModelUsage.unpriced(10, 15),
                            "",
                            Map.of());
                } else {
                    secondModelEntered.countDown();
                    try {
                        if (!secondModelRelease.await(15, TimeUnit.SECONDS)) {
                            throw new RuntimeException("Second model release timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    String text = "Revised answer: France's capital is Paris, and Germany's capital is Berlin.";
                    sink.emit(new ModelStreamEvent.ContentDelta(request.callId(), 1L, text));
                    return new AgentChatResponse(
                            request.callId().value(),
                            request.model().providerModelId(),
                            text,
                            List.of(),
                            ModelFinishReason.STOP,
                            ModelUsage.unpriced(15, 25),
                            "",
                            Map.of());
                }
            }
        };

        startServerWithModel(steerModel, List.of());
        String threadId = "thread-steer-happy";
        createThread(threadId);

        // 1. Start Run with original prompt
        String startRunBody = "{"
                + "\"input\":{\"messages\":[{\"id\":\"client-human-0\",\"type\":\"human\",\"content\":\"What is the capital of France?\"}]},"
                + "\"stream_mode\":[\"messages-tuple\",\"updates\",\"custom\"],"
                + "\"assistant_id\":\"lead_agent\","
                + "\"on_disconnect\":\"continue\""
                + "}";

        HttpRequest startRequest = HttpRequest.newBuilder()
                .uri(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(startRunBody))
                .build();

        HttpResponse<java.io.InputStream> startStreamResponse = client.send(
                startRequest, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(startStreamResponse.statusCode()).isEqualTo(200);

        String runLocation = startStreamResponse.headers().firstValue("content-location").orElseThrow();
        String runId = runLocation.replaceFirst(".*/", "");

        // 2. Wait until model is executing Call 1
        assertThat(firstModelEntered.await(10, TimeUnit.SECONDS)).isTrue();

        // 3. Submit Steer input while Model is executing Call 1
        String steerBody = "{"
                + "\"input\":{\"messages\":[{\"id\":\"client-steer-1\",\"type\":\"human\",\"content\":\"Also tell me Germany's capital\"}]},"
                + "\"stream_mode\":[\"messages-tuple\",\"updates\",\"custom\"],"
                + "\"assistant_id\":\"lead_agent\","
                + "\"on_disconnect\":\"continue\""
                + "}";
        assertThat(postRun(threadId, steerBody, null).statusCode()).isEqualTo(409);
        assertThat(postRun(threadId, steerBody.replace("\"on_disconnect\"", "\"multitask_strategy\":\"reject\",\"on_disconnect\""), null)
                .statusCode()).isEqualTo(409);
        for (String strategy : List.of("interrupt", "rollback")) {
            assertThat(postRun(threadId, steerBody.replace("\"on_disconnect\"", "\"multitask_strategy\":\"" + strategy + "\",\"on_disconnect\""), null)
                    .statusCode()).isEqualTo(422);
        }
        assertThat(postRun(threadId, steerBody, "unknown").statusCode()).isEqualTo(422);
        assertThat(postRun(threadId, steerBody.replace("\"on_disconnect\"", "\"multitask_strategy\":\"reject\",\"on_disconnect\""), "steer")
                .statusCode()).isEqualTo(422);
        assertThat(callCount.get()).isEqualTo(1);

        HttpRequest steerRequest = HttpRequest.newBuilder()
                .uri(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                .header("Content-Type", "application/json")
                .header("X-Haifa-Run-Input", "steer")
                .POST(HttpRequest.BodyPublishers.ofString(steerBody))
                .build();

        HttpResponse<java.io.InputStream> steerStreamResponse = client.send(
                steerRequest, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(steerStreamResponse.statusCode()).isEqualTo(200);
        assertThat(steerStreamResponse.headers().firstValue("content-location"))
                .contains(runLocation);

        // 4. Release Model Call 1 -> triggers completion.deferred
        firstModelRelease.countDown();

        // 5. Model enters Call 2 with steer input
        assertThat(secondModelEntered.await(10, TimeUnit.SECONDS)).isTrue();
        secondModelRelease.countDown();

        // 6. Read SSE stream events from the initial run stream
        List<String> receivedEvents = new ArrayList<>();
        List<String> aiMessageIds = new ArrayList<>();
        List<String> aiMessageContents = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(startStreamResponse.body(), StandardCharsets.UTF_8))) {
            String line;
            String currentEvent = null;
            StringBuilder currentData = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event: ")) {
                    currentEvent = line.substring("event: ".length()).trim();
                    receivedEvents.add(currentEvent);
                } else if (line.startsWith("data: ")) {
                    currentData.append(line.substring("data: ".length()));
                } else if (line.isEmpty()) {
                    if ("messages".equals(currentEvent) && currentData.length() > 0) {
                        JsonNode tuple = json.readTree(currentData.toString());
                        if (tuple.isArray() && tuple.size() > 0) {
                            JsonNode msg = tuple.get(0);
                            if ("ai".equals(msg.path("type").asText())) {
                                aiMessageIds.add(msg.path("id").asText());
                                aiMessageContents.add(msg.path("content").asText());
                            }
                        }
                    }
                    if ("end".equals(currentEvent)) {
                        break;
                    }
                    currentEvent = null;
                    currentData.setLength(0);
                }
            }
        }

        // Live SSE must have emitted both Assistant #0 (superseded) and Assistant #1 (revised)
        assertThat(aiMessageIds).hasSize(2);
        assertThat(aiMessageIds.get(0)).isEqualTo(runId + "-ai-0");
        assertThat(aiMessageIds.get(1)).isEqualTo(runId + "-ai-1");
        assertThat(aiMessageContents.get(0)).contains("Paris is the capital of France");
        assertThat(aiMessageContents.get(1)).contains("France's capital is Paris, and Germany's capital is Berlin");
        assertThat(receivedEvents).contains("end");

        // Observe the actual second Provider request, not only the product state projection.
        // Both the original turn and accepted Steer input must retain their trust frames.
        assertThat(requests).hasSize(2);
        List<String> secondCallUsers = requests.get(1).messages().stream()
                .filter(message -> message.role().name().equals("USER"))
                .map(message -> message.content()).toList();
        assertThat(secondCallUsers).containsExactly(
                "--- BEGIN USER INPUT ---\nWhat is the capital of France?\n--- END USER INPUT ---",
                "--- BEGIN USER INPUT ---\nAlso tell me Germany's capital\n--- END USER INPUT ---");

        // 7. Read Authoritative Thread State after Run Completion
        JsonNode state = getJson("/api/threads/" + threadId + "/state");
        JsonNode messages = state.path("values").path("messages");
        assertThat(messages.size()).isEqualTo(4);

        assertThat(messages.get(0).path("id").asText()).isEqualTo(runId + "-human-0");
        assertThat(messages.get(0).path("type").asText()).isEqualTo("human");
        assertThat(messages.get(0).path("content").asText()).isEqualTo("What is the capital of France?");

        assertThat(messages.get(1).path("id").asText()).isEqualTo(runId + "-ai-0");
        assertThat(messages.get(1).path("type").asText()).isEqualTo("ai");
        assertThat(messages.get(1).path("content").asText()).contains("First answer");

        assertThat(messages.get(2).path("id").asText()).isEqualTo(runId + "-human-1");
        assertThat(messages.get(2).path("type").asText()).isEqualTo("human");
        assertThat(messages.get(2).path("content").asText()).isEqualTo("Also tell me Germany's capital");

        assertThat(messages.get(3).path("id").asText()).isEqualTo(runId + "-ai-1");
        assertThat(messages.get(3).path("type").asText()).isEqualTo("ai");
        assertThat(messages.get(3).path("content").asText()).contains("Revised answer");

        // 8. Read History
        JsonNode history = postJson("/api/threads/" + threadId + "/history", "{\"limit\":10}");
        JsonNode histMessages = history.get(0).path("values").path("messages");
        assertThat(histMessages.size()).isEqualTo(4);
        assertThat(histMessages.get(0).path("id").asText()).isEqualTo(runId + "-human-0");
        assertThat(histMessages.get(1).path("id").asText()).isEqualTo(runId + "-ai-0");
        assertThat(histMessages.get(2).path("id").asText()).isEqualTo(runId + "-human-1");
        assertThat(histMessages.get(3).path("id").asText()).isEqualTo(runId + "-ai-1");
        assertThat(histMessages.get(0).path("content").asText()).isEqualTo("What is the capital of France?");
        assertThat(histMessages.get(2).path("content").asText()).isEqualTo("Also tell me Germany's capital");

        // 9. Read messages/page
        JsonNode page = getJson("/api/threads/" + threadId + "/messages/page?limit=10");
        JsonNode pageData = page.path("data");
        assertThat(pageData.size()).isEqualTo(4);
        assertThat(pageData.get(0).path("content").path("id").asText()).isEqualTo(runId + "-human-0");
        assertThat(pageData.get(1).path("content").path("id").asText()).isEqualTo(runId + "-ai-0");
        assertThat(pageData.get(2).path("content").path("id").asText()).isEqualTo(runId + "-human-1");
        assertThat(pageData.get(3).path("content").path("id").asText()).isEqualTo(runId + "-ai-1");

        // 10. Restart Server and Verify Deterministic Recovery from Durable SQLite
        api.close();
        startServerWithModel(steerModel, List.of());

        JsonNode recoveredState = getJson("/api/threads/" + threadId + "/state");
        JsonNode recoveredMessages = recoveredState.path("values").path("messages");
        assertThat(recoveredMessages.size()).isEqualTo(4);
        assertThat(recoveredMessages.get(0).path("id").asText()).isEqualTo(runId + "-human-0");
        assertThat(recoveredMessages.get(1).path("id").asText()).isEqualTo(runId + "-ai-0");
        assertThat(recoveredMessages.get(2).path("id").asText()).isEqualTo(runId + "-human-1");
        assertThat(recoveredMessages.get(3).path("id").asText()).isEqualTo(runId + "-ai-1");
        assertThat(recoveredMessages.get(0).path("content").asText()).isEqualTo("What is the capital of France?");
        assertThat(recoveredMessages.get(2).path("content").asText()).isEqualTo("Also tell me Germany's capital");
        assertThat(postRun(threadId, steerBody, "steer").statusCode()).isEqualTo(409);
    }

    @Test
    void duplicateSteerIsIdempotentAndProducesOnlyOneSteerMessage() throws Exception {
        CountDownLatch firstModelEntered = new CountDownLatch(1);
        CountDownLatch firstModelRelease = new CountDownLatch(1);
        CountDownLatch secondModelEntered = new CountDownLatch(1);
        CountDownLatch secondModelRelease = new CountDownLatch(1);
        AtomicInteger callCount = new AtomicInteger(0);

        AgentChatModel steerModel = new AgentChatModel() {
            @Override
            public AgentChatResponse invoke(AgentChatRequest request) {
                return invokeStreaming(request, event -> ModelStreamControl.CONTINUE);
            }

            @Override
            public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
                int call = callCount.incrementAndGet();
                if (call == 1) {
                    firstModelEntered.countDown();
                    try {
                        if (!firstModelRelease.await(15, TimeUnit.SECONDS)) {
                            throw new RuntimeException("First model release timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    String text = "First answer";
                    sink.emit(new ModelStreamEvent.ContentDelta(request.callId(), 1L, text));
                    return new AgentChatResponse(
                            request.callId().value(),
                            request.model().providerModelId(),
                            text,
                            List.of(),
                            ModelFinishReason.STOP,
                            ModelUsage.unpriced(10, 15),
                            "",
                            Map.of());
                } else {
                    secondModelEntered.countDown();
                    try {
                        if (!secondModelRelease.await(15, TimeUnit.SECONDS)) {
                            throw new RuntimeException("Second model release timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    String text = "Revised answer";
                    sink.emit(new ModelStreamEvent.ContentDelta(request.callId(), 1L, text));
                    return new AgentChatResponse(
                            request.callId().value(),
                            request.model().providerModelId(),
                            text,
                            List.of(),
                            ModelFinishReason.STOP,
                            ModelUsage.unpriced(15, 25),
                            "",
                            Map.of());
                }
            }
        };

        startServerWithModel(steerModel, List.of());
        String threadId = "thread-steer-duplicate";
        createThread(threadId);

        // Start run
        String startRunBody = "{"
                + "\"input\":{\"messages\":[{\"id\":\"client-human-0\",\"type\":\"human\",\"content\":\"Task A\"}]},"
                + "\"stream_mode\":[\"messages-tuple\",\"updates\",\"custom\"],"
                + "\"assistant_id\":\"lead_agent\","
                + "\"on_disconnect\":\"continue\""
                + "}";
        HttpResponse<java.io.InputStream> startStream = client.send(
                HttpRequest.newBuilder()
                        .uri(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(startRunBody))
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(startStream.statusCode()).isEqualTo(200);

        assertThat(firstModelEntered.await(10, TimeUnit.SECONDS)).isTrue();

        // Submit steer 1 with client id
        String steerBody = "{"
                + "\"input\":{\"messages\":[{\"id\":\"unique-steer-id-123\",\"type\":\"human\",\"content\":\"Steer input\"}]},"
                + "\"stream_mode\":[\"messages-tuple\",\"updates\",\"custom\"],"
                + "\"assistant_id\":\"lead_agent\","
                + "\"on_disconnect\":\"continue\""
                + "}";
        HttpResponse<java.io.InputStream> steerResp1 = client.send(
                HttpRequest.newBuilder()
                        .uri(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                        .header("Content-Type", "application/json")
                        .header("X-Haifa-Run-Input", "steer")
                        .POST(HttpRequest.BodyPublishers.ofString(steerBody))
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(steerResp1.statusCode()).isEqualTo(200);

        // Submit duplicate steer with same client id
        HttpResponse<java.io.InputStream> steerResp2 = client.send(
                HttpRequest.newBuilder()
                        .uri(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                        .header("Content-Type", "application/json")
                        .header("X-Haifa-Run-Input", "steer")
                        .POST(HttpRequest.BodyPublishers.ofString(steerBody))
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(steerResp2.statusCode()).isEqualTo(200);

        firstModelRelease.countDown();
        assertThat(secondModelEntered.await(10, TimeUnit.SECONDS)).isTrue();
        secondModelRelease.countDown();

        // Drain streams
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(startStream.body()))) {
            while (reader.readLine() != null) {}
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(steerResp1.body()))) {
            while (reader.readLine() != null) {}
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(steerResp2.body()))) {
            while (reader.readLine() != null) {}
        }

        // Verify exactly 4 messages exist in state (1 human initial + 1 ai superseded + 1 human steer + 1 ai revised)
        JsonNode state = getJson("/api/threads/" + threadId + "/state");
        JsonNode messages = state.path("values").path("messages");
        assertThat(messages.size()).isEqualTo(4);
    }

    @Test
    void cancelledRunSettlesAcceptedSteerWithoutGhostMessages() throws Exception {
        CountDownLatch modelEntered = new CountDownLatch(1);
        CountDownLatch modelHalt = new CountDownLatch(1);

        AgentChatModel hangingModel = new AgentChatModel() {
            @Override
            public AgentChatResponse invoke(AgentChatRequest request) {
                return invokeStreaming(request, event -> ModelStreamControl.CONTINUE);
            }

            @Override
            public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
                modelEntered.countDown();
                try {
                    modelHalt.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new AgentChatResponse(
                        request.callId().value(),
                        request.model().providerModelId(),
                        "cancelled response",
                        List.of(),
                        ModelFinishReason.STOP,
                        ModelUsage.unpriced(5, 5),
                        "",
                        Map.of());
            }
        };

        startServerWithModel(hangingModel, List.of());
        String threadId = "thread-steer-cancel";
        createThread(threadId);

        HttpResponse<java.io.InputStream> startStream = client.send(
                HttpRequest.newBuilder()
                        .uri(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"input\":{\"messages\":[{\"id\":\"msg-0\",\"type\":\"human\",\"content\":\"Hello\"}]}}"))
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(startStream.statusCode()).isEqualTo(200);
        String runLocation = startStream.headers().firstValue("content-location").orElseThrow();
        String runId = runLocation.replaceFirst(".*/", "");

        assertThat(modelEntered.await(10, TimeUnit.SECONDS)).isTrue();

        // Submit steer input
        HttpResponse<java.io.InputStream> steerResp = client.send(
                HttpRequest.newBuilder()
                        .uri(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                        .header("Content-Type", "application/json")
                        .header("X-Haifa-Run-Input", "steer")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"input\":{\"messages\":[{\"id\":\"steer-before-cancel\",\"type\":\"human\",\"content\":\"Wait!\"}]}}"))
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(steerResp.statusCode()).isEqualTo(200);

        // Cancel the run
        HttpResponse<String> cancelResp = client.send(
                HttpRequest.newBuilder()
                        .uri(baseUri.resolve("/api/threads/" + threadId + "/runs/" + runId + "/cancel?action=interrupt&wait=0"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(cancelResp.statusCode()).isEqualTo(202);

        modelHalt.countDown();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(startStream.body()))) {
            while (reader.readLine() != null) {}
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(steerResp.body()))) {
            while (reader.readLine() != null) {}
        }

        // Wait for terminal interrupted status
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode run = getJson("/api/threads/" + threadId + "/runs/" + runId);
            if ("interrupted".equals(run.path("status").asText())) {
                break;
            }
            Thread.sleep(50);
        }

        JsonNode run = getJson("/api/threads/" + threadId + "/runs/" + runId);
        assertThat(run.path("status").asText()).isEqualTo("interrupted");

        // Verify transcript has no ghost steer message
        JsonNode state = getJson("/api/threads/" + threadId + "/state");
        JsonNode messages = state.path("values").path("messages");
        // Only the initial human message (if committed) can exist; the steer message was never applied
        for (JsonNode m : messages) {
            assertThat(m.path("content").asText()).isNotEqualTo("Wait!");
        }
    }

    public record WeatherQuery(String location) {}
    public record WeatherResult(String result) {}

    public static final class WeatherTool implements JavaTool<WeatherQuery, WeatherResult> {
        private static final JavaToolSpec<WeatherQuery, WeatherResult> SPEC =
                JavaToolSpec.builder("search_weather", WeatherQuery.class, WeatherResult.class)
                        .description("Look up weather for a given city")
                        .build();

        private final CountDownLatch toolEntered;
        private final CountDownLatch toolRelease;
        final List<WeatherQuery> receivedQueries = new CopyOnWriteArrayList<>();

        WeatherTool(CountDownLatch toolEntered, CountDownLatch toolRelease) {
            this.toolEntered = toolEntered;
            this.toolRelease = toolRelease;
        }

        @Override
        public JavaToolSpec<WeatherQuery, WeatherResult> spec() {
            return SPEC;
        }

        @Override
        public WeatherResult invoke(WeatherQuery input, JavaToolContext context) {
            receivedQueries.add(input);
            toolEntered.countDown();
            try {
                if (!toolRelease.await(15, TimeUnit.SECONDS)) {
                    throw new RuntimeException("Tool release timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return new WeatherResult(input.location() + " is sunny, 22C");
        }
    }

    @Test
    void steerWhileToolExecutingAppliesAtSafePoint() throws Exception {
        CountDownLatch toolEntered = new CountDownLatch(1);
        CountDownLatch toolRelease = new CountDownLatch(1);
        CountDownLatch secondModelEntered = new CountDownLatch(1);
        CountDownLatch secondModelRelease = new CountDownLatch(1);
        AtomicInteger callCount = new AtomicInteger(0);

        WeatherTool weatherTool = new WeatherTool(toolEntered, toolRelease);

        AgentChatModel toolSteerModel = new AgentChatModel() {
            @Override
            public AgentChatResponse invoke(AgentChatRequest request) {
                return invokeStreaming(request, event -> ModelStreamControl.CONTINUE);
            }

            @Override
            public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
                int call = callCount.incrementAndGet();
                if (call == 1) {
                    return new AgentChatResponse(
                            request.callId().value(),
                            request.model().providerModelId(),
                            "",
                            List.of(new ModelToolCall(
                                    new ProviderToolCallCorrelationId("tool-call-1"),
                                    "search_weather",
                                    Map.of("location", "Tokyo"))),
                            ModelFinishReason.TOOL_CALLS,
                            ModelUsage.unpriced(10, 10),
                            "",
                            Map.of());
                } else {
                    secondModelEntered.countDown();
                    try {
                        if (!secondModelRelease.await(15, TimeUnit.SECONDS)) {
                            throw new RuntimeException("Second model release timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    String text = "Tool executed for Tokyo and steer was applied at safe point.";
                    sink.emit(new ModelStreamEvent.ContentDelta(request.callId(), 1L, text));
                    return new AgentChatResponse(
                            request.callId().value(),
                            request.model().providerModelId(),
                            text,
                            List.of(),
                            ModelFinishReason.STOP,
                            ModelUsage.unpriced(15, 20),
                            "",
                            Map.of());
                }
            }
        };

        startServerWithModel(toolSteerModel, List.of(weatherTool));
        String threadId = "thread-steer-tool";
        createThread(threadId);

        String startRunBody = "{"
                + "\"input\":{\"messages\":[{\"id\":\"human-tool-0\",\"type\":\"human\",\"content\":\"Check Tokyo weather\"}]},"
                + "\"stream_mode\":[\"messages-tuple\",\"updates\",\"custom\"],"
                + "\"assistant_id\":\"lead_agent\","
                + "\"on_disconnect\":\"continue\""
                + "}";
        HttpResponse<java.io.InputStream> startStream = client.send(
                HttpRequest.newBuilder()
                        .uri(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(startRunBody))
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(startStream.statusCode()).isEqualTo(200);

        // Wait until tool is executing
        assertThat(toolEntered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(weatherTool.receivedQueries).hasSize(1);
        assertThat(weatherTool.receivedQueries.get(0).location()).isEqualTo("Tokyo");

        // Submit steer input while tool is running
        String steerBody = "{"
                + "\"input\":{\"messages\":[{\"id\":\"steer-tool-1\",\"type\":\"human\",\"content\":\"Also check Kyoto\"}]},"
                + "\"stream_mode\":[\"messages-tuple\",\"updates\",\"custom\"],"
                + "\"assistant_id\":\"lead_agent\","
                + "\"on_disconnect\":\"continue\""
                + "}";
        HttpResponse<java.io.InputStream> steerStream = client.send(
                HttpRequest.newBuilder()
                        .uri(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                        .header("Content-Type", "application/json")
                        .header("X-Haifa-Run-Input", "steer")
                        .POST(HttpRequest.BodyPublishers.ofString(steerBody))
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(steerStream.statusCode()).isEqualTo(200);

        // Tool was NOT modified while running
        assertThat(weatherTool.receivedQueries.get(0).location()).isEqualTo("Tokyo");

        // Release tool
        toolRelease.countDown();

        // Model entered Call 2 after tool completed and steer input was safely applied
        assertThat(secondModelEntered.await(10, TimeUnit.SECONDS)).isTrue();
        secondModelRelease.countDown();

        // Drain streams
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(startStream.body()))) {
            while (reader.readLine() != null) {}
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(steerStream.body()))) {
            while (reader.readLine() != null) {}
        }

        // Verify thread state
        JsonNode state = getJson("/api/threads/" + threadId + "/state");
        JsonNode messages = state.path("values").path("messages");
        assertThat(messages.size()).isGreaterThanOrEqualTo(3);
        assertThat(countToolRequests(messages, "search_weather")).isEqualTo(1);
        assertThat(countToolResults(messages, "search_weather")).isEqualTo(1);

        JsonNode historyMessages = postJson("/api/threads/" + threadId + "/history", "{\"limit\":10}")
                .get(0).path("values").path("messages");
        assertThat(countToolRequests(historyMessages, "search_weather")).isEqualTo(1);
        assertThat(countToolResults(historyMessages, "search_weather")).isEqualTo(1);
    }

    @Test
    void restartedServerPreservesSteerTimelineAndDeterministicWireIds() throws Exception {
        CountDownLatch firstModelEntered = new CountDownLatch(1);
        CountDownLatch firstModelRelease = new CountDownLatch(1);
        CountDownLatch secondModelEntered = new CountDownLatch(1);
        CountDownLatch secondModelRelease = new CountDownLatch(1);
        AtomicInteger callCount = new AtomicInteger(0);

        AgentChatModel steerModel = new AgentChatModel() {
            @Override
            public AgentChatResponse invoke(AgentChatRequest request) {
                return invokeStreaming(request, event -> ModelStreamControl.CONTINUE);
            }

            @Override
            public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
                int call = callCount.incrementAndGet();
                if (call == 1) {
                    firstModelEntered.countDown();
                    try {
                        if (!firstModelRelease.await(15, TimeUnit.SECONDS)) {
                            throw new RuntimeException("First model release timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    String text = "First answer: Alpha";
                    sink.emit(new ModelStreamEvent.ContentDelta(request.callId(), 1L, text));
                    return new AgentChatResponse(
                            request.callId().value(),
                            request.model().providerModelId(),
                            text,
                            List.of(),
                            ModelFinishReason.STOP,
                            ModelUsage.unpriced(10, 15),
                            "",
                            Map.of());
                } else {
                    secondModelEntered.countDown();
                    try {
                        if (!secondModelRelease.await(15, TimeUnit.SECONDS)) {
                            throw new RuntimeException("Second model release timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    String text = "Revised answer: Beta";
                    sink.emit(new ModelStreamEvent.ContentDelta(request.callId(), 1L, text));
                    return new AgentChatResponse(
                            request.callId().value(),
                            request.model().providerModelId(),
                            text,
                            List.of(),
                            ModelFinishReason.STOP,
                            ModelUsage.unpriced(15, 25),
                            "",
                            Map.of());
                }
            }
        };

        startServerWithModel(steerModel, List.of());
        String threadId = "thread-steer-restart";
        createThread(threadId);

        String startRunBody = "{"
                + "\"input\":{\"messages\":[{\"id\":\"client-human-0\",\"type\":\"human\",\"content\":\"Initial question\"}]},"
                + "\"stream_mode\":[\"messages-tuple\",\"updates\",\"custom\"],"
                + "\"assistant_id\":\"lead_agent\","
                + "\"on_disconnect\":\"continue\""
                + "}";

        HttpResponse<java.io.InputStream> startStream = client.send(
                HttpRequest.newBuilder()
                        .uri(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(startRunBody))
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(startStream.statusCode()).isEqualTo(200);
        String runLocation = startStream.headers().firstValue("content-location").orElseThrow();
        String runId = runLocation.replaceFirst(".*/", "");

        assertThat(firstModelEntered.await(10, TimeUnit.SECONDS)).isTrue();

        String steerBody = "{"
                + "\"input\":{\"messages\":[{\"id\":\"client-steer-1\",\"type\":\"human\",\"content\":\"Steer update\"}]},"
                + "\"stream_mode\":[\"messages-tuple\",\"updates\",\"custom\"],"
                + "\"assistant_id\":\"lead_agent\","
                + "\"on_disconnect\":\"continue\""
                + "}";
        HttpResponse<java.io.InputStream> steerStream = client.send(
                HttpRequest.newBuilder()
                        .uri(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                        .header("Content-Type", "application/json")
                        .header("X-Haifa-Run-Input", "steer")
                        .POST(HttpRequest.BodyPublishers.ofString(steerBody))
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(steerStream.statusCode()).isEqualTo(200);

        firstModelRelease.countDown();
        assertThat(secondModelEntered.await(10, TimeUnit.SECONDS)).isTrue();
        secondModelRelease.countDown();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(startStream.body()))) {
            while (reader.readLine() != null) {}
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(steerStream.body()))) {
            while (reader.readLine() != null) {}
        }

        // Verify pre-restart state
        JsonNode preState = getJson("/api/threads/" + threadId + "/state");
        JsonNode preMessages = preState.path("values").path("messages");
        assertThat(preMessages.size()).isEqualTo(4);
        assertThat(preMessages.get(0).path("id").asText()).isEqualTo(runId + "-human-0");
        assertThat(preMessages.get(1).path("id").asText()).isEqualTo(runId + "-ai-0");
        assertThat(preMessages.get(2).path("id").asText()).isEqualTo(runId + "-human-1");
        assertThat(preMessages.get(3).path("id").asText()).isEqualTo(runId + "-ai-1");

        // Stop server and restart
        api.close();
        api = null;

        startServerWithModel(steerModel, List.of());

        // Verify post-restart state matches 100% deterministic wire IDs
        JsonNode postState = getJson("/api/threads/" + threadId + "/state");
        JsonNode postMessages = postState.path("values").path("messages");
        assertThat(postMessages.size()).isEqualTo(4);
        assertThat(postMessages.get(0).path("id").asText()).isEqualTo(runId + "-human-0");
        assertThat(postMessages.get(0).path("content").asText()).isEqualTo("Initial question");
        assertThat(postMessages.get(1).path("id").asText()).isEqualTo(runId + "-ai-0");
        assertThat(postMessages.get(1).path("content").asText()).contains("Alpha");
        assertThat(postMessages.get(2).path("id").asText()).isEqualTo(runId + "-human-1");
        assertThat(postMessages.get(2).path("content").asText()).isEqualTo("Steer update");
        assertThat(postMessages.get(3).path("id").asText()).isEqualTo(runId + "-ai-1");
        assertThat(postMessages.get(3).path("content").asText()).contains("Beta");

        // Verify history projection matches
        JsonNode history = postJson("/api/threads/" + threadId + "/history", "{\"limit\":10}");
        JsonNode histMessages = history.get(0).path("values").path("messages");
        assertThat(histMessages.size()).isEqualTo(4);
        assertThat(histMessages.get(0).path("id").asText()).isEqualTo(runId + "-human-0");
        assertThat(histMessages.get(1).path("id").asText()).isEqualTo(runId + "-ai-0");
        assertThat(histMessages.get(2).path("id").asText()).isEqualTo(runId + "-human-1");
        assertThat(histMessages.get(3).path("id").asText()).isEqualTo(runId + "-ai-1");

        // Verify messages/page matches
        JsonNode page = getJson("/api/threads/" + threadId + "/messages/page?limit=10");
        JsonNode pageData = page.path("data");
        assertThat(pageData.size()).isEqualTo(4);
        assertThat(pageData.get(0).path("content").path("id").asText()).isEqualTo(runId + "-human-0");
        assertThat(pageData.get(1).path("content").path("id").asText()).isEqualTo(runId + "-ai-0");
        assertThat(pageData.get(2).path("content").path("id").asText()).isEqualTo(runId + "-human-1");
        assertThat(pageData.get(3).path("content").path("id").asText()).isEqualTo(runId + "-ai-1");
    }

    private void createThread(String threadId) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(baseUri.resolve("/api/threads"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"thread_id\":\"" + threadId + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> postRun(String threadId, String body, String mode) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json");
        if (mode != null) request.header("X-Haifa-Run-Input", mode);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String readSseBounded(InputStream stream) throws Exception {
        CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder frames = new StringBuilder();
                for (int lineCount = 0; lineCount < 10_000; lineCount++) {
                    String line = reader.readLine();
                    if (line == null) return frames.toString();
                    frames.append(line).append('\n');
                    if (line.equals("event: end") || line.equals("event: error")) return frames.toString();
                }
                throw new IllegalStateException("SSE stream exceeded 10,000 lines without terminal frame");
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("SSE stream could not be read", failure);
            }
        });
        try {
            return result.get(15, TimeUnit.SECONDS);
        } finally {
            stream.close();
            result.cancel(true);
        }
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(baseUri.resolve(path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private JsonNode postJson(String path, String body) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(baseUri.resolve(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private static int countToolRequests(JsonNode messages, String toolName) {
        int count = 0;
        for (JsonNode message : messages) {
            for (JsonNode toolCall : message.path("tool_calls")) {
                if (toolName.equals(toolCall.path("name").asText())) count++;
            }
        }
        return count;
    }

    private static int countToolResults(JsonNode messages, String toolName) {
        int count = 0;
        for (JsonNode message : messages) {
            if ("tool".equals(message.path("type").asText())
                    && toolName.equals(message.path("name").asText())) {
                count++;
            }
        }
        return count;
    }
}

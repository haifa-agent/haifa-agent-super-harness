package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.superharness.server.run.HaifaRunBridge;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelStreamEvent;
import io.haifa.agent.model.api.ModelStreamSink;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.superharness.server.config.LocalServerConfiguration;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Files;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * W05 LIVE02 contract tests for the new parent-AI live text seam, written against the Gateway
 * BFF protocol and the existing streaming fixture style only (no assertion on implementation
 * internals):
 * <ul>
 *   <li>every concurrent SSE observer owns its own producer buffer, so each observer sees its
 *       own complete cumulative values frames (nothing consumed by a first observer);</li>
 *   <li>a late reconnect replays public outputs on a fresh buffer and still emits live values
 *       frames for that connection;</li>
 *   <li>no values frame ever carries both the accepted durable row and its still-live
 *       provisional copy for the same invocation;</li>
 *   <li>live text is not silently sliced at an invented 64KiB bound, and the terminal durable
 *       snapshot keeps the full text exactly once.</li>
 * </ul>
 */
@Timeout(120)
class W05LiveValuesStreamContractTest {
    private static final String LIVE_HEAD = "W05-STEP-1";
    private static final String LIVE_TAIL = "|W05-STEP-2";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    @TempDir
    Path dataDirectory;
    private DeerFlowBootstrapApi api;
    private URI baseUri;
    private ScriptedModel model;
    private final List<Recorded> streams = new ArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        model = new ScriptedModel(List.of(LIVE_HEAD, LIVE_TAIL));
        startApi(model);
    }

    void startApi(ScriptedModel chatModel) throws Exception {
        startApi(chatModel, List.of());
    }

    void startApi(ScriptedModel chatModel, List<? extends JavaTool<?, ?>> fixtureTools) throws Exception {
        model = chatModel;
        startModelApi(chatModel, fixtureTools);
    }

    void startModelApi(AgentChatModel chatModel, List<? extends JavaTool<?, ?>> fixtureTools) throws Exception {
        ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                new ModelProviderId("super-harness-local-probe"), "1.0.0",
                new ModelDefinitionId("super-harness-local-probe"), "1.0.0",
                "super-harness-local-probe", "super-harness-local-probe-adapter", "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS, ModelApiBindingDefinition.STANDARD_DIALECT,
                URI.create("https://model.invalid"), new CredentialRef("env://SUPER_HARNESS_LOCAL_PROBE"),
                true, Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                32_768, 1_024, Map.of(), Map.of());
        if (fixtureTools.isEmpty()) {
            api = DeerFlowBootstrapApi.createForTests(
                    new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                    dataDirectory,
                    ignored -> new HaifaRunBridge.ModelBinding("super-harness-local-probe", "1.0.0", chatModel, snapshot),
                    new SecretKeySpec(new byte[32], "AES"));
        } else {
            api = DeerFlowBootstrapApi.createForInteractionTests(
                    new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                    dataDirectory,
                    new SecretKeySpec(new byte[32], "AES"),
                    ignored -> new HaifaRunBridge.ModelBinding("super-harness-local-probe", "1.0.0", chatModel, snapshot),
                    fixtureTools);
        }
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
    }

    @AfterEach
    void stopServer() throws Exception {
        if (model != null) { model.release.countDown(); model.secondRelease.countDown(); }
        try {
            if (!streams.isEmpty()) {
                Files.writeString(GatewayFixture.evidenceDirectory().resolve(
                        "w05-live-values-raw-" + System.nanoTime() + ".json"),
                        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(
                                streams.stream().map(stream -> Map.of("location", stream.location(),
                                        "frames", stream.frames(), "terminal_end", stream.end,
                                        "reader_error", String.valueOf(stream.failure.get()))).toList()));
            }
            Exception cleanupFailure = null;
            for (Recorded stream : streams) {
                try { stream.close(); }
                catch (Exception failed) {
                    if (cleanupFailure == null) cleanupFailure = failed;
                    else cleanupFailure.addSuppressed(failed);
                }
            }
            if (cleanupFailure != null) throw cleanupFailure;
        }
        finally { streams.clear(); if (api != null) api.close(); }
    }

    @Test
    void eachConcurrentObserverAndEachReconnectSeeTextBeforeTheModelReturns() throws Exception {
        String threadId = "thread-live-values-observers";
        createThread(threadId);
        Recorded creator = openCreator(threadId);
        assertThat(model.entered.await(15, TimeUnit.SECONDS)).isTrue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        String location = creator.location();
        assertThat(location).isNotBlank();
        Recorded one = open(location, null);
        Recorded two = open(location, null);
        Recorded reconnect = open(location, "-1");
        for (Recorded stream : List.of(creator, one, two, reconnect)) {
            stream.awaitFirst(deadline);
            assertThat(model.release.getCount()).as("actual model is still blocked mid-stream").isEqualTo(1);
            assertThat(stream.frames().stream().filter(frame -> "values".equals(frame.event()))
                    .flatMap(frame -> rows(frame.dataNode().path("messages")).stream())
                    .filter(row -> "ai".equals(row.path("type").asText()))
                    .map(row -> row.path("content").asText()).toList())
                    .contains(LIVE_HEAD).doesNotContain(LIVE_HEAD + LIVE_TAIL);
        }
        model.release.countDown();
        for (Recorded stream : List.of(creator, one, two, reconnect)) {
            stream.awaitTerminal();
            assertStoredExactlyOnce(stream, threadId, LIVE_HEAD + LIVE_TAIL);
        }
        writeEvidence(threadId);
    }

    @Test
    void longLiveTextIsCompleteBeforeReleaseAndRetirementDoesNotDuplicateAcceptedText() throws Exception {
        String big = "W05-BIG-" + "x".repeat(70_000) + "-W05-TAIL";
        stopServer();
        startApi(new ScriptedModel(List.of(big)));
        String threadId = "thread-live-values-big";
        createThread(threadId);
        Recorded creator = openCreator(threadId);
        assertThat(model.entered.await(15, TimeUnit.SECONDS)).isTrue();
        creator.awaitFirst(System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
        assertThat(model.release.getCount()).as("70k unsliced first frame before accepted message").isEqualTo(1);
        model.release.countDown();
        creator.awaitTerminal();
        assertStoredExactlyOnce(creator, threadId, big);
        writeEvidence(threadId);
    }

    @Test
    void lengthFinishReasonGenerationStreamsThenFailsAndIsNeverAccepted() throws Exception {
        stopServer();
        startApi(new ScriptedModel(List.of("W05-REJECTED-PARTIAL"), ModelFinishReason.LENGTH));
        String threadId = "thread-live-values-length";
        createThread(threadId);
        Recorded creator = openCreator(threadId);
        assertThat(model.entered.await(15, TimeUnit.SECONDS)).isTrue();
        creator.awaitFirst(System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
        assertThat(model.release.getCount())
                .as("the failed generation streamed its provisional text while the native call was gated")
                .isEqualTo(1);
        model.release.countDown();
        creator.awaitTerminal();
        List<JsonNode> values = creator.frames().stream().filter(frame -> "values".equals(frame.event()))
                .map(Recorded.Frame::dataNode).toList();
        assertThat(values).isNotEmpty();
        List<Recorded.Frame> errorFrames = creator.frames().stream()
                .filter(frame -> "error".equals(frame.event())).toList();
        assertThat(errorFrames)
                .as("the runtime's own terminal failure frame for the strict finish reason")
                .isNotEmpty();
        assertThat(errorFrames.getLast().dataNode().path("message").asText()).isNotBlank();
        List<JsonNode> terminalAi = rows(values.getLast().path("messages")).stream()
                .filter(row -> "ai".equals(row.path("type").asText())).toList();
        assertThat(terminalAi.stream().map(row -> row.path("content").asText()).toList())
                .as("the failed provisional text is not retained as an accepted AI row")
                .doesNotContain("W05-REJECTED-PARTIAL")
                .doesNotHaveDuplicates();
        HttpResponse<String> pageResponse = client.send(HttpRequest.newBuilder(baseUri.resolve(
                "/api/threads/" + threadId + "/messages/page?limit=100"))
                .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(pageResponse.statusCode()).isEqualTo(200);
        JsonNode page = new ObjectMapper().readTree(pageResponse.body());
        List<JsonNode> durableAi = rows(page.path("data")).stream().map(row -> row.path("content"))
                .filter(row -> "ai".equals(row.path("type").asText())).toList();
        assertThat(durableAi.stream().map(row -> row.path("id").asText()).toList())
                .as("no duplicated AI invocation row beside a durable terminal summary")
                .doesNotHaveDuplicates();
        for (JsonNode row : durableAi) {
            assertThat(terminalAi.stream().filter(other ->
                            other.path("id").asText().equals(row.path("id").asText())
                                    && other.path("content").asText().equals(row.path("content").asText())).count())
                    .as("durable terminal row " + row.path("id").asText()
                            + " matches the same final values id/body exactly once")
                    .isEqualTo(1);
        }
        for (JsonNode row : terminalAi) {
            assertThat(durableAi.stream().filter(other ->
                            other.path("id").asText().equals(row.path("id").asText())).count())
                    .as("final values AI row " + row.path("id").asText() + " also exists in the durable page")
                    .isEqualTo(1);
        }
        writeEvidence(threadId);
    }

    @Test
    void actualSteerStreamsTheNextInvocationBeforeCommitWithoutDuplicateRetiredRows() throws Exception {
        stopServer();
        startApi(new ScriptedModel(List.of("W05-STEER-OLD"), "W05-STEER-NEW"));
        String threadId = "thread-live-values-steer";
        createThread(threadId);
        Recorded creator = openCreator(threadId);
        assertThat(model.entered.await(15, TimeUnit.SECONDS)).isTrue();
        creator.awaitFirst(System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
        HttpResponse<java.io.InputStream> admission = client.send(HttpRequest.newBuilder(baseUri.resolve(
                        "/api/threads/" + threadId + "/runs/stream"))
                .header("Content-Type", "application/json")
                .header("X-Haifa-Run-Input", "steer")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(Map.of(
                        "input", Map.of("messages", List.of(Map.of("id", "human-steer", "type", "human",
                                "content", "W05-STEER-INPUT"))),
                        "stream_mode", List.of("messages-tuple"), "on_disconnect", "continue"))))
                .build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var body = admission.body()) {
            assertThat(admission.statusCode()).isEqualTo(200);
            assertThat(admission.headers().firstValue("Content-Location").orElseThrow())
                    .isEqualTo(creator.location());
            assertThat(model.release.getCount()).isEqualTo(1);
            Files.writeString(GatewayFixture.evidenceDirectory().resolve("w05-steer-admission.json"),
                    new ObjectMapper().writeValueAsString(Map.of("status", admission.statusCode(),
                            "location", creator.location(), "on_disconnect", "continue")));
        }
        model.release.countDown();
        assertThat(model.secondEntered.await(15, TimeUnit.SECONDS)).isTrue();
        creator.awaitText("W05-STEER-NEW", System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
        assertThat(model.secondRelease.getCount()).as("second native invocation still gated").isEqualTo(1);
        assertThat(model.invocations.get()).isEqualTo(2);
        model.secondRelease.countDown();
        creator.awaitTerminal();
        List<JsonNode> values = creator.frames().stream().filter(frame -> "values".equals(frame.event()))
                .map(Recorded.Frame::dataNode).toList();
        for (JsonNode frame : values) {
            for (String text : List.of("W05-STEER-OLD", "W05-STEER-NEW")) {
                assertThat(rows(frame.path("messages")).stream()
                        .filter(row -> "ai".equals(row.path("type").asText())
                                && text.equals(row.path("content").asText())).count())
                        .as("one invocation never appears as both provisional and accepted: " + text)
                        .isLessThanOrEqualTo(1);
            }
        }
        List<JsonNode> terminalAi = rows(values.getLast().path("messages")).stream()
                .filter(row -> "ai".equals(row.path("type").asText())).toList();
        assertThat(terminalAi.stream().filter(row -> "W05-STEER-NEW".equals(row.path("content").asText())).count())
                .isEqualTo(1);
        HttpResponse<String> page = client.send(HttpRequest.newBuilder(baseUri.resolve(
                        "/api/threads/" + threadId + "/messages/page?limit=100"))
                .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);
        List<JsonNode> durableAi = rows(new ObjectMapper().readTree(page.body()).path("data")).stream()
                .map(row -> row.path("content")).filter(row -> "ai".equals(row.path("type").asText())).toList();
        assertThat(terminalAi.stream().map(row -> Map.of("id", row.path("id").asText(),
                        "body", row.path("content").asText())).toList())
                .containsExactlyInAnyOrderElementsOf(durableAi.stream().map(row -> Map.of(
                        "id", row.path("id").asText(), "body", row.path("content").asText())).toList());
        assertThat(terminalAi.stream().map(row -> row.path("id").asText()).toList()).doesNotHaveDuplicates();
        Files.writeString(GatewayFixture.evidenceDirectory().resolve("w05-steer-final-page.json"), page.body());
        writeEvidence(threadId);
    }

    @Test
    void actualToolAndFollowingLiveTextReachEveryConcurrentObserver() throws Exception {
        stopServer();
        CountDownLatch toolEntered = new CountDownLatch(1);
        CountDownLatch toolRelease = new CountDownLatch(1);
        var tool = new DeerFlowSteerApiTest.WeatherTool(toolEntered, toolRelease);
        startApi(new ScriptedModel(List.of("W05-TOOL-TEXT"), "W05-AFTER-TOOL",
                new ModelToolCall(new ProviderToolCallCorrelationId("provider-w05-weather"),
                        "search_weather", Map.of("location", "W05-city"))), List.of(tool));
        String threadId = "thread-live-values-tool";
        createThread(threadId);
        try {
            Recorded creator = openCreator(threadId);
            assertThat(model.entered.await(15, TimeUnit.SECONDS)).isTrue();
            Recorded observer = open(creator.location(), "-1");
            long firstDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            for (Recorded stream : List.of(creator, observer)) stream.awaitFirst(firstDeadline);
            assertThat(model.release.getCount()).isEqualTo(1);
            model.release.countDown();
            assertThat(toolEntered.await(15, TimeUnit.SECONDS)).as("actual JavaTool entered").isTrue();
            assertThat(tool.receivedQueries).containsExactly(new DeerFlowSteerApiTest.WeatherQuery("W05-city"));
            toolRelease.countDown();
            assertThat(model.secondEntered.await(15, TimeUnit.SECONDS)).isTrue();
            long secondDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            for (Recorded stream : List.of(creator, observer)) stream.awaitText("W05-AFTER-TOOL", secondDeadline);
            assertThat(model.secondRelease.getCount()).as("next invocation still gated").isEqualTo(1);
            assertThat(model.invocations.get()).isEqualTo(2);
            model.secondRelease.countDown();
            for (Recorded stream : List.of(creator, observer)) stream.awaitTerminal();
            HttpResponse<String> diagnostic = client.send(HttpRequest.newBuilder(baseUri.resolve(creator.location()))
                    .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(diagnostic.statusCode()).isEqualTo(200);
            Files.writeString(GatewayFixture.evidenceDirectory().resolve("w05-tool-run.json"), diagnostic.body());
            JsonNode nativeCalls = new ObjectMapper().readTree(diagnostic.body()).path("diagnostics").path("tool_calls");
            assertThat(rows(nativeCalls)).hasSize(1);
            String nativeId = nativeCalls.get(0).path("tool_call_id").asText();
            assertThat(nativeId).isNotBlank().isNotEqualTo("provider-w05-weather");
            assertThat(nativeCalls.get(0).path("status").asText()).isEqualTo("COMPLETED");
            for (Recorded stream : List.of(creator, observer)) {
                List<JsonNode> messages = stream.frames().stream().filter(frame -> "messages".equals(frame.event()))
                        .map(Recorded.Frame::dataNode).filter(JsonNode::isArray).map(tuple -> tuple.get(0)).toList();
                assertThat(messages.stream().filter(message -> message.path("tool_calls").isArray())
                        .flatMap(message -> rows(message.path("tool_calls")).stream())
                        .filter(call -> nativeId.equals(call.path("id").asText())).toList())
                        .as("each observer receives the actual native Tool request once").hasSize(1);
                List<JsonNode> results = messages.stream().filter(message -> "tool".equals(message.path("type").asText())
                        && nativeId.equals(message.path("tool_call_id").asText())).toList();
                assertThat(results).as("each observer receives the actual native Tool result once").hasSize(1);
                assertThat(results.getFirst().path("content").asText()).isEqualTo("search_weather completed");
                assertThat(results.getFirst().path("additional_kwargs").path("haifa_tool_result").path("result").asText())
                        .isEqualTo("W05-city is sunny, 22C");
                List<JsonNode> terminalValues = stream.frames().stream().filter(frame -> "values".equals(frame.event()))
                        .map(Recorded.Frame::dataNode).toList();
                assertThat(rows(terminalValues.getLast().path("messages")).stream()
                        .filter(row -> "W05-AFTER-TOOL".equals(row.path("content").asText())).toList())
                        .as("the final accepted native invocation remains exactly once").hasSize(1);
                for (Recorded.Frame frame : stream.frames()) {
                    if (!"values".equals(frame.event())) continue;
                    for (String text : List.of("W05-TOOL-TEXT", "W05-AFTER-TOOL")) {
                        assertThat(rows(frame.dataNode().path("messages")).stream()
                                .filter(row -> text.equals(row.path("content").asText())).count())
                                .as("one native invocation has no provisional/durable duplicate").isLessThanOrEqualTo(1);
                    }
                }
            }
            writeEvidence(threadId);
        } finally {
            toolRelease.countDown();
        }
    }

    private void assertStoredExactlyOnce(Recorded stream, String threadId, String expected) throws Exception {
        List<JsonNode> values = stream.frames().stream().filter(frame -> "values".equals(frame.event()))
                .map(Recorded.Frame::dataNode).toList();
        assertThat(values).isNotEmpty();
        for (JsonNode frame : values) {
            assertThat(rows(frame.path("messages")).stream()
                    .filter(row -> "ai".equals(row.path("type").asText())).toList())
                    .as("no frame contains both provisional and accepted text for the one model call")
                    .hasSizeLessThanOrEqualTo(1);
        }
        List<JsonNode> terminal = rows(values.getLast().path("messages")).stream()
                .filter(row -> "ai".equals(row.path("type").asText())).toList();
        assertThat(terminal).hasSize(1);
        assertThat(terminal.getFirst().path("content").asText()).isEqualTo(expected);
        var response = client.send(HttpRequest.newBuilder(baseUri.resolve(
                "/api/threads/" + threadId + "/messages/page?limit=100"))
                .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode page = new ObjectMapper().readTree(response.body());
        List<JsonNode> durable = rows(page.path("data")).stream().map(row -> row.path("content"))
                .filter(row -> "ai".equals(row.path("type").asText())).toList();
        assertThat(durable).as("actual persisted messages/page accepted row").hasSize(1);
        assertThat(durable.getFirst().path("content").asText()).isEqualTo(expected);
        assertThat(durable.getFirst().path("id").asText()).isEqualTo(terminal.getFirst().path("id").asText());
    }

    @Test
    void lateObserverWithEvictedOutputPrefixWaitsForTheCompleteAcceptedBody() throws Exception {
        stopServer();
        List<String> chunks = new ArrayList<>();
        chunks.add("W05-RETAINED-PREFIX|");
        chunks.addAll(java.util.Collections.nCopies(2_049, "x"));
        startApi(new ScriptedModel(chunks, ModelFinishReason.STOP, null, null, true));
        String expected = String.join("", chunks);
        String threadId = "thread-live-values-retention";
        try {
            createThread(threadId);
            Recorded creator = openCreator(threadId);
            assertThat(model.entered.await(15, TimeUnit.SECONDS)).isTrue();
            creator.awaitFirst(System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
            model.release.countDown();
            assertThat(model.secondEntered.await(15, TimeUnit.SECONDS)).isTrue();
            creator.awaitText(expected, System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
            assertThat(model.secondRelease.getCount()).as("native call is still uncommitted").isEqualTo(1);
            Recorded late = open(creator.location(), null);
            late.awaitValues(System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
            assertThat(late.frames().stream().filter(frame -> "values".equals(frame.event()))
                    .flatMap(frame -> rows(frame.dataNode().path("messages")).stream())
                    .filter(row -> "ai".equals(row.path("type").asText())).toList())
                    .as("retained deltas without STARTED cannot invent a suffix-only provisional answer")
                    .isEmpty();
            assertThat(late.end).isFalse();
            assertThat(model.secondRelease.getCount()).isEqualTo(1);
            model.secondRelease.countDown();
            for (Recorded stream : List.of(creator, late)) {
                stream.awaitTerminal();
                assertStoredExactlyOnce(stream, threadId, expected);
            }
            writeEvidence(threadId);
        } finally {
            model.release.countDown();
            model.secondRelease.countDown();
        }
    }

    @Test
    void actualChildStreamingNeverBecomesParentLiveText() throws Exception {
        stopServer();
        DelegatingStreamingModel delegated = new DelegatingStreamingModel();
        startModelApi(delegated, List.of());
        String threadId = "thread-live-values-native-child";
        try {
            String definition = """
                    {"name":"w05-live-child","description":"Actual bounded Child stream",
                    "system_prompt":"W05-CHILD-ONLY-CONTEXT","model":"inherit","tools":[],
                    "skills":null,"max_turns":4,"timeout_seconds":90}
                    """;
            assertThat(postJson("/api/subagents", definition).statusCode()).isEqualTo(201);
            createThread(threadId);
            var response = client.send(HttpRequest.newBuilder(baseUri.resolve(
                    "/api/threads/" + threadId + "/runs/stream"))
                    .header("Content-Type", "application/json").timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"assistant_id":"lead_agent","input":{"messages":[{"id":"human-1",
                            "type":"human","content":"Delegate actual bounded work"}]},
                            "stream_mode":["messages-tuple","custom"],"on_disconnect":"continue",
                            "multitask_strategy":"reject","context":{"mode":"ultra",
                            "subagent_enabled":true,"thinking_enabled":false}}
                            """)).build(), HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            Recorded creator = new Recorded(response, LIVE_HEAD);
            streams.add(creator);
            creator.awaitFirst(System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
            Recorded observer = open(creator.location(), null);
            observer.awaitFirst(System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
            delegated.parentRelease.countDown();
            assertThat(delegated.childEntered.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(delegated.childRelease.getCount()).isEqualTo(1);
            assertThat(delegated.childCalls).hasValue(1);
            for (Recorded stream : List.of(creator, observer)) {
                assertThat(stream.end).isFalse();
                assertThat(stream.frames().stream().filter(frame -> "values".equals(frame.event()))
                        .flatMap(frame -> rows(frame.dataNode().path("messages")).stream())
                        .filter(row -> "ai".equals(row.path("type").asText()))
                        .map(row -> row.path("content").asText()).toList())
                        .contains(LIVE_HEAD).doesNotContain(DelegatingStreamingModel.CHILD_TEXT);
            }
            delegated.childRelease.countDown();
            assertThat(delegated.finalEntered.await(15, TimeUnit.SECONDS)).isTrue();
            for (Recorded stream : List.of(creator, observer)) {
                stream.awaitText(DelegatingStreamingModel.PARENT_FINAL,
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
                assertThat(delegated.finalRelease.getCount()).isEqualTo(1);
                assertThat(valuesContain(stream, DelegatingStreamingModel.CHILD_TEXT)).isFalse();
            }
            delegated.finalRelease.countDown();
            for (Recorded stream : List.of(creator, observer)) {
                stream.awaitTerminal();
                var toolResults = stream.frames().stream().filter(frame -> "messages".equals(frame.event()))
                        .map(frame -> frame.dataNode().get(0))
                        .filter(row -> "tool".equals(row.path("type").asText())
                                && "task".equals(row.path("name").asText())).toList();
                assertThat(toolResults).hasSize(1);
                assertThat(toolResults.getFirst().path("additional_kwargs").path("subagent_status").asText())
                        .isEqualTo("completed");
                assertThat(toolResults.getFirst().path("additional_kwargs").path("haifa_tool_result")
                        .path("childRunId").asText()).isNotBlank();
                assertThat(toolResults.getFirst().path("content").asText())
                        .contains(DelegatingStreamingModel.CHILD_TEXT);
                assertThat(valuesContain(stream, DelegatingStreamingModel.CHILD_TEXT)).isFalse();
            }
            assertThat(delegated.parentCalls).hasValue(2);
            assertThat(delegated.childCalls).hasValue(1);
            writeEvidence(threadId);
        } finally {
            delegated.parentRelease.countDown();
            delegated.childRelease.countDown();
            delegated.finalRelease.countDown();
        }
    }

    private void writeEvidence(String threadId) throws Exception {
        Files.writeString(GatewayFixture.evidenceDirectory().resolve("w05-live-values-" + threadId + ".json"),
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "thread_id", threadId, "retry_count", 0,
                        "streams", streams.stream().map(stream -> Map.of("location", stream.location(),
                                "frames", stream.frames(), "terminal_end", stream.end,
                                "first_frame_nanos", stream.firstFrameNanos)).toList())));
    }

    private static List<JsonNode> rows(JsonNode messages) {
        List<JsonNode> out = new ArrayList<>();
        assertThat(messages.isArray()).as("frozen protocol messages/data array").isTrue();
        messages.forEach(out::add);
        return out;
    }

    private boolean valuesContain(Recorded stream, String contains) {
        return stream.frames().stream()
                .filter(frame -> "values".equals(frame.event()))
                .flatMap(frame -> rows(frame.dataNode().path("messages")).stream())
                .anyMatch(row -> "ai".equals(row.path("type").asText())
                        && row.path("content").asText().contains(contains));
    }

    private Recorded openCreator(String threadId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(
                "/api/threads/" + threadId + "/runs/stream"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(Map.of(
                        "assistant_id", "lead_agent", "input", Map.of("messages", List.of(Map.of(
                                "id", "human-1", "type", "human", "content", "live values"))),
                        "on_disconnect", "continue", "stream_mode", List.of("messages-tuple"),
                        "multitask_strategy", "reject"))))
                .build();
        HttpResponse<java.io.InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        Recorded stream = new Recorded(response, model.chunks.getFirst());
        streams.add(stream);
        return stream;
    }

    private Recorded open(String location, String lastEventId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                baseUri.resolve(location + "/stream")).timeout(Duration.ofSeconds(15)).GET();
        if (lastEventId != null) request.header("Last-Event-ID", lastEventId);
        HttpRequest built = request.build();
        HttpResponse<java.io.InputStream> response =
                client.send(built, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        Recorded stream = new Recorded(response, model.chunks.getFirst());
        streams.add(stream);
        return stream;
    }

    private void createThread(String threadId) throws Exception {
        assertThat(postJson("/api/threads", "{\"thread_id\":\"" + threadId + "\"}").statusCode())
                .isEqualTo(200);
    }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve(path))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static final class DelegatingStreamingModel implements AgentChatModel {
        static final String CHILD_TEXT = "W05-ONLY-CHILD-STREAM";
        static final String PARENT_FINAL = "W05-PARENT-STREAM-AFTER-CHILD";
        final AtomicInteger parentCalls = new AtomicInteger();
        final AtomicInteger childCalls = new AtomicInteger();
        final CountDownLatch parentRelease = new CountDownLatch(1);
        final CountDownLatch childEntered = new CountDownLatch(1);
        final CountDownLatch childRelease = new CountDownLatch(1);
        final CountDownLatch finalEntered = new CountDownLatch(1);
        final CountDownLatch finalRelease = new CountDownLatch(1);

        @Override public AgentChatResponse invoke(AgentChatRequest request) {
            throw new AssertionError("actual Parent and Child must exercise native streaming");
        }

        @Override public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
            boolean parent = request.tools().stream().anyMatch(tool -> "task".equals(tool.name()));
            int call = parent ? parentCalls.incrementAndGet() : childCalls.incrementAndGet();
            String text = parent ? (call == 1 ? LIVE_HEAD : PARENT_FINAL) : CHILD_TEXT;
            sink.emit(new ModelStreamEvent.ContentDelta(request.callId(), 1, text));
            CountDownLatch release;
            if (!parent) { childEntered.countDown(); release = childRelease; }
            else if (call == 1) release = parentRelease;
            else { finalEntered.countDown(); release = finalRelease; }
            try {
                if (!release.await(60, TimeUnit.SECONDS)) throw new AssertionError("native delegated stream never released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
            }
            boolean task = parent && call == 1;
            return new AgentChatResponse("w05-native-delegation-" + call, request.model().providerModelId(), text,
                    task ? List.of(new ModelToolCall(new ProviderToolCallCorrelationId("provider-w05-native-child"),
                            "task", Map.of("agent", "w05-live-child", "objective", "W05-NATIVE-CHILD-OBJECTIVE"))) : List.of(),
                    task ? ModelFinishReason.TOOL_CALLS : ModelFinishReason.STOP,
                    ModelUsage.unpriced(1, 1), "", Map.of());
        }
    }

    /** Streaming fixture: emits the scripted chunks, then blocks mid-stream until released. */
    private static final class ScriptedModel implements AgentChatModel {
        private final List<String> chunks;
        private final ModelFinishReason finishReason;
        private final String secondText;
        private final ModelToolCall firstTool;
        private final boolean holdAfterAllChunks;
        final AtomicInteger invocations = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch secondEntered = new CountDownLatch(1);
        final CountDownLatch secondRelease = new CountDownLatch(1);

        ScriptedModel(List<String> chunks) {
            this(chunks, ModelFinishReason.STOP);
        }

        ScriptedModel(List<String> chunks, ModelFinishReason finishReason) {
            this(chunks, finishReason, null);
        }

        ScriptedModel(List<String> chunks, String secondText) {
            this(chunks, ModelFinishReason.STOP, secondText);
        }

        ScriptedModel(List<String> chunks, ModelFinishReason finishReason, String secondText) {
            this(chunks, finishReason, secondText, null);
        }

        ScriptedModel(List<String> chunks, String secondText, ModelToolCall firstTool) {
            this(chunks, ModelFinishReason.STOP, secondText, firstTool);
        }

        ScriptedModel(List<String> chunks, ModelFinishReason finishReason, String secondText, ModelToolCall firstTool) {
            this(chunks, finishReason, secondText, firstTool, false);
        }

        ScriptedModel(List<String> chunks, ModelFinishReason finishReason, String secondText,
                ModelToolCall firstTool, boolean holdAfterAllChunks) {
            this.holdAfterAllChunks = holdAfterAllChunks;
            this.chunks = chunks;
            this.finishReason = finishReason;
            this.secondText = secondText;
            this.firstTool = firstTool;
        }

        @Override
        public AgentChatResponse invoke(AgentChatRequest request) {
            throw new AssertionError("fixture must exercise actual native streaming");
        }

        @Override
        public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
            int invocation = invocations.incrementAndGet();
            List<String> invocationChunks = secondText != null && invocation == 2 ? List.of(secondText) : chunks;
            if (secondText != null && invocation == 2 && firstTool == null) {
                assertThat(request.messages().stream().anyMatch(message -> message.content().contains("W05-STEER-INPUT")))
                        .as("the actual second native call receives the accepted Steer").isTrue();
            }
            if (secondText != null && invocation == 2 && firstTool != null) {
                assertThat(request.messages().stream().anyMatch(message ->
                        message.role() == io.haifa.agent.model.api.ModelMessageRole.TOOL
                                && message.providerCorrelationId().orElseThrow().value().equals("provider-w05-weather")
                                && message.content().equals("search_weather completed")
                                && "W05-city is sunny, 22C".equals(message.toolResultData().get("result"))))
                        .as("the actual second native call receives the real Tool result").isTrue();
            }
            long sequence = 1;
            sink.emit(new ModelStreamEvent.ContentDelta(request.callId(), sequence++, invocationChunks.getFirst()));
            CountDownLatch invocationEntered = secondText != null && invocation == 2 ? secondEntered : entered;
            CountDownLatch invocationRelease = secondText != null && invocation == 2 ? secondRelease : release;
            invocationEntered.countDown();
            try {
                if (!invocationRelease.await(60, TimeUnit.SECONDS)) {
                    throw new AssertionError("blocking model never released mid-stream");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            for (int index = 1; index < invocationChunks.size(); index++) {
                sink.emit(new ModelStreamEvent.ContentDelta(request.callId(), sequence++, invocationChunks.get(index)));
            }
            if (holdAfterAllChunks) {
                secondEntered.countDown();
                try {
                    if (!secondRelease.await(60, TimeUnit.SECONDS)) {
                        throw new AssertionError("retained output HTTP fixture never released completion");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            String total = String.join("", invocationChunks);
            boolean firstInvokesTool = invocation == 1 && firstTool != null;
            return new AgentChatResponse(
                    "w05-live-values-contract",
                    request.model().providerModelId(),
                    total,
                    firstInvokesTool ? List.of(firstTool) : List.of(),
                    firstInvokesTool ? ModelFinishReason.TOOL_CALLS : finishReason,
                    ModelUsage.unpriced(1, 1),
                    "",
                    Map.of());
        }
    }

    /**
     * A single SSE connection and its incrementally collected frames; reading starts when the
     * response arrives, so interaction with the Run can happen while frames stream in.
     */
    private static final class Recorded implements AutoCloseable {
        private final java.io.InputStream body;
        private final String location;
        private final Thread reader;
        private final List<Frame> frames = new CopyOnWriteArrayList<>();
        private final CountDownLatch first = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile long firstFrameNanos;
        private volatile boolean end;

        Recorded(HttpResponse<java.io.InputStream> response, String expectedFirst) {
            body = response.body();
            location = response.headers().firstValue("Content-Location").orElse("");
            reader = new Thread(() -> {
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                    String line;
                    String event = null;
                    StringBuilder data = new StringBuilder();
                    while ((line = lines.readLine()) != null) {
                        if (line.startsWith("event: ")) event = line.substring(7).trim();
                        else if (line.startsWith("data: ")) data.append(line.substring(6));
                        else if (line.isEmpty()) {
                            if (event != null) {
                                Frame frame = new Frame(event, data.toString());
                                frames.add(frame);
                                synchronized (Recorded.this) { Recorded.this.notifyAll(); }
                                if ("values".equals(event) && rows(frame.dataNode().path("messages")).stream()
                                        .anyMatch(row -> "ai".equals(row.path("type").asText())
                                                && expectedFirst.equals(row.path("content").asText()))) {
                                    if (first.getCount() != 0) firstFrameNanos = System.nanoTime();
                                    first.countDown();
                                }
                                if ("end".equals(event)) { end = true; return; }
                            }
                            event = null;
                            data.setLength(0);
                        }
                    }
                    throw new AssertionError("SSE closed without terminal end event");
                } catch (Throwable error) { failure.set(error); }
                finally { finished.countDown(); synchronized (Recorded.this) { Recorded.this.notifyAll(); } }
            }, "w05-live-values-observer");
            reader.setDaemon(true);
            reader.start();
        }
        String location() { return location; }
        List<Frame> frames() { return List.copyOf(frames); }
        void awaitFirst(long deadline) throws Exception {
            boolean arrived = first.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            assertThat(failure.get()).as("SSE reader failure").isNull();
            assertThat(arrived).as("complete first live text BEFORE model release within15s").isTrue();
        }
        void awaitTerminal() throws Exception {
            assertThat(finished.await(60, TimeUnit.SECONDS)).as("terminal stream within60s").isTrue();
            assertThat(failure.get()).as("SSE reader failure").isNull();
            assertThat(end).isTrue();
        }
        synchronized void awaitValues(long deadline) throws Exception {
            while (frames.stream().noneMatch(frame -> "values".equals(frame.event()))) {
                assertThat(failure.get()).as("SSE reader failure").isNull();
                long remaining = deadline - System.nanoTime();
                assertThat(remaining).as("late observer receives a real values frame within15s").isPositive();
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }
        synchronized void awaitText(String expected, long deadline) throws Exception {
            while (!frames.stream().filter(frame -> "values".equals(frame.event()))
                    .flatMap(frame -> rows(frame.dataNode().path("messages")).stream())
                    .anyMatch(row -> "ai".equals(row.path("type").asText())
                            && expected.equals(row.path("content").asText()))) {
                assertThat(failure.get()).as("SSE reader failure").isNull();
                long remaining = deadline - System.nanoTime();
                assertThat(remaining).as("actual next invocation text before release within15s").isPositive();
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }
        @Override public void close() throws Exception {
            body.close();
            assertThat(reader.join(Duration.ofSeconds(5))).as("owned observer released").isTrue();
        }
        private record Frame(String event, String data) {
            JsonNode dataNode() {
                try { return new ObjectMapper().readTree(data); }
                catch (Exception invalid) { throw new IllegalStateException(invalid); }
            }
        }
    }
}

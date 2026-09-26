package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.sdk.conversation.ConversationTurn;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeerFlowThreadRunApiTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    @TempDir
    Path dataDirectory;
    private DeerFlowBootstrapApi api;
    private URI baseUri;
    private final SecretKey continuationKey = new SecretKeySpec(new byte[32], "AES");

    @Test
    void unknownRunStateCannotClearAdmissionOrEvictHotCache() {
        assertThat(DeerFlowThreadRunApi.isKnownTerminal(Optional::<io.haifa.agent.runtime.api.AgentRunSnapshot>empty))
                .isFalse();
        assertThat(DeerFlowThreadRunApi.isKnownTerminal(() -> {
            throw new IllegalStateException("transient durable lookup failure");
        })).isFalse();
    }

    @Test
    void workspaceCompletionObservationHasAFiniteWallClockBound() {
        assertThat(DeerFlowThreadRunApi.WORKSPACE_COMPLETION_OBSERVER_MAX_WAIT)
                .isEqualTo(Duration.ofHours(24));
        assertThat(DeerFlowThreadRunApi.WORKSPACE_COMPLETION_OBSERVER_INITIAL_POLL)
                .isEqualTo(Duration.ofMillis(25));
        assertThat(DeerFlowThreadRunApi.WORKSPACE_COMPLETION_OBSERVER_MAX_POLL)
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(DeerFlowThreadRunApi.nextWorkspaceCompletionPollMillis(25)).isEqualTo(50);
        assertThat(DeerFlowThreadRunApi.nextWorkspaceCompletionPollMillis(800)).isEqualTo(1_000);
        assertThat(DeerFlowThreadRunApi.nextWorkspaceCompletionPollMillis(1_000)).isEqualTo(1_000);
    }

    @BeforeEach
    void startServer() throws Exception {
        startApi();
    }

    private void startApi() throws Exception {
        startApi(LocalProbeModelTestSupport.binding());
    }

    private void startApi(HaifaRunBridge.ModelBinding binding) throws Exception {
        api = DeerFlowBootstrapApi.createForTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory,
                ignored -> binding,
                continuationKey);
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
    }

    @AfterEach
    void stopServer() {
        if (api != null) api.close();
    }

    @Test
    void createsIdempotentThreadAndStripsClientForgedOwnership() throws Exception {
        HttpResponse<String> first = postJson(
                "/api/threads",
                """
                {"thread_id":"thread-one","metadata":{"label":"kept","owner":"attacker","user_id":"attacker","system_role":"owner"}}
                """);
        HttpResponse<String> second = postJson(
                "/api/threads", "{\"thread_id\":\"thread-one\",\"metadata\":{\"label\":\"ignored\"}}");

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(first.body());
        assertThat(body.path("thread_id").asText()).isEqualTo("thread-one");
        assertThat(body.path("status").asText()).isEqualTo("idle");
        assertThat(body.path("metadata").path("label").asText()).isEqualTo("kept");
        assertThat(body.path("metadata").has("owner")).isFalse();
        assertThat(body.path("metadata").has("user_id")).isFalse();
        assertThat(json.readTree(second.body()).path("metadata").path("label").asText())
                .isEqualTo("kept");

        JsonNode fetched = getJson("/api/threads/thread-one");
        assertThat(fetched.path("thread_id").asText()).isEqualTo("thread-one");
        JsonNode state = getJson("/api/threads/thread-one/state");
        assertThat(state.path("values").isObject()).isTrue();
        assertThat(state.path("next").isArray()).isTrue();
        HttpResponse<String> search = postJson(
                "/api/threads/search", "{\"archived\":false,\"metadata\":{\"label\":\"kept\"},\"limit\":20,\"offset\":0}");
        assertThat(search.statusCode()).isEqualTo(200);
        assertThat(json.readTree(search.body()).get(0).path("thread_id").asText())
                .isEqualTo("thread-one");
    }

    @Test
    void realHaifaRunStreamsFrozenMetadataMessageAndEndFrames() throws Exception {
        createThread("thread-stream");

        HttpResponse<String> response = client.send(
                runRequest("thread-stream", "hello sdk"), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type"))
                .hasValueSatisfying(value -> assertThat(value).contains("text/event-stream"));
        String location = response.headers().firstValue("content-location").orElseThrow();
        String runId = location.substring(location.lastIndexOf('/') + 1);
        assertThat(response.body()).contains("event: metadata");
        assertThat(response.body()).contains("\"run_id\":\"" + runId + "\"");
        assertThat(response.body()).contains("event: messages");
        assertThat(response.body()).doesNotContain("event: messages-tuple");
        assertThat(response.body()).contains("Haifa Agent SDK: ");
        assertThat(response.body()).contains("hello sdk");
        assertThat(response.body()).containsPattern("event: end\\ndata: null\\nid: \\d+-0\\n\\n$");
        assertThat(sseIds(response.body())).isSorted();

        JsonNode run = getJson(location);
        assertThat(run.path("status").asText()).isEqualTo("success");
        assertThat(run.path("run_id").asText()).isEqualTo(runId);
        assertThat(run.path("assistant_id").asText()).isEqualTo("lead_agent");
        assertThat(run.path("llm_call_count").asLong()).isEqualTo(1);
    }

    @Test
    void acceptsFrozenUiTextBlockRequestAndConcatenatesMultipleBlocks() throws Exception {
        createThread("thread-frozen-ui-shape");
        HttpResponse<String> frozenUi = postJson(
                "/api/threads/thread-frozen-ui-shape/runs/stream",
                """
                {"input":{"messages":[{"type":"human","id":"local-human-1","content":[{"type":"text","text":"frozen UI request"}],"additional_kwargs":{}}]},"config":{"recursion_limit":1000},"context":{"model_name":"test-model","mode":"flash","thinking_enabled":false,"is_plan_mode":false,"subagent_enabled":false,"thread_id":"thread-frozen-ui-shape"},"stream_mode":["messages-tuple","updates","custom"],"assistant_id":"lead_agent","on_disconnect":"continue"}
                """);

        assertThat(frozenUi.statusCode()).isEqualTo(200);
        assertThat(frozenUi.body()).contains("Haifa Agent SDK: frozen UI request");

        createThread("thread-multiple-text-blocks");
        HttpResponse<String> multipleBlocks = postJson(
                "/api/threads/thread-multiple-text-blocks/runs/stream",
                """
                {"assistant_id":"lead_agent","input":{"messages":[{"type":"human","content":[{"type":"text","text":"first "},{"type":"text","text":"second"}]}]},"stream_mode":["messages-tuple","updates","custom"]}
                """);

        assertThat(multipleBlocks.statusCode()).isEqualTo(200);
        assertThat(multipleBlocks.body()).contains("Haifa Agent SDK: first second");
    }

    @Test
    void frozenUiProjectionEndpointsExposeStableTranscriptHistoryAndRunList() throws Exception {
        createThread("thread-projection");
        HttpResponse<String> first = client.send(
                runRequest("thread-projection", "first turn"),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> second = client.send(
                runRequest("thread-projection", "second turn"),
                HttpResponse.BodyHandlers.ofString());
        String firstRunId = first.headers()
                .firstValue("content-location")
                .orElseThrow()
                .replaceFirst(".*/", "");
        String secondRunId = second.headers()
                .firstValue("content-location")
                .orElseThrow()
                .replaceFirst(".*/", "");

        assertThat(second.body()).contains("event: messages");
        assertThat(second.body()).doesNotContain("event: messages-tuple");

        JsonNode page = getJson("/api/threads/thread-projection/messages/page?limit=3");
        assertThat(page.path("has_more").asBoolean()).isTrue();
        assertThat(page.path("next_before_seq").asLong()).isEqualTo(2);
        assertThat(page.path("data").findValuesAsText("run_id"))
                .contains(firstRunId, secondRunId);
        assertThat(page.path("data").findValuesAsText("seq"))
                .containsExactly("2", "3", "4");
        assertThat(page.path("data").get(2).path("content").path("content").asText())
                .isEqualTo("Haifa Agent SDK: second turn");

        JsonNode older = getJson("/api/threads/thread-projection/messages/page?before_seq=2");
        assertThat(older.path("has_more").asBoolean()).isFalse();
        assertThat(older.path("next_before_seq").isNull()).isTrue();
        assertThat(older.path("data")).hasSize(1);
        assertThat(older.path("data").get(0).path("seq").asLong()).isEqualTo(1);

        JsonNode newestTwo = getJson("/api/threads/thread-projection/messages/page?limit=2");
        JsonNode oldestTwo = getJson(
                "/api/threads/thread-projection/messages/page?limit=2&before_seq="
                        + newestTwo.path("next_before_seq").asLong());
        assertThat(newestTwo.path("data").findValuesAsText("seq")).containsExactly("3", "4");
        assertThat(oldestTwo.path("data").findValuesAsText("seq")).containsExactly("1", "2");
        assertThat(oldestTwo.path("data").findValuesAsText("seq"))
                .doesNotContainAnyElementsOf(newestTwo.path("data").findValuesAsText("seq"));

        HttpResponse<String> history = postJson(
                "/api/threads/thread-projection/history", "{\"limit\":1}");
        assertThat(history.statusCode()).isEqualTo(200);
        JsonNode historyEntries = json.readTree(history.body());
        assertThat(historyEntries).hasSize(1);
        String latestCheckpoint = historyEntries.get(0).path("checkpoint_id").asText();
        assertThat(latestCheckpoint).matches("turn-[1-9][0-9]*");
        assertThat(historyEntries.get(0).path("values").path("messages")).hasSize(4);
        assertThat(historyEntries.get(0).path("values").path("messages").get(3).path("run_id").asText())
                .isEqualTo(secondRunId);

        JsonNode olderHistory = json.readTree(postJson(
                        "/api/threads/thread-projection/history",
                        "{\"limit\":1,\"before\":\"" + latestCheckpoint + "\"}")
                .body());
        assertThat(olderHistory).hasSize(1);
        assertThat(olderHistory.get(0).path("checkpoint_id").asText())
                .matches("turn-[1-9][0-9]*")
                .isNotEqualTo(latestCheckpoint);

        JsonNode newestRun = getJson("/api/threads/thread-projection/runs");
        assertThat(newestRun).hasSize(2);
        assertThat(newestRun.get(0).path("run_id").asText()).isEqualTo(secondRunId);
        assertThat(newestRun.get(0).path("status").asText()).isEqualTo("success");

        assertThat(get("/api/threads/thread-projection/messages/page?after_seq=1").statusCode())
                .isEqualTo(422);
        assertThat(get("/api/threads/thread-projection/messages/page?before_seq=bad").statusCode())
                .isEqualTo(422);
        assertThat(postJson("/api/threads/thread-projection/history", "{\"limit\":0}")
                        .statusCode())
                .isEqualTo(422);
        assertThat(postJson(
                                "/api/threads/thread-projection/history",
                                "{\"limit\":1,\"before\":\"unsupported\"}")
                        .statusCode())
                .isEqualTo(422);
    }

    @Test
    void historyAccumulatorFindsOneHundredCheckpointsAcrossMoreThanTwoHundredMessages() {
        DeerFlowThreadRunApi.HistoryAccumulator accumulator =
                new DeerFlowThreadRunApi.HistoryAccumulator(100, null);
        for (int sequence = 1; sequence <= 250; sequence++) {
            accumulator.accept(turn(
                    sequence,
                    sequence % 2 == 0 ? MessageRole.ASSISTANT : MessageRole.USER));
        }

        List<DeerFlowThreadRunApi.HistoryProjection> projections = accumulator.newestFirst();
        assertThat(projections).hasSize(100);
        assertThat(projections.getFirst().checkpoint().sequence()).isEqualTo(250);
        assertThat(projections.getLast().checkpoint().sequence()).isEqualTo(52);
        assertThat(projections).allSatisfy(
                projection -> assertThat(projection.messages()).hasSizeLessThanOrEqualTo(200));

        DeerFlowThreadRunApi.HistoryAccumulator before =
                new DeerFlowThreadRunApi.HistoryAccumulator(3, 202L);
        for (int sequence = 1; sequence <= 250; sequence++) {
            before.accept(turn(
                    sequence,
                    sequence % 2 == 0 ? MessageRole.ASSISTANT : MessageRole.USER));
        }
        assertThat(before.newestFirst())
                .extracting(projection -> projection.checkpoint().sequence())
                .containsExactly(200L, 198L, 196L);
    }

    @Test
    void transcriptForwardScanFailsClosedAtOneHundredSdkPages() {
        DeerFlowThreadRunApi.requireAnotherScanPage(99);
        assertThatThrownBy(() -> DeerFlowThreadRunApi.requireAnotherScanPage(100))
                .isInstanceOf(DeerFlowThreadRunApi.TranscriptScanLimitException.class);
    }

    @Test
    void sqliteAuthorityRestoresTranscriptHistoryAndRunsAfterProcessRestart() throws Exception {
        createThread("thread-restart");
        HttpResponse<String> first = client.send(
                runRequest("thread-restart", "before restart one"),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> second = client.send(
                runRequest("thread-restart", "before restart two"),
                HttpResponse.BodyHandlers.ofString());
        String firstRunId = first.headers().firstValue("content-location").orElseThrow().replaceFirst(".*/", "");
        String secondRunId = second.headers().firstValue("content-location").orElseThrow().replaceFirst(".*/", "");

        api.close();
        api = null;
        startApi();

        JsonNode messages = getJson("/api/threads/thread-restart/messages/page?limit=10");
        assertThat(messages.path("data")).hasSize(4);
        assertThat(messages.findValuesAsText("run_id")).contains(firstRunId, secondRunId);
        assertThat(messages.toString()).contains("before restart one", "before restart two");

        JsonNode history = json.readTree(postJson(
                        "/api/threads/thread-restart/history", "{\"limit\":2}")
                .body());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).path("checkpoint_id").asText()).matches("turn-[1-9][0-9]*");

        JsonNode runs = getJson("/api/threads/thread-restart/runs");
        assertThat(runs).hasSize(2);
        assertThat(runs.findValuesAsText("run_id")).containsExactly(secondRunId, firstRunId);
        assertThat(runs.findValuesAsText("status")).containsOnly("success");
    }

    @Test
    void historicalTranscriptRemainsReadableWhenCurrentModelRevisionChanges() throws Exception {
        createThread("thread-historical-binding");
        HttpResponse<String> original = client.send(
                runRequest("thread-historical-binding", "before provider restart"),
                HttpResponse.BodyHandlers.ofString());
        String runId = original.headers()
                .firstValue("content-location")
                .orElseThrow()
                .replaceFirst(".*/", "");

        api.close();
        api = null;
        HaifaRunBridge.ModelBinding originalBinding = LocalProbeModelTestSupport.binding();
        startApi(new HaifaRunBridge.ModelBinding(
                originalBinding.name(),
                "2.0.0",
                originalBinding.model(),
                originalBinding.snapshot()));

        HttpResponse<String> messages = get("/api/threads/thread-historical-binding/messages/page");
        assertThat(messages.statusCode()).isEqualTo(200);
        assertThat(messages.body()).contains("before provider restart", runId);
        assertThat(postJson("/api/threads/thread-historical-binding/history", "{}")
                        .statusCode())
                .isEqualTo(200);
        assertThat(get("/api/threads/thread-historical-binding/runs").statusCode()).isEqualTo(200);

        HttpResponse<String> driftedRun = client.send(
                runRequest("thread-historical-binding", "must not change binding"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(driftedRun.statusCode()).isEqualTo(409);
        assertThat(driftedRun.body()).contains("already bound to a different model revision");
    }

    @Test
    void bareRunListReturnsMoreThanTenAndHotRunCacheRemainsBounded() throws Exception {
        createThread("thread-many-runs");
        List<String> runIds = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            HttpResponse<String> run = client.send(
                    runRequest("thread-many-runs", "turn " + index),
                    HttpResponse.BodyHandlers.ofString());
            runIds.add(run.headers().firstValue("content-location").orElseThrow().replaceFirst(".*/", ""));
        }

        api.close();
        api = null;
        startApi();
        api.resetTurnPageReads("thread-many-runs");

        JsonNode runs = getJson("/api/threads/thread-many-runs/runs");
        assertThat(runs).hasSize(11);
        assertThat(runs.get(0).path("run_id").asText()).isEqualTo(runIds.getLast());
        assertThat(runs.get(10).path("run_id").asText()).isEqualTo(runIds.getFirst());
        assertThat(api.turnPageReads("thread-many-runs")).isEqualTo(1);
        assertThat(api.cachedRunCount()).isLessThanOrEqualTo(8);
    }

    @Test
    void failedSessionRestoreClosesAndRollsBackTheTentativeBridge() throws Exception {
        api.close();
        api = null;
        HaifaRunBridge.ModelBinding binding = LocalProbeModelTestSupport.binding();
        PersistentThreadCatalog catalog = new PersistentThreadCatalog(json, dataDirectory);
        catalog.save(new PersistentThreadCatalog.Entry(
                "thread-missing-session",
                java.util.Map.of(),
                Instant.parse("2026-09-24T00:00:00Z"),
                Instant.parse("2026-09-24T00:00:01Z"),
                Optional.of(new io.haifa.agent.core.session.AgentSessionId("missing-session")),
                Optional.of(binding.name() + "@" + binding.revision())));
        startApi();

        assertThatThrownBy(() -> api.resetTurnPageReads("thread-missing-session"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("persisted thread conversation is unavailable");
        assertThat(api.hasAttachedBridge("thread-missing-session")).isFalse();
        HttpResponse<String> unavailable = get("/api/threads/thread-missing-session/messages/page");
        assertThat(unavailable.statusCode()).isEqualTo(503);
        assertThat(unavailable.body()).contains("Durable thread state is unavailable");
        assertThat(api.hasAttachedBridge("thread-missing-session")).isFalse();
    }

    @Test
    void updatesOnlyStreamDoesNotEmitMessagesAndRunOwnershipIsThreadIsolated() throws Exception {
        createThread("thread-updates-only");
        createThread("thread-isolated");
        HttpResponse<String> updates = postJson(
                "/api/threads/thread-updates-only/runs/stream",
                """
                {"input":{"messages":[{"type":"human","content":"no message event"}]},"stream_mode":["updates"],"on_disconnect":"continue"}
                """);
        String runId = updates.headers().firstValue("content-location").orElseThrow().replaceFirst(".*/", "");

        assertThat(updates.statusCode()).isEqualTo(200);
        assertThat(updates.body()).contains("event: metadata", "event: end");
        assertThat(updates.body()).doesNotContain("event: messages\n", "event: messages-tuple");
        assertThat(get("/api/threads/thread-isolated/runs/" + runId).statusCode()).isEqualTo(404);
        assertThat(postJson(
                                "/api/threads/thread-isolated/runs/" + runId
                                        + "/cancel?wait=0&action=interrupt",
                                "")
                        .statusCode())
                .isEqualTo(404);
        assertThat(get("/api/threads/thread-isolated/runs/" + runId + "/stream").statusCode())
                .isEqualTo(404);
    }

    @Test
    void preservesPlainStringHumanContentCompatibility() throws Exception {
        createThread("thread-string-content");

        HttpResponse<String> response = postJson(
                "/api/threads/thread-string-content/runs/stream",
                """
                {"input":{"messages":[{"role":"user","content":"plain string"}]},"stream_mode":["messages-tuple","updates","custom"]}
                """);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Haifa Agent SDK: plain string");
    }

    @Test
    void rejectsMalformedOrNonTextHumanContentWithoutPartialExtraction() throws Exception {
        createThread("thread-invalid-content-blocks");
        List<String> invalidContents = List.of(
                "\"\"",
                "\"   \"",
                "[]",
                "[{\"type\":\"text\",\"text\":\"   \"}]",
                "[{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.invalid/image.png\"}}]",
                "[{\"type\":\"text\"}]",
                "[{\"type\":\"text\",\"text\":42}]",
                "[{\"type\":\"text\",\"text\":\"kept\"},{\"type\":\"tool_use\",\"text\":\"must not be ignored\"}]",
                "[\"must not be coerced\"]",
                "{\"type\":\"text\",\"text\":\"not an array\"}",
                "42",
                "true",
                "null");

        for (String content : invalidContents) {
            HttpResponse<String> response = postJson(
                    "/api/threads/thread-invalid-content-blocks/runs/stream",
                    "{\"input\":{\"messages\":[{\"type\":\"human\",\"content\":" + content + "}]}}");

            assertThat(response.statusCode()).as("content=%s", content).isEqualTo(422);
            assertThat(json.readTree(response.body()).path("detail").asText())
                    .as("content=%s", content)
                    .isEqualTo("input.messages requires a human message");
        }
    }

    @Test
    void failedHaifaRunEmitsErrorThenEndAndReportsErrorStatus() throws Exception {
        createThread("thread-failure");

        HttpResponse<String> response = client.send(
                runRequest("thread-failure", "__fail__"), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("event: error");
        // The SDK deliberately projects provider exceptions to a stable, non-sensitive error.
        assertThat(response.body()).contains("\"message\":\"Model call failed\"");
        assertThat(response.body()).containsPattern("event: end\\ndata: null\\nid: \\d+-0\\n\\n$");
        String location = response.headers().firstValue("content-location").orElseThrow();
        assertThat(getJson(location).path("status").asText()).isEqualTo("error");
    }

    @Test
    void cancelCallsHaifaHandleAndSecondCancelConflicts() throws Exception {
        createThread("thread-cancel");
        HttpResponse<InputStream> stream = client.send(
                runRequest("thread-cancel", "__slow__"), HttpResponse.BodyHandlers.ofInputStream());
        String location = stream.headers().firstValue("content-location").orElseThrow();

        HttpResponse<String> unsupportedRollback =
                postJson(location + "/cancel?wait=false&action=rollback", "");
        assertThat(unsupportedRollback.statusCode()).isEqualTo(422);
        HttpResponse<String> cancelled =
                postJson(location + "/cancel?wait=false&action=interrupt", "");
        assertThat(cancelled.statusCode()).isEqualTo(202);
        String transcript;
        try (InputStream input = stream.body()) {
            transcript = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(transcript).contains("event: end");
        awaitStatus(location, "interrupted");
        JsonNode listed = getJson("/api/threads/thread-cancel/runs?limit=10&offset=0");
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).path("status").asText()).isEqualTo("interrupted");

        HttpResponse<String> duplicate =
                postJson(location + "/cancel?wait=false&action=interrupt", "");
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(json.readTree(duplicate.body()).path("detail").asText())
                .contains("not cancellable", "interrupted");
    }

    @Test
    void metadataIsPublishedOnlyAfterTheDurableRunHasStarted() throws Exception {
        // DeerFlow's worker calls try_start (pending -> running) before publishing
        // `metadata`; the frozen UI reads the Run and history as soon as it sees it.
        createThread("thread-start-order");
        HttpResponse<InputStream> stream = client.send(
                runRequest("thread-start-order", "__slow__"), HttpResponse.BodyHandlers.ofInputStream());
        String location = stream.headers().firstValue("content-location").orElseThrow();
        try (InputStream input = stream.body()) {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
            assertThat(reader.readLine()).isEqualTo("event: metadata");

            assertThat(getJson(location).path("status").asText()).isEqualTo("running");
            HttpResponse<String> history = postJson(
                    "/api/threads/thread-start-order/history", "{\"limit\":10}");
            assertThat(history.statusCode()).isEqualTo(200);
            assertThat(history.body()).contains("__slow__");

            assertThat(postJson(location + "/cancel?wait=false&action=interrupt", "").statusCode())
                    .isEqualTo(202);
        }
        awaitStatus(location, "interrupted");
    }

    @Test
    void cancelAcceptsFrozenSdkNumericFalseButRejectsTruthyWait() throws Exception {
        createThread("thread-sdk-cancel-query");
        HttpResponse<InputStream> stream = client.send(
                runRequest("thread-sdk-cancel-query", "__slow__"),
                HttpResponse.BodyHandlers.ofInputStream());
        String location = stream.headers().firstValue("content-location").orElseThrow();

        assertThat(postJson(location + "/cancel?wait=true&action=interrupt", "").statusCode())
                .isEqualTo(422);
        assertThat(postJson(location + "/cancel?wait=0&action=interrupt", "").statusCode())
                .isEqualTo(202);
        try (InputStream input = stream.body()) {
            assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("event: end");
        }
        awaitStatus(location, "interrupted");
    }

    @Test
    void disconnectContinueKeepsActiveRunFenceAndRejectsSecondRun() throws Exception {
        createThread("thread-continue");
        HttpRequest firstRequest = runRequest("thread-continue", "__slow__", "continue");
        HttpResponse<InputStream> first = client.send(
                firstRequest, HttpResponse.BodyHandlers.ofInputStream());
        String location = first.headers().firstValue("content-location").orElseThrow();
        first.body().close();

        Thread.sleep(75);
        HttpResponse<String> second = client.send(
                runRequest("thread-continue", "second run"), HttpResponse.BodyHandlers.ofString());
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(json.readTree(second.body()).path("detail").asText())
                .isEqualTo("Thread already has an active run");

        HttpResponse<String> cancelled =
                postJson(location + "/cancel?wait=false&action=interrupt", "");
        assertThat(cancelled.statusCode()).isEqualTo(202);
        awaitStatus(location, "interrupted");
    }

    @Test
    void disconnectedContinueRunNaturallyConvergesThreadBackToIdle() throws Exception {
        createThread("thread-continue-complete");
        HttpResponse<InputStream> first = client.send(
                runRequest("thread-continue-complete", "__continue_short__", "continue"),
                HttpResponse.BodyHandlers.ofInputStream());
        first.body().close();

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        String last = "";
        while (System.nanoTime() < deadline) {
            last = getJson("/api/threads/thread-continue-complete").path("status").asText();
            if (last.equals("idle")) {
                break;
            }
            Thread.sleep(20);
        }
        assertThat(last).isEqualTo("idle");

        HttpResponse<String> search = postJson(
                "/api/threads/search",
                "{\"status\":\"idle\",\"metadata\":{},\"limit\":100,\"offset\":0}");
        assertThat(search.statusCode()).isEqualTo(200);
        assertThat(json.readTree(search.body()).findValuesAsText("thread_id"))
                .contains("thread-continue-complete");
    }

    @Test
    void lastEventIdReplayIsStableOrderedAndIdempotent() throws Exception {
        createThread("thread-replay");
        HttpResponse<String> created = client.send(
                runRequest("thread-replay", "replay me"), HttpResponse.BodyHandlers.ofString());
        String location = created.headers().firstValue("content-location").orElseThrow();
        List<Long> createdIds = sseIds(created.body());
        assertThat(createdIds).isNotEmpty().isSorted();
        long terminalSequence = createdIds.getLast();

        HttpResponse<String> firstReplay = join(location, (terminalSequence - 1) + "-0");
        HttpResponse<String> duplicateReplay = join(location, (terminalSequence - 1) + "-0");

        assertThat(firstReplay.statusCode()).isEqualTo(200);
        assertThat(firstReplay.body()).contains("Haifa Agent SDK: replay me");
        assertThat(sseIds(firstReplay.body()))
                .isNotEmpty()
                .allMatch(sequence -> sequence == terminalSequence)
                .isSorted();
        assertThat(duplicateReplay.body()).isEqualTo(firstReplay.body());

        HttpResponse<String> terminalResume = join(location, terminalSequence + "-0");
        assertThat(terminalResume.body()).isEqualTo("event: end\ndata: null\n\n");
    }

    @Test
    void creatorDisconnectContinueRecoversWithGapAuthoritativeViewAndDurableState()
            throws Exception {
        createThread("thread-gap");
        HttpResponse<InputStream> created = client.send(
                runRequest("thread-gap", "__continue_short__", "continue"),
                HttpResponse.BodyHandlers.ofInputStream());
        String location = created.headers().firstValue("content-location").orElseThrow();
        String runId = location.substring(location.lastIndexOf('/') + 1);
        created.body().close();
        awaitStatus(location, "success");

        HttpResponse<String> gap = join(location, null);
        assertThat(gap.body()).contains("event: gap", "\"code\":\"stream_replay_gap\"");
        JsonNode gapPayload = firstEventData(gap.body(), "gap");
        assertThat(gapPayload.path("run_id").asText()).isEqualTo(runId);
        assertThat(gapPayload.path("recovery").asText()).isEqualTo("reload_durable_state");
        assertThat(gapPayload.path("earliest_available_event_id").asText()).matches("\\d+-0");
        assertThat(gapPayload.path("latest_available_event_id").asText()).matches("\\d+-0");
        assertThat(gapPayload.path("authoritative_run_view").path("status").asText())
                .isEqualTo("success");
        assertThat(gapPayload.path("authoritative_run_view").path("output").asText())
                .isEqualTo("Haifa Agent SDK: __continue_short__");

        JsonNode state = getJson("/api/threads/thread-gap/state");
        assertThat(state.path("values").path("messages").get(1).path("content").asText())
                .isEqualTo("Haifa Agent SDK: __continue_short__");
        HttpResponse<String> resumed =
                join(location, gapPayload.path("latest_available_event_id").asText());
        assertThat(resumed.body()).isEqualTo("event: end\ndata: null\n\n");
        System.out.println("W05_SSE_EVIDENCE run=" + runId
                + " requested=0-0 earliest="
                + gapPayload.path("earliest_available_event_id").asText()
                + " latest="
                + gapPayload.path("latest_available_event_id").asText());
    }

    @Test
    void observerDisconnectNeverCancelsCreatorRun() throws Exception {
        createThread("thread-observer");
        HttpResponse<InputStream> creator = client.send(
                runRequest("thread-observer", "__slow__", "continue"),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(creator.statusCode()).isEqualTo(200);
        String location = creator.headers().firstValue("content-location").orElseThrow();

        HttpResponse<InputStream> observer =
                client.send(joinRequest(location, "-1"), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(observer.statusCode()).isEqualTo(200);
        observer.body().close();
        Thread.sleep(75);
        assertThat(getJson(location).path("status").asText()).isEqualTo("running");
        assertThat(postJson(location + "/cancel?wait=false&action=interrupt", "").statusCode())
                .isEqualTo(202);
        String creatorTranscript;
        try (InputStream input = creator.body()) {
            creatorTranscript = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(creatorTranscript).contains("event: end");
        awaitStatus(location, "interrupted");
    }

    @Test
    void creatorDisconnectCancelInterruptsRunWhileObserverDisconnectDoesNot() throws Exception {
        createThread("thread-creator-cancel");
        HttpResponse<InputStream> creator = client.send(
                runRequest("thread-creator-cancel", "__slow__", "cancel"),
                HttpResponse.BodyHandlers.ofInputStream());
        String location = creator.headers().firstValue("content-location").orElseThrow();
        creator.body().close();

        awaitStatus(location, "interrupted");
    }

    @Test
    void rejectsMissingThreadUnsupportedModeAndMissingHumanMessage() throws Exception {
        assertThat(client.send(
                                runRequest("missing", "hello"),
                                HttpResponse.BodyHandlers.ofString())
                        .statusCode())
                .isEqualTo(404);
        createThread("thread-invalid");
        HttpResponse<String> unsupported = postJson(
                "/api/threads/thread-invalid/runs/stream",
                "{\"assistant_id\":\"lead_agent\",\"input\":{\"messages\":[{\"type\":\"human\",\"content\":\"hi\"}]},\"stream_mode\":[\"events\"]}");
        assertThat(unsupported.statusCode()).isEqualTo(422);
        assertThat(unsupported.body()).contains("Unsupported stream mode(s): events");
        HttpResponse<String> noMessage = postJson(
                "/api/threads/thread-invalid/runs/stream",
                "{\"assistant_id\":\"lead_agent\",\"input\":{\"messages\":[]}}");
        assertThat(noMessage.statusCode()).isEqualTo(422);
        HttpResponse<String> invalidDisconnect = postJson(
                "/api/threads/thread-invalid/runs/stream",
                "{\"input\":{\"messages\":[{\"type\":\"human\",\"content\":\"hi\"}]},\"on_disconnect\":\"detach\"}");
        assertThat(invalidDisconnect.statusCode()).isEqualTo(422);
        HttpResponse<String> malformed = postJson("/api/threads", "not-json");
        assertThat(malformed.statusCode()).isEqualTo(422);
        assertThat(json.readTree(malformed.body()).path("detail").asText())
                .isEqualTo("Invalid JSON body");

        HttpResponse<String> created = client.send(
                runRequest("thread-invalid", "protocol boundary"),
                HttpResponse.BodyHandlers.ofString());
        String location = created.headers().firstValue("content-location").orElseThrow();
        HttpResponse<String> getAction = client.send(
                HttpRequest.newBuilder(baseUri.resolve(location + "/stream?action=interrupt"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(getAction.statusCode()).isEqualTo(405);
        assertThat(getAction.headers().firstValue("allow")).contains("POST");

        HttpResponse<String> invalidCursor = join(location, "not-a-cursor");
        assertThat(invalidCursor.statusCode()).isEqualTo(409);
        assertThat(json.readTree(invalidCursor.body()).path("detail").asText())
                .isEqualTo("Invalid Last-Event-ID");
        for (String rejectedCursor : List.of("", "-2", "1.5-0", "other-run/1-0", "1-1")) {
            HttpResponse<String> rejected = join(location, rejectedCursor);
            assertThat(rejected.statusCode()).as(rejectedCursor).isEqualTo(409);
            assertThat(json.readTree(rejected.body()).path("detail").asText())
                    .as(rejectedCursor)
                    .isEqualTo("Invalid Last-Event-ID");
        }
    }

    private void createThread(String threadId) throws Exception {
        assertThat(postJson("/api/threads", "{\"thread_id\":\"" + threadId + "\"}").statusCode())
                .isEqualTo(200);
    }

    private ConversationTurn turn(long sequence, MessageRole role) {
        return new ConversationTurn(
                "message-" + sequence,
                role,
                Optional.empty(),
                sequence,
                List.of(new TextPart("turn " + sequence, "text/plain")),
                MessageVisibility.USER_VISIBLE,
                Instant.EPOCH.plusSeconds(sequence));
    }

    private HttpRequest runRequest(String threadId, String message) {
        return runRequest(threadId, message, "cancel");
    }

    private HttpRequest runRequest(String threadId, String message, String onDisconnect) {
        String body = """
                {"assistant_id":"lead_agent","input":{"messages":[{"id":"human-1","type":"human","content":"%s"}]},"stream_mode":["messages-tuple","updates","custom"],"on_disconnect":"%s","multitask_strategy":"reject"}
                """.formatted(message, onDisconnect);
        return HttpRequest.newBuilder(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> join(String location, String lastEventId) throws Exception {
        return client.send(joinRequest(location, lastEventId), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest joinRequest(String location, String lastEventId) {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(location + "/stream")).GET();
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }
        return request.build();
    }

    private List<Long> sseIds(String transcript) {
        Matcher matcher = Pattern.compile("(?m)^id: (\\d+)-0$").matcher(transcript);
        List<Long> ids = new ArrayList<>();
        while (matcher.find()) {
            ids.add(Long.parseLong(matcher.group(1)));
        }
        return ids;
    }

    private JsonNode firstEventData(String transcript, String event) throws Exception {
        Matcher matcher = Pattern.compile(
                        "event: " + Pattern.quote(event) + "\\ndata: ([^\\n]+)\\n\\n")
                .matcher(transcript);
        assertThat(matcher.find()).isTrue();
        return json.readTree(matcher.group(1));
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private void awaitStatus(String path, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        String last = "";
        while (System.nanoTime() < deadline) {
            last = getJson(path).path("status").asText();
            if (last.equals(expected)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Run status was " + last + ", expected " + expected);
    }
}

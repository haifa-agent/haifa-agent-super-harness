package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRunStatus;
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
    void historicalTranscriptRemainsReadableAndNextTurnUsesCurrentModelAfterRevisionChanges() throws Exception {
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
                runRequest("thread-historical-binding", "after provider revision change"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(driftedRun.statusCode()).isEqualTo(200);
        String nextRunId = driftedRun.headers().firstValue("content-location").orElseThrow().replaceFirst(".*/", "");
        assertThat(nextRunId).isNotEqualTo(runId);
        assertThat(driftedRun.body()).contains("after provider revision change");
        assertThat(get("/api/threads/thread-historical-binding/messages/page").body())
                .contains("before provider restart", "after provider revision change", runId, nextRunId);
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
        assertThat(observer.headers().firstValue("content-location")).contains(location);
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

    @Test
    void fallbackTitleMatchesTheFrozenLocalRule() {
        assertThat(DeerFlowThreadRunApi.fallbackTitle(null)).isEqualTo("New Conversation");
        assertThat(DeerFlowThreadRunApi.fallbackTitle("   ")).isEqualTo("New Conversation");
        String fifty = "t".repeat(50);
        assertThat(DeerFlowThreadRunApi.fallbackTitle(fifty)).isEqualTo(fifty);
        // The bound includes the ellipsis: 47 body characters plus "...".
        assertThat(DeerFlowThreadRunApi.fallbackTitle("t".repeat(51)))
                .isEqualTo("t".repeat(47) + "...");
        assertThat(DeerFlowThreadRunApi.fallbackTitle("t".repeat(47) + "..."))
                .hasSize(50);
        // A configured max_chars below the frozen ceiling truncates earlier but still includes
        // the ellipsis inside the bound.
        assertThat(DeerFlowThreadRunApi.fallbackTitle("t".repeat(40), 10))
                .isEqualTo("t".repeat(7) + "...");
        assertThat(DeerFlowThreadRunApi.fallbackTitle("  short question  "))
                .isEqualTo("short question");
        // Frozen Python counts Unicode code points. An emoji is one code point but two UTF-16
        // code units, so a naive String.length bound would cut it in half.
        String emoji = "\uD83D\uDE00";
        assertThat(DeerFlowThreadRunApi.fallbackTitle(emoji.repeat(50))).isEqualTo(emoji.repeat(50));
        assertThat(DeerFlowThreadRunApi.fallbackTitle(emoji.repeat(51)))
                .isEqualTo(emoji.repeat(47) + "...");
        assertThat(DeerFlowThreadRunApi.fallbackTitle(emoji.repeat(51)))
                .hasSize(47 * 2 + 3);
        // Mixed astral + BMP text counts each code point once.
        String mixed = emoji.repeat(30) + "abcdefghijklmnopqrstuvwxyz";
        assertThat(DeerFlowThreadRunApi.fallbackTitle(mixed))
                .isEqualTo(emoji.repeat(30) + "abcdefghijklmnopq" + "...");
    }

    @Test
    void shouldGenerateFallbackTitleMatchesFrozenTitleMiddleware() {
        assertThat(DeerFlowThreadRunApi.shouldGenerateFallbackTitle(
                        AgentRunStatus.COMPLETED, true))
                .isTrue();
        assertThat(DeerFlowThreadRunApi.shouldGenerateFallbackTitle(
                        AgentRunStatus.COMPLETED, false))
                .isFalse();
        // allow_partial_exchange=True accepts a lone first user message on cancellation.
        assertThat(DeerFlowThreadRunApi.shouldGenerateFallbackTitle(
                        AgentRunStatus.CANCELLED, false))
                .isTrue();
        assertThat(DeerFlowThreadRunApi.shouldGenerateFallbackTitle(AgentRunStatus.FAILED, true))
                .isFalse();
        assertThat(DeerFlowThreadRunApi.shouldGenerateFallbackTitle(AgentRunStatus.TIMEOUT, true))
                .isFalse();
    }

    @Test
    void titlePersistenceFailureIsReportedSanitizedWithoutLeakingRequestText() throws Exception {
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true, StandardCharsets.UTF_8));
        boolean ok;
        try {
            ok = DeerFlowThreadRunApi.runBestEffort("run-y", () -> {
                throw new java.io.IOException(
                        "sensitive request text from the user\r\nW03_INJECTED_LINE");
            });
        } finally {
            System.setErr(original);
        }
        String log = captured.toString(StandardCharsets.UTF_8);
        assertThat(ok).isFalse();
        // The diagnostic keeps only the stable code, the server-generated run id and the class.
        assertThat(log).contains("W03_TITLE_PERSIST_FAILURE", "run-y", "IOException");
        assertThat(log).doesNotContain(
                "sensitive request text", "W03_INJECTED_LINE", "thread-x");
    }

    @Test
    void firstCompletedExchangeStreamsAndPersistsTheLocalFallbackTitle() throws Exception {
        createThread("thread-title-first");
        var releaseObserver = new java.util.concurrent.CountDownLatch(1);
        api.threadRunApi().setDurableTurnScannerForTests(threadId -> {
            if (Thread.currentThread().getName().startsWith("super-harness-title-completion-")) {
                try {
                    if (!releaseObserver.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        return 0;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return 0;
                }
            }
            return 1;
        });
        try {
            HttpResponse<String> run = client.send(
                    runRequest("thread-title-first", "How do I page through results?"),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(run.statusCode()).isEqualTo(200);
            assertThat(run.body()).contains("event: messages");
            assertThat(run.body()).contains("event: updates");
            assertThat(run.body()).contains("\"title\":\"How do I page through results?\"");
            // The title update must be written before the terminal end frame.
            assertThat(run.body().indexOf("event: updates"))
                    .isLessThan(run.body().lastIndexOf("event: end"));

            // Freeze the independent observer: the UI's immediate post-end read must still see
            // the durable title, rather than the async observer winning only after that read.
            JsonNode thread = getJson("/api/threads/thread-title-first");
            assertThat(thread.path("metadata").path("title").asText())
                    .isEqualTo("How do I page through results?");
            assertThat(getJson("/api/threads/thread-title-first/state").path("values").path("title").asText())
                    .isEqualTo("How do I page through results?");
        } finally {
            releaseObserver.countDown();
            api.threadRunApi().setDurableTurnScannerForTests(null);
        }
        awaitTitleObserversIdle();
    }

    @Test
    void failedRunDoesNotPublishATitle() throws Exception {
        createThread("thread-title-failed");

        HttpResponse<String> run = client.send(
                runRequest("thread-title-failed", "__fail__", "continue"),
                HttpResponse.BodyHandlers.ofString());

        assertThat(run.body()).doesNotContain("event: updates");
        JsonNode thread = getJson("/api/threads/thread-title-failed");
        assertThat(thread.path("values").has("title")).isFalse();
        assertThat(thread.path("metadata").has("title")).isFalse();
    }

    @Test
    void secondSuccessfulTurnAfterAFailedFirstTurnDoesNotAutoTitle() throws Exception {
        createThread("thread-title-two-turns");
        HttpResponse<String> first = client.send(
                runRequest("thread-title-two-turns", "__fail__", "continue"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(first.body()).doesNotContain("event: updates");
        awaitThreadStatus("thread-title-two-turns", "idle");
        awaitTitleObserversIdle();

        // The failed first turn's user message is durable, so the thread already has one user Turn
        // and the frozen middleware must never generate a title from a second user input.
        assertThat(humanTurnContents("thread-title-two-turns")).containsExactly("__fail__");

        HttpResponse<String> second = client.send(
                runRequest("thread-title-two-turns", "second prompt", "continue"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.body()).doesNotContain("event: updates");
        awaitThreadStatus("thread-title-two-turns", "idle");
        awaitTitleObserversIdle();

        assertThat(humanTurnContents("thread-title-two-turns"))
                .containsExactly("__fail__", "second prompt");
        JsonNode thread = getJson("/api/threads/thread-title-two-turns");
        assertThat(thread.path("values").has("title")).isFalse();
        assertThat(thread.path("metadata").has("title")).isFalse();

        // The first-turn boundary is re-derived from the durable transcript after a restart, so a
        // post-restart turn still sees more than one user Turn and stays untitled.
        api.close();
        startApi();
        assertThat(humanTurnContents("thread-title-two-turns"))
                .containsExactly("__fail__", "second prompt");
        HttpResponse<String> third = client.send(
                runRequest("thread-title-two-turns", "third prompt", "continue"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(third.body()).doesNotContain("event: updates");
        awaitThreadStatus("thread-title-two-turns", "idle");
        awaitTitleObserversIdle();
        assertThat(getJson("/api/threads/thread-title-two-turns").path("values").has("title"))
                .isFalse();
        assertThat(getJson("/api/threads/thread-title-two-turns").path("metadata").has("title"))
                .isFalse();
    }

    @Test
    void titleDurableReadFailureDoesNotBlockTheRunOrPersistATitle() throws Exception {
        createThread("thread-title-read-failure");
        api.threadRunApi().setDurableTurnScannerForTests(threadId -> {
            throw new IllegalStateException(
                    "injected SQLite read failure\r\nW03_INJECTED_LINE for sensitive prompt text");
        });
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true, StandardCharsets.UTF_8));
        HttpResponse<String> run;
        try {
            run = client.send(
                    runRequest("thread-title-read-failure", "sensitive prompt text"),
                    HttpResponse.BodyHandlers.ofString());
            awaitTitleObserversIdle();
        } finally {
            System.setErr(original);
            api.threadRunApi().setDurableTurnScannerForTests(null);
        }

        // The auxiliary durable read failing must never block admission or fail the main Run.
        assertThat(run.statusCode()).isEqualTo(200);
        assertThat(run.body()).doesNotContain("__title__");
        String location = run.headers().firstValue("content-location").orElseThrow();
        assertThat(getJson(location).path("status").asText()).isEqualTo("success");
        JsonNode thread = getJson("/api/threads/thread-title-read-failure");
        assertThat(thread.path("values").has("title")).isFalse();
        assertThat(thread.path("metadata").has("title")).isFalse();

        // Diagnostics stay sanitized: the stable code and error class only. The client-controlled
        // thread_id, the prompt, the exception message and any injected line must not appear.
        // (A CR/LF thread_id cannot drive a Run end-to-end because the frozen Content-Location
        // response header would carry it; removing the parameter closes that vector structurally.)
        String log = captured.toString(StandardCharsets.UTF_8);
        assertThat(log).contains("W03_TITLE_TURN_READ_FAILURE", "IllegalStateException");
        assertThat(log).doesNotContain(
                "thread-title-read-failure",
                "sensitive prompt text",
                "injected SQLite read failure",
                "W03_INJECTED_LINE");
    }

    @Test
    void longFirstMessageIsTruncatedToTheFrozenCodeUnitBoundary() throws Exception {
        createThread("thread-title-long");
        String longMessage = "q".repeat(60);

        HttpResponse<String> run = client.send(
                runRequest("thread-title-long", longMessage),
                HttpResponse.BodyHandlers.ofString());

        assertThat(run.statusCode()).isEqualTo(200);
        awaitTitle("thread-title-long", "q".repeat(47) + "...");
    }

    @Test
    void interruptedFirstTurnStillGetsThePartialExchangeFallbackTitle() throws Exception {
        createThread("thread-title-cancelled");
        try (InputStream stream = client.send(
                        runRequest("thread-title-cancelled", "__slow__", "cancel"),
                        HttpResponse.BodyHandlers.ofInputStream())
                .body()) {
            // Closing the creator transport with on_disconnect=cancel interrupts the first turn
            // before any assistant message is durably committed.
            // The body is intentionally not consumed.
        }
        awaitThreadStatus("thread-title-cancelled", "idle");
        // The partial-exchange rule only applies when the lone user Turn is actually durable;
        // assert the real transcript, not just the Run status.
        assertThat(humanTurnContents("thread-title-cancelled")).contains("__slow__");
        awaitTitle("thread-title-cancelled", "__slow__");
    }

    @Test
    void titleIsPersistedEvenWhenTheStreamOmitsTheUpdatesMode() throws Exception {
        createThread("thread-title-no-updates");
        HttpResponse<String> run = client.send(
                runRequestWithModes("thread-title-no-updates", "no updates subscription"),
                HttpResponse.BodyHandlers.ofString());

        assertThat(run.statusCode()).isEqualTo(200);
        assertThat(run.body()).doesNotContain("event: updates");
        awaitTitle("thread-title-no-updates", "no updates subscription");
    }

    @Test
    void titleIsPersistedWhenTheCreatorDisconnectsBeforeCompletion() throws Exception {
        createThread("thread-title-disconnect");
        try (InputStream stream = client.send(
                        runRequest("thread-title-disconnect", "__slow__", "continue"),
                        HttpResponse.BodyHandlers.ofInputStream())
                .body()) {
            // Disconnect immediately; with on_disconnect=continue the Run finishes on its own.
        }
        awaitThreadStatus("thread-title-disconnect", "idle");
        awaitTitle("thread-title-disconnect", "__slow__");
    }

    @Test
    void autoTitleSurvivesARestart() throws Exception {
        createThread("thread-title-restart");
        client.send(
                runRequest("thread-title-restart", "restart recovery prompt"),
                HttpResponse.BodyHandlers.ofString());
        awaitTitle("thread-title-restart", "restart recovery prompt");

        api.close();
        startApi();
        assertThat(getJson("/api/threads/thread-title-restart").path("values").path("title").asText())
                .isEqualTo("restart recovery prompt");
        assertThat(getJson("/api/threads/thread-title-restart").path("metadata").path("title").asText())
                .isEqualTo("restart recovery prompt");
    }

    @Test
    void concurrentRenamesWhileARunIsInFlightAllRejectAtomically() throws Exception {
        createThread("thread-title-concurrent");
        try (InputStream stream = client.send(
                        runRequest("thread-title-concurrent", "__slow__", "cancel"),
                        HttpResponse.BodyHandlers.ofInputStream())
                .body()) {
            awaitThreadStatus("thread-title-concurrent", "busy");
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(8);
            try {
                List<java.util.concurrent.Future<Integer>> futures = new ArrayList<>();
                for (int index = 0; index < 16; index++) {
                    futures.add(pool.submit(() -> postJson(
                                    "/api/threads/thread-title-concurrent/state",
                                    "{\"values\":{\"title\":\"Rename " + java.util.UUID.randomUUID() + "\"}}")
                            .statusCode()));
                }
                for (java.util.concurrent.Future<Integer> future : futures) {
                    assertThat(future.get()).isEqualTo(409);
                }
            } finally {
                pool.shutdownNow();
            }
            assertThat(getJson("/api/threads/thread-title-concurrent").path("values").has("title"))
                    .isFalse();
        }
    }

    @Test
    void explicitRenameWinsAndSurvivesRestart() throws Exception {
        createThread("thread-title-rename");
        client.send(runRequest("thread-title-rename", "first turn"), HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> renamed = postJson(
                "/api/threads/thread-title-rename/state",
                "{\"values\":{\"title\":\"  Renamed by user  \"}}");
        assertThat(renamed.statusCode()).isEqualTo(200);
        JsonNode renamedState = json.readTree(renamed.body());
        assertThat(renamedState.path("values").path("title").asText()).isEqualTo("Renamed by user");
        assertThat(renamedState.has("metadata")).isTrue();
        assertThat(renamedState.has("checkpoint")).isTrue();
        assertThat(getJson("/api/threads/thread-title-rename").path("values").path("title").asText())
                .isEqualTo("Renamed by user");

        // A later run must not overwrite the explicit user title, and must not re-emit one.
        HttpResponse<String> second = client.send(
                runRequest("thread-title-rename", "second turn"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(second.body()).doesNotContain("event: updates");
        assertThat(getJson("/api/threads/thread-title-rename").path("values").path("title").asText())
                .isEqualTo("Renamed by user");

        api.close();
        startApi();
        assertThat(getJson("/api/threads/thread-title-rename").path("values").path("title").asText())
                .isEqualTo("Renamed by user");
        assertThat(getJson("/api/threads/thread-title-rename").path("metadata").path("title").asText())
                .isEqualTo("Renamed by user");
    }

    @Test
    void renameWhileARunIsInFlightIsRejected() throws Exception {
        createThread("thread-title-busy");
        try (InputStream stream = client.send(
                        runRequest("thread-title-busy", "__slow__", "continue"),
                        HttpResponse.BodyHandlers.ofInputStream())
                .body()) {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            String status = "idle";
            while (System.nanoTime() < deadline) {
                status = getJson("/api/threads/thread-title-busy").path("status").asText();
                if (status.equals("busy")) break;
                Thread.sleep(10);
            }
            assertThat(status).isEqualTo("busy");

            HttpResponse<String> rename = postJson(
                    "/api/threads/thread-title-busy/state",
                    "{\"values\":{\"title\":\"Too late\"}}");
            assertThat(rename.statusCode()).isEqualTo(409);
            assertThat(json.readTree(rename.body()).path("detail").asText())
                    .isEqualTo("Thread has an active run");
        }
    }

    @Test
    void invalidRenamePayloadsAreRejected() throws Exception {
        createThread("thread-title-invalid");

        assertThat(postJson("/api/threads/thread-title-invalid/state", "{}").statusCode())
                .isEqualTo(422);
        assertThat(postJson("/api/threads/thread-title-invalid/state", "{\"values\":\"x\"}")
                        .statusCode())
                .isEqualTo(422);
        assertThat(postJson(
                                "/api/threads/thread-title-invalid/state",
                                "{\"values\":{\"title\":\"   \"}}")
                        .statusCode())
                .isEqualTo(422);
        assertThat(postJson(
                                "/api/threads/thread-title-invalid/state",
                                "{\"values\":{\"title\":5}}")
                        .statusCode())
                .isEqualTo(422);
        // Unknown state fields fail closed instead of being silently ignored.
        assertThat(postJson(
                                "/api/threads/thread-title-invalid/state",
                                "{\"values\":{\"goal\":{\"objective\":\"x\"}}}")
                        .statusCode())
                .isEqualTo(422);
        assertThat(postJson("/api/threads/thread-title-invalid/state", "{\"unknown\":1}")
                        .statusCode())
                .isEqualTo(422);
        assertThat(postJson("/api/threads/missing-thread/state", "{\"values\":{\"title\":\"x\"}}")
                        .statusCode())
                .isEqualTo(404);
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

    private HttpRequest runRequestWithModes(String threadId, String message) {
        String body = """
                {"assistant_id":"lead_agent","input":{"messages":[{"id":"human-1","type":"human","content":"%s"}]},"stream_mode":["messages-tuple"],"on_disconnect":"continue","multitask_strategy":"reject"}
                """.formatted(message);
        return HttpRequest.newBuilder(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private void awaitThreadStatus(String threadId, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        String last = "";
        while (System.nanoTime() < deadline) {
            last = getJson("/api/threads/" + threadId).path("status").asText();
            if (last.equals(expected)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Thread status was '" + last + "', expected '" + expected + "'");
    }

    private void awaitTitleObserversIdle() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (api.threadRunApi().titleCompletionObserverCount() == 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Title completion observers did not settle");
    }

    private List<String> humanTurnContents(String threadId) throws Exception {
        JsonNode page = getJson("/api/threads/" + threadId + "/messages/page?limit=50");
        List<String> humans = new ArrayList<>();
        for (JsonNode row : page.path("data")) {
            JsonNode content = row.path("content");
            if (content.path("type").asText().equals("human")) {
                humans.add(content.path("content").asText());
            }
        }
        return humans;
    }

    private void awaitTitle(String threadId, String expectedTitle) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        String last = "";
        while (System.nanoTime() < deadline) {
            JsonNode values = getJson("/api/threads/" + threadId).path("values");
            last = values.has("title") ? values.path("title").asText() : "";
            if (last.equals(expectedTitle)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Thread title was '" + last + "', expected '" + expectedTitle + "'");
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

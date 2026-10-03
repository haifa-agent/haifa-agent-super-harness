package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.superharness.server.run.HaifaRunBridge;
import io.haifa.agent.superharness.server.run.PersistentThreadCatalog;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Transcript and history projections, restart recovery and the run list. */
class DeerFlowThreadHistoryApiTest extends AbstractDeerFlowThreadRunApiTest {
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
    void failedCrossModelAdmissionAfterRestartKeepsOnlyTheRestoredBridge() throws Exception {
        HaifaRunBridge.ModelBinding original = LocalProbeModelTestSupport.binding();
        HaifaRunBridge.ModelBinding alternate = new HaifaRunBridge.ModelBinding(
                original.name(), "2.0.0", original.model(), original.snapshot());
        java.util.function.Function<JsonNode, HaifaRunBridge.ModelBinding> resolver = body ->
                "alternate-model".equals(body.path("context").path("model_name").asText())
                        ? alternate
                        : original;
        api.close();
        api = null;
        startApi(resolver);
        createThread("thread-failed-cross-model");
        assertThat(client.send(
                                runRequest("thread-failed-cross-model", "first model turn"),
                                HttpResponse.BodyHandlers.ofString())
                        .statusCode())
                .isEqualTo(200);

        api.close();
        api = null;
        startApi(resolver);
        assertThat(get("/api/threads/thread-failed-cross-model/messages/page").statusCode())
                .isEqualTo(200);
        assertThat(ownedBridgeCount()).isEqualTo(1);

        String failedBody = """
                {"input":{"messages":[{"type":"human","content":"%s"}]},
                "context":{"model_name":"alternate-model"}}
                """.formatted("x".repeat(32_001));
        HttpResponse<String> failed = postJson(
                "/api/threads/thread-failed-cross-model/runs/stream", failedBody);
        assertThat(failed.statusCode()).as(failed.body()).isEqualTo(500);
        assertThat(api.hasAttachedBridge("thread-failed-cross-model")).isTrue();
        assertThat(ownedBridgeCount()).isEqualTo(1);
        assertThat(client.send(
                                runRequest("thread-failed-cross-model", "retry on original model"),
                                HttpResponse.BodyHandlers.ofString())
                        .statusCode())
                .isEqualTo(200);

        // The admission fence restores the latest durable Run even without an explicit
        // historical read. That original bridge must remain attached after B is rejected.
        api.close();
        api = null;
        startApi(resolver);
        assertThat(ownedBridgeCount()).isZero();
        assertThat(postJson("/api/threads/thread-failed-cross-model/runs/stream", failedBody)
                        .statusCode())
                .isEqualTo(500);
        assertThat(api.hasAttachedBridge("thread-failed-cross-model")).isTrue();
        assertThat(ownedBridgeCount()).isEqualTo(1);
        assertThat(client.send(
                                runRequest("thread-failed-cross-model", "second retry on original model"),
                                HttpResponse.BodyHandlers.ofString())
                        .statusCode())
                .isEqualTo(200);
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
}

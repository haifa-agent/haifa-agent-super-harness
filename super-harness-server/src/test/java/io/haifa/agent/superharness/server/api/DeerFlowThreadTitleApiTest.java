package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.core.run.AgentRunStatus;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Automatic thread titles and explicit renames. */
class DeerFlowThreadTitleApiTest extends AbstractDeerFlowThreadRunApiTest {
    @Test
    void fallbackTitleMatchesTheFrozenLocalRule() {
        assertThat(DeerFlowConversationService.fallbackTitle(null)).isEqualTo("New Conversation");
        assertThat(DeerFlowConversationService.fallbackTitle("   ")).isEqualTo("New Conversation");
        String fifty = "t".repeat(50);
        assertThat(DeerFlowConversationService.fallbackTitle(fifty)).isEqualTo(fifty);
        // The bound includes the ellipsis: 47 body characters plus "...".
        assertThat(DeerFlowConversationService.fallbackTitle("t".repeat(51)))
                .isEqualTo("t".repeat(47) + "...");
        assertThat(DeerFlowConversationService.fallbackTitle("t".repeat(47) + "..."))
                .hasSize(50);
        // A configured max_chars below the frozen ceiling truncates earlier but still includes
        // the ellipsis inside the bound.
        assertThat(DeerFlowConversationService.fallbackTitle("t".repeat(40), 10))
                .isEqualTo("t".repeat(7) + "...");
        assertThat(DeerFlowConversationService.fallbackTitle("  short question  "))
                .isEqualTo("short question");
        // Frozen Python counts Unicode code points. An emoji is one code point but two UTF-16
        // code units, so a naive String.length bound would cut it in half.
        String emoji = "\uD83D\uDE00";
        assertThat(DeerFlowConversationService.fallbackTitle(emoji.repeat(50))).isEqualTo(emoji.repeat(50));
        assertThat(DeerFlowConversationService.fallbackTitle(emoji.repeat(51)))
                .isEqualTo(emoji.repeat(47) + "...");
        assertThat(DeerFlowConversationService.fallbackTitle(emoji.repeat(51)))
                .hasSize(47 * 2 + 3);
        // Mixed astral + BMP text counts each code point once.
        String mixed = emoji.repeat(30) + "abcdefghijklmnopqrstuvwxyz";
        assertThat(DeerFlowConversationService.fallbackTitle(mixed))
                .isEqualTo(emoji.repeat(30) + "abcdefghijklmnopq" + "...");
    }

    @Test
    void shouldGenerateFallbackTitleMatchesFrozenTitleMiddleware() {
        assertThat(DeerFlowConversationService.shouldGenerateFallbackTitle(
                        AgentRunStatus.COMPLETED, true))
                .isTrue();
        assertThat(DeerFlowConversationService.shouldGenerateFallbackTitle(
                        AgentRunStatus.COMPLETED, false))
                .isFalse();
        // allow_partial_exchange=True accepts a lone first user message on cancellation.
        assertThat(DeerFlowConversationService.shouldGenerateFallbackTitle(
                        AgentRunStatus.CANCELLED, false))
                .isTrue();
        assertThat(DeerFlowConversationService.shouldGenerateFallbackTitle(AgentRunStatus.FAILED, true))
                .isFalse();
        assertThat(DeerFlowConversationService.shouldGenerateFallbackTitle(AgentRunStatus.TIMEOUT, true))
                .isFalse();
    }

    @Test
    void titlePersistenceFailureIsReportedSanitizedWithoutLeakingRequestText() throws Exception {
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true, StandardCharsets.UTF_8));
        boolean ok;
        try {
            ok = DeerFlowConversationService.runBestEffort("run-y", () -> {
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
        api.threadRunApi().conversations().setDurableTurnScannerForTests(threadId -> {
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
            api.threadRunApi().conversations().setDurableTurnScannerForTests(null);
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
        api.threadRunApi().conversations().setDurableTurnScannerForTests(threadId -> {
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
            api.threadRunApi().conversations().setDurableTurnScannerForTests(null);
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

    @Test
    void userInputBoundaryIsModelFacingWhileHistoryAndTitleStayRaw() throws Exception {
        String threadId = "thread-input-boundary";
        assertThat(postJson("/api/threads", "{\"thread_id\":\"" + threadId + "\"}").statusCode())
                .isEqualTo(200);
        HttpResponse<String> run = client.send(
                runRequestWithModes(threadId, "boundary probe"), HttpResponse.BodyHandlers.ofString());
        assertThat(run.statusCode()).as(run.body()).isEqualTo(200);
        assertThat(run.body()).contains("Haifa Agent SDK: boundary probe");
        awaitThreadStatus(threadId, "idle");

        List<String> humans = humanTurnContents(threadId);
        assertThat(humans).contains("boundary probe");
        assertThat(String.join("\n", humans)).doesNotContain("BEGIN USER INPUT", "END USER INPUT");
        awaitTitle(threadId, "boundary probe");
    }
}

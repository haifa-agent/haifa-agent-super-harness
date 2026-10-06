package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.superharness.server.testsupport.ControlledProvider;
import io.haifa.agent.superharness.server.testsupport.DelegationHttpFixture;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * W05: while a Run is in flight, the pending user message projected into thread state/history must
 * reuse the client-generated human message id as {@code <client id>__user}, because the frozen UI
 * folds that reserved suffix back onto the optimistic {@code local-human-*} bubble. Without a
 * client id it keeps the synthetic {@code <run>-human-0}; after the Run settles the durable id is
 * unchanged (the client id never enters persistence or replay).
 */
class PendingHumanIdentityHttpTest {
    private Path dataRoot;
    private Path evidence;
    private GatewayFixture.HttpEvidence http;

    @BeforeEach
    void prepare() throws Exception {
        dataRoot = GatewayFixture.dataDirectory();
        evidence = GatewayFixture.evidenceDirectory();
        http = new GatewayFixture.HttpEvidence(evidence, Duration.ofSeconds(40));
    }

    @AfterEach
    void close() throws InterruptedException {
        if (http != null) {
            http.close();
        }
    }

    @Test
    void pendingHumanReusesClientIdWhileInFlightAndKeepsDurableIdAfterSettling() throws Exception {
        Files.createDirectories(evidence);
        String withClientThread = "pending-human-with-client-id";
        String withoutClientThread = "pending-human-without-client-id";
        Map<String, String> environment = Map.of(
                "HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W05_PENDING_KEY",
                "W05_PENDING_KEY", java.util.Base64.getEncoder().encodeToString(new byte[32]));

        AtomicReference<CountDownLatch> entered = new AtomicReference<>(new CountDownLatch(1));
        AtomicReference<CountDownLatch> release = new AtomicReference<>(new CountDownLatch(1));
        try (var provider = new ControlledProvider(evidence, true, (request, call) -> {
            entered.get().countDown();
            if (!release.get().await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("controlled model release timed out");
            }
            return ControlledProvider.stop("lead-" + call, "w08-model", "PENDING-HUMAN-ANSWER");
        })) {
            try (var api = GatewayFixture.production(dataRoot, 0, environment, evidence)) {
                URI base = api.base;
                DelegationHttpFixture.configure(http, base, provider.port());
                assertThat(http.request(base, "POST", "/api/threads",
                        "{\"thread_id\":\"" + withClientThread + "\"}").statusCode()).isEqualTo(200);
                assertThat(http.request(base, "POST", "/api/threads",
                        "{\"thread_id\":\"" + withoutClientThread + "\"}").statusCode()).isEqualTo(200);

                // -------- Run A: a client-generated human message id -------------------------
                BlockedRun blockedA = startBlockedRun(
                        base, withClientThread, runBody("local-human-A", "pending human with client id"),
                        "pending-human-with-id.sse", entered);
                JsonNode stateA = http.get(base, "/api/threads/" + withClientThread + "/state",
                        "pending-human-with-id-state.json");
                assertThat(pendingHumanId(stateA))
                        .as("the in-flight pending human must reuse the client id with the frozen __user suffix")
                        .isEqualTo("local-human-A__user");
                assertThat(historyHumanId(base, withClientThread, "pending-human-with-id-history.json"))
                        .isEqualTo("local-human-A__user");

                release.get().countDown();
                HttpResponse<String> responseA = finish(blockedA);
                JsonNode settledA = http.get(base, "/api/threads/" + withClientThread + "/state",
                        "pending-human-with-id-settled-state.json");
                List<JsonNode> settledMessagesA = DelegationHttpFixture.messages(settledA);
                assertThat(settledMessagesA).hasSize(2);
                String durableIdA = settledMessagesA.get(0).path("id").asText();
                assertThat(durableIdA)
                        .as("after settling the durable human id is the synthetic wire id, never the client id")
                        .isEqualTo(runId(responseA) + "-human-0")
                        .doesNotContain("local-human-A");
                assertThat(historyHumanId(base, withClientThread, "pending-human-with-id-settled-history.json"))
                        .isEqualTo(durableIdA);

                // -------- Run B: no client-generated human message id ------------------------
                entered.set(new CountDownLatch(1));
                release.set(new CountDownLatch(1));
                BlockedRun blockedB = startBlockedRun(
                        base, withoutClientThread, runBody(null, "pending human without client id"),
                        "pending-human-no-id.sse", entered);
                JsonNode stateB = http.get(base, "/api/threads/" + withoutClientThread + "/state",
                        "pending-human-no-id-state.json");
                String pendingIdB = pendingHumanId(stateB);
                assertThat(pendingIdB)
                        .as("without a client id the pending human keeps the synthetic wire id")
                        .endsWith("-human-0")
                        .doesNotContain("__user");

                release.get().countDown();
                finish(blockedB);
                JsonNode settledB = http.get(base, "/api/threads/" + withoutClientThread + "/state",
                        "pending-human-no-id-settled-state.json");
                assertThat(DelegationHttpFixture.messages(settledB)).hasSize(2);
                assertThat(settledB.path("values").path("messages").get(0).path("id").asText())
                        .as("the synthetic pending id equals the durable id the Run settles into")
                        .isEqualTo(pendingIdB);
            }
        }
    }

    private static String runBody(String humanId, String content) {
        String idField = humanId == null ? "" : "\"id\":\"" + humanId + "\",";
        return """
                {"assistant_id":"lead_agent","input":{"messages":[{%s"type":"human","content":"%s"}]},
                "stream_mode":["messages-tuple","updates","custom"],
                "context":{"mode":"flash","model_name":"w08"},
                "on_disconnect":"continue","multitask_strategy":"reject"}
                """.formatted(idField, content);
    }

    /**
     * Starts the SSE Run on a virtual thread and waits until the controlled model call is reached,
     * so the Run is provably in flight with its pending human projected. The model stays blocked
     * until the caller counts {@code release} down.
     */
    private BlockedRun startBlockedRun(
            URI base, String threadId, String runBody, String evidenceName,
            AtomicReference<CountDownLatch> entered) throws Exception {
        AtomicReference<HttpResponse<String>> response = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread stream = Thread.ofVirtual().start(() -> {
            try {
                response.set(http.request(base, "POST", "/api/threads/" + threadId + "/runs/stream",
                        runBody, evidenceName));
            } catch (Exception exception) {
                failure.set(exception);
            }
        });
        assertThat(entered.get().await(15, TimeUnit.SECONDS))
                .as("the controlled model call must be reached while the Run is in flight").isTrue();
        stream.join(1_000);
        assertThat(failure.get()).as("Run stream must not fail before it settles").isNull();
        return new BlockedRun(stream, response, failure);
    }

    private HttpResponse<String> finish(BlockedRun blocked) throws Exception {
        blocked.stream().join(40_000);
        assertThat(blocked.stream().isAlive())
                .as("Run stream must settle within the bounded deadline").isFalse();
        assertThat(blocked.failure().get()).as("Run stream must not fail").isNull();
        HttpResponse<String> response = blocked.response().get();
        assertThat(response).as("Run stream response must be captured").isNotNull();
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("event: end");
        return response;
    }

    private static String pendingHumanId(JsonNode state) {
        List<JsonNode> messages = DelegationHttpFixture.messages(state);
        assertThat(messages).as("in-flight state must project the pending human message").isNotEmpty();
        assertThat(messages.get(0).path("type").asText()).isEqualTo("human");
        return messages.get(0).path("id").asText();
    }

    private String historyHumanId(URI base, String threadId, String evidenceName) throws Exception {
        HttpResponse<String> response = http.request(
                base, "POST", "/api/threads/" + threadId + "/history", "{\"limit\":10}", evidenceName);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode history = GatewayFixture.JSON.readTree(response.body());
        return history.get(0).path("values").path("messages").get(0).path("id").asText();
    }

    private static String runId(HttpResponse<String> response) {
        assertThat(response).isNotNull();
        String location = response.headers().firstValue("content-location").orElseThrow();
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private record BlockedRun(
            Thread stream,
            AtomicReference<HttpResponse<String>> response,
            AtomicReference<Exception> failure) {}
}

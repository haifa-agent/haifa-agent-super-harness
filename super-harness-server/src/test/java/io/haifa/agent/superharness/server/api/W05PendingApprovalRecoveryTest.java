package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.InteractionConsequenceView;
import io.haifa.agent.runtime.api.InteractionInputContract;
import io.haifa.agent.runtime.api.InteractionKind;
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.api.InteractionRequesterView;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.InteractionTargetView;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
import io.haifa.agent.superharness.server.run.HaifaPublicInteractionSpikeTest;
import io.haifa.agent.superharness.server.run.HaifaRunBridge;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deterministic L2 proof for the V2 pending-Approval exact-recovery slice (case V2-M4-01..03 and the
 * adjacent M3 negatives). The authoritative Interaction state must remain readable from the public
 * SDK/SQLite facts across reload, SSE reconnect and an in-process gateway restart without a second
 * Interaction authority or a blind tool replay.
 */
class W05PendingApprovalRecoveryTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final AtomicInteger toolInvocations = new AtomicInteger();
    private final AtomicBoolean secondModelAvailable = new AtomicBoolean(true);
    private final SecretKey continuationKey = new SecretKeySpec(new byte[32], "AES");
    private final HaifaPublicInteractionSpikeTest.ApprovalProbeModel model =
            new HaifaPublicInteractionSpikeTest.ApprovalProbeModel();
    @TempDir
    Path dataDirectory;
    private DeerFlowBootstrapApi api;
    private URI baseUri;

    @BeforeEach
    void startServer() throws Exception {
        var binding = new HaifaRunBridge.ModelBinding(
                "interaction-probe",
                "1.0.0",
                model,
                HaifaPublicInteractionSpikeTest.modelSnapshot());
        api = DeerFlowBootstrapApi.createForInteractionTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory,
                continuationKey,
                ignored -> binding,
                List.of(new HaifaPublicInteractionSpikeTest.MutatingProbeTool(toolInvocations)));
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
    }

    @AfterEach
    void stopServer() {
        if (api != null) api.close();
    }

    @Test
    void reconnectWhilePendingReplaysTheSameCardAndNeverStartsASecondRun() throws Exception {
        createThread("reconnect-thread");
        HttpResponse<String> requested =
                postJson("/api/threads/reconnect-thread/runs/stream", ordinaryRunBody("reconnect approval"));
        assertThat(requested.statusCode()).isEqualTo(200);
        String location = requested.headers().firstValue("content-location").orElseThrow();
        String runId = location.substring(location.lastIndexOf('/') + 1);
        JsonNode card = humanInputCard(requested.body());
        String requestId = requestId(card);
        String cardId = card.path("id").asText();
        String targetToolCall = card.path("artifact").path("human_input").path("tool_call_id").asText();
        assertThat(toolInvocations).hasValue(0);

        // The frozen UI drops and re-establishes the SSE stream (joinStream). A first-observer join
        // whose cursor left the bounded replay window receives the accepted gap recovery contract.
        HttpResponse<String> firstObserver = send(HttpRequest.newBuilder(baseUri.resolve(location + "/stream"))
                .header("Last-Event-ID", "-1")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build());
        assertThat(firstObserver.statusCode()).as(firstObserver.body()).isEqualTo(200);
        assertThat(firstObserver.body()).contains("event: gap", "stream_replay_gap");
        JsonNode gap = firstEventData(firstObserver.body(), "gap");
        assertThat(gap.path("run_id").asText()).isEqualTo(runId);
        assertThat(gap.path("recovery").asText()).isEqualTo("reload_durable_state");

        // The frozen UI resumes after the retained tail. That reconnect must observe the same
        // pending Interaction card from the authoritative SDK view rather than a heartbeat-only
        // stream, and must never start a second Run.
        HttpResponse<String> resumed = send(HttpRequest.newBuilder(baseUri.resolve(location + "/stream"))
                .header("Last-Event-ID", gap.path("latest_available_event_id").asText())
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build());
        assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
        assertThat(resumed.body()).contains("event: messages", "event: end");
        JsonNode replayedCard = humanInputCard(resumed.body());
        assertThat(replayedCard.path("id").asText()).isEqualTo(cardId);
        assertThat(requestId(replayedCard)).isEqualTo(requestId);
        assertThat(replayedCard.path("artifact").path("human_input").path("tool_call_id").asText())
                .isEqualTo(targetToolCall);
        assertThat(replayedCard.path("artifact").path("human_input").path("options").findValuesAsText("id"))
                .containsExactly("approve", "reject");

        // Reload reads the same authoritative pending card without duplicating it.
        JsonNode state = getJson("/api/threads/reconnect-thread/state");
        assertThat(requestId(messageWithHumanInput(state.path("values").path("messages")))).isEqualTo(requestId);
        JsonNode runs = getJson("/api/threads/reconnect-thread/runs");
        assertThat(runs.findValuesAsText("run_id")).containsExactly(runId);
        assertThat(getJson(location).path("status").asText()).isEqualTo("interrupted");
        assertThat(toolInvocations).hasValue(0);
        System.out.println("W05_PENDING_RECOVERY_EVIDENCE scenario=reconnect run=" + runId
                + " request=" + requestId + " card=" + cardId
                + " resume_observed_card=true tool_calls=" + toolInvocations.get());
    }

    @Test
    void restartKeepsTheSamePendingInteractionAndApprovesExactlyOnce() throws Exception {
        createThread("restart-exact-thread");
        HttpResponse<String> requested =
                postJson("/api/threads/restart-exact-thread/runs/stream", ordinaryRunBody("restart approval"));
        assertThat(requested.statusCode()).isEqualTo(200);
        String location = requested.headers().firstValue("content-location").orElseThrow();
        JsonNode beforeCard = humanInputCard(requested.body());
        String requestId = requestId(beforeCard);
        String cardId = beforeCard.path("id").asText();
        String targetToolCall =
                beforeCard.path("artifact").path("human_input").path("tool_call_id").asText();
        JsonNode beforeToolCall = toolCallMessage(messageEvents(requested.body()));
        assertThat(beforeToolCall.path("tool_calls").get(0).path("id").asText()).isEqualTo(targetToolCall);

        api.close();
        startServer();

        JsonNode state = getJson("/api/threads/restart-exact-thread/state");
        JsonNode afterCard = messageWithHumanInput(state.path("values").path("messages"));
        JsonNode afterToolCall = messageWithToolCalls(state.path("values").path("messages"));
        assertThat(afterCard.path("id").asText()).isEqualTo(cardId);
        assertThat(requestId(afterCard)).isEqualTo(requestId);
        assertThat(afterCard.path("tool_call_id").asText()).isEqualTo(cardId);
        assertThat(afterCard.path("artifact").path("human_input").path("tool_call_id").asText())
                .isEqualTo(targetToolCall);
        assertThat(afterToolCall.path("tool_calls").get(0).path("id").asText())
                .isEqualTo(targetToolCall);
        assertThat(getJson(location).path("status").asText()).isEqualTo("interrupted");
        assertThat(toolInvocations).hasValue(0);

        HttpResponse<String> approved = postJson(
                "/api/threads/restart-exact-thread/runs/stream",
                hiddenResponseBody(requestId, "approve", "approve"));
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(approved.headers().firstValue("content-location")).contains(location);
        assertThat(approved.body()).contains("interaction resolved", "event: end");
        assertThat(toolInvocations).hasValue(1);
        assertThat(getJson(location).path("status").asText()).isEqualTo("success");

        JsonNode page = getJson("/api/threads/restart-exact-thread/messages/page?limit=20");
        List<String> responseRequestIds = new ArrayList<>();
        page.path("data").forEach(row -> {
            JsonNode humanInput = row.path("content").path("artifact").path("human_input");
            if (humanInput.hasNonNull("request_id")) responseRequestIds.add(humanInput.path("request_id").asText());
        });
        assertThat(responseRequestIds).contains(requestId);
        assertThat(page.path("data").findValuesAsText("request_id")).contains(requestId);

        HttpResponse<String> duplicate = postJson(
                "/api/threads/restart-exact-thread/runs/stream",
                hiddenResponseBody(requestId, "approve", "approve"));
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(toolInvocations).hasValue(1);
        System.out.println("W05_PENDING_RECOVERY_EVIDENCE scenario=restart-approve run="
                + location.substring(location.lastIndexOf('/') + 1) + " request=" + requestId
                + " card=" + cardId + " duplicate=409 tool_calls=1");
    }

    @Test
    void pendingSecondModelKeepsItsOriginalRunAcrossGatewayRestart() throws Exception {
        api.close();
        startServerWithModelSwitch();
        createThread("cross-model-pending");
        String firstBody = withModel(ordinaryRunBody("first model terminal"),
                "super-harness-local-probe");
        HttpResponse<String> first = postJson("/api/threads/cross-model-pending/runs/stream", firstBody);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("first model terminal", "event: end");

        HttpResponse<String> pending = postJson("/api/threads/cross-model-pending/runs/stream",
                withModel(ordinaryRunBody("second model approval"), "interaction-probe"));
        assertThat(pending.statusCode()).isEqualTo(200);
        String location = pending.headers().firstValue("content-location").orElseThrow();
        String requestId = requestId(humanInputCard(pending.body()));
        assertThat(toolInvocations).hasValue(0);

        api.close();
        startServerWithModelSwitch();
        assertThat(requestId(messageWithHumanInput(
                getJson("/api/threads/cross-model-pending/state").path("values").path("messages"))))
                .isEqualTo(requestId);
        HttpResponse<String> duplicate = postJson("/api/threads/cross-model-pending/runs/stream",
                withModel(ordinaryRunBody("new turn while pending"), "super-harness-local-probe"));
        assertThat(duplicate.statusCode()).isEqualTo(409);
        HttpResponse<String> approved = postJson("/api/threads/cross-model-pending/runs/stream",
                hiddenResponseBody(requestId, "approve", "approve"));
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(approved.headers().firstValue("content-location")).contains(location);
        assertThat(approved.body()).contains("interaction resolved", "event: end");
        assertThat(toolInvocations).hasValue(1);
        System.out.println("W03_CROSS_MODEL_PENDING_EVIDENCE run=" + runId(location)
                + " request=" + requestId + " tool_calls=" + toolInvocations.get());
    }

    @Test
    void unavailablePendingModelCannotApplyApprovalSideEffect() throws Exception {
        api.close();
        startServerWithModelSwitch();
        createThread("cross-model-unavailable");
        assertThat(postJson("/api/threads/cross-model-unavailable/runs/stream",
                withModel(ordinaryRunBody("first terminal"), "super-harness-local-probe")).statusCode())
                .isEqualTo(200);
        HttpResponse<String> pending = postJson("/api/threads/cross-model-unavailable/runs/stream",
                withModel(ordinaryRunBody("second approval"), "interaction-probe"));
        String requestId = requestId(humanInputCard(pending.body()));
        api.close();
        secondModelAvailable.set(false);
        startServerWithModelSwitch();
        assertThat(getJson("/api/threads/cross-model-unavailable/state").toString())
                .contains(requestId);
        HttpResponse<String> refused = postJson("/api/threads/cross-model-unavailable/runs/stream",
                hiddenResponseBody(requestId, "approve", "approve"));
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("Pending Run model is unavailable");
        assertThat(toolInvocations).hasValue(0);
        System.out.println("W03_PENDING_MODEL_UNAVAILABLE_EVIDENCE request=" + requestId
                + " status=409 tool_calls=0");
    }

    private void startServerWithModelSwitch() throws Exception {
        var first = LocalProbeModelTestSupport.binding();
        var second = new HaifaRunBridge.ModelBinding(
                "interaction-probe", "1.0.0", model,
                HaifaPublicInteractionSpikeTest.modelSnapshot());
        api = DeerFlowBootstrapApi.createForInteractionTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory,
                continuationKey,
                body -> {
                    if ("interaction-probe".equals(body.path("context").path("model_name").asText())) {
                        if (!secondModelAvailable.get()) {
                            throw new IllegalArgumentException("Model is unavailable");
                        }
                        return second;
                    }
                    return first;
                },
                List.of(new HaifaPublicInteractionSpikeTest.MutatingProbeTool(toolInvocations)));
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
    }

    private String withModel(String body, String modelName) {
        return body.replace("\"on_disconnect\"",
                "\"context\":{\"model_name\":\"" + modelName + "\"},\"on_disconnect\"");
    }

    @Test
    void cancelWhilePendingIsTerminalAndAcceptingTheInteractionIsRejectedWithoutSideEffect() throws Exception {
        createThread("cancel-thread");
        HttpResponse<String> requested =
                postJson("/api/threads/cancel-thread/runs/stream", ordinaryRunBody("cancel approval"));
        String location = requested.headers().firstValue("content-location").orElseThrow();
        String requestId = requestId(humanInputCard(requested.body()));

        HttpResponse<String> cancelled =
                postJson("/api/threads/cancel-thread/runs/" + runId(location) + "/cancel?action=interrupt&wait=false", "");
        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(202);
        assertThat(getJson(location).path("status").asText()).isEqualTo("interrupted");
        assertThat(toolInvocations).hasValue(0);

        HttpResponse<String> lateApprove = postJson(
                "/api/threads/cancel-thread/runs/stream", hiddenResponseBody(requestId, "approve", "approve"));
        assertThat(lateApprove.statusCode())
                .as("a cancelled Run must reject the stale approval cleanly: %s", lateApprove.body())
                .isEqualTo(409);
        assertThat(toolInvocations).hasValue(0);
        System.out.println("W05_PENDING_RECOVERY_EVIDENCE scenario=cancel run=" + runId(location)
                + " request=" + requestId + " cancel=202 late_approve=" + lateApprove.statusCode()
                + " body=" + lateApprove.body().replace('\n', ' ')
                + " tool_calls=" + toolInvocations.get());
    }

    @Test
    void restartRejectsAStaleRequestIdAndAForeignThreadWithoutSideEffect() throws Exception {
        createThread("wrong-run-thread");
        HttpResponse<String> requested =
                postJson("/api/threads/wrong-run-thread/runs/stream", ordinaryRunBody("wrong run approval"));
        String requestId = requestId(humanInputCard(requested.body()));

        api.close();
        startServer();

        HttpResponse<String> stale = postJson(
                "/api/threads/wrong-run-thread/runs/stream",
                hiddenResponseBody("other-request", "approve", "approve"));
        assertThat(stale.statusCode()).as(stale.body()).isEqualTo(409);
        assertThat(stale.body()).contains("Thread has no matching Interaction");
        assertThat(toolInvocations).hasValue(0);

        createThread("foreign-thread");
        HttpResponse<String> foreign = postJson(
                "/api/threads/foreign-thread/runs/stream", hiddenResponseBody(requestId, "approve", "approve"));
        assertThat(foreign.statusCode()).as(foreign.body()).isEqualTo(409);
        assertThat(toolInvocations).hasValue(0);
    }

    @Test
    void nonApprovalInteractionCannotBeProjectedAsTheFrozenApprovalCard() {
        InteractionView textInteraction = new InteractionView(
                new InteractionRequestId("input-request-1"),
                new AgentRunId("run-1"),
                new AgentSessionId("session-1"),
                1,
                InteractionKind.INPUT_REQUIRED,
                InteractionState.PENDING,
                "Provide input",
                "Please provide a value",
                List.of(),
                InteractionInputContract.NONE,
                new InteractionTargetView("tool", "tool-1", Optional.empty(), Optional.empty(), "Tool"),
                new InteractionRequesterView("human", "Operator"),
                Instant.parse("2026-09-27T00:00:00Z"),
                Optional.empty(),
                new InteractionConsequenceView("Submit", "Cancel", "Expire"));

        assertThatThrownBy(() -> DeerFlowInteractionHttpProjection.requestMessage(textInteraction, "run-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frozen approval card");
    }

    private String runId(String location) {
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private String requestId(JsonNode card) {
        return card.path("artifact").path("human_input").path("request_id").asText();
    }

    private String ordinaryRunBody(String prompt) {
        return """
                {"assistant_id":"lead_agent","input":{"messages":[{"id":"human-1","type":"human","content":[{"type":"text","text":"%s"}],"additional_kwargs":{}}]},"stream_mode":["messages-tuple","updates","custom"],"on_disconnect":"continue","multitask_strategy":"reject"}
                """
                .formatted(prompt);
    }

    private String hiddenResponseBody(String requestId, String optionId, String value) {
        return """
                {"assistant_id":"lead_agent","input":{"messages":[{"id":"hidden-response","type":"human","content":[{"type":"text","text":"For your clarification, my answer is: %s"}],"additional_kwargs":{"hide_from_ui":true,"human_input_response":{"version":1,"kind":"human_input_response","source":"haifa_interaction","request_id":"%s","response_kind":"option","option_id":"%s","value":"%s"}}}]},"stream_mode":["messages-tuple","updates","custom"],"on_disconnect":"continue","multitask_strategy":"reject"}
                """
                .formatted(value, requestId, optionId, value);
    }

    private void createThread(String threadId) throws Exception {
        assertThat(postJson("/api/threads", "{\"thread_id\":\"" + threadId + "\"}").statusCode())
                .isEqualTo(200);
    }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(baseUri.resolve(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = send(
                HttpRequest.newBuilder(baseUri.resolve(path)).GET().build());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private List<JsonNode> messageEvents(String transcript) throws Exception {
        Matcher matcher = Pattern.compile("event: messages\\ndata: ([^\\n]+)\\n").matcher(transcript);
        List<JsonNode> messages = new ArrayList<>();
        while (matcher.find()) {
            JsonNode tuple = json.readTree(matcher.group(1));
            if (tuple.isArray() && !tuple.isEmpty()) messages.add(tuple.get(0));
        }
        assertThat(messages).as("SSE transcript: %s", transcript).isNotEmpty();
        return List.copyOf(messages);
    }

    private JsonNode firstEventData(String transcript, String eventName) throws Exception {
        Matcher matcher = Pattern.compile("event: " + Pattern.quote(eventName) + "\\ndata: ([^\\n]+)\\n")
                .matcher(transcript);
        assertThat(matcher.find()).as("SSE transcript must contain %s: %s", eventName, transcript).isTrue();
        return json.readTree(matcher.group(1));
    }

    private JsonNode humanInputCard(String transcript) throws Exception {
        return messageEvents(transcript).stream()
                .filter(message -> message.path("artifact").path("human_input").hasNonNull("request_id"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing human input card: " + transcript));
    }

    private static JsonNode messageWithToolCalls(JsonNode messages) {
        for (JsonNode message : messages) {
            if (!message.path("tool_calls").isEmpty()) return message;
        }
        throw new AssertionError("missing tool call message: " + messages);
    }

    private static JsonNode toolCallMessage(List<JsonNode> messages) {
        for (JsonNode message : messages) {
            if (!message.path("tool_calls").isEmpty()) return message;
        }
        throw new AssertionError("missing tool call message: " + messages);
    }

    private static JsonNode messageWithHumanInput(JsonNode messages) {
        for (JsonNode message : messages) {
            if (message.path("artifact").path("human_input").hasNonNull("request_id")) return message;
        }
        throw new AssertionError("missing human input message: " + messages);
    }
}

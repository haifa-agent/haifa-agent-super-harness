package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeerFlowInteractionHttpApiTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final AtomicInteger toolInvocations = new AtomicInteger();
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
        api.close();
    }

    @Test
    void frozenCardApprovesSameRunReloadsAndDeduplicatesTheHiddenReply() throws Exception {
        createThread("interaction-thread");

        HttpResponse<String> requested = postJson(
                "/api/threads/interaction-thread/runs/stream", ordinaryRunBody("request approval"));
        assertThat(requested.statusCode()).isEqualTo(200);
        String location = requested.headers().firstValue("content-location").orElseThrow();
        String runId = location.replaceFirst(".*/", "");
        JsonNode cardTuple = firstEventData(requested.body(), "messages");
        JsonNode request = cardTuple.get(0).path("artifact").path("human_input");
        String requestId = request.path("request_id").asText();

        assertThat(cardTuple.get(0).path("type").asText()).isEqualTo("tool");
        assertThat(cardTuple.get(0).path("name").asText()).isEqualTo("ask_clarification");
        assertThat(request.path("version").asInt()).isEqualTo(1);
        assertThat(request.path("source").asText()).isEqualTo("haifa_interaction");
        assertThat(request.path("input_mode").asText()).isEqualTo("single_choice");
        assertThat(request.path("options").findValuesAsText("id"))
                .containsExactly("approve", "reject");
        assertThat(requested.body()).contains("event: end\ndata: null\n\n");
        assertThat(toolInvocations).hasValue(0);
        assertThat(getJson(location).path("status").asText()).isEqualTo("interrupted");

        // The frozen UI reloads history while the card waits; the human input must precede it
        // or the card is rendered as already answered.
        HttpResponse<String> pendingHistory =
                postJson("/api/threads/interaction-thread/history", "{\"limit\":10}");
        assertThat(pendingHistory.statusCode()).isEqualTo(200);
        JsonNode pendingMessages = json.readTree(pendingHistory.body())
                .get(0).path("values").path("messages");
        assertThat(pendingMessages).hasSize(2);
        assertThat(pendingMessages.get(0).path("type").asText()).isEqualTo("human");
        assertThat(pendingMessages.get(1).path("type").asText()).isEqualTo("tool");
        assertThat(pendingMessages.get(0).path("content").asText()).isEqualTo("request approval");
        assertThat(pendingMessages.get(1).path("artifact").path("human_input").path("request_id").asText())
                .isEqualTo(requestId);

        String responseBody = hiddenResponseBody(requestId, "approve", "approve");
        HttpResponse<String> resumed =
                postJson("/api/threads/interaction-thread/runs/stream", responseBody);
        assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
        assertThat(resumed.headers().firstValue("content-location")).contains(location);
        assertThat(resumed.body()).contains("event: messages");
        assertThat(resumed.body()).contains("interaction resolved");
        assertThat(resumed.body()).contains("event: end");
        assertThat(toolInvocations).hasValue(1);
        assertThat(getJson(location).path("status").asText()).isEqualTo("success");

        JsonNode page = getJson("/api/threads/interaction-thread/messages/page?limit=20");
        assertThat(page.path("data")).hasSize(4);
        assertThat(page.path("data").findValuesAsText("request_id"))
                .containsExactly(requestId, requestId);
        assertThat(page.path("data").get(1).path("content").path("artifact").path("human_input")
                        .path("request_id")
                        .asText())
                .isEqualTo(requestId);
        assertThat(page.path("data").get(2).path("content").path("additional_kwargs")
                        .path("human_input_response")
                        .path("option_id")
                        .asText())
                .isEqualTo("approve");

        HttpResponse<String> duplicate =
                postJson("/api/threads/interaction-thread/runs/stream", responseBody);
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(duplicate.body()).contains("Interaction response already accepted");
        assertThat(toolInvocations).hasValue(1);

        HttpResponse<String> conflicting = postJson(
                "/api/threads/interaction-thread/runs/stream",
                hiddenResponseBody(requestId, "reject", "reject"));
        assertThat(conflicting.statusCode()).isEqualTo(409);
        assertThat(conflicting.body()).contains("Interaction already has a different response");

        System.out.println("W05_INTERACTION_HTTP_EVIDENCE thread=interaction-thread run="
                + runId + " request=" + requestId + " duplicate=acknowledged");
    }

    @Test
    void malformedMismatchedAndCrossThreadResponsesFailClosed() throws Exception {
        createThread("owner-thread");
        HttpResponse<String> requested =
                postJson("/api/threads/owner-thread/runs/stream", ordinaryRunBody("request approval"));
        String requestId = firstEventData(requested.body(), "messages")
                .get(0)
                .path("artifact")
                .path("human_input")
                .path("request_id")
                .asText();

        HttpResponse<String> malformed = postJson(
                "/api/threads/owner-thread/runs/stream",
                hiddenResponseBody(requestId, "approve", ""));
        assertThat(malformed.statusCode()).isEqualTo(422);
        assertThat(malformed.body()).contains("Invalid human_input_response");

        HttpResponse<String> mismatched = postJson(
                "/api/threads/owner-thread/runs/stream",
                hiddenResponseBody("another-request", "approve", "approve"));
        assertThat(mismatched.statusCode()).isEqualTo(409);
        assertThat(mismatched.body()).contains("Thread has no matching Interaction");

        createThread("other-thread");
        HttpResponse<String> crossThread = postJson(
                "/api/threads/other-thread/runs/stream",
                hiddenResponseBody(requestId, "approve", "approve"));
        assertThat(crossThread.statusCode()).isEqualTo(409);
        assertThat(crossThread.body()).contains("Thread has no matching Interaction");
        assertThat(toolInvocations).hasValue(0);
    }

    @Test
    void frozenCardRejectionResumesWithoutExecutingTheTool() throws Exception {
        createThread("rejected-thread");
        HttpResponse<String> requested = postJson(
                "/api/threads/rejected-thread/runs/stream", ordinaryRunBody("request approval"));
        String location = requested.headers().firstValue("content-location").orElseThrow();
        String requestId = firstEventData(requested.body(), "messages")
                .get(0)
                .path("artifact")
                .path("human_input")
                .path("request_id")
                .asText();

        HttpResponse<String> rejected = postJson(
                "/api/threads/rejected-thread/runs/stream",
                hiddenResponseBody(requestId, "reject", "reject"));
        assertThat(rejected.statusCode()).isEqualTo(200);
        assertThat(rejected.body()).contains("event: end");
        assertThat(toolInvocations).hasValue(0);
        assertThat(getJson(location).path("status").asText()).isEqualTo("success");
    }

    @Test
    void pendingInteractionSurvivesServerRestartAndResumesTheSameRun() throws Exception {
        createThread("restart-interaction-thread");
        HttpResponse<String> requested = postJson(
                "/api/threads/restart-interaction-thread/runs/stream", ordinaryRunBody("request approval"));
        assertThat(requested.statusCode()).isEqualTo(200);
        String location = requested.headers().firstValue("content-location").orElseThrow();
        String requestId = firstEventData(requested.body(), "messages")
                .get(0).path("artifact").path("human_input").path("request_id").asText();
        assertThat(toolInvocations).hasValue(0);

        api.close();
        startServer();
        JsonNode reloadedMessages = getJson("/api/threads/restart-interaction-thread/state")
                .path("values").path("messages");
        assertThat(reloadedMessages).hasSize(2);
        assertThat(reloadedMessages.get(1).path("artifact").path("human_input")
                .path("request_id").asText()).isEqualTo(requestId);
        JsonNode reloadedPage = getJson("/api/threads/restart-interaction-thread/messages/page?limit=20")
                .path("data");
        assertThat(reloadedPage).hasSize(2);
        assertThat(reloadedPage.get(1).path("content").path("artifact").path("human_input")
                .path("request_id").asText()).isEqualTo(requestId);
        assertThat(toolInvocations).hasValue(0);
        HttpResponse<String> competingRun = postJson(
                "/api/threads/restart-interaction-thread/runs/stream", ordinaryRunBody("competing request"));
        assertThat(competingRun.statusCode()).as(competingRun.body()).isEqualTo(409);
        HttpResponse<String> staleReply = postJson(
                "/api/threads/restart-interaction-thread/runs/stream",
                hiddenResponseBody("stale-request", "approve", "approve"));
        assertThat(staleReply.statusCode()).as(staleReply.body()).isEqualTo(409);
        assertThat(toolInvocations).hasValue(0);

        HttpResponse<String> resumed = postJson(
                "/api/threads/restart-interaction-thread/runs/stream",
                hiddenResponseBody(requestId, "approve", "approve"));
        assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
        assertThat(resumed.headers().firstValue("content-location")).contains(location);
        assertThat(resumed.body()).contains("interaction resolved", "event: end");
        assertThat(toolInvocations).hasValue(1);
        assertThat(getJson(location).path("status").asText()).isEqualTo("success");
        HttpResponse<String> duplicate = postJson(
                "/api/threads/restart-interaction-thread/runs/stream",
                hiddenResponseBody(requestId, "approve", "approve"));
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(toolInvocations).hasValue(1);
        System.out.println("W05_RESTART_INTERACTION_EVIDENCE run="
                + location.substring(location.lastIndexOf('/') + 1)
                + " request=" + requestId + " tool_calls=" + toolInvocations.get()
                + " competing_run=409 stale_reply=409 duplicate=409");
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
        assertThat(postJson("/api/threads", "{\"thread_id\":\"" + threadId + "\"}")
                        .statusCode())
                .isEqualTo(200);
    }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private JsonNode firstEventData(String transcript, String event) throws Exception {
        Matcher matcher = Pattern.compile(
                        "event: " + Pattern.quote(event) + "\\ndata: ([^\\n]+)\\n")
                .matcher(transcript);
        assertThat(matcher.find()).as("SSE transcript: %s", transcript).isTrue();
        return json.readTree(matcher.group(1));
    }
}

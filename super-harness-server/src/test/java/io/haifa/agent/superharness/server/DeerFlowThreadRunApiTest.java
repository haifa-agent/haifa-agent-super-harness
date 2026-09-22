package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeerFlowThreadRunApiTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private DeerFlowBootstrapApi api;
    private URI baseUri;

    @BeforeEach
    void startServer() throws Exception {
        api = DeerFlowBootstrapApi.create(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0));
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
    }

    @AfterEach
    void stopServer() {
        api.close();
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
        assertThat(response.body()).contains("event: messages-tuple");
        assertThat(response.body()).contains("Haifa Agent SDK: ");
        assertThat(response.body()).contains("hello sdk");
        assertThat(response.body()).endsWith("event: end\ndata: null\n\n");

        JsonNode run = getJson(location);
        assertThat(run.path("status").asText()).isEqualTo("success");
        assertThat(run.path("run_id").asText()).isEqualTo(runId);
        assertThat(run.path("assistant_id").asText()).isEqualTo("lead_agent");
        assertThat(run.path("llm_call_count").asLong()).isEqualTo(1);
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
        assertThat(response.body()).endsWith("event: end\ndata: null\n\n");
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

        HttpResponse<String> duplicate =
                postJson(location + "/cancel?wait=false&action=interrupt", "");
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(json.readTree(duplicate.body()).path("detail").asText())
                .contains("not cancellable", "interrupted");
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
    }

    private void createThread(String threadId) throws Exception {
        assertThat(postJson("/api/threads", "{\"thread_id\":\"" + threadId + "\"}").statusCode())
                .isEqualTo(200);
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

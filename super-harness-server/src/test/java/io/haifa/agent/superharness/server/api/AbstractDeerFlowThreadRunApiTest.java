package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelStreamSink;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.sdk.conversation.ConversationTurn;
import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
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
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/**
 * Shared Gateway lifecycle, request helpers and model doubles for the DeerFlow thread/run API tests,
 * which are split by responsibility so the surefire forks can run them in parallel.
 */
abstract class AbstractDeerFlowThreadRunApiTest {
    final ObjectMapper json = new ObjectMapper();
    final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    @TempDir
    Path dataDirectory;
    DeerFlowBootstrapApi api;
    URI baseUri;
    final SecretKey continuationKey = new SecretKeySpec(new byte[32], "AES");

    @BeforeEach
    void startServer() throws Exception {
        startApi();
    }

    void startApi() throws Exception {
        startApi(LocalProbeModelTestSupport.binding());
    }

    void startApi(HaifaRunBridge.ModelBinding binding) throws Exception {
        startApi(ignored -> binding);
    }

    void startApi(java.util.function.Function<JsonNode, HaifaRunBridge.ModelBinding> resolver)
            throws Exception {
        if (api != null) {
            // A test that swaps the model restarts the Gateway; the previous one must release its store.
            api.close();
            api = null;
        }
        api = DeerFlowBootstrapApi.createForTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory,
                resolver,
                continuationKey);
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
    }

    void startApi(
            Map<String, String> environment,
            java.util.function.Function<JsonNode, HaifaRunBridge.ModelBinding> resolver)
            throws Exception {
        if (api != null) {
            api.close();
            api = null;
        }
        api = DeerFlowBootstrapApi.createForTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory,
                environment,
                resolver);
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
    }

    @AfterEach
    void stopServer() {
        if (api != null) api.close();
    }

    int ownedBridgeCount() throws ReflectiveOperationException {
        var field = DeerFlowConversationService.class.getDeclaredField("ownedBridges");
        field.setAccessible(true);
        return ((java.util.Set<?>) field.get(api.threadRunApi().conversations())).size();
    }

    void createThread(String threadId) throws Exception {
        assertThat(postJson("/api/threads", "{\"thread_id\":\"" + threadId + "\"}").statusCode())
                .isEqualTo(200);
    }

    ConversationTurn turn(long sequence, MessageRole role) {
        return new ConversationTurn(
                "message-" + sequence,
                role,
                Optional.empty(),
                sequence,
                List.of(new TextPart("turn " + sequence, "text/plain")),
                MessageVisibility.USER_VISIBLE,
                Instant.EPOCH.plusSeconds(sequence));
    }

    static final class CapturingModel implements AgentChatModel {
        volatile String lastUserContent = "";

        @Override
        public AgentChatResponse invoke(AgentChatRequest request) {
            capture(request);
            return response(request);
        }

        @Override
        public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
            capture(request);
            return response(request);
        }

        void capture(AgentChatRequest request) {
            lastUserContent = request.messages().stream()
                    .filter(message -> message.role().name().equals("USER"))
                    .reduce((first, second) -> second)
                    .map(message -> message.content())
                    .orElse("");
        }

        static AgentChatResponse response(AgentChatRequest request) {
            return new AgentChatResponse(
                    "capture-response",
                    request.model().providerModelId(),
                    "captured",
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(1, 1),
                    "",
                    Map.of());
        }
    }

    HttpRequest runRequest(String threadId, String message) {
        return runRequest(threadId, message, "cancel");
    }

    HttpRequest runRequest(String threadId, String message, String onDisconnect) {
        String body = """
                {"assistant_id":"lead_agent","input":{"messages":[{"id":"human-1","type":"human","content":"%s"}]},"stream_mode":["messages-tuple","updates","custom"],"on_disconnect":"%s","multitask_strategy":"reject"}
                """.formatted(message, onDisconnect);
        return HttpRequest.newBuilder(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    HttpRequest runRequestWithModes(String threadId, String message) {
        String body = """
                {"assistant_id":"lead_agent","input":{"messages":[{"id":"human-1","type":"human","content":"%s"}]},"stream_mode":["messages-tuple"],"on_disconnect":"continue","multitask_strategy":"reject"}
                """.formatted(message);
        return HttpRequest.newBuilder(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    void awaitThreadStatus(String threadId, String expected) throws Exception {
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

    void awaitTitleObserversIdle() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (api.threadRunApi().conversations().titleCompletionObserverCount() == 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Title completion observers did not settle");
    }

    List<String> humanTurnContents(String threadId) throws Exception {
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

    void awaitTitle(String threadId, String expectedTitle) throws Exception {
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

    HttpResponse<String> postJson(String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> join(String location, String lastEventId) throws Exception {
        return client.send(joinRequest(location, lastEventId), HttpResponse.BodyHandlers.ofString());
    }

    HttpRequest joinRequest(String location, String lastEventId) {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(location + "/stream")).GET();
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }
        return request.build();
    }

    List<Long> sseIds(String transcript) {
        Matcher matcher = Pattern.compile("(?m)^id: (\\d+)-0$").matcher(transcript);
        List<Long> ids = new ArrayList<>();
        while (matcher.find()) {
            ids.add(Long.parseLong(matcher.group(1)));
        }
        return ids;
    }

    JsonNode firstEventData(String transcript, String event) throws Exception {
        Matcher matcher = Pattern.compile(
                        "event: " + Pattern.quote(event) + "\\ndata: ([^\\n]+)\\n\\n")
                .matcher(transcript);
        assertThat(matcher.find()).isTrue();
        return json.readTree(matcher.group(1));
    }

    JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    void awaitStatus(String path, String expected) throws Exception {
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

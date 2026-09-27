package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelStreamSink;
import io.haifa.agent.model.api.ModelUsage;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeerFlowSuggestionsApiTest {
    private static final String THREAD = "suggestions-thread";
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    @TempDir
    Path dataDirectory;
    private ScriptedModel model;
    private DeerFlowBootstrapApi api;
    private URI baseUri;

    @BeforeEach
    void startServer() throws Exception {
        model = new ScriptedModel();
        api = DeerFlowBootstrapApi.createForTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory,
                body -> LocalProbeModelTestSupport.binding(model));
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
        assertThat(postJson("/api/threads", "{\"thread_id\":\"" + THREAD + "\"}").statusCode())
                .isEqualTo(200);
    }

    @AfterEach
    void stopServer() {
        if (api != null) api.close();
    }

    @Test
    void configReportsTheFrozenDefaults() throws Exception {
        HttpResponse<String> response = getJson("/api/suggestions/config");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("enabled").asBoolean()).isTrue();
        assertThat(body.path("max_suggestions").asInt())
                .isEqualTo(DeerFlowSuggestionsApi.DEFAULT_MAX_SUGGESTIONS);
    }

    @Test
    void generatesSuggestionsAndSendsTheFrozenPromptShape() throws Exception {
        model.responder = input -> "```json\n[\"What changed?\", \"Why now?\", \"What next?\"]\n```";

        HttpResponse<String> response = postSuggestions(
                """
                {"messages":[{"role":"user","content":"How do I page through results?"},
                {"role":"assistant","content":"Use the cursor."}],"n":3}
                """);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(suggestions(response))
                .containsExactly("What changed?", "Why now?", "What next?");
        assertThat(model.calls.get()).isEqualTo(1);

        AgentChatRequest request = model.lastRequest;
        assertThat(request.messages()).hasSize(2);
        assertThat(request.messages().get(0).role()).isEqualTo(ModelMessageRole.SYSTEM);
        assertThat(request.messages().get(0).content())
                .contains("EXACTLY 3")
                .contains("same language as the user")
                .contains("JSON array of strings only");
        assertThat(request.messages().get(1).role()).isEqualTo(ModelMessageRole.USER);
        assertThat(request.messages().get(1).content())
                .contains("User: How do I page through results?")
                .contains("Assistant: Use the cursor.")
                .contains("Generate 3 follow-up questions");
        assertThat(api.cachedRunCount()).isZero();
        assertThat(api.hasAttachedBridge(THREAD)).isFalse();
    }

    @Test
    void disabledConfigReturnsEmptyListWithoutCallingTheProvider() throws Exception {
        ScriptedModel disabledModel = new ScriptedModel();
        try (Standalone standalone = standalone(
                disabledModel, new DeerFlowSuggestionsApi.Settings(false, 3))) {
            HttpResponse<String> response = standalone.post(
                    "/api/threads/" + THREAD + "/suggestions",
                    """
                    {"messages":[{"role":"user","content":"hello"}]}
                    """);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(suggestions(response)).isEmpty();
            assertThat(disabledModel.calls.get()).isZero();
        }
    }

    @Test
    void emptyMessagesReturnEmptyListWithoutCallingTheProvider() throws Exception {
        HttpResponse<String> response = postSuggestions("{\"messages\":[]}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(suggestions(response)).isEmpty();
        assertThat(model.calls.get()).isZero();
    }

    @Test
    void blankContentKeepsTheFrozenRolePrefixAndStillCallsTheProvider() throws Exception {
        model.responder = input -> "[\"follow up\"]";

        HttpResponse<String> response =
                postSuggestions("{\"messages\":[{\"role\":\"user\",\"content\":\"   \"}]}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(suggestions(response)).containsExactly("follow up");
        assertThat(model.calls.get()).isEqualTo(1);
        assertThat(model.lastRequest.messages().get(1).content()).contains("User:");
    }

    @Test
    void nOutsideFrozenBoundsIsRejected() throws Exception {
        assertThat(postSuggestions("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"n\":0}")
                        .statusCode())
                .isEqualTo(422);
        assertThat(postSuggestions("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"n\":6}")
                        .statusCode())
                .isEqualTo(422);
        assertThat(postSuggestions("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"n\":\"3\"}")
                        .statusCode())
                .isEqualTo(422);
        assertThat(model.calls.get()).isZero();
    }

    @Test
    void nIsClampedToTheConfiguredMaximum() throws Exception {
        ScriptedModel clampModel = new ScriptedModel();
        clampModel.responder = input -> "[\"one\",\"two\",\"three\"]";
        try (Standalone standalone = standalone(
                clampModel, new DeerFlowSuggestionsApi.Settings(true, 1))) {
            HttpResponse<String> response = standalone.post(
                    "/api/threads/" + THREAD + "/suggestions",
                    """
                    {"messages":[{"role":"user","content":"hello"}],"n":5}
                    """);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(suggestions(response)).hasSize(1);
            assertThat(clampModel.lastRequest.messages().get(0).content()).contains("EXACTLY 1");
        }
    }

    @Test
    void maliciousModelOutputIsCleanedOrDropped() throws Exception {
        model.responder = input -> "Sure! Here you go: [\"a\", 7, \"\", \"  b  \"] thanks";
        HttpResponse<String> noisy = postSuggestions(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");
        assertThat(suggestions(noisy)).containsExactly("a", "b");

        model.responder = input -> "<think>secret</think>```json\n[\"only\"]\n```";
        HttpResponse<String> thinkFenced = postSuggestions(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");
        assertThat(suggestions(thinkFenced)).containsExactly("only");

        model.responder = input -> "no array here";
        HttpResponse<String> invalid = postSuggestions(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");
        assertThat(suggestions(invalid)).isEmpty();
    }

    @Test
    void providerFailureReturnsEmptyListWithoutLeakingDetails() throws Exception {
        model.failure = true;

        HttpResponse<String> response = postSuggestions(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hello sk-do-not-leak\"}]}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(suggestions(response)).isEmpty();
        assertThat(response.body()).doesNotContain("sk-", "controlled-secret", "provider failure");
    }

    @Test
    void invalidBodiesAreRejected() throws Exception {
        assertThat(postSuggestions("{}").statusCode()).isEqualTo(422);
        assertThat(postSuggestions("{\"messages\":\"nope\"}").statusCode()).isEqualTo(422);
        assertThat(postSuggestions("{\"messages\":[{\"role\":\"user\"}]}").statusCode())
                .isEqualTo(422);
        assertThat(postSuggestions("{\"messages\":[{\"content\":\"hi\"}]}").statusCode())
                .isEqualTo(422);
        assertThat(
                        postSuggestions(
                                        "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"model_name\":7}")
                                .statusCode())
                .isEqualTo(422);
        assertThat(model.calls.get()).isZero();
    }

    @Test
    void unknownThreadReturnsNotFound() throws Exception {
        HttpResponse<String> response = postSuggestionsFor(
                "missing-thread", "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(model.calls.get()).isZero();
    }

    @Test
    void missingThreadReadPermissionIsDenied() throws Exception {
        ScriptedModel denied = new ScriptedModel();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/threads",
                new DeerFlowSuggestionsApi(
                        json,
                        body -> LocalProbeModelTestSupport.binding(denied),
                        threadId -> true,
                        true,
                        3,
                        () -> false));
        server.start();
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                                    + "/api/threads/" + THREAD + "/suggestions"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(denied.calls.get()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getSuggestionsConfigOnlyAllowsGet() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(baseUri.resolve("/api/suggestions/config"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("allow")).contains("GET");
    }

    private HttpResponse<String> postSuggestions(String body) throws Exception {
        return postSuggestionsFor(THREAD, body);
    }

    private HttpResponse<String> postSuggestionsFor(String threadId, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(
                                baseUri.resolve("/api/threads/" + threadId + "/suggestions"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private List<String> suggestions(HttpResponse<String> response) throws Exception {
        List<String> result = new java.util.ArrayList<>();
        json.readTree(response.body()).path("suggestions").forEach(node -> result.add(node.asText()));
        return result;
    }

    private HttpResponse<String> getJson(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Standalone standalone(
            AgentChatModel chatModel, DeerFlowSuggestionsApi.Settings settings) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/threads",
                new DeerFlowSuggestionsApi(
                        json,
                        body -> LocalProbeModelTestSupport.binding(chatModel),
                        threadId -> true,
                        settings));
        server.start();
        return new Standalone(server);
    }

    private static final class Standalone implements AutoCloseable {
        private final HttpServer server;

        private Standalone(HttpServer server) {
            this.server = server;
        }

        private HttpResponse<String> post(String path, String body) throws Exception {
            return HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                                    "http://127.0.0.1:" + server.getAddress().getPort() + path))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    static final class ScriptedModel implements AgentChatModel {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile Function<String, String> responder = Function.identity();
        private volatile boolean failure;
        private volatile AgentChatRequest lastRequest;

        @Override
        public AgentChatResponse invoke(AgentChatRequest request) {
            calls.incrementAndGet();
            lastRequest = request;
            if (failure) {
                throw new IllegalStateException("provider failure with controlled-secret");
            }
            return new AgentChatResponse(
                    "suggestions-response",
                    request.model().providerModelId(),
                    responder.apply(lastUserText(request)),
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(1, 1),
                    "",
                    Map.of());
        }

        @Override
        public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
            return invoke(request);
        }

        private static String lastUserText(AgentChatRequest request) {
            return request.messages().stream()
                    .filter(message -> message.role() == ModelMessageRole.USER)
                    .reduce((first, second) -> second)
                    .map(message -> message.content())
                    .orElse("");
        }
    }
}

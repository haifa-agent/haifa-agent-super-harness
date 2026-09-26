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

class DeerFlowInputPolishApiTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
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
    }

    @AfterEach
    void stopServer() {
        api.close();
    }

    @Test
    void rewritesDraftWithoutCreatingThreadRunOrToolFacts() throws Exception {
        model.responder = input -> "summarize report clearly";

        HttpResponse<String> response =
                post("{\"text\":\"  summarize report  \",\"locale\":\"en-US\",\"thread_id\":\"trace-1\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type")).contains("application/json");
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        JsonNode body = json(response.body());
        assertThat(body.get("rewritten_text").asText()).isEqualTo("summarize report clearly");
        assertThat(body.get("changed").asBoolean()).isTrue();
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.hasNonNull("rewritten_text")).isTrue();
        assertThat(body.has("changed")).isTrue();
        assertThat(model.calls.get()).isEqualTo(1);
        assertThat(api.cachedRunCount()).isZero();
        assertThat(api.hasAttachedBridge("trace-1")).isFalse();
    }

    @Test
    void ignoresForgedIdentityAndUsesTheDefaultLocalOwner() throws Exception {
        model.responder = input -> "hello clearly";

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(baseUri.resolve(DeerFlowInputPolishApi.PATH))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer forged")
                        .header("X-User-Id", "attacker")
                        .header("X-System-Role", "owner")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"hello\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response.body()).get("changed").asBoolean()).isTrue();
    }

    @Test
    void unchangedDraftReportsChangedFalse() throws Exception {
        model.responder = input -> "keep the original draft";

        HttpResponse<String> response = post("{\"text\":\"  keep the original draft  \"}");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response.body());
        assertThat(body.get("rewritten_text").asText()).isEqualTo("keep the original draft");
        assertThat(body.get("changed").asBoolean()).isFalse();
    }

    @Test
    void blankOrMissingTextIsRejectedWithoutCallingTheModel() throws Exception {
        HttpResponse<String> blank = post("{\"text\":\"   \\n  \"}");
        assertThat(blank.statusCode()).isEqualTo(400);
        assertThat(json(blank.body()).get("detail").asText()).isEqualTo("Input text is required");

        HttpResponse<String> missing = post("{}");
        assertThat(missing.statusCode()).isEqualTo(422);

        HttpResponse<String> wrongType = post("{\"text\":123}");
        assertThat(wrongType.statusCode()).isEqualTo(422);

        HttpResponse<String> padded = post("{\"text\":\"\",\"locale\":\"en\"}");
        assertThat(padded.statusCode()).isEqualTo(400);

        assertThat(model.calls.get()).isZero();
    }

    @Test
    void overLimitTextIsRejectedAtTheTrimmedBoundary() throws Exception {
        model.responder = input -> "ok";
        String atLimit = "a".repeat(DeerFlowInputPolishApi.DEFAULT_MAX_CHARS);
        String overLimit = "a".repeat(DeerFlowInputPolishApi.DEFAULT_MAX_CHARS + 1);

        HttpResponse<String> boundary =
                post(objectMapper.writeValueAsString(Map.of("text", "  " + atLimit + "  ")));
        assertThat(boundary.statusCode()).isEqualTo(200);

        HttpResponse<String> rejected = post(objectMapper.writeValueAsString(Map.of("text", overLimit)));
        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(json(rejected.body()).get("detail").asText())
                .isEqualTo("Input text exceeds " + DeerFlowInputPolishApi.DEFAULT_MAX_CHARS + " characters");
        assertThat(model.calls.get()).isEqualTo(1);
    }

    @Test
    void unknownFieldsAndInvalidOptionalTypesAreRejected() throws Exception {
        HttpResponse<String> unknownField = post("{\"text\":\"hello\",\"extra\":1}");
        assertThat(unknownField.statusCode()).isEqualTo(422);
        assertThat(json(unknownField.body()).get("detail").asText())
                .isEqualTo("Unknown field 'extra'");

        HttpResponse<String> badLocale = post("{\"text\":\"hello\",\"locale\":5}");
        assertThat(badLocale.statusCode()).isEqualTo(422);

        HttpResponse<String> badThreadId = post("{\"text\":\"hello\",\"thread_id\":[1]}");
        assertThat(badThreadId.statusCode()).isEqualTo(422);

        assertThat(model.calls.get()).isZero();
    }

    @Test
    void modelFailuresReturn503WithoutLeakingProviderDetails() throws Exception {
        model.failure = true;

        HttpResponse<String> response = post("{\"text\":\"hello sk-do-not-leak\"}");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(json(response.body()).get("detail").asText()).isEqualTo("Failed to polish input");
        assertThat(response.body()).doesNotContain("super-secret").doesNotContain("sk-");
    }

    @Test
    void emptyModelResultReturns503() throws Exception {
        model.responder = input -> "   \n  ";

        HttpResponse<String> response = post("{\"text\":\"hello\"}");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(json(response.body()).get("detail").asText()).isEqualTo("Failed to polish input");
    }

    @Test
    void completeThinkBlocksAndCodeFencesAreStrippedButDanglingTagsArePreserved() throws Exception {
        model.responder = input -> "```\n<think>internal reasoning</think>\nPlease clarify the goal.\n```";

        HttpResponse<String> fenced = post("{\"text\":\"clarify it\"}");
        assertThat(fenced.statusCode()).isEqualTo(200);
        assertThat(json(fenced.body()).get("rewritten_text").asText())
                .isEqualTo("Please clarify the goal.");

        model.responder = input -> "Explain the <think> tag to me";
        HttpResponse<String> dangling =
                post("{\"text\":\"Explain the <think> tag to me\"}");
        assertThat(dangling.statusCode()).isEqualTo(200);
        assertThat(json(dangling.body()).get("rewritten_text").asText())
                .isEqualTo("Explain the <think> tag to me");
    }

    @Test
    void promptCarriesSystemInstructionAndDraftWithLocaleHint() throws Exception {
        model.responder = Function.identity();

        HttpResponse<String> response = post("{\"text\":\"draft body\",\"locale\":\"zh-CN\"}");
        assertThat(response.statusCode()).isEqualTo(200);

        AgentChatRequest request = model.lastRequest;
        assertThat(request.messages()).hasSize(2);
        assertThat(request.messages().get(0).role()).isEqualTo(ModelMessageRole.SYSTEM);
        assertThat(request.messages().get(0).content())
                .contains("leading slash command prefix exactly");
        assertThat(request.messages().get(1).role()).isEqualTo(ModelMessageRole.USER);
        assertThat(request.messages().get(1).content())
                .contains("Locale hint: zh-CN")
                .contains("<draft>\ndraft body\n</draft>");
    }

    @Test
    void nonPostMethodsAreRejected() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(baseUri.resolve(DeerFlowInputPolishApi.PATH)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("allow")).contains("POST");
    }

    @Test
    void missingRunsCreatePermissionIsDenied() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                DeerFlowInputPolishApi.PATH,
                new DeerFlowInputPolishApi(
                        objectMapper,
                        body -> LocalProbeModelTestSupport.binding(model),
                        DeerFlowInputPolishApi.DEFAULT_MAX_CHARS,
                        () -> false));
        server.start();
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                                    + DeerFlowInputPolishApi.PATH))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"hello\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(json(response.body()).get("detail").asText()).isEqualTo("Forbidden");
            assertThat(model.calls.get()).isZero();
        } finally {
            server.stop(0);
        }
    }

    private HttpResponse<String> post(String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve(DeerFlowInputPolishApi.PATH))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
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
                throw new IllegalStateException("provider failure with sk-super-secret-key");
            }
            return new AgentChatResponse(
                    "input-polish-response",
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

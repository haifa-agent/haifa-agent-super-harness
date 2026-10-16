package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
import io.haifa.agent.superharness.server.tool.clarification.ClarificationBoundaryRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * W05-task-8 B: the frozen {@code ask_clarification} stop behaviour over the production Gateway.
 *
 * <p>A controlled OpenAI-compatible Provider scripts the model by protocol shape (never by the
 * values under test). The clarification Run must stop at its tool result, never expose later model
 * text, be reported as a clean clarification end, and route the hidden reply into the next Turn.
 */
class AskClarificationRunHttpTest {
    private static final String QUESTION = "Which environment should I deploy to?";

    @TempDir Path dataRoot;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private HttpServer provider;
    private java.util.concurrent.ExecutorService providerExecutor;
    private DeerFlowBootstrapApi api;
    private URI base;
    private final String key = Base64.getEncoder().encodeToString(new byte[32]);

    @AfterEach
    void stop() {
        if (api != null) api.close();
        if (provider != null) provider.stop(0);
        if (providerExecutor != null) providerExecutor.close();
        ClarificationBoundaryRegistry.resetForTests();
    }

    // ---- Oracle #4 / #4a: fast model completes before/around the cancel ------------------------

    @Test
    void fastModelTextAfterTheClarificationIsNeverProjected() throws Exception {
        startProvider(request -> hasClarificationToolResult(request)
                ? text("SHOULD_NOT_APPEAR")
                : clarificationToolCall());
        startGateway();
        String threadId = "clarify-fast";
        createThread(threadId);

        RunResult run = streamRun(threadId, "please clarify which environment");

        JsonNode card = clarificationCardInState(threadId);
        assertClarificationCard(card);
        assertThat(run.body()).doesNotContain("SHOULD_NOT_APPEAR");
        JsonNode state = state(threadId);
        assertThat(state.toString()).doesNotContain("SHOULD_NOT_APPEAR");
        assertThat(run.status()).isEqualTo("success");
        assertThat(state.path("values").path("artifacts")).isEmpty();
        assertToolCallCompleted(threadId, run.runId());
        System.out.println("W05_CLARIFICATION_FAST_EVIDENCE thread=" + threadId + " run=" + run.runId()
                + " request_id=" + requestId(card) + " status=" + run.status());
    }

    // ---- W05-task-9: the card orders after the human message that triggered it ------------------

    @Test
    void clarificationCardOrdersAfterTheTriggeringHumanMessage() throws Exception {
        // The model never produces text after the clarification (it is cancelled at the boundary),
        // so the durable transcript holds only the human Turn. That is the case where the frozen
        // SDK's POST /history head must still expose the human + card in order.
        startProvider(request -> {
            if (!hasClarificationToolResult(request)) {
                return clarificationToolCall();
            }
            sleepQuietly(1_500);
            return text("IGNORED_PRECANCEL");
        });
        startGateway();
        String threadId = "clarify-order";
        createThread(threadId);
        String question = "please clarify which environment";

        streamRun(threadId, question);

        JsonNode state = state(threadId);
        JsonNode stateMessages = state.path("values").path("messages");
        System.out.println("W05_CLARIFICATION_ORDER_STATE " + stateMessages);
        assertCardAfterTriggeringHuman(stateMessages, question, true, "state");
        assertNoClarificationAnswerByComposerBypass(stateMessages, "state");

        JsonNode page = requestJson("GET", "/api/threads/" + threadId + "/messages/page?limit=50");
        JsonNode pageRows = page.path("data");
        System.out.println("W05_CLARIFICATION_ORDER_PAGE " + pageRows);
        // The frozen UI reads POST /history for the durable head; the clarification Run must
        // expose it there or the SDK keeps the submitted human optimistic and answers the card.
        JsonNode history = postJson("/api/threads/" + threadId + "/history", "{\"limit\":1}");
        System.out.println("W05_CLARIFICATION_ORDER_HISTORY " + history);
        JsonNode headMessages = history.path(0).path("values").path("messages");
        assertCardAfterTriggeringHuman(headMessages, question, true, "history head");
        assertNoClarificationAnswerByComposerBypass(headMessages, "history head");

        // The page endpoint projects canonical turns only; the card is not one of them, but a
        // card that is present must still follow the human.
        assertCardAfterTriggeringHuman(pageRows, question, false, "messages page");
        assertNoClarificationAnswerByComposerBypass(pageRows, "messages page");
    }

    private void assertCardAfterTriggeringHuman(
            JsonNode rows, String question, boolean requireCard, String source) {
        List<JsonNode> messages = messageContents(rows);
        int humanIndex = -1;
        int cardIndex = -1;
        boolean monotonic = true;
        Long previousSeq = null;
        for (int index = 0; index < messages.size(); index++) {
            JsonNode message = messages.get(index);
            if (message.path("type").asText().equals("human")
                    && message.path("content").asText().contains(question)
                    && !message.path("additional_kwargs").path("hide_from_ui").asBoolean()) {
                humanIndex = index;
            }
            if (isClarificationCard(message)) {
                cardIndex = index;
            }
            JsonNode seqNode = message.path("additional_kwargs").path("deerflow_seq");
            if (seqNode.isNumber()) {
                long seq = seqNode.asLong();
                if (previousSeq != null && seq <= previousSeq) {
                    monotonic = false;
                }
                previousSeq = seq;
            }
        }
        assertThat(humanIndex).as(source + " has a visible triggering human: " + messages)
                .isGreaterThanOrEqualTo(0);
        if (requireCard) {
            assertThat(cardIndex).as(source + " has the clarification card: " + messages)
                    .isGreaterThanOrEqualTo(0);
        }
        if (cardIndex >= 0) {
            assertThat(humanIndex)
                    .as(source + " must project the triggering human before its clarification card: "
                            + messages)
                    .isLessThan(cardIndex);
        }
        assertThat(monotonic).as(source + " deerflow_seq must be monotonic: " + messages).isTrue();
    }

    private static List<JsonNode> messageContents(JsonNode rows) {
        List<JsonNode> messages = new ArrayList<>();
        if (rows == null || !rows.isArray()) {
            return messages;
        }
        for (JsonNode row : rows) {
            if (row.has("type")) {
                messages.add(row);
            } else if (row.has("content") && row.path("content").isObject()) {
                messages.add(row.path("content"));
            } else {
                messages.add(row);
            }
        }
        return messages;
    }

    private static boolean isClarificationCard(JsonNode message) {
        return "ask_clarification"
                .equals(message.path("artifact").path("human_input").path("source").asText());
    }

    /**
     * Frozen {@code deriveHumanInputThreadState} composer-bypass closure: a visible plain human
     * message after the request answers the latest open request. Assert the card is not answered.
     */
    private void assertNoClarificationAnswerByComposerBypass(JsonNode rows, String source) {
        List<JsonNode> messages = messageContents(rows);
        boolean requestSeen = false;
        for (JsonNode message : messages) {
            if (isClarificationCard(message)) {
                requestSeen = true;
                continue;
            }
            boolean visibleHuman = "human".equals(message.path("type").asText())
                    && !message.path("additional_kwargs").path("hide_from_ui").asBoolean()
                    && !message.path("additional_kwargs").has("human_input_response");
            if (requestSeen && visibleHuman) {
                throw new AssertionError(
                        source + " treats the card as answered by a later human message: " + messages);
            }
        }
    }

    private JsonNode postJson(String path, String body) throws Exception {
        HttpResponse<String> response = request("POST", path, body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    // ---- Oracle #4b: slow model is cancelled and mapped to a clarification end ------------------

    @Test
    void slowModelIsCancelledAndMappedToTheFrozenClarificationSuccess() throws Exception {
        startProvider(request -> {
            if (!hasClarificationToolResult(request)) {
                return clarificationToolCall();
            }
            sleepQuietly(1_500);
            return text("SLOW_SHOULD_NOT_APPEAR");
        });
        startGateway();
        String threadId = "clarify-slow";
        createThread(threadId);

        RunResult run = streamRun(threadId, "please clarify which environment");

        assertClarificationCard(clarificationCardInState(threadId));
        assertThat(run.body()).doesNotContain("SLOW_SHOULD_NOT_APPEAR");
        JsonNode latest = awaitTerminal(threadId, run.runId());
        assertThat(latest.path("diagnostics").path("sdk_status").asText()).isEqualTo("CANCELLED");
        assertThat(latest.path("status").asText()).isEqualTo("success");
        assertThat(state(threadId).toString()).doesNotContain("SLOW_SHOULD_NOT_APPEAR");
        System.out.println("W05_CLARIFICATION_SLOW_EVIDENCE thread=" + threadId + " run=" + run.runId()
                + " sdk_status=CANCELLED mapped_status=" + latest.path("status").asText());
    }

    // ---- Oracle #5: a same-turn SH sibling never produces a workspace side effect ----------------

    @Test
    void siblingWriteFileAfterClarificationLeavesNoWorkspaceChange() throws Exception {
        startProvider(request -> hasClarificationToolResult(request)
                ? text("IGNORED_PRECANCEL")
                : clarificationAndWriteFileToolCall());
        startGateway();
        String threadId = "clarify-sibling";
        createThread(threadId);

        RunResult run = streamRun(threadId, "please clarify then write");

        JsonNode state = state(threadId);
        assertThat(state.toString()).doesNotContain("SHOULD_NOT_BE_WRITTEN");
        assertThat(state.path("values").path("artifacts")).isEmpty();
        assertThat(outputFileCount("sibling-should-not-exist.txt")).isZero();
        // The write_file sibling never executed. It is either dropped by the approval gate
        // (WAITING_APPROVAL) or cancelled with the Run (CANCELLED); either way it is never COMPLETED
        // and produced no file, artifact or projected byte.
        assertThat(outputFileCount("sibling-should-not-exist.txt")).isZero();
        System.out.println("W05_CLARIFICATION_SIBLING_EVIDENCE thread=" + threadId + " run=" + run.runId()
                + " status=" + run.status() + " file=false artifact=false");
    }

    // ---- Oracle #7: the hidden reply opens a new Turn and answers the card ----------------------

    @Test
    void clarificationReplyStartsANewTurnAndMarksTheCardAnswered() throws Exception {
        startProvider(request -> {
            if (!hasClarificationToolResult(request)) {
                return clarificationToolCall();
            }
            if (lastUserText(request).contains("MY_ANSWER")) {
                return text("CONTINUED_WITH_ANSWER");
            }
            return text("IGNORED_PRECANCEL");
        });
        startGateway();
        String threadId = "clarify-reply";
        createThread(threadId);
        RunResult first = streamRun(threadId, "please clarify which environment");
        String requestId = requestId(clarificationCardInState(threadId));

        RunResult reply = streamClarificationReply(threadId, requestId, "MY_ANSWER");

        assertThat(reply.status()).isEqualTo("success");
        assertThat(reply.body()).contains("CONTINUED_WITH_ANSWER");
        assertThat(reply.runId()).isNotEqualTo(first.runId());
        JsonNode state = state(threadId);
        JsonNode hidden = hiddenClarificationAnswer(state, requestId);
        assertThat(hidden).isNotNull();
        assertThat(hidden.path("additional_kwargs").path("hide_from_ui").asBoolean()).isTrue();
        System.out.println("W05_CLARIFICATION_REPLY_EVIDENCE thread=" + threadId + " first=" + first.runId()
                + " reply=" + reply.runId() + " request_id=" + requestId + " answer=MY_ANSWER");
    }

    @Test
    void secondClarificationReplyDoesNotOpenAnotherTurn() throws Exception {
        startProvider(request -> {
            if (!hasClarificationToolResult(request)) {
                return clarificationToolCall();
            }
            if (lastUserText(request).contains("MY_ANSWER")) {
                return text("CONTINUED_WITH_ANSWER");
            }
            return text("SECOND_TURN");
        });
        startGateway();
        String threadId = "clarify-once";
        createThread(threadId);
        streamRun(threadId, "please clarify which environment");
        String requestId = requestId(clarificationCardInState(threadId));
        streamClarificationReply(threadId, requestId, "MY_ANSWER");

        String duplicate = """
                {"version":1,"kind":"human_input_response","source":"ask_clarification",
                "request_id":"%s","response_kind":"text","value":"SECOND_ANSWER"}
                """.formatted(requestId);
        HttpResponse<String> rejected = request(
                "POST",
                "/api/threads/" + threadId + "/runs/stream",
                humanMessage("SECOND_ANSWER", "resp-SECOND_ANSWER", duplicate));
        assertThat(rejected.statusCode()).isEqualTo(409);
        assertThat(rejected.body()).contains("already accepted");
        assertThat(state(threadId).toString()).doesNotContain("SECOND_ANSWER").doesNotContain("SECOND_TURN");
    }

    // ---- Oracle #8: bypassing the card starts a normal Turn -------------------------------------

    @Test
    void bypassingTheCardStartsANormalTurn() throws Exception {
        startProvider(request -> {
            if (!hasClarificationToolResult(request)) {
                return clarificationToolCall();
            }
            if (lastUserText(request).contains("JUST_A_MESSAGE")) {
                return text("BYPASS_OK");
            }
            return text("IGNORED_PRECANCEL");
        });
        startGateway();
        String threadId = "clarify-bypass";
        createThread(threadId);
        streamRun(threadId, "please clarify which environment");

        RunResult bypass = streamRun(threadId, "JUST_A_MESSAGE");
        assertThat(bypass.status()).isEqualTo("success");
        assertThat(bypass.body()).contains("BYPASS_OK");
    }

    // ---- Oracle #9: a forged/answered request id never triggers an approval ---------------------

    @Test
    void forgedClarificationRequestIdNeverTriggersApproval() throws Exception {
        startProvider(request -> {
            if (!hasClarificationToolResult(request)) {
                return clarificationToolCall();
            }
            return text("IGNORED_PRECANCEL");
        });
        startGateway();
        String threadId = "clarify-forged";
        createThread(threadId);
        streamRun(threadId, "please clarify which environment");

        // A source=ask_clarification reply whose request_id matches no card is still a normal Turn;
        // it must never be treated as an approval (there is no pending Interaction to approve).
        RunResult forged = streamClarificationReply(threadId, "clarification:forged-id", "FORGED");
        assertThat(forged.status()).isEqualTo("success");
        assertThat(pendingInteraction(threadId, forged.runId())).isFalse();
        assertThat(state(threadId).toString()).doesNotContain("approve");
        System.out.println("W05_CLARIFICATION_FORGED_EVIDENCE thread=" + threadId + " run=" + forged.runId()
                + " status=" + forged.status() + " approval=false");
    }

    // ---- Oracle #10: card and answered state survive a restart ----------------------------------

    @Test
    void clarificationCardAndAnsweredStateSurviveAGatewayRestart() throws Exception {
        startProvider(request -> {
            if (!hasClarificationToolResult(request)) {
                return clarificationToolCall();
            }
            if (lastUserText(request).contains("PERSISTED_ANSWER")) {
                return text("AFTER_RESTART_CONTINUATION");
            }
            return text("IGNORED_PRECANCEL");
        });
        startGateway();
        String threadId = "clarify-restart";
        createThread(threadId);
        RunResult first = streamRun(threadId, "please clarify which environment");
        String requestId = requestId(clarificationCardInState(threadId));
        RunResult reply = streamClarificationReply(threadId, requestId, "PERSISTED_ANSWER");

        api.close();
        api = null;
        startGateway();

        JsonNode reloaded = state(threadId);
        assertClarificationCard(clarificationCardInState(threadId));
        assertThat(hiddenClarificationAnswer(reloaded, requestId)).isNotNull();
        assertThat(latestRun(threadId, first.runId()).path("status").asText()).isEqualTo("success");
        System.out.println("W05_CLARIFICATION_RESTART_EVIDENCE thread=" + threadId + " first=" + first.runId()
                + " reply=" + reply.runId() + " request_id=" + requestId + " recovered=true");
    }

    // ---- controlled provider -------------------------------------------------------------------

    private void startProvider(Function<JsonNode, byte[]> responder) throws IOException {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        providerExecutor = Executors.newVirtualThreadPerTaskExecutor();
        provider.setExecutor(providerExecutor);
        provider.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            byte[] response = responder.apply(request);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        provider.start();
    }

    private byte[] clarificationToolCall() {
        String arguments = jsonValue(Map.of(
                "question", QUESTION,
                "clarification_type", "approach_choice",
                "options", List.of("development", "production")));
        return toolCalls(List.of(Map.of("id", "call-clarify-1", "name", "ask_clarification", "arguments", arguments)));
    }

    private byte[] clarificationAndWriteFileToolCall() {
        String clarifyArgs = jsonValue(Map.of("question", QUESTION, "clarification_type", "missing_info"));
        String writeArgs = jsonValue(Map.of(
                "path", "/mnt/user-data/outputs/sibling-should-not-exist.txt",
                "content", "SHOULD_NOT_BE_WRITTEN"));
        return toolCalls(List.of(
                Map.of("id", "call-clarify-1", "name", "ask_clarification", "arguments", clarifyArgs),
                Map.of("id", "call-write-1", "name", "write_file", "arguments", writeArgs)));
    }

    private long outputFileCount(String filename) throws IOException {
        Path workspace = dataRoot.resolve("workspace");
        if (!Files.exists(workspace)) {
            return 0;
        }
        try (var paths = Files.walk(workspace)) {
            return paths.filter(path -> path.getFileName().toString().equals(filename)).count();
        }
    }

    private byte[] toolCalls(List<Map<String, String>> calls) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int index = 0; index < calls.size(); index++) {
            Map<String, String> call = calls.get(index);
            entries.add(Map.of(
                    "index", index, "id", call.get("id"), "type", "function",
                    "function", Map.of("name", call.get("name"), "arguments", call.get("arguments"))));
        }
        String chunk = jsonValue(Map.of(
                "id", "provider-clarify", "model", "clarify-controlled",
                "choices", List.of(Map.of("index", 0, "finish_reason", "tool_calls",
                        "delta", Map.of("tool_calls", entries)))));
        return sse("provider-clarify", chunk);
    }

    private byte[] text(String content) {
        String chunk = jsonValue(Map.of(
                "id", "provider-clarify-text", "model", "clarify-controlled",
                "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                        "delta", Map.of("content", content)))));
        return sse("provider-clarify-text", chunk);
    }

    private byte[] sse(String responseId, String... chunks) {
        StringBuilder body = new StringBuilder();
        for (String chunk : chunks) {
            body.append("data: ").append(chunk).append("\n\n");
        }
        body.append("data: {\"id\":\"").append(responseId).append("\",\"model\":\"clarify-controlled\",")
                .append("\"choices\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":3}}\n\n")
                .append("data: [DONE]\n\n");
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String jsonValue(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean hasClarificationToolResult(JsonNode request) {
        for (JsonNode message : request.path("messages")) {
            if ("tool".equals(message.path("role").asText())
                    && message.path("content").asText().contains(QUESTION)) {
                return true;
            }
        }
        return false;
    }

    private static String lastUserText(JsonNode request) {
        String result = "";
        for (JsonNode message : request.path("messages")) {
            if ("user".equals(message.path("role").asText())) {
                result = textOf(message.path("content"));
            }
        }
        return result;
    }

    private static String textOf(JsonNode content) {
        if (content.isTextual()) return content.asText();
        StringBuilder text = new StringBuilder();
        for (JsonNode part : content) text.append(part.path("text").asText());
        return text.toString();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- HTTP helpers --------------------------------------------------------------------------

    private void startGateway() throws Exception {
        api = DeerFlowBootstrapApi.create(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataRoot,
                Map.of("HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W05_CLARIFY_KEY",
                        "W05_CLARIFY_KEY", key));
        api.start();
        base = URI.create("http://127.0.0.1:" + api.address().getPort());
        String endpoint = "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1";
        String body = """
                {"config":{"name":"clarify-model","display_name":"Clarify Controlled",
                "provider":"openai-compatible","model":"clarify-controlled",
                "base_url":"%s","enabled":true,"supports_vision":false,
                "context_window":32768,"max_tokens":1024,"api_key":"controlled-key"},
                "expected_revision":null}
                """.formatted(endpoint);
        HttpResponse<String> saved = request("PUT", "/api/managed-models", body);
        assertThat(saved.statusCode()).as(saved.body()).isIn(200, 409);
    }

    private void createThread(String threadId) throws Exception {
        assertThat(request("POST", "/api/threads", "{\"thread_id\":\"" + threadId + "\"}").statusCode())
                .isEqualTo(200);
    }

    private RunResult streamRun(String threadId, String text) throws Exception {
        return stream(threadId, humanMessage(text, null, null));
    }

    private RunResult streamClarificationReply(String threadId, String requestId, String value) {
        String response = """
                {"version":1,"kind":"human_input_response","source":"ask_clarification",
                "request_id":"%s","response_kind":"text","value":"%s"}
                """.formatted(requestId, value);
        try {
            return stream(threadId, humanMessage(value, "resp-" + value, response));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private String humanMessage(String text, String id, String humanResponse) {
        Map<String, Object> additional = new LinkedHashMap<>();
        if (humanResponse != null) {
            additional.put("hide_from_ui", true);
            try {
                additional.put("human_input_response", json.readTree(humanResponse));
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        Map<String, Object> message = new LinkedHashMap<>();
        if (id != null) message.put("id", id);
        message.put("type", "human");
        message.put("content", List.of(Map.of("type", "text", "text", text)));
        message.put("additional_kwargs", additional);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assistant_id", "lead_agent");
        body.put("input", Map.of("messages", List.of(message)));
        body.put("stream_mode", List.of("messages-tuple", "updates", "custom"));
        body.put("context", Map.of("model_name", "clarify-model", "mode", "flash"));
        body.put("on_disconnect", "continue");
        body.put("multitask_strategy", "reject");
        try {
            return json.writeValueAsString(body);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private RunResult stream(String threadId, String body) throws Exception {
        HttpResponse<String> stream = request("POST", "/api/threads/" + threadId + "/runs/stream", body);
        assertThat(stream.statusCode()).as(stream.body()).isEqualTo(200);
        String location = stream.headers().firstValue("content-location").orElseThrow();
        String runId = location.substring(location.lastIndexOf('/') + 1);
        JsonNode run = awaitTerminal(threadId, runId);
        return new RunResult(runId, run.path("status").asText(), stream.body(), run);
    }

    private JsonNode awaitTerminal(String threadId, String runId) throws Exception {
        JsonNode run = null;
        for (int attempt = 0; attempt < 400; attempt++) {
            run = requestJson("GET", "/api/threads/" + threadId + "/runs/" + runId);
            String status = run.path("status").asText();
            if (!status.equals("pending") && !status.equals("running")) {
                return run;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Run did not terminate: " + run);
    }

    private JsonNode latestRun(String threadId, String runId) throws Exception {
        return requestJson("GET", "/api/threads/" + threadId + "/runs/" + runId);
    }

    private JsonNode state(String threadId) throws Exception {
        return requestJson("GET", "/api/threads/" + threadId + "/state");
    }

    private JsonNode clarificationCardInState(String threadId) throws Exception {
        for (JsonNode message : state(threadId).path("values").path("messages")) {
            JsonNode payload = message.path("artifact").path("human_input");
            if ("ask_clarification".equals(payload.path("source").asText())
                    && !payload.path("request_id").asText().isBlank()) {
                return message;
            }
        }
        throw new AssertionError("missing clarification card in state: " + state(threadId));
    }

    private static void assertClarificationCard(JsonNode card) {
        assertThat(card.path("name").asText()).isEqualTo("ask_clarification");
        assertThat(card.path("type").asText()).isEqualTo("tool");
        assertThat(card.path("content").asText()).contains(QUESTION);
        assertThat(card.path("artifact").path("human_input").path("input_mode").asText())
                .isEqualTo("choice_with_other");
        assertThat(card.path("artifact").path("human_input").path("options")).hasSize(2);
    }

    private static String requestId(JsonNode card) {
        String value = card.path("artifact").path("human_input").path("request_id").asText();
        assertThat(value).startsWith("clarification:");
        return value;
    }

    private void assertToolCallCompleted(String threadId, String runId) throws Exception {
        JsonNode run = latestRun(threadId, runId);
        assertThat(run.path("diagnostics").path("tool_calls").findValuesAsText("status"))
                .contains("COMPLETED");
    }

    private JsonNode hiddenClarificationAnswer(JsonNode state, String requestId) {
        for (JsonNode message : state.path("values").path("messages")) {
            JsonNode response = message.path("additional_kwargs").path("human_input_response");
            if ("ask_clarification".equals(response.path("source").asText())
                    && requestId.equals(response.path("request_id").asText())) {
                return message;
            }
        }
        return null;
    }

    private boolean pendingInteraction(String threadId, String runId) throws Exception {
        JsonNode run = latestRun(threadId, runId);
        return "WAITING_APPROVAL".equals(run.path("diagnostics").path("sdk_status").asText())
                || "WAITING_INTERACTION".equals(run.path("diagnostics").path("sdk_status").asText());
    }

    private JsonNode requestJson(String method, String path) throws Exception {
        HttpResponse<String> response = request(method, path, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(30));
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private record RunResult(String runId, String status, String body, JsonNode run) {}
}

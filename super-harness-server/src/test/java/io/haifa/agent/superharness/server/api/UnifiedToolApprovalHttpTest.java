package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.superharness.server.testsupport.ControlledModel;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import io.haifa.agent.superharness.server.testsupport.SseEvents;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class UnifiedToolApprovalHttpTest extends AbstractDeerFlowThreadRunApiTest {
    private static final String THREAD = "unified-tool-approval";
    private static final String FILE = "/mnt/user-data/outputs/unified-approval.txt";
    private static final String BEFORE = "Review is pending.";
    private static final String AFTER = "Review is complete.";
    private static final String QUERY = "unified approval documentation";
    private static final String PAGE_URL = "https://example.com/docs/approval";
    private static final String TEST_QUERY_MARKER = "fixture-private-query-marker";

    @Test
    @Timeout(120)
    void realToolCardsMatchRefreshAndSessionConsentCoversFilesAndWebUntilRevoked() throws Exception {
        var searchCalls = new AtomicInteger();
        var fetchCalls = new AtomicInteger();
        HttpServer web = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        web.createContext("/search", exchange -> {
            searchCalls.incrementAndGet();
            respond(exchange, """
                    <div class="result__body">
                      <h2><a href="https://example.com/docs/approval">Approval docs</a></h2>
                      <a href="https://example.com/docs/approval">Unified approval search fixture</a>
                    </div>
                    """);
        });
        web.createContext("/reader", exchange -> {
            fetchCalls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            respond(exchange, "# Unified approval fetch fixture");
        });
        web.start();
        try {
            String endpoint = "http://127.0.0.1:" + web.getAddress().getPort();
            var model = LocalProbeModelTestSupport.binding(model());
            startApi(Map.of(
                    "HAIFA_SUPER_HARNESS_WEB_READ_APPROVAL", "ask",
                    "HAIFA_SUPER_HARNESS_DDG_HTML_URL", endpoint + "/search",
                    "HAIFA_SUPER_HARNESS_JINA_READER_URL", endpoint + "/reader"), ignored -> model);
            createThread(THREAD);
            assertConsent(false);

            var write = request(THREAD, "write_file", "write_file");
            assertThat(write.approval().path("facts").findValuesAsText("value")).contains(FILE);
            assertThat(write.card().path("content").asText()).isEqualTo(BEFORE);
            assertThat(get(artifactPath()).statusCode()).isEqualTo(404);
            assertThat(submit(THREAD, write, "auto_approve").statusCode()).isEqualTo(409);
            assertWaiting(write);
            assertResolved(write, "allow_session");
            assertConsent(true);
            assertThat(get(artifactPath()).body()).isEqualTo(BEFORE);

            var edit = request(THREAD, "str_replace", "str_replace");
            assertThat(edit.approval().path("facts").findValuesAsText("value")).contains(FILE);
            assertThat(edit.approval().path("before").asText()).isEqualTo(BEFORE);
            assertThat(edit.approval().path("after").asText()).isEqualTo(AFTER);
            assertThat(get(artifactPath()).body()).isEqualTo(BEFORE);
            assertResolved(edit, "auto_approve");
            assertThat(get(artifactPath()).body()).isEqualTo(AFTER);

            var search = request(THREAD, "web_search", "web_search");
            assertThat(search.approval().path("facts").findValuesAsText("value")).contains(QUERY);
            assertThat(searchCalls).hasValue(0);
            assertThat(assertResolved(search, "auto_approve")).contains("Unified approval search fixture");
            assertThat(searchCalls).hasValue(1);

            var fetch = request(THREAD, "web_fetch", "web_fetch");
            assertThat(fetch.approval().path("facts").findValuesAsText("value")).contains("example.com", PAGE_URL);
            assertThat(fetchCalls).hasValue(0);
            assertThat(assertResolved(fetch, "auto_approve")).contains("Unified approval fetch fixture");
            assertThat(fetchCalls).hasValue(1);

            var revoked = client.send(HttpRequest.newBuilder(baseUri.resolve(consentPath(THREAD)))
                    .timeout(Duration.ofSeconds(10)).DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(200);
            assertThat(json.readTree(revoked.body()).path("active").booleanValue()).isFalse();
            assertConsent(false);
            var afterRevoke = request(THREAD, "fetch_after_revoke", "web_fetch");
            assertThat(afterRevoke.card().toString()).doesNotContain(TEST_QUERY_MARKER);
            assertThat(submit(THREAD, afterRevoke, "auto_approve").statusCode()).isEqualTo(409);
            assertWaiting(afterRevoke);
            assertResolved(afterRevoke, "reject");
            assertThat(fetchCalls).hasValue(1);
            assertConsent(false);
        } finally {
            web.stop(0);
        }
    }

    private static ControlledModel model() {
        return new ControlledModel((request, ignored) -> {
            var messages = request.messages();
            int lastUser = -1;
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i).role() == ModelMessageRole.USER) lastUser = i;
            }
            if (lastUser < 0 || request.tools().isEmpty()) return ControlledModel.stop(request);
            String prompt = messages.get(lastUser).content();
            long results = messages.subList(lastUser + 1, messages.size()).stream()
                    .filter(message -> message.role() == ModelMessageRole.TOOL)
                    .count();
            if (prompt.contains("approval-case:str_replace") && results < 2) {
                // Production edits require read-before-write in the same workspace.
                return results == 0
                        ? ControlledModel.call(request, "read_file", Map.of("path", FILE))
                        : ControlledModel.call(request, "str_replace", Map.of(
                                "path", FILE, "old_str", BEFORE, "new_str", AFTER, "replace_all", false));
            }
            if (results != 0) return ControlledModel.stop(request);
            if (prompt.contains("approval-case:write_file")) {
                return ControlledModel.call(request, "write_file", Map.of(
                        "path", FILE, "content", BEFORE, "description", "Write the initial review", "append", false));
            }
            if (prompt.contains("approval-case:web_search")) {
                return ControlledModel.call(request, "web_search", Map.of(
                        "query", QUERY, "max_results", 3, "time_range", "month"));
            }
            if (prompt.contains("approval-case:fetch_after_revoke")) {
                return ControlledModel.call(request, "web_fetch", Map.of(
                        "url", PAGE_URL + "?access_token=" + TEST_QUERY_MARKER));
            }
            return prompt.contains("approval-case:web_fetch")
                    ? ControlledModel.call(request, "web_fetch", Map.of("url", PAGE_URL))
                    : ControlledModel.stop(request);
        });
    }

    private Pending request(String thread, String action, String toolName) throws Exception {
        var body = Map.of("assistant_id", "lead_agent",
                "input", Map.of("messages", List.of(Map.of(
                        "id", UUID.randomUUID().toString(), "type", "human", "content", "approval-case:" + action))),
                "stream_mode", List.of("messages-tuple", "updates", "custom"),
                "on_disconnect", "continue", "multitask_strategy", "reject");
        var response = stream(thread, json.writeValueAsString(body));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var cards = SseEvents.read(response.body()).named("messages").stream()
                .filter(event -> event.data().isArray() && !event.data().isEmpty())
                .map(event -> event.data().get(0).path("artifact").path("human_input"))
                .filter(card -> card.hasNonNull("request_id"))
                .toList();
        assertThat(cards).as("one real SDK approval for " + toolName).hasSize(1);
        var pending = new Pending(thread, response.headers().firstValue("content-location").orElseThrow(), cards.getFirst());
        assertThat(pending.approval().path("tool_name").asText()).isEqualTo(toolName);
        assertThat(pending.approval().path("impact")).isNotEmpty();
        if (!"fetch_after_revoke".equals(action)) {
            assertThat(pending.approval().path("session_eligible").booleanValue()).isTrue();
            assertThat(pending.card().path("options").findValuesAsText("id"))
                    .contains("approve", "reject", "allow_session");
        }
        assertWaiting(pending);
        return pending;
    }

    private void assertWaiting(Pending pending) throws Exception {
        assertThat(getJson(pending.location()).path("diagnostics").path("sdk_status").asText())
                .isEqualTo("WAITING_APPROVAL");
        assertThat(refreshedCard(pending)).isEqualTo(pending.card());
    }

    private JsonNode refreshedCard(Pending pending) throws Exception {
        for (JsonNode message : getJson("/api/threads/" + pending.thread() + "/state").path("values").path("messages")) {
            JsonNode card = message.path("artifact").path("human_input");
            if (pending.requestId().equals(card.path("request_id").asText())) return card;
        }
        throw new AssertionError("Approval card missing after refresh: " + pending.requestId());
    }

    private String assertResolved(Pending pending, String option) throws Exception {
        var response = submit(pending.thread(), pending, option);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-location")).contains(pending.location());
        var messages = getJson("/api/threads/" + pending.thread() + "/state").path("values").path("messages");
        var answers = messages.findValues("human_input_response").stream()
                .filter(answer -> pending.requestId().equals(answer.path("request_id").asText()))
                .map(answer -> answer.path("option_id").asText())
                .toList();
        assertThat(answers).containsExactly(option);
        assertThat(refreshedCard(pending)).isEqualTo(pending.card());
        return response.body();
    }

    private HttpResponse<String> submit(String thread, Pending pending, String option) throws Exception {
        return stream(thread, SseEvents.PendingApproval.reply(pending.requestId(), option));
    }

    private HttpResponse<String> stream(String thread, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(baseUri.resolve("/api/threads/" + thread + "/runs/stream"))
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(22))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertConsent(boolean active) throws Exception {
        assertThat(getJson(consentPath(THREAD)).path("active").booleanValue()).isEqualTo(active);
    }

    private static String consentPath(String thread) {
        return "/api/threads/" + thread + "/execution-approval-session";
    }

    private static String artifactPath() {
        return "/api/threads/" + THREAD + "/artifacts" + FILE;
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private record Pending(String thread, String location, JsonNode card) {
        String requestId() {
            return card.path("request_id").asText();
        }

        JsonNode approval() {
            return card.path("tool_approval");
        }
    }
}

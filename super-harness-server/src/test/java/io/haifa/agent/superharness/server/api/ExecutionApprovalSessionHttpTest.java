package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.superharness.server.config.ExecutionProviderConfig;
import io.haifa.agent.superharness.server.testsupport.ControlledModel;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ExecutionApprovalSessionHttpTest extends AbstractDeerFlowThreadRunApiTest {
    private static final String MARKER = "haifa-approval-session-probe";
    private static final String COMMAND = "echo " + MARKER;
    private static final String PURPOSE = "Exercise the execution approval card";
    private static final Pattern MESSAGE_FRAME = Pattern.compile("(?m)^event: messages\\Rdata: ([^\\r\\n]+)");

    @Test
    @Timeout(90)
    void executionApprovalSurvivesRefreshAndConsentIsLimitedToOneSessionUntilRevoked() throws Exception {
        var model = new ControlledModel((request, index) -> {
            var messages = request.messages();
            int lastUser = -1;
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i).role() == ModelMessageRole.USER) lastUser = i;
            }
            boolean executed = messages.subList(lastUser + 1, messages.size()).stream()
                    .anyMatch(message -> message.role() == ModelMessageRole.TOOL);
            boolean hasExecutionTool = request.tools().stream().anyMatch(tool -> "execution_run".equals(tool.name()));
            return executed || !hasExecutionTool
                    ? ControlledModel.stop(request)
                    : ControlledModel.call(request, "execution_run", Map.of(
                            "mode", "COMMAND", "content", COMMAND, "purpose", PURPOSE, "timeoutMillis", 10_000));
        });
        var binding = LocalProbeModelTestSupport.binding(model);
        startApi(Map.of(ExecutionProviderConfig.PROVIDER_ENVIRONMENT_KEY, "host-guarded"), ignored -> binding);
        String thread = "execution-approval-thread";
        createThread(thread);

        assertConsent(thread, false);
        var first = requestExecution(thread, "Initial request");
        assertThat(first.card().path("purpose").asText()).isEqualTo(PURPOSE);
        assertThat(first.card().path("content").asText()).isEqualTo(COMMAND);
        assertThat(first.card().path("tool_approval").path("session_eligible").asBoolean()).isTrue();
        assertThat(first.card().path("options").findValuesAsText("id")).contains("approve", "reject", "allow_session");
        assertThat(refreshedCard(thread, first.requestId())).isEqualTo(first.card());
        assertConsent(thread, false);
        assertWaitingAfterRejectedAutoApproval(thread, first);

        assertExecuted(first, submit(thread, first, "allow_session"));
        assertConsent(thread, true);
        assertThat(refreshedCard(thread, first.requestId())).isEqualTo(first.card());
        assertRecordedAnswer(thread, first, "allow_session");

        String otherThread = "other-execution-approval-thread";
        createThread(otherThread);
        assertConsent(otherThread, false);
        var other = requestExecution(otherThread, "Request in another conversation");
        assertWaitingAfterRejectedAutoApproval(otherThread, other);

        var followUp = requestExecution(thread, "Follow-up request");
        assertExecuted(followUp, submit(thread, followUp, "auto_approve"));
        assertRecordedAnswer(thread, followUp, "auto_approve");

        var revoked = client.send(HttpRequest.newBuilder(baseUri.resolve(consentPath(thread)))
                .timeout(Duration.ofSeconds(10)).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(200);
        assertThat(json.readTree(revoked.body()).path("active").booleanValue()).isFalse();
        assertConsent(thread, false);
        var afterRevoke = requestExecution(thread, "Request after revocation");
        assertWaitingAfterRejectedAutoApproval(thread, afterRevoke);
        assertExecuted(afterRevoke, submit(thread, afterRevoke, "approve"));
        assertConsent(thread, false);
    }

    private PendingExecution requestExecution(String thread, String prompt) throws Exception {
        var response = stream(thread, Map.of("id", "execution-" + prompt.replace(' ', '-'), "type", "human",
                "content", List.of(Map.of("type", "text", "text", prompt))));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var card = sseMessages(response.body()).stream()
                .map(message -> message.path("artifact").path("human_input"))
                .filter(candidate -> candidate.hasNonNull("request_id"))
                .findFirst().orElseThrow(() -> new AssertionError("Missing approval card: " + response.body()));
        return new PendingExecution(response.headers().firstValue("content-location").orElseThrow(), card);
    }

    private HttpResponse<String> submit(String thread, PendingExecution pending, String option) throws Exception {
        return stream(thread, Map.of(
                "id", "execution-answer-" + pending.requestId(),
                "type", "human",
                "content", List.of(Map.of("type", "text", "text", "For your clarification, my answer is: " + option)),
                "additional_kwargs", Map.of("hide_from_ui", true, "human_input_response", Map.of(
                        "version", 1, "kind", "human_input_response", "source", "haifa_interaction",
                        "request_id", pending.requestId(), "response_kind", "option", "option_id", option,
                        "value", option))));
    }

    private HttpResponse<String> stream(String thread, Map<String, Object> message) throws Exception {
        var body = Map.of("assistant_id", "lead_agent", "input", Map.of("messages", List.of(message)),
                "stream_mode", List.of("messages-tuple", "updates", "custom"),
                "on_disconnect", "continue", "multitask_strategy", "reject");
        return client.send(HttpRequest.newBuilder(baseUri.resolve("/api/threads/" + thread + "/runs/stream"))
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(22))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode refreshedCard(String thread, String requestId) throws Exception {
        for (JsonNode message : getJson("/api/threads/" + thread + "/state").path("values").path("messages")) {
            JsonNode candidate = message.path("artifact").path("human_input");
            if (requestId.equals(candidate.path("request_id").asText())) return candidate;
        }
        throw new AssertionError("Refreshed state did not contain card for " + requestId);
    }

    private void assertRecordedAnswer(String thread, PendingExecution pending, String option) throws Exception {
        var messages = getJson("/api/threads/" + thread + "/state").path("values").path("messages");
        List<String> answers = new ArrayList<>();
        for (JsonNode message : messages) {
            var candidate = message.path("additional_kwargs").path("human_input_response");
            if (pending.requestId().equals(candidate.path("request_id").asText())) {
                answers.add(candidate.path("option_id").asText());
            }
        }
        assertThat(answers).containsExactly(option);
    }

    private void assertWaitingAfterRejectedAutoApproval(String thread, PendingExecution pending) throws Exception {
        var denied = submit(thread, pending, "auto_approve");
        assertThat(denied.statusCode()).as(denied.body()).isEqualTo(409);
        assertThat(getJson(pending.location()).path("status").asText()).isEqualTo("interrupted");
        assertThat(refreshedCard(thread, pending.requestId())).isEqualTo(pending.card());
    }

    private void assertExecuted(PendingExecution pending, HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-location")).contains(pending.location());
        assertThat(getJson(pending.location()).path("status").asText()).isEqualTo("success");
        assertThat(sseMessages(response.body()).stream()
                .filter(message -> "tool".equals(message.path("type").asText()))
                .filter(message -> "execution_run".equals(message.path("name").asText()))
                .map(message -> message.path("content").asText()))
                .anySatisfy(content -> assertThat(content).contains(MARKER));
    }

    private void assertConsent(String thread, boolean expected) throws Exception {
        var state = getJson(consentPath(thread)).path("active");
        assertThat(state.isBoolean()).isTrue();
        assertThat(state.booleanValue()).isEqualTo(expected);
    }

    private List<JsonNode> sseMessages(String transcript) throws Exception {
        var matcher = MESSAGE_FRAME.matcher(transcript);
        List<JsonNode> messages = new ArrayList<>();
        while (matcher.find()) {
            var frame = json.readTree(matcher.group(1));
            if (frame.isArray() && !frame.isEmpty()) messages.add(frame.get(0));
        }
        return messages;
    }

    private static String consentPath(String thread) {
        return "/api/threads/" + thread + "/execution-approval-session";
    }

    private record PendingExecution(String location, JsonNode card) {
        String requestId() {
            return card.path("request_id").asText();
        }
    }
}

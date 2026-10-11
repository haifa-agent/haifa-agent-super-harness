package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.superharness.server.testsupport.ControlledModel;
import io.haifa.agent.superharness.server.testsupport.DelegationHttpFixture;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real Gateway and published SDK: settled Tool rounds survive the frozen page/reload contract. */
@Timeout(60)
class W08CompletedDelegationPageTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void twoSuccessfulTaskRoundsRetainExactNativeMessagesAndBackwardPagesAfterRestart(boolean withText)
            throws Exception {
        Path data = GatewayFixture.dataDirectory();
        Path evidence = GatewayFixture.evidenceDirectory();
        AtomicInteger parent = new AtomicInteger();
        var model = new ControlledModel((request, call) -> {
            if (request.messages().stream().anyMatch(message ->
                    message.content().contains(DelegationHttpFixture.CHILD_MARKER))) {
                return ControlledModel.response(request, "child-" + call, "CHILD-OUTPUT", List.of(),
                        ModelFinishReason.STOP, ModelUsage.unpriced(1, 1));
            }
            int round = parent.incrementAndGet();
            if (round <= 2) {
                var task = new ModelToolCall(new ProviderToolCallCorrelationId("provider-task-" + round),
                        "task", Map.of("agent", "researcher", "objective", "Bounded task round " + round));
                return ControlledModel.response(request, "parent-" + call,
                        withText ? "Delegating round " + round + "." : "", List.of(task),
                        ModelFinishReason.TOOL_CALLS, ModelUsage.unpriced(1, 1));
            }
            return ControlledModel.response(request, "parent-" + call, "PARENT-FINAL", List.of(),
                    ModelFinishReason.STOP, ModelUsage.unpriced(1, 1));
        });
        String thread = "completed-page-" + withText;
        JsonNode original;
        try (var http = new GatewayFixture.HttpEvidence(evidence, Duration.ofSeconds(40))) {
            try (var gateway = new GatewayFixture(data, model, evidence)) {
                DelegationHttpFixture.configure(http, gateway.base, 1);
                DelegationHttpFixture.seed(http, gateway.base, "researcher", 30, 3, List.of());
                DelegationHttpFixture.execute(http, gateway.base, thread, DelegationHttpFixture.input(true));
                JsonNode state = http.get(gateway.base, "/api/threads/" + thread + "/state", "settled-state.json");
                original = assertPageAndCursors(http, gateway.base, thread, "before-restart");
                assertThat(original).hasSize(6);
                List<JsonNode> stateMessages = DelegationHttpFixture.messages(state);
                List<JsonNode> taskRequests = stateMessages.stream()
                        .filter(message -> !message.path("tool_calls").isEmpty()).toList();
                assertThat(taskRequests).hasSize(2);
                List<String> taskIds = new ArrayList<>();
                for (int round = 0; round < taskRequests.size(); round++) {
                    JsonNode request = taskRequests.get(round);
                    String id = request.path("tool_calls").get(0).path("id").asText();
                    taskIds.add(id);
                    assertThat(id).isNotBlank().doesNotStartWith("provider-task-");
                    // State's Tool overlay omits the native ordinary text; the page folds that
                    // exact accepted message's text onto its request while preserving its IDs.
                    ObjectNode expectedRequest = request.deepCopy();
                    expectedRequest.put("content", withText ? "Delegating round " + (round + 1) + "." : "");
                    List<JsonNode> pageRequests = contents(original).stream()
                            .filter(message -> message.path("id").equals(request.path("id"))).toList();
                    assertThat(pageRequests).containsExactly(expectedRequest);
                    JsonNode result = stateMessages.stream()
                            .filter(message -> id.equals(message.path("tool_call_id").asText())).findFirst().orElseThrow();
                    assertThat(result.path("additional_kwargs").path("subagent_status").asText()).isEqualTo("completed");
                    assertThat(result.path("content").asText()).contains("CHILD-OUTPUT");
                    assertThat(contents(original).stream()
                            .filter(message -> id.equals(message.path("tool_call_id").asText())).toList())
                            .containsExactly(result);
                    assertThat(original.get(1 + round * 2).path("content")).isEqualTo(expectedRequest);
                    assertThat(original.get(2 + round * 2).path("content")).isEqualTo(result);
                }
                assertThat(taskIds).doesNotHaveDuplicates();
                assertThat(original.get(5).path("content").path("content").asText()).isEqualTo("PARENT-FINAL");
                assertThat(contents(original).stream().map(message -> message.path("id").asText()).toList())
                        .doesNotHaveDuplicates();
            }
            try (var reopened = new GatewayFixture(data, model, evidence)) {
                assertThat(assertPageAndCursors(http, reopened.base, thread, "after-restart"))
                        .isEqualTo(original);
            }
        }
    }

    private static List<JsonNode> contents(JsonNode rows) {
        List<JsonNode> result = new ArrayList<>();
        rows.forEach(row -> result.add(row.path("content")));
        return result;
    }

    private static JsonNode assertPageAndCursors(GatewayFixture.HttpEvidence http, URI base, String thread,
            String evidenceName) throws Exception {
        String path = "/api/threads/" + thread + "/messages/page";
        JsonNode rows = http.get(base, path + "?limit=50", evidenceName + ".json").path("data");
        List<Long> seqs = new ArrayList<>();
        rows.forEach(row -> seqs.add(row.path("seq").asLong()));
        assertThat(seqs).doesNotHaveDuplicates();
        List<Long> sorted = new ArrayList<>(seqs);
        Collections.sort(sorted);
        assertThat(seqs).isEqualTo(sorted);
        Long before = null;
        for (int index = rows.size() - 1; index >= 0; index--) {
            JsonNode page = http.get(base, path + "?limit=1" + (before == null ? "" : "&before_seq=" + before),
                    evidenceName + "-cursor-" + index + ".json");
            assertThat(page.path("data")).hasSize(1);
            assertThat(page.path("data").get(0)).isEqualTo(rows.get(index));
            assertThat(page.path("has_more").asBoolean()).isEqualTo(index > 0);
            before = page.path("data").get(0).path("seq").asLong();
        }
        return rows;
    }
}

package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
import io.haifa.agent.superharness.server.run.HaifaPublicInteractionSpikeTest;
import io.haifa.agent.superharness.server.run.HaifaRunBridge;
import io.haifa.agent.superharness.server.testsupport.ControlledModel;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(60)
class ChatHistoryNarrationHttpTest extends AbstractDeerFlowThreadRunApiTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void narratedToolRoundsKeepIdentityPositionAndFailureAcrossReload(boolean fail) throws Exception {
        api.close();
        var model = new ControlledModel((request, call) -> {
            if (call <= 2) return ControlledModel.response(request, "round-" + call, "Narration " + call,
                    List.of(new ModelToolCall(new ProviderToolCallCorrelationId("call-" + call),
                            "mutating_probe", Map.of("value", "value-" + call))),
                    ModelFinishReason.TOOL_CALLS, ModelUsage.unpriced(1, 1));
            if (fail && call == 3) throw new IllegalStateException("controlled-narration-failure");
            return ControlledModel.stop(request);
        });
        var binding = new HaifaRunBridge.ModelBinding("interaction-probe", "1.0.0", model,
                HaifaPublicInteractionSpikeTest.modelSnapshot());
        api = DeerFlowBootstrapApi.createForInteractionTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory, continuationKey, ignored -> binding,
                List.of(new HaifaPublicInteractionSpikeTest.MutatingProbeTool(new AtomicInteger())));
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
        createThread("narrated");
        HttpResponse<String> stream = client.send(runRequest("narrated", "old question"), HttpResponse.BodyHandlers.ofString());
        String location = stream.headers().firstValue("content-location").orElseThrow();
        for (int round = 0; round < 2; round++) {
            String requestId = "";
            for (String line : stream.body().split("\n")) {
                if (!line.startsWith("data: [")) continue;
                JsonNode tuple = json.readTree(line.substring(6));
                String id = tuple.path(0).path("artifact").path("human_input").path("request_id").asText();
                if (!id.isBlank()) requestId = id;
            }
            assertThat(requestId).as(stream.body()).isNotBlank();
            stream = postJson("/api/threads/narrated/runs/stream", """
                    {"assistant_id":"lead_agent","input":{"messages":[{"type":"human","content":"approve",
                    "additional_kwargs":{"hide_from_ui":true,"human_input_response":{"version":1,
                    "kind":"human_input_response","source":"haifa_interaction","request_id":"%s",
                    "response_kind":"option","option_id":"approve","value":"approve"}}}]},
                    "stream_mode":["messages-tuple","updates","custom"],"on_disconnect":"continue"}
                    """.formatted(requestId));
        }
        assertThat(getJson(location).path("status").asText()).isEqualTo(fail ? "error" : "success");
        if (fail) assertThat(stream.body()).contains("event: error", "diagnostic_id", "run_id");
        JsonNode page = getJson("/api/threads/narrated/messages/page?limit=200").path("data");
        JsonNode state = getJson("/api/threads/narrated/state").path("values").path("messages");
        List<JsonNode> contents = new ArrayList<>();
        long lastSeq = 0;
        for (JsonNode row : page) {
            assertThat(row.path("seq").asLong()).isGreaterThan(lastSeq);
            lastSeq = row.path("seq").asLong();
            assertThat(row.path("content").path("additional_kwargs").path("deerflow_seq").asLong()).isEqualTo(lastSeq);
            contents.add(row.path("content"));
        }
        assertThat(state).containsExactlyElementsOf(contents);
        for (int round = 1; round <= 2; round++) {
            final String text = "Narration " + round;
            List<JsonNode> narration = contents.stream().filter(message -> text.equals(message.path("content").asText())).toList();
            assertThat(narration).hasSize(1);
            assertThat(narration.getFirst().path("id").asText()).endsWith("-ai-" + (round - 1));
        }
        JsonNode failure = contents.getFirst().path("additional_kwargs").path("run_error");
        if (fail) assertThat(failure.path("diagnostic_id").asText()).isNotBlank();
        else assertThat(failure.isMissingNode()).isTrue();
        // Reopen the real SQLite-backed Gateway. No in-memory Run cache may be required.
        api.close();
        startApi(binding);
        assertThat(getJson("/api/threads/narrated/messages/page?limit=200").path("data")).isEqualTo(page);
        assertThat(getJson("/api/threads/narrated/state").path("values").path("messages")).isEqualTo(state);
        var next = client.send(runRequest("narrated", "today?"), HttpResponse.BodyHandlers.ofString());
        assertThat(next.statusCode()).isEqualTo(200);
        List<JsonNode> refreshed = new ArrayList<>();
        getJson("/api/threads/narrated/state").path("values").path("messages").forEach(refreshed::add);
        JsonNode newestHuman = refreshed.stream().filter(message -> "human".equals(message.path("type").asText()))
                .reduce((left, right) -> right).orElseThrow();
        assertThat(newestHuman.path("content").asText()).isEqualTo("today?");
        assertThat(newestHuman.path("additional_kwargs").has("run_error")).isFalse();
        for (JsonNode message : refreshed) {
            if (message.path("content").asText().startsWith("Narration "))
                assertThat(refreshed.indexOf(message)).isLessThan(refreshed.indexOf(newestHuman));
        }
        assertThat(org.slf4j.LoggerFactory.getILoggerFactory().getClass().getSimpleName()).isNotEqualTo("NOPLoggerFactory");
    }
}

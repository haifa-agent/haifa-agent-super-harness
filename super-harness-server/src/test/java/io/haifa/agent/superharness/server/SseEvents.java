package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** One ordered parser for the frozen SSE projection, including unknown and non-JSON data. */
final class SseEvents {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    record Event(String name, JsonNode data, String rawData) {}
    final List<Event> events;

    private SseEvents(List<Event> events) { this.events = List.copyOf(events); }

    static SseEvents read(String body) {
        List<Event> events = new ArrayList<>();
        List<String> data = new ArrayList<>();
        String name = "message";
        for (String line : (body + "\n\n").split("\r\n|\n|\r", -1)) {
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    String raw = String.join("\n", data);
                    JsonNode parsed;
                    try { parsed = JSON.readTree(raw); }
                    catch (JsonProcessingException invalidJson) { parsed = JSON.getNodeFactory().textNode(raw); }
                    if (parsed == null) parsed = JSON.getNodeFactory().textNode(raw);
                    events.add(new Event(name.isEmpty() ? "message" : name, parsed, raw));
                }
                data.clear();
                name = "message";
            } else if (!line.startsWith(":")) {
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) value = value.substring(1);
                if (field.equals("event")) name = value;
                if (field.equals("data")) data.add(value);
            }
        }
        return new SseEvents(events);
    }

    List<Event> named(String name) { return events.stream().filter(event -> event.name().equals(name)).toList(); }

    JsonNode event(String name) {
        var matches = named(name);
        assertThat(matches).as("Required real SSE event %s is missing", name).isNotEmpty();
        return matches.getFirst().data();
    }

    JsonNode card() {
        for (Event event : named("messages")) {
            JsonNode tuple = event.data();
            assertThat(tuple.isArray() && tuple.size() == 2)
                    .as("Actual frozen messages SSE must be a two-element tuple").isTrue();
            JsonNode item = tuple.get(0);
            if (!tuple.get(1).path("langgraph_node").asText().equals("tools")) continue;
            if (item.path("artifact").path("human_input").path("request_id").isTextual()) return item;
        }
        throw new AssertionError("Actual frozen SSE must contain a human input card");
    }

    /** Selection is bound to one existing Run, never to the first historical card. */
    record PendingApproval(String threadId, String runId, String requestId, JsonNode card) {
        static PendingApproval locate(String thread, String runLocation, JsonNode run, JsonNode state,
                Path evidence) throws Exception {
            String currentRunId = runLocation.substring(runLocation.lastIndexOf('/') + 1);
            var pending = new ArrayList<JsonNode>();
            var summary = JSON.createObjectNode().put("evidence_kind", "derived_safe_summary")
                    .put("thread_id", thread).put("run_location", runLocation).put("current_run_id", currentRunId)
                    .put("sdk_status", run.path("diagnostics").path("sdk_status").asText());
            var interactionFacts = summary.putArray("current_run_interactions");
            for (JsonNode interaction : run.path("diagnostics").path("interactions")) {
                var fact = interactionFacts.addObject();
                for (String field : List.of("run_id", "request_id", "kind", "state")) {
                    fact.set(field, GatewayFixture.safeCode(interaction.path(field)));
                }
                if ("PENDING".equals(interaction.path("state").asText())
                        && "approval".equals(interaction.path("kind").asText())
                        && currentRunId.equals(interaction.path("run_id").asText())) pending.add(interaction);
            }
            String pendingId = pending.size() == 1 ? pending.getFirst().path("request_id").asText() : "";
            summary.put("pending_approval_count", pending.size()).put("current_pending_request_id", pendingId);
            var matches = new ArrayList<JsonNode>();
            var cards = summary.putArray("state_approval_cards");
            for (JsonNode message : state.path("values").path("messages")) {
                var input = message.path("artifact").path("human_input");
                if (!input.hasNonNull("request_id")) continue;
                String cardRunId = message.path("run_id").asText();
                String cardRequestId = input.path("request_id").asText();
                boolean match = currentRunId.equals(cardRunId) && pendingId.equals(cardRequestId)
                        && "haifa_interaction".equals(input.path("source").asText());
                var fact = cards.addObject().put("is_current_run", currentRunId.equals(cardRunId))
                        .put("matches_current_pending", match);
                fact.set("run_id", GatewayFixture.safeCode(message.path("run_id")));
                fact.set("request_id", GatewayFixture.safeCode(input.path("request_id")));
                fact.set("tool_call_id", GatewayFixture.safeCode(input.path("tool_call_id")));
                if (match) matches.add(message);
            }
            summary.put("matching_state_card_count", matches.size());
            Files.writeString(evidence.resolve("approval-" + currentRunId + ".selection.safe-summary.json"),
                    JSON.writeValueAsString(summary));
            assertThat(run.path("run_id").asText()).isEqualTo(currentRunId);
            assertThat(run.path("thread_id").asText()).isEqualTo(thread);
            assertThat(run.path("diagnostics").path("sdk_status").asText()).isEqualTo("WAITING_APPROVAL");
            assertThat(pending.size()).as("one actual pending approval belongs to the requested Run").isEqualTo(1);
            assertThat(pendingId).matches("[A-Za-z0-9_.:-]{1,128}");
            assertThat(matches.size()).as("state contains exactly one card for the current Run and pending request")
                    .isEqualTo(1);
            return new PendingApproval(thread, currentRunId, pendingId, matches.getFirst());
        }

        static String reply(String requestId, String option) {
            ObjectNode body = JSON.createObjectNode().put("assistant_id", "lead_agent")
                    .put("on_disconnect", "continue").put("multitask_strategy", "reject");
            body.putArray("stream_mode").add("messages-tuple").add("updates").add("custom");
            var message = body.putObject("input").putArray("messages").addObject()
                    .put("id", "approval-response").put("type", "human");
            message.putArray("content").addObject().put("type", "text").put("text", option);
            message.putObject("additional_kwargs").put("hide_from_ui", true).putObject("human_input_response")
                    .put("version", 1).put("kind", "human_input_response").put("source", "haifa_interaction")
                    .put("request_id", requestId).put("response_kind", "option").put("option_id", option).put("value", option);
            return body.toString();
        }
    }
}

package io.haifa.agent.superharness.server.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared synthetic Parent task -> Child read -> Child output -> Parent output chain. */
public final class DelegationHttpFixture {
    public static final String CHILD_MARKER = "CHILD-W08";
    private DelegationHttpFixture() {}

    public static boolean isChild(JsonNode request) {
        return request.path("messages").toString().contains(CHILD_MARKER);
    }

    public static ControlledProvider.Responder readSequence(String childName) {
        AtomicInteger lead = new AtomicInteger(), child = new AtomicInteger();
        return (request, call) -> {
            if (isChild(request)) {
                if (child.incrementAndGet() == 1) {
                    return ControlledProvider.call("read-" + call, "w08-model", "child-provider-read", "list_uploaded_files", Map.of());
                }
                return ControlledProvider.stop("child-" + call, "w08-model", "CHILD-OUTPUT", 11, 13);
            }
            if (lead.incrementAndGet() == 1) return ControlledProvider.call("lead-" + call, "w08-model",
                    "lead-provider-task", "task", Map.of("agent", childName, "objective", "Inspect available uploads and report bounded findings"));
            return ControlledProvider.stop("lead-" + call, "w08-model", "LEAD-OUTPUT");
        };
    }

    public static void configure(GatewayFixture.HttpEvidence http, URI base, int providerPort) throws Exception {
        configure(http, base, providerPort, 1024);
    }

    public static void configure(GatewayFixture.HttpEvidence http, URI base, int providerPort, int maxOutputTokens) throws Exception {
        String model = """
                {"config":{"name":"w08","provider":"openai-compatible","model":"w08-model",
                "base_url":"http://127.0.0.1:%d/v1","enabled":true,"supports_vision":false,
                "context_window":32768,"max_tokens":%d,"api_key":"controlled-key"},"expected_revision":null}
                """.formatted(providerPort, maxOutputTokens);
        assertThat(http.request(base, "PUT", "/api/managed-models", model).statusCode()).isEqualTo(200);
    }

    public static void seed(GatewayFixture.HttpEvidence http, URI base, String name, int timeout, int turns,
            List<String> tools) throws Exception {
        ObjectNode definition = GatewayFixture.JSON.createObjectNode().put("name", name)
                .put("description", "Bounded independent research").put("system_prompt", CHILD_MARKER)
                .put("timeout_seconds", timeout).put("max_turns", turns);
        if (tools == null) definition.putNull("tools");
        else tools.forEach(tool -> definition.withArray("tools").add(tool));
        if (tools != null && tools.isEmpty()) definition.putArray("tools");
        assertThat(http.request(base, "POST", "/api/subagents", definition.toString()).statusCode()).isEqualTo(201);
    }

    public static ObjectNode input(boolean enabled) {
        ObjectNode body = GatewayFixture.JSON.createObjectNode().put("assistant_id", "lead_agent").put("on_disconnect", "continue");
        body.putObject("input").putArray("messages").addObject().put("type", "human").put("content", "Complete bounded work");
        body.putArray("stream_mode").add("messages-tuple").add("updates").add("custom");
        body.putObject("context").put("mode", "flash").put("model_name", "w08").put("subagent_enabled", enabled);
        return body;
    }

    public static String execute(GatewayFixture.HttpEvidence http, URI base, String thread, ObjectNode body) throws Exception {
        assertThat(http.request(base, "POST", "/api/threads", "{\"thread_id\":\"" + thread + "\"}").statusCode()).isEqualTo(200);
        var response = http.request(base, "POST", "/api/threads/" + thread + "/runs/stream", body.toString(), thread + ".sse");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("event: end");
        return response.headers().firstValue("content-location").orElseThrow();
    }

    public static List<JsonNode> messages(JsonNode state) {
        return java.util.stream.StreamSupport.stream(state.path("values").path("messages").spliterator(), false).toList();
    }
}

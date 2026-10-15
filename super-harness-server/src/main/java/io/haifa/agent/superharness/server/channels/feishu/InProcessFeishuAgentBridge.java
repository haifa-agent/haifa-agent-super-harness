package io.haifa.agent.superharness.server.channels.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.server.api.DeerFlowThreadRunApi;
import io.haifa.agent.superharness.server.auth.AccessOwner;
import java.util.Map;
import java.util.UUID;

/**
 * Reuses {@link DeerFlowThreadRunApi} in-process under {@link AccessOwner#call} so Feishu-bound
 * owners get the correct thread bucket without a public webhook or second Run loop.
 */
public final class InProcessFeishuAgentBridge implements FeishuAgentBridge {
    private final ObjectMapper json;
    private final DeerFlowThreadRunApi threads;

    public InProcessFeishuAgentBridge(ObjectMapper json, DeerFlowThreadRunApi threads) {
        this.json = json;
        this.threads = threads;
    }

    @Override
    public TurnResult runTurn(
            String ownerUserId,
            String threadId,
            Map<String, Object> channelMetadata,
            String userText)
            throws Exception {
        String owner = ownerUserId == null || ownerUserId.isBlank() ? AccessOwner.DEFAULT : ownerUserId;
        return AccessOwner.call(owner, () -> {
            try {
                ensureThread(threadId, channelMetadata);
                runStream(threadId, userText);
                String assistant = lastAssistantText(threadId);
                return new TurnResult(threadId, assistant);
            } catch (Exception exception) {
                throw new IllegalStateException("Feishu channel run failed", exception);
            }
        });
    }

    private void ensureThread(String threadId, Map<String, Object> channelMetadata) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("thread_id", threadId);
        ObjectNode metadata = body.putObject("metadata");
        if (channelMetadata != null) {
            for (Map.Entry<String, Object> entry : channelMetadata.entrySet()) {
                metadata.set(entry.getKey(), json.valueToTree(entry.getValue()));
            }
        }
        metadata.put("channel", "feishu");
        LocalHttpExchange exchange =
                new LocalHttpExchange("POST", "/api/threads", json.writeValueAsBytes(body));
        threads.handle(exchange);
        if (exchange.responseCode() != 200) {
            throw new IllegalStateException(
                    "Thread create failed: HTTP " + exchange.responseCode() + " " + exchange.responseText());
        }
    }

    private void runStream(String threadId, String userText) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("assistant_id", "lead_agent");
        body.put("on_disconnect", "continue");
        ObjectNode input = body.putObject("input");
        ArrayNode messages = input.putArray("messages");
        messages.addObject()
                .put("id", UUID.randomUUID().toString())
                .put("type", "human")
                .put("content", userText == null ? "" : userText);
        body.putArray("stream_mode").add("messages-tuple").add("updates").add("custom");
        body.putObject("context").put("mode", "flash");
        LocalHttpExchange exchange = new LocalHttpExchange(
                "POST",
                "/api/threads/" + threadId + "/runs/stream",
                json.writeValueAsBytes(body));
        threads.handle(exchange);
        if (exchange.responseCode() != 200) {
            throw new IllegalStateException(
                    "Run stream failed: HTTP " + exchange.responseCode() + " " + exchange.responseText());
        }
        String sse = exchange.responseText();
        if (!sse.contains("event: end") && !sse.contains("event: error")) {
            // streamRunEvents normally emits end on completion; tolerate values-only fixtures.
            if (!sse.contains("event: values") && !sse.contains("event: messages")) {
                throw new IllegalStateException("Run stream ended without completion marker");
            }
        }
        if (sse.contains("event: error")) {
            throw new IllegalStateException("Run stream reported error");
        }
    }

    private String lastAssistantText(String threadId) throws Exception {
        LocalHttpExchange exchange =
                new LocalHttpExchange("GET", "/api/threads/" + threadId + "/state", new byte[0]);
        threads.handle(exchange);
        if (exchange.responseCode() != 200) {
            throw new IllegalStateException(
                    "Thread state failed: HTTP " + exchange.responseCode() + " " + exchange.responseText());
        }
        JsonNode state = json.readTree(exchange.responseBytes());
        JsonNode messages = state.path("values").path("messages");
        String last = "";
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                String type = message.path("type").asText();
                String role = message.path("role").asText();
                if ("ai".equals(type) || "assistant".equals(type) || "assistant".equals(role) || "ai".equals(role)) {
                    last = messageText(message);
                }
            }
        }
        return last;
    }

    private static String messageText(JsonNode message) {
        JsonNode content = message.get("content");
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode part : content) {
                if (part.isTextual()) {
                    builder.append(part.asText());
                } else if (part.hasNonNull("text")) {
                    builder.append(part.get("text").asText());
                }
            }
            return builder.toString();
        }
        return content.toString();
    }
}

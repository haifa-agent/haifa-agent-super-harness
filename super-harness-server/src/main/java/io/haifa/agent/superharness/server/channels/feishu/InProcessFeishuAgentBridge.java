package io.haifa.agent.superharness.server.channels.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.server.api.DeerFlowThreadRunApi;
import io.haifa.agent.superharness.server.auth.AccessOwner;
import java.util.Map;
import java.util.Optional;

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
        return runTurn(ownerUserId, threadId, channelMetadata, userText, null);
    }

    @Override
    public TurnResult runTurn(
            String ownerUserId,
            String threadId,
            Map<String, Object> channelMetadata,
            String userText,
            FeishuHumanResponse response)
            throws Exception {
        String owner = ownerUserId == null || ownerUserId.isBlank() ? AccessOwner.DEFAULT : ownerUserId;
        return AccessOwner.call(owner, () -> {
            try {
                ensureThread(threadId, channelMetadata);
                String content = userText;
                if ((content == null || content.isBlank()) && response != null) {
                    content = FeishuInteraction.responseContent(
                            currentPendingLocked(threadId).orElse(null), response);
                }
                if (content == null || content.isBlank()) {
                    content = "";
                }
                runStream(threadId, content, response);
                return readTurn(threadId);
            } catch (Exception exception) {
                throw new IllegalStateException("Feishu channel run failed", exception);
            }
        });
    }

    @Override
    public Optional<FeishuPendingInput> currentPending(String ownerUserId, String threadId) throws Exception {
        String owner = ownerUserId == null || ownerUserId.isBlank() ? AccessOwner.DEFAULT : ownerUserId;
        return AccessOwner.call(owner, () -> {
            try {
                return currentPendingLocked(threadId);
            } catch (Exception exception) {
                throw new IllegalStateException("Feishu pending read failed", exception);
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

    private void runStream(String threadId, String userText, FeishuHumanResponse response) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("assistant_id", "lead_agent");
        body.put("on_disconnect", "continue");
        ObjectNode input = body.putObject("input");
        FeishuInteraction.writeHumanMessage(
                input.putArray("messages").addObject(),
                userText == null ? "" : userText,
                response);
        body.putArray("stream_mode").add("messages-tuple").add("updates").add("custom");
        body.putObject("context").put("mode", "flash");
        LocalHttpExchange exchange = new LocalHttpExchange(
                "POST",
                "/api/threads/" + threadId + "/runs/stream",
                json.writeValueAsBytes(body));
        threads.handle(exchange);
        if (exchange.responseCode() != 200) {
            throw new FeishuRunRejected(exchange.responseCode(), detail(exchange));
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

    private Optional<FeishuPendingInput> currentPendingLocked(String threadId) throws Exception {
        JsonNode state = threadState(threadId);
        if (state == null) {
            return Optional.empty();
        }
        return FeishuInteraction.pendingFromMessages(state.path("values").path("messages"));
    }

    private TurnResult readTurn(String threadId) throws Exception {
        JsonNode state = threadState(threadId);
        if (state == null) {
            throw new IllegalStateException("Thread state failed: HTTP 404");
        }
        JsonNode messages = state.path("values").path("messages");
        FeishuPendingInput pending = FeishuInteraction.pendingFromMessages(messages).orElse(null);
        String assistant = "";
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                String type = message.path("type").asText();
                String role = message.path("role").asText();
                if ("ai".equals(type) || "assistant".equals(type) || "assistant".equals(role) || "ai".equals(role)) {
                    assistant = FeishuInteraction.messageText(message);
                }
            }
        }
        if (pending != null && !pending.preface().isBlank()) {
            assistant = pending.preface();
        }
        return new TurnResult(threadId, assistant, pending);
    }

    private JsonNode threadState(String threadId) throws Exception {
        LocalHttpExchange exchange =
                new LocalHttpExchange("GET", "/api/threads/" + threadId + "/state", new byte[0]);
        threads.handle(exchange);
        if (exchange.responseCode() == 404) {
            return null;
        }
        if (exchange.responseCode() != 200) {
            throw new IllegalStateException(
                    "Thread state failed: HTTP " + exchange.responseCode() + " " + exchange.responseText());
        }
        return json.readTree(exchange.responseBytes());
    }

    private String detail(LocalHttpExchange exchange) {
        try {
            JsonNode body = json.readTree(exchange.responseBytes());
            String detail = body.path("detail").asText("");
            return detail.length() > 200 ? detail.substring(0, 200) : detail;
        } catch (Exception ignored) {
            return "";
        }
    }
}

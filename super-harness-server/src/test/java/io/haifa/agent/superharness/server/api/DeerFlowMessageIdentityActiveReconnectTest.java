package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelStreamControl;
import io.haifa.agent.model.api.ModelStreamEvent;
import io.haifa.agent.model.api.ModelStreamSink;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.sdk.conversation.ConversationTurn;
import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deterministic Java reproduction of the active reconnect message identity divergence.
 *
 * <p>Requirements from handoff.md:
 * <ul>
 *   <li>Create Run</li>
 *   <li>Human enters transient/in-flight projection</li>
 *   <li>Run still active</li>
 *   <li>Simulate reload / authoritative state read</li>
 *   <li>Observer joins from Last-Event-ID=-1</li>
 *   <li>Run completes</li>
 *   <li>Durable ConversationTurn appears</li>
 *   <li>history/state/messages-page read again</li>
 *   <li>Same logical message must not have a second wire identity</li>
 * </ul>
 */
class DeerFlowMessageIdentityActiveReconnectTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    @TempDir
    Path dataDirectory;
    private DeerFlowBootstrapApi api;
    private URI baseUri;
    private final SecretKey continuationKey = new SecretKeySpec(new byte[32], "AES");

    private final CountDownLatch modelEntered = new CountDownLatch(1);
    private final CountDownLatch modelRelease = new CountDownLatch(1);
    private final AtomicReference<String> modelCallId = new AtomicReference<>("");

    @BeforeEach
    void startServer() throws Exception {
        AgentChatModel controllableModel = new AgentChatModel() {
            @Override
            public AgentChatResponse invoke(AgentChatRequest request) {
                return invokeStreaming(request, event -> ModelStreamControl.CONTINUE);
            }

            @Override
            public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
                modelCallId.set(request.callId().value());
                modelEntered.countDown();
                try {
                    if (!modelRelease.await(15, TimeUnit.SECONDS)) {
                        throw new RuntimeException("Model release timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                String text = "Quantum computers harness superposition and entanglement.";
                sink.emit(new ModelStreamEvent.ContentDelta(request.callId(), 1L, text));
                return new AgentChatResponse(
                        request.callId().value(),
                        request.model().providerModelId(),
                        text,
                        List.of(),
                        ModelFinishReason.STOP,
                        ModelUsage.unpriced(10, 15),
                        "",
                        Map.of());
            }
        };

        api = DeerFlowBootstrapApi.createForTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory,
                ignored -> LocalProbeModelTestSupport.binding(controllableModel),
                continuationKey);
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
    }

    @AfterEach
    void stopServer() {
        modelRelease.countDown();
        if (api != null) api.close();
    }

    @Test
    void identicalMessageIdentityAcrossLiveSseInFlightOverlayAndDurableHistory() throws Exception {
        String threadId = "thread-identity-test";
        createThread(threadId);

        // 1. Creator starts Run with on_disconnect=continue
        HttpRequest createRunRequest = HttpRequest.newBuilder()
                .uri(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        """
                        {
                          "assistant_id": "lead_agent",
                          "input": {
                            "messages": [{"id":"client-human-1","type":"human","content":"Explain quantum computing"}]
                          },
                          "on_disconnect": "continue",
                          "stream_mode": ["messages-tuple", "updates", "custom"],
                          "multitask_strategy": "reject"
                        }
                        """))
                .build();

        HttpResponse<InputStream> creatorStream = client.send(
                createRunRequest, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(creatorStream.statusCode()).isEqualTo(200);

        String contentLocation = creatorStream.headers().firstValue("Content-Location").orElseThrow();
        String runId = contentLocation.substring(contentLocation.lastIndexOf('/') + 1);

        // Wait until model has entered execution (run is actively running)
        assertThat(modelEntered.await(10, TimeUnit.SECONDS)).isTrue();

        // 2. Read in-flight state while Run is active
        JsonNode activeState = getJson("/api/threads/" + threadId + "/state");
        JsonNode activeStateMessages = activeState.path("values").path("messages");
        assertThat(activeStateMessages.isArray()).isTrue();
        assertThat(activeStateMessages.size()).isGreaterThanOrEqualTo(1);
        String inFlightHumanId = activeStateMessages.get(0).path("id").asText();

        JsonNode activeHistory = postJson("/api/threads/" + threadId + "/history", "{\"limit\":10}");
        assertThat(activeHistory.isArray()).isTrue();
        String inFlightHistoryHumanId = activeHistory.get(0)
                .path("values").path("messages").get(0).path("id").asText();

        assertThat(inFlightHistoryHumanId).isEqualTo(inFlightHumanId);

        // 3. Creator disconnects (simulate browser page reload)
        creatorStream.body().close();

        // 4. Observer joins stream with Last-Event-ID: -1
        HttpRequest joinRequest = HttpRequest.newBuilder()
                .uri(baseUri.resolve(contentLocation + "/stream"))
                .header("Last-Event-ID", "-1")
                .GET()
                .build();

        HttpResponse<InputStream> joinStreamResponse = client.send(
                joinRequest, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(joinStreamResponse.statusCode()).isEqualTo(200);

        // 5. Release model to finish run and emit live SSE messages
        modelRelease.countDown();

        // Read join SSE frames until end
        List<String> liveAssistantIds = new ArrayList<>();
        List<Long> liveSseSequences = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(joinStreamResponse.body(), StandardCharsets.UTF_8))) {
            String line;
            String currentEvent = null;
            String currentId = null;
            StringBuilder currentData = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event: ")) {
                    currentEvent = line.substring("event: ".length()).trim();
                } else if (line.startsWith("id: ")) {
                    currentId = line.substring("id: ".length()).trim();
                } else if (line.startsWith("data: ")) {
                    currentData.append(line.substring("data: ".length()));
                } else if (line.isEmpty()) {
                    if ("messages".equals(currentEvent) && currentData.length() > 0) {
                        JsonNode tuple = json.readTree(currentData.toString());
                        if (tuple.isArray() && tuple.size() > 0) {
                            JsonNode msg = tuple.get(0);
                            if ("ai".equals(msg.path("type").asText())) {
                                liveAssistantIds.add(msg.path("id").asText());
                                if (currentId != null) {
                                    try {
                                        liveSseSequences.add(Long.parseLong(currentId));
                                    } catch (NumberFormatException ignored) {}
                                }
                            }
                        }
                    }
                    if ("end".equals(currentEvent)) {
                        break;
                    }
                    currentEvent = null;
                    currentId = null;
                    currentData.setLength(0);
                }
            }
        }

        assertThat(liveAssistantIds).isNotEmpty();
        String liveAssistantId = liveAssistantIds.get(0);

        // 6. Verify run has reached COMPLETED terminal
        JsonNode runSnapshot = getJson("/api/threads/" + threadId + "/runs/" + runId);
        assertThat(runSnapshot.path("status").asText()).isEqualTo("success");

        // 7. Read authoritative state, history, and messages-page after run completion
        JsonNode completedState = getJson("/api/threads/" + threadId + "/state");
        JsonNode completedMessages = completedState.path("values").path("messages");
        assertThat(completedMessages.size()).isEqualTo(2);

        String postCompletionHumanId = completedMessages.get(0).path("id").asText();
        String postCompletionAssistantId = completedMessages.get(1).path("id").asText();

        JsonNode completedHistory = postJson("/api/threads/" + threadId + "/history", "{\"limit\":10}");
        JsonNode historyMessages = completedHistory.get(0).path("values").path("messages");
        String historyHumanId = historyMessages.get(0).path("id").asText();
        String historyAssistantId = historyMessages.get(1).path("id").asText();

        JsonNode messagesPage = getJson("/api/threads/" + threadId + "/messages/page");
        JsonNode pageData = messagesPage.path("data");
        assertThat(pageData.size()).isEqualTo(2);
        String pageHumanId = pageData.get(0).path("content").path("id").asText();
        String pageAssistantId = pageData.get(1).path("content").path("id").asText();

        long humanConversationSeq = pageData.get(0).path("seq").asLong();
        long assistantConversationSeq = pageData.get(1).path("seq").asLong();

        // Print Diagnostic Report as mandated by handoff.md
        System.out.println("=== MESSAGE IDENTITY DIAGNOSTIC REPORT ===");
        System.out.printf("runId: %s%n", runId);
        System.out.printf("role: human | inFlightWireId: %s | postStateId: %s | historyId: %s | pageId: %s | convSeq: %d%n",
                inFlightHumanId, postCompletionHumanId, historyHumanId, pageHumanId, humanConversationSeq);
        System.out.printf("role: ai    | liveWireId: %s | postStateId: %s | historyId: %s | pageId: %s | convSeq: %d | sseSeq: %s%n",
                liveAssistantId, postCompletionAssistantId, historyAssistantId, pageAssistantId, assistantConversationSeq, liveSseSequences);
        System.out.println("==========================================");

        // In-flight human wire identity MUST match post-completion durable wire identity
        assertThat(postCompletionHumanId)
                .as("Human wire ID must be identical between in-flight overlay, state, history, and messages/page")
                .isEqualTo(inFlightHumanId);
        assertThat(historyHumanId).isEqualTo(inFlightHumanId);
        assertThat(pageHumanId).isEqualTo(inFlightHumanId);

        // Live SSE assistant wire identity MUST match post-completion durable wire identity
        assertThat(postCompletionAssistantId)
                .as("Assistant wire ID must be identical between live SSE stream, state, history, and messages/page")
                .isEqualTo(liveAssistantId);
        assertThat(historyAssistantId).isEqualTo(liveAssistantId);
        assertThat(pageAssistantId).isEqualTo(liveAssistantId);
    }

    private void createThread(String threadId) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(baseUri.resolve("/api/threads"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"thread_id\":\"" + threadId + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(baseUri.resolve(path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private JsonNode postJson(String path, String body) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(baseUri.resolve(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }
}

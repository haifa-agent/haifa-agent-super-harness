package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.superharness.server.auth.AccessOwner;
import io.haifa.agent.superharness.server.channels.feishu.FeishuAgentBridge;
import io.haifa.agent.superharness.server.channels.feishu.FeishuHumanResponse;
import io.haifa.agent.superharness.server.channels.feishu.InProcessFeishuAgentBridge;
import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
import io.haifa.agent.superharness.server.run.HaifaPublicInteractionSpikeTest;
import io.haifa.agent.superharness.server.run.HaifaRunBridge;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * W20-task-8 boundary test: the Web HTTP adapter and the Feishu channel bridge drive one shared
 * {@link DeerFlowConversationService}, so a thread created through either entry is the same
 * Runtime-backed thread the other entry reads and continues. No mock returns a TurnResult; every
 * assertion reads real projected thread state produced by the controlled model and the public SDK.
 */
class DeerFlowSharedConversationServiceHttpTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final AtomicInteger toolInvocations = new AtomicInteger();
    private final SecretKey continuationKey = new SecretKeySpec(new byte[32], "AES");

    @TempDir
    Path dataDirectory;

    private DeerFlowBootstrapApi api;
    private URI baseUri;
    private FeishuAgentBridge bridge;

    @BeforeEach
    void startServer() throws Exception {
        startApi(0);
    }

    @AfterEach
    void stopServer() {
        if (api != null) api.close();
    }

    private void startApi(int sequentialToolCalls) throws Exception {
        if (api != null) api.close();
        var binding = new HaifaRunBridge.ModelBinding(
                "interaction-probe",
                "1.0.0",
                new HaifaPublicInteractionSpikeTest.ApprovalProbeModel(sequentialToolCalls),
                HaifaPublicInteractionSpikeTest.modelSnapshot());
        api = DeerFlowBootstrapApi.createForInteractionTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory,
                continuationKey,
                ignored -> binding,
                List.of(new HaifaPublicInteractionSpikeTest.MutatingProbeTool(toolInvocations)));
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
        bridge = new InProcessFeishuAgentBridge(json, api.threadRunApi().conversations());
    }

    @Test
    void webCreatesThreadThenFeishuReadsAndContinuesTheSameThread() throws Exception {
        createThread("shared-web");
        assertThat(stream("shared-web", "web says hi").statusCode()).isEqualTo(200);

        // Feishu reuses the Web-created thread (same owner) and appends a turn through the shared entry.
        FeishuAgentBridge.TurnResult feishu =
                bridge.runTurn(null, "shared-web", Map.of("chat_id", "oc_cross"), "feishu says hi");
        assertThat(feishu.threadId()).isEqualTo("shared-web");
        assertThat(feishu.assistantText()).isEqualTo("interaction resolved");

        JsonNode state = getJson("/api/threads/shared-web/state");
        List<String> humanContents = humanContents(state);
        assertThat(humanContents).contains("web says hi", "feishu says hi");

        // Web continues the same shared thread after Feishu wrote to it.
        HttpResponse<String> continued = stream("shared-web", "web continues");
        assertThat(continued.statusCode()).isEqualTo(200);
        assertThat(continued.body()).contains("event: end");
        assertThat(humanContents(getJson("/api/threads/shared-web/state"))).contains("web continues");
        assertThat(api.threadRunApi().conversations().hasThread("shared-web")).isTrue();
    }

    @Test
    void feishuCreatesThreadThenWebReadsAndContinuesIt() throws Exception {
        FeishuAgentBridge.TurnResult feishu =
                bridge.runTurn(null, "feishu-first", Map.of("chat_id", "oc_first"), "hello from feishu");
        assertThat(feishu.assistantText()).isEqualTo("interaction resolved");

        JsonNode state = getJson("/api/threads/feishu-first/state");
        assertThat(humanContents(state)).contains("hello from feishu");

        HttpResponse<String> continued = stream("feishu-first", "web continues feishu thread");
        assertThat(continued.statusCode()).isEqualTo(200);
        assertThat(continued.body()).contains("event: end");
        assertThat(humanContents(getJson("/api/threads/feishu-first/state")))
                .contains("hello from feishu", "web continues feishu thread");
    }

    @Test
    void feishuApprovalContinuesTheSameRunAndRejectsADuplicateResponse() throws Exception {
        startApi(1);
        FeishuAgentBridge.TurnResult pendingTurn =
                bridge.runTurn(null, "approval-thread", Map.of("chat_id", "oc_approval"), "please mutate");
        assertThat(pendingTurn.pending()).isNotNull();
        assertThat(toolInvocations).as("no side effect before approval").hasValue(0);

        FeishuHumanResponse approve = new FeishuHumanResponse(
                pendingTurn.pending().source(),
                pendingTurn.pending().requestId(),
                "option",
                "approve",
                "approve");
        FeishuAgentBridge.TurnResult resolved =
                bridge.runTurn(null, "approval-thread", Map.of("chat_id", "oc_approval"), null, approve);
        assertThat(resolved.assistantText()).isEqualTo("interaction resolved");
        assertThat(toolInvocations).as("approval applied the same Run's side effect exactly once").hasValue(1);

        // A duplicate submission of the already accepted response is rejected by the shared admission.
        assertThatThrownBy(() ->
                        bridge.runTurn(null, "approval-thread", Map.of("chat_id", "oc_approval"), null, approve))
                .isInstanceOf(IllegalStateException.class);
        assertThat(toolInvocations).as("duplicate approval never re-invokes the tool").hasValue(1);
    }

    @Test
    void ownerIsolationUsesExistingThreadsAcrossTheSharedService() throws Exception {
        bridge.runTurn("owner-a", "thread-a", Map.of(), "owner a turn");
        bridge.runTurn("owner-b", "thread-b", Map.of(), "owner b turn");

        // Both threads exist and are readable by their own owner.
        assertThat(humanContents(asOwner("owner-a", () -> api.threadRunApi().conversations().readThreadState("thread-a"))))
                .contains("owner a turn");
        assertThat(humanContents(asOwner("owner-b", () -> api.threadRunApi().conversations().readThreadState("thread-b"))))
                .contains("owner b turn");

        // The existing thread-a is invisible to owner-b (and vice versa); this is isolation, not a
        // missing resource, because owner-a reads thread-a successfully in the same test.
        assertThatThrownBy(() -> asOwner("owner-b", () -> api.threadRunApi().conversations().readThreadState("thread-a")))
                .isInstanceOf(DeerFlowConversationService.ConversationException.class)
                .satisfies(failure -> assertThat(
                                ((DeerFlowConversationService.ConversationException) failure).kind())
                        .isEqualTo(DeerFlowConversationService.ConversationException.Kind.NOT_FOUND));
        assertThatThrownBy(() -> asOwner("owner-b", () -> api.threadRunApi().conversations().admitTurn("thread-a", json.readTree(runBody("cross-owner steer")), null, null)))
                .isInstanceOf(DeerFlowConversationService.ConversationException.class);
    }

    @Test
    void localHttpExchangeSeamIsRemoved() {
        assertThatThrownBy(() -> Class.forName(
                        "io.haifa.agent.superharness.server.channels.feishu.LocalHttpExchange"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    private <T> T asOwner(String owner, java.util.concurrent.Callable<T> action) throws Exception {
        return AccessOwner.call(owner, () -> {
            try {
                return action.call();
            } catch (RuntimeException runtime) {
                throw runtime;
            } catch (Exception checked) {
                throw new IllegalStateException(checked);
            }
        });
    }

    private void createThread(String threadId) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(baseUri.resolve("/api/threads"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"thread_id\":\"" + threadId + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    private HttpResponse<String> stream(String threadId, String message) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(runBody(message)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String runBody(String message) {
        return "{\"assistant_id\":\"lead_agent\",\"input\":{\"messages\":[{\"id\":\"human-1\",\"type\":\"human\",\"content\":\""
                + message
                + "\"}]},\"stream_mode\":[\"messages-tuple\",\"updates\",\"custom\"],\"on_disconnect\":\"continue\",\"multitask_strategy\":\"reject\"}";
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private List<String> humanContents(JsonNode state) {
        JsonNode messages = state.path("values").path("messages");
        assertThat(messages.isArray()).isTrue();
        List<String> contents = new java.util.ArrayList<>();
        for (JsonNode message : messages) {
            if ("human".equals(message.path("type").asText())) {
                contents.add(message.path("content").asText());
            }
        }
        return contents;
    }
}

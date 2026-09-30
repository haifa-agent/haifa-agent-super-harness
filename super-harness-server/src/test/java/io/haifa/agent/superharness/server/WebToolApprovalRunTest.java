package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelStreamSink;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceChangeLimits;
import io.haifa.agent.superharness.workspace.WorkspaceLimits;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * W11-task-1 approval behaviour end-to-end through a real Run: the frozen default web tools execute
 * without an approval card, while {@code write_file} keeps the standard preset approval. The web
 * tool fixture is a loopback JDK {@code HttpServer} so no test touches the network.
 */
class WebToolApprovalRunTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecretKey KEY = new SecretKeySpec(new byte[32], "AES");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @TempDir Path dataRoot;

    @Test
    void webSearchRunExecutesWithoutAnApprovalCard() throws Exception {
        HttpServer ddg = WebToolTestSupport.loopbackServer();
        ddg.createContext("/html/", WebToolApprovalRunTest::serveSearch);
        ddg.start();
        try {
            WebToolConfig web = WebToolTestSupport.config(ddg, "/reader");
            List<? extends JavaTool<?, ?>> tools =
                    List.of(new ProductionWebSearchTool(web), new ProductionWebFetchTool(web));
            AgentChatModel model = new ToolCallingModel("web_search", Map.of("query", "haifa"));
            try (var api = startApi(model, tools)) {
                URI base = URI.create("http://127.0.0.1:" + api.address().getPort());
                createThread(base, "web-approval");
                HttpResponse<String> stream = postRun(base, "web-approval", "lead_agent");
                assertThat(stream.statusCode()).as(stream.body()).isEqualTo(200);
                assertThat(stream.body()).contains("event: end");
                assertThat(stream.body()).as("web tools must not raise an approval card")
                        .doesNotContain("human_input", "request_id");
                assertThat(stream.body()).contains("Alpha snippet text");

                JsonNode run = JSON.readTree(request(base, "GET",
                        stream.headers().firstValue("content-location").orElseThrow(), null).body());
                assertThat(run.path("status").asText()).isEqualTo("success");
                assertThat(run.path("diagnostics").path("sdk_status").asText()).isEqualTo("COMPLETED");
                assertThat(run.path("diagnostics").path("tool_calls").findValuesAsText("name"))
                        .contains("web_search");
                assertThat(run.path("diagnostics").path("tool_calls").findValuesAsText("status"))
                        .contains("COMPLETED");
                assertThat(run.path("diagnostics").path("interactions")).isEmpty();
            }
        } finally {
            ddg.stop(0);
        }
    }

    @Test
    void writeFileRunStillRequiresApproval() throws Exception {
        String threadId = "write-approval";
        var bindings = new PersistentProjectWorkspaceService(
                dataRoot.resolve("workspace"), WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
        var workspace = new RestrictedWorkspaceService(dataRoot.resolve("workspace"), WorkspaceLimits.defaults());
        bindings.bindThreadWorkspace(DeerFlowBootstrapApi.DEFAULT_USER_ID, threadId);
        List<? extends JavaTool<?, ?>> tools = List.of(new ProductionWriteFileTool(
                threadId, "thread:" + threadId, bindings, workspace, new FileToolSession()));
        AgentChatModel model = new ToolCallingModel("write_file",
                Map.of("path", "/mnt/user-data/outputs/never.txt", "content", "blocked"));
        try (var api = startApi(model, tools)) {
            URI base = URI.create("http://127.0.0.1:" + api.address().getPort());
            createThread(base, threadId);
            HttpResponse<String> stream = postRun(base, threadId, "lead_agent");
            assertThat(stream.statusCode()).as(stream.body()).isEqualTo(200);
            assertThat(stream.body()).contains("human_input", "request_id");
            JsonNode run = JSON.readTree(request(base, "GET",
                    stream.headers().firstValue("content-location").orElseThrow(), null).body());
            assertThat(run.path("diagnostics").path("sdk_status").asText()).isEqualTo("WAITING_APPROVAL");
            assertThat(run.path("diagnostics").path("interactions").findValuesAsText("request_id")).isNotEmpty();
            assertThat(Files.exists(dataRoot.resolve("workspace").resolve("workspaces"))).isTrue();
        }
    }

    private DeerFlowBootstrapApi startApi(AgentChatModel model, List<? extends JavaTool<?, ?>> tools)
            throws IOException {
        var binding = LocalProbeModelTestSupport.binding(model);
        var api = DeerFlowBootstrapApi.createForInteractionTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataRoot,
                KEY,
                ignored -> binding,
                tools);
        api.start();
        return api;
    }

    private void createThread(URI base, String threadId) throws Exception {
        assertThat(request(base, "POST", "/api/threads", "{\"thread_id\":\"" + threadId + "\"}")
                .statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> postRun(URI base, String threadId, String assistantId) throws Exception {
        String body = """
                {"assistant_id":"%s","input":{"messages":[{"id":"human-1","type":"human","content":[{"type":"text","text":"run"}]}]},
                "stream_mode":["messages-tuple","updates","custom"],"on_disconnect":"continue","multitask_strategy":"reject"}
                """.formatted(assistantId);
        return request(base, "POST", "/api/threads/" + threadId + "/runs/stream", body);
    }

    private static HttpResponse<String> request(URI base, String method, String path, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(30));
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void serveSearch(HttpExchange exchange) throws IOException {
        String html = """
                <html><body>
                <div class="result__body">
                  <h2 class="result__title"><a href="https://example.com/alpha">Alpha</a></h2>
                  <a class="result__snippet" href="https://example.com/alpha">Alpha snippet text</a>
                </div>
                </body></html>
                """;
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** Deterministic two-call model: first call requests the configured Tool, then answers with its result. */
    static final class ToolCallingModel implements AgentChatModel {
        private final String toolName;
        private final Map<String, Object> arguments;

        ToolCallingModel(String toolName, Map<String, Object> arguments) {
            this.toolName = toolName;
            this.arguments = arguments;
        }

        @Override
        public AgentChatResponse invoke(AgentChatRequest request) {
            List<ModelMessage> currentTurn = messagesAfterLastUser(request);
            boolean hasToolResult = currentTurn.stream().anyMatch(m -> m.role() == ModelMessageRole.TOOL);
            if (hasToolResult) {
                return new AgentChatResponse(
                        "final", request.model().providerModelId(), "FINAL:" + lastToolContent(currentTurn),
                        List.of(), ModelFinishReason.STOP, ModelUsage.unpriced(1, 1), "", Map.of());
            }
            return new AgentChatResponse(
                    "tool-call", request.model().providerModelId(), "",
                    List.of(new ModelToolCall(new ProviderToolCallCorrelationId("corr"), toolName, arguments)),
                    ModelFinishReason.TOOL_CALLS, ModelUsage.unpriced(1, 1), "", Map.of());
        }

        @Override
        public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
            return invoke(request);
        }

        private static List<ModelMessage> messagesAfterLastUser(AgentChatRequest request) {
            List<ModelMessage> messages = request.messages();
            int lastUser = -1;
            for (int index = 0; index < messages.size(); index++) {
                if (messages.get(index).role() == ModelMessageRole.USER) lastUser = index;
            }
            return messages.subList(Math.max(0, lastUser + 1), messages.size());
        }

        private static String lastToolContent(List<ModelMessage> currentTurn) {
            StringBuilder text = new StringBuilder();
            currentTurn.stream()
                    .filter(message -> message.role() == ModelMessageRole.TOOL)
                    .forEach(message -> {
                        if (message.content() != null) text.append(message.content());
                        if (message.toolResultData() != null) text.append(message.toolResultData());
                    });
            return text.toString();
        }
    }
}

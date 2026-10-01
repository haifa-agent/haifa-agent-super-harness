package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * W05-task-10: the frozen {@code ask_clarification} sibling behaviour for tools whose execution is
 * owned by the public Haifa SDK rather than by Super Harness.
 *
 * <p>W05-task-8 already proved (Oracle #5) that a same-turn <em>SH-owned</em> side-effecting sibling
 * never produces a workspace side effect, because every SH tool can consult
 * {@link ClarificationBoundaryRegistry}. Frozen DeerFlow instead drops same-turn siblings in
 * {@code ClarificationMiddleware.after_model} before the tools node runs them. The SDK Skill and MCP
 * tools have no public interception point in the composition SH is allowed to use, so this test may
 * only <em>record</em> what actually happens; it never fakes a sibling with a mock Java tool and it
 * never weakens network approval.</p>
 *
 * <p>Every scenario drives the real production wiring through HTTP: a managed OpenAI-compatible model
 * pointing at a controlled Provider, the real bundled {@code consulting-analysis} Skill package, a
 * local minimal Streamable HTTP MCP server connected by the real public SDK MCP client, and the real
 * {@code ask_clarification} Java tool. The controlled Provider scripts the model by protocol shape
 * (a same-turn pair of tool calls), and the test records the authoritative ToolCall / Interaction /
 * Provider-request / MCP {@code tools/call} facts. MCP is read-only but still traverses the standard
 * approval policy, so an unapproved sibling can never reach {@code tools/call}.</p>
 */
class ClarificationSdkSiblingRunHttpTest {
    private static final String SKILL = "consulting-analysis";
    private static final String SKILL_BODY_MARKER = "Data Authenticity Protocol";
    private static final String SKILL_LOAD = "skill_load";
    private static final String CLARIFY = "ask_clarification";
    private static final String MCP_SERVER = "clarify-mcp";
    private static final String MCP_TOOL = "sibling_probe";
    private static final String MCP_TOOL_SUFFIX = "_" + MCP_TOOL;
    private static final String MCP_RESULT_MARKER = "SIBLING_PROBE_OK";
    private static final String CLARIFY_QUESTION = "Which environment should I deploy to?";

    @TempDir Path dataRoot;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String key = Base64.getEncoder().encodeToString(new byte[32]);
    private final Path evidenceRoot = Path.of(System.getProperty(
            "w05.sdk.sibling.evidenceDir", "local-tmp/w05-sdk-sibling-evidence"))
            .resolve(UUID.randomUUID().toString());
    private final List<List<String>> offeredTools = Collections.synchronizedList(new ArrayList<>());

    private HttpServer provider;
    private ExecutorService providerExecutor;
    private LocalMcpServer mcp;
    private DeerFlowBootstrapApi api;
    private URI base;

    private volatile boolean sameTurnPairEmitted;
    private volatile boolean skillBodyVisibleInLaterRequest;
    private volatile boolean mcpResultVisibleToModel;

    @AfterEach
    void stop() {
        if (api != null) {
            api.close();
            api = null;
        }
        if (provider != null) {
            provider.stop(0);
            provider = null;
        }
        if (providerExecutor != null) {
            providerExecutor.close();
            providerExecutor = null;
        }
        if (mcp != null) {
            mcp.close();
            mcp = null;
        }
        ClarificationBoundaryRegistry.resetForTests();
    }

    // ---- Oracle: SDK Skill sibling after clarification ------------------------------------------

    @Test
    void clarificationBeforeSkillSiblingOffersBothAndRecordsTheOutcome() throws Exception {
        startProvider(request -> {
            if (hasToolResult(request) || sameTurnPairEmitted) {
                return text("FINAL_AFTER_SKILL");
            }
            sameTurnPairEmitted = true;
            return siblingPair(true, SKILL_LOAD, skillArguments());
        });
        startGatewayAndRegister();
        String threadId = "sdk-clarify-then-skill";
        createThread(threadId);

        RunResult run = streamRun(threadId, "W05_SDK clarify then skill");

        assertOffered(CLARIFY, SKILL_LOAD);
        assertThat(sameTurnPairEmitted).as("the controlled Provider emitted the same-turn pair").isTrue();
        Observed observed = observe(threadId, run).requiredSettled();
        assertThat(observed.clarificationCard())
                .as("a completed ask_clarification must still project the frozen card: " + observed)
                .isTrue();
        assertThat(observed.askClarificationStatus()).isEqualTo("COMPLETED");
        System.out.println(observed.evidence("clarify-first-skill", threadId, run, sameTurnPairEmitted));
    }

    // ---- Oracle: SDK Skill sibling before clarification ----------------------------------------

    @Test
    void skillSiblingBeforeClarificationOffersBothAndRecordsTheOutcome() throws Exception {
        startProvider(request -> {
            if (hasToolResult(request) || sameTurnPairEmitted) {
                return text("FINAL_AFTER_SKILL");
            }
            sameTurnPairEmitted = true;
            return siblingPair(false, SKILL_LOAD, skillArguments());
        });
        startGatewayAndRegister();
        String threadId = "sdk-skill-then-clarify";
        createThread(threadId);

        RunResult run = streamRun(threadId, "W05_SDK skill then clarify");

        assertOffered(CLARIFY, SKILL_LOAD);
        assertThat(sameTurnPairEmitted).isTrue();
        Observed observed = observe(threadId, run).requiredSettled();
        // Sibling-first is the order the frozen middleware would still drop; SH records whatever
        // the SDK actually did rather than asserting the frozen protection that has no hook here.
        assertThat(observed.clarificationCard())
                .as("ask_clarification must complete and project its card: " + observed)
                .isTrue();
        assertThat(observed.askClarificationStatus()).isEqualTo("COMPLETED");
        System.out.println(observed.evidence("skill-first-clarify", threadId, run, sameTurnPairEmitted));
    }

    // ---- Oracle: SDK MCP sibling after clarification (no approval, no tools/call) ---------------

    @Test
    void clarificationBeforeMcpSiblingStaysUnapprovedWithNoToolsCall() throws Exception {
        startProvider(request -> {
            if (hasToolResult(request) || sameTurnPairEmitted) {
                return text("FINAL_AFTER_MCP");
            }
            String mcpTool = mcpToolNameOrNull(request);
            if (mcpTool == null) {
                return text("MCP_TOOL_NOT_OFFERED");
            }
            sameTurnPairEmitted = true;
            return siblingPair(true, mcpTool, "{}");
        });
        startGatewayAndRegister();
        String threadId = "sdk-clarify-then-mcp";
        createThread(threadId);

        RunResult run = streamRun(threadId, "W05_SDK clarify then mcp");

        assertOffered(CLARIFY, MCP_TOOL_SUFFIX);
        assertThat(sameTurnPairEmitted).isTrue();
        Observed observed = observe(threadId, run).requiredSettled();
        assertThat(mcp.toolsCallCount())
                .as("an unapproved MCP sibling must never reach tools/call: " + observed)
                .isZero();
        assertThat(observed.mcpToolStatus()).isNotEqualTo("COMPLETED");
        assertThat(observed.clarificationCard()).as(observed.toString()).isTrue();
        assertThat(observed.askClarificationStatus()).isEqualTo("COMPLETED");
        System.out.println(observed.evidence("clarify-first-mcp", threadId, run, sameTurnPairEmitted));
    }

    // ---- Oracle: SDK MCP sibling before clarification (order matters for approval) --------------

    @Test
    void mcpSiblingBeforeClarificationStaysUnapprovedWithNoToolsCall() throws Exception {
        startProvider(request -> {
            if (hasToolResult(request) || sameTurnPairEmitted) {
                return text("FINAL_AFTER_MCP");
            }
            String mcpTool = mcpToolNameOrNull(request);
            if (mcpTool == null) {
                return text("MCP_TOOL_NOT_OFFERED");
            }
            sameTurnPairEmitted = true;
            return siblingPair(false, mcpTool, "{}");
        });
        startGatewayAndRegister();
        String threadId = "sdk-mcp-then-clarify";
        createThread(threadId);

        RunResult run = streamRun(threadId, "W05_SDK mcp then clarify");

        assertOffered(CLARIFY, MCP_TOOL_SUFFIX);
        assertThat(sameTurnPairEmitted).isTrue();
        Observed observed = observe(threadId, run).requiredSettled();
        // The MCP sibling is offered first and needs approval; the run may interrupt before
        // ask_clarification is ever invoked. That absent card is the recorded known difference.
        assertThat(mcp.toolsCallCount())
                .as("an unapproved MCP sibling must never reach tools/call: " + observed)
                .isZero();
        assertThat(observed.mcpToolStatus()).isNotEqualTo("COMPLETED");
        System.out.println(observed.evidence("mcp-first-clarify", threadId, run, sameTurnPairEmitted));
    }

    // ---- Baseline: an approved MCP tool really executes over the SDK transport ------------------

    @Test
    void approvedMcpSiblingExecutesExactlyOneRealToolsCall() throws Exception {
        startProvider(request -> {
            if (hasToolResult(request) || sameTurnPairEmitted) {
                return text("FINAL_AFTER_APPROVED_MCP");
            }
            String mcpTool = mcpToolNameOrNull(request);
            if (mcpTool == null) {
                return text("MCP_TOOL_NOT_OFFERED");
            }
            sameTurnPairEmitted = true;
            return singleToolCall(mcpTool, "{}");
        });
        startGatewayAndRegister();
        String threadId = "sdk-approved-mcp";
        createThread(threadId);

        RunResult pending = streamRun(threadId, "W05_SDK approved mcp");
        assertOffered(MCP_TOOL_SUFFIX);
        assertThat(mcp.toolsCallCount()).as("no tools/call before approval").isZero();
        String requestId = humanInputRequestId(pending.body())
                .orElseThrow(() -> new AssertionError("missing MCP approval card: " + pending.body()));

        // This hidden reply is test flow only; it is not a product behaviour and not a bypass.
        HttpResponse<String> approved = postJson(
                "/api/threads/" + threadId + "/runs/stream", approvalBody(requestId));
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(approved.body()).contains("event: end");

        JsonNode finalRun = awaitTerminal(threadId, pending.runId());
        RunResult run = new RunResult(
                pending.runId(), finalRun.path("status").asText(), pending.body(), finalRun);
        Observed observed = observe(threadId, run).requiredSettled();
        assertThat(mcp.toolsCallCount())
                .as("the approved MCP tool must execute exactly once: " + observed)
                .isEqualTo(1);
        assertThat(observed.mcpToolStatus()).isEqualTo("COMPLETED");
        assertThat(observed.httpStatus()).isEqualTo("success");
        System.out.println(observed.evidence("approved-mcp-baseline", threadId, run, false));
    }

    // ---- controlled Provider -------------------------------------------------------------------

    private void startProvider(Function<JsonNode, byte[]> responder) throws IOException {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        providerExecutor = Executors.newVirtualThreadPerTaskExecutor();
        provider.setExecutor(providerExecutor);
        provider.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            writeRaw("provider-request-" + UUID.randomUUID() + ".json", request.toPrettyString());
            captureProviderRequest(request);
            byte[] response = responder.apply(request);
            writeRaw("provider-response-" + UUID.randomUUID() + ".sse", new String(response, StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        provider.start();
    }

    private void captureProviderRequest(JsonNode request) {
        offeredTools.add(toolNames(request));
        if (hasToolResult(request)) {
            String rendered = request.toString();
            if (rendered.contains(SKILL_BODY_MARKER)) {
                skillBodyVisibleInLaterRequest = true;
            }
            if (rendered.contains(MCP_RESULT_MARKER)) {
                mcpResultVisibleToModel = true;
            }
        }
    }

    private byte[] siblingPair(boolean clarificationFirst, String siblingName, String siblingArguments) {
        Map<String, String> clarify = Map.of(
                "id", "call-clarify-1", "name", CLARIFY, "arguments", jsonValue(Map.of(
                        "question", CLARIFY_QUESTION,
                        "clarification_type", "approach_choice",
                        "options", List.of("development", "production"))));
        Map<String, String> sibling =
                Map.of("id", "call-sibling-1", "name", siblingName, "arguments", siblingArguments);
        List<Map<String, String>> calls =
                clarificationFirst ? List.of(clarify, sibling) : List.of(sibling, clarify);
        return toolCalls(calls);
    }

    private byte[] singleToolCall(String name, String arguments) {
        return toolCalls(List.of(Map.of("id", "call-sibling-1", "name", name, "arguments", arguments)));
    }

    private String skillArguments() {
        return jsonValue(Map.of("skill", SKILL));
    }

    private byte[] toolCalls(List<Map<String, String>> calls) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int index = 0; index < calls.size(); index++) {
            Map<String, String> call = calls.get(index);
            entries.add(Map.of(
                    "index", index, "id", call.get("id"), "type", "function",
                    "function", Map.of("name", call.get("name"), "arguments", call.get("arguments"))));
        }
        String chunk = jsonValue(Map.of(
                "id", "provider-sdk-sibling", "model", "sibling-controlled",
                "choices", List.of(Map.of("index", 0, "finish_reason", "tool_calls",
                        "delta", Map.of("tool_calls", entries)))));
        return sse("provider-sdk-sibling", chunk);
    }

    private byte[] text(String content) {
        String chunk = jsonValue(Map.of(
                "id", "provider-sdk-sibling-text", "model", "sibling-controlled",
                "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                        "delta", Map.of("content", content)))));
        return sse("provider-sdk-sibling-text", chunk);
    }

    private byte[] sse(String responseId, String... chunks) {
        StringBuilder body = new StringBuilder();
        for (String chunk : chunks) {
            body.append("data: ").append(chunk).append("\n\n");
        }
        body.append("data: {\"id\":\"").append(responseId).append("\",\"model\":\"sibling-controlled\",")
                .append("\"choices\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":3}}\n\n")
                .append("data: [DONE]\n\n");
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String jsonValue(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private List<String> toolNames(JsonNode request) {
        List<String> names = new ArrayList<>();
        for (JsonNode tool : request.path("tools")) {
            String name = tool.path("function").path("name").asText("");
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    private String mcpToolNameOrNull(JsonNode request) {
        for (String name : toolNames(request)) {
            if (name.endsWith(MCP_TOOL_SUFFIX) || name.contains(MCP_TOOL)) {
                return name;
            }
        }
        return null;
    }

    private static boolean hasToolResult(JsonNode request) {
        for (JsonNode message : request.path("messages")) {
            if ("tool".equals(message.path("role").asText())) {
                return true;
            }
        }
        return false;
    }

    private void assertOffered(String... expected) {
        List<String> names = offeredTools.isEmpty()
                ? List.of()
                : new ArrayList<>(offeredTools.get(0));
        for (String name : expected) {
            if (name.equals(MCP_TOOL_SUFFIX)) {
                assertThat(names).as("the MCP tool must be registered for the model: " + names)
                        .anyMatch(candidate -> candidate.endsWith(MCP_TOOL_SUFFIX));
            } else {
                assertThat(names).as("the model's real tools list: " + names).contains(name);
            }
        }
    }

    // ---- facts ---------------------------------------------------------------------------------

    private Observed observe(String threadId, RunResult run) throws Exception {
        JsonNode latest = awaitTerminal(threadId, run.runId());
        writeRaw("run-" + run.runId() + ".json", latest.toPrettyString());
        writeRaw("state-" + threadId + ".json", state(threadId).toPrettyString());
        JsonNode diagnostics = latest.path("diagnostics");
        JsonNode tools = diagnostics.path("tool_calls");
        String status = latest.path("status").asText();
        boolean settled = !status.equals("pending") && !status.equals("running");
        return new Observed(
                status,
                diagnostics.path("sdk_status").asText(""),
                settled,
                hasClarificationCard(threadId),
                toolStatus(tools, CLARIFY).orElse("ABSENT"),
                toolStatus(tools, SKILL_LOAD).orElse("ABSENT"),
                mcpToolStatus(tools).orElse("ABSENT"),
                diagnostics.path("interactions").toString(),
                offeredTools.isEmpty() ? List.of() : new ArrayList<>(offeredTools.get(0)),
                skillBodyVisibleInLaterRequest,
                mcpResultVisibleToModel,
                mcp.toolsCallCount());
    }

    private static Optional<String> toolStatus(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.path("name").asText())) {
                return Optional.of(tool.path("status").asText());
            }
        }
        return Optional.empty();
    }

    private static Optional<String> mcpToolStatus(JsonNode tools) {
        for (JsonNode tool : tools) {
            if (tool.path("name").asText().endsWith(MCP_TOOL_SUFFIX)) {
                return Optional.of(tool.path("status").asText());
            }
        }
        return Optional.empty();
    }

    private boolean hasClarificationCard(String threadId) throws Exception {
        for (JsonNode message : state(threadId).path("values").path("messages")) {
            JsonNode payload = message.path("artifact").path("human_input");
            if (CLARIFY.equals(payload.path("source").asText())
                    && !payload.path("request_id").asText().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private record Observed(
            String httpStatus,
            String sdkStatus,
            boolean settled,
            boolean clarificationCard,
            String askClarificationStatus,
            String skillLoadStatus,
            String mcpToolStatus,
            String interactions,
            List<String> offeredTools,
            boolean skillBodyVisible,
            boolean mcpResultVisible,
            int mcpToolsCallCount) {
        Observed requiredSettled() {
            assertThat(settled).as("run must settle within the bounded wait: " + this).isTrue();
            return this;
        }

        String evidence(String scenario, String threadId, RunResult run, boolean sameTurnPair) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("scenario", scenario);
            values.put("thread", threadId);
            values.put("run", run.runId());
            values.put("http_status", httpStatus);
            values.put("sdk_status", sdkStatus);
            values.put("offered_tools", offeredTools);
            values.put("same_turn_pair_emitted", sameTurnPair);
            values.put("clarification_card", clarificationCard);
            values.put("ask_clarification_status", askClarificationStatus);
            values.put("skill_load_status", skillLoadStatus);
            values.put("skill_body_visible_in_later_request", skillBodyVisible);
            values.put("mcp_tool_status", mcpToolStatus);
            values.put("mcp_result_visible_to_model", mcpResultVisible);
            values.put("mcp_tools_call_count", mcpToolsCallCount);
            values.put("interactions", interactions);
            return "W05_SDK_SIBLING_EVIDENCE " + values;
        }
    }

    // ---- HTTP helpers --------------------------------------------------------------------------

    private void startGatewayAndRegister() throws Exception {
        api = DeerFlowBootstrapApi.create(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataRoot,
                Map.of("HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W05_SDK_SIBLING_KEY",
                        "W05_SDK_SIBLING_KEY", key));
        api.start();
        base = URI.create("http://127.0.0.1:" + api.address().getPort());

        String endpoint = "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1";
        String model = """
                {"config":{"name":"clarify-model","display_name":"Sibling Controlled",
                "provider":"openai-compatible","model":"sibling-controlled",
                "base_url":"%s","enabled":true,"supports_vision":false,
                "context_window":32768,"max_tokens":1024,"api_key":"controlled-key"},
                "expected_revision":null}
                """.formatted(endpoint);
        HttpResponse<String> saved = request("PUT", "/api/managed-models", model);
        assertThat(saved.statusCode()).as(saved.body()).isIn(200, 409);

        mcp = new LocalMcpServer();
        String servers = """
                {"mcp_servers":{"%s":{"type":"http","url":"%s","read_only":true,
                "tool_name_prefix":true,"session_init_timeout":20,"tool_call_timeout":20,
                "tools":{"%s":{}}}}}
                """.formatted(MCP_SERVER, mcp.url(), MCP_TOOL);
        HttpResponse<String> configured = request("POST", "/api/mcp/config/servers", servers);
        assertThat(configured.statusCode()).as(configured.body()).isEqualTo(200);
    }

    private void createThread(String threadId) throws Exception {
        assertThat(request("POST", "/api/threads", "{\"thread_id\":\"" + threadId + "\"}").statusCode())
                .isEqualTo(200);
    }

    private RunResult streamRun(String threadId, String text) throws Exception {
        HttpResponse<String> stream = request(
                "POST", "/api/threads/" + threadId + "/runs/stream", humanMessage(text));
        assertThat(stream.statusCode()).as(stream.body()).isEqualTo(200);
        String location = stream.headers().firstValue("content-location").orElseThrow();
        String runId = location.substring(location.lastIndexOf('/') + 1);
        writeRaw("run-" + runId + "-initial.sse", stream.body());
        JsonNode run = awaitTerminal(threadId, runId);
        return new RunResult(runId, run.path("status").asText(), stream.body(), run);
    }

    private String humanMessage(String text) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", "human-1");
        message.put("type", "human");
        message.put("content", List.of(Map.of("type", "text", "text", text)));
        message.put("additional_kwargs", Map.of());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assistant_id", "lead_agent");
        body.put("input", Map.of("messages", List.of(message)));
        body.put("stream_mode", List.of("messages-tuple", "updates", "custom"));
        body.put("context", Map.of("model_name", "clarify-model", "mode", "flash"));
        body.put("on_disconnect", "continue");
        body.put("multitask_strategy", "reject");
        try {
            return json.writeValueAsString(body);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private JsonNode awaitTerminal(String threadId, String runId) throws Exception {
        JsonNode run = null;
        for (int attempt = 0; attempt < 800; attempt++) {
            run = requestJson("GET", "/api/threads/" + threadId + "/runs/" + runId);
            String status = run.path("status").asText();
            if (!status.equals("pending") && !status.equals("running")) {
                return run;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Run did not settle within the bounded wait: " + run);
    }

    private JsonNode state(String threadId) throws Exception {
        return requestJson("GET", "/api/threads/" + threadId + "/state");
    }

    private static Optional<String> humanInputRequestId(String transcript) throws Exception {
        Matcher matcher = Pattern.compile("event: messages\\ndata: ([^\\n]+)\\n").matcher(transcript);
        while (matcher.find()) {
            JsonNode message = new ObjectMapper().readTree(matcher.group(1)).path(0);
            JsonNode requestId = message.path("artifact").path("human_input").path("request_id");
            if (requestId.isTextual()) {
                return Optional.of(requestId.asText());
            }
        }
        return Optional.empty();
    }

    private static String approvalBody(String requestId) {
        return """
                {"assistant_id":"lead_agent","input":{"messages":[{"id":"hidden-response","type":"human","content":[{"type":"text","text":"For your clarification, my answer is: approve"}],"additional_kwargs":{"hide_from_ui":true,"human_input_response":{"version":1,"kind":"human_input_response","source":"haifa_interaction","request_id":"%s","response_kind":"option","option_id":"approve","value":"approve"}}}]},"stream_mode":["messages-tuple","updates","custom"],"on_disconnect":"continue","multitask_strategy":"reject"}
                """
                .formatted(requestId);
    }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        HttpResponse<String> response = request("POST", path, body);
        writeRaw("approval-request.json", body);
        writeRaw("approval-response.sse", response.body());
        return response;
    }

    private void writeRaw(String name, String contents) throws IOException {
        Files.createDirectories(evidenceRoot);
        Files.writeString(evidenceRoot.resolve(name), contents, StandardCharsets.UTF_8);
    }

    private JsonNode requestJson(String method, String path) throws Exception {
        HttpResponse<String> response = request(method, path, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(30));
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private record RunResult(String runId, String status, String body, JsonNode run) {}

    // ---- minimal Streamable HTTP MCP server (JDK HttpServer, protocol 2025-06-18) --------------

    /**
     * A local MCP fixture for this test only. It implements the minimal Streamable HTTP subset the
     * public SDK MCP client needs, and counts real {@code tools/call} requests so an unapproved tool
     * can never be mistaken for an executed one.
     */
    static final class LocalMcpServer implements AutoCloseable {
        private static final String PROTOCOL_VERSION = "2025-06-18";
        private final ObjectMapper mapper = new ObjectMapper();
        private final HttpServer server;
        private final ExecutorService executor;
        private final AtomicInteger toolsCallCount = new AtomicInteger();
        private final List<String> calledTools = Collections.synchronizedList(new ArrayList<>());
        private final String sessionId = UUID.randomUUID().toString();

        LocalMcpServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newFixedThreadPool(4);
            server.setExecutor(executor);
            server.createContext("/mcp", this::handle);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        }

        int toolsCallCount() {
            return toolsCallCount.get();
        }

        @SuppressWarnings("unused")
        List<String> calledTools() {
            return List.copyOf(calledTools);
        }

        @Override
        public void close() {
            server.stop(0);
            executor.close();
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "POST");
                    sendText(exchange, 405, "method not allowed");
                    return;
                }
                JsonNode root = mapper.readTree(exchange.getRequestBody().readAllBytes());
                if (root.isArray()) {
                    ArrayNode responses = mapper.createArrayNode();
                    for (JsonNode item : root) {
                        ObjectNode response = dispatch(item);
                        if (response != null) {
                            responses.add(response);
                        }
                    }
                    exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId);
                    if (responses.isEmpty()) {
                        exchange.sendResponseHeaders(202, -1);
                    } else {
                        sendJson(exchange, 200, responses.toString());
                    }
                    return;
                }
                ObjectNode response = dispatch(root);
                exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId);
                if (response == null) {
                    exchange.sendResponseHeaders(202, -1);
                } else {
                    sendJson(exchange, 200, response.toString());
                }
            } catch (Exception failure) {
                sendJson(exchange, 500, "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":"
                        + "{\"code\":-32603,\"message\":\"internal error\"}}");
            } finally {
                exchange.close();
            }
        }

        private ObjectNode dispatch(JsonNode request) {
            JsonNode id = request.get("id");
            if (id == null || id.isNull()) {
                return null;
            }
            String method = request.path("method").asText("");
            ObjectNode response = mapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", id);
            switch (method) {
                case "initialize" -> response.set("result", initializeResult());
                case "ping" -> response.set("result", mapper.createObjectNode());
                case "tools/list" -> response.set("result", toolsListResult());
                case "tools/call" -> response.set("result", toolsCallResult(request));
                default -> response.set("error", errorNode(-32601, "Method not found: " + method));
            }
            return response;
        }

        private ObjectNode initializeResult() {
            ObjectNode result = mapper.createObjectNode();
            result.put("protocolVersion", PROTOCOL_VERSION);
            result.putObject("capabilities").putObject("tools").put("listChanged", false);
            ObjectNode info = result.putObject("serverInfo");
            info.put("name", MCP_SERVER);
            info.put("version", "1.0.0");
            result.put("instructions", "W05 clarification sibling SDK evidence local MCP fixture");
            return result;
        }

        private ObjectNode toolsListResult() {
            ObjectNode result = mapper.createObjectNode();
            ArrayNode tools = result.putArray("tools");
            ObjectNode tool = tools.addObject();
            tool.put("name", MCP_TOOL);
            tool.put("description", "Deterministic local probe used to count real tools/call requests.");
            ObjectNode schema = tool.putObject("inputSchema");
            schema.put("type", "object");
            schema.putObject("properties");
            schema.putArray("required");
            schema.put("additionalProperties", false);
            tool.putObject("annotations").put("readOnlyHint", true);
            return result;
        }

        private ObjectNode toolsCallResult(JsonNode request) {
            String name = request.path("params").path("name").asText("");
            toolsCallCount.incrementAndGet();
            calledTools.add(name);
            ObjectNode result = mapper.createObjectNode();
            ArrayNode content = result.putArray("content");
            content.addObject().put("type", "text").put("text", MCP_RESULT_MARKER + " " + name);
            result.put("isError", false);
            return result;
        }

        private ObjectNode errorNode(int code, String message) {
            ObjectNode error = mapper.createObjectNode();
            error.put("code", code);
            error.put("message", message);
            return error;
        }

        private void sendJson(HttpExchange exchange, int status, String payload) throws IOException {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }

        private void sendText(HttpExchange exchange, int status, String payload) throws IOException {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }
}

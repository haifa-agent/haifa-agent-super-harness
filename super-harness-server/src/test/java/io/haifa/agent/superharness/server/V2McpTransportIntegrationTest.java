package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelStreamSink;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

/**
 * L2 evidence for the V2 minimum MCP carriage. Drives the real Haifa SDK MCP client
 * (through {@code McpToolPlatforms}) against the two independent local Streamable HTTP
 * test servers started by {@code scripts/start-v2-test-mcp-servers.ps1}. Every tool
 * result in this test is produced by a real loopback HTTP MCP call; no stub, route
 * fulfilment or in-memory tool is used.
 */
@TestMethodOrder(OrderAnnotation.class)
class V2McpTransportIntegrationTest {
    private static final String REPORTING_URL = System.getProperty(
            "v2.mcp.reporting.url", "http://127.0.0.1:30628/mcp");
    private static final String REGION_URL =
            System.getProperty("v2.mcp.region.url", "http://127.0.0.1:30629/mcp");
    private static final long REGION_PID = Long.getLong("v2.mcp.region.pid", -1L);

    private final ObjectMapper json = new ObjectMapper();
    private final SwitchableMcpModel model = new SwitchableMcpModel();
    private final SecretKey continuationKey = new SecretKeySpec(new byte[32], "AES");
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    @TempDir
    Path dataDirectory;
    private DeerFlowBootstrapApi api;
    private URI baseUri;
    private HaifaRunBridge.ModelBinding binding;

    @BeforeEach
    void startServer() throws Exception {
        assumeTrue(reachable(REPORTING_URL), "v2-reporting test MCP server is not running");
        assumeTrue(reachable(REGION_URL), "v2-region test MCP server is not running");
        binding = new HaifaRunBridge.ModelBinding("v2-mcp-probe", "1.0.0", model, snapshot());
        api = DeerFlowBootstrapApi.createForTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory,
                ignored -> binding,
                continuationKey);
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
    }

    @AfterEach
    void stopServer() {
        if (api != null) {
            api.close();
        }
    }

    @Test
    @Order(1)
    void discoversBothServersAndInvokesEachOverRealTransport() throws Exception {
        configureBothServers();

        String listing = run("mcp-discovery", null, "list tools");
        assertThat(listing).contains("TOOLS:");
        assertThat(listing).contains("get_reporting_policy", "get_region_label");
        assertThat(countOccurrences(listing, "_get_status")).isEqualTo(2);

        String reporting = run("mcp-reporting", null, "call get_reporting_policy {}");
        assertThat(reporting).contains("RESULT:");
        assertThat(reporting).contains("policy_id");
        assertThat(reporting).contains("v2-pilot-reporting-001");
        assertThat(reporting).contains("1500");
        assertThat(reporting).contains("v2-reporting");

        String region = run("mcp-region", null, "call get_region_label {\"region\":\"East\"}");
        assertThat(region).contains("RESULT:");
        assertThat(region).doesNotContain("NO-TOOL");

        String reportingStatus = run("mcp-status-reporting", null, "call reporting_get_status {}");
        assertThat(reportingStatus).contains("v2-reporting");

        String regionStatus = run("mcp-status-region", null, "call region_get_status {}");
        assertThat(regionStatus).contains("v2-region");

        System.out.println("W10_MCP_L2_EVIDENCE discovery=2x2 reporting=policy_id:v2-pilot-reporting-001"
                + " region=get_region_label get_status=same-name-distinct");
    }

    @Test
    @Order(2)
    void disablingAServerRevokesOnlyThatServerOnLaterRuns() throws Exception {
        configureBothServers();
        createThread("mcp-toggle-thread");
        assertThat(run("mcp-toggle-thread", null, "list tools")).contains("get_region_label");

        patch("/api/mcp/config", "{\"server_name\":\"v2-region\",\"enabled\":false}");

        String afterDisable = run("mcp-toggle-thread", null, "list tools");
        assertThat(afterDisable).contains("get_reporting_policy");
        assertThat(afterDisable).doesNotContain("get_region_label");

        String reporting = run("mcp-toggle-thread", null, "call get_reporting_policy {}");
        assertThat(reporting).contains("v2-pilot-reporting-001");

        String revoked = run("mcp-toggle-thread", null, "call region_get_region_label {\"region\":\"East\"}");
        assertThat(revoked).contains("NO-TOOL");
        assertThat(revoked).doesNotContain("华东");
    }

    @Test
    @Order(3)
    void emptyMcpPluginsSelectsNoServerButRunProceeds() throws Exception {
        configureBothServers();
        HttpResponse<String> created = postJson(
                "/api/agents",
                "{\"name\":\"no-mcp\",\"display_name\":\"No MCP\",\"mcp_plugins\":[],\"soul\":\"\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);

        String listing = run("mcp-none", "no-mcp", "list tools");
        assertThat(listing).contains("TOOLS:");
        assertThat(listing).doesNotContain("get_reporting_policy", "get_region_label");

        // The same message with no Agent selection still exposes every enabled server.
        String all = run("mcp-all", null, "list tools");
        assertThat(all).contains("get_reporting_policy");
    }

    @Test
    @Order(5)
    void changedDeclarationRebuildsTheBridgeButAnUnchangedConfigDoesNot() throws Exception {
        configureBothServers();
        createThread("mcp-signature");
        assertThat(run("mcp-signature", null, "list tools")).contains("get_reporting_policy");
        long afterFirst = api.threadRunApi().bridgeBuildCount();

        assertThat(run("mcp-signature", null, "list tools")).contains("get_reporting_policy");
        assertThat(api.threadRunApi().bridgeBuildCount())
                .as("an identical MCP projection must not rebuild the bridge")
                .isEqualTo(afterFirst);

        replaceServer("v2-reporting", """
                {"type":"http","url":"%s","read_only":true,"tool_name_prefix":true,
                 "session_init_timeout":20,"tool_call_timeout":21,
                 "tools":{"get_reporting_policy":{},"get_status":{}}}
                """.formatted(REPORTING_URL));
        assertThat(run("mcp-signature", null, "list tools")).contains("get_reporting_policy");
        assertThat(api.threadRunApi().bridgeBuildCount())
                .as("a tool_call_timeout change must rebuild with the new declaration")
                .isEqualTo(afterFirst + 1);

        replaceServer("v2-reporting", """
                {"type":"http","url":"%s","read_only":true,"tool_name_prefix":true,
                 "session_init_timeout":20,"tool_call_timeout":21,
                 "headers":{"X-Probe":"alpha"},
                 "tools":{"get_reporting_policy":{},"get_status":{}}}
                """.formatted(REPORTING_URL));
        assertThat(run("mcp-signature", null, "list tools")).contains("get_reporting_policy");
        assertThat(api.threadRunApi().bridgeBuildCount())
                .as("a header change must rebuild with the new declaration")
                .isEqualTo(afterFirst + 2);
    }

    @Test
    @Order(6)
    void activeSteerAndApprovalReuseTheMountedMcpBridgeWithoutRebuild() throws Exception {
        configureBothServers();
        createThread("mcp-reuse");

        model.arm();
        HttpResponse<java.io.InputStream> started = postStream(
                "/api/threads/mcp-reuse/runs/stream", buildRunBody("list tools", null), null, null);
        assertThat(started.statusCode()).isEqualTo(200);
        long mounted = api.threadRunApi().bridgeBuildCount();
        assertThat(model.awaitEntered()).as("model must enter its first call").isTrue();

        HttpResponse<java.io.InputStream> steer = postStream(
                "/api/threads/mcp-reuse/runs/stream",
                buildSteerBody("list tools"),
                "X-Haifa-Run-Input",
                "steer");
        assertThat(steer.statusCode()).as("an active steer must be accepted").isEqualTo(200);
        assertThat(api.threadRunApi().bridgeBuildCount())
                .as("a steer must never rebuild the mounted bridge")
                .isEqualTo(mounted);

        model.release();
        drain(started.body());
        drain(steer.body());
        assertThat(run("mcp-reuse", null, "list tools")).contains("get_reporting_policy");
        assertThat(api.threadRunApi().bridgeBuildCount())
                .as("the MCP tool stays mounted after a steer")
                .isEqualTo(mounted);

        // Read-only MCP tools still traverse the SDK approval policy. The pending card and
        // the approval must both reuse the same mounted bridge.
        HttpResponse<String> pending = postJson(
                "/api/threads/mcp-reuse/runs/stream", buildRunBody("call get_reporting_policy {}", null));
        assertThat(pending.statusCode()).as(pending.body()).isEqualTo(200);
        String requestId = humanInputRequestId(pending.body()).orElseThrow();
        assertThat(api.threadRunApi().bridgeBuildCount())
                .as("a pending approval must not rebuild the bridge")
                .isEqualTo(mounted);

        HttpResponse<String> approved = postJson(
                "/api/threads/mcp-reuse/runs/stream", approvalBody(requestId));
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(approved.body()).contains("v2-pilot-reporting-001");
        assertThat(countToolResults(approved.body(), "get_reporting_policy")).isEqualTo(1);
        assertThat(api.threadRunApi().bridgeBuildCount())
                .as("approval must reuse the mounted bridge")
                .isEqualTo(mounted);
    }

    @Test
    @Order(7)
    void restartRecoversPendingMcpApprovalAndExecutesTheToolExactlyOnce() throws Exception {
        configureBothServers();
        createThread("mcp-restart");
        HttpResponse<String> pending = postJson(
                "/api/threads/mcp-restart/runs/stream", buildRunBody("call get_reporting_policy {}", null));
        assertThat(pending.statusCode()).as(pending.body()).isEqualTo(200);
        String location = pending.headers().firstValue("content-location").orElseThrow();
        String requestId = humanInputRequestId(pending.body()).orElseThrow();
        assertThat(countToolResults(pending.body(), "get_reporting_policy")).isZero();

        // An exact gateway restart on the same data-root and continuation key.
        api.close();
        api = DeerFlowBootstrapApi.createForTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory,
                ignored -> binding,
                continuationKey);
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());

        HttpResponse<String> approved = postJson(
                "/api/threads/mcp-restart/runs/stream", approvalBody(requestId));
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(approved.headers().firstValue("content-location")).contains(location);
        assertThat(approved.body()).contains("event: end", "v2-pilot-reporting-001");
        assertThat(countToolResults(approved.body(), "get_reporting_policy"))
                .as("the recovered MCP tool must execute exactly once")
                .isEqualTo(1);
    }

    @Test
    @Order(8)
    void disconnectedMcpResultRetainsFailedToolCallAndRunIdentity() throws Exception {
        assumeTrue(Boolean.getBoolean("v2.mcp.negative.controls"),
                "W21 transport-failure diagnostics require the real fixture's negative controls");
        configureBothServers();
        URI controlRoot = URI.create(REPORTING_URL).resolve("/__test/");
        JsonNode before = json.readTree(client.send(
                HttpRequest.newBuilder(controlRoot.resolve("state")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body());
        HttpResponse<String> armed = client.send(
                HttpRequest.newBuilder(controlRoot.resolve("arm-disconnect"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(armed.statusCode()).as(armed.body()).isEqualTo(200);

        createThread("mcp-diagnostic-disconnect");
        HttpResponse<String> pending = postJson(
                "/api/threads/mcp-diagnostic-disconnect/runs/stream",
                buildRunBody("call reporting_get_status {}", null));
        System.out.println("W21_MCP_PENDING_SSE=" + json.writeValueAsString(pending.body()));
        assertThat(pending.statusCode()).as(pending.body()).isEqualTo(200);
        String location = pending.headers().firstValue("content-location").orElseThrow();
        String requestId = humanInputRequestId(pending.body()).orElseThrow();
        HttpResponse<String> resumed = postJson(
                "/api/threads/mcp-diagnostic-disconnect/runs/stream", approvalBody(requestId));
        System.out.println("W21_MCP_RESUMED_SSE=" + json.writeValueAsString(resumed.body()));
        System.out.println("W21_MCP_RESUMED_SSE_SHA256=" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(resumed.body().getBytes(StandardCharsets.UTF_8))));
        assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
        assertThat(resumed.body()).contains("event: end");

        HttpResponse<String> runResponse = client.send(
                HttpRequest.newBuilder(baseUri.resolve(location)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(runResponse.statusCode()).isEqualTo(200);
        JsonNode run = json.readTree(runResponse.body());
        JsonNode tool = null;
        for (JsonNode candidate : run.path("diagnostics").path("tool_calls")) {
            if (candidate.path("name").asText().contains("reporting_get_status")) {
                tool = candidate;
                break;
            }
        }
        assertThat(tool).isNotNull();
        assertThat(tool.path("run_id").asText()).isEqualTo(run.path("run_id").asText());
        assertThat(tool.path("tool_call_id").asText()).isNotBlank();
        assertThat(tool.path("status").asText()).isEqualTo("FAILED");
        assertThat(tool.path("error_code").asText()).isNotBlank();
        assertThat(run.path("diagnostics").path("interactions").findValuesAsText("request_id"))
                .contains(requestId);
        JsonNode after = json.readTree(client.send(
                HttpRequest.newBuilder(controlRoot.resolve("state")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body());
        assertThat(after.path("get_status_calls").asInt())
                .isEqualTo(before.path("get_status_calls").asInt() + 1);
        assertThat(after.path("disconnects_after_call").asInt())
                .isEqualTo(before.path("disconnects_after_call").asInt() + 1);
        System.out.println("W21_MCP_FAILURE_EVIDENCE run=" + run.path("run_id").asText()
                + " tool=" + tool.path("tool_call_id").asText()
                + " status=" + tool.path("status").asText()
                + " code=" + tool.path("error_code").asText()
                + " fixture=" + after);
    }

    @Test
    @Order(9)
    void oneServerFailureIsIsolatedFromItsSibling() throws Exception {
        configureBothServers();
        assumeTrue(REGION_PID > 0, "no v2.mcp.region.pid supplied; isolation kill step not run");

        boolean killed = ProcessHandle.of(REGION_PID)
                .map(handle -> {
                    handle.destroy();
                    return true;
                })
                .orElse(false);
        assumeTrue(killed, "v2-region process could not be destroyed");
        waitForPortClosed(REGION_URL, Duration.ofSeconds(10));

        String listing = run("mcp-partial", null, "list tools");
        assertThat(listing).contains("get_reporting_policy");
        assertThat(listing).doesNotContain("get_region_label");

        String reporting = run("mcp-partial-reporting", null, "call get_reporting_policy {}");
        assertThat(reporting).contains("v2-pilot-reporting-001");
    }

    private void configureBothServers() throws Exception {
        HttpResponse<String> response = postJson(
                "/api/mcp/config/servers",
                """
                {"mcp_servers":{
                  "v2-reporting":{"type":"http","url":"%s","read_only":true,
                    "tool_name_prefix":true,"session_init_timeout":20,"tool_call_timeout":20,
                    "tools":{"get_reporting_policy":{},"get_status":{}}},
                  "v2-region":{"type":"http","url":"%s","read_only":true,
                    "tool_name_prefix":true,"session_init_timeout":20,"tool_call_timeout":20,
                    "tools":{"get_region_label":{},"get_status":{}}}}}
                """
                        .formatted(REPORTING_URL, REGION_URL));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    private String run(String threadId, String agentName, String message) throws Exception {
        createThread(threadId);
        String body = buildRunBody(message, agentName);
        HttpResponse<String> response = postJson("/api/threads/" + threadId + "/runs/stream", body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String runId = response.headers().firstValue("content-location").orElseThrow();
        assertThat(runId).contains("/runs/");
        // Read-only MCP tools still traverse the SDK approval policy (a network side effect),
        // so a real call pauses once with a frozen human-input card. Approve it and join the
        // resumed stream so the real MCP payload reaches the model.
        java.util.Optional<String> requestId = humanInputRequestId(response.body());
        if (requestId.isEmpty()) {
            return response.body();
        }
        HttpResponse<String> resumed = postJson(
                "/api/threads/" + threadId + "/runs/stream",
                approvalBody(requestId.orElseThrow()));
        assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
        assertThat(resumed.body()).contains("event: end");
        return response.body() + "\n" + resumed.body();
    }

    private static java.util.Optional<String> humanInputRequestId(String transcript) throws Exception {
        Matcher matcher = Pattern.compile("event: messages\\ndata: ([^\\n]+)\\n").matcher(transcript);
        while (matcher.find()) {
            JsonNode message = new ObjectMapper().readTree(matcher.group(1)).path(0);
            JsonNode requestId = message.path("artifact").path("human_input").path("request_id");
            if (requestId.isTextual()) {
                return java.util.Optional.of(requestId.asText());
            }
        }
        return java.util.Optional.empty();
    }

    private static String approvalBody(String requestId) {
        return """
                {"assistant_id":"lead_agent","input":{"messages":[{"id":"hidden-response","type":"human","content":[{"type":"text","text":"For your clarification, my answer is: approve"}],"additional_kwargs":{"hide_from_ui":true,"human_input_response":{"version":1,"kind":"human_input_response","source":"haifa_interaction","request_id":"%s","response_kind":"option","option_id":"approve","value":"approve"}}}]},"stream_mode":["messages-tuple","updates","custom"],"on_disconnect":"continue","multitask_strategy":"reject"}
                """
                .formatted(requestId);
    }

    /** The steer contract rejects {@code multitask_strategy} on the same request. */
    private static String buildSteerBody(String message) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        root.put("assistant_id", "lead_agent");
        var messageNode = root.putObject("input").putArray("messages").addObject();
        messageNode.put("id", "steer-1");
        messageNode.put("type", "human");
        messageNode.putArray("content").addObject().put("type", "text").put("text", message);
        var streamMode = root.putArray("stream_mode");
        streamMode.add("messages-tuple");
        streamMode.add("updates");
        streamMode.add("custom");
        root.put("on_disconnect", "continue");
        return mapper.writeValueAsString(root);
    }

    private static String buildRunBody(String message, String agentName) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        root.put("assistant_id", "lead_agent");
        var messageNode = root.putObject("input").putArray("messages").addObject();
        messageNode.put("id", "human-1");
        messageNode.put("type", "human");
        messageNode.putArray("content").addObject().put("type", "text").put("text", message);
        if (agentName != null) {
            root.putObject("context").put("agent_name", agentName);
        }
        var streamMode = root.putArray("stream_mode");
        streamMode.add("messages-tuple");
        streamMode.add("updates");
        streamMode.add("custom");
        root.put("on_disconnect", "continue");
        root.put("multitask_strategy", "reject");
        return mapper.writeValueAsString(root);
    }

    private void createThread(String threadId) throws Exception {
        HttpResponse<String> response = postJson("/api/threads", "{\"thread_id\":\"" + threadId + "\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void patch(String path, String body) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(baseUri.resolve(path))
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    private void replaceServer(String name, String serverJson) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(baseUri.resolve("/api/mcp/config/server"))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(
                                "{\"server_name\":\"" + name + "\",\"server\":" + serverJson + "}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    private HttpResponse<java.io.InputStream> postStream(
            String path, String body, String headerName, String headerValue) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (headerName != null) {
            request.header(headerName, headerValue);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private static void drain(java.io.InputStream body) throws Exception {
        try (body) {
            body.transferTo(java.io.OutputStream.nullOutputStream());
        }
    }

    /** Counts the frozen {@code tool} transcript messages for one MCP tool in an SSE transcript. */
    private static int countToolResults(String transcript, String toolNameFragment) throws Exception {
        Matcher matcher = Pattern.compile("event: messages\\ndata: ([^\\n]+)\\n").matcher(transcript);
        int count = 0;
        while (matcher.find()) {
            JsonNode message = new ObjectMapper().readTree(matcher.group(1)).path(0);
            if ("tool".equals(message.path("type").asText())
                    && message.path("name").asText().contains(toolNameFragment)
                    && message.path("additional_kwargs").path("haifa_tool_result").isObject()) {
                count++;
            }
        }
        return count;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int index = haystack.indexOf(needle); index >= 0; index = haystack.indexOf(needle, index + 1)) {
            count++;
        }
        return count;
    }

    private static boolean reachable(String url) {
        URI uri = URI.create(url);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 500);
            return true;
        } catch (Exception unreachable) {
            return false;
        }
    }

    private static void waitForPortClosed(String url, Duration timeout) {
        URI uri = URI.create(url);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 200);
            } catch (Exception closed) {
                return;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static ResolvedModelSnapshot snapshot() {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("v2-mcp-probe"),
                "1.0.0",
                new ModelDefinitionId("v2-mcp-probe"),
                "1.0.0",
                "v2-mcp-probe",
                "v2-mcp-probe-adapter",
                "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                URI.create("https://model.invalid"),
                new CredentialRef("env://SUPER_HARNESS_V2_MCP_PROBE"),
                false,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                8_192,
                1_024,
                Map.of(),
                Map.of());
    }

    /**
     * Wraps the scripted probe model so a test can hold the first model call open and issue
     * an Active Steer against a genuinely running Run.
     */
    static final class SwitchableMcpModel implements AgentChatModel {
        private final ScriptedMcpModel delegate = new ScriptedMcpModel();
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;

        void arm() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        boolean awaitEntered() throws InterruptedException {
            CountDownLatch latch = entered;
            return latch != null && latch.await(15, TimeUnit.SECONDS);
        }

        void release() {
            CountDownLatch latch = release;
            if (latch != null) {
                latch.countDown();
            }
        }

        private void gate() {
            CountDownLatch enteredLatch = entered;
            CountDownLatch releaseLatch = release;
            if (enteredLatch == null || releaseLatch == null) {
                return;
            }
            enteredLatch.countDown();
            try {
                releaseLatch.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public AgentChatResponse invoke(AgentChatRequest request) {
            gate();
            return delegate.invoke(request);
        }

        @Override
        public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
            gate();
            return delegate.invokeStreaming(request, sink);
        }
    }

    /**
     * Deterministic, stateless probe model. It reads a directive from the latest user
     * message: {@code list tools} reports the exact tool names the SDK offered, and
     * {@code call <substring> <json>} calls the matching tool by its real name. After a
     * tool result arrives it echoes that result back as the final answer so the test can
     * assert on the real MCP payload.
     */
    static final class ScriptedMcpModel implements AgentChatModel {
        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public AgentChatResponse invoke(AgentChatRequest request) {
            String directive = lastUserText(request);
            if (directive.startsWith("list tools")) {
                String names = request.tools().stream()
                        .map(ModelToolSpecification::name)
                        .sorted()
                        .collect(Collectors.joining(","));
                return finalAnswer(request, "TOOLS:" + names);
            }
            // Only the current turn's tool result counts; the durable conversation still
            // carries the previous turn's TOOL message.
            List<ModelMessage> currentTurn = messagesAfterLastUser(request);
            boolean hasToolResult = currentTurn.stream()
                    .anyMatch(message -> message.role() == ModelMessageRole.TOOL);
            if (hasToolResult) {
                return finalAnswer(request, "RESULT:" + lastToolText(currentTurn));
            }
            if (directive.startsWith("call ")) {
                String rest = directive.substring("call ".length()).trim();
                int separator = rest.indexOf(' ');
                String substring = separator < 0 ? rest : rest.substring(0, separator);
                String argumentsJson = separator < 0 ? "{}" : rest.substring(separator + 1).trim();
                return request.tools().stream()
                        .filter(tool -> tool.name().contains(substring))
                        .findFirst()
                        .map(tool -> toolCall(request, tool.name(), argumentsJson))
                        .orElseGet(() -> finalAnswer(request, "NO-TOOL:" + substring));
            }
            return finalAnswer(request, "NOOP");
        }

        @Override
        public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
            return invoke(request);
        }

        private AgentChatResponse toolCall(AgentChatRequest request, String name, String argumentsJson) {
            Map<String, Object> arguments = parseArguments(argumentsJson);
            return new AgentChatResponse(
                    "v2-mcp-tool-call",
                    request.model().providerModelId(),
                    "",
                    List.of(new ModelToolCall(
                            new ProviderToolCallCorrelationId("v2-mcp-correlation"), name, arguments)),
                    ModelFinishReason.TOOL_CALLS,
                    ModelUsage.unpriced(1, 1),
                    "",
                    Map.of());
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> parseArguments(String argumentsJson) {
            if (argumentsJson.isBlank()) return Map.of();
            try {
                return mapper.readValue(argumentsJson, Map.class);
            } catch (Exception invalid) {
                throw new IllegalArgumentException("invalid probe tool arguments: " + argumentsJson, invalid);
            }
        }

        private AgentChatResponse finalAnswer(AgentChatRequest request, String content) {
            return new AgentChatResponse(
                    "v2-mcp-final",
                    request.model().providerModelId(),
                    content,
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(1, Math.max(1, content.length() / 4)),
                    "",
                    Map.of());
        }

        private static String lastUserText(AgentChatRequest request) {
            return request.messages().stream()
                    .filter(message -> message.role() == ModelMessageRole.USER)
                    .reduce((first, second) -> second)
                    .map(ModelMessage::content)
                    .orElse("");
        }

        private static List<ModelMessage> messagesAfterLastUser(AgentChatRequest request) {
            List<ModelMessage> messages = request.messages();
            int lastUser = -1;
            for (int index = 0; index < messages.size(); index++) {
                if (messages.get(index).role() == ModelMessageRole.USER) lastUser = index;
            }
            return messages.subList(Math.max(0, lastUser + 1), messages.size());
        }

        private static String lastToolText(List<ModelMessage> currentTurn) {
            StringBuilder text = new StringBuilder();
            currentTurn.stream()
                    .filter(message -> message.role() == ModelMessageRole.TOOL)
                    .forEach(message -> {
                        if (message.content() != null && !message.content().isBlank()) {
                            text.append(message.content());
                        }
                        if (message.toolResultData() != null && !message.toolResultData().isEmpty()) {
                            text.append(message.toolResultData());
                        }
                    });
            return text.toString();
        }
    }
}

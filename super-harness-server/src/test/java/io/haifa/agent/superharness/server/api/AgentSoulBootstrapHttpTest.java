package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static io.haifa.agent.superharness.server.testsupport.AgentSoulTestSupport.*;
import static io.haifa.agent.superharness.server.testsupport.GatewayFixture.*;
import static io.haifa.agent.superharness.server.testsupport.ControlledModel.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.superharness.server.prompt.LeadPromptInputs;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Oracle04/05 always use production model, Skill and MCP resolvers. */
class AgentSoulBootstrapHttpTest {
    @TempDir Path data;

    @org.junit.jupiter.api.BeforeEach
    void isolatedData() throws Exception {
        data = dataDirectory();
    }

    @Test
    void bootstrapBypassesOnlyAgentLookupInAllResolvers() throws Exception {
        try (var fixture = new GatewayFixture.Managed(data)) {
            var http = fixture.http;
            http.thread("bootstrap-missing");
            http.completed(http.run("bootstrap-missing", "missing-legal-agent", true));
            assertThat(fixture.requests.size()).isEqualTo(1);
            assertThat(names(fixture, 0)).contains("setup_agent").doesNotContain("update_agent");
            for (Boolean flag : java.util.Arrays.asList(null, false)) {
                String thread = flag == null ? "missing-no-flag" : "missing-false";
                http.thread(thread);
                assertThat(http.run(thread, "missing-legal-agent", flag).statusCode()).isEqualTo(422);
                assertThat(fixture.requests.size()).isEqualTo(1);
            }
            List<ObjectNode> invalid = List.of(
                    http.input("missing-legal-agent", true, "bad-boolean"),
                    http.input("../illegal-agent", true, "bad-name"),
                    http.input("missing-legal-agent", true, "bad-context"));
            ((ObjectNode) invalid.get(0).path("context")).put("is_bootstrap", "true");
            invalid.get(2).put("context", "not-an-object");
            int index = 0;
            for (var body : invalid) {
                String thread = "invalid-bootstrap-" + index++;
                http.thread(thread);
                assertThat(http.request("POST", "/api/threads/" + thread + "/runs/stream", body).statusCode())
                        .isEqualTo(422);
                assertThat(fixture.requests.size()).isEqualTo(1);
            }
            http.thread("bootstrap-no-name");
            http.completed(http.run("bootstrap-no-name", null, true));
            assertThat(fixture.requests.size()).isEqualTo(2);
            assertThat(names(fixture, 1)).contains("setup_agent").doesNotContain("update_agent");
            assertThat(instructions(fixture, 1).contains("You are " + LeadPromptInputs.DEFAULT_AGENT_NAME + ",")).isTrue();
        }
    }

    @Test
    void bootstrapDeclaresOnlyBaseAndSetupEvenWhenExistingAgentSkillAndMcpAreEnabled() throws Exception {
        AtomicInteger initializeCalls = new AtomicInteger();
        AtomicInteger listCalls = new AtomicInteger();
        HttpServer mcp = localMcp(initializeCalls, listCalls);
        mcp.start();
        try (var fixture = new GatewayFixture.Managed(data)) {
            var http = fixture.http;
            http.seed("agent-a", "synthetic custom identity that bootstrap must ignore");
            ObjectNode all = JSON.createObjectNode();
            all.putNull("skills");
            all.putNull("mcp_plugins");
            http.update("agent-a", all);
            assertThat(http.request("PUT", "/api/skills/consulting-analysis",
                    JSON.createObjectNode().put("enabled", true)).statusCode()).isEqualTo(200);
            ObjectNode config = JSON.createObjectNode();
            ObjectNode server = config.putObject("mcp_servers").putObject("m3-probe")
                    .put("type", "http").put("url", "http://127.0.0.1:" + mcp.getAddress().getPort() + "/mcp")
                    .put("enabled", true).put("read_only", true).put("tool_name_prefix", true)
                    .put("session_init_timeout", 5).put("tool_call_timeout", 5);
            server.putObject("tools").putObject("probe_tool");
            assertThat(http.request("POST", "/api/mcp/config/servers", config).statusCode()).isEqualTo(200);

            // Prove the same installed/enabled configuration really exposes both capabilities.
            http.thread("custom-positive");
            http.completed(http.run("custom-positive", "agent-a", false));
            assertThat(fixture.requests.size()).isEqualTo(1);
            assertThat(names(fixture, 0)).contains("skill_load", "skill_resource_read", "update_agent");
            assertThat(names(fixture, 0).stream().anyMatch(name -> name.endsWith("probe_tool"))).isTrue();
            assertThat(initializeCalls.get()).isPositive();
            assertThat(listCalls.get()).isPositive();
            soulEquals(instructions(fixture, 0), "synthetic custom identity that bootstrap must ignore");
            int initialized = initializeCalls.get();
            int listed = listCalls.get();

            for (String agent : List.of("agent-a", "missing-agent")) {
                String thread = "bootstrap-" + agent;
                http.thread(thread);
                http.completed(http.run(thread, agent, true));
                int request = fixture.requests.size() - 1;
                String instructions = instructions(fixture, request);
                assertThat(instructions.contains("You are " + LeadPromptInputs.DEFAULT_AGENT_NAME + ",")).isTrue();
                assertThat(instructions.contains("<soul>\n")).isFalse();
                assertThat(instructions.contains("<self_update>")).isFalse();
                assertThat(instructions.contains("<skill_system>\n")).isFalse();
                Set<String> expected = new java.util.HashSet<>(BASE_TOOLS);
                expected.add("setup_agent");
                assertThat(names(fixture, request)).containsExactlyInAnyOrderElementsOf(expected);
                assertThat(initializeCalls.get()).as("bootstrap does not connect MCP").isEqualTo(initialized);
                assertThat(listCalls.get()).isEqualTo(listed);
                java.nio.file.Files.writeString(http.evidence.resolve(thread + ".provider-summary.json"),
                        JSON.writeValueAsString(java.util.Map.of("instructions_chars", instructions.length(),
                                "instructions_sha256", hash(instructions), "tools", names(fixture, request),
                                "mcp_initialize_count", initializeCalls.get(), "mcp_list_count", listCalls.get())));
            }
        } finally { mcp.stop(0); }
    }

    private static HttpServer localMcp(AtomicInteger initializeCalls, AtomicInteger listCalls) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            var request = JSON.readTree(exchange.getRequestBody());
            if (!request.has("id")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
            response.set("id", request.get("id"));
            ObjectNode result = response.putObject("result");
            switch (request.path("method").asText()) {
                case "initialize" -> {
                    initializeCalls.incrementAndGet();
                    result.put("protocolVersion", "2025-06-18");
                    result.putObject("capabilities").putObject("tools");
                    result.putObject("serverInfo").put("name", "m3-probe").put("version", "1");
                }
                case "tools/list" -> {
                    listCalls.incrementAndGet();
                    ObjectNode tool = result.putArray("tools").addObject().put("name", "probe_tool")
                            .put("description", "controlled read-only probe");
                    tool.putObject("inputSchema").put("type", "object").putObject("properties");
                }
                default -> {
                    response.remove("result");
                    response.putObject("error").put("code", -32601).put("message", "method not supported");
                }
            }
            byte[] bytes = JSON.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
            exchange.close();
        });
        return server;
    }
}

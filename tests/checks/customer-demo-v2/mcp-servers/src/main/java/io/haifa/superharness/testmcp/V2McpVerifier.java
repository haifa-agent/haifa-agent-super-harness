package io.haifa.superharness.testmcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifies the V2 local test MCP servers through the official MCP Java SDK
 * client over a real loopback Streamable HTTP transport. Emits per-case JSON.
 */
public final class V2McpVerifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final McpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapper(new ObjectMapper());

    private final ArrayNode cases = MAPPER.createArrayNode();
    private boolean anyFailure;

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        String reportingUrl = opt.getOrDefault("--reporting-url", "http://127.0.0.1:20628/mcp");
        String regionUrl = opt.getOrDefault("--region-url", "http://127.0.0.1:20629/mcp");
        String out = opt.get("--out");
        long regionPid = Long.parseLong(opt.getOrDefault("--region-pid", "-1"));

        V2McpVerifier verifier = new V2McpVerifier();
        verifier.run(reportingUrl, regionUrl, regionPid);

        String json = verifier.toJson();
        System.out.println(json);
        if (out != null) {
            Path outPath = Path.of(out);
            Files.createDirectories(outPath.toAbsolutePath().getParent());
            Files.writeString(outPath, json, StandardCharsets.UTF_8);
        }
        System.exit(verifier.anyFailure ? 1 : 0);
    }

    private void run(String reportingUrl, String regionUrl, long regionPid) {
        McpSyncClient reporting = client(reportingUrl, Duration.ofSeconds(8));
        McpSyncClient region = client(regionUrl, Duration.ofSeconds(8));
        boolean regionAlive = true;
        try {
            reporting.initialize();
            region.initialize();
            pass("initialize-both", "both servers completed initialize over real HTTP");

            Map<String, List<Tool>> toolsByServer = new LinkedHashMap<>();
            toolsByServer.put("v2-reporting", reporting.listTools().tools());
            toolsByServer.put("v2-region", region.listTools().tools());
            boolean twoEach = toolsByServer.get("v2-reporting").size() == 2
                    && toolsByServer.get("v2-region").size() == 2;
            boolean allReadOnly = toolsByServer.values().stream().flatMap(List::stream)
                    .allMatch(t -> t.annotations() != null && Boolean.TRUE.equals(t.annotations().readOnlyHint()));
            if (twoEach && allReadOnly) {
                pass("tools-list-2-each", "each server lists 2 read-only tools: "
                        + names(toolsByServer.get("v2-reporting")) + " / " + names(toolsByServer.get("v2-region")));
            } else {
                fail("tools-list-2-each", "unexpected tool lists or annotations: " + toolsByServer);
            }

            // case 2: reporting policy
            CallToolResult policy = reporting.callTool(call("get_reporting_policy", Map.of()));
            String policyText = firstText(policy);
            var policyJson = MAPPER.readTree(policyText);
            boolean policyOk = policyJson.path("policy_id").asText().equals("v2-pilot-reporting-001")
                    && policyJson.path("high_amount_threshold_cny").asInt() == 1500
                    && policyJson.path("comparison").asText().equals(">=")
                    && policyJson.path("server_id").asText().equals("v2-reporting")
                    && policyJson.path("source_file_sha256").asText()
                    .equals("563e216fc77f87d968b3c34994746fa1f3368380ede30c97e03782c3895a9e9a");
            if (policyOk) {
                pass("reporting-policy", "policy_id/threshold/comparison/server_id/source_file_sha256 verified");
            } else {
                fail("reporting-policy", "unexpected policy payload: " + policyText);
            }

            // case 3: region label East + unknown region tool error
            CallToolResult east = region.callTool(call("get_region_label", Map.of("region", "East")));
            CallToolResult unknown = region.callTool(call("get_region_label", Map.of("region", "North")));
            boolean eastOk = !Boolean.TRUE.equals(east.isError()) && "华东".equals(firstText(east));
            boolean unknownOk = Boolean.TRUE.equals(unknown.isError());
            if (eastOk && unknownOk) {
                pass("region-label", "East -> 华东; unknown region -> isError=true");
            } else {
                fail("region-label", "east=" + firstText(east) + " isError=" + east.isError()
                        + "; unknown isError=" + unknown.isError());
            }

            // case 4: cross-server same-named get_status resolves to its own server
            CallToolResult reportingStatus = reporting.callTool(call("get_status", Map.of()));
            CallToolResult regionStatus = region.callTool(call("get_status", Map.of()));
            boolean statusOk = MAPPER.readTree(firstText(reportingStatus)).path("server_id").asText()
                    .equals("v2-reporting")
                    && MAPPER.readTree(firstText(regionStatus)).path("server_id").asText().equals("v2-region");
            if (statusOk) {
                pass("cross-server-get-status", "both get_status tools carry their own server_id");
            } else {
                fail("cross-server-get-status", "reporting=" + firstText(reportingStatus)
                        + " region=" + firstText(regionStatus));
            }

            // case 5: independent failure isolation (region stopped by pid)
            if (regionPid > 0) {
                boolean killed = ProcessHandle.of(regionPid)
                        .map(ph -> {
                            ph.destroy();
                            return true;
                        }).orElse(false);
                waitForPortClosed(regionUrl, Duration.ofSeconds(10));
                boolean reportingStillUp;
                try {
                    CallToolResult after = reporting.callTool(call("get_reporting_policy", Map.of()));
                    reportingStillUp = !Boolean.TRUE.equals(after.isError());
                } catch (Exception e) {
                    reportingStillUp = false;
                }
                String regionError;
                try {
                    region.callTool(call("get_status", Map.of()));
                    regionError = null;
                } catch (Exception e) {
                    regionError = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                if (killed && reportingStillUp && regionError != null) {
                    regionAlive = false;
                    pass("independent-failure-isolation",
                            "region stopped; reporting still callable; region call failed bounded: " + regionError);
                } else {
                    fail("independent-failure-isolation", "killed=" + killed + " reportingStillUp="
                            + reportingStillUp + " regionError=" + regionError);
                }
            } else {
                record("independent-failure-isolation", "NOT_RUN",
                        "no --region-pid supplied; isolation kill step skipped");
            }
        } catch (Exception e) {
            anyFailure = true;
            ObjectNode c = base("unexpected-run-failure", "FAIL");
            c.put("detail", e.getClass().getName() + ": " + e.getMessage());
            cases.add(c);
        } finally {
            closeQuietly(reporting);
            if (regionAlive) {
                closeQuietly(region);
            }
        }
    }

    private static McpSyncClient client(String url, Duration timeout) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(url)
                .jsonMapper(JSON_MAPPER)
                .build();
        return McpClient.sync(transport)
                .requestTimeout(timeout)
                .initializationTimeout(timeout)
                .build();
    }

    private static CallToolRequest call(String name, Map<String, Object> arguments) {
        try {
            String json = (arguments == null || arguments.isEmpty())
                    ? "{}"
                    : MAPPER.writeValueAsString(arguments);
            return new CallToolRequest(JSON_MAPPER, name, json);
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot build tool call for " + name, e);
        }
    }


    private static String firstText(CallToolResult result) {
        List<Content> content = result.content();
        if (content == null || content.isEmpty()) {
            return "";
        }
        Content first = content.get(0);
        return first instanceof TextContent text ? text.text() : String.valueOf(first);
    }

    private static List<String> names(List<Tool> tools) {
        return tools.stream().map(Tool::name).toList();
    }

    private static void waitForPortClosed(String url, Duration timeout) {
        String hostPort = url.replaceFirst("^https?://", "").replaceFirst("/.*$", "");
        int colon = hostPort.lastIndexOf(':');
        String host = hostPort.substring(0, colon);
        int port = Integer.parseInt(hostPort.substring(colon + 1));
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(host, port), 200);
            } catch (Exception e) {
                return;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void closeQuietly(McpSyncClient client) {
        try {
            client.closeGracefully();
        } catch (Exception ignored) {
            // best effort
        }
    }

    private void pass(String id, String detail) {
        record(id, "PASS", detail);
    }

    private void fail(String id, String detail) {
        anyFailure = true;
        record(id, "FAIL", detail);
    }

    private void record(String id, String status, String detail) {
        ObjectNode c = base(id, status);
        c.put("detail", detail);
        cases.add(c);
    }

    private static ObjectNode base(String id, String status) {
        ObjectNode c = MAPPER.createObjectNode();
        c.put("case", id);
        c.put("status", status);
        return c;
    }

    private String toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", "haifa.customer-demo-v2.w10-mcp-verification/v1");
        root.put("overall", anyFailure ? "FAIL" : "PASS");
        root.set("cases", cases);
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return "{\"overall\":\"FAIL\",\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opt = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                opt.put(args[i], args[++i]);
            }
        }
        return opt;
    }
}

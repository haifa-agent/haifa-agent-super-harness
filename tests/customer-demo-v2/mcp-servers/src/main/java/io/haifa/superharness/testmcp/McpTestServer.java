package io.haifa.superharness.testmcp;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Minimal standalone Streamable HTTP MCP fixture server (protocol 2025-06-18).
 *
 * <p>The official MCP Java SDK server transport requires a servlet container
 * (jakarta.servlet). Jetty/Tomcat embed are not resolvable offline in this
 * repository, so this fixture implements the minimal required subset directly
 * on the JDK HttpServer and is verified by the official SDK client.</p>
 */
public final class McpTestServer {

    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpTestServer() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        String serverId = require(opt, "--server-id");
        int port = Integer.parseInt(require(opt, "--port"));
        long delayMs = Long.parseLong(opt.getOrDefault("--delay-ms", "0"));

        Fixture fixture;
        if ("v2-reporting".equals(serverId)) {
            fixture = ReportingFixture.load(
                    Path.of(require(opt, "--policy-file")),
                    require(opt, "--policy-sha256"));
        } else if ("v2-region".equals(serverId)) {
            fixture = new RegionFixture();
        } else {
            throw new IllegalArgumentException("unknown --server-id: " + serverId);
        }

        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        server.createContext("/mcp", new McpHandler(fixture, delayMs));
        server.createContext("/healthz", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("MCP_SERVER_READY server_id=" + serverId + " port=" + port
                + " url=http://127.0.0.1:" + port + "/mcp");
        System.out.flush();
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

    private static String require(Map<String, String> opt, String key) {
        String value = opt.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required argument " + key);
        }
        return value;
    }

    // ----------------------------------------------------------------- tools

    interface Fixture {
        String serverId();

        List<ObjectNode> tools();

        ToolOutcome call(String name, Map<String, Object> arguments) throws Exception;
    }

    record ToolOutcome(String text, boolean isError) {
    }

    static final class ReportingFixture implements Fixture {
        private final JsonNode policy;
        private final String sha256;

        private ReportingFixture(JsonNode policy, String sha256) {
            this.policy = policy;
            this.sha256 = sha256;
        }

        static ReportingFixture load(Path file, String expectedSha256) throws Exception {
            byte[] bytes = Files.readAllBytes(file);
            String actual = sha256Hex(bytes);
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                System.err.println("REFUSING TO START: policy fixture SHA-256 mismatch for " + file
                        + " expected=" + expectedSha256 + " actual=" + actual);
                System.exit(2);
            }
            return new ReportingFixture(MAPPER.readTree(bytes), actual.toLowerCase());
        }

        @Override
        public String serverId() {
            return "v2-reporting";
        }

        @Override
        public List<ObjectNode> tools() {
            List<ObjectNode> tools = new ArrayList<>();
            tools.add(tool("get_reporting_policy",
                    "Return the frozen V2 pilot reporting policy fixture.",
                    emptyObjectSchema(), true));
            tools.add(tool("get_status",
                    "Report this MCP fixture server readiness.",
                    emptyObjectSchema(), true));
            return tools;
        }

        @Override
        public ToolOutcome call(String name, Map<String, Object> arguments) {
            if ("get_reporting_policy".equals(name)) {
                ObjectNode result = policy.deepCopy();
                result.put("server_id", serverId());
                result.put("source_file_sha256", sha256);
                return new ToolOutcome(result.toString(), false);
            }
            if ("get_status".equals(name)) {
                ObjectNode result = MAPPER.createObjectNode();
                result.put("server_id", serverId());
                result.put("state", "ready");
                result.put("transport", "streamable-http");
                return new ToolOutcome(result.toString(), false);
            }
            return new ToolOutcome("unknown tool: " + name, true);
        }
    }

    static final class RegionFixture implements Fixture {
        @Override
        public String serverId() {
            return "v2-region";
        }

        @Override
        public List<ObjectNode> tools() {
            List<ObjectNode> tools = new ArrayList<>();
            ObjectNode regionSchema = MAPPER.createObjectNode();
            regionSchema.put("type", "object");
            ObjectNode props = regionSchema.putObject("properties");
            props.putObject("region").put("type", "string")
                    .put("description", "Region code: East or West");
            regionSchema.putArray("required").add("region");
            regionSchema.put("additionalProperties", false);
            tools.add(tool("get_region_label",
                    "Map a region code to its Chinese label.", regionSchema, true));
            tools.add(tool("get_status",
                    "Report this MCP fixture server readiness.", emptyObjectSchema(), true));
            return tools;
        }

        @Override
        public ToolOutcome call(String name, Map<String, Object> arguments) {
            if ("get_region_label".equals(name)) {
                Object raw = arguments == null ? null : arguments.get("region");
                String region = raw == null ? null : String.valueOf(raw);
                if ("East".equals(region)) {
                    return new ToolOutcome("华东", false);
                }
                if ("West".equals(region)) {
                    return new ToolOutcome("华西", false);
                }
                return new ToolOutcome("unknown region: " + region, true);
            }
            if ("get_status".equals(name)) {
                ObjectNode result = MAPPER.createObjectNode();
                result.put("server_id", serverId());
                result.put("state", "ready");
                result.put("transport", "streamable-http");
                return new ToolOutcome(result.toString(), false);
            }
            return new ToolOutcome("unknown tool: " + name, true);
        }
    }

    private static ObjectNode emptyObjectSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        schema.putArray("required");
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode tool(String name, String description, ObjectNode inputSchema, boolean readOnly) {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        tool.set("inputSchema", inputSchema);
        tool.putObject("annotations").put("readOnlyHint", readOnly);
        return tool;
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // --------------------------------------------------------------- handler

    static final class McpHandler implements com.sun.net.httpserver.HttpHandler {
        private final Fixture fixture;
        private final long delayMs;
        private final String sessionId = UUID.randomUUID().toString();

        McpHandler(Fixture fixture, long delayMs) {
            this.fixture = fixture;
            this.delayMs = delayMs;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String method = exchange.getRequestMethod();
                if (!"POST".equals(method)) {
                    exchange.getResponseHeaders().set("Allow", "POST");
                    sendText(exchange, 405, "method not allowed");
                    return;
                }
                byte[] body = exchange.getRequestBody().readAllBytes();
                JsonNode root;
                try {
                    root = MAPPER.readTree(body);
                } catch (Exception e) {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    sendText(exchange, 400, "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":"
                            + "{\"code\":-32700,\"message\":\"parse error\"}}");
                    return;
                }

                if (root.isArray()) {
                    ArrayNode responses = MAPPER.createArrayNode();
                    for (JsonNode item : root) {
                        ObjectNode response = dispatch(item);
                        if (response != null) {
                            responses.add(response);
                        }
                    }
                    if (responses.isEmpty()) {
                        exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId);
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
            } catch (Exception e) {
                System.err.println("handler error: " + e);
                sendJson(exchange, 500, "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":"
                        + "{\"code\":-32603,\"message\":\"internal error\"}}");
            }
        }

        private ObjectNode dispatch(JsonNode request) throws Exception {
            JsonNode id = request.get("id");
            if (id == null || id.isNull()) {
                return null; // notification
            }
            String method = request.path("method").asText("");
            ObjectNode response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", id);

            switch (method) {
                case "initialize" -> response.set("result", initializeResult(request));
                case "ping" -> response.set("result", MAPPER.createObjectNode());
                case "tools/list" -> response.set("result", toolsListResult());
                case "tools/call" -> response.set("result", toolsCallResult(request));
                default -> response.set("error", errorNode(-32601, "Method not found: " + method));
            }
            return response;
        }

        private ObjectNode initializeResult(JsonNode request) {
            String requested = request.path("params").path("protocolVersion").asText(PROTOCOL_VERSION);
            String negotiated = switch (requested) {
                case "2024-11-05", "2025-03-26", "2025-06-18" -> requested;
                default -> PROTOCOL_VERSION;
            };
            ObjectNode result = MAPPER.createObjectNode();
            result.put("protocolVersion", negotiated);
            ObjectNode capabilities = result.putObject("capabilities");
            capabilities.putObject("tools").put("listChanged", false);
            ObjectNode serverInfo = result.putObject("serverInfo");
            serverInfo.put("name", fixture.serverId());
            serverInfo.put("version", "1.0.0");
            result.put("instructions", "V2 L2 deterministic local test MCP server (" + fixture.serverId() + ")");
            return result;
        }

        private ObjectNode toolsListResult() {
            ObjectNode result = MAPPER.createObjectNode();
            ArrayNode tools = result.putArray("tools");
            for (ObjectNode tool : fixture.tools()) {
                tools.add(tool);
            }
            return result;
        }

        private ObjectNode toolsCallResult(JsonNode request) throws Exception {
            String name = request.path("params").path("name").asText("");
            JsonNode argsNode = request.path("params").get("arguments");
            Map<String, Object> arguments = new LinkedHashMap<>();
            if (argsNode != null && argsNode.isObject()) {
                for (Map.Entry<String, JsonNode> e : argsNode.properties()) {
                    arguments.put(e.getKey(), MAPPER.convertValue(e.getValue(), Object.class));
                }
            }
            if ("get_status".equals(name) && delayMs > 0) {
                Thread.sleep(delayMs);
            }
            ToolOutcome outcome = fixture.call(name, arguments);
            ObjectNode result = MAPPER.createObjectNode();
            ArrayNode content = result.putArray("content");
            content.addObject().put("type", "text").put("text", outcome.text());
            result.put("isError", outcome.isError());
            return result;
        }

        private ObjectNode errorNode(int code, String message) {
            ObjectNode error = MAPPER.createObjectNode();
            error.put("code", code);
            error.put("message", message);
            return error;
        }

        private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        private void sendText(HttpExchange exchange, int status, String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}

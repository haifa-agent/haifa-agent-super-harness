package io.haifa.agent.superharness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.starter.McpServerSpec;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Maps the trusted MCP configuration onto the public Haifa Agent SDK MCP declarations.
 *
 * <p>SH only projects the frozen {@code mcpServers} fields the SDK entry point needs
 * (server id, url, header/credential references, timeouts, enable state and the tool
 * allowlist). Connection isolation, real discovery, tool naming, invocation, error
 * handling and resource release are owned by the SDK through
 * {@code McpToolPlatforms.applyTo(...)}.</p>
 */
final class McpRunTools {
    static final String TENANT_ID = "super-harness";
    static final String PRINCIPAL_ID = "default";
    static final String PRINCIPAL_TYPE = "user";
    /**
     * The V2 local test servers (and the frozen Streamable HTTP contract used by this
     * slice) negotiate MCP protocol 2025-06-18; the SDK default targets a newer version.
     */
    static final String PROTOCOL_VERSION = "2025-06-18";

    private McpRunTools() {}

    record Selection(Optional<Set<String>> ids) {
        /** Frozen tri-state: {@code null}=all enabled, {@code []}=none, list=only those. */
        boolean admits(String serverId) {
            return ids.map(allowed -> allowed.contains(serverId)).orElse(true);
        }

        static Selection all() {
            return new Selection(Optional.empty());
        }

        static Selection fromJson(JsonNode value) {
            if (value == null || value.isNull()) return all();
            if (!value.isArray()) {
                throw new IllegalArgumentException("mcp_plugins must be an array or null");
            }
            Set<String> ids = new LinkedHashSet<>();
            value.forEach(item -> {
                if (!item.isTextual() || item.asText().isBlank()) {
                    throw new IllegalArgumentException("mcp_plugins entries must be non-empty strings");
                }
                ids.add(item.asText());
            });
            return new Selection(Optional.of(ids));
        }
    }

    static List<McpServerSpec> specs(
            Map<String, ObjectNode> servers, Selection selection, List<String> diagnostics) {
        List<McpServerSpec> specs = new ArrayList<>();
        for (Map.Entry<String, ObjectNode> entry : servers.entrySet()) {
            String id = entry.getKey();
            ObjectNode server = entry.getValue();
            if (!selection.admits(id)) continue;
            if (!server.path("enabled").asBoolean(true)) continue;
            String type = server.path("type").asText("").toLowerCase(Locale.ROOT);
            if (!Set.of("http", "streamable-http").contains(type)) {
                diagnostics.add(id + ": transport '" + type + "' is outside this slice");
                continue;
            }
            if (!server.path("tool_name_prefix").asBoolean(true)) {
                diagnostics.add(id + ": tool_name_prefix=false is outside this slice");
                continue;
            }
            Set<String> allowed = new LinkedHashSet<>();
            JsonNode tools = server.path("tools");
            if (tools.isObject()) {
                tools.properties().forEach(tool -> allowed.add(tool.getKey()));
            }
            if (allowed.isEmpty()) {
                diagnostics.add(id + ": no tool allowlist configured");
                continue;
            }
            try {
                URI endpoint = URI.create(server.path("url").asText());
                McpServerSpec spec = McpServerSpec.streamableHttp(id, endpoint)
                        .allowTools(allowed)
                        .protocolVersion(PROTOCOL_VERSION);
                if (isLoopbackHttp(endpoint)) {
                    spec = spec.allowLoopbackHttp();
                }
                if (server.path("read_only").asBoolean(false)) {
                    spec = spec.readOnly();
                }
                Duration connectTimeout = timeout(server, "session_init_timeout");
                if (connectTimeout != null) {
                    spec = spec.connectTimeout(connectTimeout);
                }
                Duration requestTimeout = timeout(server, "tool_call_timeout");
                if (requestTimeout != null) {
                    spec = spec.requestTimeout(requestTimeout);
                }
                spec = applyHeaders(spec, server);
                // Per-server isolation: one unreachable or uncredentialed server must
                // degrade to a diagnostic instead of removing its siblings' tools.
                specs.add(spec.optional());
            } catch (RuntimeException invalid) {
                // A projection the SDK rejects must not abort sibling servers or be faked.
                diagnostics.add(id + ": " + invalid.getMessage());
            }
        }
        return specs;
    }

    /**
     * Maps configured headers to the SDK declaration. A {@code $NAME} value is a
     * credential reference resolved from the process environment at connect time; a
     * plain value is a non-secret static header. No secret is ever read into SH.
     */
    private static McpServerSpec applyHeaders(McpServerSpec spec, ObjectNode server) {
        JsonNode headers = server.path("headers");
        if (!headers.isObject()) {
            return spec;
        }
        McpServerSpec result = spec;
        for (Map.Entry<String, JsonNode> header : headers.properties()) {
            String value = header.getValue().asText("");
            if (value.startsWith("$") && value.length() > 1) {
                result = result.header(header.getKey(), value.substring(1));
            } else if (!value.isEmpty()) {
                result = result.header(header.getKey(), () -> value);
            }
        }
        return result;
    }

    static boolean isLoopbackHttp(URI endpoint) {
        if (!"http".equalsIgnoreCase(endpoint.getScheme())) return false;
        String host = endpoint.getHost();
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("localhost")
                || normalized.equals("::1")
                || normalized.equals("[::1]")
                || normalized.startsWith("127.");
    }

    private static Duration timeout(ObjectNode server, String field) {
        JsonNode value = server.get(field);
        if (value == null || value.isNull() || !value.isNumber()) return null;
        long millis = Math.max(1L, Math.round(value.asDouble() * 1000.0));
        return Duration.ofMillis(millis);
    }
}

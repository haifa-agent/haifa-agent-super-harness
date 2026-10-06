package io.haifa.agent.superharness.server.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.workspace.AtomicMoves;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Thin, config-driven projection of the frozen DeerFlow {@code mcpServers} map.
 *
 * <p>Holds only the fields the frozen UI and the Haifa SDK connection mapping need
 * (enable state, transport, url, header/credential references, per-server timeouts
 * and the tool allowlist). It deliberately carries no secret value: header entries
 * are treated as credential references and are masked on read. No authoritative MCP
 * table, pool or executor is introduced here.</p>
 */
public final class McpConfigStore {
    /** Frozen SDK connection names must be a lowercase stable identifier. */
    private static final Pattern SERVER_ID = Pattern.compile("^[a-z][a-z0-9-]{0,62}$");
    private static final String MASKED = "***";
    private static final Set<String> HTTP_TRANSPORTS = Set.of("http", "streamable-http");

    private final ObjectMapper json;
    private final Path file;
    private final Object lock = new Object();

    public McpConfigStore(ObjectMapper json, Path dataDirectory) {
        this.json = json;
        this.file = dataDirectory.toAbsolutePath().normalize().resolve("extensions_config.json");
    }

    /** Normalized, unmasked servers keyed by id. Callers must not mutate the nodes. */
    public Map<String, ObjectNode> servers() {
        synchronized (lock) {
            ObjectNode root = read();
            Map<String, ObjectNode> servers = new LinkedHashMap<>();
            JsonNode map = root.path("mcp_servers");
            if (map.isObject()) {
                map.properties().forEach(entry -> servers.put(entry.getKey(), (ObjectNode) entry.getValue()));
            }
            return servers;
        }
    }

    /** REST projection: {@code {"mcp_servers": {id: maskedServer}}}. */
    public ObjectNode maskedSnapshot() {
        ObjectNode response = json.createObjectNode();
        ObjectNode servers = response.putObject("mcp_servers");
        servers().forEach((id, server) -> servers.set(id, mask(server)));
        return response;
    }

    /** PUT /api/mcp/config: complete replacement. */
    public ObjectNode replaceAll(JsonNode mcpServers) {
        Map<String, ObjectNode> normalized = normalizeMap(mcpServers);
        synchronized (lock) {
            write(normalized);
        }
        return maskedSnapshot();
    }

    /** POST /api/mcp/config/servers: add servers, failing on any duplicate id. */
    public ObjectNode addServers(JsonNode mcpServers) {
        Map<String, ObjectNode> normalized = normalizeMap(mcpServers);
        if (normalized.isEmpty()) {
            throw new McpStoreException(422, "mcp_servers must not be empty");
        }
        synchronized (lock) {
            Map<String, ObjectNode> current = servers();
            for (String id : normalized.keySet()) {
                if (current.containsKey(id)) {
                    throw new McpStoreException(409, "MCP server '" + id + "' already exists");
                }
            }
            current.putAll(normalized);
            write(current);
        }
        return maskedSnapshot();
    }

    /** PUT /api/mcp/config/server: complete replacement of one existing server. */
    public ObjectNode replaceServer(String name, JsonNode server) {
        Map<String, ObjectNode> normalized = normalizeMap(singleServerMap(name, server));
        synchronized (lock) {
            Map<String, ObjectNode> current = servers();
            if (!current.containsKey(name)) {
                throw new McpStoreException(404, "MCP server '" + name + "' not found");
            }
            current.put(name, normalized.get(name));
            write(current);
        }
        return maskedSnapshot();
    }

    /** PATCH /api/mcp/config: flip one server's enable state. */
    public ObjectNode setEnabled(String name, boolean enabled) {
        synchronized (lock) {
            Map<String, ObjectNode> current = servers();
            ObjectNode server = current.get(name);
            if (server == null) {
                throw new McpStoreException(404, "MCP server '" + name + "' not found");
            }
            server.put("enabled", enabled);
            write(current);
        }
        return maskedSnapshot();
    }

    /** DELETE /api/mcp/config/servers/{name}. */
    public ObjectNode deleteServer(String name) {
        synchronized (lock) {
            Map<String, ObjectNode> current = servers();
            if (current.remove(name) == null) {
                throw new McpStoreException(404, "MCP server '" + name + "' not found");
            }
            write(current);
        }
        return maskedSnapshot();
    }

    private JsonNode singleServerMap(String name, JsonNode server) {
        ObjectNode wrapper = json.createObjectNode();
        wrapper.set(name, server);
        return wrapper;
    }

    private Map<String, ObjectNode> normalizeMap(JsonNode mcpServers) {
        if (!(mcpServers instanceof ObjectNode object)) {
            throw new McpStoreException(422, "mcp_servers must be an object");
        }
        Map<String, ObjectNode> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : object.properties()) {
            normalized.put(entry.getKey(), normalizeServer(entry.getKey(), entry.getValue()));
        }
        return normalized;
    }

    private ObjectNode normalizeServer(String id, JsonNode raw) {
        if (!SERVER_ID.matcher(id).matches()) {
            throw new McpStoreException(
                    422, "MCP server id must match [a-z][a-z0-9-]{0,62}");
        }
        if (!(raw instanceof ObjectNode object)) {
            throw new McpStoreException(422, "MCP server '" + id + "' must be an object");
        }
        ObjectNode server = object.deepCopy();
        if (!server.has("transport") && !server.has("type")) {
            server.put("type", "stdio");
        }
        if (!server.has("type") && server.has("transport")) {
            server.put("type", server.path("transport").asText());
        }
        String type = server.path("type").asText("").trim().toLowerCase(Locale.ROOT);
        server.put("type", type);
        requireBoolean(server, id, "enabled", true);
        requireBoolean(server, id, "tool_name_prefix", true);
        requireBoolean(server, id, "read_only", false);
        requireObjectIfPresent(server, id, "tools");
        requireObjectIfPresent(server, id, "headers");
        requirePositiveOrNull(server, id, "session_init_timeout");
        requirePositiveOrNull(server, id, "tool_call_timeout");
        if (HTTP_TRANSPORTS.contains(type)) {
            String url = server.path("url").asText("").trim();
            if (url.isEmpty()) {
                throw new McpStoreException(422, "MCP server '" + id + "' with transport '" + type + "' requires url");
            }
            URI endpoint;
            try {
                endpoint = URI.create(url);
            } catch (IllegalArgumentException invalid) {
                throw new McpStoreException(422, "MCP server '" + id + "' url is not a valid URI");
            }
            String scheme = endpoint.getScheme() == null ? "" : endpoint.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https"))
                    || endpoint.getHost() == null
                    || endpoint.getUserInfo() != null
                    || endpoint.getQuery() != null
                    || endpoint.getFragment() != null) {
                throw new McpStoreException(422, "MCP server '" + id + "' url must be an absolute HTTP(S) URL");
            }
            server.put("url", url);
        }
        validateHeaders(id, server);
        validateTools(id, server);
        return server;
    }

    private void validateHeaders(String id, ObjectNode server) {
        JsonNode headers = server.get("headers");
        if (headers == null || headers.isNull()) return;
        Set<String> lowered = new java.util.HashSet<>();
        for (Map.Entry<String, JsonNode> header : headers.properties()) {
            if (!header.getValue().isTextual()) {
                throw new McpStoreException(422, "MCP server '" + id + "' header values must be strings");
            }
            if (!lowered.add(header.getKey().toLowerCase(Locale.ROOT))) {
                throw new McpStoreException(
                        422, "MCP server '" + id + "' maps the same HTTP header under two spellings");
            }
        }
    }

    private void validateTools(String id, ObjectNode server) {
        JsonNode tools = server.get("tools");
        if (tools == null || tools.isNull()) return;
        for (Map.Entry<String, JsonNode> tool : tools.properties()) {
            if (tool.getKey().isBlank()) {
                throw new McpStoreException(422, "MCP server '" + id + "' tool names must not be blank");
            }
            if (!tool.getValue().isObject()) {
                throw new McpStoreException(422, "MCP server '" + id + "' tool overrides must be objects");
            }
        }
    }

    private void requireBoolean(ObjectNode server, String id, String field, boolean fallback) {
        JsonNode value = server.get(field);
        if (value == null || value.isNull()) {
            server.put(field, fallback);
            return;
        }
        if (!value.isBoolean()) {
            throw new McpStoreException(422, "MCP server '" + id + "' field '" + field + "' must be boolean");
        }
    }

    private void requireObjectIfPresent(ObjectNode server, String id, String field) {
        JsonNode value = server.get(field);
        if (value != null && !value.isNull() && !value.isObject()) {
            throw new McpStoreException(422, "MCP server '" + id + "' field '" + field + "' must be an object");
        }
    }

    private void requirePositiveOrNull(ObjectNode server, String id, String field) {
        JsonNode value = server.get(field);
        if (value == null || value.isNull()) return;
        if (!value.isNumber() || value.asDouble() <= 0) {
            throw new McpStoreException(
                    422, "MCP server '" + id + "' field '" + field + "' must be a positive number or null");
        }
    }

    /** Deep copy with credential-bearing values replaced by the frozen mask. */
    static ObjectNode mask(ObjectNode server) {
        ObjectNode masked = server.deepCopy();
        JsonNode headers = masked.get("headers");
        if (headers instanceof ObjectNode object) {
            object.properties().forEach(entry -> object.put(entry.getKey(), MASKED));
        }
        JsonNode env = masked.get("env");
        if (env instanceof ObjectNode object) {
            object.properties().forEach(entry -> object.put(entry.getKey(), MASKED));
        }
        JsonNode oauth = masked.get("oauth");
        if (oauth instanceof ObjectNode object) {
            object.putNull("client_secret");
            object.putNull("refresh_token");
        }
        JsonNode userAuth = masked.get("user_auth");
        if (userAuth instanceof ObjectNode object && object.get("users") instanceof ObjectNode users) {
            users.properties().forEach(entry -> users.put(entry.getKey(), MASKED));
        }
        return masked;
    }

    private ObjectNode read() {
        if (!Files.exists(file)) {
            ObjectNode empty = json.createObjectNode();
            empty.set("schema_version", json.createObjectNode().numberNode(1));
            empty.set("mcp_servers", json.createObjectNode());
            return empty;
        }
        try {
            JsonNode root = json.readTree(file.toFile());
            if (!(root instanceof ObjectNode object)) {
                throw new IllegalStateException("MCP configuration must be a JSON object");
            }
            Map<String, ObjectNode> normalized = new LinkedHashMap<>();
            JsonNode map = object.path("mcp_servers");
            if (map.isObject()) {
                for (Map.Entry<String, JsonNode> entry : map.properties()) {
                    normalized.put(entry.getKey(), normalizeServer(entry.getKey(), entry.getValue()));
                }
            }
            ObjectNode rebuilt = json.createObjectNode();
            rebuilt.set("schema_version", json.createObjectNode().numberNode(1));
            ObjectNode servers = rebuilt.putObject("mcp_servers");
            normalized.forEach(servers::set);
            return rebuilt;
        } catch (IOException exception) {
            throw new IllegalStateException("MCP configuration is unavailable", exception);
        }
    }

    private void write(Map<String, ObjectNode> servers) {
        ObjectNode root = json.createObjectNode();
        root.set("schema_version", json.createObjectNode().numberNode(1));
        ObjectNode map = root.putObject("mcp_servers");
        servers.forEach(map::set);
        Path temporary = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), root);
            AtomicMoves.replace(temporary, file);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Preserve the primary storage failure.
            }
            throw new IllegalStateException("MCP configuration is unavailable", exception);
        }
    }

    /** A request that cannot be satisfied as written; carries the REST status. */
    public static final class McpStoreException extends RuntimeException {
        private final int status;

        McpStoreException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}

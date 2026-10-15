package io.haifa.agent.superharness.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.haifa.agent.superharness.server.auth.AccessOwner;
import io.haifa.agent.superharness.server.auth.LocalAuthService;
import io.haifa.agent.superharness.server.channels.feishu.FeishuChannelWorker;
import io.haifa.agent.superharness.server.channels.feishu.FeishuOpenDomain;
import io.haifa.agent.superharness.server.channels.feishu.FeishuTransport;
import io.haifa.agent.superharness.server.config.ChannelConnectionStore;
import io.haifa.agent.superharness.server.config.ChannelRuntimeConfigStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Frozen DeerFlow IM channel connections REST projection (Feishu-only L1b).
 *
 * <p>Wire shape mirrors {@code deer-flow/.../channel_connections.py} and
 * {@code frontend/src/core/channels/api.ts}. Runtime credentials and bindings live under the
 * Gateway data-root; POST/DELETE runtime-config starts/stops the Feishu WS worker.</p>
 */
final class DeerFlowChannelsApi implements HttpHandler {
    private static final System.Logger LOG = System.getLogger(DeerFlowChannelsApi.class.getName());
    static final String ADMIN_REQUIRED = "Admin privileges required to manage channel runtime credentials.";
    private static final String MASKED = "********";
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Pattern CONNECTION_PATH =
            Pattern.compile("^/api/channels/connections/([^/]+)$");
    private static final Pattern PROVIDER_CONNECT =
            Pattern.compile("^/api/channels/([^/]+)/connect$");
    private static final Pattern PROVIDER_RUNTIME =
            Pattern.compile("^/api/channels/([^/]+)/runtime-config$");

    private static final Map<String, ProviderMeta> PROVIDERS = Map.of(
            "feishu",
            new ProviderMeta(
                    "Feishu",
                    "binding_code",
                    List.of(
                            new CredentialField("app_id", "App ID", "text", true),
                            new CredentialField("app_secret", "App secret", "password", true),
                            new CredentialField("domain", "Open Platform domain", "text", false)),
                    List.of("app_id", "app_secret")));

    private final ObjectMapper json;
    private final ChannelRuntimeConfigStore runtimeStore;
    private final ChannelConnectionStore connectionStore;
    private final FeishuChannelWorker feishuWorker;
    private final boolean channelConnectionsEnabled;
    private final boolean feishuEnabled;

    DeerFlowChannelsApi(
            ObjectMapper json,
            ChannelRuntimeConfigStore runtimeStore,
            ChannelConnectionStore connectionStore) {
        this(json, runtimeStore, connectionStore, null, true, true);
    }

    DeerFlowChannelsApi(
            ObjectMapper json,
            ChannelRuntimeConfigStore runtimeStore,
            ChannelConnectionStore connectionStore,
            FeishuChannelWorker feishuWorker) {
        this(json, runtimeStore, connectionStore, feishuWorker, true, true);
    }

    DeerFlowChannelsApi(
            ObjectMapper json,
            ChannelRuntimeConfigStore runtimeStore,
            ChannelConnectionStore connectionStore,
            boolean channelConnectionsEnabled,
            boolean feishuEnabled) {
        this(json, runtimeStore, connectionStore, null, channelConnectionsEnabled, feishuEnabled);
    }

    DeerFlowChannelsApi(
            ObjectMapper json,
            ChannelRuntimeConfigStore runtimeStore,
            ChannelConnectionStore connectionStore,
            FeishuChannelWorker feishuWorker,
            boolean channelConnectionsEnabled,
            boolean feishuEnabled) {
        this.json = json;
        this.runtimeStore = runtimeStore;
        this.connectionStore = connectionStore;
        this.feishuWorker = feishuWorker;
        this.channelConnectionsEnabled = channelConnectionsEnabled;
        this.feishuEnabled = feishuEnabled;
    }

    /** Package/test-visible Feishu bind seam. */
    ChannelConnectionStore connections() {
        return connectionStore;
    }

    /** Package/test-visible worker (may be null when constructed without one). */
    FeishuChannelWorker feishuWorker() {
        return feishuWorker;
    }

    void overrideFeishuTransportFactory(FeishuTransport.Factory factory) {
        if (feishuWorker != null) {
            feishuWorker.overrideTransportFactory(factory);
        }
    }

    /**
     * After Gateway restart, reopen the Feishu WebSocket when persisted runtime credentials are
     * still configured. Does not start a provider that was explicitly disconnected. Failures are
     * logged and leave HTTP up; the admin can re-save runtime-config to retry.
     */
    void restorePersistedFeishuWorker() {
        if (feishuWorker == null || !channelConnectionsEnabled || !feishuEnabled) {
            return;
        }
        ProviderMeta meta = PROVIDERS.get("feishu");
        ObjectNode runtime = effectiveRuntime("feishu");
        if (!runtimeConfigured("feishu", meta, runtime)) {
            return;
        }
        try {
            feishuWorker.startOrRestart(
                    runtime.path("app_id").asText(""),
                    runtime.path("app_secret").asText(""),
                    runtime.path("domain").asText(FeishuOpenDomain.DEFAULT));
        } catch (Exception failure) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "Persisted Feishu channel credentials are present but the worker failed to start",
                    failure);
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!isLoopbackPeer(exchange) && !LocalAuthService.sessionAuthActive()) {
                send(exchange, 403, Map.of("detail", "Local profile accepts loopback clients only"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("/api/channels/providers".equals(path) && "GET".equals(method)) {
                send(exchange, 200, providersResponse());
                return;
            }
            if ("/api/channels/connections".equals(path) && "GET".equals(method)) {
                send(exchange, 200, connectionsResponse());
                return;
            }
            Matcher connection = CONNECTION_PATH.matcher(path);
            if (connection.matches() && "DELETE".equals(method)) {
                disconnectConnection(exchange, connection.group(1));
                return;
            }
            Matcher connect = PROVIDER_CONNECT.matcher(path);
            if (connect.matches() && "POST".equals(method)) {
                connectProvider(exchange, connect.group(1));
                return;
            }
            Matcher runtime = PROVIDER_RUNTIME.matcher(path);
            if (runtime.matches()) {
                String provider = runtime.group(1);
                if ("POST".equals(method)) {
                    configureRuntime(exchange, provider);
                    return;
                }
                if ("DELETE".equals(method)) {
                    clearRuntime(exchange, provider);
                    return;
                }
                methodNotAllowed(exchange, "POST, DELETE");
                return;
            }
            send(exchange, 404, Map.of("detail", "Not Found"));
        } catch (ChannelsException invalid) {
            send(exchange, invalid.status, Map.of("detail", invalid.getMessage()));
        } catch (Exception exception) {
            send(exchange, 500, Map.of("detail", "Channel connection operation failed"));
        }
    }

    private ObjectNode providersResponse() {
        ObjectNode response = json.createObjectNode();
        response.put("enabled", channelConnectionsEnabled);
        ArrayNode providers = response.putArray("providers");
        if (!channelConnectionsEnabled) {
            return response;
        }
        List<ObjectNode> connections = connectionStore.listConnections(AccessOwner.current());
        ObjectNode newestByProvider = newestConnectionByProvider(connections);
        for (Map.Entry<String, ProviderMeta> entry : PROVIDERS.entrySet()) {
            if (!providerEnabled(entry.getKey())) {
                continue;
            }
            ObjectNode connection = (ObjectNode) newestByProvider.get(entry.getKey());
            providers.add(providerResponse(entry.getKey(), entry.getValue(), connection));
        }
        return response;
    }

    private ObjectNode connectionsResponse() {
        ObjectNode response = json.createObjectNode();
        ArrayNode connections = response.putArray("connections");
        if (!channelConnectionsEnabled) {
            return response;
        }
        for (ObjectNode row : connectionStore.listConnections(AccessOwner.current())) {
            ObjectNode wire = row.deepCopy();
            wire.remove("updated_at");
            connections.add(wire);
        }
        return response;
    }

    private void disconnectConnection(HttpExchange exchange, String connectionId) throws IOException {
        requireFeatureEnabled();
        boolean disconnected =
                connectionStore.disconnectConnection(connectionId, AccessOwner.current());
        if (!disconnected) {
            throw new ChannelsException(404, "Channel connection not found");
        }
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private void connectProvider(HttpExchange exchange, String provider) throws IOException {
        requireFeatureEnabled();
        ProviderMeta meta = requireProvider(provider);
        if (!providerEnabled(provider)) {
            throw new ChannelsException(400, "Channel provider is not enabled");
        }
        ObjectNode runtime = effectiveRuntime(provider);
        String unavailable = unavailableReason(provider, meta, runtime);
        if (unavailable != null) {
            throw new ChannelsException(400, unavailable);
        }
        if (!runtimeConfigured(provider, meta, runtime)) {
            throw new ChannelsException(400, "Channel provider is not configured");
        }
        String code = ChannelConnectionStore.newBindingCode();
        Instant expiresAt = Instant.now().plusSeconds(ChannelConnectionStore.STATE_TTL_SECONDS);
        boolean inserted = connectionStore.createOauthStateWithinCap(
                AccessOwner.current(),
                provider,
                code,
                expiresAt,
                ChannelConnectionStore.MAX_PENDING_CONNECT_CODES_PER_PROVIDER);
        if (!inserted) {
            throw new ChannelsException(
                    429,
                    "Too many pending channel connection codes. Wait for existing codes to expire or use one of them.");
        }
        ObjectNode response = json.createObjectNode();
        response.put("provider", provider);
        response.put("mode", meta.authMode);
        response.putNull("url");
        response.put("code", code);
        response.put("instruction", "Send /connect " + code + " to the DeerFlow " + meta.displayName + " bot.");
        response.put("expires_in", ChannelConnectionStore.STATE_TTL_SECONDS);
        send(exchange, 200, response);
    }

    private void configureRuntime(HttpExchange exchange, String provider) throws IOException {
        requireAdmin();
        requireFeatureEnabled();
        ProviderMeta meta = requireProvider(provider);
        if (!providerEnabled(provider)) {
            throw new ChannelsException(400, "Channel provider is not enabled");
        }
        ObjectNode body = readObject(exchange);
        JsonNode valuesNode = body.get("values");
        if (valuesNode != null && !valuesNode.isObject()) {
            throw new ChannelsException(400, "values must be an object");
        }
        ObjectNode values = valuesNode instanceof ObjectNode object ? object : json.createObjectNode();
        ObjectNode existing = runtimeStore.getProviderConfig(provider);
        if (existing != null && runtimeStore.isRuntimeDisconnected(existing)) {
            existing = null;
        }
        ObjectNode runtime = json.createObjectNode();
        runtime.put("enabled", true);
        for (CredentialField field : meta.fields) {
            String raw = values.path(field.name).asText("");
            if ("password".equals(field.type) && MASKED.equals(raw)) {
                String kept = existing == null ? "" : existing.path(field.name).asText("").trim();
                if (!kept.isEmpty()) {
                    runtime.put(field.name, kept);
                    continue;
                }
            }
            String value = raw == null ? "" : raw.trim();
            if ("domain".equals(field.name)) {
                try {
                    value = FeishuOpenDomain.normalizeOrThrow(value);
                } catch (IllegalArgumentException invalid) {
                    throw new ChannelsException(400, invalid.getMessage());
                }
            }
            if (field.required && value.isEmpty()) {
                throw new ChannelsException(
                        400, "Missing required channel configuration: " + field.label);
            }
            runtime.put(field.name, value);
        }
        if ("feishu".equals(provider) && !runtime.hasNonNull("domain")) {
            runtime.put("domain", FeishuOpenDomain.DEFAULT);
        }
        if ("feishu".equals(provider) && feishuWorker != null) {
            try {
                feishuWorker.startOrRestart(
                        runtime.path("app_id").asText(""),
                        runtime.path("app_secret").asText(""),
                        runtime.path("domain").asText(FeishuOpenDomain.DEFAULT));
            } catch (Exception failure) {
                throw new ChannelsException(
                        400,
                        "Failed to start Feishu channel. Check the values and try again.");
            }
        }
        runtimeStore.setProviderConfig(provider, runtime);
        send(exchange, 200, providerResponse(provider, meta, newestOwnerConnection(provider)));
    }

    private void clearRuntime(HttpExchange exchange, String provider) throws IOException {
        requireAdmin();
        requireFeatureEnabled();
        ProviderMeta meta = requireProvider(provider);
        if (!providerEnabled(provider)) {
            throw new ChannelsException(400, "Channel provider is not enabled");
        }
        if ("feishu".equals(provider) && feishuWorker != null) {
            feishuWorker.stop();
        }
        connectionStore.disconnectProviderConnections(provider);
        runtimeStore.setProviderDisconnected(provider);
        send(exchange, 200, providerResponse(provider, meta, null));
    }

    private ObjectNode providerResponse(String provider, ProviderMeta meta, ObjectNode connection) {
        ObjectNode runtime = effectiveRuntime(provider);
        boolean enabled = providerEnabled(provider);
        boolean configured = enabled && runtimeConfigured(provider, meta, runtime);
        String unavailable = unavailableReason(provider, meta, runtime);
        boolean connectable = enabled && configured && unavailable == null;
        String connectionStatus;
        if (unavailable != null) {
            if (connection != null && !"connected".equals(connection.path("status").asText())) {
                connectionStatus = connection.path("status").asText("not_connected");
            } else {
                connectionStatus = "not_connected";
            }
        } else if (connection != null) {
            connectionStatus = connection.path("status").asText("not_connected");
        } else if (!LocalAuthService.sessionAuthActive() && configured) {
            // Auth-disabled local mode: configured running channel needs no per-user binding.
            connectionStatus = "connected";
        } else {
            connectionStatus = "not_connected";
        }
        ObjectNode response = json.createObjectNode();
        response.put("provider", provider);
        response.put("display_name", meta.displayName);
        response.put("enabled", enabled);
        response.put("configured", configured);
        response.put("connectable", connectable);
        if (unavailable == null) {
            response.putNull("unavailable_reason");
        } else {
            response.put("unavailable_reason", unavailable);
        }
        response.put("auth_mode", meta.authMode);
        response.put("connection_status", connectionStatus);
        ArrayNode fields = response.putArray("credential_fields");
        for (CredentialField field : meta.fields) {
            ObjectNode node = fields.addObject();
            node.put("name", field.name);
            node.put("label", field.label);
            node.put("type", field.type);
            node.put("required", field.required);
        }
        ObjectNode values = response.putObject("credential_values");
        if (runtime != null && !runtimeStore.isRuntimeDisconnected(runtime)) {
            for (CredentialField field : meta.fields) {
                String value = runtime.path(field.name).asText("").trim();
                if (value.isEmpty()) {
                    continue;
                }
                values.put(field.name, "password".equals(field.type) ? MASKED : value);
            }
        }
        return response;
    }

    private ObjectNode effectiveRuntime(String provider) {
        ObjectNode stored = runtimeStore.getProviderConfig(provider);
        if (stored == null || runtimeStore.isRuntimeDisconnected(stored)) {
            return null;
        }
        return stored;
    }

    private boolean runtimeConfigured(String provider, ProviderMeta meta, ObjectNode runtime) {
        if (runtime == null || !runtime.path("enabled").asBoolean(false)) {
            return false;
        }
        for (String key : meta.requiredKeys) {
            if (runtime.path(key).asText("").trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private String unavailableReason(String provider, ProviderMeta meta, ObjectNode runtime) {
        if (!providerEnabled(provider)) {
            return null;
        }
        if (!runtimeConfigured(provider, meta, runtime)) {
            return "Enter the required " + meta.displayName + " credentials to connect this channel.";
        }
        return null;
    }

    private boolean providerEnabled(String provider) {
        return "feishu".equals(provider) && feishuEnabled;
    }

    private ProviderMeta requireProvider(String provider) {
        ProviderMeta meta = PROVIDERS.get(provider);
        if (meta == null) {
            throw new ChannelsException(404, "Unknown channel provider");
        }
        return meta;
    }

    private void requireFeatureEnabled() {
        if (!channelConnectionsEnabled) {
            throw new ChannelsException(400, "Channel connections are disabled");
        }
    }

    private void requireAdmin() {
        // bindPrefix(..., true) already gates non-GET when session auth is active; keep an
        // explicit check so direct handler use (role-parameterized tests) matches DeerFlow.
        if (!AccessOwner.admin()) {
            throw new ChannelsException(403, ADMIN_REQUIRED);
        }
    }

    private ObjectNode newestOwnerConnection(String provider) {
        List<ObjectNode> connections = connectionStore.listConnections(AccessOwner.current());
        ObjectNode byProvider = newestConnectionByProvider(connections);
        JsonNode connection = byProvider.get(provider);
        return connection instanceof ObjectNode object ? object : null;
    }

    private ObjectNode newestConnectionByProvider(List<ObjectNode> connections) {
        ObjectNode byProvider = json.createObjectNode();
        for (ObjectNode connection : connections) {
            String provider = connection.path("provider").asText();
            JsonNode existing = byProvider.get(provider);
            if (existing == null
                    || Instant.parse(connection.path("updated_at").asText(Instant.EPOCH.toString()))
                            .isAfter(Instant.parse(
                                    existing.path("updated_at").asText(Instant.EPOCH.toString())))) {
                byProvider.set(provider, connection);
            }
        }
        return byProvider;
    }

    private ObjectNode readObject(HttpExchange exchange) throws IOException {
        byte[] payload = readBounded(exchange.getRequestBody());
        JsonNode node;
        try {
            node = json.readTree(payload);
        } catch (Exception exception) {
            throw new ChannelsException(422, "Invalid JSON body");
        }
        if (!(node instanceof ObjectNode object)) {
            throw new ChannelsException(422, "JSON body must be an object");
        }
        return object;
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int read; (read = input.read(buffer)) >= 0; ) {
            total += read;
            if (total > MAX_BODY_BYTES) {
                throw new ChannelsException(413, "Request body exceeds 65536 bytes");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void methodNotAllowed(HttpExchange exchange, String allow) throws IOException {
        exchange.getResponseHeaders().set("Allow", allow);
        send(exchange, 405, Map.of("detail", "Method Not Allowed"));
    }

    private static boolean isLoopbackPeer(HttpExchange exchange) {
        return exchange.getRemoteAddress() != null
                && exchange.getRemoteAddress().getAddress() != null
                && exchange.getRemoteAddress().getAddress().isLoopbackAddress();
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, payload.length);
        try (var responseBody = exchange.getResponseBody()) {
            responseBody.write(payload);
        }
    }

    private record ProviderMeta(
            String displayName,
            String authMode,
            List<CredentialField> fields,
            List<String> requiredKeys) {}

    private record CredentialField(String name, String label, String type, boolean required) {}

    private static final class ChannelsException extends RuntimeException {
        private final int status;

        private ChannelsException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}

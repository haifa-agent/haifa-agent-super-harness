package io.haifa.agent.superharness.server.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.workspace.AtomicMoves;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * File-backed user IM bindings and one-time connect codes.
 *
 * <p>Semantics match DeerFlow {@code ChannelConnectionRepository} for the L1 surface: single-use
 * oauth states, at most one active connection per {@code (provider, external_account_id,
 * workspace_id)}, latest bind wins. No ChannelManager / MessageBus / SQL schema expansion.</p>
 */
public final class ChannelConnectionStore {
    public static final int STATE_TTL_SECONDS = 600;
    public static final int MAX_PENDING_CONNECT_CODES_PER_PROVIDER = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectMapper json;
    private final Path file;
    private final Object lock = new Object();

    public ChannelConnectionStore(ObjectMapper json, Path dataDirectory) {
        this.json = json;
        this.file = dataDirectory
                .toAbsolutePath()
                .normalize()
                .resolve("channels")
                .resolve("connections.json");
    }

    /** 128-bit url-safe binding code (DeerFlow {@code secrets.token_urlsafe(16)}). */
    public static String newBindingCode() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Insert a pending connect code when the owner has fewer than {@code maxPending} active codes
     * for the provider. Returns {@code false} when the cap would be exceeded.
     */
    public boolean createOauthStateWithinCap(
            String ownerUserId,
            String provider,
            String state,
            Instant expiresAt,
            int maxPending) {
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Instant now = Instant.now();
        synchronized (lock) {
            StoreData data = read();
            purgeExpiredStates(data, now);
            long pending = data.oauthStates.stream()
                    .filter(row -> ownerUserId.equals(text(row, "owner_user_id"))
                            && provider.equals(text(row, "provider"))
                            && !row.path("consumed").asBoolean(false)
                            && !isExpired(row, now))
                    .count();
            if (pending >= maxPending) {
                return false;
            }
            ObjectNode row = json.createObjectNode();
            row.put("provider", provider);
            row.put("state", state);
            row.put("owner_user_id", ownerUserId);
            row.put("expires_at", expiresAt.toString());
            row.put("consumed", false);
            data.oauthStates.add(row);
            write(data);
            return true;
        }
    }

    /**
     * Single-use consume. Returns the owner user id when the code is valid, unexpired, and unused.
     */
    public Optional<String> consumeOauthState(String provider, String code) {
        Objects.requireNonNull(provider, "provider");
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        synchronized (lock) {
            StoreData data = read();
            purgeExpiredStates(data, now);
            for (ObjectNode row : data.oauthStates) {
                if (!provider.equals(text(row, "provider")) || !code.equals(text(row, "state"))) {
                    continue;
                }
                if (row.path("consumed").asBoolean(false) || isExpired(row, now)) {
                    write(data);
                    return Optional.empty();
                }
                row.put("consumed", true);
                row.put("consumed_at", now.toString());
                write(data);
                return Optional.of(text(row, "owner_user_id"));
            }
            write(data);
            return Optional.empty();
        }
    }

    /**
     * Upsert an active connection for {@code ownerUserId}. Revokes other owners' active rows for
     * the same external identity before inserting/updating.
     */
    public ObjectNode upsertConnection(
            String ownerUserId,
            String provider,
            String externalAccountId,
            String externalAccountName,
            String workspaceId,
            String workspaceName,
            Map<String, ?> metadata,
            String status) {
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        Objects.requireNonNull(provider, "provider");
        String externalId = normalizeOptional(externalAccountId);
        String workspace = normalizeOptional(workspaceId);
        String resolvedStatus = status == null || status.isBlank() ? "connected" : status;
        Instant now = Instant.now();
        synchronized (lock) {
            StoreData data = read();
            if ("connected".equals(resolvedStatus)) {
                for (ObjectNode row : data.connections) {
                    if (provider.equals(text(row, "provider"))
                            && Objects.equals(normalizeOptional(text(row, "external_account_id")), externalId)
                            && Objects.equals(normalizeOptional(text(row, "workspace_id")), workspace)
                            && !ownerUserId.equals(text(row, "owner_user_id"))
                            && !"revoked".equals(text(row, "status"))) {
                        row.put("status", "revoked");
                        row.put("updated_at", now.toString());
                    }
                }
            }
            ObjectNode existing = null;
            for (ObjectNode row : data.connections) {
                if (ownerUserId.equals(text(row, "owner_user_id"))
                        && provider.equals(text(row, "provider"))
                        && Objects.equals(normalizeOptional(text(row, "external_account_id")), externalId)
                        && Objects.equals(normalizeOptional(text(row, "workspace_id")), workspace)) {
                    existing = row;
                    break;
                }
            }
            if (existing == null) {
                existing = json.createObjectNode();
                existing.put("id", UUID.randomUUID().toString());
                existing.put("owner_user_id", ownerUserId);
                existing.put("provider", provider);
                existing.put("created_at", now.toString());
                existing.putArray("scopes");
                existing.putObject("metadata");
                data.connections.add(existing);
            }
            existing.put("status", resolvedStatus);
            putNullable(existing, "external_account_id", externalId);
            putNullable(existing, "external_account_name", externalAccountName);
            putNullable(existing, "workspace_id", workspace);
            putNullable(existing, "workspace_name", workspaceName);
            if (metadata != null) {
                existing.set("metadata", json.valueToTree(metadata));
            }
            if (!existing.has("scopes") || !existing.get("scopes").isArray()) {
                existing.putArray("scopes");
            }
            existing.put("updated_at", now.toString());
            write(data);
            return projection(existing);
        }
    }

    /** All connections for an owner (including revoked), newest first. */
    public List<ObjectNode> listConnections(String ownerUserId) {
        synchronized (lock) {
            StoreData data = read();
            List<ObjectNode> rows = new ArrayList<>();
            for (ObjectNode row : data.connections) {
                if (ownerUserId.equals(text(row, "owner_user_id"))) {
                    rows.add(projection(row));
                }
            }
            rows.sort((left, right) -> {
                int byUpdated = Instant.parse(text(right, "updated_at", Instant.EPOCH.toString()))
                        .compareTo(Instant.parse(text(left, "updated_at", Instant.EPOCH.toString())));
                if (byUpdated != 0) {
                    return byUpdated;
                }
                return text(right, "id").compareTo(text(left, "id"));
            });
            return rows;
        }
    }

    /** Owner-scoped disconnect. Returns {@code false} when the row is missing or not owned. */
    public boolean disconnectConnection(String connectionId, String ownerUserId) {
        Instant now = Instant.now();
        synchronized (lock) {
            StoreData data = read();
            for (ObjectNode row : data.connections) {
                if (connectionId.equals(text(row, "id"))
                        && ownerUserId.equals(text(row, "owner_user_id"))) {
                    row.put("status", "revoked");
                    row.put("updated_at", now.toString());
                    write(data);
                    return true;
                }
            }
            return false;
        }
    }

    /** Revoke every active connection for a provider (runtime-config DELETE). */
    public int disconnectProviderConnections(String provider) {
        Instant now = Instant.now();
        synchronized (lock) {
            StoreData data = read();
            int count = 0;
            for (ObjectNode row : data.connections) {
                if (provider.equals(text(row, "provider")) && !"revoked".equals(text(row, "status"))) {
                    row.put("status", "revoked");
                    row.put("updated_at", now.toString());
                    count++;
                }
            }
            if (count > 0) {
                write(data);
            }
            return count;
        }
    }

    /**
     * Test-visible Feishu worker bind seam: consume {@code /connect <code>} then upsert the
     * connection for the code's owner. Returns empty when the code is invalid/expired/used.
     */
    public Optional<ObjectNode> bindFeishuFromConnectCode(
            String code, String externalAccountId, String workspaceId, Map<String, ?> metadata) {
        Optional<String> owner = consumeOauthState("feishu", code);
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> meta = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
        return Optional.of(upsertConnection(
                owner.get(),
                "feishu",
                externalAccountId,
                null,
                workspaceId,
                null,
                meta,
                "connected"));
    }

    /**
     * Active (non-revoked) connection for an external Feishu identity, including
     * {@code owner_user_id} for Run ownership. Prefer workspace match; optional null workspace is
     * not used by the Feishu worker (chat_id is the workspace).
     */
    public Optional<ObjectNode> findConnectionByExternalIdentity(
            String provider, String externalAccountId, String workspaceId) {
        Objects.requireNonNull(provider, "provider");
        String externalId = normalizeOptional(externalAccountId);
        String workspace = normalizeOptional(workspaceId);
        if (externalId == null) {
            return Optional.empty();
        }
        synchronized (lock) {
            StoreData data = read();
            for (ObjectNode row : data.connections) {
                if (!provider.equals(text(row, "provider"))) {
                    continue;
                }
                if ("revoked".equals(text(row, "status"))) {
                    continue;
                }
                if (!Objects.equals(normalizeOptional(text(row, "external_account_id")), externalId)) {
                    continue;
                }
                if (!Objects.equals(normalizeOptional(text(row, "workspace_id")), workspace)) {
                    continue;
                }
                ObjectNode out = projection(row);
                out.put("owner_user_id", text(row, "owner_user_id"));
                return Optional.of(out);
            }
            return Optional.empty();
        }
    }

    private ObjectNode projection(ObjectNode row) {
        ObjectNode out = json.createObjectNode();
        out.put("id", text(row, "id"));
        out.put("provider", text(row, "provider"));
        out.put("status", text(row, "status"));
        putNullable(out, "external_account_id", normalizeOptional(text(row, "external_account_id")));
        putNullable(out, "external_account_name", normalizeOptional(text(row, "external_account_name")));
        putNullable(out, "workspace_id", normalizeOptional(text(row, "workspace_id")));
        putNullable(out, "workspace_name", normalizeOptional(text(row, "workspace_name")));
        JsonNode scopes = row.get("scopes");
        out.set("scopes", scopes != null && scopes.isArray() ? scopes.deepCopy() : json.createArrayNode());
        JsonNode metadata = row.get("metadata");
        out.set("metadata", metadata != null && metadata.isObject() ? metadata.deepCopy() : json.createObjectNode());
        // Retained for newest-by-provider selection; frozen ChannelConnectionResponse omits it.
        if (!text(row, "updated_at").isBlank()) {
            out.put("updated_at", text(row, "updated_at"));
        }
        return out;
    }

    private StoreData read() {
        if (!Files.exists(file)) {
            return StoreData.empty();
        }
        try {
            JsonNode root = json.readTree(file.toFile());
            if (!(root instanceof ObjectNode object)) {
                return StoreData.empty();
            }
            List<ObjectNode> connections = readArray(object, "connections");
            List<ObjectNode> oauthStates = readArray(object, "oauth_states");
            return new StoreData(connections, oauthStates);
        } catch (IOException exception) {
            return StoreData.empty();
        }
    }

    private void write(StoreData data) {
        ObjectNode root = json.createObjectNode();
        ArrayNode connections = root.putArray("connections");
        data.connections.forEach(connections::add);
        ArrayNode oauthStates = root.putArray("oauth_states");
        data.oauthStates.forEach(oauthStates::add);
        Path temporary = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), root);
            tryRestrictOwnerOnly(temporary);
            AtomicMoves.replace(temporary, file);
            tryRestrictOwnerOnly(file);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Preserve the primary storage failure.
            }
            throw new IllegalStateException("Channel connection store is unavailable", exception);
        }
    }

    private static List<ObjectNode> readArray(ObjectNode root, String field) {
        List<ObjectNode> rows = new ArrayList<>();
        JsonNode array = root.get(field);
        if (array != null && array.isArray()) {
            for (JsonNode item : array) {
                if (item instanceof ObjectNode object) {
                    rows.add(object.deepCopy());
                }
            }
        }
        return rows;
    }

    private static void purgeExpiredStates(StoreData data, Instant now) {
        Iterator<ObjectNode> iterator = data.oauthStates.iterator();
        while (iterator.hasNext()) {
            if (isExpired(iterator.next(), now)) {
                iterator.remove();
            }
        }
    }

    private static boolean isExpired(ObjectNode row, Instant now) {
        String expiresAt = text(row, "expires_at");
        if (expiresAt.isBlank()) {
            return true;
        }
        try {
            return Instant.parse(expiresAt).isBefore(now);
        } catch (Exception exception) {
            return true;
        }
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null || value.isBlank()) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static String text(ObjectNode node, String field) {
        return text(node, field, "");
    }

    private static String text(ObjectNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return fallback;
        }
        return value.asText();
    }

    private static void tryRestrictOwnerOnly(Path path) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and non-POSIX volumes keep the default ACL.
        }
    }

    private static final class StoreData {
        private final List<ObjectNode> connections;
        private final List<ObjectNode> oauthStates;

        private StoreData(List<ObjectNode> connections, List<ObjectNode> oauthStates) {
            this.connections = connections;
            this.oauthStates = oauthStates;
        }

        private static StoreData empty() {
            return new StoreData(new ArrayList<>(), new ArrayList<>());
        }
    }
}

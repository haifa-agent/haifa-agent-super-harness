package io.haifa.agent.superharness.server.channels.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.workspace.AtomicMoves;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Minimal JSON store under the Gateway data-root backing Feishu multi-thread routing:
 *
 * <ul>
 *   <li>current chat pointer: {@code (connection_id, chat_id) → thread_id} (single-chat {@code /new}
 *       and the first unthreaded message);
 *   <li>immutable message/topic aliases: {@code (connection_id, chat_id, alias) → thread_id} for
 *       platform {@code root_id}/{@code parent_id}/{@code feishu_thread_id}, outbound reply message
 *       ids, and approval/clarification {@code request_id}s.
 * </ul>
 *
 * <p>The legacy {@code (connection_id, chat_id)} rows keep working as the current pointer, so an
 * existing {@code channels/feishu-thread-map.json} needs no migration. Reads are fail-closed: a
 * corrupt file, a non-object root, or a non-object {@code mappings} is an error, never an empty
 * store, so a broken file can never silently create a new thread or be overwritten.
 */
public final class FeishuChatThreadStore {
    private final ObjectMapper json;
    private final Path file;
    private final Object lock = new Object();

    public FeishuChatThreadStore(ObjectMapper json, Path dataDirectory) {
        this.json = json;
        this.file = dataDirectory
                .toAbsolutePath()
                .normalize()
                .resolve("channels")
                .resolve("feishu-thread-map.json");
    }

    public Optional<String> find(String connectionId, String chatId) {
        synchronized (lock) {
            ObjectNode row = read().get(key(connectionId, chatId));
            if (row == null) {
                return Optional.empty();
            }
            String threadId = row.path("thread_id").asText("");
            return threadId.isBlank() ? Optional.empty() : Optional.of(threadId);
        }
    }

    public String getOrCreate(String connectionId, String chatId) {
        synchronized (lock) {
            Map<String, ObjectNode> data = read();
            String mapKey = key(connectionId, chatId);
            ObjectNode existing = data.get(mapKey);
            if (existing != null) {
                String threadId = existing.path("thread_id").asText("");
                if (!threadId.isBlank()) {
                    return threadId;
                }
            }
            return writeCurrent(data, connectionId, chatId, "feishu-" + UUID.randomUUID());
        }
    }

    /** Rotate the current chat pointer (single-chat {@code /new}); existing aliases are untouched. */
    public String bindCurrent(String connectionId, String chatId, String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("thread_id is required");
        }
        synchronized (lock) {
            return writeCurrent(read(), connectionId, chatId, threadId);
        }
    }

    private String writeCurrent(
            Map<String, ObjectNode> data, String connectionId, String chatId, String threadId) {
        ObjectNode row = json.createObjectNode();
        row.put("connection_id", connectionId);
        row.put("chat_id", chatId);
        row.put("thread_id", threadId);
        data.put(key(connectionId, chatId), row);
        write(data);
        return threadId;
    }

    /** Look up an immutable message/topic/request alias within the same connection + chat scope. */
    public Optional<String> findAlias(String connectionId, String chatId, String alias) {
        if (alias == null || alias.isBlank()) {
            return Optional.empty();
        }
        synchronized (lock) {
            ObjectNode row = read().get(aliasKey(connectionId, chatId, alias));
            if (row == null) {
                return Optional.empty();
            }
            String id = row.path("thread_id").asText("");
            return id.isBlank() ? Optional.empty() : Optional.of(id);
        }
    }

    /**
     * First writer wins: returns the thread already bound to {@code alias}, or binds and returns
     * {@code candidateThreadId}. An alias is never re-pointed to a later thread, and a losing
     * candidate is never written over the winner.
     */
    public String bindAliasIfAbsent(
            String connectionId, String chatId, String alias, String candidateThreadId) {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("alias is required");
        }
        if (candidateThreadId == null || candidateThreadId.isBlank()) {
            throw new IllegalArgumentException("thread_id is required");
        }
        synchronized (lock) {
            Map<String, ObjectNode> data = read();
            String mapKey = aliasKey(connectionId, chatId, alias);
            ObjectNode existing = data.get(mapKey);
            if (existing != null) {
                String bound = existing.path("thread_id").asText("");
                if (!bound.isBlank()) {
                    return bound;
                }
            }
            ObjectNode row = json.createObjectNode();
            row.put("connection_id", connectionId);
            row.put("chat_id", chatId);
            row.put("alias_id", alias);
            row.put("thread_id", candidateThreadId);
            data.put(mapKey, row);
            write(data);
            return candidateThreadId;
        }
    }

    private static String aliasKey(String connectionId, String chatId, String alias) {
        return key(connectionId, chatId) + "|alias:" + alias;
    }

    private static String key(String connectionId, String chatId) {
        return (connectionId == null ? "" : connectionId) + "|" + (chatId == null ? "" : chatId);
    }

    private Map<String, ObjectNode> read() {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        JsonNode root;
        try {
            root = json.readTree(file.toFile());
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read existing Feishu thread mappings", exception);
        }
        if (!(root instanceof ObjectNode object)) {
            throw new IllegalStateException("Feishu thread mapping root is not a JSON object");
        }
        JsonNode mappings = object.get("mappings");
        if (mappings == null || mappings.isNull()) {
            // A well-formed empty store may not have written the mappings object yet.
            return new LinkedHashMap<>();
        }
        if (!(mappings instanceof ObjectNode map)) {
            throw new IllegalStateException("Feishu thread mapping 'mappings' is not a JSON object");
        }
        Map<String, ObjectNode> data = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : map.properties()) {
            if (!(entry.getValue() instanceof ObjectNode value)) {
                throw new IllegalStateException("Feishu thread mapping entry is not a JSON object");
            }
            data.put(entry.getKey(), value.deepCopy());
        }
        return data;
    }

    private void write(Map<String, ObjectNode> data) {
        ObjectNode root = json.createObjectNode();
        ObjectNode mappings = root.putObject("mappings");
        data.forEach(mappings::set);
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
            throw new IllegalStateException("Feishu thread mapping store is unavailable", exception);
        }
    }
}

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
 * Minimal JSON map {@code (connection_id, chat_id) → thread_id} under the Gateway data-root.
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
            String threadId = "feishu-" + UUID.randomUUID();
            ObjectNode row = json.createObjectNode();
            row.put("connection_id", connectionId);
            row.put("chat_id", chatId);
            row.put("thread_id", threadId);
            data.put(mapKey, row);
            write(data);
            return threadId;
        }
    }

    private static String key(String connectionId, String chatId) {
        return (connectionId == null ? "" : connectionId) + "|" + (chatId == null ? "" : chatId);
    }

    private Map<String, ObjectNode> read() {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try {
            JsonNode root = json.readTree(file.toFile());
            if (!(root instanceof ObjectNode object)) {
                return new LinkedHashMap<>();
            }
            Map<String, ObjectNode> data = new LinkedHashMap<>();
            JsonNode mappings = object.get("mappings");
            if (mappings instanceof ObjectNode map) {
                for (Map.Entry<String, JsonNode> entry : map.properties()) {
                    if (entry.getValue() instanceof ObjectNode value) {
                        data.put(entry.getKey(), value.deepCopy());
                    }
                }
            }
            return data;
        } catch (IOException exception) {
            return new LinkedHashMap<>();
        }
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

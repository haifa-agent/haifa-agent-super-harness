package io.haifa.agent.superharness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.core.session.AgentSessionId;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal DeerFlow thread catalog.
 *
 * <p>Messages, Run status/output and checkpoint payloads deliberately remain absent. Their sole
 * authority is the Haifa SQLite Runtime/Conversation store.
 */
final class PersistentThreadCatalog {
    private static final int FORMAT_VERSION = 1;
    private static final long MAXIMUM_CATALOG_BYTES = 4L * 1024 * 1024;

    private final ObjectMapper json;
    private final Path catalog;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    PersistentThreadCatalog(ObjectMapper json, Path dataDirectory) throws IOException {
        this.json = json;
        Path ownerDirectory = dataDirectory.toAbsolutePath().normalize().resolve("owners").resolve("default");
        Files.createDirectories(ownerDirectory);
        this.catalog = ownerDirectory.resolve("threads.json");
        load();
    }

    synchronized Optional<Entry> find(String threadId) {
        return Optional.ofNullable(entries.get(threadId));
    }

    synchronized List<Entry> list() {
        return List.copyOf(entries.values());
    }

    synchronized void save(Entry entry) throws IOException {
        Entry previous = entries.put(entry.threadId(), entry);
        try {
            persist();
        } catch (IOException | RuntimeException | Error failure) {
            if (previous == null) entries.remove(entry.threadId());
            else entries.put(entry.threadId(), previous);
            throw failure;
        }
    }

    private void load() throws IOException {
        if (!Files.exists(catalog)) return;
        if (Files.size(catalog) > MAXIMUM_CATALOG_BYTES) {
            throw new IOException("thread catalog exceeds the configured size bound");
        }
        JsonNode root = json.readTree(catalog.toFile());
        if (root == null || root.path("version").asInt(-1) != FORMAT_VERSION || !root.path("threads").isArray()) {
            throw new IOException("thread catalog has an unsupported format");
        }
        for (JsonNode item : root.path("threads")) {
            Entry entry = new Entry(
                    requiredText(item, "thread_id"),
                    objectMap(item.path("metadata")),
                    Instant.parse(requiredText(item, "created_at")),
                    Instant.parse(requiredText(item, "updated_at")),
                    optionalText(item, "session_id").map(AgentSessionId::new),
                    optionalText(item, "model_binding"));
            if (entries.putIfAbsent(entry.threadId(), entry) != null) {
                throw new IOException("thread catalog contains a duplicate thread_id");
            }
        }
    }

    private void persist() throws IOException {
        ObjectNode root = json.createObjectNode();
        root.put("version", FORMAT_VERSION);
        ArrayNode threads = root.putArray("threads");
        for (Entry entry : entries.values()) {
            ObjectNode item = threads.addObject();
            item.put("thread_id", entry.threadId());
            item.set("metadata", json.valueToTree(entry.metadata()));
            item.put("created_at", entry.createdAt().toString());
            item.put("updated_at", entry.updatedAt().toString());
            entry.sessionId().ifPresentOrElse(
                    value -> item.put("session_id", value.value()), () -> item.putNull("session_id"));
            entry.modelBindingIdentity().ifPresentOrElse(
                    value -> item.put("model_binding", value), () -> item.putNull("model_binding"));
        }
        byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        if (bytes.length > MAXIMUM_CATALOG_BYTES) {
            throw new IOException("thread catalog exceeds the configured size bound");
        }
        Path temporary = catalog.resolveSibling(catalog.getFileName() + ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(
                        temporary,
                        catalog,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, catalog, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String requiredText(JsonNode node, String name) throws IOException {
        return optionalText(node, name).orElseThrow(() -> new IOException("thread catalog field is missing: " + name));
    }

    private Optional<String> optionalText(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) return Optional.empty();
        if (!value.isTextual() || value.textValue().isBlank()) return Optional.empty();
        return Optional.of(value.textValue());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(JsonNode node) throws IOException {
        if (!node.isObject()) throw new IOException("thread catalog metadata must be an object");
        return new LinkedHashMap<>(json.convertValue(node, Map.class));
    }

    record Entry(
            String threadId,
            Map<String, Object> metadata,
            Instant createdAt,
            Instant updatedAt,
            Optional<AgentSessionId> sessionId,
            Optional<String> modelBindingIdentity) {
        Entry {
            if (threadId == null || threadId.isBlank()) throw new IllegalArgumentException("threadId must not be blank");
            metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
            if (createdAt == null || updatedAt == null) throw new IllegalArgumentException("timestamps are required");
            if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not precede createdAt");
            sessionId = sessionId == null ? Optional.empty() : sessionId;
            modelBindingIdentity = modelBindingIdentity == null ? Optional.empty() : modelBindingIdentity;
        }
    }
}

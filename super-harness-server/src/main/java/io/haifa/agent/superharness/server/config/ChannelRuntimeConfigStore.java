package io.haifa.agent.superharness.server.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.workspace.AtomicMoves;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * JSON-backed store for IM channel bot credentials entered from the UI.
 *
 * <p>Mirrors DeerFlow {@code ChannelRuntimeConfigStore}: local plaintext under the Gateway
 * data-root with owner-only permissions when the filesystem supports POSIX modes. No second
 * Runtime domain — deployment-level secrets only.</p>
 */
public final class ChannelRuntimeConfigStore {
    public static final String RUNTIME_DISABLED_FLAG = "_runtime_disabled";

    private final ObjectMapper json;
    private final Path file;
    private final Object lock = new Object();

    public ChannelRuntimeConfigStore(ObjectMapper json, Path dataDirectory) {
        this.json = json;
        this.file = dataDirectory
                .toAbsolutePath()
                .normalize()
                .resolve("channels")
                .resolve("runtime-config.json");
    }

    public Map<String, ObjectNode> loadAll() {
        synchronized (lock) {
            return copyAll(read());
        }
    }

    public ObjectNode getProviderConfig(String provider) {
        synchronized (lock) {
            ObjectNode config = read().get(provider);
            return config == null ? null : config.deepCopy();
        }
    }

    public void setProviderConfig(String provider, ObjectNode config) {
        synchronized (lock) {
            Map<String, ObjectNode> data = read();
            data.put(provider, config.deepCopy());
            write(data);
        }
    }

    public void setProviderDisconnected(String provider) {
        ObjectNode disconnected = json.createObjectNode();
        disconnected.put("enabled", false);
        disconnected.put(RUNTIME_DISABLED_FLAG, true);
        setProviderConfig(provider, disconnected);
    }

    public boolean isRuntimeDisconnected(ObjectNode config) {
        return config != null
                && config.path(RUNTIME_DISABLED_FLAG).asBoolean(false)
                && !config.path("enabled").asBoolean(true);
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
            for (Map.Entry<String, JsonNode> entry : object.properties()) {
                if (entry.getValue() instanceof ObjectNode value) {
                    data.put(entry.getKey(), value.deepCopy());
                }
            }
            return data;
        } catch (IOException exception) {
            return new LinkedHashMap<>();
        }
    }

    private void write(Map<String, ObjectNode> data) {
        ObjectNode root = json.createObjectNode();
        data.forEach(root::set);
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
            throw new IllegalStateException("Channel runtime configuration is unavailable", exception);
        }
    }

    private static Map<String, ObjectNode> copyAll(Map<String, ObjectNode> source) {
        Map<String, ObjectNode> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, value.deepCopy()));
        return copy;
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
}

package io.haifa.agent.superharness.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Frozen DeerFlow capability catalog manifests.
 *
 * <p>The bundled JSON is inert metadata copied byte-for-byte from the frozen reference
 * commit recorded in {@code manifest.json}. It is validated with the frozen Pydantic
 * defaults and field order; no executable plugin loader, installation adapter or
 * persistent store is involved.
 */
final class CapabilityCatalog {
    static final String MANIFEST_RESOURCE = "upstream/deerflow/capabilities/manifest.json";
    static final String CATALOG_RESOURCE = "upstream/deerflow/capabilities/builtin.json";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9.-]*$");
    private static final Pattern ADAPTER = Pattern.compile("^[a-z][a-z0-9_-]*$");
    private static final Set<String> CATEGORIES = Set.of(
            "office", "knowledge", "research", "business", "development", "custom");
    private static final Set<String> KINDS = Set.of("mcp", "cli", "native");
    private static final Set<String> AUTH_METHODS = Set.of("none", "api_key", "oauth");
    private static final Set<String> CONTRIBUTIONS = Set.of("tools", "skills");
    /** Frozen {@code PluginManifest} declaration order; the response serializer follows it. */
    static final List<String> ENTRY_FIELDS = List.of(
            "schema_version",
            "id",
            "version",
            "name",
            "description",
            "setup",
            "category",
            "kind",
            "adapter",
            "source",
            "icon",
            "aliases",
            "auth_methods",
            "contributions",
            "config_schema");
    private static final Set<String> ENTRY_FIELD_SET = Set.copyOf(ENTRY_FIELDS);

    private static volatile List<ObjectNode> verified;

    private CapabilityCatalog() {}

    /** Loads, verifies and parses the bundled catalog once; failures stop server startup. */
    static List<ObjectNode> catalog() {
        List<ObjectNode> local = verified;
        if (local != null) {
            return local;
        }
        synchronized (CapabilityCatalog.class) {
            if (verified != null) {
                return verified;
            }
            byte[] manifestBytes = readResource(MANIFEST_RESOURCE);
            byte[] catalogBytes = readResource(CATALOG_RESOURCE);
            verify(manifestBytes, catalogBytes);
            List<ObjectNode> parsed = List.copyOf(parse(catalogBytes));
            verified = parsed;
            return parsed;
        }
    }

    /** Verifies the bundled bytes against the frozen source hash recorded in the manifest. */
    static void verify(byte[] manifestBytes, byte[] catalogBytes) {
        String expected = expectedHash(manifestBytes, "builtin.json");
        String actual = sha256(catalogBytes);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IllegalStateException("Frozen capability catalog hash mismatch at " + CATALOG_RESOURCE
                    + " (expected " + expected + ", actual " + actual + ")");
        }
    }

    /**
     * Applies the frozen {@code PluginManifest} defaults, field order and validators.
     * Unknown fields are rejected like the frozen {@code extra="forbid"} model.
     */
    static List<ObjectNode> parse(byte[] catalogBytes) {
        JsonNode root;
        try {
            root = JSON.readTree(catalogBytes);
        } catch (IOException invalid) {
            throw new IllegalStateException("Frozen capability catalog is not valid JSON", invalid);
        }
        if (root == null || !root.isArray()) {
            throw new IllegalStateException("Frozen capability catalog must be a JSON array");
        }
        List<ObjectNode> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode raw : root) {
            if (!raw.isObject()) {
                throw new IllegalStateException("Frozen capability catalog entries must be JSON objects");
            }
            rejectUnknownFields(raw);
            ObjectNode entry = JSON.createObjectNode();
            int schemaVersion = integer(raw, "schema_version", 1);
            if (schemaVersion != 1) {
                throw new IllegalStateException("Unsupported plugin manifest schema_version: " + schemaVersion);
            }
            entry.put("schema_version", 1);
            String id = requiredText(raw, "id");
            if (id.length() > 128 || !ID.matcher(id).matches()) {
                throw new IllegalStateException("Invalid plugin identifier: " + id);
            }
            if (!ids.add(id)) {
                throw new IllegalStateException("Duplicate plugin identifier in catalog: " + id);
            }
            entry.put("id", id);
            String version = requiredText(raw, "version");
            if (version.isEmpty() || version.length() > 80) {
                throw new IllegalStateException("Invalid plugin version for " + id);
            }
            entry.put("version", version);
            entry.set("name", localized(raw, "name", id));
            entry.set("description", localized(raw, "description", id));
            entry.set("setup", localized(raw, "setup", id));
            String category = requiredText(raw, "category");
            if (!CATEGORIES.contains(category)) {
                throw new IllegalStateException("Unsupported plugin category for " + id + ": " + category);
            }
            entry.put("category", category);
            String kind = requiredText(raw, "kind");
            if (!KINDS.contains(kind)) {
                throw new IllegalStateException("Unsupported plugin kind for " + id + ": " + kind);
            }
            entry.put("kind", kind);
            String adapter = requiredText(raw, "adapter");
            if (!ADAPTER.matcher(adapter).matches()) {
                throw new IllegalStateException("Invalid plugin adapter for " + id + ": " + adapter);
            }
            entry.put("adapter", adapter);
            String source = requiredText(raw, "source");
            if (!source.startsWith("https://")) {
                throw new IllegalStateException("Plugin source must use HTTPS: " + id);
            }
            entry.put("source", source);
            entry.set("icon", icon(raw.get("icon"), id));
            entry.set("aliases", textArray(raw, "aliases", id));
            entry.set("auth_methods", enumArray(raw, "auth_methods", AUTH_METHODS, id));
            entry.set("contributions", enumArray(raw, "contributions", CONTRIBUTIONS, id));
            entry.set("config_schema", configSchema(raw.get("config_schema")));
            entries.add(entry);
        }
        return entries;
    }

    private static void rejectUnknownFields(JsonNode raw) {
        Iterator<String> names = raw.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!ENTRY_FIELD_SET.contains(name)) {
                throw new IllegalStateException("Unknown plugin manifest field: " + name);
            }
        }
    }

    private static ObjectNode localized(JsonNode raw, String field, String id) {
        JsonNode value = raw.get(field);
        if (value == null || !value.isObject()) {
            throw new IllegalStateException("Plugin " + id + " requires localized " + field);
        }
        ObjectNode copy = JSON.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!entry.getValue().isTextual()) {
                throw new IllegalStateException("Plugin " + id + " " + field + " values must be strings");
            }
            copy.put(entry.getKey(), entry.getValue().asText());
        }
        return copy;
    }

    private static JsonNode icon(JsonNode value, String id) {
        if (value == null || value.isNull()) {
            return NullNode.getInstance();
        }
        if (!value.isTextual() || !isBundledIcon(value.asText())) {
            throw new IllegalStateException("Catalog icons must be bundled plugin assets: " + id);
        }
        return NODES.textNode(value.asText());
    }

    private static boolean isBundledIcon(String value) {
        return value.startsWith("/images/plugins/")
                && !value.contains("..")
                && !value.contains("?")
                && !value.contains("#");
    }

    private static ArrayNode textArray(JsonNode raw, String field, String id) {
        JsonNode value = raw.get(field);
        ArrayNode array = JSON.createArrayNode();
        if (value == null) {
            return array;
        }
        if (!value.isArray()) {
            throw new IllegalStateException("Plugin " + id + " " + field + " must be an array");
        }
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new IllegalStateException("Plugin " + id + " " + field + " values must be strings");
            }
            array.add(item.asText());
        }
        return array;
    }

    private static ArrayNode enumArray(JsonNode raw, String field, Set<String> allowed, String id) {
        ArrayNode array = textArray(raw, field, id);
        for (JsonNode item : array) {
            if (!allowed.contains(item.asText())) {
                throw new IllegalStateException("Plugin " + id + " " + field + " contains unsupported value: "
                        + item.asText());
            }
        }
        return array;
    }

    private static JsonNode configSchema(JsonNode value) {
        if (value == null) {
            return JSON.createObjectNode().put("type", "object");
        }
        if (!value.isObject()) {
            throw new IllegalStateException("Plugin config_schema must be a JSON object");
        }
        return value.deepCopy();
    }

    private static String requiredText(JsonNode raw, String field) {
        JsonNode value = raw.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalStateException("Plugin manifest field '" + field + "' must be a string");
        }
        return value.asText();
    }

    private static int integer(JsonNode raw, String field, int fallback) {
        JsonNode value = raw.get(field);
        if (value == null) {
            return fallback;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalStateException("Plugin manifest field '" + field + "' must be an integer");
        }
        return value.intValue();
    }

    private static String expectedHash(byte[] manifestBytes, String path) {
        try {
            JsonNode root = JSON.readTree(manifestBytes);
            JsonNode files = root.path("files");
            if (files.isArray()) {
                for (JsonNode file : files) {
                    if (path.equals(file.path("path").asText())) {
                        return file.path("sha256").asText();
                    }
                }
            }
        } catch (IOException invalid) {
            throw new IllegalStateException("Frozen capability catalog manifest is not valid JSON", invalid);
        }
        throw new IllegalStateException("Capability catalog manifest entry is missing: " + path);
    }

    private static byte[] readResource(String path) {
        ClassLoader loader = CapabilityCatalog.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Required capability resource not found on classpath: " + path);
            }
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to read capability resource: " + path, failure);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

}

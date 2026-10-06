package io.haifa.agent.superharness.server.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.workspace.AtomicMoves;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/** File-backed product definitions only; never owns a Child or execution state. */
public final class SubagentCatalogStore {
    private static final System.Logger LOG = System.getLogger(SubagentCatalogStore.class.getName());
    private static final List<String> FIELDS = List.of("name", "display_name", "description", "system_prompt",
            "tools", "disallowed_tools", "skills", "model", "max_turns", "timeout_seconds", "enabled");
    private static final List<String> REQUIRED_DENIED = List.of("ask_clarification", "present_files", "task");
    private final ObjectMapper json;
    private final Path directory;
    private final Object lock = new Object();

    public SubagentCatalogStore(ObjectMapper json, Path dataRoot) {
        this.json = json;
        this.directory = dataRoot.toAbsolutePath().normalize().resolve("owners/default/subagents");
    }

    public List<ObjectNode> catalog(boolean includePrompts) throws IOException {
        synchronized (lock) {
            List<ObjectNode> items = new ArrayList<>();
            items.add(response(BuiltinSubagent.generalPurpose(json), "builtin", includePrompts));
            for (ObjectNode definition : managed()) items.add(response(definition, "managed", includePrompts));
            items.sort(Comparator.comparing((ObjectNode n) -> n.path("name").asText())
                    .thenComparing(n -> n.path("source").asText().equals("builtin") ? 0 : 1));
            return items;
        }
    }

    /** Fresh eligible definitions and a SHA-256 of their normalized content, in name order. */
    public RuntimeSnapshot runtimeSnapshot() throws IOException {
        synchronized (lock) {
            List<ObjectNode> definitions = new ArrayList<>();
            definitions.add(BuiltinSubagent.generalPurpose(json));
            for (ObjectNode definition : managed()) {
                if (definition.path("enabled").asBoolean() && !reserved(definition.path("name").asText())) {
                    definitions.add(definition);
                }
            }
            definitions.sort(Comparator.comparing(n -> n.path("name").asText()));
            ArrayNode canonical = json.createArrayNode();
            for (ObjectNode definition : definitions) {
                ObjectNode ordered = canonical.addObject();
                for (String field : FIELDS) ordered.set(field, definition.get(field));
            }
            try {
                String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(json.writeValueAsBytes(canonical)));
                return new RuntimeSnapshot(List.copyOf(definitions), fingerprint);
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is required", exception);
            }
        }
    }

    public record RuntimeSnapshot(List<ObjectNode> definitions, String fingerprint) {}

    public ObjectNode create(JsonNode request, Predicate<String> configuredModel) throws IOException {
        ObjectNode definition = normalize(request);
        String name = definition.path("name").asText();
        validateModel(definition, configuredModel);
        if (reserved(name)) throw new CatalogException(409,
                "Subagent name '" + name + "' is reserved by a built-in or config.yaml definition.");
        synchronized (lock) {
            Files.createDirectories(directory);
            Path temporary = temporary(definition);
            try {
                // Linking a complete same-directory file publishes atomically without replacement.
                // ATOMIC_MOVE alone is allowed to overwrite an existing target on some platforms.
                Files.createLink(file(name), temporary);
            } catch (FileAlreadyExistsException exception) {
                throw new CatalogException(409, "Managed subagent '" + name + "' already exists");
            } finally {
                AtomicMoves.deleteQuietly(temporary);
            }
            return response(definition, "managed", true);
        }
    }

    public ObjectNode update(String rawName, JsonNode patch, Predicate<String> configuredModel) throws IOException {
        String name = normalizeName(rawName);
        synchronized (lock) {
            ObjectNode definition = read(file(name));
            if (definition == null) throw missing(name);
            for (String field : FIELDS) {
                if (!field.equals("name") && patch.has(field)) definition.set(field, patch.get(field));
            }
            definition = normalize(definition);
            validateModel(definition, configuredModel);
            Path temporary = temporary(definition);
            try {
                // The same lock covers read/merge/replace and delete, avoiding stale read races.
                if (!Files.isRegularFile(file(name))) throw missing(name);
                AtomicMoves.replace(temporary, file(name));
            } finally {
                AtomicMoves.deleteQuietly(temporary);
            }
            return response(definition, "managed", true);
        }
    }

    public void delete(String rawName) throws IOException {
        String name = normalizeName(rawName);
        synchronized (lock) {
            if (!Files.isRegularFile(file(name)) || !Files.deleteIfExists(file(name))) throw missing(name);
        }
    }

    private Path file(String name) { return directory.resolve(name + ".json"); }

    private Path temporary(ObjectNode definition) throws IOException {
        Path temporary = Files.createTempFile(directory, ".subagent-", ".tmp");
        try {
            Files.write(temporary, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(definition));
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            return temporary;
        } catch (IOException exception) {
            AtomicMoves.deleteQuietly(temporary);
            throw exception;
        }
    }

    private List<ObjectNode> managed() throws IOException {
        if (!Files.exists(directory)) return List.of();
        List<ObjectNode> definitions = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                ObjectNode definition = read(path);
                if (definition != null) definitions.add(definition);
            }
        }
        return definitions;
    }

    private ObjectNode read(Path path) {
        if (!Files.isRegularFile(path)) return null;
        try {
            ObjectNode definition = normalize(json.readTree(path.toFile()));
            if (!path.getFileName().toString().equals(definition.path("name").asText() + ".json")) {
                throw new IllegalArgumentException("Definition identity differs from its file name");
            }
            return definition;
        } catch (IOException | RuntimeException exception) {
            // Do not put malformed content or prompts into logs.
            LOG.log(System.Logger.Level.WARNING, "Skipping invalid managed subagent file {0}", path.getFileName());
            return null;
        }
    }

    private ObjectNode response(ObjectNode definition, String source, boolean includePrompts) {
        ObjectNode result = definition.deepCopy();
        if (!includePrompts) result.putNull("system_prompt");
        result.put("source", source).put("editable", source.equals("managed"))
                .put("conflict", source.equals("managed") && reserved(definition.path("name").asText()));
        result.putObject("config_overrides");
        return result;
    }

    private ObjectNode normalize(JsonNode request) {
        if (request == null || !request.isObject()) throw fieldError("body", "Expected a JSON object");
        ObjectNode definition = json.createObjectNode();
        definition.put("name", normalizeName(requiredText(request, "name")));
        nullableText(request, definition, "display_name");
        definition.put("description", requiredText(request, "description").strip());
        definition.put("system_prompt", requiredText(request, "system_prompt").strip());
        names(request, definition, "tools", true);
        names(request, definition, "skills", true);
        names(request, definition, "disallowed_tools", false);
        ArrayNode denied = (ArrayNode) definition.get("disallowed_tools");
        for (String name : REQUIRED_DENIED) {
            boolean exists = false;
            for (JsonNode value : denied) if (value.asText().equals(name)) exists = true;
            if (!exists) denied.add(name);
        }
        definition.put("model", request.has("model") ? requiredText(request, "model") : "inherit");
        positiveInteger(request, definition, "max_turns", 50);
        positiveInteger(request, definition, "timeout_seconds", 900);
        if (request.has("enabled") && !request.get("enabled").isBoolean()) throw fieldError("enabled", "Must be boolean");
        definition.put("enabled", request.path("enabled").asBoolean(true));
        return definition;
    }

    private void nullableText(JsonNode request, ObjectNode target, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) target.putNull(field);
        else if (!value.isTextual()) throw fieldError(field, "Must be a string or null");
        else if (value.asText().strip().isEmpty()) target.putNull(field);
        else target.put(field, value.asText().strip());
    }

    private String requiredText(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || !value.isTextual() || value.asText().strip().isEmpty()) {
            throw fieldError(field, "Must be a nonblank string");
        }
        return value.asText();
    }

    private void names(JsonNode request, ObjectNode target, String field, boolean nullable) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) {
            if (nullable) target.putNull(field); else target.putArray(field);
            return;
        }
        if (!value.isArray()) throw fieldError(field, "Must be an array of strings" + (nullable ? " or null" : ""));
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (JsonNode entry : value) {
            if (!entry.isTextual() || entry.asText().strip().isEmpty()) throw fieldError(field, "Entries must be nonblank strings");
            normalized.add(entry.asText().strip());
        }
        ArrayNode array = target.putArray(field);
        normalized.forEach(array::add);
    }

    private void positiveInteger(JsonNode request, ObjectNode target, String field, int fallback) {
        JsonNode value = request.get(field);
        if (value == null) target.put(field, fallback);
        else if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) {
            throw fieldError(field, "Must be an integer greater than or equal to 1");
        } else target.put(field, value.intValue());
    }

    private void validateModel(ObjectNode definition, Predicate<String> configuredModel) {
        String model = definition.path("model").asText();
        if (!model.equals("inherit") && !configuredModel.test(model)) throw new CatalogException(422,
                "Unknown model '" + model + "'. Use 'inherit' or a configured model name.");
    }

    public static String normalizeName(String name) {
        if (name == null || !name.matches("^[A-Za-z0-9-]+$")) {
            throw new CatalogException(422, List.of(java.util.Map.of("loc", List.of("body", "name"),
                    "msg", "Name must match ^[A-Za-z0-9-]+$")));
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static boolean reserved(String name) { return name.equals("general-purpose"); }
    private static CatalogException missing(String name) { return new CatalogException(404, "Managed subagent '" + name + "' not found"); }
    private static CatalogException fieldError(String field, String message) {
        return new CatalogException(422, List.of(java.util.Map.of("loc", List.of("body", field), "msg", message)));
    }

    public static final class CatalogException extends RuntimeException {
        private final int status;
        private final Object detail;
        public CatalogException(int status, Object detail) { super("Subagent catalog request rejected"); this.status = status; this.detail = detail; }
        public int status() { return status; }
        public Object detail() { return detail; }
    }
}

package io.haifa.agent.superharness.server.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.superharness.workspace.AtomicMoves;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p>Complete messages, Run status/output and checkpoint payloads remain absent. Their sole
 * authority is the Haifa SQLite Runtime/Conversation store. The bounded interaction displays
 * below retain only SDK-confirmed safe card presentation and selected option for frozen UI
 * history; they never authorize an approval or replay a tool.
 */
public final class PersistentThreadCatalog {
    private static final int FORMAT_VERSION = 1;
    private static final long MAXIMUM_CATALOG_BYTES = 4L * 1024 * 1024;
    static final int MAX_INTERACTION_DISPLAYS_PER_THREAD = 64;
    static final int MAX_INTERACTION_DISPLAYS_PER_RUN = 8;
    static final int MAX_CLARIFICATION_DISPLAYS_PER_THREAD = 64;

    private final ObjectMapper json;
    private final Path catalog;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public PersistentThreadCatalog(ObjectMapper json, Path dataDirectory) throws IOException {
        this.json = json;
        Path ownerDirectory = dataDirectory.toAbsolutePath().normalize().resolve("owners").resolve("default");
        Files.createDirectories(ownerDirectory);
        this.catalog = ownerDirectory.resolve("threads.json");
        load();
    }

    public synchronized Optional<Entry> find(String threadId) {
        return Optional.ofNullable(entries.get(threadId));
    }

    public synchronized List<Entry> list() {
        return List.copyOf(entries.values());
    }

    public synchronized void save(Entry entry) throws IOException {
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
                    optionalText(item, "model_binding"),
                    readInteractionDisplays(item.path("interaction_displays")),
                    readClarificationDisplays(item.path("clarification_displays")),
                    optionalText(item, "owner_id").orElse("default"));
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
            ArrayNode displays = item.putArray("interaction_displays");
            for (InteractionDisplay display : entry.interactionDisplays()) {
                ObjectNode encoded = displays.addObject();
                encoded.put("runId", display.runId());
                encoded.put("requestId", display.requestId());
                encoded.put("requestSequence", display.requestSequence());
                encoded.put("createdAt", display.createdAt().toString());
                encoded.put("toolCallId", display.toolCallId());
                encoded.put("title", display.title());
                encoded.put("question", display.question());
                encoded.put("context", display.context());
                ArrayNode options = encoded.putArray("options");
                display.options().forEach(options::add);
                if (display.answeredOption() == null) encoded.putNull("answeredOption");
                else encoded.put("answeredOption", display.answeredOption());
            }
            ArrayNode clarifications = item.putArray("clarification_displays");
            for (ClarificationDisplay display : entry.clarificationDisplays()) {
                ObjectNode encoded = clarifications.addObject();
                encoded.put("runId", display.runId());
                encoded.put("requestId", display.requestId());
                encoded.put("requestSequence", display.requestSequence());
                encoded.put("createdAt", display.createdAt().toString());
                encoded.put("toolCallId", display.toolCallId());
                putNullable(encoded, "answeredKind", display.answeredKind());
                putNullable(encoded, "answeredOptionId", display.answeredOptionId());
                putNullable(encoded, "answeredValue", display.answeredValue());
                putNullable(encoded, "answeredRunId", display.answeredRunId());
            }
            if (!"default".equals(entry.ownerId())) item.put("owner_id", entry.ownerId());
        }
        byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        if (bytes.length > MAXIMUM_CATALOG_BYTES) {
            throw new IOException("thread catalog exceeds the configured size bound");
        }
        Path temporary = catalog.resolveSibling(catalog.getFileName() + ".tmp");
        try {
            Files.write(temporary, bytes);
            AtomicMoves.replace(temporary, catalog);
        } finally {
            AtomicMoves.deleteQuietly(temporary);
        }
    }

    private String requiredText(JsonNode node, String name) throws IOException {
        return optionalText(node, name).orElseThrow(() -> new IOException("thread catalog field is missing: " + name));
    }

    private static void putNullable(ObjectNode object, String name, String value) {
        if (value == null) object.putNull(name);
        else object.put(name, value);
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

    private List<InteractionDisplay> readInteractionDisplays(JsonNode node) throws IOException {
        if (node.isMissingNode()) return List.of();
        if (!node.isArray() || node.size() > MAX_INTERACTION_DISPLAYS_PER_THREAD) {
            throw new IOException("thread catalog interaction displays are invalid");
        }
        List<InteractionDisplay> displays = new ArrayList<>();
        try {
            for (JsonNode item : node) {
                List<String> options = new ArrayList<>();
                JsonNode rawOptions = item.path("options");
                if (!rawOptions.isArray() || rawOptions.size() > 4) {
                    throw new IllegalArgumentException("invalid options");
                }
                for (JsonNode option : rawOptions) {
                    if (!option.isTextual()) throw new IllegalArgumentException("invalid option");
                    options.add(option.asText());
                }
                displays.add(new InteractionDisplay(
                        requiredText(item, "runId"),
                        requiredText(item, "requestId"),
                        item.path("requestSequence").asLong(-1),
                        Instant.parse(requiredText(item, "createdAt")),
                        requiredText(item, "toolCallId"),
                        requiredText(item, "title"),
                        requiredText(item, "question"),
                        optionalText(item, "context").orElse(""),
                        options,
                        optionalText(item, "answeredOption").orElse(null)));
            }
            validateDisplays(displays);
        } catch (IllegalArgumentException failure) {
            throw new IOException("thread catalog interaction displays are invalid", failure);
        }
        return List.copyOf(displays);
    }

    private List<ClarificationDisplay> readClarificationDisplays(JsonNode node) throws IOException {
        if (node.isMissingNode()) return List.of();
        if (!node.isArray() || node.size() > MAX_CLARIFICATION_DISPLAYS_PER_THREAD) {
            throw new IOException("thread catalog clarification displays are invalid");
        }
        List<ClarificationDisplay> displays = new ArrayList<>();
        try {
            for (JsonNode item : node) {
                displays.add(new ClarificationDisplay(
                        requiredText(item, "runId"),
                        requiredText(item, "requestId"),
                        item.path("requestSequence").asLong(-1),
                        Instant.parse(requiredText(item, "createdAt")),
                        requiredText(item, "toolCallId"),
                        optionalText(item, "answeredKind").orElse(null),
                        optionalText(item, "answeredOptionId").orElse(null),
                        optionalText(item, "answeredValue").orElse(null),
                        optionalText(item, "answeredRunId").orElse(null)));
            }
            validateClarificationDisplays(displays);
        } catch (IllegalArgumentException failure) {
            throw new IOException("thread catalog clarification displays are invalid", failure);
        }
        return List.copyOf(displays);
    }

    public static void validateClarificationDisplays(List<ClarificationDisplay> displays) {
        if (displays.size() > MAX_CLARIFICATION_DISPLAYS_PER_THREAD) {
            throw new IllegalArgumentException("too many clarification displays");
        }
        java.util.Set<String> requestIds = new java.util.HashSet<>();
        long previousSequence = 0;
        for (ClarificationDisplay display : displays) {
            if (!requestIds.add(display.requestId()) || display.requestSequence() <= previousSequence) {
                throw new IllegalArgumentException("clarification display IDs or order are invalid");
            }
            previousSequence = display.requestSequence();
        }
    }

    public record ClarificationDisplay(
            String runId,
            String requestId,
            long requestSequence,
            Instant createdAt,
            String toolCallId,
            String answeredKind,
            String answeredOptionId,
            String answeredValue,
            String answeredRunId) {
        public ClarificationDisplay {
            if (runId == null || runId.isBlank() || runId.length() > 200
                    || requestId == null || requestId.isBlank() || requestId.length() > 200
                    || requestSequence < 1 || createdAt == null
                    || toolCallId == null || toolCallId.isBlank() || toolCallId.length() > 200) {
                throw new IllegalArgumentException("invalid clarification display");
            }
            boolean unanswered = answeredKind == null;
            if (!unanswered && (!"text".equals(answeredKind) && !"option".equals(answeredKind)
                    || answeredValue == null || answeredValue.isBlank() || answeredValue.length() > 8192
                    || answeredRunId == null || answeredRunId.isBlank() || answeredRunId.length() > 200)) {
                throw new IllegalArgumentException("invalid clarification display response");
            }
            if ("option".equals(answeredKind)
                    && (answeredOptionId == null || answeredOptionId.isBlank()
                            || answeredOptionId.length() > 200)) {
                throw new IllegalArgumentException("invalid clarification display option");
            }
        }

        public ClarificationDisplay answered(String kind, String optionId, String value, String runId) {
            return new ClarificationDisplay(runId(), requestId(), requestSequence(), createdAt(),
                    toolCallId(), kind, optionId, value, runId);
        }
    }

    public static void validateDisplays(List<InteractionDisplay> displays) {
        if (displays.size() > MAX_INTERACTION_DISPLAYS_PER_THREAD) {
            throw new IllegalArgumentException("too many interaction displays");
        }
        Map<String, Integer> perRun = new LinkedHashMap<>();
        java.util.Set<String> requestIds = new java.util.HashSet<>();
        long previousSequence = 0;
        for (InteractionDisplay display : displays) {
            if (!requestIds.add(display.requestId()) || display.requestSequence() <= previousSequence) {
                throw new IllegalArgumentException("interaction display IDs or order are invalid");
            }
            previousSequence = display.requestSequence();
            if (perRun.merge(display.runId(), 1, Integer::sum) > MAX_INTERACTION_DISPLAYS_PER_RUN) {
                throw new IllegalArgumentException("too many Run interaction displays");
            }
        }
    }

    public record InteractionDisplay(
            String runId,
            String requestId,
            long requestSequence,
            Instant createdAt,
            String toolCallId,
            String title,
            String question,
            String context,
            List<String> options,
            String answeredOption) {
        public InteractionDisplay {
            if (runId == null || runId.isBlank() || runId.length() > 200
                    || requestId == null || requestId.isBlank() || requestId.length() > 200
                    || requestSequence < 1 || createdAt == null
                    || toolCallId == null || toolCallId.isBlank() || toolCallId.length() > 200
                    || title == null || title.isBlank() || title.length() > 256
                    || question == null || question.isBlank() || question.length() > 8192
                    || context == null || context.length() > 2048
                    || options == null || options.isEmpty() || options.size() > 4
                    || options.stream().anyMatch(option -> option == null || option.isBlank()
                            || option.length() > 100)
                    || answeredOption != null && !options.contains(answeredOption)) {
                throw new IllegalArgumentException("invalid interaction display");
            }
            options = List.copyOf(options);
        }

        public InteractionDisplay answered(String option) {
            return new InteractionDisplay(runId, requestId, requestSequence, createdAt,
                    toolCallId, title, question, context, options, option);
        }
    }

    public record Entry(
            String threadId,
            Map<String, Object> metadata,
            Instant createdAt,
            Instant updatedAt,
            Optional<AgentSessionId> sessionId,
            Optional<String> modelBindingIdentity,
            List<InteractionDisplay> interactionDisplays,
            List<ClarificationDisplay> clarificationDisplays,
            String ownerId) {
        public Entry(String threadId, Map<String, Object> metadata, Instant createdAt, Instant updatedAt,
                Optional<AgentSessionId> sessionId, Optional<String> modelBindingIdentity) {
            this(threadId, metadata, createdAt, updatedAt, sessionId, modelBindingIdentity,
                    List.of(), List.of(), "default");
        }

        public Entry(String threadId, Map<String, Object> metadata, Instant createdAt, Instant updatedAt,
                Optional<AgentSessionId> sessionId, Optional<String> modelBindingIdentity,
                List<InteractionDisplay> interactionDisplays, List<ClarificationDisplay> clarificationDisplays) {
            this(threadId, metadata, createdAt, updatedAt, sessionId, modelBindingIdentity,
                    interactionDisplays, clarificationDisplays, "default");
        }

        public Entry {
            if (threadId == null || threadId.isBlank()) throw new IllegalArgumentException("threadId must not be blank");
            metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
            if (createdAt == null || updatedAt == null) throw new IllegalArgumentException("timestamps are required");
            if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not precede createdAt");
            sessionId = sessionId == null ? Optional.empty() : sessionId;
            modelBindingIdentity = modelBindingIdentity == null ? Optional.empty() : modelBindingIdentity;
            interactionDisplays = List.copyOf(interactionDisplays);
            validateDisplays(interactionDisplays);
            clarificationDisplays = List.copyOf(clarificationDisplays);
            validateClarificationDisplays(clarificationDisplays);
            if (ownerId == null || ownerId.isBlank()) ownerId = "default";
        }
    }
}

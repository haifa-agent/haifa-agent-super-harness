package io.haifa.agent.superharness.server.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.runtime.api.AgentPlanView;
import io.haifa.agent.runtime.api.TodoItemView;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads and validates frozen DeerFlow plan authoring prompt and tool description resources.
 */
public final class PlanAuthoringResources {
    public static final String MANIFEST_RESOURCE = "upstream/deerflow/tool-descriptions/manifest.json";
    public static final String TOOL_DESCRIPTION_RESOURCE = "upstream/deerflow/tool-descriptions/write_todos.txt";
    public static final String SYSTEM_PROMPT_RESOURCE = "upstream/deerflow/prompts/todo-list-system.txt";
    public static final String SYSTEM_PROMPT_HASH_RESOURCE = "upstream/deerflow/prompts/todo-list-system.txt.sha256";

    private static final System.Logger LOG = System.getLogger(PlanAuthoringResources.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    private record VerifiedPayload(String toolDescription, String systemPrompt) {}
    private static volatile VerifiedPayload verified;

    private PlanAuthoringResources() {}

    /**
     * Eagerly verifies frozen plan authoring resources on classpath.
     */
    public static void verifyResources() {
        loadAndVerify();
    }

    private static VerifiedPayload loadAndVerify() {
        VerifiedPayload local = verified;
        if (local != null) {
            return local;
        }
        synchronized (PlanAuthoringResources.class) {
            if (verified != null) {
                return verified;
            }
            byte[] manifestBytes = readResource(MANIFEST_RESOURCE);
            byte[] descBytes = readResource(TOOL_DESCRIPTION_RESOURCE);
            byte[] promptBytes = readResource(SYSTEM_PROMPT_RESOURCE);
            byte[] promptHashBytes = readResource(SYSTEM_PROMPT_HASH_RESOURCE);
            String expectedPromptHash = new String(promptHashBytes, StandardCharsets.US_ASCII).trim();

            verifyResources(manifestBytes, descBytes, promptBytes, expectedPromptHash);

            verified = new VerifiedPayload(
                    new String(descBytes, StandardCharsets.UTF_8),
                    new String(promptBytes, StandardCharsets.UTF_8));
            return verified;
        }
    }

    /**
     * Validates given resource bytes and hashes; fails fast on mismatch.
     */
    public static void verifyResources(
            byte[] manifestBytes, byte[] descBytes, byte[] promptBytes, String expectedPromptHash) {
        String expectedDescHash = extractToolHashFromManifest(manifestBytes, "write_todos");
        verifyHash(TOOL_DESCRIPTION_RESOURCE, descBytes, expectedDescHash, "write_todos tool description");
        verifyHash(SYSTEM_PROMPT_RESOURCE, promptBytes, expectedPromptHash, "todo-list system prompt");
    }

    public static String toolDescription() {
        return loadAndVerify().toolDescription();
    }

    public static String systemPrompt() {
        return loadAndVerify().systemPrompt();
    }

    static void verifyHash(String path, byte[] content, String expectedHash, String label) {
        String actualHash = sha256(content);
        String expected = expectedHash == null ? "" : expectedHash.trim().toLowerCase();
        if (!actualHash.equalsIgnoreCase(expected)) {
            throw new IllegalStateException(label + " hash mismatch at " + path
                    + " (expected " + expected + ", actual " + actualHash + ")");
        }
    }

    static String extractToolHashFromManifest(byte[] manifestBytes, String toolName) {
        try {
            JsonNode root = JSON.readTree(manifestBytes);
            JsonNode descriptions = root.path("descriptions");
            if (descriptions.isArray()) {
                for (JsonNode desc : descriptions) {
                    if (toolName.equals(desc.path("tool").asText())) {
                        String hash = desc.path("description_sha256").asText();
                        if (hash != null && !hash.isBlank()) {
                            return hash.trim();
                        }
                    }
                }
            }
            throw new IllegalStateException(
                    "Tool '" + toolName + "' not found in descriptions manifest: " + MANIFEST_RESOURCE);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to parse tool descriptions manifest: " + MANIFEST_RESOURCE, error);
        }
    }

    /**
     * Projects an authoritative SDK {@link AgentPlanView} into DeerFlow-compatible {@code todos}.
     * Returns {@link Optional#empty()} and logs a warning if any item has an unrecognized status.
     */
    public static Optional<List<Map<String, Object>>> projectTodos(
            AgentPlanView plan, String threadId, String runId) {
        if (plan == null) {
            return Optional.of(List.of());
        }
        List<Map<String, Object>> todos = new ArrayList<>();
        for (TodoItemView item : plan.items()) {
            String rawStatus = item.status();
            String status = normalizeStatus(rawStatus);
            if (status == null) {
                LOG.log(System.Logger.Level.WARNING,
                        "Unknown plan item status: thread_id={0}, run_id={1}, status={2}",
                        threadId, runId, rawStatus);
                return Optional.empty();
            }
            Map<String, Object> todo = new LinkedHashMap<>();
            todo.put("content", item.title());
            todo.put("status", status);
            todos.add(todo);
        }
        return Optional.of(List.copyOf(todos));
    }

    public static Optional<List<Map<String, Object>>> projectTodos(AgentPlanView plan) {
        return projectTodos(plan, "unknown", "unknown");
    }

    private static String normalizeStatus(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "PENDING", "pending" -> "pending";
            case "IN_PROGRESS", "in_progress" -> "in_progress";
            case "COMPLETED", "completed" -> "completed";
            default -> null;
        };
    }

    public static byte[] readResource(String path) {
        ClassLoader loader = PlanAuthoringResources.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Required plan authoring resource not found on classpath: " + path);
            }
            return stream.readAllBytes();
        } catch (IOException error) {
            throw new IllegalStateException("Failed to read plan authoring resource: " + path, error);
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}

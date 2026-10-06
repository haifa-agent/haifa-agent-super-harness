package io.haifa.agent.superharness.server.plan;

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
import java.util.Objects;

/**
 * Loads and validates frozen DeerFlow plan authoring prompt and tool description resources.
 */
public final class PlanAuthoringResources {
    private static final String TOOL_DESCRIPTION_RESOURCE = "upstream/deerflow/tool-descriptions/write_todos.txt";
    private static final String SYSTEM_PROMPT_RESOURCE = "upstream/deerflow/prompts/todo-list-system.txt";
    private static final String SYSTEM_PROMPT_HASH_RESOURCE = "upstream/deerflow/prompts/todo-list-system.txt.sha256";
    private static final String EXPECTED_TOOL_DESCRIPTION_SHA256 =
            "d23e05e338b031152b2e0289fea622994244347040af6b032c95ba9407b32d63";

    private static final String TOOL_DESCRIPTION;
    private static final String SYSTEM_PROMPT;

    static {
        byte[] descBytes = readResource(TOOL_DESCRIPTION_RESOURCE);
        String descHash = sha256(descBytes);
        if (!descHash.equalsIgnoreCase(EXPECTED_TOOL_DESCRIPTION_SHA256)) {
            throw new IllegalStateException("write_todos tool description hash mismatch at "
                    + TOOL_DESCRIPTION_RESOURCE + " (expected " + EXPECTED_TOOL_DESCRIPTION_SHA256
                    + ", actual " + descHash + ")");
        }
        TOOL_DESCRIPTION = new String(descBytes, StandardCharsets.UTF_8);

        byte[] promptBytes = readResource(SYSTEM_PROMPT_RESOURCE);
        byte[] expectedPromptHashBytes = readResource(SYSTEM_PROMPT_HASH_RESOURCE);
        String expectedPromptHash = new String(expectedPromptHashBytes, StandardCharsets.US_ASCII).trim();
        String promptHash = sha256(promptBytes);
        if (!promptHash.equalsIgnoreCase(expectedPromptHash)) {
            throw new IllegalStateException("todo-list system prompt hash mismatch at "
                    + SYSTEM_PROMPT_RESOURCE + " (expected " + expectedPromptHash
                    + ", actual " + promptHash + ")");
        }
        SYSTEM_PROMPT = new String(promptBytes, StandardCharsets.UTF_8);
    }

    private PlanAuthoringResources() {}

    public static String toolDescription() {
        return TOOL_DESCRIPTION;
    }

    public static String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    /**
     * Projects an authoritative SDK {@link AgentPlanView} into DeerFlow-compatible {@code todos}.
     */
    public static List<Map<String, Object>> projectTodos(AgentPlanView plan) {
        if (plan == null) {
            return List.of();
        }
        List<Map<String, Object>> todos = new ArrayList<>();
        for (TodoItemView item : plan.items()) {
            String status = switch (item.status()) {
                case "PENDING", "pending" -> "pending";
                case "IN_PROGRESS", "in_progress" -> "in_progress";
                case "COMPLETED", "completed" -> "completed";
                default -> throw new IllegalStateException("Unexpected SDK plan item status: '" + item.status() + "'");
            };
            Map<String, Object> todo = new LinkedHashMap<>();
            todo.put("content", item.title());
            todo.put("status", status);
            todos.add(todo);
        }
        return List.copyOf(todos);
    }

    private static byte[] readResource(String path) {
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

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}

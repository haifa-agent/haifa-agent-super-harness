package io.haifa.agent.superharness.server.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.runtime.api.AgentPlanView;
import io.haifa.agent.runtime.api.TodoItemView;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlanAuthoringResourcesTest {

    @Test
    void classpathResourcesValidateSuccessfully() {
        PlanAuthoringResources.verifyResources();
        assertThat(PlanAuthoringResources.toolDescription())
                .startsWith("Use this tool to create and manage a structured task list");
        assertThat(PlanAuthoringResources.systemPrompt()).contains("todo");
    }

    @Test
    void resourceHashMismatchFailsFastWithDescriptivePathAndHashes() {
        byte[] validManifest = PlanAuthoringResources.readResource(PlanAuthoringResources.MANIFEST_RESOURCE);
        byte[] validDesc = PlanAuthoringResources.readResource(PlanAuthoringResources.TOOL_DESCRIPTION_RESOURCE);
        byte[] validPrompt = PlanAuthoringResources.readResource(PlanAuthoringResources.SYSTEM_PROMPT_RESOURCE);
        String validPromptHash = new String(
                PlanAuthoringResources.readResource(PlanAuthoringResources.SYSTEM_PROMPT_HASH_RESOURCE),
                StandardCharsets.US_ASCII).trim();

        // Valid bytes succeed
        PlanAuthoringResources.verifyResources(validManifest, validDesc, validPrompt, validPromptHash);

        // Corrupted tool description
        byte[] tamperedDesc = "corrupted tool description content".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> PlanAuthoringResources.verifyResources(
                validManifest, tamperedDesc, validPrompt, validPromptHash))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("write_todos tool description hash mismatch at "
                        + PlanAuthoringResources.TOOL_DESCRIPTION_RESOURCE)
                .hasMessageContaining("expected")
                .hasMessageContaining("actual");

        // Corrupted system prompt
        byte[] tamperedPrompt = "corrupted prompt content".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> PlanAuthoringResources.verifyResources(
                validManifest, validDesc, tamperedPrompt, validPromptHash))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("todo-list system prompt hash mismatch at "
                        + PlanAuthoringResources.SYSTEM_PROMPT_RESOURCE)
                .hasMessageContaining("expected")
                .hasMessageContaining("actual");
    }

    @Test
    void manifestExtractsHashDynamicallyAndFailsOnMissingTool() {
        byte[] validManifest = PlanAuthoringResources.readResource(PlanAuthoringResources.MANIFEST_RESOURCE);
        String hash = PlanAuthoringResources.extractToolHashFromManifest(validManifest, "write_todos");
        assertThat(hash).isEqualTo("d23e05e338b031152b2e0289fea622994244347040af6b032c95ba9407b32d63");

        assertThatThrownBy(() -> PlanAuthoringResources.extractToolHashFromManifest(validManifest, "non_existent_tool"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Tool 'non_existent_tool' not found in descriptions manifest");
    }

    @Test
    void projectTodosProjectsRecognizedStatuses() {
        AgentPlanView plan = planWithItems(List.of(
                todoItem("Task 1", "PENDING"),
                todoItem("Task 2", "in_progress"),
                todoItem("Task 3", "COMPLETED")));

        Optional<List<Map<String, Object>>> todos = PlanAuthoringResources.projectTodos(plan, "thread-1", "run-1");
        assertThat(todos).isPresent();
        List<Map<String, Object>> list = todos.get();
        assertThat(list).hasSize(3);
        assertThat(list.get(0)).containsEntry("content", "Task 1").containsEntry("status", "pending");
        assertThat(list.get(1)).containsEntry("content", "Task 2").containsEntry("status", "in_progress");
        assertThat(list.get(2)).containsEntry("content", "Task 3").containsEntry("status", "completed");
    }

    @Test
    void projectTodosGracefullyOmitsUnknownStatusWithoutCrashing() {
        AgentPlanView planWithUnknownStatus = planWithItems(List.of(
                todoItem("Task 1", "pending"),
                todoItem("Unrecognized task", "BLOCKED")));

        // Must return Optional.empty() instead of throwing IllegalStateException, allowing 200 OK responses
        Optional<List<Map<String, Object>>> todos =
                PlanAuthoringResources.projectTodos(planWithUnknownStatus, "thread-err", "run-err");
        assertThat(todos).isEmpty();
    }

    private static TodoItemView todoItem(String title, String status) {
        return new TodoItemView(
                "item-id",
                title,
                "description",
                "medium",
                List.of(),
                status,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static AgentPlanView planWithItems(List<TodoItemView> items) {
        Instant now = Instant.now();
        return new AgentPlanView("plan-1", "run-1", "test objective", items, 1L, now, now);
    }
}

package io.haifa.agent.superharness.server.channels.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FeishuRunProjectorTest {

    @Test
    void projectsToolStartFinishArtifactsAndTodosWithRateLimitBypassForTests() throws Exception {
        List<String> progress = new ArrayList<>();
        FeishuRunProjector projector = new FeishuRunProjector(progress::add, 0L, 24);

        projector.event("messages", FeishuRunProjector.toolRequestFrame("write_file", "call-1"), "1-0");
        projector.event(
                "messages",
                FeishuRunProjector.toolResultFrame("write_file", "call-1", "wrote /mnt/user-data/outputs/a.txt"),
                "2-0");
        projector.event(
                "updates",
                Map.of("__artifacts__", Map.of("artifacts", List.of("/mnt/user-data/outputs/a.txt"))),
                null);
        projector.event(
                "updates",
                Map.of(
                        "tools",
                        Map.of(
                                "todos",
                                List.of(
                                        Map.of("content", "Draft outline", "status", "completed"),
                                        Map.of("content", "Write file", "status", "in_progress")))),
                null);
        projector.event("custom", Map.of("type", "task_started", "task_id", "task-abc"), null);
        projector.event("end", null, null);

        assertThat(projector.sawEnd()).isTrue();
        assertThat(projector.toolProgressCount()).isGreaterThanOrEqualTo(2);
        assertThat(projector.artifacts()).containsExactly("/mnt/user-data/outputs/a.txt");
        assertThat(projector.todoLines()).anyMatch(line -> line.contains("Draft outline"));
        assertThat(projector.subagentStarts()).isEqualTo(1);
        assertThat(progress.size()).isGreaterThanOrEqualTo(2);
        assertThat(progress).anyMatch(line -> line.contains("write_file"));
        assertThat(FeishuRunProjector.artifactHints("thread-1", projector.artifacts()))
                .contains("/api/threads/thread-1/artifacts/mnt/user-data/outputs/a.txt");
        assertThat(FeishuRunProjector.todoAppendix(projector.todoLines())).contains("待办清单");
    }
}

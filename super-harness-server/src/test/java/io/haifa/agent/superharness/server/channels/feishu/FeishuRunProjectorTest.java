package io.haifa.agent.superharness.server.channels.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Test
    void artifactHintsUrlEncodeReservedPathSegments() {
        String hints = FeishuRunProjector.artifactHints(
                "thread-1", List.of("/mnt/user-data/outputs/report#1.txt"));
        assertThat(hints).contains("`/mnt/user-data/outputs/report#1.txt`");
        assertThat(hints)
                .contains("/api/threads/thread-1/artifacts/mnt/user-data/outputs/report%231.txt")
                .doesNotContain("/artifacts/mnt/user-data/outputs/report#1.txt");
        assertThat(FeishuRunProjector.encodeArtifactPath("/mnt/user-data/outputs/a?b=%c.txt"))
                .isEqualTo("/mnt/user-data/outputs/a%3Fb%3D%25c.txt");
    }

    @Test
    void recordsReplayGapSoTheTurnCanFallBackToTheDurableState() throws Exception {
        FeishuRunProjector projector = new FeishuRunProjector(summary -> {}, 0L, 24);

        projector.event(
                "gap",
                Map.of("code", "stream_replay_gap", "run_id", "run-1", "recovery", "reload_durable_state"),
                null);

        assertThat(projector.sawGap()).isTrue();
        assertThat(projector.sawEnd()).isFalse();
        assertThat(projector.sawError()).isFalse();
        assertThat(projector.gapDiagnostics()).containsExactly("stream_replay_gap");
    }

    @Test
    void keepsGapDiagnosticsBounded() throws Exception {
        FeishuRunProjector projector = new FeishuRunProjector(summary -> {}, 0L, 24);

        for (int index = 0; index < FeishuRunProjector.MAX_GAP_DIAGNOSTICS + 3; index++) {
            projector.event("gap", Map.of("code", "stream_replay_gap", "attempt", index), null);
        }

        assertThat(projector.gapDiagnostics()).hasSize(FeishuRunProjector.MAX_GAP_DIAGNOSTICS);
        assertThat(projector.sawGap()).isTrue();
    }

    @Test
    void sharedToolScopeKeepsReplayedToolCardsToOncePerThread() throws Exception {
        Set<String> scope = new LinkedHashSet<>();

        List<String> firstTurn = new ArrayList<>();
        FeishuRunProjector turn1 = new FeishuRunProjector(firstTurn::add, 0L, 24, scope);
        turn1.event("messages", FeishuRunProjector.toolRequestFrame("execution_run", "call-1"), "1-0");
        turn1.event("messages", FeishuRunProjector.toolResultFrame("execution_run", "call-1", "ok"), "2-0");

        // A later Turn observes the same historical tool frames when a pending card is re-emitted.
        List<String> secondTurn = new ArrayList<>();
        FeishuRunProjector turn2 = new FeishuRunProjector(secondTurn::add, 0L, 24, scope);
        turn2.event("messages", FeishuRunProjector.toolRequestFrame("execution_run", "call-1"), "1-0");
        turn2.event("messages", FeishuRunProjector.toolResultFrame("execution_run", "call-1", "ok"), "2-0");

        assertThat(firstTurn).anyMatch(line -> line.contains("execution_run"));
        assertThat(secondTurn).isEmpty();
        assertThat(turn2.toolProgressCount()).isGreaterThanOrEqualTo(1);
    }
}

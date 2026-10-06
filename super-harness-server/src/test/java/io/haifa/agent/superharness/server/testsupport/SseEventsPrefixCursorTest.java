package io.haifa.agent.superharness.server.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Direct-cause evidence for the missing {@code id:} flake: the disconnected-prefix wait used by
 * {@code interleavedChildrenKeepIndependentPersistentCursorsAcrossConcurrentAppends} is satisfied by
 * an id-less Parent tool <em>request</em> {@code messages} frame plus four id-less subtask
 * {@code custom} frames, so it can return before any durable cursor frame arrives.
 */
class SseEventsPrefixCursorTest {
    private static final Predicate<SseEvents> PREFIX_REACHED = events -> !events.named("messages").isEmpty()
            && events.named("custom").stream()
                    .filter(event -> "task_running".equals(event.data().path("type").asText())).count() >= 4;

    @Test
    void prefixWaitCompletesWithoutAnyDurableCursorWhenOnlyIdlessFramesArrived() throws Exception {
        String idless = frame("messages", null,
                "[{\"type\":\"tool\",\"name\":\"list_uploaded_files\"},\"agent\"]")
                + taskRunning(1) + taskRunning(2) + taskRunning(3) + taskRunning(4);

        var prefix = SseEvents.readUntil(stream(idless), PREFIX_REACHED, Duration.ofSeconds(2));

        assertThat(prefix.lastEventId())
                .as("the real wait predicate is already true while every frame is id-less")
                .isEmpty();
    }

    @Test
    void prefixWaitCarriesTheCursorOnceTheDurableParentFrameArrives() throws Exception {
        String withCursor = frame("messages", "7-0",
                "[{\"type\":\"tool\",\"name\":\"list_uploaded_files\"},\"agent\"]")
                + taskRunning(1) + taskRunning(2) + taskRunning(3) + taskRunning(4);

        var prefix = SseEvents.readUntil(stream(withCursor), PREFIX_REACHED, Duration.ofSeconds(2));

        assertThat(prefix.lastEventId()).contains("7-0");
    }

    private static ByteArrayInputStream stream(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String taskRunning(int index) {
        return frame("custom", null,
                "{\"type\":\"task_running\",\"task_id\":\"task-" + index + "\",\"message_index\":" + index + "}");
    }

    private static String frame(String event, String id, String data) {
        return "event: " + event + "\n" + (id == null ? "" : "id: " + id + "\n") + "data: " + data + "\n\n";
    }
}

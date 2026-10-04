package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DeerFlowSubtaskStreamQueryTest {
    @Test void childlessCustomStreamDoesNotQuerySdkForEveryTenMillisecondIteration() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        AtomicLong time = new AtomicLong(-1_000_000_000L);
        try (var stream = new DeerFlowSubtaskStream(null, new AgentRunId("query-parent"), () -> {
            queries.incrementAndGet(); return List.of();
        }, time::get)) {
            assertThat(stream.drain(false, event -> { throw new AssertionError("Childless Run emitted a Child frame"); })).isTrue();
            for (int iteration = 1; iteration < 10; iteration++) {
                time.addAndGet(Duration.ofMillis(10).toNanos());
                assertThat(stream.drain(false, event -> { throw new AssertionError("Childless Run emitted a Child frame"); })).isFalse();
            }
            assertThat(queries).hasValue(1);
            time.addAndGet(Duration.ofMillis(10).toNanos());
            assertThat(stream.drain(false, event -> { throw new AssertionError("Childless Run emitted a Child frame"); })).isTrue();
            assertThat(queries).hasValue(2);
        }
    }

    @Test void parentTerminalAlwaysReadsFreshChildrenDespiteDiscoveryBackoff() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        try (var stream = new DeerFlowSubtaskStream(null, new AgentRunId("terminal-parent"), () -> {
            queries.incrementAndGet(); return List.of();
        }, () -> 1000L)) {
            assertThat(stream.drain(false, event -> {})).isTrue();
            assertThat(stream.drain(false, event -> {})).isFalse();
            assertThat(stream.drain(true, event -> {})).isTrue();
            assertThat(stream.drain(true, event -> {})).isTrue();
            assertThat(queries).hasValue(3);
        }
    }
}

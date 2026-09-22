package io.haifa.agent.superharness.events;

import io.haifa.agent.runtime.api.AgentRunViewSnapshot;
import java.util.List;

public sealed interface ReplayResult permits ReplayResult.Events, ReplayResult.Gap {
    record Events(List<ProjectedRunEvent> events, ProjectionCursor head) implements ReplayResult {
        public Events {
            events = List.copyOf(events);
        }
    }

    record Gap(ProjectionGap gap, AgentRunViewSnapshot authoritativeView) implements ReplayResult {}
}

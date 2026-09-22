package io.haifa.agent.superharness.events;

import io.haifa.agent.runtime.api.AgentRunViewSnapshot;

public sealed interface AppendResult permits AppendResult.Accepted, AppendResult.Gap {
    record Accepted(ProjectedRunEvent event, boolean duplicate) implements AppendResult {}

    record Gap(ProjectionGap gap, AgentRunViewSnapshot authoritativeView) implements AppendResult {}
}

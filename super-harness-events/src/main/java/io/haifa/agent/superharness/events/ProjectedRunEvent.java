package io.haifa.agent.superharness.events;

import io.haifa.agent.runtime.api.AgentRunEvent;
import java.util.Optional;

public record ProjectedRunEvent(
        StableEventKey key,
        ProjectionCursor cursor,
        AgentRunEvent source,
        RunTerminalKind terminalKind,
        Optional<ProjectedInteraction> interaction) {}

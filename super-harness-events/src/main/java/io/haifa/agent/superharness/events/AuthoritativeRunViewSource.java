package io.haifa.agent.superharness.events;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.AgentRunViewSnapshot;
import java.util.Optional;

@FunctionalInterface
public interface AuthoritativeRunViewSource {
    Optional<AgentRunViewSnapshot> load(AgentRunId runId);
}

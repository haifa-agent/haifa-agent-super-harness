package io.haifa.agent.superharness.events;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.AgentRunViewSnapshot;
import io.haifa.agent.sdk.api.AgentRuns;
import java.util.Objects;
import java.util.Optional;

public final class SdkAuthoritativeRunViewSource implements AuthoritativeRunViewSource {
    private final AgentRuns runs;

    public SdkAuthoritativeRunViewSource(AgentRuns runs) {
        this.runs = Objects.requireNonNull(runs, "runs");
    }

    @Override
    public Optional<AgentRunViewSnapshot> load(AgentRunId runId) {
        return runs.view(Objects.requireNonNull(runId, "runId"));
    }
}

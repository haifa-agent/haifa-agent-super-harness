package io.haifa.agent.superharness.server;

import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** The frozen bootstrap handshake, scoped to the declaration's validated agent identity. */
public final class ProductionSetupAgentTool
        implements JavaTool<ProductionSetupAgentTool.Input, ProductionSetupAgentTool.Output> {
    private static final JavaToolSpec<Input, Output> SPEC = JavaToolSpec.builder("setup_agent", Input.class, Output.class)
            .title("Set up the current agent")
            .description(ToolDescriptions.load("setup_agent"))
            .sideEffects(ToolSideEffect.FILE_WRITE)
            .build();
    private final String agentName;
    private final Function<Input, Output> write;

    ProductionSetupAgentTool(String agentName, Function<Input, Output> write) {
        this.agentName = agentName;
        this.write = Objects.requireNonNull(write, "write");
    }

    @Override
    public JavaToolSpec<Input, Output> spec() {
        return SPEC;
    }

    static void requireOwner(JavaToolContext context) {
        if (!"default-public-user".equals(context.principal().principalId())
                || !"user".equals(context.principal().principalType())) {
            throw new IllegalArgumentException("agent configuration requires the local Thread owner");
        }
        context.cancellation().throwIfCancellationRequested();
        if (!Instant.now().isBefore(context.deadline())) {
            throw new IllegalStateException("agent configuration deadline has expired");
        }
        if (context.sessionId().isEmpty()) {
            throw new IllegalStateException("agent configuration requires a trusted Session identity");
        }
    }

    @Override
    public Output invoke(Input input, JavaToolContext context) {
        Objects.requireNonNull(input, "input");
        requireOwner(Objects.requireNonNull(context, "context"));
        if (agentName == null || agentName.isBlank()) return new Output(false, "Error: no current agent_name");
        if (input.soul() == null || input.soul().isBlank()) {
            return new Output(false, "Error: soul content is empty; refusing to create agent with an empty SOUL.md");
        }
        if (ClarificationBoundaryRegistry.active(context.runId().value())) {
            return new Output(false, ClarificationBoundaryRegistry.DISCARD_MESSAGE);
        }
        try {
            return write.apply(input);
        } catch (RuntimeException rejected) {
            // Neither user-authored SOUL nor an arbitrary callback exception crosses the tool boundary.
            return new Output(false, "Error: agent setup rejected; verify installed skills and the rendered prompt limit");
        }
    }

    @Override
    public String summarize(Output output) {
        return output.message();
    }

    public record Input(String soul, String description, Optional<List<String>> skills) {
        public Input {
            skills = skills == null ? Optional.empty() : skills.map(List::copyOf);
        }
    }

    public record Output(boolean success, String message) {}
}

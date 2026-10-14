package io.haifa.agent.superharness.server.tool.agent;

import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.superharness.server.tool.ToolDescriptions;
import io.haifa.agent.superharness.server.tool.clarification.ClarificationBoundaryRegistry;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Self updates only; unsupported capability fields are rejected by the strict typed schema. */
public final class ProductionUpdateAgentTool
        implements JavaTool<ProductionUpdateAgentTool.Input, ProductionUpdateAgentTool.Output> {
    private static final JavaToolSpec<Input, Output> SPEC = JavaToolSpec.builder("update_agent", Input.class, Output.class)
            .title("Update the current agent")
            .description(ToolDescriptions.load("update_agent"))
            .sideEffects(ToolSideEffect.FILE_WRITE)
            .build();
    private final String agentName;
    private final Function<Input, Output> write;
    private final BooleanSupplier configAdmin;

    public ProductionUpdateAgentTool(String agentName, Function<Input, Output> write) {
        this(agentName, write, io.haifa.agent.superharness.server.auth.AccessOwner::admin);
    }

    public ProductionUpdateAgentTool(String agentName, Function<Input, Output> write, BooleanSupplier configAdmin) {
        this.agentName = agentName;
        this.write = Objects.requireNonNull(write, "write");
        this.configAdmin = Objects.requireNonNull(configAdmin, "configAdmin");
    }

    @Override
    public JavaToolSpec<Input, Output> spec() {
        return SPEC;
    }

    @Override
    public Output invoke(Input input, JavaToolContext context) {
        Objects.requireNonNull(input, "input");
        ProductionSetupAgentTool.requireOwner(Objects.requireNonNull(context, "context"));
        if (!configAdmin.getAsBoolean()) return new Output(false, "Error: Admin privileges are required.");
        if (agentName == null || agentName.isBlank()) return new Output(false, "Error: no current custom agent_name");
        if (input.soul().isPresent() && input.soul().orElseThrow().isBlank()) {
            return new Output(false, "Error: soul content is empty; omit soul to keep it unchanged");
        }
        if (input.soul().isEmpty() && input.description().isEmpty() && input.display_name().isEmpty()) {
            return new Output(false, "Error: no supported fields provided; use soul, description or display_name");
        }
        if (ClarificationBoundaryRegistry.active(context.runId().value())) {
            return new Output(false, ClarificationBoundaryRegistry.DISCARD_MESSAGE);
        }
        try {
            return write.apply(input);
        } catch (RuntimeException rejected) {
            return new Output(false, "Error: agent update rejected; only soul, description and display_name within the rendered prompt limit are supported");
        }
    }

    @Override
    public String summarize(Output output) {
        return output.message();
    }

    public record Input(Optional<String> soul, Optional<String> description, Optional<String> display_name) {
        public Input {
            soul = optionalText(soul);
            description = optionalText(description);
            display_name = optionalText(display_name);
        }

        private static Optional<String> optionalText(Optional<String> value) {
            return value == null ? Optional.empty() : value.filter(text ->
                    !java.util.Set.of("null", "none", "undefined").contains(text.trim().toLowerCase(java.util.Locale.ROOT)));
        }
    }

    public record Output(boolean success, String message) {}
}

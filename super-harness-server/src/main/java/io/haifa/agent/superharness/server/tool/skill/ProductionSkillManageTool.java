package io.haifa.agent.superharness.server.tool.skill;

import io.haifa.agent.sdk.tool.*;
import io.haifa.agent.superharness.server.config.SkillConfigStore;
import io.haifa.agent.superharness.server.tool.ToolDescriptions;
import io.haifa.agent.superharness.server.tool.clarification.ClarificationBoundaryRegistry;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.time.Instant;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Frozen user-package operations; the SDK owns execution and Approval. */
public final class ProductionSkillManageTool implements JavaTool<ProductionSkillManageTool.Input, ProductionSkillManageTool.Output> {
    private static final JavaToolSpec<Input, Output> SPEC = JavaToolSpec.builder("skill_manage", Input.class, Output.class)
            .title("Manage custom skills").description(ToolDescriptions.load("skill_manage"))
            .sideEffects(ToolSideEffect.FILE_WRITE).build();
    private final SkillConfigStore store;
    private final String threadId;
    private final BooleanSupplier configAdmin;
    public ProductionSkillManageTool(SkillConfigStore store, String threadId) {
        this(store, threadId, io.haifa.agent.superharness.server.auth.AccessOwner::admin);
    }
    public ProductionSkillManageTool(SkillConfigStore store, String threadId, BooleanSupplier configAdmin) {
        this.store = store;
        this.threadId = threadId;
        this.configAdmin = configAdmin == null ? io.haifa.agent.superharness.server.auth.AccessOwner::admin : configAdmin;
    }
    @Override public JavaToolSpec<Input, Output> spec() { return SPEC; }

    static void requireOwner(JavaToolContext context) {
        if (!"default-public-user".equals(context.principal().principalId()) || !"user".equals(context.principal().principalType())
                || context.sessionId().isEmpty()) throw new IllegalArgumentException("Skill tools require the local Thread owner");
        context.cancellation().throwIfCancellationRequested();
        if (!Instant.now().isBefore(context.deadline())) throw new IllegalStateException("Skill tool deadline expired");
    }

    @Override public Output invoke(Input input, JavaToolContext context) {
        requireOwner(context);
        if (!configAdmin.getAsBoolean()) return new Output(false, "Error: Admin privileges are required.");
        if (ClarificationBoundaryRegistry.active(context.runId().value())) return new Output(false, ClarificationBoundaryRegistry.DISCARD_MESSAGE);
        try {
            Runnable admission = () -> {
                context.cancellation().throwIfCancellationRequested();
                if (!Instant.now().isBefore(context.deadline())) throw new IllegalStateException("Skill tool deadline expired");
            };
            store.manage(input.action().name(), input.name(), value(input.content()), value(input.path()),
                    value(input.find()), value(input.replace()), input.expected_count().orElse(null), "agent", threadId, admission);
            return new Output(true, "Skill '" + input.name() + "': " + input.action() + " completed. Subsequent Runs can discover the published version.");
        } catch (java.io.IOException | RuntimeException rejected) {
            return new Output(false, "Error: Skill operation rejected; check package validity, patch counts and configured security screening.");
        }
    }
    private static String value(Optional<String> value) { return value.orElse(null); }
    @Override public String summarize(Output output) { return output.message(); }
    public enum Action { create, patch, edit, delete, write_file, remove_file }
    public record Input(Action action, String name, Optional<String> content, Optional<String> path,
            Optional<String> find, Optional<String> replace, Optional<Integer> expected_count) {
        public Input {
            content = content == null ? Optional.empty() : content; path = path == null ? Optional.empty() : path;
            find = find == null ? Optional.empty() : find; replace = replace == null ? Optional.empty() : replace;
            expected_count = expected_count == null ? Optional.empty() : expected_count;
        }
    }
    public record Output(boolean success, String message) {}
}

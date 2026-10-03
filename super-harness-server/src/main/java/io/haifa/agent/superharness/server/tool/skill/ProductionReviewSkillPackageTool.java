package io.haifa.agent.superharness.server.tool.skill;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.sdk.tool.*;
import io.haifa.agent.superharness.server.config.*;
import io.haifa.agent.superharness.server.tool.ToolDescriptions;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Inspects bytes through frozen diagnostics. Reviewed code is never a Tool or Skill source. */
public final class ProductionReviewSkillPackageTool implements JavaTool<ProductionReviewSkillPackageTool.Input, ProductionReviewSkillPackageTool.Output> {
    private static final JavaToolSpec<Input, Output> SPEC = JavaToolSpec.builder("review_skill_package", Input.class, Output.class)
            .title("Review a skill package").description(ToolDescriptions.load("review_skill_package"))
            .sideEffects(ToolSideEffect.FILE_READ).build();
    private final SkillConfigStore store;
    private final SkillPackageScreening scanner;
    private final Function<String, Map<String, byte[]>> readThreadTarget;
    public ProductionReviewSkillPackageTool(SkillConfigStore store, SkillPackageScreening scanner, Function<String, Map<String, byte[]>> readThreadTarget) {
        this.store = store; this.scanner = scanner; this.readThreadTarget = readThreadTarget;
    }
    @Override public JavaToolSpec<Input, Output> spec() { return SPEC; }
    @Override public Output invoke(Input input, JavaToolContext context) {
        ProductionSkillManageTool.requireOwner(context);
        try {
            String target = input.target();
            Map<String, byte[]> files;
            if (target.startsWith("skill://")) {
                String[] uri = target.substring(8).split("/", 2);
                if (uri.length != 2) throw new IllegalArgumentException("Installed review URI requires category and relative path");
                files = store.installedReviewFiles(uri[0], uri[1]);
            }
            else if (target.equals("inline://SKILL.md")) files = Map.of("SKILL.md", input.inline_content().orElseThrow().getBytes(StandardCharsets.UTF_8));
            else if (target.startsWith("/mnt/user-data/")) files = readThreadTarget.apply(target);
            else throw new IllegalArgumentException("Review requires an installed Skill, inline content or a Thread archive");
            JsonNode result = scanner.inspect(files, target, input.profile().orElse("deerflow"),
                    input.include_content().orElse("semantic-review"), input.scope().orElse(List.of("all")));
            String serialized = result.toString();
            List<String> chunks = new java.util.ArrayList<>();
            for (int start = 0; start < serialized.length();) {
                // Keep each UTF-8 segment inside both the public record codec and Tool view budgets.
                // The whole JSON remains bounded by the scanner's 1 MiB output limit.
                int end = Math.min(start + 4_096, serialized.length());
                if (end < serialized.length() && Character.isHighSurrogate(serialized.charAt(end - 1))) end--;
                chunks.add(serialized.substring(start, end)); start = end;
            }
            return new Output(List.copyOf(chunks), "");
        } catch (java.io.IOException | RuntimeException rejected) {
            return new Output(List.of(), "Error: review unavailable; check the target, review options and static scanner prerequisites.");
        }
    }
    @Override public String summarize(Output output) {
        return output.error().isEmpty() ? "Static Skill review completed. Target remained untrusted and was not executed or activated."
                : output.error();
    }
    public record Input(String target, Optional<String> profile, Optional<String> include_content,
            Optional<List<String>> scope, Optional<String> inline_content) {
        public Input {
            profile = profile == null ? Optional.empty() : profile; include_content = include_content == null ? Optional.empty() : include_content;
            scope = scope == null ? Optional.empty() : scope; inline_content = inline_content == null ? Optional.empty() : inline_content;
        }
    }
    public record Output(List<String> review, String error) {}
}

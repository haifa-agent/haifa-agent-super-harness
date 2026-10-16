package io.haifa.agent.superharness.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.sdk.api.SdkCaller;
import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.skill.api.SkillContent;
import io.haifa.agent.skill.api.SkillVisibilityContext;
import io.haifa.agent.skill.api.SkillContentLoader;
import io.haifa.agent.starter.SkillScriptPlatforms;
import io.haifa.agent.tool.core.ToolDefinitionCanonicalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real trusted skill script execution through the public entry: entry-file execution with bun,
 * cross-directory relative imports, the prepared version directory and the workspace outputs/
 * artifact. The driver-level bipartisan binding is assembled from the platform's own registration
 * rows, exactly as the ToolAssembly does when the Bridge registers it.
 */
public class SkillScriptInvocationTest {

    @Test
    void executesTheDeclaredEntryAndPublishesTheOutput(@TempDir Path temporary) throws Exception {
        var bun = SkillScriptToolConfig.bunExecutable(
                System.getenv(SkillScriptToolConfig.BUN_ENVIRONMENT_KEY));
        org.junit.jupiter.api.Assumptions.assumeTrue(bun != null,
                "the declared bun runtime is required for the real execution probe");
        SkillRunTools.Projection projection = SkillScriptProjections.projection(true);
        FrozenSkillBinding skillBinding = SkillScriptToolConfig.declaredBinding(projection).orElseThrow();
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path dataRoot = Files.createDirectories(temporary.resolve("data"));
        var caller = SdkCaller.defaultPublicUser();

        try (var platform = SkillScriptPlatforms.trusted(
                new SkillScriptPlatforms.SkillScriptSpec(
                        workspace,
                        dataRoot,
                        List.of(new SkillScriptPlatforms.SkillScriptRuntime(
                                SkillScriptToolConfig.SCRIPT_RUNTIME_REF, bun)),
                        projection.loader(),
                        List.of(new SkillScriptPlatforms.SkillScriptToolDeclaration(
                                SkillScriptToolConfig.toolName(),
                                SkillScriptToolConfig.title(),
                                SkillScriptToolConfig.description(),
                                SkillScriptToolConfig.inputSchema(),
                                Duration.ofSeconds(600),
                                skillBinding,
                                SkillScriptToolConfig.SCRIPT_RELATIVE_PATH,
                                SkillScriptToolConfig.SCRIPT_RUNTIME_REF,
                                Duration.ofSeconds(120),
                                SkillScriptToolConfig.purpose(),
                                new SkillScriptToolConfig.ImageGenArguments(SkillScriptToolConfig.DEFAULT_PROVIDER, workspace))),
                        Duration.ofSeconds(120),
                        Duration.ofSeconds(600),
                        262144,
                        5000),
                caller.tenant(),
                caller.principal())) {
            var registration = platform.registrations().getFirst();
            var coordinates = new io.haifa.agent.tool.api.ToolCoordinate(
                    registration.definition().name(),
                    registration.definition().version(),
                    registration.definition().providerId(),
                    new ToolDefinitionCanonicalizer().hash(registration.definition()));
            var binding = new FrozenToolBinding(
                    registration.alias(),
                    coordinates,
                    registration.definition(),
                    registration.providerBindingReference(),
                    "test-catalog-digest");
            var provider = registration.provider();
            ToolResult result = provider.invoke(new io.haifa.agent.tool.api.ToolInvocationRequest(
                    binding,
                    new ToolCallId("in-process-probe"),
                    new AgentRunId("in-process-probe"),
                    caller.tenant(),
                    caller.principal(),
                    new ToolArguments(
                            SkillScriptToolConfig.TOOL_NAME + "-input", "1.0.0",
                            Map.of("prompt", "lighthouse at dusk", "image_name", "probe.png")),
                    Instant.now().plus(Duration.ofSeconds(300)),
                    java.util.Optional.empty(),
                    new io.haifa.agent.tool.api.ToolCancellation() {
                        @Override public boolean isCancellationRequested() { return false; }
                    },
                    Map.of()));
            String wire = result.summary() == null ? "" : result.summary().toString();
            System.out.println("TOOLRESULT " + wire);
            assertThat(wire).contains("Script exited (exit 0)");
            assertThat(wire).contains("W04_WROTE ");
            assertThat(Files.isRegularFile(workspace.resolve("outputs/probe.png"))).isTrue();
            // The prepared frozen package directory is reused and stays byte-stable.
            Path trustedDirectory = workspace.resolve(".haifa-trusted-skills");
            assertThat(Files.isDirectory(trustedDirectory)).isTrue();
            try (var entries = Files.list(trustedDirectory)) {
                assertThat(entries.count()).isEqualTo(1);
            }
            // The relative import reached outside scripts/: the prepared directory carries it.
            boolean libraryPresent = false;
            try (var walk = Files.walk(trustedDirectory)) {
                libraryPresent = walk.anyMatch(path -> path.getFileName() != null
                        && path.getFileName().toString().equals("artifact.ts"));
            }
            assertThat(libraryPresent)
                    .as("the multi-file package materializes its relative imports")
                    .isTrue();
        }
    }

    private record UnusedRelayPlaceholder() {}
}

package io.haifa.agent.superharness.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.haifa.agent.execution.core.tool.ExecutionToolDefinitionFactory;
import io.haifa.agent.sdk.api.SdkCaller;
import io.haifa.agent.sdk.contribution.ToolRegistration;
import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.starter.SkillScriptPlatforms;
import io.haifa.agent.starter.HostExecutionPlatforms;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The declared Tool assembles through the public SDK entry exactly once, keeps the product boundary
 * on its schema and carries the canonical execution result contract (never a self-declared schema).
 * The bun executable is the declared product runtime; without it the declaration fails closed, so
 * the runtime assembly is exercised only on hosts that declare it.
 */
class SkillScriptPlatformTest {

    @Test
    void assemblesTheDeclaredToolWhenTheRuntimeIsDeclared(@TempDir Path temporary) throws Exception {
        assumeTrue(SkillScriptToolConfig.bunExecutable(
                System.getenv(SkillScriptToolConfig.BUN_ENVIRONMENT_KEY)) != null,
                "the trusted skill script Tool only assembles with a declared bun runtime; this host keeps the Tool off");
        Path bun = SkillScriptToolConfig.bunExecutable(System.getenv(SkillScriptToolConfig.BUN_ENVIRONMENT_KEY));
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path dataRoot = Files.createDirectories(temporary.resolve("data"));

        SkillRunTools.Projection projection = SkillScriptProjections.projection(true);
        FrozenSkillBinding skillBinding = SkillScriptToolConfig.declaredBinding(projection).orElseThrow();
        var config = SkillScriptToolConfig.fromEnvironment(
                environmentFor(bun.toAbsolutePath().toString()),
                workspace, dataRoot, projection).orElseThrow();
        var caller = SdkCaller.defaultPublicUser();

        try (var platform = SkillScriptPlatforms.trusted(
                new SkillScriptPlatforms.SkillScriptSpec(
                        workspace,
                        dataRoot,
                        List.of(new SkillScriptPlatforms.SkillScriptRuntime(
                                SkillScriptToolConfig.SCRIPT_RUNTIME_REF, config.bunExecutable())),
                        projection.loader(),
                        List.of(new SkillScriptPlatforms.SkillScriptToolDeclaration(
                                SkillScriptToolConfig.toolName(),
                                SkillScriptToolConfig.title(),
                                SkillScriptToolConfig.description(),
                                SkillScriptToolConfig.inputSchema(),
                                config.maximumTimeout(),
                                skillBinding,
                                SkillScriptToolConfig.SCRIPT_RELATIVE_PATH,
                                SkillScriptToolConfig.SCRIPT_RUNTIME_REF,
                                config.defaultTimeout(),
                                SkillScriptToolConfig.purpose(),
                                new SkillScriptToolConfig.ImageGenArguments())),
                        config.defaultTimeout(),
                        config.maximumTimeout(),
                        config.maximumOutputBytes(),
                        config.maximumOutputLines()),
                caller.tenant(),
                caller.principal())) {
            assertThat(platform.toolNames()).containsExactly(SkillScriptToolConfig.TOOL_NAME);
            List<ToolRegistration> registrations = platform.registrations();
            assertThat(registrations).hasSize(1);
            ToolRegistration registration = registrations.getFirst();
            assertThat(registration.alias().value()).isEqualTo(SkillScriptToolConfig.TOOL_NAME);
            assertThat(registration.definition().name().value()).isEqualTo(SkillScriptToolConfig.TOOL_NAME);
            assertThat(registration.definition().timeout()).isEqualTo(config.maximumTimeout());
            // Product boundary: the model may provide business arguments only.
            var properties = (java.util.Map<String, Object>) registration.definition().inputSchema().document().get("properties");
            assertThat(properties).containsKeys("prompt", "image_name", "help", "sleep_millis");
            assertThat(properties.keySet()).doesNotContain("runtime", "language", "interpreter", "script",
                    "workdir", "cwd", "workingDirectory", "workspaceId", "args");
            // The output contract is the canonical execution result schema, never self-declared.
            var declaredOutput = ExecutionToolDefinitionFactory.executionResultSchema();
            assertThat(registration.definition().outputSchema().id()).isEqualTo(declaredOutput.id());
            assertThat(registration.definition().outputSchema().document()).isEqualTo(declaredOutput.document());
            assertThat(platform.securitySummary()).isNotBlank();
        }
    }

    @Test
    void hostGuardedEntryKeepsItsOwnShapeUnchanged(@TempDir Path temporary) throws Exception {
        // Regression pin against the host execution entry the skill script entry rode on.
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path dataRoot = Files.createDirectories(temporary.resolve("data"));
        var caller = SdkCaller.defaultPublicUser();
        try (var platform = HostExecutionPlatforms.hostGuarded(
                new HostExecutionPlatforms.HostExecutionSpec(
                        workspace, dataRoot, Duration.ofMillis(120_000), Duration.ofMillis(600_000), 262_144, 5_000),
                caller.tenant(),
                caller.principal())) {
            assertThat(platform.toolNames()).containsExactly("execution_run");
        }
    }

    private static java.util.Map<String, String> environmentFor(String bunPath) {
        java.util.Map<String, String> environment = new java.util.LinkedHashMap<>();
        environment.put(HostShellToolConfig.ENVIRONMENT_KEY, "on");
        environment.put(SkillScriptToolConfig.BUN_ENVIRONMENT_KEY, bunPath);
        return environment;
    }
}

package io.haifa.agent.superharness.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Product facts of the trusted Skill script declaration, including every fail-closed branch. */
class SkillScriptToolConfigTest {

    private static Map<String, String> environment(String bunPath) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put(HostShellToolConfig.ENVIRONMENT_KEY, "on");
        if (bunPath != null) environment.put(SkillScriptToolConfig.BUN_ENVIRONMENT_KEY, bunPath);
        return environment;
    }

    @Test
    void defaultOffKeepsTheToolSurfaceEmpty() {
        assertThat(SkillScriptToolConfig.fromEnvironment(
                Map.of(), Path.of("/tmp").toAbsolutePath(), Path.of("/tmp").toAbsolutePath(),
                SkillScriptProjections.projection(true)))
                .isEmpty();
        assertThat(SkillScriptToolConfig.toolNames(Map.of(), SkillScriptProjections.projection(true))).isEmpty();
    }

    @Test
    void undeclaredOrInvalidBunFailsClosed(@TempDir Path temporary) throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path dataRoot = Files.createDirectories(temporary.resolve("data"));
        var projection = SkillScriptProjections.projection(true);

        assertThat(SkillScriptToolConfig.fromEnvironment(environment(null), workspace, dataRoot, projection)).isEmpty();
        assertThat(SkillScriptToolConfig.fromEnvironment(environment(" "), workspace, dataRoot, projection)).isEmpty();
        // A relative path can never widen the surface, and a missing absolute one fails closed.
        assertThat(SkillScriptToolConfig.fromEnvironment(environment("bun"), workspace, dataRoot, projection)).isEmpty();
        assertThat(SkillScriptToolConfig.fromEnvironment(environment(
                "D:" + java.io.File.separator + "no-such-bun" + java.io.File.separator + "bun.exe"),
                workspace, dataRoot, projection)).isEmpty();
        assertThat(SkillScriptToolConfig.toolNames(environment(null), projection)).isEmpty();
    }

    @Test
    void bindsTheDirectoryFormAndRejectsForeignSkills(@TempDir Path temporary) throws Exception {
        Path bun = Files.createDirectories(temporary.resolve("bun-home"));
        Files.writeString(bun.resolve("bun.exe"), "stub");
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path dataRoot = Files.createDirectories(temporary.resolve("data"));

        var config = SkillScriptToolConfig.fromEnvironment(
                environment(bun.toAbsolutePath().toString()), workspace, dataRoot,
                SkillScriptProjections.projection(true)).orElseThrow();
        assertThat(config.bunExecutable()).isEqualTo(bun.resolve("bun.exe"));
        assertThat(config.workspaceRoot()).isEqualTo(workspace);
        assertThat(SkillScriptToolConfig.toolNames(
                environment(bun.toAbsolutePath().toString()), SkillScriptProjections.projection(true)))
                .containsExactly(SkillScriptToolConfig.TOOL_NAME);

        // An admitted Skill without the declared entry never passes the gate.
        assertThat(SkillScriptToolConfig.fromEnvironment(
                environment(bun.toAbsolutePath().toString()), workspace, dataRoot,
                SkillScriptProjections.projection(false))).isEmpty();

        // A Skill file form of the bun path is accepted verbatim.
        var configFromFile = SkillScriptToolConfig.fromEnvironment(
                environment(bun.resolve("bun.exe").toAbsolutePath().toString()), workspace, dataRoot,
                SkillScriptProjections.projection(true)).orElseThrow();
        assertThat(configFromFile.bunExecutable()).isEqualTo(bun.resolve("bun.exe"));
    }

    @Test
    void declaredBoundsCarryTheProductTimeouts() throws Exception {
        Path bun = Files.createTempDirectory("bun-home");
        Files.writeString(bun.resolve("bun.exe"), "stub");
        var config = SkillScriptToolConfig.fromEnvironment(
                environment(bun.toAbsolutePath().toString()),
                bun, bun, SkillScriptProjections.projection(true)).orElseThrow();
        assertThat(config.defaultTimeout()).isEqualTo(Duration.ofMillis(600_000));
        assertThat(config.maximumTimeout()).isEqualTo(Duration.ofMillis(600_000));
        assertThat(config.maximumOutputBytes()).isEqualTo(HostShellToolConfig.MAXIMUM_OUTPUT_BYTES);
        assertThat(config.maximumOutputLines()).isEqualTo(HostShellToolConfig.MAXIMUM_OUTPUT_LINES);
    }

    @Test
    void mapperFixesProviderImageAndRejectsBounds() {
        var mapper = new SkillScriptToolConfig.ImageGenArguments(SkillScriptToolConfig.DEFAULT_PROVIDER);
        var mapped = mapper.map(Map.of(
                "prompt", "a lighthouse",
                "image_name", "w04-artifact.png",
                "sleep_millis", 500));
        assertThat(mapped.argv()).containsExactly(
                "--provider", "codex-cli",
                "--image", "outputs/w04-artifact.png",
                "--prompt", "a lighthouse",
                "--sleep", "500");
        assertThat(mapped.workingDirectory()).isEqualTo(".");

        var help = mapper.map(Map.of("help", true));
        assertThat(help.argv()).containsExactly("--help");

        assertThatThrownBy(() -> mapper.map(Map.of("prompt", "x", "image_name", "../escape.png")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outputs");
        assertThatThrownBy(() -> mapper.map(Map.of("prompt", "x", "image_name", "sub/way.png")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.map(Map.of("prompt", "x", "runtime", "python")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown argument");
        assertThatThrownBy(() -> mapper.map(Map.of("image_name", "a.png")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prompt");
        assertThatThrownBy(() -> mapper.map(new LinkedHashMap<>(Map.of(
                "prompt", "x", "image_name", "a.png", "sleep_millis", 700_000))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sleep_millis");
    }

    /**
     * The image Provider is a deployment fact: the declared value reaches the fixed argv, blank
     * falls back to the product default, and any value outside the Skill's bounded Provider set
     * keeps the whole Tool off instead of routing to an unintended Provider.
     */
    @Test
    void configuredProviderReachesTheArgvAndUnknownValuesFailClosed(@TempDir Path temporary) throws Exception {
        Path bun = Files.createDirectories(temporary.resolve("bun-home"));
        Files.writeString(bun.resolve("bun.exe"), "stub");
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path dataRoot = Files.createDirectories(temporary.resolve("data"));

        Map<String, String> configured = environment(bun.toAbsolutePath().toString());
        configured.put(SkillScriptToolConfig.PROVIDER_ENVIRONMENT_KEY, "DashScope");
        var config = SkillScriptToolConfig.fromEnvironment(
                configured, workspace, dataRoot, SkillScriptProjections.projection(true)).orElseThrow();
        assertThat(config.provider()).isEqualTo("dashscope");
        assertThat(new SkillScriptToolConfig.ImageGenArguments(config.provider()).map(Map.of(
                "prompt", "a lighthouse", "image_name", "w04.png")).argv())
                .containsExactly("--provider", "dashscope", "--image", "outputs/w04.png",
                        "--prompt", "a lighthouse");

        // A blank value keeps the product default; an unknown value turns the Tool off.
        assertThat(SkillScriptToolConfig.provider("  ")).contains(SkillScriptToolConfig.DEFAULT_PROVIDER);
        assertThat(SkillScriptToolConfig.provider("codex-cli")).contains("codex-cli");

        Map<String, String> unknown = environment(bun.toAbsolutePath().toString());
        unknown.put(SkillScriptToolConfig.PROVIDER_ENVIRONMENT_KEY, "not-a-provider");
        assertThat(SkillScriptToolConfig.fromEnvironment(
                unknown, workspace, dataRoot, SkillScriptProjections.projection(true))).isEmpty();
        assertThat(SkillScriptToolConfig.toolNames(unknown, SkillScriptProjections.projection(true))).isEmpty();
    }

    /**
     * The layout a packaged bun ships with on most hosts: the declared path is a link to the real
     * runtime. The SDK entry admits the declaration only when it names an exact file, so the
     * declaration follows the link instead of handing the SDK a path its preflight rejects at Run
     * admission (which failed every Run while the Tool was declared).
     */
    @Test
    void followsASymlinkedBunToTheExactFile(@TempDir Path temporary) throws Exception {
        Path home = Files.createDirectories(temporary.resolve("bun-home"));
        Path executable = home.resolve("bun.exe");
        Files.writeString(executable, "stub");
        Path fileLink = SymbolicLinks.create(temporary.resolve("bun"), executable);
        Path directoryLink = SymbolicLinks.create(temporary.resolve("bun-home-link"), home);
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path dataRoot = Files.createDirectories(temporary.resolve("data"));

        var config = SkillScriptToolConfig.fromEnvironment(
                environment(fileLink.toAbsolutePath().toString()), workspace, dataRoot,
                SkillScriptProjections.projection(true)).orElseThrow();
        assertThat(config.bunExecutable()).isEqualTo(executable.toAbsolutePath().normalize());
        assertThat(Files.isSymbolicLink(config.bunExecutable())).isFalse();
        assertThat(SkillScriptToolConfig.toolNames(
                environment(fileLink.toAbsolutePath().toString()), SkillScriptProjections.projection(true)))
                .containsExactly(SkillScriptToolConfig.TOOL_NAME);

        // The directory form follows a linked directory the same way.
        assertThat(SkillScriptToolConfig.bunExecutable(directoryLink.toAbsolutePath().toString()))
                .isEqualTo(executable.toAbsolutePath().normalize());
    }

    /** A link that names nothing keeps the Tool off instead of admitting an unusable runtime. */
    @Test
    void unresolvedSymbolicLinkKeepsTheRuntimeUndeclared(@TempDir Path temporary) throws Exception {
        Path link = SymbolicLinks.create(
                temporary.resolve("broken-bun"), temporary.resolve("missing-bun.exe"));
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path dataRoot = Files.createDirectories(temporary.resolve("data"));

        assertThat(SkillScriptToolConfig.fromEnvironment(
                environment(link.toAbsolutePath().toString()), workspace, dataRoot,
                SkillScriptProjections.projection(true))).isEmpty();
        assertThat(SkillScriptToolConfig.toolNames(
                environment(link.toAbsolutePath().toString()), SkillScriptProjections.projection(true)))
                .isEmpty();
    }
}

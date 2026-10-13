package io.haifa.agent.superharness.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.ChildRunCapacity;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.superharness.server.config.HostShellToolConfig;
import io.haifa.agent.superharness.server.config.McpRunTools;
import io.haifa.agent.superharness.server.config.SubagentRunConfiguration;
import io.haifa.agent.superharness.server.prompt.AgentPromptContext;
import io.haifa.agent.superharness.server.testsupport.ControlledModel;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-E (narrowed, human-approved): the model cannot select a workspace or working directory. The
 * declared Tool has no {@code workdir}/{@code cwd} and the Run fixes the working directory to the
 * Run workspace, so a model that supplies a working directory is rejected before the Sandbox. The
 * host shell is not a filesystem boundary - an accepted, disclosed parity limitation with the frozen
 * DeerFlow {@code sandbox.allow_host_bash} - so this case does not claim path confinement.
 */
public class HostShellToolBoundaryTest {
    public record Surface() {}

    @Test
    void rejectsAModelSelectedWorkingDirectoryBeforeExecution(@TempDir Path temporary) throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        var config = HostShellToolConfig.fromEnvironment(
                        Map.of(HostShellToolConfig.ENVIRONMENT_KEY, "on"), workspace, temporary)
                .orElseThrow();
        var model = new ControlledModel((request, index) -> index == 1
                ? ControlledModel.call(
                        request,
                        "execution_run",
                        Map.of(
                                "mode", "COMMAND",
                                "content", "echo boundary-probe",
                                "purpose", "working directory boundary probe",
                                "timeoutMillis", 60_000,
                                "workdir", "../outside-the-workspace"))
                : ControlledModel.stop(request));

        try (var foundation = new DurableHaifaFoundation(
                        temporary.resolve("foundation"), new SecretKeySpec(new byte[32], "AES"));
                var bridge = bridge(LocalProbeModelTestSupport.binding(model), foundation, config)) {
            var started = bridge.start(
                            "thread-boundary-" + System.nanoTime(), "Run the host shell tool", Optional.empty());
            awaitTerminal(bridge, started.runId());

            List<String> events = eventTypes(bridge, started.runId());
            assertThat(events)
                    .as("events=" + events)
                    .contains("tool.call.requested")
                    .doesNotContain(
                            "interaction.requested",
                            "tool.call.started",
                            "tool.call.succeeded",
                            "tool.call.completed");
        }
    }

    private static AgentRunStatus awaitTerminal(HaifaRunBridge bridge, AgentRunId runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            AgentRunStatus status = bridge.snapshot(runId).status();
            if (status.isTerminal()) {
                return status;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("the boundary probe never reached a terminal status");
    }

    private static List<String> eventTypes(HaifaRunBridge bridge, AgentRunId runId) {
        return bridge.events(runId, bridge.beforeFirstEvent(runId), 256).items().stream()
                .map(AgentRunEvent::eventType)
                .toList();
    }

    private static HaifaRunBridge bridge(
            HaifaRunBridge.ModelBinding binding, DurableHaifaFoundation foundation, HostShellToolConfig hostShell) {
        var delegation = SubagentRunConfiguration.disabled();
        return new HaifaRunBridge(
                binding,
                promptTools(),
                foundation,
                McpRunTools.Projection.empty(),
                null,
                AgentPromptContext.defaults(),
                delegation,
                new ChildRunCapacity(delegation.settings().runningCapacity()),
                false,
                null,
                hostShell,
                null);
    }

    private static List<? extends JavaTool<?, ?>> promptTools() {
        return Stream.of("read_file", "write_file", "str_replace", "present_files", "list_uploaded_files",
                        "grep", "web_search", "web_fetch")
                .map(HostShellToolBoundaryTest::namedTool)
                .toList();
    }

    private static JavaTool<Surface, Surface> namedTool(String name) {
        var spec = JavaToolSpec.builder(name, Surface.class, Surface.class)
                .description("Surface fixture")
                .pure()
                .build();
        return new JavaTool<>() {
            @Override
            public JavaToolSpec<Surface, Surface> spec() {
                return spec;
            }

            @Override
            public Surface invoke(Surface input, JavaToolContext context) {
                throw new AssertionError("Controlled model must not call fixture tools");
            }
        };
    }
}

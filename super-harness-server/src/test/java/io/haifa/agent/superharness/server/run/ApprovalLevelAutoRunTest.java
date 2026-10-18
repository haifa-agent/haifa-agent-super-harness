package io.haifa.agent.superharness.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.ChildRunCapacity;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.superharness.server.config.ApprovalPolicyConfig;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-D sibling of {@link HostShellToolApprovalTest}: the same host shell Tool under
 * {@code HAIFA_SUPER_HARNESS_APPROVAL_MODE=auto} runs without raising an approval Interaction.
 * The level only changes the policy data; the SDK still brokers, sandboxes and audits the call.
 */
public class ApprovalLevelAutoRunTest {
    public record Surface() {}

    @Test
    void runsTheHostShellToolWithoutAnApprovalWhenTheLevelIsAuto(@TempDir Path temporary) throws Exception {
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
                                "content", "echo haifa-auto-approval-probe",
                                "purpose", "auto approval probe",
                                "timeoutMillis", 60_000))
                : ControlledModel.stop(request));

        try (var foundation = new DurableHaifaFoundation(
                        temporary.resolve("foundation"), new SecretKeySpec(new byte[32], "AES"));
                var bridge = bridge(LocalProbeModelTestSupport.binding(model), foundation, config)) {
            var started = bridge.start(
                    "thread-auto-approval-" + System.nanoTime(), "Run the host shell tool", Optional.empty());
            var status = awaitTerminal(bridge, started.runId());

            assertThat(status.isTerminal()).isTrue();
            assertThat(bridge.pendingInteraction(started.runId())).isEmpty();
            List<String> events = eventTypes(bridge, started.runId());
            assertThat(events)
                    .doesNotContain("interaction.requested", "interaction.responded")
                    .contains("tool.call.succeeded");
        }
    }

    private static io.haifa.agent.core.run.AgentRunStatus awaitTerminal(
            HaifaRunBridge bridge, AgentRunId runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            var status = bridge.snapshot(runId).status();
            if (status.isTerminal()) {
                return status;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("the auto-approval run never reached a terminal status: events="
                + eventTypes(bridge, runId).stream().collect(Collectors.joining(" | ")));
    }

    private static List<String> eventTypes(HaifaRunBridge bridge, AgentRunId runId) {
        var page = bridge.events(runId, bridge.beforeFirstEvent(runId), 256);
        return page.items().stream().map(AgentRunEvent::eventType).toList();
    }

    private static HaifaRunBridge bridge(
            HaifaRunBridge.ModelBinding binding,
            DurableHaifaFoundation foundation,
            HostShellToolConfig hostShell) {
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
                null,
                null,
                ApprovalPolicyConfig.Mode.AUTO);
    }

    private static List<? extends JavaTool<?, ?>> promptTools() {
        return Stream.of("read_file", "write_file", "str_replace", "present_files", "list_uploaded_files",
                        "grep", "web_search", "web_fetch")
                .map(ApprovalLevelAutoRunTest::namedTool)
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

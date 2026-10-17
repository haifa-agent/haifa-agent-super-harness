package io.haifa.agent.superharness.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.ChildRunCapacity;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionKind;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.InteractionView;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T-D: one execution_run call waits for one approval and a rejection fails closed. */
public class HostShellToolApprovalTest {
    public record Surface() {}

    @Test
    void waitsForApprovalAndFailsClosedWhenRejected(@TempDir Path temporary) throws Exception {
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
                                "content", "echo haifa-approval-probe",
                                "purpose", "approval probe",
                                "timeoutMillis", 60_000))
                : ControlledModel.stop(request));

        try (var foundation = new DurableHaifaFoundation(
                        temporary.resolve("foundation"), new SecretKeySpec(new byte[32], "AES"));
                var bridge = bridge(LocalProbeModelTestSupport.binding(model), foundation, config)) {
            var started = bridge.start("thread-approval-" + System.nanoTime(), "Run the host shell tool", Optional.empty());
            InteractionView pending = awaitApproval(bridge, started.runId());

            assertThat(pending.kind()).isEqualTo(InteractionKind.APPROVAL);
            assertThat(pending.state()).isEqualTo(InteractionState.PENDING);
            assertThat(pending.allowedActions()).contains(InteractionAction.APPROVE, InteractionAction.REJECT);

            // respondedAt must not precede the store's receivedAt. Under full-suite LowMemory
            // forks Instant.now() can briefly lag the interaction stamp (SqliteStoreException:
            // "receivedAt must not precede respondedAt").
            Instant respondedAt = Instant.now();
            if (!respondedAt.isAfter(pending.createdAt())) {
                respondedAt = pending.createdAt().plusMillis(1);
            }
            var receipt = bridge.respond(new InteractionResponseSubmission(
                    new InteractionResponseId("host-shell-denial"),
                    pending.requestId(),
                    started.runId(),
                    pending.revision(),
                    InteractionAction.REJECT,
                    List.of(),
                    "host-shell-denial-idempotency",
                    respondedAt));

            assertThat(receipt.runId()).isEqualTo(started.runId());
            AgentRunStatus terminal = awaitTerminal(bridge, started.runId());

            assertThat(terminal).isNotNull();
            assertThat(bridge.pendingInteraction(started.runId())).isEmpty();
            // Fail closed: the denied command never reaches the Sandbox, so the Run shows the
            // Approval round trip but no started/succeeded/completed Tool call.
            assertThat(eventTypes(bridge, started.runId()))
                    .contains("interaction.requested", "interaction.responded")
                    .doesNotContain("tool.call.started", "tool.call.succeeded", "tool.call.completed");
        }
    }

    private static InteractionView awaitApproval(HaifaRunBridge bridge, AgentRunId runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        AgentRunStatus status = bridge.snapshot(runId).status();
        while (System.currentTimeMillis() < deadline) {
            var pending = bridge.pendingInteraction(runId);
            if (pending.isPresent()) {
                return pending.orElseThrow();
            }
            status = bridge.snapshot(runId).status();
            if (status.isTerminal()) {
                break;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("no approval request: status=" + status + " events=" + describe(bridge, runId));
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
        throw new AssertionError("the denied run never reached a terminal status");
    }

    private static String describe(HaifaRunBridge bridge, AgentRunId runId) {
        var page = bridge.events(runId, bridge.beforeFirstEvent(runId), 256);
        return page.items().stream()
                .map(AgentRunEvent::eventType)
                .collect(Collectors.joining(" | "));
    }

    private static List<String> eventTypes(HaifaRunBridge bridge, AgentRunId runId) {
        var page = bridge.events(runId, bridge.beforeFirstEvent(runId), 256);
        return page.items().stream().map(AgentRunEvent::eventType).toList();
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
                .map(HostShellToolApprovalTest::namedTool)
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
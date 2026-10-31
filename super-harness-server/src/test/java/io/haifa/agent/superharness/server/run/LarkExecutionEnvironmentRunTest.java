package io.haifa.agent.superharness.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.ChildRunCapacity;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.ToolCallView;
import io.haifa.agent.runtime.api.ToolResultView;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.superharness.server.config.ExecutionProvider;
import io.haifa.agent.superharness.server.config.ExecutionProviderConfig;
import io.haifa.agent.superharness.server.config.McpRunTools;
import io.haifa.agent.superharness.server.config.SubagentRunConfiguration;
import io.haifa.agent.superharness.server.integrations.lark.LarkIntegrationService;
import io.haifa.agent.superharness.server.integrations.lark.LarkSkillPackInstaller;
import io.haifa.agent.superharness.server.prompt.AgentPromptContext;
import io.haifa.agent.superharness.server.testsupport.ControlledModel;
import io.haifa.agent.superharness.server.testsupport.LarkIntegrationFakes;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * LARK-04 behavioral subset through the public SDK: a real {@code execution_run} child runs with
 * the trusted owner overlay captured by the bridge, and approval, rejection, timeout, cancellation
 * and output bounds are owned by the SDK runtime rather than by a product execution engine.
 */
public class LarkExecutionEnvironmentRunTest {
    public record Surface() {}

    private static final String FIXTURE = "io.haifa.agent.superharness.server.testsupport.LarkExecutionFixture";

    @TempDir
    Path temporary;

    @Test
    void runsEachOwnersChildWithTheSameDirectoriesTheGatewayUses() throws Exception {
        Path dataRoot = Files.createDirectories(temporary.resolve("data"));
        var service = larkService(dataRoot);
        var provider = hostProvider(dataRoot);
        Path managed = dataRoot.resolve("integrations").resolve("lark").resolve("gateway-cli")
                .resolve("node_modules").resolve("@larksuite").resolve("cli").resolve("bin")
                .resolve(isWindows() ? "lark-cli.exe" : "lark-cli");
        Files.createDirectories(managed.getParent());
        Files.writeString(managed, "");

        for (String owner : List.of("owner-a", "owner-b")) {
            Path marker = temporary.resolve("markers").resolve(owner + ".txt");
            var outcome = runWithApproval(owner, provider, fixtureCommand("env", marker),
                    InteractionAction.APPROVE, 60_000);

            assertThat(outcome.status().isTerminal()).isTrue();
            assertThat(outcome.events()).contains("tool.call.succeeded");
            String recorded = Files.readString(marker);
            Path expectedConfig = service.userConfigDirectory(owner);
            Path expectedData = service.userDataDirectory(owner);
            assertThat(recorded)
                    .contains("OWNER_CONFIG=" + expectedConfig)
                    .contains("OWNER_DATA=" + expectedData)
                    .contains("NO_UPDATE_NOTIFIER=1")
                    .contains("PATH_FIRST=" + managed.getParent())
                    .contains("HOME_PRESENT=true");

            Map<String, String> runOverlay =
                    LarkIntegrationService.hostExecutionEnvironment(dataRoot, owner);
            Map<String, String> gatewayOverlay = service.userExecutionEnvironment(owner);
            assertThat(runOverlay.get("LARKSUITE_CLI_CONFIG_DIR"))
                    .isEqualTo(gatewayOverlay.get("LARKSUITE_CLI_CONFIG_DIR"))
                    .isEqualTo(expectedConfig.toString());
            assertThat(runOverlay.get("LARKSUITE_CLI_DATA_DIR"))
                    .isEqualTo(gatewayOverlay.get("LARKSUITE_CLI_DATA_DIR"))
                    .isEqualTo(expectedData.toString());
            assertThat(runOverlay).doesNotContainKeys("HOME", "TEMP", "TMP", "SystemRoot", "USERPROFILE");
        }

        Path first = temporary.resolve("markers").resolve("owner-a.txt");
        Path second = temporary.resolve("markers").resolve("owner-b.txt");
        assertThat(firstLineOf(first)).isNotEqualTo(firstLineOf(second));
    }

    @Test
    void rejectingTheApprovalNeverLaunchesTheChild() throws Exception {
        Path dataRoot = Files.createDirectories(temporary.resolve("data"));
        var provider = hostProvider(dataRoot);
        Path marker = temporary.resolve("markers").resolve("denied.txt");

        var outcome = runWithApproval("owner-a", provider, fixtureCommand("env", marker),
                InteractionAction.REJECT, 60_000);

        assertThat(outcome.status().isTerminal()).isTrue();
        assertThat(outcome.events())
                .contains("interaction.requested", "interaction.responded")
                .doesNotContain("tool.call.started", "tool.call.succeeded", "tool.call.completed");
        assertThat(Files.exists(marker)).isFalse();
    }

    @Test
    void timeoutTerminatesTheActualChild() throws Exception {
        Path dataRoot = Files.createDirectories(temporary.resolve("data"));
        var provider = hostProvider(dataRoot);
        Path pidFile = temporary.resolve("markers").resolve("timeout-pid.txt");

        long started = System.nanoTime();
        var outcome = runWithApproval("owner-a", provider,
                fixtureCommand("sleep", pidFile, "30000"), InteractionAction.APPROVE, 3_000);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(outcome.status().isTerminal()).isTrue();
        assertThat(outcome.events()).contains("tool.call.started");
        assertThat(outcome.toolSucceeded()).isFalse();
        assertThat(outcome.toolResult()).isNotNull();
        assertThat(outcome.toolResult().structuredData().values())
                .containsEntry("timedOut", true)
                .containsEntry("processState", "TIMED_OUT");
        assertThat(elapsedMillis).isLessThan(25_000);
        long pid = awaitPid(pidFile);
        assertThat(awaitProcessGone(pid, Duration.ofSeconds(15)))
                .as("timed-out child process %s must exit", pid)
                .isTrue();
    }

    @Test
    void cancellingTheRunTerminatesTheActualChild() throws Exception {
        Path dataRoot = Files.createDirectories(temporary.resolve("data"));
        var provider = hostProvider(dataRoot);
        Path pidFile = temporary.resolve("markers").resolve("cancel-pid.txt");

        try (var foundation = foundation("cancel");
                var bridge = bridge(LocalProbeModelTestSupport.binding(callModel("sleep", pidFile, "60000")),
                        foundation, provider, "owner-a")) {
            var startedRun = bridge.start("thread-cancel-" + System.nanoTime(),
                    "Run the lark execution fixture", Optional.empty());
            approve(bridge, startedRun.runId());
            long pid = awaitPid(pidFile);

            assertThat(bridge.cancel(startedRun.runId())).isTrue();
            AgentRunStatus terminal = awaitTerminal(bridge, startedRun.runId(), Duration.ofSeconds(30));

            assertThat(terminal).isEqualTo(AgentRunStatus.CANCELLED);
            assertThat(awaitProcessGone(pid, Duration.ofSeconds(15)))
                    .as("cancelled child process %s must exit", pid)
                    .isTrue();
        }
    }

    @Test
    void outputBeyondTheProviderBoundIsReportedAsTruncated() throws Exception {
        Path dataRoot = Files.createDirectories(temporary.resolve("data"));
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        var provider = new ExecutionProviderConfig(
                ExecutionProvider.HOST_GUARDED, workspace, dataRoot,
                Duration.ofSeconds(30), Duration.ofSeconds(60), 2_048, 50, Optional.empty());

        var outcome = runWithApproval("owner-a", provider, fixtureCommand("output", null, "8000"),
                InteractionAction.APPROVE, 30_000);

        assertThat(outcome.status().isTerminal()).isTrue();
        ToolResultView result = outcome.toolResult();
        assertThat(result).isNotNull();
        assertThat(result.truncated()).isTrue();
        assertThat(result.structuredData().values()).containsEntry("truncated", true);
        assertThat(result.summary().text().length()).isLessThanOrEqualTo(4_096);
    }

    // ------------------------------------------------------------------ helpers

    private record Outcome(AgentRunStatus status, List<String> events, ToolResultView toolResult) {
        boolean toolSucceeded() {
            return events.contains("tool.call.succeeded");
        }
    }

    private Outcome runWithApproval(String owner, ExecutionProviderConfig provider, String content,
            InteractionAction action, long timeoutMillis) throws Exception {
        var model = callModel("raw", null, content, timeoutMillis);
        try (var foundation = foundation(owner + "-" + System.nanoTime());
                var bridge = bridge(LocalProbeModelTestSupport.binding(model), foundation, provider, owner)) {
            var started = bridge.start("thread-lark-" + System.nanoTime(),
                    "Run the lark execution fixture", Optional.empty());
            approve(bridge, started.runId(), action);
            AgentRunStatus status = awaitTerminal(bridge, started.runId(), Duration.ofSeconds(40));
            List<String> events = eventTypes(bridge, started.runId());
            ToolResultView result = bridge.toolCalls(started.runId()).stream()
                    .filter(call -> "execution_run".equals(call.toolName()))
                    .map(ToolCallView::result)
                    .flatMap(Optional::stream)
                    .reduce((first, second) -> second)
                    .orElse(null);
            return new Outcome(status, events, result);
        }
    }

    private ControlledModel callModel(String mode, Path marker, String extra, long timeoutMillis) {
        return new ControlledModel((request, index) -> {
            if (index != 1) {
                return ControlledModel.stop(request);
            }
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("mode", "COMMAND");
            arguments.put("content", "raw".equals(mode) ? extra : fixtureCommand(mode, marker, extra));
            arguments.put("purpose", "lark execution environment probe");
            arguments.put("timeoutMillis", timeoutMillis);
            return ControlledModel.call(request, "execution_run", arguments);
        });
    }

    private ControlledModel callModel(String mode, Path marker, String extra) {
        return new ControlledModel((request, index) -> index == 1
                ? ControlledModel.call(request, "execution_run", Map.of(
                        "mode", "COMMAND",
                        "content", fixtureCommand(mode, marker, extra),
                        "purpose", "lark execution environment probe",
                        "timeoutMillis", 60_000))
                : ControlledModel.stop(request));
    }

    private void approve(HaifaRunBridge bridge, AgentRunId runId) throws Exception {
        approve(bridge, runId, InteractionAction.APPROVE);
    }

    private void approve(HaifaRunBridge bridge, AgentRunId runId, InteractionAction action) throws Exception {
        InteractionView pending = awaitApproval(bridge, runId);
        bridge.respond(new InteractionResponseSubmission(
                new InteractionResponseId("lark-exec-" + pending.requestId().value() + "-" + action),
                pending.requestId(),
                runId,
                pending.revision(),
                action,
                List.of(),
                "lark-exec-idempotency-" + pending.requestId().value(),
                pending.createdAt()));
    }

    private static InteractionView awaitApproval(HaifaRunBridge bridge, AgentRunId runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            var pending = bridge.pendingInteraction(runId);
            if (pending.isPresent()) {
                return pending.orElseThrow();
            }
            if (bridge.snapshot(runId).status().isTerminal()) {
                break;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("no approval request: " + describe(bridge, runId));
    }

    private static AgentRunStatus awaitTerminal(HaifaRunBridge bridge, AgentRunId runId, Duration budget)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + budget.toMillis();
        while (System.currentTimeMillis() < deadline) {
            AgentRunStatus status = bridge.snapshot(runId).status();
            if (status.isTerminal()) {
                return status;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("the run never reached a terminal status: " + describe(bridge, runId));
    }

    private static long awaitPid(Path pidFile) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(pidFile)) {
                String text = Files.readString(pidFile);
                for (String line : text.split("\\R")) {
                    if (line.startsWith("PID=") && line.length() > 4) {
                        return Long.parseLong(line.substring(4).trim());
                    }
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("the fixture never recorded its PID at " + pidFile);
    }

    private static boolean awaitProcessGone(long pid, Duration budget) throws InterruptedException {
        long deadline = System.currentTimeMillis() + budget.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                Thread.sleep(50);
                continue;
            }
            return true;
        }
        return !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static String firstLineOf(Path path) throws Exception {
        return Files.readString(path).lines().findFirst().orElse("");
    }

    private static String fixtureCommand(String mode, Path marker) {
        return fixtureCommand(mode, marker, null);
    }

    private static String fixtureCommand(String mode, Path marker, String extra) {
        boolean windows = isWindows();
        String javaPath = javaExecutable();
        String cp = fixtureClasspath().toString();

        StringBuilder command = new StringBuilder();
        if (windows) {
            command.append("& ").append(quotePowerShell(javaPath))
                    .append(" -cp ").append(quotePowerShell(cp))
                    .append(' ').append(FIXTURE)
                    .append(' ').append(mode);
            if (marker != null) {
                command.append(' ').append(quotePowerShell(marker.toAbsolutePath().normalize().toString()));
            }
            if (extra != null) {
                command.append(' ').append(quotePowerShell(extra));
            }
        } else {
            command.append(quotePosix(javaPath))
                    .append(" -cp ").append(quotePosix(cp))
                    .append(' ').append(FIXTURE)
                    .append(' ').append(mode);
            if (marker != null) {
                command.append(' ').append(quotePosix(marker.toAbsolutePath().normalize().toString()));
            }
            if (extra != null) {
                command.append(' ').append(quotePosix(extra));
            }
        }
        return command.toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static String quotePowerShell(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String quotePosix(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toAbsolutePath().normalize().toString();
    }

    private static Path fixtureClasspath() {
        try {
            var codeSource = io.haifa.agent.superharness.server.testsupport.LarkExecutionFixture.class
                    .getProtectionDomain()
                    .getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                throw new IllegalStateException("LarkExecutionFixture has no CodeSource location");
            }
            return Path.of(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve LarkExecutionFixture CodeSource Path", e);
        }
    }

    private LarkIntegrationService larkService(Path dataRoot) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var skills = new io.haifa.agent.superharness.server.config.SkillConfigStore(
                json, dataRoot,
                io.haifa.agent.superharness.server.config.SkillConfigStore.repositoryRootFromWorkingDirectory());
        Path root = dataRoot.resolve("integrations").resolve("lark");
        return new LarkIntegrationService(json, dataRoot, skills, new LarkSkillPackInstaller(json, root),
                new LarkIntegrationFakes.FakeCli(true), new LarkIntegrationFakes.FakeRegistration(),
                new LarkIntegrationFakes.FakeArchives(dataRoot.resolve("unused.zip")),
                System::nanoTime, duration -> { });
    }

    private ExecutionProviderConfig hostProvider(Path dataRoot) throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        return ExecutionProviderConfig.fromEnvironment(
                        Map.of(ExecutionProviderConfig.PROVIDER_ENVIRONMENT_KEY, "host-guarded"),
                        workspace, dataRoot)
                .orElseThrow();
    }

    private DurableHaifaFoundation foundation(String name) throws Exception {
        return new DurableHaifaFoundation(
                temporary.resolve("foundation-" + name.replaceAll("[^A-Za-z0-9-]", "_")),
                new SecretKeySpec(new byte[32], "AES"));
    }

    private HaifaRunBridge bridge(HaifaRunBridge.ModelBinding binding, DurableHaifaFoundation foundation,
            ExecutionProviderConfig provider, String owner) {
        var delegation = SubagentRunConfiguration.disabled();
        return new HaifaRunBridge(
                binding, promptTools(), foundation, McpRunTools.Projection.empty(), null,
                AgentPromptContext.defaults(), delegation,
                new ChildRunCapacity(delegation.settings().runningCapacity()),
                false, null, provider, null, null, null, owner, null, null);
    }

    private static List<String> eventTypes(HaifaRunBridge bridge, AgentRunId runId) {
        return bridge.events(runId, bridge.beforeFirstEvent(runId), 512).items().stream()
                .map(AgentRunEvent::eventType)
                .collect(Collectors.toList());
    }

    private static String describe(HaifaRunBridge bridge, AgentRunId runId) {
        var page = bridge.events(runId, bridge.beforeFirstEvent(runId), 512);
        return page.items().stream().map(AgentRunEvent::eventType).collect(Collectors.joining(" | "));
    }

    private static List<? extends JavaTool<?, ?>> promptTools() {
        return Stream.of("read_file", "write_file", "str_replace", "present_files", "list_uploaded_files",
                        "grep", "web_search", "web_fetch")
                .map(LarkExecutionEnvironmentRunTest::namedTool)
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

package io.haifa.agent.superharness.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.runtime.api.ChildRunCapacity;
import io.haifa.agent.sandbox.docker.DockerAioLifecycleMode;
import io.haifa.agent.sandbox.docker.DockerAioSandboxConfiguration;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.superharness.server.api.DeerFlowBootstrapApi;
import io.haifa.agent.superharness.server.config.ApprovalPolicyConfig;
import io.haifa.agent.superharness.server.config.ExecutionProvider;
import io.haifa.agent.superharness.server.config.ExecutionProviderConfig;
import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
import io.haifa.agent.superharness.server.config.McpRunTools;
import io.haifa.agent.superharness.server.config.SubagentRunConfiguration;
import io.haifa.agent.superharness.server.prompt.AgentPromptContext;
import io.haifa.agent.superharness.server.testsupport.ControlledModel;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Live Docker AIO integration tests executed against a real Docker daemon (e.g. in WSL2 or Linux).
 * Skipped gracefully when {@code /var/run/docker.sock} is absent.
 */
@Tag("slow")
public class LiveDockerAioVerificationTest {
    private static final Path DOCKER_SOCKET = Path.of("/var/run/docker.sock");
    private static final String AIO_IMAGE =
            "enterprise-public-cn-beijing.cr.volces.com/vefaas-public/all-in-one-sandbox@sha256:6328d7fd2f0ff0b4c147c3d05b3df1ce331f4a482eb6e550ecd64ed1fcf906e7";

    public record FixturePayload() {}

    private static JavaTool<FixturePayload, FixturePayload> namedTool(String name) {
        var spec = JavaToolSpec.builder(name, FixturePayload.class, FixturePayload.class)
                .description("Prompt tool fixture")
                .pure()
                .build();
        return new JavaTool<>() {
            @Override
            public JavaToolSpec<FixturePayload, FixturePayload> spec() {
                return spec;
            }

            @Override
            public FixturePayload invoke(FixturePayload input, JavaToolContext context) {
                throw new AssertionError("Controlled model must not call fixture tools");
            }
        };
    }

    private static List<? extends JavaTool<?, ?>> promptTools() {
        return java.util.stream.Stream.of(
                        "read_file", "write_file", "str_replace", "present_files", "list_uploaded_files",
                        "grep", "web_search", "web_fetch")
                .map(LiveDockerAioVerificationTest::namedTool)
                .toList();
    }

    private static boolean isLiveDockerAvailable() {
        return Files.exists(DOCKER_SOCKET);
    }

    @Test
    void warmSessionLifecycleAndWorkspaceMountLiveExecution(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(isLiveDockerAvailable(), "Live Docker daemon at " + DOCKER_SOCKET + " is required");

        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path foundationDir = Files.createDirectories(tempDir.resolve("foundation"));

        var dockerConfig = new DockerAioSandboxConfiguration(
                "live-test-install",
                DOCKER_SOCKET,
                AIO_IMAGE,
                "/mnt/user-data/workspace",
                Map.of("bash", "/bin/bash", "python", "/usr/bin/python3"),
                OptionalInt.empty(),
                OptionalInt.empty(),
                2_000_000_000L,
                1024L * 1024 * 1024,
                256,
                Duration.ofSeconds(15),
                Duration.ofSeconds(5),
                8080,
                4,
                DockerAioLifecycleMode.WARM_SESSION,
                Duration.ofMinutes(5));

        var executionProvider = new ExecutionProviderConfig(
                ExecutionProvider.DOCKER_AIO,
                workspace,
                tempDir,
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                100_000,
                1_000,
                Optional.of(dockerConfig));

        ControlledModel model = new ControlledModel((request, index) -> {
            if (index == 1) {
                return ControlledModel.call(request, "execution_run", Map.of(
                        "mode", "COMMAND",
                        "content", "echo 'LIVE_WARM_SESSION_STEP_1' > /mnt/user-data/workspace/step1.txt && cat /mnt/user-data/workspace/step1.txt",
                        "purpose", "warm step 1"));
            }
            if (index == 2) {
                return ControlledModel.call(request, "execution_run", Map.of(
                        "mode", "COMMAND",
                        "content", "echo 'LIVE_WARM_SESSION_STEP_2' >> /mnt/user-data/workspace/step1.txt && cat /mnt/user-data/workspace/step1.txt",
                        "purpose", "warm step 2"));
            }
            return ControlledModel.stop(request);
        });

        try (var foundation = new DurableHaifaFoundation(foundationDir, new SecretKeySpec(new byte[32], "AES"));
                var bridge = new HaifaRunBridge(
                        LocalProbeModelTestSupport.binding(model),
                        promptTools(),
                        foundation,
                        McpRunTools.Projection.empty(),
                        null,
                        AgentPromptContext.defaults(),
                        SubagentRunConfiguration.disabled(),
                        new ChildRunCapacity(1),
                        false,
                        null,
                        executionProvider,
                        null,
                        null,
                        null,
                        "test-owner",
                        "test-thread",
                        ApprovalPolicyConfig.Mode.AUTO)) {

            var started = bridge.start("thread-warm-live-" + System.nanoTime(), "Run warm container steps", Optional.empty());

            long deadline = System.currentTimeMillis() + 60_000;
            while (model.requests.size() < 3 && System.currentTimeMillis() < deadline) {
                var snap = bridge.snapshot(started.runId());
                if (snap.status().isTerminal()) {
                    System.err.println("=== RUN TERMINAL STATE: " + snap.status() + " error=" + snap.error());
                    var page = bridge.events(started.runId(), bridge.beforeFirstEvent(started.runId()), 256);
                    for (var ev : page.items()) {
                        System.err.println("EVENT: " + ev.eventType() + " -> " + ev.payload());
                    }
                    break;
                }
                Thread.sleep(50);
            }

            assertThat(model.requests.size()).isGreaterThanOrEqualTo(3);

            // Verify host workspace observed the file created by the container
            Path step1File = workspace.resolve("step1.txt");
            assertThat(Files.exists(step1File)).isTrue();
            String step1Content = Files.readString(step1File);
            assertThat(step1Content).contains("LIVE_WARM_SESSION_STEP_1");
            assertThat(step1Content).contains("LIVE_WARM_SESSION_STEP_2");
        }
    }

    @Test
    void superHarnessGatewayWithLiveDockerRunExecution(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(isLiveDockerAvailable(), "Live Docker daemon at " + DOCKER_SOCKET + " is required");

        ControlledModel model = new ControlledModel((request, index) -> {
            if (index == 1) {
                return ControlledModel.call(request, "execution_run", Map.of(
                        "mode", "COMMAND",
                        "content", "echo 'GATEWAY_LIVE_DOCKER_SUCCESS_2026' > /mnt/user-data/workspace/gateway_out.txt && cat /mnt/user-data/workspace/gateway_out.txt",
                        "purpose", "test gateway docker execution"));
            }
            return ControlledModel.stop(request);
        });

        Map<String, String> env = new LinkedHashMap<>();
        env.put(ExecutionProviderConfig.PROVIDER_ENVIRONMENT_KEY, "docker-aio");
        env.put(ExecutionProviderConfig.DOCKER_IMAGE_KEY, AIO_IMAGE);
        env.put(ExecutionProviderConfig.DOCKER_SOCKET_KEY, DOCKER_SOCKET.toString());
        env.put(ApprovalPolicyConfig.ENVIRONMENT_KEY, "auto");
        // Cross-platform continuation key support for Linux & Windows
        env.put("HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://LIVE_DOCKER_TEST_KEY");
        env.put("LIVE_DOCKER_TEST_KEY", Base64.getEncoder().encodeToString(new byte[32]));

        LocalServerConfiguration config = new LocalServerConfiguration(
                "local", InetAddress.getByName("127.0.0.1"), 0);

        try (DeerFlowBootstrapApi api = DeerFlowBootstrapApi.createForTests(
                config,
                tempDir,
                env,
                ignored -> LocalProbeModelTestSupport.binding(model))) {

            api.start();
            URI baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
            HttpClient client = HttpClient.newHttpClient();

            // Create thread
            String threadId = "thread-live-docker-" + System.nanoTime();
            HttpResponse<String> createThreadResp = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/threads"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"thread_id\":\"" + threadId + "\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(createThreadResp.statusCode()).isEqualTo(200);

            // Send run stream request
            String runBody = """
                    {"assistant_id":"lead_agent","input":{"messages":[{"id":"human-1","type":"human","content":"please execute"}]},"stream_mode":["messages-tuple"],"on_disconnect":"cancel","multitask_strategy":"reject"}
                    """;

            HttpResponse<String> runResp = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(runBody))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            System.err.println("=== GATEWAY RUN STREAM STATUS=" + runResp.statusCode() + " BODY:\n" + runResp.body());
            assertThat(runResp.statusCode()).isEqualTo(200);

            // Await completion: model should have been called twice (1: tool call request, 2: tool result response)
            long deadline = System.currentTimeMillis() + 60_000;
            while (model.requests.size() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            assertThat(model.requests.size()).isGreaterThanOrEqualTo(2);

            // Inspect the second request messages: should include the execution_run tool result
            var secondRequest = model.requests.get(1);
            boolean hasToolOutput = secondRequest.messages().stream()
                    .anyMatch(msg -> msg.content() != null && msg.content().contains("GATEWAY_LIVE_DOCKER_SUCCESS_2026"));
            assertThat(hasToolOutput).isTrue();
        }
    }
}

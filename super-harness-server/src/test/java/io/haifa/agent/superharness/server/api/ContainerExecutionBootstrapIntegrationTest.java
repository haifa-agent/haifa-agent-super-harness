package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.sandbox.docker.DockerAioLifecycleMode;
import io.haifa.agent.superharness.server.config.ExecutionProvider;
import io.haifa.agent.superharness.server.config.ExecutionProviderConfig;
import io.haifa.agent.superharness.server.testsupport.ControlledModel;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContainerExecutionBootstrapIntegrationTest extends AbstractDeerFlowThreadRunApiTest {
    private static final String VALID_IMAGE =
            "deer-flow-aio@sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void gatewayBootstrapWithDockerAioExposesExecutionRunTool() throws Exception {
        ControlledModel model = new ControlledModel();
        startApi(
                Map.of(
                        ExecutionProviderConfig.PROVIDER_ENVIRONMENT_KEY, "docker-aio",
                        ExecutionProviderConfig.DOCKER_IMAGE_KEY, VALID_IMAGE),
                ignored -> LocalProbeModelTestSupport.binding(model));

        String threadId = "thread-docker-aio-" + System.nanoTime();
        createThread(threadId);

        HttpResponse<String> response = client.send(
                runRequest(threadId, "test docker execution"), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        long deadline = System.currentTimeMillis() + 10_000;
        while (model.requests.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertThat(model.requests).isNotEmpty();

        List<String> toolNames = model.requests.get(0).tools().stream()
                .map(ModelToolSpecification::name)
                .toList();
        assertThat(toolNames).contains("execution_run");
    }

    @Test
    void gatewayBootstrapWithDefaultConfigOmitsExecutionRunTool() throws Exception {
        ControlledModel model = new ControlledModel();
        startApi(Map.of(), ignored -> LocalProbeModelTestSupport.binding(model));

        String threadId = "thread-default-" + System.nanoTime();
        createThread(threadId);

        HttpResponse<String> response = client.send(
                runRequest(threadId, "test default execution"), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        long deadline = System.currentTimeMillis() + 10_000;
        while (model.requests.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertThat(model.requests).isNotEmpty();

        List<String> toolNames = model.requests.get(0).tools().stream()
                .map(ModelToolSpecification::name)
                .toList();
        assertThat(toolNames).doesNotContain("execution_run");
    }

    @Test
    void gatewayBootstrapWithDisabledConfigOmitsExecutionRunTool() throws Exception {
        ControlledModel model = new ControlledModel();
        startApi(
                Map.of(ExecutionProviderConfig.PROVIDER_ENVIRONMENT_KEY, "disabled"),
                ignored -> LocalProbeModelTestSupport.binding(model));

        String threadId = "thread-disabled-" + System.nanoTime();
        createThread(threadId);

        HttpResponse<String> response = client.send(
                runRequest(threadId, "test disabled execution"), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        long deadline = System.currentTimeMillis() + 10_000;
        while (model.requests.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertThat(model.requests).isNotEmpty();

        List<String> toolNames = model.requests.get(0).tools().stream()
                .map(ModelToolSpecification::name)
                .toList();
        assertThat(toolNames).doesNotContain("execution_run");
    }

    @Test
    void gatewayBootstrapWithHostGuardedExposesExecutionRunTool() throws Exception {
        ControlledModel model = new ControlledModel();
        startApi(
                Map.of(ExecutionProviderConfig.PROVIDER_ENVIRONMENT_KEY, "host-guarded"),
                ignored -> LocalProbeModelTestSupport.binding(model));

        String threadId = "thread-host-" + System.nanoTime();
        createThread(threadId);

        HttpResponse<String> response = client.send(
                runRequest(threadId, "test host execution"), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        long deadline = System.currentTimeMillis() + 10_000;
        while (model.requests.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertThat(model.requests).isNotEmpty();

        List<String> toolNames = model.requests.get(0).tools().stream()
                .map(ModelToolSpecification::name)
                .toList();
        assertThat(toolNames).contains("execution_run");
    }

    @Test
    void gatewayBootstrapWithConflictingLegacyHostShellFailsClosed() throws Exception {
        ControlledModel model = new ControlledModel();
        startApi(
                Map.of(
                        ExecutionProviderConfig.PROVIDER_ENVIRONMENT_KEY, "docker-aio",
                        ExecutionProviderConfig.DOCKER_IMAGE_KEY, VALID_IMAGE,
                        ExecutionProviderConfig.LEGACY_HOST_SHELL_KEY, "on"),
                ignored -> LocalProbeModelTestSupport.binding(model));

        String threadId = "thread-conflict-" + System.nanoTime();
        createThread(threadId);

        HttpResponse<String> response = client.send(
                runRequest(threadId, "test conflict execution"), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(model.requests).isEmpty();
    }

    @Test
    void defaultLifecycleModeIsWarmSession() {
        var config = ExecutionProviderConfig.fromEnvironment(
                Map.of(
                        ExecutionProviderConfig.PROVIDER_ENVIRONMENT_KEY, "docker-aio",
                        ExecutionProviderConfig.DOCKER_IMAGE_KEY, VALID_IMAGE),
                dataDirectory,
                dataDirectory);
        assertThat(config).isPresent();
        assertThat(config.get().provider()).isEqualTo(ExecutionProvider.DOCKER_AIO);
        assertThat(config.get().dockerConfiguration()).isPresent();
        assertThat(config.get().dockerConfiguration().get().lifecycleMode())
                .isEqualTo(DockerAioLifecycleMode.WARM_SESSION);
    }
}

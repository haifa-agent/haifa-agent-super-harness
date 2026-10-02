package io.haifa.agent.superharness.server.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelStreamControl;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Guards against fixture false positives using the real SDK and owned loopback resources. */
class AcceptanceFixturesTest {
    @Test void typedModelRequiresUsageAndHasEquivalentInvocationPaths() throws Exception {
        var model = new ControlledModel();
        try (var gateway = new GatewayFixture(GatewayFixture.dataDirectory(), model)) {
            gateway.thread("fixture-model");
            gateway.completed(gateway.run("fixture-model", null, null));
            var request = model.requests.getFirst();
            assertThatThrownBy(() -> ControlledModel.response(request, "missing-usage", "", List.of(),
                    ModelFinishReason.STOP, null)).isInstanceOf(NullPointerException.class).hasMessageContaining("usage");
            assertThatThrownBy(() -> ControlledModel.response(request, "missing-finish", "", List.of(),
                    null, model.lastResponse.usage())).isInstanceOf(NullPointerException.class).hasMessageContaining("finish");
            var invoked = model.invoke(request);
            var streamed = model.invokeStreaming(request, ignored -> ModelStreamControl.CONTINUE);
            assertThat(streamed).isEqualTo(invoked);
            assertThat(streamed.usage()).isNotNull();
            assertThat(streamed.finishReason()).isEqualTo(ModelFinishReason.STOP);
            assertThat(model.requests).hasSize(3);
            assertThat(model.calls).hasValue(3);
        }
    }

    @Test void httpProviderRejectsMissingUsageAndExposesHandlerFailure() throws Exception {
        var evidence = GatewayFixture.evidenceDirectory();
        var provider = new ControlledProvider(evidence, true, (request, call) -> {
            var response = ControlledProvider.stop("bad-usage", "fixture", "synthetic");
            response.remove("usage");
            return response;
        });
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(provider.endpoint().resolve("/v1/chat/completions"))
                    .timeout(java.time.Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString("{\"stream\":true}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(provider.calls).hasValue(1);
            assertThat(provider.failure.get()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("usage");
            assertThatThrownBy(provider::assertHealthy).isInstanceOf(AssertionError.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        } finally {
            assertThatThrownBy(provider::close).isInstanceOf(AssertionError.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
            GatewayFixture.portReleased(provider.port());
        }
    }

    @Test void sseRetainsOrderedMultilineUnknownAndNonJsonEvents() throws Exception {
        var events = SseEvents.read(": heartbeat\r\nevent: updates\r\ndata: {\"a\":\r\ndata: 1}\r\n\r\n"
                + "event: extension\ndata: plain text\n\nevent: end\ndata: null\n\n"
                + "event:\ndata: 1 trailing-text\n\n");
        assertThat(events.events).extracting(SseEvents.Event::name).containsExactly("updates", "extension", "end", "message");
        assertThat(events.event("updates").path("a").asInt()).isEqualTo(1);
        assertThat(events.event("extension").asText()).isEqualTo("plain text");
        assertThat(events.events.getFirst().rawData()).isEqualTo("{\"a\":\n1}");
        assertThat(events.event("message").asText()).isEqualTo("1 trailing-text");
        Files.writeString(GatewayFixture.evidenceDirectory().resolve("ordered-events.json"),
                GatewayFixture.JSON.writeValueAsString(events.events));
    }

    @Test void approvalSelectsCurrentRunAfterAnEarlierRunAndRejectsMissingRun() throws Exception {
        var model = new ControlledModel((request, call) -> call % 2 == 1
                ? ControlledModel.call(request, "write_file", Map.of("path", "/mnt/user-data/outputs/fixture-" + call + ".txt",
                        "content", "controlled fixture\n")) : ControlledModel.stop(request));
        try (var gateway = new GatewayFixture(GatewayFixture.dataDirectory(), model)) {
            String thread = "fixture-current-run";
            gateway.thread(thread);
            var first = gateway.run(thread, null, null);
            String firstLocation = GatewayFixture.location(first);
            var old = gateway.pending(thread, firstLocation);
            Files.writeString(gateway.evidence.resolve("first-run.raw.sse"), first.body());
            gateway.completed(gateway.approve(thread, firstLocation));
            var second = gateway.run(thread, null, null);
            String secondLocation = GatewayFixture.location(second);
            var current = gateway.pending(thread, secondLocation);
            Files.writeString(gateway.evidence.resolve("second-run.raw.sse"), second.body());
            Files.writeString(gateway.evidence.resolve("two-run-state.raw.json"), gateway.state(thread).toPrettyString());
            Files.writeString(gateway.evidence.resolve("current-run.raw.json"), gateway.get(secondLocation).toPrettyString());
            assertThat(secondLocation).isNotEqualTo(firstLocation);
            assertThat(current.requestId()).isNotEqualTo(old.requestId());
            assertThat(gateway.approvalId(thread)).isEqualTo(current.requestId());
            assertThat(gateway.state(thread).path("values").path("messages").toString())
                    .contains(old.requestId(), current.requestId());
            assertThatThrownBy(() -> gateway.pending(thread, "/api/threads/" + thread + "/runs/missing-run"))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("Current Run").hasMessageContaining("404");
            gateway.completed(gateway.approve(thread, secondLocation));
            assertThat(model.calls).hasValue(4);
        }
    }

    @Test void exceptionalChildScopeClosesRealSdkGatewayAndProvider() throws Exception {
        var gateway = new GatewayProcessFixture("fixture-exceptional-cleanup");
        var provider = new ControlledProvider(gateway.evidence.resolve("provider"), true,
                (request, call) -> ControlledProvider.stop("cleanup-" + call, "m3-fixture-model", "completed"));
        int providerPort = provider.port();
        class DeliberateFailure extends Exception {}
        assertThatThrownBy(() -> {
            try (provider; gateway) {
                gateway.start();
                gateway.configureModel(providerPort);
                assertThat(gateway.send("POST", "/api/threads", "{\"thread_id\":\"fixture-cleanup\"}").statusCode())
                        .isEqualTo(200);
                var run = gateway.send("POST", "/api/threads/fixture-cleanup/runs/stream",
                        gateway.runBody(null, false, "Complete synthetic cleanup fixture"));
                Files.writeString(gateway.evidence.resolve("run.raw.sse"), run.body());
                String location = GatewayProcessFixture.location(run);
                assertThat(gateway.get(location).path("diagnostics").path("sdk_status").asText()).isEqualTo("COMPLETED");
                assertThat(provider.calls).hasValue(1);
                throw new DeliberateFailure();
            }
        }).isInstanceOf(DeliberateFailure.class).satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
        for (long pid : gateway.pids) assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        for (int port : gateway.ports) GatewayFixture.portReleased(port);
        GatewayFixture.portReleased(providerPort);
    }
}

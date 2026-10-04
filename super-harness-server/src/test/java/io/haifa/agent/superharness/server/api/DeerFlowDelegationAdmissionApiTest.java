package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.server.testsupport.ControlledProvider;
import io.haifa.agent.superharness.server.testsupport.DelegationHttpFixture;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Configuration validation must precede Run admission; only catalog I/O can disable delegation. */
class DeerFlowDelegationAdmissionApiTest {
    private static final Map<String, String> ENVIRONMENT = Map.of(
            "HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W08_ADMISSION_KEY",
            "W08_ADMISSION_KEY", Base64.getEncoder().encodeToString(new byte[32]));

    @Test void disabledManagedChildModelRejectsAdmissionInsteadOfRemovingAllTaskTools() throws Exception {
        Path data = GatewayFixture.dataDirectory(), evidence = GatewayFixture.evidenceDirectory();
        try (var http = new GatewayFixture.HttpEvidence(evidence, Duration.ofSeconds(30));
                var provider = new ControlledProvider(evidence, true,
                        (request, call) -> ControlledProvider.stop("lead-" + call, "w08-model", "ordinary response"));
                var gateway = GatewayFixture.production(data, Integer.getInteger("w08.gatewayPort", 14618), ENVIRONMENT, evidence)) {
            DelegationHttpFixture.configure(http, gateway.base, provider.port());
            ObjectNode save = childModel(provider.port());
            var createdModel = http.request(gateway.base, "PUT", "/api/managed-models", save.toString());
            assertThat(createdModel.statusCode()).isEqualTo(200);
            String revision = GatewayFixture.JSON.readTree(createdModel.body()).path("revision").asText();
            DelegationHttpFixture.seed(http, gateway.base, "researcher", 30, 5, List.of());
            assertThat(http.request(gateway.base, "PUT", "/api/subagents/researcher", "{\"model\":\"w08-child\"}")
                    .statusCode()).isEqualTo(200);
            save.put("expected_revision", revision);
            ((ObjectNode) save.path("config")).put("enabled", false);
            assertThat(http.request(gateway.base, "PUT", "/api/managed-models", save.toString()).statusCode()).isEqualTo(200);
            assertThat(http.request(gateway.base, "POST", "/api/threads", "{\"thread_id\":\"disabled-child\"}")
                    .statusCode()).isEqualTo(200);
            var rejected = http.request(gateway.base, "POST", "/api/threads/disabled-child/runs/stream",
                    DelegationHttpFixture.input(true).toString(), "disabled-child-rejected.http");
            assertThat(rejected.statusCode()).isEqualTo(422);
            assertThat(rejected.body()).contains("Child model is unavailable");
            assertThat(provider.calls).hasValue(0);
            assertThat(http.get(gateway.base, "/api/threads/disabled-child/runs", "disabled-child-runs.json")).isEmpty();
            assertThat(http.request(gateway.base, "DELETE", "/api/subagents/researcher", null)
                    .statusCode()).isEqualTo(204);
            DelegationHttpFixture.execute(http, gateway.base, "valid-after-rejection", DelegationHttpFixture.input(true));
            assertThat(provider.requests.getFirst().path("tools").findValuesAsText("name")).contains("task");
            assertThat(provider.failure.get()).isNull();
        }
    }

    @Test void unreadableCatalogDirectoryDisablesDelegationButStillAllowsOrdinaryRun() throws Exception {
        Path data = GatewayFixture.dataDirectory(), evidence = GatewayFixture.evidenceDirectory();
        try (var http = new GatewayFixture.HttpEvidence(evidence, Duration.ofSeconds(30));
                var provider = new ControlledProvider(evidence, true,
                        (request, call) -> ControlledProvider.stop("lead-" + call, "w08-model", "safe ordinary response"));
                var gateway = GatewayFixture.production(data, Integer.getInteger("w08.gatewayPort", 14618), ENVIRONMENT, evidence)) {
            DelegationHttpFixture.configure(http, gateway.base, provider.port());
            Path catalog = data.resolve("owners/default/subagents");
            Files.createDirectories(catalog.getParent());
            assertThat(catalog).doesNotExist();
            Files.writeString(catalog, "not a directory");
            String location = DelegationHttpFixture.execute(http, gateway.base, "unreadable-catalog", DelegationHttpFixture.input(true));
            assertThat(http.get(gateway.base, location, "ordinary-run.json").path("status").asText()).isEqualTo("success");
            assertThat(provider.requests.getFirst().path("tools").findValuesAsText("name")).doesNotContain("task");
            assertThat(provider.failure.get()).isNull();
        }
    }

    private static ObjectNode childModel(int providerPort) {
        ObjectNode body = GatewayFixture.JSON.createObjectNode();
        body.putObject("config").put("name", "w08-child").put("provider", "openai-compatible").put("model", "w08-child-model")
                .put("base_url", "http://127.0.0.1:" + providerPort + "/v1").put("enabled", true)
                .put("supports_vision", false).put("context_window", 32768).put("max_tokens", 1024).put("api_key", "controlled-child-key");
        body.putNull("expected_revision");
        return body;
    }
}

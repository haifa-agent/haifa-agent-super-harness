package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelStreamSink;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.FrozenInstructionDiagnostic;
import io.haifa.agent.runtime.api.RuntimeApiErrorCode;
import io.haifa.agent.runtime.api.RuntimeContractException;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceChangeLimits;
import io.haifa.agent.superharness.workspace.WorkspaceLimits;
import io.haifa.agent.superharness.workspace.WorkspaceError;
import io.haifa.agent.superharness.workspace.WorkspaceException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Product recovery fail-closed cases on actual existing SDK/SQLite Runs, never missing-resource substitutes. */
public class AgentSoulPromptRecoveryHttpTest {
    private static final String AGENT = "haifa_agent_name";
    private static final String BOOTSTRAP = "haifa_agent_bootstrap";
    private static final String SIGNATURE = "haifa_lead_prompt_signature";
    private static final String DETAIL = "Pending Run prompt or agent identity is unavailable";
    private static final String OUTPUT = "/mnt/user-data/outputs/m3-guard-must-not-exist.txt";
    private static final String HUMAN = "M3 original historical input";

    static Stream<String> damagedMetadata() {
        return Stream.of("signature-missing", "signature-number", "signature-empty", "signature-bad",
                "signature-mismatch", "agent-number", "bootstrap-string", "agent-missing", "bootstrap-missing");
    }

    @ParameterizedTest @MethodSource("damagedMetadata")
    void existingPendingRunWithDamagedServerMetadataCannotExecute(String mutation) throws Exception {
        try (var fixture = new GatewayProcessFixture("guard-" + mutation)) {
            var model = new GuardModel(true);
            Pending pending = admit(fixture, model);
            PersistentThreadCatalog catalog = new PersistentThreadCatalog(fixture.json, fixture.data);
            var original = catalog.find("guard-thread").orElseThrow();
            Map<String, Object> metadata = new LinkedHashMap<>(original.metadata());
            switch (mutation) {
                case "signature-missing" -> metadata.remove(SIGNATURE);
                case "signature-number" -> metadata.put(SIGNATURE, 17);
                case "signature-empty" -> metadata.put(SIGNATURE, "");
                case "signature-bad" -> metadata.put(SIGNATURE, "sha256:not-a-valid-digest");
                case "signature-mismatch" -> metadata.put(SIGNATURE,
                        original.metadata().get(SIGNATURE).equals("0".repeat(64)) ? "1".repeat(64) : "0".repeat(64));
                case "agent-number" -> metadata.put(AGENT, 17);
                case "bootstrap-string" -> metadata.put(BOOTSTRAP, "false");
                case "agent-missing" -> metadata.remove(AGENT);
                case "bootstrap-missing" -> metadata.remove(BOOTSTRAP);
                default -> throw new AssertionError("Unknown metadata fixture");
            }
            replace(catalog, original, metadata);
            try (var api = open(fixture, model)) {
                api.start();
                fixture.base = URI.create("http://127.0.0.1:" + api.address().getPort());
                assertRejected(fixture, pending, model);
            }
            assertNoWrite(fixture);
            fixture.proof("guard-result", Map.of("case", mutation, "existing_run", pending.run(),
                    "status", 409, "detail", DETAIL, "model_calls", model.calls.get(), "writes", 0));
        }
    }

    static Stream<String> missingDiagnostics() { return Stream.of("empty", "error", "wrong-run-id"); }

    @ParameterizedTest @MethodSource("missingDiagnostics")
    void publicTypedDiagnosticUnavailableFailsProductGuardWithoutExecution(String mode) throws Exception {
        try (var fixture = new GatewayProcessFixture("guard-diagnostic-" + mode)) {
            var model = new GuardModel(true);
            AgentRunId otherOwnerRun = mode.equals("wrong-run-id") ? admitOtherOwnerRun(fixture) : null;
            Pending pending = admit(fixture, model);
            var diagnosticCalls = new AtomicInteger();
            BiFunction<HaifaRunBridge, AgentRunId, Optional<FrozenInstructionDiagnostic>> diagnostic = (bridge, run) -> {
                diagnosticCalls.incrementAndGet();
                if (mode.equals("empty")) return Optional.empty();
                if (mode.equals("wrong-run-id")) {
                    FrozenInstructionDiagnostic actual = bridge.frozenInstructionDiagnostic(run).orElseThrow();
                    assertThat(otherOwnerRun).isNotEqualTo(run);
                    // Clone only the public projection's identity. The original request Run and
                    // this other owner-visible Run both really exist; this proves the product
                    // delegate guard, not SDK authorization or damaged snapshot semantics.
                    return Optional.of(new FrozenInstructionDiagnostic(otherOwnerRun, actual.definitionId(),
                            actual.definitionVersion(), actual.configurationSnapshot(), actual.instructionContentHash()));
                }
                throw new RuntimeContractException(RuntimeApiErrorCode.INTERNAL_ERROR, "Synthetic safe diagnostic failure");
            };
            var projects = new PersistentProjectWorkspaceService(fixture.data.resolve("workspace"),
                    WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
            var workspace = new RestrictedWorkspaceService(fixture.data.resolve("workspace"), WorkspaceLimits.defaults());
            var binding = LocalProbeModelTestSupport.binding(model);
            // This package-private typed seam is intentionally a product guard fixture. It does
            // not claim to prove SDK authorization, snapshot integrity or process recovery.
            try (var routes = new DeerFlowThreadRunApi(fixture.json, ignored -> binding, ignored -> true,
                    projects, fixture.data, new SecretKeySpec(new byte[32], "AES"),
                    scope -> DeerFlowBootstrapApi.fileTools(scope, projects, workspace),
                    DeerFlowThreadRunApi.McpProjectionProvider.none(), DeerFlowThreadRunApi.SkillProjectionProvider.none(),
                    new DeerFlowUploadContext(workspace), diagnostic)) {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                var executor = Executors.newVirtualThreadPerTaskExecutor();
                server.setExecutor(executor);
                server.createContext("/", routes::handle);
                server.start();
                fixture.base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
                try { assertRejected(fixture, pending, model); }
                finally { server.stop(0); executor.close(); }
            }
            assertThat(diagnosticCalls.get()).isPositive();
            assertNoWrite(fixture);
            fixture.proof("guard-result", Map.of("case", mode, "existing_run", pending.run(), "status", 409,
                    "typed_diagnostic_test_injection", true, "other_owner_run", otherOwnerRun == null ? "" : otherOwnerRun.value(),
                    "model_calls", model.calls.get(), "writes", 0));
        }
    }

    private static AgentRunId admitOtherOwnerRun(GatewayProcessFixture fixture) throws Exception {
        var otherModel = new GuardModel(false);
        try (var api = open(fixture, otherModel)) {
            api.start();
            fixture.base = URI.create("http://127.0.0.1:" + api.address().getPort());
            assertThat(fixture.send("POST", "/api/threads", "{\"thread_id\":\"diagnostic-other-owner-thread\"}")
                    .statusCode()).isEqualTo(200);
            var response = fixture.send("POST", "/api/threads/diagnostic-other-owner-thread/runs/stream", ordinary(fixture));
            String location = GatewayProcessFixture.location(response);
            JsonNode real = fixture.get(location);
            assertThat(real.path("status").asText()).isEqualTo("success");
            assertThat(real.path("diagnostics").path("sdk_status").asText()).isEqualTo("COMPLETED");
            assertThat(otherModel.calls).hasValue(1);
            return new AgentRunId(location.substring(location.lastIndexOf('/') + 1));
        }
    }

    @Test void clientsCannotForgeAgentBootstrapOrPromptSignatureDuringCreateOrPatch() throws Exception {
        try (var fixture = new GatewayProcessFixture("client-metadata")) {
            var model = new GuardModel(false);
            try (var api = open(fixture, model)) {
                api.start();
                fixture.base = URI.create("http://127.0.0.1:" + api.address().getPort());
                Map<String, Object> forged = Map.of(AGENT, "forged-agent", BOOTSTRAP, true, SIGNATURE, "a".repeat(64));
                var created = fixture.send("POST", "/api/threads", fixture.json.writeValueAsString(Map.of(
                        "thread_id", "guard-thread", "metadata", forged)));
                assertThat(created.statusCode()).isEqualTo(200);
                assertNoServerKeys(fixture.json.readTree(created.body()).path("metadata"));
                var patched = fixture.send("PATCH", "/api/threads/guard-thread", fixture.json.writeValueAsString(
                        Map.of("metadata", forged)));
                // PATCH is not an implemented thread endpoint; its actual 404 rejects this
                // attempted write to an existing resource whose GET remains a positive check.
                assertThat(patched.statusCode()).isEqualTo(404);
                assertNoServerKeys(fixture.get("/api/threads/guard-thread").path("metadata"));
                var run = fixture.send("POST", "/api/threads/guard-thread/runs/stream", ordinary(fixture));
                GatewayProcessFixture.location(run);
                JsonNode admitted = fixture.get("/api/threads/guard-thread").path("metadata");
                assertThat(admitted.path(AGENT).asText()).isEmpty();
                assertThat(admitted.path(BOOTSTRAP).asBoolean()).isFalse();
                String signature = admitted.path(SIGNATURE).asText();
                assertThat(signature).matches("[0-9a-f]{64}").isNotEqualTo("a".repeat(64));
                assertThat(fixture.send("PATCH", "/api/threads/guard-thread", fixture.json.writeValueAsString(
                        Map.of("metadata", forged))).statusCode()).isEqualTo(404);
                assertThat(fixture.send("POST", "/api/threads", fixture.json.writeValueAsString(Map.of(
                        "thread_id", "guard-thread", "metadata", forged))).statusCode()).isEqualTo(200);
                JsonNode retained = fixture.get("/api/threads/guard-thread").path("metadata");
                assertThat(retained.path(SIGNATURE).asText()).isEqualTo(signature);
                assertThat(retained.path(AGENT).asText()).isEmpty();
                assertThat(retained.path(BOOTSTRAP).asBoolean()).isFalse();
            }
        }
    }

    @Test void legacyTerminalHistoryRemainsExactAndFreshTurnAdmitsCurrentIdentity() throws Exception {
        legacy(false);
    }

    @Test void legacyExistingPendingRunCannotApproveWithoutOriginalProvenance() throws Exception {
        legacy(true);
    }

    private void legacy(boolean pendingCase) throws Exception {
        try (var fixture = new GatewayProcessFixture("legacy-" + pendingCase)) {
            var model = new GuardModel(pendingCase);
            Pending pending = admit(fixture, model);
            var catalog = new PersistentThreadCatalog(fixture.json, fixture.data);
            var original = catalog.find("guard-thread").orElseThrow();
            Map<String, Object> metadata = new LinkedHashMap<>(original.metadata());
            metadata.remove(AGENT); metadata.remove(BOOTSTRAP); metadata.remove(SIGNATURE);
            replace(catalog, original, metadata);
            String newRun = null;
            String newSignature = null;
            try (var api = open(fixture, model)) {
                api.start();
                fixture.base = URI.create("http://127.0.0.1:" + api.address().getPort());
                if (pendingCase) {
                    assertRejected(fixture, pending, model);
                    var switchingInput = fixture.json.readTree(ordinary(fixture));
                    ((ObjectNode) switchingInput.path("context"))
                            .put("agent_name", "legacy-switch-attempt").put("is_bootstrap", true);
                    var newTurn = fixture.send("POST", "/api/threads/guard-thread/runs/stream",
                            fixture.json.writeValueAsString(switchingInput));
                    assertThat(newTurn.statusCode()).as("fresh identity must not bypass a legacy pending Run")
                            .isEqualTo(409);
                    ObjectNode steerInput = (ObjectNode) fixture.json.readTree(ordinary(fixture));
                    steerInput.remove("multitask_strategy");
                    var steerRequest = HttpRequest.newBuilder(fixture.base.resolve("/api/threads/guard-thread/runs/stream"))
                            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                            .header("X-Haifa-Run-Input", "steer")
                            .POST(HttpRequest.BodyPublishers.ofString(fixture.json.writeValueAsString(steerInput))).build();
                    var steer = fixture.http.send(steerRequest, HttpResponse.BodyHandlers.ofString());
                    String steerDetail = fixture.json.readTree(steer.body()).path("detail").asText();
                    fixture.proof("legacy-steer-rejection", Map.of("existing_run", pending.run(),
                            "status", steer.statusCode(), "response_sha256", GatewayProcessFixture.hash(steer.body()),
                            "detail", DETAIL.equals(steerDetail) ? steerDetail : "UNEXPECTED_DETAIL",
                            "detail_matches_identity_guard", DETAIL.equals(steerDetail),
                            "evidence_kind", "derived-response-digest"));
                    assertThat(steer.statusCode()).as("Steer requires the original nonterminal Run provenance")
                            .isEqualTo(409);
                    assertThat(steerDetail).as("legal Steer must reach the original-identity recovery guard")
                            .isEqualTo(DETAIL);
                    assertThat(model.calls).hasValue(1);
                    assertThat(fixture.get("/api/threads/guard-thread/runs/" + pending.run())
                            .path("diagnostics").path("sdk_status").asText()).isEqualTo("WAITING_APPROVAL");
                    assertNoServerKeys(fixture.get("/api/threads/guard-thread").path("metadata"));
                }
                else {
                    JsonNode state = fixture.get("/api/threads/guard-thread/state");
                    JsonNode oldMessages = state.path("values").path("messages").deepCopy();
                    assertHumanInputs(state, HUMAN);
                    var history = fixture.send("POST", "/api/threads/guard-thread/history", "{\"limit\":10}");
                    assertThat(history.statusCode()).isEqualTo(200);
                    JsonNode historicalState = fixture.json.readTree(history.body()).get(0);
                    assertHumanInputs(historicalState, HUMAN);
                    assertThat(historicalState.path("values").path("messages")).isEqualTo(oldMessages);
                    assertNoServerKeys(fixture.get("/api/threads/guard-thread").path("metadata"));
                    assertThat(model.calls).hasValue(1);
                    // The former M3-only 409 expectation overrestricted the frozen M2 contract:
                    // terminal history is exact, while a fresh Run may admit trusted current configuration.
                    String currentInput = "M3 fresh terminal-thread input";
                    var admitted = fixture.send("POST", "/api/threads/guard-thread/runs/stream",
                            ordinary(fixture, currentInput));
                    assertThat(admitted.statusCode()).isEqualTo(200);
                    String location = GatewayProcessFixture.location(admitted);
                    newRun = location.substring(location.lastIndexOf('/') + 1);
                    assertThat(newRun).isNotEqualTo(pending.run());
                    JsonNode currentRun = fixture.get(location);
                    assertThat(currentRun.path("run_id").asText()).isEqualTo(newRun);
                    assertThat(currentRun.path("status").asText()).isEqualTo("success");
                    assertThat(currentRun.path("diagnostics").path("sdk_status").asText()).isEqualTo("COMPLETED");
                    assertThat(model.calls).as("one old and one newly admitted model call").hasValue(2);
                    JsonNode currentMetadata = fixture.get("/api/threads/guard-thread").path("metadata");
                    assertThat(currentMetadata.path(AGENT).isTextual()).isTrue();
                    assertThat(currentMetadata.path(AGENT).asText()).isEmpty();
                    assertThat(currentMetadata.path(BOOTSTRAP).isBoolean()).isTrue();
                    assertThat(currentMetadata.path(BOOTSTRAP).asBoolean()).isFalse();
                    newSignature = currentMetadata.path(SIGNATURE).asText();
                    assertThat(newSignature).matches("[0-9a-f]{64}");
                    JsonNode after = fixture.get("/api/threads/guard-thread/state");
                    assertHumanInputs(after, HUMAN, currentInput);
                    JsonNode afterMessages = after.path("values").path("messages");
                    assertThat(afterMessages.size()).isGreaterThan(oldMessages.size());
                    for (int index = 0; index < oldMessages.size(); index++) {
                        assertThat(afterMessages.get(index)).as("unchanged historical message %s", index)
                                .isEqualTo(oldMessages.get(index));
                    }
                    var refreshedHistory = fixture.send("POST", "/api/threads/guard-thread/history", "{\"limit\":10}");
                    assertThat(refreshedHistory.statusCode()).isEqualTo(200);
                    JsonNode latestHistory = fixture.json.readTree(refreshedHistory.body()).get(0);
                    assertHumanInputs(latestHistory, HUMAN, currentInput);
                    assertThat(latestHistory.path("values").path("messages")).isEqualTo(afterMessages);
                    fixture.proof("legacy-terminal-new-admission", Map.of("old_run", pending.run(), "new_run", newRun,
                            "status", "success", "model_calls", model.calls.get(), "historical_messages_unchanged", true,
                            "current_signature", newSignature, "read_did_not_mint_metadata", true));
                }
            }
            var reloaded = new PersistentThreadCatalog(fixture.json, fixture.data);
            JsonNode persisted = fixture.json.valueToTree(reloaded.find("guard-thread").orElseThrow().metadata());
            if (pendingCase) assertNoServerKeys(persisted);
            else {
                assertThat(persisted.path(AGENT).asText()).isEmpty();
                assertThat(persisted.path(BOOTSTRAP).isBoolean()).isTrue();
                assertThat(persisted.path(BOOTSTRAP).asBoolean()).isFalse();
                assertThat(persisted.path(SIGNATURE).asText()).isEqualTo(newSignature);
                try (var foundation = new DurableHaifaFoundation(fixture.data, new SecretKeySpec(new byte[32], "AES"));
                        var bridge = new HaifaRunBridge(LocalProbeModelTestSupport.binding(model), queryOnlyTools(), foundation)) {
                    AgentRunId actualRun = new AgentRunId(newRun);
                    FrozenInstructionDiagnostic frozen = bridge.frozenInstructionDiagnostic(actualRun).orElseThrow();
                    assertThat(frozen.runId()).isEqualTo(actualRun);
                    assertThat(frozen.instructionContentHash()).isEqualTo("sha256:" + newSignature);
                    assertThat(bridge.snapshot(actualRun).status()).isEqualTo(AgentRunStatus.COMPLETED);
                    assertThat(model.calls).as("public diagnostic reopening must not execute a model").hasValue(2);
                    fixture.proof("legacy-new-run-frozen-diagnostic", Map.of("run_id", frozen.runId().value(),
                            "instruction_content_hash", frozen.instructionContentHash(), "model_calls", model.calls.get()));
                }
            }
            assertNoWrite(fixture);
        }
    }

    private static void assertHumanInputs(JsonNode state, String... expected) {
        List<String> inputs = new ArrayList<>();
        state.path("values").path("messages").forEach(message -> {
            if (message.path("type").asText().equals("human")) inputs.add(message.path("content").asText());
        });
        assertThat(inputs).containsExactly(expected);
    }

    // These declarations only satisfy the composer before querying the persisted public Run.
    // Their synthetic schemas do not reproduce the production catalog/configuration digest.
    private static List<? extends JavaTool<?, ?>> queryOnlyTools() {
        return AgentSoulTestSupport.BASE_TOOLS.stream().sorted()
                .<JavaTool<QueryOnlyEmpty, QueryOnlyEmpty>>map(name -> new JavaTool<>() {
                    private final JavaToolSpec<QueryOnlyEmpty, QueryOnlyEmpty> spec = JavaToolSpec
                            .builder(name, QueryOnlyEmpty.class, QueryOnlyEmpty.class)
                            .description("Read-only diagnostic fixture declaration").pure().build();
                    @Override public JavaToolSpec<QueryOnlyEmpty, QueryOnlyEmpty> spec() { return spec; }
                    @Override public QueryOnlyEmpty invoke(QueryOnlyEmpty input, JavaToolContext context) {
                        throw new AssertionError("Public diagnostic reader must never execute a tool");
                    }
                }).toList();
    }

    public record QueryOnlyEmpty() {}

    private Pending admit(GatewayProcessFixture fixture, GuardModel model) throws Exception {
        try (var api = open(fixture, model)) {
            api.start(); fixture.base = URI.create("http://127.0.0.1:" + api.address().getPort());
            assertThat(fixture.send("POST", "/api/threads", "{\"thread_id\":\"guard-thread\"}").statusCode()).isEqualTo(200);
            var response = fixture.send("POST", "/api/threads/guard-thread/runs/stream", ordinary(fixture));
            String location = GatewayProcessFixture.location(response);
            String run = location.substring(location.lastIndexOf('/') + 1);
            fixture.runDiagnostic(location, "before-admission-status-and-card", model.responseDiagnostic(fixture));
            assertThat(fixture.get(location).path("status").asText()).isEqualTo(model.pending ? "interrupted" : "success");
            assertThat(model.calls).hasValue(1);
            String request = model.pending ? fixture.card(response.body()).path("artifact").path("human_input")
                    .path("request_id").asText() : "";
            if (model.pending) assertThat(request).isNotBlank();
            return new Pending(run, request);
        }
    }

    private static DeerFlowBootstrapApi open(GatewayProcessFixture fixture, GuardModel model) throws Exception {
        var binding = LocalProbeModelTestSupport.binding(model);
        return DeerFlowBootstrapApi.createForInteractionTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0), fixture.data,
                new SecretKeySpec(new byte[32], "AES"), ignored -> binding, List.of());
    }

    private static String ordinary(GatewayProcessFixture fixture) throws Exception {
        return ordinary(fixture, HUMAN);
    }

    private static String ordinary(GatewayProcessFixture fixture, String humanInput) throws Exception {
        return fixture.json.writeValueAsString(Map.of("assistant_id", "lead_agent",
                "input", Map.of("messages", List.of(Map.of("type", "human", "content", humanInput))),
                "context", Map.of("model_name", "super-harness-local-probe"),
                "stream_mode", List.of("messages-tuple", "updates", "custom"), "on_disconnect", "continue",
                "multitask_strategy", "reject"));
    }

    private static void assertRejected(GatewayProcessFixture fixture, Pending pending, GuardModel model) throws Exception {
        var rejected = fixture.send("POST", "/api/threads/guard-thread/runs/stream", fixture.approval(pending.request()));
        assertThat(rejected.statusCode()).isEqualTo(409);
        assertThat(fixture.json.readTree(rejected.body()).path("detail").asText()).isEqualTo(DETAIL);
        assertThat(rejected.body()).doesNotContain("Synthetic", "instruction", "current SOUL");
        assertThat(model.calls).hasValue(1);
    }

    private static void replace(PersistentThreadCatalog catalog, PersistentThreadCatalog.Entry original,
            Map<String, Object> metadata) throws Exception {
        catalog.save(new PersistentThreadCatalog.Entry(original.threadId(), metadata, original.createdAt(),
                original.updatedAt(), original.sessionId(), original.modelBindingIdentity(),
                original.interactionDisplays(), original.clarificationDisplays()));
    }

    private static void assertNoServerKeys(JsonNode metadata) {
        assertThat(metadata.has(AGENT)).isFalse(); assertThat(metadata.has(BOOTSTRAP)).isFalse();
        assertThat(metadata.has(SIGNATURE)).isFalse();
    }

    private static void assertNoWrite(GatewayProcessFixture fixture) throws Exception {
        var workspace = new RestrictedWorkspaceService(fixture.data.resolve("workspace"), WorkspaceLimits.defaults());
        var projects = new PersistentProjectWorkspaceService(fixture.data.resolve("workspace"),
                WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
        var binding = projects.requireThreadWorkspace("default", "guard-thread");
        assertThat(workspace.findArtifacts("default", binding.workspaceId(), "thread:guard-thread")).isEmpty();
        // This is the actual production write_file target, not a test counter standing in for it.
        assertThatThrownBy(() -> workspace.readVerifiedBytes("default", binding.workspaceId(), OUTPUT))
                .isInstanceOfSatisfying(WorkspaceException.class, failure -> assertThat(failure.error()).isEqualTo(WorkspaceError.FILE_NOT_FOUND));
    }

    private record Pending(String run, String request) {}
    private static final class GuardModel implements AgentChatModel {
        private final boolean pending;
        private final ControlledModel model;
        private final AtomicInteger calls;
        GuardModel(boolean pending) {
            this.pending = pending;
            model = new ControlledModel((request, call) -> ControlledModel.response(request, "m3-guard-" + call,
                    pending ? "" : "Fixture complete",
                    pending && call == 1 ? List.of(new ModelToolCall(new ProviderToolCallCorrelationId("m3-guard-write"),
                            "write_file", Map.of("path", OUTPUT, "content", "must not execute"))) : List.of(),
                    pending && call == 1 ? ModelFinishReason.TOOL_CALLS : ModelFinishReason.STOP,
                    ModelUsage.unpriced(4, 4)));
            calls = model.calls;
        }
        @Override public AgentChatResponse invoke(AgentChatRequest request) { return model.invoke(request); }
        @Override public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) { return model.invokeStreaming(request, sink); }

        Map<String, Object> responseDiagnostic(GatewayProcessFixture fixture) throws Exception {
            Map<String, Object> shape = Map.of("response_kind", "public-AgentChatResponse",
                    "model_calls", calls.get(), "response_present", model.lastResponse != null,
                    "tool_calls_count", pending && calls.get() == 1 ? 1 : 0,
                    "finish_reason", pending && calls.get() == 1 ? "TOOL_CALLS" : "STOP",
                    "usage_present", model.lastResponse != null, "input_tokens", 4, "output_tokens", 4);
            Map<String, Object> diagnostic = new LinkedHashMap<>(shape);
            diagnostic.put("response_shape_sha256", GatewayProcessFixture.hash(fixture.json.writeValueAsString(shape)));
            diagnostic.put("evidence_kind", "derived-typed-response-shape");
            return diagnostic;
        }
    }
}

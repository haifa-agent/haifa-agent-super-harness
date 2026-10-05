package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.server.testsupport.ControlledProvider;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import io.haifa.agent.superharness.server.testsupport.DelegationHttpFixture;
import io.haifa.agent.superharness.server.testsupport.SseEvents;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Production Gateway + public SDK JAR + real Provider HTTP, with raw synthetic evidence. */
class DeerFlowTaskDelegationApiTest {
    private Path data;
    private Path evidence;
    private GatewayFixture.HttpEvidence http;
    private final Map<String, String> env = Map.of(
            "HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W08_ASSEMBLY_KEY",
            "W08_ASSEMBLY_KEY", Base64.getEncoder().encodeToString(new byte[32]));

    @BeforeEach void prepare() throws Exception {
        data = GatewayFixture.dataDirectory(); evidence = GatewayFixture.evidenceDirectory();
        http = new GatewayFixture.HttpEvidence(evidence, Duration.ofSeconds(30));
    }
    @AfterEach void close() throws InterruptedException { http.close(); }

    @Test void gateAndWhitelistControlTheActualModelToolsAndPrompt() throws Exception {
        try (var provider = new ControlledProvider(evidence, true,
                (request, call) -> ControlledProvider.stop("gate-" + call, "w08-model", "done"));
                var gateway = GatewayFixture.production(data, Integer.getInteger("w08.gatewayPort", 14618), env, evidence)) {
            configure(gateway.base, provider.port());
            seed(gateway.base, "researcher", "CHILD-W08", 30, 5);
            String ordinaryLocation = execute(gateway.base, "off", input(false));
            assertThat(provider.requests.get(0).path("tools").findValuesAsText("name")).doesNotContain("task");
            JsonNode ordinary = http.get(gateway.base, ordinaryLocation, "ordinary-run.json");
            assertThat(ordinary.path("status").asText()).isEqualTo("success");
            assertThat(ordinary.path("lead_agent_tokens").asLong()).isEqualTo(7);
            assertThat(ordinary.path("subagent_tokens").asLong()).isZero();
            assertThat(ordinary.path("total_input_tokens").asLong()).isEqualTo(4);
            assertThat(ordinary.path("total_output_tokens").asLong()).isEqualTo(3);
            assertThat(ordinary.path("total_tokens").asLong()).isEqualTo(7);
            assertThat(ordinary.path("llm_call_count").asLong()).isEqualTo(1);
            execute(gateway.base, "on", input(true));
            assertThat(provider.requests.get(1).path("tools").findValuesAsText("name")).contains("task");
            assertThat(String.join("\n", provider.requests.get(1).path("messages").findValuesAsText("content")))
                    .contains("researcher", "general-purpose", "\"agent\"", "\"objective\"");
            assertThat(http.request(gateway.base, "POST", "/api/agents", """
                    {"name":"restricted","soul":"Bounded agent","allowed_subagents":[],"skills":[],"mcp_plugins":[]}
                    """).statusCode()).isEqualTo(201);
            ObjectNode none = input(true); ((ObjectNode) none.path("context")).put("agent_name", "restricted");
            execute(gateway.base, "none", none);
            assertThat(provider.requests.get(2).path("tools").findValuesAsText("name")).doesNotContain("task");
            assertThat(http.request(gateway.base, "PUT", "/api/agents/restricted", "{\"allowed_subagents\":[\"researcher\"]}").statusCode()).isEqualTo(200);
            execute(gateway.base, "intersection", none);
            String prompt = provider.requests.get(3).path("messages").toString();
            assertThat(prompt).contains("<name>researcher</name>").doesNotContain("<name>general-purpose</name>");
            assertThat(provider.failure.get()).isNull();
        }
    }

    @Test void childResultSseHistoryUsageAndRestartRetainExactTaskIdentity() throws Exception {
        AtomicInteger parent = new AtomicInteger();
        try (var provider = new ControlledProvider(evidence, true, (request, call) -> {
            if (child(request)) {
                assertThat(request.path("tools")).isEmpty();
                return ControlledProvider.stop("child-" + call, "w08-model", "CHILD-OUTPUT", 11, 13);
            }
            if (parent.incrementAndGet() == 1) return ControlledProvider.call("lead-" + call, "w08-model",
                    "provider-call-distinct", "task", Map.of("agent", "researcher", "objective", "Find a bounded fact"), 2, 3);
            return ControlledProvider.stop("lead-" + call, "w08-model", "LEAD-OUTPUT", 5, 7);
        })) {
            String location; JsonNode history;
            try (var gateway = GatewayFixture.production(data, Integer.getInteger("w08.gatewayPort", 14618), env, evidence)) {
                configure(gateway.base, provider.port()); seed(gateway.base, "researcher", "CHILD-W08", 30, 5);
                location = execute(gateway.base, "identity", input(true));
                JsonNode run = http.get(gateway.base, location, "run.json");
                assertThat(run.path("status").asText()).isEqualTo("success");
                assertThat(run.path("lead_agent_tokens").asLong()).isEqualTo(17);
                assertThat(run.path("subagent_tokens").asLong()).isEqualTo(24);
                assertThat(run.path("total_tokens").asLong()).isEqualTo(41);
                JsonNode threadUsage = http.get(gateway.base, "/api/threads/identity/token-usage", "thread-usage.json");
                assertThat(threadUsage.path("total_input_tokens").asLong()).isEqualTo(18);
                assertThat(threadUsage.path("total_output_tokens").asLong()).isEqualTo(23);
                assertThat(threadUsage.path("total_tokens").asLong()).isEqualTo(41);
                assertThat(threadUsage.path("total_runs").asInt()).isEqualTo(1);
                assertThat(threadUsage.path("by_model").elements().next().path("tokens").asLong()).isEqualTo(41);
                assertThat(threadUsage.path("by_model").elements().next().path("runs").asInt()).isEqualTo(1);
                history = http.get(gateway.base, "/api/threads/identity/state", "state.json");
                List<JsonNode> messages = messages(history);
                JsonNode invocation = messages.stream().filter(message -> !message.path("tool_calls").isEmpty()).findFirst().orElseThrow().path("tool_calls").get(0);
                String task = invocation.path("id").asText();
                assertThat(task).isNotBlank().isNotEqualTo("provider-call-distinct");
                assertThat(invocation.path("args").path("subagent_type").asText()).isEqualTo("researcher");
                assertThat(invocation.path("args").path("prompt").asText()).isEqualTo("Find a bounded fact");
                JsonNode result = messages.stream().filter(message -> message.path("name").asText().equals("task")).findFirst().orElseThrow();
                assertThat(result.path("tool_call_id").asText()).isEqualTo(task);
                assertThat(result.path("additional_kwargs").path("subagent_status").asText()).isEqualTo("completed");
                assertThat(result.path("additional_kwargs").path("subagent_result_brief").asText()).isEqualTo("CHILD-OUTPUT");
                assertThat(result.path("additional_kwargs").path("subagent_result_sha256").asText()).isEqualTo(GatewayFixture.hash("CHILD-OUTPUT"));
                String sse = Files.readString(evidence.resolve("identity.sse"));
                assertThat(sse).contains(task, "subagent_type", "subagent_result_sha256", "CHILD-OUTPUT");
                JsonNode threads = GatewayFixture.JSON.readTree(http.request(gateway.base, "POST", "/api/threads/search", "{}").body());
                assertThat(threads).hasSize(1); // Child Session cannot leak into product Thread catalog.
                assertThat(http.request(gateway.base, "PUT", "/api/subagents/researcher", "{\"enabled\":false}").statusCode()).isEqualTo(200);
            }
            int calls = provider.calls.get();
            try (var restarted = GatewayFixture.production(data, Integer.getInteger("w08.gatewayPort", 14618), env, evidence)) {
                JsonNode replay = http.get(restarted.base, "/api/threads/identity/state", "restart-state.json");
                assertThat(replay.path("values").path("messages")).isEqualTo(history.path("values").path("messages"));
                assertThat(http.get(restarted.base, location, "restart-run.json").path("subagent_tokens").asLong()).isEqualTo(24);
                assertThat(http.get(restarted.base, "/api/threads/identity/token-usage", "thread-usage-restart.json")
                        .path("total_tokens").asLong()).isEqualTo(41);
                assertThat(provider.calls.get()).isEqualTo(calls);
            }
            assertThat(provider.failure.get()).isNull();
        }
    }

    @Test void missingChildProviderUsageStaysUnknownAndExplicitZeroSurvivesRestart() throws Exception {
        AtomicInteger parent = new AtomicInteger();
        try (var provider = new ControlledProvider(evidence, true, (request, call) -> {
            if (child(request)) {
                ObjectNode response = ControlledProvider.stop("child-" + call, "w08-model", "child output", 0, 0);
                if (request.path("messages").toString().contains("usage omitted")) response.remove("usage");
                return response;
            }
            int lead = parent.incrementAndGet();
            return lead % 2 == 1 ? ControlledProvider.call("lead-" + call, "w08-model", "usage-task-" + call,
                    "task", Map.of("agent", "researcher", "objective", lead == 1 ? "usage omitted" : "usage explicit zero"))
                    : ControlledProvider.stop("lead-" + call, "w08-model", "parent continued");
        }, true)) {
            Map<String, String> locations = new java.util.LinkedHashMap<>();
            Map<String, JsonNode> states = new java.util.LinkedHashMap<>();
            try (var gateway = GatewayFixture.production(data, Integer.getInteger("w08.gatewayPort", 14618), env, evidence)) {
                configure(gateway.base, provider.port()); seed(gateway.base, "researcher", "CHILD-W08", 30, 5);
                for (String thread : List.of("child-usage-omitted", "child-usage-zero")) {
                    boolean omitted = thread.endsWith("omitted");
                    String location = execute(gateway.base, thread, input(true));
                    locations.put(thread, location);
                    JsonNode run = http.get(gateway.base, location, thread + "-run.json");
                    assertThat(run.path("status").asText()).isEqualTo("success");
                    JsonNode state = http.get(gateway.base, "/api/threads/" + thread + "/state", thread + "-state.json");
                    states.put(thread, state);
                    JsonNode result = messages(state).stream().filter(message -> message.path("name").asText().equals("task"))
                            .findFirst().orElseThrow();
                    JsonNode metadata = result.path("additional_kwargs");
                    assertThat(metadata.path("subagent_status").asText()).isEqualTo(omitted ? "failed" : "completed");
                    if (omitted) {
                        assertThat(metadata.has("subagent_token_usage")).isFalse();
                        assertThat(run.path("total_tokens").isNull()).isTrue();
                        assertThat(run.has("subagent_tokens")).isFalse();
                        assertThat(http.get(gateway.base, "/api/threads/" + thread + "/token-usage", thread + "-thread-usage.json").isNull()).isTrue();
                        assertThat(Files.readString(evidence.resolve(thread + ".sse"))).doesNotContain("subagent_token_usage");
                    } else {
                        assertThat(metadata.path("subagent_token_usage").path("total_tokens").isIntegralNumber()).isTrue();
                        assertThat(metadata.path("subagent_token_usage").path("total_tokens").asLong()).isZero();
                        assertThat(run.path("subagent_tokens").isIntegralNumber()).isTrue();
                        assertThat(run.path("subagent_tokens").asLong()).isZero();
                        assertThat(run.path("total_tokens").asLong()).isEqualTo(14);
                        assertThat(http.get(gateway.base, "/api/threads/" + thread + "/token-usage", thread + "-thread-usage.json")
                                .path("total_tokens").asLong()).isEqualTo(14);
                        var stream = SseEvents.read(Files.readString(evidence.resolve(thread + ".sse")));
                        var totals = java.util.stream.Stream.of("messages", "messages/partial", "messages/complete", "custom")
                                .flatMap(name -> stream.named(name).stream())
                                .flatMap(frame -> frame.data().findValues("subagent_token_usage").stream())
                                .map(usage -> usage.path("total_tokens")).toList();
                        assertThat(totals).isNotEmpty().allSatisfy(total -> {
                            assertThat(total.isIntegralNumber()).isTrue();
                            assertThat(total.asLong()).isZero();
                        });
                    }
                }
                assertThat(provider.responseDiagnostics.stream().filter(frame -> Boolean.FALSE.equals(frame.get("usage_present"))).toList())
                        .hasSize(1);
                var omittedFrame = provider.responseDiagnostics.stream()
                        .filter(frame -> Boolean.FALSE.equals(frame.get("usage_present"))).findFirst().orElseThrow();
                int omittedCall = ((Number) omittedFrame.get("call")).intValue();
                String suffix = omittedFrame.get("response_kind").equals("openai-compatible-sse") ? ".sse" : ".json";
                assertThat(Files.readString(evidence.resolve("provider-response-" + omittedCall + suffix)))
                        .contains("child output").doesNotContain("\"usage\"");
            }
            int calls = provider.calls.get();
            try (var restarted = GatewayFixture.production(data, Integer.getInteger("w08.gatewayPort", 14618), env, evidence)) {
                for (String thread : locations.keySet()) {
                    assertThat(http.get(restarted.base, "/api/threads/" + thread + "/state", thread + "-restart-state.json")
                            .path("values").path("messages")).isEqualTo(states.get(thread).path("values").path("messages"));
                    JsonNode run = http.get(restarted.base, locations.get(thread), thread + "-restart-run.json");
                    if (thread.endsWith("omitted")) {
                        assertThat(run.path("total_tokens").isNull()).isTrue();
                        assertThat(run.has("subagent_tokens")).isFalse();
                        assertThat(http.get(restarted.base, "/api/threads/" + thread + "/token-usage", thread + "-thread-usage-restart.json").isNull()).isTrue();
                    } else {
                        assertThat(run.path("subagent_tokens").isIntegralNumber()).isTrue();
                        assertThat(run.path("subagent_tokens").asLong()).isZero();
                        assertThat(http.get(restarted.base, "/api/threads/" + thread + "/token-usage", thread + "-thread-usage-restart.json")
                                .path("total_tokens").asLong()).isEqualTo(14);
                    }
                }
                assertThat(provider.calls.get()).isEqualTo(calls);
            }
            assertThat(provider.failure.get()).isNull();
        }
    }

    @Test void unknownChildIsRejectedBeforeCreationAndParentContinues() throws Exception {
        try (var provider = new ControlledProvider(evidence, true, (request, call) -> call == 1
                ? ControlledProvider.call("unknown-" + call, "w08-model", "unknown-provider", "task",
                        Map.of("agent", "missing-child", "objective", "Bounded request"))
                : ControlledProvider.stop("lead-" + call, "w08-model", "continued"));
                var gateway = GatewayFixture.production(data, Integer.getInteger("w08.gatewayPort", 14618), env, evidence)) {
            configure(gateway.base, provider.port());
            String location = execute(gateway.base, "unknown", input(true));
            assertThat(http.get(gateway.base, location, "run.json").path("status").asText()).isEqualTo("success");
            JsonNode result = messages(http.get(gateway.base, "/api/threads/unknown/state", "state.json")).stream()
                    .filter(message -> message.path("name").asText().equals("task")).findFirst().orElseThrow();
            assertThat(result.path("additional_kwargs").path("subagent_status").asText()).isEqualTo("failed");
            assertThat(result.path("additional_kwargs").path("subagent_error").asText()).isNotBlank();
            assertThat(provider.calls.get()).isEqualTo(2);
            assertThat(result.path("additional_kwargs").has("subagent_token_usage")).isFalse();
        }
    }

    @Test void childCanUseAProductionReadToolWithoutEnteringInteractiveApproval() throws Exception {
        try (var provider = new ControlledProvider(evidence, true, DelegationHttpFixture.readSequence("researcher"));
                var gateway = GatewayFixture.production(data, Integer.getInteger("w08.gatewayPort", 14618), env, evidence)) {
            configure(gateway.base, provider.port());
            DelegationHttpFixture.seed(http, gateway.base, "researcher", 30, 5, List.of("list_uploaded_files"));
            String location = execute(gateway.base, "read-child", input(true));
            assertThat(http.get(gateway.base, location, "run.json").path("status").asText()).isEqualTo("success");
            assertThat(provider.calls.get()).isEqualTo(4);
            JsonNode childFollowUp = provider.requests.stream().filter(DelegationHttpFixture::isChild).skip(1).findFirst().orElseThrow();
            assertThat(childFollowUp.path("messages").findValuesAsText("role")).contains("tool");
            JsonNode result = java.util.stream.StreamSupport.stream(childFollowUp.path("messages").spliterator(), false)
                    .filter(message -> message.path("role").asText().equals("tool"))
                    .map(message -> {
                        try { return GatewayFixture.JSON.readTree(message.path("content").asText()); }
                        catch (java.io.IOException malformed) { throw new AssertionError("Child Tool result is not JSON", malformed); }
                    }).findFirst().orElseThrow();
            assertThat(result.path("structuredData").path("error").asText()).isEmpty();
            assertThat(GatewayFixture.JSON.readTree(result.path("structuredData").path("content").asText()).path("files")).isEmpty();
            assertThat(childFollowUp.path("tools").findValuesAsText("name")).containsExactly("list_uploaded_files");
            assertThat(Files.readString(evidence.resolve("read-child.sse"))).doesNotContain("human_input_request", "WAITING_APPROVAL");
            assertThat(provider.failure.get()).isNull();
        }
    }

    @Test void childApprovalRequiredWriteIsDeniedWithoutExecutingAndChildContinues() throws Exception {
        AtomicInteger parent = new AtomicInteger(), childCalls = new AtomicInteger();
        try (var provider = new ControlledProvider(evidence, true, (request, call) -> {
            if (child(request)) {
                if (childCalls.incrementAndGet() == 1) {
                    assertThat(request.path("tools").findValuesAsText("name")).containsExactly("write_file");
                    return ControlledProvider.call("write-" + call, "w08-model", "child-write-provider", "write_file",
                            Map.of("path", "/mnt/user-data/outputs/denied.txt", "content", "must not exist"));
                }
                assertThat(request.path("messages").findValuesAsText("role")).contains("tool");
                assertThat(java.util.stream.StreamSupport.stream(request.path("messages").spliterator(), false)
                        .filter(message -> message.path("role").asText().equals("tool"))
                        .map(message -> message.path("content").asText()).toList())
                        .anySatisfy(content -> assertThat(content).contains("denied by policy"));
                return ControlledProvider.stop("child-" + call, "w08-model", "continued-after-denial");
            }
            return parent.incrementAndGet() == 1 ? ControlledProvider.call("lead-" + call, "w08-model",
                    "parent-task-provider", "task", Map.of("agent", "researcher", "objective", "Bounded write attempt"))
                    : ControlledProvider.stop("lead-" + call, "w08-model", "parent-continued");
        }); var gateway = GatewayFixture.production(data, Integer.getInteger("w08.gatewayPort", 14618), env, evidence)) {
            configure(gateway.base, provider.port());
            DelegationHttpFixture.seed(http, gateway.base, "researcher", 30, 5, List.of("write_file"));
            String location = execute(gateway.base, "noninteractive", input(true));
            assertThat(http.get(gateway.base, location, "run.json").path("status").asText()).isEqualTo("success");
            assertThat(childCalls.get()).isEqualTo(2);
            assertThat(http.request(gateway.base, "GET", "/api/threads/noninteractive/artifacts/mnt/user-data/outputs/denied.txt", null).statusCode()).isEqualTo(404);
            assertThat(Files.readString(evidence.resolve("noninteractive.sse"))).doesNotContain("WAITING_APPROVAL", "human_input_request");
            assertThat(provider.failure.get()).isNull();
        }
    }

    @Test void childTimeoutComesFromItsProfileAndParentContinues() throws Exception {
        AtomicInteger parent = new AtomicInteger();
        var provider = new ControlledProvider(evidence, true, (request, call) -> {
            if (child(request)) {
                Thread.sleep(1800); // Real controlled HTTP latency exceeds the declared SDK Child wall.
                return ControlledProvider.stop("late-" + call, "w08-model", "late child");
            }
            return parent.incrementAndGet() == 1 ? ControlledProvider.call("lead-" + call, "w08-model",
                    "timeout-provider", "task", Map.of("agent", "researcher", "objective", "Bounded slow request"))
                    : ControlledProvider.stop("lead-" + call, "w08-model", "parent continued");
        }, (request, authorization) -> true, DelegationHttpFixture::isChild);
        try (provider;
                var gateway = GatewayFixture.production(data, Integer.getInteger("w08.gatewayPort", 14618), env, evidence)) {
            configure(gateway.base, provider.port()); seed(gateway.base, "researcher", "CHILD-W08", 1, 5);
            String location = execute(gateway.base, "timeout", input(true));
            assertThat(http.get(gateway.base, location, "run.json").path("status").asText()).isEqualTo("success");
            JsonNode result = messages(http.get(gateway.base, "/api/threads/timeout/state", "state.json")).stream()
                    .filter(message -> message.path("name").asText().equals("task")).findFirst().orElseThrow();
            assertThat(result.path("additional_kwargs").path("subagent_status").asText()).isEqualTo("timed_out");
            assertThat(parent.get()).isEqualTo(2);
        }
        assertThat(provider.expectedResponseAborts.get()).isEqualTo(1);
    }

    @Test void selectedChildModelHasARealModelContribution() throws Exception {
        AtomicInteger parent = new AtomicInteger();
        try (var provider = new ControlledProvider(evidence, true, (request, call) -> {
            String actual = request.path("model").asText();
            if (child(request)) {
                assertThat(actual).isEqualTo("alternate-provider-model");
                return ControlledProvider.stop("child-" + call, actual, "alternate model output");
            }
            return parent.incrementAndGet() == 1 ? ControlledProvider.call("lead-" + call, actual,
                    "alternate-provider", "task", Map.of("agent", "researcher", "objective", "Use a specialist model"))
                    : ControlledProvider.stop("lead-" + call, actual, "parent output");
        }, (request, authorization) -> request.path("model").asText().equals("alternate-provider-model")
                ? "Bearer w08-alt-key".equals(authorization)
                : "Bearer controlled-key".equals(authorization));
                var gateway = GatewayFixture.production(data, Integer.getInteger("w08.gatewayPort", 14618), env, evidence)) {
            configure(gateway.base, provider.port());
            String alternate = """
                    {"config":{"name":"alternate","provider":"openai-compatible","model":"alternate-provider-model",
                    "base_url":"http://127.0.0.1:%d/v1","enabled":true,"supports_vision":false,
                    "context_window":32768,"max_tokens":1024,"api_key":"w08-alt-key"},"expected_revision":null}
                    """.formatted(provider.port());
            assertThat(http.request(gateway.base, "PUT", "/api/managed-models", alternate).statusCode()).isEqualTo(200);
            seed(gateway.base, "researcher", "CHILD-W08", 30, 5);
            assertThat(http.request(gateway.base, "PUT", "/api/subagents/researcher", "{\"model\":\"alternate\"}").statusCode()).isEqualTo(200);
            String location = execute(gateway.base, "alternate", input(true));
            assertThat(http.get(gateway.base, location, "run.json").path("status").asText()).isEqualTo("success");
            assertThat(provider.requests.stream().filter(DelegationHttpFixture::isChild).count()).isEqualTo(1);
            JsonNode threadUsage = http.get(gateway.base, "/api/threads/alternate/token-usage", "alternate-thread-usage.json");
            assertThat(threadUsage.path("total_tokens").asLong()).isEqualTo(21);
            assertThat(threadUsage.path("by_model").size()).isEqualTo(2);
            var models = threadUsage.path("by_model").fields();
            while (models.hasNext()) {
                var model = models.next();
                assertThat(model.getValue().path("tokens").asLong())
                        .isEqualTo(model.getKey().endsWith("alternate-provider-model") ? 7 : 14);
                assertThat(model.getValue().path("runs").asInt()).isEqualTo(1);
            }
            assertThat(provider.failure.get()).isNull();
        }
    }

    @Test @Tag("slow") void waitingForChildBeyondParentIdleDoesNotTerminateParent() throws Exception {
        // Oracle10 exercises the unchanged production60s idle limit. A shorter delay
        // cannot prove this boundary; this case deliberately budgets90s for HTTP.
        http.close(); http = new GatewayFixture.HttpEvidence(evidence, Duration.ofSeconds(90));
        AtomicInteger parent = new AtomicInteger();
        try (var provider = new ControlledProvider(evidence, true, (request, call) -> {
            if (child(request)) {
                Thread.sleep(64_000);
                return ControlledProvider.stop("long-child-" + call, "w08-model", "child completed after parent idle");
            }
            return parent.incrementAndGet() == 1 ? ControlledProvider.call("lead-" + call, "w08-model",
                    "idle-provider", "task", Map.of("agent", "researcher", "objective", "A bounded long investigation"))
                    : ControlledProvider.stop("lead-" + call, "w08-model", "parent continued");
        }); var gateway = GatewayFixture.production(data, Integer.getInteger("w08.gatewayPort", 14618), env, evidence)) {
            configure(gateway.base, provider.port()); seed(gateway.base, "researcher", "CHILD-W08", 75, 5);
            String location = execute(gateway.base, "beyond-idle", input(true));
            assertThat(http.get(gateway.base, location, "run.json").path("status").asText()).isEqualTo("success");
            JsonNode result = messages(http.get(gateway.base, "/api/threads/beyond-idle/state", "state.json")).stream()
                    .filter(message -> message.path("name").asText().equals("task")).findFirst().orElseThrow();
            assertThat(result.path("additional_kwargs").path("subagent_status").asText()).isEqualTo("completed");
            assertThat(parent.get()).isEqualTo(2);
            assertThat(provider.failure.get()).isNull();
        }
    }

    @Test void directoryDisableOnlyAffectsLaterRunAndKeepsAdmittedChildFrozen() throws Exception {
        var admitted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger parent = new AtomicInteger(), children = new AtomicInteger();
        try (var provider = new ControlledProvider(evidence, true, (request, call) -> {
            if (child(request)) {
                children.incrementAndGet(); admitted.countDown();
                if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Child release deadline expired");
                return ControlledProvider.stop("child-" + call, "w08-model", "frozen child output");
            }
            int leadCall = parent.incrementAndGet();
            if (leadCall == 1 || leadCall == 3) return ControlledProvider.call("lead-" + call, "w08-model",
                    "frozen-provider-" + call, "task", Map.of("agent", "researcher", "objective", "Bounded work"));
            return ControlledProvider.stop("lead-" + call, "w08-model", "lead completed");
        }); var gateway = GatewayFixture.production(data, Integer.getInteger("w08.gatewayPort", 14618), env, evidence);
                var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            configure(gateway.base, provider.port()); seed(gateway.base, "researcher", "CHILD-W08", 30, 5);
            var first = executor.submit(() -> execute(gateway.base, "frozen", input(true)));
            try {
                assertThat(admitted.await(10, java.util.concurrent.TimeUnit.SECONDS)).as("real Child model admission").isTrue();
                assertThat(http.request(gateway.base, "PUT", "/api/subagents/researcher", "{\"enabled\":false}").statusCode()).isEqualTo(200);
            } finally { release.countDown(); }
            String firstLocation = first.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(http.get(gateway.base, firstLocation, "frozen-run.json").path("status").asText()).isEqualTo("success");
            var second = http.request(gateway.base, "POST", "/api/threads/frozen/runs/stream", input(true).toString(), "later.sse");
            assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
            assertThat(children.get()).isEqualTo(1);
            var results = messages(http.get(gateway.base, "/api/threads/frozen/state", "state.json")).stream()
                    .filter(message -> message.path("name").asText().equals("task")).toList();
            assertThat(results).hasSize(2);
            assertThat(results.get(0).path("additional_kwargs").path("subagent_status").asText()).isEqualTo("completed");
            assertThat(results.get(1).path("additional_kwargs").path("subagent_status").asText()).isEqualTo("failed");
            assertThat(provider.requests.get(3).path("messages").toString()).doesNotContain("<name>researcher</name>");
            assertThat(provider.failure.get()).isNull();
        }
    }

    @Test void actualDiscoveredMcpNamesAreInheritedAndEmptyToolsRemainEmpty() throws Exception {
        AtomicInteger parent = new AtomicInteger();
        try (var mcp = new ClarificationSdkSiblingRunHttpTest.LocalMcpServer();
                var provider = new ControlledProvider(evidence, true, (request, call) -> {
                    if (child(request)) return ControlledProvider.stop("child-" + call, "w08-model", "inherited tools inspected");
                    return parent.incrementAndGet() % 2 == 1
                            ? ControlledProvider.call("lead-" + call, "w08-model", "mcp-task-" + call,
                                    "task", Map.of("agent", "researcher", "objective", "Inspect available tool declarations"))
                            : ControlledProvider.stop("lead-" + call, "w08-model", "lead completed");
                }); var gateway = GatewayFixture.production(data, Integer.getInteger("w08.gatewayPort", 14618), env, evidence)) {
            configure(gateway.base, provider.port());
            String servers = """
                    {"mcp_servers":{"w08-discovery":{"type":"http","url":"%s","read_only":true,
                    "tool_name_prefix":true,"session_init_timeout":20,"tool_call_timeout":20,
                    "tools":{"sibling_probe":{}}}}}
                    """.formatted(mcp.url());
            assertThat(http.request(gateway.base, "POST", "/api/mcp/config/servers", servers).statusCode()).isEqualTo(200);
            DelegationHttpFixture.seed(http, gateway.base, "researcher", 30, 5, null);
            String inherited = execute(gateway.base, "mcp-inherit", input(true));
            assertThat(http.get(gateway.base, inherited, "mcp-inherit-run.json").path("status").asText()).isEqualTo("success");
            JsonNode parentRequest = provider.requests.get(0);
            String discoveredAlias = java.util.stream.StreamSupport.stream(parentRequest.path("tools").spliterator(), false)
                    .map(tool -> tool.path("function"))
                    .filter(tool -> tool.path("description").asText().contains("Deterministic local probe used to count real tools/call requests."))
                    .map(tool -> tool.path("name").asText()).findFirst().orElseThrow();
            var expected = new java.util.TreeSet<>(java.util.stream.StreamSupport.stream(parentRequest.path("tools").spliterator(), false)
                    .map(tool -> tool.path("function").path("name").asText()).toList());
            expected.removeAll(java.util.Set.of("task", "ask_clarification", "present_files"));
            assertThat(java.util.stream.StreamSupport.stream(provider.requests.get(1).path("tools").spliterator(), false)
                    .map(tool -> tool.path("function").path("name").asText()).toList())
                    .contains(discoveredAlias).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(http.request(gateway.base, "PUT", "/api/subagents/researcher", "{\"tools\":[]}").statusCode()).isEqualTo(200);
            String empty = execute(gateway.base, "mcp-empty", input(true));
            assertThat(http.get(gateway.base, empty, "mcp-empty-run.json").path("status").asText()).isEqualTo("success");
            assertThat(provider.requests.get(4).path("tools")).isEmpty();
            assertThat(mcp.toolsCallCount()).isZero(); // This case proves discovery/inheritance, not invocation policy.
            assertThat(provider.failure.get()).isNull();
        }
    }

    @Test void legacyParentIdRemainsAValidChildNameAndOldRunHistorySurvivesNewDelegation() throws Exception {
        AtomicInteger parent = new AtomicInteger();
        try (var provider = new ControlledProvider(evidence, true, (request, call) -> {
            if (child(request)) return ControlledProvider.stop("child-" + call, "w08-model", "legacy-name child completed");
            return parent.incrementAndGet() == 2
                    ? ControlledProvider.call("lead-" + call, "w08-model", "legacy-name-task", "task",
                            Map.of("agent", "haifa-super-harness-agent", "objective", "Bounded independent work"))
                    : ControlledProvider.stop("lead-" + call, "w08-model", "legacy thread continued");
        })) {
            String oldLocation; JsonNode admittedHistory;
            try (var gateway = GatewayFixture.production(data, Integer.getInteger("w08.gatewayPort", 14618), env, evidence)) {
                configure(gateway.base, provider.port());
                seed(gateway.base, "haifa-super-harness-agent", "CHILD-W08", 30, 5);
                oldLocation = execute(gateway.base, "legacy-thread", input(false));
                assertThat(provider.requests.get(0).path("tools").findValuesAsText("name")).doesNotContain("task");
                assertThat(http.get(gateway.base, oldLocation, "legacy-old-run.json").path("status").asText()).isEqualTo("success");
                var delegated = http.request(gateway.base, "POST", "/api/threads/legacy-thread/runs/stream",
                        input(true).toString(), "legacy-delegation.sse");
                assertThat(delegated.statusCode()).as(delegated.body()).isEqualTo(200);
                assertThat(http.get(gateway.base, delegated.headers().firstValue("content-location").orElseThrow(),
                        "legacy-delegation-run.json").path("status").asText()).isEqualTo("success");
                admittedHistory = http.get(gateway.base, "/api/threads/legacy-thread/state", "legacy-state.json");
                var result = messages(admittedHistory).stream().filter(message -> message.path("name").asText().equals("task"))
                        .findFirst().orElseThrow();
                assertThat(result.path("additional_kwargs").path("subagent_status").asText()).isEqualTo("completed");
                assertThat(result.path("additional_kwargs").path("subagent_result_brief").asText()).isEqualTo("legacy-name child completed");
                assertThat(http.get(gateway.base, oldLocation, "legacy-old-run-after.json").path("status").asText()).isEqualTo("success");
            }
            int calls = provider.calls.get();
            try (var restarted = GatewayFixture.production(data, Integer.getInteger("w08.gatewayPort", 14618), env, evidence)) {
                assertThat(http.get(restarted.base, oldLocation, "legacy-old-run-reopen.json").path("status").asText()).isEqualTo("success");
                assertThat(http.get(restarted.base, "/api/threads/legacy-thread/state", "legacy-state-reopen.json")
                        .path("values").path("messages")).isEqualTo(admittedHistory.path("values").path("messages"));
                assertThat(provider.calls.get()).isEqualTo(calls);
            }
            assertThat(provider.failure.get()).isNull();
        }
    }

    private void configure(URI base, int port) throws Exception {
        DelegationHttpFixture.configure(http, base, port);
    }
    private void seed(URI base, String name, String prompt, int timeout, int turns) throws Exception {
        ObjectNode definition = GatewayFixture.JSON.createObjectNode().put("name", name).put("description", "Bounded independent research")
                .put("system_prompt", prompt).put("timeout_seconds", timeout).put("max_turns", turns);
        definition.putArray("tools");
        assertThat(http.request(base, "POST", "/api/subagents", definition.toString()).statusCode()).isEqualTo(201);
    }
    private static ObjectNode input(boolean enabled) {
        return DelegationHttpFixture.input(enabled);
    }
    private String execute(URI base, String thread, ObjectNode input) throws Exception {
        return DelegationHttpFixture.execute(http, base, thread, input);
    }
    private static boolean child(JsonNode request) { return DelegationHttpFixture.isChild(request); }
    private static List<JsonNode> messages(JsonNode state) {
        return DelegationHttpFixture.messages(state);
    }
}

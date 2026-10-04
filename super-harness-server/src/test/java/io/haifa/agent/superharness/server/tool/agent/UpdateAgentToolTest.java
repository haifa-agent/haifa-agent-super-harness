package io.haifa.agent.superharness.server.tool.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static io.haifa.agent.superharness.server.testsupport.AgentSoulTestSupport.*;
import static io.haifa.agent.superharness.server.testsupport.GatewayFixture.*;
import static io.haifa.agent.superharness.server.testsupport.ControlledModel.*;

import io.haifa.agent.superharness.server.run.DurableHaifaFoundation;
import io.haifa.agent.superharness.server.testsupport.ControlledModel;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Oracle07/08/11 use actual SDK Tool calls against the existing product store. */
class UpdateAgentToolTest {
    @TempDir Path data;

    @org.junit.jupiter.api.BeforeEach
    void isolatedData() throws Exception {
        data = dataDirectory();
    }

    @Test
    void componentOwnerCancellationDeadlineSessionAndMissingScopeNeverReachMutation() {
        var mutations = new java.util.concurrent.atomic.AtomicInteger();
        var tool = new ProductionUpdateAgentTool("agent-a", input -> {
            mutations.incrementAndGet();
            return new ProductionUpdateAgentTool.Output(true, "component callback");
        });
        var input = new ProductionUpdateAgentTool.Input(java.util.Optional.of("synthetic nonblank"),
                java.util.Optional.empty(), java.util.Optional.empty());
        for (var rejected : List.of(context("other-user", false, false, true),
                context("default-public-user", true, false, true),
                context("default-public-user", false, true, true),
                context("default-public-user", false, false, false))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> tool.invoke(input, rejected))
                    .isInstanceOf(RuntimeException.class);
            assertThat(mutations).hasValue(0);
        }
        var noScope = new ProductionUpdateAgentTool(null, ignored -> {
            mutations.incrementAndGet();
            return new ProductionUpdateAgentTool.Output(true, "component callback");
        });
        assertThat(noScope.invoke(input, context("default-public-user", false, false, true)).success()).isFalse();
        assertThat(mutations).hasValue(0);
    }

    @Test
    void replacementAndOnlyDescriptionOrDisplayNamePreserveUnprovidedFields() throws Exception {
        var model = new ControlledModel((request, index) -> switch (index) {
            case 1 -> call(request, "update_agent", Map.of("soul", "synthetic full replacement"));
            case 2 -> call(request, "update_agent", Map.of("description", "new description"));
            case 3 -> call(request, "update_agent", Map.of("display_name", "New Display"));
            default -> stop(request);
        });
        try (var fixture = new GatewayFixture(data, model)) {
            fixture.seed("agent-a", "synthetic original prefix and tail");
            fixture.seed("agent-b", "synthetic-B");
            var preserved = JSON.createObjectNode();
            preserved.putObject("knowledge_scope").put("project", "synthetic-project");
            preserved.putArray("tool_groups").add("synthetic-group");
            fixture.update("agent-a", preserved);
            var before = fixture.agent("agent-a");
            String b = hash(fixture.agent("agent-b").toString());
            fixture.thread("update-fields");
            fixture.completed(fixture.run("update-fields", "agent-a", false));
            var after = fixture.agent("agent-a");
            assertThat(hash(after.path("soul").asText())).isEqualTo(hash("synthetic full replacement"));
            assertThat(after.path("description").asText()).isEqualTo("new description");
            assertThat(after.path("display_name").asText()).isEqualTo("New Display");
            for (String field : List.of("knowledge_scope", "tool_groups", "skills", "mcp_plugins", "model",
                    "model_settings", "allowed_subagents")) {
                assertThat(hash(after.path(field).toString())).as("preserve %s", field)
                        .isEqualTo(hash(before.path(field).toString()));
            }
            assertThat(hash(fixture.agent("agent-b").toString())).isEqualTo(b);
            assertThat(fixture.results("update-fields", "update_agent")).hasSize(3);
            assertThat(model.requests.size()).isEqualTo(4);
            for (var request : model.requests) {
                // Updating persistent product configuration must not change this active Run.
                soulEquals(instructions(request), "synthetic original prefix and tail");
                assertThat(names(request)).contains("update_agent").doesNotContain("setup_agent");
                requestEvidence(fixture, request);
            }
        }
    }

    static Stream<Arguments> forbiddenArguments() {
        return Stream.of("skills", "tool_groups", "mcp_plugins", "model", "agent_name", "name")
                .flatMap(field -> Stream.of(false, true).map(mixed -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put(field, field.equals("model") ? "different-model"
                            : field.endsWith("name") ? "agent-b" : List.of("synthetic-capability"));
                    if (mixed) payload.put("soul", "synthetic must-not-be-written");
                    return Arguments.of(field, mixed, payload);
                }));
    }

    @ParameterizedTest(name = "reject {0}, mixed={1}")
    @MethodSource("forbiddenArguments")
    void undeclaredCapabilityOrForeignTargetIsRejectedBeforeAnyPartialWrite(
            String field, boolean mixed, Map<String, Object> payload) throws Exception {
        var model = new ControlledModel((request, index) -> index == 1
                ? call(request, "update_agent", payload) : stop(request));
        try (var fixture = new GatewayFixture(data, model)) {
            fixture.seed("agent-a", "synthetic-A");
            fixture.seed("agent-b", "synthetic-B");
            var positiveControl = replacePositiveControl(fixture, "agent-a");
            String a = hash(fixture.agent("agent-a").toString());
            String b = hash(fixture.agent("agent-b").toString());
            ConfigFingerprint before = ConfigFingerprint.read(data);
            fixture.thread("update-rejected");
            String location = fixture.completed(fixture.run("update-rejected", "agent-a", false));
            var run = fixture.get(location);
            ConfigFingerprint after = ConfigFingerprint.read(data);
            String afterA = hash(fixture.agent("agent-a").toString());
            String afterB = hash(fixture.agent("agent-b").toString());
            var results = fixture.results("update-rejected", "update_agent");
            var summary = JSON.createObjectNode().put("evidence_kind", "derived_safe_summary")
                    .put("unsupported_field", field).put("mixed_allowed_soul", mixed)
                    .put("run_location", location).put("thread_id", "update-rejected")
                    .put("model_request_count", model.requests.size()).put("tool_message_count", results.size())
                    .put("same_identity", before.key().equals(after.key()))
                    .put("same_mtime", before.modified().equals(after.modified()))
                    .put("same_size", before.size() == after.size()).put("same_bytes", before.digest().equals(after.digest()))
                    .put("existing_agent_a_same", a.equals(afterA)).put("existing_agent_b_same", b.equals(afterB));
            summary.set("positive_control", positiveControl);
            summary.set("config_before", before.safeSummary());
            summary.set("config_after", after.safeSummary());
            summary.set("public_run", safeRunSummary(run));
            String expectedRunId = location.substring(location.lastIndexOf('/') + 1);
            var modelRunIds = summary.putArray("model_run_ids");
            for (var request : model.requests) modelRunIds.add(request.runId().value());
            summary.put("all_model_requests_match_run", model.requests.stream()
                    .allMatch(request -> request.runId().value().equals(expectedRunId)));
            Object forbiddenValue = payload.get(field);
            var forbiddenTexts = forbiddenValue instanceof List<?> values ? values.stream().map(Object::toString).toList()
                    : List.of(forbiddenValue.toString());
            var messageFacts = summary.putArray("tool_messages");
            for (var result : results) {
                String content = result.path("content").asText();
                String lower = content.toLowerCase(java.util.Locale.ROOT);
                messageFacts.addObject().put("content_chars", content.length()).put("content_sha256", hash(content))
                        .put("is_tool_call_failed", content.equals("tool_call_failed"))
                        .put("has_error_marker", lower.contains("error") || lower.contains("false"))
                        .put("has_unsupported", lower.contains("unsupported"))
                        .put("has_allowed_field_names", lower.contains("soul") && lower.contains("description")
                                && lower.contains("display_name"))
                        .put("contains_rejected_value", forbiddenTexts.stream().anyMatch(content::contains))
                        .put("contains_mixed_soul", content.contains("synthetic must-not-be-written"))
                        .put("haifa_tool_result_present", result.path("additional_kwargs").has("haifa_tool_result"));
            }
            var repairFacts = summary.putArray("next_model_tool_messages");
            var repairMessages = model.requests.size() < 2 ? List.<io.haifa.agent.model.api.ModelMessage>of()
                    : model.requests.getLast().messages().stream()
                            .filter(message -> message.role().name().equals("TOOL")).toList();
            for (var message : repairMessages) {
                String text = message.content();
                repairFacts.addObject().put("chars", text.length()).put("sha256", hash(text))
                        .put("has_schema_repair_hint", text.contains("Repair the tool arguments"))
                        .put("contains_rejected_value", forbiddenTexts.stream().anyMatch(text::contains))
                        .put("contains_mixed_soul", text.contains("synthetic must-not-be-written"));
            }
            java.nio.file.Files.writeString(fixture.evidence.resolve("unknown-field.safe-summary.json"),
                    JSON.writeValueAsString(summary));

            assertThat(after).as("no configuration replace: physical identity, mtime and bytes unchanged").isEqualTo(before);
            assertThat(afterA).isEqualTo(a);
            assertThat(afterB).isEqualTo(b);
            var toolCalls = run.path("diagnostics").path("tool_calls");
            assertThat(toolCalls.size()).isEqualTo(1);
            assertThat(toolCalls.get(0).path("status").asText()).isEqualTo("CANCELLED");
            assertThat(results.size()).isEqualTo(1);
            assertThat(results.getFirst().path("additional_kwargs").has("haifa_tool_result"))
                    .as("schema-rejected Tool never produced a success result").isFalse();
            assertThat(model.requests.size()).isEqualTo(2);
            assertThat(model.requests.stream().allMatch(request -> request.runId().value().equals(expectedRunId)))
                    .as("all actual model requests belong to the admitted Run").isTrue();
            assertThat(repairMessages.size()).isEqualTo(1);
            String repair = repairMessages.getFirst().content();
            String expectedRepair = "Tool request rejected. Repair the tool arguments: $/" + field
                    + " is not allowed for the selected mode.";
            assertThat(hash(repair)).as("exact value-free SDK schema repair hint").isEqualTo(hash(expectedRepair));
            assertThat(forbiddenTexts.stream().anyMatch(repair::contains)).as("no rejected parameter value in repair hint").isFalse();
            assertThat(repair.contains("synthetic must-not-be-written")).as("no mixed SOUL value in repair hint").isFalse();
            String error = results.getFirst().path("content").asText().toLowerCase(java.util.Locale.ROOT);
            assertThat(forbiddenTexts.stream().anyMatch(error::contains)).as("no rejected parameter value in product error").isFalse();
            assertThat(error.contains("synthetic must-not-be-written")).as("no mixed SOUL value in product error").isFalse();
            assertThat(error.contains("error") || error.contains("false")).as("actual SDK rejected Tool call").isTrue();
            assertThat(error.contains(field) || error.contains("schema") || error.contains("undeclared")
                    || error.contains("unknown") || error.contains("unexpected"))
                    .as("SDK error explains undeclared input").isTrue();
            assertThat(error.contains("unsupported")).as("product error states unsupported input").isTrue();
            assertThat(error.contains("soul") && error.contains("description") && error.contains("display_name"))
                    .as("product error explains the three supported fields").isTrue();
        }
    }

    @Test
    void blankSoulCannotReplaceExistingConfiguration() throws Exception {
        var model = new ControlledModel((request, index) -> index == 1
                ? call(request, "update_agent", Map.of("soul", " \n\t", "description", "must-not-write"))
                : stop(request));
        try (var fixture = new GatewayFixture(data, model)) {
            fixture.seed("agent-a", "synthetic-A");
            replacePositiveControl(fixture, "agent-a");
            ConfigFingerprint before = ConfigFingerprint.read(data);
            fixture.thread("update-blank");
            fixture.completed(fixture.run("update-blank", "agent-a", false));
            before.assertUnchanged(data);
            var results = fixture.results("update-blank", "update_agent");
            assertThat(results.size()).isEqualTo(1);
            assertThat(results.getFirst().path("content").asText().toLowerCase(java.util.Locale.ROOT).contains("soul"))
                    .isTrue();
        }
    }

    @Test
    void frozenNullishStringsKeepValuesAndOrdinaryTextReplacesThem() throws Exception {
        String[] firstSoul = new String[1];
        String[] firstDisplay = new String[1];
        var model = new ControlledModel((request, index) -> switch (index) {
            case 1 -> call(request, "update_agent", Map.of("soul", " null ", "description", "changed", "display_name", "None"));
            case 2 -> {
                try {
                    var stored = JSON.readTree(data.resolve("owners/default/configuration.json").toFile()).path("agents").get(0);
                    firstSoul[0] = hash(stored.path("soul").asText());
                    firstDisplay[0] = stored.path("display_name").asText();
                } catch (Exception exception) { throw new AssertionError("config snapshot unavailable", exception); }
                yield call(request, "update_agent", Map.of("soul", "ordinary null word", "description", "undefined", "display_name", "New Display"));
            }
            default -> stop(request);
        });
        try (var fixture = new GatewayFixture(data, model)) {
            fixture.seed("agent-a", "synthetic-A");
            fixture.thread("nulls");
            fixture.completed(fixture.run("nulls", "agent-a", false));
            var after = fixture.agent("agent-a");
            assertThat(firstSoul[0]).isEqualTo(hash("synthetic-A"));
            assertThat(firstDisplay[0]).isEqualTo("Display agent-a");
            assertThat(after.path("soul").asText()).isEqualTo("ordinary null word");
            assertThat(after.path("description").asText()).isEqualTo("changed");
            assertThat(after.path("display_name").asText()).isEqualTo("New Display");
            assertThat(fixture.results("nulls", "update_agent")).hasSize(2);
            // The second actual model request sees the first Tool's null result but still original frozen SOUL.
            soulEquals(instructions(model.requests.get(1)), "synthetic-A");
        }
    }

    @Test
    void componentJsonNullAndNullishInputNormalizeToAbsentWithoutMutation() {
        var explicitNull = new ProductionUpdateAgentTool.Input(null, null, null);
        assertThat(explicitNull.soul()).isEmpty();
        assertThat(explicitNull.description()).isEmpty();
        assertThat(explicitNull.display_name()).isEmpty();
        for (String nullish : List.of("null", " NONE ", "Undefined")) {
            var input = new ProductionUpdateAgentTool.Input(java.util.Optional.of(nullish),
                    java.util.Optional.of(nullish), java.util.Optional.of(nullish));
            assertThat(input.soul()).isEmpty();
            assertThat(input.description()).isEmpty();
            assertThat(input.display_name()).isEmpty();
        }
        var mutations = new java.util.concurrent.atomic.AtomicInteger();
        var tool = new ProductionUpdateAgentTool("agent-a", ignored -> {
            mutations.incrementAndGet();
            return new ProductionUpdateAgentTool.Output(true, "unexpected mutation");
        });
        assertThat(tool.invoke(explicitNull, context("default-public-user", false, false, true)).success()).isFalse();
        assertThat(mutations).hasValue(0);
    }

    @Test
    void actualSdkSchemaFailureNeverInvokesRegisteredProductionToolOrMutationCallback() throws Exception {
        var invocations = new java.util.concurrent.atomic.AtomicInteger();
        var mutations = new java.util.concurrent.atomic.AtomicInteger();
        var production = new ProductionUpdateAgentTool("agent-a", ignored -> {
            mutations.incrementAndGet();
            return new ProductionUpdateAgentTool.Output(true, "unexpected mutation");
        });
        io.haifa.agent.sdk.tool.JavaTool<ProductionUpdateAgentTool.Input, ProductionUpdateAgentTool.Output> counted =
                new io.haifa.agent.sdk.tool.JavaTool<>() {
                    @Override
                    public io.haifa.agent.sdk.tool.JavaToolSpec<ProductionUpdateAgentTool.Input, ProductionUpdateAgentTool.Output> spec() {
                        return production.spec();
                    }

                    @Override
                    public ProductionUpdateAgentTool.Output invoke(ProductionUpdateAgentTool.Input input,
                            io.haifa.agent.sdk.tool.JavaToolContext context) {
                        invocations.incrementAndGet();
                        return production.invoke(input, context);
                    }
                };
        var model = new ControlledModel((request, index) -> index == 1
                ? call(request, "update_agent", Map.of("soul", "synthetic allowed field", "model", "forbidden-model"))
                : stop(request));
        var binding = LocalProbeModelTestSupport.binding(model);
        var profile = io.haifa.agent.sdk.product.ProductProfile.create(
                new io.haifa.agent.sdk.product.ProductId("m3-schema-counter"),
                new io.haifa.agent.sdk.product.ProductVersion("1.0.0"),
                new io.haifa.agent.core.agent.AgentDefinitionId("m3-schema-counter"),
                new io.haifa.agent.core.agent.AgentDefinitionVersion(1, 0, 0),
                "Call the offered tool once, then report its error.",
                new io.haifa.agent.sdk.product.ProductRunProfileRef(binding.name(), "1.0.0"),
                new io.haifa.agent.core.run.AgentRunBudget(0, 8192, 65536, 16, 16, 0, "USD", 100),
                new io.haifa.agent.core.run.AgentRunLimits(16, 0, 1, 30000, 15000, 16, 16, 0),
                java.util.Set.of("update_agent"), java.util.Set.of());
        var models = new io.haifa.agent.sdk.contribution.ModelContribution(
                Map.of(io.haifa.agent.model.api.ModelAdapterCoordinate.from(binding.snapshot()), binding.model()),
                binding.snapshot(), Map.of(binding.name(), binding.snapshot()));
        try (var foundation = new DurableHaifaFoundation(data, new javax.crypto.spec.SecretKeySpec(new byte[32], "AES"));
                var agent = io.haifa.agent.sdk.api.HaifaAgents.builder(profile)
                .callerProvider(io.haifa.agent.sdk.api.SdkCallerProvider.defaultPublicUser())
                .model(models).persistence(foundation.persistence()).conversation(foundation.conversation())
                .policy(new io.haifa.agent.sdk.contribution.PolicyPlatformContribution(
                        io.haifa.agent.policy.api.PolicyPresets.standardApproval(),
                        new io.haifa.agent.policy.core.DefaultPolicyDecisionService()))
                .tool(counted)
                .autoApproveStandardFileWriteTools(java.util.Set.of(new io.haifa.agent.tool.api.ToolName("update_agent")))
                .build()) {
            var started = agent.conversations().start(new io.haifa.agent.sdk.conversation.StartConversationCommand(
                    "m3-schema-counter-id", "m3-schema-counter", "execute controlled invalid input"));
            var completed = agent.runs().await(started.runId(), java.time.Duration.ofSeconds(15)).orElseThrow(
                    () -> new AssertionError("bounded public SDK schema control did not terminate"));
            var calls = agent.runs().toolCalls(started.runId());
            // Preserve public facts before assertions; the first FAILED expectation remains in archived evidence.
            var evidence = evidenceDirectory();
            var summary = JSON.createObjectNode().put("run_id", started.runId().value())
                    .put("run_status", completed.status().name())
                    .put("run_error_code", completed.error().map(value -> value.code().wireCode()).orElse(""))
                    .put("run_error_category", completed.error().map(value -> value.code().category().name()).orElse(""))
                    .put("invocations", invocations.get()).put("mutations", mutations.get())
                    .put("model_request_count", model.requests.size());
            var toolFacts = summary.putArray("tool_calls");
            for (var toolCall : calls) {
                var fact = toolFacts.addObject().put("tool_call_id", toolCall.id().value())
                        .put("status", toolCall.status().name())
                        .put("error_code", toolCall.error().map(value -> value.wireCode()).orElse(""))
                        .put("error_category", toolCall.error().map(value -> value.category().name()).orElse(""))
                        .put("result_present", toolCall.result().isPresent());
                toolCall.result().ifPresent(result -> fact.put("result_successful", result.successful())
                        .put("summary_chars", result.summary().text().length())
                        .put("summary_sha256", hash(result.summary().text())));
            }
            var page = agent.runs().events(started.runId(),
                    io.haifa.agent.runtime.api.RunEventCursor.beforeFirst(started.runId()), 200);
            summary.put("events_have_more", page.hasMore());
            var eventFacts = summary.putArray("tool_events");
            for (var event : page.items()) {
                if (event.payload() instanceof io.haifa.agent.runtime.api.RunEventPayloads.ToolLifecycle tool) {
                    eventFacts.addObject().put("event_type", event.eventType()).put("status", tool.status())
                            .put("reason_code", tool.reasonCode()).put("tool_call_id", tool.toolCallId());
                }
            }
            var repairFacts = summary.putArray("model_tool_messages");
            for (var request : model.requests) {
                for (var message : request.messages()) {
                    if (message.role().name().equals("TOOL")) {
                        String text = message.content();
                        repairFacts.addObject().put("chars", text.length()).put("sha256", hash(text))
                                .put("has_schema_repair_hint", text.contains("Repair the tool arguments"))
                                .put("contains_rejected_value", text.contains("forbidden-model"))
                                .put("contains_synthetic_soul", text.contains("synthetic allowed field"));
                    }
                }
            }
            java.nio.file.Files.writeString(evidence.resolve("sdk-schema.safe-diagnostics.json"),
                    JSON.writeValueAsString(summary));
            assertThat(completed.status().name()).as("public SDK schema diagnostics: %s", summary).isEqualTo("COMPLETED");
            assertThat(completed.error()).isEmpty();
            assertThat(calls.size()).isEqualTo(1);
            assertThat(invocations).hasValue(0);
            assertThat(mutations).hasValue(0);
            assertThat(calls.getFirst().status().name()).isEqualTo("CANCELLED");
            assertThat(calls.getFirst().error()).isEmpty();
            assertThat(calls.getFirst().result()).isEmpty();
            assertThat(page.hasMore()).as("all controlled public events observed").isFalse();
            assertThat(model.requests.size()).isEqualTo(2);
            var repairMessages = model.requests.get(1).messages().stream()
                    .filter(message -> message.role().name().equals("TOOL")).toList();
            assertThat(repairMessages.size()).as("next actual model request has one schema repair Tool message").isEqualTo(1);
            String repair = repairMessages.getFirst().content();
            String expectedRepair = "Tool request rejected. Repair the tool arguments: "
                    + "$/model is not allowed for the selected mode.";
            assertThat(repair.length()).as("exact bounded schema repair message length").isEqualTo(95);
            assertThat(hash(repair)).as("exact value-free schema repair message").isEqualTo(hash(expectedRepair));
            assertThat(repair.contains("forbidden-model")).as("rejected field value is absent").isFalse();
            assertThat(repair.contains("synthetic allowed field")).as("mixed SOUL value is absent").isFalse();
        }
    }

    @Test
    void exactUpdatedPromptLimitIsAcceptedButOneMoreCharacterCannotReplaceIt() throws Exception {
        String boundary = customSoulAtLimit("agent-a");
        ConfigFingerprint[] afterFirstWrite = new ConfigFingerprint[1];
        var model = new ControlledModel((request, index) -> {
            if (index == 1) return call(request, "update_agent", Map.of("soul", boundary));
            if (index == 2) {
                try { afterFirstWrite[0] = ConfigFingerprint.read(data); }
                catch (Exception exception) { throw new AssertionError("configuration fingerprint unavailable", exception); }
                return call(request, "update_agent", Map.of("soul", boundary + "x", "description", "must-not-write"));
            }
            return stop(request);
        });
        try (var fixture = new GatewayFixture(data, model)) {
            fixture.seed("agent-a", "synthetic-A");
            replacePositiveControl(fixture, "agent-a");
            fixture.thread("update-boundary");
            fixture.completed(fixture.run("update-boundary", "agent-a", false));
            assertThat(afterFirstWrite[0]).isNotNull();
            afterFirstWrite[0].assertUnchanged(data);
            assertThat(hash(fixture.agent("agent-a").path("soul").asText())).isEqualTo(hash(boundary));
            var results = fixture.results("update-boundary", "update_agent");
            assertThat(results.size()).isEqualTo(2);
            assertThat(results.getFirst().path("content").asText().contains("updated successfully!")).isTrue();
            assertThat(results.getLast().path("content").asText().contains("Error:")).isTrue();
            // Isolate the saved prompt boundary from the preceding update Tool-call history.
            fixture.thread("update-boundary-fresh");
            fixture.completed(fixture.run("update-boundary-fresh", "agent-a", false));
            assertThat(instructions(model.requests.getLast()).trim().length()).isEqualTo(32000);
            soulEquals(instructions(model.requests.getLast()), boundary);
        }
    }

    @Test
    void updateChecksRenderedLengthIncludingHtmlExpansionBeforeReplacingAgent() throws Exception {
        String oversized = "&".repeat(7000);
        var model = new ControlledModel((request, index) -> index == 1
                ? call(request, "update_agent", Map.of("soul", oversized, "description", "must-not-write"))
                : stop(request));
        try (var fixture = new GatewayFixture(data, model)) {
            fixture.seed("agent-a", "synthetic-A");
            replacePositiveControl(fixture, "agent-a");
            ConfigFingerprint before = ConfigFingerprint.read(data);
            fixture.thread("update-long");
            fixture.completed(fixture.run("update-long", "agent-a", false));
            before.assertUnchanged(data);
            var result = fixture.results("update-long", "update_agent");
            assertThat(result.size()).isEqualTo(1);
            String error = result.getFirst().path("content").asText().toLowerCase(java.util.Locale.ROOT);
            assertThat(error.contains(oversized)).isFalse();
            assertThat(error.contains("limit") || error.contains("length") || error.contains("characters")).isTrue();
        }
    }
}

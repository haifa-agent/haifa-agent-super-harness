package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;
import static io.haifa.agent.superharness.server.AgentSoulTestSupport.*;
import static io.haifa.agent.superharness.server.GatewayFixture.*;
import static io.haifa.agent.superharness.server.ControlledModel.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual SDK setup calls, including already-existing Agent records and rejection controls. */
class SetupAgentToolTest {
    @TempDir Path data;

    @org.junit.jupiter.api.BeforeEach
    void isolatedData() throws Exception {
        data = dataDirectory();
    }

    @Test
    void importedDescriptionsAndAdaptationLedgerMatchCompleteFrozenDocstringsAndActualSpecs() throws Exception {
        String setupDocstring = """
                Setup the custom DeerFlow agent.

                Args:
                    soul: Full SOUL.md content defining the agent's personality and behavior.
                    description: One-line description of what the agent does.
                    skills: Optional list of skill names this agent should use. None means use all enabled skills, empty list means no skills.
                """.strip();
        String updateDocstring = """
                Persist updates to the current custom agent's SOUL.md and config.yaml.

                Use this when the user asks to refine the agent's identity, description,
                skill whitelist, tool-group whitelist, or default model. Only the fields
                you explicitly pass are updated; omitted fields keep their existing values.

                Pass ``soul`` as the FULL replacement SOUL.md content — there is no patch
                semantics, so always start from the current SOUL and apply your edits.

                Pass ``skills=[]`` to disable all skills for this agent. Omit ``skills``
                entirely to keep the existing whitelist. Do not pass literal strings like
                ``"null"`` / ``"none"`` / ``"undefined"`` for unchanged fields; omit those
                fields instead.

                Args:
                    soul: Optional full replacement SOUL.md content.
                    description: Optional new one-line description.
                    skills: Optional skill whitelist. ``[]`` = no skills, omit = unchanged.
                    tool_groups: Optional tool-group whitelist. ``[]`` = empty, omit = unchanged.
                    model: Optional model override (must match a configured model name).

                Returns:
                    Command with a ToolMessage describing the result. Changes take effect
                    on the next user turn (when the lead agent is rebuilt with the fresh
                    SOUL.md and config.yaml).
                """.strip();
        var frozenDocs = Map.of("setup_agent", setupDocstring, "update_agent", updateDocstring);
        var frozenHashes = Map.of("setup_agent", "6aed4ca0d136f4f49735ffa4f5635b85e7468d5b59491eba89520b216f67baa2",
                "update_agent", "3d3272e8751023e7cd76cc60261ff7dc669ddce5cd04f9b5a294614b14a7a62e");
        var setup = new ProductionSetupAgentTool("agent-a", ignored -> new ProductionSetupAgentTool.Output(true, "unused"));
        var update = new ProductionUpdateAgentTool("agent-a", ignored -> new ProductionUpdateAgentTool.Output(true, "unused"));
        var actual = Map.of("setup_agent", setup.spec().description(), "update_agent", update.spec().description());
        var ledger = JSON.readTree(descriptionResource("m3-manifest.json"));
        assertThat(ledger.path("schema").asText()).isEqualTo("sh.deerflow-tool-descriptions/v1");
        assertThat(ledger.path("source_sha").asText()).isEqualTo("e545c28ac301e6f9feae247c513d8ea8f83df294");
        assertThat(ledger.path("descriptions").size()).isEqualTo(2);
        for (var entry : ledger.path("descriptions")) {
            String tool = entry.path("tool").asText();
            assertThat(frozenDocs).containsKey(tool);
            String resource = descriptionResource(tool + ".txt");
            // The frozen Google-docstring parser exposes the entire description preceding Args.
            String parsedDescription = frozenDocs.get(tool).split("\n\nArgs:", 2)[0];
            assertThat(resource).as("complete frozen parsed description: %s", tool).isEqualTo(parsedDescription);
            assertThat(actual.get(tool)).as("actual production spec: %s", tool).isEqualTo(resource);
            assertThat(hash(resource)).isEqualTo(frozenHashes.get(tool));
            assertThat(entry.path("description_sha256").asText()).isEqualTo(frozenHashes.get(tool));
            assertThat(entry.path("source_function").asText()).isEqualTo(tool);
            assertThat(entry.path("source_file").asText()).isEqualTo("deerflow/tools/builtins/" + tool + "_tool.py");
            assertThat(entry.path("source_lineno").asInt()).isEqualTo(tool.equals("setup_agent") ? 18 : 77);
        }
        var adaptations = JSON.readTree(descriptionResource("m3-adaptations.json"));
        assertThat(adaptations.path("schema").asText()).isEqualTo("sh.deerflow-tool-adaptations/v1");
        assertThat(adaptations.path("source_sha").asText()).isEqualTo(ledger.path("source_sha").asText());
        com.fasterxml.jackson.databind.JsonNode supported = null;
        com.fasterxml.jackson.databind.JsonNode bootstrap = null;
        for (var adaptation : adaptations.path("adaptations")) {
            if (adaptation.path("id").asText().equals("M3-S2-update-supported-fields")) supported = adaptation;
            if (adaptation.path("id").asText().equals("M3-bootstrap-no-imported-skill")) bootstrap = adaptation;
        }
        assertThat(supported).isNotNull();
        assertThat(supported.path("tool").asText()).isEqualTo("update_agent");
        assertThat(supported.path("source_description").asText()).isEqualTo("update_agent.txt");
        var fields = new java.util.ArrayList<String>();
        supported.path("supported_fields").forEach(value -> fields.add(value.asText()));
        assertThat(fields).containsExactlyInAnyOrder("soul", "description", "display_name");
        var unsupported = new java.util.ArrayList<String>();
        supported.path("unsupported_fields").forEach(value -> unsupported.add(value.asText()));
        assertThat(unsupported).contains("skills", "tool_groups", "model", "mcp_plugins", "allowed_subagents",
                "model_settings", "thinking_enabled", "reasoning_effort", "knowledge_scope", "name");
        assertThat(supported.path("description_policy").asText()).contains("verbatim", "strict actual input schema");
        assertThat(supported.path("behavior").asText()).contains("rejects undeclared fields before invocation",
                "cannot modify capability or permission");
        var schema = new io.haifa.agent.sdk.tool.JavaRecordSchemaGenerator()
                .generate("update_agent", "1.0.0", ProductionUpdateAgentTool.Input.class).document();
        assertThat(((Map<?, ?>) schema.get("properties")).keySet().stream().map(Object::toString).toList())
                .containsExactlyInAnyOrderElementsOf(fields);
        assertThat(schema.get("additionalProperties")).isEqualTo(false);
        assertThat(bootstrap).isNotNull();
        assertThat(bootstrap.path("behavior").asText()).contains("MCP and Skill selection are none", "exposes setup_agent",
                "Missing agent_name returns an error without writing a global SOUL file");
    }

    private String descriptionResource(String name) throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("upstream/deerflow/tool-descriptions/" + name)) {
            assertThat(stream).as("required classpath ledger/description %s", name).isNotNull();
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test
    void bootstrapWithoutAgentNameRegistersSetupButActualInvocationCannotWrite() throws Exception {
        var model = new ControlledModel((request, index) -> index == 1
                ? call(request, "setup_agent", Map.of("soul", "synthetic no scope", "description", "must-not-write"))
                : stop(request));
        try (var fixture = new GatewayFixture(data, model)) {
            fixture.seed("control", "synthetic control");
            replacePositiveControl(fixture, "control");
            ConfigFingerprint before = ConfigFingerprint.read(data);
            fixture.thread("bootstrap-no-name");
            fixture.completed(fixture.run("bootstrap-no-name", null, true));
            before.assertUnchanged(data);
            assertThat(model.requests.size()).isEqualTo(2);
            for (var request : model.requests) {
                assertThat(names(request)).contains("setup_agent").doesNotContain("update_agent");
                assertThat(instructions(request).contains("<soul>\n")).isFalse();
                requestEvidence(fixture, request);
            }
            var results = fixture.results("bootstrap-no-name", "setup_agent");
            assertThat(results.size()).isEqualTo(1);
            assertThat(results.getFirst().path("content").asText()).isEqualTo("Error: no current agent_name");
            assertThat(fixture.get("/api/agents").findValuesAsText("name")).containsExactly("control");
            assertThat(data.resolve("SOUL.md")).doesNotExist();
        }
    }

    @Test
    void componentOwnerCancellationDeadlineSessionAndMissingScopeNeverReachMutation() {
        var mutations = new java.util.concurrent.atomic.AtomicInteger();
        var tool = new ProductionSetupAgentTool("agent-a", input -> {
            mutations.incrementAndGet();
            return new ProductionSetupAgentTool.Output(true, "component callback");
        });
        var input = new ProductionSetupAgentTool.Input("synthetic nonblank", "description", java.util.Optional.empty());
        for (var rejected : List.of(context("other-user", false, false, true),
                context("default-public-user", true, false, true),
                context("default-public-user", false, true, true),
                context("default-public-user", false, false, false))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> tool.invoke(input, rejected))
                    .isInstanceOf(RuntimeException.class);
            assertThat(mutations).hasValue(0);
        }
        var noScope = new ProductionSetupAgentTool(null, ignored -> {
            mutations.incrementAndGet();
            return new ProductionSetupAgentTool.Output(true, "component callback");
        });
        assertThat(noScope.invoke(input, context("default-public-user", false, false, true)).success()).isFalse();
        assertThat(mutations).hasValue(0);
    }

    @Test
    void setupUpsertsSameScopedAgentTwiceAndPreservesUnchangedFields() throws Exception {
        var model = new ControlledModel((request, index) -> switch (index) {
            case 1 -> call(request, "setup_agent", Map.of("soul", "synthetic setup first", "description", "first"));
            case 2 -> call(request, "setup_agent", Map.of("soul", "synthetic setup replacement", "description", "second"));
            default -> stop(request);
        });
        try (var fixture = new GatewayFixture(data, model)) {
            fixture.seed("agent-a", "synthetic old soul");
            var preserved = JSON.createObjectNode().put("display_name", "Preserved name");
            preserved.putObject("knowledge_scope").put("project", "synthetic-project");
            preserved.putArray("tool_groups").add("synthetic-existing-group");
            preserved.putArray("allowed_subagents").add("synthetic-existing-subagent");
            fixture.update("agent-a", preserved);
            var before = fixture.agent("agent-a");
            fixture.thread("setup-upsert");
            fixture.completed(fixture.run("setup-upsert", "agent-a", true));
            assertThat(model.requests.size()).isEqualTo(3);
            var results = fixture.results("setup-upsert", "setup_agent");
            assertThat(results.size()).isEqualTo(2);
            for (var result : results) {
                assertThat(result.path("content").asText().contains("Agent 'agent-a' created successfully!"))
                        .as("exact frozen setup success ToolMessage").isTrue();
            }
            var after = fixture.agent("agent-a");
            assertThat(hash(after.path("soul").asText())).isEqualTo(hash("synthetic setup replacement"));
            assertThat(after.path("description").asText()).isEqualTo("second");
            for (String field : List.of("display_name", "knowledge_scope", "tool_groups", "allowed_subagents",
                    "skills", "mcp_plugins", "model_settings", "model")) {
                assertThat(hash(after.path(field).toString())).as("preserve %s", field)
                        .isEqualTo(hash(before.path(field).toString()));
            }
            assertThat(fixture.get("/api/agents").findValuesAsText("name").stream()
                    .filter("agent-a"::equals).count()).isEqualTo(1);
            assertThat(fixture.state("setup-upsert").toString().contains("\"human_input\""))
                    .as("narrow FILE_WRITE exemption executes without approval").isFalse();
            for (var request : model.requests) {
                assertThat(names(request)).contains("setup_agent").doesNotContain("update_agent");
                requestEvidence(fixture, request);
            }
        }
    }

    @Test
    void setupCreatesMissingAgentAndInstalledSkillWhitelist() throws Exception {
        var model = new ControlledModel((request, index) -> index == 1
                ? call(request, "setup_agent", Map.of("soul", "synthetic new agent", "description", "new",
                        "skills", List.of("consulting-analysis"))) : stop(request));
        try (var fixture = new GatewayFixture(data, model)) {
            fixture.thread("setup-new");
            fixture.completed(fixture.run("setup-new", "new-agent", true));
            assertThat(hash(fixture.agent("new-agent").path("soul").asText())).isEqualTo(hash("synthetic new agent"));
            assertThat(fixture.agent("new-agent").path("skills").findValuesAsText("name")).isEmpty();
            assertThat(fixture.agent("new-agent").path("skills").get(0).asText()).isEqualTo("consulting-analysis");
            assertThat(fixture.results("setup-new", "setup_agent")).hasSize(1);
            assertThat(model.requests.size()).isEqualTo(2);
            for (var request : model.requests) requestEvidence(fixture, request);
        }
    }

    @Test
    void blankSoulUnknownSkillAndForeignTargetsCannotReplaceConfiguration() throws Exception {
        List<Map<String, Object>> payloads = List.of(
                Map.of("soul", " ", "description", "must-not-write"),
                Map.of("soul", "synthetic replacement", "description", "must-not-write", "skills", List.of("missing-skill")),
                Map.of("soul", "synthetic replacement", "description", "must-not-write", "agent_name", "agent-b"),
                Map.of("soul", "synthetic replacement", "description", "must-not-write", "name", "agent-b"));
        int caseIndex = 0;
        for (var payload : payloads) {
            var model = new ControlledModel((request, index) -> index == 1
                    ? call(request, "setup_agent", payload) : stop(request));
            try (var fixture = new GatewayFixture(data.resolve("negative-" + caseIndex++), model)) {
                fixture.seed("agent-a", "synthetic-A");
                fixture.seed("agent-b", "synthetic-B");
                replacePositiveControl(fixture, "agent-a");
                String a = hash(fixture.agent("agent-a").toString());
                String b = hash(fixture.agent("agent-b").toString());
                ConfigFingerprint before = ConfigFingerprint.read(fixture.data);
                fixture.thread("setup-rejected");
                fixture.completed(fixture.run("setup-rejected", "agent-a", true));
                before.assertUnchanged(fixture.data);
                assertThat(hash(fixture.agent("agent-a").toString())).isEqualTo(a);
                assertThat(hash(fixture.agent("agent-b").toString())).isEqualTo(b);
                var results = fixture.results("setup-rejected", "setup_agent");
                assertThat(results.size()).isEqualTo(1);
                String content = results.getFirst().path("content").asText().toLowerCase(java.util.Locale.ROOT);
                assertThat(content.contains("error") || content.contains("false")).as("actual rejected Tool result").isTrue();
                assertThat(content.contains("soul") || content.contains("skill") || content.contains("schema")
                        || content.contains("agent_name") || content.contains("name"))
                        .as("rejection exposes the input/schema reason").isTrue();
                assertThat(model.requests.size()).isEqualTo(2);
            }
        }
    }

    @Test
    void exactFutureCustomLimitIsAcceptedButOneMoreCharacterCannotReplaceIt() throws Exception {
        String boundary = customSoulAtLimit("new-agent");
        ConfigFingerprint[] afterFirstWrite = new ConfigFingerprint[1];
        var model = new ControlledModel((request, index) -> {
            if (index == 1) return call(request, "setup_agent", Map.of("soul", boundary, "description", "boundary", "skills", List.of()));
            if (index == 2) {
                try { afterFirstWrite[0] = ConfigFingerprint.read(data); }
                catch (Exception exception) { throw new AssertionError("configuration fingerprint unavailable", exception); }
                return call(request, "setup_agent", Map.of("soul", boundary + "x", "description", "must-not-write"));
            }
            return stop(request);
        });
        try (var fixture = new GatewayFixture(data, model)) {
            fixture.seed("control", "synthetic-control");
            replacePositiveControl(fixture, "control");
            fixture.thread("setup-boundary");
            fixture.completed(fixture.run("setup-boundary", "new-agent", true));
            assertThat(afterFirstWrite[0]).isNotNull();
            afterFirstWrite[0].assertUnchanged(data);
            assertThat(hash(fixture.agent("new-agent").path("soul").asText())).isEqualTo(hash(boundary));
            var results = fixture.results("setup-boundary", "setup_agent");
            assertThat(results.size()).isEqualTo(2);
            assertThat(results.getFirst().path("content").asText().contains("created successfully!")).isTrue();
            assertThat(results.getLast().path("content").asText().contains("Error:")).isTrue();
            fixture.completed(fixture.run("setup-boundary", "new-agent", false));
            assertThat(instructions(model.requests.getLast()).trim().length()).isEqualTo(32000);
            soulEquals(instructions(model.requests.getLast()), boundary);
        }
    }

    @Test
    void setupChecksFutureCustomPromptLengthBeforeCreatingOrReplacingAgent() throws Exception {
        String oversized = "&".repeat(7000);
        var model = new ControlledModel((request, index) -> index == 1
                ? call(request, "setup_agent", Map.of("soul", oversized, "description", "must-not-write"))
                : stop(request));
        try (var fixture = new GatewayFixture(data, model)) {
            fixture.seed("agent-b", "synthetic existing control");
            replacePositiveControl(fixture, "agent-b");
            ConfigFingerprint before = ConfigFingerprint.read(data);
            fixture.thread("setup-long");
            fixture.completed(fixture.run("setup-long", "new-agent", true));
            before.assertUnchanged(data);
            assertThat(fixture.request("GET", "/api/agents/new-agent", null).statusCode()).isEqualTo(404);
            var result = fixture.results("setup-long", "setup_agent");
            assertThat(result.size()).isEqualTo(1);
            assertThat(result.getFirst().path("content").asText().contains(oversized)).isFalse();
            String error = result.getFirst().path("content").asText().toLowerCase(java.util.Locale.ROOT);
            assertThat(error.contains("limit") || error.contains("length") || error.contains("characters")).isTrue();
        }
    }
}

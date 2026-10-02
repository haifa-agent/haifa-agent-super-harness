package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.superharness.server.testsupport.ControlledProvider;
import io.haifa.agent.superharness.server.testsupport.GatewayProcessFixture;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceChangeLimits;
import io.haifa.agent.superharness.workspace.WorkspaceLimits;
import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** Oracle 12: two real production Gateway JVMs and the frozen REST/SSE approval protocol. */
class M3AgentSoulGatewayRestartTest {
    private static final String A = "M3 synthetic SOUL A 中文";
    private static final String B = "M3 synthetic SOUL B 中文";
    private static final String PATH = "/mnt/user-data/outputs/m3-restart-once.txt";
    private static final String CONTENT = "M3 approved once\n";

    @Test void updatedSoulDoesNotReplaceTheWaitingRunAcrossProductionGatewayRestart() throws Exception {
        restart(false);
    }

    @Test void setupCreatedAgentDoesNotReplaceBootstrapBindingAcrossProductionGatewayRestart() throws Exception {
        restart(true);
    }

    private void restart(boolean bootstrap) throws Exception {
        String thread = bootstrap ? "m3-bootstrap-restart" : "m3-update-restart";
        String agent = bootstrap ? "m3-bootstrap-target" : "m3-update-target";
        List<Capture> captures = new CopyOnWriteArrayList<>();
        try (var fixture = new GatewayProcessFixture(thread)) {
            int providerPort;
            try (var provider = new ControlledProvider(fixture.evidence.resolve("provider"), false, (request, call) -> {
                captures.add(capture(request));
                fixture.proof("provider-" + call, captures.get(call - 1));
                String tool = call == 1 ? (bootstrap ? "setup_agent" : "update_agent")
                        : call == 2 ? "write_file" : null;
                Map<String, Object> arguments = call == 1
                        ? (bootstrap ? Map.of("soul", B, "description", "M3 fixture", "skills", List.of())
                                : Map.of("soul", B))
                        : Map.of("path", PATH, "content", CONTENT);
                return tool == null
                        ? ControlledProvider.stop("m3-response-" + call, "m3-fixture-model", "M3 fixture complete", 4, 4)
                        : ControlledProvider.call("m3-response-" + call, "m3-fixture-model", "m3-tool-" + call,
                                tool, arguments, 4, 4);
            })) {
                providerPort = provider.port();
                var responseDiagnostics = provider.responseDiagnostics;
                var providerFailure = provider.failure;
                fixture.start();
                fixture.configureModel(providerPort);
                if (!bootstrap) assertThat(fixture.send("POST", "/api/agents", fixture.json.writeValueAsString(Map.of(
                        "name", agent, "display_name", "M3 target", "soul", A, "skills", List.of()))).statusCode()).isEqualTo(201);
                assertThat(fixture.send("POST", "/api/threads", fixture.json.writeValueAsString(Map.of(
                        "thread_id", thread, "metadata", Map.of("title", "M3 restart fixture")))).statusCode()).isEqualTo(200);
                var admitted = fixture.send("POST", "/api/threads/" + thread + "/runs/stream",
                        fixture.runBody(agent, bootstrap, "Execute fixture plan"));
                String location = GatewayProcessFixture.location(admitted);
                String run = location.substring(location.lastIndexOf('/') + 1);
                fixture.runDiagnostic(location, "before-admission-card", List.copyOf(responseDiagnostics));
                JsonNode card = fixture.card(admitted.body());
                String requestId = card.path("artifact").path("human_input").path("request_id").asText();
                String toolCallId = card.path("artifact").path("human_input").path("tool_call_id").asText();
                assertThat(requestId).isNotBlank();
                assertThat(toolCallId).isNotBlank();
                assertThat(providerFailure.get()).isNull();
                assertThat(captures).hasSize(2);
                // setup/update is a real automatic standard-write exception; only the ordinary
                // write has raised a pending card, after the actual Agent store changed to B.
                assertThat(GatewayProcessFixture.hash(fixture.get("/api/agents/" + agent).path("soul").asText()))
                        .isEqualTo(GatewayProcessFixture.hash(B));
                JsonNode metadata = fixture.get("/api/threads/" + thread).path("metadata");
                String signature = metadata.path("haifa_lead_prompt_signature").asText();
                assertThat(signature).matches("[0-9a-f]{64}");
                assertThat(metadata.path("haifa_agent_name").asText()).isEqualTo(agent);
                assertThat(metadata.path("haifa_agent_bootstrap").isBoolean()).isTrue();
                assertThat(metadata.path("haifa_agent_bootstrap").asBoolean()).isEqualTo(bootstrap);
                assertThat(fixture.get(location).path("status").asText()).isEqualTo("interrupted");
                assertThat(fixture.send("GET", "/api/threads/" + thread + "/artifacts" + PATH, null).statusCode()).isEqualTo(404);
                assertInstruction(captures.get(0), signature, bootstrap, false);
                assertInstruction(captures.get(1), signature, bootstrap, false);
                assertThat(captures.get(0).tools()).contains(bootstrap ? "setup_agent" : "update_agent");
                assertThat(captures.get(0).tools()).doesNotContain(bootstrap ? "update_agent" : "setup_agent");
                assertThat(captures.get(1).tools()).isEqualTo(captures.get(0).tools());
                long firstPid = fixture.gateway.pid();
                fixture.stop();
                fixture.start();
                assertThat(fixture.gateway.pid()).isNotEqualTo(firstPid);
                JsonNode persisted = fixture.get("/api/threads/" + thread).path("metadata");
                assertThat(persisted.path("haifa_lead_prompt_signature").asText()).isEqualTo(signature);
                assertThat(persisted.path("haifa_agent_name").asText()).isEqualTo(agent);
                assertThat(persisted.path("haifa_agent_bootstrap").asBoolean()).isEqualTo(bootstrap);
                var gap = fixture.send("GET", location + "/stream", null, "-1");
                assertThat(gap.statusCode()).isEqualTo(200);
                var gapData = fixture.event(gap.body(), "gap");
                assertThat(gapData.path("run_id").asText()).isEqualTo(run);
                var joined = fixture.send("GET", location + "/stream", null,
                        gapData.path("latest_available_event_id").asText());
                assertThat(joined.statusCode()).isEqualTo(200);
                fixture.runDiagnostic(location, "before-recovered-card", List.copyOf(responseDiagnostics));
                JsonNode recovered = fixture.card(joined.body());
                assertThat(recovered.path("id")).isEqualTo(card.path("id"));
                assertThat(recovered.path("artifact").path("human_input").path("request_id").asText()).isEqualTo(requestId);
                assertThat(recovered.path("artifact").path("human_input").path("tool_call_id").asText()).isEqualTo(toolCallId);
                assertThat(captures).hasSize(2);
                assertThat(fixture.get(location).path("status").asText()).isEqualTo("interrupted");
                assertThat(fixture.send("GET", "/api/threads/" + thread + "/artifacts" + PATH, null).statusCode()).isEqualTo(404);
                fixture.runDiagnostic(location, "before-first-approval-response", List.copyOf(responseDiagnostics));
                var approved = fixture.send("POST", "/api/threads/" + thread + "/runs/stream", fixture.approval(requestId));
                fixture.proof("first-approval-phase", Map.of("run", run, "request", requestId,
                        "http_status", approved.statusCode(), "provider_calls", captures.size()));
                fixture.runDiagnostic(location, "after-first-approval-response", List.copyOf(responseDiagnostics));
                assertThat(approved.statusCode()).isEqualTo(200);
                assertThat(fixture.get(location).path("status").asText()).isEqualTo("success");
                assertThat(captures).hasSize(3);
                assertThat(providerFailure.get()).isNull();
                assertInstruction(captures.get(2), signature, bootstrap, false);
                assertThat(captures.get(2).tools()).isEqualTo(captures.get(0).tools());
                long writeResults = 0;
                for (JsonNode message : fixture.get("/api/threads/" + thread + "/state").path("values").path("messages")) {
                    if (message.path("type").asText().equals("tool") && message.path("name").asText().equals("write_file")) {
                        assertThat(message.path("additional_kwargs").path("haifa_tool_status").asText()).isEqualTo("completed");
                        writeResults++;
                    }
                }
                assertThat(writeResults).as("actual successful production write ToolMessage count").isEqualTo(1);
                var artifact = fixture.http.send(HttpRequest.newBuilder(fixture.base.resolve(
                        "/api/threads/" + thread + "/artifacts" + PATH)).timeout(java.time.Duration.ofSeconds(10)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                assertThat(artifact.statusCode()).isEqualTo(200);
                assertThat(artifact.body()).isEqualTo(CONTENT.getBytes(StandardCharsets.UTF_8));
                assertThat(fixture.send("POST", "/api/threads/" + thread + "/runs/stream",
                        fixture.approval(requestId)).statusCode()).isEqualTo(409);
                assertThat(captures).hasSize(3);
                var next = fixture.send("POST", "/api/threads/" + thread + "/runs/stream",
                        fixture.runBody(agent, false, "Complete next fixture"));
                String nextLocation = GatewayProcessFixture.location(next);
                assertThat(nextLocation).isNotEqualTo(location);
                assertThat(fixture.get(nextLocation).path("status").asText()).isEqualTo("success");
                assertThat(captures).hasSize(4);
                assertThat(captures.get(3).soulB()).isTrue();
                assertThat(captures.get(3).soulA()).isFalse();
                assertThat(captures.get(3).tools()).contains("update_agent").doesNotContain("setup_agent");
                String nextSignature = fixture.get("/api/threads/" + thread).path("metadata")
                        .path("haifa_lead_prompt_signature").asText();
                assertThat(nextSignature).isNotEqualTo(signature);
                assertInstruction(captures.get(3), nextSignature, false, true);
                fixture.proof("oracle-12", Map.of("run", run, "request", requestId, "tool_call", toolCallId,
                        "signature", signature, "next_signature", nextSignature, "bootstrap", bootstrap,
                        "provider_calls", 4, "duplicate_approval", 409, "artifact_sha256", GatewayProcessFixture.hash(CONTENT)));
                fixture.stop();
                var workspace = new RestrictedWorkspaceService(fixture.data.resolve("workspace"), WorkspaceLimits.defaults());
                var projects = new PersistentProjectWorkspaceService(fixture.data.resolve("workspace"),
                        WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
                var binding = projects.requireThreadWorkspace("default", thread);
                assertThat(workspace.readVerifiedBytes("default", binding.workspaceId(), PATH)).isEqualTo(artifact.body());
                assertThat(workspace.findArtifacts("default", binding.workspaceId(), "thread:" + thread)).singleElement();
                System.out.println("M3_RESTART_EVIDENCE bootstrap=" + bootstrap + " run=" + run + " signature=" + signature
                        + " provider_calls=4 duplicate=409 evidence=" + fixture.evidence);
            }
            try (var probe = new java.net.ServerSocket()) { probe.bind(new InetSocketAddress("127.0.0.1", providerPort)); }
        }
    }

    private static Capture capture(JsonNode request) throws Exception {
        List<String> instructions = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (JsonNode message : request.path("messages")) {
            String role = message.path("role").asText();
            if (role.equals("system") || role.equals("developer")) {
                String content = message.path("content").asText();
                instructions.add(content);
                hashes.add(GatewayProcessFixture.hash(content.trim()));
            }
        }
        List<String> leads = instructions.stream()
                .filter(text -> text.contains("<role>") && text.contains("open-source super agent")).toList();
        String lead = leads.size() == 1 ? leads.get(0) : "";
        String soul = block(lead, "soul");
        String selfUpdate = block(lead, "self_update");
        Set<String> tools = new TreeSet<>();
        for (JsonNode tool : request.path("tools")) tools.add(tool.path("function").path("name").asText());
        return new Capture(List.copyOf(hashes), Set.copyOf(tools), leads.size(),
                leads.size() == 1 ? GatewayProcessFixture.hash(lead.trim()) : "",
                soul != null && soul.strip().equals(A), soul != null && soul.strip().equals(B),
                soul != null, selfUpdate != null);
    }

    private static String block(String lead, String tag) {
        var match = java.util.regex.Pattern.compile("(?ms)^<" + tag + ">\\h*\\R(.*?)^</" + tag + ">\\h*$")
                .matcher(lead);
        if (!match.find()) return null;
        String body = match.group(1);
        GatewayProcessFixture.require(!match.find(), "Actual Lead must not contain duplicate structured blocks");
        return body;
    }

    private static void assertInstruction(Capture capture, String signature, boolean bootstrap, boolean next) {
        assertThat(capture.instructionHashes()).contains("sha256:" + signature);
        assertThat(capture.leadCount()).as("exactly one actual Lead instruction layer").isEqualTo(1);
        assertThat(capture.leadHash()).isEqualTo("sha256:" + signature);
        assertThat(capture.soulA()).isEqualTo(!bootstrap && !next);
        assertThat(capture.soulB()).isEqualTo(next);
        assertThat(capture.soulTag()).isEqualTo(!bootstrap);
        if (bootstrap) assertThat(capture.selfUpdate()).isFalse();
    }

    record Capture(List<String> instructionHashes, Set<String> tools, int leadCount, String leadHash, boolean soulA, boolean soulB,
            boolean soulTag, boolean selfUpdate) {}
}

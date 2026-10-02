package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceChangeLimits;
import io.haifa.agent.superharness.workspace.WorkspaceLimits;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Oracle 12/13: opt-in paid Provider, production config/tools, SDK approval and Artifact transport. */
@EnabledIfSystemProperty(named = "haifa.lead.live", matches = "true")
class LeadPromptLiveAcceptanceTest {
    @Test
    void realChineseReportUsesOutputsArtifactAndActualSdkPromptDiagnostics() throws Exception {
        String dataProperty = System.getProperty("super.harness.lead.live.data.dir");
        String evidenceProperty = System.getProperty("super.harness.lead.live.evidence.dir");
        assertThat(dataProperty).as("Explicit isolated data directory is required").isNotBlank();
        assertThat(evidenceProperty).as("Explicit external evidence directory is required").isNotBlank();
        assertThat(System.getProperty("super.harness.lead.live.port"))
                .as("Explicit controller-owned Gateway port is required").isNotBlank();
        Path data = GatewayFixture.directory(
                "super.harness.lead.live.data.dir", Path.of(dataProperty), "live-data-");
        Path evidence = GatewayFixture.directory(
                "super.harness.lead.live.evidence.dir", Path.of(evidenceProperty), "live-evidence-");
        try (var support = new GatewayFixture.HttpEvidence(evidence)) {
            int port = Integer.parseInt(System.getProperty("super.harness.lead.live.port"));
            String threadId = "lead-live-report";
            Map<String, String> environment = new LinkedHashMap<>(System.getenv());
            environment.put("HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://M2_LIVE_TEST_KEY");
            environment.put("M2_LIVE_TEST_KEY", Base64.getEncoder().encodeToString(new byte[32]));
            long started = System.nanoTime();
            try {
                try (var api = DeerFlowBootstrapApi.create(new LocalServerConfiguration(
                        "local", InetAddress.getByName("127.0.0.1"), port), data, environment)) {
                    api.start();
                    URI base = URI.create("http://127.0.0.1:" + api.address().getPort());
                    String managed = """
                            {"config":{"name":"m2-live-pro","display_name":"M2 DeepSeek V4 Pro",
                            "provider":"openai-compatible","model":"deepseek-v4-pro","base_url":"https://api.deepseek.com",
                            "enabled":true,"supports_vision":false,"context_window":1048576,"max_tokens":8192,
                            "credential_ref":"os://DEEPSEEK_API_KEY"},"expected_revision":null}
                            """;
                    assertThat(support.request(base, "PUT", "/api/managed-models", managed, "managed-model.json")
                            .statusCode()).as("Production model config/OS credential reference").isEqualTo(200);
                    var agentCreated = support.request(base, "POST", "/api/agents",
                            "{\"name\":\"m2-live-no-skills\",\"display_name\":\"M2 Live\",\"skills\":[],\"soul\":\"\"}",
                            "agent-created.json");
                    assertThat(agentCreated.statusCode()).isEqualTo(201);
                    assertThat(support.json.readTree(agentCreated.body()).path("skills")).isEmpty();
                    assertThat(support.request(base, "POST", "/api/threads", support.json.writeValueAsString(Map.of(
                            "thread_id", threadId, "metadata", Map.of("title", "M2 prompt assembly acceptance"))),
                            "thread.json").statusCode()).isEqualTo(200);
                    // Explicit empty Skills prevents Skill bodies from entering raw evidence. The
                    // production tool catalog and resolver remain intact; Skill recovery has its own case.
                    String body = support.json.writeValueAsString(Map.of("assistant_id", "lead_agent",
                            "input", Map.of("messages", List.of(Map.of("type", "human", "content",
                                    "请写一份简短中文工作报告并保存为文件，内容只描述本次提示词装配验证；采用你默认的报告格式即可。"))),
                            "context", Map.of("model_name", "m2-live-pro", "mode", "flash", "agent_name", "m2-live-no-skills",
                                    "thinking_enabled", false, "is_plan_mode", false, "subagent_enabled", false),
                            "stream_mode", List.of("messages-tuple", "updates", "custom"),
                            "on_disconnect", "continue", "multitask_strategy", "reject"));
                    String streamPath = "/api/threads/" + threadId + "/runs/stream";
                    String location = GatewayFixture.location(
                            support.request(base, "POST", streamPath, body, "run-initial.sse"));
                    JsonNode admittedThread = support.get(base, "/api/threads/" + threadId, "admitted-thread.json");
                    JsonNode admittedSkills = admittedThread.path("metadata")
                            .path(DeerFlowThreadRunApi.SKILL_SELECTION_METADATA_KEY);
                    assertThat(admittedSkills.isArray()).as("Server records the selected Agent's empty declaration").isTrue();
                    assertThat(admittedSkills).isEmpty();
                    String runId = location.substring(location.lastIndexOf('/') + 1);
                    long deadline = System.nanoTime() + Duration.ofMinutes(4).toNanos();
                    var approved = new HashSet<String>();
                    JsonNode run = null;
                    for (int index = 0; index < 8 && System.nanoTime() < deadline; index++) {
                        run = support.get(base, location, "run-status-" + index + ".json");
                        String status = run.path("diagnostics").path("sdk_status").asText();
                        if (status.equals("COMPLETED") || status.equals("FAILED") || status.equals("CANCELLED")) break;
                        assertThat(status).as("Run pause must be actual SDK approval; run evidence %s", index)
                                .isEqualTo("WAITING_APPROVAL");
                        JsonNode state = support.get(base, "/api/threads/" + threadId + "/state",
                                "pending-state-" + index + ".json");
                        JsonNode card = SseEvents.PendingApproval.locate(threadId, location, run, state, evidence)
                                .card().path("artifact").path("human_input");
                        assertThat(card).as("Pending SDK HumanInput card").isNotNull();
                        String requestId = card.path("request_id").asText();
                        assertThat(approved.add(requestId)).isTrue();
                        var response = support.request(base, "POST", streamPath,
                                SseEvents.PendingApproval.reply(requestId, "approve"), "approval-" + index + ".sse");
                        assertThat(GatewayFixture.location(response)).isEqualTo(location);
                        // Rejoin the original Run to consume any replay/live tail, without creating a new Run.
                        assertThat(support.request(base, "GET", location + "/stream", null,
                                "same-run-join-" + index + ".sse").statusCode()).isEqualTo(200);
                    }
                    run = support.get(base, location, "final-run.json");
                    support.get(base, "/api/threads/" + threadId + "/token-usage", "token-usage.json");
                    LeadPromptAcceptanceTestSupport.diagnostics(support, api, threadId, runId);
                    assertThat(run.path("diagnostics").path("sdk_status").asText())
                            .as("No hidden retry or clarification replacement; preserve actual first outcome")
                            .isEqualTo("COMPLETED");
                    assertThat(run.path("total_input_tokens").asLong()).isPositive();
                    assertThat(run.path("total_output_tokens").asLong()).isPositive();
                    assertThat(run.path("total_output_tokens").asLong()).isLessThanOrEqualTo(8192);
                    assertThat(run.path("llm_call_count").asLong()).isPositive();
                    JsonNode state = support.get(base, "/api/threads/" + threadId + "/state", "final-state.json");
                    List<JsonNode> writes = new ArrayList<>();
                    String finalText = "";
                    for (JsonNode message : state.path("values").path("messages")) {
                        if (message.path("type").asText().equals("ai")
                                && message.path("tool_calls").isEmpty()) finalText = message.path("content").asText();
                        if (message.path("type").asText().equals("tool")
                                && message.path("name").asText().equals("write_file")) writes.add(message);
                    }
                    assertThat(finalText).as("Final response includes Chinese language").containsPattern("[\\p{IsHan}]");
                    assertThat(writes).as("Real completed write_file result").isNotEmpty();
                    var projects = new PersistentProjectWorkspaceService(data.resolve("workspace"),
                            WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
                    var workspace = new RestrictedWorkspaceService(data.resolve("workspace"), WorkspaceLimits.defaults());
                    var workspaceBinding = projects.requireThreadWorkspace("default", threadId);
                    var published = workspace.findArtifacts("default", workspaceBinding.workspaceId(), "thread:" + threadId);
                    List<Map<String, Object>> artifacts = new ArrayList<>();
                    for (JsonNode write : writes) {
                        JsonNode structured = write.path("additional_kwargs").path("haifa_tool_result");
                        String path = structured.path("path").asText();
                        assertThat(path).startsWith("/mnt/user-data/outputs/");
                        String execution = write.path("tool_call_id").asText();
                        assertThat(structured.path("artifactExecutionRef").asText()).isEqualTo(execution);
                        assertThat(structured.path("artifactId").asText()).isNotBlank();
                        assertThat(run.path("diagnostics").path("artifacts").findValuesAsText("artifact_id"))
                                .contains(structured.path("artifactId").asText());
                        boolean matchingCall = false;
                        for (JsonNode message : state.path("values").path("messages")) {
                            for (JsonNode call : message.path("tool_calls")) {
                                if (call.path("id").asText().equals(execution)) {
                                    assertThat(call.path("name").asText()).isEqualTo("write_file");
                                    assertThat(call.path("args").path("path").asText()).isEqualTo(path);
                                    matchingCall = true;
                                }
                            }
                        }
                        assertThat(matchingCall).as("Artifact tied to actual model requested write_file call").isTrue();
                        var download = support.client.send(HttpRequest.newBuilder(base.resolve(
                                "/api/threads/" + threadId + "/artifacts" + path)).timeout(Duration.ofSeconds(15)).GET().build(),
                                HttpResponse.BodyHandlers.ofByteArray());
                        assertThat(download.statusCode()).isEqualTo(200);
                        assertThat(download.body()).isNotEmpty();
                        String sha = GatewayFixture.hash(download.body());
                        assertThat(structured.path("artifactSha256").asText()).isEqualTo("sha256:" + sha);
                        assertThat(download.headers().firstValue("etag")).contains("\"" + sha + "\"");
                        var matchingArtifacts = published.stream().filter(value -> value.id().value()
                                .equals(structured.path("artifactId").asText())).toList();
                        assertThat(matchingArtifacts).singleElement().satisfies(value -> {
                            assertThat(value.provenance().runId().value()).isEqualTo(runId);
                            assertThat(value.provenance().executionRef()).isEqualTo(execution);
                            assertThat(value.provenance().sourceHash()).isEqualTo("sha256:" + sha);
                            assertThat(value.payload().sha256()).isEqualTo("sha256:" + sha);
                            assertThat(workspace.loadArtifact("default", value)).isEqualTo(download.body());
                        });
                        Files.write(evidence.resolve("artifact-" + artifacts.size() + ".bin"), download.body());
                        artifacts.add(Map.of("path", path, "artifact_id", structured.path("artifactId").asText(),
                                "execution_ref", execution, "sha256", sha, "bytes", download.body().length));
                    }
                    support.save("live-proof.json", Map.of("thread_id", threadId, "run_id", runId,
                            "promptSignature", LeadPromptAcceptanceTestSupport.bridge(api, threadId).promptSignature(),
                            "approval_count", approved.size(), "artifacts", artifacts,
                            "input_tokens", run.path("total_input_tokens").asLong(),
                            "output_tokens", run.path("total_output_tokens").asLong(),
                            "llm_call_count", run.path("llm_call_count").asLong(),
                            "stop_reason", run.path("stop_reason").asText(), "budget_changed", false));
                }
            } finally {
                GatewayFixture.portReleased(port);
                support.save("process-health.json", Map.of("port", port, "port_released", true,
                        "elapsed_ms", (System.nanoTime() - started) / 1_000_000, "data_root", data.toString()));
                System.out.println("M2_LIVE_EVIDENCE evidence=" + evidence + " data=" + data + " port_released=true");
            }
        }
    }
}

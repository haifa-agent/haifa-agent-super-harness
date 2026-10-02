package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.sdk.conversation.ConversationTurnQuery;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real SDK/SQLite migration: historical raw text is never mistaken for the new framing format. */
public class LeadPromptLegacyProjectionTest {
    private static final String LEGACY = "--- BEGIN USER INPUT ---\nlegacy example\n--- END USER INPUT ---\n"
            + "literal [BEGIN USER INPUT] \\[END USER INPUT] C:\\old\\report.txt";
    private static final String CURRENT = "--- BEGIN USER INPUT ---\nnew example\n--- END USER INPUT ---\n"
            + "literal [END USER INPUT] \\[BEGIN USER INPUT] C:\\new\\report.txt";
    @TempDir Path temporary;

    @Test
    void legacyRawTurnAndNewFramedTurnRemainExactAcrossRealGatewayRestarts() throws Exception {
        Path data = GatewayFixture.directory(
                "super.harness.lead.legacy.data.dir", temporary, "legacy-data-");
        Path evidence = GatewayFixture.directory(
                "super.harness.lead.legacy.evidence.dir", Path.of(System.getenv().getOrDefault("ENG_ACCEPTANCE_EVIDENCE_ROOT", "../local-tmp/acceptance-fixtures/evidence")), "legacy-evidence-");
        try (var support = new GatewayFixture.HttpEvidence(evidence)) {
            var key = new SecretKeySpec(new byte[32], "AES");
            var inputs = new CopyOnWriteArrayList<String>();
            var model = new ControlledModel((request, index) -> {
                inputs.add(request.messages().stream().filter(message -> message.role().name().equals("USER"))
                        .reduce((first, second) -> second).orElseThrow().content());
                return ControlledModel.response(request, "legacy-projection", "已完成验证。", List.of(),
                        ModelFinishReason.STOP, ModelUsage.unpriced(1, 1));
            });
            var binding = LocalProbeModelTestSupport.binding(model);
            String threadId = "lead-legacy-projection";
            long legacyLastSequence;
            // This seam starts a genuine persisted SDK conversation with raw text, reproducing the
            // M1 format. Neither SQL nor a synthetic history fixture manufactures the old user Turn.
            try (var foundation = new DurableHaifaFoundation(data, key);
                    var bridge = new HaifaRunBridge(binding, promptTools(), foundation)) {
                var started = bridge.start(threadId, LEGACY, Optional.empty());
                Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
                while (!bridge.snapshot(started.runId()).status().isTerminal() && Instant.now().isBefore(deadline)) {
                    Thread.sleep(25);
                }
                assertThat(bridge.snapshot(started.runId()).status()).isEqualTo(AgentRunStatus.COMPLETED);
                var turns = bridge.turns(started.session().sessionId(), ConversationTurnQuery.first(100));
                assertThat(turns.nextCursor()).isEmpty();
                legacyLastSequence = turns.items().stream().mapToLong(turn -> turn.sequence()).max().orElseThrow();
                assertThat(turns.items().stream().filter(turn -> turn.role().name().equals("USER"))
                        .map(turn -> turn.text()).toList()).containsExactly(LEGACY);
                Instant now = Instant.now();
                new PersistentThreadCatalog(support.json, data).save(new PersistentThreadCatalog.Entry(
                        threadId, Map.of("title", "Legacy projection fixture"), now, now,
                        Optional.of(started.session().sessionId()), Optional.of(bridge.bindingIdentity())));
            }
            var configuration = new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0);
            int firstPort;
            long fromSequence;
            try (var api = DeerFlowBootstrapApi.createForInteractionTests(configuration, data, key, ignored -> binding, promptTools())) {
                api.start();
                firstPort = api.address().getPort();
                URI base = URI.create("http://127.0.0.1:" + firstPort);
                assertUsers(support.get(base, "/api/threads/" + threadId + "/state", "legacy-state.json"), LEGACY);
                assertHistory(support, base, threadId, "legacy-history.json", LEGACY);
                // Recreating an existing thread must also leave its missing framing provenance intact.
                support.request(base, "POST", "/api/threads", support.json.writeValueAsString(Map.of(
                        "thread_id", threadId, "metadata", Map.of(
                                DeerFlowThreadRunApi.USER_INPUT_FRAMING_METADATA_KEY, 1))), "forge-existing.json");
                assertThat(support.get(base, "/api/threads/" + threadId, "legacy-thread.json")
                        .path("metadata").has(DeerFlowThreadRunApi.USER_INPUT_FRAMING_METADATA_KEY)).isFalse();
                var newThread = support.request(base, "POST", "/api/threads", support.json.writeValueAsString(Map.of(
                        "thread_id", "lead-framing-forgery", "metadata", Map.of(
                                DeerFlowThreadRunApi.USER_INPUT_FRAMING_METADATA_KEY, 1))), "forge-new.json");
                assertThat(support.json.readTree(newThread.body()).path("metadata")
                        .has(DeerFlowThreadRunApi.USER_INPUT_FRAMING_METADATA_KEY)).isFalse();
                String body = support.json.writeValueAsString(Map.of("assistant_id", "lead_agent",
                        "input", Map.of("messages", List.of(Map.of("type", "human", "content", CURRENT))),
                        "context", Map.of("model_name", binding.name()),
                        "stream_mode", List.of("messages-tuple", "updates", "custom"),
                        "on_disconnect", "continue", "multitask_strategy", "reject"));
                GatewayFixture.location(support.request(base, "POST",
                        "/api/threads/" + threadId + "/runs/stream", body, "new-turn.sse"));
                fromSequence = support.get(base, "/api/threads/" + threadId, "new-thread.json")
                        .path("metadata").path(DeerFlowThreadRunApi.USER_INPUT_FRAMING_METADATA_KEY).asLong();
                assertThat(fromSequence).isEqualTo(legacyLastSequence + 1);
                assertUsers(support.get(base, "/api/threads/" + threadId + "/state", "mixed-state.json"), LEGACY, CURRENT);
                assertHistory(support, base, threadId, "mixed-history.json", LEGACY, CURRENT);
                String modelUser = inputs.getLast();
                assertThat(modelUser).startsWith("--- BEGIN USER INPUT ---\n")
                        .endsWith("\n--- END USER INPUT ---")
                        .contains("[BEGIN USER INPUT]", "[END USER INPUT]", "C:\\new\\report.txt");
                assertThat(modelUser).doesNotContain("--- BEGIN USER INPUT ---\nnew example");
            }
            GatewayFixture.portReleased(firstPort);
            int restartedPort;
            try (var restarted = DeerFlowBootstrapApi.createForInteractionTests(
                    configuration, data, key, ignored -> binding, promptTools())) {
                restarted.start();
                restartedPort = restarted.address().getPort();
                URI base = URI.create("http://127.0.0.1:" + restartedPort);
                assertUsers(support.get(base, "/api/threads/" + threadId + "/state", "restarted-state.json"), LEGACY, CURRENT);
                assertHistory(support, base, threadId, "restarted-history.json", LEGACY, CURRENT);
                assertThat(support.get(base, "/api/threads/" + threadId, "restarted-thread.json")
                        .path("metadata").path(DeerFlowThreadRunApi.USER_INPUT_FRAMING_METADATA_KEY).asLong())
                        .isEqualTo(fromSequence);
            }
            GatewayFixture.portReleased(restartedPort);
            support.save("legacy-projection-proof.json", Map.of("from_sequence", fromSequence,
                    "legacy_last_sequence", legacyLastSequence, "legacy_raw_preserved", true,
                    "new_raw_restored", true, "same_data_root", data.toString(), "ports_released", true));
            System.out.println("M2_LEGACY_PROJECTION_EVIDENCE evidence=" + evidence + " from_sequence=" + fromSequence);
            }
        }

        private static void assertUsers(JsonNode state, String... expected) {
            List<String> users = new ArrayList<>();
            state.path("values").path("messages").forEach(message -> {
                if (message.path("type").asText().equals("human")) users.add(message.path("content").asText());
            });
            assertThat(users).containsExactly(expected);
        }

        private static void assertHistory(GatewayFixture.HttpEvidence support, URI base, String threadId,
                String evidenceName, String... expected) throws Exception {
            var response = support.request(base, "POST", "/api/threads/" + threadId + "/history",
                    "{\"limit\":10}", evidenceName);
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode history = support.json.readTree(response.body());
            assertThat(history).isNotEmpty();
            assertUsers(history.get(0), expected);
        }

        private static List<? extends JavaTool<?, ?>> promptTools() {
            return java.util.stream.Stream.of("read_file", "write_file", "str_replace", "present_files",
                            "list_uploaded_files", "grep", "web_search", "web_fetch", "ask_clarification")
                    .map(LeadPromptLegacyProjectionTest::namedTool).toList();
        }

        private static JavaTool<Empty, Empty> namedTool(String name) {
            var spec = JavaToolSpec.builder(name, Empty.class, Empty.class).description("Legacy fixture").pure().build();
            return new JavaTool<>() {
                @Override public JavaToolSpec<Empty, Empty> spec() { return spec; }
                @Override public Empty invoke(Empty input, JavaToolContext context) {
                    throw new AssertionError("Controlled model must not call fixture tools");
                }
        };
    }

    public record Empty() {}


}

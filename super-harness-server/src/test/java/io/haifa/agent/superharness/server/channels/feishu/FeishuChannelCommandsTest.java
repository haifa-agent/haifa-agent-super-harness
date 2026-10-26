package io.haifa.agent.superharness.server.channels.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.superharness.server.config.ChannelConnectionStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** DeerFlow IM command parity for Feishu: help/models/memory/agent/bootstrap/goal. */
class FeishuChannelCommandsTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path dataDirectory;

    @Test
    void helpModelsMemoryAndAgentListAreReadOnly() throws Exception {
        Harness harness = harness();
        harness.bridge.modelNames.addAll(List.of("deepseek-chat", "gpt-test"));
        harness.bridge.agents.add(new FeishuAgentBridge.AgentListItem("researcher", "Finds facts"));
        harness.bridge.memoryFacts.set(3);

        harness.worker.handleInbound(p2p("om-help", "/help"));
        assertThat(lastReply(harness)).isEqualTo(FeishuInteraction.COMMAND_HELP);

        harness.worker.handleInbound(p2p("om-models", "/models"));
        assertThat(lastReply(harness))
                .isEqualTo(FeishuInteraction.modelsNotice(List.of("deepseek-chat", "gpt-test")));

        harness.worker.handleInbound(p2p("om-memory", "/memory"));
        assertThat(lastReply(harness)).isEqualTo(FeishuInteraction.memoryNotice(3));

        harness.worker.handleInbound(p2p("om-agents", "/agent list"));
        assertThat(lastReply(harness))
                .contains("• lead_agent — Default agent")
                .contains("• researcher — Finds facts");
        assertThat(harness.bridge.turnTexts).isEmpty();
        assertThat(harness.bridge.createdThreads).isEmpty();
        harness.worker.close();
    }

    @Test
    void agentUsePinsCustomAgentAndLeadAgent() throws Exception {
        Harness harness = harness();
        harness.bridge.knownAgents.add("researcher");

        harness.worker.handleInbound(p2p("om-missing", "/agent use missing-agent"));
        assertThat(lastReply(harness)).contains("was not found");

        harness.worker.handleInbound(p2p("om-bad", "/agent use Bad_Name"));
        assertThat(lastReply(harness)).isEqualTo(FeishuInteraction.AGENT_INVALID_NAME);

        harness.worker.handleInbound(p2p("om-use", "/agent use researcher"));
        assertThat(lastReply(harness)).isEqualTo(FeishuInteraction.agentSelectedNotice("researcher"));
        assertThat(harness.bridge.createdWithAgent).containsExactly("researcher");
        String pinned = harness.threads.find(connectionId(harness), "oc_chat").orElseThrow();

        // Ordinary follow-up turns must carry the pin into TurnOptions (Codex P1).
        harness.worker.handleInbound(p2p("om-follow", "continue with the researcher"));
        assertThat(harness.bridge.turnTexts).containsExactly("continue with the researcher");
        assertThat(harness.bridge.lastAgent.get()).isEqualTo("researcher");

        harness.worker.handleInbound(p2p("om-lead", "/agent use lead_agent"));
        assertThat(lastReply(harness))
                .isEqualTo(FeishuInteraction.agentSelectedNotice(FeishuInteraction.DEFAULT_ASSISTANT_ID));
        assertThat(harness.bridge.createdWithAgent).containsExactly("researcher", "lead_agent");
        assertThat(harness.threads.find(connectionId(harness), "oc_chat").orElseThrow()).isNotEqualTo(pinned);

        harness.worker.handleInbound(p2p("om-lead-follow", "back to default"));
        assertThat(harness.bridge.lastAgent.get()).isNull();
        harness.worker.close();
    }

    @Test
    void bootstrapStartsTurnWithBootstrapOption() throws Exception {
        Harness harness = harness();
        harness.worker.handleInbound(p2p("om-boot", "/bootstrap"));
        assertThat(harness.bridge.turnTexts).containsExactly("Initialize workspace");
        assertThat(harness.bridge.lastBootstrap.get()).isTrue();

        harness.worker.handleInbound(p2p("om-boot-2", "/bootstrap set up my workspace"));
        assertThat(harness.bridge.turnTexts).containsExactly("Initialize workspace", "set up my workspace");
        harness.worker.close();
    }

    @Test
    void goalStatusClearAndSetMatchDeerFlowSemantics() throws Exception {
        Harness harness = harness();

        harness.worker.handleInbound(p2p("om-goal-empty", "/goal"));
        assertThat(lastReply(harness)).isEqualTo(FeishuInteraction.NO_ACTIVE_GOAL);

        harness.worker.handleInbound(p2p("om-goal-clear-empty", "/goal clear"));
        assertThat(lastReply(harness)).isEqualTo(FeishuInteraction.GOAL_CLEARED);

        harness.worker.handleInbound(p2p("om-goal-set", "/goal ship the demo"));
        assertThat(harness.bridge.goals.get("feishu-test-thread")).isEqualTo("ship the demo");
        assertThat(harness.bridge.turnTexts).containsExactly("ship the demo");

        harness.worker.handleInbound(p2p("om-goal-status", "/goal"));
        assertThat(lastReply(harness)).isEqualTo(FeishuInteraction.goalStatusNotice("ship the demo"));

        harness.worker.handleInbound(p2p("om-goal-clear", "/goal clear"));
        assertThat(lastReply(harness)).isEqualTo(FeishuInteraction.GOAL_CLEARED);
        assertThat(harness.bridge.goals).doesNotContainKey("feishu-test-thread");
        harness.worker.close();
    }

    @Test
    void compactRemainsWebOnlyWhileGoalIsHandled() throws Exception {
        Harness harness = harness();
        harness.worker.handleInbound(p2p("om-compact", "/compact"));
        assertThat(lastReply(harness)).isEqualTo(FeishuInteraction.COMMAND_WEB_ONLY);
        harness.worker.handleInbound(p2p("om-skill", "/skill demo"));
        assertThat(lastReply(harness)).isEqualTo(FeishuInteraction.COMMAND_WEB_ONLY);
        harness.worker.close();
    }

    @Test
    void parseGoalCommandMatchesDeerFlowAliases() {
        assertThat(FeishuInteraction.parseGoalCommand("").kind()).isEqualTo(FeishuInteraction.GoalKind.STATUS);
        assertThat(FeishuInteraction.parseGoalCommand("clear").kind()).isEqualTo(FeishuInteraction.GoalKind.CLEAR);
        assertThat(FeishuInteraction.parseGoalCommand("RESET").kind()).isEqualTo(FeishuInteraction.GoalKind.CLEAR);
        assertThat(FeishuInteraction.parseGoalCommand("off").kind()).isEqualTo(FeishuInteraction.GoalKind.CLEAR);
        assertThat(FeishuInteraction.parseGoalCommand("ship it").kind()).isEqualTo(FeishuInteraction.GoalKind.SET);
        assertThat(FeishuInteraction.parseGoalCommand("ship it").objective()).isEqualTo("ship it");
        assertThat(FeishuInteraction.normalizeGoalObjective("  a   b  ")).contains("a b");
        assertThat(FeishuInteraction.normalizeGoalObjective("")).isEmpty();
    }

    private Harness harness() throws Exception {
        ChannelConnectionStore connections = new ChannelConnectionStore(json, dataDirectory);
        FeishuChatThreadStore threads = new FeishuChatThreadStore(json, dataDirectory);
        ScriptedBridge bridge = new ScriptedBridge();
        AtomicReference<RecordingFeishuTransport> transportRef = new AtomicReference<>();
        FeishuChannelWorker worker = new FeishuChannelWorker(connections, threads, bridge, () -> {
            RecordingFeishuTransport transport = new RecordingFeishuTransport();
            transportRef.set(transport);
            return transport;
        });
        worker.startOrRestart("cli_x", "secret_x", FeishuOpenDomain.DEFAULT);
        String code = ChannelConnectionStore.newBindingCode();
        connections.createOauthStateWithinCap(
                "owner",
                "feishu",
                code,
                java.time.Instant.now().plusSeconds(60),
                ChannelConnectionStore.MAX_PENDING_CONNECT_CODES_PER_PROVIDER);
        connections.bindFeishuFromConnectCode(code, "ou_owner", "oc_chat", Map.of());
        // Pre-bind a stable thread id so /goal set can resolve without relying on UUID generation.
        String connectionId = connections
                .findConnectionByExternalIdentity("feishu", "ou_owner", "oc_chat")
                .orElseThrow()
                .path("id")
                .asText("");
        threads.bindCurrent(connectionId, "oc_chat", "feishu-test-thread");
        return new Harness(worker, transportRef.get(), bridge, connections, threads);
    }

    private static FeishuInboundMessage p2p(String messageId, String text) {
        return new FeishuInboundMessage("oc_chat", messageId, "ou_owner", text, "p2p");
    }

    private static String connectionId(Harness harness) {
        return harness
                .connections
                .findConnectionByExternalIdentity("feishu", "ou_owner", "oc_chat")
                .orElseThrow()
                .path("id")
                .asText("");
    }

    private static String lastReply(Harness harness) {
        List<RecordingFeishuTransport.CardReply> replies = harness.transport.replies();
        assertThat(replies).isNotEmpty();
        return replies.get(replies.size() - 1).text();
    }

    private record Harness(
            FeishuChannelWorker worker,
            RecordingFeishuTransport transport,
            ScriptedBridge bridge,
            ChannelConnectionStore connections,
            FeishuChatThreadStore threads) {}

    private static final class ScriptedBridge implements FeishuAgentBridge {
        private final List<String> modelNames = new ArrayList<>();
        private final List<AgentListItem> agents = new ArrayList<>();
        private final List<String> knownAgents = new ArrayList<>();
        private final AtomicInteger memoryFacts = new AtomicInteger();
        private final List<String> turnTexts = new CopyOnWriteArrayList<>();
        private final List<String> createdThreads = new CopyOnWriteArrayList<>();
        private final List<String> createdWithAgent = new CopyOnWriteArrayList<>();
        private final Map<String, String> goals = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, String> pins = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicBoolean lastBootstrap = new AtomicBoolean(false);
        private final AtomicReference<String> lastAgent = new AtomicReference<>();

        @Override
        public TurnResult runTurn(
                String ownerUserId,
                String threadId,
                Map<String, Object> channelMetadata,
                String userText,
                FeishuHumanResponse response,
                List<UploadedFile> files,
                TurnOptions options)
                throws Exception {
            TurnOptions resolved = options == null ? TurnOptions.defaults() : options;
            lastBootstrap.set(resolved.bootstrap());
            lastAgent.set(resolved.agentName());
            turnTexts.add(userText);
            return new TurnResult(threadId, "ok:" + userText);
        }

        @Override
        public TurnResult runTurn(
                String ownerUserId, String threadId, Map<String, Object> channelMetadata, String userText)
                throws Exception {
            return runTurn(
                    ownerUserId, threadId, channelMetadata, userText, null, List.of(), TurnOptions.defaults());
        }

        @Override
        public void createThread(String ownerUserId, String threadId, Map<String, Object> channelMetadata) {
            createdThreads.add(threadId);
        }

        @Override
        public void createThreadWithAgent(
                String ownerUserId,
                String threadId,
                Map<String, Object> channelMetadata,
                String agentName) {
            createdThreads.add(threadId);
            createdWithAgent.add(agentName);
            if (agentName == null
                    || agentName.isBlank()
                    || FeishuInteraction.DEFAULT_ASSISTANT_ID.equalsIgnoreCase(agentName)) {
                pins.remove(threadId);
            } else {
                pins.put(threadId, agentName);
            }
        }

        @Override
        public Optional<String> pinnedAgentName(String ownerUserId, String threadId) {
            return Optional.ofNullable(pins.get(threadId));
        }

        @Override
        public List<String> listModelNames(String ownerUserId) {
            return List.copyOf(modelNames);
        }

        @Override
        public int memoryFactCount(String ownerUserId) {
            return memoryFacts.get();
        }

        @Override
        public List<AgentListItem> listAgents(String ownerUserId) {
            return List.copyOf(agents);
        }

        @Override
        public boolean agentExists(String ownerUserId, String agentName) {
            return knownAgents.contains(agentName);
        }

        @Override
        public Optional<String> currentGoal(String ownerUserId, String threadId) {
            return Optional.ofNullable(goals.get(threadId));
        }

        @Override
        public void setGoal(String ownerUserId, String threadId, String objective) {
            goals.put(threadId, objective);
        }

        @Override
        public void clearGoal(String ownerUserId, String threadId) {
            goals.remove(threadId);
        }
    }
}

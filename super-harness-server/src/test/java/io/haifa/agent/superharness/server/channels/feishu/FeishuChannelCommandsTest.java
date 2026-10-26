package io.haifa.agent.superharness.server.channels.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.superharness.server.testsupport.feishu.FeishuWorkerHarness;
import io.haifa.agent.superharness.server.testsupport.feishu.ScriptedFeishuBridge;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** DeerFlow IM command parity for Feishu: help/models/memory/agent/bootstrap/goal. */
class FeishuChannelCommandsTest {
    @TempDir
    Path dataDirectory;

    @ParameterizedTest(name = "{0}")
    @MethodSource("readOnlyCommands")
    void readOnlyCommandsReturnCatalogText(
            String label, String inbound, Consumer<ScriptedFeishuBridge> setup, String expected)
            throws Exception {
        try (FeishuWorkerHarness harness = commandsHarness()) {
            setup.accept(harness.bridge);
            harness.worker.handleInbound(FeishuWorkerHarness.p2p("om-" + label, inbound));
            assertThat(harness.lastCardReply()).isEqualTo(expected);
            assertThat(harness.bridge.turnTexts).isEmpty();
            assertThat(harness.bridge.createdThreads).isEmpty();
        }
    }

    static Stream<Arguments> readOnlyCommands() {
        return Stream.of(
                Arguments.of(
                        "help",
                        "/help",
                        (Consumer<ScriptedFeishuBridge>) bridge -> {},
                        FeishuInteraction.COMMAND_HELP),
                Arguments.of(
                        "models",
                        "/models",
                        (Consumer<ScriptedFeishuBridge>)
                                bridge -> bridge.modelNames.addAll(List.of("deepseek-chat", "gpt-test")),
                        FeishuInteraction.modelsNotice(List.of("deepseek-chat", "gpt-test"))),
                Arguments.of(
                        "memory",
                        "/memory",
                        (Consumer<ScriptedFeishuBridge>) bridge -> bridge.memoryFacts.set(3),
                        FeishuInteraction.memoryNotice(3)),
                Arguments.of(
                        "compact",
                        "/compact",
                        (Consumer<ScriptedFeishuBridge>) bridge -> {},
                        FeishuInteraction.COMMAND_WEB_ONLY),
                Arguments.of(
                        "skill",
                        "/skill demo",
                        (Consumer<ScriptedFeishuBridge>) bridge -> {},
                        FeishuInteraction.COMMAND_WEB_ONLY));
    }

    @Test
    void agentListIncludesDefaultAndCustomAgents() throws Exception {
        try (FeishuWorkerHarness harness = commandsHarness()) {
            harness.bridge.agents.add(new FeishuAgentBridge.AgentListItem("researcher", "Finds facts"));
            harness.worker.handleInbound(FeishuWorkerHarness.p2p("om-agents", "/agent list"));
            assertThat(harness.lastCardReply())
                    .contains("• lead_agent — Default agent")
                    .contains("• researcher — Finds facts");
            assertThat(harness.bridge.turnTexts).isEmpty();
            assertThat(harness.bridge.createdThreads).isEmpty();
        }
    }

    @Test
    void agentUsePinsCustomAgentAndLeadAgent() throws Exception {
        try (FeishuWorkerHarness harness = commandsHarness()) {
            harness.bridge.knownAgents.add("researcher");

            harness.worker.handleInbound(FeishuWorkerHarness.p2p("om-missing", "/agent use missing-agent"));
            assertThat(harness.lastCardReply()).contains("was not found");

            harness.worker.handleInbound(FeishuWorkerHarness.p2p("om-bad", "/agent use Bad_Name"));
            assertThat(harness.lastCardReply()).isEqualTo(FeishuInteraction.AGENT_INVALID_NAME);

            harness.worker.handleInbound(FeishuWorkerHarness.p2p("om-use", "/agent use researcher"));
            assertThat(harness.lastCardReply())
                    .isEqualTo(FeishuInteraction.agentSelectedNotice("researcher"));
            assertThat(harness.bridge.createdWithAgent).containsExactly("researcher");
            String pinned = harness.threads
                    .find(harness.connectionId(), FeishuWorkerHarness.DEFAULT_CHAT_ID)
                    .orElseThrow();

            // Ordinary follow-up turns must carry the pin into TurnOptions (Codex P1).
            harness.worker.handleInbound(
                    FeishuWorkerHarness.p2p("om-follow", "continue with the researcher"));
            assertThat(harness.bridge.turnTexts).containsExactly("continue with the researcher");
            assertThat(harness.bridge.lastAgent.get()).isEqualTo("researcher");

            harness.worker.handleInbound(FeishuWorkerHarness.p2p("om-lead", "/agent use lead_agent"));
            assertThat(harness.lastCardReply())
                    .isEqualTo(FeishuInteraction.agentSelectedNotice(FeishuInteraction.DEFAULT_ASSISTANT_ID));
            assertThat(harness.bridge.createdWithAgent).containsExactly("researcher", "lead_agent");
            assertThat(harness.threads
                            .find(harness.connectionId(), FeishuWorkerHarness.DEFAULT_CHAT_ID)
                            .orElseThrow())
                    .isNotEqualTo(pinned);

            harness.worker.handleInbound(FeishuWorkerHarness.p2p("om-lead-follow", "back to default"));
            assertThat(harness.bridge.lastAgent.get()).isNull();
        }
    }

    @Test
    void bootstrapStartsTurnWithBootstrapOption() throws Exception {
        try (FeishuWorkerHarness harness = commandsHarness()) {
            harness.worker.handleInbound(FeishuWorkerHarness.p2p("om-boot", "/bootstrap"));
            assertThat(harness.bridge.turnTexts).containsExactly("Initialize workspace");
            assertThat(harness.bridge.lastBootstrap.get()).isTrue();

            harness.worker.handleInbound(
                    FeishuWorkerHarness.p2p("om-boot-2", "/bootstrap set up my workspace"));
            assertThat(harness.bridge.turnTexts)
                    .containsExactly("Initialize workspace", "set up my workspace");
        }
    }

    @Test
    void goalStatusClearAndSetMatchDeerFlowSemantics() throws Exception {
        try (FeishuWorkerHarness harness = commandsHarness()) {
            harness.worker.handleInbound(FeishuWorkerHarness.p2p("om-goal-empty", "/goal"));
            assertThat(harness.lastCardReply()).isEqualTo(FeishuInteraction.NO_ACTIVE_GOAL);

            harness.worker.handleInbound(FeishuWorkerHarness.p2p("om-goal-clear-empty", "/goal clear"));
            assertThat(harness.lastCardReply()).isEqualTo(FeishuInteraction.GOAL_CLEARED);

            harness.worker.handleInbound(FeishuWorkerHarness.p2p("om-goal-set", "/goal ship the demo"));
            assertThat(harness.bridge.goals.get(FeishuWorkerHarness.DEFAULT_THREAD_ID))
                    .isEqualTo("ship the demo");
            assertThat(harness.bridge.turnTexts).containsExactly("ship the demo");

            harness.worker.handleInbound(FeishuWorkerHarness.p2p("om-goal-status", "/goal"));
            assertThat(harness.lastCardReply())
                    .isEqualTo(FeishuInteraction.goalStatusNotice("ship the demo"));

            harness.worker.handleInbound(FeishuWorkerHarness.p2p("om-goal-clear", "/goal clear"));
            assertThat(harness.lastCardReply()).isEqualTo(FeishuInteraction.GOAL_CLEARED);
            assertThat(harness.bridge.goals).doesNotContainKey(FeishuWorkerHarness.DEFAULT_THREAD_ID);
        }
    }

    @ParameterizedTest(name = "goal arg [{0}] -> {1}")
    @MethodSource("goalAliases")
    void parseGoalCommandMatchesDeerFlowAliases(
            String arg, FeishuInteraction.GoalKind kind, String objective) {
        FeishuInteraction.GoalCommand parsed = FeishuInteraction.parseGoalCommand(arg);
        assertThat(parsed.kind()).isEqualTo(kind);
        if (objective != null) {
            assertThat(parsed.objective()).isEqualTo(objective);
        }
    }

    static Stream<Arguments> goalAliases() {
        return Stream.of(
                Arguments.of("", FeishuInteraction.GoalKind.STATUS, null),
                Arguments.of("clear", FeishuInteraction.GoalKind.CLEAR, null),
                Arguments.of("RESET", FeishuInteraction.GoalKind.CLEAR, null),
                Arguments.of("off", FeishuInteraction.GoalKind.CLEAR, null),
                Arguments.of("ship it", FeishuInteraction.GoalKind.SET, "ship it"));
    }

    @Test
    void normalizeGoalObjectiveCollapsesWhitespace() {
        assertThat(FeishuInteraction.normalizeGoalObjective("  a   b  ")).contains("a b");
        assertThat(FeishuInteraction.normalizeGoalObjective("")).isEmpty();
    }

    private FeishuWorkerHarness commandsHarness() throws Exception {
        return FeishuWorkerHarness.open(dataDirectory, new ScriptedFeishuBridge().withOkAssistantReplies())
                .withStableThread();
    }
}

package io.haifa.agent.superharness.server.testsupport.feishu;

import io.haifa.agent.superharness.server.channels.feishu.FeishuAgentBridge;
import io.haifa.agent.superharness.server.channels.feishu.FeishuHumanResponse;
import io.haifa.agent.superharness.server.channels.feishu.FeishuInteraction;
import io.haifa.agent.superharness.server.channels.feishu.FeishuPendingInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared Feishu worker bridge double for unit-layer tests (commands / routing / Phase-2 /
 * reactions). One class covers the ScriptedBridge variants previously copied across Feishu*Test
 * files.
 */
public final class ScriptedFeishuBridge implements FeishuAgentBridge {
    /** Assistant text shape returned by a successful non-steer turn. */
    public enum ReplyStyle {
        /** Commands suite: {@code ok:<text>}. */
        OK,
        /** Multi-thread routing: {@code continued:<text>}. */
        CONTINUED,
        /** Phase-2 / Interaction: bare {@code continued}. */
        CONTINUED_BARE,
        /** Reactions / WorkerUnit: {@code done:<text>}. */
        DONE
    }

    public final List<String> modelNames = new ArrayList<>();
    public final List<AgentListItem> agents = new ArrayList<>();
    public final List<String> knownAgents = new ArrayList<>();
    public final AtomicInteger memoryFacts = new AtomicInteger();
    public final List<String> turnTexts = new CopyOnWriteArrayList<>();
    public final List<String> turnThreads = new CopyOnWriteArrayList<>();
    public final List<String> steerThreads = new CopyOnWriteArrayList<>();
    public final List<String> createdThreads = new CopyOnWriteArrayList<>();
    public final List<String> createdWithAgent = new CopyOnWriteArrayList<>();
    public final Map<String, String> goals = new ConcurrentHashMap<>();
    public final Map<String, String> pins = new ConcurrentHashMap<>();
    public final AtomicInteger turns = new AtomicInteger();
    public final AtomicInteger steerCalls = new AtomicInteger();
    public final AtomicInteger cancelCalls = new AtomicInteger();
    public final AtomicReference<FeishuHumanResponse> responses = new AtomicReference<>();
    public final AtomicReference<String> lastMode = new AtomicReference<>("flash");
    public final AtomicBoolean lastBootstrap = new AtomicBoolean(false);
    public final AtomicReference<String> lastAgent = new AtomicReference<>();
    public final AtomicBoolean activeRun = new AtomicBoolean(false);
    public final AtomicBoolean failNextTurn = new AtomicBoolean(false);
    /** When true, every non-steer turn throws (reactions failure path). */
    public final AtomicBoolean failTurns = new AtomicBoolean(false);

    public volatile FeishuPendingInput pending;
    public volatile FeishuPendingInput turnPending;
    public volatile CountDownLatch blockEnter;
    public volatile CountDownLatch blockRelease;
    /** When true, only block when userText equals {@code "long"} (routing tests). */
    public volatile boolean blockOnlyOnLongText;

    private volatile ReplyStyle replyStyle = ReplyStyle.CONTINUED;

    public ScriptedFeishuBridge withOkAssistantReplies() {
        replyStyle = ReplyStyle.OK;
        return this;
    }

    public ScriptedFeishuBridge withContinuedAssistantReplies() {
        replyStyle = ReplyStyle.CONTINUED;
        return this;
    }

    public ScriptedFeishuBridge withBareContinuedReplies() {
        replyStyle = ReplyStyle.CONTINUED_BARE;
        return this;
    }

    /** Reactions suite reply shape: {@code done:<text>}. */
    public ScriptedFeishuBridge withDoneAssistantReplies() {
        replyStyle = ReplyStyle.DONE;
        return this;
    }

    public ScriptedFeishuBridge blockOnlyOnLongText() {
        blockOnlyOnLongText = true;
        return this;
    }

    @Override
    public TurnResult runTurn(
            String ownerUserId, String threadId, Map<String, Object> channelMetadata, String userText)
            throws Exception {
        return runTurn(
                ownerUserId, threadId, channelMetadata, userText, null, List.of(), TurnOptions.defaults());
    }

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
        lastMode.set(resolved.mode());
        lastBootstrap.set(resolved.bootstrap());
        lastAgent.set(resolved.agentName());
        if (resolved.steer()) {
            // Steer must not wait on the busy Turn latch — it runs on the connectExecutor path.
            steerCalls.incrementAndGet();
            steerThreads.add(threadId);
            return new TurnResult(threadId, "steered");
        }
        if (failTurns.get() || failNextTurn.getAndSet(false)) {
            throw new IllegalStateException("scripted Feishu turn failure");
        }
        CountDownLatch enter = blockEnter;
        CountDownLatch release = blockRelease;
        if (blockOnlyOnLongText) {
            if (release != null && "long".equals(userText)) {
                if (enter != null) {
                    enter.countDown();
                }
                release.await(3, TimeUnit.SECONDS);
            }
        } else {
            if (enter != null) {
                enter.countDown();
            }
            if (release != null) {
                release.await(3, TimeUnit.SECONDS);
            }
        }
        turns.incrementAndGet();
        turnThreads.add(threadId);
        turnTexts.add(userText);
        responses.set(response);
        pending = null;
        FeishuPendingInput resultPending = turnPending;
        turnPending = null;
        // Do not clear activeRun here: MultiThread FT-08 keeps the flag set across an independent
        // sibling-thread turn so /steer still sees the long-running thread as active.
        return new TurnResult(threadId, assistantText(userText), resultPending);
    }

    private String assistantText(String userText) {
        return switch (replyStyle) {
            case OK -> "ok:" + (userText == null ? "" : userText);
            case CONTINUED -> "continued:" + (userText == null ? "" : userText);
            case CONTINUED_BARE -> "continued";
            case DONE -> "done:" + (userText == null ? "" : userText);
        };
    }

    @Override
    public Optional<FeishuPendingInput> currentPending(String ownerUserId, String threadId) {
        return Optional.ofNullable(pending);
    }

    @Override
    public boolean cancelActiveRun(String ownerUserId, String threadId) {
        cancelCalls.incrementAndGet();
        activeRun.set(false);
        return true;
    }

    @Override
    public boolean hasActiveRun(String ownerUserId, String threadId) {
        return activeRun.get();
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

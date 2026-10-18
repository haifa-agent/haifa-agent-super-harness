package io.haifa.agent.superharness.server.channels.feishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.superharness.server.config.ChannelConnectionStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * W20-task-9 multi-thread routing Oracle coverage (FT-01..FT-14). Uses transport/bridge doubles:
 * real Feishu tenant end-to-end is NOT_RUN here (see the task handoff).
 */
class FeishuMultiThreadRoutingTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path dataDirectory;

    // ---- FT-01 -----------------------------------------------------------------------------

    @Test
    void p2pFirstMessageCreatesAndReusesCurrentThread() throws Exception {
        Harness harness = harness();
        String chat = "oc_chat";
        worker(harness).handleInbound(p2p(harness, "om-1", "hello"));
        String first = harness.threads().find(connectionId(harness), chat).orElseThrow();
        worker(harness).handleInbound(p2p(harness, "om-2", "again"));
        assertThat(harness.bridge().turnThreads).containsExactly(first, first);
        harness.worker().close();
    }

    // ---- FT-02 -----------------------------------------------------------------------------

    @Test
    void twoNewCommandsRotateCurrentPointerAndKeepOldThreads() throws Exception {
        Harness harness = harness();
        String connectionId = connectionId(harness);
        worker(harness).handleInbound(p2p(harness, "om-1", "hello"));
        String threadA = harness.threads().find(connectionId, "oc_chat").orElseThrow();

        worker(harness).handleInbound(p2p(harness, "om-new-1", "/new"));
        String threadB = harness.threads().find(connectionId, "oc_chat").orElseThrow();
        worker(harness).handleInbound(p2p(harness, "om-new-2", "/new"));
        String threadC = harness.threads().find(connectionId, "oc_chat").orElseThrow();

        assertThat(List.of(threadA, threadB, threadC)).doesNotHaveDuplicates();
        assertThat(harness.bridge().createdThreads).contains(threadB, threadC);
        assertThat(harness.threads().find(connectionId, "oc_chat")).contains(threadC);
        // The first thread's own message alias survives both rotations.
        assertThat(harness.threads().findAlias(connectionId, "oc_chat", "om-1")).contains(threadA);
        harness.worker().close();
    }

    // ---- FT-03 -----------------------------------------------------------------------------

    @Test
    void statusIsReadOnlyAndResolvesOldMessages() throws Exception {
        Harness harness = harness();
        String connectionId = connectionId(harness);
        worker(harness).handleInbound(p2p(harness, "om-empty", "/status"));
        assertThat(lastReply(harness)).isEqualTo(FeishuInteraction.NO_ACTIVE_THREAD);
        assertThat(harness.threads().find(connectionId, "oc_chat")).isEmpty();

        worker(harness).handleInbound(p2p(harness, "om-1", "hello"));
        String threadA = harness.threads().find(connectionId, "oc_chat").orElseThrow();
        worker(harness).handleInbound(p2p(harness, "om-status", "/status"));
        assertThat(lastReply(harness)).isEqualTo(FeishuInteraction.ACTIVE_THREAD + threadA);

        worker(harness).handleInbound(p2p(harness, "om-new", "/new"));
        String threadB = harness.threads().find(connectionId, "oc_chat").orElseThrow();
        // Replying to the old message shows the old thread, not the new pointer.
        worker(harness).handleInbound(reply(harness, "om-reply-status", "/status", "om-1", "", ""));
        assertThat(lastReply(harness)).isEqualTo(FeishuInteraction.ACTIVE_THREAD + threadA);
        assertThat(threadB).isNotEqualTo(threadA);
        harness.worker().close();
    }

    // ---- FT-04 -----------------------------------------------------------------------------

    @Test
    void newThenNormalMessageEntersNewThreadAndGroupNewIsAnchored() throws Exception {
        Harness harness = harness();
        String connectionId = connectionId(harness);
        worker(harness).handleInbound(p2p(harness, "om-1", "hello"));
        worker(harness).handleInbound(p2p(harness, "om-new", "/new"));
        String threadB = harness.threads().find(connectionId, "oc_chat").orElseThrow();
        worker(harness).handleInbound(p2p(harness, "om-2", "next"));
        assertThat(harness.bridge().turnThreads.getLast()).isEqualTo(threadB);

        // Group /new without reply context anchors only its own command/confirmation ids.
        bindGroup(harness, "oc_group");
        worker(harness).handleInbound(group(harness, "oc_group", "om-g-new", "/new", "", "", ""));
        String groupNewThread = harness.bridge().createdThreads.getLast();
        worker(harness).handleInbound(group(harness, "oc_group", "om-g-plain", "plain", "", "", ""));
        assertThat(harness.bridge().turnThreads.getLast()).isNotEqualTo(groupNewThread);
        harness.worker().close();
    }

    // ---- FT-05 -----------------------------------------------------------------------------

    @Test
    void replyToOldMessageAfterNewStillRoutesToOldThread() throws Exception {
        Harness harness = harness();
        String connectionId = connectionId(harness);
        worker(harness).handleInbound(p2p(harness, "om-1", "hello"));
        String threadA = harness.threads().find(connectionId, "oc_chat").orElseThrow();
        worker(harness).handleInbound(p2p(harness, "om-new", "/new"));
        String threadB = harness.threads().find(connectionId, "oc_chat").orElseThrow();

        // Reply to old message om-1 -> thread A; ordinary message -> thread B.
        worker(harness).handleInbound(reply(harness, "om-reply", "follow up", "om-1", "", ""));
        assertThat(harness.bridge().turnThreads.getLast()).isEqualTo(threadA);
        worker(harness).handleInbound(p2p(harness, "om-2", "plain"));
        assertThat(harness.bridge().turnThreads.getLast()).isEqualTo(threadB);
        harness.worker().close();
    }

    // ---- FT-06 -----------------------------------------------------------------------------

    @Test
    void groupTopicsIsolateAndContinueByRoot() throws Exception {
        Harness harness = harness();
        bindGroup(harness, "oc_group");
        // Two different roots -> two threads; same root -> same thread.
        worker(harness).handleInbound(group(harness, "oc_group", "om-a1", "a1", "", "root-a", ""));
        String threadA = harness.bridge().turnThreads.getLast();
        worker(harness).handleInbound(group(harness, "oc_group", "om-b1", "b1", "", "root-b", ""));
        String threadB = harness.bridge().turnThreads.getLast();
        assertThat(threadA).isNotEqualTo(threadB);
        worker(harness).handleInbound(group(harness, "oc_group", "om-a2", "a2", "", "root-a", ""));
        assertThat(harness.bridge().turnThreads.getLast()).isEqualTo(threadA);
        harness.worker().close();
    }

    // ---- FT-07 -----------------------------------------------------------------------------

    @Test
    void aliasLookupUsesDeterministicPriority() throws Exception {
        FeishuChatThreadStore store = new FeishuChatThreadStore(json, dataDirectory);
        store.bindAliasIfAbsent("c", "chat", "root", "thread-root");
        store.bindAliasIfAbsent("c", "chat", "parent", "thread-parent");
        store.bindAliasIfAbsent("c", "chat", "fthread", "thread-fthread");
        FeishuInboundMessage message = new FeishuInboundMessage(
                "chat", "om", "ou", "hi", "group", "text", List.of(), null, "root", "parent", "fthread");
        // root_id wins over parent_id and feishu thread_id.
        assertThat(store.findAlias("c", "chat", message.rootId())).contains("thread-root");
        assertThat(store.findAlias("c", "chat", message.parentId())).contains("thread-parent");
        assertThat(store.findAlias("c", "chat", message.feishuThreadId())).contains("thread-fthread");
    }

    // ---- FT-08 / FT-09 ---------------------------------------------------------------------

    @Test
    void differentThreadsRunIndependentlyAndStopOnlyTargetsOne() throws Exception {
        Harness harness = harness();
        String connectionId = connectionId(harness);
        harness.bridge().activeRun = true;
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        harness.bridge().blockEnter = entered;
        harness.bridge().blockRelease = release;
        // Thread A starts a long run that blocks until released.
        Thread longRun = new Thread(() -> {
            try {
                worker(harness).handleInbound(p2p(harness, "om-a", "long"));
            } catch (Exception ignored) {
                // Released below.
            }
        });
        longRun.start();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        String threadA = harness.threads().find(connectionId, "oc_chat").orElseThrow();

        // /new is not blocked while A runs; B is an independent thread.
        worker(harness).handleInbound(p2p(harness, "om-new", "/new"));
        String threadB = harness.threads().find(connectionId, "oc_chat").orElseThrow();
        assertThat(threadB).isNotEqualTo(threadA);
        worker(harness).handleInbound(p2p(harness, "om-b", "task on b"));
        assertThat(harness.bridge().turnThreads).contains(threadB);

        // /steer as a reply to A's old message targets A only.
        worker(harness).handleInbound(reply(harness, "om-steer", "/steer more", "om-a", "", ""));
        assertThat(harness.bridge().steerThreads).contains(threadA);
        assertThat(harness.bridge().steerThreads).doesNotContain(threadB);

        release.countDown();
        longRun.join(3_000);
        harness.worker().close();
    }

    // ---- FT-10 -----------------------------------------------------------------------------

    @Test
    void cardCallbackUsesImmutableRequestAliasAcrossNew() throws Exception {
        Harness harness = harness();
        String connectionId = connectionId(harness);
        worker(harness).handleInbound(p2p(harness, "om-1", "hello"));
        String threadA = harness.threads().find(connectionId, "oc_chat").orElseThrow();
        // Simulate the production card path binding request:req-1 -> thread A.
        harness.threads().bindAliasIfAbsent(connectionId, "oc_chat", "request:req-1", threadA);
        worker(harness).handleInbound(p2p(harness, "om-new", "/new"));
        String threadB = harness.threads().find(connectionId, "oc_chat").orElseThrow();
        assertThat(threadB).isNotEqualTo(threadA);

        harness.bridge().pending = approval("req-1");
        worker(harness).handleInbound(new FeishuInboundMessage(
                "oc_chat", "om-card", "ou_owner", "", "p2p",
                new FeishuInboundMessage.CardAction("haifa_interaction", "req-1", "approve", "approve", "2.0")));
        assertThat(harness.bridge().turnThreads.getLast()).isEqualTo(threadA);
        harness.worker().close();
    }

    @Test
    void cardCallbackWithoutRequestAliasIsSafelyRejected() throws Exception {
        Harness harness = harness();
        String connectionId = connectionId(harness);
        worker(harness).handleInbound(p2p(harness, "om-1", "hello"));
        String threadA = harness.threads().find(connectionId, "oc_chat").orElseThrow();
        // No request alias -> reject; must not fall back to the current pointer or run a turn.
        int runsBefore = harness.bridge().turnThreads.size();
        harness.bridge().pending = approval("req-unknown");
        worker(harness).handleInbound(new FeishuInboundMessage(
                "oc_chat", "om-card", "ou_owner", "", "p2p",
                new FeishuInboundMessage.CardAction("haifa_interaction", "req-unknown", "approve", "approve", "2.0")));
        assertThat(harness.bridge().turnThreads).hasSize(runsBefore);
        assertThat(lastReply(harness)).isEqualTo(FeishuInteraction.ALREADY_HANDLED);
        assertThat(threadA).isNotBlank();
        harness.worker().close();
    }

    // ---- FT-11 -----------------------------------------------------------------------------

    @Test
    void legacyMappingFileIsReadAndExtendedIdempotently() throws Exception {
        Path file = dataDirectory.resolve("channels").resolve("feishu-thread-map.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"mappings\":{\"conn|chat\":{\"connection_id\":\"conn\","
                + "\"chat_id\":\"chat\",\"thread_id\":\"feishu-old\"}}}");
        FeishuChatThreadStore store = new FeishuChatThreadStore(json, dataDirectory);
        assertThat(store.find("conn", "chat")).contains("feishu-old");
        assertThat(store.getOrCreate("conn", "chat")).isEqualTo("feishu-old");
        store.bindAliasIfAbsent("conn", "chat", "alias-1", "feishu-old");
        // Legacy pointer survives the alias write and repeated migration reads.
        assertThat(store.find("conn", "chat")).contains("feishu-old");
        assertThat(store.findAlias("conn", "chat", "alias-1")).contains("feishu-old");
    }

    // ---- FT-12 -----------------------------------------------------------------------------

    @Test
    void concurrentFirstMessagesAndRedeliveryKeepOneThread() throws Exception {
        FeishuChatThreadStore store = new FeishuChatThreadStore(json, dataDirectory);
        CountDownLatch start = new CountDownLatch(1);
        List<String> winners = new CopyOnWriteArrayList<>();
        List<Thread> threads = new CopyOnWriteArrayList<>();
        for (int index = 0; index < 8; index++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    winners.add(store.bindAliasIfAbsent("c", "chat", "topic", "feishu-" + java.util.UUID.randomUUID()));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(3_000);
        }
        assertThat(winners).hasSize(8);
        assertThat(winners.stream().distinct().count()).isEqualTo(1);
    }

    // ---- FT-13 -----------------------------------------------------------------------------

    @Test
    void corruptStoreFailsClosedWithoutOverwriting() throws Exception {
        Path file = dataDirectory.resolve("channels").resolve("feishu-thread-map.json");
        Files.createDirectories(file.getParent());
        String corrupt = "{ this is not json";
        Files.writeString(file, corrupt);
        FeishuChatThreadStore store = new FeishuChatThreadStore(json, dataDirectory);
        assertThatThrownBy(() -> store.find("c", "chat")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.getOrCreate("c", "chat")).isInstanceOf(IllegalStateException.class);
        assertThat(Files.readString(file)).isEqualTo(corrupt);
    }

    @Test
    void nonObjectMappingsFailsClosed() throws Exception {
        Path file = dataDirectory.resolve("channels").resolve("feishu-thread-map.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"mappings\":[]}");
        FeishuChatThreadStore store = new FeishuChatThreadStore(json, dataDirectory);
        assertThatThrownBy(() -> store.find("c", "chat")).isInstanceOf(IllegalStateException.class);
    }

    // ---- FT-14 -----------------------------------------------------------------------------

    @Test
    void sameAliasIdInDifferentScopesDoesNotCrossThreads() throws Exception {
        FeishuChatThreadStore store = new FeishuChatThreadStore(json, dataDirectory);
        store.bindAliasIfAbsent("conn-1", "chat", "root", "thread-1");
        store.bindAliasIfAbsent("conn-2", "chat", "root", "thread-2");
        store.bindAliasIfAbsent("conn-1", "other-chat", "root", "thread-3");
        assertThat(store.findAlias("conn-1", "chat", "root")).contains("thread-1");
        assertThat(store.findAlias("conn-2", "chat", "root")).contains("thread-2");
        assertThat(store.findAlias("conn-1", "other-chat", "root")).contains("thread-3");
    }

    // ---- harness ---------------------------------------------------------------------------

    private static FeishuChannelWorker worker(Harness harness) {
        return harness.worker();
    }

    private static String lastReply(Harness harness) {
        return harness.transport().replies().getLast().text();
    }

    private String connectionId(Harness harness) {
        return harness.connections()
                .findConnectionByExternalIdentity("feishu", "ou_owner", "oc_chat")
                .orElseThrow()
                .path("id")
                .asText("");
    }

    private FeishuInboundMessage p2p(Harness harness, String messageId, String text) {
        return new FeishuInboundMessage("oc_chat", messageId, "ou_owner", text, "p2p");
    }

    private FeishuInboundMessage reply(
            Harness harness, String messageId, String text, String parentId, String rootId, String feishuThreadId) {
        return new FeishuInboundMessage(
                "oc_chat", messageId, "ou_owner", text, "p2p", "text", List.of(), null,
                rootId, parentId, feishuThreadId);
    }

    private FeishuInboundMessage group(
            Harness harness, String chatId, String messageId, String text, String parentId, String rootId,
            String feishuThreadId) {
        return new FeishuInboundMessage(
                chatId, messageId, "ou_owner", text, "group", "text", List.of(), null,
                rootId, parentId, feishuThreadId);
    }

    private static FeishuPendingInput approval(String requestId) {
        return new FeishuPendingInput(
                "haifa_interaction",
                requestId,
                "risk_confirmation",
                "single_choice",
                "是否允许写入？",
                "",
                "是否允许写入？",
                List.of(new FeishuPendingInput.Option("approve", "Approve", "approve")),
                false);
    }

    private void bindGroup(Harness harness, String chatId) throws Exception {
        String code = ChannelConnectionStore.newBindingCode();
        harness.connections()
                .createOauthStateWithinCap(
                        "owner",
                        "feishu",
                        code,
                        java.time.Instant.now().plusSeconds(60),
                        ChannelConnectionStore.MAX_PENDING_CONNECT_CODES_PER_PROVIDER);
        harness.connections().bindFeishuFromConnectCode(code, "ou_owner", chatId, Map.of());
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
        return new Harness(worker, transportRef.get(), bridge, connections, threads);
    }

    private record Harness(
            FeishuChannelWorker worker,
            RecordingFeishuTransport transport,
            ScriptedBridge bridge,
            ChannelConnectionStore connections,
            FeishuChatThreadStore threads) {}

    private static final class ScriptedBridge implements FeishuAgentBridge {
        private final List<String> turnThreads = new CopyOnWriteArrayList<>();
        private final List<String> steerThreads = new CopyOnWriteArrayList<>();
        private final List<String> createdThreads = new CopyOnWriteArrayList<>();
        private volatile FeishuPendingInput pending;
        private volatile boolean activeRun;
        private volatile CountDownLatch blockEnter;
        private volatile CountDownLatch blockRelease;

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
            if (options != null && options.steer()) {
                steerThreads.add(threadId);
                return new TurnResult(threadId, "steered");
            }
            turnThreads.add(threadId);
            CountDownLatch release = blockRelease;
            if (release != null && "long".equals(userText)) {
                CountDownLatch enter = blockEnter;
                if (enter != null) {
                    enter.countDown();
                }
                release.await(3, TimeUnit.SECONDS);
            }
            pending = null;
            return new TurnResult(threadId, "continued:" + userText);
        }

        @Override
        public Optional<FeishuPendingInput> currentPending(String ownerUserId, String threadId) {
            return Optional.ofNullable(pending);
        }

        @Override
        public boolean cancelActiveRun(String ownerUserId, String threadId) {
            activeRun = false;
            return true;
        }

        @Override
        public boolean hasActiveRun(String ownerUserId, String threadId) {
            return activeRun;
        }

        @Override
        public void createThread(String ownerUserId, String threadId, Map<String, Object> channelMetadata) {
            createdThreads.add(threadId);
        }
    }
}

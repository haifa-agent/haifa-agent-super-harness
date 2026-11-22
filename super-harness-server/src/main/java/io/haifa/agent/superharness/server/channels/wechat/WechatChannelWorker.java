package io.haifa.agent.superharness.server.channels.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.server.config.ChannelConnectionStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;

/** One outbound iLink poller. The conversation service remains the owner of Threads and Runs. */
public final class WechatChannelWorker implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(WechatChannelWorker.class.getName());
    private static final int RECENT_MESSAGES = 512;

    @FunctionalInterface
    public interface TextTurn {
        String run(String owner, String threadId, String text) throws Exception;
    }

    private final ObjectMapper json;
    private final WechatTransport transport;
    private final ChannelConnectionStore connections;
    private final TextTurn turn;
    private final Path checkpoint;
    private volatile Poller current;

    public WechatChannelWorker(ObjectMapper json, WechatTransport transport, ChannelConnectionStore connections,
            Path dataRoot, TextTurn turn) {
        this.json = json;
        this.transport = transport;
        this.connections = connections;
        this.turn = turn;
        this.checkpoint = dataRoot.resolve("channels").resolve("wechat-poll.json");
    }

    public synchronized void start(ObjectNode runtime) {
        String token = runtime.path("bot_token").asText();
        if (token.isBlank()) {
            throw new IllegalArgumentException("WeChat Bot token is required");
        }
        URI origin = WechatTransport.origin(
                runtime.path("base_url").asText(WechatTransport.DEFAULT_ORIGIN.toString()));
        String bot = runtime.path("bot_id").asText();
        String identity = bot.isBlank() ? "manual-" + uuid(token) : bot;
        Poller poller = current;
        if (poller != null && poller.token.equals(token) && poller.identity.equals(identity)
                && poller.origin.equals(origin) && poller.thread.isAlive()) {
            return;
        }
        stop();
        current = new Poller(origin, token, identity);
        current.thread.start();
    }

    public boolean online() {
        Poller poller = current;
        return poller != null && poller.online;
    }

    public synchronized void stop() {
        Poller old = current;
        current = null;
        if (old != null) {
            old.thread.interrupt();
        }
    }

    @Override
    public void close() {
        stop();
    }

    private final class Poller implements Runnable {
        private final URI origin;
        private final String token;
        private final String identity;
        private final Thread thread;
        private final LinkedHashSet<String> recent = new LinkedHashSet<>();
        private String cursor = "";
        private volatile boolean online;

        private Poller(URI origin, String token, String identity) {
            this.origin = origin;
            this.token = token;
            this.identity = identity;
            this.thread = Thread.ofVirtual().name("wechat-ilink").unstarted(this);
        }

        @Override
        public void run() {
            restore();
            try {
                notifyStart();
                while (current == this && !Thread.currentThread().isInterrupted()) {
                    try {
                        poll();
                    } catch (HttpTimeoutException normalLongPoll) {
                        // getUpdates intentionally keeps HTTP open while waiting for messages.
                    } catch (IOException failure) {
                        online = false;
                        LOG.log(System.Logger.Level.WARNING, "WeChat update poll failed");
                        Thread.sleep(Duration.ofSeconds(5));
                    }
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            } finally {
                online = false;
            }
        }

        private void notifyStart() throws InterruptedException {
            try {
                transport.request(origin, "msg/notifystart", "", WechatTransport.authenticatedBody(json),
                        token, Duration.ofSeconds(12));
            } catch (IOException failure) {
                LOG.log(System.Logger.Level.WARNING, "WeChat start notification failed");
            }
        }

        private void poll() throws IOException, InterruptedException {
            ObjectNode request = WechatTransport.authenticatedBody(json);
            request.put("get_updates_buf", cursor);
            JsonNode result = transport.request(origin, "getupdates", "", request, token, Duration.ofSeconds(38));
            if (!WechatTransport.accepted(result)) {
                if (result.path("ret").asInt() == -14 || result.path("errcode").asInt() == -14) {
                    online = false;
                    LOG.log(System.Logger.Level.WARNING, "WeChat session expired; reconnect the channel if needed");
                    Thread.sleep(Duration.ofHours(1));
                    return;
                }
                throw new IOException("WeChat rejected updates request");
            }
            online = true;
            for (JsonNode message : result.path("msgs")) {
                if (current != this) {
                    return;
                }
                handle(message);
            }
            String next = result.path("get_updates_buf").asText();
            if (!next.isBlank() && !next.equals(cursor)) {
                cursor = next;
                save(this);
            }
            if (result.path("msgs").isEmpty()) {
                Thread.sleep(Duration.ofMillis(250));
            }
        }

        private void handle(JsonNode message) throws IOException, InterruptedException {
            String group = message.path("group_id").asText("");
            String sender = message.path("from_user_id").asText();
            String context = message.path("context_token").asText();
            if (message.path("message_type").asInt() == 2 || (!group.isBlank() && !"0".equals(group))
                    || sender.isBlank() || context.isBlank()) {
                return;
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode item : message.path("item_list")) {
                if (item.path("type").asInt() == 1) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(item.path("text_item").path("text").asText());
                }
            }
            String prompt = text.toString().strip();
            if (prompt.isEmpty()) {
                return;
            }
            String key = message.path("message_id").asText();
            if (key.isBlank()) {
                key = message.path("client_id").asText();
            }
            if (key.isBlank()) {
                String sequence = message.path("seq").asText();
                key = sequence.isBlank() ? "" : "seq:" + sequence;
            }
            if (key.isBlank() || recent.contains(key)) {
                return;
            }
            String reply = route(sender, prompt);
            recent.add(key);
            if (recent.size() > RECENT_MESSAGES) {
                recent.removeFirst();
            }
            save(this);
            if (reply != null && !reply.isBlank() && current == this) {
                try {
                    transport.send(json, origin, token, sender, context, boundedReply(reply));
                } catch (IOException failure) {
                    // An ambiguous transport error can mean WeChat already delivered the message.
                    LOG.log(System.Logger.Level.WARNING, "WeChat reply delivery failed");
                }
            }
        }

        private String route(String sender, String prompt) throws InterruptedException {
            if (prompt.startsWith("/connect ")) {
                synchronized (WechatChannelWorker.this) {
                    if (current != this) {
                        return null;
                    }
                    return connections.consumeOauthState("wechat", prompt.substring("/connect ".length()).strip())
                            .map(owner -> {
                                connections.upsertConnection(owner, "wechat", sender, null, identity,
                                        null, Map.of(), "connected");
                                return "微信已连接，可以开始对话。";
                            })
                            .orElse("连接码无效或已过期，请在网页重新获取。");
                }
            }
            ObjectNode connection = connections.findConnectionByExternalIdentity("wechat", sender, identity)
                    .orElse(null);
            if (connection == null) {
                return null;
            }
            String owner = connection.path("owner_user_id").asText();
            String id = connection.path("id").asText();
            if (owner.isBlank() || id.isBlank() || current != this) {
                return null;
            }
            // The stable, owner-scoped binding is the conversation key. An iLink context token is
            // per delivery and may change between turns; it cannot be a Haifa Thread identifier.
            String threadId = uuid("wechat-thread:" + id);
            try {
                return turn.run(owner, threadId, prompt);
            } catch (InterruptedException stopped) {
                throw stopped;
            } catch (Exception failure) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("WeChat conversation interrupted");
                }
                LOG.log(System.Logger.Level.WARNING, "WeChat conversation turn failed");
                return "这条消息处理失败，请重试。";
            }
        }

        private void restore() {
            if (Files.notExists(checkpoint)) {
                return;
            }
            synchronized (WechatChannelWorker.this) {
                if (current != this) {
                    return;
                }
                try {
                    JsonNode state = json.readTree(checkpoint.toFile());
                    if (state != null && identity.equals(state.path("bot").asText())) {
                        cursor = state.path("cursor").asText();
                        for (JsonNode item : state.path("recent")) {
                            recent.add(item.asText());
                            if (recent.size() >= RECENT_MESSAGES) {
                                break;
                            }
                        }
                    }
                } catch (IOException failure) {
                    LOG.log(System.Logger.Level.WARNING, "Could not read WeChat polling checkpoint");
                }
            }
        }
    }

    private synchronized void save(Poller poller) throws IOException {
        if (current != poller) {
            return;
        }
        Files.createDirectories(checkpoint.getParent());
        Path staging = Files.createTempFile(checkpoint.getParent(), "wechat-", ".tmp");
        try {
            ObjectNode state = json.createObjectNode().put("bot", poller.identity).put("cursor", poller.cursor);
            var recent = state.putArray("recent");
            poller.recent.forEach(recent::add);
            json.writeValue(staging.toFile(), state);
            Files.move(staging, checkpoint, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    private static String boundedReply(String text) {
        return text.codePointCount(0, text.length()) <= 3800 ? text
                : text.substring(0, text.offsetByCodePoints(0, 3750)) + "\n（完整回复见网页会话）";
    }

    private static String uuid(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }
}

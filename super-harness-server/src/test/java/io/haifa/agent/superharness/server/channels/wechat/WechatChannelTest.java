package io.haifa.agent.superharness.server.channels.wechat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.server.config.ChannelConnectionStore;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WechatChannelTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path dataRoot;

    @Test
    void qrSessionOwnsPinAndNeverReturnsBotCredentials() throws Exception {
        List<String> operations = new ArrayList<>();
        WechatTransport transport = (origin, path, query, body, token, timeout) -> {
            operations.add(path);
            return switch (operations.size()) {
                case 1 -> {
                    assertEquals("?bot_type=3", query);
                    assertTrue(body.path("local_token_list").isEmpty());
                    yield json.createObjectNode().put("qrcode", "private-scan-id")
                            .put("qrcode_img_content", "https://weixin.qq.com/test");
                }
                case 2 -> {
                    assertEquals(Duration.ofSeconds(35), timeout);
                    throw new HttpTimeoutException("iLink long poll idle");
                }
                case 3 -> json.createObjectNode().put("status", "need_verifycode");
                case 4 -> {
                    assertTrue(query.contains("&verify_code=1234"));
                    yield json.createObjectNode().put("status", "confirmed")
                            .put("bot_token", "fixture-bot-token").put("ilink_bot_id", "bot-a");
                }
                default -> throw new AssertionError("Unexpected iLink request");
            };
        };
        WechatQrLogin login = new WechatQrLogin(json, transport);
        ObjectNode started = login.start("admin", null);
        String id = started.path("id").asText();
        assertEquals("pending", started.path("status").asText());
        assertNull(login.poll("other", id, "", runtime -> {
            throw new AssertionError("Other user accessed session");
        }));
        ObjectNode idle = login.poll("admin", id, "", ignored -> null);
        assertEquals("pending", idle.path("status").asText());
        assertTrue(idle.path("error").isNull());
        assertEquals("verification_required", login.poll("admin", id, "", ignored -> null).path("status").asText());
        JsonNode confirmed = login.poll("admin", id, "1234", runtime -> {
            assertEquals("fixture-bot-token", runtime.path("bot_token").asText());
            return json.createObjectNode().put("provider", "wechat").put("configured", true);
        });
        assertEquals("confirmed", confirmed.path("status").asText());
        assertEquals("wechat", confirmed.path("provider").path("provider").asText());
        assertTrue(confirmed.findValues("bot_token").isEmpty());
        assertEquals(4, operations.size());
    }

    @Test
    void webTimeoutKeepsTheInFlightWechatPollAndAppliesItsResultOnce() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch confirmedByWechat = new CountDownLatch(1);
        AtomicInteger upstreamPolls = new AtomicInteger();
        AtomicInteger activations = new AtomicInteger();
        WechatTransport transport = (origin, path, query, body, token, timeout) -> {
            if ("get_bot_qrcode".equals(path)) {
                return json.createObjectNode().put("qrcode", "private-scan-id")
                        .put("qrcode_img_content", "https://weixin.qq.com/test");
            }
            upstreamPolls.incrementAndGet();
            started.countDown();
            if (!confirmedByWechat.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Test never released the upstream poll");
            }
            return json.createObjectNode().put("status", "confirmed")
                    .put("bot_token", "fixture-bot-token").put("ilink_bot_id", "bot-a");
        };
        var login = new WechatQrLogin(json, transport, Duration.ofMillis(100));
        String id = login.start("admin", null).path("id").asText();
        try {
            assertEquals("pending", login.poll("admin", id, "", ignored -> null).path("status").asText());
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals("pending", login.poll("admin", id, "", ignored -> null).path("status").asText());
            assertEquals(1, upstreamPolls.get());
            confirmedByWechat.countDown();
            Instant deadline = Instant.now().plusSeconds(3);
            JsonNode result;
            do {
                result = login.poll("admin", id, "", runtime -> {
                    activations.incrementAndGet();
                    return json.createObjectNode().put("configured", true);
                });
            } while ("pending".equals(result.path("status").asText()) && Instant.now().isBefore(deadline));
            assertEquals("confirmed", result.path("status").asText());
            assertTrue(result.path("provider").path("configured").asBoolean());
            assertEquals("confirmed", login.poll("admin", id, "", ignored -> {
                throw new AssertionError("Confirmed QR activated again");
            }).path("status").asText());
            assertEquals(1, upstreamPolls.get());
            assertEquals(1, activations.get());
        } finally {
            login.cancel("admin", id);
        }
    }

    @Test
    void cancellingAnInFlightQrInterruptsTheUpstreamPoll() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        WechatTransport transport = (origin, path, query, body, token, timeout) -> {
            if ("get_bot_qrcode".equals(path)) {
                return json.createObjectNode().put("qrcode", "private-scan-id")
                        .put("qrcode_img_content", "https://weixin.qq.com/test");
            }
            started.countDown();
            try {
                Thread.sleep(Duration.ofSeconds(5));
                throw new AssertionError("Cancel never interrupted the upstream poll");
            } catch (InterruptedException cancelled) {
                interrupted.countDown();
                throw cancelled;
            }
        };
        var login = new WechatQrLogin(json, transport, Duration.ofMillis(100));
        String id = login.start("admin", null).path("id").asText();
        assertEquals("pending", login.poll("admin", id, "", ignored -> null).path("status").asText());
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertTrue(login.cancel("admin", id));
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertNull(login.poll("admin", id, "", ignored -> null));
    }

    @Test
    void pairedSenderReusesThreadAcrossDifferentContextTokensAndIgnoresReplay() throws Exception {
        ChannelConnectionStore connections = new ChannelConnectionStore(json, dataRoot);
        assertTrue(connections.createOauthStateWithinCap("owner", "wechat", "ABCD-1234",
                Instant.now().plusSeconds(60), 5));
        assertTrue(connections.createOauthStateWithinCap("other-owner", "wechat", "EFGH-5678",
                Instant.now().plusSeconds(60), 5));
        var delivered = new LinkedBlockingQueue<JsonNode>();
        var requests = new LinkedBlockingQueue<ObjectNode>();
        var turns = new LinkedBlockingQueue<List<String>>();
        WechatTransport transport = (origin, path, query, body, token, timeout) -> switch (path) {
            case "getupdates" -> requests.take();
            case "sendmessage" -> {
                delivered.add(body.path("msg"));
                yield json.createObjectNode().put("message_id", "ack-without-ret");
            }
            case "msg/notifystart" -> json.createObjectNode();
            default -> throw new AssertionError("Unrecognized operation " + path);
        };
        try (var worker = new WechatChannelWorker(json, transport, connections, dataRoot,
                (owner, threadId, text) -> {
                    turns.add(List.of(owner, threadId, text));
                    return "回答 " + text;
                })) {
            worker.start(json.createObjectNode().put("bot_token", "fixture-bot-token").put("bot_id", "bot-a"));
            ObjectNode batch = json.createObjectNode().put("ret", 0).put("get_updates_buf", "cursor-a");
            batch.putArray("msgs")
                    .add(message(99, "me", "ignored", "unpaired"))
                    .add(message(1, "me", "binding", "/connect ABCD-1234"))
                    .add(message(2, "me", "ctx-1", "first"))
                    .add(message(2, "me", "ctx-1", "first"))
                    .add(message(3, "me", "ctx-2", "second"))
                    .add(message(4, "other", "binding-other", "/connect EFGH-5678"))
                    .add(message(5, "other", "ctx-other", "other"));
            requests.add(batch);
            assertEquals("binding", delivered.poll(5, TimeUnit.SECONDS).path("context_token").asText());
            assertEquals("ctx-1", delivered.poll(5, TimeUnit.SECONDS).path("context_token").asText());
            assertEquals("ctx-2", delivered.poll(5, TimeUnit.SECONDS).path("context_token").asText());
            assertEquals("binding-other", delivered.poll(5, TimeUnit.SECONDS).path("context_token").asText());
            assertEquals("ctx-other", delivered.poll(5, TimeUnit.SECONDS).path("context_token").asText());
            List<String> first = turns.remove();
            List<String> second = turns.remove();
            List<String> other = turns.remove();
            assertEquals(List.of("owner", first.get(1), "first"), first);
            assertEquals(List.of("owner", first.get(1), "second"), second);
            assertEquals("other-owner", other.getFirst());
            assertNotEquals(first.get(1), other.get(1));
            assertTrue(turns.isEmpty());
        }
    }

    private ObjectNode message(int id, String from, String context, String text) {
        ObjectNode message = json.createObjectNode().put("message_id", id).put("message_type", 1)
                .put("from_user_id", from).put("context_token", context);
        message.putArray("item_list").addObject().put("type", 1).putObject("text_item").put("text", text);
        return message;
    }
}

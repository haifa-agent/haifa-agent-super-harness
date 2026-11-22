package io.haifa.agent.superharness.server.channels.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/** One active administrator QR login for the single configured WeChat runtime. */
public final class WechatQrLogin {
    private static final System.Logger LOG = System.getLogger(WechatQrLogin.class.getName());
    private static final Duration TTL = Duration.ofSeconds(90);
    // iLink can hold a status request for about 31s, longer than the Next proxy's 30s.
    private static final Duration ILINK_POLL_TIMEOUT = Duration.ofSeconds(35);
    private static final Duration WEB_POLL_TIMEOUT = Duration.ofSeconds(18);
    private final ObjectMapper json;
    private final WechatTransport transport;
    private final Duration webPollTimeout;
    private Session active;

    public WechatQrLogin(ObjectMapper json, WechatTransport transport) {
        this(json, transport, WEB_POLL_TIMEOUT);
    }

    WechatQrLogin(ObjectMapper json, WechatTransport transport, Duration webPollTimeout) {
        this.json = json;
        this.transport = transport;
        this.webPollTimeout = webPollTimeout;
    }

    public synchronized ObjectNode start(String owner, ObjectNode runtime) throws IOException, InterruptedException {
        ObjectNode body = json.createObjectNode();
        var tokens = body.putArray("local_token_list");
        String token = runtime == null ? "" : runtime.path("bot_token").asText();
        if (!token.isBlank()) {
            tokens.add(token);
        }
        JsonNode data = transport.request(WechatTransport.DEFAULT_ORIGIN, "get_bot_qrcode", "?bot_type=3",
                body, null, Duration.ofSeconds(12));
        String qr = data.path("qrcode").asText();
        String content = data.path("qrcode_img_content").asText();
        if (!WechatTransport.accepted(data) || qr.isBlank() || !content.startsWith("https://")) {
            throw new IOException("Invalid WeChat QR response");
        }
        invalidate();
        active = new Session(UUID.randomUUID().toString(), owner, qr, content);
        return response(active);
    }

    public ObjectNode poll(String owner, String id, String pin, Function<ObjectNode, ObjectNode> activate)
            throws InterruptedException {
        Session session;
        Poll attempt;
        synchronized (this) {
            session = find(owner, id);
            if (session == null) {
                return null;
            }
            expire(session);
            if (session.terminal()
                    || ("verification_required".equals(session.status) && pin.isEmpty()
                            && session.poll == null && !"network".equals(session.error))) {
                return response(session);
            }
            if (session.poll == null) {
                session.poll = beginPoll(session, pin);
            }
            attempt = session.poll;
        }
        JsonNode data;
        Throwable failure = null;
        try {
            data = attempt.result().get(webPollTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | CancellationException unfinished) {
            synchronized (this) {
                if (active != session) {
                    return null;
                }
                expire(session);
                return response(session);
            }
        } catch (ExecutionException failed) {
            data = null;
            failure = failed.getCause();
            if (failure instanceof Error fatal) {
                throw fatal;
            }
            if (!(failure instanceof IOException) && !(failure instanceof InterruptedException)) {
                throw new IllegalStateException("Unexpected WeChat status poll failure", failure);
            }
        }
        synchronized (this) {
            if (active != session) {
                return null;
            }
            expire(session);
            if (!session.terminal() && session.poll == attempt) {
                session.poll = null;
                if (failure == null) {
                    String before = session.status;
                    apply(session, data, attempt.pin(), activate);
                    if (!before.equals(session.status)) {
                        LOG.log(System.Logger.Level.INFO, "WeChat QR login state: {0}", session.status);
                    }
                } else {
                    if (!attempt.pin().isEmpty()) {
                        session.status = "verification_required";
                    }
                    session.error = failure instanceof HttpTimeoutException && attempt.pin().isEmpty()
                            ? null : "network";
                    LOG.log(System.Logger.Level.WARNING, "WeChat QR status poll failed: {0}",
                            failure.getClass().getSimpleName());
                }
            }
            return response(session);
        }
    }

    private Poll beginPoll(Session session, String pin) {
        URI origin = session.origin;
        String query = "?qrcode=" + WechatTransport.queryValue(session.qr)
                + (pin.isEmpty() ? "" : "&verify_code=" + WechatTransport.queryValue(pin));
        if (!pin.isEmpty()) {
            // While checking a submitted code, the existing UI continues polling as scanned.
            session.status = "scanned";
            session.error = null;
        }
        FutureTask<JsonNode> result = new FutureTask<>(() ->
                transport.request(origin, "get_qrcode_status", query, null, null, ILINK_POLL_TIMEOUT));
        Thread.ofVirtual().name("wechat-qr-status").start(result);
        return new Poll(pin, result);
    }

    private void apply(Session s, JsonNode data, String pin, Function<ObjectNode, ObjectNode> activate) {
        s.error = null;
        switch (data.path("status").asText()) {
            case "wait" -> s.status = "pending";
            case "scaned" -> s.status = "scanned";
            case "scaned_but_redirect" -> {
                try {
                    s.origin = WechatTransport.origin(data.path("redirect_host").asText());
                    s.status = "scanned";
                } catch (IllegalArgumentException invalid) {
                    fail(s, "invalid_response");
                }
            }
            case "need_verifycode" -> {
                s.status = "verification_required";
                if (!pin.isEmpty()) {
                    s.error = "verification_rejected";
                }
            }
            case "expired" -> s.status = "expired";
            case "verify_code_blocked" -> fail(s, "verification_blocked");
            case "binded_redirect" -> fail(s, "already_bound");
            case "confirmed" -> {
                String token = data.path("bot_token").asText();
                String botId = data.path("ilink_bot_id").asText();
                if (token.isBlank() || botId.isBlank()) {
                    fail(s, "invalid_response");
                    return;
                }
                try {
                    URI origin = WechatTransport.origin(data.path("baseurl").asText(s.origin.toString()));
                    ObjectNode runtime = json.createObjectNode()
                            .put("enabled", true)
                            .put("bot_token", token)
                            .put("bot_id", botId)
                            .put("base_url", origin.toString());
                    s.provider = activate.apply(runtime);
                    s.status = "confirmed";
                } catch (IllegalArgumentException invalid) {
                    fail(s, "invalid_response");
                }
            }
            default -> fail(s, "invalid_response");
        }
    }

    public synchronized boolean cancel(String owner, String id) {
        if (find(owner, id) == null) {
            return false;
        }
        invalidate();
        return true;
    }

    public synchronized void invalidate() {
        if (active != null) {
            active.cancelPoll();
        }
        active = null;
    }

    private Session find(String owner, String id) {
        return active != null && active.owner.equals(owner) && active.id.equals(id) ? active : null;
    }

    private static void expire(Session s) {
        if (!s.terminal() && !Instant.now().isBefore(s.expires)) {
            s.status = "expired";
            s.error = null;
            s.cancelPoll();
        }
    }

    private static void fail(Session s, String error) {
        s.status = "failed";
        s.error = error;
    }

    private ObjectNode response(Session s) {
        ObjectNode wire = json.createObjectNode()
                .put("id", s.id)
                .put("status", s.status)
                .put("qrcode_content", s.content)
                .put("expires_in", Math.max(0, Duration.between(Instant.now(), s.expires).toSeconds()));
        wire.put("error", s.error);
        wire.set("provider", s.provider);
        return wire;
    }

    private record Poll(String pin, FutureTask<JsonNode> result) {}

    private static final class Session {
        private final String id;
        private final String owner;
        private final String qr;
        private final String content;
        private final Instant expires = Instant.now().plus(TTL);
        private URI origin = WechatTransport.DEFAULT_ORIGIN;
        private String status = "pending";
        private String error;
        private Poll poll;
        private ObjectNode provider;

        private Session(String id, String owner, String qr, String content) {
            this.id = id;
            this.owner = owner;
            this.qr = qr;
            this.content = content;
        }

        private boolean terminal() {
            return "confirmed".equals(status) || "expired".equals(status) || "failed".equals(status);
        }

        private void cancelPoll() {
            if (poll != null) {
                poll.result().cancel(true);
                poll = null;
            }
        }
    }
}

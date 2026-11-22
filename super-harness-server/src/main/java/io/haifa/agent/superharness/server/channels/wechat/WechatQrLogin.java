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
import java.util.function.Function;

/** One active administrator QR login for the single configured WeChat runtime. */
public final class WechatQrLogin {
    private static final Duration TTL = Duration.ofSeconds(90);
    // The frozen UI's Next proxy allows 30 seconds for each HTTP request.
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(18);
    private final ObjectMapper json;
    private final WechatTransport transport;
    private Session active;

    public WechatQrLogin(ObjectMapper json, WechatTransport transport) {
        this.json = json;
        this.transport = transport;
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
        active = new Session(UUID.randomUUID().toString(), owner, qr, content);
        return response(active);
    }

    public ObjectNode poll(String owner, String id, String pin, Function<ObjectNode, ObjectNode> activate)
            throws InterruptedException {
        Session session;
        URI origin;
        synchronized (this) {
            session = find(owner, id);
            if (session == null) {
                return null;
            }
            expire(session);
            if (session.polling || session.terminal()
                    || ("verification_required".equals(session.status) && pin.isEmpty()
                            && !"network".equals(session.error))) {
                return response(session);
            }
            session.polling = true;
            origin = session.origin;
        }
        try {
            String query = "?qrcode=" + WechatTransport.queryValue(session.qr);
            if (!pin.isEmpty()) {
                query += "&verify_code=" + WechatTransport.queryValue(pin);
            }
            JsonNode data = transport.request(origin, "get_qrcode_status", query, null, null, POLL_TIMEOUT);
            synchronized (this) {
                if (active != session) {
                    return null;
                }
                expire(session);
                if (!session.terminal()) {
                    apply(session, data, pin, activate);
                }
                return response(session);
            }
        } catch (IOException failure) {
            synchronized (this) {
                if (active != session) {
                    return null;
                }
                expire(session);
                if (!session.terminal()) {
                    session.error = failure instanceof HttpTimeoutException && pin.isEmpty() ? null : "network";
                }
                return response(session);
            }
        } finally {
            synchronized (this) {
                session.polling = false;
            }
        }
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
        active = null;
        return true;
    }

    public synchronized void invalidate() {
        active = null;
    }

    private Session find(String owner, String id) {
        return active != null && active.owner.equals(owner) && active.id.equals(id) ? active : null;
    }

    private static void expire(Session s) {
        if (!s.terminal() && !Instant.now().isBefore(s.expires)) {
            s.status = "expired";
            s.error = null;
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

    private static final class Session {
        private final String id;
        private final String owner;
        private final String qr;
        private final String content;
        private final Instant expires = Instant.now().plus(TTL);
        private URI origin = WechatTransport.DEFAULT_ORIGIN;
        private String status = "pending";
        private String error;
        private boolean polling;
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
    }
}

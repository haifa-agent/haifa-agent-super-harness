package io.haifa.agent.superharness.server.channels.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** The iLink HTTP operations used by the WeChat text channel. */
public interface WechatTransport {
    URI DEFAULT_ORIGIN = URI.create("https://ilinkai.weixin.qq.com");

    JsonNode request(URI origin, String path, String query, ObjectNode body, String token, Duration timeout)
            throws IOException, InterruptedException;

    static URI origin(String value) {
        URI result = URI.create(value.contains("://") ? value : "https://" + value);
        String host = result.getHost();
        if (!"https".equals(result.getScheme()) || host == null || !host.endsWith(".weixin.qq.com")
                || result.getUserInfo() != null || result.getRawQuery() != null || result.getRawFragment() != null
                || (result.getPort() != -1 && result.getPort() != 443)
                || !(result.getPath().isEmpty() || "/".equals(result.getPath()))) {
            throw new IllegalArgumentException("Unexpected WeChat API origin");
        }
        return URI.create("https://" + host);
    }

    static String queryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static ObjectNode authenticatedBody(ObjectMapper json) {
        ObjectNode body = json.createObjectNode();
        body.putObject("base_info")
                .put("channel_version", "2.4.10")
                .put("bot_agent", "Haifa-Super-Harness/1.0.0");
        return body;
    }

    static boolean accepted(JsonNode data) {
        return zeroOrAbsent(data.get("ret")) && zeroOrAbsent(data.get("errcode"));
    }

    private static boolean zeroOrAbsent(JsonNode code) {
        return code == null || code.isNull() || (code.isIntegralNumber() && code.longValue() == 0);
    }

    default void send(ObjectMapper json, URI origin, String token, String recipient, String context, String text)
            throws IOException, InterruptedException {
        ObjectNode body = authenticatedBody(json);
        ObjectNode message = body.putObject("msg");
        message.put("from_user_id", "");
        message.put("to_user_id", recipient);
        message.put("client_id", "haifa-" + UUID.randomUUID());
        message.put("message_type", 2);
        message.put("message_state", 2);
        message.put("context_token", context);
        message.putArray("item_list").addObject().put("type", 1).putObject("text_item").put("text", text);
        JsonNode result = request(origin, "sendmessage", "", body, token, Duration.ofSeconds(15));
        if (!accepted(result)) {
            throw new IOException("WeChat did not accept the reply");
        }
    }

    static WechatTransport http(ObjectMapper json) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        return (origin, path, query, body, token, timeout) -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(
                            origin.resolve("/ilink/bot/" + path + query))
                    .timeout(timeout)
                    .header("iLink-App-Id", "bot")
                    .header("iLink-App-ClientVersion", Integer.toString(0x02040a));
            if (body == null) {
                request.GET();
            } else {
                String random = Integer.toUnsignedString(ThreadLocalRandom.current().nextInt());
                request.header("Content-Type", "application/json")
                        .header("AuthorizationType", "ilink_bot_token")
                        .header("X-WECHAT-UIN", Base64.getEncoder().encodeToString(
                                random.getBytes(StandardCharsets.UTF_8)))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
            }
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("WeChat " + path + " HTTP " + response.statusCode());
            }
            JsonNode result = json.readTree(response.body());
            if (result == null || !result.isObject()) {
                throw new IOException("Invalid WeChat " + path + " response");
            }
            return result;
        };
    }
}

package io.haifa.agent.superharness.server.integrations.lark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Official HTTPS implementation of {@link LarkAppRegistration}. */
public final class LarkHttpAppRegistration implements LarkAppRegistration {
    static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);
    static final Duration LATEST_VERSION_TIMEOUT = Duration.ofSeconds(10);
    static final String REGISTRATION_PATH = "/oauth/v1/app/registration";
    static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/" + LarkSkillCatalog.GITHUB_REPOSITORY + "/releases/latest";

    private final ObjectMapper json;
    private final HttpClient client;

    public LarkHttpAppRegistration(ObjectMapper json) {
        this(json, HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build());
    }

    LarkHttpAppRegistration(ObjectMapper json, HttpClient client) {
        this.json = json;
        this.client = client;
    }

    @Override
    public Begin begin(String brand) {
        // Frozen behaviour: the begin step always targets the Feishu accounts host; the tenant
        // brand only takes over if the poll response reports a Lark tenant.
        Map<String, String> form = new LinkedHashMap<>();
        form.put("action", "begin");
        form.put("archetype", "PersonalAgent");
        form.put("auth_method", "client_secret");
        form.put("request_user_info", "open_id tenant_brand");
        JsonNode response = postForm(accountsUrl("feishu"), form);
        String userCode = text(response, "user_code");
        String deviceCode = text(response, "device_code");
        if (userCode.isBlank() || deviceCode.isBlank()) {
            throw new IllegalArgumentException("Lark app registration did not return a user_code and device_code.");
        }
        return new Begin(userCode, deviceCode, intOrNull(response, "expires_in"), intOrNull(response, "interval"));
    }

    @Override
    public PollOutcome pollOnce(String deviceCode, String brand) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("action", "poll");
        form.put("device_code", deviceCode);
        JsonNode response = postForm(accountsUrl(brand), form);
        String error = text(response, "error");
        if (error.isEmpty() && !text(response, "client_id").isEmpty()) {
            return PollOutcome.approved(new Poll(
                    text(response, "client_id"), text(response, "client_secret"), tenantBrand(response)));
        }
        String description = text(response, "error_description");
        return switch (error) {
            case "authorization_pending" -> PollOutcome.pending();
            case "slow_down" -> PollOutcome.slowDown();
            default -> PollOutcome.error(error.isEmpty() ? "authorization_pending" : error,
                    description.isEmpty() ? "Lark app registration is still pending." : description);
        };
    }

    @Override
    public String latestCliVersion() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_RELEASE_API))
                .timeout(LATEST_VERSION_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "haifa-super-harness")
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return null;
            }
            JsonNode body = json.readTree(response.body());
            String tag = body == null ? "" : text(body, "tag_name");
            return LarkCliProcessGateway.normalizeVersionTag(tag);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    private JsonNode postForm(String url, Map<String, String> form) {
        StringBuilder encoded = new StringBuilder();
        form.forEach((key, value) -> {
            if (!encoded.isEmpty()) {
                encoded.append('&');
            }
            encoded.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "haifa-super-harness")
                .POST(HttpRequest.BodyPublishers.ofString(encoded.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Lark app registration request failed: InterruptedException");
        } catch (IOException failure) {
            throw new IllegalArgumentException("Lark app registration request failed: "
                    + failure.getClass().getSimpleName());
        }
        try {
            JsonNode parsed = json.readTree(response.body());
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalArgumentException("Lark app registration returned non-JSON response.");
            }
            return parsed;
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Lark app registration returned non-JSON response.", invalid);
        }
    }

    static String accountsUrl(String brand) {
        String host = "lark".equals(brand) ? "https://accounts.larksuite.com" : "https://accounts.feishu.cn";
        return host + REGISTRATION_PATH;
    }

    static String openUrl(String brand) {
        return "lark".equals(brand) ? "https://open.larksuite.com" : "https://open.feishu.cn";
    }

    private static String tenantBrand(JsonNode response) {
        JsonNode userInfo = response.get("user_info");
        if (userInfo == null || !userInfo.isObject()) {
            return null;
        }
        String brand = text(userInfo, "tenant_brand").toLowerCase(java.util.Locale.ROOT);
        return brand.equals("feishu") || brand.equals("lark") ? brand : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.canConvertToInt()) {
            return null;
        }
        return value.asInt();
    }
}

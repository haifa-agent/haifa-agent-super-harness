package io.haifa.agent.superharness.server.channels.feishu;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Bridges a personal Lark CLI authorization failure to the Feishu channel's own UI. */
final class FeishuLarkAuthorization {
    static final String SOURCE = "lark_authorization";
    static final String RESUME = "resume";
    static final String PUBLIC_WEB_URL_ENV = "HAIFA_SUPER_HARNESS_PUBLIC_WEB_URL";
    static final String CONTINUE_TEXT = "我已完成所需的飞书个人授权，请继续这个会话里刚才因权限不足而中断的任务。"
            + "先核对已完成的步骤，只重试未完成的操作；写操作按正常审批流程执行。";
    static final String NOT_AUTHORIZED = "所需的飞书个人权限还未生效。请先打开授权页，再点「继续原任务」。";
    static final String STALE = "这项任务已有后续处理，无法从旧授权卡继续。请在原对话查看最新进展。";
    private static final Pattern SCOPE = Pattern.compile("[a-z][a-z0-9_.-]*(?::[a-z0-9_.-]+){1,4}");
    private static final Pattern EXIT = Pattern.compile("Command exited \\(exit [1-9][0-9]*\\)");
    private static final Pattern LEGACY_LINK = Pattern.compile(
            "\\[([^\\]\\r\\n]+)]\\((?:(?:https?:/+)?/)?workspace/capabilities\\?tab=plugins&plugin=lark\\)");
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private FeishuLarkAuthorization() {}

    static List<String> missingScopes(String receipt) {
        if (receipt == null || receipt.length() > 8192) return List.of();
        int newline = receipt.indexOf('\n');
        if (newline < 0 || !EXIT.matcher(receipt.substring(0, newline).strip()).matches()) return List.of();
        try {
            JsonNode envelope = JSON.readTree(receipt.substring(newline + 1));
            if (envelope == null || !envelope.path("ok").isBoolean() || envelope.path("ok").asBoolean()) {
                return List.of();
            }
            JsonNode error = envelope.path("error");
            if (!"missing_scope".equals(error.path("subtype").asText())
                    || !"user".equals(error.path("identity").asText(envelope.path("identity").asText()))) {
                return List.of();
            }
            JsonNode missing = error.path("missing_scopes");
            if (!missing.isArray() || missing.size() > 24) return List.of();
            // Platform errors can list alternative scopes for an API. The CLI's local
            // preflight has no platform code and reports only the exact required scopes.
            if (error.hasNonNull("code") && missing.size() > 1) return List.of();
            var scopes = new LinkedHashSet<String>();
            for (JsonNode item : missing) {
                if (!item.isTextual() || !validScope(item.asText())) return List.of();
                scopes.add(item.asText());
            }
            return parseScopes(String.join(" ", scopes));
        } catch (IOException malformed) {
            return List.of();
        }
    }

    static List<String> parseScopes(String value) {
        if (value == null || value.isBlank() || value.length() > 1024) return List.of();
        List<String> scopes = List.of(value.split(" ", -1));
        if (scopes.size() > 24 || scopes.stream().anyMatch(scope -> !validScope(scope))) return List.of();
        return scopes.stream().distinct().sorted().toList();
    }

    private static boolean validScope(String scope) {
        return scope.length() <= 100 && SCOPE.matcher(scope).matches();
    }

    static String publicWebUrl(String value) {
        if (value == null || value.isBlank()) return "";
        URI uri = URI.create(value.strip());
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme())
                        && List.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())))) {
            throw new IllegalArgumentException(PUBLIC_WEB_URL_ENV + " must be an HTTPS web URL or a loopback HTTP URL");
        }
        return uri.toASCIIString().replaceFirst("/+$", "");
    }

    static String link(String publicWebUrl, List<String> scopes) {
        if (publicWebUrl.isBlank()) return "";
        return publicWebUrl + "/connect/feishu?lark_scope="
                + URLEncoder.encode(String.join(" ", scopes), StandardCharsets.UTF_8);
    }

    static String withoutLegacyLink(String text) {
        return LEGACY_LINK.matcher(text).replaceAll("$1（见授权卡片）");
    }

    static String requestId(String runId, String scopeRequest) {
        try {
            return runId + "." + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(scopeRequest.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}

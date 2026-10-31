package io.haifa.agent.superharness.server.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.haifa.agent.superharness.server.auth.AccessOwner;
import io.haifa.agent.superharness.server.integrations.lark.LarkCliUnavailableException;
import io.haifa.agent.superharness.server.integrations.lark.LarkFlowSupersededException;
import io.haifa.agent.superharness.server.integrations.lark.LarkIntegrationService;
import io.haifa.agent.superharness.server.integrations.lark.LarkTimeoutException;
import io.haifa.agent.superharness.server.integrations.lark.LarkUpstreamUnavailableException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Frozen DeerFlow {@code /api/integrations/lark/*} REST projection.
 *
 * <p>Wire shape and status mapping mirror
 * {@code backend/app/gateway/routers/integrations.py}. Only {@code POST /install} requires an
 * administrator; per-user app configuration and OAuth stay self-service. A non-admin caller of the
 * non-gated routes receives the status with host filesystem paths redacted, exactly as the frozen
 * router does. The handler owns no Runtime semantics: it maps HTTP to {@link LarkIntegrationService}.
 */
final class DeerFlowLarkIntegrationsApi implements HttpHandler {
    private static final System.Logger LOG = System.getLogger(DeerFlowLarkIntegrationsApi.class.getName());
    static final String ADMIN_REQUIRED = "Admin privileges required to install integrations.";
    private static final int MAX_BODY_BYTES = 8 * 1024;
    private static final String STATUS = "/api/integrations/lark/status";
    private static final String INSTALL = "/api/integrations/lark/install";
    private static final String CONFIG_START = "/api/integrations/lark/config/start";
    private static final String CONFIG_COMPLETE = "/api/integrations/lark/config/complete";
    private static final String CONFIG_CREDENTIALS = "/api/integrations/lark/config/credentials";
    private static final String AUTH_START = "/api/integrations/lark/auth/start";
    private static final String AUTH_COMPLETE = "/api/integrations/lark/auth/complete";

    private final ObjectMapper json;
    private final LarkIntegrationService service;

    DeerFlowLarkIntegrationsApi(ObjectMapper json, LarkIntegrationService service) {
        this.json = json;
        this.service = service;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String owner = AccessOwner.current();
        try {
            switch (path) {
                case STATUS -> {
                    if (!"GET".equals(method)) {
                        methodNotAllowed(exchange, "GET");
                        return;
                    }
                    send(exchange, 200, statusBody(owner));
                }
                case INSTALL -> {
                    if (!"POST".equals(method)) {
                        methodNotAllowed(exchange, "POST");
                        return;
                    }
                    if (!AccessOwner.admin()) {
                        send(exchange, 403, Map.of("detail", ADMIN_REQUIRED));
                        return;
                    }
                    LarkIntegrationService.InstallResult result = service.install(owner);
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("success", result.success());
                    response.put("installed_skills", result.installedSkills());
                    response.put("message", result.message());
                    response.put("status", status(result.status()));
                    send(exchange, 200, response);
                }
                case CONFIG_START -> {
                    if (!"POST".equals(method)) {
                        methodNotAllowed(exchange, "POST");
                        return;
                    }
                    JsonNode body = body(exchange);
                    String brand = optionalText(body, "brand", "feishu");
                    send(exchange, 200, configStart(service.startConfig(owner, brand)));
                }
                case CONFIG_COMPLETE -> {
                    if (!"POST".equals(method)) {
                        methodNotAllowed(exchange, "POST");
                        return;
                    }
                    JsonNode body = body(exchange);
                    String deviceCode = requiredText(body, "device_code");
                    String generation = generation(body);
                    String brand = optionalText(body, "brand", "feishu");
                    Integer interval = optionalInteger(body, "interval");
                    Integer expiresIn = optionalInteger(body, "expires_in");
                    send(exchange, 200, configComplete(
                            service.completeConfig(owner, deviceCode, generation, brand, interval, expiresIn),
                            AccessOwner.admin()));
                }
                case CONFIG_CREDENTIALS -> {
                    if (!"POST".equals(method)) {
                        methodNotAllowed(exchange, "POST");
                        return;
                    }
                    JsonNode body = body(exchange);
                    String appId = requiredText(body, "app_id");
                    String appSecret = requiredText(body, "app_secret");
                    String brand = optionalText(body, "brand", "feishu");
                    if (!brand.equals("feishu") && !brand.equals("lark")) {
                        throw new BodyFailure("brand must be feishu or lark");
                    }
                    send(exchange, 200, configComplete(
                            service.switchCredentials(owner, appId, appSecret, brand),
                            AccessOwner.admin()));
                }
                case AUTH_START -> {
                    if (!"POST".equals(method)) {
                        methodNotAllowed(exchange, "POST");
                        return;
                    }
                    JsonNode body = body(exchange);
                    boolean recommend = optionalBoolean(body, "recommend");
                    List<String> domains = stringList(body, "domains");
                    if (body.hasNonNull("scope") && !body.path("scope").isTextual()) {
                        throw new BodyFailure("scope must be a string or null");
                    }
                    String scope = body.path("scope").isTextual() ? body.path("scope").asText() : null;
                    String generation = body.hasNonNull("generation") ? generation(body) : null;
                    send(exchange, 200, authStart(
                            service.startAuth(owner, recommend, domains, scope, generation)));
                }
                case AUTH_COMPLETE -> {
                    if (!"POST".equals(method)) {
                        methodNotAllowed(exchange, "POST");
                        return;
                    }
                    JsonNode body = body(exchange);
                    String deviceCode = requiredText(body, "device_code");
                    String generation = generation(body);
                    int wait = body.has("wait_timeout_seconds")
                            ? body.path("wait_timeout_seconds").asInt(-1)
                            : 45;
                    if (!body.path("wait_timeout_seconds").isMissingNode()
                            && !body.path("wait_timeout_seconds").isIntegralNumber()) {
                        throw new BodyFailure("wait_timeout_seconds must be an integer");
                    }
                    if (wait < 5 || wait > 45) {
                        throw new BodyFailure("wait_timeout_seconds must be between 5 and 45");
                    }
                    send(exchange, 200, authComplete(
                            service.completeAuth(owner, deviceCode, generation, wait),
                            AccessOwner.admin()));
                }
                default -> send(exchange, 404, Map.of("detail", "Not Found"));
            }
        } catch (BodyFailure invalid) {
            send(exchange, 422, Map.of("detail", invalid.getMessage()));
        } catch (LarkFlowSupersededException superseded) {
            send(exchange, 409, Map.of("detail", superseded.getMessage()));
        } catch (LarkCliUnavailableException unavailable) {
            send(exchange, 404, Map.of("detail", message(unavailable.getMessage())));
        } catch (java.io.FileNotFoundException missing) {
            send(exchange, 404, Map.of("detail", message(missing.getMessage())));
        } catch (LarkTimeoutException timeout) {
            send(exchange, 504, Map.of("detail", message(timeout.getMessage())));
        } catch (LarkUpstreamUnavailableException unavailable) {
            // Upstream outage or missing egress, not a malformed client request.
            LOG.log(System.Logger.Level.WARNING, "Lark upstream is unavailable: " + path);
            send(exchange, 502, Map.of("detail", message(unavailable.getMessage())));
        } catch (IllegalArgumentException invalid) {
            send(exchange, 400, Map.of("detail", message(invalid.getMessage())));
        } catch (IOException failure) {
            LOG.log(System.Logger.Level.ERROR, "Lark integration request failed: " + path + " (" + failure.getClass().getSimpleName() + ")");
            send(exchange, 500, Map.of("detail", routeFailureDetail(path)));
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.ERROR, "Lark integration request failed: " + path + " (" + failure.getClass().getSimpleName() + ")");
            send(exchange, 500, Map.of("detail", routeFailureDetail(path)));
        }
    }

    private Map<String, Object> statusBody(String owner) {
        LarkIntegrationService.Status status = service.status(owner);
        return status(AccessOwner.admin() ? status : status.redacted());
    }

    private static Map<String, Object> status(LarkIntegrationService.Status status) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("installed", status.installed());
        response.put("version", status.version());
        response.put("manifest_version", status.manifestVersion());
        response.put("latest_available_version", status.latestAvailableVersion());
        response.put("runtime_version_mismatch", status.runtimeVersionMismatch());
        response.put("app_configured", status.appConfigured());
        response.put("app_id", status.appId());
        response.put("app_brand", status.appBrand());
        response.put("skills_expected", status.skillsExpected());
        response.put("skills_installed", status.skillsInstalled());
        response.put("installed_skills", status.installedSkills());
        response.put("enabled_skills", status.enabledSkills());
        response.put("install_path", status.installPath());
        Map<String, Object> cli = new LinkedHashMap<>();
        cli.put("available", status.cli().available());
        cli.put("path", status.cli().path());
        cli.put("version", status.cli().version());
        cli.put("error", status.cli().error());
        response.put("cli", cli);
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("status", status.auth().status());
        auth.put("message", status.auth().message());
        auth.put("user", status.auth().user());
        auth.put("verified", status.auth().verified());
        response.put("auth", auth);
        response.put("sandbox_runtime_mode", status.sandboxRuntimeMode());
        response.put("sandbox_runtime_ready", status.sandboxRuntimeReady());
        response.put("sandbox_runtime_detail", status.sandboxRuntimeDetail());
        return response;
    }

    private static Map<String, Object> configStart(LarkIntegrationService.ConfigStart result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("verification_url", result.verificationUrl());
        response.put("device_code", result.deviceCode());
        response.put("generation", result.generation());
        response.put("expires_in", result.expiresIn());
        response.put("interval", result.interval());
        response.put("user_code", result.userCode());
        response.put("brand", result.brand());
        return response;
    }

    private static Map<String, Object> configComplete(LarkIntegrationService.ConfigComplete result,
            boolean includeHostPaths) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", result.success());
        response.put("message", result.message());
        response.put("generation", result.generation());
        response.put("status", status(includeHostPaths ? result.status() : result.status().redacted()));
        return response;
    }

    private static Map<String, Object> authStart(LarkIntegrationService.AuthStart result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("verification_url", result.verificationUrl());
        response.put("device_code", result.deviceCode());
        response.put("generation", result.generation());
        response.put("expires_in", result.expiresIn());
        response.put("user_code", result.userCode());
        response.put("hint", result.hint());
        return response;
    }

    private static Map<String, Object> authComplete(LarkIntegrationService.AuthComplete result,
            boolean includeHostPaths) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", result.success());
        response.put("message", result.message());
        response.put("status", status(includeHostPaths ? result.status() : result.status().redacted()));
        return response;
    }

    private JsonNode body(HttpExchange exchange) throws IOException {
        byte[] payload = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (payload.length > MAX_BODY_BYTES) {
            throw new BodyFailure("Request body is too large");
        }
        if (payload.length == 0) {
            throw new BodyFailure("Request body must be a JSON object");
        }
        JsonNode node;
        try {
            node = json.readTree(payload);
        } catch (JsonProcessingException invalid) {
            throw new BodyFailure("Invalid JSON body");
        }
        if (node == null || !node.isObject()) {
            throw new BodyFailure("Request body must be a JSON object");
        }
        return node;
    }

    private static String requiredText(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new BodyFailure(field + " must be a non-empty string");
        }
        return value.asText();
    }

    private static String optionalText(JsonNode body, String field, String fallback) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isTextual()) {
            throw new BodyFailure(field + " must be a string");
        }
        return value.asText();
    }

    private static String generation(JsonNode body) {
        JsonNode value = body.get("generation");
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new BodyFailure("generation must be a non-empty string");
        }
        String text = value.asText();
        if (text.length() > 64) {
            throw new BodyFailure("generation must be at most 64 characters");
        }
        return text;
    }

    private static Integer optionalInteger(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw new BodyFailure(field + " must be an integer");
        }
        return value.asInt();
    }

    private static List<String> stringList(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new BodyFailure(field + " must be an array");
        }
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (JsonNode entry : value) {
            if (!entry.isTextual()) {
                throw new BodyFailure(field + " entries must be strings");
            }
            result.add(entry.asText());
        }
        return result;
    }

    private static boolean optionalBoolean(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null) return false;
        if (value.isBoolean()) return value.asBoolean();
        // Match the frozen Pydantic bool coercions without silently dropping malformed values.
        String text = value.isTextual() || value.isNumber() ? value.asText().toLowerCase(java.util.Locale.ROOT) : "";
        if (List.of("1", "true", "t", "yes", "y", "on").contains(text)) return true;
        if (List.of("0", "false", "f", "no", "n", "off").contains(text)) return false;
        throw new BodyFailure(field + " must be a boolean");
    }

    private static String routeFailureDetail(String path) {
        return switch (path) {
            case INSTALL -> "Failed to install Lark integration.";
            case CONFIG_START -> "Failed to start Lark connection setup.";
            case CONFIG_COMPLETE -> "Failed to complete Lark connection setup.";
            case CONFIG_CREDENTIALS -> "Failed to switch Lark app credentials.";
            case AUTH_START -> "Failed to start Lark authorization.";
            case AUTH_COMPLETE -> "Failed to complete Lark authorization.";
            default -> "Failed to get Lark integration status.";
        };
    }

    private static String message(String value) {
        return value == null || value.isBlank() ? "Lark integration request failed." : value;
    }

    private void methodNotAllowed(HttpExchange exchange, String allow) throws IOException {
        exchange.getResponseHeaders().set("Allow", allow);
        send(exchange, 405, Map.of("detail", "Method Not Allowed"));
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, payload.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(payload);
        }
    }

    private static final class BodyFailure extends RuntimeException {
        private BodyFailure(String message) {
            super(message);
        }
    }
}

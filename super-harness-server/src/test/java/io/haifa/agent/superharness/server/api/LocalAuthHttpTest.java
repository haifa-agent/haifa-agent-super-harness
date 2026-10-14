package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.superharness.server.auth.LocalAuthService;
import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalAuthHttpTest {
    private static final String PHRASE = "Sup3r-secret-pass";
    private static final String ORIGIN = "http://127.0.0.1:45618";
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    @TempDir
    Path dataDirectory;

    @Test
    void localSessionMatchesRegistrationOriginAndCookieContract() throws Exception {
        DeerFlowBootstrapApi api = start(dataDirectory, ORIGIN);
        try {
            URI base = base(api);
            JsonNode setup = json.readTree(send(base, "GET", "/api/v1/auth/setup-status", null, null).body());
            assertThat(setup.get("needs_setup").asBoolean()).isTrue();
            assertThat(setup.get("registration_enabled").asBoolean()).isTrue();

            HttpResponse<String> forged = send(base, "GET", "/api/v1/auth/me", null,
                    Map.of("Authorization", "Bearer forged", "X-User-Id", "attacker"));
            assertThat(forged.statusCode()).isEqualTo(401);
            assertThat(forged.body()).contains("not_authenticated");

            HttpResponse<String> user = send(base, "POST", "/api/v1/auth/register",
                    "{\"email\":\"user@example.com\",\"password\":\"" + PHRASE + "\"}",
                    Map.of("Content-Type", "application/json", "Origin", ORIGIN));
            assertThat(user.statusCode()).isEqualTo(201);
            assertThat(json.readTree(user.body()).get("system_role").asText()).isEqualTo("user");
            assertThat(cookie(user, "access_token")).contains("HttpOnly").contains("SameSite=Lax").doesNotContain("Max-Age=");
            assertThat(cookie(user, "csrf_token")).contains("SameSite=Strict").doesNotContain("HttpOnly");

            JsonNode stillSetup = json.readTree(send(base, "GET", "/api/v1/auth/setup-status", null, null).body());
            assertThat(stillSetup.get("needs_setup").asBoolean()).isTrue();

            HttpResponse<String> admin = send(base, "POST", "/api/v1/auth/initialize",
                    "{\"email\":\"admin@example.com\",\"password\":\"" + PHRASE + "\",\"remember_me\":true}",
                    Map.of("Content-Type", "application/json", "Origin", ORIGIN));
            assertThat(admin.statusCode()).isEqualTo(201);
            assertThat(json.readTree(admin.body()).get("system_role").asText()).isEqualTo("admin");
            assertThat(cookie(admin, "access_token")).contains("HttpOnly").contains("Max-Age=");
            Session session = Session.from(admin);

            JsonNode ready = json.readTree(send(base, "GET", "/api/v1/auth/setup-status", null, null).body());
            assertThat(ready.get("needs_setup").asBoolean()).isFalse();
            assertThat(ready.get("registration_enabled").asBoolean()).isTrue();

            HttpResponse<String> duplicate = send(base, "POST", "/api/v1/auth/register",
                    "{\"email\":\"admin@example.com\",\"password\":\"" + PHRASE + "\"}",
                    Map.of("Content-Type", "application/json", "Origin", ORIGIN));
            assertThat(duplicate.statusCode()).isEqualTo(400);
            assertThat(duplicate.body()).contains("email_already_exists");

            HttpResponse<String> again = send(base, "POST", "/api/v1/auth/initialize",
                    "{\"email\":\"other@example.com\",\"password\":\"" + PHRASE + "\"}",
                    Map.of("Content-Type", "application/json", "Origin", ORIGIN));
            assertThat(again.statusCode()).isEqualTo(409);
            assertThat(again.body()).contains("system_already_initialized");

            HttpResponse<String> evil = send(base, "POST", "/api/v1/auth/login/local",
                    "username=admin@example.com&password=" + PHRASE,
                    Map.of("Content-Type", "application/x-www-form-urlencoded", "Origin", "http://evil.example",
                            "X-Forwarded-Host", "127.0.0.1:45618", "X-Forwarded-Proto", "https"));
            assertThat(evil.statusCode()).isEqualTo(403);
            assertThat(json.readTree(evil.body()).get("detail").asText()).isEqualTo(LocalAuthService.ORIGIN_DENIED);
            assertThat(evil.headers().allValues("set-cookie")).isEmpty();

            HttpResponse<String> login = send(base, "POST", "/api/v1/auth/login/local",
                    "username=admin@example.com&password=" + PHRASE + "&remember_me=true",
                    Map.of("Content-Type", "application/x-www-form-urlencoded", "Origin", ORIGIN));
            assertThat(login.statusCode()).isEqualTo(200);
            assertThat(json.readTree(login.body()).get("email").asText()).isEqualTo("admin@example.com");

            HttpResponse<String> logoutDenied = send(base, "POST", "/api/v1/auth/logout", "",
                    Map.of("Origin", "http://evil.example", "Cookie", session.cookieHeader()));
            assertThat(logoutDenied.statusCode()).isEqualTo(403);
            HttpResponse<String> stillIn = send(base, "GET", "/api/v1/auth/me", null, session.headers());
            assertThat(stillIn.statusCode()).isEqualTo(200);

            Files.writeString(dataDirectory.resolve("auth/local.json"), "{\"allow_registration\":false}");
            HttpResponse<String> closed = send(base, "POST", "/api/v1/auth/register",
                    "{\"email\":\"new@example.com\",\"password\":\"" + PHRASE + "\"}",
                    Map.of("Content-Type", "application/json"));
            assertThat(closed.statusCode()).isEqualTo(403);
            assertThat(closed.body()).contains("registration_disabled");
            JsonNode disabled = json.readTree(send(base, "GET", "/api/v1/auth/setup-status", null, null).body());
            assertThat(disabled.get("registration_enabled").asBoolean()).isFalse();
            assertThat(disabled.get("needs_setup").asBoolean()).isFalse();
        } finally {
            api.close();
        }
    }

    @Test
    void malformedRegistrationConfigRefusesToStart() throws Exception {
        Files.createDirectories(dataDirectory.resolve("auth"));
        Files.writeString(dataDirectory.resolve("auth/local.json"), "{");
        assertThatThrownBy(() -> start(dataDirectory, ORIGIN)).isInstanceOf(IOException.class);
    }

    @Test
    void emptyAllowlistRejectsBrowserOriginAndAllowsClientsWithoutOrigin() throws Exception {
        DeerFlowBootstrapApi api = start(dataDirectory, "");
        try {
            URI base = base(api);
            HttpResponse<String> denied = send(base, "POST", "/api/v1/auth/initialize",
                    "{\"email\":\"admin@example.com\",\"password\":\"" + PHRASE + "\"}",
                    Map.of("Content-Type", "application/json", "Origin", ORIGIN));
            assertThat(denied.statusCode()).isEqualTo(403);
            assertThat(denied.headers().allValues("set-cookie")).isEmpty();
            HttpResponse<String> created = send(base, "POST", "/api/v1/auth/initialize",
                    "{\"email\":\"admin@example.com\",\"password\":\"" + PHRASE + "\"}",
                    Map.of("Content-Type", "application/json"));
            assertThat(created.statusCode()).isEqualTo(201);
        } finally {
            api.close();
        }
    }

    @Test
    void savedOwnerGatesThreadRunAndProjectAndSurvivesRestart() throws Exception {
        DeerFlowBootstrapApi api = start(dataDirectory, ORIGIN);
        Session admin;
        Session user;
        String threadId = "thread-a";
        String projectId;
        String threadBefore;
        try {
            URI base = base(api);
            admin = Session.from(send(base, "POST", "/api/v1/auth/initialize",
                    "{\"email\":\"admin@example.com\",\"password\":\"" + PHRASE + "\",\"remember_me\":true}",
                    Map.of("Content-Type", "application/json", "Origin", ORIGIN)));
            user = Session.from(send(base, "POST", "/api/v1/auth/register",
                    "{\"email\":\"user@example.com\",\"password\":\"" + PHRASE + "\"}",
                    Map.of("Content-Type", "application/json", "Origin", ORIGIN)));

            HttpResponse<String> anonymous = send(base, "POST", "/api/threads",
                    "{\"thread_id\":\"anonymous\"}", Map.of("Content-Type", "application/json"));
            assertThat(anonymous.statusCode()).isEqualTo(401);

            HttpResponse<String> created = send(base, "POST", "/api/threads",
                    "{\"thread_id\":\"" + threadId + "\"}", admin.jsonHeaders());
            assertThat(created.statusCode()).isEqualTo(200);
            HttpResponse<String> project = send(base, "POST", "/api/projects",
                    "{\"name\":\"Owner project\"}", admin.jsonHeaders());
            assertThat(project.statusCode()).isEqualTo(201);
            projectId = json.readTree(project.body()).get("id").asText();
            threadBefore = send(base, "GET", "/api/threads/" + threadId, null, admin.headers()).body();

            assertThat(send(base, "POST", "/api/threads/" + threadId + "/runs/stream",
                    "{\"input\":{\"messages\":[]}}", user.jsonHeaders()).statusCode()).isEqualTo(404);
            assertThat(send(base, "POST", "/api/threads/" + threadId + "/runs/run-b/cancel",
                    "{}", user.jsonHeaders()).statusCode()).isEqualTo(404);
            assertThat(send(base, "GET", "/api/threads/" + threadId + "/runs/run-b/stream",
                    null, user.headers()).statusCode()).isEqualTo(404);
            assertThat(send(base, "POST", "/api/threads/" + threadId + "/state",
                    "{}", user.jsonHeaders()).statusCode()).isEqualTo(404);
            assertThat(send(base, "DELETE", "/api/projects/" + projectId, null, user.headers()).statusCode())
                    .isEqualTo(404);

            assertThat(send(base, "GET", "/api/threads/" + threadId, null, admin.headers()).body())
                    .isEqualTo(threadBefore);
            assertThat(send(base, "GET", "/api/projects/" + projectId, null, admin.headers()).statusCode())
                    .isEqualTo(200);
            assertThat(Files.readString(dataDirectory.resolve("owners/default/threads.json"))).contains(admin.userId);
        } finally {
            api.close();
        }

        DeerFlowBootstrapApi restarted = start(dataDirectory, ORIGIN);
        try {
            URI base = base(restarted);
            assertThat(send(base, "GET", "/api/threads/" + threadId, null, admin.headers()).statusCode()).isEqualTo(200);
            assertThat(send(base, "GET", "/api/threads/" + threadId, null, user.headers()).statusCode()).isEqualTo(404);
            assertThat(send(base, "GET", "/api/projects/" + projectId, null, admin.headers()).statusCode()).isEqualTo(200);
            assertThat(send(base, "DELETE", "/api/projects/" + projectId, null, user.headers()).statusCode())
                    .isEqualTo(404);
        } finally {
            restarted.close();
        }
    }

    private DeerFlowBootstrapApi start(Path data, String origins) throws Exception {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put(LocalAuthService.ENABLED, "1");
        if (origins != null) environment.put(LocalAuthService.ALLOWED_ORIGINS, origins);
        DeerFlowBootstrapApi api = DeerFlowBootstrapApi.create(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                data,
                GatewayFixture.productionEnvironment(environment));
        api.start();
        return api;
    }

    private URI base(DeerFlowBootstrapApi api) {
        return URI.create("http://127.0.0.1:" + api.address().getPort());
    }

    private HttpResponse<String> send(URI base, String method, String path, String body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path));
        if (headers != null) headers.forEach(request::header);
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.method(method, HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String cookie(HttpResponse<?> response, String name) {
        return response.headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith(name + "="))
                .findFirst()
                .orElse("");
    }

    private static final class Session {
        private final String userId;
        private final String accessToken;
        private final String csrfToken;

        private Session(String userId, String accessToken, String csrfToken) {
            this.userId = userId;
            this.accessToken = accessToken;
            this.csrfToken = csrfToken;
        }

        static Session from(HttpResponse<String> response) throws Exception {
            String access = value(cookie(response, "access_token"));
            String csrf = value(cookie(response, "csrf_token"));
            String id = new ObjectMapper().readTree(response.body()).get("id").asText();
            return new Session(id, access, csrf);
        }

        Map<String, String> headers() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("Cookie", cookieHeader());
            values.put("X-CSRF-Token", csrfToken);
            return values;
        }

        Map<String, String> jsonHeaders() {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Cookie", cookieHeader());
            headers.put("X-CSRF-Token", csrfToken);
            return headers;
        }

        String cookieHeader() {
            return "access_token=" + accessToken + "; csrf_token=" + csrfToken;
        }
    }

    private static String value(String setCookie) {
        int start = setCookie.indexOf('=');
        int end = setCookie.indexOf(';');
        return setCookie.substring(start + 1, end);
    }
}

package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.superharness.server.auth.AccessOwner;
import io.haifa.agent.superharness.server.auth.AccountSdkIdentity;
import io.haifa.agent.superharness.server.auth.LocalAuthService;
import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
import io.haifa.agent.superharness.server.run.PersistentThreadCatalog;
import io.haifa.agent.superharness.server.testsupport.ControlledProvider;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import io.haifa.agent.superharness.server.testsupport.SseEvents;
import io.haifa.agent.superharness.server.tool.agent.ProductionSetupAgentTool;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceChangeLimits;
import io.haifa.agent.superharness.workspace.WorkspaceLimits;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
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
            JsonNode loginBody = json.readTree(login.body());
            assertThat(loginBody.get("expires_in").asLong()).isEqualTo(7L * 24 * 3600);
            assertThat(loginBody.get("needs_setup").asBoolean()).isFalse();
            assertThat(loginBody.has("email")).isFalse();

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
    void adminCanRetireOnlyTemporaryUsersAndTheirSessionsStopWorking() throws Exception {
        String accounts = "/api/v1/auth/admin/temporary-users";
        String me = "/api/v1/auth/me";
        String login = "/api/v1/auth/login/local";
        Map<String, String> headers = Map.of("Content-Type", "application/json", "Origin", ORIGIN);
        String adminCredentials = json.writeValueAsString(Map.of("email", "admin@example.com", "password", PHRASE));
        String permanentCredentials = json.writeValueAsString(Map.of("email", "permanent@example.com", "password", PHRASE));
        String temporaryCredentials = json.writeValueAsString(Map.of("email", "temp@example.com", "password", PHRASE));
        String newPhrase = PHRASE + "-rotated";
        Session admin;
        Session ordinary;
        String temporaryId;
        Session temporary;
        try (var api = start(dataDirectory, ORIGIN)) {
            URI base = base(api);
            admin = Session.from(send(base, "POST", "/api/v1/auth/initialize", adminCredentials, headers));
            ordinary = Session.from(send(base, "POST", "/api/v1/auth/register", permanentCredentials, headers));
            Files.writeString(dataDirectory.resolve("auth/local.json"), "{\"allow_registration\":false}");
            assertThat(send(base, "POST", "/api/v1/auth/register", temporaryCredentials, headers).statusCode()).isEqualTo(403);
            assertThat(send(base, "POST", accounts, temporaryCredentials, headers).statusCode()).isEqualTo(401);
            assertThat(send(base, "POST", accounts, temporaryCredentials, ordinary.jsonHeaders()).statusCode()).isEqualTo(403);
            assertThat(send(base, "POST", accounts, temporaryCredentials, Map.of("Cookie", admin.cookieHeader(),
                    "Content-Type", "application/json")).statusCode()).isEqualTo(403);

            HttpResponse<String> created = send(base, "POST", accounts, temporaryCredentials, admin.jsonHeaders());
            assertThat(created.statusCode()).isEqualTo(201);
            assertThat(created.headers().allValues("Set-Cookie")).isEmpty();
            JsonNode account = json.readTree(created.body());
            assertThat(account.get("system_role").asText()).isEqualTo("user");
            temporaryId = account.get("id").asText();
            assertThat(send(base, "POST", accounts, temporaryCredentials, admin.jsonHeaders()).statusCode()).isEqualTo(400);
            HttpResponse<String> loggedIn = send(base, "POST", login, temporaryCredentials, headers);
            assertThat(loggedIn.statusCode()).isEqualTo(200);
            Session beforeRotation = Session.from(loggedIn, temporaryId);
            HttpResponse<String> rotated = send(base, "POST", "/api/v1/auth/change-password",
                    json.writeValueAsString(Map.of("current_password", PHRASE, "new_password", newPhrase)),
                    beforeRotation.jsonHeaders());
            assertThat(rotated.statusCode()).isEqualTo(200);
            temporary = Session.from(rotated);
        }
        try (var api = start(dataDirectory, ORIGIN)) {
            URI base = base(api);
            String target = accounts + "/" + temporaryId;
            assertThat(json.readTree(send(base, "GET", me, null, temporary.headers()).body()).get("id").asText())
                    .isEqualTo(temporaryId);
            assertThat(send(base, "DELETE", target, null, temporary.headers()).statusCode()).isEqualTo(403);
            assertThat(send(base, "DELETE", target, null, Map.of("Cookie", admin.cookieHeader())).statusCode()).isEqualTo(403);
            assertThat(send(base, "DELETE", accounts + "/" + admin.userId, null, admin.headers()).statusCode()).isEqualTo(404);
            assertThat(send(base, "DELETE", accounts + "/" + ordinary.userId, null, admin.headers()).statusCode()).isEqualTo(404);
            assertThat(send(base, "DELETE", target, null, admin.headers()).statusCode()).isEqualTo(204);
            assertThat(send(base, "DELETE", target, null, admin.headers()).statusCode()).isEqualTo(404);
            assertThat(send(base, "GET", me, null, temporary.headers()).statusCode()).isEqualTo(401);
            String revokedCredentials = json.writeValueAsString(Map.of("email", "temp@example.com", "password", newPhrase));
            assertThat(send(base, "POST", login, revokedCredentials, headers).statusCode()).isEqualTo(401);

            HttpResponse<String> replacement = send(base, "POST", accounts, temporaryCredentials, admin.jsonHeaders());
            assertThat(replacement.statusCode()).isEqualTo(201);
            assertThat(json.readTree(replacement.body()).get("id").asText()).isNotEqualTo(temporaryId);
            assertThat(send(base, "GET", me, null, temporary.headers()).statusCode()).isEqualTo(401);
            assertThat(send(base, "GET", me, null, ordinary.headers()).statusCode()).isEqualTo(200);
            assertThat(json.readTree(send(base, "GET", "/api/v1/auth/setup-status", null, null).body())
                    .get("registration_enabled").asBoolean()).isFalse();
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

            HttpResponse<String> otherThread = send(base, "POST", "/api/threads",
                    "{\"thread_id\":\"other-owner-thread\"}", user.jsonHeaders());
            assertThat(otherThread.statusCode()).isEqualTo(200);
            String move = """
                    {"project_id":"%s"}
                    """.formatted(projectId);
            assertThat(send(base, "POST", "/api/threads/" + threadId + "/move",
                    move, user.jsonHeaders()).statusCode()).isEqualTo(404);
            assertThat(send(base, "POST", "/api/threads/other-owner-thread/move",
                    move, user.jsonHeaders()).statusCode()).isEqualTo(404);
            assertThat(json.readTree(send(base, "GET", "/api/threads/other-owner-thread", null, user.headers()).body())
                    .path("metadata").has("deerflow_project_id")).isFalse();

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

    @Test
    void accountRunsAndToolsStayIsolatedWhileLegacyApprovalAndHistorySurviveRestart() throws Exception {
        Path data = GatewayFixture.dataDirectory();
        Path evidence = GatewayFixture.evidenceDirectory();
        Map<String, String> environment = Map.of(LocalAuthService.ENABLED, "1",
                AccountSdkIdentity.BIND_NEW_THREADS, "true");
        Map<String, String> rollbackEnvironment = Map.of(LocalAuthService.ENABLED, "1",
                AccountSdkIdentity.BIND_NEW_THREADS, "false");
        int gatewayPort = Integer.getInteger("w13.gatewayPort", 0);
        String legacy = "legacy-account-thread";
        String aliceThread = "alice-account-thread";
        String bobThread = "bob-account-thread";
        try (var provider = new ControlledProvider(evidence, true, (request, call) -> {
            String responseId = "identity-" + call;
            String filename = switch (call) {
                case 2 -> "legacy";
                case 5 -> "alice";
                case 7 -> "bob";
                default -> null;
            };
            if (filename != null) {
                return ControlledProvider.call(responseId, "identity-provider", "write-" + filename, "write_file",
                        Map.of("path", "/mnt/user-data/outputs/" + filename + ".txt", "content", filename));
            }
            return ControlledProvider.stop(responseId, "identity-provider", "completed turn " + call);
        })) {
            try (var gateway = GatewayFixture.production(data, gatewayPort, environment, evidence)) {
                String account = "{\"email\":\"%s\",\"password\":\"%s\"}";
                assertThat(send(gateway.base, "POST", "/api/v1/auth/initialize",
                        account.formatted("alice@example.com", PHRASE),
                        Map.of("Content-Type", "application/json")).statusCode()).isEqualTo(201);
                assertThat(send(gateway.base, "POST", "/api/v1/auth/register",
                        account.formatted("bob@example.com", PHRASE),
                        Map.of("Content-Type", "application/json")).statusCode()).isEqualTo(201);
                Session alice = login(gateway.base, "alice@example.com");
                String config = """
                        {"config":{"name":"identity-model","display_name":"Identity model","provider":"openai-compatible",
                        "model":"identity-provider","base_url":"http://127.0.0.1:%d/v1","enabled":true,
                        "supports_vision":false,"context_window":32768,"max_tokens":1024,"api_key":"controlled-key"},
                        "expected_revision":null}
                        """.formatted(provider.port());
                assertThat(send(gateway.base, "PUT", "/api/managed-models", config, alice.jsonHeaders()).statusCode())
                        .isEqualTo(200);
                Instant created = Instant.now();
                new PersistentThreadCatalog(json, data).save(new PersistentThreadCatalog.Entry(
                        legacy, Map.of(), created, created, Optional.empty(), Optional.empty(),
                        java.util.List.of(), java.util.List.of(), alice.userId));
            }

            String legacyLocation;
            SseEvents.PendingApproval legacyPending;
            try (var gateway = GatewayFixture.production(data, gatewayPort, environment, evidence)) {
                Session alice = login(gateway.base, "alice@example.com");
                run(gateway.base, alice, legacy, "legacy history marker", evidence.resolve("legacy-history.sse"));
                legacyLocation = run(gateway.base, alice, legacy, "legacy file approval",
                        evidence.resolve("legacy-pending.sse"));
                legacyPending = pending(gateway.base, alice, legacy, legacyLocation, evidence);
                assertThat(provider.calls).hasValue(2);
                assertThat(send(gateway.base, "GET", artifact(legacy, "legacy"), null, alice.headers()).statusCode())
                        .isEqualTo(404);
            }

            String continued;
            String aliceLocation;
            String bobLocation;
            Session alice;
            Session bob;
            try (var gateway = GatewayFixture.production(data, gatewayPort, environment, evidence)) {
                URI base = gateway.base;
                alice = login(base, "alice@example.com");
                bob = login(base, "bob@example.com");
                assertThat(alice.userId).isNotEqualTo(bob.userId);
                JsonNode history = authenticatedGet(base, alice, thread(legacy) + "/state");
                Files.writeString(evidence.resolve("legacy-restarted-state.json"), history.toPrettyString());
                assertThat(history.toString()).contains("legacy history marker", "completed turn 1");
                var recovered = pending(base, alice, legacy, legacyLocation, evidence);
                assertThat(recovered.requestId()).isEqualTo(legacyPending.requestId());
                assertThat(recovered.card().path("id")).isEqualTo(legacyPending.card().path("id"));
                assertThat(send(base, "GET", legacyLocation, null, bob.headers()).statusCode()).isEqualTo(404);
                assertThat(send(base, "POST", thread(legacy) + "/runs/stream",
                        SseEvents.PendingApproval.reply(recovered.requestId(), "approve"), bob.jsonHeaders())
                        .statusCode()).isEqualTo(404);
                assertThat(provider.calls).hasValue(2);
                approve(base, alice, legacy, legacyLocation, recovered, "legacy", evidence.resolve("legacy-approved.sse"));
                assertThat(send(base, "POST", thread(legacy) + "/runs/stream",
                        SseEvents.PendingApproval.reply(recovered.requestId(), "approve"), alice.jsonHeaders())
                        .statusCode()).isEqualTo(409);
                continued = run(base, alice, legacy, "continue old Session", evidence.resolve("legacy-continued.sse"));
                assertThat(authenticatedGet(base, alice, continued).path("status").asText()).isEqualTo("success");

                aliceLocation = accountWrite(base, alice, aliceThread, "alice", evidence);
                bobLocation = accountWrite(base, bob, bobThread, "bob", evidence);
                assertThat(send(base, "GET", thread(aliceThread) + "/state", null, bob.headers()).statusCode())
                        .isEqualTo(404);
                assertThat(send(base, "GET", artifact(aliceThread, "alice"), null, bob.headers()).statusCode())
                        .isEqualTo(404);
                assertThat(send(base, "GET", bobLocation, null, alice.headers()).statusCode()).isEqualTo(404);
                assertThat(send(base, "GET", "/api/memory", null, alice.headers()).statusCode()).isEqualTo(404);
                assertThat(provider.calls).hasValue(8);
            }

            String rollbackLocation;
            try (var gateway = GatewayFixture.production(data, gatewayPort, rollbackEnvironment, evidence)) {
                URI base = gateway.base;
                Session rollbackAlice = login(base, "alice@example.com");
                Session rollbackBob = login(base, "bob@example.com");
                assertThat(authenticatedGet(base, rollbackAlice, aliceLocation)
                        .path("status").asText()).isEqualTo("success");
                assertThat(authenticatedGet(base, rollbackBob, bobLocation)
                        .path("status").asText()).isEqualTo("success");
                assertThat(authenticatedGet(base, rollbackAlice, thread(aliceThread) + "/state").toString())
                        .contains("completed turn 6");
                assertThat(send(base, "GET", thread(aliceThread) + "/state", null, rollbackBob.headers()).statusCode())
                        .isEqualTo(404);
                assertThat(send(base, "GET", thread(bobThread) + "/state", null, rollbackAlice.headers()).statusCode())
                        .isEqualTo(404);
                String rollbackThread = "new-thread-in-rollback";
                assertThat(send(base, "POST", "/api/threads",
                        "{\"thread_id\":\"new-thread-in-rollback\"}", rollbackAlice.jsonHeaders()).statusCode())
                        .isEqualTo(200);
                rollbackLocation = run(base, rollbackAlice, rollbackThread, "rollback creates public SDK session",
                        evidence.resolve("rollback-new-thread.sse"));
            }
            assertSdkOwner(data, rollbackLocation, "public", "default-public-user");
            String legacySession = assertSdkOwner(data, legacyLocation, "public", "default-public-user");
            assertThat(assertSdkOwner(data, continued, "public", "default-public-user")).isEqualTo(legacySession);
            assertThat(assertSdkOwner(data, aliceLocation, "super-harness", alice.userId))
                    .isNotEqualTo(assertSdkOwner(data, bobLocation, "super-harness", bob.userId));
            System.out.println("W13_ACCOUNT_IDENTITY_EVIDENCE evidence=" + evidence.toAbsolutePath()
                    + " legacy=" + legacyLocation + " alice=" + aliceLocation + " bob=" + bobLocation
                    + " legacyRequest=" + legacyPending.requestId());
        }
    }

    @Test
    void signedInMemoryUsesOnlyTheOwnersCurrentFactsAcrossNewRunsAndRestart() throws Exception {
        Path data = GatewayFixture.dataDirectory();
        Path evidence = GatewayFixture.evidenceDirectory();
        String key = "HAIFA_SUPER_HARNESS_MEMORY_ENABLED";
        Map<String, String> enabled = Map.of(LocalAuthService.ENABLED, "1", key, "true");
        Map<String, String> disabled = Map.of(LocalAuthService.ENABLED, "1");
        var prompts = new java.util.concurrent.ConcurrentHashMap<String, JsonNode>();
        try (var provider = new ControlledProvider(evidence, true, (request, call) -> {
            for (JsonNode message : request.path("messages")) {
                if (message.path("role").asText().equals("user")) {
                    JsonNode content = message.path("content");
                    String text = content.isArray() ? content.path(0).path("text").asText() : content.asText();
                    text.lines().filter(line -> line.startsWith("report-clue-"))
                            .findFirst().ifPresent(clue -> prompts.put(clue, request.deepCopy()));
                }
            }
            return ControlledProvider.stop("memory-" + call, "memory-provider", "Report checked.");
        })) {
            String factId;
            String bobId;
            String earlierThread;
            try (var gateway = GatewayFixture.production(data, 0, enabled, evidence)) {
                URI base = gateway.base;
                String account = "{\"email\":\"%s\",\"password\":\"%s\"}";
                assertThat(send(base, "POST", "/api/v1/auth/initialize",
                        account.formatted("memory-a@example.com", PHRASE),
                        Map.of("Content-Type", "application/json")).statusCode()).isEqualTo(201);
                assertThat(send(base, "POST", "/api/v1/auth/register",
                        account.formatted("memory-b@example.com", PHRASE),
                        Map.of("Content-Type", "application/json")).statusCode()).isEqualTo(201);
                Session alice = login(base, "memory-a@example.com");
                Session bob = login(base, "memory-b@example.com");
                assertThat(alice.userId).isNotEqualTo(bob.userId);
                String config = """
                        {"config":{"name":"identity-model","display_name":"Memory model","provider":"openai-compatible",
                        "model":"memory-provider","base_url":"http://127.0.0.1:%d/v1","enabled":true,
                        "supports_vision":false,"context_window":32768,"max_tokens":1024,"api_key":"controlled-key"},
                        "expected_revision":null}
                        """.formatted(provider.port());
                assertThat(send(base, "PUT", "/api/managed-models", config, alice.jsonHeaders()).statusCode())
                        .isEqualTo(200);
                assertThat(send(base, "GET", "/api/memory", null, Map.of()).statusCode()).isEqualTo(401);
                JsonNode aliceSaved = memory(base, alice, "POST", "/api/memory/facts", fact("report-clue ALPHA746"));
                JsonNode bobSaved = memory(base, bob, "POST", "/api/memory/facts", fact("report-clue BOB278"));
                JsonNode aliceFact = aliceSaved.path("facts").get(0);
                factId = aliceFact.path("id").asText();
                bobId = bobSaved.path("facts").get(0).path("id").asText();
                assertThat(aliceFact.path("source").asText()).isEqualTo("manual");
                assertThat(bobId).isNotEqualTo(factId);
                earlierThread = memoryRun(base, alice, "report-clue-a-before", prompts, evidence)
                        .toString();
                assertThat(earlierThread).contains("[memory " + factId + "@1]", "ALPHA746").doesNotContain("BOB278");
                assertThat(memoryRun(base, bob, "report-clue-b-before", prompts, evidence).toString())
                        .contains("[memory " + bobId + "@1]", "BOB278").doesNotContain("ALPHA746", factId);
                assertThat(memory(base, bob, "GET", "/api/memory/export", null).toString())
                        .contains(bobId).doesNotContain(factId, "ALPHA746");
                for (String method : java.util.List.of("PATCH", "DELETE")) {
                    String input = method.equals("PATCH") ? "{\"content\":\"foreign\"}" : null;
                    var denied = send(base, method, "/api/memory/facts/" + factId, input, bob.jsonHeaders());
                    var missing = send(base, method, "/api/memory/facts/absent-memory-id", input, bob.jsonHeaders());
                    assertThat(denied.statusCode()).isEqualTo(404);
                    assertThat(denied.body()).isEqualTo(missing.body());
                }
                assertThat(send(base, "PATCH", "/api/memory/facts/" + factId,
                        "{\"content\":\"invalid mutation\",\"confidence\":0.9}", alice.jsonHeaders()).statusCode())
                        .isEqualTo(422);
                assertThat(memory(base, alice, "GET", "/api/memory", null)).isEqualTo(aliceSaved);
                JsonNode corrected = memory(base, alice, "PATCH", "/api/memory/facts/" + factId,
                        fact("report-clue CORRECT935"));
                assertThat(corrected.path("facts").get(0).path("id").asText()).isEqualTo(factId);
                assertThat(memory(base, alice, "GET", "/api/memory/export", null)).isEqualTo(corrected);
                assertThat(memoryRun(base, alice, "report-clue-a-corrected", prompts, evidence).toString())
                        .contains("[memory " + factId + "@2]", "CORRECT935").doesNotContain("ALPHA746", "BOB278");
                assertThat(send(base, "POST", "/api/memory/import", "{}", alice.jsonHeaders()).statusCode())
                        .isEqualTo(501);
            }

            try (var gateway = GatewayFixture.production(data, 0, disabled, evidence)) {
                URI base = gateway.base;
                Session alice = login(base, "memory-a@example.com");
                assertThat(send(base, "GET", "/api/memory", null, alice.headers()).statusCode()).isEqualTo(404);
                assertThat(memoryRun(base, alice, "report-clue-disabled", prompts, evidence).toString())
                        .doesNotContain("[memory ", "CORRECT935");
            }
            try (var gateway = GatewayFixture.production(data, 0, enabled, evidence)) {
                URI base = gateway.base;
                Session alice = login(base, "memory-a@example.com");
                Session bob = login(base, "memory-b@example.com");
                assertThat(memory(base, alice, "GET", "/api/memory", null).toString()).contains(factId, "CORRECT935");
                assertThat(memory(base, alice, "DELETE", "/api/memory/facts/" + factId, null).path("facts").isEmpty())
                        .isTrue();
                assertThat(memoryRun(base, alice, "report-clue-a-deleted", prompts, evidence).toString())
                        .doesNotContain("[memory ", "ALPHA746", "CORRECT935", "BOB278");
                assertThat(memory(base, alice, "GET", "/api/memory/export", null).path("facts").isEmpty()).isTrue();
                memory(base, alice, "POST", "/api/memory/facts", fact("report-clue CLEAR371"));
                memory(base, alice, "POST", "/api/memory/facts", fact("report-clue CLEAR608"));
                assertThat(memory(base, alice, "DELETE", "/api/memory", null).path("facts").isEmpty()).isTrue();
                assertThat(memory(base, bob, "GET", "/api/memory", null).toString()).contains(bobId, "BOB278");
            }
            try (var gateway = GatewayFixture.production(data, 0, enabled, evidence)) {
                URI base = gateway.base;
                Session alice = login(base, "memory-a@example.com");
                Session bob = login(base, "memory-b@example.com");
                assertThat(memory(base, alice, "GET", "/api/memory", null).path("facts").isEmpty()).isTrue();
                assertThat(authenticatedGet(base, alice, thread("report-clue-a-before") + "/state").toString())
                        .contains("report-clue-a-before", "Report checked.");
                assertThat(memoryRun(base, alice, "report-clue-a-restart", prompts, evidence).toString())
                        .doesNotContain("[memory ", "ALPHA746", "CORRECT935", "CLEAR371", "CLEAR608", "BOB278");
                assertThat(memoryRun(base, bob, "report-clue-b-restart", prompts, evidence).toString())
                        .contains("[memory " + bobId + "@1]", "BOB278").doesNotContain("CORRECT935");
            }
            System.out.println("W13_MEMORY_HTTP_EVIDENCE evidence=" + evidence.toAbsolutePath());
        }
    }

    private JsonNode memoryRun(URI base, Session account, String clue, Map<String, JsonNode> prompts, Path evidence)
            throws Exception {
        assertThat(send(base, "POST", "/api/threads", "{\"thread_id\":\"%s\"}".formatted(clue),
                account.jsonHeaders()).statusCode()).isEqualTo(200);
        String location = run(base, account, clue, clue, evidence.resolve(clue + ".sse"));
        assertThat(authenticatedGet(base, account, location).path("status").asText()).isEqualTo("success");
        JsonNode prompt = prompts.get(clue);
        assertThat(prompt).as("Provider request for %s", clue).isNotNull();
        Files.writeString(evidence.resolve(clue + "-provider.json"), prompt.toPrettyString());
        return prompt;
    }

    private JsonNode memory(URI base, Session session, String method, String path, String body) throws Exception {
        var response = send(base, method, path, body, session.jsonHeaders());
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        return json.readTree(response.body());
    }

    private static String fact(String content) {
        return "{\"content\":\"%s\",\"category\":\"context\",\"confidence\":0.8}".formatted(content);
    }

    private Session login(URI base, String email) throws Exception {
        HttpResponse<String> response = send(base, "POST", "/api/v1/auth/login/local",
                "username=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                        + "&password=" + URLEncoder.encode(PHRASE, StandardCharsets.UTF_8),
                Map.of("Content-Type", "application/x-www-form-urlencoded"));
        assertThat(response.statusCode()).isEqualTo(200);
        Session cookies = Session.from(response, "");
        String id = authenticatedGet(base, cookies, "/api/v1/auth/me").path("id").asText();
        assertThat(id).isNotBlank();
        return Session.from(response, id);
    }

    private String accountWrite(URI base, Session session, String threadId, String filename, Path evidence)
            throws Exception {
        assertThat(send(base, "POST", "/api/threads", "{\"thread_id\":\"%s\"}".formatted(threadId),
                session.jsonHeaders()).statusCode()).isEqualTo(200);
        String location = run(base, session, threadId, "write " + filename, evidence.resolve(filename + "-pending.sse"));
        approve(base, session, threadId, location, pending(base, session, threadId, location, evidence), filename,
                evidence.resolve(filename + "-approved.sse"));
        return location;
    }

    private void approve(URI base, Session session, String threadId, String location,
            SseEvents.PendingApproval pending, String filename, Path evidence) throws Exception {
        var response = send(base, "POST", thread(threadId) + "/runs/stream",
                SseEvents.PendingApproval.reply(pending.requestId(), "approve"), session.jsonHeaders());
        Files.writeString(evidence, response.body());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-location")).contains(location);
        assertThat(authenticatedGet(base, session, location).path("status").asText()).isEqualTo("success");
        var download = send(base, "GET", artifact(threadId, filename), null, session.headers());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(filename);
    }

    private SseEvents.PendingApproval pending(URI base, Session session, String threadId, String location, Path evidence)
            throws Exception {
        return SseEvents.PendingApproval.locate(threadId, location, authenticatedGet(base, session, location),
                authenticatedGet(base, session, thread(threadId) + "/state"), evidence);
    }

    private String run(URI base, Session session, String threadId, String input, Path evidence) throws Exception {
        String body = """
                {"assistant_id":"lead_agent","input":{"messages":[{"type":"human","content":"%s"}]},
                "stream_mode":["messages-tuple","updates","custom"],"on_disconnect":"continue",
                "context":{"model_name":"identity-model","mode":"flash","thinking_enabled":false}}
                """.formatted(input);
        var response = send(base, "POST", thread(threadId) + "/runs/stream", body, session.jsonHeaders());
        Files.writeString(evidence, response.body());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.headers().firstValue("content-location").orElseThrow();
    }

    private JsonNode authenticatedGet(URI base, Session session, String path) throws Exception {
        var response = send(base, "GET", path, null, session.headers());
        assertThat(response.statusCode()).as("GET %s", path).isEqualTo(200);
        return json.readTree(response.body());
    }

    private static String thread(String threadId) {
        return "/api/threads/" + threadId;
    }

    private static String artifact(String threadId, String filename) {
        return thread(threadId) + "/artifacts/mnt/user-data/outputs/" + filename + ".txt";
    }

    private static String assertSdkOwner(Path data, String location, String tenant, String owner) throws Exception {
        String url = "jdbc:sqlite:" + data.resolve("runtime/haifa-agent.sqlite").toUri() + "?mode=ro";
        try (var connection = DriverManager.getConnection(url);
                var query = connection.prepareStatement("""
                        SELECT r.session_id, r.tenant_id, r.principal_id, r.principal_type,
                            s.tenant_id, s.owner_principal_id, s.owner_principal_type
                        FROM run r JOIN session s ON r.session_id = s.session_id WHERE r.run_id = ?
                        """)) {
            query.setString(1, location.substring(location.lastIndexOf('/') + 1));
            try (var row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(2)).isEqualTo(tenant);
                assertThat(row.getString(3)).isEqualTo(owner);
                assertThat(row.getString(4)).isEqualTo("user");
                assertThat(row.getString(5)).isEqualTo(tenant);
                assertThat(row.getString(6)).isEqualTo(owner);
                assertThat(row.getString(7)).isEqualTo("user");
                return row.getString(1);
            }
        }
    }

    @Test
    void ordinaryUserCannotMutateGlobalConfiguration() throws Exception {
        DeerFlowBootstrapApi api = start(dataDirectory, ORIGIN);
        try {
            URI base = base(api);
            Session admin = Session.from(send(base, "POST", "/api/v1/auth/initialize",
                    "{\"email\":\"admin@example.com\",\"password\":\"" + PHRASE + "\",\"remember_me\":true}",
                    Map.of("Content-Type", "application/json", "Origin", ORIGIN)));
            Session user = Session.from(send(base, "POST", "/api/v1/auth/register",
                    "{\"email\":\"user@example.com\",\"password\":\"" + PHRASE + "\"}",
                    Map.of("Content-Type", "application/json", "Origin", ORIGIN)));
            assertThat(send(base, "POST", "/api/agents", "{}", user.jsonHeaders()).statusCode()).isEqualTo(403);
            assertThat(send(base, "PUT", "/api/managed-models", "{}", user.jsonHeaders()).statusCode()).isEqualTo(403);
            assertThat(send(base, "PATCH", "/api/mcp/config", "{}", user.jsonHeaders()).statusCode()).isEqualTo(403);
            assertThat(send(base, "POST", "/api/skills/install", "{}", user.jsonHeaders()).statusCode()).isEqualTo(403);
            assertThat(send(base, "GET", "/api/models", null, user.headers()).statusCode()).isNotEqualTo(403);
            assertThat(send(base, "POST", "/api/agents", "{}", admin.jsonHeaders()).statusCode()).isNotEqualTo(403);

            Map<String, String> preferences = user.jsonHeaders();
            preferences.put("X-Expected-User-Id", user.userId);
            assertThat(send(base, "PATCH", "/api/v1/auth/preferences", "{\"mode\":\"flash\"}", preferences).statusCode())
                    .isEqualTo(200);
            assertThat(send(base, "PATCH", "/api/v1/auth/preferences", "{\"model_name\":\"demo\"}", preferences).statusCode())
                    .isEqualTo(200);
            JsonNode saved = json.readTree(send(base, "GET", "/api/v1/auth/preferences", null, preferences).body());
            assertThat(saved.get("mode").asText()).isEqualTo("flash");
            assertThat(saved.get("model_name").asText()).isEqualTo("demo");
        } finally {
            api.close();
        }
    }

    @Test
    void reviewGapsStayClosedForSearchOwnerToolsPasswordsAndLogin() throws Exception {
        DeerFlowBootstrapApi api = start(dataDirectory, ORIGIN);
        try {
            URI base = base(api);
            HttpResponse<String> common = send(base, "POST", "/api/v1/auth/initialize",
                    "{\"email\":\"admin@example.com\",\"password\":\"welcome123\"}",
                    Map.of("Content-Type", "application/json", "Origin", ORIGIN));
            assertThat(common.statusCode()).isEqualTo(400);
            assertThat(common.body()).contains("Password is too common");

            Session admin = Session.from(send(base, "POST", "/api/v1/auth/initialize",
                    "{\"email\":\"admin@example.com\",\"password\":\"" + PHRASE + "\",\"remember_me\":true}",
                    Map.of("Content-Type", "application/json", "Origin", ORIGIN)));
            Session user = Session.from(send(base, "POST", "/api/v1/auth/register",
                    "{\"email\":\"user@example.com\",\"password\":\"" + PHRASE + "\"}",
                    Map.of("Content-Type", "application/json", "Origin", ORIGIN)));
            HttpResponse<String> created = send(base, "POST", "/api/threads",
                    "{\"thread_id\":\"search\",\"metadata\":{\"title\":\"owner-only-marker\"}}", admin.jsonHeaders());
            assertThat(created.statusCode()).isEqualTo(200);
            HttpResponse<String> foreign = send(base, "GET", "/api/threads/search", null, user.headers());
            assertThat(foreign.statusCode()).isEqualTo(404);
            assertThat(foreign.body()).doesNotContain("owner-only-marker");
            assertThat(send(base, "GET", "/api/threads/search/state", null, user.headers()).statusCode()).isEqualTo(404);
            assertThat(send(base, "GET", "/api/threads/search", null, admin.headers()).statusCode()).isEqualTo(200);
            HttpResponse<String> search = send(base, "POST", "/api/threads/search", "{}", user.jsonHeaders());
            assertThat(search.statusCode()).isEqualTo(200);
            assertThat(search.body()).doesNotContain("owner-only-marker");

            var field = DeerFlowBootstrapApi.class.getDeclaredField("configurationApi");
            field.setAccessible(true);
            var configuration = (DeerFlowConfigurationApi) field.get(api);
            var mutations = new java.util.concurrent.atomic.AtomicInteger();
            var tool = new ProductionSetupAgentTool("probe-agent", input -> {
                mutations.incrementAndGet();
                configuration.setupAgentForTool("probe-agent", input, candidate -> {});
                return new ProductionSetupAgentTool.Output(true, "created");
            });
            var context = new JavaToolContext(
                    new AgentRunId("probe-run"), Optional.of(new AgentSessionId("probe-session")),
                    new ToolCallId("probe-tool"), new TenantRef("public"),
                    new PrincipalRef("default-public-user", "user"), Instant.now().plusSeconds(15),
                    Optional.empty(), () -> false, Map.of());
            AccessOwner.bind(user.userId, "user");
            try {
                var result = tool.invoke(
                        new ProductionSetupAgentTool.Input("Safe component probe soul", "", Optional.empty()), context);
                assertThat(result.success()).isFalse();
                assertThat(mutations).hasValue(0);
            } finally {
                AccessOwner.clear();
            }
            assertThat(send(base, "GET", "/api/agents/probe-agent", null, admin.headers()).statusCode()).isEqualTo(404);

            var authField = DeerFlowBootstrapApi.class.getDeclaredField("localAuth");
            authField.setAccessible(true);
            var auth = (LocalAuthService) authField.get(api);
            assertThat(auth.allowsGlobalConfigMutation(user.userId)).isFalse();
            assertThat(auth.allowsGlobalConfigMutation(admin.userId)).isTrue();

            var projects = new PersistentProjectWorkspaceService(
                    dataDirectory.resolve("review-workspace"), WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
            var workspace = new RestrictedWorkspaceService(
                    dataDirectory.resolve("review-workspace"), WorkspaceLimits.defaults());
            var binding = projects.bindThreadWorkspace(admin.userId, "search");
            workspace.upload(admin.userId, binding.workspaceId(), "probe.skill", new byte[] {1, 2, 3},
                    "application/octet-stream");
            String failure;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                failure = executor.submit(() -> {
                    try {
                        DeerFlowWorkspaceApi.readSkillReviewTarget(
                                projects, workspace, "search", "/mnt/user-data/uploads/probe.skill");
                        return "NONE";
                    } catch (Exception ex) {
                        return ex.getClass().getSimpleName() + ":" + ex.getMessage();
                    }
                }).get();
            }
            assertThat(failure).doesNotContain("binding does not exist");
        } finally {
            api.close();
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
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(30));
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
            return from(response, new ObjectMapper().readTree(response.body()).get("id").asText());
        }

        static Session from(HttpResponse<String> response, String id) {
            String access = value(cookie(response, "access_token"));
            String csrf = value(cookie(response, "csrf_token"));
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

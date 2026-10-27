package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.superharness.server.auth.AccessOwner;
import io.haifa.agent.superharness.server.config.SkillConfigStore;
import io.haifa.agent.superharness.server.integrations.lark.LarkIntegrationService;
import io.haifa.agent.superharness.server.integrations.lark.LarkSkillPackInstaller;
import io.haifa.agent.superharness.server.testsupport.LarkIntegrationFakes;
import io.haifa.agent.superharness.server.testsupport.LarkIntegrationFakes.FakeArchives;
import io.haifa.agent.superharness.server.testsupport.LarkIntegrationFakes.FakeCli;
import io.haifa.agent.superharness.server.testsupport.LarkIntegrationFakes.FakeRegistration;
import io.haifa.agent.superharness.server.testsupport.LarkIntegrationFakes.VirtualClock;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** HTTP contract for the seven frozen Lark integration routes. */
class DeerFlowLarkIntegrationsApiTest {
    private static final String ADMIN_REQUIRED = "Admin privileges required to install integrations.";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @TempDir
    Path dataDirectory;

    private HttpServer server;
    private URI base;
    private FakeCli cli;
    private FakeRegistration registration;
    private FakeArchives archives;
    private VirtualClock clock;
    private LarkIntegrationService service;

    @BeforeEach
    void start() throws Exception {
        SkillConfigStore skills = new SkillConfigStore(json, dataDirectory,
                SkillConfigStore.repositoryRootFromWorkingDirectory());
        cli = new FakeCli(false);
        registration = new FakeRegistration();
        Path archive = LarkIntegrationFakes.archive(dataDirectory.resolve("fixture.zip"), "v1.0.65",
                LarkIntegrationFakes.allNames(), null);
        archives = new FakeArchives(archive);
        clock = new VirtualClock();
        service = new LarkIntegrationService(json, dataDirectory, skills,
                new LarkSkillPackInstaller(json, dataDirectory.resolve("integrations").resolve("lark")),
                cli, registration, archives, clock::nanoTime, clock::sleep);
        DeerFlowLarkIntegrationsApi api = new DeerFlowLarkIntegrationsApi(json, service);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> {
            String owner = exchange.getRequestHeaders().getFirst("X-Test-Owner");
            String role = exchange.getRequestHeaders().getFirst("X-Test-Role");
            try {
                if (owner != null) {
                    AccessOwner.bind(owner, role);
                } else {
                    AccessOwner.bind(AccessOwner.DEFAULT, "admin");
                }
                api.handle(exchange);
            } finally {
                AccessOwner.clear();
            }
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void statusReturnsTheFrozenShapeAndRedactsHostPathsForOrdinaryUsers() throws Exception {
        cli.makeAvailable("1.0.65");
        JsonNode user = json.readTree(request("GET", "/api/integrations/lark/status", null, "alice", "user").body());
        assertThat(user.path("installed").asBoolean()).isFalse();
        assertThat(user.path("version").asText()).isEqualTo("v1.0.65");
        assertThat(user.path("manifest_version").isNull()).isTrue();
        assertThat(user.has("latest_available_version")).isTrue();
        assertThat(user.path("runtime_version_mismatch").asBoolean()).isFalse();
        assertThat(user.path("app_configured").asBoolean()).isFalse();
        assertThat(user.has("app_id")).isTrue();
        assertThat(user.has("app_brand")).isTrue();
        assertThat(user.path("skills_expected").asInt()).isEqualTo(27);
        assertThat(user.path("skills_installed").asInt()).isZero();
        assertThat(user.path("installed_skills").isArray()).isTrue();
        assertThat(user.path("enabled_skills").isArray()).isTrue();
        assertThat(user.path("install_path").asText()).isEmpty();
        assertThat(user.path("cli").path("available").asBoolean()).isTrue();
        assertThat(user.path("cli").path("path").isNull()).isTrue();
        assertThat(user.path("cli").has("version")).isTrue();
        assertThat(user.path("cli").has("error")).isTrue();
        assertThat(user.path("auth").path("status").asText()).isEqualTo("not_configured");
        assertThat(user.path("auth").path("verified").asBoolean()).isFalse();
        assertThat(user.path("sandbox_runtime_mode").asText()).isEqualTo("none");
        assertThat(user.path("sandbox_runtime_ready").asBoolean()).isFalse();
        assertThat(user.path("sandbox_runtime_detail").asText()).isNotBlank();

        JsonNode admin = json.readTree(request("GET", "/api/integrations/lark/status", null, null, null).body());
        assertThat(admin.path("cli").path("path").asText()).isEqualTo("C:/fake/lark-cli.cmd");
        assertThat(admin.path("install_path").asText()).contains("integrations");
    }

    @Test
    void installRequiresAdminAndReinstallsIdempotently() throws Exception {
        HttpResponse<String> denied = request("POST", "/api/integrations/lark/install", null, "alice", "user");
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(json.readTree(denied.body()).path("detail").asText()).isEqualTo(ADMIN_REQUIRED);

        HttpResponse<String> installed = request("POST", "/api/integrations/lark/install", null, "alice", "admin");
        assertThat(installed.statusCode()).as(installed.body()).isEqualTo(200);
        JsonNode body = json.readTree(installed.body());
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("installed_skills")).hasSize(27);
        assertThat(body.path("message").asText()).isEqualTo("Installed 27 Lark/Feishu skills.");
        assertThat(body.path("status").path("installed").asBoolean()).isTrue();
        assertThat(body.path("status").path("skills_installed").asInt()).isEqualTo(27);
        assertThat(body.path("status").path("manifest_version").asText()).isEqualTo("v1.0.65");

        HttpResponse<String> again = request("POST", "/api/integrations/lark/install", null, "alice", "admin");
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(json.readTree(again.body()).path("installed_skills")).hasSize(27);
    }

    @Test
    void selfServiceFlowsMapFrozenStatusesAndErrors() throws Exception {
        HttpResponse<String> started = request("POST", "/api/integrations/lark/config/start", "{}", "alice", "user");
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        JsonNode start = json.readTree(started.body());
        assertThat(start.path("device_code").asText()).isEqualTo("DEVICE-CODE");
        assertThat(start.path("generation").asText()).isNotBlank();
        assertThat(start.path("brand").asText()).isEqualTo("feishu");
        assertThat(start.path("verification_url").asText()).contains("open.feishu.cn/page/cli");
        assertThat(start.path("expires_in").asInt()).isEqualTo(600);
        assertThat(start.path("interval").asInt()).isEqualTo(5);
        assertThat(start.path("user_code").asText()).isEqualTo("USER-CODE");
        String generation = start.path("generation").asText();

        HttpResponse<String> crossUser = request("POST", "/api/integrations/lark/config/complete",
                "{\"device_code\":\"DEVICE-CODE\",\"generation\":\"" + generation + "\",\"brand\":\"feishu\"}",
                "bob", "user");
        assertThat(crossUser.statusCode()).isEqualTo(409);
        assertThat(json.readTree(crossUser.body()).path("detail").asText())
                .isEqualTo("This Lark integration flow was superseded by a newer action.");

        HttpResponse<String> missingDevice = request("POST", "/api/integrations/lark/config/complete",
                "{\"generation\":\"" + generation + "\"}", "alice", "user");
        assertThat(missingDevice.statusCode()).isEqualTo(422);

        HttpResponse<String> wrongType = request("POST", "/api/integrations/lark/config/complete",
                "{\"device_code\":5,\"generation\":\"" + generation + "\"}", "alice", "user");
        assertThat(wrongType.statusCode()).isEqualTo(422);

        HttpResponse<String> unsafeScope = request("POST", "/api/integrations/lark/auth/start",
                "{\"scope\":\"docx:document; rm -rf /\"}", "alice", "user");
        assertThat(unsafeScope.statusCode()).isEqualTo(400);
        assertThat(json.readTree(unsafeScope.body()).path("detail").asText()).contains("scope");

        HttpResponse<String> badWait = request("POST", "/api/integrations/lark/auth/complete",
                "{\"device_code\":\"D\",\"generation\":\"" + generation + "\",\"wait_timeout_seconds\":4}",
                "alice", "user");
        assertThat(badWait.statusCode()).isEqualTo(422);

        assertThat(request("GET", "/api/integrations/lark/install", null, "alice", "admin").statusCode())
                .isEqualTo(405);
        assertThat(request("POST", "/api/integrations/lark/unknown", "{}", "alice", "user").statusCode())
                .isEqualTo(404);
        assertThat(request("POST", "/api/integrations/lark/config/start", "[]", "alice", "user").statusCode())
                .isEqualTo(422);
        assertThat(request("GET", "/api/integrations/lark/status/extra", null, "alice", "user").statusCode())
                .isEqualTo(404);
    }

    @Test
    void pendingRegistrationMapsTo504AndMissingCliMapsTo404() throws Exception {
        registration.pending(1_000);
        JsonNode started = json.readTree(request("POST", "/api/integrations/lark/config/start", "{}", "alice", "user").body());
        String generation = started.path("generation").asText();
        HttpResponse<String> timeout = request("POST", "/api/integrations/lark/config/complete",
                "{\"device_code\":\"DEVICE-CODE\",\"generation\":\"" + generation + "\"}", "alice", "user");
        assertThat(timeout.statusCode()).as(timeout.body()).isEqualTo(504);

        HttpResponse<String> missingCli = request("POST", "/api/integrations/lark/auth/start", "{}", "alice", "user");
        assertThat(missingCli.statusCode()).isEqualTo(404);
        assertThat(json.readTree(missingCli.body()).path("detail").asText())
                .contains("lark-cli is not installed on the Gateway");
    }

    @Test
    void credentialsSwitchReturnsStatusWithoutEchoingTheSecret() throws Exception {
        cli.makeAvailable("1.0.65");
        JsonNode start = json.readTree(request("POST", "/api/integrations/lark/config/start", "{}", "alice", "user").body());
        String generation = start.path("generation").asText();
        registration.approved("cli_alice", "server-side-secret", "feishu");
        HttpResponse<String> completed = request("POST", "/api/integrations/lark/config/complete",
                "{\"device_code\":\"DEVICE-CODE\",\"generation\":\"" + generation + "\"}", "alice", "user");
        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(200);
        assertThat(completed.body()).doesNotContain("server-side-secret");
        JsonNode completedBody = json.readTree(completed.body());
        assertThat(completedBody.path("status").path("install_path").asText()).isEmpty();
        assertThat(completedBody.path("status").path("cli").path("path").isNull()).isTrue();

        HttpResponse<String> switched = request("POST", "/api/integrations/lark/config/credentials",
                "{\"app_id\":\"cli_new\",\"app_secret\":\"another-secret\",\"brand\":\"feishu\"}",
                "alice", "user");
        assertThat(switched.statusCode()).as(switched.body()).isEqualTo(200);
        assertThat(switched.body()).doesNotContain("another-secret");
        JsonNode body = json.readTree(switched.body());
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("generation").asText()).isNotBlank();
        assertThat(body.path("generation").asText()).isNotEqualTo(generation);
        assertThat(body.path("status").path("app_id").asText()).isEqualTo("cli_new");
        assertThat(body.path("status").path("app_configured").asBoolean()).isTrue();
        assertThat(body.path("status").path("install_path").asText()).isEmpty();
        assertThat(body.path("status").path("cli").path("path").isNull()).isTrue();

        HttpResponse<String> invalidBrand = request("POST", "/api/integrations/lark/config/credentials",
                "{\"app_id\":\"cli_new\",\"app_secret\":\"another-secret\",\"brand\":\"other\"}",
                "alice", "user");
        assertThat(invalidBrand.statusCode()).isEqualTo(422);
    }

    private HttpResponse<String> request(String method, String path, String body, String owner, String role)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(10));
        if (owner != null) {
            builder.header("X-Test-Owner", owner);
        }
        if (role != null) {
            builder.header("X-Test-Role", role);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}

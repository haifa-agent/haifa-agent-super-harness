package io.haifa.agent.superharness.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.server.api.DeerFlowBootstrapApi;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** M2-12: real SDK discovery and production REST with distinct source categories. */
class V2SkillSourceCategoryTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void publicUpstreamAndCustomTestFixtureHaveIndependentRestAttribution() throws Exception {
        Path sourceRepository = SkillConfigStore.repositoryRootFromWorkingDirectory();
        Path data = isolatedDirectory("w15.category.dataRoot", "data-");
        Path evidence = isolatedDirectory("w15.category.evidenceRoot", "http-");
        Path repository = data.resolve("repository");
        ObjectNode manifest = (ObjectNode) json.readTree(
                sourceRepository.resolve("third-party/deerflow/skills/manifest.json").toFile());
        JsonNode upstream = manifest.path("packages").get(0);
        manifest.set("packages", json.createArrayNode().add(upstream.deepCopy()));
        assertThat(upstream.path("name").asText()).isEqualTo("consulting-analysis");
        assertThat(upstream.path("category").asText()).isEqualTo("public");
        for (var file : upstream.path("files").properties()) {
            copy(sourceRepository, repository, upstream.path("root").asText() + "/" + file.getKey());
        }
        copy(sourceRepository, repository, upstream.path("license_file").asText());

        String fixtureRoot = "third-party/deerflow/skills/v2-test-fixture/custom";
        byte[] fixtureBytes = Files.readAllBytes(sourceRepository.resolve(
                "contracts/customer-demo-v2/fixtures/skills/v2-format-control/SKILL.md"));
        Path fixture = repository.resolve(fixtureRoot + "/v2-format-control/SKILL.md");
        Files.createDirectories(fixture.getParent());
        Files.write(fixture, fixtureBytes);
        String fixtureHash = sha256(fixtureBytes);
        String fixtureLicense = "third-party/deerflow/LICENSES/v2-test-fixture.txt";
        byte[] licenseBytes = "TEST-FIXTURE-ONLY: V2 contract fixture; not an upstream DeerFlow resource.\n"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(repository.resolve(fixtureLicense), licenseBytes);
        ObjectNode custom = manifest.withArray("packages").addObject();
        custom.put("name", "v2-format-control");
        custom.put("category", "custom");
        custom.put("license", "TEST-FIXTURE-ONLY");
        custom.put("license_file", fixtureLicense);
        custom.put("license_sha256", sha256(licenseBytes));
        custom.put("source", "V2 contract fixture");
        custom.put("source_sha", "fixture-v2");
        custom.put("version", "sha256:" + fixtureHash);
        custom.put("active", true);
        custom.put("enabled_by_default", true);
        custom.put("root", fixtureRoot);
        custom.putObject("files").put("v2-format-control/SKILL.md", fixtureHash);
        custom.put("parser", "strict");
        Path manifestPath = repository.resolve("third-party/deerflow/skills/manifest.json");
        Files.write(manifestPath, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));

        var store = new SkillConfigStore(json, data.resolve("state"), repository);
        var catalog = store.projection(SkillRunTools.Selection.all()).catalog().snapshot();
        assertThat(catalog.bindings()).hasSize(2);
        assertThat(catalog.bindings().stream().map(binding -> binding.coordinate().source().sourceId()))
                .containsExactlyInAnyOrder("consulting-analysis", "v2-format-control");
        assertThat(store.projection(SkillRunTools.Selection.all()).allowedSkills())
                .containsExactlyInAnyOrder("consulting-analysis", "v2-format-control");

        int port = Integer.getInteger("w15.category.port", 0);
        var api = DeerFlowBootstrapApi.createForSkillTests(
                new LocalServerConfiguration("local", InetAddress.getLoopbackAddress(), port),
                data.resolve("state"), new SecretKeySpec(new byte[32], "AES"),
                ignored -> { throw new AssertionError("M2-12 discovery must not invoke a model"); },
                List.of(), store);
        Path httpLog = evidence.resolve("responses.raw.log");
        System.out.println("W15_CATEGORY_DATA=" + data);
        System.out.println("W15_CATEGORY_MANIFEST=" + manifestPath);
        System.out.println("W15_CATEGORY_HTTP=" + httpLog);
        api.start();
        try (api) {
            URI base = URI.create("http://127.0.0.1:" + api.address().getPort());
            JsonNode skills = request(base, "GET", "/api/skills", null, httpLog).path("skills");
            assertThat(skills).hasSize(2);
            JsonNode consulting = request(base, "GET", "/api/skills/consulting-analysis", null, httpLog);
            JsonNode control = request(base, "GET", "/api/skills/v2-format-control", null, httpLog);
            assertAttribution(consulting, "public", "bytedance/deer-flow", "MIT",
                    upstream.path("files").path("consulting-analysis/SKILL.md").asText());
            assertAttribution(control, "custom", "V2 contract fixture", "TEST-FIXTURE-ONLY", fixtureHash);
            assertThat(consulting.path("category")).isNotEqualTo(control.path("category"));
            assertThat(consulting.path("source")).isNotEqualTo(control.path("source"));
            assertThat(consulting.path("source_sha").asText())
                    .isEqualTo("e545c28ac301e6f9feae247c513d8ea8f83df294");
            assertThat(control.path("description").asText()).contains("NOT an upstream DeerFlow resource");
            assertThat(control.path("source_sha").asText()).isEqualTo("fixture-v2");
            assertThat(control.path("version").asText()).isEqualTo("sha256:" + fixtureHash);
            for (JsonNode skill : skills) {
                assertThat(skill).isEqualTo(skill.path("name").asText().equals("consulting-analysis")
                        ? consulting : control);
            }

            JsonNode disabled = request(base, "PUT", "/api/skills/v2-format-control",
                    "{\"enabled\":false}", httpLog);
            assertThat(disabled.path("enabled").asBoolean()).isFalse();
            assertThat(disabled.path("category").asText()).isEqualTo("custom");
            assertThat(request(base, "GET", "/api/skills/consulting-analysis", null, httpLog))
                    .isEqualTo(consulting);
            assertThat(store.projection(SkillRunTools.Selection.all()).allowedSkills())
                    .containsExactly("consulting-analysis");
            System.out.println("W15_CATEGORY_EVIDENCE case=V2-M2-12 categories=public,custom"
                    + " sources=bytedance/deer-flow,V2-contract-fixture models=0 PASS");
        }
    }

    private Path isolatedDirectory(String property, String prefix) throws Exception {
        Path root = Path.of(System.getProperty(property, temporaryDirectory.toString()));
        Files.createDirectories(root);
        return Files.createTempDirectory(root, prefix);
    }

    private static void copy(Path source, Path destination, String relative) throws Exception {
        Path target = destination.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.copy(source.resolve(relative), target);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void assertAttribution(
            JsonNode skill, String category, String source, String license, String hash) {
        assertThat(skill.path("category").asText()).isEqualTo(category);
        assertThat(skill.path("source").asText()).isEqualTo(source);
        assertThat(skill.path("license").asText()).isEqualTo(license);
        assertThat(skill.path("sha256").asText()).isEqualTo(hash);
        assertThat(skill.path("enabled").asBoolean()).isTrue();
    }

    private JsonNode request(URI base, String method, String path, String body, Path log) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10));
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        var response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
        String raw = method + " " + path + " HTTP " + response.statusCode() + "\n" + response.body() + "\n";
        Files.writeString(log, raw, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println("W15_CATEGORY_HTTP_RESPONSE " + raw);
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }
}

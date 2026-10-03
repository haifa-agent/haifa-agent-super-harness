package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.superharness.server.config.*;
import io.haifa.agent.superharness.server.testsupport.*;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Production REST assembly with a controlled write-screen decision; no static stand-in for HTTP. */
class SkillPackageHttpTest {
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper();
    private static String markdown(String text) {
        return "---\nname: package-control\ndescription: A controlled package for contract tests\nlicense: MIT\n---\n" + text + "\n";
    }

    @Test void uploadEditExportHistoryRollbackDeleteUseFrozenCustomRoutes() throws Exception {
        Path data = temporary.resolve("data");
        var store = new SkillConfigStore(json, data, SkillConfigStore.repositoryRootFromWorkingDirectory());
        store.configureWriteScreening((directory, files) -> json.createObjectNode().put("decision", "allow"));
        var api = DeerFlowBootstrapApi.createForSkillTests(new LocalServerConfiguration("local", InetAddress.getLoopbackAddress(),
                Integer.getInteger("w15.package.port", 0)), data, new SecretKeySpec(new byte[32], "AES"),
                ignored -> LocalProbeModelTestSupport.binding(new ControlledModel()), List.of(), store);
        try (var fixture = new GatewayFixture(data, api, temporary.resolve("evidence"))) {
            byte[] archive = SkillPackageFiles.export("package-control", Map.of("SKILL.md", markdown("Original").getBytes(StandardCharsets.UTF_8),
                    "references/fact.txt", "stable fact".getBytes(StandardCharsets.UTF_8)));
            String boundary = "w15-boundary";
            var multipart = new ByteArrayOutputStream();
            multipart.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"archive\"; filename=\"package-control.skill\"\r\n"
                    + "Content-Type: application/zip\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            multipart.write(archive); multipart.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            var uploaded = fixture.client.send(HttpRequest.newBuilder(fixture.base.resolve("/api/skills/install/upload"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(uploaded.statusCode()).isEqualTo(200);
            assertThat(json.readTree(uploaded.body()).path("skill_name").asText()).isEqualTo("package-control");
            byte[] publishedConfiguration = java.nio.file.Files.readAllBytes(data.resolve("skills_config.json"));
            var duplicate = fixture.client.send(HttpRequest.newBuilder(fixture.base.resolve("/api/skills/install/upload"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(duplicate.statusCode()).isEqualTo(409);
            assertThat(fixture.request("POST", "/api/skills/custom/missing-package/rollback",
                    json.createObjectNode().put("history_index", -1)).statusCode()).isEqualTo(404);
            assertThat(java.nio.file.Files.readAllBytes(data.resolve("skills_config.json"))).containsExactly(publishedConfiguration);
            String prefix = "/api/skills/custom/package-control";
            for (String invalid : List.of("{", "", "[]")) {
                for (var route : List.of(Map.entry("/api/skills/install", "POST"), Map.entry(prefix, "PUT"),
                        Map.entry(prefix + "/rollback", "POST"))) {
                    var malformed = fixture.client.send(HttpRequest.newBuilder(fixture.base.resolve(route.getKey()))
                            .header("Content-Type", "application/json")
                            .method(route.getValue(), HttpRequest.BodyPublishers.ofString(invalid)).build(), HttpResponse.BodyHandlers.ofString());
                    assertThat(malformed.statusCode()).as(route + " body=" + invalid).isEqualTo(422);
                }
            }
            assertThat(fixture.request("POST", prefix + "/rollback", json.createObjectNode().put("history_index", "last")).statusCode()).isEqualTo(422);
            assertThat(fixture.request("PUT", prefix, json.createObjectNode().put("content", 3)).statusCode()).isEqualTo(422);
            assertThat(fixture.request("POST", "/api/skills/install", json.createObjectNode().put("thread_id", 3).put("path", "x.skill")).statusCode()).isEqualTo(422);
            var oversized = fixture.client.send(HttpRequest.newBuilder(fixture.base.resolve("/api/skills/install"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("x".repeat(8193))).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(oversized.statusCode()).isEqualTo(413);
            assertThat(java.nio.file.Files.readAllBytes(data.resolve("skills_config.json"))).containsExactly(publishedConfiguration);
            assertThat(fixture.get("/api/skills/custom").path("skills")).hasSize(1);
            String revision = fixture.get(prefix + "/export-manifest").path("revision").asText();
            var exported = fixture.client.send(HttpRequest.newBuilder(fixture.base.resolve(prefix + "/export?expected_revision=" + revision))
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(exported.statusCode()).isEqualTo(200);
            assertThat(SkillPackageFiles.archive(exported.body()).get("references/fact.txt")).containsExactly("stable fact".getBytes(StandardCharsets.UTF_8));
            assertThat(fixture.request("PUT", prefix, json.createObjectNode().put("content", markdown("Edited"))).statusCode()).isEqualTo(200);
            var stale = fixture.request("GET", prefix + "/export?expected_revision=" + revision, null);
            assertThat(stale.statusCode()).isEqualTo(409);
            assertThat(json.readTree(stale.body()).path("detail").path("code").asText()).isEqualTo("skill_changed");
            assertThat(fixture.request("POST", prefix + "/rollback", json.createObjectNode().put("history_index", -1)).statusCode()).isEqualTo(200);
            assertThat(fixture.get(prefix).path("content").asText()).contains("Original").doesNotContain("Edited");
            assertThat(fixture.get(prefix + "/history").path("history")).hasSize(3);
            assertThat(fixture.request("DELETE", prefix, null).statusCode()).isEqualTo(200);
            assertThat(fixture.request("GET", prefix, null).statusCode()).isEqualTo(404);
            assertThat(fixture.get(prefix + "/history").path("history")).hasSize(4);
            assertThat(fixture.request("PUT", "/api/skills/custom/consulting-analysis", json.createObjectNode().put("content", markdown("Forbidden")))
                    .statusCode()).isEqualTo(404);
        }
    }
}

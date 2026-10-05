package io.haifa.agent.superharness.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.superharness.server.api.DeerFlowSkillsApi;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalSkillDirectoryTest {
    private final ObjectMapper json = new ObjectMapper();
    @TempDir Path temporary;

    private Path repository() throws Exception { return SkillConfigStore.repositoryRootFromWorkingDirectory(); }
    private Path packageAt(Path root, String directory, String name, String extra) throws Exception {
        Path target = root.resolve(directory); Files.createDirectories(target);
        Files.writeString(target.resolve("SKILL.md"), "---\nname: " + name
                + "\ndescription: Test directory Skill\n" + extra + "---\nUse the package instructions.\n");
        return target;
    }
    private Path packages() throws Exception {
        Path root = temporary.resolve("skills/public");
        packageAt(root, "bootstrap", "bootstrap", "");
        Path chart = packageAt(root, "chart-visualization", "chart-visualization", "compatibility:\n  nodejs: \">=18.0.0\"\n");
        Files.writeString(chart.resolve("SKILL.md"), Files.readString(chart.resolve("SKILL.md")).replace("\n", "\r\n"));
        Path alias = packageAt(root, "vercel-deploy-claimable", "vercel-deploy", "");
        for (Path scriptPackage : java.util.List.of(chart, alias)) {
            Files.createDirectories(scriptPackage.resolve("scripts"));
            Files.writeString(scriptPackage.resolve("scripts/test.py"), "print('example')\n");
        }
        for (int index = 3; index < 23; index++) packageAt(root, "sample-" + index, "sample-" + index, "");
        return root;
    }
    private SkillConfigStore store(Path root) throws Exception {
        return new SkillConfigStore(json, temporary.resolve("data"), repository(), root);
    }

    @Test void discoversPackagesWithoutPretendingScriptReviewPassed() throws Exception {
        Path root = packages();
        byte[] before = Files.readAllBytes(root.resolve("chart-visualization/SKILL.md"));
        var store = store(root.getParent());
        assertThat(store.list()).hasSize(23);
        assertThat(store.projection(SkillRunTools.Selection.all()).allowedSkills()).hasSize(21);
        assertThat(store.detail("chart-visualization").orElseThrow().get("enabled")).isEqualTo(false);
        assertThat(store.detail("bootstrap").orElseThrow().get("license")).isEqualTo("");
        assertThat(Files.readAllBytes(root.resolve("chart-visualization/SKILL.md"))).containsExactly(before);
        assertThatThrownBy(() -> store.setEnabled("chart-visualization", true)).isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("scanner is unavailable");
        assertThat(store.projection(SkillRunTools.Selection.all()).allowedSkills()).hasSize(21);
    }

    @Test void reviewedAliasAndCompatibilityPackageBindAndSurviveRestart() throws Exception {
        Path root = packages(); var store = store(root); AtomicInteger screens = new AtomicInteger();
        store.configureWriteScreening((directory, files) -> {
            screens.incrementAndGet(); assertThat(files).containsKeys("SKILL.md", "scripts/test.py");
            return json.createObjectNode().put("decision", "allow").put("reason", "Controlled test scanner");
        });
        store.setEnabled("chart-visualization", true); store.setEnabled("vercel-deploy", true);
        assertThat(screens).hasValue(2);
        assertThat(store.projection(SkillRunTools.Selection.all()).catalog().snapshot().bindings()).hasSize(23);
        assertThat(store.installedReviewFiles("public", "vercel-deploy")).containsKey("scripts/test.py");
        store.setEnabled("bootstrap", false);
        var restarted = store(root);
        assertThat(restarted.projection(SkillRunTools.Selection.all()).allowedSkills()).hasSize(22).doesNotContain("bootstrap");
        assertThat(restarted.projection(SkillRunTools.Selection.none()).allowedSkills()).isEmpty();
        assertThat(restarted.projection(SkillRunTools.Selection.of(java.util.Set.of("vercel-deploy"))).allowedSkills())
                .containsExactly("vercel-deploy");
    }

    @Test void failedReviewCannotPublishEnableState() throws Exception {
        var store = store(packages()); store.setEnabled("bootstrap", false);
        byte[] before = Files.readAllBytes(temporary.resolve("data/skills_config.json"));
        store.configureWriteScreening((directory, files) -> json.createObjectNode().put("decision", "block"));
        assertThatThrownBy(() -> store.setEnabled("vercel-deploy", true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readAllBytes(temporary.resolve("data/skills_config.json"))).containsExactly(before);
    }

    @Test void malformedOrDuplicatePackageCannotReplacePublishedCatalog() throws Exception {
        Path root = packages(); var store = store(root);
        var before = store.projection(SkillRunTools.Selection.all()).fingerprint();
        Path bad = packageAt(root, "invalid", "INVALID", "");
        assertThatThrownBy(store::reload).isInstanceOf(java.io.IOException.class).hasMessageContaining("failed parsing");
        assertThat(store.list()).hasSize(23);
        assertThat(store.projection(SkillRunTools.Selection.all()).fingerprint()).isEqualTo(before);
        Files.delete(bad.resolve("SKILL.md")); packageAt(root, "duplicate", "bootstrap", "");
        assertThatThrownBy(store::reload).isInstanceOf(java.io.IOException.class).hasMessageContaining("Duplicate");
        assertThat(store.list()).hasSize(23);
    }

    @Test void supportingFileChangesInvalidateVersionAndReview() throws Exception {
        Path root = packages(); var store = store(root);
        store.configureWriteScreening((directory, files) -> json.createObjectNode().put("decision", "allow"));
        store.setEnabled("chart-visualization", true);
        String version = store.detail("chart-visualization").orElseThrow().get("version").toString();
        Files.writeString(root.resolve("chart-visualization/scripts/test.py"), "print('changed')\n");
        assertThatThrownBy(store::reload).isInstanceOf(IllegalStateException.class).hasMessageContaining("catalog is incomplete");
        assertThat(store.detail("chart-visualization").orElseThrow().get("version")).isEqualTo(version);
    }

    @Test void changedDirectoryRetainsPinnedInstructionsAcrossReloadAndRestart() throws Exception {
        Path root = packages(); var store = store(root);
        var selection = SkillRunTools.Selection.of(java.util.Set.of("bootstrap"));
        var original = store.projection(selection).catalog().snapshot().bindings().getFirst();
        String version = store.detail("bootstrap").orElseThrow().get("version").toString();
        var versions = java.util.Map.of("bootstrap", "bootstrap@" + version);
        Files.writeString(root.resolve("bootstrap/SKILL.md"), Files.readString(root.resolve("bootstrap/SKILL.md"))
                .replace("Use the package instructions.", "Updated instructions."));
        store.reload(); store.setEnabled("bootstrap", false);
        var restarted = store(root);
        assertThat(restarted.recoveryProjection(selection, versions).catalog().snapshot().bindings()).containsExactly(original);
        assertThat(restarted.list()).hasSize(23);
        assertThat(restarted.projection(selection).allowedSkills()).isEmpty();
        Path retained = temporary.resolve("data/skills/external/.versions/bootstrap");
        try (var versionsOnDisk = Files.list(retained)) {
            Path old = versionsOnDisk.filter(path -> path.getFileName().toString().equals(version.substring(7)))
                    .findFirst().orElseThrow();
            Files.writeString(old.resolve("SKILL.md"), "tampered");
        }
        assertThatThrownBy(() -> store(root)).isInstanceOf(java.io.IOException.class).hasMessageContaining("Retained Skill bytes changed");
    }

    @Test void missingDirectoryNeverFallsBackAndNamesCannotEscape() throws Exception {
        Path root = packages(); var store = store(root);
        assertThatThrownBy(() -> store.reviewFiles("../bootstrap")).isInstanceOf(IllegalArgumentException.class);
        Files.move(root, root.resolveSibling("moved"));
        assertThatThrownBy(store::reload).isInstanceOf(java.io.IOException.class).hasMessageContaining("unavailable");
        assertThat(store.list()).hasSize(23);
        assertThatThrownBy(() -> store(root)).isInstanceOf(java.io.IOException.class);
    }

    @Test void configuredDirectoryPropertyIsExplicitAndInvalidPathFails() throws Exception {
        String key = "haifa.superharness.skills.dir"; String previous = System.getProperty(key);
        try {
            Path root = packages(); System.setProperty(key, root.toString());
            assertThat(SkillConfigStore.resolveExternalSkillsDirectory(repository())).isEqualTo(root);
            System.setProperty(key, temporary.resolve("missing").toString());
            assertThatThrownBy(() -> SkillConfigStore.resolveExternalSkillsDirectory(repository()))
                    .isInstanceOf(java.io.IOException.class).hasMessageContaining("unavailable");
        } finally { if (previous == null) System.clearProperty(key); else System.setProperty(key, previous); }
    }

    @Test void persistedDirectorySurvivesRestartAndRejectsRelativePaths() throws Exception {
        Path root = packages(); Path data = temporary.resolve("data"); Files.createDirectories(data);
        var config = json.createObjectNode().put("schema_version", 1).put("external_directory", root.toString());
        config.putObject("enabled"); Path file = data.resolve("skills_config.json");
        json.writeValue(file.toFile(), config);
        var first = new SkillConfigStore(json, data, repository()); first.setEnabled("bootstrap", false);
        var restarted = new SkillConfigStore(json, data, repository());
        assertThat(restarted.list()).hasSize(23);
        assertThat(restarted.detail("bootstrap").orElseThrow().get("enabled")).isEqualTo(false);
        config.put("external_directory", "relative/path"); json.writeValue(file.toFile(), config);
        assertThatThrownBy(() -> new SkillConfigStore(json, data, repository()))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("absolute");
    }

    @Test void realHttpListsTogglesAndReloadsDirectorySkills() throws Exception {
        var store = store(packages()); var api = new DeerFlowSkillsApi(json, store);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", api::handle);
        server.start(); URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        try {
            var response = request(base, "GET", "/api/skills", null);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.readTree(response.body()).path("skills").size()).isEqualTo(23);
            assertThat(request(base, "PUT", "/api/skills/bootstrap", "{\"enabled\":false}").statusCode()).isEqualTo(200);
            assertThat(request(base, "POST", "/api/skills/reload", "{}").statusCode()).isEqualTo(200);
            assertThat(json.readTree(request(base, "GET", "/api/skills/bootstrap", null).body()).path("enabled").asBoolean()).isFalse();
        } finally { server.stop(0); }
    }
    private HttpResponse<String> request(URI base, String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5));
        if (body == null) request.GET();
        else request.method(method, HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json");
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}

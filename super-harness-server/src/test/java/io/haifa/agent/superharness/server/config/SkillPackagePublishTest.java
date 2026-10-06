package io.haifa.agent.superharness.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.superharness.server.testsupport.DirectoryMonitor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class SkillPackagePublishTest {
    private final ObjectMapper json = new ObjectMapper();
    @TempDir
    Path data;

    private static byte[] markdown(String name, String text) {
        return ("---\nname: " + name + "\ndescription: A test package\nlicense: MIT\n---\n" + text + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private SkillConfigStore store(Path data) throws Exception {
        var store = new SkillConfigStore(json, data, SkillConfigStore.repositoryRootFromWorkingDirectory());
        store.configureWriteScreening((directory, files) -> json.createObjectNode().put("decision", "allow")
                .put("reason", "Controlled unit screening fixture"));
        return store;
    }

    private static void awaitOpenHandle(DirectoryMonitor monitor) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (monitor.openHandleCount() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(monitor.openHandleCount()).as("monitor must hold the freshly written file open").isPositive();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void publishingSucceedsWhenStagingFileIsHeldByAnotherProcess() throws Exception {
        var store = store(data);
        Path versionsRoot = data.resolve("skills/custom/.versions");
        Files.createDirectories(versionsRoot);
        byte[] pkgArchive = SkillPackageFiles.export("sample-helper", Map.of(
                "SKILL.md", markdown("sample-helper", "Instructions for held handle test"),
                "references/facts.txt", "known facts\n".getBytes(StandardCharsets.UTF_8)));
        String expectedRevision = SkillPackageFiles.digest(SkillPackageFiles.archivePackage(pkgArchive));

        try (var monitor = DirectoryMonitor.open(data, DirectoryMonitor.DEFAULT_FILE_HOLD)) {
            monitor.arm();
            store.configureWriteScreening((directory, files) -> {
                try {
                    awaitOpenHandle(monitor);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                return json.createObjectNode().put("decision", "allow");
            });

            String installedName = store.install(pkgArchive, "thread-repro");
            assertThat(installedName).isEqualTo("sample-helper");
        }

        Map<String, byte[]> published = publishedVersion("sample-helper", expectedRevision);
        assertThat(published.keySet()).containsExactlyInAnyOrder("SKILL.md", "references/facts.txt");
        assertThat(new String(published.get("SKILL.md"), StandardCharsets.UTF_8))
                .contains("Instructions for held handle test");
        assertThat(SkillPackageFiles.digest(SkillPackageFiles.PackageContent.filesOnly(published)))
                .isEqualTo(expectedRevision);
        assertThat(store.customDetail("sample-helper").get("content").toString())
                .contains("Instructions for held handle test");
    }

    /** Reads the version the configuration registers as current straight from its directory. */
    private Map<String, byte[]> publishedVersion(String name, String expectedRevision) throws IOException {
        String current = json.readTree(data.resolve("skills_config.json").toFile())
                .path("custom").path(name).path("current").asText();
        assertThat(current).as("registered current revision").isEqualTo(expectedRevision);
        return SkillPackageFiles.directory(data.resolve("skills/custom/.versions/" + name + "/" + current + "/" + name));
    }

    @Test
    void unregisteredVersionDirectoryOnDiskIsNeverTreatedAsValidVersion() throws Exception {
        var store = store(data);
        String fakeRevision = "a".repeat(64);
        Path fakeDir = data.resolve("skills/custom/.versions/sample-helper/" + fakeRevision + "/sample-helper");
        Files.createDirectories(fakeDir);
        Files.write(fakeDir.resolve("SKILL.md"), markdown("sample-helper", "Unregistered version"));

        assertThatThrownBy(() -> store.customDetail("sample-helper")).isInstanceOf(FileNotFoundException.class);
        assertThat(store.projection(SkillRunTools.Selection.all()).allowedSkills()).doesNotContain("sample-helper");
        assertThatThrownBy(() -> store.history("sample-helper")).isInstanceOf(FileNotFoundException.class);
        assertThatThrownBy(() -> store.exportArchive("sample-helper", fakeRevision)).isInstanceOf(Exception.class);
    }

    @Test
    void unregisteredResidualTargetDirectoryIsCleanedAndRepublishedSuccessfully() throws Exception {
        var store = store(data);
        byte[] pkgArchive = SkillPackageFiles.export("sample-helper", Map.of(
                "SKILL.md", markdown("sample-helper", "Cleaned residual directory test")));
        String expectedRevision = SkillPackageFiles.digest(SkillPackageFiles.archivePackage(pkgArchive));

        // Simulate an interrupted previous attempt that left an incomplete target directory without config registration
        Path targetResidual = data.resolve("skills/custom/.versions/sample-helper/" + expectedRevision + "/sample-helper");
        Files.createDirectories(targetResidual);
        Files.writeString(targetResidual.resolve("SKILL.md"), "corrupted partial file from aborted publish");

        // Publishing must succeed by safely cleaning residual and publishing fresh content
        String installed = store.install(pkgArchive, "thread-residual");
        assertThat(installed).isEqualTo("sample-helper");

        Map<String, byte[]> published = publishedVersion("sample-helper", expectedRevision);
        assertThat(published.keySet()).containsExactly("SKILL.md");
        assertThat(new String(published.get("SKILL.md"), StandardCharsets.UTF_8))
                .contains("Cleaned residual directory test");
        assertThat(SkillPackageFiles.digest(SkillPackageFiles.PackageContent.filesOnly(published)))
                .isEqualTo(expectedRevision);
    }

    @Test
    void registeredVersionTamperingIsRejectedAsImmutableViolation() throws Exception {
        var store = store(data);
        byte[] pkgArchive = SkillPackageFiles.export("sample-helper", Map.of(
                "SKILL.md", markdown("sample-helper", "Original immutable instructions")));
        String installed = store.install(pkgArchive, null);
        assertThat(installed).isEqualTo("sample-helper");

        String revision = json.readTree(data.resolve("skills_config.json").toFile()).path("custom").path("sample-helper").path("current").asText();
        Path installedSkillMd = data.resolve("skills/custom/.versions/sample-helper/" + revision + "/sample-helper/SKILL.md");
        Files.writeString(installedSkillMd, "tampered after valid registration");

        // Reload or republish of already-registered version must reject tampering
        assertThatThrownBy(store::reload).isInstanceOf(IOException.class).hasMessageContaining("hash mismatch");
    }
}

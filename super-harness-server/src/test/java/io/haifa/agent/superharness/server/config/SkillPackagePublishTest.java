package io.haifa.agent.superharness.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class SkillPackagePublishTest {
    /** Shorter than the bounded retry window of a single-file publish (about 0.85 s). */
    private static final Duration HOLD = Duration.ofMillis(200);

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

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void publishingSucceedsWhenStagingFileIsHeldByAnotherProcess() throws Exception {
        var store = store(data);
        byte[] pkgArchive = SkillPackageFiles.export("sample-helper", Map.of(
                "SKILL.md", markdown("sample-helper", "Instructions for held handle test"),
                "references/facts.txt", "known facts\n".getBytes(StandardCharsets.UTF_8)));
        String expectedRevision = SkillPackageFiles.digest(SkillPackageFiles.archivePackage(pkgArchive));
        AtomicReference<Throwable> renameWhileHeld = new AtomicReference<>();
        AtomicReference<Thread> releaser = new AtomicReference<>();

        // Screening runs on the fully written staging package right before it is published. Holding one
        // of its files the way another process would (no delete sharing) makes the whole-directory
        // rename fail; the handle is released inside the single-file retry window of the fallback.
        store.configureWriteScreening((directory, files) -> {
            try {
                FileInputStream held = new FileInputStream(directory.resolve("SKILL.md").toFile());
                Path staging = directory.getParent();
                Path probe = staging.resolveSibling(staging.getFileName() + ".probe");
                try {
                    Files.move(staging, probe, StandardCopyOption.ATOMIC_MOVE);
                    Files.move(probe, staging, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException denied) {
                    renameWhileHeld.set(denied);
                }
                Thread release = new Thread(() -> {
                    try {
                        Thread.sleep(HOLD.toMillis());
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        try {
                            held.close();
                        } catch (IOException ignored) {
                            // The assertions below fail if the handle was never released.
                        }
                    }
                }, "staging-file-holder");
                release.setDaemon(true);
                release.start();
                releaser.set(release);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            return json.createObjectNode().put("decision", "allow");
        });

        String installedName = store.install(pkgArchive, "thread-repro");
        releaser.get().join(Duration.ofSeconds(5).toMillis());

        assertThat(renameWhileHeld.get())
                .as("precondition: the staging directory cannot be renamed while one of its files is held")
                .isInstanceOf(IOException.class);
        assertThat(installedName).isEqualTo("sample-helper");
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

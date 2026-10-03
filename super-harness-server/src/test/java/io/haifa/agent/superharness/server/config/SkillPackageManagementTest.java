package io.haifa.agent.superharness.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillPackageManagementTest {
    private final ObjectMapper json = new ObjectMapper();
    @TempDir Path data;
    private static byte[] markdown(String text) {
        return ("---\nname: sample-helper\ndescription: A small package for tests\nlicense: MIT\n---\n" + text + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }
    private SkillConfigStore store() throws Exception {
        var store = new SkillConfigStore(json, data, SkillConfigStore.repositoryRootFromWorkingDirectory());
        store.configureWriteScreening((directory, files) -> json.createObjectNode().put("decision", "allow")
                .put("reason", "Controlled unit screening fixture"));
        return store;
    }
    private static Map<String, byte[]> currentFiles(SkillConfigStore store) throws Exception {
        String revision = store.exportManifest("sample-helper").get("revision").toString();
        return SkillPackageFiles.archive(store.exportArchive("sample-helper", revision));
    }

    @Test void completePackageSurvivesRestartAndExportRoundTrip() throws Exception {
        var store = store();
        Map<String, byte[]> source = Map.of("SKILL.md", markdown("Original instructions"), "references/facts.txt", "known facts".getBytes(),
                "scripts/helper.py", "print('example')\n".getBytes(), "assets/payload.bin", new byte[] {1, 2, 3});
        assertThat(store.install(SkillPackageFiles.export("sample-helper", source), "thread-one")).isEqualTo("sample-helper");
        assertThat(store.customDetail("sample-helper").get("editable")).isEqualTo(true);
        var restart = new SkillConfigStore(json, data, SkillConfigStore.repositoryRootFromWorkingDirectory());
        assertThat(restart.projection(SkillRunTools.Selection.all()).allowedSkills()).contains("sample-helper", "consulting-analysis");
        var manifest = restart.exportManifest("sample-helper");
        Map<String, byte[]> restored = SkillPackageFiles.archive(restart.exportArchive("sample-helper", manifest.get("revision").toString()));
        assertThat(restored.keySet()).isEqualTo(source.keySet());
        source.forEach((name, bytes) -> assertThat(restored.get(name)).containsExactly(bytes));
    }

    @Test void installedEntryMetadataSurvivesVersionStorageRestartAndExport() throws Exception {
        var store = store();
        var source = SkillPackageFiles.archivePackage(SkillPackageArchiveTest.unixMetadataArchive());
        store.install(SkillPackageArchiveTest.unixMetadataArchive(), null);
        String before = store.exportManifest("sample-helper").get("revision").toString();
        var restart = new SkillConfigStore(json, data, SkillConfigStore.repositoryRootFromWorkingDirectory());
        assertThat(restart.projection(SkillRunTools.Selection.all()).allowedSkills()).contains("sample-helper");
        var manifest = restart.exportManifest("sample-helper");
        assertThat(manifest.get("revision")).isEqualTo(before);
        assertThat(manifest.get("directory_count")).isEqualTo(4L);
        var restored = SkillPackageFiles.archivePackage(restart.exportArchive("sample-helper", before));
        assertThat(restored.emptyDirectories()).isEqualTo(source.emptyDirectories());
        assertThat(restored.executableFiles()).isEqualTo(source.executableFiles());
        source.files().forEach((path, bytes) -> assertThat(restored.files().get(path)).containsExactly(bytes));
    }

    @Test void editRollbackAndDeleteRetainHistoryWithoutRevivingOnReload() throws Exception {
        var store = store();
        store.manage("create", "sample-helper", new String(markdown("Original"), StandardCharsets.UTF_8), null, null, null, null, "agent", "thread-one");
        String revision = store.exportManifest("sample-helper").get("revision").toString();
        store.manage("edit", "sample-helper", new String(markdown("Edited"), StandardCharsets.UTF_8), null, null, null, null, "agent", "thread-two");
        assertThatThrownBy(() -> store.exportArchive("sample-helper", revision)).isInstanceOf(IllegalStateException.class).hasMessage("skill_changed");
        store.rollback("sample-helper", -1);
        assertThat(store.customDetail("sample-helper").get("content").toString()).contains("Original").doesNotContain("Edited");
        store.manage("delete", "sample-helper", null, null, null, null, null, "human", null);
        store.reload();
        assertThat(store.detail("sample-helper")).isEmpty();
        assertThat(store.history("sample-helper")).hasSize(4);
        assertThatThrownBy(() -> store.customDetail("sample-helper")).isInstanceOf(java.io.FileNotFoundException.class);
        assertThat(store.projection(SkillRunTools.Selection.all()).allowedSkills()).doesNotContain("sample-helper");
    }

    @Test void rollbackRestoresSupportingFileBytesAndRemovesNewFilesWithoutChangingInstructions() throws Exception {
        var store = store();
        byte[] instructions = markdown("Original");
        byte[] binary = new byte[] {0, 1, (byte) 0xff, 42};
        store.install(SkillPackageFiles.export("sample-helper", Map.of("SKILL.md", instructions,
                "references/fact.txt", "first".getBytes(StandardCharsets.UTF_8), "assets/data.bin", binary)), null);
        store.manage("write_file", "sample-helper", "second", "references/fact.txt", null, null, null, "agent", null);
        store.rollback("sample-helper", -1);
        assertThat(currentFiles(store).get("references/fact.txt")).containsExactly("first".getBytes(StandardCharsets.UTF_8));
        store.manage("remove_file", "sample-helper", null, "assets/data.bin", null, null, null, "agent", null);
        store.rollback("sample-helper", -1);
        assertThat(currentFiles(store).get("assets/data.bin")).containsExactly(binary);
        store.manage("write_file", "sample-helper", "new", "references/new.txt", null, null, null, "agent", null);
        store.rollback("sample-helper", -1);
        assertThat(currentFiles(store)).doesNotContainKey("references/new.txt");
        assertThat(currentFiles(store).get("SKILL.md")).containsExactly(instructions);
    }

    @Test void managedPathAliasesCannotPublishAnUninstallablePackage() throws Exception {
        var store = store();
        store.manage("create", "sample-helper", new String(markdown("Original"), StandardCharsets.UTF_8), null, null, null, null, "agent", null);
        store.manage("write_file", "sample-helper", "one", "references/Foo.txt", null, null, null, "agent", null);
        byte[] before = Files.readAllBytes(data.resolve("skills_config.json"));
        int history = store.history("sample-helper").size();
        assertThatThrownBy(() -> store.manage("write_file", "sample-helper", "two", "references/foo.txt", null, null, null, "agent", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate Skill package path");
        assertThat(Files.readAllBytes(data.resolve("skills_config.json"))).containsExactly(before);
        assertThat(store.history("sample-helper")).hasSize(history);
        assertThat(currentFiles(store)).containsKey("references/Foo.txt").doesNotContainKey("references/foo.txt");
        store.manage("write_file", "sample-helper", "one", "references/\u00e9.txt", null, null, null, "agent", null);
        before = Files.readAllBytes(data.resolve("skills_config.json"));
        assertThatThrownBy(() -> store.manage("write_file", "sample-helper", "two", "references/e\u0301.txt", null, null, null, "agent", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate Skill package path");
        assertThat(Files.readAllBytes(data.resolve("skills_config.json"))).containsExactly(before);
        store.manage("write_file", "sample-helper", "one", "templates/Folder/a.txt", null, null, null, "agent", null);
        before = Files.readAllBytes(data.resolve("skills_config.json"));
        assertThatThrownBy(() -> store.manage("write_file", "sample-helper", "two", "templates/folder/b.txt", null, null, null, "agent", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate Skill package path");
        assertThat(Files.readAllBytes(data.resolve("skills_config.json"))).containsExactly(before);
    }

    @Test void failedScanAndBadPatchDoNotPublishConfiguration() throws Exception {
        var store = store();
        store.manage("create", "sample-helper", new String(markdown("Original"), StandardCharsets.UTF_8), null, null, null, null, "agent", null);
        byte[] original = Files.readAllBytes(data.resolve("skills_config.json"));
        assertThatThrownBy(() -> store.manage("patch", "sample-helper", null, null, "missing", "replacement", 1, "agent", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readAllBytes(data.resolve("skills_config.json"))).containsExactly(original);
        store.configureWriteScreening((directory, files) -> { throw new java.io.IOException("Controlled scanner failure"); });
        assertThatThrownBy(() -> store.manage("edit", "sample-helper", new String(markdown("Rejected"), StandardCharsets.UTF_8), null, null, null, null, "agent", null))
                .isInstanceOf(java.io.IOException.class);
        assertThat(Files.readAllBytes(data.resolve("skills_config.json"))).containsExactly(original);
        assertThat(store.customDetail("sample-helper").get("content").toString()).contains("Original");
        try (var list = Files.list(data.resolve("skills/custom/.versions"))) {
            assertThat(list.noneMatch(path -> path.getFileName().toString().startsWith(".publishing-"))).isTrue();
        }
    }

    @Test void absentSemanticScannerFailsClosedAndUnsafeZipIsRejected() throws Exception {
        var store = new SkillConfigStore(json, data, SkillConfigStore.repositoryRootFromWorkingDirectory());
        byte[] archive = SkillPackageFiles.export("sample-helper", Map.of("SKILL.md", markdown("Original")));
        assertThatThrownBy(() -> store.install(archive, null)).isInstanceOf(java.io.IOException.class).hasMessageContaining("scanner is unavailable");
        assertThat(Files.exists(data.resolve("skills_config.json"))).isFalse();
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("../outside/SKILL.md")); zip.write(markdown("Bad")); zip.closeEntry();
        }
        assertThatThrownBy(() -> SkillPackageFiles.archive(bytes.toByteArray())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsafe");
        assertThat(Files.exists(data.resolve("outside"))).isFalse();
    }

    @Test void immutableUserVersionDetectsTamperingOnReload() throws Exception {
        var store = store(); store.install(SkillPackageFiles.export("sample-helper", Map.of("SKILL.md", markdown("Original"))), null);
        String revision = json.readTree(data.resolve("skills_config.json").toFile()).path("custom").path("sample-helper").path("current").asText();
        Files.writeString(data.resolve("skills/custom/.versions/sample-helper/" + revision + "/sample-helper/SKILL.md"), "tampered");
        assertThatThrownBy(store::reload).isInstanceOf(java.io.IOException.class).hasMessageContaining("hash mismatch");
    }

    @Test void currentAuthorityRejectsAnExistingForeignPackageOrRevokedAccessButNotOrdinaryDisable() throws Exception {
        var store = store();
        Map<String, byte[]> files = Map.of("SKILL.md", markdown("Original"), "scripts/helper.py", "print('example')\n".getBytes());
        store.install(SkillPackageFiles.export("sample-helper", files), null);
        var projection = store.projection(SkillRunTools.Selection.of(java.util.Set.of("sample-helper")));
        var binding = projection.catalog().snapshot().bindings().getFirst();
        var owner = SkillReviewApprovals.discovery().visibility();
        assertThat(projection.loader().load(binding, owner).instructions()).contains("Original");
        var foreign = new io.haifa.agent.skill.api.SkillVisibilityContext(owner.tenant(),
                new io.haifa.agent.core.reference.PrincipalRef("another-owner", "user"), java.util.Optional.empty(), false, owner.allowedScopes());
        assertThatThrownBy(() -> projection.loader().load(binding, foreign)).isInstanceOf(SecurityException.class);
        store.setEnabled("sample-helper", false);
        assertThat(projection.loader().load(binding, owner).instructions()).contains("Original");
        store.revokePackageReview("sample-helper");
        assertThatThrownBy(() -> projection.loader().load(binding, owner)).isInstanceOf(SecurityException.class).hasMessageContaining("revoked");
        assertThatThrownBy(() -> store.recoveryProjection(SkillRunTools.Selection.of(java.util.Set.of("sample-helper"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("incomplete");
    }

    @Test void creatorRequiresExactScreenBeforeEnablingAndKeepsItsCompleteResources() throws Exception {
        var store = new SkillConfigStore(json, data, SkillConfigStore.repositoryRootFromWorkingDirectory());
        assertThatThrownBy(() -> store.setEnabled("skill-creator", true)).isInstanceOf(java.io.IOException.class);
        assertThat(Files.exists(data.resolve("skills_config.json"))).isFalse();
        store.configureWriteScreening((directory, files) -> {
            assertThat(files).hasSize(20);
            assertThat(Files.isRegularFile(directory.resolve("SKILL.md"))).isTrue();
            return json.createObjectNode().put("decision", "allow");
        });
        store.setEnabled("skill-creator", true);
        var projection = store.projection(SkillRunTools.Selection.of(java.util.Set.of("skill-creator")));
        assertThat(projection.catalog().snapshot().bindings()).hasSize(1);
        assertThat(projection.trust().scriptExecutionGrants()).isEmpty();
        assertThat(projection.loader().load(projection.catalog().snapshot().bindings().getFirst(), SkillReviewApprovals.discovery().visibility())
                .readableResources()).containsKey("references/schemas.md");
    }
}

package io.haifa.agent.superharness.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SkillPackageArchiveTest {
    static byte[] unixMetadataArchive() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var entry : Map.of("sample-helper/SKILL.md", "---\nname: sample-helper\ndescription: Metadata fixture\n---\nUse resources.\n",
                    "sample-helper/scripts/run.sh", "#!/bin/sh\necho safe\n", "sample-helper/assets/cache/", "").entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        byte[] bytes = output.toByteArray();
        var little = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int end = bytes.length - 22;
        int current = little.getInt(end + 16);
        while (current < end) {
            int nameLength = Short.toUnsignedInt(little.getShort(current + 28));
            String name = new String(bytes, current + 46, nameLength, StandardCharsets.UTF_8);
            int mode = name.endsWith("/") ? 0040755 : name.endsWith("run.sh") ? 0100755 : 0100644;
            bytes[current + 5] = 3;
            little.putInt(current + 38, (mode << 16) | (name.endsWith("/") ? 0x10 : 0));
            current += 46 + nameLength + Short.toUnsignedInt(little.getShort(current + 30))
                    + Short.toUnsignedInt(little.getShort(current + 32));
        }
        return bytes;
    }
    private static byte[] archive(String... paths) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (String path : paths) { zip.putNextEntry(new ZipEntry(path)); zip.write("fixture".getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
        }
        return output.toByteArray();
    }
    @ParameterizedTest
    @ValueSource(strings = {"../SKILL.md", "/root/SKILL.md", "C:/SKILL.md", "assets/secret:stream", "assets/CON.txt", "assets/COM¹.txt", "assets/file.", "assets/a\\b", "assets/\u007f.txt"})
    void rejectsUnportablePathsBeforePublication(String path) throws Exception {
        byte[] zip = archive("SKILL.md", path);
        assertThatThrownBy(() -> SkillPackageFiles.archive(zip)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsafe");
    }
    @Test void rejectsCaseAndUnicodeAliasesNestedSkillsAndExcessiveMembers() throws Exception {
        assertThatThrownBy(() -> SkillPackageFiles.archive(archive("SKILL.md", "assets/Fact.txt", "assets/fact.txt"))).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> SkillPackageFiles.archive(archive("SKILL.md", "assets/\u00e9.txt", "assets/e\u0301.txt"))).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> SkillPackageFiles.archive(archive("SKILL.md", "evals/nested/SKILL.md"))).hasMessageContaining("Nested");
        String[] members = java.util.stream.IntStream.range(0, 4097).mapToObj(index -> "member-" + index).toArray(String[]::new);
        assertThatThrownBy(() -> SkillPackageFiles.archive(archive(members))).hasMessageContaining("excessive");
    }
    @Test void rejectsCentralDirectoryLinksAndExecutableMagicButExportsOpaqueAssets() throws Exception {
        byte[] linked = archive("SKILL.md");
        for (int i = 0; i < linked.length - 46; i++) {
            if (linked[i] == 0x50 && linked[i + 1] == 0x4b && linked[i + 2] == 1 && linked[i + 3] == 2) {
                linked[i + 40] = (byte) 0xff; linked[i + 41] = (byte) 0xa1; break;
            }
        }
        assertThatThrownBy(() -> SkillPackageFiles.archive(linked)).hasMessageContaining("links");
        byte[] markdown = "---\nname: sample-helper\ndescription: Export fixture\n---\nKnown instructions\n".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> executable = Map.of("SKILL.md", markdown, "assets/binary", new byte[]{0x7f, 'E', 'L', 'F', 0});
        assertThatThrownBy(() -> SkillPackageFiles.archive(SkillPackageFiles.export("sample-helper", executable))).hasMessageContaining("executable binary");
        assertThat(SkillPackageFiles.exportManifest("sample-helper", executable).get("can_export")).isEqualTo(false);
        var ordinary = Map.of("SKILL.md", markdown, "assets/data.bin", new byte[]{1, 2, 3});
        assertThat(SkillPackageFiles.archive(SkillPackageFiles.export("sample-helper", ordinary)).get("assets/data.bin")).containsExactly(1, 2, 3);
    }

    @Test void unixExecutableAndEmptyDirectorySurviveArchiveAndExport() throws Exception {
        var imported = SkillPackageFiles.archivePackage(unixMetadataArchive());
        assertThat(imported.emptyDirectories()).containsExactly("assets/cache");
        assertThat(imported.executableFiles()).containsExactly("scripts/run.sh");
        var manifest = SkillPackageFiles.exportManifest("sample-helper", imported);
        assertThat(manifest.get("can_export")).isEqualTo(true);
        assertThat(((java.util.List<?>) manifest.get("files")).toString()).contains("assets/cache", "scripts/run.sh", "executable=true");
        assertThat(manifest.get("revision")).isNotEqualTo(SkillPackageFiles.exportManifest("sample-helper", imported.files()).get("revision"));
        var exported = SkillPackageFiles.archivePackage(SkillPackageFiles.export("sample-helper", imported));
        assertThat(exported.emptyDirectories()).isEqualTo(imported.emptyDirectories());
        assertThat(exported.executableFiles()).isEqualTo(imported.executableFiles());
        imported.files().forEach((path, bytes) -> assertThat(exported.files().get(path)).containsExactly(bytes));
    }
}

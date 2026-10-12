package io.haifa.agent.superharness.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Human authorization binds an exact custom Skill package digest and relaxes only the semantic review. */
class HumanAuthorizedCustomSkillTest {
    private final ObjectMapper json = new ObjectMapper();
    @TempDir Path temporary;

    private SkillConfigStore store() throws Exception {
        return new SkillConfigStore(json, temporary.resolve("data"),
                SkillConfigStore.repositoryRootFromWorkingDirectory(), null);
    }

    private String state() throws Exception {
        return Files.readString(temporary.resolve("data/skills_config.json"));
    }

    private void seed(String body) throws Exception {
        Path file = temporary.resolve("data/skills_config.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }

    @Test void recordsExactPackageAuthorization() throws Exception {
        var store = store();
        String revision = "a".repeat(64);
        var entry = store.authorizePackage("sample-skill", revision, "operator");
        assertThat(entry.path("revision").asText()).isEqualTo(revision);
        assertThat(entry.path("authorized_by").asText()).isEqualTo("operator");
        assertThat(entry.path("scope").asText()).isEqualTo("semantic-review");
        assertThat(entry.path("authorized_at").asText()).isNotBlank();
        assertThat(state()).contains("authorized_packages").contains(revision);
    }

    @Test void rejectsNonDigestRevisionAndBlankAuthor() throws Exception {
        var store = store();
        assertThatThrownBy(() -> store.authorizePackage("sample-skill", "sha256:abc", "operator"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("64-hex");
        assertThatThrownBy(() -> store.authorizePackage("sample-skill", "b".repeat(64), "   "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("authorized_by");
        assertThat(Files.exists(temporary.resolve("data/skills_config.json"))).isFalse();
    }

    @Test void rejectsInvalidSkillName() throws Exception {
        var store = store();
        assertThatThrownBy(() -> store.authorizePackage("Bad_Name", "c".repeat(64), "operator"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void preservesEveryExistingStateKey() throws Exception {
        seed("{\"schema_version\":1,\"enabled\":{\"consulting-analysis\":true},\"bundled_reviews\":{\"skill-creator@sha256:deadbeef\":{\"decision\":\"allow\"}},\"custom\":{\"my-skill\":{\"current\":\"\",\"versions\":[],\"history\":[]}},\"external_directory\":\"/opt/deer-flow/repo/skills/public\"}");
        var store = store();
        store.authorizePackage("sample-skill", "d".repeat(64), "operator");
        String written = state();
        assertThat(written).contains("schema_version").contains("consulting-analysis")
                .contains("skill-creator@sha256:deadbeef").contains("my-skill")
                .contains("/opt/deer-flow/repo/skills/public").contains("authorized_packages");
    }

    @Test void authorizationIsBoundToOneNameAndRevision() throws Exception {
        var store = store();
        store.authorizePackage("skill-a", "e".repeat(64), "operator");
        store.authorizePackage("skill-b", "f".repeat(64), "operator");
        store.authorizePackage("skill-a", "1".repeat(64), "operator-2");
        String written = state();
        assertThat(written).contains("skill-a").contains("skill-b");
        assertThat(written).contains("1".repeat(64)).contains("operator-2");
        assertThat(written).doesNotContain("e".repeat(64));
    }
private byte[] archive(String name, boolean withScript) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(buffer)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("SKILL.md"));
            zip.write(("---\nname: " + name + "\ndescription: Test package\n---\nUse the package instructions.\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            if (withScript) {
                zip.putNextEntry(new java.util.zip.ZipEntry("scripts/tool.py"));
                zip.write("print('example')\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    @Test void unauthorizedInstallRunsTheSemanticReview() throws Exception {
        var store = store();
        java.util.concurrent.atomic.AtomicInteger semantic = new java.util.concurrent.atomic.AtomicInteger();
        store.configureWriteScreening(new SkillPackageScreening.WriteScreen() {
            @Override public com.fasterxml.jackson.databind.JsonNode screen(Path directory, java.util.Map<String, byte[]> files) {
                semantic.incrementAndGet();
                return json.createObjectNode().put("decision", "allow");
            }
        });
        store.install(archive("sample-skill", true), null);
        assertThat(semantic.get()).isEqualTo(1);
    }

    @Test void authorizedInstallSkipsTheSemanticReview() throws Exception {
        var store = store();
        java.util.concurrent.atomic.AtomicInteger semantic = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger staticOnly = new java.util.concurrent.atomic.AtomicInteger();
        store.configureWriteScreening(new SkillPackageScreening.WriteScreen() {
            @Override public com.fasterxml.jackson.databind.JsonNode screen(Path directory, java.util.Map<String, byte[]> files) {
                semantic.incrementAndGet();
                return json.createObjectNode().put("decision", "allow");
            }
            @Override public com.fasterxml.jackson.databind.JsonNode staticScreen(Path directory) {
                staticOnly.incrementAndGet();
                return json.createObjectNode().put("decision", "allow").put("authorization_source", "human");
            }
        });
        byte[] packageBytes = archive("sample-skill", true);
        String revision = (String) store.previewArchive(packageBytes).get("revision");
        store.authorizePackage("sample-skill", revision, "operator");
        store.install(packageBytes, null);
        assertThat(semantic.get()).isZero();
        assertThat(staticOnly.get()).isEqualTo(1);
    }
}
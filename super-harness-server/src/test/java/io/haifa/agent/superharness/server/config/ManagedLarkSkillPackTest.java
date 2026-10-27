package io.haifa.agent.superharness.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.skill.api.SkillScope;
import io.haifa.agent.skill.api.SkillVisibilityContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedLarkSkillPackTest {
    private final ObjectMapper json = new ObjectMapper();
    @TempDir Path data;

    private SkillConfigStore store() throws Exception {
        return new SkillConfigStore(json, data, SkillConfigStore.repositoryRootFromWorkingDirectory());
    }

    private Path pack() throws Exception {
        Path root = data.resolve("integrations/lark/skills/lark-doc");
        Files.createDirectories(root.resolve("scripts"));
        Files.createDirectories(root.resolve("references"));
        Files.writeString(root.resolve("SKILL.md"), "---\nname: lark-doc\ndescription: Managed document fixture\n---\nOriginal instructions.\n");
        Files.writeString(root.resolve("scripts/read.py"), "print('read-only fixture')\n");
        Files.writeString(root.resolve("references/doc.txt"), "document reference");
        return root;
    }

    private SkillVisibilityContext visibility() {
        var caller = io.haifa.agent.sdk.api.SdkCaller.defaultPublicUser();
        return new SkillVisibilityContext(caller.tenant(), caller.principal(),
                Optional.empty(), false, Set.of(SkillScope.PRODUCT));
    }

    @Test void scriptsRequireScreenAndExactGrantThenLoadThroughBothSdkSeamsAcrossRestart() throws Exception {
        Path root = pack(); var store = store();
        assertThat(store.detail("lark-doc").orElseThrow().get("enabled")).isEqualTo(false);
        assertThatThrownBy(() -> store.setEnabled("lark-doc", true)).isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("scanner is unavailable");
        store.configureWriteScreening((target, files) -> {
            assertThat(target).isEqualTo(root);
            assertThat(files).containsKeys("SKILL.md", "scripts/read.py", "references/doc.txt");
            return json.createObjectNode().put("decision", "allow");
        });
        store.setEnabled("lark-doc", true);
        var selection = SkillRunTools.Selection.of(Set.of("lark-doc"));
        var admitted = store.projection(selection);
        var binding = admitted.catalog().snapshot().bindings().getFirst();
        assertThat(binding.packageReviewGrantId()).isPresent();
        var foreign = new SkillVisibilityContext(visibility().tenant(), new PrincipalRef("foreign-principal", "user"),
                Optional.empty(), false, Set.of(SkillScope.PRODUCT));
        assertThatThrownBy(() -> admitted.loader().load(binding, foreign)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> admitted.loader().loadResourceBytes(binding, foreign, "scripts/read.py"))
                .isInstanceOf(SecurityException.class);
        assertThat(store.installedReviewFiles("integration", "lark-doc")).containsKey("scripts/read.py");
        assertThat(admitted.loader().load(binding, visibility()).instructions()).contains("Original instructions");
        assertThat(admitted.loader().loadResourceBytes(binding, visibility(), "scripts/read.py").orElseThrow())
                .isEqualTo(Files.readAllBytes(root.resolve("scripts/read.py")));
        assertThat(admitted.loader().loadResourceBytes(binding, visibility(), "references/doc.txt").orElseThrow())
                .isEqualTo("document reference".getBytes(StandardCharsets.UTF_8));
        String version = store.detail("lark-doc").orElseThrow().get("version").toString();
        store.setEnabled("lark-doc", false); store.reload();
        assertThat(store.projection(selection).allowedSkills()).isEmpty();
        var restarted = store();
        var recovered = restarted.recoveryProjection(selection, Map.of("lark-doc", "lark-doc@" + version));
        assertThat(recovered.loader().load(binding, visibility()).instructions()).contains("Original instructions");
        assertThat(recovered.loader().loadResourceBytes(binding, visibility(), "scripts/read.py")).isPresent();
        Files.writeString(root.resolve("scripts/read.py"), "print('new revision')\n");
        // The disabled current package can change; a pinned old Run retains the exact old bytes/grant.
        restarted.reload();
        var changed = store();
        var pinned = changed.recoveryProjection(selection, Map.of("lark-doc", "lark-doc@" + version));
        assertThat(pinned.catalog().snapshot().bindings()).containsExactly(binding);
        assertThat(new String(pinned.loader().loadResourceBytes(binding, visibility(), "scripts/read.py").orElseThrow(),
                StandardCharsets.UTF_8)).contains("read-only fixture");
    }

    @Test void blockedReviewDoesNotPublishEnableAndCustomSameNameHasOneActiveDefinition() throws Exception {
        pack(); var store = store();
        store.configureWriteScreening((target, files) -> json.createObjectNode().put("decision", "block"));
        assertThatThrownBy(() -> store.setEnabled("lark-doc", true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.detail("lark-doc").orElseThrow().get("enabled")).isEqualTo(false);
        store.configureWriteScreening((target, files) -> json.createObjectNode().put("decision", "allow"));
        byte[] markdown = "---\nname: lark-doc\ndescription: User override\n---\nCustom instructions.\n".getBytes(StandardCharsets.UTF_8);
        store.install(SkillPackageFiles.export("lark-doc", Map.of("SKILL.md", markdown)), null);
        var restarted = store();
        assertThat(restarted.list().stream().filter(row -> row.get("name").equals("lark-doc"))).hasSize(1);
        assertThat(restarted.detail("lark-doc").orElseThrow().get("source")).isEqualTo("user-installed");
        assertThat(restarted.reviewFiles("lark-doc").get("SKILL.md")).isEqualTo(markdown);
    }
}

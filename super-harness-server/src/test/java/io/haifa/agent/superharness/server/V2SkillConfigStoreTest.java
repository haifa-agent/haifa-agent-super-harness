package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.skill.api.SkillScope;
import io.haifa.agent.skill.api.SkillVisibilityContext;
import io.haifa.agent.superharness.server.prompt.LeadPromptComposer;
import io.haifa.agent.superharness.server.prompt.LeadPromptInputs;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SDK binding retention and frozen REST shape for the trusted W15 package. */
class V2SkillConfigStoreTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path dataDirectory;

    @Test
    void enableSelectionAndRetainedSdkBindingSurviveReload() throws Exception {
        Path repository = SkillConfigStore.repositoryRootFromWorkingDirectory();
        var store = new SkillConfigStore(json, dataDirectory, repository);
        var active = store.projection(SkillRunTools.Selection.all());
        assertThat(active.allowedSkills()).containsExactly("consulting-analysis");
        assertThat(active.catalog().snapshot().bindings()).hasSize(1);
        var oldBinding = active.catalog().snapshot().bindings().getFirst();
        assertThat(store.projection(SkillRunTools.Selection.none()).allowedSkills()).isEmpty();
        assertThat(store.projection(SkillRunTools.Selection.of(Set.of("consulting-analysis")))
                        .allowedSkills())
                .containsExactly("consulting-analysis");

        store.setEnabled("consulting-analysis", false);
        var restarted = new SkillConfigStore(json, dataDirectory, repository);
        var disabled = restarted.projection(SkillRunTools.Selection.all());
        assertThat(disabled.allowedSkills()).isEmpty();
        assertThat(disabled.installTools()).isFalse();
        assertThat(disabled.forRecovery().installTools()).isTrue();
        var visibility = new SkillVisibilityContext(
                new TenantRef(McpRunTools.TENANT_ID),
                new PrincipalRef(McpRunTools.PRINCIPAL_ID, McpRunTools.PRINCIPAL_TYPE),
                Optional.empty(), false, Set.of(SkillScope.PRODUCT));
        assertThat(disabled.loader().load(oldBinding, visibility).instructions())
                .contains("Data Authenticity Protocol");
    }

    @Test
    void explicitlyEmptyRecoveryKeepsTheOriginalDeclarationAndPrompt() throws Exception {
        Path repository = SkillConfigStore.repositoryRootFromWorkingDirectory();
        var store = new SkillConfigStore(json, dataDirectory, repository);
        var admitted = store.projection(SkillRunTools.Selection.none());
        assertThat(admitted.installTools()).isFalse();
        var composer = LeadPromptComposer.classpath();
        String originalPrompt = composer.compose(AgentPromptContext.defaults().promptInputs(
                admitted.selectedPackages().stream()
                        .map(pkg -> new LeadPromptInputs.Skill(pkg.name(), pkg.description())).toList()),
                AgentSoulTestSupport.BASE_TOOLS).signature();

        var restarted = new SkillConfigStore(json, dataDirectory, repository);
        var recovered = restarted.recoveryProjection(SkillRunTools.Selection.none());
        assertThat(recovered.allowedSkills()).isEmpty();
        assertThat(recovered.catalog().snapshot().bindings()).isEmpty();
        assertThat(recovered.installTools()).isFalse();
        assertThat(composer.compose(AgentPromptContext.defaults().promptInputs(
                recovered.selectedPackages().stream()
                        .map(pkg -> new LeadPromptInputs.Skill(pkg.name(), pkg.description())).toList()),
                AgentSoulTestSupport.BASE_TOOLS).signature()).isEqualTo(originalPrompt);
    }

    @Test
    void previouslySelectedRecoveryRetainsBindingAfterDisableAndReload() throws Exception {
        Path repository = SkillConfigStore.repositoryRootFromWorkingDirectory();
        var store = new SkillConfigStore(json, dataDirectory, repository);
        var admitted = store.projection(SkillRunTools.Selection.of(Set.of("consulting-analysis")));
        assertThat(admitted.installTools()).isTrue();
        var originalBinding = admitted.catalog().snapshot().bindings().getFirst();
        var actualSelection = SkillRunTools.Selection.of(admitted.allowedSkills());
        store.setEnabled("consulting-analysis", false);

        var restarted = new SkillConfigStore(json, dataDirectory, repository);
        assertThat(restarted.projection(actualSelection).allowedSkills()).isEmpty();
        var recovered = restarted.recoveryProjection(actualSelection);
        assertThat(recovered.installTools()).isTrue();
        assertThat(recovered.allowedSkills()).containsExactly("consulting-analysis");
        assertThat(recovered.catalog().snapshot().bindings()).containsExactly(originalBinding);
        var visibility = new SkillVisibilityContext(
                new TenantRef(McpRunTools.TENANT_ID),
                new PrincipalRef(McpRunTools.PRINCIPAL_ID, McpRunTools.PRINCIPAL_TYPE),
                Optional.empty(), false, Set.of(SkillScope.PRODUCT));
        assertThat(recovered.loader().load(originalBinding, visibility).instructions())
                .contains("Data Authenticity Protocol");
    }

    @Test
    void frozenRestReturnsOneSkillAndProcessReloadResponse() throws Exception {
        var store = new SkillConfigStore(
                json, dataDirectory, SkillConfigStore.repositoryRootFromWorkingDirectory());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skills", new DeerFlowSkillsApi(json, store));
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode list = json.readTree(request(base, "GET", "/api/skills", null).body());
            assertThat(list.path("skills")).hasSize(1);
            JsonNode detail = json.readTree(request(base, "GET", "/api/skills/consulting-analysis", null).body());
            assertThat(detail.path("source").asText()).isEqualTo("bytedance/deer-flow");
            assertThat(detail.path("license").asText()).isEqualTo("MIT");
            assertThat(detail.path("sha256").asText())
                    .isEqualTo("5bc99df7e5f8af5471eaa5d20b85fc46e99895cf3f2642fa9da94108d03ca13e");

            var disabled = request(base, "PUT", "/api/skills/consulting-analysis", "{\"enabled\":false}");
            assertThat(disabled.statusCode()).isEqualTo(200);
            assertThat(json.readTree(disabled.body()).path("enabled").asBoolean()).isFalse();
            assertThat(json.readTree(disabled.body()).path("name").asText())
                    .isEqualTo("consulting-analysis");
            JsonNode reload = json.readTree(request(base, "POST", "/api/skills/reload", "{}").body());
            assertThat(reload.size()).isEqualTo(3);
            assertThat(reload.path("success").asBoolean()).isTrue();
            assertThat(reload.path("scope").asText()).isEqualTo("process");
            assertThat(reload.path("message").isTextual()).isTrue();
            assertThat(request(base, "GET", "/api/skills/unknown", null).statusCode()).isEqualTo(404);
            assertThat(request(base, "PUT", "/api/skills/consulting-analysis", "{").statusCode())
                    .isEqualTo(422);
            assertThat(new SkillConfigStore(json, dataDirectory,
                            SkillConfigStore.repositoryRootFromWorkingDirectory())
                            .projection(SkillRunTools.Selection.all()).allowedSkills())
                    .isEmpty();
        } finally {
            server.stop(0);
        }
    }

    private static HttpResponse<String> request(URI base, String method, String path, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(10));
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

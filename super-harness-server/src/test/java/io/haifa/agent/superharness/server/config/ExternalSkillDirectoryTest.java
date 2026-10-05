package io.haifa.agent.superharness.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.skill.api.SkillAvailability;
import io.haifa.agent.skill.api.SkillOrigin;
import io.haifa.agent.skill.api.SkillParserMode;
import io.haifa.agent.skill.api.SkillScopeRef;
import io.haifa.agent.skill.api.SkillSourceDescriptor;
import io.haifa.agent.skill.api.SkillSourceRef;
import io.haifa.agent.skill.core.LocalDirectorySkillSource;
import io.haifa.agent.skill.core.SkillPackageLimits;
import io.haifa.agent.skill.core.SkillPackageParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExternalSkillDirectoryTest {

    @Test
    void testDirectDirectoryDiscoveryOnDeerFlowSkills() throws Exception {
        Path skillsDir = Path.of("d:/workspace/haifa-agent-super-harness/deer-flow/skills/public");
        assertThat(Files.isDirectory(skillsDir)).isTrue();

        SkillPackageLimits limits = new SkillPackageLimits(
                4096, 32, 64 * 1024 * 1024, 512 * 1024 * 1024, 1024 * 1024, 2000, 20000);
        SkillPackageParser parser = new SkillPackageParser(limits);

        List<SkillRunTools.Package> packages = new java.util.ArrayList<>();

        try (var stream = Files.list(skillsDir)) {
            for (Path pkgDir : stream.filter(Files::isDirectory).sorted().toList()) {
                Path skillMd = pkgDir.resolve("SKILL.md");
                if (!Files.isRegularFile(skillMd)) continue;

                String dirName = pkgDir.getFileName().toString();
                // Read package files
                java.util.Map<String, byte[]> files = SkillPackageFiles.directory(pkgDir);

                // Handle compatibility mapping edge case in chart-visualization if present
                byte[] mdBytes = files.get("SKILL.md");
                String mdText = new String(mdBytes, java.nio.charset.StandardCharsets.UTF_8);
                if (mdText.contains("compatibility:\n  nodejs:")) {
                    mdText = mdText.replace("compatibility:\n  nodejs: \">=18.0.0\"", "compatibility: \"nodejs >=18.0.0\"");
                    files.put("SKILL.md", mdText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }

                SkillSourceDescriptor descriptor = new SkillSourceDescriptor(
                        new SkillSourceRef(dirName, "1.0.0"),
                        SkillScopeRef.product(),
                        SkillOrigin.BUNDLED,
                        100,
                        SkillParserMode.COMPATIBLE,
                        true,
                        false);

                var parsedResult = parser.parseFiles(dirName, files, descriptor);
                if (parsedResult.parsed().isEmpty()) {
                    System.err.println("Failed to parse " + dirName + ": " + parsedResult.diagnostics());
                    continue;
                }

                var parsed = parsedResult.parsed().orElseThrow();
                String name = parsed.metadata().name().value();
                String desc = parsed.metadata().description();

                packages.add(new SkillRunTools.Package(
                        name,
                        desc,
                        "public",
                        parsed.metadata().license().orElse("MIT"),
                        "deer-flow/skills",
                        "local-fs",
                        "1.0.0",
                        SkillPackageFiles.hash(files.get("SKILL.md")),
                        true,
                        true,
                        new io.haifa.agent.skill.core.InMemorySkillSource(descriptor, parser, SkillAvailability.ENABLED,
                                java.util.Map.of(dirName, files))));
            }
        }

        System.out.println("Total successfully packaged skills: " + packages.size());
        assertThat(packages).hasSize(23);

        List<io.haifa.agent.skill.api.SkillPackageReviewGrant> grants = new java.util.ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        for (var pkg : packages) {
            var reg = pkg.source().discover(SkillReviewApprovals.discovery()).registrations().getFirst();
            if (reg.packageIndex().resources().stream().anyMatch(r -> r.relativePath().startsWith("scripts/"))) {
                var scan = mapper.createObjectNode().put("decision", "allow");
                var evidence = SkillReviewApprovals.evidence(mapper, reg, scan);
                grants.add(SkillReviewApprovals.grant(reg, evidence));
            }
        }
        var trust = SkillReviewApprovals.snapshot(grants);

        List<io.haifa.agent.skill.api.SkillSource> currentSources = packages.stream().map(SkillRunTools.Package::source).toList();
        var catalog = new io.haifa.agent.skill.core.SkillCatalogBuilder(currentSources,
                new io.haifa.agent.skill.api.SkillResolutionPolicy("super-harness-bundled-skills", java.util.List.of(io.haifa.agent.skill.api.SkillScope.USER, io.haifa.agent.skill.api.SkillScope.PRODUCT), false),
                trust, SkillReviewApprovals.subject(), java.time.Clock.systemUTC())
                .build(SkillReviewApprovals.discovery());

        System.out.println("Bindings count with trust: " + catalog.snapshot().bindings().size());
        assertThat(catalog.snapshot().bindings()).hasSize(23);
        System.out.println("All 23 skills successfully bound into catalog!");

        java.util.Map<String, Boolean> enabledMap = new java.util.HashMap<>();
        packages.forEach(p -> enabledMap.put(p.name(), true));
        var projection = SkillRunTools.projection(packages, enabledMap, SkillRunTools.Selection.all(), trust);
        assertThat(projection.catalog().snapshot().bindings()).hasSize(23);
        assertThat(projection.allowedSkills()).hasSize(23);
        System.out.println("Projection also completely matches 23 skills!");
    }

    @Test
    void testSkillConfigStoreWithExternalDirectory(@org.junit.jupiter.api.io.TempDir Path dataDir) throws Exception {
        Path skillsDir = Path.of("d:/workspace/haifa-agent-super-harness/deer-flow/skills");
        Path repoRoot = SkillConfigStore.repositoryRootFromWorkingDirectory();
        ObjectMapper mapper = new ObjectMapper();

        SkillConfigStore store = new SkillConfigStore(mapper, dataDir, repoRoot, skillsDir);

        var list = store.list();
        System.out.println("SkillConfigStore listed skills count: " + list.size());
        assertThat(list).hasSize(23);

        var bootstrapOpt = store.detail("bootstrap");
        assertThat(bootstrapOpt).isPresent();
        assertThat(bootstrapOpt.get().get("name")).isEqualTo("bootstrap");
        assertThat(bootstrapOpt.get().get("category")).isEqualTo("public");

        var projection = store.projection(SkillRunTools.Selection.all());
        assertThat(projection.allowedSkills()).hasSize(23);
        assertThat(projection.catalog().snapshot().bindings()).hasSize(23);

        var files = store.reviewFiles("bootstrap");
        assertThat(files).containsKey("SKILL.md");

        // Test enable/disable toggle
        store.setEnabled("bootstrap", false);
        var updatedProj = store.projection(SkillRunTools.Selection.all());
        assertThat(updatedProj.allowedSkills()).doesNotContain("bootstrap");
        assertThat(updatedProj.allowedSkills()).hasSize(22);

        // Test restart durability
        SkillConfigStore restarted = new SkillConfigStore(mapper, dataDir, repoRoot, skillsDir);
        var restartedProj = restarted.projection(SkillRunTools.Selection.all());
        assertThat(restartedProj.allowedSkills()).doesNotContain("bootstrap");
        assertThat(restartedProj.allowedSkills()).hasSize(22);
        System.out.println("SkillConfigStore with external directory verified completely!");
    }

    @Test
    void testExternalSkillsRestApi(@org.junit.jupiter.api.io.TempDir Path dataDir) throws Exception {
        Path skillsDir = Path.of("d:/workspace/haifa-agent-super-harness/deer-flow/skills");
        Path repoRoot = SkillConfigStore.repositoryRootFromWorkingDirectory();
        ObjectMapper mapper = new ObjectMapper();

        SkillConfigStore store = new SkillConfigStore(mapper, dataDir, repoRoot, skillsDir);
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skills", new io.haifa.agent.superharness.server.api.DeerFlowSkillsApi(mapper, store));
        server.start();

        try {
            java.net.URI base = java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort());

            // 1. GET /api/skills -> all 23 skills
            var listResp = request(base, "GET", "/api/skills", null);
            assertThat(listResp.statusCode()).isEqualTo(200);
            com.fasterxml.jackson.databind.JsonNode listJson = mapper.readTree(listResp.body());
            assertThat(listJson.path("skills")).hasSize(23);

            // 2. GET /api/skills/deep-research
            var detailResp = request(base, "GET", "/api/skills/deep-research", null);
            assertThat(detailResp.statusCode()).isEqualTo(200);
            com.fasterxml.jackson.databind.JsonNode detailJson = mapper.readTree(detailResp.body());
            assertThat(detailJson.path("name").asText()).isEqualTo("deep-research");
            assertThat(detailJson.path("source").asText()).isEqualTo("deer-flow/skills");
            assertThat(detailJson.path("category").asText()).isEqualTo("public");
            assertThat(detailJson.path("enabled").asBoolean()).isTrue();

            // 3. GET /api/skills/chart-visualization
            var chartResp = request(base, "GET", "/api/skills/chart-visualization", null);
            assertThat(chartResp.statusCode()).isEqualTo(200);

            // 4. PUT /api/skills/deep-research (disable)
            var putResp = request(base, "PUT", "/api/skills/deep-research", "{\"enabled\":false}");
            assertThat(putResp.statusCode()).isEqualTo(200);
            com.fasterxml.jackson.databind.JsonNode putJson = mapper.readTree(putResp.body());
            assertThat(putJson.path("enabled").asBoolean()).isFalse();

            // 5. POST /api/skills/reload
            var reloadResp = request(base, "POST", "/api/skills/reload", "{}");
            assertThat(reloadResp.statusCode()).isEqualTo(200);
            com.fasterxml.jackson.databind.JsonNode reloadJson = mapper.readTree(reloadResp.body());
            assertThat(reloadJson.path("success").asBoolean()).isTrue();

            // 6. Verify disabled persisted in list
            var updatedListResp = request(base, "GET", "/api/skills", null);
            com.fasterxml.jackson.databind.JsonNode updatedListJson = mapper.readTree(updatedListResp.body());
            for (com.fasterxml.jackson.databind.JsonNode s : updatedListJson.path("skills")) {
                if (s.path("name").asText().equals("deep-research")) {
                    assertThat(s.path("enabled").asBoolean()).isFalse();
                }
            }
            System.out.println("REST API verified: 23 external skills successfully listed, fetched, toggled and reloaded!");
        } finally {
            server.stop(0);
        }
    }

    private static java.net.http.HttpResponse<String> request(java.net.URI base, String method, String path, String body)
            throws Exception {
        java.net.http.HttpRequest.Builder req = java.net.http.HttpRequest.newBuilder(base.resolve(path))
                .timeout(java.time.Duration.ofSeconds(10));
        if (body == null) {
            req.GET();
        } else {
            req.method(method, java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json");
        }
        return java.net.http.HttpClient.newHttpClient()
                .send(req.build(), java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
    }
}

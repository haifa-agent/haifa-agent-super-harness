package io.haifa.agent.superharness.server.tool.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.runtime.api.ToolCallView;
import io.haifa.agent.runtime.api.ToolDataView;
import io.haifa.agent.runtime.api.ToolResultView;
import io.haifa.agent.runtime.api.display.BoundedText;
import io.haifa.agent.sdk.tool.JavaRecordSchemaGenerator;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.superharness.server.api.DeerFlowToolTranscriptProjection;
import io.haifa.agent.superharness.server.config.SkillConfigStore;
import io.haifa.agent.superharness.server.config.SkillPackageScreening;
import io.haifa.agent.superharness.server.tool.clarification.ProductionAskClarificationTool;
import io.haifa.agent.superharness.server.tool.skill.ProductionReviewSkillPackageTool;
import io.haifa.agent.superharness.server.tool.skill.ProductionSkillManageTool;
import io.haifa.agent.superharness.server.tool.web.ProductionImageSearchTool;
import io.haifa.agent.superharness.server.tool.web.ProductionWebFetchTool;
import io.haifa.agent.superharness.server.tool.web.ProductionWebSearchTool;
import io.haifa.agent.superharness.server.tool.web.WebToolConfig;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceBinding;
import io.haifa.agent.superharness.workspace.WorkspaceChangeLimits;
import io.haifa.agent.superharness.workspace.WorkspaceLimits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract and behaviour coverage for the frozen DeerFlow default file tool set (W04-task-7). */
class ProductionFileToolsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path dataRoot;

    @Test
    void schemasMatchTheFrozenParameterContract() {
        JavaRecordSchemaGenerator generator = new JavaRecordSchemaGenerator();
        assertSchema(generator, "read_file", ProductionReadFileTool.Input.class,
                List.of("path", "description", "start_line", "end_line"), List.of("path"));
        assertSchema(generator, "write_file", ProductionWriteFileTool.Input.class,
                List.of("path", "content", "description", "append"), List.of("path", "content"));
        assertSchema(generator, "str_replace", ProductionStrReplaceTool.Input.class,
                List.of("path", "old_str", "new_str", "description", "replace_all"),
                List.of("path", "old_str", "new_str"));
        assertSchema(generator, "ls", ProductionLsTool.Input.class, List.of("path", "description"), List.of("path"));
        assertSchema(generator, "glob", ProductionGlobTool.Input.class,
                List.of("pattern", "path", "description", "include_dirs", "max_results"),
                List.of("pattern", "path"));
        assertSchema(generator, "grep", ProductionGrepTool.Input.class,
                List.of("pattern", "path", "description", "glob", "literal", "case_sensitive", "max_results"),
                List.of("pattern", "path"));
        assertSchema(generator, "list_uploaded_files", ProductionListUploadedFilesTool.Input.class,
                List.of("include_outline", "max_results", "query", "extensions"), List.of());
        assertSchema(generator, "present_files", ProductionPresentFilesTool.Input.class,
                List.of("filepaths"), List.of("filepaths"));
        assertSchema(generator, "view_image", ProductionViewImageTool.Input.class,
                List.of("image_path"), List.of("image_path"));
    }

    @Test
    void importedDescriptionsMatchTheJavaSpecAndTheLedger() throws Exception {
        Fixture fixture = new Fixture(dataRoot.resolve("workspace"), "thread-descriptions");
        SkillConfigStore skills = new SkillConfigStore(JSON, dataRoot,
                SkillConfigStore.repositoryRootFromWorkingDirectory());
        SkillPackageScreening screening = new SkillPackageScreening(JSON, dataRoot);
        Map<String, String> actual = Map.ofEntries(
                Map.entry("read_file", fixture.read.spec().description()),
                Map.entry("write_file", fixture.write.spec().description()),
                Map.entry("str_replace", fixture.replace.spec().description()),
                Map.entry("ls", fixture.ls.spec().description()),
                Map.entry("glob", fixture.glob.spec().description()),
                Map.entry("grep", fixture.grep.spec().description()),
                Map.entry("list_uploaded_files", fixture.uploads.spec().description()),
                Map.entry("present_files", fixture.present.spec().description()),
                Map.entry("ask_clarification", new ProductionAskClarificationTool().spec().description()),
                Map.entry("web_search",
                        new ProductionWebSearchTool(WebToolConfig.defaults()).spec().description()),
                Map.entry("web_fetch",
                        new ProductionWebFetchTool(WebToolConfig.defaults()).spec().description()),
                Map.entry("image_search",
                        new ProductionImageSearchTool(WebToolConfig.defaults()).spec().description()),
                Map.entry("skill_manage", new ProductionSkillManageTool(skills, "thread-descriptions").spec().description()),
                Map.entry("review_skill_package", new ProductionReviewSkillPackageTool(skills, screening,
                        ignored -> Map.of()).spec().description()),
                Map.entry("write_todos", io.haifa.agent.superharness.server.plan.PlanAuthoringResources.toolDescription()),
                Map.entry("view_image", fixture.viewImage.spec().description()));
        JsonNode manifest = JSON.readTree(getClass().getClassLoader()
                .getResourceAsStream("upstream/deerflow/tool-descriptions/manifest.json"));
        for (JsonNode entry : manifest.path("descriptions")) {
            String tool = entry.path("tool").asText();
            String resource;
            try (var stream = getClass().getClassLoader()
                    .getResourceAsStream("upstream/deerflow/tool-descriptions/" + tool + ".txt")) {
                resource = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertThat(actual.get(tool)).as(tool).isEqualTo(resource);
            assertThat(sha256(resource.getBytes(StandardCharsets.UTF_8)))
                    .as(tool).isEqualTo(entry.path("description_sha256").asText());
        }
    }

    @Test
    void strReplaceMatchesTheFrozenZeroOneAndMultipleOccurrenceBehaviour() {
        Fixture fixture = new Fixture(dataRoot.resolve("workspace"), "thread-replace");
        String path = "/mnt/user-data/outputs/notes.txt";
        JavaToolContext trusted = context();
        fixture.write.invoke(writeInput(path, "one two two"), trusted);
        fixture.read.invoke(readInput(path), trusted);

        var missing = fixture.replace.invoke(replaceInput(path, "absent", "x", false), trusted);
        assertThat(missing.error()).isEqualTo("String to replace not found in file: " + path);
        assertThat(fixture.text(path)).isEqualTo("one two two");

        var first = fixture.replace.invoke(replaceInput(path, "two", "2", false), trusted);
        assertThat(fixture.replace.summarize(first)).isEqualTo("OK");
        assertThat(fixture.text(path)).isEqualTo("one 2 two");

        fixture.read.invoke(readInput(path), trusted);
        var all = fixture.replace.invoke(replaceInput(path, "two", "2", true), trusted);
        assertThat(fixture.replace.summarize(all)).isEqualTo("OK");
        assertThat(fixture.text(path)).isEqualTo("one 2 2");

        // Any write invalidates the read mark.
        var blocked = fixture.replace.invoke(replaceInput(path, "2", "3", false), trusted);
        assertThat(blocked.error()).contains("has not been read");
    }

    @Test
    void lsRendersATwoLevelTreeAndEmptyDirectories() {
        Fixture fixture = new Fixture(dataRoot.resolve("workspace"), "thread-ls");
        JavaToolContext trusted = context();
        fixture.write.invoke(writeInput("/mnt/user-data/workspace/a.py", "a"), trusted);
        fixture.write.invoke(writeInput("/mnt/user-data/workspace/sub/b.py", "b"), trusted);

        var result = fixture.ls.invoke(new ProductionLsTool.Input("/mnt/user-data/workspace", Optional.empty()), trusted);
        assertThat(result.error()).isEmpty();
        assertThat(result.content()).contains("a.py", "sub/", "  b.py");

        var empty = fixture.ls.invoke(
                new ProductionLsTool.Input("/mnt/user-data/outputs", Optional.empty()), trusted);
        assertThat(empty.content()).isEqualTo("(empty)");
    }

    @Test
    void globUsesRecursivePythonSemanticsAndTruncates() {
        Fixture fixture = new Fixture(dataRoot.resolve("workspace"), "thread-glob");
        JavaToolContext trusted = context();
        fixture.write.invoke(writeInput("/mnt/user-data/workspace/a.py", "a"), trusted);
        fixture.write.invoke(writeInput("/mnt/user-data/workspace/sub/b.py", "b"), trusted);
        fixture.write.invoke(writeInput("/mnt/user-data/workspace/sub/c.txt", "c"), trusted);

        var found = fixture.glob.invoke(globInput("**/*.py", "/mnt/user-data/workspace",
                Optional.of(false), Optional.of(200)), trusted);
        assertThat(found.content()).contains("Found 2 paths under /mnt/user-data/workspace")
                .contains("/mnt/user-data/workspace/a.py", "/mnt/user-data/workspace/sub/b.py")
                .doesNotContain("c.txt");

        var truncated = fixture.glob.invoke(globInput("**/*", "/mnt/user-data/workspace",
                Optional.of(false), Optional.of(1)), trusted);
        assertThat(truncated.content()).contains("(showing first 1)")
                .contains("Results truncated. Narrow the path or pattern to see fewer matches.");

        var none = fixture.glob.invoke(globInput("**/*.md", "/mnt/user-data/workspace",
                Optional.of(false), Optional.of(200)), trusted);
        assertThat(none.content()).isEqualTo("No files matched under /mnt/user-data/workspace");
    }

    @Test
    void grepHonoursPatternFlagsGlobFilterAndLimits() {
        Fixture fixture = new Fixture(dataRoot.resolve("workspace"), "thread-grep");
        JavaToolContext trusted = context();
        fixture.write.invoke(writeInput("/mnt/user-data/workspace/notes.txt", "Alpha row\nbeta row\nGamma"), trusted);
        fixture.write.invoke(writeInput("/mnt/user-data/workspace/script.py", "row = 1"), trusted);

        var upper = fixture.grep.invoke(grepInput("alpha", "/mnt/user-data/workspace", Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty()), trusted);
        assertThat(upper.content()).isEqualTo("Found 1 matches under /mnt/user-data/workspace"
                + "\n/mnt/user-data/workspace/notes.txt:1: Alpha row");

        var caseSensitive = fixture.grep.invoke(grepInput("alpha", "/mnt/user-data/workspace", Optional.empty(),
                Optional.empty(), Optional.of(true), Optional.empty()), trusted);
        assertThat(caseSensitive.content()).isEqualTo("No matches found under /mnt/user-data/workspace");

        var literal = fixture.grep.invoke(grepInput("row.", "/mnt/user-data/workspace", Optional.empty(),
                Optional.of(true), Optional.empty(), Optional.empty()), trusted);
        assertThat(literal.content()).isEqualTo("No matches found under /mnt/user-data/workspace");

        var globFiltered = fixture.grep.invoke(grepInput("row", "/mnt/user-data/workspace",
                Optional.of("**/*.txt"), Optional.empty(), Optional.empty(), Optional.empty()), trusted);
        assertThat(globFiltered.content()).contains("Found 2 matches").doesNotContain("script.py");

        var limited = fixture.grep.invoke(grepInput("row", "/mnt/user-data/workspace", Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(1)), trusted);
        assertThat(limited.content()).contains("(showing first 1)")
                .contains("Results truncated. Narrow the path or add a glob filter.");

        var invalid = fixture.grep.invoke(grepInput("[", "/mnt/user-data/workspace", Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty()), trusted);
        assertThat(invalid.error()).startsWith("Invalid regex pattern:");
    }

    @Test
    void grepCatastrophicBacktrackingReturnsWithinTheDeadline() {
        Fixture fixture = new Fixture(dataRoot.resolve("workspace"), "thread-grep-timeout");
        JavaToolContext trusted = context();
        fixture.write.invoke(writeInput("/mnt/user-data/workspace/victim.txt", "a".repeat(60) + "b"), trusted);

        // Java's Pattern optimizes the classic (a+)+$ form, so the deadline guard is proven with a
        // bounded-repetition alternation that genuinely backtracks exponentially in this engine.
        long started = System.nanoTime();
        var result = fixture.grep.invoke(grepInput("(?:a|aa|aaa){1,20}b", "/mnt/user-data/workspace",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), trusted);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertThat(result.error()).contains("timed out");
        assertThat(elapsedMillis).isLessThan(15_000);

        // Whatever the engine does with the prompt's example, grep must still return promptly.
        started = System.nanoTime();
        var example = fixture.grep.invoke(grepInput("(a+)+$", "/mnt/user-data/workspace",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), trusted);
        assertThat(example.error()).isEmpty();
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(15_000);
    }

    @Test
    void readToolsRejectEscapesAndUploadsWithoutChangingTheWorkspace() {
        Fixture fixture = new Fixture(dataRoot.resolve("workspace"), "thread-read-paths");
        JavaToolContext trusted = context();
        for (String rejected : new String[] {"../outside", "/etc", "C:/Windows", "workspace\\escape"}) {
            assertThat(fixture.ls.invoke(new ProductionLsTool.Input(rejected, Optional.empty()), trusted).error())
                    .as(rejected).isNotEmpty();
            assertThat(fixture.glob.invoke(globInput("**", rejected, Optional.of(false), Optional.of(200)), trusted)
                    .error()).as(rejected).isNotEmpty();
            assertThat(fixture.grep.invoke(grepInput("x", rejected, Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty()), trusted).error()).as(rejected).isNotEmpty();
        }
    }

    @Test
    void listUploadedFilesExcludesTheCurrentRunAndAppliesFilters() throws Exception {
        Fixture fixture = new Fixture(dataRoot.resolve("workspace"), "thread-uploads");
        fixture.upload("customers.csv", "id,region\n1,East\n");
        fixture.upload("notes.txt", "hello\n");
        JavaToolContext trusted = context();

        var all = fixture.uploads.invoke(uploadsInput(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty()), trusted);
        JsonNode allJson = JSON.readTree(all.content());
        assertThat(allJson.path("total_count").asInt()).isEqualTo(2);

        fixture.setCurrentRun(java.util.Set.of("customers.csv"));
        var historical = fixture.uploads.invoke(uploadsInput(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty()), trusted);
        JsonNode historicalJson = JSON.readTree(historical.content());
        assertThat(historicalJson.path("total_count").asInt()).isEqualTo(1);
        assertThat(historicalJson.path("files").get(0).path("filename").asText()).isEqualTo("notes.txt");

        var query = fixture.uploads.invoke(uploadsInput(Optional.empty(), Optional.of("notes"),
                Optional.empty(), Optional.empty()), trusted);
        assertThat(JSON.readTree(query.content()).path("total_count").asInt()).isEqualTo(1);

        var extensions = fixture.uploads.invoke(uploadsInput(Optional.empty(), Optional.empty(),
                Optional.of(List.of("txt")), Optional.empty()), trusted);
        assertThat(JSON.readTree(extensions.content()).path("files").get(0).path("extension").asText())
                .isEqualTo(".txt");

        var outline = fixture.uploads.invoke(uploadsInput(Optional.of(true), Optional.empty(), Optional.empty(),
                Optional.empty()), trusted);
        assertThat(outline.content()).doesNotContain("outline");
    }

    @Test
    void overwriteAndStrReplacePublishDistinctArtifactsForTheSameOutputPath() {
        Fixture fixture = new Fixture(dataRoot.resolve("workspace"), "thread-artifacts");
        JavaToolContext trusted = context();
        String path = "/mnt/user-data/outputs/report.txt";
        fixture.write.invoke(writeInput(path, "original"), trusted);
        fixture.read.invoke(readInput(path), trusted);
        fixture.write.invoke(writeInput(path, "replaced"), trusted);
        fixture.read.invoke(readInput(path), trusted);
        fixture.replace.invoke(replaceInput(path, "replaced", "final", false), trusted);

        var artifacts = fixture.workspace.findArtifacts(
                fixture.binding.ownerId(), fixture.binding.workspaceId(), fixture.projectRef);
        assertThat(artifacts).hasSize(3);
        assertThat(artifacts).allSatisfy(artifact -> assertThat(artifact.provenance().sourceLogicalPath())
                .isEqualTo(path));

        ToolCallView write = call("write_file", path, "artifact-1");
        ToolCallView replace = call("str_replace", path, "artifact-2");
        ToolCallView duplicate = call("write_file", path, "artifact-2");
        assertThat(DeerFlowToolTranscriptProjection.artifactPaths(List.of(write, replace, duplicate)))
                .containsExactly(path);
    }

    private void assertSchema(
            JavaRecordSchemaGenerator generator,
            String tool,
            Class<? extends Record> input,
            List<String> properties,
            List<String> required) {
        Map<String, Object> document = generator.generate(tool, "1.0.0", input).document();
        @SuppressWarnings("unchecked")
        Map<String, Object> actualProperties = (Map<String, Object>) document.get("properties");
        assertThat(actualProperties.keySet()).as(tool).containsExactlyInAnyOrderElementsOf(properties);
        Object requiredValue = document.get("required");
        @SuppressWarnings("unchecked")
        List<String> actualRequired = requiredValue instanceof List<?> list ? (List<String>) list : List.of();
        assertThat(actualRequired).as(tool).containsExactlyInAnyOrderElementsOf(required);
    }

    private static ToolCallView call(String tool, String path, String artifactId) {
        ToolResultView result = new ToolResultView(
                true,
                BoundedText.of("OK", 100, 100),
                new ToolDataView(Map.of("path", path, "artifactId", artifactId), false),
                List.of(), List.of(), false);
        return new ToolCallView(
                new ToolCallId("call-" + tool + "-" + artifactId), new AgentRunId("run-1"), tool, "1.0.0",
                ToolDataView.empty(), ToolCallStatus.COMPLETED, Optional.of(result), Optional.empty(),
                Instant.now(), Optional.empty(), Optional.empty());
    }

    private static ProductionWriteFileTool.Input writeInput(String path, String content) {
        return new ProductionWriteFileTool.Input(path, content, Optional.empty(), Optional.empty());
    }

    private static ProductionReadFileTool.Input readInput(String path) {
        return new ProductionReadFileTool.Input(path, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ProductionStrReplaceTool.Input replaceInput(
            String path, String oldStr, String newStr, boolean replaceAll) {
        return new ProductionStrReplaceTool.Input(
                path, oldStr, newStr, Optional.empty(), Optional.of(replaceAll));
    }

    private static ProductionGlobTool.Input globInput(
            String pattern, String path, Optional<Boolean> includeDirs, Optional<Integer> maxResults) {
        return new ProductionGlobTool.Input(pattern, path, Optional.empty(), includeDirs, maxResults);
    }

    private static ProductionGrepTool.Input grepInput(
            String pattern,
            String path,
            Optional<String> glob,
            Optional<Boolean> literal,
            Optional<Boolean> caseSensitive,
            Optional<Integer> maxResults) {
        return new ProductionGrepTool.Input(
                pattern, path, Optional.empty(), glob, literal, caseSensitive, maxResults);
    }

    private static ProductionListUploadedFilesTool.Input uploadsInput(
            Optional<Boolean> includeOutline,
            Optional<String> query,
            Optional<List<String>> extensions,
            Optional<Integer> maxResults) {
        return new ProductionListUploadedFilesTool.Input(includeOutline, maxResults, query, extensions);
    }

    private static JavaToolContext context() {
        return new JavaToolContext(new AgentRunId("run-1"), Optional.of(new AgentSessionId("session-1")),
                new ToolCallId("tool-call-1"), new TenantRef("public"),
                new PrincipalRef("default-public-user", "user"),
                Instant.now().plusSeconds(60), Optional.empty(), () -> false, Map.of());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static final class Fixture {
        private final RestrictedWorkspaceService workspace;
        private final WorkspaceBinding binding;
        private final String projectRef;
        private final ProductionReadFileTool read;
        private final ProductionWriteFileTool write;
        private final ProductionStrReplaceTool replace;
        private final ProductionLsTool ls;
        private final ProductionGlobTool glob;
        private final ProductionGrepTool grep;
        private final ProductionListUploadedFilesTool uploads;
        private final ProductionPresentFilesTool present;

        private Fixture(Path root, String threadId) {
            var bindings = new PersistentProjectWorkspaceService(root,
                    WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
            this.workspace = new RestrictedWorkspaceService(root, WorkspaceLimits.defaults());
            this.binding = bindings.bindThreadWorkspace("default", threadId);
            this.projectRef = "thread:" + threadId;
            FileToolSession session = new FileToolSession();
            CurrentRunUploads currentRunUploads = new CurrentRunUploads();
            this.read = new ProductionReadFileTool(threadId, bindings, workspace, session);
            this.write = new ProductionWriteFileTool(threadId, projectRef, bindings, workspace, session);
            this.replace = new ProductionStrReplaceTool(threadId, projectRef, bindings, workspace, session);
            this.ls = new ProductionLsTool(threadId, bindings, workspace);
            this.glob = new ProductionGlobTool(threadId, bindings, workspace);
            this.grep = new ProductionGrepTool(threadId, bindings, workspace);
            this.uploads = new ProductionListUploadedFilesTool(threadId, bindings, workspace, currentRunUploads);
            this.present = new ProductionPresentFilesTool(threadId, projectRef, bindings, workspace);
            this.viewImage = new ProductionViewImageTool(threadId, bindings, workspace);
            this.currentRunUploads = currentRunUploads;
        }

        private final ProductionViewImageTool viewImage;
        private final CurrentRunUploads currentRunUploads;

        private void setCurrentRun(java.util.Set<String> names) {
            currentRunUploads.set(names);
        }

        private void upload(String filename, String content) {
            workspace.upload(binding.ownerId(), binding.workspaceId(), filename,
                    content.getBytes(StandardCharsets.UTF_8), "text/plain");
        }

        private String text(String path) {
            return new String(workspace.readVerifiedBytes(binding.ownerId(), binding.workspaceId(), path),
                    StandardCharsets.UTF_8);
        }
    }
}

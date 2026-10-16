package io.haifa.agent.superharness.workspace;

import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.diff.DiffFile;
import io.haifa.agent.project.diff.DiffRequest;
import io.haifa.agent.project.filesystem.ReadOptions;
import io.haifa.agent.project.filesystem.SearchRequest;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersistentProjectWorkspaceServiceTest {
    @TempDir
    Path storageRoot;

    private final WorkspaceLimits workspaceLimits = new WorkspaceLimits(64, 128, 32);
    private final WorkspaceChangeLimits changeLimits = new WorkspaceChangeLimits(20, 128, 1024);

    @Test
    void persistsProjectDocumentsDeduplicatesFirstWriterAndPromotesThreadOutput() {
        RestrictedWorkspaceService workspace = workspace();
        WorkspaceBinding binding = workspace.bind("owner-a", "thread-1");
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "outputs/result.txt", bytes("run result\n"),
                Optional.empty());
        PersistentProjectWorkspaceService projects = projects();
        projects.bindProject("owner-a", "project-1");

        ProjectDocumentReceipt uploaded = projects.uploadProjectDocument(
                "owner-a", "project-1", "notes.txt", bytes("reference\n"));
        ProjectDocumentReceipt duplicate = projects.uploadProjectDocument(
                "owner-a", "project-1", "renamed.txt", bytes("reference\n"));
        ProjectDocumentReceipt promoted = projects.promoteThreadFile(
                "owner-a", "project-1", "thread-1", binding.workspaceId(),
                ProjectDocumentSourceKind.OUTPUT, "result.txt", Optional.of("saved-result.txt"));

        assertThat(duplicate.deduplicated()).isTrue();
        assertThat(duplicate.document()).isEqualTo(uploaded.document());
        assertThat(duplicate.document().name()).isEqualTo("notes.txt");
        assertThat(promoted.document().sourceThreadId()).contains("thread-1");
        assertThat(promoted.document().sourceKind()).isEqualTo(ProjectDocumentSourceKind.OUTPUT);
        assertThat(promoted.document().sourceName()).contains("result.txt");

        PersistentProjectWorkspaceService restarted = projects();
        assertThat(restarted.listProjectDocuments("owner-a", "project-1"))
                .extracting(ProjectDocument::name)
                .containsExactlyInAnyOrder("notes.txt", "saved-result.txt");
        ProjectDocumentContent page = restarted.readProjectDocument(
                "owner-a", "project-1", promoted.document().id(), 4, 6);
        assertThat(page.bytes()).isEqualTo(bytes("result"));
        assertThat(page.totalBytes()).isEqualTo(bytes("run result\n").length);
        assertThat(page.truncated()).isTrue();
        assertThat(page.sha256()).isEqualTo(promoted.document().sha256());
    }

    @Test
    void rejectsForeignProjectThreadUnsafeNamesOversizeAndMissingDocuments() {
        RestrictedWorkspaceService workspace = workspace();
        WorkspaceBinding first = workspace.bind("owner-a", "thread-1");
        WorkspaceBinding second = workspace.bind("owner-a", "thread-2");
        workspace.writeWorkingFile("owner-a", first.workspaceId(), "outputs/result.txt", bytes("result"),
                Optional.empty());
        PersistentProjectWorkspaceService projects = projects();
        projects.bindProject("owner-a", "project-1");

        assertError(WorkspaceError.OWNER_MISMATCH,
                () -> projects.listProjectDocuments("owner-b", "project-1"));
        assertError(WorkspaceError.OWNER_MISMATCH,
                () -> projects.promoteThreadFile("owner-a", "project-1", "thread-1", second.workspaceId(),
                        ProjectDocumentSourceKind.OUTPUT, "result.txt", Optional.empty()));
        assertError(WorkspaceError.INVALID_PATH,
                () -> projects.promoteThreadFile("owner-a", "project-1", "thread-1", first.workspaceId(),
                        ProjectDocumentSourceKind.OUTPUT, "../result.txt", Optional.empty()));
        assertError(WorkspaceError.FILE_TOO_LARGE,
                () -> projects.uploadProjectDocument("owner-a", "project-1", "large.bin", new byte[65]));
        assertError(WorkspaceError.PROJECT_NOT_FOUND,
                () -> projects.listProjectDocuments("owner-a", "missing-project"));
        assertError(WorkspaceError.DOCUMENT_NOT_FOUND,
                () -> projects.readProjectDocument("owner-a", "project-1",
                        "doc-00000000000000000000000000000000", 0, 8));
    }

    @Test
    void persistsRunBaselineAndReportsCreatedModifiedDeletedAndBinaryChanges() throws IOException {
        RestrictedWorkspaceService workspace = workspace();
        WorkspaceBinding binding = workspace.bind("owner-a", "thread-1");
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "workspace/edit.txt", bytes("before\n"),
                Optional.empty());
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "outputs/delete.txt", bytes("gone\n"),
                Optional.empty());
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "outputs/binary.bin", new byte[] {0, 1, 2},
                Optional.empty());
        PersistentProjectWorkspaceService changes = projects();
        changes.beginRunChanges("owner-a", "thread-1", binding.workspaceId(), "run-1");

        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "workspace/edit.txt", bytes("after\n"),
                Optional.of(sha256(bytes("before\n"))));
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "outputs/new.txt", bytes("created\n"),
                Optional.empty());
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "outputs/binary.bin",
                new byte[] {(byte) 0xff, 1, 2}, Optional.of(sha256(new byte[] {0, 1, 2})));
        Files.delete(storageRoot.resolve("workspaces").resolve(binding.workspaceId().value())
                .resolve("user-data/outputs/delete.txt"));
        changes.completeRunChanges("owner-a", "thread-1", binding.workspaceId(), "run-1");

        WorkspaceChanges result = projects().readRunChanges(
                "owner-a", "thread-1", binding.workspaceId(), "run-1");

        assertThat(result.files()).hasSize(4);
        assertThat(result.files()).extracting(change -> change.path().value(), change -> change.type())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("workspace/edit.txt", FileChangeType.REPLACE),
                        org.assertj.core.groups.Tuple.tuple("outputs/new.txt", FileChangeType.CREATE),
                        org.assertj.core.groups.Tuple.tuple("outputs/delete.txt", FileChangeType.DELETE),
                        org.assertj.core.groups.Tuple.tuple("outputs/binary.bin", FileChangeType.REPLACE));
        assertThat(result.diffUnavailablePaths()).extracting(ProjectPath::value)
                .containsExactly("outputs/binary.bin");
        assertThat(result.diff().unifiedDiff())
                .contains("--- a/workspace/edit.txt", "-before", "+after", "+++ b/outputs/new.txt");
        assertThat(result.diff().fileCount()).isEqualTo(3);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void stagesBeforeRunPublishesAtomicallyPersistsAndExcludesUploads() {
        RestrictedWorkspaceService workspace = workspace();
        WorkspaceBinding binding = workspace.bind("owner-a", "thread-stage");
        workspace.upload("owner-a", binding.workspaceId(), "ignored.txt", bytes("upload"), "text/plain");
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "workspace/before.txt", bytes("before\n"),
                Optional.empty());
        PersistentProjectWorkspaceService changes = projects();

        StagedRunChanges staged = changes.stageRunChanges("owner-a", "thread-stage", binding.workspaceId());
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "outputs/created.txt", bytes("created\n"),
                Optional.empty());
        changes.publishRunChanges(staged, "run-staged");
        changes.completeRunChanges("owner-a", "thread-stage", binding.workspaceId(), "run-staged");

        WorkspaceChanges result = projects().readRunChanges(
                "owner-a", "thread-stage", binding.workspaceId(), "run-staged");
        assertThat(result.files()).extracting(change -> change.path().value())
                .containsExactly("outputs/created.txt");
        assertThat(result.diff().unifiedDiff()).contains("+created");
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "outputs/later.txt", bytes("later\n"),
                Optional.empty());
        assertThat(projects().readRunChanges("owner-a", "thread-stage", binding.workspaceId(), "run-staged")
                .files()).extracting(change -> change.path().value()).doesNotContain("outputs/later.txt");
        assertError(WorkspaceError.RUN_BASELINE_NOT_FOUND,
                () -> changes.publishRunChanges(staged, "run-staged"));
    }

    @Test
    void publishesAndCompletesWhenTheStagedDirectoryRenameIsDeniedByAHeldHandle() throws Exception {
        Assumptions.assumeTrue(
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win"),
                "A held handle denies a directory rename only on Windows; other platforms publish atomically.");
        RestrictedWorkspaceService workspace = workspace();
        WorkspaceBinding binding = workspace.bind("owner-a", "thread-denied");
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "workspace/before.txt", bytes("before\n"),
                Optional.empty());
        PersistentProjectWorkspaceService changes = projects();
        StagedRunChanges staged = changes.stageRunChanges("owner-a", "thread-denied", binding.workspaceId());
        Path stagedRoot = storageRoot.resolve("workspace-changes").resolve(".baseline-" + staged.token);
        Path probe = storageRoot.resolve("workspace-changes").resolve("probe-" + staged.token);
        Path heldPayload;
        try (var payloads = Files.list(stagedRoot.resolve("payloads"))) {
            heldPayload = payloads.findFirst().orElseThrow();
        }

        // A handle on a file inside the staged directory makes a direct directory rename fail on
        // Windows, exactly like a watcher or indexer that touches the just-written snapshot.
        try (java.io.InputStream held = Files.newInputStream(heldPayload)) {
            assertThatThrownBy(() -> Files.move(stagedRoot, probe, StandardCopyOption.ATOMIC_MOVE))
                    .isInstanceOf(java.nio.file.FileSystemException.class);
            assertThat(stagedRoot).isDirectory();
            assertThat(probe).doesNotExist();

            changes.publishRunChanges(staged, "run-denied");
            workspace.writeWorkingFile("owner-a", binding.workspaceId(), "outputs/created.txt",
                    bytes("created\n"), Optional.empty());
            changes.completeRunChanges("owner-a", "thread-denied", binding.workspaceId(), "run-denied");

            WorkspaceChanges result = projects().readRunChanges(
                    "owner-a", "thread-denied", binding.workspaceId(), "run-denied");
            assertThat(result.files()).extracting(change -> change.path().value())
                    .containsExactly("outputs/created.txt");
            assertThat(result.diff().unifiedDiff()).contains("+created");
        }
    }

    @Test
    void completesAnInterruptedCompletionThatLeftAnUnpublishedCompletedDirectory() throws IOException {
        RestrictedWorkspaceService workspace = workspace();
        WorkspaceBinding binding = workspace.bind("owner-a", "thread-interrupted");
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "workspace/before.txt", bytes("before\n"),
                Optional.empty());
        PersistentProjectWorkspaceService changes = projects();
        StagedRunChanges staged = changes.stageRunChanges("owner-a", "thread-interrupted", binding.workspaceId());
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "outputs/created.txt", bytes("created\n"),
                Optional.empty());
        changes.publishRunChanges(staged, "run-interrupted");

        // A previous completion attempt interrupted after creating the target directory but before
        // baseline.properties was placed must not be mistaken for an already completed run.
        Path completed = baseline(binding, "run-interrupted").getParent().resolve("completed");
        Files.createDirectories(completed.resolve("payloads"));
        assertThat(completed.resolve("baseline.properties")).doesNotExist();

        changes.completeRunChanges("owner-a", "thread-interrupted", binding.workspaceId(), "run-interrupted");

        WorkspaceChanges first = projects().readRunChanges(
                "owner-a", "thread-interrupted", binding.workspaceId(), "run-interrupted");
        assertThat(first.files()).extracting(change -> change.path().value())
                .containsExactly("outputs/created.txt");
        assertThat(first.diff().unifiedDiff()).contains("+created");

        // Completing again must be idempotent and must not alter the published snapshot.
        changes.completeRunChanges("owner-a", "thread-interrupted", binding.workspaceId(), "run-interrupted");
        WorkspaceChanges second = projects().readRunChanges(
                "owner-a", "thread-interrupted", binding.workspaceId(), "run-interrupted");
        assertThat(second.files()).isEqualTo(first.files());
        assertThat(second.diff().unifiedDiff()).isEqualTo(first.diff().unifiedDiff());
    }

    @Test
    void discardAndRestartCleanupRemoveOnlyOpaqueStagedBaselines() throws IOException {
        RestrictedWorkspaceService workspace = workspace();
        WorkspaceBinding binding = workspace.bind("owner-a", "thread-cleanup");
        PersistentProjectWorkspaceService changes = projects();

        StagedRunChanges discarded = changes.stageRunChanges("owner-a", "thread-cleanup", binding.workspaceId());
        Path discardedRoot = storageRoot.resolve("workspace-changes").resolve(".baseline-" + discarded.token);
        changes.discardRunChanges(discarded);
        assertThat(discardedRoot).doesNotExist();

        StagedRunChanges abandoned = changes.stageRunChanges("owner-a", "thread-cleanup", binding.workspaceId());
        Path abandonedRoot = storageRoot.resolve("workspace-changes").resolve(".baseline-" + abandoned.token);
        Path metadata = abandonedRoot.resolve("baseline.properties");
        Properties properties = load(metadata);
        properties.setProperty("processId", Long.toString(Long.MAX_VALUE));
        store(properties, metadata);

        projects();
        assertThat(abandonedRoot).doesNotExist();
    }

    @Test
    void reportsOversizedChangedFileWithoutAttemptingAContentDiff() {
        WorkspaceLimits largerWorkspace = new WorkspaceLimits(512, 512, 64);
        WorkspaceChangeLimits tinyDiffs = new WorkspaceChangeLimits(20, 8, 256);
        RestrictedWorkspaceService workspace = new RestrictedWorkspaceService(storageRoot, largerWorkspace);
        PersistentProjectWorkspaceService changes =
                new PersistentProjectWorkspaceService(storageRoot, largerWorkspace, tinyDiffs);
        WorkspaceBinding binding = workspace.bind("owner-a", "thread-large");
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "outputs/large.txt", bytes("before content"),
                Optional.empty());
        changes.beginRunChanges("owner-a", "thread-large", binding.workspaceId(), "run-large");
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "outputs/large.txt", bytes("after content!"),
                Optional.of(sha256(bytes("before content"))));
        changes.completeRunChanges("owner-a", "thread-large", binding.workspaceId(), "run-large");

        WorkspaceChanges result = changes.readRunChanges(
                "owner-a", "thread-large", binding.workspaceId(), "run-large");
        assertThat(result.files()).hasSize(1);
        assertThat(result.diff().unifiedDiff()).isEmpty();
        assertThat(result.diffUnavailablePaths()).extracting(ProjectPath::value)
                .containsExactly("outputs/large.txt");
    }

    @Test
    void classifiesExtensionlessNulContentAsBinaryAndDecodesUtf16BomText() {
        RestrictedWorkspaceService workspace = workspace();
        WorkspaceBinding binding = workspace.bind("owner-a", "thread-encoding");
        PersistentProjectWorkspaceService changes = projects();
        changes.beginRunChanges("owner-a", "thread-encoding", binding.workspaceId(), "run-encoding");
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "outputs/extensionless",
                new byte[] {0, 1, 2, 3}, Optional.empty());
        byte[] utf16 = "title\n".getBytes(StandardCharsets.UTF_16);
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "workspace/guide.md", utf16, Optional.empty());
        changes.completeRunChanges("owner-a", "thread-encoding", binding.workspaceId(), "run-encoding");

        WorkspaceChanges result = changes.readRunChanges(
                "owner-a", "thread-encoding", binding.workspaceId(), "run-encoding");
        assertThat(result.binaryPaths()).extracting(ProjectPath::value)
                .containsExactly("outputs/extensionless");
        assertThat(result.diff().unifiedDiff()).contains("+++ b/workspace/guide.md", "+title");
    }

    @Test
    void sensitiveSegmentsAreMetadataOnlyAndNeverPersistContentOrContentHash() throws Exception {
        RestrictedWorkspaceService workspace = workspace();
        WorkspaceBinding binding = workspace.bind("owner-a", "thread-sensitive");
        byte[] before = bytes("alpha-secret-value\n");
        byte[] after = bytes("bravo-secret-value\n");
        workspace.writeWorkingFile("owner-a", binding.workspaceId(),
                "workspace/secrets/config.json", before, Optional.empty());
        PersistentProjectWorkspaceService changes = projects();

        changes.beginRunChanges("owner-a", "thread-sensitive", binding.workspaceId(), "run-sensitive");
        Path runRoot = baseline(binding, "run-sensitive").getParent();
        try (var payloads = Files.list(runRoot.resolve("payloads"))) {
            assertThat(payloads).isEmpty();
        }
        String baseline = Files.readString(runRoot.resolve("baseline.properties"));
        assertThat(baseline)
                .doesNotContain(new String(before, StandardCharsets.UTF_8))
                .doesNotContain(Hashing.sha256(before));

        Thread.sleep(5);
        workspace.writeWorkingFile("owner-a", binding.workspaceId(),
                "workspace/secrets/config.json", after, Optional.of(Hashing.sha256(before)));
        changes.completeRunChanges("owner-a", "thread-sensitive", binding.workspaceId(), "run-sensitive");

        WorkspaceChanges result = changes.readRunChanges(
                "owner-a", "thread-sensitive", binding.workspaceId(), "run-sensitive");
        assertThat(result.sensitivePaths()).extracting(ProjectPath::value)
                .containsExactly("workspace/secrets/config.json");
        assertThat(result.diff().unifiedDiff()).isEmpty();
        assertThat(result.lineCounts().get(ProjectPath.of("workspace/secrets/config.json")))
                .isEqualTo(new WorkspaceLineCounts(0, 0));
        try (var payloads = Files.list(runRoot.resolve("completed/payloads"))) {
            assertThat(payloads).isEmpty();
        }
        String completed = Files.readString(runRoot.resolve("completed/baseline.properties"));
        assertThat(completed)
                .doesNotContain(new String(after, StandardCharsets.UTF_8))
                .doesNotContain(Hashing.sha256(after));
        assertThat(WorkspaceSensitivePath.matches("workspace/API_TOKEN/data.json")).isTrue();
        assertThat(WorkspaceSensitivePath.matches("outputs/public/result.txt")).isFalse();
    }

    @Test
    void masksLegacySensitiveSnapshotsWithoutReadingTheirPersistedPayloads() throws Exception {
        RestrictedWorkspaceService workspace = workspace();
        WorkspaceBinding binding = workspace.bind("owner-a", "thread-legacy-sensitive");
        byte[] before = bytes("legacy secret before\n");
        byte[] after = bytes("legacy secret after\n");
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "workspace/public.json", before,
                Optional.empty());
        PersistentProjectWorkspaceService changes = projects();

        changes.beginRunChanges("owner-a", "thread-legacy-sensitive", binding.workspaceId(),
                "run-legacy-sensitive");
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "workspace/public.json", after,
                Optional.of(Hashing.sha256(before)));
        changes.completeRunChanges("owner-a", "thread-legacy-sensitive", binding.workspaceId(),
                "run-legacy-sensitive");

        Path baseline = baseline(binding, "run-legacy-sensitive");
        Path completed = baseline.getParent().resolve("completed/baseline.properties");
        String beforeHash = renameLegacySnapshotPath(
                baseline, "workspace/public.json", "workspace/secrets/config.json");
        String afterHash = renameLegacySnapshotPath(
                completed, "workspace/public.json", "workspace/secrets/config.json");
        Files.delete(baseline.getParent().resolve("payloads").resolve(beforeHash));
        Files.delete(completed.getParent().resolve("payloads").resolve(afterHash));

        WorkspaceChanges result = changes.readRunChanges(
                "owner-a", "thread-legacy-sensitive", binding.workspaceId(), "run-legacy-sensitive");
        assertThat(result.files()).hasSize(1);
        assertThat(result.files().getFirst().before().contentHash()).isEqualTo("sensitive");
        assertThat(result.files().getFirst().after().contentHash()).isEqualTo("sensitive");
        assertThat(result.sensitivePaths()).extracting(ProjectPath::value)
                .containsExactly("workspace/secrets/config.json");
        assertThat(result.diff().unifiedDiff()).isEmpty();
        assertThat(result.lineCounts().get(ProjectPath.of("workspace/secrets/config.json")))
                .isEqualTo(new WorkspaceLineCounts(0, 0));
    }

    @Test
    void countsHeaderLikeContentByPositionAndKeepsCountsWhenAggregateDiffIsTruncated() {
        WorkspaceLimits largerWorkspace = new WorkspaceLimits(512, 512, 64);
        WorkspaceChangeLimits tinyOutput = new WorkspaceChangeLimits(20, 256, 48);
        RestrictedWorkspaceService workspace = new RestrictedWorkspaceService(storageRoot, largerWorkspace);
        PersistentProjectWorkspaceService changes =
                new PersistentProjectWorkspaceService(storageRoot, largerWorkspace, tinyOutput);
        WorkspaceBinding binding = workspace.bind("owner-a", "thread-counts");
        byte[] before = bytes("-- deleted header-like content\n");
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "workspace/counts.txt", before,
                Optional.empty());
        changes.beginRunChanges("owner-a", "thread-counts", binding.workspaceId(), "run-counts");
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "workspace/counts.txt",
                bytes("++ added header-like content\n"), Optional.of(Hashing.sha256(before)));
        changes.completeRunChanges("owner-a", "thread-counts", binding.workspaceId(), "run-counts");

        WorkspaceChanges result = changes.readRunChanges(
                "owner-a", "thread-counts", binding.workspaceId(), "run-counts");
        ProjectPath path = ProjectPath.of("workspace/counts.txt");
        assertThat(result.diff().unifiedDiff()).isEmpty();
        assertThat(result.diffUnavailablePaths()).containsExactly(path);
        assertThat(result.lineCounts().get(path)).isEqualTo(new WorkspaceLineCounts(1, 1));
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void combinesPublishedReadWriteSearchAndDiffContractsWithBoundedFailures() {
        RestrictedWorkspaceService workspace = workspace();
        WorkspaceBinding binding = workspace.bind("owner-a", "thread-1");
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "workspace/note.txt", bytes("alpha\n"),
                Optional.empty());
        PersistentProjectWorkspaceService changes = projects();
        changes.beginRunChanges("owner-a", "thread-1", binding.workspaceId(), "run-1");

        var publicFiles = workspace.workspaceFiles("owner-a", binding.workspaceId());
        WorkspacePath note = new WorkspacePath(binding.workspaceId(), ProjectPath.of("workspace/note.txt"));
        assertThat(publicFiles.read(note, new ReadOptions(0, 32, 100, StandardCharsets.UTF_8, false)).text())
                .isEqualTo("alpha\n");
        assertThat(publicFiles.search(new SearchRequest(
                new WorkspacePath(binding.workspaceId(), ProjectPath.of("workspace")),
                "alpha", 10, 10, 128, true))).hasSize(1);
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "workspace/note.txt", bytes("beta\n"),
                Optional.of(sha256(bytes("alpha\n"))));
        changes.completeRunChanges("owner-a", "thread-1", binding.workspaceId(), "run-1");
        assertThat(changes.readRunChanges("owner-a", "thread-1", binding.workspaceId(), "run-1")
                .diff().unifiedDiff()).contains("-alpha", "+beta");

        assertError(WorkspaceError.HASH_CONFLICT,
                () -> workspace.writeWorkingFile("owner-a", binding.workspaceId(), "workspace/note.txt",
                        bytes("stale"), Optional.of(sha256(bytes("alpha\n")))));
        assertError(WorkspaceError.RUN_BASELINE_NOT_FOUND,
                () -> changes.readRunChanges("owner-a", "thread-1", binding.workspaceId(), "missing-run"));
        assertError(WorkspaceError.OWNER_MISMATCH,
                () -> changes.readRunChanges("owner-a", "other-thread", binding.workspaceId(), "run-1"));
        assertError(WorkspaceError.RUN_ALREADY_TRACKED,
                () -> changes.beginRunChanges("owner-a", "thread-1", binding.workspaceId(), "run-1"));
        assertError(WorkspaceError.FILE_TOO_LARGE,
                () -> changes.diff(new DiffRequest(
                        List.of(new DiffFile(ProjectPath.of("workspace/large.txt"), "", "12345")), 1, 4, 32)));
    }

    @Test
    void emitsUnifiedHunkAndEnforcesExactIncrementalUtf8OutputBoundary() {
        PersistentProjectWorkspaceService changes = projects();
        DiffRequest generous = new DiffRequest(
                List.of(new DiffFile(ProjectPath.of("workspace/unicode.txt"), "old\n", "新\n")), 1, 64, 256);
        var rendered = changes.diff(generous);

        assertThat(rendered.unifiedDiff())
                .contains("--- a/workspace/unicode.txt\n", "+++ b/workspace/unicode.txt\n", "@@ -1,1 +1,1 @@\n");
        assertThat(rendered.outputBytes())
                .isEqualTo(rendered.unifiedDiff().getBytes(StandardCharsets.UTF_8).length);
        assertThat(changes.diff(new DiffRequest(generous.files(), 1, 64, rendered.outputBytes())))
                .isEqualTo(rendered);
        assertError(WorkspaceError.FILE_TOO_LARGE,
                () -> changes.diff(new DiffRequest(generous.files(), 1, 64, rendered.outputBytes() - 1)));
    }

    @Test
    void rejectsTamperedBaselinePathAndHashMetadata() throws IOException {
        RestrictedWorkspaceService workspace = workspace();
        WorkspaceBinding binding = workspace.bind("owner-a", "thread-1");
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "workspace/note.txt", bytes("baseline\n"),
                Optional.empty());
        PersistentProjectWorkspaceService changes = projects();

        changes.beginRunChanges("owner-a", "thread-1", binding.workspaceId(), "run-path");
        Path pathBaseline = baseline(binding, "run-path");
        Properties pathProperties = load(pathBaseline);
        String validValue = pathProperties.stringPropertyNames().stream()
                .filter(key -> key.startsWith("file."))
                .findFirst().map(pathProperties::getProperty).orElseThrow();
        String escapedKey = "file." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("../escape".getBytes(StandardCharsets.UTF_8));
        pathProperties.setProperty(escapedKey, validValue);
        store(pathProperties, pathBaseline);
        assertError(WorkspaceError.CONTENT_INTEGRITY,
                () -> changes.readRunChanges("owner-a", "thread-1", binding.workspaceId(), "run-path"));

        changes.beginRunChanges("owner-a", "thread-1", binding.workspaceId(), "run-hash");
        Path hashBaseline = baseline(binding, "run-hash");
        Properties hashProperties = load(hashBaseline);
        String fileKey = hashProperties.stringPropertyNames().stream()
                .filter(key -> key.startsWith("file."))
                .findFirst().orElseThrow();
        String size = hashProperties.getProperty(fileKey).split(":", 2)[0];
        hashProperties.setProperty(fileKey, size + ":" + "0".repeat(64));
        store(hashProperties, hashBaseline);
        assertError(WorkspaceError.CONTENT_INTEGRITY,
                () -> changes.readRunChanges("owner-a", "thread-1", binding.workspaceId(), "run-hash"));
    }

    @Test
    void rejectsTamperedProjectDocumentMetadataAsContentIntegrityFailure() throws IOException {
        List<Consumer<Properties>> corruptions = List.of(
                properties -> properties.setProperty("id", "doc-00000000000000000000000000000000"),
                properties -> properties.setProperty("projectId", "foreign-project"),
                properties -> properties.setProperty("sha256", "A".repeat(64)),
                properties -> properties.setProperty("sizeBytes", "65"),
                properties -> properties.setProperty("name", "../escape"),
                properties -> properties.setProperty("createdAt", "not-an-instant"));

        for (int index = 0; index < corruptions.size(); index++) {
            String projectId = "project-tamper-" + index;
            PersistentProjectWorkspaceService projects = projects();
            projects.bindProject("owner-a", projectId);
            ProjectDocument document = projects.uploadProjectDocument(
                    "owner-a", projectId, "document.txt", bytes("content-" + index)).document();
            Path metadata = documentMetadata(projectId, document.id());
            Properties properties = load(metadata);
            corruptions.get(index).accept(properties);
            store(properties, metadata);

            assertError(WorkspaceError.CONTENT_INTEGRITY,
                    () -> projects.listProjectDocuments("owner-a", projectId));
        }
    }

    private RestrictedWorkspaceService workspace() {
        return new RestrictedWorkspaceService(storageRoot, workspaceLimits);
    }

    private PersistentProjectWorkspaceService projects() {
        return new PersistentProjectWorkspaceService(storageRoot, workspaceLimits, changeLimits);
    }

    private Path baseline(WorkspaceBinding binding, String runId) {
        return storageRoot.resolve("workspace-changes").resolve(binding.workspaceId().value())
                .resolve("run-" + Hashing.sha256(runId).substring(0, 32)).resolve("baseline.properties");
    }

    private Path documentMetadata(String projectId, String documentId) {
        return storageRoot.resolve("projects")
                .resolve("prj-" + Hashing.sha256(projectId).substring(0, 32))
                .resolve("documents").resolve(documentId + ".properties");
    }

    private static Properties load(Path path) throws IOException {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static void store(Properties properties, Path path) throws IOException {
        try (var output = Files.newOutputStream(path, StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(output, "tampered by negative test");
        }
    }

    private static String renameLegacySnapshotPath(Path baseline, String from, String to) throws IOException {
        Properties properties = load(baseline);
        String fromKey = "file." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(from.getBytes(StandardCharsets.UTF_8));
        String toKey = "file." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(to.getBytes(StandardCharsets.UTF_8));
        String legacyValue = properties.remove(fromKey).toString();
        assertThat(legacyValue).matches("\\d+:[0-9a-f]{64}");
        properties.setProperty(toKey, legacyValue);
        store(properties, baseline);
        return legacyValue.substring(legacyValue.indexOf(':') + 1);
    }

    private static void assertError(WorkspaceError expected, ThrowingAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(WorkspaceException.class)
                .extracting(exception -> ((WorkspaceException) exception).error())
                .isEqualTo(expected);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] value) {
        return Hashing.sha256(value);
    }

    @Test
    void projectProfilesAreOwnerScopedPersistentAndPatchedAtomically() {
        PersistentProjectWorkspaceService projects = projects();
        ProjectProfile created = projects.createProject(
                "owner-a", "Alpha", "Be concise", Map.of("icon", "folder", "color", "blue"));

        assertThat(created.id()).matches("[0-9a-f]{32}");
        assertThat(projects.listProjects("owner-a", Optional.of("active")))
                .extracting(ProjectProfile::id)
                .containsExactly(created.id());
        assertThat(projects.listProjects("owner-a", Optional.of("archived"))).isEmpty();
        assertThat(projects.projectExists("owner-a", created.id())).isTrue();
        assertThat(projects.projectExists("owner-b", created.id())).isFalse();

        ProjectProfile patched = projects.patchProject("owner-a", created.id(), Optional.of("Renamed"),
                Optional.empty(), Optional.of(Map.of("color", "green")));
        assertThat(patched.name()).isEqualTo("Renamed");
        assertThat(patched.instructions()).isEqualTo("Be concise");
        assertThat(patched.presentation()).containsExactlyEntriesOf(Map.of("color", "green"));
        assertThat(patched.createdAt()).isEqualTo(created.createdAt());
        assertThat(patched.updatedAt()).isAfterOrEqualTo(created.updatedAt());

        PersistentProjectWorkspaceService restarted = projects();
        assertThat(restarted.getProject("owner-a", created.id())).isEqualTo(patched);
        assertThatThrownBy(() -> restarted.getProject("owner-b", created.id()))
                .isInstanceOfSatisfying(WorkspaceException.class,
                        failure -> assertThat(failure.error()).isEqualTo(WorkspaceError.OWNER_MISMATCH));
    }

    @Test
    void projectProfileValidationIsUtf8BoundedAndFailedPatchPreservesMetadata() {
        PersistentProjectWorkspaceService projects = projects();
        ProjectProfile created = projects.createProject("owner-a", "Alpha", "", Map.of());
        String oversized = "界".repeat(2731);

        assertThatThrownBy(() -> projects.patchProject("owner-a", created.id(), Optional.empty(),
                Optional.of(oversized), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(projects.getProject("owner-a", created.id())).isEqualTo(created);
        assertThatThrownBy(() -> projects.createProject("owner-a", "", "", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projects.createProject("owner-a", "ok", "", Map.of("other", "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void projectArchiveRestoreDeleteLifecycleAndArchivedRejection() {
        PersistentProjectWorkspaceService projects = projects();
        ProjectProfile project = projects.createProject("owner-a", "Alpha", "Instructions", Map.of());
        String projectId = project.id();

        projects.uploadProjectDocument("owner-a", projectId, "notes.txt", bytes("live content\n"));
        assertThat(projects.listProjectDocuments("owner-a", projectId)).hasSize(1);

        // Archive project
        ProjectProfile archived = projects.archiveProject("owner-a", projectId);
        assertThat(archived.status()).isEqualTo("archived");
        assertThat(projects.listProjects("owner-a", Optional.of("archived")))
                .extracting(ProjectProfile::id).containsExactly(projectId);
        assertThat(projects.listProjects("owner-a", Optional.of("active"))).isEmpty();

        // Archived project still allows read access to shelf documents
        assertThat(projects.listProjectDocuments("owner-a", projectId)).hasSize(1);

        // Archived project rejects uploads, promote, and individual document trashing (fail-closed 404)
        assertError(WorkspaceError.PROJECT_NOT_FOUND,
                () -> projects.uploadProjectDocument("owner-a", projectId, "new.txt", bytes("blocked\n")));
        String docId = projects.listProjectDocuments("owner-a", projectId).get(0).id();
        assertError(WorkspaceError.PROJECT_NOT_FOUND,
                () -> projects.trashProjectDocument("owner-a", projectId, docId));

        // Restore project
        ProjectProfile restored = projects.restoreProject("owner-a", projectId);
        assertThat(restored.status()).isEqualTo("active");
        assertThat(projects.listProjects("owner-a", Optional.of("active")))
                .extracting(ProjectProfile::id).containsExactly(projectId);

        // Now upload works again
        projects.uploadProjectDocument("owner-a", projectId, "doc2.txt", bytes("doc 2\n"));
        assertThat(projects.listProjectDocuments("owner-a", projectId)).hasSize(2);

        // Deleting project trashes all its live documents and unlinks the project directory
        projects.deleteProject("owner-a", projectId);
        assertThat(projects.projectExists("owner-a", projectId)).isFalse();
        assertError(WorkspaceError.PROJECT_NOT_FOUND, () -> projects.getProject("owner-a", projectId));

        // Documents are preserved in trash with trashOrigin matching the deleted project
        TrashDocumentList trashList = projects.listTrashDocuments("owner-a", 10, 0);
        assertThat(trashList.total()).isEqualTo(2);
        assertThat(trashList.documents())
                .extracting(d -> d.trashOrigin().orElseThrow().projectId())
                .containsOnly(projectId);
        assertThat(trashList.documents())
                .extracting(d -> d.trashOrigin().orElseThrow().projectName())
                .containsOnly("Alpha");

        // Verify restart persistence of trash after project deletion
        PersistentProjectWorkspaceService restarted = projects();
        TrashDocumentList restartedTrash = restarted.listTrashDocuments("owner-a", 10, 0);
        assertThat(restartedTrash.total()).isEqualTo(2);
    }

    @Test
    void documentTrashListRestoreMergeAndPurgeLifecycle() throws IOException {
        PersistentProjectWorkspaceService projects = projects();
        ProjectProfile p1 = projects.createProject("owner-a", "P1", "", Map.of());
        ProjectProfile p2 = projects.createProject("owner-a", "P2", "", Map.of());

        byte[] contentA = bytes("content A\n");
        byte[] contentB = bytes("content B\n");
        ProjectDocumentReceipt receiptA = projects.uploadProjectDocument("owner-a", p1.id(), "docA.txt", contentA);
        ProjectDocumentReceipt receiptB = projects.uploadProjectDocument("owner-a", p1.id(), "docB.txt", contentB);
        String docAId = receiptA.document().id();
        String docBId = receiptB.document().id();

        // Trash docA
        projects.trashProjectDocument("owner-a", p1.id(), docAId);
        assertThat(projects.listProjectDocuments("owner-a", p1.id()))
                .extracting(ProjectDocument::id).containsExactly(docBId);

        // List trash
        TrashDocumentList trash = projects.listTrashDocuments("owner-a", 10, 0);
        assertThat(trash.total()).isEqualTo(1);
        assertThat(trash.documents().get(0).id()).isEqualTo(docAId);
        assertThat(trash.documents().get(0).name()).isEqualTo("docA.txt");
        assertThat(trash.documents().get(0).trashOrigin().orElseThrow().projectId()).isEqualTo(p1.id());

        // Restore docA to origin project
        RestoreDocumentResult restoredA = projects.restoreTrashDocument("owner-a", docAId, Optional.empty());
        assertThat(restoredA.outcome()).isEqualTo("restored");
        assertThat(restoredA.document().id()).isEqualTo(docAId);
        assertThat(restoredA.document().projectId()).isEqualTo(p1.id());
        assertThat(projects.listProjectDocuments("owner-a", p1.id())).hasSize(2);
        assertThat(projects.listTrashDocuments("owner-a", 10, 0).total()).isEqualTo(0);

        // Upload same content to P2
        ProjectDocumentReceipt receiptC = projects.uploadProjectDocument("owner-a", p2.id(), "docC.txt", contentA);

        // Trash docA again and restore to P2 -> should MERGE because content matches
        projects.trashProjectDocument("owner-a", p1.id(), docAId);
        RestoreDocumentResult mergedResult = projects.restoreTrashDocument("owner-a", docAId, Optional.of(p2.id()));
        assertThat(mergedResult.outcome()).isEqualTo("merged");
        assertThat(mergedResult.document().id()).isEqualTo(receiptC.document().id());
        assertThat(projects.listTrashDocuments("owner-a", 10, 0).total()).isEqualTo(0);

        // Trash docB, test content_missing on corrupted payload
        projects.trashProjectDocument("owner-a", p1.id(), docBId);
        Path trashPayload = storageRoot.resolve("trash").resolve("payloads").resolve(receiptB.document().sha256());
        Files.writeString(trashPayload, "corrupted", StandardOpenOption.TRUNCATE_EXISTING);

        assertError(WorkspaceError.CONTENT_INTEGRITY,
                () -> projects.restoreTrashDocument("owner-a", docBId, Optional.of(p1.id())));
        // Document remains in trash
        assertThat(projects.listTrashDocuments("owner-a", 10, 0).total()).isEqualTo(1);

        // Single purge
        projects.purgeTrashDocument("owner-a", docBId);
        assertThat(projects.listTrashDocuments("owner-a", 10, 0).total()).isEqualTo(0);

        // Empty trash
        projects.uploadProjectDocument("owner-a", p1.id(), "temp1.txt", bytes("1"));
        projects.uploadProjectDocument("owner-a", p1.id(), "temp2.txt", bytes("2"));
        String t1 = projects.listProjectDocuments("owner-a", p1.id()).get(0).id();
        String t2 = projects.listProjectDocuments("owner-a", p1.id()).get(1).id();
        projects.trashProjectDocument("owner-a", p1.id(), t1);
        projects.trashProjectDocument("owner-a", p1.id(), t2);
        assertThat(projects.listTrashDocuments("owner-a", 10, 0).total()).isEqualTo(2);

        int purgedCount = projects.emptyTrash("owner-a");
        assertThat(purgedCount).isEqualTo(2);
        assertThat(projects.listTrashDocuments("owner-a", 10, 0).total()).isEqualTo(0);
    }

    @Test
    void purgeTrashDocumentRetainsRowAndAllowsRetryWhenPayloadUnlinkFails() throws Exception {
        PersistentProjectWorkspaceService projects = projects();
        ProjectProfile p = projects.createProject("owner-a", "P-Purge", "", Map.of());
        byte[] content = bytes("purge failure test\n");
        ProjectDocumentReceipt receipt = projects.uploadProjectDocument("owner-a", p.id(), "doc.txt", content);
        String docId = receipt.document().id();
        String sha = receipt.document().sha256();

        projects.trashProjectDocument("owner-a", p.id(), docId);
        assertThat(projects.listTrashDocuments("owner-a", 10, 0).total()).isEqualTo(1);

        Path payloadFile = storageRoot.resolve("trash").resolve("payloads").resolve(sha);
        Path payloadsDir = payloadFile.getParent();
        // Inject delete failure: Windows denies unlink of a read-only file; POSIX still allows
        // the owner to unlink a read-only file, so clear write on the payloads directory instead.
        Set<PosixFilePermission> originalDirPermissions = null;
        boolean posixLock = false;
        try {
            try {
                originalDirPermissions = Files.getPosixFilePermissions(payloadsDir);
                Files.setPosixFilePermissions(
                        payloadsDir,
                        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
                posixLock = true;
            } catch (UnsupportedOperationException unsupported) {
                Assumptions.assumeTrue(payloadFile.toFile().setReadOnly(), "unable to mark payload read-only");
            }
            // Attempting to purge should fail because payload deletion fails
            assertError(WorkspaceError.IO_FAILURE, () -> projects.purgeTrashDocument("owner-a", docId));

            // Metadata row MUST still exist in trash!
            TrashDocumentList list = projects.listTrashDocuments("owner-a", 10, 0);
            assertThat(list.total()).isEqualTo(1);
            assertThat(list.documents().get(0).id()).isEqualTo(docId);
        } finally {
            if (posixLock && originalDirPermissions != null) {
                Files.setPosixFilePermissions(payloadsDir, originalDirPermissions);
            } else {
                payloadFile.toFile().setWritable(true);
            }
        }

        // Lock/read-only released: now purge should succeed and row is removed
        projects.purgeTrashDocument("owner-a", docId);
        assertThat(projects.listTrashDocuments("owner-a", 10, 0).total()).isEqualTo(0);
        assertThat(Files.exists(payloadFile)).isFalse();
    }

    @Test
    void purgeWithSharedPayloadOnlyDeletesPayloadWhenLastReferencePurged() throws Exception {
        PersistentProjectWorkspaceService projects = projects();
        ProjectProfile p1 = projects.createProject("owner-a", "P-Shared-1", "", Map.of());
        ProjectProfile p2 = projects.createProject("owner-a", "P-Shared-2", "", Map.of());
        byte[] content = bytes("shared content identical\n");
        ProjectDocumentReceipt r1 = projects.uploadProjectDocument("owner-a", p1.id(), "doc1.txt", content);
        ProjectDocumentReceipt r2 = projects.uploadProjectDocument("owner-a", p2.id(), "doc2.txt", content);
        String id1 = r1.document().id();
        String id2 = r2.document().id();
        String sha = r1.document().sha256();

        projects.trashProjectDocument("owner-a", p1.id(), id1);
        projects.trashProjectDocument("owner-a", p2.id(), id2);
        assertThat(projects.listTrashDocuments("owner-a", 10, 0).total()).isEqualTo(2);

        Path payloadFile = storageRoot.resolve("trash").resolve("payloads").resolve(sha);
        assertThat(Files.exists(payloadFile)).isTrue();

        // Purge first document: payload is shared with id2, so payload MUST NOT be deleted
        projects.purgeTrashDocument("owner-a", id1);
        assertThat(projects.listTrashDocuments("owner-a", 10, 0).total()).isEqualTo(1);
        assertThat(Files.exists(payloadFile)).isTrue();

        // Purge second document: no more references, payload MUST now be deleted
        projects.purgeTrashDocument("owner-a", id2);
        assertThat(projects.listTrashDocuments("owner-a", 10, 0).total()).isEqualTo(0);
        assertThat(Files.exists(payloadFile)).isFalse();
    }

    @Test
    void restoreTrashDocumentVerifiesTargetPayloadBeforeMerging() throws Exception {
        PersistentProjectWorkspaceService projects = projects();
        ProjectProfile p1 = projects.createProject("owner-a", "P1-TargetCheck", "", Map.of());
        ProjectProfile p2 = projects.createProject("owner-a", "P2-TargetCheck", "", Map.of());
        byte[] content = bytes("merge check content\n");
        ProjectDocumentReceipt r1 = projects.uploadProjectDocument("owner-a", p1.id(), "doc1.txt", content);
        ProjectDocumentReceipt r2 = projects.uploadProjectDocument("owner-a", p2.id(), "doc2.txt", content);
        String id1 = r1.document().id();
        String sha = r1.document().sha256();

        // Trash doc1
        projects.trashProjectDocument("owner-a", p1.id(), id1);
        assertThat(projects.listTrashDocuments("owner-a", 10, 0).total()).isEqualTo(1);

        // Corrupt P2's payload file
        Path p2Payload = storageRoot.resolve("projects")
                .resolve("prj-" + Hashing.sha256(p2.id()).substring(0, 32))
                .resolve("payloads").resolve(sha);
        Files.writeString(p2Payload, "corrupted target bytes", StandardOpenOption.TRUNCATE_EXISTING);

        // Restore doc1 into P2 -> merge candidate exists, but target payload is corrupted -> MUST fail with CONTENT_INTEGRITY (409)
        assertError(WorkspaceError.CONTENT_INTEGRITY,
                () -> projects.restoreTrashDocument("owner-a", id1, Optional.of(p2.id())));

        // doc1 MUST still be preserved in trash!
        assertThat(projects.listTrashDocuments("owner-a", 10, 0).total()).isEqualTo(1);
        // Source trash payload MUST still be intact!
        Path trashPayload = storageRoot.resolve("trash").resolve("payloads").resolve(sha);
        assertThat(Files.readAllBytes(trashPayload)).isEqualTo(content);
    }

    @Test
    void deleteProjectWithThreadUnbindFailurePreservesProject() throws IOException {
        PersistentProjectWorkspaceService projects = projects();
        ProjectProfile p = projects.createProject("owner-a", "P-UnbindFail", "", Map.of());
        byte[] docBytes = bytes("test active document bytes\n");
        ProjectDocumentReceipt receipt = projects.uploadProjectDocument("owner-a", p.id(), "test.txt", docBytes);
        String docId = receipt.document().id();
        Path projectDir = storageRoot.resolve("projects")
                .resolve("prj-" + Hashing.sha256(p.id()).substring(0, 32));

        // Unbind action throws IOException
        assertError(WorkspaceError.IO_FAILURE,
                () -> projects.deleteProject("owner-a", p.id(), () -> {
                    throw new IOException("simulated thread unbind persistence error");
                }));

        // 1. Project directory and profile must STILL exist!
        assertThat(projects.projectExists("owner-a", p.id())).isTrue();
        assertThat(projects.getProject("owner-a", p.id()).name()).isEqualTo("P-UnbindFail");
        assertThat(Files.exists(projectDir)).isTrue();

        // 2. Active documents must STILL exist in project: listable and readable with exact bytes!
        List<ProjectDocument> docs = projects.listProjectDocuments("owner-a", p.id());
        assertThat(docs).extracting(ProjectDocument::id).containsExactly(docId);
        ProjectDocumentContent content = projects.readProjectDocument("owner-a", p.id(), docId, 0, 32);
        assertThat(content.bytes()).isEqualTo(docBytes);

        // 3. No documents leaked to trash during failed deletion!
        assertThat(projects.listTrashDocuments("owner-a", 10, 0).total()).isEqualTo(0);

        // 4. Verify restart persistence: new service instance sees project and documents intact!
        PersistentProjectWorkspaceService restarted = projects();
        assertThat(restarted.projectExists("owner-a", p.id())).isTrue();
        assertThat(restarted.getProject("owner-a", p.id()).name()).isEqualTo("P-UnbindFail");
        assertThat(restarted.listProjectDocuments("owner-a", p.id()))
                .extracting(ProjectDocument::id).containsExactly(docId);
        assertThat(restarted.readProjectDocument("owner-a", p.id(), docId, 0, 32).bytes()).isEqualTo(docBytes);
        assertThat(restarted.listTrashDocuments("owner-a", 10, 0).total()).isEqualTo(0);

        // 5. Retry with succeeding unbind action: project deleted and documents moved to trash!
        restarted.deleteProject("owner-a", p.id(), () -> {});
        assertThat(restarted.projectExists("owner-a", p.id())).isFalse();
        assertError(WorkspaceError.PROJECT_NOT_FOUND, () -> restarted.getProject("owner-a", p.id()));
        assertThat(Files.exists(projectDir)).isFalse();

        TrashDocumentList trash = restarted.listTrashDocuments("owner-a", 10, 0);
        assertThat(trash.total()).isEqualTo(1);
        assertThat(trash.documents().get(0).id()).isEqualTo(docId);
        assertThat(trash.documents().get(0).name()).isEqualTo("test.txt");
    }

    @Test
    void retentionSweepPurgesExpiredTrashDocumentsOnList() throws IOException {
        PersistentProjectWorkspaceService projects = projects();
        ProjectProfile pA = projects.createProject("owner-a", "PA", "", Map.of());
        ProjectProfile pB = projects.createProject("owner-b", "PB", "", Map.of());

        ProjectDocumentReceipt rA1 = projects.uploadProjectDocument("owner-a", pA.id(), "a1.txt", bytes("a1"));
        ProjectDocumentReceipt rA2 = projects.uploadProjectDocument("owner-a", pA.id(), "a2.txt", bytes("a2"));
        ProjectDocumentReceipt rB1 = projects.uploadProjectDocument("owner-b", pB.id(), "b1.txt", bytes("b1"));

        projects.trashProjectDocument("owner-a", pA.id(), rA1.document().id());
        projects.trashProjectDocument("owner-a", pA.id(), rA2.document().id());
        projects.trashProjectDocument("owner-b", pB.id(), rB1.document().id());

        // Artificially age a1 to 35 days ago (expired), a2 to 5 days ago (active)
        Path a1Trash = storageRoot.resolve("trash").resolve("documents").resolve(rA1.document().id() + ".properties");
        updateProperties(a1Trash, props -> props.setProperty("trashedAt", Instant.now().minus(35, ChronoUnit.DAYS).toString()));

        Path b1Trash = storageRoot.resolve("trash").resolve("documents").resolve(rB1.document().id() + ".properties");
        updateProperties(b1Trash, props -> props.setProperty("trashedAt", Instant.now().minus(40, ChronoUnit.DAYS).toString()));

        // When owner-a lists trash, 30-day retention sweep runs:
        // a1 (35 days old) is purged!
        // a2 (5 days old) is retained!
        TrashDocumentList listA = projects.listTrashDocuments("owner-a", 10, 0);
        assertThat(listA.total()).isEqualTo(1);
        assertThat(listA.documents().get(0).id()).isEqualTo(rA2.document().id());
        assertThat(Files.exists(a1Trash)).isFalse();

        // owner-b's trash document (b1) was NOT touched by owner-a's listing (owner isolation)
        assertThat(Files.exists(b1Trash)).isTrue();
        // When owner-b lists trash, b1 is swept
        TrashDocumentList listB = projects.listTrashDocuments("owner-b", 10, 0);
        assertThat(listB.total()).isEqualTo(0);
        assertThat(Files.exists(b1Trash)).isFalse();
    }

    private static void updateProperties(Path path, Consumer<Properties> updater) throws IOException {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(path)) {
            properties.load(in);
        }
        updater.accept(properties);
        try (var out = Files.newOutputStream(path)) {
            properties.store(out, null);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}

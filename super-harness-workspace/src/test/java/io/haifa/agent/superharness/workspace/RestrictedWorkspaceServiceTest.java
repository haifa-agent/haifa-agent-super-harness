package io.haifa.agent.superharness.workspace;

import io.haifa.agent.artifact.Artifact;
import io.haifa.agent.project.filesystem.ReadOptions;
import io.haifa.agent.project.filesystem.SearchRequest;
import io.haifa.agent.project.filesystem.WorkspaceFileService;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestrictedWorkspaceServiceTest {
    @TempDir
    Path storageRoot;

    private final WorkspaceLimits limits = new WorkspaceLimits(32, 64, 16);

    @Test
    void bindsOwnerAndThreadToPersistentRootAndRejectsCrossOwner() {
        RestrictedWorkspaceService first = service();
        WorkspaceBinding binding = first.bind("owner-a", "thread-1");
        first.writeWorkingFile("owner-a", binding.workspaceId(), "workspace/note.txt",
                bytes("persistent"), Optional.empty());

        RestrictedWorkspaceService restarted = service();
        WorkspaceBinding rebound = restarted.bind("owner-a", "thread-1");

        assertThat(rebound).isEqualTo(binding);
        assertThat(restarted.read("owner-a", rebound.workspaceId(),
                "/mnt/user-data/workspace/note.txt", 0, 16).bytes()).isEqualTo(bytes("persistent"));
        assertThatThrownBy(() -> restarted.read("owner-b", binding.workspaceId(),
                "workspace/note.txt", 0, 16))
                .isInstanceOf(WorkspaceException.class)
                .extracting(exception -> ((WorkspaceException) exception).error())
                .isEqualTo(WorkspaceError.OWNER_MISMATCH);
    }

    @Test
    void normalizesOnlySupportedMountPathsAndRejectsEscapes() {
        RestrictedWorkspaceService workspace = service();
        WorkspaceBinding binding = workspace.bind("owner-a", "thread-1");

        workspace.writeWorkingFile("owner-a", binding.workspaceId(),
                "/mnt/user-data/outputs/report.md", bytes("ok"), Optional.empty());
        assertThat(workspace.read("owner-a", binding.workspaceId(), "outputs/report.md", 0, 16).bytes())
                .isEqualTo(bytes("ok"));

        for (String invalid : new String[]{"../secret", "workspace/../secret", "/etc/passwd",
                "C:/Windows/win.ini", "/mnt/skills/public/SKILL.md", "workspace//file"}) {
            assertThatThrownBy(() -> workspace.read("owner-a", binding.workspaceId(), invalid, 0, 16))
                    .as(invalid)
                    .isInstanceOf(WorkspaceException.class)
                    .extracting(exception -> ((WorkspaceException) exception).error())
                    .isIn(WorkspaceError.INVALID_PATH, WorkspaceError.PATH_ESCAPE);
        }
    }

    @Test
    void keepsUploadOriginalsImmutableAndUsesDeterministicCollisionNames() {
        RestrictedWorkspaceService workspace = service();
        WorkspaceBinding binding = workspace.bind("owner-a", "thread-1");

        UploadReceipt first = workspace.upload("owner-a", binding.workspaceId(), "data.csv",
                bytes("a,b\n1,2\n"), "text/csv");
        UploadReceipt duplicate = workspace.upload("owner-a", binding.workspaceId(), "data.csv",
                bytes("a,b\n1,2\n"), "text/csv");
        UploadReceipt collision = workspace.upload("owner-a", binding.workspaceId(), "data.csv",
                bytes("a,b\n3,4\n"), "text/csv");

        assertThat(first.logicalPath()).isEqualTo("/mnt/user-data/uploads/data.csv");
        assertThat(duplicate.logicalPath()).isEqualTo(first.logicalPath());
        assertThat(duplicate.deduplicated()).isTrue();
        assertThat(collision.logicalPath()).matches("/mnt/user-data/uploads/data__[0-9a-f]{12}\\.csv");
        assertThat(workspace.read("owner-a", binding.workspaceId(), first.logicalPath(), 0, 16).bytes())
                .isEqualTo(bytes("a,b\n1,2\n"));
        assertThatThrownBy(() -> workspace.writeWorkingFile("owner-a", binding.workspaceId(),
                first.logicalPath(), bytes("changed"), Optional.empty()))
                .isInstanceOf(WorkspaceException.class)
                .extracting(exception -> ((WorkspaceException) exception).error())
                .isEqualTo(WorkspaceError.IMMUTABLE_UPLOAD);
    }

    @Test
    void publishesExactWorkspaceBytesWithHashAndProvenanceAcrossRestart() throws Exception {
        RestrictedWorkspaceService workspace = service();
        WorkspaceBinding binding = workspace.bind("owner-a", "thread-1");
        byte[] report = bytes("verified artifact\n");
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "outputs/report.md", report,
                Optional.empty());
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(report));
        ArtifactPublishContext context = new ArtifactPublishContext("project-1", UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "exec-1", "download");

        Artifact artifact = workspace.publish("owner-a", binding.workspaceId(), "outputs/report.md",
                "Report", "document", "text/markdown", context);

        assertThat(artifact.payload().sha256()).isEqualTo("sha256:" + expectedHash);
        assertThat(artifact.payload().byteCount()).isEqualTo(report.length);
        assertThat(artifact.provenance().sourceLogicalPath())
                .isEqualTo("/mnt/user-data/outputs/report.md");
        assertThat(artifact.provenance().sourceHash()).isEqualTo("sha256:" + expectedHash);
        assertThat(artifact.provenance().sourceHash()).isEqualTo(artifact.payload().sha256());
        assertThat(artifact.provenance().createdBy().principalId()).isEqualTo("owner-a");
        assertThat(workspace.loadArtifact("owner-a", artifact)).isEqualTo(report);

        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "outputs/report.md", bytes("new draft"),
                Optional.of(expectedHash));
        RestrictedWorkspaceService restarted = service();
        assertThat(restarted.loadArtifact("owner-a", artifact)).isEqualTo(report);
        assertThat(restarted.findArtifacts("owner-a", binding.workspaceId(), "project-1"))
                .singleElement().isEqualTo(artifact);
        assertThat(restarted.read("owner-a", binding.workspaceId(), "outputs/report.md", 0, 16).bytes())
                .isEqualTo(bytes("new draft"));
    }

    @Test
    void exposesPublishedWorkspaceReadApiAndEnforcesMissingRangeAndSizeFailures() {
        RestrictedWorkspaceService workspace = service();
        WorkspaceBinding binding = workspace.bind("owner-a", "thread-1");
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "workspace/notes.txt",
                bytes("alpha\nbeta\n"), Optional.empty());
        WorkspaceFileService publicApi = workspace.workspaceFiles("owner-a", binding.workspaceId());
        WorkspacePath path = new WorkspacePath(binding.workspaceId(), ProjectPath.of("workspace/notes.txt"));

        assertThat(publicApi.stat(path, true).contentHash()).contains(sha256(bytes("alpha\nbeta\n")));
        assertThat(publicApi.read(path,
                new ReadOptions(0, 16, 100, StandardCharsets.UTF_8, false)).text())
                .isEqualTo("alpha\nbeta\n");
        assertThat(publicApi.search(new SearchRequest(
                new WorkspacePath(binding.workspaceId(), ProjectPath.of("workspace")),
                "beta", 10, 10, 64, true)))
                .singleElement().satisfies(result -> {
                    assertThat(result.line()).isEqualTo(2);
                    assertThat(result.column()).isEqualTo(1);
                });

        assertThatThrownBy(() -> workspace.read("owner-a", binding.workspaceId(),
                "workspace/missing.txt", 0, 16))
                .isInstanceOf(WorkspaceException.class)
                .extracting(exception -> ((WorkspaceException) exception).error())
                .isEqualTo(WorkspaceError.FILE_NOT_FOUND);
        assertThatThrownBy(() -> workspace.read("owner-a", binding.workspaceId(),
                "workspace/notes.txt", 99, 16))
                .isInstanceOf(WorkspaceException.class)
                .extracting(exception -> ((WorkspaceException) exception).error())
                .isEqualTo(WorkspaceError.READ_OUT_OF_BOUNDS);
        assertThatThrownBy(() -> workspace.upload("owner-a", binding.workspaceId(), "large.bin",
                new byte[33], "application/octet-stream"))
                .isInstanceOf(WorkspaceException.class)
                .extracting(exception -> ((WorkspaceException) exception).error())
                .isEqualTo(WorkspaceError.FILE_TOO_LARGE);
        assertThatThrownBy(() -> workspace.read("owner-a", binding.workspaceId(),
                "workspace/notes.txt", 0, 17))
                .isInstanceOf(WorkspaceException.class)
                .extracting(exception -> ((WorkspaceException) exception).error())
                .isEqualTo(WorkspaceError.READ_OUT_OF_BOUNDS);
    }

    @Test
    void rejectsStaleWriteHashWithoutChangingTheFile() {
        RestrictedWorkspaceService workspace = service();
        WorkspaceBinding binding = workspace.bind("owner-a", "thread-1");
        workspace.writeWorkingFile("owner-a", binding.workspaceId(), "workspace/state.txt",
                bytes("v1"), Optional.empty());

        assertThatThrownBy(() -> workspace.writeWorkingFile("owner-a", binding.workspaceId(),
                "workspace/state.txt", bytes("v2"), Optional.of(sha256(bytes("other")))))
                .isInstanceOf(WorkspaceException.class)
                .extracting(exception -> ((WorkspaceException) exception).error())
                .isEqualTo(WorkspaceError.HASH_CONFLICT);
        assertThat(workspace.read("owner-a", binding.workspaceId(), "workspace/state.txt", 0, 16).bytes())
                .isEqualTo(bytes("v1"));
    }

    @Test
    void readsVerifiedBytesOnceAndOnlyRollsBackNewReceipt() {
        RestrictedWorkspaceService workspace = service();
        WorkspaceBinding binding = workspace.bind("owner-a", "thread-rollback");
        UploadReceipt created = workspace.upload(
                "owner-a", binding.workspaceId(), "input.txt", bytes("payload"), "text/plain");

        assertThat(workspace.readVerifiedBytes(
                "owner-a", binding.workspaceId(), created.logicalPath())).isEqualTo(bytes("payload"));
        workspace.rollbackNewUpload("owner-a", binding.workspaceId(), created);
        assertThatThrownBy(() -> workspace.readVerifiedBytes(
                "owner-a", binding.workspaceId(), created.logicalPath()))
                .isInstanceOf(WorkspaceException.class)
                .extracting(exception -> ((WorkspaceException) exception).error())
                .isEqualTo(WorkspaceError.FILE_NOT_FOUND);

        UploadReceipt recreated = workspace.upload(
                "owner-a", binding.workspaceId(), "input.txt", bytes("payload"), "text/plain");
        UploadReceipt existing = workspace.upload(
                "owner-a", binding.workspaceId(), "input.txt", bytes("payload"), "text/plain");
        assertThat(recreated.deduplicated()).isFalse();
        assertThat(existing.deduplicated()).isTrue();
        assertThatThrownBy(() -> workspace.rollbackNewUpload("owner-a", binding.workspaceId(), existing))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(workspace.readVerifiedBytes(
                "owner-a", binding.workspaceId(), recreated.logicalPath())).isEqualTo(bytes("payload"));
    }

    private RestrictedWorkspaceService service() {
        return new RestrictedWorkspaceService(storageRoot, limits);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}

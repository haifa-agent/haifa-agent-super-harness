package io.haifa.agent.superharness.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceRegistryMoveTest {
    @TempDir
    Path directory;

    @Test
    void anExistingTargetIsReportedAsAlreadyExistingOnEveryPlatform() throws Exception {
        Path source = Files.createDirectory(directory.resolve(".completed-2"));
        Path target = Files.createDirectory(directory.resolve("completed"));
        Files.writeString(target.resolve("baseline.properties"), "runId=r0");

        assertThatThrownBy(() -> WorkspaceRegistry.moveAtomically(source, target))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(target.resolve("baseline.properties")).hasContent("runId=r0");
    }
}

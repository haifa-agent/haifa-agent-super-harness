package io.haifa.agent.superharness.workspace;

import io.haifa.agent.project.changeset.FileChange;
import io.haifa.agent.project.diff.DiffResult;
import io.haifa.agent.project.path.ProjectPath;

import java.util.List;

/** Core change evidence. HTTP projection to DeerFlow's response shape remains a server concern. */
public record WorkspaceChanges(
        List<FileChange> files,
        DiffResult diff,
        List<ProjectPath> diffUnavailablePaths,
        List<ProjectPath> binaryPaths,
        boolean truncated) {
    public WorkspaceChanges {
        files = List.copyOf(files);
        diffUnavailablePaths = List.copyOf(diffUnavailablePaths);
        binaryPaths = List.copyOf(binaryPaths);
    }
}

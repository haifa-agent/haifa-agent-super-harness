package io.haifa.agent.superharness.workspace;

import io.haifa.agent.project.changeset.FileChange;
import io.haifa.agent.project.diff.DiffResult;
import io.haifa.agent.project.path.ProjectPath;

import java.util.List;
import java.util.Map;

/** Core change evidence. HTTP projection to DeerFlow's response shape remains a server concern. */
public record WorkspaceChanges(
        List<FileChange> files,
        DiffResult diff,
        List<ProjectPath> diffUnavailablePaths,
        List<ProjectPath> binaryPaths,
        List<ProjectPath> sensitivePaths,
        Map<ProjectPath, WorkspaceLineCounts> lineCounts,
        boolean truncated) {
    public WorkspaceChanges {
        files = List.copyOf(files);
        diffUnavailablePaths = List.copyOf(diffUnavailablePaths);
        binaryPaths = List.copyOf(binaryPaths);
        sensitivePaths = List.copyOf(sensitivePaths);
        lineCounts = Map.copyOf(lineCounts);
    }
}

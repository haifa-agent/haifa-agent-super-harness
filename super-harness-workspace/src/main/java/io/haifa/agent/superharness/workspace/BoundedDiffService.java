package io.haifa.agent.superharness.workspace;

import io.haifa.agent.project.diff.DiffFile;
import io.haifa.agent.project.diff.DiffRequest;
import io.haifa.agent.project.diff.DiffResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Deterministic bounded implementation over Haifa's published diff value contract. */
public final class BoundedDiffService {
    public DiffResult diff(DiffRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.files().size() > request.maxFiles()) {
            throw new WorkspaceException(WorkspaceError.FILE_TOO_LARGE, "diff file count exceeds configured limit");
        }
        StringBuilder output = new StringBuilder();
        int outputBytes = 0;
        int changed = 0;
        for (DiffFile file : request.files()) {
            int beforeBytes = file.before().getBytes(StandardCharsets.UTF_8).length;
            int afterBytes = file.after().getBytes(StandardCharsets.UTF_8).length;
            if (beforeBytes > request.maxFileBytes() || afterBytes > request.maxFileBytes()) {
                throw new WorkspaceException(WorkspaceError.FILE_TOO_LARGE, "diff input exceeds per-file limit");
            }
            if (file.before().equals(file.after())) {
                continue;
            }
            changed++;
            FileDiff fragmentResult = diffFile(file, request.maxFileBytes());
            String fragment = fragmentResult.rendered();
            int fragmentBytes = fragment.getBytes(StandardCharsets.UTF_8).length;
            if ((long) outputBytes + fragmentBytes > request.maxOutputBytes()) {
                throw new WorkspaceException(WorkspaceError.FILE_TOO_LARGE, "diff output exceeds configured limit");
            }
            output.append(fragment);
            outputBytes += fragmentBytes;
        }
        String unifiedDiff = output.toString();
        return new DiffResult(unifiedDiff, changed, outputBytes,
                Hashing.sha256(unifiedDiff.getBytes(StandardCharsets.UTF_8)));
    }

    FileDiff diffFile(DiffFile file, int maxFileBytes) {
        int beforeBytes = file.before().getBytes(StandardCharsets.UTF_8).length;
        int afterBytes = file.after().getBytes(StandardCharsets.UTF_8).length;
        if (beforeBytes > maxFileBytes || afterBytes > maxFileBytes) {
            throw new WorkspaceException(WorkspaceError.FILE_TOO_LARGE, "diff input exceeds per-file limit");
        }
        String rendered = renderFile(file);
        int additions = 0;
        int deletions = 0;
        List<String> lines = rendered.lines().toList();
        for (int index = 2; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.startsWith("+")) additions++;
            else if (line.startsWith("-")) deletions++;
        }
        return new FileDiff(rendered, additions, deletions,
                rendered.getBytes(StandardCharsets.UTF_8).length);
    }

    record FileDiff(String rendered, int additions, int deletions, int outputBytes) {}

    private static String renderFile(DiffFile file) {
        StringBuilder output = new StringBuilder();
        String path = file.path().value();
        List<String> before = file.before().lines().toList();
        List<String> after = file.after().lines().toList();
        output.append("--- a/").append(path).append('\n');
        output.append("+++ b/").append(path).append('\n');
        output.append("@@ -").append(before.isEmpty() ? 0 : 1).append(',').append(before.size())
                .append(" +").append(after.isEmpty() ? 0 : 1).append(',').append(after.size()).append(" @@\n");
        for (String line : before) {
            output.append('-').append(line).append('\n');
        }
        for (String line : after) {
            output.append('+').append(line).append('\n');
        }
        return output.toString();
    }
}

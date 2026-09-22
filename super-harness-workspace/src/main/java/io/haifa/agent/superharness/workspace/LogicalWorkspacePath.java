package io.haifa.agent.superharness.workspace;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.Locale;

final class LogicalWorkspacePath {
    private static final String MOUNT_PREFIX = "/mnt/user-data/";
    private static final Set<String> ROOTS = Set.of("uploads", "workspace", "outputs");

    private LogicalWorkspacePath() {}

    static String normalize(String input) {
        if (input == null || input.isBlank() || input.indexOf('\0') >= 0) {
            throw failure(WorkspaceError.INVALID_PATH, "logical path must not be blank");
        }
        if (!input.equals(input.trim())) {
            throw failure(WorkspaceError.INVALID_PATH, "logical path must not have surrounding whitespace");
        }
        String value = input;
        if (value.startsWith(MOUNT_PREFIX)) {
            value = value.substring(MOUNT_PREFIX.length());
        } else if (value.equals("/mnt/user-data")) {
            throw failure(WorkspaceError.INVALID_PATH, "a file path is required");
        } else if (value.startsWith("/") || value.startsWith("\\")
                || value.matches("^[A-Za-z]:.*")) {
            throw failure(WorkspaceError.PATH_ESCAPE, "absolute paths are not allowed: " + input);
        }
        if (value.indexOf('\\') >= 0) {
            throw failure(WorkspaceError.INVALID_PATH, "backslashes are not valid logical separators");
        }
        List<String> segments = List.of(value.split("/", -1));
        if (segments.size() < 2 || !ROOTS.contains(segments.getFirst())) {
            throw failure(WorkspaceError.INVALID_PATH,
                    "logical path must be below uploads/, workspace/, or outputs/");
        }
        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw failure(WorkspaceError.PATH_ESCAPE, "path traversal or empty segments are not allowed");
            }
            if (segment.chars().anyMatch(character -> character < 32)
                    || segment.matches(".*[<>:\"|?*].*")
                    || segment.endsWith(".") || segment.endsWith(" ")) {
                throw failure(WorkspaceError.INVALID_PATH, "path contains a non-portable segment");
            }
            String baseName = segment.contains(".") ? segment.substring(0, segment.indexOf('.')) : segment;
            String upper = baseName.toUpperCase(Locale.ROOT);
            if (upper.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
                throw failure(WorkspaceError.INVALID_PATH, "path uses a reserved device name");
            }
        }
        return String.join("/", segments);
    }

    static Path resolve(Path userDataRoot, String input) {
        String normalized = normalize(input);
        Path candidate = userDataRoot.resolve(normalized.replace('/', java.io.File.separatorChar)).normalize();
        Path normalizedRoot = userDataRoot.toAbsolutePath().normalize();
        Path absolute = candidate.toAbsolutePath().normalize();
        if (!absolute.startsWith(normalizedRoot)) {
            throw failure(WorkspaceError.PATH_ESCAPE, "path escapes workspace root");
        }
        return absolute;
    }

    static String upstream(String normalized) {
        return MOUNT_PREFIX + normalize(normalized);
    }

    private static WorkspaceException failure(WorkspaceError error, String message) {
        return new WorkspaceException(error, message);
    }
}

package io.haifa.agent.superharness.server;

import io.haifa.agent.project.filesystem.FileEntry;
import io.haifa.agent.project.filesystem.FileListPage;
import io.haifa.agent.project.filesystem.FileListRequest;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.filesystem.WorkspaceFileService;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceError;
import io.haifa.agent.superharness.workspace.WorkspaceException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Shared bounded path/listing helpers for the frozen DeerFlow file tools.
 *
 * <p>All paths cross the same {@link RestrictedWorkspaceService} normalization and the same
 * owner-bound {@link WorkspaceFileService} read surface as {@code read_file}/{@code write_file},
 * so the read tools cannot reach outside {@code /mnt/user-data} or follow links.
 */
final class FileToolSupport {
    static final String VIRTUAL_PREFIX = "/mnt/user-data/";
    /** Bounded traversal for ls/glob/grep; a pathological tree cannot make a Run unbounded. */
    static final int MAX_SCAN_ENTRIES = 5_000;
    static final int MAX_SCAN_FILES = 2_000;
    static final int LS_OUTPUT_MAX_CHARS = 20_000;
    static final int LIST_PAGE_SIZE = 500;

    private FileToolSupport() {}

    static String normalize(String requestedPath) {
        return RestrictedWorkspaceService.normalizeLogicalPath(requestedPath);
    }

    /**
     * Directory-capable normalization. {@link RestrictedWorkspaceService#normalizeLogicalPath}
     * requires a file below a root, so a bare directory root ({@code workspace},
     * {@code /mnt/user-data/workspace}, ...) is accepted here for ls/glob/grep.
     */
    static String normalizeDirectory(String requestedPath) {
        String value = requestedPath == null ? null : requestedPath.strip();
        if (value != null) {
            while (value.length() > 1 && value.endsWith("/")) {
                value = value.substring(0, value.length() - 1);
            }
            String relative = value.startsWith(VIRTUAL_PREFIX) ? value.substring(VIRTUAL_PREFIX.length()) : value;
            if (relative.equals("uploads") || relative.equals("workspace") || relative.equals("outputs")) {
                return relative;
            }
        }
        return normalize(requestedPath);
    }

    static String virtualPath(String normalizedLogical) {
        return VIRTUAL_PREFIX + normalizedLogical;
    }

    static WorkspacePath workspacePath(WorkspaceId workspaceId, String normalizedLogical) {
        return new WorkspacePath(workspaceId, ProjectPath.of(normalizedLogical));
    }

    static String leafName(WorkspacePath path) {
        String value = path.projectPath().value();
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    /** Read every entry of one directory (bounded), without following links. */
    static List<FileEntry> listDirectory(
            WorkspaceFileService files, WorkspacePath directory, int maxEntries) {
        List<FileEntry> entries = new ArrayList<>();
        int offset = 0;
        while (entries.size() < maxEntries) {
            FileListPage page = files.list(new FileListRequest(directory, offset, LIST_PAGE_SIZE));
            for (FileEntry entry : page.entries()) {
                if (entries.size() >= maxEntries) {
                    return List.copyOf(entries);
                }
                entries.add(entry);
            }
            if (!page.truncated()) {
                break;
            }
            if (page.nextOffset() <= offset) {
                break;
            }
            offset = page.nextOffset();
        }
        return List.copyOf(entries);
    }

    static boolean isDirectory(FileEntry entry) {
        return entry.metadata().type() == FileType.DIRECTORY;
    }

    static boolean isLink(FileEntry entry) {
        return entry.metadata().link();
    }

    /**
     * Head-truncate ls output exactly like frozen {@code _truncate_ls_output}
     * ({@code sandbox/tools.py:1971-1991}); {@code maxChars == 0} disables truncation.
     */
    static String truncateLsOutput(String output, int maxChars) {
        if (maxChars == 0 || output.length() <= maxChars) {
            return output;
        }
        int total = output.length();
        int markerMaxLen = ("\n... [truncated: showing first " + total + " of " + total
                + " chars. Use a more specific path to see fewer results] ...").length();
        int kept = Math.max(0, maxChars - markerMaxLen);
        if (kept == 0) {
            return output.substring(0, maxChars);
        }
        String marker = "\n... [truncated: showing first " + kept + " of " + total
                + " chars. Use a more specific path to see fewer results] ...";
        return output.substring(0, kept) + marker;
    }

    /**
     * Translate a glob pattern to a regex that follows recursive Python glob semantics
     * (frozen {@code sandbox.glob} is Python-backed): a leading or embedded double-star segment
     * matches zero or more path segments, so a recursive Python-style pattern also matches a
     * root-level file. Java {@code PathMatcher} does not, which is why SH does not use it directly.
     */
    static Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        int index = 0;
        int length = glob.length();
        while (index < length) {
            char character = glob.charAt(index);
            if (character == '*') {
                boolean doubleStar = index + 1 < length && glob.charAt(index + 1) == '*';
                if (doubleStar) {
                    int after = index + 2;
                    boolean slash = after < length && glob.charAt(after) == '/';
                    if (slash) {
                        regex.append("(?:.*/)?");
                        index = after + 1;
                    } else {
                        regex.append(".*");
                        index = after;
                    }
                } else {
                    regex.append("[^/]*");
                    index++;
                }
            } else if (character == '?') {
                regex.append("[^/]");
                index++;
            } else if (character == '[') {
                int close = glob.indexOf(']', index + 1);
                if (close < 0) {
                    regex.append("\\[");
                    index++;
                } else {
                    String content = glob.substring(index + 1, close);
                    if (content.startsWith("!")) {
                        content = "^" + content.substring(1);
                    }
                    // A slash never participates in one glob segment.
                    regex.append('[').append(content).append(']');
                    index = close + 1;
                }
            } else {
                regex.append(Pattern.quote(String.valueOf(character)));
                index++;
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString());
    }

    /** True when a workspace failure means the path does not exist. */
    static boolean missing(WorkspaceException failure) {
        return failure.error() == WorkspaceError.FILE_NOT_FOUND
                || failure.error() == WorkspaceError.WORKSPACE_NOT_FOUND;
    }
}

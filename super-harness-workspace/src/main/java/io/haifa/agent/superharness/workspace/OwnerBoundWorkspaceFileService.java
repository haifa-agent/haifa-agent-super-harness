package io.haifa.agent.superharness.workspace;

import io.haifa.agent.project.filesystem.FileContent;
import io.haifa.agent.project.filesystem.FileEntry;
import io.haifa.agent.project.filesystem.FileListPage;
import io.haifa.agent.project.filesystem.FileListRequest;
import io.haifa.agent.project.filesystem.FileMetadata;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.filesystem.ReadOptions;
import io.haifa.agent.project.filesystem.SearchRequest;
import io.haifa.agent.project.filesystem.SearchResult;
import io.haifa.agent.project.filesystem.WorkspaceFileService;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.WorkspaceId;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Owner-scoped adapter implementing Haifa's published read-side workspace contract. */
final class OwnerBoundWorkspaceFileService implements WorkspaceFileService {
    private final WorkspaceId workspaceId;
    private final Path userDataRoot;
    private final WorkspaceLimits limits;

    OwnerBoundWorkspaceFileService(WorkspaceId workspaceId, Path userDataRoot, WorkspaceLimits limits) {
        this.workspaceId = workspaceId;
        this.userDataRoot = userDataRoot;
        this.limits = limits;
    }

    @Override
    public FileListPage list(FileListRequest request) {
        verifyWorkspace(request.directory());
        if (request.offset() < 0 || request.limit() < 1) {
            throw new IllegalArgumentException("invalid list range");
        }
        Path directory = resolveNode(request.directory().projectPath(), true);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException(WorkspaceError.FILE_NOT_FOUND, "workspace directory does not exist");
        }
        RestrictedWorkspaceService.ensureNoLinks(userDataRoot, directory);
        try (Stream<Path> paths = Files.list(directory)) {
            List<FileEntry> entries = paths
                    .filter(path -> !path.getFileName().toString().endsWith(".part"))
                    .map(this::metadata)
                    .map(FileEntry::new)
                    .sorted()
                    .toList();
            int from = Math.min(request.offset(), entries.size());
            int to = Math.min(from + request.limit(), entries.size());
            return new FileListPage(entries.subList(from, to), to, to < entries.size());
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to list workspace directory", exception);
        }
    }

    @Override
    public FileMetadata stat(WorkspacePath workspacePath, boolean includeHash) {
        verifyWorkspace(workspacePath);
        Path path = resolveNode(workspacePath.projectPath(), true);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException(WorkspaceError.FILE_NOT_FOUND, "workspace path does not exist");
        }
        FileMetadata metadata = metadata(path);
        if (!includeHash || metadata.type() != FileType.FILE) {
            return metadata;
        }
        byte[] bytes = RestrictedWorkspaceService.readAllVerified(userDataRoot, path,
                limits.maxWorkingFileBytes());
        return new FileMetadata(metadata.path(), metadata.type(), metadata.size(), metadata.lastModifiedAt(),
                Optional.of(Hashing.sha256(bytes)), metadata.link());
    }

    @Override
    public FileContent read(WorkspacePath workspacePath, ReadOptions options) {
        verifyWorkspace(workspacePath);
        if (options.offset() < 0 || options.maxBytes() < 1 || options.maxBytes() > limits.maxReadBytes()
                || options.maxCharacters() < 1) {
            throw new WorkspaceException(WorkspaceError.READ_OUT_OF_BOUNDS, "invalid read range");
        }
        Path path = resolveNode(workspacePath.projectPath(), false);
        byte[] all = RestrictedWorkspaceService.readAllVerified(userDataRoot, path,
                limits.maxWorkingFileBytes());
        if (options.offset() > all.length) {
            throw new WorkspaceException(WorkspaceError.READ_OUT_OF_BOUNDS, "read offset exceeds file size");
        }
        int count = (int) Math.min(options.maxBytes(), all.length - options.offset());
        byte[] page = java.util.Arrays.copyOfRange(all, (int) options.offset(), (int) options.offset() + count);
        String text = decode(page, options.charset());
        boolean characterTruncated = text.length() > options.maxCharacters();
        if (characterTruncated) {
            if (!options.allowTruncation()) {
                throw new WorkspaceException(WorkspaceError.FILE_TOO_LARGE, "decoded content exceeds character limit");
            }
            text = text.substring(0, options.maxCharacters());
        }
        boolean truncated = options.offset() + count < all.length || characterTruncated;
        String hash = Hashing.sha256(all);
        return new FileContent(workspacePath, text, options.charset(), options.offset(), count, all.length,
                hash, hash, truncated);
    }

    @Override
    public List<SearchResult> search(SearchRequest request) {
        verifyWorkspace(request.root());
        if (request.maxScannedFiles() < 1 || request.maxResults() < 1 || request.maxFileBytes() < 1) {
            throw new IllegalArgumentException("invalid search budget");
        }
        Path root = resolveNode(request.root().projectPath(), true);
        String needle = request.caseSensitive() ? request.query() : request.query().toLowerCase(java.util.Locale.ROOT);
        List<SearchResult> results = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.naturalOrder()).toList()) {
                RestrictedWorkspaceService.ensureNoLinks(userDataRoot, path);
                if (scanned++ >= request.maxScannedFiles() || results.size() >= request.maxResults()) {
                    break;
                }
                if (Files.size(path) > request.maxFileBytes()) {
                    continue;
                }
                List<String> lines = Files.readAllLines(path);
                for (int lineIndex = 0; lineIndex < lines.size() && results.size() < request.maxResults(); lineIndex++) {
                    String line = lines.get(lineIndex);
                    String haystack = request.caseSensitive() ? line : line.toLowerCase(java.util.Locale.ROOT);
                    int column = haystack.indexOf(needle);
                    if (column >= 0) {
                        results.add(new SearchResult(toWorkspacePath(path), lineIndex + 1, column + 1, line));
                    }
                }
            }
            return List.copyOf(results);
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to search workspace", exception);
        }
    }

    private FileMetadata metadata(Path path) {
        RestrictedWorkspaceService.ensureNoLinks(userDataRoot, path);
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            FileType type = attributes.isRegularFile() ? FileType.FILE
                    : attributes.isDirectory() ? FileType.DIRECTORY
                    : attributes.isSymbolicLink() ? FileType.LINK : FileType.OTHER;
            return new FileMetadata(toWorkspacePath(path), type, attributes.size(),
                    Optional.of(Instant.ofEpochMilli(attributes.lastModifiedTime().toMillis())), Optional.empty(),
                    attributes.isSymbolicLink());
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to stat workspace path", exception);
        }
    }

    private Path resolveNode(ProjectPath projectPath, boolean directoryAllowed) {
        String value = projectPath.value();
        if (value.isBlank() && directoryAllowed) {
            return userDataRoot;
        }
        if (directoryAllowed && (value.equals("uploads") || value.equals("workspace") || value.equals("outputs"))) {
            return userDataRoot.resolve(value);
        }
        return LogicalWorkspacePath.resolve(userDataRoot, value);
    }

    private WorkspacePath toWorkspacePath(Path path) {
        String relative = userDataRoot.relativize(path.toAbsolutePath().normalize()).toString()
                .replace(java.io.File.separatorChar, '/');
        return new WorkspacePath(workspaceId, relative.isEmpty() ? ProjectPath.root() : ProjectPath.of(relative));
    }

    private void verifyWorkspace(WorkspacePath path) {
        if (!workspaceId.equals(path.workspaceId())) {
            throw new WorkspaceException(WorkspaceError.OWNER_MISMATCH,
                    "owner-bound file service cannot access another workspace");
        }
    }

    private static String decode(byte[] bytes, Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new WorkspaceException(WorkspaceError.INVALID_PATH, "file is not valid " + charset, exception);
        }
    }
}

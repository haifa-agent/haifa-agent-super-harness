package io.haifa.agent.superharness.workspace;

import io.haifa.agent.project.changeset.FileChange;
import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.changeset.FileVersion;
import io.haifa.agent.project.diff.DiffFile;
import io.haifa.agent.project.diff.DiffRequest;
import io.haifa.agent.project.diff.DiffResult;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.workspace.WorkspaceId;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Persistent Project shelf and run-change core. It intentionally exposes no HTTP types and relies only on
 * published Haifa project-api values for changes and diffs.
 */
public final class PersistentProjectWorkspaceService {
    private static final String BINDING_FILE = "binding.properties";
    private static final String BASELINE_FILE = "baseline.properties";
    public static final int PROJECT_NAME_MAX_CODE_POINTS = 128;
    public static final int PROJECT_INSTRUCTIONS_MAX_BYTES = 8192;

    private final Path projectsRoot;
    private final Path changesRoot;
    private final WorkspaceRegistry registry;
    private final WorkspaceLimits workspaceLimits;
    private final WorkspaceChangeLimits changeLimits;
    private final BoundedDiffService diffs = new BoundedDiffService();

    public PersistentProjectWorkspaceService(
            Path storageRoot, WorkspaceLimits workspaceLimits, WorkspaceChangeLimits changeLimits) {
        Path root = Objects.requireNonNull(storageRoot, "storageRoot").toAbsolutePath().normalize();
        this.workspaceLimits = Objects.requireNonNull(workspaceLimits, "workspaceLimits");
        this.changeLimits = Objects.requireNonNull(changeLimits, "changeLimits");
        this.registry = new WorkspaceRegistry(root);
        this.projectsRoot = root.resolve("projects");
        this.changesRoot = root.resolve("workspace-changes");
        WorkspaceRegistry.createDirectories(projectsRoot);
        WorkspaceRegistry.createDirectories(changesRoot);
    }

    /** Persist an owner binding for a trusted project id. */
    public synchronized void bindProject(String ownerId, String projectId) {
        String owner = requireIdentity(ownerId, "ownerId");
        String project = requireIdentity(projectId, "projectId");
        Path root = projectRoot(project);
        Path binding = root.resolve(BINDING_FILE);
        if (Files.exists(binding)) {
            requireProject(owner, project);
            return;
        }
        WorkspaceRegistry.createDirectories(root.resolve("documents"));
        WorkspaceRegistry.createDirectories(root.resolve("payloads"));
        Properties properties = new Properties();
        properties.setProperty("ownerId", owner);
        properties.setProperty("projectId", project);
        String defaultName = project.codePoints()
                .limit(PROJECT_NAME_MAX_CODE_POINTS)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        Instant now = Instant.now();
        properties.setProperty("name", defaultName);
        properties.setProperty("instructions", "");
        properties.setProperty("status", "active");
        properties.setProperty("createdAt", now.toString());
        properties.setProperty("updatedAt", now.toString());
        storeNew(properties, binding, "Super Harness project binding");
    }

    /** Create one active Project under the server-selected owner. */
    public synchronized ProjectProfile createProject(
            String ownerId, String name, String instructions, Map<String, String> presentation) {
        String owner = requireIdentity(ownerId, "ownerId");
        String projectId = UUID.randomUUID().toString().replace("-", "");
        String validName = validateProjectName(name);
        String validInstructions = validateProjectInstructions(instructions);
        Map<String, String> validPresentation = validatePresentation(presentation);
        Path root = projectRoot(projectId);
        WorkspaceRegistry.createDirectories(root.resolve("documents"));
        WorkspaceRegistry.createDirectories(root.resolve("payloads"));
        Instant now = Instant.now();
        Properties properties = projectProperties(owner, projectId, validName, validInstructions,
                validPresentation, "active", now, now);
        storeNew(properties, root.resolve(BINDING_FILE), "Super Harness project binding");
        return new ProjectProfile(projectId, validName, validInstructions, validPresentation, "active", now, now);
    }

    public synchronized boolean projectExists(String ownerId, String projectId) {
        try {
            getProject(ownerId, projectId);
            return true;
        } catch (WorkspaceException failure) {
            if (failure.error() == WorkspaceError.PROJECT_NOT_FOUND
                    || failure.error() == WorkspaceError.OWNER_MISMATCH
                    || failure.error() == WorkspaceError.INVALID_IDENTITY) {
                return false;
            }
            throw failure;
        }
    }

    public synchronized ProjectProfile getProject(String ownerId, String projectId) {
        Path root = requireProject(ownerId, projectId);
        return loadProject(root.resolve(BINDING_FILE), projectId);
    }

    public synchronized List<ProjectProfile> listProjects(String ownerId, Optional<String> status) {
        String owner = requireIdentity(ownerId, "ownerId");
        String expectedStatus = status.orElse(null);
        if (expectedStatus != null && !expectedStatus.equals("active") && !expectedStatus.equals("archived")) {
            throw new IllegalArgumentException("project status must be active or archived");
        }
        try (Stream<Path> paths = Files.list(projectsRoot)) {
            return paths.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.resolve(BINDING_FILE))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> loadProperties(path, WorkspaceError.PROJECT_NOT_FOUND, "project does not exist"))
                    .filter(properties -> owner.equals(properties.getProperty("ownerId")))
                    .map(properties -> loadProject(properties, properties.getProperty("projectId")))
                    .filter(project -> expectedStatus == null || expectedStatus.equals(project.status()))
                    .sorted(Comparator.comparing(ProjectProfile::createdAt).thenComparing(ProjectProfile::id))
                    .toList();
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to list projects", exception);
        }
    }

    /** Patch only explicitly supplied fields. Empty optionals preserve the stored value. */
    public synchronized ProjectProfile patchProject(
            String ownerId,
            String projectId,
            Optional<String> name,
            Optional<String> instructions,
            Optional<Map<String, String>> presentation) {
        Path root = requireProject(ownerId, projectId);
        Path binding = root.resolve(BINDING_FILE);
        Properties current = loadProperties(binding, WorkspaceError.PROJECT_NOT_FOUND, "project does not exist");
        ProjectProfile existing = loadProject(current, projectId);
        String nextName = name.map(PersistentProjectWorkspaceService::validateProjectName).orElse(existing.name());
        String nextInstructions = instructions.map(PersistentProjectWorkspaceService::validateProjectInstructions)
                .orElse(existing.instructions());
        Map<String, String> nextPresentation = presentation
                .map(PersistentProjectWorkspaceService::validatePresentation)
                .orElse(existing.presentation());
        Instant now = Instant.now();
        Properties updated = projectProperties(current.getProperty("ownerId"), projectId, nextName,
                nextInstructions, nextPresentation, existing.status(), existing.createdAt(), now);
        storeReplacing(updated, binding, "Super Harness project binding");
        return new ProjectProfile(projectId, nextName, nextInstructions, nextPresentation,
                existing.status(), existing.createdAt(), now);
    }

    /** Store one immutable upload snapshot. Equal content deduplicates and the first metadata wins. */
    public synchronized ProjectDocumentReceipt uploadProjectDocument(
            String ownerId, String projectId, String name, byte[] content) {
        return storeDocument(ownerId, projectId, name, content, Optional.empty(), ProjectDocumentSourceKind.UPLOAD,
                Optional.empty());
    }

    /** Copy one plain-named thread upload/output into a project-owned immutable shelf snapshot. */
    public synchronized ProjectDocumentReceipt promoteThreadFile(
            String ownerId,
            String projectId,
            String threadId,
            WorkspaceId workspaceId,
            ProjectDocumentSourceKind sourceKind,
            String sourceName,
            Optional<String> shelfName) {
        requireProject(ownerId, projectId);
        WorkspaceBinding binding = registry.requireOwner(ownerId, workspaceId);
        String thread = requireIdentity(threadId, "threadId");
        if (!binding.threadId().equals(thread)) {
            throw new WorkspaceException(WorkspaceError.OWNER_MISMATCH,
                    "workspace belongs to a different thread");
        }
        ProjectDocumentSourceKind kind = Objects.requireNonNull(sourceKind, "sourceKind");
        String filename = validateFilename(sourceName);
        String rootName = kind == ProjectDocumentSourceKind.UPLOAD ? "uploads/" : "outputs/";
        Path userData = registry.userDataRoot(workspaceId);
        byte[] bytes = RestrictedWorkspaceService.readAllVerified(
                userData, LogicalWorkspacePath.resolve(userData, rootName + filename), workspaceLimits.maxUploadBytes());
        String displayName = validateFilename(shelfName.orElse(filename));
        return storeDocument(ownerId, projectId, displayName, bytes, Optional.of(thread), kind,
                Optional.of(filename));
    }

    public synchronized List<ProjectDocument> listProjectDocuments(String ownerId, String projectId) {
        Path project = requireProject(ownerId, projectId);
        Path documents = project.resolve("documents");
        try (Stream<Path> paths = Files.list(documents)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .map(path -> loadDocument(path, projectId))
                    .sorted(Comparator.comparing(ProjectDocument::createdAt).thenComparing(ProjectDocument::id))
                    .toList();
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to list project documents", exception);
        }
    }

    public ProjectDocumentContent readProjectDocument(
            String ownerId, String projectId, String documentId, long offset, int maxBytes) {
        if (offset < 0 || maxBytes < 1 || maxBytes > workspaceLimits.maxReadBytes()) {
            throw new WorkspaceException(WorkspaceError.READ_OUT_OF_BOUNDS, "invalid project document read range");
        }
        Path project = requireProject(ownerId, projectId);
        ProjectDocument document = requireDocument(project, documentId, projectId);
        Path payload = project.resolve("payloads").resolve(document.sha256());
        RestrictedWorkspaceService.ensureReadableFile(project, payload);
        try {
            long size = Files.size(payload);
            if (size != document.sizeBytes()) {
                throw new WorkspaceException(WorkspaceError.CONTENT_INTEGRITY,
                        "project document size does not match persisted metadata");
            }
            if (offset > size) {
                throw new WorkspaceException(WorkspaceError.READ_OUT_OF_BOUNDS,
                        "project document offset exceeds file size");
            }
            int count = (int) Math.min(maxBytes, size - offset);
            ByteBuffer buffer = ByteBuffer.allocate(count);
            try (FileChannel channel = FileChannel.open(payload, StandardOpenOption.READ)) {
                channel.position(offset);
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                    // Bounded short reads are legal.
                }
            }
            byte[] all = RestrictedWorkspaceService.readAllVerified(project, payload, workspaceLimits.maxUploadBytes());
            if (!Hashing.sha256(all).equals(document.sha256())) {
                throw new WorkspaceException(WorkspaceError.CONTENT_INTEGRITY,
                        "project document hash does not match persisted metadata");
            }
            return new ProjectDocumentContent(document.id(), buffer.array(), document.sha256(), size, offset,
                    offset + count < size);
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to read project document", exception);
        }
    }

    /** Capture a bounded immutable baseline for a run. */
    public synchronized void beginRunChanges(
            String ownerId, String threadId, WorkspaceId workspaceId, String runId) {
        WorkspaceBinding binding = requireThreadWorkspace(ownerId, threadId, workspaceId);
        String run = requireIdentity(runId, "runId");
        Path target = runRoot(binding.workspaceId(), run);
        if (Files.exists(target)) {
            throw new WorkspaceException(WorkspaceError.RUN_ALREADY_TRACKED,
                    "a baseline already exists for this workspace and run");
        }
        Map<String, FileState> baseline = scan(registry.userDataRoot(workspaceId));
        Path temporary = changesRoot.resolve(".baseline-" + UUID.randomUUID());
        WorkspaceRegistry.createDirectories(temporary.resolve("payloads"));
        Properties properties = new Properties();
        properties.setProperty("ownerId", binding.ownerId());
        properties.setProperty("threadId", binding.threadId());
        properties.setProperty("workspaceId", binding.workspaceId().value());
        properties.setProperty("runId", run);
        for (FileState state : baseline.values()) {
            String key = "file." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(state.path().getBytes(StandardCharsets.UTF_8));
            properties.setProperty(key, state.size() + ":" + state.sha256());
            Path payload = temporary.resolve("payloads").resolve(state.sha256());
            if (!Files.exists(payload)) {
                writeNew(payload, state.bytes());
            }
        }
        storeNew(properties, temporary.resolve(BASELINE_FILE), "Super Harness workspace change baseline");
        try {
            Files.createDirectories(target.getParent());
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target);
            }
        } catch (FileAlreadyExistsException exception) {
            throw new WorkspaceException(WorkspaceError.RUN_ALREADY_TRACKED,
                    "a baseline already exists for this workspace and run", exception);
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to publish run baseline", exception);
        }
    }

    /** Compare current files with the persisted baseline; neither baseline nor current files are modified. */
    public WorkspaceChanges readRunChanges(
            String ownerId, String threadId, WorkspaceId workspaceId, String runId) {
        WorkspaceBinding binding = requireThreadWorkspace(ownerId, threadId, workspaceId);
        String run = requireIdentity(runId, "runId");
        Path root = runRoot(workspaceId, run);
        Properties properties = loadProperties(root.resolve(BASELINE_FILE), WorkspaceError.RUN_BASELINE_NOT_FOUND,
                "run baseline does not exist");
        if (!binding.ownerId().equals(properties.getProperty("ownerId"))
                || !binding.threadId().equals(properties.getProperty("threadId"))
                || !binding.workspaceId().value().equals(properties.getProperty("workspaceId"))
                || !run.equals(properties.getProperty("runId"))) {
            throw new WorkspaceException(WorkspaceError.OWNER_MISMATCH,
                    "run baseline does not belong to this owner, thread, and workspace");
        }
        Map<String, FileState> before = loadBaseline(root, properties);
        Map<String, FileState> after = scan(registry.userDataRoot(workspaceId));
        List<String> paths = Stream.concat(before.keySet().stream(), after.keySet().stream()).distinct().sorted().toList();
        List<FileChange> changes = new ArrayList<>();
        List<DiffFile> diffFiles = new ArrayList<>();
        List<ProjectPath> unavailable = new ArrayList<>();
        for (String pathValue : paths) {
            FileState oldState = before.get(pathValue);
            FileState newState = after.get(pathValue);
            if (oldState != null && newState != null && oldState.sha256().equals(newState.sha256())) {
                continue;
            }
            ProjectPath path = ProjectPath.of(pathValue);
            FileVersion oldVersion = oldState == null ? null : oldState.version();
            FileVersion newVersion = newState == null ? null : newState.version();
            FileChangeType type = oldState == null ? FileChangeType.CREATE
                    : newState == null ? FileChangeType.DELETE : FileChangeType.REPLACE;
            changes.add(new FileChange(type, path, null, oldVersion, newVersion));
            Optional<String> oldText = oldState == null ? Optional.of("") : decodeUtf8(oldState.bytes());
            Optional<String> newText = newState == null ? Optional.of("") : decodeUtf8(newState.bytes());
            if (oldText.isPresent() && newText.isPresent()) {
                diffFiles.add(new DiffFile(path, oldText.orElseThrow(), newText.orElseThrow()));
            } else {
                unavailable.add(path);
            }
        }
        DiffResult diff = diffs.diff(new DiffRequest(diffFiles, changeLimits.maxFiles(),
                changeLimits.maxFileBytes(), changeLimits.maxOutputBytes()));
        return new WorkspaceChanges(changes, diff, unavailable, false);
    }

    public DiffResult diff(DiffRequest request) {
        return diffs.diff(request);
    }

    private ProjectDocumentReceipt storeDocument(
            String ownerId,
            String projectId,
            String name,
            byte[] content,
            Optional<String> sourceThread,
            ProjectDocumentSourceKind sourceKind,
            Optional<String> sourceName) {
        Path project = requireProject(ownerId, projectId);
        String displayName = validateFilename(name);
        byte[] bytes = Objects.requireNonNull(content, "content").clone();
        if (bytes.length == 0) {
            throw new IllegalArgumentException("project document content must not be empty");
        }
        if (bytes.length > workspaceLimits.maxUploadBytes()) {
            throw new WorkspaceException(WorkspaceError.FILE_TOO_LARGE,
                    "project document exceeds configured size limit");
        }
        String hash = Hashing.sha256(bytes);
        for (ProjectDocument existing : listProjectDocuments(ownerId, projectId)) {
            if (existing.sha256().equals(hash)) {
                return new ProjectDocumentReceipt(existing, true);
            }
        }
        String documentId = "doc-" + Hashing.sha256(projectId + "\0" + hash).substring(0, 32);
        Instant now = Instant.now();
        ProjectDocument document = new ProjectDocument(documentId, projectId, displayName, bytes.length, hash,
                sourceThread, sourceKind, sourceName, now, now);
        Path payload = project.resolve("payloads").resolve(hash);
        if (!Files.exists(payload)) {
            writeNew(payload, bytes);
        }
        Properties properties = new Properties();
        properties.setProperty("id", document.id());
        properties.setProperty("projectId", document.projectId());
        properties.setProperty("name", document.name());
        properties.setProperty("sizeBytes", Long.toString(document.sizeBytes()));
        properties.setProperty("sha256", document.sha256());
        properties.setProperty("sourceKind", document.sourceKind().wireValue());
        document.sourceThreadId().ifPresent(value -> properties.setProperty("sourceThreadId", value));
        document.sourceName().ifPresent(value -> properties.setProperty("sourceName", value));
        properties.setProperty("createdAt", document.createdAt().toString());
        properties.setProperty("updatedAt", document.updatedAt().toString());
        storeNew(properties, project.resolve("documents").resolve(document.id() + ".properties"),
                "Super Harness project document");
        return new ProjectDocumentReceipt(document, false);
    }

    private ProjectDocument requireDocument(Path project, String documentId, String projectId) {
        if (documentId == null || !documentId.matches("doc-[0-9a-f]{32}")) {
            throw new WorkspaceException(WorkspaceError.DOCUMENT_NOT_FOUND, "project document does not exist");
        }
        Path metadata = project.resolve("documents").resolve(documentId + ".properties");
        if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException(WorkspaceError.DOCUMENT_NOT_FOUND, "project document does not exist");
        }
        return loadDocument(metadata, documentId, projectId);
    }

    private ProjectDocument loadDocument(Path metadata, String expectedProjectId) {
        String filename = metadata.getFileName().toString();
        String expectedDocumentId = filename.substring(0, filename.length() - ".properties".length());
        return loadDocument(metadata, expectedDocumentId, expectedProjectId);
    }

    private ProjectDocument loadDocument(Path metadata, String expectedDocumentId, String expectedProjectId) {
        Properties properties = loadProperties(metadata, WorkspaceError.DOCUMENT_NOT_FOUND,
                "project document does not exist");
        try {
            String id = properties.getProperty("id");
            String projectId = properties.getProperty("projectId");
            String name = validateFilename(properties.getProperty("name"));
            long size = Long.parseLong(properties.getProperty("sizeBytes"));
            String sha256 = properties.getProperty("sha256");
            if (!Objects.equals(id, expectedDocumentId) || id == null || !id.matches("doc-[0-9a-f]{32}")) {
                throw new IllegalArgumentException("document id does not match metadata filename");
            }
            if (expectedProjectId != null && !Objects.equals(projectId, expectedProjectId)) {
                throw new IllegalArgumentException("document project does not match bound project");
            }
            requireIdentity(projectId, "projectId");
            if (size < 0 || size > workspaceLimits.maxUploadBytes()) {
                throw new IllegalArgumentException("document size is outside configured bounds");
            }
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("document hash is invalid");
            }
            ProjectDocumentSourceKind kind = switch (properties.getProperty("sourceKind")) {
                case "upload" -> ProjectDocumentSourceKind.UPLOAD;
                case "output" -> ProjectDocumentSourceKind.OUTPUT;
                default -> throw new IllegalArgumentException("document source kind is invalid");
            };
            Optional<String> sourceThreadId = Optional.ofNullable(properties.getProperty("sourceThreadId"));
            sourceThreadId.ifPresent(value -> requireIdentity(value, "sourceThreadId"));
            Optional<String> sourceName = Optional.ofNullable(properties.getProperty("sourceName"));
            sourceName.ifPresent(PersistentProjectWorkspaceService::validateFilename);
            Instant createdAt = Instant.parse(properties.getProperty("createdAt"));
            Instant updatedAt = Instant.parse(properties.getProperty("updatedAt"));
            if (updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("document update timestamp precedes creation");
            }
            return new ProjectDocument(id, projectId, name, size, sha256, sourceThreadId, kind, sourceName,
                    createdAt, updatedAt);
        } catch (RuntimeException exception) {
            throw new WorkspaceException(WorkspaceError.CONTENT_INTEGRITY,
                    "project document metadata is corrupt", exception);
        }
    }

    private WorkspaceBinding requireThreadWorkspace(String ownerId, String threadId, WorkspaceId workspaceId) {
        WorkspaceBinding binding = registry.requireOwner(ownerId, workspaceId);
        if (!binding.threadId().equals(requireIdentity(threadId, "threadId"))) {
            throw new WorkspaceException(WorkspaceError.OWNER_MISMATCH,
                    "workspace belongs to a different thread");
        }
        return binding;
    }

    private Map<String, FileState> scan(Path userDataRoot) {
        Map<String, FileState> states = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(userDataRoot)) {
            for (Path path : paths.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                    .filter(candidate -> !candidate.getFileName().toString().endsWith(".part"))
                    .sorted().toList()) {
                if (states.size() >= changeLimits.maxFiles()) {
                    throw new WorkspaceException(WorkspaceError.FILE_TOO_LARGE,
                            "workspace change scan exceeds file-count limit");
                }
                RestrictedWorkspaceService.ensureNoLinks(userDataRoot, path);
                byte[] bytes = RestrictedWorkspaceService.readAllVerified(userDataRoot, path,
                        changeLimits.maxFileBytes());
                String logical = userDataRoot.relativize(path).toString().replace(java.io.File.separatorChar, '/');
                logical = LogicalWorkspacePath.normalize(logical);
                states.put(logical, new FileState(logical, bytes.length, Hashing.sha256(bytes), bytes));
            }
            return states;
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to scan workspace changes", exception);
        }
    }

    private Map<String, FileState> loadBaseline(Path runRoot, Properties properties) {
        Map<String, FileState> states = new LinkedHashMap<>();
        properties.stringPropertyNames().stream().filter(key -> key.startsWith("file.")).sorted().forEach(key -> {
            String path;
            long size;
            String hash;
            try {
                path = new String(Base64.getUrlDecoder().decode(key.substring(5)), StandardCharsets.UTF_8);
                if (!LogicalWorkspacePath.normalize(path).equals(path)) {
                    throw new IllegalArgumentException("non-canonical path");
                }
                String[] value = properties.getProperty(key).split(":", 2);
                if (value.length != 2 || !value[1].matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("invalid baseline entry");
                }
                size = Long.parseLong(value[0]);
                if (size < 0 || size > changeLimits.maxFileBytes()) {
                    throw new IllegalArgumentException("invalid baseline size");
                }
                hash = value[1];
            } catch (RuntimeException exception) {
                throw new WorkspaceException(WorkspaceError.CONTENT_INTEGRITY,
                        "run baseline metadata is corrupt", exception);
            }
            Path payload = runRoot.resolve("payloads").resolve(hash);
            if (!Files.isRegularFile(payload, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkspaceException(WorkspaceError.CONTENT_INTEGRITY,
                        "run baseline payload is missing");
            }
            byte[] bytes = RestrictedWorkspaceService.readAllVerified(runRoot, payload, changeLimits.maxFileBytes());
            if (bytes.length != size || !Hashing.sha256(bytes).equals(hash)) {
                throw new WorkspaceException(WorkspaceError.CONTENT_INTEGRITY,
                        "run baseline payload does not match persisted metadata");
            }
            states.put(path, new FileState(path, size, hash, bytes));
        });
        return states;
    }

    private Path requireProject(String ownerId, String projectId) {
        String owner = requireIdentity(ownerId, "ownerId");
        String project = requireIdentity(projectId, "projectId");
        Path root = projectRoot(project);
        Properties binding = loadProperties(root.resolve(BINDING_FILE), WorkspaceError.PROJECT_NOT_FOUND,
                "project does not exist");
        if (!project.equals(binding.getProperty("projectId"))) {
            throw new WorkspaceException(WorkspaceError.IO_FAILURE, "project binding id is corrupt");
        }
        if (!owner.equals(binding.getProperty("ownerId"))) {
            throw new WorkspaceException(WorkspaceError.OWNER_MISMATCH, "project belongs to a different owner");
        }
        return root;
    }

    private ProjectProfile loadProject(Path binding, String expectedProjectId) {
        return loadProject(loadProperties(binding, WorkspaceError.PROJECT_NOT_FOUND, "project does not exist"),
                expectedProjectId);
    }

    private ProjectProfile loadProject(Properties properties, String expectedProjectId) {
        try {
            String id = requireIdentity(properties.getProperty("projectId"), "projectId");
            if (!Objects.equals(id, expectedProjectId)) {
                throw new IllegalArgumentException("project id does not match binding");
            }
            String name = validateProjectName(properties.getProperty("name"));
            String instructions = validateProjectInstructions(properties.getProperty("instructions", ""));
            Map<String, String> presentation = new LinkedHashMap<>();
            Optional.ofNullable(properties.getProperty("presentation.icon"))
                    .ifPresent(value -> presentation.put("icon", value));
            Optional.ofNullable(properties.getProperty("presentation.color"))
                    .ifPresent(value -> presentation.put("color", value));
            String status = properties.getProperty("status");
            if (!Objects.equals(status, "active") && !Objects.equals(status, "archived")) {
                throw new IllegalArgumentException("project status is invalid");
            }
            Instant createdAt = Instant.parse(properties.getProperty("createdAt"));
            Instant updatedAt = Instant.parse(properties.getProperty("updatedAt"));
            if (updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("project update timestamp precedes creation");
            }
            return new ProjectProfile(id, name, instructions, presentation, status, createdAt, updatedAt);
        } catch (RuntimeException exception) {
            if (exception instanceof WorkspaceException workspace) {
                throw workspace;
            }
            throw new WorkspaceException(WorkspaceError.CONTENT_INTEGRITY,
                    "project metadata is corrupt", exception);
        }
    }

    private static Properties projectProperties(
            String ownerId,
            String projectId,
            String name,
            String instructions,
            Map<String, String> presentation,
            String status,
            Instant createdAt,
            Instant updatedAt) {
        Properties properties = new Properties();
        properties.setProperty("ownerId", ownerId);
        properties.setProperty("projectId", projectId);
        properties.setProperty("name", name);
        properties.setProperty("instructions", instructions);
        properties.setProperty("status", status);
        properties.setProperty("createdAt", createdAt.toString());
        properties.setProperty("updatedAt", updatedAt.toString());
        presentation.forEach((key, value) -> properties.setProperty("presentation." + key, value));
        return properties;
    }

    private static String validateProjectName(String name) {
        if (name == null || name.isEmpty() || name.codePointCount(0, name.length()) > PROJECT_NAME_MAX_CODE_POINTS) {
            throw new IllegalArgumentException("project name must contain 1-128 characters");
        }
        return name;
    }

    private static String validateProjectInstructions(String instructions) {
        if (instructions == null) {
            throw new IllegalArgumentException("project instructions must not be null");
        }
        if (instructions.getBytes(StandardCharsets.UTF_8).length > PROJECT_INSTRUCTIONS_MAX_BYTES) {
            throw new IllegalArgumentException("project instructions exceed the configured 8192-byte UTF-8 limit");
        }
        return instructions;
    }

    private static Map<String, String> validatePresentation(Map<String, String> presentation) {
        Objects.requireNonNull(presentation, "presentation");
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : presentation.entrySet()) {
            if (!entry.getKey().equals("icon") && !entry.getKey().equals("color")) {
                throw new IllegalArgumentException("project presentation only supports icon and color");
            }
            if (entry.getValue() == null || entry.getValue().getBytes(StandardCharsets.UTF_8).length > 256) {
                throw new IllegalArgumentException("project presentation values must contain at most 256 UTF-8 bytes");
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private Path projectRoot(String projectId) {
        return projectsRoot.resolve("prj-" + Hashing.sha256(projectId).substring(0, 32));
    }

    private Path runRoot(WorkspaceId workspaceId, String runId) {
        return changesRoot.resolve(workspaceId.value())
                .resolve("run-" + Hashing.sha256(runId).substring(0, 32));
    }

    private static Optional<String> decodeUtf8(byte[] bytes) {
        try {
            return Optional.of(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException exception) {
            return Optional.empty();
        }
    }

    private static String requireIdentity(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new WorkspaceException(WorkspaceError.INVALID_IDENTITY,
                    field + " must contain 1-256 characters");
        }
        return value;
    }

    private static String validateFilename(String filename) {
        if (filename == null || filename.isBlank() || filename.getBytes(StandardCharsets.UTF_8).length > 255
                || filename.contains("/") || filename.contains("\\") || !filename.equals(filename.trim())
                || filename.equals(".") || filename.equals("..")) {
            throw new WorkspaceException(WorkspaceError.INVALID_PATH,
                    "project document name must be a plain filename of at most 255 UTF-8 bytes");
        }
        LogicalWorkspacePath.normalize("uploads/" + filename);
        return filename;
    }

    private static Properties loadProperties(Path path, WorkspaceError missingError, String missingMessage) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException(missingError, missingMessage);
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
            return properties;
        } catch (IOException | RuntimeException exception) {
            throw WorkspaceRegistry.ioFailure("failed to load persisted workspace metadata", exception);
        }
    }

    private static void storeNew(Properties properties, Path target, String comment) {
        try {
            Files.createDirectories(target.getParent());
            Path part = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".part");
            try (OutputStream output = Files.newOutputStream(part, StandardOpenOption.CREATE_NEW)) {
                properties.store(output, comment);
            }
            WorkspaceRegistry.moveAtomically(part, target);
        } catch (FileAlreadyExistsException exception) {
            throw new WorkspaceException(WorkspaceError.HASH_CONFLICT,
                    "persisted metadata already exists", exception);
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to persist workspace metadata", exception);
        }
    }

    private static void storeReplacing(Properties properties, Path target, String comment) {
        Path part = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".part");
        try {
            try (OutputStream output = Files.newOutputStream(part, StandardOpenOption.CREATE_NEW)) {
                properties.store(output, comment);
            }
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // Preserve the original persistence failure.
            }
            throw WorkspaceRegistry.ioFailure("failed to update persisted workspace metadata", exception);
        }
    }

    private static void writeNew(Path target, byte[] bytes) {
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException exception) {
            byte[] current = RestrictedWorkspaceService.readAllVerified(target.getParent(), target, bytes.length);
            if (!java.util.Arrays.equals(current, bytes)) {
                throw new WorkspaceException(WorkspaceError.CONTENT_INTEGRITY,
                        "content-addressed payload collision", exception);
            }
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to persist immutable payload", exception);
        }
    }

    private record FileState(String path, long size, String sha256, byte[] bytes) {
        private FileState {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        FileVersion version() {
            return new FileVersion(FileType.FILE, size, sha256);
        }
    }
}

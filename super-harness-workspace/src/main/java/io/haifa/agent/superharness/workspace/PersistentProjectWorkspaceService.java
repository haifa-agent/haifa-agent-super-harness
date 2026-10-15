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
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Persistent Project shelf and run-change core. It intentionally exposes no HTTP types and relies only on
 * published Haifa project-api values for changes and diffs.
 */
public final class PersistentProjectWorkspaceService {
    private static final Logger LOGGER = Logger.getLogger(PersistentProjectWorkspaceService.class.getName());
    private static final String BINDING_FILE = "binding.properties";
    private static final String BASELINE_FILE = "baseline.properties";
    private static final String COMPLETED_DIR = "completed";
    public static final int PROJECT_NAME_MAX_CODE_POINTS = 128;
    public static final int PROJECT_INSTRUCTIONS_MAX_BYTES = 8192;
    private static final int MAX_ABANDONED_STAGES = 64;
    private static final Duration STAGE_MAX_AGE = Duration.ofHours(1);

    private final Path projectsRoot;
    private final Path changesRoot;
    private final Path trashRoot;
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
        this.trashRoot = root.resolve("trash");
        WorkspaceRegistry.createDirectories(projectsRoot);
        WorkspaceRegistry.createDirectories(changesRoot);
        WorkspaceRegistry.createDirectories(trashRoot.resolve("documents"));
        WorkspaceRegistry.createDirectories(trashRoot.resolve("payloads"));
        cleanupAbandonedStages();
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

    public synchronized ProjectProfile archiveProject(String ownerId, String projectId) {
        Path project = requireProject(ownerId, projectId);
        Path binding = project.resolve(BINDING_FILE);
        Properties properties = loadProperties(binding, WorkspaceError.PROJECT_NOT_FOUND, "project does not exist");
        properties.setProperty("status", "archived");
        properties.setProperty("updatedAt", Instant.now().toString());
        storeReplacing(properties, binding, "Super Harness project binding");
        return loadProject(properties, projectId);
    }

    public synchronized ProjectProfile restoreProject(String ownerId, String projectId) {
        Path project = requireProject(ownerId, projectId);
        Path binding = project.resolve(BINDING_FILE);
        Properties properties = loadProperties(binding, WorkspaceError.PROJECT_NOT_FOUND, "project does not exist");
        properties.setProperty("status", "active");
        properties.setProperty("updatedAt", Instant.now().toString());
        storeReplacing(properties, binding, "Super Harness project binding");
        return loadProject(properties, projectId);
    }

    @FunctionalInterface
    public interface ProjectDeletionAction {
        void execute() throws Exception;
    }

    public synchronized void deleteProject(String ownerId, String projectId) {
        deleteProject(ownerId, projectId, () -> {});
    }

    public synchronized void deleteProject(String ownerId, String projectId, ProjectDeletionAction onUnbind) {
        Path project = requireProject(ownerId, projectId);
        ProjectProfile profile = getProject(ownerId, projectId);
        Path documentsDir = project.resolve("documents");
        List<ProjectDocument> docsToTrash = new ArrayList<>();
        if (Files.exists(documentsDir)) {
            try (Stream<Path> paths = Files.list(documentsDir)) {
                List<Path> docFiles = paths.filter(path -> path.getFileName().toString().endsWith(".properties")).toList();
                for (Path docFile : docFiles) {
                    docsToTrash.add(loadDocument(docFile, projectId));
                }
            } catch (IOException exception) {
                throw WorkspaceRegistry.ioFailure("failed to read project documents during project deletion", exception);
            }
        }

        List<Path> createdTrashDocs = new ArrayList<>();
        List<Path> createdTrashPayloads = new ArrayList<>();
        try {
            // 1. Stage documents into trash without mutating or deleting from projectRoot
            for (ProjectDocument document : docsToTrash) {
                Path payload = project.resolve("payloads").resolve(document.sha256());
                Path trashPayload = trashRoot.resolve("payloads").resolve(document.sha256());
                if (Files.exists(payload) && !Files.exists(trashPayload)) {
                    Files.copy(payload, trashPayload, StandardCopyOption.REPLACE_EXISTING);
                    createdTrashPayloads.add(trashPayload);
                }

                Properties properties = new Properties();
                properties.setProperty("ownerId", ownerId);
                properties.setProperty("id", document.id());
                properties.setProperty("projectId", document.projectId());
                properties.setProperty("name", document.name());
                properties.setProperty("sizeBytes", Long.toString(document.sizeBytes()));
                properties.setProperty("sha256", document.sha256());
                properties.setProperty("sourceKind", document.sourceKind().wireValue());
                document.sourceThreadId().ifPresent(val -> properties.setProperty("sourceThreadId", val));
                document.sourceName().ifPresent(val -> properties.setProperty("sourceName", val));
                properties.setProperty("createdAt", document.createdAt().toString());
                properties.setProperty("updatedAt", document.updatedAt().toString());
                properties.setProperty("trashedAt", Instant.now().toString());
                properties.setProperty("trashOriginProjectId", document.projectId());
                properties.setProperty("trashOriginProjectName", profile.name());

                Path trashDoc = trashRoot.resolve("documents").resolve(document.id() + ".properties");
                storeReplacing(properties, trashDoc, "Super Harness trashed document");
                createdTrashDocs.add(trashDoc);
            }

            // 2. Execute unbind action before project directory deletion
            if (onUnbind != null) {
                onUnbind.execute();
            }

            // 3. Commit: unbind succeeded and all trash entries staged; delete project directory
            deleteDirectoryRecursively(project);
        } catch (Exception failure) {
            // Roll back all staged trash entries so failure leaves trash pristine and project intact
            for (Path trashDocPath : createdTrashDocs) {
                try {
                    Files.deleteIfExists(trashDocPath);
                } catch (IOException rollbackErr) {
                    LOGGER.log(Level.WARNING, "failed to rollback trash document " + trashDocPath, rollbackErr);
                }
            }
            for (Path trashPayloadPath : createdTrashPayloads) {
                try {
                    Files.deleteIfExists(trashPayloadPath);
                } catch (IOException rollbackErr) {
                    LOGGER.log(Level.WARNING, "failed to rollback trash payload " + trashPayloadPath, rollbackErr);
                }
            }
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw WorkspaceRegistry.ioFailure("failed to delete project", failure);
        }
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
        requireActiveProject(ownerId, projectId);
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
        if (offset < 0 || maxBytes < 1 || maxBytes > workspaceLimits.maxUploadBytes()) {
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

    public synchronized void trashProjectDocument(String ownerId, String projectId, String documentId) {
        Path project = requireActiveProject(ownerId, projectId);
        ProjectProfile profile = getProject(ownerId, projectId);
        ProjectDocument document = requireDocument(project, documentId, projectId);
        trashDocumentInternal(ownerId, project, document, profile.name());
    }

    public static final int DEFAULT_TRASH_RETENTION_DAYS = 30;

    public synchronized void sweepExpiredTrash(String ownerId, int retentionDays, Instant now) {
        String owner = requireIdentity(ownerId, "ownerId");
        Path docsDir = trashRoot.resolve("documents");
        if (!Files.exists(docsDir)) {
            return;
        }
        Instant cutoff = now.minus(retentionDays, ChronoUnit.DAYS);
        List<String> expiredIds = new ArrayList<>();
        try (Stream<Path> paths = Files.list(docsDir)) {
            List<Path> files = paths.filter(p -> p.getFileName().toString().endsWith(".properties")).toList();
            for (Path file : files) {
                try {
                    Properties props = loadProperties(file, WorkspaceError.DOCUMENT_NOT_FOUND, "");
                    if (owner.equals(props.getProperty("ownerId"))) {
                        String trashedAtStr = props.getProperty("trashedAt");
                        if (trashedAtStr != null) {
                            Instant trashedAt = Instant.parse(trashedAtStr);
                            if (!trashedAt.isAfter(cutoff)) {
                                expiredIds.add(props.getProperty("id"));
                            }
                        }
                    }
                } catch (Exception parseException) {
                    LOGGER.log(Level.WARNING, "Failed to parse trash document properties during sweep: " + file, parseException);
                }
            }
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Failed to list trash documents during retention sweep", exception);
            return;
        }

        for (String id : expiredIds) {
            try {
                purgeTrashDocument(owner, id);
            } catch (Exception purgeException) {
                LOGGER.log(Level.WARNING, "Retention sweep purge failed for document " + id + "; retained for retry", purgeException);
            }
        }
    }

    public synchronized TrashDocumentList listTrashDocuments(String ownerId, int limit, int offset) {
        return listTrashDocuments(ownerId, limit, offset, DEFAULT_TRASH_RETENTION_DAYS, Instant.now());
    }

    public synchronized TrashDocumentList listTrashDocuments(
            String ownerId, int limit, int offset, int retentionDays, Instant now) {
        String owner = requireIdentity(ownerId, "ownerId");
        sweepExpiredTrash(owner, retentionDays, now);
        Path docsDir = trashRoot.resolve("documents");
        if (!Files.exists(docsDir)) {
            return new TrashDocumentList(List.of(), 0, limit, offset);
        }
        List<TrashDocument> trashed = new ArrayList<>();
        try (Stream<Path> paths = Files.list(docsDir)) {
            List<Path> files = paths.filter(path -> path.getFileName().toString().endsWith(".properties")).toList();
            for (Path file : files) {
                try {
                    Properties props = loadProperties(file, WorkspaceError.DOCUMENT_NOT_FOUND, "trashed document missing");
                    if (owner.equals(props.getProperty("ownerId"))) {
                        trashed.add(loadTrashDocument(props, file));
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Skipping unreadable trash document " + file, e);
                }
            }
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to list trash documents", exception);
        }
        trashed.sort(Comparator.comparing(TrashDocument::trashedAt).reversed().thenComparing(TrashDocument::id));
        int total = trashed.size();
        int from = Math.min(offset, total);
        int to = Math.min(total, from + limit);
        List<TrashDocument> paged = trashed.subList(from, to);
        return new TrashDocumentList(paged, total, limit, offset);
    }

    public synchronized RestoreDocumentResult restoreTrashDocument(
            String ownerId, String documentId, Optional<String> requestedTargetProjectId) {
        String owner = requireIdentity(ownerId, "ownerId");
        Path trashDoc = trashRoot.resolve("documents").resolve(documentId + ".properties");
        if (!Files.exists(trashDoc)) {
            throw new WorkspaceException(WorkspaceError.DOCUMENT_NOT_FOUND, "trash document not found");
        }
        Properties props = loadProperties(trashDoc, WorkspaceError.DOCUMENT_NOT_FOUND, "trash document not found");
        if (!owner.equals(props.getProperty("ownerId"))) {
            throw new WorkspaceException(WorkspaceError.DOCUMENT_NOT_FOUND, "trash document not found");
        }
        TrashDocument trashed = loadTrashDocument(props, trashDoc);

        String targetProjectId;
        if (requestedTargetProjectId.isPresent() && !requestedTargetProjectId.get().isBlank()) {
            targetProjectId = requestedTargetProjectId.get();
        } else if (trashed.trashOrigin().isPresent()) {
            targetProjectId = trashed.trashOrigin().get().projectId();
        } else {
            throw new WorkspaceException(WorkspaceError.PROJECT_NOT_FOUND, "no target project available");
        }

        Path targetProjectRoot = requireActiveProject(owner, targetProjectId);

        Path trashPayload = trashRoot.resolve("payloads").resolve(trashed.sha256());
        if (!Files.exists(trashPayload)) {
            throw new WorkspaceException(WorkspaceError.CONTENT_INTEGRITY, "content_missing");
        }
        try {
            long size = Files.size(trashPayload);
            if (size != trashed.sizeBytes()) {
                throw new WorkspaceException(WorkspaceError.CONTENT_INTEGRITY, "content_missing");
            }
            byte[] all = Files.readAllBytes(trashPayload);
            if (!Hashing.sha256(all).equals(trashed.sha256())) {
                throw new WorkspaceException(WorkspaceError.CONTENT_INTEGRITY, "content_missing");
            }
        } catch (IOException exception) {
            throw new WorkspaceException(WorkspaceError.CONTENT_INTEGRITY, "content_missing", exception);
        }

        List<ProjectDocument> existingDocs = listProjectDocuments(owner, targetProjectId);
        Optional<ProjectDocument> matchingCandidate = existingDocs.stream()
                .filter(d -> d.sha256().equals(trashed.sha256()))
                .findFirst();

        if (matchingCandidate.isPresent()) {
            // Verify target surviving document's payload exists and is intact!
            Path candidatePayload = targetProjectRoot.resolve("payloads").resolve(matchingCandidate.get().sha256());
            if (!Files.exists(candidatePayload)) {
                throw new WorkspaceException(WorkspaceError.CONTENT_INTEGRITY, "content_missing");
            }
            try {
                if (Files.size(candidatePayload) != matchingCandidate.get().sizeBytes()
                        || !Hashing.sha256(Files.readAllBytes(candidatePayload)).equals(matchingCandidate.get().sha256())) {
                    throw new WorkspaceException(WorkspaceError.CONTENT_INTEGRITY, "content_missing");
                }
            } catch (IOException exception) {
                throw new WorkspaceException(WorkspaceError.CONTENT_INTEGRITY, "content_missing", exception);
            }

            // Both source and target are verified. Remove trash document metadata.
            boolean otherReferenced = hasOtherTrashReferences(trashed.id(), trashed.sha256());
            if (!otherReferenced) {
                try {
                    Files.deleteIfExists(trashPayload);
                } catch (IOException exception) {
                    LOGGER.log(Level.WARNING, "failed to clean discarded trash payload after merge", exception);
                }
            }
            try {
                Files.deleteIfExists(trashDoc);
            } catch (IOException exception) {
                throw WorkspaceRegistry.ioFailure("failed to delete trashed document during merge", exception);
            }
            return new RestoreDocumentResult("merged", matchingCandidate.get());
        }

        Path targetPayload = targetProjectRoot.resolve("payloads").resolve(trashed.sha256());
        try {
            boolean copyNeeded = true;
            if (Files.exists(targetPayload)) {
                if (Files.size(targetPayload) == trashed.sizeBytes()
                        && Hashing.sha256(Files.readAllBytes(targetPayload)).equals(trashed.sha256())) {
                    copyNeeded = false;
                }
            }
            if (copyNeeded) {
                Files.copy(trashPayload, targetPayload, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!Files.exists(targetPayload)
                    || Files.size(targetPayload) != trashed.sizeBytes()
                    || !Hashing.sha256(Files.readAllBytes(targetPayload)).equals(trashed.sha256())) {
                throw new WorkspaceException(WorkspaceError.CONTENT_INTEGRITY, "content_missing");
            }
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to copy payload to restored project", exception);
        }

        Instant now = Instant.now();
        ProjectDocument restoredDoc = new ProjectDocument(
                trashed.id(),
                targetProjectId,
                trashed.name(),
                trashed.sizeBytes(),
                trashed.sha256(),
                trashed.sourceThreadId(),
                trashed.sourceKind(),
                trashed.sourceName(),
                trashed.createdAt(),
                now);

        Properties restoredProps = new Properties();
        restoredProps.setProperty("id", restoredDoc.id());
        restoredProps.setProperty("projectId", targetProjectId);
        restoredProps.setProperty("name", restoredDoc.name());
        restoredProps.setProperty("sizeBytes", Long.toString(restoredDoc.sizeBytes()));
        restoredProps.setProperty("sha256", restoredDoc.sha256());
        restoredProps.setProperty("sourceKind", restoredDoc.sourceKind().wireValue());
        restoredDoc.sourceThreadId().ifPresent(val -> restoredProps.setProperty("sourceThreadId", val));
        restoredDoc.sourceName().ifPresent(val -> restoredProps.setProperty("sourceName", val));
        restoredProps.setProperty("createdAt", restoredDoc.createdAt().toString());
        restoredProps.setProperty("updatedAt", now.toString());

        Path targetDocPath = targetProjectRoot.resolve("documents").resolve(restoredDoc.id() + ".properties");
        storeNew(restoredProps, targetDocPath, "Super Harness restored project document");

        boolean otherReferenced = hasOtherTrashReferences(trashed.id(), trashed.sha256());
        if (!otherReferenced) {
            try {
                Files.deleteIfExists(trashPayload);
            } catch (IOException exception) {
                LOGGER.log(Level.WARNING, "failed to delete trash payload after restore", exception);
            }
        }
        try {
            Files.deleteIfExists(trashDoc);
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to remove document from trash", exception);
        }

        return new RestoreDocumentResult("restored", restoredDoc);
    }

    public synchronized void purgeTrashDocument(String ownerId, String documentId) {
        String owner = requireIdentity(ownerId, "ownerId");
        Path trashDoc = trashRoot.resolve("documents").resolve(documentId + ".properties");
        if (!Files.exists(trashDoc)) {
            throw new WorkspaceException(WorkspaceError.DOCUMENT_NOT_FOUND, "trash document not found");
        }
        Properties props = loadProperties(trashDoc, WorkspaceError.DOCUMENT_NOT_FOUND, "trash document not found");
        if (!owner.equals(props.getProperty("ownerId"))) {
            throw new WorkspaceException(WorkspaceError.DOCUMENT_NOT_FOUND, "trash document not found");
        }
        String sha256 = props.getProperty("sha256");

        // 1. Guarded payload removal: delete payload file first if unreferenced by other trash documents.
        // If file deletion fails, throw ioFailure so trashDoc is NOT deleted, remaining retryable!
        boolean otherReferenced = hasOtherTrashReferences(documentId, sha256);
        if (!otherReferenced && sha256 != null && !sha256.isBlank()) {
            Path payloadFile = trashRoot.resolve("payloads").resolve(sha256);
            try {
                Files.deleteIfExists(payloadFile);
            } catch (IOException exception) {
                throw WorkspaceRegistry.ioFailure("failed to delete trash payload " + sha256, exception);
            }
        }

        // 2. Only after payload deletion succeeds (or was shared), delete metadata
        try {
            Files.deleteIfExists(trashDoc);
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to delete trash document metadata", exception);
        }
    }

    public synchronized int emptyTrash(String ownerId) {
        String owner = requireIdentity(ownerId, "ownerId");
        Path docsDir = trashRoot.resolve("documents");
        if (!Files.exists(docsDir)) {
            return 0;
        }
        List<String> docIds = new ArrayList<>();
        try (Stream<Path> paths = Files.list(docsDir)) {
            List<Path> files = paths.filter(path -> path.getFileName().toString().endsWith(".properties")).toList();
            for (Path file : files) {
                Properties props = loadProperties(file, WorkspaceError.DOCUMENT_NOT_FOUND, "trash doc missing");
                if (owner.equals(props.getProperty("ownerId"))) {
                    docIds.add(props.getProperty("id"));
                }
            }
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to empty trash", exception);
        }
        int count = 0;
        for (String docId : docIds) {
            purgeTrashDocument(owner, docId);
            count++;
        }
        return count;
    }

    /** Capture a bounded immutable baseline for a run. */
    public synchronized void beginRunChanges(
            String ownerId, String threadId, WorkspaceId workspaceId, String runId) {
        StagedRunChanges staged = stageRunChanges(ownerId, threadId, workspaceId);
        try {
            publishRunChanges(staged, runId);
        } catch (RuntimeException failure) {
            discardRunChanges(staged);
            throw failure;
        }
    }

    public synchronized WorkspaceBinding bindThreadWorkspace(String ownerId, String threadId) {
        return registry.bind(ownerId, threadId);
    }

    /**
     * Binds (or verifies) the Thread workspace and returns its host user-data root — the directory
     * behind the logical {@code /mnt/user-data} tree. Only the trusted Skill script Tool consumes
     * the raw path: the SDK executes the package entry with this root as the workspace, so its
     * {@code outputs/} lands exactly where every other producer and the Artifact chain serve.
     */
    public synchronized Path bindThreadWorkspaceDataRoot(String ownerId, String threadId) {
        return registry.userDataRoot(bindThreadWorkspace(ownerId, threadId).workspaceId());
    }

    public WorkspaceBinding requireThreadWorkspace(String ownerId, String threadId) {
        return registry.require(ownerId, threadId);
    }

    public WorkspaceChangeLimits workspaceChangeLimits() {
        return changeLimits;
    }

    /** Capture before starting the SDK run, then publish under the returned real run id. */
    public synchronized StagedRunChanges stageRunChanges(
            String ownerId, String threadId, WorkspaceId workspaceId) {
        cleanupAbandonedStages();
        WorkspaceBinding binding = requireThreadWorkspace(ownerId, threadId, workspaceId);
        Map<String, FileState> baseline = scan(registry.userDataRoot(workspaceId));
        String token = UUID.randomUUID().toString();
        Path temporary = stagedRoot(token);
        try {
            WorkspaceRegistry.createDirectories(temporary.resolve("payloads"));
            Properties properties = new Properties();
            properties.setProperty("ownerId", binding.ownerId());
            properties.setProperty("threadId", binding.threadId());
            properties.setProperty("workspaceId", binding.workspaceId().value());
            properties.setProperty("stageToken", token);
            properties.setProperty("processId", Long.toString(ProcessHandle.current().pid()));
            properties.setProperty("createdAt", Instant.now().toString());
            persistFileStates(properties, temporary, baseline);
            storeNew(properties, temporary.resolve(BASELINE_FILE), "Super Harness workspace change baseline");
            return new StagedRunChanges(token);
        } catch (RuntimeException failure) {
            try {
                deleteStage(temporary);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public synchronized void publishRunChanges(StagedRunChanges staged, String runId) {
        Path temporary = requireStage(staged);
        String run = requireIdentity(runId, "runId");
        Properties properties = loadProperties(temporary.resolve(BASELINE_FILE),
                WorkspaceError.RUN_BASELINE_NOT_FOUND, "staged run baseline does not exist");
        validateStage(properties, staged.token);
        WorkspaceId workspaceId = new WorkspaceId(properties.getProperty("workspaceId"));
        Path target = runRoot(workspaceId, run);
        if (isPublishedRun(target)) {
            throw new WorkspaceException(WorkspaceError.RUN_ALREADY_TRACKED,
                    "a baseline already exists for this workspace and run");
        }
        properties.remove("stageToken");
        properties.remove("processId");
        properties.remove("createdAt");
        properties.setProperty("runId", run);
        storeReplacing(properties, temporary.resolve(BASELINE_FILE),
                "Super Harness workspace change baseline");
        try {
            Files.createDirectories(target.getParent());
            WorkspaceRegistry.moveAtomically(temporary, target);
        } catch (FileAlreadyExistsException exception) {
            if (!isPublishedRun(target)) {
                publishRunChangesByMovingContent(temporary, target, exception);
                return;
            }
            throw new WorkspaceException(WorkspaceError.RUN_ALREADY_TRACKED,
                    "a baseline already exists for this workspace and run", exception);
        } catch (IOException exception) {
            publishRunChangesByMovingContent(temporary, target, exception);
        }
    }

    private static boolean isPublishedRun(Path runRoot) {
        return Files.isRegularFile(runRoot.resolve(BASELINE_FILE), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Publishes a run baseline when the directory rename is denied because another process holds a
     * handle on the staged directory (Windows watchers, indexers or sync clients). Payloads are moved
     * individually and the {@code baseline.properties} marker is placed last, so readers that key on
     * the marker never observe a partial snapshot. A target may already hold content from an
     * interrupted attempt; content-addressed payloads make that retry idempotent, and a partial
     * directory without the marker is never mistaken for an already tracked run.
     */
    private void publishRunChangesByMovingContent(Path staged, Path target, IOException renameFailure) {
        try {
            Files.createDirectories(target);
            movePayloadFiles(staged, target);
            movePublishedMarker(staged.resolve(BASELINE_FILE), target.resolve(BASELINE_FILE));
        } catch (FileAlreadyExistsException raced) {
            if (isPublishedRun(target)) {
                throw new WorkspaceException(WorkspaceError.RUN_ALREADY_TRACKED,
                        "a baseline already exists for this workspace and run", raced);
            }
            renameFailure.addSuppressed(raced);
            throw WorkspaceRegistry.ioFailure("failed to publish run baseline", renameFailure);
        } catch (IOException fallbackFailure) {
            renameFailure.addSuppressed(fallbackFailure);
            throw WorkspaceRegistry.ioFailure("failed to publish run baseline", renameFailure);
        }
        deleteTreeBestEffort(staged.getParent(), staged, "\\.baseline-[0-9a-f-]{36}");
    }

    public synchronized void discardRunChanges(StagedRunChanges staged) {
        if (staged == null || staged.token == null || !staged.token.matches("[0-9a-f-]{36}")) {
            return;
        }
        try {
            deleteStage(stagedRoot(staged.token));
        } catch (RuntimeException ignored) {
            // Workspace recording is diagnostic evidence and cannot alter the Run outcome.
        }
    }

    /** Persist the post-run snapshot once; later runs cannot change this run's evidence. */
    public synchronized void completeRunChanges(
            String ownerId, String threadId, WorkspaceId workspaceId, String runId) {
        WorkspaceBinding binding = requireThreadWorkspace(ownerId, threadId, workspaceId);
        String run = requireIdentity(runId, "runId");
        Path root = runRoot(workspaceId, run);
        Properties baseline = loadProperties(root.resolve(BASELINE_FILE), WorkspaceError.RUN_BASELINE_NOT_FOUND,
                "run baseline does not exist");
        validateRunBinding(binding, run, baseline);
        Path completed = root.resolve(COMPLETED_DIR);
        if (isPublishedRun(completed)) {
            Properties existing = loadProperties(completed.resolve(BASELINE_FILE),
                    WorkspaceError.RUN_CHANGES_NOT_COMPLETED, "run changes are not completed");
            validateRunBinding(binding, run, existing);
            return;
        }
        Map<String, FileState> after = scan(registry.userDataRoot(workspaceId));
        Path temporary = root.resolve(".completed-" + UUID.randomUUID());
        WorkspaceRegistry.createDirectories(temporary.resolve("payloads"));
        Properties properties = new Properties();
        properties.setProperty("ownerId", binding.ownerId());
        properties.setProperty("threadId", binding.threadId());
        properties.setProperty("workspaceId", binding.workspaceId().value());
        properties.setProperty("runId", run);
        try {
            persistFileStates(properties, temporary, after);
            storeNew(properties, temporary.resolve(BASELINE_FILE), "Super Harness completed workspace changes");
            WorkspaceRegistry.moveAtomically(temporary, completed);
        } catch (FileAlreadyExistsException raced) {
            if (!publishCompletedChangesByMovingContent(temporary, completed, raced)) {
                throw WorkspaceRegistry.ioFailure("failed to publish completed workspace changes", raced);
            }
            deleteTreeBestEffort(root, temporary, "\\.completed-[0-9a-f-]{36}");
        } catch (IOException failure) {
            if (!publishCompletedChangesByMovingContent(temporary, completed, failure)) {
                throw WorkspaceRegistry.ioFailure("failed to publish completed workspace changes", failure);
            }
            deleteTreeBestEffort(root, temporary, "\\.completed-[0-9a-f-]{36}");
        } catch (RuntimeException failure) {
            deleteTreeBestEffort(root, temporary, "\\.completed-[0-9a-f-]{36}");
            throw failure;
        }
    }

    /**
     * Completes a run when the directory rename is denied because a handle is held on the staged
     * directory. Mirrors {@link #publishRunChangesByMovingContent}: payloads move first and the
     * {@code baseline.properties} marker moves last, so a reader never sees a partial snapshot.
     * Returns true when the completed snapshot is published, false when the caller must fail.
     */
    private boolean publishCompletedChangesByMovingContent(Path staged, Path completed, IOException renameFailure) {
        if (isPublishedRun(completed)) {
            return true;
        }
        try {
            Files.createDirectories(completed);
            movePayloadFiles(staged, completed);
            movePublishedMarker(staged.resolve(BASELINE_FILE), completed.resolve(BASELINE_FILE));
            return true;
        } catch (FileAlreadyExistsException raced) {
            // A concurrent completion placed the marker first; that snapshot is authoritative.
            if (isPublishedRun(completed)) {
                return true;
            }
            renameFailure.addSuppressed(raced);
            return false;
        } catch (IOException fallbackFailure) {
            renameFailure.addSuppressed(fallbackFailure);
            return false;
        }
    }

    private static void movePayloadFiles(Path staged, Path target) throws IOException {
        Path stagedPayloads = staged.resolve("payloads");
        if (!Files.isDirectory(stagedPayloads, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path targetPayloads = target.resolve("payloads");
        Files.createDirectories(targetPayloads);
        try (Stream<Path> entries = Files.list(stagedPayloads)) {
            for (Path entry : entries.sorted().toList()) {
                moveFileIfAbsent(entry, targetPayloads.resolve(entry.getFileName()));
            }
        }
    }

    private static void moveFileIfAbsent(Path source, Path target) throws IOException {
        try {
            AtomicMoves.create(source, target);
        } catch (FileAlreadyExistsException alreadyPresent) {
            Files.deleteIfExists(source);
        }
    }

    private static void movePublishedMarker(Path source, Path target) throws IOException {
        AtomicMoves.create(source, target);
    }

    /** Compare current files with the persisted baseline; neither baseline nor current files are modified. */
    public WorkspaceChanges readRunChanges(
            String ownerId, String threadId, WorkspaceId workspaceId, String runId) {
        WorkspaceBinding binding = requireThreadWorkspace(ownerId, threadId, workspaceId);
        String run = requireIdentity(runId, "runId");
        Path root = runRoot(workspaceId, run);
        Properties properties = loadProperties(root.resolve(BASELINE_FILE), WorkspaceError.RUN_BASELINE_NOT_FOUND,
                "run baseline does not exist");
        validateRunBinding(binding, run, properties);
        Map<String, FileState> before = loadBaseline(root, properties);
        Path completed = root.resolve(COMPLETED_DIR);
        Properties completedProperties = loadProperties(completed.resolve(BASELINE_FILE),
                WorkspaceError.RUN_CHANGES_NOT_COMPLETED, "run changes are not completed");
        validateRunBinding(binding, run, completedProperties);
        Map<String, FileState> after = loadBaseline(completed, completedProperties);
        List<String> paths = Stream.concat(before.keySet().stream(), after.keySet().stream()).distinct().sorted().toList();
        List<FileChange> changes = new ArrayList<>();
        List<DiffFile> diffFiles = new ArrayList<>();
        List<ProjectPath> unavailable = new ArrayList<>();
        List<ProjectPath> binary = new ArrayList<>();
        List<ProjectPath> sensitive = new ArrayList<>();
        Map<ProjectPath, WorkspaceLineCounts> lineCounts = new LinkedHashMap<>();
        int diffOutputBytes = 0;
        boolean truncated = false;
        for (String pathValue : paths) {
            FileState oldState = before.get(pathValue);
            FileState newState = after.get(pathValue);
            if (oldState != null && newState != null && oldState.sameFile(newState)) {
                continue;
            }
            boolean sensitivePath = WorkspaceSensitivePath.matches(pathValue)
                    || (oldState != null && oldState.sensitive())
                    || (newState != null && newState.sensitive());
            ProjectPath path = ProjectPath.of(pathValue);
            FileVersion oldVersion = oldState == null ? null : oldState.version();
            FileVersion newVersion = newState == null ? null : newState.version();
            FileChangeType type = oldState == null ? FileChangeType.CREATE
                    : newState == null ? FileChangeType.DELETE : FileChangeType.REPLACE;
            changes.add(new FileChange(type, path, null, oldVersion, newVersion));
            if (sensitivePath) {
                unavailable.add(path);
                sensitive.add(path);
                lineCounts.put(path, new WorkspaceLineCounts(0, 0));
                continue;
            }
            if ((oldState != null && oldState.size() > changeLimits.maxFileBytes())
                    || (newState != null && newState.size() > changeLimits.maxFileBytes())) {
                unavailable.add(path);
                continue;
            }
            Optional<String> oldText = oldState == null ? Optional.of("") : decodeText(oldState.bytes());
            Optional<String> newText = newState == null ? Optional.of("") : decodeText(newState.bytes());
            if (oldText.isPresent() && newText.isPresent()) {
                DiffFile candidate = new DiffFile(path, oldText.orElseThrow(), newText.orElseThrow());
                BoundedDiffService.FileDiff single;
                try {
                    single = diffs.diffFile(candidate, changeLimits.maxFileBytes());
                } catch (WorkspaceException failure) {
                    if (failure.error() != WorkspaceError.FILE_TOO_LARGE) {
                        throw failure;
                    }
                    unavailable.add(path);
                    truncated = true;
                    continue;
                }
                lineCounts.put(path, new WorkspaceLineCounts(single.additions(), single.deletions()));
                if ((long) diffOutputBytes + single.outputBytes() <= changeLimits.maxOutputBytes()) {
                    diffFiles.add(candidate);
                    diffOutputBytes += single.outputBytes();
                } else {
                    unavailable.add(path);
                    truncated = true;
                }
            } else {
                unavailable.add(path);
                binary.add(path);
                lineCounts.put(path, new WorkspaceLineCounts(0, 0));
            }
        }
        DiffResult diff = diffs.diff(new DiffRequest(diffFiles, changeLimits.maxFiles(),
                changeLimits.maxFileBytes(), changeLimits.maxOutputBytes()));
        return new WorkspaceChanges(changes, diff, unavailable, binary, sensitive, lineCounts, truncated);
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
        Path project = requireActiveProject(ownerId, projectId);
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

    private static void validateRunBinding(WorkspaceBinding binding, String run, Properties properties) {
        if (!binding.ownerId().equals(properties.getProperty("ownerId"))
                || !binding.threadId().equals(properties.getProperty("threadId"))
                || !binding.workspaceId().value().equals(properties.getProperty("workspaceId"))
                || !run.equals(properties.getProperty("runId"))) {
            throw new WorkspaceException(WorkspaceError.OWNER_MISMATCH,
                    "run baseline does not belong to this owner, thread, and workspace");
        }
    }

    private Map<String, FileState> scan(Path userDataRoot) {
        Map<String, FileState> states = new LinkedHashMap<>();
        for (String rootName : List.of("workspace", "outputs")) {
            Path scanRoot = userDataRoot.resolve(rootName);
            try (Stream<Path> paths = Files.walk(scanRoot)) {
                for (Path path : paths.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                        .filter(candidate -> !candidate.getFileName().toString().endsWith(".part"))
                        .sorted().toList()) {
                    if (states.size() >= changeLimits.maxFiles()) {
                        throw new WorkspaceException(WorkspaceError.FILE_TOO_LARGE,
                                "workspace change scan exceeds file-count limit");
                    }
                    RestrictedWorkspaceService.ensureNoLinks(userDataRoot, path);
                    String logical = userDataRoot.relativize(path).toString()
                            .replace(java.io.File.separatorChar, '/');
                    logical = LogicalWorkspacePath.normalize(logical);
                    BasicFileAttributes attributes = Files.readAttributes(
                            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    boolean sensitive = WorkspaceSensitivePath.matches(logical);
                    if (sensitive) {
                        states.put(logical, new FileState(logical, attributes.size(),
                                attributes.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS),
                                null, null, true));
                    } else {
                        byte[] bytes = RestrictedWorkspaceService.readAllVerified(userDataRoot, path,
                                workspaceLimits.maxWorkingFileBytes());
                        states.put(logical, new FileState(logical, bytes.length,
                                attributes.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS),
                                Hashing.sha256(bytes), bytes, false));
                    }
                }
            } catch (IOException exception) {
                throw WorkspaceRegistry.ioFailure("failed to scan workspace changes", exception);
            }
        }
        return states;
    }

    private Map<String, FileState> loadBaseline(Path runRoot, Properties properties) {
        Map<String, FileState> states = new LinkedHashMap<>();
        properties.stringPropertyNames().stream().filter(key -> key.startsWith("file.")).sorted().forEach(key -> {
            String path;
            long size;
            String hash = null;
            long modifiedNanos = 0;
            boolean sensitive = false;
            try {
                path = new String(Base64.getUrlDecoder().decode(key.substring(5)), StandardCharsets.UTF_8);
                if (!LogicalWorkspacePath.normalize(path).equals(path)) {
                    throw new IllegalArgumentException("non-canonical path");
                }
                String value = properties.getProperty(key);
                String[] fields = value.split(":", 3);
                boolean metadataOnly = fields.length == 3 && fields[0].equals("S");
                sensitive = metadataOnly || WorkspaceSensitivePath.matches(path);
                if (metadataOnly) {
                    size = Long.parseLong(fields[1]);
                    modifiedNanos = Long.parseLong(fields[2]);
                    if (modifiedNanos < 0) throw new IllegalArgumentException("invalid sensitive mtime");
                } else {
                    if (fields.length != 2 || !fields[1].matches("[0-9a-f]{64}")) {
                        throw new IllegalArgumentException("invalid baseline entry");
                    }
                    size = Long.parseLong(fields[0]);
                    hash = fields[1];
                }
                if (size < 0 || size > workspaceLimits.maxWorkingFileBytes()) {
                    throw new IllegalArgumentException("invalid baseline size");
                }
            } catch (RuntimeException exception) {
                throw new WorkspaceException(WorkspaceError.CONTENT_INTEGRITY,
                        "run baseline metadata is corrupt", exception);
            }
            if (sensitive) {
                // Legacy snapshots persisted sensitive paths as size:hash plus a payload. Keep the
                // hash only as an internal comparison token; never resolve or read that payload.
                states.put(path, new FileState(path, size, modifiedNanos, hash, null, true));
                return;
            }
            Path payload = runRoot.resolve("payloads").resolve(hash);
            if (!Files.isRegularFile(payload, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkspaceException(WorkspaceError.CONTENT_INTEGRITY,
                        "run baseline payload is missing");
            }
            byte[] bytes = RestrictedWorkspaceService.readAllVerified(
                    runRoot, payload, workspaceLimits.maxWorkingFileBytes());
            if (bytes.length != size || !Hashing.sha256(bytes).equals(hash)) {
                throw new WorkspaceException(WorkspaceError.CONTENT_INTEGRITY,
                        "run baseline payload does not match persisted metadata");
            }
            states.put(path, new FileState(path, size, 0, hash, bytes, false));
        });
        return states;
    }

    private static void persistFileStates(Properties properties, Path root, Map<String, FileState> states) {
        for (FileState state : states.values()) {
            String key = "file." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(state.path().getBytes(StandardCharsets.UTF_8));
            if (state.sensitive()) {
                properties.setProperty(key, "S:" + state.size() + ":" + state.modifiedNanos());
                continue;
            }
            properties.setProperty(key, state.size() + ":" + state.sha256());
            Path payload = root.resolve("payloads").resolve(state.sha256());
            if (!Files.exists(payload)) writeNew(payload, state.bytes());
        }
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

    private Path requireActiveProject(String ownerId, String projectId) {
        Path root = requireProject(ownerId, projectId);
        Properties binding = loadProperties(root.resolve(BINDING_FILE), WorkspaceError.PROJECT_NOT_FOUND,
                "project does not exist");
        if (!"active".equals(binding.getProperty("status"))) {
            throw new WorkspaceException(WorkspaceError.PROJECT_NOT_FOUND, "project is archived");
        }
        return root;
    }

    private void trashDocumentInternal(String ownerId, Path projectRoot, ProjectDocument document, String projectName) {
        Path payload = projectRoot.resolve("payloads").resolve(document.sha256());
        Path trashPayload = trashRoot.resolve("payloads").resolve(document.sha256());
        try {
            if (Files.exists(payload) && !Files.exists(trashPayload)) {
                Files.copy(payload, trashPayload, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to copy document payload to trash", exception);
        }
        Properties properties = new Properties();
        properties.setProperty("ownerId", ownerId);
        properties.setProperty("id", document.id());
        properties.setProperty("projectId", document.projectId());
        properties.setProperty("name", document.name());
        properties.setProperty("sizeBytes", Long.toString(document.sizeBytes()));
        properties.setProperty("sha256", document.sha256());
        properties.setProperty("sourceKind", document.sourceKind().wireValue());
        document.sourceThreadId().ifPresent(val -> properties.setProperty("sourceThreadId", val));
        document.sourceName().ifPresent(val -> properties.setProperty("sourceName", val));
        properties.setProperty("createdAt", document.createdAt().toString());
        properties.setProperty("updatedAt", document.updatedAt().toString());
        properties.setProperty("trashedAt", Instant.now().toString());
        properties.setProperty("trashOriginProjectId", document.projectId());
        properties.setProperty("trashOriginProjectName", projectName);

        Path trashDoc = trashRoot.resolve("documents").resolve(document.id() + ".properties");
        storeReplacing(properties, trashDoc, "Super Harness trashed document");

        Path originalDoc = projectRoot.resolve("documents").resolve(document.id() + ".properties");
        try {
            Files.deleteIfExists(originalDoc);
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to delete original document properties", exception);
        }
    }

    private TrashDocument loadTrashDocument(Properties properties, Path path) {
        try {
            String id = properties.getProperty("id");
            String name = validateFilename(properties.getProperty("name"));
            long size = Long.parseLong(properties.getProperty("sizeBytes"));
            String sha256 = properties.getProperty("sha256");
            ProjectDocumentSourceKind kind = switch (properties.getProperty("sourceKind")) {
                case "upload" -> ProjectDocumentSourceKind.UPLOAD;
                case "output" -> ProjectDocumentSourceKind.OUTPUT;
                default -> throw new IllegalArgumentException("document source kind is invalid");
            };
            Optional<String> sourceThreadId = Optional.ofNullable(properties.getProperty("sourceThreadId"));
            Optional<String> sourceName = Optional.ofNullable(properties.getProperty("sourceName"));
            Instant createdAt = Instant.parse(properties.getProperty("createdAt"));
            Instant updatedAt = Instant.parse(properties.getProperty("updatedAt"));
            Instant trashedAt = Instant.parse(properties.getProperty("trashedAt"));
            String originProjId = properties.getProperty("trashOriginProjectId");
            String originProjName = properties.getProperty("trashOriginProjectName");
            Optional<TrashDocument.TrashOrigin> origin = (originProjId != null && originProjName != null)
                    ? Optional.of(new TrashDocument.TrashOrigin(originProjId, originProjName))
                    : Optional.empty();
            return new TrashDocument(id, name, size, sha256, sourceThreadId, kind, sourceName, createdAt, updatedAt, trashedAt, origin);
        } catch (RuntimeException exception) {
            throw new WorkspaceException(WorkspaceError.CONTENT_INTEGRITY, "trashed document metadata is corrupt", exception);
        }
    }

    private boolean hasOtherTrashReferences(String excludeDocId, String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            return false;
        }
        Path docsDir = trashRoot.resolve("documents");
        if (!Files.exists(docsDir)) {
            return false;
        }
        String excludeFileName = excludeDocId + ".properties";
        try (Stream<Path> paths = Files.list(docsDir)) {
            return paths.filter(p -> p.getFileName().toString().endsWith(".properties"))
                    .filter(p -> !p.getFileName().toString().equals(excludeFileName))
                    .anyMatch(p -> {
                        try {
                            Properties pProps = loadProperties(p, WorkspaceError.DOCUMENT_NOT_FOUND, "");
                            return sha256.equals(pProps.getProperty("sha256"));
                        } catch (Exception ignored) {
                            return false;
                        }
                    });
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "failed to scan trash documents for payload references", exception);
            return true;
        }
    }

    private static void deleteDirectoryRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw WorkspaceRegistry.ioFailure("failed to delete project directory", exception);
        }
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

    private Path stagedRoot(String token) {
        if (token == null || !token.matches("[0-9a-f-]{36}")) {
            throw new WorkspaceException(WorkspaceError.RUN_BASELINE_NOT_FOUND,
                    "staged run baseline does not exist");
        }
        return changesRoot.resolve(".baseline-" + token);
    }

    private Path requireStage(StagedRunChanges staged) {
        if (staged == null) {
            throw new WorkspaceException(WorkspaceError.RUN_BASELINE_NOT_FOUND,
                    "staged run baseline does not exist");
        }
        Path path = stagedRoot(staged.token);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException(WorkspaceError.RUN_BASELINE_NOT_FOUND,
                    "staged run baseline does not exist");
        }
        return path;
    }

    private static void validateStage(Properties properties, String expectedToken) {
        try {
            if (!expectedToken.equals(properties.getProperty("stageToken"))) {
                throw new IllegalArgumentException("stage token does not match metadata");
            }
            long processId = Long.parseLong(properties.getProperty("processId"));
            if (processId != ProcessHandle.current().pid()) {
                throw new IllegalArgumentException("stage belongs to a different process");
            }
            Instant.parse(properties.getProperty("createdAt"));
            requireIdentity(properties.getProperty("ownerId"), "ownerId");
            requireIdentity(properties.getProperty("threadId"), "threadId");
            new WorkspaceId(properties.getProperty("workspaceId"));
        } catch (RuntimeException failure) {
            throw new WorkspaceException(WorkspaceError.CONTENT_INTEGRITY,
                    "staged run baseline metadata is corrupt", failure);
        }
    }

    private void cleanupAbandonedStages() {
        List<Path> stages;
        try (Stream<Path> paths = Files.list(changesRoot)) {
            stages = paths.filter(path -> path.getFileName().toString().matches("\\.baseline-[0-9a-f-]{36}"))
                    .limit(MAX_ABANDONED_STAGES + 1L)
                    .toList();
        } catch (IOException failure) {
            LOGGER.log(Level.WARNING, "failed to inspect staged run baselines", failure);
            return;
        }
        if (stages.size() > MAX_ABANDONED_STAGES) {
            LOGGER.warning("staged run baseline cleanup reached its bounded inspection limit");
            stages = stages.subList(0, MAX_ABANDONED_STAGES);
        }
        Instant now = Instant.now();
        for (Path stage : stages) {
            Properties properties;
            try {
                properties = loadProperties(stage.resolve(BASELINE_FILE), WorkspaceError.RUN_BASELINE_NOT_FOUND,
                        "staged run baseline does not exist");
                long processId = Long.parseLong(properties.getProperty("processId"));
                Instant createdAt = Instant.parse(properties.getProperty("createdAt"));
                boolean originatingProcessAlive = ProcessHandle.of(processId)
                        .map(ProcessHandle::isAlive)
                        .orElse(false);
                if (!originatingProcessAlive || createdAt.plus(STAGE_MAX_AGE).isBefore(now)) {
                    try {
                        deleteStage(stage);
                    } catch (RuntimeException cleanupFailure) {
                        LOGGER.log(Level.WARNING, "failed to remove an abandoned staged baseline", cleanupFailure);
                    }
                }
            } catch (RuntimeException corrupt) {
                try {
                    deleteStage(stage);
                } catch (RuntimeException cleanupFailure) {
                    corrupt.addSuppressed(cleanupFailure);
                    LOGGER.log(Level.WARNING, "failed to remove a corrupt staged baseline", corrupt);
                }
            }
        }
    }

    private void deleteStage(Path stage) {
        Path normalized = stage.toAbsolutePath().normalize();
        if (!normalized.getParent().equals(changesRoot)
                || !normalized.getFileName().toString().matches("\\.baseline-[0-9a-f-]{36}")) {
            throw new WorkspaceException(WorkspaceError.PATH_ESCAPE,
                    "refusing to delete a path outside staged run baselines");
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        deleteTreeWithin(changesRoot, normalized, "\\.baseline-[0-9a-f-]{36}");
    }

    private static void deleteTreeWithin(Path parent, Path target, String filenamePattern) {
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.getParent().equals(parent.toAbsolutePath().normalize())
                || !normalized.getFileName().toString().matches(filenamePattern)) {
            throw new WorkspaceException(WorkspaceError.PATH_ESCAPE,
                    "refusing to delete a path outside the expected temporary root");
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException failure) {
            throw WorkspaceRegistry.ioFailure("failed to clean staged run baseline", failure);
        }
    }

    private static void deleteTreeBestEffort(Path parent, Path target, String filenamePattern) {
        try {
            deleteTreeWithin(parent, target, filenamePattern);
        } catch (RuntimeException cleanupFailure) {
            LOGGER.log(Level.WARNING, "failed to remove temporary workspace change evidence", cleanupFailure);
        }
    }

    private static Optional<String> decodeText(byte[] bytes) {
        try {
            int offset = 0;
            java.nio.charset.Charset charset = StandardCharsets.UTF_8;
            if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) {
                charset = StandardCharsets.UTF_16LE;
                offset = 2;
            } else if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) {
                charset = StandardCharsets.UTF_16BE;
                offset = 2;
            } else if (bytes.length >= 3 && bytes[0] == (byte) 0xef
                    && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) {
                offset = 3;
            }
            String decoded = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
            return decoded.indexOf('\0') >= 0 ? Optional.empty() : Optional.of(decoded);
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
            AtomicMoves.replace(part, target);
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

    private record FileState(
            String path, long size, long modifiedNanos, String sha256, byte[] bytes, boolean sensitive) {
        private FileState {
            bytes = bytes == null ? null : bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes == null ? null : bytes.clone();
        }

        FileVersion version() {
            return new FileVersion(FileType.FILE, size, sensitive ? "sensitive" : sha256);
        }

        boolean sameFile(FileState other) {
            if (sensitive || other.sensitive) {
                if (sensitive != other.sensitive || size != other.size) return false;
                if (sha256 != null || other.sha256 != null) {
                    return sha256 != null && sha256.equals(other.sha256);
                }
                return modifiedNanos == other.modifiedNanos;
            }
            return sha256.equals(other.sha256);
        }
    }
}

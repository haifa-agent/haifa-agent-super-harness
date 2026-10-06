package io.haifa.agent.superharness.workspace;

import io.haifa.agent.project.workspace.WorkspaceId;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

final class WorkspaceRegistry {
    private static final String BINDING_FILE = "binding.properties";
    private final Path workspacesRoot;

    WorkspaceRegistry(Path storageRoot) {
        this.workspacesRoot = storageRoot.toAbsolutePath().normalize().resolve("workspaces");
        createDirectories(workspacesRoot);
    }

    synchronized WorkspaceBinding bind(String ownerId, String threadId) {
        String owner = requireIdentity(ownerId, "ownerId");
        String thread = requireIdentity(threadId, "threadId");
        WorkspaceId workspaceId = workspaceId(owner, thread);
        Path workspaceRoot = workspaceRoot(workspaceId);
        Path bindingFile = workspaceRoot.resolve(BINDING_FILE);
        if (Files.exists(bindingFile)) {
            WorkspaceBinding existing = load(workspaceId);
            if (!existing.ownerId().equals(owner) || !existing.threadId().equals(thread)) {
                throw new WorkspaceException(WorkspaceError.OWNER_MISMATCH,
                        "workspace identity does not match persisted binding");
            }
            return existing;
        }
        try {
            Files.createDirectories(workspaceRoot.resolve("user-data/uploads"));
            Files.createDirectories(workspaceRoot.resolve("user-data/workspace"));
            Files.createDirectories(workspaceRoot.resolve("user-data/outputs"));
            Properties properties = new Properties();
            properties.setProperty("ownerId", owner);
            properties.setProperty("threadId", thread);
            properties.setProperty("workspaceId", workspaceId.value());
            atomicStore(properties, bindingFile, "Super Harness workspace binding");
            return new WorkspaceBinding(owner, thread, workspaceId);
        } catch (IOException exception) {
            throw ioFailure("failed to persist workspace binding", exception);
        }
    }

    WorkspaceBinding require(String ownerId, String threadId) {
        String owner = requireIdentity(ownerId, "ownerId");
        String thread = requireIdentity(threadId, "threadId");
        WorkspaceBinding binding = load(workspaceId(owner, thread));
        if (!binding.ownerId().equals(owner) || !binding.threadId().equals(thread)) {
            throw new WorkspaceException(WorkspaceError.OWNER_MISMATCH,
                    "workspace identity does not match persisted binding");
        }
        return binding;
    }

    WorkspaceBinding requireOwner(String ownerId, WorkspaceId workspaceId) {
        String owner = requireIdentity(ownerId, "ownerId");
        WorkspaceBinding binding = load(workspaceId);
        if (!binding.ownerId().equals(owner)) {
            throw new WorkspaceException(WorkspaceError.OWNER_MISMATCH,
                    "workspace belongs to a different owner");
        }
        return binding;
    }

    WorkspaceBinding load(WorkspaceId workspaceId) {
        Path bindingFile = workspaceRoot(workspaceId).resolve(BINDING_FILE);
        if (!Files.isRegularFile(bindingFile)) {
            throw new WorkspaceException(WorkspaceError.WORKSPACE_NOT_FOUND,
                    "workspace binding does not exist: " + workspaceId.value());
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(bindingFile)) {
            properties.load(input);
        } catch (IOException exception) {
            throw ioFailure("failed to read workspace binding", exception);
        }
        String persistedId = properties.getProperty("workspaceId");
        if (!workspaceId.value().equals(persistedId)) {
            throw new WorkspaceException(WorkspaceError.IO_FAILURE, "workspace binding id is corrupt");
        }
        return new WorkspaceBinding(properties.getProperty("ownerId"), properties.getProperty("threadId"), workspaceId);
    }

    Path userDataRoot(WorkspaceId workspaceId) {
        load(workspaceId);
        return workspaceRoot(workspaceId).resolve("user-data").toAbsolutePath().normalize();
    }

    private Path workspaceRoot(WorkspaceId workspaceId) {
        if (workspaceId == null || !workspaceId.value().matches("ws-[0-9a-f]{32}")) {
            throw new WorkspaceException(WorkspaceError.WORKSPACE_NOT_FOUND, "invalid workspace id");
        }
        return workspacesRoot.resolve(workspaceId.value());
    }

    private static WorkspaceId workspaceId(String owner, String thread) {
        return new WorkspaceId("ws-" + Hashing.sha256(owner + "\0" + thread).substring(0, 32));
    }

    private static String requireIdentity(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new WorkspaceException(WorkspaceError.INVALID_IDENTITY, field + " must contain 1-256 characters");
        }
        return value;
    }

    private static void atomicStore(Properties properties, Path target, String comment) throws IOException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try (OutputStream output = Files.newOutputStream(part)) {
            properties.store(output, comment);
        }
        moveAtomically(part, target);
    }

    /**
     * Renames atomically when the file system supports it.
     *
     * <p>Windows reports a briefly held handle (a scanner, indexer or concurrent directory reader
     * touching a just-written file) as an {@link java.nio.file.AccessDeniedException} or, for a
     * sharing/lock violation, a plain {@link java.nio.file.FileSystemException}. Both are transient
     * and are retried within a bounded window (about 1.5 seconds in total) before being reported. An
     * existing target is reported as {@link java.nio.file.FileAlreadyExistsException}, as on other
     * platforms, so callers' "someone else published it" handling applies. Failures that are not
     * transient (missing source, non-empty target directory) are rethrown immediately.
     */
    static void moveAtomically(Path source, Path target) throws IOException {
        long backoffMillis = 10;
        for (int attempt = 1; ; attempt++) {
            try {
                try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                    Files.move(source, target);
                }
                return;
            } catch (java.nio.file.NoSuchFileException
                    | java.nio.file.DirectoryNotEmptyException
                    | java.nio.file.FileAlreadyExistsException fatal) {
                throw fatal;
            } catch (java.nio.file.FileSystemException denied) {
                if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    var exists = new java.nio.file.FileAlreadyExistsException(
                            source.toString(), target.toString(), denied.getReason());
                    exists.initCause(denied);
                    throw exists;
                }
                if (attempt >= MOVE_ATTEMPTS) {
                    throw denied;
                }
                try {
                    Thread.sleep(backoffMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw denied;
                }
                backoffMillis = Math.min(200, backoffMillis * 2);
            }
        }
    }

    private static final int MOVE_ATTEMPTS = 12;

    static void createDirectories(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException exception) {
            throw ioFailure("failed to create storage directory", exception);
        }
    }

    static WorkspaceException ioFailure(String message, Exception cause) {
        return new WorkspaceException(WorkspaceError.IO_FAILURE, message, cause);
    }
}

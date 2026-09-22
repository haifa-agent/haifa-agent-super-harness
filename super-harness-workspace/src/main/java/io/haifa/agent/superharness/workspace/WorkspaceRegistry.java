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
        WorkspaceId workspaceId = new WorkspaceId("ws-" + Hashing.sha256(owner + "\0" + thread).substring(0, 32));
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

    static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

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

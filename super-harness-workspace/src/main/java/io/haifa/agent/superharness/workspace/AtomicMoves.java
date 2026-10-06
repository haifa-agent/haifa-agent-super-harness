package io.haifa.agent.superharness.workspace;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Publishes a temporary file by renaming it over (or into) its final name, retrying a transient
 * Windows occupancy failure for a bounded window.
 *
 * <p>Windows reports a handle briefly held on a just-written file (an indexer, antivirus scanner or
 * sync client) in two forms: a handle held on the <em>target</em> surfaces as
 * {@link AccessDeniedException} ({@code ERROR_ACCESS_DENIED}), while a handle held on the
 * <em>source</em> temporary surfaces as a plain {@link FileSystemException}
 * ({@code ERROR_SHARING_VIOLATION}). Both are transient, so both are retried. The retry catches
 * {@link FileSystemException} and immediately rethrows the types that waiting cannot fix:
 * {@link NoSuchFileException}, {@link FileAlreadyExistsException}, {@link DirectoryNotEmptyException},
 * {@link NotDirectoryException} and {@link AtomicMoveNotSupportedException} (which has its own
 * non-atomic fallback in {@link #move}). Classification uses the exception type only, never the
 * localized message.
 *
 * <p>Both operations retry for about 0.85 seconds in total, then rethrow the original exception.
 * The success path stays a single atomic rename, so no reader observes a partial file. This is a
 * Super Harness persistence helper, not Agent Runtime state.
 */
public final class AtomicMoves {
    /** Bounded retry window; the sleeps below total about 0.85 seconds. */
    static final int MOVE_ATTEMPTS = 12;
    private static final long INITIAL_BACKOFF_MILLIS = 10;
    private static final long MAXIMUM_BACKOFF_MILLIS = 100;

    private AtomicMoves() {
    }

    /** Atomically replaces {@code target} with {@code source}, retrying a transient occupancy. */
    public static void replace(Path source, Path target) throws IOException {
        moveWithRetry(source, target, true);
    }

    /**
     * Atomically moves {@code source} to {@code target} without replacing an existing target,
     * retrying a transient occupancy.
     *
     * <p>An existing target is reported as {@link FileAlreadyExistsException} on every platform,
     * including Windows where the rename is otherwise seen as an access denial, so callers keep
     * their existing "someone else published it" handling.
     */
    public static void create(Path source, Path target) throws IOException {
        moveWithRetry(source, target, false);
    }

    /**
     * Deletes a temporary publication file, ignoring a cleanup failure so it can never mask the
     * result of the rename that just ran. A held handle or a stale name is harmless here: after a
     * successful rename the temporary is already gone, and after a failed one the original
     * exception is the one callers must see. This is intentionally not retried.
     */
    public static void deleteQuietly(Path temporary) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // The temporary name carries no authority; the published target already decided the result.
        }
    }

    private static void moveWithRetry(Path source, Path target, boolean replace) throws IOException {
        long backoffMillis = INITIAL_BACKOFF_MILLIS;
        for (int attempt = 1; ; attempt++) {
            try {
                move(source, target, replace);
                return;
            } catch (FileSystemException failure) {
                if (!isTransientOccupancy(failure)) {
                    throw failure;
                }
                if (!replace && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    FileAlreadyExistsException exists = new FileAlreadyExistsException(
                            source.toString(), target.toString(), failure.getReason());
                    exists.initCause(failure);
                    throw exists;
                }
                if (attempt >= MOVE_ATTEMPTS) {
                    throw failure;
                }
                try {
                    Thread.sleep(backoffMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
                backoffMillis = Math.min(MAXIMUM_BACKOFF_MILLIS, backoffMillis * 2);
            }
        }
    }

    /**
     * A held handle looks like a plain {@link FileSystemException}; every other
     * {@link FileSystemException} subtype is a permanent condition that retrying cannot fix.
     */
    private static boolean isTransientOccupancy(FileSystemException failure) {
        return !(failure instanceof NoSuchFileException
                || failure instanceof FileAlreadyExistsException
                || failure instanceof DirectoryNotEmptyException
                || failure instanceof NotDirectoryException
                || failure instanceof AtomicMoveNotSupportedException);
    }

    private static void move(Path source, Path target, boolean replace) throws IOException {
        try {
            if (replace) {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException unsupported) {
            if (replace) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target);
            }
        }
    }
}

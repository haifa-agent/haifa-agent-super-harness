package io.haifa.agent.superharness.workspace;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Publishes a temporary file by renaming it over (or into) its final name, retrying a transient
 * Windows access denial for a bounded window.
 *
 * <p>Windows reports a handle briefly held on a just-written file (an indexer, antivirus scanner or
 * sync client) as {@link AccessDeniedException}, so a rename that is atomic on other platforms
 * fails momentarily. Both operations below retry only that denial and only for about one second in
 * total, then rethrow the original exception. Failures that are not an access denial - a missing
 * source, a missing target directory, or an existing target for a create - fail immediately and are
 * never swallowed. The success path stays a single atomic rename, so no reader observes a partial
 * file. This is a Super Harness persistence helper, not Agent Runtime state.
 */
public final class AtomicMoves {
    /** Bounded retry window; the sleeps below total about one second. */
    static final int MOVE_ATTEMPTS = 12;
    private static final long INITIAL_BACKOFF_MILLIS = 10;
    private static final long MAXIMUM_BACKOFF_MILLIS = 100;

    private AtomicMoves() {
    }

    /** Atomically replaces {@code target} with {@code source}, retrying a transient denial. */
    public static void replace(Path source, Path target) throws IOException {
        moveWithRetry(source, target, true);
    }

    /**
     * Atomically moves {@code source} to {@code target} without replacing an existing target,
     * retrying a transient denial.
     *
     * <p>An existing target is reported as {@link FileAlreadyExistsException} on every platform,
     * including Windows where the rename is otherwise seen as an access denial, so callers keep
     * their existing "someone else published it" handling.
     */
    public static void create(Path source, Path target) throws IOException {
        moveWithRetry(source, target, false);
    }

    private static void moveWithRetry(Path source, Path target, boolean replace) throws IOException {
        long backoffMillis = INITIAL_BACKOFF_MILLIS;
        for (int attempt = 1; ; attempt++) {
            try {
                move(source, target, replace);
                return;
            } catch (AccessDeniedException denied) {
                if (!replace && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    FileAlreadyExistsException exists = new FileAlreadyExistsException(
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
                backoffMillis = Math.min(MAXIMUM_BACKOFF_MILLIS, backoffMillis * 2);
            }
        }
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

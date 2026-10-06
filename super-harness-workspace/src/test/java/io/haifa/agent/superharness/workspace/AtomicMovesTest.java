package io.haifa.agent.superharness.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A handle held on a file only denies rename with Windows sharing semantics, so the handle-based
 * tests skip with an assumption on other platforms. Each one first proves the precondition - that a
 * direct move is actually denied while the handle is held - before exercising the bounded retry.
 * A handle on the target denies with {@link AccessDeniedException}; a handle on the source denies
 * with a plain {@link FileSystemException}. The missing-source test needs no assumption.
 */
class AtomicMovesTest {
    @TempDir
    Path directory;

    @Test
    void replaceSucceedsAfterAShortLivedDenialOnTheTarget() throws Exception {
        assumeWindowsSharingDenial();
        Path target = Files.writeString(directory.resolve("target.json"), "before");
        Path source = Files.writeString(directory.resolve("source.tmp"), "after");

        try (InputStream handle = new FileInputStream(target.toFile())) {
            assertThatThrownBy(() -> Files.move(
                            source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING))
                    .isInstanceOf(AccessDeniedException.class);

            Thread release = new Thread(() -> {
                sleep(150);
                closeQuietly(handle);
            });
            release.start();

            AtomicMoves.replace(source, target);
            release.join();
        }

        assertThat(target).hasContent("after");
        assertThat(source).doesNotExist();
    }

    @Test
    void replaceFailsWithinTheBoundedWindowWhenTheHandleNeverCloses() throws Exception {
        assumeWindowsSharingDenial();
        Path target = Files.writeString(directory.resolve("target.json"), "before");
        Path source = Files.writeString(directory.resolve("source.tmp"), "after");

        try (InputStream handle = new FileInputStream(target.toFile())) {
            long started = System.nanoTime();
            assertThatThrownBy(() -> AtomicMoves.replace(source, target))
                    .isInstanceOf(AccessDeniedException.class);
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertThat(elapsedMillis).isLessThan(5000);
        }

        assertThat(source).exists();
        assertThat(target).hasContent("before");
    }

    @Test
    void replaceSucceedsAfterAShortLivedSharingViolationOnTheSource() throws Exception {
        assumeWindowsSharingDenial();
        Path target = Files.writeString(directory.resolve("target.json"), "before");
        Path source = Files.writeString(directory.resolve("source.tmp"), "after");

        try (InputStream handle = new FileInputStream(source.toFile())) {
            // A handle on the source is a sharing violation reported as a plain FileSystemException,
            // not an AccessDeniedException - the case the bounded retry must also cover.
            assertThatThrownBy(() -> Files.move(
                            source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING))
                    .isInstanceOf(FileSystemException.class)
                    .isNotInstanceOf(AccessDeniedException.class);

            Thread release = new Thread(() -> {
                sleep(150);
                closeQuietly(handle);
            });
            release.start();

            AtomicMoves.replace(source, target);
            release.join();
        }

        assertThat(target).hasContent("after");
        assertThat(source).doesNotExist();
    }

    @Test
    void replaceFailsWithinTheBoundedWindowWhenTheSourceHandleNeverCloses() throws Exception {
        assumeWindowsSharingDenial();
        Path target = Files.writeString(directory.resolve("target.json"), "before");
        Path source = Files.writeString(directory.resolve("source.tmp"), "after");

        try (InputStream handle = new FileInputStream(source.toFile())) {
            long started = System.nanoTime();
            assertThatThrownBy(() -> AtomicMoves.replace(source, target))
                    .isInstanceOf(FileSystemException.class)
                    .isNotInstanceOf(AccessDeniedException.class);
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertThat(elapsedMillis).isGreaterThanOrEqualTo(500).isLessThan(5000);
        }

        assertThat(source).exists();
        assertThat(target).hasContent("before");
    }

    @Test
    void doesNotRetryAMissingSource() throws Exception {
        Path target = Files.writeString(directory.resolve("target.json"), "before");
        Path missing = directory.resolve("missing.tmp");

        long started = System.nanoTime();
        assertThatThrownBy(() -> AtomicMoves.replace(missing, target))
                .isInstanceOf(NoSuchFileException.class);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        // The full retry window is about 0.85s; a permanent failure must return far sooner.
        assertThat(elapsedMillis).isLessThan(400);
        assertThat(target).hasContent("before");
    }

    private static void assumeWindowsSharingDenial() {
        Assumptions.assumeTrue(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"),
                "A held file handle only denies rename with Windows sharing semantics");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // The test only needs the handle released; a failure here would surface as a retry timeout.
        }
    }
}

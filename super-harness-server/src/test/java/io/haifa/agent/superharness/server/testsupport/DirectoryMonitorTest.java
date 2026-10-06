package io.haifa.agent.superharness.server.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Proves the in-repository directory monitor recreates the observed condition. On this machine a
 * registered {@code WatchService} key alone does not block a whole-directory rename, but the
 * monitor's brief {@link java.io.FileInputStream} opens on new/modified files do: while such a
 * handle is held, renaming the containing directory and atomically replacing the file are both
 * rejected; once the monitor closes the same operations succeed. Only meaningful on Windows.
 */
class DirectoryMonitorTest {
    @Test
    void monitoredFileOpenRejectsRenameAndReplaceUntilClosed() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        assumeTrue(os.contains("win"), "directory-handle sharing is a Windows behavior; os=" + os);

        Path root = Files.createTempDirectory("directory-monitor-proof-");
        Path watched = Files.createDirectory(root.resolve("watched"));

        Exception renameRejected;
        try (var monitor = DirectoryMonitor.open(watched, Duration.ofSeconds(2))) {
            monitor.arm();
            Files.writeString(watched.resolve("fresh.txt"), "payload");
            awaitOpenHandle(monitor);
            renameRejected = attempt(() -> Files.move(watched, root.resolve("moved-while-watched"),
                    StandardCopyOption.ATOMIC_MOVE));
        }

        assertThat(renameRejected)
                .as("a monitored whole-directory rename must be rejected while a child file handle is held")
                .isNotNull();

        // The same operation succeeds once every handle is released.
        Files.move(watched, root.resolve("moved-after-close"), StandardCopyOption.ATOMIC_MOVE);
        assertThat(Files.isDirectory(root.resolve("moved-after-close"))).isTrue();

        // And an atomically replaced file is rejected while its own handle is held by the monitor.
        Path replaced = root.resolve("replaced.txt");
        Files.writeString(replaced, "old");
        Exception replaceRejected;
        try (var monitor = DirectoryMonitor.open(root, Duration.ofSeconds(2))) {
            monitor.arm();
            Path temporary = root.resolve("replacement.txt");
            Files.writeString(temporary, "new");
            awaitOpenHandle(monitor);
            replaceRejected = attempt(() -> Files.move(temporary, replaced,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
        }
        assertThat(replaceRejected)
                .as("an atomic single-file replace must be rejected while the monitor holds the target open")
                .isNotNull();
        Files.writeString(replaced, "after-close");
    }

    private static void awaitOpenHandle(DirectoryMonitor monitor) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (monitor.openHandleCount() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(monitor.openHandleCount()).as("monitor must hold the freshly written file open").isPositive();
    }

    private static Exception attempt(ThrowingRunnable action) {
        try {
            action.run();
            return null;
        } catch (Exception failure) {
            return failure;
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}

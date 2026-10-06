package io.haifa.agent.superharness.server.testsupport;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test-only replica of an external process that watches a directory tree. It registers a
 * {@link WatchService} on every directory (including directories created later) so each directory
 * keeps an open handle, and briefly opens new/modified files with {@link FileInputStream}. This is
 * never referenced by production code; it exists only to reproduce the "monitored data directory"
 * condition inside this repository.
 */
public final class DirectoryMonitor implements AutoCloseable {
    private static final Duration STOP_DEADLINE = Duration.ofSeconds(5);

    /** Default time a monitored file is held open; long enough to perturb concurrent writes. */
    public static final Duration DEFAULT_FILE_HOLD = Duration.ofMillis(40);

    private final Path root;
    private final Duration fileHold;
    private final WatchService service;
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean armed = new AtomicBoolean(false);
    private final List<WatchKey> keys = new CopyOnWriteArrayList<>();
    private final List<FileInputStream> fileHandles = new CopyOnWriteArrayList<>();
    private final List<Thread> fileOpeners = new CopyOnWriteArrayList<>();

    private DirectoryMonitor(Path root, Duration fileHold) throws IOException {
        this.root = root;
        this.fileHold = fileHold;
        this.service = FileSystems.getDefault().newWatchService();
        registerTree();
        this.thread = new Thread(this::consume, "directory-monitor");
        thread.setDaemon(true);
        thread.start();
    }

    /** Number of files currently held open by the monitor; lets a test wait for the condition. */
    public int openHandleCount() {
        return fileHandles.size();
    }

    /**
     * Enables the brief file opens. Directory registration always happens from construction, but
     * file handles perturb whatever writes concurrently, so callers arm it only once the gateway
     * bootstrap has finished and the Run under test is about to start.
     */
    public void arm() {
        armed.set(true);
    }

    /** Starts watching an already-existing directory with the {@link #DEFAULT_FILE_HOLD}. */
    public static DirectoryMonitor open(Path root) throws IOException {
        return open(root, DEFAULT_FILE_HOLD);
    }

    /** Starts watching an already-existing directory; later-created sub-directories are registered too. */
    public static DirectoryMonitor open(Path root, Duration fileHold) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("monitor root must be an existing directory: " + root);
        }
        return new DirectoryMonitor(root, fileHold);
    }

    private void registerTree() throws IOException {
        try (var walk = Files.walk(root)) {
            for (Path directory : walk.filter(Files::isDirectory).toList()) {
                register(directory);
            }
        }
    }

    private void register(Path directory) throws IOException {
        keys.add(directory.register(
                service,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY));
    }

    private void consume() {
        while (running.get()) {
            WatchKey key;
            try {
                key = service.poll(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.nio.file.ClosedWatchServiceException closed) {
                return;
            }
            if (key == null) continue;
            Path directory = (Path) key.watchable();
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                Path child = directory.resolve((Path) event.context());
                if (Files.isDirectory(child)) {
                    try {
                        register(child);
                    } catch (IOException alreadyGone) {
                        // The directory was removed between the event and registration.
                    }
                } else {
                    openBriefly(child);
                }
            }
            if (!key.reset()) keys.remove(key);
        }
    }

    private void openBriefly(Path file) {
        if (!armed.get()) return;
        Thread opener = Thread.ofVirtual().name("directory-monitor-open").start(() -> {
            try (FileInputStream handle = new FileInputStream(file.toFile())) {
                fileHandles.add(handle);
                try {
                    Thread.sleep(fileHold.toMillis());
                } finally {
                    fileHandles.remove(handle);
                }
            } catch (IOException | InterruptedException unavailable) {
                if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        });
        fileOpeners.add(opener);
    }

    @Override
    public void close() throws Exception {
        running.set(false);
        thread.interrupt();
        thread.join(STOP_DEADLINE.toMillis());
        service.close();
        for (WatchKey key : keys) key.cancel();
        keys.clear();
        for (Thread opener : fileOpeners) opener.join(STOP_DEADLINE.toMillis());
        for (FileInputStream handle : List.copyOf(fileHandles)) {
            try {
                handle.close();
            } catch (IOException ignored) {
                // Best effort release; the JVM will close remaining handles at exit.
            }
        }
        fileHandles.clear();
    }
}

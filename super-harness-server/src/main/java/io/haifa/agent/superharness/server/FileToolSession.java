package io.haifa.agent.superharness.server;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-Bridge in-memory READ-BEFORE-WRITE state for the frozen file tools (issue #3857).
 *
 * <p>Frozen DeerFlow stamps a content hash on each successful {@code read_file} ToolMessage and
 * blocks a later {@code write_file} (existing file, including {@code append}) or
 * {@code str_replace} unless the newest mark for the path still matches the file's current hash.
 * This holder is the SH equivalent, keyed by the canonical logical path returned from the
 * workspace service. It deliberately keeps no persistence: a rebuilt Bridge or gateway restart
 * clears every mark, which is the stricter direction (the model must re-read).
 */
final class FileToolSession {
    private final Map<String, String> recentReads = new HashMap<>();

    /** Record the verified hash the model last saw for {@code normalizedPath}. */
    void recordRead(String normalizedPath, String sha256) {
        Objects.requireNonNull(normalizedPath, "normalizedPath");
        Objects.requireNonNull(sha256, "sha256");
        synchronized (recentReads) {
            recentReads.put(normalizedPath, sha256);
        }
    }

    /** Whether the newest recorded read for {@code normalizedPath} matches {@code sha256}. */
    boolean matchesRead(String normalizedPath, String sha256) {
        synchronized (recentReads) {
            return sha256.equals(recentReads.get(normalizedPath));
        }
    }

    /** Any write invalidates earlier reads of the same path. */
    void invalidate(String normalizedPath) {
        synchronized (recentReads) {
            recentReads.remove(normalizedPath);
        }
    }
}

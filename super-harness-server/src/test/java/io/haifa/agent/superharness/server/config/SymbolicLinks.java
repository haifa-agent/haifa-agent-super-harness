package io.haifa.agent.superharness.server.config;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Symbolic-link fixture shared by the trusted Skill script declarations. Creating a link needs a
 * privilege on Windows (or Developer Mode), so the capability is asserted per test: a host that
 * cannot create one skips the case with that reason instead of silently reporting a pass.
 */
final class SymbolicLinks {

    private SymbolicLinks() {}

    static Path create(Path link, Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (java.io.IOException | UnsupportedOperationException notPermitted) {
            assumeTrue(false, "this host cannot create symbolic links: " + notPermitted);
            throw new IllegalStateException(notPermitted);
        }
    }
}

package io.haifa.agent.superharness.server.api;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

/** Guarded JUnit launcher used only by {@code scripts/run-w05-interaction-smoke.ps1}. */
class InteractionAcceptanceServerTest {
    @Test
    void serveTestOnlyInteractionFixture() throws Exception {
        assumeTrue("1".equals(System.getenv("W05_INTERACTION_ACCEPTANCE")));
        InteractionAcceptanceServer.run();
    }
}

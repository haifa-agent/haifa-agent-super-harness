package io.haifa.agent.superharness.server.api;

import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
import io.haifa.agent.superharness.server.run.HaifaPublicInteractionSpikeTest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-classpath-only acceptance entry point for the W05 frozen-card browser profile.
 *
 * <p>This class is deliberately absent from the executable JAR. Normal bootstrap still registers
 * no tools; the acceptance runner must opt in through the guarded JUnit launcher below.
 */
final class InteractionAcceptanceServer {
    private InteractionAcceptanceServer() {}

    static void run() throws Exception {
        var configuration = LocalServerConfiguration.fromEnvironment(System.getenv());
        var api = DeerFlowBootstrapApi.createForInteractionTests(
                configuration,
                configuration.dataDirectory(System.getenv()),
                null,
                List.of(new HaifaPublicInteractionSpikeTest.MutatingProbeTool(new AtomicInteger())));
        Runtime.getRuntime().addShutdownHook(new Thread(api::close, "w05-interaction-shutdown"));
        api.start();
        System.out.printf(
                "W05 test-only interaction acceptance API listening on http://%s:%d%n",
                api.address().getAddress().getHostAddress(), api.address().getPort());
        new CountDownLatch(1).await();
    }
}

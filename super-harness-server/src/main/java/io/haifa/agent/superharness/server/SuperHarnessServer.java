package io.haifa.agent.superharness.server;

import java.util.concurrent.CountDownLatch;

/** Local-only entry point for the first DeerFlow-compatible HTTP slice. */
public final class SuperHarnessServer {
    private SuperHarnessServer() {}

    public static void main(String[] args) throws Exception {
        LocalServerConfiguration configuration =
                LocalServerConfiguration.fromEnvironment(System.getenv());
        DeerFlowBootstrapApi api = DeerFlowBootstrapApi.create(configuration);
        Runtime.getRuntime().addShutdownHook(new Thread(api::close, "super-harness-shutdown"));
        api.start();
        System.out.printf(
                "Super Harness local API listening on http://%s:%d%n",
                api.address().getAddress().getHostAddress(), api.address().getPort());
        new CountDownLatch(1).await();
    }
}

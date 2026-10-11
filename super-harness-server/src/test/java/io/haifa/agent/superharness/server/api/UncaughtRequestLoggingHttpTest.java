package io.haifa.agent.superharness.server.api;

import java.util.Map;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An unhandled request failure must leave a WARNING record and keep the 500 response, without
 * leaking the failure detail to the client. A probe route registered before start makes the
 * otherwise unreachable path deterministic; it goes through the same Javalin exception mapper.
 */
class UncaughtRequestLoggingHttpTest {
    @TempDir
    Path dataDirectory;

    @Test
    void anUnhandledFailureIsLoggedAsWarningAndKeptAsServerError() throws Exception {
        DeerFlowBootstrapApi api = DeerFlowBootstrapApi.create(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0), dataDirectory,
                GatewayFixture.productionEnvironment(Map.of()));
        try {
            Field javalinField = DeerFlowBootstrapApi.class.getDeclaredField("javalin");
            javalinField.setAccessible(true);
            io.javalin.Javalin javalin = (io.javalin.Javalin) javalinField.get(api);
            javalin.get("/__w04-uncaught-probe", ctx -> {
                throw new IllegalStateException("probe boom");
            });

            Logger logger = Logger.getLogger(DeerFlowBootstrapApi.class.getName());
            List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
            Handler capture = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    records.add(record);
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            Level previousLevel = logger.getLevel();
            logger.addHandler(capture);
            logger.setLevel(Level.ALL);
            api.start();
            try {
                URI baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
                HttpResponse<String> response = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(baseUri.resolve("/__w04-uncaught-probe")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());

                assertThat(response.statusCode()).isEqualTo(500);
                assertThat(response.body()).doesNotContain("probe boom");
                assertThat(records)
                        .anySatisfy(record -> {
                            assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                            assertThat(record.getMessage())
                                    .contains("GET")
                                    .contains("/__w04-uncaught-probe")
                                    .contains(IllegalStateException.class.getName())
                                    .contains("probe boom");
                        });
            } finally {
                logger.removeHandler(capture);
                logger.setLevel(previousLevel);
            }
        } finally {
            api.close();
        }
    }
}

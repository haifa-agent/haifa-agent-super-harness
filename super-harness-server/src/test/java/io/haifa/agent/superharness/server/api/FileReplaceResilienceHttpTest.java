package io.haifa.agent.superharness.server.api;

import java.util.Map;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The thread catalog publishes {@code threads.json} by an atomic rename. A handle briefly held on
 * that file only denies the rename with Windows sharing semantics, so the test skips elsewhere. It
 * proves the precondition (a direct rename is denied) before exercising the bounded retry over HTTP.
 */
class FileReplaceResilienceHttpTest {
    private final HttpClient client = HttpClient.newHttpClient();
    @TempDir
    Path dataDirectory;
    private DeerFlowBootstrapApi api;
    private URI baseUri;

    @BeforeEach
    void startServer() throws Exception {
        api = DeerFlowBootstrapApi.create(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0), dataDirectory,
                GatewayFixture.productionEnvironment(Map.of()));
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
    }

    @AfterEach
    void stopServer() {
        if (api != null) {
            api.close();
        }
    }

    @Test
    void createThreadReturnsOkWhenTheCatalogTargetDeniesRenameBriefly() throws Exception {
        Assumptions.assumeTrue(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"),
                "A held file handle only denies rename with Windows sharing semantics");
        assertThat(createThread("thread-one").statusCode()).isEqualTo(200);
        Path catalog = dataDirectory.resolve("owners").resolve("default").resolve("threads.json");
        assertThat(catalog).exists();

        Path probe = Files.writeString(dataDirectory.resolve("rename-probe.tmp"), "probe");
        try (InputStream handle = new FileInputStream(catalog.toFile())) {
            assertThatThrownBy(() -> Files.move(
                            probe, catalog, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING))
                    .isInstanceOf(AccessDeniedException.class);
            Files.deleteIfExists(probe);

            Thread release = new Thread(() -> {
                sleep(150);
                closeQuietly(handle);
            });
            release.start();
            HttpResponse<String> response = createThread("thread-two");
            release.join();

            assertThat(response.statusCode()).isEqualTo(200);
        }

        String persisted = Files.readString(catalog);
        assertThat(persisted).contains("thread-one").contains("thread-two");
    }

    private HttpResponse<String> createThread(String threadId) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve("/api/threads"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"thread_id\":\"" + threadId + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
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
            // The request retry covers the handle release; failures would surface as a failed request.
        }
    }
}

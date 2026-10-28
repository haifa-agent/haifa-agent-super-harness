package io.haifa.agent.superharness.server.integrations.lark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LarkHttpDeadlineTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void archiveFetchAbortsOnBodyStallAndDeletesPartialFile(@TempDir Path tempDir) throws Exception {
        AtomicBoolean serverStallInterrupted = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/archive.zip", exchange -> {
            byte[] chunk = "partial-zip-bytes".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, chunk.length + 1024);
            OutputStream os = exchange.getResponseBody();
            os.write(chunk);
            os.flush();
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                serverStallInterrupted.set(true);
            }
            try {
                os.write(new byte[1024]);
                os.close();
            } catch (IOException ignored) {
                // Client cancelled or disconnected
            }
        });
        server.start();

        int port = server.getAddress().getPort();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(300))
                .build();
        Duration timeout = Duration.ofMillis(150);
        var source = new LarkArchiveSource.GitHub(client, timeout, v -> "http://127.0.0.1:" + port + "/archive.zip");

        long start = System.nanoTime();
        try {
            assertThatThrownBy(() -> source.fetch("v1.0.65"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasCauseInstanceOf(HttpTimeoutException.class);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMs).as("fetch should abort near timeout rather than 1500ms").isLessThan(900);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void archiveFetchSucceedsWhenBodyCompletesWithinDeadline() throws Exception {
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(zipBytes)) {
            zos.putNextEntry(new ZipEntry("README.txt"));
            zos.write("valid archive content".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        byte[] payload = zipBytes.toByteArray();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/archive.zip", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        server.start();

        int port = server.getAddress().getPort();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();
        Duration timeout = Duration.ofSeconds(2);
        var source = new LarkArchiveSource.GitHub(client, timeout, v -> "http://127.0.0.1:" + port + "/archive.zip");

        Path fetched = null;
        try {
            fetched = source.fetch("v1.0.65");
            assertThat(Files.isRegularFile(fetched)).isTrue();
            assertThat(Files.size(fetched)).isEqualTo(payload.length);
        } finally {
            server.stop(0);
            if (fetched != null) {
                Files.deleteIfExists(fetched);
            }
        }
    }

    @Test
    void latestCliVersionReturnsNullOnBodyStall() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/releases/latest", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {}
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("{\"tag_name\":\"v1.0.65\"}".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {}
        });
        server.start();

        int port = server.getAddress().getPort();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
        Duration timeout = Duration.ofMillis(150);
        var registration = new LarkHttpAppRegistration(json, client, Duration.ofSeconds(1), timeout,
                null, "http://127.0.0.1:" + port + "/releases/latest");

        long start = System.nanoTime();
        try {
            String version = registration.latestCliVersion();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(version).isNull();
            assertThat(elapsedMs).as("latestCliVersion should abort near timeout").isLessThan(900);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void latestCliVersionReturnsVersionWhenBodyCompletes() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/releases/latest", exchange -> {
            byte[] body = "{\"tag_name\":\"v1.0.65\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        int port = server.getAddress().getPort();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        var registration = new LarkHttpAppRegistration(json, client, Duration.ofSeconds(2), Duration.ofSeconds(2),
                null, "http://127.0.0.1:" + port + "/releases/latest");

        try {
            assertThat(registration.latestCliVersion()).isEqualTo("v1.0.65");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void postFormAbortsOnBodyStallAndThrowsSafeException() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(LarkHttpAppRegistration.REGISTRATION_PATH, exchange -> {
            exchange.sendResponseHeaders(200, 200);
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {}
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("{\"user_code\":\"U1\",\"device_code\":\"D1\"}".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {}
        });
        server.start();

        int port = server.getAddress().getPort();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
        Duration timeout = Duration.ofMillis(150);
        var registration = new LarkHttpAppRegistration(json, client, timeout, Duration.ofSeconds(1),
                "http://127.0.0.1:" + port, "http://127.0.0.1:" + port + "/latest");

        long start = System.nanoTime();
        try {
            assertThatThrownBy(() -> registration.begin("feishu"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("HttpTimeoutException");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMs).as("postForm should abort near timeout").isLessThan(900);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void postFormSucceedsWhenBodyCompletes() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(LarkHttpAppRegistration.REGISTRATION_PATH, exchange -> {
            byte[] body = "{\"user_code\":\"U1\",\"device_code\":\"D1\",\"expires_in\":300,\"interval\":5}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        int port = server.getAddress().getPort();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        var registration = new LarkHttpAppRegistration(json, client, Duration.ofSeconds(2), Duration.ofSeconds(2),
                "http://127.0.0.1:" + port, "http://127.0.0.1:" + port + "/latest");

        try {
            LarkAppRegistration.Begin begin = registration.begin("feishu");
            assertThat(begin.userCode()).isEqualTo("U1");
            assertThat(begin.deviceCode()).isEqualTo("D1");
            assertThat(begin.expiresIn()).isEqualTo(300);
            assertThat(begin.interval()).isEqualTo(5);
        } finally {
            server.stop(0);
        }
    }
}

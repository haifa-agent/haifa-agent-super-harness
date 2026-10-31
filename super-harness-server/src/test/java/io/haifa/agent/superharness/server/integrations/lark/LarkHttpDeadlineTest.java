package io.haifa.agent.superharness.server.integrations.lark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Flow;
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
        var source = new LarkArchiveSource.GitHub(client, timeout, v -> "http://127.0.0.1:" + port + "/archive.zip", tempDir);

        long start = System.nanoTime();
        try {
            assertThatThrownBy(() -> source.fetch("v1.0.65"))
                    .as("a stalled GitHub download is an upstream outage, not a client input error")
                    .isInstanceOf(LarkUpstreamUnavailableException.class)
                    .hasCauseInstanceOf(HttpTimeoutException.class);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMs).as("fetch should abort near timeout rather than 1500ms").isLessThan(900);
            try (var files = Files.list(tempDir)) {
                assertThat(files.toList()).as("Partial archive in tempDir must be deleted").isEmpty();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void archiveFetchSucceedsWhenBodyCompletesWithinDeadline(@TempDir Path tempDir) throws Exception {
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
        var source = new LarkArchiveSource.GitHub(client, timeout, v -> "http://127.0.0.1:" + port + "/archive.zip", tempDir);

        Path fetched = null;
        try {
            fetched = source.fetch("v1.0.65");
            assertThat(Files.isRegularFile(fetched)).isTrue();
            assertThat(fetched.getParent()).isEqualTo(tempDir);
            assertThat(Files.size(fetched)).isEqualTo(payload.length);
        } finally {
            server.stop(0);
            if (fetched != null) {
                Files.deleteIfExists(fetched);
            }
            try (var files = Files.list(tempDir)) {
                assertThat(files.toList()).isEmpty();
            }
        }
    }

    @Test
    void boundedArchiveCancelledBeforeSubscribeDoesNotRecreateTargetFile(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("cancelled-target.zip");
        Files.createFile(target);
        Files.delete(target);
        assertThat(Files.exists(target)).isFalse();

        var subscriber = new LarkArchiveSource.BoundedArchive(target, 1024);
        subscriber.cancel();
        assertThat(subscriber.getBody().toCompletableFuture().isCancelled()).isTrue();

        var subscriptionCancelled = new AtomicBoolean();
        var requested = new AtomicBoolean();
        Flow.Subscription subscription = new Flow.Subscription() {
            @Override public void request(long n) { requested.set(true); }
            @Override public void cancel() { subscriptionCancelled.set(true); }
        };

        subscriber.onSubscribe(subscription);
        assertThat(subscriptionCancelled.get()).as("Subscription must be cancelled when subscriber was already cancelled").isTrue();
        assertThat(requested.get()).as("No data must be requested").isFalse();
        assertThat(Files.exists(target)).as("Target file must not be recreated after cancellation").isFalse();
        assertThat(subscriber.getBody().toCompletableFuture().isCancelled()).isTrue();
    }

    @Test
    void boundedSendEnforcesBodyDeadlineWhenHeadersArriveImmediately() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/delayed-body", exchange -> {
            exchange.sendResponseHeaders(200, 1024);
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {}
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(new byte[1024]);
            } catch (IOException ignored) {}
        });
        server.start();

        int port = server.getAddress().getPort();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
        AtomicBoolean headersReceived = new AtomicBoolean();
        HttpResponse.BodyHandler<String> handler = responseInfo -> {
            headersReceived.set(true);
            return HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        };

        // Header timeout is intentionally generous (10s); overall transfer deadline is short (200ms)
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/delayed-body"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        long start = System.nanoTime();
        try {
            assertThatThrownBy(() -> LarkHttpCalls.sendWithDeadline(client, request, handler, Duration.ofMillis(200)))
                    .isInstanceOf(HttpTimeoutException.class);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(headersReceived.get()).as("Headers must have been received before body timeout occurred").isTrue();
            assertThat(elapsedMs).as("Body timeout must abort near 200ms rather than waiting 1500ms").isLessThan(900);
        } finally {
            server.stop(0);
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
                    .as("a stalled accounts host is an upstream outage, not a client input error")
                    .isInstanceOf(LarkUpstreamUnavailableException.class)
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

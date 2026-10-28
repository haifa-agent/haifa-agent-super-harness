package io.haifa.agent.superharness.server.integrations.lark;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Function;

/** Source of the official Lark CLI content archive for one release tag. */
public interface LarkArchiveSource {
    /** Download (or resolve) the archive; the caller owns deletion of a temporary file. */
    Path fetch(String version) throws IOException;

    /** Official GitHub content archive over HTTPS with a bounded size and timeout. */
    final class GitHub implements LarkArchiveSource {
        static final Duration TIMEOUT = Duration.ofSeconds(60);
        static final int MAX_BYTES = LarkSkillPackInstaller.MAX_ARCHIVE_BYTES;
        private final HttpClient client;
        private final Duration timeout;
        private final Function<String, String> urlResolver;

        public GitHub() {
            this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), TIMEOUT, LarkSkillPackInstaller::archiveUrl);
        }

        GitHub(HttpClient client) {
            this(client, TIMEOUT, LarkSkillPackInstaller::archiveUrl);
        }

        GitHub(HttpClient client, Duration timeout, Function<String, String> urlResolver) {
            if (client.followRedirects() != HttpClient.Redirect.NEVER) throw new IllegalArgumentException("Archive redirects are disabled");
            this.client = client;
            this.timeout = timeout;
            this.urlResolver = urlResolver;
        }

        @Override
        public Path fetch(String version) throws IOException {
            String url = urlResolver.apply(version);
            Path archive = Files.createTempFile("haifa-lark-skills-", ".zip");
            boolean ok = false;
            try (BoundedArchive subscriber = new BoundedArchive(archive, MAX_BYTES)) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(timeout)
                        .header("User-Agent", "haifa-super-harness")
                        .GET()
                        .build();
                HttpResponse<Path> response = LarkHttpCalls.sendWithDeadline(
                        client, request, info -> subscriber, timeout);
                if (!response.uri().equals(request.uri())) {
                    throw new IllegalArgumentException("Lark archive redirects are not permitted");
                }
                if (response.statusCode() != 200) {
                    throw new IllegalArgumentException("Could not download the Lark skill pack (" + version
                            + ") from GitHub: HTTP " + response.statusCode());
                }
                if (Files.size(archive) > MAX_BYTES) {
                    throw new IllegalArgumentException("Lark CLI source archive is too large.");
                }
                ok = true;
                return archive;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while downloading the Lark skill pack", interrupted);
            } catch (IOException | RuntimeException failure) {
                if (failure instanceof IllegalArgumentException) {
                    throw (IllegalArgumentException) failure;
                }
                throw new IllegalArgumentException("Could not download the Lark skill pack (" + version
                        + ") from GitHub. Check the Gateway's internet access, or pre-stage the archive via "
                        + LarkSkillCatalog.ARCHIVE_ENV + ".", failure);
            } finally {
                if (!ok) {
                    try {
                        Files.deleteIfExists(archive);
                    } catch (IOException ignored) {
                        // Best-effort temp cleanup.
                    }
                }
            }
        }
    }

    /** Streams to disk with back pressure, cancelling before a byte beyond the limit is written. */
    final class BoundedArchive implements HttpResponse.BodySubscriber<Path>, AutoCloseable {
        private final java.util.concurrent.CompletableFuture<Path> result = new java.util.concurrent.CompletableFuture<>();
        private final Path target;
        private final long limit;
        private java.util.concurrent.Flow.Subscription subscription;
        private java.io.OutputStream output;
        private long written;

        BoundedArchive(Path target, long limit) {
            this.target = target;
            this.limit = limit;
        }

        @Override public java.util.concurrent.CompletionStage<Path> getBody() { return result; }

        @Override public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
            subscription = value;
            try {
                output = Files.newOutputStream(target);
                value.request(1);
            } catch (IOException failure) { onError(failure); }
        }

        @Override public void onNext(java.util.List<java.nio.ByteBuffer> chunks) {
            if (result.isDone()) return;
            try {
                long incoming = chunks.stream().mapToLong(java.nio.ByteBuffer::remaining).sum();
                if (incoming > limit - written) throw new IOException("Lark CLI source archive is too large.");
                byte[] buffer = new byte[8192];
                for (java.nio.ByteBuffer chunk : chunks) {
                    while (chunk.hasRemaining()) {
                        int size = Math.min(chunk.remaining(), buffer.length);
                        chunk.get(buffer, 0, size);
                        output.write(buffer, 0, size);
                        written += size;
                    }
                }
                subscription.request(1);
            } catch (IOException failure) { onError(failure); }
        }

        @Override public void onError(Throwable failure) {
            if (subscription != null) subscription.cancel();
            try { if (output != null) output.close(); } catch (IOException close) { failure.addSuppressed(close); }
            result.completeExceptionally(failure);
        }

        @Override public void onComplete() {
            try {
                if (output != null) output.close();
                result.complete(target);
            } catch (IOException failure) { onError(failure); }
        }

        @Override
        public void close() {
            cancel();
        }

        void cancel() {
            if (subscription != null) {
                try { subscription.cancel(); } catch (RuntimeException ignored) {}
            }
            try {
                if (output != null) output.close();
            } catch (IOException ignored) {}
            result.cancel(true);
        }
    }
}

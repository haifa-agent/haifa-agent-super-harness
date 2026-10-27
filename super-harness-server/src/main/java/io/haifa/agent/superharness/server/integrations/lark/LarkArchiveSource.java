package io.haifa.agent.superharness.server.integrations.lark;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Source of the official Lark CLI content archive for one release tag. */
public interface LarkArchiveSource {
    /** Download (or resolve) the archive; the caller owns deletion of a temporary file. */
    Path fetch(String version) throws IOException;

    /** Official GitHub content archive over HTTPS with a bounded size and timeout. */
    final class GitHub implements LarkArchiveSource {
        static final Duration TIMEOUT = Duration.ofSeconds(60);
        static final int MAX_BYTES = LarkSkillPackInstaller.MAX_ARCHIVE_BYTES;
        private final HttpClient client;

        public GitHub() {
            this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        }

        GitHub(HttpClient client) {
            this.client = client;
        }

        @Override
        public Path fetch(String version) throws IOException {
            String url = LarkSkillPackInstaller.archiveUrl(version);
            Path archive = Files.createTempFile("haifa-lark-skills-", ".zip");
            boolean ok = false;
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(TIMEOUT)
                        .header("User-Agent", "haifa-super-harness")
                        .GET()
                        .build();
                HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(archive,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.WRITE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING));
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
}

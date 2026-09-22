package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeerFlowWorkspaceApiTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @TempDir
    Path dataDirectory;
    private DeerFlowBootstrapApi api;
    private URI baseUri;

    @BeforeEach
    void startServer() throws Exception {
        api = DeerFlowBootstrapApi.createForTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory,
                ignored -> LocalProbeModelTestSupport.binding());
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
    }

    @AfterEach
    void stopServer() {
        api.close();
    }

    @Test
    void frozenUploadListAndArtifactRangeUseTheSameVerifiedBytes() throws Exception {
        createThread("thread-files");
        byte[] source = "alpha\nbeta\n".getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> uploaded = upload(
                "thread-files", "quoted-boundary", List.of(new TestFile("report.txt", "text/plain", source)), true);

        assertThat(uploaded.statusCode()).isEqualTo(200);
        JsonNode upload = json.readTree(uploaded.body());
        assertThat(upload.path("success").asBoolean()).isTrue();
        assertThat(upload.path("skipped_files").isArray()).isTrue();
        JsonNode file = upload.path("files").get(0);
        assertThat(file.path("filename").asText()).isEqualTo("report.txt");
        assertThat(file.path("original_filename").asText()).isEqualTo("report.txt");
        assertThat(file.path("size").asLong()).isEqualTo(source.length);
        assertThat(file.path("virtual_path").asText())
                .isEqualTo("/mnt/user-data/uploads/report.txt");
        assertThat(file.path("artifact_url").asText())
                .isEqualTo("/api/threads/thread-files/artifacts/mnt/user-data/uploads/report.txt");

        JsonNode limits = json.readTree(get("/api/threads/thread-files/uploads/limits").body());
        assertThat(limits.path("max_files").asInt()).isEqualTo(10);
        assertThat(limits.path("max_file_size").asLong()).isPositive();
        assertThat(limits.path("max_total_size").asLong())
                .isGreaterThanOrEqualTo(limits.path("max_file_size").asLong());

        JsonNode listing = json.readTree(get("/api/threads/thread-files/uploads/list").body());
        assertThat(listing.path("count").asInt()).isEqualTo(1);
        assertThat(listing.path("files").get(0).path("filename").asText()).isEqualTo("report.txt");
        assertThat(listing.path("files").get(0).path("size").asLong()).isEqualTo(source.length);

        String artifactPath = file.path("artifact_url").asText();
        HttpResponse<byte[]> full = getBytes(artifactPath, null);
        assertThat(full.statusCode()).isEqualTo(200);
        assertThat(full.body()).isEqualTo(source);
        assertThat(full.headers().firstValue("content-length")).contains(Integer.toString(source.length));
        assertThat(full.headers().firstValue("accept-ranges")).contains("bytes");
        assertThat(full.headers().firstValue("etag")).contains("\"" + sha256(source) + "\"");
        assertThat(full.headers().firstValue("content-disposition")).hasValueSatisfying(
                value -> assertThat(value).startsWith("inline;"));

        HttpResponse<byte[]> range = getBytes(artifactPath, "bytes=2-6");
        assertThat(range.statusCode()).isEqualTo(206);
        assertThat(range.body()).isEqualTo("pha\nb".getBytes(StandardCharsets.UTF_8));
        assertThat(range.headers().firstValue("content-range")).contains("bytes 2-6/11");
        assertThat(range.headers().firstValue("content-length")).contains("5");

        HttpResponse<byte[]> suffix = getBytes(artifactPath, "bytes=-5");
        assertThat(suffix.statusCode()).isEqualTo(206);
        assertThat(suffix.body()).isEqualTo("beta\n".getBytes(StandardCharsets.UTF_8));
        assertThat(getBytes(artifactPath + "?download=true", null)
                .headers().firstValue("content-disposition"))
                .hasValueSatisfying(value -> assertThat(value).startsWith("attachment;"));
    }

    @Test
    void rejectsMissingThreadsUnexpectedFieldsTraversalAndUnsatisfiedRanges() throws Exception {
        byte[] value = "safe".getBytes(StandardCharsets.UTF_8);
        assertThat(upload("missing", "missing-boundary", List.of(
                new TestFile("safe.txt", "text/plain", value)), false).statusCode()).isEqualTo(404);

        createThread("thread-negative");
        HttpResponse<String> tokenDisposition = multipart(
                "/api/threads/thread-negative/uploads",
                "token-disposition",
                "files",
                List.of(new TestFile("token.txt", "text/plain", value)),
                true,
                false);
        assertThat(tokenDisposition.statusCode()).isEqualTo(200);

        HttpResponse<String> wrongField = multipart(
                "/api/threads/thread-negative/uploads",
                "wrong-field",
                "file",
                List.of(new TestFile("safe.txt", "text/plain", value)),
                false,
                true);
        assertThat(wrongField.statusCode()).isEqualTo(422);
        assertThat(json.readTree(wrongField.body()).path("detail").asText())
                .contains("Unexpected multipart field");

        List<TestFile> tooMany = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            tooMany.add(new TestFile("file-" + index + ".txt", "text/plain", value));
        }
        assertThat(upload("thread-negative", "too-many", tooMany, false).statusCode()).isEqualTo(413);

        HttpResponse<String> uploaded = upload(
                "thread-negative", "valid", List.of(new TestFile("safe.txt", "text/plain", value)), false);
        assertThat(uploaded.statusCode()).isEqualTo(200);
        String artifact = json.readTree(uploaded.body()).path("files").get(0).path("artifact_url").asText();

        assertUnsatisfied(artifact, "bytes=9-10", value.length);
        assertUnsatisfied(artifact, "bytes=1-2,3-4", value.length);
        assertUnsatisfied(artifact, "bytes=3-1", value.length);
        assertUnsatisfied(artifact, "items=0-1", value.length);

        assertThat(getBytes(
                "/api/threads/thread-negative/artifacts/mnt/user-data/uploads/%2e%2e%2Fsecret.txt", null)
                .statusCode()).isIn(404, 422);
        assertThat(getBytes(
                "/api/threads/thread-negative/artifacts/mnt/user-data/foreign/safe.txt", null)
                .statusCode()).isEqualTo(404);
        assertThat(getBytes(
                "/api/threads/missing/artifacts/mnt/user-data/uploads/safe.txt", null)
                .statusCode()).isEqualTo(404);
        assertThat(get("/api/threads/thread-negative/uploads").statusCode()).isEqualTo(405);
    }

    @Test
    void activeContentIsAlwaysAttachmentAndUploadCollisionsDoNotReplaceOriginal() throws Exception {
        createThread("thread-collision");
        byte[] original = "<html>first</html>".getBytes(StandardCharsets.UTF_8);
        byte[] changed = "<html>other</html>".getBytes(StandardCharsets.UTF_8);

        JsonNode first = json.readTree(upload(
                "thread-collision", "first", List.of(new TestFile("page.html", "text/html", original)), false)
                .body());
        JsonNode second = json.readTree(upload(
                "thread-collision", "second", List.of(new TestFile("page.html", "text/html", changed)), false)
                .body());

        String firstUrl = first.path("files").get(0).path("artifact_url").asText();
        String secondUrl = second.path("files").get(0).path("artifact_url").asText();
        assertThat(secondUrl).isNotEqualTo(firstUrl).contains("page__");
        assertThat(getBytes(firstUrl, null).body()).isEqualTo(original);
        assertThat(getBytes(firstUrl, null).headers().firstValue("content-disposition"))
                .hasValueSatisfying(value -> assertThat(value).startsWith("attachment;"));
        assertThat(getBytes(secondUrl, null).body()).isEqualTo(changed);
    }

    private void assertUnsatisfied(String path, String range, long total) throws Exception {
        HttpResponse<byte[]> response = getBytes(path, range);
        assertThat(response.statusCode()).isEqualTo(416);
        assertThat(response.headers().firstValue("content-range")).contains("bytes */" + total);
        assertThat(response.headers().firstValue("accept-ranges")).contains("bytes");
    }

    private void createThread(String threadId) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(baseUri.resolve("/api/threads"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"thread_id\":\"" + threadId + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> upload(
            String threadId, String boundary, List<TestFile> files, boolean quoteBoundary) throws Exception {
        return multipart(
                "/api/threads/" + threadId + "/uploads",
                boundary,
                "files",
                files,
                quoteBoundary,
                true);
    }

    private HttpResponse<String> multipart(
            String path,
            String boundary,
            String field,
            List<TestFile> files,
            boolean quoteBoundary,
            boolean quoteDisposition) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (TestFile file : files) {
            writeAscii(body, "--" + boundary + "\r\n");
            String name = quoteDisposition ? "\"" + field + "\"" : field;
            String filename = quoteDisposition ? "\"" + file.filename + "\"" : file.filename;
            writeAscii(body, "Content-Disposition: form-data; name=" + name
                    + "; filename=" + filename + "\r\n");
            writeAscii(body, "Content-Type: " + file.mediaType + "\r\n\r\n");
            body.writeBytes(file.content);
            writeAscii(body, "\r\n");
        }
        writeAscii(body, "--" + boundary + "--\r\n");
        String parameter = quoteBoundary ? "\"" + boundary + "\"" : boundary;
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve(path))
                        .header("Content-Type", "multipart/form-data; boundary=" + parameter)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> getBytes(String path, String range) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path)).GET();
        if (range != null) {
            request.header("Range", range);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void writeAscii(ByteArrayOutputStream output, String value) {
        output.writeBytes(value.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private record TestFile(String filename, String mediaType, byte[] content) {}
}

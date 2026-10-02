package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeerFlowTrashApiTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

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
    void trashDocumentRestorePurgeAndEmptyLifecycle() throws Exception {
        // Initial empty trash
        JsonNode emptyListing = body(get("/api/trash/documents?limit=10&offset=0"));
        assertThat(emptyListing.path("total").asInt()).isEqualTo(0);
        assertThat(emptyListing.path("documents")).isEmpty();

        // Create Project A and Project B
        String projA = body(request("POST", "/api/projects", "{\"name\":\"Project A\"}")).path("id").asText();
        String projB = body(request("POST", "/api/projects", "{\"name\":\"Project B\"}")).path("id").asText();

        byte[] content1 = "sample content 1\n".getBytes(StandardCharsets.UTF_8);
        byte[] content2 = "sample content 2\n".getBytes(StandardCharsets.UTF_8);

        String doc1Id = body(multipart(projA, "file", "doc1.txt", content1)).path("document").path("id").asText();
        String doc2Id = body(multipart(projA, "file", "doc2.txt", content2)).path("document").path("id").asText();

        // Soft delete doc1
        assertThat(request("DELETE", "/api/projects/" + projA + "/documents/" + doc1Id, null).statusCode()).isEqualTo(204);

        // Verify doc1 is in trash with origin metadata
        JsonNode trashAfterDelete = body(get("/api/trash/documents"));
        assertThat(trashAfterDelete.path("total").asInt()).isEqualTo(1);
        JsonNode trashedDoc1 = trashAfterDelete.path("documents").get(0);
        assertThat(trashedDoc1.path("id").asText()).isEqualTo(doc1Id);
        assertThat(trashedDoc1.path("name").asText()).isEqualTo("doc1.txt");
        assertThat(trashedDoc1.path("trash_origin").path("project_id").asText()).isEqualTo(projA);
        assertThat(trashedDoc1.path("trash_origin").path("project_name").asText()).isEqualTo("Project A");

        // Restore doc1 without body (defaults to origin project A)
        HttpResponse<String> restore1 = request("POST", "/api/trash/documents/" + doc1Id + "/restore", null);
        assertThat(restore1.statusCode()).isEqualTo(200);
        JsonNode restore1Body = body(restore1);
        assertThat(restore1Body.path("outcome").asText()).isEqualTo("restored");
        assertThat(restore1Body.path("document").path("id").asText()).isEqualTo(doc1Id);
        assertThat(body(get("/api/trash/documents")).path("total").asInt()).isEqualTo(0);
        assertThat(body(get("/api/projects/" + projA + "/documents")).path("total").asInt()).isEqualTo(2);

        // Upload same bytes content1 to Project B (docB)
        String docBId = body(multipart(projB, "file", "docB.txt", content1)).path("document").path("id").asText();

        // Soft delete doc1 again from Project A
        assertThat(request("DELETE", "/api/projects/" + projA + "/documents/" + doc1Id, null).statusCode()).isEqualTo(204);

        // Restore doc1 explicitly into Project B -> MERGES because identical sha256 exists in Project B
        HttpResponse<String> mergeResp = request("POST", "/api/trash/documents/" + doc1Id + "/restore", """
                {"project_id":"%s"}
                """.formatted(projB));
        assertThat(mergeResp.statusCode()).isEqualTo(200);
        JsonNode mergeBody = body(mergeResp);
        assertThat(mergeBody.path("outcome").asText()).isEqualTo("merged");
        assertThat(mergeBody.path("document").path("id").asText()).isEqualTo(docBId);
        assertThat(body(get("/api/trash/documents")).path("total").asInt()).isEqualTo(0);

        // Soft delete doc2 from Project A, then delete Project A entirely
        assertThat(request("DELETE", "/api/projects/" + projA + "/documents/" + doc2Id, null).statusCode()).isEqualTo(204);
        assertThat(request("DELETE", "/api/projects/" + projA, null).statusCode()).isEqualTo(204);

        // Restoring doc2 without target should fail 404 because origin project A is deleted
        assertThat(request("POST", "/api/trash/documents/" + doc2Id + "/restore", null).statusCode()).isEqualTo(404);

        // Restoring doc2 targeting Project B succeeds
        HttpResponse<String> restore2 = request("POST", "/api/trash/documents/" + doc2Id + "/restore", """
                {"project_id":"%s"}
                """.formatted(projB));
        assertThat(restore2.statusCode()).isEqualTo(200);
        assertThat(body(restore2).path("outcome").asText()).isEqualTo("restored");

        // Soft delete doc2 from Project B, then test purge
        assertThat(request("DELETE", "/api/projects/" + projB + "/documents/" + doc2Id, null).statusCode()).isEqualTo(204);
        assertThat(body(get("/api/trash/documents")).path("total").asInt()).isEqualTo(1);

        // Purge single document
        HttpResponse<String> purgeResp = request("POST", "/api/trash/documents/" + doc2Id + "/purge", null);
        assertThat(purgeResp.statusCode()).isEqualTo(204);
        assertThat(body(get("/api/trash/documents")).path("total").asInt()).isEqualTo(0);
        assertThat(request("POST", "/api/trash/documents/" + doc2Id + "/purge", null).statusCode()).isEqualTo(404);

        // Upload docX and docY to Project B, soft delete both, and test empty trash
        String docX = body(multipart(projB, "file", "docX.txt", "x".getBytes(StandardCharsets.UTF_8)))
                .path("document").path("id").asText();
        String docY = body(multipart(projB, "file", "docY.txt", "y".getBytes(StandardCharsets.UTF_8)))
                .path("document").path("id").asText();
        request("DELETE", "/api/projects/" + projB + "/documents/" + docX, null);
        request("DELETE", "/api/projects/" + projB + "/documents/" + docY, null);
        assertThat(body(get("/api/trash/documents")).path("total").asInt()).isEqualTo(2);

        // Empty trash
        HttpResponse<String> emptyResp = request("POST", "/api/trash/purge", null);
        assertThat(emptyResp.statusCode()).isEqualTo(200);
        assertThat(body(emptyResp).path("purged").asInt()).isEqualTo(2);
        assertThat(body(get("/api/trash/documents")).path("total").asInt()).isEqualTo(0);
    }

    @Test
    void restoreRejectsArchivedTargetAndIntegrityMismatch() throws Exception {
        String projId = body(request("POST", "/api/projects", "{\"name\":\"Target\"}")).path("id").asText();
        byte[] bytes = "original payload\n".getBytes(StandardCharsets.UTF_8);
        JsonNode upload = body(multipart(projId, "file", "test.txt", bytes));
        String docId = upload.path("document").path("id").asText();
        String sha256 = upload.path("document").path("sha256").asText();

        // Move to trash
        request("DELETE", "/api/projects/" + projId + "/documents/" + docId, null);

        // Archive project
        request("POST", "/api/projects/" + projId + "/archive", "{}");

        // Target project is archived -> 404
        HttpResponse<String> blockedRestore = request("POST", "/api/trash/documents/" + docId + "/restore", """
                {"project_id":"%s"}
                """.formatted(projId));
        assertThat(blockedRestore.statusCode()).isEqualTo(404);

        // Unarchive project
        request("POST", "/api/projects/" + projId + "/restore", "{}");

        // Tamper with trash payload file to cause 409 content_missing
        Path trashPayload = dataDirectory.resolve("workspace").resolve("projects")
                .getParent().resolve("trash").resolve("payloads")
                .resolve(sha256);
        Files.writeString(trashPayload, "corrupted payload", StandardOpenOption.TRUNCATE_EXISTING);

        HttpResponse<String> conflictRestore = request("POST", "/api/trash/documents/" + docId + "/restore", """
                {"project_id":"%s"}
                """.formatted(projId));
        assertThat(conflictRestore.statusCode()).isEqualTo(409);
        assertThat(json.readTree(conflictRestore.body()).path("detail").asText()).isEqualTo("content_missing");

        // Document remains in trash
        assertThat(body(get("/api/trash/documents")).path("total").asInt()).isEqualTo(1);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> multipart(String projectId, String field, String filename, byte[] content)
            throws Exception {
        String boundary = "w04-trash-boundary";
        byte[] prefix = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
        byte[] body = new byte[prefix.length + content.length + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(content, 0, body, prefix.length, content.length);
        System.arraycopy(suffix, 0, body, prefix.length + content.length, suffix.length);
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/projects/" + projectId + "/documents"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> request(String method, String path, String payload) throws Exception {
        HttpRequest.BodyPublisher body = payload == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(payload);
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .header("Content-Type", "application/json")
                .method(method, body)
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isBetween(200, 299);
        return json.readTree(response.body());
    }
}

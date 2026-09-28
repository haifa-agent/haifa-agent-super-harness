package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeerFlowProjectApiTest {
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
    void frozenProjectCrudAndProjectThreadListSharePersistentAuthorities() throws Exception {
        JsonNode config = body(get("/api/projects/config"));
        assertThat(config.path("instructions_max_bytes").asInt()).isEqualTo(8192);
        assertThat(config.path("trash_retention_days").asInt()).isEqualTo(30);

        HttpResponse<String> create = request("POST", "/api/projects", """
                {"name":"Alpha","instructions":"Be concise","presentation":{"icon":"folder","color":"blue"}}
                """);
        assertThat(create.statusCode()).isEqualTo(201);
        JsonNode project = body(create);
        String id = project.path("id").asText();
        assertThat(id).matches("[0-9a-f]{32}");
        assertThat(project.path("status").asText()).isEqualTo("active");
        assertThat(project.path("created_at").asText()).isNotBlank();

        JsonNode listing = body(get("/api/projects?status=active"));
        assertThat(listing.path("projects")).hasSize(1);
        assertThat(body(get("/api/projects?status=archived")).path("projects")).isEmpty();
        assertThat(body(get("/api/projects/" + id)).path("name").asText()).isEqualTo("Alpha");

        JsonNode patched = body(request("PATCH", "/api/projects/" + id, """
                {"name":"Renamed","presentation":{"color":"green"}}
                """));
        assertThat(patched.path("name").asText()).isEqualTo("Renamed");
        assertThat(patched.path("instructions").asText()).isEqualTo("Be concise");
        assertThat(patched.path("presentation").fieldNames()).toIterable().containsExactly("color");

        HttpResponse<String> thread = request("POST", "/api/threads", """
                {"thread_id":"project-thread","project_id":"%s","metadata":{"title":"Project chat","deerflow_project_id":"forged"}}
                """.formatted(id));
        assertThat(thread.statusCode()).isEqualTo(200);
        assertThat(body(thread).path("metadata").path("deerflow_project_id").asText()).isEqualTo(id);
        assertThat(body(thread).path("values").path("title").asText()).isEqualTo("Project chat");

        JsonNode projectThreads = body(get("/api/projects/" + id + "/threads?limit=100&offset=0"));
        assertThat(projectThreads).hasSize(1);
        assertThat(projectThreads.get(0).path("thread_id").asText()).isEqualTo("project-thread");
        assertThat(projectThreads.get(0).path("display_name").asText()).isEqualTo("Project chat");
        assertThat(projectThreads.get(0).has("status")).isFalse();

        api.close();
        api = DeerFlowBootstrapApi.createForTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory,
                ignored -> LocalProbeModelTestSupport.binding());
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
        assertThat(body(get("/api/projects/" + id)).path("name").asText()).isEqualTo("Renamed");
    }

    @Test
    void projectRoutesFailClosedForInvalidInputMissingProjectsAndUnimplementedOperations() throws Exception {
        assertThat(request("POST", "/api/threads", """
                {"thread_id":"missing-project","project_id":"missing","metadata":{"deerflow_project_id":"missing"}}
                """).statusCode()).isEqualTo(404);
        assertThat(get("/api/projects/missing").statusCode()).isEqualTo(404);
        assertThat(get("/api/projects?status=deleted").statusCode()).isEqualTo(422);
        assertThat(request("POST", "/api/projects", "{\"name\":\"\",\"instructions\":\"\"}")
                .statusCode()).isEqualTo(422);
        assertThat(request("POST", "/api/projects", """
                {"name":"bad","presentation":{"unexpected":"value"}}
                """).statusCode()).isEqualTo(422);
        assertThat(request("POST", "/api/projects", """
                {"name":"bad","instructions":null,"presentation":null}
                """).statusCode()).isEqualTo(422);
        assertThat(request("POST", "/api/projects", """
                {"name":"large","instructions":"%s"}
                """.formatted("界".repeat(2731))).statusCode()).isEqualTo(422);

        String id = body(request("POST", "/api/projects", "{\"name\":\"Kept\"}"))
                .path("id").asText();
        assertThat(request("PATCH", "/api/projects/" + id, "{\"name\":42}").statusCode())
                .isEqualTo(422);
        HttpResponse<String> archived = request("POST", "/api/projects/" + id + "/archive", "{}");
        assertThat(archived.statusCode()).isEqualTo(200);
        assertThat(body(archived).path("status").asText()).isEqualTo("archived");

        HttpResponse<String> restored = request("POST", "/api/projects/" + id + "/restore", "{}");
        assertThat(restored.statusCode()).isEqualTo(200);
        assertThat(body(restored).path("status").asText()).isEqualTo("active");

        HttpResponse<String> deleted = request("DELETE", "/api/projects/" + id, null);
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertThat(get("/api/projects/" + id).statusCode()).isEqualTo(404);
    }

    @Test
    void frozenProjectDocumentUploadListRangeDownloadAndAttachUseExistingAuthorities() throws Exception {
        String projectId = body(request("POST", "/api/projects", "{\"name\":\"Files\"}"))
                .path("id").asText();
        assertThat(request("POST", "/api/threads", """
                {"thread_id":"document-thread","project_id":"%s","metadata":{"title":"Document chat"}}
                """.formatted(projectId)).statusCode()).isEqualTo(200);

        byte[] bytes = "hello project document\n".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> uploaded = multipart(projectId, "file", "notes.txt", bytes);
        assertThat(uploaded.statusCode()).isEqualTo(201);
        JsonNode uploadBody = body(uploaded);
        String documentId = uploadBody.path("document").path("id").asText();
        assertThat(uploadBody.path("deduplicated").asBoolean()).isFalse();
        assertThat(uploadBody.path("document").path("content_missing").asBoolean()).isFalse();
        assertThat(uploadBody.path("document").path("size_bytes").asLong()).isEqualTo(bytes.length);

        JsonNode listing = body(get("/api/projects/" + projectId + "/documents?limit=10&offset=0"));
        assertThat(listing.path("total").asInt()).isEqualTo(1);
        assertThat(listing.path("documents").get(0).path("id").asText()).isEqualTo(documentId);

        HttpResponse<String> range = get(
                "/api/projects/" + projectId + "/documents/" + documentId + "/content",
                "Range", "bytes=0-4");
        assertThat(range.statusCode()).isEqualTo(206);
        assertThat(range.body()).isEqualTo("hello");
        assertThat(range.headers().firstValue("Content-Range")).contains("bytes 0-4/" + bytes.length);
        assertThat(range.headers().firstValue("ETag")).contains("\"" + uploadBody.path("document").path("sha256").asText() + "\"");

        HttpResponse<String> download = get(
                "/api/projects/" + projectId + "/documents/" + documentId + "/content?download=true");
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(new String(bytes, StandardCharsets.UTF_8));
        assertThat(download.headers().firstValue("Content-Disposition")).hasValueSatisfying(
                value -> assertThat(value).startsWith("attachment;").contains("notes.txt"));

        JsonNode attached = body(request("POST", "/api/projects/" + projectId + "/documents/" + documentId
                + "/attach-to-thread/document-thread", null));
        assertThat(attached.path("filename").asText()).isEqualTo("notes.txt");
        assertThat(attached.path("size_bytes").asLong()).isEqualTo(bytes.length);
        assertThat(attached.path("virtual_path").asText()).isEqualTo("/mnt/user-data/uploads/notes.txt");
        HttpResponse<String> artifact = get(attached.path("artifact_url").asText(), "Range", "bytes=6-12");
        assertThat(artifact.statusCode()).isEqualTo(206);
        assertThat(artifact.body()).isEqualTo("project");

        HttpResponse<String> duplicate = multipart(projectId, "file", "renamed.txt", bytes);
        assertThat(duplicate.statusCode()).isEqualTo(200);
        assertThat(body(duplicate).path("document").path("name").asText()).isEqualTo("notes.txt");
    }

    @Test
    void projectDocumentTransportFailsClosedForMalformedMultipartRangeAndCrossAuthority() throws Exception {
        String projectId = body(request("POST", "/api/projects", "{\"name\":\"Files\"}"))
                .path("id").asText();
        byte[] bytes = "bounded".getBytes(StandardCharsets.UTF_8);
        assertThat(multipart(projectId, "files", "wrong.txt", bytes).statusCode()).isEqualTo(422);
        String documentId = body(multipart(projectId, "file", "safe.txt", bytes))
                .path("document").path("id").asText();

        HttpResponse<String> multiRange = get(
                "/api/projects/" + projectId + "/documents/" + documentId + "/content",
                "Range", "bytes=0-1,3-4");
        assertThat(multiRange.statusCode()).isEqualTo(416);
        assertThat(multiRange.headers().firstValue("Content-Range")).contains("bytes */" + bytes.length);
        assertThat(request("POST", "/api/projects/" + projectId + "/documents/" + documentId
                + "/attach-to-thread/missing", null).statusCode()).isEqualTo(404);
        assertThat(get("/api/projects/" + projectId + "/documents/missing/content").statusCode()).isEqualTo(404);

        String otherProject = body(request("POST", "/api/projects", "{\"name\":\"Other\"}"))
                .path("id").asText();
        assertThat(request("POST", "/api/threads", """
                {"thread_id":"other-project-thread","project_id":"%s"}
                """.formatted(otherProject)).statusCode()).isEqualTo(200);
        assertThat(request("POST", "/api/projects/" + projectId + "/documents/" + documentId
                + "/attach-to-thread/other-project-thread", null).statusCode()).isEqualTo(404);

        // Frozen project shelf: a zero-byte upload is 400, not the generic 422.
        HttpResponse<String> emptyUpload = multipart(projectId, "file", "empty.txt", new byte[0]);
        assertThat(emptyUpload.statusCode()).isEqualTo(400);
        assertThat(emptyUpload.body()).contains("must not be empty");

        // Frozen project shelf: an unusable display/upload name is 400, not the generic 422.
        HttpResponse<String> unsafeName = multipart(projectId, "file", "../escape.txt", bytes);
        assertThat(unsafeName.statusCode()).isEqualTo(400);
        assertThat(unsafeName.body()).contains("plain filename");
        assertThat(multipart(projectId, "file", "", bytes).statusCode()).isEqualTo(400);

        // Frozen project shelf: an over-limit upload is 413, not the generic 422.
        assertThat(multipart(projectId, "file", "big.txt", new byte[25 * 1024 * 1024 + 1]).statusCode())
                .isEqualTo(413);

        // A missing project stays 404 and no failed upload is visible on the shelf.
        assertThat(multipart("missing", "file", "safe.txt", bytes).statusCode()).isEqualTo(404);
        assertThat(body(get("/api/projects/" + projectId + "/documents")).path("total").asInt()).isEqualTo(1);
    }

    @Test
    void projectArchiveRestoreDeleteAndDocumentTrashLifecycle() throws Exception {
        HttpResponse<String> create = request("POST", "/api/projects", """
                {"name":"LifeCycle","instructions":"Test project"}
                """);
        String projectId = body(create).path("id").asText();

        // Upload doc1
        byte[] bytes1 = "doc1 content\n".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> uploaded1 = multipart(projectId, "file", "doc1.txt", bytes1);
        String doc1Id = body(uploaded1).path("document").path("id").asText();

        // Delete doc1 (soft delete)
        HttpResponse<String> deleteDoc1 = request("DELETE", "/api/projects/" + projectId + "/documents/" + doc1Id, null);
        assertThat(deleteDoc1.statusCode()).isEqualTo(204);
        JsonNode docsAfterDelete = body(get("/api/projects/" + projectId + "/documents"));
        assertThat(docsAfterDelete.path("total").asInt()).isEqualTo(0);

        // Upload doc2
        byte[] bytes2 = "doc2 content\n".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> uploaded2 = multipart(projectId, "file", "doc2.txt", bytes2);
        String doc2Id = body(uploaded2).path("document").path("id").asText();
        assertThat(body(get("/api/projects/" + projectId + "/documents")).path("total").asInt()).isEqualTo(1);

        // Archive project
        HttpResponse<String> archiveResp = request("POST", "/api/projects/" + projectId + "/archive", "{}");
        assertThat(archiveResp.statusCode()).isEqualTo(200);
        assertThat(body(archiveResp).path("status").asText()).isEqualTo("archived");

        // Archived project rejects document upload and trash (fail-closed 404)
        HttpResponse<String> uploadArchived = multipart(projectId, "file", "doc3.txt", bytes("blocked\n"));
        assertThat(uploadArchived.statusCode()).isEqualTo(404);
        HttpResponse<String> trashArchived = request("DELETE", "/api/projects/" + projectId + "/documents/" + doc2Id, null);
        assertThat(trashArchived.statusCode()).isEqualTo(404);

        // Restore project
        HttpResponse<String> restoreResp = request("POST", "/api/projects/" + projectId + "/restore", "{}");
        assertThat(restoreResp.statusCode()).isEqualTo(200);
        assertThat(body(restoreResp).path("status").asText()).isEqualTo("active");

        // Bind thread to project
        HttpResponse<String> threadResp = request("POST", "/api/threads", """
                {"thread_id":"w04-test-thread","project_id":"%s","metadata":{"title":"W04 Thread"}}
                """.formatted(projectId));
        assertThat(threadResp.statusCode()).isEqualTo(200);
        assertThat(body(get("/api/projects/" + projectId + "/threads?limit=10")).has(0)).isTrue();

        // Delete project
        HttpResponse<String> deleteProj = request("DELETE", "/api/projects/" + projectId, null);
        assertThat(deleteProj.statusCode()).isEqualTo(204);
        assertThat(get("/api/projects/" + projectId).statusCode()).isEqualTo(404);

        // Verify thread has deerflow_project_id unlinked
        JsonNode threadAfter = body(get("/api/threads/w04-test-thread"));
        assertThat(threadAfter.path("metadata").has("deerflow_project_id")).isFalse();

        // Verify doc1 and doc2 are both in trash
        JsonNode trashList = body(get("/api/trash/documents"));
        assertThat(trashList.path("total").asInt()).isEqualTo(2);
        assertThat(trashList.path("documents"))
                .extracting(node -> node.path("id").asText())
                .containsExactlyInAnyOrder(doc1Id, doc2Id);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String header, String value) throws Exception {
        return client.send(HttpRequest.newBuilder(baseUri.resolve(path)).header(header, value).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> multipart(String projectId, String field, String filename, byte[] content)
            throws Exception {
        String boundary = "w04-boundary";
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
                .header("X-User-Id", "attacker")
                .method(method, body)
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isBetween(200, 299);
        return json.readTree(response.body());
    }
}

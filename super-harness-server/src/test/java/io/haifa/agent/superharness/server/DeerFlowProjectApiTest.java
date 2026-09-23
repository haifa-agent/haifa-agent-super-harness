package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
        assertThat(body(get("/api/projects/" + id)).path("name").asText()).isEqualTo("Kept");
        assertThat(request("POST", "/api/projects/" + id + "/archive", "{}").statusCode()).isEqualTo(404);
        assertThat(request("DELETE", "/api/projects/" + id, null).statusCode()).isEqualTo(405);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
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

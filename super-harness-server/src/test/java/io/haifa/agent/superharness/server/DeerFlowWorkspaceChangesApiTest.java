package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelUsage;
import java.io.InputStream;
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
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeerFlowWorkspaceChangesApiTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @TempDir
    Path dataDirectory;

    private DeerFlowBootstrapApi api;
    private URI baseUri;

    @BeforeEach
    void startServer() throws Exception {
        startWithBinding(LocalProbeModelTestSupport.binding(request -> {
            try {
                Path userData = onlyUserData();
                Files.writeString(userData.resolve("outputs/race.txt"), "first\nsecond\n");
                Files.write(userData.resolve("outputs/image.png"), new byte[] {0, 1, 2, (byte) 0xff});
                Files.write(userData.resolve("outputs/extensionless"), new byte[] {0, 1, 2, 3});
                Files.write(userData.resolve("outputs/large.txt"), new byte[1024 * 1024 + 1]);
                Files.writeString(userData.resolve("workspace/.env"), "TOKEN=do-not-expose\n");
                Files.createDirectories(userData.resolve("workspace/secrets"));
                Files.writeString(userData.resolve("workspace/secrets/config.json"),
                        "{\"value\":\"nested-do-not-expose\"}\n");
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            return new AgentChatResponse(
                    "workspace-change-response",
                    request.model().providerModelId(),
                    "workspace changed",
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(1, 1),
                    "",
                    Map.of());
        }));
    }

    @AfterEach
    void stopServer() {
        if (api != null) api.close();
    }

    @Test
    void baselinePrecedesImmediateModelWriteAndFrozenProjectionPersistsAcrossRestart() throws Exception {
        createThread("thread-changes");
        HttpResponse<String> run = client.send(runRequest("thread-changes", "write immediately"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(run.statusCode()).isEqualTo(200);
        String location = run.headers().firstValue("content-location").orElseThrow();
        String runId = location.substring(location.lastIndexOf('/') + 1);
        String changesPath = location + "/workspace-changes?include_files=true&include_diff=true";

        JsonNode changes = successfulGet(changesPath);
        assertThat(changes.path("available").asBoolean()).isTrue();
        assertThat(changes.path("version").asInt()).isEqualTo(1);
        assertThat(changes.path("summary").path("created").asInt()).isEqualTo(6);
        assertThat(changes.path("summary").path("truncated").asBoolean()).isTrue();
        assertThat(changes.path("limits").path("max_file_bytes_for_diff").asInt())
                .isEqualTo(1024 * 1024);

        JsonNode text = file(changes, "/mnt/user-data/outputs/race.txt");
        assertThat(text.path("root").asText()).isEqualTo("outputs");
        assertThat(text.path("status").asText()).isEqualTo("created");
        assertThat(text.path("diff").asText())
                .contains("+++ b/mnt/user-data/outputs/race.txt", "+first", "+second");
        assertThat(text.path("additions").asInt()).isEqualTo(2);
        assertThat(text.path("sha256_after").asText()).matches("[0-9a-f]{64}");

        JsonNode binary = file(changes, "/mnt/user-data/outputs/image.png");
        assertThat(binary.path("binary").asBoolean()).isTrue();
        assertThat(binary.path("diff_unavailable_reason").asText()).isEqualTo("binary");
        assertThat(binary.path("diff").asText()).isEmpty();
        JsonNode extensionless = file(changes, "/mnt/user-data/outputs/extensionless");
        assertThat(extensionless.path("binary").asBoolean()).isTrue();
        assertThat(extensionless.path("diff_unavailable_reason").asText()).isEqualTo("binary");
        JsonNode large = file(changes, "/mnt/user-data/outputs/large.txt");
        assertThat(large.path("diff_unavailable_reason").asText()).isEqualTo("large");
        JsonNode sensitive = file(changes, "/mnt/user-data/workspace/.env");
        assertThat(sensitive.path("sensitive").asBoolean()).isTrue();
        assertThat(sensitive.path("diff_unavailable_reason").asText()).isEqualTo("sensitive");
        assertThat(sensitive.path("sha256_before").isNull()).isTrue();
        assertThat(sensitive.path("sha256_after").isNull()).isTrue();
        JsonNode nestedSensitive = file(changes, "/mnt/user-data/workspace/secrets/config.json");
        assertThat(nestedSensitive.path("sensitive").asBoolean()).isTrue();
        assertThat(nestedSensitive.path("diff_unavailable_reason").asText()).isEqualTo("sensitive");
        assertThat(nestedSensitive.path("sha256_after").isNull()).isTrue();
        assertThat(changes.toString()).doesNotContain("do-not-expose", "nested-do-not-expose");

        JsonNode withoutDiff = successfulGet(
                location + "/workspace-changes?include_files=true&include_diff=false");
        assertThat(file(withoutDiff, "/mnt/user-data/outputs/race.txt").path("diff").asText()).isEmpty();
        assertThat(file(withoutDiff, "/mnt/user-data/outputs/race.txt").path("additions").asInt()).isEqualTo(2);
        JsonNode summaryOnly = successfulGet(
                location + "/workspace-changes?include_files=false&include_diff=true");
        assertThat(summaryOnly.path("files")).isEmpty();
        assertThat(summaryOnly.path("summary")).isEqualTo(changes.path("summary"));

        assertThat(get("/api/threads/other/runs/" + runId + "/workspace-changes").statusCode()).isEqualTo(404);
        assertThat(get("/api/threads/thread-changes/runs/missing/workspace-changes").statusCode()).isEqualTo(404);
        assertThat(get(location + "/workspace-changes?include_diff=maybe").statusCode()).isEqualTo(422);
        assertThat(post(location + "/workspace-changes", "{}").statusCode()).isEqualTo(405);

        api.close();
        startWithBinding(LocalProbeModelTestSupport.binding());
        JsonNode restarted = successfulGet(changesPath);
        assertThat(restarted.path("available").asBoolean()).isTrue();
        assertThat(restarted.path("summary").path("created").asInt()).isEqualTo(6);
        assertThat(file(restarted, "/mnt/user-data/outputs/race.txt").path("sha256_after").asText())
                .isEqualTo(text.path("sha256_after").asText());
    }

    @Test
    void activeRunHasFrozenUnavailableShapeAndStartFailureLeavesNoStage() throws Exception {
        api.close();
        startWithBinding(LocalProbeModelTestSupport.binding());
        createThread("thread-active");
        HttpResponse<InputStream> active = client.send(
                runRequest("thread-active", "__slow__"), HttpResponse.BodyHandlers.ofInputStream());
        String location = active.headers().firstValue("content-location").orElseThrow();
        JsonNode unavailable = successfulGet(location + "/workspace-changes");
        assertThat(unavailable).isEqualTo(json.readTree("""
                {"available":false,"version":1,"summary":{"created":0,"modified":0,"deleted":0,"symlink_created":0,"additions":0,"deletions":0,"truncated":false},"files":[],"limits":{}}
                """));
        active.body().close();
        post(location + "/cancel?wait=false&action=interrupt", "");

        createThread("thread-start-failure");
        String tooLong = "x".repeat(32_001);
        HttpResponse<String> failed = post("/api/threads/thread-start-failure/runs/stream", runBody(tooLong));
        assertThat(failed.statusCode()).isEqualTo(500);
        try (var paths = Files.list(dataDirectory.resolve("workspace/workspace-changes"))) {
            assertThat(paths.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.startsWith(".baseline-"));
        }
    }

    @Test
    void completedRunWithoutChangesKeepsFrozenUnavailableShape() throws Exception {
        api.close();
        startWithBinding(LocalProbeModelTestSupport.binding());
        createThread("thread-unchanged");
        HttpResponse<String> run = client.send(runRequest("thread-unchanged", "leave files unchanged"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(run.statusCode()).isEqualTo(200);
        String location = run.headers().firstValue("content-location").orElseThrow();

        JsonNode unavailable = successfulGet(location + "/workspace-changes");
        assertThat(unavailable).isEqualTo(json.readTree("""
                {"available":false,"version":1,"summary":{"created":0,"modified":0,"deleted":0,"symlink_created":0,"additions":0,"deletions":0,"truncated":false},"files":[],"limits":{}}
                """));
    }

    @Test
    void continueDisconnectCompletesInBackgroundAndSurvivesRestartWithoutLazyRead() throws Exception {
        createThread("thread-background-completion");
        HttpResponse<InputStream> response = client.send(
                runRequest("thread-background-completion", "__continue_short__"),
                HttpResponse.BodyHandlers.ofInputStream());
        String location = response.headers().firstValue("content-location").orElseThrow();
        String runId = location.substring(location.lastIndexOf('/') + 1);
        response.body().close();

        Path changesRoot = dataDirectory.resolve("workspace/workspace-changes");
        awaitCompletedSnapshot(changesRoot, runId);
        api.close();
        startWithBinding(LocalProbeModelTestSupport.binding());

        JsonNode restarted = successfulGet(location + "/workspace-changes");
        assertThat(restarted.path("available").asBoolean()).isTrue();
        assertThat(restarted.toString()).doesNotContain("do-not-expose", "nested-do-not-expose");
    }

    @Test
    void legacySensitiveSnapshotIsMaskedWithoutItsPersistedPayload() throws Exception {
        createThread("thread-legacy-sensitive");
        HttpResponse<String> run = client.send(runRequest("thread-legacy-sensitive", "legacy snapshot"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(run.statusCode()).isEqualTo(200);
        assertThat(run.body()).as("SSE transcript: %s", run.body()).contains("event: end");
        String location = run.headers().firstValue("content-location").orElseThrow();
        api.close();

        Path completedBaseline = onlyCompletedBaseline();
        String legacyHash = renameLegacySnapshotPath(
                completedBaseline, "outputs/race.txt", "workspace/secrets/legacy.txt");
        Files.delete(completedBaseline.getParent().resolve("payloads").resolve(legacyHash));

        startWithBinding(LocalProbeModelTestSupport.binding());
        JsonNode changes = successfulGet(location + "/workspace-changes");
        JsonNode sensitive = file(changes, "/mnt/user-data/workspace/secrets/legacy.txt");
        assertThat(sensitive.path("sensitive").asBoolean()).isTrue();
        assertThat(sensitive.path("diff_unavailable_reason").asText()).isEqualTo("sensitive");
        assertThat(sensitive.path("sha256_before").isNull()).isTrue();
        assertThat(sensitive.path("sha256_after").isNull()).isTrue();
        assertThat(sensitive.path("diff").asText()).isEmpty();
        assertThat(changes.toString()).doesNotContain(legacyHash, "first", "second");
    }

    @Test
    void workspaceStageLimitDoesNotBlockOrChangeTheRunStream() throws Exception {
        api.close();
        startWithBinding(LocalProbeModelTestSupport.binding());
        createThread("thread-stage-overflow");
        HttpResponse<String> seed = client.send(runRequest("thread-stage-overflow", "seed workspace"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(seed.statusCode()).isEqualTo(200);
        Path crowded = onlyUserData().resolve("workspace/crowded");
        Files.createDirectories(crowded);
        for (int index = 0; index < 501; index++) {
            Files.writeString(crowded.resolve("file-" + index + ".txt"), "x");
        }

        HttpResponse<String> run = client.send(runRequest("thread-stage-overflow", "recorder must be optional"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(run.statusCode()).isEqualTo(200);
        assertThat(run.body()).contains("Haifa Agent SDK: recorder must be optional");
        String location = run.headers().firstValue("content-location").orElseThrow();
        assertThat(successfulGet(location + "/workspace-changes").path("available").asBoolean()).isFalse();
        try (var paths = Files.list(dataDirectory.resolve("workspace/workspace-changes"))) {
            assertThat(paths.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.startsWith(".baseline-"));
        }
    }

    @Test
    void apiCloseInterruptsAWorkspaceCompletionObserver() throws Exception {
        api.close();
        startWithBinding(LocalProbeModelTestSupport.binding());
        createThread("thread-observer-close");
        HttpResponse<InputStream> response = client.send(
                runRequest("thread-observer-close", "__slow__"), HttpResponse.BodyHandlers.ofInputStream());
        response.body().close();

        api.close();
        assertThat(api.threadRunApi().workspaceCompletionObserverCount()).isZero();
    }

    private void startWithBinding(HaifaRunBridge.ModelBinding binding) throws Exception {
        api = DeerFlowBootstrapApi.createForTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory,
                ignored -> binding);
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
    }

    private Path onlyUserData() {
        try (var paths = Files.walk(dataDirectory.resolve("workspace/workspaces"))) {
            return paths.filter(path -> path.getFileName().toString().equals("user-data"))
                    .findFirst()
                    .orElseThrow();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void awaitCompletedSnapshot(Path changesRoot, String runId) throws Exception {
        // Walking the unpublished .completed-* directory holds Windows directory handles and can
        // deny the recorder's atomic rename. Observe this exact Run without opening its staging tree.
        String runHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(runId.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        Path completed = changesRoot.resolve(onlyUserData().getParent().getFileName())
                .resolve("run-" + runHash).resolve("completed/baseline.properties");
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(completed)) return;
            Thread.sleep(25);
        }
        HttpResponse<String> run = get("/api/threads/thread-background-completion/runs/" + runId);
        String status = run.statusCode() == 200
                ? json.readTree(run.body()).path("status").asText()
                : "HTTP " + run.statusCode();
        long baselines;
        try (var paths = Files.walk(changesRoot)) {
            baselines = paths.filter(path -> path.getFileName().toString().equals("baseline.properties"))
                    .count();
        }
        throw new AssertionError("workspace completion observer did not persist run " + runId
                + "; run_status=" + status + "; observer_count="
                + api.threadRunApi().workspaceCompletionObserverCount()
                + "; baseline_files=" + baselines);
    }

    private Path onlyCompletedBaseline() throws Exception {
        try (var paths = Files.walk(dataDirectory.resolve("workspace/workspace-changes"))) {
            return paths.filter(path -> path.getFileName().toString().equals("baseline.properties"))
                    .filter(path -> path.getParent().getFileName().toString().equals("completed"))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private static String renameLegacySnapshotPath(Path baseline, String from, String to) throws Exception {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(baseline)) {
            properties.load(input);
        }
        String fromKey = "file." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(from.getBytes(StandardCharsets.UTF_8));
        String toKey = "file." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(to.getBytes(StandardCharsets.UTF_8));
        String legacyValue = properties.remove(fromKey).toString();
        assertThat(legacyValue).matches("\\d+:[0-9a-f]{64}");
        properties.setProperty(toKey, legacyValue);
        try (var output = Files.newOutputStream(baseline, StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(output, "legacy sensitive snapshot fixture");
        }
        return legacyValue.substring(legacyValue.indexOf(':') + 1);
    }

    private void createThread(String threadId) throws Exception {
        assertThat(post("/api/threads", "{\"thread_id\":\"" + threadId + "\"}").statusCode()).isEqualTo(200);
    }

    private HttpRequest runRequest(String threadId, String message) throws Exception {
        return HttpRequest.newBuilder(baseUri.resolve("/api/threads/" + threadId + "/runs/stream"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(runBody(message)))
                .build();
    }

    private String runBody(String message) throws Exception {
        return json.writeValueAsString(Map.of(
                "assistant_id", "lead_agent",
                "input", Map.of("messages", List.of(Map.of("id", "human-1", "type", "human", "content", message))),
                "stream_mode", List.of("messages-tuple", "updates", "custom"),
                "on_disconnect", "continue",
                "multitask_strategy", "reject"));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode successfulGet(String path) throws Exception {
        HttpResponse<String> response = get(path);
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(baseUri.resolve(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode file(JsonNode response, String path) {
        for (JsonNode file : response.path("files")) {
            if (file.path("path").asText().equals(path)) return file;
        }
        throw new AssertionError("missing workspace change " + path + ": " + response);
    }
}

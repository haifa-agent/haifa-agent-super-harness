package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Frozen {@code DELETE /api/threads/{id}} (and the rewritten
 * {@code DELETE /api/langgraph/threads/{id}}) must retire a thread: it leaves
 * {@code /api/threads/search} and every read returns 404, idempotently and across a restart.
 */
class ThreadDeleteHttpTest {
    private Path dataRoot;
    private Path evidence;
    private GatewayFixture.HttpEvidence http;
    private Map<String, String> environment;

    @BeforeEach
    void prepare() throws Exception {
        dataRoot = GatewayFixture.dataDirectory();
        evidence = GatewayFixture.evidenceDirectory();
        http = new GatewayFixture.HttpEvidence(evidence, Duration.ofSeconds(40));
        environment = Map.of(
                "HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://THREAD_DELETE_KEY",
                "THREAD_DELETE_KEY", Base64.getEncoder().encodeToString(new byte[32]));
    }

    @AfterEach
    void close() throws InterruptedException {
        if (http != null) {
            http.close();
        }
    }

    @Test
    void deleteRetiresThreadFromSearchAndReadsAndSurvivesRestart() throws Exception {
        Files.createDirectories(evidence);
        String threadId = "thread-delete-1";
        String createBody = "{\"thread_id\":\"" + threadId + "\"}";

        try (var api = GatewayFixture.production(dataRoot, 0, environment, evidence)) {
            URI base = api.base;
            assertThat(http.request(base, "POST", "/api/threads", createBody).statusCode()).isEqualTo(200);
            assertThat(http.request(base, "GET", "/api/threads/" + threadId, null).statusCode()).isEqualTo(200);
            assertThat(http.request(base, "POST", "/api/threads/search", "{\"limit\":50}").body())
                    .contains(threadId);

            var deleted = http.request(base, "DELETE", "/api/threads/" + threadId, null);
            assertThat(deleted.statusCode()).isEqualTo(200);
            assertThat(deleted.body()).contains("\"success\":true");

            assertThat(http.request(base, "GET", "/api/threads/" + threadId, null).statusCode()).isEqualTo(404);
            assertThat(http.request(base, "GET", "/api/threads/" + threadId + "/state", null).statusCode())
                    .isEqualTo(404);
            assertThat(http.request(base, "POST", "/api/threads/search", "{\"limit\":50}").body())
                    .doesNotContain(threadId);
            // The frozen frontend issues the cleanup twice; the second attempt must be idempotent.
            assertThat(http.request(base, "DELETE", "/api/threads/" + threadId, null).statusCode()).isEqualTo(404);
        }

        try (var api = GatewayFixture.production(dataRoot, 0, environment, evidence)) {
            assertThat(http.request(api.base, "GET", "/api/threads/" + threadId, null).statusCode()).isEqualTo(404);
            assertThat(http.request(api.base, "POST", "/api/threads/search", "{\"limit\":50}").body())
                    .doesNotContain(threadId);
        }
    }

    @Test
    void deleteReportsMissingThreadAsNotFound() throws Exception {
        Files.createDirectories(evidence);
        try (var api = GatewayFixture.production(dataRoot, 0, environment, evidence)) {
            assertThat(http.request(api.base, "DELETE", "/api/threads/absent-thread", null).statusCode())
                    .isEqualTo(404);
        }
    }
}

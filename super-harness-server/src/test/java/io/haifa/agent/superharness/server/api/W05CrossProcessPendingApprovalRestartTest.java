package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
import io.haifa.agent.superharness.server.run.HaifaPublicInteractionSpikeTest;
import io.haifa.agent.superharness.server.run.HaifaRunBridge;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deterministic L2 proof for case V2-M4-03: a pending production Approval survives an exact
 * separate-OS-process Gateway restart. A real child JVM re-opens the same data-root and continuation
 * key, reads the pending Interaction from the authoritative SDK/SQLite facts, approves it, and the
 * approved tool executes exactly once. A missing continuation key fails closed without any replay.
 *
 * <p>This is not L3: the model is a controlled final-answer stub and no frozen UI is driven. It is
 * stronger than the in-JVM restart proof because it covers credential resolution, SQLite reopening
 * and process-local caches.
 */
class W05CrossProcessPendingApprovalRestartTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final AtomicInteger toolInvocations = new AtomicInteger();
    private final SecretKey continuationKey = randomKey();
    @TempDir
    Path dataDirectory;
    private DeerFlowBootstrapApi api;
    private URI baseUri;

    @BeforeEach
    void startServer() throws Exception {
        String externalEvidence = System.getProperty("w05.restart.evidence.dir");
        if (api == null && externalEvidence != null && !externalEvidence.isBlank()) {
            dataDirectory = Files.createDirectories(Path.of(externalEvidence)
                    .resolve("run-" + java.util.UUID.randomUUID()));
        }
        api = openApi();
    }

    @AfterEach
    void stopServer() {
        if (api != null) api.close();
    }

    @Test
    void separateProcessRestartResumesThePendingApprovalAndExecutesTheToolExactlyOnce() throws Exception {
        Pending pending = startPending("cross-process-thread", "cross-process approval");
        assertThat(toolInvocations).hasValue(0);

        api.close();
        Thread.sleep(200);

        Path marker = dataDirectory.resolve("tool-invocations.log");
        Path evidence = dataDirectory.resolve("restart-evidence.json");
        ProcessResult child = launchChild(
                pending.runId(),
                evidence,
                marker,
                Map.of(
                        DeerFlowBootstrapApi.CONTINUATION_KEY_REFERENCE_ENVIRONMENT,
                        "env://W05_PROCESS_KEY",
                        "W05_PROCESS_KEY",
                        Base64.getEncoder().encodeToString(continuationKey.getEncoded())));
        assertThat(child.exitCode()).as("child stderr: %s", child.stderr()).isZero();

        JsonNode childEvidence = json.readTree(evidence.toFile());
        assertThat(childEvidence.path("continuation_key_resolved").asBoolean()).isTrue();
        assertThat(childEvidence.path("run_id").asText()).isEqualTo(pending.runId());
        assertThat(childEvidence.path("request_id").asText()).isEqualTo(pending.requestId());
        assertThat(childEvidence.path("interaction_state").asText()).isEqualTo("PENDING");
        assertThat(childEvidence.path("tool_call_id").asText()).isEqualTo(pending.toolCallId());
        assertThat(childEvidence.path("final_status").asText()).isEqualTo("COMPLETED");
        assertThat(childEvidence.path("tool_invocations").asLong()).isEqualTo(1);
        assertThat(markerLines(marker)).as("approved tool must execute exactly once").isEqualTo(1);

        // Re-open the Gateway on the same data-root: the run is durable and no longer pending.
        startServer();
        JsonNode run = getJson("/api/threads/cross-process-thread/runs/" + pending.runId());
        assertThat(run.path("status").asText()).isEqualTo("success");
        JsonNode runs = getJson("/api/threads/cross-process-thread/runs");
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).path("run_id").asText()).isEqualTo(pending.runId());
        System.out.println("W05_CROSS_PROCESS_EVIDENCE scenario=resume run=" + pending.runId()
                + " request=" + pending.requestId() + " child_exit=" + child.exitCode()
                + " marker_lines=" + markerLines(marker));
    }

    @Test
    void separateProcessRestartWithoutItsContinuationKeyFailsClosedAndDoesNotReplay() throws Exception {
        Pending pending = startPending("fail-closed-thread", "fail closed approval");

        api.close();
        Thread.sleep(200);

        Path marker = dataDirectory.resolve("tool-invocations.log");
        Path evidence = dataDirectory.resolve("fail-closed-evidence.json");
        // An explicit env:// reference that is absent must fail closed, never mint a new key.
        ProcessResult child = launchChild(
                pending.runId(),
                evidence,
                marker,
                Map.of(DeerFlowBootstrapApi.CONTINUATION_KEY_REFERENCE_ENVIRONMENT, "env://W05_MISSING_KEY"));
        assertThat(child.exitCode()).as("child stderr: %s", child.stderr()).isNotZero();
        assertThat(child.stderr()).contains("continuation key credential is unavailable");
        assertThat(markerLines(marker)).isZero();

        // The authoritative facts are unchanged: still pending, tool never executed.
        startServer();
        assertThat(getJson("/api/threads/fail-closed-thread/runs/" + pending.runId()).path("status").asText())
                .isEqualTo("interrupted");
        assertThat(getJson("/api/threads/fail-closed-thread/runs")).hasSize(1);
        assertThat(toolInvocations).hasValue(0);
        System.out.println("W05_CROSS_PROCESS_EVIDENCE scenario=fail-closed run=" + pending.runId()
                + " request=" + pending.requestId() + " child_exit=" + child.exitCode()
                + " marker_lines=" + markerLines(marker));
    }

    private DeerFlowBootstrapApi openApi() throws Exception {
        var binding = new HaifaRunBridge.ModelBinding(
                "interaction-probe",
                "1.0.0",
                new HaifaPublicInteractionSpikeTest.ApprovalProbeModel(),
                HaifaPublicInteractionSpikeTest.modelSnapshot());
        DeerFlowBootstrapApi created = DeerFlowBootstrapApi.createForInteractionTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory,
                continuationKey,
                ignored -> binding,
                List.of(new HaifaPublicInteractionSpikeTest.MutatingProbeTool(toolInvocations)));
        created.start();
        api = created;
        baseUri = URI.create("http://127.0.0.1:" + created.address().getPort());
        return created;
    }

    private Pending startPending(String threadId, String prompt) throws Exception {
        assertThat(postJson("/api/threads", "{\"thread_id\":\"" + threadId + "\"}").statusCode())
                .isEqualTo(200);
        HttpResponse<String> requested =
                postJson("/api/threads/" + threadId + "/runs/stream", ordinaryRunBody(prompt));
        assertThat(requested.statusCode()).as(requested.body()).isEqualTo(200);
        String location = requested.headers().firstValue("content-location").orElseThrow();
        JsonNode card = humanInputCard(requested.body());
        return new Pending(
                location.substring(location.lastIndexOf('/') + 1),
                card.path("artifact").path("human_input").path("request_id").asText(),
                card.path("artifact").path("human_input").path("tool_call_id").asText());
    }

    private long markerLines(Path marker) throws IOException {
        return Files.exists(marker) ? Files.readAllLines(marker).size() : 0L;
    }

    private ProcessResult launchChild(
            String runId, Path evidence, Path marker, Map<String, String> environment)
            throws IOException, InterruptedException {
        String javaHome = System.getProperty("java.home");
        String javaExecutable = javaHome + (isWindows() ? "\\bin\\java.exe" : "/bin/java");
        Path argumentFile = dataDirectory.resolve("java-args.txt");
        List<String> lines = new ArrayList<>();
        lines.add("-cp");
        lines.add(quote(System.getProperty("java.class.path").replace('\\', '/')));
        lines.add(W05RestartResumeProcess.class.getName());
        lines.add(quote(dataDirectory.toAbsolutePath().normalize().toString().replace('\\', '/')));
        lines.add(quote(runId));
        lines.add(quote(evidence.toAbsolutePath().normalize().toString().replace('\\', '/')));
        lines.add(quote(marker.toAbsolutePath().normalize().toString().replace('\\', '/')));
        Files.write(
                argumentFile,
                lines,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);

        ProcessBuilder builder = new ProcessBuilder(javaExecutable, "@" + argumentFile.toString());
        builder.environment().putAll(environment);
        builder.redirectErrorStream(true);
        Path childLog = evidence.resolveSibling(evidence.getFileName() + ".child.log");
        builder.redirectOutput(childLog.toFile());
        Process process = builder.start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("child restart process timed out; original log: " + childLog);
        }
        String output = Files.readString(childLog, StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), output);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private static SecretKey randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new SecretKeySpec(key, "AES");
    }

    private String ordinaryRunBody(String prompt) {
        return """
                {"assistant_id":"lead_agent","input":{"messages":[{"id":"human-1","type":"human","content":[{"type":"text","text":"%s"}],"additional_kwargs":{}}]},"stream_mode":["messages-tuple","updates","custom"],"on_disconnect":"continue","multitask_strategy":"reject"}
                """
                .formatted(prompt);
    }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private JsonNode humanInputCard(String transcript) throws Exception {
        Matcher matcher = Pattern.compile("event: messages\\ndata: ([^\\n]+)\\n").matcher(transcript);
        while (matcher.find()) {
            JsonNode tuple = json.readTree(matcher.group(1));
            if (tuple.isArray() && !tuple.isEmpty()) {
                JsonNode message = tuple.get(0);
                if (message.path("artifact").path("human_input").hasNonNull("request_id")) {
                    return message;
                }
            }
        }
        throw new AssertionError("missing human input card: " + transcript);
    }

    private record Pending(String runId, String requestId, String toolCallId) {}

    private record ProcessResult(int exitCode, String stderr) {}
}

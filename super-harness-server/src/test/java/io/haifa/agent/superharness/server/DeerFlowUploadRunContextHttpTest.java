package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * W04 V2 file context: frozen-UI upload -> saved original -> Thread reference -> Run reads the saved
 * bytes through the production {@code read_file} Tool -> model answer.
 *
 * <p>The controlled OpenAI-compatible Provider is scripted only by protocol shape: it calls
 * {@code read_file} on the path the Run context lists and derives every number from the Tool result
 * it receives. It never holds the CSV values; the assertions compare against an independent
 * recomputation of the fixed V2 fixture.
 */
class DeerFlowUploadRunContextHttpTest {
    private static final String FIXTURE_SHA256 =
            "ec326b7d0cf68f660ed769f8eecf9b71b43137af4e9aa83de75aa75bc5302f00";
    private static final Pattern CONTEXT_PATH =
            Pattern.compile("(/mnt/user-data/uploads/[^\\s)]+)");

    @TempDir Path dataRoot;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<JsonNode> providerRequests = java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger providerCalls = new AtomicInteger();
    private final String key = Base64.getEncoder().encodeToString(new byte[32]);
    private HttpServer provider;
    private DeerFlowBootstrapApi api;
    private URI base;
    private byte[] fixture;

    @BeforeEach
    void start() throws Exception {
        fixture = Files.readAllBytes(Path.of("..", "contracts", "customer-demo-v2", "fixtures", "customers.csv"));
        assertThat(sha256(fixture)).as("fixed V2 fixture must not drift").isEqualTo(FIXTURE_SHA256);
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            providerRequests.add(request);
            byte[] response = controlledProviderResponse(request, providerCalls.incrementAndGet());
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        provider.start();
        startGateway();
    }

    @AfterEach
    void stop() {
        if (api != null) api.close();
        if (provider != null) provider.stop(0);
    }

    /** FAIL (a): the frozen UI uploads before the new conversation's Thread row exists. */
    @Test
    void firstUploadOfANewConversationIsAcceptedBeforeTheThreadIsCreated() throws Exception {
        String threadId = "v2-new-" + System.nanoTime();
        HttpResponse<String> limits = request("GET", "/api/threads/" + threadId + "/uploads/limits", null);
        assertThat(limits.statusCode()).as(limits.body()).isEqualTo(200);
        HttpResponse<String> emptyList = request("GET", "/api/threads/" + threadId + "/uploads/list", null);
        assertThat(emptyList.statusCode()).as(emptyList.body()).isEqualTo(200);
        assertThat(json.readTree(emptyList.body()).path("count").asInt()).isZero();

        HttpResponse<String> uploaded = upload(threadId, "customers.csv", fixture);
        assertThat(uploaded.statusCode()).as(uploaded.body()).isEqualTo(200);
        JsonNode file = json.readTree(uploaded.body()).path("files").get(0);
        assertThat(file.path("virtual_path").asText()).isEqualTo("/mnt/user-data/uploads/customers.csv");
        assertThat(file.path("size").asLong()).isEqualTo(fixture.length);

        // The frozen SDK then creates the Thread and submits the Run that references the upload.
        assertThat(request("POST", "/api/threads", "{\"thread_id\":\"" + threadId + "\"}").statusCode())
                .isEqualTo(200);
        HttpResponse<byte[]> artifact = getBytes(file.path("artifact_url").asText());
        assertThat(artifact.statusCode()).isEqualTo(200);
        assertThat(sha256(artifact.body())).isEqualTo(FIXTURE_SHA256);

        RunResult run = runWithFiles(threadId, "v2-file-context new thread: report row 17 and totals",
                List.of(fileRef("customers.csv", fixture.length)));
        assertThat(run.status()).isEqualTo("success");
        assertCsvOracle(run.answer());
        assertThat(run.readFileSha256()).isEqualTo(FIXTURE_SHA256);
        System.out.println("W04_V2_NEW_THREAD_EVIDENCE thread=" + threadId + " run=" + run.runId()
                + " upload=" + file.path("virtual_path").asText() + " sha256=" + sha256(artifact.body())
                + " toolCall=" + run.readFileToolCallId() + " answer=" + run.answer());
    }

    @Test
    void uploadRejectsInvalidThreadIdentifiersLikeTheFrozenThreadIdType() throws Exception {
        assertThat(upload("bad.thread", "customers.csv", fixture).statusCode()).isEqualTo(422);
        assertThat(upload("x".repeat(65), "customers.csv", fixture).statusCode()).isEqualTo(422);
        assertThat(request("GET", "/api/threads/bad.thread/uploads/limits", null).statusCode()).isEqualTo(422);
    }

    /** FAIL (b): an upload on an existing Thread must reach the Run context and be read from disk. */
    @Test
    void existingThreadUploadReachesTheRunContextAndTheModelReadsTheSavedOriginal() throws Exception {
        String threadId = "v2-existing";
        assertThat(request("POST", "/api/threads", "{\"thread_id\":\"" + threadId + "\"}").statusCode())
                .isEqualTo(200);
        RunResult opener = runWithFiles(threadId, "v2-file-context opener", List.of());
        assertThat(opener.answer()).isEqualTo("NO_FILE_CONTEXT");

        HttpResponse<String> uploaded = upload(threadId, "customers.csv", fixture);
        assertThat(uploaded.statusCode()).as(uploaded.body()).isEqualTo(200);
        int before = providerRequests.size();
        RunResult run = runWithFiles(threadId, "v2-file-context existing thread: report row 17 and totals",
                List.of(fileRef("customers.csv", fixture.length)));
        assertThat(run.status()).isEqualTo("success");
        assertCsvOracle(run.answer());

        // Run context layer: the reference (not the CSV contents) reaches the model.
        String contextMessage = lastUserContent(providerRequests.get(before));
        assertThat(contextMessage).contains("<current_uploads>", "customers.csv",
                "Path: /mnt/user-data/uploads/customers.csv", FIXTURE_SHA256);
        assertThat(contextMessage).doesNotContain("C017", "customer_id,region");
        // Tool layer: the second provider request carries read_file's saved-byte result.
        String toolResult = lastToolContent(providerRequests.get(before + 1));
        assertThat(toolResult).contains(FIXTURE_SHA256, "customer_id,region,amount_cny", "C017,East,1700");
        assertThat(run.readFileSha256()).isEqualTo(FIXTURE_SHA256);
        assertThat(run.readFilePath()).isEqualTo("/mnt/user-data/uploads/customers.csv");

        // Workspace layer: the saved original is byte-identical and unchanged by the Run.
        Path saved = savedUpload(threadId, "customers.csv");
        assertThat(sha256(Files.readAllBytes(saved))).isEqualTo(FIXTURE_SHA256);

        // Durable transcript layer: history shows the original user text plus the upload reference.
        JsonNode state = json.readTree(request("GET", "/api/threads/" + threadId + "/state", null).body());
        assertThat(state.toString()).contains("read_file", FIXTURE_SHA256, run.answer());
        System.out.println("W04_V2_EXISTING_THREAD_EVIDENCE thread=" + threadId + " run=" + run.runId()
                + " saved=" + saved + " sha256=" + FIXTURE_SHA256 + " toolCall=" + run.readFileToolCallId()
                + " answer=" + run.answer());
    }

    @Test
    void aFileThatIsNotAttachedToThisMessageIsNotInjectedIntoTheRunContext() throws Exception {
        String threadId = "v2-not-attached";
        assertThat(request("POST", "/api/threads", "{\"thread_id\":\"" + threadId + "\"}").statusCode())
                .isEqualTo(200);
        assertThat(upload(threadId, "customers.csv", fixture).statusCode()).isEqualTo(200);
        int before = providerRequests.size();
        RunResult run = runWithFiles(threadId, "v2-file-context without attachment", List.of());
        assertThat(run.answer()).isEqualTo("NO_FILE_CONTEXT");
        assertThat(lastUserContent(providerRequests.get(before))).doesNotContain("<current_uploads>", "C017");
    }

    @Test
    void crossThreadMissingAndTraversalReferencesAreReportedUnavailableAndCannotBeRead() throws Exception {
        assertThat(request("POST", "/api/threads", "{\"thread_id\":\"v2-owner-a\"}").statusCode()).isEqualTo(200);
        assertThat(request("POST", "/api/threads", "{\"thread_id\":\"v2-owner-b\"}").statusCode()).isEqualTo(200);
        assertThat(upload("v2-owner-a", "customers.csv", fixture).statusCode()).isEqualTo(200);

        int before = providerRequests.size();
        RunResult foreign = runWithFiles("v2-owner-b", "v2-file-context foreign reference",
                List.of(fileRef("customers.csv", fixture.length),
                        fileRef("../v2-owner-a/customers.csv", fixture.length),
                        fileRef("missing.csv", 10)));
        String context = lastUserContent(providerRequests.get(before));
        assertThat(context).contains("<current_uploads>", "unavailable", "missing.csv");
        assertThat(context).doesNotContain("../", FIXTURE_SHA256);
        assertThat(context).contains("1 file reference(s) rejected");
        // The controlled model still tries read_file on the listed path; the Thread-scoped Tool fails closed.
        assertThat(foreign.answer()).startsWith("FILE_UNREADABLE");
        assertThat(foreign.answer()).doesNotContain("C017");
        assertThat(lastToolContent(providerRequests.get(before + 1))).startsWith("Error:");

        HttpResponse<byte[]> crossArtifact = getBytes(
                "/api/threads/v2-owner-b/artifacts/mnt/user-data/uploads/customers.csv");
        assertThat(crossArtifact.statusCode()).isEqualTo(404);
        assertThat(new String(crossArtifact.body(), StandardCharsets.UTF_8)).doesNotContain("C017");
    }

    @Test
    void aProjectDocumentReachesTheRunOnlyAfterItIsAttachedToTheThread() throws Exception {
        HttpResponse<String> project = request("POST", "/api/projects", "{\"name\":\"V2 customers\"}");
        assertThat(project.statusCode()).as(project.body()).isEqualTo(201);
        String projectId = json.readTree(project.body()).path("id").asText();
        HttpResponse<String> document = multipart(
                "/api/projects/" + projectId + "/documents", "file", "customers.csv", fixture);
        assertThat(document.statusCode()).as(document.body()).isEqualTo(201);
        String documentId = json.readTree(document.body()).path("document").path("id").asText();
        assertThat(json.readTree(document.body()).path("document").path("sha256").asText())
                .isEqualTo(FIXTURE_SHA256);
        String threadId = "v2-project-thread";
        assertThat(request("POST", "/api/threads",
                "{\"thread_id\":\"" + threadId + "\",\"project_id\":\"" + projectId + "\"}").statusCode())
                .isEqualTo(200);

        // Not attached yet: the shelf document is not a Thread upload and cannot be read by the Run.
        RunResult unattached = runWithFiles(threadId, "v2-file-context project before attach",
                List.of(fileRef("customers.csv", fixture.length)));
        assertThat(unattached.answer()).startsWith("FILE_UNREADABLE").doesNotContain("C017");

        HttpResponse<String> attached = request("POST", "/api/projects/" + projectId + "/documents/"
                + documentId + "/attach-to-thread/" + threadId, null);
        assertThat(attached.statusCode()).as(attached.body()).isEqualTo(200);
        JsonNode attachment = json.readTree(attached.body());
        assertThat(attachment.path("virtual_path").asText()).isEqualTo("/mnt/user-data/uploads/customers.csv");
        RunResult run = runWithFiles(threadId, "v2-file-context project attached",
                List.of(fileRef(attachment.path("filename").asText(), attachment.path("size_bytes").asLong())));
        assertThat(run.status()).isEqualTo("success");
        assertCsvOracle(run.answer());
        assertThat(run.readFileSha256()).isEqualTo(FIXTURE_SHA256);

        HttpResponse<String> foreignAttach = request("POST", "/api/projects/" + projectId + "/documents/"
                + documentId + "/attach-to-thread/v2-not-a-project-thread", null);
        assertThat(foreignAttach.statusCode()).isEqualTo(404);
        System.out.println("W04_V2_PROJECT_ATTACH_EVIDENCE project=" + projectId + " document=" + documentId
                + " thread=" + threadId + " run=" + run.runId() + " toolCall=" + run.readFileToolCallId()
                + " answer=" + run.answer());
    }

    @Test
    void wrongEncodingAndCorruptedOriginalsFailExplicitlyInsteadOfYieldingValues() throws Exception {
        String threadId = "v2-encoding";
        assertThat(request("POST", "/api/threads", "{\"thread_id\":\"" + threadId + "\"}").statusCode())
                .isEqualTo(200);
        byte[] gbk = "customer_id,region,amount_cny\nC001,华东,100\n".getBytes(java.nio.charset.Charset.forName("GBK"));
        assertThat(upload(threadId, "gbk.csv", gbk).statusCode()).isEqualTo(200);
        int before = providerRequests.size();
        RunResult encoded = runWithFiles(threadId, "v2-file-context wrong encoding",
                List.of(fileRef("gbk.csv", gbk.length)));
        assertThat(lastUserContent(providerRequests.get(before))).contains("not valid UTF-8");
        assertThat(encoded.answer()).startsWith("FILE_UNREADABLE");
        assertThat(lastToolContent(providerRequests.get(before + 1))).contains("not valid UTF-8");

        byte[] bom = concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, fixture);
        assertThat(upload(threadId, "bom.csv", bom).statusCode()).isEqualTo(200);
        before = providerRequests.size();
        runWithFiles(threadId, "v2-file-context bom", List.of(fileRef("bom.csv", bom.length)));
        assertThat(lastUserContent(providerRequests.get(before))).contains("UTF-8 with BOM");

        assertThat(upload(threadId, "corrupt.csv", fixture).statusCode()).isEqualTo(200);
        Path saved = savedUpload(threadId, "corrupt.csv");
        byte[] corrupted = fixture.clone();
        corrupted[40] = (byte) 0xFF;
        Files.write(saved, corrupted);
        before = providerRequests.size();
        RunResult corrupt = runWithFiles(threadId, "v2-file-context corrupt",
                List.of(fileRef("corrupt.csv", fixture.length)));
        assertThat(corrupt.answer()).startsWith("FILE_UNREADABLE").doesNotContain("C017");
        assertThat(lastUserContent(providerRequests.get(before))).doesNotContain(FIXTURE_SHA256);
    }

    @Test
    void duplicateUploadsAndReferencesStayDeterministicAndOriginalsAreImmutable() throws Exception {
        String threadId = "v2-duplicate";
        assertThat(request("POST", "/api/threads", "{\"thread_id\":\"" + threadId + "\"}").statusCode())
                .isEqualTo(200);
        JsonNode first = json.readTree(upload(threadId, "customers.csv", fixture).body()).path("files").get(0);
        JsonNode again = json.readTree(upload(threadId, "customers.csv", fixture).body()).path("files").get(0);
        assertThat(again.path("virtual_path").asText()).isEqualTo(first.path("virtual_path").asText());
        byte[] changed = new String(fixture, StandardCharsets.UTF_8).replace("C017,East,1700", "C017,East,9999")
                .getBytes(StandardCharsets.UTF_8);
        JsonNode renamed = json.readTree(upload(threadId, "customers.csv", changed).body()).path("files").get(0);
        assertThat(renamed.path("virtual_path").asText()).isNotEqualTo(first.path("virtual_path").asText());
        assertThat(sha256(Files.readAllBytes(savedUpload(threadId, "customers.csv")))).isEqualTo(FIXTURE_SHA256);

        int before = providerRequests.size();
        RunResult run = runWithFiles(threadId, "v2-file-context duplicate reference",
                List.of(fileRef("customers.csv", fixture.length), fileRef("customers.csv", fixture.length)));
        String context = lastUserContent(providerRequests.get(before));
        assertThat(countOccurrences(context, "Path: /mnt/user-data/uploads/customers.csv")).isEqualTo(1);
        assertCsvOracle(run.answer());
    }

    @Test
    void theSavedOriginalIsStillReadByANewRunAfterAGatewayRestart() throws Exception {
        String threadId = "v2-restart";
        assertThat(request("POST", "/api/threads", "{\"thread_id\":\"" + threadId + "\"}").statusCode())
                .isEqualTo(200);
        assertThat(upload(threadId, "customers.csv", fixture).statusCode()).isEqualTo(200);
        RunResult first = runWithFiles(threadId, "v2-file-context before restart",
                List.of(fileRef("customers.csv", fixture.length)));
        assertCsvOracle(first.answer());

        api.close();
        api = null;
        startGateway();

        RunResult afterRestart = runWithFiles(threadId, "v2-file-context after restart",
                List.of(fileRef("customers.csv", fixture.length)));
        assertThat(afterRestart.status()).isEqualTo("success");
        assertCsvOracle(afterRestart.answer());
        assertThat(afterRestart.readFileSha256()).isEqualTo(FIXTURE_SHA256);
        assertThat(afterRestart.runId()).isNotEqualTo(first.runId());
        assertThat(sha256(Files.readAllBytes(savedUpload(threadId, "customers.csv")))).isEqualTo(FIXTURE_SHA256);
        System.out.println("W04_V2_RESTART_EVIDENCE thread=" + threadId + " runBefore=" + first.runId()
                + " runAfter=" + afterRestart.runId() + " sha256=" + afterRestart.readFileSha256());
    }

    @Test
    void largeUploadsAreReadInBoundedLineWindows() throws Exception {
        String threadId = "v2-large";
        assertThat(request("POST", "/api/threads", "{\"thread_id\":\"" + threadId + "\"}").statusCode())
                .isEqualTo(200);
        StringBuilder text = new StringBuilder();
        for (int line = 1; line <= 4000; line++) {
            text.append("line-").append(line).append(",").append("x".repeat(40)).append('\n');
        }
        byte[] large = text.toString().getBytes(StandardCharsets.UTF_8);
        assertThat(upload(threadId, "large.txt", large).statusCode()).isEqualTo(200);
        int before = providerRequests.size();
        RunResult run = runWithFiles(threadId, "v2-file-context large", List.of(fileRef("large.txt", large.length)));
        assertThat(run.status()).as(run.runJson()).isEqualTo("success");
        String toolResult = lastToolContent(providerRequests.get(before + 1));
        assertThat(toolResult).contains("truncated=true", "start_line", "line-1,").doesNotContain("line-4000,");
        assertThat(toolResult.getBytes(StandardCharsets.UTF_8).length).isLessThan(8 * 1024);
        // The next window continues exactly where the bounded read stopped.
        Matcher next = Pattern.compile("continue with start_line=(\\d+)").matcher(toolResult);
        assertThat(next.find()).isTrue();
        assertThat(toolResult).contains("line-" + (Integer.parseInt(next.group(1)) - 1) + ",")
                .doesNotContain("line-" + next.group(1) + ",");
    }

    // ---- controlled provider (L2) -------------------------------------------------------------

    private byte[] controlledProviderResponse(JsonNode request, int call) throws IOException {
        String responseId = "v2-file-context-" + call;
        JsonNode messages = request.path("messages");
        JsonNode last = messages.get(messages.size() - 1);
        List<String> tools = request.path("tools").findValuesAsText("name");
        String chunk;
        if ("tool".equals(last.path("role").asText())) {
            chunk = textChunk(responseId, answerFromToolResult(toolText(last.path("content").asText())));
        } else {
            String user = textOf(last.path("content"));
            Matcher path = CONTEXT_PATH.matcher(user);
            if (user.contains("<current_uploads>") && path.find() && tools.contains("read_file")) {
                String arguments = json.writeValueAsString(Map.of("path", path.group(1)));
                chunk = json.writeValueAsString(Map.of(
                        "id", responseId, "model", "v2-controlled",
                        "choices", List.of(Map.of("index", 0, "finish_reason", "tool_calls",
                                "delta", Map.of("tool_calls", List.of(Map.of(
                                        "index", 0, "id", "call-v2-read-" + call, "type", "function",
                                        "function", Map.of("name", "read_file", "arguments", arguments))))))));
            } else {
                chunk = textChunk(responseId, "NO_FILE_CONTEXT");
            }
        }
        return ("data: " + chunk + "\n\n"
                + "data: {\"id\":\"" + responseId + "\",\"model\":\"v2-controlled\",\"choices\":[],"
                + "\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":3}}\n\n"
                + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
    }

    private String textChunk(String responseId, String text) throws IOException {
        return json.writeValueAsString(Map.of(
                "id", responseId, "model", "v2-controlled",
                "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                        "delta", Map.of("content", text)))));
    }

    /** Parses only what the Tool returned; the numbers are never known to the provider in advance. */
    static String answerFromToolResult(String toolContent) {
        if (toolContent.startsWith("Error:")) {
            return "FILE_UNREADABLE " + toolContent.lines().findFirst().orElse("");
        }
        List<String> lines = toolContent.lines().toList();
        int header = lines.indexOf("customer_id,region,amount_cny");
        if (header < 0) {
            return "FILE_UNREADABLE no CSV header";
        }
        List<String[]> rows = new ArrayList<>();
        for (String line : lines.subList(header + 1, lines.size())) {
            if (line.isBlank()) continue;
            rows.add(line.split(",", -1));
        }
        Map<String, Long> totals = new LinkedHashMap<>();
        long total = 0;
        int high = 0;
        for (String[] row : rows) {
            long amount = Long.parseLong(row[2]);
            totals.merge(row[1], amount, Long::sum);
            total += amount;
            if (amount >= 1500) high++;
        }
        String row17 = rows.size() >= 17 ? rows.get(16)[0] : "missing";
        return "row17=" + row17 + " east=" + totals.getOrDefault("East", 0L)
                + " west=" + totals.getOrDefault("West", 0L) + " total=" + total
                + " high=" + high + " rows=" + rows.size();
    }

    private void assertCsvOracle(String answer) {
        // Independent recomputation from the fixture bytes (not from the Run).
        List<String> lines = new String(fixture, StandardCharsets.UTF_8).lines().toList();
        assertThat(lines.getFirst()).isEqualTo("customer_id,region,amount_cny");
        List<String[]> rows = lines.subList(1, lines.size()).stream().map(line -> line.split(",")).toList();
        long east = rows.stream().filter(row -> row[1].equals("East")).mapToLong(row -> Long.parseLong(row[2])).sum();
        long west = rows.stream().filter(row -> row[1].equals("West")).mapToLong(row -> Long.parseLong(row[2])).sum();
        long high = rows.stream().filter(row -> Long.parseLong(row[2]) >= 1500).count();
        String expected = "row17=" + rows.get(16)[0] + " east=" + east + " west=" + west
                + " total=" + (east + west) + " high=" + high + " rows=" + rows.size();
        assertThat(expected).isEqualTo("row17=C017 east=10000 west=11000 total=21000 high=6 rows=20");
        assertThat(answer).isEqualTo(expected);
    }

    // ---- HTTP helpers -------------------------------------------------------------------------

    private void startGateway() throws Exception {
        api = DeerFlowBootstrapApi.create(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataRoot,
                Map.of("HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W04_V2_TEST_KEY",
                        "W04_V2_TEST_KEY", key));
        api.start();
        base = URI.create("http://127.0.0.1:" + api.address().getPort());
        HttpResponse<String> models = request("GET", "/api/managed-models", null);
        String revision = null;
        for (JsonNode model : json.readTree(models.body()).path("models")) {
            if ("v2-model".equals(model.path("name").asText())) revision = model.path("revision").asText();
        }
        String endpoint = "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1";
        String body = """
                {"config":{"name":"v2-model","display_name":"V2 Controlled",
                "provider":"openai-compatible","model":"v2-controlled",
                "base_url":"%s","enabled":true,"supports_vision":false,
                "context_window":8192,"max_tokens":1024,"api_key":"controlled-key"},
                "expected_revision":%s}
                """.formatted(endpoint, revision == null ? "null" : "\"" + revision + "\"");
        if (revision != null) {
            return; // A Gateway restart keeps the persisted managed model and its revision.
        }
        HttpResponse<String> saved = request("PUT", "/api/managed-models", body);
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
    }

    private RunResult runWithFiles(String threadId, String text, List<Map<String, Object>> files) throws Exception {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "human");
        message.put("content", List.of(Map.of("type", "text", "text", text)));
        message.put("additional_kwargs", files.isEmpty() ? Map.of() : Map.of("files", files));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assistant_id", "lead_agent");
        body.put("input", Map.of("messages", List.of(message)));
        body.put("stream_mode", List.of("messages-tuple", "updates", "custom"));
        body.put("context", Map.of("model_name", "v2-model", "mode", "flash", "thread_id", threadId));
        body.put("on_disconnect", "continue");
        body.put("multitask_strategy", "reject");
        HttpResponse<String> stream = request(
                "POST", "/api/threads/" + threadId + "/runs/stream", json.writeValueAsString(body));
        assertThat(stream.statusCode()).as(stream.body()).isEqualTo(200);
        String location = stream.headers().firstValue("content-location").orElseThrow();
        String runId = location.substring(location.lastIndexOf('/') + 1);
        String status = "";
        String runJson = "";
        for (int attempt = 0; attempt < 200; attempt++) {
            runJson = request("GET", location, null).body();
            status = json.readTree(runJson).path("status").asText();
            if (!status.equals("pending") && !status.equals("running")) break;
            Thread.sleep(25);
        }
        JsonNode state = json.readTree(request("GET", "/api/threads/" + threadId + "/state", null).body());
        String answer = "";
        String toolCallId = null;
        String readPath = null;
        String readSha = null;
        for (JsonNode item : state.path("values").path("messages")) {
            if (!runId.equals(item.path("run_id").asText(runId))) continue;
            if ("ai".equals(item.path("type").asText()) && item.path("tool_calls").isEmpty()
                    && !item.path("content").asText().isEmpty()) {
                answer = item.path("content").asText();
            }
            for (JsonNode call : item.path("tool_calls")) {
                if ("read_file".equals(call.path("name").asText())) toolCallId = call.path("id").asText();
            }
            if ("tool".equals(item.path("type").asText()) && "read_file".equals(item.path("name").asText())) {
                JsonNode structured = item.path("additional_kwargs").path("haifa_tool_result");
                readPath = structured.path("path").asText(null);
                readSha = structured.path("sha256").asText(null);
            }
        }
        return new RunResult(runId, status, answer, toolCallId, readPath, readSha, runJson);
    }

    private static Map<String, Object> fileRef(String filename, long size) {
        return Map.of("filename", filename, "size", size,
                "path", "/mnt/user-data/uploads/" + filename, "status", "uploaded");
    }

    private Path savedUpload(String threadId, String filename) throws IOException {
        try (Stream<Path> paths = Files.walk(dataRoot.resolve("workspace").resolve("workspaces"))) {
            List<Path> matches = paths.filter(path -> path.getFileName().toString().equals(filename))
                    .filter(path -> path.getParent().getFileName().toString().equals("uploads"))
                    .filter(path -> {
                        try {
                            return Files.readString(path.getParent().getParent().getParent()
                                    .resolve("binding.properties")).contains("threadId=" + threadId);
                        } catch (IOException unreadable) {
                            return false;
                        }
                    })
                    .toList();
            assertThat(matches).hasSize(1);
            return matches.getFirst();
        }
    }

    private HttpResponse<String> upload(String threadId, String filename, byte[] content) throws Exception {
        return multipart("/api/threads/" + threadId + "/uploads", "files", filename, content);
    }

    private HttpResponse<String> multipart(String path, String field, String filename, byte[] content)
            throws Exception {
        String boundary = "v2-boundary-" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field
                + "\"; filename=\"" + filename + "\"\r\nContent-Type: text/csv\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
        return client.send(HttpRequest.newBuilder(base.resolve(path))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(30));
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> getBytes(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String lastUserContent(JsonNode request) {
        String result = "";
        for (JsonNode message : request.path("messages")) {
            if ("user".equals(message.path("role").asText())) result = textOf(message.path("content"));
        }
        return result;
    }

    private static String lastToolContent(JsonNode request) {
        String result = null;
        for (JsonNode message : request.path("messages")) {
            if ("tool".equals(message.path("role").asText())) result = message.path("content").asText();
        }
        assertThat(result).as("provider request must carry a Tool result: " + request).isNotNull();
        return toolText(result);
    }

    /** The SDK hands the model a JSON Tool envelope; its summary is the Tool's text result. */
    private static String toolText(String content) {
        try {
            JsonNode envelope = new ObjectMapper().readTree(content);
            if (envelope != null && envelope.path("summary").isTextual()) return envelope.path("summary").asText();
        } catch (IOException notJson) {
            // A plain-text Tool result is used as-is.
        }
        return content;
    }

    private static String textOf(JsonNode content) {
        if (content.isTextual()) return content.asText();
        StringBuilder text = new StringBuilder();
        for (JsonNode part : content) text.append(part.path("text").asText());
        return text.toString();
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        for (int index = value.indexOf(needle); index >= 0; index = value.indexOf(needle, index + 1)) count++;
        return count;
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] result = java.util.Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private record RunResult(
            String runId, String status, String answer, String readFileToolCallId,
            String readFilePath, String readFileSha256, String runJson) {}
}

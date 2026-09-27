package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Production bootstrap, controlled OpenAI-compatible Provider, SDK approval, real file transport. */
class ProductionWriteFileHttpTest {
    @TempDir Path dataRoot;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void productionApprovalGateRejectsMismatchedCrossThreadDuplicateAndRejectedWrites() throws Exception {
        byte[] expected = "真实文件内容\n".getBytes(StandardCharsets.UTF_8);
        String path = "/mnt/user-data/outputs/production.txt";
        List<JsonNode> providerRequests = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger calls = new AtomicInteger();
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            providerRequests.add(request);
            int call = calls.getAndIncrement();
            boolean hasToolResult = false;
            for (JsonNode message : request.path("messages")) {
                if ("tool".equals(message.path("role").asText())) {
                    hasToolResult = true;
                    break;
                }
            }
            String chunk;
            String responseId = "provider-write-" + (call + 1);
            if (!hasToolResult) {
                String arguments = json.writeValueAsString(Map.of(
                        "path", path, "content", new String(expected, StandardCharsets.UTF_8)));
                chunk = json.writeValueAsString(Map.of(
                        "id", responseId, "model", "demo-provider-id",
                        "choices", List.of(Map.of("index", 0, "finish_reason", "tool_calls",
                                "delta", Map.of("tool_calls", List.of(Map.of(
                                        "index", 0, "id", "call-production-write-" + (call + 1), "type", "function",
                                        "function", Map.of("name", "write_file", "arguments", arguments))))))));
            } else {
                chunk = json.writeValueAsString(Map.of(
                        "id", responseId, "model", "demo-provider-id",
                        "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                                "delta", Map.of("content", "Created the file.")))));
            }
            byte[] response = ("data: " + chunk + "\n\n"
                    + "data: {\"id\":\"" + responseId + "\",\"model\":\"demo-provider-id\",\"choices\":[],"
                    + "\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":3}}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        provider.start();
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        try (var api = DeerFlowBootstrapApi.create(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataRoot,
                Map.of("HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W05_TEST_KEY",
                        "W05_TEST_KEY", key))) {
            api.start();
            URI base = URI.create("http://127.0.0.1:" + api.address().getPort());
            String endpoint = "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1";
            String model = """
                    {"config":{"name":"demo-model","display_name":"Demo Model",
                    "provider":"openai-compatible","model":"demo-provider-id",
                    "base_url":"%s","enabled":true,"supports_vision":false,
                    "context_window":8192,"max_tokens":1024,"api_key":"controlled-key"},
                    "expected_revision":null}
                    """.formatted(endpoint);
            assertThat(request(base, "PUT", "/api/managed-models", model).statusCode()).isEqualTo(200);
            assertThat(request(base, "POST", "/api/threads", "{\"thread_id\":\"production-file\"}")
                    .statusCode()).isEqualTo(200);
            String runBody = """
                    {"assistant_id":"lead_agent","input":{"messages":[{"type":"human","content":"create a file"}]},
                    "stream_mode":["messages-tuple","updates","custom"],
                    "context":{"model_name":"demo-model","mode":"flash","reasoning_effort":"minimal",
                    "thinking_enabled":false,"is_plan_mode":false,"subagent_enabled":false},
                    "on_disconnect":"continue","multitask_strategy":"reject"}
                    """;
            HttpResponse<String> pending = request(base, "POST", "/api/threads/production-file/runs/stream", runBody);
            assertThat(pending.statusCode()).as(pending.body()).isEqualTo(200);
            String location = pending.headers().firstValue("content-location").orElseThrow();
            String runId = location.substring(location.lastIndexOf('/') + 1);
            var requestIdMatch = Pattern.compile("\\\"request_id\\\":\\\"([^\\\"]+)\\\"").matcher(pending.body());
            assertThat(requestIdMatch.find()).as(pending.body()).isTrue();
            String requestId = requestIdMatch.group(1);
            assertThat(providerRequests).hasSize(1);
            assertThat(providerRequests.getFirst().path("tools").findValuesAsText("name"))
                    .contains("write_file").doesNotContain("ask_clarification");
            URI artifact = base.resolve("/api/threads/production-file/artifacts" + path);
            assertThat(client.send(HttpRequest.newBuilder(artifact).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(404);

            String wrongRequest = interactionResponse("another-request", "approve");
            assertThat(request(base, "POST", "/api/threads/production-file/runs/stream", wrongRequest)
                    .statusCode()).isEqualTo(409);
            assertThat(request(base, "POST", "/api/threads", "{\"thread_id\":\"production-file-other\"}")
                    .statusCode()).isEqualTo(200);
            String crossThread = interactionResponse(requestId, "approve");
            assertThat(request(base, "POST", "/api/threads/production-file-other/runs/stream", crossThread)
                    .statusCode()).isEqualTo(409);
            assertThat(client.send(HttpRequest.newBuilder(artifact).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(404);

            String approval = interactionResponse(requestId, "approve");
            HttpResponse<String> resumed = request(base, "POST", "/api/threads/production-file/runs/stream", approval);
            assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
            assertThat(resumed.body()).contains("Created the file.");
            assertThat(json.readTree(request(base, "GET", location, null).body()).path("status").asText())
                    .isEqualTo("success");
            assertThat(providerRequests).hasSize(2);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected));
            assertThat(providerRequests.get(1).toString())
                    .contains("Created", "call-production-write", path, sha);
            JsonNode toolResult = null;
            for (JsonNode message : providerRequests.get(1).path("messages")) {
                if ("tool".equals(message.path("role").asText())) toolResult = message;
            }
            assertThat(toolResult).isNotNull();
            assertThat(toolResult.path("tool_call_id").asText()).isEqualTo("call-production-write-1");
            String toolSummary = toolResult.path("content").asText();
            assertThat(toolSummary).contains(path, sha);

            HttpResponse<byte[]> downloaded = client.send(
                    HttpRequest.newBuilder(artifact).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(downloaded.statusCode()).isEqualTo(200);
            assertThat(downloaded.body()).isEqualTo(expected);
            assertThat(downloaded.headers().firstValue("etag")).contains("\"" + sha + "\"");

            JsonNode changes = json.readTree(request(base, "GET",
                    location + "/workspace-changes?include_files=true", null).body());
            assertThat(changes.path("available").asBoolean()).as(changes.toString()).isTrue();
            assertThat(changes.path("files").toString()).contains(path, sha);

            HttpResponse<String> duplicate = request(
                    base, "POST", "/api/threads/production-file/runs/stream", approval);
            assertThat(duplicate.statusCode()).as(duplicate.body()).isEqualTo(409);
            assertThat(providerRequests).hasSize(2);
            assertThat(client.send(HttpRequest.newBuilder(artifact).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()).body()).isEqualTo(expected);

            assertThat(request(base, "POST", "/api/threads", "{\"thread_id\":\"production-file-rejected\"}")
                    .statusCode()).isEqualTo(200);
            HttpResponse<String> rejectedPending = request(
                    base, "POST", "/api/threads/production-file-rejected/runs/stream", runBody);
            assertThat(rejectedPending.statusCode()).as(rejectedPending.body()).isEqualTo(200);
            var rejectedRequestMatch = Pattern.compile("\\\"request_id\\\":\\\"([^\\\"]+)\\\"")
                    .matcher(rejectedPending.body());
            assertThat(rejectedRequestMatch.find()).as(rejectedPending.body()).isTrue();
            URI rejectedArtifact = base.resolve("/api/threads/production-file-rejected/artifacts" + path);
            assertThat(client.send(HttpRequest.newBuilder(rejectedArtifact).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(404);
            HttpResponse<String> rejected = request(
                    base,
                    "POST",
                    "/api/threads/production-file-rejected/runs/stream",
                    interactionResponse(rejectedRequestMatch.group(1), "reject"));
            assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(200);
            assertThat(client.send(HttpRequest.newBuilder(rejectedArtifact).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(404);

            System.out.println("W05_PRODUCTION_FILE_EVIDENCE run=" + runId
                    + " toolCall=call-production-write-1 size=" + expected.length + " sha256=" + sha
                    + " wrong_request=409 cross_thread=409 duplicate=409 rejected_file=404"
                    + " toolSummary=" + toolSummary);
        } finally {
            provider.stop(0);
        }
    }

    private static String interactionResponse(String requestId, String option) {
        return """
                {"assistant_id":"lead_agent","input":{"messages":[{"id":"response","type":"human",
                "content":[{"type":"text","text":"%s"}],
                "additional_kwargs":{"hide_from_ui":true,"human_input_response":
                {"version":1,"kind":"human_input_response","source":"haifa_interaction",
                "request_id":"%s","response_kind":"option","option_id":"%s","value":"%s"}}}]},
                "stream_mode":["messages-tuple","updates","custom"],
                "on_disconnect":"continue","multitask_strategy":"reject"}
                """.formatted(option, requestId, option, option);
    }

    private HttpResponse<String> request(URI base, String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(30));
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}

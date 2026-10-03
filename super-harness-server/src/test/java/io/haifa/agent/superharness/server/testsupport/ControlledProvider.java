package io.haifa.agent.superharness.server.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Legal loopback /v1 Provider used by the production SDK client, with observable failures. */
public final class ControlledProvider implements AutoCloseable {
    @FunctionalInterface public interface Responder { ObjectNode respond(JsonNode request, int call) throws Exception; }
    private static final ObjectMapper JSON = new ObjectMapper();
    final HttpServer server;
    final Path evidence;
    public final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    public final List<Map<String, Object>> responseDiagnostics = new CopyOnWriteArrayList<>();
    public final AtomicInteger calls = new AtomicInteger();
    public final AtomicInteger expectedResponseAborts = new AtomicInteger();
    public final AtomicReference<Throwable> failure = new AtomicReference<>();

    public ControlledProvider(Path evidence, boolean rawEvidence, Responder responder) throws Exception {
        this(evidence, rawEvidence, responder, (request, authorization) -> true);
    }

    /** Synthetic credential assertions return a boolean; headers never enter evidence or errors. */
    public ControlledProvider(Path evidence, boolean rawEvidence, Responder responder,
            java.util.function.BiPredicate<JsonNode, String> authorizationAccepted) throws Exception {
        this(evidence, rawEvidence, responder, authorizationAccepted, request -> false);
    }

    /** Only response-write IO failures for explicitly selected cancellation requests are expected. */
    public ControlledProvider(Path evidence, boolean rawEvidence, Responder responder,
            java.util.function.BiPredicate<JsonNode, String> authorizationAccepted,
            java.util.function.Predicate<JsonNode> expectedResponseAbort) throws Exception {
        this.evidence = Files.createDirectories(evidence);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                if (!exchange.getRequestMethod().equals("POST")
                        || !exchange.getRequestURI().getPath().equals("/v1/chat/completions")) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                JsonNode request = JSON.readTree(exchange.getRequestBody());
                if (!authorizationAccepted.test(request, exchange.getRequestHeaders().getFirst("Authorization"))) {
                    throw new AssertionError("Controlled Provider authorization did not match synthetic model identity");
                }
                requests.add(request);
                int call = calls.incrementAndGet();
                ObjectNode response = responder.respond(request, call);
                validate(response);
                boolean streaming = request.path("stream").asBoolean();
                byte[] bytes = encode(response, streaming);
                var diagnostic = Map.<String, Object>of("call", call,
                        "response_kind", streaming ? "openai-compatible-sse" : "openai-compatible-json",
                        "choices_count", response.path("choices").size(),
                        "finish_reason", response.path("choices").get(0).path("finish_reason").asText(),
                        "tool_calls_count", response.path("choices").get(0).path("message").path("tool_calls").size(),
                        "usage_present", true, "response_bytes", bytes.length,
                        "response_sha256", "sha256:" + GatewayFixture.hash(new String(bytes, StandardCharsets.UTF_8)));
                responseDiagnostics.add(diagnostic);
                Files.writeString(evidence.resolve("provider-response-" + call + ".summary.json"),
                        JSON.writeValueAsString(diagnostic));
                Files.writeString(evidence.resolve("provider-" + call + ".summary.json"), JSON.writeValueAsString(Map.of(
                        "provider_call_count", call, "request_chars", request.toString().length(),
                        "request_sha256", GatewayFixture.hash(request.toString()), "response_bytes", bytes.length,
                        "response_sha256", GatewayFixture.hash(new String(bytes, StandardCharsets.UTF_8)),
                        "response_has_usage", true)));
                if (rawEvidence) {
                    // Synthetic requests only; never persist Authorization or managed-model credentials.
                    Files.writeString(evidence.resolve("provider-request-" + call + ".json"), request.toPrettyString());
                    Files.write(evidence.resolve("provider-response-" + call + (streaming ? ".sse" : ".json")), bytes);
                }
                try {
                    exchange.getResponseHeaders().set("Content-Type", streaming
                            ? "text/event-stream; charset=utf-8" : "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var output = exchange.getResponseBody()) { output.write(bytes); }
                } catch (java.io.IOException responseWriteFailure) {
                    if (!expectedResponseAbort.test(request)) throw responseWriteFailure;
                    expectedResponseAborts.incrementAndGet();
                    Files.writeString(evidence.resolve("provider-response-abort-" + call + ".json"),
                            JSON.writeValueAsString(Map.of("call", call, "phase", "response-write",
                                    "error_class", responseWriteFailure.getClass().getName(),
                                    "explicitly_expected", true, "response_bytes", bytes.length)));
                }
            } catch (Exception | AssertionError handlerFailure) {
                failure.compareAndSet(null, handlerFailure);
                try {
                    Files.writeString(evidence.resolve("provider-failure.json"), JSON.writeValueAsString(Map.of(
                            "error_class", handlerFailure.getClass().getName(), "calls", calls.get())));
                } catch (Exception evidenceFailure) { handlerFailure.addSuppressed(evidenceFailure); }
                exchange.sendResponseHeaders(500, -1);
            } finally { exchange.close(); }
        });
        server.start();
    }

    URI endpoint() { return URI.create("http://127.0.0.1:" + port() + "/v1"); }
    public int port() { return server.getAddress().getPort(); }

    public static ObjectNode stop(String id, String model, String content) {
        return stop(id, model, content, 4, 3);
    }

    public static ObjectNode stop(String id, String model, String content, long input, long output) {
        ObjectNode response = base(id, model, "stop", input, output);
        ((ObjectNode) response.path("choices").get(0).path("message")).put("content", content);
        return response;
    }

    public static ObjectNode call(String id, String model, String toolId, String tool, Map<String, Object> arguments) {
        return call(id, model, toolId, tool, arguments, 4, 3);
    }

    public static ObjectNode call(String id, String model, String toolId, String tool, Map<String, Object> arguments,
            long input, long output) {
        ObjectNode response = base(id, model, "tool_calls", input, output);
        var message = (ObjectNode) response.path("choices").get(0).path("message");
        message.put("content", "");
        message.putArray("tool_calls").addObject().put("id", toolId).put("type", "function")
                .putObject("function").put("name", tool).put("arguments", JSON.valueToTree(arguments).toString());
        return response;
    }

    private static ObjectNode base(String id, String model, String finish, long input, long output) {
        ObjectNode response = JSON.createObjectNode().put("id", id).put("object", "chat.completion")
                .put("created", 1).put("model", model);
        response.putArray("choices").addObject().put("index", 0).put("finish_reason", finish)
                .putObject("message").put("role", "assistant");
        response.putObject("usage").put("prompt_tokens", input).put("completion_tokens", output)
                .put("total_tokens", input + output);
        return response;
    }

    private static void validate(ObjectNode response) {
        if (response == null || !response.path("usage").isObject()) {
            throw new IllegalArgumentException("Controlled Provider response requires usage");
        }
        JsonNode usage = response.path("usage");
        for (String field : List.of("prompt_tokens", "completion_tokens", "total_tokens")) {
            if (!usage.path(field).isIntegralNumber() || usage.path(field).asLong() < 0) {
                throw new IllegalArgumentException("Controlled Provider usage requires nonnegative " + field);
            }
        }
        if (usage.path("total_tokens").asLong() != usage.path("prompt_tokens").asLong()
                + usage.path("completion_tokens").asLong()) {
            throw new IllegalArgumentException("Controlled Provider usage total is inconsistent");
        }
        if (response.path("id").asText().isBlank() || response.path("model").asText().isBlank()
                || !response.path("choices").isArray() || response.path("choices").size() != 1
                || !response.path("choices").get(0).path("message").isObject()
                || !List.of("stop", "tool_calls").contains(response.path("choices").get(0).path("finish_reason").asText())) {
            throw new IllegalArgumentException("Controlled Provider response lacks a complete choice/finish reason");
        }
    }

    static byte[] encode(ObjectNode response, boolean streaming) throws Exception {
        validate(response);
        if (!streaming) return JSON.writeValueAsBytes(response);
        ObjectNode chunk = response.deepCopy();
        chunk.put("object", "chat.completion.chunk");
        chunk.remove("usage");
        ObjectNode choice = (ObjectNode) chunk.path("choices").get(0);
        ObjectNode delta = (ObjectNode) choice.remove("message");
        int index = 0;
        for (JsonNode tool : delta.path("tool_calls")) ((ObjectNode) tool).put("index", index++);
        choice.set("delta", delta);
        ObjectNode usage = response.deepCopy();
        usage.putArray("choices");
        usage.put("object", "chat.completion.chunk");
        return ("data: " + chunk + "\n\ndata: " + usage + "\n\ndata: [DONE]\n\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    void assertHealthy() {
        Throwable observed = failure.get();
        if (observed != null) throw new AssertionError("Controlled Provider handler failed", observed);
    }

    @Override public void close() throws Exception {
        try { server.stop(0); }
        finally {
            try {
                Files.writeString(evidence.resolve("provider-final.summary.json"), JSON.writeValueAsString(Map.of(
                        "provider_call_count", calls.get(), "handler_failed", failure.get() != null)));
            } catch (Exception evidenceFailure) {
                if (failure.get() == null) throw evidenceFailure;
                failure.get().addSuppressed(evidenceFailure);
            }
        }
        assertHealthy();
    }
}

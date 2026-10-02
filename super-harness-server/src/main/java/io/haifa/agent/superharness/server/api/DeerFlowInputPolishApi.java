package io.haifa.agent.superharness.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.superharness.server.run.HaifaRunBridge;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Frozen DeerFlow pre-send draft polishing route backed by a single bounded model call. */
final class DeerFlowInputPolishApi implements HttpHandler {
    static final String PATH = "/api/input-polish";
    static final int DEFAULT_MAX_CHARS = 4_000;
    static final Duration MODEL_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_BODY_BYTES = 128 * 1024;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 1_024;
    private static final Set<String> ALLOWED_FIELDS = Set.of("text", "locale", "thread_id");
    private static final Pattern COMPLETE_THINK_BLOCK =
            Pattern.compile("<think\\b[^>]*>.*?</think\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final String SYSTEM_INSTRUCTION =
            """
            You are DeerFlow's pre-send prompt optimizer.
            Rewrite the user's rough draft into a clearer instruction for an AI agent before it is sent.
            Do not answer the task.
            Preserve the user's language, intent, entities, file paths, URLs, code blocks, and any leading slash command prefix exactly.
            Improve the draft by making the goal, scope, constraints, and desired output explicit when they are implied by the draft.
            For vague quality words such as 'better', 'good-looking', or 'polished', translate them into concrete but generic quality criteria.
            Do not invent facts, business context, tools, file names, dates, metrics, or user preferences that are not implied.
            Prefer one concise paragraph or a short bullet list. Keep it under 180 words unless the original draft is longer.
            Output only the rewritten draft, with no markdown wrapper, explanation, or alternatives.""";
    private final ObjectMapper json;
    private final Function<JsonNode, HaifaRunBridge.ModelBinding> modelResolver;
    private final int maxChars;
    private final BooleanSupplier runCreatePermission;

    DeerFlowInputPolishApi(
            ObjectMapper json, Function<JsonNode, HaifaRunBridge.ModelBinding> modelResolver) {
        this(json, modelResolver, DEFAULT_MAX_CHARS, () -> true);
    }

    DeerFlowInputPolishApi(
            ObjectMapper json,
            Function<JsonNode, HaifaRunBridge.ModelBinding> modelResolver,
            int maxChars,
            BooleanSupplier runCreatePermission) {
        this.json = json;
        this.modelResolver = modelResolver;
        this.maxChars = maxChars;
        this.runCreatePermission = runCreatePermission;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals(PATH)) {
            send(exchange, 404, Map.of("detail", "Not Found"));
            return;
        }
        if (!exchange.getRequestMethod().equals("POST")) {
            exchange.getResponseHeaders().set("Allow", "POST");
            send(exchange, 405, Map.of("detail", "Method Not Allowed"));
            return;
        }
        if (!isLoopbackPeer(exchange)) {
            send(exchange, 403, Map.of("detail", "Local profile accepts loopback clients only"));
            return;
        }
        if (!runCreatePermission.getAsBoolean()) {
            send(exchange, 403, Map.of("detail", "Forbidden"));
            return;
        }
        ObjectNode body;
        try {
            body = readObject(exchange);
        } catch (PayloadTooLargeException tooLarge) {
            send(exchange, 413, Map.of("detail", "Request body exceeds 131072 bytes"));
            return;
        } catch (InvalidRequestException invalid) {
            send(exchange, invalid.status, Map.of("detail", invalid.getMessage()));
            return;
        }
        try {
            validateFields(body);
            String text = normalizeText(body);
            String locale = optionalText(body, "locale");
            optionalText(body, "thread_id");
            String rewritten = polish(text, locale);
            if (rewritten.isEmpty()) {
                send(exchange, 503, Map.of("detail", "Failed to polish input"));
                return;
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("rewritten_text", rewritten);
            response.put("changed", !rewritten.equals(text));
            send(exchange, 200, response);
        } catch (InvalidRequestException invalid) {
            send(exchange, invalid.status, Map.of("detail", invalid.getMessage()));
        } catch (Exception failure) {
            send(exchange, 503, Map.of("detail", "Failed to polish input"));
        }
    }

    private String polish(String text, String locale) {
        HaifaRunBridge.ModelBinding binding = modelResolver.apply(json.createObjectNode());
        ResolvedModelSnapshot snapshot = binding.snapshot();
        int maxOutputTokens = Math.min(snapshot.maxOutputTokens(), DEFAULT_MAX_OUTPUT_TOKENS);
        String callId = "input-polish-" + UUID.randomUUID();
        AgentChatRequest request = new AgentChatRequest(
                new ModelCallId(callId),
                new AgentRunId(callId),
                1,
                1,
                snapshot,
                List.of(
                        ModelMessage.text(ModelMessageRole.SYSTEM, SYSTEM_INSTRUCTION),
                        ModelMessage.text(ModelMessageRole.USER, userContent(text, locale))),
                List.of(),
                maxOutputTokens,
                MODEL_TIMEOUT,
                Map.of());
        AgentChatResponse response = binding.model().invoke(request);
        String content = response.content();
        return clean(content == null ? "" : content);
    }

    static String clean(String text) {
        String candidate = COMPLETE_THINK_BLOCK.matcher(text).replaceAll("");
        candidate = stripMarkdownCodeFence(candidate);
        return candidate.strip();
    }

    private static String stripMarkdownCodeFence(String text) {
        String stripped = text.strip();
        if (!stripped.startsWith("```")) {
            return stripped;
        }
        String[] lines = stripped.split("\n", -1);
        if (lines.length >= 3
                && lines[0].startsWith("```")
                && lines[lines.length - 1].startsWith("```")) {
            return String.join("\n", Arrays.copyOfRange(lines, 1, lines.length - 1))
                    .strip();
        }
        return stripped;
    }

    private static String userContent(String text, String locale) {
        String localeHint = locale == null || locale.isBlank()
                ? "same language as the draft"
                : locale.strip();
        return "Locale hint: " + localeHint
                + "\n\nRewrite this draft while preserving its intent:\n<draft>\n" + text
                + "\n</draft>";
    }

    private static void validateFields(ObjectNode body) {
        body.fieldNames().forEachRemaining(field -> {
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new InvalidRequestException(422, "Unknown field '" + field + "'");
            }
        });
    }

    private String normalizeText(ObjectNode body) {
        JsonNode value = body.get("text");
        if (value == null || !value.isTextual()) {
            throw new InvalidRequestException(422, "text must be a string");
        }
        String text = value.asText().strip();
        if (text.isEmpty()) {
            throw new InvalidRequestException(400, "Input text is required");
        }
        if (text.length() > maxChars) {
            throw new InvalidRequestException(
                    400, "Input text exceeds " + maxChars + " characters");
        }
        return text;
    }

    private static String optionalText(ObjectNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new InvalidRequestException(422, field + " must be a string or null");
        }
        return value.asText();
    }

    private ObjectNode readObject(HttpExchange exchange) throws IOException {
        byte[] payload = readBounded(exchange.getRequestBody());
        JsonNode node;
        try {
            node = json.readTree(payload);
        } catch (Exception exception) {
            throw new InvalidRequestException(422, "Invalid JSON body");
        }
        if (!(node instanceof ObjectNode object)) {
            throw new InvalidRequestException(422, "JSON body must be an object");
        }
        return object;
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int read; (read = input.read(buffer)) >= 0; ) {
            total += read;
            if (total > MAX_BODY_BYTES) {
                throw new PayloadTooLargeException();
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static boolean isLoopbackPeer(HttpExchange exchange) {
        return exchange.getRemoteAddress() != null
                && exchange.getRemoteAddress().getAddress() != null
                && exchange.getRemoteAddress().getAddress().isLoopbackAddress();
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, payload.length);
        try (var response = exchange.getResponseBody()) {
            response.write(payload);
        }
    }

    private static final class InvalidRequestException extends RuntimeException {
        private final int status;

        private InvalidRequestException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static final class PayloadTooLargeException extends IOException {}
}

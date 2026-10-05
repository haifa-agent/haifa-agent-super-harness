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
import io.haifa.agent.superharness.server.api.dto.SuggestionsConfigResponse;
import io.haifa.agent.superharness.server.api.dto.SuggestionsResponse;
import io.haifa.agent.superharness.server.run.HaifaRunBridge;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Frozen DeerFlow follow-up suggestions compatibility routes.
 *
 * <p>Only the global config read and the per-thread generation endpoint are implemented. The
 * generation call is a single bounded one-shot model call; it never creates Thread/Run/Tool facts.
 * Provider or parsing failures yield an empty list, matching the frozen router.
 */
final class DeerFlowSuggestionsApi implements HttpHandler {
    static final String CONFIG_PATH = "/api/suggestions/config";
    static final Pattern THREAD_SUGGESTIONS_PATH =
            Pattern.compile("^/api/threads/([^/]+)/suggestions$");
    static final int DEFAULT_MAX_SUGGESTIONS = 3;
    static final int MAX_SUGGESTIONS_LIMIT = 5;

    private static final int MAX_BODY_BYTES = 128 * 1024;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 1_024;
    private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(60);

    private final ObjectMapper json;
    private final Function<JsonNode, HaifaRunBridge.ModelBinding> modelResolver;
    private final Predicate<String> threadExists;
    private final BooleanSupplier threadReadPermission;
    private final boolean enabled;
    private final int configuredMaxSuggestions;

    DeerFlowSuggestionsApi(
            ObjectMapper json,
            Function<JsonNode, HaifaRunBridge.ModelBinding> modelResolver,
            Predicate<String> threadExists,
            Settings settings) {
        this(json, modelResolver, threadExists, settings.enabled(), settings.maxSuggestions(), () -> true);
    }

    DeerFlowSuggestionsApi(
            ObjectMapper json,
            Function<JsonNode, HaifaRunBridge.ModelBinding> modelResolver,
            Predicate<String> threadExists,
            boolean enabled,
            int configuredMaxSuggestions,
            BooleanSupplier threadReadPermission) {
        this.json = json;
        this.modelResolver = modelResolver;
        this.threadExists = threadExists;
        this.enabled = enabled;
        this.configuredMaxSuggestions = clampMaxSuggestions(configuredMaxSuggestions);
        this.threadReadPermission = threadReadPermission;
    }

    /** Returns true when this handler owns the exchange, so the bootstrap context does not fall through. */
    boolean tryHandle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals(CONFIG_PATH)) {
            handle(exchange);
            return true;
        }
        if (THREAD_SUGGESTIONS_PATH.matcher(path).matches()) {
            handle(exchange);
            return true;
        }
        return false;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!isLoopbackPeer(exchange)) {
            send(exchange, 403, Map.of("detail", "Local profile accepts loopback clients only"));
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (path.equals(CONFIG_PATH)) {
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.getResponseHeaders().set("Allow", "GET");
                send(exchange, 405, Map.of("detail", "Method Not Allowed"));
                return;
            }
            send(
                    exchange,
                    200,
                    new SuggestionsConfigResponse(enabled, configuredMaxSuggestions));
            return;
        }
        Matcher match = THREAD_SUGGESTIONS_PATH.matcher(path);
        if (!match.matches()) {
            send(exchange, 404, Map.of("detail", "Not Found"));
            return;
        }
        if (!exchange.getRequestMethod().equals("POST")) {
            exchange.getResponseHeaders().set("Allow", "POST");
            send(exchange, 405, Map.of("detail", "Method Not Allowed"));
            return;
        }
        generateSuggestions(exchange, match.group(1));
    }

    private void generateSuggestions(HttpExchange exchange, String threadId) throws IOException {
        if (!threadExists.test(threadId)) {
            send(exchange, 404, Map.of("detail", "Thread not found"));
            return;
        }
        if (!threadReadPermission.getAsBoolean()) {
            send(exchange, 403, Map.of("detail", "Forbidden"));
            return;
        }
        if (!enabled) {
            send(exchange, 200, new SuggestionsResponse(List.of()));
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

        List<ConversationMessage> messages;
        int requested;
        String modelName;
        try {
            messages = readMessages(body);
            requested = readN(body);
            modelName = readModelName(body);
        } catch (InvalidRequestException invalid) {
            send(exchange, invalid.status, Map.of("detail", invalid.getMessage()));
            return;
        }

        if (messages.isEmpty()) {
            send(exchange, 200, new SuggestionsResponse(List.of()));
            return;
        }
        int n = Math.min(requested, configuredMaxSuggestions);
        String conversation = formatConversation(messages);
        if (conversation.isEmpty()) {
            send(exchange, 200, new SuggestionsResponse(List.of()));
            return;
        }
        List<String> suggestions = generate(conversation, n, modelName);
        send(exchange, 200, new SuggestionsResponse(suggestions));
    }

    private List<String> generate(String conversation, int n, String modelName) {
        try {
            ObjectNode resolverBody = json.createObjectNode();
            ObjectNode context = resolverBody.putObject("context").put("mode", "flash").put("thinking_enabled", false);
            if (modelName != null) context.put("model_name", modelName);
            HaifaRunBridge.ModelBinding binding = modelResolver.apply(resolverBody);
            ResolvedModelSnapshot snapshot = binding.snapshot();
            int maxOutputTokens = Math.min(snapshot.maxOutputTokens(), DEFAULT_MAX_OUTPUT_TOKENS);
            String callId = "suggest-agent-" + UUID.randomUUID();
            AgentChatRequest request = new AgentChatRequest(
                    new ModelCallId(callId),
                    new AgentRunId(callId),
                    1,
                    1,
                    snapshot,
                    List.of(
                            ModelMessage.text(ModelMessageRole.SYSTEM, systemInstruction(n)),
                            ModelMessage.text(ModelMessageRole.USER, userContent(conversation, n))),
                    List.of(),
                    maxOutputTokens,
                    MODEL_TIMEOUT,
                    Map.of());
            AgentChatResponse response = binding.model().invoke(request);
            String raw = response.content();
            return cleanSuggestions(raw, n);
        } catch (RuntimeException failure) {
            // Provider, credential and parser failures collapse to the frozen empty result. Never
            // reflect exception text, endpoints or credentials back to the client.
            return List.of();
        }
    }

    static String systemInstruction(int n) {
        return "You are generating follow-up questions to help the user continue the conversation.\n"
                + "Based on the conversation below, produce EXACTLY " + n
                + " short questions the user might ask next.\n"
                + "Requirements:\n"
                + "- Questions must be relevant to the preceding conversation.\n"
                + "- Questions must be written in the same language as the user.\n"
                + "- Keep each question concise (ideally <= 20 words / <= 40 Chinese characters).\n"
                + "- Do NOT include numbering, markdown, or any extra text.\n"
                + "- Output MUST be a JSON array of strings only.\n";
    }

    static String userContent(String conversation, int n) {
        return "Conversation Context:\n" + conversation + "\n\nGenerate " + n + " follow-up questions";
    }

    static List<String> cleanSuggestions(String raw, int n) {
        List<String> parsed = parseJsonStringList(raw);
        List<String> cleaned = new ArrayList<>();
        for (String item : parsed) {
            String value = item.replace("\n", " ").strip();
            if (!value.isEmpty()) {
                cleaned.add(value);
            }
            if (cleaned.size() == n) {
                break;
            }
        }
        return cleaned;
    }

    static List<String> parseJsonStringList(String text) {
        String candidate = DeerFlowInputPolishApi.clean(text == null ? "" : text);
        int start = candidate.indexOf('[');
        int end = candidate.lastIndexOf(']');
        if (start == -1 || end == -1 || end <= start) {
            return List.of();
        }
        JsonNode data;
        try {
            data = MAPPER.readTree(candidate.substring(start, end + 1));
        } catch (IOException | RuntimeException invalid) {
            return List.of();
        }
        if (!data.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : data) {
            if (!item.isTextual()) {
                continue;
            }
            String value = item.asText().strip();
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static String formatConversation(List<ConversationMessage> messages) {
        List<String> parts = new ArrayList<>();
        for (ConversationMessage message : messages) {
            String role = message.role().strip().toLowerCase(Locale.ROOT);
            String content = message.content().strip();
            if (role.equals("user") || role.equals("human")) {
                parts.add("User: " + content);
            } else if (role.equals("assistant") || role.equals("ai")) {
                parts.add("Assistant: " + content);
            } else {
                parts.add(message.role() + ": " + content);
            }
        }
        return String.join("\n", parts).strip();
    }

    private List<ConversationMessage> readMessages(ObjectNode body) {
        JsonNode messages = body.get("messages");
        if (messages == null || messages.isNull()) {
            throw new InvalidRequestException(422, "messages is required");
        }
        if (!messages.isArray()) {
            throw new InvalidRequestException(422, "messages must be an array");
        }
        List<ConversationMessage> result = new ArrayList<>();
        for (JsonNode node : messages) {
            if (!node.isObject()) {
                throw new InvalidRequestException(422, "messages entries must be objects");
            }
            // Pydantic ignores unknown message fields by default.
            JsonNode role = node.get("role");
            JsonNode content = node.get("content");
            if (role == null || !role.isTextual()) {
                throw new InvalidRequestException(422, "messages[].role must be a string");
            }
            if (content == null || !content.isTextual()) {
                throw new InvalidRequestException(422, "messages[].content must be a string");
            }
            result.add(new ConversationMessage(role.asText(), content.asText()));
        }
        return result;
    }

    private int readN(ObjectNode body) {
        JsonNode n = body.get("n");
        if (n == null || n.isNull()) {
            return DEFAULT_MAX_SUGGESTIONS;
        }
        if (!n.isIntegralNumber() || !n.canConvertToInt()) {
            throw new InvalidRequestException(422, "n must be an integer");
        }
        int value = n.intValue();
        if (value < 1 || value > MAX_SUGGESTIONS_LIMIT) {
            throw new InvalidRequestException(
                    422, "n must be between 1 and " + MAX_SUGGESTIONS_LIMIT);
        }
        return value;
    }

    private String readModelName(ObjectNode body) {
        JsonNode value = body.get("model_name");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new InvalidRequestException(422, "model_name must be a string or null");
        }
        return value.asText();
    }

    static int clampMaxSuggestions(int value) {
        if (value < 1) return DEFAULT_MAX_SUGGESTIONS;
        return Math.min(value, MAX_SUGGESTIONS_LIMIT);
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

    private static Map<String, Object> orderedMap(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }

    record ConversationMessage(String role, String content) {}

    /** Suggestions configuration resolved once at process start. */
    record Settings(boolean enabled, int maxSuggestions) {
        static final String ENABLED_ENV = "HAIFA_SUPER_HARNESS_SUGGESTIONS_ENABLED";
        static final String MAX_ENV = "HAIFA_SUPER_HARNESS_SUGGESTIONS_MAX";

        static Settings defaults() {
            return new Settings(true, DEFAULT_MAX_SUGGESTIONS);
        }

        static Settings fromEnvironment(Map<String, String> environment) {
            boolean enabled = true;
            String rawEnabled = environment.get(ENABLED_ENV);
            if (rawEnabled != null && !rawEnabled.isBlank()) {
                String normalized = rawEnabled.strip().toLowerCase(Locale.ROOT);
                enabled = !(normalized.equals("0") || normalized.equals("false") || normalized.equals("no"));
            }
            int max = DEFAULT_MAX_SUGGESTIONS;
            String rawMax = environment.get(MAX_ENV);
            if (rawMax != null && !rawMax.isBlank()) {
                try {
                    max = clampMaxSuggestions(Integer.parseInt(rawMax.strip()));
                } catch (NumberFormatException ignored) {
                    max = DEFAULT_MAX_SUGGESTIONS;
                }
            }
            return new Settings(enabled, max);
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

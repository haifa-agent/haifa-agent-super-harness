package io.haifa.agent.superharness.server.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.haifa.agent.memory.api.Memory;
import io.haifa.agent.memory.api.MemoryActor;
import io.haifa.agent.memory.api.MemoryDraft;
import io.haifa.agent.memory.api.MemoryId;
import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemoryOperationException;
import io.haifa.agent.memory.api.MemoryPage;
import io.haifa.agent.memory.api.MemoryPageCursor;
import io.haifa.agent.memory.api.MemoryQuery;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.api.MemoryService;
import io.haifa.agent.memory.api.MemorySourceRef;
import io.haifa.agent.memory.api.MemorySourceType;
import io.haifa.agent.superharness.server.auth.AccessOwner;
import io.haifa.agent.superharness.server.auth.AccountSdkIdentity;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Frozen DeerFlow memory settings backed by the signed-in account's SDK USER scope. */
final class DeerFlowMemoryApi implements HttpHandler {
    static final String PATH = "/api/memory";
    private static final String FACTS = PATH + "/facts";
    private static final int MAX_BODY_BYTES = 32 * 1024;
    private static final int PAGE_SIZE = 100;
    private static final double CONFIDENCE = 0.8;
    private static final Set<String> FIELDS = Set.of("content", "category", "confidence");

    private final ObjectMapper json;
    private final MemoryService memories;

    DeerFlowMemoryApi(ObjectMapper json, MemoryService memories) {
        this.json = json;
        this.memories = memories;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String owner = AccessOwner.current();
        if (AccessOwner.DEFAULT.equals(owner)) {
            error(exchange, 401, "Sign in to manage memory.");
            return;
        }
        var caller = AccountSdkIdentity.forOwner(owner);
        var actor = new MemoryActor(caller.tenant(), caller.principal());
        var scope = MemoryScope.user(caller.tenant(), caller.principal());
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        boolean committed = false;
        JsonNode result;
        try {
            if (path.equals(PATH) && method.equals("GET") || path.equals(PATH + "/export") && method.equals("GET")) {
                result = view(scope, actor);
            } else {
                mutate(exchange, path, method, actor, scope);
                committed = true;
                result = view(scope, actor);
            }
        } catch (RequestError invalid) {
            error(exchange, invalid.status, invalid.getMessage());
            return;
        } catch (MemoryOperationException failure) {
            if (committed) {
                error(exchange, 503, "The memory change was saved. Reload memory to see the current state.");
            } else {
                switch (failure.code()) {
                    case "MEMORY_UNAVAILABLE", "MEMORY_SCOPE_UNAVAILABLE" -> error(exchange, 404, "Memory fact not found.");
                    case "MEMORY_REVISION_STALE", "MEMORY_WRITE_STALE" -> error(exchange, 409, "Memory changed. Reload it and try again.");
                    case "MEMORY_CONTENT_SENSITIVE" -> error(exchange, 422, "Memory cannot contain credentials or other secrets.");
                    default -> error(exchange, 503, "Memory is unavailable.");
                }
            }
            return;
        } catch (IllegalArgumentException invalid) {
            error(exchange, committed ? 503 : 422, committed
                    ? "The memory change was saved. Reload memory to see the current state."
                    : "Invalid memory fact.");
            return;
        } catch (RuntimeException unavailable) {
            error(exchange, 503, committed
                    ? "The memory change was saved. Reload memory to see the current state."
                    : "Memory is unavailable.");
            return;
        }
        json(exchange, 200, result);
    }

    private void mutate(HttpExchange exchange, String path, String method, MemoryActor actor, MemoryScope scope)
            throws IOException {
        if (path.equals(PATH + "/import") && method.equals("POST")) {
            throw new RequestError(501, "Memory import is unavailable in this version.");
        }
        if (path.equals(PATH) && method.equals("DELETE")) {
            memories.clear(scope, actor);
            return;
        }
        if (path.equals(FACTS) && method.equals("POST")) {
            Instant observedAt = Instant.now();
            JsonNode body = factInput(exchange);
            MemoryKind kind = switch (text(body, "category")) {
                case "context" -> MemoryKind.FACT;
                case "preference" -> MemoryKind.PREFERENCE;
                default -> throw new RequestError(422, "Use the context or preference category.");
            };
            checkConfidence(body);
            String subject = UUID.randomUUID().toString();
            memories.put(new MemoryDraft(scope, kind, subject, text(body, "content"),
                    Optional.of(new MemorySourceRef(MemorySourceType.EXPLICIT_USER_COMMAND, subject)),
                    Optional.of(observedAt)), actor);
            return;
        }
        if (path.startsWith(FACTS + "/") && path.indexOf('/', FACTS.length() + 1) == -1
                && (method.equals("PATCH") || method.equals("DELETE"))) {
            Memory current = find(path.substring(FACTS.length() + 1), scope, actor);
            if (method.equals("DELETE")) {
                memories.delete(current.id(), current.revision(), actor);
                return;
            }
            JsonNode body = factInput(exchange);
            if (body.has("category") && !category(current).equals(text(body, "category"))) {
                throw new RequestError(422, "The category of a saved fact cannot be changed.");
            }
            if (body.has("confidence")) checkConfidence(body);
            memories.update(current.id(), current.revision(), text(body, "content"), actor);
            return;
        }
        throw new RequestError(404, "Not Found");
    }

    private Memory find(String id, MemoryScope scope, MemoryActor actor) {
        MemoryId memoryId;
        try {
            memoryId = new MemoryId(id);
        } catch (IllegalArgumentException invalid) {
            throw new RequestError(404, "Memory fact not found.");
        }
        return memories.find(memoryId, actor)
                .filter(memory -> scope.equals(memory.scope()))
                .orElseThrow(() -> new RequestError(404, "Memory fact not found."));
    }

    private JsonNode factInput(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) throw new RequestError(413, "Memory request is too large.");
        JsonNode body;
        try {
            body = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
        } catch (com.fasterxml.jackson.core.JacksonException invalid) {
            throw new RequestError(422, "Send a JSON memory fact.");
        }
        if (body == null || !body.isObject() || body.propertyStream().anyMatch(field -> !FIELDS.contains(field.getKey()))) {
            throw new RequestError(422, "Only content, category, and confidence are supported.");
        }
        return body;
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new RequestError(422, "A nonempty " + field + " is required.");
        }
        return value.textValue().strip();
    }

    private static void checkConfidence(JsonNode body) {
        JsonNode value = body.path("confidence");
        if (!value.isNumber() || value.decimalValue().compareTo(java.math.BigDecimal.valueOf(CONFIDENCE)) != 0) {
            throw new RequestError(422, "This version supports confidence 0.8 for manually saved facts.");
        }
    }

    private JsonNode view(MemoryScope scope, MemoryActor actor) {
        var result = json.createObjectNode();
        result.put("version", "1.0");
        var section = Map.of("summary", "", "updatedAt", Instant.EPOCH.toString());
        result.set("user", json.valueToTree(Map.of(
                "workContext", section, "personalContext", section, "topOfMind", section)));
        result.set("history", json.valueToTree(Map.of(
                "recentMonths", section, "earlierContext", section, "longTermBackground", section)));
        var facts = result.putArray("facts");
        Instant updated = Instant.EPOCH;
        Optional<MemoryPageCursor> after = Optional.empty();
        do {
            MemoryPage page = memories.list(new MemoryQuery(scope, Set.of(), Optional.empty(), after, PAGE_SIZE), actor);
            for (Memory memory : page.items()) {
                var fact = facts.addObject();
                fact.put("id", memory.id().value());
                fact.put("content", memory.content());
                fact.put("category", category(memory));
                fact.put("confidence", CONFIDENCE);
                fact.put("createdAt", memory.createdAt().toString());
                fact.put("source", "manual");
                if (memory.updatedAt().isAfter(updated)) updated = memory.updatedAt();
            }
            after = page.nextCursor();
        } while (after.isPresent());
        result.put("lastUpdated", updated.toString());
        return result;
    }

    private static String category(Memory memory) {
        return memory.kind() == MemoryKind.PREFERENCE ? "preference" : "context";
    }

    private void error(HttpExchange exchange, int status, String detail) throws IOException {
        json(exchange, status, Map.of("detail", detail));
    }

    private void json(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] bytes = json.writeValueAsBytes(payload);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var response = exchange.getResponseBody()) {
            response.write(bytes);
        }
    }

    private static final class RequestError extends RuntimeException {
        private final int status;

        RequestError(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}

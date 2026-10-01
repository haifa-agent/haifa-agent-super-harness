package io.haifa.agent.superharness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.haifa.agent.auth.localmodel.ExternalLoginRegistry;
import io.haifa.agent.auth.localmodel.LocalModelCredentialResolver;
import io.haifa.agent.auth.localmodel.WindowsLocalModelAuthStore;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.CredentialResolver;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelStreamControl;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.openai.OpenAiCompatibleChatModel;
import io.haifa.agent.model.openai.OpenAiCompatibleDialects;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Frozen DeerFlow model and custom-agent REST contracts for the local owner. */
final class DeerFlowConfigurationApi implements HttpHandler {
    private static final int MAX_BODY_BYTES = 128 * 1024;
    private static final int MAX_SOUL_CHARS = 64 * 1024;
    private static final Pattern AGENT_PATH = Pattern.compile("^/api/agents/([^/]+)$");
    private static final Pattern AGENT_NAME = Pattern.compile("^[A-Za-z0-9-]+$");
    private static final Pattern MODEL_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$");
    private static final Pattern OS_CREDENTIAL_REF =
            Pattern.compile("^os://[A-Za-z_][A-Za-z0-9_-]*$");
    private static final URI DEEPSEEK_ENDPOINT = URI.create("https://api.deepseek.com");
    private static final Set<String> REASONING_EFFORTS = Set.of("low", "medium", "high");
    private static final List<String> AGENT_FIELDS = List.of(
            "display_name",
            "description",
            "model",
            "tool_groups",
            "skills",
            "mcp_plugins",
            "knowledge_scope",
            "allowed_subagents",
            "model_settings",
            "thinking_enabled",
            "reasoning_effort",
            "soul");
    private static final int DEFAULT_CONTEXT_WINDOW = 8_192;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 1_024;
    private static final int MAX_PROVIDER_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final ObjectMapper json;
    private final ConfigurationStore store;
    private final ProbeTransport probeTransport;
    private final CredentialResolver externalCredentialResolver;

    DeerFlowConfigurationApi(ObjectMapper json, Path dataDirectory) throws IOException {
        this(json, dataDirectory, null, operatingSystemCredentialResolver(json));
    }

    DeerFlowConfigurationApi(ObjectMapper json, Path dataDirectory, ProbeTransport probeTransport)
            throws IOException {
        this(json, dataDirectory, probeTransport, operatingSystemCredentialResolver(json));
    }

    DeerFlowConfigurationApi(
            ObjectMapper json,
            Path dataDirectory,
            ProbeTransport probeTransport,
            CredentialResolver externalCredentialResolver)
            throws IOException {
        this.json = json;
        this.store = new ConfigurationStore(json, dataDirectory.resolve("owners").resolve("default"));
        this.probeTransport = probeTransport == null ? this::invokeToolCallProbe : probeTransport;
        this.externalCredentialResolver = java.util.Objects.requireNonNull(
                externalCredentialResolver, "externalCredentialResolver must not be null");
    }

    HaifaRunBridge.ModelBinding resolveRunModel(JsonNode body) {
        JsonNode context = body.path("context");
        boolean bootstrap = isBootstrap(body);
        if (!context.isMissingNode() && !context.isNull() && !context.isObject()) {
            throw new IllegalArgumentException("context must be an object");
        }
        String mode = context.path("mode").asText("flash");
        if (!Set.of("flash", "thinking", "pro", "ultra").contains(mode)) {
            throw new IllegalArgumentException("Unsupported mode '" + mode + "'");
        }
        if (!mode.equals("flash")) {
            throw new IllegalArgumentException(
                    "Mode '" + mode + "' requires an available reasoning-capable model");
        }
        // The frozen composer sends "minimal" when Flash is selected, including for
        // models that do not expose reasoning effort. It is the no-reasoning value,
        // not a request to enable a reasoning-capable mode.
        if (context.has("reasoning_effort") && !context.get("reasoning_effort").isNull()
                && !"minimal".equals(context.get("reasoning_effort").asText())) {
            throw new IllegalArgumentException("reasoning_effort is not supported in flash mode");
        }
        rejectUnavailableBooleanCapability(context, "thinking_enabled", "thinking");
        rejectUnavailableBooleanCapability(context, "is_plan_mode", "plan mode");
        rejectUnavailableBooleanCapability(context, "subagent_enabled", "subagent delegation");

        String requestedModel = optionalNonBlankText(context.get("model_name"), "context.model_name");
        String agentName = selectedAgentName(body);
        String agentModel = null;
        if (agentName != null && !bootstrap) {
            String selectedAgentName = agentName.toLowerCase(java.util.Locale.ROOT);
            ObjectNode agent = store.agent(selectedAgentName)
                    .orElseThrow(
                            () -> new IllegalArgumentException("Agent '" + selectedAgentName + "' not found"));
            agentModel = nullableStoredText(agent.get("model"));
        }
        String selectedName = requestedModel != null ? requestedModel : agentModel;
        ObjectNode selected = selectedName == null
                ? store.defaultExecutableModel().orElseThrow(
                        () -> new IllegalArgumentException("No enabled managed model with a credential is available"))
                : store.executableModel(selectedName)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Model '" + selectedName + "' is not configured and usable"));
        return binding(selected);
    }

    /**
     * Frozen {@code Agent.mcp_plugins} tri-state for the Run's selected Agent:
     * {@code null}=all enabled installations, {@code []}=none, list=only those ids.
     * A Run without a selected Agent uses the all-enabled default.
     */
    McpRunTools.Selection resolveMcpPluginSelection(JsonNode body) {
        String agentName = selectedAgentName(body);
        if (isBootstrap(body)) return McpRunTools.Selection.none();
        if (agentName == null) {
            return McpRunTools.Selection.all();
        }
        String selected = agentName.toLowerCase(java.util.Locale.ROOT);
        ObjectNode agent = store.agent(selected)
                .orElseThrow(() -> new IllegalArgumentException("Agent '" + selected + "' not found"));
        return McpRunTools.Selection.fromJson(agent.get("mcp_plugins"));
    }

    /** Frozen Agent.skills: null selects all enabled packages, [] selects none. */
    SkillRunTools.Selection resolveSkillSelection(JsonNode body) {
        String agentName = selectedAgentName(body);
        if (isBootstrap(body)) return SkillRunTools.Selection.none();
        if (agentName == null) return SkillRunTools.Selection.all();
        String selected = agentName.toLowerCase(java.util.Locale.ROOT);
        ObjectNode agent = store.agent(selected)
                .orElseThrow(() -> new IllegalArgumentException("Agent '" + selected + "' not found"));
        return SkillRunTools.Selection.fromJson(agent.get("skills"));
    }

    static boolean isBootstrap(JsonNode body) {
        JsonNode context = body.path("context");
        if (!context.isMissingNode() && !context.isNull() && !context.isObject()) {
            throw new IllegalArgumentException("context must be an object");
        }
        JsonNode value = context.get("is_bootstrap");
        if (value == null || value.isNull()) return false;
        if (!value.isBoolean()) throw new IllegalArgumentException("context.is_bootstrap must be boolean");
        return value.booleanValue();
    }

    AgentPromptContext resolveAgentPromptContext(JsonNode body) {
        return restoreAgentPromptContext(selectedAgentName(body), isBootstrap(body));
    }

    AgentPromptContext restoreAgentPromptContext(String agentName, boolean bootstrap) {
        if (agentName == null || agentName.isEmpty()) return new AgentPromptContext(null, bootstrap, null);
        if (!AGENT_NAME.matcher(agentName).matches()) throw new IllegalArgumentException("Invalid selected agent identity");
        String normalized = agentName.toLowerCase(java.util.Locale.ROOT);
        if (bootstrap) return new AgentPromptContext(normalized, true, null);
        ObjectNode agent = store.agent(normalized)
                .orElseThrow(() -> new IllegalArgumentException("Selected agent is unavailable"));
        return new AgentPromptContext(normalized, false, agent.path("soul").asText(""));
    }

    ObjectNode setupAgentForTool(String agentName, ProductionSetupAgentTool.Input input,
            java.util.function.Consumer<ObjectNode> candidateValidator) {
        validateAgentName(agentName);
        if (agentName == null || agentName.isBlank() || input.soul() == null || input.soul().isBlank()) {
            throw new IllegalArgumentException("setup_agent requires a current identity and nonempty soul");
        }
        ObjectNode patch = json.createObjectNode();
        patch.put("soul", input.soul());
        if (input.description() != null && !input.description().isEmpty()) patch.put("description", input.description());
        input.skills().ifPresent(names -> patch.set("skills", json.valueToTree(names)));
        validateAgentFields(patch, false);
        String normalized = agentName.toLowerCase(java.util.Locale.ROOT);
        return store.writeAgentForTool(normalized, normalizeAgent(normalized, patch), patch, candidateValidator);
    }

    ObjectNode updateAgentForTool(String agentName, ProductionUpdateAgentTool.Input input,
            java.util.function.Consumer<ObjectNode> candidateValidator) {
        validateAgentName(agentName);
        if (agentName == null || agentName.isBlank()) throw new IllegalArgumentException("update_agent requires a current identity");
        ObjectNode patch = json.createObjectNode();
        input.soul().ifPresent(value -> {
            if (value.isBlank()) throw new IllegalArgumentException("soul must not be empty");
            patch.put("soul", value);
        });
        input.description().ifPresent(value -> patch.put("description", value));
        input.display_name().ifPresent(value -> patch.put("display_name", value));
        if (patch.isEmpty()) throw new IllegalArgumentException("no supported agent update fields");
        validateAgentFields(patch, false);
        return store.writeAgentForTool(agentName.toLowerCase(java.util.Locale.ROOT), null, patch, candidateValidator);
    }

    private static String selectedAgentName(JsonNode body) {
        JsonNode context = body.path("context");
        String agentName = optionalNonBlankText(context.get("agent_name"), "context.agent_name");
        String assistantId = optionalNonBlankText(body.get("assistant_id"), "assistant_id");
        if (agentName == null && assistantId != null && !assistantId.equals("lead_agent")) {
            agentName = assistantId.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
        if (agentName != null) {
            if (!AGENT_NAME.matcher(agentName).matches()) throw new IllegalArgumentException("Invalid selected agent identity");
        }
        return agentName == null ? null : agentName.toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!isLoopbackPeer(exchange)) {
                send(exchange, 403, Map.of("detail", "Local profile accepts loopback clients only"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/api/models") && method.equals("GET")) {
                listModels(exchange);
                return;
            }
            if (path.equals("/api/managed-models") && method.equals("GET")) {
                listManagedModels(exchange);
                return;
            }
            if (path.equals("/api/managed-models") && method.equals("PUT")) {
                saveManagedModel(exchange);
                return;
            }
            if (path.equals("/api/managed-models/test") && method.equals("POST")) {
                testManagedModel(exchange);
                return;
            }
            if (path.equals("/api/agents") && method.equals("GET")) {
                listAgents(exchange);
                return;
            }
            if (path.equals("/api/agents") && method.equals("POST")) {
                createAgent(exchange);
                return;
            }
            if (path.equals("/api/agents/check") && method.equals("GET")) {
                checkAgentName(exchange);
                return;
            }
            Matcher agent = AGENT_PATH.matcher(path);
            if (agent.matches() && method.equals("GET")) {
                getAgent(exchange, agent.group(1));
                return;
            }
            if (agent.matches() && method.equals("PUT")) {
                updateAgent(exchange, agent.group(1));
                return;
            }
            if (agent.matches() && method.equals("DELETE")) {
                deleteAgent(exchange, agent.group(1));
                return;
            }
            if (path.equals("/api/models")) {
                exchange.getResponseHeaders().set("Allow", "GET");
                send(exchange, 405, Map.of("detail", "Method Not Allowed"));
                return;
            }
            send(exchange, 404, Map.of("detail", "Not Found"));
        } catch (PayloadTooLargeException exception) {
            send(exchange, 413, Map.of("detail", "Request body exceeds 131072 bytes"));
        } catch (InvalidRequestException exception) {
            send(exchange, exception.status, Map.of("detail", exception.getMessage()));
        } catch (Exception exception) {
            send(exchange, 500, Map.of("detail", "Configuration operation failed"));
        }
    }

    private void listModels(HttpExchange exchange) throws IOException {
        ArrayNode models = json.createArrayNode();
        store.executableModels().stream().map(this::publicExecutableModel).forEach(models::add);
        ObjectNode response = json.createObjectNode();
        response.set("models", models);
        response.set("token_usage", json.createObjectNode().put("enabled", false));
        send(exchange, 200, response);
    }

    private void listManagedModels(HttpExchange exchange) throws IOException {
        ArrayNode models = json.createArrayNode();
        store.models().stream()
                .sorted(Comparator.comparing(node -> node.path("name").asText()))
                .map(this::publicModel)
                .forEach(models::add);
        send(exchange, 200, json.createObjectNode().set("models", models));
    }

    private void saveManagedModel(HttpExchange exchange) throws IOException {
        ObjectNode request = readObject(exchange);
        validateModelRequest(request);
        ObjectNode config = normalizeModel(requireObject(request, "config"));
        validateModel(config);
        String expectedRevision = nullableText(request.get("expected_revision"));
        ObjectNode saved = store.saveModel(config, expectedRevision);
        send(exchange, 200, publicModel(saved));
    }

    private void testManagedModel(HttpExchange exchange) throws IOException {
        ObjectNode request = readObject(exchange);
        validateModelRequest(request);
        ObjectNode config = normalizeModel(requireObject(request, "config"));
        validateModel(config);
        String expectedRevision = nullableText(request.get("expected_revision"));
        ObjectNode effective = store.modelForProbe(config, expectedRevision);
        if (!hasConfiguredCredential(effective)) {
            throw new InvalidRequestException(422, "Model credential is required for probe");
        }
        ProbeResult result = runToolCallProbe(effective);
        send(
                exchange,
                200,
                Map.of(
                        "ok", result == ProbeResult.SUCCESS,
                        "message", result.message));
    }

    private ProbeResult runToolCallProbe(ObjectNode config) {
        try {
            AgentChatResponse response = probeTransport.invoke(config);
            boolean hasExpectedToolCall = response.toolCalls().stream()
                    .anyMatch(call -> call.name().equals("connection_check") && call.arguments().isEmpty());
            return hasExpectedToolCall ? ProbeResult.SUCCESS : ProbeResult.TOOL_CALL_MISSING;
        } catch (Exception ignored) {
            // Provider errors may carry credentials, URLs or response bodies. Keep the frozen safe result.
            return ProbeResult.CONNECTION_FAILED;
        }
    }

    private AgentChatResponse invokeToolCallProbe(ObjectNode config) {
        HaifaRunBridge.ModelBinding binding = binding(config);
        var tool = new ModelToolSpecification(
                "connection_check",
                "1",
                "Check the connection",
                "connection-check-input",
                "1",
                Map.of("type", "object", "properties", Map.of()),
                true);
        var request = new AgentChatRequest(
                new ModelCallId("managed-model-probe"),
                new AgentRunId("managed-model-probe"),
                1,
                1,
                binding.snapshot(),
                List.of(ModelMessage.text(ModelMessageRole.USER, "Call connection_check.")),
                List.of(tool),
                Math.min(binding.snapshot().maxOutputTokens(), 256),
                Duration.ofSeconds(20),
                Map.of());
        return binding.model().invokeStreaming(request, event -> ModelStreamControl.CONTINUE);
    }

    private static void validateModelRequest(ObjectNode request) {
        rejectUnknownFields(request, Set.of("config", "expected_revision"));
    }

    private void listAgents(HttpExchange exchange) throws IOException {
        ArrayNode agents = json.createArrayNode();
        store.agents().stream()
                .sorted(Comparator.comparing(node -> node.path("name").asText()))
                .map(this::publicAgent)
                .forEach(agents::add);
        send(exchange, 200, json.createObjectNode().set("agents", agents));
    }

    private void checkAgentName(HttpExchange exchange) throws IOException {
        String name = queryParameter(exchange.getRequestURI().getRawQuery(), "name")
                .orElseThrow(() -> new InvalidRequestException(422, "Agent name is required"));
        validateAgentName(name);
        String normalized = name.toLowerCase(java.util.Locale.ROOT);
        send(
                exchange,
                200,
                Map.of("available", store.agent(normalized).isEmpty(), "name", normalized));
    }

    private void createAgent(HttpExchange exchange) throws IOException {
        ObjectNode request = readObject(exchange);
        String name = requireText(request, "name").toLowerCase(java.util.Locale.ROOT);
        validateAgentName(name);
        validateAgentFields(request, true);
        ObjectNode created = store.createAgent(normalizeAgent(name, request));
        send(exchange, 201, publicAgent(created));
    }

    private void getAgent(HttpExchange exchange, String name) throws IOException {
        validateAgentName(name);
        ObjectNode agent = store.agent(name.toLowerCase(java.util.Locale.ROOT))
                .orElseThrow(() -> new InvalidRequestException(404, "Agent '" + name + "' not found"));
        send(exchange, 200, publicAgent(agent));
    }

    private void updateAgent(HttpExchange exchange, String name) throws IOException {
        validateAgentName(name);
        ObjectNode request = readObject(exchange);
        validateAgentFields(request, false);
        ObjectNode updated = store.updateAgent(name.toLowerCase(java.util.Locale.ROOT), request);
        send(exchange, 200, publicAgent(updated));
    }

    private void deleteAgent(HttpExchange exchange, String name) throws IOException {
        validateAgentName(name);
        store.deleteAgent(name.toLowerCase(java.util.Locale.ROOT));
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private void validateModel(ObjectNode config) {
        rejectUnknownFields(
                config,
                Set.of(
                        "name",
                        "display_name",
                        "provider",
                        "model",
                        "base_url",
                        "api_key",
                        "credential_ref",
                        "enabled",
                        "supports_vision",
                        "context_window",
                        "max_tokens",
                        "revision"));
        String provider = requireText(config, "provider");
        if (!provider.equals("openai-compatible")) {
            throw new InvalidRequestException(422, "Only the public Haifa openai-compatible provider is supported");
        }
        String name = requireText(config, "name");
        if (!MODEL_NAME.matcher(name).matches()) {
            throw new InvalidRequestException(422, "Invalid model name");
        }
        if (config.path("display_name").asText().length() > 100) {
            throw new InvalidRequestException(422, "display_name exceeds 100 characters");
        }
        if (requireText(config, "model").length() > 200) {
            throw new InvalidRequestException(422, "model exceeds 200 characters");
        }
        if (config.path("base_url").asText().length() > 2048) {
            throw new InvalidRequestException(422, "base_url exceeds 2048 characters");
        }
        URI endpoint;
        try {
            endpoint = URI.create(requireText(config, "base_url"));
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException(422, "Invalid model base_url");
        }
        if (!(endpoint.getScheme().equals("http") || endpoint.getScheme().equals("https"))
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || endpoint.getPort() == 0) {
            throw new InvalidRequestException(422, "Model base_url must be an absolute HTTP(S) URL");
        }
        positiveOrNull(config, "context_window");
        positiveOrNull(config, "max_tokens");
        if (config.has("credential_ref") && !config.get("credential_ref").isNull()) {
            if (!config.get("credential_ref").isTextual()
                    || !OS_CREDENTIAL_REF.matcher(config.get("credential_ref").asText()).matches()) {
                throw new InvalidRequestException(422, "credential_ref must use os://NAME");
            }
        }
    }

    private ObjectNode normalizeModel(ObjectNode input) {
        ObjectNode config = input.deepCopy();
        if (!config.has("provider")) config.put("provider", "openai-compatible");
        if (!config.has("display_name")) config.put("display_name", "");
        if (!config.has("enabled")) config.put("enabled", true);
        if (!config.has("supports_vision")) config.put("supports_vision", false);
        if (!config.has("context_window")) config.putNull("context_window");
        if (!config.has("max_tokens")) config.putNull("max_tokens");
        for (String textField :
                List.of("name", "display_name", "provider", "model", "base_url", "credential_ref")) {
            if (config.has(textField) && config.get(textField).isTextual()) {
                config.put(textField, config.get(textField).asText().trim());
            }
        }
        if (config.has("base_url") && config.get("base_url").isTextual()) {
            config.put("base_url", config.get("base_url").asText().replaceFirst("/+$", ""));
        }
        for (String booleanField : List.of("enabled", "supports_vision")) {
            if (!config.get(booleanField).isBoolean()) {
                throw new InvalidRequestException(422, booleanField + " must be boolean");
            }
        }
        if (config.has("api_key") && !config.get("api_key").isNull() && !config.get("api_key").isTextual()) {
            throw new InvalidRequestException(422, "api_key must be a string or null");
        }
        return config;
    }

    private void validateAgentFields(ObjectNode request, boolean create) {
        Set<String> allowed = new java.util.HashSet<>(AGENT_FIELDS);
        if (create) allowed.add("name");
        rejectUnknownFields(request, allowed);
        if (request.has("soul") && !request.get("soul").isNull()) {
            if (!request.get("soul").isTextual() || request.get("soul").asText().length() > MAX_SOUL_CHARS) {
                throw new InvalidRequestException(413, "SOUL exceeds 65536 characters");
            }
        }
        if (request.has("model") && !request.get("model").isNull()) {
            String model = requireText(request, "model");
            if (!store.executableModelNames().contains(model)) {
                throw new InvalidRequestException(422, "Model '" + model + "' is not configured and usable");
            }
        }
        if (request.has("reasoning_effort") && !request.get("reasoning_effort").isNull()) {
            if (!REASONING_EFFORTS.contains(requireText(request, "reasoning_effort"))) {
                throw new InvalidRequestException(422, "Invalid reasoning_effort");
            }
        }
        if (request.has("thinking_enabled")
                && !request.get("thinking_enabled").isNull()
                && !request.get("thinking_enabled").isBoolean()) {
            throw new InvalidRequestException(422, "thinking_enabled must be boolean or null");
        }
        for (String field : List.of("tool_groups", "skills", "mcp_plugins", "allowed_subagents")) {
            if (request.has(field) && !request.get(field).isNull() && !request.get(field).isArray()) {
                throw new InvalidRequestException(422, field + " must be an array or null");
            }
            if (request.has(field) && request.get(field).isArray()) {
                request.get(field).forEach(value -> {
                    if (!value.isTextual()) {
                        throw new InvalidRequestException(422, field + " entries must be strings");
                    }
                });
            }
        }
        if (request.has("model_settings") && !request.get("model_settings").isNull()) {
            if (!(request.get("model_settings") instanceof ObjectNode settings)) {
                throw new InvalidRequestException(422, "model_settings must be an object or null");
            }
            rejectUnknownFields(settings, Set.of("temperature", "max_tokens"));
            JsonNode temperature = settings.get("temperature");
            if (temperature != null
                    && !temperature.isNull()
                    && (!temperature.isNumber()
                            || temperature.asDouble() < 0
                            || temperature.asDouble() > 2)) {
                throw new InvalidRequestException(422, "temperature must be between 0 and 2");
            }
            JsonNode maxTokens = settings.get("max_tokens");
            if (maxTokens != null
                    && !maxTokens.isNull()
                    && (!maxTokens.canConvertToInt()
                            || maxTokens.asInt() < 1
                            || maxTokens.asInt() > 200_000)) {
                throw new InvalidRequestException(422, "max_tokens must be between 1 and 200000");
            }
        }
        if (!create && request.has("name")) {
            throw new InvalidRequestException(422, "Agent name cannot be changed");
        }
    }

    private ObjectNode normalizeAgent(String name, ObjectNode request) {
        ObjectNode agent = json.createObjectNode();
        agent.put("name", name);
        agent.set("display_name", request.has("display_name") ? request.get("display_name") : json.nullNode());
        agent.put("description", request.path("description").asText(""));
        for (String field : AGENT_FIELDS.subList(2, AGENT_FIELDS.size() - 1)) {
            agent.set(field, request.has(field) ? request.get(field).deepCopy() : json.nullNode());
        }
        agent.put("soul", request.path("soul").asText(""));
        return agent;
    }

    private ObjectNode publicAgent(ObjectNode stored) {
        return stored.deepCopy();
    }

    private ObjectNode publicModel(ObjectNode stored) {
        ObjectNode result = json.createObjectNode();
        for (String field : List.of(
                "name",
                "display_name",
                "provider",
                "model",
                "base_url",
                "enabled",
                "supports_vision",
                "context_window",
                "max_tokens",
                "revision")) {
            result.set(field, stored.get(field));
        }
        result.put("source", "managed");
        result.put("has_api_key", hasText(stored, "api_key"));
        result.put("has_credential", hasConfiguredCredential(stored));
        result.put(
                "credential_source",
                hasText(stored, "credential_ref")
                        ? "os"
                        : hasText(stored, "api_key") ? "api_key" : "none");
        return result;
    }

    private ObjectNode publicExecutableModel(ObjectNode stored) {
        ObjectNode result = json.createObjectNode();
        result.put("id", stored.path("name").asText());
        result.put("name", stored.path("name").asText());
        result.put("model", stored.path("model").asText());
        String displayName = stored.path("display_name").asText();
        result.put("display_name", displayName.isBlank() ? stored.path("name").asText() : displayName);
        result.put("supports_thinking", false);
        result.put("supports_reasoning_effort", false);
        return result;
    }

    private HaifaRunBridge.ModelBinding binding(ObjectNode config) {
        String name = config.path("name").asText();
        String revision = config.path("revision").asText("unsaved-probe");
        URI endpoint = URI.create(config.path("base_url").asText());
        boolean allowInsecureHttp = "http".equalsIgnoreCase(endpoint.getScheme()) && isLiteralLoopback(endpoint);
        if ("http".equalsIgnoreCase(endpoint.getScheme()) && !allowInsecureHttp) {
            throw new IllegalArgumentException("Model '" + name + "' requires HTTPS outside loopback");
        }
        int contextWindow = positiveOr(config, "context_window", DEFAULT_CONTEXT_WINDOW);
        int maxOutputTokens = positiveOr(
                config, "max_tokens", Math.min(DEFAULT_MAX_OUTPUT_TOKENS, contextWindow));
        if (maxOutputTokens > contextWindow) {
            throw new IllegalArgumentException("Model '" + name + "' has incompatible token limits");
        }
        String credentialReference;
        CredentialResolver credentials;
        if (hasText(config, "credential_ref")) {
            credentialReference = config.path("credential_ref").asText();
            credentials = externalCredentialResolver;
        } else {
            String secret = config.path("api_key").asText();
            credentialReference = "managed://default/" + name;
            credentials = ignored -> new ResolvedCredential(secret);
        }
        try {
            credentials.resolve(new CredentialRef(credentialReference));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Model '" + name + "' credential is unavailable");
        }
        boolean deepSeek = DEEPSEEK_ENDPOINT.equals(endpoint);
        String dialect = deepSeek
                ? OpenAiCompatibleDialects.DEEPSEEK
                : ModelApiBindingDefinition.STANDARD_DIALECT;
        var model = new OpenAiCompatibleChatModel(
                "openai-compatible",
                "1.0.0",
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                json,
                credentials,
                allowInsecureHttp,
                MAX_PROVIDER_RESPONSE_BYTES);
        ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                new ModelProviderId("super-harness-managed-openai-compatible"),
                "0.1.1-SNAPSHOT",
                new ModelDefinitionId(name),
                revision,
                config.path("model").asText(),
                "openai-compatible",
                "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                dialect,
                endpoint,
                new CredentialRef(credentialReference),
                true,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                contextWindow,
                maxOutputTokens,
                Map.of(),
                deepSeek ? Map.of("thinking", "disabled") : Map.of());
        return new HaifaRunBridge.ModelBinding(name, revision, model, snapshot);
    }

    private static CredentialResolver operatingSystemCredentialResolver(ObjectMapper json) {
        return new LocalModelCredentialResolver(
                System::getenv,
                WindowsLocalModelAuthStore.defaultStore(json),
                new ExternalLoginRegistry(List.of()),
                Clock.systemUTC(),
                Duration.ZERO);
    }

    private static boolean isLiteralLoopback(URI endpoint) {
        String host = endpoint.getHost();
        if (host == null) return false;
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("::1")
                || normalized.equals("[::1]")
                || normalized.startsWith("127.");
    }

    private static String optionalNonBlankText(JsonNode value, String field) {
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
        return value.asText();
    }

    private static String nullableStoredText(JsonNode value) {
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static void rejectUnavailableBooleanCapability(
            JsonNode context, String field, String capability) {
        JsonNode value = context.get(field);
        if (value == null || value.isNull()) return;
        if (!value.isBoolean()) {
            throw new IllegalArgumentException("context." + field + " must be boolean");
        }
        if (value.asBoolean()) {
            throw new IllegalArgumentException("Configured managed models do not support " + capability);
        }
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

    private ObjectNode requireObject(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (!(value instanceof ObjectNode object)) {
            throw new InvalidRequestException(422, field + " must be an object");
        }
        return object;
    }

    private static String requireText(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new InvalidRequestException(422, field + " must be a non-empty string");
        }
        return value.asText();
    }

    private static String nullableText(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new InvalidRequestException(422, "expected_revision must be a string or null");
        }
        return value.asText();
    }

    private static void positiveOrNull(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value != null && !value.isNull() && (!value.canConvertToInt() || value.asInt() <= 0)) {
            throw new InvalidRequestException(422, field + " must be a positive integer or null");
        }
    }

    private static int positiveOr(ObjectNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        return value != null && value.canConvertToInt() && value.asInt() > 0 ? value.asInt() : fallback;
    }

    private static boolean hasText(ObjectNode node, String field) {
        return node.has(field) && node.get(field).isTextual() && !node.get(field).asText().isBlank();
    }

    private static boolean hasConfiguredCredential(ObjectNode node) {
        return hasText(node, "credential_ref") || hasText(node, "api_key");
    }

    private static void rejectUnknownFields(ObjectNode node, Set<String> allowed) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new InvalidRequestException(422, "Unknown field '" + field + "'");
            }
        });
    }

    private static void validateAgentName(String name) {
        if (!AGENT_NAME.matcher(name).matches()) {
            throw new InvalidRequestException(422, "Invalid agent name '" + name + "'");
        }
    }

    private static Optional<String> queryParameter(String rawQuery, String name) {
        if (rawQuery == null) return Optional.empty();
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            if (separator >= 0 && pair.substring(0, separator).equals(name)) {
                return Optional.of(java.net.URLDecoder.decode(
                        pair.substring(separator + 1), StandardCharsets.UTF_8));
            }
        }
        return Optional.empty();
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

    private static void mergeCredentialSelection(ObjectNode candidate, ObjectNode existing) {
        if (!candidate.has("credential_ref") && existing != null && hasText(existing, "credential_ref")) {
            candidate.put("credential_ref", existing.path("credential_ref").asText());
        }
        if (!candidate.has("api_key") && existing != null && hasText(existing, "api_key")) {
            candidate.put("api_key", existing.path("api_key").asText());
        }
        if (candidate.has("credential_ref") && candidate.get("credential_ref").isNull()) {
            candidate.remove("credential_ref");
        }
        if (hasText(candidate, "credential_ref")) {
            candidate.remove("api_key");
        } else if (candidate.has("api_key") && !hasText(candidate, "api_key")) {
            candidate.remove("api_key");
        }
    }

    private static final class ConfigurationStore {
        private final ObjectMapper json;
        private final Path file;
        private final Path keyFile;
        private final SecretKeySpec encryptionKey;
        private final Object lock = new Object();

        private ConfigurationStore(ObjectMapper json, Path ownerDirectory) throws IOException {
            this.json = json;
            Files.createDirectories(ownerDirectory);
            this.file = ownerDirectory.resolve("configuration.json");
            this.keyFile = ownerDirectory.resolve("credential.key");
            if (Files.exists(file) && !Files.exists(keyFile)) {
                JsonNode existing = json.readTree(file.toFile());
                for (JsonNode model : existing.path("models")) {
                    if (model.has("api_key") || model.has("api_key_ciphertext")) {
                        throw new IOException("Configuration credential key is missing");
                    }
                }
            }
            if (!Files.exists(keyFile)) {
                byte[] key = new byte[32];
                new SecureRandom().nextBytes(key);
                writeKeyAtomically(key);
            }
            this.encryptionKey = new SecretKeySpec(Files.readAllBytes(keyFile), "AES");
            if (!Files.exists(file)) {
                write(empty());
            }
        }

        List<ObjectNode> models() {
            synchronized (lock) {
                return objects(read().withArrayProperty("models"));
            }
        }

        List<ObjectNode> agents() {
            synchronized (lock) {
                return objects(read().withArrayProperty("agents"));
            }
        }

        Set<String> executableModelNames() {
            synchronized (lock) {
                return executableModels().stream()
                        .map(model -> model.path("name").asText())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
            }
        }

        List<ObjectNode> executableModels() {
            synchronized (lock) {
                return models().stream().filter(ConfigurationStore::isExecutable).toList();
            }
        }

        Optional<ObjectNode> executableModel(String name) {
            synchronized (lock) {
                return models().stream()
                        .filter(model -> model.path("name").asText().equals(name))
                        .filter(ConfigurationStore::isExecutable)
                        .findFirst();
            }
        }

        Optional<ObjectNode> defaultExecutableModel() {
            synchronized (lock) {
                return models().stream().filter(ConfigurationStore::isExecutable).findFirst();
            }
        }

        ObjectNode saveModel(ObjectNode request, String expectedRevision) {
            synchronized (lock) {
                ObjectNode root = read();
                ArrayNode models = root.withArrayProperty("models");
                String name = request.path("name").asText();
                ObjectNode existing = find(models, name).orElse(null);
                if (existing == null && expectedRevision != null) {
                    throw new InvalidRequestException(404, "Model no longer exists");
                }
                if (existing != null
                        && (expectedRevision == null
                                || !existing.path("revision").asText().equals(expectedRevision))) {
                    throw new InvalidRequestException(409, "Model changed or already exists; reload before saving");
                }
                ObjectNode saved = request.deepCopy();
                mergeCredentialSelection(saved, existing);
                saved.put("revision", UUID.randomUUID().toString());
                replace(models, existing, saved);
                write(root);
                return saved.deepCopy();
            }
        }

        ObjectNode modelForProbe(ObjectNode request, String expectedRevision) {
            synchronized (lock) {
                ObjectNode candidate = request.deepCopy();
                ObjectNode existing = null;
                if (expectedRevision != null) {
                    existing = find(read().withArrayProperty("models"), request.path("name").asText())
                            .orElseThrow(() -> new InvalidRequestException(409, "Model changed; reload before testing"));
                    if (!existing.path("revision").asText().equals(expectedRevision)) {
                        throw new InvalidRequestException(409, "Model changed; reload before testing");
                    }
                    candidate.put("revision", existing.path("revision").asText());
                }
                mergeCredentialSelection(candidate, existing);
                return candidate;
            }
        }

        Optional<ObjectNode> agent(String name) {
            synchronized (lock) {
                return find(read().withArrayProperty("agents"), name).map(ObjectNode::deepCopy);
            }
        }

        ObjectNode createAgent(ObjectNode agent) {
            synchronized (lock) {
                ObjectNode root = read();
                ArrayNode agents = root.withArrayProperty("agents");
                if (find(agents, agent.path("name").asText()).isPresent()) {
                    throw new InvalidRequestException(409, "Agent '" + agent.path("name").asText() + "' already exists");
                }
                agents.add(agent);
                write(root);
                return agent.deepCopy();
            }
        }

        ObjectNode updateAgent(String name, ObjectNode update) {
            synchronized (lock) {
                ObjectNode root = read();
                ArrayNode agents = root.withArrayProperty("agents");
                ObjectNode existing = find(agents, name)
                        .orElseThrow(() -> new InvalidRequestException(404, "Agent '" + name + "' not found"));
                for (String field : AGENT_FIELDS) {
                    if (!update.has(field)) continue;
                    if (field.equals("soul") && update.get(field).isNull()) continue;
                    if (field.equals("model_settings") && update.get(field).isObject()) {
                        ObjectNode merged = existing.path(field).isObject()
                                ? ((ObjectNode) existing.get(field)).deepCopy()
                                : json.createObjectNode();
                        update.get(field).properties().forEach(entry -> {
                            if (entry.getValue().isNull()) merged.remove(entry.getKey());
                            else merged.set(entry.getKey(), entry.getValue().deepCopy());
                        });
                        existing.set(field, merged.isEmpty() ? json.nullNode() : merged);
                        continue;
                    }
                    existing.set(field, update.get(field).deepCopy());
                }
                write(root);
                return existing.deepCopy();
            }
        }

        ObjectNode writeAgentForTool(String name, ObjectNode createDefaults, ObjectNode patch,
                java.util.function.Consumer<ObjectNode> validator) {
            synchronized (lock) {
                ObjectNode root = read();
                ArrayNode agents = root.withArrayProperty("agents");
                Optional<ObjectNode> found = find(agents, name);
                if (found.isEmpty() && createDefaults == null) {
                    throw new IllegalArgumentException("current agent is unavailable");
                }
                ObjectNode candidate = found.map(ObjectNode::deepCopy).orElseGet(() -> createDefaults.deepCopy());
                patch.properties().forEach(field -> candidate.set(field.getKey(), field.getValue().deepCopy()));
                // Validation and publication share the existing store lock. No partial patch writes.
                validator.accept(candidate.deepCopy());
                if (found.isPresent()) replace(agents, found.orElseThrow(), candidate);
                else agents.add(candidate);
                write(root);
                return candidate.deepCopy();
            }
        }

        void deleteAgent(String name) {
            synchronized (lock) {
                ObjectNode root = read();
                ArrayNode agents = root.withArrayProperty("agents");
                ObjectNode existing = find(agents, name)
                        .orElseThrow(() -> new InvalidRequestException(404, "Agent '" + name + "' not found"));
                agents.remove(indexOf(agents, existing));
                write(root);
            }
        }

        private ObjectNode read() {
            try {
                ObjectNode root = (ObjectNode) json.readTree(file.toFile());
                ArrayNode models = root.withArrayProperty("models");
                for (JsonNode value : models) {
                    ObjectNode model = (ObjectNode) value;
                    if (hasText(model, "api_key_ciphertext")) {
                        model.put("api_key", decrypt(model.path("api_key_ciphertext").asText()));
                        model.remove("api_key_ciphertext");
                    }
                }
                return root;
            } catch (IOException | GeneralSecurityException exception) {
                throw new IllegalStateException("Configuration store is unavailable", exception);
            }
        }

        private void write(ObjectNode root) {
            Path temporary = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
            try {
                ObjectNode persisted = root.deepCopy();
                for (JsonNode value : persisted.withArrayProperty("models")) {
                    ObjectNode model = (ObjectNode) value;
                    if (hasText(model, "api_key")) {
                        model.put("api_key_ciphertext", encrypt(model.path("api_key").asText()));
                        model.remove("api_key");
                    }
                }
                json.writeValue(temporary.toFile(), persisted);
                try {
                    Files.move(
                            temporary,
                            file,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | GeneralSecurityException exception) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Preserve the primary storage failure.
                }
                throw new IllegalStateException("Configuration store is unavailable", exception);
            }
        }

        private void writeKeyAtomically(byte[] key) throws IOException {
            Path temporary = keyFile.resolveSibling(keyFile.getFileName() + "." + UUID.randomUUID() + ".tmp");
            try {
                Files.write(temporary, key);
                try {
                    Files.move(
                            temporary,
                            keyFile,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, keyFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        private String encrypt(String secret) throws GeneralSecurityException {
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, envelope, 0, nonce.length);
            System.arraycopy(encrypted, 0, envelope, nonce.length, encrypted.length);
            return "v1:" + Base64.getEncoder().encodeToString(envelope);
        }

        private String decrypt(String envelope) throws GeneralSecurityException {
            if (!envelope.startsWith("v1:")) {
                throw new GeneralSecurityException("Unknown credential format");
            }
            byte[] bytes = Base64.getDecoder().decode(envelope.substring(3));
            if (bytes.length <= 12) throw new GeneralSecurityException("Invalid credential envelope");
            byte[] nonce = java.util.Arrays.copyOfRange(bytes, 0, 12);
            byte[] encrypted = java.util.Arrays.copyOfRange(bytes, 12, bytes.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        }

        private ObjectNode empty() {
            ObjectNode root = json.createObjectNode();
            root.put("schema_version", 1);
            root.set("models", json.createArrayNode());
            root.set("agents", json.createArrayNode());
            return root;
        }

        private static boolean isExecutable(ObjectNode model) {
            if (!model.path("enabled").asBoolean(false)
                    || !model.path("provider").asText().equals("openai-compatible")
                    || !hasConfiguredCredential(model)
                    || !hasText(model, "name")
                    || !hasText(model, "model")
                    || !hasText(model, "base_url")
                    || !hasText(model, "revision")) {
                return false;
            }
            URI endpoint;
            try {
                endpoint = URI.create(model.path("base_url").asText());
            } catch (IllegalArgumentException exception) {
                return false;
            }
            if (!("http".equalsIgnoreCase(endpoint.getScheme())
                            || "https".equalsIgnoreCase(endpoint.getScheme()))
                    || endpoint.getHost() == null
                    || endpoint.getUserInfo() != null
                    || endpoint.getQuery() != null
                    || endpoint.getFragment() != null
                    || ("http".equalsIgnoreCase(endpoint.getScheme()) && !isLiteralLoopback(endpoint))) {
                return false;
            }
            int contextWindow = positiveOr(model, "context_window", DEFAULT_CONTEXT_WINDOW);
            int maxTokens = positiveOr(
                    model, "max_tokens", Math.min(DEFAULT_MAX_OUTPUT_TOKENS, contextWindow));
            return maxTokens <= contextWindow;
        }

        private static List<ObjectNode> objects(ArrayNode values) {
            List<ObjectNode> result = new ArrayList<>();
            values.forEach(value -> result.add(((ObjectNode) value).deepCopy()));
            return result;
        }

        private static Optional<ObjectNode> find(ArrayNode values, String name) {
            for (JsonNode value : values) {
                if (value.path("name").asText().equals(name)) return Optional.of((ObjectNode) value);
            }
            return Optional.empty();
        }

        private static void replace(ArrayNode values, ObjectNode existing, ObjectNode replacement) {
            if (existing == null) {
                values.add(replacement);
            } else {
                values.set(indexOf(values, existing), replacement);
            }
        }

        private static int indexOf(ArrayNode values, ObjectNode target) {
            for (int index = 0; index < values.size(); index++) {
                if (values.get(index) == target) return index;
            }
            throw new IllegalStateException("Stored item disappeared");
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

    enum ProbeResult {
        SUCCESS("success"),
        TOOL_CALL_MISSING("tool_call_missing"),
        CONNECTION_FAILED("connection_failed");

        private final String message;

        ProbeResult(String message) {
            this.message = message;
        }
    }

    @FunctionalInterface
    interface ProbeTransport {
        AgentChatResponse invoke(ObjectNode config);
    }
}

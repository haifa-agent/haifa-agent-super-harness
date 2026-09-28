package io.haifa.agent.superharness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.auth.localmodel.WindowsCredentialManagerClient;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceChangeLimits;
import io.haifa.agent.superharness.workspace.WorkspaceLimits;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Minimal HTTP compatibility surface consumed during frozen DeerFlow UI bootstrap. */
public final class DeerFlowBootstrapApi implements AutoCloseable {
    static final String CONTINUATION_KEY_REFERENCE_ENVIRONMENT =
            "HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF";
    static final String DEFAULT_CONTINUATION_KEY_REFERENCE =
            "os://HAIFA_SUPER_HARNESS_CONTINUATION_KEY";
    static final String DEFAULT_USER_ID = "default";
    static final String DEFAULT_USER_EMAIL = "default@test.local";
    static final List<String> DEFAULT_PERMISSIONS =
            List.of(
                    "threads:read",
                    "threads:write",
                    "threads:delete",
                    "runs:create",
                    "runs:read",
                    "runs:cancel",
                    "projects:read",
                    "projects:write",
                    "projects:delete");

    private final HttpServer server;
    private final ExecutorService executor;
    private final ObjectMapper objectMapper;
    private final DeerFlowThreadRunApi threadRunApi;
    private final DeerFlowConfigurationApi configurationApi;
    private final DeerFlowWorkspaceApi workspaceApi;
    private final DeerFlowProjectApi projectApi;
    private final DeerFlowTrashApi trashApi;
    private final DeerFlowInputPolishApi inputPolishApi;
    private final DeerFlowSuggestionsApi suggestionsApi;

    private DeerFlowBootstrapApi(
            HttpServer server,
            ExecutorService executor,
            Path dataDirectory,
            SecretKey continuationKey,
            Function<JsonNode, HaifaRunBridge.ModelBinding> testRunModelResolver,
            List<? extends JavaTool<?, ?>> testRunTools,
            DeerFlowSuggestionsApi.Settings suggestionsSettings)
            throws IOException {
        this.server = server;
        this.executor = executor;
        this.objectMapper = new ObjectMapper();
        this.configurationApi = new DeerFlowConfigurationApi(objectMapper, dataDirectory);
        PersistentProjectWorkspaceService projects = new PersistentProjectWorkspaceService(
                dataDirectory.resolve("workspace"), WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
        RestrictedWorkspaceService toolWorkspace = new RestrictedWorkspaceService(
                dataDirectory.resolve("workspace"), WorkspaceLimits.defaults());
        Function<JsonNode, HaifaRunBridge.ModelBinding> modelResolver =
                testRunModelResolver == null
                        ? configurationApi::resolveRunModel
                        : testRunModelResolver;
        this.threadRunApi = new DeerFlowThreadRunApi(
                objectMapper,
                modelResolver,
                projectId -> projects.projectExists(DEFAULT_USER_ID, projectId),
                projects,
                dataDirectory,
                continuationKey,
                scope -> !testRunTools.isEmpty()
                        ? testRunTools
                        : testRunModelResolver == null
                                ? List.of(
                                        new ProductionWriteFileTool(
                                                scope.threadId(), scope.artifactProjectRef(), projects, toolWorkspace),
                                        new ProductionReadFileTool(scope.threadId(), projects, toolWorkspace))
                                : List.of(),
                new DeerFlowUploadContext(toolWorkspace));
        this.workspaceApi = new DeerFlowWorkspaceApi(
                objectMapper, dataDirectory.resolve("workspace"), threadRunApi::hasThread);
        this.projectApi = new DeerFlowProjectApi(objectMapper, projects, threadRunApi, workspaceApi);
        this.trashApi = new DeerFlowTrashApi(objectMapper, projects);
        this.inputPolishApi = new DeerFlowInputPolishApi(objectMapper, modelResolver);
        this.suggestionsApi = new DeerFlowSuggestionsApi(
                objectMapper, modelResolver, threadRunApi::hasThread, suggestionsSettings);
        registerRoutes();
    }

    public static DeerFlowBootstrapApi create(LocalServerConfiguration configuration)
            throws IOException {
        return create(
                configuration,
                Path.of(".super-harness", "data").toAbsolutePath(),
                System.getenv());
    }

    static DeerFlowBootstrapApi create(LocalServerConfiguration configuration, Path dataDirectory)
            throws IOException {
        return createWithKey(
                configuration,
                dataDirectory,
                generateContinuationKey(),
                null,
                DeerFlowSuggestionsApi.Settings.defaults());
    }

    static DeerFlowBootstrapApi create(
            LocalServerConfiguration configuration,
            Path dataDirectory,
            Map<String, String> environment)
            throws IOException {
        return createWithKey(
                configuration,
                dataDirectory,
                continuationKey(environment, dataDirectory),
                null,
                DeerFlowSuggestionsApi.Settings.fromEnvironment(environment));
    }

    private static DeerFlowBootstrapApi createWithKey(
            LocalServerConfiguration configuration,
            Path dataDirectory,
            SecretKey continuationKey,
            Function<JsonNode, HaifaRunBridge.ModelBinding> runModelResolver,
            DeerFlowSuggestionsApi.Settings suggestionsSettings)
            throws IOException {
        HttpServer server = HttpServer.create(configuration.socketAddress(), 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        return new DeerFlowBootstrapApi(
                server, executor, dataDirectory, continuationKey, runModelResolver, List.of(), suggestionsSettings);
    }

    static DeerFlowBootstrapApi createForTests(
            LocalServerConfiguration configuration,
            Path dataDirectory,
            Function<JsonNode, HaifaRunBridge.ModelBinding> runModelResolver)
            throws IOException {
        return createWithKey(
                configuration,
                dataDirectory,
                generateContinuationKey(),
                runModelResolver,
                DeerFlowSuggestionsApi.Settings.defaults());
    }

    static DeerFlowBootstrapApi createForTests(
            LocalServerConfiguration configuration,
            Path dataDirectory,
            Function<JsonNode, HaifaRunBridge.ModelBinding> runModelResolver,
            DeerFlowSuggestionsApi.Settings suggestionsSettings)
            throws IOException {
        return createWithKey(
                configuration, dataDirectory, generateContinuationKey(), runModelResolver, suggestionsSettings);
    }

    static DeerFlowBootstrapApi createForInteractionTests(
            LocalServerConfiguration configuration,
            Path dataDirectory,
            Function<JsonNode, HaifaRunBridge.ModelBinding> runModelResolver,
            List<? extends JavaTool<?, ?>> tools)
            throws IOException {
        return createForInteractionTests(
                configuration, dataDirectory, generateContinuationKey(), runModelResolver, tools);
    }

    static DeerFlowBootstrapApi createForInteractionTests(
            LocalServerConfiguration configuration,
            Path dataDirectory,
            SecretKey continuationKey,
            Function<JsonNode, HaifaRunBridge.ModelBinding> runModelResolver,
            List<? extends JavaTool<?, ?>> tools)
            throws IOException {
        HttpServer server = HttpServer.create(configuration.socketAddress(), 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        return new DeerFlowBootstrapApi(
                server,
                executor,
                dataDirectory,
                continuationKey,
                runModelResolver,
                tools,
                DeerFlowSuggestionsApi.Settings.defaults());
    }

    static DeerFlowBootstrapApi createForTests(
            LocalServerConfiguration configuration,
            Path dataDirectory,
            Function<JsonNode, HaifaRunBridge.ModelBinding> runModelResolver,
            SecretKey continuationKey)
            throws IOException {
        return createWithKey(
                configuration,
                dataDirectory,
                continuationKey,
                runModelResolver,
                DeerFlowSuggestionsApi.Settings.defaults());
    }

    private static SecretKey continuationKey(Map<String, String> environment, Path dataDirectory) {
        return continuationKey(
                environment, dataDirectory, WindowsCredentialManagerClient.defaultClient());
    }

    static SecretKey continuationKey(
            Map<String, String> environment,
            Path dataDirectory,
            WindowsCredentialManagerClient credentials) {
        boolean explicitReference = environment.containsKey(CONTINUATION_KEY_REFERENCE_ENVIRONMENT);
        String reference = environment.getOrDefault(
                CONTINUATION_KEY_REFERENCE_ENVIRONMENT, DEFAULT_CONTINUATION_KEY_REFERENCE);
        String encoded;
        if (reference.startsWith("env://")) {
            String name = credentialName(reference, "env://");
            encoded = environment.get(name);
            if (encoded == null || encoded.isBlank()) {
                throw new IllegalStateException("continuation key credential is unavailable");
            }
        } else if (reference.startsWith("os://")) {
            String name = credentialName(reference, "os://");
            if (reference.equals(DEFAULT_CONTINUATION_KEY_REFERENCE)) {
                name = defaultOsCredentialName(dataDirectory);
            }
            String target = "haifa:os:" + name;
            Optional<String> stored = credentials.read(target).filter(value -> !value.isBlank());
            if (stored.isEmpty()) {
                if (explicitReference) {
                    throw new IllegalStateException("continuation key credential is unavailable");
                }
                Path database = dataDirectory.toAbsolutePath()
                        .normalize()
                        .resolve("runtime")
                        .resolve("haifa-agent.sqlite");
                if (java.nio.file.Files.exists(database)) {
                    throw new IllegalStateException(
                            "continuation key credential is unavailable for the existing Runtime database");
                }
                encoded = Base64.getEncoder().encodeToString(generateContinuationKey().getEncoded());
                credentials.write(target, encoded);
                String verified = credentials
                        .read(target)
                        .filter(value -> !value.isBlank())
                        .orElseThrow(() -> new IllegalStateException(
                                "continuation key credential could not be verified after provisioning"));
                if (!java.security.MessageDigest.isEqual(
                        encoded.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        verified.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                    throw new IllegalStateException(
                            "continuation key credential could not be verified after provisioning");
                }
            } else {
                encoded = stored.orElseThrow();
            }
        } else {
            throw new IllegalArgumentException(
                    CONTINUATION_KEY_REFERENCE_ENVIRONMENT + " must use env:// or os://");
        }
        try {
            byte[] key = Base64.getDecoder().decode(encoded.trim());
            if (key.length != 32) {
                throw new IllegalArgumentException("continuation key credential must contain a 256-bit key");
            }
            return new SecretKeySpec(key, "AES");
        } catch (IllegalArgumentException invalid) {
            if (invalid.getMessage() != null && invalid.getMessage().startsWith("continuation key credential")) {
                throw invalid;
            }
            throw new IllegalArgumentException("continuation key credential is not valid Base64", invalid);
        }
    }

    private static String credentialName(String reference, String prefix) {
        String name = reference.substring(prefix.length());
        if (!name.matches("[A-Za-z_][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException("continuation key credential reference is invalid");
        }
        return name;
    }

    static String defaultOsCredentialName(Path dataDirectory) {
        String canonicalIdentity = dataDirectory.toAbsolutePath()
                .normalize()
                .toString()
                .toLowerCase(java.util.Locale.ROOT);
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonicalIdentity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String suffix = java.util.HexFormat.of().formatHex(digest, 0, 8);
            return "HAIFA_SUPER_HARNESS_CONTINUATION_KEY_" + suffix;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static SecretKey generateContinuationKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256, new SecureRandom());
            return generator.generateKey();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES-256 is required", exception);
        }
    }

    public void start() {
        server.start();
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    DeerFlowThreadRunApi threadRunApi() {
        return threadRunApi;
    }

    int cachedRunCount() {
        return threadRunApi.cachedRunCount();
    }

    void resetTurnPageReads(String threadId) {
        threadRunApi.resetTurnPageReads(threadId);
    }

    long turnPageReads(String threadId) {
        return threadRunApi.turnPageReads(threadId);
    }

    boolean hasAttachedBridge(String threadId) {
        return threadRunApi.hasAttachedBridge(threadId);
    }

    @Override
    public void close() {
        server.stop(0);
        threadRunApi.close();
        executor.close();
    }

    private void registerRoutes() {
        get("/health", Map.of("status", "ok"));
        get(
                "/api/v1/auth/setup-status",
                orderedMap("needs_setup", false, "registration_enabled", false));
        get(
                "/api/v1/auth/me",
                orderedMap(
                        "id",
                        DEFAULT_USER_ID,
                        "email",
                        DEFAULT_USER_EMAIL,
                        "system_role",
                        "admin",
                        "needs_setup",
                        false,
                        "oauth_provider",
                        null,
                        "permissions",
                        DEFAULT_PERMISSIONS));
        get(
                "/api/features",
                orderedMap(
                        "agents_api",
                        Map.of("enabled", true),
                        "browser_control",
                        Map.of("enabled", false),
                        "mcp_tasks",
                        Map.of("enabled", false),
                        "subagent_batches",
                        orderedMap(
                                "enabled",
                                false,
                                "repository_available",
                                false,
                                "worker_running",
                                false,
                                "max_running",
                                3),
                        "conversation_references",
                        orderedMap("enabled", false, "max_references", 3),
                        "knowledge_base",
                        Map.of("scope_selection_enabled", false)));
        server.createContext("/api/models", configurationApi);
        server.createContext("/api/managed-models", configurationApi);
        server.createContext("/api/agents", configurationApi);
        server.createContext(DeerFlowInputPolishApi.PATH, inputPolishApi);
        server.createContext("/api/suggestions", suggestionsApi);
        server.createContext("/api/projects", projectApi);
        server.createContext("/api/trash", trashApi);
        server.createContext("/api/threads", exchange -> {
            if (!workspaceApi.tryHandle(exchange) && !suggestionsApi.tryHandle(exchange)) {
                threadRunApi.handle(exchange);
            }
        });
        server.createContext("/", this::notFound);
    }

    private void get(String path, Object body) {
        server.createContext(path, exactGet(path, body));
    }

    private HttpHandler exactGet(String path, Object body) {
        return exchange -> {
            if (!exchange.getRequestURI().getPath().equals(path)) {
                notFound(exchange);
                return;
            }
            if (!isLoopbackPeer(exchange)) {
                send(exchange, 403, Map.of("detail", "Local profile accepts loopback clients only"));
                return;
            }
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.getResponseHeaders().set("Allow", "GET");
                send(exchange, 405, Map.of("detail", "Method Not Allowed"));
                return;
            }
            send(exchange, 200, body);
        };
    }

    private void notFound(HttpExchange exchange) throws IOException {
        send(exchange, 404, Map.of("detail", "Not Found"));
    }

    private boolean isLoopbackPeer(HttpExchange exchange) {
        return exchange.getRemoteAddress() != null
                && exchange.getRemoteAddress().getAddress() != null
                && exchange.getRemoteAddress().getAddress().isLoopbackAddress();
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, payload.length);
        try (var responseBody = exchange.getResponseBody()) {
            responseBody.write(payload);
        }
    }

    private static Map<String, Object> orderedMap(Object... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("orderedMap requires key/value pairs");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }
}

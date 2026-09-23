package io.haifa.agent.superharness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceChangeLimits;
import io.haifa.agent.superharness.workspace.WorkspaceLimits;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** Minimal HTTP compatibility surface consumed during frozen DeerFlow UI bootstrap. */
public final class DeerFlowBootstrapApi implements AutoCloseable {
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

    private DeerFlowBootstrapApi(
            HttpServer server,
            ExecutorService executor,
            Path dataDirectory,
            Function<JsonNode, HaifaRunBridge.ModelBinding> testRunModelResolver)
            throws IOException {
        this.server = server;
        this.executor = executor;
        this.objectMapper = new ObjectMapper();
        this.configurationApi = new DeerFlowConfigurationApi(objectMapper, dataDirectory);
        PersistentProjectWorkspaceService projects = new PersistentProjectWorkspaceService(
                dataDirectory.resolve("workspace"), WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
        this.threadRunApi = new DeerFlowThreadRunApi(
                objectMapper,
                testRunModelResolver == null
                        ? configurationApi::resolveRunModel
                        : testRunModelResolver,
                projectId -> projects.projectExists(DEFAULT_USER_ID, projectId),
                projects);
        this.projectApi = new DeerFlowProjectApi(objectMapper, projects, threadRunApi);
        this.workspaceApi = new DeerFlowWorkspaceApi(
                objectMapper, dataDirectory.resolve("workspace"), threadRunApi::hasThread);
        registerRoutes();
    }

    public static DeerFlowBootstrapApi create(LocalServerConfiguration configuration)
            throws IOException {
        return create(configuration, Path.of(".super-harness", "data").toAbsolutePath());
    }

    static DeerFlowBootstrapApi create(LocalServerConfiguration configuration, Path dataDirectory)
            throws IOException {
        HttpServer server = HttpServer.create(configuration.socketAddress(), 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        return new DeerFlowBootstrapApi(server, executor, dataDirectory, null);
    }

    static DeerFlowBootstrapApi createForTests(
            LocalServerConfiguration configuration,
            Path dataDirectory,
            Function<JsonNode, HaifaRunBridge.ModelBinding> runModelResolver)
            throws IOException {
        HttpServer server = HttpServer.create(configuration.socketAddress(), 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        return new DeerFlowBootstrapApi(server, executor, dataDirectory, runModelResolver);
    }

    public void start() {
        server.start();
    }

    public InetSocketAddress address() {
        return server.getAddress();
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
        server.createContext("/api/projects", projectApi);
        server.createContext("/api/threads", exchange -> {
            if (!workspaceApi.tryHandle(exchange)) {
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

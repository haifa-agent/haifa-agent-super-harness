package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.core.content.StoredImageContentPart;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ImageDataPart;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelRequestId;
import io.haifa.agent.model.api.ModelStreamSink;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
import io.haifa.agent.superharness.server.run.DurableHaifaFoundation;
import io.haifa.agent.superharness.server.run.HaifaRunBridge;
import io.haifa.agent.superharness.server.run.SuperHarnessModelImageResolver;
import io.haifa.agent.superharness.server.tool.file.ProductionViewImageTool;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceBinding;
import io.haifa.agent.superharness.workspace.WorkspaceChangeLimits;
import io.haifa.agent.superharness.workspace.WorkspaceLimits;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end integration test for DeerFlow vision capabilities and view_image tooling (TASK-W11-003).
 *
 * <p>Verifies the complete lifecycle: file upload -> prompt injection -> tool execution -> transient
 * multimodal request -> content-dependent vision model answer -> artifact retrieval, along with negative
 * cases and security boundaries (cross-thread isolation, path traversal, extension spoofing, non-vision
 * models, resolver checks, and 10+ MiB graceful degradation).
 */
public class DeerFlowViewImageIntegrationTest {

    private static final byte[] VALID_PNG_BYTES = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

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

    @BeforeEach
    void start() throws Exception {
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

    private void startGateway() throws Exception {
        api = DeerFlowBootstrapApi.create(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataRoot,
                Map.of("HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W11_V2_TEST_KEY",
                        "W11_V2_TEST_KEY", key));
        api.start();
        base = URI.create("http://127.0.0.1:" + api.address().getPort());

        String endpoint = "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1";

        // 1. vision-model (supports_vision: true)
        String visionConfig = """
                {"config":{"name":"vision-model","display_name":"Vision Controlled",
                "provider":"openai-compatible","model":"vision-controlled",
                "base_url":"%s","enabled":true,"supports_vision":true,
                "context_window":32768,"max_tokens":1024,"api_key":"controlled-key"},
                "expected_revision":null}
                """.formatted(endpoint);
        HttpResponse<String> savedVision = request("PUT", "/api/managed-models", visionConfig);
        assertThat(savedVision.statusCode()).as(savedVision.body()).isEqualTo(200);

        // 2. text-only-model (supports_vision: false)
        String textOnlyConfig = """
                {"config":{"name":"text-only-model","display_name":"Text Only Controlled",
                "provider":"openai-compatible","model":"text-only-controlled",
                "base_url":"%s","enabled":true,"supports_vision":false,
                "context_window":32768,"max_tokens":1024,"api_key":"controlled-key"},
                "expected_revision":null}
                """.formatted(endpoint);
        HttpResponse<String> savedText = request("PUT", "/api/managed-models", textOnlyConfig);
        assertThat(savedText.statusCode()).as(savedText.body()).isEqualTo(200);
    }

    // =========================================================================
    // Oracle Test Cases (TASK-W11-003 Section 4)
    // =========================================================================

    /**
     * Oracle 1: Full-chain integration: Upload -> Prompt injection (<current_uploads>) -> view_image
     * Tool call -> Transient multimodal injection -> Content-dependent vision answer -> Artifact download.
     */
    @Test
    void testUploadAndAgentViewImageFullChain() throws Exception {
        String threadId = "v2-vision-fullchain-" + System.nanoTime();

        // Step 1: Upload valid PNG image
        HttpResponse<String> uploaded = upload(threadId, "chart.png", VALID_PNG_BYTES);
        assertThat(uploaded.statusCode()).as(uploaded.body()).isEqualTo(200);
        JsonNode file = json.readTree(uploaded.body()).path("files").get(0);
        String virtualPath = file.path("virtual_path").asText();
        String artifactUrl = file.path("artifact_url").asText();
        assertThat(virtualPath).isEqualTo("/mnt/user-data/uploads/chart.png");
        assertThat(artifactUrl).isEqualTo("/api/threads/" + threadId + "/artifacts/mnt/user-data/uploads/chart.png");

        // Step 2: Create Thread row
        assertThat(request("POST", "/api/threads", "{\"thread_id\":\"" + threadId + "\"}").statusCode())
                .isEqualTo(200);

        // Step 3-5: Run with files using vision-model
        RunResult run = runWithFiles(threadId, "请分析上传的图表",
                List.of(fileRef("chart.png", VALID_PNG_BYTES.length)), "vision-model");
        assertThat(run.status()).isEqualTo("success");

        // Content-dependent assertion: Controlled provider extracted the injected image bytes,
        // calculated SHA-256, and replied with content-bound confirmation text.
        String expectedSha = sha256(VALID_PNG_BYTES);
        assertThat(run.answer())
                .contains("Vision Analysis: Verified PNG image with sha256=" + expectedSha + ", content confirmed.");

        // Step 6: Artifact download verification
        HttpResponse<byte[]> artifact = getBytes(artifactUrl);
        assertThat(artifact.statusCode()).isEqualTo(200);
        assertThat(artifact.headers().firstValue("Content-Type")).contains("image/png");
        assertThat(artifact.body()).isEqualTo(VALID_PNG_BYTES);
    }

    /**
     * Oracle 2: Zero Base64 pollution in database: Verify that neither database tables nor thread state
     * contain Base64-encoded image payloads or data URIs.
     */
    @Test
    void testZeroBase64PollutionInDatabase() throws Exception {
        String threadId = "v2-vision-db-" + System.nanoTime();
        assertThat(upload(threadId, "chart.png", VALID_PNG_BYTES).statusCode()).isEqualTo(200);
        assertThat(request("POST", "/api/threads", "{\"thread_id\":\"" + threadId + "\"}").statusCode()).isEqualTo(200);

        RunResult run = runWithFiles(threadId, "请分析上传的图表",
                List.of(fileRef("chart.png", VALID_PNG_BYTES.length)), "vision-model");
        assertThat(run.status()).isEqualTo("success");

        String b64 = Base64.getEncoder().encodeToString(VALID_PNG_BYTES);

        // 1. Thread state & messages endpoints
        HttpResponse<String> state = request("GET", "/api/threads/" + threadId + "/state", null);
        assertThat(state.body()).doesNotContain("data:image").doesNotContain(b64);

        HttpResponse<String> messages = request("GET", "/api/threads/" + threadId + "/messages/page", null);
        assertThat(messages.body()).doesNotContain("data:image").doesNotContain(b64);

        // 2. Scan all database files under dataRoot (e.g. .sqlite, .db)
        try (Stream<Path> paths = Files.walk(dataRoot)) {
            List<Path> dbFiles = paths.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".sqlite") || name.endsWith(".db");
                    })
                    .toList();
            for (Path dbFile : dbFiles) {
                byte[] raw = Files.readAllBytes(dbFile);
                String dbContent = new String(raw, StandardCharsets.ISO_8859_1);
                assertThat(dbContent).as("Database file " + dbFile + " must not contain data:image")
                        .doesNotContain("data:image");
                assertThat(dbContent).as("Database file " + dbFile + " must not contain base64 image payload")
                        .doesNotContain(b64);
            }
        }
    }

    /**
     * Oracle 3: Cross-thread image access denied: Thread B cannot view or download Thread A's image uploads.
     */
    @Test
    void testCrossThreadImageAccessDenied() throws Exception {
        String threadA = "v2-thread-a-" + System.nanoTime();
        String threadB = "v2-thread-b-" + System.nanoTime();

        assertThat(upload(threadA, "secret-a.png", VALID_PNG_BYTES).statusCode()).isEqualTo(200);
        assertThat(request("POST", "/api/threads", "{\"thread_id\":\"" + threadA + "\"}").statusCode()).isEqualTo(200);
        assertThat(request("POST", "/api/threads", "{\"thread_id\":\"" + threadB + "\"}").statusCode()).isEqualTo(200);

        // Tool bound to Thread B trying to view Thread A's upload
        Path wsRoot = dataRoot.resolve("workspace");
        PersistentProjectWorkspaceService bindings = new PersistentProjectWorkspaceService(
                wsRoot, WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
        RestrictedWorkspaceService ws = new RestrictedWorkspaceService(wsRoot, WorkspaceLimits.defaults());
        ProductionViewImageTool toolForThreadB = new ProductionViewImageTool(threadB, bindings, ws);

        ProductionViewImageTool.Output output = toolForThreadB.invoke(
                new ProductionViewImageTool.Input("/mnt/user-data/uploads/secret-a.png"),
                toolContext(threadB));

        assertThat(output.status()).isEqualTo("error");
        assertThat(output.message()).contains("Image file not found: /mnt/user-data/uploads/secret-a.png");

        // Artifact route isolation
        HttpResponse<String> artifactFromB = request("GET",
                "/api/threads/" + threadB + "/artifacts/mnt/user-data/uploads/secret-a.png", null);
        assertThat(artifactFromB.statusCode()).as(artifactFromB.body()).isNotEqualTo(200);
    }

    /**
     * Oracle 4: Path traversal rejected: Fail-closed boundary defense on escapes.
     */
    @Test
    void testPathTraversalRejected() throws Exception {
        String threadId = "v2-traversal-" + System.nanoTime();
        assertThat(request("POST", "/api/threads", "{\"thread_id\":\"" + threadId + "\"}").statusCode()).isEqualTo(200);

        Path wsRoot = dataRoot.resolve("workspace");
        PersistentProjectWorkspaceService bindings = new PersistentProjectWorkspaceService(
                wsRoot, WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
        RestrictedWorkspaceService ws = new RestrictedWorkspaceService(wsRoot, WorkspaceLimits.defaults());
        ProductionViewImageTool tool = new ProductionViewImageTool(threadId, bindings, ws);

        for (String maliciousPath : List.of(
                "../../secret.png",
                "/etc/shadow",
                "/mnt/user-data/../secret.png",
                "c:\\windows\\system32\\calc.png",
                "/mnt/user-data/workspace/../../escape.png")) {
            ProductionViewImageTool.Output output = tool.invoke(
                    new ProductionViewImageTool.Input(maliciousPath),
                    toolContext(threadId));
            assertThat(output.status()).isEqualTo("error");
            assertThat(output.message())
                    .contains("Error: Only image paths under /mnt/user-data/workspace, /mnt/user-data/uploads, /mnt/user-data/outputs are allowed");
        }
    }

    /**
     * Oracle 5: Magic bytes mismatch rejected: Files spoofing extensions are intercepted.
     */
    @Test
    void testMagicBytesMismatchRejected() throws Exception {
        String threadId = "v2-magic-" + System.nanoTime();
        byte[] fakeBytes = "Not a real PNG, just plain text.".getBytes(StandardCharsets.UTF_8);
        assertThat(upload(threadId, "fake.png", fakeBytes).statusCode()).isEqualTo(200);
        assertThat(request("POST", "/api/threads", "{\"thread_id\":\"" + threadId + "\"}").statusCode()).isEqualTo(200);

        Path wsRoot = dataRoot.resolve("workspace");
        PersistentProjectWorkspaceService bindings = new PersistentProjectWorkspaceService(
                wsRoot, WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
        RestrictedWorkspaceService ws = new RestrictedWorkspaceService(wsRoot, WorkspaceLimits.defaults());
        ProductionViewImageTool tool = new ProductionViewImageTool(threadId, bindings, ws);

        ProductionViewImageTool.Output output = tool.invoke(
                new ProductionViewImageTool.Input("/mnt/user-data/uploads/fake.png"),
                toolContext(threadId));
        assertThat(output.status()).isEqualTo("error");
        assertThat(output.message()).contains("Error: File contents do not match a supported image format");
    }

    /**
     * Oracle 6: Non-vision model graceful behavior: Models without vision capabilities have view_image
     * excluded and complete pure text dialogue.
     */
    @Test
    void testNonVisionModelGracefulBehavior() throws Exception {
        String threadId = "v2-nonvision-" + System.nanoTime();
        assertThat(upload(threadId, "chart.png", VALID_PNG_BYTES).statusCode()).isEqualTo(200);
        assertThat(request("POST", "/api/threads", "{\"thread_id\":\"" + threadId + "\"}").statusCode()).isEqualTo(200);

        int beforeCalls = providerCalls.get();
        RunResult run = runWithFiles(threadId, "分析这个图表",
                List.of(fileRef("chart.png", VALID_PNG_BYTES.length)), "text-only-model");

        assertThat(run.status()).isEqualTo("success");
        assertThat(run.answer()).isEqualTo("Text only model: cannot view images.");

        // Assert view_image was omitted from the tools list delivered to the model
        JsonNode providerReq = providerRequests.get(beforeCalls);
        List<String> tools = providerReq.path("tools").findValuesAsText("name");
        assertThat(tools).doesNotContain("view_image");
    }

    /**
     * Oracle 7: SuperHarnessModelImageResolver cross-thread and escape protection: Forged storeId throws SecurityException.
     */
    @Test
    void testModelImageResolverCrossThreadRejection() throws Exception {
        Path wsRoot = dataRoot.resolve("workspace");
        PersistentProjectWorkspaceService bindings = new PersistentProjectWorkspaceService(
                wsRoot, WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
        RestrictedWorkspaceService ws = new RestrictedWorkspaceService(wsRoot, WorkspaceLimits.defaults());
        String threadId = "v2-resolver-test";
        WorkspaceBinding binding = bindings.bindThreadWorkspace("default", threadId);

        ws.createWorkingFile(binding.ownerId(), binding.workspaceId(), "workspace/test.png", VALID_PNG_BYTES);
        SuperHarnessModelImageResolver resolver = new SuperHarnessModelImageResolver(ws, "default", threadId);

        // 1. Cross-thread storeId rejected
        StoredImageContentPart forgedCrossThread = new StoredImageContentPart(
                "other-tenant-store-id",
                "/mnt/user-data/workspace/test.png",
                "image/png",
                VALID_PNG_BYTES.length,
                "sha256:" + sha256(VALID_PNG_BYTES),
                "test.png");
        assertThatThrownBy(() -> resolver.resolve(forgedCrossThread))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("does not match authorized workspace context");

        // 2. Path traversal in storeId rejected
        StoredImageContentPart pathTraversalStore = new StoredImageContentPart(
                "../escape",
                "/mnt/user-data/workspace/test.png",
                "image/png",
                VALID_PNG_BYTES.length,
                "sha256:" + sha256(VALID_PNG_BYTES),
                "test.png");
        assertThatThrownBy(() -> resolver.resolve(pathTraversalStore))
                .isInstanceOf(SecurityException.class);

        // 3. Legitimate resolution succeeds
        StoredImageContentPart validPart = new StoredImageContentPart(
                binding.workspaceId().value(),
                "/mnt/user-data/workspace/test.png",
                "image/png",
                VALID_PNG_BYTES.length,
                "sha256:" + sha256(VALID_PNG_BYTES),
                "test.png");
        ImageDataPart resolved = resolver.resolve(validPart);
        assertThat(resolved).isNotNull();
        assertThat(resolved.mediaType()).isEqualTo("image/png");
        assertThat(resolved.bytes()).isEqualTo(VALID_PNG_BYTES);
    }

    /**
     * Oracle 8: (10 MiB, 20 MiB] Image graceful degradation: 15 MiB valid image succeeds tool execution,
     * but transient injection injects degradation warning text without throwing unhandled exceptions.
     */
    @Test
    void testImageOverTenMegabytesGracefulDegradation() throws Exception {
        Path wsRoot = dataRoot.resolve("workspace");
        PersistentProjectWorkspaceService bindings = new PersistentProjectWorkspaceService(
                wsRoot, WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
        RestrictedWorkspaceService ws = new RestrictedWorkspaceService(wsRoot, WorkspaceLimits.defaults());
        String threadId = "v2-large-image-" + System.nanoTime();
        WorkspaceBinding binding = bindings.bindThreadWorkspace("default", threadId);

        int size = 15 * 1024 * 1024; // 15 MiB
        byte[] largeBytes = new byte[size];
        System.arraycopy(VALID_PNG_BYTES, 0, largeBytes, 0, Math.min(VALID_PNG_BYTES.length, 32));
        String path = "/mnt/user-data/workspace/large.png";
        ws.createWorkingFile(binding.ownerId(), binding.workspaceId(), "workspace/large.png", largeBytes);

        // 1. ProductionViewImageTool invocation succeeds (within 20 MiB limit)
        ProductionViewImageTool tool = new ProductionViewImageTool(threadId, bindings, ws);
        ProductionViewImageTool.Output output = tool.invoke(
                new ProductionViewImageTool.Input(path),
                toolContext(threadId));
        assertThat(output.status()).isEqualTo("success");
        assertThat(output.message()).isEqualTo("Successfully read image");

        // 2. injectVision handles (10 MiB, 20 MiB] gracefully without unhandled exception
        DurableHaifaFoundation foundation = new DurableHaifaFoundation(
                dataRoot.resolve("foundation"), new SecretKeySpec(new byte[32], "AES"));
        try {
            ResolvedModelSnapshot visionSnapshot = createModelSnapshot(true);
            HaifaRunBridge.ModelBinding visionBinding = new HaifaRunBridge.ModelBinding(
                    "vision-model", "1.0.0", new DummyChatModel(), visionSnapshot);
            HaifaRunBridge bridge = new HaifaRunBridge(
                    visionBinding,
                    promptTools(tool),
                    foundation,
                    ws,
                    "default",
                    threadId);

            ProviderToolCallCorrelationId correlationId = new ProviderToolCallCorrelationId("call-large-img");
            ModelMessage userMsg = ModelMessage.text(ModelMessageRole.USER, "View large image");
            ModelMessage assistantMsg = ModelMessage.assistant(
                    "",
                    List.of(new ModelToolCall(correlationId, "view_image", Map.of("image_path", path))));
            ModelMessage toolMsg = ModelMessage.tool(
                    correlationId,
                    "Successfully read image",
                    output.structuredData(),
                    false);

            AgentChatRequest request = createChatRequest(visionSnapshot, List.of(userMsg, assistantMsg, toolMsg));
            AgentChatRequest injected = bridge.injectVision(request);

            assertThat(injected.messages()).hasSize(4);
            ModelMessage lastMsg = injected.messages().get(3);
            assertThat(lastMsg.role()).isEqualTo(ModelMessageRole.USER);
            assertThat(lastMsg.content()).isEqualTo(
                    "[Image viewing skipped: image size (" + size + " bytes) exceeds runtime capacity limit (10 MiB). Upstream SDK limit bump required.]");
            assertThat(lastMsg.images()).isEmpty();
            bridge.close();
        } finally {
            foundation.close();
        }
    }

    // =========================================================================
    // Controlled Provider & Helpers
    // =========================================================================

    private byte[] controlledProviderResponse(JsonNode request, int call) throws IOException {
        String responseId = "view-image-" + call;
        String model = request.path("model").asText();
        JsonNode messages = request.path("messages");
        JsonNode last = messages.get(messages.size() - 1);
        List<String> tools = request.path("tools").findValuesAsText("name");

        String injectedImageDataUrl = null;
        for (JsonNode msg : messages) {
            if ("user".equals(msg.path("role").asText()) && msg.path("content").isArray()) {
                for (JsonNode part : msg.path("content")) {
                    if ("image_url".equals(part.path("type").asText())) {
                        injectedImageDataUrl = part.path("image_url").path("url").asText();
                    }
                }
            }
        }

        String chunk;
        if (injectedImageDataUrl != null && injectedImageDataUrl.startsWith("data:image/png;base64,")) {
            String b64 = injectedImageDataUrl.substring("data:image/png;base64,".length());
            byte[] imageBytes = Base64.getDecoder().decode(b64);
            String hash = sha256(imageBytes);
            chunk = textChunk(responseId, model,
                    "Vision Analysis: Verified PNG image with sha256=" + hash + ", content confirmed.");
        } else if ("text-only-controlled".equals(model)) {
            chunk = textChunk(responseId, model, "Text only model: cannot view images.");
        } else {
            String user = textOf(last.path("content"));
            if (user.contains("<current_uploads>") && tools.contains("view_image")) {
                Matcher pathMatch = CONTEXT_PATH.matcher(user);
                String imgPath = pathMatch.find() ? pathMatch.group(1) : "/mnt/user-data/uploads/chart.png";
                String arguments = json.writeValueAsString(Map.of("image_path", imgPath));
                chunk = json.writeValueAsString(Map.of(
                        "id", responseId, "model", model,
                        "choices", List.of(Map.of("index", 0, "finish_reason", "tool_calls",
                                "delta", Map.of("tool_calls", List.of(Map.of(
                                        "index", 0, "id", "call-view-" + call, "type", "function",
                                        "function", Map.of("name", "view_image", "arguments", arguments))))))));
            } else {
                chunk = textChunk(responseId, model, "NO_IMAGE_CONTEXT");
            }
        }

        return ("data: " + chunk + "\n\n"
                + "data: {\"id\":\"" + responseId + "\",\"model\":\"" + model + "\",\"choices\":[],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":10}}\n\n"
                + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
    }

    private String textChunk(String responseId, String model, String text) throws IOException {
        return json.writeValueAsString(Map.of(
                "id", responseId, "model", model,
                "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                        "delta", Map.of("content", text)))));
    }

    private RunResult runWithFiles(String threadId, String text, List<Map<String, Object>> files,
            String modelName) throws Exception {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "human");
        message.put("content", List.of(Map.of("type", "text", "text", text)));
        message.put("additional_kwargs", files.isEmpty() ? Map.of() : Map.of("files", files));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assistant_id", "lead_agent");
        body.put("input", Map.of("messages", List.of(message)));
        body.put("stream_mode", List.of("messages-tuple", "updates", "custom"));
        body.put("context", Map.of("model_name", modelName, "mode", "flash", "thread_id", threadId));
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
        for (JsonNode item : state.path("values").path("messages")) {
            if (!runId.equals(item.path("run_id").asText(runId))) continue;
            if ("ai".equals(item.path("type").asText()) && item.path("tool_calls").isEmpty()
                    && !item.path("content").asText().isEmpty()) {
                answer = item.path("content").asText();
            }
        }
        return new RunResult(runId, status, answer, runJson);
    }

    private static Map<String, Object> fileRef(String filename, long size) {
        return Map.of("filename", filename, "size", size,
                "path", "/mnt/user-data/uploads/" + filename, "status", "uploaded");
    }

    private HttpResponse<String> upload(String threadId, String filename, byte[] content) throws Exception {
        return multipart("/api/threads/" + threadId + "/uploads", "files", filename, content);
    }

    private HttpResponse<String> multipart(String path, String field, String filename, byte[] content)
            throws Exception {
        String boundary = "v2-boundary-" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field
                + "\"; filename=\"" + filename + "\"\r\nContent-Type: image/png\r\n\r\n")
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

    private static String textOf(JsonNode content) {
        if (content.isTextual()) return content.asText();
        StringBuilder text = new StringBuilder();
        for (JsonNode part : content) text.append(part.path("text").asText());
        return text.toString();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static JavaToolContext toolContext(String threadId) {
        return new JavaToolContext(
                new AgentRunId("run-view-" + threadId),
                Optional.of(new AgentSessionId("session-view-" + threadId)),
                new ToolCallId("tool-call-view-1"),
                new TenantRef("default"),
                new PrincipalRef("default-public-user", "user"),
                Instant.now().plusSeconds(60),
                Optional.empty(),
                () -> false,
                Map.of());
    }

    private static ResolvedModelSnapshot createModelSnapshot(boolean supportsVision) {
        Set<ModelCapability> capabilities = new LinkedHashSet<>();
        capabilities.add(ModelCapability.TEXT_CHAT);
        capabilities.add(ModelCapability.TOOL_CALLING);
        if (supportsVision) {
            capabilities.add(ModelCapability.IMAGE_INPUT);
            capabilities.add(ModelCapability.IMAGE_UPLOAD_INPUT);
            capabilities.add(ModelCapability.IMAGE_URL_INPUT);
        }
        Map<String, Object> providerOptions = supportsVision
                ? Map.of("supports_vision", true)
                : Map.of();
        String modelName = supportsVision ? "vision-model" : "non-vision-model";
        return ResolvedModelSnapshot.create(
                new ModelProviderId("test-provider"),
                "1.0.0",
                new ModelDefinitionId(modelName),
                "1.0.0",
                modelName,
                "openai-compatible",
                "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                java.net.URI.create("https://model.invalid"),
                new CredentialRef("env://TEST_KEY"),
                false,
                Set.copyOf(capabilities),
                32_768,
                1_024,
                providerOptions,
                Map.of());
    }

    private static AgentChatRequest createChatRequest(ResolvedModelSnapshot snapshot, List<ModelMessage> messages) {
        return new AgentChatRequest(
                new ModelCallId("call-1"),
                new ModelRequestId("req-1"),
                new AgentRunId("run-1"),
                1,
                1,
                snapshot,
                messages,
                List.of(),
                1000,
                Duration.ofSeconds(30),
                Map.of(),
                Optional.empty());
    }

    private static final class DummyChatModel implements AgentChatModel {
        @Override
        public AgentChatResponse invoke(AgentChatRequest request) {
            return new AgentChatResponse(
                    "dummy-response",
                    request.model().providerModelId(),
                    "I see the image",
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(1, 1),
                    "",
                    Map.of());
        }

        @Override
        public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
            return invoke(request);
        }
    }

    private static List<JavaTool<?, ?>> promptTools(ProductionViewImageTool imageTool) {
        List<JavaTool<?, ?>> tools = new ArrayList<>();
        tools.add(imageTool);
        for (String name : List.of(
                "read_file",
                "write_file",
                "str_replace",
                "present_files",
                "list_uploaded_files",
                "grep",
                "web_search",
                "web_fetch",
                "ask_clarification")) {
            tools.add(namedTool(name));
        }
        return tools;
    }

    private static JavaTool<Empty, Empty> namedTool(String name) {
        var spec = JavaToolSpec.builder(name, Empty.class, Empty.class)
                .description("Test tool " + name)
                .pure()
                .build();
        return new JavaTool<>() {
            @Override
            public JavaToolSpec<Empty, Empty> spec() {
                return spec;
            }
            @Override
            public Empty invoke(Empty input, JavaToolContext context) {
                return new Empty();
            }
        };
    }

    public record Empty() {}

    private record RunResult(String runId, String status, String answer, String runJson) {}
}

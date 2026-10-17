package io.haifa.agent.superharness.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
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
import io.haifa.agent.superharness.server.config.SubagentRunConfiguration;
import io.haifa.agent.superharness.server.prompt.AgentPromptContext;
import io.haifa.agent.superharness.server.prompt.LeadPromptComposer;
import io.haifa.agent.superharness.server.tool.file.FileToolSession;
import io.haifa.agent.superharness.server.tool.file.ProductionReadFileTool;
import io.haifa.agent.superharness.server.tool.file.ProductionViewImageTool;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceBinding;
import io.haifa.agent.superharness.workspace.WorkspaceChangeLimits;
import io.haifa.agent.superharness.workspace.WorkspaceLimits;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ModelVisionInjectionTest {

    @TempDir
    Path tempDir;

    private RestrictedWorkspaceService workspace;
    private WorkspaceBinding binding;
    private DurableHaifaFoundation foundation;
    private ProductionViewImageTool viewImageTool;
    private ProductionReadFileTool readFileTool;
    private HaifaRunBridge visionBridge;
    private HaifaRunBridge nonVisionBridge;
    private ResolvedModelSnapshot visionModelSnapshot;
    private ResolvedModelSnapshot nonVisionModelSnapshot;

    private static final byte[] PNG_HEADER = new byte[] {
            (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47,
            (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A
    };

    @BeforeEach
    void setUp() throws Exception {
        Path root = tempDir.resolve("workspace-root");
        PersistentProjectWorkspaceService bindings = new PersistentProjectWorkspaceService(
                root, WorkspaceLimits.defaults(), WorkspaceChangeLimits.defaults());
        this.workspace = new RestrictedWorkspaceService(root, WorkspaceLimits.defaults());
        String threadId = "thread-vision-injection-test";
        this.binding = bindings.bindThreadWorkspace("default", threadId);

        this.viewImageTool = new ProductionViewImageTool(threadId, bindings, workspace);
        this.readFileTool = new ProductionReadFileTool(threadId, bindings, workspace, new FileToolSession());

        Path foundationDir = tempDir.resolve("foundation");
        this.foundation = new DurableHaifaFoundation(foundationDir, new SecretKeySpec(new byte[32], "AES"));

        this.visionModelSnapshot = createModelSnapshot(true);
        this.nonVisionModelSnapshot = createModelSnapshot(false);

        HaifaRunBridge.ModelBinding visionBinding = new HaifaRunBridge.ModelBinding(
                "vision-model", "1.0.0", new DummyChatModel(), visionModelSnapshot);
        HaifaRunBridge.ModelBinding nonVisionBinding = new HaifaRunBridge.ModelBinding(
                "non-vision-model", "1.0.0", new DummyChatModel(), nonVisionModelSnapshot);

        this.visionBridge = new HaifaRunBridge(
                visionBinding,
                promptTools(viewImageTool),
                foundation,
                workspace,
                "default",
                threadId);

        this.nonVisionBridge = new HaifaRunBridge(
                nonVisionBinding,
                promptTools(viewImageTool),
                foundation,
                workspace,
                "default",
                threadId);
    }

    @AfterEach
    void tearDown() {
        if (visionBridge != null) {
            visionBridge.close();
        }
        if (nonVisionBridge != null) {
            nonVisionBridge.close();
        }
        if (foundation != null) {
            foundation.close();
        }
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

    private byte[] makeImageBytes(int totalSize) {
        byte[] bytes = new byte[totalSize];
        System.arraycopy(PNG_HEADER, 0, bytes, 0, PNG_HEADER.length);
        for (int i = PNG_HEADER.length; i < totalSize; i++) {
            bytes[i] = (byte) (i & 0xFF);
        }
        return bytes;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

    @Test
    void supportsVision_identifiesVisionCapabilityCorrectly() {
        assertThat(HaifaRunBridge.supportsVision(visionModelSnapshot)).isTrue();
        assertThat(HaifaRunBridge.supportsVision(nonVisionModelSnapshot)).isFalse();
        assertThat(HaifaRunBridge.supportsVision(null)).isFalse();
    }

    @Test
    void filterToolsForVision_includesOrExcludesViewImage() {
        Set<String> allTools = Set.of("read_file", "write_file", "view_image");

        Set<String> withVision = LeadPromptComposer.filterToolsForVision(allTools, true);
        assertThat(withVision).contains("view_image", "read_file", "write_file");

        Set<String> withoutVision = LeadPromptComposer.filterToolsForVision(allTools, false);
        assertThat(withoutVision).doesNotContain("view_image");
        assertThat(withoutVision).contains("read_file", "write_file");
    }

    @Test
    void injectVision_normalImage_injectsMultimodalUserMessage() {
        byte[] imgBytes = makeImageBytes(1024);
        String path = "/mnt/user-data/workspace/diagram.png";
        workspace.createWorkingFile(binding.ownerId(), binding.workspaceId(), "workspace/diagram.png", imgBytes);
        String hash = sha256(imgBytes);

        ProviderToolCallCorrelationId correlationId = new ProviderToolCallCorrelationId("call-img-1");
        ModelMessage userMsg = ModelMessage.text(ModelMessageRole.USER, "What is in diagram.png?");
        ModelMessage assistantMsg = ModelMessage.assistant(
                "",
                List.of(new ModelToolCall(correlationId, "view_image", Map.of("image_path", path))));
        ModelMessage toolMsg = ModelMessage.tool(
                correlationId,
                "Successfully read image",
                Map.of("status", "success", "path", path, "mime_type", "image/png", "sha256", hash, "size", imgBytes.length),
                false);

        AgentChatRequest request = createChatRequest(visionModelSnapshot, List.of(userMsg, assistantMsg, toolMsg));
        AgentChatRequest injected = visionBridge.injectVision(request);

        assertThat(injected.messages()).hasSize(4);
        ModelMessage lastMsg = injected.messages().get(3);
        assertThat(lastMsg.role()).isEqualTo(ModelMessageRole.USER);
        assertThat(lastMsg.content()).isEqualTo("Here are the images you've viewed:\n- **" + path + "** (image/png)");
        assertThat(lastMsg.images()).hasSize(1);
        ImageDataPart part = (ImageDataPart) lastMsg.images().get(0);
        assertThat(part.mediaType()).isEqualTo("image/png");
        assertThat(part.bytes()).isEqualTo(imgBytes);
    }

    @Test
    void injectVision_largeImage10to20MiB_injectsGracefulTextWithoutImageDataPart() {
        int size = 15 * 1024 * 1024; // 15 MiB
        byte[] largeBytes = makeImageBytes(size);
        String path = "/mnt/user-data/workspace/huge.png";
        workspace.createWorkingFile(binding.ownerId(), binding.workspaceId(), "workspace/huge.png", largeBytes);
        String hash = sha256(largeBytes);

        ProviderToolCallCorrelationId correlationId = new ProviderToolCallCorrelationId("call-img-2");
        ModelMessage userMsg = ModelMessage.text(ModelMessageRole.USER, "View huge image");
        ModelMessage assistantMsg = ModelMessage.assistant(
                "",
                List.of(new ModelToolCall(correlationId, "view_image", Map.of("image_path", path))));
        ModelMessage toolMsg = ModelMessage.tool(
                correlationId,
                "Successfully read image",
                Map.of("status", "success", "path", path, "mime_type", "image/png", "sha256", hash, "size", size),
                false);

        AgentChatRequest request = createChatRequest(visionModelSnapshot, List.of(userMsg, assistantMsg, toolMsg));
        AgentChatRequest injected = visionBridge.injectVision(request);

        assertThat(injected.messages()).hasSize(4);
        ModelMessage lastMsg = injected.messages().get(3);
        assertThat(lastMsg.role()).isEqualTo(ModelMessageRole.USER);
        assertThat(lastMsg.content()).isEqualTo(
                "[Image viewing skipped: image size (" + size + " bytes) exceeds runtime capacity limit (10 MiB). Upstream SDK limit bump required.]");
        assertThat(lastMsg.images()).isEmpty();
    }

    @Test
    void injectVision_missingFile_injectsDegradationText() {
        String path = "/mnt/user-data/workspace/deleted.png";
        ProviderToolCallCorrelationId correlationId = new ProviderToolCallCorrelationId("call-img-3");
        ModelMessage userMsg = ModelMessage.text(ModelMessageRole.USER, "View deleted image");
        ModelMessage assistantMsg = ModelMessage.assistant(
                "",
                List.of(new ModelToolCall(correlationId, "view_image", Map.of("image_path", path))));
        ModelMessage toolMsg = ModelMessage.tool(
                correlationId,
                "Successfully read image",
                Map.of("status", "success", "path", path, "mime_type", "image/png", "sha256", "abc", "size", 100),
                false);

        AgentChatRequest request = createChatRequest(visionModelSnapshot, List.of(userMsg, assistantMsg, toolMsg));
        AgentChatRequest injected = visionBridge.injectVision(request);

        assertThat(injected.messages()).hasSize(4);
        ModelMessage lastMsg = injected.messages().get(3);
        assertThat(lastMsg.role()).isEqualTo(ModelMessageRole.USER);
        assertThat(lastMsg.content()).isEqualTo("(file unavailable or changed: " + path + ")");
        assertThat(lastMsg.images()).isEmpty();
    }

    @Test
    void injectVision_tamperedFile_injectsDegradationText() {
        byte[] imgBytes = makeImageBytes(512);
        String path = "/mnt/user-data/workspace/tampered.png";
        workspace.createWorkingFile(binding.ownerId(), binding.workspaceId(), "workspace/tampered.png", imgBytes);

        String fakeHash = "0000000000000000000000000000000000000000000000000000000000000000";
        ProviderToolCallCorrelationId correlationId = new ProviderToolCallCorrelationId("call-img-4");
        ModelMessage userMsg = ModelMessage.text(ModelMessageRole.USER, "View tampered image");
        ModelMessage assistantMsg = ModelMessage.assistant(
                "",
                List.of(new ModelToolCall(correlationId, "view_image", Map.of("image_path", path))));
        ModelMessage toolMsg = ModelMessage.tool(
                correlationId,
                "Successfully read image",
                Map.of("status", "success", "path", path, "mime_type", "image/png", "sha256", fakeHash, "size", imgBytes.length),
                false);

        AgentChatRequest request = createChatRequest(visionModelSnapshot, List.of(userMsg, assistantMsg, toolMsg));
        AgentChatRequest injected = visionBridge.injectVision(request);

        assertThat(injected.messages()).hasSize(4);
        ModelMessage lastMsg = injected.messages().get(3);
        assertThat(lastMsg.role()).isEqualTo(ModelMessageRole.USER);
        assertThat(lastMsg.content()).isEqualTo("(file unavailable or changed: " + path + ")");
        assertThat(lastMsg.images()).isEmpty();
    }

    @Test
    void injectVision_idempotency_doesNotInjectTwice() {
        byte[] imgBytes = makeImageBytes(1024);
        String path = "/mnt/user-data/workspace/diagram.png";
        workspace.createWorkingFile(binding.ownerId(), binding.workspaceId(), "workspace/diagram.png", imgBytes);
        String hash = sha256(imgBytes);

        ProviderToolCallCorrelationId correlationId = new ProviderToolCallCorrelationId("call-img-5");
        ModelMessage userMsg = ModelMessage.text(ModelMessageRole.USER, "View diagram");
        ModelMessage assistantMsg = ModelMessage.assistant(
                "",
                List.of(new ModelToolCall(correlationId, "view_image", Map.of("image_path", path))));
        ModelMessage toolMsg = ModelMessage.tool(
                correlationId,
                "Successfully read image",
                Map.of("status", "success", "path", path, "mime_type", "image/png", "sha256", hash, "size", imgBytes.length),
                false);

        AgentChatRequest request = createChatRequest(visionModelSnapshot, List.of(userMsg, assistantMsg, toolMsg));
        AgentChatRequest firstInjection = visionBridge.injectVision(request);
        assertThat(firstInjection.messages()).hasSize(4);

        AgentChatRequest secondInjection = visionBridge.injectVision(firstInjection);
        assertThat(secondInjection.messages()).hasSize(4);
    }

    @Test
    void injectVision_nonVisionModel_returnsRequestUnmodified() {
        byte[] imgBytes = makeImageBytes(1024);
        String path = "/mnt/user-data/workspace/diagram.png";
        workspace.createWorkingFile(binding.ownerId(), binding.workspaceId(), "workspace/diagram.png", imgBytes);
        String hash = sha256(imgBytes);

        ProviderToolCallCorrelationId correlationId = new ProviderToolCallCorrelationId("call-img-6");
        ModelMessage userMsg = ModelMessage.text(ModelMessageRole.USER, "View diagram");
        ModelMessage assistantMsg = ModelMessage.assistant(
                "",
                List.of(new ModelToolCall(correlationId, "view_image", Map.of("image_path", path))));
        ModelMessage toolMsg = ModelMessage.tool(
                correlationId,
                "Successfully read image",
                Map.of("status", "success", "path", path, "mime_type", "image/png", "sha256", hash, "size", imgBytes.length),
                false);

        AgentChatRequest request = createChatRequest(nonVisionModelSnapshot, List.of(userMsg, assistantMsg, toolMsg));
        AgentChatRequest injected = nonVisionBridge.injectVision(request);

        assertThat(injected.messages()).hasSize(3);
    }

    @Test
    void injectVision_unrelatedToolCall_returnsRequestUnmodified() {
        ProviderToolCallCorrelationId correlationId = new ProviderToolCallCorrelationId("call-file-1");
        ModelMessage userMsg = ModelMessage.text(ModelMessageRole.USER, "Read file");
        ModelMessage assistantMsg = ModelMessage.assistant(
                "",
                List.of(new ModelToolCall(correlationId, "read_file", Map.of("path", "/mnt/user-data/workspace/notes.txt"))));
        ModelMessage toolMsg = ModelMessage.tool(
                correlationId,
                "File content",
                Map.of("content", "hello"),
                false);

        AgentChatRequest request = createChatRequest(visionModelSnapshot, List.of(userMsg, assistantMsg, toolMsg));
        AgentChatRequest injected = visionBridge.injectVision(request);

        assertThat(injected.messages()).hasSize(3);
    }

    @Test
    void injectVision_preservesOriginalRequestWithoutPersistencePollution() {
        byte[] imgBytes = makeImageBytes(1024);
        String path = "/mnt/user-data/workspace/diagram.png";
        workspace.createWorkingFile(binding.ownerId(), binding.workspaceId(), "workspace/diagram.png", imgBytes);
        String hash = sha256(imgBytes);

        ProviderToolCallCorrelationId correlationId = new ProviderToolCallCorrelationId("call-img-7");
        ModelMessage userMsg = ModelMessage.text(ModelMessageRole.USER, "What is in diagram.png?");
        ModelMessage assistantMsg = ModelMessage.assistant(
                "",
                List.of(new ModelToolCall(correlationId, "view_image", Map.of("image_path", path))));
        ModelMessage toolMsg = ModelMessage.tool(
                correlationId,
                "Successfully read image",
                Map.of("status", "success", "path", path, "mime_type", "image/png", "sha256", hash, "size", imgBytes.length),
                false);

        List<ModelMessage> originalMessages = List.of(userMsg, assistantMsg, toolMsg);
        AgentChatRequest request = createChatRequest(visionModelSnapshot, originalMessages);

        AgentChatRequest injected = visionBridge.injectVision(request);

        // Injected copy has 4 messages
        assertThat(injected.messages()).hasSize(4);
        // Original request messages remain untouched with 3 messages
        assertThat(request.messages()).hasSize(3);
        assertThat(originalMessages).hasSize(3);
    }

    @Test
    void preflightSignatures_matchBuiltBridgeForVisionAndNonVisionModels() {
        List<JavaTool<?, ?>> tools = promptTools(viewImageTool);
        SubagentRunConfiguration delegation = SubagentRunConfiguration.disabled();
        Set<String> platformToolNames = Set.of();

        // 1. Non-vision model: preflight must match attached bridge
        String expectedNonVisionPromptSig = HaifaRunBridge.promptSignatureFor(
                tools, null, AgentPromptContext.defaults(), delegation, platformToolNames, false);
        String expectedNonVisionDelegationSig = delegation.signature(tools, "", "");

        assertThat(nonVisionBridge.promptSignature()).isEqualTo(expectedNonVisionPromptSig);
        assertThat(nonVisionBridge.delegationSignature()).isEqualTo(expectedNonVisionDelegationSig);

        // 2. Vision model: preflight must match attached bridge
        String expectedVisionPromptSig = HaifaRunBridge.promptSignatureFor(
                tools, null, AgentPromptContext.defaults(), delegation, platformToolNames, true);
        String expectedVisionDelegationSig = delegation.signature(tools, "", "");

        assertThat(visionBridge.promptSignature()).isEqualTo(expectedVisionPromptSig);
        assertThat(visionBridge.delegationSignature()).isEqualTo(expectedVisionDelegationSig);
    }

    @Test
    void childToolsFor_gatesVisionToolPerChildModelCapability() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var childDef = mapper.createObjectNode()
                .put("name", "child-agent")
                .put("description", "A child agent")
                .put("system_prompt", "Help with tasks")
                .put("max_turns", 3)
                .put("timeout_seconds", 60);
        childDef.putArray("tools").add("read_file").add("view_image");

        HaifaRunBridge.ModelBinding visionChildBinding = new HaifaRunBridge.ModelBinding(
                "vision-model", "1.0.0", new DummyChatModel(), visionModelSnapshot);
        HaifaRunBridge.ModelBinding nonVisionChildBinding = new HaifaRunBridge.ModelBinding(
                "non-vision-model", "1.0.0", new DummyChatModel(), nonVisionModelSnapshot);

        var visionChild = new SubagentRunConfiguration.Child(childDef, visionChildBinding);
        var nonVisionChild = new SubagentRunConfiguration.Child(childDef, nonVisionChildBinding);

        Set<String> parentTools = Set.of("read_file", "write_file", "view_image");

        // Vision child retains view_image
        Set<String> visionChildTools = HaifaRunBridge.childToolsFor(visionChild, parentTools);
        assertThat(visionChildTools).contains("read_file", "view_image");

        // Non-vision child has view_image filtered out
        Set<String> nonVisionChildTools = HaifaRunBridge.childToolsFor(nonVisionChild, parentTools);
        assertThat(nonVisionChildTools).contains("read_file");
        assertThat(nonVisionChildTools).doesNotContain("view_image");
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
}

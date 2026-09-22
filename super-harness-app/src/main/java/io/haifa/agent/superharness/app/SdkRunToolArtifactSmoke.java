package io.haifa.agent.superharness.app;

import io.haifa.agent.artifact.ArtifactProvenance;
import io.haifa.agent.artifact.ArtifactService;
import io.haifa.agent.artifact.ArtifactType;
import io.haifa.agent.artifact.InMemoryArtifactPayloadStore;
import io.haifa.agent.artifact.InMemoryArtifactStore;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.starter.HaifaAgentStarter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Public-artifact smoke used to prove the first real Run -&gt; Tool -&gt; Artifact path. */
public final class SdkRunToolArtifactSmoke {
    private static final String TOOL_ALIAS = "artifact_render";
    private static final byte[] EXPECTED_CONTENT = "# W01 SDK smoke\n\nPublished by the real Tool result.\n"
            .getBytes(StandardCharsets.UTF_8);

    private SdkRunToolArtifactSmoke() {}

    public static SmokeEvidence execute() throws Exception {
        var modelCalls = new AtomicInteger();
        var rendered = new AtomicReference<RenderedArtifact>();
        var invocation = new AtomicReference<JavaToolContext>();
        var tool = new ArtifactRenderTool(rendered, invocation);

        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            if (modelCalls.incrementAndGet() == 1) {
                return new AgentChatResponse(
                        "tool",
                        request.model().providerModelId(),
                        "",
                        List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("artifact-render-1"),
                                TOOL_ALIAS,
                                Map.of(
                                        "title", "W01 SDK smoke",
                                        "content", new String(EXPECTED_CONTENT, StandardCharsets.UTF_8)))),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unpriced(4, 1),
                        "",
                        Map.of());
            }
            return new AgentChatResponse(
                    "final",
                    request.model().providerModelId(),
                    "Artifact published",
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(4, 2),
                    "",
                    Map.of());
        };

        try (var agent = HaifaAgentStarter.builder()
                .name("Super Harness W01 smoke")
                .instructions("Render the requested deterministic local artifact.")
                .model(model, snapshot())
                .tool(tool)
                .build()) {
            var conversation = agent.conversations()
                    .start(new StartConversationCommand(
                            "w01-sdk-run-tool-artifact", "W01 SDK smoke", "Render the smoke artifact."));
            var completed = agent.runs().await(conversation.runId());
            var toolResult = require(rendered.get(), "Tool result was not produced");
            var toolContext = require(invocation.get(), "Tool invocation context was not captured");

            if (!toolContext.runId().equals(conversation.runId())) {
                throw new IllegalStateException("Tool invocation was not correlated to the started Run");
            }

            var artifacts = new ArtifactService(
                    new InMemoryArtifactStore(),
                    new InMemoryArtifactPayloadStore(),
                    () -> "w01-artifact-1",
                    () -> Instant.parse("2026-09-22T00:00:00Z"));
            byte[] content = toolResult.content().getBytes(StandardCharsets.UTF_8);
            var artifact = artifacts.publish(
                    new ArtifactType("report"),
                    toolResult.title(),
                    content,
                    "text/markdown",
                    new ArtifactProvenance(
                            new ProjectRef("super-harness-w01"),
                            "workspace://super-harness-w01",
                            conversation.runId(),
                            conversation.record().sessionId(),
                            null,
                            "java-tool:" + TOOL_ALIAS,
                            "reports/w01-sdk-smoke.md",
                            sha256(content),
                            "local-evidence-only",
                            toolContext.principal()));
            byte[] loaded = artifacts.load(artifact);
            if (!java.util.Arrays.equals(content, loaded)) {
                throw new IllegalStateException("Published Artifact payload did not round-trip");
            }

            return new SmokeEvidence(
                    conversation.runId().value(),
                    TOOL_ALIAS,
                    artifact.id().value(),
                    artifact.payload().sha256(),
                    artifact.payload().byteCount(),
                    completed.output().orElseThrow());
        }
    }

    private static ResolvedModelSnapshot snapshot() {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("super-harness-smoke"),
                "1.0.0",
                new ModelDefinitionId("super-harness-smoke-model"),
                "1.0.0",
                "super-harness-smoke-model",
                "super-harness-smoke-adapter",
                "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                URI.create("https://model.invalid"),
                new CredentialRef("env://SUPER_HARNESS_SMOKE_KEY"),
                false,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                8_192,
                1_024,
                Map.of(),
                Map.of());
    }

    private static String sha256(byte[] content) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static <T> T require(T value, String message) {
        if (value == null) {
            throw new IllegalStateException(message);
        }
        return value;
    }

    public record SmokeEvidence(
            String runId,
            String toolAlias,
            String artifactId,
            String artifactSha256,
            long artifactBytes,
            String output) {}

    public record RenderArtifactRequest(String title, String content) {}

    public record RenderedArtifact(String title, String content) {}

    private static final class ArtifactRenderTool implements JavaTool<RenderArtifactRequest, RenderedArtifact> {
        private static final JavaToolSpec<RenderArtifactRequest, RenderedArtifact> SPEC = JavaToolSpec.builder(
                        TOOL_ALIAS, RenderArtifactRequest.class, RenderedArtifact.class)
                .title("Render local artifact")
                .description("Deterministically renders artifact bytes without publishing or external side effects")
                .pure()
                .build();

        private final AtomicReference<RenderedArtifact> rendered;
        private final AtomicReference<JavaToolContext> invocation;

        private ArtifactRenderTool(
                AtomicReference<RenderedArtifact> rendered, AtomicReference<JavaToolContext> invocation) {
            this.rendered = rendered;
            this.invocation = invocation;
        }

        @Override
        public JavaToolSpec<RenderArtifactRequest, RenderedArtifact> spec() {
            return SPEC;
        }

        @Override
        public RenderedArtifact invoke(RenderArtifactRequest request, JavaToolContext context) {
            var result = new RenderedArtifact(request.title(), request.content());
            rendered.set(result);
            invocation.set(context);
            return result;
        }
    }
}

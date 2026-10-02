package io.haifa.agent.superharness.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Local diagnostic only: replays DeerFlowConfigurationApi's managed-model probe without swallowing errors. Do not commit. */
@EnabledIfEnvironmentVariable(named = "SILICONFLOW_API_KEY", matches = ".+")
class SiliconFlowLiveProbeDiagnosticTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    record Variant(String label, int contextWindow, int maxTokens, HttpClient.Version version, Duration timeout) {}

    @Test
    void diagnoseManagedModelProbe() throws Exception {
        String key = System.getenv("SILICONFLOW_API_KEY");
        List<String> models = List.of(System.getenv()
                .getOrDefault("SF_MODELS", "Pro/zai-org/GLM-5.1,zai-org/GLM-5.3").split(","));
        List<String> bases = List.of("https://api.siliconflow.cn", "https://api.siliconflow.cn/v1");
        List<Variant> variants = List.of(
                new Variant("as-configured (ctx=1048576,max=131072,HTTP/2,20s)", 1_048_576, 131_072, HttpClient.Version.HTTP_2, Duration.ofSeconds(20)),
                new Variant("http-1.1", 1_048_576, 131_072, HttpClient.Version.HTTP_1_1, Duration.ofSeconds(20)),
                new Variant("small-limits (ctx=8192,max=1024)", 8_192, 1_024, HttpClient.Version.HTTP_2, Duration.ofSeconds(20)),
                new Variant("timeout-60s", 1_048_576, 131_072, HttpClient.Version.HTTP_2, Duration.ofSeconds(60)));

        StringBuilder report = new StringBuilder("SiliconFlow managed-model probe diagnostic\n");
        for (String model : models) {
            for (String base : bases) {
                for (Variant variant : variants) {
                    report.append(run(key, model.trim(), base, variant));
                }
            }
        }
        String text = report.toString().replace(key, "<redacted>");
        System.out.println(text);
        Files.writeString(Path.of("target", "siliconflow-probe-diagnostic.txt"), text, StandardCharsets.UTF_8);
    }

    private static String run(String key, String model, String base, Variant v) {
        String stage = "snapshot";
        long start = System.nanoTime();
        String verdict;
        String detail;
        try {
            URI endpoint = URI.create(base);
            CredentialResolver credentials = ignored -> new ResolvedCredential(key);
            ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                    new ModelProviderId("super-harness-managed-openai-compatible"), "0.1.1-SNAPSHOT",
                    new ModelDefinitionId("glm-diagnostic"), "diagnostic", model,
                    "openai-compatible", "1.0.0",
                    ModelApiStyles.OPENAI_CHAT_COMPLETIONS, ModelApiBindingDefinition.STANDARD_DIALECT,
                    endpoint, new CredentialRef("managed://default/glm-diagnostic"), true,
                    Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                    v.contextWindow(), v.maxTokens(), Map.of(), Map.of());
            stage = "client";
            var chat = new OpenAiCompatibleChatModel("openai-compatible", "1.0.0",
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(5))
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .version(v.version())
                            .build(),
                    JSON, credentials, false, 4 * 1024 * 1024);
            var tool = new ModelToolSpecification("connection_check", "1", "Check the connection",
                    "connection-check-input", "1", Map.of("type", "object", "properties", Map.of()), true);
            var request = new AgentChatRequest(new ModelCallId("managed-model-probe"),
                    new AgentRunId("managed-model-probe"), 1, 1, snapshot,
                    List.of(ModelMessage.text(ModelMessageRole.USER, "Call connection_check.")),
                    List.of(tool), Math.min(snapshot.maxOutputTokens(), 256), v.timeout(), Map.of());
            stage = "invoke";
            AgentChatResponse response = chat.invokeStreaming(request, event -> ModelStreamControl.CONTINUE);
            boolean ok = response.toolCalls().stream()
                    .anyMatch(c -> c.name().equals("connection_check") && c.arguments().isEmpty());
            verdict = ok ? "SUCCESS" : "TOOL_CALL_MISSING";
            detail = "toolCalls=" + response.toolCalls().stream().map(c -> c.name() + c.arguments()).toList();
        } catch (Throwable error) {
            verdict = "CONNECTION_FAILED (stage=" + stage + ")";
            detail = chain(error);
        }
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        return String.format("%n[%s] model=%s base=%s variant=%s  %d ms%n    %s%n", verdict, model, base, v.label(), ms, detail);
    }

    private static String chain(Throwable error) {
        StringBuilder b = new StringBuilder();
        for (Throwable c = error; c != null && b.length() < 3000; c = c.getCause()) {
            b.append("\n      ").append(c.getClass().getName()).append(": ").append(c.getMessage());
            if (c.getCause() == c) break;
        }
        StackTraceElement[] frames = error.getStackTrace();
        for (int i = 0; i < Math.min(4, frames.length); i++) {
            b.append("\n        at ").append(frames[i]);
        }
        return b.toString();
    }
}
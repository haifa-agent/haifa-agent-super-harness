package io.haifa.agent.superharness.server.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelStreamSink;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.sdk.diagnostics.PromptDiagnostics;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.superharness.server.config.McpRunTools;
import io.haifa.agent.superharness.server.config.SkillConfigStore;
import io.haifa.agent.superharness.server.config.SkillRunTools;
import io.haifa.agent.superharness.server.run.DurableHaifaFoundation;
import io.haifa.agent.superharness.server.run.HaifaRunBridge;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P3 preflight: prove whether the SDK injects its own {@code SKILL} prompt layer once the Skill
 * platform is installed. The result decides the {@code skills_section} rendering (D3). The
 * controller reads {@code W03_PROMPT_DIAGNOSTICS_*} and {@code W03_SKILL_TOOL_SPEC} from surefire
 * output as raw evidence.
 */
public class LeadPromptPreflightProbeTest {
    @TempDir
    Path dataDirectory;

    @Test
    void skillPlatformComponentSourcesWithAndWithoutSkillProjection() throws Exception {
        var repository = SkillConfigStore.repositoryRootFromWorkingDirectory();
        var store = new SkillConfigStore(new ObjectMapper(), dataDirectory, repository);
        var withSkills = store.projection(SkillRunTools.Selection.all());
        var withoutSkills = store.projection(SkillRunTools.Selection.none());
        assertThat(withSkills.allowedSkills()).containsExactly("consulting-analysis");
        assertThat(withoutSkills.allowedSkills()).isEmpty();

        try (var foundation = new DurableHaifaFoundation(
                dataDirectory, new SecretKeySpec(new byte[32], "AES"))) {
            report("with-skill-platform-a", runOnce(foundation, withSkills, "p3-with-skills-a"));
            report("with-skill-platform-b", runOnce(foundation, withSkills, "p3-with-skills-b"));
            report("without-skill-projection", runOnce(foundation, withoutSkills, "p3-without-skills"));
        }
    }

    @Test
    void capturesSkillToolSpecificationsOfferedToTheModel() throws Exception {
        var store = new SkillConfigStore(
                new ObjectMapper(), dataDirectory, SkillConfigStore.repositoryRootFromWorkingDirectory());
        var projection = store.projection(SkillRunTools.Selection.all());
        try (var foundation = new DurableHaifaFoundation(
                dataDirectory, new SecretKeySpec(new byte[32], "AES"))) {
            var capturing = new CapturingModel();
            try (var bridge = new HaifaRunBridge(
                    LocalProbeModelTestSupport.binding(capturing), promptTools(), foundation,
                    McpRunTools.Projection.empty(), projection)) {
                runToTerminal(bridge, "p3-tool-specs", "tool spec probe");
            }
            capturing.tools.forEach(tool -> System.out.println(
                    "W03_SKILL_TOOL_SPEC name=" + tool.name() + " schema=" + tool.inputJsonSchema()));
        }
    }

    @Test
    void promptSignatureFollowsSkillSelectionAndMatchesTheBuiltBridge() throws Exception {
        var store = new SkillConfigStore(
                new ObjectMapper(), dataDirectory, SkillConfigStore.repositoryRootFromWorkingDirectory());
        var withSkills = store.projection(SkillRunTools.Selection.all());
        var withoutSkills = store.projection(SkillRunTools.Selection.none());
        String enabled = HaifaRunBridge.promptSignatureFor(promptTools(), withSkills);
        String disabled = HaifaRunBridge.promptSignatureFor(promptTools(), withoutSkills);
        // A restart-recovered Bridge must reproduce the original Run's prompt signature.
        assertThat(HaifaRunBridge.promptSignatureFor(promptTools(), withSkills.forRecovery()))
                .isEqualTo(enabled);
        assertThat(enabled).matches("[0-9a-f]{64}").isNotEqualTo(disabled);
        try (var foundation = new DurableHaifaFoundation(
                dataDirectory, new SecretKeySpec(new byte[32], "AES"))) {
            try (var bridge = new HaifaRunBridge(
                    LocalProbeModelTestSupport.binding(), promptTools(), foundation,
                    McpRunTools.Projection.empty(), withSkills)) {
                assertThat(bridge.promptSignature()).isEqualTo(enabled);
            }
        }
    }

    private PromptDiagnostics runOnce(
            DurableHaifaFoundation foundation, SkillRunTools.Projection projection, String threadId)
            throws InterruptedException {
        try (var bridge = new HaifaRunBridge(
                LocalProbeModelTestSupport.binding(),
                promptTools(),
                foundation,
                McpRunTools.Projection.empty(),
                projection)) {
            return bridge.promptDiagnostics(runToTerminal(bridge, threadId, "diagnostics probe"));
        }
    }

    private static List<? extends JavaTool<?, ?>> promptTools() {
        return java.util.stream.Stream.of("read_file", "write_file", "str_replace", "present_files",
                        "list_uploaded_files", "grep", "web_search", "web_fetch", "ask_clarification")
                .map(LeadPromptPreflightProbeTest::namedTool).toList();
    }

    private static JavaTool<Empty, Empty> namedTool(String name) {
        JavaToolSpec<Empty, Empty> spec = JavaToolSpec.builder(name, Empty.class, Empty.class)
                .description("preflight placeholder " + name)
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

    private static io.haifa.agent.core.run.AgentRunId runToTerminal(
            HaifaRunBridge bridge, String threadId, String text) throws InterruptedException {
        var started = bridge.start(threadId, text, Optional.empty());
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        AgentRunSnapshot snapshot;
        do {
            snapshot = bridge.snapshot(started.runId());
            if (snapshot.status().isTerminal()) break;
            Thread.sleep(50);
        } while (Instant.now().isBefore(deadline));
        assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
        return started.runId();
    }

    private static void report(String label, PromptDiagnostics diagnostics) {
        var sources = diagnostics.components().stream()
                .map(component -> component.source().name())
                .toList();
        System.out.println("W03_PROMPT_DIAGNOSTICS_STATUS=" + label
                + " available=" + diagnostics.available() + " status=" + diagnostics.statusCode());
        System.out.println("W03_PROMPT_DIAGNOSTICS_SOURCES=" + label + " -> " + sources);
        diagnostics.components().forEach(component -> System.out.println(
                "W03_PROMPT_DIAGNOSTICS_COMPONENT=" + label
                        + " source=" + component.source()
                        + " layer=" + component.layer()
                        + " digest=" + component.contentDigest()
                        + " tokens=" + component.estimatedTokens()));
    }

    private static final class CapturingModel implements AgentChatModel {
        private volatile List<ModelToolSpecification> tools = List.of();

        @Override
        public AgentChatResponse invoke(AgentChatRequest request) {
            tools = request.tools();
            return response(request);
        }

        @Override
        public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
            tools = request.tools();
            return response(request);
        }

        private static AgentChatResponse response(AgentChatRequest request) {
            return new AgentChatResponse(
                    "p3-capturing",
                    request.model().providerModelId(),
                    "captured",
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(1, 1),
                    "",
                    Map.of());
        }
    }
}

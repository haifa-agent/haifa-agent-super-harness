package io.haifa.agent.superharness.server.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.superharness.server.api.DeerFlowBootstrapApi;
import io.haifa.agent.superharness.server.api.DeerFlowConversationService;
import io.haifa.agent.superharness.server.api.DeerFlowThreadRunApi;
import io.haifa.agent.superharness.server.run.HaifaRunBridge;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Read-only Lead prompt assertions on the existing product bridge. */
public final class LeadPromptAcceptanceTestSupport {
    public static HaifaRunBridge bridge(DeerFlowBootstrapApi api, String threadId) throws Exception {
        Field threads = DeerFlowConversationService.class.getDeclaredField("threads");
        threads.setAccessible(true);
        Object record = ((Map<?, ?>) threads.get(api.threadRunApi().conversations())).get(threadId);
        assertThat(record).as("real SH ThreadRecord").isNotNull();
        Field field = record.getClass().getDeclaredField("bridge");
        field.setAccessible(true);
        HaifaRunBridge bridge = (HaifaRunBridge) field.get(record);
        assertThat(bridge).as("bridge attached by actual HTTP/recovery path").isNotNull();
        return bridge;
    }
    public static void diagnostics(GatewayFixture.HttpEvidence support, DeerFlowBootstrapApi api, String threadId, String runId) throws Exception {
        HaifaRunBridge bridge = bridge(api, threadId);
        var diagnostics = bridge.promptDiagnostics(new AgentRunId(runId));
        List<Map<String, Object>> components = new ArrayList<>();
        diagnostics.components().forEach(component -> components.add(Map.of(
                "source", String.valueOf(component.source()), "layer", component.layer(),
                "contentDigest", component.contentDigest(), "estimatedTokens", component.estimatedTokens())));
        support.save("prompt-diagnostics.json", Map.of("run_id", runId,
                "promptSignature", bridge.promptSignature(), "available", diagnostics.available(),
                "status", diagnostics.statusCode(), "components", components));
        assertThat(diagnostics.available()).as("real Run prompt diagnostics").isTrue();
        var instructions = diagnostics.components().stream()
                .filter(component -> "AGENT_INSTRUCTIONS".equals(String.valueOf(component.source()))).toList();
        assertThat(instructions).as("AGENT_INSTRUCTIONS component").hasSize(1);
        assertThat(instructions.get(0).contentDigest().replace("sha256:", ""))
                .as("SDK digest matches exact Bridge instructions signature")
                .isEqualTo(bridge.promptSignature());
        assertThat(instructions.get(0).estimatedTokens()).isPositive();
    }
}

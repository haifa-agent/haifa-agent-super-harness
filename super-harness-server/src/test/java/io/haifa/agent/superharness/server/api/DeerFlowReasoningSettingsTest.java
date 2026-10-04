package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.model.api.ModelReasoningMode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeerFlowReasoningSettingsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void explicitFalseAndNullOverrideAgentTrueWhileOmissionInherits() throws Exception {
        var agent = node("{\"thinking_enabled\":true,\"reasoning_effort\":\"low\"}");
        for (String context : new String[]{"{\"thinking_enabled\":false}", "{\"thinking_enabled\":null}"}) {
            assertThat(DeerFlowReasoningSettings.resolve(node(context), agent, true).mode())
                    .isEqualTo(ModelReasoningMode.DISABLED);
        }
        assertThat(DeerFlowReasoningSettings.resolve(node("{}"), agent, true).frozenOptions())
                .containsEntry("thinking", "enabled").containsEntry("reasoning_effort", "low");
        assertThat(DeerFlowReasoningSettings.resolve(node("{}"), node("{\"thinking_enabled\":null}"), true)
                .frozenOptions()).containsEntry("reasoning_effort", "high");
    }

    @Test
    void mapsUiEffortWithoutConflatingMinimalWithDisabledThinking() throws Exception {
        for (var entry : Map.of("minimal", "low", "low", "low", "medium", "high", "high", "high").entrySet()) {
            var context = node("{\"thinking_enabled\":true,\"reasoning_effort\":\"" + entry.getKey() + "\"}");
            assertThat(DeerFlowReasoningSettings.resolve(context, null, true).frozenOptions())
                    .containsEntry("thinking", "enabled").containsEntry("reasoning_effort", entry.getValue());
        }
        var agent = node("{\"thinking_enabled\":true,\"reasoning_effort\":\"low\"}");
        assertThat(DeerFlowReasoningSettings.resolve(node("{\"reasoning_effort\":null}"), agent, true).frozenOptions())
                .containsEntry("reasoning_effort", "high");
    }

    @Test
    void flashAndThinkingPresetsKeepSeparateSdkAttributes() throws Exception {
        assertThat(DeerFlowReasoningSettings.resolve(node("{\"mode\":\"flash\",\"reasoning_effort\":\"minimal\"}"), null, true)
                .frozenOptions()).containsExactlyEntriesOf(Map.of("thinking", "disabled"));
        assertThat(DeerFlowReasoningSettings.resolve(node("{\"mode\":\"thinking\"}"), null, true).frozenOptions())
                .containsEntry("thinking", "enabled").containsEntry("reasoning_effort", "low");
    }

    @Test
    void rejectsInvalidUnsupportedAndUndeliveredExecutionPaths() {
        for (String context : new String[]{"{\"mode\":\"turbo\"}", "{\"mode\":\"pro\"}",
                "{\"subagent_enabled\":\"true\"}", "{\"reasoning_effort\":\"max\"}",
                "{\"thinking_enabled\":\"false\"}", "{\"mode\":\"flash\",\"thinking_enabled\":true}"}) {
            assertThatThrownBy(() -> DeerFlowReasoningSettings.resolve(node(context), null, true))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> DeerFlowReasoningSettings.resolve(node("{\"mode\":\"thinking\"}"), null, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("support thinking");
    }

    @Test
    void delegationKeepsReasoningPolicyAndPlanPrerequisiteIndependent() throws Exception {
        assertThat(DeerFlowReasoningSettings.resolve(
                node("{\"mode\":\"flash\",\"subagent_enabled\":true}"), null, true).frozenOptions())
                .containsExactlyEntriesOf(Map.of("thinking", "disabled"));
        assertThat(DeerFlowReasoningSettings.resolve(
                node("{\"mode\":\"thinking\",\"subagent_enabled\":true}"), null, true).frozenOptions())
                .containsEntry("thinking", "enabled").containsEntry("reasoning_effort", "low");
        assertThatThrownBy(() -> DeerFlowReasoningSettings.resolve(
                node("{\"mode\":\"ultra\",\"subagent_enabled\":true,\"is_plan_mode\":true}"), null, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("W07 plan execution");
    }

    private JsonNode node(String value) throws Exception {
        return json.readTree(value);
    }
}

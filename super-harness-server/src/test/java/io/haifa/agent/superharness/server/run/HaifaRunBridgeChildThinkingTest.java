package io.haifa.agent.superharness.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelStreamSink;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.ChildRunView;
import io.haifa.agent.runtime.api.RunEventPage;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.superharness.server.config.McpRunTools;
import io.haifa.agent.superharness.server.config.SubagentCatalogStore;
import io.haifa.agent.superharness.server.config.SubagentRunConfiguration;
import io.haifa.agent.superharness.server.prompt.AgentPromptContext;
import io.haifa.agent.superharness.server.run.HaifaRunBridge.ModelBinding;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(60)
public class HaifaRunBridgeChildThinkingTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void inheritedReasoningModelDisablesThinkingInChildActualRun(@TempDir Path tempDir) throws Exception {
        String childName = "researcher";
        String objectiveMarker = "investigate-facts-inherited";
        var model = new CapturingDelegationChatModel(childName, objectiveMarker);
        var snapshot = reasoningSnapshot("reasoning-lead");
        var parentBinding = new ModelBinding("reasoning-lead", "1.0.0", model, snapshot);

        ObjectNode definition = createChildDefinition(childName, "inherit");
        SubagentRunConfiguration delegation = projectDelegation(List.of(definition), parentBinding, name -> {
            throw new AssertionError("Inherited child must not query explicit models");
        });

        try (var foundation = new DurableHaifaFoundation(tempDir, new SecretKeySpec(new byte[32], "AES"))) {
            try (var bridge = new HaifaRunBridge(
                    parentBinding,
                    promptTools(),
                    foundation,
                    McpRunTools.Projection.empty(),
                    null,
                    AgentPromptContext.defaults(),
                    delegation)) {

                HaifaRunBridge.StartedRun started = bridge.start(
                        "thread-inherited-" + System.currentTimeMillis(),
                        "Please run delegation",
                        Optional.empty());

                List<AgentRunEvent> recordedEvents = new CopyOnWriteArrayList<>();
                try (var subscription = bridge.subscribe(
                        started.runId(),
                        bridge.beforeFirstEvent(started.runId()),
                        recordedEvents::add)) {

                    AgentRunSnapshot terminal = awaitCompletion(bridge, started.runId());
                    assertThat(terminal.status()).isEqualTo(AgentRunStatus.COMPLETED);

                    RunEventPage page = bridge.events(started.runId(), bridge.beforeFirstEvent(started.runId()), 256);
                    assertThat(page.items()).isNotEmpty();

                    List<ChildRunView> children = bridge.children(started.runId());
                    assertThat(children).hasSize(1);
                    ChildRunView child = children.get(0);
                    assertThat(child.status()).isEqualTo(AgentRunStatus.COMPLETED);
                    assertThat(child.parentRunId()).isEqualTo(started.runId());
                    assertThat(child.agentDefinitionId().value()).isEqualTo(childName);

                    Optional<String> childTaskId = bridge.childTaskId(started.runId(), child.runId());
                    assertThat(childTaskId).isPresent();
                }

                // Verify parent requests before and after Child execution
                assertThat(model.parentRequests).hasSize(2);

                AgentChatRequest parentCall1 = model.parentRequests.get(0);
                assertThat(parentCall1.model().invocationOptions())
                        .containsEntry("thinking", "enabled")
                        .containsEntry("reasoning_effort", "high");
                assertThat(parentCall1.model().maxOutputTokens()).isEqualTo(2048);
                assertThat(parentCall1.tools().stream().map(ModelToolSpecification::name)).contains("task");

                AgentChatRequest parentCall2 = model.parentRequests.get(1);
                assertThat(parentCall2.model().invocationOptions())
                        .containsEntry("thinking", "enabled")
                        .containsEntry("reasoning_effort", "high");
                assertThat(parentCall2.model().maxOutputTokens()).isEqualTo(2048);

                // Verify actual Child request
                assertThat(model.childRequests).hasSize(1);
                AgentChatRequest childCall = model.childRequests.get(0);
                assertThat(childCall.tools()).isEmpty();
                assertThat(childCall.model().modelId()).isEqualTo(parentBinding.snapshot().modelId());
                assertThat(childCall.model().modelVersion()).isEqualTo(parentBinding.snapshot().modelVersion());
                assertThat(childCall.model().credentialRef()).isEqualTo(parentBinding.snapshot().credentialRef());
                assertThat(childCall.model().maxOutputTokens()).isEqualTo(2048);
                assertThat(childCall.model().invocationOptions())
                        .containsEntry("thinking", "disabled")
                        .doesNotContainKeys("reasoning_effort", "reasoning_token_budget");
            }
        }
    }

    @Test
    void explicitReasoningModelDisablesThinkingInChildActualRun(@TempDir Path tempDir) throws Exception {
        String childName = "specialist";
        String objectiveMarker = "investigate-facts-explicit";
        var parentModel = new CapturingDelegationChatModel(childName, objectiveMarker);
        var parentSnapshot = reasoningSnapshot("reasoning-lead");
        var parentBinding = new ModelBinding("reasoning-lead", "1.0.0", parentModel, parentSnapshot);

        var childModel = new CapturingDelegationChatModel(childName, objectiveMarker);
        var childSnapshot = reasoningSnapshot("explicit-child-reasoning");
        var childBinding = new ModelBinding("explicit-child-reasoning", "1.0.0", childModel, childSnapshot);

        ObjectNode definition = createChildDefinition(childName, "explicit-child-reasoning");
        SubagentRunConfiguration delegation = projectDelegation(
                List.of(definition),
                parentBinding,
                name -> "explicit-child-reasoning".equals(name) ? childBinding : null);

        try (var foundation = new DurableHaifaFoundation(tempDir, new SecretKeySpec(new byte[32], "AES"))) {
            try (var bridge = new HaifaRunBridge(
                    parentBinding,
                    promptTools(),
                    foundation,
                    McpRunTools.Projection.empty(),
                    null,
                    AgentPromptContext.defaults(),
                    delegation)) {

                HaifaRunBridge.StartedRun started = bridge.start(
                        "thread-explicit-" + System.currentTimeMillis(),
                        "Please run delegation to specialist",
                        Optional.empty());

                List<AgentRunEvent> recordedEvents = new CopyOnWriteArrayList<>();
                try (var subscription = bridge.subscribe(
                        started.runId(),
                        bridge.beforeFirstEvent(started.runId()),
                        recordedEvents::add)) {

                    AgentRunSnapshot terminal = awaitCompletion(bridge, started.runId());
                    assertThat(terminal.status()).isEqualTo(AgentRunStatus.COMPLETED);

                    List<ChildRunView> children = bridge.children(started.runId());
                    assertThat(children).hasSize(1);
                    ChildRunView child = children.get(0);
                    assertThat(child.status()).isEqualTo(AgentRunStatus.COMPLETED);
                    assertThat(child.parentRunId()).isEqualTo(started.runId());
                }

                assertThat(parentModel.parentRequests).hasSize(2);
                assertThat(childModel.childRequests).hasSize(1);

                AgentChatRequest childCall = childModel.childRequests.get(0);
                assertThat(childCall.tools()).isEmpty();
                assertThat(childCall.model().modelId().value()).isEqualTo("explicit-child-reasoning");
                assertThat(childCall.model().modelVersion()).isEqualTo(childSnapshot.modelVersion());
                assertThat(childCall.model().credentialRef()).isEqualTo(childSnapshot.credentialRef());
                assertThat(childCall.model().maxOutputTokens()).isEqualTo(2048);
                assertThat(childCall.model().invocationOptions())
                        .containsEntry("thinking", "disabled")
                        .doesNotContainKeys("reasoning_effort", "reasoning_token_budget");
            }
        }
    }

    @Test
    void ordinaryNonReasoningModelGainsNoThinkingOptionInChildActualRun(@TempDir Path tempDir) throws Exception {
        String childName = "standard-worker";
        String objectiveMarker = "standard-task";
        var model = new CapturingDelegationChatModel(childName, objectiveMarker);
        var snapshot = standardSnapshot("standard-chat");
        var parentBinding = new ModelBinding("standard-chat", "1.0.0", model, snapshot);

        ObjectNode definition = createChildDefinition(childName, "inherit");
        SubagentRunConfiguration delegation = projectDelegation(List.of(definition), parentBinding, name -> null);

        try (var foundation = new DurableHaifaFoundation(tempDir, new SecretKeySpec(new byte[32], "AES"))) {
            try (var bridge = new HaifaRunBridge(
                    parentBinding,
                    promptTools(),
                    foundation,
                    McpRunTools.Projection.empty(),
                    null,
                    AgentPromptContext.defaults(),
                    delegation)) {

                HaifaRunBridge.StartedRun started = bridge.start(
                        "thread-standard-" + System.currentTimeMillis(),
                        "Please run standard child",
                        Optional.empty());

                AgentRunSnapshot terminal = awaitCompletion(bridge, started.runId());
                assertThat(terminal.status()).isEqualTo(AgentRunStatus.COMPLETED);

                List<ChildRunView> children = bridge.children(started.runId());
                assertThat(children).hasSize(1);
                assertThat(children.get(0).status()).isEqualTo(AgentRunStatus.COMPLETED);

                assertThat(model.childRequests).hasSize(1);
                AgentChatRequest childCall = model.childRequests.get(0);
                assertThat(childCall.tools()).isEmpty();
                assertThat(childCall.model().invocationOptions())
                        .doesNotContainKey("thinking")
                        .doesNotContainKey("reasoning_effort")
                        .doesNotContainKey("reasoning_token_budget");
            }
        }
    }

    @Test
    void childCappedLengthOutputCompletesWithAuthoritativeMetadata(@TempDir Path tempDir) throws Exception {
        String childName = "researcher";
        String objectiveMarker = "summarize-truncated-facts";
        var model = new TruncatingChildChatModel(childName, objectiveMarker);
        var snapshot = standardSnapshot("standard-chat");
        var parentBinding = new ModelBinding("standard-chat", "1.0.0", model, snapshot);

        ObjectNode definition = createChildDefinition(childName, "inherit");
        SubagentRunConfiguration delegation = projectDelegation(List.of(definition), parentBinding, name -> null);

        try (var foundation = new DurableHaifaFoundation(tempDir, new SecretKeySpec(new byte[32], "AES"))) {
            try (var bridge = new HaifaRunBridge(
                    parentBinding,
                    promptTools(),
                    foundation,
                    McpRunTools.Projection.empty(),
                    null,
                    AgentPromptContext.defaults(),
                    delegation)) {

                HaifaRunBridge.StartedRun started = bridge.start(
                        "thread-capped-" + System.currentTimeMillis(),
                        "Please run delegation with capped child output",
                        Optional.empty());

                AgentRunSnapshot terminal = awaitCompletion(bridge, started.runId());
                assertThat(terminal.status()).isEqualTo(AgentRunStatus.COMPLETED);

                List<ChildRunView> children = bridge.children(started.runId());
                assertThat(children).hasSize(1);
                ChildRunView child = children.get(0);
                assertThat(child.status()).isEqualTo(AgentRunStatus.COMPLETED);
                assertThat(child.parentRunId()).isEqualTo(started.runId());
                assertThat(child.usage().outputTokens()).isEqualTo(2_048L);

                AgentRunSnapshot childRun = bridge.findRun(child.runId()).orElseThrow();
                assertThat(childRun.status()).isEqualTo(AgentRunStatus.COMPLETED);
                assertThat(childRun.usage().outputTokens()).isEqualTo(2_048L);
                assertThat(childRun.result()).isPresent();
                assertThat(childRun.result().orElseThrow().warnings())
                        .contains("TRUNCATED:LENGTH");
                assertThat(childRun.output()).isPresent();
                assertThat(childRun.output().orElseThrow()).contains(objectiveMarker);

                assertThat(model.childRequests).hasSize(1);
                AgentChatRequest childCall = model.childRequests.get(0);
                assertThat(childCall.model().maxOutputTokens()).isEqualTo(2_048);

                RunEventPage childPages = eventsFor(bridge, child.runId());
                List<RunEventPayloads.ModelLifecycle> lifecycle = childPages.items().stream()
                        .filter(event -> event.payload() instanceof RunEventPayloads.ModelLifecycle)
                        .map(event -> (RunEventPayloads.ModelLifecycle) event.payload())
                        .toList();
                assertThat(lifecycle)
                        .anySatisfy(item -> {
                            assertThat(item.outputTokens()).isEqualTo(2_048L);
                            assertThat(item.finishReason()).isEqualTo("LENGTH");
                        });

                assertThat(model.parentRequests).hasSize(2);
                assertThat(model.parentRequests.get(1).messages().stream()
                        .filter(message -> message.role() == io.haifa.agent.model.api.ModelMessageRole.TOOL)
                        .toList())
                        .singleElement()
                        .satisfies(message -> assertThat(message.toolResultData())
                                .containsEntry("modelOutputTruncated", true)
                                .containsEntry("modelFinishReason", "LENGTH")
                                .containsEntry("warnings", List.of("TRUNCATED:LENGTH"))
                                .containsEntry("truncated", false));
                assertThat(bridge.snapshot(started.runId()).result()).isPresent();
            }
        }
    }

    @Test
    void parentCappedLengthOutputStillFailsUnderStrictPolicy(@TempDir Path tempDir) throws Exception {
        String childName = "researcher";
        String objectiveMarker = "parent-cover-length";
        var model = new ParentTruncatingChatModel(objectiveMarker);
        var snapshot = standardSnapshot("standard-chat");
        var parentBinding = new ModelBinding("standard-chat", "1.0.0", model, snapshot);

        ObjectNode definition = createChildDefinition(childName, "inherit");
        SubagentRunConfiguration delegation = projectDelegation(List.of(definition), parentBinding, name -> null);

        try (var foundation = new DurableHaifaFoundation(tempDir, new SecretKeySpec(new byte[32], "AES"))) {
            try (var bridge = new HaifaRunBridge(
                    parentBinding,
                    promptTools(),
                    foundation,
                    McpRunTools.Projection.empty(),
                    null,
                    AgentPromptContext.defaults(),
                    delegation)) {

                HaifaRunBridge.StartedRun started = bridge.start(
                        "thread-parent-capped-" + System.currentTimeMillis(),
                        "Please run delegation with capped parent output",
                        Optional.empty());

                AgentRunSnapshot terminal = awaitCompletion(bridge, started.runId());
                assertThat(terminal.status()).isEqualTo(AgentRunStatus.FAILED);
                assertThat(terminal.error()).isPresent();
                assertThat(terminal.error().orElseThrow().code()).isEqualTo(AgentErrorCode.MODEL_OUTPUT_TRUNCATED);
                assertThat(bridge.children(started.runId())).isEmpty();

                assertThat(model.parentRequests).hasSize(1);
                assertThat(model.childRequests).isEmpty();
            }
        }
    }

    @Test
    void projectResolvesInheritedAndExplicitConfigurations() {
        var parentSnapshot = reasoningSnapshot("parent-model");
        var parentModel = new CapturingDelegationChatModel("researcher", "m1");
        var parentBinding = new ModelBinding("parent-model", "1.0.0", parentModel, parentSnapshot);

        var analystSnapshot = standardSnapshot("analyst-model");
        var analystModel = new CapturingDelegationChatModel("analyst", "m2");
        var analystBinding = new ModelBinding("analyst-model", "1.0.0", analystModel, analystSnapshot);

        ObjectNode inheritedDef = createChildDefinition("researcher", "inherit");
        ObjectNode explicitDef = createChildDefinition("analyst", "analyst-model");

        var catalog = new SubagentCatalogStore.RuntimeSnapshot(
                List.of(inheritedDef, explicitDef), "test-catalog-fingerprint");
        var delegation = SubagentRunConfiguration.project(
                catalog,
                Optional.empty(),
                parentBinding,
                name -> "analyst-model".equals(name) ? analystBinding : null,
                SubagentRunConfiguration.Settings.defaults());

        assertThat(delegation.children()).hasSize(2);

        SubagentRunConfiguration.Child child0 = delegation.children().get(0);
        assertThat(child0.name()).isEqualTo("researcher");
        assertThat(child0.model()).isEqualTo(parentBinding);
        assertThat(child0.turns()).isEqualTo(4);
        assertThat(child0.timeoutMillis()).isEqualTo(90_000L);
        assertThat(child0.allowedTools(Set.of("read_file", "task"))).isEmpty();
        assertThat(child0.profileId()).isEqualTo("child/researcher");

        SubagentRunConfiguration.Child child1 = delegation.children().get(1);
        assertThat(child1.name()).isEqualTo("analyst");
        assertThat(child1.model()).isEqualTo(analystBinding);
        assertThat(child1.turns()).isEqualTo(4);
        assertThat(child1.timeoutMillis()).isEqualTo(90_000L);
        assertThat(child1.allowedTools(Set.of("read_file", "task"))).isEmpty();
        assertThat(child1.profileId()).isEqualTo("child/analyst");

        assertThat(delegation.settings().parallel()).isEqualTo(3);
        assertThat(delegation.settings().total()).isEqualTo(6);
        assertThat(delegation.settings().runningCapacity()).isEqualTo(3);
    }

    private static AgentRunSnapshot awaitCompletion(HaifaRunBridge bridge, AgentRunId runId)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        AgentRunSnapshot snapshot;
        do {
            snapshot = bridge.snapshot(runId);
            if (snapshot.status().isTerminal()) {
                return snapshot;
            }
            Thread.sleep(25);
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("Run did not reach terminal within 30s; current snapshot=" + snapshot);
    }

    private static ObjectNode createChildDefinition(String name, String model) {
        ObjectNode def = JSON.createObjectNode()
                .put("name", name)
                .put("description", "Bounded test subagent " + name)
                .put("system_prompt", "You are test subagent " + name)
                .put("model", model)
                .put("max_turns", 4)
                .put("timeout_seconds", 90);
        def.putArray("tools");
        def.putNull("skills");
        return def;
    }

    private static SubagentRunConfiguration projectDelegation(
            List<ObjectNode> definitions,
            ModelBinding parent,
            Function<String, ModelBinding> models) {
        var catalog = new SubagentCatalogStore.RuntimeSnapshot(definitions, "test-catalog-" + System.nanoTime());
        return SubagentRunConfiguration.project(
                catalog,
                Optional.empty(),
                parent,
                models,
                SubagentRunConfiguration.Settings.defaults());
    }

    public static ResolvedModelSnapshot reasoningSnapshot(String modelName) {
        return ResolvedModelSnapshot.create(
                new ModelProviderId(modelName),
                "1.0.0",
                new ModelDefinitionId(modelName),
                "1.0.0",
                modelName,
                modelName + "-adapter",
                "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                URI.create("https://model.invalid"),
                new CredentialRef("env://SUPER_HARNESS_TEST_" + modelName.toUpperCase().replace('-', '_')),
                false,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.REASONING),
                32_768,
                2_048,
                Map.of(),
                Map.of("thinking", "enabled", "reasoning_effort", "high"));
    }

    public static ResolvedModelSnapshot standardSnapshot(String modelName) {
        return ResolvedModelSnapshot.create(
                new ModelProviderId(modelName),
                "1.0.0",
                new ModelDefinitionId(modelName),
                "1.0.0",
                modelName,
                modelName + "-adapter",
                "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                URI.create("https://model.invalid"),
                new CredentialRef("env://SUPER_HARNESS_TEST_" + modelName.toUpperCase().replace('-', '_')),
                false,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                32_768,
                2_048,
                Map.of(),
                Map.of());
    }

    static final class CapturingDelegationChatModel implements AgentChatModel {
        final List<AgentChatRequest> requests = new CopyOnWriteArrayList<>();
        final List<AgentChatRequest> parentRequests = new CopyOnWriteArrayList<>();
        final List<AgentChatRequest> childRequests = new CopyOnWriteArrayList<>();
        final AtomicInteger parentCalls = new AtomicInteger();
        final AtomicInteger childCalls = new AtomicInteger();
        final String targetChildName;
        final String objectiveMarker;

        CapturingDelegationChatModel(String targetChildName, String objectiveMarker) {
            this.targetChildName = targetChildName;
            this.objectiveMarker = objectiveMarker;
        }

        @Override
        public AgentChatResponse invoke(AgentChatRequest request) {
            requests.add(request);
            boolean isParent = request.tools().stream().anyMatch(tool -> "task".equals(tool.name()));
            if (isParent) {
                parentRequests.add(request);
                int call = parentCalls.incrementAndGet();
                if (call == 1) {
                    return new AgentChatResponse(
                            "parent-call-" + call,
                            request.model().providerModelId(),
                            "",
                            List.of(new ModelToolCall(
                                    new ProviderToolCallCorrelationId("parent-task-" + call),
                                    "task",
                                    Map.of("agent", targetChildName, "objective", objectiveMarker))),
                            ModelFinishReason.TOOL_CALLS,
                            ModelUsage.unpriced(1, 1),
                            "",
                            Map.of());
                } else {
                    return new AgentChatResponse(
                            "parent-call-" + call,
                            request.model().providerModelId(),
                            "parent finished after child completion",
                            List.of(),
                            ModelFinishReason.STOP,
                            ModelUsage.unpriced(1, 1),
                            "",
                            Map.of());
                }
            } else {
                childRequests.add(request);
                int call = childCalls.incrementAndGet();
                return new AgentChatResponse(
                        "child-call-" + call,
                        request.model().providerModelId(),
                        "child finished objective: " + objectiveMarker,
                        List.of(),
                        ModelFinishReason.STOP,
                        ModelUsage.unpriced(1, 1),
                        "",
                        Map.of());
            }
        }

        @Override
        public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
            return invoke(request);
        }
    }

    static final class TruncatingChildChatModel implements AgentChatModel {
        final List<AgentChatRequest> parentRequests = new CopyOnWriteArrayList<>();
        final List<AgentChatRequest> childRequests = new CopyOnWriteArrayList<>();
        final AtomicInteger parentCalls = new AtomicInteger();
        final AtomicInteger childCalls = new AtomicInteger();
        final String targetChildName;
        final String objectiveMarker;

        TruncatingChildChatModel(String targetChildName, String objectiveMarker) {
            this.targetChildName = targetChildName;
            this.objectiveMarker = objectiveMarker;
        }

        @Override
        public AgentChatResponse invoke(AgentChatRequest request) {
            boolean isParent = request.tools().stream().anyMatch(tool -> "task".equals(tool.name()));
            if (isParent) {
                parentRequests.add(request);
                int call = parentCalls.incrementAndGet();
                if (call == 1) {
                    return new AgentChatResponse(
                            "parent-call-" + call,
                            request.model().providerModelId(),
                            "",
                            List.of(new ModelToolCall(
                                    new ProviderToolCallCorrelationId("parent-task-" + call),
                                    "task",
                                    Map.of("agent", targetChildName, "objective", objectiveMarker))),
                            ModelFinishReason.TOOL_CALLS,
                            ModelUsage.unpriced(1, 1),
                            "",
                            Map.of());
                }
                return new AgentChatResponse(
                        "parent-call-" + call,
                        request.model().providerModelId(),
                        "parent accepted the capped child result",
                        List.of(),
                        ModelFinishReason.STOP,
                        ModelUsage.unpriced(1, 1),
                        "",
                        Map.of());
            }
            childRequests.add(request);
            childCalls.incrementAndGet();
            return new AgentChatResponse(
                    "child-call-" + childCalls.get(),
                    request.model().providerModelId(),
                    "Partial facts for " + objectiveMarker + " delivered at the output token limit",
                    List.of(),
                    ModelFinishReason.LENGTH,
                    ModelUsage.unpriced(1_300, 2_048),
                    "",
                    Map.of());
        }

        @Override
        public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
            return invoke(request);
        }
    }

    static final class ParentTruncatingChatModel implements AgentChatModel {
        final List<AgentChatRequest> parentRequests = new CopyOnWriteArrayList<>();
        final List<AgentChatRequest> childRequests = new CopyOnWriteArrayList<>();
        final String objectiveMarker;

        ParentTruncatingChatModel(String objectiveMarker) {
            this.objectiveMarker = objectiveMarker;
        }

        @Override
        public AgentChatResponse invoke(AgentChatRequest request) {
            boolean isParent = request.tools().stream().anyMatch(tool -> "task".equals(tool.name()));
            if (isParent) {
                parentRequests.add(request);
                return new AgentChatResponse(
                        "parent-call-1",
                        request.model().providerModelId(),
                        "Parent partial output cut by the token ceiling: " + objectiveMarker,
                        List.of(),
                        ModelFinishReason.LENGTH,
                        ModelUsage.unpriced(1_200, 2_048),
                        "",
                        Map.of());
            }
            childRequests.add(request);
            throw new AssertionError("A failed parent must not reach the child");
        }

        @Override
        public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
            return invoke(request);
        }
    }

    private static RunEventPage eventsFor(HaifaRunBridge bridge, AgentRunId runId) {
        return bridge.events(runId, bridge.beforeFirstEvent(runId), 256);
    }

    private static List<? extends JavaTool<?, ?>> promptTools() {
        return java.util.stream.Stream.of(
                        "read_file",
                        "write_file",
                        "str_replace",
                        "present_files",
                        "list_uploaded_files",
                        "grep",
                        "web_search",
                        "web_fetch",
                        "ask_clarification")
                .map(HaifaRunBridgeChildThinkingTest::namedTool)
                .toList();
    }

    private static JavaTool<Empty, Empty> namedTool(String name) {
        var spec = JavaToolSpec.builder(name, Empty.class, Empty.class)
                .description("Legacy fixture")
                .pure()
                .build();
        return new JavaTool<>() {
            @Override
            public JavaToolSpec<Empty, Empty> spec() {
                return spec;
            }

            @Override
            public Empty invoke(Empty input, JavaToolContext context) {
                throw new AssertionError("Controlled model must not call fixture tools");
            }
        };
    }

    public record Empty() {}
}

package io.haifa.agent.superharness.server.tool.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.model.api.*;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.superharness.server.config.SkillConfigStore;
import io.haifa.agent.superharness.server.config.SkillPackageScreening;
import io.haifa.agent.superharness.server.testsupport.ControlledModel;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class ProductionSkillManageToolTest {
    @TempDir Path data;
    private final ObjectMapper json = new ObjectMapper();

    @Test @Timeout(30) void cancellationDuringModelScreenDoesNotPublishAndReleasesStoreLock() throws Exception {
        var store = new SkillConfigStore(json, data, SkillConfigStore.repositoryRootFromWorkingDirectory());
        store.configureWriteScreening((directory, files) -> json.createObjectNode().put("decision", "allow"));
        String original = "---\nname: sample-helper\ndescription: Controlled package\n---\nOriginal.\n";
        store.manage("create", "sample-helper", original, null, null, null, null, "agent", "thread-one");
        byte[] before = Files.readAllBytes(data.resolve("skills_config.json"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var model = new ControlledModel((request, count) -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Controlled scan timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("Controlled scan interrupted", interrupted);
            }
            return ControlledModel.response(request, "screen", "{\"decision\":\"allow\"}",
                    List.of(), ModelFinishReason.STOP, ModelUsage.unpriced(10, 2));
        });
        // This case exercises cancellation DURING the model screen, so the per-file semantic
        // review must be enabled explicitly now that it is opt-in (DeerFlow parity).
        System.setProperty("haifa.superharness.skill.semantic-review", "on");
        store.configureWriteScreening(new SkillPackageScreening(json, data).writes(
                ignored -> LocalProbeModelTestSupport.binding(model)));
        var cancelled = new AtomicBoolean();
        var context = new JavaToolContext(new AgentRunId("run-skill-cancel"), Optional.of(new AgentSessionId("session-one")),
                new ToolCallId("tool-skill-cancel"), new TenantRef("public"),
                new PrincipalRef("default-public-user", "user"), Instant.now().plusSeconds(20),
                Optional.empty(), cancelled::get, Map.of());
        var tool = new ProductionSkillManageTool(store, "thread-one");
        var input = new ProductionSkillManageTool.Input(ProductionSkillManageTool.Action.edit, "sample-helper",
                Optional.of(original + "Edited.\n"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> tool.invoke(input, context));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);
            release.countDown();
            assertThat(result.get(10, TimeUnit.SECONDS).success()).isFalse();
        } finally { release.countDown(); }
        assertThat(model.requests).hasSize(1);
        assertThat(Files.readAllBytes(data.resolve("skills_config.json"))).containsExactly(before);
        assertThat(store.history("sample-helper")).hasSize(1);
        try (var versions = Files.list(data.resolve("skills/custom/.versions/sample-helper"))) {
            assertThat(versions.filter(Files::isDirectory)).hasSize(1);
        }
        store.configureWriteScreening((directory, files) -> json.createObjectNode().put("decision", "allow"));
        store.manage("edit", "sample-helper", original + "After cancellation.\n", null, null, null, null, "agent", "thread-one");
        assertThat(store.customDetail("sample-helper").get("content").toString()).contains("After cancellation");
    }
}

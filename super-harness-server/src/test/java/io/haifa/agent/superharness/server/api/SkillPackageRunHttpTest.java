package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static io.haifa.agent.superharness.server.testsupport.ControlledModel.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.superharness.server.config.*;
import io.haifa.agent.superharness.server.testsupport.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Real public SDK Run, Approval, Skill activation/resource read and persisted recovery. */
class SkillPackageRunHttpTest {
    private final ObjectMapper json = new ObjectMapper();
    private static String markdown(String text) {
        return "---\nname: package-control\ndescription: A controlled package for Run tests\nlicense: MIT\n---\n" + text + "\n";
    }
    private SkillConfigStore store(Path data) throws Exception {
        var store = new SkillConfigStore(json, data, SkillConfigStore.repositoryRootFromWorkingDirectory());
        store.configureWriteScreening((directory, files) -> json.createObjectNode().put("decision", "allow"));
        return store;
    }
    private GatewayFixture gateway(Path data, SkillConfigStore store, ControlledModel model, Path evidence) throws Exception {
        return new GatewayFixture(data, DeerFlowBootstrapApi.createForSkillTests(
                new LocalServerConfiguration("local", InetAddress.getLoopbackAddress(), 0), data,
                new SecretKeySpec(new byte[32], "AES"), ignored -> LocalProbeModelTestSupport.binding(model), List.of(), store), evidence);
    }

    @Test void largeReviewCrossesSdkCarrierWithoutTruncationOrSkillActivation() throws Exception {
        Path data = GatewayFixture.dataDirectory(), evidence = GatewayFixture.evidenceDirectory();
        var store = store(data);
        String longReference = "A bounded review paragraph.\n".repeat(2600) + "END_OF_REVIEW_RESOURCE";
        store.install(SkillPackageFiles.export("package-control", Map.of("SKILL.md", markdown("Keep this target untrusted").getBytes(StandardCharsets.UTF_8),
                "references/large.txt", longReference.getBytes(StandardCharsets.UTF_8))), null);
        var model = new ControlledModel((request, count) -> count == 1
                ? call(request, "review_skill_package", Map.of("target", "skill://custom/package-control", "include_content", "semantic-review")) : stop(request));
        var probe = LocalProbeModelTestSupport.binding(model);
        var s = probe.snapshot();
        var large = io.haifa.agent.model.api.ResolvedModelSnapshot.create(s.providerId(), s.providerVersion(), s.modelId(), s.modelVersion(),
                s.providerModelId(), s.adapterType(), s.adapterVersion(), s.apiStyle(), s.dialect(), s.endpoint(), s.credentialRef(), s.nativeStreaming(),
                s.capabilities(), 1_048_576, s.maxOutputTokens(), s.providerOptions(), s.invocationOptions());
        try (var fixture = new GatewayFixture(data, DeerFlowBootstrapApi.createForSkillTests(
                new LocalServerConfiguration("local", InetAddress.getLoopbackAddress(), 0), data, new SecretKeySpec(new byte[32], "AES"),
                ignored -> new io.haifa.agent.superharness.server.run.HaifaRunBridge.ModelBinding("super-harness-local-probe", "1.0.0", model, large), List.of(), store), evidence)) {
            fixture.thread("review-large");
            fixture.completed(fixture.run("review-large", null, null));
            var message = fixture.results("review-large", "review_skill_package").getFirst();
            assertThat(message.path("content").asText()).hasSizeGreaterThan(65_536).contains("END_OF_REVIEW_RESOURCE");
            // Large raw results are externalized by the SDK; content completeness is asserted separately.
            assertThat(message.path("additional_kwargs").path("haifa_tool_result_truncated").asBoolean()).isTrue();
            assertThat(message.path("additional_kwargs").path("haifa_tool_result").path("review").size()).isGreaterThan(16);
            assertThat(message.path("artifact").path("evidence_level").asText()).isEqualTo("static");
            assertThat(message.path("artifact").path("target_executed").asBoolean()).isFalse();
            assertThat(message.path("additional_kwargs").path("review_subject_entry").path("display_ref").asText()).isEqualTo("skill://custom/package-control");
            assertThat(fixture.results("review-large", "skill_load")).isEmpty();
            assertThat(model.requests.getLast().messages().toString()).contains("END_OF_REVIEW_RESOURCE");
        }
    }

    @Test void reviewReadsActualThreadDirectoryButCannotReadAnotherExistingThreadsPackage() throws Exception {
        Path data = GatewayFixture.dataDirectory(), evidence = GatewayFixture.evidenceDirectory();
        var store = store(data);
        String logical = "/mnt/user-data/workspace/review-package";
        var model = new ControlledModel((request, count) -> switch (count) {
            case 1 -> call(request, "write_file", Map.of("path", logical + "/SKILL.md", "content", markdown("Thread review only")));
            case 2 -> call(request, "write_file", Map.of("path", logical + "/references/fact.txt", "content", "EXISTING_THREAD_RESOURCE"));
            case 4, 6 -> call(request, "review_skill_package", Map.of("target", logical, "include_content", "semantic-review"));
            default -> stop(request);
        });
        try (var fixture = gateway(data, store, model, evidence)) {
            fixture.thread("review-owner");
            var pending = fixture.run("review-owner", null, null);
            String location = pending.headers().firstValue("content-location").orElseThrow();
            fixture.approve("review-owner", location);
            assertThat(fixture.get(location).path("diagnostics").path("sdk_status").asText()).isEqualTo("WAITING_APPROVAL");
            fixture.approve("review-owner", location);
            assertThat(fixture.get(location).path("status").asText()).isEqualTo("success");
            fixture.completed(fixture.run("review-owner", null, null));
            var own = fixture.results("review-owner", "review_skill_package").getFirst();
            assertThat(own.path("artifact").path("artifacts").toString()).contains("EXISTING_THREAD_RESOURCE");
            assertThat(own.path("artifact").path("target_executed").asBoolean()).isFalse();
            assertThat(store.detail("package-control")).isEmpty();
            fixture.thread("review-other");
            fixture.completed(fixture.run("review-other", null, null));
            var other = fixture.results("review-other", "review_skill_package").getFirst();
            assertThat(other.path("additional_kwargs").path("haifa_tool_result").path("error").asText()).contains("review unavailable");
            assertThat(other.has("artifact")).isFalse();
            assertThat(other.toString()).doesNotContain("EXISTING_THREAD_RESOURCE");
            assertThat(fixture.results("review-owner", "review_skill_package").getFirst().path("artifact").path("artifacts").toString())
                    .contains("EXISTING_THREAD_RESOURCE");
        }
    }

    @Test void managementRequiresApprovalAndSubsequentRunUsesPublishedPackageAndReadOnlyReview() throws Exception {
        Path data = GatewayFixture.dataDirectory(), evidence = GatewayFixture.evidenceDirectory();
        var store = store(data);
        var model = new ControlledModel((request, call) -> switch (call) {
            case 1 -> call(request, "skill_manage", Map.of("action", "create", "name", "package-control", "content", markdown("ORIGINAL_PACKAGE_INSTRUCTIONS")));
            case 3 -> call(request, "skill_load", Map.of("skill", "package-control"));
            case 4 -> call(request, "skill_resource_read", Map.of("skill", "package-control", "path", "references/fact.txt"));
            case 5 -> call(request, "review_skill_package", Map.of("target", "skill://custom/package-control", "include_content", "facts-only"));
            default -> stop(request);
        });
        try (var fixture = gateway(data, store, model, evidence)) {
            fixture.thread("skill-create");
            var pending = fixture.run("skill-create", null, null);
            assertThat(pending.statusCode()).as(pending.body()).isEqualTo(200);
            String location = pending.headers().firstValue("content-location").orElseThrow();
            assertThat(fixture.get(location).path("diagnostics").path("sdk_status").asText()).isEqualTo("WAITING_APPROVAL");
            assertThat(store.detail("package-control")).isEmpty();
            fixture.approve("skill-create", location);
            assertThat(store.customDetail("package-control").get("content")).asString().contains("ORIGINAL_PACKAGE_INSTRUCTIONS");
            assertThat(fixture.results("skill-create", "skill_manage")).hasSize(1);
            store.manage("write_file", "package-control", "REAL_RESOURCE_FACT", "references/fact.txt", null, null, null, "human", null);
            fixture.thread("skill-use");
            fixture.completed(fixture.run("skill-use", null, null));
            assertThat(fixture.results("skill-use", "skill_load")).hasSize(1);
            assertThat(fixture.results("skill-use", "skill_resource_read").toString()).contains("REAL_RESOURCE_FACT");
            assertThat(fixture.results("skill-use", "review_skill_package").toString()).contains("target_executed", "static").doesNotContain("review unavailable");
            assertThat(model.requests.get(3).messages().toString()).contains("ORIGINAL_PACKAGE_INSTRUCTIONS");
            assertThat(model.requests.get(4).messages().toString()).contains("REAL_RESOURCE_FACT");
            assertThat(store.history("package-control")).hasSize(2);
        }
        System.out.println("W15_PACKAGE_RUN_EVIDENCE approval=create-before-publication run=skill-use tools=skill_load,skill_resource_read,review_skill_package evidence=" + evidence);
    }

    @Test void pendingRunAfterEditAndGatewayRestartReadsItsOriginalCompleteVersion() throws Exception {
        Path data = GatewayFixture.dataDirectory(), evidence = GatewayFixture.evidenceDirectory();
        var store = store(data);
        store.install(SkillPackageFiles.export("package-control", Map.of("SKILL.md", markdown("ORIGINAL_PACKAGE_INSTRUCTIONS").getBytes(StandardCharsets.UTF_8))), null);
        var model = new ControlledModel((request, count) -> switch (count) {
            case 1 -> call(request, "skill_load", Map.of("skill", "package-control"));
            case 2 -> call(request, "write_file", Map.of("path", "/mnt/user-data/outputs/original.txt", "content", "approved output"));
            default -> stop(request);
        });
        String location;
        try (var fixture = gateway(data, store, model, evidence)) {
            fixture.thread("skill-persisted");
            var pending = fixture.run("skill-persisted", null, null);
            assertThat(pending.statusCode()).as(pending.body()).isEqualTo(200);
            location = pending.headers().firstValue("content-location").orElseThrow();
            assertThat(fixture.get(location).path("diagnostics").path("sdk_status").asText()).isEqualTo("WAITING_APPROVAL");
            assertThat(fixture.results("skill-persisted", "skill_load")).hasSize(1);
            store.manage("edit", "package-control", markdown("EDITED_PACKAGE_INSTRUCTIONS"), null, null, null, null, "human", null);
            assertThat(store.customDetail("package-control").get("content")).asString().contains("EDITED_PACKAGE_INSTRUCTIONS");
        }
        try (var fixture = gateway(data, store(data), model, evidence.resolve("restart"))) {
            fixture.approve("skill-persisted", location);
            assertThat(fixture.get(location).path("status").asText()).isEqualTo("success");
            assertThat(model.requests.getLast().messages().toString()).contains("ORIGINAL_PACKAGE_INSTRUCTIONS").doesNotContain("EDITED_PACKAGE_INSTRUCTIONS");
            assertThat(fixture.results("skill-persisted", "write_file")).hasSize(1);
        }
        System.out.println("W15_PACKAGE_RECOVERY_EVIDENCE source=frozen-original after=edit+restart evidence=" + evidence);
    }
}

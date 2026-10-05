package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static io.haifa.agent.superharness.server.testsupport.ControlledModel.*;

import com.fasterxml.jackson.databind.JsonNode;
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

    @Test void nextTurnApprovalFollowsItsHumanAfterSkillToolsAndSurvivesRestart() throws Exception {
        Path data = GatewayFixture.dataDirectory(), evidence = GatewayFixture.evidenceDirectory();
        var store = store(data);
        store.install(SkillPackageFiles.export("package-control", Map.of(
                "SKILL.md", markdown("Read the supplied reference before answering").getBytes(StandardCharsets.UTF_8),
                "references/fact.txt", "FACT=17\n".getBytes(StandardCharsets.UTF_8))), null);
        String thread = "skill-next-turn-approval";
        String output = "/mnt/user-data/outputs/next-turn.json";
        var model = new ControlledModel((request, count) -> switch (count) {
            case 1, 4 -> call(request, "skill_load", Map.of("skill", "package-control"));
            case 2, 5 -> call(request, "skill_resource_read", Map.of("skill", "package-control", "path", "references/fact.txt"));
            case 6 -> call(request, "write_file", Map.of("path", output, "content", "{\"observed\":17,\"passed\":true}\n"));
            default -> stop(request);
        });
        String location;
        com.fasterxml.jackson.databind.JsonNode pendingRows;
        try (var fixture = gateway(data, store, model, evidence)) {
            fixture.thread(thread);
            fixture.completed(fixture.run(thread, null, null));
            var pending = fixture.run(thread, null, null);
            location = pending.headers().firstValue("content-location").orElseThrow();
            assertThat(fixture.get(location).path("diagnostics").path("sdk_status").asText()).isEqualTo("WAITING_APPROVAL");
            for (int request : List.of(0, 3)) {
                assertThat(model.requests.get(request).tools()).extracting(tool -> tool.name())
                        .contains("skill_load").doesNotContain("skill_resource_read");
                assertThat(model.requests.get(request + 1).tools()).extracting(tool -> tool.name())
                        .contains("skill_load", "skill_resource_read");
            }
            assertThat(fixture.request("GET", "/api/threads/" + thread + "/artifacts" + output, null).statusCode()).isEqualTo(404);
            pendingRows = fixture.get("/api/threads/" + thread + "/messages/page?limit=20").path("data");
            java.nio.file.Files.writeString(evidence.resolve("pending-message-page.json"), pendingRows.toPrettyString(), StandardCharsets.UTF_8);
            assertThat(pendingRows).hasSize(4);
            assertThat(pendingRows.get(0).path("content").path("type").asText()).isEqualTo("human");
            assertThat(pendingRows.get(1).path("content").path("type").asText()).isEqualTo("ai");
            assertThat(pendingRows.get(2).path("content").path("type").asText()).isEqualTo("human");
            assertThat(pendingRows.get(3).path("content").path("name").asText()).isEqualTo("ask_clarification");
            assertThat(pendingRows.get(3).path("run_id")).isEqualTo(pendingRows.get(2).path("run_id"));
            assertThat(pendingRows.findValuesAsText("seq")).doesNotHaveDuplicates();
        }
        try (var fixture = gateway(data, store(data), model, evidence.resolve("restart"))) {
            assertThat(fixture.get("/api/threads/" + thread + "/messages/page?limit=20").path("data")).isEqualTo(pendingRows);
            fixture.approve(thread, location);
            assertThat(fixture.get(location).path("status").asText()).isEqualTo("success");
            var downloaded = fixture.request("GET", "/api/threads/" + thread + "/artifacts" + output, null);
            assertThat(downloaded.statusCode()).isEqualTo(200);
            assertThat(json.readTree(downloaded.body()).path("observed").asInt()).isEqualTo(17);
            var completedRows = fixture.get("/api/threads/" + thread + "/messages/page?limit=20").path("data");
            assertThat(completedRows).hasSize(6);
            assertThat(completedRows.findValuesAsText("seq")).doesNotHaveDuplicates();
            for (int i = 0; i < pendingRows.size(); i++) {
                assertThat(completedRows.get(i).path("seq")).isEqualTo(pendingRows.get(i).path("seq"));
            }
            assertThat(completedRows.get(4).path("content").path("additional_kwargs").path("human_input_response").path("option_id").asText()).isEqualTo("approve");
            assertThat(completedRows.get(5).path("content").path("type").asText()).isEqualTo("ai");
            var backwards = new java.util.ArrayList<String>();
            Long before = null;
            for (int i = 0; i < completedRows.size(); i++) {
                var page = fixture.get("/api/threads/" + thread + "/messages/page?limit=1" + (before == null ? "" : "&before_seq=" + before));
                assertThat(page.path("data")).hasSize(1);
                var row = page.path("data").get(0);
                backwards.add(row.path("content").path("id").asText());
                before = row.path("seq").asLong();
                assertThat(page.path("has_more").asBoolean()).isEqualTo(i < completedRows.size() - 1);
            }
            java.util.Collections.reverse(backwards);
            assertThat(backwards).containsExactlyElementsOf(java.util.stream.StreamSupport.stream(completedRows.spliterator(), false)
                    .map(row -> row.path("content").path("id").asText()).toList());
        }
        System.out.println("W15_NEXT_TURN_APPROVAL_EVIDENCE thread=" + thread + " same_run=" + location + " restart=true download_observed=17 evidence=" + evidence);
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

    @Test void laterRunReadBeforeActivationIsRejectedThenRepairedAfterLoad() throws Exception {
        Path data = GatewayFixture.dataDirectory(), evidence = GatewayFixture.evidenceDirectory();
        var store = store(data);
        store.install(SkillPackageFiles.export("package-control", Map.of(
                "SKILL.md", markdown("Read the supplied reference before answering").getBytes(StandardCharsets.UTF_8),
                "references/fact.txt", "FACT=17\n".getBytes(StandardCharsets.UTF_8))), null);
        String thread = "skill-read-after-load";
        // Run 1 activates then reads; Run 2 reads before activating, is rejected, then repairs itself.
        var model = new ControlledModel((request, count) -> switch (count) {
            case 1 -> call(request, "skill_load", Map.of("skill", "package-control"));
            case 2 -> call(request, "skill_resource_read", Map.of("skill", "package-control", "path", "references/fact.txt"));
            case 4 -> call(request, "skill_resource_read", Map.of("skill", "package-control", "path", "references/fact.txt"));
            case 5 -> call(request, "skill_load", Map.of("skill", "package-control"));
            case 6 -> call(request, "skill_resource_read", Map.of("skill", "package-control", "path", "references/fact.txt"));
            default -> stop(request);
        });
        try (var fixture = gateway(data, store, model, evidence)) {
            fixture.thread(thread);
            fixture.completed(fixture.run(thread, null, null));
            assertThat(model.requests.get(0).tools()).extracting(tool -> tool.name())
                    .contains("skill_load").doesNotContain("skill_resource_read");
            assertThat(model.requests.get(1).tools()).extracting(tool -> tool.name())
                    .contains("skill_load", "skill_resource_read");

            var second = fixture.run(thread, null, null);
            String location = fixture.completed(second);
            String runId = location.substring(location.lastIndexOf('/') + 1);
            // Disclosure stays activation-driven: the later Run's first request must still hide the reader,
            // and only the request issued after skill_load may expose skill_resource_read.
            assertThat(model.requests.get(3).tools()).extracting(tool -> tool.name())
                    .contains("skill_load").doesNotContain("skill_resource_read");
            assertThat(model.requests.get(4).tools()).extracting(tool -> tool.name())
                    .contains("skill_load").doesNotContain("skill_resource_read");
            assertThat(model.requests.get(5).tools()).extracting(tool -> tool.name())
                    .contains("skill_load", "skill_resource_read");

            var messages = new java.util.ArrayList<JsonNode>();
            for (JsonNode message : fixture.state(thread).path("values").path("messages")) {
                if (runId.equals(message.path("run_id").asText())) messages.add(message);
            }
            var reads = new java.util.ArrayList<JsonNode>();
            var readPositions = new java.util.ArrayList<Integer>();
            int loadPosition = -1;
            boolean loadActivated = false;
            for (int position = 0; position < messages.size(); position++) {
                var message = messages.get(position);
                if (!"tool".equals(message.path("type").asText())) continue;
                if ("skill_resource_read".equals(message.path("name").asText())) {
                    reads.add(message);
                    readPositions.add(position);
                } else if (loadPosition < 0 && "skill_load".equals(message.path("name").asText())) {
                    loadPosition = position;
                    loadActivated = message.path("additional_kwargs").path("haifa_tool_result").path("activated").asBoolean();
                }
            }
            assertThat(loadPosition).isGreaterThanOrEqualTo(0);
            assertThat(loadActivated).as("skill_load result must report activated=true").isTrue();
            assertThat(reads).hasSizeGreaterThanOrEqualTo(2);
            var firstRead = reads.getFirst();
            assertThat(firstRead.path("additional_kwargs").path("haifa_tool_status").asText()).isEqualTo("failed");
            assertThat(firstRead.path("additional_kwargs").path("haifa_tool_result").path("failureCode").asText())
                    .isEqualTo("SKILL_NOT_ACTIVATED");
            int repairedReads = 0;
            for (int index = 0; index < reads.size(); index++) {
                if (readPositions.get(index) <= loadPosition) continue;
                var read = reads.get(index);
                assertThat(read.path("additional_kwargs").path("haifa_tool_status").asText()).isEqualTo("completed");
                assertThat(read.path("additional_kwargs").path("haifa_tool_result").path("content").asText()).contains("FACT=17");
                repairedReads++;
            }
            assertThat(repairedReads).as("at least one read must succeed after activation").isGreaterThanOrEqualTo(1);
        }
        System.out.println("W15_GATED_READER_EVIDENCE later_run_pre_activation_read_rejected_then_repaired=true evidence=" + evidence);
    }
}

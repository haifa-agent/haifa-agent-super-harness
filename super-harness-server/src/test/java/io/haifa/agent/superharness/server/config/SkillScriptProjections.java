package io.haifa.agent.superharness.server.config;

import io.haifa.agent.skill.core.InMemorySkillSource;
import io.haifa.agent.skill.api.SkillAvailability;
import io.haifa.agent.skill.api.SkillOrigin;
import io.haifa.agent.skill.api.SkillParserMode;
import io.haifa.agent.skill.api.SkillScopeRef;
import io.haifa.agent.skill.api.SkillSource;
import io.haifa.agent.skill.api.SkillSourceDescriptor;
import io.haifa.agent.skill.api.SkillSourceRef;
import io.haifa.agent.skill.core.SkillPackageLimits;
import io.haifa.agent.skill.core.SkillPackageParser;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared seeded-Skill projection fixture for the trusted Skill script declaration tests: the same
 * InMemory source + package review grant chain the production store builds when it admits an
 * external scripts Skill. The entry is a real multi-file package: scripts/main.ts imports
 * ../lib/artifact.ts across directories and writes the bounded artifact under outputs/.
 */
public final class SkillScriptProjections {

    private static final byte[] SKILL_MARKDOWN = ("---\n"
            + "name: baoyu-image-gen\n"
            + "description: Acceptance seed Skill for the trusted script Tool\n"
            + "license: MIT\n"
            + "---\n"
            + "# baoyu-image-gen\n").getBytes(StandardCharsets.UTF_8);

    private static final String ENTRY_SCRIPT = """
            import { writeArtifact } from "../lib/artifact.ts";
            const image = process.argv[process.argv.indexOf("--image") + 1] ?? "outputs/probe.png";
            writeArtifact(image, process.argv.slice(2).join("\\n"));
            console.log("W04_WROTE " + image);
            """;

    private static final String LIBRARY_SCRIPT = """
            import { mkdirSync, writeFileSync } from "node:fs";
            export function writeArtifact(name: string, note: string): void {
              mkdirSync("outputs", { recursive: true });
              writeFileSync(name, Buffer.from("W04 fixture artifact\\n" + note + "\\n"));
            }
            """;

    private SkillScriptProjections() {}

    static SkillPackageParser parser() {
        return new SkillPackageParser(new SkillPackageLimits(
                4096, 32, 64 * 1024 * 1024, 512 * 1024 * 1024, 1024 * 1024, 2000, 20000));
    }

    private static SkillSource source(SkillSourceDescriptor descriptor, Map<String, byte[]> files) {
        return new InMemorySkillSource(descriptor, parser(), SkillAvailability.ENABLED, Map.of("baoyu-image-gen", files));
    }

    /** A projection including the scripts entry ({@code withEntry} adds scripts/main.ts). */
    public static SkillRunTools.Projection projection(boolean withEntry) {
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", SKILL_MARKDOWN);
        files.put("EXTEND.md", ("---\ndefault_provider: codex-cli\n---\n").getBytes(StandardCharsets.UTF_8));
        if (withEntry) {
            files.put("scripts/main.ts", ENTRY_SCRIPT.getBytes(StandardCharsets.UTF_8));
            files.put("lib/artifact.ts", LIBRARY_SCRIPT.getBytes(StandardCharsets.UTF_8));
        }
        String digest = SkillPackageFiles.digest(files);
        SkillSourceDescriptor descriptor = new SkillSourceDescriptor(
                new SkillSourceRef("baoyu-image-gen", "sha256:" + digest),
                SkillScopeRef.product(), SkillOrigin.BUNDLED, 100, SkillParserMode.COMPATIBLE, true, false);
        var registration = source(descriptor, files).discover(SkillReviewApprovals.discovery())
                .registrations().getFirst();
        var grant = SkillReviewApprovals.grant(registration, SkillReviewApprovals.evidence(
                new com.fasterxml.jackson.databind.ObjectMapper(), registration,
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                        .objectNode().put("decision", "allow").put("revoked", false)));
        SkillRunTools.Package pkg = new SkillRunTools.Package("baoyu-image-gen", "seed", "public", "MIT",
                "test-seed", "sha256:" + digest, "sha256:" + digest, SkillPackageFiles.hash(SKILL_MARKDOWN),
                true, true, source(descriptor, files));
        return SkillRunTools.projection(List.of(pkg), Map.of("baoyu-image-gen", true), SkillRunTools.Selection.all(),
                SkillReviewApprovals.snapshot(List.of(grant)));
    }
}

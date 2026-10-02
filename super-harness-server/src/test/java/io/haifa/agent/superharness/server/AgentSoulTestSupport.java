package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;
import static io.haifa.agent.superharness.server.GatewayFixture.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.model.api.AgentChatRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Test-only real SDK/HTTP fixtures. Synthetic SOUL text stays in assertion memory. */
final class AgentSoulTestSupport {

    static final Set<String> BASE_TOOLS = Set.of("read_file", "write_file", "str_replace", "ls", "glob", "grep",
            "list_uploaded_files", "present_files", "ask_clarification", "web_search", "web_fetch", "image_search");

    private AgentSoulTestSupport() {}

    static io.haifa.agent.sdk.tool.JavaToolContext context(String principal, boolean cancelled, boolean expired,
            boolean session) {
        return new io.haifa.agent.sdk.tool.JavaToolContext(new io.haifa.agent.core.run.AgentRunId("m3-component"),
                session ? java.util.Optional.of(new io.haifa.agent.core.session.AgentSessionId("m3-session"))
                        : java.util.Optional.empty(),
                new io.haifa.agent.core.tool.ToolCallId("m3-call"), new io.haifa.agent.core.reference.TenantRef("public"),
                new io.haifa.agent.core.reference.PrincipalRef(principal, "user"),
                expired ? java.time.Instant.now().minusSeconds(1) : java.time.Instant.now().plusSeconds(30),
                java.util.Optional.empty(), () -> cancelled, Map.of());
    }

    static String instructions(AgentChatRequest request) {
        var candidates = request.messages().stream()
                .filter(message -> message.role().name().equals("SYSTEM"))
                .map(message -> message.content())
                .filter(text -> text.contains("<role>") && text.contains("open-source super agent"))
                .toList();
        assertThat(candidates.size()).as("one actual Lead instructions layer").isEqualTo(1);
        return candidates.getFirst();
    }

    static Set<String> names(AgentChatRequest request) {
        return request.tools().stream().map(tool -> tool.name()).collect(java.util.stream.Collectors.toSet());
    }

    static String catalogHash(AgentChatRequest request) {
        return hash(request.tools().stream().map(tool -> tool.name() + ":" + tool.inputJsonSchema())
                .sorted().collect(java.util.stream.Collectors.joining("\n")));
    }

    static void soulEquals(String instructions, String soul) {
        String escaped = soul.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        assertThat(instructions.contains("<soul>\n" + escaped + "\n</soul>"))
                .as("actual escaped SOUL matches stored synthetic text; hash=%s chars=%s", hash(soul), soul.length())
                .isTrue();
    }

    static String customSoulAtLimit(String agent) {
        var tools = new java.util.HashSet<>(BASE_TOOLS);
        tools.add("update_agent");
        int fixed = io.haifa.agent.superharness.server.prompt.LeadPromptComposer.classpath()
                .compose(new io.haifa.agent.superharness.server.prompt.LeadPromptInputs(agent, "x", true, List.of()), tools)
                .instructions().trim().length() - 1;
        return "x".repeat(32000 - fixed);
    }

    /** Observes the known product configuration replace path, without inspecting credentials. */
    record ConfigFingerprint(Object key, FileTime modified, long size, String digest) {
        static ConfigFingerprint read(Path data) throws Exception {
            Path file = data.resolve("owners/default/configuration.json").toRealPath();
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            Object identity = attributes.fileKey();
            if (identity == null) identity = windowsFileId(file);
            return new ConfigFingerprint(identity, attributes.lastModifiedTime(), attributes.size(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        }

        private static String windowsFileId(Path file) throws Exception {
            assertThat(System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows"))
                    .as("null JDK fileKey requires the explicitly approved Windows FileID observer").isTrue();
            Process observer = new ProcessBuilder("fsutil.exe", "file", "queryfileid", file.toString())
                    .redirectErrorStream(true).start();
            try {
                assertThat(observer.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
                        .as("bounded fsutil file queryfileid observer").isTrue();
                byte[] output = observer.getInputStream().readNBytes(4097);
                assertThat(output.length).as("bounded FileID observer output").isLessThanOrEqualTo(4096);
                assertThat(observer.exitValue()).as("FileID observer exit code; output hash=%s",
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(output))).isZero();
                var match = java.util.regex.Pattern.compile("(?i)(?<![0-9a-f])0x([0-9a-f]{32})(?![0-9a-f])")
                        .matcher(new String(output, java.nio.charset.Charset.defaultCharset()));
                assertThat(match.find()).as("exact 128-bit FileID must be present").isTrue();
                String id = match.group(1).toLowerCase(java.util.Locale.ROOT);
                assertThat(id).as("FileID must be nonzero").isNotEqualTo("0".repeat(32));
                assertThat(match.find()).as("FileID observer must return exactly one identity").isFalse();
                return "windows-file-id:" + id;
            } finally {
                if (observer.isAlive()) observer.destroyForcibly();
                observer.getInputStream().close();
                observer.getOutputStream().close();
                observer.getErrorStream().close();
            }
        }

        void assertUnchanged(Path data) throws Exception {
            assertThat(read(data)).as("no successful configuration replace: fileKey/mtime/bytes unchanged")
                    .isEqualTo(this);
        }

        ObjectNode safeSummary() {
            return JSON.createObjectNode().put("file_identity", key.toString())
                    .put("last_modified", modified.toString()).put("size_bytes", size).put("bytes_sha256", digest);
        }
    }

    static ObjectNode replacePositiveControl(GatewayFixture fixture, String name) throws Exception {
        ConfigFingerprint before = ConfigFingerprint.read(fixture.data);
        fixture.update(name, JSON.createObjectNode().put("description", fixture.agent(name).path("description").asText()));
        ConfigFingerprint after = ConfigFingerprint.read(fixture.data);
        ObjectNode summary = JSON.createObjectNode().put("evidence_kind", "derived_safe_summary")
                .put("same_value_rest_update", true).put("identity_changed", !after.key().equals(before.key()));
        summary.set("before", before.safeSummary());
        summary.set("after", after.safeSummary());
        Files.writeString(fixture.evidence.resolve("config-positive-" + UUID.randomUUID() + ".safe-summary.json"),
                JSON.writeValueAsString(summary));
        assertThat(after.key()).as("same-value REST update replaces physical configuration file").isNotEqualTo(before.key());
        return summary;
    }

    static void requestEvidence(GatewayFixture fixture, AgentChatRequest request) throws Exception {
        String text = instructions(request);
        Files.writeString(fixture.evidence.resolve("request-" + request.callId().value() + ".summary.json"),
                JSON.writeValueAsString(Map.of("run_id", request.runId().value(), "instructions_chars", text.length(),
                        "instructions_sha256", hash(text.trim()), "tools", names(request),
                        "catalog_sha256", catalogHash(request))));
    }

    static String instructions(GatewayFixture.Managed fixture, int index) {
        for (JsonNode message : fixture.requests.get(index).path("messages")) {
            if ("system".equals(message.path("role").asText())
                    && message.path("content").asText().contains("open-source super agent")) {
                return message.path("content").asText();
            }
        }
        throw new AssertionError("managed Provider request missing Lead instructions");
    }

    static Set<String> names(GatewayFixture.Managed fixture, int index) {
        JsonNode tools = fixture.requests.get(index).path("tools");
        assertThat(tools.isArray()).as("actual OpenAI request tools is an array").isTrue();
        Set<String> names = new java.util.HashSet<>();
        int position = 0;
        for (JsonNode tool : tools) {
            assertThat(tool.isObject()).as("OpenAI Tool %s is an object", position).isTrue();
            assertThat(tool.path("type").isTextual() && tool.path("type").asText().equals("function"))
                    .as("OpenAI Tool %s declares type=function", position).isTrue();
            JsonNode function = tool.path("function");
            assertThat(function.isObject()).as("OpenAI Tool %s has function declaration", position).isTrue();
            JsonNode name = function.path("name");
            assertThat(name.isTextual() && !name.asText().isBlank())
                    .as("OpenAI Tool %s has a nonblank textual function.name", position).isTrue();
            JsonNode schema = function.path("parameters");
            assertThat(schema.isObject() && schema.path("type").asText().equals("object"))
                    .as("OpenAI Tool %s has an object input schema", position).isTrue();
            assertThat(names.add(name.asText())).as("OpenAI Tool %s name is unique", position).isTrue();
            position++;
        }
        return Set.copyOf(names);
    }
}

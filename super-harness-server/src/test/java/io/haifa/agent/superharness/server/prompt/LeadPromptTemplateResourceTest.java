package io.haifa.agent.superharness.server.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** Oracle 1/2/5/8: byte-faithful template, ledger binding, placeholder parsing and fail-closed. */
class LeadPromptTemplateResourceTest {
    private static final String EXPECTED_LOCAL_SHA256 =
            "6d54a9271f76aff4d60d2d05f4e0c614770532962fb5b0a37219a84a7a833e80";

    @Test
    void classpathTemplateMatchesCompanionHashAndLedgerLocalSha256() throws Exception {
        byte[] template = resource(LeadPromptTemplate.TEMPLATE_RESOURCE);
        String actual = sha256(template);
        assertThat(actual).isEqualTo(EXPECTED_LOCAL_SHA256);
        String companion = new String(resource(LeadPromptTemplate.HASH_RESOURCE), StandardCharsets.UTF_8).trim();
        assertThat(companion).isEqualTo(EXPECTED_LOCAL_SHA256);

        JsonNode seed = new ObjectMapper()
                .readTree(repositoryRoot().resolve("contracts/upstream/deerflow/resource-seed.json").toFile());
        JsonNode entry = null;
        for (JsonNode resource : seed.path("resources")) {
            if (resource.path("id").asText().equals("deerflow.prompt.lead-agent-system-template")) {
                entry = resource;
            }
        }
        assertThat(entry).isNotNull();
        assertThat(entry.path("local_sha256").asText()).isEqualTo(EXPECTED_LOCAL_SHA256);
        assertThat(entry.path("sha256").asText())
                .isEqualTo("dd36fc045720055ce0506b787c76ab8a909ed25bf595ede0a5f0d42c9aa83dc1");
        assertThat(entry.path("intended_local_path").asText())
                .isEqualTo("resources/upstream/deerflow/prompts/lead-agent-system-template.txt");
        assertThat(entry.path("import_status").asText()).isEqualTo("IMPORTED");
    }

    @Test
    void everyAdaptationMatchesExactlyOnceAndTemplateParses() {
        LeadPromptTemplate template = LeadPromptTemplate.load();
        assertThat(template.adaptedTemplate()).doesNotContain("{{", "}}");
    }

    @Test
    void pythonFormatEscapesAreRestoredAndUnknownPlaceholdersRejected() {
        LeadPromptTemplate.validatePlaceholders("literal {{brace}} and {agent_name}");
        assertThatThrownBy(() -> LeadPromptTemplate.validatePlaceholders("unknown {not_a_placeholder}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown placeholder");
        assertThatThrownBy(() -> LeadPromptTemplate.validatePlaceholders("dangling {agent_name"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hashMismatchAndMissingAdaptationFailClosed() throws Exception {
        byte[] template = resource(LeadPromptTemplate.TEMPLATE_RESOURCE);
        String adaptations = new String(resource(LeadPromptTemplate.ADAPTATIONS_RESOURCE), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> LeadPromptTemplate.parse(template, adaptations, "deadbeef"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hash mismatch");
        String noMatch = "[{\"id\":\"x\",\"find\":\"THIS DOES NOT EXIST\",\"replace\":\"\","
                + "\"reason\":\"r\",\"unlock_condition\":\"u\",\"owner_wp\":\"W03\"}]";
        assertThatThrownBy(() -> LeadPromptTemplate.parse(template, noMatch, EXPECTED_LOCAL_SHA256))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("matched 0 times");
        String twice = "[{\"id\":\"x\",\"find\":\"the\",\"replace\":\"\","
                + "\"reason\":\"r\",\"unlock_condition\":\"u\",\"owner_wp\":\"W03\"}]";
        assertThatThrownBy(() -> LeadPromptTemplate.parse(template, twice, EXPECTED_LOCAL_SHA256))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("more than once");
    }

    private static byte[] resource(String path) throws Exception {
        try (InputStream stream = LeadPromptTemplate.class.getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return stream.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isRegularFile(current.resolve("contracts/upstream/deerflow/resource-seed.json"))) {
            return current;
        }
        if (current.getFileName() != null
                && current.getFileName().toString().equals("super-harness-server")
                && current.getParent() != null) {
            return current.getParent();
        }
        throw new IllegalStateException("repository root is unavailable from " + current);
    }
}

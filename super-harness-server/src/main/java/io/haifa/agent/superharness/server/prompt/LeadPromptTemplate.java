package io.haifa.agent.superharness.server.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Byte-faithful frozen lead-agent template plus the ordered SH adaptations.
 *
 * <p>The template is stored verbatim under {@code resources/upstream/deerflow/prompts} and copied
 * onto the server classpath by the POM. On load the classpath bytes are checked against the
 * companion {@code *.sha256} file (the ledger {@code local_sha256}); every adaptation {@code find}
 * must match exactly once; and every brace construct must name one of the 15 known placeholders.
 * Any violation fails closed before a Bridge is built.
 */
public final class LeadPromptTemplate {
    static final String TEMPLATE_RESOURCE = "/upstream/deerflow/prompts/lead-agent-system-template.txt";
    static final String HASH_RESOURCE = TEMPLATE_RESOURCE + ".sha256";
    static final String INTERACTIVE_RESOURCE = "/upstream/deerflow/prompts/interactive-clarification-system.txt";
    static final String ADAPTATIONS_RESOURCE = "/upstream/deerflow/prompts/lead-agent-adaptations.json";

    static final Set<String> KNOWN_PLACEHOLDERS = Set.of(
            "agent_name",
            "soul",
            "self_update_section",
            "interaction_thinking_guidance",
            "subagent_thinking",
            "clarification_system",
            "skills_section",
            "memory_tool_section",
            "deferred_tools_section",
            "mcp_routing_hints_section",
            "subagent_section",
            "acp_section",
            "clarification_reminder",
            "subagent_reminder",
            "skill_first_reminder");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String adaptedTemplate;
    private final String localSha256;
    private final String interactiveClarification;

    private LeadPromptTemplate(String adaptedTemplate, String localSha256, String interactiveClarification) {
        this.adaptedTemplate = adaptedTemplate;
        this.localSha256 = localSha256;
        this.interactiveClarification = interactiveClarification;
    }

    public static LeadPromptTemplate load() {
        byte[] template = readResource(TEMPLATE_RESOURCE);
        String declaredHash = new String(readResource(HASH_RESOURCE), StandardCharsets.US_ASCII).trim();
        String adaptationsJson = new String(readResource(ADAPTATIONS_RESOURCE), StandardCharsets.UTF_8);
        LeadPromptTemplate parsed = parse(template, adaptationsJson, declaredHash);
        byte[] interactive = readResource(INTERACTIVE_RESOURCE);
        String expected = new String(readResource(INTERACTIVE_RESOURCE + ".sha256"), StandardCharsets.US_ASCII).trim();
        if (!sha256(interactive).equals(expected)) {
            throw new IllegalStateException("interactive clarification resource hash mismatch at " + INTERACTIVE_RESOURCE);
        }
        return new LeadPromptTemplate(parsed.adaptedTemplate, parsed.localSha256,
                new String(interactive, StandardCharsets.UTF_8));
    }

    /** Single validation path used by tests for positive and negative cases. */
    static LeadPromptTemplate parse(byte[] template, String adaptationsJson, String declaredHash) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(adaptationsJson, "adaptationsJson");
        String actualHash = sha256(template);
        String declared = declaredHash == null ? "" : declaredHash.trim().toLowerCase();
        if (!actualHash.equals(declared)) {
            throw new IllegalStateException(
                    "lead-agent template hash mismatch at " + TEMPLATE_RESOURCE + " (expected " + declared
                            + ", actual " + actualHash + ")");
        }
        String text = new String(template, StandardCharsets.UTF_8);
        for (Adaptation adaptation : adaptations(adaptationsJson)) {
            int first = text.indexOf(adaptation.find());
            if (first < 0) {
                throw new IllegalStateException(
                        "adaptation " + adaptation.id() + " matched 0 times in the lead-agent template");
            }
            if (text.indexOf(adaptation.find(), first + 1) >= 0) {
                throw new IllegalStateException(
                        "adaptation " + adaptation.id() + " matched more than once in the lead-agent template");
            }
            text = text.substring(0, first) + adaptation.replace() + text.substring(first + adaptation.find().length());
        }
        validatePlaceholders(text);
        return new LeadPromptTemplate(text, actualHash, "");
    }

    String adaptedTemplate() {
        return adaptedTemplate;
    }

    String interactiveClarification() {
        return interactiveClarification;
    }

    String localSha256() {
        return localSha256;
    }

    static List<Adaptation> adaptations(String adaptationsJson) {
        JsonNode root;
        try {
            root = JSON.readTree(adaptationsJson);
        } catch (IOException invalid) {
            throw new IllegalStateException("lead-agent adaptations are not valid JSON");
        }
        if (root == null || !root.isArray() || root.isEmpty()) {
            throw new IllegalStateException("lead-agent adaptations must be a non-empty ordered array");
        }
        List<Adaptation> result = new ArrayList<>();
        Set<String> ids = new java.util.LinkedHashSet<>();
        for (JsonNode node : root) {
            String id = required(node, "id");
            if (!ids.add(id)) {
                throw new IllegalStateException("duplicate lead-agent adaptation id: " + id);
            }
            result.add(new Adaptation(
                    id,
                    required(node, "find"),
                    required(node, "replace"),
                    required(node, "reason"),
                    required(node, "unlock_condition"),
                    required(node, "owner_wp")));
        }
        return result;
    }

    private static String required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalStateException("lead-agent adaptation entry requires a textual '" + field + "'");
        }
        return value.textValue();
    }

    static void validatePlaceholders(String text) {
        int index = 0;
        while (index < text.length()) {
            char character = text.charAt(index);
            if (character == '{') {
                if (index + 1 < text.length() && text.charAt(index + 1) == '{') {
                    index += 2;
                    continue;
                }
                int close = text.indexOf('}', index + 1);
                if (close < 0) {
                    throw new IllegalStateException("lead-agent template has an unmatched '{' at index " + index);
                }
                String name = text.substring(index + 1, close);
                if (!KNOWN_PLACEHOLDERS.contains(name)) {
                    throw new IllegalStateException("lead-agent template has an unknown placeholder at index " + index);
                }
                index = close + 1;
            } else if (character == '}') {
                if (index + 1 < text.length() && text.charAt(index + 1) == '}') {
                    index += 2;
                    continue;
                }
                throw new IllegalStateException("lead-agent template has an unmatched '}' at index " + index);
            } else {
                index++;
            }
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] readResource(String path) {
        try (InputStream stream = LeadPromptTemplate.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("lead-agent prompt resource is missing from the classpath: " + path);
            }
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("lead-agent prompt resource could not be read: " + path, failure);
        }
    }

    record Adaptation(String id, String find, String replace, String reason, String unlockCondition, String ownerWp) {}
}

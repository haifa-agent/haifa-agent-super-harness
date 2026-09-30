package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaRecordSchemaGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * W05-task-8: frozen-parity coverage for {@code ask_clarification} request normalization, schema,
 * description ledger and pure tool registration.
 */
class AskClarificationToolTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    // ---- normalization: request shapes -------------------------------------------------------

    @Test
    void freeTextRequestProducesFrozenVersionOnePayload() {
        Map<String, Object> args = map(
                "question", "Tell me more", "clarification_type", "missing_info", "options", null);

        Map<String, Object> payload = ClarificationRequestNormalizer.buildHumanInputPayload(
                args, "call-abc", "clarification:call-abc", null);

        assertThat(payload)
                .isEqualTo(map(
                        "version", 1,
                        "kind", "human_input_request",
                        "source", "ask_clarification",
                        "request_id", "clarification:call-abc",
                        "tool_call_id", "call-abc",
                        "clarification_type", "missing_info",
                        "question", "Tell me more",
                        "input_mode", "free_text"));
        assertThat(ClarificationRequestNormalizer.formatClarificationMessage(args, null))
                .isEqualTo("\u2753 Tell me more");
    }

    @Test
    void choiceRequestProducesFrozenOptionPayload() {
        Map<String, Object> args = map(
                "question", "Which environment should I deploy to?",
                "clarification_type", "approach_choice",
                "context", "Need the target environment for config.",
                "options", List.of("development", "staging", "production"));

        Map<String, Object> payload = ClarificationRequestNormalizer.buildHumanInputPayload(
                args, "call-abc", "clarification:call-abc", null);

        assertThat(payload)
                .isEqualTo(map(
                        "version", 1,
                        "kind", "human_input_request",
                        "source", "ask_clarification",
                        "request_id", "clarification:call-abc",
                        "tool_call_id", "call-abc",
                        "clarification_type", "approach_choice",
                        "question", "Which environment should I deploy to?",
                        "context", "Need the target environment for config.",
                        "input_mode", "choice_with_other",
                        "options", List.of(
                                map("id", "option-1", "label", "development", "value", "development"),
                                map("id", "option-2", "label", "staging", "value", "staging"),
                                map("id", "option-3", "label", "production", "value", "production"))));
    }

    @Test
    void optionsNormalizationMatchesFrozenCoercionTable() {
        // #1995: model serializes the array as a JSON string.
        Map<String, Object> jsonString = map("question", "Which env?", "clarification_type", "approach_choice",
                "options", "[\"dev\", \"staging\", \"prod\"]");
        assertThat(ClarificationRequestNormalizer.normalizeOptions(jsonString.get("options")))
                .containsExactly("dev", "staging", "prod");

        // JSON string decoding to a scalar is one option.
        assertThat(ClarificationRequestNormalizer.normalizeOptions("\"development\""))
                .containsExactly("development");
        assertThat(ClarificationRequestNormalizer.normalizeOptions("just one option"))
                .containsExactly("just one option");
        assertThat(ClarificationRequestNormalizer.normalizeOptions(null)).isEmpty();
        assertThat(ClarificationRequestNormalizer.normalizeOptions(List.of())).isEmpty();

        // Mixed-type JSON array uses Python str() semantics.
        assertThat(ClarificationRequestNormalizer.normalizeOptions("[\"Option A\", 2, true, null]"))
                .containsExactly("Option A", "2", "True", "None");

        // XML-to-dict payloads flatten scalar leaves in source order.
        Map<String, Object> dictOptions = map(
                "item", map(
                        "item", "Move the system section earlier</item>",
                        "$text", "Merge it into the shared patterns section</item>"),
                "$text", "Add a standalone section</item>");
        assertThat(ClarificationRequestNormalizer.normalizeOptions(dictOptions))
                .containsExactly("Move the system section earlier",
                        "Merge it into the shared patterns section", "Add a standalone section");
        assertThat(ClarificationRequestNormalizer.normalizeOptions(
                map("item", List.of(List.of("First</item>"), map("$text", 2)), "$text", null)))
                .containsExactly("First", "2");

        // Top-level options are trimmed and blank-dropped.
        assertThat(ClarificationRequestNormalizer.normalizeOptions(List.of(" dev ", "", "prod", "dev")))
                .containsExactly("dev", "prod");
    }

    @Test
    void validFieldsProduceFrozenVersionTwoFormPayload() {
        Map<String, Object> args = map(
                "question", "Please provide the expense details.",
                "clarification_type", "missing_info",
                "fields", List.of(
                        map("name", "amount", "label", "Amount", "type", "number", "required", true),
                        map("name", "category", "label", "Category", "type", "select",
                                "options", List.of("travel", "meals"), "required", true),
                        map("name", "receipts", "label", "Receipts", "type", "multi_select",
                                "options", List.of("A-1", "A-2")),
                        map("name", "note", "type", "textarea")));

        Map<String, Object> payload = ClarificationRequestNormalizer.buildHumanInputPayload(
                args, "call-abc", "clarification:call-abc", null);

        assertThat(payload.get("version")).isEqualTo(2);
        assertThat(payload.get("input_mode")).isEqualTo("form");
        assertThat(payload.get("fields"))
                .isEqualTo(List.of(
                        map("name", "amount", "label", "Amount", "type", "number", "required", true),
                        map("name", "category", "label", "Category", "type", "select", "required", true,
                                "options", List.of(
                                        map("id", "category-option-1", "label", "travel", "value", "travel"),
                                        map("id", "category-option-2", "label", "meals", "value", "meals"))),
                        map("name", "receipts", "label", "Receipts", "type", "multi_select", "required", false,
                                "options", List.of(
                                        map("id", "receipts-option-1", "label", "A-1", "value", "A-1"),
                                        map("id", "receipts-option-2", "label", "A-2", "value", "A-2"))),
                        map("name", "note", "label", "note", "type", "textarea", "required", false)));
    }

    // ---- normalization: atomic degradation ---------------------------------------------------

    @Test
    void structurallyInvalidFieldsDegradeTheWholeForm() {
        // Over the 16-field cap.
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int index = 0; index < 17; index++) {
            tooMany.add(map("name", "field_" + index, "type", "text"));
        }
        assertDegradesToFreeText(tooMany);

        // A structurally broken entry (non-object / missing / blank name) invalidates every field.
        for (Object bad : List.of("not-a-dict", map("label", "no name"), map("name", "   "))) {
            List<Object> fields = List.of(bad, map("name", "amount", "type", "number"));
            Map<String, Object> payload = ClarificationRequestNormalizer.buildHumanInputPayload(
                    map("question", "Details please", "clarification_type", "missing_info",
                            "fields", fields, "options", List.of("dev", "prod")),
                    "call-abc", "clarification:call-abc", null);
            assertThat(payload.get("version")).as(String.valueOf(bad)).isEqualTo(1);
            assertThat(payload.get("input_mode")).as(String.valueOf(bad)).isEqualTo("choice_with_other");
            assertThat(payload).as(String.valueOf(bad)).doesNotContainKey("fields");
        }

        // Duplicate names.
        assertDegradesToFreeText(List.of(
                map("name", "amount", "type", "number"),
                map("name", "amount", "type", "text")));

        // Names that collide with JS Object.prototype members.
        for (String reserved : List.of("__proto__", "constructor", "toString", "hasOwnProperty")) {
            assertDegradesToFreeText(List.of(map("name", reserved, "type", "text")));
        }

        // Over-cap lengths / counts and the serialized byte budget.
        assertDegradesToFreeText(List.of(map("name", "amount", "label", "x".repeat(201), "type", "number")));
        List<Object> tooManyOptions = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            tooManyOptions.add("option-" + index);
        }
        assertDegradesToFreeText(List.of(map("name", "category", "type", "select", "options", tooManyOptions)));
        assertDegradesToFreeText(List.of(map("name", "note", "placeholder", "x".repeat(201))));
        assertDegradesToFreeText(oversizedSerializedForm());
    }

    @Test
    void benignFieldIssuesDegradeLocallyToText() {
        // Unknown type degrades to text.
        Map<String, Object> payload = ClarificationRequestNormalizer.buildHumanInputPayload(
                map("question", "Details please", "clarification_type", "missing_info",
                        "fields", List.of(map("name", "amount", "type", "slider"))),
                "call-abc", "clarification:call-abc", null);
        assertThat((Map<String, Object>) ((List<?>) payload.get("fields")).get(0))
                .containsEntry("type", "text");

        // Select without options degrades to text and omits the options key.
        payload = ClarificationRequestNormalizer.buildHumanInputPayload(
                map("question", "Details please", "clarification_type", "missing_info",
                        "fields", List.of(map("name", "category", "type", "select"))),
                "call-abc", "clarification:call-abc", null);
        Map<String, Object> degraded = (Map<String, Object>) ((List<?>) payload.get("fields")).get(0);
        assertThat(degraded).containsEntry("type", "text").doesNotContainKey("options");

        // type: [] / type: {} are legal JSON from a model and must not crash the membership probe.
        for (Object badType : List.of(List.of(), Map.of(), List.of("select"), map("t", "select"))) {
            Map<String, Object> unhashable = ClarificationRequestNormalizer.buildHumanInputPayload(
                    map("question", "Details please", "clarification_type", "missing_info",
                            "fields", List.of(map("name", "amount", "type", badType))),
                    "call-abc", "clarification:call-abc", null);
            assertThat((Map<String, Object>) ((List<?>) unhashable.get("fields")).get(0))
                    .as(String.valueOf(badType)).containsEntry("type", "text");
        }

        // required accepts integer serialization.
        payload = ClarificationRequestNormalizer.buildHumanInputPayload(
                map("question", "Details please", "clarification_type", "missing_info",
                        "fields", List.of(
                                map("name", "amount", "type", "number", "required", 1),
                                map("name", "note", "type", "text", "required", 0))),
                "call-abc", "clarification:call-abc", null);
        List<?> fields = (List<?>) payload.get("fields");
        assertThat(((Map<?, ?>) fields.get(0)).get("required")).isEqualTo(true);
        assertThat(((Map<?, ?>) fields.get(1)).get("required")).isEqualTo(false);
    }

    @Test
    void formFallbackTextListsFieldsLikeFrozen() {
        Map<String, Object> args = map(
                "question", "Please provide the expense details.",
                "clarification_type", "missing_info",
                "fields", List.of(
                        map("name", "amount", "label", "Amount", "type", "number", "required", true),
                        map("name", "category", "label", "Category", "type", "select",
                                "options", List.of("travel", "meals"), "required", true),
                        map("name", "receipts", "label", "Receipts", "type", "multi_select",
                                "options", List.of("A-1", "A-2")),
                        map("name", "note", "type", "textarea")));

        String fallback = ClarificationRequestNormalizer.formatClarificationMessage(args, null);

        assertThat(fallback)
                .contains("1. Amount (required)")
                .contains("2. Category (required) \u2014 options: travel / meals")
                .contains("3. Receipts \u2014 options: A-1 / A-2 (multiple allowed)")
                .contains("4. note")
                .contains("Please reply with a value for each field.");
    }

    @Test
    void contextIsRenderedBeforeTheQuestion() {
        Map<String, Object> args = map(
                "question", "Which env?", "clarification_type", "approach_choice",
                "context", "Need target env for config", "options", List.of("dev", "prod"));
        String fallback = ClarificationRequestNormalizer.formatClarificationMessage(args, null);
        assertThat(fallback).contains("Need target env for config").contains("Which env?");
        assertThat(fallback.indexOf("Need target env for config")).isLessThan(fallback.indexOf("Which env?"));
    }

    @Test
    void stableRequestIdIsDerivedFromTheToolCallId() {
        assertThat(ClarificationRequestNormalizer.stableRequestId("call-clarify-1", "ignored"))
                .isEqualTo("clarification:call-clarify-1");
        assertThat(ClarificationRequestNormalizer.stableRequestId("", "hello"))
                .isEqualTo(ClarificationRequestNormalizer.stableRequestId("", "hello"))
                .startsWith("clarification:");
    }

    // ---- schema and description ledger -------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void schemaMatchesTheFrozenParameterContract() {
        Map<String, Object> document =
                new JavaRecordSchemaGenerator().generate("ask_clarification", "1.0.0", ProductionAskClarificationTool.Input.class)
                        .document();
        Map<String, Object> properties = (Map<String, Object>) document.get("properties");
        assertThat(properties.keySet()).containsExactlyInAnyOrder(
                "question", "clarification_type", "context", "options", "fields");
        assertThat((List<String>) document.get("required")).containsExactlyInAnyOrder("question", "clarification_type");

        Map<String, Object> clarificationType = (Map<String, Object>) properties.get("clarification_type");
        assertThat((List<String>) clarificationType.get("enum")).containsExactly(
                "missing_info", "ambiguous_requirement", "approach_choice", "risk_confirmation", "suggestion");

        Map<String, Object> fields = (Map<String, Object>) properties.get("fields");
        Map<String, Object> items = (Map<String, Object>) fields.get("items");
        assertThat((List<String>) items.get("required")).containsExactly("name");
        Map<String, Object> itemProperties = (Map<String, Object>) items.get("properties");
        assertThat(itemProperties.keySet()).containsExactlyInAnyOrder(
                "name", "label", "type", "required", "options", "placeholder");
        assertThat((List<String>) ((Map<String, Object>) itemProperties.get("type")).get("enum")).containsExactly(
                "text", "textarea", "number", "select", "multi_select", "checkbox", "date");
    }

    @Test
    void descriptionMatchesTheImportedResourceAndTheLedger() throws Exception {
        String resource;
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("upstream/deerflow/tool-descriptions/ask_clarification.txt")) {
            assertThat(stream).isNotNull();
            resource = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(new ProductionAskClarificationTool().spec().description()).isEqualTo(resource);

        JsonNode manifest = JSON.readTree(getClass().getClassLoader()
                .getResourceAsStream("upstream/deerflow/tool-descriptions/manifest.json"));
        JsonNode entry = null;
        for (JsonNode candidate : manifest.path("descriptions")) {
            if ("ask_clarification".equals(candidate.path("tool").asText())) {
                entry = candidate;
            }
        }
        assertThat(entry).isNotNull();
        assertThat(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(resource.getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(entry.path("description_sha256").asText());
    }

    @Test
    void toolIsPureAndRegistersUnderTheFrozenName() {
        var spec = new ProductionAskClarificationTool().spec();
        assertThat(spec.name().value()).isEqualTo("ask_clarification");
        assertThat(spec.pure()).isTrue();
        assertThat(spec.sideEffects()).isEmpty();
    }

    @Test
    void invokeReturnsTheFrozenFallbackAndTheStructuredPayload() throws Exception {
        ProductionAskClarificationTool tool = new ProductionAskClarificationTool();
        var result = tool.invoke(
                new ProductionAskClarificationTool.Input(
                        "Which environment should I use?",
                        ProductionAskClarificationTool.ClarificationType.approach_choice,
                        Optional.empty(),
                        Optional.of(List.of("dev", "prod")),
                        Optional.empty()),
                context());
        assertThat(result.error()).isEmpty();
        assertThat(tool.summarize(result)).contains("Which environment should I use?", "1. dev", "2. prod");
        JsonNode payload = JSON.readTree(result.humanInput());
        assertThat(payload.path("source").asText()).isEqualTo("ask_clarification");
        assertThat(payload.path("input_mode").asText()).isEqualTo("choice_with_other");
        assertThat(payload.path("request_id").asText()).isEqualTo("clarification:tool-call-1");
        assertThat(payload.path("tool_call_id").asText()).isEqualTo("tool-call-1");
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static void assertDegradesToFreeText(List<?> fields) {
        Map<String, Object> payload = ClarificationRequestNormalizer.buildHumanInputPayload(
                map("question", "Details please", "clarification_type", "missing_info", "fields", fields),
                "call-abc", "clarification:call-abc", null);
        assertThat(payload.get("input_mode")).as(String.valueOf(fields)).isEqualTo("free_text");
        assertThat(payload).as(String.valueOf(fields)).doesNotContainKey("fields");
    }

    private static List<Map<String, Object>> oversizedSerializedForm() {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            List<String> options = new ArrayList<>();
            for (int option = 0; option < 20; option++) {
                options.add("\u9009".repeat(190) + option);
            }
            fields.add(map("name", "field_" + index, "label", "\u6807".repeat(190),
                    "type", "select", "options", options));
        }
        return fields;
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], pairs[index + 1]);
        }
        return result;
    }

    private static JavaToolContext context() {
        return new JavaToolContext(new AgentRunId("run-1"), Optional.of(new AgentSessionId("session-1")),
                new ToolCallId("tool-call-1"), new TenantRef("public"),
                new PrincipalRef("default-public-user", "user"),
                Instant.now().plusSeconds(60), Optional.empty(), () -> false, Map.of());
    }
}

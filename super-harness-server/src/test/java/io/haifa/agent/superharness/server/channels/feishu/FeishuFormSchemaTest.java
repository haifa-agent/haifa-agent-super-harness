package io.haifa.agent.superharness.server.channels.feishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Independent contract for the emitted Card 2.0 subset, checked against Feishu's component docs:
 * https://open.feishu.cn/document/feishu-cards/card-json-v2-components/interactive-components/
 * single-select-dropdown-menu, multi-select-dropdown-menu, input, checker, date-picker.
 * Unknown properties are rejected rather than inferred from the production renderer.
 */
class FeishuFormSchemaTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void productionFailureCombinationUsesOnlyDocumentedComponentProperties() {
        FeishuPendingInput pending = form(List.of(
                field("auth_mode", "认证方式", "select"),
                field("scope", "操作范围", "multi_select"),
                field("more_detail", "补充说明", "textarea")));
        JsonNode card = json.valueToTree(FeishuInteraction.interactiveCard(pending));
        assertThat(card.path("schema").asText()).isEqualTo("2.0");
        for (JsonNode element : card.path("body").path("elements")) {
            assertComponent(element);
        }
        assertThat(card.toString()).contains("认证方式", "操作范围", "补充说明");
        String submitted = FeishuInteraction.buildFormSubmissionValue(
                pending, Map.of("auth_mode", "app", "scope", List.of("app"), "more_detail", "line1\nline2"));
        assertThat(submitted).contains("\"scope\":[\"app\"]", "line1\\nline2");
    }

    @Test
    void allSupportedFieldsUseTheDocumentedInputTypeEnum() {
        JsonNode card = json.valueToTree(FeishuInteraction.interactiveCard(form(List.of(
                field("text", "文本", "text"),
                field("notes", "多行", "textarea"),
                field("count", "数量", "number"),
                field("one", "单选", "select"),
                field("many", "多选", "multi_select"),
                field("agree", "确认", "checkbox"),
                field("day", "日期", "date")))));
        for (JsonNode element : card.path("body").path("elements")) {
            assertComponent(element);
        }
        assertThat(card.toString()).contains("数量", "日期");
    }

    @Test
    void numberTextInputsRejectInvalidValuesAndPreserveWebSubmissionStrings() {
        FeishuPendingInput pending = form(List.of(field("count", "数量", "number")));
        for (String invalid : List.of("abc", "NaN", "Infinity", "1e999", "0x10", "1,000")) {
            assertThat(FeishuInteraction.formValidationError(pending, Map.of("count", invalid)))
                    .as("reject numeric value %s", invalid).isEqualTo(FeishuInteraction.INVALID_NUMBER);
        }
        for (String valid : List.of("0", "-12.5", ".25", "1e3")) {
            assertThat(FeishuInteraction.formValidationError(pending, Map.of("count", valid))).isEmpty();
        }
        assertThat(FeishuInteraction.formValidationError(pending, Map.of())).isEqualTo(FeishuInteraction.EMPTY_FORM);
        assertThat(FeishuInteraction.buildFormSubmissionValue(pending, Map.of("count", "12.5")))
                .contains("\"count\":\"12.5\"");
    }

    @Test
    void contractRejectsHistoricalUndocumentedPropertiesAndInputType() {
        assertThatThrownBy(() -> assertComponent(json.valueToTree(Map.of(
                        "tag", "select_static", "name", "one", "label", Map.of("tag", "plain_text", "content", "x")))))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertComponent(json.valueToTree(Map.of(
                        "tag", "input", "name", "count", "input_type", "number"))))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void failedFormGivesOneConsistentWebInstruction() {
        FeishuPendingInput pending = form(List.of(field("notes", "说明", "textarea")));
        String fallback = FeishuInteraction.cardDeliveryFallback(pending, "thread-form");
        assertThat(fallback)
                .contains("表单卡片发送失败", "请在 Web 端完成该表单", "/workspace/chats/thread-form", "请补充")
                .doesNotContain("请直接以文字回复", "Please reply with a value for each field.");
        assertThat(FeishuInteraction.interactiveCard(pending).toString())
                .doesNotContain("Please reply with a value for each field.");
    }

    private static void assertComponent(JsonNode element) {
        String tag = element.path("tag").asText();
        Set<String> allowed = switch (tag) {
            case "form" -> Set.of("tag", "name", "elements");
            case "markdown" -> Set.of("tag", "content");
            case "div" -> Set.of("tag", "text");
            case "input" -> Set.of("tag", "name", "required", "width", "input_type", "rows", "auto_resize",
                    "label", "label_position", "placeholder");
            case "select_static", "multi_select_static" -> Set.of(
                    "tag", "name", "required", "placeholder", "options", "width");
            case "date_picker" -> Set.of("tag", "name", "required", "width", "placeholder");
            case "checker" -> Set.of("tag", "name", "checked", "text");
            case "button" -> Set.of("tag", "name", "text", "type", "width", "form_action_type", "behaviors");
            default -> throw new AssertionError("Unexpected component: " + tag);
        };
        Set<String> actual = new HashSet<>();
        element.fieldNames().forEachRemaining(actual::add);
        assertThat(actual).as("documented %s properties", tag).isSubsetOf(allowed);
        if ("input".equals(tag)) {
            assertThat(element.path("input_type").asText("text")).isIn("text", "multiline_text", "password");
        }
        if ("form".equals(tag)) {
            for (JsonNode child : element.path("elements")) {
                assertComponent(child);
            }
        }
    }

    private static FeishuPendingInput.Field field(String name, String label, String type) {
        return new FeishuPendingInput.Field(name, label, type, true, "", List.of(
                new FeishuPendingInput.Option("app", "应用", "app")));
    }

    private static FeishuPendingInput form(List<FeishuPendingInput.Field> fields) {
        return new FeishuPendingInput("ask_clarification", "req-form", "missing_info", "form", "请补充", "",
                "请补充\n\nPlease reply with a value for each field.", List.of(), false, fields, "", "", List.of(), List.of());
    }
}

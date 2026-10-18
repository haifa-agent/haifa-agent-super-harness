package io.haifa.agent.superharness.server.channels.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit and snapshot tests for {@link FeishuCardPresenter}.
 *
 * <p>Verifies fix for https://github.com/haifa-agent/haifa-agent-super-harness/issues/85:
 * <ul>
 *   <li>The reproduction fixture renders structured Card 2.0 tables and distinct headings.
 *   <li>Raw {@code |---|---|...} delimiter rows are never exposed.
 *   <li>Fenced code blocks containing markdown, tables, and HTML remain strictly literal.
 *   <li>UTF-8 Chinese text, emojis, checksums, and special symbols are lossless.
 *   <li>Size-aware segmentation prevents card payload overflow on large tables.
 * </ul>
 */
class FeishuCardPresenterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String REPRODUCTION_FIXTURE = """
            ## 验证结果

            | 检查项 | 写入 | 读取 | 结论 |
            |---|---|---|---|
            | SHA-256 | `de96bb2c...632296` | `de96bb2c...632296` | ✅ 完全一致 |
            | 字节数 | 548 B | 548 B | ✅ 一致 |
            | 行数 | — | 19 行 | ✅ 完整 |

            ## 补充说明

            **哈希值完全一致**，说明文件内容未损坏。

            ```html
            <div>Literal HTML code example; must not be rendered as HTML.</div>
            ```
            """;

    @Test
    void reproductionFixtureRendersWithoutRawTableDelimiters() throws Exception {
        Map<String, Object> card = FeishuCardPresenter.cardPayload(REPRODUCTION_FIXTURE);

        assertThat(card.get("schema")).isEqualTo("2.0");
        assertThat(card.get("config")).isEqualTo(Map.of("wide_screen_mode", true, "update_multi", true));
        assertThat(card).doesNotContainKey("elements");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) card.get("body");
        assertThat(body).isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> elements = (List<Map<String, Object>>) body.get("elements");
        assertThat(elements).hasSize(3);

        // 1. First block: Heading "## 验证结果"
        Map<String, Object> el0 = elements.get(0);
        assertThat(el0.get("tag")).isEqualTo("markdown");
        assertThat(el0.get("content")).isEqualTo("## 验证结果");

        // 2. Second block: Native Card 2.0 table
        Map<String, Object> el1 = elements.get(1);
        assertThat(el1.get("tag")).isEqualTo("table");
        assertThat(el1.get("row_height")).isEqualTo("low");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) el1.get("columns");
        assertThat(columns).hasSize(4);
        assertThat(columns).extracting(c -> c.get("display_name"))
                .containsExactly("检查项", "写入", "读取", "结论");
        assertThat(columns).allMatch(c -> "lark_md".equals(c.get("data_type")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) el1.get("rows");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsEntry("c0", "SHA-256")
                .containsEntry("c1", "`de96bb2c...632296`")
                .containsEntry("c2", "`de96bb2c...632296`")
                .containsEntry("c3", "✅ 完全一致");
        assertThat(rows.get(1)).containsEntry("c0", "字节数")
                .containsEntry("c1", "548 B")
                .containsEntry("c2", "548 B")
                .containsEntry("c3", "✅ 一致");
        assertThat(rows.get(2)).containsEntry("c0", "行数")
                .containsEntry("c1", "—")
                .containsEntry("c2", "19 行")
                .containsEntry("c3", "✅ 完整");

        // 3. Third block: Heading, bold text, and literal HTML code block
        Map<String, Object> el2 = elements.get(2);
        assertThat(el2.get("tag")).isEqualTo("markdown");
        String el2Content = (String) el2.get("content");
        assertThat(el2Content).contains("## 补充说明");
        assertThat(el2Content).contains("**哈希值完全一致**，说明文件内容未损坏。");
        assertThat(el2Content).contains("```html\n<div>Literal HTML code example; must not be rendered as HTML.</div>\n```");

        // 4. Verify serialized JSON never contains raw table delimiters
        String json = JSON.writeValueAsString(card);
        assertThat(json).doesNotContain("|---|---|---|---|");
        assertThat(json).doesNotContain("|---|");
    }

    @Test
    void fencedCodeBlocksWithMarkdownTablesAndHeadingsRemainLiteral() {
        String input = """
                Here is how you format a markdown table:

                ```markdown
                ## This is a code heading, not a real heading

                | Col A | Col B |
                |---|---|
                | 123 | 456 |
                ```

                Normal text follows.
                """;

        List<Map<String, Object>> elements = FeishuCardPresenter.presentElements(input);
        // All content must be retained in a single markdown element since the table is inside code fence
        assertThat(elements).hasSize(1);
        assertThat(elements.getFirst().get("tag")).isEqualTo("markdown");

        String content = (String) elements.getFirst().get("content");
        assertThat(content).contains("```markdown\n## This is a code heading, not a real heading\n\n| Col A | Col B |\n|---|---|\n| 123 | 456 |\n```");
        assertThat(content).contains("Normal text follows.");
    }

    @Test
    void losslessMixedChineseEnglishEmojisAndChecksums() {
        String input = """
                # 状态报告 (Status Report) 🚀

                | 指标 | 详情 | 状态 |
                | :--- | :---: | ---: |
                | Commit SHA | `7f2f345...9a01` | ✅ PASS |
                | Special chars | `# @ % & * ( ) [ ] { }` | ⚠️ WARN |
                | 空值检查 | N/A | ❌ FAIL |

                完成时间：2026-10-18 09:30:00 (UTC+8)
                """;

        List<Map<String, Object>> elements = FeishuCardPresenter.presentElements(input);
        assertThat(elements).hasSize(3);

        assertThat(elements.get(0).get("content")).isEqualTo("# 状态报告 (Status Report) 🚀");

        @SuppressWarnings("unchecked")
        Map<String, Object> table = elements.get(1);
        assertThat(table.get("tag")).isEqualTo("table");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) table.get("columns");
        assertThat(cols).hasSize(3);
        assertThat(cols.get(0).get("horizontal_align")).isEqualTo("left");
        assertThat(cols.get(1).get("horizontal_align")).isEqualTo("center");
        assertThat(cols.get(2).get("horizontal_align")).isEqualTo("right");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) table.get("rows");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsEntry("c1", "`7f2f345...9a01`");
        assertThat(rows.get(1)).containsEntry("c1", "`# @ % & * ( ) [ ] { }`");

        assertThat(elements.get(2).get("content")).isEqualTo("完成时间：2026-10-18 09:30:00 (UTC+8)");
    }

    @Test
    void mobileFriendlyListFallbackWhenNativeTableUnavailable() {
        String tableOnly = """
                | 配置项 | 设定值 |
                |---|---|
                | port | 8080 |
                | host | localhost |
                """;

        String mobileText = FeishuCardPresenter.presentMarkdown(tableOnly);
        assertThat(mobileText).doesNotContain("|---|---|");
        assertThat(mobileText).doesNotContain("|");
        assertThat(mobileText).contains("• **port**: 8080");
        assertThat(mobileText).contains("• **host**: localhost");
    }

    @Test
    void largeTableSizeAwareSegmentation() {
        StringBuilder sb = new StringBuilder();
        sb.append("| ID | 名称 | 数值 |\n|---|---|---|\n");
        for (int i = 1; i <= 120; i++) {
            sb.append("| ").append(i).append(" | item_").append(i).append(" | val_").append(i).append(" |\n");
        }

        List<Map<String, Object>> elements = FeishuCardPresenter.presentElements(sb.toString());
        assertThat(elements).hasSize(2);

        Map<String, Object> table = elements.get(0);
        assertThat(table.get("tag")).isEqualTo("table");

        @SuppressWarnings("unchecked")
        List<?> rows = (List<?>) table.get("rows");
        assertThat(rows).hasSize(FeishuCardPresenter.MAX_TABLE_ROWS);

        Map<String, Object> notice = elements.get(1);
        assertThat(notice.get("tag")).isEqualTo("markdown");
        assertThat((String) notice.get("content")).contains("表格共 120 行，卡片展示前 100 行");
    }

    @Test
    void setextHeadingNormalization() {
        String input = """
                主标题
                ======

                这是正文段落。

                副标题
                ------

                这是次级段落。
                """;

        List<Map<String, Object>> elements = FeishuCardPresenter.presentElements(input);
        assertThat(elements).hasSize(1);
        String content = (String) elements.getFirst().get("content");
        assertThat(content).contains("# 主标题");
        assertThat(content).contains("## 副标题");
        assertThat(content).doesNotContain("======");
        assertThat(content).doesNotContain("------");
    }

    @Test
    void tableWithEscapedPipesAndCodeSpans() {
        String input = """
                | Pattern | Example | Description |
                |---|---|---|
                | regex | `a|b` | match a or b |
                | escaped | a \\| b | literal pipe |
                """;

        List<Map<String, Object>> elements = FeishuCardPresenter.presentElements(input);
        assertThat(elements).hasSize(1);
        Map<String, Object> table = elements.getFirst();
        assertThat(table.get("tag")).isEqualTo("table");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) table.get("rows");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("c1", "`a|b`");
        assertThat(rows.get(1)).containsEntry("c1", "a | b");
    }

    @Test
    void emptyAndBlankInputReturnsSafeCard() {
        Map<String, Object> card = FeishuCardPresenter.cardPayload(null);
        assertThat(card.get("schema")).isEqualTo("2.0");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) card.get("body");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> elements = (List<Map<String, Object>>) body.get("elements");
        assertThat(elements).hasSize(1);
        assertThat(elements.getFirst()).isEqualTo(Map.of("tag", "markdown", "content", ""));
    }

    @Test
    void sizeAwareCardPayloadBounding() {
        String hugeText = "A".repeat(30_000);
        List<Map<String, Object>> elements = FeishuCardPresenter.presentElements(hugeText);
        assertThat(elements).hasSize(1);
        String content = (String) elements.getFirst().get("content");
        assertThat(content.length()).isLessThan(30_000);
        assertThat(content).contains("*(内容较长，已展示前部分内容，完整结果请在 Web 查看)*");
    }
}

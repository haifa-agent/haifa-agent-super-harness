package io.haifa.agent.superharness.server.channels.feishu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic Markdown-to-Feishu Card 2.0 presentation conversion layer.
 *
 * <p>Addresses Feishu formatting limitations where raw Markdown pipe tables and headings
 * render as literal text in Card 1.0 or non-standard card payloads.
 *
 * <ul>
 *   <li>Standardizes outbound reply cards on Card 2.0 ({@code schema: "2.0"}).
 *   <li>Converts GFM pipe tables into native Feishu Card 2.0 {@code tag: "table"} elements
 *       with {@code data_type: "lark_md"} so inline code, bold, and emojis render cleanly.
 *   <li>Falls back to mobile-friendly structured row/field representation when true table layout
 *       is unavailable, exceeding capacity (>5 tables, >50 columns), or explicitly requested.
 *   <li>Ensures fenced code blocks remain strictly literal (never corrupted into card layout).
 *   <li>Preserves UTF-8 Chinese characters, emojis, whitespace, and logical heading hierarchy.
 *   <li>Applies size-aware bounding to prevent provider card payload overflow.
 * </ul>
 */
public final class FeishuCardPresenter {

    public static final int MAX_TABLE_ELEMENTS_PER_CARD = 5;
    public static final int MAX_TABLE_COLUMNS = 50;
    public static final int MAX_TABLE_ROWS = 100;
    public static final int MAX_CARD_PAYLOAD_CHARS = 24_000;

    private static final Pattern ATX_HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern SETEXT_H1 = Pattern.compile("^={2,}\\s*$");
    private static final Pattern SETEXT_H2 = Pattern.compile("^-{2,}\\s*$");
    private static final Pattern TABLE_DELIMITER_CELL = Pattern.compile("^:?-+:?$");

    private FeishuCardPresenter() {}

    /**
     * Builds a complete Feishu Card 2.0 payload for ordinary assistant messages,
     * progress cards, and final responses.
     */
    public static Map<String, Object> cardPayload(String markdownText) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("schema", "2.0");
        card.put("config", Map.of("wide_screen_mode", true, "update_multi", true));
        card.put("body", Map.of("elements", presentElements(markdownText)));
        return card;
    }

    /**
     * Parses Markdown text into structured Card 2.0 elements (markdown blocks, native table components).
     */
    public static List<Map<String, Object>> presentElements(String markdownText) {
        if (markdownText == null || markdownText.isBlank()) {
            return List.of(Map.of("tag", "markdown", "content", ""));
        }

        String boundedText = boundPayloadSize(markdownText);
        List<Block> blocks = parseBlocks(boundedText);

        List<Map<String, Object>> elements = new ArrayList<>();
        StringBuilder markdownBuffer = new StringBuilder();
        int tableComponentCount = 0;
        TableMode tableMode = resolveTableMode();

        for (Block block : blocks) {
            if (block instanceof MarkdownBlock mb) {
                if (!mb.content().isEmpty()) {
                    if (!markdownBuffer.isEmpty()) {
                        markdownBuffer.append("\n\n");
                    }
                    markdownBuffer.append(mb.content());
                }
            } else if (block instanceof TableBlock tb) {
                boolean canUseNativeTable = tableMode != TableMode.LIST
                        && tableComponentCount < MAX_TABLE_ELEMENTS_PER_CARD
                        && tb.columns().size() >= 2
                        && tb.columns().size() <= MAX_TABLE_COLUMNS
                        && !tb.rows().isEmpty();

                if (canUseNativeTable) {
                    flushMarkdownBuffer(elements, markdownBuffer);
                    elements.add(tb.toCard2TableElement());
                    tableComponentCount++;
                    if (tb.truncated()) {
                        elements.add(Map.of(
                                "tag", "markdown",
                                "content", String.format(Locale.ROOT, "*(表格共 %d 行，卡片展示前 %d 行)*", tb.totalRows(), MAX_TABLE_ROWS)));
                    }
                } else {
                    // Fall back to mobile-friendly row/field representation
                    String listFormat = tb.toMobileFriendlyList();
                    if (!markdownBuffer.isEmpty()) {
                        markdownBuffer.append("\n\n");
                    }
                    markdownBuffer.append(listFormat);
                }
            }
        }

        flushMarkdownBuffer(elements, markdownBuffer);

        if (elements.isEmpty()) {
            elements.add(Map.of("tag", "markdown", "content", ""));
        }

        return elements;
    }

    /**
     * Converts raw Markdown containing unsupported tables into mobile-friendly Markdown
     * without raw delimiter rows, suitable for embedding inside single markdown components.
     */
    public static String presentMarkdown(String markdownText) {
        if (markdownText == null || markdownText.isBlank()) {
            return "";
        }
        List<Block> blocks = parseBlocks(markdownText);
        StringBuilder result = new StringBuilder();
        for (Block block : blocks) {
            if (block instanceof MarkdownBlock mb) {
                if (!mb.content().isEmpty()) {
                    if (!result.isEmpty()) {
                        result.append("\n\n");
                    }
                    result.append(mb.content());
                }
            } else if (block instanceof TableBlock tb) {
                if (!result.isEmpty()) {
                    result.append("\n\n");
                }
                result.append(tb.toMobileFriendlyList());
            }
        }
        return result.toString();
    }

    private static void flushMarkdownBuffer(List<Map<String, Object>> elements, StringBuilder buffer) {
        if (!buffer.isEmpty()) {
            String content = buffer.toString().trim();
            if (!content.isEmpty()) {
                elements.add(Map.of("tag", "markdown", "content", content));
            }
            buffer.setLength(0);
        }
    }

    private static String boundPayloadSize(String text) {
        if (text.length() <= MAX_CARD_PAYLOAD_CHARS) {
            return text;
        }
        return text.substring(0, MAX_CARD_PAYLOAD_CHARS)
                + "\n\n*(内容较长，已展示前部分内容，完整结果请在 Web 查看)*";
    }

    // --- Block parsing ---

    sealed interface Block permits MarkdownBlock, TableBlock {}

    record MarkdownBlock(String content) implements Block {}

    record TableBlock(
            List<String> columns,
            List<String> alignments,
            List<List<String>> rows,
            int totalRows,
            boolean truncated) implements Block {

        Map<String, Object> toCard2TableElement() {
            Map<String, Object> table = new LinkedHashMap<>();
            table.put("tag", "table");
            table.put("page_size", Math.min(10, Math.max(1, Math.min(5, rows.size()))));
            table.put("row_height", "low");
            table.put("header_style", Map.of("text_align", "left", "bold", true));

            List<Map<String, Object>> columnDefs = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                String colHeader = columns.get(i);
                String colName = "c" + i;
                String align = i < alignments.size() ? alignments.get(i) : "left";
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("name", colName);
                col.put("display_name", colHeader.isBlank() ? "Col " + (i + 1) : colHeader);
                col.put("data_type", "lark_md");
                col.put("horizontal_align", align);
                columnDefs.add(col);
            }
            table.put("columns", columnDefs);

            List<Map<String, Object>> rowMaps = new ArrayList<>();
            for (List<String> rowData : rows) {
                Map<String, Object> rowMap = new LinkedHashMap<>();
                for (int c = 0; c < columns.size(); c++) {
                    String val = c < rowData.size() ? rowData.get(c) : "";
                    rowMap.put("c" + c, val);
                }
                rowMaps.add(rowMap);
            }
            table.put("rows", rowMaps);

            return table;
        }

        String toMobileFriendlyList() {
            StringBuilder sb = new StringBuilder();
            boolean isTwoCol = columns.size() == 2;

            for (int r = 0; r < rows.size(); r++) {
                List<String> row = rows.get(r);
                if (r > 0) {
                    sb.append("\n");
                }
                if (isTwoCol) {
                    String k = !row.isEmpty() ? row.getFirst() : "";
                    String v = row.size() > 1 ? row.get(1) : "";
                    sb.append("• **").append(k).append("**: ").append(v);
                } else {
                    String firstVal = !row.isEmpty() ? row.getFirst() : "";
                    String firstHeader = !columns.isEmpty() ? columns.getFirst() : "项";
                    sb.append("**[").append(r + 1).append("] ").append(firstHeader).append(": ").append(firstVal).append("**");
                    for (int c = 1; c < columns.size(); c++) {
                        String header = columns.get(c);
                        String val = c < row.size() ? row.get(c) : "";
                        sb.append("\n  • **").append(header).append("**: ").append(val);
                    }
                }
            }
            if (truncated) {
                sb.append(String.format(Locale.ROOT, "\n*(表格共 %d 行，卡片展示前 %d 行)*", totalRows, MAX_TABLE_ROWS));
            }
            return sb.toString();
        }
    }

    static List<Block> parseBlocks(String text) {
        String[] rawLines = text.split("\\R", -1);
        List<Block> blocks = new ArrayList<>();
        StringBuilder currentMarkdown = new StringBuilder();

        int i = 0;
        while (i < rawLines.length) {
            String line = rawLines[i];

            // 1. Check for fenced code block start
            String codeFence = getCodeFence(line);
            if (codeFence != null) {
                // Collect lines until closing fence
                currentMarkdown.append(line).append("\n");
                i++;
                while (i < rawLines.length) {
                    String codeLine = rawLines[i];
                    currentMarkdown.append(codeLine).append("\n");
                    if (isClosingCodeFence(codeLine, codeFence)) {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }

            // 2. Check for GFM table start: must be header row followed by delimiter row
            if (isTableHeaderRow(line) && i + 1 < rawLines.length && isTableDelimiterRow(rawLines[i + 1])) {
                List<String> headers = splitCells(line);
                List<String> alignments = parseAlignments(rawLines[i + 1]);

                if (headers.size() == alignments.size() && headers.size() >= 2) {
                    // Flush accumulated markdown before table
                    if (!currentMarkdown.isEmpty()) {
                        blocks.add(new MarkdownBlock(currentMarkdown.toString().trim()));
                        currentMarkdown.setLength(0);
                    }

                    i += 2; // skip header and delimiter
                    List<List<String>> rows = new ArrayList<>();
                    int totalDataRows = 0;

                    while (i < rawLines.length) {
                        String rowLine = rawLines[i];
                        if (rowLine.isBlank() || getCodeFence(rowLine) != null) {
                            break;
                        }
                        if (!rowLine.contains("|")) {
                            break;
                        }
                        List<String> cells = splitCells(rowLine);
                        totalDataRows++;
                        if (rows.size() < MAX_TABLE_ROWS) {
                            rows.add(cells);
                        }
                        i++;
                    }

                    boolean truncated = totalDataRows > MAX_TABLE_ROWS;
                    blocks.add(new TableBlock(headers, alignments, rows, totalDataRows, truncated));
                    continue;
                }
            }

            // 3. Check for Setext headings: Line 1 followed by === (H1) or --- (H2)
            if (i + 1 < rawLines.length && !line.isBlank() && !line.contains("|")) {
                String nextLine = rawLines[i + 1];
                if (SETEXT_H1.matcher(nextLine).matches()) {
                    currentMarkdown.append("# ").append(line.trim()).append("\n");
                    i += 2;
                    continue;
                } else if (SETEXT_H2.matcher(nextLine).matches()) {
                    currentMarkdown.append("## ").append(line.trim()).append("\n");
                    i += 2;
                    continue;
                }
            }

            // Standard line
            currentMarkdown.append(line).append("\n");
            i++;
        }

        if (!currentMarkdown.isEmpty()) {
            blocks.add(new MarkdownBlock(currentMarkdown.toString().trim()));
        }

        return blocks;
    }

    private static String getCodeFence(String line) {
        String trimmed = line.stripLeading();
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            char ch = trimmed.charAt(0);
            int count = 0;
            while (count < trimmed.length() && trimmed.charAt(count) == ch) {
                count++;
            }
            if (count >= 3) {
                return trimmed.substring(0, count);
            }
        }
        return null;
    }

    private static boolean isClosingCodeFence(String line, String openFence) {
        String trimmed = line.trim();
        char ch = openFence.charAt(0);
        int count = 0;
        while (count < trimmed.length() && trimmed.charAt(count) == ch) {
            count++;
        }
        return count >= openFence.length() && count == trimmed.length();
    }

    private static boolean isTableHeaderRow(String line) {
        String trimmed = line.trim();
        if (!trimmed.contains("|")) {
            return false;
        }
        List<String> cells = splitCells(trimmed);
        return cells.size() >= 2;
    }

    private static boolean isTableDelimiterRow(String line) {
        String trimmed = line.trim();
        if (!trimmed.contains("-") || !trimmed.contains("|")) {
            return false;
        }
        List<String> cells = splitCells(trimmed);
        if (cells.size() < 2) {
            return false;
        }
        for (String cell : cells) {
            if (!TABLE_DELIMITER_CELL.matcher(cell.trim()).matches()) {
                return false;
            }
        }
        return true;
    }

    public static List<String> splitCells(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|") && !trimmed.endsWith("\\|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inBacktick = false;

        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\\' && i + 1 < trimmed.length()) {
                char next = trimmed.charAt(i + 1);
                if (next == '|') {
                    current.append('|');
                    i++;
                    continue;
                }
                current.append(c);
                continue;
            }
            if (c == '`') {
                inBacktick = !inBacktick;
                current.append(c);
                continue;
            }
            if (c == '|' && !inBacktick) {
                cells.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        cells.add(current.toString().trim());
        return cells;
    }

    private static List<String> parseAlignments(String delimiterLine) {
        List<String> cells = splitCells(delimiterLine);
        List<String> alignments = new ArrayList<>();
        for (String cell : cells) {
            String c = cell.trim();
            boolean leftColon = c.startsWith(":");
            boolean rightColon = c.endsWith(":");
            if (leftColon && rightColon) {
                alignments.add("center");
            } else if (rightColon) {
                alignments.add("right");
            } else {
                alignments.add("left");
            }
        }
        return alignments;
    }

    public enum TableMode {
        AUTO,
        NATIVE,
        LIST
    }

    private static TableMode resolveTableMode() {
        String env = System.getenv("HAIFA_SUPER_HARNESS_FEISHU_TABLE_MODE");
        if (env == null || env.isBlank()) {
            return TableMode.AUTO;
        }
        return switch (env.trim().toLowerCase(Locale.ROOT)) {
            case "native", "table" -> TableMode.NATIVE;
            case "list", "mobile" -> TableMode.LIST;
            default -> TableMode.AUTO;
        };
    }
}

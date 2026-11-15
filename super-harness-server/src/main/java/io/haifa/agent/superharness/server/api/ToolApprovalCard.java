package io.haifa.agent.superharness.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionInputType;
import io.haifa.agent.runtime.api.InteractionKind;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.ToolCallView;
import io.haifa.agent.superharness.server.config.ExecutionProvider;
import io.haifa.agent.superharness.server.tool.web.WebToolConfig;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Projects the SDK's bounded ToolCallView into the approval card shown before execution. */
final class ToolApprovalCard {
    private static final String UNKNOWN = "无法确认";
    private static final String HIDDEN = "[已隐藏]";
    private static final int PREVIEW_LIMIT = 2048;
    private static final int SUMMARY_LIMIT = 420;
    private static final int URL_LIMIT = 2048;
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");
    private static final Pattern LEGACY_CONTEXT =
            Pattern.compile("\\AApproval required for ([A-Za-z0-9][A-Za-z0-9_.:-]{0,127})(?:@|#|\\z)");
    private static final Pattern SENSITIVE_TEXT = Pattern.compile(
            "(?i)\\[REDACTED]|(?:^|[\\s\"'_,;])"
                    + "(?:pass(?:word|wd)?|secret|token|api[-_]?key|authorization|cookie|credential|signature|private[-_]?key)"
                    + "[\"']?\\s*[:=]|--(?:password|passwd|secret|token|api[-_]?key|authorization|cookie|user)\\b"
                    + "|://[^\\s/]+@|BEGIN [A-Z ]*PRIVATE KEY|\\bBearer\\s+|\\bsk-[A-Za-z0-9_-]+");
    private static final Pattern SENSITIVE_QUERY_KEY =
            Pattern.compile("(?i)token|secret|pass|key|signature|credential|auth|cookie|session|(?:^|[-_])sig(?:$|[-_])");
    private static final Set<InteractionAction> STANDARD_ACTIONS =
            Set.of(InteractionAction.APPROVE, InteractionAction.REJECT);

    private ToolApprovalCard() {}

    static Optional<ToolCallView> targetCall(InteractionView request, List<ToolCallView> calls) {
        if (!"tool".equals(request.target().type())) return Optional.empty();
        return calls.stream().filter(call -> matchesTarget(request, call)).findFirst();
    }

    private static boolean matchesTarget(InteractionView request, ToolCallView call) {
        return "tool".equals(request.target().type())
                && call.id().value().equals(request.target().reference())
                && call.runId().equals(request.runId());
    }

    static boolean sessionEligible(InteractionView request, ToolCallView call, ExecutionProvider provider) {
        if (!matchesTarget(request, call)
                || !InteractionKind.APPROVAL.equals(request.kind())
                || request.state() != InteractionState.PENDING
                || !InteractionInputType.NONE.equals(request.inputContract().type())
                || !request.allowedActions().contains(InteractionAction.APPROVE)
                || !STANDARD_ACTIONS.containsAll(request.allowedActions())
                || call.status() != ToolCallStatus.WAITING_APPROVAL
                || request.safePrompt().regionMatches(true, 0, "Reauthenticate", 0, "Reauthenticate".length())
                || call.arguments().truncated()
                || !TOOL_NAME.matcher(call.toolName()).matches()) {
            return false;
        }
        Map<String, Object> args = call.arguments().values();
        return switch (call.toolName()) {
            case "execution_run" -> ExecutionApprovalCard.sessionEligible(request, call, provider);
            case "write_file" -> readableTarget(args.get("path"))
                    && args.get("content") instanceof String && optionalBoolean(args, "append");
            case "str_replace" -> readableTarget(args.get("path"))
                    && args.get("old_str") instanceof String oldText && !oldText.isEmpty()
                    && args.get("new_str") instanceof String && optionalBoolean(args, "replace_all");
            case "web_search" -> readableTarget(args.get("query"))
                    && (!args.containsKey("max_results") || args.get("max_results") instanceof Number)
                    && (!args.containsKey("time_range") || args.get("time_range") instanceof String);
            case "web_fetch" -> safeUrl(args.get("url")).filter(SafeUrl::completeTarget).isPresent();
            default -> !String.valueOf(args).contains("[TRUNCATED]") && !SENSITIVE_TEXT.matcher(args.toString()).find();
        };
    }

    static JsonNode build(
            InteractionView request, ToolCallView call, ExecutionProvider provider, WebToolConfig web) {
        if ("execution_run".equals(call.toolName())) {
            ObjectNode execution = ExecutionApprovalCard.build(request, call, provider);
            normalizeExecution(execution);
            execution.put("session_eligible", sessionEligible(request, call, provider));
            return execution;
        }
        String tool = TOOL_NAME.matcher(call.toolName()).matches() ? call.toolName() : "tool";
        Map<String, Object> args = call.arguments().values();
        ObjectNode card = card(tool);
        switch (tool) {
            case "write_file" -> write(card, args);
            case "str_replace" -> replace(card, args);
            case "web_search" -> search(card, args, web);
            case "web_fetch" -> fetch(card, args, web);
            default -> {
                fact(facts(card), "工具", tool);
                impact(card).add("批准后将执行此工具请求。");
            }
        }
        if (call.arguments().truncated()) impact(card).add("部分参数超出展示范围，仅支持逐次审批。");
        card.put("session_eligible", sessionEligible(request, call, provider));
        return card;
    }

    /** Existing history predates the structured target snapshot. Use only the safe tool name. */
    static JsonNode legacy(String context) {
        if (context == null) return null;
        var matcher = LEGACY_CONTEXT.matcher(context);
        if (!matcher.find()) return null;
        String tool = matcher.group(1);
        ObjectNode card = card(tool);
        card.put("purpose", "会话记录中的审批请求。");
        fact(facts(card), "工具", tool);
        impact(card).add("早期记录不包含结构化请求详情。");
        return card;
    }

    private static void normalizeExecution(ObjectNode card) {
        card.put("tool_name", "execution_run");
        card.put("detail_title", "查看执行详情");
        card.put("impact_title", "执行影响");
        ArrayNode facts = card.putArray("facts");
        fact(facts, "执行环境", card.remove("environment").asText());
        fact(facts, "工作目录", card.remove("workspace").asText());
        fact(facts, "网络访问", card.remove("network").asText());
    }

    private static ObjectNode card(String tool) {
        ObjectNode card = JsonNodeFactory.instance.objectNode();
        card.put("tool_name", tool);
        card.put("title", switch (tool) {
            case "write_file" -> "写入文件";
            case "str_replace", "edit_file" -> "修改文件";
            case "web_search" -> "联网搜索";
            case "web_fetch" -> "读取网页";
            case "execution_run" -> "执行命令或脚本";
            default -> "执行工具";
        });
        card.put("purpose", "继续当前任务。");
        card.put("impact_title", "执行影响");
        card.put("detail_title", "查看请求详情");
        card.put("detail_label", "");
        card.put("session_eligible", false);
        card.putArray("facts");
        card.putArray("impact");
        card.putArray("technical_facts");
        return card;
    }

    private static void write(ObjectNode card, Map<String, Object> args) {
        boolean append = Boolean.TRUE.equals(args.get("append"));
        card.put("purpose", summary(args.get("description"), "将内容保存到目标文件。"));
        fact(facts(card), "目标文件", summary(args.get("path"), UNKNOWN));
        fact(facts(card), "工作区", "当前任务工作区");
        fact(facts(card), "写入方式", append ? "追加内容" : "写入完整内容");
        card.put("impact_title", append ? "会在文件末尾追加" : "可能覆盖文件的现有内容");
        impact(card).add(append ? "目标文件不存在时会创建。" : "目标文件已存在时，原内容将被替换。");
        card.put("detail_title", "查看写入内容");
        card.put("detail_label", "写入预览");
        card.put("content", preview(args.get("content")));
        card.put("content_type", "text");
        fact(technical(card), "写入模式", append ? "追加" : "覆盖");
    }

    private static void replace(ObjectNode card, Map<String, Object> args) {
        boolean replaceAll = Boolean.TRUE.equals(args.get("replace_all"));
        card.put("purpose", summary(args.get("description"), "替换目标文件中匹配的文本。"));
        fact(facts(card), "目标文件", summary(args.get("path"), UNKNOWN));
        fact(facts(card), "工作区", "当前任务工作区");
        fact(facts(card), "替换范围", replaceAll ? "替换所有匹配" : "仅替换第一处匹配");
        card.put("impact_title", "会写入目标文件");
        impact(card).add("匹配到的文本会被替换为新内容。");
        card.put("detail_title", "查看改动");
        card.put("detail_label", "修改前后对比");
        card.put("before", preview(args.get("old_str")));
        card.put("after", preview(args.get("new_str")));
    }

    private static void search(ObjectNode card, Map<String, Object> args, WebToolConfig config) {
        card.put("purpose", "查找与搜索词匹配的网页。");
        fact(facts(card), "搜索词", summary(args.get("query"), UNKNOWN));
        fact(facts(card), "搜索服务", config.searchProvider() == WebToolConfig.WebSearchProvider.DEEPSEEK
                ? "DeepSeek" : "DuckDuckGo");
        card.put("impact_title", "会向外部服务发送搜索词");
        impact(card).add("搜索结果会返回当前任务。");
        card.put("detail_title", "查看搜索参数");
        card.put("detail_label", "结果数与时间范围");
        Object maximum = args.get("max_results");
        fact(technical(card), "最多返回", (maximum instanceof Number ? maximum : config.maxResults()) + " 条");
        fact(technical(card), "时间范围", switch (summary(args.get("time_range"), "")) {
            case "day" -> "最近一天";
            case "week" -> "最近一周";
            case "month" -> "最近一个月";
            case "year" -> "最近一年";
            case "" -> "不限";
            default -> UNKNOWN;
        });
    }

    private static void fetch(ObjectNode card, Map<String, Object> args, WebToolConfig config) {
        card.put("purpose", "读取指定网页的内容。");
        var url = safeUrl(args.get("url"));
        fact(facts(card), "目标域名", url.map(SafeUrl::host).orElse(UNKNOWN));
        fact(facts(card), "请求地址", url.map(SafeUrl::display).orElse(UNKNOWN));
        fact(facts(card), "读取服务", config.fetchProvider() == WebToolConfig.WebFetchProvider.DEEPSEEK
                ? "DeepSeek" : "Jina Reader");
        card.put("impact_title", "会向外部服务发送请求地址");
        impact(card).add("读取服务将获取网页内容并返回给当前任务。");
        card.put("detail_label", "地址与参数");
        url.ifPresent(value -> {
            fact(technical(card), "URL 路径", value.path());
            if (value.query()) fact(technical(card), "查询参数", "值已隐藏");
        });
    }

    private static Optional<SafeUrl> safeUrl(Object arg) {
        if (!(arg instanceof String value) || value.length() > URL_LIMIT) return Optional.empty();
        try {
            // SDK may already redact credential values with a literal marker.
            URI uri = URI.create(value.replace("[REDACTED]", "%5BREDACTED%5D"));
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return Optional.empty();
            }
            String rawPath = uri.getRawPath();
            String path = rawPath.isEmpty() ? "/" : rawPath;
            boolean sensitive = SENSITIVE_TEXT.matcher(uri.getPath()).find();
            if (sensitive) path = "/" + HIDDEN;
            String rawQuery = uri.getRawQuery();
            boolean hasQuery = rawQuery != null && !rawQuery.isEmpty();
            if (hasQuery) {
                for (String pair : rawQuery.split("&")) {
                    int equals = pair.indexOf('=');
                    String key = equals < 0 ? pair : pair.substring(0, equals);
                    if (SENSITIVE_QUERY_KEY.matcher(URLDecoder.decode(key, StandardCharsets.UTF_8)).find()) {
                        sensitive = true;
                        break;
                    }
                }
            }
            String display = scheme.toLowerCase(Locale.ROOT) + "://" + host
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
                    + path + (hasQuery ? "?" + HIDDEN : "");
            return Optional.of(new SafeUrl(host, limit(display, SUMMARY_LIMIT), limit(path, SUMMARY_LIMIT),
                    hasQuery, !sensitive && uri.getRawUserInfo() == null && display.length() <= SUMMARY_LIMIT));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private static boolean readableTarget(Object arg) {
        return arg instanceof String value && !value.isBlank()
                && value.length() <= SUMMARY_LIMIT && !value.contains("[TRUNCATED]")
                && !SENSITIVE_TEXT.matcher(value).find();
    }

    private static boolean optionalBoolean(Map<String, Object> arguments, String key) {
        return !arguments.containsKey(key) || arguments.get(key) instanceof Boolean;
    }

    private static String summary(Object arg, String fallback) {
        if (!(arg instanceof String value) || value.isBlank()) return fallback;
        return SENSITIVE_TEXT.matcher(value).find() ? HIDDEN : limit(value, SUMMARY_LIMIT);
    }

    private static String preview(Object arg) {
        if (!(arg instanceof String value)) return UNKNOWN;
        if (SENSITIVE_TEXT.matcher(value).find()) return HIDDEN;
        if (value.isEmpty()) return "（空文本）";
        return limit(value, PREVIEW_LIMIT);
    }

    private static String limit(String text, int maxCharacters) {
        if (text.length() <= maxCharacters) return text;
        int end = Character.isHighSurrogate(text.charAt(maxCharacters - 1)) ? maxCharacters - 1 : maxCharacters;
        return text.substring(0, end) + "…（已截断）";
    }

    private static ArrayNode facts(ObjectNode card) {
        return (ArrayNode) card.get("facts");
    }

    private static ArrayNode technical(ObjectNode card) {
        return (ArrayNode) card.get("technical_facts");
    }

    private static ArrayNode impact(ObjectNode card) {
        return (ArrayNode) card.get("impact");
    }

    private static void fact(ArrayNode array, String label, String value) {
        array.addObject().put("label", label).put("value", value);
    }

    private record SafeUrl(String host, String display, String path, boolean query, boolean completeTarget) {}
}

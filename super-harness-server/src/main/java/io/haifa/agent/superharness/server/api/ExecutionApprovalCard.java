package io.haifa.agent.superharness.server.api;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.ToolCallView;
import io.haifa.agent.superharness.server.config.ExecutionProvider;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Safe, display-only projection of the existing SDK execution approval. */
final class ExecutionApprovalCard {
    static final String ALLOW_SESSION = "allow_session";
    static final String AUTO_APPROVE = "auto_approve";
    private static final Set<String> EXECUTION_FIELDS = Set.of(
            "mode", "language", "content", "args", "purpose", "timeoutMillis");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)\\[(?:REDACTED|TRUNCATED)]|--(?:password|passwd|secret|token|api[-_]?key|authorization|cookie|user)\\b"
                    + "|\\b(?:password|passwd|secret|token|api[-_]?key|authorization|cookie)\\s*[:=]"
                    + "|(?:^|\\s)-u(?:\\s|,)|://[^\\s/]+:[^\\s/]+@|BEGIN [A-Z ]*PRIVATE KEY|\\bBearer\\s+|\\bsk-[A-Za-z0-9_-]+");

    private ExecutionApprovalCard() {}

    static boolean sessionEligible(InteractionView request, ToolCallView call, ExecutionProvider provider) {
        Map<String, Object> args = call.arguments().values();
        return provider.isEnabled()
                && call.status() == ToolCallStatus.WAITING_APPROVAL
                && request.allowedActions().contains(InteractionAction.APPROVE)
                && !request.safePrompt().startsWith("Reauthenticate")
                && !call.arguments().truncated()
                && EXECUTION_FIELDS.containsAll(args.keySet())
                && ("COMMAND".equals(args.get("mode")) || "SCRIPT".equals(args.get("mode")))
                && args.get("content") instanceof String content && !content.isBlank()
                && !SENSITIVE.matcher(args.toString()).find();
    }

    static ObjectNode build(InteractionView request, ToolCallView call, ExecutionProvider provider) {
        Map<String, Object> args = call.arguments().values();
        boolean script = "SCRIPT".equals(args.get("mode"));
        boolean host = provider == ExecutionProvider.HOST_GUARDED;
        var result = JsonNodeFactory.instance.objectNode();
        result.put("title", host ? (script ? "运行本机脚本" : "运行本机命令")
                : provider == ExecutionProvider.DOCKER_AIO
                        ? (script ? "在容器内运行脚本" : "在容器内运行命令") : "执行命令或脚本");
        result.put("purpose", safeText(args.get("purpose"), "继续当前任务。"));
        result.put("environment", host ? "宿主机" : provider == ExecutionProvider.DOCKER_AIO ? "一次性容器" : "无法确认");
        result.put("workspace", host ? "本次任务的工作目录" : provider == ExecutionProvider.DOCKER_AIO
                ? "挂载的任务工作目录" : "无法确认");
        result.put("network", host ? "可使用宿主机网络" : provider == ExecutionProvider.DOCKER_AIO
                ? "按容器网络配置运行" : "无法确认");
        var impact = result.putArray("impact");
        if (host) {
            impact.add("以当前系统用户权限执行，可访问该用户有权访问的文件。");
            impact.add("进程没有强隔离，可发起网络请求。");
        } else if (provider == ExecutionProvider.DOCKER_AIO) {
            impact.add("进程在容器内运行，文件访问受挂载配置约束。");
        } else {
            impact.add("执行环境无法确认，请核对执行详情。");
        }
        if (call.arguments().truncated()) impact.add("执行详情超出展示范围，仅支持逐次审批。");
        result.put("detail_label", script ? "脚本与参数" : "命令与参数");
        result.put("content_type", safeText(args.get("language"), script ? "脚本" : "默认 Shell"));
        result.put("content", safeText(args.get("content"), "执行内容不可用。"));
        var technical = result.putArray("technical_facts");
        if (args.get("args") instanceof List<?> arguments && !arguments.isEmpty()) {
            addFact(technical, "独立参数", safeText(arguments.toString(), "—"));
        }
        if (args.get("timeoutMillis") instanceof Number timeout) {
            addFact(technical, "超时", timeout + " ms");
        }
        result.put("session_eligible", sessionEligible(request, call, provider));
        return result;
    }

    private static void addFact(com.fasterxml.jackson.databind.node.ArrayNode facts, String label, String value) {
        facts.addObject().put("label", label).put("value", value);
    }

    private static String safeText(Object value, String fallback) {
        if (!(value instanceof String text) || text.isBlank()) return fallback;
        return SENSITIVE.matcher(text).find() ? "含敏感参数，已隐藏。" : text;
    }
}

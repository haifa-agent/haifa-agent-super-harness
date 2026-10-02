package io.haifa.agent.superharness.server.tool.clarification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.superharness.server.tool.ToolDescriptions;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Frozen DeerFlow's builtin {@code ask_clarification} (frozen
 * {@code tools/builtins/clarification_tool.py}) implemented in SH because the SDK exposes no public
 * "request user input / end run" entry point.
 *
 * <p>Frozen intercepts the call in {@code ClarificationMiddleware} before tool execution; SH instead
 * runs a real {@link JavaTool}. The tool itself is a side-effect-free request builder: it normalizes
 * the frozen arguments (see {@link ClarificationRequestNormalizer}), returns the frozen plain-text
 * fallback as its result content, and exposes the structured {@code human_input} payload as JSON so
 * the run/projection layer can present the Human Input Card. Deciding to stop the run, rejecting
 * sibling tools and cancelling the model continuation are separate concerns handled by the run
 * layer, not by this class.
 *
 * <p>The schema mirrors the frozen model-facing contract: {@code question} and
 * {@code clarification_type} are required, {@code context}/{@code options}/{@code fields} are
 * optional, and a form field carries {@code name}/{@code label}/{@code type}/{@code required}/
 * {@code options}/{@code placeholder}. It declares itself {@code pure()}: the request never touches
 * files, the network or any external system.
 */
public final class ProductionAskClarificationTool
        implements JavaTool<ProductionAskClarificationTool.Input, ProductionAskClarificationTool.Output> {
    static final String SDK_LOCAL_PRINCIPAL = "default-public-user";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JavaToolSpec<Input, Output> SPEC =
            JavaToolSpec.builder("ask_clarification", Input.class, Output.class)
                    .title("Ask the user for clarification")
                    .description(ToolDescriptions.load("ask_clarification"))
                    .pure()
                    .build();

    public ProductionAskClarificationTool() {}

    @Override
    public JavaToolSpec<Input, Output> spec() {
        return SPEC;
    }

    @Override
    public Output invoke(Input input, JavaToolContext context) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(context, "context");
        if (!SDK_LOCAL_PRINCIPAL.equals(context.principal().principalId())
                || !"user".equals(context.principal().principalType())) {
            throw new IllegalArgumentException("ask_clarification requires the local Thread owner");
        }
        context.cancellation().throwIfCancellationRequested();
        if (!Instant.now().isBefore(context.deadline())) {
            throw new IllegalStateException("ask_clarification deadline has expired");
        }

        Map<String, Object> args = input.toArgs();
        List<Map<String, Object>> fields = ClarificationRequestNormalizer.normalizeFields(args.get("fields"));
        String content = ClarificationRequestNormalizer.formatClarificationMessage(args, fields);
        String toolCallId = context.toolCallId().value();
        // Publish the stop boundary before any sibling side-effecting SH tool can observe it.
        ClarificationBoundaryRegistry.mark(context.runId().value(), toolCallId);
        String requestId = ClarificationRequestNormalizer.stableRequestId(toolCallId, content);
        Map<String, Object> humanInput =
                ClarificationRequestNormalizer.buildHumanInputPayload(args, toolCallId, requestId, fields);
        try {
            return Output.success(content, JSON.writeValueAsString(humanInput));
        } catch (JsonProcessingException impossible) {
            return Output.failure("Unexpected error building clarification request");
        }
    }

    @Override
    public String summarize(Output output) {
        return output.error().isEmpty() ? output.content() : "Error: " + output.error();
    }

    /** Frozen {@code clarification_type} Literal values, in declaration order. */
    public enum ClarificationType {
        missing_info,
        ambiguous_requirement,
        approach_choice,
        risk_confirmation,
        suggestion
    }

    /** Frozen {@code ClarificationFormField.type} Literal values, in declaration order. */
    public enum FieldType {
        text,
        textarea,
        number,
        select,
        multi_select,
        checkbox,
        date
    }

    /** One form field definition for a structured clarification card. */
    public record FormField(
            String name,
            Optional<String> label,
            Optional<FieldType> type,
            Optional<Boolean> required,
            Optional<List<String>> options,
            Optional<String> placeholder) {
        public FormField {
            Objects.requireNonNull(name, "name");
            label = label == null ? Optional.empty() : label;
            type = type == null ? Optional.empty() : type;
            required = required == null ? Optional.empty() : required;
            options = options == null ? Optional.empty() : options;
            placeholder = placeholder == null ? Optional.empty() : placeholder;
        }

        Map<String, Object> toRaw() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            label.ifPresent(value -> map.put("label", value));
            type.ifPresent(value -> map.put("type", value.name()));
            required.ifPresent(value -> map.put("required", value));
            options.ifPresent(value -> map.put("options", value));
            placeholder.ifPresent(value -> map.put("placeholder", value));
            return map;
        }
    }

    public record Input(
            String question,
            ClarificationType clarification_type,
            Optional<String> context,
            Optional<List<String>> options,
            Optional<List<FormField>> fields) {
        public Input {
            Objects.requireNonNull(question, "question");
            Objects.requireNonNull(clarification_type, "clarification_type");
            context = context == null ? Optional.empty() : context;
            options = options == null ? Optional.empty() : options;
            fields = fields == null ? Optional.empty() : fields;
        }

        Map<String, Object> toArgs() {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("question", question);
            args.put("clarification_type", clarification_type.name());
            context.ifPresent(value -> args.put("context", value));
            options.ifPresent(value -> args.put("options", value));
            fields.ifPresent(value -> args.put("fields", value.stream().map(FormField::toRaw).toList()));
            return args;
        }
    }

    public record Output(String content, String humanInput, String error) {
        static Output success(String content, String humanInput) {
            return new Output(content, humanInput, "");
        }

        static Output failure(String error) {
            return new Output("", "", error);
        }
    }
}

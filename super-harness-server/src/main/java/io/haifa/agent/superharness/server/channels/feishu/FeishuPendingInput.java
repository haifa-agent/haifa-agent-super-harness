package io.haifa.agent.superharness.server.channels.feishu;

import java.util.List;

/**
 * Tail unanswered {@code ask_clarification} / approval card read from thread state. Not an approval
 * store.
 */
public record FeishuPendingInput(
        String source,
        String requestId,
        String clarificationType,
        String inputMode,
        String question,
        String preface,
        String content,
        List<Option> options,
        boolean formUnsupported,
        List<Field> fields,
        String riskLevel,
        String purpose,
        List<Fact> environmentFacts,
        List<Fact> technicalFacts) {

    public FeishuPendingInput(
            String source,
            String requestId,
            String clarificationType,
            String inputMode,
            String question,
            String preface,
            String content,
            List<Option> options,
            boolean formUnsupported) {
        this(
                source,
                requestId,
                clarificationType,
                inputMode,
                question,
                preface,
                content,
                options,
                formUnsupported,
                List.of(),
                "",
                "",
                List.of(),
                List.of());
    }

    public FeishuPendingInput {
        source = source == null ? "" : source;
        requestId = requestId == null ? "" : requestId;
        clarificationType = clarificationType == null ? "" : clarificationType;
        inputMode = inputMode == null ? "" : inputMode;
        question = question == null ? "" : question;
        preface = preface == null ? "" : preface;
        content = content == null ? "" : content;
        options = options == null ? List.of() : List.copyOf(options);
        fields = fields == null ? List.of() : List.copyOf(fields);
        riskLevel = riskLevel == null ? "" : riskLevel;
        purpose = purpose == null ? "" : purpose;
        environmentFacts = environmentFacts == null ? List.of() : List.copyOf(environmentFacts);
        technicalFacts = technicalFacts == null ? List.of() : List.copyOf(technicalFacts);
    }

    public record Option(String id, String label, String value) {
        public Option {
            id = id == null ? "" : id;
            label = label == null ? "" : label;
            value = value == null ? "" : value;
        }
    }

    /**
     * Form v2 field Feishu Card 2.0 can render: text / textarea / number / select / multi_select /
     * checkbox / date.
     */
    public record Field(
            String name, String label, String type, boolean required, String placeholder, List<Option> options) {
        public Field {
            name = name == null ? "" : name;
            label = label == null ? "" : label;
            type = type == null ? "" : type;
            placeholder = placeholder == null ? "" : placeholder;
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    public record Fact(String label, String value) {
        public Fact {
            label = label == null ? "" : label;
            value = value == null ? "" : value;
        }
    }

    public boolean singleChoice() {
        return "single_choice".equals(inputMode) || "haifa_interaction".equals(source);
    }

    public boolean formSupported() {
        return "form".equals(inputMode) && !formUnsupported && !fields.isEmpty();
    }
}

package io.haifa.agent.superharness.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic, side-effect-free port of frozen DeerFlow's {@code ClarificationMiddleware}
 * normalization (frozen {@code agents/middlewares/clarification_middleware.py}).
 *
 * <p>Frozen intercepts {@code ask_clarification} before tool execution, so it must validate raw
 * model arguments itself. SH registers a real {@code JavaTool}, but the same defensive rules are
 * kept here so a malformed argument can never produce an unrenderable card: structural errors
 * (non-object fields, bad/duplicate/reserved names, over-cap counts/lengths) degrade the whole form
 * to the legacy option/free-text modes, while benign issues (unknown type, option-less select)
 * degrade locally to {@code text}. This class only builds the request payload and text fallback; it
 * has no HTTP, projection or persistence concerns.
 */
final class ClarificationRequestNormalizer {
    static final int MAX_FORM_FIELDS = 16;
    static final int MAX_FIELD_OPTIONS = 24;
    static final int MAX_FIELD_TEXT_CHARS = 200;
    static final int MAX_FORM_SERIALIZED_BYTES = 16_384;
    static final Set<String> FORM_FIELD_TYPES =
            Set.of("text", "textarea", "number", "select", "multi_select", "checkbox", "date");
    static final Set<String> OPTION_FIELD_TYPES = Set.of("select", "multi_select");

    /**
     * Field names that collide with JavaScript {@code Object.prototype} properties. The frontend
     * stores form values in a plain object keyed by field name, so these would read inherited
     * prototype members instead of user input.
     */
    static final Set<String> RESERVED_FIELD_NAMES = Set.of(
            "__proto__",
            "constructor",
            "prototype",
            "toString",
            "toLocaleString",
            "valueOf",
            "hasOwnProperty",
            "isPrototypeOf",
            "propertyIsEnumerable",
            "__defineGetter__",
            "__defineSetter__",
            "__lookupGetter__",
            "__lookupSetter__");

    private static final Pattern XML_TAG_RE =
            Pattern.compile("</?[A-Za-z_][\\w:.-]*(?:\\s[^<>]*?)?\\s*/?>");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> TYPE_ICONS = Map.of(
            "missing_info", "\u2753",
            "ambiguous_requirement", "\ud83e\udd14",
            "approach_choice", "\ud83d\udd00",
            "risk_confirmation", "\u26a0\ufe0f",
            "suggestion", "\ud83d\udca1");

    private ClarificationRequestNormalizer() {}

    /**
     * Frozen {@code _normalize_options}: accept native lists, JSON-string arrays (some models
     * serialize arrays as strings), XML-to-dict payloads and scalars; strip residual XML tags, trim,
     * drop blanks and dedupe order-preserving.
     */
    static List<String> normalizeOptions(Object rawOptions) {
        Object options = rawOptions;
        if (options instanceof String text) {
            try {
                options = JSON.readValue(text, Object.class);
            } catch (JsonProcessingException | RuntimeException failure) {
                options = List.of(text);
            }
        }
        if (options == null) {
            return List.of();
        }
        if (options instanceof Map<?, ?> map) {
            options = flattenDictOptionValues(map);
        } else if (!(options instanceof List<?>)) {
            options = List.of(options);
        }

        List<String> normalized = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object option : (List<?>) options) {
            String text = XML_TAG_RE.matcher(pythonStr(option)).replaceAll("").strip();
            if (text.isEmpty() || !seen.add(text)) {
                continue;
            }
            normalized.add(text);
        }
        return normalized;
    }

    /** Frozen {@code _flatten_dict_option_values}: scalar leaves in source order. */
    private static List<Object> flattenDictOptionValues(Map<?, ?> value) {
        List<Object> flattened = new ArrayList<>();
        collectOptionLeaves(value, flattened);
        return flattened;
    }

    private static void collectOptionLeaves(Object nested, List<Object> out) {
        if (nested instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                collectOptionLeaves(item, out);
            }
        } else if (nested instanceof List<?> list) {
            for (Object item : list) {
                collectOptionLeaves(item, out);
            }
        } else if (nested instanceof String || nested instanceof Boolean || nested instanceof Number) {
            out.add(nested);
        }
    }

    /** Frozen {@code _normalize_bool}: models serialize booleans as strings or 1/0. */
    static boolean normalizeBool(Object raw) {
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof Number number) {
            return number.doubleValue() != 0.0d;
        }
        if (raw instanceof String text) {
            return text.strip().equalsIgnoreCase("true");
        }
        return false;
    }

    /**
     * Frozen {@code _normalize_fields}: atomic validation. A structurally broken entry invalidates
     * the whole form so a card can never render "complete" while missing a business field. Returns an
     * immutable list of normalized field maps, or an empty list when the form must degrade.
     */
    static List<Map<String, Object>> normalizeFields(Object rawFields) {
        Object fields = rawFields;
        if (fields instanceof String text) {
            try {
                fields = JSON.readValue(text, Object.class);
            } catch (JsonProcessingException | RuntimeException failure) {
                return List.of();
            }
        }
        if (!(fields instanceof List<?> list)) {
            return List.of();
        }
        if (list.size() > MAX_FORM_FIELDS) {
            return List.of();
        }

        List<Map<String, Object>> normalized = new ArrayList<>();
        Set<String> seenNames = new LinkedHashSet<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                return List.of();
            }
            Object rawName = map.get("name");
            if (!(rawName instanceof String nameText) || nameText.strip().isEmpty()) {
                return List.of();
            }
            String name = nameText.strip();
            if (RESERVED_FIELD_NAMES.contains(name)
                    || seenNames.contains(name)
                    || name.length() > MAX_FIELD_TEXT_CHARS) {
                return List.of();
            }
            seenNames.add(name);

            Object rawLabel = map.get("label");
            String label = rawLabel instanceof String labelText && !labelText.strip().isEmpty()
                    ? labelText.strip()
                    : name;
            if (label.length() > MAX_FIELD_TEXT_CHARS) {
                return List.of();
            }

            Object rawType = map.get("type");
            String fieldType = rawType instanceof String typeText && FORM_FIELD_TYPES.contains(typeText)
                    ? typeText
                    : "text";

            List<String> options = OPTION_FIELD_TYPES.contains(fieldType)
                    ? normalizeOptions(map.get("options"))
                    : List.of();
            if (options.size() > MAX_FIELD_OPTIONS
                    || options.stream().anyMatch(option -> option.length() > MAX_FIELD_TEXT_CHARS)) {
                return List.of();
            }
            if (OPTION_FIELD_TYPES.contains(fieldType) && options.isEmpty()) {
                fieldType = "text";
            }

            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", name);
            field.put("label", label);
            field.put("type", fieldType);
            field.put("required", normalizeBool(map.get("required")));
            if (OPTION_FIELD_TYPES.contains(fieldType)) {
                List<Map<String, Object>> optionPayload = new ArrayList<>();
                for (int index = 0; index < options.size(); index++) {
                    String option = options.get(index);
                    Map<String, Object> entryPayload = new LinkedHashMap<>();
                    entryPayload.put("id", name + "-option-" + (index + 1));
                    entryPayload.put("label", option);
                    entryPayload.put("value", option);
                    optionPayload.add(entryPayload);
                }
                field.put("options", optionPayload);
            }
            Object rawPlaceholder = map.get("placeholder");
            if (rawPlaceholder instanceof String placeholderText && !placeholderText.strip().isEmpty()) {
                String placeholder = placeholderText.strip();
                if (placeholder.length() > MAX_FIELD_TEXT_CHARS) {
                    return List.of();
                }
                field.put("placeholder", placeholder);
            }
            normalized.add(field);
        }

        if (pythonJsonBytes(normalized) > MAX_FORM_SERIALIZED_BYTES) {
            return List.of();
        }
        return normalized;
    }

    /**
     * Frozen {@code _build_human_input_payload}. Legacy modes stay {@code version: 1}; the v2
     * {@code form} mode carries {@code version: 2}. {@code fields} may be pre-normalized so callers
     * rendering both the payload and the text fallback normalize only once.
     */
    static Map<String, Object> buildHumanInputPayload(
            Map<String, Object> args, String toolCallId, String requestId, List<Map<String, Object>> fields) {
        List<Map<String, Object>> normalizedFields =
                fields == null ? normalizeFields(args.get("fields")) : fields;
        List<String> options = normalizeOptions(args.get("options"));

        int version;
        String inputMode;
        if (!normalizedFields.isEmpty()) {
            version = 2;
            inputMode = "form";
        } else if (!options.isEmpty()) {
            version = 1;
            inputMode = "choice_with_other";
        } else {
            version = 1;
            inputMode = "free_text";
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", version);
        payload.put("kind", "human_input_request");
        payload.put("source", "ask_clarification");
        payload.put("request_id", requestId);
        payload.put("clarification_type", normalizeClarificationType(args));
        payload.put("question", pythonTruthy(argument(args, "question")) ? pythonStr(argument(args, "question")) : "");
        payload.put("input_mode", inputMode);

        if (toolCallId != null && !toolCallId.isEmpty()) {
            payload.put("tool_call_id", toolCallId);
        }
        if (args.containsKey("context")) {
            Object context = args.get("context");
            payload.put("context", context == null ? null : pythonStr(context));
        }
        if ("form".equals(inputMode)) {
            payload.put("fields", normalizedFields);
        } else if (!options.isEmpty()) {
            List<Map<String, Object>> optionPayload = new ArrayList<>();
            for (int index = 0; index < options.size(); index++) {
                String option = options.get(index);
                Map<String, Object> entryPayload = new LinkedHashMap<>();
                entryPayload.put("id", "option-" + (index + 1));
                entryPayload.put("label", option);
                entryPayload.put("value", option);
                optionPayload.add(entryPayload);
            }
            payload.put("options", optionPayload);
        }
        return payload;
    }

    /** Frozen {@code _format_clarification_message}: readable plain-text fallback for the card. */
    static String formatClarificationMessage(Map<String, Object> args, List<Map<String, Object>> fields) {
        Object question = argument(args, "question", "");
        String clarificationType = normalizeClarificationType(args);
        Object context = args.get("context");
        List<Map<String, Object>> normalizedFields =
                fields == null ? normalizeFields(args.get("fields")) : fields;
        List<String> options = normalizeOptions(args.get("options"));

        String icon = TYPE_ICONS.getOrDefault(clarificationType, "\u2753");
        List<String> parts = new ArrayList<>();
        if (pythonTruthy(context)) {
            parts.add(icon + " " + pythonStr(context));
            parts.add("\n" + pythonStr(question));
        } else {
            parts.add(icon + " " + pythonStr(question));
        }

        if (!normalizedFields.isEmpty()) {
            parts.add("");
            for (int index = 0; index < normalizedFields.size(); index++) {
                Map<String, Object> field = normalizedFields.get(index);
                StringBuilder line = new StringBuilder("  " + (index + 1) + ". " + pythonStr(field.get("label")));
                if (Boolean.TRUE.equals(field.get("required"))) {
                    line.append(" (required)");
                }
                Object fieldOptions = field.get("options");
                if (fieldOptions instanceof List<?> list && !list.isEmpty()) {
                    List<String> labels = new ArrayList<>();
                    for (Object option : list) {
                        labels.add(pythonStr(((Map<?, ?>) option).get("label")));
                    }
                    line.append(" \u2014 options: ").append(String.join(" / ", labels));
                    if ("multi_select".equals(field.get("type"))) {
                        line.append(" (multiple allowed)");
                    }
                }
                parts.add(line.toString());
            }
            parts.add("");
            parts.add("Please reply with a value for each field.");
        } else if (!options.isEmpty()) {
            parts.add("");
            for (int index = 0; index < options.size(); index++) {
                parts.add("  " + (index + 1) + ". " + options.get(index));
            }
        }
        return String.join("\n", parts);
    }

    /** Frozen {@code _stable_message_id}: retried calls replace, not append. */
    static String stableRequestId(String toolCallId, String formattedMessage) {
        if (toolCallId != null && !toolCallId.isEmpty()) {
            return "clarification:" + toolCallId;
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(formattedMessage.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String hex = java.util.HexFormat.of().formatHex(digest);
            return "clarification:" + hex.substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static String normalizeClarificationType(Map<String, Object> args) {
        return pythonStr(argument(args, "clarification_type", "missing_info"));
    }

    private static Object argument(Map<String, Object> args, String key) {
        return args.get(key);
    }

    private static Object argument(Map<String, Object> args, String key, Object fallback) {
        return args.containsKey(key) ? args.get(key) : fallback;
    }

    private static boolean pythonTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return !text.isEmpty();
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0d;
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    private static String pythonStr(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Boolean bool) {
            return bool ? "True" : "False";
        }
        if (value instanceof Double || value instanceof Float) {
            return value.toString();
        }
        if (value instanceof java.math.BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return value.toString();
    }

    /** Byte length of the Python-equivalent {@code json.dumps(value, ensure_ascii=False)}. */
    private static int pythonJsonBytes(Object value) {
        return pythonJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private static String pythonJson(Object value) {
        StringBuilder builder = new StringBuilder();
        appendPythonJson(value, builder);
        return builder.toString();
    }

    private static void appendPythonJson(Object value, StringBuilder builder) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String text) {
            appendJsonString(text, builder);
        } else if (value instanceof Boolean bool) {
            builder.append(bool ? "true" : "false");
        } else if (value instanceof Number number) {
            builder.append(pythonStr(number));
        } else if (value instanceof List<?> list) {
            builder.append('[');
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) {
                    builder.append(", ");
                }
                appendPythonJson(list.get(index), builder);
            }
            builder.append(']');
        } else if (value instanceof Map<?, ?> map) {
            builder.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    builder.append(", ");
                }
                first = false;
                appendJsonString((String) entry.getKey(), builder);
                builder.append(": ");
                appendPythonJson(entry.getValue(), builder);
            }
            builder.append('}');
        } else {
            appendJsonString(value.toString(), builder);
        }
    }

    private static void appendJsonString(String text, StringBuilder builder) {
        builder.append('"');
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (character < 0x20) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        builder.append('"');
    }
}

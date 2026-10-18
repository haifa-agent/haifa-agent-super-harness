package io.haifa.agent.superharness.server.tool.web;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Defensive redaction for provider-controlled text (error bodies and exception messages) that can
 * echo request material such as an API key or the {@code Authorization} header back to the caller.
 *
 * <p>A tool result is projected into the model conversation and persisted in the thread state, so a
 * reflected secret would leak twice. Redaction therefore happens at the tool boundary, before the
 * text becomes part of a result, and never relies on the provider behaving well.
 */
final class ToolSecretRedactor {
    static final String REDACTED = "[REDACTED]";

    /** Matches a whole {@code Bearer <token>} sequence so the scheme name is removed as well. */
    private static final Pattern AUTHORIZATION_HEADER = Pattern.compile("(?i)bearer\\s+[^\\s\"',}]+");

    /** Keeps provider text useful for diagnosis without pasting an unbounded error document. */
    static final int MAX_TEXT_CHARACTERS = 512;

    private ToolSecretRedactor() {}

    /** Redacts every known secret and every Authorization header, then bounds and normalizes the text. */
    static String redact(String text, String... secrets) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String redacted = AUTHORIZATION_HEADER.matcher(text).replaceAll(Matcher.quoteReplacement(REDACTED));
        for (String secret : secrets) {
            if (secret != null && !secret.isBlank()) {
                redacted = redacted.replace(secret, REDACTED);
            }
        }
        redacted = redacted.replaceAll("\\p{Cntrl}", " ");
        if (redacted.length() > MAX_TEXT_CHARACTERS) {
            redacted = redacted.substring(0, MAX_TEXT_CHARACTERS);
        }
        return redacted.strip();
    }
}
